package com.vsergeychik.carddemo.account;

import com.vsergeychik.carddemo.account.AccountInterestCalcJob.ChunkDelegate;
import com.vsergeychik.carddemo.account.AccountInterestCalcJob.DisclosureGroupAccess;
import com.vsergeychik.carddemo.account.AccountInterestCalcJob.InterestCalculationRun;
import com.vsergeychik.carddemo.account.AccountInterestCalcJob.RecordOutcome;
import com.vsergeychik.carddemo.account.AccountInterestCalcJob.SysoutSink;
import com.vsergeychik.carddemo.account.AccountInterestCalcJob.WorkingStorage;
import com.vsergeychik.carddemo.account.model.AccountRecord;
import com.vsergeychik.carddemo.account.model.DisclosureGroupRecord;
import com.vsergeychik.carddemo.card.CardXrefRepository;
import com.vsergeychik.carddemo.card.model.CardXrefRecord;
import com.vsergeychik.carddemo.common.DiagnosticText;
import com.vsergeychik.carddemo.common.AbendException;
import com.vsergeychik.carddemo.common.CobolDecimal;
import com.vsergeychik.carddemo.common.FileStatus;
import com.vsergeychik.carddemo.common.PhysicalSequence;
import com.vsergeychik.carddemo.common.RecordImageForm;
import com.vsergeychik.carddemo.common.SensitiveDiagnostics;
import com.vsergeychik.carddemo.config.BatchConfig;
import com.vsergeychik.carddemo.config.BatchConfig.JobContract;
import com.vsergeychik.carddemo.config.BatchConfig.JobContracts;
import com.vsergeychik.carddemo.config.BatchConfig.JobDatasetBinding;
import com.vsergeychik.carddemo.config.BatchConfig.JobParameterContract;
import com.vsergeychik.carddemo.config.BatchConfig.StepContract;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBinding;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBindings;
import com.vsergeychik.carddemo.common.DatasetUnitOfWork;
import com.vsergeychik.carddemo.transaction.TranCatBalRepository;
import com.vsergeychik.carddemo.transaction.TransactionRepository;
import com.vsergeychik.carddemo.transaction.model.TranCatBalRecord;
import com.vsergeychik.carddemo.transaction.model.TranRecord;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.JobParametersInvalidException;
import org.springframework.batch.core.JobParametersValidator;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.job.AbstractJob;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import com.vsergeychik.carddemo.testsupport.ConcurrentTasks;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.Timeout;

/**
 * Verifies {@link AccountInterestCalcJob} against {@code app/cbl/CBACT04C.cbl} and
 * {@code app/jcl/INTCALC.jcl}, the only authorities for what this program does.
 */
class AccountInterestCalcJobTest {
    private static final Charset ASCII = StandardCharsets.US_ASCII;

    private static final PhysicalSequence ORDINAL = PhysicalSequence.of("_ROWID_");
    private static final AtomicInteger SEQ = new AtomicInteger();

    private static final String TCATBAL_DS = "TEST.TCATBALF.KSDS";
    private static final String ACCT_DS = "TEST.ACCTDATA.KSDS";
    private static final String XREF_DS = "TEST.CARDXREF.KSDS";
    private static final String XREF_AIX_DS = "TEST.CARDXREF.AIX";
    private static final String SYSTRAN_DS = "TEST.SYSTRAN";
    private static final String DISCGRP_DS = "TEST.DISCGRP.KSDS";
    private static final String TRANSACT_DS = "TEST.TRANSACT.KSDS";
    private static final String COL = "REC";

    private static final String PARM = "2022071800";

    private static final Clock FIXED = Clock.fixed(
            Instant.parse("2022-07-18T12:34:56.780Z"), ZoneId.of("UTC"));

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

    private record PresentBean<T>(T bean) implements ObjectProvider<T> {
        @Override
        public T getObject() {
            return bean;
        }
    }

    private static final Map<String, JobDatasetBinding> TRANSACT_ALIASED_TO_SYSTRAN = Map.of(
            TransactionRepository.CICS_FILE_NAME,
            new JobDatasetBinding(TransactionRepository.SEQUENTIAL_OUTPUT_DD_NAME, null, null, false,
                    null, null, null, null, null, null, null, null));

    private static DatasetBindings bindings() {
        return bindings(50, 16);
    }

    private static DatasetBindings bindings(int discgrpRecordLength, Integer discgrpKeyLength) {
        DatasetBindings b = new DatasetBindings();
        b.put(TranCatBalRepository.DD_NAME, new DatasetBinding(TCATBAL_DS, "ksds", false, "FB", null,
                50, "CVTRA01Y", 17, null, null, null));
        b.put(AccountRepository.CICS_FILE_NAME, new DatasetBinding(ACCT_DS, "ksds", false, "FB", null,
                300, "CVACT01Y", 11, null, null, null));
        b.put(AccountRepository.BATCH_DD_NAME, new DatasetBinding(ACCT_DS, "ksds", false, "FB", null,
                300, "CVACT01Y", 11, null, null, null));
        b.put(CardXrefRepository.BASE_DD_NAME, new DatasetBinding(XREF_DS, "ksds", false, "FB", null,
                50, "CVACT03Y", null, null, null, null));
        b.put(CardXrefRepository.ALTERNATE_INDEX_DD_NAME, new DatasetBinding(XREF_AIX_DS, "aix-path",
                false, "FB", null, 50, "CVACT03Y", null, null, CardXrefRepository.BASE_DD_NAME,
                CardXrefRepository.EXPECTED_ALTERNATE_KEY_FIELD));
        b.put(CardXrefRepository.BATCH_DD_NAME, new DatasetBinding(XREF_DS, "ksds", false, "FB", null,
                50, "CVACT03Y", null, null, null, null));
        b.put(CardXrefRepository.ALTERNATE_INDEX_BATCH_DD_NAME, new DatasetBinding(XREF_AIX_DS,
                "aix-path", false, "FB", null, 50, "CVACT03Y", null, null,
                CardXrefRepository.BATCH_DD_NAME,
                CardXrefRepository.EXPECTED_ALTERNATE_KEY_FIELD));
        b.put(TransactionRepository.CICS_FILE_NAME, new DatasetBinding(TRANSACT_DS, "ksds", false, "FB",
                null, 350, "CVTRA05Y", 16, null, null, null));
        b.put(TransactionRepository.INPUT_DD_NAME, new DatasetBinding(TRANSACT_DS, "ksds", false, "FB",
                null, 350, "CVTRA05Y", 16, null, null, null));
        b.put(TransactionRepository.SEQUENTIAL_OUTPUT_DD_NAME, new DatasetBinding(SYSTRAN_DS,
                "sequential", false, "F", 0, 350, "CVTRA05Y", null, null, null, null));
        b.put(AccountInterestCalcJob.DISCGRP_DD_NAME, new DatasetBinding(DISCGRP_DS, "ksds", false, "FB",
                null, discgrpRecordLength, "CVTRA02Y", discgrpKeyLength, null, null, null));
        return b;
    }

    private static JobContracts contracts() {
        return contracts(new StepContract(AccountInterestCalcJob.STEP_NAME,
                AccountInterestCalcJob.PROGRAM_ID, false),
                List.of(new JobParameterContract(BatchConfig.PARM_DATE_PARAMETER, "string", PARM)));
    }

    private static JobContracts contracts(StepContract step, List<JobParameterContract> parameters) {
        return contracts(step, parameters, TRANSACT_ALIASED_TO_SYSTRAN);
    }

    private static JobContracts contracts(StepContract step, List<JobParameterContract> parameters,
            Map<String, JobDatasetBinding> datasets) {
        return contracts(List.of(step), parameters, datasets);
    }

    private static JobContracts contracts(List<StepContract> steps,
            List<JobParameterContract> parameters, Map<String, JobDatasetBinding> datasets) {
        JobContracts c = new JobContracts();
        c.put(AccountInterestCalcJob.JOB_KEY, new JobContract(AccountInterestCalcJob.PROGRAM_ID,
                parameters, steps, null, datasets));
        return c;
    }

    private static Map<String, JobDatasetBinding> jobScopedDatasets() {
        return Map.of(AccountInterestCalcJob.TRANSACT_DD_NAME,
                new JobDatasetBinding(TransactionRepository.SEQUENTIAL_OUTPUT_DD_NAME, null, null,
                        false, null, null, null, null, null, null, null, null));
    }

    private static BatchConfig scaffolding(JobContracts contracts, DatasetBindings bindings) {
        return new BatchConfig(new PresentBean<>(Mockito.mock(JobRepository.class)),
                new PresentBean<>(Mockito.mock(PlatformTransactionManager.class)), contracts, bindings);
    }

    private static DatasetUnitOfWork unitOfWork(JdbcTemplate t) {
        return new DatasetUnitOfWork(new JdbcTransactionManager(
                Objects.requireNonNull(t.getDataSource(), "the template must carry a data source")));
    }

    private static DatasetUnitOfWork mockedUnitOfWork() {
        return new DatasetUnitOfWork(Mockito.mock(PlatformTransactionManager.class));
    }

    private static JdbcTemplate database() {
        DriverManagerDataSource ds = new DriverManagerDataSource("jdbc:h2:mem:intcalc"
                + SEQ.incrementAndGet() + ";DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE", "sa", "");
        ds.setDriverClassName("org.h2.Driver");
        JdbcTemplate t = new JdbcTemplate(ds);
        create(t, TCATBAL_DS, 50);
        create(t, ACCT_DS, 300);
        create(t, XREF_DS, 50);
        create(t, XREF_AIX_DS, 50);
        create(t, DISCGRP_DS, 50);
        create(t, SYSTRAN_DS, 350);
        create(t, TRANSACT_DS, 350);
        return t;
    }

    private static void create(JdbcTemplate t, String dsname, int width) {
        t.execute("CREATE TABLE \"" + dsname + "\" (" + COL + " VARCHAR(" + width + "))");
    }

    private static void seed(JdbcTemplate t, String dsname, String image) {
        t.update("INSERT INTO \"" + dsname + "\" VALUES (?)", image);
    }

    private static List<String> rows(JdbcTemplate t, String dsname) {
        return t.queryForList("SELECT " + COL + " FROM \"" + dsname + "\"", String.class);
    }

    private static AccountInterestCalcJob job(JdbcTemplate t, DatasetBindings b, SysoutSink sysout) {
        return new AccountInterestCalcJob(scaffolding(contracts(), b),
                new TranCatBalRepository(t, b, ASCII, RecordImageForm.CHARACTER),
                new AccountRepository(t, b, ASCII, RecordImageForm.CHARACTER),
                new CardXrefRepository(t, b, ASCII, RecordImageForm.CHARACTER),
                new TransactionRepository(t, b, ASCII, RecordImageForm.CHARACTER, ORDINAL),
                new DisclosureGroupRepository(t, b, ASCII,
                        RecordImageForm.CHARACTER),
                unitOfWork(t),
                new PresentBean<>(sysout), FIXED);
    }

    private static String tcatbalImage(long acctId, String typeCd, int catCd, String balance) {
        TranCatBalRecord r = TranCatBalRecord.newInstance(ASCII);
        r.trancatAcctId(acctId).trancatTypeCd(typeCd).trancatCd(catCd)
                .tranCatBal(new BigDecimal(balance));
        return new String(r.encode(), ASCII);
    }

    private static String discgrpImage(String groupId, String typeCd, int catCd, String rate) {
        DisclosureGroupRecord r = new DisclosureGroupRecord(ASCII);
        r.disAcctGroupId(groupId);
        r.disTranTypeCd(typeCd);
        r.disTranCatCd(catCd);
        r.disIntRate(new BigDecimal(rate));
        return r.encodeToString();
    }

    private static String acctImage(long acctId, String balance, String groupId) {
        AccountRecord a = new AccountRecord(ASCII);
        a.setAcctId(acctId);
        a.setAcctActiveStatus("Y");
        a.setAcctCurrBal(new BigDecimal(balance));
        a.setAcctCreditLimit(new BigDecimal("5000.00"));
        a.setAcctCashCreditLimit(new BigDecimal("1000.00"));
        a.setAcctOpenDate("2020-01-01");
        a.setAcctExpiraionDate("2026-01-01");
        a.setAcctReissueDate("2023-01-01");
        a.setAcctCurrCycCredit(new BigDecimal("111.11"));
        a.setAcctCurrCycDebit(new BigDecimal("222.22"));
        a.setAcctAddrZip("12345");
        a.setAcctGroupId(groupId);
        return a.toFixedWidthString();
    }

    private static String xrefImage(String cardNum, int custId, long acctId) {
        return new String(new CardXrefRecord(cardNum, custId, acctId).encode(ASCII), ASCII);
    }

    private static JdbcTemplate singleAccountDatabase() {
        JdbcTemplate t = database();
        seed(t, TCATBAL_DS, tcatbalImage(11L, "01", 1, "1000.00"));
        seed(t, ACCT_DS, acctImage(11L, "500.00", "A000000000"));
        seed(t, XREF_DS, xrefImage("4444333322221111", 1, 11L));
        seed(t, XREF_AIX_DS, xrefImage("4444333322221111", 1, 11L));
        seed(t, DISCGRP_DS, discgrpImage("A000000000", "01", 1, "12.50"));
        return t;
    }

    @Test
    void keyGeometryIsAssertedAndCorrect() {
        assertThat(AccountInterestCalcJob.verifyDeclaredKeyGeometry()).isEqualTo(16);
        assertThat(DisclosureGroupRecord.DIS_GROUP_KEY_LENGTH).isEqualTo(16);
        assertThat(TranCatBalRecord.TRAN_CAT_KEY_LENGTH).isEqualTo(17);
        assertThat(AccountInterestCalcJob.PARM_DATE_WIDTH
                + AccountInterestCalcJob.TRANID_SUFFIX_WIDTH).isEqualTo(TranRecord.TRAN_ID_LENGTH);
    }

    @ParameterizedTest(name = "{0} at {1}% -> {2} (a rounding implementation would say {3})")
    @CsvSource({
        "     99.99, 15.00,   1.24,   1.25",
        "    100.00, 15.00,   1.25,   1.25",
        "   1000.00,  0.19,   0.15,   0.16",
        "   1000.00, 12.50,  10.41,  10.42",
        "   1234.56, 19.99,  20.56,  20.57",
        "   1234.56, 15.00,  15.43,  15.43",
        "      0.00, 15.00,   0.00,   0.00",
        "      0.01, 15.00,   0.00,   0.00",
        "    -99.99, 15.00,  -1.24,  -1.25",
        "  -1000.00,  0.19,  -0.15,  -0.16",
        "  -1000.00, 12.50, -10.41, -10.42",
    })
    void theInterestFormulaTruncatesAtEveryRow(String balance, String rate, String expectedDown,
            String roundedAlternative) {
        BigDecimal computed = AccountInterestCalcJob.computeMonthlyInterest(
                new BigDecimal(balance.trim()), new BigDecimal(rate.trim()));

        assertThat(computed).isEqualTo(new BigDecimal(expectedDown.trim()));
        assertThat(computed.scale()).isEqualTo(2);

        BigDecimal rounded = new BigDecimal(roundedAlternative.trim());
        if (rounded.compareTo(new BigDecimal(expectedDown.trim())) != 0) {
            assertThat(computed).isNotEqualByComparingTo(rounded);
        }
    }

    @Test
    void monthlyInterestUsesTheFixtureRates() {
        assertThat(AccountInterestCalcJob.computeMonthlyInterest(
                new BigDecimal("1234.56"), new BigDecimal("15.00")))
                .isEqualTo(new BigDecimal("15.43"));
        assertThat(AccountInterestCalcJob.computeMonthlyInterest(
                new BigDecimal("999999.99"), new BigDecimal("0.00")))
                .isEqualTo(new BigDecimal("0.00"));
        assertThat(AccountInterestCalcJob.computeMonthlyInterest(
                new BigDecimal("0.00"), new BigDecimal("15.00")))
                .isEqualTo(new BigDecimal("0.00"));
    }

    @Test
    void feesIsANoOpAndIsCalledOncePerInterestComputation() {
        JdbcTemplate t = singleAccountDatabase();
        DatasetBindings b = bindings();
        CapturedSysout sysout = new CapturedSysout();
        AccountInterestCalcJob spy = Mockito.spy(job(t, b, sysout));

        long processed = spy.calculateInterest(PARM, sysout);

        assertThat(processed).isEqualTo(1L);
        Mockito.verify(spy, Mockito.times(1)).computeFees();
    }

    @Test
    void feesIsNotCalledWhenTheRateIsZero() {
        JdbcTemplate t = database();
        seed(t, TCATBAL_DS, tcatbalImage(11L, "01", 1, "1000.00"));
        seed(t, ACCT_DS, acctImage(11L, "500.00", "A000000000"));
        seed(t, XREF_DS, xrefImage("4444333322221111", 1, 11L));
        seed(t, XREF_AIX_DS, xrefImage("4444333322221111", 1, 11L));
        seed(t, DISCGRP_DS, discgrpImage("A000000000", "01", 1, "0.00"));
        DatasetBindings b = bindings();
        CapturedSysout sysout = new CapturedSysout();
        AccountInterestCalcJob spy = Mockito.spy(job(t, b, sysout));

        spy.calculateInterest(PARM, sysout);

        Mockito.verify(spy, Mockito.never()).computeFees();
        assertThat(rows(t, SYSTRAN_DS)).isEmpty();
        assertThat(sysout.lines()).hasSize(3);

        BigDecimal codecZero = DisclosureGroupRecord
                .decode(discgrpImage("A000000000", "01", 1, "0.00"), ASCII).disIntRate();
        assertThat(codecZero.scale()).isEqualTo(2);
        assertThat(codecZero).isNotEqualTo(BigDecimal.ZERO).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(codecZero.equals(BigDecimal.ZERO)).isFalse();
        assertThat(codecZero.compareTo(BigDecimal.ZERO)).isZero();
    }

    @Test
    void feesChangesNothing() {
        JdbcTemplate t = singleAccountDatabase();
        AccountInterestCalcJob job = job(t, bindings(), new CapturedSysout());
        job.computeFees();
        job.computeFees();
        assertThat(rows(t, SYSTRAN_DS)).isEmpty();
    }

    @Test
    void theWrittenTransactionIs350BytesWithTheExpectedFields() {
        JdbcTemplate t = singleAccountDatabase();
        CapturedSysout sysout = new CapturedSysout();
        job(t, bindings(), sysout).calculateInterest(PARM, sysout);

        List<String> written = rows(t, SYSTRAN_DS);
        assertThat(written).hasSize(1);
        String image = written.get(0);
        assertThat(image).hasSize(350);

        TranRecord record = TranRecord.decode(image, ASCII);
        assertThat(record.tranId()).isEqualTo("2022071800000001");
        assertThat(record.tranTypeCd()).isEqualTo("01");
        assertThat(record.tranCatCd()).isEqualTo(5);
        assertThat(record.tranCatCdImage()).isEqualTo("0005");
        assertThat(record.tranSource()).isEqualTo("System    ");
        assertThat(record.tranDesc()).startsWith("Int. for a/c 00000000011");
        assertThat(record.tranAmt()).isEqualTo(new BigDecimal("10.41"));
        assertThat(record.tranMerchantId()).isZero();
        assertThat(record.tranMerchantName()).isBlank();
        assertThat(record.tranMerchantCity()).isBlank();
        assertThat(record.tranMerchantZip()).isBlank();
        assertThat(record.tranCardNum()).isEqualTo("4444333322221111");
        assertThat(record.tranOrigTs()).isEqualTo(record.tranProcTs());
        assertThat(record.filler()).isEqualTo(" ".repeat(20));

        assertThat(TranRecord.TRAN_ID_OFFSET).isZero();
        assertThat(image.substring(0, 16)).isEqualTo("2022071800000001");
        assertThat(image.substring(TranRecord.TRAN_ID_OFFSET,
                TranRecord.TRAN_ID_OFFSET + TranRecord.TRAN_ID_LENGTH))
                .isEqualTo("2022071800000001");
        assertThat(image.substring(16, 18)).isEqualTo("01");
        assertThat(image.substring(18, 22)).isEqualTo("0005");
        assertThat(image.substring(22, 32)).isEqualTo("System    ");
        assertThat(image.substring(32, 132))
                .isEqualTo("Int. for a/c 00000000011" + " ".repeat(76));
        assertThat(image.substring(132, 143)).isEqualTo("0000000104A").hasSize(11);
        assertThat(TranRecord.TRAN_AMT_OFFSET).isEqualTo(132);
        assertThat(TranRecord.TRAN_AMT_LENGTH).isEqualTo(11);
        assertThat(image.substring(143, 152)).isEqualTo("000000000");
        assertThat(image.substring(152, 202)).isEqualTo(" ".repeat(50));
        assertThat(image.substring(202, 252)).isEqualTo(" ".repeat(50));
        assertThat(image.substring(252, 262)).isEqualTo(" ".repeat(10));
        assertThat(image.substring(262, 278)).isEqualTo("4444333322221111");
        assertThat(image.substring(278, 304)).isEqualTo("2022-07-18-12.34.56.780000").hasSize(26);
        assertThat(image.substring(304, 330)).isEqualTo("2022-07-18-12.34.56.780000").hasSize(26);
        assertThat(image.substring(330, 350)).isEqualTo(" ".repeat(20));
        assertThat(TranRecord.FILLER_OFFSET + TranRecord.FILLER_LENGTH)
                .isEqualTo(TranRecord.RECORD_LENGTH);
    }

