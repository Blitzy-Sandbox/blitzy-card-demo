package com.vsergeychik.carddemo.parity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.when;

import com.vsergeychik.carddemo.card.CardXrefRepository;
import com.vsergeychik.carddemo.card.model.CardXrefRecord;
import com.vsergeychik.carddemo.common.AbendException;
import com.vsergeychik.carddemo.common.CobolDecimal;
import com.vsergeychik.carddemo.common.FileStatus;
import com.vsergeychik.carddemo.common.FixedWidthRecord.FieldSpan;
import com.vsergeychik.carddemo.common.FixedWidthRecord.RecordLayout;
import com.vsergeychik.carddemo.common.PhysicalSequence;
import com.vsergeychik.carddemo.common.RecordImageForm;
import com.vsergeychik.carddemo.config.BatchConfig;
import com.vsergeychik.carddemo.config.BatchConfig.JobContract;
import com.vsergeychik.carddemo.config.BatchConfig.JobContracts;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBinding;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBindings;
import com.vsergeychik.carddemo.common.DatasetUnitOfWork;
import com.vsergeychik.carddemo.statement.StatementGenerationJobA.DatasetUtilityPort;
import com.vsergeychik.carddemo.transaction.DateParmReader;
import com.vsergeychik.carddemo.transaction.TranCategoryRepository;
import com.vsergeychik.carddemo.transaction.TranReportWriter;
import com.vsergeychik.carddemo.transaction.TranTypeRepository;
import com.vsergeychik.carddemo.transaction.TransactionReportJob;
import com.vsergeychik.carddemo.transaction.TransactionReportJob.ExecutionSummary;
import com.vsergeychik.carddemo.transaction.TransactionReportJob.SysoutSink;
import com.vsergeychik.carddemo.transaction.TransactionRepository;
import com.vsergeychik.carddemo.transaction.model.TranCategoryRecord;
import com.vsergeychik.carddemo.transaction.model.TranRecord;
import com.vsergeychik.carddemo.transaction.model.TranReportLayouts;
import com.vsergeychik.carddemo.transaction.model.TranTypeRecord;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * The twenty-case behavioural-parity gate for {@code app/cbl/CBTRN03C.cbl}, the transaction detail report,
 * against its Java translation {@link TransactionReportJob}.
 */
@DisplayName("CBTRN03C parity - the transaction detail report, both source defects included")
class CBTRN03CParityTest {
    private static final String PROGRAM = "CBTRN03C";

    private static final Charset ASCII = ParityHarness.FIXTURE_CHARSET;

    private static final String TRANREPT = TransactionReportJob.TRANREPT_DD_NAME;

    private static final RecordLayout FD_REPTFILE_REC = RecordLayout.of(
            TranReportWriter.RECORD_LENGTH,
            FieldSpan.alphanumeric("FD-REPTFILE-REC", 0, TranReportWriter.RECORD_LENGTH));

    private static final PhysicalSequence ORDINAL = PhysicalSequence.of("_ROWID_");

    private static final String TEST_TRANFILE = "TEST.TRANSACT.DALY";

    private static final String TEST_MASTER = "TEST.TRANSACT.VSAM.KSDS";

    private static final String TEST_BACKUP = "TEST.TRANSACT.BKUP";

    private static final String TEST_TRANREPT = "TEST.TRANREPT";

    private static final String TEST_CARDXREF = "TEST.CARDXREF";

    private static final String TEST_TRANTYPE = "TEST.TRANTYPE";

    private static final String TEST_TRANCATG = "TEST.TRANCATG";

    private static final String TEST_DATEPARM = "TEST.DATEPARM";

    private static final String BACKEND_REFUSAL_STATUS = "35";

    private static Stream<Arguments> cases() {
        return ParityHarness.casesOf(PROGRAM).stream()
                .map(parityCase -> Arguments.of(parityCase.caseId(), parityCase));
    }

    @ParameterizedTest(name = "{0} - diff count must be zero")
    @MethodSource("cases")
    @DisplayName("every case is field-for-field identical to the statically derived baseline (G18)")
    void everyCaseProducesNoDifference(String caseId, ParityCase parityCase) {
        FieldDiffer.DiffResult result = ParityHarness.usAscii()
                .judge(parityCase, ParityCase.UnitKind.BATCH_JOB, new ReportRun());

        assertThat(result.count())
                .as("%s/%s must produce no difference. A module is not complete until its diff count "
                        + "is zero across all %d of its cases, so one difference here means this "
                        + "module is incomplete rather than nearly done.%n%s",
                        PROGRAM, caseId, ParityHarness.CASES_PER_PROGRAM, result.render())
                .isZero();
        assertThat(result.isClean())
                .as("%s/%s reported a clean result inconsistent with its own count of %d",
                        PROGRAM, caseId, result.count())
                .isTrue();
    }

