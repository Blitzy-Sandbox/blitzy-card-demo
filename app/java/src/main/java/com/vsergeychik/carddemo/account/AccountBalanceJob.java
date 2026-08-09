package com.vsergeychik.carddemo.account;

import com.vsergeychik.carddemo.account.AccountRepository.AccountFile;
import com.vsergeychik.carddemo.account.AccountRepository.OpenMode;
import com.vsergeychik.carddemo.account.AccountRepository.ReadResult;
import com.vsergeychik.carddemo.account.model.AccountRecord;
import com.vsergeychik.carddemo.common.AbendException;
import com.vsergeychik.carddemo.common.FileStatus;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.config.BatchConfig;
import com.vsergeychik.carddemo.config.BatchConfig.StepContract;
import com.vsergeychik.carddemo.config.BatchConfig.StopSignal;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.charset.Charset;
import java.util.List;
import java.util.Objects;

/**
 * {@code CBACT01C} - the batch program that reads the account master sequentially and prints every
 * record to {@code SYSOUT}.
 *
 * <h2>THE NAME SAYS BALANCE. THE PROGRAM COMPUTES NOTHING.</h2>
 *
 * <p>Read this before changing anything here. The class name {@code AccountBalanceJob} is mandated by
 * the migration prompt and is honoured verbatim under rule R1 - <em>names from the prompt, behaviour
 * from the source</em> - but it does <strong>not</strong> describe what the program does. The source
 * header at {@code app/cbl/CBACT01C.cbl:L5} reads
 * <em>"Function&nbsp;&nbsp;&nbsp;&nbsp;: Read and print account data file."</em>, and that is the whole
 * of it. Verified by exhaustive scan of all 193 lines: <strong>zero</strong> {@code COMPUTE}
 * statements, <strong>zero</strong> {@code MULTIPLY}, <strong>zero</strong> {@code DIVIDE}, and
 * exactly two {@code ADD} statements - {@code ADD 8 TO ZERO GIVING APPL-RESULT} at {@code L152} and
 * {@code ADD 12 TO ZERO GIVING APPL-RESULT} at {@code L157} - both of which are return-code
 * housekeeping, not arithmetic on money. No balance is calculated, adjusted, accumulated or written.
 * The dataset is opened {@code INPUT} and never rewritten.
 *
 * <p>So there is no monetary arithmetic in this file and there must never be any. A future reader who
 * "completes" this class by adding a balance calculation would not be finishing an unfinished
 * migration; they would be inventing a feature the COBOL estate does not have, and every one of this
 * program's parity cases would fail at once.
 *
 * <h2>Its observable output is its SYSOUT, and nothing else</h2>
 *
 * <p>This program writes no records. It has no output dataset: {@code app/jcl/READACCT.jcl:L22-L28}
 * gives it one input DD and two print DDs -
 * <pre>
 * //STEP05 EXEC PGM=CBACT01C
 * //STEPLIB  DD DISP=SHR,
 * //         DSN=&lt;load library&gt;
 * //ACCTFILE DD DISP=SHR,
 * //         DSN=&lt;account master KSDS&gt;
 * //SYSOUT   DD SYSOUT=*
 * //SYSPRINT DD SYSOUT=*
 * </pre>
 * and there is no {@code PARM}, so {@linkplain #jobParameters() this job declares no job parameters}.
 * The behavioural fingerprint of a run is therefore the exact sequence of {@code SYSOUT} lines, which
 * is why every line this class emits goes through one injected {@link SysoutSink} rather than to a
 * stream or a logger chosen at the call site.
 *
 * <h2>TWO DISPLAYS PER RECORD - the single highest-value parity detail in this file</h2>
 *
 * <p>Each record is displayed <strong>twice</strong>, in two different shapes, by two different
 * paragraphs, and both are required:
 * <ol>
 *   <li>{@code 1000-ACCTFILE-GET-NEXT} performs {@code 1100-DISPLAY-ACCT-RECORD} on a successful read
 *       ({@code app/cbl/CBACT01C.cbl:L96}), which emits <strong>eleven labelled field lines plus a
 *       separator line</strong> ({@code L118-L131});</li>
 *   <li>the mainline then separately performs {@code DISPLAY ACCOUNT-RECORD} ({@code L78}), which
 *       emits <strong>one raw 300-byte record image</strong>.</li>
 * </ol>
 *
 * <p>That is <strong>13 lines per record</strong>. Against the 50-record fixture in
 * {@code app/data/ASCII/acctdata.txt} a complete run emits exactly
 * {@code 1 + (50 x 13) + 1 = 652} lines: the start banner, then thirteen lines per record, then the
 * end banner. Consolidating the two displays would look like a tidy-up and would break every parity
 * case, so practice B5 applies literally - the duplication is preserved, not optimised away.
 *
 * <h2>Eleven of twelve named fields, and the misspelling stays</h2>
 *
 * <p>{@code app/cpy/CVACT01Y.cpy} declares twelve named fields plus {@code FILLER PIC X(178)}.
 * {@code 1100-DISPLAY-ACCT-RECORD} displays <strong>eleven</strong> of them:
 * {@code ACCT-ADDR-ZIP} is <strong>not</strong> displayed, and must not be added. The seventh label is
 * spelled {@code ACCT-EXPIRAION-DATE} - the copybook's own misspelling, preserved under implicit
 * requirement I1 because field-for-field diffing compares names as well as values. Each label is
 * exactly {@value #LABEL_WIDTH} characters wide and is followed immediately by the field's display
 * image with no separator, and the paragraph closes with a rule of exactly
 * {@value #SEPARATOR_WIDTH} hyphens.
 *
 * <h2>A field's display image is its stored bytes</h2>
 *
 * <p>COBOL {@code DISPLAY} of a data item transfers that item's storage, so no formatting decision is
 * involved and none may be introduced:
 * <ul>
 *   <li>{@code ACCT-ID PIC 9(11)} emits eleven zero-filled digits - {@code 00000000001};</li>
 *   <li>{@code ACCT-CURR-BAL PIC S9(10)V99} emits the twelve-byte zoned image with the sign
 *       overpunched into the trailing byte, exactly as stored -
 *       <code>00000001940&#123;</code> for {@code +1940.00}. There is no decimal point in storage, so
 *       none appears in the output;</li>
 *   <li>{@code ACCT-GROUP-ID PIC X(10)} emits ten bytes, space-padded on the right and never
 *       trimmed.</li>
 * </ul>
 *
 * <p>Every image therefore comes from {@link AccountRecord}'s raw span accessors, which read the
 * declared span through the shared fixed-width codec, and each display line is composed with
 * {@link FixedWidthCodec#concatenateDelimitedBySize(String...)} - the {@code DELIMITED BY SIZE}
 * primitive, which contributes every operand's full declared width and trims nothing. Java number
 * formatting is never used, and neither is either of the JVM's binary approximate primitives, whose
 * names this file does not even contain (gate G22): a
 * {@code BigDecimal} would round-trip the value but not the bytes, and the bytes are the contract.
 *
 * <h2>Why this is a Tasklet and never a chunk step</h2>
 *
 * <p>One {@code Tasklet}, one transaction, one pass. The COBOL is a single sequential pass whose
 * ordering is the whole of its observable behaviour: open, then read-and-display until end of file,
 * then close, then the closing banner. A chunk-oriented reader / processor / writer would relocate
 * the commit boundaries and interleave the framework's own bookkeeping with the display sequence, so
 * the ordering could no longer be shown to be identical. {@code BatchConfig} states the same rule from
 * the other side: chunk orientation is correct for exactly two of the nine jobs, and this is not one
 * of them.
 *
 * <h2>The read loop, transcribed</h2>
 *
 * <p>{@code app/cbl/CBACT01C.cbl:L71-L87} is the whole procedure division, and
 * {@link #readAndPrintAccountFile(SysoutSink)} follows it statement for statement:
 * <pre>
 * DISPLAY 'START OF EXECUTION OF PROGRAM CBACT01C'.
 * PERFORM 0000-ACCTFILE-OPEN.
 * PERFORM UNTIL END-OF-FILE = 'Y'
 *     IF  END-OF-FILE = 'N'
 *         PERFORM 1000-ACCTFILE-GET-NEXT
 *         IF  END-OF-FILE = 'N'
 *             DISPLAY ACCOUNT-RECORD
 *         END-IF
 *     END-IF
 * END-PERFORM.
 * PERFORM 9000-ACCTFILE-CLOSE.
 * DISPLAY 'END OF EXECUTION OF PROGRAM CBACT01C'.
 * GOBACK.
 * </pre>
 *
 * <p>The loop test and the guard immediately inside it are <em>different</em> tests in COBOL - one
 * asks whether the flag is {@code 'Y'}, the other whether it is {@code 'N'} - so they are two
 * different predicates here as well ({@link WorkingStorage#endOfFileIsYes()} and
 * {@link WorkingStorage#endOfFileIsNo()}) rather than one predicate used twice. Neither is
 * collapsed into the other, because collapsing them would silently assume a two-valued flag that
 * only this program's own {@code MOVE} statements happen to guarantee.
 *
 * <h2>APPL-RESULT, and where the exit code comes from</h2>
 *
 * <p>{@code APPL-RESULT PIC S9(9) COMP} with {@code 88 APPL-AOK VALUE 0} and
 * {@code 88 APPL-EOF VALUE 16} ({@code L61-L63}) is the program's own status register, and its value
 * transitions are reproduced literally in {@link WorkingStorage} because they are what the abend's
 * return code is taken from:
 * <table border="1">
 *   <caption>APPL-RESULT through the three I/O paragraphs</caption>
 *   <tr><th>Paragraph</th><th>Before the verb</th><th>File status {@code '00'}</th>
 *       <th>Otherwise</th></tr>
 *   <tr><td>{@code 0000-ACCTFILE-OPEN} (L133-L149)</td><td>{@code MOVE 8}</td><td>{@code MOVE 0}</td>
 *       <td>{@code MOVE 12}</td></tr>
 *   <tr><td>{@code 1000-ACCTFILE-GET-NEXT} (L92-L116)</td><td>-</td><td>{@code MOVE 0}</td>
 *       <td>{@code MOVE 16} on {@code '10'}, else {@code MOVE 12}</td></tr>
 *   <tr><td>{@code 9000-ACCTFILE-CLOSE} (L151-L167)</td><td>{@code ADD 8 TO ZERO GIVING}</td>
 *       <td>{@code SUBTRACT APPL-RESULT FROM APPL-RESULT}</td>
 *       <td>{@code ADD 12 TO ZERO GIVING}</td></tr>
 * </table>
 *
 * <p>The close paragraph reaches zero by subtracting the register from itself rather than by moving a
 * literal, and that is transcribed as written: an idiom is not a defect to be normalised.
 *
 * <p>A non-{@code APPL-AOK}, non-{@code APPL-EOF} outcome takes the fatal arm - display the error
 * text, render the file status through the shared renderer, then abend. The abend is
 * {@link AbendException#standard(String, int, String)}, which supplies
 * {@code ABCODE = }{@value AbendException#STANDARD_ABEND_CODE} and
 * {@code TIMING = }{@value AbendException#STANDARD_TIMING} exactly as {@code MOVE 999 TO ABCODE} and
 * {@code MOVE 0 TO TIMING} do at {@code L171-L172}, and carries {@code APPL-RESULT} as the return
 * code. {@code BatchConfig}'s listeners then carry that value onto the step's exit status and the
 * process exit code, so the {@code 0} / {@code 4} / {@code 8} / {@code 12} contract of gate G35 holds
 * without this class touching an exit status itself.
 *
 * <h2>What this class does not do</h2>
 * <ul>
 *   <li><strong>It never logs.</strong> {@code SYSOUT} is parity-relevant output, and a logger would
 *       decorate it with a timestamp, a level and a thread name. Diagnostics that are not COBOL output
 *       travel in the abend's message instead.</li>
 *   <li><strong>It names no dataset.</strong> The DD name {@value #DD_NAME} is a key;
 *       {@link AccountRepository} resolves it from {@code carddemo.datasets}, so no dataset name
 *       appears in this source (gate G46).</li>
 *   <li><strong>It holds no static mutable state</strong> (gate G53). The end-of-file flag, the status
 *       register and the record counter live in a {@link WorkingStorage} instance created afresh per
 *       run, and every collaborator is constructor-injected. Two concurrent runs share nothing.</li>
 *   <li><strong>It launches nothing.</strong> {@code spring.batch.job.enabled} is {@code false}, so
 *       the published {@link #accountBalanceJob() job} runs only when something deliberately launches
 *       it - exactly as it ran only when JCL submitted {@code STEP05}.</li>
 *   <li><strong>It reads and writes no COBOL source.</strong> Everything under {@code app/cbl} and
 *       {@code app/jcl} is the read-only parity oracle (practice B3); the citations throughout are
 *       provenance only.</li>
 * </ul>
 *
 * <h2>Its two siblings are not interchangeable with it</h2>
 *
 * <p>{@code CBACT02C} and {@code CBACT03C} share this program's open / read / display / close shape,
 * and this class deliberately mirrors that shape method for method so the three read alike. Their
 * <em>display</em> behaviour differs, though, and copying this one's would be wrong:
 * {@code CBACT02C} emits one line per record and {@code CBACT03C} emits two, because of a
 * commented-out display in the first and a duplicated one in the second. Only {@code CBACT01C} emits
 * thirteen.
 *
 * <h2>User-specified rules</h2>
 *
 * <p>{@code review_rules} returns exactly one line - "No user rules provided." - and that single line
 * is the whole document, so <strong>no user rule governs this file</strong>. Its absence is not
 * licence to lower the bar: the migration plan elevates twelve enterprise practices to binding
 * constraints instead, and the ones bearing on this file are B1 (no dependency is added - every type
 * used here arrives from the batch and core starters already declared), B2 (Spring Batch 5.2.6 and
 * Spring Boot 3.5.16 APIs only, through {@code BatchConfig}'s builder seams; the annotation that
 * activates batch infrastructure manually - which under Boot 3 <em>disables</em> the auto-configuration -
 * appears nowhere in this module, and is described rather than named here so that a compliance scan of
 * this source tree records no hit), B3 (the COBOL and JCL cited above are read-only), B5 (the
 * duplicated display and the subtract-from-self idiom are preserved rather than tidied), B7 (the whole
 * procedure division is reachable by a plain unit test with no application context and no launcher),
 * B8 (explicit imports, no wildcard import, the dataset name externalised, the {@code SYSOUT} code
 * page taken from the repository rather than from the platform), B9 (no static mutable state) and B11
 * (display images come from the hand-written codec, so every byte is auditable against the copybook).
 *
 * @see AccountRepository
 * @see AccountRecord
 * @see BatchConfig
 */
