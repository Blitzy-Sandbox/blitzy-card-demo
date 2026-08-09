package com.vsergeychik.carddemo.account;

import com.vsergeychik.carddemo.card.CardXrefRepository;
import com.vsergeychik.carddemo.card.CardXrefRepository.BrowseCursor;
import com.vsergeychik.carddemo.card.CardXrefRepository.ReadResult;
import com.vsergeychik.carddemo.card.model.CardXrefRecord;
import com.vsergeychik.carddemo.common.AbendException;
import com.vsergeychik.carddemo.common.FileStatus;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.config.CobolCharsetConfig;
import com.vsergeychik.carddemo.config.BatchConfig;
import com.vsergeychik.carddemo.config.BatchConfig.JobContract;
import com.vsergeychik.carddemo.config.BatchConfig.StepContract;
import com.vsergeychik.carddemo.config.BatchConfig.StopSignal;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.util.Objects;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The Java translation of {@code app/cbl/CBACT03C.cbl} - the CardDemo batch program that reads the
 * card cross-reference file and prints it - assembled as the single-step Spring Batch job that
 * {@code app/jcl/READXREF.jcl} runs.
 *
 * <h2>THE CLASS NAME SAYS "UPDATE"; THIS PROGRAM PERFORMS NO UPDATES AT ALL</h2>
 *
 * <p>Read that again, because it is the single most misleading name in this migration and the whole
 * reason this section is the first thing in this file. The name {@code AccountBalanceUpdateJob} is
 * <strong>mandated verbatim</strong> by the migration plan and is deliberately <em>not</em>
 * corrected here, because a mandated name never authorises a change of behaviour: <strong>the name
 * comes from the plan and the behaviour comes from the COBOL source</strong>. Three separate things
 * the name promises are absent from the source, and all three were verified by reading it:
 *
 * <ul>
 *   <li><strong>It does not update anything.</strong> {@code app/cbl/CBACT03C.cbl} contains no
 *       {@code WRITE}, no {@code REWRITE} and no {@code DELETE} statement of any kind. Its one file
 *       is opened {@code OPEN INPUT} at {@code :120}. Nothing in this class calls a write-side
 *       repository method, and {@link CardXrefRepository} does not publish one to call - it exposes
 *       keyed reads, an alternate-index read and {@link CardXrefRepository#openBrowse()}, and
 *       nothing else.</li>
 *   <li><strong>It does not touch a balance.</strong> No field named for a balance is read, computed
 *       or displayed. The record it handles is {@code CARD-XREF-RECORD}
 *       ({@code app/cpy/CVACT03Y.cpy}), which ties a card number to a customer id and an account id
 *       and holds no monetary field whatsoever. There is consequently no {@code BigDecimal} in this
 *       file and no arithmetic beyond counting records and reproducing the program's
 *       {@code APPL-RESULT} ladder.</li>
 *   <li><strong>It does not read the account master.</strong> Its one dataset is the cross reference,
 *       DD {@code XREFFILE} ({@code app/jcl/READXREF.jcl}), which is why this class in the
 *       {@code account} package is a deliberate consumer of the {@code card} package's types: one
 *       Java type per copybook, shared by every program that copies it, never duplicated per
 *       program.</li>
 * </ul>
 *
 * <p>The source states its own purpose in one line at {@code app/cbl/CBACT03C.cbl:5} -
 * {@code Function : Read and print account cross reference data file.} That is exactly and only what
 * this class does. <strong>Never "complete" the update the name implies.</strong>
 *
 * <h2>The parity trap: every record is displayed TWICE</h2>
 *
 * <p>{@code CBACT03C} has <strong>two active {@code DISPLAY CARD-XREF-RECORD} statements</strong>,
 * and both run for every record that is read successfully:
 *
 * <ol>
 *   <li>{@code :96}, inside {@code 1000-XREFFILE-GET-NEXT}, on the {@code '00'} arm immediately
 *       after {@code MOVE 0 TO APPL-RESULT};</li>
 *   <li>{@code :78}, in the mainline, immediately after the {@code PERFORM} of that paragraph
 *       returns with {@code END-OF-FILE} still {@code 'N'}.</li>
 * </ol>
 *
 * <p>So the same 50-byte record image is emitted <strong>twice, as two byte-identical consecutive
 * lines</strong>. That is not a defect in this translation and it must not be tidied away
 * (preserve-behaviour: the migration reproduces the legacy program's observable output, including
 * its redundancies). For the 50-record {@code app/data/ASCII/cardxref.txt} fixture the complete
 * output is therefore one start banner, <strong>100</strong> record lines and one end banner -
 * <strong>102 lines</strong>.
 *
 * <p>This is the most easily-missed difference in the whole reader family, because three programs of
 * 178 to 193 lines look identical and differ by one line each:
 *
 * <table border="1">
 *   <caption>The one-line difference between the three near-identical readers</caption>
 *   <tr><th>Program</th><th>Dataset</th><th>Lines per record</th><th>Why</th></tr>
 *   <tr><td>{@code CBACT01C}</td><td>{@code ACCTFILE}</td><td>13</td>
 *       <td>the inner statement is {@code PERFORM 1100-DISPLAY-ACCT-RECORD}, a field-by-field
 *           display, and the mainline adds the raw image</td></tr>
 *   <tr><td>{@code CBACT02C}</td><td>{@code CARDFILE}</td><td>1</td>
 *       <td>the inner display is commented out; only the mainline's remains</td></tr>
 *   <tr><td><strong>{@code CBACT03C}</strong></td><td>{@code XREFFILE}</td><td><strong>2</strong></td>
 *       <td>the inner statement is a live {@code DISPLAY} of the same record area the mainline
 *           displays, so the raw image is emitted twice</td></tr>
 * </table>
 *
 * <h2>What the JCL contracts</h2>
 *
 * <p>{@code app/jcl/READXREF.jcl} is a single-step job. Its five statements, with the two dataset
 * names left where they belong - in configuration, never in Java (gate G46):
 * <pre>
 * //STEP05   EXEC PGM=CBACT03C            :22   no PARM
 * //STEPLIB  DD DISP=SHR,DSN=...          :23   the load library, which has no Java counterpart
 * //XREFFILE DD DISP=SHR,DSN=...          :25   bound by carddemo.datasets.XREFFILE
 * //SYSOUT   DD SYSOUT=*                  :27   the SysoutSink below
 * //SYSPRINT DD SYSOUT=*                  :28   likewise
 * </pre>
 *
 * <p>Three facts follow and all three are enforced in this class's constructor rather than merely
 * documented: the step is named {@value #STEP_NAME}; the {@code EXEC PGM=} carries
 * <strong>no {@code PARM}</strong>, so this job declares <strong>no job parameters</strong>; and its
 * one input DD is {@value #XREFFILE_DD_NAME}, whose dataset name is resolved from
 * {@code carddemo.datasets.XREFFILE} and never written in Java.
 *
 * <h2>A Tasklet, not a chunk-oriented step</h2>
 *
 * <p>{@code CBACT03C} is a single pass that opens once, reads until end of file, closes once and
 * emits its output in strict record order. A chunk-oriented step would relocate the commit
 * boundaries and with them the order in which lines reach {@code SYSOUT}, so the step body is a
 * {@link Tasklet} built through {@link BatchConfig#taskletStep(String, Tasklet)}, which binds the
 * shared job repository, the shared abend listener and the module's transaction manager. Chunk steps
 * are correct for exactly two jobs in this estate and this is not one of them.
 *
 * <p>Nothing here carries Spring Batch's {@code EnableBatchProcessing} annotation, and its absence is
 * load-bearing rather than an omission: under Spring Boot 3 that annotation <em>disables</em> batch
 * auto-configuration, so declaring it would take away the very {@code JobRepository} this job is built
 * on. Boot's auto-configuration supplies that repository and {@link BatchConfig} owns the one
 * {@code PlatformTransactionManager}; declaring either again would make the framework's by-type
 * resolution ambiguous and stop the context from starting.
 *
 * <h2>Reaching the program body without a launcher</h2>
 *
 * <p>The program is {@link #execute(SysoutSink)}, a plain method taking the destination its
 * {@code SYSOUT} lines go to. Every branch of the translation - the three {@code APPL-RESULT}
 * ladders, both {@code 88}-level conditions in each truth state, and all three abend paths - is
 * therefore reachable from an ordinary unit test with no {@code JobLauncher}, no application context
 * and no database in the way. The {@link #accountBalanceUpdateTasklet()} is a thin adapter over it
 * and adds no logic of its own.
 *
 * <h2>No user rules govern this file</h2>
 *
 * <p>{@code review_rules} returns exactly one line - "No user rules provided." - which is the
 * complete document. Their absence is not permission to lower the bar: the enterprise practices the
 * migration plan substitutes are what bind here, and the ones this file turns on are
 * preserve-the-legacy-behaviour (the duplicated display above, and the dead {@code MOVE 8} in the
 * open paragraph), reference inputs are immutable (nothing under {@code app/cbl}, {@code app/cpy},
 * {@code app/jcl} or {@code app/data} is written), explicit over implicit (the code page is named,
 * the dataset name comes from configuration, and there is no wildcard import), and no static mutable
 * state (every collaborator is a final field set by the constructor, and every piece of the COBOL's
 * {@code WORKING-STORAGE} is a local of {@link #execute(SysoutSink)}).
 *
 * @see CardXrefRepository#openBrowse()
 * @see CardXrefRecord
 */
@Configuration(AccountBalanceUpdateJob.CONFIGURATION_BEAN_NAME)
public class AccountBalanceUpdateJob {

    // =================================================================================================
    // Identity. Every name here is transcribed from app/jcl/READXREF.jcl, app/cbl/CBACT03C.cbl or
    // app/java/src/main/resources/application.yml, and none is invented.
    // =================================================================================================

    /**
     * This configuration class's own bean name.
     *
     * <p>Stated explicitly, and it has to be. Component scanning would otherwise name the class bean
     * after its decapitalised simple name - {@value #JOB_NAME} - which is exactly the name the
     * {@link Job} bean below must carry, and two definitions under one name stop the context from
     * starting. Naming the configuration for what it is leaves {@value #JOB_NAME} free for the job
     * itself, which is the name an operator launches and the name the batch metadata records.
     */
    public static final String CONFIGURATION_BEAN_NAME = "accountBalanceUpdateJobConfiguration";

    /**
     * The configuration key of this job's contract under {@code carddemo.jobs}, spelled exactly as
     * {@code application.yml} declares it.
     *
     * <p>The contract there records the program, the empty parameter list and the single step, and
     * this class reads all three back and refuses to start if any of them disagrees with the JCL.
     */
    public static final String JOB_KEY = "account-balance-update-job";

    /**
     * The job's name in the Spring Batch metadata, which is also its identity for a re-run.
     *
     * <p>The camel-case form of the mandated class name, matching the convention the rest of the
     * module's batch jobs follow.
     */
    public static final String JOB_NAME = "accountBalanceUpdateJob";

    /**
     * The {@link Step} bean's name.
     *
     * <p>Qualified by the job rather than named {@value #STEP_NAME}, because three other single-step
     * readers in this estate - {@code READACCT}, {@code READCARD} and {@code READCUST} - also name
     * their JCL step {@code STEP05}. The batch metadata records {@value #STEP_NAME}, which is what an
     * operator compares against the JCL; the bean name only has to be unique in the container.
     */
    public static final String STEP_BEAN_NAME = "accountBalanceUpdateStep";

    /** The COBOL {@code PROGRAM-ID} this job translates: {@code app/cbl/CBACT03C.cbl:23}. */
    public static final String PROGRAM_NAME = "CBACT03C";

    /**
     * Diagnostics for the one thing this program does that its SYSOUT cannot carry: a failure to close the
     * cross-reference browse while unwinding from an abend. Nothing on a successful path is logged.
     */
    private static final Log LOG = LogFactory.getLog(AccountBalanceUpdateJob.class);

    /**
     * The step name, transcribed from {@code app/jcl/READXREF.jcl}'s only step.
     *
     * <p>The JCL step is {@code //STEP05 EXEC PGM=CBACT03C}, so the Spring Batch step carries the
     * same name: an operator reading a failed execution sees the step the mainframe would have named.
     */
    public static final String STEP_NAME = "STEP05";

    /**
     * The DD name of this job's one input dataset, as {@code app/jcl/READXREF.jcl} declares it and as
     * {@code app/cbl/CBACT03C.cbl:29} assigns it - {@code SELECT XREFFILE-FILE ASSIGN TO XREFFILE}.
     *
     * <p>It is a configuration key, not a dataset name. The dataset itself is whatever
     * {@code carddemo.datasets.XREFFILE} binds, which is how no dataset name appears in this file.
     */
    public static final String XREFFILE_DD_NAME = "XREFFILE";

    /**
     * The bean name of the module's active dataset code page.
     *
     * <p>Taken from {@link CobolCharsetConfig#DATASET_CHARSET_BEAN_NAME}, the class that publishes the
     * bean, rather than restated as a literal here. A second spelling of a bean name is a rename waiting
     * to break silently: the qualifier would still compile, still resolve at startup against the old
     * name, and fail only when the publisher moved on. Kept as a constant of this class so a caller or a
     * test that referred to it still can.
     */
    public static final String DATASET_CHARSET_BEAN_NAME =
            CobolCharsetConfig.DATASET_CHARSET_BEAN_NAME;

    // =================================================================================================
    // The literals this program displays. Byte-exact, and every one of them names XREFFILE - the three
    // sibling readers differ only in that word, so a copied message would report the wrong dataset.
    // =================================================================================================

    /** {@code app/cbl/CBACT03C.cbl:71} - the first line of output. */
    public static final String START_OF_EXECUTION = "START OF EXECUTION OF PROGRAM CBACT03C";

    /** {@code app/cbl/CBACT03C.cbl:85} - the last line of output, emitted after the close. */
    public static final String END_OF_EXECUTION = "END OF EXECUTION OF PROGRAM CBACT03C";

    /** {@code app/cbl/CBACT03C.cbl:129} - the open paragraph's failure message. */
    public static final String ERROR_OPENING_XREFFILE = "ERROR OPENING XREFFILE";

    /** {@code app/cbl/CBACT03C.cbl:110} - the read paragraph's failure message. */
    public static final String ERROR_READING_XREFFILE = "ERROR READING XREFFILE";

    /** {@code app/cbl/CBACT03C.cbl:147} - the close paragraph's failure message. */
    public static final String ERROR_CLOSING_XREFFILE = "ERROR CLOSING XREFFILE";

    /**
     * How many lines each successfully read record produces: <strong>two</strong>, from
     * {@code app/cbl/CBACT03C.cbl:96} and {@code :78}.
     *
     * <p>A named constant rather than a literal two, so the parity expectation is stated once and can
     * be cited from a test rather than re-derived.
     */
    public static final int DISPLAYS_PER_RECORD = 2;

    /**
     * How many lines an execution emits regardless of how many records it reads: the start banner at
     * {@code app/cbl/CBACT03C.cbl:71} and the end banner at {@code :85}.
     *
     * <p>Both are unconditional, so an empty dataset still produces two lines - and the end banner
     * appears even so, because it follows the close rather than the loop.
     */
    public static final int BANNER_LINES = 2;

    // =================================================================================================
    // The APPL-RESULT vocabulary. app/cbl/CBACT03C.cbl:61-63 declares
    //   01 APPL-RESULT PIC S9(9) COMP.
    //      88 APPL-AOK VALUE 0.
    //      88 APPL-EOF VALUE 16.
    // The two 88-levels are FileStatus.APPL_AOK and FileStatus.APPL_EOF, the shared vocabulary every
    // batch consumer reads them from. The two remaining values the program moves have no 88-level and
    // are aliased here from the shared constants that carry them, so the ladder can be read against
    // the COBOL without leaving this file.
    // =================================================================================================

    /**
     * The value {@code 0000-XREFFILE-OPEN} seeds {@code APPL-RESULT} with at
     * {@code app/cbl/CBACT03C.cbl:119} ({@code MOVE 8 TO APPL-RESULT}) and that
     * {@code 9000-XREFFILE-CLOSE} seeds it with at {@code :137}
     * ({@code ADD 8 TO ZERO GIVING APPL-RESULT}).
     *
     * <p>Both seeds are overwritten by the very next test in the same paragraph and neither is ever
     * read, so this is dead code. It is reproduced anyway, because tidying it would be an
     * unrequested change to a program whose observable behaviour is the migration's contract, and
     * because a reader diffing this class against the COBOL must find every statement accounted for.
     */
    public static final int APPL_RESULT_ASSUMED_FAILURE = AbendException.RETURN_CODE_ASSUMED_FAILURE;

    /**
     * The value all three paragraphs move for a status other than the ones they name -
     * {@code app/cbl/CBACT03C.cbl:101}, {@code :124} and {@code :142} - and therefore the
     * {@code RETURN-CODE} this program's abend carries.
     */
    public static final int APPL_RESULT_FATAL = CardXrefRepository.APPL_RESULT_FATAL;

    // =================================================================================================
    // END-OF-FILE, the program's one control flag: 01 END-OF-FILE PIC X(01) VALUE 'N' at
    // app/cbl/CBACT03C.cbl:65. Modelled as the two-valued character field it is, rather than as a
    // boolean, so the mainline's two guards read against the COBOL statement for statement.
    // =================================================================================================

    /** {@code END-OF-FILE} while records remain: the {@code VALUE 'N'} the field is declared with. */
    public static final String NOT_AT_END_OF_FILE = "N";

    /** {@code END-OF-FILE} once the read has reported {@code '10'}: the {@code 'Y'} of {@code :108}. */
    public static final String AT_END_OF_FILE = "Y";

    /**
     * What is named in a codec diagnostic when a record image cannot be rendered.
     *
     * <p>The subject names the <em>field</em>, never its content: a cross-reference record carries a
     * card number, so its bytes belong in {@code SYSOUT} where the program puts them and nowhere
     * else.
     */
    private static final String RECORD_DISPLAY_SUBJECT =
            "the CARD-XREF-RECORD display image of app/cbl/CBACT03C.cbl:78 and :96";

    /**
     * The line terminator the default {@code SYSOUT} sink writes after each display.
     *
     * <p>A single line feed, stated as a character so it is encoded in the same code page as the line
     * it terminates, rather than the platform's line separator.
     */
    private static final char SYSOUT_LINE_TERMINATOR = '\n';

    // =================================================================================================
    // Collaborators. Every one is final and set by the constructor, and there is no static mutable
    // state anywhere in this file: the COBOL's WORKING-STORAGE becomes locals of execute(SysoutSink),
    // never fields, so two concurrent executions cannot see each other's END-OF-FILE flag or record
    // area.
    // =================================================================================================

    /** The seam that hands out job and step builders already bound to the shared batch plumbing. */
    private final BatchConfig batchConfig;

    /**
     * The cross reference, reached only through {@link CardXrefRepository#openBrowse()}.
     *
     * <p>The browse is over the <strong>base {@code CCXREF} cluster in card-number key order</strong>,
     * which is what {@code app/cbl/CBACT03C.cbl:29-33} declares: {@code ORGANIZATION IS INDEXED},
     * {@code ACCESS MODE IS SEQUENTIAL}, {@code RECORD KEY IS FD-XREF-CARD-NUM}. That key is
     * <strong>character data, not a number</strong>: the file description at {@code :38-40} splits the
     * record into {@code FD-XREF-CARD-NUM PIC X(16)} and {@code FD-XREF-DATA PIC X(34)}, together the
     * {@value CardXrefRecord#RECORD_LENGTH} bytes {@code app/cpy/CVACT03Y.cpy} declares, and a
     * 16-digit card number with a leading zero sorts and compares as the sixteen characters it is. The
     * {@code CXACAIX} alternate-index path over the same cluster is emphatically <strong>not</strong>
     * used - it is a different access path in a different key order, and this program does not open
     * it - so {@link CardXrefRepository#readByAccountIdViaAltIndex(long)} is never called from here.
     */
    private final CardXrefRepository cardXrefRepository;

    /**
     * The codec for the dataset code page, which is what renders a decoded record back to the
     * 50 bytes {@code DISPLAY CARD-XREF-RECORD} writes.
     *
     * <p>Constructed once from the injected charset, and the only place in this class that knows
     * anything about bytes. Its constructor rejects a code page that cannot hold a digit or a space
     * in one byte, so a misconfigured charset fails at startup rather than at the first record.
     */
    private final FixedWidthCodec codec;

    /**
     * Where {@code SYSOUT} goes when the caller does not say.
     *
     * <p>Held as a provider rather than resolved in the constructor, so a deployment that publishes a
     * {@code SysoutSink} bean is honoured without this class needing one to exist, and the context
     * still starts when none does.
     */
    private final ObjectProvider<SysoutSink> sysoutSinkProvider;

    /**
     * The dataset this job reads, as configuration binds {@value #XREFFILE_DD_NAME}.
     *
     * <p>Resolved at construction so a missing or mis-declared binding fails at startup, and retained
     * only so it can be reported. It is never composed into a statement: the repository owns every
     * statement it issues.
     */
    private final String xrefFileDatasetName;

    /** The step name this job's configured contract declares, which must be {@value #STEP_NAME}. */
    private final String stepName;

    /**
     * Wires the job and validates, at startup, that the configured contract still says what
     * {@code app/jcl/READXREF.jcl} says.
     *
     * <p>The four guards below are not ceremony. Each one rejects a configuration that would make this
     * job read the wrong bytes, run the wrong step, or accept a parameter the JCL never passes - and
     * each fails now, when an operator can act on the message, rather than in the middle of a batch
     * window.
     *
     * @param batchConfig        the batch seam supplying job and step builders and the job contracts;
     *                           never {@code null}
     * @param cardXrefRepository the cross-reference repository, browsed in base-key order; never
     *                           {@code null}
     * @param datasetCharset     the module's active dataset code page, injected by the bean name
     *                           {@value #DATASET_CHARSET_BEAN_NAME} so it is stated explicitly rather
     *                           than taken from the platform; never {@code null}
     * @param sysoutSinkProvider provider for a deployment-supplied {@code SYSOUT} destination; never
     *                           {@code null}, though it may resolve to nothing, in which case
     *                           {@link #defaultSysoutSink()} is used
     * @throws NullPointerException  if any argument is {@code null}
     * @throws IllegalStateException if the configured contract names a different program, declares a
     *                               step named anything but {@value #STEP_NAME}, gates that step
     *                               behind a preceding step's exit code, declares any job parameter,
     *                               or binds {@value #XREFFILE_DD_NAME} to a dataset whose record
     *                               length is not the {@value CardXrefRecord#RECORD_LENGTH} bytes
     *                               {@code app/cpy/CVACT03Y.cpy} declares
     */
    public AccountBalanceUpdateJob(
            BatchConfig batchConfig,
            CardXrefRepository cardXrefRepository,
            @Qualifier(DATASET_CHARSET_BEAN_NAME) Charset datasetCharset,
            ObjectProvider<SysoutSink> sysoutSinkProvider) {

        this.batchConfig = Objects.requireNonNull(batchConfig, "The batch configuration seam is "
                + "required: it supplies the job repository, the transaction manager and the abend "
                + "listener, so no job class assembles Spring Batch plumbing of its own");
        this.cardXrefRepository = Objects.requireNonNull(cardXrefRepository, "The card cross-reference "
                + "repository is required: app/cbl/CBACT03C.cbl:29 assigns its one file to DD "
                + XREFFILE_DD_NAME + ", and this job reads nothing else");
        this.codec = new FixedWidthCodec(Objects.requireNonNull(datasetCharset, "A dataset charset is "
                + "required: the record this program displays is 50 bytes of fixed-width data, so the "
                + "code page is stated explicitly and never taken from the platform"));
        this.sysoutSinkProvider = Objects.requireNonNull(sysoutSinkProvider, "A SysoutSink provider is "
                + "required: DISPLAY output is emitted through an injected sink so it can be captured "
                + "and compared, never written straight to a stream from the program body");

        JobContract contract = batchConfig.contract(JOB_KEY);
        requireProgram(contract.program(), "carddemo.jobs." + JOB_KEY + ".program");
        requireNoJobParameters(contract);

        StepContract step = contract.step(STEP_NAME);
        requireProgram(step.program(), "carddemo.jobs." + JOB_KEY + ".steps[" + STEP_NAME
                + "].program");
        requireUngatedStep(step);
        this.stepName = step.name();

        // The DD name is a configuration key; what it resolves to is configuration's business. This
        // reads the binding for the two facts the job needs - which dataset, and how wide its records
        // are - and keeps neither the binding nor any statement built from it. The type is inferred
        // rather than named so this file's imports remain exactly its declared dependency set.
        var xrefFile = batchConfig.datasetBinding(JOB_KEY, XREFFILE_DD_NAME);
        requireCopybookRecordLength(xrefFile.recordLength());
        this.xrefFileDatasetName = requireUsableDatasetName(xrefFile.dsname());
        // This job's JCL names its input XREFFILE (app/jcl/READXREF.jcl:25-26); the repository it browses
        // is bound to the CICS file name CCXREF, because the online programs address it that way. Both
        // keys carry independent overrides in application.yml, so they can be pointed at different
        // datasets - and a step that read a dataset its own DD statement never named would do so silently
        // and with a correct-looking result. Proven equal here, once, so a divergence fails the context
        // instead of a run.
        batchConfig.requireSameDataset(JOB_KEY, XREFFILE_DD_NAME, CardXrefRepository.BASE_DD_NAME);
    }

    // =================================================================================================
    // Constructor guards. Each is a static function of its argument, so each is reachable from a plain
    // unit test with a hand-built contract and each failure message names the configuration key an
    // operator must correct.
    // =================================================================================================

    /**
     * Requires a configured program name to be the program this class translates.
     *
     * <p>A contract naming another program would run this job's step body against another program's
     * DD bindings, which is the kind of mismatch that produces plausible output from the wrong
     * dataset.
     *
     * @param configured the program name configuration declares
     * @param key        the configuration key it was read from, for the diagnostic
     * @throws IllegalStateException if it is not {@value #PROGRAM_NAME}
     */
    private static void requireProgram(String configured, String key) {
        if (!PROGRAM_NAME.equals(configured)) {
            throw new IllegalStateException(key + " is '" + configured + "', but "
                    + AccountBalanceUpdateJob.class.getSimpleName() + " translates " + PROGRAM_NAME
                    + " (app/cbl/CBACT03C.cbl), which app/jcl/READXREF.jcl runs as its only step. "
                    + "Correct the configured program name; do not repoint this class.");
        }
    }

    /**
     * Requires the configured contract to declare no job parameter.
     *
     * <p>{@code app/jcl/READXREF.jcl:22} is a bare {@code EXEC PGM=CBACT03C} with no {@code PARM}, so
     * the absence of parameters <em>is</em> the contract. A declared parameter would be a value this
     * program has no field to receive and no statement to read.
     *
     * @param contract the configured contract
     * @throws IllegalStateException if any parameter is declared
     */
    private static void requireNoJobParameters(JobContract contract) {
        if (!contract.parameters().isEmpty()) {
            throw new IllegalStateException("carddemo.jobs." + JOB_KEY + ".parameters declares "
                    + contract.parameters().size() + " parameter(s), but app/jcl/READXREF.jcl:22 runs "
                    + PROGRAM_NAME + " with no PARM at all and the program declares no LINKAGE "
                    + "SECTION. The empty list is the contract; declare no parameter here.");
        }
    }

    /**
     * Requires the configured step to be ungated.
     *
     * <p>{@code COND=(0,NE)} appears on three steps of {@code app/jcl/CREASTMT.JCL} and nowhere else
     * in this estate. {@code app/jcl/READXREF.jcl} carries no {@code COND}, and gating a job's only
     * step behind a preceding one would invent a condition the mainframe does not evaluate - and, for
     * a first step, would prevent the job from ever running.
     *
     * @param step the configured step contract
     * @throws IllegalStateException if the step requires a preceding zero exit code
     */
    private static void requireUngatedStep(StepContract step) {
        if (step.requirePrecedingExitCodeZero()) {
            throw new IllegalStateException("carddemo.jobs." + JOB_KEY + ".steps[" + step.name()
                    + "].require-preceding-exit-code-zero is true, but app/jcl/READXREF.jcl declares "
                    + "no COND on its only step. Gating the first step of a single-step job behind a "
                    + "preceding step's exit code would stop it running at all.");
        }
    }

    /**
     * Requires the configured record width to agree with {@code app/cpy/CVACT03Y.cpy}.
     *
     * <p>The fixture {@code app/data/ASCII/cardxref.txt} holds <strong>36</strong> bytes per record
     * because it omits the trailing {@code FILLER X(14)}; the harness widens those rows to 50 before
     * anything decodes them. The configured width is never narrowed to match the fixture: at 36 bytes
     * the account id would be read from where the {@code FILLER} begins, and the display line would be
     * 14 bytes short of what the program writes.
     *
     * @param configuredRecordLength the width configuration declares for the DD
     * @throws IllegalStateException if it is not {@value CardXrefRecord#RECORD_LENGTH}
     */
    private static void requireCopybookRecordLength(int configuredRecordLength) {
        if (configuredRecordLength != CardXrefRecord.RECORD_LENGTH) {
            throw new IllegalStateException("carddemo.datasets." + XREFFILE_DD_NAME
                    + ".record-length is " + configuredRecordLength + ", but app/cpy/CVACT03Y.cpy "
                    + "declares (RECLN " + CardXrefRecord.RECORD_LENGTH + ") and "
                    + PROGRAM_NAME + " DISPLAYs the whole record area. The 36-byte form of "
                    + "app/data/ASCII/cardxref.txt is the fixture omitting its trailing FILLER X("
                    + CardXrefRecord.FILLER_LENGTH + ") and is widened to "
                    + CardXrefRecord.RECORD_LENGTH + " before use, never configured down to.");
        }
    }

    /**
     * Requires the DD to bind a dataset name that can actually be addressed.
     *
     * <p>A blank name is the one failure mode a configuration file produces silently: an unset
     * environment-variable reference resolves to the empty string, and every read afterwards addresses
     * nothing.
     *
     * @param dsname the configured dataset name
     * @return that name, unchanged
     * @throws IllegalStateException if it is {@code null} or blank
     */
    private static String requireUsableDatasetName(String dsname) {
        if (dsname == null || dsname.isBlank()) {
            throw new IllegalStateException("carddemo.datasets." + XREFFILE_DD_NAME + ".dsname is "
                    + (dsname == null ? "not declared" : "blank") + ", so DD " + XREFFILE_DD_NAME
                    + " names no dataset for " + PROGRAM_NAME + " to read. app/jcl/READXREF.jcl binds "
                    + "it to the card cross-reference cluster; supply that dataset, or the "
                    + "environment variable the binding defers to.");
        }
        return dsname;
    }

    // =================================================================================================
    // The Spring Batch assembly: one job, one step, one tasklet. app/jcl/READXREF.jcl has one step and
    // no COND, so there is no flow to build and no decider to place.
    // =================================================================================================

    /**
     * The job {@code app/jcl/READXREF.jcl} runs: one step, no parameters, no gating.
     *
     * <p>Built through {@link BatchConfig#job(String)}, so it carries the shared job repository and the
     * abend listener that turns an {@link AbendException}'s {@code RETURN-CODE} into the job's exit
     * status - which is what makes a JCL-equivalent {@code COND} test on a downstream job continue to
     * mean what it meant on the mainframe.
     *
     * <p>No {@code JobParametersValidator} is installed, and that absence is deliberate: the JCL step
     * carries no {@code PARM}, so this job declares no parameter, and a validator asserting that the
     * parameters are empty would forbid the identifying parameter a re-run of the same job instance
     * needs.
     *
     * @return the job, named {@value #JOB_NAME} in the batch metadata
     */
    @Bean(JOB_NAME)
    public Job accountBalanceUpdateJob() {
        return batchConfig.job(JOB_NAME)
                .start(accountBalanceUpdateStep())
                .build();
    }

    /**
     * The step, named for {@code app/jcl/READXREF.jcl}'s {@code //STEP05}.
     *
     * <p>A {@link Tasklet} step, not a chunk-oriented one. {@code CBACT03C} opens once, reads to end of
     * file and closes once, emitting its lines in strict record order; chunking that would move the
     * commit boundaries and with them the order the lines are written in, which is precisely the
     * observable output this migration is measured against.
     *
     * @return the single step of this job
     */
    @Bean(STEP_BEAN_NAME)
    public Step accountBalanceUpdateStep() {
        return batchConfig.taskletStep(stepName, accountBalanceUpdateTasklet()).build();
    }

    /**
     * The step body: a thin adapter that resolves the {@code SYSOUT} destination and runs the program.
     *
     * <p>It holds no logic of its own on purpose. Everything the COBOL does lives in
     * {@link #execute(SysoutSink, StopSignal)}, which needs neither a {@code JobLauncher} nor an
     * application context, so every branch of the translation is reachable from a plain unit test. The
     * tasklet itself contributes no read or write counts: {@code CBACT03C} keeps no counters, and
     * inventing step metrics here would put numbers in the batch metadata that no legacy artefact can
     * confirm.
     *
     * <p>The chunk context is read for one thing: this step execution's {@link StopSignal}, which the
     * pass consults between records. A tasklet that runs once is checked for interruption once by the
     * framework, at the top, so a stop requested during a full-file pass would otherwise not be seen
     * until the pass had finished.
     *
     * <p>Not a bean. The step is the bean; a separately published tasklet would be a second handle on
     * the same body with no caller.
     *
     * @return a tasklet that runs the program exactly once per step execution
     */
    public Tasklet accountBalanceUpdateTasklet() {
        return (contribution, chunkContext) -> {
            execute(resolveSysoutSink(), StopSignal.of(chunkContext));
            return RepeatStatus.FINISHED;
        };
    }

    // =================================================================================================
    // THE PROGRAM. app/cbl/CBACT03C.cbl:70-87, statement for statement.
    // =================================================================================================

    /**
     * Runs {@code CBACT03C}: read the card cross-reference file from beginning to end and display
     * every record, twice.
     *
     * <p>The COBOL mainline, and the Java below it, in the same order:
     * <pre>
     * PROCEDURE DIVISION.                                              :70
     *     DISPLAY 'START OF EXECUTION OF PROGRAM CBACT03C'.            :71
     *     PERFORM 0000-XREFFILE-OPEN.                                  :72
     *     PERFORM UNTIL END-OF-FILE = 'Y'                              :74
     *         IF  END-OF-FILE = 'N'                                    :75
     *             PERFORM 1000-XREFFILE-GET-NEXT                       :76
     *             IF  END-OF-FILE = 'N'                                :77
     *                 DISPLAY CARD-XREF-RECORD                         :78
     *             END-IF                                               :79
     *         END-IF                                                   :80
     *     END-PERFORM.                                                 :81
     *     PERFORM 9000-XREFFILE-CLOSE.                                 :83
     *     DISPLAY 'END OF EXECUTION OF PROGRAM CBACT03C'.              :85
     *     GOBACK.                                                      :87
     * </pre>
     *
     * <p>Two details of that loop are worth stating because they look like mistakes and are not.
     * <strong>The guard at {@code :75} is redundant</strong> - the {@code PERFORM UNTIL} condition
     * already establishes it, and {@code END-OF-FILE} holds only {@code 'N'} or {@code 'Y'} - so its
     * false path is unreachable. It is reproduced rather than removed, and a coverage report will show
     * it as a half-taken branch for exactly that reason. <strong>The display at {@code :78} is the
     * second of two</strong>: {@code 1000-XREFFILE-GET-NEXT} has already displayed the same record area
     * at {@code :96}, so each record produces two byte-identical lines.
     *
     * <p>The COBOL's {@code WORKING-STORAGE} becomes locals here - the {@code END-OF-FILE} flag, the
     * {@code CARD-XREF-RECORD} area and the record count - so nothing is shared between two
     * executions. On end of file the record area deliberately keeps the previous record, exactly as a
     * COBOL {@code READ ... INTO} leaves it untouched at {@code AT END}; the guard at {@code :77} is
     * what stops that stale record being displayed again.
     *
     * <p>Nothing in here writes. No repository write method is called, because
     * {@link CardXrefRepository} publishes none - the class name's promise of an update has no
     * implementation to reach for.
     *
     * @param sysout where the {@code DISPLAY} lines go; never {@code null}
     * @return the return code and the number of records read
     * @throws NullPointerException if {@code sysout} is {@code null}
     * @throws AbendException       if the open, a read or the close reports a file status the program
     *                              does not name, carrying {@code RETURN-CODE}
     *                              {@value #APPL_RESULT_FATAL}, {@code ABCODE} 999 and {@code TIMING}
     *                              0, exactly as {@code 9999-ABEND-PROGRAM} sets them before
     *                              {@code CALL 'CEE3ABD'}
     */
    public ExecutionSummary execute(SysoutSink sysout) {
        return execute(sysout, StopSignal.RUNNING);
    }

    /**
     * Runs {@code CBACT03C}, yielding to the given stop signal between records.
     *
     * <p>The pass is identical to {@link #execute(SysoutSink)} - same reads, same pair of displayed
     * lines per record, same order - and the signal changes nothing while no stop is pending. It exists
     * because this program is one pass over the whole cross-reference file inside a single tasklet
     * invocation, so the framework's interruption check at the step's repeat boundary happens once and
     * cannot end a pass already under way. The probe is consulted between records, where the record in
     * flight is always complete, and nothing is retried; see {@link StopSignal}.
     *
     * @param sysout     where the {@code DISPLAY} lines go; never {@code null}
     * @param stopSignal the between-record cancellation probe; {@link StopSignal#RUNNING} for a caller
     *                   outside a step; never {@code null}
     * @return the return code and the number of records read
     * @throws NullPointerException if {@code sysout} or {@code stopSignal} is {@code null}
     * @throws AbendException       if the open, a read or the close reports a file status the program
     *                              does not name
     * @throws BatchConfig.StopRequestedException if the step is asked to stop, which abandons the pass
     *                              between records
     */
    public ExecutionSummary execute(SysoutSink sysout, StopSignal stopSignal) {
        Objects.requireNonNull(sysout, "A SYSOUT sink is required to run " + PROGRAM_NAME
                + ": the program's observable output is its DISPLAY lines, so there is nothing to run "
                + "without somewhere to put them");
        Objects.requireNonNull(stopSignal, "A stop signal is required; pass StopSignal.RUNNING outside a "
                + "step, which is what the single-argument overload does");

        // :71  DISPLAY 'START OF EXECUTION OF PROGRAM CBACT03C'.
        sysout.display(START_OF_EXECUTION);

        // :72  PERFORM 0000-XREFFILE-OPEN.
        // Outside the try: a failed open throws without yielding a cursor, so there would be nothing for
        // a finally to release.
        BrowseCursor cursor = openXrefFile(sysout);

        // This run's own record of having reached :83. A local, never a field: the job is a singleton
        // bean and per-run state on it would be shared between runs (practice B9, gate G53).
        boolean closeIssued = false;

        try {

            // :65  01 END-OF-FILE PIC X(01) VALUE 'N'.   - and the record area COPY CVACT03Y declares.
            String endOfFile = NOT_AT_END_OF_FILE;
            CardXrefRecord recordArea = null;
            int recordsRead = 0;

            // :74  PERFORM UNTIL END-OF-FILE = 'Y'
            while (!AT_END_OF_FILE.equals(endOfFile)) {

                // NO COBOL COUNTERPART. The between-record yield to a stop request: a call rather than a
                // condition, so it adds no arm to the translated control flow, and positioned before the
                // read below so the record in flight is always complete. See BatchConfig.StopSignal.
                stopSignal.checkStopRequested();

                // :75  IF END-OF-FILE = 'N'   - redundant; see this method's documentation.
                if (NOT_AT_END_OF_FILE.equals(endOfFile)) {

                    // :76  PERFORM 1000-XREFFILE-GET-NEXT   - which displays the record at :96.
                    GetNextOutcome next = getNextXrefRecord(cursor, sysout);
                    endOfFile = next.endOfFile();
                    if (next.record() != null) {
                        recordArea = next.record();
                        recordsRead++;
                    }

                    // :77  IF END-OF-FILE = 'N'
                    if (NOT_AT_END_OF_FILE.equals(endOfFile)) {
                        // :78  DISPLAY CARD-XREF-RECORD  - the SECOND display of the same record area.
                        sysout.display(displayImageOf(recordArea));
                    }
                }
            }

            // :83  PERFORM 9000-XREFFILE-CLOSE.
            closeIssued = true;
            closeXrefFile(cursor, sysout);

            // :85  DISPLAY 'END OF EXECUTION OF PROGRAM CBACT03C'.
            sysout.display(END_OF_EXECUTION);

            // :87  GOBACK.   - RETURN-CODE is never moved, so the program returns zero.
            return new ExecutionSummary(AbendException.RETURN_CODE_OK, recordsRead);
        } finally {
            if (!closeIssued) {
                releaseCursor(cursor);
            }
        }
    }

    /**
     * Closes the cross-reference browse on the way out of an incomplete run, silently and only if it is
     * still open.
     *
     * <p>{@code CBACT03C} abends outright without closing, and this preserves that observably. Four
     * properties make it safe rather than merely well-intentioned:
     *
     * <ul>
     *   <li><strong>It costs nothing.</strong> {@link BrowseCursor#closeBrowse()} sets a flag and returns
     *       a status; it issues no I/O, so releasing costs no round trip on either path.</li>
     *   <li><strong>It is reached only when {@code 9000-XREFFILE-CLOSE} did not run.</strong> The
     *       caller's own {@code closeIssued} local is the guard, not {@link BrowseCursor#isOpen()}.
     *       Asking the cursor would make "exactly one {@code CLOSE} per run" - which is what the single
     *       statement at {@code :83} means - depend on the cursor tracking its own state; asking the run
     *       makes it depend on the program's control flow, which is where the property comes from. It
     *       also avoids a second hazard: {@code closeBrowse()} logs an error when called on a cursor
     *       that never opened, so a release that fired regardless would manufacture a spurious error
     *       record for a run whose open had already reported itself properly.</li>
     *   <li><strong>It is silent.</strong> No {@code DISPLAY} is emitted; the source has no such line and
     *       the line sequence is this program's entire observable output.</li>
     *   <li><strong>It cannot displace the real failure.</strong> Anything raised while releasing is
     *       swallowed, so the caller receives the exception the run was already unwinding with.</li>
     * </ul>
     *
     * <p>It exists because {@link BrowseCursor} declares {@link AutoCloseable}: a pass that releases only
     * on its normal tail rests on what the cursor happens to hold today rather than on its contract.
     *
     * @param cursor the cursor the open returned; never {@code null} here
     */
    private static void releaseCursor(BrowseCursor cursor) {
        try {
            cursor.closeBrowse();
        } catch (RuntimeException cleanupFailure) {
            // Only the failure's TYPE is logged - never the throwable and never its message. A driver
            // composes its message around the value it refused, and a cross-reference row carries the
            // card number and the account and customer identifiers (CWE-532); a newline in that text
            // could forge a second log entry (CWE-117). A class name carries no data and no newline.
            LOG.warn("Closing the " + XREFFILE_DD_NAME + " browse of " + PROGRAM_NAME + " after an "
                    + "incomplete run failed - " + cleanupFailure.getClass().getName()
                    + ". The run's own outcome is reported unchanged, because the run's own failure is the "
                    + "one that matters.");
        }
    }

    // =================================================================================================
    // 1000-XREFFILE-GET-NEXT - app/cbl/CBACT03C.cbl:92-116
    // =================================================================================================

    /**
     * Reads the next cross-reference record and displays it: the Java form of
     * {@code 1000-XREFFILE-GET-NEXT}.
     *
     * <pre>
     * 1000-XREFFILE-GET-NEXT.                                          :92
     *     READ XREFFILE-FILE INTO CARD-XREF-RECORD.                    :93
     *     IF  XREFFILE-STATUS = '00'                                   :94
     *         MOVE 0 TO APPL-RESULT                                    :95
     *         DISPLAY CARD-XREF-RECORD                                 :96  &lt;-- the FIRST display
     *     ELSE                                                         :97
     *         IF  XREFFILE-STATUS = '10'                               :98
     *             MOVE 16 TO APPL-RESULT                               :99
     *         ELSE                                                     :100
     *             MOVE 12 TO APPL-RESULT                               :101
     *     IF  APPL-AOK                                                 :104
     *         CONTINUE                                                 :105
     *     ELSE                                                         :106
     *         IF  APPL-EOF                                             :107
     *             MOVE 'Y' TO END-OF-FILE                              :108
     *         ELSE                                                     :109
     *             DISPLAY 'ERROR READING XREFFILE'                     :110
     *             MOVE XREFFILE-STATUS TO IO-STATUS                    :111
     *             PERFORM 9910-DISPLAY-IO-STATUS                       :112
     *             PERFORM 9999-ABEND-PROGRAM                           :113
     * </pre>
     *
     * <p><strong>The display at {@code :96} is live.</strong> It is not a {@code PERFORM} of a
     * field-by-field paragraph as in {@code CBACT01C}, and it is not commented out as in
     * {@code CBACT02C}. Together with {@code :78} it is why every record appears twice, and it comes
     * <em>first</em>: the pair of lines is emitted in the order {@code :96} then {@code :78}, which for
     * two identical lines is unobservable but is nonetheless what the program does.
     *
     * <p>The status ladder is written as the COBOL writes it - {@code '00'}, then {@code '10'}, then
     * everything else - and only then is {@code APPL-RESULT} tested. Keeping the two stages separate
     * matters: a status of {@code '22'} or {@code '23'} is neither of the two the program names, so it
     * takes the third arm and abends, which is what a batch program that tests only {@code '00'} and
     * {@code '10'} does.
     *
     * @param cursor the open browse of the base cluster
     * @param sysout where the record line and any failure message go
     * @return the new value of {@code END-OF-FILE} and the record the read placed in the record area,
     *         which is {@code null} at end of file
     * @throws AbendException if the read reports any other status
     */
    private GetNextOutcome getNextXrefRecord(BrowseCursor cursor, SysoutSink sysout) {
        // :93  READ XREFFILE-FILE INTO CARD-XREF-RECORD.
        ReadResult read = cursor.readNext();
        String status = read.status();

        int applResult;
        CardXrefRecord record = null;
        if (FileStatus.isOk(status)) {
            // :95  MOVE 0 TO APPL-RESULT
            applResult = FileStatus.APPL_AOK;
            record = read.record().orElseThrow(() -> new IllegalStateException("A read of DD "
                    + XREFFILE_DD_NAME + " reported file status " + FileStatus.toStatusImage(status)
                    + " and carried no record. The two are contradictory: a successful READ leaves the "
                    + "record area populated, which is why " + PROGRAM_NAME + ":96 DISPLAYs it."));
            // :96  DISPLAY CARD-XREF-RECORD   - the FIRST of the two displays of this record.
            sysout.display(displayImageOf(record));
        } else if (FileStatus.isEndOfFile(status)) {
            // :99  MOVE 16 TO APPL-RESULT
            applResult = FileStatus.APPL_EOF;
        } else {
            // :101  MOVE 12 TO APPL-RESULT
            applResult = APPL_RESULT_FATAL;
        }

        // :104  IF APPL-AOK CONTINUE
        if (applAok(applResult)) {
            return new GetNextOutcome(NOT_AT_END_OF_FILE, record);
        }
        // :107  IF APPL-EOF  ->  :108  MOVE 'Y' TO END-OF-FILE
        if (applEof(applResult)) {
            return new GetNextOutcome(AT_END_OF_FILE, null);
        }
        // :110-112  DISPLAY 'ERROR READING XREFFILE', then 9910-DISPLAY-IO-STATUS.
        reportIoFailure(sysout, ERROR_READING_XREFFILE, status);
        // :113  PERFORM 9999-ABEND-PROGRAM - which does not return.
        throw abendProgram(sysout, ERROR_READING_XREFFILE, status);
    }

    // =================================================================================================
    // 0000-XREFFILE-OPEN - app/cbl/CBACT03C.cbl:118-134
    // =================================================================================================

    /**
     * Opens the cross reference for input: the Java form of {@code 0000-XREFFILE-OPEN}.
     *
     * <pre>
     * 0000-XREFFILE-OPEN.                                              :118
     *     MOVE 8 TO APPL-RESULT.                                       :119
     *     OPEN INPUT XREFFILE-FILE                                     :120
     *     IF  XREFFILE-STATUS = '00'                                   :121
     *         MOVE 0 TO APPL-RESULT                                    :122
     *     ELSE                                                         :123
     *         MOVE 12 TO APPL-RESULT                                   :124
     *     IF  APPL-AOK                                                 :126
     *         CONTINUE                                                 :127
     *     ELSE                                                         :128
     *         DISPLAY 'ERROR OPENING XREFFILE'                         :129
     *         MOVE XREFFILE-STATUS TO IO-STATUS                        :130
     *         PERFORM 9910-DISPLAY-IO-STATUS                           :131
     *         PERFORM 9999-ABEND-PROGRAM                               :132
     * </pre>
     *
     * <p>{@code OPEN INPUT} is the whole answer to the class name: this program never opens the file
     * for output or for I/O, so no update is possible even in principle.
     *
     * <p>The {@code MOVE 8} at {@code :119} is dead - the very next test overwrites it on both arms -
     * and is reproduced anyway, because a statement present in the source must be accounted for in the
     * translation.
     *
     * <p>The abend leaves the cursor open, exactly as {@code CALL 'CEE3ABD'} does: it terminates the
     * task without reaching {@code 9000-XREFFILE-CLOSE}. Nothing leaks by doing so - a browse cursor
     * holds no connection between reads - and closing here would emit a line the program never writes.
     *
     * @param sysout where a failure message goes
     * @return the open cursor
     * @throws AbendException if the open reports any status but {@code '00'}
     */
    /**
     * The cross-reference repository addressing <strong>this job's</strong>
     * {@value #XREFFILE_DD_NAME} DD.
     *
     * <p>{@code CBACT03C} reads the dataset {@code app/jcl/READXREF.jcl:25-26} binds to
     * {@code //XREFFILE DD}, and this job resolves that DD through its own view of the catalogue - the
     * job-scoped entry first, the global one second. The injected repository resolved
     * {@link CardXrefRepository#BASE_DD_NAME} from the global catalogue, which is the <em>online</em>
     * name for the same dataset.
     *
     * <p>The finding was that nothing required the two to agree: this job validated
     * {@value #XREFFILE_DD_NAME} in its constructor and then browsed through {@code CCXREF}, so a
     * deployment that re-pointed {@value #XREFFILE_DD_NAME} would have read a dataset nobody asked for
     * behind a clean start-up. Handing the resolved binding in makes the declared DD the one browsed.
     *
     * <p>No alternate-index binding is supplied, because {@code app/cbl/CBACT03C.cbl:29} declares one
     * {@code SELECT} with no {@code ALTERNATE RECORD KEY} and {@code app/jcl/READXREF.jcl} declares no
     * second DD: this program reads the base cluster sequentially and nothing else. Passing one would
     * assert a path this program never opens.
     *
     * @return the repository this run browses; never {@code null}
     * @throws IllegalStateException if neither this job nor the global catalogue declares
     *                               {@value #XREFFILE_DD_NAME}, or the binding is unusable
     */
    private CardXrefRepository xrefFileRepository() {
        return cardXrefRepository.addressing(
                batchConfig.datasetBinding(JOB_KEY, XREFFILE_DD_NAME), XREFFILE_DD_NAME,
                null, CardXrefRepository.ALTERNATE_INDEX_BATCH_DD_NAME);
    }

    private BrowseCursor openXrefFile(SysoutSink sysout) {
        // :119  MOVE 8 TO APPL-RESULT.   - dead, and preserved.
        int applResult = APPL_RESULT_ASSUMED_FAILURE;

        // :120  OPEN INPUT XREFFILE-FILE
        BrowseCursor cursor = xrefFileRepository().openBrowse();
        String status = cursor.openStatus();

        if (FileStatus.isOk(status)) {
            // :122  MOVE 0 TO APPL-RESULT
            applResult = FileStatus.APPL_AOK;
        } else {
            // :124  MOVE 12 TO APPL-RESULT
            applResult = APPL_RESULT_FATAL;
        }

        // :126  IF APPL-AOK CONTINUE ELSE ...
        if (!applAok(applResult)) {
            // :129-131  DISPLAY 'ERROR OPENING XREFFILE', then 9910-DISPLAY-IO-STATUS.
            reportIoFailure(sysout, ERROR_OPENING_XREFFILE, status);
            // :132  PERFORM 9999-ABEND-PROGRAM - which does not return.
            throw abendProgram(sysout, ERROR_OPENING_XREFFILE, status);
        }
        return cursor;
    }

    // =================================================================================================
    // 9000-XREFFILE-CLOSE - app/cbl/CBACT03C.cbl:136-152
    // =================================================================================================

    /**
     * Closes the cross reference: the Java form of {@code 9000-XREFFILE-CLOSE}.
     *
     * <pre>
     * 9000-XREFFILE-CLOSE.                                             :136
     *     ADD 8 TO ZERO GIVING APPL-RESULT.                            :137
     *     CLOSE XREFFILE-FILE                                          :138
     *     IF  XREFFILE-STATUS = '00'                                   :139
     *         SUBTRACT APPL-RESULT FROM APPL-RESULT                    :140
     *     ELSE                                                         :141
     *         ADD 12 TO ZERO GIVING APPL-RESULT                        :142
     *     IF  APPL-AOK                                                 :144
     *         CONTINUE                                                 :145
     *     ELSE                                                         :146
     *         DISPLAY 'ERROR CLOSING XREFFILE'                         :147
     *         MOVE XREFFILE-STATUS TO IO-STATUS                        :148
     *         PERFORM 9910-DISPLAY-IO-STATUS                           :149
     *         PERFORM 9999-ABEND-PROGRAM                               :150
     * </pre>
     *
     * <p>The arithmetic is deliberately transcribed rather than simplified. {@code :137} seeds eight by
     * adding it to zero, and {@code :140} reaches zero by subtracting {@code APPL-RESULT} from itself -
     * two roundabout ways of writing what {@code 0000-XREFFILE-OPEN} writes as plain {@code MOVE}s. The
     * values are identical, and keeping the shape makes the two paragraphs diffable against their
     * source.
     *
     * <p>A close that fails abends after the loop has already run, so its message follows every record
     * line rather than replacing them.
     *
     * @param cursor the cursor to close
     * @param sysout where a failure message goes
     * @throws AbendException if the close reports any status but {@code '00'}
     */
    private void closeXrefFile(BrowseCursor cursor, SysoutSink sysout) {
        // :137  ADD 8 TO ZERO GIVING APPL-RESULT.   - dead, and preserved.
        int applResult = APPL_RESULT_ASSUMED_FAILURE;

        // :138  CLOSE XREFFILE-FILE
        String status = cursor.closeBrowse();

        if (FileStatus.isOk(status)) {
            // :140  SUBTRACT APPL-RESULT FROM APPL-RESULT   - which is to say, zero.
            applResult -= applResult;
        } else {
            // :142  ADD 12 TO ZERO GIVING APPL-RESULT
            applResult = APPL_RESULT_FATAL;
        }

        // :144  IF APPL-AOK CONTINUE ELSE ...
        if (!applAok(applResult)) {
            // :147-149  DISPLAY 'ERROR CLOSING XREFFILE', then 9910-DISPLAY-IO-STATUS.
            reportIoFailure(sysout, ERROR_CLOSING_XREFFILE, status);
            // :150  PERFORM 9999-ABEND-PROGRAM - which does not return.
            throw abendProgram(sysout, ERROR_CLOSING_XREFFILE, status);
        }
    }

    // =================================================================================================
    // The two 88-level condition names of app/cbl/CBACT03C.cbl:62-63, as named predicates. Each is
    // tested in both truth states by the three paragraphs above, which is what makes both states
    // reachable from a test rather than only the happy one.
    // =================================================================================================

    /**
     * {@code 88 APPL-AOK VALUE 0} - {@code app/cbl/CBACT03C.cbl:62}.
     *
     * @param applResult the current {@code APPL-RESULT}
     * @return {@code true} when it is {@link FileStatus#APPL_AOK}
     */
    private static boolean applAok(int applResult) {
        return applResult == FileStatus.APPL_AOK;
    }

    /**
     * {@code 88 APPL-EOF VALUE 16} - {@code app/cbl/CBACT03C.cbl:63}.
     *
     * <p>Tested only by {@code 1000-XREFFILE-GET-NEXT}: an open and a close have no end-of-file
     * outcome, which is why their ladders move {@code 0} or {@code 12} and never {@code 16}.
     *
     * @param applResult the current {@code APPL-RESULT}
     * @return {@code true} when it is {@link FileStatus#APPL_EOF}
     */
    private static boolean applEof(int applResult) {
        return applResult == FileStatus.APPL_EOF;
    }

    // =================================================================================================
    // 9910-DISPLAY-IO-STATUS and 9999-ABEND-PROGRAM - app/cbl/CBACT03C.cbl:154-174. Shared by all three
    // paragraphs above, exactly as the COBOL shares them.
    // =================================================================================================

    /**
     * Displays a paragraph's failure message and then the file status, which is
     * {@code MOVE ... TO IO-STATUS} followed by {@code PERFORM 9910-DISPLAY-IO-STATUS}.
     *
     * <p>The status line comes from {@link FileStatus#toDisplayLine(String)} rather than being
     * assembled here, so all sixteen emission sites across the migrated batch programs produce one
     * byte-identical form of {@code DISPLAY 'FILE STATUS IS: NNNN' IO-STATUS-04}. For a status the
     * repository reports as a permanent error that renders {@code FILE STATUS IS: NNNN9000}, which is
     * what the paragraph's extended-status arm produces for {@code '9'} followed by a zero feedback
     * code.
     *
     * @param sysout  where the two lines go
     * @param message the paragraph's own message - one of the three {@code XREFFILE} literals
     * @param status  the two-character file status the operation reported
     */
    private static void reportIoFailure(SysoutSink sysout, String message, String status) {
        sysout.display(message);
        sysout.display(FileStatus.toDisplayLine(status));
    }

    /**
     * {@code 9999-ABEND-PROGRAM} - {@code app/cbl/CBACT03C.cbl:154-158}.
     *
     * <pre>
     * 9999-ABEND-PROGRAM.                                              :154
     *     DISPLAY 'ABENDING PROGRAM'                                   :155
     *     MOVE 0 TO TIMING                                             :156
     *     MOVE 999 TO ABCODE                                           :157
     *     CALL 'CEE3ABD'.                                              :158
     * </pre>
     *
     * <p>The display happens here, before the exception is built, because the COBOL writes that line
     * before it calls the Language Environment service - and because a caller that failed to throw what
     * this returns would still have emitted the line the program emits.
     *
     * <p>{@link AbendException#standard(String, int, String)} supplies {@code ABCODE} 999 and
     * {@code TIMING} 0, the two arguments {@code :156} and {@code :157} set. The
     * {@code RETURN-CODE} is {@value #APPL_RESULT_FATAL}, the value all three paragraphs move into
     * {@code APPL-RESULT} on their failing arm, and {@link BatchConfig}'s listeners carry it onto the
     * step and job exit status so a downstream {@code COND} test still means what it meant in JCL.
     *
     * <p>Returned rather than thrown so the call site reads {@code throw abendProgram(...)}, which makes
     * it evident at each of the three sites that control does not continue - as it does not after
     * {@code CALL 'CEE3ABD'}.
     *
     * @param sysout  where the abend line goes
     * @param message the message the failing paragraph already displayed, carried as the abend's reason
     * @param status  the file status that caused the abend
     * @return the abend to throw
     */
    private static AbendException abendProgram(SysoutSink sysout, String message, String status) {
        // :155  DISPLAY 'ABENDING PROGRAM'
        sysout.display(AbendException.ABEND_DISPLAY_TEXT);
        // :156-158  MOVE 0 TO TIMING, MOVE 999 TO ABCODE, CALL 'CEE3ABD'.
        return AbendException.standard(PROGRAM_NAME, APPL_RESULT_FATAL,
                message + " - " + FileStatus.toDisplayLine(status));
    }

    // =================================================================================================
    // DISPLAY CARD-XREF-RECORD - the record area rendered exactly as the 50 bytes it occupies.
    // =================================================================================================

    /**
     * Renders a cross-reference record as the {@value CardXrefRecord#RECORD_LENGTH}-character image
     * {@code DISPLAY CARD-XREF-RECORD} writes.
     *
     * <p>The record is encoded to its stored bytes and those bytes are decoded back to text, rather
     * than the three fields being formatted individually. That is deliberate: the encode path owns the
     * copybook's padding rules and emits every declared span, so the line is
     * {@value CardXrefRecord#RECORD_LENGTH} characters with the card number left justified and
     * space-padded, both identifiers right justified and zero-filled, and the trailing
     * {@code FILLER X(14)} present as fourteen spaces. Formatting the fields here instead would put a
     * second, divergeable copy of those rules in this file - and the most likely divergence is dropping
     * the {@code FILLER}, which would make every line 14 bytes short of what the program writes.
     *
     * <p>Public because it is the observable output of this program: a parity harness renders the same
     * image from a record and compares it field by field.
     *
     * @param record the record the read placed in the record area; never {@code null}
     * @return exactly {@value CardXrefRecord#RECORD_LENGTH} characters
     * @throws NullPointerException if {@code record} is {@code null}
     */
    public String displayImageOf(CardXrefRecord record) {
        Objects.requireNonNull(record, "A record is required to render the CARD-XREF-RECORD display "
                + "image; app/cbl/CBACT03C.cbl:78 and :96 display the record area, which a successful "
                + "READ has always populated");
        return codec.decodeImage(record.encode(codec), RECORD_DISPLAY_SUBJECT);
    }

    // =================================================================================================
    // SYSOUT. app/jcl/READXREF.jcl declares //SYSOUT DD SYSOUT=* and //SYSPRINT DD SYSOUT=*; neither is
    // a dataset this module binds, so where the lines actually go is a deployment-time input - like the
    // JDBC driver - and it arrives as an injected sink.
    // =================================================================================================

    /**
     * The {@code SYSOUT} destination for this execution: whatever the container publishes, else
     * {@link #defaultSysoutSink()}.
     *
     * <p>Resolved per execution rather than cached, so a deployment can publish a sink whose lifecycle
     * is its own.
     *
     * @return the sink to display through; never {@code null}
     */
    private SysoutSink resolveSysoutSink() {
        return sysoutSinkProvider.getIfAvailable(this::defaultSysoutSink);
    }

    /**
     * The sink used when the container publishes none: one line per {@code DISPLAY}, verbatim, in the
     * configured dataset code page, to the process's standard output.
     *
     * <p>Four properties of it are deliberate, and each corresponds to something a mainframe
     * {@code SYSOUT=*} DD does that a casual Java equivalent would not:
     * <ul>
     *   <li><strong>Verbatim.</strong> No timestamp, no level, no logger name, no thread - a
     *       {@code DISPLAY} writes the line and nothing else, and any prefix would make every line of
     *       output differ from the legacy output it is compared against. This is also why it is not a
     *       logging framework: a logger's layout is configuration, and the byte content of this
     *       program's output is not negotiable.</li>
     *   <li><strong>In the dataset code page.</strong> The line is encoded through the same codec that
     *       renders the record, so a record image reaches {@code SYSOUT} as the bytes it holds rather
     *       than being re-encoded in whatever the platform default happens to be. The codec refuses a
     *       character the code page cannot represent instead of substituting a question mark for
     *       it.</li>
     *   <li><strong>One line feed, stated.</strong> Not the platform line separator, which would emit
     *       two bytes on some hosts and one on others for the same program.</li>
     *   <li><strong>Flushed per line.</strong> The three failure paths display a message, then a status,
     *       then {@code 'ABENDING PROGRAM'}, and then the process ends non-zero. Buffered output could
     *       lose precisely the three lines that explain why.</li>
     * </ul>
     *
     * @return a sink writing to the process's standard output; never {@code null}
     */
    public SysoutSink defaultSysoutSink() {
        // System.out is the JVM's handle on the process's standard output stream, which is what a
        // //SYSOUT DD SYSOUT=* becomes off the mainframe. It is named once, here, and never from the
        // program body: execute(SysoutSink) only ever calls SysoutSink.display, so a test or a parity
        // harness substitutes a destination without this class knowing the difference.
        return sysoutSinkTo(System.out);
    }

    /**
     * The same sink over a caller-chosen stream, for a deployment whose {@code SYSOUT} is a file, a
     * pipe or an in-memory buffer rather than the process's own output.
     *
     * <p>The behaviour is identical to {@link #defaultSysoutSink()} in every respect except where the
     * bytes land: verbatim line, dataset code page, one line feed, flushed per line. The stream's
     * lifecycle belongs to the caller - this sink never closes what it did not open, because a
     * {@code DISPLAY} does not close {@code SYSOUT}.
     *
     * @param destination where the bytes go; never {@code null}
     * @return a sink writing to that stream
     * @throws NullPointerException if {@code destination} is {@code null}
     */
    public SysoutSink sysoutSinkTo(OutputStream destination) {
        return new StreamSysoutSink(destination, codec);
    }

    // =================================================================================================
    // Accessors. Read-only views of what configuration bound, for a test or an operator.
    // =================================================================================================

    /**
     * The dataset DD {@value #XREFFILE_DD_NAME} is bound to, as {@code carddemo.datasets.XREFFILE}
     * declares it.
     *
     * @return the configured dataset name; never {@code null}, never blank
     */
    public String xrefFileDatasetName() {
        return xrefFileDatasetName;
    }

    /**
     * The step name this job's configured contract declares, which the constructor has already
     * confirmed is {@value #STEP_NAME}.
     *
     * @return the step name; never {@code null}
     */
    public String stepName() {
        return stepName;
    }

    // =================================================================================================
    // Nested types.
    // =================================================================================================

    /**
     * Where a {@code DISPLAY} line goes.
     *
     * <p>The seam that keeps this program's observable output observable. A test or a parity harness
     * supplies a sink that collects the lines and asserts on them; a deployment publishes one that
     * writes them where its operators read them; and {@link #defaultSysoutSink()} covers the case
     * where nobody does either.
     *
     * <p>One line per call, already complete: the implementation adds a terminator and nothing else. It
     * must not reorder, buffer across a failure boundary, deduplicate or otherwise improve on what it is
     * given - {@code CBACT03C} displays every record twice, and a sink that noticed and collapsed the
     * pair would break the very parity this seam exists to demonstrate.
     */
    @FunctionalInterface
    public interface SysoutSink {

        /**
         * Emits one line of {@code SYSOUT}.
         *
         * @param line the line, exactly as the program composed it; never {@code null} and never
         *             terminated - the sink supplies the terminator
         */
        void display(String line);
    }

    /**
     * The default {@link SysoutSink}: bytes in the configured code page, one line feed, flushed.
     *
     * <p>Immutable and stateless beyond the stream it writes to, so it is safe to share, and it holds no
     * static state of any kind.
     */
    private static final class StreamSysoutSink implements SysoutSink {

        /** What is named in a codec diagnostic when a line cannot be encoded. */
        private static final String SYSOUT_SUBJECT = "a SYSOUT display line of " + PROGRAM_NAME;

        /** The destination stream, supplied rather than chosen by this class. */
        private final OutputStream destination;

        /** The codec whose code page the line is encoded in. */
        private final FixedWidthCodec codec;

        /**
         * @param destination where the bytes go
         * @param codec       the code page to encode them in
         */
        private StreamSysoutSink(OutputStream destination, FixedWidthCodec codec) {
            this.destination = Objects.requireNonNull(destination, "A destination stream is required");
            this.codec = Objects.requireNonNull(codec, "A codec is required: a SYSOUT line is written "
                    + "in a named code page, never in the platform default");
        }

        /**
         * Writes the line and its terminator, then flushes.
         *
         * @param line the line to emit
         * @throws NullPointerException if {@code line} is {@code null}
         * @throws UncheckedIOException if the stream refuses the write. Unchecked because
         *                              {@link SysoutSink#display(String)} declares no checked exception:
         *                              a COBOL {@code DISPLAY} has no failure path for the program to
         *                              handle, so there is none to translate
         */
        @Override
        public void display(String line) {
            Objects.requireNonNull(line, "A line is required to display; " + PROGRAM_NAME + " never "
                    + "displays nothing");
            byte[] encoded = codec.encodeImage(line + SYSOUT_LINE_TERMINATOR, SYSOUT_SUBJECT);
            try {
                destination.write(encoded);
                destination.flush();
            } catch (IOException refused) {
                throw new UncheckedIOException("Could not write a SYSOUT line of " + PROGRAM_NAME
                        + " to its destination stream; " + encoded.length + " byte(s) were pending",
                        refused);
            }
        }
    }

    /**
     * What one {@code PERFORM 1000-XREFFILE-GET-NEXT} left behind: the new {@code END-OF-FILE} value and
     * the record the {@code READ ... INTO} placed in the record area.
     *
     * <p>Two values rather than one, because the mainline needs both: {@code :77} tests the flag and
     * {@code :78} displays the area. Returning them together is what keeps the record area a local of
     * {@link #execute(SysoutSink)} instead of a field, which is what makes two concurrent executions
     * impossible to confuse.
     *
     * @param endOfFile {@value #NOT_AT_END_OF_FILE} or {@value #AT_END_OF_FILE}
     * @param record    the record read, or {@code null} at end of file - in which case the caller leaves
     *                  its record area untouched, exactly as a COBOL {@code READ ... INTO} does at
     *                  {@code AT END}
     */
    private record GetNextOutcome(String endOfFile, CardXrefRecord record) {
    }

    /**
     * What one execution of {@code CBACT03C} produced.
     *
     * @param returnCode  the {@code RETURN-CODE} the program left, which is
     *                    {@link AbendException#RETURN_CODE_OK} for every execution that returns at all -
     *                    {@code CBACT03C} never moves a value into {@code RETURN-CODE}, and its three
     *                    failure paths abend rather than returning
     * @param recordsRead how many records the browse returned, which is how many the program displayed -
     *                    each of them {@value #DISPLAYS_PER_RECORD} times
     */
    public record ExecutionSummary(int returnCode, int recordsRead) {

        /**
         * Rejects a negative record count, which no execution can produce and no expectation should
         * state.
         *
         * @throws IllegalArgumentException if {@code recordsRead} is negative
         */
        public ExecutionSummary {
            if (recordsRead < 0) {
                throw new IllegalArgumentException("A record count of " + recordsRead + " is not "
                        + "possible: a sequential browse returns zero or more records, and " + PROGRAM_NAME
                        + " counts the ones it displayed.");
            }
        }

        /**
         * How many record lines the execution emitted: {@value #DISPLAYS_PER_RECORD} per record, because
         * {@code app/cbl/CBACT03C.cbl} displays the record area at {@code :96} and again at {@code :78}.
         *
         * @return the number of record lines
         */
        public int recordLinesDisplayed() {
            return recordsRead * DISPLAYS_PER_RECORD;
        }

        /**
         * How many lines the execution emitted in total: the two banners plus the record lines.
         *
         * <p>For the 50-record {@code app/data/ASCII/cardxref.txt} fixture this is 102, which is the
         * expectation the parity case for this program states.
         *
         * @return the total number of {@code SYSOUT} lines
         */
        public int totalLinesDisplayed() {
            return BANNER_LINES + recordLinesDisplayed();
        }
    }
}
