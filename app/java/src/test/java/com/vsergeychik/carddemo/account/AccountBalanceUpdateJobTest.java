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
import org.springframework.batch.core.StepContribution;
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
 */
@DisplayName("AccountBalanceUpdateJob - CBACT03C: reads the cross reference, updates nothing, "
        + "displays every record twice")
class AccountBalanceUpdateJobTest {
    private static final Charset ASCII = StandardCharsets.US_ASCII;

    private static final FixedWidthCodec CODEC = new FixedWidthCodec(ASCII);

    private static final String FIXTURE = "/fixtures/cardxref.txt";

    private static final int FIXTURE_ROW_COUNT = 50;

    private static final int FIXTURE_ROW_WIDTH = 36;

    private static final String TEST_DSNAME = "CARDDEMO.TEST.CARDXREF.VSAM.KSDS";

    private static final String SUBJECT_SOURCE_PATH = "app/java/src/main/java/com/vsergeychik/"
            + "carddemo/account/AccountBalanceUpdateJob.java";

    private static final Pattern WILDCARD_IMPORT =
            Pattern.compile("import\\s+(?:static\\s+)?[\\w.]+\\.\\*\\s*;");

    private static final String ACTIVE_PROFILE_PROPERTY = "spring.profiles.active=";

    private static final class CapturingSysout implements SysoutSink {
        private final List<String> lines = new ArrayList<>();

        @Override
        public void display(String line) {
            lines.add(line);
        }

        private List<String> lines() {
            return Collections.unmodifiableList(new ArrayList<>(lines));
        }
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

    private static DatasetBindings bindings(int recordLength, String dsname) {
        return bindings(recordLength, dsname, dsname);
    }

    private static DatasetBindings bindings(int recordLength, String dsname, String baseDsname) {
        DatasetBindings catalogue = new DatasetBindings();
        catalogue.put(AccountBalanceUpdateJob.XREFFILE_DD_NAME, new DatasetBinding(dsname,
                DatasetBinding.KSDS, false, "FB", null, recordLength, "CVACT03Y",
                CardXrefRecord.XREF_CARD_NUM_LENGTH, null, null, null));
        catalogue.put(CardXrefRepository.BASE_DD_NAME, new DatasetBinding(baseDsname,
                DatasetBinding.KSDS, false, "FB", null, recordLength, "CVACT03Y",
                CardXrefRecord.XREF_CARD_NUM_LENGTH, null, null, null));
        return catalogue;
    }

    private static JobContracts contracts(String program, String stepName, boolean gated,
            List<JobParameterContract> parameters) {
        JobContracts catalogue = new JobContracts();
        catalogue.put(AccountBalanceUpdateJob.JOB_KEY, new JobContract(program, parameters,
                List.of(new StepContract(stepName, program, gated)), null, Map.of()));
        return catalogue;
    }

    private static JobContracts validContracts() {
        return contracts(AccountBalanceUpdateJob.PROGRAM_NAME, AccountBalanceUpdateJob.STEP_NAME,
                false, List.of());
    }

    private static BatchConfig batchConfig(JobContracts contracts, DatasetBindings bindings) {
        return new BatchConfig(new SuppliedProvider<>(mock(JobRepository.class)),
                new SuppliedProvider<>(mock(PlatformTransactionManager.class)), contracts, bindings);
    }

    private static BatchConfig validBatchConfig() {
        return batchConfig(validContracts(), bindings(CardXrefRecord.RECORD_LENGTH, TEST_DSNAME));
    }

    private static AccountBalanceUpdateJob job(CardXrefRepository repository) {
        return new AccountBalanceUpdateJob(validBatchConfig(), repository, ASCII,
                new SuppliedProvider<>(null));
    }

