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
import com.vsergeychik.carddemo.common.DatasetUnitOfWork;
import com.vsergeychik.carddemo.statement.StatementGenerationJobA;
import com.vsergeychik.carddemo.statement.StatementGenerationJobA.DatasetUtilityPort;
import com.vsergeychik.carddemo.statement.StatementGenerationJobA.SysoutSink;
import com.vsergeychik.carddemo.statement.StatementGenerationJobA.TiotEntry;
import com.vsergeychik.carddemo.statement.StatementGenerationJobA.TiotImage;
import com.vsergeychik.carddemo.statement.StatementGenerationJobA.TiotSource;
import com.vsergeychik.carddemo.statement.StatementGenerationJobB;
import com.vsergeychik.carddemo.statement.TrnxRepository;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
import com.vsergeychik.carddemo.testdataset.RecordImageDataSource;
import com.vsergeychik.carddemo.testdataset.RecordImageStore.ColumnForm;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.Mockito.mock;

/**
 * The twenty-case parity gate for {@code CBSTM03A}, the account-statement generator.
 */
@DisplayName("CBSTM03A parity - the statement generator's loops, widths, masks and write order")
class CBSTM03AParityTest {
    private static final String PROGRAM = StatementGenerationJobA.PROGRAM_ID;

    private static final Charset ASCII = StandardCharsets.US_ASCII;

    private static final RecordImageForm IMAGE_FORM = RecordImageForm.CHARACTER;

    private static final PhysicalSequence ORDINAL = PhysicalSequence.of("_ROWID_");

    private static final String RECORD_IMAGE_COLUMN = "REC";

    private static final String TRNXFILE_DSNAME = "TEST.M2.CARDDEMO.TRXFL.VSAM.KSDS";

    private static final String XREFFILE_DSNAME = "TEST.M2.CARDDEMO.CARDXREF.VSAM.KSDS";

    private static final String ACCTFILE_DSNAME = "TEST.M2.CARDDEMO.ACCTDATA.VSAM.KSDS";

    private static final String CUSTFILE_DSNAME = "TEST.M2.CARDDEMO.CUSTDATA.VSAM.KSDS";

    private static final String STMTFILE_DSNAME = "TEST.M2.CARDDEMO.STATEMNT.PS";

    private static final String HTMLFILE_DSNAME = "TEST.M2.CARDDEMO.STATEMNT.HTML";

    private static final List<String> ALL_DD_NAMES = List.of(
        StatementGenerationJobA.TRNXFILE_DD,
        StatementGenerationJobA.XREFFILE_DD,
        StatementGenerationJobA.ACCTFILE_DD,
        StatementGenerationJobA.CUSTFILE_DD,
        StatementGenerationJobA.STMTFILE_DD,
        StatementGenerationJobA.HTMLFILE_DD);

    private static final Map<CallSite, String> GUARD_ARMS_COVERED_OUTSIDE_THIS_DIRECTORY = Map.of(
        CallSite.READ_CUSTFILE,
        "StatementGenerationJobATest.TheStatusMatrix.theCustFileKeyedReadHasNoEndOfFileArm");

    private static final String NULL_UCB_DDS_KEY = "NULL_UCB_DDS";

    private static final RecordLayout STMT_LAYOUT = RecordLayout.of(
        StatementTextWriter.RECORD_LENGTH,
        FieldSpan.alphanumeric("FD-STMTFILE-REC", 0, StatementTextWriter.RECORD_LENGTH));

    private static final RecordLayout HTML_LAYOUT = RecordLayout.of(
        StatementHtmlWriter.RECORD_LENGTH,
        FieldSpan.alphanumeric("FD-HTMLFILE-REC", 0, StatementHtmlWriter.RECORD_LENGTH));

    private static final String BYPASSING_STEP = StatementGenerationJobA.STEP_010;

    private static final int BYPASSING_RETURN_CODE = 4;

    static List<ParityCase> cases() {
        return ParityHarness.casesOf(PROGRAM);
    }

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

