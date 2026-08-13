package com.vsergeychik.carddemo.parity;

import com.vsergeychik.carddemo.common.AbendException;
import com.vsergeychik.carddemo.common.FileStatus;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.FixedWidthRecord.FieldSpan;
import com.vsergeychik.carddemo.common.FixedWidthRecord.PictureKind;
import com.vsergeychik.carddemo.common.RecordImageForm;
import com.vsergeychik.carddemo.config.BatchConfig;
import com.vsergeychik.carddemo.config.BatchConfig.JobContract;
import com.vsergeychik.carddemo.config.BatchConfig.JobContracts;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBinding;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBindings;
import com.vsergeychik.carddemo.customer.CustomerFileReaderJob;
import com.vsergeychik.carddemo.customer.CustomerRepository;
import com.vsergeychik.carddemo.customer.CustomerRepository.CustomerFile;
import com.vsergeychik.carddemo.customer.CustomerRepository.ReadResult;
import com.vsergeychik.carddemo.customer.CustomerService;
import com.vsergeychik.carddemo.testdataset.RecordImageDataSource;
import com.vsergeychik.carddemo.testdataset.RecordImageStore.ColumnForm;
import com.vsergeychik.carddemo.customer.CustomerService.SysoutSink;
import com.vsergeychik.carddemo.customer.model.CustomerRecord;
import com.vsergeychik.carddemo.parity.FieldDiffer.DiffResult;
import com.vsergeychik.carddemo.parity.ParityCase.EmittedMessage;
import com.vsergeychik.carddemo.parity.ParityCase.UnitKind;
import com.vsergeychik.carddemo.parity.ParityHarness.Invocation;
import com.vsergeychik.carddemo.parity.ParityHarness.SeededDataset;
import com.vsergeychik.carddemo.parity.ParityHarness.UnitOutcome;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mockito;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.scope.context.StepContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * The parity gate for {@code CBCUS01C}: twenty declarative cases, each judged field by field, each required
 * to report a diff count of zero.
 */
@DisplayName("CBCUS01C parity - 20 statically derived cases over the standalone batch program that "
        + "reads the customer master and displays every record twice")
class CBCUS01CParityTest {
    private static final String PROGRAM = CustomerService.PROGRAM_ID;

    private static final String DD_NAME = CustomerRepository.BATCH_DD_NAME;

    private static final String OPEN_SITE = "OPEN-" + CustomerRepository.BATCH_DD_NAME;

    private static final String READ_SITE = "READ-" + CustomerRepository.BATCH_DD_NAME;

    private static final String CLOSE_SITE = "CLOSE-" + CustomerRepository.BATCH_DD_NAME;

    private static final Charset DATASET_CHARSET = ParityHarness.FIXTURE_CHARSET;

    private static final String TEST_DSNAME = "PARITY.CBCUS01C.CUSTOMER.KSDS";

    private static final String RECORD_IMAGE_COLUMN = "REC";

    private static final String PERMANENT_ERROR_LINE =
            FileStatus.toDisplayLine(CustomerRepository.PERMANENT_ERROR_STATUS);

