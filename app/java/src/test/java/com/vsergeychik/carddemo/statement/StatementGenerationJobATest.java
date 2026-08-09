package com.vsergeychik.carddemo.statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.vsergeychik.carddemo.CardDemoApplication;
import com.vsergeychik.carddemo.account.model.AccountRecord;
import com.vsergeychik.carddemo.card.model.CardXrefRecord;
import com.vsergeychik.carddemo.common.AbendException;
import com.vsergeychik.carddemo.common.CobolDecimal;
import com.vsergeychik.carddemo.common.FileStatus;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.RecordImageForm;
import com.vsergeychik.carddemo.config.BatchConfig;
import com.vsergeychik.carddemo.config.BatchConfig.JobContract;
import com.vsergeychik.carddemo.config.BatchConfig.JobContracts;
import com.vsergeychik.carddemo.config.BatchConfig.JobDatasetBinding;
import com.vsergeychik.carddemo.config.BatchConfig.JobParameterContract;
import com.vsergeychik.carddemo.config.BatchConfig.StepContract;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBinding;
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
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.StepExecution;
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
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;

import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

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
 */
class StatementGenerationJobATest {

    // =============================================================================================
    // Constants. Dataset names mirror src/main/resources/application.yml exactly, because the
    // JdbcDatasetUtilityPort validates them against the z/OS dataset-name grammar.
    // =============================================================================================

    /** The fixtures' code page, named explicitly and never defaulted. */
    private static final Charset ASCII = StandardCharsets.US_ASCII;

    /** {@code AWS.M2.CARDDEMO.TRANSACT.VSAM.KSDS} - the transaction master, {@code SORTIN}'s alias. */
    private static final String TRANSACT_DSNAME = "AWS.M2.CARDDEMO.TRANSACT.VSAM.KSDS";

    /** {@code AWS.M2.CARDDEMO.TRXFL.VSAM.KSDS} - the work cluster, {@code OUTFILE}'s alias. */
    private static final String TRNXFILE_DSNAME = "AWS.M2.CARDDEMO.TRXFL.VSAM.KSDS";

    /** {@code AWS.M2.CARDDEMO.TRXFL.SEQ} - the intermediate sequential file. */
    private static final String TRXFL_SEQ_DSNAME = "AWS.M2.CARDDEMO.TRXFL.SEQ";

    /** {@code AWS.M2.CARDDEMO.CARDXREF.VSAM.KSDS}. */
    private static final String XREFFILE_DSNAME = "AWS.M2.CARDDEMO.CARDXREF.VSAM.KSDS";

    /** {@code AWS.M2.CARDDEMO.ACCTDATA.VSAM.KSDS}. */
    private static final String ACCTFILE_DSNAME = "AWS.M2.CARDDEMO.ACCTDATA.VSAM.KSDS";

    /** {@code AWS.M2.CARDDEMO.CUSTDATA.VSAM.KSDS}. */
    private static final String CUSTFILE_DSNAME = "AWS.M2.CARDDEMO.CUSTDATA.VSAM.KSDS";

    /** {@code AWS.M2.CARDDEMO.STATEMNT.PS} - the eighty-byte plain-text statement. */
    private static final String STMTFILE_DSNAME = "AWS.M2.CARDDEMO.STATEMNT.PS";

    /** {@code AWS.M2.CARDDEMO.STATEMNT.HTML} - the hundred-byte HTML statement. */
    private static final String HTMLFILE_DSNAME = "AWS.M2.CARDDEMO.STATEMNT.HTML";

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
        return List.of(
                new StepContract(StatementGenerationJobA.STEP_DELDEF01, "IDCAMS", false),
                new StepContract(StatementGenerationJobA.STEP_010, "SORT", false),
                new StepContract(StatementGenerationJobA.STEP_020, "IDCAMS", true),
                new StepContract(StatementGenerationJobA.STEP_030, "IEFBR14", true),
                new StepContract(StatementGenerationJobA.STEP_040, StatementGenerationJobA.PROGRAM_ID,
                        true));
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

        @Override
        public int deleteAllRecords(DatasetBinding binding) {
            operations.add("DELETE " + binding.dsname());
            List<String> removed = contents.remove(binding.dsname());
            return removed == null ? 0 : removed.size();
        }

