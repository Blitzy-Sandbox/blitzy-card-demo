package com.vsergeychik.carddemo.transaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vsergeychik.carddemo.card.CardXrefRepository;
import com.vsergeychik.carddemo.card.CardXrefRepository.BrowseCursor;
import com.vsergeychik.carddemo.card.model.CardXrefRecord;
import com.vsergeychik.carddemo.common.AbendException;
import com.vsergeychik.carddemo.common.FileStatus;
import com.vsergeychik.carddemo.common.RecordImageForm;
import com.vsergeychik.carddemo.config.BatchConfig;
import com.vsergeychik.carddemo.config.BatchConfig.JobContract;
import com.vsergeychik.carddemo.config.BatchConfig.JobContracts;
import com.vsergeychik.carddemo.config.BatchConfig.JobParameterContract;
import com.vsergeychik.carddemo.config.BatchConfig.StepContract;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBinding;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBindings;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Tests for {@link TransactionReportJob}, the Java translation of {@code app/cbl/CBTRN03C.cbl}.
 *
 * <h2>What this suite is really guarding</h2>
 *
 * <p>Three things, and all three are things a well-meaning implementer would get wrong.
 *
 * <p><strong>Defect 1.</strong> {@code NEXT SENTENCE} at {@code :177} ends the whole read loop rather
 * than skipping one record, so a single out-of-range record truncates the report and suppresses the
 * page and grand totals entirely. Asserted directly, and asserted as an absence: the totals must not
 * appear.
 *
 * <p><strong>Defect 2.</strong> The end-of-file arm at {@code :200} adds the last record's amount a
 * second time. Asserted numerically against the arithmetic, not against a hard-coded expectation, so
 * the assertion says <em>why</em> the number is what it is.
 *
 * <p><strong>Only page totals roll into the grand total.</strong> {@code :297} is the only place
 * {@code WS-GRAND-TOTAL} grows, and {@code 1120-WRITE-ACCOUNT-TOTALS} has no counterpart. Asserted by
 * running a report whose account totals and page totals differ, and pinning the grand total to the
 * page totals.
 *
 * <h2>How it runs</h2>
 *
 * <p>Plain JUnit 5 with Mockito and AssertJ. No application context, no {@code JobLauncher}, no
 * database, no filesystem, no clock, no locale and no platform default charset: the program body is
 * {@link TransactionReportJob#execute(SysoutSink, TranReportWriter.RecordSink)}, an ordinary method,
 * and the report is collected in memory through the writer's own sink seam. The writer itself is
 * <em>real</em> rather than mocked, so every assertion about a 133-byte record is an assertion about
 * bytes the production code path produced.
 *
 * <p>{@code review_rules} returns exactly one line - "No user rules provided." - so no user rule
 * governs this file. The gates it enforces directly are G1 and G3 (it compiles and the beans wire),
 * G20 and G21 (every report line is 133 bytes with its {@code FILLER} intact), G22 (no binary
 * floating point), G28 (the arithmetic sites), G30 (the {@code EVALUATE} arms in source order with
 * {@code WHEN OTHER} last), G35 (the abend's return code, abend code and timing), G44 and G46 (no DDL
 * and no dataset name in Java), G47 (every file-status outcome per call site), G49 and G50 (both
 * sides of every branch, including both {@code 88}-levels), G51 (the body runs with no launcher), G52
 * (no wildcard import) and G53 (no mutable static state).
 */
@DisplayName("TransactionReportJob - CBTRN03C: prints the transaction detail report, defects included")
class TransactionReportJobTest {

    /** The fixture code page, named explicitly rather than taken from the platform. */
    private static final Charset ASCII = StandardCharsets.US_ASCII;

    /** A dataset name for the bindings. Never a real one, and never {@code AWS.M2.CARDDEMO.*}. */
    private static final String TEST_TRANFILE = "TEST.TRANSACT.DALY";

    /** A dataset name for the report binding. */
    private static final String TEST_TRANREPT = "TEST.TRANREPT";

    /** The reporting range every happy-path run uses. */
    private static final String START_DATE = "2022-01-01";

    /** The reporting range every happy-path run uses. */
    private static final String END_DATE = "2022-07-06";

    /** A processing date inside {@link #START_DATE} to {@link #END_DATE}. */
    private static final String IN_RANGE_DATE = "2022-03-15";

    /** A processing date after {@link #END_DATE}, which is what fires defect 1. */
    private static final String OUT_OF_RANGE_DATE = "2022-09-01";

    /** The first card number, 16 characters exactly. */
    private static final String CARD_ONE = "4111111111111111";

    /** A second card number, so the account break can be driven. */
    private static final String CARD_TWO = "4222222222222222";

    /** The transaction type code every fixture record carries. */
    private static final String TYPE_CODE = "01";

    /** The transaction category code every fixture record carries. */
    private static final int CATEGORY_CODE = 1;

    /** {@code TRAN-TYPE-DESC} is {@code PIC X(50)}; 51 characters proves the 15-byte truncation. */
    private static final String TYPE_DESCRIPTION = "Purchase";

    /** {@code TRAN-CAT-TYPE-DESC} is {@code PIC X(50)}. */
    private static final String CATEGORY_DESCRIPTION = "Regular Sales Draft";

    /** The account identifier the cross reference resolves to. */
    private static final long ACCOUNT_ID = 12345678901L;

    /** The customer identifier on the cross-reference record; unused by the report. */
    private static final int CUSTOMER_ID = 123456789;

    // =============================================================================================
    // Test doubles.
    // =============================================================================================

    /**
     * An {@link ObjectProvider} over one bean, or over none.
     *
     * <p>Needed because the constructor takes a provider rather than a sink: a deployment may publish
     * a {@code SYSOUT} destination and may equally publish none.
     *
     * @param <T> the bean type
     */
    private static final class SuppliedProvider<T> implements ObjectProvider<T> {

        /** The bean, or {@code null} when the container publishes none. */
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

    /** Collects every {@code DISPLAY} line in order. */
    private static final class CapturingSysout implements SysoutSink {

        /** The lines, in emission order. */
        private final List<String> lines = new ArrayList<>();

        @Override
        public void display(String line) {
            lines.add(line);
        }
    }

    /**
     * Collects every report record as bytes, in write order, and can be told to refuse.
     *
     * <p>The bytes are kept rather than decoded, so the width assertions are byte assertions.
     */
    private static final class CollectingSink implements TranReportWriter.RecordSink {

        /** The records, in write order. */
        private final List<byte[]> records = new ArrayList<>();

        /** What {@link #open()} reports. */
        private final FileStatus.Outcome openOutcome;

        /** What {@link #close()} reports. */
        private final FileStatus.Outcome closeOutcome;

        /** The one-based index of the write to refuse, or zero to accept every write. */
        private final int refuseWriteNumber;

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
            return closeOutcome;
        }

        /**
         * The collected records decoded as text, for readable assertions.
         *
         * @return one string per record, each exactly 133 characters
         */
        private List<String> lines() {
            return records.stream().map(bytes -> new String(bytes, ASCII)).toList();
        }
    }

    // =============================================================================================
    // Fixtures.
    // =============================================================================================

    /**
     * A transaction record.
     *
     * @param tranId    {@code TRAN-ID}
     * @param cardNum   {@code TRAN-CARD-NUM}
     * @param amount    {@code TRAN-AMT}
     * @param procDate  the first ten characters of {@code TRAN-PROC-TS}
     * @return the populated 350-byte record
     */
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

    /** @return the {@code TRANREPT} and {@code TRANFILE} catalogue the writer and the job resolve */
    private static DatasetBindings bindings() {
        return bindings(TranRecord.RECORD_LENGTH);
    }

    /**
     * @param tranFileRecordLength the width to declare for {@code TRANFILE}
     * @return the catalogue
     */
    private static DatasetBindings bindings(int tranFileRecordLength) {
        DatasetBindings catalogue = new DatasetBindings();
        catalogue.put(TranReportWriter.DD_NAME, new DatasetBinding(TEST_TRANREPT, "sequential", true,
                "FB", 0, TranReportWriter.RECORD_LENGTH, "CVTRA07Y", null, null, null, null));
        catalogue.put(TransactionReportJob.TRANFILE_DD_NAME, new DatasetBinding(TEST_TRANFILE,
                "sequential", true, "FB", 0, tranFileRecordLength, "CVTRA05Y", null, null, null, null));
        return catalogue;
    }

    /**
     * @param program         the program to declare on the job and on every step
     * @param stepNames       the step names, in order
     * @param gated           whether every step is gated
     * @param parameters      the declared parameters
     * @param dateRangeSource the declared date-range source
     * @return the catalogue
     */
    private static JobContracts contracts(String program, List<String> stepNames, boolean gated,
            List<JobParameterContract> parameters, String dateRangeSource) {
        JobContracts catalogue = new JobContracts();
        List<StepContract> steps = stepNames.stream()
                .map(name -> new StepContract(name, program, gated))
                .toList();
        catalogue.put(TransactionReportJob.JOB_KEY,
                new JobContract(program, parameters, steps, dateRangeSource, Map.of()));
        return catalogue;
    }

    /** @return the step names {@code application.yml} declares, in order */
    private static List<String> declaredSteps() {
        return List.of(TransactionReportJob.BACKUP_STEP_NAME, TransactionReportJob.SORT_STEP_NAME,
                TransactionReportJob.STEP_NAME);
    }

    /** @return the contract catalogue exactly as {@code application.yml} declares it */
    private static JobContracts validContracts() {
        return contracts(TransactionReportJob.PROGRAM_NAME, declaredSteps(), false, List.of(),
                TransactionReportJob.DATE_RANGE_SOURCE);
    }

    /**
     * @param contracts the job catalogue
     * @param catalogue the dataset catalogue
     * @return a {@link BatchConfig} over both
     */
    private static BatchConfig batchConfig(JobContracts contracts, DatasetBindings catalogue) {
        return new BatchConfig(new SuppliedProvider<>(mock(JobRepository.class)),
                new SuppliedProvider<>(mock(PlatformTransactionManager.class)), contracts, catalogue);
    }

    /** @return a {@link BatchConfig} over the catalogues configuration actually declares */
    private static BatchConfig validBatchConfig() {
        return batchConfig(validContracts(), bindings());
    }

    /** @return a correctly configured, real report writer over {@link #ASCII} */
    private static TranReportWriter writer() {
        return writer(bindings());
    }

    /**
     * @param catalogue the dataset catalogue to resolve {@code TRANREPT} from
     * @return the writer
     */
    private static TranReportWriter writer(DatasetBindings catalogue) {
        return new TranReportWriter(new JdbcTemplate(), ASCII, catalogue, RecordImageForm.CHARACTER);
    }

    // =============================================================================================
    // The harness: five mocked collaborators, a real writer, and one call to the program body.
    // =============================================================================================

    /**
     * Everything one run of {@code CBTRN03C} needs, stubbed to succeed, with each stub overridable
     * before {@link #run()} is called.
     */
    private static final class Harness {

        /** {@value TransactionReportJob#TRANFILE_DD_NAME}. */
        private final TransactionRepository transactions = mock(TransactionRepository.class);

        /** The open browse of the sorted daily file. */
        private final TransactionRepository.InputFile tranFile =
                mock(TransactionRepository.InputFile.class);

        /** {@value TransactionReportJob#CARDXREF_DD_NAME}. */
        private final CardXrefRepository xrefs = mock(CardXrefRepository.class);

        /** The open handle standing in for {@code OPEN INPUT XREF-FILE}. */
        private final BrowseCursor xrefCursor = mock(BrowseCursor.class);

        /** {@value TransactionReportJob#TRANTYPE_DD_NAME}. */
        private final TranTypeRepository types = mock(TranTypeRepository.class);

        /** {@value TransactionReportJob#TRANCATG_DD_NAME}. */
        private final TranCategoryRepository categories = mock(TranCategoryRepository.class);

        /** {@value TransactionReportJob#DATEPARM_DD_NAME}. */
        private final DateParmReader dateParms = mock(DateParmReader.class);

        /** Where the report goes. */
        private final CollectingSink sink;

        /** Where the {@code DISPLAY} lines go. */
        private final CapturingSysout sysout = new CapturingSysout();

        /** The dataset catalogue, so a width can be varied. */
        private DatasetBindings catalogue = bindings();

        /**
         * Stubs every collaborator to succeed and to yield {@code records} then end of file.
         *
         * @param records the records the browse returns, in order
         * @param sink    where the report goes
         */
        private Harness(List<TranRecord> records, CollectingSink sink) {
            this.sink = sink;

            when(transactions.openInput(any(DatasetBinding.class))).thenReturn(tranFile);
            when(tranFile.openStatus()).thenReturn(FileStatus.OK);
            when(tranFile.closeInput()).thenReturn(FileStatus.OK);
            yielding(records);

            when(xrefs.openBrowse()).thenReturn(xrefCursor);
            when(xrefCursor.openStatus()).thenReturn(FileStatus.OK);
            when(xrefCursor.closeBrowse()).thenReturn(FileStatus.OK);
            when(xrefs.readByCardNumber(anyString())).thenAnswer(invocation ->
                    CardXrefRepository.ReadResult.found(CardXrefRepository.BASE_DD_NAME,
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

        /**
         * Re-stubs the browse to yield {@code records} and then end of file.
         *
         * @param records the records to yield
         * @return this harness
         */
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

        /** @return the job under test, wired to this harness */
        private TransactionReportJob job() {
            return job(validContracts());
        }

        /**
         * @param jobContracts the contract catalogue to wire
         * @return the job under test
         */
        private TransactionReportJob job(JobContracts jobContracts) {
            return new TransactionReportJob(batchConfig(jobContracts, catalogue), transactions, xrefs,
                    types, categories, dateParms, writer(catalogue), ASCII,
                    new SuppliedProvider<>(null));
        }

        /** @return the summary of one run */
        private ExecutionSummary run() {
            return job().execute(sysout, sink);
        }
    }

    /**
     * @param records the records the browse yields
     * @return a harness whose every collaborator succeeds
     */
    private static Harness harness(List<TranRecord> records) {
        return new Harness(records, new CollectingSink());
    }

    /**
     * @param records the records the browse yields
     * @param sink    where the report goes
     * @return a harness over that sink
     */
    private static Harness harness(List<TranRecord> records, CollectingSink sink) {
        return new Harness(records, sink);
    }

    /**
     * The 6-byte {@code FD-TRAN-CAT-KEY} image: the 2-character type code then the 4-digit category
     * code, zero-filled on the left.
     *
     * <p>Composed by hand rather than with a formatter, so no locale can change the digits.
     *
     * @param typeCode     the type code
     * @param categoryCode the category code
     * @return the 6-character key image
     */
    private static String keyImageOf(String typeCode, int categoryCode) {
        String digits = Integer.toString(categoryCode);
        return typeCode + "0".repeat(TranCategoryRecord.TRAN_CAT_CD_LENGTH - digits.length()) + digits;
    }

    /**
     * A layout set populated exactly as a run populates it, so an expected line can be rendered by the
     * same code the job renders it with rather than transcribed by hand.
     *
     * @return the layouts, with the reporting range already moved in
     */
    private static TranReportLayouts expectedLayouts() {
        TranReportLayouts layouts = new TranReportLayouts(ASCII);
        layouts.moveReptStartDate(START_DATE);
        layouts.moveReptEndDate(END_DATE);
        return layouts;
    }

    /**
     * Pads a natural-width layout image to the 133-byte record the writer emits.
     *
     * @param layoutImage the rendered image
     * @return the image padded on the right with spaces
     */
    private static String asRecord(String layoutImage) {
        return layoutImage
                + " ".repeat(TranReportWriter.RECORD_LENGTH - layoutImage.length());
    }

    /**
     * The four header lines a page carries, as 133-byte records.
     *
     * @param layouts the populated layout set
     * @return the four records, in write order
     */
    private static List<String> headerRecords(TranReportLayouts layouts) {
        return List.of(asRecord(layouts.renderReportNameHeader()),
                TranReportWriter.WS_BLANK_LINE_IMAGE,
                asRecord(layouts.renderTransactionHeader1()),
                asRecord(layouts.renderTransactionHeader2()));
    }

    /**
     * The detail line for one record, as a 133-byte record.
     *
     * @param layouts the populated layout set, which this mutates exactly as the job does
     * @param tran    the record to render
     * @return the detail record
     */
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

    /**
     * The page-total pair, as 133-byte records.
     *
     * @param layouts the populated layout set
     * @param total   the page total to render
     * @return the total line and the rule line that follows it
     */
    private static List<String> pageTotalRecords(TranReportLayouts layouts, BigDecimal total) {
        layouts.moveReptPageTotal(total);
        return List.of(asRecord(layouts.renderReportPageTotals()),
                asRecord(layouts.renderTransactionHeader2()));
    }

    /**
     * The account-total pair, as 133-byte records.
     *
     * @param layouts the populated layout set
     * @param total   the account total to render
     * @return the total line and the rule line that follows it
     */
    private static List<String> accountTotalRecords(TranReportLayouts layouts, BigDecimal total) {
        layouts.moveReptAccountTotal(total);
        return List.of(asRecord(layouts.renderReportAccountTotals()),
                asRecord(layouts.renderTransactionHeader2()));
    }

    /**
     * The grand-total line, as a 133-byte record.
     *
     * @param layouts the populated layout set
     * @param total   the grand total to render
     * @return the grand-total record
     */
    private static String grandTotalRecord(TranReportLayouts layouts, BigDecimal total) {
        layouts.moveReptGrandTotal(total);
        return asRecord(layouts.renderReportGrandTotals());
    }

    /**
     * @param amount a decimal literal
     * @return that amount at the monetary scale
     */
    private static BigDecimal money(String amount) {
        return new BigDecimal(amount);
    }


    // =============================================================================================
    // Wiring and identity.
    // =============================================================================================

    @Nested
    @DisplayName("Wiring - one job, one tasklet step, no parameters (gate G3)")
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
        }

        @Test
        @DisplayName("the tasklet runs the program once and finishes")
        void theTaskletRunsOnceAndFinishes() throws Exception {
            Harness harness = harness(List.of());
            // The provider publishes a capturing sink, so the tasklet's own resolveSysoutSink path is
            // the one under test rather than the two-argument execute overload.
            TransactionReportJob job = new TransactionReportJob(validBatchConfig(),
                    harness.transactions, harness.xrefs, harness.types, harness.categories,
                    harness.dateParms, writer(), ASCII, new SuppliedProvider<>(harness.sysout));

            assertThat(job.transactionReportTasklet().execute(null, null))
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
                    harness.dateParms, writer(), ASCII, new SuppliedProvider<>(null)));
            assertThatNullPointerException().isThrownBy(() -> new TransactionReportJob(
                    validBatchConfig(), null, harness.xrefs, harness.types, harness.categories,
                    harness.dateParms, writer(), ASCII, new SuppliedProvider<>(null)));
            assertThatNullPointerException().isThrownBy(() -> new TransactionReportJob(
                    validBatchConfig(), harness.transactions, null, harness.types, harness.categories,
                    harness.dateParms, writer(), ASCII, new SuppliedProvider<>(null)));
            assertThatNullPointerException().isThrownBy(() -> new TransactionReportJob(
                    validBatchConfig(), harness.transactions, harness.xrefs, null, harness.categories,
                    harness.dateParms, writer(), ASCII, new SuppliedProvider<>(null)));
            assertThatNullPointerException().isThrownBy(() -> new TransactionReportJob(
                    validBatchConfig(), harness.transactions, harness.xrefs, harness.types, null,
                    harness.dateParms, writer(), ASCII, new SuppliedProvider<>(null)));
            assertThatNullPointerException().isThrownBy(() -> new TransactionReportJob(
                    validBatchConfig(), harness.transactions, harness.xrefs, harness.types,
                    harness.categories, null, writer(), ASCII, new SuppliedProvider<>(null)));
            assertThatNullPointerException().isThrownBy(() -> new TransactionReportJob(
                    validBatchConfig(), harness.transactions, harness.xrefs, harness.types,
                    harness.categories, harness.dateParms, null, ASCII,
                    new SuppliedProvider<>(null)));
            assertThatNullPointerException().isThrownBy(() -> new TransactionReportJob(
                    validBatchConfig(), harness.transactions, harness.xrefs, harness.types,
                    harness.categories, harness.dateParms, writer(), null,
                    new SuppliedProvider<>(null)));
            assertThatNullPointerException().isThrownBy(() -> new TransactionReportJob(
                    validBatchConfig(), harness.transactions, harness.xrefs, harness.types,
                    harness.categories, harness.dateParms, writer(), ASCII, null));
        }

        @Test
        @DisplayName("a null SYSOUT sink is refused by the program body")
        void aNullSysoutSinkIsRefused() {
            TransactionReportJob job = harness(List.of()).job();

            assertThatNullPointerException().isThrownBy(() -> job.execute(null));
        }
    }

    // =============================================================================================
    // The startup guards. Each rejects a configuration that would report from the wrong place.
    // =============================================================================================

    @Nested
    @DisplayName("Startup guards - the contract must still say what the JCL says")
    class StartupGuards {

        @Test
        @DisplayName("a contract naming another program is refused")
        void anotherProgramIsRefused() {
            JobContracts wrong = contracts("CBTRN02C", declaredSteps(), false, List.of(),
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
                    false, List.of(new JobParameterContract("parmDate", "string", "2022071800")),
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
                    false, List.of(), source);

            assertThatIllegalStateException()
                    .isThrownBy(() -> harness(List.of()).job(wrongSource))
                    .withMessageContaining("date-range-source");
        }

        @Test
        @DisplayName("an absent date-range source is refused")
        void anAbsentDateRangeSourceIsRefused() {
            JobContracts noSource = contracts(TransactionReportJob.PROGRAM_NAME, declaredSteps(),
                    false, List.of(), null);

            assertThatIllegalStateException()
                    .isThrownBy(() -> harness(List.of()).job(noSource))
                    .withMessageContaining("not declared");
        }

        @Test
        @DisplayName("a lost or reordered step sequence is refused")
        void aWrongStepSequenceIsRefused() {
            JobContracts oneStep = contracts(TransactionReportJob.PROGRAM_NAME,
                    List.of(TransactionReportJob.STEP_NAME), false, List.of(),
                    TransactionReportJob.DATE_RANGE_SOURCE);
            JobContracts reordered = contracts(TransactionReportJob.PROGRAM_NAME,
                    List.of(TransactionReportJob.STEP_NAME, TransactionReportJob.SORT_STEP_NAME,
                            TransactionReportJob.BACKUP_STEP_NAME),
                    false, List.of(), TransactionReportJob.DATE_RANGE_SOURCE);

            assertThatIllegalStateException()
                    .isThrownBy(() -> harness(List.of()).job(oneStep))
                    .withMessageContaining(".steps");
            assertThatIllegalStateException()
                    .isThrownBy(() -> harness(List.of()).job(reordered))
                    .withMessageContaining("in that order");
        }

        @Test
        @DisplayName("a gated step is refused - TRANREPT.jcl carries no COND")
        void aGatedStepIsRefused() {
            JobContracts gated = contracts(TransactionReportJob.PROGRAM_NAME, declaredSteps(), true,
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
                            harness.dateParms, narrow, ASCII, new SuppliedProvider<>(null)))
                    .withMessageContaining("133");
        }

        @Test
        @DisplayName("the valid contract is accepted")
        void theValidContractIsAccepted() {
            assertThat(harness(List.of()).job()).isNotNull();
        }
    }

    // =============================================================================================
    // The two decisions, as pure functions.
    // =============================================================================================

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
            // '15/03/2022' style values order by their leading characters, which is why the program's
            // yyyy-mm-dd shape happens to order correctly and another shape would not. Reproduced, not
            // repaired: parsing would reject values COBOL happily compares.
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


    // =============================================================================================
    // The golden line sequence.
    // =============================================================================================

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

            // The page total at end of file is 100.00 + 50.00 + 50.00: the last amount twice, which is
            // defect 2. The grand total is the page total, because :297 is the only roll-up.
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
            // doReturn, not when(...): re-stubbing with when() would first INVOKE the harness's
            // existing answer with the matcher's default argument, and a zero-length key image is
            // exactly what TranTypeRepository.ReadResult rejects.
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

    // =============================================================================================
    // The two defects.
    // =============================================================================================

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

    // =============================================================================================
    // Pagination and the account break.
    // =============================================================================================

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
            // The page total at end of file is 100 + 30 + 20 + 20: defect 2 again.
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


    // =============================================================================================
    // 0550-DATEPARM-READ - all three EVALUATE arms, WHEN OTHER last (gate G30).
    // =============================================================================================

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

    // =============================================================================================
    // 1000-TRANFILE-GET-NEXT - the third arm, and the WHEN OTHER ordering.
    // =============================================================================================

    @Nested
    @DisplayName("1000-TRANFILE-GET-NEXT - '00', then '10', then WHEN OTHER (gates G30, G47)")
    class TranFileRead {

        // '22' is deliberately absent: TransactionRepository.ReadResult classifies it as DUPLICATE and
        // requires a record on that arm, so it cannot be reported as a bare failure. '23' can, and it
        // is the more interesting case anyway - a keyed status arriving from a sequential read.
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

    // =============================================================================================
    // The three keyed lookups - each abends, with its own byte-exact text (gate G47).
    // =============================================================================================

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
        @DisplayName("every INVALID KEY literal ends in a space, as the source writes it")
        void everyInvalidKeyLiteralEndsInASpace() {
            assertThat(TransactionReportJob.INVALID_CARD_NUMBER).endsWith(" ");
            assertThat(TransactionReportJob.INVALID_TRANSACTION_TYPE).endsWith(" ");
            assertThat(TransactionReportJob.INVALID_TRAN_CATG_KEY).endsWith(" ");
        }
    }

    // =============================================================================================
    // The twelve open and close ladders, plus the write ladder.
    // =============================================================================================

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

    // =============================================================================================
    // The summary, and the statelessness the whole design rests on.
    // =============================================================================================

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

    // =============================================================================================
    // Shared assertions.
    // =============================================================================================

    /**
     * Asserts that an abend carries what {@code 9999-ABEND-PROGRAM} sets, and that the three lines the
     * failing paragraph emits reached {@code SYSOUT} in order (gate G35).
     *
     * @param abend   the exception the run threw
     * @param harness the harness whose {@code SYSOUT} was captured
     * @param message the paragraph's own message
     * @param status  the file status the paragraph moved into {@code IO-STATUS}
     */
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

    /**
     * Every static field of a type that is not {@code final}.
     *
     * @param type the type to inspect
     * @return the names of its mutable statics, which must always be none
     */
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

}
