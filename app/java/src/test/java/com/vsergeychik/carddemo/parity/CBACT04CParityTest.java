package com.vsergeychik.carddemo.parity;

import com.vsergeychik.carddemo.account.AccountInterestCalcJob;
import com.vsergeychik.carddemo.account.AccountInterestCalcJob.SysoutSink;
import com.vsergeychik.carddemo.account.AccountRepository;
import com.vsergeychik.carddemo.account.DisclosureGroupRepository;
import com.vsergeychik.carddemo.account.model.AccountRecord;
import com.vsergeychik.carddemo.account.model.DisclosureGroupRecord;
import com.vsergeychik.carddemo.card.CardXrefRepository;
import com.vsergeychik.carddemo.card.model.CardXrefRecord;
import com.vsergeychik.carddemo.common.AbendException;
import com.vsergeychik.carddemo.common.CobolDecimal;
import com.vsergeychik.carddemo.common.PhysicalSequence;
import com.vsergeychik.carddemo.common.RecordImageForm;
import com.vsergeychik.carddemo.config.BatchConfig;
import com.vsergeychik.carddemo.config.BatchConfig.JobContract;
import com.vsergeychik.carddemo.config.BatchConfig.JobContracts;
import com.vsergeychik.carddemo.config.BatchConfig.JobDatasetBinding;
import com.vsergeychik.carddemo.config.BatchConfig.JobParameterContract;
import com.vsergeychik.carddemo.config.BatchConfig.StepContract;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBinding;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBindings;
import com.vsergeychik.carddemo.common.DatasetUnitOfWork;
import com.vsergeychik.carddemo.parity.FieldDiffer.DiffResult;
import com.vsergeychik.carddemo.parity.ParityCase.UnitKind;
import com.vsergeychik.carddemo.parity.ParityHarness.Invocation;
import com.vsergeychik.carddemo.parity.ParityHarness.SeededDataset;
import com.vsergeychik.carddemo.parity.ParityHarness.UnitOutcome;
import com.vsergeychik.carddemo.transaction.TranCatBalRepository;
import com.vsergeychik.carddemo.transaction.TransactionRepository;
import com.vsergeychik.carddemo.transaction.model.TranCatBalRecord;
import com.vsergeychik.carddemo.transaction.model.TranRecord;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.time.Clock;
import java.util.Comparator;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.ObjectProvider;
import com.vsergeychik.carddemo.testdataset.RecordImageDataSource;
import com.vsergeychik.carddemo.testdataset.RecordImageStore;
import com.vsergeychik.carddemo.testdataset.RecordImageStore.ColumnForm;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The twenty-case parity gate for {@code CBACT04C}, the interest calculator - and the numeric-parity anchor
 * of the whole migration.
 */
@DisplayName("CBACT04C - interest calculator parity gate (20 cases, diff count must be zero)")
final class CBACT04CParityTest {
    private static final String PROGRAM = AccountInterestCalcJob.PROGRAM_ID;

    private static final String IMAGE_COLUMN = "REC";

    private static final PhysicalSequence WRITE_ORDER = PhysicalSequence.of("_ROWID_");

    private static final String TCATBALF_KEY = TranCatBalRepository.DD_NAME;

    private static final String ACCTFILE_KEY = AccountRepository.BATCH_DD_NAME;

    private static final String XREFFILE_KEY = CardXrefRepository.BATCH_DD_NAME;

    private static final String XREFFIL1_KEY = CardXrefRepository.ALTERNATE_INDEX_BATCH_DD_NAME;

    private static final String DISCGRP_KEY = AccountInterestCalcJob.DISCGRP_DD_NAME;

    private static final String SYSTRAN_KEY = TransactionRepository.SEQUENTIAL_OUTPUT_DD_NAME;

    private static final String TCATBALF_DSNAME = "CARDDEMO.PARITY.TCATBALF.KSDS";

    private static final String ACCTDATA_DSNAME = "CARDDEMO.PARITY.ACCTDATA.KSDS";