    @Test
    @DisplayName("the program declares exactly twenty cases, case01 through case20 (G15)")
    void theProgramDeclaresExactlyTwentyCases() {
        List<ParityCase> loaded = ParityHarness.casesOf(PROGRAM);

        assertThat(loaded)
                .as("the parity gate for %s is stated as twenty cases; a shorter set is not a smaller "
                        + "gate, it is a gate that stops asking questions", PROGRAM)
                .hasSize(ParityHarness.CASES_PER_PROGRAM);
        assertThat(loaded).extracting(ParityCase::caseId)
                .containsExactly(expectedCaseIds());
        assertThat(loaded).allSatisfy(parityCase ->
                assertThat(parityCase.program())
                        .as("a case under parity/%s/ that names another program would be judged "
                                + "against the wrong source", PROGRAM)
                        .isEqualTo(PROGRAM));
        assertThat(loaded).allSatisfy(parityCase ->
                assertThat(parityCase.description())
                        .as("%s must say which COBOL branch it pins, so parity coverage is auditable "
                                + "without re-reading the COBOL", parityCase.caseId())
                        .isNotBlank());
    }

    @Test
    @DisplayName("the reporting range comes from the DATEPARM dataset, never from a PARM")
    void everyCaseTakesItsRangeFromTheDatasetAndNotFromAParm() {
        for (ParityCase parityCase : ParityHarness.casesOf(PROGRAM)) {
            assertThat(parityCase.unitKind())
                    .as("%s drives a Spring Batch tasklet, so its unit kind is BATCH_JOB",
                            parityCase.caseId())
                    .isEqualTo(ParityCase.UnitKind.BATCH_JOB);
            assertThat(parityCase.jobParameters())
                    .as("%s declares a job parameter, but app/jcl/TRANREPT.jcl:59-80 gives STEP10R no "
                            + "PARM at all and 0550-DATEPARM-READ takes the range from a dataset. A "
                            + "case that passed the range in as a parameter would assert the opposite "
                            + "of what the JCL says", parityCase.caseId())
                    .isEmpty();
            assertThat(parityCase.inputs())
                    .as("%s must seed DD %s, because it is the only source of WS-START-DATE and "
                            + "WS-END-DATE", parityCase.caseId(), DateParmReader.DD_NAME)
                    .containsKey(DateParmReader.DD_NAME);
            assertThat(parityCase.screenRequest())
                    .as("%s is a batch case; a screen request would mean an online invocation",
                            parityCase.caseId())
                    .isNull();
            assertThat(parityCase.expectedResponse())
                    .as("%s is a batch case; an online response has nothing to compare against",
                            parityCase.caseId())
                    .isNull();
        }
    }

    private static String[] expectedCaseIds() {
        String[] identifiers = new String[ParityHarness.CASES_PER_PROGRAM];
        for (int ordinal = 1; ordinal <= ParityHarness.CASES_PER_PROGRAM; ordinal++) {
            identifiers[ordinal - 1] = ParityHarness.caseId(ordinal);
        }
        return identifiers;
    }

    @Test
    @DisplayName("every expected report record is exactly 133 bytes, on both channels (G20)")
    void everyExpectedReportRecordIsOneHundredAndThirtyThreeBytes() {
        for (ParityCase parityCase : ParityHarness.casesOf(PROGRAM)) {
            List<ParityCase.ExpectedRecord> pinned = new ArrayList<>(parityCase.expectedWrites());
            pinned.addAll(parityCase.expectedFinalState());
            for (ParityCase.ExpectedRecord record : pinned) {
                assertThat(record.dataset())
                        .as("%s pins a record on a dataset this program does not write; %s is its only "
                                + "output", parityCase.caseId(), TRANREPT)
                        .isEqualTo(TRANREPT);
                assertThat(record.expectedBytes())
                        .as("%s row %d must pin the whole record: seven differently shaped layouts "
                                + "share this FD, so a field subset would leave most of the line "
                                + "unasserted", parityCase.caseId(), record.rowIndex())
                        .isNotNull();
                assertThat(record.expectedBytes().length())
                        .as("%s row %d is %d character(s); app/jcl/TRANREPT.jcl:78 declares LRECL=133",
                                parityCase.caseId(), record.rowIndex(),
                                record.expectedBytes().length())
                        .isEqualTo(TranReportWriter.RECORD_LENGTH);
            }
            assertThat(parityCase.expectedDatasets())
                    .as("%s must pin %s at dataset level, because a dataset created and left empty is "
                            + "the one thing no row expectation can describe", parityCase.caseId(),
                            TRANREPT)
                    .isNotEmpty()
                    .allSatisfy(expectation -> {
                        assertThat(expectation.dataset()).isEqualTo(TRANREPT);
                        assertThat(expectation.recordLength())
                                .as("%s must pin %s's declared width, so an empty report is still "
                                        + "identifiable as a 133-byte report", parityCase.caseId(),
                                        TRANREPT)
                                .isEqualTo(TranReportWriter.RECORD_LENGTH);
                    });
        }
    }

