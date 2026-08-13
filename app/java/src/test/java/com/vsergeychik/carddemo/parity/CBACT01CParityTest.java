package com.vsergeychik.carddemo.parity;

import com.vsergeychik.carddemo.account.AccountBalanceJob;
import com.vsergeychik.carddemo.account.AccountBalanceJob.SysoutSink;
import com.vsergeychik.carddemo.account.AccountRepository;
import com.vsergeychik.carddemo.account.model.AccountRecord;
import com.vsergeychik.carddemo.common.AbendException;
import com.vsergeychik.carddemo.common.FileStatus;
import com.vsergeychik.carddemo.common.RecordImageForm;
import com.vsergeychik.carddemo.config.BatchConfig;
import com.vsergeychik.carddemo.config.BatchConfig.JobContract;
import com.vsergeychik.carddemo.config.BatchConfig.JobContracts;
import com.vsergeychik.carddemo.config.BatchConfig.StepContract;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBinding;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBindings;
import com.vsergeychik.carddemo.parity.FieldDiffer.DiffResult;
import com.vsergeychik.carddemo.parity.ParityHarness.Invocation;
import com.vsergeychik.carddemo.parity.ParityHarness.SeededDataset;
import com.vsergeychik.carddemo.parity.ParityHarness.UnitOutcome;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mockito;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessException;
import com.vsergeychik.carddemo.testdataset.RecordImageDataSource;
import com.vsergeychik.carddemo.testdataset.RecordImageStore.ColumnForm;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.nio.charset.Charset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The parity gate for {@code CBACT01C}: twenty declarative cases, each judged field by field, each required
 * to report a diff count of zero.
 */
@DisplayName("CBACT01C parity - 20 statically derived cases over AccountBalanceJob, which reads and "
        + "prints the account master and computes nothing")
class CBACT01CParityTest {
    private static final String PROGRAM = AccountBalanceJob.PROGRAM_ID;

    private static final String DD_NAME = AccountRepository.BATCH_DD_NAME;

    private static final Charset DATASET_CHARSET = ParityHarness.FIXTURE_CHARSET;

    private static final String TEST_DSNAME = "PARITY.CBACT01C.ACCOUNT.KSDS";

    private static final String RECORD_IMAGE_COLUMN = "REC";

    private static final String OPEN_ACCTFILE_SITE = "OPEN-" + DD_NAME;

    private static final String PERMANENT_ERROR_LINE =
            FileStatus.toDisplayLine(AccountRepository.PERMANENT_ERROR_STATUS);

    static List<ParityCase> cases() {
        return ParityHarness.casesOf(PROGRAM);
    }

    @Test
    @DisplayName("the set is exactly 20 CBACT01C batch cases, case01 through case20")
    void theCaseSetIsExactlyTwenty() {
        List<ParityCase> declared = cases();

        assertThat(declared)
                .as("the gate is 'diff count zero across all twenty cases', so the set must hold "
                        + "exactly " + ParityHarness.CASES_PER_PROGRAM + " cases; write the missing "
                        + "case files rather than lowering the count")
                .hasSize(ParityHarness.CASES_PER_PROGRAM);

        for (int ordinal = 1; ordinal <= ParityHarness.CASES_PER_PROGRAM; ordinal++) {
            ParityCase declaredCase = declared.get(ordinal - 1);
            assertThat(declaredCase.caseId())
                    .as("the cases must be enumerated in ascending order")
                    .isEqualTo(ParityHarness.caseId(ordinal));
            assertThat(declaredCase.program())
                    .as("every case in parity/%s/ must name that program", PROGRAM)
                    .isEqualTo(PROGRAM);
            assertThat(declaredCase.unitKind())
                    .as("%s is a non-CICS program invoked by app/jcl/READACCT.jcl, so every case "
                            + "reaches it as a batch job", PROGRAM)
                    .isEqualTo(ParityCase.UnitKind.BATCH_JOB);
            assertThat(declaredCase.jobParameters())
                    .as("app/jcl/READACCT.jcl:L22 is a bare EXEC PGM=%s with no PARM, so no case may "
                            + "declare a job parameter", PROGRAM)
                    .isEmpty();
            assertThat(declaredCase.expectedWrites())
                    .as("%s issues no WRITE and no REWRITE at all - the name AccountBalanceJob "
                            + "notwithstanding - so no case may expect a written record", PROGRAM)
                    .isEmpty();
        }
    }