    @Test
    @DisplayName("decodes every case's declared stimulus, and names all five substitutable call sites")
    void everyCaseDeclaresADecodableScenario() {
        Map<CallSite, List<String>> byCallSite = new LinkedHashMap<>();
        for (ParityCase parityCase : cases()) {
            Scenario decoded = scenarioOf(parityCase);
            assertThat(decoded)
                .as("case %s declares a stimulus that does not decode", parityCase.caseId())
                .isNotNull();
            byCallSite.computeIfAbsent(decoded.site(), key -> new ArrayList<>())
                .add(parityCase.caseId());
            if (decoded.site() == CallSite.NONE) {
                assertThat(decoded.status())
                    .as("case %s names no call site, so it must substitute no status either",
                        parityCase.caseId())
                    .isNull();
            } else {
                assertThat(decoded.status())
                    .as("case %s names %s, so it must say what that site reports", parityCase.caseId(),
                        decoded.site())
                    .isNotNull();
            }
        }

        for (CallSite site : CallSite.values()) {
            if (site == CallSite.NONE) {
                continue;
            }
            String coveredElsewhere = GUARD_ARMS_COVERED_OUTSIDE_THIS_DIRECTORY.get(site);
            if (coveredElsewhere != null) {
                assertThat(byCallSite.get(site))
                    .as("%s is recorded as covered by %s rather than by a case here, so no case may "
                        + "declare it: two homes for one guard arm is how the record of where it is "
                        + "driven goes stale", site.declaredName(), coveredElsewhere)
                    .isNull();
                continue;
            }
            assertThat(byCallSite.get(site))
                .as("no case declares an outcome at %s, and it is not recorded in "
                    + "GUARD_ARMS_COVERED_OUTSIDE_THIS_DIRECTORY either - so the guard arm at that call "
                    + "site is reached by nothing, anywhere (gate G47)", site.declaredName())
                .isNotNull()
                .isNotEmpty();
        }
        assertThat(byCallSite.get(CallSite.NONE))
            .as("the cases whose whole assertion is the record sequence declare no substitution at all, "
                + "and they are the majority")
            .isNotNull()
            .isNotEmpty();
    }

    private enum CallSite {
        NONE(null),

        OPEN_TRNXFILE("OPEN-TRNXFILE"),

        READ_XREFFILE("READ-XREFFILE"),

        READ_CUSTFILE("READ-CUSTFILE"),

        READ_ACCTFILE("READ-ACCTFILE"),

        CLOSE_TRNXFILE("CLOSE-TRNXFILE");

        private final String declaredName;

        CallSite(String declaredName) {
            this.declaredName = declaredName;
        }

        private String declaredName() {
            if (declaredName == null) {
                throw new IllegalStateException("CallSite.NONE has no declared name: a case declares no "
                    + "substitution by naming no call site at all.");
            }
            return declaredName;
        }
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
        private static Scenario normal() {
            return new Scenario(CallSite.NONE, null, 0, List.of());
        }

    }

    private static Scenario scenarioOf(ParityCase parityCase) {
        return scenarioFrom(parityCase.caseId(), parityCase.unitStimulus());
    }

    private static Scenario scenarioFrom(String caseId, ParityCase.UnitStimulus stimulus) {
        if (!stimulus.operationScript().isEmpty()) {
            throw new IllegalArgumentException(caseId + " of " + PROGRAM + " declares an "
                + "operationScript. CBSTM03A issues its four file operations through CBSTM03B, whose own "
                + "gate declares the script; here the operations are a consequence of the seed and the "
                + "guard arms, so a script would be a control nothing reads.");
        }
        if (!stimulus.linkage().isEmpty()) {
            throw new IllegalArgumentException(caseId + " of " + PROGRAM + " declares linkage values. "
                + "CBSTM03A takes no PROCEDURE DIVISION USING parameters - app/jcl/CREASTMT.JCL declares "
                + "no PARM on the statement step - so there is no linkage boundary to hand a value "
                + "across.");
        }

        CallSite site = CallSite.NONE;
        String status = null;
        for (Map.Entry<String, ParityCase.CallSiteOutcome> declared
                : stimulus.callSiteOutcomes().entrySet()) {
            if (site != CallSite.NONE) {
                throw new IllegalArgumentException(caseId + " of " + PROGRAM + " declares more than one "
                    + "call-site outcome. One substitution per case, deliberately: the guard arms are "
                    + "reached one at a time and a run that failed at two sites would never reach the "
                    + "second.");
            }
            site = callSiteNamed(caseId, declared.getKey());
            status = statusOf(caseId, declared.getKey(), declared.getValue());
        }

        int bypassExitCode = 0;
        for (Map.Entry<String, Integer> step : stimulus.stepStatuses().entrySet()) {
            if (bypassExitCode != 0) {
                throw new IllegalArgumentException(caseId + " of " + PROGRAM + " declares a non-zero "
                    + "condition code for more than one preceding step. COND=(0,NE) flushes the "
                    + "statement step on the first non-zero code, so a second one could not be the "
                    + "reason the step was bypassed.");
            }
            bypassExitCode = step.getValue();
        }

        List<String> nullUcbDds = stimulus
            .environmentValue(NULL_UCB_DDS_KEY)
            .map(CBSTM03AParityTest::declaredDdNames)
            .orElse(List.of());

        return new Scenario(site, status, bypassExitCode, nullUcbDds);
    }

