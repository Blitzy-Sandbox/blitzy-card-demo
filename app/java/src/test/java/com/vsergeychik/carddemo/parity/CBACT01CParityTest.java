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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;

import java.nio.charset.Charset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The parity gate for {@code CBACT01C}: twenty declarative cases, each judged field by field, each
 * required to report a diff count of zero.
 *
 * <h2>Where the expected values come from - read this first</h2>
 * <p>Every expectation in {@code src/test/resources/parity/CBACT01C/case01.json} through
 * {@code case20.json} is <strong>statically derived</strong>. It was obtained by reading
 * {@code app/cbl/CBACT01C.cbl} paragraph by paragraph, cross-checked against three other
 * authoritative sources: the byte layout of {@code app/cpy/CVACT01Y.cpy} (thirteen spans totalling
 * 300 bytes, ending in {@code FILLER PIC X(178)}), the DD and {@code PARM} contract of
 * {@code app/jcl/READACCT.jcl} (one input DD named {@code ACCTFILE}, no {@code PARM}, {@code SYSOUT}
 * and {@code SYSPRINT} for output), and the real fixture data in
 * {@code app/data/ASCII/acctdata.txt} (exactly fifty records of exactly 300 bytes).
 *
 * <p><strong>No expected value here was captured from a run of the legacy COBOL, and none was
 * captured from a run of this Java translation either.</strong> Executing the 28 legacy programs is
 * impossible in this environment - there is no z/OS runtime, the available COBOL compiler has its
 * indexed file handler disabled, and no Language Environment {@code CEE*} service is present, so
 * neither {@code CEEDAYS} nor the {@code CALL 'CEE3ABD'} at {@code app/cbl/CBACT01C.cbl:L173} can be
 * linked. The static derivation is the documented substitute for a captured baseline, and it is a
 * deliberate, escalated deviation from the original wording of the acceptance criterion rather than
 * an unremarked convenience. What survives that substitution is everything substantive: twenty cases
 * for this program, comparison field by field rather than as whole strings, and a diff count that
 * must be zero across all twenty before this module is complete. Only the provenance of the expected
 * values changed.
 *
 * <h2>The class name says balance; the program computes nothing</h2>
 * <p>{@link AccountBalanceJob} is the mandated name for the translation of {@code CBACT01C}, and it
 * does not describe what the program does. The source's own header reads
 * {@code Function : Read and print account data file}, and that is the whole of it: the program
 * opens {@code ACCTFILE} for input, walks it sequentially, displays each record, closes the file and
 * returns. It computes no balance, applies no interest, issues no {@code WRITE} and no
 * {@code REWRITE}, and touches no other dataset. The name is honoured verbatim and the behaviour is
 * taken from the source - so nobody should "complete" this class by adding a balance calculation,
 * and no case below expects one. Two of these twenty assert that positively rather than by omission:
 * {@code expectedWrites} is empty in all twenty, and the final state of {@code ACCTFILE} is pinned
 * byte for byte, so a translation that modified even one of the fifty stored records would be
 * reported.
 *
 * <h2>What the fingerprint of this program is</h2>
 * <p>{@code CBACT01C} writes no record anywhere, so its {@code SYSOUT} <em>is</em> its observable
 * output. The fingerprint is therefore the ordered list of {@code DISPLAY} lines plus the
 * {@code RETURN-CODE}, and the case files state that list line for line and character for character.
 * The line sequence of one successful pass is:
 * <ol>
 *   <li>{@code START OF EXECUTION OF PROGRAM CBACT01C} - the mainline's first statement, {@code L71};</li>
 *   <li>for each record, <strong>thirteen</strong> lines, because each record is displayed
 *       <em>twice</em> by two different paragraphs:
 *       {@code 1100-DISPLAY-ACCT-RECORD} emits eleven labelled field lines and a 49-dash separator
 *       from inside the {@code '00'} arm of the read paragraph at {@code L96}, and then the
 *       mainline's own {@code DISPLAY ACCOUNT-RECORD} at {@code L78} emits the raw 300-byte image;</li>
 *   <li>{@code END OF EXECUTION OF PROGRAM CBACT01C} - {@code L85}.</li>
 * </ol>
 * For the fifty-record fixture that is {@code 1 + (50 x 13) + 1 = 652} lines, which
 * {@code case01.json} states in full. Each of the eleven labels is exactly
 * {@value AccountBalanceJob#LABEL_WIDTH} characters wide including its terminating colon; the eleven
 * were extracted from {@code L119-L129} and each one matches the copybook field name it introduces.
 * Note which field is <em>absent</em> from them: {@code ACCT-ADDR-ZIP} is declared by the copybook
 * and stored in the record, yet the paragraph never labels it. {@code case09} pins that omission.
 *
 * <p>A fatal arm replaces the closing banner with three lines and a non-zero return code: the
 * paragraph's own error literal, then the {@code 9910-DISPLAY-IO-STATUS} rendering
 * {@code "}{@value FileStatus#DISPLAY_PREFIX}{@code "} followed by the four-character
 * {@code IO-STATUS-04} image - the {@code NNNN} is part of the literal at {@code L183} and not a
 * placeholder - and then {@value AbendException#ABEND_DISPLAY_TEXT} from
 * {@code 9999-ABEND-PROGRAM}. The {@code CALL 'CEE3ABD'} at {@code L173} arrives here as an
 * {@link AbendException} carrying {@code APPL-RESULT}, which is
 * {@value AccountRepository#APPL_RESULT_FATAL} at every one of this program's three fatal arms. An
 * abend is an <em>observation</em> and is compared like any other expectation; it never fails a case
 * by escaping.
 *
 * <h2>How the unit is reached: no launcher, no HTTP, no context</h2>
 * <p>Each case declares {@code "unitKind": "BATCH_JOB"} and the adapter below drives the tasklet
 * logic directly, through {@link AccountBalanceJob#readAndPrintAccountFile(SysoutSink)}. There is no
 * {@code JobLauncher}, no {@code JobLauncherTestUtils}, no job repository, no asynchronous executor,
 * no application context and nothing resembling an HTTP layer between the assertion and the code, so
 * the read order and the display order observed here are the ones the translated statements produce.
 * The job's {@code SYSOUT} seam is what makes that possible: the lines are collected into the
 * harness's recorder rather than written to a stream, so they survive an abend and reach the
 * fingerprint even when the run does not finish.
 *
 * <p>The collaborators are constructed rather than injected. {@link AccountRepository} and
 * {@link AccountBalanceJob} are the units under test; the four supporting types their public
 * constructors mandate - {@link BatchConfig}, {@link DatasetBindings}, {@link RecordImageForm} and an
 * {@link ObjectProvider} for the {@code SYSOUT} sink - are named here for that reason and no other.
 * No dataset name from the real system appears anywhere in this file: the cases address the dataset
 * by its {@code ACCTFILE} binding key and this class supplies a stand-in name of its own, because the
 * eight names taken from {@code app/csd/CARDDEMO.CSD} live only in {@code application.yml}.
 *
 * <h2>How a case's declared input decides which backend it meets</h2>
 * <p>A parity case is inputs and expectations; it is never a script. {@link Invocation} deliberately
 * exposes no part of the expectation, so this adapter cannot and does not branch on what a case
 * expects. It branches only on the dataset the case declares, through a closed mapping in which every
 * arm is a genuine property of that dataset:
 * <table border="1">
 *   <caption>Declared {@code ACCTFILE} input to backend condition</caption>
 *   <tr><th>the case declares</th><th>the backend is</th><th>the arm reached</th></tr>
 *   <tr><td>no {@code ACCTFILE} entry at all</td><td>a database holding no such relation</td>
 *       <td>{@code 0000-ACCTFILE-OPEN}'s fatal arm - {@code case12}</td></tr>
 *   <tr><td>{@code "empty": true} at 300 bytes</td><td>the relation, holding no row</td>
 *       <td>the first {@code READ} reports {@code '10'} - {@code case02}</td></tr>
 *   <tr><td>rows measuring 300 bytes</td><td>the relation, seeded with them</td>
 *       <td>{@code '00'} per record, then {@code '10'} - fifteen of the twenty</td></tr>
 *   <tr><td>rows measuring anything else</td><td>the relation, seeded with rows that are not
 *       {@code CVACT01Y} records</td>
 *       <td>{@code 1000-ACCTFILE-GET-NEXT}'s fatal arm - {@code case13}, {@code case14}</td></tr>
 * </table>
 * The fourth arm is worth a word. A stored row of 122 bytes is precisely the account record with its
 * trailing {@code FILLER PIC X(178)} span absent, and a row of 301 bytes is one byte too wide; the
 * repository refuses to decode fields from offsets that would not be theirs and reports a permanent
 * error, which is the status the COBOL's {@code WHEN OTHER} arm exists for. That is the same failure
 * a record-width assertion catches, seen from the program's side.
 *
 * <h2>Two arms these twenty cases cannot reach, stated rather than hidden</h2>
 * <ul>
 *   <li><strong>The fatal close.</strong> {@code 9000-ACCTFILE-CLOSE}'s {@code ELSE} arm needs the
 *       dataset to stop being addressable <em>between</em> the browse and the close. That is a
 *       temporal condition, and no seeded dataset expresses it: a {@code BATCH_JOB} case may declare
 *       only its datasets and its JCL {@code PARM} values - {@link ParityCase} forbids it a
 *       {@code screenRequest}, and with it the forced outcomes an online case can declare - and
 *       {@code app/jcl/READACCT.jcl} declares no {@code PARM}, so inventing a job parameter to carry
 *       an instruction would misstate that JCL contract. The arm is not left unasserted: it is driven
 *       and its byte-exact line sequence and return code 12 are asserted by
 *       {@code AccountBalanceJobTest}, which can refuse the second metadata probe of a run.</li>
 *   <li><strong>{@code 9910-DISPLAY-IO-STATUS}'s numeric branch.</strong> The only fatal status this
 *       program's backend produces begins with {@code '9'}, so the extended branch at {@code L179} is
 *       the one every fatal arm below takes, and the numeric branch at {@code L185} is unreachable
 *       from here. Statuses {@code '22'} and {@code '23'} are likewise unreachable, because
 *       {@code CBACT01C} performs only a sequential {@code READ} - there is no keyed read to report
 *       a duplicate or a missing record. All three are covered by {@code FileStatusTest} against the
 *       paragraph itself.</li>
 * </ul>
 *
 * <h2>Independence</h2>
 * <p>Every case runs against a private in-memory relation of its own, named after a fresh
 * {@link UUID}, and shuts it down afterwards. This class holds no mutable state, static or
 * otherwise, so the twenty cases may run in any order, repeatedly, or in parallel and each observes
 * exactly what it seeded. That is also why the four sibling readers - {@code CBACT02C},
 * {@code CBACT03C} and {@code CBCUS01C} share this program's open / read-loop / display / close
 * shape - keep parity classes of their own instead of a shared abstract base: each drives a different
 * dataset at a different record width with a different display format, and a shared base would report
 * a failure without saying which program produced it.
 *
 * @see AccountBalanceJob the unit under test, the translation of {@code CBACT01C}
 * @see ParityHarness which seeds a case, invokes the unit and captures the fingerprint
 * @see FieldDiffer which compares the fingerprint field by field and counts the differences
 */
@DisplayName("CBACT01C parity - 20 statically derived cases over AccountBalanceJob, which reads and "
        + "prints the account master and computes nothing")
class CBACT01CParityTest {

    // =============================================================================================
    // Identity. The program name is taken from the class under test so this file and the resource
    // directory parity/CBACT01C/ cannot drift apart, and the DD name from the repository so it is
    // the same key application.yml binds.
    // =============================================================================================

    /** {@code CBACT01C} - also the {@code parity/<PROGRAM>/} resource directory and this class's stem. */
    private static final String PROGRAM = AccountBalanceJob.PROGRAM_ID;

    /** {@code ACCTFILE} - the one DD {@code app/jcl/READACCT.jcl:L25} declares, and the case key. */
    private static final String DD_NAME = AccountRepository.BATCH_DD_NAME;

    /**
     * The code page the seeded rows and the repository both use: {@code US-ASCII}, taken from the
     * harness so the two cannot disagree.
     *
     * <p>Named explicitly and never left to the platform. A fixed-width mainframe record is bytes in a
     * specific code page, and {@code app/data/ASCII} is the ASCII half of the shipped data - the
     * EBCDIC half is read as {@code IBM037} and is not what these cases seed.
     */
    private static final Charset DATASET_CHARSET = ParityHarness.FIXTURE_CHARSET;

    /**
     * A stand-in dataset name. The eight real ones declared by {@code app/csd/CARDDEMO.CSD} live only
     * in {@code application.yml}, so no fully-qualified mainframe dataset name appears in Java at all -
     * a case addresses its dataset by binding key and this is the local name that key resolves to.
     */
    private static final String TEST_DSNAME = "PARITY.CBACT01C.ACCOUNT.KSDS";

    /** The record-image column of the seeded relation, which the repository's probe discovers. */
    private static final String RECORD_IMAGE_COLUMN = "REC";

    /**
     * The rendered status line every fatal arm of this program emits.
     *
     * <p>The only status the backend reports for a refused operation begins with {@code '9'}, so
     * {@code 9910-DISPLAY-IO-STATUS} renders it through its extended branch at
     * {@code app/cbl/CBACT01C.cbl:L179-L183}. Composed through the class that owns the paragraph's
     * shape rather than transcribed, so a change to either would show up as a failure here.
     */
    private static final String PERMANENT_ERROR_LINE =
            FileStatus.toDisplayLine(AccountRepository.PERMANENT_ERROR_STATUS);

    // =============================================================================================
    // The case set.
    // =============================================================================================

    /**
     * This program's complete case set, in {@code case01} through {@code case20} order.
     *
     * <p>{@link ParityHarness#casesOf(String)} enforces the set: a missing case is named
     * individually, and a resource in the directory that the twenty-case enumeration would never read
     * - a {@code case21.json}, a {@code Case07.json}, a {@code case07.json.bak} - is refused by name.
     * A short set is not a smaller gate; it is a gate that passes without asking the questions.
     *
     * @return the twenty cases, in ascending case order
     */
    static List<ParityCase> cases() {
        return ParityHarness.casesOf(PROGRAM);
    }

    /**
     * The count is part of the gate, so it is asserted rather than assumed.
     *
     * <p>{@code casesOf} already refuses a set that is not exactly twenty, which makes this a
     * statement of the requirement at the place a reader looks for it rather than a second
     * mechanism - and it also pins that every case in the directory really is a {@code CBACT01C}
     * case declaring the batch unit kind, which the loader checks per file but nothing otherwise
     * states as a property of the set.
     */
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

    /**
     * Pins the shape every case's expected line sequence must have, so a fixture cannot quietly
     * describe a program other than this one.
     *
     * <p>This is a check on the <em>expectations</em>, and it is worth having beside the gate rather
     * than folded into it. The gate compares one case's expectations against one run; nothing in it
     * would notice a fixture whose expected lines were internally inconsistent - a fatal case that
     * expected the closing banner, say, or a successful case whose line count was not the banners plus
     * a whole number of thirteen-line blocks. Such a fixture would then be compared faithfully against
     * a translation that had been written to match it, and both would be wrong together.
     *
     * <p>Three properties are asserted, each taken from the source rather than restated:
     * <ul>
     *   <li>every run opens with {@code L71}'s banner;</li>
     *   <li>a run that ends normally closes with {@code L85}'s banner and emits the two banners plus a
     *       whole number of {@value AccountBalanceJob#LINES_PER_RECORD}-line record blocks, and ends
     *       with {@code RETURN-CODE} 0 because the program never touches it;</li>
     *   <li>a run that abends ends with exactly the three lines of the shared fatal arm - one of the
     *       two error literals reachable in these cases, the rendered file status, and
     *       {@value AbendException#ABEND_DISPLAY_TEXT} - carries
     *       {@value AccountRepository#APPL_RESULT_FATAL} as its return code, and never reaches the
     *       closing banner.</li>
     * </ul>
     */
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
                assertThat(texts)
                        .as("%s: the fatal arm is the error literal, the rendered status and the abend "
                                + "banner, in that order, and the run never reaches L85", where)
                        .hasSizeGreaterThanOrEqualTo(4)
                        .endsWith(PERMANENT_ERROR_LINE, AbendException.ABEND_DISPLAY_TEXT)
                        .doesNotContain(AccountBalanceJob.END_OF_EXECUTION);
                assertThat(texts.get(texts.size() - 3))
                        .as("%s: the error literal is the one belonging to the paragraph that failed",
                                where)
                        .isIn(AccountBalanceJob.ERROR_OPENING_ACCTFILE,
                                AccountBalanceJob.ERROR_READING_ACCOUNT_FILE);
            }
        }
    }

    // =============================================================================================
    // The gate.
    // =============================================================================================

    /**
     * Runs one case and requires the diff count to be zero.
     *
     * <p>The whole gate is in the last assertion. A module is not complete until the count is zero
     * across all twenty of its cases: nineteen clean and one difference is an incomplete module, not
     * a nearly complete one. The failure text is {@link DiffResult#render()}, which names every
     * difference it found - the dataset, the row, the field, its offset and length, and the expected
     * and observed values - and never truncates the list, so one run is enough to see the whole
     * picture.
     *
     * @param parityCase one of the twenty cases, supplied by {@link #cases()}
     */
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

    // =============================================================================================
    // The adapter: how this class reaches the tasklet logic.
    // =============================================================================================

    /**
     * Constructs the unit for one case, runs one complete pass, and records what the pass produced.
     *
     * <p>{@code null} is returned rather than a built outcome, which is what the harness asks a unit
     * that may abend to do: the recorder survives the exception and a method's return value does not,
     * so the lines a run displayed before abending still reach the fingerprint.
     *
     * <p>The final state is reported inside a {@code finally} block so it is reported on the abend
     * path too - "the dataset the run failed on still holds exactly what it held" is an assertion
     * worth making - and the relation is shut down in an outer one, so no case leaves an in-memory
     * database behind for the next.
     *
     * @param invocation the seeded datasets, the pinned clock, the codec and the recorder
     * @return {@code null}, meaning the recorder holds the outcome
     */
    private UnitOutcome runAccountBalanceJob(Invocation invocation) {
        JdbcTemplate template = freshDatabase();
        boolean ranToCompletion = false;
        try {
            SeededDataset seeded = invocation.hasDataset(DD_NAME) ? invocation.dataset(DD_NAME) : null;
            if (seeded != null) {
                createRelation(template, seeded.recordLength());
                seedRelation(template, seeded);
            }

            UnitOutcome.Builder recorder = invocation.recorder();
            SysoutSink sysout = recorder::display;
            AccountBalanceJob job = accountBalanceJob(template, sysout);
            try {
                job.readAndPrintAccountFile(sysout);

                // GOBACK at app/cbl/CBACT01C.cbl:L87. The program never touches RETURN-CODE, so a
                // normal end is zero; a fatal arm never reaches this statement and the harness takes
                // the abend's own return code instead.
                recorder.returnCode(AccountBalanceJob.RETURN_CODE_NORMAL_END);
            } finally {
                reportFinalState(template, seeded, recorder);
            }
            ranToCompletion = true;
            return null;
        } finally {
            discard(template, ranToCompletion);
        }
    }

    /**
     * Reports what {@code ACCTFILE} holds after the run, read back from the relation rather than
     * echoed from the seed.
     *
     * <p>Reading it back is the point. {@code CBACT01C} opens the dataset {@code INPUT} and issues no
     * {@code WRITE} and no {@code REWRITE}, so "the fifty stored records are still exactly the fifty
     * stored records" is a real assertion about a class named {@code AccountBalanceJob} - and echoing
     * the seed back would assert nothing at all, because the seed is what the case declared.
     *
     * <p>The rows are ordered by the record image ascending, which is the order the browse itself
     * reads them in: {@code app/cbl/CBACT01C.cbl:L29-L33} declares {@code ACCTFILE} as
     * {@code ORGANIZATION INDEXED}, {@code ACCESS MODE SEQUENTIAL}, {@code RECORD KEY FD-ACCT-ID}, so
     * key order and not insertion order is what a sequential pass sees. {@code case20} seeds three
     * records in descending key order to pin exactly that.
     *
     * <p>Nothing is reported for a dataset whose stored rows are not {@code CVACT01Y} records. A
     * 122-byte or 301-byte row has no 300-byte layout to be described by, and describing it under one
     * would be a false statement about its bytes rather than an assertion about them. Those two cases
     * assert through the displayed line sequence and the return code, which is where their behaviour
     * actually shows.
     *
     * @param template the template over this case's relation
     * @param seeded   the dataset as it was seeded, or {@code null} when the case declared none
     * @param recorder where the final state is reported
     */
    private void reportFinalState(JdbcTemplate template, SeededDataset seeded,
            UnitOutcome.Builder recorder) {
        if (seeded == null || seeded.recordLength() != AccountRecord.RECORD_LENGTH) {
            return;
        }
        List<String> stored = template.queryForList(
                "SELECT " + RECORD_IMAGE_COLUMN + " FROM \"" + TEST_DSNAME + "\" ORDER BY "
                        + RECORD_IMAGE_COLUMN,
                String.class);
        recorder.finalState(DD_NAME, AccountRecord.LAYOUT, stored);
    }

    // =============================================================================================
    // The backend one case meets: a private in-memory relation with one record-image column.
    // =============================================================================================

    /**
     * A private in-memory database for one case, with no relation in it yet.
     *
     * <p>The name carries a fresh {@link UUID}, so no two invocations - in any order, repeated, or
     * concurrent - can reach each other's data. A counter would have done the same job and would have
     * been static mutable state, which this class has none of.
     *
     * <p>{@code DB_CLOSE_DELAY=-1} keeps the database alive between operations, which is required
     * rather than convenient: the template borrows and returns a connection per operation, and an
     * in-memory database is otherwise discarded with its last connection - taking the relation with
     * it between the {@code OPEN} and the first {@code READ}.
     *
     * @return a template over the empty database
     */
    private JdbcTemplate freshDatabase() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:parity-" + PROGRAM + '-' + UUID.randomUUID()
                        + ";DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
                "sa", "");
        dataSource.setDriverClassName("org.h2.Driver");
        return new JdbcTemplate(dataSource);
    }

    /**
     * Creates the relation the repository will discover: one column, holding the record image.
     *
     * <p>The column is declared at the width the case's own rows measure rather than at the copybook's
     * 300, so a case seeding a row of another width really does store a row of that width. Widening
     * it to 300 would let the backend pad the difference away and the fatal read arm of {@code case13}
     * and {@code case14} would never be reached.
     *
     * @param template    the template over this case's database
     * @param recordWidth the width the seeded rows measure
     */
    private void createRelation(JdbcTemplate template, int recordWidth) {
        template.execute("CREATE TABLE \"" + TEST_DSNAME + "\" (" + RECORD_IMAGE_COLUMN
                + " VARCHAR(" + recordWidth + "))");
    }

    /**
     * Stores the case's rows, verbatim and in the order the case declared them.
     *
     * <p>Declaration order is preserved and deliberately not sorted here. A KSDS browse reads in key
     * order whatever order the records were loaded in, and {@code case20} exists to prove the
     * translation does the same - so the seed must be free to disagree with the read order.
     *
     * @param template the template over this case's relation
     * @param seeded   the dataset as the harness seeded it
     */
    private void seedRelation(JdbcTemplate template, SeededDataset seeded) {
        for (String row : seeded.rows()) {
            template.update("INSERT INTO \"" + TEST_DSNAME + "\" VALUES (?)", row);
        }
    }

    /**
     * Discards this case's in-memory database.
     *
     * <p>{@code DB_CLOSE_DELAY=-1} keeps a database alive for the rest of the JVM, so a case that did
     * not discard its own would accumulate one per invocation. This is where they are discarded.
     *
     * <p>A failure to discard is reported only when the run itself succeeded. A run that is already
     * failing owns the exception the caller needs - the abend it did not expect, or the difference the
     * differ was about to render - and replacing it with one raised while tidying up would hide
     * exactly the finding the case exists to produce. Nothing is lost by not reporting it either: the
     * database is private to this one invocation and unreachable from any other.
     *
     * <p>Nothing about the failure is logged. The only thing a driver's message could add here is the
     * record it was handed, and an account row carries {@code ACCT-CURR-BAL} and both credit limits.
     *
     * @param template        the template over this case's database
     * @param ranToCompletion whether the run finished without an exception of its own
     * @throws IllegalStateException if the database could not be discarded after a successful run
     */
    private void discard(JdbcTemplate template, boolean ranToCompletion) {
        try {
            template.execute("SHUTDOWN");
        } catch (DataAccessException failure) {
            if (ranToCompletion) {
                throw new IllegalStateException("The private in-memory relation backing a " + PROGRAM
                        + " parity case could not be discarded, so this case has leaked a database "
                        + "into the rest of the run. Reported rather than swallowed because the run "
                        + "itself succeeded, which means there is no earlier failure this one could "
                        + "be hiding.", failure);
            }
        }
    }

    // =============================================================================================
    // The unit and the collaborators its constructors mandate.
    // =============================================================================================

    /**
     * The job under test, over one case's relation, writing every {@code DISPLAY} to the given sink.
     *
     * @param template the template over this case's relation
     * @param sysout   where the displayed lines are captured
     * @return the job
     */
    private AccountBalanceJob accountBalanceJob(JdbcTemplate template, SysoutSink sysout) {
        return new AccountBalanceJob(batchScaffolding(), accountRepository(template),
                new DeclaredBean<>(sysout));
    }

    /**
     * The repository over one case's relation, at the geometry {@code app/cpy/CVACT01Y.cpy} declares.
     *
     * <p>{@link RecordImageForm#CHARACTER} because the relation presents the image as characters, and
     * the code page is single-byte so the character form is exact.
     *
     * @param template the template over this case's relation
     * @return the repository
     */
    private AccountRepository accountRepository(JdbcTemplate template) {
        return new AccountRepository(template, datasetBindings(), DATASET_CHARSET,
                RecordImageForm.CHARACTER);
    }

    /**
     * The dataset catalogue, naming one dataset under both of the keys the repository requires.
     *
     * <p>{@code app/csd/CARDDEMO.CSD:L1-L2} defines the CICS file and {@code app/jcl/READACCT.jcl:L25}
     * defines the batch DD over <em>one</em> account master, so both keys name the same stand-in
     * dataset here. The declared record width is 300 and the declared key width is 11, from
     * {@code ACCT-ID PIC 9(11)}.
     *
     * @return the catalogue
     */
    private DatasetBindings datasetBindings() {
        DatasetBinding account = new DatasetBinding(TEST_DSNAME, DatasetBinding.KSDS, false, "FB",
                null, AccountRecord.RECORD_LENGTH, "CVACT01Y", AccountRecord.KEY_LENGTH, null, null,
                null);
        DatasetBindings catalogue = new DatasetBindings();
        catalogue.put(AccountRepository.CICS_FILE_NAME, account);
        catalogue.put(DD_NAME, account);
        return catalogue;
    }

    /**
     * The batch scaffolding, carrying the {@code carddemo.jobs} contract for this job.
     *
     * <p>The contract is what {@code application.yml} declares and what {@code app/jcl/READACCT.jcl}
     * supports: program {@code CBACT01C}, no parameters, and one step named {@code STEP05} that is
     * <strong>not</strong> gated on a preceding exit code, because that JCL carries no {@code COND}
     * and declares only the one step.
     *
     * <p>The job repository and the transaction manager are mocked, and they are never used: nothing
     * here launches a job or opens a step, so no batch metadata is written. They exist because the
     * constructor takes them.
     *
     * @return the scaffolding
     */
    private BatchConfig batchScaffolding() {
        JobContracts contracts = new JobContracts();
        contracts.put(AccountBalanceJob.JOB_KEY, new JobContract(PROGRAM, List.of(),
                List.of(new StepContract(AccountBalanceJob.STEP_NAME, PROGRAM, false)), null,
                Map.of()));
        return new BatchConfig(new DeclaredBean<>(Mockito.mock(JobRepository.class)),
                new DeclaredBean<>(Mockito.mock(PlatformTransactionManager.class)), contracts,
                datasetBindings());
    }

    /**
     * An {@link ObjectProvider} that always yields the bean it was given.
     *
     * <p>The job resolves its {@code SYSOUT} sink through a provider so a caller can supply one
     * without reconfiguring the bean, which is exactly what a parity case needs: with the sink
     * declared, the displayed lines reach the recorder instead of the process's standard output.
     *
     * @param bean the bean to yield
     * @param <T>  the bean type
     */
    private record DeclaredBean<T>(T bean) implements ObjectProvider<T> {

        @Override
        public T getObject() {
            return bean;
        }
    }
}