    @Test
    void db2TimestampHasThreeHyphensAndThreeDots() {
        AccountInterestCalcJob job = job(database(), bindings(), new CapturedSysout());
        String ts = job.db2FormatTimestamp();
        assertThat(ts).hasSize(26).isEqualTo("2022-07-18-12.34.56.780000");
        assertThat(ts.chars().filter(c -> c == '-').count()).isEqualTo(3L);
        assertThat(ts.chars().filter(c -> c == '.').count()).isEqualTo(3L);
        assertThat(ts.charAt(10)).isEqualTo('-');
        assertThat(job.currentDate().image()).hasSize(21).isEqualTo("2022071812345678+0000");
        assertThat(job.currentDate().mil()).isEqualTo("78");
        assertThat(job.currentDate().rest()).isEqualTo("+0000");
    }

    @Test
    void cobolTimestampRejectsAWrongWidthComponent() {
        assertThatIllegalArgumentException().isThrownBy(() ->
                new AccountInterestCalcJob.CobolTimestamp("22", "07", "18", "12", "34", "56", "78",
                        "+0000"));
    }

    @Test
    void accountBreakPostsInterestZeroesBothCyclesThenRewrites() {
        JdbcTemplate t = database();
        seed(t, TCATBAL_DS, tcatbalImage(11L, "01", 1, "1000.00"));
        seed(t, TCATBAL_DS, tcatbalImage(22L, "01", 1, "2000.00"));
        seed(t, ACCT_DS, acctImage(11L, "500.00", "A000000000"));
        seed(t, ACCT_DS, acctImage(22L, "700.00", "A000000000"));
        seed(t, XREF_DS, xrefImage("4444333322221111", 1, 11L));
        seed(t, XREF_DS, xrefImage("4444333322222222", 2, 22L));
        seed(t, XREF_AIX_DS, xrefImage("4444333322221111", 1, 11L));
        seed(t, XREF_AIX_DS, xrefImage("4444333322222222", 2, 22L));
        seed(t, DISCGRP_DS, discgrpImage("A000000000", "01", 1, "12.50"));
        CapturedSysout sysout = new CapturedSysout();

        long processed = job(t, bindings(), sysout).calculateInterest(PARM, sysout);
        assertThat(processed).isEqualTo(2L);

        List<String> accounts = rows(t, ACCT_DS);
        assertThat(accounts).hasSize(2).allSatisfy(image -> assertThat(image).hasSize(300));
        AccountRecord first = accounts.stream().map(i -> AccountRecord.decode(i, ASCII))
                .filter(a -> a.getAcctId() == 11L).findFirst().orElseThrow();
        AccountRecord second = accounts.stream().map(i -> AccountRecord.decode(i, ASCII))
                .filter(a -> a.getAcctId() == 22L).findFirst().orElseThrow();

        assertThat(first.getAcctCurrBal()).isEqualTo(new BigDecimal("510.41"));
        assertThat(first.getAcctCurrCycCredit()).isEqualTo(new BigDecimal("0.00"));
        assertThat(first.getAcctCurrCycDebit()).isEqualTo(new BigDecimal("0.00"));

        assertThat(second.getAcctCurrBal()).isEqualTo(new BigDecimal("700.00"));
        assertThat(second.getAcctCurrCycCredit()).isEqualTo(new BigDecimal("111.11"));
        assertThat(second.getAcctCurrCycDebit()).isEqualTo(new BigDecimal("222.22"));

        List<String> written = rows(t, SYSTRAN_DS);
        assertThat(written).hasSize(2);
        assertThat(written.stream().map(i -> TranRecord.decode(i, ASCII).tranId()))
                .containsExactly("2022071800000001", "2022071800000002");
    }

    @Test
    void suffixCountsAcrossAccountBoundariesAndNeverResets() {
        JdbcTemplate t = database();
        seed(t, TCATBAL_DS, tcatbalImage(11L, "01", 1, "1000.00"));
        seed(t, TCATBAL_DS, tcatbalImage(11L, "01", 2, "1000.00"));
        seed(t, TCATBAL_DS, tcatbalImage(22L, "01", 1, "1000.00"));
        seed(t, ACCT_DS, acctImage(11L, "0.00", "A000000000"));
        seed(t, ACCT_DS, acctImage(22L, "0.00", "A000000000"));
        seed(t, XREF_DS, xrefImage("4444333322221111", 1, 11L));
        seed(t, XREF_DS, xrefImage("4444333322222222", 2, 22L));
        seed(t, XREF_AIX_DS, xrefImage("4444333322221111", 1, 11L));
        seed(t, XREF_AIX_DS, xrefImage("4444333322222222", 2, 22L));
        seed(t, DISCGRP_DS, discgrpImage("A000000000", "01", 1, "12.50"));
        seed(t, DISCGRP_DS, discgrpImage("A000000000", "01", 2, "12.50"));
        CapturedSysout sysout = new CapturedSysout();

        job(t, bindings(), sysout).calculateInterest(PARM, sysout);

        assertThat(rows(t, SYSTRAN_DS).stream().map(i -> TranRecord.decode(i, ASCII).tranId()))
                .containsExactly("2022071800000001", "2022071800000002", "2022071800000003");
        AccountRecord first = rows(t, ACCT_DS).stream().map(i -> AccountRecord.decode(i, ASCII))
                .filter(a -> a.getAcctId() == 11L).findFirst().orElseThrow();
        assertThat(first.getAcctCurrBal()).isEqualTo(new BigDecimal("20.82"));
    }

    @Test
    void theWrittenAmountIsThePerRecordInterestAndNotTheAccumulatedTotal() {
        JdbcTemplate t = database();
        seed(t, TCATBAL_DS, tcatbalImage(11L, "01", 1, "1000.00"));
        seed(t, TCATBAL_DS, tcatbalImage(11L, "01", 2, "99.99"));
        seed(t, TCATBAL_DS, tcatbalImage(22L, "01", 1, "0.00"));
        seed(t, ACCT_DS, acctImage(11L, "500.00", "A000000000"));
        seed(t, ACCT_DS, acctImage(22L, "700.00", "A000000000"));
        seed(t, XREF_DS, xrefImage("4444333322221111", 1, 11L));
        seed(t, XREF_DS, xrefImage("4444333322222222", 2, 22L));
        seed(t, XREF_AIX_DS, xrefImage("4444333322221111", 1, 11L));
        seed(t, XREF_AIX_DS, xrefImage("4444333322222222", 2, 22L));
        seed(t, DISCGRP_DS, discgrpImage("A000000000", "01", 1, "12.50"));
        seed(t, DISCGRP_DS, discgrpImage("A000000000", "01", 2, "15.00"));
        CapturedSysout sysout = new CapturedSysout();

        long processed = job(t, bindings(), sysout).calculateInterest(PARM, sysout);
        assertThat(processed).isEqualTo(3L);

        List<BigDecimal> amounts = rows(t, SYSTRAN_DS).stream()
                .map(image -> TranRecord.decode(image, ASCII).tranAmt())
                .toList();
        assertThat(amounts).containsExactly(new BigDecimal("10.41"), new BigDecimal("1.24"),
                new BigDecimal("0.00"));
        assertThat(amounts).allSatisfy(amount -> assertThat(amount.scale()).isEqualTo(2));
        assertThat(amounts).doesNotContain(new BigDecimal("11.65"));
        assertThat(amounts).doesNotContain(new BigDecimal("1.25"));

        AccountRecord broken = rows(t, ACCT_DS).stream().map(image -> AccountRecord.decode(image, ASCII))
                .filter(account -> account.getAcctId() == 11L).findFirst().orElseThrow();
        assertThat(broken.getAcctCurrBal()).isEqualTo(new BigDecimal("511.65"));
    }

    @Test
    void theDisclosureKeyPlacesTheCategoryAndTypeInTheirDeclaredSpans() {
        assertThat(DisclosureGroupRecord.DIS_ACCT_GROUP_ID_OFFSET).isZero();
        assertThat(DisclosureGroupRecord.DIS_ACCT_GROUP_ID_LENGTH).isEqualTo(10);
        assertThat(DisclosureGroupRecord.DIS_TRAN_TYPE_CD_OFFSET).isEqualTo(10);
        assertThat(DisclosureGroupRecord.DIS_TRAN_TYPE_CD_LENGTH).isEqualTo(2);
        assertThat(DisclosureGroupRecord.DIS_TRAN_CAT_CD_OFFSET).isEqualTo(12);
        assertThat(DisclosureGroupRecord.DIS_TRAN_CAT_CD_LENGTH).isEqualTo(4);

        Doubles doubles = new Doubles().withOneRecord();
        doubles.job().calculateInterest(PARM, doubles.sysout);
        assertThat(doubles.discgrp.keysRead)
                .containsExactly("A000000000" + "01" + "0001");
        assertThat(doubles.discgrp.keysRead.get(0)).hasSize(16);

        JdbcTemplate transposed = database();
        seed(transposed, TCATBAL_DS, tcatbalImage(11L, "02", 1, "1000.00"));
        seed(transposed, ACCT_DS, acctImage(11L, "0.00", "A000000000"));
        seed(transposed, XREF_DS, xrefImage("4444333322221111", 1, 11L));
        seed(transposed, XREF_AIX_DS, xrefImage("4444333322221111", 1, 11L));
        seed(transposed, DISCGRP_DS, discgrpImage("A000000000", "01", 2, "12.50"));
        seed(transposed, DISCGRP_DS, discgrpImage("DEFAULT", "02", 1, "6.00"));
        CapturedSysout transposedSysout = new CapturedSysout();
        job(transposed, bindings(), transposedSysout).calculateInterest(PARM, transposedSysout);
        assertThat(transposedSysout.lines())
                .contains(AccountInterestCalcJob.DISCLOSURE_GROUP_RECORD_MISSING);
        assertThat(TranRecord.decode(rows(transposed, SYSTRAN_DS).get(0), ASCII).tranAmt())
                .isEqualTo(new BigDecimal("5.00"));

        JdbcTemplate aligned = database();
        seed(aligned, TCATBAL_DS, tcatbalImage(11L, "02", 1, "1000.00"));
        seed(aligned, ACCT_DS, acctImage(11L, "0.00", "A000000000"));
        seed(aligned, XREF_DS, xrefImage("4444333322221111", 1, 11L));
        seed(aligned, XREF_AIX_DS, xrefImage("4444333322221111", 1, 11L));
        seed(aligned, DISCGRP_DS, discgrpImage("A000000000", "02", 1, "12.50"));
        CapturedSysout alignedSysout = new CapturedSysout();
        job(aligned, bindings(), alignedSysout).calculateInterest(PARM, alignedSysout);
        assertThat(alignedSysout.lines())
                .doesNotContain(AccountInterestCalcJob.DISCLOSURE_GROUP_RECORD_MISSING);
        assertThat(TranRecord.decode(rows(aligned, SYSTRAN_DS).get(0), ASCII).tranAmt())
                .isEqualTo(new BigDecimal("10.41"));
    }

    @Test
    void aNonDateShapedParmDateIsConcatenatedVerbatim() {
        JdbcTemplate t = singleAccountDatabase();
        CapturedSysout sysout = new CapturedSysout();

        job(t, bindings(), sysout).calculateInterest("XXXXXXXXXX", sysout);

        assertThat(TranRecord.decode(rows(t, SYSTRAN_DS).get(0), ASCII).tranId())
                .isEqualTo("XXXXXXXXXX000001")
                .hasSize(TranRecord.TRAN_ID_LENGTH);
    }

    @Test
    void theOnlyDeclaredJobParameterIsTheStringParmDate() {
        AccountInterestCalcJob subject = job(database(), bindings(), new CapturedSysout());

        JobParameters declared = subject.jobParameters();
        assertThat(declared.getParameters()).hasSize(1)
                .containsOnlyKeys(BatchConfig.PARM_DATE_PARAMETER);
        assertThat(declared.getParameter(BatchConfig.PARM_DATE_PARAMETER).getType())
                .isEqualTo(String.class);
        assertThat(declared.getString(BatchConfig.PARM_DATE_PARAMETER)).isEqualTo(PARM);
        assertThat(subject.declaredParmDate()).isEqualTo(PARM)
                .hasSize(AccountInterestCalcJob.PARM_DATE_WIDTH);

        JdbcTemplate t = database();
        DatasetBindings b = bindings();
        AccountInterestCalcJob nonDate = new AccountInterestCalcJob(
                scaffolding(contracts(
                        new StepContract(AccountInterestCalcJob.STEP_NAME,
                                AccountInterestCalcJob.PROGRAM_ID, false),
                        List.of(new JobParameterContract(BatchConfig.PARM_DATE_PARAMETER, "string",
                                "XXXXXXXXXX"))),
                        b),
                new TranCatBalRepository(t, b, ASCII, RecordImageForm.CHARACTER),
                new AccountRepository(t, b, ASCII, RecordImageForm.CHARACTER),
                new CardXrefRepository(t, b, ASCII, RecordImageForm.CHARACTER),
                new TransactionRepository(t, b, ASCII, RecordImageForm.CHARACTER, ORDINAL),
                new ScriptedDisclosureGroupAccess(),
                mockedUnitOfWork(),
                new PresentBean<>(new CapturedSysout()), FIXED);
        assertThat(nonDate.declaredParmDate()).isEqualTo("XXXXXXXXXX")
                .hasSize(AccountInterestCalcJob.PARM_DATE_WIDTH);
    }

    @Test
    void tranDescKeepsThePreviousIterationsTailBytes() {
        JdbcTemplate t = database();
        seed(t, TCATBAL_DS, tcatbalImage(11L, "01", 1, "1000.00"));
        seed(t, ACCT_DS, acctImage(11L, "0.00", "A000000000"));
        seed(t, XREF_DS, xrefImage("4444333322221111", 1, 11L));
        seed(t, XREF_AIX_DS, xrefImage("4444333322221111", 1, 11L));
        seed(t, DISCGRP_DS, discgrpImage("A000000000", "01", 1, "12.50"));
        CapturedSysout sysout = new CapturedSysout();
        AccountInterestCalcJob job = job(t, bindings(), sysout);
        job.calculateInterest(PARM, sysout);

        String desc = TranRecord.decode(rows(t, SYSTRAN_DS).get(0), ASCII).tranDesc();
        assertThat(desc).hasSize(100);
        assertThat(desc.substring(0, 24)).isEqualTo("Int. for a/c 00000000011");
        assertThat(desc.substring(24)).isEqualTo(" ".repeat(76));

        TranRecord dirty = new TranRecord(ASCII);
        dirty.moveTranDesc("X".repeat(100));
        dirty.stringIntoTranDesc("Int. for a/c ", "00000000011");
        assertThat(dirty.tranDesc()).isEqualTo("Int. for a/c 00000000011" + "X".repeat(76));
    }

    @Test
    void aMissingGroupRetriesWithTheDefaultGroupPaddedToTen() {
        JdbcTemplate t = database();
        seed(t, TCATBAL_DS, tcatbalImage(11L, "01", 1, "1000.00"));
        seed(t, ACCT_DS, acctImage(11L, "0.00", "ZZZZZZZZZZ"));
        seed(t, XREF_DS, xrefImage("4444333322221111", 1, 11L));
        seed(t, XREF_AIX_DS, xrefImage("4444333322221111", 1, 11L));
        seed(t, DISCGRP_DS, discgrpImage("DEFAULT", "01", 1, "6.00"));
        CapturedSysout sysout = new CapturedSysout();

        job(t, bindings(), sysout).calculateInterest(PARM, sysout);

        assertThat(sysout.lines()).contains(AccountInterestCalcJob.DISCLOSURE_GROUP_RECORD_MISSING,
                AccountInterestCalcJob.TRY_WITH_DEFAULT_GROUP_CODE);
        assertThat(TranRecord.decode(rows(t, SYSTRAN_DS).get(0), ASCII).tranAmt())
                .isEqualTo(new BigDecimal("5.00"));
    }

    @Test
    void aBlankAccountGroupIdFallsBackToTheDefaultGroupAtTheFixtureRate() {
        JdbcTemplate t = database();
        seed(t, TCATBAL_DS, tcatbalImage(11L, "01", 1, "1000.00"));
        seed(t, ACCT_DS, acctImage(11L, "0.00", "          "));
        seed(t, XREF_DS, xrefImage("4444333322221111", 1, 11L));
        seed(t, XREF_AIX_DS, xrefImage("4444333322221111", 1, 11L));
        seed(t, DISCGRP_DS, discgrpImage("A000000000", "01", 1, "99.00"));
        seed(t, DISCGRP_DS, discgrpImage(DisclosureGroupRecord.DEFAULT_ACCT_GROUP_ID, "01", 1, "15.00"));
        CapturedSysout sysout = new CapturedSysout();

        job(t, bindings(), sysout).calculateInterest(PARM, sysout);

        assertThat(sysout.lines()).containsSubsequence(
                AccountInterestCalcJob.DISCLOSURE_GROUP_RECORD_MISSING,
                AccountInterestCalcJob.TRY_WITH_DEFAULT_GROUP_CODE);
        assertThat(TranRecord.decode(rows(t, SYSTRAN_DS).get(0), ASCII).tranAmt())
                .isEqualTo(new BigDecimal("12.50"));
        assertThat(DisclosureGroupRecord.DEFAULT_ACCT_GROUP_ID).isEqualTo("DEFAULT");
        assertThat(DisclosureGroupRecord
                .decode(discgrpImage(DisclosureGroupRecord.DEFAULT_ACCT_GROUP_ID, "01", 1, "15.00"),
                        ASCII)
                .disAcctGroupId()).isEqualTo("DEFAULT   ")
                .hasSize(DisclosureGroupRecord.DIS_ACCT_GROUP_ID_LENGTH);
    }

    @Test
    void aMissingDefaultGroupAbendsWithReturnCodeTwelve() {
        JdbcTemplate t = database();
        seed(t, TCATBAL_DS, tcatbalImage(11L, "01", 1, "1000.00"));
        seed(t, ACCT_DS, acctImage(11L, "0.00", "ZZZZZZZZZZ"));
        seed(t, XREF_DS, xrefImage("4444333322221111", 1, 11L));
        seed(t, XREF_AIX_DS, xrefImage("4444333322221111", 1, 11L));
        CapturedSysout sysout = new CapturedSysout();
        AccountInterestCalcJob job = job(t, bindings(), sysout);

        assertThatExceptionOfType(AbendException.class)
                .isThrownBy(() -> job.calculateInterest(PARM, sysout))
                .satisfies(abend -> {
                    assertThat(abend.getReturnCode()).isEqualTo(12);
                    assertThat(abend.getReturnCode())
                            .isEqualTo(AccountInterestCalcJob.APPL_RESULT_FATAL);
                    assertThat(abend.getProgram()).isEqualTo("CBACT04C");
                    assertThat(abend.getAbendCode()).hasValue(999);
                    assertThat(abend.getAbendCode()).hasValue(AbendException.STANDARD_ABEND_CODE);
                    assertThat(abend.getTiming()).hasValue(0);
                    assertThat(abend.getTiming()).hasValue(AbendException.STANDARD_TIMING);
                    assertThat(abend.hasAbendCode()).isTrue();
                    assertThat(abend.hasTiming()).isTrue();
                });
        assertThat(sysout.lines()).containsSubsequence(
                AccountInterestCalcJob.ERROR_READING_DEFAULT_DISCGRP,
                FileStatus.toDisplayLine(FileStatus.NOT_FOUND),
                AbendException.ABEND_DISPLAY_TEXT);
        assertThat(sysout.lines()).last().isEqualTo(AbendException.ABEND_DISPLAY_TEXT);
        assertThat(sysout.lines()).doesNotContain(AccountInterestCalcJob.END_OF_EXECUTION);
    }

    @Test
    void aMissingAccountEmitsBothLinesThenAbends() {
        JdbcTemplate t = database();
        seed(t, TCATBAL_DS, tcatbalImage(99L, "01", 1, "1000.00"));
        seed(t, DISCGRP_DS, discgrpImage("A000000000", "01", 1, "12.50"));
        CapturedSysout sysout = new CapturedSysout();
        AccountInterestCalcJob job = job(t, bindings(), sysout);

        assertThatExceptionOfType(AbendException.class)
                .isThrownBy(() -> job.calculateInterest(PARM, sysout));

        List<String> lines = sysout.lines();
        int notFound = lines.indexOf(AccountInterestCalcJob.ACCOUNT_NOT_FOUND_PREFIX + "00000000099");
        int error = lines.indexOf(AccountInterestCalcJob.ERROR_READING_ACCTFILE);
        assertThat(notFound).isNotNegative();
        assertThat(error).isGreaterThan(notFound);
    }