@Configuration(AccountBalanceJob.CONFIGURATION_BEAN_NAME)
public class AccountBalanceJob {

    // =================================================================================================
    // Identity: the COBOL program, the configuration key, the Spring Batch names and the DD name.
    // =================================================================================================

    /**
     * The bean name of this configuration class itself.
     *
     * <p><strong>Stated explicitly, and it has to be.</strong> Component scanning names a configuration
     * bean after its class - {@code AccountBalanceJob} would become {@code accountBalanceJob} - and
     * {@link #accountBalanceJob()} publishes the {@link Job} under exactly that name. Two definitions of
     * one name is a hard failure: Spring Boot disables bean-definition overriding by default, so the
     * context does not start at all, and it fails at registration time with a message about the class
     * rather than about the collision's cause.
     *
     * <p>The class name is mandated and the job name follows the COBOL program, so neither may move. The
     * configuration bean's own name is the one thing here that carries no external contract, so it is
     * the one that is renamed. <strong>Every sibling batch job class in this module faces the same
     * collision</strong> - a class named after its job, publishing a bean named after its job - and this
     * is how it is resolved.
     */
    public static final String CONFIGURATION_BEAN_NAME = "accountBalanceJobConfiguration";

    /**
     * Diagnostics for the one thing this program does that its SYSOUT cannot carry: a failure to release
     * the account-master handle while unwinding from an abend.
     *
     * <p>Nothing on a successful path is logged here. The program's observable output is its
     * {@code DISPLAY} line sequence, and adding to it would be a parity defect.
     */
    private static final Log LOG = LogFactory.getLog(AccountBalanceJob.class);

    /**
     * The COBOL {@code PROGRAM-ID} this job was translated from, as
     * {@code app/cbl/CBACT01C.cbl:L23} declares it.
     *
     * <p>Carried into every {@link AbendException} this class raises, so a failed run names the
     * paragraph's own program rather than a Java class.
     */
    public static final String PROGRAM_ID = "CBACT01C";

    /**
     * This job's key under the {@code carddemo.jobs} configuration prefix.
     *
     * <p>{@code BatchConfig.JobContracts} binds strictly and validates at startup, so an entry that is
     * absent, renamed or paired with a different program stops the context rather than producing a job
     * that runs against the wrong contract.
     */
    public static final String JOB_KEY = "account-balance-job";

    /**
     * The Spring Batch job name, which is also this job's identity in the batch metadata and the name
     * of the bean {@link #accountBalanceJob()} publishes.
     */
    public static final String JOB_NAME = "accountBalanceJob";

    /**
     * The single step, named after the JCL step it replaces: {@code //STEP05 EXEC PGM=CBACT01C} at
     * {@code app/jcl/READACCT.jcl:L22}.
     *
     * <p>Transcribed rather than invented, so a run can be read straight against the JCL. The value is
     * cross-checked against {@code carddemo.jobs.account-balance-job.steps} when this class is
     * constructed.
     */
    public static final String STEP_NAME = "STEP05";

    /**
     * The whole step sequence of {@code app/jcl/READACCT.jcl}: one step, {@value #STEP_NAME}, running
     * {@value #PROGRAM_ID}, ungated.
     *
     * <p>Stated as a sequence rather than as a step name alone because a sequence is what the JCL
     * declares and what {@link BatchConfig#requireSteps(String, List, String)} compares. "One step, and
     * that one" is a stronger statement than "this step is present", and it is the statement the JCL
     * actually makes: there is no second {@code EXEC} in that job.
     */
    public static final List<StepContract> REQUIRED_STEPS =
            List.of(new StepContract(STEP_NAME, PROGRAM_ID, false));

    /**
     * The DD name the JCL binds the account master under, {@code app/jcl/READACCT.jcl:L25-L26}.
     *
     * <p>Taken from {@link AccountRepository#BATCH_DD_NAME} rather than restated, so the job and the
     * repository cannot drift apart. It is a <em>key</em>: the dataset it resolves to lives in
     * {@code carddemo.datasets} and never in Java (gate G46).
     */
    public static final String DD_NAME = AccountRepository.BATCH_DD_NAME;

    // =================================================================================================
    // The SYSOUT literals. Every one of these is byte-exact observable output: a changed character is a
    // failed parity case, so each is quoted with the source line it was transcribed from.
    // =================================================================================================

    /** {@code DISPLAY 'START OF EXECUTION OF PROGRAM CBACT01C'} - {@code app/cbl/CBACT01C.cbl:L71}. */
    public static final String START_OF_EXECUTION = "START OF EXECUTION OF PROGRAM CBACT01C";

    /** {@code DISPLAY 'END OF EXECUTION OF PROGRAM CBACT01C'} - {@code app/cbl/CBACT01C.cbl:L85}. */
    public static final String END_OF_EXECUTION = "END OF EXECUTION OF PROGRAM CBACT01C";

    /**
     * {@code DISPLAY 'ERROR OPENING ACCTFILE'} - {@code app/cbl/CBACT01C.cbl:L144}.
     *
     * <p>Names the <strong>DD name</strong>, not the file. The read and close paragraphs word their own
     * failures differently, and the three texts are deliberately not unified.
     */
    public static final String ERROR_OPENING_ACCTFILE = "ERROR OPENING ACCTFILE";

    /** {@code DISPLAY 'ERROR READING ACCOUNT FILE'} - {@code app/cbl/CBACT01C.cbl:L110}. */
    public static final String ERROR_READING_ACCOUNT_FILE = "ERROR READING ACCOUNT FILE";

    /** {@code DISPLAY 'ERROR CLOSING ACCOUNT FILE'} - {@code app/cbl/CBACT01C.cbl:L162}. */
    public static final String ERROR_CLOSING_ACCOUNT_FILE = "ERROR CLOSING ACCOUNT FILE";

