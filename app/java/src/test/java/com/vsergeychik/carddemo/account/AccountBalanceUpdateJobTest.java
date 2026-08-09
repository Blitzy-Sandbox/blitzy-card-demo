package com.vsergeychik.carddemo.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vsergeychik.carddemo.account.AccountBalanceUpdateJob.ExecutionSummary;
import com.vsergeychik.carddemo.account.AccountBalanceUpdateJob.SysoutSink;
import com.vsergeychik.carddemo.card.CardXrefRepository;
import com.vsergeychik.carddemo.card.CardXrefRepository.BrowseCursor;
import com.vsergeychik.carddemo.card.CardXrefRepository.ReadResult;
import com.vsergeychik.carddemo.card.model.CardXrefRecord;
import com.vsergeychik.carddemo.common.AbendException;
import com.vsergeychik.carddemo.common.FileStatus;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.config.BatchConfig;
import com.vsergeychik.carddemo.config.BatchConfig.JobContract;
import com.vsergeychik.carddemo.config.BatchConfig.JobContracts;
import com.vsergeychik.carddemo.config.BatchConfig.JobParameterContract;
import com.vsergeychik.carddemo.config.BatchConfig.StepContract;
import com.vsergeychik.carddemo.config.BatchConfig.StopRequestedException;
import com.vsergeychik.carddemo.config.BatchConfig.StopSignal;
import com.vsergeychik.carddemo.config.CobolCharsetConfig;
import com.vsergeychik.carddemo.config.DataSourceConfig;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBinding;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBindings;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobInterruptedException;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.scope.context.StepContext;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.env.RandomValuePropertySource;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Tests for {@link AccountBalanceUpdateJob}, the Java translation of {@code app/cbl/CBACT03C.cbl}.
 *
 * <h2>What this suite is really guarding</h2>
 * Two things, and they are the two a reader of the class name would get wrong.
 *
 * <p><strong>That the job performs no update.</strong> The mandated name says
 * {@code AccountBalanceUpdateJob}; the program reads the card cross-reference file and prints it. The
 * repository is a spy in every execution test here and is verified never to have been asked for
 * anything but a base-cluster browse - and separately, by reflection, to publish no write-side method
 * that could have been asked for.
 *
 * <p><strong>That every record is displayed twice.</strong>
 * {@code app/cbl/CBACT03C.cbl} displays the record area at {@code :96}, inside
 * {@code 1000-XREFFILE-GET-NEXT}, and again at {@code :78} in the mainline. The assertions below pin
 * the count, the pairing and the byte-for-byte identity of each pair, because a translation that
 * emitted one line per record would look completely reasonable and be wrong for all 50 records of the
 * fixture.
 *
 * <h2>How it runs</h2>
 * Plain JUnit 5 with Mockito and no application context, except for the single wiring test that
 * starts one deliberately. The program body is {@link AccountBalanceUpdateJob#execute(SysoutSink)},
 * a plain method, so every branch - three status ladders, both {@code 88}-level conditions in both
 * truth states, and all three abend paths - is driven by an ordinary call with a capturing sink. No
 * {@code JobLauncher}, no database, no clock, no locale and no platform default charset is involved.
 *
 * <p>{@code review_rules} returns exactly one line - "No user rules provided." - so no user rule
 * governs this file. The gates it enforces directly are G1 and G3 (it compiles and the beans wire),
 * G19 and G21 (a displayed record is 50 bytes with its {@code FILLER} present as spaces), G22 (no
 * binary floating point), G35 (the abend's return code, abend code and timing), G45 (the alternate
 * index is never opened), G46 (no dataset name in Java), G47 (every file-status outcome per call
 * site), G49 and G50 (both sides of every branch, including both {@code 88}-levels) and G52 (no
 * wildcard import).
 */
@DisplayName("AccountBalanceUpdateJob - CBACT03C: reads the cross reference, updates nothing, "
        + "displays every record twice")
class AccountBalanceUpdateJobTest {

    /** The fixture code page, named explicitly rather than taken from the platform. */
    private static final Charset ASCII = StandardCharsets.US_ASCII;

    /** The codec the expectations are rendered with, matching the one the job builds. */
    private static final FixedWidthCodec CODEC = new FixedWidthCodec(ASCII);

    /** The derived fixture on the test classpath; {@code app/data/ASCII} itself is never opened. */
    private static final String FIXTURE = "/fixtures/cardxref.txt";

    /** Rows in {@code app/data/ASCII/cardxref.txt}. */
    private static final int FIXTURE_ROW_COUNT = 50;

    /** Bytes per fixture row: 36, because the fixture omits the trailing {@code FILLER X(14)}. */
    private static final int FIXTURE_ROW_WIDTH = 36;

    /** A dataset name for the test bindings. Not a mainframe name, and never read as one. */
    private static final String TEST_DSNAME = "CARDDEMO.TEST.CARDXREF.VSAM.KSDS";

    /** The subject's source path inside the checkout, for the source-level hygiene checks. */
    private static final String SUBJECT_SOURCE_PATH = "app/java/src/main/java/com/vsergeychik/"
            + "carddemo/account/AccountBalanceUpdateJob.java";

    /** A star import, in the one shape Java can express it. */
    private static final Pattern WILDCARD_IMPORT =
            Pattern.compile("import\\s+(?:static\\s+)?[\\w.]+\\.\\*\\s*;");

    /** The property that activates a profile, for the wiring test. */
    private static final String ACTIVE_PROFILE_PROPERTY = "spring.profiles.active=";

    // =================================================================================================
    // Fixtures and doubles.
    // =================================================================================================

    /**
     * A {@link SysoutSink} that keeps every line, in order, exactly as it was given.
     *
     * <p>It deliberately does not deduplicate: the whole point of this suite is that consecutive
     * identical lines are the correct output.
     */
    private static final class CapturingSysout implements SysoutSink {

        /** The lines, in emission order. */
        private final List<String> lines = new ArrayList<>();

        @Override
        public void display(String line) {
            lines.add(line);
        }

        /** @return an unmodifiable snapshot of what was displayed */
        private List<String> lines() {
            return Collections.unmodifiableList(new ArrayList<>(lines));
        }
    }

    /**
     * An {@link ObjectProvider} over zero or one bean, so the container's two states - a published
     * {@code SysoutSink} and none - are both reachable without an application context.
     *
     * <p>Only the three lookup methods this module uses are overridden; the interface's own default
     * {@code getIfAvailable(Supplier)} then runs for real, which is the method under test when the
     * job falls back to its default sink.
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

    /**
     * The dataset catalogue the job resolves {@code XREFFILE} from.
     *
     * @param recordLength the width to declare
     * @param dsname       the dataset name to declare
     * @return a catalogue holding just that entry
     */
    private static DatasetBindings bindings(int recordLength, String dsname) {
        return bindings(recordLength, dsname, dsname);
    }

    /**
     * @param recordLength the width to declare
     * @param dsname       the dataset name to declare for this job's {@code XREFFILE} DD
     * @param baseDsname   the dataset name to declare for {@code CCXREF}, the key the cross-reference
     *                     repository is bound to
     * @return a catalogue holding both entries
     */
    private static DatasetBindings bindings(int recordLength, String dsname, String baseDsname) {
        DatasetBindings catalogue = new DatasetBindings();
        catalogue.put(AccountBalanceUpdateJob.XREFFILE_DD_NAME, new DatasetBinding(dsname,
                DatasetBinding.KSDS, false, "FB", null, recordLength, "CVACT03Y",
                CardXrefRecord.XREF_CARD_NUM_LENGTH, null, null, null));
        // The job's JCL says XREFFILE (app/jcl/READXREF.jcl:25-26); the repository it browses is bound to
        // the CICS file name CCXREF. The job proves at construction that the two name one dataset, so
        // both keys are declared here, and passing two different names exercises the rejection.
        catalogue.put(CardXrefRepository.BASE_DD_NAME, new DatasetBinding(baseDsname,
                DatasetBinding.KSDS, false, "FB", null, recordLength, "CVACT03Y",
                CardXrefRecord.XREF_CARD_NUM_LENGTH, null, null, null));
        return catalogue;
    }

    /**
     * The job-contract catalogue, in the shape {@code application.yml} declares for this job.
     *
     * @param program    the program name to declare on the job and its step
     * @param stepName   the step name to declare
     * @param gated      whether the step declares {@code COND=(0,NE)} gating
     * @param parameters the declared job parameters
     * @return a catalogue holding just that contract
     */
    private static JobContracts contracts(String program, String stepName, boolean gated,
            List<JobParameterContract> parameters) {
        JobContracts catalogue = new JobContracts();
        catalogue.put(AccountBalanceUpdateJob.JOB_KEY, new JobContract(program, parameters,
                List.of(new StepContract(stepName, program, gated)), null, Map.of()));
        return catalogue;
    }

    /** @return the contract catalogue exactly as {@code application.yml} declares it */
    private static JobContracts validContracts() {
        return contracts(AccountBalanceUpdateJob.PROGRAM_NAME, AccountBalanceUpdateJob.STEP_NAME,
                false, List.of());
    }

    /**
     * A {@link BatchConfig} over the supplied catalogues, with batch plumbing that is present but
     * never touched unless a bean method asks for it.
     *
     * @param contracts the job contracts
     * @param bindings  the dataset catalogue
     * @return the configured seam
     */
    private static BatchConfig batchConfig(JobContracts contracts, DatasetBindings bindings) {
        return new BatchConfig(new SuppliedProvider<>(mock(JobRepository.class)),
                new SuppliedProvider<>(mock(PlatformTransactionManager.class)), contracts, bindings);
    }

    /** @return a {@link BatchConfig} over the catalogues configuration actually declares */
    private static BatchConfig validBatchConfig() {
        return batchConfig(validContracts(), bindings(CardXrefRecord.RECORD_LENGTH, TEST_DSNAME));
    }

    /**
     * The job under test, over a caller-supplied repository and no published sink.
     *
     * @param repository the repository double
     * @return the job
     */
    private static AccountBalanceUpdateJob job(CardXrefRepository repository) {
        return new AccountBalanceUpdateJob(validBatchConfig(), repository, ASCII,
                new SuppliedProvider<>(null));
    }

    /**
     * A step execution shaped as the framework builds one, so a tasklet can be driven exactly as a
     * running step drives it.
     *
     * <p>Built by hand rather than with a test factory from another artifact, because the dependency set
     * is closed and the constructors needed are public API. Only the step name and the
     * {@code terminateOnly} flag are read by anything under test here.
     *
     * @return a fresh step execution, not asked to stop
     */
    private static StepExecution stepExecution() {
        return new StepExecution(AccountBalanceUpdateJob.STEP_NAME, new JobExecution(1L));
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
     * A probe that permits the given number of records and then reports a stop.
     *
     * <p>It sets {@code terminateOnly} on the real step execution and then delegates to the real
     * {@link StopSignal}, so the refusal is produced by the production probe and the framework's own
     * interruption policy rather than by a stand-in that merely throws the same type.
     *
     * @param stepExecution the execution to mark
     * @param permitted     how many consultations return before the stop is requested
     * @return the probe
     */
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

    /**
     * A browse cursor that opens cleanly, returns the given records and closes cleanly.
     *
     * @param records the records to return, in order
     * @return the cursor double
     */
    private static BrowseCursor cursorOver(List<CardXrefRecord> records) {
        List<ReadResult> reads = new ArrayList<>();
        records.forEach(record -> reads.add(xrefFound(CardXrefRepository.BASE_DD_NAME, record)));
        reads.add(ReadResult.endOfFile(CardXrefRepository.BASE_DD_NAME));
        return cursorYielding(FileStatus.OK, FileStatus.OK, reads);
    }

    /**
     * A browse cursor with an explicit open status, close status and read sequence.
     *
     * @param openStatus  what {@code OPEN INPUT} reports
     * @param closeStatus what {@code CLOSE} reports
     * @param reads       the reads, in order; the last is repeated if the program asks again
     * @return the cursor double
     */
    private static BrowseCursor cursorYielding(String openStatus, String closeStatus,
            List<ReadResult> reads) {
        BrowseCursor cursor = mock(BrowseCursor.class);
        when(cursor.openStatus()).thenReturn(openStatus);
        when(cursor.closeBrowse()).thenReturn(closeStatus);
        if (!reads.isEmpty()) {
            when(cursor.readNext()).thenReturn(reads.get(0),
                    reads.subList(1, reads.size()).toArray(new ReadResult[0]));
        }
        return cursor;
    }

    /**
     * A repository whose browse is the supplied cursor.
     *
     * @param cursor the cursor its open returns
     * @return the repository double
     */
    /**
     * A cross-reference repository mock that hands itself back when the job re-binds it to its own DD.
     *
     * <p>{@code CBACT03C} browses the DD {@code app/jcl/READXREF.jcl:25-26} binds, so the job asks the
     * repository for a view addressing {@value AccountBalanceUpdateJob#XREFFILE_DD_NAME} before it
     * opens. A bare mock answers {@code null} to that, so a stubbed cursor would hang off an instance
     * the job never touches.
     *
     * <p>Returning the same mock is what the real repository does whenever the DD resolves to the
     * dataset it already addresses - the shipped configuration, where {@code XREFFILE} and
     * {@code CCXREF} are two names for one cluster.
     *
     * @return the mock, with the re-binding stubbed
     */
    private static CardXrefRepository cardXrefRepositoryMock() {
        CardXrefRepository repository = mock(CardXrefRepository.class);
        when(repository.addressing(any(), any(), any(), any())).thenReturn(repository);
        return repository;
    }

    private static CardXrefRepository repositoryWith(BrowseCursor cursor) {
        CardXrefRepository repository = cardXrefRepositoryMock();
        when(repository.openBrowse()).thenReturn(cursor);
        return repository;
    }

    /**
     * A repository whose browse returns the given records.
     *
     * @param records the records the browse yields
     * @return the repository double
     */
    private static CardXrefRepository repositoryOver(List<CardXrefRecord> records) {
        // The cursor is built BEFORE the repository is stubbed. Stubbing one mock inside an unfinished
        // when() for another is what Mockito reports as unfinished stubbing.
        return repositoryWith(cursorOver(records));
    }

    /**
     * The fixture rows, verbatim and 36 bytes wide.
     *
     * @return the rows of {@code src/test/resources/fixtures/cardxref.txt}
     */
    private static List<String> fixtureRows() {
        List<String> rows = new ArrayList<>();
        try (InputStream stream = AccountBalanceUpdateJobTest.class.getResourceAsStream(FIXTURE)) {
            assertThat(stream).as("the derived fixture must be on the test classpath at %s", FIXTURE)
                    .isNotNull();
            for (String line : new String(stream.readAllBytes(), ASCII).split("\n", -1)) {
                String row = line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
                if (!row.isEmpty()) {
                    rows.add(row);
                }
            }
        } catch (IOException failure) {
            throw new UncheckedIOException("Unable to read the CARDXREF fixture " + FIXTURE, failure);
        }
        return rows;
    }

    /**
     * The fixture as records, each 36-byte row widened to the 50 the copybook declares.
     *
     * @return the 50 cross-reference records the fixture holds, in fixture order
     */
    private static List<CardXrefRecord> fixtureRecords() {
        List<CardXrefRecord> records = new ArrayList<>();
        for (String row : fixtureRows()) {
            String widened = CODEC.padToDeclaredWidth(row, CardXrefRecord.RECORD_LENGTH);
            records.add(CardXrefRecord.decode(CODEC.encodeImage(widened, "a fixture row"), CODEC));
        }
        return records;
    }

    /**
     * The 50-character image a record must be displayed as, composed here from the row rather than
     * from the class under test.
     *
     * @param row a 36-byte fixture row
     * @return the row followed by the fourteen spaces of the trailing {@code FILLER}
     */
    private static String expectedImage(String row) {
        return row + " ".repeat(CardXrefRecord.FILLER_LENGTH);
    }

    /**
     * The subject's own source text.
     *
     * @return the contents of {@code AccountBalanceUpdateJob.java}
     * @throws IOException if it cannot be read
     */
    private static String subjectSource() throws IOException {
        Path candidate = Path.of("").toAbsolutePath();
        while (candidate != null) {
            Path resolved = candidate.resolve(SUBJECT_SOURCE_PATH);
            if (Files.exists(resolved)) {
                return Files.readString(resolved, StandardCharsets.UTF_8);
            }
            candidate = candidate.getParent();
        }
        throw new IllegalStateException("Could not find " + SUBJECT_SOURCE_PATH + " at or above "
                + Path.of("").toAbsolutePath());
    }

    // =================================================================================================
    // The mandated name against the verified behaviour - migration rule R1.
    // =================================================================================================

    // =================================================================================================
    // The declared DD is the DD browsed - the DD-mapping finding.
    // =================================================================================================

    @Nested
    @DisplayName("the DD this job declares is the DD it browses - app/jcl/READXREF.jcl:25-26")
    class TheDeclaredDdDrivesTheBrowse {

        @Test
        @DisplayName("the browse is taken through the resolved XREFFILE binding, not through CCXREF")
        void theBrowseGoesThroughTheDeclaredDd() {
            CardXrefRepository repository = repositoryOver(List.of());

            job(repository).execute(new CapturingSysout());

            ArgumentCaptor<DatasetBinding> used = ArgumentCaptor.forClass(DatasetBinding.class);
            verify(repository).addressing(used.capture(),
                    eq(AccountBalanceUpdateJob.XREFFILE_DD_NAME), isNull(), anyString());
            assertThat(used.getValue().dsname()).isEqualTo(TEST_DSNAME);
            assertThat(AccountBalanceUpdateJob.XREFFILE_DD_NAME).isEqualTo("XREFFILE");
        }

        @Test
        @DisplayName("no alternate-index binding is handed over, because this program opens no path")
        void noAlternateIndexBindingIsSupplied() {
            // app/cbl/CBACT03C.cbl:29-33 is one SELECT with no ALTERNATE RECORD KEY and READXREF.jcl
            // declares one DD. Supplying an alternate-index binding would assert a path this program
            // never opens - and the alternate-index DD name is still named, so a diagnostic can say
            // which key would have been at fault.
            CardXrefRepository repository = repositoryOver(List.of());

            job(repository).execute(new CapturingSysout());

            verify(repository).addressing(any(), anyString(), isNull(),
                    eq(CardXrefRepository.ALTERNATE_INDEX_BATCH_DD_NAME));
        }

        @Test
        @DisplayName("the browse is taken on the instance the re-binding returned")
        void theBrowseIsTakenOnTheRebindingResult() {
            CardXrefRepository injected = cardXrefRepositoryMock();
            CardXrefRepository rebound = repositoryOver(List.of());
            when(injected.addressing(any(), anyString(), any(), anyString())).thenReturn(rebound);

            job(injected).execute(new CapturingSysout());

            verify(rebound).openBrowse();
            verify(injected, never()).openBrowse();
        }

        @Test
        @DisplayName("the re-binding is asked for once per run")
        void theRebindingHappensOncePerRun() {
            CardXrefRepository repository = repositoryOver(fixtureRecords());

            job(repository).execute(new CapturingSysout());

            verify(repository, times(1)).addressing(any(), anyString(), any(), anyString());
        }
    }

    @Nested
    @DisplayName("The name says Update; CBACT03C updates nothing")
    class UpdatesNothing {

        @Test
        @DisplayName("a full pass asks the repository for one browse and for nothing else")
        void aFullPassOnlyBrowses() {
            CardXrefRepository repository = repositoryOver(fixtureRecords());

            job(repository).execute(new CapturingSysout());

            verify(repository).openBrowse();
            // G45: the CXACAIX path is a different access path in a different key order, and
            // app/cbl/CBACT03C.cbl:29-33 opens the base cluster in card-number order only.
            verify(repository, never()).readByAccountIdViaAltIndex(anyLong());
            verify(repository, never()).readByAccountIdViaAltIndex(anyString());
            verify(repository, never()).readByCardNumber(anyString());
        }

        @Test
        @DisplayName("the repository publishes no write-side method this job could have called")
        void theRepositoryPublishesNoWriteMethod() {
            List<String> writeVerbs = List.of("write", "rewrite", "add", "insert", "update", "delete",
                    "put", "save", "store", "merge", "persist");

            List<String> offenders = new ArrayList<>();
            for (Method method : CardXrefRepository.class.getDeclaredMethods()) {
                if (!Modifier.isPublic(method.getModifiers()) || method.isSynthetic()) {
                    continue;
                }
                if (writeVerbs.stream().anyMatch(verb -> namesTheVerb(method.getName(), verb))) {
                    offenders.add(method.getName());
                }
            }

            assertThat(offenders)
                    .as("CBACT03C opens its file OPEN INPUT and contains no WRITE, REWRITE or DELETE, "
                            + "so a write-side method on the cross reference would be an API this "
                            + "program has no statement for")
                    .isEmpty();
        }

        /**
         * Whether a method name opens with a verb <em>as a word</em>, in camel case.
         *
         * <p>A plain prefix test is not the same question. {@code addressing} opens with the letters of
         * {@code add} and is a read-side selector - it returns this repository addressing the dataset a
         * DD names - so a prefix test reports a write method that does not exist, and a guard that cries
         * wolf gets relaxed rather than obeyed. Requiring the next character to be upper case, or the
         * name to be the verb exactly, keeps {@code add}, {@code addRecord}, {@code writeImage} and
         * {@code deleteAll} caught while letting an unrelated word through.
         *
         * @param methodName the declared method name, in its own case
         * @param verb       the lower-case verb to test for
         * @return {@code true} when the name is the verb, or the verb followed by a new camel-case word
         */
        private boolean namesTheVerb(String methodName, String verb) {
            String name = methodName.toLowerCase(Locale.ROOT);
            if (!name.startsWith(verb)) {
                return false;
            }
            return methodName.length() == verb.length()
                    || Character.isUpperCase(methodName.charAt(verb.length()));
        }

        @Test
        @DisplayName("no method of the job is named for writing, and none for the account master")
        void noMethodPromisesWhatTheProgramDoesNot() {
            List<String> methodNames = Stream.of(AccountBalanceUpdateJob.class.getDeclaredMethods())
                    .filter(method -> !method.isSynthetic())
                    .map(Method::getName)
                    .map(name -> name.toLowerCase(Locale.ROOT))
                    .toList();

            // The mandated class name is carried by the bean methods and cannot be helped. What can be
            // helped is any member promising an operation the program does not perform.
            assertThat(methodNames)
                    .as("CBACT03C has no WRITE, REWRITE or DELETE, and reads no account master")
                    .isNotEmpty()
                    .noneMatch(name -> name.contains("write"))
                    .noneMatch(name -> name.contains("rewrite"))
                    .noneMatch(name -> name.contains("delete"))
                    .noneMatch(name -> name.contains("acctfile"))
                    .noneMatch(name -> name.contains("acctdat"));
        }
    }

    // =================================================================================================
    // The transcribed literals. Byte-exact, and each names XREFFILE.
    // =================================================================================================

    @Nested
    @DisplayName("Every displayed literal is transcribed from app/cbl/CBACT03C.cbl")
    class TranscribedLiterals {

        @Test
        @DisplayName("the two banners are the lines at :71 and :85, naming CBACT03C")
        void theBannersAreByteExact() {
            assertThat(AccountBalanceUpdateJob.START_OF_EXECUTION)
                    .isEqualTo("START OF EXECUTION OF PROGRAM CBACT03C");
            assertThat(AccountBalanceUpdateJob.END_OF_EXECUTION)
                    .isEqualTo("END OF EXECUTION OF PROGRAM CBACT03C");
        }

        @Test
        @DisplayName("the three failure messages are the lines at :129, :110 and :147, naming XREFFILE")
        void theFailureMessagesAreByteExact() {
            assertThat(AccountBalanceUpdateJob.ERROR_OPENING_XREFFILE)
                    .isEqualTo("ERROR OPENING XREFFILE");
            assertThat(AccountBalanceUpdateJob.ERROR_READING_XREFFILE)
                    .isEqualTo("ERROR READING XREFFILE");
            assertThat(AccountBalanceUpdateJob.ERROR_CLOSING_XREFFILE)
                    .isEqualTo("ERROR CLOSING XREFFILE");
        }

        @Test
        @DisplayName("the identity constants match the JCL, the program and application.yml")
        void theIdentityConstantsMatchTheirSources() {
            assertThat(AccountBalanceUpdateJob.PROGRAM_NAME).isEqualTo("CBACT03C");
            assertThat(AccountBalanceUpdateJob.STEP_NAME).isEqualTo("STEP05");
            assertThat(AccountBalanceUpdateJob.XREFFILE_DD_NAME).isEqualTo("XREFFILE");
            assertThat(AccountBalanceUpdateJob.JOB_KEY).isEqualTo("account-balance-update-job");
            assertThat(AccountBalanceUpdateJob.JOB_NAME).isEqualTo("accountBalanceUpdateJob");
        }

        @Test
        @DisplayName("the configuration bean name differs from the job bean name, or neither wires")
        void theConfigurationAndJobBeanNamesDiffer() {
            assertThat(AccountBalanceUpdateJob.CONFIGURATION_BEAN_NAME)
                    .isNotEqualTo(AccountBalanceUpdateJob.JOB_NAME);
            assertThat(AccountBalanceUpdateJob.STEP_BEAN_NAME)
                    .isNotEqualTo(AccountBalanceUpdateJob.JOB_NAME)
                    .isNotEqualTo(AccountBalanceUpdateJob.STEP_NAME);
        }

        @Test
        @DisplayName("two displays per record and two banners per execution")
        void theLineCountsAreStatedOnce() {
            assertThat(AccountBalanceUpdateJob.DISPLAYS_PER_RECORD).isEqualTo(2);
            assertThat(AccountBalanceUpdateJob.BANNER_LINES).isEqualTo(2);
        }

        @Test
        @DisplayName("the END-OF-FILE flag values are the PIC X(01) characters, not booleans")
        void theEndOfFileFlagValuesAreCharacters() {
            assertThat(AccountBalanceUpdateJob.NOT_AT_END_OF_FILE).isEqualTo("N");
            assertThat(AccountBalanceUpdateJob.AT_END_OF_FILE).isEqualTo("Y");
        }

        @Test
        @DisplayName("the APPL-RESULT values are the shared ones, not privately redefined")
        void theApplResultValuesComeFromTheSharedVocabulary() {
            assertThat(AccountBalanceUpdateJob.APPL_RESULT_ASSUMED_FAILURE).isEqualTo(8);
            assertThat(AccountBalanceUpdateJob.APPL_RESULT_FATAL).isEqualTo(12);
            assertThat(AccountBalanceUpdateJob.APPL_RESULT_FATAL)
                    .isEqualTo(CardXrefRepository.APPL_RESULT_FATAL)
                    .isEqualTo(AbendException.RETURN_CODE_IO_ERROR);
            assertThat(FileStatus.APPL_AOK).isZero();
            assertThat(FileStatus.APPL_EOF).isEqualTo(16);
        }

        @Test
        @DisplayName("the charset bean name is the one the charset configuration actually publishes")
        void theCharsetBeanNameMatchesTheConfiguration() {
            // The constant is restated in the subject rather than imported, because that class is
            // outside the subject's declared dependency set. This is the assertion that makes the
            // restatement safe: the two cannot drift without failing here.
            assertThat(AccountBalanceUpdateJob.DATASET_CHARSET_BEAN_NAME)
                    .isEqualTo(CobolCharsetConfig.DATASET_CHARSET_BEAN_NAME);
        }
    }

    // =================================================================================================
    // The fixture run - the parity expectation, end to end.
    // =================================================================================================

    @Nested
    @DisplayName("A pass over the 50-record fixture")
    class TheFixtureRun {

        @Test
        @DisplayName("emits exactly 102 lines: one banner, 100 record lines, one banner")
        void emitsOneHundredAndTwoLines() {
            List<CardXrefRecord> records = fixtureRecords();
            CapturingSysout sysout = new CapturingSysout();

            ExecutionSummary summary = job(repositoryOver(records)).execute(sysout);

            assertThat(records).hasSize(FIXTURE_ROW_COUNT);
            assertThat(sysout.lines()).hasSize(102);
            assertThat(sysout.lines().get(0)).isEqualTo(AccountBalanceUpdateJob.START_OF_EXECUTION);
            assertThat(sysout.lines().get(101)).isEqualTo(AccountBalanceUpdateJob.END_OF_EXECUTION);
            assertThat(summary.recordsRead()).isEqualTo(FIXTURE_ROW_COUNT);
            assertThat(summary.recordLinesDisplayed()).isEqualTo(100);
            assertThat(summary.totalLinesDisplayed()).isEqualTo(102);
            assertThat(summary.returnCode()).isEqualTo(AbendException.RETURN_CODE_OK);
        }

        @Test
        @DisplayName("displays each record twice, as two byte-identical consecutive lines")
        void displaysEachRecordTwice() {
            List<String> rows = fixtureRows();
            CapturingSysout sysout = new CapturingSysout();

            job(repositoryOver(fixtureRecords())).execute(sysout);

            List<String> lines = sysout.lines();
            for (int record = 0; record < rows.size(); record++) {
                int first = AccountBalanceUpdateJob.BANNER_LINES - 1
                        + record * AccountBalanceUpdateJob.DISPLAYS_PER_RECORD;
                assertThat(lines.get(first))
                        .as("record %d, the display at app/cbl/CBACT03C.cbl:96", record + 1)
                        .isEqualTo(expectedImage(rows.get(record)));
                assertThat(lines.get(first + 1))
                        .as("record %d, the display at app/cbl/CBACT03C.cbl:78 - byte-identical to "
                                + "the one before it", record + 1)
                        .isEqualTo(lines.get(first));
            }
        }

        @Test
        @DisplayName("every record line is 50 bytes with the trailing FILLER present as 14 spaces")
        void everyRecordLineIsFiftyBytes() {
            CapturingSysout sysout = new CapturingSysout();

            job(repositoryOver(fixtureRecords())).execute(sysout);

            List<String> recordLines = sysout.lines().subList(1, sysout.lines().size() - 1);
            assertThat(recordLines).hasSize(100);
            assertThat(recordLines).allSatisfy(line -> {
                assertThat(line).hasSize(CardXrefRecord.RECORD_LENGTH);
                assertThat(line.substring(CardXrefRecord.FILLER_OFFSET))
                        .isEqualTo(" ".repeat(CardXrefRecord.FILLER_LENGTH));
                assertThat(line).doesNotContain("\n").doesNotContain("\r");
            });
        }

        @Test
        @DisplayName("the records appear in browse order, which is the fixture's own order")
        void recordsAppearInBrowseOrder() {
            List<String> rows = fixtureRows();
            CapturingSysout sysout = new CapturingSysout();

            job(repositoryOver(fixtureRecords())).execute(sysout);

            List<String> distinctInOrder = new ArrayList<>();
            sysout.lines().stream()
                    .filter(line -> line.length() == CardXrefRecord.RECORD_LENGTH)
                    .forEach(line -> {
                        if (distinctInOrder.isEmpty()
                                || !distinctInOrder.get(distinctInOrder.size() - 1).equals(line)) {
                            distinctInOrder.add(line);
                        }
                    });

            assertThat(distinctInOrder)
                    .containsExactlyElementsOf(rows.stream().map(
                            AccountBalanceUpdateJobTest::expectedImage).toList());
        }

        @Test
        @DisplayName("the fixture is the 36-byte form, so the widening is doing real work")
        void theFixtureIsTheShortForm() {
            assertThat(fixtureRows()).hasSize(FIXTURE_ROW_COUNT)
                    .allSatisfy(row -> assertThat(row).hasSize(FIXTURE_ROW_WIDTH));
        }

        @Test
        @DisplayName("a row whose FILLER X(14) is not spaces is displayed twice, as it stands")
        void aRowsFillerSurvivesBothDisplays() {
            // READ ... INTO CARD-XREF-RECORD (:93) fills the whole 50-byte area from the row, and both
            // DISPLAY CARD-XREF-RECORD sites (:96 and :78) write that area. CVACT03Y's trailing
            // FILLER X(14) is covered by no field, so whatever the row held there appears on both lines.
            // Rendering the decoded record instead would blank it - 14 wrong bytes, twice per record.
            CardXrefRecord record = new CardXrefRecord(fixtureRecords().get(0).xrefCardNum(), 50, 50L);
            String clean = xrefImageOf(record);
            String dirty = clean.substring(0, CardXrefRecord.FILLER_OFFSET)
                    + "*".repeat(CardXrefRecord.FILLER_LENGTH);
            BrowseCursor cursor = cursorYielding(FileStatus.OK, FileStatus.OK, List.of(
                    ReadResult.found(CardXrefRepository.BASE_DD_NAME, record, dirty),
                    ReadResult.endOfFile(CardXrefRepository.BASE_DD_NAME)));
            CapturingSysout sysout = new CapturingSysout();

            job(repositoryWith(cursor)).execute(sysout);

            assertThat(sysout.lines()).hasSize(4);
            assertThat(sysout.lines().get(1))
                    .as("the display at app/cbl/CBACT03C.cbl:96 - the row's own 50 bytes")
                    .isEqualTo(dirty)
                    .isNotEqualTo(clean);
            assertThat(sysout.lines().get(2))
                    .as("the display at app/cbl/CBACT03C.cbl:78 - the same unchanged record area")
                    .isEqualTo(dirty);
        }
    }

    // =================================================================================================
    // The loop's boundaries: no records, and one record.
    // =================================================================================================

    @Nested
    @DisplayName("The read loop at its boundaries")
    class LoopBoundaries {

        @Test
        @DisplayName("an empty dataset still emits both banners and nothing between them")
        void anEmptyDatasetEmitsBothBanners() {
            CapturingSysout sysout = new CapturingSysout();

            ExecutionSummary summary = job(repositoryOver(List.of())).execute(sysout);

            assertThat(sysout.lines()).containsExactly(AccountBalanceUpdateJob.START_OF_EXECUTION,
                    AccountBalanceUpdateJob.END_OF_EXECUTION);
            assertThat(summary.recordsRead()).isZero();
            assertThat(summary.totalLinesDisplayed()).isEqualTo(AccountBalanceUpdateJob.BANNER_LINES);
        }

        @Test
        @DisplayName("one record produces four lines: banner, image, the same image, banner")
        void oneRecordProducesFourLines() {
            String row = fixtureRows().get(0);
            CapturingSysout sysout = new CapturingSysout();

            ExecutionSummary summary =
                    job(repositoryOver(List.of(fixtureRecords().get(0)))).execute(sysout);

            assertThat(sysout.lines()).containsExactly(AccountBalanceUpdateJob.START_OF_EXECUTION,
                    expectedImage(row), expectedImage(row), AccountBalanceUpdateJob.END_OF_EXECUTION);
            assertThat(summary.recordsRead()).isEqualTo(1);
            assertThat(summary.recordLinesDisplayed())
                    .isEqualTo(AccountBalanceUpdateJob.DISPLAYS_PER_RECORD);
        }

        @Test
        @DisplayName("the browse is opened once and closed once, whatever the record count")
        void theBrowseIsOpenedOnceAndClosedOnce() {
            BrowseCursor cursor = cursorOver(fixtureRecords());
            CardXrefRepository repository = repositoryWith(cursor);

            job(repository).execute(new CapturingSysout());

            verify(repository).openBrowse();
            verify(cursor).closeBrowse();
            // 50 records plus the read that reports AT END: app/cbl/CBACT03C.cbl:74 loops until the
            // flag is set, and the flag is only set by a read.
            verify(cursor, times(FIXTURE_ROW_COUNT + 1)).readNext();
        }
    }

    // =================================================================================================
    // The three failure paths, each with its own message - app/cbl/CBACT03C.cbl:129, :110 and :147.
    // =================================================================================================

    @Nested
    @DisplayName("The three abend paths, each naming its own paragraph's failure")
    class AbendPaths {

        /** The status the repository reports for a backend it could not reach: renders "9000". */
        private static final String PERMANENT = CardXrefRepository.PERMANENT_ERROR_STATUS;

        @Test
        @DisplayName("an open that fails displays ERROR OPENING XREFFILE, the status, then abends")
        void anOpenFailureAbends() {
            BrowseCursor cursor = cursorYielding(PERMANENT, FileStatus.OK,
                    List.of(ReadResult.endOfFile(CardXrefRepository.BASE_DD_NAME)));
            CardXrefRepository repository = repositoryWith(cursor);
            CapturingSysout sysout = new CapturingSysout();
            AccountBalanceUpdateJob subject = job(repository);

            AbendException abend = assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> subject.execute(sysout))
                    .actual();

            assertThat(sysout.lines()).containsExactly(AccountBalanceUpdateJob.START_OF_EXECUTION,
                    AccountBalanceUpdateJob.ERROR_OPENING_XREFFILE,
                    FileStatus.toDisplayLine(PERMANENT), AbendException.ABEND_DISPLAY_TEXT);
            assertThat(abend.getReason()).contains(AccountBalanceUpdateJob.ERROR_OPENING_XREFFILE
                    + " - " + FileStatus.toDisplayLine(PERMANENT));
            // CEE3ABD terminates the task, so neither the loop nor the close paragraph is reached.
            verify(cursor, never()).readNext();
            verify(cursor, never()).closeBrowse();
        }

        @Test
        @DisplayName("a read that fails mid-pass keeps the lines already emitted, then abends")
        void aReadFailureAbendsAfterTheLinesAlreadyEmitted() {
            String row = fixtureRows().get(0);
            BrowseCursor cursor = cursorYielding(FileStatus.OK, FileStatus.OK, List.of(
                    xrefFound(CardXrefRepository.BASE_DD_NAME, fixtureRecords().get(0)),
                    ReadResult.other(CardXrefRepository.BASE_DD_NAME, PERMANENT)));
            CapturingSysout sysout = new CapturingSysout();
            AccountBalanceUpdateJob subject = job(repositoryWith(cursor));

            AbendException abend = assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> subject.execute(sysout))
                    .actual();

            assertThat(sysout.lines()).containsExactly(AccountBalanceUpdateJob.START_OF_EXECUTION,
                    expectedImage(row), expectedImage(row),
                    AccountBalanceUpdateJob.ERROR_READING_XREFFILE,
                    FileStatus.toDisplayLine(PERMANENT), AbendException.ABEND_DISPLAY_TEXT);
            assertThat(abend.getReason()).contains(AccountBalanceUpdateJob.ERROR_READING_XREFFILE
                    + " - " + FileStatus.toDisplayLine(PERMANENT));

            // What parity requires is that 9000-XREFFILE-CLOSE did not RUN: CEE3ABD terminates the task
            // at :113, so the paragraph's own DISPLAY and its APPL-RESULT transitions never happen. The
            // containsExactly above is what proves that - ERROR CLOSING XREFFILE and the end banner are
            // both absent - and the abend's reason names the read, not the close.
            //
            // The handle is nevertheless released once on the way out, silently. That is not the
            // paragraph running: closeBrowse() issues no I/O, emits no DISPLAY and cannot alter the
            // abend, so the SYSOUT line sequence and the return code - the whole of what CBACT03C
            // observably produces - are identical either way. Asserting exactly once also pins that the
            // cleanup cannot fire twice or fire on a path that already closed.
            verify(cursor, times(1)).closeBrowse();
            assertThat(sysout.lines())
                    .doesNotContain(AccountBalanceUpdateJob.ERROR_CLOSING_XREFFILE)
                    .doesNotContain(AccountBalanceUpdateJob.END_OF_EXECUTION);
        }

        @Test
        @DisplayName("a close that fails displays ERROR CLOSING XREFFILE after every record line")
        void aCloseFailureAbendsAfterTheLoop() {
            String row = fixtureRows().get(0);
            BrowseCursor cursor = cursorYielding(FileStatus.OK, PERMANENT, List.of(
                    xrefFound(CardXrefRepository.BASE_DD_NAME, fixtureRecords().get(0)),
                    ReadResult.endOfFile(CardXrefRepository.BASE_DD_NAME)));
            CapturingSysout sysout = new CapturingSysout();
            AccountBalanceUpdateJob subject = job(repositoryWith(cursor));

            AbendException abend = assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> subject.execute(sysout))
                    .actual();

            assertThat(sysout.lines()).containsExactly(AccountBalanceUpdateJob.START_OF_EXECUTION,
                    expectedImage(row), expectedImage(row),
                    AccountBalanceUpdateJob.ERROR_CLOSING_XREFFILE,
                    FileStatus.toDisplayLine(PERMANENT), AbendException.ABEND_DISPLAY_TEXT);
            assertThat(abend.getReason()).contains(AccountBalanceUpdateJob.ERROR_CLOSING_XREFFILE
                    + " - " + FileStatus.toDisplayLine(PERMANENT));
            // The end banner belongs after the close, so a failing close replaces it.
            assertThat(sysout.lines()).doesNotContain(AccountBalanceUpdateJob.END_OF_EXECUTION);
        }

        @Test
        @DisplayName("every abend carries RETURN-CODE 12, ABCODE 999 and TIMING 0 for CBACT03C")
        void everyAbendCarriesTheCeeThreeAbdArguments() {
            BrowseCursor cursor = cursorYielding(PERMANENT, FileStatus.OK,
                    List.of(ReadResult.endOfFile(CardXrefRepository.BASE_DD_NAME)));
            AccountBalanceUpdateJob subject = job(repositoryWith(cursor));

            AbendException abend = assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> subject.execute(new CapturingSysout()))
                    .actual();

            assertThat(abend.getProgram()).isEqualTo(AccountBalanceUpdateJob.PROGRAM_NAME);
            assertThat(abend.getReturnCode()).isEqualTo(AccountBalanceUpdateJob.APPL_RESULT_FATAL);
            assertThat(abend.getAbendCode()).hasValue(AbendException.STANDARD_ABEND_CODE);
            assertThat(abend.getTiming()).hasValue(AbendException.STANDARD_TIMING);
        }

        @Test
        @DisplayName("a status the program does not name takes the WHEN OTHER arm and abends")
        void anUnnamedStatusTakesTheOtherArm() {
            // Neither '23' nor '22' is tested by name at app/cbl/CBACT03C.cbl:94 or :98, so both move
            // 12 into APPL-RESULT at :101 - which is exactly what a batch program testing only '00'
            // and '10' does with them (gate G47).
            List<ReadResult> unnamed = List.of(
                    ReadResult.notFound(CardXrefRepository.BASE_DD_NAME),
                    xrefDuplicate(CardXrefRepository.BASE_DD_NAME, fixtureRecords().get(0),
                            FileStatus.DUPKEY));

            for (ReadResult result : unnamed) {
                BrowseCursor cursor =
                        cursorYielding(FileStatus.OK, FileStatus.OK, List.of(result));
                CapturingSysout sysout = new CapturingSysout();
                AccountBalanceUpdateJob subject = job(repositoryWith(cursor));

                AbendException abend = assertThatExceptionOfType(AbendException.class)
                        .as("file status '%s'", result.status())
                        .isThrownBy(() -> subject.execute(sysout))
                        .actual();

                assertThat(sysout.lines()).endsWith(AccountBalanceUpdateJob.ERROR_READING_XREFFILE,
                        FileStatus.toDisplayLine(result.status()),
                        AbendException.ABEND_DISPLAY_TEXT);
                assertThat(abend.getReturnCode()).isEqualTo(AccountBalanceUpdateJob.APPL_RESULT_FATAL);
            }
        }

        @Test
        @DisplayName("a read reporting success without a record is a contradiction and fails loudly")
        void aSuccessWithoutARecordFailsLoudly() {
            // ReadResult's own invariant makes this unconstructible through its factories - a record is
            // present exactly for OK and DUPLICATE - so the only way to reach the guard is to fabricate
            // the contradiction. It is worth reaching: a silent fall-through here would display nothing
            // for a record app/cbl/CBACT03C.cbl:96 displays, and the pass would look complete.
            ReadResult contradictory = mock(ReadResult.class);
            when(contradictory.status()).thenReturn(FileStatus.OK);
            when(contradictory.record()).thenReturn(Optional.empty());
            BrowseCursor cursor =
                    cursorYielding(FileStatus.OK, FileStatus.OK, List.of(contradictory));
            AccountBalanceUpdateJob subject = job(repositoryWith(cursor));

            assertThatIllegalStateException()
                    .isThrownBy(() -> subject.execute(new CapturingSysout()))
                    .withMessageContaining(AccountBalanceUpdateJob.XREFFILE_DD_NAME)
                    .withMessageContaining("carried no record");
        }

        @Test
        @DisplayName("the IO status line is the shared 9910-DISPLAY-IO-STATUS form, not a local one")
        void theStatusLineIsTheSharedForm() {
            BrowseCursor cursor = cursorYielding(PERMANENT, FileStatus.OK,
                    List.of(ReadResult.endOfFile(CardXrefRepository.BASE_DD_NAME)));
            CapturingSysout sysout = new CapturingSysout();
            AccountBalanceUpdateJob subject = job(repositoryWith(cursor));

            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> subject.execute(sysout));

            assertThat(sysout.lines().get(2))
                    .startsWith(FileStatus.DISPLAY_PREFIX)
                    .isEqualTo("FILE STATUS IS: NNNN9000")
                    .hasSize(FileStatus.DISPLAY_PREFIX.length() + FileStatus.STATUS_IMAGE_LENGTH);
        }
    }

    // =================================================================================================
    // The constructor: what it requires, and what it refuses.
    // =================================================================================================

    @Nested
    @DisplayName("The constructor validates the configured contract against the JCL")
    class ConstructorContract {

        @Test
        @DisplayName("it accepts the contract application.yml actually declares, and reports it back")
        void itAcceptsTheConfiguredContract() {
            AccountBalanceUpdateJob subject = job(repositoryOver(List.of()));

            assertThat(subject.stepName()).isEqualTo(AccountBalanceUpdateJob.STEP_NAME);
            assertThat(subject.xrefFileDatasetName()).isEqualTo(TEST_DSNAME);
        }

        @Test
        @DisplayName("every collaborator is required")
        void everyCollaboratorIsRequired() {
            CardXrefRepository repository = cardXrefRepositoryMock();
            ObjectProvider<SysoutSink> sinks = new SuppliedProvider<>(null);

            assertThatNullPointerException().isThrownBy(() ->
                    new AccountBalanceUpdateJob(null, repository, ASCII, sinks));
            assertThatNullPointerException().isThrownBy(() ->
                    new AccountBalanceUpdateJob(validBatchConfig(), null, ASCII, sinks));
            assertThatNullPointerException().isThrownBy(() ->
                    new AccountBalanceUpdateJob(validBatchConfig(), repository, null, sinks));
            assertThatNullPointerException().isThrownBy(() ->
                    new AccountBalanceUpdateJob(validBatchConfig(), repository, ASCII, null));
        }

        @Test
        @DisplayName("XREFFILE and CCXREF pointing at different datasets is refused at construction")
        void divergingDdNamesAreRefused() {
            // The step's JCL names XREFFILE (app/jcl/READXREF.jcl:25-26); the repository it browses is
            // bound to the CICS file name CCXREF. Both keys carry independent overrides in
            // application.yml, so a deployment can point them at different datasets - and the job would
            // then browse one its own DD statement never named, silently. A COBOL step cannot do this,
            // because the DD statement is the binding.
            BatchConfig diverging = batchConfig(validContracts(),
                    bindings(CardXrefRecord.RECORD_LENGTH, TEST_DSNAME, "TEST.SOMETHING.ELSE"));

            assertThatIllegalStateException().isThrownBy(() -> new AccountBalanceUpdateJob(diverging,
                    mock(CardXrefRepository.class), ASCII, new SuppliedProvider<>(null)))
                    .withMessageContaining(AccountBalanceUpdateJob.XREFFILE_DD_NAME)
                    .withMessageContaining(CardXrefRepository.BASE_DD_NAME)
                    .withMessageContaining("TEST.SOMETHING.ELSE");
        }

        @Test
        @DisplayName("XREFFILE and CCXREF naming one dataset is accepted, which is the shipped default")
        void agreeingDdNamesAreAccepted() {
            assertThat(job(mock(CardXrefRepository.class)).xrefFileDatasetName())
                    .isEqualTo(TEST_DSNAME);
        }

        @Test
        @DisplayName("a contract naming another program is refused, on the job and on the step")
        void aContractNamingAnotherProgramIsRefused() {
            JobContracts wrongJobProgram = contracts("CBACT01C", AccountBalanceUpdateJob.STEP_NAME,
                    false, List.of());
            JobContracts wrongStepProgram = new JobContracts();
            wrongStepProgram.put(AccountBalanceUpdateJob.JOB_KEY,
                    new JobContract(AccountBalanceUpdateJob.PROGRAM_NAME, List.of(),
                            List.of(new StepContract(AccountBalanceUpdateJob.STEP_NAME, "CBACT02C",
                                    false)), null, Map.of()));

            assertThatIllegalStateException().isThrownBy(() -> new AccountBalanceUpdateJob(
                    batchConfig(wrongJobProgram, bindings(CardXrefRecord.RECORD_LENGTH, TEST_DSNAME)),
                    cardXrefRepositoryMock(), ASCII, new SuppliedProvider<>(null)))
                    .withMessageContaining("carddemo.jobs." + AccountBalanceUpdateJob.JOB_KEY
                            + ".program")
                    .withMessageContaining("CBACT01C");

            assertThatIllegalStateException().isThrownBy(() -> new AccountBalanceUpdateJob(
                    batchConfig(wrongStepProgram, bindings(CardXrefRecord.RECORD_LENGTH, TEST_DSNAME)),
                    cardXrefRepositoryMock(), ASCII, new SuppliedProvider<>(null)))
                    .withMessageContaining(AccountBalanceUpdateJob.STEP_NAME + "].program")
                    .withMessageContaining("CBACT02C");
        }

        @Test
        @DisplayName("a second step declared beside STEP05 is refused: READXREF.jcl has one EXEC")
        void anAddedStepIsRefused() {
            // The program, gating and name checks all resolve STEP05 by name and find it whether it
            // stands alone or first of two, so none of them can see an added step. Here the extra step
            // is a well-formed copy of the real one, which is the shape a copy-paste edit produces.
            JobContracts withASecondStep = new JobContracts();
            withASecondStep.put(AccountBalanceUpdateJob.JOB_KEY,
                    new JobContract(AccountBalanceUpdateJob.PROGRAM_NAME, List.of(),
                            List.of(new StepContract(AccountBalanceUpdateJob.STEP_NAME,
                                            AccountBalanceUpdateJob.PROGRAM_NAME, false),
                                    new StepContract("STEP06",
                                            AccountBalanceUpdateJob.PROGRAM_NAME, false)),
                            null, Map.of()));

            assertThatIllegalStateException().isThrownBy(() -> new AccountBalanceUpdateJob(
                    batchConfig(withASecondStep, bindings(CardXrefRecord.RECORD_LENGTH, TEST_DSNAME)),
                    cardXrefRepositoryMock(), ASCII, new SuppliedProvider<>(null)))
                    .withMessageContaining("does not declare the step sequence of "
                            + "app/jcl/READXREF.jcl:22")
                    .withMessageContaining("configured: [STEP05/CBACT03C, STEP06/CBACT03C]")
                    .withMessageContaining("required:   [STEP05/CBACT03C]");
        }

        @Test
        @DisplayName("the shipped single-step sequence is what the class requires")
        void theShippedSequenceIsRequired() {
            assertThat(AccountBalanceUpdateJob.REQUIRED_STEPS)
                    .containsExactly(new StepContract(AccountBalanceUpdateJob.STEP_NAME,
                            AccountBalanceUpdateJob.PROGRAM_NAME, false));
            assertThat(validContracts().get(AccountBalanceUpdateJob.JOB_KEY).steps())
                    .isEqualTo(AccountBalanceUpdateJob.REQUIRED_STEPS);
        }

        @Test
        @DisplayName("a contract without STEP05 is refused by the contract itself")
        void aContractWithoutStepZeroFiveIsRefused() {
            JobContracts renamedStep = contracts(AccountBalanceUpdateJob.PROGRAM_NAME, "STEP15",
                    false, List.of());

            assertThatIllegalStateException().isThrownBy(() -> new AccountBalanceUpdateJob(
                    batchConfig(renamedStep, bindings(CardXrefRecord.RECORD_LENGTH, TEST_DSNAME)),
                    cardXrefRepositoryMock(), ASCII, new SuppliedProvider<>(null)))
                    .withMessageContaining("declares no step named '"
                            + AccountBalanceUpdateJob.STEP_NAME + "'");
        }

        @Test
        @DisplayName("a COND-gated step is refused: READXREF.jcl carries no COND")
        void aGatedStepIsRefused() {
            JobContracts gated = contracts(AccountBalanceUpdateJob.PROGRAM_NAME,
                    AccountBalanceUpdateJob.STEP_NAME, true, List.of());

            assertThatIllegalStateException().isThrownBy(() -> new AccountBalanceUpdateJob(
                    batchConfig(gated, bindings(CardXrefRecord.RECORD_LENGTH, TEST_DSNAME)),
                    cardXrefRepositoryMock(), ASCII, new SuppliedProvider<>(null)))
                    .withMessageContaining("require-preceding-exit-code-zero")
                    .withMessageContaining("no COND");
        }

        @Test
        @DisplayName("a declared job parameter is refused: the EXEC PGM= carries no PARM")
        void aDeclaredParameterIsRefused() {
            JobContracts parameterised = contracts(AccountBalanceUpdateJob.PROGRAM_NAME,
                    AccountBalanceUpdateJob.STEP_NAME, false,
                    List.of(new JobParameterContract(BatchConfig.PARM_DATE_PARAMETER, "string",
                            "2022071800")));

            assertThatIllegalStateException().isThrownBy(() -> new AccountBalanceUpdateJob(
                    batchConfig(parameterised, bindings(CardXrefRecord.RECORD_LENGTH, TEST_DSNAME)),
                    cardXrefRepositoryMock(), ASCII, new SuppliedProvider<>(null)))
                    .withMessageContaining("no PARM at all");
        }

        @ParameterizedTest
        @ValueSource(ints = {FIXTURE_ROW_WIDTH, 49, 51, 0})
        @DisplayName("a record length other than 50 is refused, including the fixture's own 36")
        void aRecordLengthOtherThanFiftyIsRefused(int width) {
            assertThatIllegalStateException().isThrownBy(() -> new AccountBalanceUpdateJob(
                    batchConfig(validContracts(), bindings(width, TEST_DSNAME)),
                    cardXrefRepositoryMock(), ASCII, new SuppliedProvider<>(null)))
                    .withMessageContaining("record-length is " + width)
                    .withMessageContaining("CVACT03Y");
        }

        @Test
        @DisplayName("a blank or absent dataset name is refused")
        void aBlankDatasetNameIsRefused() {
            for (String unusable : new String[] {null, "", "   "}) {
                assertThatIllegalStateException().isThrownBy(() -> new AccountBalanceUpdateJob(
                        batchConfig(validContracts(),
                                bindings(CardXrefRecord.RECORD_LENGTH, unusable)),
                        cardXrefRepositoryMock(), ASCII, new SuppliedProvider<>(null)))
                        .withMessageContaining("carddemo.datasets."
                                + AccountBalanceUpdateJob.XREFFILE_DD_NAME + ".dsname");
            }
        }

        @Test
        @DisplayName("an unbound XREFFILE DD is refused by the dataset catalogue")
        void anUnboundDatasetIsRefused() {
            assertThatIllegalStateException().isThrownBy(() -> new AccountBalanceUpdateJob(
                    batchConfig(validContracts(), new DatasetBindings()),
                    cardXrefRepositoryMock(), ASCII, new SuppliedProvider<>(null)))
                    .withMessageContaining("No dataset binding is configured for DD name '"
                            + AccountBalanceUpdateJob.XREFFILE_DD_NAME + "'");
        }

        @Test
        @DisplayName("a code page that cannot hold a digit in one byte is refused")
        void aMultiByteCodePageIsRefused() {
            assertThatIllegalArgumentException().isThrownBy(() -> new AccountBalanceUpdateJob(
                    validBatchConfig(), cardXrefRepositoryMock(), StandardCharsets.UTF_16,
                    new SuppliedProvider<>(null)))
                    .withMessageContaining("byte(s)");
        }
    }

    // =================================================================================================
    // The Spring Batch assembly: one job, one tasklet step, no parameters.
    // =================================================================================================

    @Nested
    @DisplayName("The batch assembly is one job over one tasklet step named STEP05")
    class BatchAssembly {

        @Test
        @DisplayName("the job is named for the class and the step for the JCL step")
        void theJobAndStepCarryTheExpectedNames() {
            AccountBalanceUpdateJob subject = job(repositoryOver(List.of()));

            Step step = subject.accountBalanceUpdateStep();
            Job batchJob = subject.accountBalanceUpdateJob();

            assertThat(step.getName()).isEqualTo(AccountBalanceUpdateJob.STEP_NAME);
            assertThat(batchJob.getName()).isEqualTo(AccountBalanceUpdateJob.JOB_NAME);
        }

        @Test
        @DisplayName("the tasklet runs the program once and reports FINISHED")
        void theTaskletRunsTheProgramOnce() throws Exception {
            CapturingSysout sysout = new CapturingSysout();
            AccountBalanceUpdateJob subject = new AccountBalanceUpdateJob(validBatchConfig(),
                    repositoryOver(fixtureRecords()), ASCII, new SuppliedProvider<>(sysout));

            RepeatStatus status = subject.accountBalanceUpdateTasklet()
                    .execute(null, chunkContext(stepExecution()));

            assertThat(status).isEqualTo(RepeatStatus.FINISHED);
            assertThat(sysout.lines()).hasSize(102);
        }

        @Test
        @DisplayName("a published SysoutSink bean is preferred over the default")
        void aPublishedSinkIsPreferred() throws Exception {
            CapturingSysout published = new CapturingSysout();
            AccountBalanceUpdateJob subject = new AccountBalanceUpdateJob(validBatchConfig(),
                    repositoryOver(List.of()), ASCII, new SuppliedProvider<>(published));

            subject.accountBalanceUpdateTasklet().execute(null, chunkContext(stepExecution()));

            assertThat(published.lines()).containsExactly(
                    AccountBalanceUpdateJob.START_OF_EXECUTION,
                    AccountBalanceUpdateJob.END_OF_EXECUTION);
        }

        @Test
        @DisplayName("with no published sink the default one is used, and the program still runs")
        void withNoPublishedSinkTheDefaultIsUsed() throws Exception {
            // An empty dataset, so the fallback writes the two banner lines to the process's standard
            // output and nothing more. The assertion is that the fallback resolves and the program
            // completes; where those two lines land is the deployment's business.
            AccountBalanceUpdateJob subject = job(repositoryOver(List.of()));

            RepeatStatus status = subject.accountBalanceUpdateTasklet()
                    .execute(null, chunkContext(stepExecution()));

            assertThat(status).isEqualTo(RepeatStatus.FINISHED);
            assertThat(subject.defaultSysoutSink()).isNotNull();
        }
    }

    @Nested
    @DisplayName("Bounded cancellation - the pass yields to a stop request between records")
    class BoundedCancellation {

        @Test
        @DisplayName("no stop requested leaves the pass exactly as it was: 102 lines, unchanged")
        void withoutAStopTheWholePassRuns() {
            CapturingSysout sysout = new CapturingSysout();
            AccountBalanceUpdateJob subject = new AccountBalanceUpdateJob(validBatchConfig(),
                    repositoryOver(fixtureRecords()), ASCII, new SuppliedProvider<>(sysout));

            ExecutionSummary summary = subject.execute(sysout, StopSignal.of(stepExecution()));

            // The signal is consulted on every iteration and changes nothing while nothing is pending.
            // Fifty records, two DISPLAY lines each, plus the two banners.
            assertThat(sysout.lines()).hasSize(102);
            assertThat(summary.recordsRead()).isEqualTo(FIXTURE_ROW_COUNT);
        }

        @Test
        @DisplayName("a stop requested before the pass starts ends it at the first record boundary")
        void aStopBeforeTheFirstRecordEndsThePassAtOnce() {
            StepExecution stepExecution = stepExecution();
            stepExecution.setTerminateOnly();
            CapturingSysout sysout = new CapturingSysout();
            AccountBalanceUpdateJob subject = job(repositoryOver(fixtureRecords()));

            assertThatExceptionOfType(StopRequestedException.class)
                    .isThrownBy(() -> subject.execute(sysout, StopSignal.of(stepExecution)))
                    .withMessageContaining(AccountBalanceUpdateJob.STEP_NAME)
                    .withMessageContaining("NO write is retried")
                    // The cause is what makes AbstractStep report the step as STOPPED rather than
                    // FAILED, so it is asserted rather than left as an implementation detail.
                    .withCauseInstanceOf(JobInterruptedException.class);

            // The OPEN happened and its banner was written; not one record line was.
            assertThat(sysout.lines())
                    .containsExactly(AccountBalanceUpdateJob.START_OF_EXECUTION);
        }

        @Test
        @DisplayName("a stop part way through leaves the record in flight displayed twice, not once")
        void aStopPartWayThroughLeavesTheRecordInFlightComplete() {
            StepExecution stepExecution = stepExecution();
            CapturingSysout sysout = new CapturingSysout();
            AccountBalanceUpdateJob subject = job(repositoryOver(fixtureRecords()));
            int stopAfter = 4;

            assertThatExceptionOfType(StopRequestedException.class).isThrownBy(() ->
                    subject.execute(sysout, signalStoppingAfter(stepExecution, stopAfter)));

            // CBACT03C displays each record TWICE - :96 inside the read paragraph and :78 in the loop -
            // so a pass stopped between records must show an EVEN number of record lines. An odd count
            // would mean the probe had landed mid-record, which is the one thing its position rules out.
            assertThat(sysout.lines()).hasSize(stopAfter * 2 + 1);
            assertThat(sysout.lines().size() - 1).isEven();

            // And the close banner is NOT written, exactly as it is not written on an abend.
            assertThat(sysout.lines()).doesNotContain(AccountBalanceUpdateJob.END_OF_EXECUTION);
        }

        @Test
        @DisplayName("the single-argument overload runs unbounded, so every existing caller is unchanged")
        void theSingleArgumentOverloadIsUnbounded() {
            CapturingSysout withSignal = new CapturingSysout();
            CapturingSysout withoutSignal = new CapturingSysout();

            job(repositoryOver(fixtureRecords())).execute(withoutSignal);
            job(repositoryOver(fixtureRecords())).execute(withSignal, StopSignal.RUNNING);

            assertThat(withoutSignal.lines()).isEqualTo(withSignal.lines());
        }

        @Test
        @DisplayName("a null stop signal is refused rather than silently treated as 'never stop'")
        void aNullStopSignalIsRefused() {
            AccountBalanceUpdateJob subject = job(repositoryOver(fixtureRecords()));

            assertThatNullPointerException()
                    .isThrownBy(() -> subject.execute(new CapturingSysout(), null))
                    .withMessageContaining("StopSignal.RUNNING");
        }

        @Test
        @DisplayName("the tasklet takes its signal from the step execution the framework supplies")
        void theTaskletTakesItsSignalFromTheStepExecution() {
            StepExecution stepExecution = stepExecution();
            stepExecution.setTerminateOnly();
            CapturingSysout sysout = new CapturingSysout();
            AccountBalanceUpdateJob subject = new AccountBalanceUpdateJob(validBatchConfig(),
                    repositoryOver(fixtureRecords()), ASCII, new SuppliedProvider<>(sysout));

            // Driven exactly as TaskletStep drives it, so this asserts the wiring and not just the
            // program: a tasklet that ignored the chunk context would run the whole pass here.
            assertThatExceptionOfType(StopRequestedException.class).isThrownBy(() ->
                    subject.accountBalanceUpdateTasklet().execute(null, chunkContext(stepExecution)));

            assertThat(sysout.lines())
                    .containsExactly(AccountBalanceUpdateJob.START_OF_EXECUTION);
        }
    }

    // =================================================================================================
    // The structural declarations that decide whether any of this wires at all.
    // =================================================================================================

    @Nested
    @DisplayName("The declarations Spring reads")
    class SpringDeclarations {

        @Test
        @DisplayName("the class is a @Configuration under its own bean name, and is not final")
        void theClassIsAConfigurationUnderItsOwnName() {
            Configuration configuration =
                    AccountBalanceUpdateJob.class.getDeclaredAnnotation(Configuration.class);

            assertThat(configuration).isNotNull();
            assertThat(configuration.value())
                    .isEqualTo(AccountBalanceUpdateJob.CONFIGURATION_BEAN_NAME);
            assertThat(configuration.proxyBeanMethods())
                    .as("the job bean method calls the step bean method, so the class must be proxied "
                            + "for the call to return the step bean rather than a second instance")
                    .isTrue();
            assertThat(Modifier.isFinal(AccountBalanceUpdateJob.class.getModifiers()))
                    .as("a final @Configuration cannot be subclassed by the proxy")
                    .isFalse();
        }

        @Test
        @DisplayName("the two @Bean methods declare the job and step bean names explicitly")
        void theBeanMethodsDeclareTheirNames() throws NoSuchMethodException {
            Method jobBean = AccountBalanceUpdateJob.class
                    .getDeclaredMethod("accountBalanceUpdateJob");
            Method stepBean = AccountBalanceUpdateJob.class
                    .getDeclaredMethod("accountBalanceUpdateStep");

            // Read through value() rather than name(): the two are @AliasFor one another, and that
            // alias is resolved by Spring's annotation machinery rather than by plain reflection.
            assertThat(jobBean.getDeclaredAnnotation(Bean.class).value())
                    .containsExactly(AccountBalanceUpdateJob.JOB_NAME);
            assertThat(stepBean.getDeclaredAnnotation(Bean.class).value())
                    .containsExactly(AccountBalanceUpdateJob.STEP_BEAN_NAME);
            assertThat(Stream.of(AccountBalanceUpdateJob.class.getDeclaredMethod(
                            "accountBalanceUpdateTasklet").getDeclaredAnnotations())
                    .map(annotation -> annotation.annotationType().getSimpleName()).toList())
                    .as("the step is the bean; a published tasklet would be a second handle with no "
                            + "caller")
                    .doesNotContain("Bean");
        }

        @Test
        @DisplayName("the charset is injected by bean name, so the active code page is not guessed")
        void theCharsetIsInjectedByBeanName() {
            Constructor<?>[] constructors = AccountBalanceUpdateJob.class.getDeclaredConstructors();
            assertThat(constructors).as("one constructor, so no @Autowired is needed").hasSize(1);

            List<Parameter> charsetParameters = Stream.of(constructors[0].getParameters())
                            .filter(parameter -> parameter.getType() == Charset.class)
                            .toList();

            assertThat(charsetParameters).hasSize(1);
            assertThat(charsetParameters.get(0).getDeclaredAnnotation(Qualifier.class))
                    .as("three Charset beans exist, so by-type injection would be ambiguous")
                    .isNotNull();
            assertThat(charsetParameters.get(0).getDeclaredAnnotation(Qualifier.class).value())
                    .isEqualTo(CobolCharsetConfig.DATASET_CHARSET_BEAN_NAME);
        }

        @Test
        @DisplayName("the class carries no annotation that would disable batch auto-configuration")
        void theClassCarriesNoBatchBootstrapAnnotation() {
            List<String> annotations = Stream.of(AccountBalanceUpdateJob.class.getAnnotations())
                    .map(annotation -> annotation.annotationType().getSimpleName())
                    .toList();

            assertThat(annotations)
                    .as("under Spring Boot 3 EnableBatchProcessing DISABLES batch auto-configuration, "
                            + "which would remove the JobRepository this job is built on")
                    .noneMatch(name -> name.contains("EnableBatch"))
                    .contains("Configuration");
        }

        @Test
        @DisplayName("no static field is mutable, and none is a collaborator")
        void noStaticStateIsMutable() {
            List<String> mutableStatics = Stream.of(AccountBalanceUpdateJob.class.getDeclaredFields())
                    .filter(field -> Modifier.isStatic(field.getModifiers()))
                    .filter(field -> !field.isSynthetic())
                    .filter(field -> !Modifier.isFinal(field.getModifiers()))
                    .map(Field::getName)
                    .toList();

            assertThat(mutableStatics)
                    .as("WORKING-STORAGE becomes locals of execute(SysoutSink), never static fields")
                    .isEmpty();
        }
    }

    // =================================================================================================
    // The SYSOUT sink: verbatim, in the named code page, one line feed, flushed.
    // =================================================================================================

    @Nested
    @DisplayName("The default SYSOUT sink writes a DISPLAY line and nothing else")
    class SysoutSinkContract {

        @Test
        @DisplayName("a line is written verbatim, terminated by exactly one line feed")
        void aLineIsWrittenVerbatim() {
            ByteArrayOutputStream captured = new ByteArrayOutputStream();
            AccountBalanceUpdateJob subject = job(repositoryOver(List.of()));

            subject.sysoutSinkTo(captured).display(AccountBalanceUpdateJob.START_OF_EXECUTION);

            assertThat(new String(captured.toByteArray(), ASCII))
                    .isEqualTo(AccountBalanceUpdateJob.START_OF_EXECUTION + "\n");
        }

        @Test
        @DisplayName("nothing is prefixed: no timestamp, no level, no logger name, no thread")
        void nothingIsPrefixed() {
            ByteArrayOutputStream captured = new ByteArrayOutputStream();
            AccountBalanceUpdateJob subject = job(repositoryOver(List.of()));
            String image = expectedImage(fixtureRows().get(0));

            SysoutSink sink = subject.sysoutSinkTo(captured);
            sink.display(image);
            sink.display(image);

            assertThat(new String(captured.toByteArray(), ASCII)).isEqualTo(image + "\n" + image + "\n");
            assertThat(captured.size())
                    .isEqualTo((CardXrefRecord.RECORD_LENGTH + 1)
                            * AccountBalanceUpdateJob.DISPLAYS_PER_RECORD);
        }

        @Test
        @DisplayName("each line is flushed as it is written, so an abend cannot lose its own diagnosis")
        void eachLineIsFlushed() {
            List<String> events = new ArrayList<>();
            OutputStream recording = new OutputStream() {
                @Override
                public void write(int singleByte) {
                    events.add("write");
                }

                @Override
                public void write(byte[] bytes, int offset, int length) {
                    events.add("write");
                }

                @Override
                public void flush() {
                    events.add("flush");
                }
            };

            job(repositoryOver(List.of())).sysoutSinkTo(recording).display("A");

            assertThat(events).containsExactly("write", "flush");
        }

        @Test
        @DisplayName("a stream that refuses the write surfaces as an unchecked failure")
        void aRefusedWriteSurfaces() {
            OutputStream broken = new OutputStream() {
                @Override
                public void write(int singleByte) throws IOException {
                    throw new IOException("the SYSOUT device is unavailable");
                }
            };
            SysoutSink sink = job(repositoryOver(List.of())).sysoutSinkTo(broken);

            assertThatExceptionOfType(UncheckedIOException.class)
                    .isThrownBy(() -> sink.display("A"))
                    .withMessageContaining(AccountBalanceUpdateJob.PROGRAM_NAME);
        }

        @Test
        @DisplayName("a null destination and a null line are both refused")
        void nullsAreRefused() {
            AccountBalanceUpdateJob subject = job(repositoryOver(List.of()));
            SysoutSink sink = subject.sysoutSinkTo(new ByteArrayOutputStream());

            assertThatNullPointerException().isThrownBy(() -> subject.sysoutSinkTo(null));
            assertThatNullPointerException().isThrownBy(() -> sink.display(null));
        }

        @Test
        @DisplayName("execute refuses to run without a destination for its output")
        void executeRefusesWithoutASink() {
            AccountBalanceUpdateJob subject = job(repositoryOver(List.of()));

            assertThatNullPointerException().isThrownBy(() -> subject.execute(null));
        }
    }

    // =================================================================================================
    // The display image, and the summary.
    // =================================================================================================

    @Nested
    @DisplayName("A rendered record is the 50 bytes CVACT03Y declares")
    class DisplayImageContract {

        @Test
        @DisplayName("row 1 of the fixture renders as its 36 bytes plus 14 spaces")
        void rowOneRenders() {
            AccountBalanceUpdateJob subject = job(repositoryOver(List.of()));
            CardXrefRecord first = fixtureRecords().get(0);

            String image = subject.displayImageOf(first);

            assertThat(image).isEqualTo(expectedImage(fixtureRows().get(0)))
                    .hasSize(CardXrefRecord.RECORD_LENGTH);
            assertThat(image).startsWith(first.xrefCardNum());
        }

        @Test
        @DisplayName("a record whose fields are shorter than their spans is padded, not truncated")
        void shortFieldsArePadded() {
            AccountBalanceUpdateJob subject = job(repositoryOver(List.of()));

            String image = subject.displayImageOf(new CardXrefRecord("12345", 7, 9L));

            assertThat(image).hasSize(CardXrefRecord.RECORD_LENGTH);
            assertThat(image.substring(0, CardXrefRecord.XREF_CARD_NUM_LENGTH))
                    .isEqualTo("12345           ");
            assertThat(image.substring(CardXrefRecord.XREF_CUST_ID_OFFSET,
                    CardXrefRecord.XREF_ACCT_ID_OFFSET)).isEqualTo("000000007");
            assertThat(image.substring(CardXrefRecord.XREF_ACCT_ID_OFFSET,
                    CardXrefRecord.FILLER_OFFSET)).isEqualTo("00000000009");
            assertThat(image.substring(CardXrefRecord.FILLER_OFFSET))
                    .isEqualTo(" ".repeat(CardXrefRecord.FILLER_LENGTH));
        }

        @Test
        @DisplayName("rendering nothing is refused")
        void renderingNothingIsRefused() {
            AccountBalanceUpdateJob subject = job(repositoryOver(List.of()));

            assertThatNullPointerException().isThrownBy(() -> subject.displayImageOf(null));
        }
    }

    @Nested
    @DisplayName("The execution summary")
    class ExecutionSummaryContract {

        @Test
        @DisplayName("the derived line counts follow from the record count")
        void theDerivedCountsFollow() {
            ExecutionSummary summary = new ExecutionSummary(AbendException.RETURN_CODE_OK, 50);

            assertThat(summary.recordLinesDisplayed()).isEqualTo(100);
            assertThat(summary.totalLinesDisplayed()).isEqualTo(102);
        }

        @Test
        @DisplayName("zero records is a legitimate outcome and yields the two banners")
        void zeroRecordsIsLegitimate() {
            ExecutionSummary summary = new ExecutionSummary(AbendException.RETURN_CODE_OK, 0);

            assertThat(summary.recordLinesDisplayed()).isZero();
            assertThat(summary.totalLinesDisplayed()).isEqualTo(AccountBalanceUpdateJob.BANNER_LINES);
        }

        @Test
        @DisplayName("a negative record count is refused")
        void aNegativeRecordCountIsRefused() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new ExecutionSummary(AbendException.RETURN_CODE_OK, -1))
                    .withMessageContaining("not possible");
        }
    }

    // =================================================================================================
    // Source-level hygiene: the gates that are properties of the file rather than of a run.
    // =================================================================================================

    @Nested
    @DisplayName("The source itself honours the migration's standing prohibitions")
    class SourceHygiene {

        @Test
        @DisplayName("no wildcard import, so every copybook-to-type correspondence stays auditable")
        void noWildcardImport() throws IOException {
            assertThat(WILDCARD_IMPORT.matcher(subjectSource()).find())
                    .as("gate G52")
                    .isFalse();
        }

        @Test
        @DisplayName("no dataset name, no binary floating point, no non-truncating rounding")
        void noneOfTheStandingProhibitions() throws IOException {
            String source = subjectSource();

            List<String> forbidden = List.of("AWS.M2.CARDDEMO", "double", "float", "HALF_UP",
                    "HALF_EVEN", "CEILING", "System.out.println", "@EnableBatchProcessing");

            assertThat(forbidden.stream().filter(source::contains).toList())
                    .as("gates G22, G24 and G46: dataset names live in application.yml, and this "
                            + "program has no monetary field to round in the first place")
                    .isEmpty();
        }

        @Test
        @DisplayName("every internal import is one of the eight files this file declares as a "
                + "dependency")
        void everyInternalImportIsDeclared() throws IOException {
            List<String> allowed = List.of(
                    "com.vsergeychik.carddemo.card.CardXrefRepository",
                    "com.vsergeychik.carddemo.card.model.CardXrefRecord",
                    "com.vsergeychik.carddemo.common.AbendException",
                    "com.vsergeychik.carddemo.common.FileStatus",
                    "com.vsergeychik.carddemo.common.FixedWidthCodec",
                    "com.vsergeychik.carddemo.config.BatchConfig",
                    // The bean name of the dataset code page is taken from the class that publishes it
                    // rather than restated as a literal, so that class is now a declared dependency.
                    "com.vsergeychik.carddemo.config.CobolCharsetConfig");

            List<String> internalImports = subjectSource().lines()
                    .map(String::strip)
                    .filter(line -> line.startsWith("import com.vsergeychik.carddemo"))
                    .map(line -> line.substring("import ".length(), line.length() - 1))
                    .toList();

            assertThat(internalImports).isNotEmpty();
            assertThat(internalImports).allSatisfy(imported -> assertThat(allowed)
                    .as("%s is imported but is not one of this file's declared dependencies; a nested "
                            + "type of a declared one is fine, a new file is not", imported)
                    .anySatisfy(permitted -> assertThat(imported).startsWith(permitted)));
        }

        @Test
        @DisplayName("the two record-display sites and the two banners are all present exactly once")
        void theDisplaySitesArePresent() throws IOException {
            String source = subjectSource();

            // Both DISPLAY sites write a stored image - the bytes the row actually held - and neither
            // re-encodes the decoded record. DISPLAY CARD-XREF-RECORD (app/cbl/CBACT03C.cbl:78 and :96)
            // writes the whole 50-byte record area, and the area's FILLER X(14) carries whatever the row
            // carried; re-encoding a decoded record allocates a fresh area and so would blank it.
            assertThat(source).contains("sysout.display(storedImage);")
                    .contains("sysout.display(recordAreaImage);")
                    .contains("sysout.display(START_OF_EXECUTION);")
                    .contains("sysout.display(END_OF_EXECUTION);");

            assertThat(source)
                    .as("neither DISPLAY site may reconstruct the image from the decoded fields, which "
                            + "would space-normalise the trailing FILLER X(14)")
                    .doesNotContain("sysout.display(displayImageOf(");
        }

        @Test
        @DisplayName("the redundant END-OF-FILE guard of :75 is preserved alongside the one of :77")
        void theRedundantGuardIsPreserved() throws IOException {
            String guard = "if (NOT_AT_END_OF_FILE.equals(endOfFile))";

            long occurrences = subjectSource().lines()
                    .filter(line -> line.strip().equals(guard + " {"))
                    .count();

            // Two, not one. The guard at app/cbl/CBACT03C.cbl:75 is redundant - the PERFORM UNTIL of
            // :74 already establishes it, and END-OF-FILE holds only 'N' or 'Y' - so its false path is
            // unreachable and a coverage report shows it as a half-taken branch. It is preserved
            // deliberately: removing a statement because it cannot fail is a change to a program whose
            // observable behaviour is this migration's contract.
            assertThat(occurrences)
                    .as("the guard at :75 and the guard at :77 are both present")
                    .isEqualTo(2);
        }
    }

    // =================================================================================================
    // Wiring: the beans this class publishes come up in a real context, on the fixture profile.
    // =================================================================================================

    @Nested
    @DisplayName("The job wires in a real application context")
    class SpringWiring {

        @Test
        @DisplayName("the job and step beans are published, and the qualified charset resolves")
        void theJobAndStepBeansArePublished() {
            new ApplicationContextRunner()
                    .withInitializer(new ConfigDataApplicationContextInitializer())
                    .withInitializer(context -> RandomValuePropertySource
                            .addToEnvironment(context.getEnvironment()))
                    .withConfiguration(AutoConfigurations.of(
                            PropertyPlaceholderAutoConfiguration.class))
                    .withBean(JobRepository.class, () -> mock(JobRepository.class))
                    .withUserConfiguration(CobolCharsetConfig.class, DataSourceConfig.class,
                            BatchConfig.class, CardXrefRepository.class,
                            AccountBalanceUpdateJob.class)
                    .withPropertyValues(ACTIVE_PROFILE_PROPERTY + "test")
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        assertThat(context).hasBean(AccountBalanceUpdateJob.CONFIGURATION_BEAN_NAME);
                        assertThat(context).hasBean(AccountBalanceUpdateJob.JOB_NAME);
                        assertThat(context).hasBean(AccountBalanceUpdateJob.STEP_BEAN_NAME);
                        assertThat(context.getBean(AccountBalanceUpdateJob.JOB_NAME, Job.class)
                                .getName()).isEqualTo(AccountBalanceUpdateJob.JOB_NAME);
                        assertThat(context.getBean(AccountBalanceUpdateJob.STEP_BEAN_NAME, Step.class)
                                .getName()).isEqualTo(AccountBalanceUpdateJob.STEP_NAME);
                        AccountBalanceUpdateJob subject =
                                context.getBean(AccountBalanceUpdateJob.class);
                        assertThat(subject.stepName()).isEqualTo(AccountBalanceUpdateJob.STEP_NAME);
                        assertThat(subject.xrefFileDatasetName()).isNotBlank();
                        // No SysoutSink bean is published by this module, so the fallback is what a
                        // wired job would use.
                        assertThat(context).doesNotHaveBean(SysoutSink.class);
                        assertThat(subject.defaultSysoutSink()).isNotNull();
                    });
        }
    }

    // =================================================================================================
    // Synthesised cross-reference read outcomes. A ReadResult carries the decoded record AND the bytes it
    // was decoded from, because DISPLAY CARD-XREF-RECORD (app/cbl/CBACT03C.cbl:78 and :96) writes the
    // record area and the area's FILLER X(14) holds whatever the row held. A test constructing an outcome
    // has no row, so the image it supplies is the one a row of exactly this record would carry - stated
    // once here rather than at every call site.
    // =================================================================================================

    /**
     * The found arm over a synthesised row of this record.
     *
     * @param ddName the access path
     * @param record the record the row would carry
     * @return the outcome, carrying the record and the image a row of it would hold
     */
    private static CardXrefRepository.ReadResult xrefFound(String ddName, CardXrefRecord record) {
        return CardXrefRepository.ReadResult.found(ddName, record, xrefImageOf(record));
    }

    /**
     * The duplicate arm over a synthesised row of this record.
     *
     * @param ddName   the access path
     * @param first    the first of the matching records
     * @param cicsResp DUPREC for the base key or DUPKEY for an alternate key
     * @return the outcome, carrying the record and the image a row of it would hold
     */
    private static CardXrefRepository.ReadResult xrefDuplicate(String ddName, CardXrefRecord first,
            int cicsResp) {
        return CardXrefRepository.ReadResult.duplicate(ddName, first, xrefImageOf(first), cicsResp);
    }

    /**
     * The 50-character image a row of this record would hold.
     *
     * @param record the record
     * @return its encoded image
     */
    private static String xrefImageOf(CardXrefRecord record) {
        return new String(record.encode(StandardCharsets.US_ASCII), StandardCharsets.US_ASCII);
    }
}
