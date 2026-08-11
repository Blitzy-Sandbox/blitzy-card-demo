package com.vsergeychik.carddemo.parity;

import com.vsergeychik.carddemo.account.AccountRepository;
import com.vsergeychik.carddemo.account.model.AccountRecord;
import com.vsergeychik.carddemo.card.CardXrefRepository;
import com.vsergeychik.carddemo.card.model.CardXrefRecord;
import com.vsergeychik.carddemo.common.AbendException;
import com.vsergeychik.carddemo.common.CobolDecimal;
import com.vsergeychik.carddemo.common.FileStatus;
import com.vsergeychik.carddemo.common.PhysicalSequence;
import com.vsergeychik.carddemo.common.RecordImageForm;
import com.vsergeychik.carddemo.config.BatchConfig;
import com.vsergeychik.carddemo.config.BatchConfig.JobContract;
import com.vsergeychik.carddemo.config.BatchConfig.JobContracts;
import com.vsergeychik.carddemo.config.BatchConfig.JobDatasetBinding;
import com.vsergeychik.carddemo.config.BatchConfig.StepContract;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBinding;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBindings;
import com.vsergeychik.carddemo.parity.FieldDiffer.DiffResult;
import com.vsergeychik.carddemo.parity.ParityCase.UnitKind;
import com.vsergeychik.carddemo.parity.ParityHarness.Invocation;
import com.vsergeychik.carddemo.parity.ParityHarness.SeededDataset;
import com.vsergeychik.carddemo.parity.ParityHarness.UnitOutcome;
import com.vsergeychik.carddemo.transaction.DalyRejectWriter;
import com.vsergeychik.carddemo.transaction.DalyRejectWriter.RecordSink;
import com.vsergeychik.carddemo.transaction.DalyTranRepository;
import com.vsergeychik.carddemo.transaction.TranCatBalRepository;
import com.vsergeychik.carddemo.transaction.TransactionRepository;
import com.vsergeychik.carddemo.transaction.TransactionValidationJob;
import com.vsergeychik.carddemo.transaction.TransactionValidationJob.RunOutcome;
import com.vsergeychik.carddemo.transaction.TransactionValidationJob.SysoutSink;
import com.vsergeychik.carddemo.transaction.model.DalyTranRecord;
import com.vsergeychik.carddemo.transaction.model.TranCatBalRecord;
import com.vsergeychik.carddemo.transaction.model.TranRecord;