    private record Scenario(String openStatus, String closeStatus, int failingRead,
                            String failingReadStatus) {
        private static final int NO_FAILING_READ = -1;

        private Scenario {
            requireStatus(openStatus, "openStatus");
            requireStatus(closeStatus, "closeStatus");
            if (failingRead < NO_FAILING_READ) {
                throw new IllegalStateException("A failing read index of " + failingRead
                        + " is neither a zero-based position nor " + NO_FAILING_READ
                        + ", which is how this table says that no read fails");
            }
            boolean readFails = failingRead != NO_FAILING_READ;
            if (readFails != (failingReadStatus != null)) {
                throw new IllegalStateException("A scenario must either name a failing read index AND "
                        + "the status that read reports, or neither. This one names index "
                        + failingRead + " and status "
                        + (failingReadStatus == null ? "none" : "one")
                        + ", which would arrange a failure nothing reports or a status nothing "
                        + "carries.");
            }
            if (failingReadStatus != null) {
                requireStatus(failingReadStatus, "failingReadStatus");
            }
            if (!FileStatus.isOk(openStatus) && (readFails || !FileStatus.isOk(closeStatus))) {
                throw new IllegalStateException("A scenario whose OPEN fails cannot also arrange a "
                        + "read or a close outcome: app/cbl/CBCUS01C.cbl:L132 abends inside "
                        + "0000-CUSTFILE-OPEN, so neither the READ at L93 nor the CLOSE at L138 is "
                        + "ever reached, and an arrangement for them would assert a path that does "
                        + "not exist.");
            }
            if (readFails && !FileStatus.isOk(closeStatus)) {
                throw new IllegalStateException("A scenario whose READ fails cannot also arrange a "
                        + "failing CLOSE: app/cbl/CBCUS01C.cbl:L113 abends inside "
                        + "1000-CUSTFILE-GET-NEXT, so 9000-CUSTFILE-CLOSE at L136 is never "
                        + "performed. The handle is still released silently on the way out, which is "
                        + "why the close status is stated at all.");
            }
        }

        private static void requireStatus(String status, String member) {
            Objects.requireNonNull(status, "Scenario." + member + " is required; a COBOL FILE STATUS "
                    + "is always two characters, and '" + FileStatus.OK + "' is how a successful "
                    + "operation reports itself");
            if (status.length() != FileStatus.STATUS_LENGTH) {
                throw new IllegalStateException("Scenario." + member + " is " + status.length()
                        + " character(s); IO-STATUS at app/cbl/CBCUS01C.cbl:L50-L52 is two PIC X "
                        + "items and holds exactly " + FileStatus.STATUS_LENGTH);
            }
        }

        private static Scenario asDeclared() {
            return new Scenario(FileStatus.OK, FileStatus.OK, NO_FAILING_READ, null);
        }

        private static Scenario openFails(String status) {
            return new Scenario(status, FileStatus.OK, NO_FAILING_READ, null);
        }

        private static Scenario readFailsAfter(int afterRecords, String status) {
            return new Scenario(FileStatus.OK, FileStatus.OK, afterRecords, status);
        }

        private static Scenario closeFails(String status) {
            return new Scenario(FileStatus.OK, status, NO_FAILING_READ, null);
        }

        private boolean arranged() {
            return !FileStatus.isOk(openStatus) || readFails() || closeFails();
        }

        private boolean readFails() {
            return failingRead != NO_FAILING_READ;
        }

        private boolean closeFails() {
            return !FileStatus.isOk(closeStatus);
        }

        private String failingStatus() {
            if (!FileStatus.isOk(openStatus)) {
                return openStatus;
            }
            if (readFails()) {
                return failingReadStatus;
            }
            return closeFails() ? closeStatus : null;
        }
    }

    private static Scenario scenarioFor(ParityCase parityCase) {
        return scenarioFrom(parityCase.unitStimulus());
    }

    private static Scenario scenarioFrom(ParityCase.UnitStimulus stimulus) {
        if (!stimulus.operationScript().isEmpty() || !stimulus.linkage().isEmpty()
                || !stimulus.stepStatuses().isEmpty() || !stimulus.environment().isEmpty()) {
            throw new IllegalArgumentException(PROGRAM + " calls no subprogram, takes no linkage, "
                    + "follows no conditional job step and runs under no environmental variant: its "
                    + "only stimulus is its seeded rows and the outcome of one of its three call sites.");
        }
        String openStatus = FileStatus.OK;
        String closeStatus = FileStatus.OK;
        int failingRead = Scenario.NO_FAILING_READ;
        String failingReadStatus = null;
        for (Map.Entry<String, ParityCase.CallSiteOutcome> declared
                : stimulus.callSiteOutcomes().entrySet()) {
            String site = declared.getKey();
            ParityCase.CallSiteOutcome outcome = declared.getValue();
            String status = requireStatusOutcome(site, outcome);
            if (OPEN_SITE.equals(site)) {
                openStatus = status;
            } else if (CLOSE_SITE.equals(site)) {
                closeStatus = status;
            } else if (READ_SITE.equals(site)) {
                failingRead = outcome.recordsBefore();
                failingReadStatus = status;
            } else {
                throw new IllegalArgumentException("Call site " + site + " is not one of " + PROGRAM
                        + "'s three: " + OPEN_SITE + ", " + READ_SITE + " and " + CLOSE_SITE
                        + ". A site nothing answers to arranges nothing, and the case would assert the "
                        + "opposite of what it says.");
            }
        }
        return new Scenario(openStatus, closeStatus, failingRead, failingReadStatus);
    }

