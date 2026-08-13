package com.vsergeychik.carddemo.customer;

import com.vsergeychik.carddemo.common.AbendException;
import com.vsergeychik.carddemo.common.FileStatus;
import com.vsergeychik.carddemo.common.RecordImageForm;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBinding;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBindings;
import com.vsergeychik.carddemo.customer.CustomerRepository.CustomerFile;
import com.vsergeychik.carddemo.customer.CustomerRepository.ReadResult;
import com.vsergeychik.carddemo.customer.CustomerService.Execution;
import com.vsergeychik.carddemo.customer.CustomerService.PrintStreamSysoutSink;
import com.vsergeychik.carddemo.customer.CustomerService.Sysout;
import com.vsergeychik.carddemo.customer.CustomerService.SysoutSink;
import com.vsergeychik.carddemo.customer.CustomerService.WorkingStorage;
import com.vsergeychik.carddemo.customer.model.CustomerRecord;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;
import org.mockito.Mockito;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * Unit tests for {@link CustomerService}, the Java form of {@code app/cbl/CBCUS01C.cbl}.
 */
@DisplayName("CustomerService - CBCUS01C, read and print the customer data file")
class CustomerServiceTest {
    private static final Charset ASCII = StandardCharsets.US_ASCII;

    private static final Charset EBCDIC = Charset.forName("IBM037");

    private static final String TEST_DSNAME = "TEST.CUSTOMER.KSDS";

    private static final String RECORD_IMAGE_COLUMN = "REC";

    private static final String FIXTURE = "/fixtures/custdata.txt";

    private static final int FIXTURE_RECORDS = 50;

    private static final int FIXTURE_LINE_COUNT = 102;

    private static final int FIVE_HUNDRED = CustomerRecord.RECORD_LENGTH;

    private static final int NINE = CustomerRepository.KEY_LENGTH;

    private static final int FILLER_FIRST_BYTE = 333;

    private static final AtomicInteger DATABASE_SEQUENCE = new AtomicInteger();

    private static DatasetBindings bindings() {
        DatasetBindings catalogue = new DatasetBindings();
        catalogue.put(CustomerRepository.CICS_FILE_NAME, new DatasetBinding(TEST_DSNAME, "ksds", false,
                "FB", null, FIVE_HUNDRED, "CVCUS01Y", NINE, null, null, null));
        catalogue.put(CustomerRepository.BATCH_DD_NAME, new DatasetBinding(TEST_DSNAME, "ksds", false,
                "FB", null, FIVE_HUNDRED, "CVCUS01Y", NINE, null, null, null));
        return catalogue;
    }

    private static CustomerRepository repository(JdbcTemplate template) {
        return new CustomerRepository(template, bindings(), ASCII, RecordImageForm.CHARACTER);
    }

    private static CustomerService service(JdbcTemplate template) {
        return new CustomerService(repository(template));
    }