    private static final String CARDXREF_DSNAME = "CARDDEMO.PARITY.CARDXREF.KSDS";

    private static final String CARDXREF_AIX_DSNAME = "CARDDEMO.PARITY.CARDXREF.AIX";

    private static final String DISCGRP_DSNAME = "CARDDEMO.PARITY.DISCGRP.KSDS";

    private static final String SYSTRAN_DSNAME = "CARDDEMO.PARITY.SYSTRAN";

    private static final String TRANSACT_DSNAME = "CARDDEMO.PARITY.TRANSACT.KSDS";

    private static final boolean STEP_IS_UNGATED = false;

    private static final String PINNED_PARM_DATE = "2022071800";

    private static final String PARM_DATE_TYPE = "string";

    private static final class CapturedSysout implements SysoutSink {
        private final List<String> lines = new ArrayList<>();

        @Override
        public void write(String line) {
            lines.add(line);
        }

        private List<String> lines() {
            return lines;
        }
    }

    private record SuppliedBean<T>(T bean) implements ObjectProvider<T> {
        @Override
        public T getObject() {
            return Objects.requireNonNull(bean, "a supplied bean is never null");
        }
    }

    private static final class AbsentBean<T> implements ObjectProvider<T> {
        @Override
        public T getObject() {
            throw new NoSuchBeanDefinitionException("A " + JobRepository.class.getSimpleName()
                + " is deliberately absent from a parity run: " + PROGRAM + "'s paragraphs are reached "
                + "through calculateInterest(String, SysoutSink), with no launcher, no job repository "
                + "and no step executor between the assertion and the code.");
        }
    }