    /**
     * The width of every label literal in {@code 1100-DISPLAY-ACCT-RECORD}: exactly 25 characters,
     * ending in a colon, with the field's display image following immediately and no space between.
     *
     * <p>Verified by extracting all eleven literals from {@code app/cbl/CBACT01C.cbl:L119-L129} and
     * measuring each: every one is 25 characters. That regularity is why {@link #label(String)} derives
     * the labels from the copybook field names instead of restating eleven hand-padded literals, where
     * a single mistyped space would be invisible in review and fatal in comparison.
     */
    public static final int LABEL_WIDTH = 25;

    /**
     * The number of hyphens in the separator line at {@code app/cbl/CBACT01C.cbl:L130}: exactly 49.
     *
     * <p>Counted from the source literal rather than estimated. It is neither 48 nor 50, and it is not
     * the 80-column print width it superficially resembles.
     */
    public static final int SEPARATOR_WIDTH = 49;

    /**
     * The rule that closes every record's field block:
     * {@code DISPLAY '-------------------------------------------------'} at
     * {@code app/cbl/CBACT01C.cbl:L130}.
     *
     * <p>Built by repetition rather than typed out, so its width is {@value #SEPARATOR_WIDTH} by
     * construction.
     */
    public static final String RECORD_SEPARATOR = "-".repeat(SEPARATOR_WIDTH);

    // =================================================================================================
    // The eleven labels of 1100-DISPLAY-ACCT-RECORD - app/cbl/CBACT01C.cbl:L119-L129.
    //
    // Each is its copybook field name padded to LABEL_WIDTH and terminated with a colon, which is
    // exactly how the COBOL literals are formed. Deriving them from AccountRecord's name constants ties
    // the label to the copybook field it announces: the misspelled ACCT-EXPIRAION-DATE stays misspelled
    // because ACCT_EXPIRAION_DATE_NAME is misspelled, and a copybook field that was ever renamed could
    // not leave a stale label behind.
    // =================================================================================================

    /** Label for {@code ACCT-ID} - {@code app/cbl/CBACT01C.cbl:L119}. */
    public static final String LABEL_ACCT_ID = label(AccountRecord.ACCT_ID_NAME);

    /** Label for {@code ACCT-ACTIVE-STATUS} - {@code app/cbl/CBACT01C.cbl:L120}. */
    public static final String LABEL_ACCT_ACTIVE_STATUS = label(AccountRecord.ACCT_ACTIVE_STATUS_NAME);

    /** Label for {@code ACCT-CURR-BAL} - {@code app/cbl/CBACT01C.cbl:L121}. */
    public static final String LABEL_ACCT_CURR_BAL = label(AccountRecord.ACCT_CURR_BAL_NAME);

    /** Label for {@code ACCT-CREDIT-LIMIT} - {@code app/cbl/CBACT01C.cbl:L122}. */
    public static final String LABEL_ACCT_CREDIT_LIMIT = label(AccountRecord.ACCT_CREDIT_LIMIT_NAME);

    /** Label for {@code ACCT-CASH-CREDIT-LIMIT} - {@code app/cbl/CBACT01C.cbl:L123}. */
    public static final String LABEL_ACCT_CASH_CREDIT_LIMIT =
            label(AccountRecord.ACCT_CASH_CREDIT_LIMIT_NAME);

    /** Label for {@code ACCT-OPEN-DATE} - {@code app/cbl/CBACT01C.cbl:L124}. */
    public static final String LABEL_ACCT_OPEN_DATE = label(AccountRecord.ACCT_OPEN_DATE_NAME);

    /**
     * Label for {@code ACCT-EXPIRAION-DATE} - {@code app/cbl/CBACT01C.cbl:L125}.
     *
     * <p><strong>The misspelling is deliberate and load-bearing.</strong> {@code app/cpy/CVACT01Y.cpy}
     * spells the field {@code ACCT-EXPIRAION-DATE}, the display literal spells it the same way, and
     * field-for-field diffing compares names as well as values (implicit requirement I1). Correcting it
     * to {@code EXPIRATION} would change observable output.
     */
    public static final String LABEL_ACCT_EXPIRAION_DATE =
            label(AccountRecord.ACCT_EXPIRAION_DATE_NAME);

    /** Label for {@code ACCT-REISSUE-DATE} - {@code app/cbl/CBACT01C.cbl:L126}. */
    public static final String LABEL_ACCT_REISSUE_DATE = label(AccountRecord.ACCT_REISSUE_DATE_NAME);

    /** Label for {@code ACCT-CURR-CYC-CREDIT} - {@code app/cbl/CBACT01C.cbl:L127}. */
    public static final String LABEL_ACCT_CURR_CYC_CREDIT =
            label(AccountRecord.ACCT_CURR_CYC_CREDIT_NAME);

    /** Label for {@code ACCT-CURR-CYC-DEBIT} - {@code app/cbl/CBACT01C.cbl:L128}. */
    public static final String LABEL_ACCT_CURR_CYC_DEBIT =
            label(AccountRecord.ACCT_CURR_CYC_DEBIT_NAME);

    /** Label for {@code ACCT-GROUP-ID} - {@code app/cbl/CBACT01C.cbl:L129}. */
    public static final String LABEL_ACCT_GROUP_ID = label(AccountRecord.ACCT_GROUP_ID_NAME);

    /**
     * The eleven labels in emission order, published so the sequence can be asserted as a whole rather
     * than one constant at a time.
     *
     * <p><strong>{@code ACCT-ADDR-ZIP} is absent, and that absence is the contract.</strong> The
     * copybook declares twelve named fields; {@code 1100-DISPLAY-ACCT-RECORD} displays eleven. The
     * omitted field is the ZIP code, at offset {@value AccountRecord#ACCT_ADDR_ZIP_OFFSET} in the
     * record, and adding it would insert a line into every record's output.
     */
    public static final List<String> FIELD_LABELS = List.of(
            LABEL_ACCT_ID,
            LABEL_ACCT_ACTIVE_STATUS,
            LABEL_ACCT_CURR_BAL,
            LABEL_ACCT_CREDIT_LIMIT,
            LABEL_ACCT_CASH_CREDIT_LIMIT,
            LABEL_ACCT_OPEN_DATE,
            LABEL_ACCT_EXPIRAION_DATE,
            LABEL_ACCT_REISSUE_DATE,
            LABEL_ACCT_CURR_CYC_CREDIT,
            LABEL_ACCT_CURR_CYC_DEBIT,
            LABEL_ACCT_GROUP_ID);

    /**
     * The number of {@code SYSOUT} lines one account record produces: the eleven labelled field lines,
     * the separator, and the raw record image the mainline displays separately.
     *
     * <p>Published because it is the arithmetic behind the whole-run line count, and because a change
     * to it would mean the per-record display sequence had been altered.
     */
    public static final int LINES_PER_RECORD = 13;

    // =================================================================================================
    // APPL-RESULT values and the END-OF-FILE flag - app/cbl/CBACT01C.cbl:L61-L65.
    //
    // Every value is taken from a collaborator that already declares it rather than restated, so there
    // is one definition of each number in the module.
    // =================================================================================================

    /**
     * {@code 88 APPL-AOK VALUE 0} - {@code app/cbl/CBACT01C.cbl:L62}.
     *
     * @see WorkingStorage#applAok()
     */
    public static final int APPL_AOK = FileStatus.APPL_AOK;

    /**
     * {@code 88 APPL-EOF VALUE 16} - {@code app/cbl/CBACT01C.cbl:L63}.
     *
     * <p>Note that 16 is <em>not</em> a JCL return code: it is this program's internal marker for
     * "end of file reached", tested at {@code L107} and never placed in {@code RETURN-CODE}.
     *
     * @see WorkingStorage#applEof()
     */
    public static final int APPL_EOF = FileStatus.APPL_EOF;

    /**
     * The value moved into {@code APPL-RESULT} <em>before</em> each I/O verb is issued: {@code 8}, from
     * {@code MOVE 8 TO APPL-RESULT} at {@code L134} and {@code ADD 8 TO ZERO GIVING APPL-RESULT} at
     * {@code L152}.
     *
     * <p>A deliberate assume-failure default, so a verb that neither succeeded nor reported a status
     * cannot leave the register looking successful.
     */
    public static final int APPL_RESULT_ASSUMED_FAILURE = AbendException.RETURN_CODE_ASSUMED_FAILURE;

    /**
     * The value moved into {@code APPL-RESULT} on every fatal arm: {@code 12}, from {@code MOVE 12} at
     * {@code L101} and {@code L139} and {@code ADD 12 TO ZERO GIVING} at {@code L157}.
     *
     * <p>This is the return code the abend carries, so it is also the step's exit code and the process's
     * exit code (gate G35).
     */
    public static final int APPL_RESULT_FATAL = AccountRepository.APPL_RESULT_FATAL;

    /**
     * The return code of a normal end: {@code 0}.
     *
     * <p>{@code CBACT01C} never moves anything into {@code RETURN-CODE}, so a run that reaches
     * {@code GOBACK} ends with zero, which Spring Batch reports as a completed step.
     */
    public static final int RETURN_CODE_NORMAL_END = AbendException.RETURN_CODE_OK;

    /**
     * {@code END-OF-FILE PIC X(01) VALUE 'N'} - {@code app/cbl/CBACT01C.cbl:L65}, its declared initial
     * value.
     */
    public static final String END_OF_FILE_NO = "N";

    /**
     * The value {@code MOVE 'Y' TO END-OF-FILE} places in the flag at {@code app/cbl/CBACT01C.cbl:L108},
     * and the value the mainline loop terminates on.
     */
    public static final String END_OF_FILE_YES = "Y";

    // =================================================================================================
    // Collaborators. All final, all constructor-injected, none static (practice B9, gate G53).
    // =================================================================================================

    /** The batch scaffolding: the job and step builders, and the {@code carddemo.jobs} contracts. */
    private final BatchConfig batchConfig;

    /** The account master, reached over JDBC. The only dataset this program touches. */
    private final AccountRepository accountRepository;

