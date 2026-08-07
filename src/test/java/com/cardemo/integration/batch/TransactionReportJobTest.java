/*
 ******************************************************************
 * Program     : TransactionReportJobTest.java
 * Application : CardDemo
 * Type        : JUnit 5 integration test (Failsafe tier)
 * Function    : Launches the transaction report job against a real
 *               PostgreSQL 16 instance and a real LocalStack endpoint and
 *               asserts the job level contract of the legacy report: the
 *               three steps in procedure order, the inclusive date filter
 *               applied twice, twenty lines per page, 133 byte records,
 *               the control break on card number under an Account Total
 *               label, the concrete generation carried forward between
 *               steps, and the four preserved legacy defects.
 * Source      : app/jcl/TRANREPT.jcl, app/proc/TRANREPT.prc,
 *               app/ctl/REPROCT.ctl, app/cbl/CBTRN03C.cbl,
 *               app/cpy/CVTRA07Y.cpy @ 7756d89
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
package com.cardemo.integration.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobExecutionListener;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.cardemo.batch.jobs.TransactionReportJob;
import com.cardemo.batch.processors.TransactionReportProcessor;
import com.cardemo.model.entity.CardCrossReference;
import com.cardemo.model.entity.Transaction;
import com.cardemo.model.key.TransactionCategoryId;
import com.cardemo.repository.CardCrossReferenceRepository;
import com.cardemo.repository.TransactionCategoryRepository;
import com.cardemo.repository.TransactionRepository;
import com.cardemo.repository.TransactionTypeRepository;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Object;

/**
 * The batch tier's concrete assertions for the transaction report job.
 *
 * <h2>What it does</h2>
 *
 * <p>Launches the assembled job and asserts what only a real launch can reach: the three steps of
 * {@code app/proc/TRANREPT.prc} in the order that member declares them ({@code :21-22} STEP01R,
 * {@code :35} STEP05R, {@code :57} STEP10R, closed by {@code :79 // PEND}); the concrete generation the
 * backup step publishes being the one the sort step consumes; the geometry of the emitted report against
 * the {@code TRANREPT DD DCB=(LRECL=133,RECFM=FB,BLKSIZE=0)} of {@code :76}, independently corroborated by
 * {@code WS-BLANK-LINE PIC X(133)} at {@code app/cbl/CBTRN03C.cbl:133}; and the read loop of
 * {@code :170-206} in every arm it has.
 *
 * <p>The backup step reproduces the single control card of {@code app/ctl/REPROCT.ctl:15}, which is
 * {@code REPRO INFILE(FILEIN) OUTFILE(FILEOUT)} and the whole of that step's logic - there is no COBOL
 * program for it. The sort step reproduces {@code :44 SORT FIELDS=(TRAN-CARD-NUM,A)} and the
 * {@code INCLUDE COND} of {@code :45-46}, whose symbols are declared at {@code :39-42}.
 *
 * <p>That substitution is worth naming for what it is. {@code EXEC PGM=SORT} at {@code :35} is a separate
 * z/OS utility address space, and its Java replacement is an in-process
 * {@code java.util.Comparator} over the sixteen card-number bytes plus a predicate over the ten
 * processing-date bytes - <strong>no external sort process is spawned, and none may be</strong>. This is a
 * mechanism substitution and is asserted as one: nothing here claims the in-process sort is faster, because
 * the legacy system publishes no throughput figure to compare it against and inventing one would be
 * fabrication. The absence of process execution is also what discharges the first of the three risky
 * patterns this file is required to keep out - there is no {@code Runtime.exec}, no
 * {@code ProcessBuilder} and no script evaluation anywhere in it, just as there is no
 * {@code ObjectInputStream} deserialization and no string-concatenated SQL; the two queries below bind
 * every value through a {@code ?} placeholder.
 *
 * <h2>Verified locators</h2>
 *
 * <p>Every locator below was read on disk in this checkout rather than carried over from prose, and each is
 * the authority for an assertion in this class.
 *
 * <ul>
 *   <li><strong>{@code app/proc/TRANREPT.prc}</strong> - {@code :1} the procedure header, {@code :21-22}
 *       STEP01R with its control library, {@code :29} the 350-byte backup DCB, {@code :31} the
 *       {@code TRANSACT.BKUP(+1)} target, {@code :35} STEP05R, {@code :36-37} the SORTIN that is that same
 *       generation, {@code :39-42} the four sort symbols, {@code :44} the sort field, {@code :45-46} the
 *       inclusive {@code INCLUDE COND}, {@code :51} {@code DCB=(*.SORTIN)}, {@code :53} the
 *       {@code TRANSACT.DALY(+1)} target, {@code :57} STEP10R, {@code :63-72} its five input DDs,
 *       {@code :76} the 133-byte report DCB, {@code :78} the {@code TRANREPT(+1)} target and {@code :79}
 *       {@code // PEND}.</li>
 *   <li><strong>{@code app/ctl/REPROCT.ctl:15}</strong> - the one control card the backup step is.</li>
 *   <li><strong>{@code app/cbl/CBTRN03C.cbl}</strong> - {@code :127} the working-storage group,
 *       {@code :131-132} {@code WS-PAGE-SIZE PIC 9(03) COMP-3} with {@code VALUE 20}, {@code :133}
 *       {@code WS-BLANK-LINE PIC X(133)}, {@code :134} {@code WS-PAGE-TOTAL PIC S9(09)V99}, {@code :137}
 *       {@code WS-CURR-CARD-NUM PIC X(16)}, {@code :155} {@code ABCODE PIC S9(9) BINARY}, {@code :170-206}
 *       the read loop, {@code :173-174} the re-filter, {@code :177} the transfer, {@code :181-185} the
 *       control break, {@code :183} the sole {@code 1120-WRITE-ACCOUNT-TOTALS}, {@code :197} the end-of-data
 *       branch, {@code :200-201} the double count, {@code :208} the close that the transfer falls into,
 *       {@code :282-285} the page-break test, {@code :287-288} the accumulation, {@code :293}
 *       {@code 1110-WRITE-PAGE-TOTALS}, {@code :297} the only grand-total rollup, {@code :306}
 *       {@code 1120-WRITE-ACCOUNT-TOTALS}, {@code :318} {@code 1110-WRITE-GRAND-TOTALS}, {@code :324}
 *       {@code 1120-WRITE-HEADERS}, {@code :343} {@code 1111-WRITE-REPORT-REC}, {@code :361}
 *       {@code 1120-WRITE-DETAIL}, and {@code :629-630} {@code MOVE 999 TO ABCODE} then
 *       {@code CALL 'CEE3ABD'}.</li>
 *   <li><strong>{@code app/cpy/CVTRA07Y.cpy:48-66}</strong> - the report line group, of which {@code :48} is
 *       the 133-character rule line, {@code :51-54} the page totals, {@code :56-60} the account totals with
 *       the {@code 'Account Total'} literal on {@code :57-58}, and {@code :63-66} the grand totals.</li>
 *   <li><strong>{@code app/cpy/CVTRA05Y.cpy}</strong> - {@code :2} {@code RECLN = 350}, {@code :10}
 *       {@code TRAN-AMT PIC S9(09)V99}, {@code :17} {@code TRAN-PROC-TS PIC X(26)} and {@code :18} the
 *       closing 20-byte filler. {@code app/cpy/CVTRA03Y.cpy:5} declares {@code TRAN-TYPE} and
 *       {@code app/cpy/CVTRA04Y.cpy:5-7} the six-byte {@code TRAN-CAT-KEY}.</li>
 *   <li><strong>{@code app/jcl/DEFGDGB.jcl:37-39}</strong> and <strong>{@code app/jcl/REPTFILE.jcl:26-28}</strong>
 *       - the two conflicting retention declarations for the report base.</li>
 * </ul>
 *
 * <h2>Preserved behavioural defects, classified by severity</h2>
 *
 * <p>Four defects in {@code app/cbl/CBTRN03C.cbl} change what the report contains. Every one of them is
 * <strong>preserved rather than repaired</strong>, because behavioural parity is the contract and the frozen
 * corpus is the oracle; and every one carries a dedicated test, because the governing rule requires a test
 * for any non-trivial bug fix and that obligation binds just as hard when the decision is to keep the bug.
 * Remediation is therefore deliberately withheld here: repairing any of these is a parity-breaking change
 * that belongs to the root-owned decision log and traceability matrix, which this class references and never
 * writes.
 *
 * <ul>
 *   <li><strong>High</strong> - {@code :177}. The read loop of {@code :170-206} is a single COBOL sentence,
 *       so the {@code NEXT SENTENCE} on the out-of-window arm transfers past the period that terminates it
 *       on {@code :206} and lands on the close at {@code :208}. The <em>first</em> out-of-window record
 *       therefore abandons the entire report rather than being skipped, which is the opposite of the
 *       filter-and-continue idiom a reader expects. Normally unobservable, because the sort layer has
 *       already removed those records - which is precisely why
 *       {@code anOutOfWindowRecordDownstreamOfTheSortTerminatesTheReadLoop} injects one <em>downstream</em>
 *       of the sort to reach it deliberately.</li>
 *   <li><strong>High</strong> - {@code :183}. {@code 1120-WRITE-ACCOUNT-TOTALS} is performed at exactly that
 *       one site, inside the control-break arm, and the end-of-data branch at {@code :197} performs only the
 *       page and grand totals. For N distinct card numbers the report therefore carries N-1 account-total
 *       lines and the last card silently gets none; at N=1 it carries none at all. Asserted by
 *       {@code threeDistinctCardsEmitOnlyTwoAccountTotalLines} and
 *       {@code aSingleCardEmitsNoAccountTotalLineAtAll}. This is the same omitted-final-group defect class as
 *       the skipped last account in {@code app/cbl/CBACT04C.cbl}.</li>
 *   <li><strong>High</strong> - {@code :200-201}. That same end-of-data branch executes
 *       {@code ADD TRAN-AMT TO WS-PAGE-TOTAL WS-ACCOUNT-TOTAL} while the record area still holds the last
 *       record successfully read, whose amount was already accumulated at {@code :287}. The final
 *       transaction's amount is consequently counted twice into both totals, so the printed totals disagree
 *       with the sum of the printed detail lines. Asserted by
 *       {@code theLastAmountIsDoubleCountedIntoThePageAndGrandTotals}.</li>
 *   <li><strong>Medium</strong> - {@code :181-185} with {@code app/cpy/CVTRA07Y.cpy:57-58}. The break is
 *       taken on {@code WS-CURR-CARD-NUM}, declared {@code PIC X(16)} at {@code :137}, so it is a
 *       <em>card-number</em> break; the literal the group emits reads {@code 'Account Total'}. The
 *       arithmetic is right and the label misdescribes it, which misleads a reader of the report rather than
 *       corrupting it. Both halves are preserved together by
 *       {@code controlBreakFiresOnTheCardNumberUnderAnAccountTotalLabel} - one card number maps to one
 *       account often enough for the label to look correct, and nothing in the program enforces that.</li>
 * </ul>
 *
 * <h2>Legacy quirks logged and deliberately not repaired</h2>
 *
 * <ul>
 *   <li>{@code app/proc/TRANREPT.prc:1} is {@code //REPROC PROC}, so the procedure's <em>internal</em> name
 *       is {@code REPROC} while the member name an {@code EXEC PROC=TRANREPT} resolves is {@code TRANREPT} -
 *       and {@code app/proc/REPROC.prc:1} carries the same internal name. Severity: <strong>Medium</strong>.
 *       Logged, never fixed.</li>
 *   <li>{@code app/proc/TRANREPT.prc:39} declares {@code TRAN-CARD-NUM,263,16,ZD} - zoned decimal - for a
 *       field the copybook declares as {@code PIC X(16)}, and the sort at {@code :44} is nevertheless an
 *       ascending character ordering over those sixteen bytes. Severity: <strong>Medium</strong>.</li>
 *   <li>{@code app/cbl/CBTRN03C.cbl} gives two <em>different</em> paragraphs the same {@code 1110-} prefix:
 *       {@code 1110-WRITE-PAGE-TOTALS} at {@code :293} and {@code 1110-WRITE-GRAND-TOTALS} at {@code :318},
 *       with {@code 1120-WRITE-ACCOUNT-TOTALS} sitting between them at {@code :306}. Severity:
 *       <strong>Medium</strong>. The numbering is reproduced as found.</li>
 *   <li>{@code app/jcl/TRANREPT.jcl} declares {@code STEP05R} twice, at {@code :23} and {@code :37}. The
 *       procedure member is the authority this job is built from, and it declares the three steps once
 *       each.</li>
 *   <li>{@code V2__create_indexes.sql} names the processing-timestamp index {@code idx_transaction_proc_ts}.
 *       Where planning prose called it {@code idx_transaction_tran_proc_ts}, the authored migration governs
 *       and is what this class asserts against. Severity: <strong>Low</strong>, naming only.</li>
 * </ul>
 *
 * <h2>The injected fixed clock, which this test cannot do without</h2>
 *
 * <p>{@code TRAN-PROC-TS} is not carried by the daily input fixture: columns 305-330 of every record in
 * {@code app/data/ASCII/dailytran.txt} are blank, so the processing timestamp is produced at post time and
 * therefore comes entirely from the clock. The report then filters that value <strong>twice</strong>, and
 * both filters are inclusive at both ends:
 *
 * <ul>
 *   <li>at the sort layer, by the {@code INCLUDE COND} of {@code app/proc/TRANREPT.prc:45-46} over the
 *       symbols {@code TRAN-PROC-DT,305,10,CH}, {@code PARM-START-DATE,C'2022-01-01'} and
 *       {@code PARM-END-DATE,C'2022-07-06'} declared at {@code :40-42};</li>
 *   <li>again in the processor, at {@code app/cbl/CBTRN03C.cbl:173-174}, which tests
 *       {@code IF TRAN-PROC-TS (1:10) >= WS-START-DATE AND TRAN-PROC-TS (1:10) <= WS-END-DATE}.</li>
 * </ul>
 *
 * <p>A wall-clock time source would place every generated processing date far outside that window and the
 * report would come back <strong>empty through both filters</strong> - a green run asserting nothing.
 * Severity of that mistake: <strong>Blocker</strong>. The remedy is the harness's fixed clock, whose
 * instant sits inside the window; {@code reportIsNotEmptyUnderTheInjectedFixedClock} exists to prove it is
 * honoured. Nothing here calls a wall-clock method.
 *
 * <p>The comparison is a <strong>character comparison over the first ten characters</strong> of a
 * {@code CHAR(26)} value, never a parsed date, which is why {@code endBoundaryIsInclusiveLateInThatDay}
 * puts a late time of day on the last day of the window and still expects the record.
 *
 * <h2>How to run, build and test</h2>
 *
 * <p>{@code ./mvnw clean verify}. Failsafe 3.5.4 binds {@code src/test/java/com/cardemo/integration/**} at
 * {@code integration-test} and {@code verify} even though these classes keep the {@code Test} suffix, and
 * Surefire binds {@code .../unit/**} only and excludes this tree; a class moved out of this package matches
 * neither include set and would silently never run. <strong>A reachable Docker socket is a
 * prerequisite</strong>: the harness starts one PostgreSQL 16 container and one LocalStack container.
 *
 * <h2>Key configurations and defaults</h2>
 *
 * <dl>
 *   <dt>Profile</dt>
 *   <dd>{@code test}, activated by the harness, which also binds the datasource by injected connection
 *       details and the object store and queue endpoints from the running container. No address, port or
 *       credential appears in this source.</dd>
 *   <dt>Time</dt>
 *   <dd>One fixed UTC {@code java.time.Clock} published by the harness as the primary clock bean, pinned to
 *       an instant inside {@code 2022-01-01 … 2022-07-06}. Both window bounds are the values
 *       {@code app/proc/TRANREPT.prc:41-42} declares.</dd>
 *   <dt>Batch</dt>
 *   <dd>{@code spring.batch.job.enabled: false}, so nothing auto-launches and every launch here is
 *       explicit. Chunk size resolves through {@code carddemo.batch.tranrept.chunk-size} to
 *       {@code carddemo.batch.chunk-size}, which is 100 - a commit interval and a tunable, never a parity
 *       contract.</dd>
 *   <dt>Schema and seed</dt>
 *   <dd>The three Flyway migrations own both. {@code "transaction"} is seeded empty, so every test commits
 *       its own synthetic posted rows and the harness removes them again; the lookup relations the report
 *       resolves against are seeded and are used as found.</dd>
 *   <dt>Generations</dt>
 *   <dd>Three bases are touched: {@code TRANSACT.BKUP(+1)} at {@code :31}, {@code TRANSACT.DALY(+1)} at
 *       {@code :53} and {@code TRANREPT(+1)} at {@code :78}. Retention is <strong>resolved to 10</strong>
 *       because {@code app/jcl/DEFGDGB.jcl:38} declares {@code LIMIT(5)} for the report base while
 *       {@code app/jcl/REPTFILE.jcl:27} declares {@code LIMIT(10)} for the same base, and one lifecycle
 *       value has to be chosen. Severity of the source conflict: <strong>Medium</strong>.</dd>
 * </dl>
 *
 * <h2>Common failure modes and troubleshooting</h2>
 *
 * <dl>
 *   <dt>Every assertion about report content fails and the report is three lines long</dt>
 *   <dd>The batch tier resolved a wall-clock time rather than the injected clock, so every processing date
 *       fell outside the 2022 window and both filters removed every record. Three lines is the end-of-data
 *       closing block alone. Fix the time source, never the window.</dd>
 *   <dt>Container startup fails</dt>
 *   <dd>No Docker socket. This tier cannot be simulated; report the prerequisite rather than relaxing it.</dd>
 *   <dt>A test dependency will not resolve</dt>
 *   <dd>The Testcontainers 2.0.3 module rename. Only the prefixed artifact identifiers exist on that line,
 *       and the managed version is overridden by property rather than by a second bill-of-materials import.
 *       Severity: <strong>Blocker</strong>. It is already resolved in the build; do not re-open it.</dd>
 *   <dt>The build fails on an unused import or a raw type</dt>
 *   <dd>{@code -Xlint:all -Werror} at {@code release 25} is fatal by design. Remove the import; never relax
 *       the compiler configuration.</dd>
 *   <dt>A launch fails with instance-already-complete</dt>
 *   <dd>Two launches shared a job instance. Every launch here is stamped with the harness's per-test run
 *       identifier, so one test method launches one job once.</dd>
 * </dl>
 *
 * <h2>Scope, and what is deliberately not asserted here</h2>
 *
 * <p>This is a job and step level test. {@code com.cardemo.batch.processors.TransactionReportProcessor}
 * internals belong to {@code src/test/java/com/cardemo/unit/batch}, and the byte-level
 * {@code FILE STATUS IS: NNNN} rendering is already asserted there; re-testing either would duplicate
 * without adding evidence. No decider gating is asserted, because {@code app/jcl/TRANREPT.jcl} carries no
 * {@code COND} - the corpus-wide {@code COND=(0,NE)} sites are all in {@code app/jcl/CREASTMT.JCL} - and
 * asserting one would be invented control flow. No exit-code locator is claimed either:
 * {@code app/cbl/CBTRN03C.cbl} sets no {@code RETURN-CODE} at all, and its {@code :629-630} abend is
 * {@code MOVE 999 TO ABCODE} followed by {@code CALL 'CEE3ABD'}, which is a different mechanism from the
 * four-character CICS literal used by the online programs; conflating the two would be a
 * <strong>Blocker</strong>. No exact reject count is asserted anywhere in this package.
 *
 * <p><strong>Not available:</strong> there is no captured legacy {@code TRANREPT} dataset in this
 * repository - an exhaustive search for expected, baseline and golden artefacts returned dataset definition
 * job control and no captured data - so no expected-output baseline file is created here. What would be
 * needed is a 133-byte-per-line {@code TRANREPT} dataset from a real {@code CBTRN03C} run at a known input
 * state; a baseline produced by running this implementation would be circular and is forbidden. File status
 * {@code '35'} is likewise not available: neither the literal nor its response code occurs in
 * {@code app/cbl}, so no test for that path is invented.
 */
