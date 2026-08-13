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
 */
@DisplayName("AccountBalanceReaderJob - CBACT02C, which reads and prints the CARD file")
class AccountBalanceReaderJobTest {
    private static final String FIXTURE = "/fixtures/carddata.txt";

    private static final Charset FIXTURE_CHARSET = StandardCharsets.US_ASCII;

    private static final int FIXTURE_RECORD_COUNT = 50;

    private static final int EXPECTED_LINE_COUNT = FIXTURE_RECORD_COUNT + 2;

    private static final int SIBLING_THIRTEEN_LINE_COUNT = FIXTURE_RECORD_COUNT * 13 + 2;

    private static final String PERMANENT_ERROR_LINE =
            FileStatus.toDisplayLine(AccountBalanceReaderJob.PERMANENT_ERROR_STATUS);

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

    private static List<CardRecord> fixtureRecords() {
        return fixtureRows().stream()
                .map(row -> CardRecord.decodeImage(row, FIXTURE_CHARSET))
                .toList();
    }

    private static final class CollectingSink implements SysoutSink {
        private final List<String> lines = new ArrayList<>();

        @Override
        public void write(String line) {
            lines.add(line);
        }

        private List<String> lines() {
            return lines;
        }
    }

    private static final class SingleValueProvider<T> implements ObjectProvider<T> {
        private final T value;

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

    private static DatasetBinding cardfileBinding(String dsname) {
        return new DatasetBinding(dsname, "ksds", false, "FB", null, CardRecord.RECORD_LENGTH,
                "CVACT02Y", CardRecord.CARD_NUM_LENGTH, null, null, null);
    }

    private static DatasetBindings datasetBindings(String dsname) {
        return datasetBindings(dsname, dsname);
    }

    private static DatasetBindings datasetBindings(String dsname, String baseDsname) {
        DatasetBindings bindings = new DatasetBindings();
        bindings.put(AccountBalanceReaderJob.DD_NAME, cardfileBinding(dsname));
        bindings.put(CardRepository.BASE_DD_NAME, cardfileBinding(baseDsname));
        return bindings;
    }

    private static JobContract sourceDerivedContract() {
        return new JobContract(AccountBalanceReaderJob.PROGRAM_ID, List.of(),
                List.of(new StepContract(AccountBalanceReaderJob.STEP_NAME,
                        AccountBalanceReaderJob.PROGRAM_ID, false)),
                null, Map.of());
    }

    private static BatchConfig batchConfig(JobContract contract, String dsname) {
        return batchConfig(contract, dsname, dsname);
    }

    private static BatchConfig batchConfig(JobContract contract, String dsname, String baseDsname) {
        BatchConfig.JobContracts contracts = new BatchConfig.JobContracts();
        contracts.put(AccountBalanceReaderJob.JOB_KEY, contract);
        return new BatchConfig(
                new SingleValueProvider<>(mock(JobRepository.class)),
                new SingleValueProvider<>(mock(PlatformTransactionManager.class)),
                contracts,
                datasetBindings(dsname, baseDsname));
    }

    private static AccountBalanceReaderJob jobOver(CardRepository cardRepository) {
        return new AccountBalanceReaderJob(
                batchConfig(sourceDerivedContract(), "CARDDEMO.TEST.CARDDATA.VSAM.KSDS"),
                cardRepository,
                FIXTURE_CHARSET,
                new SingleValueProvider<>(null));
    }

    private static CardRepository cardRepositoryMock() {
        CardRepository repository = mock(CardRepository.class);
        when(repository.addressing(any(), any())).thenReturn(repository);
        return repository;
    }

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

    private static StepExecution stepExecution() {
        return new StepExecution(AccountBalanceReaderJob.STEP_NAME, new JobExecution(1L));
    }

    private static ChunkContext chunkContext(StepExecution stepExecution) {
        return new ChunkContext(new StepContext(stepExecution));
    }

    private static CardRepository repositoryOverWholeFixture() {
        return repositoryReturning(wholeFixtureReadResults());
    }

