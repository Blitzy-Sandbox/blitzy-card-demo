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
import com.vsergeychik.carddemo.common.DatasetUnitOfWork;
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
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

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
import com.vsergeychik.carddemo.testdataset.RecordImageDataSource;
import com.vsergeychik.carddemo.testdataset.RecordImageStore;
import com.vsergeychik.carddemo.testdataset.RecordImageStore.ColumnForm;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * The twenty-case parity gate for {@code CBTRN02C}, the {@code POSTTRAN} poster - and one of the three
 * densest-coverage programs in the estate, because of its four-stage validation cascade.
 */
@DisplayName("CBTRN02C - POSTTRAN validate-and-post parity gate (20 cases, diff count must be zero)")
final class CBTRN02CParityTest {
    private static final String PROGRAM = TransactionValidationJob.PROGRAM_ID;

    private static final String IMAGE_COLUMN = "REC";

    private static final PhysicalSequence WRITE_ORDER = PhysicalSequence.of("_ROWID_");

    private static final String DALYTRAN_KEY = TransactionValidationJob.DALYTRAN_DD_NAME;

    private static final String TRANFILE_KEY = TransactionValidationJob.TRANFILE_DD_NAME;

    private static final String XREFFILE_KEY = TransactionValidationJob.XREFFILE_DD_NAME;

    private static final String DALYREJS_KEY = TransactionValidationJob.DALYREJS_DD_NAME;

    private static final String ACCTFILE_KEY = TransactionValidationJob.ACCTFILE_DD_NAME;

    private static final String TCATBALF_KEY = TransactionValidationJob.TCATBALF_DD_NAME;

    private static final String DALYTRAN_DSNAME = "CARDDEMO.PARITY.DALYTRAN.PS";

    private static final String TRANSACT_DSNAME = "CARDDEMO.PARITY.TRANSACT.KSDS";

    private static final String SYSTRAN_DSNAME = "CARDDEMO.PARITY.SYSTRAN";

    private static final String CARDXREF_DSNAME = "CARDDEMO.PARITY.CARDXREF.KSDS";

    private static final String CARDXREF_AIX_DSNAME = "CARDDEMO.PARITY.CARDXREF.AIX";

    private static final String ACCTDATA_DSNAME = "CARDDEMO.PARITY.ACCTDATA.KSDS";

    private static final String TCATBALF_DSNAME = "CARDDEMO.PARITY.TCATBALF.KSDS";

    private static final String DALYREJS_DSNAME = "CARDDEMO.PARITY.DALYREJS";

    private static final boolean STEP_IS_UNGATED = false;

    private enum Scenario {
        CLEAN,

        ACCOUNT_REWRITE_NOT_FOUND,

        REJECT_OPEN_FAILS,

        REJECT_WRITE_FAILS,

        DALYTRAN_READ_FAILS,

        TCATBAL_READ_FAILS
    }

    private static final String DALYTRAN_PERMANENT_ERROR_STATUS = "30";

    private static final String TCATBAL_READ_FAILURE_STATUS = "30";

    private static final String REWRITE_ACCTFILE_SITE = "REWRITE-" + ACCTFILE_KEY;

    private static final String OPEN_DALYREJS_SITE = "OPEN-" + DALYREJS_KEY;

    private static final String WRITE_DALYREJS_SITE = "WRITE-" + DALYREJS_KEY;

    private static final String READ_DALYTRAN_SITE = "READ-" + DALYTRAN_KEY;

    private static final String READ_TCATBALF_SITE = "READ-" + TCATBALF_KEY;

    private static Scenario scenarioOf(ParityCase parityCase) {
        return scenarioFrom(parityCase.caseId(), parityCase.unitStimulus());
    }

