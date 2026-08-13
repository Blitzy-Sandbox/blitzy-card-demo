package com.vsergeychik.carddemo.transaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vsergeychik.carddemo.card.CardXrefRepository;
import com.vsergeychik.carddemo.card.CardXrefRepository.BrowseCursor;
import com.vsergeychik.carddemo.card.model.CardXrefRecord;
import com.vsergeychik.carddemo.common.AbendException;
import com.vsergeychik.carddemo.common.FileStatus;
import com.vsergeychik.carddemo.common.PhysicalSequence;
import com.vsergeychik.carddemo.common.RecordImageForm;
import com.vsergeychik.carddemo.config.BatchConfig;
import com.vsergeychik.carddemo.config.BatchConfig.JobContract;
import com.vsergeychik.carddemo.config.BatchConfig.JobContracts;
import com.vsergeychik.carddemo.config.BatchConfig.JobParameterContract;
import com.vsergeychik.carddemo.config.BatchConfig.StepContract;
import com.vsergeychik.carddemo.config.BatchConfig.StopRequestedException;
import com.vsergeychik.carddemo.config.BatchConfig.StopSignal;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBinding;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBindings;
import com.vsergeychik.carddemo.common.DatasetUnitOfWork;
import com.vsergeychik.carddemo.statement.StatementGenerationJobA.DatasetUtilityPort;
import com.vsergeychik.carddemo.testdataset.RecordImageDataSource;
import com.vsergeychik.carddemo.transaction.TransactionReportJob.ExecutionSummary;
import com.vsergeychik.carddemo.transaction.TransactionReportJob.SysoutSink;
import com.vsergeychik.carddemo.transaction.model.TranCategoryRecord;
import com.vsergeychik.carddemo.transaction.model.TranRecord;
import com.vsergeychik.carddemo.transaction.model.TranReportLayouts;
import com.vsergeychik.carddemo.transaction.model.TranTypeRecord;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import javax.sql.DataSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.JobInterruptedException;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.scope.context.StepContext;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Tests for {@link TransactionReportJob}, the Java translation of {@code app/cbl/CBTRN03C.cbl}.
 */
@DisplayName("TransactionReportJob - CBTRN03C: prints the transaction detail report, defects included")
class TransactionReportJobTest {
    private static final Charset ASCII = StandardCharsets.US_ASCII;

    private static final PhysicalSequence ORDINAL = PhysicalSequence.of("_ROWID_");

    private static final String TEST_TRANFILE = "TEST.TRANSACT.DALY";

    private static final String TEST_TRANREPT = "TEST.TRANREPT";

    private static final String TEST_CARDXREF = "TEST.CARDXREF";

    private static final String TEST_TRANTYPE = "TEST.TRANTYPE";

    private static final String TEST_TRANCATG = "TEST.TRANCATG";

    private static final String TEST_MASTER = "TEST.TRANSACT.VSAM.KSDS";

    private static final String TEST_BACKUP = "TEST.TRANSACT.BKUP";

    private static final String START_DATE = "2022-01-01";

    private static final String END_DATE = "2022-07-06";

    private static final String IN_RANGE_DATE = "2022-03-15";

    private static final String OUT_OF_RANGE_DATE = "2022-09-01";

    private static final String CARD_ONE = "4111111111111111";

    private static final String CARD_TWO = "4222222222222222";

    private static final String CARD_THREE = "4333333333333333";

    private static final String TYPE_CODE = "01";

    private static final int CATEGORY_CODE = 1;

    private static final String BACKEND_REFUSAL_STATUS = "35";

    private static final String TYPE_DESCRIPTION = "Purchase";

    private static final String CATEGORY_DESCRIPTION = "Regular Sales Draft";

    private static final long ACCOUNT_ID = 12345678901L;

    private static final int CUSTOMER_ID = 123456789;

    private static final class SuppliedProvider<T> implements ObjectProvider<T> {
        private final T bean;

        private SuppliedProvider(T bean) {
            this.bean = bean;
        }

        @Override
        public T getObject() {
            if (bean == null) {
                throw new NoSuchBeanDefinitionException("no bean of this type is published");
            }
            return bean;
        }

        @Override
        public T getIfAvailable() {
            return bean;
        }

        @Override
        public T getIfUnique() {
            return bean;
        }

        @Override
        public Stream<T> stream() {
            return bean == null ? Stream.empty() : Stream.of(bean);
        }
    }

    private static final class InMemoryDatasetUtilityPort implements DatasetUtilityPort {
        private final Map<String, List<String>> datasets = new java.util.LinkedHashMap<>();

        private final List<String> calls = new ArrayList<>();

        @Override
        public int deleteAllRecords(DatasetBinding binding) {
            calls.add("delete " + binding.dsname());
            List<String> removed = datasets.put(binding.dsname(), new ArrayList<>());
            return removed == null ? 0 : removed.size();
        }

        @Override
        public List<String> readAllRecordImages(DatasetBinding binding) {
            calls.add("read " + binding.dsname());
            return List.copyOf(datasets.getOrDefault(binding.dsname(), List.of()));
        }

        @Override
        public int writeRecordImages(DatasetBinding binding, List<String> recordImages) {
            calls.add("write " + binding.dsname());
            datasets.computeIfAbsent(binding.dsname(), key -> new ArrayList<>()).addAll(recordImages);
            return recordImages.size();
        }

        private void seed(String dsname, List<String> recordImages) {
            datasets.put(dsname, new ArrayList<>(recordImages));
        }

        private List<String> contents(String dsname) {
            return List.copyOf(datasets.getOrDefault(dsname, List.of()));
        }
    }

    private static InMemoryDatasetUtilityPort utilityPort() {
        return new InMemoryDatasetUtilityPort();
    }

    private static DatasetUnitOfWork unitOfWork() {
        return new DatasetUnitOfWork(new JdbcTransactionManager(new SimpleDriverDataSource(
                new org.h2.Driver(), "jdbc:h2:mem:report-uow-" + UUID.randomUUID(), "sa", "")));
    }

    private static final class CapturingSysout implements SysoutSink {
        private final List<String> lines = new ArrayList<>();

        @Override
        public void display(String line) {
            lines.add(line);
        }
    }

    private static final class CollectingSink implements TranReportWriter.RecordSink {
        private final List<byte[]> records = new ArrayList<>();

        private final FileStatus.Outcome openOutcome;

        private final FileStatus.Outcome closeOutcome;

        private final int refuseWriteNumber;

        private int discards;

        private int discardCount;

        private boolean closedBeforeDiscard;

        private boolean closed;

        private boolean discardsRefused;

        private CollectingSink() {
            this(FileStatus.Outcome.OK, FileStatus.Outcome.OK, 0);
        }

        private CollectingSink(FileStatus.Outcome openOutcome, FileStatus.Outcome closeOutcome,
                int refuseWriteNumber) {
            this.openOutcome = openOutcome;
            this.closeOutcome = closeOutcome;
            this.refuseWriteNumber = refuseWriteNumber;
        }

        @Override
        public FileStatus.Outcome write(byte[] recordImage) {
            records.add(recordImage);
            return records.size() == refuseWriteNumber
                    ? FileStatus.Outcome.OTHER
                    : FileStatus.Outcome.OK;
        }

        @Override
        public FileStatus.Outcome open() {
            return openOutcome;
        }

        @Override
        public FileStatus.Outcome close() {
            closed = true;
            return closeOutcome;
        }

        @Override
        public FileStatus.Outcome discard(int recordsWritten) {
            discards++;
            discardCount = recordsWritten;
            closedBeforeDiscard = closed;
            return discardsRefused ? FileStatus.Outcome.OTHER : FileStatus.Outcome.OK;
        }

        private List<String> lines() {
            return records.stream().map(bytes -> new String(bytes, ASCII)).toList();
        }
    }

    private static TranRecord record(String tranId, String cardNum, String amount, String procDate) {
        TranRecord tran = new TranRecord(ASCII);
        tran.moveTranId(tranId);
        tran.moveTranTypeCd(TYPE_CODE);
        tran.moveTranCatCd(CATEGORY_CODE);
        tran.moveTranSource("POS TERM  ");
        tran.moveTranDesc("A fixture transaction");
        tran.moveTranAmt(new BigDecimal(amount));
        tran.moveTranMerchantId(999999999L);
        tran.moveTranCardNum(cardNum);
        tran.moveTranOrigTs(procDate + "-00.00.00.000000");
        tran.moveTranProcTs(procDate + "-00.00.00.000000");
        return tran;
    }

    private static DatasetBindings bindings() {
        return bindings(TranRecord.RECORD_LENGTH);
    }

    private static DatasetBindings bindings(int tranFileRecordLength) {
        return bindings(tranFileRecordLength, TEST_CARDXREF);
    }

    private static DatasetBindings bindings(int tranFileRecordLength, String cardxrefDsname) {
        return bindings(tranFileRecordLength, cardxrefDsname, TranRecord.RECORD_LENGTH, "FB");
    }

    private static DatasetBindings bindings(int tranFileRecordLength, int utilityRecordLength,
            String utilityRecordFormat) {
        return bindings(tranFileRecordLength, TEST_CARDXREF, utilityRecordLength, utilityRecordFormat);
    }

    private static DatasetBindings bindings(int tranFileRecordLength, String cardxrefDsname,
            int utilityRecordLength, String utilityRecordFormat) {
        DatasetBindings catalogue = new DatasetBindings();
        catalogue.put(TranReportWriter.DD_NAME, new DatasetBinding(TEST_TRANREPT, "sequential", true,
                "FB", 0, TranReportWriter.RECORD_LENGTH, "CVTRA07Y", null, null, null, null));
        catalogue.put(TransactionReportJob.TRANFILE_DD_NAME, new DatasetBinding(TEST_TRANFILE,
                "sequential", true, "FB", 0, tranFileRecordLength, "CVTRA05Y", null, null, null, null));
        catalogue.put(TransactionReportJob.CARDXREF_DD_NAME, new DatasetBinding(cardxrefDsname, "ksds",
                false, "FB", null, CardXrefRecord.RECORD_LENGTH, "CVACT03Y", null, null, null, null));
        catalogue.put(CardXrefRepository.BASE_DD_NAME, new DatasetBinding(TEST_CARDXREF, "ksds", false,
                "FB", null, CardXrefRecord.RECORD_LENGTH, "CVACT03Y", null, null, null, null));
        catalogue.put(TransactionReportJob.TRANTYPE_DD_NAME, new DatasetBinding(TEST_TRANTYPE, "ksds",
                false, "FB", null, TranTypeRecord.RECORD_LENGTH, "CVTRA03Y", null, null, null, null));
        catalogue.put(TransactionReportJob.TRANCATG_DD_NAME, new DatasetBinding(TEST_TRANCATG, "ksds",
                false, "FB", null, TranCategoryRecord.RECORD_LENGTH, "CVTRA04Y", null, null, null, null));
        catalogue.put(TransactionReportJob.BACKUP_INPUT_DD_NAME, new DatasetBinding(TEST_MASTER,
                "ksds", false, utilityRecordFormat, 0, utilityRecordLength, "CVTRA05Y",
                TranRecord.TRAN_ID_KEY_LENGTH, 0, null, null));
        catalogue.put(TransactionReportJob.BACKUP_OUTPUT_DD_NAME, new DatasetBinding(TEST_BACKUP,
                "sequential", true, utilityRecordFormat, 0, utilityRecordLength, "CVTRA05Y", null,
                null, null, null));
        catalogue.put(TransactionReportJob.SORT_INPUT_DD_NAME, new DatasetBinding(TEST_BACKUP,
                "sequential", true, utilityRecordFormat, 0, utilityRecordLength, "CVTRA05Y", null,
                null, null, null));
        catalogue.put(TransactionReportJob.SORT_OUTPUT_DD_NAME, new DatasetBinding(TEST_TRANFILE,
                "sequential", true, utilityRecordFormat, 0, utilityRecordLength, "CVTRA05Y", null,
                null, null, null));
        return catalogue;
    }

    private static JobContracts contracts(String program, List<StepContract> steps,
            List<JobParameterContract> parameters, String dateRangeSource) {
        JobContracts catalogue = new JobContracts();
        catalogue.put(TransactionReportJob.JOB_KEY,
                new JobContract(program, parameters, steps, dateRangeSource, Map.of()));
        return catalogue;
    }

    private static List<StepContract> declaredSteps() {
        return TransactionReportJob.REQUIRED_STEPS;
    }

    private static List<StepContract> gatedSteps() {
        return declaredSteps().stream()
                .map(step -> new StepContract(step.name(), step.program(), true))
                .toList();
    }

    private static JobContracts validContracts() {
        return contracts(TransactionReportJob.PROGRAM_NAME, declaredSteps(), List.of(),
                TransactionReportJob.DATE_RANGE_SOURCE);
    }

    private static BatchConfig batchConfig(JobContracts contracts, DatasetBindings catalogue) {
        return new BatchConfig(new SuppliedProvider<>(mock(JobRepository.class)),
                new SuppliedProvider<>(mock(PlatformTransactionManager.class)), contracts, catalogue);
    }

    private static BatchConfig validBatchConfig() {
        return batchConfig(validContracts(), bindings());
    }

    private static TranReportWriter writer() {
        return writer(bindings());
    }

    private static TranReportWriter writer(DatasetBindings catalogue) {
        return new TranReportWriter(new JdbcTemplate(), ASCII, catalogue, RecordImageForm.CHARACTER);
    }

    private static TranReportWriter writerOverReachableDestination() {
        try {
            java.sql.PreparedStatement statement = mock(java.sql.PreparedStatement.class);
            when(statement.executeUpdate()).thenReturn(1);
            java.sql.Connection connection = mock(java.sql.Connection.class);
            when(connection.prepareStatement(anyString())).thenReturn(statement);
            javax.sql.DataSource dataSource = mock(javax.sql.DataSource.class);
            when(dataSource.getConnection()).thenReturn(connection);
            return new TranReportWriter(new JdbcTemplate(dataSource), ASCII, bindings(),
                    RecordImageForm.CHARACTER);
        } catch (java.sql.SQLException impossible) {
            throw new IllegalStateException("stubbing a mock does not perform I/O", impossible);
        }
    }

    private static TranReportWriter writerOverAReachableDestination() throws SQLException {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(mock(Statement.class));
        return new TranReportWriter(new JdbcTemplate(dataSource), ASCII, bindings(),
                RecordImageForm.CHARACTER);
    }

    private static final class Harness {
        private final TransactionRepository transactions = mock(TransactionRepository.class);

        private final TransactionRepository.InputFile tranFile =
                mock(TransactionRepository.InputFile.class);

        private final CardXrefRepository xrefs = mock(CardXrefRepository.class);

        private final BrowseCursor xrefCursor = mock(BrowseCursor.class);

        private final TranTypeRepository types = mock(TranTypeRepository.class);

        private final TranCategoryRepository categories = mock(TranCategoryRepository.class);

        private final DateParmReader dateParms = mock(DateParmReader.class);

        private final CollectingSink sink;

        private final CapturingSysout sysout = new CapturingSysout();

        private DatasetBindings catalogue = bindings();

        private Harness(List<TranRecord> records, CollectingSink sink) {
            this.sink = sink;

            when(transactions.openInput(any(DatasetBinding.class))).thenReturn(tranFile);
            when(tranFile.openStatus()).thenReturn(FileStatus.OK);
            when(tranFile.closeInput()).thenReturn(FileStatus.OK);
            when(tranFile.isOpen()).thenAnswer(question -> mockingDetails(tranFile).getInvocations()
                    .stream().noneMatch(call -> "closeInput".equals(call.getMethod().getName())));
            yielding(records);

            when(xrefs.openBrowse()).thenReturn(xrefCursor);
            when(xrefCursor.openStatus()).thenReturn(FileStatus.OK);
            when(xrefCursor.closeBrowse()).thenReturn(FileStatus.OK);
            when(xrefCursor.isOpen()).thenAnswer(question -> mockingDetails(xrefCursor).getInvocations()
                    .stream().noneMatch(call -> "closeBrowse".equals(call.getMethod().getName())));
            when(xrefs.readByCardNumber(anyString())).thenAnswer(invocation ->
                    xrefFound(CardXrefRepository.BASE_DD_NAME,
                            new CardXrefRecord(invocation.getArgument(0), CUSTOMER_ID, ACCOUNT_ID)));

            when(types.open()).thenReturn(FileStatus.OK);
            when(types.close()).thenReturn(FileStatus.OK);
            when(types.readByTranType(anyString())).thenAnswer(invocation ->
                    TranTypeRepository.ReadResult.found(invocation.getArgument(0),
                            TranTypeRecord.of(invocation.getArgument(0), TYPE_DESCRIPTION, ASCII)));

            when(categories.open()).thenReturn(FileStatus.OK);
            when(categories.close()).thenReturn(FileStatus.OK);
            when(categories.keyImage(anyString(), anyInt())).thenAnswer(invocation ->
                    keyImageOf(invocation.getArgument(0), invocation.getArgument(1)));
            when(categories.readByKey(anyString(), anyInt())).thenAnswer(invocation ->
                    TranCategoryRepository.ReadResult.found(TranCategoryRecord.of(
                            invocation.getArgument(0), invocation.getArgument(1),
                            CATEGORY_DESCRIPTION, ASCII)));

            when(dateParms.open()).thenReturn(FileStatus.OK);
            when(dateParms.close()).thenReturn(FileStatus.OK);
            when(dateParms.read()).thenReturn(DateParmReader.ReadResult.found(
                    new DateParmReader.DateParm(START_DATE, " ", END_DATE)));
        }

        private Harness yielding(List<TranRecord> records) {
            List<TransactionRepository.ReadResult> reads = new ArrayList<>();
            records.forEach(record -> reads.add(TransactionRepository.ReadResult.found(
                    TransactionReportJob.TRANFILE_DD_NAME, record)));
            reads.add(TransactionRepository.ReadResult.endOfFile(
                    TransactionReportJob.TRANFILE_DD_NAME));
            when(tranFile.readNext()).thenReturn(reads.get(0),
                    reads.subList(1, reads.size()).toArray(new TransactionRepository.ReadResult[0]));
            return this;
        }

        private TransactionReportJob job() {
            return job(validContracts());
        }

        private TransactionReportJob job(JobContracts jobContracts) {
            return new TransactionReportJob(batchConfig(jobContracts, catalogue), transactions, xrefs,
                    types, categories, dateParms, writer(catalogue), ASCII,
                    new SuppliedProvider<>(null), new JdbcTemplate(), RecordImageForm.CHARACTER, ORDINAL, unitOfWork(), new SuppliedProvider<>(utilityPort()));
        }

        private ExecutionSummary run() {
            return job().execute(sysout, sink);
        }
    }

    private static Harness harness(List<TranRecord> records) {
        return new Harness(records, new CollectingSink());
    }

    private static Harness harness(List<TranRecord> records, CollectingSink sink) {
        return new Harness(records, sink);
    }

    private static StepExecution stepExecution(String stepName) {
        return new StepExecution(stepName, new JobExecution(1L));
    }

    private static ChunkContext chunkContext(StepExecution stepExecution) {
        return new ChunkContext(new StepContext(stepExecution));
    }

