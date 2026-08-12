package com.vsergeychik.carddemo.parity;

import com.vsergeychik.carddemo.common.AbendException;
import com.vsergeychik.carddemo.common.FileStatus;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.FixedWidthRecord.FieldSpan;
import com.vsergeychik.carddemo.common.FixedWidthRecord.PictureKind;
import com.vsergeychik.carddemo.common.RecordImageForm;
import com.vsergeychik.carddemo.config.BatchConfig;
import com.vsergeychik.carddemo.config.BatchConfig.JobContract;
import com.vsergeychik.carddemo.config.BatchConfig.JobContracts;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBinding;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBindings;
import com.vsergeychik.carddemo.customer.CustomerFileReaderJob;
import com.vsergeychik.carddemo.customer.CustomerRepository;
import com.vsergeychik.carddemo.customer.CustomerRepository.CustomerFile;
import com.vsergeychik.carddemo.customer.CustomerRepository.ReadResult;
import com.vsergeychik.carddemo.customer.CustomerService;
import com.vsergeychik.carddemo.customer.CustomerService.SysoutSink;
import com.vsergeychik.carddemo.customer.model.CustomerRecord;
import com.vsergeychik.carddemo.parity.FieldDiffer.DiffResult;
import com.vsergeychik.carddemo.parity.ParityCase.EmittedMessage;
import com.vsergeychik.carddemo.parity.ParityCase.UnitKind;
import com.vsergeychik.carddemo.parity.ParityHarness.Invocation;
import com.vsergeychik.carddemo.parity.ParityHarness.SeededDataset;
import com.vsergeychik.carddemo.parity.ParityHarness.UnitOutcome;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mockito;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.scope.context.StepContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;