    private static String requireStatusOutcome(String site, ParityCase.CallSiteOutcome outcome) {
        if (outcome.resp() != null) {
            throw new IllegalArgumentException("Call site " + site + " declares a CICS RESP, but "
                    + PROGRAM + " is a batch program: every one of its I/O verbs reports a "
                    + "two-character FILE STATUS and the program tests that and nothing else.");
        }
        if (outcome.isRefused()) {
            return CustomerRepository.PERMANENT_ERROR_STATUS;
        }
        if (outcome.status() == null) {
            throw new IllegalArgumentException("Call site " + site + " declares neither a FILE STATUS "
                    + "nor a refusal, so it arranges nothing at all.");
        }
        return outcome.status();
    }

    private static Map<String, Scenario> shippedScenarios() {
        Map<String, Scenario> declared = new LinkedHashMap<>();
        for (ParityCase parityCase : ParityHarness.casesOf(PROGRAM)) {
            declared.put(parityCase.caseId(), scenarioFor(parityCase));
        }
        return Collections.unmodifiableMap(declared);
    }

    static List<ParityCase> cases() {
        return ParityHarness.casesOf(PROGRAM);
    }

    @Test
    @DisplayName("the set is exactly 20 CBCUS01C cases, case01 through case20, each with a scenario")
    void theCaseSetIsExactlyTwenty() {
        List<ParityCase> declared = cases();

        assertThat(declared)
                .as("the gate is 'diff count zero across all twenty cases', so the set must hold "
                        + "exactly " + ParityHarness.CASES_PER_PROGRAM + " cases; write the missing "
                        + "case files rather than lowering the count")
                .hasSize(ParityHarness.CASES_PER_PROGRAM);

        for (int ordinal = 1; ordinal <= ParityHarness.CASES_PER_PROGRAM; ordinal++) {
            ParityCase declaredCase = declared.get(ordinal - 1);
            String where = PROGRAM + '/' + declaredCase.caseId();

            assertThat(declaredCase.caseId())
                    .as("the cases must be enumerated in ascending order")
                    .isEqualTo(ParityHarness.caseId(ordinal));
            assertThat(declaredCase.program())
                    .as("every case in parity/%s/ must name that program", PROGRAM)
                    .isEqualTo(PROGRAM);
            assertThat(declaredCase.unitKind())
                    .as("%s: %s is a non-CICS batch program whose logic lives in CustomerService and "
                            + "whose runnable form is CustomerFileReaderJob, so a case reaches it "
                            + "either as a batch job or as that service - never through a controller "
                            + "and never as a called component", where, PROGRAM)
                    .isIn(UnitKind.BATCH_JOB, UnitKind.SERVICE);
            assertThat(declaredCase.jobParameters())
                    .as("%s: app/jcl/READCUST.jcl:L6 is a bare EXEC PGM=%s with no PARM, so no case "
                            + "may declare a job parameter", where, PROGRAM)
                    .isEmpty();
            assertThat(declaredCase.expectedWrites())
                    .as("%s: %s opens CUSTFILE INPUT and issues no WRITE and no REWRITE at all - the "
                            + "CustomerRepository name notwithstanding - so no case may expect a "
                            + "written record", where, PROGRAM)
                    .isEmpty();
            assertThat(scenarioFor(declaredCase))
                    .as("%s: every case states the backend behaviour it runs under", where)
                    .isNotNull();
        }

        assertThat(shippedScenarios().keySet())
                .as("every shipped case decodes to exactly one scenario, and no scenario exists that no "
                        + "case declares - which is what a table in Java could not guarantee")
                .containsExactlyElementsOf(declared.stream().map(ParityCase::caseId).toList());
    }