    private static Scenario scenarioFrom(String caseId, ParityCase.UnitStimulus stimulus) {
        if (!stimulus.operationScript().isEmpty() || !stimulus.linkage().isEmpty()) {
            throw new IllegalArgumentException(PROGRAM + '/' + caseId + " declares an operationScript or "
                + "linkage values. CBTRN02C reaches its six datasets through repositories rather than "
                + "through a called subprogram, and app/jcl/POSTTRAN.jcl declares no PARM on STEP15, so "
                + "it takes no linkage either.");
        }
        if (!stimulus.stepStatuses().isEmpty() || !stimulus.environment().isEmpty()) {
            throw new IllegalArgumentException(PROGRAM + '/' + caseId + " declares a step status or an "
                + "environment variant. app/jcl/POSTTRAN.jcl gates STEP15 with no COND, and none of the "
                + "permitted environment keys names anything this program can observe.");
        }
        if (stimulus.callSiteOutcomes().isEmpty()) {
            return Scenario.CLEAN;
        }
        if (stimulus.callSiteOutcomes().size() > 1) {
            throw new IllegalArgumentException(PROGRAM + '/' + caseId + " declares outcomes at "
                + stimulus.callSiteOutcomes().keySet() + ". One arrangement per case, deliberately: five "
                + "of the six arms end in an abend, so a run arranged to fail at two sites would never "
                + "reach the second and the case would assert half of what it says.");
        }

        Map.Entry<String, ParityCase.CallSiteOutcome> declared =
            stimulus.callSiteOutcomes().entrySet().iterator().next();
        String site = declared.getKey();
        ParityCase.CallSiteOutcome outcome = declared.getValue();
        if (outcome.resp() != null) {
            throw new IllegalArgumentException(PROGRAM + '/' + caseId + " declares a CICS RESP at " + site
                + ". CBTRN02C is a batch program reached by EXEC PGM= in app/jcl/POSTTRAN.jcl and every "
                + "one of its I/O verbs reports a two-character FILE STATUS.");
        }

        if (REWRITE_ACCTFILE_SITE.equals(site)) {
            requireStatus(caseId, site, outcome, FileStatus.NOT_FOUND);
            requireNoRecordCount(caseId, site, outcome);
            return Scenario.ACCOUNT_REWRITE_NOT_FOUND;
        }
        if (OPEN_DALYREJS_SITE.equals(site)) {
            requireRefusal(caseId, site, outcome);
            return Scenario.REJECT_OPEN_FAILS;
        }
        if (WRITE_DALYREJS_SITE.equals(site)) {
            requireRefusal(caseId, site, outcome);
            return Scenario.REJECT_WRITE_FAILS;
        }
        if (READ_DALYTRAN_SITE.equals(site)) {
            requireStatus(caseId, site, outcome, DALYTRAN_PERMANENT_ERROR_STATUS);
            if (!Integer.valueOf(1).equals(outcome.afterRecords())) {
                throw new IllegalArgumentException(PROGRAM + '/' + caseId + " declares afterRecords "
                    + outcome.afterRecords() + " at " + site + ". The arrangement is scoped to the SECOND "
                    + "read on purpose: the first must succeed and deliver a record the run posts in "
                    + "full, so the case can assert both that the failure happened and that what "
                    + "preceded it survived. Declare afterRecords 1.");
            }
            return Scenario.DALYTRAN_READ_FAILS;
        }
        if (READ_TCATBALF_SITE.equals(site)) {
            requireStatus(caseId, site, outcome, TCATBAL_READ_FAILURE_STATUS);
            requireNoRecordCount(caseId, site, outcome);
            return Scenario.TCATBAL_READ_FAILS;
        }
        throw new IllegalArgumentException(PROGRAM + '/' + caseId + " declares an outcome at '" + site
            + "', which is not one of " + List.of(REWRITE_ACCTFILE_SITE, OPEN_DALYREJS_SITE,
                WRITE_DALYREJS_SITE, READ_DALYTRAN_SITE, READ_TCATBALF_SITE)
            + ". Those five are the only seams where this program's guards can be reached at all - every "
            + "other arm is reachable from seeded rows, and arranging one of those would replace the "
            + "subject with the arrangement.");
    }