    @Test
    @DisplayName("every case's expected SYSOUT has the shape CBACT01C's paragraphs produce")
    void expectedLineSequencesHaveTheProgramsShape() {
        for (ParityCase declaredCase : cases()) {
            List<ParityCase.EmittedMessage> messages = declaredCase.expectedMessages();
            String where = PROGRAM + '/' + declaredCase.caseId();

            assertThat(messages)
                    .as("%s: the mainline displays its opening banner at L71 before anything else, so "
                            + "no case can expect fewer than one line", where)
                    .isNotEmpty();
            assertThat(messages.get(0).text())
                    .as("%s: the first line is the L71 banner", where)
                    .isEqualTo(AccountBalanceJob.START_OF_EXECUTION);

            List<String> texts = messages.stream().map(ParityCase.EmittedMessage::text).toList();
            if (declaredCase.expectedReturnCode() == AccountBalanceJob.RETURN_CODE_NORMAL_END) {
                assertThat(texts.get(texts.size() - 1))
                        .as("%s: a normal end reaches the L85 banner", where)
                        .isEqualTo(AccountBalanceJob.END_OF_EXECUTION);
                assertThat((texts.size() - 2) % AccountBalanceJob.LINES_PER_RECORD)
                        .as("%s: SYSOUT is the two banners plus %d lines per record - the eleven "
                                + "labelled lines and the separator of 1100-DISPLAY-ACCT-RECORD, then "
                                + "the mainline's raw DISPLAY ACCOUNT-RECORD at L78 - so %d lines "
                                + "cannot be a whole number of records", where,
                                AccountBalanceJob.LINES_PER_RECORD, texts.size())
                        .isZero();
                assertThat(texts)
                        .as("%s: only the fatal arms display an error literal or the abend banner",
                                where)
                        .doesNotContain(AbendException.ABEND_DISPLAY_TEXT,
                                AccountBalanceJob.ERROR_OPENING_ACCTFILE,
                                AccountBalanceJob.ERROR_READING_ACCOUNT_FILE);
            } else {
                assertThat(declaredCase.expectedReturnCode())
                        .as("%s: every fatal arm of this program leaves APPL-RESULT at %d before "
                                + "9999-ABEND-PROGRAM performs CALL 'CEE3ABD' at L173", where,
                                AccountRepository.APPL_RESULT_FATAL)
                        .isEqualTo(AccountRepository.APPL_RESULT_FATAL);
                String declaredOpenStatus = declaredCase.unitStimulus().callSiteOutcomes()
                        .values().stream()
                        .map(ParityCase.CallSiteOutcome::status)
                        .filter(java.util.Objects::nonNull)
                        .findFirst()
                        .orElse(AccountRepository.PERMANENT_ERROR_STATUS);
                assertThat(texts)
                        .as("%s: the fatal arm is the error literal, the rendered status and the abend "
                                + "banner, in that order, and the run never reaches L85. The status line "
                                + "is rendered from the status the run actually reported - the declared "
                                + "one where the case declares a seam, and AccountRepository's frozen "
                                + "permanent error otherwise - so a case cannot pin a line for a byte no "
                                + "code path produced", where)
                        .hasSizeGreaterThanOrEqualTo(4)
                        .endsWith(FileStatus.toDisplayLine(declaredOpenStatus),
                                AbendException.ABEND_DISPLAY_TEXT)
                        .doesNotContain(AccountBalanceJob.END_OF_EXECUTION);
                assertThat(texts.get(texts.size() - 3))
                        .as("%s: the error literal is the one belonging to the paragraph that failed",
                                where)
                        .isIn(AccountBalanceJob.ERROR_OPENING_ACCTFILE,
                                AccountBalanceJob.ERROR_READING_ACCOUNT_FILE);
            }
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    @DisplayName("the diff count is zero")
    void parityDiffCountIsZero(ParityCase parityCase) {
        DiffResult result = ParityHarness.usAscii()
                .judge(parityCase, ParityCase.UnitKind.BATCH_JOB, this::runAccountBalanceJob);

        assertThat(result.count())
                .as("%s", result.render())
                .isZero();
    }

    private UnitOutcome runAccountBalanceJob(Invocation invocation) {
        RecordImageDataSource backend = new RecordImageDataSource();
        JdbcTemplate template = new JdbcTemplate(backend);
        SeededDataset seeded = invocation.hasDataset(DD_NAME) ? invocation.dataset(DD_NAME) : null;
        if (seeded != null) {
            declareRelation(backend, seeded.recordLength());
            seedRelation(backend, seeded);
        }
        {
            UnitOutcome.Builder recorder = invocation.recorder();
            SysoutSink sysout = recorder::display;
            AccountBalanceJob job = accountBalanceJob(template, sysout,
                    declaredOpenStatus(invocation));
            try {
                job.readAndPrintAccountFile(sysout);

                recorder.returnCode(AccountBalanceJob.RETURN_CODE_NORMAL_END);
            } finally {
                reportFinalState(backend, seeded, recorder);
            }
            return null;
        }
    }

    private void reportFinalState(RecordImageDataSource backend, SeededDataset seeded,
            UnitOutcome.Builder recorder) {
        if (seeded == null || seeded.recordLength() != AccountRecord.RECORD_LENGTH) {
            return;
        }
        List<String> stored = new java.util.ArrayList<>(backend.store().rows(TEST_DSNAME));
        stored.sort(java.util.Comparator.naturalOrder());
        recorder.finalState(DD_NAME, AccountRecord.LAYOUT, stored);
    }

    private void declareRelation(RecordImageDataSource backend, int recordWidth) {
        backend.define(TEST_DSNAME, RECORD_IMAGE_COLUMN, ColumnForm.CHARACTER, recordWidth);
    }

    private void seedRelation(RecordImageDataSource backend, SeededDataset seeded) {
        backend.store().seed(TEST_DSNAME, seeded.rows());
    }

    private AccountBalanceJob accountBalanceJob(JdbcTemplate template, SysoutSink sysout,
            String declaredOpenStatus) {
        return new AccountBalanceJob(batchScaffolding(),
                accountRepository(template, declaredOpenStatus), new DeclaredBean<>(sysout));
    }

    private static String declaredOpenStatus(Invocation invocation) {
        Map<String, ParityCase.CallSiteOutcome> declared = invocation.stimulus().callSiteOutcomes();
        if (declared.isEmpty()) {
            return null;
        }
        if (declared.size() != 1) {
            throw new IllegalArgumentException(PROGRAM + '/' + invocation.caseId() + " declares outcomes "
                    + "at " + declared.keySet() + ". This program has one substitutable seam - the "
                    + "OPEN INPUT at app/cbl/CBACT01C.cbl:L135 - and its fatal arm abends, so a second "
                    + "arrangement could never be reached.");
        }
        Map.Entry<String, ParityCase.CallSiteOutcome> entry = declared.entrySet().iterator().next();
        if (!OPEN_ACCTFILE_SITE.equals(entry.getKey())) {
            throw new IllegalArgumentException(PROGRAM + '/' + invocation.caseId() + " declares an "
                    + "outcome at '" + entry.getKey() + "', and the only site is '" + OPEN_ACCTFILE_SITE
                    + "'. Every other status this program distinguishes is reachable from the seed: an "
                    + "absent relation for the fatal arms, and stored rows for the normal one.");
        }
        ParityCase.CallSiteOutcome outcome = entry.getValue();
        if (outcome.resp() != null || outcome.isRefused() || outcome.afterRecords() != null) {
            throw new IllegalArgumentException(PROGRAM + '/' + invocation.caseId() + " declares a RESP, a "
                    + "refusal or a record count at " + OPEN_ACCTFILE_SITE + ". CBACT01C is a batch "
                    + "program reached by EXEC PGM= in app/jcl/READACCT.jcl and its OPEN reports a "
                    + "two-character FILE STATUS; a refusal is what an absent relation already produces, "
                    + "and declaring one here would say nothing the seed does not.");
        }
        String status = outcome.status();
        if (status == null) {
            throw new IllegalArgumentException(PROGRAM + '/' + invocation.caseId() + " names "
                    + OPEN_ACCTFILE_SITE + " without a status, which substitutes nothing.");
        }
        if (FileStatus.isOk(status)) {
            throw new IllegalArgumentException(PROGRAM + '/' + invocation.caseId() + " declares status '"
                    + status + "' at " + OPEN_ACCTFILE_SITE + ", which is the successful open the seeded "
                    + "relation already produces.");
        }
        if (status.charAt(0) == '9') {
            throw new IllegalArgumentException(PROGRAM + '/' + invocation.caseId() + " declares status '"
                    + status + "' at " + OPEN_ACCTFILE_SITE + ". A status beginning '9' reaches the "
                    + "extended arm of 9910-DISPLAY-IO-STATUS, which an absent relation already reaches - "
                    + "so declaring one would duplicate a case rather than distinguish it. This seam "
                    + "exists for the ELSE arm at app/cbl/CBACT01C.cbl:L184-L186, which needs a NUMERIC "
                    + "status whose first character is not '9'.");
        }
        return status;
    }

    private AccountRepository accountRepository(JdbcTemplate template, String declaredOpenStatus) {
        AccountRepository real = new AccountRepository(template, datasetBindings(), DATASET_CHARSET,
                RecordImageForm.CHARACTER);
        if (declaredOpenStatus == null) {
            return real;
        }
        AccountRepository arranged = Mockito.spy(real);
        Mockito.doAnswer(call -> {
            AccountRepository.AccountFile handle =
                    Mockito.spy(real.open(AccountRepository.OpenMode.INPUT));
            Mockito.doReturn(declaredOpenStatus).when(handle).openStatus();
            return handle;
        }).when(arranged).open(AccountRepository.OpenMode.INPUT);
        return arranged;
    }

    private DatasetBindings datasetBindings() {
        DatasetBinding account = new DatasetBinding(TEST_DSNAME, DatasetBinding.KSDS, false, "FB",
                null, AccountRecord.RECORD_LENGTH, "CVACT01Y", AccountRecord.KEY_LENGTH, null, null,
                null);
        DatasetBindings catalogue = new DatasetBindings();
        catalogue.put(AccountRepository.CICS_FILE_NAME, account);
        catalogue.put(DD_NAME, account);
        return catalogue;
    }

    private BatchConfig batchScaffolding() {
        JobContracts contracts = new JobContracts();
        contracts.put(AccountBalanceJob.JOB_KEY, new JobContract(PROGRAM, List.of(),
                List.of(new StepContract(AccountBalanceJob.STEP_NAME, PROGRAM, false)), null,
                Map.of()));
        return new BatchConfig(new DeclaredBean<>(Mockito.mock(JobRepository.class)),
                new DeclaredBean<>(Mockito.mock(PlatformTransactionManager.class)), contracts,
                datasetBindings());
    }

    private record DeclaredBean<T>(T bean) implements ObjectProvider<T> {
        @Override
        public T getObject() {
            return bean;
        }
    }
}