    @Test
    @DisplayName("declares BATCH_JOB for eighteen cases and SERVICE for case10 and case14")
    void theDeclaredUnitKindsAreEighteenJobsAndTwoServices() {
        List<String> services = cases().stream()
                .filter(one -> one.unitKind() == UnitKind.SERVICE)
                .map(ParityCase::caseId)
                .toList();
        List<String> jobs = cases().stream()
                .filter(one -> one.unitKind() == UnitKind.BATCH_JOB)
                .map(ParityCase::caseId)
                .toList();

        assertThat(services)
                .describedAs("the two cases that reach CustomerService directly, bypassing the tasklet "
                        + "wiring, are the ones the class documentation names")
                .containsExactly("case10", "case14");
        assertThat(jobs)
                .describedAs("every other case drives the tasklet, so the wiring is exercised by the "
                        + "great majority rather than assumed")
                .hasSize(ParityHarness.CASES_PER_PROGRAM - services.size());
        assertThat(jobs.size() + services.size())
                .describedAs("the two kinds must account for the whole set with nothing unclassified")
                .isEqualTo(ParityHarness.CASES_PER_PROGRAM);
    }

    @Test
    @DisplayName("every case's expected SYSOUT has the shape CBCUS01C's paragraphs produce")
    void expectedLineSequencesHaveTheProgramsShape() {
        for (ParityCase declaredCase : cases()) {
            List<EmittedMessage> messages = declaredCase.expectedMessages();
            String where = PROGRAM + '/' + declaredCase.caseId();

            assertThat(messages)
                    .as("%s: the mainline displays its opening banner at L71 before anything else, so "
                            + "no case can expect fewer than one line", where)
                    .isNotEmpty();
            assertThat(messages.get(0).text())
                    .as("%s: the first line is the L71 banner", where)
                    .isEqualTo(CustomerService.START_OF_EXECUTION);

            List<String> texts = messages.stream().map(EmittedMessage::text).toList();
            if (declaredCase.expectedReturnCode() == CustomerService.RETURN_CODE_NORMAL_END) {
                assertNormalEndShape(where, texts);
            } else {
                assertFatalShape(where, declaredCase, texts);
            }
            assertRecordImagesArePairedAndFiveHundredBytes(where, texts);
        }
    }

    private static void assertNormalEndShape(String where, List<String> texts) {
        assertThat(texts.get(texts.size() - 1))
                .as("%s: a normal end reaches the L85 banner", where)
                .isEqualTo(CustomerService.END_OF_EXECUTION);
        assertThat((texts.size() - CustomerService.BANNER_LINE_COUNT)
                % CustomerService.DISPLAYS_PER_RECORD)
                .as("%s: SYSOUT is the two banners plus %d identical lines per record - the display "
                        + "inside the read's '00' arm at L96 and the mainline's own at L78 - so %d "
                        + "lines cannot be a whole number of records", where,
                        CustomerService.DISPLAYS_PER_RECORD, texts.size())
                .isZero();
        assertThat(texts)
                .as("%s: only the fatal arms display an error literal or the abend banner", where)
                .doesNotContain(CustomerService.ABENDING_PROGRAM)
                .doesNotContainAnyElementsOf(CustomerService.ERROR_TEXTS);
    }

    private static void assertFatalShape(String where, ParityCase declaredCase, List<String> texts) {
        assertThat(declaredCase.expectedReturnCode())
                .as("%s: every fatal arm of this program leaves APPL-RESULT at %d before "
                        + "Z-ABEND-PROGRAM performs CALL 'CEE3ABD' at L158", where,
                        CustomerService.APPL_RESULT_FATAL)
                .isEqualTo(CustomerService.APPL_RESULT_FATAL);
        assertThat(texts)
                .as("%s: the fatal arm is the error literal, the rendered status and the abend "
                        + "banner, in that order, and the run never reaches L85", where)
                .hasSizeGreaterThanOrEqualTo(4)
                .endsWith(CustomerService.ABENDING_PROGRAM)
                .doesNotContain(CustomerService.END_OF_EXECUTION);
        assertThat(texts.get(texts.size() - 2))
                .as("%s: the rendered file status carries the '%s' literal, which is text rather than "
                        + "a placeholder", where, FileStatus.DISPLAY_PREFIX)
                .startsWith(FileStatus.DISPLAY_PREFIX)
                .hasSize(FileStatus.DISPLAY_PREFIX.length() + FileStatus.STATUS_IMAGE_LENGTH);

        String arranged = scenarioFor(declaredCase).failingStatus();
        assertThat(texts.get(texts.size() - 2))
                .as("%s: the rendered status must be the one this case arranges - %s", where,
                        arranged == null ? "nothing, so the backend's own permanent-error status"
                                : "the status its own unitStimulus names")
                .isEqualTo(arranged == null
                        ? PERMANENT_ERROR_LINE
                        : FileStatus.toDisplayLine(arranged));
        assertThat(texts.get(texts.size() - 3))
                .as("%s: the error literal is the one belonging to the paragraph that failed", where)
                .isIn(CustomerService.ERROR_TEXTS);
        assertThat((texts.size() - 4) % CustomerService.DISPLAYS_PER_RECORD)
                .as("%s: a fatal run is the opening banner, %d lines per record already displayed, "
                        + "and the three lines of the fatal arm, so %d lines cannot be a whole "
                        + "number of records", where, CustomerService.DISPLAYS_PER_RECORD,
                        texts.size())
                .isZero();
    }