    private static void requireStatus(String caseId, String site,
                                      ParityCase.CallSiteOutcome outcome, String expected) {
        if (outcome.isRefused() || !expected.equals(outcome.status())) {
            throw new IllegalArgumentException(PROGRAM + '/' + caseId + " declares "
                + (outcome.isRefused() ? "a refusal" : "status '" + outcome.status() + "'") + " at "
                + site + ", and the status that seam reports is '" + expected + "'. It is named in the "
                + "case file rather than assumed so the rendered FILE STATUS IS: line in the "
                + "expectation traces to the declaration that produced it.");
        }
    }

    private static void requireRefusal(String caseId, String site,
                                       ParityCase.CallSiteOutcome outcome) {
        if (!outcome.isRefused() || outcome.status() != null) {
            throw new IllegalArgumentException(PROGRAM + '/' + caseId + " declares status '"
                + outcome.status() + "' at " + site + ". The reject writer's sink reports a "
                + "FileStatus.Outcome rather than a two-character image - OTHER carries no status to "
                + "spell - so this seam is declared as a refusal.");
        }
        requireNoRecordCount(caseId, site, outcome);
    }

    private static void requireNoRecordCount(String caseId, String site,
                                             ParityCase.CallSiteOutcome outcome) {
        if (outcome.afterRecords() != null) {
            throw new IllegalArgumentException(PROGRAM + '/' + caseId + " declares afterRecords at " + site
                + ", which is a single call rather than a loop: there is no record count for the "
                + "arrangement to land after.");
        }
    }

    private static final class CapturedSysout implements SysoutSink {
        private final List<String> lines = new ArrayList<>();

        @Override
        public void write(String line) {
            lines.add(line);
        }

        private List<String> lines() {
            return lines;
        }
    }

    private static final class CollectingRejects implements RecordSink {
        private final Scenario scenario;

        private final List<byte[]> records = new ArrayList<>();

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
            records.add(recordImage);
            return FileStatus.Outcome.OK;
        }

        @Override
        public FileStatus.Outcome discard(int recordsWritten) {
            records.clear();
            return FileStatus.Outcome.OK;
        }

