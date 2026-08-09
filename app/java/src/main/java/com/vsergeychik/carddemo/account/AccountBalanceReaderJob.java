package com.vsergeychik.carddemo.account;

import java.io.PrintStream;
import java.nio.charset.Charset;
import java.util.List;
import java.util.Objects;

import com.vsergeychik.carddemo.card.CardRepository;
import com.vsergeychik.carddemo.card.CardRepository.BrowseDirection;
import com.vsergeychik.carddemo.card.CardRepository.CardBrowse;
import com.vsergeychik.carddemo.card.CardRepository.CardReadResult;
import com.vsergeychik.carddemo.card.model.CardRecord;
import com.vsergeychik.carddemo.common.AbendException;
import com.vsergeychik.carddemo.common.FileStatus;
import com.vsergeychik.carddemo.config.BatchConfig;
import com.vsergeychik.carddemo.config.BatchConfig.JobContract;
import com.vsergeychik.carddemo.config.BatchConfig.JobParameterContract;
import com.vsergeychik.carddemo.config.BatchConfig.StepContract;
import com.vsergeychik.carddemo.config.BatchConfig.StopSignal;

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
 * {@code CBACT02C} - the batch job that reads the <strong>card</strong> master sequentially and
 * prints every record.
 *
 * <h2>READ THIS FIRST: the class name says Account, the program reads the CARD file</h2>
 * <p>Nothing in {@code app/cbl/CBACT02C.cbl} touches the account file. Not one line. The program's
 * own header states its function as <em>"Read and print card data file"</em>
 * ({@code app/cbl/CBACT02C.cbl:5}), and every declaration agrees with it:
 * <ul>
 *   <li>{@code SELECT CARDFILE-FILE ASSIGN TO CARDFILE} ({@code :29}), positioned by
 *       {@code RECORD KEY IS FD-CARD-NUM} ({@code :32}) - and that key is
 *       <strong>character</strong> data, {@code FD-CARD-NUM PIC X(16)} ({@code :39}), where the
 *       account file's key is the numeric {@code PIC 9(11)};</li>
 *   <li>{@code COPY CVACT02Y.} ({@code :45}) - the 150-byte card record, not the 300-byte account
 *       record;</li>
 *   <li>the JCL binds one input DD and it is {@code //CARDFILE DD ... CARDDATA.VSAM.KSDS}
 *       ({@code app/jcl/READCARD.jcl:25-26}), under the comment <em>"RUN THE PROGRAM THAT READS THE
 *       CARD MASTER VSAM FILE"</em> ({@code :20}).</li>
 * </ul>
 *
 * <p>The name is nonetheless correct, because the migration plan mandates it and rule R1 is
 * explicit: <strong>names come from the plan, behaviour comes from the source.</strong> A mandated
 * name never authorises adding, removing or altering logic, so this class carries the mandated name
 * and does exactly and only what {@code CBACT02C} does. It also lives in the {@code account}
 * package for that same reason - the plan's package assignment puts it there - which makes it a
 * deliberate <em>cross-package consumer</em>: its model and its repository both come from
 * {@code card}, because one Java type per copybook is shared by every consumer and never duplicated
 * per program. The divergence is catalogued in the plan's class-name register so that no reader
 * mistakes it for a defect, and it is restated here because a reader arrives at this file, not at
 * that register.
 *
 * <h2>THE ONE THING MOST EASILY GOT WRONG: this job emits ONE line per record</h2>
 * <p>Three programs in this estate share an identical open / read-loop / display / close shape, and
 * they emit <em>different amounts of output</em>. The difference is invisible in their structure and
 * is discoverable only by reading one line of each. In this program that line is <strong>{@code :96},
 * and it is a comment</strong>:
 *
 * <pre>
 * 1000-CARDFILE-GET-NEXT.
 *     READ CARDFILE-FILE INTO CARD-RECORD.
 *     IF  CARDFILE-STATUS = '00'
 *         MOVE 0 TO APPL-RESULT
 * *        DISPLAY CARD-RECORD          &lt;-- :96, COMMENTED OUT
 *     ELSE
 * </pre>
 *
 * <p>There is consequently <strong>no {@code 1100-DISPLAY-…} paragraph anywhere in the file</strong>.
 * Every {@code DISPLAY} the program contains is accounted for: the two banners ({@code :71},
 * {@code :85}), the raw record image ({@code :78}), the three file-error texts ({@code :110},
 * {@code :129}, {@code :147}), the abend text ({@code :155}) and the two file-status lines
 * ({@code :168}, {@code :172}). Nothing renders a field label.
 *
 * <p>So one record produces <strong>one</strong> line of output - the whole
 * {@value CardRecord#RECORD_LENGTH}-byte record image written by {@code :78} - and the
 * fifty-record fixture produces exactly {@code 1 + 50 + 1 = 52} lines. Reproducing the sibling
 * reader's field-by-field rendering here would produce six hundred and fifty-two, and would fail
 * every one of this program's parity cases at once. The commented line stays commented: preserving
 * behaviour includes preserving the behaviour somebody switched off.
 *
 * <h2>What this class is, structurally</h2>
 * <p>A {@link Configuration} that publishes one {@link Job} of one <strong>tasklet</strong> step.
 * Tasklet rather than chunk-oriented, deliberately: the whole step body runs in one pass inside one
 * transaction, so the order in which records are read and lines are emitted is provably the order
 * the single-pass COBOL produces. Chunk orientation is correct for exactly two jobs in this
 * migration and this is not one of them - relocating commit boundaries would relocate the output.
 *
 * <p>The step is named {@code STEP05} after the JCL step it replaces
 * ({@code app/jcl/READCARD.jcl:22}, {@code //STEP05 EXEC PGM=CBACT02C}), and it takes
 * <strong>no job parameters</strong>, because that step carries no {@code PARM}. The step name and
 * the absence of parameters are both read back from the job contract at wiring time rather than
 * trusted, so a configuration that drifts from the JCL fails at startup instead of running the wrong
 * shape.
 *
 * <p>All framework plumbing arrives from {@code BatchConfig}: it owns the job repository, the
 * transaction manager and the return-code listeners, and hands out builders already bound to them.
 * This class therefore declares no repository, no launcher, no scheduler and no manual batch
 * activation, and it must never grow one - each of those would either duplicate a bean Spring Boot
 * already publishes or make by-type injection ambiguous across every job class at once.
 *
 * <h2>Every branch is reachable without a launcher</h2>
 * <p>{@link #execute(SysoutSink)} is the whole {@code PROCEDURE DIVISION} as a plain method over a
 * plain sink. A test drives it directly - no application context, no {@code JobLauncher}, no HTTP -
 * which is what makes the mandated per-package branch-coverage bar reachable deterministically, and
 * what lets a parity case assert the emitted line sequence rather than a job's exit status.
 *
 * <h2>WORKING-STORAGE is per-invocation state, never a field of this class</h2>
 * <p>{@code CBACT02C} declares {@code APPL-RESULT}, {@code END-OF-FILE}, {@code CARDFILE-STATUS} and
 * {@code IO-STATUS} in {@code WORKING-STORAGE} ({@code :46-:67}). Those become fields of a
 * short-lived {@link CardfileRead} object created per call, <strong>not</strong> fields of this
 * singleton and emphatically not static ones: a singleton holding a program's working storage would
 * corrupt any concurrent run and would make one test's outcome depend on another's. This class holds
 * only its injected collaborators, and every one of them is {@code final}.
 *
 * <h2>No dataset name appears in this file</h2>
 * <p>The card master is addressed by DD name only. {@link #cardfileDatasetName()} resolves
 * {@value #DD_NAME} through the job's own view of the dataset catalogue, so the name a deployment
 * actually uses is a configuration value and the JCL's {@code DSN=} literal appears nowhere in Java.
 * Records are read through {@code CardRepository}'s browse over the base cluster, which the
 * catalogue binds to the very same dataset under its CICS name - the two DD names are configured
 * aliases of one KSDS, exactly as the estate uses them.
 *
 * <h2>User-specified rules</h2>
 * <p>{@code review_rules} returns exactly one line - "No user rules provided." - and that single
 * line is the whole document, so <strong>no user rule governs this file</strong>. Its absence is not
 * licence to lower the bar. The migration plan's twelve enterprise practices bind instead, and the
 * ones bearing on this file are B1 (no dependency added - every type here comes from the batch and
 * JDBC starters already declared), B2 (Spring Batch 5.2.6 APIs only, with the published 6.x line
 * deliberately rejected), B3 (the COBOL, copybook and JCL trees cited throughout are read-only and
 * appear purely as provenance), B5 (behaviour preserved as found, which is why {@code :96} stays
 * commented), B7 (every decision here is reachable by a plain unit test), B8 (the code page and the
 * DD name are both named explicitly and never defaulted; no wildcard import), B9 (no static mutable
 * state, constructor injection throughout) and B11 (the record image comes from the hand-written
 * codec, so every byte offset stays reviewable against the copybook).
 *
 * @see CardRepository
 * @see CardRecord
 * @see AbendException
 * @see FileStatus
 */
@Configuration(AccountBalanceReaderJob.CONFIGURATION_BEAN_NAME)
public class AccountBalanceReaderJob {

    // =================================================================================================
    // Identity. Every value below is transcribed from the source or the JCL and none is invented.
    // =================================================================================================

    /**
     * The bean name of this configuration class: {@value}.
     *
     * <p>Stated explicitly, and it has to be. Spring names a component bean after its decapitalised
     * simple class name, which for this class is {@code accountBalanceReaderJob} - precisely the name
     * the {@link Job} published by {@link #accountBalanceReaderJob()} wants, since a {@code @Bean}
     * method's bean name is its method name. Two definitions of one name is refused outright, because
     * bean-definition overriding is disabled by default in Spring Boot, and the context would not
     * start. Naming the configuration explicitly hands the natural name to the job, which is the bean
     * anything downstream actually looks up.
     */
    public static final String CONFIGURATION_BEAN_NAME = "accountBalanceReaderJobConfiguration";

    /**
     * The COBOL {@code PROGRAM-ID} this job was translated from: {@value}.
     *
     * <p>{@code app/cbl/CBACT02C.cbl:23}. Carried into every {@link AbendException} this job raises,
     * so a failed run names the program a mainframe operator would have looked for.
     */
    public static final String PROGRAM_ID = "CBACT02C";

    /**
     * Diagnostics for the one thing this program does that its SYSOUT cannot carry: a failure to end the
     * card-file browse while unwinding from an abend. Nothing on a successful path is logged.
     */
    private static final Log LOG = LogFactory.getLog(AccountBalanceReaderJob.class);

    /**
     * This job's key in the {@code carddemo.jobs} configuration catalogue: {@value}.
     *
     * <p>The catalogue pairs it with {@link #PROGRAM_ID} and validates that pairing at startup, so
     * the key cannot be quietly re-pointed at another program.
     */
    public static final String JOB_KEY = "account-balance-reader-job";

    /**
     * The Spring Batch job name, which is also this job's identity in the batch metadata:
     * {@value}.
     *
     * <p>It matches the mandated class name so that anything launching this job finds it under the
     * name the migration plan assigns. It is not a bean name - see {@link #CONFIGURATION_BEAN_NAME}
     * for why the two must differ.
     */
    public static final String JOB_NAME = "accountBalanceReaderJob";

    /**
     * The single step's name, transcribed from the JCL step it replaces: {@value}.
     *
     * <p>{@code app/jcl/READCARD.jcl:22} is {@code //STEP05 EXEC PGM=CBACT02C}. That job declares one
     * step and no others, so this job has one step and no others.
     */
    public static final String STEP_NAME = "STEP05";

    /**
     * The whole step sequence of {@code app/jcl/READCARD.jcl}: one step, {@value #STEP_NAME}, running
     * {@value #PROGRAM_ID}, ungated.
     *
     * <p>The sequence rather than the name alone, because "one step, and that one" is what the JCL
     * declares and it is strictly stronger than "that step is present". A contract that declared
     * {@value #STEP_NAME} plus a second step would satisfy the weaker statement while running an
     * {@code EXEC} this job never had.
     */
    public static final List<StepContract> REQUIRED_STEPS =
            List.of(new StepContract(STEP_NAME, PROGRAM_ID, false));

    /**
     * The DD name of the one input this program reads: {@value}.
     *
     * <p>{@code app/cbl/CBACT02C.cbl:29} assigns the file to it and
     * {@code app/jcl/READCARD.jcl:25-26} binds it. The dataset it resolves to is a configuration
     * value, never a literal in Java - see {@link #cardfileDatasetName()}.
     */
    public static final String DD_NAME = "CARDFILE";

    // =================================================================================================
    // The DISPLAY literals, byte for byte. Every one of them is part of what a parity case inspects,
    // so not one may be reworded, capitalised differently or made consistent with a sibling program.
    // =================================================================================================

    /**
     * {@code app/cbl/CBACT02C.cbl:71}: {@code DISPLAY 'START OF EXECUTION OF PROGRAM CBACT02C'}.
     */
    public static final String START_BANNER = "START OF EXECUTION OF PROGRAM CBACT02C";

    /**
     * {@code app/cbl/CBACT02C.cbl:85}: {@code DISPLAY 'END OF EXECUTION OF PROGRAM CBACT02C'}.
     */
    public static final String END_BANNER = "END OF EXECUTION OF PROGRAM CBACT02C";

    /**
     * {@code app/cbl/CBACT02C.cbl:129}: {@code DISPLAY 'ERROR OPENING CARDFILE'}.
     *
     * <p>Note the wording. The sibling account reader says {@code 'ERROR OPENING ACCTFILE'} and, for
     * its read and close paragraphs, switches to the spaced form {@code 'ERROR READING ACCOUNT FILE'}.
     * <strong>This program says {@code CARDFILE} in all three, with no space.</strong> The
     * inconsistency between the two programs is the estate's, and normalising it here would change
     * observable output.
     */
    public static final String OPEN_ERROR_TEXT = "ERROR OPENING CARDFILE";

    /**
     * {@code app/cbl/CBACT02C.cbl:110}: {@code DISPLAY 'ERROR READING CARDFILE'}.
     *
     * @see #OPEN_ERROR_TEXT
     */
    public static final String READ_ERROR_TEXT = "ERROR READING CARDFILE";

    /**
     * {@code app/cbl/CBACT02C.cbl:147}: {@code DISPLAY 'ERROR CLOSING CARDFILE'}.
     *
     * @see #OPEN_ERROR_TEXT
     */
    public static final String CLOSE_ERROR_TEXT = "ERROR CLOSING CARDFILE";

    // =================================================================================================
    // APPL-RESULT and END-OF-FILE. app/cbl/CBACT02C.cbl:61-65.
    //
    //     01  APPL-RESULT             PIC S9(9)   COMP.
    //         88  APPL-AOK            VALUE 0.
    //         88  APPL-EOF            VALUE 16.
    //     01  END-OF-FILE             PIC X(01)    VALUE 'N'.
    //
    // The two condition names themselves - APPL-AOK and APPL-EOF - are NOT restated here. They are
    // FileStatus.APPL_AOK and FileStatus.APPL_EOF, declared once for the whole module, and a second
    // spelling of a value the guard chain turns on is exactly the kind of duplication that drifts.
    // =================================================================================================

    /**
     * The value moved into {@code APPL-RESULT} <em>before</em> an operation is attempted: {@value}.
     *
     * <p>{@code app/cbl/CBACT02C.cbl:119} opens with {@code MOVE 8 TO APPL-RESULT} and {@code :137}
     * closes with {@code ADD 8 TO ZERO GIVING APPL-RESULT}. Two different verbs, one identical
     * effect: the program assumes failure and then earns success. The read paragraph does not do this
     * - it assigns only after the read - so this value belongs to the open and the close alone.
     *
     * <p>It is the abend type's "assumed failure" return code, and is taken from there rather than
     * written as a bare {@code 8}.
     */
    public static final int APPL_RESULT_ASSUMED_FAILURE = AbendException.RETURN_CODE_ASSUMED_FAILURE;

    /**
     * The value moved into {@code APPL-RESULT} when an operation fails outright: {@value}.
     *
     * <p>{@code app/cbl/CBACT02C.cbl:101} for a failed read, {@code :124} for a failed open and
     * {@code :142} for a failed close. It becomes the process return code, so it is taken from the
     * abend type's I/O-error constant rather than written as a bare {@code 12}.
     */
    public static final int APPL_RESULT_FATAL = AbendException.RETURN_CODE_IO_ERROR;

    /**
     * {@code END-OF-FILE}'s initial value, {@code 'N'} - {@code app/cbl/CBACT02C.cbl:65}.
     */
    public static final char END_OF_FILE_NO = 'N';

    /**
     * The only value ever moved into {@code END-OF-FILE}, {@code 'Y'} -
     * {@code app/cbl/CBACT02C.cbl:108}.
     *
     * <p>{@code :108} is the sole {@code MOVE} to that field in the entire program, which is why
     * {@code 'N'} and {@code 'Y'} are its only two reachable values.
     */
    public static final char END_OF_FILE_YES = 'Y';

    // =================================================================================================
    // The two derived values the translation needs and the COBOL does not spell out.
    // =================================================================================================

    /**
     * The feedback byte reported inside a permanent-error status: binary zero.
     *
     * <p>Zero because a translated data-access failure carries no VSAM feedback code to report, and
     * inventing one would put a number into a job's log that no backend produced.
     */
    private static final char PERMANENT_ERROR_FEEDBACK_CODE = 0;

    /**
     * The two-character status reported for a failure that has no two-character equivalent:
     * {@code '9'} followed by {@link #PERMANENT_ERROR_FEEDBACK_CODE}.
     *
     * <p><strong>This is COBOL's own convention, not an invention of this translation.</strong> A
     * status whose first byte is {@code '9'} carries a binary feedback code in its second byte, and
     * {@code 9910-DISPLAY-IO-STATUS} tests for precisely that case before rendering it -
     * {@code IF IO-STATUS NOT NUMERIC OR IO-STAT1 = '9'} ({@code app/cbl/CBACT02C.cbl:162-163}).
     * Handing this value to {@link FileStatus#toDisplayLine(String)} therefore emits
     * {@code FILE STATUS IS: NNNN9000}, which is exactly the line the COBOL would have written.
     *
     * <p>It is needed because three of the responses the card repository can report - a length error,
     * an unreachable dataset and an otherwise invalid request - map to no two-character status at all,
     * and that repository reports the absence truthfully rather than fabricating one. This job must
     * still print a status line, so the permanent-error convention supplies it. Every such response
     * lands on the {@code ELSE MOVE 12} arm either way, which is where a permanent I/O error belongs.
     */
    public static final String PERMANENT_ERROR_STATUS = "9" + PERMANENT_ERROR_FEEDBACK_CODE;

    /**
     * The key a sequential read of the card master starts from: {@value CardRecord#CARD_NUM_LENGTH}
     * spaces.
     *
     * <p>{@code CBACT02C} declares {@code ACCESS MODE IS SEQUENTIAL} ({@code :31}) and simply
     * {@code READ}s, which walks the whole file in ascending key order from its first record. The card
     * repository's browse positions <em>at or after</em> the key it is given, so beginning at a value
     * that sorts at or below every key begins at the first record - and a space sorts below every
     * digit and every letter in both of this module's supported code pages, so a key of spaces is that
     * value.
     *
     * <p>{@code CARD-NUM} is {@code PIC X(16)} ({@code app/cpy/CVACT02Y.cpy:5}), so the width is taken
     * from the model rather than written as a literal sixteen.
     */
    public static final String LOWEST_CARD_NUMBER_KEY = " ".repeat(CardRecord.CARD_NUM_LENGTH);

    // =================================================================================================
    // Collaborators. Four, all final, all constructor-injected, none of them mutable (practice B9,
    // gate G53). This class holds no program state whatsoever - see CardfileRead for where it lives.
    // =================================================================================================

    /**
     * The module's batch scaffolding: the source of the job and step builders, of the job contract
     * this job validates itself against, and of the DD-name resolution
     * {@link #cardfileDatasetName()} performs.
     */
    private final BatchConfig batchConfig;

    /**
     * The card master's data-access component, used for its sequential browse over the
     * <strong>base</strong> cluster.
     *
     * <p>Not the account repository, whatever this class is called, and not the alternate-index
     * finder: {@code CBACT02C} reads the base cluster in key order and never positions by account id.
     */
    private final CardRepository cardRepository;

    /**
     * The active dataset code page, selected by bean name so the choice is visible in the wiring.
     *
     * <p>It renders the {@value CardRecord#RECORD_LENGTH}-byte record image that {@code :78}
     * displays. A fixed-width mainframe record is bytes in a specific code page, so the code page is
     * always stated and the platform default is never consulted (practice B8).
     *
     * <p>The bean is selected by {@link CardRepository#DATASET_CHARSET_BEAN_NAME} rather than by the
     * charset configuration's own copy of that name. The two constants hold the same value, and taking
     * it from the repository this job reads through makes it structurally impossible for the job to
     * render a record in one code page while the repository decoded it in another.
     */
    private final Charset datasetCharset;

    /**
     * Where this job's {@code DISPLAY} output goes, resolved on each use.
     *
     * <p>An {@link ObjectProvider} rather than a {@link SysoutSink} directly, so that a deployment or
     * a test may publish one and the job falls back to {@link #standardOutputSysoutSink()} when
     * nothing does. Resolution is deferred to the point of use because a sink published elsewhere in
     * the context need not exist when this configuration is constructed.
     */
    private final ObjectProvider<SysoutSink> sysoutSinkProvider;

    /**
     * Wiring constructor.
     *
     * @param batchConfig        the module's batch scaffolding; never {@code null}
     * @param cardRepository     the card master's data-access component; never {@code null}
     * @param datasetCharset     the active dataset code page, selected by bean name; never
     *                           {@code null}
     * @param sysoutSinkProvider provider for an application-supplied {@link SysoutSink}; never
     *                           {@code null}, though it may resolve to nothing
     * @throws NullPointerException if any argument is {@code null}
     */
    public AccountBalanceReaderJob(
            BatchConfig batchConfig,
            CardRepository cardRepository,
            @Qualifier(CardRepository.DATASET_CHARSET_BEAN_NAME) Charset datasetCharset,
            ObjectProvider<SysoutSink> sysoutSinkProvider) {
        this.batchConfig = Objects.requireNonNull(batchConfig,
                "The batch scaffolding is required: this job owns no job repository, no transaction "
                        + "manager and no listeners of its own, and receives all three through it");
        this.cardRepository = Objects.requireNonNull(cardRepository,
                "The card repository is required: CBACT02C reads the card master and nothing else, "
                        + "however this class is named");
        this.datasetCharset = Objects.requireNonNull(datasetCharset,
                "A dataset charset is required: the record image DISPLAY writes is bytes in a specific "
                        + "code page, so the code page is stated explicitly and never taken from the "
                        + "platform");
        this.sysoutSinkProvider = Objects.requireNonNull(sysoutSinkProvider,
                "A sink provider is required: SYSOUT is a seam so that a parity case can capture the "
                        + "exact line sequence this job emits");
        // This job's JCL names its input CARDFILE (app/jcl/READCARD.jcl:25-26); the repository it reads
        // through is bound to the CICS file name CARDDAT, because the online programs address it that
        // way. Both keys carry independent overrides in application.yml, so they can be pointed at
        // different datasets - and a step that read a dataset its own DD statement never named would do
        // so silently and with a correct-looking result. Proven equal here, once, so a divergence fails
        // the context instead of a run.
        this.batchConfig.requireSameDataset(JOB_KEY, DD_NAME, CardRepository.BASE_DD_NAME);
    }

    // =================================================================================================
    // Wiring. One job, one tasklet step, no job parameters - app/jcl/READCARD.jcl:22.
    // =================================================================================================

    /**
     * The runnable job: one tasklet step, {@code STEP05}, and nothing else.
     *
     * <p><strong>Nothing here launches it.</strong> Batch job launching is switched off in both
     * profiles and this class adds no scheduler, no start-up runner and no timer, so the job runs only
     * when something deliberately launches it - which is how a job ran on the mainframe, when JCL
     * submitted an {@code EXEC PGM=} step and not before.
     *
     * <p><strong>No business parameter is declared or invented.</strong>
     * {@code app/jcl/READCARD.jcl:22} passes nothing, so this job accepts nothing, and
     * {@link #requireContract()} checks that the configured contract agrees. What
     * {@link BatchConfig#job(String)} does attach is a <em>run identity</em> and a non-restartable
     * policy, which together make every launch a whole fresh run - the necessary consequence of a job
     * with no parameters, since without an identity of its own it would have exactly one instance for
     * all time and a second submission of {@code READCARD} would be refused as already complete. The
     * identity is not a value the COBOL receives; it is the equivalent of submitting the JCL again.
     *
     * @return the job, bound to the shared job repository and carrying the shared return-code
     *         listener; never {@code null}
     * @throws IllegalStateException if the configured contract for {@value #JOB_KEY} is missing,
     *                               declares a step other than {@value #STEP_NAME}, or declares a job
     *                               parameter
     */
    @Bean
    public Job accountBalanceReaderJob() {
        return batchConfig.job(JOB_NAME)
                .start(readCardfileStep())
                .build();
    }

    /**
     * The one step, a <strong>tasklet</strong> named after the JCL step it replaces.
     *
     * <p>Tasklet and not chunk-oriented, and that is a parity decision rather than a stylistic one.
     * The whole of {@code CBACT02C}'s {@code PROCEDURE DIVISION} is a single pass: it opens, walks the
     * file to its end, closes, and emits its output strictly in the order it reads. A tasklet runs
     * that body inside one transaction and preserves that order by construction. Chunking it would
     * relocate the commit boundaries and, with them, the order in which lines reach the output - and
     * output order is precisely what a parity case compares.
     *
     * <p>The step is ungated. {@code COND=(0,NE)} appears on three steps of one job elsewhere in the
     * estate and on nothing in {@code app/jcl/READCARD.jcl}, which declares a single step that
     * therefore has nothing preceding it to be gated behind. The configured contract records that as
     * {@code require-preceding-exit-code-zero: false} and inventing gating here would bypass work the
     * mainframe performs.
     *
     * @return a fresh step, bound to the shared repository, the shared listener and the module's
     *         transaction manager; never {@code null}
     * @throws IllegalStateException if the configured contract is missing or disagrees with the JCL
     */
    public Step readCardfileStep() {
        JobContract contract = requireContract();
        return batchConfig.taskletStep(contract.step(STEP_NAME).name(), readCardfileTasklet()).build();
    }

    /**
     * The step body: the whole program, run once.
     *
     * <p>It resolves the sink and delegates to {@link #execute(SysoutSink, StopSignal)}, then reports
     * {@link RepeatStatus#FINISHED} because {@code CBACT02C} runs exactly once and
     * {@code GOBACK}s ({@code app/cbl/CBACT02C.cbl:87}) - there is no second pass to ask for.
     *
     * <p>The chunk context is read for one thing: this step execution's {@link StopSignal}, which the
     * pass consults between records. Because the tasklet runs once, the framework's own interruption
     * check happens once at the top and cannot end a pass already under way.
     *
     * <p>An {@link AbendException} raised inside is <strong>not</strong> caught here. It propagates so
     * that the shared listeners can carry its {@code RETURN-CODE} onto the step's exit status and out
     * to the process exit code, which is what makes a JCL-equivalent {@code COND} test on a following
     * step still work. Swallowing it would report a clean run over a failed one. A
     * {@link BatchConfig.StopRequestedException} propagates for the same reason and to the same
     * effect, except that the framework reads it as a stop rather than a failure.
     *
     * @return the tasklet; never {@code null}
     */
    public Tasklet readCardfileTasklet() {
        return (contribution, chunkContext) -> {
            execute(sysoutSink(), StopSignal.of(chunkContext));
            return RepeatStatus.FINISHED;
        };
    }

    /**
     * The dataset the {@value #DD_NAME} DD resolves to for this job, as configuration declares it.
     *
     * <p>Exposed so that a deployment can verify the binding without starting a run, and so that a
     * test can assert it without a backend. Resolution is the job's own view first and the global
     * catalogue second, because a DD name is not globally unique in this estate.
     *
     * <p>This method is the reason no {@code DSN=} literal appears anywhere in this file. The JCL
     * names a dataset at {@code app/jcl/READCARD.jcl:26}; Java names only the DD.
     *
     * @return the configured dataset name for {@value #DD_NAME}; never blank
     * @throws IllegalStateException if neither this job nor the global catalogue declares
     *                               {@value #DD_NAME}
     */
    public String cardfileDatasetName() {
        return batchConfig.datasetBinding(JOB_KEY, DD_NAME).dsname();
    }

    /**
     * This job's configured contract, checked against the JCL it was transcribed from.
     *
     * <p>Two checks, and both catch a real class of drift at startup rather than mid-run:
     * <ul>
     *   <li>the contract must declare <strong>exactly</strong> {@link #REQUIRED_STEPS} - the one
     *       {@value #STEP_NAME} step running {@value #PROGRAM_ID}, ungated, and nothing else.
     *       {@link JobContract#step(String)} would confirm only that such a step exists somewhere in the
     *       sequence, which says nothing about a second step alongside it, a different program on it or
     *       a {@code COND} gate over it, so the sequence is compared as a whole;</li>
     *   <li>it must declare <strong>no</strong> job parameter, because
     *       {@code app/jcl/READCARD.jcl:22} is a bare {@code EXEC PGM=} with no {@code PARM}. A
     *       parameter declared against this job would be an input {@code CBACT02C} never receives, and
     *       the program has no {@code LINKAGE SECTION} to receive one into.</li>
     * </ul>
     *
     * <p>The {@code carddemo.jobs} catalogue makes both statements too, at context refresh. This is not
     * a duplicated guarantee: nothing sequences that validator ahead of this bean, so a job constructed
     * first would otherwise resolve its datasets and build its step from a contract that was about to
     * be rejected.
     *
     * @return the contract; never {@code null}
     * @throws IllegalStateException if the contract is absent, declares any step sequence other than
     *                               {@link #REQUIRED_STEPS}, or declares a job parameter
     */
    private JobContract requireContract() {
        JobContract contract = batchConfig.contract(JOB_KEY);
        batchConfig.requireSteps(JOB_KEY, REQUIRED_STEPS, "app/jcl/READCARD.jcl:22");
        List<JobParameterContract> parameters = contract.parameters();
        if (!parameters.isEmpty()) {
            throw new IllegalStateException("carddemo.jobs." + JOB_KEY + " declares "
                    + parameters.size() + " job parameter(s) - "
                    + parameters.stream().map(JobParameterContract::name).toList()
                    + " - but app/jcl/READCARD.jcl:22 is a bare //STEP05 EXEC PGM=" + PROGRAM_ID
                    + " with no PARM, and " + PROGRAM_ID + " declares no LINKAGE SECTION to receive "
                    + "one into. Remove the parameter: the only step in this estate carrying a PARM "
                    + "belongs to a different job.");
        }
        return contract;
    }

    // =================================================================================================
    // The PROCEDURE DIVISION. app/cbl/CBACT02C.cbl:70-87.
    // =================================================================================================

    /**
     * Runs the whole program once, writing every {@code DISPLAY} to {@code sysout}.
     *
     * <p>The literal translation of {@code app/cbl/CBACT02C.cbl:70-87}:
     *
     * <pre>
     * PROCEDURE DIVISION.
     *     DISPLAY 'START OF EXECUTION OF PROGRAM CBACT02C'.        :71
     *     PERFORM 0000-CARDFILE-OPEN.                              :72
     *     PERFORM UNTIL END-OF-FILE = 'Y'                          :74
     *         IF  END-OF-FILE = 'N'                                :75
     *             PERFORM 1000-CARDFILE-GET-NEXT                   :76
     *             IF  END-OF-FILE = 'N'                            :77
     *                 DISPLAY CARD-RECORD                          :78
     *             END-IF
     *         END-IF
     *     END-PERFORM.                                             :81
     *     PERFORM 9000-CARDFILE-CLOSE.                             :83
     *     DISPLAY 'END OF EXECUTION OF PROGRAM CBACT02C'.          :85
     *     GOBACK.                                                  :87
     * </pre>
     *
     * <p>Fifty records in therefore means fifty-two lines out: one banner, fifty
     * {@value CardRecord#RECORD_LENGTH}-character record images, one banner. Nothing else. See this
     * class's documentation for why {@code :96} does not add a fifty-first through six-hundredth.
     *
     * <p>Each call builds its own {@link CardfileRead}, so a call carries its own
     * {@code WORKING-STORAGE} and two calls cannot interfere.
     *
     * @param sysout where the {@code DISPLAY} output goes; never {@code null}
     * @throws NullPointerException if {@code sysout} is {@code null}
     * @throws AbendException       if the open, a read or the close fails, carrying
     *                              {@link #APPL_RESULT_FATAL} as the {@code RETURN-CODE} exactly as
     *                              {@code 9999-ABEND-PROGRAM} does
     */
    public void execute(SysoutSink sysout) {
        execute(sysout, StopSignal.RUNNING);
    }

    /**
     * Runs the program, yielding to the given stop signal between records.
     *
     * <p>The pass is identical to {@link #execute(SysoutSink)} - same reads, same displayed lines, same
     * order - and the signal changes nothing while no stop is pending. It exists because
     * {@code CBACT02C} is one pass over the whole card master inside a single tasklet invocation, so the
     * framework's interruption check at the step's repeat boundary happens once and cannot end a pass
     * already under way. The probe is consulted between records, where the record in flight is always
     * complete, and nothing is retried; see {@link StopSignal}.
     *
     * @param sysout     where the {@code DISPLAY} output goes; never {@code null}
     * @param stopSignal the between-record cancellation probe; {@link StopSignal#RUNNING} for a caller
     *                   outside a step; never {@code null}
     * @throws NullPointerException if {@code sysout} or {@code stopSignal} is {@code null}
     * @throws AbendException       if the open, a read or the close fails, carrying
     *                              {@link #APPL_RESULT_FATAL} as the {@code RETURN-CODE} exactly as
     *                              {@code 9999-ABEND-PROGRAM} does
     * @throws BatchConfig.StopRequestedException if the step is asked to stop, which abandons the pass
     *                              between records
     */
    public void execute(SysoutSink sysout, StopSignal stopSignal) {
        Objects.requireNonNull(sysout, "A SYSOUT sink is required to run " + PROGRAM_ID
                + "; every one of its DISPLAY statements writes through it");
        Objects.requireNonNull(stopSignal, "A stop signal is required; pass StopSignal.RUNNING outside a "
                + "step, which is what the single-argument overload does");
        new CardfileRead(cardfileRepository(), datasetCharset, sysout).execute(stopSignal);
    }

    /**
     * The card repository addressing <strong>this job's</strong> {@value #DD_NAME} DD.
     *
     * <p>{@code CBACT02C} reads the dataset {@code app/jcl/READCARD.jcl:25-26} binds to
     * {@code //CARDFILE DD}, and this job resolves that DD through its own view of the catalogue - the
     * job-scoped entry first, the global one second - because a DD name is not unique across this
     * estate. The injected repository resolved {@link CardRepository#BASE_DD_NAME} from the global
     * catalogue instead, which is the <em>online</em> name for the same dataset.
     *
     * <p>Those two resolutions agree in the shipped configuration, and the finding was that nothing
     * required them to: the job validated {@value #DD_NAME} in {@link #cardfileDatasetName()} and then
     * read through {@code CARDDAT}, so a deployment that re-pointed {@value #DD_NAME} - which is
     * precisely what a {@code //CARDFILE DD} statement does - would have read a dataset nobody asked
     * for while every startup check passed. Handing the resolved binding to the repository makes the
     * declared DD the one that drives the read.
     *
     * <p>Resolved per execution rather than held, because the catalogue is the configuration's to
     * answer and this class holds no I/O state (practice B9). The repository returns itself when the
     * binding names the dataset it already addresses, so the common case costs nothing.
     *
     * @return the repository this run reads through; never {@code null}
     * @throws IllegalStateException if neither this job nor the global catalogue declares
     *                               {@value #DD_NAME}, or the binding is unusable
     */
    private CardRepository cardfileRepository() {
        return cardRepository.addressing(batchConfig.datasetBinding(JOB_KEY, DD_NAME), DD_NAME);
    }

    /**
     * One run of {@code CBACT02C}, holding that run's {@code WORKING-STORAGE}.
     *
     * <p>A short-lived object per call rather than fields on the surrounding singleton, because
     * {@code APPL-RESULT}, {@code END-OF-FILE}, {@code CARDFILE-STATUS} and {@code IO-STATUS} are
     * program state: as singleton fields they would be shared across concurrent runs and would leak
     * one test's outcome into the next. Nothing here is {@code static} and nothing here outlives the
     * call that created it (practice B9, gate G53).
     *
     * <p>{@code static} on the class itself is the opposite point - it holds no reference back to the
     * configuration, so a run cannot reach the container from inside the program's own logic.
     */
    private static final class CardfileRead {

        /** The card master's data-access component, for its browse over the base cluster. */
        private final CardRepository cardRepository;

        /** The code page the {@code :78} record image is rendered in. */
        private final Charset datasetCharset;

        /** Where every {@code DISPLAY} of this run goes. */
        private final SysoutSink sysout;

        /**
         * {@code 01 APPL-RESULT PIC S9(9) COMP} - {@code app/cbl/CBACT02C.cbl:61}.
         *
         * <p>Tested through the {@code 88} condition names {@code APPL-AOK} and {@code APPL-EOF} and
         * never compared to a bare number at a call site.
         */
        private int applResult;

        /**
         * {@code 01 END-OF-FILE PIC X(01) VALUE 'N'} - {@code app/cbl/CBACT02C.cbl:65}, initialised
         * here to the same value the {@code VALUE} clause gives it.
         */
        private char endOfFile = END_OF_FILE_NO;

        /**
         * {@code 01 CARDFILE-STATUS} - {@code app/cbl/CBACT02C.cbl:46-48}: the two-character status
         * the file system reports for the most recent operation.
         */
        private String cardfileStatus;

        /**
         * {@code 01 IO-STATUS} - {@code app/cbl/CBACT02C.cbl:50-52}.
         *
         * <p>A field of its own and not an alias of {@link #cardfileStatus}, because the COBOL keeps
         * them apart: each error arm performs {@code MOVE CARDFILE-STATUS TO IO-STATUS} before
         * {@code 9910-DISPLAY-IO-STATUS} reads it ({@code :111}, {@code :130}, {@code :148}). The move
         * is what makes the status line report the failing operation's status rather than whatever the
         * next one produces.
         */
        private String ioStatus;

        /**
         * The open card file: the browse handle, standing in for the open {@code CARDFILE-FILE}.
         *
         * <p>{@code null} until {@code 0000-CARDFILE-OPEN} has succeeded, which is the state an
         * unopened COBOL file is in.
         */
        private CardBrowse cardfile;

        /**
         * {@code 01 CARD-RECORD} from {@code COPY CVACT02Y.} - {@code app/cbl/CBACT02C.cbl:45}.
         *
         * <p>The target of {@code READ CARDFILE-FILE INTO CARD-RECORD} ({@code :93}). Its decoded
         * fields are what a parity case asserts field by field; the {@code DISPLAY} at {@code :78}
         * writes {@link #cardRecordImage} instead, for the reason recorded there.
         */
        private CardRecord cardRecord;

        /**
         * The {@value CardRepository#RECORD_LENGTH} bytes {@code READ ... INTO CARD-RECORD} moved into
         * the record area, as characters - the operand of the {@code DISPLAY} at {@code :78}.
         *
         * <p><strong>Why this exists alongside {@link #cardRecord}.</strong> {@code DISPLAY CARD-RECORD}
         * names the {@code 01} group item, so it writes the whole area, and the area's last
         * {@value CardRecord#FILLER_LENGTH} bytes are {@code FILLER X(59)}
         * ({@code app/cpy/CVACT02Y.cpy}) - a span that holds no field, that this program never writes,
         * and that {@code READ ... INTO} fills with whatever the row held. Rendering the line from the
         * decoded fields would agree on all six declared fields and emit fifty-nine spaces for the
         * {@code FILLER} whatever was stored, which is a different line from the one the program writes
         * whenever a row carries anything else there. So the row's own image is kept and displayed.
         *
         * <p>Per-run state on the working-storage object, exactly as the record area is, and never a
         * field on the job bean (practice <strong>B9</strong>, gate <strong>G53</strong>).
         */
        private String cardRecordImage;

        /**
         * The failure behind the most recent non-{@link FileStatus#OK} status, where the translation
         * has one to hand.
         *
         * <p>It has <strong>no COBOL counterpart</strong> and no observable effect: COBOL has no
         * exception chain, so an {@code OPEN} that fails leaves a status and nothing else. It is kept
         * only so that the abend can carry the underlying refusal as its cause, which preserves the
         * diagnostic trail for whoever reads the stack. It is deliberately not rendered into any
         * {@code DISPLAY} line, because a line the COBOL does not write must not appear.
         *
         * <p>{@code null} where the failure produced no exception, which is every read failure: the
         * repository reports those as a response rather than by throwing.
         */
        private RuntimeException datasetRefusal;

        /**
         * @param cardRepository the card master's data-access component
         * @param datasetCharset the code page the record image is rendered in
         * @param sysout         where this run's {@code DISPLAY} output goes
         */
        private CardfileRead(CardRepository cardRepository, Charset datasetCharset,
                             SysoutSink sysout) {
            this.cardRepository = cardRepository;
            this.datasetCharset = datasetCharset;
            this.sysout = sysout;
        }

        /**
         * {@code PROCEDURE DIVISION} - {@code app/cbl/CBACT02C.cbl:70-87}.
         *
         * @param stopSignal the between-record cancellation probe
         * @throws AbendException if the open, a read or the close fails
         * @throws BatchConfig.StopRequestedException if the step is asked to stop
         */
        private void execute(StopSignal stopSignal) {
            sysout.write(START_BANNER);                                      // :71
            openCardfile();                                                  // :72
            try {
                executeAfterOpen(stopSignal);
            } finally {
                releaseBrowse();
            }
        }

        /**
         * The read loop and the close - everything {@link #execute()} performs once the file is open.
         *
         * <p>Split out so the browse can be released on every exit from the pass without the
         * paragraph-by-paragraph body acquiring a nesting level it does not have in the source.
         *
         * @param stopSignal the between-record cancellation probe
         * @throws AbendException if a read or the close fails
         */
        private void executeAfterOpen(StopSignal stopSignal) {

            // :74  PERFORM UNTIL END-OF-FILE = 'Y'
            while (!endOfFile()) {
                // NO COBOL COUNTERPART. The between-record yield to a stop request: a call rather than
                // a condition, so it adds no arm to the translated control flow, and positioned before
                // the read below so the record in flight is always complete. See BatchConfig.StopSignal.
                stopSignal.checkStopRequested();

                // :75  IF END-OF-FILE = 'N' - true on every iteration, by construction. The loop
                // condition immediately above has just tested the same one-character field, and 'N'
                // and 'Y' are its only two reachable values: :65 initialises it to 'N' and :108 is
                // the sole MOVE to it in the whole program, moving 'Y'. The COBOL states the guard
                // redundantly; restating it in Java would add a branch nothing could ever take, and
                // an unreachable branch is worse than no branch - it reads as a case somebody forgot
                // to cover. No observable outcome changes, which is the standard rule R7 sets for
                // restructuring control flow.
                readNextCardfileRecord();                                    // :76

                // :77  IF END-OF-FILE = 'N' - genuinely two-armed: the read above is what sets the
                // field, and the last read of the file takes the other arm.
                if (!endOfFile()) {
                    // :78  DISPLAY CARD-RECORD. The whole 150-byte record area, one line, and the only
                    // per-record output this program produces. The row's own image rather than a
                    // re-encoding of the decoded fields: the two differ in FILLER X(59), which the read
                    // fills from the row and which a re-encode would blank.
                    sysout.write(cardRecordImage);
                }
            }                                                                // :81

            closeCardfile();                                                 // :83
            sysout.write(END_BANNER);                                        // :85
            // :87  GOBACK - RETURN-CODE is left at zero, which a normal return from this method is.
        }

        /**
         * Ends the card-file browse on the way out of an incomplete run, silently and only if it is still
         * open.
         *
         * <p>{@code CBACT02C} abends outright without closing, so this changes nothing the program
         * observably produces. Three properties make that true rather than merely intended:
         *
         * <ul>
         *   <li><strong>It costs nothing.</strong> {@link CardBrowse#endBrowse()} sets a flag and issues
         *       no I/O at all, so releasing an already-ended browse is free and releasing an open one on
         *       the abend path is equally free.</li>
         *   <li><strong>It is silent.</strong> No {@code DISPLAY} is emitted. The line sequence is this
         *       program's entire observable output and the source has no such line.</li>
         *   <li><strong>It cannot displace the real failure.</strong> Any exception raised while
         *       releasing is swallowed, so the {@link AbendException} the run is already unwinding with
         *       is the one the caller receives.</li>
         * </ul>
         *
         * <p>The reason it exists at all is the contract rather than a present leak: {@link CardBrowse}
         * declares {@link AutoCloseable}, and a pass that only releases on its normal tail depends on
         * what the handle happens to hold today instead of on what it promises.
         *
         * <p>The guard is {@link #closeIssued} - this execution's own record of having run
         * {@code 9000-CARDFILE-CLOSE} - rather than {@link CardBrowse#isEnded()}. Asking the handle
         * would make "exactly one {@code CLOSE} per run", which is what the single {@code CLOSE}
         * statement at {@code :83} means, depend on the handle tracking its own state; asking this
         * execution makes it depend on the program's control flow, which is where the property actually
         * comes from.
         */
        private void releaseBrowse() {
            if (cardfile == null || closeIssued) {
                return;
            }
            try {
                cardfile.endBrowse();
            } catch (RuntimeException cleanupFailure) {
                // Only the failure's TYPE is logged - never the throwable and never its message. A driver
                // composes its message around the value it refused, and a card row carries the card
                // number and CVV (CWE-532); a newline in that text could forge a second log entry
                // (CWE-117). A class name carries no data and no newline.
                LOG.warn("Ending the " + DD_NAME + " browse of " + PROGRAM_ID + " after an incomplete run "
                        + "failed - " + cleanupFailure.getClass().getName()
                        + ". The run's own outcome is reported unchanged, because the run's own failure is "
                        + "the one that matters.");
            }
        }

        /**
         * {@code IF END-OF-FILE = 'Y'} / {@code IF END-OF-FILE = 'N'}.
         *
         * @return {@code true} once {@code END-OF-FILE} holds {@link #END_OF_FILE_YES}
         */
        private boolean endOfFile() {
            return endOfFile == END_OF_FILE_YES;
        }

        // -------------------------------------------------------------------------------------------
        // 0000-CARDFILE-OPEN - app/cbl/CBACT02C.cbl:118-134.
        // -------------------------------------------------------------------------------------------

        /**
         * {@code 0000-CARDFILE-OPEN}.
         *
         * <pre>
         *     MOVE 8 TO APPL-RESULT.                       :119
         *     OPEN INPUT CARDFILE-FILE                     :120
         *     IF  CARDFILE-STATUS = '00'                   :121
         *         MOVE 0 TO APPL-RESULT                    :122
         *     ELSE
         *         MOVE 12 TO APPL-RESULT                   :124
         *     END-IF
         *     IF  APPL-AOK                                 :126
         *         CONTINUE                                 :127
         *     ELSE
         *         DISPLAY 'ERROR OPENING CARDFILE'         :129
         *         MOVE CARDFILE-STATUS TO IO-STATUS        :130
         *         PERFORM 9910-DISPLAY-IO-STATUS           :131
         *         PERFORM 9999-ABEND-PROGRAM               :132
         *     END-IF
         * </pre>
         *
         * @throws AbendException if the open reports anything but {@link FileStatus#OK}
         */
        /**
         * Whether {@code 9000-CARDFILE-CLOSE} has already issued this execution's {@code CLOSE}.
         *
         * <p>Per-execution state on a per-execution object, never a field of the singleton job bean
         * (practice B9, gate G53).
         */
        private boolean closeIssued;

        private void openCardfile() {
            // :119  The program assumes failure before it attempts the open and earns success below.
            // The assignment is overwritten either way; it is kept because the COBOL performs it.
            applResult = APPL_RESULT_ASSUMED_FAILURE;

            cardfileStatus = openCardfileBrowse();                           // :120

            if (FileStatus.OK.equals(cardfileStatus)) {                      // :121
                applResult = FileStatus.APPL_AOK;                            // :122
            } else {
                applResult = APPL_RESULT_FATAL;                              // :124
            }

            if (applResult == FileStatus.APPL_AOK) {                         // :126  IF APPL-AOK
                return;                                                      // :127  CONTINUE
            }
            throw reportFileErrorAndAbend(OPEN_ERROR_TEXT);                  // :129-:132
        }

        /**
         * {@code OPEN INPUT CARDFILE-FILE} - {@code app/cbl/CBACT02C.cbl:120}.
         *
         * <p>Two things happen when a COBOL program opens a file, and both are reproduced. The DD name
         * is resolved to a dataset - which is what the {@code //CARDFILE DD} statement supplies at
         * {@code app/jcl/READCARD.jcl:25-26} - and the file is positioned for the first sequential
         * read. Either can fail, so both sit inside the guard.
         *
         * <p>The failure is reported as a status rather than propagated, because that is what
         * {@code OPEN} does: it has a {@code FILE STATUS} clause ({@code :33}) and no exception, and
         * the caller's own guard chain decides what a bad status means. It decides to abend one line
         * later, so nothing is swallowed - the original failure is carried out as the abend's cause.
         *
         * @return {@link FileStatus#OK} when the file is open and positioned, or
         *         {@link #PERMANENT_ERROR_STATUS} when it could not be
         */
        private String openCardfileBrowse() {
            try {
                // openBrowse, not startBrowse: this program tests its OPEN. startBrowse is the online
                // entry point, which issues no backend call and reports nothing because COCRDLIC discards
                // its own STARTBR response - reading through it here left ':129-:132' unreachable, so an
                // unusable dataset first appeared as 'ERROR READING CARDFILE' on the following read.
                cardfile = cardRepository.openBrowse(LOWEST_CARD_NUMBER_KEY, BrowseDirection.FORWARD);
                // The repository answers in CICS responses, translated the same way a read's response is
                // translated (see readNextCardfileRecord) so the open and the read reach their arms
                // identically. A response with no batch equivalent lands on the permanent-error
                // convention, and thence on the ELSE MOVE 12 arm.
                return FileStatus.batchStatusOfCicsResp(cardfile.openResp())
                        .orElse(PERMANENT_ERROR_STATUS);
            } catch (RuntimeException refused) {
                // A configuration fault rather than a dataset condition - an unresolvable binding, say.
                // Still reported as a status, because the caller abends one line later and carries this
                // out as the abend's cause, so nothing is swallowed.
                datasetRefusal = refused;
                return PERMANENT_ERROR_STATUS;
            }
        }

        // -------------------------------------------------------------------------------------------
        // 1000-CARDFILE-GET-NEXT - app/cbl/CBACT02C.cbl:92-116.
        // -------------------------------------------------------------------------------------------

        /**
         * {@code 1000-CARDFILE-GET-NEXT}.
         *
         * <pre>
         *     READ CARDFILE-FILE INTO CARD-RECORD.         :93
         *     IF  CARDFILE-STATUS = '00'                   :94
         *         MOVE 0 TO APPL-RESULT                    :95
         * *        DISPLAY CARD-RECORD                     :96  &lt;-- A COMMENT. NOT CODE.
         *     ELSE
         *         IF  CARDFILE-STATUS = '10'               :98
         *             MOVE 16 TO APPL-RESULT               :99
         *         ELSE
         *             MOVE 12 TO APPL-RESULT               :101
         *         END-IF
         *     END-IF
         *     IF  APPL-AOK                                 :104
         *         CONTINUE                                 :105
         *     ELSE
         *         IF  APPL-EOF                             :107
         *             MOVE 'Y' TO END-OF-FILE              :108
         *         ELSE
         *             DISPLAY 'ERROR READING CARDFILE'     :110
         *             MOVE CARDFILE-STATUS TO IO-STATUS    :111
         *             PERFORM 9910-DISPLAY-IO-STATUS       :112
         *             PERFORM 9999-ABEND-PROGRAM           :113
         *         END-IF
         *     END-IF
         * </pre>
         *
         * <p>The {@code MOVE 0} at {@code :95} is the <em>only</em> statement on the success arm.
         * {@code :96} is commented out, so a successful read emits nothing at all from here; the one
         * line a record produces is written by the mainline at {@code :78}.
         *
         * @throws AbendException if the read reports a status that is neither {@link FileStatus#OK} nor
         *                        {@link FileStatus#END_OF_FILE}
         */
        private void readNextCardfileRecord() {
            CardReadResult result = cardfile.readNext();                      // :93

            // READ ... INTO moves the record whenever one is delivered, before any status is tested.
            // Both views of the same bytes move together, because both come from the same row: the
            // decoded fields for everything that branches on a value, and the stored image for the
            // DISPLAY, which writes the area including its FILLER.
            if (result.isRecordReturned()) {
                cardRecord = result.requireRecord();
                cardRecordImage = result.requireStoredImage();
            }

            // The repository reports a CICS response, which maps to the two-character batch status the
            // COBOL tests - and reports no status at all for the three responses that genuinely have
            // none. Those land on the permanent-error convention, and thence on the ELSE MOVE 12 arm.
            cardfileStatus = result.batchStatus().orElse(PERMANENT_ERROR_STATUS);

            if (FileStatus.OK.equals(cardfileStatus)) {                       // :94
                applResult = FileStatus.APPL_AOK;                             // :95
                // :96 DISPLAY CARD-RECORD is commented out in the source and stays absent here.
            } else if (FileStatus.END_OF_FILE.equals(cardfileStatus)) {        // :98
                applResult = FileStatus.APPL_EOF;                             // :99
            } else {
                applResult = APPL_RESULT_FATAL;                               // :101
            }

            if (applResult == FileStatus.APPL_AOK) {                          // :104  IF APPL-AOK
                return;                                                       // :105  CONTINUE
            }
            if (applResult == FileStatus.APPL_EOF) {                          // :107  IF APPL-EOF
                endOfFile = END_OF_FILE_YES;                                  // :108
                return;
            }
            throw reportFileErrorAndAbend(READ_ERROR_TEXT);                    // :110-:113
        }

        // -------------------------------------------------------------------------------------------
        // 9000-CARDFILE-CLOSE - app/cbl/CBACT02C.cbl:136-152.
        // -------------------------------------------------------------------------------------------

        /**
         * {@code 9000-CARDFILE-CLOSE}.
         *
         * <pre>
         *     ADD 8 TO ZERO GIVING APPL-RESULT.            :137
         *     CLOSE CARDFILE-FILE                          :138
         *     IF  CARDFILE-STATUS = '00'                   :139
         *         SUBTRACT APPL-RESULT FROM APPL-RESULT    :140
         *     ELSE
         *         ADD 12 TO ZERO GIVING APPL-RESULT        :142
         *     END-IF
         *     IF  APPL-AOK                                 :144
         *         CONTINUE                                 :145
         *     ELSE
         *         DISPLAY 'ERROR CLOSING CARDFILE'         :147
         *         MOVE CARDFILE-STATUS TO IO-STATUS        :148
         *         PERFORM 9910-DISPLAY-IO-STATUS           :149
         *         PERFORM 9999-ABEND-PROGRAM               :150
         *     END-IF
         * </pre>
         *
         * <p>Worth noticing how differently this paragraph spells the same arithmetic the open
         * paragraph performs with plain {@code MOVE}s. {@code ADD 8 TO ZERO GIVING} sets the field to
         * eight; {@code SUBTRACT APPL-RESULT FROM APPL-RESULT} subtracts the field from itself and so
         * always leaves zero; {@code ADD 12 TO ZERO GIVING} sets it to twelve. The results are
         * identical to {@code MOVE 8}, {@code MOVE 0} and {@code MOVE 12}, and the verbs are
         * transcribed as written rather than simplified, because the point of reading this method is to
         * be able to check it against the paragraph.
         *
         * @throws AbendException if the close reports anything but {@link FileStatus#OK}
         */
        private void closeCardfile() {
            // :137  ADD 8 TO ZERO GIVING APPL-RESULT
            applResult = APPL_RESULT_ASSUMED_FAILURE;

            cardfileStatus = closeCardfileBrowse();                           // :138

            if (FileStatus.OK.equals(cardfileStatus)) {                       // :139
                // :140  SUBTRACT APPL-RESULT FROM APPL-RESULT - the field less itself, so zero.
                applResult -= applResult;
            } else {
                // :142  ADD 12 TO ZERO GIVING APPL-RESULT
                applResult = APPL_RESULT_FATAL;
            }

            if (applResult == FileStatus.APPL_AOK) {                          // :144  IF APPL-AOK
                return;                                                       // :145  CONTINUE
            }
            throw reportFileErrorAndAbend(CLOSE_ERROR_TEXT);                  // :147-:150
        }

        /**
         * {@code CLOSE CARDFILE-FILE} - {@code app/cbl/CBACT02C.cbl:138}.
         *
         * <p>Ending the browse releases the handle so that a read after it is refused rather than
         * silently resuming. As with the open, a failure is reported as a status rather than
         * propagated, because {@code CLOSE} has a {@code FILE STATUS} clause and no exception.
         *
         * @return {@link FileStatus#OK} when the file closed, or {@link #PERMANENT_ERROR_STATUS} when
         *         it did not
         */
        private String closeCardfileBrowse() {
            closeIssued = true;
            try {
                cardfile.endBrowse();
                return FileStatus.OK;
            } catch (RuntimeException refused) {
                datasetRefusal = refused;
                return PERMANENT_ERROR_STATUS;
            }
        }

        // -------------------------------------------------------------------------------------------
        // 9910-DISPLAY-IO-STATUS and 9999-ABEND-PROGRAM - app/cbl/CBACT02C.cbl:154-174.
        // -------------------------------------------------------------------------------------------

        /**
         * The three-statement error arm the open, read and close paragraphs share, followed by the
         * abend.
         *
         * <p>All three arms are written identically in the source apart from their text -
         * {@code DISPLAY '<em>text</em>'}, {@code MOVE CARDFILE-STATUS TO IO-STATUS},
         * {@code PERFORM 9910-DISPLAY-IO-STATUS}, {@code PERFORM 9999-ABEND-PROGRAM} - so they are
         * written once here and the text is the parameter. The order matters and is preserved: the
         * error text, then the file-status line, then the abend text.
         *
         * <p>Returns the exception rather than throwing it, so that each call site reads
         * {@code throw reportFileErrorAndAbend(...)} and the compiler can see that control does not
         * continue past the arm - which is exactly what {@code PERFORM 9999-ABEND-PROGRAM} means.
         *
         * @param errorText the paragraph's own {@code DISPLAY} literal
         * @return the abend to throw; never {@code null}
         */
        private AbendException reportFileErrorAndAbend(String errorText) {
            sysout.write(errorText);                                          // DISPLAY '<text>'
            ioStatus = cardfileStatus;                                        // MOVE ... TO IO-STATUS
            displayIoStatus();                                                // PERFORM 9910
            return abendProgram(errorText);                                   // PERFORM 9999
        }

        /**
         * {@code 9910-DISPLAY-IO-STATUS} - {@code app/cbl/CBACT02C.cbl:161-174}.
         *
         * <p>Not reimplemented here. The paragraph's whole body - the {@code NOT NUMERIC} class
         * condition, the {@code IO-STAT1 = '9'} test, the binary feedback byte rendered as three
         * digits, the {@code '0000'} template with the status overlaid at position three, and the
         * {@code 'FILE STATUS IS: NNNN'} literal whose trailing {@code NNNN} really is part of the
         * literal - belongs to {@link FileStatus#toDisplayLine(String)}, which sixteen emission sites
         * across the estate share. One implementation is what makes all sixteen byte-identical.
         */
        private void displayIoStatus() {
            sysout.write(FileStatus.toDisplayLine(ioStatus));                 // :168 / :172
        }

        /**
         * {@code 9999-ABEND-PROGRAM} - {@code app/cbl/CBACT02C.cbl:154-158}.
         *
         * <pre>
         *     DISPLAY 'ABENDING PROGRAM'                   :155
         *     MOVE 0 TO TIMING                             :156
         *     MOVE 999 TO ABCODE                           :157
         *     CALL 'CEE3ABD'.                              :158
         * </pre>
         *
         * <p>The two {@code MOVE}s are the two arguments the Language Environment abend service reads,
         * and the abend type supplies both from its own constants - {@code ABCODE} 999 and
         * {@code TIMING} 0 - so neither is restated here. What this method must get right is the
         * {@code RETURN-CODE}: {@link #applResult} is carried through unchanged, never re-derived, so
         * the value the guard chain put there is the value the process exits with and a following JCL
         * step's {@code COND} test still reads what it read on the mainframe.
         *
         * @param reason the failing operation's {@code DISPLAY} text, which the abend carries as detail
         * @return the abend; never {@code null}
         */
        private AbendException abendProgram(String reason) {
            sysout.write(AbendException.ABEND_DISPLAY_TEXT);                  // :155
            return AbendException.standard(PROGRAM_ID, applResult,            // :156-:158
                    reason + " " + FileStatus.toDisplayLine(ioStatus), datasetRefusal);
        }
    }

    // =================================================================================================
    // SYSOUT. //SYSOUT DD SYSOUT=* - app/jcl/READCARD.jcl:27.
    // =================================================================================================

    /**
     * The sink this job writes its {@code DISPLAY} output to: an application-supplied one where the
     * context publishes it, and {@link #standardOutputSysoutSink()} otherwise.
     *
     * @return the sink to use for the next run; never {@code null}
     */
    public SysoutSink sysoutSink() {
        return sysoutSinkProvider.getIfAvailable(AccountBalanceReaderJob::standardOutputSysoutSink);
    }

    /**
     * The default sink: the job's own standard output stream, written verbatim.
     *
     * <p>{@code app/jcl/READCARD.jcl:27} binds {@code //SYSOUT DD SYSOUT=*}, which on the mainframe is
     * the job's print stream. The standard output stream is its direct equivalent, and the stream is
     * handed to the adapter as a value rather than reached statically at each emission point, so every
     * {@code DISPLAY} in this class goes through the one seam and a test can replace all of them at
     * once.
     *
     * <p><strong>Undecorated, and that is the whole point.</strong> Nothing prefixes a timestamp, a
     * severity or a logger name onto these lines. A parity case compares the emitted sequence line for
     * line against what the COBOL writes, so any decoration would fail every case while the
     * translation underneath was correct. This is also why the module's logger is not used for
     * {@code DISPLAY} output: it is for diagnostics about the run, not for the run's own output.
     *
     * @return a sink over the standard output stream; never {@code null}
     */
    public static SysoutSink standardOutputSysoutSink() {
        return new PrintStreamSysoutSink(System.out);
    }

    /**
     * Where a line of {@code DISPLAY} output goes.
     *
     * <p>The seam that lets a parity case capture this job's output exactly. An implementation
     * <strong>must preserve call order</strong> and must not buffer in a way that could reorder or
     * coalesce lines: the sequence of lines is what a parity case compares, and a job that emits the
     * right lines in the wrong order has not reproduced the program.
     *
     * <p>The single method returns {@code void}, deliberately. COBOL's {@code DISPLAY} declares no
     * {@code FILE STATUS} and reports no outcome, so {@code CBACT02C} never tests whether one
     * succeeded. Returning a status here would invite a caller to branch on something the program
     * cannot see, and that would be a behaviour change dressed as diligence.
     */
    @FunctionalInterface
    public interface SysoutSink {

        /**
         * Accepts one line of output.
         *
         * @param line the line exactly as {@code DISPLAY} writes it, with no line terminator of its
         *             own and no decoration of any kind; never {@code null}
         */
        void write(String line);
    }

    /**
     * A {@link SysoutSink} over a {@link PrintStream}.
     *
     * <p>The default's implementation, and a named type rather than a lambda so that the one place
     * this module's batch output reaches a stream is visible and directly testable. Order is preserved
     * because a print stream preserves it.
     *
     * @param stream the stream to write to, one line per call; never {@code null}
     */
    public record PrintStreamSysoutSink(PrintStream stream) implements SysoutSink {

        /**
         * Rejects a missing stream.
         *
         * @throws NullPointerException if {@code stream} is {@code null}
         */
        public PrintStreamSysoutSink {
            Objects.requireNonNull(stream, "A print stream is required to emit SYSOUT lines");
        }

        /**
         * Writes the line and terminates it, which is what one {@code DISPLAY} produces.
         *
         * @param line the line; never {@code null}
         * @throws NullPointerException if {@code line} is {@code null}
         */
        @Override
        public void write(String line) {
            Objects.requireNonNull(line, "A DISPLAY never writes an absent line");
            stream.println(line);
        }
    }
}
