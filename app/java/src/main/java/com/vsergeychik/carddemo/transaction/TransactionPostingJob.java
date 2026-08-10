package com.vsergeychik.carddemo.transaction;

import com.vsergeychik.carddemo.account.AccountRepository;
import com.vsergeychik.carddemo.account.model.AccountRecord;
import com.vsergeychik.carddemo.card.CardRepository;
import com.vsergeychik.carddemo.card.CardRepository.BrowseDirection;
import com.vsergeychik.carddemo.card.CardXrefRepository;
import com.vsergeychik.carddemo.card.model.CardRecord;
import com.vsergeychik.carddemo.card.model.CardXrefRecord;
import com.vsergeychik.carddemo.common.AbendException;
import com.vsergeychik.carddemo.common.FileStatus;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.config.BatchConfig;
import com.vsergeychik.carddemo.config.BatchConfig.JobContract;
import com.vsergeychik.carddemo.config.BatchConfig.StepContract;
import com.vsergeychik.carddemo.config.BatchConfig.StopSignal;
import com.vsergeychik.carddemo.customer.CustomerRepository;
import com.vsergeychik.carddemo.customer.model.CustomerRecord;
import com.vsergeychik.carddemo.transaction.model.DalyTranRecord;
import com.vsergeychik.carddemo.transaction.model.TranRecord;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Objects;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The Java translation of {@code app/cbl/CBTRN01C.cbl} (491 lines), assembled as a single-step
 * Spring Batch job.
 *
 * <h2>READ THIS FIRST: the name says "Posting". THE PROGRAM POSTS NOTHING.</h2>
 *
 * <p>The class name {@code TransactionPostingJob} is <strong>mandated verbatim</strong> by the
 * migration plan and is deliberately not corrected, because a mandated name never authorises a change
 * of behaviour: under rule <strong>R1</strong> <em>the name comes from the plan and the behaviour comes
 * from the COBOL source</em>. The source header is just as misleading - {@code app/cbl/CBTRN01C.cbl:5}
 * reads {@code Function : Post the records from daily transaction file.} - and it is wrong about its own
 * program. Practice <strong>B4</strong> requires the divergence to be stated rather than quietly
 * satisfied, so here it is, verified by exhaustive search of the source:
 *
 * <ul>
 *   <li><strong>There is no {@code WRITE} and no {@code REWRITE} anywhere in the program.</strong>
 *       {@code grep -n "WRITE\|REWRITE" app/cbl/CBTRN01C.cbl} returns <em>nothing</em>. Every one of the
 *       six files is opened {@code OPEN INPUT} ({@code :254}, {@code :273}, {@code :291}, {@code :309},
 *       {@code :327}, {@code :345}). Nothing in this class calls a write-side repository method - no
 *       {@code write}, no {@code rewrite}, no {@code delete} - and a run of this job cannot change a
 *       single byte of any dataset.</li>
 *   <li><strong>{@code TRANFILE} is opened and closed and never written.</strong> {@code :58-62}
 *       declares it, {@code 0500-TRANFILE-OPEN} opens it and {@code 9500-TRANFILE-CLOSE} closes it. In
 *       between, nothing. The "posting" the name promises would be a write to this very file, and the
 *       program never issues one.</li>
 *   <li><strong>{@code CUSTFILE} and {@code CARDFILE} are opened and closed and never read.</strong>
 *       {@link CustomerRepository} and {@link CardRepository} are injected for exactly one purpose: so
 *       those two opens and those two closes happen, in their place in the order, with their own status
 *       ladders and their own error texts. No read is added.</li>
 * </ul>
 *
 * <p>What the program actually does is read the daily transaction file to end of file and, per record,
 * report what it found: the raw 350-byte record image, then a cross-reference lookup by card number,
 * then - when that succeeded - an account read by the cross-referenced account id.
 * <strong>Read-and-report only.</strong> Never "complete" the posting the name implies.
 *
 * <h2>It is the migration's single orphan: no JCL runs it, and none is invented</h2>
 *
 * <p>{@code grep -rn "CBTRN01C" app/jcl/ app/proc/ app/csd/} returns <strong>nothing</strong>. There is
 * no {@code EXEC PGM=CBTRN01C} in any of the 29 JCL jobs or the two cataloged procedures, and no
 * {@code DEFINE PROGRAM} for it in the CSD. It is nonetheless one of the twenty-eight in-scope programs,
 * so implicit requirement <strong>I4</strong> and gate <strong>G13</strong> both apply and pull in
 * opposite-looking directions, which is the whole point:
 *
 * <ul>
 *   <li><strong>Fully runnable.</strong> {@link #transactionPostingJob()} publishes a real {@link Job}
 *       bean named {@value #JOB_NAME}, launchable by name exactly like its eight siblings, over the
 *       {@code carddemo.jobs.}{@value #JOB_KEY} contract that configuration already declares.</li>
 *   <li><strong>Never triggered.</strong> This file contains no {@code @Scheduled}, no
 *       {@code CommandLineRunner}, no {@code ApplicationRunner}, no {@code JobLauncher} call and no
 *       reference from any other job's flow. {@code spring.batch.job.enabled: false}
 *       ({@code app/java/src/main/resources/application.yml:206}) is what keeps a published job from
 *       running itself at start-up, and this class does not override it.</li>
 * </ul>
 *
 * <p>Practice <strong>B5</strong> forbids the two tempting alternatives equally: deleting an
 * unreferenced program, and wiring one into a pipeline it was never part of.
 *
 * <h2>Two defects in the source, both preserved</h2>
 *
 * <p>Practice <strong>B5</strong> preserves behaviour including its defects, because the parity gate
 * compares against what the legacy program does rather than what it meant to do.
 *
 * <p><strong>Defect 1 - one extra cross-reference lookup after end of file.</strong> In
 * {@code MAIN-PARA} the three statements
 *
 * <pre>
 *     MOVE 0                 TO WS-XREF-READ-STATUS      :170
 *     MOVE DALYTRAN-CARD-NUM TO XREF-CARD-NUM            :171
 *     PERFORM 2000-LOOKUP-XREF                           :172
 * </pre>
 *
 * sit <strong>outside</strong> the inner {@code IF END-OF-DAILY-TRANS-FILE = 'N'} that spans
 * {@code :167-169} - that guard encloses only {@code DISPLAY DALYTRAN-RECORD}. So the iteration whose
 * read returns end of file still performs a lookup, and it performs it on the <em>stale</em>
 * {@code DALYTRAN-CARD-NUM} left in the record area by the previous record, because a COBOL
 * {@code READ ... INTO} does not disturb the receiving area at {@code AT END}. That lookup normally
 * succeeds - it is the same key that succeeded a moment ago - so it emits four more {@code SYSOUT} lines
 * and, if the account read then fails, a fifth. Every run therefore performs
 * <strong>{@code recordsRead + 1}</strong> lookups, which {@link ExecutionSummary} states and a test
 * asserts. <em>Do not hoist the lookup inside the guard.</em>
 *
 * <p><strong>Defect 2 - the daily-transaction close reports the customer file.</strong>
 * {@code 9000-DALYTRAN-CLOSE} ({@code :361-377}) closes {@code DALYTRAN-FILE} and tests
 * {@code DALYTRAN-STATUS}, but its failing arm is a copy of the paragraph below it and was never
 * adjusted:
 *
 * <pre>
 *     DISPLAY 'ERROR CLOSING CUSTOMER FILE'              :372
 *     MOVE CUSTFILE-STATUS TO IO-STATUS                  :373
 * </pre>
 *
 * <p>So a failure closing the <em>daily transaction</em> file announces the <em>customer</em> file and
 * renders the <em>customer</em> file's status - which at that point is the {@code '00'} its own
 * successful open left there, so the rendered line reads {@code FILE STATUS IS: NNNN0000} while the
 * status that actually failed is never shown. Reproduced exactly; see
 * {@link Execution#closeDalytran()}.
 *
 * <h2>What this class owns, and what it does not</h2>
 *
 * <p>It owns the Spring Batch wiring, the {@code SYSOUT} destination, and the whole procedure division.
 * Practice <strong>B2</strong> bounds the wiring: the job and step builders come from
 * {@link BatchConfig#job(String)} and {@link BatchConfig#taskletStep(String, Tasklet)}, which arrive
 * already bound to the auto-configured {@code JobRepository}, the module's single
 * {@code PlatformTransactionManager} and the shared abend listeners. There is no
 * {@code @EnableBatchProcessing} here, no {@code JobRepository}, {@code JobLauncher} or transaction
 * manager of this class's own (gate <strong>G3</strong>), and <strong>no job parameters</strong> - there
 * is no JCL step, therefore no {@code PARM}, therefore nothing to declare.
 *
 * <p>A <strong>{@link Tasklet}</strong> step and never a chunk-oriented one. The program is one
 * sequential pass whose ordering <em>is</em> its observable behaviour, and the record it reports on
 * carries state into the next iteration - the stale card number of defect 1 is precisely that. Chunking
 * would relocate the commit boundaries the pass sits inside.
 *
 * <p>The procedure division is reachable without a launcher: {@link #execute(SysoutSink)} runs the whole
 * program as plain Java, which is what lets the twenty {@code CBTRN01C} parity cases and every branch
 * test exercise it directly (practice <strong>B10</strong>, gate <strong>G51</strong>).
 *
 * <h2>State, arithmetic and the things this file deliberately does not import</h2>
 *
 * <p>A {@code @Configuration} class is a singleton, so nothing per-run may live on it (practice
 * <strong>B9</strong>, gate <strong>G53</strong>). Every item of {@code WORKING-STORAGE} -
 * {@code END-OF-DAILY-TRANS-FILE}, {@code WS-XREF-READ-STATUS}, {@code WS-ACCT-READ-STATUS},
 * {@code APPL-RESULT}, {@code IO-STATUS} and all six record areas - is a field of a fresh
 * {@link Execution} created per run. This file declares no mutable static of any kind; its only static
 * fields are immutable constants and the logger.
 *
 * <p><strong>There is no arithmetic to get wrong, and that was verified rather than assumed.</strong>
 * The program contains six arithmetic statements, all of them the identical
 * {@code ADD 8 TO ZERO GIVING APPL-RESULT} that seeds the six close paragraphs ({@code :362},
 * {@code :380}, {@code :398}, {@code :416}, {@code :434}, {@code :452}); it contains no
 * {@code COMPUTE}, no {@code SUBTRACT}, no {@code MULTIPLY}, no {@code DIVIDE} and no {@code ROUNDED}.
 * No monetary field is computed, and none is formatted for display: {@code DALYTRAN-AMT} reaches
 * {@code SYSOUT} only inside the raw record image, as the zoned characters the dataset holds.
 * {@code common/CobolDecimal} is therefore <strong>not imported</strong> - there is no scaled value for
 * it to police - and there is no {@code BigDecimal}, no {@code double} and no {@code float} in this file
 * (gates <strong>G22</strong>, <strong>G23</strong>, <strong>G24</strong>).
 *
 * <p>No dataset name is written here: the six DD names are configuration keys, resolved through
 * {@link BatchConfig#datasetBinding(String, String)}, and no {@code AWS.M2.CARDDEMO} literal appears
 * (gate <strong>G46</strong>). No import is a wildcard (gate <strong>G52</strong>). Nothing is annotated
 * as an entity and no DDL is emitted (gate <strong>G44</strong>).
 *
 * <h2>How its parity expectations were derived</h2>
 *
 * <p>Risk <strong>R-A</strong>: no COBOL runtime is reachable from this build, so the twenty parity
 * cases for this program are <strong>statically derived</strong> - read out of the paragraphs above,
 * cross-checked against {@code app/cpy/CVTRA06Y.cpy}, {@code app/cpy/CVACT03Y.cpy} and
 * {@code app/cpy/CVACT01Y.cpy} for widths and offsets, and seeded from the 300 real records of
 * {@code app/data/ASCII/dailytran.txt}. They are not captured from a live execution, and that is
 * recorded here rather than left to be discovered.
 *
 * @see DalyTranRepository the {@code DALYTRAN} sequential pass this program reads
 * @see CardXrefRepository the {@code CCXREF} base cluster {@code 2000-LOOKUP-XREF} reads by card number
 * @see AccountRepository  the {@code ACCTFILE} keyed read {@code 3000-READ-ACCOUNT} issues
 */
@Configuration(TransactionPostingJob.CONFIGURATION_BEAN_NAME)
public class TransactionPostingJob {

    // =================================================================================================
    // Identity. Every name below is a configuration key, a JCL-equivalent name or a COBOL literal.
    // Nothing here is invented.
    // =================================================================================================

    /**
     * The bean name of this configuration class itself.
     *
     * <p><strong>Qualified deliberately, and it has to be.</strong> Component scanning would name this
     * configuration bean after its class - {@code transactionPostingJob} - and
     * {@link #transactionPostingJob()} publishes the {@link Job} under exactly that name, because
     * {@code BatchConfig}'s contract lookup derives it from the key {@value #JOB_KEY}. Two definitions of
     * one bean name is a hard failure: definition overriding is disabled by default, so the context
     * would refuse to start. The class name is mandated by the plan and the job name is derived from the
     * contract, so neither may move; this bean's own name carries no external contract, so it is the one
     * that is qualified. Every sibling batch job class in this module resolves the collision the same
     * way.
     */
    public static final String CONFIGURATION_BEAN_NAME = "transactionPostingJobConfiguration";

    /** The COBOL program this class translates. */
    public static final String PROGRAM_ID = "CBTRN01C";

    /**
     * This job's key under the {@code carddemo.jobs} configuration prefix.
     *
     * <p>{@code BatchConfig.JobContracts.REQUIRED_JOBS} pairs this key with {@value #PROGRAM_ID} and
     * validates the pairing at context refresh, so a contract that is absent, renamed or re-pointed at
     * another program stops the context rather than producing a job that runs against the wrong
     * bindings.
     */
    public static final String JOB_KEY = "transaction-posting-job";

    /**
     * The Spring Batch job name, which is also this job's identity in the batch metadata and the name of
     * the bean {@link #transactionPostingJob()} publishes.
     *
     * <p>It is the camel-case form of {@value #JOB_KEY}, because that is what {@code BatchConfig} derives
     * when it resolves a job bean name back to its contract. A different name here would leave the job
     * unable to find its own parameter contract.
     */
    public static final String JOB_NAME = "transactionPostingJob";

    /**
     * The single step's name.
     *
     * <p>Every other batch job in this estate takes its step name from the {@code //STEPnn EXEC} card of
     * the JCL that runs it. This program has no JCL, so there is no card to transcribe and the name is
     * this module's own - which is why it is read from the contract at construction rather than trusted
     * from here: {@code carddemo.jobs.}{@value #JOB_KEY}{@code .steps} is where it is declared, and the
     * constructor proves the declaration still says {@value #STEP_NAME}.
     */
    public static final String STEP_NAME = "STEP01";

    /**
     * The whole step sequence this job runs: one step, {@value #STEP_NAME}, running
     * {@value #PROGRAM_ID}, ungated.
     *
     * <p>A sequence rather than a bare name, because only a whole-sequence comparison rejects a second
     * step declared beside this one or this one declared second. Ungated because {@code COND=(0,NE)}
     * exists on three steps of {@code app/jcl/CREASTMT.JCL} and nowhere else in this estate - and gating
     * the only step of a single-step job would stop it running at all.
     */
    public static final List<StepContract> REQUIRED_STEPS =
            List.of(new StepContract(STEP_NAME, PROGRAM_ID, false));

    // -------------------------------------------------------------------------------------------------
    // The six DD names of app/cbl/CBTRN01C.cbl:29-62, in the order the program opens and closes them.
    // Each is a key into carddemo.datasets, resolved through this job's own contract view so a
    // job-scoped override is honoured. No dataset name appears in this file (gate G46).
    // -------------------------------------------------------------------------------------------------

    /** {@code SELECT DALYTRAN-FILE ASSIGN TO DALYTRAN} - {@code app/cbl/CBTRN01C.cbl:29}. */
    public static final String DALYTRAN_DD_NAME = "DALYTRAN";

    /** {@code SELECT CUSTOMER-FILE ASSIGN TO CUSTFILE} - {@code app/cbl/CBTRN01C.cbl:34}. */
    public static final String CUSTFILE_DD_NAME = "CUSTFILE";

    /** {@code SELECT XREF-FILE ASSIGN TO XREFFILE} - {@code app/cbl/CBTRN01C.cbl:40}. */
    public static final String XREFFILE_DD_NAME = "XREFFILE";

    /** {@code SELECT CARD-FILE ASSIGN TO CARDFILE} - {@code app/cbl/CBTRN01C.cbl:46}. */
    public static final String CARDFILE_DD_NAME = "CARDFILE";

    /** {@code SELECT ACCOUNT-FILE ASSIGN TO ACCTFILE} - {@code app/cbl/CBTRN01C.cbl:52}. */
    public static final String ACCTFILE_DD_NAME = "ACCTFILE";

    /** {@code SELECT TRANSACT-FILE ASSIGN TO TRANFILE} - {@code app/cbl/CBTRN01C.cbl:58}. */
    public static final String TRANFILE_DD_NAME = "TRANFILE";

    // -------------------------------------------------------------------------------------------------
    // Every DISPLAY literal, transcribed byte for byte. The alignment spaces inside 'ACCOUNT ID : ' and
    // 'CUSTOMER ID: ' are part of the text and are NOT tidied: the line sequence is this program's
    // entire observable output, so a single space is a parity difference.
    // -------------------------------------------------------------------------------------------------

    /** {@code app/cbl/CBTRN01C.cbl:156}. */
    public static final String START_BANNER = "START OF EXECUTION OF PROGRAM CBTRN01C";

    /** {@code app/cbl/CBTRN01C.cbl:195}. */
    public static final String END_BANNER = "END OF EXECUTION OF PROGRAM CBTRN01C";

    /** {@code app/cbl/CBTRN01C.cbl:263}. */
    public static final String ERROR_OPENING_DALYTRAN = "ERROR OPENING DAILY TRANSACTION FILE";

    /** {@code app/cbl/CBTRN01C.cbl:282}. */
    public static final String ERROR_OPENING_CUSTFILE = "ERROR OPENING CUSTOMER FILE";

    /** {@code app/cbl/CBTRN01C.cbl:300}. */
    public static final String ERROR_OPENING_XREFFILE = "ERROR OPENING CROSS REF FILE";

    /** {@code app/cbl/CBTRN01C.cbl:318}. */
    public static final String ERROR_OPENING_CARDFILE = "ERROR OPENING CARD FILE";

    /** {@code app/cbl/CBTRN01C.cbl:336}. */
    public static final String ERROR_OPENING_ACCTFILE = "ERROR OPENING ACCOUNT FILE";

    /** {@code app/cbl/CBTRN01C.cbl:354}. */
    public static final String ERROR_OPENING_TRANFILE = "ERROR OPENING TRANSACTION FILE";

    /** {@code app/cbl/CBTRN01C.cbl:219}. */
    public static final String ERROR_READING_DALYTRAN = "ERROR READING DAILY TRANSACTION FILE";

    /**
     * {@code app/cbl/CBTRN01C.cbl:372} and {@code :390} - <strong>the same literal at both sites</strong>.
     *
     * <p>{@code 9000-DALYTRAN-CLOSE} displays it for a failure closing the <em>daily transaction</em>
     * file and {@code 9100-CUSTFILE-CLOSE} displays it for the customer file. That is defect 2 of this
     * class's documentation, and one constant serving both sites is how it is stated: there is no
     * {@code 'ERROR CLOSING DAILY TRANSACTION FILE'} literal in the program to name.
     */
    public static final String ERROR_CLOSING_CUSTFILE = "ERROR CLOSING CUSTOMER FILE";

    /** {@code app/cbl/CBTRN01C.cbl:408}. */
    public static final String ERROR_CLOSING_XREFFILE = "ERROR CLOSING CROSS REF FILE";

    /** {@code app/cbl/CBTRN01C.cbl:426}. */
    public static final String ERROR_CLOSING_CARDFILE = "ERROR CLOSING CARD FILE";

    /** {@code app/cbl/CBTRN01C.cbl:444}. */
    public static final String ERROR_CLOSING_ACCTFILE = "ERROR CLOSING ACCOUNT FILE";

    /** {@code app/cbl/CBTRN01C.cbl:462}. */
    public static final String ERROR_CLOSING_TRANFILE = "ERROR CLOSING TRANSACTION FILE";

    /** {@code app/cbl/CBTRN01C.cbl:232}. */
    public static final String INVALID_CARD_NUMBER_FOR_XREF = "INVALID CARD NUMBER FOR XREF";

    /** {@code app/cbl/CBTRN01C.cbl:235}. */
    public static final String SUCCESSFUL_READ_OF_XREF = "SUCCESSFUL READ OF XREF";

    /** {@code app/cbl/CBTRN01C.cbl:236} - the prefix of the card-number line, with its trailing space. */
    public static final String XREF_CARD_NUMBER_PREFIX = "CARD NUMBER: ";

    /**
     * {@code app/cbl/CBTRN01C.cbl:237} - the prefix of the account-id line.
     *
     * <p>The space before the colon is in the source: the three prefixes are padded so the values line
     * up in the spool. Removing it would be a one-byte parity difference on every successful lookup.
     */
    public static final String XREF_ACCOUNT_ID_PREFIX = "ACCOUNT ID : ";

    /** {@code app/cbl/CBTRN01C.cbl:238}. */
    public static final String XREF_CUSTOMER_ID_PREFIX = "CUSTOMER ID: ";

    /** {@code app/cbl/CBTRN01C.cbl:246}. */
    public static final String INVALID_ACCOUNT_NUMBER_FOUND = "INVALID ACCOUNT NUMBER FOUND";

    /** {@code app/cbl/CBTRN01C.cbl:249}. */
    public static final String SUCCESSFUL_READ_OF_ACCOUNT_FILE = "SUCCESSFUL READ OF ACCOUNT FILE";

    /** The first operand of {@code DISPLAY 'ACCOUNT ' ACCT-ID ' NOT FOUND'} - {@code :178}. */
    public static final String ACCOUNT_NOT_FOUND_PREFIX = "ACCOUNT ";

    /** The third operand of {@code DISPLAY 'ACCOUNT ' ACCT-ID ' NOT FOUND'} - {@code :178}. */
    public static final String ACCOUNT_NOT_FOUND_SUFFIX = " NOT FOUND";

    /** The first operand of the card-not-verified message - {@code app/cbl/CBTRN01C.cbl:181}. */
    public static final String CARD_NOT_VERIFIED_PREFIX = "CARD NUMBER ";

    /**
     * The third operand of the card-not-verified message - {@code app/cbl/CBTRN01C.cbl:182}.
     *
     * <p>The message is a <strong>single</strong> {@code DISPLAY} with four operands spread over three
     * source lines, so it produces one line and not three. The trailing {@code ID-} runs straight into
     * {@code DALYTRAN-ID} with no separator, exactly as written.
     */
    public static final String CARD_NOT_VERIFIED_SUFFIX =
            " COULD NOT BE VERIFIED. SKIPPING TRANSACTION ID-";

    // -------------------------------------------------------------------------------------------------
    // WORKING-STORAGE values. app/cbl/CBTRN01C.cbl:142-151.
    // -------------------------------------------------------------------------------------------------

    /**
     * The value the open and close paragraphs seed {@code APPL-RESULT} with before attempting the verb -
     * {@code MOVE 8 TO APPL-RESULT} at {@code :253} and its five siblings, and
     * {@code ADD 8 TO ZERO GIVING APPL-RESULT} at {@code :362} and its five.
     *
     * <p>Dead in every one of the twelve paragraphs, because the very next test overwrites it on both
     * arms. Reproduced anyway: a statement present in the source is accounted for in the translation.
     */
    public static final int APPL_RESULT_ASSUMED_FAILURE = AbendException.RETURN_CODE_ASSUMED_FAILURE;

    /**
     * The value every failing arm moves into {@code APPL-RESULT}, and therefore the {@code RETURN-CODE}
     * every abend of this program carries: {@code MOVE 12 TO APPL-RESULT}.
     */
    public static final int APPL_RESULT_FATAL = AbendException.RETURN_CODE_IO_ERROR;

    /** {@code 01 END-OF-DAILY-TRANS-FILE PIC X(01) VALUE 'N'} - {@code app/cbl/CBTRN01C.cbl:146}. */
    public static final String NOT_AT_END_OF_FILE = "N";

    /** The value {@code :217} moves into it once the read reports end of file. */
    public static final String AT_END_OF_FILE = "Y";

    /**
     * The value {@code :170} and {@code :174} move into {@code WS-XREF-READ-STATUS} and
     * {@code WS-ACCT-READ-STATUS} before each lookup: a clean slate, tested as {@code = 0} at {@code :173}
     * and as {@code NOT = 0} at {@code :177}.
     */
    public static final int READ_STATUS_OK = 0;

    /**
     * The value the two {@code INVALID KEY} arms move into their read status - {@code MOVE 4 TO
     * WS-XREF-READ-STATUS} at {@code :233} and {@code MOVE 4 TO WS-ACCT-READ-STATUS} at {@code :247}.
     *
     * <p>Four and not twelve: an unfound cross reference or account is not fatal here. The program
     * reports it and reads the next daily transaction.
     */
    public static final int READ_STATUS_INVALID_KEY = 4;

    /**
     * The {@code XREF-ACCT-ID} a never-populated {@code CARD-XREF-RECORD} area yields: zero.
     *
     * <p>The declared value of a {@code PIC 9(11)} field, and the value {@code :175} therefore moves into
     * {@code ACCT-ID} on the one path that can reach it before any lookup has succeeded - a cross-reference
     * read whose status was neither success nor an invalid key, which executes neither of the
     * {@code READ}'s conditional phrases and so leaves {@code WS-XREF-READ-STATUS} at zero.
     */
    public static final long UNPOPULATED_ACCOUNT_ID = 0L;

    /**
     * The feedback code carried by a status this module reports for a dataset it could not reach at all.
     *
     * <p>{@code IO-STAT1 = '9'} is what {@code Z-DISPLAY-IO-STATUS} ({@code :477-483}) recognises as an
     * extended status: it renders the first byte verbatim and the second byte's numeric value as three
     * digits. A feedback code of zero therefore renders {@code FILE STATUS IS: NNNN9000}, which is the
     * form every repository in this module reports and every batch program's status line shows.
     */
    private static final char PERMANENT_ERROR_FEEDBACK_CODE = 0;

    /**
     * The two-character {@code FILE STATUS} this class reports for a dataset that could not be reached.
     *
     * <p>Byte-identical to the constant each repository publishes for the same condition. It is
     * reconstructed here rather than borrowed from one of them, because the one open that needs it -
     * {@code CARDFILE} - is reported by {@link CardRepository} as a CICS response rather than as a batch
     * status, and borrowing the daily-transaction repository's constant to describe the card file would
     * misattribute it.
     */
    public static final String PERMANENT_ERROR_STATUS = "9" + PERMANENT_ERROR_FEEDBACK_CODE;

    /**
     * The lowest possible {@code CARD-NUM} key: sixteen spaces.
     *
     * <p>{@code CBTRN01C} declares {@code CARD-FILE} as {@code ACCESS MODE IS RANDOM} ({@code :48}) and
     * never reads it, so it has no browse position of its own to reproduce. {@link CardRepository}'s open
     * seam is nonetheless a browse, which needs an anchor, and the lowest key is the honest one: it
     * positions where an {@code OPEN INPUT} of a keyed file positions, and no read ever follows.
     */
    public static final String LOWEST_CARD_NUMBER_KEY = " ".repeat(CardRecord.CARD_NUM_LENGTH);

    /** How many banner lines a run emits: the one at {@code :156} and the one at {@code :195}. */
    public static final int BANNER_LINES = 2;

    /**
     * How many {@code SYSOUT} lines one successful {@code 2000-LOOKUP-XREF} emits: {@code :235} through
     * {@code :238}.
     */
    public static final int XREF_SUCCESS_LINES = 4;

    /** Where a codec diagnostic says a line came from, when {@code SYSOUT} cannot encode it. */
    private static final String SYSOUT_SUBJECT = "a SYSOUT display line of " + PROGRAM_ID;

    /**
     * The line terminator {@code SYSOUT} writes.
     *
     * <p>One line feed, stated rather than taken from the platform separator, which would emit two bytes
     * on some hosts and one on others for the same program.
     */
    private static final char SYSOUT_LINE_TERMINATOR = '\n';

    /**
     * The logger, used only to record that a best-effort release of an already-failing run did not
     * succeed.
     *
     * <p>An immutable reference to a stateless collaborator, so it is not the mutable static state
     * practice B9 forbids. Nothing this program displays is ever logged through it: {@code SYSOUT} is the
     * program's output and a logger's layout is configuration.
     */
    private static final Log LOG = LogFactory.getLog(TransactionPostingJob.class);

    // =================================================================================================
    // Collaborators. All final, all constructor-injected, none holding per-run state.
    // =================================================================================================

    /** The batch seam: job and step builders, the job contracts and the dataset binding view. */
    private final BatchConfig batchConfig;

    /** DD {@value #DALYTRAN_DD_NAME} - the one file this program actually reads. */
    private final DalyTranRepository dalyTranRepository;

    /**
     * DD {@value #CUSTFILE_DD_NAME} - <strong>opened and closed, never read</strong>.
     *
     * <p>Injected so {@code 0100-CUSTFILE-OPEN} and {@code 9100-CUSTFILE-CLOSE} happen in their place in
     * the order, with their own status ladders and their own error texts. Those two verbs are the whole
     * of this file's involvement in the program, and they are observable: either can fail and abend.
     */
    private final CustomerRepository customerRepository;

    /**
     * DD {@value #XREFFILE_DD_NAME} - the {@code CCXREF} <strong>base</strong> cluster, read by the
     * sixteen-character card number.
     *
     * <p>Gate <strong>G45</strong>: the {@code CXACAIX} alternate-index path is a different access path in
     * a different key order and this program does not open it, so
     * {@link CardXrefRepository#readByAccountIdViaAltIndex(long)} is never called from here.
     * {@code :43} declares {@code RECORD KEY IS FD-XREF-CARD-NUM}, which {@code :78} declares as
     * {@code PIC X(16)} - character data, so a card number with a leading zero compares as the sixteen
     * characters it is.
     */
    private final CardXrefRepository cardXrefRepository;

    /** DD {@value #CARDFILE_DD_NAME} - <strong>opened and closed, never read</strong>. */
    private final CardRepository cardRepository;

    /** DD {@value #ACCTFILE_DD_NAME} - read by the cross-referenced account id, and never written. */
    private final AccountRepository accountRepository;

    /** DD {@value #TRANFILE_DD_NAME} - <strong>opened and closed, never read and never written</strong>. */
    private final TransactionRepository transactionRepository;

    /**
     * The codec for the dataset code page.
     *
     * <p>Its only job here is to render the three numeric display fields at their declared widths:
     * {@code XREF-ACCT-ID} and {@code ACCT-ID} as eleven digits, {@code XREF-CUST-ID} as nine, and the
     * card number as sixteen characters. Its constructor rejects a code page that cannot hold a digit or a
     * space in one byte, so a misconfigured charset fails at start-up rather than at the first record.
     */
    private final FixedWidthCodec codec;

    /**
     * Where {@code SYSOUT} goes when the caller does not say.
     *
     * <p>Held as a provider rather than resolved once, so a deployment that publishes a {@link SysoutSink}
     * bean is honoured without this class needing one to exist, and the context still starts when none
     * does.
     */
    private final ObjectProvider<SysoutSink> sysoutSinkProvider;

    /**
     * This job's validated step contract, resolved at construction so a mis-declared contract fails at
     * start-up rather than when the job is first launched.
     */
    private final StepContract stepContract;

    /**
     * Wires the job and proves, at start-up, that configuration still describes {@value #PROGRAM_ID}.
     *
     * <p>Six guards run beyond the presence of the collaborators, and none of them is ceremony. Each
     * rejects a configuration that would make this job run the wrong step, accept an input the program has
     * no field to receive, or read the wrong bytes - and each fails now, when an operator can act on the
     * message, rather than in the middle of a run.
     *
     * <ul>
     *   <li>The contract names {@value #PROGRAM_ID}, at the job and at its step.</li>
     *   <li>It declares <strong>no parameter</strong>. There is no JCL for this program, so there is no
     *       {@code PARM} and no {@code LINKAGE SECTION} to receive one; the empty list is the contract.</li>
     *   <li>Its single step is ungated, and the sequence is exactly {@link #REQUIRED_STEPS}.</li>
     *   <li>All six DDs bind a record width that agrees with the copybook the program's record area is
     *       declared from, and a dataset name that can actually be addressed.</li>
     *   <li>The three DDs whose repository resolves its own global binding - {@value #DALYTRAN_DD_NAME},
     *       {@value #CUSTFILE_DD_NAME} and {@value #ACCTFILE_DD_NAME} - resolve to the same dataset this
     *       job's DD does, because those repositories have no job-scoped seam to be re-pointed through
     *       and a step reading a dataset its own DD never named would do so silently.</li>
     * </ul>
     *
     * @param batchConfig           the batch seam supplying builders, contracts and dataset bindings;
     *                              never {@code null}
     * @param dalyTranRepository    DD {@value #DALYTRAN_DD_NAME}, the sequential input; never {@code null}
     * @param customerRepository    DD {@value #CUSTFILE_DD_NAME}, opened and closed only; never
     *                              {@code null}
     * @param cardXrefRepository    DD {@value #XREFFILE_DD_NAME}, the {@code CCXREF} base cluster; never
     *                              {@code null}
     * @param cardRepository        DD {@value #CARDFILE_DD_NAME}, opened and closed only; never
     *                              {@code null}
     * @param accountRepository     DD {@value #ACCTFILE_DD_NAME}, read by key; never {@code null}
     * @param transactionRepository DD {@value #TRANFILE_DD_NAME}, opened and closed only; never
     *                              {@code null}
     * @param sysoutSinkProvider    provider for a deployment-supplied {@code SYSOUT} destination; never
     *                              {@code null}, though it may resolve to nothing, in which case
     *                              {@link #defaultSysoutSink()} is used
     * @throws NullPointerException  if any argument is {@code null}
     * @throws IllegalStateException if the contract is absent, names another program, declares a
     *                               parameter, gates the step, declares any other step sequence, or binds
     *                               a DD to an unusable dataset or to a record width that contradicts its
     *                               copybook
     */
    public TransactionPostingJob(
            BatchConfig batchConfig,
            DalyTranRepository dalyTranRepository,
            CustomerRepository customerRepository,
            CardXrefRepository cardXrefRepository,
            CardRepository cardRepository,
            AccountRepository accountRepository,
            TransactionRepository transactionRepository,
            ObjectProvider<SysoutSink> sysoutSinkProvider) {

        this.batchConfig = Objects.requireNonNull(batchConfig, "The batch configuration seam is required: "
                + "it supplies the job repository, the transaction manager, the abend listeners, the job "
                + "contracts and the dataset bindings, so no job class assembles Spring Batch plumbing or "
                + "resolves a dataset name of its own");
        this.dalyTranRepository = Objects.requireNonNull(dalyTranRepository, "The daily-transaction "
                + "repository is required: app/cbl/CBTRN01C.cbl:29 assigns DD " + DALYTRAN_DD_NAME
                + ", and it is the one file this program reads");
        this.customerRepository = Objects.requireNonNull(customerRepository, "The customer repository is "
                + "required even though this program never reads the customer file: 0100-CUSTFILE-OPEN and "
                + "9100-CUSTFILE-CLOSE are two of its twelve file verbs, both can fail and abend, and both "
                + "are part of its observable behaviour");
        this.cardXrefRepository = Objects.requireNonNull(cardXrefRepository, "The card cross-reference "
                + "repository is required: 2000-LOOKUP-XREF reads the CCXREF base cluster by the "
                + "sixteen-character card number");
        this.cardRepository = Objects.requireNonNull(cardRepository, "The card repository is required even "
                + "though this program never reads the card file: 0300-CARDFILE-OPEN and "
                + "9300-CARDFILE-CLOSE are two of its twelve file verbs");
        this.accountRepository = Objects.requireNonNull(accountRepository, "The account repository is "
                + "required: 3000-READ-ACCOUNT reads it by the cross-referenced account id. It is read "
                + "only - the program issues no WRITE and no REWRITE anywhere");
        this.transactionRepository = Objects.requireNonNull(transactionRepository, "The transaction "
                + "repository is required even though this program never reads or writes the transaction "
                + "file: 0500-TRANFILE-OPEN and 9500-TRANFILE-CLOSE are its last two file verbs, and the "
                + "posting this job's name promises is exactly the write it never issues");
        this.sysoutSinkProvider = Objects.requireNonNull(sysoutSinkProvider, "A SysoutSink provider is "
                + "required: DISPLAY output is emitted through an injected sink so it can be captured and "
                + "compared, never written straight to a stream from the program body");

        // The dataset code page is taken from a repository this job already depends on rather than
        // injected by bean name, which keeps this file's imports exactly its declared dependency set.
        // Every repository in the module is constructed from the one CobolCharsetConfig bean, so they all
        // report the same code page - and each has already refused a code page that is not single byte.
        this.codec = new FixedWidthCodec(dalyTranRepository.datasetCharset());

        JobContract contract = batchConfig.contract(JOB_KEY);
        requireProgram(contract.program(), "carddemo.jobs." + JOB_KEY + ".program");
        requireNoJobParameters(contract);

        StepContract step = contract.step(STEP_NAME);
        requireProgram(step.program(),
                "carddemo.jobs." + JOB_KEY + ".steps[" + STEP_NAME + "].program");
        requireUngatedStep(step);
        // The three checks above examine one field each of one step, and none can see a second step
        // declared beside it or a different step declared first. This job runs exactly one step, so the
        // sequence is compared whole - one comparison covering the cardinality and the order the
        // field-level checks are structurally blind to.
        batchConfig.requireSteps(JOB_KEY, REQUIRED_STEPS,
                "app/cbl/CBTRN01C.cbl:155 - the program's single MAIN-PARA; no JCL declares an EXEC card "
                        + "for it, so the step name is this module's own and configuration owns it");
        this.stepContract = step;

        // Each DD is read for the two facts this job needs - which dataset, and how wide its records are -
        // and neither the binding nor any statement built from it is retained. The width is checked against
        // the copybook the program's own record area is declared from, because this module addresses every
        // record by absolute offset: a differently-sized record would misplace every field after the first.
        requireDatasetGeometry(DALYTRAN_DD_NAME, DalyTranRecord.RECORD_LENGTH, "app/cpy/CVTRA06Y.cpy");
        requireDatasetGeometry(CUSTFILE_DD_NAME, CustomerRecord.RECORD_LENGTH, "app/cpy/CVCUS01Y.cpy");
        requireDatasetGeometry(XREFFILE_DD_NAME, CardXrefRecord.RECORD_LENGTH, "app/cpy/CVACT03Y.cpy");
        requireDatasetGeometry(CARDFILE_DD_NAME, CardRecord.RECORD_LENGTH, "app/cpy/CVACT02Y.cpy");
        requireDatasetGeometry(ACCTFILE_DD_NAME, AccountRecord.RECORD_LENGTH, "app/cpy/CVACT01Y.cpy");
        requireDatasetGeometry(TRANFILE_DD_NAME, TranRecord.RECORD_LENGTH, "app/cpy/CVTRA05Y.cpy");

        // Three of the six repositories resolve their dataset from the global catalogue at construction and
        // publish no seam for re-pointing one per job, so for those three the job's DD and the repository's
        // binding must already agree. The other three are handed this job's own resolved binding at the
        // point of use - see openXreffile, openCardfile and openTranfile - so a job-scoped override is
        // honoured there rather than forbidden.
        batchConfig.requireSameDataset(JOB_KEY, DALYTRAN_DD_NAME, DalyTranRepository.DD_NAME);
        batchConfig.requireSameDataset(JOB_KEY, CUSTFILE_DD_NAME, CustomerRepository.BATCH_DD_NAME);
        batchConfig.requireSameDataset(JOB_KEY, ACCTFILE_DD_NAME, AccountRepository.BATCH_DD_NAME);
    }

    // =================================================================================================
    // Constructor guards. Each is a function of its arguments, so each is reachable from a plain unit test
    // with a hand-built contract, and each message names the configuration key an operator must correct.
    // =================================================================================================

    /**
     * Requires a configured program name to be the program this class translates.
     *
     * <p>A contract naming another program would run this job's step body against another program's DD
     * bindings, which is the kind of mismatch that produces plausible output from the wrong dataset.
     *
     * @param configured the program name configuration declares
     * @param key        the configuration key it was read from, for the diagnostic
     * @throws IllegalStateException if it is not {@value #PROGRAM_ID}
     */
    private static void requireProgram(String configured, String key) {
        if (!PROGRAM_ID.equals(configured)) {
            throw new IllegalStateException(key + " is '" + configured + "', but "
                    + TransactionPostingJob.class.getSimpleName() + " translates " + PROGRAM_ID
                    + " (app/cbl/CBTRN01C.cbl). Correct the configured program name; do not repoint this "
                    + "class.");
        }
    }

    /**
     * Requires the configured contract to declare no job parameter.
     *
     * <p>{@value #PROGRAM_ID} is invoked by no JCL anywhere, so there is no {@code EXEC PGM=} card and no
     * {@code PARM} to translate, and the program declares no {@code LINKAGE SECTION} to receive one. A
     * declared parameter would be a value the program cannot read - and, because Spring Batch identifies
     * an instance by its parameters, it would silently change which instance a submission resolves to.
     *
     * @param contract the configured contract
     * @throws IllegalStateException if any parameter is declared
     */
    private static void requireNoJobParameters(JobContract contract) {
        if (!contract.parameters().isEmpty()) {
            throw new IllegalStateException("carddemo.jobs." + JOB_KEY + ".parameters declares "
                    + contract.parameters().size() + " parameter(s), but " + PROGRAM_ID + " is invoked by "
                    + "no JCL anywhere - grep -rn \"" + PROGRAM_ID + "\" app/jcl/ app/proc/ app/csd/ "
                    + "returns nothing - so there is no PARM to translate and the program declares no "
                    + "LINKAGE SECTION. The empty list is the contract; declare no parameter here.");
        }
    }

    /**
     * Requires the configured step to be ungated.
     *
     * <p>{@code COND=(0,NE)} appears on three steps of {@code app/jcl/CREASTMT.JCL} and nowhere else in
     * this estate. Gating the first step of a single-step job would stop it running at all - and for a
     * program no JCL invokes there is not even a preceding step whose exit code could be tested.
     *
     * @param step the configured step contract
     * @throws IllegalStateException if the step requires a preceding zero exit code
     */
    private static void requireUngatedStep(StepContract step) {
        if (step.requirePrecedingExitCodeZero()) {
            throw new IllegalStateException("carddemo.jobs." + JOB_KEY + ".steps[" + step.name()
                    + "].require-preceding-exit-code-zero is true, but this job declares one step and "
                    + PROGRAM_ID + " has no JCL, so there is no COND to reproduce and no preceding step to "
                    + "test. Gating the first step of a single-step job would stop it running at all.");
        }
    }

    /**
     * Requires a DD to bind a dataset that can be addressed and whose record width agrees with the
     * copybook the program's record area is declared from.
     *
     * <p>Two failure modes, and both are silent without this check. A blank dataset name is what an unset
     * environment-variable reference resolves to, after which every access addresses nothing. A record
     * width that disagrees with the copybook misplaces every field after the first, because this module
     * addresses records by absolute offset - and the result looks like data rather than like an error.
     *
     * @param ddName          the DD name, which is also the {@code carddemo.datasets} key
     * @param copybookLength  the record length the copybook declares
     * @param copybookPath    the copybook that declares it, for the diagnostic
     * @throws IllegalStateException if the width disagrees or the dataset name is absent or blank
     */
    private void requireDatasetGeometry(String ddName, int copybookLength, String copybookPath) {
        // The type is inferred rather than named: DatasetBinding is declared by DataSourceConfig, which is
        // not among this file's declared dependencies, and inferring it keeps the import list exactly the
        // dependency set. Nothing is retained - the binding is read for two facts and discarded.
        var binding = batchConfig.datasetBinding(JOB_KEY, ddName);
        if (binding.recordLength() != copybookLength) {
            throw new IllegalStateException("carddemo.datasets." + ddName + ".record-length is "
                    + binding.recordLength() + ", but " + copybookPath + " declares " + copybookLength
                    + " bytes and " + PROGRAM_ID + " declares its record area from that copybook. This "
                    + "module addresses every record by absolute offset, so a differently-sized record "
                    + "would misplace every field after the first. Correct carddemo.datasets." + ddName
                    + ".record-length to " + copybookLength + ".");
        }
        String dsname = binding.dsname();
        if (dsname == null || dsname.isBlank()) {
            throw new IllegalStateException("carddemo.datasets." + ddName + ".dsname is "
                    + (dsname == null ? "not declared" : "blank") + ", so DD " + ddName + " names no "
                    + "dataset for " + PROGRAM_ID + " to open. Supply that dataset, or the environment "
                    + "variable the binding defers to.");
        }
    }

    // =================================================================================================
    // The Spring Batch assembly: one job, one step, one tasklet. There is no flow to build, because
    // there is one step, and no decider to place, because there is no COND to reproduce.
    //
    // GATE G13 IS ASSERTED AGAINST WHAT IS - AND IS NOT - IN THIS SECTION. The Job below is real and
    // launchable by name. Nothing in this file schedules it, runs it at start-up or wires it into another
    // job's flow: there is no @Scheduled, no CommandLineRunner, no ApplicationRunner and no JobLauncher
    // anywhere in this class. Publishing a Job bean does not run it - spring.batch.job.enabled is false -
    // and this class does not override that.
    // =================================================================================================

    /**
     * The job, published as a bean under {@value #JOB_NAME}: one step, no parameters, no gating, no
     * trigger.
     *
     * <p>The builder arrives from {@link BatchConfig#job(String)} already carrying the auto-configured job
     * repository, both shared return-code listeners, the run-identity incrementer, the parameter validator
     * and the non-restartable policy - which is what makes the {@code RETURN-CODE} contract of gate
     * <strong>G35</strong> hold for this job by construction rather than by opting in.
     *
     * <p><strong>Launchable, and launched by nothing.</strong> The bean exists so that this program - the
     * migration's only orphan - is as runnable as its eight siblings, which is what implicit requirement
     * <strong>I4</strong> asks for. It is reached only when something outside this class deliberately
     * launches {@value #JOB_NAME} with the empty {@link #jobParameters()} the contract declares.
     *
     * @return the {@value #JOB_NAME} job; never {@code null}
     */
    @Bean(JOB_NAME)
    public Job transactionPostingJob() {
        return batchConfig.job(JOB_NAME)
                .start(transactionPostingStep())
                .build();
    }

    /**
     * The single step, named {@value #STEP_NAME} as this job's contract declares.
     *
     * <p>A {@link Tasklet} step, and never a chunk-oriented one. {@value #PROGRAM_ID} opens six files,
     * makes one pass over the daily transaction file and closes six files, emitting its lines in strict
     * order; and one iteration carries state into the next - the stale card number of defect 1 is exactly
     * that. Chunking would relocate the commit boundaries that pass sits inside.
     *
     * <p>The name is read from the validated {@linkplain #stepContract() contract} rather than from the
     * constant, so the name that reaches the batch metadata is demonstrably the name configuration
     * declares.
     *
     * <p>Not a bean. Nine sibling job classes exist in this module, and a published {@link Step} bean from
     * each would make every by-type injection of that interface ambiguous at once.
     *
     * @return a fresh step; never {@code null}
     */
    public Step transactionPostingStep() {
        return batchConfig.taskletStep(stepContract.name(), transactionPostingTasklet()).build();
    }

    /**
     * The step body: one invocation, one complete execution of the procedure division.
     *
     * <p>It holds no logic of its own. It resolves the {@code SYSOUT} destination, runs
     * {@link #execute(SysoutSink, StopSignal)}, and reports the record count to the framework as step
     * metadata - {@value #PROGRAM_ID} keeps no counter of its own, so the count is never displayed.
     *
     * <p>The chunk context is read for one thing: this step execution's {@link StopSignal}, which the pass
     * consults between records. A tasklet that runs once is checked for interruption once by the
     * framework, at the top, so a stop requested during a 300-record pass would otherwise not be seen
     * until the pass had finished.
     *
     * <p>Nothing is caught. An {@link AbendException} must reach the framework so {@code BatchConfig}'s
     * listeners can carry its {@code RETURN-CODE} onto the step's exit status and the process exit code;
     * swallowing it would report a failed job as complete.
     *
     * <p>Not a bean, for the same reason the step is not.
     *
     * @return a tasklet that runs the program exactly once per step execution; never {@code null}
     */
    public Tasklet transactionPostingTasklet() {
        return (contribution, chunkContext) -> {
            ExecutionSummary summary = execute(resolveSysoutSink(), StopSignal.of(chunkContext));
            for (int recorded = 0; recorded < summary.recordsRead(); recorded++) {
                contribution.incrementReadCount();
            }
            return RepeatStatus.FINISHED;
        };
    }

    /**
     * The job parameters this job is launched with: <strong>none</strong>.
     *
     * <p>Read from the contract rather than built here, so the empty set configuration declares is
     * demonstrably what reaches the launcher. {@value #PROGRAM_ID} has no {@code EXEC PGM=} card anywhere,
     * so there is no {@code PARM} to translate.
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

    // =================================================================================================
    // THE PROGRAM. Reachable with no JobLauncher, no application context and no HTTP in the path, which
    // is what lets every branch below be driven from a plain unit test and what the twenty parity cases
    // for this program run against (practice B10, gate G51).
    // =================================================================================================

    /**
     * Runs {@value #PROGRAM_ID} once: open six files, read the daily transaction file to end of file
     * reporting each record, close six files.
     *
     * <p>{@code MAIN-PARA}, and the Java below it, in the same order:
     *
     * <pre>
     * DISPLAY 'START OF EXECUTION OF PROGRAM CBTRN01C'.               :156
     * PERFORM 0000-DALYTRAN-OPEN.                                     :157
     * PERFORM 0100-CUSTFILE-OPEN.                                     :158
     * PERFORM 0200-XREFFILE-OPEN.                                     :159
     * PERFORM 0300-CARDFILE-OPEN.                                     :160
     * PERFORM 0400-ACCTFILE-OPEN.                                     :161
     * PERFORM 0500-TRANFILE-OPEN.                                     :162
     * PERFORM UNTIL END-OF-DAILY-TRANS-FILE = 'Y'                     :164
     *     IF  END-OF-DAILY-TRANS-FILE = 'N'                           :165
     *         PERFORM 1000-DALYTRAN-GET-NEXT                          :166
     *         IF  END-OF-DAILY-TRANS-FILE = 'N'                       :167
     *             DISPLAY DALYTRAN-RECORD                             :168
     *         END-IF                                                  :169
     *         MOVE 0                 TO WS-XREF-READ-STATUS           :170
     *         MOVE DALYTRAN-CARD-NUM TO XREF-CARD-NUM                 :171
     *         PERFORM 2000-LOOKUP-XREF                                :172
     *         IF WS-XREF-READ-STATUS = 0                              :173
     *           MOVE 0            TO WS-ACCT-READ-STATUS              :174
     *           MOVE XREF-ACCT-ID TO ACCT-ID                          :175
     *           PERFORM 3000-READ-ACCOUNT                             :176
     *           IF WS-ACCT-READ-STATUS NOT = 0                        :177
     *               DISPLAY 'ACCOUNT ' ACCT-ID ' NOT FOUND'           :178
     *           END-IF                                                :179
     *         ELSE                                                    :180
     *           DISPLAY 'CARD NUMBER ' DALYTRAN-CARD-NUM              :181
     *           ' COULD NOT BE VERIFIED. SKIPPING TRANSACTION ID-'    :182
     *           DALYTRAN-ID                                           :183
     *         END-IF                                                  :184
     *     END-IF                                                      :185
     * END-PERFORM.                                                    :186
     * PERFORM 9000-DALYTRAN-CLOSE.                                    :188
     * PERFORM 9100-CUSTFILE-CLOSE.                                    :189
     * PERFORM 9200-XREFFILE-CLOSE.                                    :190
     * PERFORM 9300-CARDFILE-CLOSE.                                    :191
     * PERFORM 9400-ACCTFILE-CLOSE.                                    :192
     * PERFORM 9500-TRANFILE-CLOSE.                                    :193
     * DISPLAY 'END OF EXECUTION OF PROGRAM CBTRN01C'.                 :195
     * GOBACK.                                                         :197
     * </pre>
     *
     * <p>Three properties of that loop look like mistakes. One is; two are not.
     *
     * <ul>
     *   <li><strong>The guard at {@code :165} is redundant.</strong> The {@code PERFORM UNTIL} condition
     *       already establishes it and the flag holds only {@code 'N'} or {@code 'Y'}, so its false path is
     *       unreachable. Reproduced rather than removed, which is why a coverage report shows it as a
     *       half-taken branch.</li>
     *   <li><strong>The lookup at {@code :170-172} is outside the guard at {@code :167-169}.</strong> This
     *       is defect 1, and it is the reason the loop runs one more lookup than there are records. See
     *       this class's documentation.</li>
     *   <li><strong>Nothing writes.</strong> No repository write method is called from anywhere below,
     *       because {@value #PROGRAM_ID} contains no {@code WRITE} and no {@code REWRITE}.</li>
     * </ul>
     *
     * @param sysout where the {@code DISPLAY} lines go; never {@code null}
     * @return the return code the program left, and what the run read and looked up
     * @throws NullPointerException if {@code sysout} is {@code null}
     * @throws AbendException       if any of the twelve file verbs, or the read, reports a status the
     *                              program treats as fatal - carrying {@code RETURN-CODE}
     *                              {@value #APPL_RESULT_FATAL}, {@code ABCODE} 999 and {@code TIMING} 0,
     *                              exactly as {@code Z-ABEND-PROGRAM} sets them before
     *                              {@code CALL 'CEE3ABD'}
     */
    public ExecutionSummary execute(SysoutSink sysout) {
        return execute(sysout, StopSignal.RUNNING);
    }

    /**
     * Runs {@value #PROGRAM_ID}, yielding to the given stop signal between records.
     *
     * <p>The pass is identical to {@link #execute(SysoutSink)} - same verbs, same order, same lines - and
     * the signal changes nothing while no stop is pending. It exists because this program is one pass over
     * a 300-record dataset inside a single tasklet invocation, so the framework's interruption check at the
     * step's repeat boundary happens once and cannot end a pass already under way. The probe is consulted
     * between records, where the record in flight is always complete, and nothing is retried.
     *
     * @param sysout     where the {@code DISPLAY} lines go; never {@code null}
     * @param stopSignal the between-record cancellation probe; {@link StopSignal#RUNNING} for a caller
     *                   outside a step; never {@code null}
     * @return the return code the program left, and what the run read and looked up
     * @throws NullPointerException if {@code sysout} or {@code stopSignal} is {@code null}
     * @throws AbendException       if any file verb or the read reports a fatal status
     * @throws BatchConfig.StopRequestedException if the step is asked to stop, which abandons the pass
     *                              between records
     */
    public ExecutionSummary execute(SysoutSink sysout, StopSignal stopSignal) {
        Objects.requireNonNull(sysout, "A SYSOUT sink is required to run " + PROGRAM_ID + ": the program's "
                + "observable output is its DISPLAY lines, so there is nothing to run without somewhere to "
                + "put them");
        Objects.requireNonNull(stopSignal, "A stop signal is required; pass StopSignal.RUNNING outside a "
                + "step, which is what the single-argument overload does");
        return new Execution(sysout).run(stopSignal);
    }

    // =================================================================================================
    // One run of the procedure division.
    //
    // Every item of app/cbl/CBTRN01C.cbl's WORKING-STORAGE is a field of THIS class and never of the
    // enclosing configuration bean, which is a singleton: two concurrent runs must not be able to see each
    // other's END-OF-DAILY-TRANS-FILE flag, read statuses or record areas (practice B9, gate G53). An
    // instance is created by execute(...) and discarded when it returns.
    // =================================================================================================

    /** One execution of {@value #PROGRAM_ID}: its {@code WORKING-STORAGE}, its file handles and its body. */
    private final class Execution {

        /** Where every {@code DISPLAY} of this run goes. */
        private final SysoutSink sysout;

        // -------------------------------------------------------------------------------------------
        // WORKING-STORAGE - app/cbl/CBTRN01C.cbl:96-151.
        // -------------------------------------------------------------------------------------------

        /** {@code 01 END-OF-DAILY-TRANS-FILE PIC X(01) VALUE 'N'} - {@code :146}. */
        private String endOfDailyTransFile = NOT_AT_END_OF_FILE;

        /** {@code 01 APPL-RESULT PIC S9(9) COMP} - {@code :142}, with its two {@code 88}-levels. */
        private int applResult;

        /**
         * {@code 01 IO-STATUS} - {@code :129}. The two characters a failing arm moves the offending file's
         * status into, immediately before {@code Z-DISPLAY-IO-STATUS} renders them.
         */
        private String ioStatus;

        /** {@code 05 WS-XREF-READ-STATUS PIC 9(04)} - {@code :150}. */
        private int xrefReadStatus;

        /** {@code 05 WS-ACCT-READ-STATUS PIC 9(04)} - {@code :151}. */
        private int acctReadStatus;

        /**
         * {@code 01 DALYTRAN-RECORD} - {@code COPY CVTRA06Y} at {@code :99}.
         *
         * <p>Initialised to a declared-value record area rather than left absent, because that is what the
         * program has before its first read and the loop can reach {@code MOVE DALYTRAN-CARD-NUM TO
         * XREF-CARD-NUM} without one: an empty dataset ends the very first read, and the lookup of defect 1
         * then runs on this untouched area. A {@code READ ... INTO} at {@code AT END} leaves the area alone,
         * so this field is replaced only by a successful read.
         */
        private DalyTranRecord dalytranRecord = new DalyTranRecord(codec.charset());

        /**
         * The {@code XREF-CARD-NUM PIC X(16)} span of {@code CARD-XREF-RECORD} - {@code COPY CVACT03Y} at
         * {@code :109}.
         *
         * <p>Held apart from {@link #cardXrefRecord} because the program writes it independently of the rest
         * of the area: {@code :171} moves the daily transaction's card number into it, and only a successful
         * {@code READ ... INTO} at {@code :229} overwrites the whole area including it. Sixteen spaces to
         * begin with, which is the area's state before anything has been moved into it.
         */
        private String xrefCardNum = " ".repeat(CardXrefRecord.XREF_CARD_NUM_LENGTH);

        /**
         * The rest of {@code CARD-XREF-RECORD}: the {@code XREF-CUST-ID} and {@code XREF-ACCT-ID} a
         * successful {@code READ ... INTO} placed there.
         *
         * <p>{@code null} until the first successful lookup, which models the one thing that matters about
         * the untouched area: the program cannot have read a cross reference from it. Both fields are
         * displayed only on the arm that has just populated them.
         */
        private CardXrefRecord cardXrefRecord;

        /**
         * The {@code ACCT-ID PIC 9(11)} span of {@code ACCOUNT-RECORD} - {@code COPY CVACT01Y} at
         * {@code :119} - as the eleven characters {@code DISPLAY} writes.
         *
         * <p>{@code :175} moves {@code XREF-ACCT-ID} into it and {@code :178} displays it. Eleven zeros to
         * begin with, which is a {@code PIC 9} area's declared value.
         */
        private String acctId = "0".repeat(AccountRecord.KEY_LENGTH);

        /** The same value as {@link #acctId}, as the number the keyed read is issued with. */
        private long acctIdKey;

        // -------------------------------------------------------------------------------------------
        // The six FILE STATUS fields - app/cbl/CBTRN01C.cbl:100-127. Each open, each close and the one
        // read moves its own file's status into its own field, and a failing arm then copies that field
        // into IO-STATUS. Keeping six fields rather than one is what lets 9000-DALYTRAN-CLOSE's defect be
        // reproduced: it copies the CUSTOMER file's field after closing the DAILY TRANSACTION file.
        // -------------------------------------------------------------------------------------------

        /** {@code 01 DALYTRAN-STATUS} - {@code :100}. */
        private String dalytranStatus;

        /** {@code 01 CUSTFILE-STATUS} - {@code :105}. */
        private String custfileStatus;

        /** {@code 01 XREFFILE-STATUS} - {@code :110}. */
        private String xreffileStatus;

        /** {@code 01 CARDFILE-STATUS} - {@code :115}. */
        private String cardfileStatus;

        /** {@code 01 ACCTFILE-STATUS} - {@code :120}. */
        private String acctfileStatus;

        /** {@code 01 TRANFILE-STATUS} - {@code :125}. */
        private String tranfileStatus;

        // -------------------------------------------------------------------------------------------
        // The six open file handles. Not COBOL state - a COBOL program addresses a file by name - but the
        // Java equivalent of "this file is open", and per-run for the same reason every field here is.
        // -------------------------------------------------------------------------------------------

        /** The {@value #DALYTRAN_DD_NAME} sequential pass, the only handle this run ever reads from. */
        private DalyTranRepository.DalytranFile dalytranFile;

        /** The {@value #CUSTFILE_DD_NAME} handle: opened, closed, never read. */
        private CustomerRepository.CustomerFile custfile;

        /**
         * The {@value #XREFFILE_DD_NAME} handle.
         *
         * <p>{@link CardXrefRepository#openBrowse()} is used purely as the {@code OPEN INPUT} seam: it
         * establishes that the dataset is there and reports a {@code FILE STATUS} for it, which is what
         * {@code :291-296} tests. {@code CBTRN01C} declares this file {@code ACCESS MODE IS RANDOM}
         * ({@code :42}), so {@code readNext()} is never called on this cursor - every read of this dataset
         * is the keyed {@link CardXrefRepository#readByCardNumber(String)} of
         * {@code 2000-LOOKUP-XREF}.
         */
        private CardXrefRepository.BrowseCursor xreffile;

        /** The {@value #CARDFILE_DD_NAME} handle: opened, closed, never read. */
        private CardRepository.CardBrowse cardfile;

        /** The {@value #ACCTFILE_DD_NAME} handle, opened {@code INPUT} and read by key. */
        private AccountRepository.AccountFile acctfile;

        /**
         * The {@value #TRANFILE_DD_NAME} handle: opened, closed, <strong>never read and never
         * written</strong>.
         *
         * <p>{@code TransactionRepository.openInput(binding)} models a sequential input pass while
         * {@code :60} declares this file {@code ACCESS MODE IS RANDOM}. The difference is unobservable
         * here, and only here: the sole behaviours this program exposes for this file are the status its
         * open reports and the status its close reports, and those are the same for either access mode. No
         * read follows, so no ordering can differ.
         */
        private TransactionRepository.InputFile tranfile;

        // -------------------------------------------------------------------------------------------
        // Close-ladder bookkeeping. Whether each of the six CLOSE statements was reached, which is what
        // the best-effort release consults - the run's own control flow, not a handle's self-reported
        // state, because "exactly one CLOSE per file per run" is a property of the control flow.
        // -------------------------------------------------------------------------------------------

        /** Whether {@code 9000-DALYTRAN-CLOSE} was reached. */
        private boolean dalytranCloseIssued;

        /** Whether {@code 9100-CUSTFILE-CLOSE} was reached. */
        private boolean custfileCloseIssued;

        /** Whether {@code 9200-XREFFILE-CLOSE} was reached. */
        private boolean xreffileCloseIssued;

        /** Whether {@code 9300-CARDFILE-CLOSE} was reached. */
        private boolean cardfileCloseIssued;

        /** Whether {@code 9400-ACCTFILE-CLOSE} was reached. */
        private boolean acctfileCloseIssued;

        /** Whether {@code 9500-TRANFILE-CLOSE} was reached. */
        private boolean tranfileCloseIssued;

        // -------------------------------------------------------------------------------------------
        // Counters and carried diagnostics. Step metadata and diagnosis only: CBTRN01C keeps no counter of
        // its own and displays none of these.
        // -------------------------------------------------------------------------------------------

        /** How many daily transaction records the pass read successfully. */
        private int recordsRead;

        /**
         * How many times {@code 2000-LOOKUP-XREF} ran, which is always {@link #recordsRead} plus one.
         *
         * <p>The plus one is defect 1, counted rather than asserted so a test can state it as a number.
         */
        private int xrefLookups;

        /** How many times {@code 3000-READ-ACCOUNT} ran. */
        private int accountReads;

        /**
         * A backend refusal that a file verb reported as a status rather than propagating, carried so it can
         * become the abend's cause.
         *
         * <p>Nothing is swallowed: the verb reports {@link TransactionPostingJob#PERMANENT_ERROR_STATUS},
         * the guard chain abends one line later, and the original failure travels out attached to the
         * abend.
         */
        private RuntimeException datasetRefusal;

        /**
         * The cross-reference repository rebased onto <strong>this job's</strong>
         * {@value #XREFFILE_DD_NAME} binding, resolved by the open and used by every keyed read.
         *
         * <p>The injected repository resolved {@link CardXrefRepository#BASE_DD_NAME} - the CICS name for
         * the same dataset - from the global catalogue. Rebasing makes the DD this job declares the DD it
         * actually reads, so a job-scoped override is honoured rather than silently ignored.
         */
        private CardXrefRepository xrefAccess;

        /**
         * @param sysout where this run's {@code DISPLAY} lines go
         */
        private Execution(SysoutSink sysout) {
            this.sysout = sysout;
        }

        // -------------------------------------------------------------------------------------------
        // MAIN-PARA - app/cbl/CBTRN01C.cbl:155-197.
        // -------------------------------------------------------------------------------------------

        /**
         * Runs the procedure division once. See {@link TransactionPostingJob#execute(SysoutSink)} for the
         * transcribed mainline and for the two defects this reproduces.
         *
         * @param stopSignal the between-record cancellation probe
         * @return what the run produced
         * @throws AbendException if any file verb or the read reports a fatal status
         */
        private ExecutionSummary run(StopSignal stopSignal) {
            // :156  DISPLAY 'START OF EXECUTION OF PROGRAM CBTRN01C'.
            sysout.display(START_BANNER);

            // The try opens BEFORE the first OPEN, and not after the last one: the fourth open failing
            // leaves the first three files open, and those are the handles the release below exists for.
            try {
                openDalytran();                                        // :157
                openCustfile();                                        // :158
                openXreffile();                                        // :159
                openCardfile();                                        // :160
                openAcctfile();                                        // :161
                openTranfile();                                        // :162

                // :164  PERFORM UNTIL END-OF-DAILY-TRANS-FILE = 'Y'
                while (!AT_END_OF_FILE.equals(endOfDailyTransFile)) {

                    // NO COBOL COUNTERPART. The between-record yield to a stop request: a call rather than
                    // a condition, so it adds no arm to the translated control flow, and placed before the
                    // read so the record in flight is always complete.
                    stopSignal.checkStopRequested();

                    // :165  IF END-OF-DAILY-TRANS-FILE = 'N'
                    // Redundant - the PERFORM UNTIL condition has already established it and the flag holds
                    // only 'N' or 'Y' - so its false path is unreachable and a coverage report shows this as
                    // a half-taken branch. Reproduced rather than removed: a statement in the source is
                    // accounted for in the translation.
                    if (NOT_AT_END_OF_FILE.equals(endOfDailyTransFile)) {

                        // :166  PERFORM 1000-DALYTRAN-GET-NEXT
                        getNextDalytranRecord();

                        // :167  IF END-OF-DAILY-TRANS-FILE = 'N'
                        if (NOT_AT_END_OF_FILE.equals(endOfDailyTransFile)) {
                            // :168  DISPLAY DALYTRAN-RECORD - the raw 350-byte image, FILLER included.
                            sysout.display(dalytranRecord.displayImage());
                        }

                        // ===================================================================
                        // DEFECT 1. The next three statements are OUTSIDE the guard that closed
                        // one line above: :169's END-IF ends at the DISPLAY. So the iteration
                        // whose read returned end of file still performs a lookup, on the stale
                        // DALYTRAN-CARD-NUM the previous record left in the record area - a
                        // READ ... INTO does not disturb its receiving area AT END. DO NOT HOIST
                        // THESE INSIDE THE GUARD.
                        // ===================================================================

                        // :170  MOVE 0 TO WS-XREF-READ-STATUS
                        xrefReadStatus = READ_STATUS_OK;
                        // :171  MOVE DALYTRAN-CARD-NUM TO XREF-CARD-NUM - both PIC X(16), so the MOVE is
                        // an exact-width copy; routed through the codec so the rule is stated, not assumed.
                        xrefCardNum = codec.movePicX(dalytranRecord.dalytranCardNum(),
                                CardXrefRecord.XREF_CARD_NUM_LENGTH);
                        // :172  PERFORM 2000-LOOKUP-XREF
                        lookupXref();

                        // :173  IF WS-XREF-READ-STATUS = 0
                        if (xrefReadStatus == READ_STATUS_OK) {
                            // :174  MOVE 0 TO WS-ACCT-READ-STATUS
                            acctReadStatus = READ_STATUS_OK;
                            // :175  MOVE XREF-ACCT-ID TO ACCT-ID
                            moveXrefAccountIdToAccountId();
                            // :176  PERFORM 3000-READ-ACCOUNT
                            readAccount();
                            // :177  IF WS-ACCT-READ-STATUS NOT = 0
                            if (acctReadStatus != READ_STATUS_OK) {
                                // :178  DISPLAY 'ACCOUNT ' ACCT-ID ' NOT FOUND'
                                // ACCT-ID is the value :175 moved in: an INVALID KEY read leaves
                                // ACCOUNT-RECORD untouched, so the eleven digits shown are the cross
                                // reference's account id and not the account file's.
                                sysout.display(ACCOUNT_NOT_FOUND_PREFIX + acctId
                                        + ACCOUNT_NOT_FOUND_SUFFIX);
                            }
                        } else {
                            // :181-:183  ONE DISPLAY with four operands, concatenated with no separator.
                            sysout.display(CARD_NOT_VERIFIED_PREFIX + dalytranRecord.dalytranCardNum()
                                    + CARD_NOT_VERIFIED_SUFFIX + dalytranRecord.dalytranId());
                        }
                    }
                }

                closeDalytran();                                       // :188
                closeCustfile();                                       // :189
                closeXreffile();                                       // :190
                closeCardfile();                                       // :191
                closeAcctfile();                                       // :192
                closeTranfile();                                       // :193

                // :195  DISPLAY 'END OF EXECUTION OF PROGRAM CBTRN01C'.
                sysout.display(END_BANNER);

                // :197  GOBACK. - RETURN-CODE is never moved anywhere in this program, so a run that
                // returns at all returns zero.
                return new ExecutionSummary(AbendException.RETURN_CODE_OK, recordsRead, xrefLookups,
                        accountReads);
            } finally {
                releaseUnclosedHandles();
            }
        }

        /**
         * {@code MOVE XREF-ACCT-ID TO ACCT-ID} - {@code app/cbl/CBTRN01C.cbl:175}.
         *
         * <p>Both fields are {@code PIC 9(11)}, so the move is an exact-width digit copy; the value is kept
         * twice, as the eleven characters {@code :178} may display and as the number the keyed read is
         * issued with.
         *
         * <p>When no lookup has ever succeeded the record area is still at its declared value, which for a
         * {@code PIC 9} field is zero - and the program can reach here in that state, because a lookup that
         * reported a status outside {@code '00'}, {@code '22'} and {@code '23'} executes neither of the
         * {@code READ}'s conditional phrases and so leaves {@code WS-XREF-READ-STATUS} at the zero
         * {@code :170} put there. See {@link #lookupXref()}.
         */
        private void moveXrefAccountIdToAccountId() {
            long xrefAcctId = cardXrefRecord == null
                    ? UNPOPULATED_ACCOUNT_ID
                    : cardXrefRecord.xrefAcctId();
            acctIdKey = xrefAcctId;
            acctId = codec.movePic9(xrefAcctId, AccountRecord.KEY_LENGTH);
        }

        // -------------------------------------------------------------------------------------------
        // 1000-DALYTRAN-GET-NEXT - app/cbl/CBTRN01C.cbl:202-225.
        // -------------------------------------------------------------------------------------------

        /**
         * Reads the next daily transaction record.
         *
         * <pre>
         * 1000-DALYTRAN-GET-NEXT.                                          :202
         *     READ DALYTRAN-FILE INTO DALYTRAN-RECORD.                     :203
         *     IF  DALYTRAN-STATUS = '00'                                   :204
         *         MOVE 0 TO APPL-RESULT                                    :205
         *     ELSE                                                         :206
         *         IF  DALYTRAN-STATUS = '10'                               :207
         *             MOVE 16 TO APPL-RESULT                               :208
         *         ELSE                                                     :209
         *             MOVE 12 TO APPL-RESULT                               :210
         *         END-IF                                                   :211
         *     END-IF                                                       :212
         *     IF  APPL-AOK                                                 :213
         *         CONTINUE                                                 :214
         *     ELSE                                                         :215
         *         IF  APPL-EOF                                             :216
         *             MOVE 'Y' TO END-OF-DAILY-TRANS-FILE                  :217
         *         ELSE                                                     :218
         *             DISPLAY 'ERROR READING DAILY TRANSACTION FILE'       :219
         *             MOVE DALYTRAN-STATUS TO IO-STATUS                    :220
         *             PERFORM Z-DISPLAY-IO-STATUS                          :221
         *             PERFORM Z-ABEND-PROGRAM                              :222
         *         END-IF                                                   :223
         *     END-IF                                                       :224
         * </pre>
         *
         * <p>Two nested ladders, and both nesting levels are reproduced separately rather than collapsed
         * into one three-way test, because that is how the source reads and because gate <strong>G50</strong>
         * requires both sides of each condition to be driven. The status ladder classifies first and only
         * then is {@code APPL-RESULT} tested: a status of {@code '22'} or {@code '23'} is neither of the two
         * the paragraph names, so it takes the third arm and abends - which is what a batch program that
         * tests only {@code '00'} and {@code '10'} does.
         *
         * <p>{@code DISPLAY DALYTRAN-RECORD} is <strong>not</strong> here. Unlike its sibling
         * {@code CBACT03C}, this program displays the record only from the mainline, at {@code :168}, so a
         * record produces exactly one image line.
         *
         * @throws AbendException if the read reports any status but {@code '00'} or {@code '10'}
         */
        private void getNextDalytranRecord() {
            // :203  READ DALYTRAN-FILE INTO DALYTRAN-RECORD.
            DalyTranRepository.ReadResult read = readDalytran();
            dalytranStatus = read.status();

            if (FileStatus.isOk(dalytranStatus)) {                     // :204
                // :205  MOVE 0 TO APPL-RESULT
                applResult = FileStatus.APPL_AOK;
                // The INTO move, which is part of a successful READ and of nothing else. At AT END the
                // receiving area is left exactly as it was, which is what defect 1 rests on.
                dalytranRecord = read.dalyTran().orElseThrow(() -> new IllegalStateException("A read of "
                        + "DD " + DALYTRAN_DD_NAME + " reported file status "
                        + FileStatus.toStatusImage(FileStatus.OK) + " and carried no record. The two are "
                        + "contradictory: a successful READ ... INTO populates the record area, which "
                        + PROGRAM_ID + ":168 then displays."));
                recordsRead++;
            } else if (FileStatus.isEndOfFile(dalytranStatus)) {        // :207
                // :208  MOVE 16 TO APPL-RESULT
                applResult = FileStatus.APPL_EOF;
            } else {
                // :210  MOVE 12 TO APPL-RESULT
                applResult = APPL_RESULT_FATAL;
            }

            if (applResult == FileStatus.APPL_AOK) {                   // :213  IF APPL-AOK
                return;                                                // :214  CONTINUE
            }
            if (applResult == FileStatus.APPL_EOF) {                   // :216  IF APPL-EOF
                // :217  MOVE 'Y' TO END-OF-DAILY-TRANS-FILE
                endOfDailyTransFile = AT_END_OF_FILE;
                return;
            }
            // :219-:222
            throw reportAndAbend(ERROR_READING_DALYTRAN, dalytranStatus);
        }

        /**
         * {@code READ DALYTRAN-FILE INTO DALYTRAN-RECORD} - {@code app/cbl/CBTRN01C.cbl:203}.
         *
         * <p>A backend refusal is reported as a {@code FILE STATUS} rather than propagated, because that is
         * what a {@code READ} with a {@code FILE STATUS} clause and no {@code USE} procedure does: it
         * returns a status and the caller's own ladder decides. That ladder decides to abend three lines
         * later, and the refusal travels out as the abend's cause, so nothing is swallowed.
         *
         * @return the read outcome; never {@code null}
         */
        private DalyTranRepository.ReadResult readDalytran() {
            try {
                return dalytranFile.readNext();
            } catch (RuntimeException refused) {
                datasetRefusal = refused;
                return DalyTranRepository.ReadResult.other(PERMANENT_ERROR_STATUS);
            }
        }

        // -------------------------------------------------------------------------------------------
        // 2000-LOOKUP-XREF - app/cbl/CBTRN01C.cbl:227-239.
        // -------------------------------------------------------------------------------------------

        /**
         * Reads the cross reference by card number and reports what it found.
         *
         * <pre>
         * 2000-LOOKUP-XREF.                                                :227
         *     MOVE XREF-CARD-NUM TO FD-XREF-CARD-NUM                       :228
         *     READ XREF-FILE  RECORD INTO CARD-XREF-RECORD                 :229
         *     KEY IS FD-XREF-CARD-NUM                                      :230
         *          INVALID KEY                                             :231
         *            DISPLAY 'INVALID CARD NUMBER FOR XREF'                :232
         *            MOVE 4 TO WS-XREF-READ-STATUS                         :233
         *          NOT INVALID KEY                                         :234
         *            DISPLAY 'SUCCESSFUL READ OF XREF'                     :235
         *            DISPLAY 'CARD NUMBER: ' XREF-CARD-NUM                 :236
         *            DISPLAY 'ACCOUNT ID : ' XREF-ACCT-ID                  :237
         *            DISPLAY 'CUSTOMER ID: ' XREF-CUST-ID                  :238
         *     END-READ.                                                    :239
         * </pre>
         *
         * <p><strong>Four lines on success, in that order.</strong> The three prefixes carry their own
         * padding so the values line up in the spool; the space before the colon in
         * {@value #XREF_ACCOUNT_ID_PREFIX} is in the source and is reproduced.
         *
         * <p><strong>The read overwrites its own key field before the key field is displayed.</strong>
         * {@code READ ... INTO CARD-XREF-RECORD} moves the whole fifty-byte area, and {@code XREF-CARD-NUM}
         * is the first sixteen bytes of it - so the number {@code :236} shows is the record's own, not the
         * key that was searched for. Those are the same value for an exact-match keyed read, but the
         * ordering is modelled faithfully rather than assumed away.
         *
         * <p><strong>A status that is neither success nor an invalid key executes neither phrase.</strong>
         * A {@code READ} statement's {@code INVALID KEY} phrase runs for the invalid-key condition and its
         * {@code NOT INVALID KEY} phrase runs for a successful read; an I/O error that is neither - an
         * unreachable dataset, say - is reported through the {@code FILE STATUS} clause and, with no
         * {@code USE} procedure declared, control simply passes to the statement after the {@code READ}.
         * So no line is displayed, {@code WS-XREF-READ-STATUS} keeps the zero {@code :170} put there, and
         * the mainline goes on to read an account for whatever {@code XREF-ACCT-ID} the record area still
         * holds. That is what the program does, so that is what this does; see
         * {@link #moveXrefAccountIdToAccountId()} for the value used when the area was never populated.
         *
         * <p>Gate <strong>G45</strong>: the read is issued against the {@code CCXREF} base cluster by its
         * sixteen-character card-number key. The {@code CXACAIX} alternate-index path is never opened by
         * this program and is never touched here.
         */
        private void lookupXref() {
            xrefLookups++;

            // :228  MOVE XREF-CARD-NUM TO FD-XREF-CARD-NUM - the repository applies the same PIC X(16)
            //       MOVE to whatever it is handed, so the key is built exactly once.
            // :229  READ XREF-FILE RECORD INTO CARD-XREF-RECORD KEY IS FD-XREF-CARD-NUM
            CardXrefRepository.ReadResult read = readXrefByCardNumber(xrefCardNum);
            xreffileStatus = read.status();

            if (isInvalidKeyCondition(xreffileStatus)) {                // :231  INVALID KEY
                // :232  DISPLAY 'INVALID CARD NUMBER FOR XREF'
                sysout.display(INVALID_CARD_NUMBER_FOR_XREF);
                // :233  MOVE 4 TO WS-XREF-READ-STATUS
                xrefReadStatus = READ_STATUS_INVALID_KEY;
            } else if (FileStatus.isOk(xreffileStatus)) {               // :234  NOT INVALID KEY
                // The INTO move, whole-area, before any of the four DISPLAYs below reads from it.
                cardXrefRecord = read.record().orElseThrow(() -> new IllegalStateException("A read of DD "
                        + XREFFILE_DD_NAME + " reported file status "
                        + FileStatus.toStatusImage(FileStatus.OK) + " and carried no record. The two are "
                        + "contradictory: the NOT INVALID KEY arm of " + PROGRAM_ID + ":234 displays three "
                        + "fields of the record area a successful READ ... INTO has just populated."));
                xrefCardNum = codec.movePicX(cardXrefRecord.xrefCardNum(),
                        CardXrefRecord.XREF_CARD_NUM_LENGTH);
                // :235
                sysout.display(SUCCESSFUL_READ_OF_XREF);
                // :236  DISPLAY 'CARD NUMBER: ' XREF-CARD-NUM   - PIC X(16), untrimmed and unmasked.
                sysout.display(XREF_CARD_NUMBER_PREFIX + xrefCardNum);
                // :237  DISPLAY 'ACCOUNT ID : ' XREF-ACCT-ID    - PIC 9(11), so eleven digits.
                sysout.display(XREF_ACCOUNT_ID_PREFIX
                        + codec.movePic9(cardXrefRecord.xrefAcctId(), CardXrefRecord.XREF_ACCT_ID_LENGTH));
                // :238  DISPLAY 'CUSTOMER ID: ' XREF-CUST-ID    - PIC 9(09), so nine digits.
                sysout.display(XREF_CUSTOMER_ID_PREFIX
                        + codec.movePic9(cardXrefRecord.xrefCustId(), CardXrefRecord.XREF_CUST_ID_LENGTH));
            }
            // Neither phrase for any other status. Nothing is displayed and nothing is moved - see this
            // method's documentation. Deliberately not an else: there is no statement here to translate.
        }

        /**
         * {@code READ XREF-FILE RECORD INTO CARD-XREF-RECORD KEY IS FD-XREF-CARD-NUM} -
         * {@code app/cbl/CBTRN01C.cbl:229-230}.
         *
         * @param cardNumber the sixteen-character key {@code :228} moved into the record key
         * @return the read outcome; never {@code null}
         */
        private CardXrefRepository.ReadResult readXrefByCardNumber(String cardNumber) {
            try {
                return xrefAccess.readByCardNumber(cardNumber);
            } catch (RuntimeException refused) {
                datasetRefusal = refused;
                return CardXrefRepository.ReadResult.other(XREFFILE_DD_NAME, PERMANENT_ERROR_STATUS);
            }
        }

        // -------------------------------------------------------------------------------------------
        // 3000-READ-ACCOUNT - app/cbl/CBTRN01C.cbl:241-250.
        // -------------------------------------------------------------------------------------------

        /**
         * Reads the account by the cross-referenced account id.
         *
         * <pre>
         * 3000-READ-ACCOUNT.                                               :241
         *     MOVE ACCT-ID TO FD-ACCT-ID                                   :242
         *     READ ACCOUNT-FILE RECORD INTO ACCOUNT-RECORD                 :243
         *     KEY IS FD-ACCT-ID                                            :244
         *          INVALID KEY                                             :245
         *            DISPLAY 'INVALID ACCOUNT NUMBER FOUND'                :246
         *            MOVE 4 TO WS-ACCT-READ-STATUS                         :247
         *          NOT INVALID KEY                                         :248
         *            DISPLAY 'SUCCESSFUL READ OF ACCOUNT FILE'             :249
         *     END-READ.                                                    :250
         * </pre>
         *
         * <p><strong>Read only.</strong> Nothing is computed from the account, nothing is displayed from it
         * beyond the fixed success line, and nothing is written back - the file was opened
         * {@code OPEN INPUT} at {@code :327} and this program contains no {@code REWRITE}.
         *
         * <p>The same {@code READ ... INTO} ordering applies as in {@link #lookupXref()}: a successful read
         * overwrites {@code ACCOUNT-RECORD}, and with it {@code ACCT-ID}. That matters for the line the
         * mainline may display at {@code :178}, which is reached only when this read <em>failed</em> and the
         * area therefore still holds the value {@code :175} moved in.
         *
         * <p>And the same "neither phrase" rule applies: a status outside {@code '00'}, {@code '22'} and
         * {@code '23'} displays nothing and leaves {@code WS-ACCT-READ-STATUS} at zero, so the mainline
         * emits no {@code 'ACCOUNT ... NOT FOUND'} line either.
         */
        private void readAccount() {
            accountReads++;

            // :242  MOVE ACCT-ID TO FD-ACCT-ID
            // :243  READ ACCOUNT-FILE RECORD INTO ACCOUNT-RECORD KEY IS FD-ACCT-ID
            AccountRepository.ReadResult read = readAccountByKey(acctIdKey);
            acctfileStatus = read.status();

            if (isInvalidKeyCondition(acctfileStatus)) {                // :245  INVALID KEY
                // :246  DISPLAY 'INVALID ACCOUNT NUMBER FOUND'
                sysout.display(INVALID_ACCOUNT_NUMBER_FOUND);
                // :247  MOVE 4 TO WS-ACCT-READ-STATUS
                acctReadStatus = READ_STATUS_INVALID_KEY;
            } else if (FileStatus.isOk(acctfileStatus)) {               // :248  NOT INVALID KEY
                // The INTO move: ACCOUNT-RECORD, and therefore ACCT-ID, becomes the record's own.
                acctId = codec.movePic9(read.account().orElseThrow(() -> new IllegalStateException("A read "
                        + "of DD " + ACCTFILE_DD_NAME + " reported file status "
                        + FileStatus.toStatusImage(FileStatus.OK) + " and carried no record. The two are "
                        + "contradictory: a successful READ ... INTO populates ACCOUNT-RECORD, which is "
                        + "what makes ACCT-ID the account file's own value from " + PROGRAM_ID
                        + ":248 onward.")).getAcctId(), AccountRecord.KEY_LENGTH);
                // :249  DISPLAY 'SUCCESSFUL READ OF ACCOUNT FILE'
                sysout.display(SUCCESSFUL_READ_OF_ACCOUNT_FILE);
            }
            // Neither phrase for any other status; see this method's documentation.
        }

        /**
         * {@code READ ACCOUNT-FILE RECORD INTO ACCOUNT-RECORD KEY IS FD-ACCT-ID} -
         * {@code app/cbl/CBTRN01C.cbl:243-244}.
         *
         * @param accountId the eleven-digit account id {@code :242} moved into the record key
         * @return the read outcome; never {@code null}
         */
        private AccountRepository.ReadResult readAccountByKey(long accountId) {
            try {
                return acctfile.readByKey(accountId);
            } catch (RuntimeException refused) {
                datasetRefusal = refused;
                return AccountRepository.ReadResult.of(PERMANENT_ERROR_STATUS);
            }
        }

        // -------------------------------------------------------------------------------------------
        // The six OPEN paragraphs - app/cbl/CBTRN01C.cbl:252-359.
        //
        // All six are written identically in the source apart from the file they open, the status field
        // they test and the literal they display:
        //
        //     MOVE 8 TO APPL-RESULT.                  <- dead; the next test overwrites it either way
        //     OPEN INPUT <file>
        //     IF  <file>-STATUS = '00' MOVE 0 TO APPL-RESULT ELSE MOVE 12 TO APPL-RESULT END-IF
        //     IF  APPL-AOK CONTINUE
        //     ELSE DISPLAY '<text>' / MOVE <file>-STATUS TO IO-STATUS
        //          PERFORM Z-DISPLAY-IO-STATUS / PERFORM Z-ABEND-PROGRAM END-IF
        //
        // They are written out one per paragraph rather than folded into a loop, because each names its own
        // status field and its own literal and each is a separate abend site - and because the close
        // paragraphs are NOT all identical (see closeDalytran), so a fold would have hidden that.
        //
        // EVERY ONE IS 'OPEN INPUT'. That is the whole answer to this class's name: no file is ever opened
        // for output or for I-O, so no posting is possible even in principle.
        // -------------------------------------------------------------------------------------------

        /**
         * {@code 0000-DALYTRAN-OPEN} - {@code app/cbl/CBTRN01C.cbl:252-268}.
         *
         * @throws AbendException if the open reports any status but {@code '00'}
         */
        private void openDalytran() {
            applResult = APPL_RESULT_ASSUMED_FAILURE;                  // :253
            dalytranStatus = openDalytranFile();                       // :254
            if (FileStatus.isOk(dalytranStatus)) {                     // :255
                applResult = FileStatus.APPL_AOK;                      // :256
            } else {
                applResult = APPL_RESULT_FATAL;                        // :258
            }
            if (applResult == FileStatus.APPL_AOK) {                    // :260  IF APPL-AOK
                return;                                                 // :261  CONTINUE
            }
            throw reportAndAbend(ERROR_OPENING_DALYTRAN, dalytranStatus);   // :263-:266
        }

        /**
         * {@code 0100-CUSTFILE-OPEN} - {@code app/cbl/CBTRN01C.cbl:271-287}.
         *
         * <p>The customer file is opened here and closed at {@code :379}, and <strong>never read between
         * them</strong>. Both verbs are nonetheless part of the program's behaviour: either can fail, and a
         * failure here abends before a single daily transaction has been read.
         *
         * @throws AbendException if the open reports any status but {@code '00'}
         */
        private void openCustfile() {
            applResult = APPL_RESULT_ASSUMED_FAILURE;                  // :272
            custfileStatus = openCustomerFile();                       // :273
            if (FileStatus.isOk(custfileStatus)) {                     // :274
                applResult = FileStatus.APPL_AOK;                      // :275
            } else {
                applResult = APPL_RESULT_FATAL;                        // :277
            }
            if (applResult == FileStatus.APPL_AOK) {                    // :279
                return;                                                 // :280
            }
            throw reportAndAbend(ERROR_OPENING_CUSTFILE, custfileStatus);   // :282-:285
        }

        /**
         * {@code 0200-XREFFILE-OPEN} - {@code app/cbl/CBTRN01C.cbl:289-305}.
         *
         * @throws AbendException if the open reports any status but {@code '00'}
         */
        private void openXreffile() {
            applResult = APPL_RESULT_ASSUMED_FAILURE;                  // :290
            xreffileStatus = openXrefFile();                           // :291
            if (FileStatus.isOk(xreffileStatus)) {                     // :292
                applResult = FileStatus.APPL_AOK;                      // :293
            } else {
                applResult = APPL_RESULT_FATAL;                        // :295
            }
            if (applResult == FileStatus.APPL_AOK) {                    // :297
                return;                                                 // :298
            }
            throw reportAndAbend(ERROR_OPENING_XREFFILE, xreffileStatus);   // :300-:303
        }

        /**
         * {@code 0300-CARDFILE-OPEN} - {@code app/cbl/CBTRN01C.cbl:307-323}.
         *
         * <p>The card file is opened here and closed at {@code :415}, and <strong>never read between
         * them</strong>.
         *
         * @throws AbendException if the open reports any status but {@code '00'}
         */
        private void openCardfile() {
            applResult = APPL_RESULT_ASSUMED_FAILURE;                  // :308
            cardfileStatus = openCardFile();                           // :309
            if (FileStatus.isOk(cardfileStatus)) {                     // :310
                applResult = FileStatus.APPL_AOK;                      // :311
            } else {
                applResult = APPL_RESULT_FATAL;                        // :313
            }
            if (applResult == FileStatus.APPL_AOK) {                    // :315
                return;                                                 // :316
            }
            throw reportAndAbend(ERROR_OPENING_CARDFILE, cardfileStatus);   // :318-:321
        }

        /**
         * {@code 0400-ACCTFILE-OPEN} - {@code app/cbl/CBTRN01C.cbl:325-341}.
         *
         * @throws AbendException if the open reports any status but {@code '00'}
         */
        private void openAcctfile() {
            applResult = APPL_RESULT_ASSUMED_FAILURE;                  // :326
            acctfileStatus = openAccountFile();                        // :327
            if (FileStatus.isOk(acctfileStatus)) {                     // :328
                applResult = FileStatus.APPL_AOK;                      // :329
            } else {
                applResult = APPL_RESULT_FATAL;                        // :331
            }
            if (applResult == FileStatus.APPL_AOK) {                    // :333
                return;                                                 // :334
            }
            throw reportAndAbend(ERROR_OPENING_ACCTFILE, acctfileStatus);   // :336-:339
        }

        /**
         * {@code 0500-TRANFILE-OPEN} - {@code app/cbl/CBTRN01C.cbl:343-359}.
         *
         * <p>The transaction file is opened here and closed at {@code :451}, and <strong>never read or
         * written between them</strong>. This is the dead access the class name promises to use: the
         * "posting" would be a write to this file, and the program never issues one. Preserved, not tidied.
         *
         * @throws AbendException if the open reports any status but {@code '00'}
         */
        private void openTranfile() {
            applResult = APPL_RESULT_ASSUMED_FAILURE;                  // :344
            tranfileStatus = openTransactionFile();                    // :345
            if (FileStatus.isOk(tranfileStatus)) {                     // :346
                applResult = FileStatus.APPL_AOK;                      // :347
            } else {
                applResult = APPL_RESULT_FATAL;                        // :349
            }
            if (applResult == FileStatus.APPL_AOK) {                    // :351
                return;                                                 // :352
            }
            throw reportAndAbend(ERROR_OPENING_TRANFILE, tranfileStatus);   // :354-:357
        }

        // -------------------------------------------------------------------------------------------
        // The OPEN INPUT verbs themselves.
        //
        // Each reports a FILE STATUS rather than propagating, because that is what OPEN does: it has a
        // FILE STATUS clause and no exception, and the caller's guard chain decides what a bad status
        // means. It decides to abend one line later, and the original failure travels out as the abend's
        // cause, so nothing is swallowed.
        //
        // A HANDLE IS RETAINED ONLY WHEN ITS OPEN SUCCEEDED. A COBOL program has no usable file after a
        // failed OPEN either, and it is what makes the best-effort release below release exactly the files
        // an OPEN actually established.
        // -------------------------------------------------------------------------------------------

        /** {@code OPEN INPUT DALYTRAN-FILE} - {@code app/cbl/CBTRN01C.cbl:254}. */
        private String openDalytranFile() {
            try {
                DalyTranRepository.DalytranFile opened = dalyTranRepository.open();
                String status = opened.openStatus();
                if (FileStatus.isOk(status)) {
                    dalytranFile = opened;
                }
                return status;
            } catch (RuntimeException refused) {
                datasetRefusal = refused;
                return PERMANENT_ERROR_STATUS;
            }
        }

        /** {@code OPEN INPUT CUSTOMER-FILE} - {@code app/cbl/CBTRN01C.cbl:273}. */
        private String openCustomerFile() {
            try {
                CustomerRepository.CustomerFile opened = customerRepository.openInput();
                String status = opened.openStatus();
                if (FileStatus.isOk(status)) {
                    custfile = opened;
                }
                return status;
            } catch (RuntimeException refused) {
                datasetRefusal = refused;
                return PERMANENT_ERROR_STATUS;
            }
        }

        /**
         * {@code OPEN INPUT XREF-FILE} - {@code app/cbl/CBTRN01C.cbl:291}.
         *
         * <p>Two things happen, and both can fail: the DD is resolved to a dataset, and the dataset is
         * established as reachable. {@link CardXrefRepository#openBrowse()} performs the second by
         * describing the relation without reading a row, which is exactly what {@code OPEN INPUT}
         * establishes - and it re-establishes it on every call rather than trusting an earlier operation's
         * success.
         *
         * <p>The rebased repository is retained alongside the cursor, because the keyed reads of
         * {@code 2000-LOOKUP-XREF} must address the same DD this open resolved.
         */
        private String openXrefFile() {
            try {
                // The binding type is inferred rather than named: DatasetBinding is declared by
                // DataSourceConfig, which is not among this file's declared dependencies.
                var binding = batchConfig.datasetBinding(JOB_KEY, XREFFILE_DD_NAME);
                CardXrefRepository access = cardXrefRepository.addressing(binding, XREFFILE_DD_NAME,
                        null, CardXrefRepository.ALTERNATE_INDEX_BATCH_DD_NAME);
                CardXrefRepository.BrowseCursor opened = access.openBrowse();
                String status = opened.openStatus();
                if (FileStatus.isOk(status)) {
                    xrefAccess = access;
                    xreffile = opened;
                }
                return status;
            } catch (RuntimeException refused) {
                datasetRefusal = refused;
                return PERMANENT_ERROR_STATUS;
            }
        }

        /**
         * {@code OPEN INPUT CARD-FILE} - {@code app/cbl/CBTRN01C.cbl:309}.
         *
         * <p>{@link CardRepository} reports an open as a CICS response, because its other seventeen callers
         * are online programs. It is translated to a batch {@code FILE STATUS} the same way a read's
         * response is, so this open reaches the same arms an ordinary batch open would; a response with no
         * batch equivalent lands on the permanent-error convention, and thence on the
         * {@code ELSE MOVE 12} arm.
         */
        private String openCardFile() {
            try {
                var binding = batchConfig.datasetBinding(JOB_KEY, CARDFILE_DD_NAME);
                CardRepository access = cardRepository.addressing(binding, CARDFILE_DD_NAME);
                // openBrowse and not startBrowse: this program tests its OPEN, and only openBrowse reports
                // whether the dataset was actually there. No read ever follows - the file is declared
                // ACCESS MODE IS RANDOM at :48 and the program never reads it - so the anchor key is
                // simply the lowest one.
                CardRepository.CardBrowse opened =
                        access.openBrowse(LOWEST_CARD_NUMBER_KEY, BrowseDirection.FORWARD);
                String status = FileStatus.batchStatusOfCicsResp(opened.openResp())
                        .orElse(PERMANENT_ERROR_STATUS);
                if (FileStatus.isOk(status)) {
                    cardfile = opened;
                }
                return status;
            } catch (RuntimeException refused) {
                datasetRefusal = refused;
                return PERMANENT_ERROR_STATUS;
            }
        }

        /**
         * {@code OPEN INPUT ACCOUNT-FILE} - {@code app/cbl/CBTRN01C.cbl:327}.
         *
         * <p>{@code INPUT} and never {@code I-O}: this program reads the account file and never rewrites
         * it, and opening it for update would grant a capability the source does not take.
         */
        private String openAccountFile() {
            try {
                AccountRepository.AccountFile opened =
                        accountRepository.open(AccountRepository.OpenMode.INPUT);
                String status = opened.openStatus();
                if (FileStatus.isOk(status)) {
                    acctfile = opened;
                }
                return status;
            } catch (RuntimeException refused) {
                datasetRefusal = refused;
                return PERMANENT_ERROR_STATUS;
            }
        }

        /** {@code OPEN INPUT TRANSACT-FILE} - {@code app/cbl/CBTRN01C.cbl:345}. */
        private String openTransactionFile() {
            try {
                var binding = batchConfig.datasetBinding(JOB_KEY, TRANFILE_DD_NAME);
                TransactionRepository.InputFile opened = transactionRepository.openInput(binding);
                String status = opened.openStatus();
                if (FileStatus.isOk(status)) {
                    tranfile = opened;
                }
                return status;
            } catch (RuntimeException refused) {
                datasetRefusal = refused;
                return PERMANENT_ERROR_STATUS;
            }
        }

        // -------------------------------------------------------------------------------------------
        // The six CLOSE paragraphs - app/cbl/CBTRN01C.cbl:361-467.
        //
        // Same shape as the opens, with one difference of form and one of substance:
        //
        //   * FORM. The seed is written ADD 8 TO ZERO GIVING APPL-RESULT rather than MOVE 8 TO
        //     APPL-RESULT. The value is identical and the statement is equally dead; the shape is kept so
        //     each paragraph stays diffable against its source.
        //   * SUBSTANCE. 9000-DALYTRAN-CLOSE displays the CUSTOMER file's literal and renders the CUSTOMER
        //     file's status. That is defect 2, and it is why these six are written out rather than folded.
        //
        // A close that fails abends after the loop has already run, so its lines follow every record line
        // rather than replacing them - and the closes after it never happen, exactly as CALL 'CEE3ABD'
        // means they do not.
        // -------------------------------------------------------------------------------------------

        /**
         * {@code 9000-DALYTRAN-CLOSE} - {@code app/cbl/CBTRN01C.cbl:361-377}.
         *
         * <pre>
         * 9000-DALYTRAN-CLOSE.                                             :361
         *     ADD 8 TO ZERO GIVING APPL-RESULT.                            :362
         *     CLOSE DALYTRAN-FILE                                          :363
         *     IF  DALYTRAN-STATUS = '00'                                   :364
         *         MOVE 0 TO APPL-RESULT                                    :365
         *     ELSE                                                         :366
         *         MOVE 12 TO APPL-RESULT                                   :367
         *     END-IF                                                       :368
         *     IF  APPL-AOK                                                 :369
         *         CONTINUE                                                 :370
         *     ELSE                                                         :371
         *         DISPLAY 'ERROR CLOSING CUSTOMER FILE'                    :372  &lt;-- DEFECT 2
         *         MOVE CUSTFILE-STATUS TO IO-STATUS                        :373  &lt;-- DEFECT 2
         *         PERFORM Z-DISPLAY-IO-STATUS                              :374
         *         PERFORM Z-ABEND-PROGRAM                                  :375
         *     END-IF                                                       :376
         * </pre>
         *
         * <p><strong>DEFECT 2, PRESERVED. This paragraph closes the daily transaction file and reports the
         * customer file.</strong> Lines {@code :372} and {@code :373} are a copy of the paragraph below and
         * were never adjusted: the failing arm displays {@code 'ERROR CLOSING CUSTOMER FILE'} and moves
         * {@code CUSTFILE-STATUS} - not {@code DALYTRAN-STATUS} - into {@code IO-STATUS}. Since the customer
         * file's own open succeeded, its status field still holds {@code '00'}, so the rendered line reads
         * {@code FILE STATUS IS: NNNN0000} while the status that actually failed is never shown anywhere.
         *
         * <p>It is <strong>not</strong> corrected here. Practice <strong>B5</strong> preserves behaviour
         * including its defects, and the parity gate compares against what the program does. The
         * {@code RETURN-CODE} is unaffected - the ladder above already moved {@value #APPL_RESULT_FATAL} -
         * so a failing close still abends with 12; only what it says about itself is wrong.
         *
         * @throws AbendException if the close reports any status but {@code '00'}
         */
        private void closeDalytran() {
            applResult = APPL_RESULT_ASSUMED_FAILURE;                  // :362
            dalytranCloseIssued = true;
            dalytranStatus = closeDalytranFile();                      // :363
            if (FileStatus.isOk(dalytranStatus)) {                     // :364
                applResult = FileStatus.APPL_AOK;                      // :365
            } else {
                applResult = APPL_RESULT_FATAL;                        // :367
            }
            if (applResult == FileStatus.APPL_AOK) {                    // :369
                return;                                                 // :370
            }
            // :372-:375  DEFECT 2: the customer file's literal, and the customer file's status field.
            throw reportAndAbend(ERROR_CLOSING_CUSTFILE, custfileStatus);
        }

        /**
         * {@code 9100-CUSTFILE-CLOSE} - {@code app/cbl/CBTRN01C.cbl:379-395}.
         *
         * <p>This paragraph's failing arm is the one {@code 9000-DALYTRAN-CLOSE} was copied from, and here
         * the literal and the status field are both correct.
         *
         * @throws AbendException if the close reports any status but {@code '00'}
         */
        private void closeCustfile() {
            applResult = APPL_RESULT_ASSUMED_FAILURE;                  // :380
            custfileCloseIssued = true;
            custfileStatus = closeCustomerFile();                      // :381
            if (FileStatus.isOk(custfileStatus)) {                     // :382
                applResult = FileStatus.APPL_AOK;                      // :383
            } else {
                applResult = APPL_RESULT_FATAL;                        // :385
            }
            if (applResult == FileStatus.APPL_AOK) {                    // :387
                return;                                                 // :388
            }
            throw reportAndAbend(ERROR_CLOSING_CUSTFILE, custfileStatus);    // :390-:393
        }

        /**
         * {@code 9200-XREFFILE-CLOSE} - {@code app/cbl/CBTRN01C.cbl:397-413}.
         *
         * @throws AbendException if the close reports any status but {@code '00'}
         */
        private void closeXreffile() {
            applResult = APPL_RESULT_ASSUMED_FAILURE;                  // :398
            xreffileCloseIssued = true;
            xreffileStatus = closeXrefFile();                          // :399
            if (FileStatus.isOk(xreffileStatus)) {                     // :400
                applResult = FileStatus.APPL_AOK;                      // :401
            } else {
                applResult = APPL_RESULT_FATAL;                        // :403
            }
            if (applResult == FileStatus.APPL_AOK) {                    // :405
                return;                                                 // :406
            }
            throw reportAndAbend(ERROR_CLOSING_XREFFILE, xreffileStatus);    // :408-:411
        }

        /**
         * {@code 9300-CARDFILE-CLOSE} - {@code app/cbl/CBTRN01C.cbl:415-431}.
         *
         * @throws AbendException if the close reports any status but {@code '00'}
         */
        private void closeCardfile() {
            applResult = APPL_RESULT_ASSUMED_FAILURE;                  // :416
            cardfileCloseIssued = true;
            cardfileStatus = closeCardFile();                          // :417
            if (FileStatus.isOk(cardfileStatus)) {                     // :418
                applResult = FileStatus.APPL_AOK;                      // :419
            } else {
                applResult = APPL_RESULT_FATAL;                        // :421
            }
            if (applResult == FileStatus.APPL_AOK) {                    // :423
                return;                                                 // :424
            }
            throw reportAndAbend(ERROR_CLOSING_CARDFILE, cardfileStatus);    // :426-:429
        }

        /**
         * {@code 9400-ACCTFILE-CLOSE} - {@code app/cbl/CBTRN01C.cbl:433-449}.
         *
         * @throws AbendException if the close reports any status but {@code '00'}
         */
        private void closeAcctfile() {
            applResult = APPL_RESULT_ASSUMED_FAILURE;                  // :434
            acctfileCloseIssued = true;
            acctfileStatus = closeAccountFile();                       // :435
            if (FileStatus.isOk(acctfileStatus)) {                     // :436
                applResult = FileStatus.APPL_AOK;                      // :437
            } else {
                applResult = APPL_RESULT_FATAL;                        // :439
            }
            if (applResult == FileStatus.APPL_AOK) {                    // :441
                return;                                                 // :442
            }
            throw reportAndAbend(ERROR_CLOSING_ACCTFILE, acctfileStatus);    // :444-:447
        }

        /**
         * {@code 9500-TRANFILE-CLOSE} - {@code app/cbl/CBTRN01C.cbl:451-467}.
         *
         * <p>The last of the twelve file verbs, and the second of the two this program applies to the
         * transaction file. Nothing was read from it and nothing was written to it in between.
         *
         * @throws AbendException if the close reports any status but {@code '00'}
         */
        private void closeTranfile() {
            applResult = APPL_RESULT_ASSUMED_FAILURE;                  // :452
            tranfileCloseIssued = true;
            tranfileStatus = closeTransactionFile();                   // :453
            if (FileStatus.isOk(tranfileStatus)) {                     // :454
                applResult = FileStatus.APPL_AOK;                      // :455
            } else {
                applResult = APPL_RESULT_FATAL;                        // :457
            }
            if (applResult == FileStatus.APPL_AOK) {                    // :459
                return;                                                 // :460
            }
            throw reportAndAbend(ERROR_CLOSING_TRANFILE, tranfileStatus);    // :462-:465
        }

        // -------------------------------------------------------------------------------------------
        // The CLOSE verbs themselves. As with the opens, a refusal is reported as a FILE STATUS rather
        // than propagated, because CLOSE has a FILE STATUS clause and no exception.
        // -------------------------------------------------------------------------------------------

        /** {@code CLOSE DALYTRAN-FILE} - {@code app/cbl/CBTRN01C.cbl:363}. */
        private String closeDalytranFile() {
            try {
                return dalytranFile.closeFile();
            } catch (RuntimeException refused) {
                datasetRefusal = refused;
                return PERMANENT_ERROR_STATUS;
            }
        }

        /** {@code CLOSE CUSTOMER-FILE} - {@code app/cbl/CBTRN01C.cbl:381}. */
        private String closeCustomerFile() {
            try {
                return custfile.closeFile();
            } catch (RuntimeException refused) {
                datasetRefusal = refused;
                return PERMANENT_ERROR_STATUS;
            }
        }

        /** {@code CLOSE XREF-FILE} - {@code app/cbl/CBTRN01C.cbl:399}. */
        private String closeXrefFile() {
            try {
                return xreffile.closeBrowse();
            } catch (RuntimeException refused) {
                datasetRefusal = refused;
                return PERMANENT_ERROR_STATUS;
            }
        }

        /**
         * {@code CLOSE CARD-FILE} - {@code app/cbl/CBTRN01C.cbl:417}.
         *
         * <p>{@link CardRepository.CardBrowse#endBrowse()} reports nothing, so a clean return is
         * {@code '00'} and a refusal is the permanent-error convention - which is the same two outcomes
         * every other close in this program can produce.
         */
        private String closeCardFile() {
            try {
                cardfile.endBrowse();
                return FileStatus.OK;
            } catch (RuntimeException refused) {
                datasetRefusal = refused;
                return PERMANENT_ERROR_STATUS;
            }
        }

        /** {@code CLOSE ACCOUNT-FILE} - {@code app/cbl/CBTRN01C.cbl:435}. */
        private String closeAccountFile() {
            try {
                return acctfile.closeFile();
            } catch (RuntimeException refused) {
                datasetRefusal = refused;
                return PERMANENT_ERROR_STATUS;
            }
        }

        /** {@code CLOSE TRANSACT-FILE} - {@code app/cbl/CBTRN01C.cbl:453}. */
        private String closeTransactionFile() {
            try {
                return tranfile.closeInput();
            } catch (RuntimeException refused) {
                datasetRefusal = refused;
                return PERMANENT_ERROR_STATUS;
            }
        }

        // -------------------------------------------------------------------------------------------
        // Best-effort release of an incomplete run's handles. No COBOL counterpart, and deliberately
        // invisible.
        // -------------------------------------------------------------------------------------------

        /**
         * Releases, silently, every handle whose {@code CLOSE} paragraph was not reached.
         *
         * <p>{@value #PROGRAM_ID} abends outright without closing - {@code CALL 'CEE3ABD'} terminates the
         * task - and this preserves that observably while still honouring the {@link AutoCloseable} contract
         * every handle declares. Four properties make it safe rather than merely well-intentioned:
         *
         * <ul>
         *   <li><strong>It is silent.</strong> No {@code DISPLAY} is emitted and no status is recorded. The
         *       source has no such line, and the line sequence is this program's entire observable
         *       output.</li>
         *   <li><strong>It releases only what an {@code OPEN} established.</strong> A handle field is
         *       assigned only when its open reported {@code '00'}, so a failed open leaves nothing to
         *       release - which is what stops a release manufacturing a spurious error record for a run
         *       whose open had already reported itself properly.</li>
         *   <li><strong>It is guarded by the run's own control flow.</strong> The six {@code closeIssued}
         *       flags say whether each {@code CLOSE} paragraph was reached, which is where the property
         *       "exactly one {@code CLOSE} per file per run" actually comes from - a handle's own opinion of
         *       whether it is closed would not establish it.</li>
         *   <li><strong>It cannot displace the real failure.</strong> Anything raised while releasing is
         *       swallowed and only its type is logged, so the caller receives the exception the run was
         *       already unwinding with.</li>
         * </ul>
         *
         * <p>Reverse of the open order, so each file is released before the one it was opened after.
         */
        private void releaseUnclosedHandles() {
            if (!tranfileCloseIssued) {
                releaseQuietly(tranfile, TRANFILE_DD_NAME);
            }
            if (!acctfileCloseIssued) {
                releaseQuietly(acctfile, ACCTFILE_DD_NAME);
            }
            if (!cardfileCloseIssued) {
                releaseQuietly(cardfile, CARDFILE_DD_NAME);
            }
            if (!xreffileCloseIssued) {
                releaseQuietly(xreffile, XREFFILE_DD_NAME);
            }
            if (!custfileCloseIssued) {
                releaseQuietly(custfile, CUSTFILE_DD_NAME);
            }
            if (!dalytranCloseIssued) {
                releaseQuietly(dalytranFile, DALYTRAN_DD_NAME);
            }
        }

        // -------------------------------------------------------------------------------------------
        // Z-DISPLAY-IO-STATUS and Z-ABEND-PROGRAM - app/cbl/CBTRN01C.cbl:469-489.
        //
        // Note the paragraph names: this program prefixes them Z-, where its siblings CBTRN02C and
        // CBTRN03C use 9999- and 9910-. The bodies are identical; only the labels differ.
        // -------------------------------------------------------------------------------------------

        /**
         * The four-statement failing arm the twelve file verbs and the read all share, followed by the
         * abend.
         *
         * <p>Written once because all thirteen sites are written identically in the source apart from their
         * literal and the status field they move: {@code DISPLAY '<em>text</em>'},
         * {@code MOVE <em>file</em>-STATUS TO IO-STATUS}, {@code PERFORM Z-DISPLAY-IO-STATUS},
         * {@code PERFORM Z-ABEND-PROGRAM}. Both are parameters, and the status is a parameter rather than
         * being derived from the text precisely so that {@link #closeDalytran()} can pass the customer
         * file's status with the customer file's text - which is what defect 2 is.
         *
         * <p>The order matters and is preserved: the error text, then the file-status line, then
         * {@code 'ABENDING PROGRAM'}.
         *
         * <p>Returns the exception rather than throwing it, so each call site reads
         * {@code throw reportAndAbend(...)} and the compiler can see that control does not continue past the
         * arm - as it does not after {@code CALL 'CEE3ABD'}.
         *
         * @param errorText the paragraph's own {@code DISPLAY} literal
         * @param status    the two-character status the paragraph moves into {@code IO-STATUS}
         * @return the abend to throw; never {@code null}
         */
        private AbendException reportAndAbend(String errorText, String status) {
            sysout.display(errorText);                         // DISPLAY '<text>'
            ioStatus = status;                                 // MOVE <file>-STATUS TO IO-STATUS
            displayIoStatus();                                 // PERFORM Z-DISPLAY-IO-STATUS
            return abendProgram(errorText);                    // PERFORM Z-ABEND-PROGRAM
        }

        /**
         * {@code Z-DISPLAY-IO-STATUS} - {@code app/cbl/CBTRN01C.cbl:476-489}.
         *
         * <p>The line is composed by {@link FileStatus#toDisplayLine(String)} rather than assembled here, so
         * every emission site across the migrated batch programs produces one byte-identical form of
         * {@code DISPLAY 'FILE STATUS IS: NNNN' IO-STATUS-04} - including the paragraph's two arms: a
         * numeric status renders as {@code '00'} followed by its two characters, and an extended status
         * whose first byte is {@code '9'} renders that byte followed by the second byte's numeric value in
         * three digits.
         */
        private void displayIoStatus() {
            sysout.display(FileStatus.toDisplayLine(ioStatus));
        }

        /**
         * {@code Z-ABEND-PROGRAM} - {@code app/cbl/CBTRN01C.cbl:469-473}.
         *
         * <pre>
         * Z-ABEND-PROGRAM.                                                 :469
         *     DISPLAY 'ABENDING PROGRAM'                                   :470
         *     MOVE 0 TO TIMING                                             :471
         *     MOVE 999 TO ABCODE                                           :472
         *     CALL 'CEE3ABD'.                                              :473
         * </pre>
         *
         * <p>The display happens here, before the exception is built, because the COBOL writes that line
         * before it calls the Language Environment service - and because a caller that failed to throw what
         * this returns would still have emitted the line the program emits.
         *
         * <p>{@link AbendException#standard(String, int, String, Throwable)} supplies {@code ABCODE} 999 and
         * {@code TIMING} 0, the two values {@code :471} and {@code :472} set. The {@code RETURN-CODE} is the
         * live {@code APPL-RESULT}, which every failing arm has already moved
         * {@value #APPL_RESULT_FATAL} into, and {@code BatchConfig}'s listeners carry it onto the step and
         * job exit status and thence onto the process exit code (gate <strong>G35</strong>).
         *
         * @param reason the message the failing paragraph already displayed, carried as the abend's reason
         * @return the abend to throw; never {@code null}
         */
        private AbendException abendProgram(String reason) {
            // :470  DISPLAY 'ABENDING PROGRAM'
            sysout.display(AbendException.ABEND_DISPLAY_TEXT);
            // :471-:473  MOVE 0 TO TIMING, MOVE 999 TO ABCODE, CALL 'CEE3ABD'.
            return AbendException.standard(PROGRAM_ID, applResult,
                    reason + " " + FileStatus.toDisplayLine(ioStatus), datasetRefusal);
        }
    }

    // =================================================================================================
    // The invalid-key condition, and the silent release. Static because neither reads per-run state, which
    // makes both reachable from a plain unit test with no Execution in the way.
    // =================================================================================================

    /**
     * Whether a {@code FILE STATUS} is the <strong>invalid key condition</strong> - the one a keyed
     * {@code READ}'s {@code INVALID KEY} phrase runs for.
     *
     * <p>Two statuses qualify for the two keyed reads this program issues: {@link FileStatus#NOT_FOUND}
     * ({@code '23'}), which is what an absent record reports, and {@link FileStatus#DUPLICATE}
     * ({@code '22'}), which is what this module's repositories report for a base key that is not unique.
     * Both are the invalid-key class, and both take the same arm.
     *
     * <p><strong>Everything else is neither arm.</strong> A successful read takes {@code NOT INVALID KEY};
     * any other status - an unreachable dataset, say - is reported through the {@code FILE STATUS} clause,
     * and with no {@code USE} procedure declared control simply passes to the statement after the
     * {@code READ} with neither conditional phrase executed. That is why the two lookup methods are written
     * as {@code if}/{@code else if} with no {@code else}: there is no third statement in the source to
     * translate.
     *
     * @param status the two-character status a read reported; never {@code null}
     * @return {@code true} when the {@code INVALID KEY} phrase applies
     */
    static boolean isInvalidKeyCondition(String status) {
        return FileStatus.isNotFound(status) || FileStatus.isDuplicate(status);
    }

    /**
     * Closes one handle of an incomplete run, silently, logging only the type of anything raised.
     *
     * <p>Only the failure's <em>type</em> is logged - never the throwable and never its message. A driver
     * composes its message around the value it refused, and the records this program handles carry card
     * numbers and account and customer identifiers (CWE-532); a newline in that text could forge a second
     * log entry (CWE-117). A class name carries neither data nor a newline.
     *
     * @param handle the handle to release; ignored when {@code null}, which is what a file whose open failed
     *               leaves behind
     * @param ddName the DD name, named in the diagnostic
     */
    private static void releaseQuietly(AutoCloseable handle, String ddName) {
        if (handle == null) {
            return;
        }
        try {
            handle.close();
        } catch (Exception cleanupFailure) {
            LOG.warn("Releasing the " + ddName + " handle of " + PROGRAM_ID + " after an incomplete run "
                    + "failed - " + cleanupFailure.getClass().getName() + ". The run's own outcome is "
                    + "reported unchanged, because the run's own failure is the one that matters.");
        }
    }

    // =================================================================================================
    // SYSOUT. This program has no JCL, so it has no //SYSOUT DD to transcribe: where its DISPLAY lines
    // actually go is a deployment-time input, and it arrives as an injected sink.
    // =================================================================================================

    /**
     * The {@code SYSOUT} destination for one execution: whatever the container publishes, else
     * {@link #defaultSysoutSink()}.
     *
     * <p>Resolved per execution rather than cached, so a deployment can publish a sink whose lifecycle is
     * its own.
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
     *
     * <ul>
     *   <li><strong>Verbatim.</strong> No timestamp, no level, no logger name, no thread. A {@code DISPLAY}
     *       writes the line and nothing else, and any prefix would make every line differ from the legacy
     *       output it is compared against. This is also why it is not a logging framework: a logger's layout
     *       is configuration, and the byte content of this program's output is not negotiable.</li>
     *   <li><strong>In the dataset code page.</strong> The 350-byte record image this program displays is
     *       dataset characters, so it reaches {@code SYSOUT} in the code page the dataset was read in rather
     *       than in whatever the platform default happens to be. The codec refuses a character the code page
     *       cannot represent instead of substituting a question mark for it.</li>
     *   <li><strong>One line feed, stated.</strong> Not the platform line separator, which would emit two
     *       bytes on some hosts and one on others for the same program.</li>
     *   <li><strong>Flushed per line.</strong> A failing arm displays a message, then a status, then
     *       {@code 'ABENDING PROGRAM'}, and then the process ends non-zero. Buffered output could lose
     *       precisely the three lines that explain why.</li>
     * </ul>
     *
     * @return a sink writing to the process's standard output; never {@code null}
     */
    public SysoutSink defaultSysoutSink() {
        // System.out is the JVM's handle on the process's standard output stream, which is what a
        // //SYSOUT DD SYSOUT=* becomes off the mainframe. It is named once, here, and never from the program
        // body: Execution only ever calls SysoutSink.display, so a test or a parity harness substitutes a
        // destination without the program knowing the difference.
        return sysoutSinkTo(System.out);
    }

    /**
     * The same sink over a caller-chosen stream, for a deployment whose {@code SYSOUT} is a file, a pipe or
     * an in-memory buffer rather than the process's own output.
     *
     * <p>Identical to {@link #defaultSysoutSink()} in every respect except where the bytes land. The
     * stream's lifecycle belongs to the caller - this sink never closes what it did not open, because a
     * {@code DISPLAY} does not close {@code SYSOUT}.
     *
     * @param destination where the bytes go; never {@code null}
     * @return a sink writing to that stream; never {@code null}
     * @throws NullPointerException if {@code destination} is {@code null}
     */
    public SysoutSink sysoutSinkTo(OutputStream destination) {
        return new StreamSysoutSink(destination, codec);
    }

    /**
     * Where one {@code DISPLAY} statement's line goes.
     *
     * <p>The seam that keeps this program's observable output observable. A test or a parity harness supplies
     * a sink that collects the lines and asserts on them; a deployment publishes one that writes them where
     * its operators read them; and {@link #defaultSysoutSink()} covers the case where nobody does either.
     *
     * <p>Declared here rather than in the shared package on purpose: each batch program has its own
     * {@code SYSOUT}, and a per-job type means sibling job classes can each accept an injected sink without
     * their beans becoming ambiguous with one another.
     *
     * <p>One line per call, already complete: the implementation adds a terminator and nothing else. It must
     * not reorder, buffer across a failure boundary, deduplicate or otherwise improve on what it is given -
     * the extra post-end-of-file lookup of defect 1 emits lines that look redundant and are not, and a sink
     * that noticed and collapsed them would break the very parity this seam exists to demonstrate.
     */
    @FunctionalInterface
    public interface SysoutSink {

        /**
         * Emits one line of {@code SYSOUT}.
         *
         * <p>The line arrives without a terminator and must not be trimmed, wrapped, re-encoded or
         * decorated. Trailing spaces are significant: a raw {@code DALYTRAN-RECORD} image ends in the twenty
         * bytes of {@code CVTRA06Y}'s trailing {@code FILLER} (gate <strong>G21</strong>).
         *
         * @param line the line, exactly as the program composed it; never {@code null}
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

        /** The destination stream, supplied rather than chosen by this class. */
        private final OutputStream destination;

        /** The codec whose code page each line is encoded in. */
        private final FixedWidthCodec codec;

        /**
         * @param destination where the bytes go
         * @param codec       the code page to encode them in
         */
        private StreamSysoutSink(OutputStream destination, FixedWidthCodec codec) {
            this.destination = Objects.requireNonNull(destination, "A destination stream is required for "
                    + "SYSOUT: " + PROGRAM_ID + "'s DISPLAY sequence is its entire observable output");
            this.codec = Objects.requireNonNull(codec, "A codec is required: a SYSOUT line is written in a "
                    + "named code page, never in the platform default");
        }

        /**
         * Writes the line and its terminator, then flushes.
         *
         * @param line the line to emit
         * @throws NullPointerException if {@code line} is {@code null}
         * @throws UncheckedIOException if the stream refuses the write. Unchecked because
         *                              {@link SysoutSink#display(String)} declares no checked exception: a
         *                              COBOL {@code DISPLAY} has no failure path for the program to handle,
         *                              so there is none to translate
         */
        @Override
        public void display(String line) {
            Objects.requireNonNull(line, "A line is required to display; " + PROGRAM_ID + " never displays "
                    + "nothing");
            byte[] encoded = codec.encodeImage(line + SYSOUT_LINE_TERMINATOR, SYSOUT_SUBJECT);
            try {
                destination.write(encoded);
                destination.flush();
            } catch (IOException refused) {
                throw new UncheckedIOException("Could not write a SYSOUT line of " + PROGRAM_ID + " to its "
                        + "destination stream; " + encoded.length + " byte(s) were pending", refused);
            }
        }
    }

    // =================================================================================================
    // What one execution produced.
    // =================================================================================================

    /**
     * The outcome of one execution of {@value #PROGRAM_ID}.
     *
     * <p>Step metadata and test evidence, never {@code SYSOUT}: {@value #PROGRAM_ID} keeps no counter of its
     * own and displays none of these numbers.
     *
     * @param recordsRead  how many daily transaction records the pass read successfully, which is how many
     *                     raw record images it displayed - one each, because this program's only
     *                     {@code DISPLAY DALYTRAN-RECORD} is the mainline's at {@code :168}
     * @param returnCode   the {@code RETURN-CODE} the program left, which is
     *                     {@link AbendException#RETURN_CODE_OK} for every execution that returns at all:
     *                     {@value #PROGRAM_ID} never moves a value into {@code RETURN-CODE}, and every
     *                     failure path abends rather than returning
     * @param xrefLookups  how many times {@code 2000-LOOKUP-XREF} ran, which is always
     *                     {@code recordsRead + 1} - see {@link #postEndOfFileLookups()}
     * @param accountReads how many times {@code 3000-READ-ACCOUNT} ran, which is one per lookup that did not
     *                     report an invalid key
     */
    public record ExecutionSummary(int returnCode, int recordsRead, int xrefLookups, int accountReads) {

        /**
         * How many cross-reference lookups a run performs beyond the number of records it read:
         * <strong>one</strong>, always, and never zero.
         *
         * <p>This is defect 1 expressed as a number. The lookup at {@code app/cbl/CBTRN01C.cbl:170-172} sits
         * outside the end-of-file guard that closes one line above it, so the iteration whose read reported
         * end of file performs one more lookup - on the previous record's card number - before the loop
         * test ends the pass. A run over an empty dataset therefore performs one lookup and reads no
         * records.
         */
        public static final int POST_END_OF_FILE_LOOKUPS = 1;

        /**
         * Rejects a summary no execution could have produced.
         *
         * <p>Three invariants, and the third is the one worth having: {@code xrefLookups} is
         * {@code recordsRead} plus exactly {@value #POST_END_OF_FILE_LOOKUPS}. Stating it here means every
         * caller and every test that builds an expectation is held to defect 1 rather than merely being able
         * to check for it.
         *
         * @throws IllegalArgumentException if any count is negative, if the lookup count is not the record
         *                                  count plus one, or if there were more account reads than lookups
         */
        public ExecutionSummary {
            if (recordsRead < 0 || xrefLookups < 0 || accountReads < 0) {
                throw new IllegalArgumentException("An execution of " + PROGRAM_ID + " cannot have read "
                        + recordsRead + " record(s), performed " + xrefLookups + " cross-reference "
                        + "lookup(s) and " + accountReads + " account read(s): none of those can be "
                        + "negative.");
            }
            if (xrefLookups != recordsRead + POST_END_OF_FILE_LOOKUPS) {
                throw new IllegalArgumentException("An execution of " + PROGRAM_ID + " that read "
                        + recordsRead + " record(s) performs exactly "
                        + (recordsRead + POST_END_OF_FILE_LOOKUPS) + " cross-reference lookup(s), not "
                        + xrefLookups + ". The extra one is not optional: app/cbl/CBTRN01C.cbl:170-172 sits "
                        + "outside the end-of-file guard that closes at :169, so the iteration whose read "
                        + "reported end of file looks up the previous record's card number before the loop "
                        + "ends. See TransactionPostingJob's documentation, defect 1.");
            }
            if (accountReads > xrefLookups) {
                throw new IllegalArgumentException("An execution of " + PROGRAM_ID + " cannot have "
                        + "performed " + accountReads + " account read(s) against " + xrefLookups
                        + " cross-reference lookup(s): app/cbl/CBTRN01C.cbl:176 reads the account only on "
                        + "the arm a lookup that did not report an invalid key selects, so there is at most "
                        + "one account read per lookup.");
            }
        }

        /**
         * The one lookup this run performed beyond its record count.
         *
         * @return {@value #POST_END_OF_FILE_LOOKUPS}, always
         */
        public int postEndOfFileLookups() {
            return xrefLookups - recordsRead;
        }

        /**
         * How many raw {@code DALYTRAN-RECORD} images the run displayed: one per record read.
         *
         * <p>One and not two. Its sibling {@code CBACT03C} displays each record twice because
         * {@code 1000-XREFFILE-GET-NEXT} has a live {@code DISPLAY} of its own;
         * {@code 1000-DALYTRAN-GET-NEXT} has none, so the only image line is the mainline's.
         *
         * @return the number of record image lines
         */
        public int recordImageLinesDisplayed() {
            return recordsRead;
        }

        /**
         * Whether the run ended with {@code RETURN-CODE} zero, which every execution that returns at all
         * does.
         *
         * @return {@code true} when the return code is {@link AbendException#RETURN_CODE_OK}
         */
        public boolean completedCleanly() {
            return returnCode == AbendException.RETURN_CODE_OK;
        }
    }
}