        private List<byte[]> records() {
            return records;
        }
    }

    private record SuppliedBean<T>(T bean) implements ObjectProvider<T> {
        @Override
        public T getObject() {
            return Objects.requireNonNull(bean, "a supplied bean is never null");
        }
    }

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

    private static UnitOutcome invokePostingJob(Invocation invocation) {
        UnitOutcome.Builder recorder = invocation.recorder();
        Scenario scenario = scenarioFrom(invocation.caseId(), invocation.stimulus());
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

    private static DatasetUnitOfWork stepDeclaredUnitOfWork(JdbcTemplate database) {
        return new DatasetUnitOfWork(new JdbcTransactionManager(
                Objects.requireNonNull(database.getDataSource(),
                        "every harness template is built over its own data source")));
    }

    private static <T> T inStepUnitOfWork(java.util.function.Supplier<T> work) {
        boolean synchronizationDeclaredHere = !TransactionSynchronizationManager.isSynchronizationActive();
        if (synchronizationDeclaredHere) {
            TransactionSynchronizationManager.initSynchronization();
        }
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            return work.get();
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
            if (synchronizationDeclaredHere && TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.clearSynchronization();
            }
        }
    }

    private static void inStepUnitOfWork(Runnable work) {
        inStepUnitOfWork(() -> {
            work.run();
            return null;
        });
    }

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

    private static JdbcTemplate database(String discriminator) {
        Objects.requireNonNull(discriminator, "A per-case store is named after the case that owns it");
        JdbcTemplate database = new JdbcTemplate(new RecordImageDataSource());
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

    private static void createRelation(JdbcTemplate database, String dsname, int width) {
        store(database).define(dsname, IMAGE_COLUMN, ColumnForm.CHARACTER, width);
    }

    private static RecordImageStore store(JdbcTemplate database) {
        return ((RecordImageDataSource) Objects.requireNonNull(database.getDataSource(),
            "A per-case template always has its store behind it")).store();
    }

    private static void seedRow(JdbcTemplate database, String dsname, String image) {
        store(database).seed(dsname, image);
    }

    private static List<String> rowsOf(JdbcTemplate database, String dsname, String ordering) {
        List<String> rows = new ArrayList<>(store(database).rows(dsname));
        if (ordering.contains(IMAGE_COLUMN)) {
            rows.sort(Comparator.naturalOrder());
        }
        return rows;
    }

    private static TransactionValidationJob job(JdbcTemplate database, Clock clock,
                                                CapturedSysout sysout, CollectingRejects rejects,
                                                Scenario scenario, Charset charset) {
        DatasetBindings bindings = bindings();
        return new TransactionValidationJob(
            new BatchConfig(new NoBeanPublished<>(), new NoBeanPublished<>(), contracts(), bindings),
            dalyTranRepository(database, bindings, charset, scenario),
            new CardXrefRepository(database, bindings, charset, RecordImageForm.CHARACTER),
            accountRepository(database, bindings, charset, scenario),
            tranCatBalRepository(database, bindings, charset, scenario),
            new TransactionRepository(database, bindings, charset, RecordImageForm.CHARACTER,
                WRITE_ORDER),
            rejectWriter(database, bindings, charset, rejects),
            stepDeclaredUnitOfWork(database),
            new SuppliedBean<SysoutSink>(sysout),
            clock);
    }

    private static DalyTranRepository dalyTranRepository(JdbcTemplate database,
                                                        DatasetBindings bindings, Charset charset,
                                                        Scenario scenario) {
        DalyTranRepository real =
            new DalyTranRepository(database, bindings, charset, RecordImageForm.CHARACTER, WRITE_ORDER);
        if (scenario != Scenario.DALYTRAN_READ_FAILS) {
            return real;
        }
        DalyTranRepository arranged = Mockito.spy(real);
        AtomicInteger reads = new AtomicInteger();
        Mockito.doAnswer(call -> reads.incrementAndGet() == 1
                ? call.callRealMethod()
                : DalyTranRepository.ReadResult.other(DALYTRAN_PERMANENT_ERROR_STATUS))
            .when(arranged).readNext(Mockito.any());
        return arranged;
    }

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

    private static TranCatBalRepository tranCatBalRepository(JdbcTemplate database,
                                                            DatasetBindings bindings,
                                                            Charset charset, Scenario scenario) {
        TranCatBalRepository real =
            new TranCatBalRepository(database, bindings, charset, RecordImageForm.CHARACTER);
        if (scenario != Scenario.TCATBAL_READ_FAILS) {
            return real;
        }
        TranCatBalRepository arranged = Mockito.spy(real);
        Mockito.doAnswer(call -> {
            TranCatBalRepository.TranCatBalFile handle =
                Mockito.spy(real.open(TranCatBalRepository.OpenMode.I_O));
            Mockito.doReturn(TranCatBalRepository.ReadResult.of(TCATBAL_READ_FAILURE_STATUS))
                .when(handle).readByKey(Mockito.any(TranCatBalRecord.TranCatKey.class));
            return handle;
        }).when(arranged).open(TranCatBalRepository.OpenMode.I_O);
        return arranged;
    }

    private static DalyRejectWriter rejectWriter(JdbcTemplate database, DatasetBindings bindings,
                                                 Charset charset, CollectingRejects rejects) {
        DalyRejectWriter real =
            new DalyRejectWriter(database, charset, bindings, RecordImageForm.CHARACTER);
        DalyRejectWriter redirected = Mockito.spy(real);
        Mockito.doAnswer(call -> real.openOutput(rejects)).when(redirected).openOutput();
        return redirected;
    }

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

    @Test
    @DisplayName("decodes every case's stimulus and declares all five arrangements at least once")
    void everyCaseDeclaresADecodableArrangement() {
        Map<Scenario, List<String>> byScenario = new LinkedHashMap<>();
        for (ParityCase parityCase : cases()) {
            Scenario decoded = scenarioOf(parityCase);
            assertThat(decoded)
                .as("%s/%s declares a stimulus that does not decode", PROGRAM, parityCase.caseId())
                .isNotNull();
            byScenario.computeIfAbsent(decoded, key -> new ArrayList<>()).add(parityCase.caseId());
        }

        for (Scenario scenario : Scenario.values()) {
            assertThat(byScenario.get(scenario))
                .as("no case declares %s, so the seam it arranges is reached by nothing in this "
                    + "directory", scenario)
                .isNotNull()
                .isNotEmpty();
        }
        assertThat(byScenario.get(Scenario.CLEAN))
            .as("the ordinary path - every operation succeeding and the case decided by its seeded rows "
                + "alone - must be the majority, or the directory never exercises it")
            .hasSizeGreaterThan(ParityHarness.CASES_PER_PROGRAM / 2);
    }

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

        assertThat(DalyRejectWriter.DESC_TRANSACTION_AFTER_EXPIRATION.length())
            .isLessThan(DalyRejectWriter.WS_VALIDATION_FAIL_REASON_DESC_LENGTH);
    }

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

        assertThat(DalyTranRecord.RECORD_LENGTH).isEqualTo(350);
        assertThat(TranRecord.RECORD_LENGTH).isEqualTo(350);
        assertThat(TranCatBalRecord.RECORD_LENGTH).isEqualTo(50);
        assertThat(TranCatBalRecord.TRAN_CAT_KEY_LENGTH).isEqualTo(17);
        assertThat(AccountRecord.RECORD_LENGTH).isEqualTo(300);
        assertThat(CardXrefRecord.RECORD_LENGTH).isEqualTo(50);
    }

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

        assertThat(outcome.temporaryBalance().orElseThrow().scale())
            .isEqualTo(TransactionValidationJob.WS_TEMP_BAL_SCALE)
            .isEqualTo(CobolDecimal.MONETARY_SCALE);
        assertThat(CobolDecimal.COBOL_ROUNDING.name()).isEqualTo("DOWN");
        assertThat(delegate.runOutcome().orElseThrow().returnCode())
            .isEqualTo(TransactionValidationJob.RETURN_CODE_CLEAN);
    }

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
            stepDeclaredUnitOfWork(database),
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

    private static final String CARD_NUMBER = "4859452612877065";

    private static final long ACCOUNT_ID = 7L;

    private static final int CUSTOMER_ID = 5;

    private static final String TYPE_CODE = "01";

    private static final int CATEGORY_CODE = 1;

    private static final String ORIGIN_TIMESTAMP = "2022-06-10 19:27:53.000000";

    private static Clock pinnedClock() {
        return ParityHarness.fixedClockAt(ParityHarness.DEFAULT_PINNED_CLOCK);
    }

    private static void seedResolvableTransaction(JdbcTemplate database, String amount,
                                                  String creditLimit, String cycleCredit,
                                                  String cycleDebit) {
        seedResolvableTransaction(database, amount, creditLimit, cycleCredit, cycleDebit, "2024-12-13");
    }

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

    private static String imageOf(byte[] record, Charset charset) {
        return new String(record, charset);
    }

    private static int reasonOf(CollectingRejects rejects) {
        assertThat(rejects.records()).hasSize(1);
        return Integer.parseInt(trailerSpan(rejects, DalyRejectWriter.WS_VALIDATION_FAIL_REASON_OFFSET,
            DalyRejectWriter.WS_VALIDATION_FAIL_REASON_LENGTH));
    }

    private static String descriptionOf(CollectingRejects rejects) {
        assertThat(rejects.records()).hasSize(1);
        return trailerSpan(rejects, DalyRejectWriter.WS_VALIDATION_FAIL_REASON_DESC_OFFSET,
            DalyRejectWriter.WS_VALIDATION_FAIL_REASON_DESC_LENGTH).stripTrailing();
    }

    private static String trailerSpan(CollectingRejects rejects, int offset, int length) {
        byte[] record = rejects.records().get(0);
        assertThat(record).hasSize(DalyRejectWriter.RECORD_LENGTH);
        return imageOf(record, ParityHarness.FIXTURE_CHARSET).substring(offset, offset + length);
    }
}
