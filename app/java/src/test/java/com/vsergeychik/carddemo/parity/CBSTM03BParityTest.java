package com.vsergeychik.carddemo.parity;

import com.vsergeychik.carddemo.account.model.AccountRecord;
import com.vsergeychik.carddemo.card.model.CardXrefRecord;
import com.vsergeychik.carddemo.common.FileStatus;
import com.vsergeychik.carddemo.common.FixedWidthRecord.RecordLayout;
import com.vsergeychik.carddemo.common.RecordImageForm;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBinding;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBindings;
import com.vsergeychik.carddemo.statement.StatementGenerationJobB;
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
 *
 * <h2>What this class is the gate for</h2>
 * <p>{@code app/cbl/CBSTM03B.CBL}'s {@code LINKAGE SECTION} is a repository interface expressed in
 * COBOL - a generic four-file, six-operation data-access API - and it is the reason
 * {@code app/cbl/CBSTM03A.CBL} declares only its two <em>outputs</em> while all four of the
 * statement job's <em>inputs</em> live here. These twenty cases are therefore the specification of
 * the statement job's entire data layer, which is why this suite is authored before
 * {@code CBSTM03A}'s: everything {@code CBSTM03A} observes, it observes through this contract.
 *
 * <h2>The baseline is statically derived, never captured (AAP risk R-A, practice B12)</h2>
 * <p>The expected values in {@code src/test/resources/parity/CBSTM03B/case01..case20.json} were
 * <strong>derived by structured reading of the COBOL source</strong>, cross-checked against the
 * copybook byte layouts ({@code app/cpy/COSTM01.CPY}, {@code app/cpy/CVACT03Y.cpy},
 * {@code app/cpy/CUSTREC.cpy}, {@code app/cpy/CVACT01Y.cpy}), the DD and {@code SORT} contracts in
 * {@code app/jcl/CREASTMT.JCL}, and the real fixture data in {@code app/data/ASCII}. They were
 * <strong>not</strong> captured from a live COBOL execution, because no such execution is possible
 * in this environment. {@code CBSTM03B} is one of the programs that cannot even be <em>built</em>
 * by the only COBOL compiler available: {@code cobc} refuses it at {@code CBSTM03B:116} with
 * "executable program requested but PROCEDURE/ENTRY has USING clause", since
 * {@code PROCEDURE DIVISION USING LK-M03B-AREA} is a subprogram entry and not a {@code -x} main.
 * Seven of the twenty-eight programs additionally use {@code ORGANIZATION INDEXED}, which that
 * build reports as {@code indexed file handler : disabled}, and no Language Environment
 * {@code CEE*} service exists there either. The deviation is a change of <em>provenance only</em> -
 * twenty cases per program, field-for-field diffing and a diff count of zero per module all stand -
 * and it is recorded rather than absorbed (practice B12).
 *
 * <p>Because a statically derived expectation can encode a misreading, the derivation deliberately
 * avoids the one shortcut that would make this suite worthless: it never asks
 * {@link StatementGenerationJobB} what it produces. The case files were computed from the copybook
 * offsets, the shipped fixtures and the COBOL rules alone, so a passing comparison is independent
 * evidence rather than the implementation compared with itself.
 *
 * <h2>The unit is a {@code @Component}, not a Spring Batch job (gate G12)</h2>
 * <p>The prompt-mandated class name is {@code StatementGenerationJobB}, and the name is honoured
 * verbatim under rule R1 - but the name is the only thing job-like about it. The source header of
 * {@code app/cbl/CBSTM03B.CBL} reads {@code Type : BATCH COBOL Subroutine}; there is no
 * {@code EXEC PGM=CBSTM03B} anywhere in {@code app/jcl}, {@code app/proc} or {@code app/csd}; and
 * {@code CBSTM03A} calls it thirteen times ({@code :351}, {@code :377}, {@code :401}, {@code :734},
 * {@code :746}, {@code :769}, {@code :787}, {@code :805}, {@code :835}, {@code :860}, {@code :877},
 * {@code :893}, {@code :909}). It is therefore a Spring {@code @Component} - implicit requirement
 * I3 - and {@link StructuralGates} asserts that no {@code Job}, {@code Step} or {@code Tasklet}
 * originates from the type, so the divergence between its name and its nature is checked and not
 * merely documented (practice B4).
 *
 * <h2>{@code 'W'} and {@code 'Z'} are declared and dead, and stay that way (practice B5)</h2>
 * <p>{@code 88 M03B-WRITE VALUE 'W'} ({@code CBSTM03B.CBL:107}) and
 * {@code 88 M03B-REWRITE VALUE 'Z'} ({@code :108}) are declared in the linkage area and tested by
 * no {@code IF} in any of the four paragraphs. All four {@code SELECT}s are opened
 * {@code INPUT} and no {@code WRITE} or {@code REWRITE} statement exists in the program. Cases 17
 * and 18 assert the declared-but-unsupported behaviour <em>as it is</em> - a silent no-op returning
 * the file's stale status, with the seeded dataset unchanged afterwards. Implementing write support
 * would be a new feature and a parity violation.
 *
 * <h2>The contract these twenty cases pin</h2>
 * <p>{@code 01 LK-M03B-AREA} ({@code CBSTM03B.CBL:100-112}) is {@code LK-M03B-DD PIC X(08)} +
 * {@code LK-M03B-OPER PIC X(01)} + {@code LK-M03B-RC PIC X(02)} + {@code LK-M03B-KEY PIC X(25)} +
 * {@code LK-M03B-KEY-LN PIC S9(4)} + {@code LK-M03B-FLDT PIC X(1000)} =
 * {@value StatementGenerationJobB#AREA_LENGTH} bytes, and the six {@code 88}-levels on
 * {@code LK-M03B-OPER} are {@code 'O'}, {@code 'C'}, {@code 'R'}, {@code 'K'}, {@code 'W'} and
 * {@code 'Z'}. That area <strong>is</strong> the observable behaviour of one {@code CALL}, so every
 * case's fingerprint is the ordered transcript of the area images the calls left behind, pinned
 * byte for byte at 1040 bytes each. The subroutine returns <strong>raw, space-padded record
 * spans</strong> and decodes nothing into a model type, which is why an expectation states the
 * 1000-byte {@code LK-M03B-FLDT} span - a meaningful prefix of 350, 50, 500 or 300 bytes followed
 * by spaces - rather than a decoded object.
 *
 * <p>The dispatch is {@code EVALUATE LK-M03B-DD} in exactly this order ({@code :118-128}):
 * {@code 'TRNXFILE'} to {@code 1000-TRNXFILE-PROC THRU 1999-EXIT}, {@code 'XREFFILE'} to
 * {@code 2000}-{@code 2999}, {@code 'CUSTFILE'} to {@code 3000}-{@code 3999}, {@code 'ACCTFILE'} to
 * {@code 4000}-{@code 4999}, and {@code WHEN OTHER} to {@code GO TO 9999-GOBACK} - which performs
 * no I/O and, because the {@code GO TO} jumps past every {@code nnn900-EXIT}, leaves
 * {@code LK-M03B-RC} and {@code LK-M03B-FLDT} <strong>untouched</strong>. Case 19 exercises that
 * arm and asserts the status is the <em>stale prior</em> value rather than a {@code '00'} (gate
 * G48).
 *
 * <p>The capability matrix is <strong>asymmetric</strong> and read-only: {@code TRNXFILE} and
 * {@code XREFFILE} are {@code ACCESS MODE IS SEQUENTIAL} and honour {@code OPEN}, sequential
 * {@code READ} and {@code CLOSE}; {@code CUSTFILE} and {@code ACCTFILE} are
 * {@code ACCESS MODE IS RANDOM} and honour {@code OPEN}, keyed {@code READ} and {@code CLOSE}; all
 * four are {@code OPEN INPUT}. An unsupported operation matches no {@code IF} and falls out of the
 * third one into {@code nnn900-EXIT}, whose {@code MOVE <dd>FILE-STATUS TO LK-M03B-RC} is outside
 * every {@code IF} and still runs - so it is a documented no-op that returns the file's
 * last-known status, and cases 12, 15, 17 and 18 assert exactly that rather than an error.
 *
 * <p>There are <strong>four independent two-byte {@code FILE STATUS} areas</strong>, one per
 * {@code SELECT} ({@code :83-97}), none of them carrying a {@code VALUE} clause and therefore all
 * of them holding spaces before any I/O. Case 15 asserts that untouched initial value and case 20
 * asserts the independence as a ten-call interleaving (gate G47).
 *
 * <p>The four {@code FD} splits independently confirm the copybook widths - {@code TRNXFILE}
 * {@code 16 + 16 + X(318) = 350}, {@code XREFFILE} {@code X(16) + X(34) = 50}, {@code CUSTFILE}
 * {@code X(09) + X(491) = 500}, {@code ACCTFILE} {@code 9(11) + X(289) = 300} - and one source
 * quirk survives into the expectations: {@code FD-ACCT-DATA} names <strong>both</strong> the
 * {@code TRNXFILE} 318-byte data span ({@code :63}) and the {@code ACCTFILE} 289-byte data span
 * ({@code :78}), which is legal COBOL because they sit in different {@code FD} records. Case 16
 * reads both in one run so the two spans are stated side by side and cannot be conflated.
 *
 * <h2>How the unit is reached (gate G51)</h2>
 * <p>Through its typed methods, as a plain object: the subroutine is constructed with a
 * {@link JdbcTemplate} over a per-case in-memory relation seeded from the harness's own fixtures,
 * a four-entry {@link DatasetBindings} catalogue, an explicitly named code page and an explicit
 * {@link RecordImageForm}. There is no batch job launcher, no mock MVC layer, no servlet container
 * and no Spring application context anywhere in the path, and no production dataset name from
 * {@code app/csd/CARDDEMO.CSD} is written as a literal (gate G46). Session state lives on the
 * {@link Session} the case opens and is discarded with it, so no observation can leak from one case
 * into the next; this class holds no mutable static field of its own (practice B9, gate G53).
 *
 * @see StatementGenerationJobB
 * @see ParityHarness
 * @see FieldDiffer
 */
@DisplayName("CBSTM03B parity - the statement job's four-file, six-operation data-access contract")
class CBSTM03BParityTest {

    /**
     * The program name, which is also this class's stem and the name of its case directory.
     *
     * <p>Upper case, and deliberately so. The source file is {@code app/cbl/CBSTM03B.CBL} with an
     * upper-case extension - one of only two in {@code app/cbl}, the other being
     * {@code CBSTM03A.CBL} - but a source file's extension says nothing about a resource directory.
     * The directory is {@code parity/CBSTM03B/} because every program's is the upper-case program
     * name.
     */
    private static final String PROGRAM = "CBSTM03B";

    /**
     * The binding key the ordered {@code LK-M03B-AREA} transcript is recorded under.
     *
     * <p>One key for the whole run rather than one per DD, because the linkage area is a single
     * shared 1040-byte structure and the thing worth pinning is the <em>total</em> call order: cases
     * 16, 19 and 20 interleave DDs, and a per-DD key would lose the interleaving that is their whole
     * point. The DD each call addressed is carried inside the area, in {@code LK-M03B-DD}, which is
     * where the COBOL puts it - so the {@code WHEN OTHER} case can name an unrecognised DD without
     * implying that a dataset by that name exists.
     */
    private static final String AREA_DATASET = "M03BAREA";

    /**
     * The DD name case 19 uses to reach {@code WHEN OTHER} ({@code CBSTM03B.CBL:127-128}).
     *
     * <p>Eight characters, so it occupies {@code LK-M03B-DD PIC X(08)} exactly and the arm is
     * selected on the value rather than on a width difference.
     */
    private static final String UNRECOGNISED_DD = "NOSUCHDD";

    /**
     * The key case06 hands to a keyed read of {@code TRNXFILE} and that the subroutine never looks
     * at, because {@code 1000-TRNXFILE-PROC} has no {@code IF M03B-READ-K} to look at it with.
     *
     * <p>These are the first {@value StatementGenerationJobB#KEY_LENGTH} bytes of the 32-byte
     * {@code TRNX-KEY} of the first seeded row - all that a {@code LK-M03B-KEY PIC X(25)} span can
     * hold of a key {@code app/jcl/CREASTMT.JCL:30} defines to IDCAMS as {@code KEYS(32 0)}. It is
     * paired with {@link StatementGenerationJobB#TRNXFILE_KEY_LENGTH}, so the call addresses 32
     * bytes of a 25-byte span. That is deliberately nonsense and is deliberately harmless: the only
     * statement that would evaluate {@code LK-M03B-KEY (1:LK-M03B-KEY-LN)} is the one at
     * {@code app/cbl/CBSTM03B.CBL:189} and {@code :214}, in the two RANDOM paragraphs, so nothing
     * here ever reference-modifies the span and {@link Request#usedKey()} is never reached.
     */
    private static final String TRNXFILE_UNUSED_KEY = "0500024453765740000000005";

    /**
     * The dataset code page, stated explicitly and never taken from the platform (practice B8).
     *
     * <p>{@code US-ASCII} because the authoritative fixtures under {@code app/data/ASCII} are ASCII;
     * {@code IBM037} is the code page for the EBCDIC datasets, which are reference-only.
     */
    private static final Charset DATASET_CHARSET = StandardCharsets.US_ASCII;

    /**
     * The record-image column of a seeded relation.
     *
     * <p>{@code DatasetRelation} discovers the name from the described result set rather than
     * assuming one, so any name works; this one is short so a rendered statement stays readable.
     */
    private static final String RECORD_IMAGE_COLUMN = "REC";

    /**
     * Test dataset names, one per DD.
     *
     * <p>Deliberately <strong>not</strong> the production names {@code app/csd/CARDDEMO.CSD}
     * declares: gate G46 requires that no such literal appears in Java, and a test that hard-coded
     * them would be the one place it did. The shape still satisfies the z/OS
     * dataset-name grammar the binding validates, because that is part of what a binding means.
     */
    private static final String TRNXFILE_DSNAME = "TEST.M2.CARDDEMO.TRXFL.VSAM.KSDS";

    /** The {@code XREFFILE} test dataset name. */
    private static final String XREFFILE_DSNAME = "TEST.M2.CARDDEMO.CARDXREF.VSAM.KSDS";

    /** The {@code CUSTFILE} test dataset name. */
    private static final String CUSTFILE_DSNAME = "TEST.M2.CARDDEMO.CUSTDATA.VSAM.KSDS";

    /** The {@code ACCTFILE} test dataset name. */
    private static final String ACCTFILE_DSNAME = "TEST.M2.CARDDEMO.ACCTDATA.VSAM.KSDS";

    /**
     * {@code CBSTM03B} sets no {@code RETURN-CODE} anywhere - it {@code GOBACK}s without touching
     * it - so every one of the twenty cases expects zero, and each case file states it explicitly
     * because {@link ParityCase} makes the member mandatory.
     */
    private static final int EXPECTED_RETURN_CODE = 0;

    // =============================================================================================
    //  THE GATE
    // =============================================================================================

    /**
     * The program's twenty declarative cases, in {@code case01} through {@code case20} order.
     *
     * <p>{@link ParityHarness#casesOf(String)} is the whole guard and it is a strict one: it refuses
     * a directory that is missing any of the twenty, naming each absentee, and equally refuses one
     * that holds anything the twenty-case enumeration would never read - a {@code case21.json}, a
     * {@code Case07.json}, a {@code case07.json.bak}. Both halves matter here, because the case
     * count <em>is</em> the gate: "the diff count is zero across all twenty cases" is satisfied
     * vacuously by a set of four.
     *
     * @return the twenty cases, never fewer and never more
     */
    static List<ParityCase> cases() {
        return ParityHarness.casesOf(PROGRAM);
    }

    /**
     * Runs one case against {@link StatementGenerationJobB} and requires a diff count of zero.
     *
     * <p>The differ compares in <strong>both</strong> directions - every expectation against what
     * the run produced, and every observation against what the case expected - so an area image the
     * case never pinned is a difference in its own right and cannot pass unnoticed. On a failure the
     * whole rendered report is surfaced, never a summary of it: it localises each difference to the
     * {@code LK-M03B-} span that carries it, with that span's declared offset and width, which is
     * the only form in which a 1040-byte mismatch is actionable.
     *
     * @param parityCase the case to run; supplied by {@link #cases()}
     */
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

    /**
     * Guards the case set itself, independently of {@link #cases()} being called by the runner.
     *
     * <p>A parameterized method whose source resolved to nothing is reported by JUnit, but a
     * <em>short</em> source is not, so the count is asserted here as well - and with it the identity
     * of every case, since twenty files that all declared {@code case01} would satisfy a count.
     */
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

    /**
     * Every case declares a decodable call script, and pins one area image per {@code CALL}.
     *
     * <p>The scripts live in the case files, which is where the behaviour under test belongs: this
     * subroutine's entire contract is a DD name plus a one-character operation code, so the sequence of
     * calls <em>is</em> what a case asserts. {@link #scriptOf(ParityCase)} refuses a case that declares
     * none, and refuses a malformed one rather than defaulting it, but it does so one case at a time and
     * only when that case runs. Decoding the whole set here turns a drifted fixture into one failure that
     * names it.
     *
     * <p>The second assertion is the one that keeps a script honest: one 1040-byte
     * {@code LK-M03B-AREA} expectation per call, so a script that grew a call would leave it unasserted
     * and a script that lost one would assert a call that never happened. It also covers every one of the
     * six operation codes across the set, because two of them - {@code W} and {@code Z} - are declared by
     * {@code app/cbl/CBSTM03B.cbl:107-108} and tested by nothing in the source, and preserving dead code
     * means driving it (practice B5).
     */
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
            .as("all six of the 88-level operation codes app/cbl/CBSTM03B.cbl:103-108 declares must be "
                + "issued by some case, including the two the source itself never tests")
            .containsExactlyInAnyOrder(Operation.values());
    }

    // =============================================================================================
    //  STRUCTURAL GATES - the claims the twenty cases rest on but cannot themselves make
    // =============================================================================================

    /**
     * The structural facts a behavioural case cannot assert, checked so the divergence between this
     * type's mandated name and its actual nature is proved rather than described.
     */
    @Nested
    @DisplayName("Structural gates")
    class StructuralGates {

        /**
         * Gate G12. {@code CBSTM03B} is a called subroutine and its Java form must be a Spring
         * {@code @Component} - never a Spring Batch {@code Job}, {@code Step} or {@code Tasklet},
         * whatever the mandated class name suggests.
         *
         * <p>Three independent checks, because each closes a different way the name could win over
         * the nature: the type must not <em>be</em> one of the three, it must not <em>expose</em> one
         * from any method, and it must carry {@code @Component} so that component scanning - not a
         * {@code @Configuration} class declaring a job bean - is how it reaches the context.
         */
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

        /**
         * The 1040-byte arithmetic of {@code LK-M03B-AREA}, which every case's {@code expectedBytes}
         * silently depends on. Asserted from the declared offsets and widths rather than from the
         * total alone, because six spans summing correctly is a weaker statement than six spans
         * sitting where the copybook puts them.
         */
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

        /**
         * The {@code EVALUATE LK-M03B-DD} arm order ({@code CBSTM03B.CBL:118-126}), which is the
         * order the four DD names must be recognised in.
         */
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

        /**
         * The asymmetric, read-only capability matrix ({@code CBSTM03B.CBL:31-53} and
         * {@code :133-229}), and the two operation codes that are declared and dead.
         */
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

        /**
         * The four record widths, which are what make the meaningful prefix of the 1000-byte record
         * area 350, 50, 500 or 300 bytes rather than anything else, and the two caller-computed key
         * widths, which are the {@code 9} and {@code 11} the keyed cases pass.
         */
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

        /**
         * The initial content of a {@code FILE STATUS} area and of the record area, which are what
         * case 15 and case 01 respectively assert behaviourally.
         */
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

    // =============================================================================================
    //  THE ADAPTER - how one case reaches the unit under test
    // =============================================================================================

    /**
     * One {@code CALL 'CBSTM03B' USING WS-M03B-AREA}, together with what the caller establishes in
     * the shared area immediately before it.
     *
     * <p>The two flags are not decoration: they are the caller's idiom, and which of them is set
     * decides what a case can assert. {@code app/cbl/CBSTM03A.CBL} issues
     * {@code MOVE ZERO TO WS-M03B-RC} before almost every call ({@code :349}, {@code :375},
     * {@code :399}, {@code :733}, {@code :768}, {@code :786}, {@code :804}, {@code :859},
     * {@code :876}, {@code :892}) - and a figurative constant moved to an alphanumeric receiver
     * fills it, so the inbound status is {@code "00"} and not {@code "0 "}. It issues
     * {@code MOVE SPACES TO WS-M03B-FLDT} at some sites ({@code :350}, {@code :376}, {@code :400},
     * {@code :745}, {@code :834}) and <em>not</em> at others ({@code :857-860}). Leaving the area
     * uncleared is what makes "at end of file the record area is returned unchanged" an assertion
     * with content rather than a tautology over 1000 spaces, and leaving the status un-zeroed is the
     * only way to prove {@code WHEN OTHER} returns the <em>stale</em> value.
     *
     * @param dd the {@code LK-M03B-DD} value; one of the four names, or an unrecognised one for the
     *     {@code WHEN OTHER} arm
     * @param oper the {@code LK-M03B-OPER} operation, including the two that are declared and dead
     * @param key the {@code LK-M03B-KEY} value, empty for a non-keyed operation - {@link Request}
     *     fits it to the 25-byte span under the {@code PIC X} rule
     * @param keyLength the {@code LK-M03B-KEY-LN} value the caller computed; {@code 0} for a
     *     non-keyed operation, which is rendered as the signed zoned image
     *     <code>"000&#123;"</code> - a brace, because the sign of a positive zero is overpunched
     *     into the low-order byte
     * @param zeroRc whether the caller issued {@code MOVE ZERO TO WS-M03B-RC} first
     * @param clearFldt whether the caller issued {@code MOVE SPACES TO WS-M03B-FLDT} first
     */
    private record Call(String dd, Operation oper, String key, int keyLength, boolean zeroRc,
                        boolean clearFldt) {
    }

    /**
     * The call script one case declares, decoded from its {@link ParityCase.UnitStimulus}.
     *
     * <p><strong>Nothing here reads {@link ParityCase#caseId()}.</strong> It used to: a {@code switch}
     * over {@code case01}..{@code case20} held twenty hand-written {@code List<Call>} scripts, and that
     * was the defect this replaces. The sequence of calls <em>is</em> the behaviour under test for this
     * program - {@code PROCEDURE DIVISION USING LK-M03B-AREA} dispatches on a DD name and an operation
     * code, and nothing else happens - so a case file that did not state its own sequence was a case file
     * that did not describe what it asserts. Renumbering a case silently gave it a different script, and
     * a twenty-first case would have thrown from a {@code default} arm.
     *
     * <p>{@link ParityCase.UnitStimulus#operationScript()} carries it now, one
     * {@link ParityCase.ScriptedOperation} per {@code CALL}, in issue order. Each declares the DD name
     * as {@code LK-M03B-DD} carries it, the operation as one of the six {@code 88}-level codes, the key
     * and key length as {@code LK-M03B-KEY} and {@code LK-M03B-KEY-LN} carry them, and the two caller
     * idioms the source distinguishes:
     * <ul>
     *   <li>{@code status} - the value handed in as {@code LK-M03B-RC}. Declared {@code "00"} for the
     *       {@code MOVE ZERO TO WS-M03B-RC} every real call site issues; omitted to carry the previous
     *       call's status forward, which is the only way to observe a fall-through exit that assigns
     *       nothing new.</li>
     *   <li>{@code primesRecordArea} - whether the caller issues
     *       {@code MOVE SPACES TO WS-M03B-FLDT} first. {@code app/cbl/CBSTM03A.CBL:L350}, {@code :L745}
     *       and {@code :L834} do; {@code :L857-860} conspicuously does not, and that omission is what
     *       makes "at end of file the record area is returned unchanged" an assertion with content.</li>
     * </ul>
     *
     * <p>Each script is a transcription of the {@code CBSTM03A} idiom for the paragraph it stands for,
     * and the expected area transcript in the same case file was derived from the same script by reading
     * {@code CBSTM03B}'s rules - never by running this code.
     *
     * @param parityCase the case whose script to decode
     * @return the calls to issue, in order; never empty
     * @throws IllegalArgumentException if the case declares no script, if an operation names a code this
     *     program does not declare, or if the stimulus declares a member this program has no use for
     */
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

    /**
     * Decodes one declared operation into the call this gate issues.
     *
     * @param caseId the case, for a diagnostic only - never for a decision
     * @param declared the declared operation
     * @return the call
     * @throws IllegalArgumentException if the status is anything other than {@code '00'} or absent, or if
     *     a key is declared without a length or a length without a key
     */
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

    /**
     * Resolves a declared one-character operation code to the enum member it names.
     *
     * @param caseId the case, for the diagnostic
     * @param code the declared code
     * @return the matching operation
     * @throws IllegalArgumentException if no operation carries that code
     */
    private static Operation operationNamed(String caseId, String code) {
        for (Operation candidate : Operation.values()) {
            if (candidate.image().equals(code)) {
                return candidate;
            }
        }
        throw new IllegalArgumentException(caseId + " of " + PROGRAM + " declares operation '" + code
            + "', which is not one of the six 88-level codes app/cbl/CBSTM03B.cbl:103-108 declares.");
    }

    /**
     * Constructs the subroutine over a per-case in-memory relation and replays the case's script.
     *
     * <p>The recorded fingerprint is the ordered transcript of {@code LK-M03B-AREA} images the calls
     * left behind - {@link StatementGenerationJobB#toAreaImage(Request, Response)} composes each one
     * from the request's half and the response's half, which is exactly the state of the area at the
     * moment {@code GOBACK} returns control - followed by every seeded dataset read back from the
     * relation. The read-back is the read-only proof: all four {@code SELECT}s are
     * {@code OPEN INPUT} and no paragraph writes, so a dataset that came out different from the way
     * it went in would be a parity failure that no area image could reveal.
     *
     * <p>No {@code RETURN-CODE} is recorded, deliberately. {@code CBSTM03B} never assigns one - it
     * {@code GOBACK}s without touching it - and the harness reads an unstated return code as zero,
     * so leaving it unrecorded is what "the subroutine did not set it" looks like. Recording the
     * expected value here would turn the case's own expectation into the observation it is compared
     * against.
     *
     * @param parityCase the case whose declared script to replay
     * @param invocation the seeded datasets, the pinned clock, the codec and the recorder
     * @return the outcome the recorder holds
     */
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

    // =============================================================================================
    //  THE COLLABORATORS - a fixture-backed relation and a four-entry binding catalogue
    // =============================================================================================

    /**
     * The subroutine over a given template, with the four bindings and an explicitly named code page.
     *
     * @param template the template to reach the relations through
     * @return the subject
     */
    private static StatementGenerationJobB subroutine(JdbcTemplate template) {
        return new StatementGenerationJobB(template, bindings(), DATASET_CHARSET,
            RecordImageForm.CHARACTER);
    }

    /**
     * A private in-memory relation per seeded dataset, holding the harness's own seeded rows.
     *
     * <p>A relation is created <strong>only</strong> for a dataset the case declares, so a call
     * against a DD the case never seeded fails to describe its dataset rather than quietly finding an
     * empty one - which keeps a case's {@code inputs} member load-bearing.
     *
     * <p>The column is declared at the seeded width rather than at the copybook width. The two agree
     * for every case here, and stating the seeded one means a disagreement would surface as the
     * {@code '04'} record-length conflict COBOL reports rather than being papered over by a wider
     * column. Each case gets a store of its own, so two cases - and two clones running in parallel -
     * can never share one; no counter is kept, because a mutable static field is exactly what practice
     * B9 and gate G53 forbid.
     *
     * <p><strong>No DDL.</strong> A relation here is declared to a {@link RecordImageDataSource}, which
     * holds record images and has no schema, so gate <strong>G44</strong> - no DDL, no schema migration,
     * no entity annotation and no generated table definition anywhere in this module - holds with nothing
     * to reinterpret. Everything above the driver is unchanged: the real {@code JdbcTemplate}, the real
     * {@link StatementGenerationJobB}, {@code DatasetRelation}'s real composed statements and
     * {@code RecordImageForm}'s real column read all run as they do against a site's gateway.
     *
     * @param seeded the datasets the harness seeded, keyed by binding key
     * @return a data source over the seeded relations
     */
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

    /**
     * Reads one relation back after the run, in ascending record-image order.
     *
     * <p>Ascending order over the whole image is ascending key order, because the key is a prefix of
     * the image in all four of these datasets - which is what a {@code KSDS} guarantees and what the
     * expectation in each case file is stated in.
     *
     * @param backend the store holding the seeded relations
     * @param dd the binding key whose relation to read
     * @return every stored row, in ascending order
     */
    private static List<String> storedRows(RecordImageDataSource backend, String dd) {
        List<String> rows = new ArrayList<>(backend.store().rows(dsnameOf(dd)));
        rows.sort(java.util.Comparator.naturalOrder());
        return rows;
    }

    /**
     * The four-entry {@code carddemo.datasets} catalogue the subroutine is constructed with.
     *
     * <p>Every entry has to agree with its copybook about the record width and with
     * {@code CBSTM03B}'s own {@code RECORD KEY} about the key width, because the constructor checks
     * both at startup rather than at the first read.
     *
     * @return the catalogue
     */
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

    /**
     * One well-formed indexed binding.
     *
     * <p>{@code recordFormat} is {@code "FB"}, which is what the JCL declares; the CSD says
     * {@code RECORDFORMAT(V)} for the same datasets and the two disagree in the source. Practice B4
     * forbids reconciling that, and nothing here depends on it - the subroutine treats length as
     * copybook-fixed and makes no assertion about the format either way.
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
            copybook, keyLength, null, null, null);
    }

    /**
     * The layout each dataset's rows are decoded through when its final state is recorded.
     *
     * @param dd the binding key
     * @return that dataset's copybook layout
     * @throws IllegalArgumentException if the DD is not one of the four
     */
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

    /**
     * The test dataset name each binding key resolves to.
     *
     * @param dd the binding key
     * @return that dataset's test name
     * @throws IllegalArgumentException if the DD is not one of the four
     */
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

    /**
     * Renders an identifier as a SQL delimited identifier, doubling any quotation mark within it.
     *
     * <p>A dataset name is dotted and would otherwise be parsed as a qualified reference, and it is
     * never a parameter, so it is delimited rather than bound. Doubling is unreachable for these
     * four constants; it is written anyway so the helper is correct for whatever it is handed rather
     * than only for the values one caller happens to supply.
     *
     * @param identifier the identifier text
     * @return the delimited identifier
     */
    private static String delimited(String identifier) {
        return '"' + identifier.replace("\"", "\"\"") + '"';
    }
}
