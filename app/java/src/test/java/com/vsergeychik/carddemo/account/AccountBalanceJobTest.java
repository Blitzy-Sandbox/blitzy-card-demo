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
 */
@DisplayName("AccountBalanceJob - CBACT01C, which reads and prints the account master and computes "
        + "nothing")
class AccountBalanceJobTest {
    private static final Charset ASCII = StandardCharsets.US_ASCII;

    private static final String TEST_DSNAME = "TEST.ACCOUNT.KSDS";

    private static final String RECORD_IMAGE_COLUMN = "REC";

    private static final String FIXTURE = "/fixtures/acctdata.txt";

    private static final int FIXTURE_RECORDS = 50;

    private static final int RECORD_LENGTH = 300;

    private static final int KEY_LENGTH = 11;

    private static final int FILLER_LENGTH = 178;

    private static final int EXPECTED_LINES =
            1 + (FIXTURE_RECORDS * AccountBalanceJob.LINES_PER_RECORD) + 1;

    private static final String PERMANENT_ERROR_LINE =
            FileStatus.toDisplayLine(AccountRepository.PERMANENT_ERROR_STATUS);

    private static final AtomicInteger DATABASE_SEQUENCE = new AtomicInteger();

    private static final class CapturedSysout implements SysoutSink {
        private final List<String> lines = new ArrayList<>();

        @Override
        public void write(String line) {
            lines.add(line);
        }

        List<String> lines() {
            return lines;
        }
    }

    private static final class AbsentBean<T> implements ObjectProvider<T> {
        @Override
        public T getObject() {
            throw new NoSuchBeanDefinitionException("no bean of this type is declared in this test");
        }
    }

    private record PresentBean<T>(T bean) implements ObjectProvider<T> {
        @Override
        public T getObject() {
            return bean;
        }
    }

    private static DatasetBindings bindings() {
        DatasetBindings catalogue = new DatasetBindings();
        catalogue.put(AccountRepository.CICS_FILE_NAME, new DatasetBinding(TEST_DSNAME, "ksds", false,
                "FB", null, RECORD_LENGTH, "CVACT01Y", KEY_LENGTH, null, null, null));
        catalogue.put(AccountRepository.BATCH_DD_NAME, new DatasetBinding(TEST_DSNAME, "ksds", false,
                "FB", null, RECORD_LENGTH, "CVACT01Y", KEY_LENGTH, null, null, null));
        return catalogue;
    }

    private static JobContracts jobContracts() {
        return jobContracts(new StepContract(AccountBalanceJob.STEP_NAME,
                AccountBalanceJob.PROGRAM_ID, false));
    }

    private static JobContracts jobContracts(StepContract step) {
        return jobContracts(List.of(step));
    }

    private static JobContracts jobContracts(List<StepContract> steps) {
        JobContracts catalogue = new JobContracts();
        catalogue.put(AccountBalanceJob.JOB_KEY, new JobContract(AccountBalanceJob.PROGRAM_ID,
                List.of(), steps, null, Map.of()));
        return catalogue;
    }

    private static BatchConfig scaffolding(JobContracts contracts) {
        return new BatchConfig(new PresentBean<>(Mockito.mock(JobRepository.class)),
                new PresentBean<>(Mockito.mock(PlatformTransactionManager.class)),
                contracts, bindings());
    }

    private static AccountBalanceJob job(JdbcTemplate template, SysoutSink sysout) {
        return new AccountBalanceJob(scaffolding(jobContracts()), repository(template),
                new PresentBean<>(sysout));
    }

    private static AccountRepository repository(JdbcTemplate template) {
        return new AccountRepository(template, bindings(), ASCII, RecordImageForm.CHARACTER);
    }

