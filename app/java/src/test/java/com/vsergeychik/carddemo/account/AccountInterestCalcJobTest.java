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
import com.vsergeychik.carddemo.common.AbendException;
import com.vsergeychik.carddemo.common.CobolDecimal;
import com.vsergeychik.carddemo.common.FileStatus;
import com.vsergeychik.carddemo.common.RecordImageForm;
import com.vsergeychik.carddemo.config.BatchConfig;
import com.vsergeychik.carddemo.config.BatchConfig.JobContract;
import com.vsergeychik.carddemo.config.BatchConfig.JobContracts;
import com.vsergeychik.carddemo.config.BatchConfig.JobParameterContract;
import com.vsergeychik.carddemo.config.BatchConfig.StepContract;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBinding;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBindings;
import com.vsergeychik.carddemo.transaction.TranCatBalRepository;
import com.vsergeychik.carddemo.transaction.TransactionRepository;
import com.vsergeychik.carddemo.transaction.model.TranCatBalRecord;
import com.vsergeychik.carddemo.transaction.model.TranRecord;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies {@link AccountInterestCalcJob} against {@code app/cbl/CBACT04C.cbl} and
 * {@code app/jcl/INTCALC.jcl}, the only authorities for what this program does.
 *
 * <p>The program is the arithmetic heart of the {@code account} package, so the assertions here are
 * organised around the acceptance gates it carries rather than around its methods:
 *
 * <ul>
 *   <li><strong>G19 / G20 / G21</strong> - every generated transaction is exactly
 *       {@value TranRecord#RECORD_LENGTH} bytes with its {@code FILLER} present, and both 50-byte input
 *       records round-trip. {@code DIS-GROUP-KEY} is asserted at 16 characters and
 *       {@code TRAN-CAT-KEY} at 17, because both records total 50 bytes and confusing the two shifts
 *       {@code DIS-INT-RATE} by one byte while every width check still passes.</li>
 *   <li><strong>G23 / G24 / G25</strong> - {@code COMPUTE WS-MONTHLY-INT = (TRAN-CAT-BAL *
 *       DIS-INT-RATE) / 1200} at {@code app/cbl/CBACT04C.cbl:464-465} carries no {@code ROUNDED}, so the
 *       store truncates. One case is chosen whose exact quotient has a third decimal digit of five or
 *       more, which is the only kind of case that can tell {@link RoundingMode#DOWN} from
 *       {@code HALF_UP}.</li>
 *   <li><strong>G26</strong> - {@code 1400-COMPUTE-FEES} ({@code :518-520}) is a no-op that must still
 *       be invoked from {@code :216}: once per interest computation, inside the
 *       {@code IF DIS-INT-RATE NOT = 0} guard, and never when the rate is zero.</li>
 *   <li><strong>G27</strong> - the account break adds the accumulated interest to
 *       {@code ACCT-CURR-BAL}, zeroes both cycle amounts and only then rewrites
 *       ({@code :352-356}), and the rewritten record is 300 bytes.</li>
 *   <li><strong>G28</strong> - each of the program's four {@code ADD}s and its single {@code COMPUTE}
 *       has a targeted assertion. There is no {@code MULTIPLY} and no {@code DIVIDE} anywhere in
 *       {@code CBACT04C}, and the {@code / 1200} above is the only division in the whole estate.</li>
 *   <li><strong>G35</strong> - each of the program's {@code CALL 'CEE3ABD'} paths raises an
 *       {@link AbendException} carrying {@code RETURN-CODE} 12, after emitting its own message and the
 *       rendered file status and nothing else.</li>
 *   <li><strong>G49 / G50</strong> - every guard chain is driven both ways, including the arms that
 *       only a refusing dataset can reach, which is why the second half of this class replaces the
 *       H2-backed repositories with doubles.</li>
 * </ul>
 *
 * <p>Three preserved behaviours are asserted as behaviours rather than tolerated as quirks, because a
 * later reader would otherwise be tempted to repair them and would fail every parity case for this
 * program:
 *
 * <ol>
 *   <li>The {@code ELSE} at {@code :219-221} is unreachable, so the final account group's accumulated
 *       interest is never rewritten. There is no end-of-file flush, and
 *       {@link TheUnreachableElse} says so in as many words.</li>
 *   <li>{@code STRING ... INTO TRAN-DESC} at {@code :485-489} does not blank the remainder of its
 *       receiver, so bytes 25 to 100 carry the previous iteration's tail.</li>
 *   <li>{@code WS-TRANID-SUFFIX} is never reset, so it counts across account boundaries.</li>
 * </ol>
 *
 * <p>A fourth is the {@code '23'} window in {@code 1200-GET-INTEREST-RATE}: {@code READ ... INTO}
 * transfers on success only, so a missing group leaves the previous group's record in place until the
 * {@code 'DEFAULT   '} retry succeeds - and the retry has no {@code INVALID KEY} phrase, which is what
 * makes a second {@code '23'} fatal.
 *
 * <p>Two literals are wrong in the source and are preserved verbatim: {@code 0200-DISCGRP-OPEN} reports
 * {@code 'ERROR OPENING DALY REJECTS FILE'} ({@code :281}), and {@code 0100-XREFFILE-OPEN} appends the
 * file status to its own message ({@code :263}).
 *
 * <p>Nothing here launches a job. The arithmetic and the paragraph guards are reached through
 * package-visible entry points, exactly as practice B10 and gate G51 require, so a failure names a
 * paragraph rather than a framework.
 */
class AccountInterestCalcJobTest {

    private static final Charset ASCII = StandardCharsets.US_ASCII;
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

    // -------------------------------------------------------------------------------------- helpers

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
        JobContracts c = new JobContracts();
        c.put(AccountInterestCalcJob.JOB_KEY, new JobContract(AccountInterestCalcJob.PROGRAM_ID,
                parameters, List.of(step), null, Map.of()));
        return c;
    }

    private static BatchConfig scaffolding(JobContracts contracts, DatasetBindings bindings) {
        return new BatchConfig(new PresentBean<>(Mockito.mock(JobRepository.class)),
                new PresentBean<>(Mockito.mock(PlatformTransactionManager.class)), contracts, bindings);
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
                new TransactionRepository(t, b, ASCII, RecordImageForm.CHARACTER),
                AccountInterestCalcJob.carddemoDisclosureGroupAccess(t, b, ASCII,
                        RecordImageForm.CHARACTER),
                new PresentBean<>(sysout), new PresentBean<>(FIXED));
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

    /** One account, one category: balance 1000.00 at 12.50 percent => 10.41 truncated. */
    private static JdbcTemplate singleAccountDatabase() {
        JdbcTemplate t = database();
        seed(t, TCATBAL_DS, tcatbalImage(11L, "01", 1, "1000.00"));
        seed(t, ACCT_DS, acctImage(11L, "500.00", "A000000000"));
        seed(t, XREF_DS, xrefImage("4444333322221111", 1, 11L));
        seed(t, XREF_AIX_DS, xrefImage("4444333322221111", 1, 11L));
        seed(t, DISCGRP_DS, discgrpImage("A000000000", "01", 1, "12.50"));
        return t;
    }

    // ------------------------------------------------------------------- G19/G21 and key geometry

    @Test
    void keyGeometryIsAssertedAndCorrect() {
        assertThat(AccountInterestCalcJob.verifyDeclaredKeyGeometry()).isEqualTo(16);
        assertThat(DisclosureGroupRecord.DIS_GROUP_KEY_LENGTH).isEqualTo(16);
        assertThat(TranCatBalRecord.TRAN_CAT_KEY_LENGTH).isEqualTo(17);
        assertThat(AccountInterestCalcJob.PARM_DATE_WIDTH
                + AccountInterestCalcJob.TRANID_SUFFIX_WIDTH).isEqualTo(TranRecord.TRAN_ID_LENGTH);
    }

    // ---------------------------------------------------------------------------------------- G25

    @Test
    void monthlyInterestTruncatesRatherThanRounds() {
        // 1000.00 * 12.50 / 1200 = 10.41666... -> 10.41 under DOWN, 10.42 under HALF_UP.
        BigDecimal down = AccountInterestCalcJob.computeMonthlyInterest(
                new BigDecimal("1000.00"), new BigDecimal("12.50"));
        assertThat(down).isEqualTo(new BigDecimal("10.41"));
        assertThat(new BigDecimal("1000.00").multiply(new BigDecimal("12.50"))
                .divide(new BigDecimal("1200"), 2, RoundingMode.HALF_UP))
                .isEqualTo(new BigDecimal("10.42"));
        assertThat(down.scale()).isEqualTo(2);
    }

    @Test
    void monthlyInterestUsesTheFixtureRates() {
        // discgrp.txt row 1: group A000000000, type 01, cat 0001, rate 15.00.
        BigDecimal fifteen = AccountInterestCalcJob.computeMonthlyInterest(
                new BigDecimal("1234.56"), new BigDecimal("15.00"));
        // 1234.56 * 15.00 = 18518.4000; / 1200 = 15.43200 -> 15.43
        assertThat(fifteen).isEqualTo(new BigDecimal("15.43"));
        assertThat(AccountInterestCalcJob.computeMonthlyInterest(
                new BigDecimal("-1000.00"), new BigDecimal("12.50")))
                .isEqualTo(new BigDecimal("-10.41"));
    }

    // ---------------------------------------------------------------------------------------- G26

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
    }

    @Test
    void feesChangesNothing() {
        JdbcTemplate t = singleAccountDatabase();
        AccountInterestCalcJob job = job(t, bindings(), new CapturedSysout());
        // Called on its own it must do nothing at all - no exception, no state.
        job.computeFees();
        job.computeFees();
        assertThat(rows(t, SYSTRAN_DS)).isEmpty();
    }

    // ------------------------------------------------------------------------------- G19/G20/G21

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
    }

    // ---------------------------------------------------------------------------- DB2 timestamp

    @Test
    void db2TimestampHasThreeHyphensAndThreeDots() {
        AccountInterestCalcJob job = job(database(), bindings(), new CapturedSysout());
        String ts = job.db2FormatTimestamp();
        assertThat(ts).hasSize(26).isEqualTo("2022-07-18-12.34.56.780000");
        assertThat(ts.chars().filter(c -> c == '-').count()).isEqualTo(3L);
        assertThat(ts.chars().filter(c -> c == '.').count()).isEqualTo(3L);
        assertThat(ts.charAt(10)).isEqualTo('-');
        // COBOL-TS is 4+2+2+2+2+2+2+5 = 21: yyyy mm dd hh mi ss hundredths +hhmm
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

    // -------------------------------------------------------------------------------- G27 ordering

    @Test
    void accountBreakPostsInterestZeroesBothCyclesThenRewrites() {
        JdbcTemplate t = database();
        // Two accounts, so the FIRST one is closed out by the second's break.
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

        // Account 11 was closed out: 500.00 + 10.41, both cycle amounts zeroed.
        assertThat(first.getAcctCurrBal()).isEqualTo(new BigDecimal("510.41"));
        assertThat(first.getAcctCurrCycCredit()).isEqualTo(new BigDecimal("0.00"));
        assertThat(first.getAcctCurrCycDebit()).isEqualTo(new BigDecimal("0.00"));

        // THE UNREACHABLE ELSE: account 22 is the LAST group and is NEVER rewritten. Its cycle
        // amounts and balance are exactly as seeded. This is the legacy defect, asserted on purpose.
        assertThat(second.getAcctCurrBal()).isEqualTo(new BigDecimal("700.00"));
        assertThat(second.getAcctCurrCycCredit()).isEqualTo(new BigDecimal("111.11"));
        assertThat(second.getAcctCurrCycDebit()).isEqualTo(new BigDecimal("222.22"));

        // The suffix is never reset: two transactions, 000001 then 000002.
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
        // Account 11 accumulated two categories: 10.41 + 10.41 = 20.82.
        AccountRecord first = rows(t, ACCT_DS).stream().map(i -> AccountRecord.decode(i, ASCII))
                .filter(a -> a.getAcctId() == 11L).findFirst().orElseThrow();
        assertThat(first.getAcctCurrBal()).isEqualTo(new BigDecimal("20.82"));
    }

    // -------------------------------------------------------------------------- TRAN-DESC residue

    @Test
    void tranDescKeepsThePreviousIterationsTailBytes() {
        JdbcTemplate t = database();
        // The first account id is 11 digits wide; the second too, so the 24-character prefix is the
        // same width. To see residue we overwrite the record area's tail via a long first description
        // is not possible - instead we assert the run's live TranRecord keeps 76 spaces beyond 24 and
        // that a manual STRING over a dirtied area preserves the tail.
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

        // And the verb itself does not blank the receiver: dirty the tail, then STRING 24 characters.
        TranRecord dirty = new TranRecord(ASCII);
        dirty.moveTranDesc("X".repeat(100));
        dirty.stringIntoTranDesc("Int. for a/c ", "00000000011");
        assertThat(dirty.tranDesc()).isEqualTo("Int. for a/c 00000000011" + "X".repeat(76));
    }

    // ---------------------------------------------------------------- DISCGRP '23' retry and abend

    @Test
    void aMissingGroupRetriesWithTheDefaultGroupPaddedToTen() {
        JdbcTemplate t = database();
        seed(t, TCATBAL_DS, tcatbalImage(11L, "01", 1, "1000.00"));
        seed(t, ACCT_DS, acctImage(11L, "0.00", "ZZZZZZZZZZ"));
        seed(t, XREF_DS, xrefImage("4444333322221111", 1, 11L));
        seed(t, XREF_AIX_DS, xrefImage("4444333322221111", 1, 11L));
        // No ZZZZZZZZZZ group; only DEFAULT, padded to ten by the mover.
        seed(t, DISCGRP_DS, discgrpImage("DEFAULT", "01", 1, "6.00"));
        CapturedSysout sysout = new CapturedSysout();

        job(t, bindings(), sysout).calculateInterest(PARM, sysout);

        assertThat(sysout.lines()).contains(AccountInterestCalcJob.DISCLOSURE_GROUP_RECORD_MISSING,
                AccountInterestCalcJob.TRY_WITH_DEFAULT_GROUP_CODE);
        // 1000.00 * 6.00 / 1200 = 5.00
        assertThat(TranRecord.decode(rows(t, SYSTRAN_DS).get(0), ASCII).tranAmt())
                .isEqualTo(new BigDecimal("5.00"));
    }

    @Test
    void aMissingDefaultGroupAbendsWithReturnCodeTwelve() {
        JdbcTemplate t = database();
        seed(t, TCATBAL_DS, tcatbalImage(11L, "01", 1, "1000.00"));
        seed(t, ACCT_DS, acctImage(11L, "0.00", "ZZZZZZZZZZ"));
        seed(t, XREF_DS, xrefImage("4444333322221111", 1, 11L));
        seed(t, XREF_AIX_DS, xrefImage("4444333322221111", 1, 11L));
        // Neither the account's group nor DEFAULT exists.
        CapturedSysout sysout = new CapturedSysout();
        AccountInterestCalcJob job = job(t, bindings(), sysout);

        assertThatExceptionOfType(AbendException.class)
                .isThrownBy(() -> job.calculateInterest(PARM, sysout))
                .satisfies(abend -> {
                    assertThat(abend.getReturnCode()).isEqualTo(12);
                    assertThat(abend.getProgram()).isEqualTo("CBACT04C");
                    assertThat(abend.getAbendCode()).hasValue(999);
                });
        assertThat(sysout.lines()).contains(AccountInterestCalcJob.ERROR_READING_DEFAULT_DISCGRP,
                AbendException.ABEND_DISPLAY_TEXT);
        // The closes never ran, so the closing banner was never emitted.
        assertThat(sysout.lines()).doesNotContain(AccountInterestCalcJob.END_OF_EXECUTION);
    }

    // ------------------------------------------------------------------- the missing-account paths

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

    // ---------------------------------------------------------------- banners and the raw DISPLAY

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

    // ------------------------------------------------------- the unreachable ELSE, driven directly

    @Test
    void theUnreachableElseIsPresentAndReachableThroughAThirdFlagValue() {
        JdbcTemplate t = singleAccountDatabase();
        CapturedSysout sysout = new CapturedSysout();
        AccountInterestCalcJob job = job(t, bindings(), sysout);
        InterestCalculationRun run = job.newRun(PARM, sysout);
        run.openFiles();
        try {
            // A third value is neither 'Y' nor 'N', so the loop body's ELSE arm runs - which in COBOL
            // is the only way L220 can ever be reached. It rewrites the uninitialised ACCOUNT-RECORD,
            // whose key is 00000000000, and that account does not exist, so it abends.
            run.workingStorage().moveEndOfFile("X");
            assertThat(run.workingStorage().endOfFileIsYes()).isFalse();
            assertThat(run.workingStorage().endOfFileIsNo()).isFalse();
            assertThatExceptionOfType(AbendException.class).isThrownBy(run::updateAccount);
            assertThat(sysout.lines()).contains(AccountInterestCalcJob.ERROR_REWRITING_ACCTFILE);
        } finally {
            run.release();
        }
    }

    // ------------------------------------------------------------------------- WORKING-STORAGE unit

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

    // ----------------------------------------------------------------------- the seam and geometry

    @Test
    void theDisclosureGroupSeamReportsFoundAndNotFound() {
        JdbcTemplate t = database();
        DatasetBindings b = bindings();
        seed(t, DISCGRP_DS, discgrpImage("A000000000", "01", 1, "12.50"));
        DisclosureGroupAccess access = AccountInterestCalcJob.carddemoDisclosureGroupAccess(t, b, ASCII,
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
        DisclosureGroupAccess access = AccountInterestCalcJob.carddemoDisclosureGroupAccess(t, bindings(),
                ASCII, RecordImageForm.CHARACTER);
        var file = access.open();
        assertThatIllegalArgumentException().isThrownBy(() -> file.readByKey("A000000000010001X"));
        file.close();
    }

    @Test
    void aSeventeenByteDisclosureKeyIsRefusedAtConstruction() {
        JdbcTemplate t = database();
        assertThatIllegalStateException().isThrownBy(() ->
                AccountInterestCalcJob.carddemoDisclosureGroupAccess(t, bindings(50, 17), ASCII,
                        RecordImageForm.CHARACTER))
                .withMessageContaining("key-length must be 16");
    }

    @Test
    void aWrongRecordWidthIsRefusedAtConstruction() {
        JdbcTemplate t = database();
        assertThatIllegalStateException().isThrownBy(() ->
                AccountInterestCalcJob.carddemoDisclosureGroupAccess(t, bindings(60, 16), ASCII,
                        RecordImageForm.CHARACTER));
    }

    // ---------------------------------------------------------------------- the chunk step delegate

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
        // Closing an unopened delegate is a no-op, not a failure.
        delegate.close();
    }

    // ----------------------------------------------------------------- contract and wiring guards

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
                new TransactionRepository(t, b, ASCII, RecordImageForm.CHARACTER),
                AccountInterestCalcJob.carddemoDisclosureGroupAccess(t, b, ASCII,
                        RecordImageForm.CHARACTER),
                new PresentBean<>(new CapturedSysout()), new PresentBean<>(FIXED)));

        JobContracts wrongProgram = contracts(new StepContract("STEP15", "CBACT01C", false),
                List.of(new JobParameterContract(BatchConfig.PARM_DATE_PARAMETER, "string", PARM)));
        assertThatIllegalStateException().isThrownBy(() -> new AccountInterestCalcJob(
                scaffolding(wrongProgram, b),
                new TranCatBalRepository(t, b, ASCII, RecordImageForm.CHARACTER),
                new AccountRepository(t, b, ASCII, RecordImageForm.CHARACTER),
                new CardXrefRepository(t, b, ASCII, RecordImageForm.CHARACTER),
                new TransactionRepository(t, b, ASCII, RecordImageForm.CHARACTER),
                AccountInterestCalcJob.carddemoDisclosureGroupAccess(t, b, ASCII,
                        RecordImageForm.CHARACTER),
                new PresentBean<>(new CapturedSysout()), new PresentBean<>(FIXED)));

        JobContracts noParm = contracts(new StepContract("STEP15", "CBACT04C", false), List.of());
        assertThatIllegalStateException().isThrownBy(() -> new AccountInterestCalcJob(
                scaffolding(noParm, b),
                new TranCatBalRepository(t, b, ASCII, RecordImageForm.CHARACTER),
                new AccountRepository(t, b, ASCII, RecordImageForm.CHARACTER),
                new CardXrefRepository(t, b, ASCII, RecordImageForm.CHARACTER),
                new TransactionRepository(t, b, ASCII, RecordImageForm.CHARACTER),
                AccountInterestCalcJob.carddemoDisclosureGroupAccess(t, b, ASCII,
                        RecordImageForm.CHARACTER),
                new PresentBean<>(new CapturedSysout()), new PresentBean<>(FIXED)));
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

    // ------------------------------------------------------------------------- the fixture rates

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

    // =================================================================================================
    // The refusing-dataset arms.
    //
    // app/cbl/CBACT04C.cbl checks a FILE STATUS after every single file operation, and each check
    // carries its own message. A healthy backend never reaches any of those arms, so the doubles below
    // exist to make one chosen operation refuse while every other behaves. That is the only way to prove
    // that the twelve messages are attached to the twelve paragraphs the source attaches them to - a
    // transposition between two of them is invisible to a happy-path test and would be a parity failure
    // on every abending run.
    // =================================================================================================

    /**
     * A scripted {@code DISCGRP} access path. There is no {@code DisclosureGroupRepository} in the
     * module - {@code CVTRA02Y} has exactly one consumer in the estate and it is this program - so the
     * seam being faked here is the one the job publishes itself.
     */
    private static final class ScriptedDisclosureGroupAccess
            implements AccountInterestCalcJob.DisclosureGroupAccess {

        /** What {@code OPEN INPUT} reports; {@code app/cbl/CBACT04C.cbl:273} tests it. */
        private String openStatus = FileStatus.OK;

        /** What {@code CLOSE} reports; {@code :562} tests it. */
        private String closeStatus = FileStatus.OK;

        /** The reads to hand back, in order; the last is repeated once exhausted. */
        private final List<AccountInterestCalcJob.DisclosureGroupRead> scripted = new ArrayList<>();

        /** Every key asked for, in order, so the {@code 'DEFAULT   '} retry can be proven. */
        private final List<String> keysRead = new ArrayList<>();

        /** How many times the file was closed, so double-closing can be proven harmless. */
        private int closes;

        @Override
        public String datasetName() {
            return DISCGRP_DS;
        }

        @Override
        public AccountInterestCalcJob.DisclosureGroupFile open() {
            return new ScriptedDisclosureGroupFile(this);
        }

        /**
         * Adds one scripted outcome.
         *
         * @param read the outcome to return from the next unscripted read onwards
         * @return this, for chaining
         */
        ScriptedDisclosureGroupAccess yielding(AccountInterestCalcJob.DisclosureGroupRead read) {
            scripted.add(read);
            return this;
        }

        /**
         * Answers one read, repeating the last scripted outcome once the script is exhausted.
         *
         * @param keyImage the 16-character {@code DIS-GROUP-KEY}
         * @return the scripted outcome
         */
        private AccountInterestCalcJob.DisclosureGroupRead answer(String keyImage) {
            keysRead.add(keyImage);
            if (scripted.isEmpty()) {
                return AccountInterestCalcJob.DisclosureGroupRead.notFound();
            }
            int index = Math.min(keysRead.size() - 1, scripted.size() - 1);
            return scripted.get(index);
        }
    }

    /** One open handle over a {@link ScriptedDisclosureGroupAccess}. */
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
            return access.closeStatus;
        }

        @Override
        public void close() {
            access.closes++;
        }
    }

    /**
     * Every collaborator of one run, as a double, with each handle opening and closing cleanly until a
     * test says otherwise and the browse at end of file from its first read - the shortest complete run
     * the program has.
     */
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
            // The constructor asks the account repository for the charset, so this one is not optional.
            when(accountRepository.datasetCharset()).thenReturn(ASCII);

            when(tcatbalRepository.open(TranCatBalRepository.OpenMode.INPUT)).thenReturn(tcatbalFile);
            when(accountRepository.open(AccountRepository.OpenMode.I_O)).thenReturn(accountFile);
            when(xrefRepository.openBrowse()).thenReturn(xrefCursor);
            when(transactionRepository.openOutput()).thenReturn(transactionFile);

            when(tcatbalFile.openStatus()).thenReturn(FileStatus.OK);
            when(tcatbalFile.closeFile()).thenReturn(FileStatus.OK);
            when(accountFile.openStatus()).thenReturn(FileStatus.OK);
            when(accountFile.closeFile()).thenReturn(FileStatus.OK);
            when(xrefCursor.openStatus()).thenReturn(FileStatus.OK);
            when(xrefCursor.closeBrowse()).thenReturn(FileStatus.OK);
            when(transactionFile.openStatus()).thenReturn(FileStatus.OK);
            when(transactionFile.closeOutput()).thenReturn(FileStatus.OK);

            when(tcatbalFile.readNext()).thenReturn(TranCatBalRepository.ReadResult.endOfFile());
        }

        /**
         * Scripts the browse to yield one record for account 11 category {@code '01'}/1, then end of
         * file, and makes the account and cross-reference reads succeed.
         *
         * @return this, for chaining
         */
        Doubles withOneRecord() {
            TranCatBalRecord record = TranCatBalRecord.decode(
                    tcatbalImage(11L, "01", 1, "1000.00"), ASCII);
            when(tcatbalFile.readNext()).thenReturn(TranCatBalRepository.ReadResult.found(record),
                    TranCatBalRepository.ReadResult.endOfFile());
            when(accountFile.readByKey(anyLong())).thenReturn(AccountRepository.ReadResult.found(
                    AccountRecord.decode(acctImage(11L, "500.00", "A000000000"), ASCII)));
            when(xrefRepository.readByAccountIdViaAltIndex(anyLong()))
                    .thenReturn(CardXrefRepository.ReadResult.found(
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
                    new PresentBean<>(sysout), new PresentBean<>(FIXED));
        }

        /**
         * Runs the whole program and requires that it abends.
         *
         * @return the abend, for its message and return code
         */
        AbendException runAndExpectAbend() {
            AccountInterestCalcJob subject = job();
            return assertThatExceptionOfType(AbendException.class)
                    .isThrownBy(() -> subject.calculateInterest(PARM, sysout))
                    .actual();
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
            // The opens are ordered, and the first one failing means the other four never happen.
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

            // DISPLAY 'ERROR OPENING CROSS REF FILE' XREFFILE-STATUS - two operands, one line, and
            // COBOL DISPLAY contributes no separator of its own.
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

            // The source defect: 0200-DISCGRP-OPEN displays the daily-rejects message. Repairing it
            // would change an observable line and fail parity, so practice B5 keeps it.
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
            // The account master is opened after DISCGRP, so it is never reached.
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
            // I-O and not INPUT: the account master is rewritten on every break.
            verify(doubles.accountRepository).open(AccountRepository.OpenMode.I_O);
            verify(doubles.transactionRepository, never()).openOutput();
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
            // All five opens were attempted, in the source's order, before the last one refused.
            verify(doubles.tcatbalRepository).open(TranCatBalRepository.OpenMode.INPUT);
            verify(doubles.xrefRepository).openBrowse();
            verify(doubles.accountRepository).open(AccountRepository.OpenMode.I_O);
            verify(doubles.transactionRepository).openOutput();
            // An abend leaves the CLOSE paragraphs unperformed, so none of them displayed.
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

            // release() is not a CLOSE paragraph: it displays nothing and reports no status. The four
            // handles that opened are released; the CLOSE paragraphs were never performed.
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

            // The TCATBALF handle itself exists and is released; the other four were never obtained,
            // which is the null arm of every guard in release().
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
            // Not a file status: the access path itself could not produce a handle. The run holds no
            // handle for any of the five files, which is the null arm of every guard in release().
            when(doubles.tcatbalRepository.open(TranCatBalRepository.OpenMode.INPUT))
                    .thenThrow(new IllegalStateException("the access path could not be reached"));
            AccountInterestCalcJob subject = doubles.job();

            assertThatIllegalStateException()
                    .isThrownBy(() -> subject.calculateInterest(PARM, doubles.sysout))
                    .withMessageContaining("could not be reached");

            // The banner was displayed and then nothing else: no message, no status, no abend line,
            // because this is not a condition CBACT04C has a FILE STATUS for.
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

            // The closes run after the loop, so the browse completed: the banner, then the failure.
            assertThat(doubles.sysout.lines()).containsExactly(
                    AccountInterestCalcJob.START_OF_EXECUTION,
                    AccountInterestCalcJob.ERROR_CLOSING_TCATBALF,
                    FileStatus.toDisplayLine(TranCatBalRepository.PERMANENT_ERROR_STATUS),
                    AbendException.ABEND_DISPLAY_TEXT);
            // The end banner is displayed after the fifth close, so a failing first close replaces it.
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

            // Unlike its OPEN at :263, the CLOSE at :552 displays one operand only.
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

            // 9200-DISCGRP-CLOSE names DISCLOSURE GROUP correctly while 0200-DISCGRP-OPEN does not.
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
            // Four closes succeeded; the fifth refused, so the end banner is not reached.
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
            // release() runs afterwards regardless, and closing twice is harmless by design.
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
            // One read, and it reported AT END: the flag is only ever set by a read.
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

            // :417 only displays its two lines for the INVALID KEY condition, so a permanent error
            // displays neither of them - just the paragraph's own message.
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
            // The interest was computed and accumulated before the write was attempted.
            verify(doubles.transactionFile).writeSequential(Mockito.any(TranRecord.class));
        }

        @Test
        @DisplayName(":389 reports ERROR READING ACCOUNT FILE after its own not-found line")
        void accountReadNotFoundEmitsBothLines() {
            Doubles doubles = new Doubles().withOneRecord();
            when(doubles.accountFile.readByKey(anyLong()))
                    .thenReturn(AccountRepository.ReadResult.notFound());

            doubles.runAndExpectAbend();

            // INVALID KEY displays 'ACCOUNT NOT FOUND: ' with the key, and then the guard at :381
            // accepts '00' only - so '23' displays the error line too and abends. Both lines, in order.
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
            // The ALTERNATE key path, not the base cluster: one dataset reached two ways.
            verify(doubles.xrefRepository).readByAccountIdViaAltIndex(11L);
            verify(doubles.xrefRepository, never()).readByCardNumber(Mockito.anyString());
        }

        @Test
        @DisplayName("a '23' on the DEFAULT retry is fatal, because :444 has no INVALID KEY phrase")
        void theDefaultRetryHasNoInvalidKeyPhrase() {
            Doubles doubles = new Doubles().withOneRecord();
            doubles.discgrp.scripted.clear();
            // Both the keyed read and the DEFAULT retry miss.
            doubles.discgrp.yielding(AccountInterestCalcJob.DisclosureGroupRead.notFound());

            doubles.runAndExpectAbend();

            assertThat(doubles.sysout.lines()).endsWith(
                    AccountInterestCalcJob.DISCLOSURE_GROUP_RECORD_MISSING,
                    AccountInterestCalcJob.TRY_WITH_DEFAULT_GROUP_CODE,
                    AccountInterestCalcJob.ERROR_READING_DEFAULT_DISCGRP,
                    FileStatus.toDisplayLine(FileStatus.NOT_FOUND),
                    AbendException.ABEND_DISPLAY_TEXT);
            // Two reads: the group's own key, then 'DEFAULT' padded to X(10) with the type and
            // category components left exactly as :211-:212 set them.
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
                    .thenReturn(CardXrefRepository.ReadResult.found(
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

            // The second record is a different account, so the break fires and the rewrite is attempted.
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

        /**
         * Builds bindings whose {@code DISCGRP} entry is exactly the one supplied.
         *
         * @param discgrp the entry to install
         * @return the bindings
         */
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
                            AccountInterestCalcJob.carddemoDisclosureGroupAccess(template,
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
                            AccountInterestCalcJob.carddemoDisclosureGroupAccess(template, all, ASCII,
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
                            AccountInterestCalcJob.carddemoDisclosureGroupAccess(template, all, ASCII,
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
                            AccountInterestCalcJob.carddemoDisclosureGroupAccess(template, all, ASCII,
                                    RecordImageForm.CHARACTER))
                    .withMessageContaining("dsname");
        }

        @Test
        @DisplayName("the access path reports the configured dataset name and nothing invented")
        void theDatasetNameComesFromConfiguration() {
            AccountInterestCalcJob.DisclosureGroupAccess access =
                    AccountInterestCalcJob.carddemoDisclosureGroupAccess(database(), bindings(), ASCII,
                            RecordImageForm.CHARACTER);

            assertThat(access.datasetName()).isEqualTo(DISCGRP_DS)
                    .doesNotContain("AWS.M2.CARDDEMO");
        }

        @Test
        @DisplayName("an open that never reached the dataset reports the same failure from every "
                + "operation")
        void anOpenThatFailed() {
            // A template with no DISCGRP relation at all: the open cannot resolve a statement.
            DriverManagerDataSource source = new DriverManagerDataSource("jdbc:h2:mem:intcalcnodiscgrp"
                    + SEQ.incrementAndGet() + ";DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE", "sa", "");
            source.setDriverClassName("org.h2.Driver");
            AccountInterestCalcJob.DisclosureGroupAccess access =
                    AccountInterestCalcJob.carddemoDisclosureGroupAccess(new JdbcTemplate(source),
                            bindings(), ASCII, RecordImageForm.CHARACTER);

            try (AccountInterestCalcJob.DisclosureGroupFile file = access.open()) {
                assertThat(file.openStatus()).isNotEqualTo(FileStatus.OK);

                AccountInterestCalcJob.DisclosureGroupRead read = file.readByKey("A00000000001" + "0001");

                // Not an empty dataset and not a not-found: the same permanent failure the open saw, so
                // a caller that ignored the open status cannot mistake one for the other.
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
                    AccountInterestCalcJob.carddemoDisclosureGroupAccess(database(), bindings(), ASCII,
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
                    AccountInterestCalcJob.carddemoDisclosureGroupAccess(database(), bindings(), ASCII,
                            RecordImageForm.CHARACTER);

            try (AccountInterestCalcJob.DisclosureGroupFile file = access.open()) {
                assertThatNullPointerException().isThrownBy(() -> file.readByKey(null));
                // Seventeen is CVTRA01Y's key, and a short key would become a prefix match.
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
                    AccountInterestCalcJob.carddemoDisclosureGroupAccess(template, bindings(), ASCII,
                            RecordImageForm.CHARACTER);

            try (AccountInterestCalcJob.DisclosureGroupFile file = access.open()) {
                assertThat(file.readByKey("A00000000001" + "0001").status()).isEqualTo(FileStatus.OK);

                // The relation disappears under an open handle, which is what a permanent I/O error
                // looks like from here.
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
                    AccountInterestCalcJob.carddemoDisclosureGroupAccess(template, bindings(), ASCII,
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

        /**
         * A step execution carrying the given {@code parmDate}, or none at all.
         *
         * @param parmDate the value to supply, or {@code null} to supply no parameter
         * @return the execution
         */
        private StepExecution stepExecution(String parmDate) {
            JobParametersBuilder parameters = new JobParametersBuilder();
            if (parmDate != null) {
                parameters.addString(BatchConfig.PARM_DATE_PARAMETER, parmDate);
            }
            return new StepExecution(AccountInterestCalcJob.STEP_NAME,
                    new JobExecution(1L, parameters.toJobParameters()));
        }

        @Test
        @DisplayName("a launcher-supplied PARM is moved into PIC X(10): padded right, truncated right")
        void aSuppliedParmDate() {
            AccountInterestCalcJob.ChunkDelegate delegate =
                    new Doubles().job().newChunkDelegate();

            delegate.beforeStep(stepExecution("2023010100"));
            assertThat(delegate.parmDate()).isEqualTo("2023010100");

            delegate.beforeStep(stepExecution("2023"));
            assertThat(delegate.parmDate()).isEqualTo("2023      ")
                    .hasSize(AccountInterestCalcJob.PARM_DATE_WIDTH);

            delegate.beforeStep(stepExecution("20230101001234"));
            assertThat(delegate.parmDate()).isEqualTo("2023010100")
                    .hasSize(AccountInterestCalcJob.PARM_DATE_WIDTH);
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

            // The mainframe leaves those paragraphs unperformed when the loop did not end at AT END.
            assertThat(doubles.sysout.lines())
                    .doesNotContain(AccountInterestCalcJob.END_OF_EXECUTION);
            verify(doubles.tcatbalFile, never()).closeFile();
            // The handles are still released, silently, so nothing is left open.
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
                // The first record of a run is never an account break's rewrite: WS-FIRST-TIME is 'Y'.
                assertThat(first.accountRewritten()).isFalse();
                assertThat(delegate.transactionsWritten()).isEqualTo(1L);
                assertThat(delegate.accountsRewritten()).isZero();

                // Reading again reports AT END, which the ItemReader contract renders as null.
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
                // The shape a record takes when the account broke but the rate was zero: an account was
                // rewritten, no interest was computed and therefore no transaction was written. Built
                // directly, because a single chunk cannot contain both this shape and the other one.
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
            // Closing twice is harmless, which is the null arm of close()'s own guard.
            delegate.close();
            verify(doubles.tcatbalFile, times(1)).closeFile();
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
                    doubles.discgrp, new PresentBean<>(doubles.sysout),
                    new PresentBean<>(Clock.fixed(Instant.parse("2022-07-18T12:34:56.780Z"),
                            ZoneOffset.ofHoursMinutes(-5, -30))));

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
                    east.discgrp, new PresentBean<>(east.sysout),
                    new PresentBean<>(Clock.fixed(Instant.parse("2022-07-18T12:34:56.780Z"),
                            ZoneOffset.ofHoursMinutes(5, 45))));

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
         *
         * <p>This exists <strong>only</strong> to prove the arm is present in the translated control
         * flow. In the real program {@code END-OF-FILE} is initialised to {@code 'N'} and the only value
         * ever moved into it is {@code 'Y'}, so a mainframe run can never take this arm, and the final
         * account group's accumulated interest is therefore never rewritten. That is a defect of
         * {@code CBACT04C} and it is reproduced deliberately: adding an end-of-file flush would alter
         * the account master on every run and fail every parity case for this program. Do not "fix" it.
         */
        private final class PoisonedRunJob extends AccountInterestCalcJob {

            /** The run this job last allocated, so a test can reach its {@code WORKING-STORAGE}. */
            private InterestCalculationRun created;

            private PoisonedRunJob(Doubles doubles) {
                super(scaffolding(contracts(), bindings()), doubles.tcatbalRepository,
                        doubles.accountRepository, doubles.xrefRepository,
                        doubles.transactionRepository, doubles.discgrp,
                        new PresentBean<>(doubles.sysout), new PresentBean<>(FIXED));
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
            // The arm rewrites the uninitialised ACCOUNT-RECORD, whose key is all zeros because no read
            // ever happened, so no such account exists and the rewrite reports '23'.
            //
            // The refusal is also what makes this test terminate. PERFORM UNTIL END-OF-FILE = 'Y' tests
            // only for 'Y', and the ELSE arm moves nothing into the flag, so a rewrite that SUCCEEDED
            // would loop forever - on the mainframe exactly as here. That is a second reason the arm is
            // unreachable by design rather than merely unused, and a second reason not to "fix" it into
            // an end-of-file flush.
            when(doubles.accountFile.rewrite(Mockito.any(AccountRecord.class)))
                    .thenReturn(AccountRepository.WriteResult.notFound());
            AccountInterestCalcJob subject = new PoisonedRunJob(doubles);

            // 'X' is neither 'Y' nor 'N', so the loop runs and its guard fails: the ELSE arm.
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
            // No record was ever read: the arm is reached instead of the read, not after it.
            verify(doubles.tcatbalFile, never()).readNext();
        }

        @Test
        @DisplayName("the arm completing normally would loop forever, which is why nothing may flush "
                + "at end of file")
        void theArmCompletingNormallyWouldNeverTerminate() {
            Doubles doubles = new Doubles();
            PoisonedRunJob subject = new PoisonedRunJob(doubles);
            // The arm changes no flag, so a rewrite that SUCCEEDS returns straight to the loop test,
            // which still sees a value that is not 'Y' - an endless loop, on the mainframe exactly as
            // here. The only way to observe the arm completing is to let the rewrite itself end the
            // loop, which is a device of this test and not of the program: nothing in CBACT04C moves
            // anything into END-OF-FILE except 1000-TCATBALF-GET-NEXT at :340.
            when(doubles.accountFile.rewrite(Mockito.any(AccountRecord.class))).thenAnswer(invocation -> {
                subject.created.workingStorage()
                        .moveEndOfFile(AccountInterestCalcJob.END_OF_FILE_YES);
                return AccountRepository.WriteResult.written();
            });

            long records = subject.calculateInterest(PARM, doubles.sysout);

            // One trip through the arm, then the loop test saw 'Y' and the five closes ran.
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
            // One account, one category, interest computed and a transaction written - and then end of
            // file, which reaches the loop guard and not the ELSE. So no REWRITE happens at all, and
            // ACCT-CURR-BAL keeps the value it was read with. This is the defect, asserted as behaviour.
            verify(doubles.transactionFile).writeSequential(Mockito.any(TranRecord.class));
            verify(doubles.accountFile, never()).rewrite(Mockito.any(AccountRecord.class));
            assertThat(doubles.sysout.lines())
                    .contains(AccountInterestCalcJob.END_OF_EXECUTION)
                    .doesNotContain(AccountInterestCalcJob.ERROR_REWRITING_ACCTFILE);
        }
    }
}