    private static CallSite callSiteNamed(String caseId, String name) {
        for (CallSite candidate : CallSite.values()) {
            if (candidate != CallSite.NONE && candidate.declaredName().equals(name)) {
                return candidate;
            }
        }
        throw new IllegalArgumentException(caseId + " of " + PROGRAM + " declares a call-site outcome at "
            + "'" + name + "', which is not one of " + substitutableCallSiteNames() + ". A name matching "
            + "no call site would leave the guard arm unreached while the case reads as though it had "
            + "driven it.");
    }

    private static String statusOf(String caseId, String site,
                                   ParityCase.CallSiteOutcome outcome) {
        if (outcome.resp() != null) {
            throw new IllegalArgumentException(caseId + " of " + PROGRAM + " declares a CICS RESP at "
                + site + ". CBSTM03A is a batch program reached by EXEC PGM= in "
                + "app/jcl/CREASTMT.JCL and every one of its I/O verbs reports a two-character FILE "
                + "STATUS; a RESP has no meaning at this call site.");
        }
        if (outcome.isRefused()) {
            throw new IllegalArgumentException(caseId + " of " + PROGRAM + " refuses the call at " + site
                + ". Every status CBSTM03A's guards distinguish is expressible as two printable "
                + "characters, so a refusal would be a status with no COBOL spelling.");
        }
        if (outcome.afterRecords() != null) {
            throw new IllegalArgumentException(caseId + " of " + PROGRAM + " declares afterRecords at "
                + site + ". Which record the substituted status lands on is a property of the seed - the "
                + "sequential read reports it once the seeded window is exhausted - so a count here "
                + "would be a second, contradictory way of saying the same thing.");
        }
        if (outcome.status() == null) {
            throw new IllegalArgumentException(caseId + " of " + PROGRAM + " declares an outcome at "
                + site + " that names no status. Naming a site and nothing else would substitute "
                + "nothing.");
        }
        return outcome.status();
    }

    private static List<String> declaredDdNames(String declared) {
        List<String> names = new ArrayList<>();
        for (String candidate : declared.split(",", -1)) {
            String name = candidate.trim();
            if (name.isEmpty()) {
                throw new IllegalArgumentException("NULL_UCB_DDS declares '" + declared + "', which "
                    + "contains an empty DD name. A blank entry is a control that does nothing.");
            }
            if (!ALL_DD_NAMES.contains(name)) {
                throw new IllegalArgumentException("NULL_UCB_DDS names '" + name + "', which is not one "
                    + "of the six DD names app/jcl/CREASTMT.JCL allocates to the statement step: "
                    + ALL_DD_NAMES + ". A DD the program never opens has no TIOT entry to report on.");
            }
            names.add(name);
        }
        return List.copyOf(names);
    }

    private static List<String> substitutableCallSiteNames() {
        List<String> names = new ArrayList<>();
        for (CallSite candidate : CallSite.values()) {
            if (candidate != CallSite.NONE) {
                names.add(candidate.declaredName());
            }
        }
        return List.copyOf(names);
    }

    private ParityHarness.UnitOutcome drive(ParityCase parityCase,
                                            ParityHarness.Invocation invocation) {
        Scenario scenario = scenarioOf(parityCase);
        ParityHarness.UnitOutcome.Builder recorder = invocation.recorder();

        if (scenario.bypassExitCode() != 0
            && bypassed(scaffolding(bindings()), scenario.bypassExitCode())) {
            return recorder.returnCode(scenario.bypassExitCode()).build();
        }

        JobFixture assembled = fixture(invocation.datasets(), scenario, recorder);

        try {
            assembled.job().printAccountStatements(recorder::display);
        } finally {
            recorder.finalState(StatementGenerationJobA.STMTFILE_DD, STMT_LAYOUT,
                assembled.textSink().retained());
            recorder.finalState(StatementGenerationJobA.HTMLFILE_DD, HTML_LAYOUT,
                assembled.htmlSink().retained());
        }
        return recorder.build();
    }

    private record JobFixture(StatementGenerationJobA job, TextSink textSink, HtmlSink htmlSink) {
    }

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