    @Test
    void aMissingCrossReferenceAbendsAfterItsNotFoundLine() {
        JdbcTemplate t = database();
        seed(t, TCATBAL_DS, tcatbalImage(11L, "01", 1, "1000.00"));
        seed(t, ACCT_DS, acctImage(11L, "0.00", "A000000000"));
        seed(t, DISCGRP_DS, discgrpImage("A000000000", "01", 1, "12.50"));
        CapturedSysout sysout = new CapturedSysout();
        AccountInterestCalcJob job = job(t, bindings(), sysout);

        assertThatExceptionOfType(AbendException.class)
                .isThrownBy(() -> job.calculateInterest(PARM, sysout));
        assertThat(sysout.lines()).contains(
                AccountInterestCalcJob.ACCOUNT_NOT_FOUND_PREFIX + "00000000011",
                AccountInterestCalcJob.ERROR_READING_XREFFILE);
    }

    @Test
    void theRunEmitsTheBannersAndOneRawRecordImagePerRecord() {
        JdbcTemplate t = singleAccountDatabase();
        CapturedSysout sysout = new CapturedSysout();
        job(t, bindings(), sysout).calculateInterest(PARM, sysout);

        List<String> lines = sysout.lines();
        assertThat(lines).hasSize(3);
        assertThat(lines.get(0)).isEqualTo("START OF EXECUTION OF PROGRAM CBACT04C");
        assertThat(lines.get(1)).hasSize(50)
                .isEqualTo(tcatbalImage(11L, "01", 1, "1000.00"));
        assertThat(lines.get(2)).isEqualTo("END OF EXECUTION OF PROGRAM CBACT04C");
    }

    @Test
    void anEmptyBrowseEmitsOnlyTheTwoBanners() {
        JdbcTemplate t = database();
        CapturedSysout sysout = new CapturedSysout();
        long processed = job(t, bindings(), sysout).calculateInterest(PARM, sysout);
        assertThat(processed).isZero();
        assertThat(sysout.lines()).containsExactly(AccountInterestCalcJob.START_OF_EXECUTION,
                AccountInterestCalcJob.END_OF_EXECUTION);
    }

    @Test
    void theUnreachableElseIsPresentAndReachableThroughAThirdFlagValue() {
        JdbcTemplate t = singleAccountDatabase();
        CapturedSysout sysout = new CapturedSysout();
        AccountInterestCalcJob job = job(t, bindings(), sysout);
        InterestCalculationRun run = job.newRun(PARM, sysout);
        run.openFiles();
        try {
            run.workingStorage().moveEndOfFile("X");
            assertThat(run.workingStorage().endOfFileIsYes()).isFalse();
            assertThat(run.workingStorage().endOfFileIsNo()).isFalse();
            assertThatExceptionOfType(AbendException.class).isThrownBy(run::updateAccount);
            assertThat(sysout.lines()).contains(AccountInterestCalcJob.ERROR_REWRITING_ACCTFILE);
        } finally {
            run.release();
        }
    }

    @Test
    void workingStorageReproducesEveryDeclaredField() {
        WorkingStorage ws = new WorkingStorage();
        assertThat(ws.applResult()).isZero();
        assertThat(ws.applAok()).isTrue();
        assertThat(ws.applEof()).isFalse();
        assertThat(ws.endOfFileFlag()).isEqualTo("N");
        assertThat(ws.endOfFileIsNo()).isTrue();
        assertThat(ws.firstTimeFlag()).isEqualTo("Y");
        assertThat(ws.firstTimeIsYes()).isTrue();
        assertThat(ws.lastAcctNum()).isEqualTo("           ");
        assertThat(ws.monthlyInterest()).isEqualTo(new BigDecimal("0.00"));
        assertThat(ws.totalInterest()).isEqualTo(new BigDecimal("0.00"));
        assertThat(ws.recordCount()).isZero();
        assertThat(ws.tranidSuffix()).isZero();

        ws.moveToApplResult(16);
        assertThat(ws.applEof()).isTrue();
        ws.addOneToRecordCount();
        assertThat(ws.recordCount()).isEqualTo(1L);
        ws.addOneToTranidSuffix();
        assertThat(ws.tranidSuffix()).isEqualTo(1L);
        ws.moveToMonthlyInterest(new BigDecimal("1.239"));
        assertThat(ws.monthlyInterest()).isEqualTo(new BigDecimal("1.23"));
        ws.addToTotalInterest(ws.monthlyInterest());
        ws.addToTotalInterest(ws.monthlyInterest());
        assertThat(ws.totalInterest()).isEqualTo(new BigDecimal("2.46"));
        ws.moveZeroToTotalInterest();
        assertThat(ws.totalInterest()).isEqualTo(new BigDecimal("0.00"));
        ws.moveFirstTime("N");
        assertThat(ws.firstTimeIsYes()).isFalse();
        ws.moveLastAcctNum("00000000011");
        assertThat(ws.lastAcctNum()).isEqualTo("00000000011");

        assertThatIllegalArgumentException().isThrownBy(() -> ws.moveEndOfFile("YY"));
        assertThatIllegalArgumentException().isThrownBy(() -> ws.moveFirstTime(""));
        assertThatIllegalArgumentException().isThrownBy(() -> ws.moveLastAcctNum("1"));
    }

    @Test
    void theDisclosureGroupSeamReportsFoundAndNotFound() {
        JdbcTemplate t = database();
        DatasetBindings b = bindings();
        seed(t, DISCGRP_DS, discgrpImage("A000000000", "01", 1, "12.50"));
        DisclosureGroupAccess access = new DisclosureGroupRepository(t, b, ASCII,
                RecordImageForm.CHARACTER);
        assertThat(access.datasetName()).isEqualTo(DISCGRP_DS);

        try (var file = access.open()) {
            assertThat(file.openStatus()).isEqualTo(FileStatus.OK);
            var found = file.readByKey("A00000000001" + "0001");
            assertThat(found.isFound()).isTrue();
            assertThat(found.requireRecord().disIntRate()).isEqualTo(new BigDecimal("12.50"));
            var missing = file.readByKey("ZZZZZZZZZZ01" + "0001");
            assertThat(missing.isNotFound()).isTrue();
            assertThat(missing.status()).isEqualTo(FileStatus.NOT_FOUND);
            assertThatIllegalStateException().isThrownBy(missing::requireRecord);
            assertThat(file.closeFile()).isEqualTo(FileStatus.OK);
        }
    }

    @Test
    void theSeamRejectsAKeyOfTheWrongWidth() {
        JdbcTemplate t = database();
        DisclosureGroupAccess access = new DisclosureGroupRepository(t, bindings(),
                ASCII, RecordImageForm.CHARACTER);
        var file = access.open();
        assertThatIllegalArgumentException().isThrownBy(() -> file.readByKey("A000000000010001X"));
        file.close();
    }

    @Test
    void aSeventeenByteDisclosureKeyIsRefusedAtConstruction() {
        JdbcTemplate t = database();
        assertThatIllegalStateException().isThrownBy(() ->
                new DisclosureGroupRepository(t, bindings(50, 17), ASCII,
                        RecordImageForm.CHARACTER))
                .withMessageContaining("key-length must be 16");
    }

    @Test
    void aWrongRecordWidthIsRefusedAtConstruction() {
        JdbcTemplate t = database();
        assertThatIllegalStateException().isThrownBy(() ->
                new DisclosureGroupRepository(t, bindings(60, 16), ASCII,
                        RecordImageForm.CHARACTER));
    }

    @Test
    void theChunkDelegateRunsTheWholeProgram() throws Exception {
        JdbcTemplate t = singleAccountDatabase();
        CapturedSysout sysout = new CapturedSysout();
        AccountInterestCalcJob job = job(t, bindings(), sysout);
        ChunkDelegate delegate = job.newChunkDelegate();

        assertThat(delegate.parmDate()).isEqualTo(PARM);
        delegate.open(new org.springframework.batch.item.ExecutionContext());
        TranCatBalRecord first = delegate.read();
        assertThat(first).isNotNull();
        RecordOutcome outcome = delegate.process(first);
        assertThat(outcome.interestComputed()).isTrue();
        assertThat(outcome.feesComputed()).isTrue();
        assertThat(outcome.accountRewritten()).isFalse();
        assertThat(outcome.monthlyInterest()).isEqualTo(new BigDecimal("10.41"));
        delegate.write(org.springframework.batch.item.Chunk.of(outcome));
        assertThat(delegate.read()).isNull();
        delegate.close();

        assertThat(delegate.transactionsWritten()).isEqualTo(1L);
        assertThat(delegate.accountsRewritten()).isZero();
        assertThat(sysout.lines()).first().isEqualTo(AccountInterestCalcJob.START_OF_EXECUTION);
        assertThat(sysout.lines()).last().isEqualTo(AccountInterestCalcJob.END_OF_EXECUTION);
        assertThat(delegate.run()).isNull();
    }

    @Test
    void theChunkDelegateRefusesToReadBeforeItIsOpened() {
        AccountInterestCalcJob job = job(database(), bindings(), new CapturedSysout());
        ChunkDelegate delegate = job.newChunkDelegate();
        assertThatIllegalStateException().isThrownBy(delegate::read);
        delegate.close();
    }

    @Test
    void theJobAndStepBeansWire() {
        AccountInterestCalcJob job = job(database(), bindings(), new CapturedSysout());
        assertThat(job.accountInterestCalcJob().getName())
                .isEqualTo(AccountInterestCalcJob.JOB_NAME);
        assertThat(job.accountInterestCalcStep().getName()).isEqualTo("STEP15");
        assertThat(job.jobParameters().getString(BatchConfig.PARM_DATE_PARAMETER)).isEqualTo(PARM);
        assertThat(job.stepContract().program()).isEqualTo("CBACT04C");
        assertThat(job.declaredParmDate()).isEqualTo(PARM).hasSize(10);
        assertThat(job.datasetCharset()).isEqualTo(ASCII);
        assertThat(job.clock()).isSameAs(FIXED);
        assertThat(job.sysoutSink()).isNotNull();
    }

    @Test
    void anAbsentTransactionDestinationAbendsFromTheOpen() {
        JdbcTemplate t = database();
        t.execute("DROP TABLE \"" + SYSTRAN_DS + "\"");
        seed(t, TCATBAL_DS, tcatbalImage(11L, "01", 1, "1000.00"));
        seed(t, ACCT_DS, acctImage(11L, "0.00", "A000000000"));
        seed(t, XREF_DS, xrefImage("4444333322221111", 1, 11L));
        seed(t, XREF_AIX_DS, xrefImage("4444333322221111", 1, 11L));
        seed(t, DISCGRP_DS, discgrpImage("A000000000", "01", 1, "12.50"));
        CapturedSysout sysout = new CapturedSysout();
        AccountInterestCalcJob job = job(t, bindings(), sysout);

        assertThatExceptionOfType(AbendException.class)
                .isThrownBy(() -> job.calculateInterest(PARM, sysout))
                .satisfies(abend -> {
                    assertThat(abend.getReturnCode()).isEqualTo(12);
                    assertThat(abend.getProgram()).isEqualTo("CBACT04C");
                });
        assertThat(sysout.lines()).contains(AccountInterestCalcJob.ERROR_OPENING_TRANFILE,
                AbendException.ABEND_DISPLAY_TEXT);
        assertThat(sysout.lines()).doesNotContain(AccountInterestCalcJob.END_OF_EXECUTION);
    }

    @Test
    void theSystranGenerationClearIsDurable() {
        JdbcTemplate t = database();
        seed(t, SYSTRAN_DS, "X".repeat(350));
        AccountInterestCalcJob job = job(t, bindings(), new CapturedSysout());

        TransactionTemplate enclosing =
                new TransactionTemplate(new JdbcTransactionManager(t.getDataSource()));
        assertThatCode(() -> enclosing.execute(status -> {
            job.newRun(PARM, new CapturedSysout()).tranfileOpen();
            status.setRollbackOnly();
            return null;
        })).doesNotThrowAnyException();

        assertThat(rows(t, SYSTRAN_DS))
                .as("the previous generation's record must be gone: a run that appended to it would emit "
                        + "a transaction set that is not its own")
                .isEmpty();
    }

    @Test
    void aGatedOrMisnamedStepIsRefused() {
        JdbcTemplate t = database();
        DatasetBindings b = bindings();
        JobContracts gated = contracts(new StepContract("STEP15", "CBACT04C", true),
                List.of(new JobParameterContract(BatchConfig.PARM_DATE_PARAMETER, "string", PARM)));
        assertThatIllegalStateException().isThrownBy(() -> new AccountInterestCalcJob(
                scaffolding(gated, b),
                new TranCatBalRepository(t, b, ASCII, RecordImageForm.CHARACTER),
                new AccountRepository(t, b, ASCII, RecordImageForm.CHARACTER),
                new CardXrefRepository(t, b, ASCII, RecordImageForm.CHARACTER),
                new TransactionRepository(t, b, ASCII, RecordImageForm.CHARACTER, ORDINAL),
                new DisclosureGroupRepository(t, b, ASCII,
                        RecordImageForm.CHARACTER),
                unitOfWork(t),
                new PresentBean<>(new CapturedSysout()), FIXED));

        JobContracts wrongProgram = contracts(new StepContract("STEP15", "CBACT01C", false),
                List.of(new JobParameterContract(BatchConfig.PARM_DATE_PARAMETER, "string", PARM)));
        assertThatIllegalStateException().isThrownBy(() -> new AccountInterestCalcJob(
                scaffolding(wrongProgram, b),
                new TranCatBalRepository(t, b, ASCII, RecordImageForm.CHARACTER),
                new AccountRepository(t, b, ASCII, RecordImageForm.CHARACTER),
                new CardXrefRepository(t, b, ASCII, RecordImageForm.CHARACTER),
                new TransactionRepository(t, b, ASCII, RecordImageForm.CHARACTER, ORDINAL),
                new DisclosureGroupRepository(t, b, ASCII,
                        RecordImageForm.CHARACTER),
                unitOfWork(t),
                new PresentBean<>(new CapturedSysout()), FIXED));

        JobContracts noParm = contracts(new StepContract("STEP15", "CBACT04C", false), List.of());
        assertThatIllegalStateException().isThrownBy(() -> new AccountInterestCalcJob(
                scaffolding(noParm, b),
                new TranCatBalRepository(t, b, ASCII, RecordImageForm.CHARACTER),
                new AccountRepository(t, b, ASCII, RecordImageForm.CHARACTER),
                new CardXrefRepository(t, b, ASCII, RecordImageForm.CHARACTER),
                new TransactionRepository(t, b, ASCII, RecordImageForm.CHARACTER, ORDINAL),
                new DisclosureGroupRepository(t, b, ASCII,
                        RecordImageForm.CHARACTER),
                unitOfWork(t),
                new PresentBean<>(new CapturedSysout()), FIXED));
    }

    @Test
    @DisplayName("a second step declared beside STEP15 is refused: INTCALC.jcl has one EXEC, and "
            + "running it twice would post interest twice")
    void anAddedStepIsRefused() {
        JdbcTemplate t = database();
        DatasetBindings b = bindings();
        JobContracts twoSteps = contracts(
                List.of(new StepContract(AccountInterestCalcJob.STEP_NAME,
                                AccountInterestCalcJob.PROGRAM_ID, false),
                        new StepContract("STEP16", AccountInterestCalcJob.PROGRAM_ID, false)),
                List.of(new JobParameterContract(BatchConfig.PARM_DATE_PARAMETER, "string", PARM)),
                TRANSACT_ALIASED_TO_SYSTRAN);

        assertThatIllegalStateException().isThrownBy(() -> new AccountInterestCalcJob(
                scaffolding(twoSteps, b),
                new TranCatBalRepository(t, b, ASCII, RecordImageForm.CHARACTER),
                new AccountRepository(t, b, ASCII, RecordImageForm.CHARACTER),
                new CardXrefRepository(t, b, ASCII, RecordImageForm.CHARACTER),
                new TransactionRepository(t, b, ASCII, RecordImageForm.CHARACTER, ORDINAL),
                new DisclosureGroupRepository(t, b, ASCII,
                        RecordImageForm.CHARACTER),
                unitOfWork(t),
                new PresentBean<>(new CapturedSysout()), FIXED))
                .withMessageContaining("does not declare the step sequence of app/jcl/INTCALC.jcl:22")
                .withMessageContaining("configured: [STEP15/CBACT04C, STEP16/CBACT04C]")
                .withMessageContaining("required:   [STEP15/CBACT04C]");
    }

    @Test
    @DisplayName("the Clock is a required collaborator, not an optional one with a system-clock default")
    void theClockIsRequired() {
        JdbcTemplate t = database();
        DatasetBindings b = bindings();

        assertThatNullPointerException().isThrownBy(() -> new AccountInterestCalcJob(
                scaffolding(contracts(), b),
                new TranCatBalRepository(t, b, ASCII, RecordImageForm.CHARACTER),
                new AccountRepository(t, b, ASCII, RecordImageForm.CHARACTER),
                new CardXrefRepository(t, b, ASCII, RecordImageForm.CHARACTER),
                new TransactionRepository(t, b, ASCII, RecordImageForm.CHARACTER, ORDINAL),
                new DisclosureGroupRepository(t, b, ASCII,
                        RecordImageForm.CHARACTER),
                unitOfWork(t),
                new PresentBean<>(new CapturedSysout()), null))
                .withMessageContaining("A Clock is required")
                .withMessageContaining("CURRENT-DATE");

        assertThat(Arrays.stream(AccountInterestCalcJob.class.getDeclaredConstructors())
                .flatMap(constructor -> Arrays.stream(constructor.getParameterTypes()))
                .filter(Clock.class::equals)
                .count())
                .as("exactly one constructor parameter, and it is a Clock rather than a provider of one")
                .isEqualTo(1);
        assertThat(new Doubles().job().currentDate().image())
                .as("the injected clock is what CURRENT-DATE reads, so the rendering is reproducible")
                .isEqualTo(new Doubles().job().currentDate().image());
    }

    @Test
    @DisplayName("the shipped single-step sequence is what the class requires")
    void theShippedSequenceIsRequired() {
        assertThat(AccountInterestCalcJob.REQUIRED_STEPS)
                .containsExactly(new StepContract(AccountInterestCalcJob.STEP_NAME,
                        AccountInterestCalcJob.PROGRAM_ID, false));
        assertThat(contracts().get(AccountInterestCalcJob.JOB_KEY).steps())
                .isEqualTo(AccountInterestCalcJob.REQUIRED_STEPS);
    }

    @Test
    @DisplayName("every DD app/jcl/INTCALC.jcl declares is proven to name the repository's own dataset")
    void divergingStepDatasetsAreRefused() {
        JdbcTemplate t = database();
        for (String ddName : List.of(AccountInterestCalcJob.ACCTFILE_DD_NAME,
                AccountInterestCalcJob.XREFFILE_DD_NAME,
                AccountInterestCalcJob.XREFFIL1_DD_NAME)) {
            DatasetBindings diverging = bindings();
            DatasetBinding declared = diverging.binding(ddName);
            diverging.put(ddName, new DatasetBinding("TEST.SOMETHING.ELSE", declared.organization(),
                    declared.gdg(), declared.recordFormat(), declared.blockSize(),
                    declared.recordLength(), declared.copybook(), declared.keyLength(),
                    declared.keyOffset(), declared.base(), declared.alternateKey()));
            DatasetBindings sound = bindings();

            assertThatIllegalStateException()
                    .as("DD %s must be proven against the repository it reads through", ddName)
                    .isThrownBy(() -> new AccountInterestCalcJob(
                            scaffolding(contracts(), diverging),
                            new TranCatBalRepository(t, sound, ASCII, RecordImageForm.CHARACTER),
                            new AccountRepository(t, sound, ASCII, RecordImageForm.CHARACTER),
                            new CardXrefRepository(t, sound, ASCII, RecordImageForm.CHARACTER),
                            new TransactionRepository(t, sound, ASCII, RecordImageForm.CHARACTER, ORDINAL),
                            new DisclosureGroupRepository(t, sound, ASCII,
                                    RecordImageForm.CHARACTER),
                            unitOfWork(t),
                            new PresentBean<>(new CapturedSysout()), FIXED))
                    .withMessageContaining(ddName)
                    .withMessageContaining("TEST.SOMETHING.ELSE");
        }
    }

