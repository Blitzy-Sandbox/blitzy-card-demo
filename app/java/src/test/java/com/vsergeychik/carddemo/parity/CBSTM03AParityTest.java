package com.vsergeychik.carddemo.parity;

import com.vsergeychik.carddemo.account.model.AccountRecord;
import com.vsergeychik.carddemo.card.model.CardXrefRecord;
import com.vsergeychik.carddemo.common.AbendException;
import com.vsergeychik.carddemo.common.CobolDecimal;
import com.vsergeychik.carddemo.common.FileStatus;
import com.vsergeychik.carddemo.common.FixedWidthRecord.FieldSpan;
import com.vsergeychik.carddemo.common.FixedWidthRecord.RecordLayout;
import com.vsergeychik.carddemo.common.PhysicalSequence;
import com.vsergeychik.carddemo.common.RecordImageForm;
import com.vsergeychik.carddemo.config.BatchConfig;
import com.vsergeychik.carddemo.config.BatchConfig.JobContract;
import com.vsergeychik.carddemo.config.BatchConfig.JobContracts;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBinding;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBindings;
import com.vsergeychik.carddemo.config.DatasetUnitOfWork;
import com.vsergeychik.carddemo.statement.StatementGenerationJobA;
import com.vsergeychik.carddemo.statement.StatementGenerationJobA.DatasetUtilityPort;
import com.vsergeychik.carddemo.statement.StatementGenerationJobA.SysoutSink;
import com.vsergeychik.carddemo.statement.StatementGenerationJobA.TiotEntry;
import com.vsergeychik.carddemo.statement.StatementGenerationJobA.TiotImage;
import com.vsergeychik.carddemo.statement.StatementGenerationJobA.TiotSource;
import com.vsergeychik.carddemo.statement.StatementGenerationJobB;
import com.vsergeychik.carddemo.statement.StatementGenerationJobB.Response;
import com.vsergeychik.carddemo.statement.StatementGenerationJobB.Session;
import com.vsergeychik.carddemo.statement.StatementHtmlWriter;
import com.vsergeychik.carddemo.statement.StatementTextWriter;
import com.vsergeychik.carddemo.statement.model.Stm03CustomerRecord;
import com.vsergeychik.carddemo.statement.model.TrnxRecord;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mockito;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.job.flow.FlowExecutionStatus;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.Mockito.mock;

/**
 * The twenty-case parity gate for {@code CBSTM03A}, the account-statement generator.
 *
 * <h2>Why this program gets the densest coverage in the plan</h2>
 * <p>{@code app/cbl/CBSTM03A.CBL} is the <strong>only</strong> one of the twenty-eight programs whose
 * {@code GO TO} statements form implicit <em>loops</em> rather than paragraph exits, and the only one
 * that reaches them through {@code ALTER ... TO PROCEED TO} - COBOL's self-modifying branch. Fifteen
 * {@code GO TO}s target {@code 0000-START} (four sites), {@code 1000-MAINLINE},
 * {@code 8100-FILE-OPEN} (four sites), {@code 8100-TRNXFILE-OPEN}, {@code 8500-READTRNX-READ},
 * {@code 8599-EXIT} and {@code 9999-GOBACK}, and between them they build two nested record loops out
 * of nothing but branches. Restructuring that into Java is the single highest-risk translation in the
 * estate, which is why {@code CBSTM03A} shares the densest case allocation with {@code COACTUPC} and
 * {@code CBTRN02C}, and why these cases assert <strong>iteration order and count</strong> rather than
 * final state alone (gate G32).
 *
 * <h2>The baseline is statically derived, never captured (AAP risk R-A, practice B12)</h2>
 * <p>The expected values in {@code src/test/resources/parity/CBSTM03A/case01..case20.json} were
 * <strong>derived by structured reading of the COBOL source</strong> - the seventeen {@code ST-LINE}
 * templates and the thirty {@code HTML-FIXED-LN} literals at {@code app/cbl/CBSTM03A.CBL:L85-L223},
 * the {@code STRING} statements at {@code L462-L481} and {@code L560-L716}, the copybook byte layouts
 * of {@code app/cpy/COSTM01.CPY}, {@code app/cpy/CVACT03Y.cpy}, {@code app/cpy/CUSTREC.cpy} and
 * {@code app/cpy/CVACT01Y.cpy}, the DD and {@code COND} contracts of {@code app/jcl/CREASTMT.JCL},
 * and the real fixture rows of {@code app/data/ASCII}. They were <strong>not</strong> captured from a
 * live COBOL execution, because no such execution is possible here.
 *
 * <p>{@code CBSTM03A} is in fact one of the programs that proves the point. The only COBOL compiler
 * available refuses it outright at {@code app/cpy/CUSTREC.cpy:6} with "unbalanced parentheses" and
 * "invalid PICTURE character '2'", because seventeen lines of that copybook - lines 6 through 22 -
 * carry literal TAB characters in the source margin, which no fixed-format COBOL reader can align.
 * Even setting that aside, its four input files are {@code ORGANIZATION INDEXED} and that build
 * reports {@code indexed file handler : disabled}, and its abend needs a Language Environment
 * {@code CEE3ABD} that does not exist there. The deviation is a change of <em>provenance only</em> -
 * twenty cases per program, field-for-field diffing and a diff count of zero per module all stand -
 * and it is recorded rather than absorbed (practice B12).
 *
 * <p>Because a statically derived expectation can encode a misreading, the derivation deliberately
 * avoids the one shortcut that would make this suite worthless: it never asks the Java classes what
 * they produce. Every 80-byte and 100-byte image in the case files was composed from the COBOL
 * literals, the copybook offsets and the shipped fixtures alone, so a passing comparison is
 * independent evidence rather than the implementation compared with itself.
 *
 * <h2>The TIOT walk is substituted, and the substitution is stated rather than hidden</h2>
 * <p>{@code L266-L291} walks real z/OS control blocks: it sets the address of the {@code PSA} from a
 * null pointer, follows {@code TCB-POINT} to the {@code TCB}, follows {@code TIOT-POINT} to the
 * {@code TIOT}, and then bumps a pointer through the DD chain twenty bytes at a time until a
 * low-values terminator. None of that has a JVM counterpart and none of it is invented here.
 * {@link StatementGenerationJobA} substitutes a {@link TiotSource}, and these cases assert the
 * <strong>lines the production class emits</strong> from it: {@code 'Running JCL : '} with the eight-byte
 * job and step names, {@code 'DD Names from TIOT: '}, one entry line per DD of
 * {@code app/jcl/CREASTMT.JCL}'s {@value StatementGenerationJobA#STEP_040} step, and then the
 * post-loop entry. The two null-UCB literals are <em>byte-different</em> - {@code ' --  null UCB'}
 * inside the loop at {@code L281} and {@code ' -- null  UCB'} after it at {@code L290} - and case 20
 * pins both, because a translation that normalised them would look correct and read wrong.
 *
 * <h2>What the twenty cases pin</h2>
 * <ul>
 *   <li><strong>Both output widths, in the same run.</strong> {@code STMTFILE} is
 *       {@value StatementTextWriter#RECORD_LENGTH} bytes ({@code 01 FD-STMTFILE-REC PIC X(80)} at
 *       {@code L45}, {@code LRECL=80} at {@code app/jcl/CREASTMT.JCL:L89}) and {@code HTMLFILE} is
 *       {@value StatementHtmlWriter#RECORD_LENGTH} ({@code PIC X(100)} at {@code L47},
 *       {@code LRECL=100} at {@code :L94}). The {@code STEP030} pre-delete declares the HTML file at
 *       {@code LRECL=80}; that is the <em>losing</em> declaration and the creating step wins (gates
 *       G19 and G20, risk R-G).</li>
 *   <li><strong>Write order, including the duplicates.</strong> {@code ST-LINE5} is written twice
 *       ({@code L492}, {@code L494}) and {@code ST-LINE12} three times ({@code L500}, {@code L502},
 *       {@code L435}), and the HTML footer is eight records ({@code L439-L454}). Nothing is
 *       de-duplicated and nothing is reordered.</li>
 *   <li><strong>The 1-based 51x10 table.</strong> Case 3 pins {@code WS-CARD-NUM(1)} and
 *       {@code WS-TRAN-NUM(1,1)}; case 4 pins {@code WS-CARD-NUM(51)} and {@code WS-TRAN-NUM(51,1)}
 *       through {@code (51,10)} - both {@code OCCURS} bounds at once (gate G33).</li>
 *   <li><strong>Every byte of every record.</strong> {@link FieldDiffer} reports an observed row no
 *       expectation addresses, and an expectation that leaves any byte of a record unasserted, as
 *       differences in their own right - so each case pins the complete image of each of its records
 *       and thereby proves that every {@code FILLER} literal in the sixteen line templates was
 *       emitted (gate G21).</li>
 *   <li><strong>The amount masks as exact strings.</strong> {@code PIC 9(9).99-} for
 *       {@code ST-CURR-BAL} and {@code PIC Z(9).99-} for {@code ST-TRANAMT} and
 *       {@code ST-TOTAL-TRAMT}, thirteen bytes each with a trailing sign byte. Case 9 pins the
 *       high-order truncation of {@code ACCT-CURR-BAL}'s ten integer digits into nine, case 8 a
 *       negative amount, and case 19 a zero. {@link StatementHtmlWriter} receives these
 *       already-edited images and never re-applies a mask, which is what {@code L622} and
 *       {@code L712} send.</li>
 *   <li><strong>Every file-status arm of every guard.</strong> {@code '00'} throughout,
 *       {@code '04'} accepted at an open by {@code IF WS-M03B-RC = '00' OR '04'} ({@code L736}),
 *       {@code '10'} benign at the cross-reference read ({@code L356}) but <em>fatal</em> at the
 *       customer read ({@code L379-L386}), and {@code WHEN OTHER} at an open, a sequential read, two
 *       keyed reads and a close (gate G47).</li>
 *   <li><strong>The unique abend.</strong> {@code CALL 'CEE3ABD'} at {@code L923} passes neither an
 *       {@code ABCODE} nor a {@code TIMING} - the only such site in the estate - which
 *       {@link StructuralGates#theAbendCarriesNeitherAnAbendCodeNorATiming()} asserts directly
 *       (gate G35).</li>
 *   <li><strong>The {@code COND=(0,NE)} gate.</strong> Case 17 has a preceding step return 4, so the
 *       statement step is bypassed: no walk, no open, no record, and the job reports 4.</li>
 * </ul>
 *
 * <h2>How the unit is reached</h2>
 * <p>{@link StatementGenerationJobA#printAccountStatements(SysoutSink)} is called directly. There is
 * no {@code JobLauncher} and no HTTP layer in the path (gate G51): the program is a single pass inside
 * one tasklet invocation, and a launcher would only add ways for a parity assertion to fail for a
 * reason that has nothing to do with parity. Its collaborators are the <em>real</em>
 * {@link StatementGenerationJobB} over a per-case in-memory relation - so all four inputs are reached
 * through the 1040-byte {@code LK-M03B-AREA} contract its own twenty cases already gate - and the
 * <em>real</em> {@link StatementTextWriter} and {@link StatementHtmlWriter}, which own every byte of
 * the two record widths and both amount masks. A mocked writer would turn the width and mask
 * assertions into assertions about the mock.
 *
 * <p>Every collaborator, every counter and every collecting list is created per invocation. This class
 * holds no mutable static field of its own (practice B9, gate G53), so no case can leak into the next
 * and two clones can run in parallel without sharing a byte.
 *
 * <h2>The standing practices this suite is held to</h2>
 * <p>{@code review_rules} reports that <strong>no user rules were provided</strong> for this project,
 * which is not permission to lower the bar: enterprise-standard practice governs instead, and the
 * relevant ones are named here so a reader can check them rather than take them on trust.
 * <ul>
 *   <li><strong>B3, reference inputs are immutable.</strong> Not a byte of {@code app/cbl},
 *       {@code app/cpy} or {@code app/jcl} was changed to make anything here pass. They are read as the
 *       parity contract, and the fixtures are read from {@code src/test/resources/fixtures/}, which are
 *       byte-for-byte copies of {@code app/data/ASCII}.</li>
 *   <li><strong>B6, the generated bytes are the contract.</strong> No HTML is escaped, trimmed,
 *       reindented or otherwise improved on its way into an expectation. {@code HTML-L08} carries two
 *       consecutive spaces after {@code <table}, several elements are unclosed, and the whole document
 *       is padded to 100 bytes a line - all of it preserved exactly.</li>
 *   <li><strong>B7, deterministic and non-interactive.</strong> Nothing here reads a wall clock, a
 *       locale, a platform charset, a directory listing or an environment variable, so two runs of the
 *       same case produce identical bytes. Each case gets its own in-memory database, named with a
 *       fresh {@link UUID}, so the suite is order independent and safe to run in parallel.</li>
 *   <li><strong>B10, the tests ship with the implementation.</strong> These twenty cases were authored
 *       against the COBOL rather than against a completed translation, which is the whole reason a
 *       difference here localises to a line of {@code app/cbl/CBSTM03A.CBL} instead of to "somewhere in
 *       the statement job".</li>
 *   <li><strong>B11, hand-written and reviewable.</strong> Every expected image was composed from the
 *       copybook offsets and the COBOL literals rather than produced by a copybook parser, so each of
 *       them is diffable against the source by eye.</li>
 * </ul>
 */
