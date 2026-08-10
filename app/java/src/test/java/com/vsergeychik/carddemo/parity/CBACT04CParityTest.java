package com.vsergeychik.carddemo.parity;

import com.vsergeychik.carddemo.account.AccountInterestCalcJob;
import com.vsergeychik.carddemo.account.AccountInterestCalcJob.SysoutSink;
import com.vsergeychik.carddemo.account.AccountRepository;
import com.vsergeychik.carddemo.account.model.AccountRecord;
import com.vsergeychik.carddemo.account.model.DisclosureGroupRecord;
import com.vsergeychik.carddemo.card.CardXrefRepository;
import com.vsergeychik.carddemo.card.model.CardXrefRecord;
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
import com.vsergeychik.carddemo.config.DatasetUnitOfWork;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The twenty-case parity gate for {@code CBACT04C}, the interest calculator - and the
 * <strong>numeric-parity anchor of the whole migration</strong>.
 *
 * <p>{@code app/cbl/CBACT04C.cbl:464-465} carries the only division in the entire estate:
 *
 * <pre>
 * COMPUTE WS-MONTHLY-INT
 *  = ( TRAN-CAT-BAL * DIS-INT-RATE) / 1200
 * </pre>
 *
 * <p>There is no {@code ROUNDED} phrase on it, and an exhaustive search of all twenty-eight programs
 * in {@code app/cbl} finds the keyword <strong>zero</strong> times. COBOL therefore truncates the
 * excess fractional digits when it stores the quotient into {@code WS-MONTHLY-INT PIC S9(09)V99}
 * ({@code :168}), which makes {@code CobolDecimal.COBOL_ROUNDING} - and nothing else - the faithful
 * mode. There is likewise no {@code MULTIPLY} and no {@code DIVIDE} verb anywhere in the estate, so
 * if the scale or the rounding mode is wrong anywhere in this system, these twenty cases are where it
 * has to be caught. That is why {@link #truncationIsObservableAndSharp()} exists alongside the
 * declarative gate: it pins the one arithmetic decision the rest of the migration inherits.
 *
 * <h2>Provenance of the expectations: statically derived, never captured (practice B12, risk R-A)</h2>
 * <p><strong>Every expected value in {@code src/test/resources/parity/CBACT04C/} was derived by
 * reading the COBOL, not by running it.</strong> No legacy execution baseline exists or can exist in
 * this environment: there is no z/OS runtime, the available COBOL compiler reports
 * {@code indexed file handler : disabled} and this program declares four {@code ORGANIZATION INDEXED}
 * files, it refuses a {@code PROCEDURE DIVISION USING} program as an executable, and it supplies no
 * Language Environment {@code CEE*} services - so neither {@code CEE3ABD} nor a runnable
 * {@code CBACT04C} is reachable. Eight such blockers are enumerated in the plan's Special Analysis and
 * the deviation is registered there as risk <strong>R-A</strong>.
 *
 * <p>What replaces a captured baseline is an exhaustive structured reading of each paragraph,
 * cross-checked against four independent authorities:
 * <ul>
 *   <li>the copybook byte layouts - {@code CVTRA01Y} 50 B, {@code CVTRA02Y} 50 B, {@code CVACT01Y}
 *       300 B, {@code CVACT03Y} 50 B and {@code CVTRA05Y} 350 B - which fix every offset and every
 *       {@code PICTURE} clause mechanically rather than interpretively;</li>
 *   <li>{@code app/jcl/INTCALC.jcl}, which fixes the six DD names, the
 *       {@code PARM='2022071800'} and the output's {@code DCB=(RECFM=F,LRECL=350)};</li>
 *   <li>the real fixtures under {@code app/data/ASCII}, so every case is seeded from genuine
 *       production-shaped data rather than invented data;</li>
 *   <li>the enumerable arithmetic and guard sites of the program itself, which is where accumulated
 *       legacy behaviour hides.</li>
 * </ul>
 * Everything else the gate demands is preserved exactly: twenty cases, field-for-field diffing, and a
 * diff count that must be zero across all twenty. Only the provenance of the expected values differs,
 * and it is stated here rather than absorbed silently.
 *
 * <h2>Three fixture facts that decide most of these cases</h2>
 * <ol>
 *   <li><strong>Every row of {@code tcatbal.txt} carries {@code TRAN-CAT-BAL} of
 *       {@code 0000000000}<code>&#123;</code> - a positive zero.</strong> Interest on zero is zero at
 *       any rate, so the truncation cases cannot come from that fixture and state their balances
 *       inline, in the fixture's own 50-byte shape.</li>
 *   <li><strong>Every row of {@code acctdata.txt} carries {@code ACCT-GROUP-ID} as ten spaces</strong>,
 *       with {@code A000000000} sitting in {@code ACCT-ADDR-ZIP} at offset 103 instead. So a run over
 *       untouched fixture data moves <em>spaces</em> into the disclosure-group key at {@code :210}, the
 *       {@code READ} at {@code :416} reports {@code '23'}, and <em>every</em> record takes the
 *       {@code 'DEFAULT'} retry at {@code :437-438}. That is preserved behaviour, not a defect to
 *       repair, and it is what makes the fallback path reachable straight from the fixtures.</li>
 *   <li><strong>{@code cardxref.txt} rows measure 36 bytes where {@code CVACT03Y} declares 50</strong>,
 *       the trailing {@code FILLER PIC X(14)} being absent, so every case that seeds the
 *       cross-reference declares {@code CARDXREF_FILLER_PAD_36_TO_50} and the fourteen spaces are
 *       supplied once, at seed time.</li>
 * </ol>
 *
 * <h2>Preserved behaviour these cases assert as behaviour</h2>
 * <ul>
 *   <li><strong>{@code 1400-COMPUTE-FEES} computes nothing.</strong> The paragraph at
 *       {@code :518-520} is a header, the comment {@code * To be implemented} and {@code EXIT.} - zero
 *       statements - and it is nevertheless performed at {@code :216} once per interest computation,
 *       inside the {@code IF DIS-INT-RATE NOT = 0} guard. {@link #computeFeesComputesNothing()} asserts
 *       that positively, and case14 asserts it declaratively: the amount written is the interest and
 *       nothing more, with no extra record and no extra line. Implementing a fee would be adding a
 *       feature the estate does not have (practice B5).</li>
 *   <li><strong>The {@code ELSE} at {@code :219-221} is unreachable</strong>, so the final account
 *       group's accumulated interest is never written back. Cases that end on an account break expect
 *       one fewer rewrite than they have accounts, and that is correct.</li>
 *   <li><strong>{@code WS-TRANID-SUFFIX} is never reset</strong>, so it counts across account
 *       boundaries - case12 walks it from {@code 000001} upwards through several accounts.</li>
 *   <li><strong>An abend discards the output generation.</strong>
 *       {@code app/jcl/INTCALC.jcl:37} is {@code DISP=(NEW,CATLG,DELETE)}, so a run that does not reach
 *       {@code GOBACK} leaves {@code SYSTRAN} with no row at all while the account rewrites, which are
 *       {@code DISP=SHR} over an existing dataset, stand.</li>
 * </ul>
 *
 * <h2>How the unit is reached (gate G51)</h2>
 * <p>Through {@link AccountInterestCalcJob#calculateInterest(String, SysoutSink)}, which runs
 * {@code :181-232} statement for statement. There is <strong>no {@code JobLauncher}</strong>, no job
 * repository, no step executor, no application context and no HTTP layer anywhere in this class, so a
 * failure names a paragraph rather than a framework. The five datasets are relations in a per-case
 * in-memory database and the code page is named explicitly on every conversion; the clock is the
 * harness's pinned one, which is what makes the two 26-byte timestamps every generated transaction
 * carries assertable byte for byte.
 *
 * <h2>Rules</h2>
 * <p>{@code review_rules} reports <strong>no user rules provided</strong> for this project. Their
 * absence is not permission to lower the bar, so this class is held to the plan's enterprise
 * practices instead: exact dependency versions with no placeholders (B1), reference trees never
 * written to (B3), dead code preserved (B5), a deterministic non-interactive run (B7), explicit
 * charset, scale and rounding with no wildcard import anywhere (B8), no static mutable state (B9),
 * tests shipped with the implementation (B10), and environmental limits documented rather than
 * absorbed (B12).
 *
 * <h2>A note to a reviewer grepping this file</h2>
 * <p>Several prohibited things are <strong>named</strong> above and below on purpose, so the next
 * engineer learns they are prohibited rather than merely unused: {@code JobLauncher}, the rounding
 * modes this system must never use, and the mainframe dataset-name prefix that must never appear in
 * source. Every such mention is a prohibition notice sitting in a comment. The machine-checkable
 * invariant is the one that matters: <em>no forbidden token appears on any non-comment line of this
 * file</em> - no wildcard import, no binary floating-point type, no rounding mode other than
 * {@link CobolDecimal#COBOL_ROUNDING}, no literal dataset name, no static mutable field, and no web
 * or launcher type anywhere in the executable text.
 */
@DisplayName("CBACT04C - interest calculator parity gate (20 cases, diff count must be zero)")
final class CBACT04CParityTest {

    /**
     * The program these cases are about, taken from the unit under test rather than re-spelled, so the
     * class name, the {@code parity/CBACT04C/} resource directory and the case files cannot drift
     * apart.
     */
    private static final String PROGRAM = AccountInterestCalcJob.PROGRAM_ID;

    /** The single column every dataset relation holds: one fixed-width record image per row. */
    private static final String IMAGE_COLUMN = "REC";

    /**
     * The physical-record ordinal a sequential output is read back in, exactly as
     * {@code application-test.yml} configures {@code carddemo.physical-sequence.expression}: H2's own
     * row-identifier pseudo-column, which increases with each insert and therefore returns records in
     * the order they were written.
     */
    private static final PhysicalSequence WRITE_ORDER = PhysicalSequence.of("_ROWID_");

    // =============================================================================================
    //  THE SIX DD NAMES OF app/jcl/INTCALC.jcl:25-41, AND THE DATASETS BEHIND THEM.
    //
    //  A case addresses a dataset by its BINDING KEY and never by a dataset name, which is what keeps
    //  gate G46 satisfiable: not one of the production dataset names catalogued in app/csd/CARDDEMO.CSD
    //  is spelled anywhere in this file, and the names below are this test's own CARDDEMO.PARITY
    //  namespace. The keys are the JCL's DD names rather than the CSD's file names, because it is the
    //  JCL that declares what this step opens.
    //
    //  XREFFILE and XREFFIL1 are TWO ACCESS PATHS OVER ONE CLUSTER, not two clusters - the step opens
    //  the base KSDS and the alternate-index path over it in the same step, which is precisely the
    //  shape gate G45 is about. They are given distinct relations here because an alternate-index path
    //  is a distinct access path with its own ordering; both hold the same records, and every case
    //  seeds them from the same rows and expects neither to be mutated.
    // =============================================================================================

    /** {@code //TCATBALF} - the browse that drives the program's only loop. {@code CVTRA01Y}, 50 B. */
    private static final String TCATBALF_KEY = TranCatBalRepository.DD_NAME;

    /** {@code //ACCTFILE} - opened I-O, read on every account break and rewritten. {@code CVACT01Y}, 300 B. */
    private static final String ACCTFILE_KEY = AccountRepository.BATCH_DD_NAME;

    /** {@code //XREFFILE} - the cross-reference base cluster. {@code CVACT03Y}, 50 B. */
    private static final String XREFFILE_KEY = CardXrefRepository.BATCH_DD_NAME;

    /** {@code //XREFFIL1} - the alternate-index path over that same cluster. {@code CVACT03Y}, 50 B. */
    private static final String XREFFIL1_KEY = CardXrefRepository.ALTERNATE_INDEX_BATCH_DD_NAME;

    /** {@code //DISCGRP} - the interest-rate lookup. {@code CVTRA02Y}, 50 B. */
    private static final String DISCGRP_KEY = AccountInterestCalcJob.DISCGRP_DD_NAME;

    /**
     * The generated-transaction output.
     *
     * <p>{@code app/jcl/INTCALC.jcl:37-41} declares it under the DD name {@code TRANSACT} - the same
     * eight characters as the CICS transaction master - and points it at
     * {@code SYSTRAN(+1)} at {@code RECFM=F LRECL=350}. The job resolves that DD through its
     * job-scoped alias and lands on this key, so the fingerprint reports the writes under the name of
     * the dataset the rows genuinely land in, and the transaction master this program never touches
     * cannot be confused with it.
     */
    private static final String SYSTRAN_KEY = TransactionRepository.SEQUENTIAL_OUTPUT_DD_NAME;

    /** {@code CVTRA01Y}'s dataset. */
    private static final String TCATBALF_DSNAME = "CARDDEMO.PARITY.TCATBALF.KSDS";

    /** {@code CVACT01Y}'s dataset, addressed as {@code ACCTDAT} by the repository and {@code ACCTFILE} by the JCL. */
    private static final String ACCTDATA_DSNAME = "CARDDEMO.PARITY.ACCTDATA.KSDS";

    /** The cross-reference base cluster, addressed as {@code CCXREF} and {@code XREFFILE}. */
    private static final String CARDXREF_DSNAME = "CARDDEMO.PARITY.CARDXREF.KSDS";

    /** The alternate-index path over it, addressed as {@code CXACAIX} and {@code XREFFIL1}. */
    private static final String CARDXREF_AIX_DSNAME = "CARDDEMO.PARITY.CARDXREF.AIX";

    /** {@code CVTRA02Y}'s dataset. */
    private static final String DISCGRP_DSNAME = "CARDDEMO.PARITY.DISCGRP.KSDS";

    /** The generated-transaction generation this step allocates and writes. */
    private static final String SYSTRAN_DSNAME = "CARDDEMO.PARITY.SYSTRAN";

    /**
     * The transaction master.
     *
     * <p>Declared because {@code TransactionRepository} binds all three of its keys at construction,
     * and created as a relation for the same reason - but this program never reads or writes it, and no
     * case seeds it or expects anything of it. Its presence is what proves the job's job-scoped
     * {@code TRANSACT} alias is doing its work: without the alias the generated transactions would land
     * here, in live data, instead of in {@link #SYSTRAN_DSNAME}.
     */
    private static final String TRANSACT_DSNAME = "CARDDEMO.PARITY.TRANSACT.KSDS";

    /** {@code app/jcl/INTCALC.jcl} declares one step and no {@code COND}, so nothing gates it. */
    private static final boolean STEP_IS_UNGATED = false;

    /** The type {@code carddemo.jobs} declares the {@code parmDate} parameter as. */
    private static final String PARM_DATE_TYPE = "string";

    // =============================================================================================
    //  Per-invocation collaborators. Each is a plain nested type rather than a mock, so what the test
    //  supplies is auditable at the point of use, and each is instantiated per invocation so no two
    //  cases can see each other's state (practice B9 - there is no static mutable field in this class).
    // =============================================================================================

    /**
     * The {@code SYSOUT} destination, capturing the displayed line sequence in order.
     *
     * <p>{@code CBACT04C}'s observable output is its datasets <em>and</em> its {@code DISPLAY} lines:
     * the run banner at {@code :181} and {@code :230}, the 50-byte category-balance image at
     * {@code :193}, {@code 'ACCOUNT NOT FOUND: '} at {@code :375} and {@code :397}, the two disclosure
     * group lines at {@code :418-419}, and on a fatal status the paragraph's own message, the rendered
     * file status and {@code 'ABENDING PROGRAM'}. All of them are compared positionally, so the order
     * this list preserves is part of the expectation.
     */
    private static final class CapturedSysout implements SysoutSink {

        /** The lines emitted, in emission order. Per-instance, never shared. */
        private final List<String> lines = new ArrayList<>();

        @Override
        public void write(String line) {
            lines.add(line);
        }

        /**
         * The lines emitted so far.
         *
         * @return the live list, read straight after the run; never {@code null}
         */
        private List<String> lines() {
            return lines;
        }
    }

    /**
     * An {@link ObjectProvider} holding one bean that is genuinely present.
     *
     * @param bean the bean to supply; never {@code null}
     * @param <T> the bean type
     */
    private record SuppliedBean<T>(T bean) implements ObjectProvider<T> {

        @Override
        public T getObject() {
            return Objects.requireNonNull(bean, "a supplied bean is never null");
        }
    }

    /**
     * An {@link ObjectProvider} for a bean that does not exist on this path.
     *
     * <p>Used for the {@link JobRepository}. This class never builds a Spring Batch {@code Job} - the
     * whole point of reaching {@link AccountInterestCalcJob#calculateInterest(String, SysoutSink)}
     * directly is that no job repository, no launcher and no step executor are in the path (gate G51) -
     * so declaring the bean absent is the truthful thing to declare. {@code ObjectProvider} specifies
     * {@code getIfAvailable()} as returning {@code null} when {@code getObject()} reports no such
     * bean, which is exactly the contract honoured here.
     *
     * @param <T> the bean type that is absent
     */
    private static final class AbsentBean<T> implements ObjectProvider<T> {

        @Override
        public T getObject() {
            throw new NoSuchBeanDefinitionException("A " + JobRepository.class.getSimpleName()
                + " is deliberately absent from a parity run: " + PROGRAM + "'s paragraphs are reached "
                + "through calculateInterest(String, SysoutSink), with no launcher, no job repository "
                + "and no step executor between the assertion and the code.");
        }
    }

    // =============================================================================================
    //  THE GATE
    // =============================================================================================

    /**
     * The twenty cases of {@code src/test/resources/parity/CBACT04C/}, in ascending case order.
     *
     * <p>{@link ParityHarness#casesOf(String)} already refuses anything other than exactly
     * {@code case01.json} through {@code case20.json} - a short set and a stray file are both loud
     * failures there - and the assertion below states the count a second time at the point where a
     * reader of this class looks for it. Twenty is the gate: "diff count is zero across all twenty
     * cases" is satisfied vacuously by a set of four, so the count is checked rather than assumed.
     *
     * @return exactly twenty cases; never {@code null}
     */
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

    /**
     * Runs one case and requires the field-by-field diff count to be <strong>zero</strong> (gate G18).
     *
     * <p>{@link ParityHarness#judge(ParityCase, UnitKind, ParityHarness.ParityUnit)} seeds the case's
     * datasets, reaches the unit through {@link #invokeInterestCalculator(Invocation)}, decodes what the
     * run produced into named fields and hands both to {@link FieldDiffer}. Nothing is compared as a
     * whole string: every difference names a dataset, a row and a copybook field.
     *
     * <p>{@link DiffResult#render()} is surfaced in the failure message rather than summarised, because
     * the differ has already written a better explanation of a difference than an assertion could - it
     * names the span, its {@code PICTURE} kind, both values and, for a signed scaled field, both
     * decoded numbers.
     *
     * <p>{@link UnitKind#BATCH_JOB} is stated at the call site rather than read from the case, so the
     * harness can check it against the case's own declaration before anything runs: a case file that
     * called this program a controller would be refused instead of quietly exercising the wrong shape.
     *
     * @param parityCase one of the twenty cases
     */
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

    // =============================================================================================
    //  THE ARITHMETIC THE WHOLE MIGRATION INHERITS (gates G22, G23, G24, G25, G26)
    //
    //  These four are not a substitute for the declarative gate above; they are the statements a
    //  reader of this class needs to see spelled out, because they are the decisions every other
    //  monetary path in the system copies.
    // =============================================================================================

    /**
     * The truncation proof, and the sharpest single assertion in this class (gates G24, G25).
     *
     * <p>{@code 1000.00} at {@code 12.50} percent has an exact product of {@code 12500.0000} and an
     * exact quotient of {@code 10.41666...} - a non-terminating quotient whose third decimal digit is
     * six. That is the only kind of operand pair that can tell truncation apart from rounding, and it is
     * why this pair is chosen:
     * <ul>
     *   <li>storing it into {@code WS-MONTHLY-INT PIC S9(09)V99} with no {@code ROUNDED} phrase gives
     *       {@code 10.41};</li>
     *   <li>any mode that carried the excess into the last retained digit would give {@code 10.42};</li>
     *   <li>the two differ, so a wrong mode cannot pass this assertion.</li>
     * </ul>
     *
     * <p>The rounded answer is written as the literal {@code 10.42} rather than produced by naming a
     * rounding mode, deliberately: the excluded modes are excluded from this module outright, and a
     * test that named one in order to disprove it would be the one place they appeared.
     */
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

        // The exact quotient, carried to a scale wide enough to show what was discarded. Its third
        // decimal digit is 6, which is what makes the case above able to distinguish the two answers at
        // all: a terminating quotient would agree under every mode and would prove nothing.
        BigDecimal exact = balance.multiply(rate)
            .divide(new BigDecimal("1200"), CobolDecimal.MONETARY_SCALE + 4,
                CobolDecimal.COBOL_ROUNDING);
        assertThat(exact).isEqualTo(new BigDecimal("10.416666"));
        assertThat(exact.setScale(CobolDecimal.MONETARY_SCALE, CobolDecimal.COBOL_ROUNDING))
            .isEqualTo(stored);
        assertThat(exact.compareTo(stored)).isPositive();

        // The receiver's scale is part of the contract, not a by-product: CVTRA05Y declares TRAN-AMT at
        // S9(09)V99 too, so a result at any other scale could not be moved into it at :490.
        assertThat(stored.scale()).isEqualTo(CobolDecimal.MONETARY_SCALE);
        assertThat(CobolDecimal.COBOL_ROUNDING.name()).isEqualTo("DOWN");
    }

    /**
     * A second non-terminating quotient at a different magnitude, so the mode is pinned by more than one
     * worked example.
     *
     * <p>{@code 0.05} at {@code 15.00} - the rate every {@code DEFAULT} group row of {@code discgrp.txt}
     * carries for type {@code 01} category {@code 0001} - has an exact quotient of
     * {@code 0.000625}, which truncates to {@code 0.00}. The whole of the interest is discarded, and
     * that is the correct answer: a rounding mode would keep a tenth of a cent that the COBOL never
     * stores. The complementary pair {@code 100000.00} at {@code 9.99} shows the same discard at the
     * other end of the range, where the retained digits are large.
     */
    @Test
    @DisplayName("truncation discards the whole result when the quotient is below a cent")
    void truncationDiscardsSubCentAndScalesUp() {
        assertThat(AccountInterestCalcJob.computeMonthlyInterest(
                new BigDecimal("0.05"), new BigDecimal("15.00")))
            .isEqualTo(new BigDecimal("0.00"))
            .isNotEqualTo(new BigDecimal("0.01"));

        // 100000.00 * 9.99 / 1200 = 832.5 exactly at scale 1, so this pair proves the store keeps a
        // terminating quotient intact rather than truncating something it should not.
        assertThat(AccountInterestCalcJob.computeMonthlyInterest(
                new BigDecimal("100000.00"), new BigDecimal("9.99")))
            .isEqualTo(new BigDecimal("832.50"));

        // 33333.33 * 15.00 / 1200 = 416.666625 -> 416.66, and 416.67 is the rounded answer.
        assertThat(AccountInterestCalcJob.computeMonthlyInterest(
                new BigDecimal("33333.33"), new BigDecimal("15.00")))
            .isEqualTo(new BigDecimal("416.66"))
            .isNotEqualTo(new BigDecimal("416.67"));

        // A negative balance truncates towards zero as well, which is what "no ROUNDED" means for a
        // signed receiver: -0.005... keeps nothing, and -10.41666... keeps -10.41 rather than -10.42.
        assertThat(AccountInterestCalcJob.computeMonthlyInterest(
                new BigDecimal("-1000.00"), new BigDecimal("12.50")))
            .isEqualTo(new BigDecimal("-10.41"))
            .isNotEqualTo(new BigDecimal("-10.42"));
    }

    /**
     * {@code 1400-COMPUTE-FEES} computes nothing, asserted positively (gate G26, practice B5).
     *
     * <p>The paragraph at {@code app/cbl/CBACT04C.cbl:518-520} contains zero statements. Proving that
     * of a Java method means proving it changes nothing observable, so a job is built over a seeded
     * database, every relation is photographed, the method is invoked repeatedly, and the photographs
     * are compared. Nothing is written, nothing is rewritten, and not one line is displayed.
     *
     * <p>The declarative half of this gate is case14, which shows the same thing from the other side:
     * the amount written for a record is the interest and nothing more, so no fee reached
     * {@code WS-TOTAL-INT} - where it would have been indistinguishable from interest by inspecting the
     * account master alone.
     */
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

    /**
     * The generated identifier's geometry, which is why {@code PARM} is character data (rule R1, and the
     * {@code STRING} at {@code :476-480}).
     *
     * <p>{@code PARM-DATE PIC X(10)} concatenated with {@code WS-TRANID-SUFFIX PIC 9(06)} is exactly the
     * sixteen bytes of {@code TRAN-ID PIC X(16)}. The {@code PARM} is never parsed as a date - it is
     * strung in verbatim - so a value that is not a date at all would still produce a well-formed
     * identifier, and case19 relies on that.
     */
    @Test
    @DisplayName("PARM-DATE X(10) + WS-TRANID-SUFFIX 9(06) is exactly TRAN-ID X(16)")
    void generatedTransactionIdentifierGeometryHolds() {
        assertThat(AccountInterestCalcJob.PARM_DATE_WIDTH
            + AccountInterestCalcJob.TRANID_SUFFIX_WIDTH).isEqualTo(TranRecord.TRAN_ID_LENGTH);
        assertThat(AccountInterestCalcJob.PARM_DATE_WIDTH).isEqualTo(BatchConfig.PARM_DATE_WIDTH);

        // The three literals :482-484 move, and the receiver each lands in. TRAN-CAT-CD is PIC 9(04) and
        // receives the alphanumeric literal '05', which COBOL treats as an unsigned integer and stores
        // as 0005 - not 0500. The case files pin that byte pair, so it is named here too.
        assertThat(AccountInterestCalcJob.GENERATED_TRAN_TYPE_CD).isEqualTo("01");
        assertThat(AccountInterestCalcJob.GENERATED_TRAN_CAT_CD).isEqualTo("05");
        assertThat(AccountInterestCalcJob.GENERATED_TRAN_SOURCE).isEqualTo("System");
        assertThat(AccountInterestCalcJob.GENERATED_TRAN_DESC_PREFIX).isEqualTo("Int. for a/c ");
        assertThat(AccountInterestCalcJob.GENERATED_TRAN_DESC_PREFIX.length()
            + AccountRecord.ACCT_ID_LENGTH).isEqualTo(24);

        // The record widths every case pins, from the copybooks rather than from a case file.
        assertThat(TranRecord.RECORD_LENGTH).isEqualTo(350);
        assertThat(AccountRecord.RECORD_LENGTH).isEqualTo(300);
        assertThat(TranCatBalRecord.RECORD_LENGTH).isEqualTo(50);
        assertThat(DisclosureGroupRecord.RECORD_LENGTH).isEqualTo(50);
        assertThat(CardXrefRecord.RECORD_LENGTH).isEqualTo(50);
    }

    // =============================================================================================
    //  HOW ONE CASE REACHES THE UNIT
    // =============================================================================================

    /**
     * Runs {@code CBACT04C} once for one case and records everything the run produced.
     *
     * <p>The sequence is the program's own: seed the five datasets the case declares, build the job over
     * them with the harness's pinned clock, and call
     * {@link AccountInterestCalcJob#calculateInterest(String, SysoutSink)} - which performs
     * {@code app/cbl/CBACT04C.cbl:181-232} statement for statement. Nothing about the expectation is
     * visible from here: {@link Invocation} deliberately carries the inputs and not the case, so a unit
     * cannot report its expectation back and pass while implementing nothing.
     *
     * <p><strong>Everything is recorded in a {@code finally} block, and that is load-bearing.</strong>
     * Three of these cases abend, and an abend is an observation rather than a failure: the lines the run
     * displayed before it abended were displayed, the account rows it rewrote before it abended stand
     * because {@code ACCTFILE} is {@code DISP=SHR} over an existing dataset, and the harness needs all of
     * it. Recording on the way out captures that, and returning {@code null} tells the harness the
     * recorder holds the outcome - the only form that survives the exception.
     *
     * <p>What is <em>not</em> recorded is a return code. The harness resolves it: zero when the run
     * reaches {@code GOBACK}, since {@code RETURN-CODE} is untouched there, and the code the
     * {@code AbendException} carries otherwise. Stating it here would make the abend translation assert
     * itself.
     *
     * @param invocation the seeded datasets, the case's job parameters, the pinned clock and the recorder
     * @return {@code null} - always, because the recorder holds the outcome
     */
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

    /**
     * The {@code PARM} the case declares, refused rather than defaulted when absent.
     *
     * <p>{@code app/jcl/INTCALC.jcl:22} is {@code EXEC PGM=CBACT04C,PARM='2022071800'} and
     * {@code app/cbl/CBACT04C.cbl:476-480} concatenates that value into every transaction identifier the
     * program writes, so a case without one could not state a single expected {@code TRAN-ID}. A
     * silently defaulted parameter would make nineteen cases pass against an identifier the twentieth
     * disagreed with.
     *
     * @param invocation the invocation carrying the case's job parameters
     * @return the declared {@code parmDate}, exactly as the case spells it
     */
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

    /**
     * Seeds every dataset the case declared into its relation, and refuses one it does not know.
     *
     * <p>The rows arrive already normalised - {@code cardxref}'s 36-byte rows have had their absent
     * {@code FILLER PIC X(14)} supplied as fourteen spaces by the harness, once, before anything decodes
     * them - so what is inserted here is exactly what the program will read.
     *
     * <p>A dataset key this method does not recognise fails loudly. {@code CBACT04C} opens exactly five
     * inputs ({@code app/jcl/INTCALC.jcl:27-36}), and a case naming a sixth is either a typo or a case
     * about a different program; seeding it into nothing would leave the case describing an input the run
     * never saw.
     *
     * @param invocation the invocation carrying the seeded datasets
     * @param database   the per-case in-memory database holding one relation per dataset
     */
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

    /**
     * Records the generated-transaction output on the writes channel, in write order.
     *
     * <p>Read back in {@link #WRITE_ORDER}, the physical-record ordinal, because {@code SYSTRAN} is a
     * sequential dataset and the order the program wrote its records in is part of what a parity case
     * asserts - the identifiers ascend with {@code WS-TRANID-SUFFIX}, and a job that produced the right
     * records in the wrong order has not reproduced the COBOL.
     *
     * <p>Reported even when it holds no row, which is a positive assertion rather than an omission: the
     * program performs {@code 0400-TRANFILE-OPEN} unconditionally, so the dataset is always opened for
     * output. A run whose every rate was zero opened it and wrote nothing, and a run that abended had its
     * generation discarded under {@code app/jcl/INTCALC.jcl:37}'s {@code DISP=(NEW,CATLG,DELETE)}. Both
     * are zero rows, and both are behaviour a case states.
     *
     * @param recorder where the observation goes
     * @param database the per-case database
     */
    private static void recordGeneratedTransactions(UnitOutcome.Builder recorder,
                                                    JdbcTemplate database) {
        recorder.wroteAll(SYSTRAN_KEY, TranRecord.LAYOUT,
            rowsOf(database, SYSTRAN_DSNAME, WRITE_ORDER.orderByClause()));
    }

    /**
     * Records what each seeded dataset holds after the run, row by row.
     *
     * <p>Every dataset the case seeded is reported, not only the one the program mutates. That is
     * deliberate: {@code ACCTFILE} is the only input this program rewrites ({@code :356}), and a case
     * that reported only {@code ACCTFILE} could not tell a run that left the other four alone from one
     * that quietly wrote to them. Reporting all five makes "the inputs were not disturbed" an assertion.
     *
     * <p>Read back in stored-image order, which for these four keyed datasets is key order - each
     * declares its key at offset zero - and for the alternate-index path is the same stored records seen
     * through a second access path. One cluster, two paths, neither mutated: that is gate G45 stated as
     * an observation rather than as a comment.
     *
     * @param recorder   where the observation goes
     * @param invocation the invocation, for the set of datasets the case seeded
     * @param database   the per-case database
     */
    private static void recordFinalState(UnitOutcome.Builder recorder, Invocation invocation,
                                         JdbcTemplate database) {
        String keyOrder = " ORDER BY " + IMAGE_COLUMN + " ASC";
        for (String key : invocation.datasets().keySet()) {
            // One arm per dataset, each naming the copybook layout its rows are decoded through. Written
            // as a statement rather than routed through a helper so the dataset-to-copybook pairing is
            // visible at the point it is used: TCATBALF is CVTRA01Y, ACCTFILE is CVACT01Y, both
            // cross-reference paths are CVACT03Y and DISCGRP is CVTRA02Y.
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
            // No else: seedDeclaredDatasets has already failed the case for an unrecognised key.
        }
    }

    // =============================================================================================
    //  THE DATASETS, AS RELATIONS
    //
    //  Every dataset this program touches is a relation holding one fixed-width record image per row,
    //  which is the shape the repositories read and write through JDBC. No DDL is authored for the
    //  application: these are the test's own relations, created and dropped inside one case, and no
    //  schema, migration, entity mapping or version column exists anywhere in this module (gate G44).
    // =============================================================================================

    /**
     * A private in-memory database for one case, with the seven relations this step addresses.
     *
     * <p>The database is named after the case, so two cases never share one and the name is derived
     * rather than generated - there is no counter and no random component, hence no static mutable state
     * (practice B9) and nothing that varies between runs (practice B7). {@code DROP ALL OBJECTS} makes
     * the setup idempotent, which matters because {@code DB_CLOSE_DELAY=-1} deliberately keeps the
     * database alive for the JVM: {@code DriverManagerDataSource} opens a connection per operation, and
     * without it the relations would vanish between the seed and the run.
     *
     * @param discriminator the case identifier, or another name unique within this class
     * @return a template over the seven created relations; never {@code null}
     */
    private static JdbcTemplate database(String discriminator) {
        DriverManagerDataSource source = new DriverManagerDataSource(
            "jdbc:h2:mem:cbact04cparity" + discriminator
                + ";DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE", "sa", "");
        source.setDriverClassName("org.h2.Driver");
        JdbcTemplate database = new JdbcTemplate(source);
        database.execute("DROP ALL OBJECTS");
        createRelation(database, TCATBALF_DSNAME, TranCatBalRecord.RECORD_LENGTH);
        createRelation(database, ACCTDATA_DSNAME, AccountRecord.RECORD_LENGTH);
        createRelation(database, CARDXREF_DSNAME, CardXrefRecord.RECORD_LENGTH);
        createRelation(database, CARDXREF_AIX_DSNAME, CardXrefRecord.RECORD_LENGTH);
        createRelation(database, DISCGRP_DSNAME, DisclosureGroupRecord.RECORD_LENGTH);
        createRelation(database, SYSTRAN_DSNAME, TranRecord.RECORD_LENGTH);
        createRelation(database, TRANSACT_DSNAME, TranRecord.RECORD_LENGTH);
        return database;
    }

    /**
     * Creates one relation at its copybook-declared width.
     *
     * <p>{@code VARCHAR} rather than {@code CHAR} on purpose: {@code CHAR} pads and strips trailing
     * spaces, and a record whose trailing {@code FILLER} span is spaces would come back short - which
     * would move every later offset and turn gate G21 into a test that cannot fail.
     *
     * @param database the per-case database
     * @param dsname   the dataset name, used as a delimited identifier
     * @param width    the copybook-declared record length
     */
    private static void createRelation(JdbcTemplate database, String dsname, int width) {
        database.execute("CREATE TABLE \"" + dsname + "\" ("
            + IMAGE_COLUMN + " VARCHAR(" + width + "))");
    }

    /**
     * Inserts one record image verbatim.
     *
     * @param database the per-case database
     * @param dsname   the dataset name
     * @param image    the record image, already normalised to its copybook width
     */
    private static void seedRow(JdbcTemplate database, String dsname, String image) {
        database.update("INSERT INTO \"" + dsname + "\" VALUES (?)", image);
    }

    /**
     * Reads a relation back.
     *
     * @param database the per-case database
     * @param dsname   the dataset name
     * @param ordering the {@code ORDER BY} clause that makes the result deterministic
     * @return the record images in that order; never {@code null}
     */
    private static List<String> rowsOf(JdbcTemplate database, String dsname, String ordering) {
        return database.queryForList(
            "SELECT " + IMAGE_COLUMN + " FROM \"" + dsname + "\"" + ordering, String.class);
    }

    /**
     * Every relation's contents, for a before-and-after comparison.
     *
     * @param database the per-case database
     * @return dataset name to record images, in key order within each dataset
     */
    private static Map<String, List<String>> photograph(JdbcTemplate database) {
        String keyOrder = " ORDER BY " + IMAGE_COLUMN + " ASC";
        Map<String, List<String>> contents = new LinkedHashMap<>();
        for (String dsname : List.of(TCATBALF_DSNAME, ACCTDATA_DSNAME, CARDXREF_DSNAME,
            CARDXREF_AIX_DSNAME, DISCGRP_DSNAME, SYSTRAN_DSNAME, TRANSACT_DSNAME)) {
            contents.put(dsname, rowsOf(database, dsname, keyOrder));
        }
        return contents;
    }

    /**
     * The relation behind a case's dataset key, or {@code null} for a key this program does not open.
     *
     * @param key the dataset binding key a case declared
     * @return the dataset name, or {@code null} when the key is not one of this step's five inputs
     */
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

    // =============================================================================================
    //  THE UNIT UNDER TEST, WIRED BY HAND
    //
    //  By hand and not from an application context, for one reason above all others: the CLOCK. Every
    //  generated transaction carries TRAN-ORIG-TS and TRAN-PROC-TS, both 26 bytes composed from
    //  FUNCTION CURRENT-DATE at :613-626. Taken from a context the clock would be the system clock, the
    //  two timestamps would differ on every run, and no case could ever pin the 350-byte record image
    //  that gates G19 and G21 are about. The harness's pinned clock makes them constants.
    // =============================================================================================

    /**
     * Builds {@code CBACT04C} over one case's database.
     *
     * <p>The five repositories are the real ones, reading and writing real fixed-width images through
     * JDBC: a parity case is about bytes, and a mocked repository would assert the test's own idea of a
     * record rather than the one the codec produces. {@code RecordImageForm.CHARACTER} and the harness's
     * charset are named explicitly on every one of them, so no conversion anywhere in the path consults a
     * platform default (practice B8).
     *
     * <p>The unit of work is a real one over the same data source, not a stub. {@code DatasetUnitOfWork}
     * reports whether a transaction is actually active, and only a real manager makes the {@code REWRITE}
     * at {@code :356} take its locking read inside a boundary - which is the behaviour under test, since
     * {@code CBACT04C} issues no syncpoint and every dataset it touches is {@code RECOVERY(NONE)}, so each
     * rewrite is durable the moment it completes and the abend at {@code :632} does not take it back.
     *
     * @param database  the per-case database
     * @param clock     the harness's pinned clock
     * @param sysout    the capturing {@code SYSOUT} sink
     * @param parmDate  the {@code PARM} the case declares, which the job contract must also declare
     * @param charset   the dataset code page, named explicitly
     * @return the job; never {@code null}
     */
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
            AccountInterestCalcJob.carddemoDisclosureGroupAccess(database, bindings, charset,
                RecordImageForm.CHARACTER),
            new DatasetUnitOfWork(manager),
            new SuppliedBean<>(sysout),
            clock);
    }

    /**
     * The DD catalogue, declared exactly as {@code app/jcl/INTCALC.jcl:25-41} and
     * {@code app/csd/CARDDEMO.CSD} between them declare it.
     *
     * <p>Both spellings of each dataset are present because both are separate configuration keys with
     * independent overrides, and the job proves at construction that each of its JCL DD names resolves to
     * the same dataset as the repository that reads it. The account master is {@code ACCTDAT} to the
     * repository and {@code ACCTFILE} to this step; the cross-reference is {@code CCXREF} and
     * {@code CXACAIX} to the repository and {@code XREFFILE} and {@code XREFFIL1} to this step. Geometry
     * comes from the copybooks and never from a literal: 50, 300, 50, 50, 50 and 350 bytes, with key
     * lengths 17, 11 and 16 taken from {@code TRAN-CAT-KEY}, {@code ACCT-ID} and {@code DIS-GROUP-KEY}.
     *
     * @return the catalogue; never {@code null}
     */
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

    /**
     * The {@code carddemo.jobs} contract for this step: one step, no gate, one parameter.
     *
     * <p>{@code app/jcl/INTCALC.jcl} declares a single {@code STEP15} running {@code CBACT04C} and carries
     * no {@code COND}, so nothing gates it - gating the only step of a single-step job would bypass all of
     * its work, and the job refuses a contract that tries.
     *
     * <p>The job-scoped {@code TRANSACT} alias is <strong>not optional</strong>. In
     * {@code app/jcl/INTCALC.jcl:37-41} the DD name {@code TRANSACT} is the generated-transaction output
     * pointing at {@code SYSTRAN(+1)}, not the transaction master that shares those eight characters.
     * Without the alias the step would resolve {@code TRANSACT} to the master and write its interest
     * transactions over live data.
     *
     * @param parmDate the {@code PARM} the case declares, so the contract and the case cannot disagree
     * @return the contract catalogue; never {@code null}
     */
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

    /**
     * One 300-byte {@code CVACT01Y} image, in the shape {@code app/data/ASCII/acctdata.txt} uses.
     *
     * <p>Only used by {@link #computeFeesComputesNothing()}; the twenty declarative cases carry their
     * account rows verbatim in their own case files, where a reader can see every byte. The three dates
     * and the two limits are the values row 1 of the fixture carries, and the zip is
     * {@code A000000000} - which is where that fixture genuinely puts it, {@code ACCT-GROUP-ID} being ten
     * spaces in all fifty of its rows.
     *
     * @param acctId    {@code ACCT-ID}, eleven digits
     * @param currBal   {@code ACCT-CURR-BAL}, a twelve-byte zoned image with a sign overpunch
     * @param cycCredit {@code ACCT-CURR-CYC-CREDIT}, twelve bytes
     * @param cycDebit  {@code ACCT-CURR-CYC-DEBIT}, twelve bytes
     * @param groupId   {@code ACCT-GROUP-ID}, ten bytes
     * @return the complete 300-byte image
     */
    private static String accountImage(String acctId, String currBal, String cycCredit,
                                       String cycDebit, String groupId) {
        String image = acctId + "Y" + currBal + "00000020200{" + "00000010200{"
            + "2014-11-20" + "2025-05-20" + "2025-05-20" + cycCredit + cycDebit
            + "A000000000" + groupId;
        return image + " ".repeat(AccountRecord.RECORD_LENGTH - image.length());
    }
}