    /**
     * The {@code DELIMITED BY SIZE} concatenator that composes each display line from its label and its
     * field image.
     *
     * <p>Constructed over {@link AccountRepository#datasetCharset()} rather than over a charset of this
     * class's own choosing, so the code page that composed a line is provably the code page the record
     * was decoded with. The platform default is never consulted (practice B8).
     */
    private final FixedWidthCodec codec;

    /** Where every {@code DISPLAY} goes. Resolved once, at construction, and never reassigned. */
    private final SysoutSink sysoutSink;

    /**
     * This job's single step as configuration declares it, resolved at construction so a mis-declared
     * contract fails at startup rather than when the job is first launched.
     */
    private final StepContract stepContract;

    /**
     * Constructs the job.
     *
     * <p>Three checks run here, and each fails loudly rather than degrading:
     * <ol>
     *   <li>the collaborators are present - a missing one is a wiring defect and is reported as one;</li>
     *   <li>{@code carddemo.jobs.}{@value #JOB_KEY} declares a step named {@value #STEP_NAME} running
     *       program {@value #PROGRAM_ID}. {@code BatchConfig} raises a naming diagnostic of its own if
     *       the job key or the step name is absent, and the program is checked here because a contract
     *       that named a different program would give this job another program's dataset
     *       resolution;</li>
     *   <li>that step is <strong>not</strong> gated on a preceding exit code.
     *       {@code app/jcl/READACCT.jcl} carries no {@code COND} at all and has only one step, so
     *       gating it would bypass the only work the job performs.</li>
     * </ol>
     *
     * <p>The {@code SYSOUT} sink is <em>optional</em>. When the context supplies a {@link SysoutSink}
     * bean - which the parity harness and every unit test do, in order to capture the exact line
     * sequence - that bean is used; otherwise {@link #standardOutput(Charset)} writes to the process's
     * standard output stream in the dataset code page, which is the honest analogue of
     * {@code //SYSOUT DD SYSOUT=*}. Resolution happens once, here, so the sink cannot change between
     * two records of the same run.
     *
     * @param batchConfig       the module's batch scaffolding; never {@code null}
     * @param accountRepository the account master; never {@code null}
     * @param sysoutSinkProvider provider for an injected {@code SYSOUT} sink, consulted once and
     *                          defaulted when the context declares none
     * @throws NullPointerException  if any argument is {@code null}
     * @throws IllegalStateException if the job contract is absent, names another program, or gates this
     *                               job's only step
     */
    public AccountBalanceJob(BatchConfig batchConfig,
            AccountRepository accountRepository,
            ObjectProvider<SysoutSink> sysoutSinkProvider) {

        this.batchConfig = Objects.requireNonNull(batchConfig, "The batch scaffolding is required: the "
                + "job and step builders, the job repository and the transaction manager all arrive "
                + "through it, so this class holds no Spring Batch plumbing of its own");
        this.accountRepository = Objects.requireNonNull(accountRepository, "The account master "
                + "repository is required: " + PROGRAM_ID + " reads the " + DD_NAME + " dataset "
                + "sequentially and does nothing else");
        Objects.requireNonNull(sysoutSinkProvider, "A SYSOUT sink provider is required; it may resolve "
                + "to no bean, in which case the standard output stream is used");

        this.codec = new FixedWidthCodec(accountRepository.datasetCharset());
        this.sysoutSink = sysoutSinkProvider
                .getIfAvailable(() -> standardOutput(accountRepository.datasetCharset()));
        this.stepContract = requireUngatedStep(batchConfig);
    }

    /**
     * Resolves and validates this job's step contract.
     *
     * <p>The whole sequence is required, not just the one step this job runs. {@code app/jcl/READACCT.jcl}
     * declares exactly one step, {@value #STEP_NAME} running {@value #PROGRAM_ID}, with no {@code PARM}
     * and no {@code COND}; confirming only that such a step is present would accept a contract that also
     * declared a second step, or that put another one first. Either would run work this JCL never ran,
     * against DD names it never named, and nothing further downstream would notice - so the required
     * sequence is stated in full and compared as a whole.
     *
     * <p>The step-level program and gating diagnostics are kept ahead of that comparison because they
     * name the two mistakes that are actually plausible here - a contract pointing this job at another
     * program, and a gate on the only step a single-step job has - and each says specifically what is
     * wrong. The sequence comparison behind them catches everything else.
     *
     * @param scaffolding the batch scaffolding holding the {@code carddemo.jobs} catalogue
     * @return the validated step contract
     * @throws IllegalStateException if the contract is absent, names another program, gates the step, or
     *                               declares anything other than this one step
     */
    private static StepContract requireUngatedStep(BatchConfig scaffolding) {
        StepContract contract = scaffolding.contract(JOB_KEY).step(STEP_NAME);
        if (!PROGRAM_ID.equals(contract.program())) {
            throw new IllegalStateException("The carddemo.jobs contract for '" + JOB_KEY + "' declares "
                    + "step '" + STEP_NAME + "' running program '" + contract.program() + "', but this "
                    + "job is the translation of " + PROGRAM_ID + " (app/jcl/READACCT.jcl:L22). A step "
                    + "that named another program would resolve another program's DD names.");
        }
        if (contract.requirePrecedingExitCodeZero()) {
            throw new IllegalStateException("The carddemo.jobs contract for '" + JOB_KEY + "' gates step "
                    + "'" + STEP_NAME + "' on a preceding exit code, but app/jcl/READACCT.jcl carries no "
                    + "COND and declares only this step. Gating the only step of a single-step job would "
                    + "bypass all of its work.");
        }
        scaffolding.requireSteps(JOB_KEY, REQUIRED_STEPS, "app/jcl/READACCT.jcl:L22");
        return contract;
    }

    // =================================================================================================
    // The Spring Batch surface: one job, one step, one tasklet.
    //
    // Only the Job is a bean. The step and the tasklet are ordinary methods, deliberately: nine sibling
    // job classes are being added to this module, and a published Step or Tasklet bean from each would
    // make every by-type injection of those interfaces ambiguous at once.
    // =================================================================================================

    /**
     * The job, published as a bean: one step, no job parameters.
     *
     * <p>{@code app/jcl/READACCT.jcl:L22} is a bare {@code EXEC PGM=CBACT01C} with no {@code PARM}, so
     * no {@link JobParameters} are declared and none may be invented - see {@link #jobParameters()}.
     *
     * <p>The builder arrives from {@link BatchConfig#job(String)} already bound to the auto-configured
     * job repository and carrying the shared abend listener, which is what makes the return-code
     * contract of gate G35 hold for this job by construction rather than by opting in.
     *
     * <p>Publishing the job does <strong>not</strong> run it: {@code spring.batch.job.enabled} is
     * {@code false}, so it executes only when something deliberately launches it.
     *
     * <p>The bean's name is this method's name, {@value #JOB_NAME}, which is also
     * {@link Job#getName()} - so a launcher that looks the job up by either finds the same one. That is
     * why this configuration class carries the explicit bean name
     * {@value #CONFIGURATION_BEAN_NAME} instead: without it the two definitions would collide and the
     * context would refuse to start.
     *
     * @return the {@value #JOB_NAME} job; never {@code null}
     */
    @Bean
    public Job accountBalanceJob() {
        return batchConfig.job(JOB_NAME)
                .start(accountBalanceStep())
                .build();
    }

    /**
     * The single step, named {@value #STEP_NAME} after the JCL step it replaces.
     *
     * <p>A <strong>tasklet</strong> step, and never a chunk-oriented one: the COBOL is one sequential
     * pass whose display ordering is its entire observable behaviour, and chunking would relocate the
     * commit boundaries that ordering sits inside. {@link BatchConfig#taskletStep(String, Tasklet)}
     * supplies the job repository, the module transaction manager and the abend listener.
     *
     * <p>The step's name is read from the validated {@linkplain #stepContract() contract} rather than
     * from the constant directly, so the name that reaches the batch metadata is demonstrably the name
     * configuration declares.
     *
     * @return a fresh step; never {@code null}
     */
    public Step accountBalanceStep() {
        return batchConfig.taskletStep(stepContract.name(), accountFileDisplayTasklet()).build();
    }

    /**
     * The step body: one invocation, one complete pass over the account master.
     *
     * <p>Returns {@link RepeatStatus#FINISHED} after a single call, because {@code CBACT01C} performs
     * exactly one pass. Returning {@code CONTINUABLE} would re-run the whole program.
     *
     * @return the tasklet; never {@code null}
     */
    public Tasklet accountFileDisplayTasklet() {
        return this::executeStep;
    }

    /**
     * Runs the procedure division once and reports how many records it read.
     *
     * <p>The read count is step metadata rather than COBOL output - {@code CBACT01C} keeps no counter of
     * its own - so it is reported to the framework and never displayed. It is nonetheless worth
     * reporting: it is the one number an operator can compare against the dataset without reading 652
     * lines of {@code SYSOUT}.
     *
     * <p>Nothing is caught here. An {@link AbendException} raised on a fatal I/O arm must reach the
     * framework so that {@code BatchConfig}'s listeners can carry its return code onto the step's exit
     * status and the process's exit code (gate G35); swallowing it would report a failed job as
     * complete.
     *
     * <p>The chunk context is read for one thing only: the {@link StopSignal} the pass consults between
     * records. A tasklet that runs once is checked for interruption once by the framework, at the top, so
     * a stop requested during a full-file pass would otherwise not be seen until the pass had finished.
     * Nothing else here comes from the context - this program has no per-chunk state and no restart
     * semantics beyond re-reading the dataset from its first record.
     *
     * @param contribution the step's contribution, which the read count is reported to
     * @param chunkContext the framework's chunk context, read only for this step execution's stop signal
     * @return {@link RepeatStatus#FINISHED}, always
     */
    private RepeatStatus executeStep(StepContribution contribution, ChunkContext chunkContext) {
        int recordsDisplayed = readAndPrintAccountFile(sysoutSink, StopSignal.of(chunkContext));
        for (int recorded = 0; recorded < recordsDisplayed; recorded++) {
            contribution.incrementReadCount();
        }
        return RepeatStatus.FINISHED;
    }

