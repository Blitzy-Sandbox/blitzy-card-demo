package com.vsergeychik.carddemo.transaction;

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
import com.vsergeychik.carddemo.config.BatchConfig.JobParameterContract;
import com.vsergeychik.carddemo.config.BatchConfig.StepContract;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBinding;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBindings;
import com.vsergeychik.carddemo.transaction.TransactionValidationJob.ChunkDelegate;
import com.vsergeychik.carddemo.transaction.TransactionValidationJob.CobolTimestamp;
import com.vsergeychik.carddemo.transaction.TransactionValidationJob.PostingResult;
import com.vsergeychik.carddemo.transaction.TransactionValidationJob.PostingRun;
import com.vsergeychik.carddemo.transaction.TransactionValidationJob.RecordOutcome;
import com.vsergeychik.carddemo.transaction.TransactionValidationJob.RunOutcome;
import com.vsergeychik.carddemo.transaction.TransactionValidationJob.StepScopedChunkDelegate;
import com.vsergeychik.carddemo.transaction.TransactionValidationJob.SysoutSink;
import com.vsergeychik.carddemo.transaction.TransactionValidationJob.WorkingStorage;
import com.vsergeychik.carddemo.transaction.model.DalyTranRecord;
import com.vsergeychik.carddemo.transaction.model.TranCatBalRecord;
import com.vsergeychik.carddemo.transaction.model.TranRecord;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

/**
 * {@link TransactionValidationJob} - {@code CBTRN02C}, the daily-transaction poster of
 * {@code app/jcl/POSTTRAN.jcl}.
 *
 * <p>Nothing here launches a job. Every paragraph is reached through the package-visible
 * {@link PostingRun} or through {@link TransactionValidationJob#postTransactions(SysoutSink)}, exactly as
 * practice B10 and gate G51 require, so a failure names a paragraph rather than a framework. The datasets
 * are real H2 relations of the copybook widths, so the assertions are over the bytes that would reach the
 * dataset.
 *
 * <p>The expected values are <strong>statically derived</strong> from the COBOL, the copybooks and the JCL -
 * no COBOL execution is possible in this environment (AAP 0.7.6, risk R-A).
 *
 * <p>The three preserved defects each have their own assertion, because a defect that is not pinned is a
 * defect somebody will tidy: reason 109 is set and cannot reject; the two count literals differ in their
 * spacing; and {@code 9300-DALYREJS-CLOSE} displays the cross-reference file's status.
 */
class TransactionValidationJobTest {

    private static final Charset ASCII = StandardCharsets.US_ASCII;

    /** H2's own row-identifier pseudo-column: a physical-sequential read follows insertion order. */
    private static final PhysicalSequence ORDINAL = PhysicalSequence.of("_ROWID_");

    private static final AtomicInteger SEQ = new AtomicInteger();

    private static final String DALYTRAN_DS = "TEST.DALYTRAN.PS";
    private static final String TRANSACT_DS = "TEST.TRANSACT.KSDS";
    private static final String SYSTRAN_DS = "TEST.SYSTRAN";
    private static final String XREF_DS = "TEST.CARDXREF.KSDS";
    private static final String XREF_AIX_DS = "TEST.CARDXREF.AIX";
    private static final String DALYREJS_DS = "TEST.DALYREJS";
    private static final String ACCT_DS = "TEST.ACCTDATA.KSDS";
    private static final String TCATBAL_DS = "TEST.TCATBALF.KSDS";
    private static final String COL = "REC";

    /** Every run is stamped from this clock, so the 26-byte {@code TRAN-PROC-TS} is assertable. */
    private static final Clock FIXED = Clock.fixed(
            Instant.parse("2022-07-18T12:34:56.780Z"), ZoneId.of("UTC"));

    /** {@code 2022-07-18-12.34.56.780000} composed by {@code Z-GET-DB2-FORMAT-TIMESTAMP}. */
    private static final String EXPECTED_PROC_TS = "2022-07-18-12.34.56.780000";

    private static final String CARD = "4444333322221111";
    private static final String OTHER_CARD = "5555444433332222";
    private static final long ACCOUNT = 11L;
    private static final int CUSTOMER = 7;
    private static final String TYPE_CD = "01";
    private static final int CAT_CD = 1;

    // ------------------------------------------------------------------------------------- collectors

    /** A {@code SYSOUT} that keeps every line, so the fingerprint can be asserted byte for byte. */
    private static final class CapturedSysout implements SysoutSink {

        private final List<String> lines = new ArrayList<>();

        @Override
        public void write(String line) {
            lines.add(line);
        }

        List<String> lines() {
            return lines;
        }
    }

    /** An {@link ObjectProvider} over one present bean, which is all the constructor needs. */
    private record PresentBean<T>(T bean) implements ObjectProvider<T> {

        @Override
        public T getObject() {
            return bean;
        }
    }

    /** An {@link ObjectProvider} that resolves to no bean, so the default sink is used. */
    private record AbsentBean<T>() implements ObjectProvider<T> {

        @Override
        public T getObject() {
            throw new IllegalStateException("no bean");
        }

        @Override
        public T getIfAvailable() {
            return null;
        }
    }

    // ---------------------------------------------------------------------------------------- wiring

    private static DatasetBindings bindings() {
        DatasetBindings b = new DatasetBindings();
        b.put(DalyTranRepository.DD_NAME, new DatasetBinding(DALYTRAN_DS, "sequential", false, "FB",
                null, 350, "CVTRA06Y", null, null, null, null));
        b.put(TransactionRepository.CICS_FILE_NAME, new DatasetBinding(TRANSACT_DS, "ksds", false, "FB",
                null, 350, "CVTRA05Y", 16, null, null, null));
        b.put(TransactionRepository.INPUT_DD_NAME, new DatasetBinding(TRANSACT_DS, "ksds", false, "FB",
                null, 350, "CVTRA05Y", 16, null, null, null));
        b.put(TransactionRepository.SEQUENTIAL_OUTPUT_DD_NAME, new DatasetBinding(SYSTRAN_DS,
                "sequential", false, "F", 0, 350, "CVTRA05Y", null, null, null, null));
        b.put(CardXrefRepository.BASE_DD_NAME, new DatasetBinding(XREF_DS, "ksds", false, "FB", null, 50,
                "CVACT03Y", null, null, null, null));
        b.put(CardXrefRepository.ALTERNATE_INDEX_DD_NAME, new DatasetBinding(XREF_AIX_DS, "aix-path",
                false, "FB", null, 50, "CVACT03Y", null, null, CardXrefRepository.BASE_DD_NAME,
                CardXrefRepository.EXPECTED_ALTERNATE_KEY_FIELD));
        b.put(CardXrefRepository.BATCH_DD_NAME, new DatasetBinding(XREF_DS, "ksds", false, "FB", null, 50,
                "CVACT03Y", null, null, null, null));
        b.put(DalyRejectWriter.DD_NAME, new DatasetBinding(DALYREJS_DS, "sequential", true, "F", 0, 430,
                null, null, null, null, null));
        b.put(AccountRepository.CICS_FILE_NAME, new DatasetBinding(ACCT_DS, "ksds", false, "FB", null,
                300, "CVACT01Y", 11, null, null, null));
        b.put(AccountRepository.BATCH_DD_NAME, new DatasetBinding(ACCT_DS, "ksds", false, "FB", null, 300,
                "CVACT01Y", 11, null, null, null));
        b.put(TranCatBalRepository.DD_NAME, new DatasetBinding(TCATBAL_DS, "ksds", false, "FB", null, 50,
                "CVTRA01Y", 17, null, null, null));
        return b;
    }

    /**
     * The one job-scoped override {@code application.yml} declares: in {@code POSTTRAN.jcl} the DD name
     * {@code TRANFILE} is the transaction master, stated explicitly as an alias of {@code TRANSACT} because
     * {@code app/jcl/TRANREPT.jcl} binds the same eight characters to a different dataset.
     */
    private static Map<String, JobDatasetBinding> jobScopedDatasets() {
        return Map.of(TransactionValidationJob.TRANFILE_DD_NAME,
                new JobDatasetBinding(TransactionRepository.CICS_FILE_NAME, null, null, false, null, null,
                        null, null, null, null, null, null));
    }

    private static JobContracts contracts() {
        return contracts(TransactionValidationJob.REQUIRED_STEPS, List.of(), jobScopedDatasets());
    }

    private static JobContracts contracts(List<StepContract> steps,
            List<JobParameterContract> parameters, Map<String, JobDatasetBinding> datasets) {
        JobContracts c = new JobContracts();
        c.put(TransactionValidationJob.JOB_KEY, new JobContract(TransactionValidationJob.PROGRAM_ID,
                parameters, steps, null, datasets));
        return c;
    }

    private static BatchConfig scaffolding(JobContracts contracts, DatasetBindings b) {
        return new BatchConfig(new PresentBean<>(Mockito.mock(JobRepository.class)),
                new PresentBean<>(Mockito.mock(PlatformTransactionManager.class)), contracts, b);
    }

    private static JdbcTemplate database() {
        return database(DALYTRAN_DS, TRANSACT_DS, XREF_DS, XREF_AIX_DS, DALYREJS_DS, ACCT_DS, TCATBAL_DS,
                SYSTRAN_DS);
    }

    /**
     * A database holding only the named relations, so a missing one makes the corresponding
     * {@code OPEN} report a permanent error and reach its own {@code 'ERROR OPENING ...'} arm.
     *
     * @param datasets the relations to create
     * @return a template over a private in-memory database
     */
    private static JdbcTemplate database(String... datasets) {
        DriverManagerDataSource ds = new DriverManagerDataSource("jdbc:h2:mem:posttran"
                + SEQ.incrementAndGet() + ";DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE", "sa", "");
        ds.setDriverClassName("org.h2.Driver");
        JdbcTemplate t = new JdbcTemplate(ds);
        for (String dsname : datasets) {
            t.execute("CREATE TABLE \"" + dsname + "\" (" + COL + " VARCHAR(" + widthOf(dsname) + "))");
        }
        return t;
    }

    /**
     * The same database, reached through connections that refuse to close.
     *
     * <p>Three of the six {@code CLOSE} paragraphs - {@code 9000-DALYTRAN-CLOSE},
     * {@code 9100-TRANFILE-CLOSE} and {@code 9200-XREFFILE-CLOSE} - release a cursor rather than describe
     * a relation, so dropping the relation does not make them fail: a cursor already open closes cleanly.
     * The one thing that does make a cursor release report a permanent error is a connection whose
     * {@code close()} refuses, which is what this wrapper produces. Spring's own
     * {@code DataSourceUtils.releaseConnection} logs and swallows a refusal, so every other statement in
     * the test still behaves normally.
     *
     * @param clean a template over the database to wrap
     * @return a template whose connections refuse to close
     */
    private static JdbcTemplate closeRefusing(JdbcTemplate clean) {
        DataSource delegate = Objects.requireNonNull(clean.getDataSource(),
                "the template under test must expose its data source");
        DataSource refusing = (DataSource) Proxy.newProxyInstance(
                TransactionValidationJobTest.class.getClassLoader(),
                new Class<?>[] {DataSource.class },
                (proxy, method, args) -> {
                    Object result = invoke(delegate, method, args);
                    return "getConnection".equals(method.getName()) && result instanceof Connection open
                            ? closeRefusingConnection(open)
                            : result;
                });
        return new JdbcTemplate(refusing);
    }

    private static Connection closeRefusingConnection(Connection delegate) {
        return (Connection) Proxy.newProxyInstance(
                TransactionValidationJobTest.class.getClassLoader(),
                new Class<?>[] {Connection.class },
                (proxy, method, args) -> {
                    if ("close".equals(method.getName())) {
                        throw new SQLException("this test refuses to close the connection, so the cursor "
                                + "release behind a COBOL CLOSE reports a permanent error");
                    }
                    return invoke(delegate, method, args);
                });
    }