@DisplayName("CBSTM03A parity - the statement generator's loops, widths, masks and write order")
class CBSTM03AParityTest {

    // =============================================================================================
    //  IDENTITY
    // =============================================================================================

    /**
     * The program these cases gate, matching the {@code parity/CBSTM03A/} resource directory.
     *
     * <p>The source file is {@code app/cbl/CBSTM03A.CBL} with an <strong>upper-case</strong>
     * extension - it and {@code CBSTM03B.CBL} are the only two of the twenty-eight that are - but the
     * program name itself is upper case either way, so nothing about the casing of the file name
     * reaches this constant or the directory it names.
     */
    private static final String PROGRAM = StatementGenerationJobA.PROGRAM_ID;

    /** The code page of the nine ASCII fixtures, named explicitly and never defaulted (practice B8). */
    private static final Charset ASCII = StandardCharsets.US_ASCII;

    /**
     * How this deployment's driver presents a record image. {@link RecordImageForm#CHARACTER} because
     * {@link #ASCII} is single byte, which is what makes a character round trip exact.
     */
    private static final RecordImageForm IMAGE_FORM = RecordImageForm.CHARACTER;

    /**
     * H2's own row-identifier pseudo-column, which is what {@code application-test.yml} configures for
     * {@value PhysicalSequence#EXPRESSION_PROPERTY}. It is never read by the statement pass - all four
     * inputs are keyed or ascending browses - but the job's utility steps take it, so it is supplied
     * rather than left to a default that does not exist.
     */
    private static final PhysicalSequence ORDINAL = PhysicalSequence.of("_ROWID_");

    /** The single column of a one-column record-image relation. */
    private static final String RECORD_IMAGE_COLUMN = "REC";

    // ---------------------------------------------------------------------------------------------
    //  Test dataset names. Deliberately NOT the production high-level qualifier that
    //  app/csd/CARDDEMO.CSD and app/jcl/CREASTMT.JCL carry (gate G46): a parity case addresses a
    //  dataset through its binding key, and the name behind that key belongs to configuration. A
    //  TEST. qualifier keeps that visible, and keeps a production dataset name out of Java source.
    // ---------------------------------------------------------------------------------------------

    /** {@code TRNXFILE} - the sorted work KSDS {@code app/jcl/CREASTMT.JCL:L83} binds. */
    private static final String TRNXFILE_DSNAME = "TEST.M2.CARDDEMO.TRXFL.VSAM.KSDS";

    /** {@code XREFFILE} - the card cross-reference, {@code :L84}. */
    private static final String XREFFILE_DSNAME = "TEST.M2.CARDDEMO.CARDXREF.VSAM.KSDS";

    /** {@code ACCTFILE} - the account master, {@code :L85}. */
    private static final String ACCTFILE_DSNAME = "TEST.M2.CARDDEMO.ACCTDATA.VSAM.KSDS";

    /** {@code CUSTFILE} - the customer master, {@code :L86}. */
    private static final String CUSTFILE_DSNAME = "TEST.M2.CARDDEMO.CUSTDATA.VSAM.KSDS";

    /** {@code STMTFILE} - the plain-text statement, {@code :L87-L91}. */
    private static final String STMTFILE_DSNAME = "TEST.M2.CARDDEMO.STATEMNT.PS";

    /** {@code HTMLFILE} - the HTML statement, {@code :L92-L96}. */
    private static final String HTMLFILE_DSNAME = "TEST.M2.CARDDEMO.STATEMNT.HTML";

    // ---------------------------------------------------------------------------------------------
    //  The two output record layouts.
    // ---------------------------------------------------------------------------------------------

    /**
     * {@code 01 FD-STMTFILE-REC PIC X(80)} ({@code app/cbl/CBSTM03A.CBL:L45}).
     *
     * <p>One span over the whole record, and that is the honest layout rather than a convenience: the
     * record area receives a different group on every {@code WRITE} - {@code ST-LINE0} is three
     * {@code FILLER}s, {@code ST-LINE8} is a literal, an edited amount and two {@code FILLER}s - so
     * there is no single sub-field structure to declare. The case files pin the complete image, which
     * asserts every one of those groups byte for byte and, by asserting the total width, proves that
     * every {@code FILLER} was emitted (gate G21).
     */
    private static final RecordLayout STMT_LAYOUT = RecordLayout.of(
        StatementTextWriter.RECORD_LENGTH,
        FieldSpan.alphanumeric("FD-STMTFILE-REC", 0, StatementTextWriter.RECORD_LENGTH));

    /**
     * {@code 01 FD-HTMLFILE-REC PIC X(100)} ({@code app/cbl/CBSTM03A.CBL:L47}), for the same reason:
     * this record carries a 100-byte fixed literal, a 59-byte {@code HTML-L11} group and four
     * {@code STRING}-composed lines, at one width.
     */
    private static final RecordLayout HTML_LAYOUT = RecordLayout.of(
        StatementHtmlWriter.RECORD_LENGTH,
        FieldSpan.alphanumeric("FD-HTMLFILE-REC", 0, StatementHtmlWriter.RECORD_LENGTH));

    /**
     * The step whose non-zero return code case 17 uses to close the {@code COND=(0,NE)} gate.
     *
     * <p>{@value StatementGenerationJobA#STEP_010} is the {@code SORT}, and it is the honest choice:
     * it is the last <em>ungated</em> step of {@code app/jcl/CREASTMT.JCL}, so it is exactly the step
     * whose failure the three gates downstream of it exist to react to.
     */
    private static final String BYPASSING_STEP = StatementGenerationJobA.STEP_010;

    /**
     * The return code case 17's preceding step leaves behind, closing the {@code COND=(0,NE)} gate.
     *
     * <p>Four rather than eight or twelve, and the choice carries a point: four is z/OS's warning code,
     * the one a {@code SORT} really does return for a condition it survived, so the bypass being tested
     * is the one that happens in practice rather than a catastrophic failure that would be obvious
     * anyway. {@code COND=(0,NE)} bypasses on <em>anything</em> other than zero, four included.
     */
    private static final int BYPASSING_RETURN_CODE = 4;

    // =============================================================================================
    //  THE GATE
    // =============================================================================================

    /**
     * The program's twenty declarative cases, in {@code case01} through {@code case20} order.
     *
     * <p>{@link ParityHarness#casesOf(String)} is the whole guard and it is a strict one: it refuses a
     * directory missing any of the twenty, naming each absentee, and equally refuses one holding
     * anything the twenty-case enumeration would never read - a {@code case21.json}, a
     * {@code Case07.json}, a {@code case07.json.bak}. Both halves matter, because the count <em>is</em>
     * part of the gate: "the diff count is zero across all twenty cases" is satisfied vacuously by a
     * set of four.
     *
     * @return the twenty cases, never fewer and never more
     */
    static List<ParityCase> cases() {
        return ParityHarness.casesOf(PROGRAM);
    }