    private static StopSignal signalStoppingAfter(StepExecution stepExecution, int permitted) {
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

    private static String keyImageOf(String typeCode, int categoryCode) {
        String digits = Integer.toString(categoryCode);
        return typeCode + "0".repeat(TranCategoryRecord.TRAN_CAT_CD_LENGTH - digits.length()) + digits;
    }

    private static TranReportLayouts expectedLayouts() {
        TranReportLayouts layouts = new TranReportLayouts(ASCII);
        layouts.moveReptStartDate(START_DATE);
        layouts.moveReptEndDate(END_DATE);
        return layouts;
    }

    private static String asRecord(String layoutImage) {
        return layoutImage
                + " ".repeat(TranReportWriter.RECORD_LENGTH - layoutImage.length());
    }

    private static List<String> headerRecords(TranReportLayouts layouts) {
        return List.of(asRecord(layouts.renderReportNameHeader()),
                TranReportWriter.WS_BLANK_LINE_IMAGE,
                asRecord(layouts.renderTransactionHeader1()),
                asRecord(layouts.renderTransactionHeader2()));
    }

    private static String detailRecord(TranReportLayouts layouts, TranRecord tran) {
        layouts.initializeTransactionDetailReport();
        layouts.moveTranReportTransId(tran.tranId());
        layouts.moveTranReportAccountId(ACCOUNT_ID);
        layouts.moveTranReportTypeCd(tran.tranTypeCd());
        layouts.moveTranReportTypeDesc(TYPE_DESCRIPTION);
        layouts.moveTranReportCatCd(tran.tranCatCdImage());
        layouts.moveTranReportCatDesc(CATEGORY_DESCRIPTION);
        layouts.moveTranReportSource(tran.tranSource());
        layouts.moveTranReportAmt(tran.tranAmt());
        return asRecord(layouts.renderTransactionDetailReport());
    }

    private static List<String> pageTotalRecords(TranReportLayouts layouts, BigDecimal total) {
        layouts.moveReptPageTotal(total);
        return List.of(asRecord(layouts.renderReportPageTotals()),
                asRecord(layouts.renderTransactionHeader2()));
    }

    private static List<String> accountTotalRecords(TranReportLayouts layouts, BigDecimal total) {
        layouts.moveReptAccountTotal(total);
        return List.of(asRecord(layouts.renderReportAccountTotals()),
                asRecord(layouts.renderTransactionHeader2()));
    }

    private static String grandTotalRecord(TranReportLayouts layouts, BigDecimal total) {
        layouts.moveReptGrandTotal(total);
        return asRecord(layouts.renderReportGrandTotals());
    }

    private static BigDecimal money(String amount) {
        return new BigDecimal(amount);
    }

    private static List<TranRecord> recordsOnOneCard(int count, String amount) {
        List<TranRecord> records = new ArrayList<>(count);
        for (int ordinal = 1; ordinal <= count; ordinal++) {
            records.add(record(transactionId(ordinal), CARD_ONE, amount, IN_RANGE_DATE));
        }
        return records;
    }

    private static String amountImageAt(String line, int offset) {
        return line.substring(offset, offset + TranReportLayouts.AMOUNT_MASK_WIDTH);
    }

    private static String spanOf(String line, int offset, int length) {
        return line.substring(offset, offset + length);
    }

    private static String transactionId(int ordinal) {
        return "TRAN" + zeroPaddedDigits(ordinal,
                TranReportLayouts.TRAN_REPORT_TRANS_ID_LENGTH - "TRAN".length());
    }

    private static String zeroPaddedDigits(long value, int width) {
        if (value < 0) {
            throw new IllegalArgumentException("A PIC 9 field is unsigned, so " + value
                    + " cannot be rendered into " + width + " digits.");
        }
        String digits = Long.toString(value);
        if (digits.length() > width) {
            throw new IllegalArgumentException(value + " needs " + digits.length()
                    + " digits and the field holds " + width + "; COBOL would truncate on the left, "
                    + "which is never what a fixture intends.");
        }
        return "0".repeat(width - digits.length()) + digits;
    }

    @Nested
    @DisplayName("STEP01R and STEP05R - the REPROC unload and the DFSORT filter-and-sort")
    class PreparatoryStepsTests {
        private final InMemoryDatasetUtilityPort port = utilityPort();

        private TransactionReportJob job() {
            return job(bindings());
        }

        private TransactionReportJob job(DatasetBindings catalogue) {
            return new TransactionReportJob(batchConfig(validContracts(), catalogue),
                    mock(TransactionRepository.class), mock(CardXrefRepository.class),
                    mock(TranTypeRepository.class), mock(TranCategoryRepository.class),
                    mock(DateParmReader.class), writer(catalogue), ASCII,
                    new SuppliedProvider<>(null), new JdbcTemplate(), RecordImageForm.CHARACTER, ORDINAL, unitOfWork(), new SuppliedProvider<>(port));
        }

        private String image(String tranId, String cardNum, String procDate) {
            return new String(record(tranId, cardNum, "0000000100.00", procDate).encode(ASCII), ASCII);
        }

        @Test
        @DisplayName("the job runs all three steps, in the JCL's order (C-02)")
        void theJobRunsAllThreeSteps() {
            Job built = job().transactionReportJob();

            assertThat(built.getName()).isEqualTo(TransactionReportJob.JOB_NAME);
            assertThat(((org.springframework.batch.core.job.SimpleJob) built).getStepNames())
                    .containsExactly(TransactionReportJob.BACKUP_STEP_NAME,
                            TransactionReportJob.SORT_STEP_NAME, TransactionReportJob.STEP_NAME);
        }

        @Test
        @DisplayName("both preparatory steps are named from the contract, so the batch metadata carries "
                + "the JCL's own step names")
        void bothStepsAreNamedFromTheContract() {
            TransactionReportJob job = job();

            assertThat(job.transactionReportBackupStep().getName())
                    .isEqualTo(TransactionReportJob.BACKUP_STEP_NAME);
            assertThat(job.transactionReportSortStep().getName())
                    .isEqualTo(TransactionReportJob.SORT_STEP_NAME);
            assertThat(job.backupStepName()).isEqualTo(TransactionReportJob.BACKUP_STEP_NAME);
            assertThat(job.sortStepName()).isEqualTo(TransactionReportJob.SORT_STEP_NAME);
            assertThat(job.datasetUtilityPort()).isSameAs(port);
        }

        @Test
        @DisplayName("STEP01R copies every record of the master onto the backup generation, unchanged "
                + "and in key order (REPRO INFILE/OUTFILE)")
        void theUnloadCopiesEveryRecordInKeyOrder() {
            String second = image("0000000000000002", CARD_ONE, IN_RANGE_DATE);
            String first = image("0000000000000001", CARD_TWO, IN_RANGE_DATE);
            port.seed(TEST_MASTER, List.of(second, first));

            assertThat(job().unloadTransactionMaster()).isEqualTo(2);

            assertThat(port.contents(TEST_BACKUP)).containsExactly(first, second);
            assertThat(port.calls).containsExactly("read " + TEST_MASTER, "delete " + TEST_BACKUP,
                    "write " + TEST_BACKUP);
        }

        @Test
        @DisplayName("STEP01R empties the backup generation first, because DISP=(NEW,CATLG,DELETE) "
                + "means this run unloads into a new one")
        void theUnloadEmptiesTheDestinationFirst() {
            port.seed(TEST_BACKUP, List.of(image("0000000000000009", CARD_ONE, IN_RANGE_DATE)));
            port.seed(TEST_MASTER, List.of(image("0000000000000001", CARD_ONE, IN_RANGE_DATE)));

            assertThat(job().unloadTransactionMaster()).isOne();

            assertThat(port.contents(TEST_BACKUP))
                    .containsExactly(image("0000000000000001", CARD_ONE, IN_RANGE_DATE));
        }

        @Test
        @DisplayName("a physical-sequential master is unloaded in its stored order, because REPRO of a "
                + "PS file copies the order the records were written in")
        void aSequentialMasterKeepsItsStoredOrder() {
            String second = image("0000000000000002", CARD_ONE, IN_RANGE_DATE);
            String first = image("0000000000000001", CARD_TWO, IN_RANGE_DATE);
            port.seed(TEST_MASTER, List.of(second, first));
            DatasetBindings unkeyed = bindings();
            unkeyed.put(TransactionReportJob.BACKUP_INPUT_DD_NAME, new DatasetBinding(TEST_MASTER,
                    "sequential", false, "FB", 0, TranRecord.RECORD_LENGTH, "CVTRA05Y", null, null,
                    null, null));

            assertThat(job(unkeyed).unloadTransactionMaster()).isEqualTo(2);

            assertThat(port.contents(TEST_BACKUP)).containsExactly(second, first);
        }

        @Test
        @DisplayName("an empty master produces an empty backup generation, not a missing one")
        void anEmptyMasterProducesAnEmptyBackup() {
            assertThat(job().unloadTransactionMaster()).isZero();

            assertThat(port.contents(TEST_BACKUP)).isEmpty();
            assertThat(port.calls).contains("delete " + TEST_BACKUP, "write " + TEST_BACKUP);
        }

        @Test
        @DisplayName("STEP05R keeps the records inside the INCLUDE range and orders them by card "
                + "number, then writes the sorted daily file")
        void theSortFiltersThenOrders() {
            String late = image("0000000000000001", CARD_ONE, OUT_OF_RANGE_DATE);
            String cardTwo = image("0000000000000002", CARD_TWO, IN_RANGE_DATE);
            String cardOne = image("0000000000000003", CARD_ONE, IN_RANGE_DATE);
            port.seed(TEST_BACKUP, List.of(late, cardTwo, cardOne));

            assertThat(job().filterAndSortUnloadedTransactions()).isEqualTo(2);

            assertThat(port.contents(TEST_TRANFILE)).containsExactly(cardOne, cardTwo);
            assertThat(port.calls).containsExactly("read " + TEST_BACKUP, "delete " + TEST_TRANFILE,
                    "write " + TEST_TRANFILE);
        }

        @Test
        @DisplayName("both INCLUDE bounds are inclusive - GE and LE, not GT and LT")
        void bothIncludeBoundsAreInclusive() {
            String atStart = image("0000000000000001", CARD_ONE,
                    TransactionReportJob.SORT_INCLUDE_START_DATE);
            String atEnd = image("0000000000000002", CARD_ONE,
                    TransactionReportJob.SORT_INCLUDE_END_DATE);
            String beforeStart = image("0000000000000003", CARD_ONE, "2021-12-31");
            String afterEnd = image("0000000000000004", CARD_ONE, "2022-07-07");

            assertThat(job().filterAndSort(List.of(atStart, atEnd, beforeStart, afterEnd)))
                    .containsExactly(atStart, atEnd);
        }

        @Test
        @DisplayName("equal card numbers keep their input order, so the detail lines inside an "
                + "account's block are deterministic")
        void equalKeysKeepTheirInputOrder() {
            String third = image("0000000000000003", CARD_ONE, IN_RANGE_DATE);
            String first = image("0000000000000001", CARD_ONE, IN_RANGE_DATE);
            String second = image("0000000000000002", CARD_ONE, IN_RANGE_DATE);

            assertThat(job().filterAndSort(List.of(third, first, second)))
                    .containsExactly(third, first, second);
        }

        @Test
        @DisplayName("no record inside the range is dropped and none outside it survives, whatever the "
                + "order they arrive in")
        void nothingIsDroppedAndNothingLeaks() {
            List<String> mixed = new ArrayList<>();
            for (int index = 1; index <= 6; index++) {
                mixed.add(image(zeroPaddedDigits(index, TranRecord.TRAN_ID_KEY_LENGTH), CARD_ONE,
                        index % 2 == 0 ? IN_RANGE_DATE : OUT_OF_RANGE_DATE));
            }

            assertThat(job().filterAndSort(mixed)).hasSize(3)
                    .allSatisfy(included -> assertThat(included.substring(
                            TranRecord.TRAN_PROC_DT_OFFSET,
                            TranRecord.TRAN_PROC_DT_OFFSET + TranRecord.TRAN_PROC_DT_LENGTH))
                            .isEqualTo(IN_RANGE_DATE));
        }

        @Test
        @DisplayName("an empty unload yields an empty sorted file, which is an empty report rather "
                + "than an error")
        void anEmptyUnloadYieldsAnEmptySortedFile() {
            assertThat(job().filterAndSort(List.of())).isEmpty();
            assertThat(job().filterAndSortUnloadedTransactions()).isZero();
            assertThat(port.contents(TEST_TRANFILE)).isEmpty();
        }

        @Test
        @DisplayName("a record of the wrong width is refused by name, rather than throwing an "
                + "exception about a substring index")
        void aWrongWidthRecordIsRefused() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> job().filterAndSort(List.of("too short")))
                    .withMessageContaining(TransactionReportJob.SORT_INPUT_DD_NAME)
                    .withMessageContaining("350");
            assertThatNullPointerException()
                    .isThrownBy(() -> job().filterAndSort(java.util.Collections.singletonList(null)));
            assertThatNullPointerException().isThrownBy(() -> job().filterAndSort(null));
        }

        @Test
        @DisplayName("the two tasklets report what they wrote and finish")
        void bothTaskletsReportAndFinish() throws Exception {
            port.seed(TEST_MASTER, List.of(image("0000000000000001", CARD_ONE, IN_RANGE_DATE),
                    image("0000000000000002", CARD_TWO, OUT_OF_RANGE_DATE)));
            TransactionReportJob job = job();
            StepContribution unload = contribution();
            StepContribution sort = contribution();

            assertThat(job.transactionReportBackupTasklet()
                    .execute(unload, chunkContext(stepExecution(TransactionReportJob.BACKUP_STEP_NAME))))
                    .isEqualTo(RepeatStatus.FINISHED);
            assertThat(job.transactionReportSortTasklet()
                    .execute(sort, chunkContext(stepExecution(TransactionReportJob.SORT_STEP_NAME))))
                    .isEqualTo(RepeatStatus.FINISHED);

            assertThat(unload.getWriteCount()).isEqualTo(2);
            assertThat(sort.getWriteCount()).isOne();
        }

        @Test
        @DisplayName("the two steps hand over through one dataset: what STEP01R writes is what STEP05R "
                + "reads, and STEP10R's TRANFILE is what STEP05R wrote")
        void thePipelineHandsOverThroughTheConfiguredDatasets() {
            String inRangeCardTwo = image("0000000000000001", CARD_TWO, IN_RANGE_DATE);
            String inRangeCardOne = image("0000000000000002", CARD_ONE, IN_RANGE_DATE);
            String outOfRange = image("0000000000000003", CARD_ONE, OUT_OF_RANGE_DATE);
            port.seed(TEST_MASTER, List.of(inRangeCardTwo, inRangeCardOne, outOfRange));
            TransactionReportJob job = job();

            job.unloadTransactionMaster();
            job.filterAndSortUnloadedTransactions();

            assertThat(port.contents(TEST_BACKUP))
                    .containsExactly(inRangeCardTwo, inRangeCardOne, outOfRange);
            assertThat(port.contents(TEST_TRANFILE))
                    .containsExactly(inRangeCardOne, inRangeCardTwo);
            assertThat(job.tranFileDatasetName()).isEqualTo(TEST_TRANFILE);
        }

        @Test
        @DisplayName("the INCLUDE literals are the JCL's SYMNAMES, and the sort positions are the "
                + "copybook's offsets")
        void theSortSymbolTableIsTranscribed() {
            assertThat(TransactionReportJob.SORT_INCLUDE_START_DATE).isEqualTo("2022-01-01");
            assertThat(TransactionReportJob.SORT_INCLUDE_END_DATE).isEqualTo("2022-07-06");
            assertThat(TransactionReportJob.SORT_TRAN_CARD_NUM_POSITION).isEqualTo(263);
            assertThat(TransactionReportJob.SORT_TRAN_CARD_NUM_LENGTH).isEqualTo(16);
            assertThat(TransactionReportJob.SORT_TRAN_PROC_DT_POSITION).isEqualTo(305);
            assertThat(TransactionReportJob.SORT_TRAN_PROC_DT_LENGTH).isEqualTo(10);
            assertThat(TransactionReportJob.UTILITY_RECORD_FORMAT).isEqualTo("FB");
        }

        @Test
        @DisplayName("a utility DD bound to the wrong width is refused at startup, all four of them")
        void aWrongUtilityWidthIsRefused() {
            for (String ddName : List.of(TransactionReportJob.BACKUP_INPUT_DD_NAME,
                    TransactionReportJob.BACKUP_OUTPUT_DD_NAME,
                    TransactionReportJob.SORT_INPUT_DD_NAME,
                    TransactionReportJob.SORT_OUTPUT_DD_NAME)) {
                DatasetBindings narrow = bindings();
                narrow.put(ddName, new DatasetBinding("TEST.NARROW", "sequential", false, "FB", 0,
                        TranRecord.RECORD_LENGTH - 1, "CVTRA05Y", null, null, null, null));

                assertThatIllegalStateException()
                        .isThrownBy(() -> job(narrow))
                        .withMessageContaining(ddName)
                        .withMessageContaining("349");
            }
        }