    @Test
    @DisplayName("the rule line and the blank line pass through unpadded; the other five are padded")
    void theFullWidthLayoutsPassThroughAndTheNarrowOnesArePadded() {
        assertThat(TranReportLayouts.TRANSACTION_HEADER_2_IMAGE)
                .as("TRANSACTION-HEADER-2 is PIC X(133) VALUE ALL '-'")
                .hasSize(TranReportWriter.RECORD_LENGTH)
                .isEqualTo("-".repeat(TranReportWriter.RECORD_LENGTH));
        assertThat(TranReportWriter.TRANSACTION_HEADER_2_PAD)
                .as("a layout already at the record width must not be padded")
                .isZero();
        assertThat(TranReportWriter.WS_BLANK_LINE_IMAGE)
                .as("WS-BLANK-LINE is PIC X(133) VALUE SPACES - app/cbl/CBTRN03C.cbl:133")
                .hasSize(TranReportWriter.RECORD_LENGTH)
                .isBlank();
        assertThat(TranReportWriter.WS_BLANK_LINE_PAD).isZero();

        assertThat(TranReportLayouts.REPORT_NAME_HEADER_LENGTH).isEqualTo(115);
        assertThat(TranReportWriter.REPORT_NAME_HEADER_PAD).isEqualTo(18);
        assertThat(TranReportLayouts.TRANSACTION_HEADER_1_LENGTH).isEqualTo(114);
        assertThat(TranReportWriter.TRANSACTION_HEADER_1_PAD).isEqualTo(19);
        assertThat(TranReportLayouts.TRANSACTION_DETAIL_REPORT_LENGTH).isEqualTo(114);
        assertThat(TranReportWriter.TRANSACTION_DETAIL_REPORT_PAD).isEqualTo(19);
        assertThat(TranReportLayouts.REPORT_PAGE_TOTALS_LENGTH).isEqualTo(112);
        assertThat(TranReportWriter.REPORT_PAGE_TOTALS_PAD).isEqualTo(21);
        assertThat(TranReportLayouts.REPORT_ACCOUNT_TOTALS_LENGTH).isEqualTo(112);
        assertThat(TranReportWriter.REPORT_ACCOUNT_TOTALS_PAD).isEqualTo(21);
        assertThat(TranReportLayouts.REPORT_GRAND_TOTALS_LENGTH).isEqualTo(112);
        assertThat(TranReportWriter.REPORT_GRAND_TOTALS_PAD).isEqualTo(21);

        assertThat(TranReportLayouts.PAGE_TOTAL_LEADER_IMAGE)
                .as("REPORT-PAGE-TOTALS carries FILLER PIC X(86) VALUE ALL '.'")
                .isEqualTo(".".repeat(86));
        assertThat(TranReportLayouts.ACCOUNT_TOTAL_LEADER_IMAGE)
                .as("REPORT-ACCOUNT-TOTALS carries FILLER PIC X(84) VALUE ALL '.'")
                .isEqualTo(".".repeat(84));
        assertThat(TranReportLayouts.GRAND_TOTAL_LEADER_IMAGE)
                .as("REPORT-GRAND-TOTALS carries FILLER PIC X(86) VALUE ALL '.'")
                .isEqualTo(".".repeat(86));
    }

    @Test
    @DisplayName("the -ZZZ,ZZZ,ZZZ.ZZ and +ZZZ,ZZZ,ZZZ.ZZ masks render exactly (G24)")
    void theEditMasksRenderExactly() {
        assertThat(TranReportLayouts.DETAIL_AMOUNT_MASK).isEqualTo("-ZZZ,ZZZ,ZZZ.ZZ");
        assertThat(TranReportLayouts.TOTAL_AMOUNT_MASK).isEqualTo("+ZZZ,ZZZ,ZZZ.ZZ");
        assertThat(TranReportLayouts.AMOUNT_MASK_WIDTH).isEqualTo(15);

        assertThat(TranReportLayouts.editDetailAmount(new BigDecimal("0.00")))
                .as("every digit position is Z, so a zero value blanks the whole item - the decimal "
                        + "point included")
                .isEqualTo(" ".repeat(TranReportLayouts.AMOUNT_MASK_WIDTH));
        assertThat(TranReportLayouts.editTotalAmount(new BigDecimal("0.00")))
                .as("a total that nets to zero prints a blank column, not +0.00")
                .isEqualTo(" ".repeat(TranReportLayouts.AMOUNT_MASK_WIDTH));

        assertThat(TranReportLayouts.editDetailAmount(new BigDecimal("100.00")))
                .isEqualTo("         100.00");
        assertThat(TranReportLayouts.editDetailAmount(new BigDecimal("-1234.56")))
                .as("the leading minus is a fixed insertion character in position 1 and never floats, "
                        + "and the comma inside the suppressed run becomes a space")
                .isEqualTo("-      1,234.56");
        assertThat(TranReportLayouts.editDetailAmount(new BigDecimal("1234.56")))
                .as("the same mask prints a space where a positive value would show its sign")
                .isEqualTo("       1,234.56");
        assertThat(TranReportLayouts.editDetailAmount(new BigDecimal("999999999.99")))
                .as("a value that fills the mask suppresses nothing and prints both commas")
                .isEqualTo(" 999,999,999.99");
        assertThat(TranReportLayouts.editTotalAmount(new BigDecimal("999999999.99")))
                .isEqualTo("+999,999,999.99");
        assertThat(TranReportLayouts.editTotalAmount(new BigDecimal("-250.25")))
                .as("the + mask still prints a minus for a negative total")
                .isEqualTo("-        250.25");
        assertThat(TranReportLayouts.editDetailAmount(new BigDecimal("0.01")))
                .as("suppression cannot pass the decimal point, so a sub-unit value keeps it")
                .isEqualTo("            .01");
    }

