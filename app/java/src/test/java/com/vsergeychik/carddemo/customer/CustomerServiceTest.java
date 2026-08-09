package com.vsergeychik.carddemo.customer;

import com.vsergeychik.carddemo.common.AbendException;
import com.vsergeychik.carddemo.common.FileStatus;
import com.vsergeychik.carddemo.common.RecordImageForm;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBinding;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBindings;
import com.vsergeychik.carddemo.customer.CustomerRepository.CustomerFile;
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
import org.junit.jupiter.params.provider.ValueSource;
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
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * Unit tests for {@link CustomerService}, the Java form of {@code app/cbl/CBCUS01C.cbl}.
 *
 * <h2>What this suite has to prove</h2>
 * {@code CBCUS01C} has a small risk surface and this suite is organised around it: the preserved
 * duplicate display, the {@code APPL-RESULT} status ladder, and the abend contract. Nothing else in the
 * program can go wrong - it has no arithmetic, no {@code EVALUATE}, no {@code GO TO}, no packed decimal
 * and no output dataset.
 *
 * <p>The headline expectation is <strong>102 lines, not 52</strong>. Fifty fixture records produce one
 * hundred record lines because {@code L96} and {@code L78} both display the same record in the same raw
 * group form. Several tests here would pass a de-duplicated implementation if they only counted records,
 * so they assert the pairing directly: for every record, two adjacent byte-identical 500-character lines.
 *
 * <h2>How the program is driven</h2>
 * Two levers, chosen per case:
 * <ul>
 *   <li>a <strong>real</strong> {@link CustomerRepository} over a private in-memory relation seeded from
 *       {@code /fixtures/custdata.txt}. This is the lever for the successful path, the empty dataset, and
 *       the three failure arms a real backend can actually produce - a refused describe for the open and
 *       the close, and a row with no record image for the read;</li>
 *   <li>a <strong>stubbed</strong> {@link CustomerFile} for the statuses a sequential browse of an input
 *       file cannot report. {@code '22'} and {@code '23'} are unreachable in production and gate G47 still
 *       requires them exercised at this call site, so they are injected rather than contrived.</li>
 * </ul>
 *
 * <p>No {@code MockMvc}, no application context, no batch launcher: the subject is constructed with a
 * repository and nothing else, which is the property gate G51 exists to protect.
 */
@DisplayName("CustomerService - CBCUS01C, read and print the customer data file")
class CustomerServiceTest {

    /** The code page of the ASCII fixtures, named explicitly and never taken from the platform. */
    private static final Charset ASCII = StandardCharsets.US_ASCII;

    /** The dataset name both bindings resolve to in these tests. */
    private static final String TEST_DSNAME = "TEST.CUSTOMER.KSDS";

    /** The single record-image column of the seeded relation. */
    private static final String RECORD_IMAGE_COLUMN = "REC";

    /** The shipped customer fixture, on the test classpath. */
    private static final String FIXTURE = "/fixtures/custdata.txt";

    /** How many records the fixture holds. */
    private static final int FIXTURE_RECORDS = 50;

    /** The line count a fifty-record run must emit: one banner, a hundred images, one banner. */
    private static final int FIXTURE_LINE_COUNT = 102;

    /** The declared record width, {@code CVCUS01Y}. */
    private static final int FIVE_HUNDRED = CustomerRecord.RECORD_LENGTH;

    /** The declared key width, {@code CUST-ID PIC 9(09)}. */
    private static final int NINE = CustomerRepository.KEY_LENGTH;

    /** One-based offset of the trailing {@code FILLER X(168)} in the record image. */
    private static final int FILLER_FIRST_BYTE = 333;

    /** Keeps every test's in-memory database private to it, so no test can see another's rows. */
    private static final AtomicInteger DATABASE_SEQUENCE = new AtomicInteger();

    // =============================================================================================
    // Fixtures and helpers.
    // =============================================================================================

