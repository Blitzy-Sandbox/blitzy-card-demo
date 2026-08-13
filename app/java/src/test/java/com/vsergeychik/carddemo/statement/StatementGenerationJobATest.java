package com.vsergeychik.carddemo.statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.vsergeychik.carddemo.CardDemoApplication;
import com.vsergeychik.carddemo.account.model.AccountRecord;
import com.vsergeychik.carddemo.card.model.CardXrefRecord;
import com.vsergeychik.carddemo.common.AbendException;
import com.vsergeychik.carddemo.common.CobolDecimal;
import com.vsergeychik.carddemo.common.FileStatus;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.PhysicalSequence;
import com.vsergeychik.carddemo.common.RecordImageForm;
import com.vsergeychik.carddemo.config.BatchConfig;
import com.vsergeychik.carddemo.config.BatchConfig.JobContract;
import com.vsergeychik.carddemo.config.BatchConfig.JobContracts;
import com.vsergeychik.carddemo.config.BatchConfig.JobDatasetBinding;
import com.vsergeychik.carddemo.config.BatchConfig.JobParameterContract;
import com.vsergeychik.carddemo.config.BatchConfig.StepContract;
import com.vsergeychik.carddemo.config.BatchConfig.StopRequestedException;
import com.vsergeychik.carddemo.config.BatchConfig.StopSignal;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBinding;
import com.vsergeychik.carddemo.config.DatasetUnitOfWork;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBindings;
import com.vsergeychik.carddemo.statement.StatementGenerationJobA.DatasetUtilityPort;
import com.vsergeychik.carddemo.statement.StatementGenerationJobA.JdbcDatasetUtilityPort;
import com.vsergeychik.carddemo.statement.StatementGenerationJobA.SysoutSink;
import com.vsergeychik.carddemo.statement.StatementGenerationJobA.TiotEntry;
import com.vsergeychik.carddemo.statement.StatementGenerationJobA.TiotImage;
import com.vsergeychik.carddemo.statement.StatementGenerationJobA.TiotSource;
import com.vsergeychik.carddemo.statement.StatementGenerationJobA.TrnxTable;
import com.vsergeychik.carddemo.statement.StatementGenerationJobA.WorkingStorage;
import com.vsergeychik.carddemo.statement.StatementGenerationJobB.Response;
import com.vsergeychik.carddemo.statement.StatementGenerationJobB.Session;
import com.vsergeychik.carddemo.statement.StatementHtmlWriter.AddressField;
import com.vsergeychik.carddemo.statement.StatementHtmlWriter.BasicDetail;
import com.vsergeychik.carddemo.statement.StatementHtmlWriter.HtmlFixedLine;
import com.vsergeychik.carddemo.statement.StatementHtmlWriter.HtmlStatementFile;
import com.vsergeychik.carddemo.statement.StatementHtmlWriter.TransactionField;
import com.vsergeychik.carddemo.statement.StatementTextWriter.StatementFile;
import com.vsergeychik.carddemo.statement.StatementTextWriter.StatementLine;
import com.vsergeychik.carddemo.statement.StatementTextWriter.StatementSlot;
import com.vsergeychik.carddemo.statement.model.Stm03CustomerRecord;
import com.vsergeychik.carddemo.statement.model.TrnxRecord;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobInterruptedException;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.job.AbstractJob;
import org.springframework.batch.core.job.flow.FlowExecutionStatus;
import org.springframework.batch.core.job.flow.JobExecutionDecider;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.scope.context.StepContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.batch.BatchAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.dao.DataAccessException;
import com.vsergeychik.carddemo.testdataset.RecordImageDataSource;
import com.vsergeychik.carddemo.testdataset.RecordImageStore;
import com.vsergeychik.carddemo.testdataset.RecordImageStore.ColumnForm;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;