    private static void assertRecordImagesArePairedAndFiveHundredBytes(String where,
            List<String> texts) {
        List<String> images = new ArrayList<>();
        for (String text : texts) {
            if (text.length() == CustomerRecord.RECORD_LENGTH) {
                images.add(text);
            }
        }
        assertThat(images.size() % CustomerService.DISPLAYS_PER_RECORD)
                .as("%s: every record contributes exactly %d lines, so an odd number of "
                        + "%d-character images means one display was dropped or one was added", where,
                        CustomerService.DISPLAYS_PER_RECORD, CustomerRecord.RECORD_LENGTH)
                .isZero();
        for (int index = 0; index < images.size(); index += CustomerService.DISPLAYS_PER_RECORD) {
            assertThat(images.get(index + 1))
                    .as("%s: record image %d and the line after it are the L96 and L78 displays of "
                            + "one record area, so they are byte-identical", where,
                            index / CustomerService.DISPLAYS_PER_RECORD)
                    .isEqualTo(images.get(index));
        }
    }

    @Test
    @DisplayName("CVCUS01Y is 500 bytes across 19 spans, FILLER included, and keeps its own DOB name")
    void theRecordLayoutAccountsForAllFiveHundredBytes() {
        assertThat(CustomerRecord.RECORD_LENGTH)
                .as("app/cpy/CVCUS01Y.cpy declares CUSTOMER-RECORD as RECLN 500, and every one of its "
                        + "six consumers - CBSTM03B's CUSTFILE FD splits it X(09) plus X(491) - depends "
                        + "on that")
                .isEqualTo(500);

        List<FieldSpan> spans = CustomerRecord.LAYOUT.spans();
        assertThat(spans)
                .as("CVCUS01Y declares 18 named fields and one trailing FILLER")
                .hasSize(19);

        int accounted = 0;
        for (FieldSpan span : spans) {
            assertThat(span.offset())
                    .as("span %s must begin where the one before it ended, or the record has a hole in "
                            + "it and every expectation past that point addresses the wrong bytes",
                            span.name())
                    .isEqualTo(accounted);
            accounted += span.length();
        }
        assertThat(accounted)
                .as("the 19 spans must account for all %d bytes; drop the trailing FILLER and 168 of "
                        + "them go unstated, which is the one omission an offset check cannot see",
                        CustomerRecord.RECORD_LENGTH)
                .isEqualTo(CustomerRecord.RECORD_LENGTH);

        assertThat(CustomerRecord.FILLER.name())
                .as("the differ addresses an unnamed span by this name, which is how case06 pins its "
                        + "168 characters")
                .isEqualTo("FILLER");
        assertThat(CustomerRecord.FILLER.offset()).isEqualTo(332);
        assertThat(CustomerRecord.FILLER.length()).isEqualTo(168);

        assertThat(CustomerRecord.CUST_DOB_YYYY_MM_DD.name())
                .as("app/cpy/CVCUS01Y.cpy spells this span with dashes; app/cpy/CUSTREC.cpy spells the "
                        + "same span CUST-DOB-YYYYMMDD and is modelled separately, so the two names "
                        + "must not converge")
                .isEqualTo("CUST-DOB-YYYY-MM-DD");
        assertThat(CustomerRecord.CUST_DOB_YYYY_MM_DD.offset()).isEqualTo(308);
        assertThat(CustomerRecord.CUST_DOB_YYYY_MM_DD.length()).isEqualTo(10);
    }