@DisplayName("Transaction report job against real PostgreSQL 16 and LocalStack")
class TransactionReportJobTest extends AbstractBatchIntegrationTest {

    /**
     * Creates the test instance.
     *
     * <p>Declared explicitly, and package private rather than public, so that construction is documented
     * rather than implied: JUnit builds one instance per test method, every collaborator arrives by injection
     * after construction, and there is consequently nothing for a constructor to do. Narrowing it from the
     * implicit public also keeps the class out of any caller's reach outside this package.
     */
    TransactionReportJobTest() {
        // Intentionally empty; every collaborator is injected after construction.
    }

    // =================================================================================================
    // Injected collaborators. Every one is an instance field: the parent harness documents a hard limit of
    // two static fields for this package, both of them containers, and nothing here widens it.
    // =================================================================================================

    /** The job under test, bound by bean name so no other assignable job can be injected in its place. */
    @Autowired
    @Qualifier("transactionReportJob")
    private Job transactionReportJob;

    /**
     * STEP10R alone, {@code app/proc/TRANREPT.prc:57}.
     *
     * <p>Injected so that the one arm of the read loop the assembled flow cannot reach - the transfer at
     * {@code app/cbl/CBTRN03C.cbl:177} - can be driven with a generation the test wrote itself. See
     * {@code launchGenerateStepOverSyntheticGeneration} for why that is the only route to it.
     */
    @Autowired
    @Qualifier("transactionReportGenerateStep")
    private Step transactionReportGenerateStep;

    /**
     * STEP05R, {@code app/proc/TRANREPT.prc:35}, injected as the production bean.
     *
     * <p>Injected for one reason the assembled flow cannot supply: a {@code SORTIN} generation carrying record
     * separators. STEP01R writes undelimited fixed blocks, so a separated object can only be planted, and the
     * refusal that finding F-012 added has to be driven against the production step to mean anything. See
     * {@code launchReportOverSeparatedBackupGeneration}.
     */
    @Autowired
    @Qualifier("transactionReportSortStep")
    private Step transactionReportSortStep;

    /** The relation STEP01R backs up; used to commit the synthetic posted rows each test needs. */
    @Autowired
    private TransactionRepository transactionRepository;

    /** The {@code CARDXREF} lookup of {@code 1500-A-LOOKUP-XREF}, and the source of the seeded card keys. */
    @Autowired
    private CardCrossReferenceRepository cardCrossReferenceRepository;

    /** The {@code TRANTYPE} lookup of {@code 1500-B-LOOKUP-TRANTYPE}, confirmed seeded before it is relied on. */
    @Autowired
    private TransactionTypeRepository transactionTypeRepository;

    /** The {@code TRANCATG} lookup of {@code 1500-C-LOOKUP-TRANCATG}, confirmed seeded before it is relied on. */
    @Autowired
    private TransactionCategoryRepository transactionCategoryRepository;

    /**
     * The repository the probe job is registered against.
     *
     * <p>Taken from the container rather than built here, so the probe shares the very job repository the
     * framework wired to the same datasource the containers provide.
     */
    @Autowired
    private JobRepository jobRepository;

    /** Reads the emitted generations back byte for byte, and writes the synthetic one. */
    @Autowired
    private S3Client s3Client;

    /** Used only to interrogate the index the date range relies on, always through bound parameters. */
    @Autowired
    private JdbcTemplate jdbcTemplate;

    // =================================================================================================
    // Bound configuration. Every value arrives from the same property the job binds, so a change to one
    // cannot leave this test asserting against a literal the application no longer uses.
    // =================================================================================================

    /** The bucket the three generations are written to. */
    @Value("${carddemo.aws.s3.batch-output-bucket}")
    private String batchOutputBucket;

    /** The {@code TRANSACT.BKUP} prefix, carrying the job's own default. */
    @Value("${carddemo.aws.s3.gdg-prefixes.transact-bkup:gdg/transact-bkup}")
    private String backupPrefix;

    /** The {@code TRANSACT.DALY} prefix, carrying the job's own default. */
    @Value("${carddemo.aws.s3.gdg-prefixes.transact-daly:gdg/transact-daly}")
    private String dailyPrefix;

    /** The {@code TRANREPT} prefix, carrying the job's own default. */
    @Value("${carddemo.aws.s3.gdg-prefixes.tranrept:gdg/tranrept}")
    private String reportPrefix;

    /** The single lifecycle value the two conflicting source declarations were resolved to. */
    @Value("${carddemo.aws.s3.gdg-retention-generations:10}")
    private int reportRetentionGenerations;

    // =================================================================================================
    // Contract values, every one traced to a locator. Instance fields, never static.
    // =================================================================================================

    /** {@code PARM-START-DATE,C'2022-01-01'}, {@code app/proc/TRANREPT.prc:41}. */
    private final String reportStartDate = "2022-01-01";

    /** {@code PARM-END-DATE,C'2022-07-06'}, {@code app/proc/TRANREPT.prc:42}. */
    private final String reportEndDate = "2022-07-06";

    /** The day before the inclusive lower bound; excluded by both filters. */
    private final String dayBeforeWindow = "2021-12-31";

    /** The day after the inclusive upper bound; excluded by both filters. */
    private final String dayAfterWindow = "2022-07-07";

    /** {@code WS-PAGE-SIZE PIC 9(03) COMP-3 VALUE 20}, {@code app/cbl/CBTRN03C.cbl:131-132}. */
    private final int linesPerPage = 20;

    /** {@code WS-BLANK-LINE PIC X(133)} at {@code :133}, and {@code LRECL=133} at {@code TRANREPT.prc:76}. */
    private final int reportLineLength = 133;

    /** {@code RECLN = 350}, {@code app/cpy/CVTRA05Y.cpy:2}, and {@code LRECL=350} at {@code TRANREPT.prc:29}. */
    private final int transactionRecordLength = 350;

    /**
     * Zero-based offset of {@code TRAN-CARD-NUM}, whose {@code SYMNAMES} entry at
     * {@code app/proc/TRANREPT.prc:39} is {@code TRAN-CARD-NUM,263,16,ZD} - one-based 263, so 262 here.
     */
    private final int cardNumberOffset = 262;

    /** Width of {@code TRAN-CARD-NUM PIC X(16)}, {@code app/cpy/CVTRA05Y.cpy}. */
    private final int cardNumberLength = 16;

    /** Width of {@code TRAN-ID PIC X(16)}, which the sort's tiebreak reads from offset zero. */
    private final int transactionIdLength = 16;

    /** The offset of the edited amount on every totals line: 13 plus 84, and 11 plus 86, both resolve here. */
    private final int totalAmountOffset = 97;

    /** {@code PIC +ZZZ,ZZZ,ZZZ.ZZ}, {@code app/cpy/CVTRA07Y.cpy:60} - one sign and fourteen mask positions. */
    private final int editedAmountWidth = 15;

    /** {@code app/cpy/CVTRA07Y.cpy:57-58} - {@code FILLER PIC X(13) VALUE 'Account Total'}. */
    private final String accountTotalLabel = "Account Total";

    /** {@code app/cpy/CVTRA07Y.cpy:51-52} - the page totals label. */
    private final String pageTotalLabel = "Page Total";

    /** {@code app/cpy/CVTRA07Y.cpy:63-64} - the grand totals label. */
    private final String grandTotalLabel = "Grand Total";

    /** The first line of every page, emitted by {@code 1120-WRITE-HEADERS} at {@code :324-326}. */
    private final String reportNameHeaderPrefix = "DALYREPT";

    /** STEP01R, {@code app/proc/TRANREPT.prc:21}. */
    private final String backupStepName = "transactionReportBackupStep";

    /** STEP05R, {@code app/proc/TRANREPT.prc:35}. */
    private final String sortStepName = "transactionReportSortStep";

    /** STEP10R, {@code app/proc/TRANREPT.prc:57}. */
    private final String generateStepName = "transactionReportGenerateStep";
    private final String categoryBalanceStepName = "transactionReportCategoryBalanceStep";

    /** Execution-context entry carrying the concrete {@code TRANSACT.BKUP} generation STEP01R created. */
    private final String backupObjectKeyEntry = "carddemo.tranrept.transact-bkup.objectKey";

    /** Execution-context entry carrying the concrete {@code TRANSACT.DALY} generation STEP05R created. */
    private final String dailyObjectKeyEntry = "carddemo.tranrept.transact-daly.objectKey";

    /** Execution-context entry carrying the concrete {@code TRANREPT} generation STEP10R created. */
    private final String reportObjectKeyEntry = "carddemo.tranrept.report.objectKey";

    /** Execution-context entry carrying the record count STEP01R wrote. */
    private final String backupRecordCountEntry = "carddemo.tranrept.transact-bkup.recordCount";

    /** Execution-context entry carrying the record count that survived the {@code INCLUDE COND}. */
    private final String dailyRecordCountEntry = "carddemo.tranrept.transact-daly.recordCount";

    /** Execution-context entry carrying the number of 133-byte lines STEP10R emitted. */
    private final String reportLineCountEntry = "carddemo.tranrept.report.lineCount";

    /** The object name every {@code TRANSACT.DALY} generation carries, {@code app/proc/TRANREPT.prc:53}. */
    private final String dailyObjectName = "TRANSACT.DALY";

    /** Width of the generation segment in a resolved object key. */
    private final int generationSegmentWidth = 19;

    /** A seeded {@code TRAN-TYPE}, {@code app/cpy/CVTRA03Y.cpy:5} - the copybook's unique naming exception. */
    private final String seededTypeCode = "01";

    /** A seeded {@code TRAN-CAT-CD}, {@code app/cpy/CVTRA04Y.cpy:7}, paired with the type code above. */
    private final Integer seededCategoryCode = Integer.valueOf(1);

    /** A type code no seeded row carries, used to prove a {@code TRANTYPE} miss is not tolerated. */
    private final String absentTypeCode = "99";

    /** A category code no seeded pair carries, used to prove a {@code TRANCATG} miss is not tolerated. */
    private final int absentCategoryCode = 9999;

    /** A sixteen-digit key that is deliberately absent from the cross reference relation. */
    private final String absentCardKey = "9999999999999999";

    /** The seeded cross-reference census, which is also the seeded card census. */
    private final int seededCrossReferenceCount = 50;

    /** {@code TRAN-SOURCE PIC X(10)}; a value the closed-enum trap would have rejected is deliberately avoided. */
    private final String probeSource = "SYNTHETIC ";

    /** {@code TRAN-DESC PIC X(100)}, carried verbatim onto no report line and used only to locate a record. */
    private final String probeDescription = "TRANSACTION REPORT PARITY PROBE";

    /** {@code TRAN-MERCHANT-ID PIC 9(09)}. */
    private final Long probeMerchantId = Long.valueOf(123_456_789L);

    /** {@code TRAN-MERCHANT-NAME PIC X(50)}. */
    private final String probeMerchantName = "PARITY PROBE MERCHANT";

    /** {@code TRAN-MERCHANT-CITY PIC X(50)}. */
    private final String probeMerchantCity = "PROBE CITY";

    /** {@code TRAN-MERCHANT-ZIP PIC X(10)}. */
    private final String probeMerchantZip = "0000000000";

    /** The name the probe job is registered under; never a production job name. */
    private final String probeJobName = "TRANREPT-STEP10R-PROBE";

    /** The generation ordinal the synthetic daily object is written under. */
    private final long syntheticGenerationOrdinal = 1L;

    // =================================================================================================
    // The three steps, and the geometry of what they emit.
    // =================================================================================================

    /** The procedure's step topology and the record geometry of the report it produces. */
    @Nested
    @DisplayName("Flow topology and report geometry")
    class FlowTopologyAndReportGeometry {

        /**
         * Creates the procedure topology group.
         *
         * <p>Declared explicitly for the same reason as the enclosing class: JUnit builds one
         * instance per test method and the enclosing instance supplies every collaborator, so the
         * body has nothing to do and says so.
         */
        FlowTopologyAndReportGeometry() {
            // Intentionally empty; the enclosing instance owns every collaborator.
        }