    @Test
    @DisplayName("dropping the job-scoped TRANSACT alias is refused: it would write to the master")
    void aMissingTransactAliasIsRefused() {
        JdbcTemplate t = database();
        DatasetBindings b = bindings();

        assertThatIllegalStateException().isThrownBy(() -> new AccountInterestCalcJob(
                scaffolding(contracts(new StepContract(AccountInterestCalcJob.STEP_NAME,
                                AccountInterestCalcJob.PROGRAM_ID, false),
                        List.of(new JobParameterContract(BatchConfig.PARM_DATE_PARAMETER, "string",
                                PARM)),
                        Map.of()), b),
                new TranCatBalRepository(t, b, ASCII, RecordImageForm.CHARACTER),
                new AccountRepository(t, b, ASCII, RecordImageForm.CHARACTER),
                new CardXrefRepository(t, b, ASCII, RecordImageForm.CHARACTER),
                new TransactionRepository(t, b, ASCII, RecordImageForm.CHARACTER, ORDINAL),
                new DisclosureGroupRepository(t, b, ASCII,
                        RecordImageForm.CHARACTER),
                unitOfWork(t),
                new PresentBean<>(new CapturedSysout()), FIXED))
                .withMessageContaining(AccountInterestCalcJob.TRANSACT_DD_NAME)
                .withMessageContaining(TransactionRepository.SEQUENTIAL_OUTPUT_DD_NAME);
    }

    @Test
    void recordOutcomeEnforcesItsInvariants() {
        assertThatIllegalArgumentException().isThrownBy(() -> new RecordOutcome(1L, "k", false, true,
                false, true, CobolDecimal.monetaryZero(), "id"));
        assertThatIllegalArgumentException().isThrownBy(() -> new RecordOutcome(1L, "k", false, false,
                false, true, CobolDecimal.monetaryZero(), "id"));
        assertThatIllegalArgumentException().isThrownBy(() -> new RecordOutcome(1L, "k", false, true,
                true, true, BigDecimal.ONE, "id"));
        RecordOutcome ok = new RecordOutcome(1L, "k", false, true, true, true,
                new BigDecimal("1.00"), "id");
        assertThat(ok.recordNumber()).isEqualTo(1L);
    }

    @Test
    void theRealFixtureRowsDecodeAtTheDeclaredWidths() {
        List<String> discgrp = fixture("/fixtures/discgrp.txt");
        List<String> tcatbal = fixture("/fixtures/tcatbal.txt");
        assertThat(discgrp).hasSize(51).allSatisfy(row -> assertThat(row).hasSize(50));
        assertThat(tcatbal).hasSize(50).allSatisfy(row -> assertThat(row).hasSize(50));

        DisclosureGroupRecord firstGroup = DisclosureGroupRecord.decode(discgrp.get(0), ASCII);
        assertThat(firstGroup.disAcctGroupId()).isEqualTo("A000000000");
        assertThat(firstGroup.disGroupKey()).hasSize(16);
        assertThat(firstGroup.disIntRate()).isEqualTo(new BigDecimal("15.00"));

        TranCatBalRecord firstBalance = TranCatBalRecord.decode(tcatbal.get(0), ASCII);
        assertThat(firstBalance.tranCatKeyImage()).hasSize(17);
        assertThat(firstBalance.tranCatBal().scale()).isEqualTo(2);
    }

    private static List<String> fixture(String resource) {
        try (InputStream stream =
                     AccountInterestCalcJobTest.class.getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IllegalStateException("absent fixture " + resource);
            }
            return new String(stream.readAllBytes(), ASCII).lines().toList();
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private static final class ScriptedDisclosureGroupAccess
            implements AccountInterestCalcJob.DisclosureGroupAccess {
        private String openStatus = FileStatus.OK;

        private String closeStatus = FileStatus.OK;

        private final List<AccountInterestCalcJob.DisclosureGroupRead> scripted = new ArrayList<>();

        private final List<String> keysRead = new ArrayList<>();

        private int closes;

        private List<String> verbLog;

        @Override
        public String datasetName() {
            return DISCGRP_DS;
        }

        ScriptedDisclosureGroupAccess recordingVerbsInto(List<String> log) {
            this.verbLog = log;
            return this;
        }

        @Override
        public AccountInterestCalcJob.DisclosureGroupFile open() {
            return new ScriptedDisclosureGroupFile(this);
        }

        ScriptedDisclosureGroupAccess yielding(AccountInterestCalcJob.DisclosureGroupRead read) {
            scripted.add(read);
            return this;
        }

        private AccountInterestCalcJob.DisclosureGroupRead answer(String keyImage) {
            keysRead.add(keyImage);
            if (scripted.isEmpty()) {
                return AccountInterestCalcJob.DisclosureGroupRead.notFound();
            }
            int index = Math.min(keysRead.size() - 1, scripted.size() - 1);
            return scripted.get(index);
        }
    }

    private static final class ScriptedDisclosureGroupFile
            implements AccountInterestCalcJob.DisclosureGroupFile {
        private final ScriptedDisclosureGroupAccess access;

        ScriptedDisclosureGroupFile(ScriptedDisclosureGroupAccess access) {
            this.access = access;
        }

        @Override
        public String openStatus() {
            return access.openStatus;
        }

        @Override
        public AccountInterestCalcJob.DisclosureGroupRead readByKey(String keyImage) {
            return access.answer(keyImage);
        }

        @Override
        public String closeFile() {
            access.closes++;
            if (access.verbLog != null) {
                access.verbLog.add(AccountInterestCalcJob.DISCGRP_DD_NAME);
            }
            return access.closeStatus;
        }

        @Override
        public void close() {
            access.closes++;
        }
    }

    private static final class Doubles {
        final TranCatBalRepository tcatbalRepository = mock(TranCatBalRepository.class);
        final TranCatBalRepository.TranCatBalFile tcatbalFile =
                mock(TranCatBalRepository.TranCatBalFile.class);
        final AccountRepository accountRepository = mock(AccountRepository.class);
        final AccountRepository.AccountFile accountFile = mock(AccountRepository.AccountFile.class);
        final CardXrefRepository xrefRepository = mock(CardXrefRepository.class);
        final CardXrefRepository.BrowseCursor xrefCursor = mock(CardXrefRepository.BrowseCursor.class);
        final TransactionRepository transactionRepository = mock(TransactionRepository.class);
        final TransactionRepository.OutputFile transactionFile =
                mock(TransactionRepository.OutputFile.class);
        final ScriptedDisclosureGroupAccess discgrp = new ScriptedDisclosureGroupAccess();
        final CapturedSysout sysout = new CapturedSysout();

        Doubles() {
            when(accountRepository.datasetCharset()).thenReturn(ASCII);

            when(tcatbalRepository.open(TranCatBalRepository.OpenMode.INPUT)).thenReturn(tcatbalFile);
            when(accountRepository.open(AccountRepository.OpenMode.I_O)).thenReturn(accountFile);
            when(xrefRepository.addressing(any(), any(), any(), any())).thenReturn(xrefRepository);
            when(xrefRepository.openBrowse()).thenReturn(xrefCursor);
            when(transactionRepository.openOutput(any(), any())).thenReturn(transactionFile);

            when(tcatbalFile.openStatus()).thenReturn(FileStatus.OK);
            when(tcatbalFile.closeFile()).thenReturn(FileStatus.OK);
            when(accountFile.openStatus()).thenReturn(FileStatus.OK);
            when(accountFile.closeFile()).thenReturn(FileStatus.OK);
            when(xrefCursor.openStatus()).thenReturn(FileStatus.OK);
            when(xrefCursor.closeBrowse()).thenReturn(FileStatus.OK);
            when(transactionFile.openStatus()).thenReturn(FileStatus.OK);
            when(transactionFile.closeOutput()).thenReturn(FileStatus.OK);
            when(transactionFile.discardGeneration()).thenReturn(FileStatus.OK);

            when(tcatbalFile.readNext()).thenReturn(TranCatBalRepository.ReadResult.endOfFile());
        }

        Doubles withOneRecord() {
            TranCatBalRecord record = TranCatBalRecord.decode(
                    tcatbalImage(11L, "01", 1, "1000.00"), ASCII);
            when(tcatbalFile.readNext()).thenReturn(TranCatBalRepository.ReadResult.found(record),
                    TranCatBalRepository.ReadResult.endOfFile());
            when(accountFile.readByKey(anyLong())).thenReturn(AccountRepository.ReadResult.found(
                    AccountRecord.decode(acctImage(11L, "500.00", "A000000000"), ASCII)));
            when(xrefRepository.readByAccountIdViaAltIndex(anyLong()))
                    .thenReturn(xrefFound(
                            CardXrefRepository.BASE_DD_NAME,
                            new CardXrefRecord("4444333322221111", 1, 11L)));
            when(accountFile.rewrite(Mockito.any(AccountRecord.class)))
                    .thenReturn(AccountRepository.WriteResult.written());
            when(transactionFile.writeSequential(Mockito.any(TranRecord.class)))
                    .thenReturn(TransactionRepository.WriteResult.written(
                            TransactionRepository.SEQUENTIAL_OUTPUT_DD_NAME));
            discgrp.yielding(AccountInterestCalcJob.DisclosureGroupRead.found(
                    DisclosureGroupRecord.decode(discgrpImage("A000000000", "01", 1, "12.50"), ASCII)));
            return this;
        }

        AccountInterestCalcJob job() {
            return new AccountInterestCalcJob(scaffolding(contracts(), bindings()), tcatbalRepository,
                    accountRepository, xrefRepository, transactionRepository, discgrp,
                    mockedUnitOfWork(),
                    new PresentBean<>(sysout), FIXED);
        }

        AbendException runAndExpectAbend() {
            AccountInterestCalcJob subject = job();
            return assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> subject.calculateInterest(PARM, sysout))
                    .actual();
        }
    }

    @Nested
    @DisplayName("the DDs this job declares are the DDs it reads and writes - INTCALC.jcl:29-41")
    class TheDeclaredDdsDriveTheIo {
        @Test
        @DisplayName("both cross-reference DDs are handed over: XREFFILE on the base, XREFFIL1 on the "
                + "path")
        void bothCrossReferenceDdsAreHandedOver() {
            Doubles doubles = new Doubles().withOneRecord();

            doubles.job().calculateInterest(PARM, doubles.sysout);

            ArgumentCaptor<DatasetBinding> base = ArgumentCaptor.forClass(DatasetBinding.class);
            ArgumentCaptor<DatasetBinding> path = ArgumentCaptor.forClass(DatasetBinding.class);
            verify(doubles.xrefRepository).addressing(base.capture(),
                    eq(AccountInterestCalcJob.XREFFILE_DD_NAME), path.capture(),
                    eq(AccountInterestCalcJob.XREFFIL1_DD_NAME));
            assertThat(base.getValue().dsname()).isEqualTo(XREF_DS);
            assertThat(path.getValue().dsname()).isEqualTo(XREF_AIX_DS);
            assertThat(path.getValue().base()).isEqualTo(CardXrefRepository.BATCH_DD_NAME);
        }

        @Test
        @DisplayName("the DD names are the JCL's, not the CSD's")
        void theDdNamesAreTheJclsOwn() {
            assertThat(AccountInterestCalcJob.XREFFILE_DD_NAME).isEqualTo("XREFFILE");
            assertThat(AccountInterestCalcJob.XREFFIL1_DD_NAME).isEqualTo("XREFFIL1");
            assertThat(AccountInterestCalcJob.XREFFILE_DD_NAME)
                    .isNotEqualTo(CardXrefRepository.BASE_DD_NAME);
            assertThat(AccountInterestCalcJob.XREFFIL1_DD_NAME)
                    .isNotEqualTo(CardXrefRepository.ALTERNATE_INDEX_DD_NAME);
        }

        @Test
        @DisplayName("the generated-transaction output is opened through TRANSACT, resolved to SYSTRAN")
        void theOutputIsOpenedThroughTheDeclaredDd() {
            Doubles doubles = new Doubles().withOneRecord();

            doubles.job().calculateInterest(PARM, doubles.sysout);

            ArgumentCaptor<DatasetBinding> output = ArgumentCaptor.forClass(DatasetBinding.class);
            verify(doubles.transactionRepository).openOutput(output.capture(),
                    eq(AccountInterestCalcJob.TRANSACT_DD_NAME));
            assertThat(AccountInterestCalcJob.TRANSACT_DD_NAME).isEqualTo("TRANSACT");
            assertThat(output.getValue().dsname()).isEqualTo(SYSTRAN_DS);
            assertThat(output.getValue().dsname()).isNotEqualTo(TRANSACT_DS);
            verify(doubles.transactionRepository, never()).openOutput();
        }

        @Test
        @DisplayName("both the sequential browse and the keyed alternate-index read go through the "
                + "re-bound repository")
        void bothAccessPathsGoThroughTheRebinding() {
            Doubles doubles = new Doubles().withOneRecord();
            CardXrefRepository rebound = mock(CardXrefRepository.class);
            when(rebound.openBrowse()).thenReturn(doubles.xrefCursor);
            when(rebound.readByAccountIdViaAltIndex(anyLong()))
                    .thenReturn(xrefFound(
                            CardXrefRepository.BATCH_DD_NAME,
                            new CardXrefRecord("4444333322221111", 1, 11L)));
            when(doubles.xrefRepository.addressing(any(), any(), any(), any())).thenReturn(rebound);

            doubles.job().calculateInterest(PARM, doubles.sysout);

            verify(rebound).openBrowse();
            verify(rebound, times(1)).readByAccountIdViaAltIndex(anyLong());
            verify(doubles.xrefRepository, never()).openBrowse();
            verify(doubles.xrefRepository, never()).readByAccountIdViaAltIndex(anyLong());
        }

        @Test
        @DisplayName("the re-binding is asked for once per run, not once per keyed read")
        void theRebindingHappensOncePerRun() {
            Doubles doubles = new Doubles().withOneRecord();

            doubles.job().calculateInterest(PARM, doubles.sysout);

            verify(doubles.xrefRepository, times(1)).addressing(any(), any(), any(), any());
        }
    }

    @Nested
    @DisplayName("The five OPEN paragraphs, each with its own message - CBACT04C:234-323")
    class OpenFailures {
        @Test
        @DisplayName(":245 reports ERROR OPENING TRANSACTION CATEGORY BALANCE and opens nothing else")
        void tcatbalf() {
            Doubles doubles = new Doubles();
            when(doubles.tcatbalFile.openStatus())
                    .thenReturn(TranCatBalRepository.PERMANENT_ERROR_STATUS);

            AbendException abend = doubles.runAndExpectAbend();

            assertThat(doubles.sysout.lines()).containsExactly(
                    AccountInterestCalcJob.START_OF_EXECUTION,
                    AccountInterestCalcJob.ERROR_OPENING_TCATBALF,
                    FileStatus.toDisplayLine(TranCatBalRepository.PERMANENT_ERROR_STATUS),
                    AbendException.ABEND_DISPLAY_TEXT);
            assertThat(abend.getReturnCode()).isEqualTo(AccountInterestCalcJob.APPL_RESULT_FATAL);
            verify(doubles.xrefRepository, never()).openBrowse();
            verify(doubles.accountRepository, never()).open(Mockito.any());
            verify(doubles.transactionRepository, never()).openOutput();
        }

        @Test
        @DisplayName(":263 appends the file status to its own message, which no other paragraph does")
        void xreffile() {
            Doubles doubles = new Doubles();
            when(doubles.xrefCursor.openStatus()).thenReturn(CardXrefRepository.PERMANENT_ERROR_STATUS);

            AbendException abend = doubles.runAndExpectAbend();

            assertThat(doubles.sysout.lines()).containsExactly(
                    AccountInterestCalcJob.START_OF_EXECUTION,
                    AccountInterestCalcJob.ERROR_OPENING_XREFFILE
                            + CardXrefRepository.PERMANENT_ERROR_STATUS,
                    FileStatus.toDisplayLine(CardXrefRepository.PERMANENT_ERROR_STATUS),
                    AbendException.ABEND_DISPLAY_TEXT);
            assertThat(abend.getReturnCode()).isEqualTo(AccountInterestCalcJob.APPL_RESULT_FATAL);
        }

        @Test
        @DisplayName(":281 names the WRONG FILE - 'DALY REJECTS' - and that is preserved verbatim")
        void discgrp() {
            Doubles doubles = new Doubles();
            doubles.discgrp.openStatus = TranCatBalRepository.PERMANENT_ERROR_STATUS;

            AbendException abend = doubles.runAndExpectAbend();

            assertThat(AccountInterestCalcJob.ERROR_OPENING_DISCGRP)
                    .isEqualTo("ERROR OPENING DALY REJECTS FILE")
                    .doesNotContain("DISCLOSURE");
            assertThat(doubles.sysout.lines()).containsExactly(
                    AccountInterestCalcJob.START_OF_EXECUTION,
                    AccountInterestCalcJob.ERROR_OPENING_DISCGRP,
                    FileStatus.toDisplayLine(TranCatBalRepository.PERMANENT_ERROR_STATUS),
                    AbendException.ABEND_DISPLAY_TEXT);
            assertThat(abend.getReason())
                    .contains(AccountInterestCalcJob.ERROR_OPENING_DISCGRP + " - "
                            + FileStatus.toDisplayLine(TranCatBalRepository.PERMANENT_ERROR_STATUS));
            verify(doubles.accountRepository, never()).open(Mockito.any());
        }

        @Test
        @DisplayName(":300 reports ERROR OPENING ACCOUNT MASTER FILE, the only OPEN I-O")
        void acctfile() {
            Doubles doubles = new Doubles();
            when(doubles.accountFile.openStatus()).thenReturn(AccountRepository.PERMANENT_ERROR_STATUS);

            doubles.runAndExpectAbend();

            assertThat(doubles.sysout.lines()).containsExactly(
                    AccountInterestCalcJob.START_OF_EXECUTION,
                    AccountInterestCalcJob.ERROR_OPENING_ACCTFILE,
                    FileStatus.toDisplayLine(AccountRepository.PERMANENT_ERROR_STATUS),
                    AbendException.ABEND_DISPLAY_TEXT);
            verify(doubles.accountRepository).open(AccountRepository.OpenMode.I_O);
            verify(doubles.transactionRepository, never()).openOutput(any(), any());
        }

        @Test
        @DisplayName(":318 reports ERROR OPENING TRANSACTION FILE, the last open and the only OUTPUT")
        void tranfile() {
            Doubles doubles = new Doubles();
            when(doubles.transactionFile.openStatus())
                    .thenReturn(TransactionRepository.PERMANENT_ERROR_STATUS);

            doubles.runAndExpectAbend();

            assertThat(doubles.sysout.lines()).containsExactly(
                    AccountInterestCalcJob.START_OF_EXECUTION,
                    AccountInterestCalcJob.ERROR_OPENING_TRANFILE,
                    FileStatus.toDisplayLine(TransactionRepository.PERMANENT_ERROR_STATUS),
                    AbendException.ABEND_DISPLAY_TEXT);
            verify(doubles.tcatbalRepository).open(TranCatBalRepository.OpenMode.INPUT);
            verify(doubles.xrefRepository).openBrowse();
            verify(doubles.accountRepository).open(AccountRepository.OpenMode.I_O);
            ArgumentCaptor<DatasetBinding> written = ArgumentCaptor.forClass(DatasetBinding.class);
            verify(doubles.transactionRepository).openOutput(written.capture(),
                    eq(AccountInterestCalcJob.TRANSACT_DD_NAME));
            assertThat(written.getValue().dsname()).isEqualTo(SYSTRAN_DS);
            verify(doubles.transactionRepository, never()).openOutput();
            assertThat(doubles.sysout.lines())
                    .doesNotContain(AccountInterestCalcJob.END_OF_EXECUTION);
        }

        @Test
        @DisplayName("an open that fails leaves the handles that did open released, silently")
        void releaseClosesOnlyWhatWasOpened() {
            Doubles doubles = new Doubles();
            when(doubles.transactionFile.openStatus())
                    .thenReturn(TransactionRepository.PERMANENT_ERROR_STATUS);

            doubles.runAndExpectAbend();

            verify(doubles.tcatbalFile).close();
            verify(doubles.xrefCursor).close();
            verify(doubles.accountFile).close();
            verify(doubles.transactionFile).close();
            verify(doubles.tcatbalFile, never()).closeFile();
            verify(doubles.xrefCursor, never()).closeBrowse();
            verify(doubles.accountFile, never()).closeFile();
            assertThat(doubles.discgrp.closes).isPositive();
        }

        @Test
        @DisplayName("the first open failing leaves nothing to release, so release() is a no-op")
        void releaseWithNothingOpen() {
            Doubles doubles = new Doubles();
            when(doubles.tcatbalFile.openStatus())
                    .thenReturn(TranCatBalRepository.PERMANENT_ERROR_STATUS);

            doubles.runAndExpectAbend();

            verify(doubles.tcatbalFile).close();
            verify(doubles.xrefCursor, never()).close();
            verify(doubles.accountFile, never()).close();
            verify(doubles.transactionFile, never()).close();
            assertThat(doubles.discgrp.closes).isZero();
        }