    private static JdbcTemplate emptyDatabase() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:acctjob" + DATABASE_SEQUENCE.incrementAndGet()
                        + ";DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE", "sa", "");
        dataSource.setDriverClassName("org.h2.Driver");
        return new JdbcTemplate(dataSource);
    }

    private static JdbcTemplate seeded(List<String> rows) {
        JdbcTemplate template = emptyDatabase();
        template.execute("CREATE TABLE \"" + TEST_DSNAME + "\" (" + RECORD_IMAGE_COLUMN
                + " VARCHAR(" + RECORD_LENGTH + "))");
        for (String row : rows) {
            template.update("INSERT INTO \"" + TEST_DSNAME + "\" VALUES (?)", row);
        }
        return template;
    }

    @SuppressWarnings("unchecked")
    private static JdbcTemplate refusingTheSecondDescribe(JdbcTemplate real) {
        JdbcTemplate spy = Mockito.spy(real);
        Mockito.doCallRealMethod()
                .doThrow(new DataAccessResourceFailureException(
                        "the account master dataset is no longer addressable"))
                .when(spy).query(Mockito.anyString(), Mockito.any(ResultSetExtractor.class));
        return spy;
    }

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

    private static AccountBalanceJob jobOverScriptedStatuses(String openStatus,
            List<ReadResult> readScript, String closeStatus, SysoutSink sysout) {
        return new AccountBalanceJob(scaffolding(jobContracts()),
                scriptedRepository(openStatus, readScript, closeStatus), new PresentBean<>(sysout));
    }

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

    private static AccountRecord firstFixtureRecord() {
        return AccountRecord.decode(firstFixtureRow(), ASCII);
    }

    private static String firstFixtureRow() {
        return fixtureRows().stream().sorted().findFirst().orElseThrow();
    }

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

    private static Path moduleRoot() {
        try {
            Path testClasses = Path.of(AccountBalanceJobTest.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
            return testClasses.getParent().getParent();
        } catch (URISyntaxException | NullPointerException unresolvable) {
            throw new IllegalStateException("The test class's code source did not resolve to a "
                    + "directory, so the module root could not be derived: "
                    + unresolvable.getClass().getName());
        }
    }

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
            assertThat(AccountBalanceJob.FIELD_LABELS).containsExactly(
                    "ACCT-ID                 :",
                    "ACCT-ACTIVE-STATUS      :",
                    "ACCT-CURR-BAL           :",
                    "ACCT-CREDIT-LIMIT       :",
                    "ACCT-CASH-CREDIT-LIMIT  :",
                    "ACCT-OPEN-DATE          :",
                    "ACCT-EXPIRAION-DATE     :",
                    "ACCT-REISSUE-DATE       :",
                    "ACCT-CURR-CYC-CREDIT    :",
                    "ACCT-CURR-CYC-DEBIT     :",
                    "ACCT-GROUP-ID           :");

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

            String raw = sysout.lines().get(AccountBalanceJob.LINES_PER_RECORD);
            assertThat(raw).hasSize(RECORD_LENGTH);
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
            assertThat(sysout.lines()).contains(labelledLine);
            assertThat(sysout.lines()).contains(first);

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

    @Nested
    @DisplayName("the account-master handle is released however the pass ends")
    class HandleRelease {
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

    @Nested
    @DisplayName("The context-load gate - the job bean really does wire (G3)")
    @SpringBootTest(classes = CardDemoApplication.class)
    @ActiveProfiles("test")
    class ContextWiring {
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
                    .withCauseInstanceOf(JobInterruptedException.class);

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

            assertThat(sysout.lines())
                    .hasSize(1 + stopAfter * AccountBalanceJob.LINES_PER_RECORD);
            assertThat((sysout.lines().size() - 1) % AccountBalanceJob.LINES_PER_RECORD).isZero();

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

            assertThatExceptionOfType(StopRequestedException.class).isThrownBy(() ->
                    tasklet.execute(contribution, new ChunkContext(new StepContext(stepExecution))));

            assertThat(sysout.lines()).containsExactly(AccountBalanceJob.START_OF_EXECUTION);
            assertThat(contribution.getReadCount()).isZero();
        }

        private StepExecution stepExecution() {
            return new StepExecution(AccountBalanceJob.STEP_NAME, new JobExecution(9L));
        }

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

    @Nested
    @DisplayName("Every file status, at every call site (G47)")
    class StatusOutcomesPerCallSite {
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

            assertThat(sysout.lines()).containsExactly(
                    AccountBalanceJob.START_OF_EXECUTION,
                    AccountBalanceJob.ERROR_OPENING_ACCTFILE,
                    FileStatus.DISPLAY_PREFIX + expectedImage,
                    AbendException.ABEND_DISPLAY_TEXT);
            assertThat(abend.getReturnCode()).isEqualTo(AccountBalanceJob.APPL_RESULT_FATAL);
            assertThat(abend.getAbendCode()).hasValue(AbendException.STANDARD_ABEND_CODE);
            assertThat(abend.getTiming()).hasValue(AbendException.STANDARD_TIMING);
        }

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

            assertThat(sysout.lines()).containsExactly(
                    AccountBalanceJob.START_OF_EXECUTION,
                    AccountBalanceJob.ERROR_READING_ACCOUNT_FILE,
                    FileStatus.DISPLAY_PREFIX + expectedImage,
                    AbendException.ABEND_DISPLAY_TEXT);
            assertThat(abend.getReturnCode()).isEqualTo(AccountBalanceJob.APPL_RESULT_FATAL);
            assertThat(abend.getReason().orElseThrow())
                    .contains(AccountBalanceJob.ERROR_READING_ACCOUNT_FILE);
        }

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
            expected.add(AccountBalanceJob.ERROR_CLOSING_ACCOUNT_FILE);
            expected.add(FileStatus.DISPLAY_PREFIX + expectedImage);
            expected.add(AbendException.ABEND_DISPLAY_TEXT);
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
            expected.add(AccountBalanceJob.START_OF_EXECUTION);
            expected.addAll(expectedBlock(firstFixtureRow()));
            expected.add(AccountBalanceJob.END_OF_EXECUTION);
            assertThat(sysout.lines()).containsExactlyElementsOf(expected);
        }

        @Test
        @DisplayName("'10' on the first read ends the loop cleanly: both banners, no abend, nothing else")
        void endOfFileOnTheFirstRead() {
            CapturedSysout sysout = new CapturedSysout();
            AccountBalanceJob subject = jobOverScriptedStatuses(FileStatus.OK,
                    List.of(ReadResult.endOfFile()), FileStatus.OK, sysout);

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

            Mockito.verify(accounts).open(OpenMode.INPUT);
            Mockito.verify(handle, Mockito.times(1)).openStatus();
            Mockito.verify(handle, Mockito.times(2)).readNext();
            Mockito.verify(handle, Mockito.times(1)).closeFile();
            Mockito.verifyNoMoreInteractions(handle);
        }
    }

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

            assertThat(sysout.lines()).contains("FILE STATUS IS: NNNN0023");
        }

        @Test
        @DisplayName("a '9x' status takes the IF arm: the feedback byte becomes three decimal digits")
        void theExtendedArmForANineStatus() {
            CapturedSysout sysout = new CapturedSysout();
            AccountBalanceJob subject = jobOverScriptedStatuses("9\n", List.of(), FileStatus.OK, sysout);

            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(subject::readAndPrintAccountFile);

            assertThat(sysout.lines()).contains("FILE STATUS IS: NNNN9010");
        }

        @Test
        @DisplayName("a non-numeric status takes the IF arm too, on the NOT NUMERIC half of the test")
        void theExtendedArmForANonNumericStatus() {
            CapturedSysout sysout = new CapturedSysout();
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

            assertThat(rows).allSatisfy(row -> assertThat(row.substring(
                    AccountRecord.ACCT_ADDR_ZIP_OFFSET,
                    AccountRecord.ACCT_ADDR_ZIP_OFFSET + AccountRecord.ACCT_ADDR_ZIP_LENGTH))
                    .isEqualTo("A000000000"));

            CapturedSysout sysout = new CapturedSysout();
            job(seeded(rows), sysout).readAndPrintAccountFile();

            assertThat(sysout.lines())
                    .as("no labelled line, because 1100-DISPLAY-ACCT-RECORD has no statement for it")
                    .noneMatch(line -> line.startsWith(AccountRecord.ACCT_ADDR_ZIP_NAME));
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

            assertThat(groupIdLines).hasSize(FIXTURE_RECORDS);
            assertThat(groupIdLines).allSatisfy(line -> assertThat(line)
                    .isEqualTo(AccountBalanceJob.LABEL_ACCT_GROUP_ID + tenSpaces)
                    .hasSize(AccountBalanceJob.LABEL_WIDTH + AccountRecord.ACCT_GROUP_ID_LENGTH));
        }

        @Test
        @DisplayName("the classpath fixture is byte-identical to the reference tree's copy, unmodified")
        void theFixtureIsAFaithfulCopy() {
            Path reference = moduleRoot().getParent().getParent()
                    .resolve(Path.of("app", "data", "ASCII", "acctdata.txt"));
            assertThat(reference).exists();

            List<String> referenceRows = readAsciiLines(reference);
            assertThat(fixtureRows())
                    .as("the classpath fixture must still equal " + reference)
                    .containsExactlyElementsOf(referenceRows);
        }

        private List<String> readAsciiLines(Path file) {
            try {
                return Files.readAllLines(file, ASCII);
            } catch (IOException unreadable) {
                throw new UncheckedIOException(unreadable);
            }
        }
    }

    @Nested
    @DisplayName("No mutable static state, and no dataset name in the source")
    class StatelessnessAndConfiguration {
        @Test
        @DisplayName("the job class declares no mutable static field: WORKING-STORAGE did not become one")
        void theJobHoldsNoMutableStaticState() {
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

            assertThat(instanceFields).containsExactly("applResult", "endOfFile");
        }

        @Test
        @DisplayName("two runs of the same bean do not share the end-of-file flag")
        void twoRunsOfOneBeanAreIndependent() {
            CapturedSysout first = new CapturedSysout();
            AccountBalanceJob subject = job(seeded(fixtureRows()), first);
            subject.readAndPrintAccountFile(first);

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
            assertThat(AccountBalanceJob.DD_NAME)
                    .isEqualTo(AccountRepository.BATCH_DD_NAME)
                    .isEqualTo("ACCTFILE");
            assertThat(bindings().get(AccountBalanceJob.DD_NAME).dsname()).isEqualTo(TEST_DSNAME);
            assertThat(repository(seeded(List.of())).datasetName()).isEqualTo(TEST_DSNAME);
        }

        private List<String> mutableStaticFieldsOf(Class<?> type) {
            return Arrays.stream(type.getDeclaredFields())
                    .filter(field -> !field.isSynthetic())
                    .filter(field -> Modifier.isStatic(field.getModifiers()))
                    .filter(field -> !Modifier.isFinal(field.getModifiers()))
                    .map(Field::getName)
                    .toList();
        }

        private Object valueOf(Field field) {
            try {
                return field.get(null);
            } catch (IllegalAccessException inaccessible) {
                throw new IllegalStateException("The static field " + field.getName() + " of "
                        + AccountBalanceJob.class.getName() + " could not be read reflectively, so the "
                        + "gate-G46 scan could not complete", inaccessible);
            }
        }

        private List<String> readSource(Path source) {
            try {
                return Files.readAllLines(source, StandardCharsets.UTF_8);
            } catch (IOException unreadable) {
                throw new UncheckedIOException(unreadable);
            }
        }
    }
}