        /**
         * The three steps of {@code app/proc/TRANREPT.prc} run, once each, in the order the member declares them.
         *
         * <p>Purpose: pin the sequence itself. A pipeline that sorted before it backed up, or that ran a step twice,
         * would still produce a report - a plausible one - so the order has to be asserted rather than inferred from
         * the report's contents.
         */
        @Test
        @DisplayName("the three steps run once each in the order app/proc/TRANREPT.prc declares: STEP01R :21, "
                + "STEP05R :35, STEP10R :57")
        @Transactional(propagation = Propagation.NOT_SUPPORTED)
        void threeStepsRunOnceEachInProcedureOrder() {
            seedInWindowTransactionsOnDistinctCards(2);

            final JobExecution execution = launchTransactionReport();
            assertRunCompleted(execution);

            assertThat(stepNamesInExecutionOrder(execution))
                    .as("app/proc/TRANREPT.prc declares STEP01R at :21, STEP05R at :35 and STEP10R at :57 "
                            + "before // PEND at :79, so the flow reproduces exactly that sequence with no "
                            + "step repeated and none suppressed - app/jcl/TRANREPT.jcl carries no COND, so "
                            + "nothing gates them. The fourth is app/jcl/PRTCATBL.jcl, a separate member, "
                            + "and it follows the report rather than interleaving with it")
                    .containsExactly(
                            backupStepName, sortStepName, generateStepName, categoryBalanceStepName);
        }

        /**
         * The report carries content, which is the proof that the injected clock rather than a wall clock produced
         * the processing timestamp.
         *
         * <p>Purpose: this is the one assertion that fails loudly on the mistake the whole class is exposed to. The
         * record's processing date is derived from the pinned instant, both filters compare only the first ten
         * characters of it, and the window is the one the procedure's symbols declare - so a wall-clock instant
         * would empty the report through both filters and every other assertion here would be vacuously satisfied.
         */
        @Test
        @DisplayName("the report is NOT empty under the injected fixed clock, which is the whole reason the "
                + "clock is injected")
        @Transactional(propagation = Propagation.NOT_SUPPORTED)
        void reportIsNotEmptyUnderTheInjectedFixedClock() {
            final String processingDate = fixedClockProcessingDate();
            assertThat(processingDate)
                    .as("the harness pins one instant, and it has to fall inside the window declared by "
                            + "app/proc/TRANREPT.prc:41-42, or both filters would remove every record and "
                            + "this whole class would assert nothing")
                    .isGreaterThanOrEqualTo(reportStartDate)
                    .isLessThanOrEqualTo(reportEndDate);

            final String transactionId = probeTransactionId(1);
            seedTransaction(transactionId, firstSeededCardNumber(), new BigDecimal("1234.56"),
                    fixedClockProcessingTimestamp());

            final JobExecution execution = launchTransactionReport();
            assertRunCompleted(execution);

            final List<String> lines = reportLines(execution);
            assertThat(lines)
                    .as("a wall-clock time source would place the generated processing date outside the 2022 "
                            + "window and the report would come back empty through the INCLUDE COND of "
                            + "app/proc/TRANREPT.prc:45-46 and again through app/cbl/CBTRN03C.cbl:173-174")
                    .isNotEmpty();
            assertThat(linesStartingWith(lines, transactionId))
                    .as("1120-WRITE-DETAIL at app/cbl/CBTRN03C.cbl:361 opens its line with TRAN-ID, so the "
                            + "seeded record is identifiable on the report without echoing a card number")
                    .hasSize(1);
            assertThat(requiredContextLong(execution, reportLineCountEntry))
                    .as("STEP10R publishes the line count it wrote, and it must agree with the object")
                    .isEqualTo(lines.size());
        }

        /**
         * Every emitted record is exactly 133 bytes and the object carries no record delimiter.
         *
         * <p>Purpose: the report is a fixed-block dataset, not text. Its geometry is declared twice in the source -
         * by the DCB of {@code app/proc/TRANREPT.prc:76} and by {@code WS-BLANK-LINE PIC X(133)} at
         * {@code app/cbl/CBTRN03C.cbl:133} - and a line feed between records would break byte-level comparison
         * against a legacy dataset while leaving every line individually correct.
         */
        @Test
        @DisplayName("every emitted record is exactly 133 bytes with no record delimiter, matching "
                + "TRANREPT DD DCB=(LRECL=133,RECFM=FB,BLKSIZE=0) at app/proc/TRANREPT.prc:76")
        @Transactional(propagation = Propagation.NOT_SUPPORTED)
        void everyEmittedRecordIsExactlyOneHundredThirtyThreeBytesWithNoDelimiter() {
            seedInWindowTransactionsOnDistinctCards(3);

            final JobExecution execution = launchTransactionReport();
            assertRunCompleted(execution);

            final byte[] payload = objectBytes(batchOutputBucket,
                    requiredContextString(execution, reportObjectKeyEntry));
            final long lineCount = requiredContextLong(execution, reportLineCountEntry);

            assertThat(payload.length)
                    .as("RECFM=FB means fixed blocks, so the object is exactly the line count times the "
                            + "record length that app/cbl/CBTRN03C.cbl:133 declares as WS-BLANK-LINE PIC X(133)")
                    .isEqualTo((int) (lineCount * reportLineLength));
            for (final String line : fixedWidthRecords(payload, reportLineLength)) {
                assertThat(line.length())
                        .as("1111-WRITE-REPORT-REC writes FD-REPTFILE-REC, a fixed 133-character record")
                        .isEqualTo(reportLineLength);
            }
            for (final byte encoded : payload) {
                assertThat(encoded)
                        .as("a fixed block dataset carries no carriage return and no line feed; a delimiter "
                                + "would make the object longer than the line count times 133")
                        .isNotEqualTo((byte) '\r')
                        .isNotEqualTo((byte) '\n');
            }
        }

        /**
         * The instructed {@code app/jcl/PRTCATBL.jcl} step unloads {@code TCATBALF} and prints that unload.
         *
         * <p>Purpose: assert finding M-05 end to end. {@code TCATBALF.BKUP} was a catalogued generation base
         * declared in configuration with <b>no producer and no consumer anywhere in the target</b>, while the
         * traceability matrix claimed the member was mapped. This case proves the pair exists: one object
         * appears under the base at {@code LRECL=50} holding every row of the relation, and the report is
         * derived from <em>that object</em> rather than from the relation, which is what
         * {@code SORTIN DSN=...TCATBALF.BKUP(+1)} at {@code app/jcl/PRTCATBL.jcl:L44-L45} says.
         *
         * <p>The line geometry carries a preserved legacy divergence. The {@code OUTREC} field list at
         * {@code :L53-L56} totals 41 bytes into a {@code SORTOUT} declared {@code LRECL=40} at {@code :L61};
         * the declared record length wins, because it is the contract a consumer of the report reads. The
         * report is therefore asserted at exactly 40 bytes per line.
         */
        @Test
        @DisplayName("app/jcl/PRTCATBL.jcl:L29-L39 then :L43-L63 - the instructed print unloads TCATBALF to "
                + "TCATBALF.BKUP(+1) at LRECL=50 and prints THAT object at the declared LRECL=40")
        @Transactional(propagation = Propagation.NOT_SUPPORTED)
        void theInstructedCategoryBalancePrintUnloadsAndPrints() {
            final Long seeded = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM transaction_category_balance", Long.class);
            assertThat(seeded)
                    .as("the harness seeds app/data/ASCII/tcatbal.txt, or this case would assert nothing")
                    .isNotNull()
                    .isPositive();

            final Map<String, String> instructed = new HashMap<>(reportWindowParameters());
            instructed.put(TransactionReportJob.JOB_PARAMETER_PRINT_CATEGORY_BALANCES, "true");
            final JobExecution execution =
                    launchJob(transactionReportJob, runIdParameters(instructed));
            assertRunCompleted(execution);

            // The producer: one object under the base, holding the whole relation at 50 bytes a record.
            final String backupKey = requiredContextString(
                    execution, TransactionReportJob.CATEGORY_BALANCE_BACKUP_KEY_CONTEXT);
            assertThat(requiredContextLong(
                    execution, TransactionReportJob.CATEGORY_BALANCE_BACKUP_COUNT_CONTEXT))
                    .as("app/jcl/PRTCATBL.jcl:L32-L33 unloads the whole cluster")
                    .isEqualTo(seeded.longValue());
            final byte[] unloaded = objectBytes(batchOutputBucket, backupKey);
            assertThat(unloaded.length)
                    .as("DCB=(LRECL=50,RECFM=FB) at :L37 means fixed blocks with no delimiter")
                    .isEqualTo((int) (seeded.longValue() * 50));

            // The consumer: the report is the same row count, at the declared 40 bytes a line.
            final byte[] report = objectBytes(batchOutputBucket, requiredContextString(
                    execution, TransactionReportJob.CATEGORY_BALANCE_REPORT_KEY_CONTEXT));
            assertThat(report.length)
                    .as("DCB=(LRECL=40,RECFM=FB) at :L61 wins over the 41-byte OUTREC field list of "
                            + ":L53-L56, and RECFM=FB carries no delimiter")
                    .isEqualTo((int) (seeded.longValue() * 40));

            final List<String> lines = fixedWidthRecords(report, 40);
            final List<String> keys = new ArrayList<>(lines.size());
            for (final String line : lines) {
                // OUTREC FIELDS=(TRANCAT-ACCT-ID,X, TRANCAT-TYPE-CD,X, TRANCAT-CD,X, TRAN-CAT-BAL,EDIT=...)
                assertThat(line.charAt(11)).as("the first X of :L53 is one blank").isEqualTo(' ');
                assertThat(line.charAt(14)).as("the second X of :L54").isEqualTo(' ');
                assertThat(line.charAt(19)).as("the third X of :L55").isEqualTo(' ');
                assertThat(line.charAt(29))
                        .as("EDIT=(TTTTTTTTT.TT) at :L56 places the decimal point after nine digits")
                        .isEqualTo('.');
                assertThat(line.substring(32))
                        .as("the trailing filler is blanks, one fewer than the 9X of :L56 asks for")
                        .isBlank();
                keys.add(line.substring(0, 18));
            }
            assertThat(keys)
                    .as("SORT FIELDS=(TRANCAT-ACCT-ID,A,TRANCAT-TYPE-CD,A,TRANCAT-CD,A) at :L52 orders the "
                            + "report by the composite key ascending")
                    .isSorted();
        }
    }

    // =================================================================================================
    // The date filter, applied twice, inclusive at both ends, as a ten-character character comparison.
    // =================================================================================================

    /** The {@code INCLUDE COND} of {@code :45-46} and the re-filter of {@code CBTRN03C.cbl:173-174}. */
    @Nested
    @DisplayName("The doubly applied inclusive date filter")
    class TheDoublyAppliedInclusiveDateFilter {

        /**
         * Creates the date filter group.
         *
         * <p>Declared explicitly for the same reason as the enclosing class: JUnit builds one
         * instance per test method and the enclosing instance supplies every collaborator, so the
         * body has nothing to do and says so.
         */
        TheDoublyAppliedInclusiveDateFilter() {
            // Intentionally empty; the enclosing instance owns every collaborator.
        }

        /**
         * A record whose processing date is exactly the lower bound is reported.
         *
         * <p>Purpose: {@code GE} at {@code app/proc/TRANREPT.prc:45} makes the lower bound a member of the range.
         * An off-by-one here silently loses the first day of every report.
         */
        @Test
        @DisplayName("a record whose processing date is exactly PARM-START-DATE is INCLUDED - GE at "
                + "app/proc/TRANREPT.prc:45")
        @Transactional(propagation = Propagation.NOT_SUPPORTED)
        void startBoundaryIsInclusive() {
            final String transactionId = probeTransactionId(1);
            seedTransaction(transactionId, firstSeededCardNumber(), new BigDecimal("10.00"),
                    batchTimestamp(reportStartDate, "00.00.00.00"));

            final JobExecution execution = launchTransactionReport();
            assertRunCompleted(execution);

            assertThat(requiredContextLong(execution, dailyRecordCountEntry))
                    .as("INCLUDE COND=(TRAN-PROC-DT,GE,PARM-START-DATE,...) is greater-than-or-equal, so the "
                            + "lower bound is a member of the range rather than a strict predecessor")
                    .isOne();
            assertThat(linesStartingWith(reportLines(execution), transactionId))
                    .as("and app/cbl/CBTRN03C.cbl:173 re-applies the same inclusive test, so the record "
                            + "survives the processor as well as the sort")
                    .hasSize(1);
        }

        /**
         * A record late on the closing day of the window is reported, which fixes the comparison as a character
         * comparison over ten characters rather than a timestamp comparison.
         *
         * <p>Purpose: {@code TRAN-PROC-TS} is a twenty-six character value, and both filters look at its first ten
         * characters only - the sort through the {@code TRAN-PROC-DT,305,10,CH} symbol, the processor through the
         * reference modification {@code TRAN-PROC-TS (1:10)}. An implementation that parsed the value and compared
         * it against the end date as an instant would drop every record after midnight on the closing day.
         */
        @Test
        @DisplayName("a record late on the day of PARM-END-DATE is INCLUDED, proving the comparison is a "
                + "character comparison over TRAN-PROC-TS (1:10) and not a parsed timestamp")
        @Transactional(propagation = Propagation.NOT_SUPPORTED)
        void endBoundaryIsInclusiveLateInThatDay() {
            final String transactionId = probeTransactionId(1);
            seedTransaction(transactionId, firstSeededCardNumber(), new BigDecimal("10.00"),
                    batchTimestamp(reportEndDate, "23.59.59.99"));

            final JobExecution execution = launchTransactionReport();
            assertRunCompleted(execution);

            assertThat(requiredContextLong(execution, dailyRecordCountEntry))
                    .as("TRAN-PROC-DT is declared at app/proc/TRANREPT.prc:40 as offset 305 for ten "
                            + "characters of CH, so only the date part is compared and the time of day on the "
                            + "closing day cannot push a record out of an LE range")
                    .isOne();
            assertThat(linesStartingWith(reportLines(execution), transactionId))
                    .as("app/cbl/CBTRN03C.cbl:174 compares TRAN-PROC-TS (1:10), a ten-character reference "
                            + "modification, so the processor agrees with the sort")
                    .hasSize(1);
        }

        /**
         * A record one day before the lower bound is copied by the backup step and then removed by both filters.
         *
         * <p>Purpose: an inclusive bound has to be inclusive of exactly one day, and the unfiltered backup step has
         * to stay unfiltered - the filtering belongs to the sort, which is where the source puts it.
         */
        @Test
        @DisplayName("a record one day before PARM-START-DATE is EXCLUDED, and it is still backed up")
        @Transactional(propagation = Propagation.NOT_SUPPORTED)
        void theDayBeforeTheWindowIsExcluded() {
            assertRecordOutsideWindowIsBackedUpButNotReported(batchTimestamp(dayBeforeWindow, "23.59.59.99"));
        }

        /**
         * A record one day after the upper bound is copied by the backup step and then removed by both filters.
         *
         * <p>Purpose: the mirror of the lower bound, asserted separately because a predicate can be wrong at one end
         * and right at the other.
         */
        @Test
        @DisplayName("a record one day after PARM-END-DATE is EXCLUDED, and it is still backed up")
        @Transactional(propagation = Propagation.NOT_SUPPORTED)
        void theDayAfterTheWindowIsExcluded() {
            assertRecordOutsideWindowIsBackedUpButNotReported(batchTimestamp(dayAfterWindow, "00.00.00.00"));
        }