    private static JdbcTemplate emptyDatabase() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:custsvc" + DATABASE_SEQUENCE.incrementAndGet()
                        + ";DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE", "sa", "");
        dataSource.setDriverClassName("org.h2.Driver");
        return new JdbcTemplate(dataSource);
    }

    private static JdbcTemplate seeded(List<String> rows) {
        JdbcTemplate template = emptyDatabase();
        template.execute("CREATE TABLE \"" + TEST_DSNAME + "\" (" + RECORD_IMAGE_COLUMN
                + " VARCHAR(" + FIVE_HUNDRED + "))");
        for (String row : rows) {
            template.update("INSERT INTO \"" + TEST_DSNAME + "\" VALUES (?)", row);
        }
        return template;
    }

    @SuppressWarnings("unchecked")
    private static JdbcTemplate refusingTheFirstDescribe(JdbcTemplate real) {
        JdbcTemplate spy = Mockito.spy(real);
        Mockito.doThrow(new DataAccessResourceFailureException(
                        "the customer master dataset is not addressable"))
                .when(spy).query(Mockito.anyString(), Mockito.any(ResultSetExtractor.class));
        return spy;
    }

    @SuppressWarnings("unchecked")
    private static JdbcTemplate refusingTheSecondDescribe(JdbcTemplate real) {
        JdbcTemplate spy = Mockito.spy(real);
        Mockito.doCallRealMethod()
                .doThrow(new DataAccessResourceFailureException(
                        "the customer master dataset is no longer addressable"))
                .when(spy).query(Mockito.anyString(), Mockito.any(ResultSetExtractor.class));
        return spy;
    }

    private static List<String> fixtureRows() {
        try (InputStream stream = CustomerServiceTest.class.getResourceAsStream(FIXTURE)) {
            if (stream == null) {
                throw new IllegalStateException("The customer fixture " + FIXTURE + " is absent from the "
                        + "test classpath; every expectation in this class is seeded from it");
            }
            return new String(stream.readAllBytes(), ASCII).lines().toList();
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private static CustomerService serviceReporting(String... statuses) {
        CustomerRepository repository = Mockito.mock(CustomerRepository.class);
        CustomerFile file = Mockito.mock(CustomerFile.class);
        Mockito.when(repository.datasetCharset()).thenReturn(ASCII);
        Mockito.when(repository.openInput()).thenReturn(file);
        Mockito.when(file.openStatus()).thenReturn(FileStatus.OK);
        Mockito.when(file.closeFile()).thenReturn(FileStatus.OK);

        CustomerRepository.ReadResult first = CustomerRepository.ReadResult.of(statuses[0]);
        CustomerRepository.ReadResult[] rest =
                new CustomerRepository.ReadResult[Math.max(statuses.length - 1, 0)];
        for (int index = 1; index < statuses.length; index++) {
            rest[index - 1] = CustomerRepository.ReadResult.of(statuses[index]);
        }
        Mockito.when(file.readNext()).thenReturn(first, rest);
        return new CustomerService(repository);
    }

    private record StubbedRun(CustomerService subject, CustomerRepository repository, CustomerFile file) {
    }

    private static CustomerRepository stubbedRepository() {
        CustomerRepository repository = Mockito.mock(CustomerRepository.class);
        Mockito.when(repository.datasetCharset()).thenReturn(ASCII);
        return repository;
    }

    private static StubbedRun stubbedRun(String openStatus, String closeStatus, ReadResult... reads) {
        CustomerRepository repository = stubbedRepository();
        CustomerFile file = Mockito.mock(CustomerFile.class);
        AtomicBoolean closed = new AtomicBoolean();

        Mockito.when(repository.openInput()).thenReturn(file);
        Mockito.when(file.openStatus()).thenReturn(openStatus);
        Mockito.when(file.datasetName()).thenReturn(TEST_DSNAME);
        Mockito.when(file.isClosed()).thenAnswer(invocation -> closed.get());
        Mockito.when(file.closeFile()).thenAnswer(invocation -> {
            closed.set(true);
            return closeStatus;
        });

        ReadResult[] sequence = reads.length == 0 ? new ReadResult[] {ReadResult.endOfFile()} : reads;
        Mockito.when(file.readNext())
                .thenReturn(sequence[0], Arrays.copyOfRange(sequence, 1, sequence.length));

        return new StubbedRun(new CustomerService(repository), repository, file);
    }

    private static StubbedRun stubbedRunOver(int records) {
        List<ReadResult> sequence = new ArrayList<>();
        for (String row : fixtureRows().subList(0, records)) {
            sequence.add(foundRow(row));
        }
        sequence.add(ReadResult.endOfFile());
        return stubbedRun(FileStatus.OK, FileStatus.OK, sequence.toArray(new ReadResult[0]));
    }

    private static ReadResult foundRow(String image) {
        return ReadResult.found(CustomerRecord.decode(image, ASCII), image);
    }

    private static AbendException runExpectingAbend(CustomerService subject, Sysout sink) {
        return assertThatExceptionOfType(AbendException.class)
                .isThrownBy(() -> subject.readAndPrintCustomerFile(sink))
                .actual();
    }

    private static Path moduleFile(String relativePath) {
        Path candidate = Path.of("").toAbsolutePath();
        while (candidate != null) {
            Path resolved = candidate.resolve(relativePath);
            if (Files.exists(resolved)) {
                return resolved;
            }
            candidate = candidate.getParent();
        }
        throw new IllegalStateException("Could not find " + relativePath + " at or above "
                + Path.of("").toAbsolutePath());
    }

    private static String moduleSource(String relativePath) {
        if (!relativePath.startsWith("app/java/")) {
            throw new IllegalArgumentException("Only this module's own sources are read by these scans; "
                    + "the COBOL, copybook, JCL, CSD and data trees are read-only reference material and "
                    + "are never opened from a test (practice B3): " + relativePath);
        }
        try {
            return Files.readString(moduleFile(relativePath), StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            throw new UncheckedIOException("Could not read " + relativePath, unreadable);
        }
    }

    @Nested
    @DisplayName("The six SYSOUT literals")
    class Literals {
        @Test
        @DisplayName("the two banners are the source's own, character for character - L71 and L85")
        void banners() {
            assertThat(CustomerService.START_OF_EXECUTION)
                    .isEqualTo("START OF EXECUTION OF PROGRAM CBCUS01C");
            assertThat(CustomerService.END_OF_EXECUTION)
                    .isEqualTo("END OF EXECUTION OF PROGRAM CBCUS01C");
        }

        @Test
        @DisplayName("the open text names the DD name while the read and close texts name the file")
        void theErrorTextAsymmetryIsPreserved() {
            assertThat(CustomerService.ERROR_OPENING_CUSTFILE).isEqualTo("ERROR OPENING CUSTFILE");
            assertThat(CustomerService.ERROR_READING_CUSTOMER_FILE)
                    .isEqualTo("ERROR READING CUSTOMER FILE");
            assertThat(CustomerService.ERROR_CLOSING_CUSTOMER_FILE)
                    .isEqualTo("ERROR CLOSING CUSTOMER FILE");
            assertThat(CustomerService.ERROR_OPENING_CUSTFILE).doesNotContain("CUSTOMER FILE");
        }

        @Test
        @DisplayName("the three error texts are distinct, so a failure names its own paragraph")
        void theThreeErrorTextsAreDistinct() {
            assertThat(CustomerService.ERROR_TEXTS).hasSize(3).doesNotHaveDuplicates();
        }

        @Test
        @DisplayName("the abend banner is the shared literal, not a local copy - L155")
        void abendBanner() {
            assertThat(CustomerService.ABENDING_PROGRAM).isEqualTo("ABENDING PROGRAM");
            assertThat(CustomerService.ABENDING_PROGRAM).isSameAs(AbendException.ABEND_DISPLAY_TEXT);
        }

        @Test
        @DisplayName("the program is identified as CBCUS01C, invoked by READCUST.jcl STEP05")
        void identity() {
            assertThat(CustomerService.PROGRAM_ID).isEqualTo("CBCUS01C");
            assertThat(CustomerService.STEP_NAME).isEqualTo("STEP05");
            assertThat(CustomerService.DD_NAME).isEqualTo("CUSTFILE");
        }
    }

    @Nested
    @DisplayName("Output geometry - two displays per record, not one")
    class Geometry {
        @Test
        @DisplayName("a record produces TWO lines, because L96 and L78 both display it")
        void twoDisplaysPerRecord() {
            assertThat(CustomerService.DISPLAYS_PER_RECORD).isEqualTo(2);
            assertThat(CustomerService.BANNER_LINE_COUNT).isEqualTo(2);
        }

        @Test
        @DisplayName("fifty records give 102 lines - the headline parity fact")
        void fiftyRecordsGiveOneHundredAndTwoLines() {
            assertThat(CustomerService.expectedSysoutLineCount(FIXTURE_RECORDS))
                    .isEqualTo(FIXTURE_LINE_COUNT);
        }

        @Test
        @DisplayName("an empty dataset gives the two banners and nothing else")
        void zeroRecordsGiveTwoLines() {
            assertThat(CustomerService.expectedSysoutLineCount(0)).isEqualTo(2);
        }

        @ParameterizedTest
        @ValueSource(ints = {0, 1, 2, 7, 50, 300})
        @DisplayName("the count is always two banners plus two lines per record")
        void theArithmeticHoldsAtEveryCount(int records) {
            assertThat(CustomerService.expectedSysoutLineCount(records)).isEqualTo(2 + 2 * records);
        }

        @Test
        @DisplayName("a negative record count is refused - a browse returns zero, never fewer")
        void negativeCountIsRefused() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> CustomerService.expectedSysoutLineCount(-1))
                    .withMessageContaining("cannot be negative");
        }

        @Test
        @DisplayName("the declared record width is the copybook's 500, FILLER included")
        void recordWidth() {
            assertThat(CustomerService.RECORD_LENGTH).isEqualTo(500);
            assertThat(CustomerService.RECORD_LENGTH).isEqualTo(CustomerRecord.RECORD_LENGTH);
        }
    }

    @Nested
    @DisplayName("Construction - a repository and nothing else")
    class Construction {
        @Test
        @DisplayName("a null repository is refused")
        void nullRepositoryIsRefused() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new CustomerService(null))
                    .withMessageContaining("CustomerRepository is required");
        }

        @Test
        @DisplayName("the subject needs no context, no launcher and no HTTP - gate G51")
        void constructibleWithNothingButARepository() {
            assertThat(service(seeded(fixtureRows()))).isNotNull();
        }

        @Test
        @DisplayName("a null sink is refused by the overload that accepts one")
        void nullSinkIsRefused() {
            CustomerService subject = service(seeded(fixtureRows()));
            assertThatNullPointerException()
                    .isThrownBy(() -> subject.readAndPrintCustomerFile(null))
                    .withMessageContaining("SYSOUT sink is required");
        }
    }

    @Nested
    @DisplayName("A clean run over the fifty-record fixture")
    class CleanRun {
        @Test
        @DisplayName("emits exactly 102 lines - 1 banner + 50 x 2 images + 1 banner")
        void oneHundredAndTwoLines() {
            Execution execution = service(seeded(fixtureRows())).readAndPrintCustomerFile();

            assertThat(execution.lineCount()).isEqualTo(FIXTURE_LINE_COUNT);
            assertThat(execution.sysout()).hasSize(FIXTURE_LINE_COUNT);
            assertThat(execution.recordsRead()).isEqualTo(FIXTURE_RECORDS);
            assertThat(execution.returnCode()).isEqualTo(CustomerService.RETURN_CODE_NORMAL_END);
        }

        @Test
        @DisplayName("the first line is the opening banner and the last is the closing one")
        void bannersBookendTheOutput() {
            List<String> sysout = service(seeded(fixtureRows())).readAndPrintCustomerFile().sysout();

            assertThat(sysout).first().isEqualTo(CustomerService.START_OF_EXECUTION);
            assertThat(sysout).last().isEqualTo(CustomerService.END_OF_EXECUTION);
        }

        @Test
        @DisplayName("each record appears TWICE, on two ADJACENT byte-identical lines - L96 then L78")
        void everyRecordIsDisplayedTwiceAndAdjacently() {
            List<String> records = service(seeded(fixtureRows()))
                    .readAndPrintCustomerFile().recordLines();

            assertThat(records).hasSize(FIXTURE_RECORDS * CustomerService.DISPLAYS_PER_RECORD);
            for (int pair = 0; pair < FIXTURE_RECORDS; pair++) {
                int first = pair * 2;
                assertThat(records.get(first))
                        .as("record %d's two displays must be byte-identical and adjacent", pair)
                        .isEqualTo(records.get(first + 1));
            }
        }

        @Test
        @DisplayName("the pairs are the fixture's own images, in ascending key order")
        void thePairsAreTheFixtureImagesInKeyOrder() {
            List<String> expected = fixtureRows();
            List<String> records = service(seeded(fixtureRows()))
                    .readAndPrintCustomerFile().recordLines();

            for (int index = 0; index < expected.size(); index++) {
                assertThat(records.get(index * 2))
                        .as("the image at browse position %d", index)
                        .isEqualTo(expected.get(index));
            }
        }

        @Test
        @DisplayName("every record line is exactly 500 characters - gate G21, the FILLER is emitted")
        void everyRecordLineIsFiveHundredCharacters() {
            List<String> records = service(seeded(fixtureRows()))
                    .readAndPrintCustomerFile().recordLines();

            assertThat(records).isNotEmpty().allSatisfy(line -> assertThat(line).hasSize(FIVE_HUNDRED));
        }

        @Test
        @DisplayName("bytes 333 to 500 of every record line are the FILLER X(168) spaces")
        void theTrailingFillerIsSpaceFilled() {
            List<String> records = service(seeded(fixtureRows()))
                    .readAndPrintCustomerFile().recordLines();

            assertThat(records).allSatisfy(line -> {
                String filler = line.substring(FILLER_FIRST_BYTE - 1);
                assertThat(filler).hasSize(168);
                assertThat(filler.chars()).allMatch(character -> character == ' ');
            });
        }

        @Test
        @DisplayName("a row whose FILLER X(168) is not spaces is displayed twice, as it stands")
        void aRowsFillerSurvivesBothDisplays() {
            String clean = fixtureRows().get(0);
            String dirty = clean.substring(0, FILLER_FIRST_BYTE - 1) + "*".repeat(168);
            assertThat(dirty).hasSize(FIVE_HUNDRED).isNotEqualTo(clean);

            List<String> records = service(seeded(List.of(dirty)))
                    .readAndPrintCustomerFile().recordLines();

            assertThat(records).hasSize(CustomerService.DISPLAYS_PER_RECORD);
            assertThat(records).allSatisfy(line -> assertThat(line)
                    .as("the row's own 500 bytes, FILLER included")
                    .hasSize(FIVE_HUNDRED)
                    .isEqualTo(dirty)
                    .isNotEqualTo(clean));
        }

        @Test
        @DisplayName("the image is the raw group, not a labelled rendering - CBCUS01C has no such paragraph")
        void theImageIsTheRawGroupAndNotLabelled() {
            List<String> records = service(seeded(fixtureRows()))
                    .readAndPrintCustomerFile().recordLines();

            assertThat(records).allSatisfy(line -> {
                assertThat(line).doesNotContain("CUST-ID");
                assertThat(line).doesNotContain(":");
                assertThat(line).doesNotContain("---");
            });
        }

        @Test
        @DisplayName("an empty dataset emits the two banners, reads nothing and does not abend")
        void anEmptyDatasetEmitsOnlyTheBanners() {
            Execution execution = service(seeded(List.of())).readAndPrintCustomerFile();

            assertThat(execution.sysout()).containsExactly(
                    CustomerService.START_OF_EXECUTION,
                    CustomerService.END_OF_EXECUTION);
            assertThat(execution.recordsRead()).isZero();
            assertThat(execution.recordLines()).isEmpty();
        }

        @Test
        @DisplayName("the returned line list is immutable, so a fingerprint cannot be edited after the fact")
        void theReturnedListIsImmutable() {
            List<String> sysout = service(seeded(fixtureRows())).readAndPrintCustomerFile().sysout();

            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> sysout.add("injected"));
        }

        @Test
        @DisplayName("two runs on one bean agree exactly - no state survives a call")
        void theServiceIsStateless() {
            CustomerService subject = service(seeded(fixtureRows()));

            Execution first = subject.readAndPrintCustomerFile();
            Execution second = subject.readAndPrintCustomerFile();

            assertThat(second.sysout()).containsExactlyElementsOf(first.sysout());
            assertThat(second.recordsRead()).isEqualTo(first.recordsRead());
        }

        @Test
        @DisplayName("a caller-supplied sink receives the same lines the return value carries")
        void theSuppliedSinkAgreesWithTheReturnValue() {
            Sysout sink = new Sysout();
            Execution execution = service(seeded(fixtureRows())).readAndPrintCustomerFile(sink);

            assertThat(sink.lines()).containsExactlyElementsOf(execution.sysout());
            assertThat(sink.lineCount()).isEqualTo(FIXTURE_LINE_COUNT);
            assertThat(sink.recordImageCount())
                    .isEqualTo(FIXTURE_RECORDS * CustomerService.DISPLAYS_PER_RECORD);
        }
    }

    @Nested
    @DisplayName("A failed open - 0000-CUSTFILE-OPEN, L118-L134")
    class OpenFailure {
        @Test
        @DisplayName("abends with RETURN-CODE 12, ABCODE 999 and TIMING 0 - gate G35")
        void abendCarriesTheCeeThreeAbdArguments() {
            CustomerService subject = service(refusingTheFirstDescribe(seeded(fixtureRows())));

            AbendException abend = assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(subject::readAndPrintCustomerFile)
                    .actual();

            assertThat(abend.getProgram()).isEqualTo(CustomerService.PROGRAM_ID);
            assertThat(abend.getReturnCode()).isEqualTo(CustomerService.APPL_RESULT_FATAL);
            assertThat(abend.getReturnCode()).isEqualTo(12);
            assertThat(abend.getAbendCode()).hasValue(AbendException.STANDARD_ABEND_CODE);
            assertThat(abend.getAbendCode()).hasValue(999);
            assertThat(abend.getTiming()).hasValue(AbendException.STANDARD_TIMING);
            assertThat(abend.getTiming()).hasValue(0);
        }

        @Test
        @DisplayName("emits banner, then the error text, then the status line, then ABENDING PROGRAM")
        void theEmissionOrderIsTheParagraphsOwn() {
            CustomerService subject = service(refusingTheFirstDescribe(seeded(fixtureRows())));
            Sysout sink = new Sysout();

            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> subject.readAndPrintCustomerFile(sink));

            assertThat(sink.lines()).containsExactly(
                    CustomerService.START_OF_EXECUTION,
                    CustomerService.ERROR_OPENING_CUSTFILE,
                    FileStatus.toDisplayLine(CustomerRepository.PERMANENT_ERROR_STATUS),
                    CustomerService.ABENDING_PROGRAM);
        }

        @Test
        @DisplayName("no record is displayed and no closing banner is emitted")
        void theRunStopsInsideTheOpenParagraph() {
            CustomerService subject = service(refusingTheFirstDescribe(seeded(fixtureRows())));
            Sysout sink = new Sysout();

            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> subject.readAndPrintCustomerFile(sink));

            assertThat(sink.recordImageCount()).isZero();
            assertThat(sink.lines()).doesNotContain(CustomerService.END_OF_EXECUTION);
        }

        @Test
        @DisplayName("the rendered status line carries the literal NNNN and then the four-digit image")
        void theStatusLineReproducesTheDisplayQuirk() {
            CustomerService subject = service(refusingTheFirstDescribe(seeded(fixtureRows())));
            Sysout sink = new Sysout();

            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> subject.readAndPrintCustomerFile(sink));

            String statusLine = sink.lines().get(2);
            assertThat(statusLine).startsWith(FileStatus.DISPLAY_PREFIX);
            assertThat(statusLine).hasSize(FileStatus.DISPLAY_PREFIX.length()
                    + FileStatus.STATUS_IMAGE_LENGTH);
            assertThat(statusLine).isEqualTo(FileStatus.DISPLAY_PREFIX + "9000");
        }

        @Test
        @DisplayName("the abend's reason names the failing paragraph and carries the rendered status")
        void theAbendReasonIsDiagnosable() {
            CustomerService subject = service(refusingTheFirstDescribe(seeded(fixtureRows())));

            AbendException abend = assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(subject::readAndPrintCustomerFile)
                    .actual();

            assertThat(abend.getReason()).isPresent();
            assertThat(abend.getReason().orElseThrow())
                    .contains(CustomerService.ERROR_OPENING_CUSTFILE)
                    .contains(FileStatus.DISPLAY_PREFIX);
        }
    }

    @Nested
    @DisplayName("A failed read - 1000-CUSTFILE-GET-NEXT, L92-L116")
    class ReadFailure {
        private List<String> rowsWithAnAbsentImage() {
            List<String> rows = new ArrayList<>();
            rows.add(null);
            rows.addAll(fixtureRows());
            return rows;
        }

        @Test
        @DisplayName("abends with RETURN-CODE 12, ABCODE 999 and TIMING 0 - gate G35")
        void abendCarriesTheCeeThreeAbdArguments() {
            CustomerService subject = service(seeded(rowsWithAnAbsentImage()));

            AbendException abend = assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(subject::readAndPrintCustomerFile)
                    .actual();

            assertThat(abend.getProgram()).isEqualTo(CustomerService.PROGRAM_ID);
            assertThat(abend.getReturnCode()).isEqualTo(12);
            assertThat(abend.getAbendCode()).hasValue(999);
            assertThat(abend.getTiming()).hasValue(0);
        }

        @Test
        @DisplayName("emits banner, then the read error text, then the status line, then ABENDING PROGRAM")
        void theEmissionOrderIsTheParagraphsOwn() {
            CustomerService subject = service(seeded(rowsWithAnAbsentImage()));
            Sysout sink = new Sysout();

            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> subject.readAndPrintCustomerFile(sink));

            assertThat(sink.lines()).containsExactly(
                    CustomerService.START_OF_EXECUTION,
                    CustomerService.ERROR_READING_CUSTOMER_FILE,
                    FileStatus.toDisplayLine(CustomerRepository.PERMANENT_ERROR_STATUS),
                    CustomerService.ABENDING_PROGRAM);
        }

        @Test
        @DisplayName("the read text is used, not the open or close text")
        void theRightParagraphOwnsTheMessage() {
            CustomerService subject = service(seeded(rowsWithAnAbsentImage()));
            Sysout sink = new Sysout();

            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> subject.readAndPrintCustomerFile(sink));

            assertThat(sink.lines())
                    .doesNotContain(CustomerService.ERROR_OPENING_CUSTFILE)
                    .doesNotContain(CustomerService.ERROR_CLOSING_CUSTOMER_FILE)
                    .doesNotContain(CustomerService.END_OF_EXECUTION);
        }
    }

    @Nested
    @DisplayName("A failed close - 9000-CUSTFILE-CLOSE, L136-L152")
    class CloseFailure {
        @Test
        @DisplayName("abends with RETURN-CODE 12, ABCODE 999 and TIMING 0 - gate G35")
        void abendCarriesTheCeeThreeAbdArguments() {
            CustomerService subject = service(refusingTheSecondDescribe(seeded(fixtureRows())));

            AbendException abend = assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(subject::readAndPrintCustomerFile)
                    .actual();

            assertThat(abend.getProgram()).isEqualTo(CustomerService.PROGRAM_ID);
            assertThat(abend.getReturnCode()).isEqualTo(12);
            assertThat(abend.getAbendCode()).hasValue(999);
            assertThat(abend.getTiming()).hasValue(0);
        }

        @Test
        @DisplayName("the whole browse is emitted first, then the close's three lines")
        void theBrowseSurvivesAndTheCloseFails() {
            CustomerService subject = service(refusingTheSecondDescribe(seeded(fixtureRows())));
            Sysout sink = new Sysout();

            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> subject.readAndPrintCustomerFile(sink));

            List<String> lines = sink.lines();
            assertThat(lines).first().isEqualTo(CustomerService.START_OF_EXECUTION);
            assertThat(sink.recordImageCount())
                    .isEqualTo(FIXTURE_RECORDS * CustomerService.DISPLAYS_PER_RECORD);
            assertThat(lines.subList(lines.size() - 3, lines.size())).containsExactly(
                    CustomerService.ERROR_CLOSING_CUSTOMER_FILE,
                    FileStatus.toDisplayLine(CustomerRepository.PERMANENT_ERROR_STATUS),
                    CustomerService.ABENDING_PROGRAM);
            assertThat(lines).doesNotContain(CustomerService.END_OF_EXECUTION);
        }
    }

    @Nested
    @DisplayName("Handle release - silent, idempotent, and only what the open acquired")
    class HandleRelease {
        @Test
        @DisplayName("a fatal read releases the browse without displaying anything extra")
        void aFatalReadReleasesTheBrowse() {
            CustomerRepository repository = Mockito.mock(CustomerRepository.class);
            CustomerFile file = Mockito.mock(CustomerFile.class);
            Mockito.when(repository.datasetCharset()).thenReturn(ASCII);
            Mockito.when(repository.openInput()).thenReturn(file);
            Mockito.when(file.openStatus()).thenReturn(FileStatus.OK);
            Mockito.when(file.closeFile()).thenReturn(FileStatus.OK);
            Mockito.when(file.readNext()).thenReturn(CustomerRepository.ReadResult.of("35"));
            Sysout sink = new Sysout();

            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> new CustomerService(repository).readAndPrintCustomerFile(sink));

            Mockito.verify(file).closeFile();
            assertThat(sink.lines())
                    .as("the release is not a second CLOSE: no close message, no END banner")
                    .doesNotContain(CustomerService.ERROR_CLOSING_CUSTOMER_FILE,
                            CustomerService.END_OF_EXECUTION);
        }

        @Test
        @DisplayName("a clean run closes exactly once - the release finds the file already closed")
        void aCleanRunClosesOnce() {
            CustomerRepository repository = Mockito.mock(CustomerRepository.class);
            CustomerFile file = Mockito.mock(CustomerFile.class);
            Mockito.when(repository.datasetCharset()).thenReturn(ASCII);
            Mockito.when(repository.openInput()).thenReturn(file);
            Mockito.when(file.openStatus()).thenReturn(FileStatus.OK);
            Mockito.when(file.readNext())
                    .thenReturn(CustomerRepository.ReadResult.of(FileStatus.END_OF_FILE));
            Mockito.when(file.closeFile()).thenAnswer(invocation -> {
                Mockito.when(file.isClosed()).thenReturn(true);
                return FileStatus.OK;
            });

            Execution execution = new CustomerService(repository).readAndPrintCustomerFile();

            Mockito.verify(file).closeFile();
            assertThat(execution.recordsRead()).isZero();
            assertThat(execution.sysout()).containsExactly(CustomerService.START_OF_EXECUTION,
                    CustomerService.END_OF_EXECUTION);
        }

        @Test
        @DisplayName("a failed open leaves nothing to release, and no close is attempted")
        void aFailedOpenReleasesNothing() {
            CustomerRepository repository = Mockito.mock(CustomerRepository.class);
            CustomerFile file = Mockito.mock(CustomerFile.class);
            Mockito.when(repository.datasetCharset()).thenReturn(ASCII);
            Mockito.when(repository.openInput()).thenReturn(file);
            Mockito.when(file.openStatus()).thenReturn("39");

            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> new CustomerService(repository).readAndPrintCustomerFile());

            Mockito.verify(file, Mockito.never()).closeFile();
        }

        @Test
        @DisplayName("a release that itself fails does not replace the abend the caller needs")
        void aFailingReleaseDoesNotMaskTheAbend() {
            CustomerRepository repository = Mockito.mock(CustomerRepository.class);
            CustomerFile file = Mockito.mock(CustomerFile.class);
            Mockito.when(repository.datasetCharset()).thenReturn(ASCII);
            Mockito.when(repository.openInput()).thenReturn(file);
            Mockito.when(file.openStatus()).thenReturn(FileStatus.OK);
            Mockito.when(file.readNext()).thenReturn(CustomerRepository.ReadResult.of("35"));
            Mockito.when(file.closeFile())
                    .thenThrow(new DataAccessResourceFailureException("the gateway dropped the browse"));

            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> new CustomerService(repository).readAndPrintCustomerFile())
                    .satisfies(abend -> assertThat(abend.getReturnCode()).isEqualTo(12));
        }
    }

    @Nested
    @DisplayName("The APPL-RESULT ladder over every file status - gate G47")
    class StatusLadder {
        @Test
        @DisplayName("'00' reads a record, moves 0, and continues")
        void okReadsAndContinues() {
            Execution execution = service(seeded(fixtureRows())).readAndPrintCustomerFile();

            assertThat(execution.recordsRead()).isEqualTo(FIXTURE_RECORDS);
        }

        @Test
        @DisplayName("'10' moves 16, sets END-OF-FILE and does NOT abend - L98-L99, L107-L108")
        void endOfFileStopsTheLoopWithoutAbending() {
            Execution execution = serviceReporting(FileStatus.END_OF_FILE).readAndPrintCustomerFile();

            assertThat(execution.recordsRead()).isZero();
            assertThat(execution.sysout()).containsExactly(
                    CustomerService.START_OF_EXECUTION,
                    CustomerService.END_OF_EXECUTION);
            assertThat(execution.returnCode()).isEqualTo(0);
        }

        @ParameterizedTest
        @ValueSource(strings = {"22", "23", "35", "37", "39", "47", "90"})
        @DisplayName("every other status routes through the single WHEN OTHER arm and abends - L101")
        void everyOtherStatusAbends(String status) {
            CustomerService subject = serviceReporting(status);
            Sysout sink = new Sysout();

            AbendException abend = assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> subject.readAndPrintCustomerFile(sink))
                    .actual();

            assertThat(abend.getReturnCode()).isEqualTo(CustomerService.APPL_RESULT_FATAL);
            assertThat(sink.lines()).containsExactly(
                    CustomerService.START_OF_EXECUTION,
                    CustomerService.ERROR_READING_CUSTOMER_FILE,
                    FileStatus.toDisplayLine(status),
                    CustomerService.ABENDING_PROGRAM);
        }

        @Test
        @DisplayName("'22' and '23' are handled identically - the ladder gives neither a special case")
        void duplicateAndNotFoundAreNotSpecialCased() {
            Sysout duplicate = new Sysout();
            Sysout notFound = new Sysout();

            assertThatExceptionOfType(AbendException.class).isThrownBy(() ->
                    serviceReporting(FileStatus.DUPLICATE).readAndPrintCustomerFile(duplicate));
            assertThatExceptionOfType(AbendException.class).isThrownBy(() ->
                    serviceReporting(FileStatus.NOT_FOUND).readAndPrintCustomerFile(notFound));

            assertThat(duplicate.lines()).hasSameSizeAs(notFound.lines());
            assertThat(duplicate.lines().get(1)).isEqualTo(notFound.lines().get(1));
            assertThat(duplicate.lines().get(3)).isEqualTo(notFound.lines().get(3));
        }

        @Test
        @DisplayName("a run reaches end of file after its records, not instead of them")
        void aFiniteBrowseEndsAtEndOfFile() {
            CustomerService subject = serviceReporting(FileStatus.END_OF_FILE, FileStatus.END_OF_FILE);

            Execution execution = subject.readAndPrintCustomerFile();

            assertThat(execution.lineCount()).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("The two 88-level condition names, both states each - gate G50")
    class ConditionNames {
        @Test
        @DisplayName("88 APPL-AOK VALUE 0 is true only for zero")
        void applAokIsTrueOnlyForZero() {
            assertThat(CustomerService.isApplAok(0)).isTrue();
            assertThat(CustomerService.isApplAok(CustomerService.APPL_AOK)).isTrue();
        }

        @ParameterizedTest
        @ValueSource(ints = {-1, 4, 8, 12, 16, 999})
        @DisplayName("88 APPL-AOK is false for every other value, 16 and 12 included")
        void applAokIsFalseOtherwise(int value) {
            assertThat(CustomerService.isApplAok(value)).isFalse();
        }

        @Test
        @DisplayName("88 APPL-EOF VALUE 16 is true only for sixteen")
        void applEofIsTrueOnlyForSixteen() {
            assertThat(CustomerService.isApplEof(16)).isTrue();
            assertThat(CustomerService.isApplEof(CustomerService.APPL_EOF)).isTrue();
        }

        @ParameterizedTest
        @ValueSource(ints = {-1, 0, 4, 8, 12, 999})
        @DisplayName("88 APPL-EOF is false for every other value, zero and 12 included")
        void applEofIsFalseOtherwise(int value) {
            assertThat(CustomerService.isApplEof(value)).isFalse();
        }

        @Test
        @DisplayName("the two are mutually exclusive, so the guard chain's arms cannot both be taken")
        void theTwoConditionsAreDisjoint() {
            assertThat(CustomerService.APPL_AOK).isNotEqualTo(CustomerService.APPL_EOF);
            assertThat(CustomerService.isApplAok(CustomerService.APPL_EOF)).isFalse();
            assertThat(CustomerService.isApplEof(CustomerService.APPL_AOK)).isFalse();
        }

        @Test
        @DisplayName("the fatal value satisfies neither, which is what reaches the abend")
        void twelveSatisfiesNeither() {
            assertThat(CustomerService.isApplAok(CustomerService.APPL_RESULT_FATAL)).isFalse();
            assertThat(CustomerService.isApplEof(CustomerService.APPL_RESULT_FATAL)).isFalse();
        }
    }

    @Nested
    @DisplayName("WORKING-STORAGE, per invocation - L46-L67")
    class WorkingStorageItems {
        @Test
        @DisplayName("END-OF-FILE starts at 'N', as its VALUE clause declares - L65")
        void theFlagStartsAtItsDeclaredValue() {
            WorkingStorage storage = new WorkingStorage();

            assertThat(storage.endOfFileFlag()).isEqualTo(CustomerService.END_OF_FILE_NO);
            assertThat(storage.endOfFileIsNo()).isTrue();
            assertThat(storage.endOfFileIsYes()).isFalse();
        }

        @Test
        @DisplayName("MOVE 'Y' TO END-OF-FILE flips both tests - L108")
        void movingYesFlipsBothTests() {
            WorkingStorage storage = new WorkingStorage();

            storage.moveEndOfFile(CustomerService.END_OF_FILE_YES);

            assertThat(storage.endOfFileIsYes()).isTrue();
            assertThat(storage.endOfFileIsNo()).isFalse();
        }

        @Test
        @DisplayName("the two flag tests are not each other's negation - a third value fails both")
        void theTwoFlagTestsAreIndependent() {
            WorkingStorage storage = new WorkingStorage();

            storage.moveEndOfFile("?");

            assertThat(storage.endOfFileIsYes()).isFalse();
            assertThat(storage.endOfFileIsNo()).isFalse();
        }

        @Test
        @DisplayName("a null flag value is refused - PIC X(01) has no null state")
        void aNullFlagIsRefused() {
            WorkingStorage storage = new WorkingStorage();

            assertThatNullPointerException().isThrownBy(() -> storage.moveEndOfFile(null));
        }

        @Test
        @DisplayName("MOVE <n> TO APPL-RESULT sets the register - L95, L99, L101, L119, L122, L124")
        void moveToApplResult() {
            WorkingStorage storage = new WorkingStorage();

            storage.moveToApplResult(CustomerService.APPL_RESULT_ASSUMED_FAILURE);
            assertThat(storage.applResult()).isEqualTo(8);

            storage.moveToApplResult(CustomerService.APPL_AOK);
            assertThat(storage.applResult()).isZero();
            assertThat(storage.applAok()).isTrue();

            storage.moveToApplResult(CustomerService.APPL_EOF);
            assertThat(storage.applEof()).isTrue();
        }

        @Test
        @DisplayName("ADD 8 TO ZERO GIVING APPL-RESULT reaches the same 8 as the MOVE - L137")
        void addToZeroGivingMatchesTheMove() {
            WorkingStorage viaAdd = new WorkingStorage();
            WorkingStorage viaMove = new WorkingStorage();

            viaAdd.addToZeroGivingApplResult(CustomerService.APPL_RESULT_ASSUMED_FAILURE);
            viaMove.moveToApplResult(CustomerService.APPL_RESULT_ASSUMED_FAILURE);

            assertThat(viaAdd.applResult()).isEqualTo(viaMove.applResult()).isEqualTo(8);
        }

        @Test
        @DisplayName("ADD 12 TO ZERO GIVING discards whatever the register held - L142")
        void addToZeroGivingIgnoresThePreviousValue() {
            WorkingStorage storage = new WorkingStorage();

            storage.moveToApplResult(999);
            storage.addToZeroGivingApplResult(CustomerService.APPL_RESULT_FATAL);

            assertThat(storage.applResult()).isEqualTo(12);
        }

        @Test
        @DisplayName("SUBTRACT APPL-RESULT FROM APPL-RESULT zeroes it from any value - L140")
        void subtractFromItselfZeroes() {
            WorkingStorage storage = new WorkingStorage();

            storage.moveToApplResult(8);
            storage.subtractApplResultFromApplResult();
            assertThat(storage.applResult()).isZero();
            assertThat(storage.applAok()).isTrue();

            storage.moveToApplResult(-7);
            storage.subtractApplResultFromApplResult();
            assertThat(storage.applResult()).isZero();
        }

        @Test
        @DisplayName("MOVE CUSTFILE-STATUS TO IO-STATUS is what the renderer reads - L111, L130, L148")
        void ioStatusCarriesTheMovedStatus() {
            WorkingStorage storage = new WorkingStorage();

            storage.moveToIoStatus(FileStatus.NOT_FOUND);

            assertThat(storage.ioStatus()).isEqualTo("23");
        }

        @Test
        @DisplayName("reading IO-STATUS before the MOVE is a translation defect, and says so")
        void ioStatusBeforeTheMoveIsRefused() {
            WorkingStorage storage = new WorkingStorage();

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(storage::ioStatus)
                    .withMessageContaining("L111");
        }

        @Test
        @DisplayName("a null status is refused - a file status is always two characters")
        void aNullStatusIsRefused() {
            WorkingStorage storage = new WorkingStorage();

            assertThatNullPointerException().isThrownBy(() -> storage.moveToIoStatus(null));
        }

        @Test
        @DisplayName("two carriers are independent, which is why they are not fields of the bean")
        void carriersAreIndependent() {
            WorkingStorage first = new WorkingStorage();
            WorkingStorage second = new WorkingStorage();

            first.moveEndOfFile(CustomerService.END_OF_FILE_YES);
            first.moveToApplResult(12);

            assertThat(second.endOfFileIsNo()).isTrue();
            assertThat(second.applResult()).isZero();
        }
    }

    @Nested
    @DisplayName("The redundant outer guard at L75, kept deliberately")
    class VacuousGuard {
        @Test
        @DisplayName("an iteration entered with the flag already 'Y' reads nothing and displays nothing")
        void theFalseArmOfTheFirstGuardIsReachableAndInert() {
            CustomerService subject = service(seeded(fixtureRows()));
            CustomerRepository repository = repository(seeded(fixtureRows()));
            Sysout sink = new Sysout();
            WorkingStorage storage = new WorkingStorage();
            storage.moveEndOfFile(CustomerService.END_OF_FILE_YES);

            try (CustomerFile file = repository.openInput()) {
                int displayed = subject.custfileDisplayIteration(sink, storage, file);

                assertThat(displayed).isZero();
                assertThat(sink.lines()).isEmpty();
            }
        }

        @Test
        @DisplayName("an iteration entered with the flag at 'N' reads one record and displays it twice")
        void theTrueArmReadsAndDisplaysThePair() {
            CustomerService subject = service(seeded(fixtureRows()));
            CustomerRepository repository = repository(seeded(fixtureRows()));
            Sysout sink = new Sysout();
            WorkingStorage storage = new WorkingStorage();

            try (CustomerFile file = repository.openInput()) {
                int displayed = subject.custfileDisplayIteration(sink, storage, file);

                assertThat(displayed).isEqualTo(1);
                assertThat(sink.lines()).hasSize(CustomerService.DISPLAYS_PER_RECORD);
                assertThat(sink.lines().get(0)).isEqualTo(sink.lines().get(1));
                assertThat(sink.lines().get(0)).isEqualTo(fixtureRows().get(0));
            }
        }

        @Test
        @DisplayName("the inner guard at L77 suppresses the mainline display for the end-of-file read")
        void theInnerGuardSuppressesTheDisplayAtEndOfFile() {
            CustomerService subject = service(seeded(List.of()));
            CustomerRepository repository = repository(seeded(List.of()));
            Sysout sink = new Sysout();
            WorkingStorage storage = new WorkingStorage();

            try (CustomerFile file = repository.openInput()) {
                int displayed = subject.custfileDisplayIteration(sink, storage, file);

                assertThat(displayed).isZero();
                assertThat(sink.lines()).isEmpty();
                assertThat(storage.endOfFileIsYes()).isTrue();
            }
        }
    }

    @Nested
    @DisplayName("Execution - the behavioural fingerprint")
    class ExecutionInvariants {
        @Test
        @DisplayName("a line count that does not match two per record is refused")
        void aDeDuplicatedFingerprintIsRejected() {
            List<String> halved = new ArrayList<>();
            halved.add(CustomerService.START_OF_EXECUTION);
            halved.addAll(fixtureRows());
            halved.add(CustomerService.END_OF_EXECUTION);

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new Execution(halved, 0, FIXTURE_RECORDS))
                    .withMessageContaining("displays every record twice");
        }

        @Test
        @DisplayName("a null line list is refused")
        void aNullLineListIsRefused() {
            assertThatNullPointerException().isThrownBy(() -> new Execution(null, 0, 0));
        }

        @Test
        @DisplayName("a negative record count is refused")
        void aNegativeRecordCountIsRefused() {
            List<String> banners = Arrays.asList(CustomerService.START_OF_EXECUTION,
                    CustomerService.END_OF_EXECUTION);

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new Execution(banners, 0, -1))
                    .withMessageContaining("cannot have read");
        }

        @Test
        @DisplayName("the record lines are the sequence with both banners removed")
        void recordLinesStripTheBanners() {
            Execution execution = service(seeded(fixtureRows())).readAndPrintCustomerFile();

            assertThat(execution.recordLines())
                    .hasSize(FIXTURE_RECORDS * CustomerService.DISPLAYS_PER_RECORD)
                    .doesNotContain(CustomerService.START_OF_EXECUTION)
                    .doesNotContain(CustomerService.END_OF_EXECUTION);
        }

        @Test
        @DisplayName("the list is copied at construction, so the caller cannot mutate it afterwards")
        void theListIsDefensivelyCopied() {
            List<String> mutable = new ArrayList<>();
            mutable.add(CustomerService.START_OF_EXECUTION);
            mutable.add(CustomerService.END_OF_EXECUTION);

            Execution execution = new Execution(mutable, 0, 0);
            mutable.add("injected after the fact");

            assertThat(execution.sysout()).hasSize(2);
        }
    }

    @Nested
    @DisplayName("Sysout - the ordered, append-only line sink")
    class SysoutGuards {
        @Test
        @DisplayName("a fresh sink is empty")
        void aFreshSinkIsEmpty() {
            Sysout sink = new Sysout();

            assertThat(sink.lines()).isEmpty();
            assertThat(sink.lineCount()).isZero();
            assertThat(sink.recordImageCount()).isZero();
        }

        @Test
        @DisplayName("the snapshot it hands out cannot be used to append")
        void theSnapshotIsUnmodifiable() {
            Sysout sink = new Sysout();

            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> sink.lines().add("injected"));
        }

        @Test
        @DisplayName("a record image that is not 500 characters is refused - the FILLER tripwire, G21")
        void aShortImageIsRefused() {
            Sysout sink = new Sysout();

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> sink.displayCustomerRecord("too short"))
                    .withMessageContaining("FILLER");
        }

        @Test
        @DisplayName("a null line and a null record image are both refused")
        void nullsAreRefused() {
            Sysout sink = new Sysout();

            assertThatNullPointerException().isThrownBy(() -> sink.display(null));
            assertThatNullPointerException().isThrownBy(() -> sink.displayCustomerRecord(null));
        }

        @Test
        @DisplayName("an image of exactly 500 characters is accepted and counted")
        void anImageOfTheDeclaredWidthIsAccepted() {
            Sysout sink = new Sysout();

            sink.displayCustomerRecord(fixtureRows().get(0));

            assertThat(sink.lineCount()).isEqualTo(1);
            assertThat(sink.recordImageCount()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("The application log discloses no customer data - CWE-532")
    class LogDisclosure {
        private ch.qos.logback.classic.Logger serviceLogger() {
            return (ch.qos.logback.classic.Logger)
                    org.slf4j.LoggerFactory.getLogger(CustomerService.class);
        }

        private List<String> capturedAtDebug(List<String> capturedSysout) {
            ch.qos.logback.classic.Logger logger = serviceLogger();
            ch.qos.logback.classic.Level previous = logger.getLevel();
            ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                    new ch.qos.logback.core.read.ListAppender<>();
            appender.start();
            logger.addAppender(appender);
            logger.setLevel(ch.qos.logback.classic.Level.DEBUG);
            try {
                assertThat(logger.isDebugEnabled())
                        .as("the capture is worthless unless DEBUG really is enabled")
                        .isTrue();
                Execution execution = service(seeded(fixtureRows())).readAndPrintCustomerFile();
                capturedSysout.addAll(execution.sysout());
            } finally {
                logger.setLevel(previous);
                logger.detachAppender(appender);
                appender.stop();
            }
            List<String> messages = new ArrayList<>();
            for (ch.qos.logback.classic.spi.ILoggingEvent event : appender.list) {
                messages.add(event.getFormattedMessage());
            }
            return messages;
        }

        @Test
        @DisplayName("with DEBUG on, a trace is written per record image - and it is only an ordinal")
        void theDebugTraceIsAnOrdinalAndNotTheRecord() {
            List<String> sysout = new ArrayList<>();
            List<String> logged = capturedAtDebug(sysout);

            List<String> traces = logged.stream()
                    .filter(message -> message.startsWith("Displayed CUSTOMER-RECORD image"))
                    .toList();

            assertThat(traces).hasSize(FIXTURE_RECORDS * CustomerService.DISPLAYS_PER_RECORD);
            assertThat(traces).allSatisfy(trace -> {
                assertThat(trace).contains(CustomerService.DD_NAME);
                assertThat(trace).contains("withheld from this log");
            });
        }

        @Test
        @DisplayName("no logged message contains a record image, a name or a social security number")
        void noLoggedMessageDisclosesCustomerData() {
            List<String> sysout = new ArrayList<>();
            List<String> logged = capturedAtDebug(sysout);

            String firstImage = fixtureRows().get(0);
            CustomerRecord firstRecord = CustomerRecord.decode(firstImage, ASCII);
            String lastName = firstRecord.getCustLastName().strip();
            String ssn = firstRecord.custSsnImage(ASCII);

            assertThat(lastName).isNotBlank();
            assertThat(ssn).hasSize(NINE);
            assertThat(logged).isNotEmpty().allSatisfy(message -> {
                assertThat(message).doesNotContain(firstImage);
                assertThat(message).doesNotContain(lastName);
                assertThat(message).doesNotContain(ssn);
            });
        }

        @Test
        @DisplayName("the control lines ARE logged verbatim, so a run is still diagnosable")
        void theControlLinesAreLoggedVerbatim() {
            List<String> sysout = new ArrayList<>();
            List<String> logged = capturedAtDebug(sysout);

            assertThat(logged)
                    .contains(CustomerService.START_OF_EXECUTION)
                    .contains(CustomerService.END_OF_EXECUTION);
        }

        @Test
        @DisplayName("with DEBUG off, no per-record entry is written at all - and SYSOUT is unchanged")
        void withDebugOffNothingPerRecordIsLogged() {
            ch.qos.logback.classic.Logger logger = serviceLogger();
            ch.qos.logback.classic.Level previous = logger.getLevel();
            ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                    new ch.qos.logback.core.read.ListAppender<>();
            appender.start();
            logger.addAppender(appender);
            logger.setLevel(ch.qos.logback.classic.Level.INFO);

            Execution execution;
            try {
                assertThat(logger.isDebugEnabled()).isFalse();
                execution = service(seeded(fixtureRows())).readAndPrintCustomerFile();
            } finally {
                logger.setLevel(previous);
                logger.detachAppender(appender);
                appender.stop();
            }

            List<String> messages = new ArrayList<>();
            for (ch.qos.logback.classic.spi.ILoggingEvent event : appender.list) {
                messages.add(event.getFormattedMessage());
            }

            assertThat(messages)
                    .noneMatch(message -> message.startsWith("Displayed CUSTOMER-RECORD image"));
            assertThat(messages).containsExactly(
                    CustomerService.START_OF_EXECUTION,
                    CustomerService.END_OF_EXECUTION);
            assertThat(execution.lineCount()).isEqualTo(FIXTURE_LINE_COUNT);
        }

        @Test
        @DisplayName("withholding the images from the log costs no parity - SYSOUT is still complete")
        void parityIsUnaffectedByTheMasking() {
            List<String> sysout = new ArrayList<>();
            capturedAtDebug(sysout);

            assertThat(sysout).hasSize(FIXTURE_LINE_COUNT);
            List<String> expected = fixtureRows();
            for (int index = 0; index < expected.size(); index++) {
                assertThat(sysout.get(1 + index * 2)).isEqualTo(expected.get(index));
                assertThat(sysout.get(2 + index * 2)).isEqualTo(expected.get(index));
            }
        }
    }
    @Nested
    @DisplayName("DBP-05: a streaming sink emits everything and retains nothing")
    class StreamingSysout {
        @Test
        @DisplayName("the streamed sequence is byte-identical to the captured one, in the same order")
        void theStreamedSequenceIsTheCapturedSequence() {
            List<String> captured = service(seeded(fixtureRows())).readAndPrintCustomerFile().sysout();

            List<String> streamed = new ArrayList<>();
            service(seeded(fixtureRows())).readAndPrintCustomerFileTo(streamed::add);

            assertThat(streamed).containsExactlyElementsOf(captured);
            assertThat(streamed).hasSize(FIXTURE_LINE_COUNT);
        }

        @Test
        @DisplayName("the duplicate display of L96 then L78 survives streaming")
        void theDuplicateDisplaySurvivesStreaming() {
            List<String> streamed = new ArrayList<>();
            service(seeded(fixtureRows())).readAndPrintCustomerFileTo(streamed::add);

            List<String> records = streamed.subList(1, streamed.size() - 1);
            assertThat(records).hasSize(FIXTURE_RECORDS * CustomerService.DISPLAYS_PER_RECORD);
            for (int pair = 0; pair < FIXTURE_RECORDS; pair++) {
                assertThat(records.get(pair * 2))
                        .as("record %d's two displays must still be byte-identical and adjacent", pair)
                        .isEqualTo(records.get(pair * 2 + 1));
            }
        }

        @Test
        @DisplayName("nothing is retained: lines() refuses rather than answering 'nothing was emitted'")
        void nothingIsRetained() {
            List<String> streamed = new ArrayList<>();
            Sysout sink = new Sysout(streamed::add);

            service(seeded(fixtureRows())).readAndPrintCustomerFileTo(streamed::add);

            assertThat(sink.retains()).isFalse();
            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(sink::lines)
                    .withMessageContaining("kept none of them");
        }

        @Test
        @DisplayName("the counters stay answerable while streaming, because they are counters")
        void theCountersStayAnswerable() {
            List<String> streamed = new ArrayList<>();
            Sysout sink = new Sysout(streamed::add);

            sink.display(CustomerService.START_OF_EXECUTION);
            sink.displayCustomerRecord("x".repeat(FIVE_HUNDRED));
            sink.displayCustomerRecord("x".repeat(FIVE_HUNDRED));

            assertThat(sink.lineCount()).isEqualTo(3);
            assertThat(sink.recordImageCount()).isEqualTo(CustomerService.DISPLAYS_PER_RECORD);
            assertThat(streamed).hasSize(3);
        }

        @Test
        @DisplayName("a capturing sink still retains, so every existing caller is unaffected")
        void aCapturingSinkStillRetains() {
            Sysout sink = new Sysout();

            assertThat(sink.retains()).isTrue();
            assertThat(sink.lines()).isEmpty();
        }

        @Test
        @DisplayName("the Execution-returning overload refuses a streaming sink instead of failing later")
        void theCapturingOverloadRefusesAStreamingSink() {
            CustomerService subject = service(seeded(fixtureRows()));
            Sysout streaming = new Sysout(line -> { });

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> subject.readAndPrintCustomerFile(streaming))
                    .withMessageContaining("readAndPrintCustomerFileTo(SysoutSink)");
        }

        @Test
        @DisplayName("an abend still streams its error text, its status line and ABENDING PROGRAM")
        void anAbendStillStreamsItsThreeLines() {
            CustomerService subject = service(refusingTheFirstDescribe(seeded(fixtureRows())));
            List<String> streamed = new ArrayList<>();

            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> subject.readAndPrintCustomerFileTo(streamed::add));

            assertThat(streamed).containsExactly(
                    CustomerService.START_OF_EXECUTION,
                    CustomerService.ERROR_OPENING_CUSTFILE,
                    FileStatus.toDisplayLine(CustomerRepository.PERMANENT_ERROR_STATUS),
                    CustomerService.ABENDING_PROGRAM);
            assertThat(streamed).doesNotContain(CustomerService.END_OF_EXECUTION);
        }

        @Test
        @DisplayName("the standard-output sink writes one undecorated line per DISPLAY")
        void theStandardOutputSinkIsUndecorated() {
            ByteArrayOutputStream captured = new ByteArrayOutputStream();
            SysoutSink sink = new PrintStreamSysoutSink(new PrintStream(captured, true, ASCII));

            sink.write(CustomerService.START_OF_EXECUTION);
            sink.write(CustomerService.END_OF_EXECUTION);

            assertThat(captured.toString(ASCII)).isEqualTo(
                    CustomerService.START_OF_EXECUTION + System.lineSeparator()
                            + CustomerService.END_OF_EXECUTION + System.lineSeparator());
        }

        @Test
        @DisplayName("a trailing space in an emitted line reaches the destination intact")
        void trailingSpaceIsPreserved() {
            List<String> streamed = new ArrayList<>();
            String image = "A".repeat(FIVE_HUNDRED - 3) + "   ";

            new Sysout(streamed::add).displayCustomerRecord(image);

            assertThat(streamed).containsExactly(image);
            assertThat(streamed.get(0)).hasSize(FIVE_HUNDRED).endsWith("   ");
        }

        @Test
        @DisplayName("a streaming sink and the streaming entry point each need a destination")
        void aDestinationIsRequired() {
            assertThatNullPointerException().isThrownBy(() -> new Sysout((SysoutSink) null));
            assertThatNullPointerException().isThrownBy(
                    () -> service(seeded(fixtureRows())).readAndPrintCustomerFileTo(null));
        }

        @Test
        @DisplayName("the standard-output sink refuses a missing stream and a missing line")
        void thePrintStreamSinkGuardsItsArguments() {
            assertThatNullPointerException().isThrownBy(() -> new PrintStreamSysoutSink(null));
            assertThatNullPointerException().isThrownBy(
                    () -> CustomerService.standardOutput(ASCII).write(null));
        }

        @Test
        @DisplayName("the SYSOUT=* default demands an explicit code page")
        void theDefaultSinkNeedsACharset() {
            assertThatNullPointerException()
                    .isThrownBy(() -> CustomerService.standardOutput(null));
        }

        @Test
        @DisplayName("the SYSOUT=* default encodes in the dataset code page, not the platform default")
        void theDefaultIsOverStandardOutputInTheDatasetCodePage() {
            CustomerService subject = service(seeded(fixtureRows()));
            SysoutSink sink = subject.standardOutputSysoutSink();

            assertThat(subject.datasetCharset()).isEqualTo(ASCII);
            assertThat(sink).isInstanceOf(PrintStreamSysoutSink.class);
            PrintStream stream = ((PrintStreamSysoutSink) sink).stream();
            assertThat(stream.charset()).isEqualTo(ASCII);
            assertThat(stream).isNotSameAs(System.out);
        }

        @Test
        @DisplayName("the SYSOUT=* default is a sink over the standard output file descriptor, not the "
                + "mutable System.out global")
        void theDefaultIsOverStandardOutput() {
            SysoutSink sink = CustomerService.standardOutput(ASCII);

            assertThat(sink).isInstanceOf(PrintStreamSysoutSink.class);
            assertThat(((PrintStreamSysoutSink) sink).stream()).isNotSameAs(System.out);
        }

        @Test
        @DisplayName("the SYSOUT factory takes the code page as a parameter and demands one")
        void theSysoutFactoryTakesItsCharsetAsAParameter() {
            assertThat(((PrintStreamSysoutSink) CustomerService.standardOutput(EBCDIC)).stream()
                    .charset()).isEqualTo(EBCDIC);
            assertThat(((PrintStreamSysoutSink) CustomerService.standardOutput(ASCII)).stream()
                    .charset()).isEqualTo(ASCII);
            assertThatNullPointerException()
                    .isThrownBy(() -> CustomerService.standardOutput(null));
        }

        @Test
        @DisplayName("a sink encodes each line in the code page it was given, never the platform default")
        void theSinkEncodesInTheCodePageGiven() {
            Charset ibm037 = Charset.forName("IBM037");
            String line = "A" + '\u00A0';
            ByteArrayOutputStream inDatasetCodePage = new ByteArrayOutputStream();
            ByteArrayOutputStream inAscii = new ByteArrayOutputStream();

            new PrintStreamSysoutSink(new PrintStream(inDatasetCodePage, true, ibm037)).write(line);
            new PrintStreamSysoutSink(new PrintStream(inAscii, true, ASCII)).write(line);

            assertThat(inDatasetCodePage.toByteArray())
                    .isEqualTo((line + System.lineSeparator()).getBytes(ibm037));
            assertThat(inDatasetCodePage.toByteArray()).isNotEqualTo(inAscii.toByteArray());
        }

        @Test
        @DisplayName("the code page the service publishes for SYSOUT is the one it reads records in")
        void theServicePublishesItsDatasetCodePage() {
            assertThat(service(seeded(fixtureRows())).datasetCharset()).isEqualTo(ASCII);
        }
    }

    @Nested
    @DisplayName("A DISPLAY reached with no record to display is refused, not silently blanked")
    class TheDisplayGuard {
        @Test
        @DisplayName("a record-less outcome raises rather than rendering spaces, and says which status")
        void aRecordLessOutcomeIsRefused() throws Exception {
            Method recordImageOf = CustomerService.class.getDeclaredMethod("recordImageOf",
                    CustomerRepository.ReadResult.class, CustomerService.WorkingStorage.class);
            recordImageOf.setAccessible(true);
            CustomerService subject = service(seeded(fixtureRows()));

            assertThatExceptionOfType(InvocationTargetException.class)
                    .isThrownBy(() -> recordImageOf.invoke(subject,
                            CustomerRepository.ReadResult.endOfFile(),
                            new CustomerService.WorkingStorage()))
                    .withCauseInstanceOf(IllegalStateException.class)
                    .satisfies(raised -> assertThat(raised.getCause())
                            .hasMessageContaining(FileStatus.END_OF_FILE)
                            .as("and names the status and the END-OF-FILE flag, so the defect is locatable")
                            .hasMessageContaining("END-OF-FILE"));
        }

    }

    @Nested
    @DisplayName("PROCEDURE DIVISION - the mainline, L70-L87")
    class Mainline {
        @Test
        @DisplayName("opens once, reads until end of file, then closes once - L72, L76, L83")
        void theInteractionOrderIsOpenThenEveryReadThenClose() {
            StubbedRun run = stubbedRunOver(2);

            Execution execution = run.subject().readAndPrintCustomerFile();

            InOrder order = Mockito.inOrder(run.repository(), run.file());
            order.verify(run.repository()).openInput();
            order.verify(run.file()).openStatus();
            order.verify(run.file(), Mockito.times(3)).readNext();
            order.verify(run.file()).closeFile();

            assertThat(execution.recordsRead()).isEqualTo(2);
            assertThat(execution.lineCount()).isEqualTo(6);
        }

        @Test
        @DisplayName("the browse is walked exactly once per record plus one terminating read")
        void everyRecordCostsOneReadAndTheEndCostsOneMore() {
            StubbedRun run = stubbedRunOver(1);

            run.subject().readAndPrintCustomerFile();

            Mockito.verify(run.file(), Mockito.times(2)).readNext();
            Mockito.verify(run.file(), Mockito.times(1)).closeFile();
        }

        @Test
        @DisplayName("an empty dataset still opens, reads once and closes - L120, L93, L138")
        void anEmptyDatasetStillOpensReadsOnceAndCloses() {
            StubbedRun run = stubbedRun(FileStatus.OK, FileStatus.OK);

            Execution execution = run.subject().readAndPrintCustomerFile();

            InOrder order = Mockito.inOrder(run.repository(), run.file());
            order.verify(run.repository()).openInput();
            order.verify(run.file(), Mockito.times(1)).readNext();
            order.verify(run.file()).closeFile();

            assertThat(execution.recordsRead()).isZero();
            assertThat(execution.sysout()).containsExactly(
                    CustomerService.START_OF_EXECUTION,
                    CustomerService.END_OF_EXECUTION);
        }

        @Test
        @DisplayName("a one-record dataset emits four lines: banner, image, image, banner")
        void aSingleRecordDatasetEmitsFourLines() {
            String only = fixtureRows().get(0);
            StubbedRun run = stubbedRunOver(1);

            Execution execution = run.subject().readAndPrintCustomerFile();

            assertThat(execution.sysout()).containsExactly(
                    CustomerService.START_OF_EXECUTION,
                    only,
                    only,
                    CustomerService.END_OF_EXECUTION);
            assertThat(execution.lineCount()).isEqualTo(4);
        }

        @Test
        @DisplayName("the service touches no access path but the browse - no keyed read, no rewrite")
        void theServiceUsesNoAccessPathButTheBrowse() {
            StubbedRun run = stubbedRunOver(3);

            run.subject().readAndPrintCustomerFile();

            Mockito.verify(run.repository(), Mockito.never()).readByKey(Mockito.anyLong());
            Mockito.verify(run.repository(), Mockito.never()).readByKey(Mockito.anyString());
            Mockito.verify(run.repository(), Mockito.never()).readForUpdate(Mockito.anyString());
            Mockito.verify(run.repository(), Mockito.never()).rewrite(Mockito.any(byte[].class));
            Mockito.verify(run.repository(), Mockito.never()).rewrite(Mockito.any(CustomerRecord.class));
            Mockito.verify(run.file(), Mockito.never()).readByKey(Mockito.anyLong());
            Mockito.verify(run.file(), Mockito.never()).readByKey(Mockito.anyString());
        }

        @Test
        @DisplayName("the FIRST line of each pair is emitted inside the read paragraph - L96, not L78")
        void theFirstOfEachPairBelongsToTheReadParagraph() throws Exception {
            Method custfileGetNext = CustomerService.class.getDeclaredMethod("custfileGetNext",
                    Sysout.class, WorkingStorage.class, CustomerFile.class);
            custfileGetNext.setAccessible(true);

            String first = fixtureRows().get(0);
            StubbedRun run = stubbedRunOver(1);
            CustomerFile file = run.repository().openInput();
            Sysout readParagraphOnly = new Sysout();
            WorkingStorage storage = new WorkingStorage();

            custfileGetNext.invoke(run.subject(), readParagraphOnly, storage, file);

            assertThat(readParagraphOnly.lines()).containsExactly(first);
            assertThat(readParagraphOnly.recordImageCount()).isEqualTo(1);
            assertThat(storage.endOfFileIsNo()).isTrue();
            assertThat(storage.applResult()).isEqualTo(CustomerService.APPL_AOK);

            StubbedRun secondRun = stubbedRunOver(1);
            CustomerFile secondFile = secondRun.repository().openInput();
            Sysout wholeIteration = new Sysout();

            int displayed = secondRun.subject().custfileDisplayIteration(wholeIteration,
                    new WorkingStorage(), secondFile);

            assertThat(displayed).isEqualTo(1);
            assertThat(wholeIteration.lines()).containsExactly(first, first);
            assertThat(wholeIteration.lineCount() - readParagraphOnly.lineCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("the false arm of the L75 guard reads nothing at all, not merely displays nothing")
        void theRedundantGuardsFalseArmIssuesNoRead() {
            StubbedRun run = stubbedRunOver(1);
            CustomerFile file = run.repository().openInput();
            Sysout sink = new Sysout();
            WorkingStorage storage = new WorkingStorage();
            storage.moveEndOfFile(CustomerService.END_OF_FILE_YES);

            int displayed = run.subject().custfileDisplayIteration(sink, storage, file);

            assertThat(displayed).isZero();
            assertThat(sink.lines()).isEmpty();
            assertThat(sink.lineCount()).isZero();
            Mockito.verify(file, Mockito.never()).readNext();
            Mockito.verify(file, Mockito.never()).closeFile();
        }

        @Test
        @DisplayName("the closing banner is emitted after the close, not before it - L83 then L85")
        void theClosingBannerFollowsTheClose() {
            CustomerRepository repository = stubbedRepository();
            CustomerFile file = Mockito.mock(CustomerFile.class);
            AtomicBoolean closed = new AtomicBoolean();
            AtomicInteger linesWhenClosed = new AtomicInteger(-1);
            Sysout sink = new Sysout();

            Mockito.when(repository.openInput()).thenReturn(file);
            Mockito.when(file.openStatus()).thenReturn(FileStatus.OK);
            Mockito.when(file.isClosed()).thenAnswer(invocation -> closed.get());
            Mockito.when(file.readNext()).thenReturn(foundRow(fixtureRows().get(0)),
                    ReadResult.endOfFile());
            Mockito.when(file.closeFile()).thenAnswer(invocation -> {
                closed.set(true);
                linesWhenClosed.set(sink.lineCount());
                return FileStatus.OK;
            });

            Execution execution = new CustomerService(repository).readAndPrintCustomerFile(sink);

            assertThat(linesWhenClosed.get()).isEqualTo(3);
            assertThat(execution.lineCount()).isEqualTo(4);
            assertThat(execution.sysout().get(3)).isEqualTo(CustomerService.END_OF_EXECUTION);
        }

        @Test
        @DisplayName("the whole 102-line sequence is asserted position by position, banners included")
        void theWholeSequenceIsAssertedPositionByPosition() {
            List<String> rows = fixtureRows();
            List<String> expected = new ArrayList<>();
            expected.add(CustomerService.START_OF_EXECUTION);
            for (String row : rows) {
                expected.add(row);
                expected.add(row);
            }
            expected.add(CustomerService.END_OF_EXECUTION);

            Execution execution = service(seeded(rows)).readAndPrintCustomerFile();

            assertThat(rows).hasSize(FIXTURE_RECORDS);
            assertThat(expected).hasSize(FIXTURE_LINE_COUNT);
            assertThat(execution.sysout()).containsExactlyElementsOf(expected);
            assertThat(execution.lineCount()).isEqualTo(102);
            assertThat(execution.recordsRead()).isEqualTo(50);

            for (int record = 0; record < FIXTURE_RECORDS; record++) {
                String image = execution.sysout().get(1 + record * CustomerService.DISPLAYS_PER_RECORD);
                assertThat(image.substring(0, NINE))
                        .as("the key at browse position %d", record)
                        .isEqualTo(CustomerRecord.decode(image, ASCII).custIdImage(ASCII));
                assertThat(Integer.parseInt(image.substring(0, NINE)))
                        .as("keys ascend by one from 000000001")
                        .isEqualTo(record + 1);
            }
        }

        @Test
        @DisplayName("a normal end returns RETURN-CODE zero, because L87 never moves into it")
        void aNormalEndReturnsZero() {
            Execution execution = stubbedRunOver(2).subject().readAndPrintCustomerFile();

            assertThat(execution.returnCode()).isEqualTo(CustomerService.RETURN_CODE_NORMAL_END);
            assertThat(execution.returnCode()).isZero();
        }
    }

    @Nested
    @DisplayName("0000-CUSTFILE-OPEN - L118-L134")
    class CustfileOpen {
        @Test
        @DisplayName("'00' moves 0 and continues, so the browse begins - L121-L122, L126-L127")
        void anOkOpenContinues() {
            StubbedRun run = stubbedRun(FileStatus.OK, FileStatus.OK, foundRow(fixtureRows().get(0)),
                    ReadResult.endOfFile());

            Execution execution = run.subject().readAndPrintCustomerFile();

            assertThat(execution.recordsRead()).isEqualTo(1);
            assertThat(execution.sysout().get(execution.lineCount() - 1))
                    .isEqualTo(CustomerService.END_OF_EXECUTION);
            Mockito.verify(run.file(), Mockito.times(2)).readNext();
        }

        @Test
        @DisplayName("'10' on the OPEN ABENDS - the paragraph has no APPL-EOF arm at all")
        void endOfFileOnTheOpenIsFatal() {
            StubbedRun run = stubbedRun(FileStatus.END_OF_FILE, FileStatus.OK);
            Sysout sink = new Sysout();

            AbendException abend = runExpectingAbend(run.subject(), sink);

            assertThat(abend.getReturnCode()).isEqualTo(CustomerService.APPL_RESULT_FATAL);
            assertThat(abend.getReturnCode()).isEqualTo(12);
            assertThat(abend.getReturnCode()).isNotEqualTo(CustomerService.APPL_EOF);
            assertThat(sink.lines()).containsExactly(
                    CustomerService.START_OF_EXECUTION,
                    CustomerService.ERROR_OPENING_CUSTFILE,
                    FileStatus.DISPLAY_PREFIX + "0010",
                    CustomerService.ABENDING_PROGRAM);
        }

        @Test
        @DisplayName("a failed OPEN issues no READ and no CLOSE - L83 is never reached")
        void aFailedOpenNeitherReadsNorCloses() {
            StubbedRun run = stubbedRun(FileStatus.NOT_FOUND, FileStatus.OK);
            Sysout sink = new Sysout();

            runExpectingAbend(run.subject(), sink);

            Mockito.verify(run.file(), Mockito.never()).readNext();
            Mockito.verify(run.file(), Mockito.never()).closeFile();
            assertThat(sink.recordImageCount()).isZero();
            assertThat(sink.lines()).doesNotContain(CustomerService.END_OF_EXECUTION);
        }

        @ParameterizedTest
        @CsvSource({"10,0010", "22,0022", "23,0023", "04,0004", "30,0030", "41,0041"})
        @DisplayName("every non-'00' open status takes the single ELSE at L123-L124 and abends with 12")
        void everyNonOkOpenStatusAbendsWithTwelve(String status, String image) {
            StubbedRun run = stubbedRun(status, FileStatus.OK);
            Sysout sink = new Sysout();

            AbendException abend = runExpectingAbend(run.subject(), sink);

            assertThat(abend.getReturnCode()).isEqualTo(12);
            assertThat(abend.getAbendCode()).hasValue(999);
            assertThat(abend.getTiming()).hasValue(0);
            assertThat(sink.lines()).containsExactly(
                    CustomerService.START_OF_EXECUTION,
                    CustomerService.ERROR_OPENING_CUSTFILE,
                    FileStatus.DISPLAY_PREFIX + image,
                    CustomerService.ABENDING_PROGRAM);
        }

        @Test
        @DisplayName("the open text names the DD name, CUSTFILE, and not the file - L129")
        void theOpenTextNamesTheDdName() {
            StubbedRun run = stubbedRun(FileStatus.NOT_FOUND, FileStatus.OK);
            Sysout sink = new Sysout();

            runExpectingAbend(run.subject(), sink);

            assertThat(sink.lines().get(1))
                    .isEqualTo("ERROR OPENING " + CustomerRepository.BATCH_DD_NAME)
                    .isEqualTo("ERROR OPENING CUSTFILE")
                    .doesNotContain("CUSTOMER FILE");
        }

        @Test
        @DisplayName("the transient 8 at L119 is never observable outside the paragraph")
        void theTransientEightIsNeverObservable() {
            StubbedRun failing = stubbedRun(FileStatus.NOT_FOUND, FileStatus.OK);
            Sysout sink = new Sysout();

            AbendException abend = runExpectingAbend(failing.subject(), sink);

            assertThat(CustomerService.APPL_RESULT_ASSUMED_FAILURE).isEqualTo(8);
            assertThat(abend.getReturnCode()).isNotEqualTo(CustomerService.APPL_RESULT_ASSUMED_FAILURE);
            assertThat(sink.lines()).noneMatch(line -> line.endsWith("0008"));

            Execution clean = stubbedRunOver(1).subject().readAndPrintCustomerFile();
            assertThat(clean.returnCode()).isNotEqualTo(CustomerService.APPL_RESULT_ASSUMED_FAILURE);
        }
    }

    @Nested
    @DisplayName("1000-CUSTFILE-GET-NEXT - L92-L116")
    class CustfileGetNext {
        @Test
        @DisplayName("'00' moves 0 and displays the record - L94-L96")
        void anOkReadMovesZeroAndDisplays() throws Exception {
            Method custfileGetNext = CustomerService.class.getDeclaredMethod("custfileGetNext",
                    Sysout.class, WorkingStorage.class, CustomerFile.class);
            custfileGetNext.setAccessible(true);

            StubbedRun run = stubbedRunOver(1);
            CustomerFile file = run.repository().openInput();
            Sysout sink = new Sysout();
            WorkingStorage storage = new WorkingStorage();

            custfileGetNext.invoke(run.subject(), sink, storage, file);

            assertThat(storage.applResult()).isEqualTo(CustomerService.APPL_AOK);
            assertThat(storage.applAok()).isTrue();
            assertThat(storage.endOfFileIsNo()).isTrue();
            assertThat(sink.lines()).containsExactly(fixtureRows().get(0));
        }

        @Test
        @DisplayName("'10' moves 16, sets the flag, emits nothing and does not abend - L98-L99, L107-L108")
        void endOfFileMovesSixteenAndEndsCleanly() throws Exception {
            Method custfileGetNext = CustomerService.class.getDeclaredMethod("custfileGetNext",
                    Sysout.class, WorkingStorage.class, CustomerFile.class);
            custfileGetNext.setAccessible(true);

            StubbedRun run = stubbedRun(FileStatus.OK, FileStatus.OK);
            CustomerFile file = run.repository().openInput();
            Sysout sink = new Sysout();
            WorkingStorage storage = new WorkingStorage();

            custfileGetNext.invoke(run.subject(), sink, storage, file);

            assertThat(storage.applResult()).isEqualTo(CustomerService.APPL_EOF);
            assertThat(storage.applResult()).isEqualTo(16);
            assertThat(storage.applEof()).isTrue();
            assertThat(storage.applAok()).isFalse();
            assertThat(storage.endOfFileIsYes()).isTrue();
            assertThat(sink.lines()).isEmpty();
        }

        @Test
        @DisplayName("'10' ends the whole run cleanly, with both banners and no error text")
        void endOfFileEndsTheRunWithoutAnError() {
            StubbedRun run = stubbedRunOver(2);

            Execution execution = run.subject().readAndPrintCustomerFile();

            assertThat(execution.returnCode()).isZero();
            assertThat(execution.sysout()).doesNotContain(CustomerService.ERROR_READING_CUSTOMER_FILE);
            assertThat(execution.sysout()).doesNotContain(CustomerService.ABENDING_PROGRAM);
            assertThat(execution.sysout()).noneMatch(line -> line.startsWith(FileStatus.DISPLAY_PREFIX));
            assertThat(execution.sysout().get(0)).isEqualTo(CustomerService.START_OF_EXECUTION);
            assertThat(execution.sysout().get(5)).isEqualTo(CustomerService.END_OF_EXECUTION);
        }

        @ParameterizedTest
        @CsvSource({"22,0022", "23,0023", "04,0004", "30,0030", "9A,9065"})
        @DisplayName("every other status takes the single ELSE at L101, then L110-L113 abends with 12")
        void everyOtherStatusAbendsWithTwelve(String status, String image) {
            StubbedRun run = stubbedRun(FileStatus.OK, FileStatus.OK, ReadResult.of(status));
            Sysout sink = new Sysout();

            AbendException abend = runExpectingAbend(run.subject(), sink);

            assertThat(abend.getReturnCode()).isEqualTo(CustomerService.APPL_RESULT_FATAL);
            assertThat(abend.getReturnCode()).isEqualTo(12);
            assertThat(sink.lines()).containsExactly(
                    CustomerService.START_OF_EXECUTION,
                    CustomerService.ERROR_READING_CUSTOMER_FILE,
                    FileStatus.DISPLAY_PREFIX + image,
                    CustomerService.ABENDING_PROGRAM);
        }

        @Test
        @DisplayName("'22' and '23' produce identical output but for the rendered status - L101")
        void duplicateAndNotFoundShareTheOneArm() {
            Sysout duplicate = new Sysout();
            Sysout notFound = new Sysout();

            runExpectingAbend(stubbedRun(FileStatus.OK, FileStatus.OK,
                    ReadResult.of(FileStatus.DUPLICATE)).subject(), duplicate);
            runExpectingAbend(stubbedRun(FileStatus.OK, FileStatus.OK,
                    ReadResult.notFound()).subject(), notFound);

            assertThat(duplicate.lines()).hasSameSizeAs(notFound.lines());
            assertThat(duplicate.lines().get(1)).isEqualTo(notFound.lines().get(1));
            assertThat(duplicate.lines().get(3)).isEqualTo(notFound.lines().get(3));
            assertThat(duplicate.lines().get(2)).isEqualTo(FileStatus.DISPLAY_PREFIX + "0022");
            assertThat(notFound.lines().get(2)).isEqualTo(FileStatus.DISPLAY_PREFIX + "0023");
        }

        @Test
        @DisplayName("a fatal read after two good records keeps the four images already displayed")
        void theFatalArmDoesNotUnwindWhatWasAlreadyDisplayed() {
            List<String> rows = fixtureRows();
            StubbedRun run = stubbedRun(FileStatus.OK, FileStatus.OK,
                    foundRow(rows.get(0)), foundRow(rows.get(1)), ReadResult.notFound());
            Sysout sink = new Sysout();

            runExpectingAbend(run.subject(), sink);

            assertThat(sink.lines()).containsExactly(
                    CustomerService.START_OF_EXECUTION,
                    rows.get(0), rows.get(0),
                    rows.get(1), rows.get(1),
                    CustomerService.ERROR_READING_CUSTOMER_FILE,
                    FileStatus.DISPLAY_PREFIX + "0023",
                    CustomerService.ABENDING_PROGRAM);
            assertThat(sink.recordImageCount()).isEqualTo(4);
            assertThat(sink.lines()).doesNotContain(CustomerService.END_OF_EXECUTION);
        }

        @Test
        @DisplayName("a read that reports end of file suppresses the mainline display at L77")
        void theInnerGuardSuppressesTheSecondDisplay() {
            StubbedRun run = stubbedRunOver(1);
            CustomerFile file = run.repository().openInput();
            Sysout sink = new Sysout();
            WorkingStorage storage = new WorkingStorage();

            int firstIteration = run.subject().custfileDisplayIteration(sink, storage, file);
            int endIteration = run.subject().custfileDisplayIteration(sink, storage, file);

            assertThat(firstIteration).isEqualTo(1);
            assertThat(endIteration).isZero();
            assertThat(sink.lines()).hasSize(CustomerService.DISPLAYS_PER_RECORD);
            assertThat(storage.endOfFileIsYes()).isTrue();
        }
    }

    @Nested
    @DisplayName("9000-CUSTFILE-CLOSE - L136-L152")
    class CustfileClose {
        @Test
        @DisplayName("'00' zeroes the register by SUBTRACT and the run ends normally - L139-L140")
        void anOkCloseZeroesTheRegisterAndEndsTheRun() {
            StubbedRun run = stubbedRunOver(1);

            Execution execution = run.subject().readAndPrintCustomerFile();

            assertThat(execution.returnCode()).isZero();
            assertThat(execution.sysout().get(3)).isEqualTo(CustomerService.END_OF_EXECUTION);
            Mockito.verify(run.file(), Mockito.times(1)).closeFile();
        }

        @Test
        @DisplayName("'10' on the CLOSE ABENDS - the paragraph has no APPL-EOF arm either")
        void endOfFileOnTheCloseIsFatal() {
            StubbedRun run = stubbedRun(FileStatus.OK, FileStatus.END_OF_FILE,
                    foundRow(fixtureRows().get(0)), ReadResult.endOfFile());
            Sysout sink = new Sysout();

            AbendException abend = runExpectingAbend(run.subject(), sink);

            assertThat(abend.getReturnCode()).isEqualTo(CustomerService.APPL_RESULT_FATAL);
            assertThat(abend.getReturnCode()).isEqualTo(12);
            assertThat(abend.getReturnCode()).isNotEqualTo(CustomerService.APPL_EOF);
            assertThat(sink.lines()).containsExactly(
                    CustomerService.START_OF_EXECUTION,
                    fixtureRows().get(0), fixtureRows().get(0),
                    CustomerService.ERROR_CLOSING_CUSTOMER_FILE,
                    FileStatus.DISPLAY_PREFIX + "0010",
                    CustomerService.ABENDING_PROGRAM);
            assertThat(sink.lines()).doesNotContain(CustomerService.END_OF_EXECUTION);
        }

        @ParameterizedTest
        @CsvSource({"10,0010", "22,0022", "23,0023", "04,0004", "30,0030"})
        @DisplayName("every non-'00' close status reaches 12 through ADD 12 TO ZERO GIVING - L141-L142")
        void everyNonOkCloseStatusAbendsWithTwelve(String status, String image) {
            StubbedRun run = stubbedRun(FileStatus.OK, status);
            Sysout sink = new Sysout();

            AbendException abend = runExpectingAbend(run.subject(), sink);

            assertThat(abend.getReturnCode()).isEqualTo(12);
            assertThat(abend.getAbendCode()).hasValue(999);
            assertThat(abend.getTiming()).hasValue(0);
            assertThat(sink.lines()).containsExactly(
                    CustomerService.START_OF_EXECUTION,
                    CustomerService.ERROR_CLOSING_CUSTOMER_FILE,
                    FileStatus.DISPLAY_PREFIX + image,
                    CustomerService.ABENDING_PROGRAM);
        }

        @Test
        @DisplayName("the close text names the file, not the DD name - L147")
        void theCloseTextNamesTheFile() {
            StubbedRun run = stubbedRun(FileStatus.OK, FileStatus.NOT_FOUND);
            Sysout sink = new Sysout();

            runExpectingAbend(run.subject(), sink);

            assertThat(sink.lines().get(1))
                    .isEqualTo("ERROR CLOSING CUSTOMER FILE")
                    .contains("CUSTOMER FILE")
                    .doesNotContain(CustomerRepository.BATCH_DD_NAME);
        }

        @Test
        @DisplayName("the close is reached even for an empty dataset - L83 is unconditional")
        void theCloseIsReachedForAnEmptyDataset() {
            StubbedRun run = stubbedRun(FileStatus.OK, FileStatus.OK);

            run.subject().readAndPrintCustomerFile();

            Mockito.verify(run.file(), Mockito.times(1)).closeFile();
        }

        @Test
        @DisplayName("the transient 8 at L137 is overwritten on both arms, exactly as at L119")
        void theTransientEightIsOverwrittenOnBothArms() {
            Execution clean = stubbedRunOver(1).subject().readAndPrintCustomerFile();
            AbendException failed = runExpectingAbend(
                    stubbedRun(FileStatus.OK, FileStatus.DUPLICATE).subject(), new Sysout());

            assertThat(clean.returnCode()).isZero();
            assertThat(failed.getReturnCode()).isEqualTo(12);
            assertThat(failed.getReturnCode()).isNotEqualTo(CustomerService.APPL_RESULT_ASSUMED_FAILURE);
        }
    }

    @Nested
    @DisplayName("Z-ABEND-PROGRAM - L154-L158")
    class AbendProgram {
        private AbendException abendAt(String site, Sysout sink) {
            CustomerService subject = switch (site) {
                case "open" -> stubbedRun(FileStatus.NOT_FOUND, FileStatus.OK).subject();
                case "read" -> stubbedRun(FileStatus.OK, FileStatus.OK,
                        ReadResult.notFound()).subject();
                case "close" -> stubbedRun(FileStatus.OK, FileStatus.NOT_FOUND).subject();
                default -> throw new IllegalArgumentException("Unknown abend site: " + site);
            };
            return runExpectingAbend(subject, sink);
        }

        @ParameterizedTest
        @ValueSource(strings = {"open", "read", "close"})
        @DisplayName("all three sites raise the same CEE3ABD arguments - RC 12, ABCODE 999, TIMING 0")
        void everySiteCarriesTheSameCeeThreeAbdArguments(String site) {
            AbendException abend = abendAt(site, new Sysout());

            assertThat(abend.getProgram()).isEqualTo(CustomerService.PROGRAM_ID);
            assertThat(abend.getProgram()).isEqualTo("CBCUS01C");
            assertThat(abend.getReturnCode()).isEqualTo(12);
            assertThat(abend.hasAbendCode()).isTrue();
            assertThat(abend.getAbendCode()).hasValue(AbendException.STANDARD_ABEND_CODE);
            assertThat(abend.getAbendCode()).hasValue(999);
            assertThat(abend.hasTiming()).isTrue();
            assertThat(abend.getTiming()).hasValue(AbendException.STANDARD_TIMING);
            assertThat(abend.getTiming()).hasValue(0);
        }

        @ParameterizedTest
        @ValueSource(strings = {"open", "read", "close"})
        @DisplayName("no abend carries 0, 4, 8 or 16 - the reachable abend value is always 12")
        void noAbendCarriesZeroFourEightOrSixteen(String site) {
            AbendException abend = abendAt(site, new Sysout());

            assertThat(abend.getReturnCode()).isNotEqualTo(AbendException.RETURN_CODE_OK);
            assertThat(abend.getReturnCode()).isNotEqualTo(AbendException.RETURN_CODE_WARNING);
            assertThat(abend.getReturnCode()).isNotEqualTo(AbendException.RETURN_CODE_ASSUMED_FAILURE);
            assertThat(abend.getReturnCode()).isNotEqualTo(AbendException.RETURN_CODE_END_OF_FILE);
            assertThat(abend.getReturnCode()).isEqualTo(AbendException.RETURN_CODE_IO_ERROR);
        }

        @ParameterizedTest
        @ValueSource(strings = {"open", "read", "close"})
        @DisplayName("'ABENDING PROGRAM' is emitted BEFORE the termination, and is the final line")
        void theBannerPrecedesTheTermination(String site) {
            Sysout sink = new Sysout();

            abendAt(site, sink);

            List<String> lines = sink.lines();
            assertThat(lines.get(lines.size() - 1)).isEqualTo(CustomerService.ABENDING_PROGRAM);
            assertThat(lines.get(lines.size() - 2)).startsWith(FileStatus.DISPLAY_PREFIX);
            assertThat(CustomerService.ERROR_TEXTS).contains(lines.get(lines.size() - 3));
        }

        @Test
        @DisplayName("the banner is the shared literal, spelt once for the whole module - L155")
        void theBannerIsTheSharedLiteral() {
            assertThat(CustomerService.ABENDING_PROGRAM)
                    .isEqualTo(AbendException.ABEND_DISPLAY_TEXT)
                    .isEqualTo("ABENDING PROGRAM");
        }

        @Test
        @DisplayName("an abend leaves no closing banner, which is how an operator tells the ends apart")
        void anAbendHasNoClosingBanner() {
            Sysout sink = new Sysout();

            abendAt("read", sink);

            assertThat(sink.lines()).doesNotContain(CustomerService.END_OF_EXECUTION);
            assertThat(sink.lines().get(0)).isEqualTo(CustomerService.START_OF_EXECUTION);
        }

        @Test
        @DisplayName("the reason names the paragraph that failed, so three identical abends stay apart")
        void theReasonNamesTheFailingParagraph() {
            assertThat(abendAt("open", new Sysout()).getReason().orElseThrow())
                    .contains(CustomerService.ERROR_OPENING_CUSTFILE);
            assertThat(abendAt("read", new Sysout()).getReason().orElseThrow())
                    .contains(CustomerService.ERROR_READING_CUSTOMER_FILE);
            assertThat(abendAt("close", new Sysout()).getReason().orElseThrow())
                    .contains(CustomerService.ERROR_CLOSING_CUSTOMER_FILE);
        }
    }

    @Nested
    @DisplayName("Z-DISPLAY-IO-STATUS - L161-L174, a compound OR in three cases")
    class DisplayIoStatus {
        @Test
        @DisplayName("case 1: a status that is not numeric takes the first arm - L162")
        void aNonNumericStatusTakesTheFirstArm() {
            assertThat("B".getBytes(ASCII)[0]).isEqualTo((byte) 66);

            assertThat(FileStatus.toStatusImage("AB")).isEqualTo("A066");
            assertThat(FileStatus.toDisplayLine("AB")).isEqualTo("FILE STATUS IS: NNNNA066");

            Sysout sink = new Sysout();
            runExpectingAbend(
                    stubbedRun(FileStatus.OK, FileStatus.OK, ReadResult.of("AB")).subject(), sink);

            assertThat(sink.lines().get(2)).isEqualTo(FileStatus.DISPLAY_PREFIX + "A066");
        }

        @Test
        @DisplayName("case 2: a NUMERIC status beginning with '9' takes the first arm too - L163")
        void aNumericStatusBeginningWithNineTakesTheFirstArm() {
            assertThat("1".getBytes(ASCII)[0]).isEqualTo((byte) 49);

            assertThat(FileStatus.toStatusImage("91")).isEqualTo("9049");
            assertThat(FileStatus.toStatusImage("91")).isNotEqualTo("0091");
            assertThat(FileStatus.toDisplayLine("91")).isEqualTo("FILE STATUS IS: NNNN9049");

            Sysout sink = new Sysout();
            runExpectingAbend(
                    stubbedRun(FileStatus.OK, FileStatus.OK, ReadResult.of("91")).subject(), sink);

            assertThat(sink.lines().get(2)).isEqualTo(FileStatus.DISPLAY_PREFIX + "9049");
        }

        @ParameterizedTest
        @CsvSource({"00,0000", "04,0004", "10,0010", "22,0022", "23,0023", "35,0035", "89,0089"})
        @DisplayName("case 3: a numeric status not beginning with '9' takes the ELSE arm - L170-L171")
        void aNumericStatusNotBeginningWithNineTakesTheElseArm(String status, String image) {
            assertThat(FileStatus.toStatusImage(status)).isEqualTo(image);
            assertThat(FileStatus.toStatusImage(status)).isEqualTo("00" + status);
            assertThat(FileStatus.toDisplayLine(status)).isEqualTo("FILE STATUS IS: NNNN" + image);
        }

        @Test
        @DisplayName("the three statuses the ladder names render '0023', '0010' and '0022' exactly")
        void theNamedStatusesRenderByteExactly() {
            assertThat(FileStatus.toDisplayLine(FileStatus.NOT_FOUND))
                    .isEqualTo("FILE STATUS IS: NNNN0023");
            assertThat(FileStatus.toDisplayLine(FileStatus.END_OF_FILE))
                    .isEqualTo("FILE STATUS IS: NNNN0010");
            assertThat(FileStatus.toDisplayLine(FileStatus.DUPLICATE))
                    .isEqualTo("FILE STATUS IS: NNNN0022");
        }

        @Test
        @DisplayName("'NNNN' is a verbatim literal, not a placeholder for the digits that follow")
        void theLiteralNnnnIsVerbatim() {
            assertThat(FileStatus.DISPLAY_PREFIX).isEqualTo("FILE STATUS IS: NNNN");
            assertThat(FileStatus.DISPLAY_PREFIX).endsWith("NNNN");
            assertThat(FileStatus.DISPLAY_PREFIX).hasSize(20);

            String line = FileStatus.toDisplayLine(FileStatus.NOT_FOUND);
            assertThat(line).hasSize(24);
            assertThat(line).startsWith(FileStatus.DISPLAY_PREFIX);
            assertThat(line.substring(FileStatus.DISPLAY_PREFIX.length())).isEqualTo("0023");
        }

        @Test
        @DisplayName("IO-STATUS-04 is four characters - PIC 9 plus PIC 999 - at every status")
        void theImageIsAlwaysFourCharacters() {
            assertThat(FileStatus.STATUS_IMAGE_LENGTH).isEqualTo(4);
            for (String status : List.of("00", "04", "10", "22", "23", "91", "AB", "9A", "  ")) {
                assertThat(FileStatus.toStatusImage(status))
                        .as("the image of status '%s'", status)
                        .hasSize(FileStatus.STATUS_IMAGE_LENGTH);
                assertThat(FileStatus.toDisplayLine(status))
                        .as("the display line for status '%s'", status)
                        .hasSize(FileStatus.DISPLAY_PREFIX.length() + FileStatus.STATUS_IMAGE_LENGTH);
            }
        }

        @Test
        @DisplayName("all three arms are driven through the program's own three fatal paragraphs")
        void allThreeArmsAreDrivenThroughTheProgram() {
            Sysout readArm = new Sysout();
            Sysout openArm = new Sysout();
            Sysout closeArm = new Sysout();

            runExpectingAbend(stubbedRun(FileStatus.OK, FileStatus.OK,
                    ReadResult.of("91")).subject(), readArm);
            runExpectingAbend(stubbedRun("AB", FileStatus.OK).subject(), openArm);
            runExpectingAbend(stubbedRun(FileStatus.OK, FileStatus.NOT_FOUND).subject(), closeArm);

            assertThat(readArm.lines().get(2)).isEqualTo(FileStatus.DISPLAY_PREFIX + "9049");
            assertThat(openArm.lines().get(2)).isEqualTo(FileStatus.DISPLAY_PREFIX + "A066");
            assertThat(closeArm.lines().get(2)).isEqualTo(FileStatus.DISPLAY_PREFIX + "0023");
        }

        @Test
        @DisplayName("the renderer is reached with the operation's own status, never with an empty one")
        void theRendererIsNeverReachedWithoutAStatus() {
            WorkingStorage untouched = new WorkingStorage();

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(untouched::ioStatus)
                    .withMessageContaining("Z-DISPLAY-IO-STATUS");

            untouched.moveToIoStatus(FileStatus.NOT_FOUND);
            assertThat(untouched.ioStatus()).isEqualTo(FileStatus.NOT_FOUND);
            assertThat(FileStatus.toDisplayLine(untouched.ioStatus()))
                    .isEqualTo(FileStatus.DISPLAY_PREFIX + "0023");
        }
    }

    @Nested
    @DisplayName("What the program does not do is absent, and provably so")
    class MigrationConstraints {
        private static final String SERVICE_SOURCE =
                "app/java/src/main/java/com/vsergeychik/carddemo/customer/CustomerService.java";

        private static final String TEST_SOURCE =
                "app/java/src/test/java/com/vsergeychik/carddemo/customer/CustomerServiceTest.java";

        private List<Class<?>> serviceTypes() {
            List<Class<?>> types = new ArrayList<>();
            types.add(CustomerService.class);
            types.addAll(List.of(CustomerService.class.getDeclaredClasses()));
            return types;
        }

        private String compiledForm(Class<?> type) {
            String binaryName = type.getName();
            String simpleName = binaryName.substring(binaryName.lastIndexOf('.') + 1);
            try (InputStream stream = type.getResourceAsStream(simpleName + ".class")) {
                if (stream == null) {
                    throw new IllegalStateException("The compiled form of " + binaryName + " is absent "
                            + "from the test classpath, so its constant pool cannot be inspected");
                }
                return new String(stream.readAllBytes(), StandardCharsets.ISO_8859_1);
            } catch (IOException failure) {
                throw new UncheckedIOException(failure);
            }
        }

        @Test
        @DisplayName("no binary floating point and no fixed-point decimal type - gates G22 and G23")
        void carriesNoBinaryFloatingPointAndNoFixedPointDecimal() {
            Set<String> forbidden = Set.of("D", "F", "[D", "[F",
                    "Ljava/lang/Double;", "Ljava/lang/Float;", "Ljava/math/BigDecimal;");

            for (Class<?> type : serviceTypes()) {
                for (Field field : type.getDeclaredFields()) {
                    assertThat(field.getType().descriptorString())
                            .as("field %s.%s", type.getSimpleName(), field.getName())
                            .isNotIn(forbidden);
                }
                for (Method method : type.getDeclaredMethods()) {
                    assertThat(method.getReturnType().descriptorString())
                            .as("%s.%s returns", type.getSimpleName(), method.getName())
                            .isNotIn(forbidden);
                    for (Class<?> parameter : method.getParameterTypes()) {
                        assertThat(parameter.descriptorString())
                                .as("%s.%s takes", type.getSimpleName(), method.getName())
                                .isNotIn(forbidden);
                    }
                }
                for (Constructor<?> constructor : type.getDeclaredConstructors()) {
                    for (Class<?> parameter : constructor.getParameterTypes()) {
                        assertThat(parameter.descriptorString())
                                .as("a constructor of %s takes", type.getSimpleName())
                                .isNotIn(forbidden);
                    }
                }
            }
        }

        @Test
        @DisplayName("no rounding mode is referenced anywhere, because nothing here rounds - gate G24")
        void referencesNoRoundingMode() {
            for (Class<?> type : serviceTypes()) {
                assertThat(compiledForm(type))
                        .as("the compiled form of %s", type.getName())
                        .doesNotContain("HALF_UP")
                        .doesNotContain("HALF_EVEN")
                        .doesNotContain("CEILING")
                        .doesNotContain("RoundingMode")
                        .doesNotContain("java/math");
            }
        }

        @Test
        @DisplayName("no batch launcher and no HTTP type is reachable from the service - gate G51")
        void reachesNoBatchLauncherAndNoHttpLayer() {
            for (Class<?> type : serviceTypes()) {
                assertThat(compiledForm(type))
                        .as("the compiled form of %s", type.getName())
                        .doesNotContain("org/springframework/batch")
                        .doesNotContain("org/springframework/web")
                        .doesNotContain("JobLauncher")
                        .doesNotContain("JobRepository")
                        .doesNotContain("Tasklet")
                        .doesNotContain("StepContribution")
                        .doesNotContain("MockMvc")
                        .doesNotContain("HttpServlet");
            }
        }

        @Test
        @DisplayName("no dataset name is compiled into the service - gate G46")
        void compilesInNoDatasetName() {
            for (Class<?> type : serviceTypes()) {
                assertThat(compiledForm(type))
                        .as("the compiled form of %s", type.getName())
                        .doesNotContain("AWS.M2.CARDDEMO")
                        .doesNotContain("VSAM.KSDS");
            }
            assertThat(CustomerService.DD_NAME).isEqualTo(CustomerRepository.BATCH_DD_NAME);
            assertThat(CustomerService.DD_NAME).isEqualTo("CUSTFILE");
        }

        @Test
        @DisplayName("the service is constructor-injected and holds no static mutable state - G53, B9")
        void isConstructorInjectedAndHoldsNoStaticMutableState() {
            Constructor<?>[] constructors = CustomerService.class.getDeclaredConstructors();
            assertThat(constructors).hasSize(1);
            assertThat(constructors[0].getParameterCount()).isEqualTo(1);
            assertThat(constructors[0].getParameterTypes()[0]).isEqualTo(CustomerRepository.class);

            for (Field field : CustomerService.class.getDeclaredFields()) {
                if (field.isSynthetic()) {
                    continue;
                }
                assertThat(Modifier.isFinal(field.getModifiers()))
                        .as("field %s of the service must be final", field.getName())
                        .isTrue();
                if (Modifier.isStatic(field.getModifiers())) {
                    assertThat(field.getType().isArray())
                            .as("static field %s must not be a mutable array", field.getName())
                            .isFalse();
                }
                for (Annotation annotation : field.getAnnotations()) {
                    assertThat(annotation.annotationType().getSimpleName())
                            .as("field %s must not be injected", field.getName())
                            .isNotEqualTo("Autowired")
                            .isNotEqualTo("Inject")
                            .isNotEqualTo("Resource")
                            .isNotEqualTo("Value");
                }
            }
        }

        @Test
        @DisplayName("no import in the service or in this test is a wildcard - gate G52")
        void noImportIsAWildcard() {
            for (String source : List.of(SERVICE_SOURCE, TEST_SOURCE)) {
                List<String> wildcards = moduleSource(source).lines()
                        .map(String::strip)
                        .filter(line -> line.startsWith("import ") && line.endsWith(".*;"))
                        .toList();
                assertThat(wildcards).as("wildcard imports in %s", source).isEmpty();
            }
        }

        @Test
        @DisplayName("this suite opens none of the read-only reference trees - practice B3, gate G5")
        void opensNoneOfTheReferenceTrees() {
            assertThat(compiledForm(CustomerServiceTest.class))
                    .doesNotContain("app/cbl")
                    .doesNotContain("app/cpy")
                    .doesNotContain("app/jcl")
                    .doesNotContain("app/csd")
                    .doesNotContain("app/data");

            assertThat(FIXTURE).isEqualTo("/fixtures/custdata.txt");
            assertThat(FIXTURE).startsWith("/fixtures/");

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> moduleSource("README.md"))
                    .withMessageContaining("read-only");
        }

        @Test
        @DisplayName("this suite needs no Spring context, no launcher and no HTTP layer - gate G51")
        void needsNoSpringContext() {
            for (Annotation annotation : CustomerServiceTest.class.getAnnotations()) {
                assertThat(annotation.annotationType().getSimpleName())
                        .isNotEqualTo("SpringBootTest")
                        .isNotEqualTo("ExtendWith")
                        .isNotEqualTo("ContextConfiguration")
                        .isNotEqualTo("WebMvcTest")
                        .isNotEqualTo("SpringBatchTest");
            }
            for (Field field : CustomerServiceTest.class.getDeclaredFields()) {
                for (Annotation annotation : field.getAnnotations()) {
                    assertThat(annotation.annotationType().getSimpleName())
                            .as("field %s of this test", field.getName())
                            .isNotEqualTo("Autowired")
                            .isNotEqualTo("MockBean")
                            .isNotEqualTo("Mock");
                }
            }
            assertThat(new CustomerService(stubbedRepository())).isNotNull();
        }

        @Test
        @DisplayName("this test class holds no static mutable state either - practice B9")
        void thisTestClassHoldsNoStaticMutableState() {
            for (Field field : CustomerServiceTest.class.getDeclaredFields()) {
                if (field.isSynthetic()) {
                    continue;
                }
                if (!Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                assertThat(Modifier.isFinal(field.getModifiers()))
                        .as("static field %s must be final", field.getName())
                        .isTrue();
                assertThat(field.getType().isArray())
                        .as("static field %s must not be a mutable array", field.getName())
                        .isFalse();
                if (field.getType() == AtomicInteger.class) {
                    assertThat(field.getName())
                            .as("the only mutable static member is the database-name sequence")
                            .isEqualTo("DATABASE_SEQUENCE");
                    continue;
                }
                assertThat(field.getType())
                        .as("static field %s must be an immutable constant", field.getName())
                        .isIn(String.class, int.class, long.class, boolean.class, Charset.class);
            }
        }
    }

}