        @Test
        @DisplayName("an open that never returned a handle leaves release() with nothing to do at all")
        void releaseWithNoHandleAtAll() {
            Doubles doubles = new Doubles();
            when(doubles.tcatbalRepository.open(TranCatBalRepository.OpenMode.INPUT))
                    .thenThrow(new IllegalStateException("the access path could not be reached"));
            AccountInterestCalcJob subject = doubles.job();

            assertThatIllegalStateException()
                    .isThrownBy(() -> subject.calculateInterest(PARM, doubles.sysout))
                    .withMessageContaining("could not be reached");

            assertThat(doubles.sysout.lines())
                    .containsExactly(AccountInterestCalcJob.START_OF_EXECUTION);
            verify(doubles.xrefRepository, never()).openBrowse();
            verify(doubles.accountRepository, never()).open(Mockito.any());
            verify(doubles.transactionRepository, never()).openOutput();
            verify(doubles.tcatbalFile, never()).close();
            assertThat(doubles.discgrp.closes).isZero();
        }
    }

    @Nested
    @DisplayName("The five CLOSE paragraphs, each with its own message - CBACT04C:522-611")
    class CloseFailures {
        @Test
        @DisplayName(":533 reports ERROR CLOSING TRANSACTION BALANCE FILE and suppresses the end banner")
        void tcatbalf() {
            Doubles doubles = new Doubles();
            when(doubles.tcatbalFile.closeFile())
                    .thenReturn(TranCatBalRepository.PERMANENT_ERROR_STATUS);

            doubles.runAndExpectAbend();

            assertThat(doubles.sysout.lines()).containsExactly(
                    AccountInterestCalcJob.START_OF_EXECUTION,
                    AccountInterestCalcJob.ERROR_CLOSING_TCATBALF,
                    FileStatus.toDisplayLine(TranCatBalRepository.PERMANENT_ERROR_STATUS),
                    AbendException.ABEND_DISPLAY_TEXT);
            assertThat(doubles.sysout.lines())
                    .doesNotContain(AccountInterestCalcJob.END_OF_EXECUTION);
            verify(doubles.xrefCursor, never()).closeBrowse();
        }

        @Test
        @DisplayName(":552 reports ERROR CLOSING CROSS REF FILE, and does not append the status")
        void xreffile() {
            Doubles doubles = new Doubles();
            when(doubles.xrefCursor.closeBrowse()).thenReturn(CardXrefRepository.PERMANENT_ERROR_STATUS);

            doubles.runAndExpectAbend();

            assertThat(doubles.sysout.lines()).containsExactly(
                    AccountInterestCalcJob.START_OF_EXECUTION,
                    AccountInterestCalcJob.ERROR_CLOSING_XREFFILE,
                    FileStatus.toDisplayLine(CardXrefRepository.PERMANENT_ERROR_STATUS),
                    AbendException.ABEND_DISPLAY_TEXT);
            verify(doubles.tcatbalFile).closeFile();
        }

        @Test
        @DisplayName(":570 reports ERROR CLOSING DISCLOSURE GROUP FILE - the right file, unlike its open")
        void discgrp() {
            Doubles doubles = new Doubles();
            doubles.discgrp.closeStatus = TranCatBalRepository.PERMANENT_ERROR_STATUS;

            doubles.runAndExpectAbend();

            assertThat(AccountInterestCalcJob.ERROR_CLOSING_DISCGRP)
                    .isEqualTo("ERROR CLOSING DISCLOSURE GROUP FILE");
            assertThat(doubles.sysout.lines()).containsExactly(
                    AccountInterestCalcJob.START_OF_EXECUTION,
                    AccountInterestCalcJob.ERROR_CLOSING_DISCGRP,
                    FileStatus.toDisplayLine(TranCatBalRepository.PERMANENT_ERROR_STATUS),
                    AbendException.ABEND_DISPLAY_TEXT);
            verify(doubles.accountFile, never()).closeFile();
        }

        @Test
        @DisplayName(":588 reports ERROR CLOSING ACCOUNT FILE")
        void acctfile() {
            Doubles doubles = new Doubles();
            when(doubles.accountFile.closeFile()).thenReturn(AccountRepository.PERMANENT_ERROR_STATUS);

            doubles.runAndExpectAbend();

            assertThat(doubles.sysout.lines()).containsExactly(
                    AccountInterestCalcJob.START_OF_EXECUTION,
                    AccountInterestCalcJob.ERROR_CLOSING_ACCTFILE,
                    FileStatus.toDisplayLine(AccountRepository.PERMANENT_ERROR_STATUS),
                    AbendException.ABEND_DISPLAY_TEXT);
            verify(doubles.transactionFile, never()).closeOutput();
        }

        @Test
        @DisplayName(":606 reports ERROR CLOSING TRANSACTION FILE, the last close before the banner")
        void tranfile() {
            Doubles doubles = new Doubles();
            when(doubles.transactionFile.closeOutput())
                    .thenReturn(TransactionRepository.PERMANENT_ERROR_STATUS);

            doubles.runAndExpectAbend();

            assertThat(doubles.sysout.lines()).containsExactly(
                    AccountInterestCalcJob.START_OF_EXECUTION,
                    AccountInterestCalcJob.ERROR_CLOSING_TRANFILE,
                    FileStatus.toDisplayLine(TransactionRepository.PERMANENT_ERROR_STATUS),
                    AbendException.ABEND_DISPLAY_TEXT);
            verify(doubles.tcatbalFile).closeFile();
            verify(doubles.xrefCursor).closeBrowse();
            verify(doubles.accountFile).closeFile();
            assertThat(doubles.sysout.lines())
                    .doesNotContain(AccountInterestCalcJob.END_OF_EXECUTION);
        }

        @Test
        @DisplayName("a clean run performs all five closes, in order, then displays the end banner")
        void allFiveClosesSucceed() {
            Doubles doubles = new Doubles();

            long records = doubles.job().calculateInterest(PARM, doubles.sysout);

            assertThat(records).isZero();
            assertThat(doubles.sysout.lines()).containsExactly(
                    AccountInterestCalcJob.START_OF_EXECUTION,
                    AccountInterestCalcJob.END_OF_EXECUTION);
            verify(doubles.tcatbalFile).closeFile();
            verify(doubles.xrefCursor).closeBrowse();
            verify(doubles.accountFile).closeFile();
            verify(doubles.transactionFile).closeOutput();
            assertThat(doubles.discgrp.closes).isPositive();
            verify(doubles.tcatbalFile).close();
            verify(doubles.transactionFile).close();
        }
    }

    @Nested
    @DisplayName("The read and write paragraphs that refuse - CBACT04C:325-515")
    class ReadAndWriteFailures {
        @Test
        @DisplayName(":342 reports ERROR READING TRANSACTION CATEGORY FILE for a status that is not "
                + "'00' or '10'")
        void tcatbalfRead() {
            Doubles doubles = new Doubles();
            when(doubles.tcatbalFile.readNext()).thenReturn(
                    TranCatBalRepository.ReadResult.of(TranCatBalRepository.PERMANENT_ERROR_STATUS));

            doubles.runAndExpectAbend();

            assertThat(doubles.sysout.lines()).containsExactly(
                    AccountInterestCalcJob.START_OF_EXECUTION,
                    AccountInterestCalcJob.ERROR_READING_TCATBALF,
                    FileStatus.toDisplayLine(TranCatBalRepository.PERMANENT_ERROR_STATUS),
                    AbendException.ABEND_DISPLAY_TEXT);
        }

        @Test
        @DisplayName("'10' is not a failure: it ends the loop quietly, which is the third ladder arm")
        void tcatbalfEndOfFileIsNotAFailure() {
            Doubles doubles = new Doubles();

            doubles.job().calculateInterest(PARM, doubles.sysout);

            assertThat(doubles.sysout.lines())
                    .doesNotContain(AccountInterestCalcJob.ERROR_READING_TCATBALF)
                    .contains(AccountInterestCalcJob.END_OF_EXECUTION);
            verify(doubles.tcatbalFile).readNext();
        }

        @Test
        @DisplayName(":431 reports ERROR READING DISCLOSURE GROUP FILE for a status that is not "
                + "'00' or '23'")
        void discgrpRead() {
            Doubles doubles = new Doubles().withOneRecord();
            doubles.discgrp.scripted.clear();
            doubles.discgrp.yielding(AccountInterestCalcJob.DisclosureGroupRead
                    .failed(TranCatBalRepository.PERMANENT_ERROR_STATUS));

            doubles.runAndExpectAbend();

            assertThat(doubles.sysout.lines())
                    .contains(AccountInterestCalcJob.ERROR_READING_DISCGRP)
                    .doesNotContain(AccountInterestCalcJob.DISCLOSURE_GROUP_RECORD_MISSING)
                    .doesNotContain(AccountInterestCalcJob.TRY_WITH_DEFAULT_GROUP_CODE);
            assertThat(doubles.sysout.lines()).endsWith(
                    AccountInterestCalcJob.ERROR_READING_DISCGRP,
                    FileStatus.toDisplayLine(TranCatBalRepository.PERMANENT_ERROR_STATUS),
                    AbendException.ABEND_DISPLAY_TEXT);
        }

        @Test
        @DisplayName(":507 reports ERROR WRITING TRANSACTION RECORD when the 350-byte write refuses")
        void transactionWrite() {
            Doubles doubles = new Doubles().withOneRecord();
            when(doubles.transactionFile.writeSequential(Mockito.any(TranRecord.class)))
                    .thenReturn(TransactionRepository.WriteResult.other(
                            TransactionRepository.SEQUENTIAL_OUTPUT_DD_NAME,
                            TransactionRepository.PERMANENT_ERROR_STATUS));

            doubles.runAndExpectAbend();

            assertThat(doubles.sysout.lines()).endsWith(
                    AccountInterestCalcJob.ERROR_WRITING_TRANSACTION,
                    FileStatus.toDisplayLine(TransactionRepository.PERMANENT_ERROR_STATUS),
                    AbendException.ABEND_DISPLAY_TEXT);
            verify(doubles.transactionFile).writeSequential(Mockito.any(TranRecord.class));
        }

        @Test
        @DisplayName(":389 reports ERROR READING ACCOUNT FILE after its own not-found line")
        void accountReadNotFoundEmitsBothLines() {
            Doubles doubles = new Doubles().withOneRecord();
            when(doubles.accountFile.readByKey(anyLong()))
                    .thenReturn(AccountRepository.ReadResult.notFound());

            doubles.runAndExpectAbend();

            assertThat(doubles.sysout.lines()).endsWith(
                    AccountInterestCalcJob.ACCOUNT_NOT_FOUND_PREFIX + "00000000011",
                    AccountInterestCalcJob.ERROR_READING_ACCTFILE,
                    FileStatus.toDisplayLine(FileStatus.NOT_FOUND),
                    AbendException.ABEND_DISPLAY_TEXT);
        }

        @Test
        @DisplayName(":411 reports ERROR READING XREF FILE after its own not-found line")
        void crossReferenceReadNotFoundEmitsBothLines() {
            Doubles doubles = new Doubles().withOneRecord();
            when(doubles.xrefRepository.readByAccountIdViaAltIndex(anyLong()))
                    .thenReturn(CardXrefRepository.ReadResult
                            .notFound(CardXrefRepository.ALTERNATE_INDEX_DD_NAME));

            doubles.runAndExpectAbend();

            assertThat(doubles.sysout.lines()).endsWith(
                    AccountInterestCalcJob.ACCOUNT_NOT_FOUND_PREFIX + "00000000011",
                    AccountInterestCalcJob.ERROR_READING_XREFFILE,
                    FileStatus.toDisplayLine(FileStatus.NOT_FOUND),
                    AbendException.ABEND_DISPLAY_TEXT);
            verify(doubles.xrefRepository).readByAccountIdViaAltIndex(11L);
            verify(doubles.xrefRepository, never()).readByCardNumber(Mockito.anyString());
        }

        @Test
        @DisplayName("a '23' on the DEFAULT retry is fatal, because :444 has no INVALID KEY phrase")
        void theDefaultRetryHasNoInvalidKeyPhrase() {
            Doubles doubles = new Doubles().withOneRecord();
            doubles.discgrp.scripted.clear();
            doubles.discgrp.yielding(AccountInterestCalcJob.DisclosureGroupRead.notFound());

            doubles.runAndExpectAbend();

            assertThat(doubles.sysout.lines()).endsWith(
                    AccountInterestCalcJob.DISCLOSURE_GROUP_RECORD_MISSING,
                    AccountInterestCalcJob.TRY_WITH_DEFAULT_GROUP_CODE,
                    AccountInterestCalcJob.ERROR_READING_DEFAULT_DISCGRP,
                    FileStatus.toDisplayLine(FileStatus.NOT_FOUND),
                    AbendException.ABEND_DISPLAY_TEXT);
            assertThat(doubles.discgrp.keysRead).hasSize(2);
            assertThat(doubles.discgrp.keysRead.get(0)).isEqualTo("A00000000001" + "0001");
            assertThat(doubles.discgrp.keysRead.get(1)).isEqualTo("DEFAULT   01" + "0001");
        }

        @Test
        @DisplayName("a '23' followed by a hit uses the DEFAULT group's rate and computes interest")
        void theDefaultRetrySucceeding() {
            Doubles doubles = new Doubles().withOneRecord();
            doubles.discgrp.scripted.clear();
            doubles.discgrp.yielding(AccountInterestCalcJob.DisclosureGroupRead.notFound());
            doubles.discgrp.yielding(AccountInterestCalcJob.DisclosureGroupRead.found(
                    DisclosureGroupRecord.decode(discgrpImage("DEFAULT", "01", 1, "12.50"), ASCII)));

            long records = doubles.job().calculateInterest(PARM, doubles.sysout);

            assertThat(records).isEqualTo(1L);
            assertThat(doubles.sysout.lines())
                    .contains(AccountInterestCalcJob.DISCLOSURE_GROUP_RECORD_MISSING,
                            AccountInterestCalcJob.TRY_WITH_DEFAULT_GROUP_CODE,
                            AccountInterestCalcJob.END_OF_EXECUTION);
            verify(doubles.transactionFile).writeSequential(Mockito.any(TranRecord.class));
        }

        @Test
        @DisplayName("a rewrite that refuses reports ERROR RE-WRITING ACCOUNT FILE - CBACT04C:365")
        void accountRewrite() {
            Doubles doubles = new Doubles();
            TranCatBalRecord first = TranCatBalRecord.decode(
                    tcatbalImage(11L, "01", 1, "1000.00"), ASCII);
            TranCatBalRecord second = TranCatBalRecord.decode(
                    tcatbalImage(12L, "01", 1, "1000.00"), ASCII);
            when(doubles.tcatbalFile.readNext()).thenReturn(
                    TranCatBalRepository.ReadResult.found(first),
                    TranCatBalRepository.ReadResult.found(second),
                    TranCatBalRepository.ReadResult.endOfFile());
            when(doubles.accountFile.readByKey(anyLong())).thenReturn(
                    AccountRepository.ReadResult.found(
                            AccountRecord.decode(acctImage(11L, "500.00", "A000000000"), ASCII)));
            when(doubles.xrefRepository.readByAccountIdViaAltIndex(anyLong()))
                    .thenReturn(xrefFound(
                            CardXrefRepository.BASE_DD_NAME,
                            new CardXrefRecord("4444333322221111", 1, 11L)));
            when(doubles.transactionFile.writeSequential(Mockito.any(TranRecord.class)))
                    .thenReturn(TransactionRepository.WriteResult.written(
                            TransactionRepository.SEQUENTIAL_OUTPUT_DD_NAME));
            when(doubles.accountFile.rewrite(Mockito.any(AccountRecord.class)))
                    .thenReturn(AccountRepository.WriteResult.notFound());
            doubles.discgrp.yielding(AccountInterestCalcJob.DisclosureGroupRead.found(
                    DisclosureGroupRecord.decode(discgrpImage("A000000000", "01", 1, "12.50"), ASCII)));

            doubles.runAndExpectAbend();

            assertThat(doubles.sysout.lines()).endsWith(
                    AccountInterestCalcJob.ERROR_REWRITING_ACCTFILE,
                    FileStatus.toDisplayLine(FileStatus.NOT_FOUND),
                    AbendException.ABEND_DISPLAY_TEXT);
            verify(doubles.accountFile).rewrite(Mockito.any(AccountRecord.class));
        }
    }

    @Nested
    @DisplayName("The DISCGRP read outcome enforces what READ ... INTO can produce")
    class TheDisclosureGroupReadOutcome {
        @Test
        @DisplayName("found carries the record, notFound and failed carry none")
        void theThreeFactories() {
            DisclosureGroupRecord record =
                    DisclosureGroupRecord.decode(discgrpImage("A000000000", "01", 1, "12.50"), ASCII);

            AccountInterestCalcJob.DisclosureGroupRead found =
                    AccountInterestCalcJob.DisclosureGroupRead.found(record);
            AccountInterestCalcJob.DisclosureGroupRead missing =
                    AccountInterestCalcJob.DisclosureGroupRead.notFound();
            AccountInterestCalcJob.DisclosureGroupRead broken =
                    AccountInterestCalcJob.DisclosureGroupRead.failed(
                            TranCatBalRepository.PERMANENT_ERROR_STATUS);

            assertThat(found.status()).isEqualTo(FileStatus.OK);
            assertThat(found.record()).containsSame(record);
            assertThat(found.isNotFound()).isFalse();
            assertThat(missing.status()).isEqualTo(FileStatus.NOT_FOUND);
            assertThat(missing.record()).isEmpty();
            assertThat(missing.isNotFound()).isTrue();
            assertThat(broken.status()).isEqualTo(TranCatBalRepository.PERMANENT_ERROR_STATUS);
            assertThat(broken.record()).isEmpty();
            assertThat(broken.isNotFound()).isFalse();
        }

        @Test
        @DisplayName("neither component may be null, and an Optional is required rather than a null")
        void nullComponentsAreRefused() {
            DisclosureGroupRecord record =
                    DisclosureGroupRecord.decode(discgrpImage("A000000000", "01", 1, "12.50"), ASCII);

            assertThatNullPointerException().isThrownBy(() ->
                    new AccountInterestCalcJob.DisclosureGroupRead(null, Optional.of(record)));
            assertThatNullPointerException().isThrownBy(() ->
                    new AccountInterestCalcJob.DisclosureGroupRead(FileStatus.OK, null));
            assertThatNullPointerException().isThrownBy(() ->
                    AccountInterestCalcJob.DisclosureGroupRead.found(null));
        }

        @Test
        @DisplayName("a FILE STATUS is exactly two characters")
        void aStatusIsTwoCharacters() {
            assertThatIllegalArgumentException().isThrownBy(() ->
                            new AccountInterestCalcJob.DisclosureGroupRead("0", Optional.empty()))
                    .withMessageContaining("exactly");
            assertThatIllegalArgumentException().isThrownBy(() ->
                            new AccountInterestCalcJob.DisclosureGroupRead("000", Optional.empty()))
                    .withMessageContaining("exactly");
        }

        @Test
        @DisplayName("the record is present exactly when the status is '00', because INTO transfers "
                + "on success only")
        void presenceMustAgreeWithTheStatus() {
            DisclosureGroupRecord record =
                    DisclosureGroupRecord.decode(discgrpImage("A000000000", "01", 1, "12.50"), ASCII);

            assertThatIllegalArgumentException().isThrownBy(() ->
                            new AccountInterestCalcJob.DisclosureGroupRead(FileStatus.OK,
                                    Optional.empty()))
                    .withMessageContaining("must be present");
            assertThatIllegalArgumentException().isThrownBy(() ->
                            new AccountInterestCalcJob.DisclosureGroupRead(FileStatus.NOT_FOUND,
                                    Optional.of(record)))
                    .withMessageContaining("must be present");
            assertThatIllegalArgumentException().isThrownBy(() ->
                    AccountInterestCalcJob.DisclosureGroupRead.failed(FileStatus.OK));
        }
    }

    @Nested
    @DisplayName("The JDBC DISCGRP access path refuses a configuration it cannot honour")
    class TheJdbcAccessPathGuards {
        private DatasetBindings withDiscgrp(DatasetBinding discgrp) {
            DatasetBindings all = bindings();
            all.put(AccountInterestCalcJob.DISCGRP_DD_NAME, discgrp);
            return all;
        }

        @Test
        @DisplayName("a missing key-length is refused, because 16 cannot be assumed")
        void aMissingKeyLength() {
            JdbcTemplate template = database();

            assertThatIllegalStateException().isThrownBy(() ->
                            new DisclosureGroupRepository(template,
                                    bindings(50, null), ASCII, RecordImageForm.CHARACTER))
                    .withMessageContaining("key-length must be 16");
        }

        @Test
        @DisplayName("a key-offset other than zero is refused: DIS-GROUP-KEY begins the record")
        void aNonZeroKeyOffset() {
            JdbcTemplate template = database();
            DatasetBindings all = withDiscgrp(new DatasetBinding(DISCGRP_DS, "ksds", false, "FB", null,
                    50, "CVTRA02Y", 16, 4, null, null));

            assertThatIllegalStateException().isThrownBy(() ->
                            new DisclosureGroupRepository(template, all, ASCII,
                                    RecordImageForm.CHARACTER))
                    .withMessageContaining("key-offset");
        }