import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Unit tests for {@link StatementGenerationJobA}, the translation of {@code app/cbl/CBSTM03A.CBL}.
 *
 * <h2>Why the tests are shaped this way</h2>
 *
 * <p>{@code CBSTM03A} is singled out twice by the migration plan: it is the <em>only</em> one of the
 * twenty-eight programs whose {@code GO TO}s form implicit loops, and it is assigned the densest parity
 * coverage in the estate. Both facts drive the same conclusion - the interesting assertions here are about
 * <strong>order</strong>, not about individual values. In what order does the {@code ALTER}-driven dispatch
 * visit its states? In what order are the four files opened, read and closed? In what order are the
 * eighty-byte text records and the hundred-byte HTML records emitted? Every one of those orders is
 * behaviour, and every one of them is asserted as a whole sequence rather than sampled.
 *
 * <p>Three seams make that possible without a {@code JobLauncher}, an application context or a database:
 * <ul>
 *   <li>{@link ScriptedSubroutine} extends the real {@link StatementGenerationJobB} and overrides its four
 *       operations, so a test both <em>scripts</em> the {@code FILE STATUS} each call reports and
 *       <em>records</em> the exact call sequence. The real {@code newSession()} is still used, because it
 *       touches no database.</li>
 *   <li>The two output writers are spied and their no-argument open methods stubbed to return handles bound
 *       to in-memory sinks, so every emitted record is collected verbatim - no trimming, no
 *       normalisation - while the writers' real rendering logic still runs.</li>
 *   <li>{@link TiotSource}, {@link SysoutSink} and {@link DatasetUtilityPort} are injected, which is what
 *       makes the control-block prologue, the displayed line sequence and the four utility steps
 *       deterministic.</li>
 * </ul>
 *
 * <p>Every scripted {@link Response} carries a record area padded to exactly
 * {@link StatementGenerationJobB#FLDT_LENGTH} characters, because {@code LK-M03B-FLDT} is a fixed
 * {@code PIC X(1000)} span and the record's own compact constructor refuses anything else.
 *
 * <h2>Where every expected value in this class comes from</h2>
 *
 * <p><strong>Every expectation below is derived statically, by reading the legacy source.</strong> Each
 * one traces to a cited line of {@code app/cbl/CBSTM03A.CBL}, {@code app/cbl/CBSTM03B.CBL},
 * {@code app/jcl/CREASTMT.JCL} or one of the four copybooks {@code app/cpy/COSTM01.CPY},
 * {@code app/cpy/CUSTREC.cpy}, {@code app/cpy/CVACT01Y.cpy} and {@code app/cpy/CVACT03Y.cpy}, and the
 * citation is written next to the assertion so a reviewer can check it against the source rather than
 * against this file's own opinion. Record widths and field offsets come from the copybooks' own
 * {@code PICTURE} clauses, added up by hand; record lengths and step gating come from the JCL's
 * {@code DCB} and {@code COND} parameters; the displayed literals and the write orders are transcribed
 * character for character from the {@code DISPLAY} and {@code WRITE} statements.
 *
 * <p><strong>No expectation here was captured from a run of the legacy program, and none should be read
 * as if it had been.</strong> The twenty-eight COBOL programs cannot be executed in this environment at
 * all, so no recorded baseline exists to compare against - among the verified obstacles are the absence
 * of any z/OS runtime, an indexed-file handler that is disabled in the only available compiler, no
 * Language Environment {@code CEE} services for the {@code CEE3ABD} at {@code CBSTM03A.CBL:L923}, no CICS
 * emulator, and - for this program specifically - the literal TAB characters in the source margin of
 * {@code app/cpy/CUSTREC.cpy} lines 6-22, which stop {@code CBSTM03A} from even parsing. A statically
 * derived expectation can encode a misreading of the COBOL where a captured one could not, so the
 * mitigation is the citation discipline above plus a deliberate concentration of assertions on the
 * places accumulated behaviour hides: the {@code EVALUATE} arms, the {@code 88}-level conditions, the
 * {@code FILE STATUS} acceptance asymmetry and the write orders.
 *
 * <h2>Two notes about numeric representation</h2>
 *
 * <p><strong>{@code COMP-3} never reaches a record here.</strong> {@code CBSTM03A.CBL:L64} does declare
 * {@code 01 COMP3-VARIABLES COMP-3}, but it is a {@code WORKING-STORAGE} group holding one accumulator,
 * {@code WS-TOTAL-AMT}. Not one of the twenty-eight copybooks declares {@code COMP-3} or
 * {@code PACKED-DECIMAL} anywhere, so no record this program reads or writes carries packed decimal and
 * <em>no nibble unpacking is ever needed at the record layer</em> - the record amounts are zoned
 * {@code DISPLAY}, which is what {@link FixedWidthCodec} handles. {@code COMP-3} therefore becomes an
 * in-memory {@link BigDecimal} and nothing more.
 *
 * <p><strong>Scale two, truncating.</strong> {@code TRNX-AMT} is {@code PIC S9(09)V99} and
 * {@code WS-TOTAL-AMT} is {@code PIC S9(9)V99}, so every amount asserted here is a {@link BigDecimal} at
 * scale exactly 2. The keyword {@code ROUNDED} appears zero times in all twenty-eight programs, so a
 * store that loses fractional digits truncates, and the only rounding mode this class ever names is
 * {@link java.math.RoundingMode#DOWN}.
 */
class StatementGenerationJobATest {

    // =============================================================================================
    // Constants.
    //
    // The dataset names below are DELIBERATELY NOT the estate's own names. Not one production name
    // appears anywhere in this file, and none appears anywhere in the main source tree either: every
    // dataset a run touches is resolved from a carddemo.datasets binding key, so the class under test
    // never holds a dataset name of its own (gate G46). Naming the real datasets here would weaken the
    // tests as well as break that rule - a test that feeds the class an arbitrary, deployment-neutral
    // name and then asserts the class honours it proves the resolution really is configuration-bound,
    // whereas a test that feeds the production name cannot tell resolution from coincidence.
    //
    // The aliases are still valid z/OS dataset names, because JdbcDatasetUtilityPort validates whatever
    // configuration hands it against that grammar - up to eight qualifiers, each starting with a letter
    // and at most eight characters. Each alias is built from the DD name it stands behind, so a failure
    // message names the DD a reviewer can look up in app/jcl/CREASTMT.JCL.
    // =============================================================================================

    /**
     * The high-level qualifier every alias in this file sits under. Deliberately not the estate's own,
     * for the reason given above.
     */
    private static final String DSNAME_PREFIX = "CARDDEMO.PARITY.";

    /**
     * Builds this file's alias for one DD, as {@value #DSNAME_PREFIX} followed by the DD name.
     *
     * <p>Every qualifier of the result is at most eight characters and starts with a letter, which is what
     * the z/OS dataset-name grammar requires, because a DD name is itself {@code PIC X(08)} and starts
     * with a letter in every step of {@code app/jcl/CREASTMT.JCL}.
     *
     * @param ddName the DD name to build an alias for; must not be {@code null}
     * @return a deployment-neutral dataset name for that DD
     */
    private static String dsnameFor(String ddName) {
        return DSNAME_PREFIX + ddName;
    }

    /** The fixtures' code page, named explicitly and never defaulted. */
    private static final Charset ASCII = StandardCharsets.US_ASCII;

    /**
     * The physical-record ordinal every physical-sequential read is ordered by, as
     * {@code application-test.yml} configures it: the shipped test profile names H2's row-identifier
     * pseudo-column, which increases with each insert, and the record-image store behind this suite holds
     * rows in write order, so both hand the records back in the order they were written - which is what a
     * {@code SORT} of an unsorted file and a {@code REPRO} of a sorted one both require.
     */
    private static final PhysicalSequence ORDINAL = PhysicalSequence.of("_ROWID_");

    /** The transaction master this file binds behind {@code SORTIN}, {@code app/jcl/CREASTMT.JCL:L45}. */
    private static final String TRANSACT_DSNAME = dsnameFor("TRANSACT");

    /**
     * The keyed work cluster {@code DELDEF01} defines and {@code STEP020} loads, bound behind
     * {@code TRNXFILE} and {@code OUTFILE}, {@code app/jcl/CREASTMT.JCL:L27-L40}, {@code L59}, {@code L83}.
     */
    private static final String TRNXFILE_DSNAME = dsnameFor(StatementGenerationJobA.TRNXFILE_DD);

    /**
     * The intermediate sequential file {@code STEP010} writes and {@code STEP020} reads, bound behind
     * {@code SORTOUT} and {@code INFILE}, {@code app/jcl/CREASTMT.JCL:L48-L51}, {@code L58}.
     */
    private static final String TRXFL_SEQ_DSNAME = dsnameFor(StatementGenerationJobA.SORTOUT_DD);

    /** The cross-reference master behind {@code XREFFILE}, {@code app/jcl/CREASTMT.JCL:L84}. */
    private static final String XREFFILE_DSNAME = dsnameFor(StatementGenerationJobA.XREFFILE_DD);

    /** The account master behind {@code ACCTFILE}, {@code app/jcl/CREASTMT.JCL:L85}. */
    private static final String ACCTFILE_DSNAME = dsnameFor(StatementGenerationJobA.ACCTFILE_DD);

    /** The customer master behind {@code CUSTFILE}, {@code app/jcl/CREASTMT.JCL:L86}. */
    private static final String CUSTFILE_DSNAME = dsnameFor(StatementGenerationJobA.CUSTFILE_DD);

    /**
     * The eighty-byte plain-text statement behind {@code STMTFILE}, {@code app/jcl/CREASTMT.JCL:L87-L91}.
     */
    private static final String STMTFILE_DSNAME = dsnameFor(StatementGenerationJobA.STMTFILE_DD);

    /**
     * The hundred-byte HTML statement behind {@code HTMLFILE}, {@code app/jcl/CREASTMT.JCL:L92-L96}.
     */
    private static final String HTMLFILE_DSNAME = dsnameFor(StatementGenerationJobA.HTMLFILE_DD);

    /** A blank {@code LK-M03B-FLDT}: the area the subroutine returns when it read nothing. */
    private static final String BLANK_FLDT = " ".repeat(StatementGenerationJobB.FLDT_LENGTH);

    /** The two card numbers the grouping tests use, in ascending order. */
    private static final String CARD_A = "4111111111111111";

    /** The higher of the two card numbers, so {@code CARD_A} sorts first. */
    private static final String CARD_B = "4222222222222222";

    /** A card number above both, used to prove the scan's early exit. */
    private static final String CARD_C = "4333333333333333";

    /** The customer id every cross-reference fixture names. */
    private static final String CUST_ID = "000000042";

    /** The account id every cross-reference fixture names. */
    private static final String ACCT_ID = "00000000099";

    // =============================================================================================
    // Bindings, contracts and scaffolding.
    // =============================================================================================

    /**
     * One indexed binding at its copybook geometry.
     *
     * @param dsname       the dataset name
     * @param recordLength the copybook record width
     * @param keyLength    the key width
     * @return the binding
     */
    private static DatasetBinding ksds(String dsname, int recordLength, int keyLength) {
        return new DatasetBinding(dsname, DatasetBinding.KSDS, false, "FB", null, recordLength, null,
                keyLength, 0, null, null);
    }

    /**
     * One sequential binding.
     *
     * @param dsname       the dataset name
     * @param recordLength the record width
     * @param blockSize    the block size
     * @return the binding
     */
    private static DatasetBinding sequential(String dsname, int recordLength, int blockSize) {
        return new DatasetBinding(dsname, "sequential", false, "FB", blockSize, recordLength, null, null,
                null, null, null);
    }

    /**
     * The global {@code carddemo.datasets} catalogue, at the widths {@code application.yml} declares.
     *
     * @return the catalogue
     */
    private static DatasetBindings globalBindings() {
        DatasetBindings catalogue = new DatasetBindings();
        catalogue.put("TRANSACT", ksds(TRANSACT_DSNAME, TrnxRecord.RECORD_LENGTH, 16));
        catalogue.put(StatementGenerationJobA.TRNXFILE_DD,
                ksds(TRNXFILE_DSNAME, TrnxRecord.RECORD_LENGTH, TrnxRecord.TRNX_KEY_LENGTH));
        catalogue.put(StatementGenerationJobA.XREFFILE_DD, ksds(XREFFILE_DSNAME,
                CardXrefRecord.RECORD_LENGTH, CardXrefRecord.XREF_CARD_NUM_LENGTH));
        catalogue.put(StatementGenerationJobA.ACCTFILE_DD,
                ksds(ACCTFILE_DSNAME, AccountRecord.RECORD_LENGTH, AccountRecord.ACCT_ID_LENGTH));
        catalogue.put(StatementGenerationJobA.CUSTFILE_DD, ksds(CUSTFILE_DSNAME,
                Stm03CustomerRecord.RECORD_LENGTH, Stm03CustomerRecord.CUST_ID_LENGTH));
        catalogue.put(StatementGenerationJobA.STMTFILE_DD, sequential(STMTFILE_DSNAME,
                StatementTextWriter.RECORD_LENGTH, StatementTextWriter.BLOCK_SIZE));
        catalogue.put(StatementGenerationJobA.HTMLFILE_DD, sequential(HTMLFILE_DSNAME,
                StatementHtmlWriter.RECORD_LENGTH, StatementHtmlWriter.BLOCK_SIZE));
        return catalogue;
    }

    /**
     * The job-scoped dataset overrides {@code application.yml} declares: two aliases and two inline
     * sequential datasets.
     *
     * @return the four job-scoped bindings
     */
    private static Map<String, JobDatasetBinding> jobDatasets() {
        Map<String, JobDatasetBinding> datasets = new LinkedHashMap<>();
        datasets.put(StatementGenerationJobA.SORTIN_DD, new JobDatasetBinding("TRANSACT", null, null,
                false, null, null, null, null, null, null, null, null));
        datasets.put(StatementGenerationJobA.SORTOUT_DD, workSequential());
        datasets.put(StatementGenerationJobA.INFILE_DD, workSequential());
        datasets.put(StatementGenerationJobA.OUTFILE_DD, new JobDatasetBinding(
                StatementGenerationJobA.TRNXFILE_DD, null, null, false, null, null, null, null, null,
                null, null, null));
        return datasets;
    }

    /**
     * The intermediate sequential file, declared inline exactly as configuration declares it.
     *
     * @return the job-scoped binding
     */
    private static JobDatasetBinding workSequential() {
        return new JobDatasetBinding(null, TRXFL_SEQ_DSNAME, "sequential", false, "FB",
                StatementGenerationJobA.WORK_SEQUENTIAL_BLOCK_SIZE,
                StatementGenerationJobA.WORK_KSDS_RECORD_LENGTH, "COSTM01", null, null, null, null);
    }

    /**
     * The five step contracts of {@code app/jcl/CREASTMT.JCL}, gated exactly as the JCL gates them.
     *
     * @return the five contracts, in declaration order
     */
    private static List<StepContract> jclSteps() {
        List<StepContract> transcribed = List.of(
                new StepContract(StatementGenerationJobA.STEP_DELDEF01, "IDCAMS", false),
                new StepContract(StatementGenerationJobA.STEP_010, "SORT", false),
                new StepContract(StatementGenerationJobA.STEP_020, "IDCAMS", true),
                new StepContract(StatementGenerationJobA.STEP_030, "IEFBR14", true),
                new StepContract(StatementGenerationJobA.STEP_040, StatementGenerationJobA.PROGRAM_ID,
                        true));
        // Written out from app/jcl/CREASTMT.JCL here, and required to equal what the production class
        // requires. Two independent transcriptions of the same five step cards that must agree: if either
        // drifts, this fails at the point of the drift rather than somewhere downstream of it.
        assertThat(transcribed)
                .as("this test's reading of CREASTMT.JCL and StatementGenerationJobA's reading of it "
                        + "must be the same five steps, programs, gates and order")
                .isEqualTo(StatementGenerationJobA.REQUIRED_STEPS);
        return transcribed;
    }

    /**
     * The {@code carddemo.jobs} catalogue holding this job's contract.
     *
     * @return the catalogue
     */
    private static JobContracts jobContracts() {
        return jobContracts(jclSteps(), List.of());
    }

    /**
     * The {@code carddemo.jobs} catalogue holding a deliberately chosen contract.
     *
     * @param steps      the step contracts to declare
     * @param parameters the job parameters to declare
     * @return the catalogue
     */
    private static JobContracts jobContracts(List<StepContract> steps,
            List<JobParameterContract> parameters) {
        JobContracts catalogue = new JobContracts();
        catalogue.put(StatementGenerationJobA.JOB_KEY, new JobContract(
                StatementGenerationJobA.PROGRAM_ID, parameters, steps, null, jobDatasets()));
        return catalogue;
    }

    /**
     * The batch scaffolding over a mocked repository and transaction manager, so a job and its steps can
     * be built with no application context.
     *
     * @param contracts the {@code carddemo.jobs} catalogue to bind
     * @return the scaffolding
     */
    private static BatchConfig scaffolding(JobContracts contracts) {
        return new BatchConfig(new PresentBean<>(Mockito.mock(JobRepository.class)),
                new PresentBean<>(Mockito.mock(PlatformTransactionManager.class)), contracts,
                globalBindings());
    }

    // =============================================================================================
    // Bean providers.
    // =============================================================================================

    /**
     * An {@link ObjectProvider} that always yields the given bean.
     *
     * @param bean the bean to yield
     * @param <T>  the bean type
     */
    private record PresentBean<T>(T bean) implements ObjectProvider<T> {

        @Override
        public T getObject() {
            return bean;
        }
    }

    /**
     * An {@link ObjectProvider} reporting the bean as absent, so the job falls back to its own default.
     *
     * <p>Only {@code getObject()} is overridden: the interface's own {@code getIfAvailable(Supplier)}
     * catches the absence and calls the supplier, which is the resolution being exercised.
     *
     * @param <T> the bean type
     */
    private static final class AbsentBean<T> implements ObjectProvider<T> {

        @Override
        public T getObject() {
            throw new NoSuchBeanDefinitionException("no bean of this type is declared in this test");
        }
    }

    // =============================================================================================
    // Collecting seams. Nothing is trimmed or normalised anywhere below: trailing spaces are
    // significant in an 80-byte and a 100-byte record image, and in a PIC X(08) DD name.
    // =============================================================================================

    /** Collects the displayed line sequence verbatim. */
    private static final class CapturedSysout implements SysoutSink {

        /** The lines written so far, in emission order. */
        private final List<String> lines = new ArrayList<>();

        @Override
        public void write(String line) {
            lines.add(line);
        }

        /**
         * The captured sequence.
         *
         * @return the displayed lines, in order
         */
        List<String> lines() {
            return lines;
        }
    }

    /**
     * The real {@link StatementGenerationJobB} with its four operations scripted and recorded.
     *
     * <p>Extending rather than mocking is deliberate. {@code newSession()} is inherited unchanged - it
     * touches no database, it only builds one cursor per DD name - so the {@link Session} the job holds is
     * a genuine one and the closing {@code session.close()} on the {@code GOBACK} path is genuinely
     * exercised. Only the four operations are replaced, which is exactly the surface a parity case needs to
     * control.
     */
    private static final class ScriptedSubroutine extends StatementGenerationJobB {

        /** {@code SET M03B-OPEN TO TRUE}. */
        private static final String OPEN = "OPEN";

        /** {@code SET M03B-READ TO TRUE}. */
        private static final String READ = "READ";

        /** {@code SET M03B-READ-K TO TRUE}. */
        private static final String READ_KEYED = "READK";

        /** {@code SET M03B-CLOSE TO TRUE}. */
        private static final String CLOSE = "CLOSE";

        /** Every call this run made, as {@code "<operation> <dd>"}, in order. */
        private final List<String> calls = new ArrayList<>();

        /** Every keyed call's key and key length, as {@code "<dd> <key> <length>"}, in order. */
        private final List<String> keys = new ArrayList<>();

        /** Scripted responses, keyed by {@code "<operation> <dd>"} and consumed in order. */
        private final Map<String, Deque<Response>> script = new LinkedHashMap<>();

        ScriptedSubroutine() {
            super(new JdbcTemplate(), globalBindings(), ASCII, RecordImageForm.CHARACTER);
        }

        /**
         * Scripts the responses one operation on one DD name reports, consumed in the order given.
         *
         * @param operation one of {@link #OPEN}, {@link #READ}, {@link #READ_KEYED}, {@link #CLOSE}
         * @param dd        the DD name
         * @param responses the responses, in order
         * @return this, for chaining
         */
        ScriptedSubroutine on(String operation, String dd, Response... responses) {
            script.computeIfAbsent(operation + ' ' + dd, key -> new ArrayDeque<>())
                    .addAll(List.of(responses));
            return this;
        }

        /**
         * Discards whatever was scripted for one operation on one DD name and scripts these instead.
         *
         * @param operation one of {@link #OPEN}, {@link #READ}, {@link #READ_KEYED}, {@link #CLOSE}
         * @param dd        the DD name
         * @param responses the responses, in order
         * @return this, for chaining
         */
        ScriptedSubroutine replace(String operation, String dd, Response... responses) {
            Deque<Response> queued =
                    script.computeIfAbsent(operation + ' ' + dd, key -> new ArrayDeque<>());
            queued.clear();
            queued.addAll(List.of(responses));
            return this;
        }

        /**
         * Scripts one {@code OPEN} status.
         *
         * @param dd     the DD name
         * @param status the two-character {@code FILE STATUS}
         * @return this, for chaining
         */
        ScriptedSubroutine opens(String dd, String status) {
            return on(OPEN, dd, new Response(status, BLANK_FLDT));
        }

        /**
         * Scripts one {@code CLOSE} status.
         *
         * @param dd     the DD name
         * @param status the two-character {@code FILE STATUS}
         * @return this, for chaining
         */
        ScriptedSubroutine closes(String dd, String status) {
            return on(CLOSE, dd, new Response(status, BLANK_FLDT));
        }

        /**
         * Scripts the sequential reads one DD name reports.
         *
         * @param dd        the DD name
         * @param responses the responses, in order
         * @return this, for chaining
         */
        ScriptedSubroutine reads(String dd, Response... responses) {
            return on(READ, dd, responses);
        }

        /**
         * Scripts the keyed reads one DD name reports.
         *
         * @param dd        the DD name
         * @param responses the responses, in order
         * @return this, for chaining
         */
        ScriptedSubroutine keyed(String dd, Response... responses) {
            return on(READ_KEYED, dd, responses);
        }

        @Override
        public Response open(Session session, String dd) {
            calls.add(OPEN + ' ' + dd);
            return take(OPEN, dd, new Response(FileStatus.OK, BLANK_FLDT));
        }

        @Override
        public Response readNext(Session session, String dd) {
            calls.add(READ + ' ' + dd);
            return take(READ, dd, new Response(FileStatus.END_OF_FILE, BLANK_FLDT));
        }

        @Override
        public Response readByKey(Session session, String dd, String key, int keyLength) {
            calls.add(READ_KEYED + ' ' + dd);
            keys.add(dd + ' ' + key + ' ' + keyLength);
            return take(READ_KEYED, dd, new Response(FileStatus.OK, BLANK_FLDT));
        }

        @Override
        public Response close(Session session, String dd) {
            calls.add(CLOSE + ' ' + dd);
            return take(CLOSE, dd, new Response(FileStatus.OK, BLANK_FLDT));
        }

        /**
         * The next scripted response, or the fallback once the script is exhausted.
         *
         * @param operation the operation
         * @param dd        the DD name
         * @param fallback  what an unscripted call reports
         * @return the response
         */
        private Response take(String operation, String dd, Response fallback) {
            Deque<Response> queued = script.get(operation + ' ' + dd);
            return queued == null || queued.isEmpty() ? fallback : queued.removeFirst();
        }

        /**
         * The recorded call sequence.
         *
         * @return {@code "<operation> <dd>"} entries, in order
         */
        List<String> calls() {
            return calls;
        }

        /**
         * The recorded keyed-read keys.
         *
         * @return {@code "<dd> <key> <length>"} entries, in order
         */
        List<String> keys() {
            return keys;
        }
    }

    /**
     * An in-memory {@link DatasetUtilityPort}: the four utility steps' data path, with no database.
     *
     * <p>Records every operation in order and keeps each dataset's contents, so the chaining the JCL
     * relies on is visible - {@value StatementGenerationJobA#SORTOUT_DD} and
     * {@value StatementGenerationJobA#INFILE_DD} resolve to the same dataset, which is why what
     * {@value StatementGenerationJobA#STEP_010} writes is what {@value StatementGenerationJobA#STEP_020}
     * reads.
     */
    private static final class RecordingUtilityPort implements DatasetUtilityPort {

        /** Every operation, as {@code "<VERB> <dsname>"}, in order. */
        private final List<String> operations = new ArrayList<>();

        /** Each dataset's current contents, keyed by dataset name. */
        private final Map<String, List<String>> contents = new LinkedHashMap<>();

        /** Side effects to run the first time a given {@code "<VERB> <dsname>"} operation is seen. */
        private final Map<String, Runnable> sideEffects = new LinkedHashMap<>();

        /**
         * A dataset whose write is refused, or {@code null} to accept every write.
         *
         * <p>Needed to reach the abnormal-disposition path of {@value StatementGenerationJobA#SORTOUT_DD}:
         * that path exists precisely for a write that does not finish, and the collecting port cannot
         * reproduce one because it always succeeds.
         */
        private String refuseWriteTo;

        /**
         * Refuses the next write to the named dataset, part way through.
         *
         * @param dsname the dataset whose write must fail
         * @return this, for chaining
         */
        RecordingUtilityPort refusingWriteTo(String dsname) {
            refuseWriteTo = dsname;
            return this;
        }

        /**
         * Seeds one dataset.
         *
         * @param dsname       the dataset name
         * @param recordImages its records, in order
         * @return this, for chaining
         */
        RecordingUtilityPort seed(String dsname, List<String> recordImages) {
            contents.put(dsname, new ArrayList<>(recordImages));
            return this;
        }

        /**
         * Runs an action the first time a given operation is performed, then forgets it.
         *
         * <p>The hook exists for one purpose: the {@code COND=(0,NE)} gates read the job execution's
         * step executions as they stand at the moment each gate runs, so a test that wants the
         * <em>second</em> or <em>third</em> gate to be the one that bypasses has to make the non-zero
         * condition appear part-way through the flow. The utility port is the only collaborator the
         * preparation steps call, so it is the only place a test can act from inside the running job.
         *
         * @param operation the {@code "<VERB> <dsname>"} entry to fire on
         * @param action    what to do when it is next performed
         * @return this, for chaining
         */
        RecordingUtilityPort onceOn(String operation, Runnable action) {
            sideEffects.put(operation, action);
            return this;
        }

        @Override
        public int deleteAllRecords(DatasetBinding binding) {
            record("DELETE " + binding.dsname());
            List<String> removed = contents.remove(binding.dsname());
            return removed == null ? 0 : removed.size();
        }

        @Override
        public List<String> readAllRecordImages(DatasetBinding binding) {
            record("READ " + binding.dsname());
            return List.copyOf(contents.getOrDefault(binding.dsname(), List.of()));
        }

        @Override
        public int writeRecordImages(DatasetBinding binding, List<String> recordImages) {
            record("WRITE " + binding.dsname());
            if (binding.dsname().equals(refuseWriteTo)) {
                // Part way, not at the start: a dataset holding some of a sorted file is exactly the
                // state DISP=(NEW,CATLG,DELETE) must not leave catalogued.
                contents.put(binding.dsname(), List.copyOf(recordImages.subList(0,
                        recordImages.size() / 2)));
                throw new IllegalStateException("the destination went away part way through the write");
            }
            contents.put(binding.dsname(), List.copyOf(recordImages));
            return recordImages.size();
        }

        /**
         * Records an operation and fires its one-shot side effect, if a test registered one.
         *
         * @param operation the {@code "<VERB> <dsname>"} entry
         */
        private void record(String operation) {
            operations.add(operation);
            Runnable sideEffect = sideEffects.remove(operation);
            if (sideEffect != null) {
                sideEffect.run();
            }
        }

        /**
         * The recorded operation sequence.
         *
         * @return {@code "<VERB> <dsname>"} entries, in order
         */
        List<String> operations() {
            return operations;
        }

        /**
         * One dataset's current contents.
         *
         * @param dsname the dataset name
         * @return its records, or an empty list
         */
        List<String> contentsOf(String dsname) {
            return contents.getOrDefault(dsname, List.of());
        }
    }

    // =============================================================================================
    // Record fixtures. Built through each model type's own API rather than by hand, so the zoned sign
    // and the field offsets come from the copybook translation and not from this test's arithmetic.
    // =============================================================================================

    /**
     * Fits an image into {@code LK-M03B-FLDT PIC X(1000)}, which is what a {@link Response} demands.
     *
     * @param image the record image
     * @return exactly {@link StatementGenerationJobB#FLDT_LENGTH} characters
     */
    private static String fldt(String image) {
        return image.length() >= StatementGenerationJobB.FLDT_LENGTH
                ? image.substring(0, StatementGenerationJobB.FLDT_LENGTH)
                : image + " ".repeat(StatementGenerationJobB.FLDT_LENGTH - image.length());
    }

    /**
     * A successful response carrying a record image.
     *
     * @param image the record image
     * @return a {@code '00'} response
     */
    private static Response ok(String image) {
        return new Response(FileStatus.OK, fldt(image));
    }

    /**
     * The end-of-file response, with the record area left as the caller blanked it.
     *
     * @return a {@code '10'} response over a blank record area
     */
    private static Response eof() {
        return new Response(FileStatus.END_OF_FILE, BLANK_FLDT);
    }

    /**
     * A response reporting an arbitrary status over a blank record area.
     *
     * @param status the two-character {@code FILE STATUS}
     * @return the response
     */
    private static Response status(String status) {
        return new Response(status, BLANK_FLDT);
    }

    /**
     * One {@code TRNX-RECORD} image, at the {@code COSTM01} geometry.
     *
     * @param cardNum the card number
     * @param tranId  the transaction identifier
     * @param desc    the description
     * @param amount  the amount, at scale 2
     * @return exactly {@link TrnxRecord#RECORD_LENGTH} characters
     */
    private static String trnxImage(String cardNum, String tranId, String desc, String amount) {
        TrnxRecord record = TrnxRecord.newRecord(ASCII);
        record.writeTrnxCardNum(cardNum);
        record.writeTrnxId(tranId);
        record.writeTrnxTypeCd("01");
        record.writeTrnxCatCd(1);
        record.writeTrnxSource("POS");
        record.writeTrnxDesc(desc);
        record.writeTrnxAmt(new BigDecimal(amount));
        record.writeTrnxMerchantId(7);
        return record.recordImage();
    }

    /**
     * One {@code CARD-XREF-RECORD} image, at the {@code CVACT03Y} geometry.
     *
     * @param cardNum the card number
     * @param custId  the nine-digit customer id
     * @param acctId  the eleven-digit account id
     * @return exactly {@link CardXrefRecord#RECORD_LENGTH} characters
     */
    private static String xrefImage(String cardNum, String custId, String acctId) {
        StringBuilder image = new StringBuilder(" ".repeat(CardXrefRecord.RECORD_LENGTH));
        place(image, CardXrefRecord.XREF_CARD_NUM_OFFSET,
                fit(cardNum, CardXrefRecord.XREF_CARD_NUM_LENGTH));
        place(image, CardXrefRecord.XREF_CUST_ID_OFFSET, fit(custId, CardXrefRecord.XREF_CUST_ID_LENGTH));
        place(image, CardXrefRecord.XREF_ACCT_ID_OFFSET, fit(acctId, CardXrefRecord.XREF_ACCT_ID_LENGTH));
        return image.toString();
    }

    /**
     * One {@code CUSTOMER-RECORD} image, at the {@code CUSTREC} geometry.
     *
     * @param first   {@code CUST-FIRST-NAME}
     * @param middle  {@code CUST-MIDDLE-NAME}
     * @param last    {@code CUST-LAST-NAME}
     * @param line3   {@code CUST-ADDR-LINE-3}
     * @param state   {@code CUST-ADDR-STATE-CD}
     * @param country {@code CUST-ADDR-COUNTRY-CD}
     * @param zip     {@code CUST-ADDR-ZIP}
     * @param fico    {@code CUST-FICO-CREDIT-SCORE}
     * @return exactly {@link Stm03CustomerRecord#RECORD_LENGTH} characters
     */
    private static String custImage(String first, String middle, String last, String line3,
            String state, String country, String zip, String fico) {
        Stm03CustomerRecord customer = new Stm03CustomerRecord(CUST_ID, first, middle, last,
                "1 MAIN STREET", "APARTMENT 2B", line3, state, country, zip, "555-0100", "555-0200",
                "123456789", "GOVTID0001", "1970-01-31", "EFT0000001", "Y", fico);
        return new String(customer.encode(ASCII), ASCII);
    }

    /**
     * The customer fixture every whole-run test uses.
     *
     * @return the image
     */
    private static String defaultCustImage() {
        return custImage("JOHN", "Q", "PUBLIC", "SPRINGFIELD", "IL", "USA", "62704", "700");
    }

    /**
     * One {@code ACCOUNT-RECORD} image, at the {@code CVACT01Y} geometry.
     *
     * @param acctId      {@code ACCT-ID}
     * @param currentBala {@code ACCT-CURR-BAL}, at scale 2
     * @return exactly {@link AccountRecord#RECORD_LENGTH} characters
     */
    private static String acctImage(long acctId, String currentBala) {
        AccountRecord account = new AccountRecord(ASCII);
        account.setAcctId(acctId);
        account.setAcctActiveStatus("Y");
        account.setAcctCurrBal(new BigDecimal(currentBala));
        account.setAcctCreditLimit(new BigDecimal("5000.00"));
        account.setAcctCashCreditLimit(new BigDecimal("1000.00"));
        account.setAcctCurrCycCredit(new BigDecimal("0.00"));
        account.setAcctCurrCycDebit(new BigDecimal("0.00"));
        account.setAcctOpenDate("2020-01-01");
        account.setAcctExpiraionDate("2030-01-01");
        account.setAcctReissueDate("2025-01-01");
        account.setAcctAddrZip(" 62704");
        account.setAcctGroupId("DEFAULT");
        return new String(account.toByteArray(), ASCII);
    }

    /**
     * The account fixture every whole-run test uses.
     *
     * @return the image
     */
    private static String defaultAcctImage() {
        return acctImage(99L, "1234.56");
    }

    /**
     * Overlays a value at a 0-based offset.
     *
     * @param target the image under construction
     * @param offset the 0-based offset
     * @param value  the value to place
     */
    private static void place(StringBuilder target, int offset, String value) {
        target.replace(offset, offset + value.length(), value);
    }

    /**
     * Fits a value to a declared width under the {@code PIC X} rule: keep the leading characters, pad on
     * the right.
     *
     * @param value  the value
     * @param length the declared width
     * @return exactly {@code length} characters
     */
    private static String fit(String value, int length) {
        return value.length() >= length ? value.substring(0, length)
                : value + " ".repeat(length - value.length());
    }

    // =============================================================================================
    // The prologue's substitute image.
    // =============================================================================================

    /**
     * A {@link TiotSource} yielding a fixed image, so the prologue's five displays are deterministic.
     *
     * @param entries the entries the walk meets
     * @return the source
     */
    private static TiotSource tiot(TiotEntry... entries) {
        TiotImage image = new TiotImage(StatementGenerationJobA.JCL_JOB_NAME,
                StatementGenerationJobA.STEP_040, List.of(entries), TiotEntry.terminator());
        return () -> image;
    }

    /**
     * The image the whole-run tests use: one allocated DD and one with nothing behind it.
     *
     * @return the source
     */
    private static TiotSource defaultTiot() {
        return tiot(new TiotEntry(StatementGenerationJobA.TRNXFILE_DD, true),
                new TiotEntry(StatementGenerationJobA.HTMLFILE_DD, false));
    }

    // =============================================================================================
    // The harness: one job, two collecting writers, one recording subroutine, one captured SYSOUT.
    // =============================================================================================

    /** A whole runnable job with every seam captured. */
    private static final class Harness {

        /** The scripted subroutine, which is also the call recorder. */
        private final ScriptedSubroutine subroutine;

        /** Every 80-byte text record emitted, in order. */
        private final List<String> textRecords = new ArrayList<>();

        /** Every 100-byte HTML record emitted, in order. */
        private final List<String> htmlRecords = new ArrayList<>();

        /** Every displayed line, in order. */
        private final CapturedSysout sysout = new CapturedSysout();

        /** The utility port the four preparation steps use. */
        private final RecordingUtilityPort utility;

        /** The spied text writer, whose no-argument open is stubbed. */
        private final StatementTextWriter textWriter;

        /** The spied HTML writer, whose no-argument open is stubbed. */
        private final StatementHtmlWriter htmlWriter;

        /** The job under test. */
        private final StatementGenerationJobA job;

        /** How many times the text sink was asked to apply its abnormal disposition. */
        private int textDiscards;

        /** How many times the HTML sink was asked to apply its abnormal disposition. */
        private int htmlDiscards;

        Harness(ScriptedSubroutine subroutine, TiotSource tiotSource, JobContracts contracts,
                RecordingUtilityPort utility) {
            this(subroutine, tiotSource, utility, scaffolding(contracts));
        }

        /**
         * The same stand-ins over a caller-supplied scaffolding, so a test that needs a <em>real</em> job
         * repository - the flow tests do, because a flow cannot run against a mock - can supply one
         * without every other test paying for a database.
         *
         * @param subroutine  the scripted subroutine
         * @param tiotSource  the TIOT source
         * @param utility     the utility port stand-in
         * @param scaffolding the batch seam to build the job through
         */
        Harness(ScriptedSubroutine subroutine, TiotSource tiotSource, RecordingUtilityPort utility,
                BatchConfig scaffolding) {
            this.subroutine = subroutine;
            this.utility = utility;

            StatementTextWriter realText = new StatementTextWriter(new JdbcTemplate(), ASCII,
                    globalBindings(), RecordImageForm.CHARACTER);
            StatementTextWriter.RecordSink textSink = new StatementTextWriter.RecordSink() {
                @Override
                public FileStatus.Outcome write(byte[] image) {
                    textRecords.add(new String(image, ASCII));
                    return FileStatus.Outcome.OK;
                }

                @Override
                public FileStatus.Outcome discard(int recordsWritten) {
                    textDiscards++;
                    return FileStatus.Outcome.OK;
                }
            };
            this.textWriter = Mockito.spy(realText);
            // A FRESH handle per open, not one handle returned repeatedly. The COBOL opens once at L293
            // and closes once at L339, so a second run unit gets a second handle - and a handle that has
            // been closed refuses further writes, exactly as the real writer does. Returning one handle
            // forever would make a two-run test fail for the harness's reason rather than the class's.
            Mockito.doAnswer(invocation -> realText.openOutput(textSink))
                    .when(this.textWriter).openOutput();

            StatementHtmlWriter realHtml = new StatementHtmlWriter(new JdbcTemplate(), ASCII,
                    globalBindings(), RecordImageForm.CHARACTER);
            StatementHtmlWriter.HtmlRecordSink htmlSink = new StatementHtmlWriter.HtmlRecordSink() {
                @Override
                public String write(byte[] record) {
                    htmlRecords.add(new String(record, ASCII));
                    return FileStatus.OK;
                }

                @Override
                public String discard(long recordsWritten) {
                    htmlDiscards++;
                    return FileStatus.OK;
                }
            };
            this.htmlWriter = Mockito.spy(realHtml);
            Mockito.doAnswer(invocation -> realHtml.open(htmlSink)).when(this.htmlWriter).open();

            this.job = new StatementGenerationJobA(subroutine, this.textWriter, this.htmlWriter,
                    scaffolding, ASCII, new JdbcTemplate(), RecordImageForm.CHARACTER, ORDINAL,
                    unitOfWork(), new PresentBean<>(sysout),
                    new PresentBean<>(tiotSource), new PresentBean<>(utility));
        }

        /**
         * Runs {@code CBSTM03A} once.
         *
         * @return how many statements were written
         */
        int run() {
            return job.printAccountStatements();
        }
    }

    /**
     * A two-character status that classifies as {@link FileStatus.Outcome#OTHER} - the {@code WHEN OTHER}
     * arm every COBOL guard chain in the estate treats as fatal. {@code '34'} is a boundary violation,
     * which is precisely the kind of condition a sequential output dataset reports and none of these two
     * files' statements guards.
     */
    private static final String HTML_REFUSED_STATUS = "34";

    /**
     * A harness whose two output sinks can be told to refuse an operation.
     *
     * <p>{@code STMT-FILE} and {@code HTML-FILE} carry no {@code FILE STATUS} clause and none of their
     * {@code OPEN}, {@code WRITE} or {@code CLOSE} statements is guarded, so a refused operation
     * terminates the run unit. Proving that needs sinks that can refuse, which the collecting harness
     * above deliberately cannot: it always answers {@code OK}.
     */
    private static final class RefusingHarness {

        /** The one-based ordinal of the text record to refuse, or zero to accept every one. */
        private final int refuseTextRecord;

        /** The one-based ordinal of the HTML record to refuse, or zero to accept every one. */
        private final int refuseHtmlRecord;

        /** The two-character status the HTML sink reports from {@code open()}. */
        private final String htmlOpenStatus;

        /** What the text sink reports from {@code close()}. */
        private final FileStatus.Outcome textCloseOutcome;

        /** The two-character status the HTML sink reports from {@code close()}. */
        private final String htmlCloseStatus;

        /**
         * Whether both discard sinks report a not-OK outcome, so the caller's refusal arm is reached.
         *
         * <p>Mutable and read at call time rather than a constructor parameter: the two sinks are built in
         * the constructor and close over the harness, and every existing caller of this harness wants the
         * default. Set it before {@link #run()}.
         */
        private boolean discardsRefused;

        /** How many text records the sink was offered. */
        private int textRecordsOffered;

        /** How many HTML records the sink was offered. */
        private int htmlRecordsOffered;

        /** Whether the text sink's close was reached. */
        private boolean textClosed;

        /** Whether the HTML sink's close was reached. */
        private boolean htmlClosed;

        /** How many times the text sink was asked to apply its abnormal disposition. */
        private int textDiscards;

        /** How many times the HTML sink was asked to apply its abnormal disposition. */
        private int htmlDiscards;

        /** The record count the text sink's disposition was given. */
        private int textDiscardCount;

        /** The record count the HTML sink's disposition was given. */
        private long htmlDiscardCount;

        /** The text handle, so a test can ask whether it was released. */
        private final StatementFile textHandle;

        /** The HTML handle, so a test can ask whether it was released. */
        private final HtmlStatementFile htmlHandle;

        /** Every displayed line, in order. */
        private final CapturedSysout sysout = new CapturedSysout();

        /** The job under test. */
        private final StatementGenerationJobA job;

        RefusingHarness(ScriptedSubroutine subroutine, int refuseTextRecord, int refuseHtmlRecord,
                String htmlOpenStatus, FileStatus.Outcome textCloseOutcome, String htmlCloseStatus) {
            this.refuseTextRecord = refuseTextRecord;
            this.refuseHtmlRecord = refuseHtmlRecord;
            this.htmlOpenStatus = htmlOpenStatus;
            this.textCloseOutcome = textCloseOutcome;
            this.htmlCloseStatus = htmlCloseStatus;

            StatementTextWriter realText = new StatementTextWriter(new JdbcTemplate(), ASCII,
                    globalBindings(), RecordImageForm.CHARACTER);
            this.textHandle = realText.openOutput(new StatementTextWriter.RecordSink() {
                @Override
                public FileStatus.Outcome write(byte[] recordImage) {
                    textRecordsOffered++;
                    return textRecordsOffered == RefusingHarness.this.refuseTextRecord
                            ? FileStatus.Outcome.OTHER
                            : FileStatus.Outcome.OK;
                }

                @Override
                public FileStatus.Outcome close() {
                    textClosed = true;
                    return RefusingHarness.this.textCloseOutcome;
                }

                @Override
                public FileStatus.Outcome discard(int recordsWritten) {
                    textDiscards++;
                    textDiscardCount = recordsWritten;
                    return RefusingHarness.this.discardsRefused
                            ? FileStatus.Outcome.OTHER
                            : FileStatus.Outcome.OK;
                }
            });
            StatementTextWriter spiedText = Mockito.spy(realText);
            Mockito.doReturn(textHandle).when(spiedText).openOutput();

            StatementHtmlWriter realHtml = new StatementHtmlWriter(new JdbcTemplate(), ASCII,
                    globalBindings(), RecordImageForm.CHARACTER);
            this.htmlHandle = realHtml.open(new StatementHtmlWriter.HtmlRecordSink() {
                @Override
                public String write(byte[] record) {
                    htmlRecordsOffered++;
                    return htmlRecordsOffered == RefusingHarness.this.refuseHtmlRecord
                            ? HTML_REFUSED_STATUS
                            : FileStatus.OK;
                }

                @Override
                public String open() {
                    return RefusingHarness.this.htmlOpenStatus;
                }

                @Override
                public String close() {
                    htmlClosed = true;
                    return RefusingHarness.this.htmlCloseStatus;
                }

                @Override
                public String discard(long recordsWritten) {
                    htmlDiscards++;
                    htmlDiscardCount = recordsWritten;
                    return RefusingHarness.this.discardsRefused
                            ? HTML_REFUSED_STATUS
                            : FileStatus.OK;
                }
            });
            StatementHtmlWriter spiedHtml = Mockito.spy(realHtml);
            Mockito.doReturn(htmlHandle).when(spiedHtml).open();

            this.job = new StatementGenerationJobA(subroutine, spiedText, spiedHtml,
                    scaffolding(jobContracts()), ASCII, new JdbcTemplate(),
                    RecordImageForm.CHARACTER, ORDINAL, unitOfWork(), new PresentBean<>(sysout),
                    new PresentBean<>(defaultTiot()), new PresentBean<>(new RecordingUtilityPort()));
        }

        /**
         * Runs {@code CBSTM03A} once.
         *
         * @return how many statements were written
         */
        int run() {
            return job.printAccountStatements();
        }
    }

    /**
     * A harness that refuses one text record.
     *
     * @param ordinal the one-based ordinal of the text record to refuse
     * @return the harness
     */
    private static RefusingHarness refusingTextRecord(int ordinal) {
        return new RefusingHarness(oneStatement(), ordinal, 0, FileStatus.OK,
                FileStatus.Outcome.OK, FileStatus.OK);
    }

    /**
     * A harness that refuses one HTML record.
     *
     * @param ordinal the one-based ordinal of the HTML record to refuse
     * @return the harness
     */
    private static RefusingHarness refusingHtmlRecord(int ordinal) {
        return new RefusingHarness(oneStatement(), 0, ordinal, FileStatus.OK,
                FileStatus.Outcome.OK, FileStatus.OK);
    }

    /**
     * A harness over a scripted subroutine, the default prologue image and the JCL contract.
     *
     * @param subroutine the scripted subroutine
     * @return the harness
     */
    private static Harness harness(ScriptedSubroutine subroutine) {
        return new Harness(subroutine, defaultTiot(), jobContracts(), new RecordingUtilityPort());
    }

    /**
     * The same harness over a caller-supplied TIOT image, for the tests that drive the prologue's walk.
     *
     * @param subroutine the scripted subroutine
     * @param tiotSource the address-space image the walk should meet
     * @return the harness
     */
    private static Harness harness(ScriptedSubroutine subroutine, TiotSource tiotSource) {
        return new Harness(subroutine, tiotSource, jobContracts(), new RecordingUtilityPort());
    }

    /**
     * A subroutine scripted for one complete statement: one transaction, one cross-reference record, one
     * customer and one account.
     *
     * @return the scripted subroutine
     */
    private static ScriptedSubroutine oneStatement() {
        return new ScriptedSubroutine()
                .reads(StatementGenerationJobA.TRNXFILE_DD,
                        ok(trnxImage(CARD_A, "TRAN000000000001", "GROCERY STORE", "12.34")), eof())
                .reads(StatementGenerationJobA.XREFFILE_DD,
                        ok(xrefImage(CARD_A, CUST_ID, ACCT_ID)), eof())
                .keyed(StatementGenerationJobA.CUSTFILE_DD, ok(defaultCustImage()))
                .keyed(StatementGenerationJobA.ACCTFILE_DD, ok(defaultAcctImage()));
    }

    // =============================================================================================
    // The Spring Batch surface.
    // =============================================================================================

    /**
     * A real unit of work over a throwaway in-memory data source.
     *
     * <p>Real rather than mocked, for the reason the update services' tests give: only a real manager makes
     * {@code persistVerb} open a boundary that a repository can detect. Here it also means the per-record
     * REPRO durability is exercised rather than stubbed out.
     *
     * <p>The URL names <em>no</em> database, which is H2's unnamed private in-memory form: each connection
     * gets a database of its own, so two tests cannot see each other's boundary and no test needs a unique
     * identifier to stay isolated. That matters for determinism (practice B7) - a generated name would make
     * two runs of this suite differ - and it costs nothing, because no test stores a row through this data
     * source. Nothing here issues DDL of any kind (gate G44); the manager exists only to open and end a
     * transaction.
     *
     * @return a unit of work; never {@code null}
     */
    private static DatasetUnitOfWork unitOfWork() {
        return new DatasetUnitOfWork(new JdbcTransactionManager(new SimpleDriverDataSource(
                new org.h2.Driver(), "jdbc:h2:mem:", "sa", "")));
    }

    /**
     * The default port over an unconfigured template, for the guards that never reach a data source.
     *
     * @return the port; never {@code null}
     */
    private static JdbcDatasetUtilityPort port() {
        return new JdbcDatasetUtilityPort(new JdbcTemplate(), ASCII, RecordImageForm.CHARACTER, ORDINAL,
                unitOfWork());
    }

    /**
     * A job over an arbitrary utility port, for the tests that observe which port operation a step calls.
     *
     * @param utility the port to inject
     * @return the job; never {@code null}
     */
    private static StatementGenerationJobA jobWithPort(DatasetUtilityPort utility) {
        return new StatementGenerationJobA(new ScriptedSubroutine(),
                new StatementTextWriter(new JdbcTemplate(), ASCII, globalBindings(),
                        RecordImageForm.CHARACTER),
                new StatementHtmlWriter(new JdbcTemplate(), ASCII, globalBindings(),
                        RecordImageForm.CHARACTER),
                scaffolding(jobContracts()), ASCII, new JdbcTemplate(), RecordImageForm.CHARACTER,
                ORDINAL,
                unitOfWork(), new PresentBean<>(new CapturedSysout()),
                new PresentBean<>(defaultTiot()), new PresentBean<>(utility));
    }

    @Nested
    @DisplayName("The job definition, from app/jcl/CREASTMT.JCL")
    class TheJobDefinition {

        @Test
        @DisplayName("the job is named statementGenerationJobA and locates all five JCL steps")
        void theJob() {
            Job published = harness(new ScriptedSubroutine()).job.statementGenerationJobA();

            assertThat(published).isNotNull();
            assertThat(published.getName()).isEqualTo(StatementGenerationJobA.JOB_NAME);
            assertThat(published).isInstanceOf(org.springframework.batch.core.step.StepLocator.class);
            assertThat(((org.springframework.batch.core.step.StepLocator) published).getStepNames())
                    .containsExactlyInAnyOrderElementsOf(StatementGenerationJobA.STEP_NAMES);
        }

        @Test
        @DisplayName("app/jcl/CREASTMT.JCL declares exactly five steps, in this order")
        void fiveStepsInJclOrder() {
            assertThat(StatementGenerationJobA.STEP_NAMES).containsExactly("DELDEF01", "STEP010",
                    "STEP020", "STEP030", "STEP040");
        }

        @Test
        @DisplayName("COND=(0,NE) is on exactly three of the five steps - STEP010 is NOT gated")
        void exactlyThreeGatedSteps() {
            assertThat(StatementGenerationJobA.GATED_STEP_NAMES).containsExactly("STEP020", "STEP030",
                    "STEP040");
            assertThat(StatementGenerationJobA.GATED_STEP_NAMES)
                    .doesNotContain(StatementGenerationJobA.STEP_DELDEF01,
                            StatementGenerationJobA.STEP_010);
        }

        @ParameterizedTest
        @DisplayName("each resolved step contract carries the gating its JCL step carries")
        @CsvSource({"DELDEF01,IDCAMS,false", "STEP010,SORT,false", "STEP020,IDCAMS,true",
                "STEP030,IEFBR14,true", "STEP040,CBSTM03A,true"})
        void everyStepContract(String stepName, String program, boolean gated) {
            StepContract contract =
                    harness(new ScriptedSubroutine()).job.stepContract(stepName);

            assertThat(contract.name()).isEqualTo(stepName);
            assertThat(contract.program()).isEqualTo(program);
            assertThat(contract.requirePrecedingExitCodeZero()).isEqualTo(gated);
        }

        @Test
        @DisplayName("stepContracts() reports all five, in JCL declaration order")
        void allFiveContracts() {
            List<StepContract> contracts = harness(new ScriptedSubroutine()).job.stepContracts();

            assertThat(contracts.stream().map(StepContract::name).toList())
                    .isEqualTo(StatementGenerationJobA.STEP_NAMES);
        }

        @Test
        @DisplayName("stepContract refuses a step name the JCL does not declare")
        void anUnknownStepName() {
            StatementGenerationJobA job = harness(new ScriptedSubroutine()).job;

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> job.stepContract("STEP999"))
                    .withMessageContaining("STEP999")
                    .withMessageContaining("declaration order");
        }

        @ParameterizedTest
        @DisplayName("each step method builds a step named after its JCL step")
        @ValueSource(strings = {"DELDEF01", "STEP010", "STEP020", "STEP030", "STEP040"})
        void eachStepIsNamedAfterItsJclStep(String stepName) {
            StatementGenerationJobA job = harness(new ScriptedSubroutine()).job;
            Map<String, Step> steps = Map.of(
                    StatementGenerationJobA.STEP_DELDEF01, job.deldef01Step(),
                    StatementGenerationJobA.STEP_010, job.step010Step(),
                    StatementGenerationJobA.STEP_020, job.step020Step(),
                    StatementGenerationJobA.STEP_030, job.step030Step(),
                    StatementGenerationJobA.STEP_040, job.step040Step());

            assertThat(steps.get(stepName).getName()).isEqualTo(stepName);
        }

        @Test
        @DisplayName("a fresh builder is used per call, so two jobs are distinct objects")
        void buildersAreNotShared() {
            StatementGenerationJobA job = harness(new ScriptedSubroutine()).job;

            assertThat(job.statementGenerationJobA()).isNotSameAs(job.statementGenerationJobA());
            assertThat(job.deldef01Step()).isNotSameAs(job.deldef01Step());
            assertThat(job.step040Step()).isNotSameAs(job.step040Step());
        }

        @Test
        @DisplayName("the job declares NO job parameters - no step of CREASTMT.JCL carries a PARM")
        void noJobParameters() {
            assertThat(harness(new ScriptedSubroutine()).job.jobParameters().isEmpty()).isTrue();
        }

        @Test
        @DisplayName("the gate forwards to BatchConfig's shared decider and reports itself as COND=(0,NE)")
        void theGateForwards() {
            BatchConfig scaffolding = scaffolding(jobContracts());
            StatementGenerationJobA.CondGate gate =
                    new StatementGenerationJobA.CondGate(scaffolding.precedingExitCodeZeroDecider());
            JobExecution execution = new JobExecution(1L);

            assertThat(gate.toString()).isEqualTo("COND=(0,NE)");
            assertThat(gate.decide(execution, null)).isEqualTo(BatchConfig.PROCEED);
        }

        @Test
        @DisplayName("the gate refuses to wrap nothing: a decision state must have a decider")
        void theGateNeedsADecider() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new StatementGenerationJobA.CondGate(null));
        }

        @Test
        @DisplayName("two gates over one decider are distinct objects, so three decision states exist")
        void gatesAreDistinctInstances() {
            JobExecutionDecider shared = scaffolding(jobContracts()).precedingExitCodeZeroDecider();

            assertThat(new StatementGenerationJobA.CondGate(shared))
                    .isNotSameAs(new StatementGenerationJobA.CondGate(shared));
        }

        @Test
        @DisplayName("the gate skips once a preceding step has reported a non-zero exit code")
        void theGateSkips() {
            BatchConfig scaffolding = scaffolding(jobContracts());
            StatementGenerationJobA.CondGate gate =
                    new StatementGenerationJobA.CondGate(scaffolding.precedingExitCodeZeroDecider());
            JobExecution execution = new JobExecution(2L);
            StepExecution failed = execution.createStepExecution(StatementGenerationJobA.STEP_010);
            failed.setExitStatus(new ExitStatus("8"));

            FlowExecutionStatus decision = gate.decide(execution, failed);

            assertThat(decision).isIn(BatchConfig.SKIP, BatchConfig.PROCEED);
            assertThat(decision).isEqualTo(BatchConfig.SKIP);
        }

        @Test
        @DisplayName("the identifiers this job publishes are the ones CREASTMT.JCL and the CSD use")
        void publishedIdentifiers() {
            assertThat(StatementGenerationJobA.PROGRAM_ID).isEqualTo("CBSTM03A");
            assertThat(StatementGenerationJobA.JOB_KEY).isEqualTo("statement-generation-job-a");
            assertThat(StatementGenerationJobA.JOB_NAME).isEqualTo("statementGenerationJobA");
            assertThat(StatementGenerationJobA.JCL_JOB_NAME).isEqualTo("CREASTMT")
                    .hasSize(StatementGenerationJobA.TIOT_NAME_WIDTH);
            assertThat(StatementGenerationJobA.CONFIGURATION_BEAN_NAME)
                    .isNotEqualTo(StatementGenerationJobA.JOB_NAME);
        }

        @Test
        @DisplayName("STEP040's six DD names are the ones CREASTMT.JCL declares on that step")
        void theProgramStepsDdNames() {
            assertThat(StatementGenerationJobA.STEP_040_DD_NAMES).containsExactly("TRNXFILE",
                    "XREFFILE", "ACCTFILE", "CUSTFILE", "STMTFILE", "HTMLFILE");
        }
    }

    /**
     * The {@code COND=(0,NE)} bypass, driven through the real job on a real job repository.
     *
     * <p>These are the only tests in this class that run the assembled {@link Job} rather than calling
     * its parts. They have to: what is under test is a <em>flow terminal</em>, and a terminal only exists
     * once the flow is built and only takes effect once the flow reaches it. A mocked job repository
     * cannot execute a flow, so an in-memory database and Spring Batch's own schema stand behind them.
     *
     * <h2>How a bypass is provoked</h2>
     * <p>{@code BatchConfig}'s gate asks whether every step execution recorded on the job so far
     * returned zero. No tasklet in this module reports a non-zero <em>completed</em> exit status - every
     * non-zero code in the estate comes from an {@link AbendException}, which fails its step - so the
     * condition is created the way the gate reads it: a step execution carrying {@code 4} is recorded on
     * the job execution, and the flow then runs normally into the gate. That is a faithful stand-in for
     * {@code app/jcl/CREASTMT.JCL}'s real exposure, which is {@code STEP010}'s DFSORT completing with
     * {@code RC=4} - precisely the case the JCL author put {@code COND=(0,NE)} on the following three
     * steps to handle.
     *
     * <p>Placing that record at three different points exercises all three arms: before the flow starts
     * for the first gate, and from inside the utility port for the second and third, since the port is
     * the only collaborator {@code STEP020} and {@code STEP030} call.
     */
    @Nested
    @DisplayName("COND=(0,NE) bypass - a flushed job reports the code that flushed it, never zero")
    class TheCondBypassExitStatus {

        @Test
        @DisplayName("the first gate bypasses: STEP020, STEP030 and STEP040 never run and the job "
                + "reports 4, not 0")
        void theFirstGateBypasses() throws Exception {
            withRealJobRepository("bypass_gate1", (repository, scaffolding) -> {
                Harness harness = new Harness(new ScriptedSubroutine(), defaultTiot(),
                        new RecordingUtilityPort(), scaffolding);
                Job job = harness.job.statementGenerationJobA();
                JobExecution execution = newExecution(repository);
                recordEarlierStepReturning(repository, execution, "4");

                ((AbstractJob) job).execute(execution);

                // Bypassing is not failing: on the mainframe a step flushed by COND does not fail the
                // job. Only the reported condition code changes.
                assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
                assertThat(execution.getExitStatus().getExitCode())
                        .as("a bare end() would say COMPLETED here, which is return code zero")
                        .isEqualTo("4");
                assertThat(jclReturnCodeOf(execution))
                        .as("and this is what the launcher hands the operating system")
                        .isEqualTo(4);
                assertThat(stepsRun(execution))
                        .containsExactly(EARLIER_STEP, StatementGenerationJobA.STEP_DELDEF01,
                                StatementGenerationJobA.STEP_010);
            });
        }

        @Test
        @DisplayName("the second gate bypasses: STEP030 and STEP040 never run and the job reports 8")
        void theSecondGateBypasses() throws Exception {
            withRealJobRepository("bypass_gate2", (repository, scaffolding) -> {
                RecordingUtilityPort utility = new RecordingUtilityPort();
                Harness harness = new Harness(new ScriptedSubroutine(), defaultTiot(), utility,
                        scaffolding);
                Job job = harness.job.statementGenerationJobA();
                JobExecution execution = newExecution(repository);
                // STEP020's REPRO reads INFILE - the intermediate sequential file STEP010 sorted into.
                // Recording the non-zero condition as that read happens puts it after the first gate has
                // already let the flow through, so the gate before STEP030 is the one that sees it.
                utility.onceOn("READ " + TRXFL_SEQ_DSNAME,
                        () -> recordEarlierStepReturning(repository, execution, "8"));

                ((AbstractJob) job).execute(execution);

                assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
                assertThat(execution.getExitStatus().getExitCode()).isEqualTo("8");
                assertThat(jclReturnCodeOf(execution)).isEqualTo(8);
                assertThat(stepsRun(execution))
                        .containsExactly(StatementGenerationJobA.STEP_DELDEF01,
                                StatementGenerationJobA.STEP_010, StatementGenerationJobA.STEP_020,
                                EARLIER_STEP);
            });
        }

        @Test
        @DisplayName("the third gate bypasses: STEP040 never runs, no statement is written, and the job "
                + "reports 12")
        void theThirdGateBypasses() throws Exception {
            withRealJobRepository("bypass_gate3", (repository, scaffolding) -> {
                RecordingUtilityPort utility = new RecordingUtilityPort();
                Harness harness = new Harness(new ScriptedSubroutine(), defaultTiot(), utility,
                        scaffolding);
                Job job = harness.job.statementGenerationJobA();
                JobExecution execution = newExecution(repository);
                // STEP030 clears the two report datasets. Recording the condition on the first of those
                // deletes lands after the second gate has let the flow through.
                utility.onceOn("DELETE " + HTMLFILE_DSNAME,
                        () -> recordEarlierStepReturning(repository, execution, "12"));

                ((AbstractJob) job).execute(execution);

                assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
                assertThat(execution.getExitStatus().getExitCode()).isEqualTo("12");
                assertThat(jclReturnCodeOf(execution)).isEqualTo(12);
                assertThat(stepsRun(execution))
                        .doesNotContain(StatementGenerationJobA.STEP_040);
                assertThat(harness.textRecords)
                        .as("STEP040 is the program; bypassed, it writes no statement at all")
                        .isEmpty();
                assertThat(harness.htmlRecords).isEmpty();
            });
        }

        @Test
        @DisplayName("no bypass: every step returns zero, all five run, and the job reports COMPLETED")
        void noBypassLeavesTheJobCompleted() throws Exception {
            // The control case, and it is what stops the fix over-reaching. The listener that carries a
            // bypass code must leave an ordinary successful run reporting COMPLETED rather than the
            // literal "0", because COMPLETED is what every other job in the estate reports on success
            // and BatchConfig.returnCodeOf reads the two identically.
            withRealJobRepository("bypass_none", (repository, scaffolding) -> {
                Harness harness = new Harness(oneStatement(), defaultTiot(),
                        new RecordingUtilityPort(), scaffolding);
                Job job = harness.job.statementGenerationJobA();
                JobExecution execution = newExecution(repository);

                ((AbstractJob) job).execute(execution);

                assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
                assertThat(execution.getExitStatus().getExitCode())
                        .as("COMPLETED, not the literal \"0\" - and BatchConfig reads the two the same")
                        .isEqualTo(ExitStatus.COMPLETED.getExitCode());
                assertThat(stepsRun(execution)).isEqualTo(StatementGenerationJobA.STEP_NAMES);
                assertThat(harness.textRecords)
                        .as("STEP040 ran, so the statement it prints is there")
                        .isNotEmpty();
            });
        }

        /** The name of the synthetic step execution that stands in for a step returning non-zero. */
        private static final String EARLIER_STEP = "EARLIER";

        /**
         * The JCL return code a finished job reports, read the way the launcher reads it.
         *
         * <p>A numeric parse of the exit code, which is exactly what {@code BatchConfig.returnCodeOf}
         * does before {@code JclJobLauncher} hands the value to the operating system. Parsed here rather
         * than by calling that method, because it is package-private to {@code config} and widening
         * production visibility for a test's convenience is not a trade worth making - the mapping itself
         * is driven exhaustively by {@code BatchConfigTest}, which is in that package.
         *
         * @param execution the finished job execution
         * @return the parsed return code
         */
        private int jclReturnCodeOf(JobExecution execution) {
            return Integer.parseInt(execution.getExitStatus().getExitCode().trim());
        }

        /**
         * Records a completed step execution carrying the given JCL return code on a job execution.
         *
         * @param repository    the repository that must persist it, so the flow sees it
         * @param execution     the running job execution
         * @param jclReturnCode the code the step reported
         */
        private void recordEarlierStepReturning(JobRepository repository, JobExecution execution,
                String jclReturnCode) {
            StepExecution earlier = execution.createStepExecution(EARLIER_STEP);
            earlier.setStatus(BatchStatus.COMPLETED);
            earlier.setExitStatus(new ExitStatus(jclReturnCode));
            repository.add(earlier);
        }

        /**
         * @param execution the finished job execution
         * @return the names of the steps that actually ran, in execution order
         */
        private List<String> stepsRun(JobExecution execution) {
            return execution.getStepExecutions().stream().map(StepExecution::getStepName).toList();
        }

        /**
         * A fresh job execution for the job under test.
         *
         * @param repository the repository to create it in
         * @return the execution
         * @throws Exception if the repository refuses it
         */
        private JobExecution newExecution(JobRepository repository) throws Exception {
            return repository.createJobExecution(StatementGenerationJobA.JOB_NAME,
                    new JobParameters());
        }

        /**
         * Runs a body against a real Spring Batch job repository over an in-memory database, and a
         * {@link BatchConfig} bound to it.
         *
         * @param databaseName a name unique to the calling test, so two tests never share a schema
         * @param body         what to run
         * @throws Exception if the body throws
         */
        private void withRealJobRepository(String databaseName, RepositoryBody body) throws Exception {
            new ApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(DataSourceAutoConfiguration.class,
                            DataSourceTransactionManagerAutoConfiguration.class,
                            BatchAutoConfiguration.class))
                    .withPropertyValues(
                            "spring.datasource.url=jdbc:h2:mem:carddemo_" + databaseName,
                            "spring.datasource.driver-class-name=org.h2.Driver",
                            "spring.batch.jdbc.initialize-schema=always",
                            "spring.batch.job.enabled=false")
                    .run(context -> {
                        JobRepository repository = context.getBean(JobRepository.class);
                        body.accept(repository, new BatchConfig(new PresentBean<>(repository),
                                new PresentBean<>(context.getBean(PlatformTransactionManager.class)),
                                jobContracts(), globalBindings()));
                    });
        }

        /** What {@link #withRealJobRepository} runs, allowed to throw. */
        @FunctionalInterface
        private interface RepositoryBody {

            /**
             * @param repository  the real job repository
             * @param scaffolding a {@link BatchConfig} bound to it
             * @throws Exception if the body fails
             */
            void accept(JobRepository repository, BatchConfig scaffolding) throws Exception;
        }
    }

    /** The contract gate: five steps, gated exactly as the JCL gates them, and no {@code PARM}. */
    @Nested
    @DisplayName("The carddemo.jobs contract gate")
    class TheContractGate {

        @Test
        @DisplayName("a contract missing a step is refused")
        void aMissingStep() {
            List<StepContract> four = new ArrayList<>(jclSteps());
            four.remove(4);
            JobContracts contracts = jobContracts(four, List.of());

            assertThatIllegalStateException()
                    .isThrownBy(() -> new Harness(new ScriptedSubroutine(), defaultTiot(), contracts,
                            new RecordingUtilityPort()))
                    .withMessageContaining(StatementGenerationJobA.STEP_040);
        }

        @Test
        @DisplayName("a step the JCL does not gate but configuration does is refused")
        void anOverGatedStep() {
            List<StepContract> gatedStep010 = List.of(jclSteps().get(0),
                    new StepContract(StatementGenerationJobA.STEP_010, "SORT", true),
                    jclSteps().get(2), jclSteps().get(3), jclSteps().get(4));
            JobContracts contracts = jobContracts(gatedStep010, List.of());

            assertThatIllegalStateException()
                    .isThrownBy(() -> new Harness(new ScriptedSubroutine(), defaultTiot(), contracts,
                            new RecordingUtilityPort()))
                    .withMessageContaining("require-preceding-exit-code-zero=true")
                    .withMessageContaining(StatementGenerationJobA.STEP_010);
        }

        @Test
        @DisplayName("a step the JCL gates but configuration does not is refused")
        void anUnderGatedStep() {
            List<StepContract> ungatedStep020 = List.of(jclSteps().get(0), jclSteps().get(1),
                    new StepContract(StatementGenerationJobA.STEP_020, "IDCAMS", false),
                    jclSteps().get(3), jclSteps().get(4));
            JobContracts contracts = jobContracts(ungatedStep020, List.of());

            assertThatIllegalStateException()
                    .isThrownBy(() -> new Harness(new ScriptedSubroutine(), defaultTiot(), contracts,
                            new RecordingUtilityPort()))
                    .withMessageContaining("require-preceding-exit-code-zero=false")
                    .withMessageContaining(StatementGenerationJobA.STEP_020);
        }

        @Test
        @DisplayName("a STEP040 naming another program is refused")
        void anotherProgramOnTheProgramStep() {
            List<StepContract> wrongProgram = List.of(jclSteps().get(0), jclSteps().get(1),
                    jclSteps().get(2), jclSteps().get(3),
                    new StepContract(StatementGenerationJobA.STEP_040, "CBSTM03B", true));
            JobContracts contracts = jobContracts(wrongProgram, List.of());

            assertThatIllegalStateException()
                    .isThrownBy(() -> new Harness(new ScriptedSubroutine(), defaultTiot(), contracts,
                            new RecordingUtilityPort()))
                    .withMessageContaining("CBSTM03B")
                    .withMessageContaining("EXEC PGM=CBSTM03A");
        }

        @Test
        @DisplayName("the two IDCAMS steps swapped are refused, even though every name, program and "
                + "gate is individually present")
        void theTwoUtilityStepsReordered() {
            // The sharpest case in the estate. DELDEF01 and STEP020 both run IDCAMS
            // (app/jcl/CREASTMT.JCL:L22 and L56) and do opposite things to the same work file: the first
            // DELETEs and DEFINEs it, the second REPROs the sorted extract into it. Swap them and every
            // per-step check still passes - both names are declared, both programs are IDCAMS, and the
            // gate that belongs to STEP020 is still on STEP020 - while the job deletes and redefines the
            // KSDS *after* loading it. STEP040 would then generate statements from an empty extract and
            // the job would report success. Only comparing the sequence in order rejects it.
            List<StepContract> swapped = new ArrayList<>(jclSteps());
            Collections.swap(swapped, 0, 2);
            JobContracts contracts = jobContracts(swapped, List.of());

            assertThatIllegalStateException()
                    .isThrownBy(() -> new Harness(new ScriptedSubroutine(), defaultTiot(), contracts,
                            new RecordingUtilityPort()))
                    .withMessageContaining("does not declare the step sequence of "
                            + "app/jcl/CREASTMT.JCL")
                    .withMessageContaining("configured: [STEP020/IDCAMS [COND=(0,NE)], STEP010/SORT, "
                            + "DELDEF01/IDCAMS, STEP030/IEFBR14 [COND=(0,NE)], "
                            + "STEP040/CBSTM03A [COND=(0,NE)]]")
                    .withMessageContaining("required:   [DELDEF01/IDCAMS, STEP010/SORT, "
                            + "STEP020/IDCAMS [COND=(0,NE)], STEP030/IEFBR14 [COND=(0,NE)], "
                            + "STEP040/CBSTM03A [COND=(0,NE)]]");
        }

        @Test
        @DisplayName("a sixth step declared beside the five is refused - CREASTMT.JCL has five EXECs")
        void anAddedStep() {
            List<StepContract> six = new ArrayList<>(jclSteps());
            six.add(new StepContract("STEP050", StatementGenerationJobA.PROGRAM_ID, true));
            JobContracts contracts = jobContracts(six, List.of());

            assertThatIllegalStateException()
                    .isThrownBy(() -> new Harness(new ScriptedSubroutine(), defaultTiot(), contracts,
                            new RecordingUtilityPort()))
                    .withMessageContaining("STEP050/CBSTM03A [COND=(0,NE)]")
                    .withMessageContaining("does not declare the step sequence of "
                            + "app/jcl/CREASTMT.JCL");
        }

        @Test
        @DisplayName("a utility step re-pointed at another program is refused, which the STEP040 program "
                + "check cannot see")
        void anotherProgramOnAUtilityStep() {
            // requireJclContract checks the program of STEP040 and of no other step, so a wrong program
            // on any of the first four is invisible to it. IEFBR14 on STEP010 would replace the DFSORT
            // that produces the sorted extract with a no-op, and the load step would then REPRO whatever
            // the previous run left in SORTOUT.
            List<StepContract> sortReplaced = new ArrayList<>(jclSteps());
            sortReplaced.set(1, new StepContract(StatementGenerationJobA.STEP_010,
                    StatementGenerationJobA.NOOP_PROGRAM, false));
            JobContracts contracts = jobContracts(sortReplaced, List.of());

            assertThatIllegalStateException()
                    .isThrownBy(() -> new Harness(new ScriptedSubroutine(), defaultTiot(), contracts,
                            new RecordingUtilityPort()))
                    .withMessageContaining("configured: [DELDEF01/IDCAMS, STEP010/IEFBR14, ")
                    .withMessageContaining("required:   [DELDEF01/IDCAMS, STEP010/SORT, ");
        }

        @Test
        @DisplayName("the shipped five-step sequence names the three programs CREASTMT.JCL names")
        void theShippedSequence() {
            assertThat(StatementGenerationJobA.REQUIRED_STEPS)
                    .extracting(StepContract::name)
                    .as("declaration order, DELDEF01 first")
                    .isEqualTo(StatementGenerationJobA.STEP_NAMES);
            assertThat(StatementGenerationJobA.REQUIRED_STEPS)
                    .filteredOn(StepContract::requirePrecedingExitCodeZero)
                    .extracting(StepContract::name)
                    .as("exactly three COND=(0,NE) gates, and STEP010 is not one of them")
                    .isEqualTo(StatementGenerationJobA.GATED_STEP_NAMES);
            assertThat(StatementGenerationJobA.REQUIRED_STEPS)
                    .extracting(StepContract::program)
                    .containsExactly(StatementGenerationJobA.UTILITY_PROGRAM,
                            StatementGenerationJobA.SORT_PROGRAM,
                            StatementGenerationJobA.UTILITY_PROGRAM,
                            StatementGenerationJobA.NOOP_PROGRAM,
                            StatementGenerationJobA.PROGRAM_ID);
        }

        @Test
        @DisplayName("a declared job parameter is refused - the only PARM in the estate is INTCALC's")
        void aDeclaredJobParameter() {
            JobContracts contracts = jobContracts(jclSteps(),
                    List.of(new JobParameterContract("parmDate", "string", "2022071800")));

            assertThatIllegalStateException()
                    .isThrownBy(() -> new Harness(new ScriptedSubroutine(), defaultTiot(), contracts,
                            new RecordingUtilityPort()))
                    .withMessageContaining("job parameter")
                    .withMessageContaining("INTCALC");
        }
    }

    /** Constructor guards and the three optional seams. */
    @Nested
    @DisplayName("Construction and the injected seams")
    class TheConstructor {

        /**
         * Builds the job with one collaborator replaced by {@code null}.
         *
         * @param absent which position to blank, 0-based over the twelve constructor arguments
         */
        private void buildWithout(int absent) {
            ScriptedSubroutine subroutine = absent == 0 ? null : new ScriptedSubroutine();
            StatementTextWriter text = absent == 1 ? null : new StatementTextWriter(new JdbcTemplate(),
                    ASCII, globalBindings(), RecordImageForm.CHARACTER);
            StatementHtmlWriter html = absent == 2 ? null : new StatementHtmlWriter(new JdbcTemplate(),
                    ASCII, globalBindings(), RecordImageForm.CHARACTER);
            BatchConfig scaffolding = absent == 3 ? null : scaffolding(jobContracts());
            Charset charset = absent == 4 ? null : ASCII;
            JdbcTemplate template = absent == 5 ? null : new JdbcTemplate();
            RecordImageForm form = absent == 6 ? null : RecordImageForm.CHARACTER;
            PhysicalSequence ordinal = absent == 7 ? null : ORDINAL;
            DatasetUnitOfWork boundary = absent == 8 ? null : unitOfWork();
            ObjectProvider<SysoutSink> sysout =
                    absent == 9 ? null : new PresentBean<>(new CapturedSysout());
            ObjectProvider<TiotSource> tiot = absent == 10 ? null : new PresentBean<>(defaultTiot());
            ObjectProvider<DatasetUtilityPort> utility =
                    absent == 11 ? null : new PresentBean<>(new RecordingUtilityPort());
            new StatementGenerationJobA(subroutine, text, html, scaffolding, charset, template, form,
                    ordinal, boundary, sysout, tiot, utility);
        }

        @ParameterizedTest
        @DisplayName("every one of the twelve collaborators is required")
        @ValueSource(ints = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11})
        void everyCollaboratorIsRequired(int absent) {
            assertThatNullPointerException().isThrownBy(() -> buildWithout(absent));
        }

        @Test
        @DisplayName("the absent ordinal is refused by name: SORT and REPRO move records in order")
        void theOrdinalIsRequiredByName() {
            assertThatNullPointerException().isThrownBy(() -> buildWithout(7))
                    .withMessageContaining(PhysicalSequence.EXPRESSION_PROPERTY);
        }

        @Test
        @DisplayName("an absent SYSOUT sink falls back to standard output")
        void anAbsentSysoutSink() {
            StatementGenerationJobA job = new StatementGenerationJobA(new ScriptedSubroutine(),
                    new StatementTextWriter(new JdbcTemplate(), ASCII, globalBindings(),
                            RecordImageForm.CHARACTER),
                    new StatementHtmlWriter(new JdbcTemplate(), ASCII, globalBindings(),
                            RecordImageForm.CHARACTER),
                    scaffolding(jobContracts()), ASCII, new JdbcTemplate(),
                    RecordImageForm.CHARACTER, ORDINAL, unitOfWork(), new AbsentBean<>(),
                    new AbsentBean<>(), new AbsentBean<>());

            assertThat(job.sysoutSink()).isNotNull();
            assertThat(job.tiotSource()).isNotNull();
            assertThat(job.datasetUtilityPort()).isInstanceOf(JdbcDatasetUtilityPort.class);
            assertThat(job.datasetCharset()).isSameAs(ASCII);
        }

        @Test
        @DisplayName("a declared SYSOUT sink, TIOT source and utility port are used as supplied")
        void declaredSeamsWin() {
            CapturedSysout sysout = new CapturedSysout();
            TiotSource source = defaultTiot();
            RecordingUtilityPort port = new RecordingUtilityPort();
            Harness built = new Harness(new ScriptedSubroutine(), source, jobContracts(), port);

            assertThat(built.job.tiotSource()).isSameAs(source);
            assertThat(built.job.datasetUtilityPort()).isSameAs(port);
            assertThat(built.job.sysoutSink()).isSameAs(built.sysout);
            assertThat(sysout.lines()).isEmpty();
        }

        @Test
        @DisplayName("the default SYSOUT sink is built over the named code page")
        void theDefaultSysoutSink() {
            // Deliberately NOT invoked. The sink writes straight to the process's standard-output file
            // descriptor, which is exactly what a batch program's SYSOUT is and exactly what the test
            // harness's own forked-JVM channel sits on; writing to it from a test corrupts that channel.
            // Nothing of this class is left uncovered by not calling it - the returned value is a bound
            // method reference to PrintStream.println, whose body is the JDK's.
            assertThat(StatementGenerationJobA.standardOutput(ASCII)).isNotNull();
            assertThat(StatementGenerationJobA.standardOutput(java.nio.charset.Charset.forName("IBM037")))
                    .isNotNull();
        }

        @Test
        @DisplayName("the default SYSOUT sink refuses an unnamed code page")
        void theDefaultSysoutSinkNeedsACharset() {
            assertThatNullPointerException()
                    .isThrownBy(() -> StatementGenerationJobA.standardOutput(null));
        }

        @Test
        @DisplayName("the configured TIOT substitute names CREASTMT, STEP040 and the six STEP040 DDs")
        void theConfiguredTiotSubstitute() {
            StatementGenerationJobA job = new StatementGenerationJobA(new ScriptedSubroutine(),
                    new StatementTextWriter(new JdbcTemplate(), ASCII, globalBindings(),
                            RecordImageForm.CHARACTER),
                    new StatementHtmlWriter(new JdbcTemplate(), ASCII, globalBindings(),
                            RecordImageForm.CHARACTER),
                    scaffolding(jobContracts()), ASCII, new JdbcTemplate(),
                    RecordImageForm.CHARACTER, ORDINAL, unitOfWork(), new AbsentBean<>(),
                    new AbsentBean<>(), new AbsentBean<>());

            TiotImage image = job.tiotSource().read();

            assertThat(image.jobName()).isEqualTo("CREASTMT");
            assertThat(image.stepName()).isEqualTo("STEP040 ");
            assertThat(image.entries().stream().map(TiotEntry::ddName).toList())
                    .containsExactly("TRNXFILE", "XREFFILE", "ACCTFILE", "CUSTFILE", "STMTFILE",
                            "HTMLFILE");
            assertThat(image.entries()).allMatch(TiotEntry::validUcb);
            assertThat(image.terminator().validUcb()).isFalse();
            assertThat(image.terminator().ddName()).isEqualTo(" ".repeat(8));
        }

        @ParameterizedTest
        @DisplayName("a STEP040 DD with nothing allocated behind it is reported as a null UCB")
        @ValueSource(strings = {"", "absent"})
        void aDdWithNoDatasetIsANullUcb(String dsname) {
            DatasetBindings catalogue = globalBindings();
            catalogue.put(StatementGenerationJobA.CUSTFILE_DD, new DatasetBinding(
                    "absent".equals(dsname) ? null : dsname, DatasetBinding.KSDS, false, "FB", null,
                    Stm03CustomerRecord.RECORD_LENGTH, null, Stm03CustomerRecord.CUST_ID_LENGTH, 0,
                    null, null));
            BatchConfig scaffolding = new BatchConfig(
                    new PresentBean<>(Mockito.mock(JobRepository.class)),
                    new PresentBean<>(Mockito.mock(PlatformTransactionManager.class)), jobContracts(),
                    catalogue);
            StatementGenerationJobA job = new StatementGenerationJobA(new ScriptedSubroutine(),
                    new StatementTextWriter(new JdbcTemplate(), ASCII, globalBindings(),
                            RecordImageForm.CHARACTER),
                    new StatementHtmlWriter(new JdbcTemplate(), ASCII, globalBindings(),
                            RecordImageForm.CHARACTER),
                    scaffolding, ASCII, new JdbcTemplate(), RecordImageForm.CHARACTER, ORDINAL, unitOfWork(),
                    new AbsentBean<>(), new AbsentBean<>(), new AbsentBean<>());

            TiotImage image = job.tiotSource().read();

            assertThat(image.entries()).hasSize(6);
            assertThat(image.entries().stream()
                    .filter(entry -> !entry.validUcb())
                    .map(TiotEntry::ddName).toList())
                    .containsExactly(StatementGenerationJobA.CUSTFILE_DD);
        }

        @Test
        @DisplayName("datasetBinding resolves through the job contract, aliases included")
        void datasetBindingsResolve() {
            StatementGenerationJobA job = harness(new ScriptedSubroutine()).job;

            assertThat(job.datasetBinding(StatementGenerationJobA.SORTIN_DD).dsname())
                    .isEqualTo(TRANSACT_DSNAME);
            assertThat(job.datasetBinding(StatementGenerationJobA.SORTOUT_DD).dsname())
                    .isEqualTo(TRXFL_SEQ_DSNAME);
            assertThat(job.datasetBinding(StatementGenerationJobA.INFILE_DD).dsname())
                    .isEqualTo(TRXFL_SEQ_DSNAME);
            assertThat(job.datasetBinding(StatementGenerationJobA.OUTFILE_DD).dsname())
                    .isEqualTo(TRNXFILE_DSNAME);
            assertThat(job.datasetBinding(StatementGenerationJobA.HTMLFILE_DD).recordLength())
                    .isEqualTo(100);
            assertThat(job.datasetBinding(StatementGenerationJobA.STMTFILE_DD).recordLength())
                    .isEqualTo(80);
        }

        @Test
        @DisplayName("the DD keys and the WS-FL-DD states are the same five names, plus READTRNX")
        void statesAndDdNames() {
            assertThat(StatementGenerationJobA.STATE_TRNXFILE)
                    .isEqualTo(StatementGenerationJobA.TRNXFILE_DD);
            assertThat(StatementGenerationJobA.STATE_XREFFILE)
                    .isEqualTo(StatementGenerationJobA.XREFFILE_DD);
            assertThat(StatementGenerationJobA.STATE_CUSTFILE)
                    .isEqualTo(StatementGenerationJobA.CUSTFILE_DD);
            assertThat(StatementGenerationJobA.STATE_ACCTFILE)
                    .isEqualTo(StatementGenerationJobA.ACCTFILE_DD);
            assertThat(StatementGenerationJobA.STATE_READTRNX).isEqualTo("READTRNX");
            assertThat(StatementGenerationJobA.FILE_CONTROL_STATES).containsExactly("TRNXFILE",
                    "XREFFILE", "CUSTFILE", "ACCTFILE", "READTRNX");
        }
    }

    // =============================================================================================
    // DELDEF01, STEP010, STEP020, STEP030 - the four preparation steps.
    // =============================================================================================

    /** The four utility steps and their tasklets. */
    @Nested
    @DisplayName("The four dataset-preparation steps")
    class TheUtilitySteps {

        /**
         * A harness whose work datasets are seeded with the given transaction records.
         *
         * @param tranRecordImages the {@code TRAN-RECORD} images to seed {@code SORTIN} with
         * @return the harness
         */
        private Harness seeded(List<String> tranRecordImages) {
            RecordingUtilityPort port = new RecordingUtilityPort().seed(TRANSACT_DSNAME,
                    tranRecordImages);
            return new Harness(new ScriptedSubroutine(), defaultTiot(), jobContracts(), port);
        }

        @Test
        @DisplayName("DELDEF01 clears the sequential work file then the work cluster, in that order")
        void deleteAndDefine() {
            Harness built = seeded(List.of());
            built.utility.seed(TRXFL_SEQ_DSNAME, List.of("a", "b"));
            built.utility.seed(TRNXFILE_DSNAME, List.of("c"));

            int cleared = built.job.deleteAndDefineWorkDatasets();

            assertThat(cleared).isEqualTo(3);
            assertThat(built.utility.operations()).containsExactly("DELETE " + TRXFL_SEQ_DSNAME,
                    "DELETE " + TRNXFILE_DSNAME);
        }

        @Test
        @DisplayName("DELDEF01 reports zero when neither work dataset holds anything - SET MAXCC = 0")
        void deleteAndDefineOverEmptyDatasets() {
            assertThat(seeded(List.of()).job.deleteAndDefineWorkDatasets()).isZero();
        }

        @Test
        @DisplayName("DELDEF01 asserts the DEFINE CLUSTER geometry rather than issuing DDL")
        void theDefineClusterGeometryIsAsserted() {
            assertThat(StatementGenerationJobA.WORK_KSDS_KEY_LENGTH).isEqualTo(32);
            assertThat(StatementGenerationJobA.WORK_KSDS_KEY_OFFSET).isZero();
            assertThat(StatementGenerationJobA.WORK_KSDS_RECORD_LENGTH).isEqualTo(350);
            assertThat(StatementGenerationJobA.WORK_SEQUENTIAL_BLOCK_SIZE).isEqualTo(3500);
        }

        @Test
        @DisplayName("a sequential work cluster is refused: CBSTM03B reads TRNXFILE by a 32-byte key")
        void aSequentialWorkClusterIsRefused() {
            DatasetBindings broken = globalBindings();
            broken.put(StatementGenerationJobA.TRNXFILE_DD,
                    sequential(TRNXFILE_DSNAME, TrnxRecord.RECORD_LENGTH, 3500));
            StatementGenerationJobA job = jobOver(broken);

            assertThatIllegalStateException().isThrownBy(job::deleteAndDefineWorkDatasets)
                    .withMessageContaining("INDEXED");
        }

        @Test
        @DisplayName("a work cluster at the wrong record width is refused")
        void aMiswidthedWorkClusterIsRefused() {
            DatasetBindings broken = globalBindings();
            broken.put(StatementGenerationJobA.TRNXFILE_DD, ksds(TRNXFILE_DSNAME, 349, 32));
            StatementGenerationJobA job = jobOver(broken);

            assertThatIllegalStateException().isThrownBy(job::deleteAndDefineWorkDatasets)
                    .withMessageContaining("RECORDSIZE(350 350)");
        }

        @Test
        @DisplayName("a work cluster at the wrong key width is refused")
        void aMiskeyedWorkClusterIsRefused() {
            DatasetBindings broken = globalBindings();
            broken.put(StatementGenerationJobA.TRNXFILE_DD,
                    ksds(TRNXFILE_DSNAME, TrnxRecord.RECORD_LENGTH, 16));
            StatementGenerationJobA job = jobOver(broken);

            assertThatIllegalStateException().isThrownBy(job::deleteAndDefineWorkDatasets)
                    .withMessageContaining("KEYS(32 0)");
        }

        @Test
        @DisplayName("a work cluster declaring no key at all is refused")
        void anUnkeyedWorkClusterIsRefused() {
            DatasetBindings broken = globalBindings();
            broken.put(StatementGenerationJobA.TRNXFILE_DD, new DatasetBinding(TRNXFILE_DSNAME,
                    DatasetBinding.KSDS, false, "FB", null, TrnxRecord.RECORD_LENGTH, null, null, null,
                    null, null));
            StatementGenerationJobA job = jobOver(broken);

            assertThatIllegalStateException().isThrownBy(job::deleteAndDefineWorkDatasets)
                    .withMessageContaining("KEYS(32 0)");
        }

        @Test
        @DisplayName("a work cluster whose key does not start at offset zero is refused")
        void anOffsetKeyedWorkClusterIsRefused() {
            DatasetBindings broken = globalBindings();
            broken.put(StatementGenerationJobA.TRNXFILE_DD, new DatasetBinding(TRNXFILE_DSNAME,
                    DatasetBinding.KSDS, false, "FB", null, TrnxRecord.RECORD_LENGTH, null,
                    TrnxRecord.TRNX_KEY_LENGTH, 4, null, null));
            StatementGenerationJobA job = jobOver(broken);

            assertThatIllegalStateException().isThrownBy(job::deleteAndDefineWorkDatasets)
                    .withMessageContaining("at offset 4");
        }

        @Test
        @DisplayName("an intermediate sequential file at the wrong block size is refused")
        void aMisblockedSequentialFileIsRefused() {
            assertThatIllegalStateException()
                    .isThrownBy(() -> jobWithWorkSequential(new JobDatasetBinding(null,
                            TRXFL_SEQ_DSNAME, "sequential", false, "FB", 3200,
                            StatementGenerationJobA.WORK_KSDS_RECORD_LENGTH, "COSTM01", null, null,
                            null, null)).deleteAndDefineWorkDatasets())
                    .withMessageContaining("BLKSIZE=3500");
        }

        @Test
        @DisplayName("an intermediate sequential file declaring no block size at all is refused")
        void anUnblockedSequentialFileIsRefused() {
            assertThatIllegalStateException()
                    .isThrownBy(() -> jobWithWorkSequential(new JobDatasetBinding(null,
                            TRXFL_SEQ_DSNAME, "sequential", false, "FB", null,
                            StatementGenerationJobA.WORK_KSDS_RECORD_LENGTH, "COSTM01", null, null,
                            null, null)).deleteAndDefineWorkDatasets())
                    .withMessageContaining("BLKSIZE=3500");
        }

        @Test
        @DisplayName("an intermediate sequential file at the wrong record width is refused")
        void aMiswidthedSequentialFileIsRefused() {
            assertThatIllegalStateException()
                    .isThrownBy(() -> jobWithWorkSequential(new JobDatasetBinding(null,
                            TRXFL_SEQ_DSNAME, "sequential", false, "FB",
                            StatementGenerationJobA.WORK_SEQUENTIAL_BLOCK_SIZE, 349, "COSTM01", null,
                            null, null, null)).deleteAndDefineWorkDatasets())
                    .withMessageContaining("LRECL=350");
        }

        /**
         * A job whose {@value StatementGenerationJobA#SORTOUT_DD} override is the given binding.
         *
         * @param sortOut the job-scoped binding to declare
         * @return the job
         */
        private StatementGenerationJobA jobWithWorkSequential(JobDatasetBinding sortOut) {
            Map<String, JobDatasetBinding> datasets = new LinkedHashMap<>(jobDatasets());
            datasets.put(StatementGenerationJobA.SORTOUT_DD, sortOut);
            JobContracts contracts = new JobContracts();
            contracts.put(StatementGenerationJobA.JOB_KEY,
                    new JobContract(StatementGenerationJobA.PROGRAM_ID, List.of(), jclSteps(), null,
                            datasets));
            return new Harness(new ScriptedSubroutine(), defaultTiot(), contracts,
                    new RecordingUtilityPort()).job;
        }

        @Test
        @DisplayName("STEP010 reads the transaction master and writes the sorted sequential file")
        void sortAndReformat() {
            String first = tranImage(CARD_B, "TRAN000000000002");
            String second = tranImage(CARD_A, "TRAN000000000001");
            Harness built = seeded(List.of(first, second));

            int written = built.job.sortAndReformatTransactions();

            assertThat(written).isEqualTo(2);
            assertThat(built.utility.operations()).containsExactly("READ " + TRANSACT_DSNAME,
                    "WRITE " + TRXFL_SEQ_DSNAME);
            List<String> derived = built.utility.contentsOf(TRXFL_SEQ_DSNAME);
            assertThat(derived).hasSize(2);
            assertThat(derived.get(0)).startsWith(CARD_A);
            assertThat(derived.get(1)).startsWith(CARD_B);
        }

        @Test
        @DisplayName("STEP020 REPROs the sequential file into the keyed cluster, unchanged and in order")
        void repro() {
            Harness built = seeded(List.of());
            List<String> sorted = List.of(trnxImage(CARD_A, "TRAN000000000001", "ONE", "1.00"),
                    trnxImage(CARD_B, "TRAN000000000002", "TWO", "2.00"));
            built.utility.seed(TRXFL_SEQ_DSNAME, sorted);

            int loaded = built.job.reproSortedFileIntoWorkDataset();

            assertThat(loaded).isEqualTo(2);
            assertThat(built.utility.operations()).containsExactly("READ " + TRXFL_SEQ_DSNAME,
                    "WRITE " + TRNXFILE_DSNAME);
            assertThat(built.utility.contentsOf(TRNXFILE_DSNAME)).isEqualTo(sorted);
        }

        @Test
        @DisplayName("STEP030 clears the HTML statement then the plain-text statement, in that order")
        void deletePreviousReports() {
            Harness built = seeded(List.of());
            built.utility.seed(HTMLFILE_DSNAME, List.of("x"));
            built.utility.seed(STMTFILE_DSNAME, List.of("y", "z"));

            int cleared = built.job.deletePreviousReportDatasets();

            assertThat(cleared).isEqualTo(3);
            assertThat(built.utility.operations()).containsExactly("DELETE " + HTMLFILE_DSNAME,
                    "DELETE " + STMTFILE_DSNAME);
        }

        @ParameterizedTest
        @DisplayName("each preparation tasklet finishes and reports its record count")
        @ValueSource(strings = {"DELDEF01", "STEP010", "STEP020", "STEP030"})
        void eachPreparationTasklet(String stepName) throws Exception {
            Harness built = seeded(List.of(tranImage(CARD_A, "TRAN000000000001")));
            built.utility.seed(TRXFL_SEQ_DSNAME, List.of());
            Map<String, Tasklet> tasklets = Map.of(
                    StatementGenerationJobA.STEP_DELDEF01, built.job.deleteAndDefineTasklet(),
                    StatementGenerationJobA.STEP_010, built.job.sortAndReformatTasklet(),
                    StatementGenerationJobA.STEP_020, built.job.reproTasklet(),
                    StatementGenerationJobA.STEP_030, built.job.deletePreviousReportsTasklet());
            StepExecution stepExecution = new StepExecution(stepName, new JobExecution(11L));
            StepContribution contribution = new StepContribution(stepExecution);

            RepeatStatus finished = tasklets.get(stepName).execute(contribution,
                    new ChunkContext(new StepContext(stepExecution)));

            assertThat(finished).isEqualTo(RepeatStatus.FINISHED);
            assertThat(contribution.getWriteCount()).isNotNegative();
        }

        /**
         * A job over a deliberately broken global catalogue.
         *
         * @param catalogue the catalogue to bind
         * @return the job
         */
        private StatementGenerationJobA jobOver(DatasetBindings catalogue) {
            BatchConfig scaffolding = new BatchConfig(
                    new PresentBean<>(Mockito.mock(JobRepository.class)),
                    new PresentBean<>(Mockito.mock(PlatformTransactionManager.class)), jobContracts(),
                    catalogue);
            return new StatementGenerationJobA(new ScriptedSubroutine(),
                    new StatementTextWriter(new JdbcTemplate(), ASCII, globalBindings(),
                            RecordImageForm.CHARACTER),
                    new StatementHtmlWriter(new JdbcTemplate(), ASCII, globalBindings(),
                            RecordImageForm.CHARACTER),
                    scaffolding, ASCII, new JdbcTemplate(), RecordImageForm.CHARACTER, ORDINAL, unitOfWork(),
                    new PresentBean<>(new CapturedSysout()),
                    new PresentBean<>(defaultTiot()), new PresentBean<>(new RecordingUtilityPort()));
        }
    }

    /**
     * One {@code TRAN-RECORD} image at the {@code CVTRA05Y} geometry, which is what {@code SORTIN} holds.
     *
     * <p>Built by hand rather than through a model type, because the whole point of {@code OUTREC} is that
     * the <em>input</em> layout differs from the output one: {@code TRAN-ID} is at position 1 and
     * {@code TRAN-CARD-NUM} at position 263.
     *
     * @param cardNum the card number, input positions 263-278
     * @param tranId  the transaction identifier, input positions 1-16
     * @return exactly 350 characters
     */
    private static String tranImage(String cardNum, String tranId) {
        StringBuilder image = new StringBuilder(" ".repeat(TrnxRecord.RECORD_LENGTH));
        place(image, 0, fit(tranId, 16));
        place(image, 16, "01");
        place(image, 18, "0001");
        place(image, 32, fit("MERCHANT DESCRIPTION", 100));
        place(image, 132, "00000012345");
        place(image, 252, fit("62704", 10));
        place(image, 262, fit(cardNum, 16));
        place(image, 278, "2022-07-18-12.34.56.123456");
        place(image, 304, "2022-07-19-01.02.03.987654");
        return image.toString();
    }

    // =============================================================================================
    // STEP010's control statements as two pure functions.
    // =============================================================================================

    /** {@code SORT FIELDS} and {@code OUTREC FIELDS}, asserted byte position by byte position. */
    @Nested
    @DisplayName("The SORT and OUTREC transformation")
    class TheSortAndOutrec {

        @Test
        @DisplayName("SORT FIELDS orders by card number first, then by transaction id")
        void sortFieldsOrder() {
            String cardAtranB = tranImage(CARD_A, "TRAN000000000002");
            String cardAtranA = tranImage(CARD_A, "TRAN000000000001");
            String cardBtranA = tranImage(CARD_B, "TRAN000000000001");
            List<String> unordered = new ArrayList<>(List.of(cardBtranA, cardAtranB, cardAtranA));

            unordered.sort(StatementGenerationJobA.SORT_FIELDS_ORDER);

            assertThat(unordered).containsExactly(cardAtranA, cardAtranB, cardBtranA);
        }

        @Test
        @DisplayName("OUTREC moves the card number to the front and completes the 32-byte key")
        void outrecBuildsTheKey() {
            String derived = harness(new ScriptedSubroutine()).job
                    .applyOutrec(tranImage(CARD_A, "TRAN000000000001"));

            assertThat(derived).hasSize(TrnxRecord.RECORD_LENGTH);
            assertThat(derived).as("output 1-16 is TRAN-CARD-NUM").startsWith(CARD_A);
            assertThat(derived.substring(TrnxRecord.TRNX_ID_OFFSET,
                    TrnxRecord.TRNX_ID_OFFSET + TrnxRecord.TRNX_ID_LENGTH))
                    .isEqualTo("TRAN000000000001");
            assertThat(derived.substring(TrnxRecord.TRNX_KEY_OFFSET,
                    TrnxRecord.TRNX_KEY_OFFSET + TrnxRecord.TRNX_KEY_LENGTH))
                    .isEqualTo(CARD_A + "TRAN000000000001");
        }

        @Test
        @DisplayName("OUTREC lands the body fields at the COSTM01 offsets")
        void outrecLandsTheBody() {
            String derived = harness(new ScriptedSubroutine()).job
                    .applyOutrec(tranImage(CARD_A, "TRAN000000000001"));
            TrnxRecord record = TrnxRecord.decode(derived.getBytes(ASCII), ASCII);

            assertThat(record.readTrnxCardNum()).isEqualTo(CARD_A);
            assertThat(record.readTrnxId()).isEqualTo("TRAN000000000001");
            assertThat(record.readTrnxTypeCd()).isEqualTo("01");
            assertThat(record.readTrnxCatCd()).isEqualTo(1);
            assertThat(record.readTrnxDesc()).startsWith("MERCHANT DESCRIPTION");
            assertThat(record.readTrnxAmt()).isEqualByComparingTo(new BigDecimal("123.45"));
            assertThat(record.readTrnxMerchantZip()).isEqualTo("62704     ");
        }

        @Test
        @DisplayName("OUTREC copies 50 trailing bytes, NOT 52 - so TRNX-PROC-TS loses its last two")
        void outrecCopiesFiftyNotFiftyTwo() {
            String derived = harness(new ScriptedSubroutine()).job
                    .applyOutrec(tranImage(CARD_A, "TRAN000000000001"));
            TrnxRecord record = TrnxRecord.decode(derived.getBytes(ASCII), ASCII);

            assertThat(StatementGenerationJobA.OUTREC_TAIL_LENGTH).isEqualTo(50);
            assertThat(record.readTrnxOrigTs()).isEqualTo("2022-07-18-12.34.56.123456");
            assertThat(record.readTrnxProcTs())
                    .isEqualTo("2022-07-19-01.02.03.9876" + "  ")
                    .hasSize(TrnxRecord.TRNX_PROC_TS_LENGTH);
            assertThat(record.readTrnxProcTs()
                    .substring(0, TrnxRecord.TRNX_PROC_TS_SORT_DERIVED_LENGTH))
                    .isEqualTo("2022-07-19-01.02.03.9876");
            assertThat(record.readFiller()).isBlank();
        }

        @Test
        @DisplayName("OUTREC leaves output 329-350 blank, because nothing is copied there")
        void outrecLeavesTheFillerBlank() {
            String derived = harness(new ScriptedSubroutine()).job
                    .applyOutrec(tranImage(CARD_A, "TRAN000000000001"));

            assertThat(derived.substring(StatementGenerationJobA.OUTREC_TAIL_POSITION - 1
                    + StatementGenerationJobA.OUTREC_TAIL_LENGTH)).isEqualTo(" ".repeat(22));
        }

        @Test
        @DisplayName("the sort happens before the reformat, which is the only order that works")
        void sortThenReformat() {
            List<String> derived = harness(new ScriptedSubroutine()).job.sortAndReformat(
                    List.of(tranImage(CARD_B, "TRAN000000000009"),
                            tranImage(CARD_A, "TRAN000000000001")));

            assertThat(derived).hasSize(2);
            assertThat(derived.get(0)).startsWith(CARD_A);
            assertThat(derived.get(1)).startsWith(CARD_B);
            assertThat(derived).allSatisfy(image -> assertThat(image)
                    .hasSize(TrnxRecord.RECORD_LENGTH));
        }

        @Test
        @DisplayName("an empty transaction master yields an empty work dataset, not an error")
        void anEmptyMaster() {
            assertThat(harness(new ScriptedSubroutine()).job.sortAndReformat(List.of())).isEmpty();
        }

        @Test
        @DisplayName("a short input record is space-padded to 350 before the reformat reads it")
        void aShortInputRecord() {
            String derived = harness(new ScriptedSubroutine()).job.applyOutrec("SHORT");

            // The five characters sat at input 1-5, which OUTREC's 17:1,262 triple relocates to
            // output 17-21. Positions 1-16 come from input 263-278, which the padding blanked.
            assertThat(derived).hasSize(TrnxRecord.RECORD_LENGTH);
            assertThat(derived.substring(0, TrnxRecord.TRNX_CARD_NUM_LENGTH)).isBlank();
            assertThat(derived.substring(StatementGenerationJobA.OUTREC_BODY_OUTPUT_POSITION - 1,
                    StatementGenerationJobA.OUTREC_BODY_OUTPUT_POSITION - 1 + "SHORT".length()))
                    .isEqualTo("SHORT");
        }

        @Test
        @DisplayName("both pure functions refuse null")
        void nullIsRefused() {
            StatementGenerationJobA job = harness(new ScriptedSubroutine()).job;

            assertThatNullPointerException().isThrownBy(() -> job.applyOutrec(null));
            assertThatNullPointerException().isThrownBy(() -> job.sortAndReformat(null));
        }

        @Test
        @DisplayName("the OUTREC geometry constants are the JCL's own triples")
        void theGeometryConstants() {
            assertThat(StatementGenerationJobA.SORT_MAJOR_KEY_POSITION).isEqualTo(263);
            assertThat(StatementGenerationJobA.SORT_MAJOR_KEY_LENGTH).isEqualTo(16);
            assertThat(StatementGenerationJobA.SORT_MINOR_KEY_POSITION).isEqualTo(1);
            assertThat(StatementGenerationJobA.SORT_MINOR_KEY_LENGTH).isEqualTo(16);
            assertThat(StatementGenerationJobA.OUTREC_CARD_NUM_OUTPUT_POSITION).isEqualTo(1);
            assertThat(StatementGenerationJobA.OUTREC_BODY_OUTPUT_POSITION).isEqualTo(17);
            assertThat(StatementGenerationJobA.OUTREC_BODY_INPUT_POSITION).isEqualTo(1);
            assertThat(StatementGenerationJobA.OUTREC_BODY_LENGTH).isEqualTo(262);
            assertThat(StatementGenerationJobA.OUTREC_TAIL_POSITION).isEqualTo(279);
            assertThat(StatementGenerationJobA.SORT_RECORD_LENGTH).isEqualTo(350);
        }
    }

    /** The JDBC-backed default utility port: three statements and one grammar. */
    @Nested
    @DisplayName("The default dataset utility port")
    class TheJdbcUtilityPort {

        @Test
        @DisplayName("all three collaborators are required, and the code page must be a total "
                + "single-byte one")
        void allThreeArgumentsAreRequired() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new JdbcDatasetUtilityPort(null, ASCII,
                            RecordImageForm.CHARACTER, ORDINAL, unitOfWork()));
            assertThatNullPointerException()
                    .isThrownBy(() -> new JdbcDatasetUtilityPort(new JdbcTemplate(), null,
                            RecordImageForm.CHARACTER, ORDINAL, unitOfWork()));
        }

        @Test
        @DisplayName("a binding naming no dataset is refused at the point of use, not at construction")
        void anUnnamedDataset() {
            JdbcDatasetUtilityPort port = port();
            DatasetBinding unnamed = sequential(null, 80, 8000);

            assertThatIllegalStateException().isThrownBy(() -> port.deleteAllRecords(unnamed))
                    .withMessageContaining("declares no");
        }

        @ParameterizedTest
        @DisplayName("a value that is not a z/OS dataset name is refused, by the shared grammar")
        @ValueSource(strings = {"../etc/passwd", "TOOLONGAQUALIFIER.X",
                "AWS..CARDDEMO", "1BAD.START",
                "AAAAAAAA.BBBBBBBB.CCCCCCCC.DDDDDDDD.EEEEEEEE.FFFFFFFF"})
        void aMalformedDatasetName(String dsname) {
            JdbcDatasetUtilityPort port = port();
            DatasetBinding malformed = sequential(dsname, 80, 8000);

            // DatasetRelation is the one grammar this module has, so the refusal is its refusal - and it
            // names the offending position, which a pattern match cannot.
            assertThatIllegalStateException().isThrownBy(() -> port.deleteAllRecords(malformed))
                    .withMessageContaining("not a well-formed z/OS dataset name")
                    .withCauseInstanceOf(IllegalArgumentException.class);
        }

        @ParameterizedTest
        @DisplayName("a lower-case name and a generation suffix ARE addressable: configuration carries "
                + "both and the shared grammar admits both")
        @ValueSource(strings = {"lower.case", "CARDDEMO.PARITY.SYSTRAN(+1)", "Mixed.Case.Name"})
        void aNameTheSharedGrammarAdmits(String dsname) {
            // A second grammar used to live in this port, and it disagreed in the direction that refuses
            // valid configuration: upper case only, and no notion of a relative-generation suffix. So a
            // deployment declaring its names as it spells them, or binding one of the three GDG outputs,
            // could not be addressed at all. Delegating to DatasetRelation removed the disagreement.
            JdbcDatasetUtilityPort port = port();
            DatasetBinding admitted = sequential(dsname, 80, 8000);

            // It reaches the template, which carries no DataSource here - so it got past the grammar.
            assertThatIllegalStateException().isThrownBy(() -> port.deleteAllRecords(admitted))
                    .withMessageContaining("DataSource");
        }

        @Test
        @DisplayName("a binding whose dsname is the empty string is refused too")
        void anEmptyDatasetName() {
            JdbcDatasetUtilityPort port = port();
            DatasetBinding empty = sequential("", 80, 8000);

            assertThatIllegalStateException().isThrownBy(() -> port.deleteAllRecords(empty))
                    .withMessageContaining("declares no");
        }

        @Test
        @DisplayName("writeRecordImages refuses a null record list but accepts an empty one")
        void writeRefusesNull() {
            JdbcDatasetUtilityPort port = port();
            DatasetBinding binding = sequential(STMTFILE_DSNAME, 80, 8000);

            assertThatNullPointerException()
                    .isThrownBy(() -> port.writeRecordImages(binding, null));
            assertThat(port.writeRecordImages(binding, List.of())).isZero();
        }

        @Test
        @DisplayName("writeRecordImages refuses a null stop signal rather than defaulting it")
        void writeRefusesANullStopSignal() {
            JdbcDatasetUtilityPort port = new JdbcDatasetUtilityPort(new JdbcTemplate(), ASCII,
                    RecordImageForm.CHARACTER, ORDINAL);
            DatasetBinding binding = sequential(STMTFILE_DSNAME, 80, 8000);

            assertThatNullPointerException()
                    .isThrownBy(() -> port.writeRecordImages(binding, List.of(), null))
                    .withMessageContaining("StopSignal.RUNNING");
        }

        @Test
        @DisplayName("a copy stops BETWEEN records: the records written before the stop are all there, "
                + "whole, and none is written twice (N-02)")
        void aCopyStopsBetweenRecords() {
            List<String> records = new ArrayList<>();
            for (int index = 1; index <= 5; index++) {
                records.add(trnxImage(CARD_A, String.format("TRAN%012d", index), "COPY", "1.00"));
            }
            JdbcTemplate template = seededRelation("a-copy-stops-between-records", TRXFL_SEQ_DSNAME,
                    StatementGenerationJobA.WORK_KSDS_RECORD_LENGTH, List.of());
            JdbcDatasetUtilityPort port = new JdbcDatasetUtilityPort(template, ASCII,
                    RecordImageForm.CHARACTER, ORDINAL);
            DatasetBinding binding = sequential(TRXFL_SEQ_DSNAME,
                    StatementGenerationJobA.WORK_KSDS_RECORD_LENGTH,
                    StatementGenerationJobA.WORK_SEQUENTIAL_BLOCK_SIZE);
            StepExecution stepExecution =
                    new StepExecution(StatementGenerationJobA.STEP_020, new JobExecution(31L));
            int stopAfter = 2;

            assertThatExceptionOfType(StopRequestedException.class).isThrownBy(() ->
                    port.writeRecordImages(binding, records,
                            signalStoppingAfter(stepExecution, stopAfter)));

            // Exactly the records written before the stop, in order, each at its declared width. This is
            // the property the finding turns on: NOTHING is retried, so a stopped copy holds a prefix of
            // its input and never a duplicate of any record in it.
            assertThat(port.readAllRecordImages(binding))
                    .containsExactlyElementsOf(records.subList(0, stopAfter));
            assertThat(port.readAllRecordImages(binding)).doesNotHaveDuplicates();
        }

        @Test
        @DisplayName("a stop before the first record writes nothing at all")
        void aStopBeforeTheFirstRecordWritesNothing() {
            JdbcTemplate template = seededRelation("a-stop-before-the-first-record-writes-nothing", TRXFL_SEQ_DSNAME,
                    StatementGenerationJobA.WORK_KSDS_RECORD_LENGTH, List.of());
            JdbcDatasetUtilityPort port = new JdbcDatasetUtilityPort(template, ASCII,
                    RecordImageForm.CHARACTER, ORDINAL);
            DatasetBinding binding = sequential(TRXFL_SEQ_DSNAME,
                    StatementGenerationJobA.WORK_KSDS_RECORD_LENGTH,
                    StatementGenerationJobA.WORK_SEQUENTIAL_BLOCK_SIZE);
            StepExecution stepExecution =
                    new StepExecution(StatementGenerationJobA.STEP_020, new JobExecution(32L));
            stepExecution.setTerminateOnly();

            assertThatExceptionOfType(StopRequestedException.class).isThrownBy(() ->
                    port.writeRecordImages(binding,
                            List.of(trnxImage(CARD_A, "TRAN000000000001", "ONE", "1.00")),
                            StopSignal.of(stepExecution)))
                    .withMessageContaining(StatementGenerationJobA.STEP_020)
                    .withCauseInstanceOf(JobInterruptedException.class);

            assertThat(port.readAllRecordImages(binding)).isEmpty();
        }

        @Test
        @DisplayName("the two-argument write is unbounded, so every existing caller is unchanged")
        void theTwoArgumentWriteIsUnbounded() {
            JdbcTemplate template = seededRelation("the-two-argument-write-is-unbounded", TRXFL_SEQ_DSNAME,
                    StatementGenerationJobA.WORK_KSDS_RECORD_LENGTH, List.of());
            JdbcDatasetUtilityPort port = new JdbcDatasetUtilityPort(template, ASCII,
                    RecordImageForm.CHARACTER, ORDINAL);
            DatasetBinding binding = sequential(TRXFL_SEQ_DSNAME,
                    StatementGenerationJobA.WORK_KSDS_RECORD_LENGTH,
                    StatementGenerationJobA.WORK_SEQUENTIAL_BLOCK_SIZE);
            String record = trnxImage(CARD_A, "TRAN000000000001", "ONE", "1.00");

            assertThat(port.writeRecordImages(binding, List.of(record))).isOne();
            assertThat(port.readAllRecordImages(binding)).containsExactly(record);
        }

        @Test
        @DisplayName("a port that cannot subdivide its write still honours a stop, once, before it "
                + "starts - which is what the interface default is for")
        void theInterfaceDefaultProbesOnceBeforeWriting() {
            List<String> written = new ArrayList<>();
            DatasetUtilityPort bulkOnly = new DatasetUtilityPort() {

                @Override
                public int deleteAllRecords(DatasetBinding binding) {
                    return 0;
                }

                @Override
                public List<String> readAllRecordImages(DatasetBinding binding) {
                    return List.copyOf(written);
                }

                @Override
                public int writeRecordImages(DatasetBinding binding, List<String> recordImages) {
                    written.addAll(recordImages);
                    return recordImages.size();
                }
            };
            DatasetBinding binding = sequential(STMTFILE_DSNAME, 80, 8000);
            StepExecution stepExecution =
                    new StepExecution(StatementGenerationJobA.STEP_020, new JobExecution(33L));
            stepExecution.setTerminateOnly();

            assertThatExceptionOfType(StopRequestedException.class).isThrownBy(() ->
                    bulkOnly.writeRecordImages(binding, List.of("a record"),
                            StopSignal.of(stepExecution)));
            assertThat(written).isEmpty();

            // And with no stop pending it delegates, so an implementation that overrides nothing keeps
            // working exactly as it did.
            assertThat(bulkOnly.writeRecordImages(binding, List.of("a record"), StopSignal.RUNNING))
                    .isOne();
            assertThat(written).containsExactly("a record");
        }

        /**
         * A probe that permits the given number of records and then reports a stop.
         *
         * <p>It sets {@code terminateOnly} on the real step execution and then delegates to the real
         * {@link StopSignal}, so the refusal is produced by the production probe and the framework's own
         * interruption policy rather than by a stand-in that merely throws the same type.
         *
         * @param stepExecution the execution to mark
         * @param permitted     how many consultations return before the stop is requested
         * @return the probe
         */
        private StopSignal signalStoppingAfter(StepExecution stepExecution, int permitted) {
            StopSignal real = StopSignal.of(stepExecution);
            int[] consulted = { 0 };
            return () -> {
                if (consulted[0] == permitted) {
                    stepExecution.setTerminateOnly();
                }
                consulted[0]++;
                real.checkStopRequested();
            };
        }

        @Test
        @DisplayName("read, write and delete move whole datasets through the delimited identifier")
        void theThreeOperationsAgainstARelation() {
            JdbcTemplate template = seededRelation("short-row-fitted", TRXFL_SEQ_DSNAME,
                    StatementGenerationJobA.WORK_KSDS_RECORD_LENGTH,
                    List.of(trnxImage(CARD_A, "TRAN000000000001", "ONE", "1.00"),
                            trnxImage(CARD_B, "TRAN000000000002", "TWO", "2.00")));
            JdbcDatasetUtilityPort port = new JdbcDatasetUtilityPort(template, ASCII,
                    RecordImageForm.CHARACTER, ORDINAL, unitOfWork());
            DatasetBinding binding = sequential(TRXFL_SEQ_DSNAME,
                    StatementGenerationJobA.WORK_KSDS_RECORD_LENGTH,
                    StatementGenerationJobA.WORK_SEQUENTIAL_BLOCK_SIZE);

            List<String> read = port.readAllRecordImages(binding);

            assertThat(read).hasSize(2);
            assertThat(read).allSatisfy(image -> assertThat(image)
                    .hasSize(StatementGenerationJobA.WORK_KSDS_RECORD_LENGTH));
            assertThat(read.get(0)).startsWith(CARD_A);

            assertThat(port.writeRecordImages(binding,
                    List.of(trnxImage(CARD_C, "TRAN000000000003", "THREE", "3.00")))).isEqualTo(1);
            assertThat(port.readAllRecordImages(binding)).hasSize(3);
            assertThat(port.deleteAllRecords(binding)).isEqualTo(3);
            assertThat(port.readAllRecordImages(binding)).isEmpty();
        }

        @Test
        @DisplayName("a short stored row is fitted to the declared width under the PIC X rule")
        void aShortStoredRow() {
            JdbcTemplate template = seededRelation("short-row-padded", TRXFL_SEQ_DSNAME, 350,
                    List.of("SHORT"));
            JdbcDatasetUtilityPort shortPort = new JdbcDatasetUtilityPort(template, ASCII,
                    RecordImageForm.CHARACTER, ORDINAL, unitOfWork());

            // The PIC X receiving rule, which is the rule every cross-width move in this module obeys:
            // a short sender is padded on the RIGHT to the receiver's declared width.
            assertThat(shortPort.readAllRecordImages(sequential(TRXFL_SEQ_DSNAME, 350, 3500)))
                    .containsExactly("SHORT" + " ".repeat(345));

            // The same rule seen from the other side: a relation holding 350-byte records read under a
            // binding that declares 80 truncates on the RIGHT, exactly as a COBOL MOVE of a long
            // alphanumeric sender into a short PIC X receiver does.
            String wide = trnxImage(CARD_A, "TRAN000000000001", "WIDE", "1.00");
            JdbcTemplate wideRow = seededRelation("a-short-stored-row-wide", TRXFL_SEQ_DSNAME, 350,
                    List.of(wide));
            JdbcDatasetUtilityPort widePort = new JdbcDatasetUtilityPort(wideRow, ASCII,
                    RecordImageForm.CHARACTER, ORDINAL);

            assertThat(widePort.readAllRecordImages(sequential(TRXFL_SEQ_DSNAME, 80, 8000)))
                    .containsExactly(wide.substring(0, 80));
        }

        @Test
        @DisplayName("a record of another width is fitted on the way out too, so every stored row is "
                + "exactly the declared width (gate G19)")
        void aWrongWidthRecordIsFittedOnTheWayOut() {
            JdbcTemplate template = seededRelation("a-wrong-width-record-is-fitted-on-the-way-out", TRXFL_SEQ_DSNAME, 350, List.of());
            JdbcDatasetUtilityPort port = new JdbcDatasetUtilityPort(template, ASCII,
                    RecordImageForm.CHARACTER, ORDINAL);
            DatasetBinding binding = sequential(TRXFL_SEQ_DSNAME, 350, 3500);
            String valid = trnxImage(CARD_A, "TRAN000000000001", "ONE", "1.00");

            assertThat(port.writeRecordImages(binding, List.of(valid, "SHORT"))).isEqualTo(2);

            // Both rows are the declared width: the second was padded on the RIGHT by the PIC X move
            // rule rather than stored at the width the caller happened to hand over, so a later read
            // decodes both against the copybook's own offsets.
            assertThat(port.readAllRecordImages(binding))
                    .containsExactly(valid, "SHORT" + " ".repeat(345));
        }

        @Test
        @DisplayName("the record image crosses JDBC through the configured representation, never "
                + "getString - both forms exercised")
        void theRecordImageCrossesThroughTheConfiguredForm() throws java.sql.SQLException {
            for (RecordImageForm form : RecordImageForm.values()) {
                javax.sql.DataSource dataSource = org.mockito.Mockito.mock(javax.sql.DataSource.class);
                java.sql.Connection connection = org.mockito.Mockito.mock(java.sql.Connection.class);
                java.sql.PreparedStatement statement =
                        org.mockito.Mockito.mock(java.sql.PreparedStatement.class);
                org.mockito.Mockito.when(dataSource.getConnection()).thenReturn(connection);
                org.mockito.Mockito.when(connection.prepareStatement(org.mockito.ArgumentMatchers
                        .anyString())).thenReturn(statement);
                JdbcDatasetUtilityPort port = new JdbcDatasetUtilityPort(new JdbcTemplate(dataSource),
                        ASCII, form, ORDINAL);
                String record = trnxImage(CARD_A, "TRAN000000000001", "FORM", "1.00");

                port.writeRecordImages(sequential(TRXFL_SEQ_DSNAME, 350, 3500), List.of(record));

                if (form == RecordImageForm.BINARY) {
                    org.mockito.Mockito.verify(statement).setBytes(1, record.getBytes(ASCII));
                    org.mockito.Mockito.verify(statement, org.mockito.Mockito.never())
                            .setString(org.mockito.ArgumentMatchers.anyInt(),
                                    org.mockito.ArgumentMatchers.anyString());
                } else {
                    org.mockito.Mockito.verify(statement).setString(1, record);
                    org.mockito.Mockito.verify(statement, org.mockito.Mockito.never())
                            .setBytes(org.mockito.ArgumentMatchers.anyInt(),
                                    org.mockito.ArgumentMatchers.any());
                }
            }
        }

        @Test
        @DisplayName("a row carrying no record image at all is a corrupt dataset, and says so")
        void aRowWithNoRecordImage() {
            JdbcTemplate template = seededRelation("no-record-image", TRXFL_SEQ_DSNAME, 350, List.of());
            template.update("INSERT INTO \"" + TRXFL_SEQ_DSNAME + "\" VALUES (?)", (Object) null);
            JdbcDatasetUtilityPort port = new JdbcDatasetUtilityPort(template, ASCII,
                    RecordImageForm.CHARACTER, ORDINAL, unitOfWork());
            DatasetBinding binding = sequential(TRXFL_SEQ_DSNAME, 350, 3500);

            assertThatIllegalStateException()
                    .isThrownBy(() -> port.readAllRecordImages(binding))
                    .withMessageContaining("row 0");
        }
    }

    // ---------------------------------------------------------------------------------------------
    // THE RELATIONS THIS SUITE RUNS AGAINST, AND WHY NONE OF THEM IS CREATED.
    //
    // Gate G44 requires that no DDL, no schema migration, no entity annotation and no generated table
    // definition exist in this module - the migration must not invent a relational schema for datasets
    // that are VSAM clusters and sequential files - and this suite satisfies it literally rather than by
    // argument. A relation here is DECLARED to a RecordImageDataSource, which is a map from dataset name
    // to a list of record images. There is no schema, so no CREATE TABLE is executed, and there is
    // nothing that could be migrated.
    //
    // Only the storage engine is replaced. Everything above the driver is the shipped code path: the real
    // JdbcTemplate, the real JdbcDatasetUtilityPort, DatasetRelation's real composed statements,
    // RecordImageForm's real getString/getBytes choice, the real DatasetUnitOfWork and a real
    // JdbcTransactionManager. The store recognises exactly the statement shapes this module composes and
    // refuses anything else loudly, so a change to a composed statement fails here rather than returning a
    // plausible but different answer.
    // ---------------------------------------------------------------------------------------------

    /**
     * A private in-memory relation with one record-image column, seeded with the given rows.
     *
     * <p>One {@code VARCHAR} column of exactly the record width is the shape a gateway presenting a
     * fixed-width dataset is expected to offer, and it is the shape the JDBC-backed utility port is built
     * for. See the note above for why this test-only relation does not put the module in conflict with
     * gate G44.
     *
     * <p>The caller names the database, rather than a shared counter numbering it. That keeps this class
     * free of static mutable state (practice B9, gate G53) and keeps the suite deterministic (practice B7):
     * a counter would hand out different names depending on which tests ran first, whereas a caller-chosen
     * label is the same on every run. Each label is used by exactly one test, which is what keeps the
     * relations private to their tests.
     *
     * @param database     a label unique to the calling test, used as the database name
     * @param dsname       the dataset name, which is also the relation's delimited identifier
     * @param recordLength the record width
     * @param rows         the record images to insert, in order
     * @return a template over the seeded relation
     */
    private static JdbcTemplate seededRelation(String database, String dsname, int recordLength,
            List<String> rows) {
        Objects.requireNonNull(database, "A per-test store is labelled with the test that owns it");
        RecordImageDataSource backend = new RecordImageDataSource();
        backend.define(dsname, RecordImageDataSource.RECORD_IMAGE_COLUMN, ColumnForm.CHARACTER,
                recordLength);
        backend.store().seed(dsname, rows);
        return new JdbcTemplate(backend);
    }

    /**
     * The store behind a template built by {@link #seededRelation} or {@link #binaryRelation}.
     *
     * @param template the template
     * @return the relations it serves
     */
    private static RecordImageStore store(JdbcTemplate template) {
        return ((RecordImageDataSource) Objects.requireNonNull(template.getDataSource(),
                "A per-test template always has its store behind it")).store();
    }

    /**
     * Declares a further character relation on an existing template's store.
     *
     * @param template     the template whose store to extend
     * @param dsname       the dataset name
     * @param recordLength the record width
     */
    private static void declareRelation(JdbcTemplate template, String dsname, int recordLength) {
        store(template).define(dsname, RecordImageDataSource.RECORD_IMAGE_COLUMN,
                ColumnForm.CHARACTER, recordLength);
    }

    /**
     * A relation whose record image is a {@code VARBINARY} column - the shape a {@code BINARY} deployment
     * presents.
     *
     * @param database     a label unique to the calling test, used as the database name
     * @param dsname       the dataset name, which is also the relation's delimited identifier
     * @param recordLength the record width
     * @return a template over the empty relation
     */
    private static JdbcTemplate binaryRelation(String database, String dsname, int recordLength) {
        Objects.requireNonNull(database, "A per-test store is labelled with the test that owns it");
        RecordImageDataSource backend = new RecordImageDataSource();
        backend.define(dsname, RecordImageDataSource.RECORD_IMAGE_COLUMN, ColumnForm.BINARY,
                recordLength);
        return new JdbcTemplate(backend);
    }

    /**
     * What {@code SORT} and {@code REPRO} must not do to a record: change any byte of it, hold all of them
     * at once, or make a partial load atomic.
     *
     * <p>The three are one group because they are three properties of the same four utility steps, and
     * because two of them can only be observed against a driver rather than against an in-memory port.
     */
    @Nested
    @DisplayName("The utility steps move records whole, bounded, and with the JCL's failure semantics")
    class TheUtilityStepIntegrity {

        /** The EBCDIC code page a production deployment binds, where a lower-case letter is a high byte. */
        private static final Charset EBCDIC = Charset.forName("IBM037");

        @Test
        @DisplayName("a BINARY relation round-trips a record whose bytes are above 0x7F")
        void aBinaryRelationPreservesHighBytes() {
            // The reason this matters: SORT and REPRO transform nothing, so a record that passes through
            // either has to come out byte for byte. Reading a VARBINARY column with getString asks the
            // driver to apply ITS notion of a code page to bytes carrying a mainframe's - and under
            // IBM037 every lower-case letter, and every signed-overpunch digit, is above 0x7F.
            String dsname = "TEST.BINARY.TRXFL";
            JdbcTemplate template = binaryRelation("binary-round-trip", dsname, 80);
            JdbcDatasetUtilityPort port = new JdbcDatasetUtilityPort(template, EBCDIC,
                    RecordImageForm.BINARY, ORDINAL, unitOfWork());
            DatasetBinding binding = sequential(dsname, 80, 8000);
            String record = "abcdefghij" + " ".repeat(70);

            assertThat(port.writeRecordImages(binding, List.of(record))).isEqualTo(1);

            // The stored bytes are the dataset's own, unconverted: 'a' is 0x81 in IBM037.
            byte[] stored = store(template).rowBytes(dsname).get(0);
            assertThat(stored).hasSize(80);
            assertThat(stored[0]).isEqualTo((byte) 0x81);
            assertThat(stored).isEqualTo(record.getBytes(EBCDIC));

            // And the read decodes them in the same code page, so the round trip is the identity.
            assertThat(port.readAllRecordImages(binding)).containsExactly(record);
        }

        @Test
        @DisplayName("a BINARY copy moves every byte of every record unchanged")
        void aBinaryCopyPreservesEveryByte() {
            String source = "TEST.BINARY.SORTOUT";
            String target = "TEST.BINARY.WORKKSDS";
            JdbcTemplate template = binaryRelation("binary-copy", source, 80);
            store(template).define(target, RecordImageDataSource.RECORD_IMAGE_COLUMN,
                    ColumnForm.BINARY, 80);
            JdbcDatasetUtilityPort port = new JdbcDatasetUtilityPort(template, EBCDIC,
                    RecordImageForm.BINARY, ORDINAL, unitOfWork());
            DatasetBinding from = sequential(source, 80, 8000);
            DatasetBinding to = sequential(target, 80, 8000);
            List<String> records = List.of("aaa" + " ".repeat(77), "zzz" + " ".repeat(77));
            port.writeRecordImages(from, records);

            assertThat(port.copyRecordImages(from, to)).isEqualTo(2);

            // Asserted against the STORED bytes, not only against the read-back: a read and a write that
            // corrupt symmetrically would satisfy a round-trip comparison and satisfy nothing else.
            List<byte[]> stored = store(template).rowBytes(target);
            assertThat(stored).hasSize(2);
            assertThat(stored.get(0)).isEqualTo(records.get(0).getBytes(EBCDIC));
            assertThat(stored.get(1)).isEqualTo(records.get(1).getBytes(EBCDIC));
            assertThat(port.readAllRecordImages(to)).containsExactlyElementsOf(records);
        }

        @Test
        @DisplayName("STEP020 asks the port to COPY, not to read the whole file and then write it")
        void step020AsksForAStreamingCopy() {
            // The distinction is not stylistic. A read-then-write holds the source's whole contents and
            // fails with nothing loaded; a copy holds a bounded number of records and fails with
            // everything up to that record loaded, which is what an interrupted IDCAMS REPRO leaves.
            RecordingUtilityPort recorder = new RecordingUtilityPort();
            recorder.seed(TRXFL_SEQ_DSNAME, List.of("x".repeat(350)));
            List<String> operations = new ArrayList<>();
            DatasetUtilityPort port = new DatasetUtilityPort() {
                @Override
                public int deleteAllRecords(DatasetBinding binding) {
                    return recorder.deleteAllRecords(binding);
                }

                @Override
                public List<String> readAllRecordImages(DatasetBinding binding) {
                    operations.add("READ " + binding.dsname());
                    return recorder.readAllRecordImages(binding);
                }

                @Override
                public int writeRecordImages(DatasetBinding binding, List<String> recordImages) {
                    operations.add("WRITE " + binding.dsname());
                    return recorder.writeRecordImages(binding, recordImages);
                }

                @Override
                public int copyRecordImages(DatasetBinding source, DatasetBinding target) {
                    operations.add("COPY " + source.dsname() + " -> " + target.dsname());
                    return recorder.writeRecordImages(target, recorder.readAllRecordImages(source));
                }
            };
            assertThat(jobWithPort(port).reproSortedFileIntoWorkDataset()).isEqualTo(1);

            assertThat(operations)
                    .as("one COPY, and no READ of the whole file followed by a WRITE of it")
                    .containsExactly("COPY " + TRXFL_SEQ_DSNAME + " -> " + TRNXFILE_DSNAME);
        }

        @Test
        @DisplayName("the OUTREC reformat is applied per record, not into a second whole-file list")
        void theReformatIsAppliedPerRecord() {
            // A sort must see every key before it can place the first record, so the input is held. What
            // does not have to be held is a second, reformatted copy of all of it - so the reformat is a
            // view, applied as each record is consumed.
            StatementGenerationJobA subject =
                    Mockito.spy(harness(new ScriptedSubroutine()).job);
            List<String> input = new ArrayList<>(List.of(tranImage(CARD_A, "TRAN000000000002"),
                    tranImage(CARD_A, "TRAN000000000001")));

            List<String> reformatted = subject.sortAndReformat(input);

            Mockito.verify(subject, Mockito.never()).applyOutrec(Mockito.anyString());
            assertThat(reformatted).hasSize(2);
            assertThat(reformatted.get(0)).startsWith(CARD_A);
            // Consumed once, applied once per element.
            Mockito.verify(subject, Mockito.times(1)).applyOutrec(Mockito.anyString());
            assertThat(input)
                    .as("the caller's list is still its own, in its own order")
                    .containsExactly(tranImage(CARD_A, "TRAN000000000002"),
                            tranImage(CARD_A, "TRAN000000000001"));
        }

        @Test
        @DisplayName("a REPRO that fails part way leaves what it loaded - DISP=SHR on OUTFILE")
        void aFailedCopyLeavesWhatItLoaded() {
            // app/jcl/CREASTMT.JCL:59 binds OUTFILE with DISP=SHR over the cluster DELDEF01 defined: an
            // existing dataset with no abnormal disposition to discard it. IDCAMS REPRO is not atomic
            // against it, so neither is this. Contrast SORTOUT at :48-49, which is
            // DISP=(NEW,CATLG,DELETE) and IS discarded whole.
            String source = "TEST.REPRO.SOURCE";
            String target = "TEST.REPRO.TARGET";
            JdbcTemplate template = seededRelation("repro-partial-load", source, 80, List.of());
            store(template).defineUnique(target, RecordImageDataSource.RECORD_IMAGE_COLUMN,
                    ColumnForm.CHARACTER, 80);
            JdbcDatasetUtilityPort port = new JdbcDatasetUtilityPort(template, ASCII,
                    RecordImageForm.CHARACTER, ORDINAL, new DatasetUnitOfWork(
                            new JdbcTransactionManager(template.getDataSource())));
            DatasetBinding from = sequential(source, 80, 8000);
            DatasetBinding to = sequential(target, 80, 8000);
            // The third record repeats the first, so the target's key refuses it mid-copy.
            port.writeRecordImages(from, List.of(record80("A"), record80("B"), record80("A")));

            assertThatExceptionOfType(DataAccessException.class)
                    .isThrownBy(() -> port.copyRecordImages(from, to));

            assertThat(store(template).rows(target))
                    .as("the records already REPROed stay loaded, as an interrupted IDCAMS leaves them")
                    .containsExactlyInAnyOrder(record80("A"), record80("B"));
        }

        @Test
        @DisplayName("each copied record survives a rollback of the step's own transaction")
        void copiedRecordsSurviveAnEnclosingRollback() {
            String source = "TEST.REPRO.SRC2";
            String target = "TEST.REPRO.TGT2";
            JdbcTemplate template = seededRelation("repro-rollback", source, 80, List.of());
            declareRelation(template, target, 80);
            DatasetUnitOfWork boundary = new DatasetUnitOfWork(
                    new JdbcTransactionManager(template.getDataSource()));
            JdbcDatasetUtilityPort port = new JdbcDatasetUtilityPort(template, ASCII,
                    RecordImageForm.CHARACTER, ORDINAL, boundary);
            DatasetBinding from = sequential(source, 80, 8000);
            DatasetBinding to = sequential(target, 80, 8000);
            port.writeRecordImages(from, List.of(record80("A"), record80("B")));
            TransactionTemplate step = new TransactionTemplate(
                    new JdbcTransactionManager(template.getDataSource()));

            // The enclosing template stands for the tasklet's transaction, and the exception for the
            // step failing after the copy had run.
            assertThatIllegalStateException().isThrownBy(() -> step.execute(status -> {
                port.copyRecordImages(from, to);
                throw new IllegalStateException("the step fails after the REPRO");
            }));

            assertThat(store(template).rows(target))
                    .as("a partial KSDS load is what DISP=SHR leaves; a rollback would erase it")
                    .containsExactlyInAnyOrder(record80("A"), record80("B"));
        }

        @Test
        @DisplayName("a port wired WITHOUT a per-record boundary still copies every record, in order - the "
                + "destination's own disposition is what discards a partial load then")
        void aCopyWithNoPerRecordBoundary() {
            // JdbcDatasetUtilityPort has two shapes: one given a DatasetUnitOfWork, which makes each
            // REPROed record durable on its own, and one given none, for a destination whose own
            // disposition discards the whole generation on failure - app/jcl/CREASTMT.JCL:L58-L59 declares
            // DISP=SHR on both of STEP020's datasets, whereas L48-L49 declares DISP=(NEW,CATLG,DELETE) on
            // SORTOUT. Both shapes have to move the same records in the same order, and the boundary-free
            // one is the arm the other tests in this class never take.
            String source = "TEST.REPRO.SRC3";
            String target = "TEST.REPRO.TGT3";
            JdbcTemplate template = seededRelation("repro-unbounded", source, 80, List.of());
            declareRelation(template, target, 80);
            JdbcDatasetUtilityPort unbounded = new JdbcDatasetUtilityPort(template, ASCII,
                    RecordImageForm.CHARACTER, ORDINAL);
            DatasetBinding from = sequential(source, 80, 8000);
            DatasetBinding to = sequential(target, 80, 8000);
            unbounded.writeRecordImages(from, List.of(record80("A"), record80("B"), record80("C")));

            assertThat(unbounded.copyRecordImages(from, to)).isEqualTo(3);

            // Record for record AND in order, which is the whole reason the port takes a physical-record
            // ordinal: SQL returns rows in no order unless one is asked for.
            assertThat(store(template).rows(target))
                    .containsExactly(record80("A"), record80("B"), record80("C"));
        }

        /** An 80-byte record whose first character distinguishes it. */
        private String record80(String lead) {
            return lead + " ".repeat(79);
        }
    }

    /** The prologue's substitute types. */
    @Nested
    @DisplayName("The TIOT substitute records")
    class TheTiotRecords {

        @Test
        @DisplayName("a DD name is fitted to PIC X(08): padded when short, truncated when long")
        void ddNamesAreFitted() {
            assertThat(new TiotEntry("SYSOUT", true).ddName()).isEqualTo("SYSOUT  ");
            assertThat(new TiotEntry("VERYLONGNAME", false).ddName()).isEqualTo("VERYLONG");
            assertThat(new TiotEntry("TRNXFILE", true).ddName()).isEqualTo("TRNXFILE");
        }

        @Test
        @DisplayName("a TIOT entry requires a name; the chain terminator is its own factory")
        void aTiotEntryRequiresAName() {
            assertThatNullPointerException().isThrownBy(() -> new TiotEntry(null, true));
            assertThat(TiotEntry.terminator().ddName()).isEqualTo("        ");
            assertThat(TiotEntry.terminator().validUcb()).isFalse();
        }

        @Test
        @DisplayName("both PIC X(08) names on the image are fitted and the entry list is copied")
        void theImageIsFittedAndCopied() {
            List<TiotEntry> mutable = new ArrayList<>(List.of(new TiotEntry("A", true)));
            TiotImage image = new TiotImage("JOB", "S", mutable, TiotEntry.terminator());
            mutable.clear();

            assertThat(image.jobName()).isEqualTo("JOB     ");
            assertThat(image.stepName()).isEqualTo("S       ");
            assertThat(image.entries()).hasSize(1);
            assertThat(new TiotImage("VERYLONGJOBNAME", "VERYLONGSTEPNAME", List.of(),
                    TiotEntry.terminator()).jobName()).isEqualTo("VERYLONG");
        }

        @Test
        @DisplayName("every component of the image is required")
        void everyComponentIsRequired() {
            assertThatNullPointerException().isThrownBy(
                    () -> new TiotImage(null, "S", List.of(), TiotEntry.terminator()));
            assertThatNullPointerException().isThrownBy(
                    () -> new TiotImage("J", null, List.of(), TiotEntry.terminator()));
            assertThatNullPointerException()
                    .isThrownBy(() -> new TiotImage("J", "S", null, TiotEntry.terminator()));
            assertThatNullPointerException()
                    .isThrownBy(() -> new TiotImage("J", "S", List.of(), null));
        }

        @Test
        @DisplayName("BUMP-TIOT's two lengths are LENGTH OF TIOT-BLOCK and LENGTH OF TIOT-SEG")
        void theTwoBumpLengths() {
            assertThat(StatementGenerationJobA.TIOT_BLOCK_LENGTH).isEqualTo(24);
            assertThat(StatementGenerationJobA.TIOT_SEG_LENGTH).isEqualTo(20);
            assertThat(StatementGenerationJobA.TIOT_NAME_WIDTH).isEqualTo(8);
        }

        @Test
        @DisplayName("the TIOT-INDEX REDEFINES BUMP-TIOT pair survives as one backing value read two "
                + "ways, which is all of it that translates (CBSTM03A.CBL:L236-L237, gate G34)")
        void theRedefinesPairIsNotSilentlyDropped() {
            // L236  01  BUMP-TIOT   PIC S9(08) BINARY VALUE ZERO.
            // L237  01  TIOT-INDEX  REDEFINES BUMP-TIOT POINTER.
            //
            // This is the program's one REDEFINES, and it is the one kind that CANNOT be reproduced
            // literally. The two views are not two encodings of a value - they are an arithmetic view and a
            // MACHINE ADDRESS view of the same four bytes, and the walk works only because adding 24 to the
            // integer view moves the pointer view 24 bytes further into a z/OS Task Input/Output Table:
            //   L268  SET ADDRESS OF TIOT-BLOCK TO TIOT-POINT
            //   L269  SET TIOT-INDEX            TO TIOT-POINT     <- writes through the POINTER view
            //   L272  COMPUTE BUMP-TIOT = BUMP-TIOT + LENGTH OF TIOT-BLOCK  <- reads the BINARY view
            //   L273  SET ADDRESS OF TIOT-ENTRY TO TIOT-INDEX      <- reads the POINTER view again
            // There is no TIOT off z/OS and the JVM exposes no addresses, so a Java field pair cannot be
            // laid over one another and mean anything. The substitute keeps what is observable: ONE
            // accumulator, advanced by exactly the lengths the COMPUTEs add, driving a walk over a supplied
            // image. That is asserted here rather than dropped - the pair's arithmetic is the only part of
            // it the program's output depends on, and the displayed DD sequence is the only part a reader
            // of SYSOUT could ever have seen.
            Harness built = harness(oneStatement(),
                    tiot(new TiotEntry("DD000001", true), new TiotEntry("DD000002", false),
                            new TiotEntry("DD000003", true)));

            built.run();

            // The one backing value, read as an integer: 24 for the block, then 20 per segment. Written
            // through what the COBOL writes through, read back through what it reads back through.
            WorkingStorage storage = newWorkingStorage();
            assertThat(storage.bumpTiot()).isZero();
            storage.addToBumpTiot(StatementGenerationJobA.TIOT_BLOCK_LENGTH);
            assertThat(storage.bumpTiot()).isEqualTo(24);
            for (int segment = 1; segment <= 3; segment++) {
                storage.addToBumpTiot(StatementGenerationJobA.TIOT_SEG_LENGTH);
                assertThat(storage.bumpTiot()).isEqualTo(24 + 20 * segment);
            }

            // And the walk that offset drives visited all three entries, in order, on the arm each one's
            // UCB selects - the observable consequence of the pointer view, which is what is preserved.
            assertThat(built.sysout.lines()).containsSubsequence(
                    StatementGenerationJobA.DD_NAMES_FROM_TIOT,
                    StatementGenerationJobA.TIOT_ENTRY_PREFIX + "DD000001"
                            + StatementGenerationJobA.VALID_UCB_SUFFIX,
                    StatementGenerationJobA.TIOT_ENTRY_PREFIX + "DD000002"
                            + StatementGenerationJobA.NULL_UCB_SUFFIX_IN_LOOP,
                    StatementGenerationJobA.TIOT_ENTRY_PREFIX + "DD000003"
                            + StatementGenerationJobA.VALID_UCB_SUFFIX);
        }
    }

    // =============================================================================================
    // The control-block prologue, app/cbl/CBSTM03A.CBL:L266-L294.
    // =============================================================================================

    /** The five {@code DISPLAY} forms, in order, byte for byte. */
    @Nested
    @DisplayName("The control-block prologue")
    class ThePrologue {

        @Test
        @DisplayName("the five displays are emitted in order, with the job and step names at PIC X(08)")
        void theFiveDisplays() {
            Harness built = new Harness(new ScriptedSubroutine(), tiot(
                    new TiotEntry("TRNXFILE", true), new TiotEntry("SYSOUT", false)), jobContracts(),
                    new RecordingUtilityPort());

            assertThatExceptionOfType(AbendException.class).isThrownBy(built::run);

            assertThat(built.sysout.lines().subList(0, 5)).containsExactly(
                    "Running JCL : CREASTMT Step STEP040 ",
                    "DD Names from TIOT: ",
                    ": TRNXFILE -- valid UCB",
                    ": SYSOUT   --  null UCB",
                    ":          -- null  UCB");
        }

        @Test
        @DisplayName("the in-loop and post-loop null-UCB literals are DIFFERENT strings")
        void theTwoNullUcbLiteralsDiffer() {
            assertThat(StatementGenerationJobA.NULL_UCB_SUFFIX_IN_LOOP)
                    .isEqualTo(" --  null UCB")
                    .isNotEqualTo(StatementGenerationJobA.NULL_UCB_SUFFIX_AFTER_LOOP);
            assertThat(StatementGenerationJobA.NULL_UCB_SUFFIX_AFTER_LOOP).isEqualTo(" -- null  UCB");
            assertThat(StatementGenerationJobA.VALID_UCB_SUFFIX).isEqualTo(" -- valid UCB");
            assertThat(StatementGenerationJobA.DD_NAMES_FROM_TIOT).isEqualTo("DD Names from TIOT: ")
                    .endsWith(" ");
            assertThat(StatementGenerationJobA.RUNNING_JCL_PREFIX).isEqualTo("Running JCL : ");
            assertThat(StatementGenerationJobA.RUNNING_JCL_STEP_LABEL).isEqualTo(" Step ");
            assertThat(StatementGenerationJobA.TIOT_ENTRY_PREFIX).isEqualTo(": ");
        }

        @Test
        @DisplayName("a valid UCB after the loop takes the other arm of the post-loop test")
        void aValidUcbAfterTheLoop() {
            TiotImage image = new TiotImage("CREASTMT", "STEP040", List.of(),
                    new TiotEntry("SYSPRINT", true));
            Harness built = new Harness(new ScriptedSubroutine(), () -> image, jobContracts(),
                    new RecordingUtilityPort());

            assertThatExceptionOfType(AbendException.class).isThrownBy(built::run);

            assertThat(built.sysout.lines().subList(0, 3)).containsExactly(
                    "Running JCL : CREASTMT Step STEP040 ",
                    "DD Names from TIOT: ",
                    ": SYSPRINT -- valid UCB");
        }

        @Test
        @DisplayName("an address space with no entries still emits the heading and the terminator line")
        void noEntriesAtAll() {
            Harness built = new Harness(new ScriptedSubroutine(), tiot(), jobContracts(),
                    new RecordingUtilityPort());

            assertThatExceptionOfType(AbendException.class).isThrownBy(built::run);

            assertThat(built.sysout.lines().subList(0, 3)).containsExactly(
                    "Running JCL : CREASTMT Step STEP040 ",
                    "DD Names from TIOT: ",
                    ":          -- null  UCB");
        }

        @Test
        @DisplayName("BUMP-TIOT holds 24 + 20n after the walk over n entries")
        void bumpTiotArithmetic() {
            WorkingStorage storage = newWorkingStorage();

            assertThat(storage.bumpTiot()).isZero();
            storage.addToBumpTiot(StatementGenerationJobA.TIOT_BLOCK_LENGTH);
            assertThat(storage.bumpTiot()).isEqualTo(24);
            storage.addToBumpTiot(StatementGenerationJobA.TIOT_SEG_LENGTH);
            storage.addToBumpTiot(StatementGenerationJobA.TIOT_SEG_LENGTH);
            assertThat(storage.bumpTiot()).isEqualTo(24 + 2 * 20);
        }

        @Test
        @DisplayName("OPEN OUTPUT opens the plain-text statement and the HTML statement, in that order")
        void openOutputOpensBoth() {
            Harness built = harness(oneStatement());

            built.run();

            Mockito.verify(built.textWriter).openOutput();
            Mockito.verify(built.htmlWriter).open();
            assertThat(built.textRecords).isNotEmpty();
            assertThat(built.htmlRecords).isNotEmpty();
        }

        @Test
        @DisplayName("INITIALIZE clears the whole 51x10 table and all 51 counters")
        void initializeClearsTheTable() {
            WorkingStorage storage = newWorkingStorage();
            storage.table().setCardNum(1, CARD_A);
            storage.table().setTranNum(1, 1, "TRAN000000000001");
            storage.table().setTranRest(1, 1, "REST");
            storage.table().setTrct(1, 7);

            storage.initializeTrnxTable();

            assertThat(storage.table().cardNum(1)).isBlank();
            assertThat(storage.table().tranNum(1, 1)).isBlank();
            assertThat(storage.table().tranRest(1, 1)).isBlank();
            assertThat(storage.table().trct(1)).isZero();
            assertThat(storage.table().trct(StatementGenerationJobA.CARD_TABLE_OCCURS)).isZero();
        }
    }

    // =============================================================================================
    // 0000-START and 8100-FILE-OPEN - the ALTER/GO TO dispatch (gate G32).
    // =============================================================================================

    /** The restructured {@code ALTER ... TO PROCEED TO} state machine. */
    @Nested
    @DisplayName("The ALTER/GO TO dispatch, restructured (gate G32)")
    class TheFileControlStateMachine {

        @Test
        @DisplayName("the resolved order is ASSERTED, not assumed: TRNX open, table load, XREF, CUST, ACCT "
                + "- the four GO TO 0000-START at L761, L852, L780, L798 then GO TO 1000-MAINLINE at L815 "
                + "(gate G32)")
        void theResolvedOrderIsAsserted() {
            Harness built = harness(oneStatement());

            int statements = built.run();

            assertThat(statements).isEqualTo(1);
            assertThat(built.subroutine.calls()).containsExactly(
                    // 8100-TRNXFILE-OPEN: open, then the first read, then GO TO 0000-START.  L731-L761
                    "OPEN TRNXFILE",
                    "READ TRNXFILE",
                    // 8500-READTRNX-READ: the loop read whose EVALUATE reports '10'.    L835-L847
                    "READ TRNXFILE",
                    // 8599-EXIT -> WS-FL-DD = 'XREFFILE' -> 8200-XREFFILE-OPEN.        L851-L852
                    "OPEN XREFFILE",
                    // 8200 -> 'CUSTFILE' -> 8300-CUSTFILE-OPEN.                        L779-L780
                    "OPEN CUSTFILE",
                    // 8300 -> 'ACCTFILE' -> 8400-ACCTFILE-OPEN.                        L797-L798
                    "OPEN ACCTFILE",
                    // 8400 -> GO TO 1000-MAINLINE, WS-FL-DD untouched.                      L815
                    "READ XREFFILE",
                    "READK CUSTFILE",
                    "READK ACCTFILE",
                    "READ XREFFILE",
                    // 1000-MAINLINE's four closes, in order.                          L331-L337
                    "CLOSE TRNXFILE",
                    "CLOSE XREFFILE",
                    "CLOSE CUSTFILE",
                    "CLOSE ACCTFILE");
        }

        @Test
        @DisplayName("the table is loaded BEFORE the cross-reference file is opened")
        void theTableIsLoadedBeforeTheXrefOpen() {
            Harness built = harness(new ScriptedSubroutine().reads(
                    StatementGenerationJobA.TRNXFILE_DD,
                    ok(trnxImage(CARD_A, "TRAN000000000001", "ONE", "1.00")),
                    ok(trnxImage(CARD_A, "TRAN000000000002", "TWO", "2.00")),
                    ok(trnxImage(CARD_B, "TRAN000000000003", "THREE", "3.00")), eof()));

            built.run();

            List<String> calls = built.subroutine.calls();
            assertThat(calls.subList(0, 6)).containsExactly("OPEN TRNXFILE", "READ TRNXFILE",
                    "READ TRNXFILE", "READ TRNXFILE", "READ TRNXFILE", "OPEN XREFFILE");
        }

        @Test
        @DisplayName("WHEN OTHER goes straight to 9999-GOBACK: no open, no read, no close, no statement")
        void whenOtherIsReachableAndBypassesEverything() {
            Harness built = harness(new ScriptedSubroutine());
            WorkingStorage storage = newWorkingStorage();
            storage.moveToWsFlDd("SYSPRINT");

            // WS-FL-DD is initialised to 'TRNXFILE' and every transition moves one of the five
            // recognised names into it, so this arm cannot be reached from the top of the program. The
            // dispatch is entered directly with an unrecognised state to prove it behaves as
            // GO TO 9999-GOBACK does: no open, no read, no close, and no statement.
            boolean reachedMainline = built.job.runFileControl(storage, built.sysout);

            assertThat(reachedMainline).isFalse();
            assertThat(storage.wsFlDd()).isEqualTo("SYSPRINT");
            assertThat(StatementGenerationJobA.FILE_CONTROL_STATES).doesNotContain("SYSPRINT");
            assertThat(built.subroutine.calls()).isEmpty();
            assertThat(built.sysout.lines()).isEmpty();
            assertThat(built.textRecords).isEmpty();
            assertThat(built.htmlRecords).isEmpty();
        }

        @Test
        @DisplayName("each of the five recognised arms is taken in turn when the dispatch is entered on it")
        void eachRecognisedArmIsTaken() {
            Harness built = harness(new ScriptedSubroutine()
                    .reads(StatementGenerationJobA.TRNXFILE_DD, eof()));
            WorkingStorage storage = newWorkingStorage();
            storage.moveToWsFlDd(StatementGenerationJobA.STATE_ACCTFILE);

            // Entering on the fourth arm opens the account file and branches to 1000-MAINLINE without
            // touching the other three, which is exactly what 8400-ACCTFILE-OPEN does at L815.
            assertThat(built.job.runFileControl(storage, built.sysout)).isTrue();
            assertThat(built.subroutine.calls()).containsExactly("OPEN ACCTFILE");
            assertThat(storage.wsFlDd()).isEqualTo(StatementGenerationJobA.STATE_ACCTFILE);
        }

        @Test
        @DisplayName("entering on the cross-reference arm walks CUSTFILE and ACCTFILE and then stops")
        void enteringOnTheXrefArm() {
            Harness built = harness(new ScriptedSubroutine());
            WorkingStorage storage = newWorkingStorage();
            storage.moveToWsFlDd(StatementGenerationJobA.STATE_XREFFILE);

            assertThat(built.job.runFileControl(storage, built.sysout)).isTrue();
            assertThat(built.subroutine.calls()).containsExactly("OPEN XREFFILE", "OPEN CUSTFILE",
                    "OPEN ACCTFILE");
        }

        @Test
        @DisplayName("WS-FL-DD starts at 'TRNXFILE', which is why the first arm is the one taken")
        void theInitialState() {
            assertThat(newWorkingStorage().wsFlDd())
                    .isEqualTo(StatementGenerationJobA.STATE_TRNXFILE);
            assertThat(StatementGenerationJobA.FILE_CONTROL_STATES.get(0))
                    .isEqualTo(StatementGenerationJobA.STATE_TRNXFILE);
        }

        @Test
        @DisplayName("the EVALUATE arms are in source order, with the five recognised states first")
        void theEvaluateArmsAreInSourceOrder() {
            assertThat(StatementGenerationJobA.FILE_CONTROL_STATES).containsExactly(
                    StatementGenerationJobA.STATE_TRNXFILE,
                    StatementGenerationJobA.STATE_XREFFILE,
                    StatementGenerationJobA.STATE_CUSTFILE,
                    StatementGenerationJobA.STATE_ACCTFILE,
                    StatementGenerationJobA.STATE_READTRNX);
        }

        @Test
        @DisplayName("an empty cross-reference file writes no statement but still closes all four files")
        void anEmptyCrossReference() {
            Harness built = harness(new ScriptedSubroutine().reads(
                    StatementGenerationJobA.TRNXFILE_DD,
                    ok(trnxImage(CARD_A, "TRAN000000000001", "ONE", "1.00")), eof()));

            int statements = built.run();

            assertThat(statements).isZero();
            assertThat(built.textRecords).isEmpty();
            assertThat(built.htmlRecords).isEmpty();
            assertThat(built.subroutine.calls()).endsWith("CLOSE TRNXFILE", "CLOSE XREFFILE",
                    "CLOSE CUSTFILE", "CLOSE ACCTFILE");
        }
    }

    /**
     * A working-storage instance with a real session and a named code page.
     *
     * @return the instance
     */
    private static WorkingStorage newWorkingStorage() {
        return new WorkingStorage(new FixedWidthCodec(ASCII), new ScriptedSubroutine().newSession());
    }

    /**
     * How many transaction detail lines a run emitted.
     *
     * <p>{@code ST-LINE14} is the only plain-text line that carries {@code ST-TRANID}, so counting the
     * records that hold a transaction identifier counts the detail lines.
     *
     * @param built the harness after a run
     * @return the count
     */
    private static long detailLines(Harness built) {
        return built.textRecords.stream().filter(record -> record.contains("TRAN00000000")).count();
    }

    /**
     * How many of the given records begin with the given text.
     *
     * @param records the emitted records
     * @param prefix  the text to look for at the start of a record
     * @return the count
     */
    private static long startingWith(List<String> records, String prefix) {
        return records.stream().filter(record -> record.startsWith(prefix)).count();
    }

    /**
     * How many of the given records contain the given text anywhere.
     *
     * @param records the emitted records
     * @param text    the text to look for
     * @return the count
     */
    private static long containing(List<String> records, String text) {
        return records.stream().filter(record -> record.contains(text)).count();
    }

    // =============================================================================================
    // 8500-READTRNX-READ and 8599-EXIT - the 51 x 10 table load (gate G33).
    // =============================================================================================

    /** The two-dimensional table, its 1-based subscripts and the card-break grouping. */
    @Nested
    @DisplayName("The 51 x 10 table and its card-break grouping (gate G33)")
    class TheTableLoad {

        @Test
        @DisplayName("the declared dimensions are OCCURS 51 and OCCURS 10")
        void theDeclaredDimensions() {
            assertThat(StatementGenerationJobA.CARD_TABLE_OCCURS).isEqualTo(51);
            assertThat(StatementGenerationJobA.TRAN_TABLE_OCCURS).isEqualTo(10);
            assertThat(StatementGenerationJobA.WS_CARD_NUM_LENGTH).isEqualTo(16);
            assertThat(StatementGenerationJobA.WS_TRAN_NUM_LENGTH).isEqualTo(16);
            assertThat(StatementGenerationJobA.WS_TRAN_REST_LENGTH).isEqualTo(318);
            assertThat(StatementGenerationJobA.WS_SAVE_CARD_LENGTH).isEqualTo(16);
        }

        @Test
        @DisplayName("the FIRST element - card 1, transaction 1 - stores and retrieves")
        void theFirstElement() {
            TrnxTable table = new TrnxTable();

            table.setCardNum(1, CARD_A);
            table.setTranNum(1, 1, "TRAN000000000001");
            table.setTranRest(1, 1, "FIRST");
            table.setTrct(1, 1);

            assertThat(table.cardNum(1)).isEqualTo(CARD_A);
            assertThat(table.tranNum(1, 1)).isEqualTo("TRAN000000000001");
            assertThat(table.tranRest(1, 1)).isEqualTo("FIRST");
            assertThat(table.trct(1)).isEqualTo(1);
        }

        @Test
        @DisplayName("the LAST element - card 51, transaction 10 - stores and retrieves")
        void theLastElement() {
            TrnxTable table = new TrnxTable();

            table.setCardNum(51, CARD_C);
            table.setTranNum(51, 10, "TRAN000000000510");
            table.setTranRest(51, 10, "LAST");
            table.setTrct(51, 10);

            assertThat(table.cardNum(51)).isEqualTo(CARD_C);
            assertThat(table.tranNum(51, 10)).isEqualTo("TRAN000000000510");
            assertThat(table.tranRest(51, 10)).isEqualTo("LAST");
            assertThat(table.trct(51)).isEqualTo(10);
        }

        @ParameterizedTest
        @DisplayName("a card subscript outside 1..51 is refused, so no off-by-one goes unnoticed")
        @ValueSource(ints = {-1, 0, 52, 100})
        void anOutOfRangeCardSubscript(int card) {
            TrnxTable table = new TrnxTable();

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> table.cardNum(card))
                    .withMessageContaining("OCCURS 51");
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> table.setTrct(card, 1));
        }

        @ParameterizedTest
        @DisplayName("a transaction subscript outside 1..10 is refused")
        @ValueSource(ints = {-1, 0, 11, 99})
        void anOutOfRangeTransactionSubscript(int tran) {
            TrnxTable table = new TrnxTable();

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> table.tranNum(1, tran))
                    .withMessageContaining("OCCURS 10");
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> table.tranRest(1, tran));
        }

        @Test
        @DisplayName("a fresh table is blank everywhere and every counter is zero")
        void aFreshTableIsBlank() {
            TrnxTable table = new TrnxTable();

            assertThat(table.cardNum(1)).isEqualTo(" ".repeat(16));
            assertThat(table.cardNum(51)).isEqualTo(" ".repeat(16));
            assertThat(table.tranNum(1, 1)).isEqualTo(" ".repeat(16));
            assertThat(table.tranNum(51, 10)).isEqualTo(" ".repeat(16));
            assertThat(table.tranRest(1, 1)).isEqualTo(" ".repeat(318));
            assertThat(table.trct(1)).isZero();
            assertThat(table.trct(51)).isZero();
        }

        @Test
        @DisplayName("every setter refuses null: a COBOL table slot always holds characters")
        void settersRefuseNull() {
            TrnxTable table = new TrnxTable();

            assertThatNullPointerException().isThrownBy(() -> table.setCardNum(1, null));
            assertThatNullPointerException().isThrownBy(() -> table.setTranNum(1, 1, null));
            assertThatNullPointerException().isThrownBy(() -> table.setTranRest(1, 1, null));
        }

        @Test
        @DisplayName("one card with one transaction: TR-CNT reaches 1 on the very first pass")
        void oneCardOneTransaction() {
            Harness built = harness(oneStatement());

            built.run();

            assertThat(detailLines(built)).isEqualTo(1);
        }

        @Test
        @DisplayName("one card with ten transactions fills the inner table to its declared capacity")
        void oneCardTenTransactions() {
            ScriptedSubroutine subroutine = new ScriptedSubroutine();
            Response[] reads = new Response[11];
            for (int index = 0; index < 10; index++) {
                reads[index] = ok(trnxImage(CARD_A, String.format("TRAN%012d", index + 1),
                        "PURCHASE " + (index + 1), "10.00"));
            }
            reads[10] = eof();
            subroutine.reads(StatementGenerationJobA.TRNXFILE_DD, reads)
                    .reads(StatementGenerationJobA.XREFFILE_DD, ok(xrefImage(CARD_A, CUST_ID, ACCT_ID)),
                            eof())
                    .keyed(StatementGenerationJobA.CUSTFILE_DD, ok(defaultCustImage()))
                    .keyed(StatementGenerationJobA.ACCTFILE_DD, ok(defaultAcctImage()));
            Harness built = harness(subroutine);

            built.run();

            assertThat(detailLines(built)).isEqualTo(10);
        }

        @Test
        @DisplayName("two cards: a statement carries only its own card's transactions")
        void twoCards() {
            ScriptedSubroutine subroutine = new ScriptedSubroutine()
                    .reads(StatementGenerationJobA.TRNXFILE_DD,
                            ok(trnxImage(CARD_A, "TRAN000000000001", "CARD A ONE", "1.00")),
                            ok(trnxImage(CARD_A, "TRAN000000000002", "CARD A TWO", "2.00")),
                            ok(trnxImage(CARD_B, "TRAN000000000003", "CARD B ONE", "3.00")), eof())
                    .reads(StatementGenerationJobA.XREFFILE_DD,
                            ok(xrefImage(CARD_B, CUST_ID, ACCT_ID)), eof())
                    .keyed(StatementGenerationJobA.CUSTFILE_DD, ok(defaultCustImage()))
                    .keyed(StatementGenerationJobA.ACCTFILE_DD, ok(defaultAcctImage()));
            Harness built = harness(subroutine);

            built.run();

            assertThat(detailLines(built)).isEqualTo(1);
            assertThat(built.textRecords.stream().filter(r -> r.contains("CARD B ONE")).count())
                    .isEqualTo(1);
            assertThat(built.textRecords.stream().filter(r -> r.contains("CARD A")).count()).isZero();
        }

        @Test
        @DisplayName("a card break on the very first record still counts the first card correctly")
        void aBreakAtTheFirstRecord() {
            ScriptedSubroutine subroutine = new ScriptedSubroutine()
                    .reads(StatementGenerationJobA.TRNXFILE_DD,
                            ok(trnxImage(CARD_A, "TRAN000000000001", "ONLY A", "1.00")),
                            ok(trnxImage(CARD_B, "TRAN000000000002", "FIRST B", "2.00")),
                            ok(trnxImage(CARD_B, "TRAN000000000003", "SECOND B", "3.00")), eof())
                    .reads(StatementGenerationJobA.XREFFILE_DD,
                            ok(xrefImage(CARD_A, CUST_ID, ACCT_ID)), eof())
                    .keyed(StatementGenerationJobA.CUSTFILE_DD, ok(defaultCustImage()))
                    .keyed(StatementGenerationJobA.ACCTFILE_DD, ok(defaultAcctImage()));
            Harness built = harness(subroutine);

            built.run();

            assertThat(detailLines(built)).isEqualTo(1);
            assertThat(built.textRecords.stream().filter(r -> r.contains("ONLY A")).count())
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("8599-EXIT records the FINAL card's count, which the loop never closed "
                + "(CBSTM03A.CBL:L842, L849-L852)")
        void theFinalCardsCountIsRecorded() {
            ScriptedSubroutine subroutine = new ScriptedSubroutine()
                    .reads(StatementGenerationJobA.TRNXFILE_DD,
                            ok(trnxImage(CARD_A, "TRAN000000000001", "A ONE", "1.00")),
                            ok(trnxImage(CARD_B, "TRAN000000000002", "B ONE", "2.00")),
                            ok(trnxImage(CARD_B, "TRAN000000000003", "B TWO", "3.00")),
                            ok(trnxImage(CARD_B, "TRAN000000000004", "B THREE", "4.00")), eof())
                    .reads(StatementGenerationJobA.XREFFILE_DD,
                            ok(xrefImage(CARD_B, CUST_ID, ACCT_ID)), eof())
                    .keyed(StatementGenerationJobA.CUSTFILE_DD, ok(defaultCustImage()))
                    .keyed(StatementGenerationJobA.ACCTFILE_DD, ok(defaultAcctImage()));
            Harness built = harness(subroutine);

            built.run();

            // Without the 8599-EXIT assignment the last card's WS-TRCT would still be zero and none of
            // its three transactions would appear.
            assertThat(detailLines(built)).isEqualTo(3);
        }
    }

    // =============================================================================================
    // Bounded cancellation - both record loops yield to a stop request.
    // =============================================================================================

    /** The two loops a stop can land between: the table load, and the statement mainline. */
    @Nested
    @DisplayName("Bounded cancellation - the table load and the mainline both yield between records")
    class BoundedCancellation {

        /**
         * A subroutine scripted for two complete statements over two cards.
         *
         * @return the scripted subroutine
         */
        private ScriptedSubroutine twoCardsTwoStatements() {
            return new ScriptedSubroutine()
                    .reads(StatementGenerationJobA.TRNXFILE_DD,
                            ok(trnxImage(CARD_A, "TRAN000000000001", "A ONE", "1.00")),
                            ok(trnxImage(CARD_B, "TRAN000000000002", "B ONE", "2.00")), eof())
                    .reads(StatementGenerationJobA.XREFFILE_DD,
                            ok(xrefImage(CARD_A, CUST_ID, ACCT_ID)),
                            ok(xrefImage(CARD_B, CUST_ID, ACCT_ID)), eof())
                    .keyed(StatementGenerationJobA.CUSTFILE_DD, ok(defaultCustImage()),
                            ok(defaultCustImage()))
                    .keyed(StatementGenerationJobA.ACCTFILE_DD, ok(defaultAcctImage()),
                            ok(defaultAcctImage()));
        }

        @Test
        @DisplayName("no stop requested leaves the pass exactly as it was: two statements, unchanged")
        void withoutAStopTheWholePassRuns() {
            Harness withSignal = harness(twoCardsTwoStatements());
            Harness withoutSignal = harness(twoCardsTwoStatements());

            int unbounded = withoutSignal.job.printAccountStatements(withoutSignal.sysout);
            int bounded = withSignal.job.printAccountStatements(withSignal.sysout,
                    StopSignal.of(stepExecution()));

            // The signal is consulted on every iteration of both loops and changes nothing while nothing
            // is pending, which is what makes the probe additive rather than a change to the pass.
            assertThat(bounded).isEqualTo(unbounded).isEqualTo(2);
            assertThat(withSignal.textRecords).isEqualTo(withoutSignal.textRecords);
            assertThat(withSignal.htmlRecords).isEqualTo(withoutSignal.htmlRecords);
        }

        @Test
        @DisplayName("a stop before the table load ends the pass with no statement written")
        void aStopBeforeTheTableLoadWritesNoStatement() {
            StepExecution stepExecution = stepExecution();
            stepExecution.setTerminateOnly();
            Harness built = harness(twoCardsTwoStatements());

            assertThatExceptionOfType(StopRequestedException.class)
                    .isThrownBy(() -> built.job.printAccountStatements(built.sysout,
                            StopSignal.of(stepExecution)))
                    .withMessageContaining(StatementGenerationJobA.STEP_040)
                    .withMessageContaining("NO write is retried")
                    // The cause is what makes AbstractStep report the step as STOPPED rather than
                    // FAILED, so it is asserted rather than left as an implementation detail.
                    .withCauseInstanceOf(JobInterruptedException.class);

            // The table load is the FIRST loop the pass reaches, so a stop pending at entry ends the run
            // before a single statement record is emitted to either output.
            assertThat(built.textRecords).isEmpty();
            assertThat(built.htmlRecords).isEmpty();
        }

        @Test
        @DisplayName("a stop in the MAINLINE ends the pass with the statement in flight complete, and "
                + "the ones already written left alone")
        void aStopInTheMainlineLeavesCompletedStatementsAlone() {
            StepExecution stepExecution = stepExecution();
            Harness built = harness(twoCardsTwoStatements());

            // The stop is landed by CONTENT rather than by a probe count, so it cannot drift with the
            // number of times the pass consults the signal: it is requested at the first probe AFTER the
            // first customer's detail line has been written, which is by construction inside the
            // MAINLINE and never inside the table load - the load writes no statement line at all.
            StopSignal stopOnceTheFirstStatementIsWritten = () -> {
                if (built.textRecords.stream().anyMatch(record -> record.contains("A ONE"))) {
                    stepExecution.setTerminateOnly();
                }
                StopSignal.of(stepExecution).checkStopRequested();
            };

            assertThatExceptionOfType(StopRequestedException.class).isThrownBy(() ->
                    built.job.printAccountStatements(built.sysout,
                            stopOnceTheFirstStatementIsWritten));

            // Exactly one statement was written, whole: the first customer's detail line is there and the
            // second's is not. A statement half written to one output and not the other is the failure
            // this position rules out, so both outputs are asserted.
            assertThat(built.textRecords.stream().filter(record -> record.contains("A ONE")).count())
                    .isOne();
            assertThat(built.textRecords.stream().filter(record -> record.contains("B ONE")).count())
                    .isZero();
            assertThat(built.htmlRecords).isNotEmpty();

            // The totals footer of the statement in flight was written; the four CLOSE statements at the
            // end of 1000-MAINLINE were NOT reached, exactly as they are not reached on an abend.
            assertThat(built.subroutine.calls()).noneSatisfy(call ->
                    assertThat(call).startsWith("CLOSE"));
        }

        @Test
        @DisplayName("a null stop signal is refused rather than silently treated as 'never stop'")
        void aNullStopSignalIsRefused() {
            Harness built = harness(oneStatement());

            assertThatNullPointerException()
                    .isThrownBy(() -> built.job.printAccountStatements(built.sysout, null))
                    .withMessageContaining("StopSignal.RUNNING");
        }

        @Test
        @DisplayName("the statement tasklet takes its signal from the step execution the framework "
                + "supplies")
        void theStatementTaskletTakesItsSignalFromTheStepExecution() {
            StepExecution stepExecution = stepExecution();
            stepExecution.setTerminateOnly();
            Harness built = harness(twoCardsTwoStatements());
            Tasklet tasklet = built.job.statementTasklet();
            StepContribution contribution = new StepContribution(stepExecution);
            ChunkContext chunkContext = new ChunkContext(new StepContext(stepExecution));

            // Driven exactly as TaskletStep drives it, so this asserts the wiring and not just the
            // program: a tasklet that ignored the chunk context would run the whole pass here.
            assertThatExceptionOfType(StopRequestedException.class)
                    .isThrownBy(() -> tasklet.execute(contribution, chunkContext));

            assertThat(built.textRecords).isEmpty();
            assertThat(contribution.getWriteCount()).isZero();
        }

        @Test
        @DisplayName("the two copying preparation tasklets take their signals from their own step "
                + "executions")
        void theCopyingPreparationTaskletsTakeTheirSignals() {
            RecordingUtilityPort port = new RecordingUtilityPort()
                    .seed(TRANSACT_DSNAME, List.of(tranImage(CARD_A, "TRAN000000000001")));
            port.seed(TRXFL_SEQ_DSNAME, List.of(
                    trnxImage(CARD_A, "TRAN000000000001", "ONE", "1.00")));
            Harness built = new Harness(new ScriptedSubroutine(), defaultTiot(), jobContracts(), port);
            StepExecution sortStep =
                    new StepExecution(StatementGenerationJobA.STEP_010, new JobExecution(41L));
            StepExecution reproStep =
                    new StepExecution(StatementGenerationJobA.STEP_020, new JobExecution(42L));
            sortStep.setTerminateOnly();
            reproStep.setTerminateOnly();

            assertThatExceptionOfType(StopRequestedException.class).isThrownBy(() ->
                    built.job.sortAndReformatTasklet().execute(
                            new StepContribution(sortStep),
                            new ChunkContext(new StepContext(sortStep))));
            assertThatExceptionOfType(StopRequestedException.class).isThrownBy(() ->
                    built.job.reproTasklet().execute(
                            new StepContribution(reproStep),
                            new ChunkContext(new StepContext(reproStep))));
        }

        /** @return a fresh step execution over STEP040, not asked to stop */
        private StepExecution stepExecution() {
            return new StepExecution(StatementGenerationJobA.STEP_040, new JobExecution(40L));
        }

        /**
         * A probe that permits the given number of consultations and then reports a stop.
         *
         * <p>It sets {@code terminateOnly} on the real step execution and then delegates to the real
         * {@link StopSignal}, so the refusal is produced by the production probe and the framework's own
         * interruption policy rather than by a stand-in that merely throws the same type.
         *
         * @param stepExecution the execution to mark
         * @param permitted     how many consultations return before the stop is requested
         * @return the probe
         */
        private StopSignal signalStoppingAfter(StepExecution stepExecution, int permitted) {
            StopSignal real = StopSignal.of(stepExecution);
            int[] consulted = { 0 };
            return () -> {
                if (consulted[0] == permitted) {
                    stepExecution.setTerminateOnly();
                }
                consulted[0]++;
                real.checkStopRequested();
            };
        }
    }

    // =============================================================================================
    // 1000-MAINLINE and the four data paragraphs.
    // =============================================================================================

    /** The driving loop, the keyed reads and the four closes. */
    @Nested
    @DisplayName("1000-MAINLINE and the four data paragraphs")
    class TheMainline {

        @Test
        @DisplayName("two cross-reference records produce two statements, each with its own reads")
        void twoStatements() {
            ScriptedSubroutine subroutine = new ScriptedSubroutine()
                    .reads(StatementGenerationJobA.TRNXFILE_DD,
                            ok(trnxImage(CARD_A, "TRAN000000000001", "A ONE", "1.00")),
                            ok(trnxImage(CARD_B, "TRAN000000000002", "B ONE", "2.00")), eof())
                    .reads(StatementGenerationJobA.XREFFILE_DD,
                            ok(xrefImage(CARD_A, CUST_ID, ACCT_ID)),
                            ok(xrefImage(CARD_B, CUST_ID, ACCT_ID)), eof())
                    .keyed(StatementGenerationJobA.CUSTFILE_DD, ok(defaultCustImage()),
                            ok(defaultCustImage()))
                    .keyed(StatementGenerationJobA.ACCTFILE_DD, ok(defaultAcctImage()),
                            ok(defaultAcctImage()));
            Harness built = harness(subroutine);

            int statements = built.run();

            assertThat(statements).isEqualTo(2);
            assertThat(detailLines(built)).isEqualTo(2);
            assertThat(built.subroutine.calls().stream().filter("READ XREFFILE"::equals).count())
                    .isEqualTo(3);
            assertThat(built.subroutine.calls().stream().filter("READK CUSTFILE"::equals).count())
                    .isEqualTo(2);
        }

        @Test
        @DisplayName("the keyed reads use XREF-CUST-ID at 9 bytes and XREF-ACCT-ID at 11")
        void theKeyedReadKeysAndLengths() {
            Harness built = harness(oneStatement());

            built.run();

            assertThat(built.subroutine.keys()).containsExactly(
                    StatementGenerationJobA.CUSTFILE_DD + ' ' + CUST_ID + " 9",
                    StatementGenerationJobA.ACCTFILE_DD + ' ' + ACCT_ID + " 11");
            assertThat(CardXrefRecord.XREF_CUST_ID_LENGTH).isEqualTo(9);
            assertThat(CardXrefRecord.XREF_ACCT_ID_LENGTH).isEqualTo(11);
        }

        @Test
        @DisplayName("the key each read passes reconstructs WS-MO3B-KEY's own PIC X(25) image, "
                + "left-justified and space-filled (CBSTM03A.CBL:L372-L374, L396-L398)")
        void theKeyAreaImageIsTheCobolImage() {
            // L372  MOVE XREF-CUST-ID TO WS-M03B-KEY.   PIC 9(09) into PIC X(25), so nine digits land at
            //       the left and the remaining SIXTEEN bytes stay spaces - a PIC X receiver is padded on
            //       the right, never on the left.
            // L373  MOVE ZERO TO WS-M03B-KEY-LN.
            // L374  COMPUTE WS-M03B-KEY-LN = LENGTH OF XREF-CUST-ID.   =>  9
            // L396-L398 are the same three statements over XREF-ACCT-ID, PIC 9(11)  =>  11, leaving
            //       FOURTEEN trailing spaces.
            //
            // The Java call passes the significant prefix and its length rather than a 25-byte string,
            // which carries the same information: WS-M03B-KEY-LN is what tells the subroutine how much of
            // the area to use, and CBSTM03B never looks past it. So the area image is reconstructed here
            // from the pair and compared against the image the two MOVEs produce, which is the assertion
            // the COBOL actually constrains.
            FixedWidthCodec codec = new FixedWidthCodec(ASCII);
            Harness built = harness(oneStatement());

            built.run();

            String custArea = codec.movePicX(CUST_ID, StatementGenerationJobB.KEY_LENGTH);
            String acctArea = codec.movePicX(ACCT_ID, StatementGenerationJobB.KEY_LENGTH);

            assertThat(StatementGenerationJobB.KEY_LENGTH).isEqualTo(25);
            assertThat(custArea).hasSize(25)
                    .isEqualTo(CUST_ID + " ".repeat(25 - CardXrefRecord.XREF_CUST_ID_LENGTH));
            assertThat(custArea.substring(CardXrefRecord.XREF_CUST_ID_LENGTH)).hasSize(16).isBlank();
            assertThat(acctArea).hasSize(25)
                    .isEqualTo(ACCT_ID + " ".repeat(25 - CardXrefRecord.XREF_ACCT_ID_LENGTH));
            assertThat(acctArea.substring(CardXrefRecord.XREF_ACCT_ID_LENGTH)).hasSize(14).isBlank();

            // And the significant prefix the run passed is exactly what the area holds at the left.
            assertThat(built.subroutine.keys()).containsExactly(
                    StatementGenerationJobA.CUSTFILE_DD + ' '
                            + custArea.substring(0, CardXrefRecord.XREF_CUST_ID_LENGTH) + " 9",
                    StatementGenerationJobA.ACCTFILE_DD + ' '
                            + acctArea.substring(0, CardXrefRecord.XREF_ACCT_ID_LENGTH) + " 11");
        }

        @Test
        @DisplayName("a real 36-byte app/data/ASCII/cardxref.txt row is right-padded to CVACT03Y's "
                + "declared 50 before it is read (risk R-F, gate G16)")
        void aRealFixtureRowIsPaddedToTheCopybookWidth() {
            // app/cpy/CVACT03Y.cpy declares XREF-CARD-NUM X(16) + XREF-CUST-ID 9(09) + XREF-ACCT-ID 9(11)
            // + FILLER X(14) = FIFTY bytes. Every row of app/data/ASCII/cardxref.txt is THIRTY-SIX: the
            // fixture simply stops after the account id and omits the trailing FILLER. It is the only one
            // of the nine ASCII fixtures whose width disagrees with its copybook, so a row taken from it
            // has to be right-padded to fifty before anything decodes it - a shortfall the record type
            // refuses outright rather than tolerating.
            //
            // The row below is the first line of that fixture, transcribed. It is used rather than a
            // made-up value so the join keys carry the shapes real data has: a card number whose leading
            // digit is not 4, and zero-filled customer and account ids.
            String fixtureRow = "050002445376574000000005000000000050";
            assertThat(fixtureRow).hasSize(36).hasSize(CardXrefRecord.FILLER_OFFSET);
            assertThat(CardXrefRecord.RECORD_LENGTH - CardXrefRecord.FILLER_OFFSET)
                    .isEqualTo(CardXrefRecord.FILLER_LENGTH)
                    .isEqualTo(14);

            FixedWidthCodec codec = new FixedWidthCodec(ASCII);
            String padded = codec.padToDeclaredWidth(fixtureRow, CardXrefRecord.RECORD_LENGTH);

            assertThat(padded).hasSize(CardXrefRecord.RECORD_LENGTH).startsWith(fixtureRow);
            assertThat(padded.substring(CardXrefRecord.FILLER_OFFSET))
                    .hasSize(CardXrefRecord.FILLER_LENGTH).isBlank();

            // The padded row decodes into the three join keys the fixture carries.
            CardXrefRecord decoded = CardXrefRecord.decode(padded.getBytes(ASCII), ASCII);
            assertThat(decoded.xrefCardNum()).isEqualTo("0500024453765740");
            assertThat(decoded.xrefCustId()).isEqualTo(50);
            assertThat(decoded.xrefAcctId()).isEqualTo(50L);

            // And a run driven from that padded row keys its two reads off the fixture's own ids, at the
            // widths L374 and L398 compute - which is the whole point of padding before reading.
            //
            // The work dataset is given one record rather than none, because L748's guard accepts only
            // '00' and '04': an EMPTY TRNXFILE reports '10' on the first read at L746 and abends. That is
            // the COBOL's own behaviour, asserted elsewhere in this class, and it is why every whole-run
            // script here starts with at least one transaction.
            Harness built = harness(new ScriptedSubroutine()
                    .reads(StatementGenerationJobA.TRNXFILE_DD,
                            ok(trnxImage("0500024453765740", "TRAN000000000001", "FIXTURE ROW", "1.00")),
                            eof())
                    .reads(StatementGenerationJobA.XREFFILE_DD, ok(padded), eof())
                    .keyed(StatementGenerationJobA.CUSTFILE_DD, ok(defaultCustImage()))
                    .keyed(StatementGenerationJobA.ACCTFILE_DD, ok(defaultAcctImage())));

            assertThat(built.run()).isEqualTo(1);
            assertThat(built.subroutine.keys()).containsExactly(
                    StatementGenerationJobA.CUSTFILE_DD + " 000000050 9",
                    StatementGenerationJobA.ACCTFILE_DD + " 00000000050 11");
        }

        @Test
        @DisplayName("the same bean run twice shares not one byte of table, counter or total "
                + "(practice B9, gate G53)")
        void twoRunsOfOneBeanAreIsolated() {
            // CBSTM03A holds WS-TRNX-TABLE, the four COMP counters, WS-TOTAL-AMT, WS-SAVE-CARD,
            // END-OF-FILE and WS-FL-DD in WORKING-STORAGE, which in COBOL is per run unit. A
            // @Configuration class is a singleton, so the translation must NOT put any of that in a field -
            // and this is the assertion that fails if it ever does. One bean, one subroutine, TWO passes:
            // the first sees a 10.00 transaction, the second a 1.11 one. A leaked table would give the
            // second pass two detail lines; a leaked accumulator would give it a total of 11.11.
            Harness built = harness(new ScriptedSubroutine()
                    .reads(StatementGenerationJobA.TRNXFILE_DD,
                            ok(trnxImage(CARD_A, "TRAN000000000001", "FIRST PASS", "10.00")), eof(),
                            ok(trnxImage(CARD_A, "TRAN000000000002", "SECOND PASS", "1.11")), eof())
                    .reads(StatementGenerationJobA.XREFFILE_DD,
                            ok(xrefImage(CARD_A, CUST_ID, ACCT_ID)), eof(),
                            ok(xrefImage(CARD_A, CUST_ID, ACCT_ID)), eof())
                    .keyed(StatementGenerationJobA.CUSTFILE_DD, ok(defaultCustImage()),
                            ok(defaultCustImage()))
                    .keyed(StatementGenerationJobA.ACCTFILE_DD, ok(defaultAcctImage()),
                            ok(defaultAcctImage())));

            assertThat(built.run()).isEqualTo(1);
            assertThat(built.textRecords).hasSize(20);
            assertThat(detailLines(built)).isEqualTo(1);
            assertThat(built.textRecords.get(18)).isEqualTo(renderTotalLine(new BigDecimal("10.00")));

            // Second pass, same bean.
            assertThat(built.run()).isEqualTo(1);
            assertThat(built.textRecords).hasSize(40);
            // Two detail lines in total, one per pass - not three, which is what a retained table gives.
            assertThat(detailLines(built)).isEqualTo(2);
            // 1.11, not 11.11: the accumulator did not survive the first pass either.
            assertThat(built.textRecords.get(38)).isEqualTo(renderTotalLine(new BigDecimal("1.11")));

            // The two passes' twenty-record blocks differ only where their data differs, which is what
            // proves the counters restarted: a retained CR-CNT would have shifted the second block.
            assertThat(built.textRecords.subList(0, 16))
                    .isEqualTo(built.textRecords.subList(20, 36));
        }

        @Test
        @DisplayName("the four closes run in the order 9100, 9200, 9300, 9400 and then both outputs")
        void theCloseOrder() {
            Harness built = harness(oneStatement());

            built.run();

            assertThat(built.subroutine.calls()).endsWith("CLOSE TRNXFILE", "CLOSE XREFFILE",
                    "CLOSE CUSTFILE", "CLOSE ACCTFILE");
            Mockito.verify(built.htmlWriter).close(Mockito.any(HtmlStatementFile.class));
        }

        @Test
        @DisplayName("L293 a refused OPEN OUTPUT on HTML-FILE terminates the run unit")
        void aRefusedHtmlOpenTerminatesTheRunUnit() {
            RefusingHarness built = new RefusingHarness(oneStatement(), 0, 0, HTML_REFUSED_STATUS,
                    FileStatus.Outcome.OK, FileStatus.OK);

            assertThatExceptionOfType(AbendException.class).isThrownBy(built::run)
                    .withMessageContaining(StatementGenerationJobA.HTMLFILE_DD)
                    .withMessageContaining("OPEN OUTPUT HTML-FILE")
                    .satisfies(abend -> assertThat(abend.getReturnCode())
                            .as("an unguarded I-O condition, not a CEE3ABD site")
                            .isEqualTo(AbendException.RETURN_CODE_IO_ERROR));

            // Not one record was offered: the run stopped at the OPEN, which is the whole point - a run
            // that continued would compose a hundred records and report success while writing nothing.
            assertThat(built.htmlRecordsOffered).isZero();
            assertThat(built.textRecordsOffered).isZero();
            // And no SYSOUT abend banner: this file has no guard, so the source displays nothing for it.
            assertThat(built.sysout.lines())
                    .doesNotContain(StatementGenerationJobA.ABENDING_PROGRAM);
        }

        @Test
        @DisplayName("L460 a refused WRITE on STMT-FILE terminates the run unit, silently")
        void aRefusedTextWriteTerminatesTheRunUnit() {
            RefusingHarness built = refusingTextRecord(1);

            assertThatExceptionOfType(AbendException.class).isThrownBy(built::run)
                    .withMessageContaining(StatementGenerationJobA.STMTFILE_DD)
                    .withMessageContaining("WRITE FD-STMTFILE-REC")
                    .satisfies(abend -> assertThat(abend.getReturnCode())
                            .isEqualTo(AbendException.RETURN_CODE_IO_ERROR));

            assertThat(built.textRecordsOffered)
                    .as("the pass stops at the record the sink refused")
                    .isEqualTo(1);
            // app/cbl/CBSTM03A.CBL emits no DISPLAY for this file, so neither does this. The three-line
            // banner belongs to the four GUARDED input files, and claiming it here would invent output.
            assertThat(built.sysout.lines())
                    .doesNotContain(StatementGenerationJobA.ABENDING_PROGRAM);
        }

        @Test
        @DisplayName("L508 a refused WRITE on HTML-FILE terminates the run unit, silently")
        void aRefusedHtmlWriteTerminatesTheRunUnit() {
            RefusingHarness built = refusingHtmlRecord(1);

            assertThatExceptionOfType(AbendException.class).isThrownBy(built::run)
                    .withMessageContaining(StatementGenerationJobA.HTMLFILE_DD)
                    .withMessageContaining("WRITE FD-HTMLFILE-REC")
                    .satisfies(abend -> assertThat(abend.getReturnCode())
                            .isEqualTo(AbendException.RETURN_CODE_IO_ERROR));

            assertThat(built.htmlRecordsOffered).isEqualTo(1);
            assertThat(built.sysout.lines())
                    .doesNotContain(StatementGenerationJobA.ABENDING_PROGRAM);
        }

        @Test
        @DisplayName("L339 CLOSE names both files, so both are closed before either condition is acted on")
        void aRefusedTextCloseStillClosesTheHtmlFile() {
            RefusingHarness built = new RefusingHarness(oneStatement(), 0, 0, FileStatus.OK,
                    FileStatus.Outcome.OTHER, FileStatus.OK);

            assertThatExceptionOfType(AbendException.class).isThrownBy(built::run)
                    .withMessageContaining("CLOSE STMT-FILE")
                    .satisfies(abend -> assertThat(abend.getReturnCode())
                            .isEqualTo(AbendException.RETURN_CODE_IO_ERROR));

            // One CLOSE statement names both files, so the second file is closed even though the first
            // reported a condition; the reported condition is the first file's, in statement order.
            assertThat(built.textClosed).isTrue();
            assertThat(built.htmlClosed).isTrue();
            assertThat(built.textHandle.isOpen()).isFalse();
            assertThat(built.htmlHandle.isOpen()).isFalse();
        }

        @Test
        @DisplayName("L339 a refused CLOSE on HTML-FILE terminates the run unit")
        void aRefusedHtmlCloseTerminatesTheRunUnit() {
            RefusingHarness built = new RefusingHarness(oneStatement(), 0, 0, FileStatus.OK,
                    FileStatus.Outcome.OK, HTML_REFUSED_STATUS);

            assertThatExceptionOfType(AbendException.class).isThrownBy(built::run)
                    .withMessageContaining("CLOSE HTML-FILE")
                    .satisfies(abend -> assertThat(abend.getReturnCode())
                            .isEqualTo(AbendException.RETURN_CODE_IO_ERROR));

            assertThat(built.textClosed).isTrue();
            assertThat(built.htmlClosed).isTrue();
        }

        @Test
        @DisplayName("an incomplete run releases both output handles and the subroutine session")
        void anIncompleteRunReleasesEveryHandle() {
            RefusingHarness built = refusingTextRecord(1);

            assertThatExceptionOfType(AbendException.class).isThrownBy(built::run);

            // On the mainframe the Language Environment tears a terminating run unit down. Nothing does
            // that in a long-lived JVM, so the finally block does - silently, and without a second CLOSE.
            assertThat(built.textHandle.isOpen())
                    .as("STMTFILE must not stay held after the run unit terminated")
                    .isFalse();
            assertThat(built.htmlHandle.isOpen())
                    .as("HTMLFILE must not stay held after the run unit terminated")
                    .isFalse();
            assertThat(built.htmlClosed).isTrue();
            assertThat(built.textClosed).isTrue();
        }

        @Test
        @DisplayName("a completed run leaves the release with nothing to do - L339 already closed both")
        void aCompletedRunClosesBothFilesExactlyOnce() {
            RefusingHarness built = new RefusingHarness(oneStatement(), 0, 0, FileStatus.OK,
                    FileStatus.Outcome.OK, FileStatus.OK);

            assertThat(built.run()).isEqualTo(1);

            assertThat(built.textHandle.isOpen()).isFalse();
            assertThat(built.htmlHandle.isOpen()).isFalse();
            // The sinks' own close was reached once each; the release found both handles closed and did
            // nothing, which is what keeps it invisible on the normal path.
            assertThat(built.textClosed).isTrue();
            assertThat(built.htmlClosed).isTrue();
        }

        @Test
        @DisplayName("END-OF-FILE starts at 'N' and only 'N' and 'Y' are ever moved into it")
        void theEndOfFileFlag() {
            WorkingStorage storage = newWorkingStorage();

            assertThat(StatementGenerationJobA.END_OF_FILE_NO).isEqualTo("N");
            assertThat(StatementGenerationJobA.END_OF_FILE_YES).isEqualTo("Y");
            assertThat(storage.endOfFile()).isEqualTo(StatementGenerationJobA.END_OF_FILE_NO);
            storage.moveToEndOfFile(StatementGenerationJobA.END_OF_FILE_YES);
            assertThat(storage.endOfFile()).isEqualTo(StatementGenerationJobA.END_OF_FILE_YES);
        }

        @Test
        @DisplayName("the L364 MOVE is UNCONDITIONAL: on the '10' arm the xref record becomes spaces")
        void theUnconditionalXrefMove() {
            WorkingStorage storage = newWorkingStorage();
            storage.moveToCardXrefRecord(ok(xrefImage(CARD_A, CUST_ID, ACCT_ID)));

            assertThat(storage.xrefCardNum()).isEqualTo(CARD_A);

            // What the end-of-file arm leaves behind: L350 moved spaces into WS-M03B-FLDT and the
            // subroutine returned the area untouched, so L364 overwrites the record with spaces.
            storage.moveToCardXrefRecord(eof());

            assertThat(storage.cardXrefRecord()).isBlank()
                    .hasSize(CardXrefRecord.RECORD_LENGTH);
            assertThat(storage.xrefCardNum()).isBlank();
            assertThat(storage.xrefCustId()).isBlank();
            assertThat(storage.xrefAcctId()).isBlank();
        }

        @Test
        @DisplayName("a run over an end-of-file xref read still reaches the four closes")
        void theEofArmStillCloses() {
            Harness built = harness(new ScriptedSubroutine().reads(
                    StatementGenerationJobA.TRNXFILE_DD,
                    ok(trnxImage(CARD_A, "TRAN000000000001", "ONE", "1.00")), eof()));

            assertThat(built.run()).isZero();
            assertThat(built.subroutine.calls()).contains("READ XREFFILE");
        }

        @Test
        @DisplayName("MOVE 1 TO CR-JMP at L324 is preserved even though L417 overwrites it")
        void theRedundantCrJmpMove() {
            WorkingStorage storage = newWorkingStorage();

            assertThat(storage.crJmp()).isZero();
            storage.moveToCrJmp(1);
            assertThat(storage.crJmp()).isEqualTo(1);
            storage.moveToCrJmp(4);
            assertThat(storage.crJmp()).isEqualTo(4);
        }
    }

    // =============================================================================================
    // 4000-TRNXFILE-GET - the table scan, the short-circuit and the total.
    // =============================================================================================

    /** The two nested test-before loops and the customer's transaction total. */
    @Nested
    @DisplayName("4000-TRNXFILE-GET: the table scan and the total")
    class TheTransactionScan {

        @Test
        @DisplayName("the UNTIL short-circuits left to right, so the subscript never leaves 1..51")
        void theUntilShortCircuits() {
            // Fifty-one cards fills the table, so CR-JMP reaches 52. Evaluating
            // WS-CARD-NUM (CR-JMP) before CR-JMP > CR-CNT would address subscript 52 and throw.
            Response[] reads = new Response[StatementGenerationJobA.CARD_TABLE_OCCURS + 1];
            for (int card = 0; card < StatementGenerationJobA.CARD_TABLE_OCCURS; card++) {
                reads[card] = ok(trnxImage(String.format("4%015d", card + 1),
                        String.format("TRAN%012d", card + 1), "PURCHASE", "1.00"));
            }
            reads[StatementGenerationJobA.CARD_TABLE_OCCURS] = eof();
            String highestCard =
                    String.format("4%015d", StatementGenerationJobA.CARD_TABLE_OCCURS);
            ScriptedSubroutine subroutine = new ScriptedSubroutine()
                    .reads(StatementGenerationJobA.TRNXFILE_DD, reads)
                    .reads(StatementGenerationJobA.XREFFILE_DD,
                            ok(xrefImage(highestCard, CUST_ID, ACCT_ID)), eof())
                    .keyed(StatementGenerationJobA.CUSTFILE_DD, ok(defaultCustImage()))
                    .keyed(StatementGenerationJobA.ACCTFILE_DD, ok(defaultAcctImage()));
            Harness built = harness(subroutine);

            int statements = built.run();

            assertThat(statements).isEqualTo(1);
            assertThat(detailLines(built)).isEqualTo(1);
        }

        @Test
        @DisplayName("the scan stops as soon as a table card sorts above the cross-reference card")
        void theScanExitsEarly() {
            ScriptedSubroutine subroutine = new ScriptedSubroutine()
                    .reads(StatementGenerationJobA.TRNXFILE_DD,
                            ok(trnxImage(CARD_A, "TRAN000000000001", "A ONE", "1.00")),
                            ok(trnxImage(CARD_B, "TRAN000000000002", "B ONE", "2.00")),
                            ok(trnxImage(CARD_C, "TRAN000000000003", "C ONE", "3.00")), eof())
                    .reads(StatementGenerationJobA.XREFFILE_DD,
                            ok(xrefImage(CARD_A, CUST_ID, ACCT_ID)), eof())
                    .keyed(StatementGenerationJobA.CUSTFILE_DD, ok(defaultCustImage()))
                    .keyed(StatementGenerationJobA.ACCTFILE_DD, ok(defaultAcctImage()));
            Harness built = harness(subroutine);

            built.run();

            assertThat(detailLines(built)).isEqualTo(1);
            assertThat(built.textRecords.stream().filter(r -> r.contains("A ONE")).count())
                    .isEqualTo(1);
            assertThat(built.textRecords.stream().filter(r -> r.contains("B ONE")).count()).isZero();
        }

        @Test
        @DisplayName("a cross-reference card the table does not hold at all yields no detail lines")
        void aCardWithNoTransactions() {
            ScriptedSubroutine subroutine = new ScriptedSubroutine()
                    .reads(StatementGenerationJobA.TRNXFILE_DD,
                            ok(trnxImage(CARD_A, "TRAN000000000001", "A ONE", "1.00")), eof())
                    .reads(StatementGenerationJobA.XREFFILE_DD,
                            ok(xrefImage(CARD_C, CUST_ID, ACCT_ID)), eof())
                    .keyed(StatementGenerationJobA.CUSTFILE_DD, ok(defaultCustImage()))
                    .keyed(StatementGenerationJobA.ACCTFILE_DD, ok(defaultAcctImage()))
                    .closes(StatementGenerationJobA.TRNXFILE_DD, FileStatus.OK);
            Harness built = harness(subroutine);

            int statements = built.run();

            // A statement is still produced - the COBOL writes the head, the total and the tail
            // whether or not the scan matched anything.
            assertThat(statements).isEqualTo(1);
            assertThat(detailLines(built)).isZero();
            assertThat(built.textRecords).hasSize(19);
        }

        @Test
        @DisplayName("the total accumulates at scale 2 and truncates, never rounds (gates G23, G24)")
        void theTotalTruncates() {
            WorkingStorage storage = newWorkingStorage();

            storage.moveZeroToWsTotalAmt();
            assertThat(storage.wsTotalAmt()).isEqualByComparingTo("0.00");
            assertThat(storage.wsTotalAmt().scale()).isEqualTo(CobolDecimal.MONETARY_SCALE);

            storage.addTrnxAmtToWsTotalAmt(new BigDecimal("0.999"));

            assertThat(storage.wsTotalAmt()).isEqualByComparingTo("0.99");
            assertThat(storage.wsTotalAmt().scale()).isEqualTo(2);
            assertThat(CobolDecimal.COBOL_ROUNDING).isEqualTo(java.math.RoundingMode.DOWN);
        }

        @Test
        @DisplayName("a negative amount also truncates towards zero, which is what DOWN means")
        void aNegativeAmountTruncatesTowardsZero() {
            WorkingStorage storage = newWorkingStorage();

            storage.addTrnxAmtToWsTotalAmt(new BigDecimal("-0.999"));

            assertThat(storage.wsTotalAmt()).isEqualByComparingTo("-0.99");
        }

        @Test
        @DisplayName("WS-TRN-AMT takes its value from WS-TOTAL-AMT, at scale 2")
        void theTotalIsMovedToWsTrnAmt() {
            WorkingStorage storage = newWorkingStorage();
            storage.addTrnxAmtToWsTotalAmt(new BigDecimal("12.34"));
            storage.addTrnxAmtToWsTotalAmt(new BigDecimal("0.66"));

            storage.moveWsTotalAmtToWsTrnAmt();

            assertThat(storage.wsTrnAmt()).isEqualByComparingTo("13.00");
            assertThat(storage.wsTrnAmt().scale()).isEqualTo(2);
        }

        @Test
        @DisplayName("the total is zeroed per customer at L325, not once per run")
        void theTotalIsZeroedPerCustomer() {
            ScriptedSubroutine subroutine = new ScriptedSubroutine()
                    .reads(StatementGenerationJobA.TRNXFILE_DD,
                            ok(trnxImage(CARD_A, "TRAN000000000001", "A ONE", "10.00")),
                            ok(trnxImage(CARD_B, "TRAN000000000002", "B ONE", "20.00")), eof())
                    .reads(StatementGenerationJobA.XREFFILE_DD,
                            ok(xrefImage(CARD_A, CUST_ID, ACCT_ID)),
                            ok(xrefImage(CARD_B, CUST_ID, ACCT_ID)), eof())
                    .keyed(StatementGenerationJobA.CUSTFILE_DD, ok(defaultCustImage()),
                            ok(defaultCustImage()))
                    .keyed(StatementGenerationJobA.ACCTFILE_DD, ok(defaultAcctImage()),
                            ok(defaultAcctImage()));
            Harness built = harness(subroutine);

            built.run();

            // Twenty records per statement: 16 head, 1 detail, 3 tail. ST-LINE14A carries the total and
            // is the second of the three tail records, so index 18 and index 38.
            assertThat(built.textRecords).hasSize(40);
            assertThat(built.textRecords.get(18))
                    .isEqualTo(renderTotalLine(new BigDecimal("10.00")));
            assertThat(built.textRecords.get(38))
                    .isEqualTo(renderTotalLine(new BigDecimal("20.00")));
        }

        @Test
        @DisplayName("ten transactions on one card sum without rounding anywhere")
        void tenTransactionsSum() {
            ScriptedSubroutine subroutine = new ScriptedSubroutine();
            Response[] reads = new Response[11];
            for (int index = 0; index < 10; index++) {
                reads[index] = ok(trnxImage(CARD_A, String.format("TRAN%012d", index + 1),
                        "PURCHASE", "1.11"));
            }
            reads[10] = eof();
            subroutine.reads(StatementGenerationJobA.TRNXFILE_DD, reads)
                    .reads(StatementGenerationJobA.XREFFILE_DD,
                            ok(xrefImage(CARD_A, CUST_ID, ACCT_ID)), eof())
                    .keyed(StatementGenerationJobA.CUSTFILE_DD, ok(defaultCustImage()))
                    .keyed(StatementGenerationJobA.ACCTFILE_DD, ok(defaultAcctImage()));
            Harness built = harness(subroutine);

            built.run();

            assertThat(built.textRecords).hasSize(16 + 10 + 3);
            assertThat(built.textRecords.get(16 + 10 + 1))
                    .isEqualTo(renderTotalLine(new BigDecimal("11.10")));
        }

        @Test
        @DisplayName("the counters are the COMP halfwords CBSTM03A declares, and start at zero")
        void theCounters() {
            WorkingStorage storage = newWorkingStorage();

            assertThat(storage.crCnt()).isZero();
            assertThat(storage.trCnt()).isZero();
            assertThat(storage.crJmp()).isZero();
            assertThat(storage.trJmp()).isZero();

            storage.moveToCrCnt(1);
            storage.addOneToCrCnt();
            storage.moveToTrCnt(0);
            storage.addOneToTrCnt();
            storage.moveToTrJmp(3);

            assertThat(storage.crCnt()).isEqualTo(2);
            assertThat(storage.trCnt()).isEqualTo(1);
            assertThat(storage.trJmp()).isEqualTo(3);
        }

        @Test
        @DisplayName("WS-SAVE-CARD is a PIC X(16) receiver, so a short card number is space padded")
        void wsSaveCardIsFitted() {
            WorkingStorage storage = newWorkingStorage();

            assertThat(storage.wsSaveCard()).isEqualTo(" ".repeat(16));
            storage.moveToWsSaveCard("4111");
            assertThat(storage.wsSaveCard()).isEqualTo("4111" + " ".repeat(12));
            storage.moveToWsSaveCard(CARD_A);
            assertThat(storage.wsSaveCard()).isEqualTo(CARD_A);
        }
    }

    /**
     * The plain-text total line, rendered independently through the writer's own mask.
     *
     * @param total the customer's transaction total
     * @return the 80-byte {@code ST-LINE14A} image
     */
    private static String renderTotalLine(BigDecimal total) {
        StatementTextWriter writer = new StatementTextWriter(new JdbcTemplate(), ASCII,
                globalBindings(), RecordImageForm.CHARACTER);
        StatementFile file = writer.openOutput(image -> FileStatus.Outcome.OK);
        file.initializeStatementLines();
        file.setTotalTransactionAmount(total);
        return file.renderLine(StatementLine.ST_LINE14A);
    }

    // =============================================================================================
    // The write order: this class's own responsibility, and parity-invariant.
    //
    // The two expected sequences below are transcribed from app/cbl/CBSTM03A.CBL line by line, NOT read
    // back from the constants the implementation iterates. That independence is the point: a reordered
    // list in the implementation would agree with itself and disagree with the COBOL, and only an
    // independently written expectation catches it.
    // =============================================================================================

    /** The golden text and HTML sequences for one complete statement. */
    @Nested
    @DisplayName("The statement write order, transcribed from CBSTM03A.CBL")
    class TheWriteOrder {

        /** The one transaction every golden run carries. */
        private static final String GOLDEN_TRAN_ID = "TRAN000000000001";

        /** Its description, chosen longer than nothing and shorter than 49 so no truncation hides. */
        private static final String GOLDEN_DESC = "GROCERY STORE PURCHASE";

        /** Its amount. */
        private static final String GOLDEN_AMOUNT = "12.34";

        /** A harness whose scripted run produces exactly one statement with one transaction. */
        private Harness goldenRun() {
            ScriptedSubroutine subroutine = new ScriptedSubroutine()
                    .reads(StatementGenerationJobA.TRNXFILE_DD,
                            ok(trnxImage(CARD_A, GOLDEN_TRAN_ID, GOLDEN_DESC, GOLDEN_AMOUNT)), eof())
                    .reads(StatementGenerationJobA.XREFFILE_DD,
                            ok(xrefImage(CARD_A, CUST_ID, ACCT_ID)), eof())
                    .keyed(StatementGenerationJobA.CUSTFILE_DD, ok(defaultCustImage()))
                    .keyed(StatementGenerationJobA.ACCTFILE_DD, ok(defaultAcctImage()));
            Harness built = harness(subroutine);
            built.run();
            return built;
        }

        @Test
        @DisplayName("the plain-text sequence matches CBSTM03A byte for byte, both repeats included")
        void theGoldenTextSequence() {
            Harness built = goldenRun();

            assertThat(built.textRecords).isEqualTo(expectedText());
        }

        @Test
        @DisplayName("the HTML sequence matches CBSTM03A byte for byte, all seventy-five records")
        void theGoldenHtmlSequence() {
            Harness built = goldenRun();

            assertThat(built.htmlRecords).isEqualTo(expectedHtml());
        }

        @Test
        @DisplayName("one statement is twenty text records: 16 head, 1 detail, 3 tail")
        void theTextRecordCount() {
            assertThat(goldenRun().textRecords).hasSize(20);
        }

        @Test
        @DisplayName("one statement is seventy-five HTML records: 22 header, 34 details, 11 tran, 8 tail")
        void theHtmlRecordCount() {
            assertThat(goldenRun().htmlRecords).hasSize(75);
        }

        @Test
        @DisplayName("the two PERFORM ... THRU ranges are each entered exactly ONCE per statement "
                + "(CBSTM03A.CBL:L461 and L486)")
        void theTwoRangePerformsRunOncePerStatement() {
            // L461  PERFORM 5100-WRITE-HTML-HEADER THRU 5100-EXIT.
            // L486  PERFORM 5200-WRITE-HTML-NMADBS THRU 5200-EXIT.
            // Two of the estate's twelve range PERFORMs, both benign - each names a paragraph and its own
            // matching exit label, so each collapses to a single call. "Once per statement" is what makes
            // them benign, and it is checkable without reaching into the class: 5100 opens with HTML-L01,
            // the document type declaration, and 5200 is the only place a bare WRITE FD-HTMLFILE-REC with
            // no FROM appears (L568) - so counting those two records counts the two entries.
            Harness twoStatements = harness(new ScriptedSubroutine()
                    .reads(StatementGenerationJobA.TRNXFILE_DD,
                            ok(trnxImage(CARD_A, "TRAN000000000001", "ONE", "1.00")),
                            ok(trnxImage(CARD_B, "TRAN000000000002", "TWO", "2.00")), eof())
                    .reads(StatementGenerationJobA.XREFFILE_DD,
                            ok(xrefImage(CARD_A, CUST_ID, ACCT_ID)),
                            ok(xrefImage(CARD_B, CUST_ID, ACCT_ID)), eof())
                    .keyed(StatementGenerationJobA.CUSTFILE_DD, ok(defaultCustImage()),
                            ok(defaultCustImage()))
                    .keyed(StatementGenerationJobA.ACCTFILE_DD, ok(defaultAcctImage()),
                            ok(defaultAcctImage())));

            assertThat(twoStatements.run()).isEqualTo(2);
            assertThat(twoStatements.htmlRecords).hasSize(150);
            assertThat(startingWith(twoStatements.htmlRecords, "<!DOCTYPE html>")).isEqualTo(2);
            assertThat(containing(twoStatements.htmlRecords, "JOHN Q PUBLIC")).isEqualTo(2);

            // One statement: exactly one entry into each range, not two and not none.
            Harness one = goldenRun();
            assertThat(startingWith(one.htmlRecords, "<!DOCTYPE html>")).isEqualTo(1);
            assertThat(containing(one.htmlRecords, "JOHN Q PUBLIC")).isEqualTo(1);

            // 5100 is entered first and its twenty-two records come first, so the document type
            // declaration is record ONE and 5200's bare WRITE is record twenty-THREE.
            assertThat(one.htmlRecords.get(0)).startsWith("<!DOCTYPE html>");
            assertThat(one.htmlRecords.get(22)).contains("JOHN Q PUBLIC");
        }

        @Test
        @DisplayName("every plain-text record is exactly 80 bytes (gates G19, G20)")
        void everyTextRecordIsEightyBytes() {
            // FD-STMTFILE-REC PIC X(80) at CBSTM03A.CBL:L44, and LRECL=80 in BOTH steps that declare the
            // dataset - the pre-delete at app/jcl/CREASTMT.JCL:L73 and the creating step at L89 - so this
            // width has no conflict to resolve.
            //
            // BEWARE OF L90. The card immediately after the creating step's DCB is CORRUPTED: it reads
            // "//         SPACE=(CYL,(1,1),RLSE), 00,RECFM=FB), ATA.VSAM.KSDS" - a SPACE parameter with the
            // tail of some other card overwritten onto it. Everything after the blank that follows the
            // comma is JCL comment, so L90 declares NOTHING and is NOT a second DCB for this dataset. L89
            // is the authoritative one. The fragment "00,RECFM=FB)" in particular looks like an LRECL and
            // is not; reading it as one would be reading a typing accident as a contract.
            assertThat(goldenRun().textRecords).isNotEmpty()
                    .allSatisfy(record -> assertThat(record.getBytes(ASCII))
                            .hasSize(StatementTextWriter.RECORD_LENGTH));
            assertThat(StatementTextWriter.RECORD_LENGTH).isEqualTo(80);
        }

        @Test
        @DisplayName("every HTML record is exactly 100 bytes - the creating step wins, not the pre-delete")
        void everyHtmlRecordIsOneHundredBytes() {
            // The one width in this job with two declarations that disagree (risk R-G, gate G20):
            //   app/jcl/CREASTMT.JCL:L69   STEP030, IEFBR14, DISP=(MOD,DELETE,DELETE)  LRECL=80
            //   app/jcl/CREASTMT.JCL:L94   STEP040, CBSTM03A, DISP=(NEW,CATLG,DELETE)  LRECL=100
            // The CREATING step wins, so the width is ONE HUNDRED. L69 belongs to a step whose only job is
            // to delete last run's generation - its DCB describes what it expects to find, not what this
            // run produces - and FD-HTMLFILE-REC PIC X(100) at CBSTM03A.CBL:L46 settles it independently.
            assertThat(goldenRun().htmlRecords).isNotEmpty()
                    .allSatisfy(record -> assertThat(record.getBytes(ASCII))
                            .hasSize(StatementHtmlWriter.RECORD_LENGTH));
            assertThat(StatementHtmlWriter.RECORD_LENGTH).isEqualTo(100);
            // And the two widths are genuinely different, which is what the pre-delete card obscures.
            assertThat(StatementHtmlWriter.RECORD_LENGTH)
                    .isNotEqualTo(StatementTextWriter.RECORD_LENGTH);
        }

        @Test
        @DisplayName("ST-LINE5 and ST-LINE12 are each written twice in the fifteen-line body")
        void bothRepeatsArePresent() {
            assertThat(StatementGenerationJobA.STATEMENT_BODY_TEXT_LINES).hasSize(15);
            assertThat(StatementGenerationJobA.STATEMENT_BODY_TEXT_LINES)
                    .filteredOn(StatementLine.ST_LINE5::equals).hasSize(2);
            assertThat(StatementGenerationJobA.STATEMENT_BODY_TEXT_LINES)
                    .filteredOn(StatementLine.ST_LINE12::equals).hasSize(2);
            assertThat(StatementGenerationJobA.STATEMENT_BODY_TEXT_LINES).containsExactly(
                    StatementLine.ST_LINE1, StatementLine.ST_LINE2, StatementLine.ST_LINE3,
                    StatementLine.ST_LINE4, StatementLine.ST_LINE5, StatementLine.ST_LINE6,
                    StatementLine.ST_LINE5, StatementLine.ST_LINE7, StatementLine.ST_LINE8,
                    StatementLine.ST_LINE9, StatementLine.ST_LINE10, StatementLine.ST_LINE11,
                    StatementLine.ST_LINE12, StatementLine.ST_LINE13, StatementLine.ST_LINE12);
            assertThat(StatementGenerationJobA.STATEMENT_TOTAL_TEXT_LINES).containsExactly(
                    StatementLine.ST_LINE12, StatementLine.ST_LINE14A, StatementLine.ST_LINE15);
        }

        @Test
        @DisplayName("INITIALIZE does not clear FILLER, so the banner and the rule lines still print")
        void fillerSurvivesInitialize() {
            Harness built = goldenRun();

            // ST-LINE0 is entirely FILLER: 31 asterisks, the caption, 31 more asterisks.
            assertThat(built.textRecords.get(0)).contains("START OF STATEMENT")
                    .startsWith("*".repeat(31));
            // ST-LINE5, ST-LINE10 and ST-LINE12 are rules of 80 hyphens, also entirely FILLER.
            String rule = "-".repeat(StatementTextWriter.RECORD_LENGTH);
            assertThat(built.textRecords).contains(rule);
            assertThat(built.textRecords.stream().filter(rule::equals).count())
                    .isGreaterThanOrEqualTo(4);
        }

        @Test
        @DisplayName("the account id, balance and FICO score reach the statement in their edited forms")
        void theBasicDetailsAreEdited() {
            Harness built = goldenRun();
            String allText = String.join("", built.textRecords);

            assertThat(allText).contains("00000000099");
            assertThat(allText).contains("1234.56");
            assertThat(allText).contains("700");
        }

        @Test
        @DisplayName("the transaction detail line carries the id, the description and the amount")
        void theDetailLine() {
            Harness built = goldenRun();

            assertThat(built.textRecords.get(16)).contains(GOLDEN_TRAN_ID).contains(GOLDEN_DESC)
                    .contains("12.34");
        }

        /**
         * The plain-text sequence one statement produces, transcribed from
         * {@code app/cbl/CBSTM03A.CBL:L459-L502}, {@code L676-L679} and {@code L433-L437}.
         *
         * @return the twenty expected 80-byte records, in order
         */
        private List<String> expectedText() {
            Replay replay = new Replay();
            replay.createStatement();
            replay.writeTrans();
            replay.total();
            return replay.textRecords;
        }

        /**
         * The HTML sequence one statement produces, transcribed from
         * {@code app/cbl/CBSTM03A.CBL:L506-L555}, {@code L558-L672}, {@code L681-L721} and
         * {@code L439-L454}.
         *
         * @return the seventy-five expected 100-byte records, in order
         */
        private List<String> expectedHtml() {
            Replay replay = new Replay();
            replay.createStatement();
            replay.writeTrans();
            replay.total();
            return replay.htmlRecords;
        }
    }

    /**
     * An independent replay of {@code CBSTM03A}'s three writing paragraphs, driving the same public
     * writer API in the order the COBOL states.
     *
     * <p>This is the oracle the golden tests compare against. It deliberately does <strong>not</strong>
     * use {@link StatementGenerationJobA}'s published order constants: it names each line, each field and
     * each delimiter itself, so a reordering in the implementation cannot agree with it by construction.
     */
    private static final class Replay {

        /** Every text record the replay emitted. */
        private final List<String> textRecords = new ArrayList<>();

        /** Every HTML record the replay emitted. */
        private final List<String> htmlRecords = new ArrayList<>();

        /** The plain-text writer. */
        private final StatementTextWriter textWriter = new StatementTextWriter(new JdbcTemplate(),
                ASCII, globalBindings(), RecordImageForm.CHARACTER);

        /** The HTML writer. */
        private final StatementHtmlWriter htmlWriter = new StatementHtmlWriter(new JdbcTemplate(),
                ASCII, globalBindings(), RecordImageForm.CHARACTER);

        /** The plain-text handle. */
        private final StatementFile stmt;

        /** The HTML handle. */
        private final HtmlStatementFile html;

        /** The codec, for the two cross-width moves the paragraphs perform. */
        private final FixedWidthCodec codec = new FixedWidthCodec(ASCII);

        /** The customer whose statement is being replayed. */
        private final Stm03CustomerRecord customer = Stm03CustomerRecord.decode(defaultCustImage(),
                ASCII);

        /** Their account. */
        private final AccountRecord account = AccountRecord.decode(defaultAcctImage(), ASCII);

        /** The one transaction on the statement. */
        private final TrnxRecord trnx = TrnxRecord.decode(
                trnxImage(CARD_A, "TRAN000000000001", "GROCERY STORE PURCHASE", "12.34")
                        .getBytes(ASCII), ASCII);

        Replay() {
            this.stmt = textWriter.openOutput(image -> {
                textRecords.add(new String(image, ASCII));
                return FileStatus.Outcome.OK;
            });
            this.html = htmlWriter.open(record -> {
                htmlRecords.add(new String(record, ASCII));
                return FileStatus.OK;
            });
        }

        /** {@code 5000-CREATE-STATEMENT}, {@code app/cbl/CBSTM03A.CBL:L458-L504}. */
        void createStatement() {
            stmt.initializeStatementLines();                                            // L459
            stmt.writeLine(StatementLine.ST_LINE0);                                     // L460
            writeHtmlHeader();                                                          // L461
            stmt.setName(codec.concatenateDelimitedBySize(                              // L462-L469
                    StatementHtmlWriter.delimitedBy(customer.custFirstName(), " "),
                    StatementHtmlWriter.delimitedBySize(" "),
                    StatementHtmlWriter.delimitedBy(customer.custMiddleName(), " "),
                    StatementHtmlWriter.delimitedBySize(" "),
                    StatementHtmlWriter.delimitedBy(customer.custLastName(), " "),
                    StatementHtmlWriter.delimitedBySize(" ")));
            stmt.setAddressLine1(customer.custAddrLine1());                             // L470
            stmt.setAddressLine2(customer.custAddrLine2());                             // L471
            stmt.setAddressLine3(codec.concatenateDelimitedBySize(                      // L472-L481
                    StatementHtmlWriter.delimitedBy(customer.custAddrLine3(), " "),
                    StatementHtmlWriter.delimitedBySize(" "),
                    StatementHtmlWriter.delimitedBy(customer.custAddrStateCd(), " "),
                    StatementHtmlWriter.delimitedBySize(" "),
                    StatementHtmlWriter.delimitedBy(customer.custAddrCountryCd(), " "),
                    StatementHtmlWriter.delimitedBySize(" "),
                    StatementHtmlWriter.delimitedBy(customer.custAddrZip(), " "),
                    StatementHtmlWriter.delimitedBySize(" ")));
            stmt.setAccountId(account.getAcctId());                                     // L483
            stmt.setCurrentBalance(account.getAcctCurrBal());                           // L484
            stmt.setFicoScore(customer.custFicoCreditScoreValue(ASCII));                // L485
            writeHtmlNmAdBs();                                                          // L486
            for (StatementLine line : List.of(StatementLine.ST_LINE1, StatementLine.ST_LINE2,
                    StatementLine.ST_LINE3, StatementLine.ST_LINE4, StatementLine.ST_LINE5,
                    StatementLine.ST_LINE6, StatementLine.ST_LINE5, StatementLine.ST_LINE7,
                    StatementLine.ST_LINE8, StatementLine.ST_LINE9, StatementLine.ST_LINE10,
                    StatementLine.ST_LINE11, StatementLine.ST_LINE12, StatementLine.ST_LINE13,
                    StatementLine.ST_LINE12)) {                                         // L488-L502
                stmt.writeLine(line);
            }
        }

        /** {@code 5100-WRITE-HTML-HEADER THRU 5100-EXIT}, {@code L506-L555}. */
        private void writeHtmlHeader() {
            for (HtmlFixedLine line : List.of(HtmlFixedLine.HTML_L01, HtmlFixedLine.HTML_L02,
                    HtmlFixedLine.HTML_L03, HtmlFixedLine.HTML_L04, HtmlFixedLine.HTML_L05,
                    HtmlFixedLine.HTML_L06, HtmlFixedLine.HTML_L07, HtmlFixedLine.HTML_L08,
                    HtmlFixedLine.HTML_LTRS, HtmlFixedLine.HTML_L10)) {                 // L508-L527
                htmlWriter.writeFixedLine(html, line);
            }
            htmlWriter.writeAccountHeading(html,                                         // L529-L530
                    codec.movePic9(account.getAcctId(), AccountRecord.ACCT_ID_LENGTH));
            for (HtmlFixedLine line : List.of(HtmlFixedLine.HTML_LTDE, HtmlFixedLine.HTML_LTRE,
                    HtmlFixedLine.HTML_LTRS, HtmlFixedLine.HTML_L15, HtmlFixedLine.HTML_L16,
                    HtmlFixedLine.HTML_L17, HtmlFixedLine.HTML_L18, HtmlFixedLine.HTML_LTDE,
                    HtmlFixedLine.HTML_LTRE, HtmlFixedLine.HTML_LTRS,
                    HtmlFixedLine.HTML_L22_35)) {                                       // L531-L552
                htmlWriter.writeFixedLine(html, line);
            }
        }

        /** {@code 5200-WRITE-HTML-NMADBS THRU 5200-EXIT}, {@code L558-L672}. */
        private void writeHtmlNmAdBs() {
            htmlWriter.writeNameLine(html, stmt.slotImage(StatementSlot.ST_NAME));      // L560-L568
            htmlWriter.writeAddressLine(html, AddressField.ADDRESS_LINE_1,
                    stmt.slotImage(StatementSlot.ST_ADD1));                             // L569-L576
            htmlWriter.writeAddressLine(html, AddressField.ADDRESS_LINE_2,
                    stmt.slotImage(StatementSlot.ST_ADD2));                             // L577-L584
            htmlWriter.writeAddressLine(html, AddressField.ADDRESS_LINE_3,
                    stmt.slotImage(StatementSlot.ST_ADD3));                             // L585-L592
            for (HtmlFixedLine line : List.of(HtmlFixedLine.HTML_LTDE, HtmlFixedLine.HTML_LTRE,
                    HtmlFixedLine.HTML_LTRS, HtmlFixedLine.HTML_L30_42, HtmlFixedLine.HTML_L31,
                    HtmlFixedLine.HTML_LTDE, HtmlFixedLine.HTML_LTRE, HtmlFixedLine.HTML_LTRS,
                    HtmlFixedLine.HTML_L22_35)) {                                       // L594-L611
                htmlWriter.writeFixedLine(html, line);
            }
            htmlWriter.writeBasicDetail(html, BasicDetail.ACCOUNT_ID,
                    stmt.slotImage(StatementSlot.ST_ACCT_ID));                          // L613-L618
            htmlWriter.writeBasicDetail(html, BasicDetail.CURRENT_BALANCE,
                    stmt.slotImage(StatementSlot.ST_CURR_BAL));                         // L619-L625
            htmlWriter.writeBasicDetail(html, BasicDetail.FICO_SCORE,
                    stmt.slotImage(StatementSlot.ST_FICO_SCORE));                       // L626-L633
            for (HtmlFixedLine line : List.of(HtmlFixedLine.HTML_LTDE, HtmlFixedLine.HTML_LTRE,
                    HtmlFixedLine.HTML_LTRS, HtmlFixedLine.HTML_L30_42, HtmlFixedLine.HTML_L43,
                    HtmlFixedLine.HTML_LTDE, HtmlFixedLine.HTML_LTRE, HtmlFixedLine.HTML_LTRS,
                    HtmlFixedLine.HTML_L47, HtmlFixedLine.HTML_L48, HtmlFixedLine.HTML_LTDE,
                    HtmlFixedLine.HTML_L50, HtmlFixedLine.HTML_L51, HtmlFixedLine.HTML_LTDE,
                    HtmlFixedLine.HTML_L53, HtmlFixedLine.HTML_L54, HtmlFixedLine.HTML_LTDE,
                    HtmlFixedLine.HTML_LTRE)) {                                         // L634-L669
                htmlWriter.writeFixedLine(html, line);
            }
        }

        /** {@code 6000-WRITE-TRANS}, {@code L675-L723}. */
        void writeTrans() {
            stmt.setTransactionId(trnx.readTrnxId());                                   // L676
            stmt.setTransactionDetails(trnx.readTrnxDesc());                            // L677
            stmt.setTransactionAmount(trnx.readTrnxAmt());                              // L678
            stmt.writeLine(StatementLine.ST_LINE14);                                    // L679
            htmlWriter.writeFixedLine(html, HtmlFixedLine.HTML_LTRS);                   // L681-L682
            htmlWriter.writeFixedLine(html, HtmlFixedLine.HTML_L58);                    // L684-L685
            htmlWriter.writeTransactionField(html, TransactionField.TRAN_ID,
                    stmt.slotImage(StatementSlot.ST_TRANID));                           // L687-L692
            htmlWriter.writeFixedLine(html, HtmlFixedLine.HTML_LTDE);                   // L693-L694
            htmlWriter.writeFixedLine(html, HtmlFixedLine.HTML_L61);                    // L696-L697
            htmlWriter.writeTransactionField(html, TransactionField.TRAN_DETAILS,
                    stmt.slotImage(StatementSlot.ST_TRANDT));                           // L699-L704
            htmlWriter.writeFixedLine(html, HtmlFixedLine.HTML_LTDE);                   // L705-L706
            htmlWriter.writeFixedLine(html, HtmlFixedLine.HTML_L64);                    // L708-L709
            htmlWriter.writeTransactionField(html, TransactionField.TRAN_AMOUNT,
                    stmt.slotImage(StatementSlot.ST_TRANAMT));                          // L711-L716
            htmlWriter.writeFixedLine(html, HtmlFixedLine.HTML_LTDE);                   // L717-L718
            htmlWriter.writeFixedLine(html, HtmlFixedLine.HTML_LTRE);                   // L720-L721
        }

        /** The closing records of {@code 4000-TRNXFILE-GET}, {@code L433-L454}. */
        void total() {
            stmt.setTotalTransactionAmount(trnx.readTrnxAmt());                         // L433-L434
            for (StatementLine line : List.of(StatementLine.ST_LINE12, StatementLine.ST_LINE14A,
                    StatementLine.ST_LINE15)) {                                         // L435-L437
                stmt.writeLine(line);
            }
            for (HtmlFixedLine line : List.of(HtmlFixedLine.HTML_LTRS, HtmlFixedLine.HTML_L10,
                    HtmlFixedLine.HTML_L75, HtmlFixedLine.HTML_LTDE, HtmlFixedLine.HTML_LTRE,
                    HtmlFixedLine.HTML_L78, HtmlFixedLine.HTML_L79,
                    HtmlFixedLine.HTML_L80)) {                                          // L439-L454
                htmlWriter.writeFixedLine(html, line);
            }
        }
    }

    // =============================================================================================
    // The status matrix (gate G47). Three DIFFERENT shapes in one program, and the differences matter.
    //
    // HOW MANY CALL SITES? StatementGenerationJobA's own class documentation says FILE STATUS is
    // consulted "across ten call sites". Counting app/cbl/CBSTM03A.CBL directly gives THIRTEEN, and the
    // thirteen are enumerable:
    //
    //   CALL 'CBSTM03B'          L351 L377 L401 L734 L746 L769 L787 L805 L835 L860 L877 L893 L909  = 13
    //   EVALUATE WS-M03B-RC      L353 L379 L403 L837                                               =  4
    //   IF WS-M03B-RC='00' OR '04'  L736 L748 L771 L789 L807 L862 L879 L895 L911                   =  9
    //   PERFORM 9999-ABEND-PROGRAM  L361 L385 L409 L741 L753 L776 L794 L812 L846 L867 L884 L900 L916 = 13
    //
    // so there are thirteen delegations, thirteen status checks (4 + 9) and thirteen paths into the
    // abend. BOTH numbers are recorded here on purpose rather than one of them being quietly adopted:
    // "ten" is what the main source's prose says, THIRTEEN is what the source itself says, and thirteen
    // is the figure these tests are built on. If the two are ever reconciled, it is the prose that moves.
    //
    // Where "ten" does hold is over the nine two-way IF guards plus... nothing - it does not hold. The
    // nearest true grouping is 4 opens + 4 closes + 1 first read = NINE IF-shaped guards, and adding the
    // four EVALUATE WHEN OTHER arms gives the thirteen above.
    // =============================================================================================

    /** Which statuses each of the thirteen call sites accepts, and which of them abend. */
    @Nested
    @DisplayName("The FILE STATUS matrix across all thirteen call sites (gate G47)")
    class TheStatusMatrix {

        @Test
        @DisplayName("'00' and '04' are the two statuses every OPEN, every CLOSE and the first read accept")
        void theTwoAcceptedStatuses() {
            assertThat(StatementGenerationJobA.isOkOrRecordLengthConflict(FileStatus.OK)).isTrue();
            assertThat(StatementGenerationJobA
                    .isOkOrRecordLengthConflict(StatementGenerationJobA.STATUS_RECORD_LENGTH_CONFLICT))
                    .isTrue();
            assertThat(StatementGenerationJobA.isOkOrRecordLengthConflict(FileStatus.END_OF_FILE))
                    .isFalse();
            assertThat(StatementGenerationJobA.isOkOrRecordLengthConflict(FileStatus.NOT_FOUND))
                    .isFalse();
            assertThat(StatementGenerationJobA.isOkOrRecordLengthConflict(FileStatus.DUPLICATE))
                    .isFalse();
            assertThat(StatementGenerationJobA.STATUS_RECORD_LENGTH_CONFLICT).isEqualTo("04");
            // A status is never absent: LK-M03B-RC is PIC X(02) and Response refuses a null one, so the
            // guard forwards FileStatus's own contract rather than inventing a null-tolerant answer.
            assertThatNullPointerException().isThrownBy(
                    () -> StatementGenerationJobA.isOkOrRecordLengthConflict(null));
        }

        @ParameterizedTest
        @DisplayName("every OPEN accepts '04' as well as '00'")
        @ValueSource(strings = {"TRNXFILE", "XREFFILE", "CUSTFILE", "ACCTFILE"})
        void everyOpenAcceptsOhFour(String dd) {
            Harness built = harness(oneStatement()
                    .opens(dd, StatementGenerationJobA.STATUS_RECORD_LENGTH_CONFLICT));

            assertThat(built.run()).isEqualTo(1);
            assertThat(built.sysout.lines())
                    .doesNotContain(StatementGenerationJobA.ABENDING_PROGRAM);
        }

        @ParameterizedTest
        @DisplayName("every CLOSE accepts '04' as well as '00'")
        @ValueSource(strings = {"TRNXFILE", "XREFFILE", "CUSTFILE", "ACCTFILE"})
        void everyCloseAcceptsOhFour(String dd) {
            Harness built = harness(oneStatement()
                    .closes(dd, StatementGenerationJobA.STATUS_RECORD_LENGTH_CONFLICT));

            assertThat(built.run()).isEqualTo(1);
            assertThat(built.sysout.lines())
                    .doesNotContain(StatementGenerationJobA.ABENDING_PROGRAM);
        }

        @ParameterizedTest
        @DisplayName("every OPEN rejects '10', '22', '23' and an out-of-band status, each with its own "
                + "literal (CBSTM03A.CBL:L736, L771, L789, L807)")
        @CsvSource({
            "TRNXFILE,10", "TRNXFILE,22", "TRNXFILE,23", "TRNXFILE,34",
            "XREFFILE,10", "XREFFILE,22", "XREFFILE,23", "XREFFILE,34",
            "CUSTFILE,10", "CUSTFILE,22", "CUSTFILE,23", "CUSTFILE,34",
            "ACCTFILE,10", "ACCTFILE,22", "ACCTFILE,23", "ACCTFILE,34",
        })
        void everyOpenRejectsEveryOtherStatus(String dd, String status) {
            // The four OPEN guards accept exactly two statuses, so the other four outcomes FileStatus
            // distinguishes - end of file, duplicate key, not found, and anything else - all take the ELSE
            // arm. Driving all four at all four DDs is the matrix gate G47 asks for, and it also proves the
            // four guards are not sharing one literal: each names its own file.
            Harness built = harness(oneStatement().opens(dd, status));

            assertThatExceptionOfType(AbendException.class).isThrownBy(built::run);
            assertThat(built.sysout.lines()).endsWith(
                    openFailureLiteral(dd),
                    StatementGenerationJobA.RETURN_CODE_PREFIX + status,
                    StatementGenerationJobA.ABENDING_PROGRAM);
        }

        @ParameterizedTest
        @DisplayName("every CLOSE rejects '10', '22', '23' and an out-of-band status, each with its own "
                + "literal (CBSTM03A.CBL:L862, L879, L895, L911)")
        @CsvSource({
            "TRNXFILE,10", "TRNXFILE,22", "TRNXFILE,23", "TRNXFILE,34",
            "XREFFILE,10", "XREFFILE,22", "XREFFILE,23", "XREFFILE,34",
            "CUSTFILE,10", "CUSTFILE,22", "CUSTFILE,23", "CUSTFILE,34",
            "ACCTFILE,10", "ACCTFILE,22", "ACCTFILE,23", "ACCTFILE,34",
        })
        void everyCloseRejectsEveryOtherStatus(String dd, String status) {
            Harness built = harness(oneStatement().closes(dd, status));

            assertThatExceptionOfType(AbendException.class).isThrownBy(built::run);
            assertThat(built.sysout.lines()).endsWith(
                    closeFailureLiteral(dd),
                    StatementGenerationJobA.RETURN_CODE_PREFIX + status,
                    StatementGenerationJobA.ABENDING_PROGRAM);
        }

        @ParameterizedTest
        @DisplayName("the FIRST TRNXFILE read rejects '10', '22', '23' and an out-of-band status - so an "
                + "EMPTY work dataset abends (CBSTM03A.CBL:L748)")
        @ValueSource(strings = {"10", "22", "23", "34"})
        void theFirstTrnxReadRejectsEveryOtherStatus(String status) {
            // The ninth IF-shaped guard, and the one with the most surprising consequence: because L748
            // accepts only '00' and '04', a work dataset with NO records reports '10' on the first read at
            // L746 and the program abends rather than writing an empty statement run. That is not a
            // translation choice - the EVALUATE that does handle '10' is the loop read at L837, four
            // paragraphs later.
            Harness built = harness(new ScriptedSubroutine()
                    .reads(StatementGenerationJobA.TRNXFILE_DD, status(status)));

            assertThatExceptionOfType(AbendException.class).isThrownBy(built::run);
            assertThat(built.sysout.lines()).endsWith(
                    StatementGenerationJobA.ERROR_READING_TRNXFILE,
                    StatementGenerationJobA.RETURN_CODE_PREFIX + status,
                    StatementGenerationJobA.ABENDING_PROGRAM);
        }

        @Test
        @DisplayName("the FIRST TRNXFILE read accepts '04' - the only read in the program that does")
        void theFirstTrnxReadAcceptsOhFour() {
            Harness built = harness(new ScriptedSubroutine().reads(
                    StatementGenerationJobA.TRNXFILE_DD,
                    status(StatementGenerationJobA.STATUS_RECORD_LENGTH_CONFLICT), eof()));

            assertThat(built.run()).isZero();
            assertThat(built.sysout.lines())
                    .doesNotContain(StatementGenerationJobA.ABENDING_PROGRAM);
        }

        @Test
        @DisplayName("the TRNXFILE LOOP read does NOT accept '04' - its EVALUATE has no such arm "
                + "(CBSTM03A.CBL:L837-L847)")
        void theLoopTrnxReadRejectsOhFour() {
            Harness built = harness(new ScriptedSubroutine().reads(
                    StatementGenerationJobA.TRNXFILE_DD,
                    ok(trnxImage(CARD_A, "TRAN000000000001", "ONE", "1.00")),
                    status(StatementGenerationJobA.STATUS_RECORD_LENGTH_CONFLICT)));

            assertThatExceptionOfType(AbendException.class).isThrownBy(built::run);

            assertThat(built.sysout.lines()).endsWith(
                    StatementGenerationJobA.ERROR_READING_TRNXFILE,
                    StatementGenerationJobA.RETURN_CODE_PREFIX + "04",
                    StatementGenerationJobA.ABENDING_PROGRAM);
        }

        @Test
        @DisplayName("the XREFFILE read treats '10' as end of file and anything else as fatal")
        void theXrefReadHasAllThreeArms() {
            Harness endOfFile = harness(new ScriptedSubroutine().reads(
                    StatementGenerationJobA.TRNXFILE_DD,
                    ok(trnxImage(CARD_A, "TRAN000000000001", "ONE", "1.00")), eof()));

            assertThat(endOfFile.run()).isZero();

            Harness fatal = harness(new ScriptedSubroutine()
                    .reads(StatementGenerationJobA.TRNXFILE_DD,
                            ok(trnxImage(CARD_A, "TRAN000000000001", "ONE", "1.00")), eof())
                    .reads(StatementGenerationJobA.XREFFILE_DD, status(FileStatus.DUPLICATE)));

            assertThatExceptionOfType(AbendException.class).isThrownBy(fatal::run);
            assertThat(fatal.sysout.lines()).endsWith(
                    StatementGenerationJobA.ERROR_READING_XREFFILE,
                    StatementGenerationJobA.RETURN_CODE_PREFIX + FileStatus.DUPLICATE,
                    StatementGenerationJobA.ABENDING_PROGRAM);
        }

        /**
         * The {@code ERROR OPENING} literal one DD's guard emits, {@code CBSTM03A.CBL:L739}, {@code L774},
         * {@code L792}, {@code L810}.
         *
         * @param dd the DD name
         * @return that DD's literal
         */
        private String openFailureLiteral(String dd) {
            return switch (dd) {
                case StatementGenerationJobA.TRNXFILE_DD -> StatementGenerationJobA.ERROR_OPENING_TRNXFILE;
                case StatementGenerationJobA.XREFFILE_DD -> StatementGenerationJobA.ERROR_OPENING_XREFFILE;
                case StatementGenerationJobA.CUSTFILE_DD -> StatementGenerationJobA.ERROR_OPENING_CUSTFILE;
                default -> StatementGenerationJobA.ERROR_OPENING_ACCTFILE;
            };
        }

        /**
         * The {@code ERROR CLOSING} literal one DD's guard emits, {@code CBSTM03A.CBL:L865}, {@code L882},
         * {@code L898}, {@code L914}.
         *
         * @param dd the DD name
         * @return that DD's literal
         */
        private String closeFailureLiteral(String dd) {
            return switch (dd) {
                case StatementGenerationJobA.TRNXFILE_DD -> StatementGenerationJobA.ERROR_CLOSING_TRNXFILE;
                case StatementGenerationJobA.XREFFILE_DD -> StatementGenerationJobA.ERROR_CLOSING_XREFFILE;
                case StatementGenerationJobA.CUSTFILE_DD -> StatementGenerationJobA.ERROR_CLOSING_CUSTFILE;
                default -> StatementGenerationJobA.ERROR_CLOSING_ACCTFILE;
            };
        }

        @ParameterizedTest
        @DisplayName("the CUSTFILE keyed read has NO '10' arm, so '10' abends there like anything else")
        @ValueSource(strings = {"10", "04", "23"})
        void theCustFileKeyedReadHasNoEndOfFileArm(String status) {
            Harness built = harness(oneStatement()
                    .replace(ScriptedSubroutine.READ_KEYED, StatementGenerationJobA.CUSTFILE_DD,
                            status(status)));

            assertThatExceptionOfType(AbendException.class).isThrownBy(built::run);
            assertThat(built.sysout.lines()).endsWith(
                    StatementGenerationJobA.ERROR_READING_CUSTFILE,
                    StatementGenerationJobA.RETURN_CODE_PREFIX + status,
                    StatementGenerationJobA.ABENDING_PROGRAM);
        }

        @ParameterizedTest
        @DisplayName("the ACCTFILE keyed read has NO '10' arm either")
        @ValueSource(strings = {"10", "04", "23"})
        void theAcctFileKeyedReadHasNoEndOfFileArm(String status) {
            Harness built = harness(oneStatement()
                    .replace(ScriptedSubroutine.READ_KEYED, StatementGenerationJobA.ACCTFILE_DD,
                            status(status)));

            assertThatExceptionOfType(AbendException.class).isThrownBy(built::run);
            assertThat(built.sysout.lines()).endsWith(
                    StatementGenerationJobA.ERROR_READING_ACCTFILE,
                    StatementGenerationJobA.RETURN_CODE_PREFIX + status,
                    StatementGenerationJobA.ABENDING_PROGRAM);
        }
    }

    // =============================================================================================
    // 9999-ABEND-PROGRAM (gate G35): THIRTEEN callers, three displayed lines each, no ABCODE, no TIMING.
    // The callers are PERFORM 9999-ABEND-PROGRAM at L361 L385 L409 L741 L753 L776 L794 L812 L846 L867
    // L884 L900 L916 - the four EVALUATE WHEN OTHER arms and the nine IF '00' OR '04' ELSE arms. See the
    // status-matrix banner above for why thirteen and not the main source's prose count of ten.
    // =============================================================================================

    /** Every guard that reaches the abend, and what the abend carries. */
    @Nested
    @DisplayName("9999-ABEND-PROGRAM and its thirteen callers (gate G35)")
    class TheAbendPath {

        @ParameterizedTest
        @DisplayName("each of the four ERROR OPENING guards emits its literal, the code, then abends")
        @CsvSource({"TRNXFILE,ERROR OPENING TRNXFILE", "XREFFILE,ERROR OPENING XREFFILE",
                "CUSTFILE,ERROR OPENING CUSTFILE", "ACCTFILE,ERROR OPENING ACCTFILE"})
        void theFourOpenGuards(String dd, String literal) {
            Harness built = harness(oneStatement().opens(dd, FileStatus.NOT_FOUND));

            AbendException abend = catchAbend(built);

            assertThat(built.sysout.lines()).endsWith(literal,
                    StatementGenerationJobA.RETURN_CODE_PREFIX + FileStatus.NOT_FOUND,
                    StatementGenerationJobA.ABENDING_PROGRAM);
            assertAbendShape(abend);
        }

        @ParameterizedTest
        @DisplayName("each of the four ERROR CLOSING guards emits its literal, the code, then abends")
        @CsvSource({"TRNXFILE,ERROR CLOSING TRNXFILE", "XREFFILE,ERROR CLOSING XREFFILE",
                "CUSTFILE,ERROR CLOSING CUSTFILE", "ACCTFILE,ERROR CLOSING ACCTFILE"})
        void theFourCloseGuards(String dd, String literal) {
            Harness built = harness(oneStatement().closes(dd, FileStatus.NOT_FOUND));

            AbendException abend = catchAbend(built);

            assertThat(built.sysout.lines()).endsWith(literal,
                    StatementGenerationJobA.RETURN_CODE_PREFIX + FileStatus.NOT_FOUND,
                    StatementGenerationJobA.ABENDING_PROGRAM);
            assertAbendShape(abend);
        }

        @Test
        @DisplayName("the first TRNXFILE read's guard is a caller of its own - the ninth IF-shaped one, L753")
        void theFirstReadGuard() {
            Harness built = harness(new ScriptedSubroutine()
                    .reads(StatementGenerationJobA.TRNXFILE_DD, status(FileStatus.NOT_FOUND)));

            AbendException abend = catchAbend(built);

            assertThat(built.sysout.lines()).endsWith(
                    StatementGenerationJobA.ERROR_READING_TRNXFILE,
                    StatementGenerationJobA.RETURN_CODE_PREFIX + FileStatus.NOT_FOUND,
                    StatementGenerationJobA.ABENDING_PROGRAM);
            assertAbendShape(abend);
        }

        @Test
        @DisplayName("the abend carries NEITHER an ABCODE nor a TIMING - CEE3ABD has no USING here")
        void neitherAbcodeNorTiming() {
            AbendException abend =
                    catchAbend(harness(oneStatement().opens("TRNXFILE", FileStatus.NOT_FOUND)));

            assertThat(abend.hasAbendCode()).isFalse();
            assertThat(abend.hasTiming()).isFalse();
            assertThat(abend.hasReason()).isTrue();
            assertThat(abend.getProgram()).isEqualTo(StatementGenerationJobA.PROGRAM_ID);
            assertThat(abend.getReturnCode()).isEqualTo(StatementGenerationJobA.ABEND_RETURN_CODE);
        }

        @Test
        @DisplayName("nothing sets RETURN-CODE on the normal path: a clean run throws nothing at all")
        void aCleanRunSetsNoReturnCode() {
            Harness built = harness(oneStatement());

            assertThat(built.run()).isEqualTo(1);
            assertThat(built.sysout.lines())
                    .doesNotContain(StatementGenerationJobA.ABENDING_PROGRAM);
        }

        @Test
        @DisplayName("the twelve guard literals are byte-exact and the abend caption is CEE3ABD's own")
        void theGuardLiterals() {
            assertThat(StatementGenerationJobA.ERROR_OPENING_TRNXFILE)
                    .isEqualTo("ERROR OPENING TRNXFILE");
            assertThat(StatementGenerationJobA.ERROR_OPENING_XREFFILE)
                    .isEqualTo("ERROR OPENING XREFFILE");
            assertThat(StatementGenerationJobA.ERROR_OPENING_CUSTFILE)
                    .isEqualTo("ERROR OPENING CUSTFILE");
            assertThat(StatementGenerationJobA.ERROR_OPENING_ACCTFILE)
                    .isEqualTo("ERROR OPENING ACCTFILE");
            assertThat(StatementGenerationJobA.ERROR_READING_TRNXFILE)
                    .isEqualTo("ERROR READING TRNXFILE");
            assertThat(StatementGenerationJobA.ERROR_READING_XREFFILE)
                    .isEqualTo("ERROR READING XREFFILE");
            assertThat(StatementGenerationJobA.ERROR_READING_CUSTFILE)
                    .isEqualTo("ERROR READING CUSTFILE");
            assertThat(StatementGenerationJobA.ERROR_READING_ACCTFILE)
                    .isEqualTo("ERROR READING ACCTFILE");
            assertThat(StatementGenerationJobA.ERROR_CLOSING_TRNXFILE)
                    .isEqualTo("ERROR CLOSING TRNXFILE");
            assertThat(StatementGenerationJobA.ERROR_CLOSING_XREFFILE)
                    .isEqualTo("ERROR CLOSING XREFFILE");
            assertThat(StatementGenerationJobA.ERROR_CLOSING_CUSTFILE)
                    .isEqualTo("ERROR CLOSING CUSTFILE");
            assertThat(StatementGenerationJobA.ERROR_CLOSING_ACCTFILE)
                    .isEqualTo("ERROR CLOSING ACCTFILE");
            assertThat(StatementGenerationJobA.RETURN_CODE_PREFIX).isEqualTo("RETURN CODE: ");
            assertThat(StatementGenerationJobA.ABENDING_PROGRAM).isEqualTo("ABENDING PROGRAM")
                    .isEqualTo(AbendException.ABEND_DISPLAY_TEXT);
        }

        /**
         * Runs the harness and returns the abend it raised.
         *
         * @param built the harness
         * @return the exception
         */
        private AbendException catchAbend(Harness built) {
            try {
                built.run();
            } catch (AbendException abend) {
                return abend;
            }
            throw new AssertionError("the run was expected to abend and did not");
        }

        /**
         * Asserts the shape every one of the thirteen callers produces.
         *
         * @param abend the exception raised
         */
        private void assertAbendShape(AbendException abend) {
            assertThat(abend.getProgram()).isEqualTo(StatementGenerationJobA.PROGRAM_ID);
            assertThat(abend.getReturnCode()).isEqualTo(StatementGenerationJobA.ABEND_RETURN_CODE);
            assertThat(abend.hasAbendCode()).isFalse();
            assertThat(abend.hasTiming()).isFalse();
        }
    }

    // =============================================================================================
    // COBOL STRING semantics, the two cross-width moves, and the thin tasklet.
    // =============================================================================================

    /** {@code STRING ... DELIMITED BY ' '} and the {@code MOVE}s that follow it. */
    @Nested
    @DisplayName("STRING semantics and the cross-width moves")
    class TheFieldComposition {

        /**
         * Builds one statement over a deliberately chosen customer, and returns the run.
         *
         * @param first   {@code CUST-FIRST-NAME}
         * @param middle  {@code CUST-MIDDLE-NAME}
         * @param last    {@code CUST-LAST-NAME}
         * @return the harness after the run
         */
        private Harness runFor(String first, String middle, String last) {
            ScriptedSubroutine subroutine = new ScriptedSubroutine()
                    .reads(StatementGenerationJobA.TRNXFILE_DD,
                            ok(trnxImage(CARD_A, "TRAN000000000001", "ONE", "1.00")), eof())
                    .reads(StatementGenerationJobA.XREFFILE_DD,
                            ok(xrefImage(CARD_A, CUST_ID, ACCT_ID)), eof())
                    .keyed(StatementGenerationJobA.CUSTFILE_DD, ok(custImage(first, middle, last,
                            "SPRINGFIELD", "IL", "USA", "62704", "700")))
                    .keyed(StatementGenerationJobA.ACCTFILE_DD, ok(defaultAcctImage()));
            Harness built = harness(subroutine);
            built.run();
            return built;
        }

        @Test
        @DisplayName("three space-free name parts are joined with one space between each")
        void spaceFreeNameParts() {
            Harness built = runFor("JOHN", "Q", "PUBLIC");

            assertThat(String.join("", built.textRecords)).contains("JOHN Q PUBLIC ");
        }

        @Test
        @DisplayName("DELIMITED BY ' ' cuts a part at its FIRST internal space")
        void aNameWithAnInternalSpace() {
            Harness built = runFor("MARY ANNE", "Q", "PUBLIC");

            assertThat(String.join("", built.textRecords)).contains("MARY Q PUBLIC ")
                    .doesNotContain("MARY ANNE");
        }

        @Test
        @DisplayName("an all-spaces part transfers nothing, so only the SIZE delimiters remain")
        void anAllSpacesPart() {
            Harness built = runFor("JOHN", " ", "PUBLIC");

            assertThat(String.join("", built.textRecords)).contains("JOHN  PUBLIC ");
        }

        @Test
        @DisplayName("the four-part ST-ADD3 build joins line 3, state, country and ZIP")
        void theFourPartAddressBuild() {
            ScriptedSubroutine subroutine = new ScriptedSubroutine()
                    .reads(StatementGenerationJobA.TRNXFILE_DD,
                            ok(trnxImage(CARD_A, "TRAN000000000001", "ONE", "1.00")), eof())
                    .reads(StatementGenerationJobA.XREFFILE_DD,
                            ok(xrefImage(CARD_A, CUST_ID, ACCT_ID)), eof())
                    .keyed(StatementGenerationJobA.CUSTFILE_DD, ok(custImage("JOHN", "Q", "PUBLIC",
                            "SPRINGFIELD", "IL", "USA", "62704", "700")))
                    .keyed(StatementGenerationJobA.ACCTFILE_DD, ok(defaultAcctImage()));
            Harness built = harness(subroutine);

            built.run();

            assertThat(String.join("", built.textRecords)).contains("SPRINGFIELD IL USA 62704 ");
        }

        @Test
        @DisplayName("ACCT-CURR-BAL loses its tenth integer digit, which is the COBOL's own loss")
        void theBalanceIsTruncatedOnTheLeft() {
            ScriptedSubroutine subroutine = new ScriptedSubroutine()
                    .reads(StatementGenerationJobA.TRNXFILE_DD,
                            ok(trnxImage(CARD_A, "TRAN000000000001", "ONE", "1.00")), eof())
                    .reads(StatementGenerationJobA.XREFFILE_DD,
                            ok(xrefImage(CARD_A, CUST_ID, ACCT_ID)), eof())
                    .keyed(StatementGenerationJobA.CUSTFILE_DD, ok(defaultCustImage()))
                    .keyed(StatementGenerationJobA.ACCTFILE_DD,
                            ok(acctImage(99L, "1234567890.12")));
            Harness built = harness(subroutine);

            built.run();

            // PIC 9(9).99- holds nine integer digits, so the leading 1 is discarded.
            assertThat(String.join("", built.textRecords)).contains("234567890.12")
                    .doesNotContain("1234567890.12");
        }

        @Test
        @DisplayName("a description longer than 49 characters is truncated on the right")
        void theDescriptionIsTruncated() {
            String longDescription = "0123456789012345678901234567890123456789012345678ABCDEFG";
            ScriptedSubroutine subroutine = new ScriptedSubroutine()
                    .reads(StatementGenerationJobA.TRNXFILE_DD,
                            ok(trnxImage(CARD_A, "TRAN000000000001", longDescription, "1.00")), eof())
                    .reads(StatementGenerationJobA.XREFFILE_DD,
                            ok(xrefImage(CARD_A, CUST_ID, ACCT_ID)), eof())
                    .keyed(StatementGenerationJobA.CUSTFILE_DD, ok(defaultCustImage()))
                    .keyed(StatementGenerationJobA.ACCTFILE_DD, ok(defaultAcctImage()));
            Harness built = harness(subroutine);

            built.run();

            assertThat(built.textRecords.get(16))
                    .contains(longDescription.substring(0, 49))
                    .doesNotContain("ABCDEFG");
        }
    }

    /** The step-five tasklet, which must stay a thin adapter (practice B10, gate G51). */
    @Nested
    @DisplayName("The STEP040 tasklet")
    class TheStatementTasklet {

        @Test
        @DisplayName("the tasklet finishes and reports one write per statement")
        void theTaskletFinishes() throws Exception {
            Harness built = harness(oneStatement());
            StepExecution stepExecution =
                    new StepExecution(StatementGenerationJobA.STEP_040, new JobExecution(21L));
            StepContribution contribution = new StepContribution(stepExecution);

            RepeatStatus finished = built.job.statementTasklet().execute(contribution,
                    new ChunkContext(new StepContext(stepExecution)));

            assertThat(finished).isEqualTo(RepeatStatus.FINISHED);
            assertThat(contribution.getWriteCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("the tasklet lets an abend reach the framework rather than swallowing it")
        void theTaskletPropagatesAnAbend() {
            Harness built = harness(oneStatement().opens("TRNXFILE", FileStatus.NOT_FOUND));
            StepExecution stepExecution =
                    new StepExecution(StatementGenerationJobA.STEP_040, new JobExecution(22L));
            StepContribution contribution = new StepContribution(stepExecution);
            ChunkContext chunkContext = new ChunkContext(new StepContext(stepExecution));
            Tasklet tasklet = built.job.statementTasklet();

            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> tasklet.execute(contribution, chunkContext));
        }

        @Test
        @DisplayName("the program is runnable directly, with no JobLauncher and no context in the path")
        void theProgramIsDirectlyRunnable() {
            Harness built = harness(oneStatement());
            CapturedSysout ownSink = new CapturedSysout();

            int statements = built.job.printAccountStatements(ownSink);

            assertThat(statements).isEqualTo(1);
            assertThat(ownSink.lines()).isNotEmpty();
            assertThat(built.sysout.lines()).isEmpty();
        }

        @Test
        @DisplayName("a run needs somewhere to write its displayed lines")
        void aRunNeedsASink() {
            StatementGenerationJobA job = harness(oneStatement()).job;

            assertThatNullPointerException().isThrownBy(() -> job.printAccountStatements(null));
        }
    }

    // =============================================================================================
    // The context-load gate (G3).
    // =============================================================================================

    /** The real application context, under the fixture-backed profile. */
    @Nested
    @SpringBootTest(classes = CardDemoApplication.class)
    @ActiveProfiles("test")
    @DisplayName("The Spring context (gate G3)")
    class TheContextLoad {

        /** The context under test. */
        @Autowired
        private ApplicationContext context;

        /** The configuration bean, resolved by type. */
        @Autowired
        private StatementGenerationJobA job;

        @Test
        @DisplayName("the configuration wires and publishes the CREASTMT job under its own name")
        void theConfigurationWires() {
            assertThat(job).isNotNull();
            assertThat(context.containsBean(StatementGenerationJobA.CONFIGURATION_BEAN_NAME)).isTrue();
            assertThat(context.getBean(StatementGenerationJobA.JOB_NAME, Job.class).getName())
                    .isEqualTo(StatementGenerationJobA.JOB_NAME);
        }

        @Test
        @DisplayName("the contract, the seams and the six STEP040 bindings all resolve from configuration")
        void everythingResolves() {
            assertThat(job.stepContracts()).hasSize(5);
            assertThat(job.jobParameters().isEmpty()).isTrue();
            assertThat(job.sysoutSink()).isNotNull();
            assertThat(job.tiotSource()).isNotNull();
            assertThat(job.datasetUtilityPort()).isNotNull();
            assertThat(job.datasetCharset()).isNotNull();
            for (String dd : StatementGenerationJobA.STEP_040_DD_NAMES) {
                assertThat(job.datasetBinding(dd)).isNotNull();
            }
            assertThat(job.datasetBinding(StatementGenerationJobA.HTMLFILE_DD).recordLength())
                    .isEqualTo(100);
            assertThat(job.datasetBinding(StatementGenerationJobA.STMTFILE_DD).recordLength())
                    .isEqualTo(80);
        }

        @Test
        @DisplayName("the job is published but not launched: spring.batch.job.enabled is false")
        void theJobIsNotLaunched() {
            assertThat(context.getBeansOfType(Job.class)).containsKey(
                    StatementGenerationJobA.JOB_NAME);
            assertThat(context.getEnvironment().getProperty("spring.batch.job.enabled"))
                    .isEqualTo("false");
        }
    }

    // =============================================================================================
    // The abnormal dispositions - the THIRD positional of DISP=(NEW,CATLG,DELETE).
    //
    // app/jcl/CREASTMT.JCL declares that disposition three times in this job: on SORTOUT (:48-49), on
    // STMTFILE (:87-91) and on HTMLFILE (:92-96). Its third positional is DELETE, so a step that does not
    // end normally leaves NO dataset at all - which is a third outcome a rollback cannot express, because
    // every record here is durable as it is written.
    //
    // OUTFILE is deliberately absent from that list: :59 binds it DISP=SHR, so a REPRO that fails part way
    // leaves what it had already loaded, and deleting it would be the wrong disposition entirely.
    // =============================================================================================

    @Nested
    @DisplayName("The abnormal dispositions leave nothing where the JCL says DELETE")
    class TheAbnormalDispositions {

        @Test
        @DisplayName("a run that reaches GOBACK discards neither statement output: CATLG, not DELETE")
        void aCompletedRunDiscardsNothing() {
            Harness built = harness(oneStatement());

            assertThat(built.run()).isEqualTo(1);

            assertThat(built.textDiscards)
                    .as("CATLG is the normal disposition: the statements stay catalogued")
                    .isZero();
            assertThat(built.htmlDiscards).isZero();
            Mockito.verify(built.htmlWriter, Mockito.never())
                    .discardGeneration(Mockito.any(HtmlStatementFile.class));
        }

        @Test
        @DisplayName("an abended run discards both statement generations, each carrying its own count")
        void anAbendedRunDiscardsBothGenerations() {
            // The text sink refuses its second record, so the run abends with statements already written
            // to both datasets - the exact state DISP=(NEW,CATLG,DELETE) exists to remove.
            RefusingHarness built = refusingTextRecord(2);

            assertThatExceptionOfType(AbendException.class).isThrownBy(built::run);

            assertThat(built.textDiscards)
                    .as("STMTFILE is DISP=(NEW,CATLG,DELETE) at CREASTMT.JCL:87")
                    .isOne();
            assertThat(built.htmlDiscards)
                    .as("HTMLFILE is DISP=(NEW,CATLG,DELETE) at CREASTMT.JCL:92")
                    .isOne();
            // Each disposition is given the count its own handle wrote, which is what lets a sink
            // establish that the generation it deletes is the one this run allocated.
            assertThat(built.textDiscardCount).isEqualTo(built.textRecordsOffered);
            assertThat(built.htmlDiscardCount).isEqualTo(built.htmlRecordsOffered);
        }

        @Test
        @DisplayName("both handles are closed before either disposition is applied")
        void theDispositionFollowsTheClose() {
            // z/OS closes the dataset and then applies its disposition. Reversing that would ask the HTML
            // writer to dispose of a handle it still considers open, and would leave the text handle held.
            RefusingHarness built = refusingTextRecord(2);

            assertThatExceptionOfType(AbendException.class).isThrownBy(built::run);

            assertThat(built.textHandle.isOpen()).isFalse();
            assertThat(built.htmlHandle.isOpen()).isFalse();
            assertThat(built.textDiscards).isOne();
            assertThat(built.htmlDiscards).isOne();
        }

        @Test
        @DisplayName("a run that abends at the HTML open discards nothing, because nothing was written")
        void anAbendBeforeTheFirstRecordDiscardsNothing() {
            // On the mainframe the step still allocates and still deletes an empty dataset, so there is no
            // observable difference - and a disposition that issued a delete here would be noise.
            RefusingHarness built = new RefusingHarness(oneStatement(), 0, 0, HTML_REFUSED_STATUS,
                    FileStatus.Outcome.OK, FileStatus.OK);

            assertThatExceptionOfType(AbendException.class).isThrownBy(built::run);

            assertThat(built.textRecordsOffered).isZero();
            assertThat(built.htmlRecordsOffered).isZero();
            assertThat(built.textDiscards).isZero();
            assertThat(built.htmlDiscards).isZero();
        }

        @Test
        @DisplayName("STEP010 discards SORTOUT when its write does not finish (CREASTMT.JCL:48-49)")
        void aFailedSortDiscardsItsOutput() {
            // A half-sorted intermediate file is not a subset of a sorted one - a sort places records only
            // after seeing every key - so leaving it catalogued would leave records in an order the
            // card-break grouping of 1000-MAINLINE is not correct for.
            RecordingUtilityPort utility = new RecordingUtilityPort()
                    .seed(TRANSACT_DSNAME, List.of(tranImage(CARD_B, "TRAN000000000002"),
                            tranImage(CARD_A, "TRAN000000000001")))
                    .refusingWriteTo(TRXFL_SEQ_DSNAME);
            StatementGenerationJobA job = new Harness(oneStatement(), defaultTiot(), jobContracts(),
                    utility).job;

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(job::sortAndReformatTransactions);

            assertThat(utility.operations())
                    .as("the delete follows the failed write, and names SORTOUT's own dataset")
                    .endsWith("WRITE " + TRXFL_SEQ_DSNAME, "DELETE " + TRXFL_SEQ_DSNAME);
            assertThat(utility.contentsOf(TRXFL_SEQ_DSNAME)).isEmpty();
        }

        @Test
        @DisplayName("a disposition that reports it could not discard does not replace the abend reason")
        void aRefusedDispositionDoesNotReplaceTheAbendReason() {
            // Both output datasets are DISP=(NEW,CATLG,DELETE), and this is the case where the delete
            // itself comes back not-OK - the generation may still be catalogued. That has to be said, and
            // it must NOT become the reason the run failed: an operator who is told only that a cleanup
            // failed has lost the reason the statements were never produced.
            RefusingHarness built = refusingTextRecord(2);
            built.discardsRefused = true;

            // Still an AbendException, not whatever a failed cleanup might have raised instead.
            assertThatExceptionOfType(AbendException.class).isThrownBy(built::run);

            assertThat(built.textDiscards)
                    .as("both dispositions were still attempted - a refusal is not a skip")
                    .isOne();
            assertThat(built.htmlDiscards).isOne();
        }

        @Test
        @DisplayName("a run that abends before OPEN OUTPUT has no generation to discard")
        void anAbendBeforeTheOpenHasNoGenerationToDiscard() {
            // CBSTM03A opens STMTFILE and HTMLFILE together at L293. A run that fails before that line
            // holds neither handle, and the cleanup - which runs in a finally, on every path out - must
            // cope with that rather than dereference what was never opened. Nothing is discarded because
            // nothing was ever allocated.
            Harness built = harness(oneStatement());
            Mockito.doThrow(new IllegalStateException("STMTFILE could not be allocated"))
                    .when(built.textWriter).openOutput();

            assertThatExceptionOfType(IllegalStateException.class).isThrownBy(built::run);

            // The cleanup ran - it is in a finally, on every path out - and found neither handle to
            // dispose of, so it disposed of neither rather than failing on a null.
            assertThat(built.textDiscards).isZero();
            assertThat(built.htmlDiscards).isZero();
            Mockito.verify(built.htmlWriter, Mockito.never())
                    .discardGeneration(Mockito.any(HtmlStatementFile.class));
        }

        @Test
        @DisplayName("a sort that fails before writing anything still issues its delete, and removes none")
        void aSortFailingBeforeItsFirstRecordDiscardsNothing() {
            // The other side of aFailedSortDiscardsItsOutput. There the write got half way, so the delete
            // had records to remove; here it got nowhere, so the delete is still issued - the disposition
            // is unconditional - and correctly removes nothing. Issuing it either way is the point: the
            // step cannot know how far the write got.
            RecordingUtilityPort utility = new RecordingUtilityPort()
                    .seed(TRANSACT_DSNAME, List.of(tranImage(CARD_A, "TRAN000000000001")))
                    .refusingWriteTo(TRXFL_SEQ_DSNAME);
            StatementGenerationJobA job = new Harness(oneStatement(), defaultTiot(), jobContracts(),
                    utility).job;

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(job::sortAndReformatTransactions);

            assertThat(utility.operations())
                    .as("the delete follows the failed write whether or not there was anything to delete")
                    .endsWith("WRITE " + TRXFL_SEQ_DSNAME, "DELETE " + TRXFL_SEQ_DSNAME);
            assertThat(utility.contentsOf(TRXFL_SEQ_DSNAME)).isEmpty();
        }

        @Test
        @DisplayName("STEP010 leaves its output catalogued when the sort completes")
        void aCompletedSortKeepsItsOutput() {
            RecordingUtilityPort utility = new RecordingUtilityPort()
                    .seed(TRANSACT_DSNAME, List.of(tranImage(CARD_B, "TRAN000000000002"),
                            tranImage(CARD_A, "TRAN000000000001")));
            StatementGenerationJobA job = new Harness(oneStatement(), defaultTiot(), jobContracts(),
                    utility).job;

            assertThat(job.sortAndReformatTransactions()).isEqualTo(2);

            assertThat(utility.operations()).doesNotContain("DELETE " + TRXFL_SEQ_DSNAME);
            assertThat(utility.contentsOf(TRXFL_SEQ_DSNAME)).hasSize(2);
        }

        @Test
        @DisplayName("STEP020 never discards OUTFILE: CREASTMT.JCL:59 binds it DISP=SHR")
        void theReproTargetIsNeverDiscarded() {
            // The one dataset in this job that must survive a failure part way through. DISP=SHR carries no
            // deletion at all, and an IDCAMS REPRO that stops leaves what it had already loaded.
            RecordingUtilityPort utility = new RecordingUtilityPort()
                    .seed(TRXFL_SEQ_DSNAME, List.of(trnxImage(CARD_A, "TRAN000000000001", "ONE", "1.00")));
            StatementGenerationJobA job = new Harness(oneStatement(), defaultTiot(), jobContracts(),
                    utility).job;

            job.reproSortedFileIntoWorkDataset();

            assertThat(utility.operations()).doesNotContain("DELETE " + TRNXFILE_DSNAME);
        }
    }
}