        @Test
        @DisplayName("a utility DD that is not RECFM=FB is refused at startup, and an omitted key "
                + "reads as absent rather than as the word null")
        void aWrongUtilityRecordFormatIsRefused() {
            assertThatIllegalStateException()
                    .isThrownBy(() -> job(bindings(TranRecord.RECORD_LENGTH,
                            TranRecord.RECORD_LENGTH, "V")))
                    .withMessageContaining("record-format 'V'")
                    .withMessageContaining("RECFM=FB");
            assertThatIllegalStateException()
                    .isThrownBy(() -> job(bindings(TranRecord.RECORD_LENGTH,
                            TranRecord.RECORD_LENGTH, null)))
                    .withMessageContaining("record-format absent");
            assertThatCode(() -> job(bindings(TranRecord.RECORD_LENGTH, TranRecord.RECORD_LENGTH,
                    "fb"))).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("SORTOUT takes DCB=(*.SORTIN), so a profile that overrode one geometry and not "
                + "the other is refused")
        void theSortOutputMustMatchTheSortInput() {
            DatasetBindings mismatched = bindings();
            mismatched.put(TransactionReportJob.SORT_OUTPUT_DD_NAME, new DatasetBinding(TEST_TRANFILE,
                    "sequential", true, "FBA", 0, TranRecord.RECORD_LENGTH, "CVTRA05Y", null, null,
                    null, null));

            assertThatIllegalStateException()
                    .isThrownBy(() -> job(mismatched))
                    .withMessageContaining("DCB=(*.SORTIN)");
        }

        @Test
        @DisplayName("a utility DD bound to no dataset name is refused at startup")
        void aBlankUtilityDatasetNameIsRefused() {
            DatasetBindings blank = bindings();
            blank.put(TransactionReportJob.SORT_OUTPUT_DD_NAME, new DatasetBinding("   ",
                    "sequential", true, "FB", 0, TranRecord.RECORD_LENGTH, "CVTRA05Y", null, null,
                    null, null));

            assertThatIllegalStateException()
                    .isThrownBy(() -> job(blank))
                    .withMessageContaining(TransactionReportJob.SORT_OUTPUT_DD_NAME);
        }

        @Test
        @DisplayName("the default utility port is the JDBC one the statement job also defaults to, "
                + "when the deployment publishes none")
        void theDefaultPortIsTheJdbcOne() {
            TransactionReportJob job = new TransactionReportJob(validBatchConfig(),
                    mock(TransactionRepository.class), mock(CardXrefRepository.class),
                    mock(TranTypeRepository.class), mock(TranCategoryRepository.class),
                    mock(DateParmReader.class), writer(), ASCII, new SuppliedProvider<>(null),
                    new JdbcTemplate(), RecordImageForm.CHARACTER, ORDINAL, unitOfWork(), new SuppliedProvider<>(null));

            assertThat(job.datasetUtilityPort())
                    .isInstanceOf(com.vsergeychik.carddemo.statement.StatementGenerationJobA
                            .JdbcDatasetUtilityPort.class);
        }

        @Test
        @DisplayName("STEP01R against a master the deployment never allocated states the obligation and "
                + "the return code, rather than surfacing the driver's own refusal")
        void anUnallocatedMasterIsADeploymentObligation() {
            TransactionReportJob job = new TransactionReportJob(validBatchConfig(),
                    mock(TransactionRepository.class), mock(CardXrefRepository.class),
                    mock(TranTypeRepository.class), mock(TranCategoryRepository.class),
                    mock(DateParmReader.class), writer(), ASCII, new SuppliedProvider<>(null),
                    new JdbcTemplate(new RecordImageDataSource()), RecordImageForm.CHARACTER, ORDINAL,
                    unitOfWork(), new SuppliedProvider<>(null));

            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(job::unloadTransactionMaster)
                    .satisfies(abend -> assertThat(abend.getReturnCode())
                            .as("STEP01R ends on the severe-error code, so STEP05R's COND gate reads a "
                                    + "number JCL produces")
                            .isEqualTo(AbendException.RETURN_CODE_IO_ERROR))
                    .withMessageContaining("cannot read a dataset whole")
                    .withMessageContaining(TEST_MASTER)
                    .withMessageContaining("deployment obligation")
                    .withMessageContaining("return code 12")
                    .withMessageContaining("backend refusal: SQLSTATE 42S02")
                    .withNoCause();
        }

        private StepContribution contribution() {
            JobExecution jobExecution = new JobExecution(1L);
            return new StepContribution(new StepExecution("utility", jobExecution));
        }
    }

    @Nested
    @DisplayName("Bounded cancellation - every step yields to a stop request between records")
    class BoundedCancellation {
        private final InMemoryDatasetUtilityPort port = utilityPort();

        private TransactionReportJob jobOverPort() {
            return new TransactionReportJob(validBatchConfig(), mock(TransactionRepository.class),
                    mock(CardXrefRepository.class), mock(TranTypeRepository.class),
                    mock(TranCategoryRepository.class), mock(DateParmReader.class), writer(), ASCII,
                    new SuppliedProvider<>(null), new JdbcTemplate(), RecordImageForm.CHARACTER, ORDINAL, unitOfWork(),
                    new SuppliedProvider<>(port));
        }

        @Test
        @DisplayName("STEP01R stops before writing anything, and the destination is left untouched")
        void theUnloadStopsBeforeWriting() {
            port.seed(TEST_MASTER, List.of(image("0000000000000001", CARD_ONE, IN_RANGE_DATE),
                    image("0000000000000002", CARD_TWO, IN_RANGE_DATE)));
            StepExecution stepExecution = stepExecution(TransactionReportJob.BACKUP_STEP_NAME);
            stepExecution.setTerminateOnly();

            assertThatExceptionOfType(StopRequestedException.class)
                    .isThrownBy(() -> jobOverPort()
                            .unloadTransactionMaster(StopSignal.of(stepExecution)))
                    .withMessageContaining(TransactionReportJob.BACKUP_STEP_NAME)
                    .withCauseInstanceOf(JobInterruptedException.class);

            assertThat(port.contents(TEST_BACKUP)).isEmpty();
            assertThat(port.calls).doesNotContain("write " + TEST_BACKUP);
        }

        @Test
        @DisplayName("STEP05R stops before writing anything, and the daily file is left untouched")
        void theSortStopsBeforeWriting() {
            port.seed(TEST_BACKUP, List.of(image("0000000000000001", CARD_ONE, IN_RANGE_DATE)));
            StepExecution stepExecution = stepExecution(TransactionReportJob.SORT_STEP_NAME);
            stepExecution.setTerminateOnly();

            assertThatExceptionOfType(StopRequestedException.class)
                    .isThrownBy(() -> jobOverPort()
                            .filterAndSortUnloadedTransactions(StopSignal.of(stepExecution)))
                    .withMessageContaining(TransactionReportJob.SORT_STEP_NAME);

            assertThat(port.contents(TEST_TRANFILE)).isEmpty();
        }

        @Test
        @DisplayName("both utility steps run unbounded through their no-signal overloads")
        void theNoSignalOverloadsAreUnbounded() {
            port.seed(TEST_MASTER, List.of(image("0000000000000001", CARD_ONE, IN_RANGE_DATE)));
            TransactionReportJob job = jobOverPort();

            assertThat(job.unloadTransactionMaster()).isOne();
            assertThat(job.filterAndSortUnloadedTransactions()).isOne();
        }

        @Test
        @DisplayName("STEP10R stops between records: whole 133-byte records, and no grand total")
        void theReportStopsBetweenRecords() {
            Harness harness = harness(List.of(
                    record("0000000000000001", CARD_ONE, "0000000100.00", IN_RANGE_DATE),
                    record("0000000000000002", CARD_ONE, "0000000200.00", IN_RANGE_DATE),
                    record("0000000000000003", CARD_ONE, "0000000300.00", IN_RANGE_DATE)));
            StepExecution stepExecution = stepExecution(TransactionReportJob.STEP_NAME);

            assertThatExceptionOfType(StopRequestedException.class).isThrownBy(() ->
                    harness.job().execute(harness.sysout, harness.sink,
                            signalStoppingAfter(stepExecution, 2)));

            assertThat(harness.sink.lines()).isNotEmpty();
            assertThat(harness.sink.lines()).allSatisfy(line ->
                    assertThat(line).hasSize(TranReportWriter.RECORD_LENGTH));

            assertThat(harness.sink.lines()).noneSatisfy(line ->
                    assertThat(line).contains("Grand Total"));
        }

        @Test
        @DisplayName("STEP10R runs the whole pass when no stop is pending, unchanged")
        void withoutAStopTheWholeReportRuns() {
            Harness withSignal = harness(List.of(
                    record("0000000000000001", CARD_ONE, "0000000100.00", IN_RANGE_DATE)));
            Harness withoutSignal = harness(List.of(
                    record("0000000000000001", CARD_ONE, "0000000100.00", IN_RANGE_DATE)));

            withoutSignal.job().execute(withoutSignal.sysout, withoutSignal.sink);
            withSignal.job().execute(withSignal.sysout, withSignal.sink, StopSignal.RUNNING);

            assertThat(withoutSignal.sink.lines()).isEqualTo(withSignal.sink.lines());
        }

        @Test
        @DisplayName("a null stop signal is refused rather than silently treated as 'never stop'")
        void aNullStopSignalIsRefused() {
            Harness harness = harness(List.of());

            assertThatNullPointerException().isThrownBy(() ->
                            harness.job().execute(harness.sysout, harness.sink, null))
                    .withMessageContaining("StopSignal.RUNNING");
        }

        @Test
        @DisplayName("the report tasklet takes its signal from the step execution the framework supplies")
        void theReportTaskletTakesItsSignalFromTheStepExecution() throws SQLException {
            Harness harness = harness(List.of(
                    record("0000000000000001", CARD_ONE, "0000000100.00", IN_RANGE_DATE)));
            TransactionReportJob job = new TransactionReportJob(validBatchConfig(),
                    harness.transactions, harness.xrefs, harness.types, harness.categories,
                    harness.dateParms, writerOverAReachableDestination(), ASCII,
                    new SuppliedProvider<>(harness.sysout), new JdbcTemplate(),
                    RecordImageForm.CHARACTER, ORDINAL, unitOfWork(), new SuppliedProvider<>(utilityPort()));
            StepExecution stepExecution = stepExecution(TransactionReportJob.STEP_NAME);
            stepExecution.setTerminateOnly();

            assertThatExceptionOfType(StopRequestedException.class).isThrownBy(() ->
                    job.transactionReportTasklet().execute(null, chunkContext(stepExecution)));

            assertThat(harness.sysout.lines).first()
                    .isEqualTo(TransactionReportJob.START_OF_EXECUTION);
        }

        @Test
        @DisplayName("the two utility tasklets take their signals from their own step executions")
        void theUtilityTaskletsTakeTheirSignalsFromTheirStepExecutions() throws Exception {
            port.seed(TEST_MASTER, List.of(image("0000000000000001", CARD_ONE, IN_RANGE_DATE)));
            TransactionReportJob job = jobOverPort();
            StepExecution unload = stepExecution(TransactionReportJob.BACKUP_STEP_NAME);
            StepExecution sort = stepExecution(TransactionReportJob.SORT_STEP_NAME);
            unload.setTerminateOnly();

            assertThatExceptionOfType(StopRequestedException.class).isThrownBy(() ->
                    job.transactionReportBackupTasklet().execute(null, chunkContext(unload)));

            assertThat(job.transactionReportSortTasklet()
                    .execute(contributionOver(sort), chunkContext(sort)))
                    .isEqualTo(RepeatStatus.FINISHED);
        }

        private StepContribution contributionOver(StepExecution stepExecution) {
            return new StepContribution(stepExecution);
        }

        private String image(String tranId, String cardNum, String procDate) {
            return new String(record(tranId, cardNum, "0000000100.00", procDate).encode(ASCII), ASCII);
        }
    }

    @Nested
    @DisplayName("Wiring - one job, three tasklet steps, no parameters (gate G3)")
    class Wiring {
        @Test
        @DisplayName("the job and the step are built and named for the JCL")
        void theJobAndStepAreBuilt() {
            TransactionReportJob job = harness(List.of()).job();

            Job built = job.transactionReportJob();
            Step step = job.transactionReportStep();

            assertThat(built.getName()).isEqualTo(TransactionReportJob.JOB_NAME);
            assertThat(step.getName()).isEqualTo(TransactionReportJob.STEP_NAME);
            assertThat(job.stepName()).isEqualTo(TransactionReportJob.STEP_NAME);
            assertThat(job.backupStepName()).isEqualTo(TransactionReportJob.BACKUP_STEP_NAME);
            assertThat(job.sortStepName()).isEqualTo(TransactionReportJob.SORT_STEP_NAME);
        }

        @Test
        @DisplayName("the tasklet runs the program once and finishes")
        void theTaskletRunsOnceAndFinishes() throws Exception {
            Harness harness = harness(List.of());
            TransactionReportJob job = new TransactionReportJob(validBatchConfig(),
                    harness.transactions, harness.xrefs, harness.types, harness.categories,
                    harness.dateParms, writerOverAReachableDestination(), ASCII,
                    new SuppliedProvider<>(harness.sysout), new JdbcTemplate(),
                    RecordImageForm.CHARACTER, ORDINAL, unitOfWork(), new SuppliedProvider<>(utilityPort()));

            assertThat(job.transactionReportTasklet()
                    .execute(null, chunkContext(stepExecution(TransactionReportJob.STEP_NAME))))
                    .isEqualTo(RepeatStatus.FINISHED);
            assertThat(harness.sysout.lines).first()
                    .isEqualTo(TransactionReportJob.START_OF_EXECUTION);
        }

        @Test
        @DisplayName("the accessors report what configuration bound")
        void theAccessorsReportConfiguration() {
            TransactionReportJob job = harness(List.of()).job();

            assertThat(job.tranFileDatasetName()).isEqualTo(TEST_TRANFILE);
            assertThat(job.datasetCharset()).isEqualTo(ASCII);
        }

        @Test
        @DisplayName("the default SYSOUT sink writes to a stream in the dataset code page")
        void theDefaultSysoutSinkWritesEncodedLines() {
            TransactionReportJob job = harness(List.of()).job();
            ByteArrayOutputStream captured = new ByteArrayOutputStream();

            job.sysoutSinkTo(captured).display(TransactionReportJob.START_OF_EXECUTION);

            assertThat(captured.toString(ASCII))
                    .isEqualTo(TransactionReportJob.START_OF_EXECUTION + "\n");
            assertThat(job.defaultSysoutSink()).isNotNull();
        }

        @Test
        @DisplayName("a stream that refuses the bytes is reported rather than swallowed")
        void aRefusingStreamIsReported() {
            TransactionReportJob job = harness(List.of()).job();
            SysoutSink sink = job.sysoutSinkTo(new OutputStream() {
                @Override
                public void write(int oneByte) throws IOException {
                    throw new IOException("the destination is unavailable");
                }
            });

            assertThatExceptionOfType(UncheckedIOException.class)
                    .isThrownBy(() -> sink.display(TransactionReportJob.START_OF_EXECUTION))
                    .withMessageContaining("SYSOUT")
                    .withMessageContaining(TransactionReportJob.PROGRAM_NAME);
            assertThatNullPointerException().isThrownBy(() -> sink.display(null));
        }

        @Test
        @DisplayName("the SORT symbol table agrees with CVTRA05Y, one-based")
        void theSortSymbolTableAgreesWithTheCopybook() {
            assertThat(TransactionReportJob.SORT_TRAN_CARD_NUM_POSITION).isEqualTo(263);
            assertThat(TransactionReportJob.SORT_TRAN_CARD_NUM_LENGTH).isEqualTo(16);
            assertThat(TransactionReportJob.SORT_TRAN_PROC_DT_POSITION).isEqualTo(305);
            assertThat(TransactionReportJob.SORT_TRAN_PROC_DT_LENGTH).isEqualTo(10);
        }

        @Test
        @DisplayName("every one-based position converts to a zero-based offset at both ends of its span")
        void oneBasedPositionsConvertAtBothEnds() {
            assertThat(TransactionReportJob.SORT_TRAN_CARD_NUM_POSITION)
                    .as("the sort key's first byte: zero-based offset %d is one-based position %d",
                            TranRecord.TRAN_CARD_NUM_OFFSET, TranRecord.TRAN_CARD_NUM_OFFSET + 1)
                    .isEqualTo(TranRecord.TRAN_CARD_NUM_OFFSET + 1);
            assertThat(TransactionReportJob.SORT_TRAN_CARD_NUM_POSITION
                    + TransactionReportJob.SORT_TRAN_CARD_NUM_LENGTH - 1)
                    .as("and its last byte, one-based")
                    .isEqualTo(TranRecord.TRAN_CARD_NUM_OFFSET + TranRecord.TRAN_CARD_NUM_LENGTH);
            assertThat(TransactionReportJob.SORT_TRAN_PROC_DT_POSITION)
                    .as("the include filter's first byte")
                    .isEqualTo(TranRecord.TRAN_PROC_DT_OFFSET + 1);
            assertThat(TransactionReportJob.SORT_TRAN_PROC_DT_POSITION
                    + TransactionReportJob.SORT_TRAN_PROC_DT_LENGTH - 1)
                    .as("and its last byte, which must land inside the 350-byte record")
                    .isEqualTo(TranRecord.TRAN_PROC_DT_OFFSET + TranRecord.TRAN_PROC_DT_LENGTH)
                    .isLessThanOrEqualTo(TranRecord.RECORD_LENGTH);
            assertThat(TranReportLayouts.TRAN_REPORT_TYPE_DESC_OFFSET)
                    .as("CVTRA07Y's first FILLER '-' occupies one-based column 32, so the description "
                            + "that follows it starts at zero-based offset 32 and one-based column 33")
                    .isEqualTo(32);
            assertThat(TranReportLayouts.TRAN_REPORT_CAT_DESC_OFFSET)
                    .as("and the second '-' occupies one-based column 53, so its description starts at "
                            + "zero-based offset 53 and one-based column 54")
                    .isEqualTo(53);
            assertThat(TranReportLayouts.TRAN_REPORT_TYPE_CD_OFFSET
                    + TranReportLayouts.TRAN_REPORT_TYPE_CD_LENGTH)
                    .as("the first separator's own zero-based index: one-based column 32 less one")
                    .isEqualTo(31);
            assertThat(TranReportLayouts.TRAN_REPORT_CAT_CD_OFFSET
                    + TranReportLayouts.TRAN_REPORT_CAT_CD_LENGTH)
                    .as("and the second's: one-based column 53 less one")
                    .isEqualTo(52);
            assertThat(TranReportLayouts.TRAN_REPORT_AMT_OFFSET + 1)
                    .as("the amount column starts at one-based 98")
                    .isEqualTo(TranReportLayouts.AMOUNT_COLUMN_START);
            assertThat(TranReportLayouts.TRAN_REPORT_AMT_OFFSET
                    + TranReportLayouts.AMOUNT_MASK_WIDTH)
                    .as("and ends at one-based 112, the last byte of the natural detail layout's data")
                    .isEqualTo(TranReportLayouts.AMOUNT_COLUMN_END);
        }

        @Test
        @DisplayName("the reporting range comes from the DATEPARM bean, and the job declares no "
                + "parameter - unlike INTCALC's PARM='2022071800'")
        void theRangeComesFromTheDatasetAndNotFromAParameter() {
            Harness harness = harness(List.of(record("TRAN000000000001", CARD_ONE, "1.00",
                    IN_RANGE_DATE)));

            harness.run();

            assertThat(TransactionReportJob.DATE_RANGE_SOURCE)
                    .as("the declared source is the DD name, not a parameter name")
                    .isEqualTo(TransactionReportJob.DATEPARM_DD_NAME)
                    .isEqualTo(DateParmReader.DD_NAME);
            assertThat(validContracts().get(TransactionReportJob.JOB_KEY).parameters())
                    .as("TRANREPT.jcl declares no PARM on any of its three steps")
                    .isEmpty();
            verify(harness.dateParms).read();
            assertThat(harness.sysout.lines)
                    .as("and the range it returned is the range the program reports")
                    .contains(TransactionReportJob.REPORTING_FROM + START_DATE
                            + TransactionReportJob.REPORTING_TO + END_DATE);
        }

        @Test
        @DisplayName("the page size is 20, and it is not the online page size of 10")
        void thePageSizeIsTwenty() {
            assertThat(TransactionReportJob.PAGE_SIZE).isEqualTo(20);
        }