    /**
     * Runs one case against {@link StatementGenerationJobA} and requires a diff count of zero.
     *
     * <p>The differ compares in <strong>both</strong> directions - every expectation against what the
     * run produced, and every observation against what the case expected - so a record the case never
     * pinned is a difference in its own right and cannot pass unnoticed. That is what makes a
     * per-record expectation the whole of this program's write order rather than a sample of it. On a
     * failure the entire rendered report is surfaced, never a summary: it localises each difference to
     * the record and byte offset that carries it, which for a ninety-five-record run is the only form
     * in which a mismatch is actionable.
     *
     * @param parityCase the case to run; supplied by {@link #cases()}
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    @DisplayName("the case's diff count is zero")
    void theCaseProducesNoDifference(ParityCase parityCase) {
        ParityHarness harness = ParityHarness.usAscii();

        FieldDiffer.DiffResult result = harness.judge(parityCase, ParityCase.UnitKind.BATCH_JOB,
            invocation -> drive(parityCase, invocation));

        assertThat(result.count())
            .as("the parity gate for %s/%s. A module is not complete until its diff count is zero "
                    + "across all twenty of its cases, so this is the assertion the gate is stated "
                    + "in terms of. Every difference found, rendered in full:%n%s",
                PROGRAM, parityCase.caseId(), result.render())
            .isZero();
    }

    /**
     * Guards the case set itself, independently of {@link #cases()} being called by the runner.
     *
     * <p>A parameterized method whose source resolved to nothing is reported by JUnit, but a
     * <em>short</em> source is not, so the count is asserted here as well - and with it the identity of
     * every case, since twenty files that all declared {@code case01} would satisfy a count. The unit
     * kind is asserted too: a case declaring anything other than {@code BATCH_JOB} would be refused by
     * the harness at run time, and finding that out here names the file instead.
     */
    @Test
    @DisplayName("exactly twenty cases exist, named case01 through case20, all declaring CBSTM03A")
    void exactlyTwentyCasesAreDeclared() {
        List<ParityCase> loaded = cases();

        assertThat(loaded)
            .as("the twenty cases under parity/%s/. Twenty is the declared volume per program - 560 "
                + "across the twenty-eight - and a short set is not a smaller gate but a gate that "
                + "passes without asking the questions.", PROGRAM)
            .hasSize(ParityHarness.CASES_PER_PROGRAM);

        List<String> identifiers = new ArrayList<>(loaded.size());
        for (ParityCase parityCase : loaded) {
            assertThat(parityCase.program())
                .as("every case under parity/%s/ must declare that program, or it would be judged "
                    + "against another program's expectations", PROGRAM)
                .isEqualTo(PROGRAM);
            assertThat(parityCase.unitKind())
                .as("case %s must declare BATCH_JOB: %s is invoked by EXEC PGM= at "
                        + "app/jcl/CREASTMT.JCL:L79, so it is a batch job and not a service, a "
                        + "component or a controller", parityCase.caseId(), PROGRAM)
                .isEqualTo(ParityCase.UnitKind.BATCH_JOB);
            assertThat(parityCase.jobParameters())
                .as("case %s must declare no job parameter: no step of app/jcl/CREASTMT.JCL carries "
                        + "a PARM, and the only PARM in this estate belongs to the interest "
                        + "calculator", parityCase.caseId())
                .isEmpty();
            identifiers.add(parityCase.caseId());
        }

        List<String> expected = new ArrayList<>(ParityHarness.CASES_PER_PROGRAM);
        for (int ordinal = 1; ordinal <= ParityHarness.CASES_PER_PROGRAM; ordinal++) {
            expected.add(ParityHarness.caseId(ordinal));
        }
        assertThat(identifiers)
            .as("the case identifiers under parity/%s/, in load order. They are compared as an "
                + "ordered list rather than a set so a duplicate cannot hide behind a matching "
                + "count.", PROGRAM)
            .isEqualTo(expected);
    }

    /**
     * Guards that every case is paired with a scenario, which is the half {@link ParityCase} cannot
     * carry.
     *
     * <p>A case file states its seed and its expectations; it does not state which of the ten
     * subroutine call sites is to report an unusual file status, nor whether the {@code COND} gate is
     * closed, because neither is an input to the program - both are properties of the environment it
     * runs in. Those live in {@link #scenario(String)}, and a case whose scenario is missing would
     * fail with a lookup error rather than a parity difference, which is a considerably worse
     * diagnostic than this.
     */
    @Test
    @DisplayName("every case has a scenario, and every declared scenario belongs to a case")
    void everyCaseHasAScenario() {
        for (ParityCase parityCase : cases()) {
            assertThat(scenario(parityCase.caseId()))
                .as("case %s must be paired with a scenario in scenario(String), next to the "
                    + "nineteen others, so the case and the environment it asserts stay side by "
                    + "side", parityCase.caseId())
                .isNotNull();
        }
    }

    // =============================================================================================
    //  THE SCENARIO - what a case file cannot carry
    // =============================================================================================

    /**
     * The ten {@code CALL 'CBSTM03B'} sites a scenario may substitute a file status at.
     *
     * <p>Ten, not thirteen: {@code CBSTM03A} calls the subroutine thirteen times, but three of those
     * calls are the {@code TRNXFILE} sequential reads of the table-loading loop, which run a variable
     * number of times and whose status is what <em>ends</em> the loop rather than something a scenario
     * chooses. The remaining ten are addressable individually, and each is named for the DD and the
     * operation so the constant cannot be misread as naming a paragraph.
     *
     * <p>A closed type rather than a pair of strings, because the whole purpose of a substitution is to
     * reach a guard arm the seeded data cannot reach. A DD-and-operation pair spelt as free text is one
     * transposed letter away from matching no call site at all, at which point the arm stays unreached
     * and the case passes having asserted the opposite of what it says.
     */
    private enum CallSite {

        /** No substitution: every call reports whatever the real subroutine reports. */
        NONE,

        /** {@code 8100-TRNXFILE-OPEN}'s open, {@code app/cbl/CBSTM03A.CBL:L734}, guarded at {@code L736}. */
        OPEN_TRNXFILE,

        /** {@code 1000-XREFFILE-GET-NEXT}'s sequential read, {@code L351}, guarded at {@code L353}. */
        READ_XREFFILE,

        /** {@code 2000-CUSTFILE-GET}'s keyed read, {@code L377}, guarded at {@code L379}. */
        READ_CUSTFILE,

        /** {@code 3000-ACCTFILE-GET}'s keyed read, {@code L401}, guarded at {@code L403}. */
        READ_ACCTFILE,

        /** {@code 9100-TRNXFILE-CLOSE}'s close, {@code L860}, guarded at {@code L862}. */
        CLOSE_TRNXFILE
    }

    /**
     * The environment one case runs in: which call site reports what, whether the {@code COND} gate is
     * closed, and which DD names have no unit control block behind them.
     *
     * @param site the call site to substitute a status at, or {@link CallSite#NONE}
     * @param status the two-character {@code FILE STATUS} that site is to report; ignored for
     *     {@link CallSite#NONE}
     * @param bypassExitCode the return code a preceding JCL step left behind, or {@code 0} when every
     *     preceding step returned zero and the statement step therefore runs
     * @param nullUcbDds the DD names whose {@code TIOT} entry reports no unit control block
     */
    private record Scenario(CallSite site, String status, int bypassExitCode,
                            List<String> nullUcbDds) {

        /** The ordinary environment: every call succeeds and every DD is allocated. */
        private static Scenario normal() {
            return new Scenario(CallSite.NONE, null, 0, List.of());
        }

        /**
         * @param site the call site to substitute at
         * @param status the status it is to report
         */
        private static Scenario reporting(CallSite site, String status) {
            return new Scenario(site, status, 0, List.of());
        }

        /** @param exitCode the non-zero return code a preceding step left behind */
        private static Scenario bypassedAfter(int exitCode) {
            return new Scenario(CallSite.NONE, null, exitCode, List.of());
        }

        /** @param ddNames the DD names with no unit control block */
        private static Scenario withNullUcb(String... ddNames) {
            return new Scenario(CallSite.NONE, null, 0, List.of(ddNames));
        }
    }

    /**
     * The scenario each case runs under, kept beside the twenty case files it belongs to.
     *
     * <p>The thirteen cases that name {@link CallSite#NONE} with no bypass and no null UCB are not
     * uninteresting - they are the ones whose whole assertion is the record sequence, and they differ
     * from each other only in their seed, which is where a case file belongs. The seven that do name
     * something are the guard-arm and gating cases, and each states its one deviation and nothing else.
     *
     * @param caseId the case whose scenario to resolve
     * @return that case's scenario; never {@code null}
     * @throws IllegalArgumentException if the case has no declared scenario
     */
    private static Scenario scenario(String caseId) {
        return switch (caseId) {
            // The record-sequence cases: seed only, no environmental deviation.
            case "case01", "case02", "case03", "case04", "case05", "case06", "case07", "case08",
                 "case09", "case10", "case18", "case19" -> Scenario.normal();

            // '04' is ACCEPTED by IF WS-M03B-RC = '00' OR '04' at L736 - a run that completes.
            case "case11" -> Scenario.reporting(CallSite.OPEN_TRNXFILE,
                FileStatus.RECORD_LENGTH_CONFLICT);

            // The ST-CURR-BAL high-order truncation case needs a run that COMPLETES: its subject is
            // MOVE ACCT-CURR-BAL TO ST-CURR-BAL at L484, so it asserts four whole statements and no
            // environmental deviation at all. The TRNXFILE open's ELSE arm it used to reach stays
            // covered by StatementGenerationJobATest.TheAbendPath.theFourOpenGuards, which forces a
            // bad status at each of the four opens in turn (gates G47, G35).
            case "case12" -> Scenario.normal();

            // The four WHEN OTHER / ELSE arms, one per remaining guarded call site (gate G47).
            case "case13" -> Scenario.reporting(CallSite.READ_XREFFILE, "37");
            // '10' at the CUSTFILE keyed read is FATAL: L379-L386 has no WHEN '10' arm.
            case "case14" -> Scenario.reporting(CallSite.READ_CUSTFILE, FileStatus.END_OF_FILE);
            case "case15" -> Scenario.reporting(CallSite.READ_ACCTFILE, "23");
            case "case16" -> Scenario.reporting(CallSite.CLOSE_TRNXFILE, "38");

            // COND=(0,NE) on STEP040, app/jcl/CREASTMT.JCL:L79.
            case "case17" -> Scenario.bypassedAfter(BYPASSING_RETURN_CODE);

            // The only case whose TIOT image reports a DD with no unit control block.
            case "case20" -> Scenario.withNullUcb(StatementGenerationJobA.HTMLFILE_DD);

            default -> throw new IllegalArgumentException("No scenario is declared for case '"
                + caseId + "' of " + PROGRAM + ". Every case file must be paired with the environment "
                + "it runs in, because ParityCase carries inputs and expectations but neither a "
                + "substituted file status nor a preceding step's return code - jobParameters cannot "
                + "carry them either, since no step of app/jcl/CREASTMT.JCL declares a PARM. Add the "
                + "scenario here, next to the nineteen others.");
        };
    }

    // =============================================================================================
    //  THE ADAPTER - one run of CBSTM03A, recorded as it happens
    // =============================================================================================

