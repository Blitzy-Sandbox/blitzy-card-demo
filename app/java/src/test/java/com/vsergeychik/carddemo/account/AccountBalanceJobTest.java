package com.vsergeychik.carddemo.account;

import com.vsergeychik.carddemo.CardDemoApplication;
import com.vsergeychik.carddemo.account.AccountBalanceJob.SysoutSink;
import com.vsergeychik.carddemo.account.AccountBalanceJob.WorkingStorage;
import com.vsergeychik.carddemo.account.AccountRepository.AccountFile;
import com.vsergeychik.carddemo.account.AccountRepository.OpenMode;
import com.vsergeychik.carddemo.account.AccountRepository.ReadResult;
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
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;
import org.mockito.stubbing.OngoingStubbing;
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
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.URISyntaxException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Unit tests for {@link AccountBalanceJob}, the translation of {@code CBACT01C}.
 *
 * <h2>The name-divergence register (AAP rule R1) - read this before writing an assertion</h2>
 *
 * <p>The class under test is called <strong>{@code AccountBalanceJob}</strong> because the migration
 * prompt names it that. The source it translates says something else entirely:
 * {@code app/cbl/CBACT01C.cbl:L5} reads {@code * Function    : Read and print account data file.}
 *
 * <p><strong>{@code CBACT01C} performs no balance work of any kind.</strong> It contains no arithmetic
 * on any balance field, no {@code REWRITE} and no {@code WRITE}; its only three arithmetic statements
 * are {@code ADD 8 TO ZERO GIVING APPL-RESULT} ({@code L152}),
 * {@code SUBTRACT APPL-RESULT FROM APPL-RESULT} ({@code L155}) and
 * {@code ADD 12 TO ZERO GIVING APPL-RESULT} ({@code L157}), all of which manipulate a status register
 * and touch no money whatsoever. Names come from the prompt; behaviour comes from the source. <em>No
 * assertion in this class may be derived from the class name.</em> A test expecting a balance to change
 * here would be asserting something the COBOL does not do, and would pass only by accident.
 *
 * <h2>What is being asserted, and why it is asserted this way</h2>
 *
 * <p>This program writes no records. Its {@code SYSOUT} <em>is</em> its observable output, so every
 * assertion below is about the exact sequence of displayed lines: how many there are, what each one
 * contains, and in what order. That is why the job's sink is injected rather than captured from a
 * stream - the sequence is collected into a list and compared element by element.
 *
 * <h2>Provenance of every expectation (practice B12)</h2>
 *
 * <p>No COBOL execution baseline exists for this estate - {@code AAP §0.7.6} records eight independently
 * verified blockers, and the substitute is a static derivation (risk R-A). So no expected line below was
 * captured from a run: each was read out of {@code app/cbl/CBACT01C.cbl} and cross-checked against
 * {@code app/cpy/CVACT01Y.cpy}, {@code app/jcl/READACCT.jcl} and {@code app/data/ASCII/acctdata.txt}.
 * Every expectation therefore carries the source line it came from, and the three facts most easily got
 * wrong were re-derived by measuring the source rather than by reading it: the eleven label literals at
 * {@code L119-L129} are each exactly {@value AccountBalanceJob#LABEL_WIDTH} characters, the separator at
 * {@code L130} is exactly {@value AccountBalanceJob#SEPARATOR_WIDTH} hyphens, and all fifty fixture rows
 * are exactly 300 bytes.
 *
 * <h2>The three preserved defects (practice B5) - asserted, never corrected</h2>
 *
 * <ol>
 *   <li><strong>{@code ACCT-ADDR-ZIP} is never displayed.</strong> {@code app/cpy/CVACT01Y.cpy:L15}
 *       declares it at offset 102, immediately before {@code ACCT-GROUP-ID}, and
 *       {@code 1100-DISPLAY-ACCT-RECORD} simply skips it - the name occurs zero times in the whole
 *       program and once in the copybook. Its <em>absence</em> is asserted; the missing line is not
 *       supplied. See {@code Literals.theZipCodeIsNotDisplayed} and
 *       {@code PreservedFixtureOddities}.</li>
 *   <li><strong>Every record is rendered twice, in two different shapes.</strong>
 *       {@code PERFORM 1100-DISPLAY-ACCT-RECORD} sits inside the read paragraph at {@code L96} and the
 *       mainline's raw {@code DISPLAY ACCOUNT-RECORD} sits at {@code L78}, so a record produces eleven
 *       labelled lines, a separator and its whole 300-byte image -
 *       {@value AccountBalanceJob#LINES_PER_RECORD} lines. Neither rendering is redundant and neither is
 *       removed. Note how differently the three sibling readers behave:
 *       {@code CBACT02C} emits <em>one</em> line per record because its inner display is commented out,
 *       and {@code CBACT03C} emits <em>two identical</em> lines. Three programs, three SYSOUT shapes -
 *       no shared helper may homogenise them.</li>
 *   <li><strong>The close paragraph reaches zero the long way.</strong> {@code L152} sets the register
 *       with {@code ADD 8 TO ZERO GIVING} rather than a {@code MOVE}, and {@code L155} clears it with
 *       {@code SUBTRACT APPL-RESULT FROM APPL-RESULT} rather than moving a literal. The spelling is
 *       transcribed rather than normalised, and {@code WorkingStorageTransitions} asserts the whole
 *       8 -&gt; 0-or-12 ladder.</li>
 * </ol>
 *
 * <p>One further oddity, in the wording rather than the logic: the open paragraph says
 * {@code 'ERROR OPENING ACCTFILE'} while the read and close paragraphs say {@code ACCOUNT FILE}. The
 * inconsistency is part of the observable output and is asserted as-is.
 *
 * <h2>Gates this class owns</h2>
 *
 * <p>G35 (the abend carries the return code the guard chain left), G47 (every file-status outcome
 * exercised per call site), G50 (both states of both {@code 88}-levels), G51 (every branch reachable with
 * no {@code JobLauncher} in the path), G52 (no wildcard imports), G53 (no mutable static state), and it
 * contributes to G49, the &ge;90% branch ratio for {@code com.vsergeychik.carddemo.account}. It also
 * carries G19 (the 300-byte record), G21 ({@code FILLER} emitted as spaces), G28 (the arithmetic sites)
 * and G46 (no dataset name in Java source).
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
    // The scripted-status seam (gate G47).
    //
    // The seeded-relation helpers above drive the arms a real backend can reach, which is the right
    // way to exercise the common paths but reaches only ONE failing status: the repository's own
    // PERMANENT_ERROR_STATUS. The COBOL guard chains, though, branch on the status VALUE, and gate G47
    // requires each status to be exercised at each of the three call sites. '22' and '23' cannot be
    // provoked out of a sequential browse over a healthy relation at all, so the handle itself is
    // scripted here: the repository is mocked, its open yields a mocked AccountFile, and that file
    // reports exactly the OPEN status, the read sequence and the CLOSE status the case names.
    //
    // Nothing about the program is stubbed - only the file it reads. Every branch below is the
    // production control flow, reached with no JobLauncher, no application context and no HTTP layer
    // in the path (gate G51).
    // =============================================================================================

    /**
     * A job whose account master reports exactly the statuses a case names.
     *
     * <p>The read script is consumed in order and then end-of-file is reported for ever after, which is
     * what a real browse does: {@code 1000-ACCTFILE-GET-NEXT} is performed until the flag turns, and a
     * script that ran out without an end-of-file would loop.
     *
     * @param openStatus  the two-character status {@code OPEN INPUT} reports ({@code L135})
     * @param readScript  the outcomes successive {@code READ}s report ({@code L93}), in order
     * @param closeStatus the two-character status {@code CLOSE} reports ({@code L153})
     * @param sysout      where displayed lines are captured
     * @return the job, over a mocked master
     */
    private static AccountBalanceJob jobOverScriptedStatuses(String openStatus,
            List<ReadResult> readScript, String closeStatus, SysoutSink sysout) {
        return new AccountBalanceJob(scaffolding(jobContracts()),
                scriptedRepository(openStatus, readScript, closeStatus), new PresentBean<>(sysout));
    }

    /**
     * A mocked repository whose one opened handle reports the given statuses.
     *
     * <p>{@code datasetCharset()} is stubbed because the job's constructor reads it to build its codec;
     * a mock returning {@code null} there would fail construction for a reason that has nothing to do
     * with the case under test.
     *
     * @param openStatus  the status {@code OPEN INPUT} reports
     * @param readScript  the outcomes successive reads report, in order
     * @param closeStatus the status {@code CLOSE} reports
     * @return the mocked repository
     */
    private static AccountRepository scriptedRepository(String openStatus, List<ReadResult> readScript,
            String closeStatus) {
        AccountFile handle = Mockito.mock(AccountFile.class);
        Mockito.when(handle.openStatus()).thenReturn(openStatus);
        Mockito.when(handle.closeFile()).thenReturn(closeStatus);
        OngoingStubbing<ReadResult> reads = Mockito.when(handle.readNext());
        for (ReadResult scripted : readScript) {
            reads = reads.thenReturn(scripted);
        }
        reads.thenReturn(ReadResult.endOfFile());

        AccountRepository accounts = Mockito.mock(AccountRepository.class);
        Mockito.when(accounts.datasetCharset()).thenReturn(ASCII);
        Mockito.when(accounts.open(OpenMode.INPUT)).thenReturn(handle);
        return accounts;
    }

    /**
     * The first fixture record, decoded - the record a scripted successful read hands back.
     *
     * @return the record decoded from the lowest-keyed fixture row
     */
    private static AccountRecord firstFixtureRecord() {
        return AccountRecord.decode(firstFixtureRow(), ASCII);
    }

    /**
     * The lowest-keyed fixture row, as stored.
     *
     * <p>Sorted rather than taken positionally: {@code ACCESS MODE IS SEQUENTIAL} over a KSDS returns
     * records in key order, so the first record a browse yields is the one with the lowest
     * {@code ACCT-ID}, whatever order the rows happen to sit in the file.
     *
     * @return one 300-byte record image
     */
    private static String firstFixtureRow() {
        return fixtureRows().stream().sorted().findFirst().orElseThrow();
    }

    /**
     * This class's own file location for {@link AccountBalanceJob}'s source, for the gate-G46 scan.
     *
     * <p>Resolved from the compiled class's own code source rather than from the working directory, so
     * the scan does not depend on where the JVM was started. The module layout is fixed -
     * {@code target/classes} sits two levels below {@code app/java}, whose sources are under
     * {@code src/main/java} - so the walk up is exact rather than a search.
     *
     * @return the path to {@code AccountBalanceJob.java}
     * @throws IllegalStateException if the source cannot be located, since the scan is an assertion and
     *                               must never be skipped
     */
    private static Path jobSourceFile() {
        Path relative = Path.of("src", "main", "java", "com", "vsergeychik", "carddemo", "account",
                "AccountBalanceJob.java");
        Path fromCodeSource = moduleRoot().resolve(relative);
        if (Files.isRegularFile(fromCodeSource)) {
            return fromCodeSource;
        }
        Path fromWorkingDirectory = Path.of("").toAbsolutePath().resolve(relative);
        if (Files.isRegularFile(fromWorkingDirectory)) {
            return fromWorkingDirectory;
        }
        throw new IllegalStateException("AccountBalanceJob.java was not found at " + fromCodeSource
                + " nor at " + fromWorkingDirectory + ". The gate-G46 scan reads the job's own source, "
                + "so it cannot be skipped when the file cannot be found.");
    }

    /**
     * The Maven module root, derived from where this test's classes were loaded from.
     *
     * @return the directory holding {@code pom.xml} and {@code src}
     * @throws IllegalStateException if the code source cannot be resolved to a directory
     */
    private static Path moduleRoot() {
        try {
            Path testClasses = Path.of(AccountBalanceJobTest.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
            // target/test-classes -> target -> the module root.
            return testClasses.getParent().getParent();
        } catch (URISyntaxException | NullPointerException unresolvable) {
            throw new IllegalStateException("The test class's code source did not resolve to a "
                    + "directory, so the module root could not be derived: "
                    + unresolvable.getClass().getName());
        }
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
        @DisplayName("all eleven labels equal the source literals byte for byte, spaces included")
        void everyLabelIsItsSourceLiteral() {
            // Transcribed verbatim from app/cbl/CBACT01C.cbl:L119-L129, one entry per DISPLAY, and
            // deliberately written out as literals rather than derived. The job DERIVES its labels from
            // the copybook field names, which removes the miscounted-space defect at the cost of making
            // a derivation bug invisible: a wrong pad rule and a wrong field name would agree with each
            // other. These are the source's own bytes, so nothing here can agree with a bug.
            //
            // Every string below is exactly LABEL_WIDTH characters. The count was taken by measuring the
            // source literals, not by counting spaces in a review (practice B12).
            assertThat(AccountBalanceJob.FIELD_LABELS).containsExactly(
                    "ACCT-ID                 :",   // L119
                    "ACCT-ACTIVE-STATUS      :",   // L120
                    "ACCT-CURR-BAL           :",   // L121
                    "ACCT-CREDIT-LIMIT       :",   // L122
                    "ACCT-CASH-CREDIT-LIMIT  :",   // L123
                    "ACCT-OPEN-DATE          :",   // L124
                    "ACCT-EXPIRAION-DATE     :",   // L125 - the copybook's misspelling, preserved
                    "ACCT-REISSUE-DATE       :",   // L126
                    "ACCT-CURR-CYC-CREDIT    :",   // L127
                    "ACCT-CURR-CYC-DEBIT     :",   // L128
                    "ACCT-GROUP-ID           :");  // L129

            // And the named constants are those same literals, so a caller reaching for one by name gets
            // the source's bytes too.
            assertThat(AccountBalanceJob.LABEL_ACCT_ID).isEqualTo("ACCT-ID                 :");
            assertThat(AccountBalanceJob.LABEL_ACCT_ACTIVE_STATUS)
                    .isEqualTo("ACCT-ACTIVE-STATUS      :");
            assertThat(AccountBalanceJob.LABEL_ACCT_CURR_BAL).isEqualTo("ACCT-CURR-BAL           :");
            assertThat(AccountBalanceJob.LABEL_ACCT_CREDIT_LIMIT)
                    .isEqualTo("ACCT-CREDIT-LIMIT       :");
            assertThat(AccountBalanceJob.LABEL_ACCT_CASH_CREDIT_LIMIT)
                    .isEqualTo("ACCT-CASH-CREDIT-LIMIT  :");
            assertThat(AccountBalanceJob.LABEL_ACCT_OPEN_DATE).isEqualTo("ACCT-OPEN-DATE          :");
            assertThat(AccountBalanceJob.LABEL_ACCT_EXPIRAION_DATE)
                    .isEqualTo("ACCT-EXPIRAION-DATE     :");
            assertThat(AccountBalanceJob.LABEL_ACCT_REISSUE_DATE)
                    .isEqualTo("ACCT-REISSUE-DATE       :");
            assertThat(AccountBalanceJob.LABEL_ACCT_CURR_CYC_CREDIT)
                    .isEqualTo("ACCT-CURR-CYC-CREDIT    :");
            assertThat(AccountBalanceJob.LABEL_ACCT_CURR_CYC_DEBIT)
                    .isEqualTo("ACCT-CURR-CYC-DEBIT     :");
            assertThat(AccountBalanceJob.LABEL_ACCT_GROUP_ID).isEqualTo("ACCT-GROUP-ID           :");
        }

        @Test
        @DisplayName("the separator literal is the source's own forty-nine hyphens, and nothing else")
        void theSeparatorIsItsSourceLiteral() {
            // app/cbl/CBACT01C.cbl:L130, transcribed. Counted by measurement: 49.
            assertThat(AccountBalanceJob.RECORD_SEPARATOR)
                    .isEqualTo("-------------------------------------------------")
                    .hasSize(AccountBalanceJob.SEPARATOR_WIDTH)
                    .hasSize(49)
                    .matches("-{49}");
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
            String first = firstFixtureRow();
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
            // Measured in BYTES, in the dataset's own named code page, because gate G19 is a byte
            // width and not a character count. The charset is never the platform default (practice B8).
            assertThat(raw.getBytes(ASCII))
                    .as("RECLN 300, as app/cpy/CVACT01Y.cpy:L2 declares it (gate G19)")
                    .hasSize(RECORD_LENGTH);
            assertThat(raw.substring(RECORD_LENGTH - FILLER_LENGTH))
                    .as("FILLER X(178), emitted as spaces (gates G19 and G21)")
                    .isEqualTo(" ".repeat(FILLER_LENGTH));
        }

        @Test
        @DisplayName("each record really is displayed twice, in two different shapes and in that order")
        void theRecordIsDisplayedTwice() {
            String first = firstFixtureRow();
            CapturedSysout sysout = new CapturedSysout();
            job(seeded(fixtureRows()), sysout).readAndPrintAccountFile();

            AccountRecord account = AccountRecord.decode(first, ASCII);
            String labelledLine = AccountBalanceJob.LABEL_ACCT_ID + account.rawAcctId();
            // Shape one: the labelled line from 1100-DISPLAY-ACCT-RECORD.
            assertThat(sysout.lines()).contains(labelledLine);
            // Shape two: the raw image from the mainline. The account id appears in both.
            assertThat(sysout.lines()).contains(first);

            // And the ORDER is not incidental: 1100-DISPLAY-ACCT-RECORD is performed from INSIDE the
            // read paragraph at L96, whereas the raw DISPLAY ACCOUNT-RECORD happens afterwards in the
            // mainline at L78. So all twelve of the decomposed lines precede the raw image, and the
            // separator at L130 sits immediately between them.
            int labelled = sysout.lines().indexOf(labelledLine);
            int separator = sysout.lines().indexOf(AccountBalanceJob.RECORD_SEPARATOR);
            int rawImage = sysout.lines().indexOf(first);
            assertThat(labelled).isLessThan(separator);
            assertThat(separator).isEqualTo(rawImage - 1);
            assertThat(rawImage - labelled)
                    .as("eleven labelled lines then the separator, then the image")
                    .isEqualTo(AccountBalanceJob.FIELD_LABELS.size() + 1);
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
            String first = firstFixtureRow();
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

            AbendException abend = assertThrows(AbendException.class,
                    subject::readAndPrintAccountFile);

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

            AbendException abend = assertThrows(AbendException.class,
                    subject::readAndPrintAccountFile);

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

            AbendException abend = assertThrows(AbendException.class,
                    subject::readAndPrintAccountFile);

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

    // =============================================================================================
    // Every file-status outcome, at every one of the three call sites (gate G47).
    //
    // The COBOL classifies the status FIRST and branches on the classification SECOND, and the two
    // chains differ per paragraph:
    //
    //   OPEN  (L136-L140)  '00' -> MOVE 0    everything else -> MOVE 12
    //   READ  (L94-L102)   '00' -> MOVE 0    '10' -> MOVE 16    everything else -> MOVE 12
    //   CLOSE (L154-L158)  '00' -> SUBTRACT  everything else -> ADD 12 TO ZERO GIVING
    //
    // So '22', '23', a plain unrecognised status and a non-numeric one all reach the SAME arm - and
    // that is precisely why each has to be driven: the arm is shared but the rendered
    // 'FILE STATUS IS: NNNN' line is not, and a renderer applied to the wrong operand would still
    // produce a plausible-looking abend. Each case therefore pins the status IMAGE as well as the arm.
    // =============================================================================================

    @Nested
    @DisplayName("Every file status, at every call site (G47)")
    class StatusOutcomesPerCallSite {

        /**
         * {@code 0000-ACCTFILE-OPEN}: any status other than {@code '00'} takes the fatal arm, displays
         * {@code 'ERROR OPENING ACCTFILE'} and the status, announces the abend and raises return code 12.
         *
         * @param status        the status {@code OPEN INPUT} reports
         * @param expectedImage the four-character {@code IO-STATUS-04} image it must render to
         */
        @ParameterizedTest(name = "OPEN reports ''{0}'' and renders {1}")
        @CsvSource({
            "22, 0022",
            "23, 0023",
            "77, 0077",
            "04, 0004",
            "AB, A066",
        })
        @DisplayName("a failing OPEN abends with return code 12, whatever the status")
        void openFailsForEveryNonZeroStatus(String status, String expectedImage) {
            CapturedSysout sysout = new CapturedSysout();
            AccountBalanceJob subject = jobOverScriptedStatuses(status, List.of(), FileStatus.OK,
                    sysout);

            AbendException abend = assertThrows(AbendException.class,
                    subject::readAndPrintAccountFile);

            // L144, L146, L170 - in that order, and nothing else. The pass never reaches the loop, so
            // no record line and no closing banner appear.
            assertThat(sysout.lines()).containsExactly(
                    AccountBalanceJob.START_OF_EXECUTION,
                    AccountBalanceJob.ERROR_OPENING_ACCTFILE,
                    FileStatus.DISPLAY_PREFIX + expectedImage,
                    AbendException.ABEND_DISPLAY_TEXT);
            // MOVE 12 TO APPL-RESULT at L139 is what CEE3ABD carries away.
            assertThat(abend.getReturnCode()).isEqualTo(AccountBalanceJob.APPL_RESULT_FATAL);
            assertThat(abend.getAbendCode()).hasValue(AbendException.STANDARD_ABEND_CODE);
            assertThat(abend.getTiming()).hasValue(AbendException.STANDARD_TIMING);
        }

        /**
         * {@code 1000-ACCTFILE-GET-NEXT}: a status that is neither {@code '00'} nor {@code '10'} takes
         * the fatal arm. The wording differs from the open paragraph's - {@code ACCOUNT FILE} rather than
         * {@code ACCTFILE} - and that inconsistency is part of the output.
         *
         * @param status        the status the read reports
         * @param expectedImage the image it must render to
         */
        @ParameterizedTest(name = "READ reports ''{0}'' and renders {1}")
        @CsvSource({
            "22, 0022",
            "23, 0023",
            "77, 0077",
            "04, 0004",
            "AB, A066",
        })
        @DisplayName("a failing READ abends with return code 12, whatever the status")
        void readFailsForEveryUnexpectedStatus(String status, String expectedImage) {
            CapturedSysout sysout = new CapturedSysout();
            AccountBalanceJob subject = jobOverScriptedStatuses(FileStatus.OK,
                    List.of(ReadResult.of(status)), FileStatus.OK, sysout);

            AbendException abend = assertThrows(AbendException.class,
                    subject::readAndPrintAccountFile);

            // L110, L112, L170. The record is not displayed in either of its two shapes, because the
            // fatal arm is reached before L96 and instead of L78.
            assertThat(sysout.lines()).containsExactly(
                    AccountBalanceJob.START_OF_EXECUTION,
                    AccountBalanceJob.ERROR_READING_ACCOUNT_FILE,
                    FileStatus.DISPLAY_PREFIX + expectedImage,
                    AbendException.ABEND_DISPLAY_TEXT);
            assertThat(abend.getReturnCode()).isEqualTo(AccountBalanceJob.APPL_RESULT_FATAL);
            assertThat(abend.getReason().orElseThrow())
                    .contains(AccountBalanceJob.ERROR_READING_ACCOUNT_FILE);
        }

        /**
         * {@code 9000-ACCTFILE-CLOSE}: any status other than {@code '00'} reaches
         * {@code ADD 12 TO ZERO GIVING APPL-RESULT} at {@code L157} and then the fatal arm. The whole
         * pass has already run at that point, so the record's thirteen lines are present and only the
         * closing banner is missing.
         *
         * @param status        the status {@code CLOSE} reports
         * @param expectedImage the image it must render to
         */
        @ParameterizedTest(name = "CLOSE reports ''{0}'' and renders {1}")
        @CsvSource({
            "22, 0022",
            "23, 0023",
            "77, 0077",
            "04, 0004",
            "AB, A066",
        })
        @DisplayName("a failing CLOSE abends with return code 12 after the whole pass has run")
        void closeFailsForEveryNonZeroStatus(String status, String expectedImage) {
            CapturedSysout sysout = new CapturedSysout();
            AccountBalanceJob subject = jobOverScriptedStatuses(FileStatus.OK,
                    List.of(ReadResult.found(firstFixtureRecord())), status, sysout);

            AbendException abend = assertThrows(AbendException.class,
                    subject::readAndPrintAccountFile);

            List<String> expected = new ArrayList<>();
            expected.add(AccountBalanceJob.START_OF_EXECUTION);
            expected.addAll(expectedBlock(firstFixtureRow()));
            expected.add(AccountBalanceJob.ERROR_CLOSING_ACCOUNT_FILE);              // L162
            expected.add(FileStatus.DISPLAY_PREFIX + expectedImage);                 // L164
            expected.add(AbendException.ABEND_DISPLAY_TEXT);                         // L170
            assertThat(sysout.lines()).containsExactlyElementsOf(expected);
            assertThat(sysout.lines()).doesNotContain(AccountBalanceJob.END_OF_EXECUTION);
            assertThat(abend.getReturnCode()).isEqualTo(AccountBalanceJob.APPL_RESULT_FATAL);
        }

        @Test
        @DisplayName("'00' at all three sites and '10' to end the browse is the whole clean run")
        void theAllClearPath() {
            CapturedSysout sysout = new CapturedSysout();
            AccountBalanceJob subject = jobOverScriptedStatuses(FileStatus.OK,
                    List.of(ReadResult.found(firstFixtureRecord())), FileStatus.OK, sysout);

            int recordsDisplayed = subject.readAndPrintAccountFile();

            assertThat(recordsDisplayed).isOne();
            List<String> expected = new ArrayList<>();
            expected.add(AccountBalanceJob.START_OF_EXECUTION);                      // L71
            expected.addAll(expectedBlock(firstFixtureRow()));                       // L96 then L78
            expected.add(AccountBalanceJob.END_OF_EXECUTION);                        // L85
            assertThat(sysout.lines()).containsExactlyElementsOf(expected);
        }

        @Test
        @DisplayName("'10' on the first read ends the loop cleanly: both banners, no abend, nothing else")
        void endOfFileOnTheFirstRead() {
            CapturedSysout sysout = new CapturedSysout();
            AccountBalanceJob subject = jobOverScriptedStatuses(FileStatus.OK,
                    List.of(ReadResult.endOfFile()), FileStatus.OK, sysout);

            // MOVE 16 TO APPL-RESULT (L99) satisfies 88 APPL-EOF (L63), so L108 moves 'Y' to the flag
            // and the loop at L74 ends. No error literal, no status line, no abend banner.
            assertThat(subject.readAndPrintAccountFile()).isZero();
            assertThat(sysout.lines()).containsExactly(
                    AccountBalanceJob.START_OF_EXECUTION,
                    AccountBalanceJob.END_OF_EXECUTION);
        }

        @Test
        @DisplayName("the '10' arm leaves APPL-RESULT at 16 - the 88 APPL-EOF value, not 12")
        void theEndOfFileArmLeavesSixteenInTheRegister() {
            CapturedSysout sysout = new CapturedSysout();
            WorkingStorage storage = new WorkingStorage();
            AccountRepository accounts = scriptedRepository(FileStatus.OK,
                    List.of(ReadResult.endOfFile()), FileStatus.OK);
            AccountBalanceJob subject = new AccountBalanceJob(scaffolding(jobContracts()), accounts,
                    new PresentBean<>(sysout));

            try (AccountFile acctFile = accounts.open(OpenMode.INPUT)) {
                int displayed = subject.acctFileDisplayIteration(sysout, storage, acctFile);

                assertThat(displayed).isZero();
                assertThat(storage.applResult()).isEqualTo(AccountBalanceJob.APPL_EOF).isEqualTo(16);
                assertThat(storage.applEof()).isTrue();
                assertThat(storage.applAok()).isFalse();
                assertThat(storage.endOfFileFlag()).isEqualTo(AccountBalanceJob.END_OF_FILE_YES);
            }
            // The end-of-file read displays nothing at all: no labelled line, no separator, no image.
            assertThat(sysout.lines()).isEmpty();
        }

        @Test
        @DisplayName("a successful read leaves APPL-RESULT at 0 and the flag at 'N'")
        void theSuccessfulArmLeavesZeroInTheRegister() {
            CapturedSysout sysout = new CapturedSysout();
            WorkingStorage storage = new WorkingStorage();
            AccountRepository accounts = scriptedRepository(FileStatus.OK,
                    List.of(ReadResult.found(firstFixtureRecord())), FileStatus.OK);
            AccountBalanceJob subject = new AccountBalanceJob(scaffolding(jobContracts()), accounts,
                    new PresentBean<>(sysout));

            try (AccountFile acctFile = accounts.open(OpenMode.INPUT)) {
                assertThat(subject.acctFileDisplayIteration(sysout, storage, acctFile)).isOne();
            }

            // MOVE 0 TO APPL-RESULT at L95 satisfies 88 APPL-AOK at L62, so the guard chain at L104
            // takes CONTINUE and the flag is left alone.
            assertThat(storage.applResult()).isEqualTo(AccountBalanceJob.APPL_AOK).isZero();
            assertThat(storage.applAok()).isTrue();
            assertThat(storage.endOfFileFlag()).isEqualTo(AccountBalanceJob.END_OF_FILE_NO);
            assertThat(sysout.lines()).containsExactlyElementsOf(expectedBlock(firstFixtureRow()));
        }

        @Test
        @DisplayName("a healthy pass opens INPUT, reads until end of file and closes exactly once")
        void theCallSequenceMatchesTheParagraphs() {
            AccountFile handle = Mockito.mock(AccountFile.class);
            Mockito.when(handle.openStatus()).thenReturn(FileStatus.OK);
            Mockito.when(handle.closeFile()).thenReturn(FileStatus.OK);
            Mockito.when(handle.readNext())
                    .thenReturn(ReadResult.found(firstFixtureRecord()))
                    .thenReturn(ReadResult.endOfFile());
            AccountRepository accounts = Mockito.mock(AccountRepository.class);
            Mockito.when(accounts.datasetCharset()).thenReturn(ASCII);
            Mockito.when(accounts.open(OpenMode.INPUT)).thenReturn(handle);

            new AccountBalanceJob(scaffolding(jobContracts()), accounts,
                    new PresentBean<>(new CapturedSysout())).readAndPrintAccountFile();

            // OPEN INPUT (L135) once - never I-O, because this program writes nothing.
            Mockito.verify(accounts).open(OpenMode.INPUT);
            // IF ACCTFILE-STATUS = '00' (L136) reads the status the open reported, exactly once.
            Mockito.verify(handle, Mockito.times(1)).openStatus();
            // Two reads (L93): the record, then the one that finds nothing.
            Mockito.verify(handle, Mockito.times(2)).readNext();
            // CLOSE (L153) exactly once, at L83 - the release guard must not add a second.
            Mockito.verify(handle, Mockito.times(1)).closeFile();
            // Nothing else is touched: no readByKey, no readForUpdate and above all no rewrite. This
            // program is read-only, which is the whole of AAP rule R1's point about its name.
            Mockito.verifyNoMoreInteractions(handle);
        }
    }

    // =============================================================================================
    // 9910-DISPLAY-IO-STATUS, reached through the job - app/cbl/CBACT01C.cbl:L176-L189.
    //
    // Two arms, and both must be reached through the program rather than only through the renderer's
    // own unit test, because the thing being asserted here is that the JOB hands the renderer the
    // status the operation reported, unmodified.
    // =============================================================================================

    @Nested
    @DisplayName("Both arms of 9910-DISPLAY-IO-STATUS, as the job reaches them")
    class IoStatusRendering {

        @Test
        @DisplayName("a numeric status takes the ELSE arm: '23' renders as NNNN0023")
        void theNumericArm() {
            CapturedSysout sysout = new CapturedSysout();
            AccountBalanceJob subject = jobOverScriptedStatuses(FileStatus.NOT_FOUND, List.of(),
                    FileStatus.OK, sysout);

            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(subject::readAndPrintAccountFile);

            // MOVE '0000' TO IO-STATUS-04 then MOVE IO-STATUS TO IO-STATUS-04(3:2) - L185-L186.
            assertThat(sysout.lines()).contains("FILE STATUS IS: NNNN0023");
        }

        @Test
        @DisplayName("a '9x' status takes the IF arm: the feedback byte becomes three decimal digits")
        void theExtendedArmForANineStatus() {
            CapturedSysout sysout = new CapturedSysout();
            // '9' followed by the byte 0x0A: MOVE IO-STAT2 TO TWO-BYTES-RIGHT reinterprets that byte as
            // the unsigned integer 10, and the PIC 999 tail renders it as 010 - L179-L182.
            AccountBalanceJob subject = jobOverScriptedStatuses("9\n", List.of(), FileStatus.OK, sysout);

            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(subject::readAndPrintAccountFile);

            assertThat(sysout.lines()).contains("FILE STATUS IS: NNNN9010");
        }

        @Test
        @DisplayName("a non-numeric status takes the IF arm too, on the NOT NUMERIC half of the test")
        void theExtendedArmForANonNumericStatus() {
            CapturedSysout sysout = new CapturedSysout();
            // 'A' is kept verbatim as the first image character; 'B' is 0x42 = 66, rendered as 066.
            AccountBalanceJob subject = jobOverScriptedStatuses("AB", List.of(), FileStatus.OK, sysout);

            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(subject::readAndPrintAccountFile);

            assertThat(sysout.lines()).contains("FILE STATUS IS: NNNNA066");
        }

        @Test
        @DisplayName("the literal really does contain the four characters NNNN, and they are not "
                + "substituted")
        void theLiteralIsPreservedVerbatim() {
            CapturedSysout sysout = new CapturedSysout();
            AccountBalanceJob subject = jobOverScriptedStatuses(FileStatus.DUPLICATE, List.of(),
                    FileStatus.OK, sysout);

            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(subject::readAndPrintAccountFile);

            String statusLine = sysout.lines().stream()
                    .filter(line -> line.startsWith(FileStatus.DISPLAY_PREFIX))
                    .findFirst().orElseThrow();
            assertThat(FileStatus.DISPLAY_PREFIX).isEqualTo("FILE STATUS IS: NNNN");
            assertThat(statusLine)
                    .as("the prefix is emitted, then the four-character image - L183 and L187")
                    .isEqualTo("FILE STATUS IS: NNNN0022")
                    .hasSize(FileStatus.DISPLAY_PREFIX.length() + FileStatus.STATUS_IMAGE_LENGTH);
        }
    }

    // =============================================================================================
    // The fixture's own oddities, preserved rather than corrected (practice B5).
    // =============================================================================================

    @Nested
    @DisplayName("The fixture's preserved oddities")
    class PreservedFixtureOddities {

        @Test
        @DisplayName("every fixture row is exactly 300 bytes, as CVACT01Y declares (G19)")
        void everyRowIsTheDeclaredWidth() {
            List<String> rows = fixtureRows();

            assertThat(rows).hasSize(FIXTURE_RECORDS);
            assertThat(rows).allSatisfy(row -> assertThat(row).hasSize(RECORD_LENGTH));
        }

        @Test
        @DisplayName("ACCT-ADDR-ZIP holds A000000000 in all fifty rows - and is displayed in none")
        void theZipCodeIsPopulatedAndStillNotDisplayed() {
            List<String> rows = fixtureRows();

            // The field is not empty, so its absence from the output cannot be explained away as
            // "there was nothing to show": app/cpy/CVACT01Y.cpy:L15 declares it and the program's
            // display paragraph simply skips it.
            assertThat(rows).allSatisfy(row -> assertThat(row.substring(
                    AccountRecord.ACCT_ADDR_ZIP_OFFSET,
                    AccountRecord.ACCT_ADDR_ZIP_OFFSET + AccountRecord.ACCT_ADDR_ZIP_LENGTH))
                    .isEqualTo("A000000000"));

            CapturedSysout sysout = new CapturedSysout();
            job(seeded(rows), sysout).readAndPrintAccountFile();

            assertThat(sysout.lines())
                    .as("no labelled line, because 1100-DISPLAY-ACCT-RECORD has no statement for it")
                    .noneMatch(line -> line.startsWith(AccountRecord.ACCT_ADDR_ZIP_NAME));
            // It is nonetheless present in the raw image the mainline displays at L78, at its own
            // offset - which is the point: the byte stream is complete, only the labelled render is not.
            assertThat(sysout.lines()).contains(firstFixtureRow());
            assertThat(firstFixtureRecord().rawAcctAddrZip()).isEqualTo("A000000000");
        }

        @Test
        @DisplayName("ACCT-GROUP-ID is blank in all fifty rows, so its line renders ten spaces")
        void theGroupIdLineRendersTenSpaces() {
            List<String> rows = fixtureRows();
            String tenSpaces = " ".repeat(AccountRecord.ACCT_GROUP_ID_LENGTH);

            assertThat(rows).allSatisfy(row -> assertThat(row.substring(
                    AccountRecord.ACCT_GROUP_ID_OFFSET,
                    AccountRecord.ACCT_GROUP_ID_OFFSET + AccountRecord.ACCT_GROUP_ID_LENGTH))
                    .isEqualTo(tenSpaces));

            CapturedSysout sysout = new CapturedSysout();
            job(seeded(rows), sysout).readAndPrintAccountFile();

            List<String> groupIdLines = sysout.lines().stream()
                    .filter(line -> line.startsWith(AccountBalanceJob.LABEL_ACCT_GROUP_ID))
                    .toList();

            // L129, once per record, and every one of them is the label followed by ten spaces. The
            // fixture is not corrected to put a group id here - DISPLAY writes the field at its full
            // declared width, so trailing blanks are output.
            assertThat(groupIdLines).hasSize(FIXTURE_RECORDS);
            assertThat(groupIdLines).allSatisfy(line -> assertThat(line)
                    .isEqualTo(AccountBalanceJob.LABEL_ACCT_GROUP_ID + tenSpaces)
                    .hasSize(AccountBalanceJob.LABEL_WIDTH + AccountRecord.ACCT_GROUP_ID_LENGTH));
        }

        @Test
        @DisplayName("the classpath fixture is byte-identical to the reference tree's copy, unmodified")
        void theFixtureIsAFaithfulCopy() {
            // Practice B3: app/data/ASCII/acctdata.txt is read-only reference data, so the tests consume
            // the copy on the classpath. That copy has to be a copy - a drifted fixture would move the
            // expectations without failing anything.
            Path reference = moduleRoot().getParent().getParent()
                    .resolve(Path.of("app", "data", "ASCII", "acctdata.txt"));
            assertThat(reference).exists();

            List<String> referenceRows = readAsciiLines(reference);
            assertThat(fixtureRows())
                    .as("the classpath fixture must still equal " + reference)
                    .containsExactlyElementsOf(referenceRows);
        }

        /**
         * Reads a reference file as US-ASCII lines. The charset is named, never defaulted (practice B8).
         *
         * @param file the file to read
         * @return its lines, in order
         */
        private List<String> readAsciiLines(Path file) {
            try {
                return Files.readAllLines(file, ASCII);
            } catch (IOException unreadable) {
                throw new UncheckedIOException(unreadable);
            }
        }
    }

    // =============================================================================================
    // No mutable static state, and no dataset name in Java source (gates G53 and G46).
    // =============================================================================================

    @Nested
    @DisplayName("No mutable static state, and no dataset name in the source")
    class StatelessnessAndConfiguration {

        @Test
        @DisplayName("the job class declares no mutable static field: WORKING-STORAGE did not become one")
        void theJobHoldsNoMutableStaticState() {
            // Practice B9 and gate G53. A @Configuration class is a singleton, so a static - or even an
            // instance - field holding APPL-RESULT or END-OF-FILE would make one run's state visible to
            // another. The check is reflective because it has to hold for fields nobody thought to test.
            assertThat(mutableStaticFieldsOf(AccountBalanceJob.class))
                    .as("every static field of the job must be final")
                    .isEmpty();
            assertThat(mutableStaticFieldsOf(WorkingStorage.class))
                    .as("WORKING-STORAGE is per-run state; none of it may be static")
                    .isEmpty();
        }

        @Test
        @DisplayName("the two working-storage items are instance fields of WorkingStorage, not statics")
        void workingStorageIsPerRun() {
            List<String> instanceFields = Arrays.stream(
                            WorkingStorage.class.getDeclaredFields())
                    .filter(field -> !field.isSynthetic())
                    .filter(field -> !Modifier.isStatic(field.getModifiers()))
                    .map(Field::getName)
                    .sorted()
                    .toList();

            // APPL-RESULT (L61) and END-OF-FILE (L65) - exactly those two, and both per instance.
            assertThat(instanceFields).containsExactly("applResult", "endOfFile");
        }

        @Test
        @DisplayName("two runs of the same bean do not share the end-of-file flag")
        void twoRunsOfOneBeanAreIndependent() {
            CapturedSysout first = new CapturedSysout();
            AccountBalanceJob subject = job(seeded(fixtureRows()), first);
            subject.readAndPrintAccountFile(first);

            // The flag reached 'Y' during the first pass. If it lived on the bean the second pass would
            // read nothing at all, so this is the assertion that the per-run WorkingStorage is real.
            CapturedSysout second = new CapturedSysout();
            subject.readAndPrintAccountFile(second);

            assertThat(second.lines()).hasSize(EXPECTED_LINES);
            assertThat(second.lines()).containsExactlyElementsOf(first.lines());
        }

        @Test
        @DisplayName("no static field of the job carries a real dataset name (G46)")
        void noDatasetNameIsCompiledIn() {
            List<String> offending = new ArrayList<>();
            for (Field field : AccountBalanceJob.class.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) {
                    continue;
                }
                field.setAccessible(true);
                Object value = valueOf(field);
                if (value instanceof String text && text.contains("AWS.M2.CARDDEMO.")) {
                    offending.add(field.getName() + " = " + text);
                }
            }

            assertThat(offending)
                    .as("dataset names resolve from carddemo.datasets in application.yml, never from "
                            + "Java source")
                    .isEmpty();
        }

        @Test
        @DisplayName("the job's own source file contains no AWS.M2.CARDDEMO literal (G46)")
        void theSourceCarriesNoDatasetName() {
            Path source = jobSourceFile();
            List<String> lines = readSource(source);

            List<String> offending = lines.stream()
                    .filter(line -> line.contains("AWS.M2.CARDDEMO."))
                    .toList();

            assertThat(offending)
                    .as("app/jcl/READACCT.jcl names AWS.M2.CARDDEMO.ACCTDATA.VSAM.KSDS; the Java side "
                            + "names only the DD, and application.yml maps it")
                    .isEmpty();
            assertThat(lines).isNotEmpty();
        }

        @Test
        @DisplayName("the job names the DD, and the binding catalogue supplies the dataset")
        void theDatasetResolvesThroughTheBindingKey() {
            // READACCT.jcl:L25 declares //ACCTFILE, so ACCTFILE is the carddemo.datasets key. The job
            // publishes that key and nothing else; the DSNAME behind it comes from the catalogue, which
            // is why this test's own stand-in name is what the repository reports.
            assertThat(AccountBalanceJob.DD_NAME)
                    .isEqualTo(AccountRepository.BATCH_DD_NAME)
                    .isEqualTo("ACCTFILE");
            assertThat(bindings().get(AccountBalanceJob.DD_NAME).dsname()).isEqualTo(TEST_DSNAME);
            assertThat(repository(seeded(List.of())).datasetName()).isEqualTo(TEST_DSNAME);
        }

        /**
         * The names of every non-final static field declared by a type.
         *
         * @param type the type to inspect
         * @return the offending field names, empty when every static field is final
         */
        private List<String> mutableStaticFieldsOf(Class<?> type) {
            return Arrays.stream(type.getDeclaredFields())
                    .filter(field -> !field.isSynthetic())
                    .filter(field -> Modifier.isStatic(field.getModifiers()))
                    .filter(field -> !Modifier.isFinal(field.getModifiers()))
                    .map(Field::getName)
                    .toList();
        }

        /**
         * Reads a static field's value, having already been made accessible.
         *
         * @param field the field to read
         * @return its value, possibly {@code null}
         */
        private Object valueOf(Field field) {
            try {
                return field.get(null);
            } catch (IllegalAccessException inaccessible) {
                throw new IllegalStateException("The static field " + field.getName() + " of "
                        + AccountBalanceJob.class.getName() + " could not be read reflectively, so the "
                        + "gate-G46 scan could not complete", inaccessible);
            }
        }

        /**
         * Reads a Java source file as UTF-8 lines. The charset is named, never defaulted (practice B8).
         *
         * @param source the file to read
         * @return its lines, in order
         */
        private List<String> readSource(Path source) {
            try {
                return Files.readAllLines(source, StandardCharsets.UTF_8);
            } catch (IOException unreadable) {
                throw new UncheckedIOException(unreadable);
            }
        }
    }
}