    private static Object invoke(Object target, java.lang.reflect.Method method, Object[] args)
            throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException wrapped) {
            throw wrapped.getCause();
        }
    }

    /**
     * Puts a relation back after a test dropped it to force a permanent I/O error.
     *
     * <p>Dropping a relation is the only reliable way to drive the {@code MOVE 12 TO APPL-RESULT} arm of a
     * paragraph whose other arms are unreachable through data alone. Restoring it before the run is
     * released keeps {@link PostingRun#release()} exercising its real cleanup rather than tripping over
     * the deliberate breakage, so the release path stays honestly covered.
     *
     * @param t      the template
     * @param dsname the relation to recreate, empty
     */
    private static void recreate(JdbcTemplate t, String dsname) {
        t.execute("CREATE TABLE IF NOT EXISTS \"" + dsname + "\" (" + COL + " VARCHAR("
                + widthOf(dsname) + "))");
    }

    private static int widthOf(String dsname) {
        if (DALYREJS_DS.equals(dsname)) {
            return 430;
        }
        if (XREF_DS.equals(dsname) || XREF_AIX_DS.equals(dsname) || TCATBAL_DS.equals(dsname)) {
            return 50;
        }
        if (ACCT_DS.equals(dsname)) {
            return 300;
        }
        return 350;
    }

    private static void seed(JdbcTemplate t, String dsname, String image) {
        t.update("INSERT INTO \"" + dsname + "\" VALUES (?)", image);
    }

    private static List<String> rows(JdbcTemplate t, String dsname) {
        return t.queryForList("SELECT " + COL + " FROM \"" + dsname + "\"", String.class);
    }

    private static TransactionValidationJob job(JdbcTemplate t, DatasetBindings b) {
        return job(t, b, contracts(), FIXED);
    }

    private static TransactionValidationJob job(JdbcTemplate t, DatasetBindings b, JobContracts contracts,
            Clock clock) {
        return job(t, b, contracts, clock, new AbsentBean<SysoutSink>());
    }

    /**
     * A job whose {@link SysoutSink} bean is the given collector, so the {@code DISPLAY}s the chunk
     * delegate emits through {@code job.sysoutSink} can be asserted.
     *
     * @param t      the template
     * @param sysout the collector to publish as the sink bean
     * @return the job
     */
    private static TransactionValidationJob jobWithSink(JdbcTemplate t, CapturedSysout sysout) {
        return job(t, bindings(), contracts(), FIXED, new PresentBean<SysoutSink>(sysout));
    }

    private static TransactionValidationJob job(JdbcTemplate t, DatasetBindings b, JobContracts contracts,
            Clock clock, ObjectProvider<SysoutSink> sysoutSink) {
        return new TransactionValidationJob(scaffolding(contracts, b),
                new DalyTranRepository(t, b, ASCII, RecordImageForm.CHARACTER, ORDINAL),
                new CardXrefRepository(t, b, ASCII, RecordImageForm.CHARACTER),
                new AccountRepository(t, b, ASCII, RecordImageForm.CHARACTER),
                new TranCatBalRepository(t, b, ASCII, RecordImageForm.CHARACTER),
                new TransactionRepository(t, b, ASCII, RecordImageForm.CHARACTER, ORDINAL),
                new DalyRejectWriter(t, ASCII, b, RecordImageForm.CHARACTER),
                sysoutSink, clock);
    }

    // ------------------------------------------------------------------------------ record fixtures

    private static DalyTranRecord dalyTran(String id, String cardNum, String amount, String origTs) {
        return dalyTran(id, cardNum, amount, origTs, TYPE_CD, CAT_CD);
    }

    private static DalyTranRecord dalyTran(String id, String cardNum, String amount, String origTs,
            String typeCd, int catCd) {
        DalyTranRecord r = new DalyTranRecord(ASCII);
        r.moveDalytranId(id);
        r.moveDalytranTypeCd(typeCd);
        r.moveDalytranCatCd(catCd);
        r.moveDalytranSource("POS TERM");
        r.moveDalytranDesc("Purchase at Abshire-Lowe");
        r.moveDalytranAmt(new BigDecimal(amount));
        r.moveDalytranMerchantId(485945261L);
        r.moveDalytranMerchantName("Abshire-Lowe");
        r.moveDalytranMerchantCity("North Enoshaven");
        r.moveDalytranMerchantZip("72112");
        r.moveDalytranCardNum(cardNum);
        r.moveDalytranOrigTs(origTs);
        return r;
    }

    private static String dalyTranImage(DalyTranRecord record) {
        return new String(record.encode(ASCII), ASCII);
    }

    private static String xrefImage(String cardNum, long acctId) {
        return new String(new CardXrefRecord(cardNum, CUSTOMER, acctId).encode(ASCII), ASCII);
    }

    private static AccountRecord account(long acctId, String creditLimit, String expiry,
            String cycleCredit, String cycleDebit, String currentBalance) {
        AccountRecord a = new AccountRecord(ASCII);
        a.setAcctId(acctId);
        a.setAcctActiveStatus("Y");
        a.setAcctCurrBal(new BigDecimal(currentBalance));
        a.setAcctCreditLimit(new BigDecimal(creditLimit));
        a.setAcctCashCreditLimit(new BigDecimal("1000.00"));
        a.setAcctOpenDate("2020-01-01");
        a.setAcctExpiraionDate(expiry);
        a.setAcctReissueDate("2023-01-01");
        a.setAcctCurrCycCredit(new BigDecimal(cycleCredit));
        a.setAcctCurrCycDebit(new BigDecimal(cycleDebit));
        a.setAcctAddrZip("12345");
        a.setAcctGroupId("A000000000");
        return a;
    }

    private static String tcatbalImage(long acctId, String typeCd, int catCd, String balance) {
        TranCatBalRecord r = TranCatBalRecord.newInstance(ASCII);
        r.trancatAcctId(acctId).trancatTypeCd(typeCd).trancatCd(catCd)
                .tranCatBal(new BigDecimal(balance));
        return new String(r.encode(), ASCII);
    }

    /**
     * A database whose card, account and balance rows all resolve, so a transaction posts.
     *
     * @param t the template to seed
     */
    private static void seedResolvableAccount(JdbcTemplate t) {
        seed(t, XREF_DS, xrefImage(CARD, ACCOUNT));
        seed(t, ACCT_DS, account(ACCOUNT, "5000.00", "2026-01-01", "100.00", "50.00", "250.00")
                .toFixedWidthString());
        seed(t, TCATBAL_DS, tcatbalImage(ACCOUNT, TYPE_CD, CAT_CD, "1000.00"));
    }

    /**
     * Opens a run's six files and returns it, so a test can drive a single paragraph.
     *
     * @param job    the job
     * @param sysout the collector
     * @return the opened run
     */
    private static PostingRun openedRun(TransactionValidationJob job, CapturedSysout sysout) {
        PostingRun run = job.newRun(sysout);
        run.openFiles();
        return run;
    }

    // =============================================================================================
    // Construction: the contract is proven before any run can start.
    // =============================================================================================

    @Nested
    @DisplayName("Construction - the carddemo.jobs contract is proven, not assumed")
    class Construction {

        @Test
        @DisplayName("the shipped single ungated STEP15 sequence is what the class requires")
        void theShippedStepSequenceIsRequired() {
            assertThat(TransactionValidationJob.REQUIRED_STEPS)
                    .containsExactly(new StepContract("STEP15", "CBTRN02C", false));
            assertThat(job(database(), bindings()).stepContract().name()).isEqualTo("STEP15");
        }

        @Test
        @DisplayName("a second step beside STEP15 is refused: POSTTRAN.jcl has one EXEC card")
        void aSecondStepIsRefused() {
            JobContracts twoSteps = contracts(
                    List.of(new StepContract("STEP15", "CBTRN02C", false),
                            new StepContract("STEP20", "CBTRN02C", false)),
                    List.of(), jobScopedDatasets());
            JdbcTemplate t = database();
            DatasetBindings b = bindings();
            assertThatIllegalStateException()
                    .isThrownBy(() -> job(t, b, twoSteps, FIXED))
                    .withMessageContaining("app/jcl/POSTTRAN.jcl");
        }

        @Test
        @DisplayName("a COND=(0,NE) gate on the only step is refused: POSTTRAN.jcl carries no COND")
        void aGatedStepIsRefused() {
            JobContracts gated = contracts(List.of(new StepContract("STEP15", "CBTRN02C", true)),
                    List.of(), jobScopedDatasets());
            JdbcTemplate t = database();
            DatasetBindings b = bindings();
            assertThatIllegalStateException().isThrownBy(() -> job(t, b, gated, FIXED));
        }

        @Test
        @DisplayName("a declared job parameter is refused: the EXEC card carries no PARM")
        void aDeclaredParameterIsRefused() {
            JobContracts parameterised = contracts(TransactionValidationJob.REQUIRED_STEPS,
                    List.of(new JobParameterContract("parmDate", "string", "2022071800")),
                    jobScopedDatasets());
            JdbcTemplate t = database();
            DatasetBindings b = bindings();
            assertThatIllegalStateException()
                    .isThrownBy(() -> job(t, b, parameterised, FIXED))
                    .withMessageContaining("declares no PARM");
        }

        @Test
        @DisplayName("the declared parameter set really is empty")
        void theParameterSetIsEmpty() {
            assertThat(job(database(), bindings()).jobParameters().isEmpty()).isTrue();
        }

        @Test
        @DisplayName("a TRANFILE bound to another dataset is refused: TRANREPT.jcl binds it elsewhere")
        void aDivergentTranfileIsRefused() {
            DatasetBindings b = bindings();
            b.put(TransactionRepository.INPUT_DD_NAME, new DatasetBinding("TEST.TRANSACT.DALY",
                    "sequential", true, "FB", 0, 350, "CVTRA05Y", null, null, null, null));
            JobContracts noAlias = contracts(TransactionValidationJob.REQUIRED_STEPS, List.of(), Map.of());
            JdbcTemplate t = database();
            assertThatIllegalStateException()
                    .isThrownBy(() -> job(t, b, noAlias, FIXED))
                    .withMessageContaining(TransactionValidationJob.TRANFILE_DD_NAME);
        }

        @Test
        @DisplayName("an XREFFILE bound away from the base cluster is refused")
        void aDivergentXreffileIsRefused() {
            DatasetBindings b = bindings();
            b.put(CardXrefRepository.BATCH_DD_NAME, new DatasetBinding("TEST.SOMETHING.ELSE", "ksds",
                    false, "FB", null, 50, "CVACT03Y", null, null, null, null));
            JdbcTemplate t = database();
            assertThatIllegalStateException().isThrownBy(() -> job(t, b, contracts(), FIXED));
        }

        @Test
        @DisplayName("the Clock is a required collaborator, not one with a system-clock default")
        void theClockIsRequired() {
            JdbcTemplate t = database();
            DatasetBindings b = bindings();
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> job(t, b, contracts(), null))
                    .withMessageContaining("FUNCTION CURRENT-DATE");
        }

        @Test
        @DisplayName("the dataset code page is the repositories' own, never the platform default")
        void theCodePageComesFromTheRepositories() {
            assertThat(job(database(), bindings()).datasetCharset()).isEqualTo(ASCII);
        }

        @Test
        @DisplayName("with no SYSOUT bean the standard-output sink is used, in the dataset code page")
        void theDefaultSinkIsStandardOutput() {
            assertThat(job(database(), bindings()).sysoutSink()).isNotNull();
            assertThat(TransactionValidationJob.standardOutput(ASCII)).isNotNull();
        }

        @Test
        @DisplayName("an injected SYSOUT bean is used in preference to standard output")
        void anInjectedSinkWins() {
            JdbcTemplate t = database();
            DatasetBindings b = bindings();
            CapturedSysout sysout = new CapturedSysout();
            TransactionValidationJob configured = new TransactionValidationJob(
                    scaffolding(contracts(), b),
                    new DalyTranRepository(t, b, ASCII, RecordImageForm.CHARACTER, ORDINAL),
                    new CardXrefRepository(t, b, ASCII, RecordImageForm.CHARACTER),
                    new AccountRepository(t, b, ASCII, RecordImageForm.CHARACTER),
                    new TranCatBalRepository(t, b, ASCII, RecordImageForm.CHARACTER),
                    new TransactionRepository(t, b, ASCII, RecordImageForm.CHARACTER, ORDINAL),
                    new DalyRejectWriter(t, ASCII, b, RecordImageForm.CHARACTER),
                    new PresentBean<>(sysout), FIXED);
            assertThat(configured.sysoutSink()).isSameAs(sysout);
            assertThat(configured.clock()).isSameAs(FIXED);
        }
    }

    // =============================================================================================
    // The Spring Batch surface.
    // =============================================================================================

    @Nested
    @DisplayName("The Spring Batch surface - one job, one chunk step of one item")
    class BatchSurface {

        @Test
        @DisplayName("the job bean name is what BatchConfig derives from the carddemo.jobs key")
        void theJobNameMatchesTheContractKey() {
            assertThat(TransactionValidationJob.JOB_KEY).isEqualTo("transaction-validation-job");
            assertThat(TransactionValidationJob.JOB_NAME).isEqualTo("transactionValidationJob");
        }

        @Test
        @DisplayName("the job is built, non-restartable, and starts the single step")
        void theJobIsBuilt() {
            Job built = job(database(), bindings()).transactionValidationJob();
            assertThat(built.getName()).isEqualTo(TransactionValidationJob.JOB_NAME);
            assertThat(built.isRestartable()).isFalse();
        }

        @Test
        @DisplayName("the step is built and named STEP15")
        void theStepIsBuilt() {
            Step built = job(database(), bindings()).transactionValidationStep();
            assertThat(built.getName()).isEqualTo("STEP15");
        }

        @Test
        @DisplayName("the commit interval is one item, so no chunk boundary can span two records")
        void theCommitIntervalIsOneItem() {
            assertThat(TransactionValidationJob.CHUNK_SIZE).isOne();
        }

        @Test
        @DisplayName("both beans appear in a real Spring context, and no infrastructure bean is added")
        void theBeansWireInASpringContext() {
            TransactionValidationJob instance = job(database(), bindings());
            try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
                context.registerBean(TransactionValidationJob.CONFIGURATION_BEAN_NAME,
                        TransactionValidationJob.class, () -> instance);
                context.refresh();

                assertThat(context.getBean(TransactionValidationJob.JOB_NAME, Job.class).getName())
                        .isEqualTo(TransactionValidationJob.JOB_NAME);
                assertThat(context.getBean(TransactionValidationJob.STEP_BEAN_NAME, Step.class).getName())
                        .isEqualTo(TransactionValidationJob.STEP_NAME);
                assertThat(context.getBeanNamesForType(Job.class)).containsExactly(
                        TransactionValidationJob.JOB_NAME);
                assertThat(context.getBeanNamesForType(Step.class)).containsExactly(
                        TransactionValidationJob.STEP_BEAN_NAME);

                // Boot auto-configures the JobRepository and BatchConfig owns the transaction manager;
                // contributing either here would produce a duplicate and break the context (gate G3).
                assertThat(context.getBeanNamesForType(JobRepository.class)).isEmpty();
                assertThat(context.getBeanNamesForType(PlatformTransactionManager.class)).isEmpty();
            }
        }

        @Test
        @DisplayName("the six DD names are the JCL's, taken from the collaborators rather than restated")
        void theDdNamesAreTheJclsOwn() {
            assertThat(TransactionValidationJob.DALYTRAN_DD_NAME).isEqualTo("DALYTRAN");
            assertThat(TransactionValidationJob.TRANFILE_DD_NAME).isEqualTo("TRANFILE");
            assertThat(TransactionValidationJob.XREFFILE_DD_NAME).isEqualTo("XREFFILE");
            assertThat(TransactionValidationJob.DALYREJS_DD_NAME).isEqualTo("DALYREJS");
            assertThat(TransactionValidationJob.ACCTFILE_DD_NAME).isEqualTo("ACCTFILE");
            assertThat(TransactionValidationJob.TCATBALF_DD_NAME).isEqualTo("TCATBALF");
        }
    }

    // =============================================================================================
    // Z-GET-DB2-FORMAT-TIMESTAMP - app/cbl/CBTRN02C.cbl:692-705.
    // =============================================================================================

    @Nested
    @DisplayName("Z-GET-DB2-FORMAT-TIMESTAMP - 26 bytes, hyphen between the date and the time")
    class Db2Timestamp {

        @Test
        @DisplayName("the composed value is exactly 26 characters")
        void theTimestampIsTwentySixCharacters() {
            assertThat(job(database(), bindings()).db2FormatTimestamp())
                    .hasSize(TransactionValidationJob.DB2_TIMESTAMP_LENGTH);
        }

        @Test
        @DisplayName("the separators are hyphens at 1-based 5, 8 and 11 and dots at 14, 17 and 20")
        void theSeparatorsAreWhereTheRedefinesPutsThem() {
            String composed = job(database(), bindings()).db2FormatTimestamp();
            assertThat(composed.charAt(4)).isEqualTo('-');
            assertThat(composed.charAt(7)).isEqualTo('-');
            // DB2-STREEP-3 at :166 sits between DB2-DD and DB2-HH: the date and the time are joined by a
            // HYPHEN, not by a space. This is the assertion that pins it.
            assertThat(composed.charAt(10)).isEqualTo('-');
            assertThat(composed.charAt(13)).isEqualTo('.');
            assertThat(composed.charAt(16)).isEqualTo('.');
            assertThat(composed.charAt(19)).isEqualTo('.');
        }

        @Test
        @DisplayName("DB2-REST is the literal '0000', so the value is never microsecond-precise")
        void theTailIsTheLiteralZeroes() {
            assertThat(job(database(), bindings()).db2FormatTimestamp()).endsWith("0000");
        }

        @Test
        @DisplayName("the whole value follows the fixed clock, so a parity case can pin it")
        void theWholeValueFollowsTheClock() {
            assertThat(job(database(), bindings()).db2FormatTimestamp()).isEqualTo(EXPECTED_PROC_TS);
        }

        @Test
        @DisplayName("COB-MIL carries hundredths, so 780 milliseconds renders as 78")
        void hundredthsAreTheWholeSubSecondPrecision() {
            assertThat(job(database(), bindings()).currentDate().mil()).isEqualTo("78");
        }

        @Test
        @DisplayName("COBOL-TS is a whole 21-character group item, COB-REST included")
        void theCobolTimestampGroupIsWhole() {
            CobolTimestamp current = job(database(), bindings()).currentDate();
            assertThat(current.image()).hasSize(TransactionValidationJob.COBOL_TIMESTAMP_LENGTH);
            assertThat(current.rest()).isEqualTo("+0000");
        }

        @Test
        @DisplayName("COB-REST renders a negative offset with a leading minus")
        void aNegativeOffsetRendersWithAMinus() {
            Clock chicago = Clock.fixed(Instant.parse("2022-07-18T12:34:56.780Z"),
                    ZoneId.of("America/Chicago"));
            CobolTimestamp current = job(database(), bindings(), contracts(), chicago).currentDate();
            assertThat(current.rest()).isEqualTo("-0500");
            assertThat(current.hh()).isEqualTo("07");
        }

        @Test
        @DisplayName("a component of the wrong width cannot form a timestamp")
        void aShortComponentIsRefused() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new CobolTimestamp("22", "07", "18", "12", "34", "56", "78",
                            "+0000"))
                    .withMessageContaining("COB-YYYY");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new CobolTimestamp("2022", "07", "18", "12", "34", "56", "78", "Z"))
                    .withMessageContaining("COB-REST");
        }

        @Test
        @DisplayName("a null component is refused rather than rendered as the word 'null'")
        void aNullComponentIsRefused() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new CobolTimestamp(null, "07", "18", "12", "34", "56", "78",
                            "+0000"));
        }
    }

    // =============================================================================================
    // The outcome types and their invariants.
    // =============================================================================================

    @Nested
    @DisplayName("The outcome types - a record cannot be both posted and rejected")
    class OutcomeTypes {

        @Test
        @DisplayName("neither posted nor rejected describes no arm of the IF at :211")
        void neitherArmIsRefused() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new RecordOutcome(1L, "T", 0, "", null, false, false, false, false,
                            null))
                    .withMessageContaining("211-216");
        }

        @Test
        @DisplayName("both posted and rejected describes both arms and is refused too")
        void bothArmsAreRefused() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new RecordOutcome(1L, "T", 0, "", null, true, true, false, false,
                            EXPECTED_PROC_TS));
        }

        @Test
        @DisplayName("a posted record whose tested reason is non-zero is refused")
        void aPostedRecordWithAReasonIsRefused() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new RecordOutcome(1L, "T", 102, "OVER", null, true, false, false,
                            false, EXPECTED_PROC_TS))
                    .withMessageContaining("Reason 109");
        }

        @Test
        @DisplayName("a null identifier or description is refused")
        void nullsAreRefused() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new RecordOutcome(1L, null, 0, "", null, true, false, false, false,
                            EXPECTED_PROC_TS));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new RecordOutcome(1L, "T", 0, null, null, true, false, false, false,
                            EXPECTED_PROC_TS));
        }

        @Test
        @DisplayName("the optional views report absence rather than a null")
        void theOptionalViewsReportAbsence() {
            RecordOutcome rejected = new RecordOutcome(1L, "T", 100, "INVALID CARD NUMBER FOUND", null,
                    false, true, false, false, null);
            assertThat(rejected.temporaryBalance()).isEmpty();
            assertThat(rejected.processingTimestamp()).isEmpty();

            RecordOutcome posted = new RecordOutcome(2L, "T", 0, "", new BigDecimal("1.00"), true, false,
                    true, false, EXPECTED_PROC_TS);
            assertThat(posted.temporaryBalance()).contains(new BigDecimal("1.00"));
            assertThat(posted.processingTimestamp()).contains(EXPECTED_PROC_TS);
        }

        @Test
        @DisplayName("a negative counter is refused: both are PIC 9(09)")
        void negativeCountersAreRefused() {
            assertThatIllegalArgumentException().isThrownBy(() -> new RunOutcome(-1L, 0L, 0));
            assertThatIllegalArgumentException().isThrownBy(() -> new RunOutcome(1L, -1L, 0));
        }

        @Test
        @DisplayName("more rejects than records read is refused")
        void tooManyRejectsAreRefused() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new RunOutcome(1L, 2L, 4))
                    .withMessageContaining("can never exceed");
        }

        @Test
        @DisplayName("the return code must follow the reject count, in both directions")
        void theReturnCodeMustFollowTheRejectCount() {
            assertThatIllegalArgumentException().isThrownBy(() -> new RunOutcome(3L, 1L, 0));
            assertThatIllegalArgumentException().isThrownBy(() -> new RunOutcome(3L, 0L, 4));
            assertThat(new RunOutcome(3L, 1L, 4).returnCode()).isEqualTo(4);
            assertThat(new RunOutcome(3L, 0L, 0).returnCode()).isZero();
        }

        @Test
        @DisplayName("a posting result always carries a 26-character TRAN-PROC-TS")
        void aPostingResultCarriesTheTimestamp() {
            assertThat(new PostingResult(true, false, EXPECTED_PROC_TS).procTimestamp())
                    .isEqualTo(EXPECTED_PROC_TS);
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new PostingResult(false, false, "2022-07-18"));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new PostingResult(false, false, null));
        }
    }

    // =============================================================================================
    // WORKING-STORAGE.
    // =============================================================================================

    @Nested
    @DisplayName("WORKING-STORAGE - the register the paragraphs move through")
    class Register {

        @Test
        @DisplayName("APPL-RESULT's two condition names are both reachable")
        void bothConditionNamesAreReachable() {
            WorkingStorage ws = new WorkingStorage();
            assertThat(ws.applAok()).isTrue();
            assertThat(ws.applEof()).isFalse();

            ws.moveToApplResult(TransactionValidationJob.APPL_RESULT_EOF);
            assertThat(ws.applAok()).isFalse();
            assertThat(ws.applEof()).isTrue();

            ws.moveToApplResult(TransactionValidationJob.APPL_RESULT_FATAL);
            assertThat(ws.applAok()).isFalse();
            assertThat(ws.applEof()).isFalse();
            assertThat(ws.applResult()).isEqualTo(12);
        }

        @Test
        @DisplayName("END-OF-FILE starts at 'N' and only MOVE 'Y' changes it")
        void endOfFileStartsAtNo() {
            WorkingStorage ws = new WorkingStorage();
            assertThat(ws.endOfFile()).isEqualTo("N");
            assertThat(ws.endOfFileIsYes()).isFalse();
            ws.moveEndOfFileYes();
            assertThat(ws.endOfFileIsYes()).isTrue();
            assertThat(ws.endOfFile()).isEqualTo("Y");
        }

        @Test
        @DisplayName("the validation trailer resets to 0 and spaces, and a MOVE overwrites it")
        void theTrailerResetsAndOverwrites() {
            WorkingStorage ws = new WorkingStorage();
            ws.moveToValidationTrailer(102, "OVERLIMIT TRANSACTION");
            assertThat(ws.validationFailReasonIsZero()).isFalse();
            assertThat(ws.validationFailReason()).isEqualTo(102);

            // The unguarded second MOVE of :417 - last writer wins.
            ws.moveToValidationTrailer(103, "TRANSACTION RECEIVED AFTER ACCT EXPIRATION");
            assertThat(ws.validationFailReason()).isEqualTo(103);
            assertThat(ws.validationFailReasonDesc())
                    .isEqualTo("TRANSACTION RECEIVED AFTER ACCT EXPIRATION");

            ws.resetValidationTrailer();
            assertThat(ws.validationFailReasonIsZero()).isTrue();
            assertThat(ws.validationFailReasonDesc()).isEmpty();
        }

        @Test
        @DisplayName("the create flag starts at 'N' and both states are reachable")
        void theCreateFlagHasBothStates() {
            WorkingStorage ws = new WorkingStorage();
            assertThat(ws.createTrancatRecIsYes()).isFalse();
            ws.moveCreateTrancatRec();
            assertThat(ws.createTrancatRecIsYes()).isTrue();
            ws.moveDoNotCreateTrancatRec();
            assertThat(ws.createTrancatRecIsYes()).isFalse();
        }

        @Test
        @DisplayName("the counters start at zero and increment one at a time")
        void theCountersIncrement() {
            WorkingStorage ws = new WorkingStorage();
            assertThat(ws.transactionCount()).isZero();
            assertThat(ws.rejectCount()).isZero();
            ws.addOneToTransactionCount();
            ws.addOneToTransactionCount();
            ws.addOneToRejectCount();
            assertThat(ws.transactionCount()).isEqualTo(2L);
            assertThat(ws.rejectCount()).isOne();
        }

        @Test
        @DisplayName("WS-TEMP-BAL is credit minus debit plus amount, at scale 2 truncating")
        void theTemporaryBalanceIsComputedAtScaleTwo() {
            WorkingStorage ws = new WorkingStorage();
            BigDecimal computed = ws.computeTempBal(new BigDecimal("100.00"), new BigDecimal("50.00"),
                    new BigDecimal("25.55"));
            assertThat(computed).isEqualByComparingTo("75.55");
            assertThat(computed.scale()).isEqualTo(CobolDecimal.MONETARY_SCALE);
            assertThat(ws.tempBal()).isEqualTo(computed);
        }

        @Test
        @DisplayName("a fractional third digit is truncated toward zero, never half-rounded")
        void theStoreTruncatesTowardZero() {
            WorkingStorage ws = new WorkingStorage();
            assertThat(ws.computeTempBal(new BigDecimal("0.009"), BigDecimal.ZERO, BigDecimal.ZERO))
                    .isEqualByComparingTo("0.00");
            assertThat(ws.computeTempBal(new BigDecimal("-0.009"), BigDecimal.ZERO, BigDecimal.ZERO))
                    .isEqualByComparingTo("0.00");
        }

        @Test
        @DisplayName("a FILE STATUS item holds exactly two characters")
        void aFileStatusItemIsTwoCharacters() {
            WorkingStorage ws = new WorkingStorage();
            ws.moveDalytranStatus(FileStatus.OK);
            ws.moveTranfileStatus(FileStatus.END_OF_FILE);
            ws.moveXreffileStatus(FileStatus.NOT_FOUND);
            ws.moveDalyrejsStatus(FileStatus.DUPLICATE);
            ws.moveAcctfileStatus(FileStatus.OK);
            ws.moveTcatbalfStatus(FileStatus.OK);
            assertThat(ws.dalytranStatus()).isEqualTo("00");
            assertThat(ws.tranfileStatus()).isEqualTo("10");
            assertThat(ws.xreffileStatus()).isEqualTo("23");
            assertThat(ws.dalyrejsStatus()).isEqualTo("22");
            assertThat(ws.acctfileStatus()).isEqualTo("00");
            assertThat(ws.tcatbalfStatus()).isEqualTo("00");

            assertThatIllegalArgumentException().isThrownBy(() -> ws.moveDalytranStatus("0"));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> ws.moveDalytranStatus(null));
        }
    }

    // =============================================================================================
    // The six OPEN paragraphs - app/cbl/CBTRN02C.cbl:236-343.
    // =============================================================================================

    @Nested
    @DisplayName("The six OPEN paragraphs - in order, each with its own message")
    class OpenLadder {

        @Test
        @DisplayName("all six open cleanly and the banner is not emitted by openFiles itself")
        void allSixOpenCleanly() {
            CapturedSysout sysout = new CapturedSysout();
            PostingRun run = openedRun(job(database(), bindings()), sysout);
            assertThat(sysout.lines()).isEmpty();
            assertThat(run.workingStorage().applAok()).isTrue();
            run.release();
        }

        /**
         * Drops one relation so that DD's open reports a permanent error, and asserts the paragraph's own
         * three-line failure tail plus the abend's {@code CEE3ABD} arguments.
         *
         * @param present the relations to create
         * @param message the {@code DISPLAY} literal the failing paragraph owns
         */
        private void assertOpenFailureReports(List<String> present, String message) {
            JdbcTemplate t = database(present.toArray(String[]::new));
            CapturedSysout sysout = new CapturedSysout();
            PostingRun run = job(t, bindings()).newRun(sysout);
            try {
                assertThatExceptionOfType(AbendException.class)
                        .isThrownBy(run::openFiles)
                        .satisfies(abend -> {
                            assertThat(abend.getReturnCode())
                                    .isEqualTo(AbendException.RETURN_CODE_IO_ERROR);
                            assertThat(abend.getAbendCode())
                                    .hasValue(AbendException.STANDARD_ABEND_CODE);
                            assertThat(abend.getTiming()).hasValue(AbendException.STANDARD_TIMING);
                        });
            } finally {
                run.release();
            }
            assertThat(sysout.lines()).hasSize(3);
            assertThat(sysout.lines().get(0)).isEqualTo(message);
            assertThat(sysout.lines().get(1)).startsWith(FileStatus.DISPLAY_PREFIX);
            assertThat(sysout.lines().get(2)).isEqualTo(AbendException.ABEND_DISPLAY_TEXT);
        }

        @Test
        @DisplayName("0000-DALYTRAN-OPEN reports 'ERROR OPENING DALYTRAN' and abends")
        void theDalytranOpenFailure() {
            assertOpenFailureReports(List.of(TRANSACT_DS, XREF_DS, XREF_AIX_DS, DALYREJS_DS, ACCT_DS,
                    TCATBAL_DS, SYSTRAN_DS), TransactionValidationJob.ERROR_OPENING_DALYTRAN);
        }

        @Test
        @DisplayName("0100-TRANFILE-OPEN reports 'ERROR OPENING TRANSACTION FILE' and abends")
        void theTranfileOpenFailure() {
            assertOpenFailureReports(List.of(DALYTRAN_DS, XREF_DS, XREF_AIX_DS, DALYREJS_DS, ACCT_DS,
                    TCATBAL_DS, SYSTRAN_DS), TransactionValidationJob.ERROR_OPENING_TRANFILE);
        }

        @Test
        @DisplayName("0200-XREFFILE-OPEN reports 'ERROR OPENING CROSS REF FILE' and abends")
        void theXreffileOpenFailure() {
            assertOpenFailureReports(List.of(DALYTRAN_DS, TRANSACT_DS, DALYREJS_DS, ACCT_DS, TCATBAL_DS,
                    SYSTRAN_DS), TransactionValidationJob.ERROR_OPENING_XREFFILE);
        }

        @Test
        @DisplayName("0300-DALYREJS-OPEN reports 'ERROR OPENING DALY REJECTS FILE' - DALY, abbreviated")
        void theDalyrejsOpenFailure() {
            assertOpenFailureReports(List.of(DALYTRAN_DS, TRANSACT_DS, XREF_DS, XREF_AIX_DS, ACCT_DS,
                    TCATBAL_DS, SYSTRAN_DS), TransactionValidationJob.ERROR_OPENING_DALYREJS);
        }

        @Test
        @DisplayName("0400-ACCTFILE-OPEN reports 'ERROR OPENING ACCOUNT MASTER FILE' and abends")
        void theAcctfileOpenFailure() {
            assertOpenFailureReports(List.of(DALYTRAN_DS, TRANSACT_DS, XREF_DS, XREF_AIX_DS, DALYREJS_DS,
                    TCATBAL_DS, SYSTRAN_DS), TransactionValidationJob.ERROR_OPENING_ACCTFILE);
        }

        @Test
        @DisplayName("0500-TCATBALF-OPEN reports 'ERROR OPENING TRANSACTION BALANCE FILE' and abends")
        void theTcatbalfOpenFailure() {
            assertOpenFailureReports(List.of(DALYTRAN_DS, TRANSACT_DS, XREF_DS, XREF_AIX_DS, DALYREJS_DS,
                    ACCT_DS, SYSTRAN_DS), TransactionValidationJob.ERROR_OPENING_TCATBALF);
        }

        @Test
        @DisplayName("the open and close literals differ where the source differs")
        void theOpenAndCloseLiteralsDifferWhereTheSourceDoes() {
            // :247 names the DD; :593 appends FILE.
            assertThat(TransactionValidationJob.ERROR_OPENING_DALYTRAN)
                    .isNotEqualTo(TransactionValidationJob.ERROR_CLOSING_DALYTRAN);
            // :302 abbreviates DALY; :648 spells DAILY.
            assertThat(TransactionValidationJob.ERROR_OPENING_DALYREJS).contains("DALY REJECTS");
            assertThat(TransactionValidationJob.ERROR_CLOSING_DALYREJS).contains("DAILY REJECTS");
            // :320 says MASTER; :666 does not.
            assertThat(TransactionValidationJob.ERROR_OPENING_ACCTFILE).contains("MASTER");
            assertThat(TransactionValidationJob.ERROR_CLOSING_ACCTFILE).doesNotContain("MASTER");
            // :338 and :685 are the one pair that differ in nothing but the verb.
            assertThat(TransactionValidationJob.ERROR_OPENING_TCATBALF)
                    .isEqualTo("ERROR OPENING TRANSACTION BALANCE FILE");
            assertThat(TransactionValidationJob.ERROR_CLOSING_TCATBALF)
                    .isEqualTo("ERROR CLOSING TRANSACTION BALANCE FILE");
        }

        @Test
        @DisplayName("a paragraph cannot run on a run whose files were never opened")
        void anUnopenedRunRefusesEveryParagraph() {
            PostingRun run = job(database(), bindings()).newRun(new CapturedSysout());
            assertThatIllegalStateException().isThrownBy(run::dalytranGetNext)
                    .withMessageContaining("195-200");
            assertThatIllegalStateException().isThrownBy(run::closeFiles);
            // Releasing an unopened run is a no-op, not a failure.
            run.release();
            assertThat(run.closedNormally()).isFalse();
        }
    }

    // =============================================================================================
    // 1000-DALYTRAN-GET-NEXT - app/cbl/CBTRN02C.cbl:345-369.
    // =============================================================================================

    @Nested
    @DisplayName("1000-DALYTRAN-GET-NEXT - three arms and only three")
    class DalytranRead {

        @Test
        @DisplayName("'00' returns the record and leaves END-OF-FILE at 'N'")
        void aSuccessfulReadReturnsTheRecord() {
            JdbcTemplate t = database();
            seed(t, DALYTRAN_DS, dalyTranImage(dalyTran("T0000000000000001", CARD, "10.00",
                    "2022-06-10 19:27:53.000000")));
            CapturedSysout sysout = new CapturedSysout();
            PostingRun run = openedRun(job(t, bindings()), sysout);
            DalyTranRecord read = run.dalytranGetNext();
            assertThat(read).isNotNull();
            assertThat(read.dalytranId()).isEqualTo("T000000000000000 ".substring(0, 16));
            assertThat(run.workingStorage().endOfFileIsYes()).isFalse();
            run.release();
        }

        @Test
        @DisplayName("'10' returns null and moves 'Y' to END-OF-FILE")
        void endOfFileReturnsNull() {
            CapturedSysout sysout = new CapturedSysout();
            PostingRun run = openedRun(job(database(), bindings()), sysout);
            assertThat(run.dalytranGetNext()).isNull();
            assertThat(run.workingStorage().endOfFileIsYes()).isTrue();
            assertThat(run.workingStorage().applEof()).isTrue();
            assertThat(sysout.lines()).isEmpty();
            run.release();
        }

        @Test
        @DisplayName("any other status reports 'ERROR READING DALYTRAN FILE' and abends")
        void aFatalReadAbends() {
            JdbcTemplate t = database();
            // A row narrower than CVTRA06Y's declared 350 bytes is reported as a permanent error rather
            // than padded, which is the WHEN OTHER arm of :350-355.
            seed(t, DALYTRAN_DS, "SHORT ROW");
            CapturedSysout sysout = new CapturedSysout();
            PostingRun run = openedRun(job(t, bindings()), sysout);
            try {
                assertThatExceptionOfType(AbendException.class)
                        .isThrownBy(run::dalytranGetNext)
                        .satisfies(abend -> assertThat(abend.getReturnCode())
                                .isEqualTo(AbendException.RETURN_CODE_IO_ERROR));
            } finally {
                run.release();
            }
            assertThat(sysout.lines()).hasSize(3);
            assertThat(sysout.lines().get(0))
                    .isEqualTo(TransactionValidationJob.ERROR_READING_DALYTRAN);
            assertThat(sysout.lines().get(2)).isEqualTo(AbendException.ABEND_DISPLAY_TEXT);
        }
    }

    // =============================================================================================
    // 1500-VALIDATE-TRAN and its two lookups - app/cbl/CBTRN02C.cbl:370-422. Gate G31.
    // =============================================================================================

    @Nested
    @DisplayName("The validation cascade - one short-circuit, and 103 overwrites 102")
    class Cascade {

        private PostingRun runOver(JdbcTemplate t, CapturedSysout sysout) {
            return openedRun(job(t, bindings()), sysout);
        }

        @Test
        @DisplayName("a resolvable transaction leaves the reason at 0")
        void aValidTransactionPasses() {
            JdbcTemplate t = database();
            seedResolvableAccount(t);
            CapturedSysout sysout = new CapturedSysout();
            PostingRun run = runOver(t, sysout);
            run.validateTran(dalyTran("T1", CARD, "10.00", "2022-06-10 19:27:53.000000"));
            assertThat(run.workingStorage().validationFailReasonIsZero()).isTrue();
            assertThat(run.cardXrefRecord().xrefAcctId()).isEqualTo(ACCOUNT);
            run.release();
        }

        @Test
        @DisplayName("an unknown card number is reason 100 and the account is never read")
        void anUnknownCardIsReasonOneHundred() {
            JdbcTemplate t = database();
            seedResolvableAccount(t);
            CapturedSysout sysout = new CapturedSysout();
            PostingRun run = runOver(t, sysout);
            run.validateTran(dalyTran("T1", OTHER_CARD, "10.00", "2022-06-10 19:27:53.000000"));
            assertThat(run.workingStorage().validationFailReason())
                    .isEqualTo(TransactionValidationJob.REASON_INVALID_CARD_NUMBER);
            assertThat(run.workingStorage().validationFailReasonDesc())
                    .isEqualTo(DalyRejectWriter.DESC_INVALID_CARD_NUMBER);
            // The short-circuit: ACCTFILE-STATUS was never touched, so it still reads '00'.
            assertThat(run.workingStorage().acctfileStatus()).isEqualTo(FileStatus.OK);
            // And the retained cross-reference area was not replaced.
            assertThat(run.cardXrefRecord().xrefAcctId()).isZero();
            run.release();
        }

        @Test
        @DisplayName("a card that resolves to a missing account is reason 101")
        void aMissingAccountIsReasonOneHundredAndOne() {
            JdbcTemplate t = database();
            seed(t, XREF_DS, xrefImage(CARD, 99L));
            CapturedSysout sysout = new CapturedSysout();
            PostingRun run = runOver(t, sysout);
            run.validateTran(dalyTran("T1", CARD, "10.00", "2022-06-10 19:27:53.000000"));
            assertThat(run.workingStorage().validationFailReason())
                    .isEqualTo(TransactionValidationJob.REASON_ACCOUNT_RECORD_NOT_FOUND);
            assertThat(run.workingStorage().validationFailReasonDesc())
                    .isEqualTo(DalyRejectWriter.DESC_ACCOUNT_RECORD_NOT_FOUND);
            run.release();
        }

        @Test
        @DisplayName("over the credit limit alone is reason 102")
        void overTheLimitAloneIsReasonOneHundredAndTwo() {
            JdbcTemplate t = database();
            seed(t, XREF_DS, xrefImage(CARD, ACCOUNT));
            seed(t, ACCT_DS, account(ACCOUNT, "100.00", "2026-01-01", "90.00", "0.00", "0.00")
                    .toFixedWidthString());
            CapturedSysout sysout = new CapturedSysout();
            PostingRun run = runOver(t, sysout);
            run.validateTran(dalyTran("T1", CARD, "50.00", "2022-06-10 19:27:53.000000"));
            assertThat(run.workingStorage().validationFailReason())
                    .isEqualTo(TransactionValidationJob.REASON_OVERLIMIT_TRANSACTION);
            assertThat(run.workingStorage().validationFailReasonDesc())
                    .isEqualTo(DalyRejectWriter.DESC_OVERLIMIT_TRANSACTION);
            // WS-TEMP-BAL = 90.00 - 0.00 + 50.00 = 140.00, which exceeds the 100.00 limit.
            assertThat(run.workingStorage().tempBal()).isEqualByComparingTo("140.00");
            run.release();
        }

        @Test
        @DisplayName("the credit-limit test passes when the limit equals the temporary balance")
        void anExactLimitPasses() {
            JdbcTemplate t = database();
            seed(t, XREF_DS, xrefImage(CARD, ACCOUNT));
            seed(t, ACCT_DS, account(ACCOUNT, "140.00", "2026-01-01", "90.00", "0.00", "0.00")
                    .toFixedWidthString());
            CapturedSysout sysout = new CapturedSysout();
            PostingRun run = runOver(t, sysout);
            run.validateTran(dalyTran("T1", CARD, "50.00", "2022-06-10 19:27:53.000000"));
            assertThat(run.workingStorage().validationFailReasonIsZero()).isTrue();
            run.release();
        }

        @Test
        @DisplayName("past the expiry date alone is reason 103")
        void pastExpiryAloneIsReasonOneHundredAndThree() {
            JdbcTemplate t = database();
            seed(t, XREF_DS, xrefImage(CARD, ACCOUNT));
            seed(t, ACCT_DS, account(ACCOUNT, "5000.00", "2022-01-01", "0.00", "0.00", "0.00")
                    .toFixedWidthString());
            CapturedSysout sysout = new CapturedSysout();
            PostingRun run = runOver(t, sysout);
            run.validateTran(dalyTran("T1", CARD, "10.00", "2022-06-10 19:27:53.000000"));
            assertThat(run.workingStorage().validationFailReason())
                    .isEqualTo(TransactionValidationJob.REASON_TRANSACTION_AFTER_EXPIRATION);
            assertThat(run.workingStorage().validationFailReasonDesc())
                    .isEqualTo(DalyRejectWriter.DESC_TRANSACTION_AFTER_EXPIRATION);
            run.release();
        }

        @Test
        @DisplayName("the expiry test passes when the dates are equal - the comparison is >=")
        void anEqualExpiryDatePasses() {
            JdbcTemplate t = database();
            seed(t, XREF_DS, xrefImage(CARD, ACCOUNT));
            seed(t, ACCT_DS, account(ACCOUNT, "5000.00", "2022-06-10", "0.00", "0.00", "0.00")
                    .toFixedWidthString());
            CapturedSysout sysout = new CapturedSysout();
            PostingRun run = runOver(t, sysout);
            run.validateTran(dalyTran("T1", CARD, "10.00", "2022-06-10 19:27:53.000000"));
            assertThat(run.workingStorage().validationFailReasonIsZero()).isTrue();
            run.release();
        }

        @Test
        @DisplayName("GATE G31: over limit AND expired is reason 103 - the second MOVE overwrites 102")
        void bothFailuresYieldOneHundredAndThree() {
            JdbcTemplate t = database();
            seed(t, XREF_DS, xrefImage(CARD, ACCOUNT));
            seed(t, ACCT_DS, account(ACCOUNT, "100.00", "2022-01-01", "90.00", "0.00", "0.00")
                    .toFixedWidthString());
            CapturedSysout sysout = new CapturedSysout();
            PostingRun run = runOver(t, sysout);
            run.validateTran(dalyTran("T1", CARD, "50.00", "2022-06-10 19:27:53.000000"));

            // app/cbl/CBTRN02C.cbl:414 is NOT guarded by :407, so :417 overwrites the 102 that :410
            // stored. Last writer wins. An early return or an else after the credit-limit failure would
            // make this assertion fail, which is exactly why it is here.
            assertThat(run.workingStorage().validationFailReason())
                    .isEqualTo(TransactionValidationJob.REASON_TRANSACTION_AFTER_EXPIRATION);
            assertThat(run.workingStorage().validationFailReasonDesc())
                    .isEqualTo(DalyRejectWriter.DESC_TRANSACTION_AFTER_EXPIRATION);
            run.release();
        }

        @Test
        @DisplayName("the expiry comparison is a plain character comparison, not a parsed date")
        void theExpiryComparisonIsCharacterWise() {
            JdbcTemplate t = database();
            seed(t, XREF_DS, xrefImage(CARD, ACCOUNT));
            // A blank expiry date is not a date at all; a character comparison simply loses to any
            // timestamp, which is what the COBOL does. Parsing would throw instead.
            seed(t, ACCT_DS, account(ACCOUNT, "5000.00", "          ", "0.00", "0.00", "0.00")
                    .toFixedWidthString());
            CapturedSysout sysout = new CapturedSysout();
            PostingRun run = runOver(t, sysout);
            run.validateTran(dalyTran("T1", CARD, "10.00", "2022-06-10 19:27:53.000000"));
            assertThat(run.workingStorage().validationFailReason())
                    .isEqualTo(TransactionValidationJob.REASON_TRANSACTION_AFTER_EXPIRATION);
            run.release();
        }

        @Test
        @DisplayName("the misspelled ACCT-EXPIRAION-DATE is the field that is compared")
        void theMisspelledFieldIsTheOneCompared() {
            AccountRecord a = account(ACCOUNT, "5000.00", "2026-12-31", "0.00", "0.00", "0.00");
            assertThat(a.getAcctExpiraionDate()).isEqualTo("2026-12-31");
            assertThat(a.rawAcctExpiraionDate()).isEqualTo("2026-12-31");
        }

        @Test
        @DisplayName("the reason is reset before every record, so none leaks forward")
        void theReasonIsResetBeforeEveryRecord() {
            JdbcTemplate t = database();
            seedResolvableAccount(t);
            // Two records: the first has an unknown card, the second resolves. The second must post.
            seed(t, DALYTRAN_DS, dalyTranImage(dalyTran("T000000000000001", OTHER_CARD, "10.00",
                    "2022-06-10 19:27:53.000000")));
            seed(t, DALYTRAN_DS, dalyTranImage(dalyTran("T000000000000002", CARD, "10.00",
                    "2022-06-10 19:27:53.000000")));
            CapturedSysout sysout = new CapturedSysout();
            PostingRun run = runOver(t, sysout);

            RecordOutcome first = run.processRecord(run.dalytranGetNext());
            RecordOutcome second = run.processRecord(run.dalytranGetNext());
            assertThat(first.rejected()).isTrue();
            assertThat(first.validationFailReason())
                    .isEqualTo(TransactionValidationJob.REASON_INVALID_CARD_NUMBER);
            assertThat(second.posted()).isTrue();
            assertThat(second.validationFailReason()).isEqualTo(TransactionValidationJob.REASON_NONE);
            assertThat(second.validationFailReasonDesc()).isEmpty();
            run.release();
        }

        @Test
        @DisplayName("a record rejected before the account was read reports no temporary balance")
        void aRecordRejectedBeforeTheAccountReadHasNoTemporaryBalance() {
            JdbcTemplate t = database();
            // The card resolves, so stage 1 passes; the account does not exist, so stage 2 sets 101 and
            // the COMPUTE at :403 is never reached.
            seed(t, XREF_DS, xrefImage(CARD, ACCOUNT));
            seed(t, DALYTRAN_DS, dalyTranImage(dalyTran("T000000000000001", CARD, "10.00",
                    "2022-06-10 19:27:53.000000")));
            seed(t, DALYTRAN_DS, dalyTranImage(dalyTran("T000000000000002", OTHER_CARD, "10.00",
                    "2022-06-10 19:27:53.000000")));
            CapturedSysout sysout = new CapturedSysout();
            PostingRun run = runOver(t, sysout);

            RecordOutcome missingAccount = run.processRecord(run.dalytranGetNext());
            RecordOutcome missingCard = run.processRecord(run.dalytranGetNext());
            run.release();

            assertThat(missingAccount.validationFailReason())
                    .isEqualTo(TransactionValidationJob.REASON_ACCOUNT_RECORD_NOT_FOUND);
            assertThat(missingAccount.temporaryBalance()).isEmpty();
            assertThat(missingAccount.tempBal()).isNull();
            assertThat(missingCard.validationFailReason())
                    .isEqualTo(TransactionValidationJob.REASON_INVALID_CARD_NUMBER);
            assertThat(missingCard.temporaryBalance()).isEmpty();
            assertThat(missingAccount.processingTimestamp()).isEmpty();
        }

        @Test
        @DisplayName("PRESERVED WINDOW: an ACCTFILE status that is neither found nor '23' takes no arm")
        void anUnguardedAccountStatusTakesNeitherArm() {
            JdbcTemplate t = database();
            seed(t, XREF_DS, xrefImage(CARD, ACCOUNT));
            CapturedSysout sysout = new CapturedSysout();
            PostingRun run = runOver(t, sysout);
            DalyTranRecord item = dalyTran("T000000000000001", CARD, "10.00",
                    "2022-06-10 19:27:53.000000");
            // 1500-B-LOOKUP-ACCT has only the two READ phrases and no status guard, so a permanent
            // error takes neither: the reason stays 0 and the record area is left as it was.
            t.execute("DROP TABLE \"" + ACCT_DS + "\"");
            try {
                run.validateTran(item);
            } finally {
                recreate(t, ACCT_DS);
                run.release();
            }
            assertThat(run.workingStorage().validationFailReason())
                    .isEqualTo(TransactionValidationJob.REASON_NONE);
            assertThat(run.workingStorage().validationFailReasonDesc()).isEmpty();
            assertThat(run.workingStorage().acctfileStatus())
                    .isEqualTo(TransactionValidationJob.PERMANENT_ERROR_STATUS);
            // No DISPLAY: the paragraph has no diagnostic for a status it does not test.
            assertThat(sysout.lines()).isEmpty();
        }

        @Test
        @DisplayName("processRecord refuses a null record and an unopened run")
        void processRecordRefusesTheImpossible() {
            JdbcTemplate t = database();
            CapturedSysout sysout = new CapturedSysout();
            PostingRun opened = runOver(t, sysout);
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> opened.processRecord(null));
            opened.release();

            PostingRun unopened = job(t, bindings()).newRun(sysout);
            DalyTranRecord item = dalyTran("T1", CARD, "1.00", "2022-06-10 19:27:53.000000");
            assertThatIllegalStateException().isThrownBy(() -> unopened.processRecord(item));
        }
    }

    // =============================================================================================
    // 2000-POST-TRANSACTION and the three paragraphs it performs - app/cbl/CBTRN02C.cbl:424-579.
    // =============================================================================================

    @Nested
    @DisplayName("The posting sequence - TCATBAL, then the account, then the master")
    class Posting {

        @Test
        @DisplayName("the posted record is exactly 350 bytes with its trailing FILLER intact")
        void thePostedRecordIsThreeHundredAndFiftyBytes() {
            JdbcTemplate t = database();
            seedResolvableAccount(t);
            CapturedSysout sysout = new CapturedSysout();
            PostingRun run = openedRun(job(t, bindings()), sysout);
            DalyTranRecord item = dalyTran("T000000000000001", CARD, "25.55",
                    "2022-06-10 19:27:53.000000");
            run.validateTran(item);
            run.postTransaction(item);
            run.release();

            List<String> written = rows(t, TRANSACT_DS);
            assertThat(written).hasSize(1);
            assertThat(written.get(0)).hasSize(TranRecord.RECORD_LENGTH);
            TranRecord stored = TranRecord.decode(written.get(0), ASCII);
            assertThat(stored.filler()).isEqualTo(" ".repeat(TranRecord.FILLER_LENGTH));
        }

        @Test
        @DisplayName("all twelve fields are copied, and TRAN-PROC-TS comes from the clock")
        void allTwelveFieldsAreCopied() {
            JdbcTemplate t = database();
            seedResolvableAccount(t);
            CapturedSysout sysout = new CapturedSysout();
            PostingRun run = openedRun(job(t, bindings()), sysout);
            DalyTranRecord item = dalyTran("T000000000000001", CARD, "25.55",
                    "2022-06-10 19:27:53.000000");
            run.validateTran(item);
            PostingResult result = run.postTransaction(item);
            run.release();

            TranRecord stored = TranRecord.decode(rows(t, TRANSACT_DS).get(0), ASCII);
            assertThat(stored.tranId()).isEqualTo(item.dalytranId());
            assertThat(stored.tranTypeCd()).isEqualTo(item.dalytranTypeCd());
            assertThat(stored.tranCatCd()).isEqualTo(item.dalytranCatCd());
            assertThat(stored.tranSource()).isEqualTo(item.dalytranSource());
            assertThat(stored.tranDesc()).isEqualTo(item.dalytranDesc());
            assertThat(stored.tranAmtImage()).isEqualTo(item.dalytranAmtImage());
            assertThat(stored.tranMerchantId()).isEqualTo(item.dalytranMerchantId());
            assertThat(stored.tranMerchantName()).isEqualTo(item.dalytranMerchantName());
            assertThat(stored.tranMerchantCity()).isEqualTo(item.dalytranMerchantCity());
            assertThat(stored.tranMerchantZip()).isEqualTo(item.dalytranMerchantZip());
            assertThat(stored.tranCardNum()).isEqualTo(item.dalytranCardNum());
            assertThat(stored.tranOrigTs()).isEqualTo(item.dalytranOrigTs());
            assertThat(stored.tranProcTs()).isEqualTo(EXPECTED_PROC_TS);
            assertThat(result.procTimestamp()).isEqualTo(EXPECTED_PROC_TS);
        }

        @Test
        @DisplayName("a negative amount keeps its zoned overpunch across the copy, sign and all")
        void theAmountIsCopiedAsAnImage() {
            JdbcTemplate t = database();
            seedResolvableAccount(t);
            CapturedSysout sysout = new CapturedSysout();
            PostingRun run = openedRun(job(t, bindings()), sysout);
            DalyTranRecord item = dalyTran("T000000000000001", CARD, "-25.55",
                    "2022-06-10 19:27:53.000000");
            run.validateTran(item);
            run.postTransaction(item);
            run.release();

            TranRecord stored = TranRecord.decode(rows(t, TRANSACT_DS).get(0), ASCII);
            assertThat(stored.tranAmtImage()).isEqualTo(item.dalytranAmtImage());
            assertThat(stored.tranAmt()).isEqualByComparingTo("-25.55");
        }

        @Test
        @DisplayName("a '23' TCATBAL read creates a record whose balance is the transaction amount")
        void aMissingBalanceRecordIsCreated() {
            JdbcTemplate t = database();
            seed(t, XREF_DS, xrefImage(CARD, ACCOUNT));
            seed(t, ACCT_DS, account(ACCOUNT, "5000.00", "2026-01-01", "0.00", "0.00", "0.00")
                    .toFixedWidthString());
            CapturedSysout sysout = new CapturedSysout();
            PostingRun run = openedRun(job(t, bindings()), sysout);
            DalyTranRecord item = dalyTran("T000000000000001", CARD, "25.55",
                    "2022-06-10 19:27:53.000000");
            run.validateTran(item);
            PostingResult result = run.postTransaction(item);
            run.release();

            assertThat(result.tranCatBalCreated()).isTrue();
            List<String> balances = rows(t, TCATBAL_DS);
            assertThat(balances).hasSize(1);
            assertThat(balances.get(0)).hasSize(TranCatBalRecord.RECORD_LENGTH);
            TranCatBalRecord created = TranCatBalRecord.decode(balances.get(0), ASCII);
            assertThat(created.trancatAcctId()).isEqualTo(ACCOUNT);
            assertThat(created.trancatTypeCd()).isEqualTo(TYPE_CD);
            assertThat(created.trancatCd()).isEqualTo(CAT_CD);
            assertThat(created.tranCatBal()).isEqualByComparingTo("25.55");
            // INITIALIZE skips FILLER, and a record with no stored predecessor was allocated with spaces.
            assertThat(created.fillerImage())
                    .isEqualTo(" ".repeat(TranCatBalRecord.FILLER_LENGTH));
        }

        @Test
        @DisplayName("the not-found notice is byte-exact, two dots and the leading space included")
        void theNotFoundNoticeIsByteExact() {
            JdbcTemplate t = database();
            seed(t, XREF_DS, xrefImage(CARD, ACCOUNT));
            seed(t, ACCT_DS, account(ACCOUNT, "5000.00", "2026-01-01", "0.00", "0.00", "0.00")
                    .toFixedWidthString());
            CapturedSysout sysout = new CapturedSysout();
            PostingRun run = openedRun(job(t, bindings()), sysout);
            DalyTranRecord item = dalyTran("T000000000000001", CARD, "1.00",
                    "2022-06-10 19:27:53.000000");
            run.validateTran(item);
            run.postTransaction(item);
            run.release();

            assertThat(sysout.lines())
                    .containsExactly("TCATBAL record not found for key : 00000000011010001.. Creating.");
        }

        @Test
        @DisplayName("a '00' TCATBAL read increments the stored balance and rewrites it")
        void anExistingBalanceRecordIsRewritten() {
            JdbcTemplate t = database();
            seedResolvableAccount(t);
            CapturedSysout sysout = new CapturedSysout();
            PostingRun run = openedRun(job(t, bindings()), sysout);
            DalyTranRecord item = dalyTran("T000000000000001", CARD, "25.55",
                    "2022-06-10 19:27:53.000000");
            run.validateTran(item);
            PostingResult result = run.postTransaction(item);
            run.release();

            assertThat(result.tranCatBalCreated()).isFalse();
            List<String> balances = rows(t, TCATBAL_DS);
            assertThat(balances).hasSize(1);
            assertThat(TranCatBalRecord.decode(balances.get(0), ASCII).tranCatBal())
                    .isEqualByComparingTo("1025.55");
            // No notice was displayed, because the read found the record.
            assertThat(sysout.lines()).isEmpty();
        }

        @Test
        @DisplayName("a non-negative amount increases ACCT-CURR-CYC-CREDIT")
        void aNonNegativeAmountCreditsTheCycle() {
            JdbcTemplate t = database();
            seedResolvableAccount(t);
            CapturedSysout sysout = new CapturedSysout();
            PostingRun run = openedRun(job(t, bindings()), sysout);
            DalyTranRecord item = dalyTran("T000000000000001", CARD, "25.55",
                    "2022-06-10 19:27:53.000000");
            run.validateTran(item);
            run.postTransaction(item);
            run.release();

            AccountRecord stored = AccountRecord.decode(rows(t, ACCT_DS).get(0), ASCII);
            assertThat(stored.getAcctCurrBal()).isEqualByComparingTo("275.55");
            assertThat(stored.getAcctCurrCycCredit()).isEqualByComparingTo("125.55");
            assertThat(stored.getAcctCurrCycDebit()).isEqualByComparingTo("50.00");
        }

        @Test
        @DisplayName("a zero amount takes the credit branch too, because the test is >= 0")
        void aZeroAmountCreditsTheCycle() {
            JdbcTemplate t = database();
            seedResolvableAccount(t);
            CapturedSysout sysout = new CapturedSysout();
            PostingRun run = openedRun(job(t, bindings()), sysout);
            DalyTranRecord item = dalyTran("T000000000000001", CARD, "0.00",
                    "2022-06-10 19:27:53.000000");
            run.validateTran(item);
            run.postTransaction(item);
            run.release();

            AccountRecord stored = AccountRecord.decode(rows(t, ACCT_DS).get(0), ASCII);
            assertThat(stored.getAcctCurrCycCredit()).isEqualByComparingTo("100.00");
            assertThat(stored.getAcctCurrCycDebit()).isEqualByComparingTo("50.00");
        }

        @Test
        @DisplayName("a negative amount increases ACCT-CURR-CYC-DEBIT instead")
        void aNegativeAmountDebitsTheCycle() {
            JdbcTemplate t = database();
            seedResolvableAccount(t);
            CapturedSysout sysout = new CapturedSysout();
            PostingRun run = openedRun(job(t, bindings()), sysout);
            DalyTranRecord item = dalyTran("T000000000000001", CARD, "-25.55",
                    "2022-06-10 19:27:53.000000");
            run.validateTran(item);
            run.postTransaction(item);
            run.release();

            AccountRecord stored = AccountRecord.decode(rows(t, ACCT_DS).get(0), ASCII);
            assertThat(stored.getAcctCurrBal()).isEqualByComparingTo("224.45");
            assertThat(stored.getAcctCurrCycCredit()).isEqualByComparingTo("100.00");
            assertThat(stored.getAcctCurrCycDebit()).isEqualByComparingTo("24.45");
        }

        @Test
        @DisplayName("PRESERVED DEFECT: an INVALID KEY on the account rewrite sets 109 and rejects nothing")
        void reasonOneHundredAndNineRejectsNothing() {
            JdbcTemplate t = database();
            seedResolvableAccount(t);
            seed(t, DALYTRAN_DS, dalyTranImage(dalyTran("T000000000000001", CARD, "25.55",
                    "2022-06-10 19:27:53.000000")));
            CapturedSysout sysout = new CapturedSysout();
            PostingRun run = openedRun(job(t, bindings()), sysout);

            // The account resolves for the validation read, and is then removed before 2800 rewrites it.
            DalyTranRecord item = run.dalytranGetNext();
            run.validateTran(item);
            t.update("DELETE FROM \"" + ACCT_DS + "\"");
            boolean invalidKey = run.updateAccountRec(item);
            run.release();

            assertThat(invalidKey).isTrue();
            assertThat(run.workingStorage().validationFailReason())
                    .isEqualTo(TransactionValidationJob.REASON_ACCOUNT_NOT_FOUND_ON_REWRITE);
            // The whole point of the defect: no reject record, and the reject counter untouched.
            assertThat(rows(t, DALYREJS_DS)).isEmpty();
            assertThat(run.workingStorage().rejectCount()).isZero();
        }

        @Test
        @DisplayName("the same defect through the mainline: the record is still reported as posted")
        void theMainlineStillPostsARecordThatSetOneHundredAndNine() {
            JdbcTemplate t = database();
            seed(t, XREF_DS, xrefImage(CARD, ACCOUNT));
            seed(t, ACCT_DS, account(ACCOUNT, "5000.00", "2026-01-01", "0.00", "0.00", "0.00")
                    .toFixedWidthString());
            seed(t, DALYTRAN_DS, dalyTranImage(dalyTran("T000000000000001", CARD, "1.00",
                    "2022-06-10 19:27:53.000000")));
            CapturedSysout sysout = new CapturedSysout();
            TransactionValidationJob validationJob = job(t, bindings());
            PostingRun run = openedRun(validationJob, sysout);

            DalyTranRecord item = run.dalytranGetNext();
            // The account resolves for 1500-B, and is removed before 2800 rewrites it, so 2800's
            // INVALID KEY arm is the one taken while the mainline has already committed to posting.
            run.workingStorage().resetValidationTrailer();
            run.validateTran(item);
            assertThat(run.workingStorage().validationFailReasonIsZero()).isTrue();
            t.update("DELETE FROM \"" + ACCT_DS + "\"");
            PostingResult posting = run.postTransaction(item);
            run.closeFiles();
            RunOutcome outcome = run.writeTrailer();
            run.release();

            assertThat(posting.accountRewriteInvalidKey()).isTrue();
            // 2900 still ran, because :442 follows :441 unconditionally, and no reject was produced.
            assertThat(rows(t, TRANSACT_DS)).hasSize(1);
            assertThat(rows(t, DALYREJS_DS)).isEmpty();
            assertThat(outcome.rejectCount()).isZero();
            assertThat(outcome.returnCode()).isEqualTo(TransactionValidationJob.RETURN_CODE_CLEAN);
        }

        @Test
        @DisplayName("the three record areas hold what the paragraphs moved into them, before any write")
        void theRecordAreasHoldWhatTheParagraphsMoved() {
            JdbcTemplate t = database();
            seedResolvableAccount(t);
            CapturedSysout sysout = new CapturedSysout();
            PostingRun run = openedRun(job(t, bindings()), sysout);
            DalyTranRecord item = dalyTran("T000000000000001", CARD, "25.55",
                    "2022-06-10 19:27:53.000000");

            run.validateTran(item);
            // 1500-A moved the cross-reference record in, and 1500-B the account record.
            assertThat(run.cardXrefRecord().xrefCardNum()).isEqualTo(CARD);
            assertThat(run.cardXrefRecord().xrefAcctId()).isEqualTo(ACCOUNT);
            assertThat(run.accountRecord().getAcctId()).isEqualTo(ACCOUNT);
            assertThat(run.accountRecord().getAcctCurrBal()).isEqualByComparingTo("250.00");

            run.updateTcatbal(item);
            // 2700-B added the amount to the area, and the rewrite wrote that area out.
            assertThat(run.tranCatBalRecord().tranCatBal()).isEqualByComparingTo("1025.55");

            run.updateAccountRec(item);
            // 2800 added to the area the validation read filled; the rewrite persisted it.
            assertThat(run.accountRecord().getAcctCurrBal()).isEqualByComparingTo("275.55");
            assertThat(run.accountRecord().getAcctCurrCycCredit()).isEqualByComparingTo("125.55");

            // 2000's twelve moves and the timestamp land in TRAN-RECORD, which 2900 then writes.
            // TRAN-ID is PIC X(16) and is not trimmed on read, so an untouched area is 16 spaces.
            assertThat(run.tranRecord().tranId()).isEqualTo(" ".repeat(16));
            run.postTransaction(item);
            assertThat(run.tranRecord().tranId()).isEqualTo("T000000000000001");
            assertThat(run.tranRecord().tranProcTs()).isEqualTo(EXPECTED_PROC_TS);
            assertThat(run.tranRecord().encode(ASCII)).hasSize(TranRecord.RECORD_LENGTH);
            run.release();
        }

        @Test
        @DisplayName("a duplicate TRAN-ID reports '22', which 2900 treats as fatal like any other status")
        void aDuplicateTranIdAbends() {
            JdbcTemplate t = database();
            seedResolvableAccount(t);
            CapturedSysout sysout = new CapturedSysout();
            PostingRun run = openedRun(job(t, bindings()), sysout);
            DalyTranRecord item = dalyTran("T000000000000001", CARD, "1.00",
                    "2022-06-10 19:27:53.000000");
            run.validateTran(item);
            run.postTransaction(item);
            try {
                assertThatExceptionOfType(AbendException.class)
                        .isThrownBy(() -> run.writeTransactionFile());
            } finally {
                run.release();
            }
            assertThat(sysout.lines()).contains(TransactionValidationJob.ERROR_WRITING_TRANFILE);
        }

        @Test
        @DisplayName("a TCATBAL read that is neither '00' nor '23' abends with its own message")
        void aFatalBalanceReadAbends() {
            JdbcTemplate t = database();
            seedResolvableAccount(t);
            CapturedSysout sysout = new CapturedSysout();
            PostingRun run = openedRun(job(t, bindings()), sysout);
            DalyTranRecord item = dalyTran("T000000000000001", CARD, "1.00",
                    "2022-06-10 19:27:53.000000");
            run.validateTran(item);
            t.execute("DROP TABLE \"" + TCATBAL_DS + "\"");
            try {
                assertThatExceptionOfType(AbendException.class)
                        .isThrownBy(() -> run.updateTcatbal(item));
            } finally {
                recreate(t, TCATBAL_DS);
                run.release();
            }
            assertThat(sysout.lines()).contains(TransactionValidationJob.ERROR_READING_TCATBALF);
        }

        @Test
        @DisplayName("a failed TCATBAL write on the create path names WRITING, not REWRITING")
        void aFailedBalanceWriteNamesWriting() {
            JdbcTemplate t = database();
            seed(t, XREF_DS, xrefImage(CARD, ACCOUNT));
            seed(t, ACCT_DS, account(ACCOUNT, "5000.00", "2026-01-01", "0.00", "0.00", "0.00")
                    .toFixedWidthString());
            CapturedSysout sysout = new CapturedSysout();
            PostingRun run = openedRun(job(t, bindings()), sysout);
            DalyTranRecord item = dalyTran("T000000000000001", CARD, "1.00",
                    "2022-06-10 19:27:53.000000");
            run.validateTran(item);
            // The read reports '23', so the create path is taken; the relation then disappears.
            t.execute("DROP TABLE \"" + TCATBAL_DS + "\"");
            try {
                assertThatExceptionOfType(AbendException.class)
                        .isThrownBy(() -> run.createTcatbalRec(item));
            } finally {
                recreate(t, TCATBAL_DS);
                run.release();
            }
            assertThat(sysout.lines()).contains(TransactionValidationJob.ERROR_WRITING_TCATBALF);
            assertThat(sysout.lines()).doesNotContain(TransactionValidationJob.ERROR_REWRITING_TCATBALF);
        }

        @Test
        @DisplayName("a failed TCATBAL rewrite on the update path names REWRITING, not WRITING")
        void aFailedBalanceRewriteNamesRewriting() {
            JdbcTemplate t = database();
            seedResolvableAccount(t);
            CapturedSysout sysout = new CapturedSysout();
            PostingRun run = openedRun(job(t, bindings()), sysout);
            DalyTranRecord item = dalyTran("T000000000000001", CARD, "1.00",
                    "2022-06-10 19:27:53.000000");
            run.validateTran(item);
            run.updateTcatbal(item);
            t.execute("DROP TABLE \"" + TCATBAL_DS + "\"");
            try {
                assertThatExceptionOfType(AbendException.class)
                        .isThrownBy(() -> run.updateTcatbalRec(item));
            } finally {
                recreate(t, TCATBAL_DS);
                run.release();
            }
            assertThat(sysout.lines()).contains(TransactionValidationJob.ERROR_REWRITING_TCATBALF);
            assertThat(sysout.lines()).doesNotContain(TransactionValidationJob.ERROR_WRITING_TCATBALF);
        }
    }

    // =============================================================================================
    // 2500-WRITE-REJECT-REC - app/cbl/CBTRN02C.cbl:446-465. Gate G20.
    // =============================================================================================

    @Nested
    @DisplayName("2500-WRITE-REJECT-REC - a 430-byte record of 350 plus an 80-byte trailer")
    class Rejects {

        @Test
        @DisplayName("the reject record is exactly 430 bytes: the transaction, the reason, the text")
        void theRejectRecordIsFourHundredAndThirtyBytes() {
            JdbcTemplate t = database();
            seedResolvableAccount(t);
            seed(t, DALYTRAN_DS, dalyTranImage(dalyTran("T000000000000001", OTHER_CARD, "10.00",
                    "2022-06-10 19:27:53.000000")));
            CapturedSysout sysout = new CapturedSysout();
            PostingRun run = openedRun(job(t, bindings()), sysout);
            RecordOutcome outcome = run.processRecord(run.dalytranGetNext());
            run.closeFiles();
            run.release();

            assertThat(outcome.rejected()).isTrue();
            List<String> rejects = rows(t, DALYREJS_DS);
            assertThat(rejects).hasSize(1);
            String reject = rejects.get(0);
            assertThat(reject).hasSize(DalyRejectWriter.RECORD_LENGTH);

            String transaction = reject.substring(0, DalyRejectWriter.FD_REJECT_RECORD_LENGTH);
            assertThat(transaction).hasSize(DalyTranRecord.RECORD_LENGTH);
            assertThat(DalyTranRecord.decode(transaction, ASCII).dalytranCardNum())
                    .isEqualTo(OTHER_CARD);

            String trailer = reject.substring(DalyRejectWriter.VALIDATION_TRAILER_OFFSET);
            assertThat(trailer).hasSize(DalyRejectWriter.VALIDATION_TRAILER_LENGTH);
            // WS-VALIDATION-FAIL-REASON is PIC 9(04), so 100 renders zero-filled to four digits.
            assertThat(trailer.substring(0, DalyRejectWriter.WS_VALIDATION_FAIL_REASON_LENGTH))
                    .isEqualTo("0100");
            // WS-VALIDATION-FAIL-REASON-DESC is PIC X(76), space-padded on the right.
            assertThat(trailer.substring(DalyRejectWriter.WS_VALIDATION_FAIL_REASON_LENGTH))
                    .isEqualTo(padRight(DalyRejectWriter.DESC_INVALID_CARD_NUMBER,
                            DalyRejectWriter.WS_VALIDATION_FAIL_REASON_DESC_LENGTH));
        }

        @ParameterizedTest
        @ValueSource(ints = {100, 101, 102, 103})
        @DisplayName("each reason the cascade sets renders zero-filled into the trailer")
        void everyReasonRendersIntoTheTrailer(int reason) {
            assertThat(DalyRejectWriter.descriptionOfReason(reason)).isNotEmpty();
            assertThat(String.format("%04d", reason)).hasSize(4);
        }

        @Test
        @DisplayName("a failed reject write reports 'ERROR WRITING TO REJECTS FILE' and abends")
        void aFailedRejectWriteAbends() {
            JdbcTemplate t = database();
            seed(t, DALYTRAN_DS, dalyTranImage(dalyTran("T000000000000001", OTHER_CARD, "10.00",
                    "2022-06-10 19:27:53.000000")));
            CapturedSysout sysout = new CapturedSysout();
            PostingRun run = openedRun(job(t, bindings()), sysout);
            DalyTranRecord item = run.dalytranGetNext();
            run.validateTran(item);
            t.execute("DROP TABLE \"" + DALYREJS_DS + "\"");
            try {
                assertThatExceptionOfType(AbendException.class)
                        .isThrownBy(() -> run.writeRejectRec(item));
            } finally {
                recreate(t, DALYREJS_DS);
                run.release();
            }
            assertThat(sysout.lines()).contains(TransactionValidationJob.ERROR_WRITING_DALYREJS);
        }

        private static String padRight(String value, int width) {
            return value + " ".repeat(width - value.length());
        }
    }

    // =============================================================================================
    // The six CLOSE paragraphs, and the trailer - app/cbl/CBTRN02C.cbl:221-232 and :582-690.
    // =============================================================================================

    @Nested
    @DisplayName("The six CLOSE paragraphs and the trailer - order, texts and RETURN-CODE")
    class CloseLadderAndTrailer {

        @Test
        @DisplayName("a DALYTRAN close whose cursor release refuses reports its own text and abends")
        void aFailedDalytranCloseAbends() {
            JdbcTemplate clean = database();
            CapturedSysout sysout = new CapturedSysout();
            PostingRun run = openedRun(job(closeRefusing(clean), bindings()), sysout);
            try {
                assertThatExceptionOfType(AbendException.class).isThrownBy(run::dalytranClose);
            } finally {
                run.release();
            }
            assertThat(run.workingStorage().dalytranStatus())
                    .isEqualTo(TransactionValidationJob.PERMANENT_ERROR_STATUS);
            assertThat(sysout.lines()).containsExactly(
                    TransactionValidationJob.ERROR_CLOSING_DALYTRAN,
                    FileStatus.toDisplayLine(TransactionValidationJob.PERMANENT_ERROR_STATUS),
                    AbendException.ABEND_DISPLAY_TEXT);
        }

        @Test
        @DisplayName("a TRANFILE close of a file that never opened reports the transaction-file text")
        void aFailedTranfileCloseAbends() {
            // 9100-TRANFILE-CLOSE fails only for a file that is not open, and the COBOL abends in
            // 0100-TRANFILE-OPEN before it could reach the close - so the guard at :608 is unreachable
            // through the program's own sequence. It is still the guard the source writes, and driving
            // the paragraph directly proves its message and its APPL-RESULT rather than assuming them.
            JdbcTemplate t = database();
            CapturedSysout sysout = new CapturedSysout();
            PostingRun run = job(t, bindings()).newRun(sysout);
            run.dalytranOpen();
            t.execute("DROP TABLE \"" + TRANSACT_DS + "\"");
            assertThatExceptionOfType(AbendException.class).isThrownBy(run::tranfileOpen);
            recreate(t, TRANSACT_DS);
            sysout.lines().clear();
            try {
                assertThatExceptionOfType(AbendException.class).isThrownBy(run::tranfileClose);
            } finally {
                run.release();
            }
            assertThat(run.workingStorage().tranfileStatus())
                    .isEqualTo(TransactionValidationJob.PERMANENT_ERROR_STATUS);
            assertThat(sysout.lines()).containsExactly(
                    TransactionValidationJob.ERROR_CLOSING_TRANFILE,
                    FileStatus.toDisplayLine(TransactionValidationJob.PERMANENT_ERROR_STATUS),
                    AbendException.ABEND_DISPLAY_TEXT);
        }

        @Test
        @DisplayName("an XREFFILE close of a browse that never opened reports the cross-reference text")
        void aFailedXreffileCloseAbends() {
            // The same unreachable-by-construction guard, at :627, for 9200-XREFFILE-CLOSE.
            JdbcTemplate t = database();
            CapturedSysout sysout = new CapturedSysout();
            PostingRun run = job(t, bindings()).newRun(sysout);
            run.dalytranOpen();
            run.tranfileOpen();
            t.execute("DROP TABLE \"" + XREF_DS + "\"");
            assertThatExceptionOfType(AbendException.class).isThrownBy(run::xreffileOpen);
            recreate(t, XREF_DS);
            sysout.lines().clear();
            try {
                assertThatExceptionOfType(AbendException.class).isThrownBy(run::xreffileClose);
            } finally {
                run.release();
            }
            assertThat(run.workingStorage().xreffileStatus())
                    .isEqualTo(TransactionValidationJob.PERMANENT_ERROR_STATUS);
            assertThat(sysout.lines()).containsExactly(
                    TransactionValidationJob.ERROR_CLOSING_XREFFILE,
                    FileStatus.toDisplayLine(TransactionValidationJob.PERMANENT_ERROR_STATUS),
                    AbendException.ABEND_DISPLAY_TEXT);
        }

        @Test
        @DisplayName("a clean close emits nothing and marks the run as normally ended")
        void aCleanCloseIsSilent() {
            JdbcTemplate t = database();
            CapturedSysout sysout = new CapturedSysout();
            PostingRun run = openedRun(job(t, bindings()), sysout);
            assertThat(run.closedNormally()).isFalse();
            run.closeFiles();
            run.release();

            assertThat(run.closedNormally()).isTrue();
            assertThat(sysout.lines()).isEmpty();
        }

        @Test
        @DisplayName("closeFiles refuses a run that never opened, because 9000 cannot precede 0000")
        void closeFilesRefusesAnUnopenedRun() {
            JdbcTemplate t = database();
            PostingRun run = job(t, bindings()).newRun(new CapturedSysout());
            assertThatIllegalStateException().isThrownBy(run::closeFiles);
        }

        @Test
        @DisplayName("the six close literals are distinct, and each differs from its own open literal")
        void theSixCloseLiteralsAreDistinct() {
            List<String> closes = List.of(TransactionValidationJob.ERROR_CLOSING_DALYTRAN,
                    TransactionValidationJob.ERROR_CLOSING_TRANFILE,
                    TransactionValidationJob.ERROR_CLOSING_XREFFILE,
                    TransactionValidationJob.ERROR_CLOSING_DALYREJS,
                    TransactionValidationJob.ERROR_CLOSING_ACCTFILE,
                    TransactionValidationJob.ERROR_CLOSING_TCATBALF);
            assertThat(closes).doesNotHaveDuplicates();
            // The source's own inconsistencies, each pinned so a tidy-up cannot pass unnoticed.
            assertThat(TransactionValidationJob.ERROR_CLOSING_DALYTRAN)
                    .isEqualTo("ERROR CLOSING DALYTRAN FILE");
            assertThat(TransactionValidationJob.ERROR_OPENING_DALYTRAN)
                    .isEqualTo("ERROR OPENING DALYTRAN");
            assertThat(TransactionValidationJob.ERROR_OPENING_DALYREJS)
                    .isEqualTo("ERROR OPENING DALY REJECTS FILE");
            assertThat(TransactionValidationJob.ERROR_CLOSING_DALYREJS)
                    .isEqualTo("ERROR CLOSING DAILY REJECTS FILE");
            assertThat(TransactionValidationJob.ERROR_OPENING_ACCTFILE)
                    .isEqualTo("ERROR OPENING ACCOUNT MASTER FILE");
            assertThat(TransactionValidationJob.ERROR_CLOSING_ACCTFILE)
                    .isEqualTo("ERROR CLOSING ACCOUNT FILE");
        }

        @Test
        @DisplayName("a failed ACCTFILE close reports its own text and abends with 12")
        void aFailedAccountCloseAbends() {
            JdbcTemplate t = database();
            CapturedSysout sysout = new CapturedSysout();
            PostingRun run = openedRun(job(t, bindings()), sysout);
            t.execute("DROP TABLE \"" + ACCT_DS + "\"");
            try {
                AbendException abend = org.assertj.core.api.Assertions
                        .catchThrowableOfType(AbendException.class, run::acctfileClose);
                assertThat(abend).isNotNull();
                assertThat(abend.getReturnCode()).isEqualTo(TransactionValidationJob.APPL_RESULT_FATAL);
            } finally {
                recreate(t, ACCT_DS);
                run.release();
            }
            assertThat(sysout.lines()).containsSubsequence(
                    TransactionValidationJob.ERROR_CLOSING_ACCTFILE,
                    AbendException.ABEND_DISPLAY_TEXT);
            assertThat(run.closedNormally()).isFalse();
        }

        @Test
        @DisplayName("a failed TCATBALF close reports the balance-file text")
        void aFailedBalanceCloseAbends() {
            JdbcTemplate t = database();
            CapturedSysout sysout = new CapturedSysout();
            PostingRun run = openedRun(job(t, bindings()), sysout);
            t.execute("DROP TABLE \"" + TCATBAL_DS + "\"");
            try {
                assertThatExceptionOfType(AbendException.class).isThrownBy(run::tcatbalfClose);
            } finally {
                recreate(t, TCATBAL_DS);
                run.release();
            }
            assertThat(sysout.lines()).contains(TransactionValidationJob.ERROR_CLOSING_TCATBALF);
        }

        @Test
        @DisplayName("the abend line is preceded by the FILE STATUS line the program renders")
        void theAbendIsPrecededByTheStatusLine() {
            JdbcTemplate t = database();
            CapturedSysout sysout = new CapturedSysout();
            PostingRun run = openedRun(job(t, bindings()), sysout);
            t.execute("DROP TABLE \"" + ACCT_DS + "\"");
            try {
                assertThatExceptionOfType(AbendException.class).isThrownBy(run::acctfileClose);
            } finally {
                recreate(t, ACCT_DS);
                run.release();
            }
            assertThat(sysout.lines()).hasSize(3);
            assertThat(sysout.lines().get(1))
                    .isEqualTo(FileStatus.toDisplayLine(TransactionValidationJob.PERMANENT_ERROR_STATUS));
        }

        @Test
        @DisplayName("PRESERVED DEFECT: 9300 renders XREFFILE-STATUS, not DALYREJS-STATUS")
        void theRejectCloseRendersTheWrongStatus() {
            JdbcTemplate t = database();
            CapturedSysout sysout = new CapturedSysout();
            PostingRun run = openedRun(job(t, bindings()), sysout);
            // Give XREFFILE-STATUS a value that is neither the reject file's nor the default, so the
            // operand actually rendered is unambiguous. '10' is what a real XREFFILE EOF would leave.
            run.workingStorage().moveXreffileStatus(FileStatus.END_OF_FILE);
            t.execute("DROP TABLE \"" + DALYREJS_DS + "\"");
            try {
                assertThatExceptionOfType(AbendException.class).isThrownBy(run::dalyrejsClose);
            } finally {
                recreate(t, DALYREJS_DS);
                run.release();
            }
            // The close itself reported a permanent error, and DALYREJS-STATUS holds it...
            assertThat(run.workingStorage().dalyrejsStatus())
                    .isEqualTo(TransactionValidationJob.PERMANENT_ERROR_STATUS);
            // ...but :649 moves XREFFILE-STATUS, so that is what the display shows.
            assertThat(sysout.lines()).containsExactly(
                    TransactionValidationJob.ERROR_CLOSING_DALYREJS,
                    FileStatus.toDisplayLine(FileStatus.END_OF_FILE),
                    AbendException.ABEND_DISPLAY_TEXT);
        }

        @Test
        @DisplayName("the two count lines are byte-exact, and the reject line has two spaces")
        void theCountLinesAreByteExact() {
            JdbcTemplate t = database();
            CapturedSysout sysout = new CapturedSysout();
            PostingRun run = openedRun(job(t, bindings()), sysout);
            run.closeFiles();
            RunOutcome outcome = run.writeTrailer();
            run.release();

            assertThat(sysout.lines()).containsExactly(
                    "TRANSACTIONS PROCESSED :000000000",
                    "TRANSACTIONS REJECTED  :000000000",
                    "END OF EXECUTION OF PROGRAM CBTRN02C");
            assertThat(outcome).isEqualTo(new RunOutcome(0L, 0L,
                    TransactionValidationJob.RETURN_CODE_CLEAN));
            // The literals themselves, so the spacing cannot drift even if a run emits nothing.
            assertThat(TransactionValidationJob.TRANSACTIONS_PROCESSED_PREFIX)
                    .isEqualTo("TRANSACTIONS PROCESSED :");
            assertThat(TransactionValidationJob.TRANSACTIONS_REJECTED_PREFIX)
                    .isEqualTo("TRANSACTIONS REJECTED  :")
                    .hasSize(TransactionValidationJob.TRANSACTIONS_PROCESSED_PREFIX.length());
        }

        @Test
        @DisplayName("both counters render zero-filled to nine digits, because they are PIC 9(09)")
        void theCountersAreNineDigits() {
            JdbcTemplate t = database();
            seedResolvableAccount(t);
            for (int i = 1; i <= 3; i++) {
                seed(t, DALYTRAN_DS, dalyTranImage(dalyTran(String.format("T%015d", i), CARD, "1.00",
                        "2022-06-10 19:27:53.000000")));
            }
            seed(t, DALYTRAN_DS, dalyTranImage(dalyTran("T000000000000009", OTHER_CARD, "1.00",
                    "2022-06-10 19:27:53.000000")));
            CapturedSysout sysout = new CapturedSysout();
            RunOutcome outcome = job(t, bindings()).postTransactions(sysout);

            assertThat(outcome.transactionCount()).isEqualTo(4L);
            assertThat(outcome.rejectCount()).isEqualTo(1L);
            assertThat(sysout.lines()).contains("TRANSACTIONS PROCESSED :000000004",
                    "TRANSACTIONS REJECTED  :000000001");
        }

        @Test
        @DisplayName("one reject sets RETURN-CODE 4; none leaves it 0")
        void theReturnCodeFollowsTheRejectCount() {
            JdbcTemplate clean = database();
            seedResolvableAccount(clean);
            seed(clean, DALYTRAN_DS, dalyTranImage(dalyTran("T000000000000001", CARD, "1.00",
                    "2022-06-10 19:27:53.000000")));
            RunOutcome cleanRun = job(clean, bindings()).postTransactions(new CapturedSysout());
            assertThat(cleanRun.returnCode()).isEqualTo(TransactionValidationJob.RETURN_CODE_CLEAN);
            assertThat(cleanRun.returnCode()).isZero();

            JdbcTemplate rejecting = database();
            seedResolvableAccount(rejecting);
            seed(rejecting, DALYTRAN_DS, dalyTranImage(dalyTran("T000000000000001", OTHER_CARD, "1.00",
                    "2022-06-10 19:27:53.000000")));
            RunOutcome rejectingRun =
                    job(rejecting, bindings()).postTransactions(new CapturedSysout());
            assertThat(rejectingRun.returnCode())
                    .isEqualTo(TransactionValidationJob.RETURN_CODE_REJECTS_PRESENT);
            assertThat(rejectingRun.returnCode()).isEqualTo(4);
        }

        @Test
        @DisplayName("release is idempotent and safe on a run that never opened anything")
        void releaseIsIdempotent() {
            JdbcTemplate t = database();
            PostingRun never = job(t, bindings()).newRun(new CapturedSysout());
            assertThatCode(never::release).doesNotThrowAnyException();
            assertThatCode(never::release).doesNotThrowAnyException();

            PostingRun opened = openedRun(job(t, bindings()), new CapturedSysout());
            opened.closeFiles();
            assertThatCode(opened::release).doesNotThrowAnyException();
            assertThatCode(opened::release).doesNotThrowAnyException();
        }
    }

    // =============================================================================================
    // The whole program - app/cbl/CBTRN02C.cbl:193-234, banners included.
    // =============================================================================================

    @Nested
    @DisplayName("A whole run - the banners, the loop, the closes and the trailer in order")
    class WholeRun {

        @Test
        @DisplayName("an empty DALYTRAN still emits both banners and two zero counts")
        void anEmptyRunIsStillAWholeRun() {
            JdbcTemplate t = database();
            CapturedSysout sysout = new CapturedSysout();
            RunOutcome outcome = job(t, bindings()).postTransactions(sysout);

            assertThat(sysout.lines()).containsExactly(
                    "START OF EXECUTION OF PROGRAM CBTRN02C",
                    "TRANSACTIONS PROCESSED :000000000",
                    "TRANSACTIONS REJECTED  :000000000",
                    "END OF EXECUTION OF PROGRAM CBTRN02C");
            assertThat(outcome).isEqualTo(new RunOutcome(0L, 0L, 0));
        }

        @Test
        @DisplayName("a mixed run posts what validates and rejects what does not, in read order")
        void aMixedRunPostsAndRejects() {
            JdbcTemplate t = database();
            seed(t, XREF_DS, xrefImage(CARD, ACCOUNT));
            seed(t, ACCT_DS, account(ACCOUNT, "5000.00", "2026-01-01", "0.00", "0.00", "0.00")
                    .toFixedWidthString());
            seed(t, TCATBAL_DS, tcatbalImage(ACCOUNT, TYPE_CD, CAT_CD, "0.00"));
            seed(t, DALYTRAN_DS, dalyTranImage(dalyTran("T000000000000001", CARD, "10.00",
                    "2022-06-10 19:27:53.000000")));
            seed(t, DALYTRAN_DS, dalyTranImage(dalyTran("T000000000000002", OTHER_CARD, "20.00",
                    "2022-06-10 19:27:53.000000")));
            seed(t, DALYTRAN_DS, dalyTranImage(dalyTran("T000000000000003", CARD, "30.00",
                    "2022-06-10 19:27:53.000000")));
            CapturedSysout sysout = new CapturedSysout();
            RunOutcome outcome = job(t, bindings()).postTransactions(sysout);

            assertThat(outcome.transactionCount()).isEqualTo(3L);
            assertThat(outcome.rejectCount()).isEqualTo(1L);
            assertThat(outcome.returnCode()).isEqualTo(4);

            List<String> posted = rows(t, TRANSACT_DS);
            assertThat(posted).hasSize(2);
            assertThat(posted).allSatisfy(image ->
                    assertThat(image).hasSize(TranRecord.RECORD_LENGTH));
            assertThat(posted.stream().map(i -> TranRecord.decode(i, ASCII).tranId()).toList())
                    .containsExactlyInAnyOrder("T000000000000001", "T000000000000003");

            List<String> rejected = rows(t, DALYREJS_DS);
            assertThat(rejected).hasSize(1);
            assertThat(rejected.get(0)).hasSize(DalyRejectWriter.RECORD_LENGTH);
            assertThat(DalyTranRecord.decode(
                    rejected.get(0).substring(0, DalyTranRecord.RECORD_LENGTH), ASCII).dalytranId())
                    .isEqualTo("T000000000000002");

            // Both amounts landed on the one account and the one balance bucket, in read order.
            assertThat(AccountRecord.decode(rows(t, ACCT_DS).get(0), ASCII).getAcctCurrBal())
                    .isEqualByComparingTo("40.00");
            assertThat(TranCatBalRecord.decode(rows(t, TCATBAL_DS).get(0), ASCII).tranCatBal())
                    .isEqualByComparingTo("40.00");
        }

        @Test
        @DisplayName("an abend leaves no count line, no closing banner and no reject generation")
        void anAbendLeavesTheTrailerUnperformed() {
            JdbcTemplate t = database();
            seedResolvableAccount(t);
            // A short row makes 1000-DALYTRAN-GET-NEXT report a permanent error, which is fatal.
            seed(t, DALYTRAN_DS, "SHORT ROW");
            CapturedSysout sysout = new CapturedSysout();
            TransactionValidationJob validationJob = job(t, bindings());

            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> validationJob.postTransactions(sysout));

            assertThat(sysout.lines()).first().isEqualTo("START OF EXECUTION OF PROGRAM CBTRN02C");
            assertThat(sysout.lines()).contains(TransactionValidationJob.ERROR_READING_DALYTRAN,
                    AbendException.ABEND_DISPLAY_TEXT);
            assertThat(sysout.lines()).noneSatisfy(line ->
                    assertThat(line).startsWith(TransactionValidationJob.TRANSACTIONS_PROCESSED_PREFIX));
            assertThat(sysout.lines()).doesNotContain("END OF EXECUTION OF PROGRAM CBTRN02C");
            // DISP=(NEW,CATLG,DELETE): the generation an abended run allocated is discarded.
            assertThat(rows(t, DALYREJS_DS)).isEmpty();
        }

        @Test
        @DisplayName("postTransactions refuses a null sink, and the no-argument form uses the sink bean")
        void postTransactionsRefusesANullSink() {
            JdbcTemplate t = database();
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> job(t, bindings()).postTransactions(null));

            // The no-argument form runs the program through whatever SysoutSink bean the context
            // supplied. It is driven with the collector rather than with the absent-bean fallback of
            // standardOutput(Charset) on purpose: that fallback writes to FileDescriptor.out, which
            // bypasses the stream Surefire redirects in a forked JVM and makes the build report
            // "Corrupted channel by directly writing to native stream". The fallback itself is still
            // exercised - every job built with an AbsentBean resolves it in the constructor - so the
            // only thing avoided here is emitting 4 lines onto the raw descriptor.
            CapturedSysout sysout = new CapturedSysout();
            RunOutcome outcome = jobWithSink(t, sysout).postTransactions();

            assertThat(outcome).isEqualTo(new RunOutcome(0L, 0L, 0));
            assertThat(sysout.lines()).containsExactly(
                    "START OF EXECUTION OF PROGRAM CBTRN02C",
                    "TRANSACTIONS PROCESSED :000000000",
                    "TRANSACTIONS REJECTED  :000000000",
                    "END OF EXECUTION OF PROGRAM CBTRN02C");
        }

        @Test
        @DisplayName("the absent-bean fallback builds a sink over the dataset code page, not the default")
        void theAbsentBeanFallbackNamesItsCharset() {
            // standardOutput(Charset) is what the constructor resolves when no SysoutSink bean exists.
            // Building one is side-effect free; it is only writing through it that reaches the process's
            // standard output, which is why this asserts the contract rather than the emission.
            assertThat(TransactionValidationJob.standardOutput(ASCII)).isNotNull();
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> TransactionValidationJob.standardOutput(null));
        }

        @Test
        @DisplayName("a run seeded from the real fixtures posts every record that resolves")
        void aRunSeededFromTheRealFixtures() throws Exception {
            List<String> dailyTran = fixture("dailytran.txt", DalyTranRecord.RECORD_LENGTH);
            List<String> tcatbal = fixture("tcatbal.txt", TranCatBalRecord.RECORD_LENGTH);
            assertThat(dailyTran).hasSize(300);
            assertThat(tcatbal).hasSize(50);

            JdbcTemplate t = database();
            // One resolvable card, generous limit and a far expiry, so the cascade turns on the data
            // rather than on the account. Every fixture row carries the same card number.
            String fixtureCard = DalyTranRecord.decode(dailyTran.get(0), ASCII).dalytranCardNum();
            seed(t, XREF_DS, xrefImage(fixtureCard, ACCOUNT));
            seed(t, ACCT_DS, account(ACCOUNT, "9999999999.99", "9999-12-31", "0.00", "0.00", "0.00")
                    .toFixedWidthString());
            int seeded = 0;
            for (String row : dailyTran) {
                DalyTranRecord record = DalyTranRecord.decode(row, ASCII);
                if (!fixtureCard.equals(record.dalytranCardNum())) {
                    continue;
                }
                seed(t, DALYTRAN_DS, row);
                seeded++;
            }
            assertThat(seeded).isPositive();

            CapturedSysout sysout = new CapturedSysout();
            RunOutcome outcome = job(t, bindings()).postTransactions(sysout);

            assertThat(outcome.transactionCount()).isEqualTo(seeded);
            assertThat(outcome.rejectCount()).isZero();
            assertThat(outcome.returnCode()).isZero();
            assertThat(rows(t, TRANSACT_DS)).hasSize(seeded)
                    .allSatisfy(image -> assertThat(image).hasSize(TranRecord.RECORD_LENGTH));
            // A balance bucket was created for every distinct type/category pair the rows carried.
            assertThat(rows(t, TCATBAL_DS)).isNotEmpty()
                    .allSatisfy(image -> assertThat(image).hasSize(TranCatBalRecord.RECORD_LENGTH));
        }

        @Test
        @DisplayName("the cardxref fixture is 36 bytes a row and must be widened to 50 before use")
        void theCardXrefFixtureIsWidened() throws Exception {
            List<String> raw = rawFixture("cardxref.txt");
            assertThat(raw).hasSize(50);
            assertThat(raw).allSatisfy(row -> assertThat(row).hasSize(36));

            JdbcTemplate t = database();
            for (String row : raw) {
                String widened = row + " ".repeat(CardXrefRecord.RECORD_LENGTH - row.length());
                assertThat(widened).hasSize(CardXrefRecord.RECORD_LENGTH);
                seed(t, XREF_DS, widened);
            }
            // The widened rows decode, so a run over them resolves cards rather than rejecting them.
            CardXrefRecord first =
                    CardXrefRecord.decode(rows(t, XREF_DS).get(0).getBytes(ASCII), ASCII);
            assertThat(first.xrefCardNum()).isNotBlank();
            assertThat(first.xrefAcctId()).isPositive();
        }

        private List<String> fixture(String name, int width) throws Exception {
            List<String> rows = rawFixture(name);
            assertThat(rows).allSatisfy(row -> assertThat(row).hasSize(width));
            return rows;
        }

        private List<String> rawFixture(String name) throws Exception {
            java.nio.file.Path path = java.nio.file.Path
                    .of("..", "..", "app", "data", "ASCII", name).normalize();
            if (!java.nio.file.Files.isReadable(path)) {
                path = java.nio.file.Path.of("app", "data", "ASCII", name);
            }
            assertThat(java.nio.file.Files.isReadable(path))
                    .as("the read-only fixture %s must be reachable from the module directory", name)
                    .isTrue();
            return java.nio.file.Files.readAllLines(path, ASCII);
        }
    }

    // =============================================================================================
    // The chunk delegate - the ItemReader / ItemProcessor / ItemWriter the Step is built from.
    // =============================================================================================

    @Nested
    @DisplayName("The chunk delegate - one run per step execution, closed once")
    class Delegate {

        @Test
        @DisplayName("the delegate runs the whole program across its callbacks, in Spring Batch's order")
        void theDelegateRunsTheWholeProgram() {
            JdbcTemplate t = database();
            seedResolvableAccount(t);
            seed(t, DALYTRAN_DS, dalyTranImage(dalyTran("T000000000000001", CARD, "10.00",
                    "2022-06-10 19:27:53.000000")));
            seed(t, DALYTRAN_DS, dalyTranImage(dalyTran("T000000000000002", OTHER_CARD, "20.00",
                    "2022-06-10 19:27:53.000000")));
            ChunkDelegate delegate = jobWithSink(t, new CapturedSysout()).newChunkDelegate();
            StepExecution execution = stepExecution();

            delegate.beforeStep(execution);
            delegate.open(new ExecutionContext());
            assertThat(delegate.currentRun()).isPresent();
            assertThat(delegate.runOutcome()).isEmpty();

            List<RecordOutcome> outcomes = new ArrayList<>();
            DalyTranRecord item;
            while ((item = delegate.read()) != null) {
                RecordOutcome outcome = delegate.process(item);
                assertThat(outcome).isNotNull();
                outcomes.add(outcome);
                delegate.write(Chunk.of(outcome));
            }
            delegate.update(new ExecutionContext());

            assertThat(outcomes).hasSize(2);
            assertThat(outcomes.get(0).posted()).isTrue();
            assertThat(outcomes.get(1).rejected()).isTrue();
            assertThat(delegate.postedCount()).isEqualTo(1L);
            assertThat(delegate.rejectedCount()).isEqualTo(1L);

            // AbstractStep.execute calls afterStep BEFORE it closes the item streams, so the outcome has
            // to exist by now. It does, because the read that returned null performed the epilogue.
            assertThat(delegate.runOutcome())
                    .contains(new RunOutcome(2L, 1L, TransactionValidationJob.RETURN_CODE_REJECTS_PRESENT));
            assertThat(delegate.afterStep(execution)).isEqualTo(ExitStatus.COMPLETED
                    .replaceExitCode(String.valueOf(TransactionValidationJob.RETURN_CODE_REJECTS_PRESENT)));

            delegate.close();
            assertThat(delegate.currentRun()).isEmpty();
        }

        @Test
        @DisplayName("the epilogue runs at the read that ends the loop, not at the stream close")
        void theEpilogueRunsAtTheEndOfFileRead() {
            JdbcTemplate t = database();
            seedResolvableAccount(t);
            seed(t, DALYTRAN_DS, dalyTranImage(dalyTran("T000000000000001", CARD, "10.00",
                    "2022-06-10 19:27:53.000000")));
            CapturedSysout sysout = new CapturedSysout();
            ChunkDelegate delegate = jobWithSink(t, sysout).newChunkDelegate();

            delegate.beforeStep(stepExecution());
            delegate.open(new ExecutionContext());
            assertThat(sysout.lines()).containsExactly("START OF EXECUTION OF PROGRAM CBTRN02C");

            DalyTranRecord item = delegate.read();
            delegate.write(Chunk.of(delegate.process(item)));
            // Still nothing after the banner: the trailer has not been reached.
            assertThat(sysout.lines()).hasSize(1);
            assertThat(delegate.runOutcome()).isEmpty();

            assertThat(delegate.read()).isNull();
            // The epilogue happened here, before close() was called at all.
            assertThat(delegate.runOutcome()).contains(new RunOutcome(1L, 0L, 0));
            assertThat(sysout.lines()).containsExactly(
                    "START OF EXECUTION OF PROGRAM CBTRN02C",
                    "TRANSACTIONS PROCESSED :000000001",
                    "TRANSACTIONS REJECTED  :000000000",
                    "END OF EXECUTION OF PROGRAM CBTRN02C");

            delegate.close();
            // close() adds nothing: the epilogue is not performed twice.
            assertThat(sysout.lines()).hasSize(4);
        }

        @Test
        @DisplayName("a clean run leaves the exit status alone, which Spring Batch reads as no change")
        void aCleanRunLeavesTheExitStatusAlone() {
            JdbcTemplate t = database();
            seedResolvableAccount(t);
            seed(t, DALYTRAN_DS, dalyTranImage(dalyTran("T000000000000001", CARD, "10.00",
                    "2022-06-10 19:27:53.000000")));
            ChunkDelegate delegate = jobWithSink(t, new CapturedSysout()).newChunkDelegate();
            StepExecution execution = stepExecution();

            delegate.beforeStep(execution);
            delegate.open(new ExecutionContext());
            DalyTranRecord item = delegate.read();
            delegate.write(Chunk.of(delegate.process(item)));
            assertThat(delegate.read()).isNull();

            assertThat(delegate.runOutcome()).contains(new RunOutcome(1L, 0L, 0));
            assertThat(delegate.afterStep(execution)).isNull();
            delegate.close();
        }

        @Test
        @DisplayName("an abend leaves no outcome, so afterStep contributes nothing to the exit status")
        void anAbendLeavesNoOutcome() {
            JdbcTemplate t = database();
            seedResolvableAccount(t);
            seed(t, DALYTRAN_DS, "SHORT ROW");
            ChunkDelegate delegate = jobWithSink(t, new CapturedSysout()).newChunkDelegate();
            StepExecution execution = stepExecution();

            delegate.beforeStep(execution);
            delegate.open(new ExecutionContext());
            assertThatExceptionOfType(AbendException.class).isThrownBy(delegate::read);
            assertThat(delegate.runOutcome()).isEmpty();
            assertThat(delegate.afterStep(execution)).isNull();
            delegate.close();
            assertThat(delegate.currentRun()).isEmpty();
        }

        @Test
        @DisplayName("a close that abends is thrown from the read, where Spring Batch cannot swallow it")
        void aCloseFailureIsThrownFromTheRead() {
            JdbcTemplate t = database();
            seedResolvableAccount(t);
            CapturedSysout sysout = new CapturedSysout();
            ChunkDelegate delegate = jobWithSink(t, sysout).newChunkDelegate();
            delegate.beforeStep(stepExecution());
            delegate.open(new ExecutionContext());
            t.execute("DROP TABLE \"" + ACCT_DS + "\"");
            try {
                // The read reaches end of file, performs the epilogue, and 9400-ACCTFILE-CLOSE fails.
                assertThatExceptionOfType(AbendException.class).isThrownBy(delegate::read);
            } finally {
                recreate(t, ACCT_DS);
                delegate.close();
            }
            assertThat(sysout.lines()).contains(TransactionValidationJob.ERROR_CLOSING_ACCTFILE);
            assertThat(delegate.runOutcome()).isEmpty();
        }

        @Test
        @DisplayName("close is idempotent, and a second open on the same delegate is refused")
        void closeIsIdempotentAndReopenIsRefused() {
            JdbcTemplate t = database();
            ChunkDelegate delegate = jobWithSink(t, new CapturedSysout()).newChunkDelegate();
            delegate.beforeStep(stepExecution());
            delegate.open(new ExecutionContext());
            assertThatIllegalStateException().isThrownBy(() -> delegate.open(new ExecutionContext()));
            delegate.close();
            assertThatCode(delegate::close).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("every callback refuses to run before open, because 0000 comes first")
        void everyCallbackRefusesBeforeOpen() {
            JdbcTemplate t = database();
            ChunkDelegate delegate = jobWithSink(t, new CapturedSysout()).newChunkDelegate();
            DalyTranRecord item = dalyTran("T1", CARD, "1.00", "2022-06-10 19:27:53.000000");
            assertThatIllegalStateException().isThrownBy(delegate::read);
            assertThatIllegalStateException().isThrownBy(() -> delegate.process(item));
            assertThat(delegate.runOutcome()).isEmpty();
            assertThat(delegate.postedCount()).isZero();
            assertThat(delegate.rejectedCount()).isZero();
            assertThat(delegate.currentRun()).isEmpty();
        }

        @Test
        @DisplayName("the write callback counts what the chunk carries and nothing else")
        void theWriteCallbackCounts() {
            JdbcTemplate t = database();
            seedResolvableAccount(t);
            seed(t, DALYTRAN_DS, dalyTranImage(dalyTran("T000000000000001", CARD, "10.00",
                    "2022-06-10 19:27:53.000000")));
            ChunkDelegate delegate = jobWithSink(t, new CapturedSysout()).newChunkDelegate();
            delegate.beforeStep(stepExecution());
            delegate.open(new ExecutionContext());
            RecordOutcome posted = delegate.process(delegate.read());
            delegate.write(Chunk.of());
            assertThat(delegate.postedCount()).isZero();
            delegate.write(Chunk.of(posted));
            assertThat(delegate.postedCount()).isEqualTo(1L);
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> delegate.write(null));
            delegate.close();
        }

        @Test
        @DisplayName("the step-scoped delegate reuses one delegate per execution and refuses a second")
        void theStepScopedDelegateScopesByExecution() {
            JdbcTemplate t = database();
            seedResolvableAccount(t);
            seed(t, DALYTRAN_DS, dalyTranImage(dalyTran("T000000000000001", CARD, "10.00",
                    "2022-06-10 19:27:53.000000")));
            StepScopedChunkDelegate scoped = new StepScopedChunkDelegate(jobWithSink(t, new CapturedSysout()));
            StepExecution first = stepExecution();

            assertThat(scoped.scopedDelegate()).isEmpty();
            scoped.beforeStep(first);
            assertThat(scoped.scopedDelegate()).isPresent();
            ChunkDelegate bound = scoped.scopedDelegate().orElseThrow();
            // The same execution binding again is the idempotent case Spring may produce on a retry.
            scoped.beforeStep(first);
            assertThat(scoped.scopedDelegate()).contains(bound);
            // A different execution on the same thread would silently share state, so it is refused.
            assertThatIllegalStateException().isThrownBy(() -> scoped.beforeStep(stepExecution()));

            scoped.open(new ExecutionContext());
            DalyTranRecord item = scoped.read();
            assertThat(item).isNotNull();
            scoped.write(Chunk.of(scoped.process(item)));
            assertThat(scoped.read()).isNull();
            scoped.update(new ExecutionContext());
            // Spring Batch's order: the listener first, then the stream close.
            assertThat(scoped.afterStep(first)).isNull();
            assertThat(bound.runOutcome()).contains(new RunOutcome(1L, 0L, 0));
            scoped.close();
            // The scope is released, so the same instance can serve the next execution.
            assertThat(scoped.scopedDelegate()).isEmpty();
            assertThatCode(() -> scoped.beforeStep(stepExecution())).doesNotThrowAnyException();
            scoped.close();
        }

        @Test
        @DisplayName("a rejecting run's exit code reaches the step through the scoped delegate too")
        void theScopedDelegateForwardsTheExitCode() {
            JdbcTemplate t = database();
            seedResolvableAccount(t);
            seed(t, DALYTRAN_DS, dalyTranImage(dalyTran("T000000000000001", OTHER_CARD, "10.00",
                    "2022-06-10 19:27:53.000000")));
            StepScopedChunkDelegate scoped = new StepScopedChunkDelegate(jobWithSink(t, new CapturedSysout()));
            StepExecution execution = stepExecution();

            scoped.beforeStep(execution);
            scoped.open(new ExecutionContext());
            DalyTranRecord item = scoped.read();
            scoped.write(Chunk.of(scoped.process(item)));
            assertThat(scoped.read()).isNull();

            assertThat(scoped.afterStep(execution)).isEqualTo(ExitStatus.COMPLETED
                    .replaceExitCode(String.valueOf(TransactionValidationJob.RETURN_CODE_REJECTS_PRESENT)));
            scoped.close();
        }

        @Test
        @DisplayName("the step-scoped delegate refuses every callback outside a scope, naming it")
        void theStepScopedDelegateRefusesOutsideAScope() {
            JdbcTemplate t = database();
            StepScopedChunkDelegate scoped = new StepScopedChunkDelegate(jobWithSink(t, new CapturedSysout()));
            DalyTranRecord item = dalyTran("T1", CARD, "1.00", "2022-06-10 19:27:53.000000");
            assertThatIllegalStateException().isThrownBy(() -> scoped.open(new ExecutionContext()));
            assertThatIllegalStateException().isThrownBy(scoped::read);
            assertThatIllegalStateException().isThrownBy(() -> scoped.process(item));
            assertThatIllegalStateException().isThrownBy(() -> scoped.write(Chunk.of()));
            // afterStep, update and close all tolerate the absent scope, because Spring Batch calls them
            // unconditionally and a step may end without a delegate ever having been bound.
            assertThat(scoped.afterStep(stepExecution())).isNull();
            assertThatCode(() -> scoped.update(new ExecutionContext())).doesNotThrowAnyException();
            assertThatCode(scoped::close).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("null arguments are refused by every scoped callback that receives one")
        void theScopedDelegateRefusesNulls() {
            JdbcTemplate t = database();
            StepScopedChunkDelegate scoped = new StepScopedChunkDelegate(jobWithSink(t, new CapturedSysout()));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> scoped.beforeStep(null));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> scoped.afterStep(null));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new StepScopedChunkDelegate(null));
        }

        private StepExecution stepExecution() {
            StepExecution execution = Mockito.mock(StepExecution.class);
            Mockito.when(execution.getExitStatus()).thenReturn(ExitStatus.COMPLETED);
            return execution;
        }
    }
}