        /**
         * The window is applied twice and the two layers agree: the backup is unfiltered, the sort reduces it, and
         * the processor's re-filter removes nothing further.
         *
         * <p>Purpose: a duplicated predicate is a place where two implementations can drift apart. Asserting the
         * three counts together shows the second filter is a faithful repetition of the first rather than a second,
         * subtly different rule.
         */
        @Test
        @DisplayName("the filter is applied twice - once at the sort layer, once in the processor - and the "
                + "two layers agree on every record")
        @Transactional(propagation = Propagation.NOT_SUPPORTED)
        void bothFilterLayersAgreeOnEveryRecord() {
            final List<String> cards = seededCardNumbersAscending();
            final String firstInWindow = probeTransactionId(1);
            final String secondInWindow = probeTransactionId(2);
            seedTransaction(firstInWindow, cards.get(0), new BigDecimal("10.00"),
                    fixedClockProcessingTimestamp());
            seedTransaction(secondInWindow, cards.get(1), new BigDecimal("20.00"),
                    batchTimestamp(reportStartDate, "12.00.00.00"));
            seedTransaction(probeTransactionId(3), cards.get(2), new BigDecimal("30.00"),
                    batchTimestamp(dayBeforeWindow, "12.00.00.00"));
            seedTransaction(probeTransactionId(4), cards.get(3), new BigDecimal("40.00"),
                    batchTimestamp(dayAfterWindow, "12.00.00.00"));

            final JobExecution execution = launchTransactionReport();
            assertRunCompleted(execution);

            assertThat(requiredContextLong(execution, backupRecordCountEntry))
                    .as("STEP01R reproduces REPRO INFILE(FILEIN) OUTFILE(FILEOUT) from app/ctl/REPROCT.ctl:15, "
                            + "which is an unfiltered copy: every posted row reaches TRANSACT.BKUP(+1)")
                    .isEqualTo(4L);
            assertThat(requiredContextLong(execution, dailyRecordCountEntry))
                    .as("the first filter, the INCLUDE COND of app/proc/TRANREPT.prc:45-46, removes the two "
                            + "records outside the inclusive window")
                    .isEqualTo(2L);

            final List<String> lines = reportLines(execution);
            assertThat(detailLineCount(lines))
                    .as("the second filter, app/cbl/CBTRN03C.cbl:173-174, is applied to what the first "
                            + "already reduced, and the two agree - so no record is lost between them and "
                            + "none survives that should not have")
                    .isEqualTo(2L);
            assertThat(linesStartingWith(lines, firstInWindow)).hasSize(1);
            assertThat(linesStartingWith(lines, secondInWindow)).hasSize(1);
        }
    }

    // =================================================================================================
    // Pagination. WS-PAGE-SIZE is 20 at app/cbl/CBTRN03C.cbl:131-132 and the break test is
    // IF FUNCTION MOD(WS-LINE-COUNTER, WS-PAGE-SIZE) = 0 at :282.
    // =================================================================================================

    /** The page boundary, driven from one card so that no control break can be mistaken for a page break. */
    @Nested
    @DisplayName("Pagination at twenty lines per page")
    class PaginationAtTwentyLinesPerPage {

        /**
         * Creates the page boundary group.
         *
         * <p>Declared explicitly for the same reason as the enclosing class: JUnit builds one
         * instance per test method and the enclosing instance supplies every collaborator, so the
         * body has nothing to do and says so.
         */
        PaginationAtTwentyLinesPerPage() {
            // Intentionally empty; the enclosing instance owns every collaborator.
        }

        /**
         * Sixteen detail lines fill the first page exactly and trigger no page break.
         *
         * <p>Purpose: the negative half of the page boundary. Four header lines plus sixteen details is exactly
         * {@code WS-PAGE-SIZE} lines, and the break test at {@code app/cbl/CBTRN03C.cbl:282} runs before a detail is
         * written, so the page that fills the counter is not the page that breaks.
         */
        @Test
        @DisplayName("sixteen detail lines fill the first page exactly and trigger no mid-report page break: "
                + "four headers plus sixteen details is WS-PAGE-SIZE lines")
        @Transactional(propagation = Propagation.NOT_SUPPORTED)
        void sixteenDetailLinesFillTheFirstPageWithoutBreaking() {
            seedInWindowTransactionsOnOneCard(16);

            final JobExecution execution = launchTransactionReport();
            assertRunCompleted(execution);

            final List<String> lines = reportLines(execution);
            assertThat(headerBlockCount(lines))
                    .as("1120-WRITE-HEADERS at app/cbl/CBTRN03C.cbl:324 runs once for the first record and "
                            + "again only after a page break; sixteen details do not reach one")
                    .isOne();
            assertThat(linesStartingWith(lines, pageTotalLabel))
                    .as("the only 1110-WRITE-PAGE-TOTALS on this run is the one the end-of-data branch at "
                            + "app/cbl/CBTRN03C.cbl:202 performs")
                    .hasSize(1);
            assertThat(lines)
                    .as("four header lines, sixteen details, then the closing block of a page total, a rule "
                            + "line and a grand total")
                    .hasSize(4 + 16 + 3);
        }

        /**
         * The seventeenth detail line breaks the page: the page totals are written and the header block is
         * re-emitted.
         *
         * <p>Purpose: the positive half of the same boundary, driven from one card so that a control break cannot be
         * mistaken for a page break. The page size is the source's twenty, not a number chosen here.
         */
        @Test
        @DisplayName("the seventeenth detail line crosses WS-PAGE-SIZE and forces a page break: the page "
                + "totals are written and the four headers are re-emitted")
        @Transactional(propagation = Propagation.NOT_SUPPORTED)
        void theSeventeenthDetailLineBreaksThePageAtExactlyTwentyLines() {
            seedInWindowTransactionsOnOneCard(17);

            final JobExecution execution = launchTransactionReport();
            assertRunCompleted(execution);

            final List<String> lines = reportLines(execution);
            assertThat(headerBlockCount(lines))
                    .as("the break at app/cbl/CBTRN03C.cbl:282-285 performs 1110-WRITE-PAGE-TOTALS and then "
                            + "1120-WRITE-HEADERS, so a second page opens with its own four-line header block")
                    .isEqualTo(2L);
            assertThat(linesStartingWith(lines, pageTotalLabel))
                    .as("one page total closes page one and one closes the report, which is what "
                            + "WS-PAGE-SIZE VALUE 20 at app/cbl/CBTRN03C.cbl:131-132 produces once the "
                            + "counter reaches a multiple of twenty")
                    .hasSize(2);
            assertThat(lines)
                    .as("four headers, sixteen details, a two-line page total block, four headers again, the "
                            + "seventeenth detail, then the three-line closing block")
                    .hasSize(4 + 16 + 2 + 4 + 1 + 3);
            assertThat(linesPerPage)
                    .as("the page size is the source's, not this test's: WS-PAGE-SIZE PIC 9(03) COMP-3 at "
                            + "app/cbl/CBTRN03C.cbl:131 with VALUE 20 at :132")
                    .isEqualTo(20);
        }
    }

    // =================================================================================================
    // The four preserved legacy defects. Each one is behaviour, not a bug to be repaired, and each one
    // carries a test because Rule 1 clause B requires a test for any non-trivial bug fix - and preserving
    // a defect deliberately is the same obligation read the other way.
    // =================================================================================================

    /** The control break, the missing final account total, the end-of-data double count and the transfer. */
    @Nested
    @DisplayName("Preserved legacy defects")
    class PreservedLegacyDefects {

        /**
         * Creates the preserved defect group.
         *
         * <p>Declared explicitly for the same reason as the enclosing class: JUnit builds one
         * instance per test method and the enclosing instance supplies every collaborator, so the
         * body has nothing to do and says so.
         */
        PreservedLegacyDefects() {
            // Intentionally empty; the enclosing instance owns every collaborator.
        }

        /**
         * The control break fires on the card number while the line it emits is labelled {@code Account Total}.
         *
         * <p>Purpose: preserved defect one of four. The break variable is {@code WS-CURR-CARD-NUM} and the value it
         * is compared against is {@code TRAN-CARD-NUM}, so the grouping is by card; the label comes from a copybook
         * that says account. Both are behaviour: correcting either the key or the wording would change what the
         * report groups or what it claims to group, and the parity contract is measured on both.
         */
        @Test
        @DisplayName("DEFECT 1 of 4: the control break fires on the CARD NUMBER while the emitted label reads "
                + "'Account Total' - app/cbl/CBTRN03C.cbl:181-185 with app/cpy/CVTRA07Y.cpy:57-58")
        @Transactional(propagation = Propagation.NOT_SUPPORTED)
        void controlBreakFiresOnTheCardNumberUnderAnAccountTotalLabel() {
            final List<String> cards = seededCardNumbersAscending();
            seedTransaction(probeTransactionId(1), cards.get(0), new BigDecimal("100.00"),
                    fixedClockProcessingTimestamp());
            seedTransaction(probeTransactionId(2), cards.get(0), new BigDecimal("25.00"),
                    fixedClockProcessingTimestamp());
            seedTransaction(probeTransactionId(3), cards.get(1), new BigDecimal("200.00"),
                    fixedClockProcessingTimestamp());

            final JobExecution execution = launchTransactionReport();
            assertRunCompleted(execution);

            final List<String> totals = linesStartingWith(reportLines(execution), accountTotalLabel);
            assertThat(totals)
                    .as("two cards were reported and the break variable is WS-CURR-CARD-NUM PIC X(16) at "
                            + "app/cbl/CBTRN03C.cbl:137, compared against TRAN-CARD-NUM at :181, so exactly "
                            + "one break occurs - on the change of card, not of account")
                    .hasSize(1);
            assertThat(totals.getFirst())
                    .as("the label is the thirteen-character FILLER of app/cpy/CVTRA07Y.cpy:57-58 followed "
                            + "by the eighty-four dot leader of :59; the wording says account while the key "
                            + "is the card, and both are preserved exactly")
                    .startsWith(accountTotalLabel + ".".repeat(84));
            assertThat(editedTotalOf(totals.getFirst()))
                    .as("the broken group is the two transactions that shared the first card, so its total "
                            + "is their sum - which is what proves the break key is the card number and not "
                            + "something that would have grouped all three rows together")
                    .isEqualByComparingTo(new BigDecimal("125.00"));
        }

        /**
         * For three distinct card numbers only two {@code Account Total} lines are emitted.
         *
         * <p>Purpose: preserved defect two of four. {@code 1120-WRITE-ACCOUNT-TOTALS} is performed from exactly one
         * site, inside the control-break branch, so the closing total of the final group is never written. The
         * amounts prove which groups did close, and the detail count proves nothing was dropped except that one
         * line.
         */
        @Test
        @DisplayName("DEFECT 2 of 4: for N distinct card numbers only N-1 'Account Total' lines are emitted, "
                + "because 1120-WRITE-ACCOUNT-TOTALS is performed at exactly one site, :183")
        @Transactional(propagation = Propagation.NOT_SUPPORTED)
        void threeDistinctCardsEmitOnlyTwoAccountTotalLines() {
            final List<String> cards = seededCardNumbersAscending();
            seedTransaction(probeTransactionId(1), cards.get(0), new BigDecimal("100.00"),
                    fixedClockProcessingTimestamp());
            seedTransaction(probeTransactionId(2), cards.get(1), new BigDecimal("200.00"),
                    fixedClockProcessingTimestamp());
            seedTransaction(probeTransactionId(3), cards.get(2), new BigDecimal("300.00"),
                    fixedClockProcessingTimestamp());

            final JobExecution execution = launchTransactionReport();
            assertRunCompleted(execution);

            final List<String> lines = reportLines(execution);
            final List<String> totals = linesStartingWith(lines, accountTotalLabel);
            assertThat(totals)
                    .as("the end-of-data branch at app/cbl/CBTRN03C.cbl:197-203 performs only "
                            + "1110-WRITE-PAGE-TOTALS and 1110-WRITE-GRAND-TOTALS, never "
                            + "1120-WRITE-ACCOUNT-TOTALS, so the last card never receives its own total line: "
                            + "N-1 lines for N cards, preserved and not repaired")
                    .hasSize(2);
            assertThat(editedTotalOf(totals.get(0)))
                    .as("the first break closes the first card's group")
                    .isEqualByComparingTo(new BigDecimal("100.00"));
            assertThat(editedTotalOf(totals.get(1)))
                    .as("the second break closes the second card's group")
                    .isEqualByComparingTo(new BigDecimal("200.00"));
            assertThat(detailLineCount(lines))
                    .as("all three cards are still reported in detail; it is only the closing total of the "
                            + "last group that the source never writes")
                    .isEqualTo(3L);
        }

        /**
         * A single card emits no {@code Account Total} line at all.
         *
         * <p>Purpose: the same defect at its boundary. With one group there is no second break to close the first,
         * and the first-time guard suppresses the only break there is, so N-1 is zero rather than one.
         */
        @Test
        @DisplayName("DEFECT 2 of 4, the N=1 case: a single card emits ZERO 'Account Total' lines")
        @Transactional(propagation = Propagation.NOT_SUPPORTED)
        void aSingleCardEmitsNoAccountTotalLineAtAll() {
            seedInWindowTransactionsOnOneCard(2);

            final JobExecution execution = launchTransactionReport();
            assertRunCompleted(execution);

            final List<String> lines = reportLines(execution);
            assertThat(linesStartingWith(lines, accountTotalLabel))
                    .as("the guard at app/cbl/CBTRN03C.cbl:182 suppresses the first break because "
                            + "WS-FIRST-TIME is still 'Y', and no later break occurs, so N-1 is zero")
                    .isEmpty();
            assertThat(detailLineCount(lines))
                    .as("both records are nonetheless reported")
                    .isEqualTo(2L);
        }

        /**
         * At end of data the last record's amount is added a second time, to the page total and through it to the
         * grand total.
         *
         * <p>Purpose: preserved defect three of four, and the reason a single record is used: with one record the
         * expected total under the defect is exactly twice the amount posted, so the assertion cannot be satisfied
         * by an accumulation that merely happens to be wrong by some other amount.
         */
        @Test
        @DisplayName("DEFECT 3 of 4: at end of data the LAST record's amount is added a second time to both "
                + "the page total and the account total - app/cbl/CBTRN03C.cbl:200-201")
        @Transactional(propagation = Propagation.NOT_SUPPORTED)
        void theLastAmountIsDoubleCountedIntoThePageAndGrandTotals() {
            seedTransaction(probeTransactionId(1), firstSeededCardNumber(), new BigDecimal("1234.56"),
                    fixedClockProcessingTimestamp());

            final JobExecution execution = launchTransactionReport();
            assertRunCompleted(execution);

            final List<String> lines = reportLines(execution);
            final BigDecimal doubleCounted = new BigDecimal("2469.12");

            assertThat(editedTotalOf(onlyLineStartingWith(lines, pageTotalLabel)))
                    .as("the single record contributed 1234.56 at app/cbl/CBTRN03C.cbl:287-288, and the "
                            + "end-of-data branch at :200-201 adds TRAN-AMT again from a record area that "
                            + "still holds the last record successfully read, so the page total is twice the "
                            + "amount that was actually posted. Preserved, not corrected")
                    .isEqualByComparingTo(doubleCounted);
            assertThat(editedTotalOf(onlyLineStartingWith(lines, grandTotalLabel)))
                    .as("1110-WRITE-PAGE-TOTALS rolls the inflated page total into WS-GRAND-TOTAL at :297, "
                            + "which is the only grand-total rollup in the program, so the defect reaches the "
                            + "grand total too")
                    .isEqualByComparingTo(doubleCounted);
        }