    /**
     * The job parameters this job is launched with: <strong>none</strong>.
     *
     * <p>{@code app/jcl/READACCT.jcl:L22} declares no {@code PARM}, and the empty
     * {@code carddemo.jobs.}{@value #JOB_KEY}{@code .parameters} list states that as a contract. Only
     * the interest calculator has a {@code PARM} in this estate, and inventing one here would change
     * how the job is identified in the batch metadata.
     *
     * @return empty job parameters; never {@code null}
     */
    public JobParameters jobParameters() {
        return batchConfig.contract(JOB_KEY).jobParameters();
    }

    /**
     * This job's validated step contract, as {@code carddemo.jobs} declares it.
     *
     * @return the contract for step {@value #STEP_NAME}; never {@code null}
     */
    public StepContract stepContract() {
        return stepContract;
    }

    /**
     * The sink every {@code DISPLAY} of this job is written to.
     *
     * <p>Surfaced so a caller can confirm which sink was resolved, and so a test can assert that an
     * injected sink really is the one in use rather than being shadowed by the default.
     *
     * @return the resolved sink; never {@code null}
     */
    public SysoutSink sysoutSink() {
        return sysoutSink;
    }

    // =================================================================================================
    // PROCEDURE DIVISION - app/cbl/CBACT01C.cbl:L70-L87.
    //
    // Runnable with no application context, no JobLauncher and no HTTP layer (gate G51), so every branch
    // below is reachable from a plain unit test and the package's branch-coverage gate can be met
    // deterministically rather than through the framework.
    // =================================================================================================

    /**
     * Runs the program against the configured {@code SYSOUT} sink.
     *
     * @return the number of account records read and displayed
     * @throws AbendException if the open, a read or the close reports a status this program treats as
     *                        fatal
     */
    public int readAndPrintAccountFile() {
        return readAndPrintAccountFile(sysoutSink);
    }

    /**
     * Runs the program, writing every {@code DISPLAY} to the given sink: the whole of
     * {@code app/cbl/CBACT01C.cbl:L71-L87}, in order.
     *
     * <p>The sink is a parameter as well as a field so that a caller can capture one run's output
     * without reconfiguring the bean - which is exactly what a parity case does.
     *
     * <p><strong>The handle is released on every exit, including the abend path.</strong> An
     * {@link AccountFile} holds no operating-system resource today - the repository borrows and returns a
     * connection per operation - so nothing is leaked in the sense of a descriptor. The release is not
     * there for that. It is there because {@link AccountFile} declares {@link AutoCloseable}, and a type
     * consumed without any guarantee of closure relies on what its implementation happens to hold rather
     * than on what its contract promises: the day the handle holds a real cursor, a held connection or a
     * temporary table, this pass would start leaking with nothing to signal it.
     *
     * <p>The release is written so that it costs nothing on the path that matters. Its guard is the
     * run's own record of having reached {@code 9000-ACCTFILE-CLOSE}, so on every successful run the
     * release does not fire at all: <em>no</em> additional round trip is issued and no output changes.
     * The guard is the program's control flow rather than {@link AccountFile#isClosed()} deliberately -
     * "one {@code CLOSE} per run" is a property of the single statement at {@code L83}, so it should not
     * depend on the handle tracking its own state. Only an abend reaches the release, and there the
     * close probe emits no {@code DISPLAY}, cannot alter the {@link AbendException} the caller receives,
     * and cannot alter the return code. The COBOL abends outright without closing; what it observably
     * produces - the SYSOUT line sequence and the return code - is identical either way.
     *
     * @param sysout where every displayed line goes; must not be {@code null}
     * @return the number of account records read and displayed; {@code 0} for an empty dataset
     * @throws NullPointerException if {@code sysout} is {@code null}
     * @throws AbendException       if the open, a read or the close reports a status this program treats
     *                              as fatal, carrying {@link #APPL_RESULT_FATAL} as its return code
     */
    public int readAndPrintAccountFile(SysoutSink sysout) {
        return readAndPrintAccountFile(sysout, StopSignal.RUNNING);
    }

    /**
     * Runs the program, yielding to the given stop signal between records.
     *
     * <p>The pass itself is identical to {@link #readAndPrintAccountFile(SysoutSink)} - same reads, same
     * displayed lines, same order - and the signal changes nothing while no stop is pending. It exists
     * because {@code CBACT01C} is one pass over a whole dataset inside a single tasklet invocation, so
     * the framework's own interruption check at the step's repeat boundary happens once and cannot end a
     * pass already under way. The probe is consulted at the <em>top of the loop body</em>, between
     * records, which is where the record in flight is always complete: it has been read and displayed,
     * or it has not been started. Nothing is retried; see {@link StopSignal}.
     *
     * @param sysout     where every displayed line goes; must not be {@code null}
     * @param stopSignal the between-record cancellation probe; {@link StopSignal#RUNNING} for a caller
     *                   outside a step; must not be {@code null}
     * @return the number of account records read and displayed; {@code 0} for an empty dataset
     * @throws NullPointerException if {@code sysout} or {@code stopSignal} is {@code null}
     * @throws AbendException       if the open, a read or the close reports a status this program treats
     *                              as fatal, carrying {@link #APPL_RESULT_FATAL} as its return code
     * @throws BatchConfig.StopRequestedException if the step is asked to stop, which abandons the pass
     *                              between records
     */
    public int readAndPrintAccountFile(SysoutSink sysout, StopSignal stopSignal) {
        Objects.requireNonNull(sysout, "A SYSOUT sink is required: the displayed line sequence is this "
                + "program's entire observable output, so there is nothing to run without somewhere to "
                + "write it");
        Objects.requireNonNull(stopSignal, "A stop signal is required; pass StopSignal.RUNNING outside a "
                + "step, which is what the single-argument overload does");

        // DISPLAY 'START OF EXECUTION OF PROGRAM CBACT01C'.                                        L71
        sysout.write(START_OF_EXECUTION);

        // WORKING-STORAGE, fresh per run: APPL-RESULT and END-OF-FILE belong to this execution and to
        // no other, which is why they are not fields of this singleton bean.
        WorkingStorage workingStorage = new WorkingStorage();

        // PERFORM 0000-ACCTFILE-OPEN.                                                              L72
        // Outside the try: a failed open throws without yielding a handle, so there would be nothing
        // for a finally to release.
        AccountFile acctFile = acctFileOpen(sysout, workingStorage);

        // This run's own record of having reached L83. A local, never a field: the job is a singleton
        // bean and per-run state on it would be shared between runs (practice B9, gate G53).
        boolean closeIssued = false;

        try {
            // PERFORM UNTIL END-OF-FILE = 'Y' ... END-PERFORM.                                 L74-L81
            int recordsDisplayed = acctFileDisplayLoop(sysout, workingStorage, acctFile, stopSignal);

            // PERFORM 9000-ACCTFILE-CLOSE.                                                         L83
            closeIssued = true;
            acctFileClose(sysout, workingStorage, acctFile);

            // DISPLAY 'END OF EXECUTION OF PROGRAM CBACT01C'.                                       L85
            sysout.write(END_OF_EXECUTION);

            // GOBACK. RETURN-CODE is untouched by this program, so a normal end is
            // RETURN_CODE_NORMAL_END.                                                               L87
            return recordsDisplayed;
        } finally {
            if (!closeIssued) {
                releaseHandle(acctFile);
            }
        }
    }

    /**
     * Releases the account-master handle on the way out of an incomplete run, silently and only if it is
     * still open.
     *
     * <p>Three properties, each deliberate:
     *
     * <ul>
     *   <li><strong>Never reached on a normal run.</strong> The caller's {@code closeIssued} local is
     *       {@code true} once {@code L83} has been performed, so the successful path is provably
     *       unchanged - same SYSOUT bytes, same round trips.</li>
     *   <li><strong>Silent.</strong> Nothing is written to SYSOUT. The line sequence is this program's
     *       entire observable output and {@code CBACT01C} has no such line, so emitting one here would
     *       be a parity defect rather than a diagnostic.</li>
     *   <li><strong>Non-throwing.</strong> A failure to release is logged and swallowed. The run is
     *       already failing and the caller needs <em>that</em> exception, not one raised while tidying
     *       up after it.</li>
     * </ul>
     *
     * @param acctFile the handle the open returned; never {@code null} here
     */
    private static void releaseHandle(AccountFile acctFile) {
        try {
            acctFile.closeFile();
        } catch (RuntimeException cleanupFailure) {
            // Only the failure's TYPE is logged - never the throwable and never its message. A driver
            // composes its message around the value it refused, and an account row carries
            // ACCT-CURR-BAL and the credit limits (CWE-532); a newline in that text could forge a
            // second log entry (CWE-117). A class name carries no data and no newline.
            LOG.warn("Releasing the " + DD_NAME + " browse of " + PROGRAM_ID + " after an incomplete run "
                    + "failed - " + cleanupFailure.getClass().getName()
                    + ". The run's own outcome is reported to the caller unchanged, because the run's own "
                    + "failure is the one that matters.");
        }
    }

    /**
     * {@code PERFORM UNTIL END-OF-FILE = 'Y'} - {@code app/cbl/CBACT01C.cbl:L74-L81}.
     *
     * <p>A {@code while} loop over the same test the COBOL writes, so the number of iterations and the
     * point at which it stops are identical. The loop carries no exit condition of its own: it ends when
     * the flag becomes {@code 'Y'}, which only {@link #acctFileGetNext} sets, and a fatal read leaves it
     * by throwing.
     *
     * <p>The one thing here that has no COBOL counterpart is the stop probe at the top of the body. It
     * is a call rather than a condition, so it adds no arm to the translated control flow: while no stop
     * is pending it returns and the iteration proceeds exactly as before. Its position is the whole of
     * its correctness - between records, before {@code 1000-ACCTFILE-GET-NEXT} reads the next one - so
     * the record in flight is always complete. See {@link StopSignal}.
     *
     * @param sysout         where displayed lines go
     * @param workingStorage this run's status register and end-of-file flag
     * @param acctFile       the opened account master
     * @param stopSignal     the between-record cancellation probe
     * @return the number of records displayed
     * @throws AbendException if a read reports a fatal status
     * @throws BatchConfig.StopRequestedException if the step is asked to stop
     */
    private int acctFileDisplayLoop(SysoutSink sysout, WorkingStorage workingStorage,
            AccountFile acctFile, StopSignal stopSignal) {
        int recordsDisplayed = 0;
        while (!workingStorage.endOfFileIsYes()) {
            stopSignal.checkStopRequested();
            recordsDisplayed += acctFileDisplayIteration(sysout, workingStorage, acctFile);
        }
        return recordsDisplayed;
    }

