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
import com.vsergeychik.carddemo.config.DatasetUnitOfWork;
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

    /**
     * The physical-record ordinal a physical-sequential read is ordered by, as
     * {@code application-test.yml} configures it: H2's own row-identifier pseudo-column, which increases
     * with each insert and so returns records in the order they were written.
     */
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

    /**
     * The job-scoped override {@code application.yml} declares for this job: in {@code INTCALC.jcl} the DD
     * name {@code TRANSACT} is the generated-transaction <em>output</em>, not the transaction master
     * ({@code app/jcl/INTCALC.jcl:37-41}), so it aliases the {@code SYSTRAN} generation.
     *
     * <p>Modelled here because the job proves the alias is present at construction. Without it the step
     * would resolve {@code TRANSACT} to the global key - the transaction master - and write its generated
     * transactions over live data.
     */
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
        // app/jcl/INTCALC.jcl:29-32 declares //XREFFILE on the base cluster and //XREFFIL1 on the
        // alternate-index path over it - the batch DD names for the same two things the CSD calls CCXREF
        // and CXACAIX. The job resolves ITS OWN DDs and reads through them, so the catalogue a test wires
        // has to declare them exactly as the JCL does.
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
        // This step's own DD names for the cross-reference need no further entries: AccountInterestCalcJob
        // .XREFFILE_DD_NAME and .XREFFIL1_DD_NAME ARE CardXrefRepository.BATCH_DD_NAME and
        // .ALTERNATE_INDEX_BATCH_DD_NAME - the same two configuration keys put above, which is the whole
        // point of the job naming the JCL's DDs rather than the CSD's. Restating them here would put the
        // same two keys into this map a second time, and the second put would win: the alternate-index
        // entry would then say its base is CCXREF, the CSD name, contradicting the JCL and the job's own
        // construction-time proof. They are separate configuration keys from the repository's CCXREF and
        // CXACAIX, and the job proves at construction that each names the same dataset as its repository
        // counterpart.
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

    /**
     * @param step       the step contract to declare
     * @param parameters the declared job parameters
     * @param datasets   this job's job-scoped dataset overrides
     * @return the contract catalogue
     */
    private static JobContracts contracts(StepContract step, List<JobParameterContract> parameters,
            Map<String, JobDatasetBinding> datasets) {
        return contracts(List.of(step), parameters, datasets);
    }

    /**
     * @param steps      the step sequence to declare, in order
     * @param parameters the declared job parameters
     * @param datasets   this job's job-scoped dataset overrides
     * @return the contract catalogue
     */
    private static JobContracts contracts(List<StepContract> steps,
            List<JobParameterContract> parameters, Map<String, JobDatasetBinding> datasets) {
        JobContracts c = new JobContracts();
        c.put(AccountInterestCalcJob.JOB_KEY, new JobContract(AccountInterestCalcJob.PROGRAM_ID,
                parameters, steps, null, datasets));
        return c;
    }

    /**
     * The job-scoped dataset overrides {@code application.yml} declares for this job.
     *
     * <p>One entry, and it is not optional. {@code app/jcl/INTCALC.jcl:37-41} declares the
     * generated-transaction output under the DD name {@code TRANSACT} - the same eight characters as the
     * CICS transaction master, addressing a completely different dataset - so the job resolves
     * {@code TRANSACT} through this map and lands on {@code SYSTRAN}. Omitting it makes the job write its
     * interest transactions into the transaction master, which is the defect the DD-mapping finding
     * described: a declared DD that drives nothing, and an alias that nothing consumes.
     *
     * @return the override map, mirroring {@code carddemo.jobs.account-interest-calc-job.datasets}
     */
    private static Map<String, JobDatasetBinding> jobScopedDatasets() {
        return Map.of(AccountInterestCalcJob.TRANSACT_DD_NAME,
                new JobDatasetBinding(TransactionRepository.SEQUENTIAL_OUTPUT_DD_NAME, null, null,
                        false, null, null, null, null, null, null, null, null));
    }

    private static BatchConfig scaffolding(JobContracts contracts, DatasetBindings bindings) {
        return new BatchConfig(new PresentBean<>(Mockito.mock(JobRepository.class)),
                new PresentBean<>(Mockito.mock(PlatformTransactionManager.class)), contracts, bindings);
    }

    /**
     * A real unit of work over the same H2 data source the repositories read.
     *
     * <p>Real, not mocked, and for the same reason the update services' tests use a real one:
     * {@code DatasetUnitOfWork.active()} reports what {@code TransactionSynchronizationManager} actually
     * sees, so only a real manager makes {@code persistVerb} open a boundary that
     * {@code AccountRepository.rewrite} can detect and take its locking read inside. A mock would leave
     * {@code active()} false and the {@code REWRITE} would silently take the non-locking path, which is
     * the very thing DBP-01 and DBP-13 are about.
     *
     * @param t the template whose data source the boundary must span
     * @return a unit of work over that data source; never {@code null}
     */
    private static DatasetUnitOfWork unitOfWork(JdbcTemplate t) {
        return new DatasetUnitOfWork(new JdbcTransactionManager(
                Objects.requireNonNull(t.getDataSource(), "the template must carry a data source")));
    }

    /**
     * A unit of work for the constructions whose repositories are mocks.
     *
     * <p>There is no data source to span, so the manager is a mock: {@code TransactionTemplate} still
     * invokes the body and returns its value, which is all a mocked repository needs. It is deliberately
     * not used where a real {@code REWRITE} is asserted.
     *
     * @return a unit of work over a mocked manager; never {@code null}
     */
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
                AccountInterestCalcJob.carddemoDisclosureGroupAccess(t, b, ASCII,
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

    /**
     * {@code COMPUTE WS-MONTHLY-INT = ( TRAN-CAT-BAL * DIS-INT-RATE) / 1200} at
     * {@code app/cbl/CBACT04C.cbl:464-465}, driven as a matrix rather than as one worked example.
     *
     * <p><strong>Why a matrix, and why these rows.</strong> {@code ROUNDED} appears zero times in all
     * 28 programs - verified with {@code grep -c ROUNDED app/cbl/*} - so the store into
     * {@code WS-MONTHLY-INT PIC S9(09)V99} ({@code :168}) <em>truncates</em>. A single example cannot
     * distinguish truncation from rounding unless the exact quotient's third decimal digit happens to
     * be five or more, so six of the eleven rows below are chosen precisely because they diverge, and
     * each carries the half-up answer in its own column. If anyone ever swaps
     * {@link RoundingMode#DOWN} for a rounding mode, those six rows fail by name.
     *
     * <p><strong>Why the divergent answer is a literal and not a computation.</strong> Gate G24 admits
     * no occurrence of {@code HALF_UP}, {@code HALF_EVEN}, {@code CEILING} or {@code FLOOR} anywhere,
     * and {@link TheNumericParityGates#neitherTheJobNorTheDecimalPolicyNamesARoundingMode()} greps for
     * exactly that. So the rounded alternative is stated as data - recomputed by hand from the exact
     * quotient - rather than produced by naming the forbidden mode here.
     *
     * <p><strong>Negative rows.</strong> {@code TRAN-CAT-BAL} is {@code PIC S9(09)V99} and
     * {@code DIS-INT-RATE} is {@code PIC S9(04)V99}: both are signed, so a credit balance is
     * representable and reachable. COBOL truncation discards excess digits, which is truncation
     * <em>toward zero</em>, and that is {@code DOWN} - not {@code FLOOR}, which would take
     * {@code -1.249875} to {@code -1.25} and away from zero.
     *
     * <p>Every expected value below was derived from the copybook scales and recomputed by hand; no
     * COBOL execution baseline exists for this program (AAP 0.7.6, risk R-A), so provenance is the
     * arithmetic itself, shown in each row's comment.
     *
     * @param balance            {@code TRAN-CAT-BAL}, scale 2
     * @param rate               {@code DIS-INT-RATE}, scale 2, a percentage
     * @param expectedDown       the value COBOL stores, truncated to scale 2
     * @param roundedAlternative the value a half-up implementation would store instead; equal to
     *                           {@code expectedDown} on the rows where the two modes agree
     */
    @ParameterizedTest(name = "{0} at {1}% -> {2} (a rounding implementation would say {3})")
    @CsvSource({
        // 1499.8500 / 1200 = 1.249875           - diverges
        "     99.99, 15.00,   1.24,   1.25",
        // 1500.0000 / 1200 = 1.25 exactly       - agrees
        "    100.00, 15.00,   1.25,   1.25",
        //  190.0000 / 1200 = 0.158333...        - diverges
        "   1000.00,  0.19,   0.15,   0.16",
        // 12500.0000 / 1200 = 10.416666...      - diverges
        "   1000.00, 12.50,  10.41,  10.42",
        // 24678.8544 / 1200 = 20.565712         - diverges
        "   1234.56, 19.99,  20.56,  20.57",
        // 18518.4000 / 1200 = 15.432            - agrees; discgrp.txt's own 15.00 rate
        "   1234.56, 15.00,  15.43,  15.43",
        // zero balance is the whole shipped tcatbal.txt fixture: 0.00 at any rate
        "      0.00, 15.00,   0.00,   0.00",
        // 0.1500 / 1200 = 0.000125              - agrees, both floor to zero
        "      0.01, 15.00,   0.00,   0.00",
        // signed: -1.249875, truncated TOWARD ZERO - diverges, and FLOOR would also be wrong
        "    -99.99, 15.00,  -1.24,  -1.25",
        // signed: -0.158333...                  - diverges
        "  -1000.00,  0.19,  -0.15,  -0.16",
        // signed: -10.416666...                 - diverges
        "  -1000.00, 12.50, -10.41, -10.42",
    })
    void theInterestFormulaTruncatesAtEveryRow(String balance, String rate, String expectedDown,
            String roundedAlternative) {

        BigDecimal computed = AccountInterestCalcJob.computeMonthlyInterest(
                new BigDecimal(balance.trim()), new BigDecimal(rate.trim()));

        // G23: the receiver is PIC S9(09)V99, so the stored value carries scale exactly 2 - not "a
        // value that happens to be numerically equal at some other scale". isEqualTo on BigDecimal
        // compares scale as well as value, which is why it is used here in preference to compareTo.
        assertThat(computed).isEqualTo(new BigDecimal(expectedDown.trim()));
        assertThat(computed.scale()).isEqualTo(2);

        // G24: on every divergent row the truncated answer must NOT be the rounded one. This is the
        // assertion a HALF_UP or HALF_EVEN regression trips, and it is stated per row so the failure
        // names the operands.
        BigDecimal rounded = new BigDecimal(roundedAlternative.trim());
        if (rounded.compareTo(new BigDecimal(expectedDown.trim())) != 0) {
            assertThat(computed).isNotEqualByComparingTo(rounded);
        }
    }

    /**
     * The same formula reached through the job's own arithmetic seam, for the two rates that actually
     * occur in {@code app/data/ASCII/discgrp.txt}.
     *
     * <p>Slicing all 51 fixture rows gives exactly three group ids - {@code A000000000},
     * {@code 'DEFAULT   '} and {@code 'ZEROAPR   '} - of seventeen rows each, and the
     * {@code 01}/{@code 0001} row of the first two carries {@code 00150}{@code &#123;}, which is
     * {@code 15.00} once the trailing zoned overpunch is read as a positive sign. {@code ZEROAPR}
     * carries {@code 00000}{@code &#123;}, which is {@code 0.00} and is the rate that closes the
     * {@code IF DIS-INT-RATE NOT = 0} guard at {@code :214}.
     */
    @Test
    void monthlyInterestUsesTheFixtureRates() {
        assertThat(AccountInterestCalcJob.computeMonthlyInterest(
                new BigDecimal("1234.56"), new BigDecimal("15.00")))
                .isEqualTo(new BigDecimal("15.43"));
        // ZEROAPR: any balance at 0.00 yields 0.00, which is why the guard matters.
        assertThat(AccountInterestCalcJob.computeMonthlyInterest(
                new BigDecimal("999999.99"), new BigDecimal("0.00")))
                .isEqualTo(new BigDecimal("0.00"));
        // The whole shipped tcatbal.txt: every one of its 50 rows is 0.00, so at the fixture rate of
        // 15.00 the fixtures generate no interest at all. That is exactly why the matrix above
        // synthesises its own balances (agent brief, phase 4).
        assertThat(AccountInterestCalcJob.computeMonthlyInterest(
                new BigDecimal("0.00"), new BigDecimal("15.00")))
                .isEqualTo(new BigDecimal("0.00"));
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

    /**
     * {@code IF DIS-INT-RATE NOT = 0} at {@code app/cbl/CBACT04C.cbl:214} closes over
     * <strong>both</strong> {@code :215} and {@code :216}, so a zero rate reaches neither
     * {@code 1300-COMPUTE-INTEREST} nor {@code 1400-COMPUTE-FEES}.
     *
     * <p><strong>This corrects the plan.</strong> AAP 0.8.3 states that
     * {@code 1400-COMPUTE-FEES} "is invoked unconditionally alongside the interest calculation
     * [L216]". That is wrong, and it was re-checked against the source rather than taken on trust:
     * {@code sed -n '213,218p'} shows {@code IF DIS-INT-RATE NOT = 0} on {@code :214},
     * {@code PERFORM 1300-COMPUTE-INTEREST} on {@code :215}, {@code PERFORM 1400-COMPUTE-FEES} on
     * {@code :216} and the closing {@code END-IF} on {@code :217}. The fee stub is invoked
     * unconditionally <em>with respect to the interest computation</em> - never one without the
     * other - but the pair together is guarded. The behaviour is asserted from the source and the
     * divergence is recorded here rather than silently conformed to (practice B4).
     *
     * <p>The rate used is {@code 0.00} at scale 2 and not {@code BigDecimal.ZERO}, deliberately. The
     * COBOL comparison is a numeric-value comparison, so the Java guard has to be
     * {@link BigDecimal#compareTo(BigDecimal)} and cannot be {@code equals}: the two are
     * {@code compareTo}-equal and <em>not</em> {@code equals}-equal, which the second block below
     * states outright. A guard written with {@code equals} would pass a test that used
     * {@code BigDecimal.ZERO} and then compute interest on every zero-rate group in production,
     * because {@code DIS-INT-RATE} always arrives from the codec at scale 2.
     */
    @Test
    void feesIsNotCalledWhenTheRateIsZero() {
        JdbcTemplate t = database();
        seed(t, TCATBAL_DS, tcatbalImage(11L, "01", 1, "1000.00"));
        seed(t, ACCT_DS, acctImage(11L, "500.00", "A000000000"));
        seed(t, XREF_DS, xrefImage("4444333322221111", 1, 11L));
        seed(t, XREF_AIX_DS, xrefImage("4444333322221111", 1, 11L));
        // 'ZEROAPR' in the real fixture carries 00000{ here; a scale-2 zero is what the codec yields.
        seed(t, DISCGRP_DS, discgrpImage("A000000000", "01", 1, "0.00"));
        DatasetBindings b = bindings();
        CapturedSysout sysout = new CapturedSysout();
        AccountInterestCalcJob spy = Mockito.spy(job(t, b, sysout));

        spy.calculateInterest(PARM, sysout);

        // Neither arm of the guard ran: no fee call, and no generated transaction either.
        Mockito.verify(spy, Mockito.never()).computeFees();
        assertThat(rows(t, SYSTRAN_DS)).isEmpty();
        // The record was still counted and still displayed - :192-193 sit OUTSIDE the guard.
        assertThat(sysout.lines()).hasSize(3);

        // Why the guard must compare by value: the rate the codec produced above is scale-2 zero, and
        // that is equals-different from BigDecimal.ZERO while being compareTo-identical to it.
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

        // -------------------------------------------------------------------------------------------
        // The same record again, read as BYTES at the absolute offsets app/cpy/CVTRA05Y.cpy declares.
        //
        // The accessors above are the record type's view of itself; these are the dataset's. They are
        // deliberately both present, because a field pair swapped consistently in the encoder and the
        // decoder round-trips perfectly through the accessors and still writes an unreadable dataset.
        // The offsets are stated as literals as well as constants, so a constant that drifted from the
        // copybook fails here rather than agreeing with itself.
        // -------------------------------------------------------------------------------------------
        assertThat(TranRecord.TRAN_ID_OFFSET).isZero();
        assertThat(image.substring(0, 16)).isEqualTo("2022071800000001");
        assertThat(image.substring(TranRecord.TRAN_ID_OFFSET,
                TranRecord.TRAN_ID_OFFSET + TranRecord.TRAN_ID_LENGTH))
                .isEqualTo("2022071800000001");
        assertThat(image.substring(16, 18)).isEqualTo("01");
        // PIC 9(04) receiving the two-character literal '05': a numeric receiver is filled from the
        // RIGHT, so the value is 0005 and not '05  '.
        assertThat(image.substring(18, 22)).isEqualTo("0005");
        // PIC X(10) receiving 'System': an alphanumeric receiver is filled from the LEFT.
        assertThat(image.substring(22, 32)).isEqualTo("System    ");
        assertThat(image.substring(32, 132))
                .isEqualTo("Int. for a/c 00000000011" + " ".repeat(76));
        // TRAN-AMT PIC S9(09)V99 = 11 zoned bytes, sign overpunched into the LAST one. 10.41 is the
        // eleven digits 00000001041 with the trailing 1 carrying the positive zone, which IBM037
        // renders as 'A' - so the span is 0000000104 followed by A, never "10.41" and never a
        // separate sign byte.
        assertThat(image.substring(132, 143)).isEqualTo("0000000104A").hasSize(11);
        assertThat(TranRecord.TRAN_AMT_OFFSET).isEqualTo(132);
        assertThat(TranRecord.TRAN_AMT_LENGTH).isEqualTo(11);
        // PIC 9(09) receiving 0: zero-filled to width, no sign byte - the field is unsigned.
        assertThat(image.substring(143, 152)).isEqualTo("000000000");
        assertThat(image.substring(152, 202)).isEqualTo(" ".repeat(50));
        assertThat(image.substring(202, 252)).isEqualTo(" ".repeat(50));
        assertThat(image.substring(252, 262)).isEqualTo(" ".repeat(10));
        // From the CROSS-REFERENCE record read at :205, not from the account record read at :203.
        assertThat(image.substring(262, 278)).isEqualTo("4444333322221111");
        // Both timestamps are the SAME 26 bytes: Z-GET-DB2-FORMAT-TIMESTAMP is performed once at :496
        // and its result moved twice, at :497 and :498.
        assertThat(image.substring(278, 304)).isEqualTo("2022-07-18-12.34.56.780000").hasSize(26);
        assertThat(image.substring(304, 330)).isEqualTo("2022-07-18-12.34.56.780000").hasSize(26);
        // FILLER X(20) - never written by the program, and therefore spaces. G21: if the codec omitted
        // it the record would be 330 bytes and the hasSize(350) above would already have failed, so
        // this states the CONTENT the width alone cannot.
        assertThat(image.substring(330, 350)).isEqualTo(" ".repeat(20));
        assertThat(TranRecord.FILLER_OFFSET + TranRecord.FILLER_LENGTH)
                .isEqualTo(TranRecord.RECORD_LENGTH);
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

    /**
     * {@code MOVE WS-MONTHLY-INT TO TRAN-AMT} at {@code app/cbl/CBACT04C.cbl:490} moves the
     * <strong>per-record</strong> interest, not the running total.
     *
     * <p>The two are easy to conflate because {@code :467} adds the same value into
     * {@code WS-TOTAL-INT} one statement earlier, and a translation that wrote the accumulator into
     * the transaction would still produce a plausible-looking first record and the correct account
     * balance. So the categories here carry <em>different</em> balances and rates - {@code 1000.00} at
     * {@code 12.50} and {@code 99.99} at {@code 15.00} - which makes the per-record amounts
     * {@code 10.41} and {@code 1.24} and the accumulated total {@code 11.65}. Three distinct values,
     * so no substitution of one for another can pass.
     *
     * <p>The second row is also the divergent truncation case from
     * {@link #theInterestFormulaTruncatesAtEveryRow} carried end to end through a real run: the exact
     * quotient is {@code 1.249875}, so a rounding implementation would write {@code 1.25} here and
     * would post {@code 11.66} to the account.
     */
    @Test
    void theWrittenAmountIsThePerRecordInterestAndNotTheAccumulatedTotal() {
        JdbcTemplate t = database();
        // Seeded in ascending TRAN-CAT-KEY order, which is both the insertion order and the key order
        // a KSDS browse returns, so the assertion holds however the browse is ordered.
        seed(t, TCATBAL_DS, tcatbalImage(11L, "01", 1, "1000.00"));
        seed(t, TCATBAL_DS, tcatbalImage(11L, "01", 2, "99.99"));
        // A second account, purely to make account 11's break happen: the last group is never
        // rewritten (the unreachable ELSE at :219-221).
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
        // Each record carries its OWN interest, at scale 2, truncated: 10.41 then 1.24 then 0.00.
        assertThat(amounts).containsExactly(new BigDecimal("10.41"), new BigDecimal("1.24"),
                new BigDecimal("0.00"));
        assertThat(amounts).allSatisfy(amount -> assertThat(amount.scale()).isEqualTo(2));
        // And no record carries the accumulator, which is what a WS-TOTAL-INT mix-up would produce.
        assertThat(amounts).doesNotContain(new BigDecimal("11.65"));
        // The rounding-mode regression this row exists to catch would have written 1.25 instead.
        assertThat(amounts).doesNotContain(new BigDecimal("1.25"));

        // ADD WS-TOTAL-INT TO ACCT-CURR-BAL at :352 posts the accumulator, once, on the break.
        AccountRecord broken = rows(t, ACCT_DS).stream().map(image -> AccountRecord.decode(image, ASCII))
                .filter(account -> account.getAcctId() == 11L).findFirst().orElseThrow();
        assertThat(broken.getAcctCurrBal()).isEqualTo(new BigDecimal("511.65"));
    }

    /**
     * {@code :210-212} composes {@code DIS-GROUP-KEY} from the account's group id, then the category
     * code, then the type code - and each lands in the span {@code app/cpy/CVTRA02Y.cpy} declares for
     * it, not in source order.
     *
     * <pre>
     * MOVE ACCT-GROUP-ID   TO FD-DIS-ACCT-GROUP-ID     :210   offset  0, X(10)
     * MOVE TRANCAT-CD      TO FD-DIS-TRAN-CAT-CD       :211   offset 12, 9(04)
     * MOVE TRANCAT-TYPE-CD TO FD-DIS-TRAN-TYPE-CD      :212   offset 10, X(02)
     * </pre>
     *
     * <p>The two middle moves are written in the opposite order to the fields' physical order, which
     * is precisely why this needs asserting: a translation that followed the statement order and
     * packed the values consecutively would put the category where the type belongs. Both keys are 16
     * characters either way, so no width check can see the mistake - it surfaces only as a disclosure
     * group that is silently not found, which then falls through to {@code 'DEFAULT   '} and charges
     * the wrong rate.
     */
    @Test
    void theDisclosureKeyPlacesTheCategoryAndTypeInTheirDeclaredSpans() {
        // The copybook's own geometry, restated so the expectation below is traceable to it.
        assertThat(DisclosureGroupRecord.DIS_ACCT_GROUP_ID_OFFSET).isZero();
        assertThat(DisclosureGroupRecord.DIS_ACCT_GROUP_ID_LENGTH).isEqualTo(10);
        assertThat(DisclosureGroupRecord.DIS_TRAN_TYPE_CD_OFFSET).isEqualTo(10);
        assertThat(DisclosureGroupRecord.DIS_TRAN_TYPE_CD_LENGTH).isEqualTo(2);
        assertThat(DisclosureGroupRecord.DIS_TRAN_CAT_CD_OFFSET).isEqualTo(12);
        assertThat(DisclosureGroupRecord.DIS_TRAN_CAT_CD_LENGTH).isEqualTo(4);

        // The key the job actually asks the DISCGRP path for, captured from the seam.
        Doubles doubles = new Doubles().withOneRecord();
        doubles.job().calculateInterest(PARM, doubles.sysout);
        assertThat(doubles.discgrp.keysRead)
                .containsExactly("A000000000" + "01" + "0001");
        assertThat(doubles.discgrp.keysRead.get(0)).hasSize(16);

        // And end to end: a group row whose TYPE and CATEGORY values are transposed relative to the
        // balance record is a DIFFERENT key and is NOT found, so the run falls back to DEFAULT.
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
        // 1000.00 * 6.00 / 1200 = 5.00 - the DEFAULT rate, not the transposed row's 12.50.
        assertThat(TranRecord.decode(rows(transposed, SYSTRAN_DS).get(0), ASCII).tranAmt())
                .isEqualTo(new BigDecimal("5.00"));

        // The correctly keyed row IS found, which proves the miss above was the transposition and not
        // an unrelated failure to read the dataset at all.
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

    /**
     * {@code PARM-DATE} is {@code PIC X(10)} character data and is never parsed as a date.
     *
     * <p>{@code app/cbl/CBACT04C.cbl:176-181} declares
     * {@code 01 EXTERNAL-PARMS} as {@code PARM-LENGTH PIC S9(04) COMP} followed by
     * {@code PARM-DATE PIC X(10)}, and the only statement that ever reads {@code PARM-DATE} is the
     * {@code STRING} at {@code :476-480} that concatenates it, verbatim, into {@code TRAN-ID}. It is
     * never moved to a numeric field, never validated and never converted.
     *
     * <p>So a value that is not date-shaped at all has to flow through untouched. Introducing a parse
     * would be a behaviour change in both directions: it would reject a PARM the mainframe accepts,
     * and it would let a reformatting of the value change every identifier the run generates.
     */
    @Test
    void aNonDateShapedParmDateIsConcatenatedVerbatim() {
        JdbcTemplate t = singleAccountDatabase();
        CapturedSysout sysout = new CapturedSysout();

        job(t, bindings(), sysout).calculateInterest("XXXXXXXXXX", sysout);

        assertThat(TranRecord.decode(rows(t, SYSTRAN_DS).get(0), ASCII).tranId())
                .isEqualTo("XXXXXXXXXX000001")
                .hasSize(TranRecord.TRAN_ID_LENGTH);
    }

    /**
     * The declared {@code parmDate} is a {@code String} job parameter, and it is the only one.
     *
     * <p>{@code app/jcl/INTCALC.jcl:22} is {@code EXEC PGM=CBACT04C,PARM='2022071800'} - one PARM, and
     * character data. A non-date-shaped declaration is accepted at construction for the same reason
     * the run above accepts one: nothing in the program interprets the value.
     */
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

        // A declaration that is not date-shaped is equally acceptable, and arrives unaltered.
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

    /**
     * With the shipped fixtures <strong>every</strong> account takes the {@code 'DEFAULT   '}
     * disclosure group, because {@code app/data/ASCII/acctdata.txt} leaves {@code ACCT-GROUP-ID}
     * blank.
     *
     * <p>Slicing all 50 rows of that fixture at the two spans {@code app/cpy/CVACT01Y.cpy} declares
     * shows {@code A000000000} at offset 102 - which is {@code ACCT-ADDR-ZIP}, not the group - and
     * <strong>ten spaces</strong> at offset 112, which is {@code ACCT-GROUP-ID}. So
     * {@code MOVE ACCT-GROUP-ID TO FD-DIS-ACCT-GROUP-ID} at {@code app/cbl/CBACT04C.cbl:210} moves
     * spaces, the keyed {@code DISCGRP} read misses, {@code :436} sees {@code '23'} and retries with
     * {@code 'DEFAULT   '}.
     *
     * <p>The consequence is worth stating plainly, because it inverts what the data appears to say:
     * the seventeen {@code A000000000} rows of {@code app/data/ASCII/discgrp.txt} are <em>dead</em>
     * with the shipped account data. A test that asserted the {@code A000000000} rate would be
     * asserting a row this program never reads. The rate that is actually charged is the
     * {@code DEFAULT}/{@code 01}/{@code 0001} row's {@code 00150}{@code &#123;}, which is
     * {@code 15.00} once the trailing zoned overpunch is read as a positive sign. A balance of
     * {@code 1000.00} at that rate gives an exact product of {@code 15000.0000} and a quotient of
     * {@code 12.50} on the nose.
     *
     * <p>Driven with a blank group id rather than a merely absent one, because
     * {@link #aMissingGroupRetriesWithTheDefaultGroupPaddedToTen} already covers a group that is
     * present-but-unknown. Blank is the case the fixtures actually produce, and it is the one that
     * would be missed.
     */
    @Test
    void aBlankAccountGroupIdFallsBackToTheDefaultGroupAtTheFixtureRate() {
        JdbcTemplate t = database();
        seed(t, TCATBAL_DS, tcatbalImage(11L, "01", 1, "1000.00"));
        // ACCT-GROUP-ID as every acctdata.txt row carries it: ten spaces.
        seed(t, ACCT_DS, acctImage(11L, "0.00", "          "));
        seed(t, XREF_DS, xrefImage("4444333322221111", 1, 11L));
        seed(t, XREF_AIX_DS, xrefImage("4444333322221111", 1, 11L));
        // Both groups present, exactly as discgrp.txt ships them - and only DEFAULT is ever read.
        seed(t, DISCGRP_DS, discgrpImage("A000000000", "01", 1, "99.00"));
        seed(t, DISCGRP_DS, discgrpImage(DisclosureGroupRecord.DEFAULT_ACCT_GROUP_ID, "01", 1, "15.00"));
        CapturedSysout sysout = new CapturedSysout();

        job(t, bindings(), sysout).calculateInterest(PARM, sysout);

        // The miss and the retry both happened, in the source's order and with its exact literals.
        assertThat(sysout.lines()).containsSubsequence(
                AccountInterestCalcJob.DISCLOSURE_GROUP_RECORD_MISSING,
                AccountInterestCalcJob.TRY_WITH_DEFAULT_GROUP_CODE);
        // 1000.00 * 15.00 = 15000.0000; / 1200 = 12.50 exactly. NOT the A000000000 row's 99.00,
        // which would have charged 82.50 - the assertion that catches a group id read from the wrong
        // span, since ACCT-ADDR-ZIP holds a value that looks exactly like a group id.
        assertThat(TranRecord.decode(rows(t, SYSTRAN_DS).get(0), ASCII).tranAmt())
                .isEqualTo(new BigDecimal("12.50"));
        // 'DEFAULT' is seven characters moved into an X(10) field, so the key it addresses by is the
        // literal right-space-padded to ten - which is what matches the fixture's own group id.
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
        // Neither the account's group nor DEFAULT exists.
        CapturedSysout sysout = new CapturedSysout();
        AccountInterestCalcJob job = job(t, bindings(), sysout);

        assertThatExceptionOfType(AbendException.class)
                .isThrownBy(() -> job.calculateInterest(PARM, sysout))
                .satisfies(abend -> {
                    // 9999-ABEND-PROGRAM, app/cbl/CBACT04C.cbl:628-632, in full:
                    //   DISPLAY 'ABENDING PROGRAM'   MOVE 0 TO TIMING   MOVE 999 TO ABCODE
                    //   CALL 'CEE3ABD'.                                              <- :632
                    // All three of the values it sets are asserted, not just the return code: TIMING
                    // and ABCODE are the two arguments CEE3ABD is given, and a translation that
                    // dropped either would abend with a different condition on the mainframe.
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
        // The three displayed lines of an abending run, in the source's order: the paragraph's own
        // message from :455, the rendered file status from 9910, and 'ABENDING PROGRAM' from :629.
        assertThat(sysout.lines()).containsSubsequence(
                AccountInterestCalcJob.ERROR_READING_DEFAULT_DISCGRP,
                FileStatus.toDisplayLine(FileStatus.NOT_FOUND),
                AbendException.ABEND_DISPLAY_TEXT);
        // 'ABENDING PROGRAM' is the last thing written: CALL 'CEE3ABD' follows it immediately and
        // nothing after :632 ever runs.
        assertThat(sysout.lines()).last().isEqualTo(AbendException.ABEND_DISPLAY_TEXT);
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
    void anAbsentTransactionDestinationAbendsFromTheOpen() {
        // 0400-TRANFILE-OPEN is an OPEN OUTPUT over SYSTRAN(+1) - DISP=(NEW,CATLG,DELETE) at
        // app/jcl/INTCALC.jcl:L37-L41 - and app/cbl/CBACT04C.cbl:L318-L321 displays
        // 'ERROR OPENING TRANSACTION FILE' and abends when it does not report '00'. This drives the
        // whole path from configuration to abend against a real backend: the destination the deployment
        // named is not there, so the open reports it, rather than the run proceeding to compute interest
        // it can never write. Before the open established anything, this run reached the first
        // COMPUTE and only then discovered the destination.
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
        // Nothing was computed: the abend is at the open, which is the fifth of the five opens, so no
        // interest line and no closing banner was ever emitted.
        assertThat(sysout.lines()).doesNotContain(AccountInterestCalcJob.END_OF_EXECUTION);
    }

    @Test
    void theSystranGenerationClearIsDurable() {
        // finding DB-03. 0400-TRANFILE-OPEN is an OPEN OUTPUT over SYSTRAN(+1), whose
        // DISP=(NEW,CATLG,DELETE) at app/jcl/INTCALC.jcl:L37-L41 means the step allocates a NEW
        // generation - so the open clears the destination and this run writes into an empty one.
        //
        // That clear is DML, and it runs in the ItemStream open callback, which Spring Batch invokes
        // OUTSIDE the chunk transaction. The pool hands out connections with auto-commit disabled, so
        // without a boundary of its own the clear would be reported and then rolled back: the open would
        // answer '00' over a generation still holding the previous run's transactions, and this run's
        // records would be appended to them. Applied through persistDisposition, it commits independently,
        // which is what a rolled-back enclosing boundary proves here.
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
                AccountInterestCalcJob.carddemoDisclosureGroupAccess(t, b, ASCII,
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
                AccountInterestCalcJob.carddemoDisclosureGroupAccess(t, b, ASCII,
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
                AccountInterestCalcJob.carddemoDisclosureGroupAccess(t, b, ASCII,
                        RecordImageForm.CHARACTER),
                unitOfWork(t),
                new PresentBean<>(new CapturedSysout()), FIXED));
    }

    @Test
    @DisplayName("a second step declared beside STEP15 is refused: INTCALC.jcl has one EXEC, and "
            + "running it twice would post interest twice")
    void anAddedStepIsRefused() {
        // The gating and program checks resolve STEP15 by name, so both find it whether it stands alone
        // or first of two, and neither can see this. It is the worst of the single-step cases to leave
        // open: this job adds accrued interest to ACCT-CURR-BAL and rewrites the account
        // (app/cbl/CBACT04C.cbl:L352-L356), so a second pass over the same TCATBALF would post every
        // account's interest a second time and write a second SYSTRAN generation, reporting success.
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
                AccountInterestCalcJob.carddemoDisclosureGroupAccess(t, b, ASCII,
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
        // FUNCTION CURRENT-DATE (app/cbl/CBACT04C.cbl:L212) supplies the two timestamps every generated
        // transaction carries, and every parity case pins them. An optional clock defaulting to
        // Clock.systemDefaultZone() could only ever take effect where the single unconditional Clock bean
        // WebConfig declares had been mis-wired - and it would hide that by writing timestamps that look
        // plausible and can never be reproduced. Refusing null is what turns that into a startup failure.
        JdbcTemplate t = database();
        DatasetBindings b = bindings();

        assertThatNullPointerException().isThrownBy(() -> new AccountInterestCalcJob(
                scaffolding(contracts(), b),
                new TranCatBalRepository(t, b, ASCII, RecordImageForm.CHARACTER),
                new AccountRepository(t, b, ASCII, RecordImageForm.CHARACTER),
                new CardXrefRepository(t, b, ASCII, RecordImageForm.CHARACTER),
                new TransactionRepository(t, b, ASCII, RecordImageForm.CHARACTER, ORDINAL),
                AccountInterestCalcJob.carddemoDisclosureGroupAccess(t, b, ASCII,
                        RecordImageForm.CHARACTER),
                unitOfWork(t),
                new PresentBean<>(new CapturedSysout()), null))
                .withMessageContaining("A Clock is required")
                .withMessageContaining("CURRENT-DATE");

        // And the constructor takes a Clock outright - no ObjectProvider - which is the shape every
        // other Clock consumer in this module already uses.
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
        // INTCALC.jcl:25-41 declares TCATBALF, XREFFILE, XREFFIL1, ACCTFILE, DISCGRP and TRANSACT. The
        // repositories are bound to their own keys - CCXREF and CXACAIX for the cross-reference, ACCTDAT
        // for the account master, SYSTRAN for the generated-transaction output - and every key carries an
        // independent override in application.yml. Each pair is proven equal at construction, so a
        // deployment cannot point a step's DD and its repository's DD at different datasets.
        //
        // Two of these were worse than unchecked before: XREFFILE_DD_NAME was defined as the repository's
        // CCXREF constant, so the job named a key its JCL never mentions, and XREFFIL1 had no constant at
        // all.
        // Only the DDs whose two sides are different configuration keys can diverge. TCATBALF and DISCGRP
        // are addressed by one key from both the step and its repository, so for those the gate degenerates
        // to a declaration check and there is nothing to point apart.
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
                            AccountInterestCalcJob.carddemoDisclosureGroupAccess(t, sound, ASCII,
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
        // INTCALC.jcl:37-41 makes TRANSACT the generated-transaction OUTPUT over SYSTRAN(+1), not the
        // transaction master. application.yml expresses that as a job-scoped alias. Without it, TRANSACT
        // resolves to the global key - the master - and the step would write its generated transactions
        // over live data. This proves the alias is required rather than assumed.
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
                AccountInterestCalcJob.carddemoDisclosureGroupAccess(t, b, ASCII,
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

        /** How many times the file was closed, so closing twice over can be proven harmless. */
        private int closes;

        /**
         * An optional shared verb log.
         *
         * <p>{@code DISCGRP} is the one dataset of the five whose access path is not a Mockito mock -
         * there is no {@code DisclosureGroupRepository} to mock, because {@code CVTRA02Y} has exactly
         * one consumer in the estate - so {@code Mockito.InOrder} cannot see its {@code CLOSE} on its
         * own. Appending to a log the mocks also append to is what puts all five closes on one
         * timeline, which is what {@code :224-228} requires be provable.
         */
        private List<String> verbLog;

        @Override
        public String datasetName() {
            return DISCGRP_DS;
        }

        /**
         * Records this path's {@code CLOSE} into a shared log alongside the mocked datasets' closes.
         *
         * @param log the shared log to append to
         * @return this, for chaining
         */
        ScriptedDisclosureGroupAccess recordingVerbsInto(List<String> log) {
            this.verbLog = log;
            return this;
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
            if (access.verbLog != null) {
                // The COBOL CLOSE, 9200-DISCGRP-CLOSE at app/cbl/CBACT04C.cbl:552. Only this one is
                // logged; close() below is the handle release, which the source has no verb for.
                access.verbLog.add(AccountInterestCalcJob.DISCGRP_DD_NAME);
            }
            return access.closeStatus;
        }

        @Override
        public void close() {
            access.closes++;
        }
    }

    /**
     * Every collaborator of one run, as a stand-in, with each handle opening and closing cleanly until a
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
            // The job reads and writes through ITS OWN DDs - //XREFFILE and //XREFFIL1 at
            // app/jcl/INTCALC.jcl:29-32, and //TRANSACT at :37-41 by way of the SYSTRAN alias - so it
            // asks each repository for a view addressing them before it opens. A bare mock answers null
            // to that, so the handles below would hang off instances the job never touches. Handing back
            // the same stand-in is what the real repositories do when the DD resolves to the dataset
            // they already address.
            //
            // "stand-in" rather than the usual word for a scripted collaborator, deliberately: gate
            // G22 forbids a binary floating-point type anywhere in this program's arithmetic, and the
            // usual word for such a collaborator is spelled identically to one of the two type names
            // that gate names. Keeping it out of the prose means a reviewer who greps this file for
            // that word finds only the assertions in TheNumericParityGates that quote it on purpose.
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
            // The TRANSACT DD's abnormal disposition, which every abend path applies. A mock that left
            // this unstubbed would report null where the contract says a file status.
            when(transactionFile.discardGeneration()).thenReturn(FileStatus.OK);

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

    // =================================================================================================
    // The declared DDs are the DDs used - the DD-mapping finding. app/jcl/INTCALC.jcl:29-32 and :37-41.
    // =================================================================================================

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
            // One dataset reached two ways, never two datasets (gate G45). Which relation each names is
            // what matters here; that the path indexes this base is CardXrefRepository's own guard.
            assertThat(base.getValue().dsname()).isEqualTo(XREF_DS);
            assertThat(path.getValue().dsname()).isEqualTo(XREF_AIX_DS);
            assertThat(path.getValue().base()).isEqualTo(CardXrefRepository.BATCH_DD_NAME);
        }

        @Test
        @DisplayName("the DD names are the JCL's, not the CSD's")
        void theDdNamesAreTheJclsOwn() {
            // The finding was that this job named the CSD's online file names - CCXREF and CXACAIX -
            // where its JCL declares XREFFILE and XREFFIL1, so the DD statements drove nothing.
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
            // The alias is what makes the DD collision resolvable, and it is consumed here rather than
            // declared and ignored: TRANSACT in, SYSTRAN out.
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
            // addressing() returns an instance whose statements resolve on first use, so asking per read
            // would re-describe the relation for every account looked up.
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
            // All five opens were attempted, in the source's order, before the last one refused.
            verify(doubles.tcatbalRepository).open(TranCatBalRepository.OpenMode.INPUT);
            verify(doubles.xrefRepository).openBrowse();
            verify(doubles.accountRepository).open(AccountRepository.OpenMode.I_O);
            // The open goes through the DD this job declares - //TRANSACT at app/jcl/INTCALC.jcl:37-41 -
            // and lands on the dataset the job-scoped SYSTRAN alias resolves it to. The no-argument open
            // is never used, because it would address the name the repository resolved for itself from
            // the global catalogue: the transaction MASTER, which is a different dataset entirely.
            ArgumentCaptor<DatasetBinding> written = ArgumentCaptor.forClass(DatasetBinding.class);
            verify(doubles.transactionRepository).openOutput(written.capture(),
                    eq(AccountInterestCalcJob.TRANSACT_DD_NAME));
            assertThat(written.getValue().dsname()).isEqualTo(SYSTRAN_DS);
            verify(doubles.transactionRepository, never()).openOutput();
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

            // The value is concatenated verbatim into every generated transaction identifier
            // (app/cbl/CBACT04C.cbl:L476-L480), so padding a short one or truncating a long one would
            // write a whole generation of misplaced identifiers and report success. The job's attached
            // validator rejects the launch first; this is the same rule at the point of use.
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

            // This job must NOT attach a validator of its own. BatchConfig.job(String) already attaches
            // one that applies the width rule AND the allow-list, and JobBuilder keeps a single
            // validator, so a second .validator(...) call here would replace that pair with the width
            // rule alone - letting an undeclared key through to change which JobInstance a submission
            // resolves to. Asserting the allow-list arm is what pins that down.
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

    // =============================================================================================
    // What a failure leaves behind, and what it must not take with it.
    // =============================================================================================

    /**
     * Each mutating verb is persisted on its own, because the datasets are {@code RECOVERY(NONE)}.
     *
     * <p>Every {@code FILE} definition in {@code app/csd/CARDDEMO.CSD} carries
     * {@code RECOVERY(NONE) FWDRECOVLOG(NO)} - {@code :9}, {@code :21}, {@code :33}, {@code :46},
     * {@code :59}, {@code :72}, {@code :84}, {@code :96} - and {@code CBACT04C} issues no syncpoint. Its
     * {@code REWRITE FD-ACCTFILE-REC} ({@code app/cbl/CBACT04C.cbl:356}) and its
     * {@code WRITE FD-TRANFILE-REC} are permanent as they return, and the abend at {@code :632} does not
     * reverse them.
     *
     * <p>A chunk-oriented step commits on the chunk boundary, so with a commit interval of one record the
     * account-break rewrite, the following account's reads and that account's generated transaction all
     * sit inside one transaction. A refusal anywhere after the rewrite would roll the rewrite back - and
     * the rewrite is the statement that zeroes {@code ACCT-CURR-CYC-CREDIT} and
     * {@code ACCT-CURR-CYC-DEBIT} at the same time as it adds the interest. Reverting it restores the
     * cycle amounts too, so the operator's re-run recomputes the same interest from the same balances and
     * posts it a second time. That is the loss these tests exist to keep unreachable.
     *
     * <p>The enclosing {@code TransactionTemplate} here stands in for the chunk transaction: it is what
     * Spring Batch opens around the reader, processor and writer, and rolling it back is what a step
     * failure does.
     */
    @Nested
    @DisplayName("Each mutating verb persists on its own - RECOVERY(NONE), no chunk rollback")
    class PerVerbPersistence {

        /**
         * Two accounts' category balances with only the first account present in {@code ACCTFILE}.
         *
         * <p>Reading account 22's first {@code TCATBALF} row breaks the group, which rewrites account 11
         * ({@code 1050-UPDATE-ACCOUNT}); the very next paragraph, {@code 1100-GET-ACCT-DATA}, then fails
         * to find account 22 and abends ({@code app/cbl/CBACT04C.cbl:372-391}). The rewrite is therefore
         * committed and the abend follows it, which is exactly the sequence in question.
         *
         * @param t the database to seed
         */
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

            // 1050-UPDATE-ACCOUNT: ADD WS-TOTAL-INT TO ACCT-CURR-BAL, both cycle amounts zeroed, REWRITE.
            AccountRecord rewritten = AccountRecord.decode(rows(t, ACCT_DS).get(0), ASCII);
            assertThat(rewritten.getAcctId()).isEqualTo(11L);
            assertThat(rewritten.getAcctCurrBal())
                    .as("the rewrite app/cbl/CBACT04C.cbl:356 performed is permanent")
                    .isEqualTo(new BigDecimal("10.41"));
            assertThat(rewritten.getAcctCurrCycCredit()).isEqualTo(new BigDecimal("0.00"));
            assertThat(rewritten.getAcctCurrCycDebit()).isEqualTo(new BigDecimal("0.00"));

            // The other resource, the other disposition. 1300-B-WRITE-TX did write one 350-byte
            // transaction before the break, and each write was durable as it completed - but TRANSACT is
            // DISP=(NEW,CATLG,DELETE) (app/jcl/INTCALC.jcl:37), so an abended step leaves no generation.
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

            // Account 22 never reached 1300-COMPUTE-INTEREST, and the abnormal disposition removed the
            // one transaction account 11 did generate. The account master is untouched by that
            // disposition, because ACCTFILE is DISP=SHR over an existing dataset.
            assertThat(rows(t, SYSTRAN_DS)).isEmpty();
            assertThat(rows(t, ACCT_DS)).hasSize(1);
        }

        /**
         * A disposition that cannot itself be applied does not replace the abend that triggered it.
         *
         * <p>{@code DISP=(NEW,CATLG,DELETE)} at {@code app/jcl/INTCALC.jcl:37} is applied on the way out
         * of a failed run, and it can fail in turn - the backend that refused the write is the same one
         * being asked to discard the generation. When it does, the abend the program raised is what the
         * operator must see, because that is the diagnosis; a secondary failure thrown from the cleanup
         * would replace {@code 'ERROR WRITING TRANSACTION RECORD'} with something that names no
         * paragraph. The failed disposition is reported and the original exception propagates
         * unchanged.
         */
        @Test
        @DisplayName("a failed abnormal disposition does not mask the abend that caused it")
        void aFailedDispositionDoesNotReplaceTheAbend() {
            Doubles doubles = new Doubles().withOneRecord();
            // The write refuses, which is what brings the run to the abnormal disposition at all.
            when(doubles.transactionFile.writeSequential(Mockito.any(TranRecord.class)))
                    .thenReturn(TransactionRepository.WriteResult.other(
                            TransactionRepository.SEQUENTIAL_OUTPUT_DD_NAME,
                            TransactionRepository.PERMANENT_ERROR_STATUS));
            // And the discard refuses too, on the way out.
            when(doubles.transactionFile.discardGeneration()).thenReturn(FileStatus.NOT_FOUND);

            AbendException abend = doubles.runAndExpectAbend();

            // The abend still names the WRITE, not the disposition, and still carries its own codes.
            assertThat(abend.getReturnCode()).isEqualTo(AccountInterestCalcJob.APPL_RESULT_FATAL);
            assertThat(abend.getAbendCode()).hasValue(AbendException.STANDARD_ABEND_CODE);
            assertThat(doubles.sysout.lines()).containsSubsequence(
                    AccountInterestCalcJob.ERROR_WRITING_TRANSACTION,
                    FileStatus.toDisplayLine(TransactionRepository.PERMANENT_ERROR_STATUS),
                    AbendException.ABEND_DISPLAY_TEXT);
            // The disposition was attempted exactly once, and nothing it reported reached SYSOUT: it is
            // not a COBOL paragraph and displays no line.
            verify(doubles.transactionFile, times(1)).discardGeneration();
            assertThat(doubles.sysout.lines()).doesNotContain(AccountInterestCalcJob.END_OF_EXECUTION);
        }

        @Test
        @DisplayName("each verb names the paragraph and the COBOL statement it persists")
        void theVerbsAreNamedForAttribution() {
            // A failed write must be attributable to a paragraph, not to "a chunk", which is the whole
            // reason persistVerb takes a name.
            assertThat(AccountInterestCalcJob.REWRITE_ACCTFILE_VERB)
                    .contains("1050-UPDATE-ACCOUNT")
                    .contains("REWRITE FD-ACCTFILE-REC");
            assertThat(AccountInterestCalcJob.WRITE_TRANFILE_VERB)
                    .contains("WRITE FD-TRANFILE-REC");
        }
    }

    /**
     * The job refuses to restart, and that refusal is parity rather than policy.
     *
     * <p>A JCL step has no restart. An operator who re-runs {@code INTCALC} submits the job again, and
     * that submission reads {@code TCATBALF} from the beginning and allocates a new {@code SYSTRAN}
     * generation, because {@code app/jcl/INTCALC.jcl:37-41} declares
     * {@code DISP=(NEW,CATLG,DELETE)} on {@code SYSTRAN(+1)}.
     *
     * <p>Spring Batch's restart is a different operation: it resumes the same {@code JobInstance} and
     * skips what earlier executions committed. This program stores no position in its execution context -
     * there is nothing in {@code CBACT04C} to store one from - so a resumed execution would re-read
     * {@code TCATBALF} from its first record while the accounts an earlier execution had already
     * rewritten stayed rewritten, and would post their interest a second time.
     */
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

    /**
     * One delegate per step execution, so two launches cannot consume each other's records.
     *
     * <p>A {@code Step} bean is built once, at context refresh, and every later launch uses the reader,
     * processor and writer it was given then. A {@link ChunkDelegate} captured there would therefore be
     * shared, and it holds the {@code PARM} the launcher supplied, the five open files, the cross-record
     * accumulators {@code WS-TOTAL-INT} and {@code WS-LAST-ACCT-NUM}, and the two run counters. Two
     * overlapping launches sharing one would interleave their {@code TCATBALF} reads and post one sum to
     * whichever account broke first.
     *
     * <p>{@code CBACT04C} has none of that to reproduce: each {@code EXEC PGM=CBACT04C} is its own
     * address space with its own {@code WORKING-STORAGE}, its own {@code PARM} and its own five
     * {@code OPEN}s. One delegate per execution is that address space.
     */
    @Nested
    @DisplayName("The step-execution scope - one address space per launch")
    class TheStepExecutionScope {

        /**
         * A step execution carrying the given {@code parmDate}.
         *
         * @param jobExecutionId the id, so two executions are distinguishable
         * @param parmDate       the {@code PARM} to supply
         * @return the execution
         */
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

            // Each execution establishes its scope, waits until the other has established its own, and
            // only then reads back what it is holding. A shared delegate would hand both whichever PARM
            // arrived second. The PARM is derived from the task's own suffix rather than from the thread
            // name, because a pooled thread's name is the pool's business and this test needs the two
            // executions to be distinguishable by something it controls.
            java.util.function.Function<String, Callable<String>> execution = suffix -> () -> {
                String parm = "202207" + suffix;
                subject.beforeStep(stepExecution(parm.hashCode(), parm));
                byParm.put(parm, subject.scopedDelegate().orElseThrow());
                bothScoped.await(ConcurrentTasks.TIMEOUT_SECONDS, TimeUnit.SECONDS);
                String observed = subject.scopedDelegate().orElseThrow().parmDate();
                subject.close();
                return observed;
            };

            // Two things the old shape got wrong. join(20_000) returns whether or not the thread
            // finished, so a task still at the barrier let the assertions run over half-written state and
            // report a missing map entry. And a throwable inside the Runnable killed its thread and went
            // to the default handler, so the real cause was printed above a failure that named something
            // else. Future.get bounds the wait, distinguishes "not finished" from "finished", and rethrows
            // the task's own throwable here.
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

            // A second execution on the same thread is then free to establish its own.
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

            // The first execution's delegate is untouched: discarding it would abandon its open files.
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

            // A second delegate here would open the five files twice and read the file twice.
            assertThat(subject.scopedDelegate().orElseThrow()).isSameAs(first);
            assertThat(first.parmDate()).isEqualTo(PARM);
            subject.close();
        }

        @Test
        @DisplayName("building the step captures no delegate: the Step bean outlives every execution")
        void buildingTheStepCapturesNoDelegate() {
            AccountInterestCalcJob subject = Mockito.spy(new Doubles().job());

            subject.accountInterestCalcStep();

            // The defect this scope exists to remove: a delegate allocated while the bean is being built
            // is shared by every later launch, PARM, accumulators, open files and all.
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

            // The banner of CBACT04C:181 proves the delegate's own open ran, through the router.
            assertThat(doubles.sysout.lines()).first()
                    .isEqualTo(AccountInterestCalcJob.START_OF_EXECUTION);
            assertThat(subject.scopedDelegate().orElseThrow().run()).isNotNull();

            subject.close();
            assertThat(subject.scopedDelegate()).isEmpty();
        }
    }

    /**
     * The {@code DISCGRP} key never reaches the application log as it was read - CWE-117 and CWE-532.
     *
     * <p>The key is built from {@code DIS-ACCT-GROUP-ID X(10)}, {@code DIS-TRAN-TYPE-CD X(02)} and
     * {@code DIS-TRAN-CAT-CD 9(04)} taken out of records the program reads, and a {@code PIC X} span
     * holds whatever bytes are in the record - a carriage return and a line feed among them. Concatenated
     * raw into a log line, such a key ends the entry early and begins one of the writer's choosing, so a
     * forged entry can be planted in a file an operator trusts (CWE-117). The leading span also links the
     * failure to a set of accounts, and a log file is retained longer and read more widely than the
     * dataset it describes (CWE-532).
     *
     * <p>The read itself always uses the real key. Only its rendering is masked, so no diagnosis is lost:
     * the line still names the dataset, the file status and the width.
     */
    @Nested
    @DisplayName("The DISCGRP key is masked and escaped in every log line - CWE-117")
    class DisclosureGroupLogDisclosure {

        /**
         * The logger the access path writes through.
         *
         * <p>Named for the nested class that holds it, not for the enclosing one: a logger name is
         * hierarchical on dots and {@code Outer$Inner} is not a descendant of {@code Outer}, so attaching
         * to the enclosing class would capture nothing and the assertions below would pass vacuously.
         */
        private ch.qos.logback.classic.Logger accessLogger() {
            return (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(
                    AccountInterestCalcJob.JdbcDisclosureGroupAccess.class);
        }

        /**
         * Captures what the access path logs while a keyed read runs, with the dataset seeded by
         * {@code seeding} and the read then made against it.
         *
         * @param keyImage the 16-character key to read with
         * @param seeding  what to do to the dataset after the open and before the read
         * @return the captured messages, in order
         */
        private List<String> capturedFor(String keyImage, java.util.function.Consumer<JdbcTemplate>
                seeding) {
            ch.qos.logback.classic.Logger logger = accessLogger();
            ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                    new ch.qos.logback.core.read.ListAppender<>();
            appender.start();
            logger.addAppender(appender);
            try {
                JdbcTemplate t = database();
                DisclosureGroupAccess access = AccountInterestCalcJob.carddemoDisclosureGroupAccess(
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

        /**
         * Captures the arm a backend refusal takes: the dataset is gone by the time the read is issued.
         *
         * @param keyImage the 16-character key to read with
         * @return the captured messages, in order
         */
        private List<String> capturedForARefusedRead(String keyImage) {
            return capturedFor(keyImage,
                    t -> t.execute("DROP TABLE \"" + DISCGRP_DS + "\""));
        }

        /**
         * Captures the arm a present but malformed row takes: the key matches, the width does not.
         *
         * @param keyImage the 16-character key to read with
         * @return the captured messages, in order
         */
        private List<String> capturedForAMalformedRow(String keyImage) {
            return capturedFor(keyImage, t -> t.update(
                    "INSERT INTO \"" + DISCGRP_DS + "\" VALUES (?)", keyImage + "0012"));
        }

        // ---------------------------------------------------------------------------------------
        // TWO GUARDS IN THE JDBC ADAPTER ARE DELIBERATELY NOT DRIVEN FROM HERE, AND THAT IS A
        // MEASURED DECISION RATHER THAN AN OMISSION.
        //
        // 1. The "no record image at column position" arm of the keyed read fires when a row MATCHES
        //    the key and its record-image column is nevertheless null. The key predicate is a pattern
        //    over that same column, and in SQL `NULL LIKE <pattern>` evaluates to NULL rather than
        //    true, so a null image can never satisfy the predicate that selected it. It was tried:
        //    inserting a null row and reading its key captures nothing at all, because the read
        //    correctly reports NOT FOUND. Reaching the arm would take a driver that returns null for a
        //    row it matched - which is what the guard exists for, and which no relation this module
        //    may add can imitate. Fabricating one would mean a fake JDBC driver, which is neither in
        //    the closed dependency set (practice B1) nor of any parity value.
        //
        // 2. The absent-key rendering of keyForDiagnostics is unreachable for the same class of
        //    reason: every call site composes the key from DIS-GROUP-KEY spans that are never null.
        //
        // Both are defensive guards over a backend contract this environment cannot violate. The
        // package clears the JaCoCo BRANCH gate comfortably without them, and chasing them would mean
        // weakening the production code to make it testable - the wrong trade.
        // ---------------------------------------------------------------------------------------

        /**
         * The rendering a logged {@code DIS-GROUP-KEY} must have.
         *
         * <p>Not {@link SensitiveDiagnostics#maskIdentifier(String)}, and the difference is the point.
         * That method renders ONE identifier and reveals its last four characters. A disclosure-group key
         * is THREE fields ({@code app/cpy/CVTRA02Y.cpy:5-8}) that do not carry the same risk:
         * {@code DIS-ACCT-GROUP-ID X(10)} identifies an account group and is masked in full-width form,
         * while {@code DIS-TRAN-TYPE-CD X(02)} and {@code DIS-TRAN-CAT-CD 9(04)} are classification codes
         * from small fixed code tables that identify no account and no customer, so they stay legible -
         * which is what lets a reader tell "no rate row for this category" from "the dataset refused the
         * read". Masking the composite as a single identifier would have hidden the codes too and left
         * the two arms indistinguishable.
         *
         * <p>Composed from the same two public helpers the production path composes, so a change to
         * either policy fails here rather than being absorbed.
         *
         * @param keyImage the key the read was issued with
         * @return the rendering the log line must carry
         */
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

        /**
         * A row whose record-image column is SQL {@code NULL} is <strong>not</strong> reported as absent.
         *
         * <p>The key predicate is a pattern over that same column and {@code NULL LIKE <pattern>} is
         * never true, so such a row is invisible to the keyed read and the read comes back with nothing
         * matched. Reporting {@code '23'} from there is what finding DB-05 is about, and on this dataset
         * it is a money difference rather than a diagnostic one: {@code app/cbl/CBACT04C.cbl:417-419}
         * takes {@code '23'} as "this account group has no rate for this category" and retries under the
         * {@code 'DEFAULT   '} group at {@code :421-430}, so an unreadable row silently charges the
         * default rate against an account whose own group may define another - and the run reports
         * success.
         *
         * <p>The absence is therefore proved before it is reported. Here it cannot be, so the read takes
         * the permanent-error arm, which {@code :412} turns into {@code 'ERROR READING DISCLOSURE GROUP'}
         * and an abend - loud, and correct, because nothing can establish what rate this account group
         * carries.
         */
        @Test
        @DisplayName("a row with a null record image is NOT reported as absent: DB-05, and it is money")
        void aRowWithANullRecordImageIsNotReportedAsAbsent() {
            JdbcTemplate t = database();
            t.execute("INSERT INTO \"" + DISCGRP_DS + "\" VALUES (NULL)");
            DisclosureGroupAccess access = AccountInterestCalcJob.carddemoDisclosureGroupAccess(
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

        /**
         * The other half of the DB-05 contract on this dataset: a genuinely absent key still reports
         * {@code '23'}, so the {@code DEFAULT}-group retry at {@code app/cbl/CBACT04C.cbl:421-430} is
         * reached exactly as it was.
         */
        @Test
        @DisplayName("a probe the backend refuses reports the permanent error, never the DEFAULT retry")
        void aRefusedProbeDoesNotBecomeTheDefaultRetry() throws SQLException {
            // The one arm no seeded relation can produce: the absence proof itself is refused, so nothing
            // about the rate table has been established. Falling back to '23' there would charge the
            // DEFAULT rate on the strength of a failed probe.
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
            DisclosureGroupAccess access = AccountInterestCalcJob.carddemoDisclosureGroupAccess(
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
            DisclosureGroupAccess access = AccountInterestCalcJob.carddemoDisclosureGroupAccess(
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
            // 16 characters, with CR LF inside the revealed tail - the only part that is not replaced.
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
                    AccountInterestCalcJob.JdbcDisclosureGroupAccess.PERMANENT_ERROR_STATUS);

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

    // =================================================================================================
    // The numeric-parity and structural gates, enforced mechanically rather than by convention.
    //
    // G22, G24, G52 and G53 are properties of the SOURCE, not of a single execution: no run can
    // demonstrate the absence of a binary numeric type, a rounding mode or a mutable static. Each is
    // checked against the code itself, with comments stripped first so the prose that explains why a
    // construct is forbidden cannot be mistaken for a use of it.
    // =================================================================================================

    @Nested
    @DisplayName("the numeric-parity and structural gates hold over the source itself")
    class TheNumericParityGates {

        /**
         * A source file with its comments removed, so a scan sees code and only code.
         *
         * <p>Both files scanned below discuss the forbidden constructs at length in their own
         * documentation - which is exactly what a reader needs and exactly what a naive grep would
         * trip over - so block comments, single-line comments and string literals are all elided
         * before the assertions run.
         *
         * @param packageDirectory the package directory under {@code com/vsergeychik/carddemo}
         * @param simpleName       the file's simple name, without the {@code .java} suffix
         * @return the file's code, with comments and string literals blanked
         * @throws IOException if the file cannot be read
         */
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

        /**
         * Blanks every comment and every string literal, leaving the code's structure intact.
         *
         * <p>Hand-written rather than delegated, because the only alternative in the closed dependency
         * set would be a parser this project may not add (practice B1, practice B11). It is a
         * character scanner over four states, which is sufficient for Java source that contains no
         * text blocks - and the two files scanned contain none.
         *
         * @param source the source text
         * @return the same text with comments and string literals replaced by spaces
         */
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

        /**
         * Gate G22: no {@code double} and no {@code float} in the interest path.
         *
         * <p>Every {@code PIC 9...V...} field in {@code CBACT04C} is a {@link BigDecimal} at the
         * copybook's declared scale, so a binary floating-point type has no legitimate place anywhere
         * in this job or in the decimal policy it delegates to. The scan covers the conversion
         * accessors as well as the type names, because {@code doubleValue()} is how a floating-point
         * value gets in without the word appearing as a declaration.
         *
         * @throws IOException if either source file cannot be read
         */
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

        /**
         * Gate G24: {@link RoundingMode#DOWN} and nothing else.
         *
         * <p>{@code grep -c ROUNDED app/cbl/*} returns zero for all 28 programs, so every COBOL store
         * of an over-precise value truncates. {@code DOWN} is the only mode that does that;
         * {@code HALF_UP} and {@code HALF_EVEN} round, and {@code FLOOR} and {@code CEILING} are
         * directional rather than magnitude-based, which differs from truncation on negative values -
         * the case the negative rows of the interest matrix pin down.
         *
         * <p>{@code docs/technical-specifications.md} asserts {@code HALF_EVEN} for this very
         * calculation and is superseded; AAP 0.7.1 records the correction. This assertion is what
         * stops the superseded value being reintroduced by someone reading that document.
         *
         * @throws IOException if either source file cannot be read
         */
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
            // The policy is where truncation lives, so DOWN must appear there and the job must simply
            // delegate to it rather than rounding on its own account.
            assertThat(policy).contains("RoundingMode.DOWN");
            assertThat(job).doesNotContain("RoundingMode.");
            assertThat(job).contains("CobolDecimal.monthlyInterest");
        }

        /**
         * Gate G46: no dataset name is written in Java.
         *
         * <p>All six DD names this step declares - {@code TCATBALF}, {@code XREFFILE},
         * {@code XREFFIL1}, {@code ACCTFILE}, {@code DISCGRP} and {@code TRANSACT} - are resolved from
         * {@code carddemo.datasets}, so the {@code AWS.M2.CARDDEMO.*} cluster names in
         * {@code app/csd/CARDDEMO.CSD} and {@code app/jcl/INTCALC.jcl} appear nowhere in the job. A
         * hard-coded cluster name would make the job unusable at any site but the one the CSD
         * describes, and would defeat the whole point of the configuration-bound data source.
         *
         * <p>Asserted on the code and separately on what the access path reports at run time, because
         * a name could be assembled from fragments rather than written whole.
         *
         * @throws IOException if the source file cannot be read
         */
        @Test
        @DisplayName("G46 - no AWS.M2.CARDDEMO literal, and every DD resolves from configuration")
        void noDatasetNameIsWrittenInJava() throws IOException {
            String job = codeOf("account", "AccountInterestCalcJob");
            assertThat(job).doesNotContain("AWS.M2").doesNotContain("CARDDEMO.ACCTDATA")
                    .doesNotContain("VSAM.KSDS").doesNotContain("AIX.PATH");

            // Every DD name the step declares is a configuration KEY, and each resolves to whatever
            // that key is bound to - here the test catalogue's own names, which contain no cluster.
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
            assertThat(AccountInterestCalcJob
                    .carddemoDisclosureGroupAccess(database(), b, ASCII, RecordImageForm.CHARACTER)
                    .datasetName()).isEqualTo(DISCGRP_DS).doesNotContain("AWS.M2.CARDDEMO");
        }

        /**
         * Gate G52: every import is explicit, in the job and in this test class alike.
         *
         * <p>The copybook-to-type correspondence is the audit trail of this migration - eleven
         * programs share {@code AccountRecord}, twelve share {@code CardXrefRecord} - and a wildcard
         * import erases it from the file where the correspondence has to be checked.
         *
         * @throws IOException if the source file cannot be read
         */
        @Test
        @DisplayName("G52 - no wildcard import in the job")
        void theJobImportsEveryTypeExplicitly() throws IOException {
            assertThat(codeOf("account", "AccountInterestCalcJob").lines()
                    .filter(line -> line.startsWith("import "))
                    .filter(line -> line.endsWith(".*;"))
                    .toList())
                    .isEmpty();
        }

        /**
         * Gate G53: no mutable static state, in the job or in any of its nested types.
         *
         * <p>COBOL {@code WORKING-STORAGE} belongs to an execution, not to a program image, so it
         * belongs to {@code InterestCalculationRun} and never to the singleton bean. A static field
         * holding {@code WS-TOTAL-INT} or {@code WS-TRANID-SUFFIX} would make two concurrent
         * executions share an accumulator and a transaction-identifier counter, which is both a
         * correctness defect and a source of non-deterministic tests.
         *
         * <p>Checked reflectively over the job and every type declared inside it, so a mutable static
         * added to a nested class - the easy place to hide one - is caught as well.
         */
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

            // Final is necessary but not sufficient for a reference type: the published step sequence
            // is a List, and a caller that could add to it would change what every construction of
            // this job requires. It is unmodifiable.
            assertThat(AccountInterestCalcJob.REQUIRED_STEPS).hasSize(1);
            assertThatExceptionOfType(UnsupportedOperationException.class).isThrownBy(() ->
                    AccountInterestCalcJob.REQUIRED_STEPS.add(new StepContract("STEP99", "X", false)));
        }

        /**
         * Practice B7: the timestamps come from an injected {@link Clock} and never from the wall
         * clock, so two runs of this suite produce the same bytes.
         *
         * <p>{@code Z-GET-DB2-FORMAT-TIMESTAMP} at {@code app/cbl/CBACT04C.cbl:613-626} calls
         * {@code FUNCTION CURRENT-DATE}, which is the one non-deterministic input this program has and
         * which reaches two 26-byte fields of every generated transaction. A job that read the system
         * clock directly would write records no parity case could ever pin, so the scan below refuses
         * the four ways of doing that.
         *
         * @throws IOException if the source file cannot be read
         */
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

            // And the injected clock is the one the timestamp is built from: the same fixed instant
            // renders the same 26 bytes on every invocation, twice over.
            AccountInterestCalcJob subject = job(database(), bindings(), new CapturedSysout());
            assertThat(subject.clock()).isSameAs(FIXED);
            assertThat(subject.db2FormatTimestamp()).isEqualTo(subject.db2FormatTimestamp())
                    .isEqualTo("2022-07-18-12.34.56.780000");
        }
    }

    // =================================================================================================
    // Statement ORDER, verified as order rather than inferred from end state.
    //
    // Gate G27 is an ordering gate, and end-state assertions cannot discharge it: a translation that
    // rewrote the account first and mutated the record afterwards would leave exactly the same three
    // field values behind in a test that only reads the record back. So the sequence is asserted with
    // Mockito.InOrder, and the record's state is snapshotted AT the moment REWRITE is invoked.
    // =================================================================================================

    @Nested
    @DisplayName("the observable statement order, CBACT04C:352-356 and :224-228")
    class TheStatementOrder {

        /**
         * {@code 1050-UPDATE-ACCOUNT} at {@code app/cbl/CBACT04C.cbl:350-356}, in order:
         *
         * <pre>
         * ADD WS-TOTAL-INT  TO ACCT-CURR-BAL          :352
         * MOVE 0 TO ACCT-CURR-CYC-CREDIT              :353
         * MOVE 0 TO ACCT-CURR-CYC-DEBIT               :354
         * REWRITE FD-ACCTFILE-REC FROM ACCOUNT-RECORD :356
         * </pre>
         *
         * <p>The three mutations are proven to precede the {@code REWRITE} by reading the record
         * <em>inside</em> the stubbed rewrite, which is the only vantage point from which "before the
         * write" and "after the write" are distinguishable. {@code InOrder} then places the whole
         * paragraph after the reads that fed it and before the closes.
         */
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

            // The three field values as they stand at the instant REWRITE is issued.
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
            // :352 had already run: 500.00 + 10.41. :353 and :354 had already run: both cycles zero,
            // at scale 2 - not null, and not an integer zero that would encode as a different image.
            assertThat(atRewrite).containsExactly(new BigDecimal("510.41"), new BigDecimal("0.00"),
                    new BigDecimal("0.00"));
            assertThat(atRewrite).allSatisfy(value -> assertThat(value.scale()).isEqualTo(2));

            // And the paragraph as a whole sits after the reads that fed it. The account break at
            // :194-205 reads ACCTFILE for the NEW account before the interest of the OLD one is
            // posted, because :196 runs before :202-203 - so the second readByKey precedes the
            // rewrite, and InOrder is what makes that visible.
            InOrder order = Mockito.inOrder(doubles.tcatbalFile, doubles.accountFile,
                    doubles.transactionFile);
            order.verify(doubles.tcatbalFile).readNext();
            order.verify(doubles.accountFile).readByKey(11L);
            order.verify(doubles.transactionFile).writeSequential(Mockito.any(TranRecord.class));
            order.verify(doubles.tcatbalFile).readNext();
            order.verify(doubles.accountFile).rewrite(Mockito.any(AccountRecord.class));
            order.verify(doubles.accountFile).readByKey(22L);

            // Exactly one rewrite: the last group is never written back (:219-221 is unreachable).
            verify(doubles.accountFile, times(1)).rewrite(Mockito.any(AccountRecord.class));

            // The rewritten image is the whole 300-byte ACCOUNT-RECORD with FILLER X(178) present and
            // space-filled - gates G19 and G21, asserted on the record REWRITE was actually handed.
            ArgumentCaptor<AccountRecord> rewritten = ArgumentCaptor.forClass(AccountRecord.class);
            verify(doubles.accountFile).rewrite(rewritten.capture());
            String image = rewritten.getValue().toFixedWidthString();
            assertThat(image).hasSize(AccountRecord.RECORD_LENGTH);
            assertThat(image).hasSize(300);
            assertThat(image.substring(AccountRecord.FILLER_OFFSET))
                    .isEqualTo(" ".repeat(AccountRecord.FILLER_LENGTH))
                    .hasSize(178);
        }

        /**
         * {@code PERFORM 9000-TCATBALF-CLOSE} through {@code PERFORM 9400-TRANFILE-CLOSE},
         * {@code app/cbl/CBACT04C.cbl:224-228}, in the source's order:
         * {@code TCATBALF}, {@code XREFFILE}, {@code DISCGRP}, {@code ACCTFILE}, {@code TRANFILE}.
         *
         * <p>The order is observable and not cosmetic: each paragraph carries its own message, so a
         * transposed pair emits a different message when the first of them refuses. It is asserted
         * twice over - once with {@code Mockito.InOrder} across the four mocked handles, and once
         * across all five on a shared verb log, because {@code DISCGRP} has no mock to order against.
         */
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

        /**
         * The mirror image: {@code PERFORM 0000-TCATBALF-OPEN} through
         * {@code PERFORM 0400-TRANFILE-OPEN}, {@code app/cbl/CBACT04C.cbl:182-186}, which is the same
         * five datasets in the same order and is asserted for the same reason.
         */
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

    // =================================================================================================
    // CWE-117 / CWE-532: DIS-GROUP-KEY is storage-derived, so it never reaches a log line raw.
    // =================================================================================================

    @Nested
    @DisplayName("the disclosure-group key is sanitized before it is logged")
    class KeyDiagnostics {

        /** This job's own source, for the scan below. */
        private static String subjectSource() throws IOException {
            Path relative = Path.of("app", "java", "src", "main", "java", "com", "vsergeychik",
                    "carddemo", "account", "AccountInterestCalcJob.java");
            Path candidate = Path.of("").toAbsolutePath();
            while (candidate != null) {
                Path resolved = candidate.resolve(relative);
                if (Files.exists(resolved)) {
                    return Files.readString(resolved, StandardCharsets.UTF_8);
                }
                candidate = candidate.getParent();
            }
            throw new IllegalStateException("AccountInterestCalcJob.java was not found from "
                    + Path.of("").toAbsolutePath());
        }

        @Test
        @DisplayName("no log statement interpolates the raw key image")
        void noLogStatementCarriesTheRawKey() throws IOException {
            // DIS-GROUP-KEY arrives from storage, so a control character in those bytes could break a
            // log line in two and forge a record (CWE-117), and DIS-ACCT-GROUP-ID names the account
            // group being priced (CWE-532). Every LOG statement that mentions the key must therefore
            // route it through keyForDiagnostics, which masks the group and single-lines the whole.
            //
            // Scoped to LOG statements deliberately. Two other sites interpolate the key and MUST keep
            // doing so: sysout.write(ACCOUNT_NOT_FOUND_PREFIX + keyImage) at L375 and L397 is the
            // program's own DISPLAY, and its bytes are what the parity diff compares - sanitizing those
            // would be a parity defect, not a fix. A third is an IllegalArgumentException reporting a
            // Java caller bug, and the module never hands a throwable's message to a logger.
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

        /**
         * Every {@code LOG.<level>(...)} statement in the given source, each as one string.
         *
         * @param source the file's text
         * @return the statements, in order
         */
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
            // The rendering keyForDiagnostics produces, asserted through DiagnosticText so that the split
            // point stays tied to the copybook: DIS-ACCT-GROUP-ID is X(10), then X(02) and 9(04).
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