        /**
         * An out-of-window record placed downstream of the sort terminates the whole read loop rather than being
         * skipped.
         *
         * <p>Purpose: preserved defect four of four, and the distinction that a per-item filter would silently lose.
         * The record after the out-of-window one is never read and the closing block is never emitted, which is what
         * separates a transfer out of the loop from a continue. See
         * {@code launchGenerateStepOverSyntheticGeneration} for why the record has to be injected downstream of the
         * sort to reach this arm at all.
         */
        @Test
        @DisplayName("DEFECT 4 of 4: NEXT SENTENCE at app/cbl/CBTRN03C.cbl:177 transfers past the period on "
                + ":206, so the FIRST out-of-window record terminates the whole read loop")
        @Transactional(propagation = Propagation.NOT_SUPPORTED)
        void anOutOfWindowRecordDownstreamOfTheSortTerminatesTheReadLoop() {
            final List<String> cards = seededCardNumbersAscending();
            final String reportedId = probeTransactionId(1);
            final String terminatingId = probeTransactionId(2);
            final String unreachedId = probeTransactionId(3);

            final JobExecution execution = launchGenerateStepOverSyntheticGeneration(List.of(
                    syntheticTransactionRecord(reportedId, seededTypeCode, seededCategoryCode.intValue(),
                            cards.get(0), new BigDecimal("11.11"), fixedClockProcessingTimestamp()),
                    syntheticTransactionRecord(terminatingId, seededTypeCode, seededCategoryCode.intValue(),
                            cards.get(1), new BigDecimal("22.22"),
                            batchTimestamp(dayAfterWindow, "00.00.00.00")),
                    syntheticTransactionRecord(unreachedId, seededTypeCode, seededCategoryCode.intValue(),
                            cards.get(2), new BigDecimal("33.33"), fixedClockProcessingTimestamp())));

            assertThat(execution.getStatus())
                    .as("the transfer leaves the loop and falls into 9000-TRANFILE-CLOSE at :208, which is an "
                            + "orderly end of the step and not a failure")
                    .isEqualTo(BatchStatus.COMPLETED);

            final List<String> lines = reportLines(execution);
            assertThat(linesStartingWith(lines, reportedId))
                    .as("the record before the out-of-window one is reported normally")
                    .hasSize(1);
            assertThat(linesStartingWith(lines, unreachedId))
                    .as("a per-item skip - Spring Batch's usual filter idiom of returning null from an "
                            + "ItemProcessor and continuing - would have reported this record. COBOL "
                            + "NEXT SENTENCE transfers to the statement after the next period, the sentence "
                            + "containing :177 begins at :170 and its period is on :206 END-PERFORM, so "
                            + "control leaves the loop entirely and this record is never read")
                    .isEmpty();
            assertThat(linesStartingWith(lines, terminatingId))
                    .as("nor is the out-of-window record itself reported")
                    .isEmpty();
            assertThat(linesStartingWith(lines, pageTotalLabel))
                    .as("the end-of-data branch at :197-203 sits inside the sentence the transfer jumped out "
                            + "of, so no page total closes an abandoned report")
                    .isEmpty();
            assertThat(linesStartingWith(lines, grandTotalLabel))
                    .as("and no grand total either - the closing block is emitted only when the driving READ "
                            + "reaches end of data")
                    .isEmpty();
            assertThat(lines)
                    .as("exactly the four-line header block of 1120-WRITE-HEADERS plus the single detail line "
                            + "that preceded the transfer")
                    .hasSize(5);
        }
    }

    // =================================================================================================
    // Boundary and hostile input at the reader and writer boundaries. Rule 1 clause B requires every
    // boundary and every empty case to be handled explicitly rather than assumed away.
    // =================================================================================================

    /** An empty range, and the three lookups the report resolves on every record. */
    @Nested
    @DisplayName("Boundary and hostile input")
    class BoundaryAndHostileInput {

        /**
         * Creates the boundary and hostile input group.
         *
         * <p>Declared explicitly for the same reason as the enclosing class: JUnit builds one
         * instance per test method and the enclosing instance supplies every collaborator, so the
         * body has nothing to do and says so.
         */
        BoundaryAndHostileInput() {
            // Intentionally empty; the enclosing instance owns every collaborator.
        }

        /**
         * An empty in-window set still emits the end-of-data closing block, with both totals signed.
         *
         * <p>Purpose: the empty case, handled explicitly rather than assumed away. Nothing to report is not nothing
         * to emit: the driving read reaches end of data immediately, the end-of-data branch runs once, and the
         * mandatory sign of the edited picture prints even on a fully suppressed zero.
         */
        @Test
        @DisplayName("an empty in-window set still emits the end-of-data closing block, and both totals "
                + "render with the mandatory sign of PIC +ZZZ,ZZZ,ZZZ.ZZ")
        @Transactional(propagation = Propagation.NOT_SUPPORTED)
        void anEmptyInWindowSetStillEmitsTheClosingBlock() {
            final JobExecution execution = launchTransactionReport();
            assertRunCompleted(execution);

            assertThat(requiredContextLong(execution, backupRecordCountEntry))
                    .as("the relation is seeded empty by the migrations, so REPRO copies nothing and reports "
                            + "an empty copy rather than failing")
                    .isZero();
            assertThat(requiredContextLong(execution, dailyRecordCountEntry))
                    .as("and the INCLUDE COND has nothing to include")
                    .isZero();

            final List<String> lines = reportLines(execution);
            assertThat(lines)
                    .as("the driving READ reaches end of data immediately, so the branch at "
                            + "app/cbl/CBTRN03C.cbl:197-203 runs once: 1110-WRITE-PAGE-TOTALS emits its total "
                            + "and the rule line of app/cpy/CVTRA07Y.cpy:48, then 1110-WRITE-GRAND-TOTALS "
                            + "emits one more")
                    .hasSize(3);
            assertThat(lines.get(0)).startsWith(pageTotalLabel);
            assertThat(lines.get(1))
                    .as("TRANSACTION-HEADER-2 is PIC X(133) VALUE ALL '-' at app/cpy/CVTRA07Y.cpy:48")
                    .isEqualTo("-".repeat(reportLineLength));
            assertThat(lines.get(2)).startsWith(grandTotalLabel);
            assertThat(lines.get(0).charAt(totalAmountOffset))
                    .as("PIC +ZZZ,ZZZ,ZZZ.ZZ carries a MANDATORY sign, so a zero total still prints one")
                    .isEqualTo('+');
            assertThat(editedTotalOf(lines.get(0)))
                    .as("a fully suppressed edited field is zero, compared by value and never by scale")
                    .isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(editedTotalOf(lines.get(2)))
                    .as("and the grand total of an empty report is zero as well")
                    .isEqualByComparingTo(BigDecimal.ZERO);
        }

        /**
         * The lookup keys every record resolves against are seeded, and the keys the miss cases use are genuinely
         * absent.
         *
         * <p>Purpose: a precondition, asserted rather than assumed, so that the three abend tests cannot pass for
         * the wrong reason. A miss caused by a fixture gap and a miss caused by a genuinely absent row are
         * indistinguishable from the abend alone.
         */
        @Test
        @DisplayName("the TRANTYPE and TRANCATG keys the report resolves on every record are seeded, so a "
                + "lookup miss in this tier is a genuine absence and never a fixture gap")
        @Transactional(propagation = Propagation.NOT_SUPPORTED)
        void theLookupKeysEveryRecordResolvesAgainstAreSeeded() {
            assertThat(transactionTypeRepository.findById(seededTypeCode))
                    .as("1500-B-LOOKUP-TRANTYPE reads TRANTYPE by TRAN-TYPE, the field name "
                            + "app/cpy/CVTRA03Y.cpy:5 declares, and the seed carries this code")
                    .isPresent();
            assertThat(transactionCategoryRepository
                            .findById(new TransactionCategoryId(seededTypeCode, seededCategoryCode)))
                    .as("1500-C-LOOKUP-TRANCATG assembles the six-byte TRAN-CAT-KEY of "
                            + "app/cpy/CVTRA04Y.cpy:5-7 from the type code and the category code, in that "
                            + "copybook field order")
                    .isPresent();
            assertThat(transactionTypeRepository.findById(absentTypeCode))
                    .as("and the code the miss cases use is genuinely absent")
                    .isEmpty();
            assertThat(transactionCategoryRepository
                            .findById(new TransactionCategoryId(seededTypeCode, Integer.valueOf(absentCategoryCode))))
                    .as("as is the composite key they use; no row is added to make either miss happen")
                    .isEmpty();
        }

        /**
         * An absent cross-reference row abends the report instead of being tolerated.
         *
         * <p>Purpose: the report resolves an account identifier through the cross reference on every control break,
         * and this program has no scoped arm that treats a record-not-found status as success. A tolerant
         * implementation would emit a report with a blank or invented account identifier, which is worse than
         * failing.
         */
        @Test
        @DisplayName("a CARDXREF record that is absent abends the report rather than being tolerated: "
                + "CBTRN03C has no '00' OR '23' guard anywhere")
        @Transactional(propagation = Propagation.NOT_SUPPORTED)
        void anAbsentCrossReferenceRecordAbendsTheReport() {
            final JobExecution execution = launchGenerateStepOverSyntheticGeneration(List.of(
                    syntheticTransactionRecord(probeTransactionId(1), seededTypeCode,
                            seededCategoryCode.intValue(), absentCardKey, new BigDecimal("10.00"),
                            fixedClockProcessingTimestamp())));

            assertThat(execution.getStatus())
                    .as("1500-A-LOOKUP-XREF ends in 9999-ABEND-PROGRAM, which is MOVE 999 TO ABCODE at "
                            + "app/cbl/CBTRN03C.cbl:629 followed by CALL 'CEE3ABD' at :630 - a language "
                            + "environment abend, not a return code the program sets")
                    .isEqualTo(BatchStatus.FAILED);
            assertFailureChainCarries(execution, "FatalProcessingException", "INVALID CARD NUMBER",
                    "the three scoped sites where a record-not-found status is success are "
                            + "app/cbl/CBTRN02C.cbl:481, app/cbl/CBACT04C.cbl:422 and the CBSTM03B call "
                            + "sites in app/cbl/CBSTM03A.CBL - none of them is in CBTRN03C, so this miss is "
                            + "fatal and nothing swallows it");
        }

        /**
         * An absent transaction type abends the report.
         *
         * <p>Purpose: the second of the three per-record lookups, asserted separately because each has its own
         * invalid-key arm and its own literal.
         */
        @Test
        @DisplayName("a TRANTYPE record that is absent abends the report")
        @Transactional(propagation = Propagation.NOT_SUPPORTED)
        void anAbsentTransactionTypeAbendsTheReport() {
            final JobExecution execution = launchGenerateStepOverSyntheticGeneration(List.of(
                    syntheticTransactionRecord(probeTransactionId(1), absentTypeCode,
                            seededCategoryCode.intValue(), firstSeededCardNumber(), new BigDecimal("10.00"),
                            fixedClockProcessingTimestamp())));

            assertThat(execution.getStatus()).isEqualTo(BatchStatus.FAILED);
            assertFailureChainCarries(execution, "FatalProcessingException", "INVALID TRANSACTION TYPE",
                    "1500-B-LOOKUP-TRANTYPE runs for every record, after the control-break lookup, and its "
                            + "INVALID KEY arm ends in the abend");
        }

        /**
         * An abended generate step leaves no {@code TRANREPT} generation catalogued.
         *
         * <p>Purpose: {@code app/proc/TRANREPT.prc} STEP10R declares {@code //TRANREPT DD
         * DISP=(NEW,CATLG,DELETE)}, whose third positional sub-parameter is the <b>abnormal-termination</b>
         * disposition. A step that abends therefore leaves nothing on the base.
         *
         * <p>Before this was honoured, an abended generate step left {@code TRANREPT} behind at <b>0 bytes</b>
         * and it survived the failed restart as well. That is worse than it sounds: a consumer resolving
         * {@code TRANREPT(0)} received an empty report and could not distinguish "no transactions matched the
         * requested window" from "the job abended", and those two situations demand opposite responses - accept
         * the result, or re-drive the run.
         *
         * <p>The writer opens its object before the first record is processed, so the object exists by the time
         * the per-record lookup abends; nothing about the failure prevents its creation, which is why the
         * disposition has to be reproduced explicitly rather than relying on the object never being made.
         */
        @Test
        @DisplayName("an abended generate step leaves NO TRANREPT generation catalogued, reproducing the "
                + "abnormal-termination disposition DELETE of app/proc/TRANREPT.prc STEP10R")
        @Transactional(propagation = Propagation.NOT_SUPPORTED)
        void anAbendedGenerateStepLeavesNoReportGenerationCatalogued() {
            final JobExecution execution = launchGenerateStepOverSyntheticGeneration(List.of(
                    syntheticTransactionRecord(probeTransactionId(1), absentTypeCode,
                            seededCategoryCode.intValue(), firstSeededCardNumber(), new BigDecimal("10.00"),
                            fixedClockProcessingTimestamp())));

            assertThat(execution.getStatus())
                    .as("the precondition of this case is a genuinely abended generate step")
                    .isEqualTo(BatchStatus.FAILED);
            assertThat(objectKeysUnder(reportPrefix))
                    .as("DISP=(NEW,CATLG,DELETE) deletes the new generation on abnormal termination, so an "
                            + "empty or fragmentary TRANREPT can never be catalogued - and a 0-byte one is "
                            + "indistinguishable from a legitimately empty report")
                    .isEmpty();
        }

        /**
         * An absent transaction category composite key abends the report.
         *
         * <p>Purpose: the third lookup, and the one whose key is composite - the type code resolves and only the
         * pair misses, so the arm this reaches is not the one the previous test reaches.
         */
        @Test
        @DisplayName("a TRANCATG composite key that is absent abends the report")
        @Transactional(propagation = Propagation.NOT_SUPPORTED)
        void anAbsentTransactionCategoryAbendsTheReport() {
            final JobExecution execution = launchGenerateStepOverSyntheticGeneration(List.of(
                    syntheticTransactionRecord(probeTransactionId(1), seededTypeCode, absentCategoryCode,
                            firstSeededCardNumber(), new BigDecimal("10.00"),
                            fixedClockProcessingTimestamp())));

            assertThat(execution.getStatus()).isEqualTo(BatchStatus.FAILED);
            assertFailureChainCarries(execution, "FatalProcessingException", "INVALID TRAN CATG KEY",
                    "the type code resolves and only the composite key misses, which is the arm "
                            + "1500-C-LOOKUP-TRANCATG guards separately from 1500-B");
        }

        /**
         * An inverted window is refused before any step runs, and nothing is written.
         *
         * <p>Purpose: an inverted range is an invalid request rather than an empty report, and the refusal names the
         * offending parameter. Asserting that no generation exists afterwards is what proves the refusal happened
         * before the first step rather than inside it.
         */
        @Test
        @DisplayName("a window whose start is after its end is refused before any step runs")
        @Transactional(propagation = Propagation.NOT_SUPPORTED)
        void aWindowWhoseStartIsAfterItsEndIsRefused() {
            final Job job = transactionReportJob;
            final JobParameters inverted = runIdParameters(Map.of(
                    TransactionReportProcessor.START_DATE_JOB_PARAMETER, reportEndDate,
                    TransactionReportProcessor.END_DATE_JOB_PARAMETER, reportStartDate));

            assertThatThrownBy(() -> launchJob(job, inverted))
                    .as("PARM-START-DATE and PARM-END-DATE at app/proc/TRANREPT.prc:41-42 bound an inclusive "
                            + "range, and an inverted range is not an empty report but an invalid request; the "
                            + "validator names the offending parameter rather than failing anonymously")
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining(TransactionReportProcessor.START_DATE_JOB_PARAMETER)
                    .hasMessageContaining("must not be after");
            assertThat(objectsUnder(batchOutputBucket, backupPrefix))
                    .as("a refused launch reaches no step, so no generation is created")
                    .isEmpty();
        }
    }

    // =================================================================================================
    // The generation carried forward between steps, the resolved retention, and the index the range needs.
    // =================================================================================================

    /** {@code (+1)} written then read in the same job, and the physical support the date range relies on. */
    @Nested
    @DisplayName("Generation carry-forward, retention and the range index")
    class GenerationCarryForwardAndTheRangeIndex {

        /**
         * Creates the generation carry-forward group.
         *
         * <p>Declared explicitly for the same reason as the enclosing class: JUnit builds one
         * instance per test method and the enclosing instance supplies every collaborator, so the
         * body has nothing to do and says so.
         */
        GenerationCarryForwardAndTheRangeIndex() {
            // Intentionally empty; the enclosing instance owns every collaborator.
        }