        @Test
        @DisplayName("no argument may be null")
        void noArgumentMayBeNull() {
            Harness harness = harness(List.of());

            assertThatNullPointerException().isThrownBy(() -> new TransactionReportJob(null,
                    harness.transactions, harness.xrefs, harness.types, harness.categories,
                    harness.dateParms, writer(), ASCII, new SuppliedProvider<>(null), new JdbcTemplate(), RecordImageForm.CHARACTER, ORDINAL, unitOfWork(), new SuppliedProvider<>(utilityPort())));
            assertThatNullPointerException().isThrownBy(() -> new TransactionReportJob(
                    validBatchConfig(), null, harness.xrefs, harness.types, harness.categories,
                    harness.dateParms, writer(), ASCII, new SuppliedProvider<>(null), new JdbcTemplate(), RecordImageForm.CHARACTER, ORDINAL, unitOfWork(), new SuppliedProvider<>(utilityPort())));
            assertThatNullPointerException().isThrownBy(() -> new TransactionReportJob(
                    validBatchConfig(), harness.transactions, null, harness.types, harness.categories,
                    harness.dateParms, writer(), ASCII, new SuppliedProvider<>(null), new JdbcTemplate(), RecordImageForm.CHARACTER, ORDINAL, unitOfWork(), new SuppliedProvider<>(utilityPort())));
            assertThatNullPointerException().isThrownBy(() -> new TransactionReportJob(
                    validBatchConfig(), harness.transactions, harness.xrefs, null, harness.categories,
                    harness.dateParms, writer(), ASCII, new SuppliedProvider<>(null), new JdbcTemplate(), RecordImageForm.CHARACTER, ORDINAL, unitOfWork(), new SuppliedProvider<>(utilityPort())));
            assertThatNullPointerException().isThrownBy(() -> new TransactionReportJob(
                    validBatchConfig(), harness.transactions, harness.xrefs, harness.types, null,
                    harness.dateParms, writer(), ASCII, new SuppliedProvider<>(null), new JdbcTemplate(), RecordImageForm.CHARACTER, ORDINAL, unitOfWork(), new SuppliedProvider<>(utilityPort())));
            assertThatNullPointerException().isThrownBy(() -> new TransactionReportJob(
                    validBatchConfig(), harness.transactions, harness.xrefs, harness.types,
                    harness.categories, null, writer(), ASCII, new SuppliedProvider<>(null), new JdbcTemplate(), RecordImageForm.CHARACTER, ORDINAL, unitOfWork(), new SuppliedProvider<>(utilityPort())));
            assertThatNullPointerException().isThrownBy(() -> new TransactionReportJob(
                    validBatchConfig(), harness.transactions, harness.xrefs, harness.types,
                    harness.categories, harness.dateParms, null, ASCII,
                    new SuppliedProvider<>(null), new JdbcTemplate(), RecordImageForm.CHARACTER, ORDINAL, unitOfWork(), new SuppliedProvider<>(utilityPort())));
            assertThatNullPointerException().isThrownBy(() -> new TransactionReportJob(
                    validBatchConfig(), harness.transactions, harness.xrefs, harness.types,
                    harness.categories, harness.dateParms, writer(), null,
                    new SuppliedProvider<>(null), new JdbcTemplate(), RecordImageForm.CHARACTER, ORDINAL, unitOfWork(), new SuppliedProvider<>(utilityPort())));
            assertThatNullPointerException().isThrownBy(() -> new TransactionReportJob(
                    validBatchConfig(), harness.transactions, harness.xrefs, harness.types,
                    harness.categories, harness.dateParms, writer(), ASCII, null, new JdbcTemplate(),
                    RecordImageForm.CHARACTER, ORDINAL, unitOfWork(), new SuppliedProvider<>(utilityPort())));
            assertThatNullPointerException().isThrownBy(() -> new TransactionReportJob(
                    validBatchConfig(), harness.transactions, harness.xrefs, harness.types,
                    harness.categories, harness.dateParms, writer(), ASCII,
                    new SuppliedProvider<>(null), null, RecordImageForm.CHARACTER, ORDINAL, unitOfWork(),
                    new SuppliedProvider<>(utilityPort())));
            assertThatNullPointerException().isThrownBy(() -> new TransactionReportJob(
                    validBatchConfig(), harness.transactions, harness.xrefs, harness.types,
                    harness.categories, harness.dateParms, writer(), ASCII,
                    new SuppliedProvider<>(null), new JdbcTemplate(), RecordImageForm.CHARACTER, ORDINAL, unitOfWork(), null));
            assertThatNullPointerException().isThrownBy(() -> new TransactionReportJob(
                    validBatchConfig(), harness.transactions, harness.xrefs, harness.types,
                    harness.categories, harness.dateParms, writer(), ASCII,
                    new SuppliedProvider<>(null), new JdbcTemplate(), RecordImageForm.CHARACTER, ORDINAL,
                    null, new SuppliedProvider<>(utilityPort())))
                    .withMessageContaining(TransactionReportJob.TRANREPT_DD_NAME);
        }

        @Test
        @DisplayName("a null SYSOUT sink is refused by the program body")
        void aNullSysoutSinkIsRefused() {
            TransactionReportJob job = harness(List.of()).job();

            assertThatNullPointerException().isThrownBy(() -> job.execute(null));
        }
    }

    @Nested
    @DisplayName("Startup guards - the contract must still say what the JCL says")
    class StartupGuards {
        @Test
        @DisplayName("CARDXREF and CCXREF pointing at different datasets is refused at construction")
        void divergingCrossReferenceDdNamesAreRefused() {
            Harness harness = harness(List.of());
            harness.catalogue = bindings(TranRecord.RECORD_LENGTH, "TEST.SOMETHING.ELSE");

            assertThatIllegalStateException()
                    .isThrownBy(() -> harness.job(validContracts()))
                    .withMessageContaining(TransactionReportJob.CARDXREF_DD_NAME)
                    .withMessageContaining(CardXrefRepository.BASE_DD_NAME)
                    .withMessageContaining("TEST.SOMETHING.ELSE");
        }

        @Test
        @DisplayName("CARDXREF and CCXREF naming one dataset is accepted, which is the shipped default")
        void agreeingCrossReferenceDdNamesAreAccepted() {
            assertThat(harness(List.of()).job()).isNotNull();
        }

        @Test
        @DisplayName("a contract naming another program is refused")
        void anotherProgramIsRefused() {
            JobContracts wrong = contracts("CBTRN02C", declaredSteps(), List.of(),
                    TransactionReportJob.DATE_RANGE_SOURCE);

            assertThatIllegalStateException()
                    .isThrownBy(() -> harness(List.of()).job(wrong))
                    .withMessageContaining("CBTRN03C")
                    .withMessageContaining(".program");
        }

        @Test
        @DisplayName("a declared job parameter is refused - the date range is a dataset")
        void aDeclaredParameterIsRefused() {
            JobContracts parameterised = contracts(TransactionReportJob.PROGRAM_NAME, declaredSteps(),
                    List.of(new JobParameterContract("parmDate", "string", "2022071800")),
                    TransactionReportJob.DATE_RANGE_SOURCE);

            assertThatIllegalStateException()
                    .isThrownBy(() -> harness(List.of()).job(parameterised))
                    .withMessageContaining(".parameters")
                    .withMessageContaining("DATEPARM");
        }

        @ParameterizedTest
        @ValueSource(strings = {"PARM", "TRANFILE", "parmDate"})
        @DisplayName("a date range sourced from anywhere but DATEPARM is refused")
        void anotherDateRangeSourceIsRefused(String source) {
            JobContracts wrongSource = contracts(TransactionReportJob.PROGRAM_NAME, declaredSteps(),
                    List.of(), source);

            assertThatIllegalStateException()
                    .isThrownBy(() -> harness(List.of()).job(wrongSource))
                    .withMessageContaining("date-range-source");
        }

        @Test
        @DisplayName("an absent date-range source is refused")
        void anAbsentDateRangeSourceIsRefused() {
            JobContracts noSource = contracts(TransactionReportJob.PROGRAM_NAME, declaredSteps(),
                    List.of(), null);

            assertThatIllegalStateException()
                    .isThrownBy(() -> harness(List.of()).job(noSource))
                    .withMessageContaining("not declared");
        }

        @Test
        @DisplayName("a lost or reordered step sequence is refused")
        void aWrongStepSequenceIsRefused() {
            JobContracts oneStep = contracts(TransactionReportJob.PROGRAM_NAME,
                    List.of(declaredSteps().get(2)), List.of(),
                    TransactionReportJob.DATE_RANGE_SOURCE);
            List<StepContract> reversed = new ArrayList<>(declaredSteps());
            Collections.reverse(reversed);
            JobContracts reordered = contracts(TransactionReportJob.PROGRAM_NAME, reversed, List.of(),
                    TransactionReportJob.DATE_RANGE_SOURCE);

            assertThatIllegalStateException()
                    .isThrownBy(() -> harness(List.of()).job(oneStep))
                    .withMessageContaining(".steps");
            assertThatIllegalStateException()
                    .isThrownBy(() -> harness(List.of()).job(reordered))
                    .withMessageContaining("in that order");
        }

        @Test
        @DisplayName("a step re-pointed at another program is refused even when the names and their "
                + "order are right")
        void aStepRunningAnotherProgramIsRefused() {
            List<StepContract> swappedPrograms = List.of(
                    new StepContract(TransactionReportJob.BACKUP_STEP_NAME,
                            TransactionReportJob.SORT_STEP_PROGRAM, false),
                    new StepContract(TransactionReportJob.SORT_STEP_NAME,
                            TransactionReportJob.BACKUP_STEP_PROGRAM, false),
                    new StepContract(TransactionReportJob.STEP_NAME,
                            TransactionReportJob.PROGRAM_NAME, false));

            assertThatIllegalStateException()
                    .isThrownBy(() -> harness(List.of()).job(contracts(
                            TransactionReportJob.PROGRAM_NAME, swappedPrograms, List.of(),
                            TransactionReportJob.DATE_RANGE_SOURCE)))
                    .withMessageContaining("does not declare the step sequence of "
                            + "app/proc/TRANREPT.prc")
                    .withMessageContaining("configured: [STEP01R/SORT, STEP05R/IDCAMS, "
                            + "STEP10R/CBTRN03C]")
                    .withMessageContaining("required:   [STEP01R/IDCAMS, STEP05R/SORT, "
                            + "STEP10R/CBTRN03C]");
        }

        @Test
        @DisplayName("a gated step is refused - TRANREPT.jcl carries no COND")
        void aGatedStepIsRefused() {
            JobContracts gated = contracts(TransactionReportJob.PROGRAM_NAME, gatedSteps(),
                    List.of(), TransactionReportJob.DATE_RANGE_SOURCE);

            assertThatIllegalStateException()
                    .isThrownBy(() -> harness(List.of()).job(gated))
                    .withMessageContaining("require-preceding-exit-code-zero")
                    .withMessageContaining("INCLUDE filter");
        }

        @ParameterizedTest
        @ValueSource(ints = {36, 300, 349, 351, 430})
        @DisplayName("a TRANFILE of any width but 350 is refused")
        void aWrongTranFileWidthIsRefused(int width) {
            Harness harness = harness(List.of());
            harness.catalogue = bindings(width);

            assertThatIllegalStateException()
                    .isThrownBy(harness::job)
                    .withMessageContaining("record-length")
                    .withMessageContaining("350");
        }

        @Test
        @DisplayName("a blank or absent TRANFILE dataset name is refused")
        void aBlankDatasetNameIsRefused() {
            DatasetBindings blank = bindings();
            blank.put(TransactionReportJob.TRANFILE_DD_NAME, new DatasetBinding("   ", "sequential",
                    true, "FB", 0, TranRecord.RECORD_LENGTH, "CVTRA05Y", null, null, null, null));
            Harness harness = harness(List.of());
            harness.catalogue = blank;

            assertThatIllegalStateException()
                    .isThrownBy(harness::job)
                    .withMessageContaining("blank");
        }

        @Test
        @DisplayName("a null TRANFILE dataset name is refused, and reported as missing")
        void aMissingDatasetNameIsRefused() {
            DatasetBindings absent = bindings();
            absent.put(TransactionReportJob.TRANFILE_DD_NAME, new DatasetBinding(null, "sequential",
                    true, "FB", 0, TranRecord.RECORD_LENGTH, "CVTRA05Y", null, null, null, null));
            Harness harness = harness(List.of());
            harness.catalogue = absent;

            assertThatIllegalStateException()
                    .isThrownBy(harness::job)
                    .withMessageContaining("missing");
        }

        @Test
        @DisplayName("a report writer that is not writing 133-byte records is refused")
        void aWrongReportWidthIsRefused() {
            Harness harness = harness(List.of());
            TranReportWriter narrow = mock(TranReportWriter.class);
            when(narrow.recordLength()).thenReturn(TranReportWriter.RECORD_LENGTH - 1);

            assertThatIllegalStateException()
                    .isThrownBy(() -> new TransactionReportJob(validBatchConfig(),
                            harness.transactions, harness.xrefs, harness.types, harness.categories,
                            harness.dateParms, narrow, ASCII, new SuppliedProvider<>(null),
                            new JdbcTemplate(), RecordImageForm.CHARACTER, ORDINAL, unitOfWork(), new SuppliedProvider<>(utilityPort())))
                    .withMessageContaining("133");
        }

        @Test
        @DisplayName("the valid contract is accepted")
        void theValidContractIsAccepted() {
            assertThat(harness(List.of()).job()).isNotNull();
        }
    }

    @Nested
    @DisplayName("The two decisions - MOD against the page size, and the alphanumeric range test")
    class PureDecisions {
        @ParameterizedTest
        @ValueSource(longs = {0L, 20L, 40L, 200L})
        @DisplayName("a multiple of the page size is a page boundary")
        void multiplesArePageBoundaries(long counter) {
            assertThat(TransactionReportJob.isPageBoundary(counter)).isTrue();
        }

        @ParameterizedTest
        @ValueSource(longs = {1L, 4L, 5L, 19L, 21L, 39L})
        @DisplayName("anything else is not - and 4 in particular, which is where the first record sits")
        void othersAreNotPageBoundaries(long counter) {
            assertThat(TransactionReportJob.isPageBoundary(counter)).isFalse();
        }