    @Test
    @DisplayName("the totals are BigDecimal at scale 2 and every store truncates, never rounds (G24)")
    void theRunningTotalsTruncateAtNineIntegerDigitsAndScaleTwo() {
        assertThat(CobolDecimal.MONETARY_SCALE)
                .as("every monetary PICTURE in this codebase is scale 2")
                .isEqualTo(2);
        assertThat(CobolDecimal.COBOL_ROUNDING)
                .as("ROUNDED appears zero times in the 28 programs, so a store truncates")
                .isEqualTo(RoundingMode.DOWN);
        assertThat(TransactionReportJob.TOTAL_INTEGER_DIGITS).isEqualTo(9);
        assertThat(TransactionReportJob.TOTAL_SCALE).isEqualTo(CobolDecimal.MONETARY_SCALE);

        BigDecimal doubled = new BigDecimal("999999999.99").add(new BigDecimal("999999999.99"));
        assertThat(doubled)
                .as("the arithmetic sum needs ten integer digits")
                .isEqualByComparingTo(new BigDecimal("1999999999.98"));
        assertThat(CobolDecimal.storeAtPicture(doubled, TransactionReportJob.TOTAL_INTEGER_DIGITS,
                        TransactionReportJob.TOTAL_SCALE))
                .as("storing it into PIC S9(09)V99 discards the high-order digit, which is what a "
                        + "page or grand total that overflows nine integer digits shows")
                .isEqualTo(new BigDecimal("999999999.98"));
        assertThat(CobolDecimal.store(new BigDecimal("1.239"), CobolDecimal.MONETARY_SCALE))
                .as("truncation toward zero, not half-up - 1.239 stores as 1.23")
                .isEqualTo(new BigDecimal("1.23"));
        assertThat(CobolDecimal.store(new BigDecimal("-1.239"), CobolDecimal.MONETARY_SCALE))
                .as("truncation is sign-symmetric - -1.239 stores as -1.23")
                .isEqualTo(new BigDecimal("-1.23"));
    }