        /**
         * The sort step consumes exactly the generation the backup step produced.
         *
         * <p>Purpose: a relative generation reference resolved twice in one job can select two different objects,
         * and the second selection would report on data this run never backed up. The concrete key is published into
         * the job execution context and stamped with the job instance, so all three generations of one run agree and
         * the handoff is visible rather than implied.
         */
        @Test
        @DisplayName("STEP05R consumes exactly the TRANSACT.BKUP generation STEP01R produced: the concrete "
                + "key is carried forward, never a relative generation re-resolved mid-job")
        @Transactional(propagation = Propagation.NOT_SUPPORTED)
        void theSortStepConsumesExactlyTheGenerationTheBackupStepProduced() {
            seedInWindowTransactionsOnDistinctCards(2);

            final JobExecution execution = launchTransactionReport();
            assertRunCompleted(execution);

            final String backupKey = requiredContextString(execution, backupObjectKeyEntry);
            final String dailyKey = requiredContextString(execution, dailyObjectKeyEntry);
            final String reportKey = requiredContextString(execution, reportObjectKeyEntry);

            assertThat(objectKeysUnder(backupPrefix))
                    .as("app/proc/TRANREPT.prc:31 writes TRANSACT.BKUP(+1), a new generation, so exactly one "
                            + "object exists under that base and it is the one the step published")
                    .containsExactly(backupKey);
            assertThat(objectKeysUnder(dailyPrefix))
                    .as("app/proc/TRANREPT.prc:53 writes TRANSACT.DALY(+1) from the SORTIN of :37, which is "
                            + "the generation the previous step created in this same job")
                    .containsExactly(dailyKey);
            assertThat(objectKeysUnder(reportPrefix))
                    .as("app/proc/TRANREPT.prc:78 writes TRANREPT(+1)")
                    .containsExactly(reportKey);

            final String generationSegment = generationSegmentOf(execution);
            assertThat(backupKey)
                    .as("a relative generation re-resolved in a later step could select a different object; "
                            + "the concrete key is stamped with the job instance so all three agree")
                    .isEqualTo(backupPrefix + "/generation=" + generationSegment + "/TRANSACT.BKUP");
            assertThat(dailyKey).isEqualTo(dailyPrefix + "/generation=" + generationSegment + "/"
                    + dailyObjectName);
            assertThat(reportKey).isEqualTo(reportPrefix + "/generation=" + generationSegment + "/TRANREPT");

            assertThat(fixedWidthRecords(objectBytes(batchOutputBucket, backupKey), transactionRecordLength))
                    .as("the object the sort step read carries LRECL=350 records, as "
                            + "app/proc/TRANREPT.prc:29 declares and app/cpy/CVTRA05Y.cpy:2 corroborates")
                    .hasSize((int) requiredContextLong(execution, backupRecordCountEntry));
        }

        /**
         * {@code SORTOUT} carries the {@code SORT FIELDS=(TRAN-CARD-NUM,A)} permutation, byte for byte.
         *
         * <p><strong>Why this has to be asserted rather than inferred, and why now.</strong> Finding
         * <strong>F-012</strong> replaced the mechanism behind this step: it used to read the whole
         * {@code SORTIN} generation into a {@code List<byte[]>} and sort it in heap, and it now pages an
         * ordered query and streams the result. Every existing assertion in this class about STEP05R covers the
         * generation's <em>key</em> and its <em>record count</em>, and both are unchanged by a wrong ordering -
         * so the one property the change could plausibly have broken was the only one nothing checked.
         *
         * <p>The rows are seeded on cards in <strong>descending</strong> order with ascending identifiers, so
         * insertion order is the reverse of the required output order on the sort key and coincides with it on
         * the tiebreak. A step that emitted rows in insertion order, or that ordered on the wrong field, fails
         * here; one that merely happened to receive them ordered cannot pass by luck.
         *
         * <p>The tiebreak is asserted too. {@code app/proc/TRANREPT.prc:44} names one field, so DFSORT leaves
         * ties unordered, and the previous implementation resolved them to ascending {@code TRAN-ID} only
         * because a stable {@code List.sort} ran over a generation that happened to be in {@code TRAN-ID}
         * order. The replacement states that tiebreak in the query. Asserting it fixes the behaviour that was
         * previously incidental.
         */
        @Test
        @DisplayName("STEP05R emits SORTOUT ordered by TRAN-CARD-NUM ascending then TRAN-ID ascending, "
                + "app/proc/TRANREPT.prc:44")
        @Transactional(propagation = Propagation.NOT_SUPPORTED)
        void theSortStepEmitsTheCardNumberPermutation() {
            final List<String> cards = seededCardNumbersAscending();
            assertThat(cards.size())
                    .as("this probe needs at least three distinct card keys to order")
                    .isGreaterThanOrEqualTo(3);
            final List<String> chosen = List.of(cards.get(0), cards.get(1), cards.get(2));

            // Seeded in DESCENDING card order, and two rows on the middle card so the tiebreak is exercised.
            seedTransaction(probeTransactionId(1), chosen.get(2), new BigDecimal("10.00"),
                    fixedClockProcessingTimestamp());
            seedTransaction(probeTransactionId(2), chosen.get(1), new BigDecimal("11.00"),
                    fixedClockProcessingTimestamp());
            seedTransaction(probeTransactionId(3), chosen.get(1), new BigDecimal("12.00"),
                    fixedClockProcessingTimestamp());
            seedTransaction(probeTransactionId(4), chosen.get(0), new BigDecimal("13.00"),
                    fixedClockProcessingTimestamp());

            final JobExecution execution = launchTransactionReport();
            assertRunCompleted(execution);

            final String dailyKey = requiredContextString(execution, dailyObjectKeyEntry);
            final List<String> records =
                    fixedWidthRecords(objectBytes(batchOutputBucket, dailyKey), transactionRecordLength);

            assertThat(records)
                    .as("four seeded rows are all in window, so all four reach SORTOUT")
                    .hasSize(4);
            assertThat(sortKeysOf(records))
                    .as("app/proc/TRANREPT.prc:44 SORT FIELDS=(TRAN-CARD-NUM,A) over the character image at "
                            + "bytes 263-278, with the TRAN-ID at bytes 1-16 breaking ties ascending")
                    .containsExactly(
                            chosen.get(0) + probeTransactionId(4),
                            chosen.get(1) + probeTransactionId(2),
                            chosen.get(1) + probeTransactionId(3),
                            chosen.get(2) + probeTransactionId(1));
            assertThat(requiredContextLong(execution, dailyRecordCountEntry))
                    .as("the published count is the number of records actually written")
                    .isEqualTo(4L);
        }

        /**
         * {@code SORTOUT} is undelimited: no record separator byte appears anywhere in it.
         *
         * <p>{@code app/proc/TRANREPT.prc:29} declares {@code DCB=(LRECL=350,RECFM=FB,BLKSIZE=0)}, which is
         * fixed blocked and carries no delimiter. This is asserted on the object this step <em>writes</em>
         * because the next step <em>reads</em> it as fixed blocks: a terminator per record would shift every
         * record after the first by one byte, and each shifted record would still be 350 bytes long and so
         * still pass a length check.
         */
        @Test
        @DisplayName("STEP05R writes SORTOUT undelimited: an exact multiple of 350 bytes and no 0x0A or 0x0D")
        @Transactional(propagation = Propagation.NOT_SUPPORTED)
        void theSortStepWritesUndelimitedFixedBlocks() {
            seedInWindowTransactionsOnDistinctCards(3);

            final JobExecution execution = launchTransactionReport();
            assertRunCompleted(execution);

            final byte[] payload = objectBytes(batchOutputBucket,
                    requiredContextString(execution, dailyObjectKeyEntry));

            assertThat(payload.length % transactionRecordLength)
                    .as("RECFM=FB holds a whole number of records; a remainder means no offset after it can "
                            + "be trusted")
                    .isZero();
            assertThat(separatorBytesIn(payload))
                    .as("app/proc/TRANREPT.prc:29 declares RECFM=FB, which is undelimited, so a separator "
                            + "byte would be corrupt geometry rather than a terminator")
                    .isEmpty();
        }

        /**
         * A {@code SORTIN} generation carrying separator bytes is refused, and named.
         *
         * <p>This is the third of the three fixed-block object read paths to be hardened - the two in
         * {@code batch/readers} were done under finding <strong>F-013</strong> - and it was the one that failed
         * least usefully. Reading 350 bytes at a time from an object with a one-byte terminator per record
         * returns a full-length buffer every time, so the old loop's length check passed while every record
         * after the first was misaligned and read as valid; the run failed, if at all, only on the short final
         * remainder, reporting a length error about the last record when the defect was in the second. The step
         * now names the byte, its offset and its row.
         */
        @Test
        @DisplayName("STEP05R refuses a SORTIN generation that carries record separators, naming the byte")
        @Transactional(propagation = Propagation.NOT_SUPPORTED)
        void theSortStepRefusesASeparatedSortInput() {
            seedInWindowTransactionsOnDistinctCards(2);

            final JobExecution execution = launchReportOverSeparatedBackupGeneration();

            assertThat(execution.getStatus()).isEqualTo(BatchStatus.FAILED);
            assertThat(failureMessagesOf(execution))
                    .as("the diagnostic must name the separator, its byte value and the DCB that forbids it, "
                            + "and must not quote any record content")
                    .anySatisfy(message -> assertThat(message)
                            .contains("separator")
                            .contains("0x0A")
                            .contains("TRANREPT.prc:L29"));
        }

        /**
         * A {@code SORTIN} generation whose population disagrees with the relation is refused.
         *
         * <p><strong>This is the guard that makes finding F-012's fix sound rather than merely faster.</strong>
         * The step no longer emits the generation's bytes; it emits the ordered relation, which is legitimate
         * only because {@code app/ctl/REPROCT.ctl:15} is {@code REPRO INFILE(FILEIN) OUTFILE(FILEOUT)} with no
         * selection, so STEP01R's generation and the relation hold the same records. That equivalence is a
         * premise, and a premise that is never checked is an assumption - so the step checks it, and a
         * disagreement fails the run rather than producing a report over a population {@code SORTIN} does not
         * contain. This is also what closes the one behavioural gap the substrate change opens: the frozen
         * snapshot semantics of {@code DISP=SHR} would hide a write that landed after STEP01R ran, and here it
         * is detected instead.
         *
         * <p>The planted generation is well formed - correct geometry, no separators - and holds one record
         * where the relation holds two, so the only thing under test is the population check itself.
         */
        @Test
        @DisplayName("STEP05R refuses a SORTIN generation whose record count disagrees with the relation")
        @Transactional(propagation = Propagation.NOT_SUPPORTED)
        void theSortStepRefusesASortInputThatDisagreesWithTheRelation() {
            seedInWindowTransactionsOnDistinctCards(2);

            final JobExecution execution = launchReportOverBackupGeneration(
                    List.of(paddedRecordFor(firstSeededCardNumber())));

            assertThat(execution.getStatus()).isEqualTo(BatchStatus.FAILED);
            assertThat(failureMessagesOf(execution))
                    .as("the diagnostic must name both counts and the control card that makes them equal, and "
                            + "must not quote any record content")
                    .anySatisfy(message -> assertThat(message)
                            .contains("app/ctl/REPROCT.ctl:L15")
                            .contains("transaction relation holds 2"));
        }

        /**
         * The retention conflict for the report generation base is resolved to ten.
         *
         * <p>Purpose: two source members declare different limits for the same base, and object storage needs one
         * lifecycle value. This is the only legacy inconsistency this job resolves rather than preserves, so the
         * resolution is asserted where a reader will find it alongside the two locators that disagree.
         */
        @Test
        @DisplayName("the TRANREPT retention conflict is resolved to 10: DEFGDGB.jcl:38 says LIMIT(5) and "
                + "REPTFILE.jcl:27 says LIMIT(10) for the same base")
        @Transactional(propagation = Propagation.NOT_SUPPORTED)
        void theReportRetentionConflictIsResolvedToTen() {
            assertThat(reportRetentionGenerations)
                    .as("two source declarations disagree about the same generation data group and one "
                            + "lifecycle value has to be chosen, so the larger is taken and the conflict is "
                            + "recorded rather than propagated. Severity of the source conflict: Medium")
                    .isEqualTo(10);
        }

        /**
         * The processing-timestamp index exists and is not unique.
         *
         * <p>Purpose: the date range this whole class exercises has physical support only if that index is there,
         * and the alternate index it replaces is non-unique in the catalogue. A unique index would reject the many
         * rows that legitimately share one processing timestamp - which is precisely what a batch posting run
         * produces.
         */
        @Test
        @DisplayName("the processing-timestamp index the date range relies on exists and is NON-UNIQUE, "
                + "matching the NONUNIQKEY alternate index in app/catlg/LISTCAT.txt")
        @Transactional(propagation = Propagation.NOT_SUPPORTED)
        void theProcessingTimestampIndexExistsAndIsNotUnique() {
            final String indexName = "idx_transaction_proc_ts";
            final Boolean unique = jdbcTemplate.queryForObject(
                    "select i.indisunique from pg_index i"
                            + " join pg_class c on c.oid = i.indexrelid"
                            + " join pg_namespace n on n.oid = c.relnamespace"
                            + " where c.relname = ? and n.nspname = current_schema()",
                    Boolean.class, indexName);
            assertThat(unique)
                    .as("all three alternate indexes are NONUNIQKEY in the catalogue, and the migration "
                            + "creates three non-unique B-tree indexes and no unique one; a unique index here "
                            + "would reject the many rows one processing timestamp legitimately carries")
                    .isNotNull()
                    .isFalse();

            final String definition = jdbcTemplate.queryForObject(
                    "select indexdef from pg_indexes where indexname = ? and schemaname = current_schema()",
                    String.class, indexName);
            assertThat(definition)
                    .as("the index has to be over the column the sort's TRAN-PROC-DT symbol addresses, or the "
                            + "range scan of app/proc/TRANREPT.prc:45-46 has no physical support")
                    .isNotNull()
                    .contains("tran_proc_ts");
        }
    }

    // =================================================================================================
    // Launchers.
    // =================================================================================================

    /**
     * Launches the assembled job over the declared window.
     *
     * @return the completed execution, never {@code null}
     */
    private JobExecution launchTransactionReport() {
        return launchJob(transactionReportJob, runIdParameters(reportWindowParameters()));
    }

    /**
     * The two date parameters the job requires, holding the values the procedure's symbols declare.
     *
     * @return an immutable two-entry map, never {@code null}
     */
    private Map<String, String> reportWindowParameters() {
        return Map.of(
                TransactionReportProcessor.START_DATE_JOB_PARAMETER, reportStartDate,
                TransactionReportProcessor.END_DATE_JOB_PARAMETER, reportEndDate);
    }

    /**
     * Runs STEP10R alone over a generation this test wrote, rather than one an earlier step produced.
     *
     * <p><strong>Why this exists, stated in full because it is the only place in this class that does not go
     * through the assembled flow.</strong> Both date filters read the same ten characters from the same two
     * job parameters, so a record that the {@code INCLUDE COND} of {@code app/proc/TRANREPT.prc:45-46}
     * admits is a record the re-filter at {@code app/cbl/CBTRN03C.cbl:173-174} also admits. The transfer at
     * {@code :177} is therefore unreachable through the assembled job - which is exactly why it has to be
     * driven deliberately, with a synthetic out-of-window record placed <em>downstream</em> of the sort. The
     * same route is the only one to a missing lookup key, because the relational foreign keys make an absent
     * transaction type or category impossible for a row that reached the backup step, while a record decoded
     * from an object carries whatever the object holds.
     *
     * <p>The step bean under test is the production one, launched through the production launcher and the
     * production job repository. Only the handoff a preceding step would have published is supplied here,
     * under the very execution-context entry STEP05R writes, and only the daily generation is synthetic.
     * This is a test harness; it is not a job the application ships.
     *
     * @param records the 350-byte record images to place in the synthetic generation, in the order the step
     *     must read them; must be neither {@code null} nor empty
     * @return the execution, which may be completed or failed depending on what the records provoke
     */
    private JobExecution launchGenerateStepOverSyntheticGeneration(final List<String> records) {
        assertThat(records)
                .as("a synthetic generation with no records would exercise the end-of-data branch, which the "
                        + "empty-window case already covers through the assembled flow")
                .isNotEmpty();

        final String dailyKey = dailyPrefix + "/generation="
                + String.format(Locale.ROOT, "%0" + generationSegmentWidth + "d",
                        Long.valueOf(syntheticGenerationOrdinal))
                + "/" + dailyObjectName;
        putFixedWidthObject(dailyKey, records);

        final Job probe = new JobBuilder(probeJobName, jobRepository)
                .listener(new DailyGenerationHandoff(dailyObjectKeyEntry, dailyKey))
                .start(transactionReportGenerateStep)
                .build();
        return launchJob(probe, runIdParameters(reportWindowParameters()));
    }