    @Test
    @DisplayName("all 50 shipped customer rows are 500 bytes with a blank FILLER")
    void everyShippedRowIsFiveHundredBytesWithABlankFiller() {
        List<String> rows = shippedRows();

        assertThat(rows)
                .as("app/data/ASCII/custdata.txt holds exactly 50 records, and "
                        + "src/test/resources/fixtures/custdata.txt is its byte-for-byte copy")
                .hasSize(50);
        for (int index = 0; index < rows.size(); index++) {
            assertThat(rows.get(index).length())
                    .as("row %d is not %d characters, so it is not a CVCUS01Y record", index,
                            CustomerRecord.RECORD_LENGTH)
                    .isEqualTo(CustomerRecord.RECORD_LENGTH);
            assertThat(rows.get(index).substring(CustomerRecord.FILLER.offset()))
                    .as("row %d's trailing FILLER is not blank; case06 pins 168 spaces there and "
                            + "case07 is the one case that deliberately varies it", index)
                    .isEqualTo(" ".repeat(CustomerRecord.FILLER.length()));
        }
    }

    @Test
    @DisplayName("CVCUS01Y's numeric spans are unsigned zoned, so an overpunch is refused by declaration")
    void theNumericSpansAreUnsignedZonedSoNoRowCarriesAnOverpunch() {
        FixedWidthCodec codec = ParityHarness.usAscii().codec();
        List<FieldSpan> numeric = List.of(CustomerRecord.CUST_ID, CustomerRecord.CUST_SSN,
                CustomerRecord.CUST_FICO_CREDIT_SCORE);

        for (FieldSpan span : numeric) {
            assertThat(span.kind())
                    .as("%s is declared PIC 9 in app/cpy/CVCUS01Y.cpy, never PIC S9, so it reserves no "
                            + "sign position", span.name())
                    .isEqualTo(PictureKind.UNSIGNED_NUMERIC);
        }
        for (String row : shippedRows()) {
            for (FieldSpan span : numeric) {
                String image = row.substring(span.offset(), span.offset() + span.length());
                assertThat(codec.decodePic9(image))
                        .as("%s holds unsigned zoned digits in every shipped row", span.name())
                        .isEqualTo(Long.parseLong(image));
            }
        }

        FieldSpan fico = CustomerRecord.CUST_FICO_CREDIT_SCORE;
        String stored = shippedRows().get(0)
                .substring(fico.offset(), fico.offset() + fico.length());
        String overpunched = stored.substring(0, stored.length() - 1) + '{';

        FixedWidthCodec.SignedZoned asSigned = codec.decodeSignedZoned(overpunched, 0);
        assertThat(asSigned.negative())
                .as("'{' is the positive-zero overpunch, so the codec reads the value as positive")
                .isFalse();
        assertThat(asSigned.signedValue())
                .as("the overpunched trailing byte carries low-order digit 0 and a positive sign, so "
                        + "the three characters denote the leading digits followed by a zero")
                .isEqualByComparingTo(new BigDecimal(stored.substring(0, stored.length() - 1) + '0'));

        assertThatIllegalArgumentException()
                .as("%s is PIC 9(%d); an overpunch byte in it is not a digit and is refused rather "
                        + "than read as a sign, which is the difference between a field that has a "
                        + "sign position and one that does not", fico.name(), fico.length())
                .isThrownBy(() -> codec.decodePic9(overpunched));
    }

    @Test
    @DisplayName("a complete pass issues no WRITE and no REWRITE against the customer master")
    void theProgramNeverWritesToTheCustomerMaster() {
        List<String> rows = shippedRows().subList(0, 3);
        CustomerRepository repository = stubbedCustomerMaster(Scenario.asDeclared(),
                SeededDataset.of(DD_NAME, rows, DATASET_CHARSET), "theProgramNeverWrites");
        List<String> emitted = new ArrayList<>();

        new CustomerService(repository).readAndPrintCustomerFileTo(emitted::add);

        assertThat(emitted)
                .as("three records, each displayed twice, between the two banners")
                .hasSize(CustomerService.expectedSysoutLineCount(rows.size()));
        Mockito.verify(repository, Mockito.never()).rewrite(Mockito.any(byte[].class));
        Mockito.verify(repository, Mockito.never()).rewrite(Mockito.any(CustomerRecord.class));
    }