    /**
     * The binding pair the repository requires: the CICS file name and the batch DD name, both naming
     * the same dataset at the copybook's width and key length.
     *
     * @return the catalogue
     */
    private static DatasetBindings bindings() {
        DatasetBindings catalogue = new DatasetBindings();
        catalogue.put(CustomerRepository.CICS_FILE_NAME, new DatasetBinding(TEST_DSNAME, "ksds", false,
                "FB", null, FIVE_HUNDRED, "CVCUS01Y", NINE, null, null, null));
        catalogue.put(CustomerRepository.BATCH_DD_NAME, new DatasetBinding(TEST_DSNAME, "ksds", false,
                "FB", null, FIVE_HUNDRED, "CVCUS01Y", NINE, null, null, null));
        return catalogue;
    }

    /**
     * A repository over the given template.
     *
     * @param template the template reaching the relation
     * @return the repository
     */
    private static CustomerRepository repository(JdbcTemplate template) {
        return new CustomerRepository(template, bindings(), ASCII, RecordImageForm.CHARACTER);
    }

    /**
     * The service over the given template.
     *
     * @param template the template reaching the relation
     * @return the subject
     */
    private static CustomerService service(JdbcTemplate template) {
        return new CustomerService(repository(template));
    }

    /**
     * A private in-memory database with no relation in it at all, so every describe is refused.
     *
     * @return a template over an empty database
     */
    private static JdbcTemplate emptyDatabase() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:custsvc" + DATABASE_SEQUENCE.incrementAndGet()
                        + ";DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE", "sa", "");
        dataSource.setDriverClassName("org.h2.Driver");
        return new JdbcTemplate(dataSource);
    }

    /**
     * A private in-memory relation with one record-image column, seeded with the given rows.
     *
     * @param rows the record images to insert, in the order given; a {@code null} entry seeds a row whose
     *             record image is absent, which is how the fatal read arm is driven
     * @return a template over the seeded relation
     */
    private static JdbcTemplate seeded(List<String> rows) {
        JdbcTemplate template = emptyDatabase();
        template.execute("CREATE TABLE \"" + TEST_DSNAME + "\" (" + RECORD_IMAGE_COLUMN
                + " VARCHAR(" + FIVE_HUNDRED + "))");
        for (String row : rows) {
            template.update("INSERT INTO \"" + TEST_DSNAME + "\" VALUES (?)", row);
        }
        return template;
    }

    /**
     * A template whose <em>first</em> describe is refused, so the open fails.
     *
     * @param real the template over the seeded relation
     * @return a spy that refuses its first describe
     */
    @SuppressWarnings("unchecked")
    private static JdbcTemplate refusingTheFirstDescribe(JdbcTemplate real) {
        JdbcTemplate spy = Mockito.spy(real);
        Mockito.doThrow(new DataAccessResourceFailureException(
                        "the customer master dataset is not addressable"))
                .when(spy).query(Mockito.anyString(), Mockito.any(ResultSetExtractor.class));
        return spy;
    }

    /**
     * A template whose <em>second</em> describe is refused, so the open succeeds and the close fails.
     *
     * <p>Only the {@code (String, ResultSetExtractor)} overload is stubbed, which both the open and the
     * close use and no read does, so the browse in between runs against the real relation.
     *
     * @param real the template over the seeded relation
     * @return a spy that refuses its second describe
     */
    @SuppressWarnings("unchecked")
    private static JdbcTemplate refusingTheSecondDescribe(JdbcTemplate real) {
        JdbcTemplate spy = Mockito.spy(real);
        Mockito.doCallRealMethod()
                .doThrow(new DataAccessResourceFailureException(
                        "the customer master dataset is no longer addressable"))
                .when(spy).query(Mockito.anyString(), Mockito.any(ResultSetExtractor.class));
        return spy;
    }

    /**
     * The 50 fixture records, exactly as stored.
     *
     * @return the fixture's lines
     */
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

    /**
     * A service whose file reports the given statuses from successive reads, with no backend at all.
     *
     * <p>The lever for statuses a real browse cannot produce. The repository and the file are both
     * stubbed; the {@code datasetCharset} answer is required because the service builds its codec from it
     * at construction.
     *
     * @param statuses the statuses successive {@code readNext} calls report
     * @return the subject
     */
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

    // =============================================================================================
    // The transcribed literals. Every one is byte-exact observable output.
    // =============================================================================================

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
            // L129 says CUSTFILE - the ASSIGN TO name. L110 and L147 say CUSTOMER FILE. The
            // inconsistency is the source's and is observable output.
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

    // =============================================================================================
    // Declared geometry - the 102-line arithmetic.
    // =============================================================================================

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

    // =============================================================================================
    // Construction.
    // =============================================================================================

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

    // =============================================================================================
    // The successful path, over the shipped fixture.
    // =============================================================================================

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
        @DisplayName("the image is the raw group, not a labelled rendering - CBCUS01C has no such paragraph")
        void theImageIsTheRawGroupAndNotLabelled() {
            List<String> records = service(seeded(fixtureRows()))
                    .readAndPrintCustomerFile().recordLines();

            // CBACT01C emits 'ACCT-ID                 :' style lines from 1100-DISPLAY-ACCT-RECORD.
            // CBCUS01C has no equivalent, so no line may carry a field label or a separator rule.
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

    // =============================================================================================
    // 0000-CUSTFILE-OPEN's fatal arm - L129-L132.
    // =============================================================================================

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
            // A permanent error is '9' plus a binary feedback byte, so Z-DISPLAY-IO-STATUS takes its
            // extended branch and renders '9000' rather than overlaying two characters onto '0000'.
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

    // =============================================================================================
    // 1000-CUSTFILE-GET-NEXT's fatal arm - L110-L113.
    // =============================================================================================

    @Nested
    @DisplayName("A failed read - 1000-CUSTFILE-GET-NEXT, L92-L116")
    class ReadFailure {

        /**
         * Rows whose first entry has no record image, which is a readable row that is not a readable
         * record - an I/O-level defect the repository reports as a permanent error rather than as an end
         * of file.
         *
         * @return the seed rows
         */
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

    // =============================================================================================
    // 9000-CUSTFILE-CLOSE's fatal arm - L147-L150.
    // =============================================================================================

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

    // =============================================================================================
    // Handle release. CBCUS01C has no statement for this: CEE3ABD ends a z/OS task and the operating
    // system reclaims its open files, whereas an AbendException ends one step inside a JVM that keeps
    // running. The release is therefore invisible - it must add no line and change no status.
    // =============================================================================================

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

    // =============================================================================================
    // The status ladder - L94-L103 - and the WHEN OTHER arm. Gate G47.
    // =============================================================================================

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

    // =============================================================================================
    // The two 88-level condition names - L62-L63. Gate G50 requires both states of each.
    // =============================================================================================

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

    // =============================================================================================
    // WORKING-STORAGE - L46-L67 - including the arithmetic spellings the close paragraph uses.
    // =============================================================================================

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

    // =============================================================================================
    // The vacuous guard at L75 - a real branch a real run cannot reach.
    // =============================================================================================

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
                // One from L96 inside the read, one from L78 in the mainline.
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

    // =============================================================================================
    // The returned fingerprint's own invariants.
    // =============================================================================================

    @Nested
    @DisplayName("Execution - the behavioural fingerprint")
    class ExecutionInvariants {

        @Test
        @DisplayName("a line count that does not match two per record is refused")
        void aDeDuplicatedFingerprintIsRejected() {
            // 50 records with only one line each - what a de-duplicated implementation would produce.
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

    // =============================================================================================
    // The SYSOUT sink's own guards.
    // =============================================================================================

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

    // =============================================================================================
    // What reaches the application log, and what deliberately does not.
    // =============================================================================================

    /**
     * The service's logging is split: control lines go to the log verbatim, record images do not.
     *
     * <p>This is a security property, not a style choice, so it is asserted rather than documented and
     * hoped for. A {@code CUSTOMER-RECORD} carries {@code CUST-SSN}, {@code CUST-DOB-YYYY-MM-DD},
     * {@code CUST-GOVT-ISSUED-ID} and the customer's names, and the application log is read by more
     * people, kept for longer and guarded less than the dataset those values came from (CWE-532).
     *
     * <p>Parity is unaffected, which the first test here proves by comparing the returned sequence against
     * the fixture while the log is being captured: {@code SYSOUT} is what the COBOL produces and
     * {@code SYSOUT} is what the return value carries, byte for byte. The application log is not an
     * artefact {@code CBCUS01C} has at all.
     *
     * <p>Debug is enabled for the duration of each test and restored afterwards, so no other suite sees a
     * changed level. Tests run sequentially - the module configures no parallel execution - so the
     * restore is sufficient.
     */
    @Nested
    @DisplayName("The application log discloses no customer data - CWE-532")
    class LogDisclosure {

        /** The logger the service writes through. */
        private ch.qos.logback.classic.Logger serviceLogger() {
            return (ch.qos.logback.classic.Logger)
                    org.slf4j.LoggerFactory.getLogger(CustomerService.class);
        }

        /**
         * Runs the fixture with the service's logger at {@code DEBUG} and a capturing appender attached,
         * then restores both.
         *
         * @return the captured events' rendered messages, in order
         */
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

            // Two per record, because the duplicate display is preserved.
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
            // The level is forced rather than assumed. Whether DEBUG happens to be on depends on which
            // suites ran before this one, and a branch whose coverage depends on that is not covered at
            // all - so both arms of the guard are driven explicitly, here and in the test above.
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
            // The observable output is untouched by the log level, which is the whole point.
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
                // Position 0 is the banner, so record n's first image is at 1 + 2n.
                assertThat(sysout.get(1 + index * 2)).isEqualTo(expected.get(index));
                assertThat(sysout.get(2 + index * 2)).isEqualTo(expected.get(index));
            }
        }
    }
    /**
     * DBP-05. Streaming SYSOUT: the production shape, which emits every line and keeps none of them.
     *
     * <p>The finding was that the complete {@code CBCUS01C} SYSOUT was retained in an {@code ArrayList}
     * and copied, with two 500-character records per customer and no record ceiling on the dataset. The
     * fix adds a streaming destination; what these tests have to establish is that adding it changed
     * nothing observable, because the emitted sequence <em>is</em> the parity contract.
     */
    @Nested
    @DisplayName("DBP-05: a streaming sink emits everything and retains nothing")
    class StreamingSysout {

        @Test
        @DisplayName("the streamed sequence is byte-identical to the captured one, in the same order")
        void theStreamedSequenceIsTheCapturedSequence() {
            List<String> captured = service(seeded(fixtureRows())).readAndPrintCustomerFile().sysout();

            List<String> streamed = new ArrayList<>();
            service(seeded(fixtureRows())).readAndPrintCustomerFileTo(streamed::add);

            // Not "has the same size" and not "contains the same elements": exactly equal, in order.
            // Every banner, every image and the preserved L96/L78 duplicate pair, byte for byte.
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

            // Refused up front. Letting it run would emit all 102 lines and only then discover, at the
            // Execution construction, that there is no sequence to build one from - after the side effect.
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

            // No timestamp, no severity, no logger name, no trimming - a parity case compares these bytes.
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
                    () -> CustomerService.standardOutputSysoutSink().write(null));
        }

        @Test
        @DisplayName("the SYSOUT=* default is a sink over the standard output stream")
        void theDefaultIsOverStandardOutput() {
            SysoutSink sink = CustomerService.standardOutputSysoutSink();

            assertThat(sink).isInstanceOf(PrintStreamSysoutSink.class);
            assertThat(((PrintStreamSysoutSink) sink).stream()).isSameAs(System.out);
        }
    }

}