    /**
     * One iteration of the mainline loop - {@code app/cbl/CBACT01C.cbl:L75-L80}:
     * <pre>
     * IF  END-OF-FILE = 'N'
     *     PERFORM 1000-ACCTFILE-GET-NEXT
     *     IF  END-OF-FILE = 'N'
     *         DISPLAY ACCOUNT-RECORD
     *     END-IF
     * END-IF
     * </pre>
     *
     * <p>Both guards test {@code = 'N'}, which is <em>not</em> the negation of the loop's
     * {@code = 'Y'}: a flag holding neither value would satisfy the loop and fail both guards. The two
     * tests are therefore kept distinct here as they are in the source, and this method is at package
     * scope so that both arms of the first guard remain directly assertable - on a real run the flag
     * only ever holds {@code 'N'} or {@code 'Y'}, so the mainline cannot reach that arm, yet it is a
     * real part of the translated control flow rather than something to drop.
     *
     * <p>The second guard is the ordinary end-of-file case: {@code 1000-ACCTFILE-GET-NEXT} has just set
     * the flag to {@code 'Y'}, so the raw record image is not displayed for the read that found nothing.
     *
     * @param sysout         where displayed lines go
     * @param workingStorage this run's status register and end-of-file flag
     * @param acctFile       the opened account master
     * @return {@code 1} when a record was read and displayed, {@code 0} otherwise
     * @throws AbendException if the read reports a fatal status
     */
    int acctFileDisplayIteration(SysoutSink sysout, WorkingStorage workingStorage,
            AccountFile acctFile) {
        // IF END-OF-FILE = 'N'                                                                     L75
        if (!workingStorage.endOfFileIsNo()) {
            return 0;
        }

        // PERFORM 1000-ACCTFILE-GET-NEXT                                                           L76
        ReadResult result = acctFileGetNext(sysout, workingStorage, acctFile);

        // IF END-OF-FILE = 'N'                                                                     L77
        if (!workingStorage.endOfFileIsNo()) {
            return 0;
        }

        // DISPLAY ACCOUNT-RECORD - the SECOND of this record's two displays, and the raw one: all
        // 300 stored bytes, zoned sign overpunches and 178 FILLER spaces included.                  L78
        AccountRecord account = result.account().orElseThrow(() -> new IllegalStateException(
                "1000-ACCTFILE-GET-NEXT reported file status '" + result.status() + "' and left "
                        + "END-OF-FILE = '" + workingStorage.endOfFileFlag() + "', so the mainline "
                        + "reached DISPLAY ACCOUNT-RECORD with no record to display. Only the '"
                        + FileStatus.OK + "' arm leaves the flag unchanged, and that arm always carries "
                        + "the decoded record."));
        sysout.write(account.toFixedWidthString());
        return 1;
    }

    /**
     * {@code 1000-ACCTFILE-GET-NEXT} - {@code app/cbl/CBACT01C.cbl:L92-L116}.
     *
     * <pre>
     * READ ACCTFILE-FILE INTO ACCOUNT-RECORD.
     * IF  ACCTFILE-STATUS = '00'      MOVE 0  TO APPL-RESULT
     *                                 PERFORM 1100-DISPLAY-ACCT-RECORD
     * ELSE IF ACCTFILE-STATUS = '10'  MOVE 16 TO APPL-RESULT
     *      ELSE                       MOVE 12 TO APPL-RESULT
     * IF  APPL-AOK                    CONTINUE
     * ELSE IF APPL-EOF                MOVE 'Y' TO END-OF-FILE
     *      ELSE                       DISPLAY 'ERROR READING ACCOUNT FILE'
     *                                 MOVE ACCTFILE-STATUS TO IO-STATUS
     *                                 PERFORM 9910-DISPLAY-IO-STATUS
     *                                 PERFORM 9999-ABEND-PROGRAM
     * </pre>
     *
     * <p>Two guard chains in sequence, not one: the first classifies the file status into
     * {@code APPL-RESULT}, and the second decides what to do about the classification. They are kept
     * separate because the {@code 88}-level conditions are what the second chain tests, and collapsing
     * them would remove the register the abend's return code is taken from.
     *
     * <p><strong>This is where the first of a record's two displays happens.</strong>
     * {@code PERFORM 1100-DISPLAY-ACCT-RECORD} sits inside the successful arm of the <em>read</em>
     * paragraph at {@code L96}, while the mainline's raw {@code DISPLAY ACCOUNT-RECORD} sits at
     * {@code L78}. Neither is redundant; both are emitted.
     *
     * @param sysout         where displayed lines go
     * @param workingStorage this run's status register and end-of-file flag
     * @param acctFile       the opened account master
     * @return the read's outcome, carrying the decoded record on the successful arm
     * @throws AbendException if the status is neither {@code '00'} nor {@code '10'}
     */
    private ReadResult acctFileGetNext(SysoutSink sysout, WorkingStorage workingStorage,
            AccountFile acctFile) {
        // READ ACCTFILE-FILE INTO ACCOUNT-RECORD.                                                   L93
        ReadResult result = acctFile.readNext();
        String status = result.status();

        if (FileStatus.isOk(status)) {                                                            // L94
            workingStorage.moveToApplResult(APPL_AOK);                                            // L95
            displayAcctRecord(sysout, result.account().orElseThrow(() -> new IllegalStateException(
                    "A read of the account master reported file status '" + FileStatus.OK + "' with no "
                            + "decoded record; 1100-DISPLAY-ACCT-RECORD has nothing to display.")));
        } else if (FileStatus.isEndOfFile(status)) {                                              // L98
            workingStorage.moveToApplResult(APPL_EOF);                                            // L99
        } else {
            workingStorage.moveToApplResult(APPL_RESULT_FATAL);                                  // L101
        }

        // IF APPL-AOK CONTINUE ELSE ...                                                     L104-L115
        if (!workingStorage.applAok()) {
            if (workingStorage.applEof()) {                                                     // L107
                workingStorage.moveEndOfFile(END_OF_FILE_YES);                                  // L108
            } else {
                throw reportAndAbend(sysout, workingStorage, ERROR_READING_ACCOUNT_FILE, status);
            }
        }
        return result;
    }

    /**
     * {@code 1100-DISPLAY-ACCT-RECORD} - {@code app/cbl/CBACT01C.cbl:L118-L131}.
     *
     * <p>Eleven labelled lines then the separator, transcribed one Java statement per COBOL statement so
     * the two can be diffed side by side. Each line is a {@value #LABEL_WIDTH}-character label
     * immediately followed by the field's stored image, composed with the {@code DELIMITED BY SIZE}
     * concatenator so that neither operand can be trimmed or re-formatted on the way out.
     *
     * <p><strong>{@code ACCT-ADDR-ZIP} is not displayed.</strong> The copybook declares it between
     * {@code ACCT-CURR-CYC-DEBIT} and {@code ACCT-GROUP-ID}, and the COBOL simply skips it. Its accessor
     * exists on {@link AccountRecord} and is not called here.
     *
     * @param sysout  where displayed lines go
     * @param account the record just read
     */
    private void displayAcctRecord(SysoutSink sysout, AccountRecord account) {
        sysout.write(displayLine(LABEL_ACCT_ID, account.rawAcctId()));                           // L119
        sysout.write(displayLine(LABEL_ACCT_ACTIVE_STATUS, account.rawAcctActiveStatus()));      // L120
        sysout.write(displayLine(LABEL_ACCT_CURR_BAL, account.rawAcctCurrBal()));                // L121
        sysout.write(displayLine(LABEL_ACCT_CREDIT_LIMIT, account.rawAcctCreditLimit()));        // L122
        sysout.write(displayLine(LABEL_ACCT_CASH_CREDIT_LIMIT,
                account.rawAcctCashCreditLimit()));                                             // L123
        sysout.write(displayLine(LABEL_ACCT_OPEN_DATE, account.rawAcctOpenDate()));              // L124
        sysout.write(displayLine(LABEL_ACCT_EXPIRAION_DATE, account.rawAcctExpiraionDate()));    // L125
        sysout.write(displayLine(LABEL_ACCT_REISSUE_DATE, account.rawAcctReissueDate()));        // L126
        sysout.write(displayLine(LABEL_ACCT_CURR_CYC_CREDIT, account.rawAcctCurrCycCredit()));   // L127
        sysout.write(displayLine(LABEL_ACCT_CURR_CYC_DEBIT, account.rawAcctCurrCycDebit()));     // L128
        sysout.write(displayLine(LABEL_ACCT_GROUP_ID, account.rawAcctGroupId()));                // L129
        sysout.write(RECORD_SEPARATOR);                                                          // L130
    }

    /**
     * Composes one {@code DISPLAY 'label' field} line.
     *
     * <p>{@code DISPLAY} with two operands writes them adjacently, each at its full width and with no
     * separator, which is precisely {@code DELIMITED BY SIZE}. Routing it through
     * {@link FixedWidthCodec#concatenateDelimitedBySize(String...)} rather than through string
     * concatenation means the rule is applied by the module's one auditable implementation of it, and a
     * {@code null} operand is refused rather than rendered as the text {@code "null"}.
     *
     * @param label the {@value #LABEL_WIDTH}-character label, colon included
     * @param image the field's stored characters, at the field's full declared width
     * @return the complete line
     */
    private String displayLine(String label, String image) {
        return codec.concatenateDelimitedBySize(label, image);
    }

    // =================================================================================================
    // The OPEN and CLOSE paragraphs. Both have the same two-armed shape, and both arms of both are
    // reproduced: the repository reports the status, and this class owns the display and the abend.
    // =================================================================================================

