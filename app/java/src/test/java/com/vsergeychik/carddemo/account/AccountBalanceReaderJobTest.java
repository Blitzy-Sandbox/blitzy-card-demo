package com.vsergeychik.carddemo.account;

import com.vsergeychik.carddemo.account.AccountBalanceReaderJob.PrintStreamSysoutSink;
import com.vsergeychik.carddemo.account.AccountBalanceReaderJob.SysoutSink;
import com.vsergeychik.carddemo.card.CardRepository;
import com.vsergeychik.carddemo.card.CardRepository.BrowseDirection;
import com.vsergeychik.carddemo.card.CardRepository.CardBrowse;
import com.vsergeychik.carddemo.card.CardRepository.CardReadResult;
import com.vsergeychik.carddemo.card.model.CardRecord;
import com.vsergeychik.carddemo.common.AbendException;
import com.vsergeychik.carddemo.common.FileStatus;
import com.vsergeychik.carddemo.config.BatchConfig;
import com.vsergeychik.carddemo.config.BatchConfig.JobContract;
import com.vsergeychik.carddemo.config.BatchConfig.JobParameterContract;
import com.vsergeychik.carddemo.config.BatchConfig.StepContract;
import com.vsergeychik.carddemo.config.CobolCharsetConfig;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBinding;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBindings;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.PlatformTransactionManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AccountBalanceReaderJob}, the translation of {@code app/cbl/CBACT02C.cbl}.
 *
 * <p>Every test runs with <strong>no application context, no {@code JobLauncher}, no HTTP and no
 * backend</strong>. The job's whole {@code PROCEDURE DIVISION} is reachable as
 * {@link AccountBalanceReaderJob#execute(SysoutSink)} over a collecting sink, which is what lets the
 * emitted line sequence be asserted directly rather than inferred from an exit status - and what
 * makes the mandated per-package branch bar reachable deterministically.
 *
 * <h2>The assertion that matters most</h2>
 * <p>{@code CBACT02C} emits <strong>one</strong> line per record, because its inner
 * {@code DISPLAY CARD-RECORD} at {@code app/cbl/CBACT02C.cbl:96} is commented out and the program has
 * no {@code 1100-DISPLAY-…} paragraph at all. Fed the fifty-record fixture the job must therefore
 * write exactly fifty-two lines: one start banner, fifty {@value CardRecord#RECORD_LENGTH}-character
 * record images, one end banner. A run producing six hundred and fifty-two lines has copied the
 * sibling account reader's field-by-field rendering, and {@link RecordDisplay} exists to fail loudly
 * if that ever happens.
 *
 * <h2>Where the data comes from</h2>
 * <p>{@code src/test/resources/fixtures/carddata.txt}, verified byte-identical to
 * {@code app/data/ASCII/carddata.txt}: fifty records, every one exactly
 * {@value CardRecord#RECORD_LENGTH} bytes. Records are decoded from those rows and handed back
 * through a stubbed browse, so the images the job emits are compared against real production-shaped
 * data rather than against values invented here.
 *
 * <h2>User-specified rules</h2>
 * <p>{@code review_rules} returns exactly one line - "No user rules provided." - so no user rule
 * governs this file. The migration plan's practices bind instead; B10 is the one that puts this file
 * here, because tests are authored with the code rather than after it.
 */
@DisplayName("AccountBalanceReaderJob - CBACT02C, which reads and prints the CARD file")
class AccountBalanceReaderJobTest {

    /** The fifty-record card fixture, byte-identical to {@code app/data/ASCII/carddata.txt}. */
    private static final String FIXTURE = "/fixtures/carddata.txt";

    /** The fixture's code page, and the one {@code application-test.yml} selects for datasets. */
    private static final Charset FIXTURE_CHARSET = StandardCharsets.US_ASCII;

    /** How many records the fixture holds. */
    private static final int FIXTURE_RECORD_COUNT = 50;

    /** One start banner, fifty record images, one end banner. */
    private static final int EXPECTED_LINE_COUNT = FIXTURE_RECORD_COUNT + 2;

    /**
     * The line count a run would produce had the sibling account reader's thirteen-line-per-record
     * rendering been copied here. Asserted against explicitly, because it is the specific wrong
     * answer this program invites.
     */
    private static final int SIBLING_THIRTEEN_LINE_COUNT = FIXTURE_RECORD_COUNT * 13 + 2;

    /** The status line a permanent error renders as, through the shared status renderer. */
    private static final String PERMANENT_ERROR_LINE =
            FileStatus.toDisplayLine(AccountBalanceReaderJob.PERMANENT_ERROR_STATUS);

    // =============================================================================================
    // Fixtures and test stubs.
    // =============================================================================================

    /**
     * The fifty fixture rows, each exactly {@value CardRecord#RECORD_LENGTH} characters.
     *
     * @return the rows in file order
     */
    private static List<String> fixtureRows() {
        List<String> rows = new ArrayList<>();
        try (InputStream stream = AccountBalanceReaderJobTest.class.getResourceAsStream(FIXTURE)) {
            assertThat(stream).as("the fixture %s must be on the test classpath", FIXTURE).isNotNull();
            try (BufferedReader reader =
                         new BufferedReader(new InputStreamReader(stream, FIXTURE_CHARSET))) {
                String row = reader.readLine();
                while (row != null) {
                    rows.add(row);
                    row = reader.readLine();
                }
            }
        } catch (IOException unreadable) {
            throw new UncheckedIOException("Could not read " + FIXTURE, unreadable);
        }
        assertThat(rows).hasSize(FIXTURE_RECORD_COUNT);
        return rows;
    }

    /**
     * The fifty fixture rows decoded into records.
     *
     * @return the records in file order
     */
    private static List<CardRecord> fixtureRecords() {
        return fixtureRows().stream()
                .map(row -> CardRecord.decodeImage(row, FIXTURE_CHARSET))
                .toList();
    }

    /**
     * A sink that keeps every line it is given, in order.
     *
     * <p>Order is the whole point: a job that emits the right lines in the wrong order has not
     * reproduced the program, so the collector must not sort, deduplicate or coalesce.
     */
    private static final class CollectingSink implements SysoutSink {

        /** The lines, in the order they were written. */
        private final List<String> lines = new ArrayList<>();

        @Override
        public void write(String line) {
            lines.add(line);
        }

        /**
         * @return the collected lines, in order
         */
        private List<String> lines() {
            return lines;
        }
    }

    /**
     * An {@link ObjectProvider} over a single optional value.
     *
     * <p>Written out rather than mocked because {@code ObjectProvider} declares no abstract method -
     * every one of its members is a default - so it is neither a functional interface nor worth
     * stubbing method by method. Overriding {@code getObject()} is sufficient: the
     * {@code getIfAvailable(Supplier)} the production code calls resolves through it, and reports the
     * value as absent exactly when a bean lookup would.
     *
     * @param <T> the provided type
     */
    private static final class SingleValueProvider<T> implements ObjectProvider<T> {

        /** The value to provide, or {@code null} to behave as though no bean were published. */
        private final T value;

        /**
         * @param value the value, or {@code null} for "no such bean"
         */
        private SingleValueProvider(T value) {
            this.value = value;
        }

        @Override
        public T getObject() throws BeansException {
            if (value == null) {
                throw new NoSuchBeanDefinitionException("no bean published for this test");
            }
            return value;
        }

        @Override
        public Stream<T> stream() {
            return value == null ? Stream.empty() : Stream.of(value);
        }
    }

    /**
     * The card master's dataset binding as {@code application.yml} declares it for {@code CARDFILE}.
     *
     * @param dsname the dataset name to declare
     * @return the binding
     */
    private static DatasetBinding cardfileBinding(String dsname) {
        return new DatasetBinding(dsname, "ksds", false, "FB", null, CardRecord.RECORD_LENGTH,
                "CVACT02Y", CardRecord.CARD_NUM_LENGTH, null, null, null);
    }

    /**
     * The dataset catalogue, holding just the DD name this job resolves.
     *
     * @param dsname the dataset name to declare for {@value AccountBalanceReaderJob#DD_NAME}
     * @return the catalogue
     */
    private static DatasetBindings datasetBindings(String dsname) {
        DatasetBindings bindings = new DatasetBindings();
        bindings.put(AccountBalanceReaderJob.DD_NAME, cardfileBinding(dsname));
        return bindings;
    }

    /**
     * This job's contract exactly as {@code application.yml} declares it: one step, no parameters.
     *
     * @return the contract
     */
    private static JobContract sourceDerivedContract() {
        return new JobContract(AccountBalanceReaderJob.PROGRAM_ID, List.of(),
                List.of(new StepContract(AccountBalanceReaderJob.STEP_NAME,
                        AccountBalanceReaderJob.PROGRAM_ID, false)),
                null, Map.of());
    }

    /**
     * A real {@link BatchConfig} over stubbed infrastructure, so the builder seams the job uses are
     * the production ones rather than mocks of them.
     *
     * @param contract the contract to publish under this job's key
     * @param dsname   the dataset name to declare for {@value AccountBalanceReaderJob#DD_NAME}
     * @return the scaffolding
     */
    private static BatchConfig batchConfig(JobContract contract, String dsname) {
        BatchConfig.JobContracts contracts = new BatchConfig.JobContracts();
        contracts.put(AccountBalanceReaderJob.JOB_KEY, contract);
        return new BatchConfig(
                new SingleValueProvider<>(mock(JobRepository.class)),
                new SingleValueProvider<>(mock(PlatformTransactionManager.class)),
                contracts,
                datasetBindings(dsname));
    }

    /**
     * The job under test, wired over a stubbed repository and no published sink.
     *
     * @param cardRepository the repository to read through
     * @return the job
     */
    private static AccountBalanceReaderJob jobOver(CardRepository cardRepository) {
        return new AccountBalanceReaderJob(
                batchConfig(sourceDerivedContract(), "CARDDEMO.TEST.CARDDATA.VSAM.KSDS"),
                cardRepository,
                FIXTURE_CHARSET,
                new SingleValueProvider<>(null));
    }

    /**
     * A repository whose forward browse returns each supplied result in turn.
     *
     * @param results the results the browse reports, in order
     * @return the stubbed repository
     */
    private static CardRepository repositoryReturning(List<CardReadResult> results) {
        CardRepository repository = mock(CardRepository.class);
        CardBrowse browse = mock(CardBrowse.class);
        when(repository.startBrowse(any(), any())).thenReturn(browse);
        if (results.size() == 1) {
            when(browse.readNext()).thenReturn(results.get(0));
        } else {
            when(browse.readNext()).thenReturn(results.get(0),
                    results.subList(1, results.size()).toArray(CardReadResult[]::new));
        }
        return repository;
    }

    /**
     * A repository that walks every fixture record and then reports the end of the file.
     *
     * @return the stubbed repository
     */
    private static CardRepository repositoryOverWholeFixture() {
        List<CardReadResult> results = new ArrayList<>(
                fixtureRecords().stream().map(CardReadResult::normal).toList());
        results.add(CardReadResult.endOfFile());
        return repositoryReturning(results);
    }

    /**
     * Runs the job over a stubbed repository and returns everything it wrote.
     *
     * @param cardRepository the repository to read through
     * @return the emitted lines, in order
     */
    private static List<String> linesFrom(CardRepository cardRepository) {
        CollectingSink sink = new CollectingSink();
        jobOver(cardRepository).execute(sink);
        return sink.lines();
    }

    // =============================================================================================
    // Identity - every constant is transcribed from the source or the JCL.
    // =============================================================================================

    @Nested
    @DisplayName("Identity, taken from the source and the JCL")
    class Identity {

        @Test
        @DisplayName("the program, job key, job name, step name and DD name are the source-derived ones")
        void identityMatchesTheSource() {
            assertThat(AccountBalanceReaderJob.PROGRAM_ID)
                    .as("app/cbl/CBACT02C.cbl:23 PROGRAM-ID")
                    .isEqualTo("CBACT02C");
            assertThat(AccountBalanceReaderJob.JOB_KEY)
                    .as("the carddemo.jobs key the catalogue pairs with CBACT02C")
                    .isEqualTo("account-balance-reader-job");
            assertThat(AccountBalanceReaderJob.JOB_NAME).isEqualTo("accountBalanceReaderJob");
            assertThat(AccountBalanceReaderJob.STEP_NAME)
                    .as("app/jcl/READCARD.jcl:22 //STEP05 EXEC PGM=CBACT02C")
                    .isEqualTo("STEP05");
            assertThat(AccountBalanceReaderJob.DD_NAME)
                    .as("app/cbl/CBACT02C.cbl:29 ASSIGN TO CARDFILE - the CARD file, not the account "
                            + "file, whatever this class is named")
                    .isEqualTo("CARDFILE");
        }

        @Test
        @DisplayName("the configuration's bean name differs from the job bean's, or the context cannot start")
        void theConfigurationBeanNameIsDistinctFromTheJobBeanName() {
            // Spring would name this configuration after its decapitalised class name, which is
            // exactly the name the @Bean Job method claims. Two definitions of one name is refused,
            // because bean-definition overriding is disabled by default.
            String naturalName = "accountBalanceReaderJob";
            assertThat(AccountBalanceReaderJob.JOB_NAME).isEqualTo(naturalName);
            assertThat(AccountBalanceReaderJob.CONFIGURATION_BEAN_NAME).isNotEqualTo(naturalName);
        }

        @Test
        @DisplayName("the banners and the three error texts are byte-exact and all say CARDFILE")
        void displayLiteralsAreByteExact() {
            assertThat(AccountBalanceReaderJob.START_BANNER)
                    .isEqualTo("START OF EXECUTION OF PROGRAM CBACT02C");
            assertThat(AccountBalanceReaderJob.END_BANNER)
                    .isEqualTo("END OF EXECUTION OF PROGRAM CBACT02C");
            // The sibling account reader says ACCTFILE for its open and switches to the spaced form
            // "ACCOUNT FILE" for its read and close. This program says CARDFILE in all three, with
            // no space, and normalising that would change observable output.
            assertThat(AccountBalanceReaderJob.OPEN_ERROR_TEXT).isEqualTo("ERROR OPENING CARDFILE");
            assertThat(AccountBalanceReaderJob.READ_ERROR_TEXT).isEqualTo("ERROR READING CARDFILE");
            assertThat(AccountBalanceReaderJob.CLOSE_ERROR_TEXT).isEqualTo("ERROR CLOSING CARDFILE");
        }

        @Test
        @DisplayName("the APPL-RESULT ladder values are 8, 12, and the shared 0 and 16 conditions")
        void applResultLadderValues() {
            assertThat(AccountBalanceReaderJob.APPL_RESULT_ASSUMED_FAILURE)
                    .as("MOVE 8 / ADD 8 TO ZERO GIVING - app/cbl/CBACT02C.cbl:119, :137")
                    .isEqualTo(8);
            assertThat(AccountBalanceReaderJob.APPL_RESULT_FATAL)
                    .as("MOVE 12 - app/cbl/CBACT02C.cbl:101, :124, :142")
                    .isEqualTo(12);
            assertThat(FileStatus.APPL_AOK).as("88 APPL-AOK VALUE 0 - :62").isZero();
            assertThat(FileStatus.APPL_EOF).as("88 APPL-EOF VALUE 16 - :63").isEqualTo(16);
        }

        @Test
        @DisplayName("END-OF-FILE's two reachable values are 'N' and 'Y'")
        void endOfFileValues() {
            assertThat(AccountBalanceReaderJob.END_OF_FILE_NO)
                    .as("VALUE 'N' - app/cbl/CBACT02C.cbl:65").isEqualTo('N');
            assertThat(AccountBalanceReaderJob.END_OF_FILE_YES)
                    .as("MOVE 'Y' - app/cbl/CBACT02C.cbl:108, the only MOVE to that field")
                    .isEqualTo('Y');
        }

        @Test
        @DisplayName("a sequential read starts from a key of CARD-NUM-width spaces")
        void lowestKeyIsSpacesAtTheDeclaredWidth() {
            assertThat(AccountBalanceReaderJob.LOWEST_CARD_NUMBER_KEY)
                    .hasSize(CardRecord.CARD_NUM_LENGTH)
                    .isBlank();
        }

        @Test
        @DisplayName("the permanent-error status is '9' plus a binary feedback byte, and renders as 9000")
        void permanentErrorStatusFollowsTheCobolConvention() {
            assertThat(AccountBalanceReaderJob.PERMANENT_ERROR_STATUS)
                    .hasSize(FileStatus.STATUS_LENGTH)
                    .startsWith("9");
            // app/cbl/CBACT02C.cbl:162-163 tests IF IO-STATUS NOT NUMERIC OR IO-STAT1 = '9' before
            // rendering, so this value takes the extended arm and prints four digits.
            assertThat(PERMANENT_ERROR_LINE).isEqualTo(FileStatus.DISPLAY_PREFIX + "9000");
        }

        @Test
        @DisplayName("the charset bean name is the one the card repository resolves, so both read one bean")
        void charsetBeanNameAgreesWithTheRepository() {
            assertThat(CardRepository.DATASET_CHARSET_BEAN_NAME)
                    .isEqualTo(CobolCharsetConfig.DATASET_CHARSET_BEAN_NAME);
        }
    }

    // =============================================================================================
    // THE CENTRAL PARITY ASSERTION: one line per record, fifty-two lines in total.
    // =============================================================================================

    @Nested
    @DisplayName("Record display - one line per record, because :96 is a comment")
    class RecordDisplay {

        @Test
        @DisplayName("the fifty-record fixture yields exactly 52 lines, not 652")
        void wholeFixtureYieldsFiftyTwoLines() {
            List<String> lines = linesFrom(repositoryOverWholeFixture());

            assertThat(lines)
                    .as("app/cbl/CBACT02C.cbl:96 is the comment '*        DISPLAY CARD-RECORD', and "
                            + "the program has no 1100-DISPLAY paragraph, so a record produces ONE "
                            + "line - the :78 record image. %d lines would mean the sibling account "
                            + "reader's thirteen-line rendering was copied here, which fails all "
                            + "twenty of this program's parity cases at once.",
                            SIBLING_THIRTEEN_LINE_COUNT)
                    .hasSize(EXPECTED_LINE_COUNT);
            assertThat(lines.size()).isNotEqualTo(SIBLING_THIRTEEN_LINE_COUNT);
        }

        @Test
        @DisplayName("the first line is the start banner and the last is the end banner")
        void bannersBracketTheOutput() {
            List<String> lines = linesFrom(repositoryOverWholeFixture());

            assertThat(lines.get(0)).isEqualTo(AccountBalanceReaderJob.START_BANNER);
            assertThat(lines.get(lines.size() - 1)).isEqualTo(AccountBalanceReaderJob.END_BANNER);
        }

        @Test
        @DisplayName("every record line is the fixture row itself, byte for byte and 150 wide")
        void recordLinesAreTheFixtureRowsExactly() {
            List<String> rows = fixtureRows();
            List<String> lines = linesFrom(repositoryOverWholeFixture());
            List<String> emitted = lines.subList(1, lines.size() - 1);

            assertThat(emitted).hasSameSizeAs(rows);
            for (int i = 0; i < rows.size(); i++) {
                assertThat(emitted.get(i))
                        .as("record %d: PIC X spans space-padded, PIC 9 spans zero-filled and the "
                                + "trailing FILLER X(59) emitted as spaces (gates G19 and G21)", i + 1)
                        .hasSize(CardRecord.RECORD_LENGTH)
                        .isEqualTo(rows.get(i));
            }
        }

        @Test
        @DisplayName("no field-label line is ever emitted - there is no 1100 paragraph to emit one")
        void noFieldLabelLineIsEverEmitted() {
            List<String> lines = linesFrom(repositoryOverWholeFixture());

            assertThat(lines).noneMatch(line -> line.startsWith("CARD-"));
            assertThat(lines).noneMatch(line -> line.contains("CARD-NUM"))
                    .noneMatch(line -> line.contains("CARD-ACCT-ID"))
                    .noneMatch(line -> line.contains("CARD-CVV-CD"))
                    .noneMatch(line -> line.contains("CARD-EMBOSSED-NAME"))
                    .noneMatch(line -> line.contains("CARD-EXPIRAION-DATE"))
                    .noneMatch(line -> line.contains("CARD-ACTIVE-STATUS"));
        }

        @Test
        @DisplayName("records are emitted in the order the browse returns them")
        void orderIsPreserved() {
            List<String> rows = fixtureRows();
            List<String> lines = linesFrom(repositoryOverWholeFixture());

            assertThat(lines.subList(1, lines.size() - 1)).containsExactlyElementsOf(rows);
        }

        @Test
        @DisplayName("an empty file emits the two banners and nothing between them")
        void emptyFileEmitsOnlyTheBanners() {
            List<String> lines = linesFrom(repositoryReturning(List.of(CardReadResult.endOfFile())));

            assertThat(lines).containsExactly(
                    AccountBalanceReaderJob.START_BANNER,
                    AccountBalanceReaderJob.END_BANNER);
        }

        @ParameterizedTest(name = "{0} record(s) yields {0} + 2 lines")
        @ValueSource(ints = {1, 2, 7, 49, FIXTURE_RECORD_COUNT})
        @DisplayName("the line count is always the record count plus the two banners")
        void lineCountIsRecordCountPlusTwo(int recordCount) {
            List<CardReadResult> results = new ArrayList<>(
                    fixtureRecords().subList(0, recordCount).stream()
                            .map(CardReadResult::normal).toList());
            results.add(CardReadResult.endOfFile());

            assertThat(linesFrom(repositoryReturning(results))).hasSize(recordCount + 2);
        }

        @Test
        @DisplayName("the browse is positioned forward over the base cluster, never the alternate index")
        void browseIsForwardOverTheBaseCluster() {
            CardRepository repository = repositoryOverWholeFixture();

            linesFrom(repository);

            verify(repository).startBrowse(AccountBalanceReaderJob.LOWEST_CARD_NUMBER_KEY,
                    BrowseDirection.FORWARD);
            verify(repository, never()).readByAccountIdViaAltIndex(any(String.class));
            verify(repository, never()).readByAccountIdViaAltIndex(eq(0L));
        }
    }

    // =============================================================================================
    // 1000-CARDFILE-GET-NEXT - the read ladder, every arm.
    // =============================================================================================

    @Nested
    @DisplayName("1000-CARDFILE-GET-NEXT - the read status ladder")
    class ReadLadder {

        @Test
        @DisplayName("status '00' continues and the record is displayed - the APPL-AOK arm")
        void okContinuesAndDisplays() {
            CardRecord first = fixtureRecords().get(0);

            List<String> lines = linesFrom(repositoryReturning(
                    List.of(CardReadResult.normal(first), CardReadResult.endOfFile())));

            assertThat(lines).containsExactly(
                    AccountBalanceReaderJob.START_BANNER,
                    first.encodeToImage(FIXTURE_CHARSET),
                    AccountBalanceReaderJob.END_BANNER);
        }

        @Test
        @DisplayName("status '10' ends the loop cleanly and displays nothing - the APPL-EOF arm")
        void endOfFileEndsTheLoop() {
            List<String> lines = linesFrom(repositoryReturning(List.of(CardReadResult.endOfFile())));

            assertThat(lines).doesNotContain(AccountBalanceReaderJob.READ_ERROR_TEXT)
                    .doesNotContain(AbendException.ABEND_DISPLAY_TEXT)
                    .endsWith(AccountBalanceReaderJob.END_BANNER);
        }

        @Test
        @DisplayName("a status that is neither '00' nor '10' reports, prints the status and abends")
        void notFoundAbends() {
            CollectingSink sink = new CollectingSink();
            CardRepository repository = repositoryReturning(List.of(CardReadResult.notFound()));

            AbendException abend = assertThatAbend(repository, sink);

            assertThat(sink.lines()).containsExactly(
                    AccountBalanceReaderJob.START_BANNER,
                    AccountBalanceReaderJob.READ_ERROR_TEXT,
                    FileStatus.toDisplayLine(FileStatus.NOT_FOUND),
                    AbendException.ABEND_DISPLAY_TEXT);
            assertThat(abend.getReturnCode()).isEqualTo(AccountBalanceReaderJob.APPL_RESULT_FATAL);
        }

        @Test
        @DisplayName("a duplicate-key read is a failure here too, and its record is never displayed")
        void duplicateKeyAbendsWithoutDisplaying() {
            CardRecord first = fixtureRecords().get(0);
            CollectingSink sink = new CollectingSink();
            CardRepository repository =
                    repositoryReturning(List.of(CardReadResult.duplicateKey(first)));

            assertThatAbend(repository, sink);

            assertThat(sink.lines())
                    .as("the record is moved into CARD-RECORD by READ ... INTO, but the program "
                            + "abends before the mainline could ever display it")
                    .doesNotContain(first.encodeToImage(FIXTURE_CHARSET))
                    .containsExactly(
                            AccountBalanceReaderJob.START_BANNER,
                            AccountBalanceReaderJob.READ_ERROR_TEXT,
                            FileStatus.toDisplayLine(FileStatus.DUPLICATE),
                            AbendException.ABEND_DISPLAY_TEXT);
        }

        @ParameterizedTest(name = "CICS response {0} has no batch status and renders as 9000")
        @CsvSource({"22", "19", "16"})
        @DisplayName("a response with no two-character equivalent takes the MOVE 12 arm and prints 9000")
        void responsesWithoutABatchStatusRenderAsPermanentError(int cicsResp) {
            // A length error, an unreachable dataset and an otherwise invalid request map to no
            // two-character status at all. The repository reports that absence truthfully, so this
            // job supplies the COBOL permanent-error convention rather than fabricating a status.
            assertThat(FileStatus.batchStatusOfCicsResp(cicsResp)).isEmpty();
            CollectingSink sink = new CollectingSink();
            CardRepository repository =
                    repositoryReturning(List.of(CardReadResult.failed(cicsResp)));

            AbendException abend = assertThatAbend(repository, sink);

            assertThat(sink.lines()).containsExactly(
                    AccountBalanceReaderJob.START_BANNER,
                    AccountBalanceReaderJob.READ_ERROR_TEXT,
                    PERMANENT_ERROR_LINE,
                    AbendException.ABEND_DISPLAY_TEXT);
            assertThat(abend.getReturnCode()).isEqualTo(AccountBalanceReaderJob.APPL_RESULT_FATAL);
        }

        @Test
        @DisplayName("a read failure part-way through keeps the records already displayed")
        void aLateFailureKeepsTheEarlierLines() {
            List<CardRecord> records = fixtureRecords();
            CollectingSink sink = new CollectingSink();
            CardRepository repository = repositoryReturning(List.of(
                    CardReadResult.normal(records.get(0)),
                    CardReadResult.normal(records.get(1)),
                    CardReadResult.notFound()));

            assertThatAbend(repository, sink);

            assertThat(sink.lines()).containsExactly(
                    AccountBalanceReaderJob.START_BANNER,
                    records.get(0).encodeToImage(FIXTURE_CHARSET),
                    records.get(1).encodeToImage(FIXTURE_CHARSET),
                    AccountBalanceReaderJob.READ_ERROR_TEXT,
                    FileStatus.toDisplayLine(FileStatus.NOT_FOUND),
                    AbendException.ABEND_DISPLAY_TEXT);
            assertThat(sink.lines()).doesNotContain(AccountBalanceReaderJob.END_BANNER);
        }

        @Test
        @DisplayName("the file is not closed and the end banner is not written when a read abends")
        void aReadFailureSkipsTheCloseAndTheEndBanner() {
            CollectingSink sink = new CollectingSink();

            assertThatAbend(repositoryReturning(List.of(CardReadResult.notFound())), sink);

            assertThat(sink.lines()).doesNotContain(AccountBalanceReaderJob.CLOSE_ERROR_TEXT)
                    .doesNotContain(AccountBalanceReaderJob.END_BANNER);
        }
    }

    // =============================================================================================
    // 0000-CARDFILE-OPEN and 9000-CARDFILE-CLOSE.
    // =============================================================================================

    @Nested
    @DisplayName("0000-CARDFILE-OPEN")
    class Open {

        @Test
        @DisplayName("a failed open reports, prints the status, abends, and never reads a record")
        void aFailedOpenAbendsBeforeReading() {
            CardRepository repository = mock(CardRepository.class);
            when(repository.startBrowse(any(), any()))
                    .thenThrow(new IllegalStateException("the DD name is not declared"));
            CollectingSink sink = new CollectingSink();

            AbendException abend = assertThatAbend(repository, sink);

            assertThat(sink.lines()).containsExactly(
                    AccountBalanceReaderJob.START_BANNER,
                    AccountBalanceReaderJob.OPEN_ERROR_TEXT,
                    PERMANENT_ERROR_LINE,
                    AbendException.ABEND_DISPLAY_TEXT);
            assertThat(abend.getReturnCode()).isEqualTo(AccountBalanceReaderJob.APPL_RESULT_FATAL);
            assertThat(abend).hasCauseInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("a successful open emits nothing of its own")
        void aSuccessfulOpenIsSilent() {
            List<String> lines = linesFrom(repositoryReturning(List.of(CardReadResult.endOfFile())));

            assertThat(lines).doesNotContain(AccountBalanceReaderJob.OPEN_ERROR_TEXT);
        }
    }

    @Nested
    @DisplayName("9000-CARDFILE-CLOSE")
    class Close {

        @Test
        @DisplayName("a failed close reports, prints the status, abends, and the end banner is lost")
        void aFailedCloseAbendsBeforeTheEndBanner() {
            CardRepository repository = mock(CardRepository.class);
            CardBrowse browse = mock(CardBrowse.class);
            when(repository.startBrowse(any(), any())).thenReturn(browse);
            when(browse.readNext()).thenReturn(CardReadResult.endOfFile());
            doThrow(new IllegalStateException("the file could not be released"))
                    .when(browse).endBrowse();
            CollectingSink sink = new CollectingSink();

            AbendException abend = assertThatAbend(repository, sink);

            assertThat(sink.lines()).containsExactly(
                    AccountBalanceReaderJob.START_BANNER,
                    AccountBalanceReaderJob.CLOSE_ERROR_TEXT,
                    PERMANENT_ERROR_LINE,
                    AbendException.ABEND_DISPLAY_TEXT);
            assertThat(sink.lines())
                    .as(":85 is never reached, because :83's PERFORM abended")
                    .doesNotContain(AccountBalanceReaderJob.END_BANNER);
            assertThat(abend.getReturnCode()).isEqualTo(AccountBalanceReaderJob.APPL_RESULT_FATAL);
        }

        @Test
        @DisplayName("a successful close leaves APPL-RESULT at zero, so the end banner follows")
        void aSuccessfulCloseIsFollowedByTheEndBanner() {
            CardRepository repository = mock(CardRepository.class);
            CardBrowse browse = mock(CardBrowse.class);
            when(repository.startBrowse(any(), any())).thenReturn(browse);
            when(browse.readNext()).thenReturn(CardReadResult.endOfFile());
            CollectingSink sink = new CollectingSink();

            jobOver(repository).execute(sink);

            verify(browse).endBrowse();
            assertThat(sink.lines()).endsWith(AccountBalanceReaderJob.END_BANNER)
                    .doesNotContain(AccountBalanceReaderJob.CLOSE_ERROR_TEXT);
        }
    }

    // =============================================================================================
    // 9999-ABEND-PROGRAM - the two CEE3ABD arguments and the RETURN-CODE.
    // =============================================================================================

    @Nested
    @DisplayName("9999-ABEND-PROGRAM - gate G35")
    class Abend {

        @Test
        @DisplayName("the abend carries ABCODE 999, TIMING 0, the program id and return code 12")
        void abendCarriesBothCeeArgumentsAndTheReturnCode() {
            AbendException abend = assertThatAbend(
                    repositoryReturning(List.of(CardReadResult.notFound())), new CollectingSink());

            assertThat(abend.getProgram()).isEqualTo(AccountBalanceReaderJob.PROGRAM_ID);
            assertThat(abend.getReturnCode()).isEqualTo(12);
            assertThat(abend.hasAbendCode()).isTrue();
            assertThat(abend.getAbendCode()).hasValue(AbendException.STANDARD_ABEND_CODE);
            assertThat(abend.hasTiming()).isTrue();
            assertThat(abend.getTiming()).hasValue(AbendException.STANDARD_TIMING);
        }

        @ParameterizedTest(name = "{0} names the failing operation in the abend reason")
        @CsvSource({
            "ERROR OPENING CARDFILE",
            "ERROR READING CARDFILE",
            "ERROR CLOSING CARDFILE"})
        @DisplayName("the abend reason repeats the paragraph's own DISPLAY text")
        void abendReasonNamesTheOperation(String errorText) {
            AbendException abend = abendFor(errorText);

            assertThat(abend.hasReason()).isTrue();
            assertThat(abend.getReason()).isPresent();
            assertThat(abend.getReason().orElseThrow()).startsWith(errorText);
        }

        /**
         * Drives whichever paragraph emits {@code errorText} and returns the abend it raises.
         *
         * @param errorText the paragraph's {@code DISPLAY} literal
         * @return the abend
         */
        private AbendException abendFor(String errorText) {
            CardRepository repository = mock(CardRepository.class);
            if (AccountBalanceReaderJob.OPEN_ERROR_TEXT.equals(errorText)) {
                when(repository.startBrowse(any(), any()))
                        .thenThrow(new IllegalStateException("refused"));
                return assertThatAbend(repository, new CollectingSink());
            }
            CardBrowse browse = mock(CardBrowse.class);
            when(repository.startBrowse(any(), any())).thenReturn(browse);
            if (AccountBalanceReaderJob.READ_ERROR_TEXT.equals(errorText)) {
                when(browse.readNext()).thenReturn(CardReadResult.notFound());
            } else {
                when(browse.readNext()).thenReturn(CardReadResult.endOfFile());
                doThrow(new IllegalStateException("refused")).when(browse).endBrowse();
            }
            return assertThatAbend(repository, new CollectingSink());
        }
    }

    // =============================================================================================
    // Wiring - the job, the step, the contract and the DD-name resolution.
    // =============================================================================================

    @Nested
    @DisplayName("Wiring")
    class Wiring {

        @Test
        @DisplayName("the job bean is built, named for the mandated class, and carries one step")
        void theJobBeanIsBuilt() {
            Job job = jobOver(mock(CardRepository.class)).accountBalanceReaderJob();

            assertThat(job).isNotNull();
            assertThat(job.getName()).isEqualTo(AccountBalanceReaderJob.JOB_NAME);
        }

        @Test
        @DisplayName("no job-parameters incrementer is attached, because READCARD.jcl passes no PARM")
        void noIncrementerIsAttached() {
            Job job = jobOver(mock(CardRepository.class)).accountBalanceReaderJob();

            assertThat(job.getJobParametersIncrementer())
                    .as("an incrementer would add a run counter, and that counter is a job "
                            + "parameter CBACT02C never receives")
                    .isNull();
        }

        @Test
        @DisplayName("the step is named STEP05, transcribed from the JCL step it replaces")
        void theStepIsNamedAfterTheJclStep() {
            Step step = jobOver(mock(CardRepository.class)).readCardfileStep();

            assertThat(step.getName()).isEqualTo(AccountBalanceReaderJob.STEP_NAME);
        }

        @Test
        @DisplayName("a contract declaring a job parameter is refused at wiring time")
        void aDeclaredJobParameterIsRefused() {
            JobContract withParameter = new JobContract(AccountBalanceReaderJob.PROGRAM_ID,
                    List.of(new JobParameterContract("parmDate", "string", "2022071800")),
                    List.of(new StepContract(AccountBalanceReaderJob.STEP_NAME,
                            AccountBalanceReaderJob.PROGRAM_ID, false)),
                    null, Map.of());
            AccountBalanceReaderJob job = new AccountBalanceReaderJob(
                    batchConfig(withParameter, "CARDDEMO.TEST.CARDDATA.VSAM.KSDS"),
                    mock(CardRepository.class), FIXTURE_CHARSET, new SingleValueProvider<>(null));

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(job::readCardfileStep)
                    .withMessageContaining("READCARD.jcl:22")
                    .withMessageContaining("no PARM");
        }

        @Test
        @DisplayName("a contract declaring the wrong step name is refused at wiring time")
        void aWrongStepNameIsRefused() {
            JobContract wrongStep = new JobContract(AccountBalanceReaderJob.PROGRAM_ID, List.of(),
                    List.of(new StepContract("STEP15", AccountBalanceReaderJob.PROGRAM_ID, false)),
                    null, Map.of());
            AccountBalanceReaderJob job = new AccountBalanceReaderJob(
                    batchConfig(wrongStep, "CARDDEMO.TEST.CARDDATA.VSAM.KSDS"),
                    mock(CardRepository.class), FIXTURE_CHARSET, new SingleValueProvider<>(null));

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(job::readCardfileStep)
                    .withMessageContaining(AccountBalanceReaderJob.STEP_NAME);
        }

        @Test
        @DisplayName("the dataset name is resolved from configuration, never written in Java")
        void theDatasetNameComesFromConfiguration() {
            String configured = "CARDDEMO.TEST.CARDDATA.VSAM.KSDS";
            AccountBalanceReaderJob job = new AccountBalanceReaderJob(
                    batchConfig(sourceDerivedContract(), configured), mock(CardRepository.class),
                    FIXTURE_CHARSET, new SingleValueProvider<>(null));

            assertThat(job.cardfileDatasetName()).isEqualTo(configured);
        }

        @Test
        @DisplayName("the tasklet runs the program once and reports FINISHED")
        void theTaskletRunsOnceAndFinishes() throws Exception {
            AccountBalanceReaderJob job = new AccountBalanceReaderJob(
                    batchConfig(sourceDerivedContract(), "CARDDEMO.TEST.CARDDATA.VSAM.KSDS"),
                    repositoryReturning(List.of(CardReadResult.endOfFile())),
                    FIXTURE_CHARSET,
                    new SingleValueProvider<>(new CollectingSink()));

            RepeatStatus status = job.readCardfileTasklet()
                    .execute(mock(StepContribution.class), mock(ChunkContext.class));

            assertThat(status)
                    .as("CBACT02C runs once and GOBACKs at :87 - there is no second pass to ask for")
                    .isEqualTo(RepeatStatus.FINISHED);
        }

        @Test
        @DisplayName("the tasklet lets an abend propagate, so the return code reaches the exit status")
        void theTaskletPropagatesAnAbend() {
            AccountBalanceReaderJob job = new AccountBalanceReaderJob(
                    batchConfig(sourceDerivedContract(), "CARDDEMO.TEST.CARDDATA.VSAM.KSDS"),
                    repositoryReturning(List.of(CardReadResult.notFound())),
                    FIXTURE_CHARSET,
                    new SingleValueProvider<>(new CollectingSink()));
            Tasklets tasklet = () -> job.readCardfileTasklet()
                    .execute(mock(StepContribution.class), mock(ChunkContext.class));

            assertThatExceptionOfType(AbendException.class).isThrownBy(tasklet::run);
        }

        /** A throwing runnable, so the tasklet's checked signature can be asserted on. */
        @FunctionalInterface
        private interface Tasklets {
            /**
             * Runs the tasklet.
             *
             * @throws Exception whatever the tasklet throws
             */
            void run() throws Exception;
        }

        @Test
        @DisplayName("a required collaborator that is absent is refused at construction")
        void everyCollaboratorIsRequired() {
            BatchConfig config = batchConfig(sourceDerivedContract(), "CARDDEMO.TEST.X");
            CardRepository repository = mock(CardRepository.class);
            ObjectProvider<SysoutSink> provider = new SingleValueProvider<>(null);

            assertThatExceptionOfType(NullPointerException.class).isThrownBy(() ->
                    new AccountBalanceReaderJob(null, repository, FIXTURE_CHARSET, provider));
            assertThatExceptionOfType(NullPointerException.class).isThrownBy(() ->
                    new AccountBalanceReaderJob(config, null, FIXTURE_CHARSET, provider));
            assertThatExceptionOfType(NullPointerException.class).isThrownBy(() ->
                    new AccountBalanceReaderJob(config, repository, null, provider));
            assertThatExceptionOfType(NullPointerException.class).isThrownBy(() ->
                    new AccountBalanceReaderJob(config, repository, FIXTURE_CHARSET, null));
        }

        @Test
        @DisplayName("execute refuses an absent sink rather than discarding the program's output")
        void executeRefusesAnAbsentSink() {
            AccountBalanceReaderJob job = jobOver(mock(CardRepository.class));

            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> job.execute(null))
                    .withMessageContaining(AccountBalanceReaderJob.PROGRAM_ID);
        }
    }

    // =============================================================================================
    // Container wiring - gate G3, proved in a real Spring container rather than argued about.
    // =============================================================================================

    /**
     * The collaborators this job needs, published as beans so a real container can inject them.
     *
     * <p>{@link BatchConfig} arrives from a {@code @Bean} method rather than by registering its
     * class, deliberately: a registered configuration class has its own {@code @Bean} methods
     * processed, and {@code BatchConfig} publishes a transaction manager over a {@code DataSource}
     * this test has no business providing. An instance returned from a {@code @Bean} method is just an
     * object, so the scaffolding is available without dragging a backend in behind it.
     */
    @org.springframework.context.annotation.Configuration
    static class SupportingBeans {

        /**
         * @return the batch scaffolding, over stubbed infrastructure
         */
        @org.springframework.context.annotation.Bean
        BatchConfig batchConfig() {
            return AccountBalanceReaderJobTest.batchConfig(sourceDerivedContract(),
                    "CARDDEMO.TEST.CARDDATA.VSAM.KSDS");
        }

        /**
         * @return a stubbed card repository, since nothing here reads a record
         */
        @org.springframework.context.annotation.Bean
        CardRepository cardRepository() {
            return mock(CardRepository.class);
        }

        /**
         * The dataset code page, published under the exact bean name the job's {@code @Qualifier}
         * names. If that qualifier ever stopped matching, this context would fail to start.
         *
         * @return the fixture code page
         */
        @org.springframework.context.annotation.Bean(CardRepository.DATASET_CHARSET_BEAN_NAME)
        Charset carddemoDatasetCharset() {
            return FIXTURE_CHARSET;
        }
    }

    @Nested
    @DisplayName("Container wiring - gate G3")
    class ContainerWiring {

        @Test
        @DisplayName("a real container starts, and publishes the job bean and the configuration bean")
        void aRealContainerPublishesBothBeansWithoutCollision() {
            try (var context = new org.springframework.context.annotation
                    .AnnotationConfigApplicationContext()) {
                context.register(SupportingBeans.class, AccountBalanceReaderJob.class);

                // If the configuration took its natural bean name, this refresh would fail: the @Bean
                // Job method claims that same name and bean-definition overriding is off by default.
                context.refresh();

                assertThat(context.containsBean(AccountBalanceReaderJob.JOB_NAME)).isTrue();
                assertThat(context.containsBean(AccountBalanceReaderJob.CONFIGURATION_BEAN_NAME))
                        .isTrue();
                assertThat(context.getBean(AccountBalanceReaderJob.JOB_NAME)).isInstanceOf(Job.class);
                assertThat(context.getBean(AccountBalanceReaderJob.CONFIGURATION_BEAN_NAME))
                        .isInstanceOf(AccountBalanceReaderJob.class);
            }
        }

        @Test
        @DisplayName("the published job is named for the mandated class and resolves by type")
        void thePublishedJobIsNamedForTheMandatedClass() {
            try (var context = new org.springframework.context.annotation
                    .AnnotationConfigApplicationContext(SupportingBeans.class,
                            AccountBalanceReaderJob.class)) {
                Job job = context.getBean(Job.class);

                assertThat(job.getName()).isEqualTo(AccountBalanceReaderJob.JOB_NAME);
            }
        }

        @Test
        @DisplayName("nothing in the context launches the job, so CBTRN01C-style triggerlessness holds")
        void nothingLaunchesTheJob() {
            try (var context = new org.springframework.context.annotation
                    .AnnotationConfigApplicationContext(SupportingBeans.class,
                            AccountBalanceReaderJob.class)) {
                // A job runs only when something deliberately launches it, exactly as a JCL step had
                // to be submitted. No runner, no scheduler and no launcher may appear here.
                assertThat(context.getBeansOfType(
                        org.springframework.boot.ApplicationRunner.class)).isEmpty();
                assertThat(context.getBeansOfType(
                        org.springframework.boot.CommandLineRunner.class)).isEmpty();
                assertThat(context.getBeanNamesForType(
                        org.springframework.batch.core.launch.JobLauncher.class)).isEmpty();
            }
        }
    }

    // =============================================================================================
    // SYSOUT - the seam and its default.
    // =============================================================================================

    @Nested
    @DisplayName("SYSOUT")
    class Sysout {

        @Test
        @DisplayName("a published sink is used in preference to the default")
        void aPublishedSinkWins() {
            CollectingSink published = new CollectingSink();
            AccountBalanceReaderJob job = new AccountBalanceReaderJob(
                    batchConfig(sourceDerivedContract(), "CARDDEMO.TEST.X"),
                    mock(CardRepository.class), FIXTURE_CHARSET,
                    new SingleValueProvider<>(published));

            assertThat(job.sysoutSink()).isSameAs(published);
        }

        @Test
        @DisplayName("with nothing published the default standard-output sink is used")
        void theDefaultIsUsedWhenNothingIsPublished() {
            AccountBalanceReaderJob job = jobOver(mock(CardRepository.class));

            assertThat(job.sysoutSink()).isInstanceOf(PrintStreamSysoutSink.class);
        }

        @Test
        @DisplayName("the default sink writes one undecorated line per call, in order")
        void theDefaultSinkWritesUndecoratedLinesInOrder() {
            ByteArrayOutputStream captured = new ByteArrayOutputStream();
            SysoutSink sink = new PrintStreamSysoutSink(
                    new PrintStream(captured, true, FIXTURE_CHARSET));

            sink.write(AccountBalanceReaderJob.START_BANNER);
            sink.write(AccountBalanceReaderJob.END_BANNER);

            assertThat(captured.toString(FIXTURE_CHARSET).lines().toList())
                    .as("no timestamp, no severity and no logger name - a parity case compares these "
                            + "lines against the COBOL's output character for character")
                    .containsExactly(AccountBalanceReaderJob.START_BANNER,
                            AccountBalanceReaderJob.END_BANNER);
        }

        @Test
        @DisplayName("the default sink refuses an absent stream and an absent line")
        void theDefaultSinkRefusesAbsentArguments() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new PrintStreamSysoutSink(null));
            SysoutSink sink = new PrintStreamSysoutSink(
                    new PrintStream(new ByteArrayOutputStream(), true, FIXTURE_CHARSET));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> sink.write(null));
        }

        @Test
        @DisplayName("the standard-output sink factory yields a print-stream sink")
        void theStandardOutputFactoryYieldsAPrintStreamSink() {
            assertThat(AccountBalanceReaderJob.standardOutputSysoutSink())
                    .isInstanceOf(PrintStreamSysoutSink.class);
        }
    }

    // =============================================================================================
    // Shared assertion helper.
    // =============================================================================================

    /**
     * Runs the job over {@code repository} and asserts that it abends.
     *
     * @param repository the stubbed repository
     * @param sink       the sink collecting the run's output
     * @return the abend raised
     */
    private static AbendException assertThatAbend(CardRepository repository, CollectingSink sink) {
        AccountBalanceReaderJob job = new AccountBalanceReaderJob(
                batchConfig(sourceDerivedContract(), "CARDDEMO.TEST.CARDDATA.VSAM.KSDS"),
                repository, FIXTURE_CHARSET, new SingleValueProvider<>(sink));
        AbendException abend = null;
        try {
            job.execute(sink);
        } catch (AbendException raised) {
            abend = raised;
        }
        assertThat(abend).as("the paragraph must abend rather than continue").isNotNull();
        return abend;
    }

    /**
     * Guards against an unused-import style drift: {@link Optional} and {@link Supplier} are used by
     * the provider stub above, and this keeps the intent explicit for a reader.
     */
    @Test
    @DisplayName("the provider stub reports absence the way a bean lookup does")
    void theProviderDoubleReportsAbsence() {
        ObjectProvider<SysoutSink> absent = new SingleValueProvider<>(null);
        CollectingSink present = new CollectingSink();

        assertThat(Optional.ofNullable(absent.getIfAvailable())).isEmpty();
        assertThat(absent.stream()).isEmpty();
        Supplier<SysoutSink> fallback = () -> present;
        assertThat(absent.getIfAvailable(fallback)).isSameAs(present);
        ObjectProvider<SysoutSink> published = new SingleValueProvider<>(present);
        assertThat(published.getIfAvailable(fallback)).isSameAs(present);
        assertThat(published.stream()).containsExactly(present);
    }
}
