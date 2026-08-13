package com.vsergeychik.carddemo.parity;

import com.vsergeychik.carddemo.account.model.AccountRecord;
import com.vsergeychik.carddemo.card.model.CardXrefRecord;
import com.vsergeychik.carddemo.common.FileStatus;
import com.vsergeychik.carddemo.common.FixedWidthRecord.RecordLayout;
import com.vsergeychik.carddemo.common.RecordImageForm;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBinding;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBindings;
import com.vsergeychik.carddemo.statement.StatementGenerationJobB;
import com.vsergeychik.carddemo.statement.TrnxRepository;
import com.vsergeychik.carddemo.statement.StatementGenerationJobB.Operation;
import com.vsergeychik.carddemo.statement.StatementGenerationJobB.Request;
import com.vsergeychik.carddemo.statement.StatementGenerationJobB.Response;
import com.vsergeychik.carddemo.statement.StatementGenerationJobB.Session;
import com.vsergeychik.carddemo.statement.model.Stm03CustomerRecord;
import com.vsergeychik.carddemo.statement.model.TrnxRecord;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.step.tasklet.Tasklet;
import com.vsergeychik.carddemo.testdataset.RecordImageDataSource;
import com.vsergeychik.carddemo.testdataset.RecordImageStore.ColumnForm;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The twenty-case parity gate for {@code CBSTM03B}, the statement job's data-access subroutine.
 */
@DisplayName("CBSTM03B parity - the statement job's four-file, six-operation data-access contract")
class CBSTM03BParityTest {
    private static final String PROGRAM = "CBSTM03B";

    private static final String AREA_DATASET = "M03BAREA";

    private static final String UNRECOGNISED_DD = "NOSUCHDD";

    private static final String TRNXFILE_UNUSED_KEY = "0500024453765740000000005";

    private static final Charset DATASET_CHARSET = StandardCharsets.US_ASCII;

    private static final String RECORD_IMAGE_COLUMN = "REC";

    private static final String TRNXFILE_DSNAME = "TEST.M2.CARDDEMO.TRXFL.VSAM.KSDS";

    private static final String XREFFILE_DSNAME = "TEST.M2.CARDDEMO.CARDXREF.VSAM.KSDS";

    private static final String CUSTFILE_DSNAME = "TEST.M2.CARDDEMO.CUSTDATA.VSAM.KSDS";

    private static final String ACCTFILE_DSNAME = "TEST.M2.CARDDEMO.ACCTDATA.VSAM.KSDS";

    private static final int EXPECTED_RETURN_CODE = 0;

    static List<ParityCase> cases() {
        return ParityHarness.casesOf(PROGRAM);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    @DisplayName("the case's diff count is zero")
    void theCaseProducesNoDifference(ParityCase parityCase) {
        ParityHarness harness = ParityHarness.usAscii();

        FieldDiffer.DiffResult result = harness.judge(parityCase, ParityCase.UnitKind.COMPONENT,
            invocation -> drive(parityCase, invocation));

        assertThat(result.count())
            .as("the parity gate for %s/%s. A module is not complete until its diff count is zero "
                    + "across all twenty of its cases, so this is the assertion the gate is stated "
                    + "in terms of. Every difference found, rendered in full:%n%s",
                PROGRAM, parityCase.caseId(), result.render())
            .isZero();
    }

    @Test
    @DisplayName("exactly twenty cases exist, named case01 through case20, all declaring CBSTM03B")
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
                .as("every case in parity/%s/ must declare that program, or it would be judged "
                    + "against expectations belonging to another one", PROGRAM)
                .isEqualTo(PROGRAM);
            assertThat(parityCase.unitKind())
                .as("%s is a called subroutine reached through a typed method, so its cases are "
                    + "COMPONENT. Declaring BATCH_JOB would let a case carry job parameters this "
                    + "program has no way to receive.", PROGRAM)
                .isEqualTo(ParityCase.UnitKind.COMPONENT);
            assertThat(parityCase.expectedReturnCode())
                .as("%s never assigns RETURN-CODE - it GOBACKs without touching it - so every case "
                    + "expects zero", PROGRAM)
                .isEqualTo(EXPECTED_RETURN_CODE);
            assertThat(parityCase.expectedMessages())
                .as("%s contains no DISPLAY statement at all; every diagnostic in the statement job "
                    + "is emitted by CBSTM03A, which decides what a status means", PROGRAM)
                .isEmpty();
            identifiers.add(parityCase.caseId());
        }

