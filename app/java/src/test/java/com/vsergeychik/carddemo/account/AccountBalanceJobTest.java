package com.vsergeychik.carddemo.account;

import com.vsergeychik.carddemo.CardDemoApplication;
import com.vsergeychik.carddemo.account.AccountBalanceJob.SysoutSink;
import com.vsergeychik.carddemo.account.AccountBalanceJob.WorkingStorage;
import com.vsergeychik.carddemo.account.AccountRepository.AccountFile;
import com.vsergeychik.carddemo.account.AccountRepository.OpenMode;
import com.vsergeychik.carddemo.account.model.AccountRecord;
import com.vsergeychik.carddemo.common.AbendException;
import com.vsergeychik.carddemo.common.FileStatus;
import com.vsergeychik.carddemo.common.RecordImageForm;
import com.vsergeychik.carddemo.config.BatchConfig;
import com.vsergeychik.carddemo.config.BatchConfig.JobContract;
import com.vsergeychik.carddemo.config.BatchConfig.JobContracts;
import com.vsergeychik.carddemo.config.BatchConfig.StepContract;
import com.vsergeychik.carddemo.config.BatchConfig.StopRequestedException;
import com.vsergeychik.carddemo.config.BatchConfig.StopSignal;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBinding;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBindings;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobInterruptedException;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.scope.context.StepContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * Unit tests for {@link AccountBalanceJob}, the translation of {@code CBACT01C}.
 *
 * <h2>What is being asserted, and why it is asserted this way</h2>
 *
 * <p>This program writes no records. Its {@code SYSOUT} <em>is</em> its observable output, so every
 * assertion below is about the exact sequence of displayed lines: how many there are, what each one
 * contains, and in what order. That is why the job's sink is injected rather than captured from a
 * stream - the sequence is collected into a list and compared element by element.
 *
 * <p>Two kinds of test, and both are needed:
 * <ul>
 *   <li><strong>Whole runs against the real fixture.</strong>
 *       {@code src/test/resources/fixtures/acctdata.txt} is the classpath copy of
 *       {@code app/data/ASCII/acctdata.txt} - 50 records of exactly 300 bytes - seeded into an
 *       in-memory relation with one record-image column, which is the shape the production gateway is
 *       expected to present. A complete pass must emit exactly
 *       {@code 1 + (50 x 13) + 1 = 652} lines.</li>
 *   <li><strong>Driven failures for the three fatal arms.</strong> An absent relation drives the open
 *       failure, a row whose image is absent drives the read failure, and a describe that succeeds once
 *       and then refuses drives the close failure. Each has to reach its own error literal, the rendered
 *       file status, the abend banner and an {@link AbendException} carrying return code 12.</li>
 * </ul>
 *
 * <p>No dataset name from the real system appears here. The tests supply their own; the real ones live
 * only in {@code application.yml} (gate G46).
 */
@DisplayName("AccountBalanceJob - CBACT01C, which reads and prints the account master and computes "
        + "nothing")
class AccountBalanceJobTest {

    // =============================================================================================
    // Constants restated from the reference sources, so a drift shows up here as a failure.
    // =============================================================================================

    /** The code page the ASCII fixtures are stored in. Never the platform default. */
    private static final Charset ASCII = StandardCharsets.US_ASCII;

    /** A stand-in dataset name. The real one lives only in {@code application.yml}. */
    private static final String TEST_DSNAME = "TEST.ACCOUNT.KSDS";

    /** The record-image column of the seeded relation, and the name the probe will discover. */
    private static final String RECORD_IMAGE_COLUMN = "REC";

    /** The classpath location of the account fixture, copied from the reference tree. */
    private static final String FIXTURE = "/fixtures/acctdata.txt";

    /** The fixture's measured record count - {@code app/data/ASCII/acctdata.txt} has 50 lines. */
    private static final int FIXTURE_RECORDS = 50;

    /** The copybook record width, restated from {@code app/cpy/CVACT01Y.cpy}. */
    private static final int RECORD_LENGTH = 300;

    /** The copybook key width, restated from {@code ACCT-ID PIC 9(11)}. */
    private static final int KEY_LENGTH = 11;

    /** {@code FILLER PIC X(178)}, restated from {@code app/cpy/CVACT01Y.cpy:L17}. */
    private static final int FILLER_LENGTH = 178;

    /** The start banner, then 13 lines per record, then the end banner. */
    private static final int EXPECTED_LINES =
            1 + (FIXTURE_RECORDS * AccountBalanceJob.LINES_PER_RECORD) + 1;

    /**
     * The status a refused backend operation is reported as, and the image it renders to.
     *
     * <p>{@code AccountRepository.PERMANENT_ERROR_STATUS} is {@code '9'} followed by a zero feedback
     * byte, so {@code 9910-DISPLAY-IO-STATUS} renders it through its extended branch as {@code 9000}.
     */
    private static final String PERMANENT_ERROR_LINE =
            FileStatus.toDisplayLine(AccountRepository.PERMANENT_ERROR_STATUS);

    /** Distinguishes the in-memory database each seeded test uses, so no two tests share a relation. */
    private static final AtomicInteger DATABASE_SEQUENCE = new AtomicInteger();

    // =============================================================================================
    // Fixtures and helpers.
    // =============================================================================================

    /**
     * A collecting sink: the whole point of the seam, and what every assertion below reads.
     *
     * <p>Records the lines verbatim - no trimming, no normalisation - because trailing spaces are
     * significant in a 300-byte record image.
     */
    private static final class CapturedSysout implements SysoutSink {

        /** The lines written so far, in the order they were written. */
        private final List<String> lines = new ArrayList<>();

        @Override
        public void write(String line) {
            lines.add(line);
        }

        /**
         * The captured sequence.
         *
         * @return the lines, in emission order
         */
        List<String> lines() {
            return lines;
        }
    }

    /**
     * An {@link ObjectProvider} reporting the bean as absent, so the job falls back to its default sink.
     *
     * <p>Only {@code getObject()} is overridden: the interface's own {@code getIfAvailable(Supplier)}
     * catches the absence and calls the supplier, which is exactly the resolution being exercised.
     *
     * @param <T> the bean type
     */
    private static final class AbsentBean<T> implements ObjectProvider<T> {

        @Override
        public T getObject() {
            throw new NoSuchBeanDefinitionException("no bean of this type is declared in this test");
        }
    }

    /**
     * An {@link ObjectProvider} that always yields the given bean.
     *
     * @param bean the bean to yield
     * @param <T>  the bean type
     */
    private record PresentBean<T>(T bean) implements ObjectProvider<T> {

        @Override
        public T getObject() {
            return bean;
        }
    }

    /**
     * The dataset catalogue the repository resolves, at the copybook geometry.
     *
     * @return a catalogue naming {@link #TEST_DSNAME} under both the CICS file name and the batch DD
     *         name
     */
    private static DatasetBindings bindings() {
        DatasetBindings catalogue = new DatasetBindings();
        catalogue.put(AccountRepository.CICS_FILE_NAME, new DatasetBinding(TEST_DSNAME, "ksds", false,
                "FB", null, RECORD_LENGTH, "CVACT01Y", KEY_LENGTH, null, null, null));
        catalogue.put(AccountRepository.BATCH_DD_NAME, new DatasetBinding(TEST_DSNAME, "ksds", false,
                "FB", null, RECORD_LENGTH, "CVACT01Y", KEY_LENGTH, null, null, null));
        return catalogue;
    }

    /**
     * The {@code carddemo.jobs} contract for this job, exactly as {@code application.yml} declares it:
     * program {@code CBACT01C}, no parameters, one ungated step named {@code STEP05}.
     *
     * @return the catalogue containing that one contract
     */
    private static JobContracts jobContracts() {
        return jobContracts(new StepContract(AccountBalanceJob.STEP_NAME,
                AccountBalanceJob.PROGRAM_ID, false));
    }

    /**
     * The {@code carddemo.jobs} catalogue carrying one deliberately chosen step contract.
     *
     * @param step the step contract to declare
     * @return the catalogue
     */
    private static JobContracts jobContracts(StepContract step) {
        return jobContracts(List.of(step));
    }