    /**
     * Runs STEP05R over a {@code SORTIN} generation this test deliberately wrote with record separators.
     *
     * <p>The generation is planted under the very execution-context entry STEP01R publishes, so the step reads
     * it exactly as it would read a real handoff, and the only thing synthetic is the object's geometry. The
     * production step bean, launcher and job repository are used unchanged. A separated object could not be
     * produced by the assembled flow - STEP01R writes undelimited blocks - which is why it has to be planted.
     *
     * @return the execution, which must be failed
     */
    private JobExecution launchReportOverSeparatedBackupGeneration() {
        // Two well-formed 350-byte images, each followed by one LF, which is what a writer treating the
        // records as text lines produces. The object length is therefore 702 rather than 700.
        final StringBuilder payload = new StringBuilder();
        for (int record = 0; record < 2; record++) {
            payload.append(paddedRecordFor(firstSeededCardNumber())).append('\n');
        }
        return launchSortStepOverPlantedBackup("Separated",
                payload.toString().getBytes(StandardCharsets.ISO_8859_1));
    }

    /**
     * Runs STEP05R over a well-formed {@code SORTIN} generation holding exactly the given record images.
     *
     * @param records the 350-character images to plant; must not be {@code null}
     * @return the execution
     */
    private JobExecution launchReportOverBackupGeneration(final List<String> records) {
        final StringBuilder payload = new StringBuilder(records.size() * transactionRecordLength);
        for (final String record : records) {
            assertThat(record.length())
                    .as("a planted generation must carry the geometry app/proc/TRANREPT.prc:29 declares")
                    .isEqualTo(transactionRecordLength);
            payload.append(record);
        }
        return launchSortStepOverPlantedBackup("Population",
                payload.toString().getBytes(StandardCharsets.ISO_8859_1));
    }

    /**
     * Plants a {@code SORTIN} generation verbatim and runs the production STEP05R bean over it.
     *
     * <p>The generation is planted under the very execution-context entry STEP01R publishes, so the step reads
     * it exactly as it would read a real handoff, and the only thing synthetic is the object's content. The
     * production step bean, launcher and job repository are used unchanged. Because the probe job runs STEP05R
     * alone, no {@code recordCount} is published for it, which is deliberate: it isolates the population check
     * against the relation from the separate check against what STEP01R published.
     *
     * @param probeSuffix distinguishes the probe job name, since one job name owns one parameter identity
     * @param payload the exact object bytes to plant, geometry included
     * @return the execution
     */
    private JobExecution launchSortStepOverPlantedBackup(final String probeSuffix, final byte[] payload) {
        final String backupKey = backupPrefix + "/generation="
                + String.format(Locale.ROOT, "%0" + generationSegmentWidth + "d",
                        Long.valueOf(syntheticGenerationOrdinal))
                + "/TRANSACT.BKUP";
        s3Client.putObject(
                PutObjectRequest.builder()
                        .bucket(batchOutputBucket)
                        .key(backupKey)
                        .contentType("application/octet-stream")
                        .build(),
                RequestBody.fromBytes(payload));

        final Job probe = new JobBuilder(probeJobName + probeSuffix, jobRepository)
                .listener(new DailyGenerationHandoff(backupObjectKeyEntry, backupKey))
                .start(transactionReportSortStep)
                .build();
        return launchJob(probe, runIdParameters(reportWindowParameters()));
    }

    /**
     * Renders one syntactically valid 350-byte record image on a given card.
     *
     * <p>Only the geometry matters to the caller, which asserts a separator refusal rather than any field, so
     * the image is a blank-filled record carrying the card number at its declared offset.
     *
     * @param cardNumber the sixteen-character card key
     * @return exactly {@code transactionRecordLength} characters
     */
    private String paddedRecordFor(final String cardNumber) {
        final char[] image = new char[transactionRecordLength];
        Arrays.fill(image, ' ');
        final String identifier = probeTransactionId(1);
        identifier.getChars(0, identifier.length(), image, 0);
        cardNumber.getChars(0, cardNumber.length(), image, cardNumberOffset);
        return new String(image);
    }

    /**
     * The sort key and its tiebreak, concatenated, for each record image in emission order.
     *
     * <p>{@code TRAN-CARD-NUM} occupies bytes 263-278 and {@code TRAN-ID} bytes 1-16, both one-based, as
     * {@code app/proc/TRANREPT.prc:39-40} declares through its {@code SYMNAMES}. Returning the pair as one
     * string lets the caller assert the exact permutation in one statement.
     *
     * @param records the record images in emission order
     * @return one thirty-two-character key per record, never {@code null}
     */
    private List<String> sortKeysOf(final List<String> records) {
        final List<String> keys = new ArrayList<>(records.size());
        for (final String record : records) {
            keys.add(record.substring(cardNumberOffset, cardNumberOffset + cardNumberLength)
                    + record.substring(0, transactionIdLength));
        }
        return keys;
    }

    /**
     * Every offset in a payload that holds a record separator byte.
     *
     * <p>Returned as offsets rather than as a boolean so a failure names where the corruption is. No byte of
     * record content is included in the result.
     *
     * @param payload the object bytes
     * @return the one-based offsets carrying {@code 0x0A} or {@code 0x0D}, empty when there are none
     */
    private List<Integer> separatorBytesIn(final byte[] payload) {
        final List<Integer> offsets = new ArrayList<>();
        for (int offset = 0; offset < payload.length; offset++) {
            if (payload[offset] == (byte) '\n' || payload[offset] == (byte) '\r') {
                offsets.add(Integer.valueOf(offset + 1));
            }
        }
        return offsets;
    }

    /**
     * The failure messages of every step of an execution, plus the job-level ones.
     *
     * <p>A tasklet failure is recorded on its step, and the whole cause chain is walked because the message a
     * test asserts on is raised inside the step body and wrapped by the framework on its way out.
     *
     * @param execution the finished execution
     * @return one entry per throwable in the execution's failure chains, never {@code null}
     */
    private List<String> failureMessagesOf(final JobExecution execution) {
        final List<String> messages = new ArrayList<>();
        final List<Throwable> roots = new ArrayList<>(execution.getAllFailureExceptions());
        for (final StepExecution step : execution.getStepExecutions()) {
            roots.addAll(step.getFailureExceptions());
        }
        for (final Throwable root : roots) {
            for (Throwable cause = root; cause != null; cause = cause.getCause()) {
                messages.add(String.valueOf(cause.getMessage()));
                if (cause.getCause() == cause) {
                    break;
                }
            }
        }
        return messages;
    }

    // =================================================================================================
    // Shared assertions.
    // =================================================================================================

    /**
     * Asserts the run reached the clean outcome, so that later assertions are meaningful rather than merely
     * wrong.
     *
     * @param execution the execution to inspect; must not be {@code null}
     */
    private void assertRunCompleted(final JobExecution execution) {
        assertThat(execution.getStatus())
                .as("the three steps of app/proc/TRANREPT.prc must all complete; a failure here makes every "
                        + "later assertion in the test meaningless")
                .isEqualTo(BatchStatus.COMPLETED);
        assertThat(execution.getExitStatus().getExitCode())
                .as("and the run must end on the clean exit code. app/jcl/TRANREPT.jcl carries no COND, so "
                        + "nothing in this job gates a step on a return code")
                .isEqualTo(ExitStatus.COMPLETED.getExitCode());
    }

    /**
     * Asserts a record outside the inclusive window is copied by the unfiltered backup step and then removed
     * by both filters.
     *
     * @param processingTimestamp the 26-character {@code TRAN-PROC-TS} to seed; must not be {@code null}
     */
    private void assertRecordOutsideWindowIsBackedUpButNotReported(final String processingTimestamp) {
        final String transactionId = probeTransactionId(1);
        seedTransaction(transactionId, firstSeededCardNumber(), new BigDecimal("10.00"),
                processingTimestamp);

        final JobExecution execution = launchTransactionReport();
        assertRunCompleted(execution);

        assertThat(requiredContextLong(execution, backupRecordCountEntry))
                .as("REPRO at app/ctl/REPROCT.ctl:15 copies the whole cluster, so an out-of-window row still "
                        + "reaches TRANSACT.BKUP(+1); the filtering is the next step's job")
                .isOne();
        assertThat(requiredContextLong(execution, dailyRecordCountEntry))
                .as("the INCLUDE COND of app/proc/TRANREPT.prc:45-46 is inclusive at both ends and nowhere "
                        + "beyond them, so a record one day outside is removed")
                .isZero();

        final List<String> lines = reportLines(execution);
        assertThat(linesStartingWith(lines, transactionId))
                .as("and the record therefore never reaches 1120-WRITE-DETAIL")
                .isEmpty();
        assertThat(detailLineCount(lines))
                .as("nothing else is reported either")
                .isZero();
    }

    /**
     * Asserts the failure chain of a failed run carries a throwable of the named type whose message carries
     * the expected text, without ever echoing a key or an amount into the assertion description.
     *
     * <p>The whole chain is walked, so a wrapped cause counts and a swallowed one cannot pass: the type is
     * matched by simple name and the message by content, on the same link.
     *
     * @param execution the failed execution; must not be {@code null}
     * @param expectedTypeSimpleName the simple name of the exception type expected; must not be {@code null}
     * @param expectedMessageFragment the literal the abend message must carry; must not be {@code null}
     * @param because the reason the assertion holds, for the failure description; must not be {@code null}
     */
    private void assertFailureChainCarries(final JobExecution execution,
            final String expectedTypeSimpleName, final String expectedMessageFragment, final String because) {

        final List<String> chain = new ArrayList<>();
        for (final Throwable failure : execution.getAllFailureExceptions()) {
            Throwable current = failure;
            int depth = 0;
            while (current != null && depth < 16) {
                chain.add(current.getClass().getSimpleName() + ": " + current.getMessage());
                final Throwable next = current.getCause();
                current = next == current ? null : next;
                depth++;
            }
        }
        assertThat(chain)
                .as("a failed step must record why it failed, so the chain cannot be empty")
                .isNotEmpty();
        assertThat(chain)
                .as(because)
                .anySatisfy(link -> assertThat(link)
                        .startsWith(expectedTypeSimpleName + ": ")
                        .contains(expectedMessageFragment));
    }

    // =================================================================================================
    // Execution-context readers.
    // =================================================================================================

    /**
     * Reads a required string entry from the job execution context.
     *
     * @param execution the execution to read; must not be {@code null}
     * @param contextKey the entry name; must not be {@code null}
     * @return the value, never {@code null}
     */
    private String requiredContextString(final JobExecution execution, final String contextKey) {
        final ExecutionContext context = execution.getExecutionContext();
        assertThat(context.containsKey(contextKey))
                .as("a step publishes its concrete object key so that the next step and this test read the "
                        + "same value instead of each resolving one of their own")
                .isTrue();
        return context.getString(contextKey);
    }

    /**
     * Reads a required numeric entry from the job execution context.
     *
     * @param execution the execution to read; must not be {@code null}
     * @param contextKey the entry name; must not be {@code null}
     * @return the value
     */
    private long requiredContextLong(final JobExecution execution, final String contextKey) {
        final ExecutionContext context = execution.getExecutionContext();
        assertThat(context.containsKey(contextKey))
                .as("a step publishes the count it wrote, which is what lets a count be asserted without "
                        + "re-deriving it from the object")
                .isTrue();
        return context.getLong(contextKey);
    }

    /**
     * The nineteen-character generation segment every object key of one run carries.
     *
     * @param execution the execution whose instance identifies the generation; must not be {@code null}
     * @return exactly {@code generationSegmentWidth} digits, never {@code null}
     */
    private String generationSegmentOf(final JobExecution execution) {
        assertThat(execution.getJobInstance())
                .as("a relative generation is resolved from the job instance, so there has to be one")
                .isNotNull();
        return String.format(Locale.ROOT, "%0" + generationSegmentWidth + "d",
                Long.valueOf(execution.getJobInstance().getInstanceId()));
    }