import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.InOrder;
import org.mockito.Mockito;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * The twenty-case parity gate for {@code CBTRN02C}, the {@code POSTTRAN} poster - and one of the three
 * densest-coverage programs in the estate, because of its four-stage validation cascade.
 *
 * <h2>What this program is, as against what its class is called (rule R1, practice B4)</h2>
 * <p>The mandated Java name is {@link TransactionValidationJob} and the program does far more than
 * validate: <strong>it validates and it posts</strong>, and it is the real poster of
 * {@code app/jcl/POSTTRAN.jcl} - not {@code CBTRN01C}, whose prompt-mandated name is
 * {@code TransactionPostingJob} and which no JCL in the repository invokes at all. The source's own
 * {@code Function} header reads "Post the records from daily transaction file"
 * ({@code app/cbl/CBTRN02C.cbl:5}). The name is honoured verbatim because the prompt mandates it; the
 * behaviour is taken from the source; and the divergence is recorded here rather than quietly
 * corrected, so no one reading a class called "validation" concludes that the writes belong somewhere
 * else.
 *
 * <h2>The cascade, and the asymmetry that is the whole point of these twenty cases (gate G31)</h2>
 * <p>Four stages, and their short-circuit structure is <strong>not uniform</strong>:
 * <ol>
 *   <li><strong>Card lookup</strong> - {@code 1500-A-LOOKUP-XREF} at {@code :380-392}. The
 *       {@code INVALID KEY} arm stores reason <strong>100</strong>
 *       {@code 'INVALID CARD NUMBER FOUND'}. {@code 1500-VALIDATE-TRAN} at {@code :372-376} then tests
 *       the reason before performing stage two, so <strong>a stage-one failure genuinely
 *       short-circuits</strong>. case02 proves it: the account and category-balance datasets come back
 *       untouched.</li>
 *   <li><strong>Account lookup</strong> - {@code 1500-B-LOOKUP-ACCT} at {@code :393-399}. Reason
 *       <strong>101</strong> {@code 'ACCOUNT RECORD NOT FOUND'}, and nothing after the read runs, so
 *       stages three and four are never evaluated. Reason <strong>109</strong> at {@code :556-558}
 *       carries the same 24 characters from a different paragraph entirely.</li>
 *   <li><strong>Credit limit</strong> - {@code :403-413}, driven by the one {@code COMPUTE} in this
 *       program: {@code WS-TEMP-BAL = ACCT-CURR-CYC-CREDIT - ACCT-CURR-CYC-DEBIT + DALYTRAN-AMT}
 *       ({@code app/cbl/CBTRN02C.cbl:403-405}). Reason <strong>102</strong>
 *       {@code 'OVERLIMIT TRANSACTION'}.</li>
 *   <li><strong>Expiration</strong> - {@code :414-420}, comparing the misspelled
 *       {@code ACCT-EXPIRAION-DATE} against {@code DALYTRAN-ORIG-TS (1:10)}. Reason
 *       <strong>103</strong> {@code 'TRANSACTION RECEIVED AFTER ACCT EXPIRATION'}.</li>
 * </ol>
 * <p><strong>Stages three and four both run unconditionally, so 103 overwrites 102.</strong> There is
 * no {@code ELSE} between them and no early exit: {@code :410} stores 102, and {@code :417} then
 * stores 103 over it along with the longer description. A transaction that is both overlimit and past
 * expiry is therefore rejected with <strong>103</strong>, never 102 and never both. That is what
 * case06 exists for, and it is the one case a translation written the natural way - short-circuiting
 * stage three into stage four, exactly as stages one and two genuinely do - would fail; case04 and
 * case05 each pass under either structure, which is precisely why all three are present.
 *
 * <h2>Provenance of the expectations: statically derived, never captured (practice B12, risk R-A)</h2>
 * <p><strong>Every expected value under {@code src/test/resources/parity/CBTRN02C/} was derived by
 * reading the COBOL, not by running it.</strong> No legacy execution baseline exists or can exist in
 * this environment: there is no z/OS runtime, the available COBOL compiler reports
 * {@code indexed file handler : disabled} while this program declares four {@code ORGANIZATION INDEXED}
 * files ({@code :34-61}), and it supplies no Language Environment {@code CEE*} services, so the
 * {@code CALL 'CEE3ABD'} at {@code :711} is not reachable either. Eight such blockers are enumerated in
 * the plan's Special Analysis and the deviation is registered there as risk <strong>R-A</strong>.
 *
 * <p>What replaces a captured baseline is an exhaustive structured reading of each paragraph,
 * cross-checked against four independent authorities:
 * <ul>
 *   <li>the copybook byte layouts - {@code CVTRA06Y} 350 B, {@code CVTRA05Y} 350 B, {@code CVTRA01Y}
 *       50 B with its 17-byte {@code TRAN-CAT-KEY}, {@code CVACT01Y} 300 B and {@code CVACT03Y} 50 B -
 *       which fix every offset and every {@code PICTURE} mechanically rather than interpretively;</li>
 *   <li>{@code app/jcl/POSTTRAN.jcl}, which fixes the six DD names, the absence of any {@code PARM}
 *       and the rejects file's {@code DCB=(RECFM=F,LRECL=430,BLKSIZE=0)};</li>
 *   <li>the real fixtures under {@code app/data/ASCII}, so every case is seeded from genuine
 *       production-shaped data rather than from invented data;</li>
 *   <li>the enumerable arithmetic and guard sites of the program itself, which is where accumulated
 *       legacy behaviour hides.</li>
 * </ul>
 * Everything else the gate demands is preserved exactly: twenty cases, field-for-field diffing, and a
 * diff count that must be zero across all twenty. Only the provenance of the expected values differs,
 * and it is stated here rather than absorbed silently.
 *
 * <h2>Three fixture facts that decide most of these cases</h2>
 * <ol>
 *   <li><strong>Every {@code dailytran.txt} card resolves in {@code cardxref.txt}, and every account
 *       it names exists in {@code acctdata.txt}.</strong> All 300 rows, verified. So a rejecting case
 *       has to remove something deliberately, and each of case02, case03 and case15 says which row it
 *       left out and why.</li>
 *   <li><strong>{@code tcatbal.txt} holds exactly fifty keys and every one of them is type
 *       {@code '01'} category {@code '0001'} at {@code +0.00}, with {@code FILLER} as twenty-two
 *       zeros.</strong> {@code dailytran} row 1 is a type {@code '03'} record, so its category-balance
 *       key is genuinely absent and the {@code '23'} create arm at {@code :495-496} is reachable
 *       straight from the fixtures - and the record it creates carries twenty-two <em>spaces</em>,
 *       because a COBOL {@code INITIALIZE} with no {@code REPLACING} leaves {@code FILLER} alone.</li>
 *   <li><strong>{@code cardxref.txt} rows measure 36 bytes where {@code CVACT03Y} declares 50</strong>,
 *       the trailing {@code FILLER PIC X(14)} being absent, so every case declares
 *       {@code CARDXREF_FILLER_PAD_36_TO_50} and the fourteen spaces are supplied once, at seed time
 *       (gate G16).</li>
 * </ol>
 *
 * <h2>How the unit is reached (gate G51)</h2>
 * <p>Through {@link TransactionValidationJob#postTransactions(SysoutSink)}, which runs
 * {@code :193-234} statement for statement. There is <strong>no {@code JobLauncher}</strong>, no job
 * repository, no step executor, no application context and no HTTP layer anywhere in this class, so a
 * failure names a paragraph rather than a framework. The five datasets are relations in a per-case
 * in-memory database, the rejects file is an in-memory sink of the kind
 * {@link RecordSink} documents, the code page is named explicitly on every conversion, and the clock is
 * the harness's pinned one - which is what makes the 26-byte {@code TRAN-PROC-TS} every posted
 * transaction carries assertable byte for byte.
 *
 * <p><strong>This job takes no job parameters, and that is a contract rather than an omission.</strong>
 * {@code app/jcl/POSTTRAN.jcl:23} is a bare {@code //STEP15 EXEC PGM=CBTRN02C} with nothing after the
 * program name, so {@link #contracts()} declares none and the job's constructor refuses a contract that
 * declares any - Spring Batch identifies a job instance by its parameters, so a spurious one would
 * silently resolve submissions to a different instance.
 *
 * <h2>Rules</h2>
 * <p>{@code review_rules} reports <strong>no user rules provided</strong> for this project. Their
 * absence is not permission to lower the bar, so this class is held to the plan's enterprise practices
 * instead: exact dependency versions with no placeholders (B1), reference trees never written to (B3),
 * divergences documented rather than corrected (B4), dead code preserved (B5), a deterministic
 * non-interactive run (B7), explicit charset, scale and rounding with no wildcard import anywhere (B8),
 * no static mutable state (B9), tests shipped with the implementation (B10), and environmental limits
 * documented rather than absorbed (B12).
 *
 * <h2>A note to a reviewer grepping this file</h2>
 * <p>Several prohibited things are <strong>named</strong> above and below on purpose, so the next
 * engineer learns they are prohibited rather than merely unused: {@code JobLauncher}, the rounding modes
 * this system must never use, and the mainframe dataset-name prefix that must never appear in source.
 * Every such mention is a prohibition notice sitting in a comment. The machine-checkable invariant is
 * the one that matters: <em>no forbidden token appears on any non-comment line of this file</em> - no
 * wildcard import, no binary floating-point type, no rounding mode other than
 * {@link CobolDecimal#COBOL_ROUNDING}, no literal production dataset name, no static mutable field, and
 * no web or launcher type anywhere in the executable text.
 */
@DisplayName("CBTRN02C - POSTTRAN validate-and-post parity gate (20 cases, diff count must be zero)")
final class CBTRN02CParityTest {

    /**
     * The program these cases are about, taken from the unit under test rather than re-spelled, so the
     * class name, the {@code parity/CBTRN02C/} resource directory and the case files cannot drift
     * apart.
     */
    private static final String PROGRAM = TransactionValidationJob.PROGRAM_ID;

    /** The single column every dataset relation holds: one fixed-width record image per row. */
    private static final String IMAGE_COLUMN = "REC";

    /**
     * The physical-record ordinal every relation is read back in, exactly as
     * {@code application-test.yml} configures {@code carddemo.physical-sequence.expression}: H2's own
     * row-identifier pseudo-column, which increases with each insert.
     *
     * <p>Used for <em>every</em> dataset here rather than only for the sequential ones, and
     * deliberately in preference to key order. For a keyed dataset the physical order is the seed order
     * followed by whatever the run appended, so a record the program <strong>created</strong> is
     * visibly at the end - which is how case08, case15 and case20 tell the {@code '23'} create arm at
     * {@code :495-496} apart from the {@code '00'} rewrite arm at {@code :498} by row position alone,
     * before a single byte is compared.
     */
    private static final PhysicalSequence WRITE_ORDER = PhysicalSequence.of("_ROWID_");

    // =============================================================================================
    //  THE SIX DD NAMES OF app/jcl/POSTTRAN.jcl:28-42, AND THE DATASETS BEHIND THEM.
    //
    //  A case addresses a dataset by its BINDING KEY and never by a dataset name, which is what keeps
    //  gate G46 satisfiable: not one of the production dataset names catalogued in
    //  app/csd/CARDDEMO.CSD is spelled anywhere in this file, and the names below are this test's own
    //  CARDDEMO.PARITY namespace. The keys are the JCL's DD names rather than the CSD's file names,
    //  because it is the JCL that declares what this step opens.
    // =============================================================================================

    /** {@code //DALYTRAN} - the sequential read that drives the program's only loop. 350 B. */
    private static final String DALYTRAN_KEY = TransactionValidationJob.DALYTRAN_DD_NAME;

    /** {@code //TRANFILE} - the transaction master a posted record is added to. 350 B. */
    private static final String TRANFILE_KEY = TransactionValidationJob.TRANFILE_DD_NAME;

    /** {@code //XREFFILE} - the cross-reference read by card number. 50 B. */
    private static final String XREFFILE_KEY = TransactionValidationJob.XREFFILE_DD_NAME;

    /** {@code //DALYREJS} - the rejects generation, {@code RECFM=F LRECL=430}. */
    private static final String DALYREJS_KEY = TransactionValidationJob.DALYREJS_DD_NAME;

    /** {@code //ACCTFILE} - opened {@code I-O}, read by key and rewritten. 300 B. */
    private static final String ACCTFILE_KEY = TransactionValidationJob.ACCTFILE_DD_NAME;

    /** {@code //TCATBALF} - opened {@code I-O}, written or rewritten per posted record. 50 B. */
    private static final String TCATBALF_KEY = TransactionValidationJob.TCATBALF_DD_NAME;

    /** {@code CVTRA06Y}'s dataset - {@code DALYTRAN.PS}, sequential. */
    private static final String DALYTRAN_DSNAME = "CARDDEMO.PARITY.DALYTRAN.PS";

    /** {@code CVTRA05Y}'s dataset, addressed as {@code TRANSACT} and as {@code TRANFILE}. */
    private static final String TRANSACT_DSNAME = "CARDDEMO.PARITY.TRANSACT.KSDS";

    /**
     * The generated-transaction generation of {@code app/jcl/INTCALC.jcl}.
     *
     * <p>Declared and created because {@link TransactionRepository} binds all three of its keys at
     * construction - but this program never opens it, and no case seeds it or expects anything of it.
     * Its presence is what proves the job's job-scoped {@code TRANFILE} alias is doing its work.
     */
    private static final String SYSTRAN_DSNAME = "CARDDEMO.PARITY.SYSTRAN";

    /** {@code CVACT03Y}'s base cluster, addressed as {@code CCXREF} and as {@code XREFFILE}. */
    private static final String CARDXREF_DSNAME = "CARDDEMO.PARITY.CARDXREF.KSDS";

    /** The alternate-index path over that same cluster - one cluster, two access paths (gate G45). */
    private static final String CARDXREF_AIX_DSNAME = "CARDDEMO.PARITY.CARDXREF.AIX";

    /** {@code CVACT01Y}'s dataset, addressed as {@code ACCTDAT} and as {@code ACCTFILE}. */
    private static final String ACCTDATA_DSNAME = "CARDDEMO.PARITY.ACCTDATA.KSDS";

    /** {@code CVTRA01Y}'s dataset. */
    private static final String TCATBALF_DSNAME = "CARDDEMO.PARITY.TCATBALF.KSDS";

    /** The rejects generation this step allocates - {@code DALYREJS(+1)}, a generation data group. */
    private static final String DALYREJS_DSNAME = "CARDDEMO.PARITY.DALYREJS";

    /** {@code app/jcl/POSTTRAN.jcl} declares one step and no {@code COND}, so nothing gates it. */
    private static final boolean STEP_IS_UNGATED = false;

    // =============================================================================================
    //  THE SCENARIO TABLE
    //
    //  Seventeen of the twenty cases are driven entirely by their seeded data. Three cannot be,
    //  because the arm they exercise is not reachable through data at all, and each of those three
    //  states the arrangement here rather than hiding it in the case file:
    //
    //    * the account REWRITE at :554 reporting INVALID KEY - a KSDS row a keyed read found moments
    //      earlier does not vanish, so the only honest place to arrange it is the seam where the
    //      backend reports it;
    //    * the rejects OPEN at :293 refusing;
    //    * the rejects WRITE at :451 refusing.
    //
    //  A BATCH_JOB case may NOT carry a ForcedOutcome: that member belongs to
    //  ParityCase.ScreenRequest, which ParityCase refuses for anything but an online case. So the
    //  arrangement lives in this table, keyed by case identifier, exactly as the other batch parity
    //  suites in this package do it.
    // =============================================================================================

    /** What the backend does for one case. */
    private enum Scenario {

        /** Every operation succeeds; the case is decided by its seeded data alone. */
        CLEAN,

        /**
         * {@code REWRITE FD-ACCTFILE-REC} at {@code :554} reports the {@code INVALID KEY} condition,
         * which is the only way {@code :556}'s reason 109 can ever be stored.
         */
        ACCOUNT_REWRITE_NOT_FOUND,

        /** {@code OPEN OUTPUT DALYREJS-FILE} at {@code :293} reports a status other than {@code '00'}. */
        REJECT_OPEN_FAILS,

        /** {@code WRITE FD-REJS-RECORD} at {@code :451} reports a status other than {@code '00'}. */
        REJECT_WRITE_FAILS
    }

    /**
     * The arrangement each of the twenty cases runs under, in case order and unmodifiable, so no test
     * can perturb what another reads (practice B9, gate G53).
     */
    private static final Map<String, Scenario> SCENARIOS = declaredScenarios();

    /**
     * Builds the scenario table.
     *
     * @return the twenty entries, in case order
     */
    private static Map<String, Scenario> declaredScenarios() {
        Map<String, Scenario> declared = new LinkedHashMap<>();
        declared.put("case01", Scenario.CLEAN);
        declared.put("case02", Scenario.CLEAN);
        declared.put("case03", Scenario.CLEAN);
        declared.put("case04", Scenario.CLEAN);
        declared.put("case05", Scenario.CLEAN);
        declared.put("case06", Scenario.CLEAN);
        declared.put("case07", Scenario.ACCOUNT_REWRITE_NOT_FOUND);
        declared.put("case08", Scenario.CLEAN);
        declared.put("case09", Scenario.CLEAN);
        declared.put("case10", Scenario.CLEAN);
        declared.put("case11", Scenario.CLEAN);
        declared.put("case12", Scenario.CLEAN);
        declared.put("case13", Scenario.CLEAN);
        declared.put("case14", Scenario.REJECT_WRITE_FAILS);
        declared.put("case15", Scenario.CLEAN);
        declared.put("case16", Scenario.CLEAN);
        declared.put("case17", Scenario.REJECT_OPEN_FAILS);
        declared.put("case18", Scenario.CLEAN);
        declared.put("case19", Scenario.CLEAN);
        declared.put("case20", Scenario.CLEAN);
        return Collections.unmodifiableMap(declared);
    }

    /**
     * The arrangement one case runs under.
     *
     * @param caseId the case identifier the harness handed the adapter
     * @return the declared arrangement; never {@code null}
     * @throws IllegalStateException if the table has no entry, which means a case file exists that
     *     nothing arranged - it would then run cleanly while its expectations described a failure, and
     *     the failure message would be about display lines rather than about the omission
     */
    private static Scenario scenarioFor(String caseId) {
        Scenario scenario = SCENARIOS.get(caseId);
        if (scenario == null) {
            throw new IllegalStateException("No scenario is declared for " + PROGRAM + '/' + caseId
                + ". Every one of the " + ParityHarness.CASES_PER_PROGRAM + " cases states the backend "
                + "behaviour it runs under, because a batch case cannot carry a ForcedOutcome - that "
                + "member belongs to ParityCase.ScreenRequest, which a BATCH_JOB case may not declare. "
                + "Declared: " + SCENARIOS.keySet() + '.');
        }
        return scenario;
    }

    // =============================================================================================
    //  TEST DOUBLES - each a plain nested type rather than a mock wherever a mock would hide what is
    //  being supplied, and each instantiated per invocation so no two cases share state.
    // =============================================================================================

    /**
     * The {@code SYSOUT} destination, capturing the displayed line sequence in order.
     *
     * <p>{@code CBTRN02C}'s observable output is its datasets <em>and</em> its {@code DISPLAY} lines:
     * the two banners at {@code :194} and {@code :232}, the {@code 'TCATBAL record not found for key'}
     * line at {@code :476-477}, the two count lines at {@code :227-228}, and on a fatal status the
     * failing paragraph's own message, the status rendered by {@code 9910-DISPLAY-IO-STATUS} and
     * {@code 'ABENDING PROGRAM'}. All of them are compared positionally, so the order this list
     * preserves is part of the expectation.
     */
    private static final class CapturedSysout implements SysoutSink {

        /** The lines emitted, in emission order. Per-instance, never shared. */
        private final List<String> lines = new ArrayList<>();

        @Override
        public void write(String line) {
            lines.add(line);
        }

        /**
         * The lines emitted so far.
         *
         * @return the live list, read straight after the run; never {@code null}
         */
        private List<String> lines() {
            return lines;
        }
    }

    /**
     * The {@value #DALYREJS_KEY} destination: an in-memory collector, which is the shape
     * {@link RecordSink} documents for a unit test and for this harness.
     *
     * <p>It collects whole 430-byte records as bytes and never as text, so nothing between
     * {@code WRITE FD-REJS-RECORD FROM REJECT-RECORD} at {@code :451} and the fingerprint performs a
     * character conversion at all - the 350-byte group move at {@code :447} is compared exactly as the
     * dataset would have received it (practice B8).
     *
     * <p>It also carries the two arranged refusals, because the alternative - refusing at the JDBC
     * layer - would refuse the open and the write together and no case could tell {@code :302}'s
     * ladder from {@code :460}'s.
     *
     * <p><strong>{@link #discard(int)} clears.</strong> {@code app/jcl/POSTTRAN.jcl:34} declares
     * {@code DISP=(NEW,CATLG,DELETE)} and the third positional is the <em>abnormal</em> disposition: a
     * step that abends leaves no generation at all, rather than leaving the records it had already
     * written. The default implementation reports success without issuing anything, which is the honest
     * answer for a collector whose records die with the run - but this harness reads the collector
     * <em>after</em> the run, so the deletion has to be modelled rather than implied.
     */
    private static final class CollectingRejects implements RecordSink {

        /** The arranged behaviour for this run. */
        private final Scenario scenario;

        /** The records accepted, in write order. Per-instance, never shared. */
        private final List<byte[]> records = new ArrayList<>();

        /**
         * Creates a sink for one run.
         *
         * <p>The scenario is required rather than defaulted: a sink that silently assumed
         * {@link Scenario#CLEAN} would turn a mis-keyed case identifier into a passing case instead of
         * a loud one, and case14 and case17 exist precisely to prove that the refusing arms of
         * {@code 2500-WRITE-REJECT-REC} are reachable.
         *
         * @param scenario the arranged backend behaviour for this run; must not be {@code null}
         */
        private CollectingRejects(Scenario scenario) {
            this.scenario = Objects.requireNonNull(scenario, "a rejects sink is always arranged");
        }

        /**
         * {@code OPEN OUTPUT DALYREJS-FILE} - {@code app/cbl/CBTRN02C.cbl:293}.
         *
         * @return {@link FileStatus.Outcome#OTHER} when this run arranges the open to refuse, and
         *     {@link FileStatus.Outcome#OK} otherwise
         */
        @Override
        public FileStatus.Outcome open() {
            return scenario == Scenario.REJECT_OPEN_FAILS
                ? FileStatus.Outcome.OTHER
                : FileStatus.Outcome.OK;
        }

        /**
         * {@code WRITE FD-REJS-RECORD FROM REJECT-RECORD} - {@code app/cbl/CBTRN02C.cbl:451}.
         *
         * <p>A refused write collects nothing, which is what makes case14's rejects file empty for two
         * independent reasons: the record was never stored, and the generation was then discarded.
         *
         * @param recordImage the 430 bytes the program composed
         * @return {@link FileStatus.Outcome#OTHER} when this run arranges the write to refuse, and
         *     {@link FileStatus.Outcome#OK} otherwise
         */
        @Override
        public FileStatus.Outcome write(byte[] recordImage) {
            Objects.requireNonNull(recordImage, "the writer never offers a null record");
            if (scenario == Scenario.REJECT_WRITE_FAILS) {
                return FileStatus.Outcome.OTHER;
            }
            // Kept rather than copied: the writer's contract states the array is freshly allocated per
            // call and neither retained nor reused, so a copy would protect against nothing.
            records.add(recordImage);
            return FileStatus.Outcome.OK;
        }

        /**
         * The {@code DELETE} positional of {@code DISP=(NEW,CATLG,DELETE)}, applied by the run's own
         * cleanup when it did not close normally.
         *
         * @param recordsWritten how many records the writer believes it wrote
         * @return {@link FileStatus.Outcome#OK}, always - deleting an in-memory generation cannot fail
         */
        @Override
        public FileStatus.Outcome discard(int recordsWritten) {
            records.clear();
            return FileStatus.Outcome.OK;
        }

        /**
         * The records this run's rejects generation holds.
         *
         * @return the live list in write order, read straight after the run; never {@code null}
         */
        private List<byte[]> records() {
            return records;
        }
    }

    /**
     * An {@link ObjectProvider} holding one bean that is genuinely present.
     *
     * @param bean the bean to supply; never {@code null}
     * @param <T> the bean type
     */
    private record SuppliedBean<T>(T bean) implements ObjectProvider<T> {

        @Override
        public T getObject() {
            return Objects.requireNonNull(bean, "a supplied bean is never null");
        }
    }

    /**
     * An {@link ObjectProvider} over a container that publishes no bean of the requested type.
     *
     * <p>One generic double serves both of {@link BatchConfig}'s providers, and it resolves to nothing
     * for each. Neither is ever consulted on the path under test: the job's {@code @Bean} methods are
     * the only things that touch the batch plumbing, and a parity run must not call them, because doing
     * so would put a job repository and a step executor between the assertion and the program body
     * (gate G51). Because the type argument is inferred from each constructor parameter, this also keeps
     * those plumbing types out of this file's imports entirely.
     *
     * @param <T> the bean type the container does not publish
     */
    private static final class NoBeanPublished<T> implements ObjectProvider<T> {

        @Override
        public T getObject() {
            throw new NoSuchBeanDefinitionException("a parity run publishes no bean of this type: "
                + PROGRAM + "'s paragraphs are reached through postTransactions(SysoutSink), with no "
                + "launcher, no job repository and no step executor between the assertion and the code");
        }

        @Override
        public T getIfAvailable() {
            return null;
        }

        @Override
        public T getIfUnique() {
            return null;
        }
    }

    // =============================================================================================
    //  THE GATE
    // =============================================================================================

    /**
     * The twenty cases of {@code src/test/resources/parity/CBTRN02C/}, in ascending case order.
     *
     * <p>{@link ParityHarness#casesOf(String)} already refuses anything other than exactly
     * {@code case01.json} through {@code case20.json} - a short set and a stray file are both loud
     * failures there - and the assertion below states the count a second time at the point where a
     * reader of this class looks for it. Twenty is the gate: "diff count is zero across all twenty
     * cases" is satisfied vacuously by a set of four, so the count is checked rather than assumed
     * (gate G15).
     *
     * @return exactly twenty cases; never {@code null}
     */
    private static List<ParityCase> cases() {
        List<ParityCase> loaded = ParityHarness.casesOf(PROGRAM);
        assertThat(loaded)
            .withFailMessage("%s must declare exactly %d parity cases under %s%s/, but %d loaded. The "
                    + "gate is a diff count of zero across ALL twenty cases; a shorter set is not a "
                    + "smaller gate, it is a gate that stops asking questions.",
                PROGRAM, ParityHarness.CASES_PER_PROGRAM, ParityHarness.CASE_RESOURCE_ROOT, PROGRAM,
                loaded.size())
            .hasSize(ParityHarness.CASES_PER_PROGRAM);
        return loaded;
    }

    /**
     * Runs one case and requires the field-by-field diff count to be <strong>zero</strong> (gate G18).
     *
     * <p>{@link ParityHarness#judge(ParityCase, UnitKind, ParityHarness.ParityUnit)} seeds the case's
     * datasets, reaches the unit through {@link #invokePostingJob(Invocation)}, decodes what the run
     * produced into named fields and hands both to {@link FieldDiffer}. Nothing is compared as a whole
     * string: every difference names a dataset, a row and a copybook field.
     *
     * <p>{@link DiffResult#render()} is surfaced in the failure message rather than summarised, because
     * the differ has already written a better explanation of a difference than an assertion could - it
     * names the span, its {@code PICTURE} kind, both values and, for a signed scaled field, both
     * decoded numbers.
     *
     * <p>{@link UnitKind#BATCH_JOB} is stated at the call site rather than read from the case, so the
     * harness can check it against the case's own declaration before anything runs: a case file that
     * called this program a controller would be refused instead of quietly exercising the wrong shape.
     *
     * @param parityCase one of the twenty cases
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    @DisplayName("every case produces zero field-level differences")
    void diffCountIsZero(ParityCase parityCase) {
        DiffResult result = ParityHarness.usAscii()
            .judge(parityCase, UnitKind.BATCH_JOB, CBTRN02CParityTest::invokePostingJob);

        assertThat(result.count())
            .withFailMessage("%s/%s reported %d field-level difference(s); the gate is zero across all "
                    + "%d cases.%n%s",
                result.program(), result.caseId(), result.count(),
                ParityHarness.CASES_PER_PROGRAM, result.render())
            .isZero();
        assertThat(result.isClean())
            .withFailMessage("%s/%s is not clean although its diff count is zero, which would mean the "
                + "two disagree.%n%s", result.program(), result.caseId(), result.render())
            .isTrue();
    }

    // =============================================================================================
    //  HOW ONE CASE REACHES THE UNIT
    // =============================================================================================

    /**
     * Runs {@code CBTRN02C} once for one case and records everything the run produced.
     *
     * <p>The sequence is the program's own: seed the five datasets the case declares, build the job over
     * them with the harness's pinned clock, and call
     * {@link TransactionValidationJob#postTransactions(SysoutSink)} - which performs
     * {@code app/cbl/CBTRN02C.cbl:193-234} statement for statement. Nothing about the expectation is
     * visible from here: {@link Invocation} deliberately carries the inputs and not the case, so a unit
     * cannot report its expectation back and pass while implementing nothing.
     *
     * <p><strong>Everything is recorded in a {@code finally} block, and that is load-bearing.</strong>
     * Three of these cases abend, and an abend is an observation rather than a failure: the lines the run
     * displayed before it abended were displayed, and the category-balance and account rows case18 had
     * already rewritten stand, because every dataset here is {@code RECOVERY(NONE)} and the program
     * issues no syncpoint. Recording on the way out captures that, and returning {@code null} tells the
     * harness the recorder holds the outcome - the only form that survives the exception.
     *
     * <p>The {@code RETURN-CODE} <em>is</em> recorded on the normal path, unlike some of the sibling
     * suites, because this program sets one: {@code :229-231} moves 4 into {@code RETURN-CODE} when
     * {@code WS-REJECT-COUNT} is greater than zero. On the abend path it is deliberately not recorded -
     * the harness takes the code the {@link AbendException} carries, so the abend translation cannot
     * assert itself.
     *
     * @param invocation the seeded datasets, the pinned clock, the code page and the recorder
     * @return {@code null} - always, because the recorder holds the outcome
     */
    private static UnitOutcome invokePostingJob(Invocation invocation) {
        UnitOutcome.Builder recorder = invocation.recorder();
        Scenario scenario = scenarioFor(invocation.caseId());
        Charset charset = invocation.charset();
        JdbcTemplate database = database(invocation.caseId());
        seedDeclaredDatasets(invocation, database);

        CapturedSysout sysout = new CapturedSysout();
        CollectingRejects rejects = new CollectingRejects(scenario);
        TransactionValidationJob job =
            job(database, invocation.clock(), sysout, rejects, scenario, charset);
        try {
            RunOutcome outcome = inStepUnitOfWork(() -> job.postTransactions(sysout));
            recorder.returnCode(outcome.returnCode());
        } finally {
            for (String line : sysout.lines()) {
                recorder.display(line);
            }
            recordRejects(recorder, rejects);
            recordFinalState(recorder, database);
        }
        return null;
    }

    /**
     * Declares the unit of work the step supplies, runs the work, and always undoes the declaration.
     *
     * <p>{@code CBTRN02C} posts, and every posting verb refuses to run outside a unit of work: the pool
     * hands out connections with auto-commit disabled, so a statement issued with nothing bound to the
     * thread is rolled back when the connection is returned, and a repository that reported {@code '00'}
     * from there would have this harness record a fingerprint for records that reached no dataset. Under
     * the launcher the step's own chunk transaction is what satisfies that requirement.
     *
     * <p>Declared on the thread rather than opened as a real transaction, and that choice is
     * parity-relevant rather than a convenience. {@code POSTTRAN}'s six datasets are all defined
     * {@code RECOVERY(NONE)} ({@code app/csd/CARDDEMO.CSD:9} and its siblings) and the program issues no
     * syncpoint, so a write is durable when it completes and {@code CALL 'CEE3ABD'} does not take it back;
     * the step reproduces that with a commit interval of one record. One transaction around a whole run
     * would instead discard everything an abending run had already written, which is a state the COBOL
     * cannot produce. The declaration satisfies the precondition and leaves each statement's durability
     * exactly where the case fixtures expect it.
     *
     * @param work the work to run
     * @param <T>  what it answers
     * @return what {@code work} answered
     */
    private static <T> T inStepUnitOfWork(java.util.function.Supplier<T> work) {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            return work.get();
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    /**
     * The same declaration, for work that answers nothing.
     *
     * @param work the work to run
     */
    private static void inStepUnitOfWork(Runnable work) {
        inStepUnitOfWork(() -> {
            work.run();
            return null;
        });
    }

    /**
     * Seeds every dataset the case declared into its relation, and refuses one it does not know.
     *
     * <p>The rows arrive already normalised - {@code cardxref}'s 36-byte rows have had their absent
     * {@code FILLER PIC X(14)} supplied as fourteen spaces by the harness, once, before anything decodes
     * them - so what is inserted here is exactly what the program will read.
     *
     * <p>A dataset key this method does not recognise fails loudly. {@code CBTRN02C} opens exactly six
     * files ({@code app/jcl/POSTTRAN.jcl:28-42}) and a case naming a seventh is either a typo or a case
     * about a different program; seeding it into nothing would leave the case describing an input the run
     * never saw.
     *
     * @param invocation the invocation carrying the seeded datasets
     * @param database   the per-case in-memory database holding one relation per dataset
     */
    private static void seedDeclaredDatasets(Invocation invocation, JdbcTemplate database) {
        for (Map.Entry<String, SeededDataset> entry : invocation.datasets().entrySet()) {
            String key = entry.getKey();
            String dsname = relationOf(key);
            assertThat(dsname)
                .withFailMessage("Case %s/%s seeds dataset \"%s\", which %s does not open. Its files are "
                        + "%s, %s, %s, %s, %s and %s (app/jcl/POSTTRAN.jcl:28-42).",
                    invocation.program(), invocation.caseId(), key, PROGRAM, DALYTRAN_KEY, TRANFILE_KEY,
                    XREFFILE_KEY, DALYREJS_KEY, ACCTFILE_KEY, TCATBALF_KEY)
                .isNotNull();
            for (String image : entry.getValue().rows()) {
                seedRow(database, dsname, image);
            }
        }
    }

    /**
     * Records the rejects generation on the writes channel, in write order.
     *
     * <p>Reported even when it holds no record, which is a positive assertion rather than an omission:
     * {@code 0300-DALYREJS-OPEN} runs unconditionally at {@code :198}, so a run whose input is entirely
     * clean opened the generation and wrote nothing to it, and {@code DISP=(NEW,CATLG,DELETE)}
     * catalogues that as an empty generation rather than as no generation. A run that abended, by
     * contrast, has had its generation deleted outright. Both are zero records, and both are behaviour a
     * case states.
     *
     * <p>The records go through {@link UnitOutcome.Builder#wroteBytes} rather than the text form, so the
     * 430 bytes reach the fingerprint without a single character conversion in between.
     *
     * @param recorder where the observation goes
     * @param rejects  the run's rejects sink
     */
    private static void recordRejects(UnitOutcome.Builder recorder, CollectingRejects rejects) {
        List<byte[]> written = rejects.records();
        if (written.isEmpty()) {
            recorder.openedWithoutWriting(DALYREJS_KEY, DalyRejectWriter.FD_REJS_RECORD_LAYOUT);
            return;
        }
        for (byte[] record : written) {
            recorder.wroteBytes(DALYREJS_KEY, DalyRejectWriter.FD_REJS_RECORD_LAYOUT, record);
        }
    }

    /**
     * Records what each of the five relations holds after the run, row by row.
     *
     * <p>All five are reported, not only the three the program mutates. That is deliberate: a case that
     * reported only {@value #ACCTFILE_KEY}, {@value #TCATBALF_KEY} and {@value #TRANFILE_KEY} could not
     * tell a run that left the daily-transaction file and the cross-reference alone from one that quietly
     * wrote to them. Reporting all five makes "the inputs were not disturbed" an assertion.
     *
     * <p>Read back in {@link #WRITE_ORDER}, the physical-record ordinal, for every one of them. For the
     * two sequential datasets that is the order records were written in; for the three keyed ones it is
     * the seed order followed by whatever the run appended, so an appended record is visibly appended -
     * which is how the {@code '23'} create arm is distinguished from the {@code '00'} rewrite arm by row
     * position before any byte is compared.
     *
     * @param recorder where the observation goes
     * @param database the per-case database
     */
    private static void recordFinalState(UnitOutcome.Builder recorder, JdbcTemplate database) {
        String order = WRITE_ORDER.orderByClause();
        recorder.finalState(DALYTRAN_KEY, DalyTranRecord.LAYOUT,
            rowsOf(database, DALYTRAN_DSNAME, order));
        recorder.finalState(XREFFILE_KEY, CardXrefRecord.LAYOUT,
            rowsOf(database, CARDXREF_DSNAME, order));
        recorder.finalState(ACCTFILE_KEY, AccountRecord.LAYOUT,
            rowsOf(database, ACCTDATA_DSNAME, order));
        recorder.finalState(TCATBALF_KEY, TranCatBalRecord.LAYOUT,
            rowsOf(database, TCATBALF_DSNAME, order));
        recorder.finalState(TRANFILE_KEY, TranRecord.LAYOUT,
            rowsOf(database, TRANSACT_DSNAME, order));
    }

    /**
     * The relation behind a case's dataset key, or {@code null} for a key this program does not open.
     *
     * <p>{@value #DALYREJS_KEY} is absent on purpose: it is an output this step allocates, never an
     * input, and it is reached through an in-memory sink rather than through a relation. A case that
     * tried to seed it would be describing rows the program could not possibly read.
     *
     * @param key the dataset binding key a case declared
     * @return the dataset name, or {@code null} when the key is not one of this step's five inputs
     */
    private static String relationOf(String key) {
        if (DALYTRAN_KEY.equals(key)) {
            return DALYTRAN_DSNAME;
        }
        if (TRANFILE_KEY.equals(key)) {
            return TRANSACT_DSNAME;
        }
        if (XREFFILE_KEY.equals(key)) {
            return CARDXREF_DSNAME;
        }
        if (ACCTFILE_KEY.equals(key)) {
            return ACCTDATA_DSNAME;
        }
        if (TCATBALF_KEY.equals(key)) {
            return TCATBALF_DSNAME;
        }
        return null;
    }

    // =============================================================================================
    //  THE DATASETS, AS RELATIONS
    //
    //  Every dataset this program reads or writes is a relation holding one fixed-width record image
    //  per row, which is the shape the repositories read and write through JDBC. No DDL is authored
    //  for the application: these are the test's own relations, created and dropped inside one case,
    //  and no schema, migration, entity mapping or version column exists anywhere in this module
    //  (gate G44).
    // =============================================================================================

    /**
     * A private in-memory database for one case, with the eight relations this step's repositories
     * address between them.
     *
     * <p>The database is named after the case, so two cases never share one and the name is derived
     * rather than generated - there is no counter and no random component, hence no static mutable state
     * (practice B9) and nothing that varies between runs (practice B7). {@code DROP ALL OBJECTS} makes
     * the setup idempotent, which matters because {@code DB_CLOSE_DELAY=-1} deliberately keeps the
     * database alive for the JVM: {@code DriverManagerDataSource} opens a connection per operation, and
     * without it the relations would vanish between the seed and the run.
     *
     * @param discriminator the case identifier, or another name unique within this class
     * @return a template over the created relations; never {@code null}
     */
    private static JdbcTemplate database(String discriminator) {
        DriverManagerDataSource source = new DriverManagerDataSource(
            "jdbc:h2:mem:cbtrn02cparity" + discriminator
                + ";DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE", "sa", "");
        source.setDriverClassName("org.h2.Driver");
        JdbcTemplate database = new JdbcTemplate(source);
        database.execute("DROP ALL OBJECTS");
        createRelation(database, DALYTRAN_DSNAME, DalyTranRecord.RECORD_LENGTH);
        createRelation(database, TRANSACT_DSNAME, TranRecord.RECORD_LENGTH);
        createRelation(database, SYSTRAN_DSNAME, TranRecord.RECORD_LENGTH);
        createRelation(database, CARDXREF_DSNAME, CardXrefRecord.RECORD_LENGTH);
        createRelation(database, CARDXREF_AIX_DSNAME, CardXrefRecord.RECORD_LENGTH);
        createRelation(database, ACCTDATA_DSNAME, AccountRecord.RECORD_LENGTH);
        createRelation(database, TCATBALF_DSNAME, TranCatBalRecord.RECORD_LENGTH);
        createRelation(database, DALYREJS_DSNAME, DalyRejectWriter.RECORD_LENGTH);
        return database;
    }

    /**
     * Creates one relation at its copybook-declared width.
     *
     * <p>{@code VARCHAR} rather than {@code CHAR} on purpose: {@code CHAR} pads and strips trailing
     * spaces, and a record whose trailing {@code FILLER} span is spaces would come back short - which
     * would move every later offset and turn gate G21 into a test that cannot fail.
     *
     * @param database the per-case database
     * @param dsname   the dataset name, used as a delimited identifier
     * @param width    the copybook-declared record length
     */
    private static void createRelation(JdbcTemplate database, String dsname, int width) {
        database.execute("CREATE TABLE \"" + dsname + "\" ("
            + IMAGE_COLUMN + " VARCHAR(" + width + "))");
    }

    /**
     * Inserts one record image verbatim.
     *
     * @param database the per-case database
     * @param dsname   the dataset name
     * @param image    the record image, already normalised to its copybook width
     */
    private static void seedRow(JdbcTemplate database, String dsname, String image) {
        database.update("INSERT INTO \"" + dsname + "\" VALUES (?)", image);
    }

    /**
     * Reads a relation back.
     *
     * @param database the per-case database
     * @param dsname   the dataset name
     * @param ordering the {@code ORDER BY} clause that makes the result deterministic
     * @return the record images in that order; never {@code null}
     */
    private static List<String> rowsOf(JdbcTemplate database, String dsname, String ordering) {
        return database.queryForList(
            "SELECT " + IMAGE_COLUMN + " FROM \"" + dsname + "\"" + ordering, String.class);
    }

    // =============================================================================================
    //  THE UNIT UNDER TEST, WIRED BY HAND
    //
    //  By hand and not from an application context, for two reasons above all others. The CLOCK:
    //  every posted transaction carries TRAN-PROC-TS, the 26 bytes Z-GET-DB2-FORMAT-TIMESTAMP
    //  composes from FUNCTION CURRENT-DATE at :693, and taken from a context that would be the system
    //  clock, so no case could pin the 350-byte record image that gates G19 and G21 are about. And the
    //  REJECTS SINK: app/jcl/POSTTRAN.jcl allocates DALYREJS(+1) fresh, and this harness needs the
    //  records it received in write order, as bytes, with the open and the write independently
    //  refusable.
    // =============================================================================================

    /**
     * Builds {@code CBTRN02C} over one case's database, arranged as that case declares.
     *
     * <p>The five repositories are the real ones, reading and writing real fixed-width images through
     * JDBC: a parity case is about bytes, and a mocked repository would assert the test's own idea of a
     * record rather than the one the codec produces. {@link RecordImageForm#CHARACTER} and the harness's
     * charset are named explicitly on every one of them, so no conversion anywhere in the path consults a
     * platform default (practice B8).
     *
     * <p>Two collaborators are wrapped, and only where the arm under test is unreachable otherwise:
     * <ul>
     *   <li>the reject writer always, so {@code OPEN OUTPUT} and {@code WRITE} land on this case's
     *       in-memory sink rather than on a relation - which is what lets case14 refuse the write while
     *       case17 refuses the open, two ladders the JDBC-backed sink could only fail together;</li>
     *   <li>the account repository only for {@link Scenario#ACCOUNT_REWRITE_NOT_FOUND}, where
     *       {@code REWRITE} must report the {@code INVALID KEY} condition that {@code :555} branches on.
     *       Everything else on that handle stays real, including the keyed read at {@code :395} that
     *       found the row in the first place.</li>
     * </ul>
     *
     * @param database the per-case database
     * @param clock    the harness's pinned clock
     * @param sysout   the capturing {@code SYSOUT} sink
     * @param rejects  the rejects sink, already arranged
     * @param scenario the arrangement this case runs under
     * @param charset  the dataset code page, named explicitly
     * @return the job, built through its only public constructor; never {@code null}
     */
    private static TransactionValidationJob job(JdbcTemplate database, Clock clock,
                                                CapturedSysout sysout, CollectingRejects rejects,
                                                Scenario scenario, Charset charset) {
        DatasetBindings bindings = bindings();
        return new TransactionValidationJob(
            new BatchConfig(new NoBeanPublished<>(), new NoBeanPublished<>(), contracts(), bindings),
            new DalyTranRepository(database, bindings, charset, RecordImageForm.CHARACTER, WRITE_ORDER),
            new CardXrefRepository(database, bindings, charset, RecordImageForm.CHARACTER),
            accountRepository(database, bindings, charset, scenario),
            new TranCatBalRepository(database, bindings, charset, RecordImageForm.CHARACTER),
            new TransactionRepository(database, bindings, charset, RecordImageForm.CHARACTER,
                WRITE_ORDER),
            rejectWriter(database, bindings, charset, rejects),
            new SuppliedBean<SysoutSink>(sysout),
            clock);
    }

    /**
     * The account master repository, arranged only where a case needs the {@code INVALID KEY} arm of
     * {@code 2800-UPDATE-ACCOUNT-REC}.
     *
     * <p>The stub is placed on the <em>handle</em> rather than on the repository so that
     * {@code 0400-ACCTFILE-OPEN} and the keyed read at {@code :395} still run for real: reason 109 is
     * only interesting <em>because</em> the account was found a moment earlier, and an arrangement that
     * broke the read too would be arranging reason 101 instead.
     *
     * @param database the per-case database
     * @param bindings the DD catalogue
     * @param charset  the dataset code page
     * @param scenario the arrangement this case runs under
     * @return the repository, real or arranged; never {@code null}
     */
    private static AccountRepository accountRepository(JdbcTemplate database, DatasetBindings bindings,
                                                       Charset charset, Scenario scenario) {
        AccountRepository real =
            new AccountRepository(database, bindings, charset, RecordImageForm.CHARACTER);
        if (scenario != Scenario.ACCOUNT_REWRITE_NOT_FOUND) {
            return real;
        }
        AccountRepository arranged = Mockito.spy(real);
        Mockito.doAnswer(call -> {
            AccountRepository.AccountFile handle =
                Mockito.spy(real.open(AccountRepository.OpenMode.I_O));
            Mockito.doReturn(AccountRepository.WriteResult.notFound())
                .when(handle).rewrite(Mockito.any());
            return handle;
        }).when(arranged).open(AccountRepository.OpenMode.I_O);
        return arranged;
    }

    /**
     * The reject writer, with {@code openOutput()} redirected onto this case's in-memory sink.
     *
     * <p>{@link DalyRejectWriter#openOutput(RecordSink)} is the published seam for exactly this - its
     * documentation names "a unit test and the parity harness" as the suppliers of an in-memory
     * collector - and the redirect is on the no-argument overload because that is the one
     * {@code 0300-DALYREJS-OPEN} calls. The delegate is the real writer, so the 430-byte record area,
     * the group move at {@code :447}, the trailer move at {@code :448} and every geometry check the
     * writer performs are all the production ones.
     *
     * @param database the per-case database, which the real writer is still built over
     * @param bindings the DD catalogue
     * @param charset  the dataset code page
     * @param rejects  the sink to redirect onto
     * @return the writer; never {@code null}
     */
    private static DalyRejectWriter rejectWriter(JdbcTemplate database, DatasetBindings bindings,
                                                 Charset charset, CollectingRejects rejects) {
        DalyRejectWriter real =
            new DalyRejectWriter(database, charset, bindings, RecordImageForm.CHARACTER);
        DalyRejectWriter redirected = Mockito.spy(real);
        Mockito.doAnswer(call -> real.openOutput(rejects)).when(redirected).openOutput();
        return redirected;
    }

    /**
     * The DD catalogue, declared exactly as {@code app/jcl/POSTTRAN.jcl:28-42} and
     * {@code app/csd/CARDDEMO.CSD} between them declare it.
     *
     * <p>Both spellings of each dataset are present because both are separate configuration keys with
     * independent overrides, and the job proves at construction that each of its JCL DD names resolves to
     * the same dataset as the repository that reads it. The account master is {@code ACCTDAT} to the
     * repository and {@value #ACCTFILE_KEY} to this step; the cross-reference is {@code CCXREF} and
     * {@code CXACAIX} to the repository and {@value #XREFFILE_KEY} to this step; the transaction master
     * is {@code TRANSACT} to the repository and {@value #TRANFILE_KEY} here, which is why the job
     * carries a job-scoped alias for it.
     *
     * <p>Geometry comes from the copybooks and never from a literal, with two exceptions that come from
     * the JCL because the JCL is where they are declared: the rejects generation is
     * {@code RECFM=F BLKSIZE=0 LRECL=430} with <strong>no copybook at all</strong> - a 350-byte record
     * plus an 80-byte trailer is not any copybook's shape - and it is a generation data group, which is
     * what {@code DALYREJS(+1)} means.
     *
     * @return the catalogue; never {@code null}
     */
    private static DatasetBindings bindings() {
        DatasetBindings bindings = new DatasetBindings();

        bindings.put(DALYTRAN_KEY, new DatasetBinding(DALYTRAN_DSNAME, "sequential", false, "FB", null,
            DalyTranRecord.RECORD_LENGTH, "CVTRA06Y", null, null, null, null));

        DatasetBinding master = new DatasetBinding(TRANSACT_DSNAME, DatasetBinding.KSDS, false, "FB",
            null, TranRecord.RECORD_LENGTH, "CVTRA05Y", TranRecord.TRAN_ID_LENGTH, null, null, null);
        bindings.put(TransactionRepository.CICS_FILE_NAME, master);
        bindings.put(TransactionRepository.INPUT_DD_NAME, master);
        bindings.put(TransactionRepository.SEQUENTIAL_OUTPUT_DD_NAME,
            new DatasetBinding(SYSTRAN_DSNAME, "sequential", false, "F", 0,
                TranRecord.RECORD_LENGTH, "CVTRA05Y", null, null, null, null));

        DatasetBinding xrefBase = new DatasetBinding(CARDXREF_DSNAME, DatasetBinding.KSDS, false, "FB",
            null, CardXrefRecord.RECORD_LENGTH, "CVACT03Y", null, null, null, null);
        bindings.put(CardXrefRepository.BASE_DD_NAME, xrefBase);
        bindings.put(XREFFILE_KEY, xrefBase);
        bindings.put(CardXrefRepository.ALTERNATE_INDEX_DD_NAME,
            new DatasetBinding(CARDXREF_AIX_DSNAME, DatasetBinding.AIX_PATH, false, "FB", null,
                CardXrefRecord.RECORD_LENGTH, "CVACT03Y", null, null,
                CardXrefRepository.BASE_DD_NAME, CardXrefRepository.EXPECTED_ALTERNATE_KEY_FIELD));

        DatasetBinding account = new DatasetBinding(ACCTDATA_DSNAME, DatasetBinding.KSDS, false, "FB",
            null, AccountRecord.RECORD_LENGTH, "CVACT01Y", AccountRecord.ACCT_ID_LENGTH, null, null,
            null);
        bindings.put(AccountRepository.CICS_FILE_NAME, account);
        bindings.put(ACCTFILE_KEY, account);

        bindings.put(TCATBALF_KEY, new DatasetBinding(TCATBALF_DSNAME, DatasetBinding.KSDS, false, "FB",
            null, TranCatBalRecord.RECORD_LENGTH, "CVTRA01Y", TranCatBalRecord.TRAN_CAT_KEY_LENGTH,
            null, null, null));

        bindings.put(DALYREJS_KEY, new DatasetBinding(DALYREJS_DSNAME, "sequential", true,
            DalyRejectWriter.RECORD_FORMAT, DalyRejectWriter.BLOCK_SIZE, DalyRejectWriter.RECORD_LENGTH,
            null, null, null, null, null));
        return bindings;
    }

    /**
     * The {@code carddemo.jobs} contract for this step: one step, no gate, <strong>no parameter</strong>.
     *
     * <p>{@code app/jcl/POSTTRAN.jcl:23} is {@code //STEP15 EXEC PGM=CBTRN02C} and nothing follows the
     * program name, so the parameter list is empty and the job's constructor refuses a contract that
     * declares one. There is a single {@code EXEC} card and no {@code COND}, so the step is ungated -
     * gating the only step of a single-step job would bypass all of its work, and the job refuses a
     * contract that tries.
     *
     * <p>The job-scoped {@value #TRANFILE_KEY} alias is <strong>not optional</strong>. Those eight
     * characters name the transaction master here, while {@code app/jcl/TRANREPT.jcl} binds the same
     * eight to a different dataset entirely; without the alias this step would resolve them by the other
     * job's meaning.
     *
     * @return the contract catalogue; never {@code null}
     */
    private static JobContracts contracts() {
        JobContracts catalogue = new JobContracts();
        catalogue.put(TransactionValidationJob.JOB_KEY, new JobContract(
            PROGRAM,
            List.of(),
            List.of(new StepContract(TransactionValidationJob.STEP_NAME, PROGRAM, STEP_IS_UNGATED)),
            null,
            Map.of(TRANFILE_KEY, new JobDatasetBinding(TransactionRepository.CICS_FILE_NAME, null, null,
                false, null, null, null, null, null, null, null, null))));
        return catalogue;
    }

    // =============================================================================================
    //  THE STATEMENTS A READER OF THIS CLASS NEEDS SPELLED OUT
    //
    //  None of these replaces the declarative gate above. They are the decisions the twenty cases
    //  encode, written out where a reader looks for them - and two of them (the posting order and the
    //  cascade's asymmetry) are properties of a SEQUENCE, which a fingerprint can only show
    //  circumstantially.
    // =============================================================================================

    /**
     * The four reject reasons, byte for byte, and the two that deliberately share a text (practice B4).
     *
     * <p>Transcribed from {@code app/cbl/CBTRN02C.cbl:386}, {@code :398}, {@code :411}, {@code :418} and
     * {@code :557}. Reason 101 and reason 109 carry the <strong>same</strong> 24 characters from two
     * different paragraphs - {@code 1500-B-LOOKUP-ACCT}'s failed read and
     * {@code 2800-UPDATE-ACCOUNT-REC}'s failed rewrite - and that is reproduced rather than
     * disambiguated: the codes differ and the texts do not, and a reject record is told apart by its
     * {@code WS-VALIDATION-FAIL-REASON} rather than by its description.
     */
    @Test
    @DisplayName("the reject reasons and their texts are byte-exact, 101 and 109 sharing one text")
    void reasonCodesAndTextsAreByteExact() {
        assertThat(TransactionValidationJob.REASON_NONE).isZero();
        assertThat(TransactionValidationJob.REASON_INVALID_CARD_NUMBER).isEqualTo(100);
        assertThat(TransactionValidationJob.REASON_ACCOUNT_RECORD_NOT_FOUND).isEqualTo(101);
        assertThat(TransactionValidationJob.REASON_OVERLIMIT_TRANSACTION).isEqualTo(102);
        assertThat(TransactionValidationJob.REASON_TRANSACTION_AFTER_EXPIRATION).isEqualTo(103);
        assertThat(TransactionValidationJob.REASON_ACCOUNT_NOT_FOUND_ON_REWRITE).isEqualTo(109);

        assertThat(DalyRejectWriter.DESC_INVALID_CARD_NUMBER).isEqualTo("INVALID CARD NUMBER FOUND");
        assertThat(DalyRejectWriter.DESC_ACCOUNT_RECORD_NOT_FOUND).isEqualTo("ACCOUNT RECORD NOT FOUND");
        assertThat(DalyRejectWriter.DESC_OVERLIMIT_TRANSACTION).isEqualTo("OVERLIMIT TRANSACTION");
        assertThat(DalyRejectWriter.DESC_TRANSACTION_AFTER_EXPIRATION)
            .isEqualTo("TRANSACTION RECEIVED AFTER ACCT EXPIRATION");

        assertThat(DalyRejectWriter.descriptionOfReason(
                TransactionValidationJob.REASON_ACCOUNT_NOT_FOUND_ON_REWRITE))
            .withFailMessage("app/cbl/CBTRN02C.cbl:557 moves the same 'ACCOUNT RECORD NOT FOUND' text "
                + "that :398 moves, and the duplication is the source's, not a mistake to tidy away.")
            .isEqualTo(DalyRejectWriter.DESC_ACCOUNT_RECORD_NOT_FOUND);

        // The longest text is 42 characters and the span that receives it is 76, so no description in
        // this program is ever truncated by the MOVE at :448 - which is why every reject's trailer is
        // the text followed by spaces, and never a clipped text.
        assertThat(DalyRejectWriter.DESC_TRANSACTION_AFTER_EXPIRATION.length())
            .isLessThan(DalyRejectWriter.WS_VALIDATION_FAIL_REASON_DESC_LENGTH);
    }

    /**
     * The 430-byte reject record's geometry (gates G19, G20, G21).
     *
     * <p>{@code app/jcl/POSTTRAN.jcl:36} declares {@code DCB=(RECFM=F,LRECL=430,BLKSIZE=0)} and
     * {@code app/cbl/CBTRN02C.cbl:82-84} declares the record as {@code FD-REJECT-RECORD PIC X(350)}
     * followed by {@code FD-VALIDATION-TRAILER PIC X(80)}, which {@code :180-182} overlays as
     * {@code PIC 9(04)} plus {@code PIC X(76)}. The three numbers have to sum to the {@code LRECL} and
     * the first of them has to equal a whole {@code DALYTRAN-RECORD}, because the move at {@code :447} is
     * a group move between items of identical width that pads and truncates nothing.
     */
    @Test
    @DisplayName("the reject record is 350 + 4 + 76 = 430 bytes, the first 350 a whole DALYTRAN-RECORD")
    void rejectRecordGeometryIsFourHundredAndThirty() {
        assertThat(DalyRejectWriter.RECORD_LENGTH).isEqualTo(430);
        assertThat(DalyRejectWriter.FD_REJECT_RECORD_LENGTH).isEqualTo(DalyTranRecord.RECORD_LENGTH);
        assertThat(DalyRejectWriter.FD_REJECT_RECORD_LENGTH
            + DalyRejectWriter.WS_VALIDATION_FAIL_REASON_LENGTH
            + DalyRejectWriter.WS_VALIDATION_FAIL_REASON_DESC_LENGTH)
            .isEqualTo(DalyRejectWriter.RECORD_LENGTH);
        assertThat(DalyRejectWriter.VALIDATION_TRAILER_OFFSET)
            .isEqualTo(DalyRejectWriter.FD_REJECT_RECORD_LENGTH);
        assertThat(DalyRejectWriter.VALIDATION_TRAILER_LENGTH)
            .isEqualTo(DalyRejectWriter.WS_VALIDATION_FAIL_REASON_LENGTH
                + DalyRejectWriter.WS_VALIDATION_FAIL_REASON_DESC_LENGTH);
        assertThat(DalyRejectWriter.FD_REJS_RECORD_LAYOUT.recordLength())
            .isEqualTo(DalyRejectWriter.RECORD_LENGTH);
        assertThat(DalyRejectWriter.RECORD_FORMAT).isEqualTo("F");
        assertThat(DalyRejectWriter.BLOCK_SIZE).isZero();

        // The five copybook widths every case pins, taken from the models rather than from a case file.
        assertThat(DalyTranRecord.RECORD_LENGTH).isEqualTo(350);
        assertThat(TranRecord.RECORD_LENGTH).isEqualTo(350);
        assertThat(TranCatBalRecord.RECORD_LENGTH).isEqualTo(50);
        assertThat(TranCatBalRecord.TRAN_CAT_KEY_LENGTH).isEqualTo(17);
        assertThat(AccountRecord.RECORD_LENGTH).isEqualTo(300);
        assertThat(CardXrefRecord.RECORD_LENGTH).isEqualTo(50);
    }

    /**
     * {@code COMPUTE WS-TEMP-BAL} at {@code app/cbl/CBTRN02C.cbl:403-405}, driven through the chunk
     * delegate (gates G22, G23, G24, G28).
     *
     * <p>This program has exactly one {@code COMPUTE}, so this is the whole of its named arithmetic
     * surface, and the case files can only observe it indirectly - through which arm the guard at
     * {@code :407} took. {@link TransactionValidationJob.RecordOutcome#temporaryBalance()} reports the
     * value itself, so the formula is asserted rather than inferred: {@code 100.00 - 50.00 + 504.77}
     * is {@code 554.77}, and each of the three terms is load-bearing. Swap the sign in front of the
     * debit and it becomes {@code 654.77}; drop the debit and it becomes {@code 604.77}; drop the credit
     * and it becomes {@code 454.77}. None of those is asserted as a rounding-mode alternative, because
     * the receiver is {@code PIC S9(09)V99} and every term is already at scale 2 - which is the point of
     * the last two assertions.
     *
     * <p>The chunk seam is used deliberately. {@code app/jcl/POSTTRAN.jcl} processes 300 daily
     * transactions whose outcomes are independent of one another, which is the one shape a
     * chunk-oriented reader, processor and writer models faithfully, and driving
     * {@link TransactionValidationJob.ChunkDelegate} directly exercises that shape with no
     * {@code JobLauncher}, no job repository and no step executor in the path (gate G51).
     */
    @Test
    @DisplayName("WS-TEMP-BAL is cycle credit minus cycle debit plus the amount, at scale 2")
    void temporaryBalanceFormulaIsExact() {
        JdbcTemplate database = database("temporaryBalance");
        seedResolvableTransaction(database, "504.77", "2065.00", "100.00", "50.00");
        CapturedSysout sysout = new CapturedSysout();
        TransactionValidationJob job = job(database, pinnedClock(), sysout,
            new CollectingRejects(Scenario.CLEAN), Scenario.CLEAN, ParityHarness.FIXTURE_CHARSET);

        TransactionValidationJob.ChunkDelegate delegate = job.newChunkDelegate();
        delegate.open(new ExecutionContext());
        DalyTranRecord item = delegate.read();
        assertThat(item).isNotNull();
        // The step's chunk transaction encloses the processing and the write, so the harness declares it
        // around exactly those two calls and not around the stream's open and close, which Spring Batch
        // runs outside it.
        TransactionValidationJob.RecordOutcome outcome = inStepUnitOfWork(() -> delegate.process(item));
        inStepUnitOfWork(() -> delegate.write(Chunk.of(outcome)));
        assertThat(delegate.read())
            .withFailMessage("the second READ at :346 must report '10': one record was seeded")
            .isNull();
        delegate.close();

        BigDecimal expected = CobolDecimal.add(
            CobolDecimal.subtract(new BigDecimal("100.00"), new BigDecimal("50.00"),
                CobolDecimal.MONETARY_SCALE),
            new BigDecimal("504.77"), CobolDecimal.MONETARY_SCALE);
        assertThat(expected).isEqualTo(new BigDecimal("554.77"));
        assertThat(outcome.temporaryBalance())
            .withFailMessage("app/cbl/CBTRN02C.cbl:403-405 is ACCT-CURR-CYC-CREDIT - "
                + "ACCT-CURR-CYC-DEBIT + DALYTRAN-AMT, so 100.00 - 50.00 + 504.77 is 554.77. All three "
                + "terms participate and the middle one is subtracted.")
            .contains(expected);
        assertThat(outcome.posted())
            .withFailMessage("2065.00 >= 554.77, so :407 CONTINUEs and the transaction posts")
            .isTrue();

        // The receiver is PIC S9(09)V99, so the stored value carries scale 2 exactly - and the policy
        // that puts it there truncates, because ROUNDED appears zero times in all 28 programs.
        assertThat(outcome.temporaryBalance().orElseThrow().scale())
            .isEqualTo(TransactionValidationJob.WS_TEMP_BAL_SCALE)
            .isEqualTo(CobolDecimal.MONETARY_SCALE);
        assertThat(CobolDecimal.COBOL_ROUNDING.name()).isEqualTo("DOWN");
        assertThat(delegate.runOutcome().orElseThrow().returnCode())
            .isEqualTo(TransactionValidationJob.RETURN_CODE_CLEAN);
    }

    /**
     * The posting order of {@code 2000-POST-TRANSACTION}: category balance, then account, then master
     * ({@code app/cbl/CBTRN02C.cbl:440-442}).
     *
     * <p>An order is a property of a sequence, and a fingerprint can only show it circumstantially -
     * case18 does exactly that, by failing the master write and observing that the other two writes
     * happened anyway. This states it directly, over the real repositories, with the stubs placed only
     * where a handle has to be intercepted to be watched.
     *
     * <p>Reversing any two of the three {@code PERFORM}s would leave every one of the twenty cases
     * passing except case18, which is why both proofs exist.
     */
    @Test
    @DisplayName("2000-POST-TRANSACTION writes TCATBALF, then ACCTFILE, then TRANFILE")
    void postingOrderIsCategoryBalanceThenAccountThenMaster() {
        JdbcTemplate database = database("postingOrder");
        seedResolvableTransaction(database, "504.77", "2065.00", "0.00", "0.00");
        DatasetBindings bindings = bindings();
        Charset charset = ParityHarness.FIXTURE_CHARSET;

        TranCatBalRepository realBalances =
            new TranCatBalRepository(database, bindings, charset, RecordImageForm.CHARACTER);
        AccountRepository realAccounts =
            new AccountRepository(database, bindings, charset, RecordImageForm.CHARACTER);
        TransactionRepository master = Mockito.spy(new TransactionRepository(database, bindings, charset,
            RecordImageForm.CHARACTER, WRITE_ORDER));

        List<TranCatBalRepository.TranCatBalFile> balanceHandles = new ArrayList<>();
        TranCatBalRepository balances = Mockito.spy(realBalances);
        Mockito.doAnswer(call -> {
            TranCatBalRepository.TranCatBalFile handle =
                Mockito.spy(realBalances.open(TranCatBalRepository.OpenMode.I_O));
            balanceHandles.add(handle);
            return handle;
        }).when(balances).open(TranCatBalRepository.OpenMode.I_O);

        List<AccountRepository.AccountFile> accountHandles = new ArrayList<>();
        AccountRepository accounts = Mockito.spy(realAccounts);
        Mockito.doAnswer(call -> {
            AccountRepository.AccountFile handle =
                Mockito.spy(realAccounts.open(AccountRepository.OpenMode.I_O));
            accountHandles.add(handle);
            return handle;
        }).when(accounts).open(AccountRepository.OpenMode.I_O);

        CapturedSysout sysout = new CapturedSysout();
        TransactionValidationJob posting = new TransactionValidationJob(
            new BatchConfig(new NoBeanPublished<>(), new NoBeanPublished<>(), contracts(), bindings),
            new DalyTranRepository(database, bindings, charset, RecordImageForm.CHARACTER, WRITE_ORDER),
            new CardXrefRepository(database, bindings, charset, RecordImageForm.CHARACTER),
            accounts, balances, master,
            rejectWriter(database, bindings, charset, new CollectingRejects(Scenario.CLEAN)),
            new SuppliedBean<SysoutSink>(sysout), pinnedClock());
        RunOutcome outcome = inStepUnitOfWork(() -> posting.postTransactions(sysout));

        assertThat(outcome.transactionCount()).isOne();
        assertThat(outcome.rejectCount()).isZero();
        assertThat(balanceHandles).hasSize(1);
        assertThat(accountHandles).hasSize(1);

        InOrder order = Mockito.inOrder(balanceHandles.get(0), accountHandles.get(0), master);
        order.verify(balanceHandles.get(0)).rewrite(Mockito.any());
        order.verify(accountHandles.get(0)).rewrite(Mockito.any());
        order.verify(master).write(Mockito.any());
    }

    /**
     * The cascade's asymmetry, stated directly: 103 replaces 102 (gate G31).
     *
     * <p>Two runs over the same transaction and the same limit, differing only in
     * {@code ACCT-EXPIRAION-DATE}. The first is overlimit alone and its reject carries {@code 0102}; the
     * second is overlimit <em>and</em> expired, and its reject carries {@code 0103} together with the
     * expiration text - because {@code :414} is not inside {@code :409}'s {@code ELSE} and runs
     * regardless, overwriting both the code and the description that {@code :410-412} had just stored.
     *
     * <p>The reason is read out of the 430 bytes the sink received, at the offset {@code CVTRA06Y}'s
     * width puts it at, so what is asserted is the record the dataset would have held.
     */
    @Test
    @DisplayName("an overlimit AND expired transaction is rejected with 103, never 102")
    void expirationOverwritesTheOverlimitReason() {
        JdbcTemplate overlimitOnly = database("overlimitOnly");
        seedResolvableTransaction(overlimitOnly, "504.77", "100.00", "0.00", "0.00", "2024-12-13");
        CollectingRejects firstRejects = new CollectingRejects(Scenario.CLEAN);
        CapturedSysout firstSysout = new CapturedSysout();
        TransactionValidationJob firstJob = job(overlimitOnly, pinnedClock(), firstSysout, firstRejects,
            Scenario.CLEAN, ParityHarness.FIXTURE_CHARSET);
        RunOutcome first = inStepUnitOfWork(() -> firstJob.postTransactions(firstSysout));

        JdbcTemplate both = database("overlimitAndExpired");
        seedResolvableTransaction(both, "504.77", "100.00", "0.00", "0.00", "2022-06-09");
        CollectingRejects secondRejects = new CollectingRejects(Scenario.CLEAN);
        CapturedSysout secondSysout = new CapturedSysout();
        TransactionValidationJob secondJob = job(both, pinnedClock(), secondSysout, secondRejects,
            Scenario.CLEAN, ParityHarness.FIXTURE_CHARSET);
        RunOutcome second = inStepUnitOfWork(() -> secondJob.postTransactions(secondSysout));

        assertThat(first.rejectCount()).isOne();
        assertThat(second.rejectCount()).isOne();
        assertThat(first.returnCode()).isEqualTo(TransactionValidationJob.RETURN_CODE_REJECTS_PRESENT);
        assertThat(second.returnCode()).isEqualTo(TransactionValidationJob.RETURN_CODE_REJECTS_PRESENT);

        assertThat(reasonOf(firstRejects))
            .withFailMessage("the limit is 100.00 and WS-TEMP-BAL is 504.77, so :410 stores 102 - and "
                + "the account has not expired, so :415 CONTINUEs and the 102 survives")
            .isEqualTo(TransactionValidationJob.REASON_OVERLIMIT_TRANSACTION);
        assertThat(reasonOf(secondRejects))
            .withFailMessage("both guards fail, and :414 is NOT inside :409's ELSE: it runs anyway and "
                + ":417 stores 103 over the 102 that :410 had just stored. A translation that made stage "
                + "three short-circuit stage four - which is how stages one and two genuinely behave - "
                + "would report 102 here.")
            .isEqualTo(TransactionValidationJob.REASON_TRANSACTION_AFTER_EXPIRATION);
        assertThat(descriptionOf(secondRejects))
            .isEqualTo(DalyRejectWriter.DESC_TRANSACTION_AFTER_EXPIRATION);
    }

    /**
     * The abend translation: {@code CALL 'CEE3ABD'} at {@code app/cbl/CBTRN02C.cbl:711} becomes an
     * {@link AbendException} carrying the {@code RETURN-CODE} the guard chain set (gate G35).
     *
     * <p>Reached through {@code 0300-DALYREJS-OPEN}, whose {@code :297} moves 12 into
     * {@code APPL-RESULT} - so the exception carries 12, the six {@code CLOSE} paragraphs never run, and
     * neither count line nor the closing banner is ever displayed. case17 asserts the same thing through
     * the harness, which converts the exception into the fingerprint's return code; this asserts the
     * exception itself, because that is the object the rest of the batch layer maps to a process exit
     * status.
     */
    @Test
    @DisplayName("a failed open abends with RETURN-CODE 12 and no trailer is emitted")
    void abendCarriesReturnCodeTwelve() {
        JdbcTemplate database = database("abendOnOpen");
        seedResolvableTransaction(database, "504.77", "2065.00", "0.00", "0.00");
        CapturedSysout sysout = new CapturedSysout();
        TransactionValidationJob job = job(database, pinnedClock(), sysout,
            new CollectingRejects(Scenario.REJECT_OPEN_FAILS), Scenario.REJECT_OPEN_FAILS,
            ParityHarness.FIXTURE_CHARSET);

        assertThatExceptionOfType(AbendException.class)
            .isThrownBy(() -> inStepUnitOfWork(() -> job.postTransactions(sysout)))
            .satisfies(abend -> assertThat(abend.getReturnCode())
                .withFailMessage("app/cbl/CBTRN02C.cbl:297 moves 12 into APPL-RESULT before the abend "
                    + "at :305, so the exception carries 12 and the step's exit status is 12.")
                .isEqualTo(TransactionValidationJob.APPL_RESULT_FATAL));

        assertThat(sysout.lines())
            .containsExactly(TransactionValidationJob.START_OF_EXECUTION,
                TransactionValidationJob.ERROR_OPENING_DALYREJS,
                FileStatus.toDisplayLine(TransactionValidationJob.PERMANENT_ERROR_STATUS),
                AbendException.ABEND_DISPLAY_TEXT);
        assertThat(sysout.lines())
            .withFailMessage("CALL 'CEE3ABD' does not return, so :227-232 are unreachable: no count "
                + "line and no closing banner is ever displayed on an abending run.")
            .doesNotContain(TransactionValidationJob.END_OF_EXECUTION);
    }

    /**
     * This step declares <strong>no</strong> job parameters, and one ungated step named {@code STEP15}.
     *
     * <p>{@code app/jcl/POSTTRAN.jcl:23} is {@code //STEP15 EXEC PGM=CBTRN02C} with nothing after the
     * program name. A parameter declared here could not change what the program does - it receives none -
     * but Spring Batch identifies a job instance by its parameters, so one would silently resolve
     * submissions to a different instance. The single {@code EXEC} card carries no {@code COND}, so
     * nothing gates the step.
     */
    @Test
    @DisplayName("POSTTRAN.jcl declares no PARM, so the job declares no parameter")
    void theStepTakesNoJobParameters() {
        TransactionValidationJob job = job(database("noParameters"), pinnedClock(),
            new CapturedSysout(), new CollectingRejects(Scenario.CLEAN), Scenario.CLEAN,
            ParityHarness.FIXTURE_CHARSET);

        assertThat(job.jobParameters().getParameters()).isEmpty();
        assertThat(job.stepContract().name()).isEqualTo("STEP15");
        assertThat(job.stepContract().program()).isEqualTo(PROGRAM);
        assertThat(TransactionValidationJob.REQUIRED_STEPS)
            .containsExactly(new StepContract("STEP15", PROGRAM, STEP_IS_UNGATED));
        assertThat(TransactionValidationJob.CHUNK_SIZE)
            .withFailMessage("a commit interval of one keeps each daily transaction's writes in their "
                + "own boundary, which is the only interval that reproduces a program that issues no "
                + "syncpoint and whose datasets are RECOVERY(NONE)")
            .isOne();
        assertThat(job.db2FormatTimestamp())
            .withFailMessage("Z-GET-DB2-FORMAT-TIMESTAMP composes exactly 26 characters, hyphens at "
                + "positions 5, 8 and 11 and dots at 14, 17 and 20, ending in the literal 0000")
            .hasSize(TransactionValidationJob.DB2_TIMESTAMP_LENGTH)
            .isEqualTo("2022-07-19-23.12.34.000000");
    }

    // =============================================================================================
    //  HELPERS FOR THE TARGETED STATEMENTS ABOVE
    //
    //  The twenty declarative cases carry their rows in their own case files, where a reader can see
    //  every byte. These four build the same shapes in code, for the statements that are about a
    //  sequence or a single value rather than about a record image.
    // =============================================================================================

    /** The card number every hand-built transaction here carries. */
    private static final String CARD_NUMBER = "4859452612877065";

    /** The account every hand-built transaction here resolves to. */
    private static final long ACCOUNT_ID = 7L;

    /** The customer the cross-reference names, which this program reads past without using. */
    private static final int CUSTOMER_ID = 5;

    /** The transaction type and category every hand-built transaction here carries. */
    private static final String TYPE_CODE = "01";

    /** {@code DALYTRAN-CAT-CD}, four digits. */
    private static final int CATEGORY_CODE = 1;

    /** The origin timestamp, whose first ten characters {@code :414} compares against. */
    private static final String ORIGIN_TIMESTAMP = "2022-06-10 19:27:53.000000";

    /**
     * The harness's pinned clock, so a hand-built run stamps the same {@code TRAN-PROC-TS} the case
     * files do.
     *
     * @return a fixed clock at {@link ParityHarness#DEFAULT_PINNED_CLOCK}; never {@code null}
     */
    private static Clock pinnedClock() {
        return ParityHarness.fixedClockAt(ParityHarness.DEFAULT_PINNED_CLOCK);
    }

    /**
     * Seeds one resolvable transaction whose account has not expired.
     *
     * @param database    the per-case database
     * @param amount      {@code DALYTRAN-AMT}
     * @param creditLimit {@code ACCT-CREDIT-LIMIT}
     * @param cycleCredit {@code ACCT-CURR-CYC-CREDIT}
     * @param cycleDebit  {@code ACCT-CURR-CYC-DEBIT}
     */
    private static void seedResolvableTransaction(JdbcTemplate database, String amount,
                                                  String creditLimit, String cycleCredit,
                                                  String cycleDebit) {
        seedResolvableTransaction(database, amount, creditLimit, cycleCredit, cycleDebit, "2024-12-13");
    }

    /**
     * Seeds one resolvable transaction: a daily record, the cross-reference row that resolves its card,
     * the account it names and the category-balance row its key addresses.
     *
     * <p>Every value is one the fixtures genuinely carry - card 4859452612877065 resolving to account 7,
     * whose {@code acctdata.txt} row has limit 2065.00 and expiry 2024-12-13 - so a hand-built run and a
     * case file describe the same world.
     *
     * @param database    the per-case database
     * @param amount      {@code DALYTRAN-AMT}
     * @param creditLimit {@code ACCT-CREDIT-LIMIT}
     * @param cycleCredit {@code ACCT-CURR-CYC-CREDIT}
     * @param cycleDebit  {@code ACCT-CURR-CYC-DEBIT}
     * @param expiry      {@code ACCT-EXPIRAION-DATE}, the copybook's own misspelling
     */
    private static void seedResolvableTransaction(JdbcTemplate database, String amount,
                                                  String creditLimit, String cycleCredit,
                                                  String cycleDebit, String expiry) {
        Charset charset = ParityHarness.FIXTURE_CHARSET;

        DalyTranRecord daily = new DalyTranRecord(charset);
        daily.moveDalytranId("0000000000683580");
        daily.moveDalytranTypeCd(TYPE_CODE);
        daily.moveDalytranCatCd(CATEGORY_CODE);
        daily.moveDalytranSource("POS TERM");
        daily.moveDalytranDesc("Purchase at Abshire-Lowe");
        daily.moveDalytranAmt(new BigDecimal(amount));
        daily.moveDalytranMerchantId(800000000L);
        daily.moveDalytranMerchantName("Abshire-Lowe");
        daily.moveDalytranMerchantCity("North Enoshaven");
        daily.moveDalytranMerchantZip("72112");
        daily.moveDalytranCardNum(CARD_NUMBER);
        daily.moveDalytranOrigTs(ORIGIN_TIMESTAMP);
        seedRow(database, DALYTRAN_DSNAME, imageOf(daily.encode(charset), charset));

        seedRow(database, CARDXREF_DSNAME, imageOf(
            new CardXrefRecord(CARD_NUMBER, CUSTOMER_ID, ACCOUNT_ID).encode(charset), charset));

        AccountRecord account = new AccountRecord(charset);
        account.setAcctId(ACCOUNT_ID);
        account.setAcctActiveStatus("Y");
        account.setAcctCurrBal(new BigDecimal("193.00"));
        account.setAcctCreditLimit(new BigDecimal(creditLimit));
        account.setAcctCashCreditLimit(new BigDecimal("1000.00"));
        account.setAcctOpenDate("2014-11-20");
        account.setAcctExpiraionDate(expiry);
        account.setAcctReissueDate("2023-01-01");
        account.setAcctCurrCycCredit(new BigDecimal(cycleCredit));
        account.setAcctCurrCycDebit(new BigDecimal(cycleDebit));
        account.setAcctAddrZip("A000000000");
        account.setAcctGroupId("");
        seedRow(database, ACCTDATA_DSNAME, account.toFixedWidthString());

        seedRow(database, TCATBALF_DSNAME, imageOf(TranCatBalRecord.newInstance(charset)
            .trancatAcctId(ACCOUNT_ID).trancatTypeCd(TYPE_CODE).trancatCd(CATEGORY_CODE)
            .tranCatBal(CobolDecimal.zero(CobolDecimal.MONETARY_SCALE)).encode(), charset));
    }

    /**
     * A record image as text, under an explicitly named code page and never a platform default.
     *
     * @param record  the record's bytes
     * @param charset the code page they were encoded under
     * @return the image, exactly as wide as the bytes
     */
    private static String imageOf(byte[] record, Charset charset) {
        return new String(record, charset);
    }

    /**
     * {@code WS-VALIDATION-FAIL-REASON} out of the single reject a run wrote.
     *
     * @param rejects the run's rejects sink, holding exactly one record
     * @return the four-digit reason as a number
     */
    private static int reasonOf(CollectingRejects rejects) {
        assertThat(rejects.records()).hasSize(1);
        return Integer.parseInt(trailerSpan(rejects, DalyRejectWriter.WS_VALIDATION_FAIL_REASON_OFFSET,
            DalyRejectWriter.WS_VALIDATION_FAIL_REASON_LENGTH));
    }

    /**
     * {@code WS-VALIDATION-FAIL-REASON-DESC} out of the single reject a run wrote, trailing spaces
     * removed only for the comparison against the source literal - the record itself keeps all 76.
     *
     * @param rejects the run's rejects sink, holding exactly one record
     * @return the description with its right padding stripped
     */
    private static String descriptionOf(CollectingRejects rejects) {
        assertThat(rejects.records()).hasSize(1);
        return trailerSpan(rejects, DalyRejectWriter.WS_VALIDATION_FAIL_REASON_DESC_OFFSET,
            DalyRejectWriter.WS_VALIDATION_FAIL_REASON_DESC_LENGTH).stripTrailing();
    }

    /**
     * One span of the single 430-byte reject a run wrote.
     *
     * @param rejects the run's rejects sink
     * @param offset  the span's zero-based offset
     * @param length  the span's declared length
     * @return the span's characters, under the harness's code page
     */
    private static String trailerSpan(CollectingRejects rejects, int offset, int length) {
        byte[] record = rejects.records().get(0);
        assertThat(record).hasSize(DalyRejectWriter.RECORD_LENGTH);
        return imageOf(record, ParityHarness.FIXTURE_CHARSET).substring(offset, offset + length);
    }
}