    /**
     * Builds the job over a per-case in-memory relation and runs it once.
     *
     * <p><strong>Everything is recorded eagerly, as it is emitted.</strong> That is not a style choice:
     * five of the twenty cases end in an {@link AbendException}, and the harness builds the fingerprint
     * from whatever the recorder holds at the moment the exception arrives. A displayed line collected
     * into a list and handed over afterwards would simply not exist on those five, and neither would a
     * written record. So the {@code SYSOUT} sink records straight into the recorder, and so does each
     * of the two output sinks - and the sinks' {@code open()} registers the dataset before the first
     * record, which is what makes "the program opened both files and wrote nothing to either" an
     * assertion rather than a silence.
     *
     * <p>The final state is the one thing that cannot be recorded eagerly, because it is the
     * <em>consequence</em> of a discard: {@code app/jcl/CREASTMT.JCL:L87-L96} declares both outputs
     * {@code DISP=(NEW,CATLG,DELETE)}, so a run that does not reach {@code 9999-GOBACK} leaves nothing
     * behind however many records it wrote. It is therefore recorded in a {@code finally}, before the
     * abend leaves this method, and only for the cases that declare a {@code FINAL_STATE} expectation -
     * asked of the case itself rather than duplicated in a flag here, so the two cannot drift.
     *
     * @param parityCase the case being run; supplies the seed and states which channels it expects
     * @param invocation the seeded datasets, the codec and the recorder
     * @return the outcome the recorder holds
     */
    private ParityHarness.UnitOutcome drive(ParityCase parityCase,
                                            ParityHarness.Invocation invocation) {
        Scenario scenario = scenario(parityCase.caseId());
        ParityHarness.UnitOutcome.Builder recorder = invocation.recorder();

        if (scenario.bypassExitCode() != 0
            && bypassed(scaffolding(bindings()), scenario.bypassExitCode())) {
            // COND=(0,NE): the step is flushed, so the program does not run and produces nothing. The
            // job still reports the highest executed condition code, which is the code that closed the
            // gate - a bypass is not a success and must not be reported as zero.
            return recorder.returnCode(scenario.bypassExitCode()).build();
        }

        JobFixture assembled = fixture(invocation.datasets(), scenario, recorder);

        boolean pinsFinalState = parityCase.expectedDatasets().stream()
            .anyMatch(expected -> expected.channel() == ParityCase.DatasetChannel.FINAL_STATE);
        try {
            // Every DISPLAY goes straight into the fingerprint rather than into a list drained
            // afterwards: five of the twenty cases end in an abend, and the harness builds from
            // whatever the recorder holds at that moment.
            assembled.job().printAccountStatements(recorder::display);
        } finally {
            if (pinsFinalState) {
                recorder.finalState(StatementGenerationJobA.STMTFILE_DD, STMT_LAYOUT,
                    assembled.textSink().retained());
                recorder.finalState(StatementGenerationJobA.HTMLFILE_DD, HTML_LAYOUT,
                    assembled.htmlSink().retained());
            }
        }
        return recorder.build();
    }

    /**
     * One runnable job with both output sinks captured, built per invocation.
     *
     * @param job the subject
     * @param textSink the {@code STMTFILE} sink
     * @param htmlSink the {@code HTMLFILE} sink
     */
    private record JobFixture(StatementGenerationJobA job, TextSink textSink, HtmlSink htmlSink) {
    }

    /**
     * Assembles the job over a given seed and scenario, with every seam captured.
     *
     * <p>Shared by the twenty cases and by the structural gates that need a real run, so both reach the
     * program the same way. The two writers are <em>real</em> and only their no-argument open is
     * redirected: they own the two record widths, both thirteen-byte amount masks and every padding
     * decision, so a mocked writer would turn the width and mask assertions into assertions about the
     * mock. {@link StatementGenerationJobA} opens its outputs with the no-argument
     * {@link StatementTextWriter#openOutput()} and {@link StatementHtmlWriter#open()}, which is why the
     * redirection is where the seam has to be.
     *
     * @param seeded the datasets to place behind the subroutine, keyed by binding key
     * @param scenario the environment: any substituted status and any DD with no unit control block
     * @param recorder where every displayed line and every written record is recorded as it happens
     * @return the assembled job and its two sinks
     */
    private JobFixture fixture(Map<String, ParityHarness.SeededDataset> seeded, Scenario scenario,
                               ParityHarness.UnitOutcome.Builder recorder) {
        DatasetBindings catalogue = bindings();
        JdbcTemplate template = seededTemplate(seeded);
        StatementTextWriter realText =
            new StatementTextWriter(template, ASCII, catalogue, IMAGE_FORM);
        StatementHtmlWriter realHtml =
            new StatementHtmlWriter(template, ASCII, catalogue, IMAGE_FORM);
        TextSink textSink = new TextSink(recorder);
        HtmlSink htmlSink = new HtmlSink(recorder);

        // A FRESH handle per open rather than one handle returned repeatedly: the COBOL opens once at
        // L293 and closes once at L339, and a closed handle refuses further writes exactly as the real
        // writer does.
        StatementTextWriter textWriter = Mockito.spy(realText);
        Mockito.doAnswer(stubbed -> realText.openOutput(textSink)).when(textWriter).openOutput();
        StatementHtmlWriter htmlWriter = Mockito.spy(realHtml);
        Mockito.doAnswer(stubbed -> realHtml.open(htmlSink)).when(htmlWriter).open();

        StatementGenerationJobA job = new StatementGenerationJobA(
            new SubstitutingSubroutine(template, catalogue, scenario),
            textWriter, htmlWriter, scaffolding(catalogue), ASCII, template, IMAGE_FORM, ORDINAL,
            unitOfWork(template), new SuppliedBean<SysoutSink>(recorder::display),
            new SuppliedBean<>(tiotSource(scenario)),
            new SuppliedBean<DatasetUtilityPort>(null));
        return new JobFixture(job, textSink, htmlSink);
    }

    /**
     * Evaluates {@code COND=(0,NE)} the way the job's own flow does, against a hand-built execution.
     *
     * <p>The decision is <strong>consumed, not asserted</strong>. If the shared decider were to answer
     * {@link BatchConfig#PROCEED} for a preceding step that returned four, this method returns
     * {@code false}, the program runs, and the case fails on the ninety-five records and nine displayed
     * lines it then produces against the nothing it expects. That is a considerably better failure than
     * an assertion here would give, because it is the difference the gate exists to catch rather than a
     * restatement of the gating rule.
     *
     * @param scaffolding the batch seam holding the shared gating policy
     * @param exitCode the non-zero return code a preceding step left behind
     * @return whether the statement step is bypassed
     */
    private static boolean bypassed(BatchConfig scaffolding, int exitCode) {
        JobExecution execution = new JobExecution(1L);
        StepExecution preceding = execution.createStepExecution(BYPASSING_STEP);
        preceding.setExitStatus(new ExitStatus(Integer.toString(exitCode)));
        FlowExecutionStatus decision =
            scaffolding.precedingExitCodeZeroDecider().decide(execution, preceding);
        return BatchConfig.SKIP.equals(decision);
    }

    // =============================================================================================
    //  STRUCTURAL GATES - the properties a per-record comparison cannot reach
    // =============================================================================================

    /**
     * The facts the twenty record comparisons rest on but cannot themselves assert.
     *
     * <p>A diff count of zero says the run produced the bytes the case expected. It cannot say that the
     * expectation was stated at the right <em>width</em>, that the abend carried no {@code ABCODE}, that
     * a sixth {@code EVALUATE} arm exists and is unreachable, or that a declared-but-never-written
     * literal survived. Each of those is a property of the translation rather than of one run, so each
     * is asserted here, once.
     */
    @Nested
    @DisplayName("Structural gates")
    class StructuralGates {

        /**
         * The five steps of {@code app/jcl/CREASTMT.JCL}, their programs and their three
         * {@code COND=(0,NE)} gates.
         *
         * <p>Gating the wrong count of steps is the easy mistake in this job and it is silent: four
         * gated steps bypass work the mainframe performs, two run work it bypasses. The first two steps
         * carry no {@code COND} at all - {@code DELDEF01} at {@code :L22} and {@code STEP010} at
         * {@code :L44} - and the last three carry one each, at {@code :L56}, {@code :L66} and
         * {@code :L79}.
         */
        @Test
        @DisplayName("the job declares DELDEF01, STEP010, STEP020, STEP030 and STEP040, gating the last three")
        void theJobDeclaresTheFiveStepsOfCreastmtWithThreeCondGates() {
            assertThat(StatementGenerationJobA.STEP_NAMES)
                .as("the five step names of app/jcl/CREASTMT.JCL, in declaration order")
                .containsExactly(StatementGenerationJobA.STEP_DELDEF01,
                    StatementGenerationJobA.STEP_010, StatementGenerationJobA.STEP_020,
                    StatementGenerationJobA.STEP_030, StatementGenerationJobA.STEP_040);
            assertThat(StatementGenerationJobA.GATED_STEP_NAMES)
                .as("COND=(0,NE) appears on exactly STEP020, STEP030 and STEP040 and on no other "
                    + "step of app/jcl/CREASTMT.JCL")
                .containsExactly(StatementGenerationJobA.STEP_020,
                    StatementGenerationJobA.STEP_030, StatementGenerationJobA.STEP_040);
            assertThat(StatementGenerationJobA.REQUIRED_STEPS)
                .as("the required contract states one entry per step, in order")
                .hasSameSizeAs(StatementGenerationJobA.STEP_NAMES);
            assertThat(StatementGenerationJobA.REQUIRED_STEPS.stream()
                    .filter(BatchConfig.StepContract::requirePrecedingExitCodeZero)
                    .map(BatchConfig.StepContract::name))
                .as("the gated entries of the required contract")
                .containsExactlyElementsOf(StatementGenerationJobA.GATED_STEP_NAMES);
            assertThat(StatementGenerationJobA.REQUIRED_STEPS.stream()
                    .map(BatchConfig.StepContract::program))
                .as("IDCAMS at :L22, SORT at :L44, IDCAMS again at :L56, IEFBR14 at :L66 and "
                    + "CBSTM03A at :L79 - and DELDEF01 and STEP020 run the SAME utility for "
                    + "opposite purposes, which is why the sequence is compared as a whole")
                .containsExactly(StatementGenerationJobA.UTILITY_PROGRAM,
                    StatementGenerationJobA.SORT_PROGRAM, StatementGenerationJobA.UTILITY_PROGRAM,
                    StatementGenerationJobA.NOOP_PROGRAM, PROGRAM);
        }