    /**
     * The step names of one execution in the order they ran.
     *
     * @param execution the execution to inspect; must not be {@code null}
     * @return the ordered names, never {@code null}
     */
    private List<String> stepNamesInExecutionOrder(final JobExecution execution) {
        final List<StepExecution> ordered = new ArrayList<>(execution.getStepExecutions());
        ordered.sort(Comparator
                .comparing(StepExecution::getStartTime, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(StepExecution::getId, Comparator.nullsLast(Comparator.naturalOrder())));
        final List<String> names = new ArrayList<>(ordered.size());
        for (final StepExecution step : ordered) {
            names.add(step.getStepName());
        }
        return names;
    }

    // =================================================================================================
    // Object-store readers and the one writer, which places the synthetic generation.
    // =================================================================================================

    /**
     * The emitted report, split into its fixed 133-byte records.
     *
     * @param execution a completed execution whose report key has been published; must not be {@code null}
     * @return the report lines in emission order, never {@code null}
     */
    private List<String> reportLines(final JobExecution execution) {
        return fixedWidthRecords(
                objectBytes(batchOutputBucket, requiredContextString(execution, reportObjectKeyEntry)),
                reportLineLength);
    }

    /**
     * Reads one object back whole.
     *
     * @param bucket the bucket; must not be {@code null}
     * @param key the object key; must not be {@code null}
     * @return the payload, never {@code null}
     */
    private byte[] objectBytes(final String bucket, final String key) {
        return s3Client.getObjectAsBytes(
                        GetObjectRequest.builder().bucket(bucket).key(key).build())
                .asByteArray();
    }

    /**
     * Lists the objects under one prefix of the output bucket, ascending by key.
     *
     * @param bucket the bucket; must not be {@code null}
     * @param prefix the key prefix; must not be {@code null}
     * @return the objects, never {@code null} and possibly empty
     */
    private List<S3Object> objectsUnder(final String bucket, final String prefix) {
        final List<S3Object> found = new ArrayList<>();
        s3Client.listObjectsV2Paginator(
                        ListObjectsV2Request.builder().bucket(bucket).prefix(prefix).build())
                .contents()
                .forEach(found::add);
        found.sort(Comparator.comparing(S3Object::key));
        return found;
    }

    /**
     * The keys under one generation prefix of the output bucket, ascending, which is the ordering the
     * lexicographically-greatest rule for a {@code (0)} reference depends on.
     *
     * @param prefix the generation prefix; must not be {@code null}
     * @return the keys, never {@code null} and possibly empty
     */
    private List<String> objectKeysUnder(final String prefix) {
        final List<S3Object> objects = objectsUnder(batchOutputBucket, prefix);
        final List<String> keys = new ArrayList<>(objects.size());
        for (final S3Object object : objects) {
            keys.add(object.key());
        }
        return keys;
    }

    /**
     * Writes one fixed-block object with no record delimiter, the shape {@code RECFM=FB} declares.
     *
     * @param key the object key; must not be {@code null}
     * @param records the record images, each exactly {@code transactionRecordLength} characters; must not be
     *     {@code null}
     */
    private void putFixedWidthObject(final String key, final List<String> records) {
        final StringBuilder payload = new StringBuilder(records.size() * transactionRecordLength);
        for (final String record : records) {
            assertThat(record.length())
                    .as("app/proc/TRANREPT.prc:29 declares DCB=(LRECL=350,RECFM=FB,BLKSIZE=0) and :51 carries "
                            + "it forward to SORTOUT with DCB=(*.SORTIN), so a synthetic generation has to "
                            + "carry the same geometry or the reader is right to reject it")
                    .isEqualTo(transactionRecordLength);
            payload.append(record);
        }
        s3Client.putObject(
                PutObjectRequest.builder()
                        .bucket(batchOutputBucket)
                        .key(key)
                        .contentType("application/octet-stream")
                        .build(),
                RequestBody.fromBytes(payload.toString().getBytes(StandardCharsets.ISO_8859_1)));
    }

    /**
     * Splits a fixed-block payload into its records.
     *
     * @param payload the object bytes; must not be {@code null}
     * @param width the record length
     * @return the records in order, never {@code null}
     */
    private List<String> fixedWidthRecords(final byte[] payload, final int width) {
        assertThat(payload.length % width)
                .as("a fixed block dataset holds a whole number of records; a remainder means the object is "
                        + "truncated and no offset after it can be trusted")
                .isZero();
        final List<String> records = new ArrayList<>(payload.length / width);
        for (int offset = 0; offset < payload.length; offset += width) {
            records.add(new String(payload, offset, width, StandardCharsets.ISO_8859_1));
        }
        return records;
    }

    // =================================================================================================
    // Report-line classifiers. Every one keys on a literal the source declares, never on a position this
    // test invented, and none of them ever returns or names a card number.
    // =================================================================================================

    /**
     * The report lines that open with one literal.
     *
     * @param lines the report; must not be {@code null}
     * @param prefix the literal each returned line opens with; must not be {@code null}
     * @return the matching lines in emission order, never {@code null}
     */
    private List<String> linesStartingWith(final List<String> lines, final String prefix) {
        final List<String> matching = new ArrayList<>();
        for (final String line : lines) {
            if (line.startsWith(prefix)) {
                matching.add(line);
            }
        }
        return matching;
    }

    /**
     * The single report line that opens with one literal.
     *
     * @param lines the report; must not be {@code null}
     * @param prefix the literal the line opens with; must not be {@code null}
     * @return that line, never {@code null}
     */
    private String onlyLineStartingWith(final List<String> lines, final String prefix) {
        final List<String> matching = linesStartingWith(lines, prefix);
        assertThat(matching)
                .as("exactly one line was expected to open with this label; a different count is itself the "
                        + "finding and must not be averaged away by taking the first")
                .hasSize(1);
        return matching.getFirst();
    }

    /**
     * How many detail lines the report carries.
     *
     * <p>A detail line is identified by its own leading field rather than by exclusion:
     * {@code 1120-WRITE-DETAIL} at {@code app/cbl/CBTRN03C.cbl:361-363} opens the record with
     * {@code TRAN-REPORT-TRANS-ID}, sixteen characters wide, and every seeded identifier here is sixteen
     * digits. No header, rule, blank or totals line can begin with sixteen digits.
     *
     * @param lines the report; must not be {@code null}
     * @return the count
     */
    private long detailLineCount(final List<String> lines) {
        long details = 0L;
        for (final String line : lines) {
            boolean allDigits = true;
            for (int index = 0; index < 16; index++) {
                final char character = line.charAt(index);
                if (character < '0' || character > '9') {
                    allDigits = false;
                    break;
                }
            }
            if (allDigits) {
                details++;
            }
        }
        return details;
    }

    /**
     * How many times {@code 1120-WRITE-HEADERS} ran, counted from the line it emits first.
     *
     * @param lines the report; must not be {@code null}
     * @return the count
     */
    private long headerBlockCount(final List<String> lines) {
        return linesStartingWith(lines, reportNameHeaderPrefix).size();
    }

    /**
     * Decodes the edited amount a totals line carries.
     *
     * <p>The field is {@code PIC +ZZZ,ZZZ,ZZZ.ZZ} - {@code app/cpy/CVTRA07Y.cpy:54}, {@code :60} and
     * {@code :66} - so it is one mandatory sign followed by fourteen mask positions, and it sits at the same
     * offset on all three totals lines: thirteen plus eighty-four for the account totals of {@code :57-59},
     * eleven plus eighty-six for the page and grand totals of {@code :51-53} and {@code :63-65}. Suppressed
     * positions are blank, group separators are suppressed with the digits they follow, and a fully
     * suppressed field is zero.
     *
     * @param line one totals line; must not be {@code null}
     * @return the decoded value, never {@code null}
     */
    private BigDecimal editedTotalOf(final String line) {
        final String field = line.substring(totalAmountOffset, totalAmountOffset + editedAmountWidth);
        final StringBuilder digits = new StringBuilder(editedAmountWidth);
        for (int index = 1; index < field.length(); index++) {
            final char character = field.charAt(index);
            if ((character >= '0' && character <= '9') || character == '.') {
                digits.append(character);
            }
        }
        if (digits.isEmpty()) {
            return BigDecimal.ZERO;
        }
        final BigDecimal magnitude = new BigDecimal(digits.toString());
        return field.charAt(0) == '-' ? magnitude.negate() : magnitude;
    }

    // =================================================================================================
    // Seeded state. Every posted row is committed, because a job reads committed state only, and the
    // harness removes them again after each test.
    // =================================================================================================

    /**
     * The seeded card keys ascending, which is the ordering {@code SORT FIELDS=(TRAN-CARD-NUM,A)} produces.
     *
     * @return the seeded keys, never {@code null}
     */
    private List<String> seededCardNumbersAscending() {
        final List<CardCrossReference> crossReferences = cardCrossReferenceRepository
                .findByCardNumberGreaterThanOrderByCardNumberAsc("",
                        PageRequest.of(0, seededCrossReferenceCount));
        assertThat(crossReferences)
                .as("the cross reference relation is seeded from app/data/ASCII/cardxref.txt, and every key "
                        + "the report resolves has to be one of those rows")
                .hasSize(seededCrossReferenceCount);
        final List<String> keys = new ArrayList<>(crossReferences.size());
        for (final CardCrossReference crossReference : crossReferences) {
            keys.add(crossReference.getCardNumber());
        }
        return keys;
    }

    /**
     * The lowest seeded card key, used wherever one group is enough.
     *
     * @return that key, never {@code null}
     */
    private String firstSeededCardNumber() {
        return seededCardNumbersAscending().getFirst();
    }

    /**
     * Renders a sixteen-character {@code TRAN-ID} from a sequence number.
     *
     * @param sequence a positive sequence number
     * @return exactly sixteen digits, never {@code null}
     */
    private String probeTransactionId(final int sequence) {
        return String.format(Locale.ROOT, "%016d", Integer.valueOf(sequence));
    }

    /**
     * Commits one synthetic posted transaction.
     *
     * @param transactionId sixteen characters; must not be {@code null}
     * @param cardNumber a seeded card key; must not be {@code null}
     * @param amount {@code TRAN-AMT}, {@code S9(09)V99}; must not be {@code null}
     * @param processingTimestamp {@code TRAN-PROC-TS}, twenty-six characters; must not be {@code null}
     */
    private void seedTransaction(final String transactionId, final String cardNumber,
            final BigDecimal amount, final String processingTimestamp) {

        transactionRepository.save(new Transaction(transactionId, seededTypeCode, seededCategoryCode,
                probeSource, probeDescription, amount, probeMerchantId, probeMerchantName, probeMerchantCity,
                probeMerchantZip, cardNumber, processingTimestamp, processingTimestamp));
    }

    /**
     * Commits one in-window transaction on each of the lowest {@code count} seeded card keys.
     *
     * @param count how many distinct groups to create; must be between one and the seeded census
     */
    private void seedInWindowTransactionsOnDistinctCards(final int count) {
        final List<String> cards = seededCardNumbersAscending();
        assertThat(count)
                .as("a probe cannot use more card keys than the fixtures seed")
                .isBetween(1, cards.size());
        for (int index = 0; index < count; index++) {
            seedTransaction(probeTransactionId(index + 1), cards.get(index), new BigDecimal("10.00"),
                    fixedClockProcessingTimestamp());
        }
    }

    /**
     * Commits {@code count} in-window transactions on one card key, so that no control break can occur and a
     * page break is the only thing that can interrupt the detail lines.
     *
     * @param count how many records to create; must be positive
     */
    private void seedInWindowTransactionsOnOneCard(final int count) {
        assertThat(count)
                .as("a page-boundary probe needs at least one record")
                .isPositive();
        final String cardNumber = firstSeededCardNumber();
        for (int index = 0; index < count; index++) {
            seedTransaction(probeTransactionId(index + 1), cardNumber, new BigDecimal("10.00"),
                    fixedClockProcessingTimestamp());
        }
    }

    // =================================================================================================
    // Timestamps. All three producers in the corpus emit CHAR(26); this one reproduces the batch shape,
    // which is Z-GET-DB2-FORMAT-TIMESTAMP: hundredths-of-a-second precision plus four literal zeros.
    // =================================================================================================

    /**
     * The batch-shaped {@code TRAN-PROC-TS} for the harness's pinned instant.
     *
     * @return exactly twenty-six characters whose first ten fall inside the declared window, never
     *     {@code null}
     */
    private String fixedClockProcessingTimestamp() {
        return LocalDateTime.ofInstant(fixedInstant(), ZoneOffset.UTC)
                .format(DateTimeFormatter.ofPattern("uuuu-MM-dd-HH.mm.ss.SS", Locale.ROOT)) + "0000";
    }

    /**
     * The ten characters both filters actually compare, for the pinned instant.
     *
     * @return exactly ten characters, never {@code null}
     */
    private String fixedClockProcessingDate() {
        return fixedClockProcessingTimestamp().substring(0, 10);
    }

    /**
     * Assembles a batch-shaped {@code TRAN-PROC-TS} for one date and one time of day.
     *
     * @param reportDate ten characters, {@code yyyy-MM-dd}; must not be {@code null}
     * @param timeOfDay eleven characters, {@code HH.mm.ss.SS}; must not be {@code null}
     * @return exactly twenty-six characters, never {@code null}
     */
    private String batchTimestamp(final String reportDate, final String timeOfDay) {
        final String stamp = reportDate + "-" + timeOfDay + "0000";
        assertThat(stamp.length())
                .as("TRAN-PROC-TS is PIC X(26) at app/cpy/CVTRA05Y.cpy:17, and the batch producer emits "
                        + "hundredths-of-a-second precision followed by four literal zeros")
                .isEqualTo(26);
        return stamp;
    }

    // =================================================================================================
    // The 350-byte record image, assembled from the offsets app/cpy/CVTRA05Y.cpy declares. Needed only for
    // the synthetic generation: everything else is seeded through the entity and encoded by the job.
    // =================================================================================================

    /**
     * Assembles one {@code TRAN-RECORD} image.
     *
     * @param transactionId {@code TRAN-ID}, bytes 1-16; must not be {@code null}
     * @param typeCode {@code TRAN-TYPE-CD}, bytes 17-18; must not be {@code null}
     * @param categoryCode {@code TRAN-CAT-CD}, bytes 19-22
     * @param cardNumber {@code TRAN-CARD-NUM}, bytes 263-278; must not be {@code null}
     * @param amount {@code TRAN-AMT}, bytes 133-143; must not be {@code null}
     * @param processingTimestamp {@code TRAN-PROC-TS}, bytes 305-330; must not be {@code null}
     * @return exactly {@code transactionRecordLength} characters, never {@code null}
     */
    private String syntheticTransactionRecord(final String transactionId, final String typeCode,
            final int categoryCode, final String cardNumber, final BigDecimal amount,
            final String processingTimestamp) {

        final StringBuilder image = new StringBuilder(transactionRecordLength);
        image.append(alphanumericField(transactionId, 16));
        image.append(alphanumericField(typeCode, 2));
        image.append(unsignedDigitsField(categoryCode, 4));
        image.append(alphanumericField(probeSource, 10));
        image.append(alphanumericField(probeDescription, 100));
        image.append(zonedDecimalAmountField(amount));
        image.append(unsignedDigitsField(probeMerchantId.longValue(), 9));
        image.append(alphanumericField(probeMerchantName, 50));
        image.append(alphanumericField(probeMerchantCity, 50));
        image.append(alphanumericField(probeMerchantZip, 10));
        image.append(alphanumericField(cardNumber, 16));
        image.append(alphanumericField(processingTimestamp, 26));
        image.append(alphanumericField(processingTimestamp, 26));
        image.append(" ".repeat(20));

        final String record = image.toString();
        assertThat(record.length())
                .as("app/cpy/CVTRA05Y.cpy:2 declares RECLN = 350 and its FILLER PIC X(20) at :18 closes the "
                        + "record; anything else is a geometry the reader is right to reject")
                .isEqualTo(transactionRecordLength);
        return record;
    }

    /**
     * Left-justifies a text field and pads it with spaces, the {@code PIC X(n)} rule.
     *
     * @param value the content; must not be {@code null} and must fit
     * @param width the declared width
     * @return exactly {@code width} characters, never {@code null}
     */
    private String alphanumericField(final String value, final int width) {
        assertThat(value.length())
                .as("a field wider than its picture clause would displace every offset after it")
                .isLessThanOrEqualTo(width);
        return value + " ".repeat(width - value.length());
    }

    /**
     * Right-justifies an unsigned integer and pads it with zeros, the {@code PIC 9(n)} rule.
     *
     * @param value the content; must not be negative and must fit
     * @param width the declared width
     * @return exactly {@code width} digits, never {@code null}
     */
    private String unsignedDigitsField(final long value, final int width) {
        final String digits = Long.toString(value);
        assertThat(digits.length())
                .as("an unsigned display field carries no sign position, so the value has to fit its digits")
                .isLessThanOrEqualTo(width);
        return "0".repeat(width - digits.length()) + digits;
    }

    /**
     * Encodes {@code TRAN-AMT PIC S9(09)V99} as eleven characters with a trailing-sign overpunch.
     *
     * <p>The sign of a zoned-decimal field is carried on its last byte, together with that byte's digit. The
     * table is the one {@code app/data/ASCII/dailytran.txt} is written in: a positive final digit of zero is
     * <code>&#123;</code> and one through nine are {@code A} through {@code I}; a negative zero is
     * <code>&#125;</code> and one through nine are {@code J} through {@code R}. Encoding is
     * <strong>position aware</strong>, driven by the picture clause and applied to this field alone -
     * the same letters occur legitimately inside the merchant text fields, which is why a blanket
     * substitution over the record would corrupt them.
     *
     * @param amount the value, which may legitimately be negative; must not be {@code null}
     * @return exactly eleven characters, never {@code null}
     */
    private String zonedDecimalAmountField(final BigDecimal amount) {
        final BigDecimal scaled = amount.setScale(2, RoundingMode.HALF_EVEN);
        final BigInteger unscaled = scaled.movePointRight(2).toBigIntegerExact();
        final String magnitude = unscaled.abs().toString();
        assertThat(magnitude.length())
                .as("PIC S9(09)V99 holds eleven digits, so a wider magnitude does not fit the field")
                .isLessThanOrEqualTo(11);
        final String digits = "0".repeat(11 - magnitude.length()) + magnitude;
        final int finalDigit = digits.charAt(10) - '0';
        final char overpunch;
        if (unscaled.signum() < 0) {
            overpunch = finalDigit == 0 ? '}' : (char) ('J' + finalDigit - 1);
        } else {
            overpunch = finalDigit == 0 ? '{' : (char) ('A' + finalDigit - 1);
        }
        return digits.substring(0, 10) + overpunch;
    }

    // =================================================================================================
    // The one collaborator this test contributes: the step handoff a preceding step would have published.
    // =================================================================================================

    /**
     * Publishes the concrete daily generation key into the job execution context before any step runs.
     *
     * <p>This is exactly what {@code executeStep05r} does when it completes: it writes the concrete key it
     * created into the job execution context, so that {@code executeStep10r} reads that value rather than
     * re-resolving a relative generation. The probe supplies the same handoff under the same entry name, so
     * the step under test takes the identical path it takes in the assembled flow.
     *
     * <p>Its two fields are final and set once, so it holds no mutable state and can be shared safely by the
     * framework thread that invokes it.
     */
    private static final class DailyGenerationHandoff implements JobExecutionListener {

        /** The execution-context entry STEP05R publishes its concrete key under. */
        private final String contextKey;

        /** The concrete key the synthetic generation was written to. */
        private final String dailyObjectKey;

        /**
         * Creates the handoff.
         *
         * @param contextKey the entry name; must not be {@code null}
         * @param dailyObjectKey the concrete key; must not be {@code null}
         */
        private DailyGenerationHandoff(final String contextKey, final String dailyObjectKey) {
            this.contextKey = Objects.requireNonNull(contextKey, "contextKey must not be null");
            this.dailyObjectKey =
                    Objects.requireNonNull(dailyObjectKey, "dailyObjectKey must not be null");
        }

        @Override
        public void beforeJob(final JobExecution jobExecution) {
            jobExecution.getExecutionContext().putString(contextKey, dailyObjectKey);
        }
    }
}