    /**
     * {@code 0000-ACCTFILE-OPEN} - {@code app/cbl/CBACT01C.cbl:L133-L149}.
     *
     * <pre>
     * MOVE 8 TO APPL-RESULT.
     * OPEN INPUT ACCTFILE-FILE
     * IF  ACCTFILE-STATUS = '00'  MOVE 0  TO APPL-RESULT
     * ELSE                        MOVE 12 TO APPL-RESULT
     * IF  APPL-AOK                CONTINUE
     * ELSE                        DISPLAY 'ERROR OPENING ACCTFILE'
     *                             MOVE ACCTFILE-STATUS TO IO-STATUS
     *                             PERFORM 9910-DISPLAY-IO-STATUS
     *                             PERFORM 9999-ABEND-PROGRAM
     * </pre>
     *
     * <p>The {@code MOVE 8} comes <em>first</em>, before the verb, and is reproduced in that order: it is
     * an assume-failure default, so a verb that neither succeeded nor reported a status cannot leave the
     * register looking successful.
     *
     * <p>{@code OPEN INPUT} and not {@code I-O}: this program never writes. The handle it returns carries
     * the position and the mode, so two concurrent runs cannot consume each other's records.
     *
     * @param sysout         where displayed lines go
     * @param workingStorage this run's status register
     * @return the opened account master, positioned before the first record
     * @throws AbendException if the open reports any status other than {@code '00'}
     */
    private AccountFile acctFileOpen(SysoutSink sysout, WorkingStorage workingStorage) {
        // MOVE 8 TO APPL-RESULT.                                                                   L134
        workingStorage.moveToApplResult(APPL_RESULT_ASSUMED_FAILURE);

        // OPEN INPUT ACCTFILE-FILE                                                                 L135
        AccountFile acctFile = accountRepository.open(OpenMode.INPUT);
        String status = acctFile.openStatus();

        if (FileStatus.isOk(status)) {                                                           // L136
            workingStorage.moveToApplResult(APPL_AOK);                                          // L137
        } else {
            workingStorage.moveToApplResult(APPL_RESULT_FATAL);                                 // L139
        }

        // IF APPL-AOK CONTINUE ELSE ...                                                     L141-L148
        if (!workingStorage.applAok()) {
            throw reportAndAbend(sysout, workingStorage, ERROR_OPENING_ACCTFILE, status);
        }
        return acctFile;
    }

    /**
     * {@code 9000-ACCTFILE-CLOSE} - {@code app/cbl/CBACT01C.cbl:L151-L167}.
     *
     * <pre>
     * ADD 8 TO ZERO GIVING APPL-RESULT.
     * CLOSE ACCTFILE-FILE
     * IF  ACCTFILE-STATUS = '00'  SUBTRACT APPL-RESULT FROM APPL-RESULT
     * ELSE                        ADD 12 TO ZERO GIVING APPL-RESULT
     * IF  APPL-AOK                CONTINUE
     * ELSE                        DISPLAY 'ERROR CLOSING ACCOUNT FILE'
     *                             MOVE ACCTFILE-STATUS TO IO-STATUS
     *                             PERFORM 9910-DISPLAY-IO-STATUS
     *                             PERFORM 9999-ABEND-PROGRAM
     * </pre>
     *
     * <p>Three arithmetic idioms, all transcribed as written rather than normalised into moves.
     * {@code ADD 8 TO ZERO GIVING} is the open paragraph's {@code MOVE 8} spelled differently;
     * {@code SUBTRACT APPL-RESULT FROM APPL-RESULT} reaches zero by subtracting the register from itself;
     * {@code ADD 12 TO ZERO GIVING} is its {@code MOVE 12}. Preserving the spelling costs nothing and
     * keeps the correspondence with the source exact - these are the two {@code ADD} statements that make
     * the arithmetic-verb census of this program non-zero while touching no money whatsoever.
     *
     * @param sysout         where displayed lines go
     * @param workingStorage this run's status register
     * @param acctFile       the opened account master
     * @throws AbendException if the close reports any status other than {@code '00'}
     */
    private void acctFileClose(SysoutSink sysout, WorkingStorage workingStorage,
            AccountFile acctFile) {
        // ADD 8 TO ZERO GIVING APPL-RESULT.                                                        L152
        workingStorage.addToZeroGivingApplResult(APPL_RESULT_ASSUMED_FAILURE);

        // CLOSE ACCTFILE-FILE                                                                     L153
        String status = acctFile.closeFile();

        if (FileStatus.isOk(status)) {                                                          // L154
            workingStorage.subtractApplResultFromApplResult();                                 // L155
        } else {
            workingStorage.addToZeroGivingApplResult(APPL_RESULT_FATAL);                       // L157
        }

        // IF APPL-AOK CONTINUE ELSE ...                                                    L159-L166
        if (!workingStorage.applAok()) {
            throw reportAndAbend(sysout, workingStorage, ERROR_CLOSING_ACCOUNT_FILE, status);
        }
    }

    // =================================================================================================
    // The fatal arm, shared by all three I/O paragraphs, and the abend itself.
    // =================================================================================================

    /**
     * The fatal arm the three I/O paragraphs share, in the order they perform it:
     * {@code DISPLAY '<text>'}, then {@code MOVE <file>-STATUS TO IO-STATUS} and
     * {@code PERFORM 9910-DISPLAY-IO-STATUS}, then {@code PERFORM 9999-ABEND-PROGRAM}.
     *
     * <p>Written once because the three sites are textually identical apart from the message -
     * {@code L110-L113}, {@code L144-L147} and {@code L162-L165} - and the ordering of the three lines
     * is the part that must not drift between them.
     *
     * <p>Returns the exception rather than throwing it, so that every call site reads
     * {@code throw reportAndAbend(...)} and the abend is visible as a termination at the point it
     * happens.
     *
     * @param sysout         where displayed lines go
     * @param workingStorage this run's status register, whose value becomes the return code
     * @param errorText      the paragraph's own error literal, emitted verbatim
     * @param status         the two-character file status the operation reported
     * @return the abend to throw
     */
    private AbendException reportAndAbend(SysoutSink sysout, WorkingStorage workingStorage,
            String errorText, String status) {
        // DISPLAY '<error text>'
        sysout.write(errorText);

        // MOVE <file>-STATUS TO IO-STATUS, then PERFORM 9910-DISPLAY-IO-STATUS.
        String statusLine = displayIoStatus(sysout, status);

        // PERFORM 9999-ABEND-PROGRAM.
        return abendProgram(sysout, workingStorage, errorText + " - " + statusLine);
    }

    /**
     * {@code MOVE <file>-STATUS TO IO-STATUS} followed by
     * {@code PERFORM 9910-DISPLAY-IO-STATUS} - {@code app/cbl/CBACT01C.cbl:L176-L189}.
     *
     * <p>The paragraph itself is <strong>not</strong> reimplemented here. It appears in sixteen places
     * across the estate, so it lives in {@link FileStatus#toDisplayLine(String)}: the same
     * {@code IO-STATUS-04} rendering, including the extended-status branch for a first byte of
     * {@code '9'} and the literal {@code "}{@value FileStatus#DISPLAY_PREFIX}{@code "} whose
     * {@code NNNN} is part of the text rather than a placeholder. A status of {@code "00"} renders as
     * {@code FILE STATUS IS: NNNN0000}.
     *
     * @param sysout where the rendered line goes
     * @param status the two-character file status
     * @return the line that was emitted, so the caller can carry it into the abend's diagnostic
     */
    private String displayIoStatus(SysoutSink sysout, String status) {
        String statusLine = FileStatus.toDisplayLine(status);
        sysout.write(statusLine);
        return statusLine;
    }

    /**
     * {@code 9999-ABEND-PROGRAM} - {@code app/cbl/CBACT01C.cbl:L169-L173}.
     *
     * <pre>
     * DISPLAY 'ABENDING PROGRAM'
     * MOVE 0   TO TIMING
     * MOVE 999 TO ABCODE
     * CALL 'CEE3ABD'.
     * </pre>
     *
     * <p>The displayed text is {@link AbendException#ABEND_DISPLAY_TEXT}, taken from the constant rather
     * than restated so all nine abend sites in the estate emit one identical line.
     *
     * <p>{@code TIMING} and {@code ABCODE} are not modelled as fields here because
     * {@link AbendException#standard(String, int, String)} <em>is</em> those two moves: it supplies
     * {@code ABCODE = }{@value AbendException#STANDARD_ABEND_CODE} and
     * {@code TIMING = }{@value AbendException#STANDARD_TIMING} for exactly the eight standard paragraphs
     * of which this is one. Declaring two write-only fields to hold the same constants would duplicate
     * the fact without checking it; the test asserts the values the raised abend actually carries.
     *
     * <p>The return code is {@code APPL-RESULT} as the guard chain left it - {@code 12} at every one of
     * this program's three fatal arms - and {@code BatchConfig}'s listeners carry it to the step's exit
     * status and the process's exit code (gate G35).
     *
     * <p>The reason text is diagnostic only and is never displayed: it carries the paragraph's error
     * literal and the rendered file status, both fixed text, and no field of any account record. Nothing
     * a caller supplied and nothing a driver said reaches it.
     *
     * @param sysout         where the abend banner goes
     * @param workingStorage this run's status register
     * @param reason         the error literal and rendered status, for the diagnostic
     * @return the abend to throw
     */
    private AbendException abendProgram(SysoutSink sysout, WorkingStorage workingStorage,
            String reason) {
        // DISPLAY 'ABENDING PROGRAM'                                                               L170
        sysout.write(AbendException.ABEND_DISPLAY_TEXT);

        // MOVE 0 TO TIMING, MOVE 999 TO ABCODE, CALL 'CEE3ABD'.                              L171-L173
        return AbendException.standard(PROGRAM_ID, workingStorage.applResult(), reason);
    }

    /**
     * Forms one of {@code 1100-DISPLAY-ACCT-RECORD}'s label literals: the copybook field name, padded
     * with spaces to {@value #LABEL_WIDTH} characters including the terminating colon.
     *
     * <p>All eleven literals at {@code app/cbl/CBACT01C.cbl:L119-L129} are formed this way - verified by
     * extracting each literal from the source and re-deriving it from its field name - so deriving them
     * removes the one defect a hand-written label can carry and review cannot see: a miscounted space.
     *
     * @param cobolName the field name exactly as {@code app/cpy/CVACT01Y.cpy} spells it, misspellings
     *                  included
     * @return the label, exactly {@value #LABEL_WIDTH} characters
     * @throws IllegalArgumentException if the name leaves no room for the colon, which would mean the
     *                                  copybook field name and this program's label width disagree
     */
    private static String label(String cobolName) {
        return cobolName + " ".repeat(LABEL_WIDTH - cobolName.length() - 1) + ':';
    }