    /**
     * The {@code carddemo.jobs} catalogue carrying a deliberately chosen step sequence.
     *
     * @param steps the sequence to declare, in order
     * @return the catalogue
     */
    private static JobContracts jobContracts(List<StepContract> steps) {
        JobContracts catalogue = new JobContracts();
        catalogue.put(AccountBalanceJob.JOB_KEY, new JobContract(AccountBalanceJob.PROGRAM_ID,
                List.of(), steps, null, Map.of()));
        return catalogue;
    }

    /**
     * The batch scaffolding, with a mocked job repository and transaction manager so a step and a job
     * can be built without an application context.
     *
     * @param contracts the {@code carddemo.jobs} catalogue to bind
     * @return the scaffolding
     */
    private static BatchConfig scaffolding(JobContracts contracts) {
        return new BatchConfig(new PresentBean<>(Mockito.mock(JobRepository.class)),
                new PresentBean<>(Mockito.mock(PlatformTransactionManager.class)),
                contracts, bindings());
    }

    /**
     * The job under test, over the given template, writing to the given sink.
     *
     * @param template the template reaching the seeded relation
     * @param sysout   where displayed lines are captured
     * @return the job
     */
    private static AccountBalanceJob job(JdbcTemplate template, SysoutSink sysout) {
        return new AccountBalanceJob(scaffolding(jobContracts()), repository(template),
                new PresentBean<>(sysout));
    }

    /**
     * A repository over the given template and the copybook-shaped bindings.
     *
     * @param template the template reaching the relation
     * @return the repository
     */
    private static AccountRepository repository(JdbcTemplate template) {
        return new AccountRepository(template, bindings(), ASCII, RecordImageForm.CHARACTER);
    }