        /**
         * The two output record widths, and the losing {@code HTMLFILE} declaration (gates G19, G20,
         * risk R-G).
         *
         * <p>{@code app/jcl/CREASTMT.JCL} declares {@code HTMLFILE} <strong>twice at different
         * widths</strong>: the {@code STEP030} pre-delete says {@code LRECL=80} at {@code :L69} and the
         * {@code STEP040} step that actually creates it says {@code LRECL=100} at {@code :L94}. The
         * creating step is authoritative and {@code 01 FD-HTMLFILE-REC PIC X(100)} at
         * {@code app/cbl/CBSTM03A.CBL:L47} confirms it. The second half of this test proves the
         * disagreement cannot be resolved the wrong way by configuration: a catalogue offering 80 is
         * refused at construction rather than at the hundredth write.
         */
        @Test
        @DisplayName("STMTFILE is 80 bytes, HTMLFILE is 100, and a configured 80 for HTMLFILE is refused")
        void theTwoOutputWidthsAreEightyAndOneHundred() {
            assertThat(StatementTextWriter.RECORD_LENGTH)
                .as("01 FD-STMTFILE-REC PIC X(80) at app/cbl/CBSTM03A.CBL:L45, LRECL=80 at "
                    + "app/jcl/CREASTMT.JCL:L89")
                .isEqualTo(80);
            assertThat(StatementTextWriter.BLOCK_SIZE)
                .as("BLKSIZE=8000 at app/jcl/CREASTMT.JCL:L89").isEqualTo(8000);
            assertThat(StatementHtmlWriter.RECORD_LENGTH)
                .as("01 FD-HTMLFILE-REC PIC X(100) at app/cbl/CBSTM03A.CBL:L47, LRECL=100 at "
                    + "app/jcl/CREASTMT.JCL:L94 - the CREATING step, not the STEP030 pre-delete's "
                    + "LRECL=80 at :L69 (risk R-G)")
                .isEqualTo(100);
            assertThat(StatementHtmlWriter.BLOCK_SIZE)
                .as("BLKSIZE=800 at app/jcl/CREASTMT.JCL:L94").isEqualTo(800);
            assertThat(STMT_LAYOUT.recordLength()).isEqualTo(StatementTextWriter.RECORD_LENGTH);
            assertThat(HTML_LAYOUT.recordLength()).isEqualTo(StatementHtmlWriter.RECORD_LENGTH);

            DatasetBindings losing = bindings();
            losing.put(StatementGenerationJobA.HTMLFILE_DD,
                sequential(HTMLFILE_DSNAME, StatementTextWriter.RECORD_LENGTH, 3200));
            assertThatExceptionOfType(IllegalStateException.class)
                .as("a catalogue carrying the STEP030 pre-delete's width must be refused, because a "
                    + "run at 80 bytes would truncate twenty characters off every HTML record and "
                    + "still report success")
                .isThrownBy(() -> new StatementHtmlWriter(new JdbcTemplate(), ASCII, losing,
                    IMAGE_FORM))
                .withMessageContaining(String.valueOf(StatementHtmlWriter.RECORD_LENGTH));
        }

        /**
         * The seventeen text line templates, each exactly 80 bytes, contiguous and complete (gate G21).
         *
         * <p>{@code 01 STATEMENT-LINES} at {@code app/cbl/CBSTM03A.CBL:L85-L146} is sixteen numbered
         * lines plus {@code ST-LINE14A}, and every one of them is a group of {@code FILLER}s and named
         * slots summing to 80. If a {@code FILLER} were dropped from any of them the group would be
         * narrower than the record and the whole line would shift, which is why the sum is asserted per
         * line rather than only in aggregate.
         */
        @Test
        @DisplayName("STATEMENT-LINES is seventeen contiguous 80-byte lines, ST-LINE14A included")
        void theSeventeenLineTemplatesAreEightyBytesEach() {
            assertThat(StatementTextWriter.LINE_COUNT)
                .as("ST-LINE0 through ST-LINE15 is sixteen lines, and ST-LINE14A at "
                    + "app/cbl/CBSTM03A.CBL:L138 is the seventeenth")
                .isEqualTo(17);
            assertThat(StatementTextWriter.StatementLine.values())
                .as("one enum constant per declared line").hasSize(17);
            assertThat(StatementTextWriter.STATEMENT_LINES_LENGTH)
                .as("seventeen lines of eighty bytes")
                .isEqualTo(17 * StatementTextWriter.RECORD_LENGTH);

            int cursor = 0;
            for (StatementTextWriter.StatementLine line : StatementTextWriter.StatementLine.values()) {
                assertThat(line.length())
                    .as("%s must be exactly one record wide, or every FILLER after it shifts",
                        line.cobolName())
                    .isEqualTo(StatementTextWriter.RECORD_LENGTH);
                assertThat(line.offset())
                    .as("%s must follow the preceding line with no gap and no overlap",
                        line.cobolName())
                    .isEqualTo(cursor);
                cursor += line.length();
            }
            assertThat(cursor).isEqualTo(StatementTextWriter.STATEMENT_LINES_LENGTH);
            assertThat(StatementTextWriter.EDITED_AMOUNT_LENGTH)
                .as("PIC 9(9).99- and PIC Z(9).99- are both nine digits, a point, two digits and a "
                    + "trailing sign byte")
                .isEqualTo(13);
        }

        /**
         * The 1-based 51x10 transaction table (gate G33).
         *
         * <p>{@code 05 WS-CARD-TBL OCCURS 51 TIMES} containing {@code 10 WS-TRAN-TBL OCCURS 10 TIMES}
         * ({@code app/cbl/CBSTM03A.CBL:L226-L230}). The bounds are asserted here and the <em>indexing</em>
         * is asserted by cases 3 and 4, which pin the first and the last element of both dimensions -
         * the two places a 1-based-to-0-based conversion goes wrong.
         */
        @Test
        @DisplayName("WS-TRNX-TABLE is 51 cards of 10 transactions, at the copybook's own widths")
        void theTableIsFiftyOneByTenAndOneBased() {
            assertThat(StatementGenerationJobA.CARD_TABLE_OCCURS)
                .as("05 WS-CARD-TBL OCCURS 51 TIMES at app/cbl/CBSTM03A.CBL:L226").isEqualTo(51);
            assertThat(StatementGenerationJobA.TRAN_TABLE_OCCURS)
                .as("10 WS-TRAN-TBL OCCURS 10 TIMES at app/cbl/CBSTM03A.CBL:L228").isEqualTo(10);
            assertThat(StatementGenerationJobA.WS_CARD_NUM_LENGTH)
                .as("WS-CARD-NUM PIC X(16), the width of TRNX-CARD-NUM it is moved from")
                .isEqualTo(TrnxRecord.TRNX_CARD_NUM_LENGTH);
            assertThat(StatementGenerationJobA.WS_TRAN_NUM_LENGTH)
                .as("WS-TRAN-NUM PIC X(16), the width of TRNX-ID")
                .isEqualTo(TrnxRecord.TRNX_ID_LENGTH);
            assertThat(StatementGenerationJobA.WS_TRAN_REST_LENGTH)
                .as("WS-TRAN-REST PIC X(318), the width of TRNX-REST - so a table entry plus its "
                    + "key is a whole 350-byte record")
                .isEqualTo(TrnxRecord.TRNX_REST_LENGTH);
            assertThat(StatementGenerationJobA.WS_CARD_NUM_LENGTH
                    + StatementGenerationJobA.WS_TRAN_NUM_LENGTH
                    + StatementGenerationJobA.WS_TRAN_REST_LENGTH)
                .as("the table carries a complete TRNX-RECORD per element")
                .isEqualTo(TrnxRecord.RECORD_LENGTH);
        }

        /**
         * The {@code WS-FL-DD} dispatch, whose sixth {@code EVALUATE} arm is unreachable (gate G30).
         *
         * <p>{@code EVALUATE WS-FL-DD} at {@code app/cbl/CBSTM03A.CBL:L298-L314} has six arms: the four
         * DD names, {@code 'READTRNX'}, and a {@code WHEN OTHER} that goes straight to
         * {@code 9999-GOBACK}. Every assignment to {@code WS-FL-DD} in the program - the
         * {@code VALUE 'TRNXFILE'} at {@code L67} and the moves at {@code L760}, {@code L779},
         * {@code L797} and {@code L851} - names one of the five, so {@code WHEN OTHER} cannot be
         * reached at run time. It is preserved rather than removed (practice B5), and this asserts the
         * five that are reachable so a sixth state slipping into the set would be caught.
         */
        @Test
        @DisplayName("WS-FL-DD takes exactly five values, so the EVALUATE's WHEN OTHER is unreachable")
        void theFileControlStatesAreTheFiveReachableWsFlDdValues() {
            assertThat(StatementGenerationJobA.FILE_CONTROL_STATES)
                .as("the five WHEN arms of EVALUATE WS-FL-DD that any assignment can reach, in "
                    + "source order at app/cbl/CBSTM03A.CBL:L299-L312")
                .containsExactly(StatementGenerationJobA.STATE_TRNXFILE,
                    StatementGenerationJobA.STATE_XREFFILE,
                    StatementGenerationJobA.STATE_CUSTFILE,
                    StatementGenerationJobA.STATE_ACCTFILE,
                    StatementGenerationJobA.STATE_READTRNX);
            assertThat(StatementGenerationJobA.STATE_READTRNX)
                .as("'READTRNX' is the one state that is not a DD name: it is the loop state "
                    + "8100-TRNXFILE-OPEN moves in at L760 before branching back to 0000-START")
                .isNotIn(StatementGenerationJobB.DD_NAMES);
            assertThat(StatementGenerationJobA.FILE_CONTROL_STATES.subList(0, 4))
                .as("the first four states are the four DD names the subroutine dispatches on, and "
                    + "in the same order it dispatches on them")
                .isEqualTo(StatementGenerationJobB.DD_NAMES);
        }

        /**
         * The program declares only its two outputs and reaches all four inputs through the subroutine.
         *
         * <p>This is the structural fact the whole statement job turns on: {@code CBSTM03A}'s
         * {@code FILE-CONTROL} at {@code app/cbl/CBSTM03A.CBL:L38-L40} declares {@code STMTFILE} and
         * {@code HTMLFILE} and nothing else, while all four of {@code TRNXFILE}, {@code XREFFILE},
         * {@code CUSTFILE} and {@code ACCTFILE} are declared in {@code CBSTM03B} - which is why
         * {@code CBSTM03A} calls it thirteen times. The step nonetheless binds all six DD names at
         * {@code app/jcl/CREASTMT.JCL:L83-L96}, because the subroutine runs inside the same step.
         */
        @Test
        @DisplayName("the step binds six DDs; the four inputs belong to CBSTM03B and the two outputs here")
        void theProgramDeclaresOnlyItsTwoOutputs() {
            assertThat(StatementGenerationJobA.STEP_040_DD_NAMES)
                .as("the six DD names of app/jcl/CREASTMT.JCL:L83-L96, in declaration order - which "
                    + "is also the order the TIOT substitute reports them in")
                .containsExactly(StatementGenerationJobA.TRNXFILE_DD,
                    StatementGenerationJobA.XREFFILE_DD, StatementGenerationJobA.ACCTFILE_DD,
                    StatementGenerationJobA.CUSTFILE_DD, StatementGenerationJobA.STMTFILE_DD,
                    StatementGenerationJobA.HTMLFILE_DD);
            assertThat(StatementGenerationJobA.STEP_040_DD_NAMES)
                .as("every DD the subroutine owns is bound to the step")
                .containsAll(StatementGenerationJobB.DD_NAMES);
            assertThat(StatementGenerationJobB.DD_NAMES)
                .as("the two outputs are NOT the subroutine's: CBSTM03A writes them itself")
                .doesNotContain(StatementGenerationJobA.STMTFILE_DD,
                    StatementGenerationJobA.HTMLFILE_DD);
            assertThat(StatementGenerationJobA.STMTFILE_DD).isEqualTo(StatementTextWriter.DD_NAME);
            assertThat(StatementGenerationJobA.HTMLFILE_DD)
                .isEqualTo(StatementHtmlWriter.HTMLFILE_DD_NAME);
        }