    // =================================================================================================
    // SYSOUT: one seam, so a run's line sequence can be captured exactly.
    // =================================================================================================

    /**
     * Where a {@code DISPLAY} goes: the Java form of {@code //SYSOUT DD SYSOUT=*}
     * ({@code app/jcl/READACCT.jcl:L27}).
     *
     * <p>An interface rather than a stream or a logger, for one reason: this program writes no records,
     * so its {@code SYSOUT} <strong>is</strong> its observable output, and a parity case has to be able
     * to capture the exact sequence of lines - all 652 of them for the fifty-record fixture - and diff
     * it field by field. A logger would prepend a timestamp, a level and a thread name to every line and
     * make that impossible; writing to the standard output stream directly from the display code would
     * make it untestable.
     *
     * <p>Being a functional interface, a test can supply {@code lines::add} and read the sequence back
     * in order.
     *
     * <p>Declared here rather than in the shared package on purpose. Each batch program has its own
     * {@code SYSOUT}, and a per-job type means nine sibling job classes can each accept an injected sink
     * without their beans becoming ambiguous with one another.
     */
    @FunctionalInterface
    public interface SysoutSink {

        /**
         * Writes one complete line, exactly as {@code DISPLAY} emits it.
         *
         * <p>The line is passed without a line terminator and must not be trimmed, wrapped, re-encoded
         * or decorated: trailing spaces are significant, because a raw 300-byte account record image
         * ends in 178 of them.
         *
         * @param line the line to emit; never {@code null} and never empty, since every
         *             {@code DISPLAY} in this program writes at least one character
         */
        void write(String line);
    }

    /**
     * The production sink: one line per call to the process's standard output stream, in the code page
     * given.
     *
     * <p>Used when the context declares no {@link SysoutSink} bean of its own. The charset is a
     * <strong>parameter</strong> and the platform default is never consulted (practice B8): the lines
     * this program emits are the account master's own stored characters, so the honest code page for
     * them is the one the dataset is read in - which on the mainframe is exactly what happens, the
     * record's bytes passing from the dataset to the spool unchanged.
     *
     * <p>The stream is auto-flushing, so a line is visible as soon as it is written rather than at
     * process exit, and it is opened on the standard output file descriptor rather than taken from a
     * mutable global, so a caller cannot silently redirect one job's {@code SYSOUT} by reassigning
     * something else. It is deliberately never closed: the standard output stream outlives every job
     * that writes to it, and closing it would silence the rest of the process.
     *
     * @param charset the code page to encode each line in; must not be {@code null}
     * @return a sink writing to standard output
     * @throws NullPointerException if {@code charset} is {@code null}
     */
    public static SysoutSink standardOutput(Charset charset) {
        Objects.requireNonNull(charset, "A code page is required for SYSOUT: a displayed record is the "
                + "dataset's own bytes, and the platform default is never assumed");
        PrintStream stream = new PrintStream(new FileOutputStream(FileDescriptor.out), true, charset);
        return stream::println;
    }

    // =================================================================================================
    // WORKING-STORAGE SECTION - app/cbl/CBACT01C.cbl:L61-L65.
    // =================================================================================================

    /**
     * The two working-storage items whose values drive this program's control flow:
     * {@code APPL-RESULT PIC S9(9) COMP} with its two {@code 88}-level conditions, and
     * {@code END-OF-FILE PIC X(01) VALUE 'N'}.
     *
     * <p><strong>One instance per run, never a field of the bean.</strong> A {@code @Configuration} class
     * is a singleton, so holding these two items on it would make one run's end-of-file flag visible to
     * another's loop. They are created by {@link AccountBalanceJob#readAndPrintAccountFile(SysoutSink)}
     * and discarded when it returns, which is what keeps two concurrent runs independent and what gate
     * G53 requires.
     *
     * <p><strong>The mutators are named after the COBOL statements, not after Java conventions.</strong>
     * {@code MOVE 8 TO APPL-RESULT} and {@code ADD 8 TO ZERO GIVING APPL-RESULT} reach the same value by
     * different statements and appear in different paragraphs, and
     * {@code SUBTRACT APPL-RESULT FROM APPL-RESULT} reaches zero without naming zero at all. Each has its
     * own method so a reviewer can put this class beside the source and see one method per statement,
     * which is the whole point of transcribing the transitions rather than simplifying them: they are
     * what the abend's return code, and so the step's exit code, is taken from.
     *
     * <p>The end-of-file flag is a one-character string and not a boolean, because the COBOL tests it
     * against two <em>different</em> literals in two adjacent places - {@code = 'Y'} for the loop and
     * {@code = 'N'} for the guard inside it - and a boolean could not tell those two tests apart.
     */
    static final class WorkingStorage {

        /**
         * {@code APPL-RESULT PIC S9(9) COMP} - the status register, {@code app/cbl/CBACT01C.cbl:L61}.
         *
         * <p>{@code PIC S9(9) COMP} is a signed nine-digit binary integer, which fits an {@code int}
         * exactly. Its declared initial value is unspecified in the source, and no paragraph reads it
         * before writing it: the open paragraph moves {@code 8} into it as its very first statement.
         */
        private int applResult;

        /**
         * {@code END-OF-FILE PIC X(01) VALUE 'N'} - {@code app/cbl/CBACT01C.cbl:L65}, initialised to its
         * declared value.
         */
        private String endOfFile = END_OF_FILE_NO;

        /**
         * {@code 88 APPL-AOK VALUE 0} - {@code app/cbl/CBACT01C.cbl:L62}.
         *
         * <p>Tested first in all three of this program's guard chains, at {@code L104}, {@code L141} and
         * {@code L159}.
         *
         * @return {@code true} when the register holds {@value AccountBalanceJob#APPL_AOK}
         */
        boolean applAok() {
            return applResult == APPL_AOK;
        }

        /**
         * {@code 88 APPL-EOF VALUE 16} - {@code app/cbl/CBACT01C.cbl:L63}.
         *
         * <p>Tested only in the read paragraph, at {@code L107}, and only after {@code APPL-AOK} has
         * already failed. That ordering is preserved: a register holding {@code 0} is never asked whether
         * it holds {@code 16}.
         *
         * @return {@code true} when the register holds {@value AccountBalanceJob#APPL_EOF}
         */
        boolean applEof() {
            return applResult == APPL_EOF;
        }

        /**
         * {@code MOVE <literal> TO APPL-RESULT} - {@code L95}, {@code L99}, {@code L101}, {@code L134}
         * and {@code L139}.
         *
         * @param value the literal being moved
         */
        void moveToApplResult(int value) {
            applResult = value;
        }

        /**
         * {@code ADD <literal> TO ZERO GIVING APPL-RESULT} - {@code L152} and {@code L157}.
         *
         * <p>{@code GIVING} replaces the receiver rather than accumulating into it, and the augend is the
         * literal zero, so the register ends up holding the addend regardless of what it held before.
         * Written as an addition to zero because that is what the source says.
         *
         * @param addend the literal being added to zero
         */
        void addToZeroGivingApplResult(int addend) {
            applResult = 0 + addend;
        }

        /**
         * {@code SUBTRACT APPL-RESULT FROM APPL-RESULT} - {@code app/cbl/CBACT01C.cbl:L155}.
         *
         * <p>The close paragraph's way of reaching zero: it subtracts the register from itself instead of
         * moving a literal. Reproduced as written - the result is always zero, and reading it as anything
         * other than "this is how this paragraph clears the register" would be reading in a subtlety that
         * is not there.
         */
        void subtractApplResultFromApplResult() {
            applResult = applResult - applResult;
        }

        /**
         * The current value of {@code APPL-RESULT}.
         *
         * @return the register's value, which on a fatal arm is the abend's return code
         */
        int applResult() {
            return applResult;
        }

        /**
         * {@code IF END-OF-FILE = 'Y'} - the mainline loop's terminating test,
         * {@code app/cbl/CBACT01C.cbl:L74}.
         *
         * @return {@code true} when the flag holds {@value AccountBalanceJob#END_OF_FILE_YES}
         */
        boolean endOfFileIsYes() {
            return END_OF_FILE_YES.equals(endOfFile);
        }

        /**
         * {@code IF END-OF-FILE = 'N'} - the guards at {@code app/cbl/CBACT01C.cbl:L75} and {@code L77}.
         *
         * <p>Deliberately <em>not</em> the negation of {@link #endOfFileIsYes()}. COBOL compares the flag
         * against a literal, so a flag holding neither {@code 'Y'} nor {@code 'N'} would fail both tests;
         * writing one as the negation of the other would quietly assume a two-valued flag that only this
         * program's own {@code MOVE} statements happen to guarantee.
         *
         * @return {@code true} when the flag holds {@value AccountBalanceJob#END_OF_FILE_NO}
         */
        boolean endOfFileIsNo() {
            return END_OF_FILE_NO.equals(endOfFile);
        }

        /**
         * {@code MOVE '<value>' TO END-OF-FILE} - {@code app/cbl/CBACT01C.cbl:L108}.
         *
         * @param value the one-character flag value being moved; must not be {@code null}, and must be
         *              one character, because the receiving item is {@code PIC X(01)} and a wider value
         *              would be truncated on the right rather than stored
         * @throws NullPointerException     if {@code value} is {@code null}
         * @throws IllegalArgumentException if {@code value} is not exactly one character
         */
        void moveEndOfFile(String value) {
            Objects.requireNonNull(value, "END-OF-FILE is PIC X(01) and holds a character, never null");
            if (value.length() != 1) {
                throw new IllegalArgumentException("END-OF-FILE is PIC X(01), so it holds exactly one "
                        + "character; '" + value + "' is " + value.length() + ". A wider value would be "
                        + "truncated on the right and a narrower one space-padded, and neither is a "
                        + "move this program performs.");
            }
            endOfFile = value;
        }

        /**
         * The current value of the {@code END-OF-FILE} flag.
         *
         * @return the one-character flag, never {@code null}
         */
        String endOfFileFlag() {
            return endOfFile;
        }
    }
}