        List<String> expected = new ArrayList<>(ParityHarness.CASES_PER_PROGRAM);
        for (int ordinal = 1; ordinal <= ParityHarness.CASES_PER_PROGRAM; ordinal++) {
            expected.add(ParityHarness.caseId(ordinal));
        }
        assertThat(identifiers)
            .as("the case identifiers, in ascending order and each appearing once")
            .containsExactlyElementsOf(expected);
    }

    @Test
    @DisplayName("declares a decodable script per case, one area image per CALL, all six op codes")
    void everyCaseDeclaresADecodableCallScript() {
        Set<Operation> exercised = EnumSet.noneOf(Operation.class);
        for (ParityCase parityCase : cases()) {
            List<Call> steps = scriptOf(parityCase);

            assertThat(steps)
                .as("the call script for %s/%s. A case with no CALL would assert nothing about a "
                    + "subroutine whose entire behaviour is what one CALL leaves in LK-M03B-AREA.",
                    PROGRAM, parityCase.caseId())
                .isNotEmpty();
            assertThat(parityCase.expectedWrites())
                .as("%s/%s must pin one 1040-byte area image per CALL: %d call(s) were scripted, so "
                    + "an expectation set of a different size would either leave a call unasserted "
                    + "or assert one that never happened",
                    PROGRAM, parityCase.caseId(), steps.size())
                .hasSize(steps.size());
            steps.forEach(step -> exercised.add(step.oper()));
        }

        assertThat(exercised)
            .as("all six of the 88-level operation codes app/cbl/CBSTM03B.CBL:103-108 declares must be "
                + "issued by some case, including the two the source itself never tests")
            .containsExactlyInAnyOrder(Operation.values());
    }

    @Nested
    @DisplayName("Structural gates")
    class StructuralGates {
        @Test
        @DisplayName("G12 - it is a @Component; no Job, Step or Tasklet originates from this type")
        void theSubroutineIsAComponentAndNotABatchJob() {
            Class<?> subject = StatementGenerationJobB.class;

            assertThat(Job.class.isAssignableFrom(subject))
                .as("app/cbl/CBSTM03B.CBL's header reads 'Type : BATCH COBOL Subroutine', there is "
                    + "no EXEC PGM=CBSTM03B anywhere in app/jcl, app/proc or app/csd, and "
                    + "CBSTM03A calls it thirteen times. The mandated name StatementGenerationJobB "
                    + "is honoured verbatim under rule R1, but a name never authorises a change of "
                    + "shape (implicit requirement I3).")
                .isFalse();
            assertThat(Step.class.isAssignableFrom(subject))
                .as("a Step would imply a place in a job flow, and there is no job to flow through")
                .isFalse();
            assertThat(Tasklet.class.isAssignableFrom(subject))
                .as("a Tasklet would imply the subroutine is a unit of work a step executes, when "
                    + "it is a collaborator another unit of work calls")
                .isFalse();

            for (Method method : subject.getDeclaredMethods()) {
                if (Modifier.isPrivate(method.getModifiers())) {
                    continue;
                }
                Class<?> returned = method.getReturnType();
                assertThat(Job.class.isAssignableFrom(returned)
                        || Step.class.isAssignableFrom(returned)
                        || Tasklet.class.isAssignableFrom(returned))
                    .as("method %s must not hand out a Job, a Step or a Tasklet: a factory method "
                        + "for one is the same claim as implementing one, made indirectly",
                        method.getName())
                    .isFalse();
            }

            assertThat(subject.getAnnotation(Component.class))
                .as("the subroutine reaches the context by component scanning, which is what "
                    + "'@Component, not a Job' means in wiring terms")
                .isNotNull();
        }

        @Test
        @DisplayName("LK-M03B-AREA is 8 + 1 + 2 + 25 + 4 + 1000 = 1040 bytes, at those offsets")
        void theLinkageAreaIsOneThousandAndFortyBytes() {
            assertThat(StatementGenerationJobB.DD_OFFSET).isZero();
            assertThat(StatementGenerationJobB.DD_LENGTH).isEqualTo(8);
            assertThat(StatementGenerationJobB.OPER_OFFSET).isEqualTo(8);
            assertThat(StatementGenerationJobB.OPER_LENGTH).isEqualTo(1);
            assertThat(StatementGenerationJobB.RC_OFFSET).isEqualTo(9);
            assertThat(StatementGenerationJobB.RC_LENGTH).isEqualTo(FileStatus.STATUS_LENGTH);
            assertThat(StatementGenerationJobB.KEY_OFFSET).isEqualTo(11);
            assertThat(StatementGenerationJobB.KEY_LENGTH).isEqualTo(25);
            assertThat(StatementGenerationJobB.KEY_LN_OFFSET).isEqualTo(36);
            assertThat(StatementGenerationJobB.KEY_LN_LENGTH).isEqualTo(4);
            assertThat(StatementGenerationJobB.FLDT_OFFSET).isEqualTo(40);
            assertThat(StatementGenerationJobB.FLDT_LENGTH).isEqualTo(1000);
            assertThat(StatementGenerationJobB.AREA_LENGTH)
                .as("PIC S9(4) with no USAGE clause is four zoned DISPLAY bytes and not a two-byte "
                    + "halfword; getting that wrong would move LK-M03B-FLDT and displace every "
                    + "record area this suite pins")
                .isEqualTo(1040);
            assertThat(StatementGenerationJobB.AREA_LAYOUT.recordLength())
                .as("the layout the fingerprint is decoded through must agree with the arithmetic")
                .isEqualTo(StatementGenerationJobB.AREA_LENGTH);
        }

        @Test
        @DisplayName("the four dispatch arms are TRNXFILE, XREFFILE, CUSTFILE, ACCTFILE in that order")
        void theDispatchOrderIsTheSourceOrder() {
            assertThat(StatementGenerationJobB.DD_NAMES)
                .as("EVALUATE is ordered and WHEN OTHER is last, so the arms are enumerated in "
                    + "source order and an unrecognised name reaches GO TO 9999-GOBACK")
                .containsExactly("TRNXFILE", "XREFFILE", "CUSTFILE", "ACCTFILE");
            assertThat(StatementGenerationJobB.DD_NAMES)
                .as("the WHEN OTHER DD name case 19 uses must not be one of the four, or that case "
                    + "would silently exercise a dispatch arm instead of the default")
                .doesNotContain(UNRECOGNISED_DD);
        }

        @Test
        @DisplayName("SEQUENTIAL honours OPEN/READ/CLOSE, RANDOM honours OPEN/READ-K/CLOSE, and "
            + "'W' and 'Z' are honoured nowhere")
        void theCapabilityMatrixIsAsymmetricAndReadOnly() {
            StatementGenerationJobB subroutine = subroutine(new JdbcTemplate());

            assertThat(subroutine.supportedOperations("TRNXFILE"))
                .as("1000-TRNXFILE-PROC tests M03B-OPEN (:135), M03B-READ (:140) and M03B-CLOSE "
                    + "(:146) - never M03B-READ-K, because the SELECT is ACCESS MODE IS SEQUENTIAL")
                .containsExactlyInAnyOrder(Operation.OPEN, Operation.READ, Operation.CLOSE);
            assertThat(subroutine.supportedOperations("XREFFILE"))
                .as("2000-XREFFILE-PROC tests the same three at :159, :164 and :170")
                .containsExactlyInAnyOrder(Operation.OPEN, Operation.READ, Operation.CLOSE);
            assertThat(subroutine.supportedOperations("CUSTFILE"))
                .as("3000-CUSTFILE-PROC tests M03B-OPEN (:183), M03B-READ-K (:188) and M03B-CLOSE "
                    + "(:195) - never a plain M03B-READ, because the SELECT is ACCESS MODE IS RANDOM")
                .containsExactlyInAnyOrder(Operation.OPEN, Operation.READ_K, Operation.CLOSE);
            assertThat(subroutine.supportedOperations("ACCTFILE"))
                .as("4000-ACCTFILE-PROC tests the same three at :208, :213 and :220")
                .containsExactlyInAnyOrder(Operation.OPEN, Operation.READ_K, Operation.CLOSE);

            for (String dd : StatementGenerationJobB.DD_NAMES) {
                assertThat(subroutine.supportedOperations(dd))
                    .as("every SELECT is OPEN INPUT and no paragraph contains a WRITE or a REWRITE, "
                        + "so 'W' (:107) and 'Z' (:108) are declared and dead on %s. Preserved "
                        + "unimplemented under practice B5; cases 17 and 18 assert the no-op.", dd)
                    .doesNotContain(Operation.WRITE, Operation.REWRITE);
            }
            assertThat(Operation.WRITE.declaredButUnusedInSource()).isTrue();
            assertThat(Operation.REWRITE.declaredButUnusedInSource()).isTrue();
            assertThat(Operation.OPEN.declaredButUnusedInSource()).isFalse();
            assertThat(Operation.READ.declaredButUnusedInSource()).isFalse();
            assertThat(Operation.READ_K.declaredButUnusedInSource()).isFalse();
            assertThat(Operation.CLOSE.declaredButUnusedInSource()).isFalse();
        }

        @Test
        @DisplayName("the four FD splits confirm the copybook widths, and the key widths are 9 and 11")
        void theFdSplitsConfirmTheCopybookWidths() {
            assertThat(StatementGenerationJobB.TRNXFILE_RECORD_LENGTH)
                .as("FD-TRNX-CARD X(16) + FD-TRNX-ID X(16) + FD-ACCT-DATA X(318), and "
                    + "app/cpy/COSTM01.CPY's TRNX-RECORD")
                .isEqualTo(350)
                .isEqualTo(TrnxRecord.RECORD_LENGTH);
            assertThat(StatementGenerationJobB.TRNXFILE_KEY_LENGTH)
                .as("FD-TRNXS-ID is the 32-byte composite TRNX-CARD-NUM + TRNX-ID")
                .isEqualTo(32);
            assertThat(StatementGenerationJobB.XREFFILE_RECORD_LENGTH)
                .as("FD-XREF-CARD-NUM X(16) + FD-XREF-DATA X(34), and app/cpy/CVACT03Y.cpy")
                .isEqualTo(50)
                .isEqualTo(CardXrefRecord.RECORD_LENGTH);
            assertThat(StatementGenerationJobB.CUSTFILE_RECORD_LENGTH)
                .as("FD-CUST-ID X(09) + FD-CUST-DATA X(491), modelled as Stm03CustomerRecord from "
                    + "app/cpy/CUSTREC.cpy so CUST-DOB-YYYYMMDD stays distinct from CVCUS01Y's "
                    + "CUST-DOB-YYYY-MM-DD")
                .isEqualTo(500)
                .isEqualTo(Stm03CustomerRecord.RECORD_LENGTH);
            assertThat(StatementGenerationJobB.ACCTFILE_RECORD_LENGTH)
                .as("FD-ACCT-ID 9(11) + FD-ACCT-DATA X(289), and app/cpy/CVACT01Y.cpy")
                .isEqualTo(300)
                .isEqualTo(AccountRecord.RECORD_LENGTH);

            assertThat(StatementGenerationJobB.TRNXFILE_ACCT_DATA_LENGTH)
                .as("FD-ACCT-DATA inside FD-TRNXFILE-REC (CBSTM03B.CBL:63)")
                .isEqualTo(318);
            assertThat(StatementGenerationJobB.ACCTFILE_ACCT_DATA_LENGTH)
                .as("FD-ACCT-DATA inside FD-ACCTFILE-REC (:78) - the SAME COBOL field name over a "
                    + "different width, legal because the two sit in different FD records. Case 16 "
                    + "reads both in one run so they cannot be conflated.")
                .isEqualTo(289)
                .isNotEqualTo(StatementGenerationJobB.TRNXFILE_ACCT_DATA_LENGTH);

            assertThat(StatementGenerationJobB.CUSTFILE_CALLER_KEY_LENGTH)
                .as("app/cbl/CBSTM03A.CBL:374 COMPUTEs it from LENGTH OF XREF-CUST-ID PIC 9(09)")
                .isEqualTo(9);
            assertThat(StatementGenerationJobB.ACCTFILE_CALLER_KEY_LENGTH)
                .as("app/cbl/CBSTM03A.CBL:398 COMPUTEs it from LENGTH OF XREF-ACCT-ID PIC 9(11)")
                .isEqualTo(11);
        }

        @Test
        @DisplayName("an untouched FILE STATUS is two spaces and an untouched record area is 1000")
        void anUntouchedAreaIsSpaces() {
            assertThat(StatementGenerationJobB.UNTOUCHED_STATUS)
                .as("CBSTM03B.CBL:83-97 declares the four status groups with no VALUE clause, so "
                    + "before any I/O each holds SPACES - a value no operation can produce, which "
                    + "is why case 15 can assert it")
                .isEqualTo("  ")
                .hasSize(FileStatus.STATUS_LENGTH);
            assertThat(StatementGenerationJobB.SPACES_FLDT)
                .as("LK-M03B-FLDT PIC X(1000); MOVE SPACES TO WS-M03B-FLDT is what "
                    + "app/cbl/CBSTM03A.CBL:350, :376, :400, :745 and :834 establish before a read")
                .hasSize(StatementGenerationJobB.FLDT_LENGTH)
                .isBlank();
        }
    }

    /**
     * One {@code CALL 'CBSTM03B' USING WS-M03B-AREA}, together with what the caller establishes in the
     * shared area immediately before it.
     *
     * @param dd the {@code LK-M03B-DD} value; one of the four names, or an unrecognised one for the
     *     {@code WHEN OTHER} arm
     * @param oper the {@code LK-M03B-OPER} operation, including the two that are declared and dead
     * @param key the {@code LK-M03B-KEY} value, empty for a non-keyed operation - {@link Request} fits it
     *     to the 25-byte span under the {@code PIC X} rule
     * @param keyLength the {@code LK-M03B-KEY-LN} value the caller computed; {@code 0} for a non-keyed
     *     operation, which is rendered as the signed zoned image "000&#123;" - a brace
     * @param zeroRc whether the caller issued {@code MOVE ZERO TO WS-M03B-RC} first
     * @param clearFldt whether the caller issued {@code MOVE SPACES TO WS-M03B-FLDT} first
     */
    private record Call(String dd, Operation oper, String key, int keyLength, boolean zeroRc,
                        boolean clearFldt) {
    }

    private static List<Call> scriptOf(ParityCase parityCase) {
        ParityCase.UnitStimulus stimulus = parityCase.unitStimulus();
        if (!stimulus.callSiteOutcomes().isEmpty()) {
            throw new IllegalArgumentException(parityCase.caseId() + " of " + PROGRAM + " declares a "
                + "callSiteOutcome. CBSTM03B is the call site: every status it reports it computes from "
                + "the DD's own FILE STATUS area over the seeded relation, so a substituted outcome here "
                + "would replace the subject with the substitution.");
        }
        if (!stimulus.stepStatuses().isEmpty()) {
            throw new IllegalArgumentException(parityCase.caseId() + " of " + PROGRAM + " declares a "
                + "step status. CBSTM03B is a called subprogram with no EXEC PGM= anywhere in app/jcl, "
                + "so no preceding job step gates it.");
        }
        if (!stimulus.environment().isEmpty()) {
            throw new IllegalArgumentException(parityCase.caseId() + " of " + PROGRAM + " declares an "
                + "environment variant. CBSTM03B reads four relations and a linkage area and nothing "
                + "else; none of the permitted keys names anything it can observe.");
        }

        List<ParityCase.ScriptedOperation> declared = stimulus.operationScript();
        if (declared.isEmpty()) {
            throw new IllegalArgumentException(parityCase.caseId() + " of " + PROGRAM + " declares no "
                + "operationScript. The sequence of CALLs is the whole behaviour of a subroutine whose "
                + "contract is one DD name and one operation code, so a case with no script asserts "
                + "nothing about it. Declare the calls in the case file, in issue order.");
        }

        List<Call> script = new ArrayList<>(declared.size());
        for (ParityCase.ScriptedOperation operation : declared) {
            script.add(callFrom(parityCase.caseId(), operation));
        }
        return List.copyOf(script);
    }

    private static Call callFrom(String caseId, ParityCase.ScriptedOperation declared) {
        Operation oper = operationNamed(caseId, declared.operation());
        boolean primesStatus = declared.status() != null;
        if (primesStatus && !FileStatus.OK.equals(declared.status())) {
            throw new IllegalArgumentException(caseId + " of " + PROGRAM + " declares status '"
                + declared.status() + "' on a scripted call. The only value a real call site hands in is "
                + "the '" + FileStatus.OK + "' of MOVE ZERO TO WS-M03B-RC; any other value would be a "
                + "status this gate invented rather than one CBSTM03B computed. Omit the member to carry "
                + "the previous call's status forward, which is how a stale status is observed.");
        }
        if ((declared.key() == null) != (declared.keyLength() == null)) {
            throw new IllegalArgumentException(caseId + " of " + PROGRAM + " declares a key without a "
                + "length, or a length without a key. LK-M03B-KEY is read as "
                + "LK-M03B-KEY (1:LK-M03B-KEY-LN), so one without the other names no bytes.");
        }
        String key = declared.key() == null ? Request.blankKey() : declared.key();
        int keyLength = declared.keyLength() == null ? 0 : declared.keyLength();
        return new Call(declared.dd(), oper, key, keyLength, primesStatus,
            declared.primesRecordAreaOrDefault());
    }

    private static Operation operationNamed(String caseId, String code) {
        for (Operation candidate : Operation.values()) {
            if (candidate.image().equals(code)) {
                return candidate;
            }
        }
        throw new IllegalArgumentException(caseId + " of " + PROGRAM + " declares operation '" + code
            + "', which is not one of the six 88-level codes app/cbl/CBSTM03B.CBL:103-108 declares.");
    }

    private ParityHarness.UnitOutcome drive(ParityCase parityCase,
                                            ParityHarness.Invocation invocation) {
        Map<String, ParityHarness.SeededDataset> seeded = invocation.datasets();
        RecordImageDataSource backend = seededBackend(seeded);
        StatementGenerationJobB subroutine = subroutine(new JdbcTemplate(backend));
        ParityHarness.UnitOutcome.Builder recorder = invocation.recorder();

        String rc = StatementGenerationJobB.UNTOUCHED_STATUS;
        String fldt = StatementGenerationJobB.SPACES_FLDT;
        try (Session session = subroutine.newSession()) {
            for (Call step : scriptOf(parityCase)) {
                if (step.zeroRc()) {
                    rc = FileStatus.OK;
                }
                if (step.clearFldt()) {
                    fldt = StatementGenerationJobB.SPACES_FLDT;
                }
                Request request = new Request(step.dd(), step.oper(), rc, step.key(),
                    step.keyLength(), fldt);
                Response response = subroutine.call(session, request);
                recorder.wroteBytes(AREA_DATASET, StatementGenerationJobB.AREA_LAYOUT,
                    subroutine.toAreaImage(request, response));
                rc = response.rc();
                fldt = response.fldt();
            }
        }

        for (String dd : StatementGenerationJobB.DD_NAMES) {
            if (!seeded.containsKey(dd)) {
                continue;
            }
            recorder.finalState(dd, layoutOf(dd), storedRows(backend, dd));
        }
        return recorder.build();
    }

    private static StatementGenerationJobB subroutine(JdbcTemplate template) {
        DatasetBindings catalogue = bindings();
        return new StatementGenerationJobB(template, catalogue, DATASET_CHARSET,
            RecordImageForm.CHARACTER, new TrnxRepository(catalogue));
    }

    private static RecordImageDataSource seededBackend(
            Map<String, ParityHarness.SeededDataset> seeded) {
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
        return backend;
    }

    private static List<String> storedRows(RecordImageDataSource backend, String dd) {
        List<String> rows = new ArrayList<>(backend.store().rows(dsnameOf(dd)));
        rows.sort(java.util.Comparator.naturalOrder());
        return rows;
    }

    private static DatasetBindings bindings() {
        DatasetBindings catalogue = new DatasetBindings();
        catalogue.put(StatementGenerationJobB.TRNXFILE_DD, ksds(TRNXFILE_DSNAME,
            TrnxRecord.RECORD_LENGTH, TrnxRecord.TRNX_KEY_LENGTH, "COSTM01"));
        catalogue.put(StatementGenerationJobB.XREFFILE_DD, ksds(XREFFILE_DSNAME,
            CardXrefRecord.RECORD_LENGTH, CardXrefRecord.XREF_CARD_NUM_LENGTH, "CVACT03Y"));
        catalogue.put(StatementGenerationJobB.CUSTFILE_DD, ksds(CUSTFILE_DSNAME,
            Stm03CustomerRecord.RECORD_LENGTH, Stm03CustomerRecord.KEY_LENGTH, "CUSTREC"));
        catalogue.put(StatementGenerationJobB.ACCTFILE_DD, ksds(ACCTFILE_DSNAME,
            AccountRecord.RECORD_LENGTH, AccountRecord.ACCT_ID_LENGTH, "CVACT01Y"));
        return catalogue;
    }

    private static DatasetBinding ksds(String dsname, int recordLength, int keyLength,
                                       String copybook) {
        return new DatasetBinding(dsname, DatasetBinding.KSDS, false, "FB", null, recordLength,
            copybook, keyLength, null, null, null);
    }

    private static RecordLayout layoutOf(String dd) {
        return switch (dd) {
            case StatementGenerationJobB.TRNXFILE_DD -> TrnxRecord.layout();
            case StatementGenerationJobB.XREFFILE_DD -> CardXrefRecord.LAYOUT;
            case StatementGenerationJobB.CUSTFILE_DD -> Stm03CustomerRecord.LAYOUT;
            case StatementGenerationJobB.ACCTFILE_DD -> AccountRecord.LAYOUT;
            default -> throw new IllegalArgumentException("'" + dd + "' names no layout: "
                + PROGRAM + " reads exactly the four datasets "
                + StatementGenerationJobB.DD_NAMES + ", and a fifth would be a dataset the "
                + "subroutine has no FD for.");
        };
    }

    private static String dsnameOf(String dd) {
        return switch (dd) {
            case StatementGenerationJobB.TRNXFILE_DD -> TRNXFILE_DSNAME;
            case StatementGenerationJobB.XREFFILE_DD -> XREFFILE_DSNAME;
            case StatementGenerationJobB.CUSTFILE_DD -> CUSTFILE_DSNAME;
            case StatementGenerationJobB.ACCTFILE_DD -> ACCTFILE_DSNAME;
            default -> throw new IllegalArgumentException("'" + dd + "' names no dataset: the "
                + "bindings this suite constructs are exactly "
                + StatementGenerationJobB.DD_NAMES);
        };
    }

    private static String delimited(String identifier) {
        return '"' + identifier.replace("\"", "\"\"") + '"';
    }
}