    private static boolean bypassed(BatchConfig scaffolding, int exitCode) {
        JobExecution execution = new JobExecution(1L);
        StepExecution preceding = execution.createStepExecution(BYPASSING_STEP);
        preceding.setExitStatus(new ExitStatus(Integer.toString(exitCode)));
        FlowExecutionStatus decision =
            scaffolding.precedingExitCodeZeroDecider().decide(execution, preceding);
        return BatchConfig.SKIP.equals(decision);
    }

    @Nested
    @DisplayName("Structural gates")
    class StructuralGates {
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

    private static DatasetBinding ksds(String dsname, int recordLength, int keyLength,
                                       String copybook) {
        return new DatasetBinding(dsname, DatasetBinding.KSDS, false, "FB", null, recordLength,
            copybook, keyLength, 0, null, null);
    }

    private static DatasetBinding sequential(String dsname, int recordLength, int blockSize) {
        return new DatasetBinding(dsname, "sequential", false, "FB", blockSize, recordLength, null,
            null, null, null, null);
    }

    private static BatchConfig scaffolding(DatasetBindings catalogue) {
        JobContracts contracts = new JobContracts();
        contracts.put(StatementGenerationJobA.JOB_KEY, new JobContract(
            StatementGenerationJobA.PROGRAM_ID, List.of(), StatementGenerationJobA.REQUIRED_STEPS,
            null, Map.of()));
        return new BatchConfig(new SuppliedBean<>(mock(JobRepository.class)),
            new SuppliedBean<>(mock(PlatformTransactionManager.class)), contracts, catalogue);
    }

    private static DatasetUnitOfWork unitOfWork(JdbcTemplate template) {
        return new DatasetUnitOfWork(new JdbcTransactionManager(template.getDataSource()));
    }

    private static JdbcTemplate seededTemplate(Map<String, ParityHarness.SeededDataset> seeded) {
        RecordImageDataSource backend = new RecordImageDataSource();
        for (String dd : StatementGenerationJobB.DD_NAMES) {
            ParityHarness.SeededDataset dataset = seeded.get(dd);
            if (dataset == null) {
                continue;
            }
            backend.define(dsnameOf(dd), RECORD_IMAGE_COLUMN, ColumnForm.CHARACTER,
                    dataset.recordLength());
            backend.store().seed(dsnameOf(dd), dataset.rows());
        }
        return new JdbcTemplate(backend);
    }

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

    private static String delimited(String identifier) {
        return '"' + identifier.replace("\"", "\"\"") + '"';
    }

    private static TiotSource tiotSource(Scenario scenario) {
        List<TiotEntry> entries = new ArrayList<>(StatementGenerationJobA.STEP_040_DD_NAMES.size());
        for (String dd : StatementGenerationJobA.STEP_040_DD_NAMES) {
            entries.add(new TiotEntry(dd, !scenario.nullUcbDds().contains(dd)));
        }
        TiotImage image = new TiotImage(StatementGenerationJobA.JCL_JOB_NAME,
            StatementGenerationJobA.STEP_040, entries, TiotEntry.terminator());
        return () -> image;
    }

    private static final class SubstitutingSubroutine extends StatementGenerationJobB {
        private final Scenario scenario;

        private SubstitutingSubroutine(JdbcTemplate template, DatasetBindings catalogue,
                                       Scenario scenario) {
            super(template, catalogue, ASCII, IMAGE_FORM, new TrnxRepository(catalogue));
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

        private Response substituted(CallSite site, String siteDd, String dd, Response real) {
            if (scenario.site() != site || !siteDd.equals(dd)) {
                return real;
            }
            return new Response(scenario.status(), real.fldt());
        }
    }

    private static final class TextSink implements StatementTextWriter.RecordSink {
        private final ParityHarness.UnitOutcome.Builder recorder;

        private final List<String> retained = new ArrayList<>();

        private TextSink(ParityHarness.UnitOutcome.Builder recorder) {
            this.recorder = recorder;
        }

        /**
         * {@code OPEN OUTPUT STMT-FILE}, {@code app/cbl/CBSTM03A.CBL:L293}.
         *
         * @return {@link FileStatus.Outcome#OK}; the COBOL's {@code OPEN} carries no {@code FILE STATUS}
         *     clause and no guard, so there is no failure arm to model
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

        private List<String> retained() {
            return List.copyOf(retained);
        }
    }

    private static final class HtmlSink implements StatementHtmlWriter.HtmlRecordSink {
        private final ParityHarness.UnitOutcome.Builder recorder;

        private final List<String> retained = new ArrayList<>();

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

        private List<String> retained() {
            return List.copyOf(retained);
        }
    }

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