    private static StepExecution stepExecution() {
        return new StepExecution(AccountBalanceUpdateJob.STEP_NAME, new JobExecution(1L));
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

    private static BrowseCursor cursorOver(List<CardXrefRecord> records) {
        List<ReadResult> reads = new ArrayList<>();
        records.forEach(record -> reads.add(xrefFound(CardXrefRepository.BASE_DD_NAME, record)));
        reads.add(ReadResult.endOfFile(CardXrefRepository.BASE_DD_NAME));
        return cursorYielding(FileStatus.OK, FileStatus.OK, reads);
    }

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

    private static CardXrefRepository repositoryOver(List<CardXrefRecord> records) {
        return repositoryWith(cursorOver(records));
    }

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

    private static List<CardXrefRecord> fixtureRecords() {
        List<CardXrefRecord> records = new ArrayList<>();
        for (String row : fixtureRows()) {
            String widened = CODEC.padToDeclaredWidth(row, CardXrefRecord.RECORD_LENGTH);
            records.add(CardXrefRecord.decode(CODEC.encodeImage(widened, "a fixture row"), CODEC));
        }
        return records;
    }

    private static String expectedImage(String row) {
        return row + " ".repeat(CardXrefRecord.FILLER_LENGTH);
    }

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

        private boolean namesTheVerb(String methodName, String verb) {
            String name = methodName.toLowerCase(Locale.ROOT);
            if (!name.startsWith(verb)) {
                return false;
            }
            return methodName.length() == verb.length()
                    || Character.isUpperCase(methodName.charAt(verb.length()));
        }

        @Test
        @DisplayName("the browse cursor publishes no write-side method and no update-mode open")
        void theBrowseCursorPublishesNoWriteMethod() {
            List<String> writeVerbs = List.of("write", "rewrite", "add", "insert", "update", "delete",
                    "put", "save", "store", "merge", "persist");
            List<String> updateModeOpens = List.of("forupdate", "openoutput", "openio", "openupdate");

            List<String> offenders = new ArrayList<>();
            for (Method method : BrowseCursor.class.getDeclaredMethods()) {
                if (!Modifier.isPublic(method.getModifiers()) || method.isSynthetic()) {
                    continue;
                }
                String lowered = method.getName().toLowerCase(Locale.ROOT);
                if (writeVerbs.stream().anyMatch(verb -> namesTheVerb(method.getName(), verb))
                        || updateModeOpens.stream().anyMatch(lowered::contains)) {
                    offenders.add(method.getName());
                }
            }

            assertThat(offenders)
                    .as("app/cbl/CBACT03C.cbl:120 is OPEN INPUT, and the paragraph pair at :92 and "
                            + ":136 only READs and CLOSEs, so no update-mode handle exists to reach for")
                    .isEmpty();
        }

        @Test
        @DisplayName("no method of the job is named for writing, and none for the account master")
        void noMethodPromisesWhatTheProgramDoesNot() {
            List<String> methodNames = Stream.of(AccountBalanceUpdateJob.class.getDeclaredMethods())
                    .filter(method -> !method.isSynthetic())
                    .map(Method::getName)
                    .map(name -> name.toLowerCase(Locale.ROOT))
                    .toList();

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
            assertThat(AccountBalanceUpdateJob.DATASET_CHARSET_BEAN_NAME)
                    .isEqualTo(CobolCharsetConfig.DATASET_CHARSET_BEAN_NAME);
        }
    }

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

        @Test
        @DisplayName("the record-line count is 2 per record - neither CBACT02C's 1 nor CBACT01C's 13")
        void theShapeIsNeitherSiblingsShape() {
            CapturingSysout sysout = new CapturingSysout();

            ExecutionSummary summary = job(repositoryOver(fixtureRecords())).execute(sysout);

            int recordLines = sysout.lines().size() - AccountBalanceUpdateJob.BANNER_LINES;
            assertThat(recordLines)
                    .as("app/cbl/CBACT03C.cbl displays the record area at :96 and again at :78, and "
                            + "both statements are live - column 7 of each is a space, not an asterisk")
                    .isEqualTo(FIXTURE_ROW_COUNT * AccountBalanceUpdateJob.DISPLAYS_PER_RECORD)
                    .isEqualTo(100)
                    .as("one line per record is CBACT02C's shape, not this program's")
                    .isNotEqualTo(FIXTURE_ROW_COUNT)
                    .as("thirteen lines per record is CBACT01C's shape, not this program's")
                    .isNotEqualTo(FIXTURE_ROW_COUNT * 13);
            assertThat(summary.recordLinesDisplayed()).isEqualTo(recordLines);
        }