    private static List<ParityCase> cases() {
        List<ParityCase> loaded = ParityHarness.casesOf(PROGRAM);
        assertThat(loaded)
            .withFailMessage("%s must declare exactly %d parity cases under %s%s/, but %d loaded. "
                    + "The gate is a diff count of zero across ALL twenty cases; a shorter set is not "
                    + "a smaller gate, it is a gate that stops asking questions.",
                PROGRAM, ParityHarness.CASES_PER_PROGRAM, ParityHarness.CASE_RESOURCE_ROOT, PROGRAM,
                loaded.size())
            .hasSize(ParityHarness.CASES_PER_PROGRAM);
        return loaded;
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    @DisplayName("every case produces zero field-level differences")
    void diffCountIsZero(ParityCase parityCase) {
        DiffResult result = ParityHarness.usAscii()
            .judge(parityCase, UnitKind.BATCH_JOB, CBACT04CParityTest::invokeInterestCalculator);

        assertThat(result.count())
            .withFailMessage("%s/%s reported %d field-level difference(s); the gate is zero across all "
                    + "%d cases.%n%s",
                result.program(), result.caseId(), result.count(),
                ParityHarness.CASES_PER_PROGRAM, result.render())
            .isZero();
        assertThat(result.isClean())
            .withFailMessage("%s/%s is not clean although its diff count is zero, which would mean the "
                    + "two disagree.%n%s", result.program(), result.caseId(), result.render())
            .isTrue();
    }

    @Test
    @DisplayName("COMPUTE at :464-465 truncates - 1000.00 at 12.50 gives 10.41, never 10.42")
    void truncationIsObservableAndSharp() {
        BigDecimal balance = new BigDecimal("1000.00");
        BigDecimal rate = new BigDecimal("12.50");

        BigDecimal stored = AccountInterestCalcJob.computeMonthlyInterest(balance, rate);

        assertThat(stored)
            .withFailMessage("app/cbl/CBACT04C.cbl:464-465 carries no ROUNDED phrase, so the store into "
                + "WS-MONTHLY-INT PIC S9(09)V99 truncates: 1000.00 at 12.50 is 10.41, and 10.42 is the "
                + "answer this system would give if it rounded - which it does not.")
            .isEqualTo(new BigDecimal("10.41"))
            .isNotEqualTo(new BigDecimal("10.42"));

        BigDecimal exact = balance.multiply(rate)
            .divide(new BigDecimal("1200"), CobolDecimal.MONETARY_SCALE + 4,
                CobolDecimal.COBOL_ROUNDING);
        assertThat(exact).isEqualTo(new BigDecimal("10.416666"));
        assertThat(exact.setScale(CobolDecimal.MONETARY_SCALE, CobolDecimal.COBOL_ROUNDING))
            .isEqualTo(stored);
        assertThat(exact.compareTo(stored)).isPositive();

        assertThat(stored.scale()).isEqualTo(CobolDecimal.MONETARY_SCALE);
        assertThat(CobolDecimal.COBOL_ROUNDING.name()).isEqualTo("DOWN");
    }

    @Test
    @DisplayName("truncation discards the whole result when the quotient is below a cent")
    void truncationDiscardsSubCentAndScalesUp() {
        assertThat(AccountInterestCalcJob.computeMonthlyInterest(
                new BigDecimal("0.05"), new BigDecimal("15.00")))
            .isEqualTo(new BigDecimal("0.00"))
            .isNotEqualTo(new BigDecimal("0.01"));

        assertThat(AccountInterestCalcJob.computeMonthlyInterest(
                new BigDecimal("100000.00"), new BigDecimal("9.99")))
            .isEqualTo(new BigDecimal("832.50"));

        assertThat(AccountInterestCalcJob.computeMonthlyInterest(
                new BigDecimal("33333.33"), new BigDecimal("15.00")))
            .isEqualTo(new BigDecimal("416.66"))
            .isNotEqualTo(new BigDecimal("416.67"));

        assertThat(AccountInterestCalcJob.computeMonthlyInterest(
                new BigDecimal("-1000.00"), new BigDecimal("12.50")))
            .isEqualTo(new BigDecimal("-10.41"))
            .isNotEqualTo(new BigDecimal("-10.42"));
    }

    @Test
    @DisplayName("1400-COMPUTE-FEES at :518-520 changes nothing at all")
    void computeFeesComputesNothing() {
        JdbcTemplate database = database("computeFees");
        seedRow(database, TCATBALF_DSNAME,
            "00000000001" + "01" + "0001" + "0000100000{" + "0".repeat(22));
        seedRow(database, ACCTDATA_DSNAME, accountImage("00000000001", "00000001940{",
            "00000000000{", "00000000000{", "A000000000"));
        seedRow(database, DISCGRP_DSNAME, "A000000000" + "01" + "0001" + "00150{" + "0".repeat(28));
        CapturedSysout sysout = new CapturedSysout();
        AccountInterestCalcJob job = job(database, ParityHarness.fixedClockAt(
            ParityHarness.DEFAULT_PINNED_CLOCK), sysout, "2022071800",
            ParityHarness.FIXTURE_CHARSET);

        Map<String, List<String>> before = photograph(database);
        for (int invocation = 0; invocation < 5; invocation++) {
            job.computeFees();
        }

        assertThat(photograph(database))
            .withFailMessage("1400-COMPUTE-FEES is a stub marked \"To be implemented\" and performs no "
                + "statement, so its Java counterpart must leave every dataset exactly as it found it. "
                + "Computing a fee here would be adding a feature the legacy estate does not have, and "
                + "it would be invisible in the account master because a fee added to WS-TOTAL-INT "
                + "looks exactly like interest.")
            .isEqualTo(before);
        assertThat(sysout.lines())
            .withFailMessage("1400-COMPUTE-FEES displays nothing; %s line(s) were emitted.",
                sysout.lines().size())
            .isEmpty();
    }

    @Test
    @DisplayName("an abnormal end discards the SYSTRAN generation and spares the rewritten account")
    void theAbnormalDispositionDiscardsTheGenerationAndSparesTheAccountMaster() {
        final String firstAccount = "00000000001";
        final String secondAccount = "00000000002";

        JdbcTemplate reaching = arrangementWritingOneTransaction("dispositionNormal", firstAccount,
            null);
        CapturedSysout reachingSysout = new CapturedSysout();
        long recordsRead = job(reaching, ParityHarness.fixedClockAt(
            ParityHarness.DEFAULT_PINNED_CLOCK), reachingSysout, PINNED_PARM_DATE,
            ParityHarness.FIXTURE_CHARSET).calculateInterest(PINNED_PARM_DATE, reachingSysout);

        assertThat(recordsRead)
            .withFailMessage("This run reaches end of file having read the one category balance it was "
                + "given, so WS-RECORD-COUNT at GOBACK is one. Anything else means the run did not take "
                + "the path this test is about.")
            .isEqualTo(1L);
        assertThat(rowsOf(reaching, SYSTRAN_DSNAME, WRITE_ORDER.orderByClause()))
            .withFailMessage("10000.00 at 15.00 is 125.00, which is not zero, so :214 is true and "
                + "1300-B-WRITE-TX writes exactly one record. Without that write this test would prove "
                + "nothing about the disposition, because an empty generation is also what a run that "
                + "never wrote leaves behind.")
            .hasSize(1);

        JdbcTemplate abending = arrangementWritingOneTransaction("dispositionAbnormal", firstAccount,
            secondAccount);
        CapturedSysout abendingSysout = new CapturedSysout();
        AccountInterestCalcJob abendingJob = job(abending, ParityHarness.fixedClockAt(
            ParityHarness.DEFAULT_PINNED_CLOCK), abendingSysout, PINNED_PARM_DATE,
            ParityHarness.FIXTURE_CHARSET);

        assertThatThrownBy(() -> abendingJob.calculateInterest(PINNED_PARM_DATE, abendingSysout))
            .isInstanceOf(AbendException.class)
            .satisfies(raised -> assertThat(((AbendException) raised).getReturnCode())
                .withFailMessage("app/cbl/CBACT04C.cbl:378-381 moves 12 into APPL-RESULT before the "
                    + "abend, so the run's RETURN-CODE is 12.")
                .isEqualTo(AccountInterestCalcJob.APPL_RESULT_FATAL));
        assertThat(abendingSysout.lines())
            .withFailMessage("The abend must be the one this arrangement forces - the INVALID KEY "
                + "phrase at :374-375 for the account the second category balance names.")
            .contains(AccountInterestCalcJob.ACCOUNT_NOT_FOUND_PREFIX + secondAccount);
        assertThat(rowsOf(abending, SYSTRAN_DSNAME, WRITE_ORDER.orderByClause()))
            .withFailMessage("The abended run wrote one record and then abended, and DISP=(NEW,CATLG,"
                + "DELETE) deletes the generation a step allocated when the step ends abnormally. A "
                + "generation left behind here is a partial dataset the mainframe would not leave.")
            .isEmpty();

        AccountRecord left = AccountRecord.decode(
            storedAccount(abending, firstAccount).getBytes(ParityHarness.FIXTURE_CHARSET),
            ParityHarness.FIXTURE_CHARSET);
        assertThat(left.getAcctCurrBal())
            .withFailMessage("app/cbl/CBACT04C.cbl:352 adds WS-TOTAL-INT to ACCT-CURR-BAL and :356 "
                + "rewrites it. That rewrite is durable under DISP=SHR and must survive the abend that "
                + "followed it, so the account master is not part of what the disposition removes.")
            .isEqualByComparingTo(new BigDecimal("319.00"));
        assertThat(left.getAcctCurrCycCredit()).isEqualByComparingTo(new BigDecimal("0.00"));
        assertThat(left.getAcctCurrCycDebit()).isEqualByComparingTo(new BigDecimal("0.00"));
    }

    private static JdbcTemplate arrangementWritingOneTransaction(String discriminator,
                                                                String firstAccount,
                                                                String unknownAccount) {
        JdbcTemplate database = database(discriminator);
        seedRow(database, TCATBALF_DSNAME,
            firstAccount + "01" + "0001" + "0000100000{" + "0".repeat(22));
        if (unknownAccount != null) {
            seedRow(database, TCATBALF_DSNAME,
                unknownAccount + "01" + "0001" + "0000100000{" + "0".repeat(22));
        }
        seedRow(database, ACCTDATA_DSNAME, accountImage(firstAccount, "00000001940{",
            "00000000000{", "00000000000{", "A000000000"));
        String crossReference = "9680294154603697" + "000000001" + firstAccount;
        seedRow(database, CARDXREF_DSNAME,
            crossReference + " ".repeat(CardXrefRecord.RECORD_LENGTH - crossReference.length()));
        seedRow(database, CARDXREF_AIX_DSNAME,
            crossReference + " ".repeat(CardXrefRecord.RECORD_LENGTH - crossReference.length()));
        seedRow(database, DISCGRP_DSNAME, "A000000000" + "01" + "0001" + "00150{" + "0".repeat(28));
        return database;
    }

    private static String storedAccount(JdbcTemplate database, String acctId) {
        return rowsOf(database, ACCTDATA_DSNAME, " ORDER BY " + IMAGE_COLUMN + " ASC").stream()
            .filter(image -> image.startsWith(acctId))
            .findFirst()
            .orElseThrow(() -> new AssertionError("The account master no longer holds " + acctId
                + ", although app/jcl/INTCALC.jcl:33-34 binds ACCTFILE DISP=SHR and nothing in "
                + "CBACT04C deletes a record"));
    }

    @Test
    @DisplayName("PARM-DATE X(10) + WS-TRANID-SUFFIX 9(06) is exactly TRAN-ID X(16)")
    void generatedTransactionIdentifierGeometryHolds() {
        assertThat(AccountInterestCalcJob.PARM_DATE_WIDTH
            + AccountInterestCalcJob.TRANID_SUFFIX_WIDTH).isEqualTo(TranRecord.TRAN_ID_LENGTH);
        assertThat(AccountInterestCalcJob.PARM_DATE_WIDTH).isEqualTo(BatchConfig.PARM_DATE_WIDTH);

        assertThat(AccountInterestCalcJob.GENERATED_TRAN_TYPE_CD).isEqualTo("01");
        assertThat(AccountInterestCalcJob.GENERATED_TRAN_CAT_CD).isEqualTo("05");
        assertThat(AccountInterestCalcJob.GENERATED_TRAN_SOURCE).isEqualTo("System");
        assertThat(AccountInterestCalcJob.GENERATED_TRAN_DESC_PREFIX).isEqualTo("Int. for a/c ");
        assertThat(AccountInterestCalcJob.GENERATED_TRAN_DESC_PREFIX.length()
            + AccountRecord.ACCT_ID_LENGTH).isEqualTo(24);

        assertThat(TranRecord.RECORD_LENGTH).isEqualTo(350);
        assertThat(AccountRecord.RECORD_LENGTH).isEqualTo(300);
        assertThat(TranCatBalRecord.RECORD_LENGTH).isEqualTo(50);
        assertThat(DisclosureGroupRecord.RECORD_LENGTH).isEqualTo(50);
        assertThat(CardXrefRecord.RECORD_LENGTH).isEqualTo(50);
    }

    private static UnitOutcome invokeInterestCalculator(Invocation invocation) {
        UnitOutcome.Builder recorder = invocation.recorder();
        Charset charset = invocation.charset();
        JdbcTemplate database = database(invocation.caseId());
        seedDeclaredDatasets(invocation, database);

        CapturedSysout sysout = new CapturedSysout();
        String parmDate = requiredParmDate(invocation);
        AccountInterestCalcJob job =
            job(database, invocation.clock(), sysout, parmDate, charset);
        try {
            job.calculateInterest(parmDate, sysout);
        } finally {
            for (String line : sysout.lines()) {
                recorder.display(line);
            }
            recordGeneratedTransactions(recorder, database);
            recordFinalState(recorder, invocation, database);
        }
        return null;
    }

    private static String requiredParmDate(Invocation invocation) {
        String declared = invocation.jobParameter(BatchConfig.PARM_DATE_PARAMETER);
        assertThat(declared)
            .withFailMessage("Case %s/%s declares no \"%s\" job parameter. app/jcl/INTCALC.jcl:22 is "
                    + "EXEC PGM=%s,PARM='2022071800' and app/cbl/CBACT04C.cbl:476-480 strings that value "
                    + "into every TRAN-ID this program writes, so it is required and is never defaulted.",
                invocation.program(), invocation.caseId(), BatchConfig.PARM_DATE_PARAMETER, PROGRAM)
            .isNotNull();
        return declared;
    }

    private static void seedDeclaredDatasets(Invocation invocation, JdbcTemplate database) {
        for (Map.Entry<String, SeededDataset> entry : invocation.datasets().entrySet()) {
            String key = entry.getKey();
            String dsname = relationOf(key);
            assertThat(dsname)
                .withFailMessage("Case %s/%s seeds dataset \"%s\", which %s does not open. Its inputs "
                        + "are %s, %s, %s, %s and %s (app/jcl/INTCALC.jcl:27-36); its only output is %s.",
                    invocation.program(), invocation.caseId(), key, PROGRAM, TCATBALF_KEY, ACCTFILE_KEY,
                    XREFFILE_KEY, XREFFIL1_KEY, DISCGRP_KEY, SYSTRAN_KEY)
                .isNotNull();
            for (String image : entry.getValue().rows()) {
                seedRow(database, dsname, image);
            }
        }
    }

    private static void recordGeneratedTransactions(UnitOutcome.Builder recorder,
                                                    JdbcTemplate database) {
        recorder.wroteAll(SYSTRAN_KEY, TranRecord.LAYOUT,
            rowsOf(database, SYSTRAN_DSNAME, WRITE_ORDER.orderByClause()));
    }

    private static void recordFinalState(UnitOutcome.Builder recorder, Invocation invocation,
                                         JdbcTemplate database) {
        String keyOrder = " ORDER BY " + IMAGE_COLUMN + " ASC";
        for (String key : invocation.datasets().keySet()) {
            if (TCATBALF_KEY.equals(key)) {
                recorder.finalState(key, TranCatBalRecord.LAYOUT,
                    rowsOf(database, TCATBALF_DSNAME, keyOrder));
            } else if (ACCTFILE_KEY.equals(key)) {
                recorder.finalState(key, AccountRecord.LAYOUT,
                    rowsOf(database, ACCTDATA_DSNAME, keyOrder));
            } else if (XREFFILE_KEY.equals(key)) {
                recorder.finalState(key, CardXrefRecord.LAYOUT,
                    rowsOf(database, CARDXREF_DSNAME, keyOrder));
            } else if (XREFFIL1_KEY.equals(key)) {
                recorder.finalState(key, CardXrefRecord.LAYOUT,
                    rowsOf(database, CARDXREF_AIX_DSNAME, keyOrder));
            } else if (DISCGRP_KEY.equals(key)) {
                recorder.finalState(key, DisclosureGroupRecord.layout(),
                    rowsOf(database, DISCGRP_DSNAME, keyOrder));
            }
        }
    }

    private static JdbcTemplate database(String discriminator) {
        Objects.requireNonNull(discriminator, "A per-case store is named after the case that owns it");
        JdbcTemplate database = new JdbcTemplate(new RecordImageDataSource());
        createRelation(database, TCATBALF_DSNAME, TranCatBalRecord.RECORD_LENGTH);
        createRelation(database, ACCTDATA_DSNAME, AccountRecord.RECORD_LENGTH);
        createRelation(database, CARDXREF_DSNAME, CardXrefRecord.RECORD_LENGTH);
        createRelation(database, CARDXREF_AIX_DSNAME, CardXrefRecord.RECORD_LENGTH);
        createRelation(database, DISCGRP_DSNAME, DisclosureGroupRecord.RECORD_LENGTH);
        createRelation(database, SYSTRAN_DSNAME, TranRecord.RECORD_LENGTH);
        createRelation(database, TRANSACT_DSNAME, TranRecord.RECORD_LENGTH);
        return database;
    }

    private static void createRelation(JdbcTemplate database, String dsname, int width) {
        store(database).define(dsname, IMAGE_COLUMN, ColumnForm.CHARACTER, width);
    }

    private static void seedRow(JdbcTemplate database, String dsname, String image) {
        store(database).seed(dsname, image);
    }

    private static RecordImageStore store(JdbcTemplate database) {
        return ((RecordImageDataSource) Objects.requireNonNull(database.getDataSource(),
            "A per-case template always has its store behind it")).store();
    }

    private static List<String> rowsOf(JdbcTemplate database, String dsname, String ordering) {
        List<String> rows = new ArrayList<>(store(database).rows(dsname));
        if (ordering.contains(IMAGE_COLUMN)) {
            rows.sort(Comparator.naturalOrder());
        }
        return rows;
    }

    private static Map<String, List<String>> photograph(JdbcTemplate database) {
        String keyOrder = " ORDER BY " + IMAGE_COLUMN + " ASC";
        Map<String, List<String>> contents = new LinkedHashMap<>();
        for (String dsname : List.of(TCATBALF_DSNAME, ACCTDATA_DSNAME, CARDXREF_DSNAME,
            CARDXREF_AIX_DSNAME, DISCGRP_DSNAME, SYSTRAN_DSNAME, TRANSACT_DSNAME)) {
            contents.put(dsname, rowsOf(database, dsname, keyOrder));
        }
        return contents;
    }

    private static String relationOf(String key) {
        if (TCATBALF_KEY.equals(key)) {
            return TCATBALF_DSNAME;
        }
        if (ACCTFILE_KEY.equals(key)) {
            return ACCTDATA_DSNAME;
        }
        if (XREFFILE_KEY.equals(key)) {
            return CARDXREF_DSNAME;
        }
        if (XREFFIL1_KEY.equals(key)) {
            return CARDXREF_AIX_DSNAME;
        }
        if (DISCGRP_KEY.equals(key)) {
            return DISCGRP_DSNAME;
        }
        return null;
    }

    private static AccountInterestCalcJob job(JdbcTemplate database, Clock clock,
                                              SysoutSink sysout, String parmDate, Charset charset) {
        DatasetBindings bindings = bindings();
        DataSource source = Objects.requireNonNull(database.getDataSource(),
            "the template must carry a data source: the unit of work spans it");
        PlatformTransactionManager manager = new JdbcTransactionManager(source);
        return new AccountInterestCalcJob(
            new BatchConfig(new AbsentBean<>(), new SuppliedBean<>(manager), contracts(parmDate),
                bindings),
            new TranCatBalRepository(database, bindings, charset, RecordImageForm.CHARACTER),
            new AccountRepository(database, bindings, charset, RecordImageForm.CHARACTER),
            new CardXrefRepository(database, bindings, charset, RecordImageForm.CHARACTER),
            new TransactionRepository(database, bindings, charset, RecordImageForm.CHARACTER,
                WRITE_ORDER),
            new DisclosureGroupRepository(database, bindings, charset,
                RecordImageForm.CHARACTER),
            new DatasetUnitOfWork(manager),
            new SuppliedBean<>(sysout),
            clock);
    }

    private static DatasetBindings bindings() {
        DatasetBindings bindings = new DatasetBindings();
        DatasetBinding tcatbal = new DatasetBinding(TCATBALF_DSNAME, DatasetBinding.KSDS, false, "FB",
            null, TranCatBalRecord.RECORD_LENGTH, "CVTRA01Y", TranCatBalRecord.TRAN_CAT_KEY_LENGTH,
            null, null, null);
        bindings.put(TCATBALF_KEY, tcatbal);

        DatasetBinding account = new DatasetBinding(ACCTDATA_DSNAME, DatasetBinding.KSDS, false, "FB",
            null, AccountRecord.RECORD_LENGTH, "CVACT01Y", AccountRecord.ACCT_ID_LENGTH, null, null,
            null);
        bindings.put(AccountRepository.CICS_FILE_NAME, account);
        bindings.put(ACCTFILE_KEY, account);

        DatasetBinding xrefBase = new DatasetBinding(CARDXREF_DSNAME, DatasetBinding.KSDS, false, "FB",
            null, CardXrefRecord.RECORD_LENGTH, "CVACT03Y", null, null, null, null);
        bindings.put(CardXrefRepository.BASE_DD_NAME, xrefBase);
        bindings.put(XREFFILE_KEY, xrefBase);

        DatasetBinding xrefPath = new DatasetBinding(CARDXREF_AIX_DSNAME, DatasetBinding.AIX_PATH,
            false, "FB", null, CardXrefRecord.RECORD_LENGTH, "CVACT03Y", null, null,
            CardXrefRepository.BASE_DD_NAME, CardXrefRepository.EXPECTED_ALTERNATE_KEY_FIELD);
        bindings.put(CardXrefRepository.ALTERNATE_INDEX_DD_NAME, xrefPath);
        bindings.put(XREFFIL1_KEY, new DatasetBinding(CARDXREF_AIX_DSNAME, DatasetBinding.AIX_PATH,
            false, "FB", null, CardXrefRecord.RECORD_LENGTH, "CVACT03Y", null, null, XREFFILE_KEY,
            CardXrefRepository.EXPECTED_ALTERNATE_KEY_FIELD));

        bindings.put(DISCGRP_KEY, new DatasetBinding(DISCGRP_DSNAME, DatasetBinding.KSDS, false, "FB",
            null, DisclosureGroupRecord.RECORD_LENGTH, "CVTRA02Y",
            DisclosureGroupRecord.DIS_GROUP_KEY_LENGTH, null, null, null));

        DatasetBinding master = new DatasetBinding(TRANSACT_DSNAME, DatasetBinding.KSDS, false, "FB",
            null, TranRecord.RECORD_LENGTH, "CVTRA05Y", TranRecord.TRAN_ID_LENGTH, null, null, null);
        bindings.put(TransactionRepository.CICS_FILE_NAME, master);
        bindings.put(TransactionRepository.INPUT_DD_NAME, master);
        bindings.put(SYSTRAN_KEY, new DatasetBinding(SYSTRAN_DSNAME, "sequential", false, "F", 0,
            TranRecord.RECORD_LENGTH, "CVTRA05Y", null, null, null, null));
        return bindings;
    }

    private static JobContracts contracts(String parmDate) {
        JobContracts catalogue = new JobContracts();
        catalogue.put(AccountInterestCalcJob.JOB_KEY, new JobContract(
            PROGRAM,
            List.of(new JobParameterContract(BatchConfig.PARM_DATE_PARAMETER, PARM_DATE_TYPE,
                parmDate)),
            List.of(new StepContract(AccountInterestCalcJob.STEP_NAME, PROGRAM, STEP_IS_UNGATED)),
            null,
            Map.of(AccountInterestCalcJob.TRANSACT_DD_NAME, new JobDatasetBinding(SYSTRAN_KEY, null,
                null, false, null, null, null, null, null, null, null, null))));
        return catalogue;
    }

    private static String accountImage(String acctId, String currBal, String cycCredit,
                                       String cycDebit, String groupId) {
        String image = acctId + "Y" + currBal + "00000020200{" + "00000010200{"
            + "2014-11-20" + "2025-05-20" + "2025-05-20" + cycCredit + cycDebit
            + "A000000000" + groupId;
        return image + " ".repeat(AccountRecord.RECORD_LENGTH - image.length());
    }
}
