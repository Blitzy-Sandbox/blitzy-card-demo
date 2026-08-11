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
import com.vsergeychik.carddemo.config.BatchConfig.StopRequestedException;
import com.vsergeychik.carddemo.config.BatchConfig.StopSignal;
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
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
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
import org.mockito.ArgumentCaptor;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobInterruptedException;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.scope.context.StepContext;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.transaction.PlatformTransactionManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.ignoreStubs;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
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
        return datasetBindings(dsname, dsname);
    }

    /**
     * The dataset catalogue, holding this job's own DD name and the key the card repository is bound to.
     *
     * <p>Both are declared because the job proves at construction that they name one dataset: its JCL
     * says {@code CARDFILE} (app/jcl/READCARD.jcl:25-26) while the repository it reads through is bound
     * to the CICS file name {@code CARDDAT}, and each key carries an independent override in
     * {@code application.yml}. Passing two different names here is how a test exercises the rejection.
     *
     * @param dsname     the dataset name to declare for {@value AccountBalanceReaderJob#DD_NAME}
     * @param baseDsname the dataset name to declare for {@code CARDDAT}
     * @return the catalogue
     */
    private static DatasetBindings datasetBindings(String dsname, String baseDsname) {
        DatasetBindings bindings = new DatasetBindings();
        bindings.put(AccountBalanceReaderJob.DD_NAME, cardfileBinding(dsname));
        bindings.put(CardRepository.BASE_DD_NAME, cardfileBinding(baseDsname));
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
        return batchConfig(contract, dsname, dsname);
    }

    /**
     * The batch scaffolding, with this job's DD and the card repository's DD declared separately.
     *
     * @param contract   this job's contract
     * @param dsname     the dataset to declare for {@value AccountBalanceReaderJob#DD_NAME}
     * @param baseDsname the dataset to declare for {@code CARDDAT}
     * @return the scaffolding
     */
    private static BatchConfig batchConfig(JobContract contract, String dsname, String baseDsname) {
        BatchConfig.JobContracts contracts = new BatchConfig.JobContracts();
        contracts.put(AccountBalanceReaderJob.JOB_KEY, contract);
        return new BatchConfig(
                new SingleValueProvider<>(mock(JobRepository.class)),
                new SingleValueProvider<>(mock(PlatformTransactionManager.class)),
                contracts,
                datasetBindings(dsname, baseDsname));
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
     * A card repository mock that hands itself back when the job re-binds it to its own DD.
     *
     * <p>{@code CBACT02C} reads through the DD {@code app/jcl/READCARD.jcl:25-26} binds, so the job asks
     * the repository for a view addressing {@value AccountBalanceReaderJob#DD_NAME} before it browses.
     * A bare mock answers {@code null} to that, so every stub on the browse would be attached to an
     * instance the job never uses - which is exactly the coupling the production change introduced, and
     * exactly what a test should have to acknowledge rather than work around silently.
     *
     * <p>Returning the same mock is the behaviour the real repository has whenever the DD resolves to
     * the dataset it already addresses, which is the shipped configuration.
     *
     * @return the mock, with the re-binding stubbed
     */
    private static CardRepository cardRepositoryMock() {
        CardRepository repository = mock(CardRepository.class);
        when(repository.addressing(any(), any())).thenReturn(repository);
        return repository;
    }

    /**
     * A repository whose forward browse returns each supplied result in turn.
     *
     * @param results the results the browse reports, in order
     * @return the stubbed repository
     */
    private static CardRepository repositoryReturning(List<CardReadResult> results) {
        CardRepository repository = cardRepositoryMock();
        CardBrowse browse = mock(CardBrowse.class);
        when(repository.openBrowse(any(), any())).thenReturn(browse);
        if (results.size() == 1) {
            when(browse.readNext()).thenReturn(results.get(0));
        } else {
            when(browse.readNext()).thenReturn(results.get(0),
                    results.subList(1, results.size()).toArray(CardReadResult[]::new));
        }
        return repository;
    }

    /**
     * A step execution shaped as the framework builds one, so a tasklet can be driven exactly as a
     * running step drives it.
     *
     * <p>Built by hand rather than with a test factory from another artifact, because the dependency set
     * is closed and the three constructors needed are public API. Only the step name and the
     * {@code terminateOnly} flag are read by anything under test here.
     *
     * @return a fresh step execution, not asked to stop
     */
    private static StepExecution stepExecution() {
        return new StepExecution(AccountBalanceReaderJob.STEP_NAME, new JobExecution(1L));
    }

    /**
     * The chunk context the framework hands a tasklet, over the given step execution.
     *
     * @param stepExecution the execution the tasklet is running inside
     * @return a real chunk context; never a mock, because the tasklet reads through it to the execution
     */
    private static ChunkContext chunkContext(StepExecution stepExecution) {
        return new ChunkContext(new StepContext(stepExecution));
    }

    /**
     * A repository that walks every fixture record and then reports the end of the file.
     *
     * @return the stubbed repository
     */
    private static CardRepository repositoryOverWholeFixture() {
        return repositoryReturning(wholeFixtureReadResults());
    }

    /**
     * The whole fixture as read outcomes, terminated by the end-of-file arm.
     *
     * @return one normal outcome per fixture record, then {@code '10'}
     */
    private static List<CardReadResult> wholeFixtureReadResults() {
        List<CardReadResult> results = new ArrayList<>(
                fixtureRecords().stream().map(AccountBalanceReaderJobTest::cardRead).toList());
        results.add(CardReadResult.endOfFile());
        return results;
    }

    /**
     * A repository that walks the whole fixture again on <em>every</em> browse it is asked to open.
     *
     * <p>{@link #repositoryOverWholeFixture()} stubs one sequence of consecutive returns, which a mock
     * exhausts on first use and thereafter repeats its final value - so a second pass over that
     * repository sees an immediate end of file. That is a property of the double, not of the job, and it
     * would silently turn any "run it twice" assertion into a test of nothing. Answering with a fresh
     * browse per open makes each pass see the same fifty records, which is what a real KSDS does.
     *
     * @return the stubbed repository
     */
    private static CardRepository repositoryReplayingWholeFixture() {
        CardRepository repository = cardRepositoryMock();
        when(repository.openBrowse(any(), any())).thenAnswer(invocation -> {
            List<CardReadResult> results = wholeFixtureReadResults();
            CardBrowse browse = mock(CardBrowse.class);
            when(browse.readNext()).thenReturn(results.get(0),
                    results.subList(1, results.size()).toArray(CardReadResult[]::new));
            return browse;
        });
        return repository;
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

    @Nested
    @DisplayName("Bounded cancellation - the pass yields to a stop request between records")
    class BoundedCancellation {

        @Test
        @DisplayName("no stop requested leaves the pass exactly as it was: 52 lines, unchanged")
        void withoutAStopTheWholePassRuns() {
            CollectingSink sink = new CollectingSink();

            jobOver(repositoryOverWholeFixture()).execute(sink, StopSignal.of(stepExecution()));

            // The signal is consulted on every iteration and changes nothing while nothing is pending,
            // which is the property that makes the probe additive rather than a change to the pass.
            assertThat(sink.lines()).hasSize(FIXTURE_RECORD_COUNT + 2);
            assertThat(sink.lines().get(0)).isEqualTo(AccountBalanceReaderJob.START_BANNER);
            assertThat(sink.lines().get(sink.lines().size() - 1))
                    .isEqualTo(AccountBalanceReaderJob.END_BANNER);
        }

        @Test
        @DisplayName("a stop requested before the pass starts ends it at the first record boundary")
        void aStopBeforeTheFirstRecordEndsThePassAtOnce() {
            StepExecution stepExecution = stepExecution();
            stepExecution.setTerminateOnly();
            CollectingSink sink = new CollectingSink();
            AccountBalanceReaderJob job = jobOver(repositoryOverWholeFixture());

            assertThatExceptionOfType(StopRequestedException.class)
                    .isThrownBy(() -> job.execute(sink, StopSignal.of(stepExecution)))
                    .withMessageContaining(AccountBalanceReaderJob.STEP_NAME)
                    .withMessageContaining("NO write is retried")
                    // The cause is what makes AbstractStep report the step as STOPPED rather than
                    // FAILED, so it is asserted rather than left as an implementation detail.
                    .withCauseInstanceOf(JobInterruptedException.class);

            // The OPEN happened and its banner was written; not one record line was. The probe sits
            // before the read, so the pass ends at a record boundary and never mid-record.
            assertThat(sink.lines()).containsExactly(AccountBalanceReaderJob.START_BANNER);
        }

        @Test
        @DisplayName("a stop requested part way through ends the pass with the record in flight complete")
        void aStopPartWayThroughLeavesTheRecordInFlightComplete() {
            StepExecution stepExecution = stepExecution();
            CollectingSink sink = new CollectingSink();
            AccountBalanceReaderJob job = jobOver(repositoryOverWholeFixture());
            int stopAfter = 3;

            // Requests the stop from the probe itself, on its fourth consultation, which is the closest
            // a unit test can get to an operator pressing stop mid-pass.
            StopSignal afterThreeRecords = countingSignal(stepExecution, stopAfter);

            assertThatExceptionOfType(StopRequestedException.class)
                    .isThrownBy(() -> job.execute(sink, afterThreeRecords));

            // Banner plus exactly three record lines: each of the three is a WHOLE record image, so no
            // line was truncated and no record was half displayed.
            assertThat(sink.lines()).hasSize(stopAfter + 1);
            assertThat(sink.lines().subList(1, sink.lines().size()))
                    .allSatisfy(line -> assertThat(line).hasSize(CardRecord.RECORD_LENGTH));

            // And the close banner is NOT written, exactly as it is not written on an abend: a stopped
            // pass must not report the end of a normal execution.
            assertThat(sink.lines()).doesNotContain(AccountBalanceReaderJob.END_BANNER);
        }

        @Test
        @DisplayName("the single-argument overload runs unbounded, so every existing caller is unchanged")
        void theSingleArgumentOverloadIsUnbounded() {
            CollectingSink withSignal = new CollectingSink();
            CollectingSink withoutSignal = new CollectingSink();

            jobOver(repositoryOverWholeFixture()).execute(withoutSignal);
            jobOver(repositoryOverWholeFixture()).execute(withSignal, StopSignal.RUNNING);

            assertThat(withoutSignal.lines()).isEqualTo(withSignal.lines());
        }

        @Test
        @DisplayName("a null stop signal is refused rather than silently treated as 'never stop'")
        void aNullStopSignalIsRefused() {
            AccountBalanceReaderJob job = jobOver(repositoryOverWholeFixture());

            assertThatNullPointerException()
                    .isThrownBy(() -> job.execute(new CollectingSink(), null))
                    .withMessageContaining("StopSignal.RUNNING");
        }

        /**
         * A probe that permits the given number of records and then reports a stop.
         *
         * <p>It sets {@code terminateOnly} on the real step execution and then delegates to the real
         * {@link StopSignal}, so the refusal is produced by the production probe and by the framework's
         * own interruption policy rather than by a stand-in that merely throws the same type.
         *
         * @param stepExecution the execution to mark
         * @param permitted     how many consultations return before the stop is requested
         * @return the probe
         */
        private StopSignal countingSignal(StepExecution stepExecution, int permitted) {
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
        @DisplayName("a row whose FILLER X(59) is not spaces is displayed as it stands, not re-encoded")
        void aRowsFillerSurvivesTheDisplay() {
            // DISPLAY CARD-RECORD (app/cbl/CBACT02C.cbl:78) writes the FD record area, and READ INTO
            // filled that area from the row. The area's FILLER X(59) is covered by no field of
            // CVACT02Y, so whatever the row held there is what the line carries. Rendering the decoded
            // record instead allocates a fresh area and blanks that span - which is a line the program
            // cannot produce, and 59 wrong bytes on every one of the fifty records.
            CardRecord record = fixtureRecords().get(0);
            String clean = record.encodeToImage(StandardCharsets.US_ASCII);
            String dirty = clean.substring(0, CardRecord.RECORD_LENGTH - CardRecord.FILLER_LENGTH)
                    + "*".repeat(CardRecord.FILLER_LENGTH);

            List<String> lines = linesFrom(repositoryReturning(List.of(
                    CardReadResult.normal(record, dirty), CardReadResult.endOfFile())));

            assertThat(lines).hasSize(3);
            assertThat(lines.get(1))
                    .as("the row's own 150 bytes, FILLER included")
                    .hasSize(CardRecord.RECORD_LENGTH)
                    .isEqualTo(dirty)
                    .isNotEqualTo(clean);
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
                            .map(AccountBalanceReaderJobTest::cardRead).toList());
            results.add(CardReadResult.endOfFile());

            assertThat(linesFrom(repositoryReturning(results))).hasSize(recordCount + 2);
        }

        @Test
        @DisplayName("the browse is positioned forward over the base cluster, never the alternate index")
        void browseIsForwardOverTheBaseCluster() {
            CardRepository repository = repositoryOverWholeFixture();

            linesFrom(repository);

            verify(repository).openBrowse(AccountBalanceReaderJob.LOWEST_CARD_NUMBER_KEY,
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
                    List.of(cardRead(first), CardReadResult.endOfFile())));

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
                    repositoryReturning(List.of(cardReadDuplicate(first)));

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
                    cardRead(records.get(0)),
                    cardRead(records.get(1)),
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
        @DisplayName("a dataset the backend will not open abends at the OPEN, not at the first read")
        void aBackendRefusalAtOpenAbendsAtTheOpen() {
            // app/cbl/CBACT02C.cbl:33 declares FILE STATUS IS CARDFILE-STATUS and :121-:127 tests it,
            // reaching DISPLAY 'ERROR OPENING CARDFILE' at :129 and the abend at :132. That arm was
            // unreachable while the job read through startBrowse, which issues no backend call and
            // reports nothing - the online contract, because COCRDLIC discards its own STARTBR response.
            // openBrowse is the batch entry point and reports what the open found.
            CardRepository repository = mock(CardRepository.class);
            CardBrowse refused = mock(CardBrowse.class);
            when(refused.openResp()).thenReturn(FileStatus.NOTOPEN);
            when(repository.openBrowse(any(), any())).thenReturn(refused);
            CollectingSink sink = new CollectingSink();

            AbendException abend = assertThatAbend(repository, sink);

            assertThat(sink.lines()).containsExactly(
                    AccountBalanceReaderJob.START_BANNER,
                    AccountBalanceReaderJob.OPEN_ERROR_TEXT,
                    FileStatus.toDisplayLine(AccountBalanceReaderJob.PERMANENT_ERROR_STATUS),
                    AbendException.ABEND_DISPLAY_TEXT);
            assertThat(abend.getMessage()).contains(AccountBalanceReaderJob.OPEN_ERROR_TEXT);
            // Never read: the abend happens before 1000-CARDFILE-GET-NEXT is performed.
            verify(refused, never()).readNext();
        }

        @Test
        @DisplayName("a failed open reports, prints the status, abends, and never reads a record")
        void aFailedOpenAbendsBeforeReading() {
            CardRepository repository = cardRepositoryMock();
            when(repository.openBrowse(any(), any()))
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
            CardRepository repository = cardRepositoryMock();
            CardBrowse browse = mock(CardBrowse.class);
            when(repository.openBrowse(any(), any())).thenReturn(browse);
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
            CardRepository repository = cardRepositoryMock();
            CardBrowse browse = mock(CardBrowse.class);
            when(repository.openBrowse(any(), any())).thenReturn(browse);
            when(browse.readNext()).thenReturn(CardReadResult.endOfFile());
            CollectingSink sink = new CollectingSink();

            jobOver(repository).execute(sink);

            verify(browse).endBrowse();
            assertThat(sink.lines()).endsWith(AccountBalanceReaderJob.END_BANNER)
                    .doesNotContain(AccountBalanceReaderJob.CLOSE_ERROR_TEXT);
        }
    }

    // =================================================================================================
    // The browse is released on every exit, not only on the normal tail. CBACT02C abends outright
    // without closing, so what is asserted here is that releasing changes nothing the program
    // observably produces - the SYSOUT line sequence and the return code are the whole of it.
    // =================================================================================================

    @Nested
    @DisplayName("the browse is released however the pass ends")
    class BrowseRelease {

        @Test
        @DisplayName("an unmodelled runtime failure in the read still releases the browse")
        void anUnmodelledReadFailureStillReleasesTheBrowse() {
            // 1000-CARDFILE-GET-NEXT converts a file STATUS into the abend ladder, but it does not
            // wrap the repository call - a driver-level refusal propagates as it is. That is precisely
            // the exit the normal tail cannot cover, so it is the one worth pinning.
            CardRepository repository = cardRepositoryMock();
            CardBrowse browse = mock(CardBrowse.class);
            when(repository.openBrowse(any(), any())).thenReturn(browse);
            when(browse.readNext()).thenThrow(new IllegalStateException("the read was refused"));
            CollectingSink sink = new CollectingSink();
            AccountBalanceReaderJob job = jobOver(repository);

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> job.execute(sink))
                    .withMessage("the read was refused");

            verify(browse).endBrowse();
            assertThat(sink.lines())
                    .as("the release is silent: it adds no line the source does not have")
                    .containsExactly(AccountBalanceReaderJob.START_BANNER);
        }

        @Test
        @DisplayName("an abending read status still releases the browse, and the abend survives")
        void anAbendingReadStatusStillReleasesTheBrowse() {
            CardRepository repository = cardRepositoryMock();
            CardBrowse browse = mock(CardBrowse.class);
            when(repository.openBrowse(any(), any())).thenReturn(browse);
            when(browse.readNext()).thenReturn(CardReadResult.notFound());
            CollectingSink sink = new CollectingSink();

            AbendException abend = assertThatAbend(repository, sink);

            verify(browse).endBrowse();
            assertThat(abend.getReturnCode()).isEqualTo(AccountBalanceReaderJob.APPL_RESULT_FATAL);
            assertThat(sink.lines())
                    .as("the read arm's own three lines, and nothing the release added")
                    .doesNotContain(AccountBalanceReaderJob.CLOSE_ERROR_TEXT)
                    .doesNotContain(AccountBalanceReaderJob.END_BANNER);
        }

        @Test
        @DisplayName("a normal run closes exactly once, because the program's own CLOSE already ran")
        void aNormalRunClosesExactlyOnce() {
            // The property being pinned is the source's: :83 is one CLOSE statement, so one run is one
            // close. The guard is this execution's own record of having run 9000-CARDFILE-CLOSE rather
            // than the handle's state, which is why this holds for a test double as well as for a real
            // browse - a double does not track whether it has been ended.
            CardRepository repository = cardRepositoryMock();
            CardBrowse browse = mock(CardBrowse.class);
            when(repository.openBrowse(any(), any())).thenReturn(browse);
            when(browse.readNext()).thenReturn(CardReadResult.endOfFile());
            CollectingSink sink = new CollectingSink();

            jobOver(repository).execute(sink);

            verify(browse, times(1)).endBrowse();
        }

        @Test
        @DisplayName("a failing close is not released a second time, and its abend survives")
        void aFailingCloseIsNotRetried() {
            CardRepository repository = cardRepositoryMock();
            CardBrowse browse = mock(CardBrowse.class);
            when(repository.openBrowse(any(), any())).thenReturn(browse);
            when(browse.readNext()).thenReturn(CardReadResult.endOfFile());
            doThrow(new IllegalStateException("the file could not be released"))
                    .when(browse).endBrowse();
            CollectingSink sink = new CollectingSink();

            AbendException abend = assertThatAbend(repository, sink);

            verify(browse, times(1)).endBrowse();
            assertThat(abend.getReturnCode())
                    .as("the close's own abend reaches the caller, not a cleanup failure")
                    .isEqualTo(AccountBalanceReaderJob.APPL_RESULT_FATAL);
        }

        @Test
        @DisplayName("a failed open leaves nothing to release")
        void aFailedOpenLeavesNothingToRelease() {
            CardRepository repository = cardRepositoryMock();
            when(repository.openBrowse(any(), any()))
                    .thenThrow(new IllegalStateException("the dataset is not addressable"));
            CollectingSink sink = new CollectingSink();

            assertThatAbend(repository, sink);

            verifyNoMoreInteractions(ignoreStubs(repository));
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
            CardRepository repository = cardRepositoryMock();
            if (AccountBalanceReaderJob.OPEN_ERROR_TEXT.equals(errorText)) {
                when(repository.openBrowse(any(), any()))
                        .thenThrow(new IllegalStateException("refused"));
                return assertThatAbend(repository, new CollectingSink());
            }
            CardBrowse browse = mock(CardBrowse.class);
            when(repository.openBrowse(any(), any())).thenReturn(browse);
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

    // =================================================================================================
    // The declared DD is the DD read - the DD-mapping finding.
    // =================================================================================================

    @Nested
    @DisplayName("the DD this job declares is the DD it reads - app/jcl/READCARD.jcl:25-26")
    class TheDeclaredDdDrivesTheRead {

        @Test
        @DisplayName("the read is taken through the resolved CARDFILE binding, not through CARDDAT")
        void theReadGoesThroughTheDeclaredDd() {
            CardRepository repository = repositoryReturning(List.of(CardReadResult.endOfFile()));

            linesFrom(repository);

            // Before it browses, the job asks the repository for a view addressing its own DD. The DD
            // name it names is the JCL's, and the binding it hands over is the one the job resolved -
            // which is what makes a //CARDFILE DD statement mean something rather than decorate.
            ArgumentCaptor<DatasetBinding> used = ArgumentCaptor.forClass(DatasetBinding.class);
            verify(repository).addressing(used.capture(), eq(AccountBalanceReaderJob.DD_NAME));
            assertThat(used.getValue().dsname()).isEqualTo("CARDDEMO.TEST.CARDDATA.VSAM.KSDS");
            assertThat(AccountBalanceReaderJob.DD_NAME).isEqualTo("CARDFILE");
        }

        @Test
        @DisplayName("the re-binding is asked for once per run, not once per record")
        void theRebindingHappensOncePerRun() {
            // addressing() hands back an instance whose statements resolve on first use, so asking per
            // record would re-describe the relation for every record of the browse. One run, one ask.
            CardRepository repository = repositoryOverWholeFixture();

            linesFrom(repository);

            verify(repository, times(1)).addressing(any(), eq(AccountBalanceReaderJob.DD_NAME));
        }

        @Test
        @DisplayName("a repository addressing the declared DD is what the browse is taken on")
        void theBrowseIsTakenOnTheRebindingResult() {
            // The distinction that matters: the job must browse the instance addressing() returned, not
            // the injected one. Handing back a DIFFERENT double proves which one it used.
            CardRepository injected = cardRepositoryMock();
            CardRepository rebound = repositoryReturning(List.of(CardReadResult.endOfFile()));
            when(injected.addressing(any(), any())).thenReturn(rebound);

            linesFrom(injected);

            verify(rebound).openBrowse(any(), any());
            verify(injected, never()).openBrowse(any(), any());
        }
    }

    @Nested
    @DisplayName("Wiring")
    class Wiring {

        @Test
        @DisplayName("the job bean is built, named for the mandated class, and carries one step")
        void theJobBeanIsBuilt() {
            Job job = jobOver(cardRepositoryMock()).accountBalanceReaderJob();

            assertThat(job).isNotNull();
            assertThat(job.getName()).isEqualTo(AccountBalanceReaderJob.JOB_NAME);
        }

        @Test
        @DisplayName("CARDFILE and CARDDAT pointing at different datasets is refused at construction")
        void divergingDdNamesAreRefused() {
            // The step's JCL names CARDFILE (app/jcl/READCARD.jcl:25-26); the repository it reads through
            // is bound to the CICS file name CARDDAT. Both keys carry independent overrides in
            // application.yml, so a deployment can point them at different datasets - and the job would
            // then read one its own DD statement never named, silently, with a correct-looking result. A
            // COBOL step cannot do this, because the DD statement is the binding.
            BatchConfig diverging = batchConfig(sourceDerivedContract(),
                    "CARDDEMO.TEST.CARDDATA.VSAM.KSDS", "CARDDEMO.TEST.SOMETHING.ELSE.KSDS");

            assertThatIllegalStateException().isThrownBy(() -> new AccountBalanceReaderJob(diverging,
                    mock(CardRepository.class), FIXTURE_CHARSET, new SingleValueProvider<>(null)))
                    .withMessageContaining(AccountBalanceReaderJob.DD_NAME)
                    .withMessageContaining(CardRepository.BASE_DD_NAME)
                    .withMessageContaining("CARDDEMO.TEST.SOMETHING.ELSE.KSDS");
        }

        @Test
        @DisplayName("CARDFILE and CARDDAT naming one dataset is accepted, which is the shipped default")
        void agreeingDdNamesAreAccepted() {
            assertThat(jobOver(mock(CardRepository.class)).cardfileDatasetName())
                    .isEqualTo("CARDDEMO.TEST.CARDDATA.VSAM.KSDS");
        }

        @Test
        @DisplayName("a run identity is attached and the job is not restartable, so each launch is a "
                + "whole fresh run exactly as resubmitting READCARD.jcl is")
        void eachLaunchIsAFreshRun() {
            Job job = jobOver(cardRepositoryMock()).accountBalanceReaderJob();

            // READCARD.jcl passes no PARM, so without an identity of its own this job would have
            // exactly one instance for all time and its second submission would be refused as already
            // complete. The incrementer supplies a run identity - not a business value the COBOL
            // receives - and preventRestart stops a failed execution being resumed mid-flight, which
            // CBACT02C has no checkpoint state to support.
            assertThat(job.getJobParametersIncrementer())
                    .as("a parameterless job needs a per-launch identity to be submittable twice")
                    .isNotNull();
            assertThat(job.isRestartable())
                    .as("a resubmission on the mainframe re-runs the step from the top; it never "
                            + "resumes a failed execution")
                    .isFalse();
        }

        @Test
        @DisplayName("the step is named STEP05, transcribed from the JCL step it replaces")
        void theStepIsNamedAfterTheJclStep() {
            Step step = jobOver(cardRepositoryMock()).readCardfileStep();

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
                    cardRepositoryMock(), FIXTURE_CHARSET, new SingleValueProvider<>(null));

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(job::readCardfileStep)
                    .withMessageContaining("READCARD.jcl:22")
                    .withMessageContaining("no PARM");
        }

        @Test
        @DisplayName("a second step declared beside STEP05 is refused, because READCARD.jcl has one "
                + "EXEC and no other")
        void anAddedStepIsRefused() {
            // Resolving STEP05 by name finds it whether it stands alone or first of two, so the
            // per-step checks cannot see this. A second step would read the CARDFILE dataset twice and
            // emit two passes of DISPLAY output for one submission.
            JobContract withASecondStep = new JobContract(AccountBalanceReaderJob.PROGRAM_ID, List.of(),
                    List.of(new StepContract(AccountBalanceReaderJob.STEP_NAME,
                                    AccountBalanceReaderJob.PROGRAM_ID, false),
                            new StepContract("STEP06", AccountBalanceReaderJob.PROGRAM_ID, false)),
                    null, Map.of());
            AccountBalanceReaderJob job = new AccountBalanceReaderJob(
                    batchConfig(withASecondStep, "CARDDEMO.TEST.CARDDATA.VSAM.KSDS"),
                    cardRepositoryMock(), FIXTURE_CHARSET, new SingleValueProvider<>(null));

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(job::readCardfileStep)
                    .withMessageContaining("does not declare the step sequence of "
                            + "app/jcl/READCARD.jcl:22")
                    .withMessageContaining("configured: [STEP05/CBACT02C, STEP06/CBACT02C]")
                    .withMessageContaining("required:   [STEP05/CBACT02C]");
        }

        @Test
        @DisplayName("the shipped single-step sequence is what the class requires")
        void theShippedSequenceIsRequired() {
            assertThat(AccountBalanceReaderJob.REQUIRED_STEPS)
                    .containsExactly(new StepContract(AccountBalanceReaderJob.STEP_NAME,
                            AccountBalanceReaderJob.PROGRAM_ID, false));
            assertThat(sourceDerivedContract().steps())
                    .isEqualTo(AccountBalanceReaderJob.REQUIRED_STEPS);
        }

        @Test
        @DisplayName("a contract declaring the wrong step name is refused at wiring time")
        void aWrongStepNameIsRefused() {
            JobContract wrongStep = new JobContract(AccountBalanceReaderJob.PROGRAM_ID, List.of(),
                    List.of(new StepContract("STEP15", AccountBalanceReaderJob.PROGRAM_ID, false)),
                    null, Map.of());
            AccountBalanceReaderJob job = new AccountBalanceReaderJob(
                    batchConfig(wrongStep, "CARDDEMO.TEST.CARDDATA.VSAM.KSDS"),
                    cardRepositoryMock(), FIXTURE_CHARSET, new SingleValueProvider<>(null));

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(job::readCardfileStep)
                    .withMessageContaining(AccountBalanceReaderJob.STEP_NAME);
        }

        @Test
        @DisplayName("the dataset name is resolved from configuration, never written in Java")
        void theDatasetNameComesFromConfiguration() {
            String configured = "CARDDEMO.TEST.CARDDATA.VSAM.KSDS";
            AccountBalanceReaderJob job = new AccountBalanceReaderJob(
                    batchConfig(sourceDerivedContract(), configured), cardRepositoryMock(),
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

            StepExecution stepExecution = stepExecution();
            StepContribution contribution = new StepContribution(stepExecution);

            RepeatStatus status = job.readCardfileTasklet()
                    .execute(contribution, chunkContext(stepExecution));

            assertThat(status)
                    .as("CBACT02C runs once and GOBACKs at :87 - there is no second pass to ask for")
                    .isEqualTo(RepeatStatus.FINISHED);
            assertThat(contribution.getReadCount())
                    .as("the first read found end of file, so nothing was read - and the read that "
                            + "found it is not itself a record")
                    .isZero();
        }

        @Test
        @DisplayName("the tasklet reports the pass's record count as the step's read count")
        void theTaskletReportsTheReadCount() throws Exception {
            // The gap this closes was visible in the batch metadata rather than in any output: a
            // completed pass over the whole card master recorded READ_COUNT 0 in
            // BATCH_STEP_EXECUTION, exactly as a pass over an empty dataset would, while three sibling
            // reader jobs recorded their real counts. The count is metadata only: CBACT02C keeps no
            // counter, so the displayed line sequence is asserted here too and is unchanged.
            CollectingSink sink = new CollectingSink();
            AccountBalanceReaderJob job = new AccountBalanceReaderJob(
                    batchConfig(sourceDerivedContract(), "CARDDEMO.TEST.CARDDATA.VSAM.KSDS"),
                    repositoryOverWholeFixture(), FIXTURE_CHARSET, new SingleValueProvider<>(sink));
            StepExecution stepExecution = stepExecution();
            StepContribution contribution = new StepContribution(stepExecution);

            RepeatStatus status = job.readCardfileTasklet()
                    .execute(contribution, chunkContext(stepExecution));

            assertThat(status).isEqualTo(RepeatStatus.FINISHED);
            assertThat(contribution.getReadCount()).isEqualTo(FIXTURE_RECORD_COUNT);
            assertThat(contribution.getWriteCount())
                    .as("CBACT02C opens INPUT at :72 and writes nothing anywhere")
                    .isZero();
            assertThat(sink.lines())
                    .as("one banner, one line per record, one banner - the count changed nothing")
                    .hasSize(EXPECTED_LINE_COUNT);
        }

        @Test
        @DisplayName("the program returns the number of records it read, for a caller outside a step")
        void theProgramReturnsItsRecordCount() {
            CollectingSink whole = new CollectingSink();
            CollectingSink empty = new CollectingSink();

            assertThat(jobOver(repositoryOverWholeFixture()).execute(whole))
                    .isEqualTo(FIXTURE_RECORD_COUNT);
            assertThat(jobOver(repositoryReturning(List.of(CardReadResult.endOfFile()))).execute(empty))
                    .as("an empty dataset read nothing")
                    .isZero();
            assertThat(whole.lines()).hasSize(EXPECTED_LINE_COUNT);
            assertThat(empty.lines()).hasSize(2);
        }

        @Test
        @DisplayName("the tasklet lets an abend propagate, so the return code reaches the exit status")
        void theTaskletPropagatesAnAbend() {
            AccountBalanceReaderJob job = new AccountBalanceReaderJob(
                    batchConfig(sourceDerivedContract(), "CARDDEMO.TEST.CARDDATA.VSAM.KSDS"),
                    repositoryReturning(List.of(CardReadResult.notFound())),
                    FIXTURE_CHARSET,
                    new SingleValueProvider<>(new CollectingSink()));
            StepExecution stepExecution = stepExecution();
            StepContribution contribution = new StepContribution(stepExecution);
            Tasklets tasklet = () -> job.readCardfileTasklet()
                    .execute(contribution, chunkContext(stepExecution));

            assertThatExceptionOfType(AbendException.class).isThrownBy(tasklet::run);
            assertThat(contribution.getReadCount())
                    .as("the abend propagates instead of returning, so a step that did not complete "
                            + "reports no count - which is the honest reading of a failed pass")
                    .isZero();
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
            CardRepository repository = cardRepositoryMock();
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
            AccountBalanceReaderJob job = jobOver(cardRepositoryMock());

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
            return cardRepositoryMock();
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
                    cardRepositoryMock(), FIXTURE_CHARSET,
                    new SingleValueProvider<>(published));

            assertThat(job.sysoutSink()).isSameAs(published);
        }

        @Test
        @DisplayName("with nothing published the default standard-output sink is used")
        void theDefaultIsUsedWhenNothingIsPublished() {
            AccountBalanceReaderJob job = jobOver(cardRepositoryMock());

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

    // =================================================================================================
    // Synthesised read outcomes. A CardReadResult carries the decoded record AND the bytes it was
    // decoded from, because DISPLAY CARD-RECORD (app/cbl/CBACT02C.cbl:78) writes the record area and the
    // area's FILLER X(59) holds whatever the row held. A test constructing an outcome has no row, so the
    // image it supplies is the one a row of exactly this record would carry - which is what these two
    // helpers state, once, rather than at every call site.
    // =================================================================================================

    /**
     * The normal arm over a synthesised row of this record.
     *
     * @param record the record the row would carry
     * @return the outcome, carrying the record and the image a row of it would hold
     */
    private static CardReadResult cardRead(CardRecord record) {
        return CardReadResult.normal(record, record.encodeToImage(StandardCharsets.US_ASCII));
    }

    /**
     * The duplicate-key arm over a synthesised row of this record.
     *
     * @param record the first record sharing the alternate key
     * @return the outcome, carrying the record and the image a row of it would hold
     */
    private static CardReadResult cardReadDuplicate(CardRecord record) {
        return CardReadResult.duplicateKey(record, record.encodeToImage(StandardCharsets.US_ASCII));
    }

    // =================================================================================================
    // Rule R1, asserted rather than annotated.
    //
    // The register in the migration plan records that this class carries a mandated name which does not
    // describe what its source does, and the class documentation restates it. Neither is a test. A
    // comment cannot fail, so a later edit that wired this job to the account file - the single most
    // natural mistake available here, given the class name and the package it sits in - would be caught
    // by nothing. The tests below turn the divergence into something the build enforces.
    // =================================================================================================

    @Nested
    @DisplayName("Rule R1 - the name says Account, the program reads the CARD file")
    class NameDivergenceIsStructural {

        /** The account record's declared width, for contrast with the card record's. */
        private static final int ACCOUNT_RECORD_LENGTH = 300;

        /** The copybook {@code CBACT02C} does NOT copy, named so the assertion below can exclude it. */
        private static final String ACCOUNT_COPYBOOK = "CVACT01Y";

        /** The DD name {@code CBACT02C} never mentions - grep 'ACCTFILE' app/cbl/CBACT02C.cbl is 0. */
        private static final String ACCOUNT_DD_NAME = "ACCTFILE";

        @Test
        @DisplayName("the injected repository is card.CardRepository - there is no account collaborator")
        void theCollaboratorIsTheCardRepository() {
            Constructor<?>[] constructors = AccountBalanceReaderJob.class.getDeclaredConstructors();
            assertThat(constructors)
                    .as("one constructor, so there is exactly one way to wire this job")
                    .hasSize(1);
            List<Class<?>> parameters = List.of(constructors[0].getParameterTypes());

            // app/cbl/CBACT02C.cbl:29  SELECT CARDFILE-FILE ASSIGN TO CARDFILE, and :45 COPY CVACT02Y.
            // The repository is the card one and the record type is the card one, both from the card
            // package, because one Java type per copybook is shared rather than duplicated per program.
            assertThat(parameters).contains(CardRepository.class);
            assertThat(CardRepository.class.getPackageName())
                    .isEqualTo("com.vsergeychik.carddemo.card");
            assertThat(CardRecord.class.getPackageName())
                    .isEqualTo("com.vsergeychik.carddemo.card.model");
            assertThat(CardRecord.RECORD_LENGTH)
                    .as("CVACT02Y is 150 bytes; CVACT01Y is %d, and this program copies neither by "
                            + "accident", ACCOUNT_RECORD_LENGTH)
                    .isEqualTo(150)
                    .isNotEqualTo(ACCOUNT_RECORD_LENGTH);
        }

        @Test
        @DisplayName("no account repository or account record type is reachable from this job's wiring")
        void noAccountTypeIsWiredIn() {
            // Every type the job can hold: its constructor parameters and its declared fields, plus the
            // fields of the per-run state object, which is where the record itself lives.
            List<Class<?>> wired = new ArrayList<>();
            for (Constructor<?> constructor : AccountBalanceReaderJob.class.getDeclaredConstructors()) {
                wired.addAll(List.of(constructor.getParameterTypes()));
            }
            for (Field field : AccountBalanceReaderJob.class.getDeclaredFields()) {
                wired.add(field.getType());
            }
            for (Class<?> nested : AccountBalanceReaderJob.class.getDeclaredClasses()) {
                for (Field field : nested.getDeclaredFields()) {
                    wired.add(field.getType());
                }
            }

            assertThat(wired)
                    .as("app/cbl/CBACT02C.cbl mentions ACCTFILE zero times, so no account-file type "
                            + "may reach this job however plausible the class name makes it look")
                    .noneMatch(type -> type.getSimpleName().equals("AccountRepository"))
                    .noneMatch(type -> type.getSimpleName().equals("AccountRecord"))
                    .noneMatch(type -> type.getName()
                            .startsWith("com.vsergeychik.carddemo.account.model."));
        }

        @Test
        @DisplayName("the DD name is CARDFILE and the resolved binding is the 150-byte card dataset")
        void theResolvedBindingIsTheCardMaster() {
            AccountBalanceReaderJob job = new AccountBalanceReaderJob(
                    batchConfig(sourceDerivedContract(), "CARDDEMO.TEST.CARDDATA.VSAM.KSDS"),
                    cardRepositoryMock(), FIXTURE_CHARSET, new SingleValueProvider<>(null));

            // app/jcl/READCARD.jcl:25-26 binds //CARDFILE to CARDDATA.VSAM.KSDS. There is no //ACCTFILE
            // statement in that job at all, and the program has no file to attach one to.
            assertThat(AccountBalanceReaderJob.DD_NAME)
                    .isEqualTo("CARDFILE")
                    .isNotEqualTo(ACCOUNT_DD_NAME);
            assertThat(job.cardfileDatasetName()).contains("CARDDATA");
            DatasetBinding resolved = cardfileBinding("CARDDEMO.TEST.CARDDATA.VSAM.KSDS");
            assertThat(resolved.copybook()).isEqualTo("CVACT02Y").isNotEqualTo(ACCOUNT_COPYBOOK);
            assertThat(resolved.recordLength()).isEqualTo(CardRecord.RECORD_LENGTH);
        }

        @Test
        @DisplayName("a whole run touches the card repository only, and never an account-side lookup")
        void aRunTouchesTheCardRepositoryOnly() {
            // The behavioural half of the same statement. There is no AccountRepository to hand this job,
            // so the strongest available form is: after a complete fifty-record pass, every interaction
            // the job had was with the card repository, and it took none of the account-keyed paths.
            CardRepository repository = repositoryOverWholeFixture();

            List<String> lines = linesFrom(repository);

            assertThat(lines).hasSize(EXPECTED_LINE_COUNT);
            verify(repository, never()).readByAccountIdViaAltIndex(any(String.class));
            verify(repository, never()).readByAccountIdViaAltIndex(eq(0L));
            // Everything else the run did was addressing() and openBrowse(), both already verified; this
            // states that nothing further was asked of the collaborator at all.
            verifyNoMoreInteractions(ignoreStubs(repository));
        }

        @Test
        @DisplayName("the program id is CBACT02C, whose Function header reads 'card data file'")
        void theProgramIdIsTheCardReader() {
            // app/cbl/CBACT02C.cbl:5  * Function    : Read and print card data file.
            assertThat(AccountBalanceReaderJob.PROGRAM_ID).isEqualTo("CBACT02C");
            // Every DISPLAY the program makes about its file names CARDFILE, all three consistently.
            assertThat(AccountBalanceReaderJob.OPEN_ERROR_TEXT).endsWith("CARDFILE");
            assertThat(AccountBalanceReaderJob.READ_ERROR_TEXT).endsWith("CARDFILE");
            assertThat(AccountBalanceReaderJob.CLOSE_ERROR_TEXT).endsWith("CARDFILE");
            assertThat(AccountBalanceReaderJob.START_BANNER).endsWith("CBACT02C");
            assertThat(AccountBalanceReaderJob.END_BANNER).endsWith("CBACT02C");
        }
    }

    // =================================================================================================
    // WORKING-STORAGE never becomes static state - practice B9, gate G53.
    // =================================================================================================

    @Nested
    @DisplayName("WORKING-STORAGE is per-invocation state, never a static field - gate G53")
    class WorkingStorageIsNeverStatic {

        @Test
        @DisplayName("every static field the job declares is final, on the class and on its nested types")
        void noStaticFieldIsMutable() {
            assertNoMutableStaticFieldOn(AccountBalanceReaderJob.class);
            for (Class<?> nested : AccountBalanceReaderJob.class.getDeclaredClasses()) {
                assertNoMutableStaticFieldOn(nested);
            }
        }

        @Test
        @DisplayName("every instance field the job bean declares is final, so the singleton holds no state")
        void theSingletonHoldsNoMutableState() {
            // app/cbl/CBACT02C.cbl:46-:67 declares CARDFILE-STATUS, IO-STATUS, APPL-RESULT, END-OF-FILE,
            // ABCODE and TIMING in WORKING-STORAGE. A singleton bean carrying those as fields would let
            // one run overwrite another's and would make one test's outcome depend on another's. They
            // belong to the per-run object instead, which is what the following two assertions pin down.
            for (Field field : AccountBalanceReaderJob.class.getDeclaredFields()) {
                if (field.isSynthetic() || Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                assertThat(Modifier.isFinal(field.getModifiers()))
                        .as("instance field %s of the job bean must be final", field.getName())
                        .isTrue();
            }
        }

        @Test
        @DisplayName("two runs of one job instance do not see each other's working storage")
        void runsAreIndependent() {
            // The observable consequence, and the reason the reflection above matters. One job instance,
            // two passes: the second must produce exactly what the first did. Shared APPL-RESULT or a
            // shared END-OF-FILE flag would make the second pass end early or not at all - END-OF-FILE
            // is the sharpest of them, because :74's PERFORM UNTIL tests it before the first read, so a
            // 'Y' left behind by the first pass would make the second emit two banners and nothing else.
            AccountBalanceReaderJob job = new AccountBalanceReaderJob(
                    batchConfig(sourceDerivedContract(), "CARDDEMO.TEST.CARDDATA.VSAM.KSDS"),
                    repositoryReplayingWholeFixture(), FIXTURE_CHARSET,
                    new SingleValueProvider<>(null));
            CollectingSink first = new CollectingSink();
            CollectingSink second = new CollectingSink();

            job.execute(first);
            job.execute(second);

            assertThat(first.lines()).hasSize(EXPECTED_LINE_COUNT);
            assertThat(second.lines()).containsExactlyElementsOf(first.lines());
        }

        /**
         * Asserts that {@code type} declares no static field that can be reassigned.
         *
         * <p>Synthetic fields are skipped deliberately. The build runs the tests under the coverage
         * agent, which adds a {@code private static transient boolean[]} probe array to every
         * instrumented class; it is marked synthetic and is not ours to hold to this rule.
         *
         * @param type the class to inspect
         */
        private void assertNoMutableStaticFieldOn(Class<?> type) {
            for (Field field : type.getDeclaredFields()) {
                if (field.isSynthetic() || field.getName().startsWith("$")) {
                    continue;
                }
                if (!Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                assertThat(Modifier.isFinal(field.getModifiers()))
                        .as("static field %s of %s must be final - COBOL WORKING-STORAGE must not "
                                + "become shared mutable Java state", field.getName(),
                                type.getSimpleName())
                        .isTrue();
            }
        }
    }

    // =================================================================================================
    // 9910-DISPLAY-IO-STATUS - app/cbl/CBACT02C.cbl:161-174. BOTH arms.
    //
    // The paragraph has two branches and this program can reach both, so both are driven here. The
    // renderer itself lives in FileStatus because sixteen emission sites across the estate share it;
    // what these tests own is that THIS program's status values render as this program's paragraph
    // renders them, and that the line the paragraph emits carries the literal text it emits.
    // =================================================================================================

    @Nested
    @DisplayName("9910-DISPLAY-IO-STATUS - both arms, and the literal byte for byte")
    class IoStatusRenderer {

        @Test
        @DisplayName("the literal is 'FILE STATUS IS: NNNN' - the NNNN really is part of it")
        void theLiteralIsByteExact() {
            // app/cbl/CBACT02C.cbl:168 and :172 are both DISPLAY 'FILE STATUS IS: NNNN' IO-STATUS-04.
            // Written out here rather than only referenced through the constant, so that a constant
            // quietly reworded into 'FILE STATUS IS: ' would fail this test instead of passing silently.
            assertThat(FileStatus.DISPLAY_PREFIX).isEqualTo("FILE STATUS IS: NNNN");
            assertThat(FileStatus.STATUS_IMAGE_LENGTH).isEqualTo(4);
        }

        @ParameterizedTest(name = "status ''{0}'' renders as ''{1}'' - the numeric ELSE arm")
        @CsvSource({
            "00, 0000",
            "10, 0010",
            "22, 0022",
            "23, 0023",
            "04, 0004"})
        @DisplayName("a numeric status not beginning '9' takes the ELSE arm: '0000' with it overlaid")
        void numericStatusesTakeTheElseArm(String status, String image) {
            // :170  MOVE '0000' TO IO-STATUS-04
            // :171  MOVE IO-STATUS TO IO-STATUS-04(3:2)   <- one-based positions 3 and 4
            assertThat(FileStatus.toDisplayLine(status))
                    .isEqualTo("FILE STATUS IS: NNNN" + image);
            assertThat(FileStatus.toStatusImage(status)).isEqualTo(image).startsWith("00");
        }

        @Test
        @DisplayName("a status beginning '9' takes the IF arm: the byte becomes three decimal digits")
        void extendedStatusTakesTheIfArm() {
            // :162-:163  IF IO-STATUS NOT NUMERIC OR IO-STAT1 = '9'
            // :164-:167  the first byte verbatim, then the second byte as an unsigned integer in a
            //            PIC 999 tail, which is why 0x0A prints 010 rather than 00A.
            assertThat(FileStatus.toDisplayLine('9', (char) 0))
                    .isEqualTo("FILE STATUS IS: NNNN9000");
            assertThat(FileStatus.toDisplayLine('9', (char) 10))
                    .isEqualTo("FILE STATUS IS: NNNN9010");
            assertThat(PERMANENT_ERROR_LINE)
                    .as("the status this job reports for a refusal is a '9' status, so it takes this arm")
                    .isEqualTo("FILE STATUS IS: NNNN9000");
        }

        @Test
        @DisplayName("a non-numeric status takes the IF arm too, even though it does not begin '9'")
        void nonNumericStatusTakesTheIfArm() {
            // The class condition IO-STATUS NOT NUMERIC is the first half of :162's OR, so a status whose
            // bytes are not both digits reaches the extended arm regardless of its first character.
            assertThat(FileStatus.toDisplayLine("AB")).isEqualTo("FILE STATUS IS: NNNNA066");
        }

        @Test
        @DisplayName("a read failure emits the error text, then the status line, then the abend text")
        void theEmissionOrderIsTheParagraphOrder() {
            // :110  DISPLAY 'ERROR READING CARDFILE'
            // :111  MOVE CARDFILE-STATUS TO IO-STATUS
            // :112  PERFORM 9910-DISPLAY-IO-STATUS      -> the FILE STATUS line
            // :113  PERFORM 9999-ABEND-PROGRAM          -> 'ABENDING PROGRAM' at :155, then CEE3ABD
            CollectingSink sink = new CollectingSink();

            assertThatAbend(repositoryReturning(List.of(CardReadResult.notFound())), sink);

            assertThat(sink.lines()).containsExactly(
                    "START OF EXECUTION OF PROGRAM CBACT02C",
                    "ERROR READING CARDFILE",
                    "FILE STATUS IS: NNNN0023",
                    "ABENDING PROGRAM");
        }
    }

    // =================================================================================================
    // 0000-CARDFILE-OPEN, every status the call site can report - gate G47.
    //
    // The open is the one call site of the three whose status is genuinely multi-valued here: it reads a
    // response from the file and translates it, so '00', '10', '22', '23' and an untranslatable response
    // are all reachable. The COBOL tests only IF CARDFILE-STATUS = '00' (:121), so every one of the other
    // four must land on the same MOVE 12 arm - and the point of driving them individually is that a
    // translation which quietly treated '10' at OPEN as an empty file would pass a two-case test.
    // =================================================================================================

    @Nested
    @DisplayName("0000-CARDFILE-OPEN - every reachable status - gate G47")
    class OpenStatusLadder {

        @ParameterizedTest(name = "CICS response {0} is status ''{1}'' at the OPEN, and abends with 12")
        @CsvSource({
            "20, 10",
            "14, 22",
            "15, 22",
            "13, 23"})
        @DisplayName("a status other than '00' takes the MOVE 12 arm and abends before any read")
        void anyStatusOtherThanOkAbends(int cicsResp, String expectedStatus) {
            CardRepository repository = cardRepositoryMock();
            CardBrowse browse = mock(CardBrowse.class);
            when(browse.openResp()).thenReturn(cicsResp);
            when(repository.openBrowse(any(), any())).thenReturn(browse);
            CollectingSink sink = new CollectingSink();

            AbendException abend = assertThatAbend(repository, sink);

            // :124  MOVE 12 TO APPL-RESULT, and :126's guard then falls through to :129-:132.
            assertThat(abend.getReturnCode()).isEqualTo(AccountBalanceReaderJob.APPL_RESULT_FATAL);
            assertThat(sink.lines()).containsExactly(
                    AccountBalanceReaderJob.START_BANNER,
                    "ERROR OPENING CARDFILE",
                    FileStatus.toDisplayLine(expectedStatus),
                    AbendException.ABEND_DISPLAY_TEXT);
            // There is no APPL-EOF arm in 0000-CARDFILE-OPEN: :121-:125 has two branches, not three, so
            // '10' at the open is a failure and not an empty file.
            verify(browse, never()).readNext();
        }

        @Test
        @DisplayName("an untranslatable response becomes the permanent-error status and abends with 12")
        void anUntranslatableResponseAbends() {
            CardRepository repository = cardRepositoryMock();
            CardBrowse browse = mock(CardBrowse.class);
            when(browse.openResp()).thenReturn(FileStatus.INVREQ);
            when(repository.openBrowse(any(), any())).thenReturn(browse);
            CollectingSink sink = new CollectingSink();

            AbendException abend = assertThatAbend(repository, sink);

            assertThat(FileStatus.batchStatusOfCicsResp(FileStatus.INVREQ)).isEmpty();
            assertThat(abend.getReturnCode()).isEqualTo(AccountBalanceReaderJob.APPL_RESULT_FATAL);
            assertThat(sink.lines()).contains(PERMANENT_ERROR_LINE);
        }

        @Test
        @DisplayName("status '00' takes the MOVE 0 arm, and the pass proceeds to the read")
        void okProceedsToTheRead() {
            CardRepository repository = cardRepositoryMock();
            CardBrowse browse = mock(CardBrowse.class);
            when(browse.openResp()).thenReturn(FileStatus.NORMAL);
            when(browse.readNext()).thenReturn(CardReadResult.endOfFile());
            when(repository.openBrowse(any(), any())).thenReturn(browse);
            CollectingSink sink = new CollectingSink();

            jobOver(repository).execute(sink);

            // :122  MOVE 0 TO APPL-RESULT, so :126 IF APPL-AOK is true and :127 CONTINUEs.
            assertThat(FileStatus.batchStatusOfCicsResp(FileStatus.NORMAL))
                    .contains(FileStatus.OK);
            verify(browse).readNext();
            assertThat(sink.lines()).containsExactly(
                    AccountBalanceReaderJob.START_BANNER, AccountBalanceReaderJob.END_BANNER);
        }
    }

    // =================================================================================================
    // 9000-CARDFILE-CLOSE, the arithmetic - gate G28.
    //
    // These three statements are the whole arithmetic surface of CBACT02C: two ADDs and one SUBTRACT,
    // and no other program-level computation exists anywhere in the file. They are also the only place
    // the program spells a plain assignment as arithmetic, which is exactly why they get their own test:
    // the ladder is easy to "simplify" into MOVE 8 / MOVE 0 / MOVE 12 and thereby lose the audit trail
    // back to :137, :140 and :142.
    // =================================================================================================

    @Nested
    @DisplayName("9000-CARDFILE-CLOSE - the 8 -> 0-or-12 arithmetic ladder - gate G28")
    class CloseArithmeticLadder {

        @Test
        @DisplayName("ADD 8 TO ZERO GIVING APPL-RESULT yields 8 - app/cbl/CBACT02C.cbl:137")
        void addEightToZeroGivingYieldsEight() {
            assertThat(0 + 8).isEqualTo(AccountBalanceReaderJob.APPL_RESULT_ASSUMED_FAILURE);
            assertThat(AccountBalanceReaderJob.APPL_RESULT_ASSUMED_FAILURE)
                    .isEqualTo(AbendException.RETURN_CODE_ASSUMED_FAILURE)
                    .isEqualTo(8);
        }

        @Test
        @DisplayName("SUBTRACT APPL-RESULT FROM APPL-RESULT yields 0 - app/cbl/CBACT02C.cbl:140")
        void subtractingTheFieldFromItselfYieldsZero() {
            // The field less itself, whatever it held. Started at 8 by :137, so this is 8 - 8.
            int applResult = AccountBalanceReaderJob.APPL_RESULT_ASSUMED_FAILURE;
            applResult -= applResult;

            assertThat(applResult).isEqualTo(FileStatus.APPL_AOK).isZero();
        }

        @Test
        @DisplayName("ADD 12 TO ZERO GIVING APPL-RESULT yields 12 - app/cbl/CBACT02C.cbl:142")
        void addTwelveToZeroGivingYieldsTwelve() {
            assertThat(0 + 12).isEqualTo(AccountBalanceReaderJob.APPL_RESULT_FATAL);
            assertThat(AccountBalanceReaderJob.APPL_RESULT_FATAL)
                    .isEqualTo(AbendException.RETURN_CODE_IO_ERROR)
                    .isEqualTo(12);
        }

        @ParameterizedTest(name = "status ''{0}'' at :139 leaves APPL-RESULT at {1}")
        @CsvSource({
            "00, 0",
            "10, 12",
            "22, 12",
            "23, 12",
            "04, 12"})
        @DisplayName("the :139 test is equality with '00', so every other status leaves 12")
        void theLadderMapsEveryStatus(String status, int expected) {
            int applResult = AccountBalanceReaderJob.APPL_RESULT_ASSUMED_FAILURE;   // :137
            if (FileStatus.OK.equals(status)) {                                     // :139
                applResult -= applResult;                                           // :140
            } else {
                applResult = AccountBalanceReaderJob.APPL_RESULT_FATAL;             // :142
            }

            assertThat(applResult).isEqualTo(expected);
        }

        @Test
        @DisplayName("a close failure abends with 12 and never with the assumed 8")
        void aCloseFailureCarriesTwelveNotEight() {
            // The observable consequence of the ladder: the 8 that :137 deposits is never what reaches
            // the RETURN-CODE, because :139-:143 always overwrites it before the guard at :144 reads it.
            CardRepository repository = cardRepositoryMock();
            CardBrowse browse = mock(CardBrowse.class);
            when(repository.openBrowse(any(), any())).thenReturn(browse);
            when(browse.readNext()).thenReturn(CardReadResult.endOfFile());
            doThrow(new IllegalStateException("the file could not be released")).when(browse).endBrowse();

            AbendException abend = assertThatAbend(repository, new CollectingSink());

            assertThat(abend.getReturnCode())
                    .isEqualTo(AccountBalanceReaderJob.APPL_RESULT_FATAL)
                    .isNotEqualTo(AccountBalanceReaderJob.APPL_RESULT_ASSUMED_FAILURE);
        }

        @Test
        @DisplayName("a successful close leaves 0, so no abend follows and the end banner is written")
        void aSuccessfulCloseLeavesZero() {
            List<String> lines = linesFrom(repositoryReturning(List.of(CardReadResult.endOfFile())));

            // :144 IF APPL-AOK is true, :145 CONTINUEs, and :85's banner follows at the mainline.
            assertThat(lines).containsExactly(
                    AccountBalanceReaderJob.START_BANNER, AccountBalanceReaderJob.END_BANNER);
        }
    }

    // =================================================================================================
    // The emitted line, field by field at its absolute offset - gates G19 and G21.
    //
    // The elsewhere-asserted "the line equals the fixture row" is the strongest statement available about
    // the line as a whole, and it is deliberately kept. What it does not do is say WHERE anything is, so
    // a codec that transposed two adjacent same-width spans would satisfy it on every row that happened
    // to agree. These tests pin each CVACT02Y field to the offset the copybook gives it.
    // =================================================================================================

    @Nested
    @DisplayName("Record geometry - every CVACT02Y field at its declared offset - gates G19, G21")
    class RecordGeometry {

        @Test
        @DisplayName("the copybook field widths sum to the declared 150, FILLER included")
        void fieldWidthsSumToTheRecordLength() {
            // app/cpy/CVACT02Y.cpy: X(16) + 9(11) + 9(03) + X(50) + X(10) + X(01) + FILLER X(59).
            // 9(11) is eleven bytes and 9(03) is three: DISPLAY usage, one byte per digit, no sign and
            // no decimal point, so there is no p+2 adjustment to make on this record at all.
            int sum = CardRecord.CARD_NUM_LENGTH
                    + CardRecord.CARD_ACCT_ID_LENGTH
                    + CardRecord.CARD_CVV_CD_LENGTH
                    + CardRecord.CARD_EMBOSSED_NAME_LENGTH
                    + CardRecord.CARD_EXPIRAION_DATE_LENGTH
                    + CardRecord.CARD_ACTIVE_STATUS_LENGTH
                    + CardRecord.FILLER_LENGTH;

            assertThat(sum).isEqualTo(CardRecord.RECORD_LENGTH).isEqualTo(150);
            assertThat(16 + 11 + 3 + 50 + 10 + 1 + 59)
                    .as("the copybook's own numbers, added up independently of the constants")
                    .isEqualTo(150);
        }

        @Test
        @DisplayName("each field begins where the field before it ends, with no gap and no overlap")
        void offsetsAreContiguous() {
            assertThat(CardRecord.CARD_NUM_OFFSET).isZero();
            assertThat(CardRecord.CARD_ACCT_ID_OFFSET)
                    .isEqualTo(CardRecord.CARD_NUM_OFFSET + CardRecord.CARD_NUM_LENGTH);
            assertThat(CardRecord.CARD_CVV_CD_OFFSET)
                    .isEqualTo(CardRecord.CARD_ACCT_ID_OFFSET + CardRecord.CARD_ACCT_ID_LENGTH);
            assertThat(CardRecord.CARD_EMBOSSED_NAME_OFFSET)
                    .isEqualTo(CardRecord.CARD_CVV_CD_OFFSET + CardRecord.CARD_CVV_CD_LENGTH);
            assertThat(CardRecord.CARD_EXPIRAION_DATE_OFFSET)
                    .isEqualTo(CardRecord.CARD_EMBOSSED_NAME_OFFSET
                            + CardRecord.CARD_EMBOSSED_NAME_LENGTH);
            assertThat(CardRecord.CARD_ACTIVE_STATUS_OFFSET)
                    .isEqualTo(CardRecord.CARD_EXPIRAION_DATE_OFFSET
                            + CardRecord.CARD_EXPIRAION_DATE_LENGTH);
            assertThat(CardRecord.FILLER_OFFSET)
                    .isEqualTo(CardRecord.CARD_ACTIVE_STATUS_OFFSET
                            + CardRecord.CARD_ACTIVE_STATUS_LENGTH);
            assertThat(CardRecord.FILLER_OFFSET + CardRecord.FILLER_LENGTH)
                    .isEqualTo(CardRecord.RECORD_LENGTH);
        }

        @Test
        @DisplayName("every emitted line carries each field's own value at that field's own offset")
        void everyFieldSitsAtItsDeclaredOffset() {
            List<CardRecord> records = fixtureRecords();
            List<String> lines = linesFrom(repositoryOverWholeFixture());
            List<String> emitted = lines.subList(1, lines.size() - 1);

            assertThat(emitted).hasSameSizeAs(records);
            for (int i = 0; i < records.size(); i++) {
                CardRecord record = records.get(i);
                String line = emitted.get(i);
                assertThat(line).as("record %d width", i + 1).hasSize(CardRecord.RECORD_LENGTH);

                assertThat(span(line, CardRecord.CARD_NUM_OFFSET, CardRecord.CARD_NUM_LENGTH))
                        .as("record %d CARD-NUM PIC X(16) at offset 0", i + 1)
                        .isEqualTo(record.cardNum());
                // PIC 9(11) DISPLAY: eleven zero-filled digits, which is the zoned image DISPLAY writes.
                // Compared against the digits themselves rather than against any decimal rendering of
                // the decoded value - a scaled or grouped rendering would be a line the program cannot
                // produce.
                assertThat(span(line, CardRecord.CARD_ACCT_ID_OFFSET,
                                CardRecord.CARD_ACCT_ID_LENGTH))
                        .as("record %d CARD-ACCT-ID PIC 9(11) at offset 16", i + 1)
                        .containsOnlyDigits()
                        .isEqualTo(zoned(record.cardAcctId(), CardRecord.CARD_ACCT_ID_LENGTH));
                assertThat(span(line, CardRecord.CARD_CVV_CD_OFFSET, CardRecord.CARD_CVV_CD_LENGTH))
                        .as("record %d CARD-CVV-CD PIC 9(03) at offset 27", i + 1)
                        .containsOnlyDigits()
                        .isEqualTo(zoned(record.cardCvvCd(), CardRecord.CARD_CVV_CD_LENGTH));
                assertThat(span(line, CardRecord.CARD_EMBOSSED_NAME_OFFSET,
                                CardRecord.CARD_EMBOSSED_NAME_LENGTH))
                        .as("record %d CARD-EMBOSSED-NAME PIC X(50) at offset 30", i + 1)
                        .isEqualTo(record.cardEmbossedName());
                assertThat(span(line, CardRecord.CARD_EXPIRAION_DATE_OFFSET,
                                CardRecord.CARD_EXPIRAION_DATE_LENGTH))
                        .as("record %d CARD-EXPIRAION-DATE PIC X(10) at offset 80 - the copybook's own "
                                + "misspelling, preserved because field-for-field diffing depends on it",
                                i + 1)
                        .isEqualTo(record.cardExpiraionDate());
                assertThat(span(line, CardRecord.CARD_ACTIVE_STATUS_OFFSET,
                                CardRecord.CARD_ACTIVE_STATUS_LENGTH))
                        .as("record %d CARD-ACTIVE-STATUS PIC X(01) at offset 90", i + 1)
                        .isEqualTo(record.cardActiveStatus());
            }
        }

        @Test
        @DisplayName("a record encoded from scratch emits FILLER X(59) as spaces - gate G21")
        void fillerIsSpaceFilledWhenTheAreaIsBuiltFresh() {
            // Distinct from the elsewhere-asserted "a row's dirty FILLER survives verbatim". That test
            // covers READ INTO, where the area already holds the row's bytes. This one covers the codec
            // building an area from a decoded record, where FILLER is covered by no field of CVACT02Y and
            // so must be written as spaces: omit it and the record is 91 bytes and every later offset in
            // the file moves.
            CardRecord record = fixtureRecords().get(0);

            String image = record.encodeToImage(StandardCharsets.US_ASCII);

            assertThat(image).hasSize(CardRecord.RECORD_LENGTH);
            assertThat(span(image, CardRecord.FILLER_OFFSET, CardRecord.FILLER_LENGTH))
                    .hasSize(CardRecord.FILLER_LENGTH)
                    .isEqualTo(" ".repeat(CardRecord.FILLER_LENGTH));
        }

        @Test
        @DisplayName("the emitted line is US-ASCII bytes of exactly the record length")
        void theLineIsOneHundredAndFiftyBytesInTheNamedCharset() {
            // The charset is named at the byte boundary rather than defaulted: app/data/ASCII is the
            // authoritative fixture set and application-test.yml selects US-ASCII for it. A default-charset
            // encode would be correct here by accident and wrong on a host configured otherwise.
            List<String> lines = linesFrom(repositoryOverWholeFixture());

            for (String line : lines.subList(1, lines.size() - 1)) {
                assertThat(line.getBytes(StandardCharsets.US_ASCII))
                        .hasSize(CardRecord.RECORD_LENGTH);
            }
        }

        /**
         * The characters of {@code image} covering one copybook field.
         *
         * @param image  the record image
         * @param offset the field's zero-based offset
         * @param length the field's declared width
         * @return the field's span
         */
        private String span(String image, int offset, int length) {
            return image.substring(offset, offset + length);
        }

        /**
         * A {@code PIC 9(n)} DISPLAY span: the value in decimal, zero-filled on the left to {@code n}.
         *
         * @param value the value the span holds
         * @param width the field's declared width
         * @return the zoned display image
         */
        private String zoned(long value, int width) {
            String digits = Long.toString(value);
            return "0".repeat(width - digits.length()) + digits;
        }
    }

    // =================================================================================================
    // The fixture itself. Every expectation in this file is seeded from it, so its shape is asserted
    // rather than assumed - and it is the COPY under src/test/resources, never app/data/ASCII in place,
    // because the reference trees are read-only (practice B3).
    // =================================================================================================

    @Nested
    @DisplayName("The card fixture - 50 records of exactly 150 characters")
    class FixtureContract {

        @Test
        @DisplayName("the fixture holds fifty rows, every one exactly 150 characters wide")
        void everyRowIsTheDeclaredWidth() {
            List<String> rows = fixtureRows();

            assertThat(rows).hasSize(FIXTURE_RECORD_COUNT);
            assertThat(rows).allSatisfy(row -> assertThat(row)
                    .as("app/cpy/CVACT02Y.cpy declares RECLN 150 and the fixture agrees on every row")
                    .hasSize(CardRecord.RECORD_LENGTH));
            assertThat(rows.stream().map(String::length).distinct().toList())
                    .as("uniform width, so there is no short or long row hiding in the middle")
                    .containsExactly(CardRecord.RECORD_LENGTH);
        }

        @Test
        @DisplayName("every row round-trips through the codec unchanged, so decoding loses nothing")
        void rowsRoundTripThroughTheCodec() {
            for (String row : fixtureRows()) {
                CardRecord decoded = CardRecord.decodeImage(row, FIXTURE_CHARSET);

                assertThat(decoded.encodeToImage(FIXTURE_CHARSET))
                        .as("a row whose FILLER is spaces re-encodes to itself byte for byte")
                        .isEqualTo(row);
            }
        }

        @Test
        @DisplayName("the fixture is read in US-ASCII, named rather than defaulted")
        void theFixtureCharsetIsNamed() {
            assertThat(FIXTURE_CHARSET).isSameAs(StandardCharsets.US_ASCII);
            assertThat(FIXTURE).isEqualTo("/fixtures/carddata.txt");
        }
    }

    // =================================================================================================
    // Gate G46 - no dataset name is written in Java.
    // =================================================================================================

    @Nested
    @DisplayName("Dataset name provenance - gate G46")
    class DatasetNameProvenance {

        /** The dataset-name prefix the JCL uses, which must appear in no Java source. */
        private static final String MAINFRAME_DSN_PREFIX = "AWS.M2.CARDDEMO.";

        @Test
        @DisplayName("the binding is read from carddemo.datasets.CARDFILE, composed not guessed")
        void thePropertyKeyIsTheConfigurationPrefixPlusTheDdName() {
            ConfigurationProperties bound =
                    DatasetBindings.class.getAnnotation(ConfigurationProperties.class);

            assertThat(bound)
                    .as("the catalogue must be bound to a configuration prefix, or nothing is external")
                    .isNotNull();
            assertThat(bound.prefix()).isEqualTo("carddemo.datasets");
            assertThat(bound.prefix() + "." + AccountBalanceReaderJob.DD_NAME)
                    .as("app/jcl/READCARD.jcl:25's DD name is the key under the prefix")
                    .isEqualTo("carddemo.datasets.CARDFILE");
        }

        @Test
        @DisplayName("no String constant the job declares carries an AWS.M2.CARDDEMO dataset name")
        void theJobDeclaresNoDatasetLiteral() throws ReflectiveOperationException {
            for (Field field : AccountBalanceReaderJob.class.getDeclaredFields()) {
                if (field.isSynthetic() || !Modifier.isStatic(field.getModifiers())
                        || field.getType() != String.class) {
                    continue;
                }
                field.setAccessible(true);
                Object value = field.get(null);

                assertThat((String) value)
                        .as("constant %s must not name a dataset - the name is a configuration value, "
                                + "resolved through cardfileDatasetName()", field.getName())
                        .doesNotContain(MAINFRAME_DSN_PREFIX);
            }
        }

        @Test
        @DisplayName("whatever the configuration says is what the job reports and reads")
        void theConfiguredNameIsTheNameUsed() {
            // Two different values through the same code path, so the answer demonstrably comes from
            // configuration rather than from a constant that happens to match one of them.
            String first = "CARDDEMO.SITE.ONE.CARDDATA.VSAM.KSDS";
            String second = "CARDDEMO.SITE.TWO.CARDDATA.VSAM.KSDS";

            AccountBalanceReaderJob one = new AccountBalanceReaderJob(
                    batchConfig(sourceDerivedContract(), first), cardRepositoryMock(),
                    FIXTURE_CHARSET, new SingleValueProvider<>(null));
            AccountBalanceReaderJob two = new AccountBalanceReaderJob(
                    batchConfig(sourceDerivedContract(), second), cardRepositoryMock(),
                    FIXTURE_CHARSET, new SingleValueProvider<>(null));

            assertThat(one.cardfileDatasetName()).isEqualTo(first);
            assertThat(two.cardfileDatasetName()).isEqualTo(second);
        }

        @Test
        @DisplayName("the DD name the job resolves is passed to the repository as the DD name")
        void theDdNameReachesTheRepository() {
            CardRepository repository = repositoryReturning(List.of(CardReadResult.endOfFile()));

            linesFrom(repository);

            verify(repository).addressing(any(), eq("CARDFILE"));
        }
    }
}