    private static List<CardReadResult> wholeFixtureReadResults() {
        List<CardReadResult> results = new ArrayList<>(
                fixtureRecords().stream().map(AccountBalanceReaderJobTest::cardRead).toList());
        results.add(CardReadResult.endOfFile());
        return results;
    }

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
                    .withCauseInstanceOf(JobInterruptedException.class);

            assertThat(sink.lines()).containsExactly(AccountBalanceReaderJob.START_BANNER);
        }

        @Test
        @DisplayName("a stop requested part way through ends the pass with the record in flight complete")
        void aStopPartWayThroughLeavesTheRecordInFlightComplete() {
            StepExecution stepExecution = stepExecution();
            CollectingSink sink = new CollectingSink();
            AccountBalanceReaderJob job = jobOver(repositoryOverWholeFixture());
            int stopAfter = 3;

            StopSignal afterThreeRecords = countingSignal(stepExecution, stopAfter);

            assertThatExceptionOfType(StopRequestedException.class)
                    .isThrownBy(() -> job.execute(sink, afterThreeRecords));

            assertThat(sink.lines()).hasSize(stopAfter + 1);
            assertThat(sink.lines().subList(1, sink.lines().size()))
                    .allSatisfy(line -> assertThat(line).hasSize(CardRecord.RECORD_LENGTH));

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
            assertThat(PERMANENT_ERROR_LINE).isEqualTo(FileStatus.DISPLAY_PREFIX + "9000");
        }