        @Test
        @DisplayName("an absent dsname is refused, and is a different guard arm from a blank one")
        void anAbsentDatasetName() {
            JdbcTemplate template = database();
            DatasetBindings all = withDiscgrp(new DatasetBinding(null, "ksds", false, "FB", null,
                    50, "CVTRA02Y", 16, 0, null, null));

            assertThatIllegalStateException().isThrownBy(() ->
                            new DisclosureGroupRepository(template, all, ASCII,
                                    RecordImageForm.CHARACTER))
                    .withMessageContaining("dsname");
        }

        @Test
        @DisplayName("a blank dsname is refused: no dataset name is hard-coded here (gate G46)")
        void aBlankDatasetName() {
            JdbcTemplate template = database();
            DatasetBindings all = withDiscgrp(new DatasetBinding("   ", "ksds", false, "FB", null,
                    50, "CVTRA02Y", 16, 0, null, null));

            assertThatIllegalStateException().isThrownBy(() ->
                            new DisclosureGroupRepository(template, all, ASCII,
                                    RecordImageForm.CHARACTER))
                    .withMessageContaining("dsname");
        }

        @Test
        @DisplayName("the access path reports the configured dataset name and nothing invented")
        void theDatasetNameComesFromConfiguration() {
            AccountInterestCalcJob.DisclosureGroupAccess access =
                    new DisclosureGroupRepository(database(), bindings(), ASCII,
                            RecordImageForm.CHARACTER);

            assertThat(access.datasetName()).isEqualTo(DISCGRP_DS)
                    .doesNotContain("AWS.M2.CARDDEMO");
        }

        @Test
        @DisplayName("an open that never reached the dataset reports the same failure from every "
                + "operation")
        void anOpenThatFailed() {
            DriverManagerDataSource source = new DriverManagerDataSource("jdbc:h2:mem:intcalcnodiscgrp"
                    + SEQ.incrementAndGet() + ";DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE", "sa", "");
            source.setDriverClassName("org.h2.Driver");
            AccountInterestCalcJob.DisclosureGroupAccess access =
                    new DisclosureGroupRepository(new JdbcTemplate(source),
                            bindings(), ASCII, RecordImageForm.CHARACTER);

            try (AccountInterestCalcJob.DisclosureGroupFile file = access.open()) {
                assertThat(file.openStatus()).isNotEqualTo(FileStatus.OK);

                AccountInterestCalcJob.DisclosureGroupRead read = file.readByKey("A00000000001" + "0001");

                assertThat(read.status()).isEqualTo(file.openStatus());
                assertThat(read.isNotFound()).isFalse();
                assertThat(read.record()).isEmpty();
                assertThat(file.closeFile()).isEqualTo(file.openStatus());
            }
        }

        @Test
        @DisplayName("a read after the close is a defect in the caller, not a file status")
        void readingAfterTheClose() {
            AccountInterestCalcJob.DisclosureGroupAccess access =
                    new DisclosureGroupRepository(database(), bindings(), ASCII,
                            RecordImageForm.CHARACTER);
            AccountInterestCalcJob.DisclosureGroupFile file = access.open();
            assertThat(file.openStatus()).isEqualTo(FileStatus.OK);
            assertThat(file.closeFile()).isEqualTo(FileStatus.OK);

            assertThatIllegalStateException()
                    .isThrownBy(() -> file.readByKey("A00000000001" + "0001"))
                    .withMessageContaining("closed");
        }

        @Test
        @DisplayName("a null key is refused, and so is one that is not exactly sixteen characters")
        void theKeyWidthIsEnforcedOnAnOpenHandle() {
            AccountInterestCalcJob.DisclosureGroupAccess access =
                    new DisclosureGroupRepository(database(), bindings(), ASCII,
                            RecordImageForm.CHARACTER);

            try (AccountInterestCalcJob.DisclosureGroupFile file = access.open()) {
                assertThatNullPointerException().isThrownBy(() -> file.readByKey(null));
                assertThatIllegalArgumentException()
                        .isThrownBy(() -> file.readByKey("A0000000000100017"))
                        .withMessageContaining("16");
            }
        }

        @Test
        @DisplayName("a backend that refuses mid-run reports a permanent status rather than throwing")
        void aRefusalDuringARead() {
            JdbcTemplate template = database();
            seed(template, DISCGRP_DS, discgrpImage("A000000000", "01", 1, "12.50"));
            AccountInterestCalcJob.DisclosureGroupAccess access =
                    new DisclosureGroupRepository(template, bindings(), ASCII,
                            RecordImageForm.CHARACTER);

            try (AccountInterestCalcJob.DisclosureGroupFile file = access.open()) {
                assertThat(file.readByKey("A00000000001" + "0001").status()).isEqualTo(FileStatus.OK);

                template.execute("DROP TABLE \"" + DISCGRP_DS + "\"");
                AccountInterestCalcJob.DisclosureGroupRead read =
                        file.readByKey("A00000000001" + "0001");

                assertThat(read.status()).isNotEqualTo(FileStatus.OK);
                assertThat(read.record()).isEmpty();
            }
        }

        @Test
        @DisplayName("a key that names no group is a not-found rather than a failure - :417 branches "
                + "on it")
        void aKeyThatNamesNoGroup() {
            JdbcTemplate template = database();
            seed(template, DISCGRP_DS, discgrpImage("A000000000", "01", 1, "12.50"));
            AccountInterestCalcJob.DisclosureGroupAccess access =
                    new DisclosureGroupRepository(template, bindings(), ASCII,
                            RecordImageForm.CHARACTER);

            try (AccountInterestCalcJob.DisclosureGroupFile file = access.open()) {
                AccountInterestCalcJob.DisclosureGroupRead read =
                        file.readByKey("Z99999999999" + "9999");

                assertThat(read.isNotFound()).isTrue();
                assertThat(read.record()).isEmpty();
            }
        }
    }

    @Nested
    @DisplayName("The chunk delegate carries the program's lifecycle, and one run at a time")
    class TheChunkDelegateLifecycle {
        private StepExecution stepExecution(String parmDate) {
            JobParametersBuilder parameters = new JobParametersBuilder();
            if (parmDate != null) {
                parameters.addString(BatchConfig.PARM_DATE_PARAMETER, parmDate);
            }
            return new StepExecution(AccountInterestCalcJob.STEP_NAME,
                    new JobExecution(1L, parameters.toJobParameters()));
        }

        @Test
        @DisplayName("a launcher-supplied PARM of the declared width is taken exactly as it is")
        void aSuppliedParmDate() {
            AccountInterestCalcJob.ChunkDelegate delegate =
                    new Doubles().job().newChunkDelegate();

            delegate.beforeStep(stepExecution("2023010100"));

            assertThat(delegate.parmDate()).isEqualTo("2023010100")
                    .hasSize(AccountInterestCalcJob.PARM_DATE_WIDTH);
        }

        @ParameterizedTest(name = "a {0}-character PARM is refused")
        @ValueSource(strings = {"2023", "20230101001234", "", " "})
        @DisplayName("a supplied PARM of any other width is REFUSED, never padded or truncated to fit")
        void aWrongWidthParmDateIsRefused(final String supplied) {
            AccountInterestCalcJob.ChunkDelegate delegate =
                    new Doubles().job().newChunkDelegate();

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> delegate.beforeStep(stepExecution(supplied)))
                    .withMessageContaining("needs exactly "
                            + AccountInterestCalcJob.PARM_DATE_WIDTH);
        }

        @Test
        @DisplayName("the job's validator enforces both the exact PARM width and the parameter "
                + "allow-list, so neither a bad width nor an invented key can launch it")
        void theJobAttachesTheParmDateValidator() throws Exception {
            Doubles doubles = new Doubles();
            Job job = doubles.job().accountInterestCalcJob();

            JobParametersValidator validator = ((AbstractJob) job).getJobParametersValidator();
            assertThat(validator)
                    .as("INTCALC is the one job in the estate that takes a PARM, and its width is "
                            + "structural rather than cosmetic")
                    .isNotNull();
            assertThatExceptionOfType(JobParametersInvalidException.class)
                    .isThrownBy(() -> validator.validate(new JobParametersBuilder()
                            .addString(BatchConfig.PARM_DATE_PARAMETER, "2023")
                            .toJobParameters()));

            assertThatExceptionOfType(JobParametersInvalidException.class)
                    .isThrownBy(() -> validator.validate(new JobParametersBuilder()
                            .addString(BatchConfig.PARM_DATE_PARAMETER, PARM)
                            .addString("reportDate", PARM)
                            .toJobParameters()))
                    .withMessageContaining("Undeclared: [reportDate]");
            assertThatExceptionOfType(JobParametersInvalidException.class)
                    .as("the declared PARM is required, not merely permitted")
                    .isThrownBy(() -> validator.validate(new JobParameters()))
                    .withMessageContaining("Missing: [" + BatchConfig.PARM_DATE_PARAMETER + "]");

            validator.validate(new JobParametersBuilder()
                    .addString(BatchConfig.PARM_DATE_PARAMETER, PARM)
                    .toJobParameters());
            validator.validate(new JobParametersBuilder()
                    .addString(BatchConfig.PARM_DATE_PARAMETER, PARM)
                    .addLong(BatchConfig.RUN_IDENTITY_PARAMETER, 7L)
                    .toJobParameters());
        }

        @Test
        @DisplayName("no supplied PARM leaves the JCL step card's declared value in place")
        void noSuppliedParmDate() {
            AccountInterestCalcJob subject = new Doubles().job();
            AccountInterestCalcJob.ChunkDelegate delegate = subject.newChunkDelegate();

            delegate.beforeStep(stepExecution(null));

            assertThat(delegate.parmDate()).isEqualTo(subject.declaredParmDate()).isEqualTo(PARM);
        }

        @Test
        @DisplayName("a step execution is required to read its job parameters")
        void aStepExecutionIsRequired() {
            AccountInterestCalcJob.ChunkDelegate delegate = new Doubles().job().newChunkDelegate();

            assertThatNullPointerException().isThrownBy(() -> delegate.beforeStep(null));
        }

        @Test
        @DisplayName("opening twice is refused: WORKING-STORAGE belongs to one execution")
        void openingTwice() {
            Doubles doubles = new Doubles();
            AccountInterestCalcJob.ChunkDelegate delegate = doubles.job().newChunkDelegate();
            delegate.beforeStep(stepExecution(PARM));
            delegate.open(new ExecutionContext());

            try {
                assertThatIllegalStateException()
                        .isThrownBy(() -> delegate.open(new ExecutionContext()))
                        .withMessageContaining("already open");
            } finally {
                delegate.close();
            }
        }

        @Test
        @DisplayName("an execution context is required even though this program stores nothing in it")
        void anExecutionContextIsRequired() {
            AccountInterestCalcJob.ChunkDelegate delegate = new Doubles().job().newChunkDelegate();

            assertThatNullPointerException().isThrownBy(() -> delegate.open(null));
        }

        @Test
        @DisplayName("closing a delegate that was never opened does nothing at all")
        void closingWithoutOpening() {
            Doubles doubles = new Doubles();
            AccountInterestCalcJob.ChunkDelegate delegate = doubles.job().newChunkDelegate();

            delegate.close();

            assertThat(doubles.sysout.lines()).isEmpty();
            verify(doubles.tcatbalRepository, never()).open(Mockito.any());
        }

        @Test
        @DisplayName("closing before end of file performs no CLOSE paragraph and no end banner")
        void closingBeforeEndOfFile() {
            Doubles doubles = new Doubles().withOneRecord();
            AccountInterestCalcJob.ChunkDelegate delegate = doubles.job().newChunkDelegate();
            delegate.beforeStep(stepExecution(PARM));
            delegate.open(new ExecutionContext());
            assertThat(delegate.read()).isNotNull();

            delegate.close();

            assertThat(doubles.sysout.lines())
                    .doesNotContain(AccountInterestCalcJob.END_OF_EXECUTION);
            verify(doubles.tcatbalFile, never()).closeFile();
            verify(doubles.tcatbalFile).close();
            verify(doubles.transactionFile).close();
        }

        @Test
        @DisplayName("reading before the open is refused rather than answered with end of file")
        void readingBeforeTheOpen() {
            AccountInterestCalcJob.ChunkDelegate delegate = new Doubles().job().newChunkDelegate();

            assertThatIllegalStateException().isThrownBy(delegate::read);
        }

        @Test
        @DisplayName("processing before the open is refused too")
        void processingBeforeTheOpen() {
            AccountInterestCalcJob.ChunkDelegate delegate = new Doubles().job().newChunkDelegate();
            TranCatBalRecord record =
                    TranCatBalRecord.decode(tcatbalImage(11L, "01", 1, "1000.00"), ASCII);

            assertThatIllegalStateException().isThrownBy(() -> delegate.process(record));
        }

        @Test
        @DisplayName("the writer is bookkeeping only: a chunk is required, and each flag is counted")
        void theWriterCountsWhatTheProcessorDid() {
            Doubles doubles = new Doubles().withOneRecord();
            AccountInterestCalcJob.ChunkDelegate delegate = doubles.job().newChunkDelegate();
            delegate.beforeStep(stepExecution(PARM));
            delegate.open(new ExecutionContext());

            try {
                assertThatNullPointerException().isThrownBy(() -> delegate.write(null));

                RecordOutcome first = delegate.process(delegate.read());
                delegate.write(new Chunk<>(List.of(first)));

                assertThat(first.interestComputed()).isTrue();
                assertThat(first.feesComputed()).isTrue();
                assertThat(first.transactionWritten()).isTrue();
                assertThat(first.accountRewritten()).isFalse();
                assertThat(delegate.transactionsWritten()).isEqualTo(1L);
                assertThat(delegate.accountsRewritten()).isZero();

                assertThat(delegate.read()).isNull();
            } finally {
                delegate.close();
            }
        }

        @Test
        @DisplayName("an account break with no transaction counts the rewrite and not a write")
        void theWriterCountsARewriteWithoutAWrite() {
            Doubles doubles = new Doubles();
            AccountInterestCalcJob.ChunkDelegate delegate = doubles.job().newChunkDelegate();
            delegate.beforeStep(stepExecution(PARM));
            delegate.open(new ExecutionContext());

            try {
                RecordOutcome rewriteOnly = new RecordOutcome(7L, "00000000011" + "01" + "0001",
                        true, false, false, false, CobolDecimal.monetaryZero(), "");

                delegate.write(new Chunk<>(List.of(rewriteOnly)));

                assertThat(delegate.accountsRewritten()).isEqualTo(1L);
                assertThat(delegate.transactionsWritten()).isZero();
            } finally {
                delegate.close();
            }
        }

        @Test
        @DisplayName("an empty chunk counts nothing, which is the loop's zero-iteration arm")
        void anEmptyChunk() {
            Doubles doubles = new Doubles();
            AccountInterestCalcJob.ChunkDelegate delegate = doubles.job().newChunkDelegate();
            delegate.beforeStep(stepExecution(PARM));
            delegate.open(new ExecutionContext());

            try {
                delegate.write(new Chunk<>(List.of()));

                assertThat(delegate.transactionsWritten()).isZero();
                assertThat(delegate.accountsRewritten()).isZero();
            } finally {
                delegate.close();
            }
        }