        @Test
        @DisplayName("no line is labelled or ruled - CBACT03C has no display paragraph at all")
        void noLineCarriesALabelledFieldPrefix() {
            CapturingSysout sysout = new CapturingSysout();

            job(repositoryOver(fixtureRecords())).execute(sysout);

            assertThat(sysout.lines()).allSatisfy(line -> assertThat(line)
                    .as("a labelled field prefix would mean a display paragraph this program lacks")
                    .doesNotContain(":")
                    .doesNotContain("-".repeat(49))
                    .doesNotContain(CardXrefRecord.XREF_CARD_NUM_NAME)
                    .doesNotContain(CardXrefRecord.XREF_CUST_ID_NAME)
                    .doesNotContain(CardXrefRecord.XREF_ACCT_ID_NAME));
        }
    }

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
            verify(cursor, times(FIXTURE_ROW_COUNT + 1)).readNext();
        }
    }

    @Nested
    @DisplayName("The three abend paths, each naming its own paragraph's failure")
    class AbendPaths {
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

    @Nested
    @DisplayName("Each file status at each call site, and each 88-level in both truth states")
    class StatusLaddersPerCallSite {
        private static final String PERMANENT = CardXrefRepository.PERMANENT_ERROR_STATUS;

        @Test
        @DisplayName("the four statuses these ladders are driven with are the shared vocabulary's own")
        void theDrivenStatusesAreTheSharedOnes() {
            assertThat(List.of(FileStatus.END_OF_FILE, FileStatus.DUPLICATE, FileStatus.NOT_FOUND,
                    FileStatus.RECORD_LENGTH_CONFLICT))
                    .containsExactly("10", "22", "23", "04");
            assertThat(FileStatus.OK).isEqualTo("00");
        }

        @ParameterizedTest(name = "OPEN reporting ''{0}''")
        @ValueSource(strings = { FileStatus.END_OF_FILE, FileStatus.DUPLICATE, FileStatus.NOT_FOUND,
                FileStatus.RECORD_LENGTH_CONFLICT })
        @DisplayName("an open reporting a status other than '00' abends with RETURN-CODE 12")
        void anOpenStatusOtherThanOkAbends(String status) {
            BrowseCursor cursor = cursorYielding(status, FileStatus.OK,
                    List.of(ReadResult.endOfFile(CardXrefRepository.BASE_DD_NAME)));
            CapturingSysout sysout = new CapturingSysout();
            AccountBalanceUpdateJob subject = job(repositoryWith(cursor));

            AbendException abend = assertThatExceptionOfType(AbendException.class)
                    .as("app/cbl/CBACT03C.cbl:121 tests only '00', so '%s' takes the :124 arm", status)
                    .isThrownBy(() -> subject.execute(sysout))
                    .actual();

            assertThat(sysout.lines()).containsExactly(AccountBalanceUpdateJob.START_OF_EXECUTION,
                    AccountBalanceUpdateJob.ERROR_OPENING_XREFFILE,
                    FileStatus.toDisplayLine(status), AbendException.ABEND_DISPLAY_TEXT);
            assertThat(abend.getReturnCode()).isEqualTo(AccountBalanceUpdateJob.APPL_RESULT_FATAL);
            assertThat(sysout.lines())
                    .doesNotContain(AccountBalanceUpdateJob.END_OF_EXECUTION);
            verify(cursor, never()).readNext();
        }

        @ParameterizedTest(name = "CLOSE reporting ''{0}''")
        @ValueSource(strings = { FileStatus.END_OF_FILE, FileStatus.DUPLICATE, FileStatus.NOT_FOUND,
                FileStatus.RECORD_LENGTH_CONFLICT })
        @DisplayName("a close reporting a status other than '00' abends with RETURN-CODE 12")
        void aCloseStatusOtherThanOkAbends(String status) {
            BrowseCursor cursor = cursorYielding(FileStatus.OK, status,
                    List.of(ReadResult.endOfFile(CardXrefRepository.BASE_DD_NAME)));
            CapturingSysout sysout = new CapturingSysout();
            AccountBalanceUpdateJob subject = job(repositoryWith(cursor));

            AbendException abend = assertThatExceptionOfType(AbendException.class)
                    .as("app/cbl/CBACT03C.cbl:139 tests only '00', so '%s' takes the :142 arm", status)
                    .isThrownBy(() -> subject.execute(sysout))
                    .actual();

            assertThat(sysout.lines()).containsExactly(AccountBalanceUpdateJob.START_OF_EXECUTION,
                    AccountBalanceUpdateJob.ERROR_CLOSING_XREFFILE,
                    FileStatus.toDisplayLine(status), AbendException.ABEND_DISPLAY_TEXT);
            assertThat(abend.getReturnCode()).isEqualTo(AccountBalanceUpdateJob.APPL_RESULT_FATAL);
            verify(cursor, times(1)).closeBrowse();
        }

        @Test
        @DisplayName("'10' is end of file to the read and a hard failure to the open and the close")
        void tenMeansEndOfFileOnlyToTheReadParagraph() {
            CapturingSysout readSite = new CapturingSysout();
            ExecutionSummary summary = job(repositoryOver(List.of())).execute(readSite);
            assertThat(readSite.lines()).containsExactly(AccountBalanceUpdateJob.START_OF_EXECUTION,
                    AccountBalanceUpdateJob.END_OF_EXECUTION);
            assertThat(summary.returnCode()).isEqualTo(AbendException.RETURN_CODE_OK);
            assertThat(summary.recordsRead()).isZero();

            CapturingSysout openSite = new CapturingSysout();
            AccountBalanceUpdateJob openFailure = job(repositoryWith(cursorYielding(
                    FileStatus.END_OF_FILE, FileStatus.OK,
                    List.of(ReadResult.endOfFile(CardXrefRepository.BASE_DD_NAME)))));
            assertThat(assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> openFailure.execute(openSite))
                    .actual().getReturnCode())
                    .isEqualTo(AccountBalanceUpdateJob.APPL_RESULT_FATAL)
                    .isNotEqualTo(AbendException.RETURN_CODE_END_OF_FILE);
            assertThat(openSite.lines()).contains(AccountBalanceUpdateJob.ERROR_OPENING_XREFFILE);

            CapturingSysout closeSite = new CapturingSysout();
            AccountBalanceUpdateJob closeFailure = job(repositoryWith(cursorYielding(
                    FileStatus.OK, FileStatus.END_OF_FILE,
                    List.of(ReadResult.endOfFile(CardXrefRepository.BASE_DD_NAME)))));
            assertThat(assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> closeFailure.execute(closeSite))
                    .actual().getReturnCode())
                    .isEqualTo(AccountBalanceUpdateJob.APPL_RESULT_FATAL)
                    .isNotEqualTo(AbendException.RETURN_CODE_END_OF_FILE);
            assertThat(closeSite.lines()).contains(AccountBalanceUpdateJob.ERROR_CLOSING_XREFFILE);
        }

        @Test
        @DisplayName("'00' at all three call sites is the whole clean pass - both 88-levels' other state")
        void okAtEveryCallSiteIsACleanPass() {
            CapturingSysout sysout = new CapturingSysout();
            BrowseCursor cursor = cursorYielding(FileStatus.OK, FileStatus.OK, List.of(
                    xrefFound(CardXrefRepository.BASE_DD_NAME, fixtureRecords().get(0)),
                    ReadResult.endOfFile(CardXrefRepository.BASE_DD_NAME)));

            ExecutionSummary summary = job(repositoryWith(cursor)).execute(sysout);

            String row = fixtureRows().get(0);
            assertThat(sysout.lines()).containsExactly(AccountBalanceUpdateJob.START_OF_EXECUTION,
                    expectedImage(row), expectedImage(row), AccountBalanceUpdateJob.END_OF_EXECUTION);
            assertThat(summary.returnCode()).isEqualTo(AbendException.RETURN_CODE_OK);
            assertThat(sysout.lines())
                    .doesNotContain(AccountBalanceUpdateJob.ERROR_OPENING_XREFFILE)
                    .doesNotContain(AccountBalanceUpdateJob.ERROR_READING_XREFFILE)
                    .doesNotContain(AccountBalanceUpdateJob.ERROR_CLOSING_XREFFILE)
                    .doesNotContain(AbendException.ABEND_DISPLAY_TEXT);
        }

        @Test
        @DisplayName("the close ladder seeds 8, then reaches 0 on '00' and 12 on anything else")
        void theCloseLadderRunsFromEightToZeroOrTwelve() {
            assertThat(AccountBalanceUpdateJob.APPL_RESULT_ASSUMED_FAILURE)
                    .as("the value :137 seeds and :119 moves")
                    .isEqualTo(8)
                    .isNotEqualTo(FileStatus.APPL_AOK);

            CapturingSysout clean = new CapturingSysout();
            ExecutionSummary summary = job(repositoryOver(List.of())).execute(clean);
            assertThat(clean.lines()).endsWith(AccountBalanceUpdateJob.END_OF_EXECUTION);
            assertThat(clean.lines()).doesNotContain(AccountBalanceUpdateJob.ERROR_CLOSING_XREFFILE);
            assertThat(summary.returnCode()).isEqualTo(FileStatus.APPL_AOK);

            CapturingSysout failing = new CapturingSysout();
            AccountBalanceUpdateJob subject = job(repositoryWith(cursorYielding(FileStatus.OK, PERMANENT,
                    List.of(ReadResult.endOfFile(CardXrefRepository.BASE_DD_NAME)))));
            assertThat(assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> subject.execute(failing))
                    .actual().getReturnCode())
                    .isEqualTo(12)
                    .isEqualTo(AccountBalanceUpdateJob.APPL_RESULT_FATAL);
        }

        @Test
        @DisplayName("no abend carries the dead 8 seed, at any of the three call sites")
        void noAbendCarriesTheDeadSeed() {
            List<CapturingSysout> sinks = List.of(new CapturingSysout(), new CapturingSysout(),
                    new CapturingSysout());
            List<AccountBalanceUpdateJob> subjects = List.of(
                    job(repositoryWith(cursorYielding(PERMANENT, FileStatus.OK,
                            List.of(ReadResult.endOfFile(CardXrefRepository.BASE_DD_NAME))))),
                    job(repositoryWith(cursorYielding(FileStatus.OK, FileStatus.OK,
                            List.of(ReadResult.other(CardXrefRepository.BASE_DD_NAME, PERMANENT))))),
                    job(repositoryWith(cursorYielding(FileStatus.OK, PERMANENT,
                            List.of(ReadResult.endOfFile(CardXrefRepository.BASE_DD_NAME))))));

            for (int site = 0; site < subjects.size(); site++) {
                AccountBalanceUpdateJob subject = subjects.get(site);
                CapturingSysout sysout = sinks.get(site);

                AbendException abend = assertThatExceptionOfType(AbendException.class)
                        .as("call site %d", site)
                        .isThrownBy(() -> subject.execute(sysout))
                        .actual();

                assertThat(abend.getReturnCode())
                        .as("call site %d carries the 12 of :101, :124 or :142", site)
                        .isEqualTo(AccountBalanceUpdateJob.APPL_RESULT_FATAL)
                        .isNotEqualTo(AccountBalanceUpdateJob.APPL_RESULT_ASSUMED_FAILURE)
                        .isNotEqualTo(FileStatus.APPL_AOK)
                        .isNotEqualTo(FileStatus.APPL_EOF);
                assertThat(abend.getAbendCode()).hasValue(AbendException.STANDARD_ABEND_CODE);
                assertThat(abend.getTiming()).hasValue(AbendException.STANDARD_TIMING);
                assertThat(sysout.lines()).last().isEqualTo(AbendException.ABEND_DISPLAY_TEXT);
            }
        }

        @ParameterizedTest(name = "''{0}'' renders as the numeric arm of 9910-DISPLAY-IO-STATUS")
        @ValueSource(strings = { FileStatus.END_OF_FILE, FileStatus.DUPLICATE, FileStatus.NOT_FOUND,
                FileStatus.RECORD_LENGTH_CONFLICT })
        @DisplayName("a numeric status renders through the ELSE arm as '00' followed by the status")
        void aNumericStatusTakesTheElseArmOfTheRenderer(String status) {
            String rendered = FileStatus.toDisplayLine(status);
            assertThat(rendered)
                    .isEqualTo(FileStatus.DISPLAY_PREFIX + "00" + status)
                    .hasSize(FileStatus.DISPLAY_PREFIX.length() + FileStatus.STATUS_IMAGE_LENGTH);

            CapturingSysout sysout = new CapturingSysout();
            AccountBalanceUpdateJob subject = job(repositoryWith(cursorYielding(status, FileStatus.OK,
                    List.of(ReadResult.endOfFile(CardXrefRepository.BASE_DD_NAME)))));

            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> subject.execute(sysout));

            assertThat(sysout.lines()).contains(rendered);
        }
    }

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

            StepExecution stepExecution = stepExecution();
            StepContribution contribution = new StepContribution(stepExecution);

            RepeatStatus status = subject.accountBalanceUpdateTasklet()
                    .execute(contribution, chunkContext(stepExecution));

            assertThat(status).isEqualTo(RepeatStatus.FINISHED);
            assertThat(sysout.lines()).hasSize(102);
            assertThat(contribution.getReadCount())
                    .as("BATCH_STEP_EXECUTION.READ_COUNT must show the volume this pass read, so a run "
                            + "over the whole file is distinguishable from a run over an empty one")
                    .isEqualTo(fixtureRecords().size());
        }

        @Test
        @DisplayName("a published SysoutSink bean is preferred over the default")
        void aPublishedSinkIsPreferred() throws Exception {
            CapturingSysout published = new CapturingSysout();
            AccountBalanceUpdateJob subject = new AccountBalanceUpdateJob(validBatchConfig(),
                    repositoryOver(List.of()), ASCII, new SuppliedProvider<>(published));

            StepExecution stepExecution = stepExecution();
            StepContribution contribution = new StepContribution(stepExecution);

            subject.accountBalanceUpdateTasklet().execute(contribution, chunkContext(stepExecution));

            assertThat(published.lines()).containsExactly(
                    AccountBalanceUpdateJob.START_OF_EXECUTION,
                    AccountBalanceUpdateJob.END_OF_EXECUTION);
            assertThat(contribution.getReadCount())
                    .as("an empty dataset read nothing, and reports nothing")
                    .isZero();
        }

        @Test
        @DisplayName("with no published sink the default one is used, and the program still runs")
        void withNoPublishedSinkTheDefaultIsUsed() throws Exception {
            AccountBalanceUpdateJob subject = job(repositoryOver(List.of()));

            StepExecution stepExecution = stepExecution();

            RepeatStatus status = subject.accountBalanceUpdateTasklet()
                    .execute(new StepContribution(stepExecution), chunkContext(stepExecution));

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
                    .withCauseInstanceOf(JobInterruptedException.class);

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

            assertThat(sysout.lines()).hasSize(stopAfter * 2 + 1);
            assertThat(sysout.lines().size() - 1).isEven();

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

            assertThatExceptionOfType(StopRequestedException.class).isThrownBy(() ->
                    subject.accountBalanceUpdateTasklet()
                            .execute(new StepContribution(stepExecution), chunkContext(stepExecution)));

            assertThat(sysout.lines())
                    .containsExactly(AccountBalanceUpdateJob.START_OF_EXECUTION);
        }
    }

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

            assertThat(occurrences)
                    .as("the guard at :75 and the guard at :77 are both present")
                    .isEqualTo(2);
        }
    }

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
                        assertThat(subject.xrefFileDatasetName())
                                .as("the dataset name is resolved from carddemo.datasets.XREFFILE."
                                        + "dsname, never composed in Java")
                                .isEqualTo(context.getEnvironment()
                                        .getProperty("carddemo.datasets."
                                                + AccountBalanceUpdateJob.XREFFILE_DD_NAME
                                                + ".dsname"));
                        assertThat(context).doesNotHaveBean(SysoutSink.class);
                        assertThat(subject.defaultSysoutSink()).isNotNull();
                    });
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