        @Test
        @DisplayName("the charset bean name is the one the card repository resolves, so both read one bean")
        void charsetBeanNameAgreesWithTheRepository() {
            assertThat(CardRepository.DATASET_CHARSET_BEAN_NAME)
                    .isEqualTo(CobolCharsetConfig.DATASET_CHARSET_BEAN_NAME);
        }
    }

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

    @Nested
    @DisplayName("0000-CARDFILE-OPEN")
    class Open {
        @Test
        @DisplayName("a dataset the backend will not open abends at the OPEN, not at the first read")
        void aBackendRefusalAtOpenAbendsAtTheOpen() {
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

    @Nested
    @DisplayName("the browse is released however the pass ends")
    class BrowseRelease {
        @Test
        @DisplayName("an unmodelled runtime failure in the read still releases the browse")
        void anUnmodelledReadFailureStillReleasesTheBrowse() {
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

    @Nested
    @DisplayName("the DD this job declares is the DD it reads - app/jcl/READCARD.jcl:25-26")
    class TheDeclaredDdDrivesTheRead {
        @Test
        @DisplayName("the read is taken through the resolved CARDFILE binding, not through CARDDAT")
        void theReadGoesThroughTheDeclaredDd() {
            CardRepository repository = repositoryReturning(List.of(CardReadResult.endOfFile()));

            linesFrom(repository);

            ArgumentCaptor<DatasetBinding> used = ArgumentCaptor.forClass(DatasetBinding.class);
            verify(repository).addressing(used.capture(), eq(AccountBalanceReaderJob.DD_NAME));
            assertThat(used.getValue().dsname()).isEqualTo("CARDDEMO.TEST.CARDDATA.VSAM.KSDS");
            assertThat(AccountBalanceReaderJob.DD_NAME).isEqualTo("CARDFILE");
        }

        @Test
        @DisplayName("the re-binding is asked for once per run, not once per record")
        void theRebindingHappensOncePerRun() {
            CardRepository repository = repositoryOverWholeFixture();

            linesFrom(repository);

            verify(repository, times(1)).addressing(any(), eq(AccountBalanceReaderJob.DD_NAME));
        }

        @Test
        @DisplayName("a repository addressing the declared DD is what the browse is taken on")
        void theBrowseIsTakenOnTheRebindingResult() {
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

        @FunctionalInterface
        private interface Tasklets {
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

    @org.springframework.context.annotation.Configuration
    static class SupportingBeans {
        @org.springframework.context.annotation.Bean
        BatchConfig batchConfig() {
            return AccountBalanceReaderJobTest.batchConfig(sourceDerivedContract(),
                    "CARDDEMO.TEST.CARDDATA.VSAM.KSDS");
        }

        @org.springframework.context.annotation.Bean
        CardRepository cardRepository() {
            return cardRepositoryMock();
        }

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
                assertThat(context.getBeansOfType(
                        org.springframework.boot.ApplicationRunner.class)).isEmpty();
                assertThat(context.getBeansOfType(
                        org.springframework.boot.CommandLineRunner.class)).isEmpty();
                assertThat(context.getBeanNamesForType(
                        org.springframework.batch.core.launch.JobLauncher.class)).isEmpty();
            }
        }
    }

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
        @DisplayName("the default sink encodes in the injected dataset code page, not the platform's")
        void theDefaultSinkEncodesInTheDatasetCodePage() {
            AccountBalanceReaderJob job = jobOver(cardRepositoryMock());

            PrintStream stream = ((PrintStreamSysoutSink) job.sysoutSink()).stream();
            assertThat(stream.charset()).isEqualTo(FIXTURE_CHARSET);
            assertThat(stream).isNotSameAs(System.out);
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
        @DisplayName("the standard-output factory takes the code page as a parameter and demands one")
        void theStandardOutputFactoryTakesItsCharsetAsAParameter() {
            Charset ebcdic = Charset.forName("IBM037");
            assertThat(((PrintStreamSysoutSink) AccountBalanceReaderJob.standardOutput(ebcdic)).stream()
                    .charset()).isEqualTo(ebcdic);
            assertThat(((PrintStreamSysoutSink) AccountBalanceReaderJob.standardOutput(FIXTURE_CHARSET))
                    .stream().charset()).isEqualTo(FIXTURE_CHARSET);
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> AccountBalanceReaderJob.standardOutput(null));
        }
    }

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

    private static CardReadResult cardRead(CardRecord record) {
        return CardReadResult.normal(record, record.encodeToImage(StandardCharsets.US_ASCII));
    }

    private static CardReadResult cardReadDuplicate(CardRecord record) {
        return CardReadResult.duplicateKey(record, record.encodeToImage(StandardCharsets.US_ASCII));
    }

    @Nested
    @DisplayName("Rule R1 - the name says Account, the program reads the CARD file")
    class NameDivergenceIsStructural {
        private static final int ACCOUNT_RECORD_LENGTH = 300;

        private static final String ACCOUNT_COPYBOOK = "CVACT01Y";

        private static final String ACCOUNT_DD_NAME = "ACCTFILE";

        @Test
        @DisplayName("the injected repository is card.CardRepository - there is no account collaborator")
        void theCollaboratorIsTheCardRepository() {
            Constructor<?>[] constructors = AccountBalanceReaderJob.class.getDeclaredConstructors();
            assertThat(constructors)
                    .as("one constructor, so there is exactly one way to wire this job")
                    .hasSize(1);
            List<Class<?>> parameters = List.of(constructors[0].getParameterTypes());

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
            CardRepository repository = repositoryOverWholeFixture();

            List<String> lines = linesFrom(repository);

            assertThat(lines).hasSize(EXPECTED_LINE_COUNT);
            verify(repository, never()).readByAccountIdViaAltIndex(any(String.class));
            verify(repository, never()).readByAccountIdViaAltIndex(eq(0L));
            verifyNoMoreInteractions(ignoreStubs(repository));
        }

        @Test
        @DisplayName("the program id is CBACT02C, whose Function header reads 'card data file'")
        void theProgramIdIsTheCardReader() {
            assertThat(AccountBalanceReaderJob.PROGRAM_ID).isEqualTo("CBACT02C");
            assertThat(AccountBalanceReaderJob.OPEN_ERROR_TEXT).endsWith("CARDFILE");
            assertThat(AccountBalanceReaderJob.READ_ERROR_TEXT).endsWith("CARDFILE");
            assertThat(AccountBalanceReaderJob.CLOSE_ERROR_TEXT).endsWith("CARDFILE");
            assertThat(AccountBalanceReaderJob.START_BANNER).endsWith("CBACT02C");
            assertThat(AccountBalanceReaderJob.END_BANNER).endsWith("CBACT02C");
        }
    }

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

    @Nested
    @DisplayName("9910-DISPLAY-IO-STATUS - both arms, and the literal byte for byte")
    class IoStatusRenderer {
        @Test
        @DisplayName("the literal is 'FILE STATUS IS: NNNN' - the NNNN really is part of it")
        void theLiteralIsByteExact() {
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
            assertThat(FileStatus.toDisplayLine(status))
                    .isEqualTo("FILE STATUS IS: NNNN" + image);
            assertThat(FileStatus.toStatusImage(status)).isEqualTo(image).startsWith("00");
        }

        @Test
        @DisplayName("a status beginning '9' takes the IF arm: the byte becomes three decimal digits")
        void extendedStatusTakesTheIfArm() {
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
            assertThat(FileStatus.toDisplayLine("AB")).isEqualTo("FILE STATUS IS: NNNNA066");
        }

        @Test
        @DisplayName("a read failure emits the error text, then the status line, then the abend text")
        void theEmissionOrderIsTheParagraphOrder() {
            CollectingSink sink = new CollectingSink();

            assertThatAbend(repositoryReturning(List.of(CardReadResult.notFound())), sink);

            assertThat(sink.lines()).containsExactly(
                    "START OF EXECUTION OF PROGRAM CBACT02C",
                    "ERROR READING CARDFILE",
                    "FILE STATUS IS: NNNN0023",
                    "ABENDING PROGRAM");
        }
    }

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

            assertThat(abend.getReturnCode()).isEqualTo(AccountBalanceReaderJob.APPL_RESULT_FATAL);
            assertThat(sink.lines()).containsExactly(
                    AccountBalanceReaderJob.START_BANNER,
                    "ERROR OPENING CARDFILE",
                    FileStatus.toDisplayLine(expectedStatus),
                    AbendException.ABEND_DISPLAY_TEXT);
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

            assertThat(FileStatus.batchStatusOfCicsResp(FileStatus.NORMAL))
                    .contains(FileStatus.OK);
            verify(browse).readNext();
            assertThat(sink.lines()).containsExactly(
                    AccountBalanceReaderJob.START_BANNER, AccountBalanceReaderJob.END_BANNER);
        }
    }

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
            int applResult = AccountBalanceReaderJob.APPL_RESULT_ASSUMED_FAILURE;
            if (FileStatus.OK.equals(status)) {
                applResult -= applResult;
            } else {
                applResult = AccountBalanceReaderJob.APPL_RESULT_FATAL;
            }

            assertThat(applResult).isEqualTo(expected);
        }

        @Test
        @DisplayName("a close failure abends with 12 and never with the assumed 8")
        void aCloseFailureCarriesTwelveNotEight() {
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

            assertThat(lines).containsExactly(
                    AccountBalanceReaderJob.START_BANNER, AccountBalanceReaderJob.END_BANNER);
        }
    }

    @Nested
    @DisplayName("Record geometry - every CVACT02Y field at its declared offset - gates G19, G21")
    class RecordGeometry {
        @Test
        @DisplayName("the copybook field widths sum to the declared 150, FILLER included")
        void fieldWidthsSumToTheRecordLength() {
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
            List<String> lines = linesFrom(repositoryOverWholeFixture());

            for (String line : lines.subList(1, lines.size() - 1)) {
                assertThat(line.getBytes(StandardCharsets.US_ASCII))
                        .hasSize(CardRecord.RECORD_LENGTH);
            }
        }

        private String span(String image, int offset, int length) {
            return image.substring(offset, offset + length);
        }

        private String zoned(long value, int width) {
            String digits = Long.toString(value);
            return "0".repeat(width - digits.length()) + digits;
        }
    }

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

    @Nested
    @DisplayName("Dataset name provenance - gate G46")
    class DatasetNameProvenance {
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