        @Test
        @DisplayName("a complete pass through the delegate ends with the five closes and the banner")
        void aCompletePass() {
            Doubles doubles = new Doubles().withOneRecord();
            AccountInterestCalcJob.ChunkDelegate delegate = doubles.job().newChunkDelegate();
            delegate.beforeStep(stepExecution(PARM));
            delegate.open(new ExecutionContext());

            TranCatBalRecord item;
            while ((item = delegate.read()) != null) {
                delegate.write(new Chunk<>(List.of(delegate.process(item))));
            }
            delegate.close();

            assertThat(doubles.sysout.lines()).first()
                    .isEqualTo(AccountInterestCalcJob.START_OF_EXECUTION);
            assertThat(doubles.sysout.lines()).last()
                    .isEqualTo(AccountInterestCalcJob.END_OF_EXECUTION);
            verify(doubles.tcatbalFile).closeFile();
            verify(doubles.transactionFile).closeOutput();
            delegate.close();
            verify(doubles.tcatbalFile, times(1)).closeFile();
        }
    }

    @Nested
    @DisplayName("Each mutating verb persists on its own - RECOVERY(NONE), no chunk rollback")
    class PerVerbPersistence {
        private void seedABreakFollowedByAFailure(JdbcTemplate t) {
            seed(t, TCATBAL_DS, tcatbalImage(11L, "01", 1, "1000.00"));
            seed(t, TCATBAL_DS, tcatbalImage(22L, "01", 1, "1000.00"));
            seed(t, ACCT_DS, acctImage(11L, "0.00", "A000000000"));
            seed(t, XREF_DS, xrefImage("4444333322221111", 1, 11L));
            seed(t, XREF_AIX_DS, xrefImage("4444333322221111", 1, 11L));
            seed(t, DISCGRP_DS, discgrpImage("A000000000", "01", 1, "12.50"));
        }

        @Test
        @DisplayName("the account-break REWRITE and the generated transaction survive a later abend")
        void theVerbsAlreadyPerformedSurviveALaterAbend() {
            JdbcTemplate t = database();
            seedABreakFollowedByAFailure(t);
            CapturedSysout sysout = new CapturedSysout();
            AccountInterestCalcJob job = job(t, bindings(), sysout);
            TransactionTemplate chunk =
                    new TransactionTemplate(new JdbcTransactionManager(t.getDataSource()));

            AbendException abend = assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> chunk.execute(status -> {
                        job.calculateInterest(PARM, sysout);
                        return null;
                    }))
                    .actual();

            assertThat(sysout.lines()).contains(AccountInterestCalcJob.ERROR_READING_ACCTFILE);
            assertThat(abend.getReturnCode()).isEqualTo(12);

            AccountRecord rewritten = AccountRecord.decode(rows(t, ACCT_DS).get(0), ASCII);
            assertThat(rewritten.getAcctId()).isEqualTo(11L);
            assertThat(rewritten.getAcctCurrBal())
                    .as("the rewrite app/cbl/CBACT04C.cbl:356 performed is permanent")
                    .isEqualTo(new BigDecimal("10.41"));
            assertThat(rewritten.getAcctCurrCycCredit()).isEqualTo(new BigDecimal("0.00"));
            assertThat(rewritten.getAcctCurrCycDebit()).isEqualTo(new BigDecimal("0.00"));

            assertThat(rows(t, SYSTRAN_DS))
                    .as("DISP=(NEW,CATLG,DELETE): the generation is catalogued only on a normal end")
                    .isEmpty();
        }

        @Test
        @DisplayName("a run that ends normally keeps its generation - CATLG, the other disposition")
        void aNormalEndKeepsItsGeneration() {
            JdbcTemplate t = database();
            seed(t, TCATBAL_DS, tcatbalImage(11L, "01", 1, "1000.00"));
            seed(t, ACCT_DS, acctImage(11L, "0.00", "A000000000"));
            seed(t, XREF_DS, xrefImage("4444333322221111", 1, 11L));
            seed(t, XREF_AIX_DS, xrefImage("4444333322221111", 1, 11L));
            seed(t, DISCGRP_DS, discgrpImage("A000000000", "01", 1, "12.50"));
            CapturedSysout sysout = new CapturedSysout();

            job(t, bindings(), sysout).calculateInterest(PARM, sysout);

            assertThat(rows(t, SYSTRAN_DS)).hasSize(1);
            assertThat(TranRecord.decode(rows(t, SYSTRAN_DS).get(0), ASCII).tranId())
                    .isEqualTo("2022071800000001");
            assertThat(sysout.lines()).last().isEqualTo(AccountInterestCalcJob.END_OF_EXECUTION);
        }

        @Test
        @DisplayName("nothing is written for the account whose read failed")
        void theFailedAccountLeavesNoTrace() {
            JdbcTemplate t = database();
            seedABreakFollowedByAFailure(t);
            CapturedSysout sysout = new CapturedSysout();
            AccountInterestCalcJob job = job(t, bindings(), sysout);

            assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> job.calculateInterest(PARM, sysout));

            assertThat(rows(t, SYSTRAN_DS)).isEmpty();
            assertThat(rows(t, ACCT_DS)).hasSize(1);
        }

        @Test
        @DisplayName("a failed abnormal disposition does not mask the abend that caused it")
        void aFailedDispositionDoesNotReplaceTheAbend() {
            Doubles doubles = new Doubles().withOneRecord();
            when(doubles.transactionFile.writeSequential(Mockito.any(TranRecord.class)))
                    .thenReturn(TransactionRepository.WriteResult.other(
                            TransactionRepository.SEQUENTIAL_OUTPUT_DD_NAME,
                            TransactionRepository.PERMANENT_ERROR_STATUS));
            when(doubles.transactionFile.discardGeneration()).thenReturn(FileStatus.NOT_FOUND);

            AbendException abend = doubles.runAndExpectAbend();

            assertThat(abend.getReturnCode()).isEqualTo(AccountInterestCalcJob.APPL_RESULT_FATAL);
            assertThat(abend.getAbendCode()).hasValue(AbendException.STANDARD_ABEND_CODE);
            assertThat(doubles.sysout.lines()).containsSubsequence(
                    AccountInterestCalcJob.ERROR_WRITING_TRANSACTION,
                    FileStatus.toDisplayLine(TransactionRepository.PERMANENT_ERROR_STATUS),
                    AbendException.ABEND_DISPLAY_TEXT);
            verify(doubles.transactionFile, times(1)).discardGeneration();
            assertThat(doubles.sysout.lines()).doesNotContain(AccountInterestCalcJob.END_OF_EXECUTION);
        }

        @Test
        @DisplayName("each verb names the paragraph and the COBOL statement it persists")
        void theVerbsAreNamedForAttribution() {
            assertThat(AccountInterestCalcJob.REWRITE_ACCTFILE_VERB)
                    .contains("1050-UPDATE-ACCOUNT")
                    .contains("REWRITE FD-ACCTFILE-REC");
            assertThat(AccountInterestCalcJob.WRITE_TRANFILE_VERB)
                    .contains("WRITE FD-TRANFILE-REC");
        }
    }

    @Nested
    @DisplayName("Restart is refused: a JCL step has none, and a resumed run would post twice")
    class RestartSemantics {
        @Test
        @DisplayName("the job is not restartable")
        void theJobIsNotRestartable() {
            AccountInterestCalcJob subject = new Doubles().job();

            assertThat(subject.accountInterestCalcJob().isRestartable())
                    .as("app/jcl/INTCALC.jcl has no restart; a resumed run would duplicate financial work")
                    .isFalse();
        }

        @Test
        @DisplayName("the step still carries the JCL step name, so the refusal changed nothing else")
        void theStepShapeIsUnchanged() {
            AccountInterestCalcJob subject = new Doubles().job();

            assertThat(subject.accountInterestCalcJob().getName())
                    .isEqualTo(AccountInterestCalcJob.JOB_NAME);
            assertThat(subject.accountInterestCalcStep().getName())
                    .isEqualTo(AccountInterestCalcJob.STEP_NAME);
        }
    }

    @Nested
    @DisplayName("The step-execution scope - one address space per launch")
    class TheStepExecutionScope {
        private StepExecution stepExecution(long jobExecutionId, String parmDate) {
            JobParametersBuilder parameters = new JobParametersBuilder()
                    .addString(BatchConfig.PARM_DATE_PARAMETER, parmDate);
            return new StepExecution(AccountInterestCalcJob.STEP_NAME,
                    new JobExecution(jobExecutionId, parameters.toJobParameters()));
        }

        private AccountInterestCalcJob.StepScopedChunkDelegate scoped() {
            return new AccountInterestCalcJob.StepScopedChunkDelegate(new Doubles().job());
        }

        @Test
        @DisplayName("two concurrent executions get two delegates, each with its own PARM")
        @Timeout(value = 60, unit = TimeUnit.SECONDS)
        void twoConcurrentExecutionsGetTwoDelegates() {
            AccountInterestCalcJob.StepScopedChunkDelegate subject = scoped();
            CyclicBarrier bothScoped = new CyclicBarrier(2);
            Map<String, ChunkDelegate> byParm = new ConcurrentHashMap<>();

            java.util.function.Function<String, Callable<String>> execution = suffix -> () -> {
                String parm = "202207" + suffix;
                subject.beforeStep(stepExecution(parm.hashCode(), parm));
                byParm.put(parm, subject.scopedDelegate().orElseThrow());
                bothScoped.await(ConcurrentTasks.TIMEOUT_SECONDS, TimeUnit.SECONDS);
                String observed = subject.scopedDelegate().orElseThrow().parmDate();
                subject.close();
                return observed;
            };

            List<String> observed = ConcurrentTasks.runBoth(
                    execution.apply("1800"), execution.apply("1900"));

            assertThat(observed)
                    .as("each execution reads back its own PARM after the other has bound its own - so "
                            + "neither can be seeing a delegate the other established")
                    .containsExactly("2022071800", "2022071900");
            assertThat(byParm).hasSize(2);
            assertThat(byParm.get("2022071800"))
                    .as("two executions must not share one WORKING-STORAGE")
                    .isNotSameAs(byParm.get("2022071900"));
            assertThat(subject.scopedDelegate())
                    .as("and the calling thread never had a scope of its own to begin with").isEmpty();
        }

        @Test
        @DisplayName("the scope is empty before beforeStep and released by close")
        void theScopeIsBoundedByBeforeStepAndClose() {
            AccountInterestCalcJob.StepScopedChunkDelegate subject = scoped();

            assertThat(subject.scopedDelegate()).isEmpty();
            subject.beforeStep(stepExecution(1L, PARM));
            assertThat(subject.scopedDelegate()).isPresent();
            assertThat(subject.scopedDelegate().orElseThrow().parmDate()).isEqualTo(PARM);
            subject.close();
            assertThat(subject.scopedDelegate()).isEmpty();

            subject.beforeStep(stepExecution(2L, "2023010100"));
            assertThat(subject.scopedDelegate().orElseThrow().parmDate()).isEqualTo("2023010100");
            subject.close();
        }

        @Test
        @DisplayName("a close with no scope is a no-op, because a stream may be closed unopened")
        void closingWithNoScopeIsHarmless() {
            AccountInterestCalcJob.StepScopedChunkDelegate subject = scoped();

            subject.close();
            subject.close();

            assertThat(subject.scopedDelegate()).isEmpty();
        }

        @Test
        @DisplayName("a different execution nested inside one is refused, not silently substituted")
        void aDifferentExecutionNestedInsideOneIsRefused() {
            AccountInterestCalcJob.StepScopedChunkDelegate subject = scoped();
            subject.beforeStep(stepExecution(1L, PARM));

            assertThatIllegalStateException()
                    .isThrownBy(() -> subject.beforeStep(stepExecution(2L, "2023010100")))
                    .withMessageContaining("already scoped to this thread")
                    .withMessageContaining("abandon the five files it has open");

            assertThat(subject.scopedDelegate().orElseThrow().parmDate()).isEqualTo(PARM);
            subject.close();
        }

        @Test
        @DisplayName("the same execution arriving twice reuses its delegate: the reader is registered "
                + "both as a stream and as a listener")
        void theSameExecutionArrivingTwiceReusesItsDelegate() {
            AccountInterestCalcJob.StepScopedChunkDelegate subject = scoped();
            StepExecution execution = stepExecution(1L, PARM);

            subject.beforeStep(execution);
            ChunkDelegate first = subject.scopedDelegate().orElseThrow();
            subject.beforeStep(execution);

            assertThat(subject.scopedDelegate().orElseThrow()).isSameAs(first);
            assertThat(first.parmDate()).isEqualTo(PARM);
            subject.close();
        }

        @Test
        @DisplayName("building the step captures no delegate: the Step bean outlives every execution")
        void buildingTheStepCapturesNoDelegate() {
            AccountInterestCalcJob subject = Mockito.spy(new Doubles().job());

            subject.accountInterestCalcStep();

            Mockito.verify(subject, Mockito.never()).newChunkDelegate();
        }

        @Test
        @DisplayName("a step execution is required, and every callback outside a scope names itself")
        void callbacksOutsideAScopeAreRefused() {
            AccountInterestCalcJob.StepScopedChunkDelegate subject = scoped();

            assertThatNullPointerException().isThrownBy(() -> subject.beforeStep(null));
            assertThatNullPointerException().isThrownBy(() -> subject.afterStep(null));

            assertThatIllegalStateException().isThrownBy(() -> subject.open(new ExecutionContext()))
                    .withMessageContaining("'open'");
            assertThatIllegalStateException().isThrownBy(subject::read)
                    .withMessageContaining("'read'");
            assertThatIllegalStateException()
                    .isThrownBy(() -> subject.process(TranCatBalRecord.newInstance(ASCII)))
                    .withMessageContaining("'process'");
            assertThatIllegalStateException().isThrownBy(() -> subject.write(new Chunk<>()))
                    .withMessageContaining("'write'");
        }

        @Test
        @DisplayName("update contributes nothing, in scope or out: there is no position to store")
        void updateStoresNoPosition() {
            AccountInterestCalcJob.StepScopedChunkDelegate subject = scoped();
            ExecutionContext context = new ExecutionContext();

            subject.update(context);
            assertThat(context.isEmpty()).isTrue();

            subject.beforeStep(stepExecution(1L, PARM));
            subject.update(context);
            assertThat(context.isEmpty())
                    .as("a stored position is what would make a restart skip committed work")
                    .isTrue();
            subject.close();
        }

        @Test
        @DisplayName("afterStep leaves the exit status alone - the job listener owns the abend's status")
        void afterStepChangesNothing() {
            AccountInterestCalcJob.StepScopedChunkDelegate subject = scoped();

            assertThat(subject.afterStep(stepExecution(1L, PARM))).isNull();
        }

        @Test
        @DisplayName("a job is required to scope its delegates")
        void aJobIsRequired() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new AccountInterestCalcJob.StepScopedChunkDelegate(null));
        }

        @Test
        @DisplayName("the scoped delegate is the one the callbacks reach, lifecycle and all")
        void theScopedDelegateIsTheOneDriven() {
            Doubles doubles = new Doubles();
            AccountInterestCalcJob.StepScopedChunkDelegate subject =
                    new AccountInterestCalcJob.StepScopedChunkDelegate(doubles.job());

            subject.beforeStep(stepExecution(1L, PARM));
            subject.open(new ExecutionContext());

            assertThat(doubles.sysout.lines()).first()
                    .isEqualTo(AccountInterestCalcJob.START_OF_EXECUTION);
            assertThat(subject.scopedDelegate().orElseThrow().run()).isNotNull();

            subject.close();
            assertThat(subject.scopedDelegate()).isEmpty();
        }
    }

    @Nested
    @DisplayName("The DISCGRP key is masked and escaped in every log line - CWE-117")
    class DisclosureGroupLogDisclosure {
        private ch.qos.logback.classic.Logger accessLogger() {
            return (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(
                    DisclosureGroupRepository.class);
        }

        private List<String> capturedFor(String keyImage, java.util.function.Consumer<JdbcTemplate>
                seeding) {
            ch.qos.logback.classic.Logger logger = accessLogger();
            ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                    new ch.qos.logback.core.read.ListAppender<>();
            appender.start();
            logger.addAppender(appender);
            try {
                JdbcTemplate t = database();
                DisclosureGroupAccess access = new DisclosureGroupRepository(
                        t, bindings(), ASCII, RecordImageForm.CHARACTER);
                AccountInterestCalcJob.DisclosureGroupFile file = access.open();
                seeding.accept(t);
                file.readByKey(keyImage);
            } finally {
                logger.detachAppender(appender);
                appender.stop();
            }
            List<String> messages = new ArrayList<>();
            for (ch.qos.logback.classic.spi.ILoggingEvent event : appender.list) {
                messages.add(event.getFormattedMessage());
            }
            return messages;
        }

        private List<String> capturedForARefusedRead(String keyImage) {
            return capturedFor(keyImage,
                    t -> t.execute("DROP TABLE \"" + DISCGRP_DS + "\""));
        }

        private List<String> capturedForAMalformedRow(String keyImage) {
            return capturedFor(keyImage, t -> t.update(
                    "INSERT INTO \"" + DISCGRP_DS + "\" VALUES (?)", keyImage + "0012"));
        }

        private static String expectedKeyRendering(String keyImage) {
            int split = Math.min(DisclosureGroupRecord.DIS_ACCT_GROUP_ID_LENGTH, keyImage.length());
            return DiagnosticText.masked(keyImage.substring(0, split))
                    + DiagnosticText.singleLine(keyImage.substring(split));
        }

        @Test
        @DisplayName("a refused read logs the key masked: the group id never reaches the line")
        void aRefusedReadDisclosesNoGroupId() {
            List<String> logged = capturedForARefusedRead("A000000000010001");

            assertThat(logged).isNotEmpty();
            assertThat(logged).allSatisfy(message -> {
                assertThat(message).doesNotContain("A000000000010001");
                assertThat(message).doesNotContain("A000000000");
            });
            assertThat(logged).anySatisfy(message -> assertThat(message)
                    .contains("key '" + expectedKeyRendering("A000000000010001") + "'"));
        }

        @Test
        @DisplayName("a malformed row logs the key masked too - the same policy on every arm")
        void aMalformedRowDisclosesNoGroupId() {
            List<String> logged = capturedForAMalformedRow("A000000000010001");

            assertThat(logged).isNotEmpty();
            assertThat(logged).allSatisfy(message -> {
                assertThat(message).doesNotContain("A000000000010001");
                assertThat(message).doesNotContain("A000000000");
            });
            assertThat(logged).anySatisfy(message -> assertThat(message)
                    .contains("key '" + expectedKeyRendering("A000000000010001") + "'"));
        }

        @Test
        @DisplayName("a row with a null record image is NOT reported as absent: DB-05, and it is money")
        void aRowWithANullRecordImageIsNotReportedAsAbsent() {
            JdbcTemplate t = database();
            t.execute("INSERT INTO \"" + DISCGRP_DS + "\" VALUES (NULL)");
            DisclosureGroupAccess access = new DisclosureGroupRepository(
                    t, bindings(), ASCII, RecordImageForm.CHARACTER);

            try (AccountInterestCalcJob.DisclosureGroupFile file = access.open()) {
                AccountInterestCalcJob.DisclosureGroupRead read = file.readByKey("A000000000010001");
                assertThat(read.isNotFound())
                        .as("'23' here would send the account to the DEFAULT group")
                        .isFalse();
                assertThat(read.isFound()).isFalse();
                assertThat(read.status()).isNotEqualTo(FileStatus.NOT_FOUND);
                assertThat(read.record()).isEmpty();
            }
        }

        @Test
        @DisplayName("a probe the backend refuses reports the permanent error, never the DEFAULT retry")
        void aRefusedProbeDoesNotBecomeTheDefaultRetry() throws SQLException {
            DataSource dataSource = Mockito.mock(DataSource.class);
            Connection connection = Mockito.mock(Connection.class);
            Statement describe = Mockito.mock(Statement.class);
            ResultSet described = Mockito.mock(ResultSet.class);
            ResultSetMetaData metaData = Mockito.mock(ResultSetMetaData.class);
            Mockito.when(dataSource.getConnection()).thenReturn(connection);
            Mockito.when(connection.createStatement()).thenReturn(describe);
            Mockito.when(describe.executeQuery(Mockito.anyString())).thenReturn(described);
            Mockito.when(described.getMetaData()).thenReturn(metaData);
            Mockito.when(metaData.getColumnCount()).thenReturn(1);
            Mockito.when(metaData.getColumnName(1)).thenReturn(COL);
            PreparedStatement keyedRead = Mockito.mock(PreparedStatement.class);
            ResultSet noRows = Mockito.mock(ResultSet.class);
            Mockito.when(noRows.next()).thenReturn(false);
            Mockito.when(keyedRead.executeQuery()).thenReturn(noRows);
            Mockito.when(connection.prepareStatement(Mockito.anyString())).thenAnswer(invocation -> {
                if (invocation.<String>getArgument(0).endsWith("IS NULL")) {
                    throw new SQLException("the unreadable-row probe is refused");
                }
                return keyedRead;
            });
            DisclosureGroupAccess access = new DisclosureGroupRepository(
                    new JdbcTemplate(dataSource), bindings(), ASCII, RecordImageForm.CHARACTER);

            try (AccountInterestCalcJob.DisclosureGroupFile file = access.open()) {
                AccountInterestCalcJob.DisclosureGroupRead read = file.readByKey("A000000000010001");
                assertThat(read.isNotFound()).isFalse();
                assertThat(read.status()).isNotEqualTo(FileStatus.NOT_FOUND);
                assertThat(read.record()).isEmpty();
            }
        }

        @Test
        @DisplayName("a genuinely absent key still reports '23', so the DEFAULT retry is unchanged")
        void aGenuinelyAbsentKeyStillReportsNotFound() {
            JdbcTemplate t = database();
            DisclosureGroupAccess access = new DisclosureGroupRepository(
                    t, bindings(), ASCII, RecordImageForm.CHARACTER);

            try (AccountInterestCalcJob.DisclosureGroupFile file = access.open()) {
                AccountInterestCalcJob.DisclosureGroupRead read = file.readByKey("A000000000010001");
                assertThat(read.isNotFound()).isTrue();
                assertThat(read.status()).isEqualTo(FileStatus.NOT_FOUND);
                assertThat(read.record()).isEmpty();
            }
        }

        @Test
        @DisplayName("a carriage return in the key cannot split the entry into two")
        void aControlCharacterCannotForgeAnEntry() {
            String forged = "A00000000001\r\n01";

            for (List<String> logged : List.of(capturedForARefusedRead(forged),
                    capturedForAMalformedRow(forged))) {
                assertThat(logged).isNotEmpty();
                assertThat(logged).allSatisfy(message -> {
                    assertThat(message).doesNotContain("\r");
                    assertThat(message).doesNotContain("\n");
                });
            }
        }

        @Test
        @DisplayName("the diagnosis survives the masking: dataset and file status remain")
        void theDiagnosisIsStillThere() {
            String status = FileStatus.toStatusImage(
                    DisclosureGroupRepository.PERMANENT_ERROR_STATUS);

            assertThat(capturedForARefusedRead("A000000000010001")).anySatisfy(message -> {
                assertThat(message).contains(DISCGRP_DS);
                assertThat(message).contains(status);
            });
            assertThat(capturedForAMalformedRow("A000000000010001")).anySatisfy(message -> {
                assertThat(message).contains(DISCGRP_DS);
                assertThat(message).contains(status);
                assertThat(message).contains("app/cpy/CVTRA02Y.cpy");
            });
        }
    }

    @Nested
    @DisplayName("COBOL-TS carries the offset from Greenwich in COB-REST PIC X(05)")
    class TheGreenwichOffset {
        @Test
        @DisplayName("a zone west of Greenwich renders a leading minus and a zero-filled magnitude")
        void aNegativeOffset() {
            Doubles doubles = new Doubles();
            AccountInterestCalcJob subject = new AccountInterestCalcJob(
                    scaffolding(contracts(), bindings()), doubles.tcatbalRepository,
                    doubles.accountRepository, doubles.xrefRepository, doubles.transactionRepository,
                    doubles.discgrp, mockedUnitOfWork(),
                    new PresentBean<>(doubles.sysout),
                    Clock.fixed(Instant.parse("2022-07-18T12:34:56.780Z"),
                            ZoneOffset.ofHoursMinutes(-5, -30)));

            assertThat(subject.currentDate().rest()).isEqualTo("-0530")
                    .hasSize(5);
        }

        @Test
        @DisplayName("a zone east of Greenwich renders a leading plus, and UTC renders +0000")
        void aPositiveOffset() {
            Doubles east = new Doubles();
            AccountInterestCalcJob subject = new AccountInterestCalcJob(
                    scaffolding(contracts(), bindings()), east.tcatbalRepository,
                    east.accountRepository, east.xrefRepository, east.transactionRepository,
                    east.discgrp, mockedUnitOfWork(),
                    new PresentBean<>(east.sysout),
                    Clock.fixed(Instant.parse("2022-07-18T12:34:56.780Z"),
                            ZoneOffset.ofHoursMinutes(5, 45)));

            assertThat(subject.currentDate().rest()).isEqualTo("+0545");
            assertThat(new Doubles().job().currentDate().rest()).isEqualTo("+0000");
        }
    }

    @Nested
    @DisplayName("The unreachable ELSE at CBACT04C:219-221 - PRESERVED ON PURPOSE")
    class TheUnreachableElse {
        /**
         * A job whose run starts with {@code END-OF-FILE} holding a third value, so the loop body's
         * {@code ELSE} arm executes.
         */
        private final class PoisonedRunJob extends AccountInterestCalcJob {
            private InterestCalculationRun created;

            private PoisonedRunJob(Doubles doubles) {
                super(scaffolding(contracts(), bindings()), doubles.tcatbalRepository,
                        doubles.accountRepository, doubles.xrefRepository,
                        doubles.transactionRepository, doubles.discgrp, mockedUnitOfWork(),
                        new PresentBean<>(doubles.sysout), FIXED);
            }

            @Override
            InterestCalculationRun newRun(String parmDate, SysoutSink sysout) {
                InterestCalculationRun run = super.newRun(parmDate, sysout);
                run.workingStorage().moveEndOfFile("X");
                created = run;
                return run;
            }
        }

        @Test
        @DisplayName("the ELSE arm exists in the translated loop, and it rewrites the account")
        void theElseArmIsPresent() {
            Doubles doubles = new Doubles();
            when(doubles.accountFile.rewrite(Mockito.any(AccountRecord.class)))
                    .thenReturn(AccountRepository.WriteResult.notFound());
            AccountInterestCalcJob subject = new PoisonedRunJob(doubles);

            AbendException abend = assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> subject.calculateInterest(PARM, doubles.sysout))
                    .actual();

            verify(doubles.accountFile, times(1)).rewrite(Mockito.any(AccountRecord.class));
            assertThat(doubles.sysout.lines()).containsExactly(
                    AccountInterestCalcJob.START_OF_EXECUTION,
                    AccountInterestCalcJob.ERROR_REWRITING_ACCTFILE,
                    FileStatus.toDisplayLine(FileStatus.NOT_FOUND),
                    AbendException.ABEND_DISPLAY_TEXT);
            assertThat(abend.getReturnCode()).isEqualTo(AccountInterestCalcJob.APPL_RESULT_FATAL);
            verify(doubles.tcatbalFile, never()).readNext();
        }

        @Test
        @DisplayName("the arm completing normally would loop forever, which is why nothing may flush "
                + "at end of file")
        void theArmCompletingNormallyWouldNeverTerminate() {
            Doubles doubles = new Doubles();
            PoisonedRunJob subject = new PoisonedRunJob(doubles);
            when(doubles.accountFile.rewrite(Mockito.any(AccountRecord.class))).thenAnswer(invocation -> {
                subject.created.workingStorage()
                        .moveEndOfFile(AccountInterestCalcJob.END_OF_FILE_YES);
                return AccountRepository.WriteResult.written();
            });

            long records = subject.calculateInterest(PARM, doubles.sysout);

            assertThat(records).isZero();
            verify(doubles.accountFile, times(1)).rewrite(Mockito.any(AccountRecord.class));
            verify(doubles.tcatbalFile, never()).readNext();
            assertThat(doubles.sysout.lines()).containsExactly(
                    AccountInterestCalcJob.START_OF_EXECUTION,
                    AccountInterestCalcJob.END_OF_EXECUTION);
            verify(doubles.tcatbalFile).closeFile();
            verify(doubles.transactionFile).closeOutput();
        }

        @Test
        @DisplayName("a real pass NEVER takes the arm: the last group's interest is not rewritten")
        void aRealPassNeverRewritesTheFinalGroup() {
            Doubles doubles = new Doubles().withOneRecord();

            long records = doubles.job().calculateInterest(PARM, doubles.sysout);

            assertThat(records).isEqualTo(1L);
            verify(doubles.transactionFile).writeSequential(Mockito.any(TranRecord.class));
            verify(doubles.accountFile, never()).rewrite(Mockito.any(AccountRecord.class));
            assertThat(doubles.sysout.lines())
                    .contains(AccountInterestCalcJob.END_OF_EXECUTION)
                    .doesNotContain(AccountInterestCalcJob.ERROR_REWRITING_ACCTFILE);
        }
    }

    @Nested
    @DisplayName("the numeric-parity and structural gates hold over the source itself")
    class TheNumericParityGates {
        private String codeOf(String packageDirectory, String simpleName) throws IOException {
            Path relative = Path.of("app", "java", "src", "main", "java", "com", "vsergeychik",
                    "carddemo", packageDirectory, simpleName + ".java");
            Path candidate = Path.of("").toAbsolutePath();
            String source = null;
            while (candidate != null && source == null) {
                Path resolved = candidate.resolve(relative);
                if (Files.exists(resolved)) {
                    source = Files.readString(resolved, StandardCharsets.UTF_8);
                }
                candidate = candidate.getParent();
            }
            if (source == null) {
                throw new IllegalStateException(simpleName + ".java was not found from "
                        + Path.of("").toAbsolutePath());
            }
            return stripCommentsAndLiterals(source);
        }

        private String stripCommentsAndLiterals(String source) {
            StringBuilder code = new StringBuilder(source.length());
            int index = 0;
            while (index < source.length()) {
                char current = source.charAt(index);
                boolean hasNext = index + 1 < source.length();
                if (current == '/' && hasNext && source.charAt(index + 1) == '*') {
                    int end = source.indexOf("*/", index + 2);
                    index = end < 0 ? source.length() : end + 2;
                    code.append(' ');
                } else if (current == '/' && hasNext && source.charAt(index + 1) == '/') {
                    int end = source.indexOf('\n', index);
                    index = end < 0 ? source.length() : end;
                    code.append(' ');
                } else if (current == '"' || current == '\'') {
                    char quote = current;
                    index++;
                    while (index < source.length() && source.charAt(index) != quote) {
                        index += source.charAt(index) == '\\' ? 2 : 1;
                    }
                    index++;
                    code.append(' ');
                } else {
                    code.append(current);
                    index++;
                }
            }
            return code.toString();
        }

        @Test
        @DisplayName("G22 - neither the job nor the decimal policy names a binary floating-point type")
        void neitherTheJobNorTheDecimalPolicyNamesAFloatingPointType() throws IOException {
            for (String code : List.of(codeOf("account", "AccountInterestCalcJob"),
                    codeOf("common", "CobolDecimal"))) {
                assertThat(code)
                        .doesNotContain("double ")
                        .doesNotContain("float ")
                        .doesNotContain("Double")
                        .doesNotContain("Float")
                        .doesNotContain("doubleValue")
                        .doesNotContain("floatValue");
            }
        }

        @Test
        @DisplayName("G24 - RoundingMode.DOWN is the only mode either file names")
        void neitherTheJobNorTheDecimalPolicyNamesARoundingMode() throws IOException {
            String policy = codeOf("common", "CobolDecimal");
            String job = codeOf("account", "AccountInterestCalcJob");

            for (String forbidden : List.of("HALF_UP", "HALF_EVEN", "CEILING", "FLOOR", "HALF_DOWN",
                    "UNNECESSARY")) {
                assertThat(policy).as("CobolDecimal must not name %s", forbidden)
                        .doesNotContain(forbidden);
                assertThat(job).as("AccountInterestCalcJob must not name %s", forbidden)
                        .doesNotContain(forbidden);
            }
            assertThat(policy).contains("RoundingMode.DOWN");
            assertThat(job).doesNotContain("RoundingMode.");
            assertThat(job).contains("CobolDecimal.monthlyInterest");
        }

        @Test
        @DisplayName("G46 - no AWS.M2.CARDDEMO literal, and every DD resolves from configuration")
        void noDatasetNameIsWrittenInJava() throws IOException {
            String job = codeOf("account", "AccountInterestCalcJob");
            assertThat(job).doesNotContain("AWS.M2").doesNotContain("CARDDEMO.ACCTDATA")
                    .doesNotContain("VSAM.KSDS").doesNotContain("AIX.PATH");

            DatasetBindings b = bindings();
            for (String ddName : List.of(AccountInterestCalcJob.TCATBALF_DD_NAME,
                    AccountInterestCalcJob.XREFFILE_DD_NAME,
                    AccountInterestCalcJob.XREFFIL1_DD_NAME,
                    AccountInterestCalcJob.ACCTFILE_DD_NAME,
                    AccountInterestCalcJob.DISCGRP_DD_NAME,
                    TransactionRepository.SEQUENTIAL_OUTPUT_DD_NAME)) {
                assertThat(b.get(ddName)).as("%s must be configured", ddName).isNotNull();
                assertThat(b.get(ddName).dsname()).as("%s must resolve from configuration", ddName)
                        .isNotBlank().doesNotContain("AWS.M2.CARDDEMO");
            }
            assertThat(new DisclosureGroupRepository(database(), b, ASCII, RecordImageForm.CHARACTER)
                    .datasetName()).isEqualTo(DISCGRP_DS).doesNotContain("AWS.M2.CARDDEMO");
        }

        @Test
        @DisplayName("G52 - no wildcard import in the job")
        void theJobImportsEveryTypeExplicitly() throws IOException {
            assertThat(codeOf("account", "AccountInterestCalcJob").lines()
                    .filter(line -> line.startsWith("import "))
                    .filter(line -> line.endsWith(".*;"))
                    .toList())
                    .isEmpty();
        }

        @Test
        @DisplayName("G53 - every static field of the job and its nested types is final")
        void theJobHoldsNoMutableStaticState() {
            List<Class<?>> declared = new ArrayList<>();
            declared.add(AccountInterestCalcJob.class);
            declared.addAll(Arrays.asList(AccountInterestCalcJob.class.getDeclaredClasses()));

            List<String> mutable = new ArrayList<>();
            for (Class<?> type : declared) {
                for (Field field : type.getDeclaredFields()) {
                    if (field.isSynthetic() || !Modifier.isStatic(field.getModifiers())) {
                        continue;
                    }
                    if (!Modifier.isFinal(field.getModifiers())) {
                        mutable.add(type.getSimpleName() + '.' + field.getName());
                    }
                }
            }
            assertThat(mutable).isEmpty();

            assertThat(AccountInterestCalcJob.REQUIRED_STEPS).hasSize(1);
            assertThatExceptionOfType(UnsupportedOperationException.class).isThrownBy(() ->
                    AccountInterestCalcJob.REQUIRED_STEPS.add(new StepContract("STEP99", "X", false)));
        }

        @Test
        @DisplayName("B7 - the job reads no clock but the one it is given")
        void theJobReadsNoAmbientClock() throws IOException {
            String job = codeOf("account", "AccountInterestCalcJob");
            assertThat(job)
                    .doesNotContain("Instant.now")
                    .doesNotContain("LocalDate.now")
                    .doesNotContain("LocalDateTime.now()")
                    .doesNotContain("System.currentTimeMillis")
                    .doesNotContain("new Date(")
                    .doesNotContain("Clock.systemDefaultZone")
                    .doesNotContain("Clock.systemUTC");

            AccountInterestCalcJob subject = job(database(), bindings(), new CapturedSysout());
            assertThat(subject.clock()).isSameAs(FIXED);
            assertThat(subject.db2FormatTimestamp()).isEqualTo(subject.db2FormatTimestamp())
                    .isEqualTo("2022-07-18-12.34.56.780000");
        }
    }

    @Nested
    @DisplayName("the observable statement order, CBACT04C:352-356 and :224-228")
    class TheStatementOrder {
        @Test
        @DisplayName("post the total, zero both cycle amounts, and only then REWRITE")
        void theBreakPostsThenZeroesBothCyclesThenRewrites() {
            Doubles doubles = new Doubles();
            TranCatBalRecord first = TranCatBalRecord.decode(
                    tcatbalImage(11L, "01", 1, "1000.00"), ASCII);
            TranCatBalRecord second = TranCatBalRecord.decode(
                    tcatbalImage(22L, "01", 1, "1000.00"), ASCII);
            when(doubles.tcatbalFile.readNext()).thenReturn(
                    TranCatBalRepository.ReadResult.found(first),
                    TranCatBalRepository.ReadResult.found(second),
                    TranCatBalRepository.ReadResult.endOfFile());
            when(doubles.accountFile.readByKey(anyLong())).thenReturn(
                    AccountRepository.ReadResult.found(
                            AccountRecord.decode(acctImage(11L, "500.00", "A000000000"), ASCII)),
                    AccountRepository.ReadResult.found(
                            AccountRecord.decode(acctImage(22L, "700.00", "A000000000"), ASCII)));
            when(doubles.xrefRepository.readByAccountIdViaAltIndex(anyLong()))
                    .thenReturn(xrefFound(CardXrefRepository.BASE_DD_NAME,
                            new CardXrefRecord("4444333322221111", 1, 11L)));
            when(doubles.transactionFile.writeSequential(Mockito.any(TranRecord.class)))
                    .thenReturn(TransactionRepository.WriteResult.written(
                            TransactionRepository.SEQUENTIAL_OUTPUT_DD_NAME));
            doubles.discgrp.yielding(AccountInterestCalcJob.DisclosureGroupRead.found(
                    DisclosureGroupRecord.decode(
                            discgrpImage("A000000000", "01", 1, "12.50"), ASCII)));

            List<BigDecimal> atRewrite = new ArrayList<>();
            when(doubles.accountFile.rewrite(Mockito.any(AccountRecord.class)))
                    .thenAnswer(invocation -> {
                        AccountRecord presented = invocation.getArgument(0, AccountRecord.class);
                        atRewrite.add(presented.getAcctCurrBal());
                        atRewrite.add(presented.getAcctCurrCycCredit());
                        atRewrite.add(presented.getAcctCurrCycDebit());
                        return AccountRepository.WriteResult.written();
                    });

            long processed = doubles.job().calculateInterest(PARM, doubles.sysout);

            assertThat(processed).isEqualTo(2L);
            assertThat(atRewrite).containsExactly(new BigDecimal("510.41"), new BigDecimal("0.00"),
                    new BigDecimal("0.00"));
            assertThat(atRewrite).allSatisfy(value -> assertThat(value.scale()).isEqualTo(2));

            InOrder order = Mockito.inOrder(doubles.tcatbalFile, doubles.accountFile,
                    doubles.transactionFile);
            order.verify(doubles.tcatbalFile).readNext();
            order.verify(doubles.accountFile).readByKey(11L);
            order.verify(doubles.transactionFile).writeSequential(Mockito.any(TranRecord.class));
            order.verify(doubles.tcatbalFile).readNext();
            order.verify(doubles.accountFile).rewrite(Mockito.any(AccountRecord.class));
            order.verify(doubles.accountFile).readByKey(22L);

            verify(doubles.accountFile, times(1)).rewrite(Mockito.any(AccountRecord.class));

            ArgumentCaptor<AccountRecord> rewritten = ArgumentCaptor.forClass(AccountRecord.class);
            verify(doubles.accountFile).rewrite(rewritten.capture());
            String image = rewritten.getValue().toFixedWidthString();
            assertThat(image).hasSize(AccountRecord.RECORD_LENGTH);
            assertThat(image).hasSize(300);
            assertThat(image.substring(AccountRecord.FILLER_OFFSET))
                    .isEqualTo(" ".repeat(AccountRecord.FILLER_LENGTH))
                    .hasSize(178);
        }

        @Test
        @DisplayName("the five closes run in source order, DISCGRP third")
        void theFiveClosesRunInSourceOrder() {
            List<String> verbs = new ArrayList<>();
            Doubles doubles = new Doubles();
            doubles.discgrp.recordingVerbsInto(verbs);
            when(doubles.tcatbalFile.closeFile()).thenAnswer(invocation -> {
                verbs.add(AccountInterestCalcJob.TCATBALF_DD_NAME);
                return FileStatus.OK;
            });
            when(doubles.xrefCursor.closeBrowse()).thenAnswer(invocation -> {
                verbs.add(AccountInterestCalcJob.XREFFILE_DD_NAME);
                return FileStatus.OK;
            });
            when(doubles.accountFile.closeFile()).thenAnswer(invocation -> {
                verbs.add(AccountInterestCalcJob.ACCTFILE_DD_NAME);
                return FileStatus.OK;
            });
            when(doubles.transactionFile.closeOutput()).thenAnswer(invocation -> {
                verbs.add(AccountInterestCalcJob.TRANSACT_DD_NAME);
                return FileStatus.OK;
            });

            doubles.job().calculateInterest(PARM, doubles.sysout);

            assertThat(verbs).containsExactly(
                    AccountInterestCalcJob.TCATBALF_DD_NAME,
                    AccountInterestCalcJob.XREFFILE_DD_NAME,
                    AccountInterestCalcJob.DISCGRP_DD_NAME,
                    AccountInterestCalcJob.ACCTFILE_DD_NAME,
                    AccountInterestCalcJob.TRANSACT_DD_NAME);

            InOrder order = Mockito.inOrder(doubles.tcatbalFile, doubles.xrefCursor,
                    doubles.accountFile, doubles.transactionFile);
            order.verify(doubles.tcatbalFile).closeFile();
            order.verify(doubles.xrefCursor).closeBrowse();
            order.verify(doubles.accountFile).closeFile();
            order.verify(doubles.transactionFile).closeOutput();
        }

        @Test
        @DisplayName("the five opens run in source order, DISCGRP third")
        void theFiveOpensRunInSourceOrder() {
            Doubles doubles = new Doubles();
            doubles.job().calculateInterest(PARM, doubles.sysout);

            InOrder order = Mockito.inOrder(doubles.tcatbalRepository, doubles.xrefRepository,
                    doubles.accountRepository, doubles.transactionRepository);
            order.verify(doubles.tcatbalRepository).open(TranCatBalRepository.OpenMode.INPUT);
            order.verify(doubles.xrefRepository).openBrowse();
            order.verify(doubles.accountRepository).open(AccountRepository.OpenMode.I_O);
            order.verify(doubles.transactionRepository).openOutput(any(), any());
        }
    }

    @Nested
    @DisplayName("the disclosure-group key is sanitized before it is logged")
    class KeyDiagnostics {
        private static String subjectSource() throws IOException {
            // DisclosureGroupRepository, not the job: the DISCGRP access path - and with it every log
            // statement that mentions the key - lives in the @Repository that owns that dataset
            // (gate G10). The job holds the port, the repository holds the JDBC adapter and its
            // diagnostics, so this is where the masking has to be proved.
            return sourceOf("DisclosureGroupRepository.java");
        }

        /**
         * Reads one of this package's main sources from the checkout.
         *
         * @param fileName the simple file name under {@code app/java/src/main/java/.../account}
         * @return its text
         * @throws IOException           if it cannot be read
         * @throws IllegalStateException if it cannot be located from the working directory
         */
        private static String sourceOf(String fileName) throws IOException {
            Path relative = Path.of("app", "java", "src", "main", "java", "com", "vsergeychik",
                    "carddemo", "account", fileName);
            Path candidate = Path.of("").toAbsolutePath();
            while (candidate != null) {
                Path resolved = candidate.resolve(relative);
                if (Files.exists(resolved)) {
                    return Files.readString(resolved, StandardCharsets.UTF_8);
                }
                candidate = candidate.getParent();
            }
            throw new IllegalStateException(fileName + " was not found from "
                    + Path.of("").toAbsolutePath());
        }

        @Test
        @DisplayName("no log statement interpolates the raw key image")
        void noLogStatementCarriesTheRawKey() throws IOException {
            List<String> offending = new ArrayList<>();
            for (String statement : logStatementsOf(subjectSource())) {
                if (statement.contains("keyImage") && !statement.contains("keyForDiagnostics(keyImage)")) {
                    offending.add(statement.replaceAll("\\s+", " ").strip());
                }
            }

            assertThat(offending)
                    .as("these LOG statements interpolate DIS-GROUP-KEY without sanitizing it")
                    .isEmpty();
        }

        private static List<String> logStatementsOf(String source) {
            List<String> statements = new ArrayList<>();
            int from = source.indexOf("LOG.");
            while (from >= 0) {
                int depth = 0;
                int index = source.indexOf('(', from);
                int close = -1;
                for (int scan = index; scan >= 0 && scan < source.length(); scan++) {
                    char character = source.charAt(scan);
                    if (character == '(') {
                        depth++;
                    } else if (character == ')') {
                        depth--;
                        if (depth == 0) {
                            close = scan;
                            break;
                        }
                    }
                }
                if (close < 0) {
                    break;
                }
                statements.add(source.substring(from, close + 1));
                from = source.indexOf("LOG.", close);
            }
            return statements;
        }

        @Test
        @DisplayName("every disclosure-group failure log sanitizes the key")
        void everyFailureLogSanitizes() throws IOException {
            String source = subjectSource();

            assertThat(source.split("keyForDiagnostics\\(keyImage\\)", -1).length - 1)
                    .as("the read failure, the absent image, the malformed width, and the two lines the "
                            + "absence proof emits - a refused probe and a proved-present unreadable row")
                    .isEqualTo(5);
        }

        @Test
        @DisplayName("the masked rendering hides the account group and keeps the classification codes")
        void theMaskedRenderingKeepsWhatDiagnosisNeeds() {
            String key = "GROUPZZZZZ" + "01" + "0002";
            assertThat(key).hasSize(DisclosureGroupRecord.DIS_GROUP_KEY_LENGTH);
            String masked = DiagnosticText.masked(
                    key.substring(0, DisclosureGroupRecord.DIS_ACCT_GROUP_ID_LENGTH))
                    + DiagnosticText.singleLine(
                            key.substring(DisclosureGroupRecord.DIS_ACCT_GROUP_ID_LENGTH));

            assertThat(masked)
                    .as("the group is masked but its shape survives, and the codes stay legible")
                    .doesNotContain("GROUP")
                    .endsWith("010002")
                    .hasSize(DisclosureGroupRecord.DIS_GROUP_KEY_LENGTH);
        }

        @Test
        @DisplayName("a control character in the stored key cannot break the log line in two")
        void aControlCharacterCannotForgeASecondRecord() {
            String injected = "0000000001" + "0\n" + "0002";
            String rendered = DiagnosticText.masked(injected.substring(0,
                    DisclosureGroupRecord.DIS_ACCT_GROUP_ID_LENGTH))
                    + DiagnosticText.singleLine(injected.substring(
                            DisclosureGroupRecord.DIS_ACCT_GROUP_ID_LENGTH));

            assertThat(rendered).doesNotContain("\n").doesNotContain("\r");
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