    private List<String> shippedRows() {
        ParityHarness harness = ParityHarness.usAscii();
        return harness.seed(harness.load(PROGRAM, ParityHarness.caseId(1))).get(DD_NAME).rows();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    @DisplayName("the diff count is zero")
    void parityDiffCountIsZero(ParityCase parityCase) {
        ParityHarness harness = ParityHarness.usAscii();
        DiffResult result = switch (parityCase.unitKind()) {
            case BATCH_JOB -> harness.judge(parityCase, UnitKind.BATCH_JOB, this::runThroughTheJob);
            case SERVICE -> harness.judge(parityCase, UnitKind.SERVICE, this::runThroughTheService);
            case COMPONENT, CONTROLLER_POJO -> throw new IllegalStateException(
                    PROGRAM + '/' + parityCase.caseId() + " declares unitKind "
                            + parityCase.unitKind() + ". This program is a non-CICS batch program: it "
                            + "has no controller and it is called by nothing, so only BATCH_JOB and "
                            + "SERVICE can reach it. " + PROGRAM + " is not CBSTM03B or CSUTLDTC.");
        };

        assertThat(result.count())
                .as("%s", result.render())
                .isZero();
    }

    private UnitOutcome runThroughTheJob(Invocation invocation) throws Exception {
        return run(invocation, true);
    }

    private UnitOutcome runThroughTheService(Invocation invocation) throws Exception {
        return run(invocation, false);
    }

    private UnitOutcome run(Invocation invocation, boolean throughTheJob) throws Exception {
        Scenario scenario = scenarioFrom(invocation.stimulus());
        SeededDataset seeded = invocation.hasDataset(DD_NAME) ? invocation.dataset(DD_NAME) : null;
        UnitOutcome.Builder recorder = invocation.recorder();
        SysoutSink sysout = recorder::display;

        if (scenario.arranged()) {
            runUnit(stubbedCustomerMaster(scenario, seeded, invocation.caseId()), sysout,
                    throughTheJob, recorder);
            return null;
        }

        RecordImageDataSource backend = new RecordImageDataSource();
        JdbcTemplate template = new JdbcTemplate(backend);
        if (seeded != null) {
            declareRelation(backend, seeded.recordLength());
            seedRelation(backend, seeded);
        }
        try {
            runUnit(new CustomerRepository(template, datasetBindings(), DATASET_CHARSET,
                    RecordImageForm.CHARACTER), sysout, throughTheJob, recorder);
        } finally {
            reportFinalState(backend, seeded, recorder);
        }
        return null;
    }

    private void runUnit(CustomerRepository repository, SysoutSink sysout, boolean throughTheJob,
            UnitOutcome.Builder recorder) throws Exception {
        CustomerService service = new CustomerService(repository);
        if (throughTheJob) {
            RepeatStatus status = customerFileReaderJob(service, sysout)
                    .customerFileDisplayTasklet()
                    .execute(stepContribution(), chunkContext());
            if (status != RepeatStatus.FINISHED) {
                throw new IllegalStateException("The " + PROGRAM + " tasklet reported " + status
                        + " rather than " + RepeatStatus.FINISHED + ". app/jcl/READCUST.jcl declares "
                        + "one step running one complete pass over the customer master, so a tasklet "
                        + "that asks to be repeated would read the dataset from its first record "
                        + "again and display every record twice more.");
            }
        } else {
            service.readAndPrintCustomerFileTo(sysout);
        }

        recorder.returnCode(CustomerService.RETURN_CODE_NORMAL_END);
    }

    private void reportFinalState(RecordImageDataSource backend, SeededDataset seeded,
            UnitOutcome.Builder recorder) {
        if (seeded == null) {
            return;
        }
        List<String> stored = new java.util.ArrayList<>(backend.store().rows(TEST_DSNAME));
        stored.sort(java.util.Comparator.naturalOrder());
        recorder.finalState(DD_NAME, CustomerRecord.LAYOUT, stored);
    }

    private void declareRelation(RecordImageDataSource backend, int recordWidth) {
        backend.define(TEST_DSNAME, RECORD_IMAGE_COLUMN, ColumnForm.CHARACTER, recordWidth);
    }

    private void seedRelation(RecordImageDataSource backend, SeededDataset seeded) {
        backend.store().seed(TEST_DSNAME, seeded.rows());
    }

    private CustomerRepository stubbedCustomerMaster(Scenario scenario, SeededDataset seeded,
            String caseId) {
        if (seeded == null) {
            throw new IllegalStateException(PROGRAM + '/' + caseId + " arranges a backend outcome but "
                    + "declares no " + DD_NAME + " input. A case that arranges a status is describing "
                    + "what happens to a dataset it has, so it must declare the dataset - and a case "
                    + "whose point is that there is no dataset at all arranges nothing and meets the "
                    + "real backend instead, which is what case08 does.");
        }

        CustomerRepository repository = Mockito.mock(CustomerRepository.class);
        Mockito.when(repository.datasetCharset()).thenReturn(DATASET_CHARSET);

        CustomerFile custFile = Mockito.mock(CustomerFile.class);
        Mockito.when(repository.openInput()).thenReturn(custFile);
        Mockito.when(custFile.openStatus()).thenReturn(scenario.openStatus());
        if (!FileStatus.isOk(scenario.openStatus())) {
            return repository;
        }

        List<ReadResult> sequence = readSequence(scenario, seeded);
        Mockito.when(custFile.readNext())
                .thenReturn(sequence.get(0),
                        sequence.subList(1, sequence.size()).toArray(ReadResult[]::new));

        AtomicBoolean closed = new AtomicBoolean();
        Mockito.when(custFile.closeFile()).thenAnswer(invocation -> {
            closed.set(true);
            return scenario.closeStatus();
        });
        Mockito.when(custFile.isClosed()).thenAnswer(invocation -> closed.get());
        return repository;
    }

    private List<ReadResult> readSequence(Scenario scenario, SeededDataset seeded) {
        List<String> rows = new ArrayList<>(seeded.rows());
        Collections.sort(rows);

        int delivered = scenario.readFails() ? scenario.failingRead() : rows.size();
        if (delivered > rows.size()) {
            throw new IllegalStateException("The scenario for " + PROGRAM + " asks for " + delivered
                    + " delivered record(s) but only " + rows.size() + " row(s) were seeded. The "
                    + "scenario and the case file's \"inputs\" describe the same run and must agree.");
        }

        List<ReadResult> sequence = new ArrayList<>(delivered + 1);
        for (int index = 0; index < delivered; index++) {
            String image = rows.get(index);
            sequence.add(ReadResult.found(CustomerRecord.decode(image, DATASET_CHARSET), image));
        }

        sequence.add(scenario.readFails()
                ? ReadResult.of(scenario.failingReadStatus())
                : ReadResult.endOfFile());
        return sequence;
    }

    private CustomerFileReaderJob customerFileReaderJob(CustomerService service, SysoutSink sysout) {
        return new CustomerFileReaderJob(batchScaffolding(), service, new DeclaredBean<>(sysout));
    }

    private DatasetBindings datasetBindings() {
        DatasetBinding customer = new DatasetBinding(TEST_DSNAME, DatasetBinding.KSDS, false, "FB",
                null, CustomerRecord.RECORD_LENGTH, "CVCUS01Y", CustomerRepository.KEY_LENGTH, null,
                null, null);
        DatasetBindings catalogue = new DatasetBindings();
        catalogue.put(CustomerRepository.CICS_FILE_NAME, customer);
        catalogue.put(DD_NAME, customer);
        return catalogue;
    }

    private BatchConfig batchScaffolding() {
        JobContracts contracts = new JobContracts();
        contracts.put(CustomerFileReaderJob.JOB_KEY, new JobContract(PROGRAM, List.of(),
                CustomerFileReaderJob.REQUIRED_STEPS, null, Map.of()));
        return new BatchConfig(new DeclaredBean<>(Mockito.mock(JobRepository.class)),
                new DeclaredBean<>(Mockito.mock(PlatformTransactionManager.class)), contracts,
                datasetBindings());
    }

    private StepContribution stepContribution() {
        return new StepContribution(new StepExecution(CustomerService.STEP_NAME, new JobExecution(1L)));
    }

    private ChunkContext chunkContext() {
        return new ChunkContext(new StepContext(
                new StepExecution(CustomerService.STEP_NAME, new JobExecution(2L))));
    }

    private record DeclaredBean<T>(T bean) implements ObjectProvider<T> {
        @Override
        public T getObject() {
            return bean;
        }
    }
}