        /**
         * The HTML literal catalogue, including the two literals nothing ever writes (practice B5).
         *
         * <p>Three properties, and each is a way the catalogue could be wrong while every case still
         * passed. First, {@code HTML-L08} and the eleven other continuation-line literals must be joined
         * with <strong>no inserted whitespace</strong>: a COBOL continuation reopens the literal at the
         * quote and adds nothing, so {@code '...styl'} followed by {@code 'e="width:70%...'} is one word.
         * Second, {@code HTML-L11} is a 59-byte group and {@code HTML-L23} a 76-byte one, and
         * {@code HTML-L23} is <em>declared and never written</em> - as is {@code HTML-LTDS}, which no
         * {@code SET} in the program names. Both stay. Third, every literal must fit the 100-byte record,
         * because a longer one would be silently truncated on the {@code MOVE}.
         */
        @Test
        @DisplayName("every HTML literal fits 100 bytes; HTML-L23 and HTML-LTDS are declared and unwritten")
        void theHtmlLiteralCatalogueIsPreservedIncludingItsDeadEntries() {
            for (StatementHtmlWriter.HtmlFixedLine line
                : StatementHtmlWriter.HtmlFixedLine.values()) {
                assertThat(line.literalLength())
                    .as("%s is %d bytes and the record is %d; a longer literal would be truncated "
                            + "by the MOVE that SET performs", line.cobolName(),
                        line.literalLength(), StatementHtmlWriter.RECORD_LENGTH)
                    .isLessThanOrEqualTo(StatementHtmlWriter.RECORD_LENGTH);
                assertThat(line.literal()).as("%s must carry a literal", line.cobolName())
                    .isNotEmpty();
            }
            assertThat(StatementHtmlWriter.HtmlFixedLine.ofCobolName("HTML-L08").literal())
                .as("the eleven continuation-line literals join with NO inserted whitespace: "
                    + "app/cbl/CBSTM03A.CBL:L157 ends '...styl' and :L158 reopens 'e=\\\"width...'")
                .isEqualTo("<table  align=\"center\" frame=\"box\" "
                    + "style=\"width:70%; font:12px Segoe UI,sans-serif;\">");
            assertThat(StatementHtmlWriter.HtmlFixedLine.ofCobolName("HTML-LTDS").literal())
                .as("88 HTML-LTDS VALUE '<td>' at app/cbl/CBSTM03A.CBL:L161 is declared and named by "
                    + "no SET anywhere in the program. It is preserved, not deleted (practice B5).")
                .isEqualTo("<td>");
            assertThat(StatementHtmlWriter.HTML_L11_LENGTH)
                .as("05 HTML-L11 is FILLER X(34) + L11-ACCT X(20) + FILLER X(05) at "
                    + "app/cbl/CBSTM03A.CBL:L212-L216")
                .isEqualTo(34 + StatementHtmlWriter.L11_ACCT_LENGTH + 5)
                .isEqualTo(59);
            assertThat(StatementHtmlWriter.HTML_L23_LENGTH)
                .as("05 HTML-L23 is FILLER X(26) + L23-NAME X(50) at :L217-L220 and is written by "
                    + "NOTHING - 5200-WRITE-HTML-NMADBS moves ST-NAME into L23-NAME at L560 and then "
                    + "STRINGs into the record area instead. Preserved (practice B5).")
                .isEqualTo(StatementHtmlWriter.STYLED_PARAGRAPH_OPEN_TAG.length()
                    + StatementHtmlWriter.L23_NAME_LENGTH)
                .isEqualTo(76);
            assertThat(StatementHtmlWriter.HtmlFixedLine.isDeclared("HTML-L23"))
                .as("HTML-L23 is a group item, not an 88-level, so it is not part of the fixed-line "
                    + "catalogue even though it is declared alongside it")
                .isFalse();
            assertThat(Stream.of(StatementHtmlWriter.BasicDetail.values())
                    .map(StatementHtmlWriter.BasicDetail::label))
                .as("the three basic-detail labels of app/cbl/CBSTM03A.CBL:L614, L621 and L628, "
                    + "byte for byte including their internal padding to a common colon column")
                .containsExactly("<p>Account ID         : ",
                    "<p>Current Balance    : ", "<p>FICO Score         : ");
        }

        /**
         * Both amount masks truncate toward zero at scale 2, and neither ever rounds (gates G22, G24).
         *
         * <p>The keyword {@code ROUNDED} appears <strong>zero times</strong> in all twenty-eight
         * programs, so a COBOL store of an over-precise fraction truncates - which makes
         * {@link java.math.RoundingMode#DOWN} the only faithful choice and {@code HALF_UP} and
         * {@code HALF_EVEN} both wrong. Every monetary value in the twenty case files is already an
         * edited thirteen-byte image, so a case file cannot by itself distinguish a truncation from a
         * rounding that happened to agree with it; this asserts the policy at the seam
         * ({@link com.vsergeychik.carddemo.common.CobolDecimal}) and then at the mask, with a third
         * fractional digit that a half-up rounding would carry.
         *
         * <p>The sign is asserted separately and deliberately: a {@code PICTURE} sign control symbol
         * represents the operational sign of the value being <em>edited</em>, so a magnitude that
         * truncates away entirely still renders a minus. Taking the sign from the truncated magnitude
         * instead is the plausible mistake, and it differs only for this value.
         */
        @Test
        @DisplayName("G24 - both masks truncate toward zero at scale 2, and the sign is the sender's")
        void theAmountMasksTruncateRatherThanRound() {
            assertThat(CobolDecimal.MONETARY_SCALE)
                .as("every scaled numeric in this system is scale 2: the only signed PICTUREs in the "
                    + "estate are S9(10)V99, S9(09)V99 and S9(9)V99")
                .isEqualTo(2);
            assertThat(CobolDecimal.COBOL_ROUNDING)
                .as("ROUNDED appears zero times in all 28 programs, so a store truncates")
                .isEqualTo(RoundingMode.DOWN);

            StatementTextWriter writer =
                new StatementTextWriter(new JdbcTemplate(), ASCII, bindings(), IMAGE_FORM);
            StatementTextWriter.StatementFile file =
                writer.openOutput(recordImage -> FileStatus.Outcome.OK);

            file.setTransactionAmount(new BigDecimal("1.999"));
            assertThat(file.slotImage(StatementTextWriter.StatementSlot.ST_TRANAMT))
                .as("PIC Z(9).99- of 1.999 truncates to 1.99; a half-up rounding would render 2.00 "
                    + "and every total built on it would drift")
                .isEqualTo("        1.99 ");

            file.setCurrentBalance(new BigDecimal("-0.005"));
            assertThat(file.slotImage(StatementTextWriter.StatementSlot.ST_CURR_BAL))
                .as("PIC 9(9).99- of -0.005 truncates the magnitude away entirely and still renders "
                    + "the sending operand's minus")
                .isEqualTo("000000000.00-");

            file.setTotalTransactionAmount(new BigDecimal("-1234567890.129"));
            assertThat(file.slotImage(StatementTextWriter.StatementSlot.ST_TOTAL_TRAMT))
                .as("nine integer positions and two fraction digits: the tenth integer digit is "
                    + "discarded on the left and the third fraction digit on the right, both without "
                    + "rounding")
                .isEqualTo("234567890.12-");
        }

        /**
         * The unique abend: {@code CALL 'CEE3ABD'} with neither an {@code ABCODE} nor a {@code TIMING}
         * (gate G35).
         *
         * <p>{@code 9999-ABEND-PROGRAM} at {@code app/cbl/CBSTM03A.CBL:L921-L923} is
         * {@code DISPLAY 'ABENDING PROGRAM'} followed by a bare {@code CALL 'CEE3ABD'} - no
         * {@code USING}, no abend code, no timing. Every other abend site in the estate passes both, so
         * {@link AbendException} models them as optional and this is the one place where both must be
         * absent.
         *
         * <p>The abend is provoked rather than constructed: an <em>empty</em> {@code TRNXFILE} makes
         * {@code 8100-TRNXFILE-OPEN}'s read at {@code L746} report {@code '10'}, which the
         * {@code IF WS-M03B-RC = '00' OR '04'} guard at {@code L748} treats as fatal. So what is asserted
         * is the exception the program's own guard chain raises, and the three displayed lines it raises
         * it with.
         */
        @Test
        @DisplayName("G35 - the abend carries neither an ABCODE nor a TIMING, after three DISPLAY lines")
        void theAbendCarriesNeitherAnAbendCodeNorATiming() {
            ParityHarness harness = ParityHarness.usAscii();
            ParityHarness.UnitOutcome.Builder recorder =
                ParityHarness.UnitOutcome.builder(harness.codec());
            Map<String, ParityHarness.SeededDataset> seeded = Map.of(
                StatementGenerationJobA.TRNXFILE_DD,
                ParityHarness.SeededDataset.empty(StatementGenerationJobA.TRNXFILE_DD,
                    TrnxRecord.RECORD_LENGTH, ASCII));
            JobFixture assembled =
                fixture(seeded, Scenario.normal(), recorder);

            assertThatExceptionOfType(AbendException.class)
                .as("an empty TRNXFILE makes the read at L746 report '10', which L748's guard does "
                    + "not accept")
                .isThrownBy(() -> assembled.job().printAccountStatements(recorder::display))
                .satisfies(abend -> {
                    assertThat(abend.hasAbendCode())
                        .as("CALL 'CEE3ABD' at L923 passes no abend code, and this is the only site "
                            + "in the estate that passes none")
                        .isFalse();
                    assertThat(abend.getAbendCode()).isEmpty();
                    assertThat(abend.hasTiming())
                        .as("and no timing either").isFalse();
                    assertThat(abend.getTiming()).isEmpty();
                    assertThat(abend.getReturnCode())
                        .as("the RETURN-CODE the ten guarded sites abend with")
                        .isEqualTo(StatementGenerationJobA.ABEND_RETURN_CODE);
                    assertThat(abend.getProgram()).isEqualTo(PROGRAM);
                });

            List<String> displayed = recorder.build().messages().stream()
                .map(ParityCase.EmittedMessage::text)
                .toList();
            assertThat(displayed)
                .as("the guard's own two DISPLAYs at L751-L752 then 9999-ABEND-PROGRAM's at L922, in "
                    + "that order and at the end of the TIOT walk's own lines")
                .endsWith(StatementGenerationJobA.ERROR_READING_TRNXFILE,
                    StatementGenerationJobA.RETURN_CODE_PREFIX + FileStatus.END_OF_FILE,
                    StatementGenerationJobA.ABENDING_PROGRAM);
        }