    /**
     * A private in-memory database with no relation in it at all, so every describe is refused.
     *
     * @return a template over an empty database
     */
    private static JdbcTemplate emptyDatabase() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:acctjob" + DATABASE_SEQUENCE.incrementAndGet()
                        + ";DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE", "sa", "");
        dataSource.setDriverClassName("org.h2.Driver");
        return new JdbcTemplate(dataSource);
    }

    /**
     * A private in-memory relation with one record-image column, seeded with the given rows.
     *
     * @param rows the record images to insert, in the order given; a {@code null} entry seeds a row
     *             whose record image is absent, which is how the fatal read arm is driven
     * @return a template over the seeded relation
     */
    private static JdbcTemplate seeded(List<String> rows) {
        JdbcTemplate template = emptyDatabase();
        template.execute("CREATE TABLE \"" + TEST_DSNAME + "\" (" + RECORD_IMAGE_COLUMN
                + " VARCHAR(" + RECORD_LENGTH + "))");
        for (String row : rows) {
            template.update("INSERT INTO \"" + TEST_DSNAME + "\" VALUES (?)", row);
        }
        return template;
    }

    /**
     * A template whose <em>second</em> describe is refused, so the open succeeds and the close fails.
     *
     * <p>Only the {@code (String, ResultSetExtractor)} overload is stubbed, which both the open and the
     * close use and no read does, so the browse in between runs against the real relation. That makes
     * the arm reachable without depending on how many connections a pass happens to acquire.
     *
     * @param real the template over the seeded relation
     * @return a spy that refuses its second describe
     */
    @SuppressWarnings("unchecked")
    private static JdbcTemplate refusingTheSecondDescribe(JdbcTemplate real) {
        JdbcTemplate spy = Mockito.spy(real);
        Mockito.doCallRealMethod()
                .doThrow(new DataAccessResourceFailureException(
                        "the account master dataset is no longer addressable"))
                .when(spy).query(Mockito.anyString(), Mockito.any(ResultSetExtractor.class));
        return spy;
    }

    /**
     * The 50 fixture records, exactly as stored.
     *
     * @return the fixture's lines
     */
    private static List<String> fixtureRows() {
        try (InputStream stream = AccountBalanceJobTest.class.getResourceAsStream(FIXTURE)) {
            if (stream == null) {
                throw new IllegalStateException("The account fixture " + FIXTURE + " is absent from the "
                        + "test classpath; every expectation in this class is seeded from it");
            }
            return new String(stream.readAllBytes(), ASCII).lines().toList();
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    /**
     * The thirteen lines one record produces, in emission order, derived from the record's stored image
     * rather than from the job.
     *
     * @param image one 300-byte record image
     * @return the eleven labelled lines, the separator, and the raw image
     */
    private static List<String> expectedBlock(String image) {
        AccountRecord account = AccountRecord.decode(image, ASCII);
        return List.of(
                AccountBalanceJob.LABEL_ACCT_ID + account.rawAcctId(),
                AccountBalanceJob.LABEL_ACCT_ACTIVE_STATUS + account.rawAcctActiveStatus(),
                AccountBalanceJob.LABEL_ACCT_CURR_BAL + account.rawAcctCurrBal(),
                AccountBalanceJob.LABEL_ACCT_CREDIT_LIMIT + account.rawAcctCreditLimit(),
                AccountBalanceJob.LABEL_ACCT_CASH_CREDIT_LIMIT + account.rawAcctCashCreditLimit(),
                AccountBalanceJob.LABEL_ACCT_OPEN_DATE + account.rawAcctOpenDate(),
                AccountBalanceJob.LABEL_ACCT_EXPIRAION_DATE + account.rawAcctExpiraionDate(),
                AccountBalanceJob.LABEL_ACCT_REISSUE_DATE + account.rawAcctReissueDate(),
                AccountBalanceJob.LABEL_ACCT_CURR_CYC_CREDIT + account.rawAcctCurrCycCredit(),
                AccountBalanceJob.LABEL_ACCT_CURR_CYC_DEBIT + account.rawAcctCurrCycDebit(),
                AccountBalanceJob.LABEL_ACCT_GROUP_ID + account.rawAcctGroupId(),
                AccountBalanceJob.RECORD_SEPARATOR,
                image);
    }

    // =============================================================================================
    // The literals. Every one is byte-exact observable output.
    // =============================================================================================

    @Nested
    @DisplayName("The transcribed SYSOUT literals")
    class Literals {

        @Test
        @DisplayName("the two banners are the source's own, character for character")
        void banners() {
            assertThat(AccountBalanceJob.START_OF_EXECUTION)
                    .isEqualTo("START OF EXECUTION OF PROGRAM CBACT01C");
            assertThat(AccountBalanceJob.END_OF_EXECUTION)
                    .isEqualTo("END OF EXECUTION OF PROGRAM CBACT01C");
        }

        @Test
        @DisplayName("the three error texts are worded differently, and each is its paragraph's own")
        void errorTexts() {
            // L144 names the DD name; L110 and L162 name the file, and use different verbs.
            assertThat(AccountBalanceJob.ERROR_OPENING_ACCTFILE).isEqualTo("ERROR OPENING ACCTFILE");
            assertThat(AccountBalanceJob.ERROR_READING_ACCOUNT_FILE)
                    .isEqualTo("ERROR READING ACCOUNT FILE");
            assertThat(AccountBalanceJob.ERROR_CLOSING_ACCOUNT_FILE)
                    .isEqualTo("ERROR CLOSING ACCOUNT FILE");
            assertThat(List.of(AccountBalanceJob.ERROR_OPENING_ACCTFILE,
                    AccountBalanceJob.ERROR_READING_ACCOUNT_FILE,
                    AccountBalanceJob.ERROR_CLOSING_ACCOUNT_FILE)).doesNotHaveDuplicates();
        }

        @Test
        @DisplayName("the separator is exactly 49 hyphens - not 48, not 50, not 80")
        void separator() {
            assertThat(AccountBalanceJob.SEPARATOR_WIDTH).isEqualTo(49);
            assertThat(AccountBalanceJob.RECORD_SEPARATOR).hasSize(49);
            assertThat(AccountBalanceJob.RECORD_SEPARATOR.chars()).allMatch(c -> c == '-');
        }

        @Test
        @DisplayName("each of the eleven labels is exactly 25 characters and ends in a colon")
        void labelWidths() {
            assertThat(AccountBalanceJob.LABEL_WIDTH).isEqualTo(25);
            assertThat(AccountBalanceJob.FIELD_LABELS).hasSize(11).allSatisfy(label -> {
                assertThat(label).hasSize(AccountBalanceJob.LABEL_WIDTH);
                assertThat(label).endsWith(":");
            });
        }

        @Test
        @DisplayName("the labels are in the source's emission order")
        void labelOrder() {
            assertThat(AccountBalanceJob.FIELD_LABELS).containsExactly(
                    AccountBalanceJob.LABEL_ACCT_ID,
                    AccountBalanceJob.LABEL_ACCT_ACTIVE_STATUS,
                    AccountBalanceJob.LABEL_ACCT_CURR_BAL,
                    AccountBalanceJob.LABEL_ACCT_CREDIT_LIMIT,
                    AccountBalanceJob.LABEL_ACCT_CASH_CREDIT_LIMIT,
                    AccountBalanceJob.LABEL_ACCT_OPEN_DATE,
                    AccountBalanceJob.LABEL_ACCT_EXPIRAION_DATE,
                    AccountBalanceJob.LABEL_ACCT_REISSUE_DATE,
                    AccountBalanceJob.LABEL_ACCT_CURR_CYC_CREDIT,
                    AccountBalanceJob.LABEL_ACCT_CURR_CYC_DEBIT,
                    AccountBalanceJob.LABEL_ACCT_GROUP_ID);
        }

        @Test
        @DisplayName("the seventh label carries the copybook's misspelling, and not the correction")
        void theMisspellingIsPreserved() {
            assertThat(AccountBalanceJob.LABEL_ACCT_EXPIRAION_DATE)
                    .startsWith("ACCT-EXPIRAION-DATE")
                    .doesNotContain("EXPIRATION");
            assertThat(AccountBalanceJob.FIELD_LABELS.get(6))
                    .isEqualTo(AccountBalanceJob.LABEL_ACCT_EXPIRAION_DATE);
        }

        @Test
        @DisplayName("ACCT-ADDR-ZIP has no label: the copybook declares 12 fields, the paragraph shows 11")
        void theZipCodeIsNotDisplayed() {
            assertThat(AccountBalanceJob.FIELD_LABELS)
                    .noneMatch(label -> label.startsWith(AccountRecord.ACCT_ADDR_ZIP_NAME));
        }

        @Test
        @DisplayName("every label announces the copybook field name it precedes")
        void labelsMatchTheCopybookNames() {
            List<String> declared = List.of(
                    AccountRecord.ACCT_ID_NAME,
                    AccountRecord.ACCT_ACTIVE_STATUS_NAME,
                    AccountRecord.ACCT_CURR_BAL_NAME,
                    AccountRecord.ACCT_CREDIT_LIMIT_NAME,
                    AccountRecord.ACCT_CASH_CREDIT_LIMIT_NAME,
                    AccountRecord.ACCT_OPEN_DATE_NAME,
                    AccountRecord.ACCT_EXPIRAION_DATE_NAME,
                    AccountRecord.ACCT_REISSUE_DATE_NAME,
                    AccountRecord.ACCT_CURR_CYC_CREDIT_NAME,
                    AccountRecord.ACCT_CURR_CYC_DEBIT_NAME,
                    AccountRecord.ACCT_GROUP_ID_NAME);
            for (int index = 0; index < declared.size(); index++) {
                String label = AccountBalanceJob.FIELD_LABELS.get(index);
                assertThat(label).startsWith(declared.get(index));
                assertThat(label.substring(declared.get(index).length()))
                        .as("padding then a colon, and nothing else")
                        .matches(" *:");
            }
        }

        @Test
        @DisplayName("the identity constants name the program, the JCL step and the DD name")
        void identity() {
            assertThat(AccountBalanceJob.PROGRAM_ID).isEqualTo("CBACT01C");
            assertThat(AccountBalanceJob.JOB_KEY).isEqualTo("account-balance-job");
            assertThat(AccountBalanceJob.JOB_NAME).isEqualTo("accountBalanceJob");
            assertThat(AccountBalanceJob.STEP_NAME).isEqualTo("STEP05");
            assertThat(AccountBalanceJob.DD_NAME).isEqualTo("ACCTFILE")
                    .isEqualTo(AccountRepository.BATCH_DD_NAME);
        }

        @Test
        @DisplayName("the APPL-RESULT values and the flag literals are the source's")
        void applResultValues() {
            assertThat(AccountBalanceJob.APPL_AOK).isZero();
            assertThat(AccountBalanceJob.APPL_EOF).isEqualTo(16);
            assertThat(AccountBalanceJob.APPL_RESULT_ASSUMED_FAILURE).isEqualTo(8);
            assertThat(AccountBalanceJob.APPL_RESULT_FATAL).isEqualTo(12);
            assertThat(AccountBalanceJob.RETURN_CODE_NORMAL_END).isZero();
            assertThat(AccountBalanceJob.END_OF_FILE_NO).isEqualTo("N");
            assertThat(AccountBalanceJob.END_OF_FILE_YES).isEqualTo("Y");
        }

        @Test
        @DisplayName("a record produces thirteen lines: eleven labelled, one separator, one raw image")
        void thirteenLinesPerRecord() {
            assertThat(AccountBalanceJob.LINES_PER_RECORD)
                    .isEqualTo(AccountBalanceJob.FIELD_LABELS.size() + 2)
                    .isEqualTo(13);
        }
    }

    // =============================================================================================
    // A complete pass over the real fixture.
    // =============================================================================================

    @Nested
    @DisplayName("A full pass over the fifty-record fixture")
    class FullPass {

        @Test
        @DisplayName("it emits exactly 652 lines: one banner, 50 x 13, one banner")
        void lineCount() {
            CapturedSysout sysout = new CapturedSysout();
            int recordsDisplayed = job(seeded(fixtureRows()), sysout).readAndPrintAccountFile();

            assertThat(recordsDisplayed).isEqualTo(FIXTURE_RECORDS);
            assertThat(sysout.lines()).hasSize(EXPECTED_LINES).hasSize(652);
            assertThat(sysout.lines().get(0)).isEqualTo(AccountBalanceJob.START_OF_EXECUTION);
            assertThat(sysout.lines().get(EXPECTED_LINES - 1))
                    .isEqualTo(AccountBalanceJob.END_OF_EXECUTION);
        }

        @Test
        @DisplayName("every record's thirteen lines appear in order, field images byte-for-byte")
        void everyBlockMatchesItsRecord() {
            List<String> rows = fixtureRows();
            CapturedSysout sysout = new CapturedSysout();
            job(seeded(rows), sysout).readAndPrintAccountFile();

            // The browse is in ascending key order, and the fixture is already in that order.
            List<String> expected = new ArrayList<>();
            expected.add(AccountBalanceJob.START_OF_EXECUTION);
            rows.stream().sorted().forEach(row -> expected.addAll(expectedBlock(row)));
            expected.add(AccountBalanceJob.END_OF_EXECUTION);

            assertThat(sysout.lines()).containsExactlyElementsOf(expected);
        }

        @Test
        @DisplayName("the first record's block is exactly the thirteen lines the COBOL would print")
        void theFirstBlockInFull() {
            String first = fixtureRows().stream().sorted().findFirst().orElseThrow();
            CapturedSysout sysout = new CapturedSysout();
            job(seeded(fixtureRows()), sysout).readAndPrintAccountFile();

            assertThat(sysout.lines().subList(1, 1 + AccountBalanceJob.LINES_PER_RECORD))
                    .containsExactlyElementsOf(expectedBlock(first));
        }

        @Test
        @DisplayName("the raw record line is 300 bytes and ends in 178 FILLER spaces")
        void theRawRecordLineIsTheWholeRecord() {
            CapturedSysout sysout = new CapturedSysout();
            job(seeded(fixtureRows()), sysout).readAndPrintAccountFile();

            // Line 13 of the first block is the mainline's DISPLAY ACCOUNT-RECORD.
            String raw = sysout.lines().get(AccountBalanceJob.LINES_PER_RECORD);
            assertThat(raw).hasSize(RECORD_LENGTH);
            assertThat(raw.substring(RECORD_LENGTH - FILLER_LENGTH))
                    .as("FILLER X(178), emitted as spaces (gates G19 and G21)")
                    .isEqualTo(" ".repeat(FILLER_LENGTH));
        }

        @Test
        @DisplayName("each record really is displayed twice, in two different shapes")
        void theRecordIsDisplayedTwice() {
            String first = fixtureRows().stream().sorted().findFirst().orElseThrow();
            CapturedSysout sysout = new CapturedSysout();
            job(seeded(fixtureRows()), sysout).readAndPrintAccountFile();

            AccountRecord account = AccountRecord.decode(first, ASCII);
            // Shape one: the labelled line from 1100-DISPLAY-ACCT-RECORD.
            assertThat(sysout.lines())
                    .contains(AccountBalanceJob.LABEL_ACCT_ID + account.rawAcctId());
            // Shape two: the raw image from the mainline. The account id appears in both.
            assertThat(sysout.lines()).contains(first);
        }

        @Test
        @DisplayName("no line mentions ACCT-ADDR-ZIP")
        void theZipCodeNeverAppears() {
            CapturedSysout sysout = new CapturedSysout();
            job(seeded(fixtureRows()), sysout).readAndPrintAccountFile();

            assertThat(sysout.lines()).noneMatch(line -> line.contains(AccountRecord.ACCT_ADDR_ZIP_NAME));
        }

        @Test
        @DisplayName("the misspelled label appears fifty times and the corrected spelling never")
        void theMisspellingReachesTheOutput() {
            CapturedSysout sysout = new CapturedSysout();
            job(seeded(fixtureRows()), sysout).readAndPrintAccountFile();

            assertThat(sysout.lines().stream()
                    .filter(line -> line.startsWith(AccountBalanceJob.LABEL_ACCT_EXPIRAION_DATE))
                    .count()).isEqualTo(FIXTURE_RECORDS);
            assertThat(sysout.lines()).noneMatch(line -> line.contains("ACCT-EXPIRATION-DATE"));
        }

        @Test
        @DisplayName("a monetary field is displayed as its stored zoned image, never as a formatted "
                + "number")
        void monetaryFieldsKeepTheirStoredImage() {
            String first = fixtureRows().stream().sorted().findFirst().orElseThrow();
            CapturedSysout sysout = new CapturedSysout();
            job(seeded(fixtureRows()), sysout).readAndPrintAccountFile();

            String balanceLine = sysout.lines().stream()
                    .filter(line -> line.startsWith(AccountBalanceJob.LABEL_ACCT_CURR_BAL))
                    .findFirst().orElseThrow();
            String image = balanceLine.substring(AccountBalanceJob.LABEL_WIDTH);

            assertThat(image)
                    .as("twelve stored characters, the sign overpunched into the last")
                    .hasSize(AccountRecord.ACCT_CURR_BAL_LENGTH)
                    .isEqualTo(first.substring(AccountRecord.ACCT_CURR_BAL_OFFSET,
                            AccountRecord.ACCT_CURR_BAL_OFFSET + AccountRecord.ACCT_CURR_BAL_LENGTH))
                    .doesNotContain(".");
        }

        @Test
        @DisplayName("no line is prefixed with a timestamp, a level or a thread name")
        void nothingDecoratesTheOutput() {
            CapturedSysout sysout = new CapturedSysout();
            job(seeded(fixtureRows()), sysout).readAndPrintAccountFile();

            assertThat(sysout.lines()).allSatisfy(line -> assertThat(line)
                    .doesNotStartWith(" ")
                    .doesNotContain("INFO")
                    .doesNotContain("DEBUG"));
        }
    }

    // =============================================================================================
    // The empty dataset, and the shape of a run that displays nothing.
    // =============================================================================================

    @Nested
    @DisplayName("An empty account master")
    class EmptyDataset {

        @Test
        @DisplayName("it emits the two banners and nothing between them")
        void twoBannersOnly() {
            CapturedSysout sysout = new CapturedSysout();
            int recordsDisplayed = job(seeded(List.of()), sysout).readAndPrintAccountFile();

            assertThat(recordsDisplayed).isZero();
            assertThat(sysout.lines()).containsExactly(AccountBalanceJob.START_OF_EXECUTION,
                    AccountBalanceJob.END_OF_EXECUTION);
        }

        @Test
        @DisplayName("the first read reaches end of file, so no raw record line is written")
        void theEndOfFileArmDisplaysNothing() {
            CapturedSysout sysout = new CapturedSysout();
            job(seeded(List.of()), sysout).readAndPrintAccountFile();

            assertThat(sysout.lines()).noneMatch(line -> line.length() == RECORD_LENGTH);
        }
    }

    // =============================================================================================
    // The three fatal arms. Each must display its own text, render the status, announce the abend and
    // raise it with return code 12 (gates G35 and G47).
    // =============================================================================================

    @Nested
    @DisplayName("The fatal arm of 0000-ACCTFILE-OPEN")
    class OpenFailure {

        @Test
        @DisplayName("an unreachable dataset displays the open text, the status and the abend banner")
        void theOpenFailurePath() {
            CapturedSysout sysout = new CapturedSysout();
            AccountBalanceJob subject = job(emptyDatabase(), sysout);

            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(subject::readAndPrintAccountFile);

            assertThat(sysout.lines()).containsExactly(
                    AccountBalanceJob.START_OF_EXECUTION,
                    AccountBalanceJob.ERROR_OPENING_ACCTFILE,
                    PERMANENT_ERROR_LINE,
                    AbendException.ABEND_DISPLAY_TEXT);
        }

        @Test
        @DisplayName("the abend carries CBACT01C, return code 12, ABCODE 999 and TIMING 0")
        void theAbendItRaises() {
            CapturedSysout sysout = new CapturedSysout();
            AccountBalanceJob subject = job(emptyDatabase(), sysout);

            AbendException abend = org.junit.jupiter.api.Assertions.assertThrows(
                    AbendException.class, subject::readAndPrintAccountFile);

            assertThat(abend.getProgram()).isEqualTo(AccountBalanceJob.PROGRAM_ID);
            assertThat(abend.getReturnCode()).isEqualTo(AccountBalanceJob.APPL_RESULT_FATAL).isEqualTo(12);
            assertThat(abend.getAbendCode()).hasValue(AbendException.STANDARD_ABEND_CODE);
            assertThat(abend.getAbendCode()).hasValue(999);
            assertThat(abend.getTiming()).hasValue(AbendException.STANDARD_TIMING);
            assertThat(abend.getReason()).isPresent();
            assertThat(abend.getReason().orElseThrow())
                    .contains(AccountBalanceJob.ERROR_OPENING_ACCTFILE)
                    .contains(PERMANENT_ERROR_LINE);
        }

        @Test
        @DisplayName("the closing banner is never displayed, because the program abended")
        void noClosingBanner() {
            CapturedSysout sysout = new CapturedSysout();
            AccountBalanceJob subject = job(emptyDatabase(), sysout);

            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(subject::readAndPrintAccountFile);

            assertThat(sysout.lines()).doesNotContain(AccountBalanceJob.END_OF_EXECUTION);
        }
    }

    @Nested
    @DisplayName("The fatal arm of 1000-ACCTFILE-GET-NEXT")
    class ReadFailure {

        /** A relation holding one row whose record image is absent: a record that cannot be read. */
        private JdbcTemplate unreadableRow() {
            JdbcTemplate template = emptyDatabase();
            template.execute("CREATE TABLE \"" + TEST_DSNAME + "\" (" + RECORD_IMAGE_COLUMN
                    + " VARCHAR(" + RECORD_LENGTH + "))");
            template.update("INSERT INTO \"" + TEST_DSNAME + "\" VALUES (NULL)");
            return template;
        }

        @Test
        @DisplayName("a row that cannot be read displays the read text, the status and the abend banner")
        void theReadFailurePath() {
            CapturedSysout sysout = new CapturedSysout();
            AccountBalanceJob subject = job(unreadableRow(), sysout);

            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(subject::readAndPrintAccountFile);

            assertThat(sysout.lines()).containsExactly(
                    AccountBalanceJob.START_OF_EXECUTION,
                    AccountBalanceJob.ERROR_READING_ACCOUNT_FILE,
                    PERMANENT_ERROR_LINE,
                    AbendException.ABEND_DISPLAY_TEXT);
        }

        @Test
        @DisplayName("the abend carries return code 12 and names the read failure")
        void theAbendItRaises() {
            CapturedSysout sysout = new CapturedSysout();
            AccountBalanceJob subject = job(unreadableRow(), sysout);

            AbendException abend = org.junit.jupiter.api.Assertions.assertThrows(
                    AbendException.class, subject::readAndPrintAccountFile);

            assertThat(abend.getReturnCode()).isEqualTo(12);
            assertThat(abend.getProgram()).isEqualTo(AccountBalanceJob.PROGRAM_ID);
            assertThat(abend.getReason().orElseThrow())
                    .contains(AccountBalanceJob.ERROR_READING_ACCOUNT_FILE);
        }

        @Test
        @DisplayName("nothing of the record is displayed: the fatal read never reaches either display")
        void noRecordIsDisplayed() {
            CapturedSysout sysout = new CapturedSysout();
            AccountBalanceJob subject = job(unreadableRow(), sysout);

            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(subject::readAndPrintAccountFile);

            assertThat(sysout.lines()).noneMatch(line -> line.contains(AccountRecord.ACCT_ID_NAME));
            assertThat(sysout.lines()).doesNotContain(AccountBalanceJob.RECORD_SEPARATOR);
        }
    }

    // =================================================================================================
    // The handle is released however the pass ends. CBACT01C abends outright without closing, so what
    // matters here is that releasing changes nothing the program observably produces - its SYSOUT line
    // sequence and its return code are the whole of that.
    // =================================================================================================

    @Nested
    @DisplayName("the account-master handle is released however the pass ends")
    class HandleRelease {

        /**
         * The job over a repository whose opens can be observed.
         *
         * @param template the template reaching the relation
         * @param sysout   where displayed lines are captured
         * @param opened   collects every handle the open returned
         * @return the job
         */
        private AccountBalanceJob jobRecordingOpens(JdbcTemplate template, SysoutSink sysout,
                List<AccountFile> opened) {
            AccountRepository spied = Mockito.spy(repository(template));
            Mockito.doAnswer(invocation -> {
                AccountFile handle = (AccountFile) invocation.callRealMethod();
                opened.add(handle);
                return handle;
            }).when(spied).open(Mockito.any());
            return new AccountBalanceJob(scaffolding(jobContracts()), spied, new PresentBean<>(sysout));
        }

        @Test
        @DisplayName("an abending read leaves the handle closed, and adds no line to SYSOUT")
        void anAbendingReadStillReleasesTheHandle() {
            JdbcTemplate template = emptyDatabase();
            template.execute("CREATE TABLE \"" + TEST_DSNAME + "\" (" + RECORD_IMAGE_COLUMN
                    + " VARCHAR(" + RECORD_LENGTH + "))");
            template.update("INSERT INTO \"" + TEST_DSNAME + "\" VALUES (NULL)");
            CapturedSysout sysout = new CapturedSysout();
            List<AccountFile> opened = new ArrayList<>();

            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> jobRecordingOpens(template, sysout, opened)
                            .readAndPrintAccountFile());

            assertThat(opened).hasSize(1);
            assertThat(opened.get(0).isClosed())
                    .as("L83 was never reached, so the request boundary released the handle")
                    .isTrue();
            assertThat(sysout.lines())
                    .as("and it did so silently - the source has no such line")
                    .containsExactly(AccountBalanceJob.START_OF_EXECUTION,
                            AccountBalanceJob.ERROR_READING_ACCOUNT_FILE,
                            PERMANENT_ERROR_LINE,
                            AbendException.ABEND_DISPLAY_TEXT);
        }

        @Test
        @DisplayName("a normal run closes the handle once, at L83, and the release does not fire")
        void aNormalRunClosesAtL83() {
            CapturedSysout sysout = new CapturedSysout();
            List<AccountFile> opened = new ArrayList<>();

            jobRecordingOpens(seeded(List.of()), sysout, opened).readAndPrintAccountFile();

            assertThat(opened).hasSize(1);
            assertThat(opened.get(0).isClosed()).isTrue();
            assertThat(sysout.lines())
                    .as("the successful path is unchanged: banner, close, banner")
                    .containsExactly(AccountBalanceJob.START_OF_EXECUTION,
                            AccountBalanceJob.END_OF_EXECUTION);
        }
    }

    @Nested
    @DisplayName("The fatal arm of 9000-ACCTFILE-CLOSE")
    class CloseFailure {

        @Test
        @DisplayName("a close that cannot describe the dataset displays the close text and abends")
        void theCloseFailurePath() {
            CapturedSysout sysout = new CapturedSysout();
            AccountBalanceJob subject =
                    job(refusingTheSecondDescribe(seeded(List.of())), sysout);

            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(subject::readAndPrintAccountFile);

            assertThat(sysout.lines()).containsExactly(
                    AccountBalanceJob.START_OF_EXECUTION,
                    AccountBalanceJob.ERROR_CLOSING_ACCOUNT_FILE,
                    PERMANENT_ERROR_LINE,
                    AbendException.ABEND_DISPLAY_TEXT);
        }

        @Test
        @DisplayName("the closing banner is not displayed, because the close abended before it")
        void noClosingBanner() {
            CapturedSysout sysout = new CapturedSysout();
            AccountBalanceJob subject =
                    job(refusingTheSecondDescribe(seeded(List.of())), sysout);

            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(subject::readAndPrintAccountFile);

            assertThat(sysout.lines()).doesNotContain(AccountBalanceJob.END_OF_EXECUTION);
        }

        @Test
        @DisplayName("the abend carries return code 12, reached by ADD 12 TO ZERO GIVING")
        void theAbendItRaises() {
            CapturedSysout sysout = new CapturedSysout();
            AccountBalanceJob subject =
                    job(refusingTheSecondDescribe(seeded(List.of())), sysout);

            AbendException abend = org.junit.jupiter.api.Assertions.assertThrows(
                    AbendException.class, subject::readAndPrintAccountFile);

            assertThat(abend.getReturnCode()).isEqualTo(12);
            assertThat(abend.getReason().orElseThrow())
                    .contains(AccountBalanceJob.ERROR_CLOSING_ACCOUNT_FILE);
        }
    }

    // =============================================================================================
    // The Spring Batch surface: a job, a step and a tasklet, all buildable with no application context.
    // =============================================================================================

    @Nested
    @DisplayName("The published job, its step and its tasklet")
    class SpringSurface {

        @Test
        @DisplayName("the job is named accountBalanceJob")
        void theJob() {
            Job published = job(seeded(List.of()), new CapturedSysout()).accountBalanceJob();

            assertThat(published).isNotNull();
            assertThat(published.getName()).isEqualTo(AccountBalanceJob.JOB_NAME);
        }

        @Test
        @DisplayName("the step is named STEP05, after the JCL step it replaces")
        void theStep() {
            Step step = job(seeded(List.of()), new CapturedSysout()).accountBalanceStep();

            assertThat(step).isNotNull();
            assertThat(step.getName()).isEqualTo(AccountBalanceJob.STEP_NAME);
        }

        @Test
        @DisplayName("a fresh builder is used per call, so two jobs are distinct objects")
        void buildersAreNotShared() {
            AccountBalanceJob subject = job(seeded(List.of()), new CapturedSysout());

            assertThat(subject.accountBalanceJob()).isNotSameAs(subject.accountBalanceJob());
            assertThat(subject.accountBalanceStep()).isNotSameAs(subject.accountBalanceStep());
        }

        @Test
        @DisplayName("the tasklet runs one complete pass, reports FINISHED and reports the read count")
        void theTasklet() throws Exception {
            CapturedSysout sysout = new CapturedSysout();
            Tasklet tasklet = job(seeded(fixtureRows()), sysout).accountFileDisplayTasklet();

            StepExecution stepExecution = new StepExecution(AccountBalanceJob.STEP_NAME,
                    new JobExecution(1L));
            StepContribution contribution = new StepContribution(stepExecution);

            RepeatStatus status =
                    tasklet.execute(contribution, new ChunkContext(new StepContext(stepExecution)));

            assertThat(status).isEqualTo(RepeatStatus.FINISHED);
            assertThat(contribution.getReadCount()).isEqualTo(FIXTURE_RECORDS);
            assertThat(sysout.lines()).hasSize(EXPECTED_LINES);
        }

        @Test
        @DisplayName("an empty dataset leaves the read count at zero")
        void theTaskletOverAnEmptyDataset() throws Exception {
            Tasklet tasklet =
                    job(seeded(List.of()), new CapturedSysout()).accountFileDisplayTasklet();

            StepExecution stepExecution = new StepExecution(AccountBalanceJob.STEP_NAME,
                    new JobExecution(2L));
            StepContribution contribution = new StepContribution(stepExecution);

            assertThat(tasklet.execute(contribution,
                    new ChunkContext(new StepContext(stepExecution)))).isEqualTo(RepeatStatus.FINISHED);
            assertThat(contribution.getReadCount()).isZero();
        }

        @Test
        @DisplayName("the tasklet lets an abend reach the framework rather than swallowing it")
        void theTaskletPropagatesAnAbend() {
            Tasklet tasklet =
                    job(emptyDatabase(), new CapturedSysout()).accountFileDisplayTasklet();
            StepExecution stepExecution = new StepExecution(AccountBalanceJob.STEP_NAME,
                    new JobExecution(3L));
            StepContribution contribution = new StepContribution(stepExecution);
            ChunkContext chunkContext = new ChunkContext(new StepContext(stepExecution));

            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> tasklet.execute(contribution, chunkContext));
        }

        @Test
        @DisplayName("the job declares no job parameters, because READACCT.jcl carries no PARM")
        void noJobParameters() {
            assertThat(job(seeded(List.of()), new CapturedSysout()).jobParameters().isEmpty()).isTrue();
        }

        @Test
        @DisplayName("the resolved step contract is the configured one")
        void theStepContract() {
            StepContract contract = job(seeded(List.of()), new CapturedSysout()).stepContract();

            assertThat(contract.name()).isEqualTo(AccountBalanceJob.STEP_NAME);
            assertThat(contract.program()).isEqualTo(AccountBalanceJob.PROGRAM_ID);
            assertThat(contract.requirePrecedingExitCodeZero()).isFalse();
        }
    }

    /**
     * The context-load gate (G3): the real application context, under the fixture-backed profile.
     *
     * <h2>Why a whole context is started for this, when nothing else here needs one</h2>
     *
     * <p>Because one entire class of defect in a {@code @Configuration} job class is invisible to every
     * other kind of test and fatal in production. Component scanning names a configuration bean after its
     * class, so {@code AccountBalanceJob} is registered as {@code accountBalanceJob} - which is exactly
     * the name {@link AccountBalanceJob#accountBalanceJob()} publishes the job under. Spring Boot
     * disables bean-definition overriding by default, so the two definitions do not merge or shadow: the
     * context refuses to start at all. Every unit test above passes regardless, because none of them
     * registers a bean definition.
     *
     * <p>{@link AccountBalanceJob#CONFIGURATION_BEAN_NAME} is what resolves it, and this is the only
     * test that can tell whether it still does. It is also the guard for the nine sibling batch job
     * classes being added to this module, each of which is a class named after the job it publishes.
     *
     * <p>The assertions are scoped to this job's own beans and never to a bean count or an exhaustive
     * bean-name list, so a sibling job arriving in the same context makes this pass rather than fail.
     */
    @Nested
    @DisplayName("The context-load gate - the job bean really does wire (G3)")
    @SpringBootTest(classes = CardDemoApplication.class)
    @ActiveProfiles("test")
    class ContextWiring {

        /** The started context, injected so the beans can be looked up by name and by type. */
        @Autowired
        private ApplicationContext context;

        @Test
        @DisplayName("the configuration bean and the job bean coexist under distinct names")
        void bothBeansAreRegistered() {
            assertThat(context.containsBean(AccountBalanceJob.CONFIGURATION_BEAN_NAME)).isTrue();
            assertThat(context.containsBean(AccountBalanceJob.JOB_NAME)).isTrue();
            assertThat(AccountBalanceJob.CONFIGURATION_BEAN_NAME)
                    .as("a configuration bean named after its own job collides with the job bean")
                    .isNotEqualTo(AccountBalanceJob.JOB_NAME);
        }

        @Test
        @DisplayName("the job bean is a Job whose name is the one a launcher looks up")
        void theJobBeanResolves() {
            Job published = context.getBean(AccountBalanceJob.JOB_NAME, Job.class);

            assertThat(published.getName()).isEqualTo(AccountBalanceJob.JOB_NAME);
            assertThat(context.getBeanNamesForType(Job.class))
                    .contains(AccountBalanceJob.JOB_NAME);
        }

        @Test
        @DisplayName("the job class wires with the configured contract, no parameters and a sink")
        void theJobClassWires() {
            AccountBalanceJob subject = context.getBean(AccountBalanceJob.class);

            assertThat(subject.stepContract().name()).isEqualTo(AccountBalanceJob.STEP_NAME);
            assertThat(subject.stepContract().program()).isEqualTo(AccountBalanceJob.PROGRAM_ID);
            assertThat(subject.jobParameters().isEmpty()).isTrue();
            assertThat(subject.sysoutSink())
                    .as("with no sink bean declared, the standard-output sink is resolved")
                    .isNotNull();
        }
    }

    @Nested
    @DisplayName("Construction validates the job contract before anything can run")
    class ContractValidation {

        @Test
        @DisplayName("a step naming another program is refused")
        void aStepNamingAnotherProgram() {
            BatchConfig wrong = scaffolding(jobContracts(
                    new StepContract(AccountBalanceJob.STEP_NAME, "CBACT02C", false)));

            assertThatIllegalStateException()
                    .isThrownBy(() -> new AccountBalanceJob(wrong, repository(seeded(List.of())),
                            new AbsentBean<>()))
                    .withMessageContaining("CBACT02C")
                    .withMessageContaining(AccountBalanceJob.PROGRAM_ID);
        }

        @Test
        @DisplayName("a gated step is refused, because READACCT.jcl carries no COND")
        void aGatedStep() {
            BatchConfig gated = scaffolding(jobContracts(new StepContract(
                    AccountBalanceJob.STEP_NAME, AccountBalanceJob.PROGRAM_ID, true)));

            assertThatIllegalStateException()
                    .isThrownBy(() -> new AccountBalanceJob(gated, repository(seeded(List.of())),
                            new AbsentBean<>()))
                    .withMessageContaining("COND");
        }

        @Test
        @DisplayName("a second step declared beside STEP05 is refused, because READACCT.jcl has one "
                + "EXEC and no other")
        void anAddedStep() {
            // The three checks above examine STEP05 and are structurally blind to anything declared
            // beside it: the step they resolve is found by name, so it is found whether it is the only
            // step or the first of two. A second step would run work app/jcl/READACCT.jcl never ran, and
            // - because this job's only effect is DISPLAY output - would silently double the SYSOUT the
            // parity harness compares.
            BatchConfig extra = scaffolding(jobContracts(List.of(
                    new StepContract(AccountBalanceJob.STEP_NAME, AccountBalanceJob.PROGRAM_ID, false),
                    new StepContract("STEP06", AccountBalanceJob.PROGRAM_ID, false))));

            assertThatIllegalStateException()
                    .isThrownBy(() -> new AccountBalanceJob(extra, repository(seeded(List.of())),
                            new AbsentBean<>()))
                    .withMessageContaining("does not declare the step sequence of "
                            + "app/jcl/READACCT.jcl")
                    .withMessageContaining("configured: [STEP05/CBACT01C, STEP06/CBACT01C]")
                    .withMessageContaining("required:   [STEP05/CBACT01C]");
        }

        @Test
        @DisplayName("the shipped single-step sequence is accepted")
        void theShippedSequence() {
            assertThat(AccountBalanceJob.REQUIRED_STEPS)
                    .containsExactly(new StepContract(AccountBalanceJob.STEP_NAME,
                            AccountBalanceJob.PROGRAM_ID, false));
            assertThat(new AccountBalanceJob(scaffolding(jobContracts()),
                    repository(seeded(List.of())), new AbsentBean<>()).stepContract())
                    .isEqualTo(AccountBalanceJob.REQUIRED_STEPS.get(0));
        }

        @Test
        @DisplayName("a differently named step is refused by the contract catalogue itself")
        void anUnknownStepName() {
            BatchConfig renamed = scaffolding(jobContracts(
                    new StepContract("STEP99", AccountBalanceJob.PROGRAM_ID, false)));

            assertThatIllegalStateException()
                    .isThrownBy(() -> new AccountBalanceJob(renamed, repository(seeded(List.of())),
                            new AbsentBean<>()))
                    .withMessageContaining(AccountBalanceJob.STEP_NAME);
        }

        @Test
        @DisplayName("an absent job key is refused")
        void anAbsentJobKey() {
            BatchConfig empty = new BatchConfig(new PresentBean<>(Mockito.mock(JobRepository.class)),
                    new PresentBean<>(Mockito.mock(PlatformTransactionManager.class)),
                    new JobContracts(), bindings());

            assertThatIllegalStateException()
                    .isThrownBy(() -> new AccountBalanceJob(empty, repository(seeded(List.of())),
                            new AbsentBean<>()));
        }

        @Test
        @DisplayName("every collaborator is required")
        void collaboratorsAreRequired() {
            BatchConfig valid = scaffolding(jobContracts());
            AccountRepository accounts = repository(seeded(List.of()));

            assertThatNullPointerException().isThrownBy(
                    () -> new AccountBalanceJob(null, accounts, new AbsentBean<>()));
            assertThatNullPointerException().isThrownBy(
                    () -> new AccountBalanceJob(valid, null, new AbsentBean<>()));
            assertThatNullPointerException().isThrownBy(
                    () -> new AccountBalanceJob(valid, accounts, null));
        }
    }

    // =============================================================================================
    // The SYSOUT seam.
    // =============================================================================================

    @Nested
    @DisplayName("The SYSOUT seam")
    class SysoutSeam {

        @Test
        @DisplayName("an injected sink is the one actually used")
        void anInjectedSinkIsUsed() {
            CapturedSysout sysout = new CapturedSysout();
            AccountBalanceJob subject = job(seeded(List.of()), sysout);

            assertThat(subject.sysoutSink()).isSameAs(sysout);
        }

        @Test
        @DisplayName("with no sink bean, the standard-output sink is resolved instead")
        void theDefaultSinkIsResolved() {
            AccountBalanceJob subject = new AccountBalanceJob(scaffolding(jobContracts()),
                    repository(seeded(List.of())), new AbsentBean<>());

            assertThat(subject.sysoutSink()).isNotNull();
        }

        @Test
        @DisplayName("the standard-output sink demands an explicit code page")
        void theDefaultSinkNeedsACharset() {
            assertThatNullPointerException()
                    .isThrownBy(() -> AccountBalanceJob.standardOutput(null));
        }

        @Test
        @DisplayName("a sink is a functional interface, so a list's add method is one")
        void aSinkCanBeALambda() {
            List<String> captured = new ArrayList<>();
            SysoutSink sink = captured::add;

            sink.write(AccountBalanceJob.START_OF_EXECUTION);

            assertThat(captured).containsExactly(AccountBalanceJob.START_OF_EXECUTION);
        }

        @Test
        @DisplayName("running without a sink is refused rather than silently discarding the output")
        void aRunWithoutASinkIsRefused() {
            AccountBalanceJob subject = job(seeded(List.of()), new CapturedSysout());

            assertThatNullPointerException()
                    .isThrownBy(() -> subject.readAndPrintAccountFile(null));
        }

        @Test
        @DisplayName("a sink passed to the run overrides the configured one for that run only")
        void aPerRunSinkIsHonoured() {
            CapturedSysout configured = new CapturedSysout();
            CapturedSysout perRun = new CapturedSysout();
            AccountBalanceJob subject = job(seeded(List.of()), configured);

            subject.readAndPrintAccountFile(perRun);

            assertThat(perRun.lines()).hasSize(2);
            assertThat(configured.lines()).isEmpty();
        }
    }

    // =============================================================================================
    // WORKING-STORAGE: the two 88-level conditions driven both ways, and every value transition.
    // =============================================================================================

    @Nested
    @DisplayName("WORKING-STORAGE and its two 88-level conditions")
    class WorkingStorageTransitions {

        @Test
        @DisplayName("the flag starts at its declared VALUE 'N'")
        void theDeclaredInitialValue() {
            WorkingStorage storage = new WorkingStorage();

            assertThat(storage.endOfFileFlag()).isEqualTo(AccountBalanceJob.END_OF_FILE_NO);
            assertThat(storage.endOfFileIsNo()).isTrue();
            assertThat(storage.endOfFileIsYes()).isFalse();
        }

        @Test
        @DisplayName("88 APPL-AOK VALUE 0 is true at zero and false at every other value")
        void applAokBothWays() {
            WorkingStorage storage = new WorkingStorage();

            storage.moveToApplResult(AccountBalanceJob.APPL_AOK);
            assertThat(storage.applAok()).isTrue();

            storage.moveToApplResult(AccountBalanceJob.APPL_RESULT_FATAL);
            assertThat(storage.applAok()).isFalse();
        }

        @Test
        @DisplayName("88 APPL-EOF VALUE 16 is true at sixteen and false at every other value")
        void applEofBothWays() {
            WorkingStorage storage = new WorkingStorage();

            storage.moveToApplResult(AccountBalanceJob.APPL_EOF);
            assertThat(storage.applEof()).isTrue();
            assertThat(storage.applAok()).isFalse();

            storage.moveToApplResult(AccountBalanceJob.APPL_RESULT_FATAL);
            assertThat(storage.applEof()).isFalse();
        }

        @ParameterizedTest(name = "MOVE {0} TO APPL-RESULT")
        @ValueSource(ints = {0, 8, 12, 16})
        @DisplayName("MOVE places the literal in the register")
        void moveToApplResult(int value) {
            WorkingStorage storage = new WorkingStorage();

            storage.moveToApplResult(value);

            assertThat(storage.applResult()).isEqualTo(value);
        }

        @Test
        @DisplayName("ADD 8 TO ZERO GIVING replaces the register rather than accumulating into it")
        void addToZeroGivingReplaces() {
            WorkingStorage storage = new WorkingStorage();

            storage.moveToApplResult(AccountBalanceJob.APPL_EOF);
            storage.addToZeroGivingApplResult(AccountBalanceJob.APPL_RESULT_ASSUMED_FAILURE);

            assertThat(storage.applResult()).isEqualTo(8);
        }

        @Test
        @DisplayName("ADD 12 TO ZERO GIVING likewise yields exactly twelve")
        void addTwelveToZeroGiving() {
            WorkingStorage storage = new WorkingStorage();

            storage.addToZeroGivingApplResult(AccountBalanceJob.APPL_RESULT_ASSUMED_FAILURE);
            storage.addToZeroGivingApplResult(AccountBalanceJob.APPL_RESULT_FATAL);

            assertThat(storage.applResult()).isEqualTo(12);
            assertThat(storage.applAok()).isFalse();
        }

        @Test
        @DisplayName("SUBTRACT APPL-RESULT FROM APPL-RESULT clears the register to zero")
        void subtractFromSelf() {
            WorkingStorage storage = new WorkingStorage();

            storage.addToZeroGivingApplResult(AccountBalanceJob.APPL_RESULT_ASSUMED_FAILURE);
            storage.subtractApplResultFromApplResult();

            assertThat(storage.applResult()).isZero();
            assertThat(storage.applAok()).isTrue();
        }

        @Test
        @DisplayName("MOVE 'Y' TO END-OF-FILE flips the loop's terminating test")
        void moveEndOfFileYes() {
            WorkingStorage storage = new WorkingStorage();

            storage.moveEndOfFile(AccountBalanceJob.END_OF_FILE_YES);

            assertThat(storage.endOfFileIsYes()).isTrue();
            assertThat(storage.endOfFileIsNo()).isFalse();
            assertThat(storage.endOfFileFlag()).isEqualTo("Y");
        }

        @Test
        @DisplayName("the two flag tests are independent: a third value satisfies neither")
        void aThirdValueSatisfiesNeitherTest() {
            WorkingStorage storage = new WorkingStorage();

            storage.moveEndOfFile("?");

            assertThat(storage.endOfFileIsYes()).isFalse();
            assertThat(storage.endOfFileIsNo()).isFalse();
        }

        @Test
        @DisplayName("PIC X(01) holds exactly one character, so a wider or narrower move is refused")
        void theFlagIsOneCharacter() {
            WorkingStorage storage = new WorkingStorage();

            assertThatNullPointerException().isThrownBy(() -> storage.moveEndOfFile(null));
            assertThatIllegalArgumentException().isThrownBy(() -> storage.moveEndOfFile("YES"));
            assertThatIllegalArgumentException().isThrownBy(() -> storage.moveEndOfFile(""));
        }
    }

    // =============================================================================================
    // The mainline guards - app/cbl/CBACT01C.cbl:L75 and L77.
    // =============================================================================================

    @Nested
    @DisplayName("The mainline loop's guards")
    class MainlineGuards {

        @Test
        @DisplayName("a flag that is neither 'Y' nor 'N' fails the guard: nothing is read and nothing "
                + "displayed")
        void theFirstGuardsFalseArm() {
            CapturedSysout sysout = new CapturedSysout();
            JdbcTemplate template = seeded(fixtureRows());
            AccountBalanceJob subject = job(template, sysout);
            AccountRepository accounts = repository(template);
            WorkingStorage storage = new WorkingStorage();
            storage.moveEndOfFile("?");

            try (AccountFile acctFile = accounts.open(OpenMode.INPUT)) {
                int displayed = subject.acctFileDisplayIteration(sysout, storage, acctFile);

                assertThat(displayed).isZero();
                assertThat(sysout.lines()).isEmpty();
            }
        }

        @Test
        @DisplayName("with the flag at 'N', one iteration reads one record and emits thirteen lines")
        void oneIterationEmitsOneBlock() {
            List<String> rows = fixtureRows();
            CapturedSysout sysout = new CapturedSysout();
            JdbcTemplate template = seeded(rows);
            AccountBalanceJob subject = job(template, sysout);
            AccountRepository accounts = repository(template);

            try (AccountFile acctFile = accounts.open(OpenMode.INPUT)) {
                int displayed =
                        subject.acctFileDisplayIteration(sysout, new WorkingStorage(), acctFile);

                assertThat(displayed).isEqualTo(1);
                assertThat(sysout.lines())
                        .containsExactlyElementsOf(
                                expectedBlock(rows.stream().sorted().findFirst().orElseThrow()));
            }
        }

        @Test
        @DisplayName("at end of file the second guard suppresses the raw record line")
        void theSecondGuardSuppressesTheRawLine() {
            CapturedSysout sysout = new CapturedSysout();
            JdbcTemplate template = seeded(List.of());
            AccountBalanceJob subject = job(template, sysout);
            AccountRepository accounts = repository(template);
            WorkingStorage storage = new WorkingStorage();

            try (AccountFile acctFile = accounts.open(OpenMode.INPUT)) {
                int displayed = subject.acctFileDisplayIteration(sysout, storage, acctFile);

                assertThat(displayed).isZero();
                assertThat(sysout.lines()).isEmpty();
                assertThat(storage.endOfFileIsYes()).isTrue();
                assertThat(storage.applEof()).isTrue();
            }
        }
    }
    // =============================================================================================
    // Bounded cancellation - the pass yields to a stop request between records.
    // =============================================================================================

    @Nested
    @DisplayName("Bounded cancellation - the pass yields to a stop request between records")
    class BoundedCancellation {

        @Test
        @DisplayName("no stop requested leaves the pass exactly as it was, line for line")
        void withoutAStopTheWholePassRuns() {
            CapturedSysout withSignal = new CapturedSysout();
            CapturedSysout withoutSignal = new CapturedSysout();

            job(seeded(fixtureRows()), withoutSignal).readAndPrintAccountFile(withoutSignal);
            job(seeded(fixtureRows()), withSignal)
                    .readAndPrintAccountFile(withSignal, StopSignal.of(stepExecution()));

            // The signal is consulted on every iteration and changes nothing while nothing is pending,
            // which is the property that makes the probe additive rather than a change to the pass.
            assertThat(withSignal.lines()).isEqualTo(withoutSignal.lines());
            assertThat(withSignal.lines()).hasSize(EXPECTED_LINES);
        }

        @Test
        @DisplayName("a stop requested before the pass starts ends it at the first record boundary")
        void aStopBeforeTheFirstRecordEndsThePassAtOnce() {
            StepExecution stepExecution = stepExecution();
            stepExecution.setTerminateOnly();
            CapturedSysout sysout = new CapturedSysout();
            AccountBalanceJob subject = job(seeded(fixtureRows()), sysout);

            assertThatExceptionOfType(StopRequestedException.class)
                    .isThrownBy(() -> subject.readAndPrintAccountFile(sysout,
                            StopSignal.of(stepExecution)))
                    .withMessageContaining(AccountBalanceJob.STEP_NAME)
                    .withMessageContaining("NO write is retried")
                    // The cause is what makes AbstractStep report the step as STOPPED rather than
                    // FAILED, so it is asserted rather than left as an implementation detail.
                    .withCauseInstanceOf(JobInterruptedException.class);

            // The OPEN happened and its banner was written; not one record line was.
            assertThat(sysout.lines()).containsExactly(AccountBalanceJob.START_OF_EXECUTION);
        }

        @Test
        @DisplayName("a stop part way through leaves whole record images and no closing banner")
        void aStopPartWayThroughLeavesTheRecordInFlightComplete() {
            StepExecution stepExecution = stepExecution();
            CapturedSysout sysout = new CapturedSysout();
            AccountBalanceJob subject = job(seeded(fixtureRows()), sysout);
            int stopAfter = 5;

            assertThatExceptionOfType(StopRequestedException.class).isThrownBy(() ->
                    subject.readAndPrintAccountFile(sysout,
                            signalStoppingAfter(stepExecution, stopAfter)));

            // CBACT01C displays each record as a BLOCK of AccountBalanceJob.LINES_PER_RECORD lines, so
            // a pass stopped between records must show a whole number of blocks. A count that was not an
            // exact multiple would mean the probe had landed mid-record, which is the one thing its
            // position rules out - and it is a stronger statement than any per-line assertion.
            assertThat(sysout.lines())
                    .hasSize(1 + stopAfter * AccountBalanceJob.LINES_PER_RECORD);
            assertThat((sysout.lines().size() - 1) % AccountBalanceJob.LINES_PER_RECORD).isZero();

            // The closing banner is NOT written, exactly as it is not written on an abend: a stopped
            // pass must not report the end of a normal execution.
            assertThat(sysout.lines()).doesNotContain(AccountBalanceJob.END_OF_EXECUTION);
        }

        @Test
        @DisplayName("a null stop signal is refused rather than silently treated as 'never stop'")
        void aNullStopSignalIsRefused() {
            AccountBalanceJob subject = job(seeded(List.of()), new CapturedSysout());

            assertThatNullPointerException()
                    .isThrownBy(() -> subject.readAndPrintAccountFile(new CapturedSysout(), null))
                    .withMessageContaining("StopSignal.RUNNING");
        }

        @Test
        @DisplayName("the tasklet takes its signal from the step execution the framework supplies")
        void theTaskletTakesItsSignalFromTheStepExecution() {
            StepExecution stepExecution = stepExecution();
            stepExecution.setTerminateOnly();
            CapturedSysout sysout = new CapturedSysout();
            Tasklet tasklet = job(seeded(fixtureRows()), sysout).accountFileDisplayTasklet();
            StepContribution contribution = new StepContribution(stepExecution);

            // Driven exactly as TaskletStep drives it, so this asserts the wiring and not just the
            // program: a tasklet that ignored the chunk context would run the whole pass here.
            assertThatExceptionOfType(StopRequestedException.class).isThrownBy(() ->
                    tasklet.execute(contribution, new ChunkContext(new StepContext(stepExecution))));

            assertThat(sysout.lines()).containsExactly(AccountBalanceJob.START_OF_EXECUTION);
            assertThat(contribution.getReadCount()).isZero();
        }

        /** @return a fresh step execution over this job's step, not asked to stop */
        private StepExecution stepExecution() {
            return new StepExecution(AccountBalanceJob.STEP_NAME, new JobExecution(9L));
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
        private StopSignal signalStoppingAfter(StepExecution stepExecution, int permitted) {
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
}