import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * The parity gate for {@code CBCUS01C}: twenty declarative cases, each judged field by field, each
 * required to report a diff count of zero.
 *
 * <h2>Where the expected values come from - read this first</h2>
 * <p>Every expectation in {@code src/test/resources/parity/CBCUS01C/case01.json} through
 * {@code case20.json} is <strong>statically derived</strong>. It was obtained by reading
 * {@code app/cbl/CBCUS01C.cbl} paragraph by paragraph - all 178 lines of it, with
 * <strong>zero</strong> {@code EXEC CICS} statements in them - and cross-checking the result against
 * three other authoritative sources: the byte layout of {@code app/cpy/CVCUS01Y.cpy} (nineteen spans
 * totalling 500 bytes, ending in {@code FILLER PIC X(168)}), the DD and {@code PARM} contract of
 * {@code app/jcl/READCUST.jcl} (one step {@code STEP05}, a bare {@code EXEC PGM=CBCUS01C} with
 * <em>no</em> {@code PARM}, one input DD named {@code CUSTFILE}, and {@code SYSOUT} and
 * {@code SYSPRINT} for output), and the real fixture data in {@code app/data/ASCII/custdata.txt}
 * (exactly fifty records of exactly 500 bytes, every one of them with a blank {@code FILLER}).
 *
 * <p><strong>No expected value here was captured from a run of the legacy COBOL, and none was
 * captured from a run of this Java translation either.</strong> Executing the 28 legacy programs is
 * impossible in this environment: there is no z/OS runtime, the available COBOL compiler has its
 * indexed file handler disabled - which rules out the {@code ORGANIZATION INDEXED} {@code SELECT} at
 * {@code app/cbl/CBCUS01C.cbl:L29-L33} outright - and no Language Environment {@code CEE*} service is
 * present, so the {@code CALL 'CEE3ABD'} at {@code app/cbl/CBCUS01C.cbl:L158} cannot even be linked.
 * The static derivation is the documented substitute for a captured baseline. It is a deliberate,
 * escalated deviation from the original wording of the acceptance criterion - recorded as risk
 * <strong>R-A</strong> - and not an unremarked convenience. What survives the substitution is
 * everything substantive: twenty cases for this program, comparison field by field rather than as
 * whole strings, and a diff count that must be zero across all twenty before this module is
 * complete. Only the provenance of the expected values changed.
 *
 * <h2>The mandated names call this a repository and a service; the program is a batch job</h2>
 * <p>{@code CBCUS01C} is mapped by name onto {@link CustomerRepository} and {@link CustomerService},
 * and that pair of names leaves out the single most important thing about the program: its own header
 * reads {@code Type : BATCH COBOL Program} and {@code Function : Read and print customer data file},
 * and {@code app/jcl/READCUST.jcl} runs it as {@code EXEC PGM=CBCUS01C}. It is a standalone, runnable
 * batch program, not a data-access helper that something else drives.
 * {@link CustomerFileReaderJob} exists precisely to preserve that runnable behaviour, and fifteen of
 * the twenty cases below reach the program through it, as a Spring Batch tasklet. The divergence is
 * documented rather than resolved by reshaping the program: the names come from the plan and the
 * behaviour comes from the source (rule <strong>R1</strong>, register 0.8.4, practice
 * <strong>B4</strong>). Nobody should conclude from the mandated names that this program has no
 * runnable form, and nobody should "simplify" it into one that has none.
 *
 * <h2>What the fingerprint of this program is - and the defect that doubles it</h2>
 * <p>{@code CBCUS01C} writes no record anywhere, so its {@code SYSOUT} <em>is</em> its observable
 * output. The fingerprint is the ordered list of {@code DISPLAY} lines plus the {@code RETURN-CODE},
 * and the case files state that list line for line and character for character.
 *
 * <p>The line sequence of one successful pass is:
 * <ol>
 *   <li>{@value CustomerService#START_OF_EXECUTION} - the mainline's first statement, {@code L71};</li>
 *   <li>for each record, <strong>{@value CustomerService#DISPLAYS_PER_RECORD} byte-identical
 *       lines</strong>, because each record is displayed <em>twice</em> by two different paragraphs:
 *       {@code 1000-CUSTFILE-GET-NEXT} emits the raw 500-byte group image from inside its {@code '00'}
 *       arm at {@code L96}, and then the mainline's own {@code DISPLAY CUSTOMER-RECORD} at {@code L78}
 *       emits the very same image again;</li>
 *   <li>{@value CustomerService#END_OF_EXECUTION} - {@code L85}.</li>
 * </ol>
 * For the fifty-record fixture that is {@code 2 + (50 x 2) = 102} lines, which {@code case01.json}
 * states in full. <strong>The duplication is a preserved defect, not redundant code</strong> (practice
 * <strong>B5</strong>): de-duplicating it would halve every fingerprint this program produces. This is
 * also the exact point at which {@code CBCUS01C} differs from its sibling {@code CBACT01C}, whose
 * {@code L96} performs a labelled-field display paragraph and which therefore emits thirteen lines per
 * record rather than two. {@code CBCUS01C} has no labelled display paragraph at all and must not be
 * given one.
 *
 * <p>A fatal arm replaces the closing banner with three lines and a non-zero return code: the failing
 * paragraph's own error literal, then the rendering of {@code Z-DISPLAY-IO-STATUS} -
 * {@code "}{@value FileStatus#DISPLAY_PREFIX}{@code "} followed by the four-character
 * {@code IO-STATUS-04} image, where the {@code NNNN} is part of the COBOL literal at {@code L168} and
 * {@code L172} rather than a placeholder awaiting substitution - and then
 * {@value AbendException#ABEND_DISPLAY_TEXT} from {@code Z-ABEND-PROGRAM}. The
 * {@code CALL 'CEE3ABD'} at {@code L158} arrives here as an {@link AbendException} carrying
 * {@code APPL-RESULT}, which is {@value CustomerService#APPL_RESULT_FATAL} at every one of this
 * program's three fatal arms. An abend is an <em>observation</em> and is compared like any other
 * expectation; it never fails a case by escaping.
 *
 * <h2>How the units are reached: no launcher, no HTTP, no context</h2>
 * <p>Two of the four unit kinds are used, and each case states which one it is:
 * <ul>
 *   <li><strong>{@code "unitKind": "BATCH_JOB"}</strong> - fifteen cases. The adapter drives
 *       {@link CustomerFileReaderJob#customerFileDisplayTasklet()} directly, calling
 *       {@link Tasklet#execute} with a plain {@link StepContribution} and {@link ChunkContext}. There
 *       is no {@code JobLauncher}, no {@code JobLauncherTestUtils}, no job repository write, no
 *       asynchronous executor, no application context and nothing resembling an HTTP layer between
 *       the assertion and the code, so the read order and the display order observed here are the ones
 *       the translated statements produce (gate <strong>G51</strong>).</li>
 *   <li><strong>{@code "unitKind": "SERVICE"}</strong> - five cases. The adapter constructs
 *       {@link CustomerService} and calls
 *       {@link CustomerService#readAndPrintCustomerFileTo(SysoutSink)}, which is the production
 *       streaming entry point and the shortest path there is to the decision logic. Both kinds are
 *       legitimate for this program because the whole of its logic lives in the service and the job is
 *       only its wiring; using both means neither the wiring nor the logic is asserted only
 *       indirectly.</li>
 * </ul>
 * In both shapes the {@code SYSOUT} seam is what makes the fingerprint possible: lines are handed to
 * the harness's recorder rather than written to a stream, so they survive an abend and reach the
 * comparison even when the run does not finish.
 *
 * <p>The collaborators are constructed rather than injected. No dataset name from the real system
 * appears anywhere in this file: the cases address the dataset by its {@code CUSTFILE} binding key and
 * this class supplies a stand-in name of its own, because the eight names taken from
 * {@code app/csd/CARDDEMO.CSD} live only in {@code application.yml} (gate <strong>G46</strong>).
 *
 * <h2>Two backends, and what decides which one a case meets</h2>
 * <p>A parity case is inputs and expectations; it is never a script. {@link Invocation} deliberately
 * exposes no part of the expectation, so this adapter cannot and does not branch on what a case
 * expects. It branches on two things only, both of them inputs: the datasets the case declares, and
 * the {@linkplain #SCENARIOS scenario table} below, which states the I/O outcomes no arrangement of
 * rows can produce.
 * <table border="1">
 *   <caption>Which backend a case meets, and why</caption>
 *   <tr><th>the case</th><th>the backend is</th><th>what it reaches</th></tr>
 *   <tr><td>arranges nothing, and declares a {@code CUSTFILE}</td>
 *       <td>a private in-memory relation holding the declared rows, read through the real
 *           {@link CustomerRepository}</td>
 *       <td>the ordinary path: {@code '00'} per record, then {@code '10'}. Thirteen cases</td></tr>
 *   <tr><td>arranges nothing, and declares no {@code CUSTFILE} at all</td>
 *       <td>a database with no such relation</td>
 *       <td>{@code 0000-CUSTFILE-OPEN}'s fatal arm, from a dataset that is genuinely not there. No
 *           case currently declares itself this way - every one of the twenty names its
 *           {@code CUSTFILE}, two of them as a dataset that exists and holds no row - so this row
 *           records a shape the adapter supports rather than one a case takes today</td></tr>
 *   <tr><td>arranges an open, read or close outcome</td>
 *       <td>a stubbed repository reporting exactly that status, with the reads assembled from the
 *           case's own declared rows through the real {@link CustomerRecord#decode(String, Charset)}</td>
 *       <td>the {@code WHEN OTHER} arms and both branches of {@code Z-DISPLAY-IO-STATUS}. Seven
 *           cases</td></tr>
 * </table>
 * The real relation is used wherever it can be, because it is the only backend against which "the
 * fifty stored records are still exactly the fifty stored records" is an assertion rather than an echo:
 * the final state of those cases is read back out of the relation, not repeated from the seed. The
 * stub is used only where the real backend cannot report the status the COBOL branches on - it reports
 * {@code '00'}, {@code '10'} and its permanent-error convention and nothing else, whereas
 * {@code L94} and {@code L98} test exactly two values and route the remaining hundred-odd to a fatal
 * arm that gate <strong>G47</strong> requires be exercised.
 *
 * <h2>Independence</h2>
 * <p>Every case that uses a relation gets a private in-memory one of its own, named after a fresh
 * {@link UUID}, and shuts it down afterwards. This class holds no mutable state, static or otherwise -
 * {@link #SCENARIOS} is an unmodifiable map of records - so the twenty cases may run in any order,
 * repeatedly, or in parallel, and each observes exactly what it seeded (practice <strong>B9</strong>,
 * gate <strong>G53</strong>).
 *
 * <h2>Why a width difference found here is a cross-module finding</h2>
 * <p>{@code CVCUS01Y} has six consumers, and its 500-byte layout is also the shape {@code CBSTM03B}
 * reports for its {@code CUSTFILE} DD, whose {@code FD} splits the record as {@code X(09)} of key plus
 * {@code X(491)} of data - 500 exactly. An offset or width difference surfaced by these twenty cases
 * therefore breaks the statement job as well as this one, so it is never a local defect.
 *
 * @see CustomerService the unit under test - the translation of {@code CBCUS01C}
 * @see CustomerFileReaderJob its Spring Batch form, which preserves the program's runnable behaviour
 * @see ParityHarness which seeds a case, invokes the unit and captures the fingerprint
 * @see FieldDiffer which compares the fingerprint field by field and counts the differences
 */
@DisplayName("CBCUS01C parity - 20 statically derived cases over the standalone batch program that "
        + "reads the customer master and displays every record twice")
class CBCUS01CParityTest {

    // =================================================================================================
    // Identity. Every name is taken from the class under test or from the repository, so this file and
    // the resource directory parity/CBCUS01C/ cannot drift apart and no dataset literal appears here.
    // =================================================================================================

    /** {@code CBCUS01C} - also the {@code parity/<PROGRAM>/} resource directory and this class's stem. */
    private static final String PROGRAM = CustomerService.PROGRAM_ID;

    /** {@code CUSTFILE} - the one DD {@code app/jcl/READCUST.jcl:L9} declares, and the case key. */
    private static final String DD_NAME = CustomerRepository.BATCH_DD_NAME;

    /**
     * The code page the seeded rows and the repository both use: {@code US-ASCII}, taken from the
     * harness so the two cannot disagree.
     *
     * <p>Named explicitly and never left to the platform. A fixed-width mainframe record is bytes in a
     * specific code page, and {@code app/data/ASCII} is the ASCII half of the shipped data - the
     * EBCDIC half is read as {@code IBM037} and is not what these cases seed (practice
     * <strong>B8</strong>).
     */
    private static final Charset DATASET_CHARSET = ParityHarness.FIXTURE_CHARSET;

    /**
     * A stand-in dataset name. The eight real ones declared by {@code app/csd/CARDDEMO.CSD} live only
     * in {@code application.yml}, so no fully-qualified mainframe dataset name appears in Java at
     * all - a case addresses its dataset by binding key, and this is the local name that key resolves
     * to (gate <strong>G46</strong>).
     */
    private static final String TEST_DSNAME = "PARITY.CBCUS01C.CUSTOMER.KSDS";

    /** The record-image column of the seeded relation, which the repository's probe discovers. */
    private static final String RECORD_IMAGE_COLUMN = "REC";

    /**
     * The rendered status line a backend refusal produces.
     *
     * <p>{@link CustomerRepository#PERMANENT_ERROR_STATUS} is {@code '9'} followed by a binary
     * feedback byte, so {@code Z-DISPLAY-IO-STATUS} renders it through the extended branch at
     * {@code app/cbl/CBCUS01C.cbl:L162-L168}. Composed through the class that owns the paragraph's
     * shape rather than transcribed, so a change to either shows up as a failure here.
     */
    private static final String PERMANENT_ERROR_LINE =
            FileStatus.toDisplayLine(CustomerRepository.PERMANENT_ERROR_STATUS);

    // =================================================================================================
    // THE SCENARIO TABLE - the I/O outcomes seeded data cannot produce.
    // =================================================================================================

    /**
     * What the backend does during one case: how the {@code OPEN} answers, how the {@code CLOSE}
     * answers, and which {@code READ} - if any - fails and with what status.
     *
     * <p>This is an <strong>input</strong>, in exactly the sense the seeded rows are. The three I/O
     * paragraphs of {@code CBCUS01C} each test a {@code FILE STATUS} and abend on the arm they do not
     * name, and no arrangement of rows can produce a failed {@code OPEN}, a {@code '22'} or a
     * {@code '23'} on a sequential read, or a failed {@code CLOSE} - those come from the dataset being
     * unavailable, from a catalogue or hardware fault, or from the dataset ceasing to be addressable
     * between the browse and the close. A case therefore states its expected output in
     * {@code parity/CBCUS01C/caseNN.json} and its arranged input here, and the two are written
     * independently: nothing in this table is derived from a case file, and nothing in a case file is
     * derived from this table.
     *
     * <p>Every component is validated on construction, so a table entry that says two contradictory
     * things - a failed open that also arranges a read outcome, a failing read index with no status -
     * cannot be written at all. That matters more than it looks: an entry that quietly did nothing
     * would leave the run on its ordinary path while the case file described a failure, and the case
     * would then fail with a message about lines rather than about the omission.
     *
     * @param openStatus the two-character status {@code OPEN INPUT} at {@code L120} reports
     * @param closeStatus the two-character status {@code CLOSE} at {@code L138} reports
     * @param failingRead the zero-based index of the {@code READ} that fails, or
     *     {@link #NO_FAILING_READ} when every read succeeds until end of file. It may equal the seeded
     *     row count, which arranges the failure at the position end of file would otherwise have been
     *     reported
     * @param failingReadStatus the status that read reports, or {@code null} when no read fails
     */
    private record Scenario(String openStatus, String closeStatus, int failingRead,
                            String failingReadStatus) {

        /** The {@link #failingRead} value meaning "no read fails". */
        private static final int NO_FAILING_READ = -1;

        /** Validates the entry against the shapes {@code CBCUS01C} can actually take. */
        private Scenario {
            requireStatus(openStatus, "openStatus");
            requireStatus(closeStatus, "closeStatus");
            if (failingRead < NO_FAILING_READ) {
                throw new IllegalStateException("A failing read index of " + failingRead
                        + " is neither a zero-based position nor " + NO_FAILING_READ
                        + ", which is how this table says that no read fails");
            }
            boolean readFails = failingRead != NO_FAILING_READ;
            if (readFails != (failingReadStatus != null)) {
                throw new IllegalStateException("A scenario must either name a failing read index AND "
                        + "the status that read reports, or neither. This one names index "
                        + failingRead + " and status "
                        + (failingReadStatus == null ? "none" : "one")
                        + ", which would arrange a failure nothing reports or a status nothing "
                        + "carries.");
            }
            if (failingReadStatus != null) {
                requireStatus(failingReadStatus, "failingReadStatus");
            }
            if (!FileStatus.isOk(openStatus) && (readFails || !FileStatus.isOk(closeStatus))) {
                throw new IllegalStateException("A scenario whose OPEN fails cannot also arrange a "
                        + "read or a close outcome: app/cbl/CBCUS01C.cbl:L132 abends inside "
                        + "0000-CUSTFILE-OPEN, so neither the READ at L93 nor the CLOSE at L138 is "
                        + "ever reached, and an arrangement for them would assert a path that does "
                        + "not exist.");
            }
            if (readFails && !FileStatus.isOk(closeStatus)) {
                throw new IllegalStateException("A scenario whose READ fails cannot also arrange a "
                        + "failing CLOSE: app/cbl/CBCUS01C.cbl:L113 abends inside "
                        + "1000-CUSTFILE-GET-NEXT, so 9000-CUSTFILE-CLOSE at L136 is never "
                        + "performed. The handle is still released silently on the way out, which is "
                        + "why the close status is stated at all.");
            }
        }

        /** Refuses anything that is not a two-character COBOL {@code FILE STATUS}. */
        private static void requireStatus(String status, String member) {
            Objects.requireNonNull(status, "Scenario." + member + " is required; a COBOL FILE STATUS "
                    + "is always two characters, and '" + FileStatus.OK + "' is how a successful "
                    + "operation reports itself");
            if (status.length() != FileStatus.STATUS_LENGTH) {
                throw new IllegalStateException("Scenario." + member + " is " + status.length()
                        + " character(s); IO-STATUS at app/cbl/CBCUS01C.cbl:L50-L52 is two PIC X "
                        + "items and holds exactly " + FileStatus.STATUS_LENGTH);
            }
        }

        /**
         * Nothing is arranged: the case's declared inputs alone decide what happens.
         *
         * <p>Used by the thirteen ordinary cases. A case that reaches a fatal arm through its inputs
         * alone - by declaring no {@code CUSTFILE} at all, so the {@code OPEN}'s metadata probe finds
         * no such relation - would also be declared this way, because that is a property of its
         * inputs and not something this table has to say.
         *
         * @return the scenario
         */
        private static Scenario asDeclared() {
            return new Scenario(FileStatus.OK, FileStatus.OK, NO_FAILING_READ, null);
        }

        /**
         * {@code OPEN INPUT} at {@code L120} reports something other than {@code '00'}.
         *
         * @param status the status it reports
         * @return the scenario
         */
        private static Scenario openFails(String status) {
            return new Scenario(status, FileStatus.OK, NO_FAILING_READ, null);
        }

        /**
         * The {@code READ} at {@code L93} succeeds for a number of records and then fails.
         *
         * @param afterRecords how many records are read and displayed first, which is also the
         *     zero-based index of the failing read
         * @param status the status the failing read reports
         * @return the scenario
         */
        private static Scenario readFailsAfter(int afterRecords, String status) {
            return new Scenario(FileStatus.OK, FileStatus.OK, afterRecords, status);
        }

        /**
         * Every read succeeds and the {@code CLOSE} at {@code L138} then fails.
         *
         * @param status the status it reports
         * @return the scenario
         */
        private static Scenario closeFails(String status) {
            return new Scenario(FileStatus.OK, status, NO_FAILING_READ, null);
        }

        /** @return whether this entry arranges anything the seeded rows could not produce */
        private boolean arranged() {
            return !FileStatus.isOk(openStatus) || readFails() || closeFails();
        }

        /** @return whether one of the reads is arranged to fail */
        private boolean readFails() {
            return failingRead != NO_FAILING_READ;
        }

        /** @return whether {@code 9000-CUSTFILE-CLOSE} is arranged to fail */
        private boolean closeFails() {
            return !FileStatus.isOk(closeStatus);
        }

        /**
         * The status whose {@code IO-STATUS-04} image the run is expected to display, or {@code null}
         * for an entry that arranges no failure at all.
         *
         * <p>Exactly one of the three can be the failing one, because the compact constructor above
         * refuses an entry that arranges two: a failed open never reaches the read or the close, and a
         * failed read never reaches the close. So the first match is the only match.
         *
         * @return the arranged failing status, whichever paragraph reports it, or {@code null}
         */
        private String failingStatus() {
            if (!FileStatus.isOk(openStatus)) {
                return openStatus;
            }
            if (readFails()) {
                return failingReadStatus;
            }
            return closeFails() ? closeStatus : null;
        }
    }

    /**
     * One scenario per case, in case order - the arranged input side of all twenty cases.
     *
     * <p>Thirteen cases arrange nothing and differ only in what they seed; seven arrange a failure,
     * and between them they reach all three abend sites and both arms of
     * {@code Z-DISPLAY-IO-STATUS}:
     * <ul>
     *   <li>{@code case10} and {@code case13} fail the {@code OPEN} at {@code L120}: {@code case10}
     *       with the extended status {@code '92'}, which drives the {@code IO-STAT1 = '9'} arm at
     *       {@code L162-L168}, and {@code case13} with {@code '37'}, the attribute-conflict status
     *       that {@code app/jcl/CUSTFILE.jcl}'s {@code INDEXED} / {@code KEYS(9 0)} /
     *       {@code RECORDSIZE(500 500)} cluster and the {@code FILE-CONTROL} entry at
     *       {@code L29-L33} are the two sides of. One open failure therefore renders through each
     *       arm of {@code Z-DISPLAY-IO-STATUS}. {@code case09} arranges no open failure of its own:
     *       it pins the {@code PIC 9(03)} upper bound of {@code CUST-FICO-CREDIT-SCORE} instead,
     *       which is an assertion about a displayed record image and so needs the ordinary path,
     *       exactly as {@code case08} needs it for the matching lower bound. Nothing is lost by
     *       that, because the numeric arm its status used to render through is the same arm
     *       {@code case12}, {@code case13}, {@code case14} and {@code case15} drive;</li>
     *   <li>{@code case12} fails the fourth {@code READ} with {@code '04'} and {@code case14} the
     *       fifth with {@code '22'} - a status that is an ordinary branch in the online programs and
     *       fatal here, because {@code L94} tests only {@code '00'} and {@code L98} only
     *       {@code '10'}; and {@code case17} fails the second with the permanent-error convention,
     *       whose second byte is not a digit at all;</li>
     *   <li>{@code case15} fails the third {@code READ} with {@code '23'}, at the position end of
     *       file would otherwise have been reported - it seeds two rows and fails the read after
     *       them, which is the one arrangement no other entry makes and the only way to state that a
     *       forced status is not quietly re-read as {@code '10'};</li>
     *   <li>{@code case16} fails the {@code CLOSE} at {@code L138} with the extended status
     *       {@code '96'} on a run that read nothing.</li>
     * </ul>
     *
     * <p>Deeply immutable: an unmodifiable view over a map of records built once by
     * {@link #declaredScenarios()}. Declaration order is preserved so a diagnostic lists the entries
     * as a reader expects, and no test can perturb what another test reads (practice
     * <strong>B9</strong>, gate <strong>G53</strong>).
     */
    private static final Map<String, Scenario> SCENARIOS = declaredScenarios();

    /**
     * Builds the scenario table.
     *
     * @return the twenty entries in case order
     */
    private static Map<String, Scenario> declaredScenarios() {
        Map<String, Scenario> declared = new LinkedHashMap<>();
        declared.put("case01", Scenario.asDeclared());
        declared.put("case02", Scenario.asDeclared());
        declared.put("case03", Scenario.asDeclared());
        declared.put("case04", Scenario.asDeclared());
        declared.put("case05", Scenario.asDeclared());
        declared.put("case06", Scenario.asDeclared());
        declared.put("case07", Scenario.asDeclared());

        // case08 seeds one real fixture row and runs the ordinary path, because what it pins is the
        // zero-filled lower end of CUST-FICO-CREDIT-SCORE PIC 9(03) in a displayed record image.
        declared.put("case08", Scenario.asDeclared());

        // case09 arranges nothing for the same reason case08 does not: the two are one pair, pinning
        // the two ends of CUST-FICO-CREDIT-SCORE PIC 9(03) - case08 the zero-filled minimum 001 at
        // fixture row 26 and case09 the fully-populated maximum 793 at row 35. Both assert a displayed
        // 500-character record image, which only the ordinary path produces; an arranged open failure
        // would abend before the first image reached SYSOUT and neither end of the range would be
        // asserted at all.
        declared.put("case09", Scenario.asDeclared());

        declared.put("case10", Scenario.openFails("92"));

        // case11 arranges nothing on purpose. It is this program's dedicated record-width and FILLER
        // case (gates G19 and G21), and those two are assertions about record images: they need six
        // 500-character lines to exist, which only the ordinary path produces. An arranged read failure
        // would abend before the first image was displayed and the case would assert nothing about
        // either gate. Nothing is lost by arranging nothing here, because the fatal read arm is reached
        // four more times - case12, case14, case15 and case17 - and the numeric branch of
        // Z-DISPLAY-IO-STATUS that a status of '30' renders through is the same branch case12's '04',
        // case13's '37', case14's '22' and case15's '23' already drive.
        declared.put("case11", Scenario.asDeclared());

        declared.put("case12", Scenario.readFailsAfter(3, FileStatus.RECORD_LENGTH_CONFLICT));

        // case13 fails the OPEN with '37' rather than a read: the attribute-conflict status is a
        // property of the dataset the program is about to open, not of a record it went on to read.
        // It renders through the ELSE arm of Z-DISPLAY-IO-STATUS, the same arm case12's '04',
        // case14's '22' and case15's '23' reach from the read site, and differs from each of them in
        // exactly the two status characters, so between them the arm is pinned as value-driven -
        // a renderer that hard-coded one image, or that overlaid IO-STATUS anywhere other than
        // IO-STATUS-04(3:2), passes one of them and fails the others. '37' has no named constant
        // in FileStatus because no CBCUS01C paragraph tests for it, so it is written here as the
        // literal the OPEN reports, exactly as case10's '92' is.
        declared.put("case13", Scenario.openFails("37"));

        declared.put("case14", Scenario.readFailsAfter(4, FileStatus.DUPLICATE));

        // case15 arranges '23' at the row count rather than inside the rows, and is not a duplicate of
        // any other read failure: case12 and case14 fail a read that pre-empts a record that was
        // there, while case15 seeds exactly two rows so the failing read is the one that would have
        // reported end of file. That is the boundary Scenario.failingRead documents and the only entry
        // that reaches it - a repository that answered "no more rows" with '10' regardless of the
        // arranged status would send this run down the L107 APPL-EOF arm, end the loop, close, and
        // display the L85 banner, and only a case whose failing read sits at the row count can tell
        // the two apart. It runs as a BATCH_JOB, so the two record lines already displayed are also
        // asserted to survive the abend through the tasklet rather than only through the service.
        declared.put("case15", Scenario.readFailsAfter(2, FileStatus.NOT_FOUND));

        declared.put("case16", Scenario.closeFails("96"));
        declared.put("case17", Scenario.readFailsAfter(1, CustomerRepository.PERMANENT_ERROR_STATUS));
        declared.put("case18", Scenario.asDeclared());
        declared.put("case19", Scenario.asDeclared());
        declared.put("case20", Scenario.asDeclared());
        return Collections.unmodifiableMap(declared);
    }

    /**
     * The scenario a case runs under.
     *
     * @param caseId the case identifier the harness handed the adapter
     * @return the arranged backend behaviour; never {@code null}
     * @throws IllegalStateException if the table has no entry, which means a case file exists that
     *     nothing arranged - it would then run on its ordinary path while its expectations described a
     *     failure, and the failure message would be about lines rather than about the omission
     */
    private static Scenario scenarioFor(String caseId) {
        Scenario scenario = SCENARIOS.get(caseId);
        if (scenario == null) {
            throw new IllegalStateException("No scenario is declared for " + PROGRAM + '/' + caseId
                    + ". Every one of the " + ParityHarness.CASES_PER_PROGRAM + " cases states the "
                    + "backend behaviour it runs under, because a batch case cannot carry a "
                    + "ForcedOutcome - that member belongs to ParityCase.ScreenRequest, which a "
                    + "BATCH_JOB case may not declare. Declared: " + SCENARIOS.keySet() + '.');
        }
        return scenario;
    }

    // =================================================================================================
    // The case set.
    // =================================================================================================

    /**
     * This program's complete case set, in {@code case01} through {@code case20} order.
     *
     * <p>{@link ParityHarness#casesOf(String)} enforces the set: a missing case is named individually,
     * and a resource in the directory that the twenty-case enumeration would never read - a
     * {@code case21.json}, a {@code Case07.json}, a {@code case07.json.bak} - is refused by name. A
     * short set is not a smaller gate; it is a gate that passes without asking the questions.
     *
     * @return the twenty cases, in ascending case order
     */
    static List<ParityCase> cases() {
        return ParityHarness.casesOf(PROGRAM);
    }

    /**
     * The count is part of the gate, so it is asserted rather than assumed.
     *
     * <p>{@code casesOf} already refuses a set that is not exactly twenty, which makes this a statement
     * of the requirement at the place a reader looks for it rather than a second mechanism - and it
     * also pins several properties of the <em>set</em> that the loader checks per file and nothing
     * otherwise states as a whole: that every case in the directory really is a {@code CBCUS01C} case,
     * that each declares one of the two unit kinds this program has, that none declares a job
     * parameter, and that none expects a written record.
     *
     * <p>The scenario table is compared against the case set in both directions here too. A case with
     * no scenario would run on its ordinary path while its file described a failure; a scenario with no
     * case would arrange something nothing runs. Neither is detectable from one side alone.
     */
    @Test
    @DisplayName("the set is exactly 20 CBCUS01C cases, case01 through case20, each with a scenario")
    void theCaseSetIsExactlyTwenty() {
        List<ParityCase> declared = cases();

        assertThat(declared)
                .as("the gate is 'diff count zero across all twenty cases', so the set must hold "
                        + "exactly " + ParityHarness.CASES_PER_PROGRAM + " cases; write the missing "
                        + "case files rather than lowering the count")
                .hasSize(ParityHarness.CASES_PER_PROGRAM);

        for (int ordinal = 1; ordinal <= ParityHarness.CASES_PER_PROGRAM; ordinal++) {
            ParityCase declaredCase = declared.get(ordinal - 1);
            String where = PROGRAM + '/' + declaredCase.caseId();

            assertThat(declaredCase.caseId())
                    .as("the cases must be enumerated in ascending order")
                    .isEqualTo(ParityHarness.caseId(ordinal));
            assertThat(declaredCase.program())
                    .as("every case in parity/%s/ must name that program", PROGRAM)
                    .isEqualTo(PROGRAM);
            assertThat(declaredCase.unitKind())
                    .as("%s: %s is a non-CICS batch program whose logic lives in CustomerService and "
                            + "whose runnable form is CustomerFileReaderJob, so a case reaches it "
                            + "either as a batch job or as that service - never through a controller "
                            + "and never as a called component", where, PROGRAM)
                    .isIn(UnitKind.BATCH_JOB, UnitKind.SERVICE);
            assertThat(declaredCase.jobParameters())
                    .as("%s: app/jcl/READCUST.jcl:L6 is a bare EXEC PGM=%s with no PARM, so no case "
                            + "may declare a job parameter", where, PROGRAM)
                    .isEmpty();
            assertThat(declaredCase.expectedWrites())
                    .as("%s: %s opens CUSTFILE INPUT and issues no WRITE and no REWRITE at all - the "
                            + "CustomerRepository name notwithstanding - so no case may expect a "
                            + "written record", where, PROGRAM)
                    .isEmpty();
            assertThat(scenarioFor(declaredCase.caseId()))
                    .as("%s: every case states the backend behaviour it runs under", where)
                    .isNotNull();
        }

        assertThat(SCENARIOS.keySet())
                .as("a scenario that no case file reads arranges something nothing runs, which is as "
                        + "silent a failure as a case with no scenario")
                .containsExactlyElementsOf(declared.stream().map(ParityCase::caseId).toList());
    }

    /**
     * Pins the shape every case's expected line sequence must have, so a fixture cannot quietly
     * describe a program other than this one.
     *
     * <p>This is a check on the <em>expectations</em>, and it is worth having beside the gate rather
     * than folded into it. The gate compares one case's expectations against one run; nothing in it
     * would notice a fixture whose expected lines were internally inconsistent - a fatal case that
     * expected the closing banner, say, or a successful case whose line count was not the two banners
     * plus an even number of record images. Such a fixture would then be compared faithfully against a
     * translation written to match it, and both would be wrong together.
     *
     * <p>Four properties are asserted, each taken from the source rather than restated:
     * <ul>
     *   <li>every run opens with {@code L71}'s banner;</li>
     *   <li>a run that ends normally closes with {@code L85}'s banner, emits the two banners plus a
     *       whole number of {@value CustomerService#DISPLAYS_PER_RECORD}-line record blocks, and ends
     *       with {@code RETURN-CODE} 0 because the program never touches it;</li>
     *   <li>a run that abends ends with exactly the three lines of the shared fatal arm - one of the
     *       three error literals, the rendered file status, and
     *       {@value AbendException#ABEND_DISPLAY_TEXT} - carries
     *       {@value CustomerService#APPL_RESULT_FATAL} as its return code, and never reaches the
     *       closing banner;</li>
     *   <li>every record image in every case is exactly {@value CustomerRecord#RECORD_LENGTH}
     *       characters, and the images arrive in <strong>adjacent identical pairs</strong>, which is
     *       the preserved {@code L96}-then-{@code L78} duplication stated as a property of the
     *       expectations themselves (gates <strong>G19</strong> and <strong>G21</strong>).</li>
     * </ul>
     */
    @Test
    @DisplayName("every case's expected SYSOUT has the shape CBCUS01C's paragraphs produce")
    void expectedLineSequencesHaveTheProgramsShape() {
        for (ParityCase declaredCase : cases()) {
            List<EmittedMessage> messages = declaredCase.expectedMessages();
            String where = PROGRAM + '/' + declaredCase.caseId();

            assertThat(messages)
                    .as("%s: the mainline displays its opening banner at L71 before anything else, so "
                            + "no case can expect fewer than one line", where)
                    .isNotEmpty();
            assertThat(messages.get(0).text())
                    .as("%s: the first line is the L71 banner", where)
                    .isEqualTo(CustomerService.START_OF_EXECUTION);

            List<String> texts = messages.stream().map(EmittedMessage::text).toList();
            if (declaredCase.expectedReturnCode() == CustomerService.RETURN_CODE_NORMAL_END) {
                assertNormalEndShape(where, texts);
            } else {
                assertFatalShape(where, declaredCase, texts);
            }
            assertRecordImagesArePairedAndFiveHundredBytes(where, texts);
        }
    }

    /**
     * Asserts the shape of a run that reached {@code GOBACK}.
     *
     * @param where the case being described, for the failure text
     * @param texts the expected line sequence
     */
    private static void assertNormalEndShape(String where, List<String> texts) {
        assertThat(texts.get(texts.size() - 1))
                .as("%s: a normal end reaches the L85 banner", where)
                .isEqualTo(CustomerService.END_OF_EXECUTION);
        assertThat((texts.size() - CustomerService.BANNER_LINE_COUNT)
                % CustomerService.DISPLAYS_PER_RECORD)
                .as("%s: SYSOUT is the two banners plus %d identical lines per record - the display "
                        + "inside the read's '00' arm at L96 and the mainline's own at L78 - so %d "
                        + "lines cannot be a whole number of records", where,
                        CustomerService.DISPLAYS_PER_RECORD, texts.size())
                .isZero();
        assertThat(texts)
                .as("%s: only the fatal arms display an error literal or the abend banner", where)
                .doesNotContain(CustomerService.ABENDING_PROGRAM)
                .doesNotContainAnyElementsOf(CustomerService.ERROR_TEXTS);
    }

    /**
     * Asserts the shape of a run that reached {@code CALL 'CEE3ABD'}.
     *
     * @param where the case being described, for the failure text
     * @param declaredCase the case, for its expected return code
     * @param texts the expected line sequence
     */
    private static void assertFatalShape(String where, ParityCase declaredCase, List<String> texts) {
        assertThat(declaredCase.expectedReturnCode())
                .as("%s: every fatal arm of this program leaves APPL-RESULT at %d before "
                        + "Z-ABEND-PROGRAM performs CALL 'CEE3ABD' at L158", where,
                        CustomerService.APPL_RESULT_FATAL)
                .isEqualTo(CustomerService.APPL_RESULT_FATAL);
        assertThat(texts)
                .as("%s: the fatal arm is the error literal, the rendered status and the abend "
                        + "banner, in that order, and the run never reaches L85", where)
                .hasSizeGreaterThanOrEqualTo(4)
                .endsWith(CustomerService.ABENDING_PROGRAM)
                .doesNotContain(CustomerService.END_OF_EXECUTION);
        assertThat(texts.get(texts.size() - 2))
                .as("%s: the rendered file status carries the '%s' literal, which is text rather than "
                        + "a placeholder", where, FileStatus.DISPLAY_PREFIX)
                .startsWith(FileStatus.DISPLAY_PREFIX)
                .hasSize(FileStatus.DISPLAY_PREFIX.length() + FileStatus.STATUS_IMAGE_LENGTH);

        // The one place the two independently written sides of a case are cross-checked against each
        // other: the status the SCENARIOS table arranges must be the status the case file expects to
        // see rendered. A case whose table entry and whose fixture disagreed would otherwise fail with
        // a message about a line, and the line would be the symptom rather than the cause. A fatal case
        // that arranges nothing could only be one whose dataset is absent, whose OPEN probe is refused
        // and which therefore reports the permanent-error convention; no case declares itself that way
        // today, and the arm stays because the alternative is a null the reader has to reason about.
        String arranged = scenarioFor(declaredCase.caseId()).failingStatus();
        assertThat(texts.get(texts.size() - 2))
                .as("%s: the rendered status must be the one this case arranges - %s", where,
                        arranged == null ? "nothing, so the backend's own permanent-error status"
                                : "a status the scenario table names")
                .isEqualTo(arranged == null
                        ? PERMANENT_ERROR_LINE
                        : FileStatus.toDisplayLine(arranged));
        assertThat(texts.get(texts.size() - 3))
                .as("%s: the error literal is the one belonging to the paragraph that failed", where)
                .isIn(CustomerService.ERROR_TEXTS);
        assertThat((texts.size() - 4) % CustomerService.DISPLAYS_PER_RECORD)
                .as("%s: a fatal run is the opening banner, %d lines per record already displayed, "
                        + "and the three lines of the fatal arm, so %d lines cannot be a whole "
                        + "number of records", where, CustomerService.DISPLAYS_PER_RECORD,
                        texts.size())
                .isZero();
    }

    /**
     * Asserts that every record image in a case is 500 characters and that they arrive in adjacent
     * identical pairs.
     *
     * <p>The pairing is the preserved duplication, expressed as a property of the expectation rather
     * than of the run: {@code L96} and {@code L78} display the <em>same</em> record area with no
     * statement between them that could change it, so the two lines are byte-identical and adjacent. A
     * fixture that stated one image per record, or two images that differed, would describe a program
     * this is not.
     *
     * @param where the case being described, for the failure text
     * @param texts the expected line sequence
     */
    private static void assertRecordImagesArePairedAndFiveHundredBytes(String where,
            List<String> texts) {
        List<String> images = new ArrayList<>();
        for (String text : texts) {
            if (text.length() == CustomerRecord.RECORD_LENGTH) {
                images.add(text);
            }
        }
        assertThat(images.size() % CustomerService.DISPLAYS_PER_RECORD)
                .as("%s: every record contributes exactly %d lines, so an odd number of "
                        + "%d-character images means one display was dropped or one was added", where,
                        CustomerService.DISPLAYS_PER_RECORD, CustomerRecord.RECORD_LENGTH)
                .isZero();
        for (int index = 0; index < images.size(); index += CustomerService.DISPLAYS_PER_RECORD) {
            assertThat(images.get(index + 1))
                    .as("%s: record image %d and the line after it are the L96 and L78 displays of "
                            + "one record area, so they are byte-identical", where,
                            index / CustomerService.DISPLAYS_PER_RECORD)
                    .isEqualTo(images.get(index));
        }
    }

    // =================================================================================================
    // The facts the twenty case files rest on. Each is asserted here rather than assumed, because a
    // case file states bytes and a wrong premise would be stated identically on both sides.
    // =================================================================================================

    /**
     * The record is 500 bytes, its nineteen spans account for every one of them, and the trailing
     * {@code FILLER} is present.
     *
     * <p>Gate <strong>G19</strong> asks that every record be byte-identical in length to its copybook
     * declaration, and gate <strong>G21</strong> that {@code FILLER} spans be present rather than
     * dropped. Both are properties of the layout before they are properties of any run, and stating them
     * here is what stops an expectation and an implementation from agreeing on a wrong premise: if
     * {@code FILLER PIC X(168)} were absent from the layout, every span after offset 332 would still
     * line up and only the total width would betray it.
     *
     * <p>{@code CUST-DOB-YYYY-MM-DD} is named explicitly for the reason recorded as <strong>I1</strong>.
     * {@code app/cpy/CUSTREC.cpy} declares the same 10-byte span at the same offset and spells it
     * {@code CUST-DOB-YYYYMMDD}, and the statement package models that copybook separately as its own
     * type. The two names are the contract; comparing one under the other's name is a difference rather
     * than an equivalence, so a translation that collapsed the two layouts onto one type has to be
     * caught by name and not by offset.
     */
    @Test
    @DisplayName("CVCUS01Y is 500 bytes across 19 spans, FILLER included, and keeps its own DOB name")
    void theRecordLayoutAccountsForAllFiveHundredBytes() {
        assertThat(CustomerRecord.RECORD_LENGTH)
                .as("app/cpy/CVCUS01Y.cpy declares CUSTOMER-RECORD as RECLN 500, and every one of its "
                        + "six consumers - CBSTM03B's CUSTFILE FD splits it X(09) plus X(491) - depends "
                        + "on that")
                .isEqualTo(500);

        List<FieldSpan> spans = CustomerRecord.LAYOUT.spans();
        assertThat(spans)
                .as("CVCUS01Y declares 18 named fields and one trailing FILLER")
                .hasSize(19);

        int accounted = 0;
        for (FieldSpan span : spans) {
            assertThat(span.offset())
                    .as("span %s must begin where the one before it ended, or the record has a hole in "
                            + "it and every expectation past that point addresses the wrong bytes",
                            span.name())
                    .isEqualTo(accounted);
            accounted += span.length();
        }
        assertThat(accounted)
                .as("the 19 spans must account for all %d bytes; drop the trailing FILLER and 168 of "
                        + "them go unstated, which is the one omission an offset check cannot see",
                        CustomerRecord.RECORD_LENGTH)
                .isEqualTo(CustomerRecord.RECORD_LENGTH);

        assertThat(CustomerRecord.FILLER.name())
                .as("the differ addresses an unnamed span by this name, which is how case06 pins its "
                        + "168 characters")
                .isEqualTo("FILLER");
        assertThat(CustomerRecord.FILLER.offset()).isEqualTo(332);
        assertThat(CustomerRecord.FILLER.length()).isEqualTo(168);

        assertThat(CustomerRecord.CUST_DOB_YYYY_MM_DD.name())
                .as("app/cpy/CVCUS01Y.cpy spells this span with dashes; app/cpy/CUSTREC.cpy spells the "
                        + "same span CUST-DOB-YYYYMMDD and is modelled separately, so the two names "
                        + "must not converge")
                .isEqualTo("CUST-DOB-YYYY-MM-DD");
        assertThat(CustomerRecord.CUST_DOB_YYYY_MM_DD.offset()).isEqualTo(308);
        assertThat(CustomerRecord.CUST_DOB_YYYY_MM_DD.length()).isEqualTo(10);
    }

    /**
     * Every shipped row is 500 bytes and its {@code FILLER} is blank.
     *
     * <p>The premise nineteen of the twenty cases seed from, asserted against the fixture itself. A
     * fixture whose rows had been reflowed, re-encoded or line-ending-translated would be one byte wider
     * per row, and the twenty cases would then all fail with messages about record widths rather than
     * about the one thing that was actually wrong.
     */
    @Test
    @DisplayName("all 50 shipped customer rows are 500 bytes with a blank FILLER")
    void everyShippedRowIsFiveHundredBytesWithABlankFiller() {
        List<String> rows = shippedRows();

        assertThat(rows)
                .as("app/data/ASCII/custdata.txt holds exactly 50 records, and "
                        + "src/test/resources/fixtures/custdata.txt is its byte-for-byte copy")
                .hasSize(50);
        for (int index = 0; index < rows.size(); index++) {
            assertThat(rows.get(index).length())
                    .as("row %d is not %d characters, so it is not a CVCUS01Y record", index,
                            CustomerRecord.RECORD_LENGTH)
                    .isEqualTo(CustomerRecord.RECORD_LENGTH);
            assertThat(rows.get(index).substring(CustomerRecord.FILLER.offset()))
                    .as("row %d's trailing FILLER is not blank; case06 pins 168 spaces there and "
                            + "case07 is the one case that deliberately varies it", index)
                    .isEqualTo(" ".repeat(CustomerRecord.FILLER.length()));
        }
    }

    /**
     * The three numeric spans of {@code CVCUS01Y} are <strong>unsigned</strong> zoned, so no customer
     * row carries a sign overpunch - and the codec is what proves it rather than an assertion about the
     * copybook text.
     *
     * <p>This is where the agent's zoned-overpunch requirement lands, and the finding is worth stating
     * plainly because it is a negative one. {@code CUST-ID PIC 9(09)}, {@code CUST-SSN PIC 9(09)} and
     * {@code CUST-FICO-CREDIT-SCORE PIC 9(03)} are the only numeric spans in the record and every one
     * of them is written {@code PIC 9} and not {@code PIC S9}: the copybook declares no sign position
     * anywhere, and there is no {@code COMP-3} in it either. A customer record therefore has nowhere to
     * put an overpunch, which is why the fifty shipped rows hold digits and nothing else in those spans.
     *
     * <p>Three things are asserted, in the order that makes the negative result meaningful:
     * <ol>
     *   <li>each span is declared {@code UNSIGNED_NUMERIC} and
     *       {@link FixedWidthCodec#decodePic9(String)} reads all fifty rows' values from all three of
     *       them, so the spans really are zoned {@code DISPLAY} digits at those offsets;</li>
     *   <li>{@link FixedWidthCodec#decodeSignedZoned(String, int)} <em>does</em> read an overpunched
     *       trailing byte, and reads it as the low-order digit plus a sign - so the codec understands
     *       the convention perfectly well;</li>
     *   <li>and {@code decodePic9} nevertheless refuses that same image. The refusal is therefore a
     *       property of the {@code PIC 9} declaration and not a gap in the codec, which is exactly the
     *       distinction that matters: were {@code CUST-FICO-CREDIT-SCORE} ever mis-transcribed as
     *       {@code PIC S9(03)}, a value of 274 would store as {@code "27D"} and the record would differ
     *       from the COBOL's on its 332nd byte.</li>
     * </ol>
     */
    @Test
    @DisplayName("CVCUS01Y's numeric spans are unsigned zoned, so an overpunch is refused by declaration")
    void theNumericSpansAreUnsignedZonedSoNoRowCarriesAnOverpunch() {
        FixedWidthCodec codec = ParityHarness.usAscii().codec();
        List<FieldSpan> numeric = List.of(CustomerRecord.CUST_ID, CustomerRecord.CUST_SSN,
                CustomerRecord.CUST_FICO_CREDIT_SCORE);

        for (FieldSpan span : numeric) {
            assertThat(span.kind())
                    .as("%s is declared PIC 9 in app/cpy/CVCUS01Y.cpy, never PIC S9, so it reserves no "
                            + "sign position", span.name())
                    .isEqualTo(PictureKind.UNSIGNED_NUMERIC);
        }
        for (String row : shippedRows()) {
            for (FieldSpan span : numeric) {
                String image = row.substring(span.offset(), span.offset() + span.length());
                assertThat(codec.decodePic9(image))
                        .as("%s holds unsigned zoned digits in every shipped row", span.name())
                        .isEqualTo(Long.parseLong(image));
            }
        }

        FieldSpan fico = CustomerRecord.CUST_FICO_CREDIT_SCORE;
        String stored = shippedRows().get(0)
                .substring(fico.offset(), fico.offset() + fico.length());
        String overpunched = stored.substring(0, stored.length() - 1) + '{';

        FixedWidthCodec.SignedZoned asSigned = codec.decodeSignedZoned(overpunched, 0);
        assertThat(asSigned.negative())
                .as("'{' is the positive-zero overpunch, so the codec reads the value as positive")
                .isFalse();
        assertThat(asSigned.signedValue())
                .as("the overpunched trailing byte carries low-order digit 0 and a positive sign, so "
                        + "the three characters denote the leading digits followed by a zero")
                .isEqualByComparingTo(new BigDecimal(stored.substring(0, stored.length() - 1) + '0'));

        assertThatIllegalArgumentException()
                .as("%s is PIC 9(%d); an overpunch byte in it is not a digit and is refused rather "
                        + "than read as a sign, which is the difference between a field that has a "
                        + "sign position and one that does not", fico.name(), fico.length())
                .isThrownBy(() -> codec.decodePic9(overpunched));
    }

    /**
     * The program never writes to the customer master, verified against the repository rather than
     * inferred from the absence of an expectation.
     *
     * <p>Eleven of the twenty cases pin the dataset's final state row by row after reading it back out
     * of a real relation, which is the strongest form of this assertion. This is the complementary one,
     * and it is worth having because it answers a different question: not "did the stored rows change"
     * but "was a write ever attempted at all". A rewrite that failed silently would leave the rows
     * intact and pass the first check.
     *
     * <p>It matters for this program in particular because {@link CustomerRepository} does publish
     * {@code rewrite}, for the online programs that share the dataset. {@code CBCUS01C} opens
     * {@code CUSTFILE} {@code INPUT} at {@code app/cbl/CBCUS01C.cbl:L120} and contains no
     * {@code WRITE}, no {@code REWRITE} and no {@code DELETE} anywhere in its 178 lines, so neither
     * overload may be reached from a complete pass.
     */
    @Test
    @DisplayName("a complete pass issues no WRITE and no REWRITE against the customer master")
    void theProgramNeverWritesToTheCustomerMaster() {
        List<String> rows = shippedRows().subList(0, 3);
        CustomerRepository repository = stubbedCustomerMaster(Scenario.asDeclared(),
                SeededDataset.of(DD_NAME, rows, DATASET_CHARSET), "theProgramNeverWrites");
        List<String> emitted = new ArrayList<>();

        new CustomerService(repository).readAndPrintCustomerFileTo(emitted::add);

        assertThat(emitted)
                .as("three records, each displayed twice, between the two banners")
                .hasSize(CustomerService.expectedSysoutLineCount(rows.size()));
        Mockito.verify(repository, Mockito.never()).rewrite(Mockito.any(byte[].class));
        Mockito.verify(repository, Mockito.never()).rewrite(Mockito.any(CustomerRecord.class));
    }

    /**
     * The fifty shipped customer rows, obtained through the harness so the fixture is resolved exactly
     * as a case file resolves it.
     *
     * <p>Seeding {@code case01} rather than reading the file directly is deliberate: the harness is what
     * refuses a fixture with translated line endings and what applies any declared normalisation, so a
     * premise asserted against its output is a premise asserted against what the twenty cases actually
     * receive.
     *
     * @return the fifty 500-character record images, in stored order
     */
    private List<String> shippedRows() {
        ParityHarness harness = ParityHarness.usAscii();
        return harness.seed(harness.load(PROGRAM, ParityHarness.caseId(1))).get(DD_NAME).rows();
    }

    // =================================================================================================
    // The gate.
    // =================================================================================================

    /**
     * Runs one case and requires the diff count to be zero.
     *
     * <p>The whole gate is in the last assertion. A module is not complete until the count is zero
     * across all twenty of its cases: nineteen clean and one difference is an incomplete module, not a
     * nearly complete one. The failure text is {@link DiffResult#render()}, which names every
     * difference it found - the dataset, the row, the field, its offset and length, and the expected
     * and observed values - and never truncates the list, so one run is enough to see the whole
     * picture.
     *
     * <p>The unit kind is dispatched on explicitly rather than handed straight back to the harness. The
     * harness checks the adapter's declared kind against the case's own, and passing
     * {@code parityCase.unitKind()} through would make that check tautological; naming the two kinds
     * this program has means a case that declared a third is refused here by name.
     *
     * @param parityCase one of the twenty cases, supplied by {@link #cases()}
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    @DisplayName("the diff count is zero")
    void parityDiffCountIsZero(ParityCase parityCase) {
        ParityHarness harness = ParityHarness.usAscii();
        DiffResult result = switch (parityCase.unitKind()) {
            case BATCH_JOB -> harness.judge(parityCase, UnitKind.BATCH_JOB, this::runThroughTheJob);
            case SERVICE -> harness.judge(parityCase, UnitKind.SERVICE, this::runThroughTheService);
            case COMPONENT, CONTROLLER_POJO -> throw new IllegalStateException(
                    PROGRAM + '/' + parityCase.caseId() + " declares unitKind "
                            + parityCase.unitKind() + ". This program is a non-CICS batch program: it "
                            + "has no controller and it is called by nothing, so only BATCH_JOB and "
                            + "SERVICE can reach it. " + PROGRAM + " is not CBSTM03B or CSUTLDTC.");
        };

        assertThat(result.count())
                .as("%s", result.render())
                .isZero();
    }

    // =================================================================================================
    // The adapters: how this class reaches the tasklet logic and the service.
    // =================================================================================================

    /**
     * Drives the case through {@link CustomerFileReaderJob}'s tasklet - the {@code BATCH_JOB} shape.
     *
     * <p>{@link Tasklet#execute} is called directly, with a plain {@link StepContribution} and
     * {@link ChunkContext} constructed here. No {@code JobLauncher} launches anything, no
     * {@link JobRepository} is written to, and no application context exists, so what is observed is
     * the step body and nothing around it. The tasklet is the right entry point rather than an
     * approximation of one: it is what {@code READCUST.jcl}'s single {@code STEP05} runs, it reports
     * the read count to the framework, and its own {@code finally} spools the displayed lines to the
     * injected {@code SYSOUT} sink - which is how a failing run's lines reach the fingerprint, exactly
     * as a mainframe spool holds what a job displayed before it abended.
     *
     * <p>{@code null} is returned rather than a built outcome, which is what the harness asks a unit
     * that may abend to do: the recorder survives the exception and a method's return value does not.
     *
     * @param invocation the seeded datasets, the pinned clock, the codec and the recorder
     * @return {@code null}, meaning the recorder holds the outcome
     * @throws Exception if the tasklet fails. {@link AbendException} is an observation the harness
     *     records; anything else is a defect and the harness reports it as one
     */
    private UnitOutcome runThroughTheJob(Invocation invocation) throws Exception {
        return run(invocation, true);
    }

    /**
     * Drives the case through {@link CustomerService} - the {@code SERVICE} shape.
     *
     * <p>{@link CustomerService#readAndPrintCustomerFileTo(SysoutSink)} is the production streaming
     * entry point and the shortest path to the decision logic there is: one Java method call on one
     * Java object, with the {@code SYSOUT} destination being the harness's recorder. Every line is
     * handed over as it is emitted, so the three lines of a fatal arm are recorded before the abend
     * leaves the method.
     *
     * @param invocation the seeded datasets, the pinned clock, the codec and the recorder
     * @return {@code null}, meaning the recorder holds the outcome
     * @throws Exception if the service fails; declared for symmetry with the tasklet shape so both
     *     adapters satisfy {@link ParityHarness.ParityUnit} identically
     */
    private UnitOutcome runThroughTheService(Invocation invocation) throws Exception {
        return run(invocation, false);
    }

    /**
     * Constructs the unit for one case, runs one complete pass, and records what the pass produced.
     *
     * <p>One implementation behind both shapes, because the two differ only in which method is called:
     * the seeding, the backend selection, the final-state reporting and the tidy-up are properties of
     * the case rather than of the entry point, and writing them twice would be writing two things that
     * had to stay identical.
     *
     * <p>The final state is reported inside a {@code finally} block so it is reported on the abend path
     * too - "the dataset the run failed on still holds exactly what it held" is an assertion worth
     * making - and the relation is shut down in an outer one, so no case leaves an in-memory database
     * behind for the next.
     *
     * @param invocation the seeded datasets, the pinned clock, the codec and the recorder
     * @param throughTheJob whether to drive the tasklet or the service directly
     * @return {@code null}, meaning the recorder holds the outcome
     * @throws Exception if the run fails; {@link Tasklet#execute} declares it, and an abend travels
     *     out through it to the harness, which records the return code it carries
     */
    private UnitOutcome run(Invocation invocation, boolean throughTheJob) throws Exception {
        Scenario scenario = scenarioFor(invocation.caseId());
        SeededDataset seeded = invocation.hasDataset(DD_NAME) ? invocation.dataset(DD_NAME) : null;
        UnitOutcome.Builder recorder = invocation.recorder();
        SysoutSink sysout = recorder::display;

        if (scenario.arranged()) {
            // A status no relation can report. Nothing is stored, so there is no final state to read
            // back and none is reported: the case asserts through its displayed lines and its return
            // code, which is where an arranged failure's behaviour actually shows.
            runUnit(stubbedCustomerMaster(scenario, seeded, invocation.caseId()), sysout,
                    throughTheJob, recorder);
            return null;
        }

        JdbcTemplate template = freshDatabase();
        boolean ranToCompletion = false;
        try {
            if (seeded != null) {
                createRelation(template, seeded.recordLength());
                seedRelation(template, seeded);
            }
            try {
                runUnit(new CustomerRepository(template, datasetBindings(), DATASET_CHARSET,
                        RecordImageForm.CHARACTER), sysout, throughTheJob, recorder);
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
     * Runs the program once over the given customer master and records the return code it ended with.
     *
     * <p>A normal end records {@link CustomerService#RETURN_CODE_NORMAL_END}, because {@code GOBACK} at
     * {@code app/cbl/CBCUS01C.cbl:L87} never touches {@code RETURN-CODE}. A fatal arm never reaches
     * that statement: it leaves by {@link AbendException}, and the harness takes the code the abend
     * carries instead.
     *
     * @param repository the customer master this run reads through
     * @param sysout where every displayed line goes
     * @param throughTheJob whether to drive the tasklet or the service directly
     * @param recorder where the return code is reported
     * @throws AbendException if the open, a read or the close reports a status the program treats as
     *     fatal - which is an observation, not a failure
     * @throws Exception if the tasklet itself fails, which {@link Tasklet#execute} declares
     */
    private void runUnit(CustomerRepository repository, SysoutSink sysout, boolean throughTheJob,
            UnitOutcome.Builder recorder) throws Exception {
        CustomerService service = new CustomerService(repository);
        if (throughTheJob) {
            RepeatStatus status = customerFileReaderJob(service, sysout)
                    .customerFileDisplayTasklet()
                    .execute(stepContribution(), chunkContext());
            if (status != RepeatStatus.FINISHED) {
                throw new IllegalStateException("The " + PROGRAM + " tasklet reported " + status
                        + " rather than " + RepeatStatus.FINISHED + ". app/jcl/READCUST.jcl declares "
                        + "one step running one complete pass over the customer master, so a tasklet "
                        + "that asks to be repeated would read the dataset from its first record "
                        + "again and display every record twice more.");
            }
        } else {
            service.readAndPrintCustomerFileTo(sysout);
        }

        // GOBACK at app/cbl/CBCUS01C.cbl:L87.
        recorder.returnCode(CustomerService.RETURN_CODE_NORMAL_END);
    }

    /**
     * Reports what {@code CUSTFILE} holds after the run, read back from the relation rather than echoed
     * from the seed.
     *
     * <p>Reading it back is the point. {@code CBCUS01C} opens the dataset {@code INPUT} and issues no
     * {@code WRITE} and no {@code REWRITE}, so "the stored records are still exactly the stored
     * records" is a real assertion about a program the plan names {@code CustomerRepository} - and
     * echoing the seed back would assert nothing at all, because the seed is what the case declared.
     *
     * <p>The rows are ordered by the record image ascending, which is the order the browse itself reads
     * them in: {@code app/cbl/CBCUS01C.cbl:L29-L33} declares {@code CUSTFILE} as
     * {@code ORGANIZATION INDEXED}, {@code ACCESS MODE SEQUENTIAL}, {@code RECORD KEY FD-CUST-ID}, so
     * key order and not insertion order is what a sequential pass sees, and {@code CUST-ID} occupies
     * the first nine bytes of the image. That the browse really does reorder a backwards-loaded
     * relation is pinned directly, by {@code CustomerRepositoryTest}'s "delivers records in ascending
     * key order even when the relation is seeded backwards" and its companion assertion that every
     * browse statement carries an explicit ascending {@code ORDER BY}.
     *
     * <p>Nothing is reported for a case that declared no dataset. {@code case08} has no customer master
     * at all, so there is no state to describe, and describing one would be an invention rather than an
     * observation.
     *
     * @param template the template over this case's relation
     * @param seeded the dataset as it was seeded, or {@code null} when the case declared none
     * @param recorder where the final state is reported
     */
    private void reportFinalState(JdbcTemplate template, SeededDataset seeded,
            UnitOutcome.Builder recorder) {
        if (seeded == null) {
            return;
        }
        List<String> stored = template.queryForList(
                "SELECT " + RECORD_IMAGE_COLUMN + " FROM \"" + TEST_DSNAME + "\" ORDER BY "
                        + RECORD_IMAGE_COLUMN,
                String.class);
        recorder.finalState(DD_NAME, CustomerRecord.LAYOUT, stored);
    }

    // =================================================================================================
    // Backend one: a private in-memory relation with one record-image column.
    // =================================================================================================

    /**
     * A private in-memory database for one case, with no relation in it yet.
     *
     * <p>The name carries a fresh {@link UUID}, so no two invocations - in any order, repeated, or
     * concurrent - can reach each other's data. A counter would have done the same job and would have
     * been static mutable state, which this class has none of.
     *
     * <p>{@code DB_CLOSE_DELAY=-1} keeps the database alive between operations, which is required
     * rather than convenient: the template borrows and returns a connection per operation, and an
     * in-memory database is otherwise discarded with its last connection - taking the relation with it
     * between the {@code OPEN} and the first {@code READ}.
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
     * 500, so a case seeding a row of another width really does store a row of that width instead of
     * having the difference padded away by the backend.
     *
     * @param template the template over this case's database
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
     * order whatever order the records were loaded in, so the seed must be free to disagree with the
     * read order: sorting it here would make the ordering untestable from a case file at all, and it
     * is {@code CustomerRepositoryTest} that loads a relation backwards and asserts the ascending read.
     *
     * @param template the template over this case's relation
     * @param seeded the dataset as the harness seeded it
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
     * differ was about to render - and replacing it with one raised while tidying up would hide exactly
     * the finding the case exists to produce. Nothing is lost by not reporting it either: the database
     * is private to this one invocation and unreachable from any other.
     *
     * <p>Nothing about the failure is logged. The only thing a driver's message could add here is the
     * record it was handed, and a customer row carries {@code CUST-SSN},
     * {@code CUST-DOB-YYYY-MM-DD}, {@code CUST-GOVT-ISSUED-ID} and the customer's names.
     *
     * @param template the template over this case's database
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

    // =================================================================================================
    // Backend two: a stubbed customer master, for the statuses no relation can report.
    // =================================================================================================

    /**
     * Builds the customer master an arranged case reads through: an {@code OPEN} that answers as the
     * scenario says, a {@code READ} sequence assembled from the case's own declared rows, and a
     * {@code CLOSE} that either succeeds or refuses.
     *
     * <p>Mockito rather than a hand-written fake because {@link CustomerFile} is {@code final}, which is
     * the established idiom for it in this module and needs no build change: the inline mock maker is
     * the default from Mockito 5 onward. {@link CustomerRepository#datasetCharset()} is stubbed because
     * {@link CustomerService}'s constructor reads it to build its codec, so leaving it unstubbed would
     * fail the construction rather than the case.
     *
     * <p>Only what the scenario needs is stubbed. A scenario whose {@code OPEN} fails returns before the
     * read sequence is even assembled, because {@code app/cbl/CBCUS01C.cbl:L132} abends inside
     * {@code 0000-CUSTFILE-OPEN} and no read and no close is ever reached - stubbing them would describe
     * a run that does not happen.
     *
     * @param scenario what this case's run must encounter
     * @param seeded the seeded {@code CUSTFILE}, whose rows become the delivered records
     * @param caseId the case being arranged, for the diagnostic if it declared no dataset
     * @return the stubbed repository
     */
    private CustomerRepository stubbedCustomerMaster(Scenario scenario, SeededDataset seeded,
            String caseId) {
        if (seeded == null) {
            throw new IllegalStateException(PROGRAM + '/' + caseId + " arranges a backend outcome but "
                    + "declares no " + DD_NAME + " input. A case that arranges a status is describing "
                    + "what happens to a dataset it has, so it must declare the dataset - and a case "
                    + "whose point is that there is no dataset at all arranges nothing and meets the "
                    + "real backend instead, which is what case08 does.");
        }

        CustomerRepository repository = Mockito.mock(CustomerRepository.class);
        Mockito.when(repository.datasetCharset()).thenReturn(DATASET_CHARSET);

        CustomerFile custFile = Mockito.mock(CustomerFile.class);
        Mockito.when(repository.openInput()).thenReturn(custFile);
        Mockito.when(custFile.openStatus()).thenReturn(scenario.openStatus());
        if (!FileStatus.isOk(scenario.openStatus())) {
            return repository;
        }

        List<ReadResult> sequence = readSequence(scenario, seeded);
        Mockito.when(custFile.readNext())
                .thenReturn(sequence.get(0),
                        sequence.subList(1, sequence.size()).toArray(ReadResult[]::new));

        // The handle tracks whether it has been closed, exactly as the real one does, so that
        // CustomerService's silent release on the way out is a no-op after 9000-CUSTFILE-CLOSE has run
        // and a single quiet close after a fatal read - which is what the real handle's idempotence
        // gives it. A plain stubbed value would make the release issue a second close, and a second
        // close of a refused dataset would be a second refusal nobody asked for.
        AtomicBoolean closed = new AtomicBoolean();
        Mockito.when(custFile.closeFile()).thenAnswer(invocation -> {
            closed.set(true);
            return scenario.closeStatus();
        });
        Mockito.when(custFile.isClosed()).thenAnswer(invocation -> closed.get());
        return repository;
    }

    /**
     * Assembles what the successive {@code READ}s at {@code app/cbl/CBCUS01C.cbl:L93} report: zero or
     * more successful reads over the seeded rows, then the read that ends the loop or abends it.
     *
     * <p>The rows are delivered in <strong>ascending record-image order</strong>, which for this
     * copybook is ascending {@code CUST-ID} order because {@code CUST-ID PIC 9(09)} occupies the first
     * nine bytes. That is what a KSDS browse of a file keyed on {@code CUST-ID} reports, and it is what
     * the real relation reports too, so the two backends deliver one order between them rather than
     * two.
     *
     * <p>A delivered record carries both views of the same bytes, because {@code READ ... INTO} does:
     * the decoded {@link CustomerRecord} for anything that branches on a value, and the row's own image
     * for the two {@code DISPLAY}s, which write the record area including its {@code FILLER}. They are
     * built from one string by the real {@link CustomerRecord#decode(String, Charset)}, so they cannot
     * disagree and no expectation here rests on a hand-built record.
     *
     * @param scenario the case's arranged shape
     * @param seeded the seeded rows
     * @return the read results in the order the browse reports them; never empty
     */
    private List<ReadResult> readSequence(Scenario scenario, SeededDataset seeded) {
        List<String> rows = new ArrayList<>(seeded.rows());
        Collections.sort(rows);

        int delivered = scenario.readFails() ? scenario.failingRead() : rows.size();
        if (delivered > rows.size()) {
            throw new IllegalStateException("The scenario for " + PROGRAM + " asks for " + delivered
                    + " delivered record(s) but only " + rows.size() + " row(s) were seeded. The "
                    + "scenario and the case file's \"inputs\" describe the same run and must agree.");
        }

        List<ReadResult> sequence = new ArrayList<>(delivered + 1);
        for (int index = 0; index < delivered; index++) {
            String image = rows.get(index);
            sequence.add(ReadResult.found(CustomerRecord.decode(image, DATASET_CHARSET), image));
        }

        // The terminating read: '10' ends the loop through APPL-EOF at L107, and anything else reaches
        // the WHEN OTHER arm at L101 and abends.
        sequence.add(scenario.readFails()
                ? ReadResult.of(scenario.failingReadStatus())
                : ReadResult.endOfFile());
        return sequence;
    }

    // =================================================================================================
    // The unit and the collaborators its constructors mandate.
    // =================================================================================================

    /**
     * The job under test, over one case's customer master, writing every {@code DISPLAY} to the given
     * sink.
     *
     * @param service the program, already wired to this case's backend
     * @param sysout where the displayed lines are captured
     * @return the job
     */
    private CustomerFileReaderJob customerFileReaderJob(CustomerService service, SysoutSink sysout) {
        return new CustomerFileReaderJob(batchScaffolding(), service, new DeclaredBean<>(sysout));
    }

    /**
     * The dataset catalogue, naming one dataset under both of the keys the repository requires.
     *
     * <p>{@code app/csd/CARDDEMO.CSD} defines the CICS file {@code CUSTDAT} and
     * {@code app/jcl/READCUST.jcl:L9} defines the batch DD {@code CUSTFILE} over <em>one</em> customer
     * master, and {@link CustomerRepository} refuses a catalogue in which the two name different
     * datasets - so both keys name the same stand-in dataset here. The declared record width is 500 and
     * the declared key width is 9, from {@code CUST-ID PIC 9(09)}; both are read from the model rather
     * than written as literals, so a copybook change surfaces here as a failure rather than as a
     * disagreement nobody notices.
     *
     * @return the catalogue
     */
    private DatasetBindings datasetBindings() {
        DatasetBinding customer = new DatasetBinding(TEST_DSNAME, DatasetBinding.KSDS, false, "FB",
                null, CustomerRecord.RECORD_LENGTH, "CVCUS01Y", CustomerRepository.KEY_LENGTH, null,
                null, null);
        DatasetBindings catalogue = new DatasetBindings();
        catalogue.put(CustomerRepository.CICS_FILE_NAME, customer);
        catalogue.put(DD_NAME, customer);
        return catalogue;
    }

    /**
     * The batch scaffolding, carrying the {@code carddemo.jobs} contract for this job.
     *
     * <p>The contract is what {@code application.yml} declares and what {@code app/jcl/READCUST.jcl}
     * supports: program {@code CBCUS01C}, no parameters, and the one step
     * {@link CustomerFileReaderJob#REQUIRED_STEPS} names, which is <strong>not</strong> gated on a
     * preceding exit code because that JCL carries no {@code COND} and declares only the one step. The
     * step sequence is taken from the job's own published requirement rather than rebuilt here, so the
     * contract this test supplies cannot drift from the one the job validates.
     *
     * <p>The job repository and the transaction manager are mocked, and they are never used: nothing
     * here launches a job or opens a step, so no batch metadata is written. They exist because the
     * constructor takes them.
     *
     * @return the scaffolding
     */
    private BatchConfig batchScaffolding() {
        JobContracts contracts = new JobContracts();
        contracts.put(CustomerFileReaderJob.JOB_KEY, new JobContract(PROGRAM, List.of(),
                CustomerFileReaderJob.REQUIRED_STEPS, null, Map.of()));
        return new BatchConfig(new DeclaredBean<>(Mockito.mock(JobRepository.class)),
                new DeclaredBean<>(Mockito.mock(PlatformTransactionManager.class)), contracts,
                datasetBindings());
    }

    /**
     * A step contribution for one tasklet call.
     *
     * <p>The tasklet reports its read count to the framework through this, which is step metadata rather
     * than COBOL output - {@code CBCUS01C} keeps no counter of its own and displays none - so it is
     * accepted and not compared.
     *
     * @return a fresh contribution
     */
    private StepContribution stepContribution() {
        return new StepContribution(new StepExecution(CustomerService.STEP_NAME, new JobExecution(1L)));
    }

    /**
     * A chunk context for one tasklet call.
     *
     * <p>The tasklet does not consult it: a tasklet that runs once has no per-chunk state, and this
     * program's only restart semantics are to read the dataset from its first record again. It exists
     * because {@link Tasklet#execute} takes it.
     *
     * @return a fresh chunk context
     */
    private ChunkContext chunkContext() {
        return new ChunkContext(new StepContext(
                new StepExecution(CustomerService.STEP_NAME, new JobExecution(2L))));
    }

    /**
     * An {@link ObjectProvider} that always yields the bean it was given.
     *
     * <p>The job resolves its {@code SYSOUT} sink through a provider so a caller can supply one without
     * reconfiguring the bean, which is exactly what a parity case needs: with the sink declared, the
     * displayed lines reach the recorder instead of the process's standard output.
     *
     * @param bean the bean to yield
     * @param <T> the bean type
     */
    private record DeclaredBean<T>(T bean) implements ObjectProvider<T> {

        @Override
        public T getObject() {
            return bean;
        }
    }
}