    @Test
    @DisplayName("the category lookup key is 6 bytes, not the 17-byte TRAN-CAT-KEY of CVTRA01Y")
    void theCategoryKeyIsSixBytesAndNotSeventeen() {
        assertThat(TranCategoryRepository.KEY_LENGTH)
                .as("CVTRA04Y's TRAN-CAT-KEY is TRAN-TYPE-CD X(02) + TRAN-CAT-CD 9(04)")
                .isEqualTo(6);
        assertThat(TranCategoryRepository.TRAN_CAT_BAL_KEY_LENGTH)
                .as("CVTRA01Y's identically named key belongs to a file CBTRN03C does not open")
                .isEqualTo(17);
        assertThat(TranCategoryRepository.KEY_LENGTH)
                .isNotEqualTo(TranCategoryRepository.TRAN_CAT_BAL_KEY_LENGTH);
        assertThat(TranTypeRepository.TRAN_TYPE_KEY_LENGTH)
                .as("CVTRA03Y's TRAN-TYPE is PIC X(02)")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("a refused report write abends with RETURN-CODE 12 and discards the generation (G47)")
    void aRefusedReportWriteAbendsAndDiscardsTheGeneration() {
        Injection injection = Injection.refusingWrite(3);
        Collector collector = new Collector(injection);
        TransactionReportJob job = job(oneInRangeTransaction(), collector, injection);

        AbendException abend = catchThrowableOfType(AbendException.class,
                () -> job.execute(collector.sysout(), collector.sink()));

        assertThat(abend.getReturnCode())
                .as("every abend site in this program arrives with APPL-RESULT 12")
                .isEqualTo(AbendException.RETURN_CODE_IO_ERROR);
        assertThat(collector.sysoutLines())
                .as("the ladder displays its own message, then 9910-DISPLAY-IO-STATUS, then "
                        + "9999-ABEND-PROGRAM")
                .containsSubsequence("ERROR WRITING REPTFILE", "ABENDING PROGRAM")
                .doesNotContain("END OF EXECUTION OF PROGRAM CBTRN03C");
        assertThat(collector.writtenImages())
                .as("the refusal is on the third write, so two records had already been written")
                .hasSize(3);
        assertThat(collector.retainedImages())
                .as("DISP=(NEW,CATLG,DELETE) leaves no report behind when the run does not reach GOBACK")
                .isEmpty();
    }

    @Test
    @DisplayName("a TRANTYPE open failure abends before the range is read or anything is written (G47)")
    void aTranTypeOpenFailureAbendsBeforeAnythingIsWritten() {
        Injection injection = Injection.tranTypeOpenStatus(BACKEND_REFUSAL_STATUS);
        Collector collector = new Collector(injection);
        TransactionReportJob job = job(oneInRangeTransaction(), collector, injection);

        AbendException abend = catchThrowableOfType(AbendException.class,
                () -> job.execute(collector.sysout(), collector.sink()));

        assertThat(abend.getReturnCode()).isEqualTo(AbendException.RETURN_CODE_IO_ERROR);
        assertThat(collector.sysoutLines())
                .containsExactly("START OF EXECUTION OF PROGRAM CBTRN03C",
                        "ERROR OPENING TRANSACTION TYPE FILE",
                        FileStatus.toDisplayLine(BACKEND_REFUSAL_STATUS),
                        "ABENDING PROGRAM");
        assertThat(collector.writtenImages()).isEmpty();
        assertThat(collector.retainedImages()).isEmpty();
    }

    @Test
    @DisplayName("a TRANFILE read failure takes WHEN OTHER and abends with its own status (G47)")
    void aTranFileReadFailureAbendsWithTheStatusItReported() {
        Injection injection = Injection.tranFileReadStatus(BACKEND_REFUSAL_STATUS);
        Collector collector = new Collector(injection);
        TransactionReportJob job = job(oneInRangeTransaction(), collector, injection);

        AbendException abend = catchThrowableOfType(AbendException.class,
                () -> job.execute(collector.sysout(), collector.sink()));

        assertThat(abend.getReturnCode()).isEqualTo(AbendException.RETURN_CODE_IO_ERROR);
        assertThat(collector.sysoutLines())
                .containsSubsequence("ERROR READING TRANSACTION FILE",
                        FileStatus.toDisplayLine(BACKEND_REFUSAL_STATUS),
                        "ABENDING PROGRAM");
        assertThat(collector.writtenImages()).isEmpty();
    }

    @Test
    @DisplayName("a report close failure abends after a complete report and discards it (G47)")
    void aReportCloseFailureAbendsAfterACompleteReport() {
        Injection injection = Injection.refusingClose();
        Collector collector = new Collector(injection);
        TransactionReportJob job = job(oneInRangeTransaction(), collector, injection);

        AbendException abend = catchThrowableOfType(AbendException.class,
                () -> job.execute(collector.sysout(), collector.sink()));

        assertThat(abend.getReturnCode()).isEqualTo(AbendException.RETURN_CODE_IO_ERROR);
        assertThat(collector.sysoutLines())
                .containsSubsequence("TRAN-AMT 0000001000{",
                        "ERROR CLOSING REPORT FILE",
                        "ABENDING PROGRAM")
                .doesNotContain("END OF EXECUTION OF PROGRAM CBTRN03C");
        assertThat(collector.writtenImages())
                .as("the report was complete: four headers, one detail, the page total, its rule line "
                        + "and the grand total")
                .hasSize(8);
        assertThat(collector.retainedImages())
                .as("an abnormal end deletes the generation however complete it was")
                .isEmpty();
    }

    private static SeededInputs oneInRangeTransaction() {
        TranRecord record = new TranRecord(ASCII);
        record.moveTranId("TRN0000000000001");
        record.moveTranTypeCd("01");
        record.moveTranCatCd(1);
        record.moveTranSource("POS TERM  ");
        record.moveTranDesc("A statically derived fixture transaction");
        record.moveTranAmt(new BigDecimal("100.00"));
        record.moveTranMerchantId(999999999L);
        record.moveTranMerchantName("FIXTURE MERCHANT");
        record.moveTranMerchantCity("FIXTURE CITY");
        record.moveTranMerchantZip("0000000000");
        record.moveTranCardNum("0500024453765740");
        record.moveTranOrigTs("2022-03-15-11.22.33.444444");
        record.moveTranProcTs("2022-03-15-11.22.33.444444");

        Map<String, CardXrefRecord> xref = new LinkedHashMap<>();
        xref.put("0500024453765740", new CardXrefRecord("0500024453765740", 50, 50L));
        Map<String, TranTypeRecord> types = new LinkedHashMap<>();
        types.put("01", TranTypeRecord.of("01", "Purchase", ASCII));
        Map<String, TranCategoryRecord> categories = new LinkedHashMap<>();
        categories.put("010001", TranCategoryRecord.of("01", 1, "Regular Sales Draft", ASCII));

        return new SeededInputs(List.of(record), xref, types, categories,
                List.of(("2022-01-01" + " " + "2022-07-06" + " ".repeat(59))));
    }

    private static final class ReportRun implements ParityHarness.ParityUnit {
        @Override
        public ParityHarness.UnitOutcome invoke(ParityHarness.Invocation invocation) {
            SeededInputs inputs = decode(invocation);
            Collector collector = new Collector(Injection.NONE);
            TransactionReportJob job = job(inputs, collector, Injection.NONE);
            ParityHarness.UnitOutcome.Builder recorder = invocation.recorder();
            try {
                ExecutionSummary summary = job.execute(collector.sysout(), collector.sink());
                record(recorder, collector);
                recorder.returnCode(summary.returnCode());
            } catch (AbendException abend) {
                record(recorder, collector);
                throw abend;
            }
            return null;
        }

        private static void record(ParityHarness.UnitOutcome.Builder recorder, Collector collector) {
            List<String> written = collector.writtenImages();
            if (written.isEmpty()) {
                recorder.openedWithoutWriting(TRANREPT, FD_REPTFILE_REC);
            } else {
                recorder.wroteAll(TRANREPT, FD_REPTFILE_REC, written);
            }
            recorder.finalState(TRANREPT, FD_REPTFILE_REC, collector.retainedImages());
            for (String line : collector.sysoutLines()) {
                recorder.display(line);
            }
        }
    }

    private record SeededInputs(List<TranRecord> transactions,
                                Map<String, CardXrefRecord> xref,
                                Map<String, TranTypeRecord> types,
                                Map<String, TranCategoryRecord> categories,
                                List<String> dateParmRows) {
    }

    private static SeededInputs decode(ParityHarness.Invocation invocation) {
        Charset charset = invocation.charset();
        List<TranRecord> transactions = new ArrayList<>();
        for (String row : rowsOf(invocation, TransactionReportJob.TRANFILE_DD_NAME)) {
            transactions.add(TranRecord.decode(row, charset));
        }
        Map<String, CardXrefRecord> xref = new LinkedHashMap<>();
        for (String row : rowsOf(invocation, TransactionReportJob.CARDXREF_DD_NAME)) {
            CardXrefRecord record = CardXrefRecord.decode(
                    invocation.codec().encodeImage(row, "a CARDXREF row"), invocation.codec());
            xref.put(record.xrefCardNum(), record);
        }
        Map<String, TranTypeRecord> types = new LinkedHashMap<>();
        for (String row : rowsOf(invocation, TranTypeRepository.DD_NAME)) {
            TranTypeRecord record = TranTypeRecord.decode(row, charset);
            types.put(record.tranType(), record);
        }
        Map<String, TranCategoryRecord> categories = new LinkedHashMap<>();
        for (String row : rowsOf(invocation, TranCategoryRepository.DD_NAME)) {
            TranCategoryRecord record = TranCategoryRecord.decode(
                    invocation.codec().encodeImage(row, "a TRANCATG row"), charset);
            categories.put(record.tranCatKeyImage(), record);
        }
        return new SeededInputs(transactions, xref, types, categories,
                rowsOf(invocation, DateParmReader.DD_NAME));
    }

    private static List<String> rowsOf(ParityHarness.Invocation invocation, String dataset) {
        return invocation.hasDataset(dataset) ? invocation.dataset(dataset).rows() : List.of();
    }

    private record Injection(String tranTypeOpenStatus,
                             String tranFileReadStatus,
                             int refusedWriteNumber,
                             FileStatus.Outcome closeOutcome) {
        private static final Injection NONE = new Injection(null, null, 0, FileStatus.Outcome.OK);

        private static Injection tranTypeOpenStatus(String status) {
            return new Injection(status, null, 0, FileStatus.Outcome.OK);
        }

        private static Injection tranFileReadStatus(String status) {
            return new Injection(null, status, 0, FileStatus.Outcome.OK);
        }

        private static Injection refusingWrite(int writeNumber) {
            return new Injection(null, null, writeNumber, FileStatus.Outcome.OK);
        }

        private static Injection refusingClose() {
            return new Injection(null, null, 0, FileStatus.Outcome.OTHER);
        }
    }

    private static TransactionReportJob job(SeededInputs inputs, Collector collector,
            Injection injection) {
        DatasetBindings catalogue = bindings();
        return new TransactionReportJob(
                batchConfig(catalogue),
                transactionRepository(inputs.transactions(), injection),
                cardXrefRepository(inputs.xref()),
                tranTypeRepository(inputs.types(), injection),
                tranCategoryRepository(inputs.categories()),
                dateParmReader(inputs.dateParmRows(), catalogue),
                new TranReportWriter(new JdbcTemplate(), ASCII, catalogue, RecordImageForm.CHARACTER),
                ASCII,
                new SuppliedProvider<>(collector.sysout()),
                new JdbcTemplate(),
                RecordImageForm.CHARACTER,
                ORDINAL,
                unitOfWork(),
                new SuppliedProvider<>(new InMemoryDatasetUtilityPort()));
    }

    private static TransactionRepository transactionRepository(List<TranRecord> records,
            Injection injection) {
        TransactionRepository repository = mock(TransactionRepository.class);
        TransactionRepository.InputFile inputFile = mock(TransactionRepository.InputFile.class);
        when(repository.openInput(any(DatasetBinding.class))).thenReturn(inputFile);
        when(inputFile.openStatus()).thenReturn(FileStatus.OK);
        when(inputFile.closeInput()).thenReturn(FileStatus.OK);
        when(inputFile.isOpen()).thenAnswer(question -> mockingDetails(inputFile).getInvocations()
                .stream().noneMatch(call -> "closeInput".equals(call.getMethod().getName())));

        List<TransactionRepository.ReadResult> reads = new ArrayList<>();
        if (injection.tranFileReadStatus() != null) {
            reads.add(TransactionRepository.ReadResult.other(TransactionReportJob.TRANFILE_DD_NAME,
                    injection.tranFileReadStatus()));
        } else {
            for (TranRecord record : records) {
                reads.add(TransactionRepository.ReadResult.found(
                        TransactionReportJob.TRANFILE_DD_NAME, record));
            }
        }
        reads.add(TransactionRepository.ReadResult.endOfFile(TransactionReportJob.TRANFILE_DD_NAME));
        when(inputFile.readNext()).thenReturn(reads.get(0),
                reads.subList(1, reads.size()).toArray(new TransactionRepository.ReadResult[0]));
        return repository;
    }

    private static CardXrefRepository cardXrefRepository(Map<String, CardXrefRecord> rows) {
        CardXrefRepository repository = mock(CardXrefRepository.class);
        CardXrefRepository.BrowseCursor cursor = mock(CardXrefRepository.BrowseCursor.class);
        when(repository.openBrowse()).thenReturn(cursor);
        when(cursor.openStatus()).thenReturn(FileStatus.OK);
        when(cursor.closeBrowse()).thenReturn(FileStatus.OK);
        when(cursor.isOpen()).thenAnswer(question -> mockingDetails(cursor).getInvocations()
                .stream().noneMatch(call -> "closeBrowse".equals(call.getMethod().getName())));
        when(repository.readByCardNumber(anyString())).thenAnswer(question -> {
            String key = question.getArgument(0);
            CardXrefRecord found = rows.get(key);
            return found == null
                    ? CardXrefRepository.ReadResult.notFound(CardXrefRepository.BASE_DD_NAME)
                    : CardXrefRepository.ReadResult.found(CardXrefRepository.BASE_DD_NAME, found,
                            new String(found.encode(ASCII), ASCII));
        });
        return repository;
    }

    private static TranTypeRepository tranTypeRepository(Map<String, TranTypeRecord> rows,
            Injection injection) {
        TranTypeRepository repository = mock(TranTypeRepository.class);
        when(repository.open()).thenReturn(injection.tranTypeOpenStatus() == null
                ? FileStatus.OK
                : injection.tranTypeOpenStatus());
        when(repository.close()).thenReturn(FileStatus.OK);
        when(repository.readByTranType(anyString())).thenAnswer(question -> {
            String key = question.getArgument(0);
            TranTypeRecord found = rows.get(key);
            return found == null
                    ? TranTypeRepository.ReadResult.notFound(key)
                    : TranTypeRepository.ReadResult.found(key, found);
        });
        return repository;
    }

    private static TranCategoryRepository tranCategoryRepository(
            Map<String, TranCategoryRecord> rows) {
        TranCategoryRepository repository = mock(TranCategoryRepository.class);
        when(repository.open()).thenReturn(FileStatus.OK);
        when(repository.close()).thenReturn(FileStatus.OK);
        when(repository.keyImage(anyString(), anyInt())).thenAnswer(question ->
                keyImageOf(question.getArgument(0), question.getArgument(1)));
        when(repository.readByKey(anyString(), anyInt())).thenAnswer(question -> {
            TranCategoryRecord found = rows.get(
                    keyImageOf(question.getArgument(0), question.getArgument(1)));
            return found == null
                    ? TranCategoryRepository.ReadResult.notFound()
                    : TranCategoryRepository.ReadResult.found(found);
        });
        return repository;
    }

    private static String keyImageOf(String tranTypeCd, int tranCatCd) {
        return String.format("%-2s%04d", tranTypeCd, tranCatCd);
    }

    private static DateParmReader dateParmReader(List<String> rows, DatasetBindings catalogue) {
        DateParmReader decoder = new DateParmReader(new JdbcTemplate(), catalogue, ASCII,
                RecordImageForm.CHARACTER, ORDINAL);
        DateParmReader reader = mock(DateParmReader.class);
        when(reader.open()).thenReturn(FileStatus.OK);
        when(reader.close()).thenReturn(FileStatus.OK);
        when(reader.read()).thenReturn(rows.isEmpty()
                ? DateParmReader.ReadResult.endOfFile()
                : DateParmReader.ReadResult.found(decoder.decode(rows.get(0))));
        return reader;
    }

    private static DatasetBindings bindings() {
        DatasetBindings catalogue = new DatasetBindings();
        catalogue.put(TranReportWriter.DD_NAME, new DatasetBinding(TEST_TRANREPT, "sequential", true,
                TranReportWriter.RECORD_FORMAT, TranReportWriter.BLOCK_SIZE,
                TranReportWriter.RECORD_LENGTH, "CVTRA07Y", null, null, null, null));
        catalogue.put(TransactionReportJob.TRANFILE_DD_NAME, new DatasetBinding(TEST_TRANFILE,
                "sequential", true, "FB", 0, TranRecord.RECORD_LENGTH, "CVTRA05Y", null, null, null,
                null));
        catalogue.put(TransactionReportJob.CARDXREF_DD_NAME, new DatasetBinding(TEST_CARDXREF,
                DatasetBinding.KSDS, false, "FB", null, CardXrefRecord.RECORD_LENGTH, "CVACT03Y", null,
                null, null, null));
        catalogue.put(CardXrefRepository.BASE_DD_NAME, new DatasetBinding(TEST_CARDXREF,
                DatasetBinding.KSDS, false, "FB", null, CardXrefRecord.RECORD_LENGTH, "CVACT03Y", null,
                null, null, null));
        catalogue.put(TranTypeRepository.DD_NAME, new DatasetBinding(TEST_TRANTYPE,
                DatasetBinding.KSDS, false, "FB", null, TranTypeRecord.RECORD_LENGTH, "CVTRA03Y", null,
                null, null, null));
        catalogue.put(TranCategoryRepository.DD_NAME, new DatasetBinding(TEST_TRANCATG,
                DatasetBinding.KSDS, false, "FB", null, TranCategoryRecord.RECORD_LENGTH, "CVTRA04Y",
                null, null, null, null));
        catalogue.put(DateParmReader.DD_NAME, new DatasetBinding(TEST_DATEPARM, "sequential", true,
                "FB", 0, DateParmReader.RECORD_LENGTH, "CBTRN03C", null, null, null, null));
        catalogue.put(TransactionReportJob.BACKUP_INPUT_DD_NAME, new DatasetBinding(TEST_MASTER,
                DatasetBinding.KSDS, false, TransactionReportJob.UTILITY_RECORD_FORMAT, 0,
                TranRecord.RECORD_LENGTH, "CVTRA05Y", TranRecord.TRAN_ID_KEY_LENGTH, 0, null, null));
        catalogue.put(TransactionReportJob.BACKUP_OUTPUT_DD_NAME, new DatasetBinding(TEST_BACKUP,
                "sequential", true, TransactionReportJob.UTILITY_RECORD_FORMAT, 0,
                TranRecord.RECORD_LENGTH, "CVTRA05Y", null, null, null, null));
        catalogue.put(TransactionReportJob.SORT_INPUT_DD_NAME, new DatasetBinding(TEST_BACKUP,
                "sequential", true, TransactionReportJob.UTILITY_RECORD_FORMAT, 0,
                TranRecord.RECORD_LENGTH, "CVTRA05Y", null, null, null, null));
        catalogue.put(TransactionReportJob.SORT_OUTPUT_DD_NAME, new DatasetBinding(TEST_TRANFILE,
                "sequential", true, TransactionReportJob.UTILITY_RECORD_FORMAT, 0,
                TranRecord.RECORD_LENGTH, "CVTRA05Y", null, null, null, null));
        return catalogue;
    }

    private static BatchConfig batchConfig(DatasetBindings catalogue) {
        JobContracts contracts = new JobContracts();
        contracts.put(TransactionReportJob.JOB_KEY, new JobContract(
                TransactionReportJob.PROGRAM_NAME, List.of(), TransactionReportJob.REQUIRED_STEPS,
                TransactionReportJob.DATE_RANGE_SOURCE, Map.of()));
        return new BatchConfig(new SuppliedProvider<>(mock(JobRepository.class)),
                new SuppliedProvider<>(mock(PlatformTransactionManager.class)), contracts, catalogue);
    }

    private static DatasetUnitOfWork unitOfWork() {
        return new DatasetUnitOfWork(new JdbcTransactionManager(new SimpleDriverDataSource(
                new org.h2.Driver(), "jdbc:h2:mem:cbtrn03c-parity-" + UUID.randomUUID(), "sa", "")));
    }

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

    private static final class Collector {
        private final ReportSink sink;

        private final CapturingSysout sysout = new CapturingSysout();

        private Collector(Injection injection) {
            this.sink = new ReportSink(injection);
        }

        private ReportSink sink() {
            return sink;
        }

        private CapturingSysout sysout() {
            return sysout;
        }

        private List<String> writtenImages() {
            return sink.writtenImages();
        }

        private List<String> retainedImages() {
            return sink.retainedImages();
        }

        private List<String> sysoutLines() {
            return sysout.lines();
        }
    }

    private static final class ReportSink implements TranReportWriter.RecordSink {
        private final List<String> written = new ArrayList<>();

        private final List<String> retained = new ArrayList<>();

        private final Injection injection;

        private ReportSink(Injection injection) {
            this.injection = injection;
        }

        @Override
        public FileStatus.Outcome write(byte[] recordImage) {
            String image = new String(recordImage, ASCII);
            written.add(image);
            if (written.size() == injection.refusedWriteNumber()) {
                return FileStatus.Outcome.OTHER;
            }
            retained.add(image);
            return FileStatus.Outcome.OK;
        }

        @Override
        public FileStatus.Outcome close() {
            return injection.closeOutcome();
        }

        @Override
        public FileStatus.Outcome discard(int recordsWritten) {
            retained.clear();
            return FileStatus.Outcome.OK;
        }

        private List<String> writtenImages() {
            return List.copyOf(written);
        }

        private List<String> retainedImages() {
            return List.copyOf(retained);
        }
    }

    private static final class CapturingSysout implements SysoutSink {
        private final List<String> lines = new ArrayList<>();

        @Override
        public void display(String line) {
            lines.add(line);
        }

        private List<String> lines() {
            return List.copyOf(lines);
        }
    }

    private static final class InMemoryDatasetUtilityPort implements DatasetUtilityPort {
        private final Map<String, List<String>> datasets = new LinkedHashMap<>();

        @Override
        public int deleteAllRecords(DatasetBinding binding) {
            List<String> removed = datasets.put(binding.dsname(), new ArrayList<>());
            return removed == null ? 0 : removed.size();
        }

        @Override
        public List<String> readAllRecordImages(DatasetBinding binding) {
            return List.copyOf(datasets.getOrDefault(binding.dsname(), List.of()));
        }

        @Override
        public int writeRecordImages(DatasetBinding binding, List<String> recordImages) {
            datasets.computeIfAbsent(binding.dsname(), key -> new ArrayList<>()).addAll(recordImages);
            return recordImages.size();
        }
    }
}