        @Test
        @DisplayName("a negative counter is impossible for PIC 9(09) COMP-3 and is refused")
        void aNegativeCounterIsRefused() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> TransactionReportJob.isPageBoundary(-1L))
                    .withMessageContaining("unsigned");
        }

        @ParameterizedTest
        @ValueSource(strings = {"2022-01-01", "2022-03-15", "2022-07-06"})
        @DisplayName("the range is inclusive at both ends")
        void theRangeIsInclusive(String procDate) {
            assertThat(TransactionReportJob.withinReportingRange(procDate, START_DATE, END_DATE))
                    .isTrue();
        }

        @ParameterizedTest
        @ValueSource(strings = {"2021-12-31", "2022-07-07", "2022-09-01", "          "})
        @DisplayName("anything outside it is out, spaces included")
        void theRangeExcludesTheRest(String procDate) {
            assertThat(TransactionReportJob.withinReportingRange(procDate, START_DATE, END_DATE))
                    .isFalse();
        }

        @Test
        @DisplayName("the comparison is alphanumeric, not chronological")
        void theComparisonIsAlphanumeric() {
            assertThat(TransactionReportJob.withinReportingRange("15/03/2022", "01/01/2022",
                    "06/07/2022")).isFalse();
        }

        @Test
        @DisplayName("no operand may be null")
        void noOperandMayBeNull() {
            assertThatNullPointerException().isThrownBy(() ->
                    TransactionReportJob.withinReportingRange(null, START_DATE, END_DATE));
            assertThatNullPointerException().isThrownBy(() ->
                    TransactionReportJob.withinReportingRange(IN_RANGE_DATE, null, END_DATE));
            assertThatNullPointerException().isThrownBy(() ->
                    TransactionReportJob.withinReportingRange(IN_RANGE_DATE, START_DATE, null));
        }
    }

    @Nested
    @DisplayName("The report - the complete line sequence, every line 133 bytes (gates G20, G21)")
    class GoldenSequence {
        @Test
        @DisplayName("two records on one card produce four headers, two details, a page pair and a grand")
        void twoRecordsProduceTheWholeSequence() {
            TranRecord first = record("TRAN000000000001", CARD_ONE, "100.00", IN_RANGE_DATE);
            TranRecord second = record("TRAN000000000002", CARD_ONE, "50.00", IN_RANGE_DATE);
            Harness harness = harness(List.of(first, second));

            ExecutionSummary summary = harness.run();

            BigDecimal pageTotal = money("200.00");
            TranReportLayouts layouts = expectedLayouts();
            List<String> expected = new ArrayList<>(headerRecords(layouts));
            expected.add(detailRecord(layouts, first));
            expected.add(detailRecord(layouts, second));
            expected.addAll(pageTotalRecords(layouts, pageTotal));
            expected.add(grandTotalRecord(layouts, pageTotal));

            assertThat(harness.sink.lines())
                    .as("the complete report, in write order")
                    .containsExactlyElementsOf(expected);
            assertThat(summary.reportLinesWritten()).isEqualTo(9);
            assertThat(summary.detailLinesWritten()).isEqualTo(2);
            assertThat(summary.structuralLinesWritten()).isEqualTo(7);
            assertThat(summary.recordsRead()).isEqualTo(2);
            assertThat(summary.lineCounter())
                    .as("four headers, two details, two total lines - the grand total bumps nothing")
                    .isEqualTo(8L);
            assertThat(summary.grandTotal()).isEqualByComparingTo(pageTotal);
            assertThat(summary.returnCode()).isEqualTo(AbendException.RETURN_CODE_OK);
            assertThat(summary.producedReport()).isTrue();
        }

        @Test
        @DisplayName("every emitted record is exactly 133 bytes")
        void everyRecordIsExactly133Bytes() {
            Harness harness = harness(List.of(record("TRAN000000000001", CARD_ONE, "1.00",
                    IN_RANGE_DATE)));

            harness.run();

            assertThat(harness.sink.records)
                    .isNotEmpty()
                    .allSatisfy(bytes -> assertThat(bytes)
                            .hasSize(TranReportWriter.RECORD_LENGTH));
        }

        @Test
        @DisplayName("the rule line is 133 hyphens and the blank line is 133 spaces")
        void theRuleAndBlankLinesAreFullWidth() {
            Harness harness = harness(List.of(record("TRAN000000000001", CARD_ONE, "1.00",
                    IN_RANGE_DATE)));

            harness.run();
            List<String> lines = harness.sink.lines();

            assertThat(lines.get(1))
                    .as("WS-BLANK-LINE PIC X(133) VALUE SPACES")
                    .isEqualTo(" ".repeat(TranReportWriter.RECORD_LENGTH));
            assertThat(lines.get(3))
                    .as("TRANSACTION-HEADER-2 PIC X(133) VALUE ALL '-'")
                    .isEqualTo("-".repeat(TranReportWriter.RECORD_LENGTH));
        }

        @Test
        @DisplayName("the detail line carries the two truncated descriptions and the edited amount")
        void theDetailLineCarriesTheTruncations() {
            TranRecord only = record("TRAN000000000001", CARD_ONE, "-1234.56", IN_RANGE_DATE);
            Harness harness = harness(List.of(only));
            doReturn(TranTypeRepository.ReadResult.found(TYPE_CODE,
                    TranTypeRecord.of(TYPE_CODE, "A".repeat(50), ASCII)))
                    .when(harness.types).readByTranType(anyString());
            doReturn(TranCategoryRepository.ReadResult.found(TranCategoryRecord.of(TYPE_CODE,
                    CATEGORY_CODE, "B".repeat(50), ASCII)))
                    .when(harness.categories).readByKey(anyString(), anyInt());

            harness.run();
            String detail = harness.sink.lines().get(4);

            assertThat(detail).contains("A".repeat(15) + " ");
            assertThat(detail).doesNotContain("A".repeat(16));
            assertThat(detail).contains("B".repeat(29) + " ");
            assertThat(detail).doesNotContain("B".repeat(30));
            assertThat(detail.substring(TranReportLayouts.AMOUNT_OFFSET,
                    TranReportLayouts.AMOUNT_OFFSET + TranReportLayouts.AMOUNT_MASK_WIDTH))
                    .as("the -ZZZ,ZZZ,ZZZ.ZZ mask, 15 bytes, with the leading minus")
                    .isEqualTo(TranReportLayouts.editDetailAmount(money("-1234.56")));
            assertThat(TranReportLayouts.editDetailAmount(money("-1234.56"))).startsWith("-");
            assertThat(TranReportLayouts.editTotalAmount(money("1234.56"))).startsWith("+");
            assertThat(TranReportLayouts.editDetailAmount(money("-1234.56")))
                    .hasSize(TranReportLayouts.AMOUNT_MASK_WIDTH);
            assertThat(TranReportLayouts.editTotalAmount(money("1234.56")))
                    .hasSize(TranReportLayouts.AMOUNT_MASK_WIDTH);
        }

        @Test
        @DisplayName("the detail line's eight moves each land in their own span, and the write counts one")
        void theEightDetailMovesEachLandInTheirOwnSpan() {
            TranRecord only = record("TRAN000000000042", CARD_ONE, "-1234.56", IN_RANGE_DATE);
            Harness harness = harness(List.of(only));

            ExecutionSummary summary = harness.run();
            String detail = harness.sink.lines().get(TransactionReportJob.HEADER_LINES);

            assertThat(spanOf(detail, TranReportLayouts.TRAN_REPORT_TRANS_ID_OFFSET,
                    TranReportLayouts.TRAN_REPORT_TRANS_ID_LENGTH))
                    .as(":363 MOVE TRAN-ID - PIC X(16) to PIC X(16), nothing truncated")
                    .isEqualTo(only.tranId());
            assertThat(spanOf(detail, TranReportLayouts.TRAN_REPORT_ACCOUNT_ID_OFFSET,
                    TranReportLayouts.TRAN_REPORT_ACCOUNT_ID_LENGTH))
                    .as(":364 MOVE XREF-ACCT-ID - from the cross reference, not from the transaction")
                    .isEqualTo(Long.toString(ACCOUNT_ID));
            assertThat(spanOf(detail, TranReportLayouts.TRAN_REPORT_TYPE_CD_OFFSET,
                    TranReportLayouts.TRAN_REPORT_TYPE_CD_LENGTH))
                    .as(":365 MOVE TRAN-TYPE-CD OF TRAN-RECORD")
                    .isEqualTo(TYPE_CODE);
            assertThat(spanOf(detail, TranReportLayouts.TRAN_REPORT_TYPE_DESC_OFFSET,
                    TranReportLayouts.TRAN_REPORT_TYPE_DESC_LENGTH))
                    .as(":366 MOVE TRAN-TYPE-DESC - X(50) into X(15), left-anchored and space-padded")
                    .isEqualTo(TYPE_DESCRIPTION + " ".repeat(
                            TranReportLayouts.TRAN_REPORT_TYPE_DESC_LENGTH
                                    - TYPE_DESCRIPTION.length()));
            assertThat(spanOf(detail, TranReportLayouts.TRAN_REPORT_CAT_CD_OFFSET,
                    TranReportLayouts.TRAN_REPORT_CAT_CD_LENGTH))
                    .as(":367 MOVE TRAN-CAT-CD OF TRAN-RECORD - PIC 9(04), so zero-filled on the left")
                    .isEqualTo("0001");
            assertThat(spanOf(detail, TranReportLayouts.TRAN_REPORT_CAT_DESC_OFFSET,
                    TranReportLayouts.TRAN_REPORT_CAT_DESC_LENGTH))
                    .as(":368 MOVE TRAN-CAT-TYPE-DESC - X(50) into X(29)")
                    .isEqualTo(CATEGORY_DESCRIPTION + " ".repeat(
                            TranReportLayouts.TRAN_REPORT_CAT_DESC_LENGTH
                                    - CATEGORY_DESCRIPTION.length()));
            assertThat(spanOf(detail, TranReportLayouts.TRAN_REPORT_SOURCE_OFFSET,
                    TranReportLayouts.TRAN_REPORT_SOURCE_LENGTH))
                    .as(":369 MOVE TRAN-SOURCE - PIC X(10) to PIC X(10)")
                    .isEqualTo(only.tranSource());
            assertThat(spanOf(detail, TranReportLayouts.TRAN_REPORT_AMT_OFFSET,
                    TranReportLayouts.AMOUNT_MASK_WIDTH))
                    .as(":370 MOVE TRAN-AMT - through the -ZZZ,ZZZ,ZZZ.ZZ mask")
                    .isEqualTo(TranReportLayouts.editDetailAmount(money("-1234.56")));
            assertThat(summary.lineCounter() - TransactionReportJob.HEADER_LINES
                    - TransactionReportJob.TOTALS_BLOCK_LINES)
                    .as(":373 ADD 1 TO WS-LINE-COUNTER, once for the detail write")
                    .isEqualTo(1L);
        }

        @Test
        @DisplayName("the dot leaders are 86, 84 and 86 characters as CVTRA07Y declares")
        void theDotLeadersAreTheDeclaredWidths() {
            assertThat(TranReportLayouts.PAGE_TOTAL_LEADER_IMAGE).hasSize(86);
            assertThat(TranReportLayouts.ACCOUNT_TOTAL_LEADER_IMAGE).hasSize(84);
            assertThat(TranReportLayouts.GRAND_TOTAL_LEADER_IMAGE).hasSize(86);
        }

        @Test
        @DisplayName("SYSOUT is the two banners, the range line, one 350-byte image per record, "
                + "and the two defect-trail lines")
        void sysoutIsTheExpectedSequence() {
            TranRecord first = record("TRAN000000000001", CARD_ONE, "100.00", IN_RANGE_DATE);
            TranRecord second = record("TRAN000000000002", CARD_ONE, "50.00", IN_RANGE_DATE);
            Harness harness = harness(List.of(first, second));

            harness.run();

            assertThat(harness.sysout.lines).containsExactly(
                    TransactionReportJob.START_OF_EXECUTION,
                    TransactionReportJob.REPORTING_FROM + START_DATE
                            + TransactionReportJob.REPORTING_TO + END_DATE,
                    first.displayImage(),
                    second.displayImage(),
                    TransactionReportJob.TRAN_AMT_DISPLAY_LABEL + second.tranAmtImage(),
                    TransactionReportJob.WS_PAGE_TOTAL_DISPLAY_LABEL + "0000001500{",
                    TransactionReportJob.END_OF_EXECUTION);
            assertThat(first.displayImage()).hasSize(TranRecord.RECORD_LENGTH);
        }
    }

    @Nested
    @DisplayName("Defect 1 - NEXT SENTENCE at :177 ends the whole read loop (practice B5)")
    class DefectOne {
        @Test
        @DisplayName("an out-of-range second record truncates the report and suppresses both totals")
        void anOutOfRangeRecordEndsTheReport() {
            TranRecord inRange = record("TRAN000000000001", CARD_ONE, "100.00", IN_RANGE_DATE);
            TranRecord outOfRange = record("TRAN000000000002", CARD_ONE, "50.00", OUT_OF_RANGE_DATE);
            Harness harness = harness(List.of(inRange, outOfRange));

            ExecutionSummary summary = harness.run();

            TranReportLayouts layouts = expectedLayouts();
            List<String> expected = new ArrayList<>(headerRecords(layouts));
            expected.add(detailRecord(layouts, inRange));

            assertThat(harness.sink.lines())
                    .as("four headers and one detail line - and nothing else")
                    .containsExactlyElementsOf(expected);
            assertThat(harness.sink.lines())
                    .as("no page total and no grand total were written")
                    .noneMatch(line -> line.startsWith(TranReportLayouts.PAGE_TOTAL_LABEL_VALUE))
                    .noneMatch(line -> line.startsWith(TranReportLayouts.GRAND_TOTAL_LABEL_VALUE));
            assertThat(summary.reportLinesWritten()).isEqualTo(5);
            assertThat(summary.detailLinesWritten()).isEqualTo(1);
            assertThat(summary.recordsRead())
                    .as("both records were read; only the first was reported")
                    .isEqualTo(2);
            assertThat(summary.grandTotal())
                    .as("the grand total is never written and never grows")
                    .isEqualByComparingTo(money("0.00"));
        }

        @Test
        @DisplayName("a first record out of range produces no report at all")
        void aFirstRecordOutOfRangeProducesNothing() {
            Harness harness = harness(List.of(record("TRAN000000000001", CARD_ONE, "100.00",
                    OUT_OF_RANGE_DATE)));

            ExecutionSummary summary = harness.run();

            assertThat(harness.sink.records).isEmpty();
            assertThat(summary.producedReport()).isFalse();
            assertThat(summary.lineCounter()).isZero();
            assertThat(harness.sysout.lines)
                    .as("only the banners and the range line")
                    .hasSize(3);
        }

        @Test
        @DisplayName("an empty TRANFILE produces no report, because the untouched area holds spaces")
        void anEmptyInputProducesNothing() {
            Harness harness = harness(List.of());

            ExecutionSummary summary = harness.run();

            assertThat(harness.sink.records).isEmpty();
            assertThat(summary.recordsRead()).isZero();
            assertThat(summary.producedReport()).isFalse();
        }
    }

    @Nested
    @DisplayName("Defect 1 - the loop ends mid-file, wherever the out-of-range record sits (practice B5)")
    class DefectOneMidFile {
        @Test
        @DisplayName("record 3 of 5 out of range: records 1-2 are reported and 4-5 never appear")
        void anOutOfRangeThirdRecordStopsAtTheSecond() {
            TranRecord one = record("TRAN000000000001", CARD_ONE, "10.00", IN_RANGE_DATE);
            TranRecord two = record("TRAN000000000002", CARD_ONE, "20.00", IN_RANGE_DATE);
            TranRecord three = record("TRAN000000000003", CARD_ONE, "30.00", OUT_OF_RANGE_DATE);
            TranRecord four = record("TRAN000000000004", CARD_ONE, "40.00", IN_RANGE_DATE);
            TranRecord five = record("TRAN000000000005", CARD_ONE, "50.00", IN_RANGE_DATE);
            Harness harness = harness(List.of(one, two, three, four, five));

            ExecutionSummary summary = harness.run();

            TranReportLayouts layouts = expectedLayouts();
            List<String> expected = new ArrayList<>(headerRecords(layouts));
            expected.add(detailRecord(layouts, one));
            expected.add(detailRecord(layouts, two));

            assertThat(harness.sink.lines())
                    .as("four headers and two detail lines - records 4 and 5 are never reached")
                    .containsExactlyElementsOf(expected);
            assertThat(summary.detailLinesWritten())
                    .as("two, not the four a skip-and-continue translation would produce")
                    .isEqualTo(2);
            assertThat(summary.recordsRead())
                    .as("three reads: the loop ends on the third, so records 4 and 5 are never read")
                    .isEqualTo(3);
            assertThat(harness.sink.lines())
                    .as("neither total block is reached, because the loop left through :177")
                    .noneMatch(line -> line.startsWith(TranReportLayouts.PAGE_TOTAL_LABEL_VALUE))
                    .noneMatch(line -> line.startsWith(TranReportLayouts.ACCOUNT_TOTAL_LABEL_VALUE))
                    .noneMatch(line -> line.startsWith(TranReportLayouts.GRAND_TOTAL_LABEL_VALUE));
            assertThat(summary.grandTotal()).isEqualByComparingTo(money("0.00"));
        }

        @Test
        @DisplayName("record 4 of 5 out of range: records 1-3 are reported and 5 never appears")
        void anOutOfRangeFourthRecordStopsAtTheThird() {
            TranRecord one = record("TRAN000000000001", CARD_ONE, "10.00", IN_RANGE_DATE);
            TranRecord two = record("TRAN000000000002", CARD_ONE, "20.00", IN_RANGE_DATE);
            TranRecord three = record("TRAN000000000003", CARD_ONE, "30.00", IN_RANGE_DATE);
            TranRecord four = record("TRAN000000000004", CARD_ONE, "40.00", OUT_OF_RANGE_DATE);
            TranRecord five = record("TRAN000000000005", CARD_ONE, "50.00", IN_RANGE_DATE);
            Harness harness = harness(List.of(one, two, three, four, five));

            ExecutionSummary summary = harness.run();

            assertThat(summary.detailLinesWritten()).isEqualTo(3);
            assertThat(summary.recordsRead()).isEqualTo(4);
            assertThat(summary.reportLinesWritten())
                    .as("four headers and three details, and no total block")
                    .isEqualTo(7);
        }
    }

    @Nested
    @DisplayName("Defect 2 - the last amount is counted twice at :200 (practice B5)")
    class DefectTwo {
        @Test
        @DisplayName("a single record's amount appears twice in the final page total")
        void theLastAmountIsAddedTwice() {
            BigDecimal amount = money("100.00");
            TranRecord only = record("TRAN000000000001", CARD_ONE, amount.toPlainString(),
                    IN_RANGE_DATE);
            Harness harness = harness(List.of(only));

            ExecutionSummary summary = harness.run();

            BigDecimal doubled = amount.add(amount);
            TranReportLayouts layouts = expectedLayouts();
            assertThat(harness.sink.lines().get(5))
                    .as("the page total is twice the only record's amount")
                    .isEqualTo(pageTotalRecords(layouts, doubled).get(0));
            assertThat(summary.grandTotal())
                    .as("and the grand total inherits the double count through :297")
                    .isEqualByComparingTo(doubled);
        }

        @Test
        @DisplayName("the two diagnostic lines show the amount and the total BEFORE the second add")
        void theDiagnosticLinesPrecedeTheAddition() {
            TranRecord only = record("TRAN000000000001", CARD_ONE, "100.00", IN_RANGE_DATE);
            Harness harness = harness(List.of(only));

            harness.run();

            assertThat(harness.sysout.lines)
                    .contains(TransactionReportJob.TRAN_AMT_DISPLAY_LABEL + only.tranAmtImage())
                    .contains(TransactionReportJob.WS_PAGE_TOTAL_DISPLAY_LABEL + "0000001000{");
            assertThat(TransactionReportJob.TRAN_AMT_DISPLAY_LABEL)
                    .as("the label ends in a space")
                    .endsWith(" ");
            assertThat(TransactionReportJob.WS_PAGE_TOTAL_DISPLAY_LABEL)
                    .as("this one does not, so the label and the digits run together")
                    .doesNotEndWith(" ");
        }
    }

    @Nested
    @DisplayName("Defect 3 - the last account's total is never written at :197-204 (practice B5)")
    class DefectThree {
        @Test
        @DisplayName("a single-account report ends with a page total and a grand total, and no account "
                + "total at all")
        void aSingleAccountReportNeverWritesAnAccountTotal() {
            TranRecord one = record("TRAN000000000001", CARD_ONE, "60.00", IN_RANGE_DATE);
            TranRecord two = record("TRAN000000000002", CARD_ONE, "40.00", IN_RANGE_DATE);
            Harness harness = harness(List.of(one, two));

            harness.run();

            assertThat(harness.sink.lines())
                    .as("WS-ACCOUNT-TOTAL reached 60.00 + 40.00 + 40.00 and was never printed")
                    .noneMatch(line -> line.startsWith(TranReportLayouts.ACCOUNT_TOTAL_LABEL_VALUE));
            assertThat(harness.sink.lines())
                    .as("the two the end-of-file arm does write")
                    .anyMatch(line -> line.startsWith(TranReportLayouts.PAGE_TOTAL_LABEL_VALUE))
                    .anyMatch(line -> line.startsWith(TranReportLayouts.GRAND_TOTAL_LABEL_VALUE));
        }

        @Test
        @DisplayName("three accounts produce two account totals - the last account's is the one lost")
        void theLastOfThreeAccountsLosesItsTotal() {
            TranRecord first = record("TRAN000000000001", CARD_ONE, "11.00", IN_RANGE_DATE);
            TranRecord second = record("TRAN000000000002", CARD_TWO, "22.00", IN_RANGE_DATE);
            TranRecord third = record("TRAN000000000003", CARD_THREE, "33.00", IN_RANGE_DATE);
            Harness harness = harness(List.of(first, second, third));

            harness.run();
            List<String> accountTotals = harness.sink.lines().stream()
                    .filter(line -> line.startsWith(TranReportLayouts.ACCOUNT_TOTAL_LABEL_VALUE))
                    .toList();

            assertThat(accountTotals)
                    .as("three accounts, two breaks, two account totals")
                    .hasSize(2);
            assertThat(accountTotals.get(0))
                    .as("the first card's own 11.00, not a running figure")
                    .contains(TranReportLayouts.editTotalAmount(money("11.00")));
            assertThat(accountTotals.get(1))
                    .as("the second card's own 22.00 - :310 zeroed the field after the first line")
                    .contains(TranReportLayouts.editTotalAmount(money("22.00")));
            assertThat(harness.sink.lines())
                    .as("and 33.00 never appears on an account-total line, only inside the page total")
                    .noneMatch(line -> line.startsWith(TranReportLayouts.ACCOUNT_TOTAL_LABEL_VALUE)
                            && line.contains(TranReportLayouts.editTotalAmount(money("33.00"))));
        }

        @Test
        @DisplayName("even a report that breaks a page still ends without the last account total")
        void aPagedReportStillLosesTheLastAccountTotal() {
            Harness harness = harness(recordsOnOneCard(17, "1.00"));

            harness.run();

            assertThat(harness.sink.lines())
                    .as("two page totals and a grand total, and not one account total")
                    .noneMatch(line -> line.startsWith(TranReportLayouts.ACCOUNT_TOTAL_LABEL_VALUE));
        }
    }

    @Nested
    @DisplayName("Pagination - twenty counted lines to a page, and no break on the first record")
    class Pagination {
        @Test
        @DisplayName("the seventeenth record breaks the page, because the headers advanced the counter")
        void theSeventeenthRecordBreaksThePage() {
            List<TranRecord> records = new ArrayList<>();
            for (int index = 1; index <= 17; index++) {
                records.add(record("TRAN00000000000" + index, CARD_ONE, "1.00", IN_RANGE_DATE));
            }
            Harness harness = harness(records);

            ExecutionSummary summary = harness.run();
            List<String> lines = harness.sink.lines();

            assertThat(lines.get(20))
                    .as("four headers plus sixteen details, then the page total")
                    .startsWith(TranReportLayouts.PAGE_TOTAL_LABEL_VALUE);
            assertThat(lines.stream()
                    .filter(line -> line.startsWith(TranReportLayouts.PAGE_TOTAL_LABEL_VALUE))
                    .count())
                    .as("one page break plus the one at end of file")
                    .isEqualTo(2L);
            assertThat(lines.stream()
                    .filter(line -> line.startsWith(TranReportLayouts.REPT_SHORT_NAME_VALUE))
                    .count())
                    .as("two pages, so the report name header appears twice")
                    .isEqualTo(2L);
            assertThat(summary.detailLinesWritten()).isEqualTo(17);
            assertThat(summary.reportLinesWritten()).isEqualTo(30);
            assertThat(summary.lineCounter()).isEqualTo(29L);
        }

        @Test
        @DisplayName("the header block writes four lines in order and advances the counter by four")
        void theHeaderBlockWritesFourLinesAndCountsFour() {
            TranRecord only = record("TRAN000000000001", CARD_ONE, "1.00", IN_RANGE_DATE);
            Harness harness = harness(List.of(only));

            ExecutionSummary summary = harness.run();
            TranReportLayouts layouts = expectedLayouts();

            assertThat(TransactionReportJob.HEADER_LINES)
                    .as("REPORT-NAME-HEADER, WS-BLANK-LINE, TRANSACTION-HEADER-1, TRANSACTION-HEADER-2")
                    .isEqualTo(4);
            assertThat(TransactionReportJob.TOTALS_BLOCK_LINES)
                    .as("a total line and the rule line that follows it - :296-301 and :309-313")
                    .isEqualTo(2);
            assertThat(harness.sink.lines().subList(0, TransactionReportJob.HEADER_LINES))
                    .as("the four header lines, in the order :325, :329, :333 and :337 write them")
                    .containsExactlyElementsOf(headerRecords(layouts));
            assertThat(summary.lineCounter())
                    .as("four for the headers, one for the detail line, two for the page-total pair - "
                            + "and nothing for the grand total")
                    .isEqualTo(TransactionReportJob.HEADER_LINES + 1L
                            + TransactionReportJob.TOTALS_BLOCK_LINES);
            assertThat(TransactionReportJob.isPageBoundary(TransactionReportJob.HEADER_LINES))
                    .as("MOD(4, 20) = 4, which is why the first record never breaks a page")
                    .isFalse();
        }

        @Test
        @DisplayName("INITIALIZE skips FILLER, so the '-' separators at columns 32 and 53 survive")
        void theDetailSeparatorsSurviveTheInitialize() {
            TranRecord only = record("TRAN000000000001", CARD_ONE, "1.00", IN_RANGE_DATE);
            Harness harness = harness(List.of(only));

            harness.run();
            String detail = harness.sink.lines().get(TransactionReportJob.HEADER_LINES);

            int typeSeparatorIndex = TranReportLayouts.TRAN_REPORT_TYPE_DESC_OFFSET - 1;
            int categorySeparatorIndex = TranReportLayouts.TRAN_REPORT_CAT_DESC_OFFSET - 1;
            assertThat(typeSeparatorIndex)
                    .as("one-based column 32 is zero-based index 31")
                    .isEqualTo(31);
            assertThat(categorySeparatorIndex)
                    .as("one-based column 53 is zero-based index 52")
                    .isEqualTo(52);
            assertThat(detail.charAt(typeSeparatorIndex))
                    .as("the separator between TRAN-REPORT-TYPE-CD and TRAN-REPORT-TYPE-DESC")
                    .isEqualTo('-');
            assertThat(detail.charAt(categorySeparatorIndex))
                    .as("the separator between TRAN-REPORT-CAT-CD and TRAN-REPORT-CAT-DESC")
                    .isEqualTo('-');
            assertThat(detail).hasSize(TranReportWriter.RECORD_LENGTH);
        }

        @Test
        @DisplayName("INITIALIZE blanks the PIC X items, zeros the PIC 9 item and leaves FILLER alone")
        void initializeFollowsTheVerbsRule() {
            TranReportLayouts layouts = expectedLayouts();
            layouts.moveTranReportTransId("TRAN000000000001");
            layouts.moveTranReportCatCd(4321L);
            layouts.moveTranReportAmt(money("12.34"));

            layouts.initializeTransactionDetailReport();
            String initialised = layouts.renderTransactionDetailReport();

            assertThat(layouts.tranReportTransId())
                    .as("PIC X(16) receives SPACE")
                    .isEqualTo(" ".repeat(TranReportLayouts.TRAN_REPORT_TRANS_ID_LENGTH));
            assertThat(layouts.tranReportCatCd())
                    .as("PIC 9(04) receives ZERO, which is '0000' and not four spaces")
                    .isEqualTo("0".repeat(TranReportLayouts.TRAN_REPORT_CAT_CD_LENGTH));
            assertThat(layouts.tranReportCatCdValue()).isZero();
            assertThat(layouts.tranReportAmt())
                    .as("the numeric-edited item receives ZERO and its all-Z mask blanks it")
                    .isEqualTo(" ".repeat(TranReportLayouts.AMOUNT_MASK_WIDTH));
            assertThat(initialised.charAt(TranReportLayouts.TRAN_REPORT_TYPE_DESC_OFFSET - 1))
                    .as("FILLER is not a receiving operand, so one-based column 32 is still '-'")
                    .isEqualTo('-');
            assertThat(initialised.charAt(TranReportLayouts.TRAN_REPORT_CAT_DESC_OFFSET - 1))
                    .as("nor is one-based column 53")
                    .isEqualTo('-');
            assertThat(initialised).hasSize(TranReportLayouts.TRANSACTION_DETAIL_REPORT_LENGTH);
        }

        @Test
        @DisplayName("thirty-one records cross two page boundaries, so three pages and three page totals")
        void thirtyOneRecordsCrossTwoPageBoundaries() {
            Harness harness = harness(recordsOnOneCard(31, "1.00"));

            ExecutionSummary summary = harness.run();
            List<String> lines = harness.sink.lines();

            assertThat(lines.stream()
                    .filter(line -> line.startsWith(TranReportLayouts.PAGE_TOTAL_LABEL_VALUE))
                    .count())
                    .as("record 17, record 31, and end of file")
                    .isEqualTo(3L);
            assertThat(lines.stream()
                    .filter(line -> line.startsWith(TranReportLayouts.REPT_SHORT_NAME_VALUE))
                    .count())
                    .as("three header blocks, so three pages")
                    .isEqualTo(3L);
            assertThat(lines.get(20))
                    .as("the first break, after four headers and sixteen details")
                    .startsWith(TranReportLayouts.PAGE_TOTAL_LABEL_VALUE);
            assertThat(lines.get(40))
                    .as("the second break, thirteen details after the second page opened")
                    .startsWith(TranReportLayouts.PAGE_TOTAL_LABEL_VALUE);
            assertThat(summary.detailLinesWritten()).isEqualTo(31);
            assertThat(summary.reportLinesWritten()).isEqualTo(50);
            assertThat(summary.lineCounter()).isEqualTo(49L);
            assertThat(lines).allSatisfy(line ->
                    assertThat(line).hasSize(TranReportWriter.RECORD_LENGTH));
        }

        @Test
        @DisplayName("each page total covers its own page only, because :298 zeroes it after the write")
        void eachPageTotalCoversItsOwnPage() {
            Harness harness = harness(recordsOnOneCard(31, "1.00"));

            ExecutionSummary summary = harness.run();
            List<String> pageTotals = harness.sink.lines().stream()
                    .filter(line -> line.startsWith(TranReportLayouts.PAGE_TOTAL_LABEL_VALUE))
                    .map(line -> amountImageAt(line, TranReportLayouts.REPT_PAGE_TOTAL_OFFSET))
                    .toList();

            assertThat(pageTotals)
                    .as("16 records on page one, 14 on page two, then record 31 counted twice at "
                            + "end of file by defect 2")
                    .containsExactly(TranReportLayouts.editTotalAmount(money("16.00")),
                            TranReportLayouts.editTotalAmount(money("14.00")),
                            TranReportLayouts.editTotalAmount(money("2.00")));
            assertThat(summary.grandTotal())
                    .as(":297 sums the three page totals, so 16 + 14 + 2 = 32 for 31 records of 1.00")
                    .isEqualByComparingTo(money("32.00"));
        }

        @Test
        @DisplayName("sixteen records fit on one page, so only the end-of-file total appears")
        void sixteenRecordsFitOnOnePage() {
            List<TranRecord> records = new ArrayList<>();
            for (int index = 1; index <= 16; index++) {
                records.add(record("TRAN00000000000" + index, CARD_ONE, "1.00", IN_RANGE_DATE));
            }
            Harness harness = harness(records);

            harness.run();
            List<String> lines = harness.sink.lines();

            assertThat(lines.stream()
                    .filter(line -> line.startsWith(TranReportLayouts.REPT_SHORT_NAME_VALUE))
                    .count())
                    .isEqualTo(1L);
            assertThat(lines.stream()
                    .filter(line -> line.startsWith(TranReportLayouts.PAGE_TOTAL_LABEL_VALUE))
                    .count())
                    .isEqualTo(1L);
        }
    }

    @Nested
    @DisplayName("The account break - and the grand total that only page totals feed")
    class AccountBreak {
        @Test
        @DisplayName("a change of card writes an account total, and the first record does not")
        void aChangeOfCardWritesAnAccountTotal() {
            TranRecord first = record("TRAN000000000001", CARD_ONE, "100.00", IN_RANGE_DATE);
            TranRecord second = record("TRAN000000000002", CARD_TWO, "30.00", IN_RANGE_DATE);
            TranRecord third = record("TRAN000000000003", CARD_TWO, "20.00", IN_RANGE_DATE);
            Harness harness = harness(List.of(first, second, third));

            ExecutionSummary summary = harness.run();

            TranReportLayouts layouts = expectedLayouts();
            List<String> expected = new ArrayList<>(headerRecords(layouts));
            expected.add(detailRecord(layouts, first));
            expected.addAll(accountTotalRecords(layouts, money("100.00")));
            expected.add(detailRecord(layouts, second));
            expected.add(detailRecord(layouts, third));
            expected.addAll(pageTotalRecords(layouts, money("170.00")));
            expected.add(grandTotalRecord(layouts, money("170.00")));

            assertThat(harness.sink.lines()).containsExactlyElementsOf(expected);
            assertThat(summary.lineCounter()).isEqualTo(11L);
            assertThat(harness.sink.lines().stream()
                    .filter(line -> line.startsWith(TranReportLayouts.ACCOUNT_TOTAL_LABEL_VALUE))
                    .count())
                    .as("one account total for the first card; the last account's is never written")
                    .isEqualTo(1L);
        }

        @Test
        @DisplayName("the grand total is the sum of the page totals, NOT of the account totals")
        void theGrandTotalFollowsThePageTotalsOnly() {
            TranRecord first = record("TRAN000000000001", CARD_ONE, "100.00", IN_RANGE_DATE);
            TranRecord second = record("TRAN000000000002", CARD_TWO, "30.00", IN_RANGE_DATE);
            TranRecord third = record("TRAN000000000003", CARD_TWO, "20.00", IN_RANGE_DATE);
            Harness harness = harness(List.of(first, second, third));

            ExecutionSummary summary = harness.run();

            assertThat(summary.grandTotal())
                    .as("100 + 30 + 20 + 20 - the one page total, defect 2 included")
                    .isEqualByComparingTo(money("170.00"));
            assertThat(summary.grandTotal())
                    .as("the account totals written were 100.00 alone, so the two cannot agree")
                    .isNotEqualByComparingTo(money("100.00"));
        }

        @ParameterizedTest
        @ValueSource(ints = {1, 16, 31})
        @DisplayName("the grand-total write increments nothing, so the counter trails the line count by "
                + "exactly one")
        void theGrandTotalWriteIncrementsNothing(int recordCount) {
            Harness harness = harness(recordsOnOneCard(recordCount, "1.00"));

            ExecutionSummary summary = harness.run();

            assertThat(harness.sink.lines().getLast())
                    .as("the grand total is written once, and it is the report's last line")
                    .startsWith(TranReportLayouts.GRAND_TOTAL_LABEL_VALUE);
            assertThat(harness.sink.lines().stream()
                    .filter(line -> line.startsWith(TranReportLayouts.GRAND_TOTAL_LABEL_VALUE))
                    .count())
                    .isEqualTo(1L);
            assertThat(summary.reportLinesWritten() - summary.lineCounter())
                    .as("every write but the grand total's is followed by ADD 1 TO WS-LINE-COUNTER")
                    .isEqualTo(1L);
        }

        @Test
        @DisplayName("the account total is zeroed at scale 2 after each break, never accumulated")
        void theAccountTotalIsZeroedAtScaleTwoAfterEachBreak() {
            TranRecord first = record("TRAN000000000001", CARD_ONE, "11.00", IN_RANGE_DATE);
            TranRecord second = record("TRAN000000000002", CARD_TWO, "22.00", IN_RANGE_DATE);
            TranRecord third = record("TRAN000000000003", CARD_THREE, "33.00", IN_RANGE_DATE);
            Harness harness = harness(List.of(first, second, third));

            ExecutionSummary summary = harness.run();
            List<String> accountAmounts = harness.sink.lines().stream()
                    .filter(line -> line.startsWith(TranReportLayouts.ACCOUNT_TOTAL_LABEL_VALUE))
                    .map(line -> amountImageAt(line, TranReportLayouts.REPT_ACCOUNT_TOTAL_OFFSET))
                    .toList();

            assertThat(accountAmounts)
                    .as("22.00, not 33.00 - the second break starts from the zero :310 moved in")
                    .containsExactly(TranReportLayouts.editTotalAmount(money("11.00")),
                            TranReportLayouts.editTotalAmount(money("22.00")));
            assertThat(summary.grandTotal().scale())
                    .as("the totals are PIC S9(09)V99, so scale 2 - never BigDecimal.ZERO's scale 0")
                    .isEqualTo(TransactionReportJob.TOTAL_SCALE);
            assertThat(BigDecimal.ZERO.scale())
                    .as("which is exactly what BigDecimal.ZERO is not")
                    .isNotEqualTo(TransactionReportJob.TOTAL_SCALE);
        }

        @Test
        @DisplayName("a zero total blanks all fifteen bytes of its amount column, sign included")
        void aZeroTotalRendersAsSpaces() {
            Harness harness = harness(recordsOnOneCard(17, "0.00"));

            ExecutionSummary summary = harness.run();
            String blank = " ".repeat(TranReportLayouts.AMOUNT_MASK_WIDTH);

            assertThat(TranReportLayouts.editTotalAmount(money("0.00")))
                    .as("the all-Z rule, stated as a pure function first")
                    .isEqualTo(blank);
            assertThat(TranReportLayouts.editDetailAmount(money("0.00")))
                    .as("and the detail mask suppresses zero the same way")
                    .isEqualTo(blank);
            assertThat(harness.sink.lines().stream()
                    .filter(line -> line.startsWith(TranReportLayouts.PAGE_TOTAL_LABEL_VALUE))
                    .map(line -> amountImageAt(line, TranReportLayouts.REPT_PAGE_TOTAL_OFFSET)))
                    .as("both page totals - the mid-report break and the one at end of file")
                    .hasSize(2)
                    .allSatisfy(image -> assertThat(image).isEqualTo(blank));
            assertThat(amountImageAt(harness.sink.lines().getLast(),
                    TranReportLayouts.REPT_GRAND_TOTAL_OFFSET))
                    .as("and the grand total, which summed two zero page totals")
                    .isEqualTo(blank);
            assertThat(summary.grandTotal())
                    .as("zero at scale 2, which is not BigDecimal.ZERO but compares equal to it")
                    .isEqualByComparingTo(money("0.00"));
            assertThat(summary.grandTotal().scale()).isEqualTo(TransactionReportJob.TOTAL_SCALE);
        }

        @Test
        @DisplayName("the cross reference is read once per account, not once per record")
        void theCrossReferenceIsReadOncePerAccount() {
            Harness harness = harness(List.of(
                    record("TRAN000000000001", CARD_ONE, "1.00", IN_RANGE_DATE),
                    record("TRAN000000000002", CARD_ONE, "1.00", IN_RANGE_DATE),
                    record("TRAN000000000003", CARD_TWO, "1.00", IN_RANGE_DATE)));

            harness.run();

            verify(harness.xrefs).readByCardNumber(CARD_ONE);
            verify(harness.xrefs).readByCardNumber(CARD_TWO);
            verify(harness.xrefs, never()).readByAccountIdViaAltIndex(ACCOUNT_ID);
        }
    }

    @Nested
    @DisplayName("The arithmetic - sixteen ADDs, two SUBTRACTs, and no rounding decision (gate G28)")
    class Arithmetic {
        @Test
        @DisplayName("the one ADD with two receivers credits the page total and the account total alike")
        void theTwoReceiverAddCreditsBoth() {
            TranRecord first = record("TRAN000000000001", CARD_ONE, "100.00", IN_RANGE_DATE);
            TranRecord second = record("TRAN000000000002", CARD_TWO, "7.00", IN_RANGE_DATE);
            Harness harness = harness(List.of(first, second));

            ExecutionSummary summary = harness.run();
            List<String> lines = harness.sink.lines();

            assertThat(lines.stream()
                    .filter(line -> line.startsWith(TranReportLayouts.ACCOUNT_TOTAL_LABEL_VALUE))
                    .map(line -> amountImageAt(line, TranReportLayouts.REPT_ACCOUNT_TOTAL_OFFSET)))
                    .as("the account receiver took the first card's 100.00")
                    .containsExactly(TranReportLayouts.editTotalAmount(money("100.00")));
            assertThat(lines.stream()
                    .filter(line -> line.startsWith(TranReportLayouts.PAGE_TOTAL_LABEL_VALUE))
                    .map(line -> amountImageAt(line, TranReportLayouts.REPT_PAGE_TOTAL_OFFSET)))
                    .as("and the page receiver took both, plus the last one again through defect 2")
                    .containsExactly(TranReportLayouts.editTotalAmount(money("114.00")));
            assertThat(summary.grandTotal()).isEqualByComparingTo(money("114.00"));
        }

        @Test
        @DisplayName("the totals are exact sums at scale 2, so no rounding mode is ever consulted")
        void theTotalsAreExactSumsAtScaleTwo() {
            TranRecord first = record("TRAN000000000001", CARD_ONE, "111111111.11", IN_RANGE_DATE);
            TranRecord second = record("TRAN000000000002", CARD_ONE, "111111111.11", IN_RANGE_DATE);
            Harness harness = harness(List.of(first, second));

            ExecutionSummary summary = harness.run();

            assertThat(summary.grandTotal())
                    .as("111111111.11 three times - the two records and defect 2's repeat of the last")
                    .isEqualTo(money("333333333.33").setScale(TransactionReportJob.TOTAL_SCALE));
            assertThat(summary.grandTotal().precision())
                    .as("eleven significant digits, which is PIC S9(09)V99 in full")
                    .isEqualTo(TransactionReportJob.TOTAL_INTEGER_DIGITS
                            + TransactionReportJob.TOTAL_SCALE);
            assertThat(amountImageAt(harness.sink.lines().getLast(),
                    TranReportLayouts.REPT_GRAND_TOTAL_OFFSET))
                    .as("and the mask prints every digit, both commas and the sign")
                    .isEqualTo("+333,333,333.33");
        }

        @Test
        @DisplayName("the smallest representable amount accumulates exactly, cent by cent")
        void theSmallestAmountAccumulatesExactly() {
            Harness harness = harness(recordsOnOneCard(3, "0.01"));

            ExecutionSummary summary = harness.run();

            assertThat(summary.grandTotal())
                    .as("four cents: three records and defect 2's repeat")
                    .isEqualTo(money("0.04"));
            String suppressed = " ".repeat(TranReportLayouts.AMOUNT_MASK_WIDTH - 1 - 1
                    - TranReportLayouts.AMOUNT_FRACTION_DIGITS);
            assertThat(amountImageAt(harness.sink.lines().getLast(),
                    TranReportLayouts.REPT_GRAND_TOTAL_OFFSET))
                    .as("suppression stops at the decimal point, so no integer digit prints")
                    .isEqualTo("+" + suppressed + ".04")
                    .hasSize(TranReportLayouts.AMOUNT_MASK_WIDTH);
        }

        @ParameterizedTest
        @ValueSource(ints = {1, 17, 31})
        @DisplayName("every counted write increments once, and the identity holds at every report shape")
        void everyCountedWriteIncrementsOnce(int recordCount) {
            Harness harness = harness(recordsOnOneCard(recordCount, "1.00"));

            ExecutionSummary summary = harness.run();
            long grandTotals = harness.sink.lines().stream()
                    .filter(line -> line.startsWith(TranReportLayouts.GRAND_TOTAL_LABEL_VALUE))
                    .count();

            assertThat(summary.lineCounter())
                    .as("WS-LINE-COUNTER counts every write but the grand total's")
                    .isEqualTo(summary.reportLinesWritten() - grandTotals);
            assertThat(grandTotals).isEqualTo(1L);
        }

        @Test
        @DisplayName("a run that never reaches end of file writes no grand total, so the two agree")
        void anIncompleteRunHasNoGrandTotalToExclude() {
            TranRecord inRange = record("TRAN000000000001", CARD_ONE, "1.00", IN_RANGE_DATE);
            TranRecord outOfRange = record("TRAN000000000002", CARD_ONE, "1.00", OUT_OF_RANGE_DATE);
            Harness harness = harness(List.of(inRange, outOfRange));

            ExecutionSummary summary = harness.run();

            assertThat(harness.sink.lines())
                    .noneMatch(line -> line.startsWith(TranReportLayouts.GRAND_TOTAL_LABEL_VALUE));
            assertThat(summary.lineCounter())
                    .as("four headers and one detail, all five counted")
                    .isEqualTo(summary.reportLinesWritten());
        }
    }

    @Nested
    @DisplayName("0550-DATEPARM-READ - the three arms of its EVALUATE")
    class DateParmRead {
        @Test
        @DisplayName("'00' displays the range and the range governs the record filter")
        void successDisplaysTheRange() {
            Harness harness = harness(List.of(record("TRAN000000000001", CARD_ONE, "1.00",
                    IN_RANGE_DATE)));

            harness.run();

            assertThat(harness.sysout.lines).element(1)
                    .isEqualTo("Reporting from " + START_DATE + " to " + END_DATE);
            assertThat(harness.sink.lines().get(0))
                    .as("the range reaches REPT-START-DATE and REPT-END-DATE of the name header")
                    .contains(START_DATE)
                    .contains(END_DATE);
        }

        @Test
        @DisplayName("'10' sets the end-of-file flag before the loop, so the report body is empty")
        void endOfFileProducesAnEmptyReport() {
            Harness harness = harness(List.of(record("TRAN000000000001", CARD_ONE, "1.00",
                    IN_RANGE_DATE)));
            doReturn(DateParmReader.ReadResult.endOfFile()).when(harness.dateParms).read();

            ExecutionSummary summary = harness.run();

            assertThat(harness.sink.records).isEmpty();
            assertThat(summary.recordsRead())
                    .as("the transaction file is never read at all")
                    .isZero();
            assertThat(summary.returnCode())
                    .as("an empty DATEPARM is not an error")
                    .isEqualTo(AbendException.RETURN_CODE_OK);
            assertThat(harness.sysout.lines).containsExactly(
                    TransactionReportJob.START_OF_EXECUTION,
                    TransactionReportJob.END_OF_EXECUTION);
            verify(harness.tranFile, never()).readNext();
        }

        @ParameterizedTest
        @ValueSource(strings = {"22", "23", "35"})
        @DisplayName("any other status displays the failure, renders the status and abends")
        void anyOtherStatusAbends(String status) {
            Harness harness = harness(List.of());
            doReturn(DateParmReader.ReadResult.other(status)).when(harness.dateParms).read();

            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(harness::run)
                    .satisfies(abend -> assertAbend(abend, harness,
                            TransactionReportJob.ERROR_READING_DATEPARM_FILE, status));
        }
    }

    @Nested
    @DisplayName("1000-TRANFILE-GET-NEXT - '00', then '10', then WHEN OTHER (gates G30, G47)")
    class TranFileRead {
        @ParameterizedTest
        @ValueSource(strings = {"23", "35", "37"})
        @DisplayName("a status the program does not name abends, even when it is one CICS knows")
        void anUnnamedStatusAbends(String status) {
            Harness harness = harness(List.of());
            doReturn(TransactionRepository.ReadResult.other(TransactionReportJob.TRANFILE_DD_NAME,
                    status)).when(harness.tranFile).readNext();

            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(harness::run)
                    .satisfies(abend -> assertAbend(abend, harness,
                            TransactionReportJob.ERROR_READING_TRANSACTION_FILE, status));
        }

        @Test
        @DisplayName("a failing read after some records have been reported keeps the lines it wrote")
        void aFailingReadKeepsTheLinesAlreadyWritten() {
            TranRecord first = record("TRAN000000000001", CARD_ONE, "1.00", IN_RANGE_DATE);
            Harness harness = harness(List.of());
            doReturn(TransactionRepository.ReadResult.found(TransactionReportJob.TRANFILE_DD_NAME,
                            first),
                    TransactionRepository.ReadResult.other(TransactionReportJob.TRANFILE_DD_NAME, "35"))
                    .when(harness.tranFile).readNext();

            assertThatExceptionOfType(AbendException.class).isThrownBy(harness::run);

            assertThat(harness.sink.records)
                    .as("four headers and the one detail line survive the abend")
                    .hasSize(5);
        }
    }

    @Nested
    @DisplayName("The three lookups - a missing row abends, and IO-STATUS is always 0023")
    class Lookups {
        @Test
        @DisplayName("a missing cross-reference row displays the 16-byte key and abends")
        void aMissingCrossReferenceAbends() {
            Harness harness = harness(List.of(record("TRAN000000000001", CARD_ONE, "1.00",
                    IN_RANGE_DATE)));
            doReturn(CardXrefRepository.ReadResult.notFound(CardXrefRepository.BASE_DD_NAME))
                    .when(harness.xrefs).readByCardNumber(anyString());

            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(harness::run)
                    .satisfies(abend -> assertAbend(abend, harness,
                            TransactionReportJob.INVALID_CARD_NUMBER + CARD_ONE,
                            FileStatus.NOT_FOUND));
            assertThat(harness.sink.records)
                    .as("the lookup happens before any line is written")
                    .isEmpty();
        }

        @Test
        @DisplayName("a missing transaction type displays the 2-byte key and abends")
        void aMissingTransactionTypeAbends() {
            Harness harness = harness(List.of(record("TRAN000000000001", CARD_ONE, "1.00",
                    IN_RANGE_DATE)));
            doReturn(TranTypeRepository.ReadResult.notFound(TYPE_CODE))
                    .when(harness.types).readByTranType(anyString());

            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(harness::run)
                    .satisfies(abend -> assertAbend(abend, harness,
                            TransactionReportJob.INVALID_TRANSACTION_TYPE + TYPE_CODE,
                            FileStatus.NOT_FOUND));
        }

        @Test
        @DisplayName("a missing transaction category displays the whole 6-byte key and abends")
        void aMissingTransactionCategoryAbends() {
            Harness harness = harness(List.of(record("TRAN000000000001", CARD_ONE, "1.00",
                    IN_RANGE_DATE)));
            doReturn(TranCategoryRepository.ReadResult.notFound())
                    .when(harness.categories).readByKey(anyString(), anyInt());

            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(harness::run)
                    .satisfies(abend -> assertAbend(abend, harness,
                            TransactionReportJob.INVALID_TRAN_CATG_KEY
                                    + keyImageOf(TYPE_CODE, CATEGORY_CODE),
                            FileStatus.NOT_FOUND));
        }

        @Test
        @DisplayName("the detail line's type and category codes come from TRAN-RECORD, not from the "
                + "category row of the same field names")
        void theDetailCodesComeFromTheTransactionRecord() {
            String foreignTypeCode = "99";
            int foreignCategoryCode = 8888;
            TranRecord only = record("TRAN000000000001", CARD_ONE, "1.00", IN_RANGE_DATE);
            Harness harness = harness(List.of(only));
            doReturn(TranCategoryRepository.ReadResult.found(TranCategoryRecord.of(foreignTypeCode,
                    foreignCategoryCode, CATEGORY_DESCRIPTION, ASCII)))
                    .when(harness.categories).readByKey(anyString(), anyInt());
            doReturn(TranTypeRepository.ReadResult.found(foreignTypeCode,
                    TranTypeRecord.of(foreignTypeCode, TYPE_DESCRIPTION, ASCII)))
                    .when(harness.types).readByTranType(anyString());

            harness.run();
            String detail = harness.sink.lines().get(TransactionReportJob.HEADER_LINES);

            assertThat(detail.substring(TranReportLayouts.TRAN_REPORT_TYPE_CD_OFFSET,
                    TranReportLayouts.TRAN_REPORT_TYPE_CD_OFFSET
                            + TranReportLayouts.TRAN_REPORT_TYPE_CD_LENGTH))
                    .as(":365 moves TRAN-TYPE-CD OF TRAN-RECORD, so the transaction's %s and not the "
                            + "category row's %s", TYPE_CODE, foreignTypeCode)
                    .isEqualTo(TYPE_CODE);
            assertThat(detail.substring(TranReportLayouts.TRAN_REPORT_CAT_CD_OFFSET,
                    TranReportLayouts.TRAN_REPORT_CAT_CD_OFFSET
                            + TranReportLayouts.TRAN_REPORT_CAT_CD_LENGTH))
                    .as(":367 moves TRAN-CAT-CD OF TRAN-RECORD, so the transaction's %d zero-filled and "
                            + "not the category row's %d", CATEGORY_CODE, foreignCategoryCode)
                    .isEqualTo(keyImageOf(TYPE_CODE, CATEGORY_CODE)
                            .substring(TranCategoryRecord.TRAN_CAT_CD_OFFSET));
            assertThat(detail)
                    .as("the description, by contrast, IS the category row's - :368 has no qualifier "
                            + "because TRAN-CAT-TYPE-DESC exists only there")
                    .contains(CATEGORY_DESCRIPTION);
        }

        @Test
        @DisplayName("both lookups are keyed from the transaction's own codes")
        void bothLookupsAreKeyedFromTheTransaction() {
            Harness harness = harness(List.of(record("TRAN000000000001", CARD_ONE, "1.00",
                    IN_RANGE_DATE)));

            harness.run();

            verify(harness.types).readByTranType(TYPE_CODE);
            verify(harness.categories).readByKey(TYPE_CODE, CATEGORY_CODE);
        }

        @Test
        @DisplayName("every INVALID KEY literal ends in a space, as the source writes it")
        void everyInvalidKeyLiteralEndsInASpace() {
            assertThat(TransactionReportJob.INVALID_CARD_NUMBER).endsWith(" ");
            assertThat(TransactionReportJob.INVALID_TRANSACTION_TYPE).endsWith(" ");
            assertThat(TransactionReportJob.INVALID_TRAN_CATG_KEY).endsWith(" ");
        }

        @Test
        @DisplayName("a cross-reference read that FAILS reports its own status, not the INVALID KEY 23")
        void aFailedCrossReferenceReadReportsItsOwnStatus() {
            Harness harness = harness(List.of(record("TRAN000000000001", CARD_ONE, "1.00",
                    IN_RANGE_DATE)));
            doReturn(CardXrefRepository.ReadResult.other(CardXrefRepository.BASE_DD_NAME,
                            BACKEND_REFUSAL_STATUS))
                    .when(harness.xrefs).readByCardNumber(anyString());

            assertNonInvalidKeyContinues(harness, TransactionReportJob.INVALID_CARD_NUMBER);
        }

        @Test
        @DisplayName("a transaction-type read that FAILS reports its own status, not the INVALID KEY 23")
        void aFailedTransactionTypeReadReportsItsOwnStatus() {
            Harness harness = harness(List.of(record("TRAN000000000001", CARD_ONE, "1.00",
                    IN_RANGE_DATE)));
            doReturn(TranTypeRepository.ReadResult.other(TYPE_CODE, BACKEND_REFUSAL_STATUS))
                    .when(harness.types).readByTranType(anyString());

            assertNonInvalidKeyContinues(harness, TransactionReportJob.INVALID_TRANSACTION_TYPE);
        }

        @Test
        @DisplayName("a category read that FAILS reports its own status, not the INVALID KEY 23")
        void aFailedCategoryReadReportsItsOwnStatus() {
            Harness harness = harness(List.of(record("TRAN000000000001", CARD_ONE, "1.00",
                    IN_RANGE_DATE)));
            doReturn(TranCategoryRepository.ReadResult.other(BACKEND_REFUSAL_STATUS))
                    .when(harness.categories).readByKey(anyString(), anyInt());

            assertNonInvalidKeyContinues(harness, TransactionReportJob.INVALID_TRAN_CATG_KEY);
        }

        @Test
        @DisplayName("an extended permanent-error status survives too, in its NNNN9000 rendering")
        void anExtendedStatusSurvivesUnchanged() {
            Harness harness = harness(List.of(record("TRAN000000000001", CARD_ONE, "1.00",
                    IN_RANGE_DATE)));
            doReturn(CardXrefRepository.ReadResult.other(CardXrefRepository.BASE_DD_NAME,
                            CardXrefRepository.PERMANENT_ERROR_STATUS))
                    .when(harness.xrefs).readByCardNumber(anyString());

            assertNonInvalidKeyContinues(harness, TransactionReportJob.INVALID_CARD_NUMBER);
        }

        @Test
        @DisplayName("only NOT_FOUND takes the INVALID KEY arm - every other outcome bypasses it")
        void onlyNotFoundTakesTheInvalidKeyArm() {
            Harness notFound = harness(List.of(record("TRAN000000000001", CARD_ONE, "1.00",
                    IN_RANGE_DATE)));
            doReturn(CardXrefRepository.ReadResult.notFound(CardXrefRepository.BASE_DD_NAME))
                    .when(notFound.xrefs).readByCardNumber(anyString());
            assertThatExceptionOfType(AbendException.class).isThrownBy(notFound::run);

            Harness refused = harness(List.of(record("TRAN000000000001", CARD_ONE, "1.00",
                    IN_RANGE_DATE)));
            doReturn(CardXrefRepository.ReadResult.other(CardXrefRepository.BASE_DD_NAME,
                            BACKEND_REFUSAL_STATUS))
                    .when(refused.xrefs).readByCardNumber(anyString());
            assertThatNoException().isThrownBy(refused::run);

            assertThat(notFound.sysout.lines)
                    .as("the INVALID KEY arm: the DISPLAY, then IO-STATUS 23, then the abend line")
                    .containsSubsequence(TransactionReportJob.INVALID_CARD_NUMBER + CARD_ONE,
                            FileStatus.toDisplayLine(FileStatus.NOT_FOUND),
                            AbendException.ABEND_DISPLAY_TEXT);
            assertThat(refused.sysout.lines)
                    .as("and the other outcome runs none of those three statements: the FILE STATUS item "
                            + "is set, control passes the END-READ and the paragraph exits")
                    .doesNotContain(TransactionReportJob.INVALID_CARD_NUMBER + CARD_ONE,
                            FileStatus.toDisplayLine(FileStatus.NOT_FOUND),
                            AbendException.ABEND_DISPLAY_TEXT);
            assertThat(refused.sink.lines())
                    .as("so that run produced a report where the invalid-key one produced none")
                    .isNotEmpty();
        }

        private void assertNonInvalidKeyContinues(Harness harness, String invalidKeyLiteral) {
            assertThatNoException()
                    .as("a non-invalid-key failure does not end the run: control passes the END-READ")
                    .isThrownBy(harness::run);

            assertThat(harness.sysout.lines)
                    .as("the INVALID KEY imperative does not run, so its DISPLAY never happens")
                    .noneSatisfy(line -> assertThat(line).startsWith(invalidKeyLiteral));
            assertThat(harness.sysout.lines)
                    .as("nor is IO-STATUS overwritten with 23, which would discard the real status")
                    .doesNotContain(FileStatus.toDisplayLine(FileStatus.NOT_FOUND));
            assertThat(harness.sysout.lines)
                    .as("and 9999-ABEND-PROGRAM does not run at all")
                    .doesNotContain(AbendException.ABEND_DISPLAY_TEXT);
            assertThat(harness.sink.lines())
                    .as("the report was still produced, from the unchanged lookup area")
                    .isNotEmpty();
        }
    }

    @Nested
    @DisplayName("The open ladders - six paragraphs, six messages (gate G47)")
    class OpenLadders {
        @ParameterizedTest
        @ValueSource(strings = {"10", "22", "23", "35"})
        @DisplayName("a TRANFILE open that is not '00' abends with its own message")
        void tranFileOpenFailureAbends(String status) {
            Harness harness = harness(List.of());
            doReturn(status).when(harness.tranFile).openStatus();

            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(harness::run)
                    .satisfies(abend -> assertAbend(abend, harness,
                            TransactionReportJob.ERROR_OPENING_TRANFILE, status));
        }

        @Test
        @DisplayName("a REPTFILE open that fails abends with 'ERROR OPENING REPTFILE'")
        void reptFileOpenFailureAbends() {
            Harness harness = harness(List.of(), new CollectingSink(FileStatus.Outcome.OTHER,
                    FileStatus.Outcome.OK, 0));

            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(harness::run)
                    .satisfies(abend -> assertAbend(abend, harness,
                            TransactionReportJob.ERROR_OPENING_REPTFILE,
                            TransactionReportJob.PERMANENT_ERROR_STATUS));
        }

        @Test
        @DisplayName("a CARDXREF open that fails abends with 'ERROR OPENING CROSS REF FILE'")
        void cardXrefOpenFailureAbends() {
            Harness harness = harness(List.of());
            doReturn("35").when(harness.xrefCursor).openStatus();

            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(harness::run)
                    .satisfies(abend -> assertAbend(abend, harness,
                            TransactionReportJob.ERROR_OPENING_CROSS_REF_FILE, "35"));
        }

        @Test
        @DisplayName("a TRANTYPE open that fails abends with its own message")
        void tranTypeOpenFailureAbends() {
            Harness harness = harness(List.of());
            doReturn("35").when(harness.types).open();

            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(harness::run)
                    .satisfies(abend -> assertAbend(abend, harness,
                            TransactionReportJob.ERROR_OPENING_TRANSACTION_TYPE_FILE, "35"));
        }

        @Test
        @DisplayName("a TRANCATG open that fails abends with its own message")
        void tranCatgOpenFailureAbends() {
            Harness harness = harness(List.of());
            doReturn("35").when(harness.categories).open();

            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(harness::run)
                    .satisfies(abend -> assertAbend(abend, harness,
                            TransactionReportJob.ERROR_OPENING_TRANSACTION_CATG_FILE, "35"));
        }

        @Test
        @DisplayName("a DATEPARM open that fails abends with its own message")
        void dateParmOpenFailureAbends() {
            Harness harness = harness(List.of());
            doReturn("35").when(harness.dateParms).open();

            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(harness::run)
                    .satisfies(abend -> assertAbend(abend, harness,
                            TransactionReportJob.ERROR_OPENING_DATE_PARM_FILE, "35"));
        }

        @Test
        @DisplayName("the opens happen in source order, so the first failure is the one reported")
        void theOpensHappenInSourceOrder() {
            Harness harness = harness(List.of());
            doReturn("35").when(harness.tranFile).openStatus();
            doReturn("35").when(harness.xrefCursor).openStatus();

            assertThatExceptionOfType(AbendException.class).isThrownBy(harness::run);

            assertThat(harness.sysout.lines)
                    .as("TRANFILE is opened first, so its message is the one that appears")
                    .contains(TransactionReportJob.ERROR_OPENING_TRANFILE)
                    .doesNotContain(TransactionReportJob.ERROR_OPENING_CROSS_REF_FILE);
        }
    }

    @Nested
    @DisplayName("The close ladders - six paragraphs, two arithmetic forms (gates G28, G47)")
    class CloseLadders {
        @Test
        @DisplayName("a TRANFILE close that fails abends after the report has been written")
        void tranFileCloseFailureAbends() {
            Harness harness = harness(List.of(record("TRAN000000000001", CARD_ONE, "1.00",
                    IN_RANGE_DATE)));
            doReturn("35").when(harness.tranFile).closeInput();

            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(harness::run)
                    .satisfies(abend -> assertAbend(abend, harness,
                            TransactionReportJob.ERROR_CLOSING_POSTED_TRANSACTION_FILE, "35"));
            assertThat(harness.sink.records)
                    .as("the whole report was written before the close was attempted")
                    .hasSize(8);
        }

        @Test
        @DisplayName("a REPTFILE close that fails abends with 'ERROR CLOSING REPORT FILE'")
        void reptFileCloseFailureAbends() {
            Harness harness = harness(List.of(), new CollectingSink(FileStatus.Outcome.OK,
                    FileStatus.Outcome.OTHER, 0));

            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(harness::run)
                    .satisfies(abend -> assertAbend(abend, harness,
                            TransactionReportJob.ERROR_CLOSING_REPORT_FILE,
                            TransactionReportJob.PERMANENT_ERROR_STATUS));
        }

        @Test
        @DisplayName("a CARDXREF close that fails abends with 'ERROR CLOSING CROSS REF FILE'")
        void cardXrefCloseFailureAbends() {
            Harness harness = harness(List.of());
            doReturn("35").when(harness.xrefCursor).closeBrowse();

            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(harness::run)
                    .satisfies(abend -> assertAbend(abend, harness,
                            TransactionReportJob.ERROR_CLOSING_CROSS_REF_FILE, "35"));
        }

        @Test
        @DisplayName("a TRANTYPE close that fails abends with its own message")
        void tranTypeCloseFailureAbends() {
            Harness harness = harness(List.of());
            doReturn("35").when(harness.types).close();

            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(harness::run)
                    .satisfies(abend -> assertAbend(abend, harness,
                            TransactionReportJob.ERROR_CLOSING_TRANSACTION_TYPE_FILE, "35"));
        }

        @Test
        @DisplayName("a TRANCATG close that fails abends with its own message")
        void tranCatgCloseFailureAbends() {
            Harness harness = harness(List.of());
            doReturn("35").when(harness.categories).close();

            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(harness::run)
                    .satisfies(abend -> assertAbend(abend, harness,
                            TransactionReportJob.ERROR_CLOSING_TRANSACTION_CATG_FILE, "35"));
        }

        @Test
        @DisplayName("a DATEPARM close that fails abends with its own message")
        void dateParmCloseFailureAbends() {
            Harness harness = harness(List.of());
            doReturn("35").when(harness.dateParms).close();

            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(harness::run)
                    .satisfies(abend -> assertAbend(abend, harness,
                            TransactionReportJob.ERROR_CLOSING_DATE_PARM_FILE, "35"));
        }

        @Test
        @DisplayName("all six closes run on the happy path, in the same order as the opens")
        void allSixClosesRun() {
            Harness harness = harness(List.of());

            harness.run();

            verify(harness.tranFile).closeInput();
            verify(harness.xrefCursor).closeBrowse();
            verify(harness.types).close();
            verify(harness.categories).close();
            verify(harness.dateParms).close();
            assertThat(harness.sysout.lines).last().isEqualTo(TransactionReportJob.END_OF_EXECUTION);
        }
    }

    @Nested
    @DisplayName("Handle release - an incomplete run reclaims what it acquired, silently")
    class HandleRelease {
        @Test
        @DisplayName("an abend after the opens still releases the handles the run acquired")
        void anAbendReleasesEveryAcquiredHandle() {
            Harness harness = harness(List.of());
            doReturn(DateParmReader.ReadResult.other("35")).when(harness.dateParms).read();

            assertThatExceptionOfType(AbendException.class).isThrownBy(harness::run);

            verify(harness.tranFile).closeInput();
            verify(harness.xrefCursor).closeBrowse();
            assertThat(harness.sysout.lines)
                    .as("the release is silent: it emits no CLOSE message the program never writes, and "
                            + "no end-of-execution line")
                    .doesNotContain(TransactionReportJob.ERROR_CLOSING_POSTED_TRANSACTION_FILE,
                            TransactionReportJob.ERROR_CLOSING_CROSS_REF_FILE,
                            TransactionReportJob.END_OF_EXECUTION);
        }

        @Test
        @DisplayName("the happy path closes each handle exactly once - the release adds no second close")
        void theHappyPathClosesExactlyOnce() {
            Harness harness = harness(List.of(record("TRAN000000000001", CARD_ONE, "1.00",
                    IN_RANGE_DATE)));

            harness.run();

            verify(harness.tranFile).closeInput();
            verify(harness.xrefCursor).closeBrowse();
        }

        @Test
        @DisplayName("a handle that fails to release does not replace the abend the caller needs")
        void aFailingReleaseDoesNotMaskTheAbend() {
            Harness harness = harness(List.of());
            doReturn(DateParmReader.ReadResult.other("35")).when(harness.dateParms).read();
            doThrow(new IllegalStateException("the gateway dropped the pass"))
                    .when(harness.tranFile).closeInput();

            assertThatExceptionOfType(AbendException.class).isThrownBy(harness::run);

            verify(harness.xrefCursor).closeBrowse();
        }

        @Test
        @DisplayName("a release failure on the browse is swallowed too, and the abend still surfaces")
        void aFailingBrowseReleaseIsSwallowed() {
            Harness harness = harness(List.of());
            doReturn(DateParmReader.ReadResult.other("35")).when(harness.dateParms).read();
            doThrow(new IllegalStateException("the browse could not be released"))
                    .when(harness.xrefCursor).closeBrowse();

            assertThatExceptionOfType(AbendException.class).isThrownBy(harness::run);

            verify(harness.tranFile).closeInput();
        }

        @Test
        @DisplayName("a handle never acquired is not closed - only what the run took is released")
        void onlyWhatWasAcquiredIsReleased() {
            Harness harness = harness(List.of());
            when(harness.tranFile.openStatus()).thenReturn("35");

            assertThatExceptionOfType(AbendException.class).isThrownBy(harness::run);

            verify(harness.xrefs, never()).openBrowse();
            verify(harness.xrefCursor, never()).closeBrowse();
        }
    }

    @Nested
    @DisplayName("1111-WRITE-REPORT-REC - a refused write abends, with no end-of-file arm")
    class WriteLadder {
        @ParameterizedTest
        @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7})
        @DisplayName("a refusal at any point in the sequence abends with 'ERROR WRITING REPTFILE'")
        void aRefusedWriteAbends(int writeNumber) {
            Harness harness = harness(List.of(record("TRAN000000000001", CARD_ONE, "1.00",
                    IN_RANGE_DATE)), new CollectingSink(FileStatus.Outcome.OK, FileStatus.Outcome.OK,
                    writeNumber));

            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(harness::run)
                    .satisfies(abend -> assertAbend(abend, harness,
                            TransactionReportJob.ERROR_WRITING_REPTFILE,
                            TransactionReportJob.PERMANENT_ERROR_STATUS));
            assertThat(harness.sink.records)
                    .as("the refused record was still handed to the sink before it refused")
                    .hasSize(writeNumber);
        }

        @Test
        @DisplayName("the permanent-error status renders as FILE STATUS IS: NNNN9000")
        void thePermanentErrorStatusRendersAsNnnn9000() {
            assertThat(FileStatus.toDisplayLine(TransactionReportJob.PERMANENT_ERROR_STATUS))
                    .isEqualTo(FileStatus.DISPLAY_PREFIX + "9000");
            assertThat(FileStatus.toDisplayLine(FileStatus.NOT_FOUND))
                    .isEqualTo(FileStatus.DISPLAY_PREFIX + "0023");
        }
    }

    @Nested
    @DisplayName("ExecutionSummary - it cannot describe a run that could not have happened")
    class Summary {
        @Test
        @DisplayName("a well-formed summary is accepted and reports its derived values")
        void aWellFormedSummaryIsAccepted() {
            ExecutionSummary summary = new ExecutionSummary(0, 2, 2, 9, 8L, money("200.00"));

            assertThat(summary.structuralLinesWritten()).isEqualTo(7);
            assertThat(summary.producedReport()).isTrue();
            assertThat(new ExecutionSummary(0, 0, 0, 0, 0L, money("0.00")).producedReport()).isFalse();
        }

        @Test
        @DisplayName("a null grand total, a negative count or a wrong scale is refused")
        void anImpossibleSummaryIsRefused() {
            assertThatNullPointerException().isThrownBy(() ->
                    new ExecutionSummary(0, 0, 0, 0, 0L, null));
            assertThatIllegalArgumentException().isThrownBy(() ->
                    new ExecutionSummary(0, -1, 0, 0, 0L, money("0.00")));
            assertThatIllegalArgumentException().isThrownBy(() ->
                    new ExecutionSummary(0, 0, -1, 0, 0L, money("0.00")));
            assertThatIllegalArgumentException().isThrownBy(() ->
                    new ExecutionSummary(0, 0, 0, -1, 0L, money("0.00")));
            assertThatIllegalArgumentException().isThrownBy(() ->
                    new ExecutionSummary(0, 0, 0, 0, -1L, money("0.00")));
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new ExecutionSummary(0, 0, 3, 2, 0L, money("0.00")))
                    .withMessageContaining("every detail line is a report line");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new ExecutionSummary(0, 0, 0, 0, 0L, new BigDecimal("0.000")))
                    .withMessageContaining("PIC S9(09)V99");
        }
    }

    @Nested
    @DisplayName("State - per-execution, never static (practice B9, gate G53)")
    class State {
        @Test
        @DisplayName("no field of the job or of its run state is a mutable static")
        void nothingMutableIsStatic() {
            assertThat(mutableStaticsOf(TransactionReportJob.class)).isEmpty();
            for (Class<?> nested : TransactionReportJob.class.getDeclaredClasses()) {
                assertThat(mutableStaticsOf(nested))
                        .as("mutable statics on %s", nested.getSimpleName())
                        .isEmpty();
            }
        }

        @Test
        @DisplayName("two runs of the same job instance are independent")
        void twoRunsAreIndependent() {
            TranRecord only = record("TRAN000000000001", CARD_ONE, "100.00", IN_RANGE_DATE);
            Harness first = harness(List.of(only));
            TransactionReportJob job = first.job();

            ExecutionSummary one = job.execute(first.sysout, first.sink);
            CollectingSink secondSink = new CollectingSink();
            Harness second = harness(List.of(only), secondSink);
            ExecutionSummary two = second.job().execute(second.sysout, secondSink);

            assertThat(one.reportLinesWritten()).isEqualTo(two.reportLinesWritten());
            assertThat(one.lineCounter()).isEqualTo(two.lineCounter());
            assertThat(one.grandTotal()).isEqualByComparingTo(two.grandTotal());
            assertThat(first.sink.lines()).containsExactlyElementsOf(secondSink.lines());
        }

        @Test
        @DisplayName("the grand total is a BigDecimal at scale 2 - never a float or a double")
        void theTotalsAreBigDecimalAtScaleTwo() {
            Harness harness = harness(List.of(record("TRAN000000000001", CARD_ONE, "0.01",
                    IN_RANGE_DATE)));

            ExecutionSummary summary = harness.run();

            assertThat(summary.grandTotal()).isInstanceOf(BigDecimal.class);
            assertThat(summary.grandTotal().scale()).isEqualTo(TransactionReportJob.TOTAL_SCALE);
            assertThat(summary.grandTotal())
                    .as("0.01 twice, exactly - which binary floating point could not promise")
                    .isEqualByComparingTo(money("0.02"));
        }
    }

    private static void assertAbend(AbendException abend, Harness harness, String message,
            String status) {
        assertThat(abend.getProgram()).isEqualTo(TransactionReportJob.PROGRAM_NAME);
        assertThat(abend.getReturnCode()).isEqualTo(AbendException.RETURN_CODE_IO_ERROR);
        assertThat(abend.getAbendCode()).hasValue(AbendException.STANDARD_ABEND_CODE);
        assertThat(abend.getTiming()).hasValue(AbendException.STANDARD_TIMING);
        assertThat(abend.getReason()).isPresent();
        assertThat(harness.sysout.lines)
                .containsSubsequence(message, FileStatus.toDisplayLine(status),
                        AbendException.ABEND_DISPLAY_TEXT);
    }

    private static List<String> mutableStaticsOf(Class<?> type) {
        List<String> mutable = new ArrayList<>();
        for (Field field : type.getDeclaredFields()) {
            if (field.isSynthetic()) {
                continue;
            }
            if (Modifier.isStatic(field.getModifiers()) && !Modifier.isFinal(field.getModifiers())) {
                mutable.add(field.getName());
            }
        }
        return mutable;
    }

    @Nested
    @DisplayName("The abnormal disposition leaves no report where the JCL says DELETE")
    class TheAbnormalDisposition {
        @Test
        @DisplayName("a run that reaches GOBACK discards nothing: CATLG, not DELETE")
        void aCompletedRunDiscardsNothing() {
            Harness harness = harness(List.of(record("TRAN000000000001", CARD_ONE, "1.00",
                    IN_RANGE_DATE)));

            assertThat(harness.run().returnCode()).isEqualTo(AbendException.RETURN_CODE_OK);

            assertThat(harness.sink.discards)
                    .as("CATLG is the normal disposition: the report stays catalogued for printing")
                    .isZero();
        }

        @Test
        @DisplayName("an abended run discards the report it had already written")
        void anAbendedRunDiscardsTheReport() {
            Harness harness = harness(List.of(record("TRAN000000000001", CARD_ONE, "1.00",
                    IN_RANGE_DATE)), new CollectingSink(FileStatus.Outcome.OK, FileStatus.Outcome.OK,
                    4));

            assertThatExceptionOfType(AbendException.class).isThrownBy(harness::run);

            assertThat(harness.sink.discards).isOne();
            assertThat(harness.sink.discardCount)
                    .as("the count is the handle's own, which is what lets a sink establish that the "
                            + "generation it deletes is the one this run allocated")
                    .isEqualTo(harness.sink.records.size());
        }

        @Test
        @DisplayName("a disposition that reports it could not discard does not replace the abend reason")
        void aRefusedDispositionDoesNotReplaceTheAbendReason() {
            Harness harness = harness(List.of(record("TRAN000000000001", CARD_ONE, "1.00",
                    IN_RANGE_DATE)), new CollectingSink(FileStatus.Outcome.OK, FileStatus.Outcome.OK,
                    4));
            harness.sink.discardsRefused = true;

            assertThatExceptionOfType(AbendException.class).isThrownBy(harness::run);

            assertThat(harness.sink.discards)
                    .as("the disposition was still attempted - a refusal is not a skip")
                    .isOne();
        }

        @Test
        @DisplayName("the report is closed before its disposition is applied, as z/OS does it")
        void theDispositionFollowsTheClose() {
            Harness harness = harness(List.of(record("TRAN000000000001", CARD_ONE, "1.00",
                    IN_RANGE_DATE)), new CollectingSink(FileStatus.Outcome.OK, FileStatus.Outcome.OK,
                    4));

            assertThatExceptionOfType(AbendException.class).isThrownBy(harness::run);

            assertThat(harness.sink.closedBeforeDiscard)
                    .as("z/OS closes the dataset and then applies its disposition")
                    .isTrue();
        }

        @Test
        @DisplayName("a run that abends before its first line discards nothing")
        void anAbendBeforeTheFirstLineDiscardsNothing() {
            Harness harness = harness(List.of(), new CollectingSink(FileStatus.Outcome.OTHER,
                    FileStatus.Outcome.OK, 0));

            assertThatExceptionOfType(AbendException.class).isThrownBy(harness::run);

            assertThat(harness.sink.records).isEmpty();
            assertThat(harness.sink.discards).isZero();
        }

        @Test
        @DisplayName("a stop request is an abnormal end too, so it discards what it had written")
        void aStoppedRunDiscardsTheReport() {
            Harness harness = harness(List.of(record("TRAN000000000001", CARD_ONE, "1.00",
                    IN_RANGE_DATE)));
            StepExecution execution = stepExecution(TransactionReportJob.STEP_NAME);
            execution.setTerminateOnly();

            assertThatExceptionOfType(StopRequestedException.class).isThrownBy(() ->
                    harness.job().execute(harness.sysout, harness.sink, StopSignal.of(execution)));

            assertThat(harness.sink.discards)
                    .as("whatever it had written is discarded, if it wrote anything at all")
                    .isEqualTo(harness.sink.records.isEmpty() ? 0 : 1);
        }
    }

    private static CardXrefRepository.ReadResult xrefFound(String ddName, CardXrefRecord record) {
        return CardXrefRepository.ReadResult.found(ddName, record, xrefImageOf(record));
    }

    private static CardXrefRepository.ReadResult xrefDuplicate(String ddName, CardXrefRecord first,
            int cicsResp) {
        return CardXrefRepository.ReadResult.duplicate(ddName, first, xrefImageOf(first), cicsResp);
    }

    private static String xrefImageOf(CardXrefRecord record) {
        return new String(record.encode(StandardCharsets.US_ASCII), StandardCharsets.US_ASCII);
    }
}