        /**
         * The two null-UCB literals differ by a space, and the difference is preserved.
         *
         * <p>{@code app/cbl/CBSTM03A.CBL:L281} displays {@code ' --  null UCB'} inside the {@code TIOT}
         * loop and {@code :L290} displays {@code ' -- null  UCB'} after it. Both are thirteen characters
         * and both have two consecutive spaces, in different places. Normalising them would look like
         * tidying and would change two of this program's displayed lines, so the divergence is asserted
         * explicitly - and case 20 is the one that emits both in a single run.
         */
        @Test
        @DisplayName("the in-loop and post-loop null-UCB literals are byte-different, and both survive")
        void theTiotSubstitutionKeepsBothNullUcbLiterals() {
            assertThat(StatementGenerationJobA.NULL_UCB_SUFFIX_IN_LOOP)
                .as("app/cbl/CBSTM03A.CBL:L281").isEqualTo(" --  null UCB");
            assertThat(StatementGenerationJobA.NULL_UCB_SUFFIX_AFTER_LOOP)
                .as("app/cbl/CBSTM03A.CBL:L290").isEqualTo(" -- null  UCB");
            assertThat(StatementGenerationJobA.NULL_UCB_SUFFIX_IN_LOOP)
                .as("they are not the same literal, however alike they read")
                .isNotEqualTo(StatementGenerationJobA.NULL_UCB_SUFFIX_AFTER_LOOP)
                .hasSameSizeAs(StatementGenerationJobA.NULL_UCB_SUFFIX_AFTER_LOOP);
            assertThat(StatementGenerationJobA.VALID_UCB_SUFFIX)
                .as("app/cbl/CBSTM03A.CBL:L279 and :L288, which are the same literal")
                .isEqualTo(" -- valid UCB");
            assertThat(TiotEntry.terminator().ddName())
                .as("the chain terminator is TIOCDDNM PIC X(08) of low values, which the post-loop "
                    + "DISPLAY at :L288-L291 emits as eight blanks")
                .isEqualTo(" ".repeat(StatementGenerationJobA.TIOT_NAME_WIDTH));
            assertThat(TiotEntry.terminator().validUcb())
                .as("the terminator has no unit control block, so it takes the post-loop null "
                    + "literal")
                .isFalse();
        }
    }

    // =============================================================================================
    //  THE COLLABORATORS - a fixture-backed relation, the real writers and a real subroutine
    // =============================================================================================

    /**
     * The six-entry {@code carddemo.datasets} catalogue every collaborator is constructed with.
     *
     * <p>Every entry has to agree with its copybook about the record width, because three constructors
     * check it at startup rather than at the first read - and one of those checks is the whole of gate
     * G20: {@link StatementHtmlWriter} refuses a configured width of 80 for {@code HTMLFILE} and names
     * both JCL declarations in the refusal, so the losing {@code STEP030} width cannot reach a run.
     *
     * <p>{@code recordFormat} is {@code "FB"}, which is what {@code app/jcl/CREASTMT.JCL} declares. The
     * CSD says {@code RECORDFORMAT(V)} for the four input datasets and the two disagree in the source;
     * practice B4 forbids reconciling that, and nothing here depends on it - every width is
     * copybook-fixed either way.
     *
     * @return the catalogue
     */
    private static DatasetBindings bindings() {
        DatasetBindings catalogue = new DatasetBindings();
        catalogue.put(StatementGenerationJobA.TRNXFILE_DD, ksds(TRNXFILE_DSNAME,
            TrnxRecord.RECORD_LENGTH, TrnxRecord.TRNX_KEY_LENGTH, "COSTM01"));
        catalogue.put(StatementGenerationJobA.XREFFILE_DD, ksds(XREFFILE_DSNAME,
            CardXrefRecord.RECORD_LENGTH, CardXrefRecord.XREF_CARD_NUM_LENGTH, "CVACT03Y"));
        catalogue.put(StatementGenerationJobA.ACCTFILE_DD, ksds(ACCTFILE_DSNAME,
            AccountRecord.RECORD_LENGTH, AccountRecord.ACCT_ID_LENGTH, "CVACT01Y"));
        catalogue.put(StatementGenerationJobA.CUSTFILE_DD, ksds(CUSTFILE_DSNAME,
            Stm03CustomerRecord.RECORD_LENGTH, Stm03CustomerRecord.KEY_LENGTH, "CUSTREC"));
        catalogue.put(StatementGenerationJobA.STMTFILE_DD, sequential(STMTFILE_DSNAME,
            StatementTextWriter.RECORD_LENGTH, StatementTextWriter.BLOCK_SIZE));
        catalogue.put(StatementGenerationJobA.HTMLFILE_DD, sequential(HTMLFILE_DSNAME,
            StatementHtmlWriter.RECORD_LENGTH, StatementHtmlWriter.BLOCK_SIZE));
        return catalogue;
    }

    /**
     * One well-formed indexed binding.
     *
     * @param dsname the dataset name
     * @param recordLength the copybook's record width
     * @param keyLength the {@code RECORD KEY} width
     * @param copybook the copybook the width comes from
     * @return the binding
     */
    private static DatasetBinding ksds(String dsname, int recordLength, int keyLength,
                                       String copybook) {
        return new DatasetBinding(dsname, DatasetBinding.KSDS, false, "FB", null, recordLength,
            copybook, keyLength, 0, null, null);
    }

    /**
     * One well-formed physical-sequential binding - the shape both statement outputs take.
     *
     * @param dsname the dataset name
     * @param recordLength the {@code LRECL} of the creating step
     * @param blockSize the {@code BLKSIZE} of the creating step
     * @return the binding
     */
    private static DatasetBinding sequential(String dsname, int recordLength, int blockSize) {
        return new DatasetBinding(dsname, "sequential", false, "FB", blockSize, recordLength, null,
            null, null, null, null);
    }

    /**
     * The batch seam, carrying the five-step {@code app/jcl/CREASTMT.JCL} contract verbatim.
     *
     * <p>{@link StatementGenerationJobA#REQUIRED_STEPS} is handed straight in rather than re-listed.
     * The constructor compares the configured sequence against that list as a whole - names, programs,
     * gates and order - so a hand-written copy here would only create a second thing to keep right, and
     * the one property worth asserting about the contract is asserted in
     * {@link StructuralGates#theJobDeclaresTheFiveStepsOfCreastmtWithThreeCondGates()} instead.
     *
     * <p>The job repository and transaction manager are mocked because nothing in this suite launches a
     * job: the program is reached by a direct call (gate G51), so no execution is ever persisted.
     *
     * @param catalogue the dataset catalogue the seam publishes
     * @return the seam
     */
    private static BatchConfig scaffolding(DatasetBindings catalogue) {
        JobContracts contracts = new JobContracts();
        contracts.put(StatementGenerationJobA.JOB_KEY, new JobContract(
            StatementGenerationJobA.PROGRAM_ID, List.of(), StatementGenerationJobA.REQUIRED_STEPS,
            null, Map.of()));
        return new BatchConfig(new SuppliedBean<>(mock(JobRepository.class)),
            new SuppliedBean<>(mock(PlatformTransactionManager.class)), contracts, catalogue);
    }

    /**
     * A real unit of work over the case's own relation.
     *
     * <p>Real rather than mocked because the abnormal disposition depends on it: five of the twenty
     * cases end in an abend, {@code app/jcl/CREASTMT.JCL:L87-L96} declares both outputs
     * {@code DISP=(NEW,CATLG,DELETE)}, and a mocked transaction manager would let a disposition that
     * never opens a boundary look correct. The discard itself reaches the two collecting sinks rather
     * than the relation, since that is where this run's records are.
     *
     * @param template the template whose data source the boundary is opened on
     * @return the unit of work
     */
    private static DatasetUnitOfWork unitOfWork(JdbcTemplate template) {
        return new DatasetUnitOfWork(new JdbcTransactionManager(template.getDataSource()));
    }

    /**
     * A template over a fresh in-memory relation per case, holding the seeded input rows.
     *
     * <p>One database per invocation, named with a fresh {@link UUID}: two cases - and two clones
     * running in parallel - can never share one, and no counter is kept because a mutable static field
     * is exactly what practice B9 and gate G53 forbid. Only the four <em>input</em> datasets get a
     * relation; the two outputs are reached through supplied sinks, so they need no table and this
     * suite issues no data-definition statement against anything the program writes.
     *
     * <p>The column is declared at the seeded width rather than at the copybook width. The two agree
     * for every case here, and stating the seeded one means a disagreement would surface as the
     * {@code '04'} record-length conflict COBOL reports rather than being papered over by a wider
     * column.
     *
     * @param seeded the datasets the harness seeded, keyed by binding key
     * @return a template over the seeded relations
     */
    private static JdbcTemplate seededTemplate(Map<String, ParityHarness.SeededDataset> seeded) {
        SimpleDriverDataSource dataSource = new SimpleDriverDataSource(new org.h2.Driver(),
            "jdbc:h2:mem:cbstm03a" + UUID.randomUUID().toString().replace("-", "")
                + ";DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE", "sa", "");
        JdbcTemplate template = new JdbcTemplate(dataSource);

        for (String dd : StatementGenerationJobB.DD_NAMES) {
            ParityHarness.SeededDataset dataset = seeded.get(dd);
            if (dataset == null) {
                continue;
            }
            String relation = delimited(dsnameOf(dd));
            template.execute("CREATE TABLE " + relation + " (" + delimited(RECORD_IMAGE_COLUMN)
                + " VARCHAR(" + dataset.recordLength() + "))");
            for (String row : dataset.rows()) {
                template.update("INSERT INTO " + relation + " VALUES (?)", row);
            }
        }
        return template;
    }

    /**
     * The test dataset name each binding key resolves to.
     *
     * @param dd the binding key
     * @return that dataset's test name
     * @throws IllegalArgumentException if the DD is not one this program touches
     */
    private static String dsnameOf(String dd) {
        return switch (dd) {
            case StatementGenerationJobA.TRNXFILE_DD -> TRNXFILE_DSNAME;
            case StatementGenerationJobA.XREFFILE_DD -> XREFFILE_DSNAME;
            case StatementGenerationJobA.ACCTFILE_DD -> ACCTFILE_DSNAME;
            case StatementGenerationJobA.CUSTFILE_DD -> CUSTFILE_DSNAME;
            case StatementGenerationJobA.STMTFILE_DD -> STMTFILE_DSNAME;
            case StatementGenerationJobA.HTMLFILE_DD -> HTMLFILE_DSNAME;
            default -> throw new IllegalArgumentException("'" + dd + "' names no dataset: "
                + PROGRAM + " reaches exactly the six DD names "
                + StatementGenerationJobA.STEP_040_DD_NAMES + ", and a seventh would be a dataset "
                + "app/jcl/CREASTMT.JCL:L79-L96 does not bind to the step.");
        };
    }