        @Override
        public List<String> readAllRecordImages(DatasetBinding binding) {
            operations.add("READ " + binding.dsname());
            return List.copyOf(contents.getOrDefault(binding.dsname(), List.of()));
        }

        @Override
        public int writeRecordImages(DatasetBinding binding, List<String> recordImages) {
            operations.add("WRITE " + binding.dsname());
            contents.put(binding.dsname(), List.copyOf(recordImages));
            return recordImages.size();
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

        Harness(ScriptedSubroutine subroutine, TiotSource tiotSource, JobContracts contracts,
                RecordingUtilityPort utility) {
            this.subroutine = subroutine;
            this.utility = utility;

            StatementTextWriter realText = new StatementTextWriter(new JdbcTemplate(), ASCII,
                    globalBindings(), RecordImageForm.CHARACTER);
            StatementFile textHandle = realText.openOutput(image -> {
                textRecords.add(new String(image, ASCII));
                return FileStatus.Outcome.OK;
            });
            this.textWriter = Mockito.spy(realText);
            Mockito.doReturn(textHandle).when(this.textWriter).openOutput();

            StatementHtmlWriter realHtml = new StatementHtmlWriter(new JdbcTemplate(), ASCII,
                    globalBindings(), RecordImageForm.CHARACTER);
            HtmlStatementFile htmlHandle = realHtml.open(record -> {
                htmlRecords.add(new String(record, ASCII));
                return FileStatus.OK;
            });
            this.htmlWriter = Mockito.spy(realHtml);
            Mockito.doReturn(htmlHandle).when(this.htmlWriter).open();

            this.job = new StatementGenerationJobA(subroutine, this.textWriter, this.htmlWriter,
                    scaffolding(contracts), ASCII, new JdbcTemplate(), new PresentBean<>(sysout),
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
     * A harness over a scripted subroutine, the default prologue image and the JCL contract.
     *
     * @param subroutine the scripted subroutine
     * @return the harness
     */
    private static Harness harness(ScriptedSubroutine subroutine) {
        return new Harness(subroutine, defaultTiot(), jobContracts(), new RecordingUtilityPort());
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

    /** The job, its five steps and the three {@code COND=(0,NE)} gates. */
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
            failed.setExitStatus(new org.springframework.batch.core.ExitStatus("8"));

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
         * @param absent which position to blank, 0-based over the nine constructor arguments
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
            ObjectProvider<SysoutSink> sysout =
                    absent == 6 ? null : new PresentBean<>(new CapturedSysout());
            ObjectProvider<TiotSource> tiot = absent == 7 ? null : new PresentBean<>(defaultTiot());
            ObjectProvider<DatasetUtilityPort> utility =
                    absent == 8 ? null : new PresentBean<>(new RecordingUtilityPort());
            new StatementGenerationJobA(subroutine, text, html, scaffolding, charset, template, sysout,
                    tiot, utility);
        }

        @ParameterizedTest
        @DisplayName("every one of the nine collaborators is required")
        @ValueSource(ints = {0, 1, 2, 3, 4, 5, 6, 7, 8})
        void everyCollaboratorIsRequired(int absent) {
            assertThatNullPointerException().isThrownBy(() -> buildWithout(absent));
        }

        @Test
        @DisplayName("an absent SYSOUT sink falls back to standard output")
        void anAbsentSysoutSink() {
            StatementGenerationJobA job = new StatementGenerationJobA(new ScriptedSubroutine(),
                    new StatementTextWriter(new JdbcTemplate(), ASCII, globalBindings(),
                            RecordImageForm.CHARACTER),
                    new StatementHtmlWriter(new JdbcTemplate(), ASCII, globalBindings(),
                            RecordImageForm.CHARACTER),
                    scaffolding(jobContracts()), ASCII, new JdbcTemplate(), new AbsentBean<>(),
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
                    scaffolding(jobContracts()), ASCII, new JdbcTemplate(), new AbsentBean<>(),
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
                    scaffolding, ASCII, new JdbcTemplate(), new AbsentBean<>(), new AbsentBean<>(),
                    new AbsentBean<>());

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
                    scaffolding, ASCII, new JdbcTemplate(), new PresentBean<>(new CapturedSysout()),
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
        @DisplayName("both arguments are required")
        void bothArgumentsAreRequired() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new JdbcDatasetUtilityPort(null, ASCII));
            assertThatNullPointerException()
                    .isThrownBy(() -> new JdbcDatasetUtilityPort(new JdbcTemplate(), null));
        }

        @Test
        @DisplayName("a binding naming no dataset is refused at the point of use, not at construction")
        void anUnnamedDataset() {
            JdbcDatasetUtilityPort port = new JdbcDatasetUtilityPort(new JdbcTemplate(), ASCII);
            DatasetBinding unnamed = sequential(null, 80, 8000);

            assertThatIllegalStateException().isThrownBy(() -> port.deleteAllRecords(unnamed))
                    .withMessageContaining("declares no");
        }

        @ParameterizedTest
        @DisplayName("a value that is not a z/OS dataset name is refused")
        @ValueSource(strings = {"../etc/passwd", "lower.case", "TOOLONGAQUALIFIER.X",
                "AWS..CARDDEMO", "1BAD.START",
                "AAAAAAAA.BBBBBBBB.CCCCCCCC.DDDDDDDD.EEEEEEEE.FFFFFFFF"})
        void aMalformedDatasetName(String dsname) {
            JdbcDatasetUtilityPort port = new JdbcDatasetUtilityPort(new JdbcTemplate(), ASCII);
            DatasetBinding malformed = sequential(dsname, 80, 8000);

            assertThatIllegalStateException().isThrownBy(() -> port.deleteAllRecords(malformed))
                    .withMessageContaining("not a z/OS dataset name");
        }

        @Test
        @DisplayName("a binding whose dsname is the empty string is refused too")
        void anEmptyDatasetName() {
            JdbcDatasetUtilityPort port = new JdbcDatasetUtilityPort(new JdbcTemplate(), ASCII);
            DatasetBinding empty = sequential("", 80, 8000);

            assertThatIllegalStateException().isThrownBy(() -> port.deleteAllRecords(empty))
                    .withMessageContaining("declares no");
        }

        @Test
        @DisplayName("writeRecordImages refuses a null record list but accepts an empty one")
        void writeRefusesNull() {
            JdbcDatasetUtilityPort port = new JdbcDatasetUtilityPort(new JdbcTemplate(), ASCII);
            DatasetBinding binding = sequential(STMTFILE_DSNAME, 80, 8000);

            assertThatNullPointerException()
                    .isThrownBy(() -> port.writeRecordImages(binding, null));
            assertThat(port.writeRecordImages(binding, List.of())).isZero();
        }

        @Test
        @DisplayName("read, write and delete move whole datasets through the delimited identifier")
        void theThreeOperationsAgainstARelation() {
            JdbcTemplate template = seededRelation(TRXFL_SEQ_DSNAME,
                    StatementGenerationJobA.WORK_KSDS_RECORD_LENGTH,
                    List.of(trnxImage(CARD_A, "TRAN000000000001", "ONE", "1.00"),
                            trnxImage(CARD_B, "TRAN000000000002", "TWO", "2.00")));
            JdbcDatasetUtilityPort port = new JdbcDatasetUtilityPort(template, ASCII);
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
            JdbcTemplate template = seededRelation(TRXFL_SEQ_DSNAME, 350, List.of("SHORT"));
            JdbcDatasetUtilityPort port = new JdbcDatasetUtilityPort(template, ASCII);

            List<String> read = port.readAllRecordImages(sequential(TRXFL_SEQ_DSNAME, 350, 3500));

            assertThat(read).containsExactly("SHORT" + " ".repeat(345));
        }

        @Test
        @DisplayName("a row carrying no record image at all is a corrupt dataset, and says so")
        void aRowWithNoRecordImage() {
            JdbcTemplate template = seededRelation(TRXFL_SEQ_DSNAME, 350, List.of());
            template.update("INSERT INTO \"" + TRXFL_SEQ_DSNAME + "\" VALUES (?)", (Object) null);
            JdbcDatasetUtilityPort port = new JdbcDatasetUtilityPort(template, ASCII);
            DatasetBinding binding = sequential(TRXFL_SEQ_DSNAME, 350, 3500);

            assertThatIllegalStateException()
                    .isThrownBy(() -> port.readAllRecordImages(binding))
                    .withMessageContaining("row 0");
        }
    }

    /** Distinguishes the in-memory database each seeded test uses, so no two tests share a relation. */
    private static final java.util.concurrent.atomic.AtomicInteger DATABASE_SEQUENCE =
            new java.util.concurrent.atomic.AtomicInteger();

    /**
     * A private in-memory relation with one record-image column, seeded with the given rows.
     *
     * <p>One {@code VARCHAR} column of exactly the record width is the shape a gateway presenting a
     * fixed-width dataset is expected to offer, and it is the shape the JDBC-backed utility port is built
     * for.
     *
     * @param dsname       the dataset name, which is also the relation's delimited identifier
     * @param recordLength the record width
     * @param rows         the record images to insert, in order
     * @return a template over the seeded relation
     */
    private static JdbcTemplate seededRelation(String dsname, int recordLength, List<String> rows) {
        org.springframework.jdbc.datasource.DriverManagerDataSource dataSource =
                new org.springframework.jdbc.datasource.DriverManagerDataSource(
                        "jdbc:h2:mem:stmtjoba" + DATABASE_SEQUENCE.incrementAndGet()
                                + ";DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE", "sa", "");
        dataSource.setDriverClassName("org.h2.Driver");
        JdbcTemplate template = new JdbcTemplate(dataSource);
        template.execute("CREATE TABLE \"" + dsname + "\" (RECORD_IMAGE VARCHAR(" + recordLength
                + "))");
        for (String row : rows) {
            template.update("INSERT INTO \"" + dsname + "\" VALUES (?)", row);
        }
        return template;
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
        @DisplayName("the resolved order is ASSERTED, not assumed: TRNX open, table load, XREF, CUST, ACCT")
        void theResolvedOrderIsAsserted() {
            Harness built = harness(oneStatement());

            int statements = built.run();

            assertThat(statements).isEqualTo(1);
            assertThat(built.subroutine.calls()).containsExactly(
                    // 8100-TRNXFILE-OPEN: open, then the first read.                  L731-L754
                    "OPEN TRNXFILE",
                    "READ TRNXFILE",
                    // 8500-READTRNX-READ: the loop read that reports end of file.      L836-L847
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
        @DisplayName("8599-EXIT records the FINAL card's count, which the loop never closed")
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
        @DisplayName("the four closes run in the order 9100, 9200, 9300, 9400 and then both outputs")
        void theCloseOrder() {
            Harness built = harness(oneStatement());

            built.run();

            assertThat(built.subroutine.calls()).endsWith("CLOSE TRNXFILE", "CLOSE XREFFILE",
                    "CLOSE CUSTFILE", "CLOSE ACCTFILE");
            Mockito.verify(built.htmlWriter).close(Mockito.any(HtmlStatementFile.class));
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
        @DisplayName("every plain-text record is exactly 80 bytes (gates G19, G20)")
        void everyTextRecordIsEightyBytes() {
            assertThat(goldenRun().textRecords).isNotEmpty()
                    .allSatisfy(record -> assertThat(record.getBytes(ASCII))
                            .hasSize(StatementTextWriter.RECORD_LENGTH));
            assertThat(StatementTextWriter.RECORD_LENGTH).isEqualTo(80);
        }

        @Test
        @DisplayName("every HTML record is exactly 100 bytes - the creating step wins, not the pre-delete")
        void everyHtmlRecordIsOneHundredBytes() {
            assertThat(goldenRun().htmlRecords).isNotEmpty()
                    .allSatisfy(record -> assertThat(record.getBytes(ASCII))
                            .hasSize(StatementHtmlWriter.RECORD_LENGTH));
            assertThat(StatementHtmlWriter.RECORD_LENGTH).isEqualTo(100);
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
    // =============================================================================================

    /** Which statuses each of the ten call sites accepts, and which of them abend. */
    @Nested
    @DisplayName("The FILE STATUS matrix across all ten call sites (gate G47)")
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
        @DisplayName("the TRNXFILE LOOP read does NOT accept '04' - its EVALUATE has no such arm")
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
    // 9999-ABEND-PROGRAM (gate G35): ten callers, three displayed lines each, no ABCODE, no TIMING.
    // =============================================================================================

    /** Every guard that reaches the abend, and what the abend carries. */
    @Nested
    @DisplayName("9999-ABEND-PROGRAM and its ten callers (gate G35)")
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
        @DisplayName("the first TRNXFILE read's guard is a distinct tenth caller")
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
         * Asserts the shape every one of the ten callers produces.
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
}