    /**
     * Renders an identifier as a SQL delimited identifier, doubling any quotation mark within it.
     *
     * <p>A dataset name is dotted and would otherwise be parsed as a qualified reference, and it is
     * never a parameter, so it is delimited rather than bound. Doubling is unreachable for these six
     * constants; it is written anyway so the helper is correct for whatever it is handed rather than
     * only for the values one caller happens to supply.
     *
     * @param identifier the identifier text
     * @return the delimited identifier
     */
    private static String delimited(String identifier) {
        return '"' + identifier.replace("\"", "\"\"") + '"';
    }

    /**
     * The {@code TIOT} substitute one case runs against.
     *
     * <p>Supplied rather than left to {@link StatementGenerationJobA}'s configured default so a case can
     * choose which DD names have no unit control block behind them, which is the only way to reach the
     * in-loop {@code ' --  null UCB'} literal at {@code L281}. The entry list is
     * {@link StatementGenerationJobA#STEP_040_DD_NAMES} in JCL declaration order and the job and step
     * names are the two the production class derives, so what is asserted is still the lines the
     * production class emits rather than a shape invented here.
     *
     * @param scenario the case's environment, naming any DD with no unit control block
     * @return the source
     */
    private static TiotSource tiotSource(Scenario scenario) {
        List<TiotEntry> entries = new ArrayList<>(StatementGenerationJobA.STEP_040_DD_NAMES.size());
        for (String dd : StatementGenerationJobA.STEP_040_DD_NAMES) {
            entries.add(new TiotEntry(dd, !scenario.nullUcbDds().contains(dd)));
        }
        TiotImage image = new TiotImage(StatementGenerationJobA.JCL_JOB_NAME,
            StatementGenerationJobA.STEP_040, entries, TiotEntry.terminator());
        return () -> image;
    }

    /**
     * The real subroutine, with one call site's {@code FILE STATUS} substituted.
     *
     * <p>Real and not scripted, deliberately. {@code CBSTM03B}'s own twenty cases already gate the
     * data layer field for field, so a diff here is almost certainly a control-flow or write-order
     * fault rather than an I/O fault - and that is only true if the I/O is the same I/O. Every read
     * therefore goes to the seeded relation through the 1040-byte {@code LK-M03B-AREA} contract, and
     * only the two-character status of one nominated call is replaced.
     *
     * <p>The record area is carried through unchanged when a status is substituted, because the guard
     * that receives it abends before looking at it. Overriding the four operations rather than
     * {@code call(Session, Request)} is what {@link StatementGenerationJobA} actually invokes: it
     * reaches the subroutine through {@code open}, {@code readNext}, {@code readByKey} and
     * {@code close} at all thirteen of its {@code CALL 'CBSTM03B'} sites.
     */
    private static final class SubstitutingSubroutine extends StatementGenerationJobB {

        /** The case's environment, naming at most one call site and the status it reports. */
        private final Scenario scenario;

        /**
         * @param template the template over the seeded relations
         * @param catalogue the dataset catalogue
         * @param scenario the case's environment
         */
        private SubstitutingSubroutine(JdbcTemplate template, DatasetBindings catalogue,
                                       Scenario scenario) {
            super(template, catalogue, ASCII, IMAGE_FORM);
            this.scenario = scenario;
        }

        @Override
        public Response open(Session session, String dd) {
            return substituted(CallSite.OPEN_TRNXFILE, TRNXFILE_DD, dd, super.open(session, dd));
        }

        @Override
        public Response readNext(Session session, String dd) {
            return substituted(CallSite.READ_XREFFILE, XREFFILE_DD, dd,
                super.readNext(session, dd));
        }

        @Override
        public Response readByKey(Session session, String dd, String key, int keyLength) {
            Response real = super.readByKey(session, dd, key, keyLength);
            Response afterCustomer =
                substituted(CallSite.READ_CUSTFILE, CUSTFILE_DD, dd, real);
            return substituted(CallSite.READ_ACCTFILE, ACCTFILE_DD, dd, afterCustomer);
        }

        @Override
        public Response close(Session session, String dd) {
            return substituted(CallSite.CLOSE_TRNXFILE, TRNXFILE_DD, dd, super.close(session, dd));
        }

        /**
         * Replaces the status of exactly the nominated call site, and of nothing else.
         *
         * @param site the call site this override implements
         * @param siteDd the DD name that site addresses
         * @param dd the DD name this call addressed
         * @param real what the subroutine actually reported
         * @return the substituted response, or {@code real} when this is not the nominated site
         */
        private Response substituted(CallSite site, String siteDd, String dd, Response real) {
            if (scenario.site() != site || !siteDd.equals(dd)) {
                return real;
            }
            return new Response(scenario.status(), real.fldt());
        }
    }

    /**
     * The {@code STMTFILE} sink: records every 80-byte image into the fingerprint as it is written.
     *
     * <p>Two channels, and they are genuinely different observations. A record handed to the sink was
     * <em>written</em> - the COBOL {@code WRITE} completed and the record is durable as it completes -
     * while what the sink still <em>retains</em> is what the step leaves behind, and
     * {@code DISP=(NEW,CATLG,DELETE)} makes those two differ on every abnormal end. A sink that modelled
     * only "what was written" could not say that the abended run left nothing.
     */
    private static final class TextSink implements StatementTextWriter.RecordSink {

        /** Where a write is recorded the moment it happens. */
        private final ParityHarness.UnitOutcome.Builder recorder;

        /** Every image still retained, in write order, after any abnormal disposition. */
        private final List<String> retained = new ArrayList<>();

        /** @param recorder the fingerprint recorder */
        private TextSink(ParityHarness.UnitOutcome.Builder recorder) {
            this.recorder = recorder;
        }

        /**
         * {@code OPEN OUTPUT STMT-FILE}, {@code app/cbl/CBSTM03A.CBL:L293}.
         *
         * <p>Registering the dataset here rather than at the first write is what makes "the program
         * opened this file and wrote nothing to it" an observation. Two of the twenty cases turn on it.
         *
         * @return {@link FileStatus.Outcome#OK}; the COBOL's {@code OPEN} carries no
         *     {@code FILE STATUS} clause and no guard, so there is no failure arm to model
         */
        @Override
        public FileStatus.Outcome open() {
            recorder.openedWithoutWriting(StatementGenerationJobA.STMTFILE_DD, STMT_LAYOUT);
            return FileStatus.Outcome.OK;
        }

        @Override
        public FileStatus.Outcome write(byte[] recordImage) {
            String image = new String(recordImage, ASCII);
            recorder.wrote(StatementGenerationJobA.STMTFILE_DD, STMT_LAYOUT, image);
            retained.add(image);
            return FileStatus.Outcome.OK;
        }

        /**
         * The {@code DELETE} positional of {@code DISP=(NEW,CATLG,DELETE)},
         * {@code app/jcl/CREASTMT.JCL:L87}.
         *
         * @param recordsWritten how many records the generation held
         * @return {@link FileStatus.Outcome#OK}
         */
        @Override
        public FileStatus.Outcome discard(int recordsWritten) {
            retained.clear();
            return FileStatus.Outcome.OK;
        }

        /** @return every image the run left behind, in write order */
        private List<String> retained() {
            return List.copyOf(retained);
        }
    }

    /**
     * The {@code HTMLFILE} sink, at 100 bytes, with the same two channels for the same reason.
     *
     * <p>It reports a two-character status rather than a {@link FileStatus.Outcome} because that is the
     * shape {@link StatementHtmlWriter.HtmlRecordSink} declares; the two writers were translated from
     * two files whose COBOL says nothing about status at all, so neither shape is more faithful than
     * the other.
     */
    private static final class HtmlSink implements StatementHtmlWriter.HtmlRecordSink {

        /** Where a write is recorded the moment it happens. */
        private final ParityHarness.UnitOutcome.Builder recorder;

        /** Every image still retained, in write order, after any abnormal disposition. */
        private final List<String> retained = new ArrayList<>();

        /** @param recorder the fingerprint recorder */
        private HtmlSink(ParityHarness.UnitOutcome.Builder recorder) {
            this.recorder = recorder;
        }

        /**
         * {@code OPEN OUTPUT HTML-FILE}, {@code app/cbl/CBSTM03A.CBL:L293}.
         *
         * @return {@link FileStatus#OK}, which the job reads back through
         *     {@link StatementHtmlWriter.HtmlStatementFile#openStatus()}
         */
        @Override
        public String open() {
            recorder.openedWithoutWriting(StatementGenerationJobA.HTMLFILE_DD, HTML_LAYOUT);
            return FileStatus.OK;
        }

        @Override
        public String write(byte[] record) {
            String image = new String(record, ASCII);
            recorder.wrote(StatementGenerationJobA.HTMLFILE_DD, HTML_LAYOUT, image);
            retained.add(image);
            return FileStatus.OK;
        }

        /**
         * The {@code DELETE} positional of {@code DISP=(NEW,CATLG,DELETE)},
         * {@code app/jcl/CREASTMT.JCL:L92}.
         *
         * @param recordsWritten how many records the generation held
         * @return {@link FileStatus#OK}
         */
        @Override
        public String discard(long recordsWritten) {
            retained.clear();
            return FileStatus.OK;
        }

        /** @return every image the run left behind, in write order */
        private List<String> retained() {
            return List.copyOf(retained);
        }
    }

    /**
     * An {@link ObjectProvider} over one bean, or over none.
     *
     * <p>{@code null} means the container publishes no such bean, which is how the utility port is
     * supplied here: the statement step never touches it, so letting
     * {@link StatementGenerationJobA} build its own default is more honest than injecting a stand-in
     * that would never be called.
     *
     * @param <T> the bean type
     * @param bean the bean, or {@code null} when none is published
     */
    private record SuppliedBean<T>(T bean) implements ObjectProvider<T> {

        @Override
        public T getObject() {
            if (bean == null) {
                throw new NoSuchBeanDefinitionException("no bean of this type is published");
            }
            return bean;
        }

        @Override
        public T getIfAvailable() {
            return bean;
        }

        @Override
        public T getIfUnique() {
            return bean;
        }

        @Override
        public Stream<T> stream() {
            return bean == null ? Stream.empty() : Stream.of(bean);
        }
    }
}
