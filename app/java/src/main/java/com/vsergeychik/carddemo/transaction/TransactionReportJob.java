package com.vsergeychik.carddemo.transaction;

import com.vsergeychik.carddemo.card.CardXrefRepository;
import com.vsergeychik.carddemo.card.CardXrefRepository.BrowseCursor;
import com.vsergeychik.carddemo.card.model.CardXrefRecord;
import com.vsergeychik.carddemo.common.AbendException;
import com.vsergeychik.carddemo.common.CobolDecimal;
import com.vsergeychik.carddemo.common.DatasetRelation.BackendDiagnostic;
import com.vsergeychik.carddemo.common.FileStatus;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.PhysicalSequence;
import com.vsergeychik.carddemo.common.RecordImageForm;
import com.vsergeychik.carddemo.config.BatchConfig;
import com.vsergeychik.carddemo.config.BatchConfig.JobContract;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBinding;
import com.vsergeychik.carddemo.config.BatchConfig.StepContract;
import com.vsergeychik.carddemo.config.BatchConfig.StopSignal;
import com.vsergeychik.carddemo.config.CobolCharsetConfig;
import com.vsergeychik.carddemo.config.DatasetUnitOfWork;
import com.vsergeychik.carddemo.statement.StatementGenerationJobA.DatasetUtilityPort;
import com.vsergeychik.carddemo.statement.StatementGenerationJobA.JdbcDatasetUtilityPort;
import com.vsergeychik.carddemo.transaction.model.TranCategoryRecord;
import com.vsergeychik.carddemo.transaction.model.TranRecord;
import com.vsergeychik.carddemo.transaction.model.TranReportLayouts;
import com.vsergeychik.carddemo.transaction.model.TranTypeRecord;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * {@code CBTRN03C} - print the transaction detail report - as a Spring Batch job.
 *
 * <p>The Java translation of {@code app/cbl/CBTRN03C.cbl} (649 lines), run on the mainframe as the
 * third and last step of {@code app/jcl/TRANREPT.jcl}. The prompt-mandated class name and the
 * program's own {@code Function :} header agree here, which is unusual in this migration and is
 * therefore worth stating: the source says "Print the transaction detail report", and that is
 * exactly what this class does. No behavioural caveat attaches to the name.
 *
 * <h2>A Tasklet, and why it cannot be a chunk step</h2>
 *
 * <p>{@code CBTRN03C} carries five pieces of state <em>across</em> records: three running totals
 * ({@code WS-PAGE-TOTAL}, {@code WS-ACCOUNT-TOTAL}, {@code WS-GRAND-TOTAL}), a line counter that
 * decides where a page break falls, and the card number of the account currently being reported.
 * A chunk-oriented step would relocate the commit boundaries and with them the order in which lines
 * reach the output, which is precisely the observable this migration is measured against, so the
 * single-pass tasklet is the only provably identical shape (AAP section 0.3.5).
 *
 * <h2>The three JCL steps, all three of them run here</h2>
 *
 * <p>{@code app/jcl/TRANREPT.jcl} names its first two steps {@code STEP05R} twice - at {@code L23}
 * and again at {@code L37}, which is a defect in that JCL - while
 * {@code app/proc/TRANREPT.prc} spells the same sequence unambiguously as {@code STEP01R}
 * ({@code L21}), {@code STEP05R} ({@code L35}) and {@code STEP10R} ({@code L57}). Configuration uses
 * the {@code .prc} names, and so does this class.
 *
 * <ol>
 *   <li>{@code STEP01R} - {@code EXEC PROC=REPROC} unloads {@code TRANSACT.VSAM.KSDS} to
 *       {@code TRANSACT.BKUP(+1)} at {@code LRECL=350 RECFM=FB}.</li>
 *   <li>{@code STEP05R} - {@code EXEC PGM=SORT} filters and orders that backup, writing
 *       {@code TRANSACT.DALY(+1)}. Its {@code SYMNAMES} are
 *       {@code TRAN-CARD-NUM,263,16,ZD} and {@code TRAN-PROC-DT,305,10,CH}; its control statements
 *       are {@code SORT FIELDS=(TRAN-CARD-NUM,A)} and
 *       {@code INCLUDE COND=(TRAN-PROC-DT,GE,PARM-START-DATE,AND,TRAN-PROC-DT,LE,PARM-END-DATE)}.</li>
 *   <li>{@code STEP10R} - {@code EXEC PGM=CBTRN03C}. <strong>The migrated program, and the only one
 *       of the three steps that is a COBOL program at all.</strong></li>
 * </ol>
 *
 * <p><strong>All three are Spring Batch steps of this job, in that order.</strong>
 * {@link #transactionReportBackupStep()} is the {@code REPRO}, {@link #transactionReportSortStep()} is
 * the DFSORT, and {@link #transactionReportStep()} is the program. The first two run mainframe
 * utilities over whole record images rather than any COBOL, so they hold no program logic and reach
 * their datasets through the shared {@link DatasetUtilityPort} - the same
 * contract the statement job's three utility steps use.
 *
 * <p>The order is load-bearing, not cosmetic. This job's {@code TRANFILE} is
 * {@code TRANSACT.DALY(+1)} - the sorted, date-filtered sequential dataset the SORT step produces -
 * presented in <em>ascending card-number order</em>. The account-break logic at {@code :181-188}
 * depends entirely on that order: it writes an account total whenever the card number changes, so an
 * unsorted input would emit one "account total" per change of card rather than one per account. Run
 * the program without its two predecessors and it reports whatever a previous run happened to leave
 * in that dataset.
 *
 * <p>Every transition is unconditional: {@code app/jcl/TRANREPT.jcl} carries no step-level
 * {@code COND} at all, the {@code COND=} it contains being the SORT's {@code INCLUDE} filter, which is
 * why {@link BatchConfig#precedingExitCodeZeroDecider()} is deliberately not wired into this job. The
 * contract in {@code carddemo.jobs.transaction-report-job.steps} records all three and is validated at
 * startup, so a profile can neither drop a step nor gate one.
 *
 * <p>The two SORT offsets independently confirm {@link TranRecord}'s layout, and that corroboration
 * is worth keeping: {@code TRAN-CARD-NUM} is declared at one-based position <strong>263</strong> for
 * 16 bytes and {@code TRAN-PROC-TS} at one-based position <strong>305</strong>, whose leading 10
 * bytes are the {@code TRAN-PROC-DT} the SORT filters on. Both agree with
 * {@code app/cpy/CVTRA05Y.cpy} field for field.
 *
 * <h2>The datasets of {@code STEP10R}</h2>
 *
 * <table border="1">
 *   <caption>DD bindings, from {@code app/jcl/TRANREPT.jcl:L62-L80}</caption>
 *   <tr><th>DD</th><th>Direction</th><th>What it is</th></tr>
 *   <tr><td>{@value #TRANFILE_DD_NAME}</td><td>input</td>
 *       <td>{@code TRANSACT.DALY(+1)}, sequential, 350 bytes, card-number ascending</td></tr>
 *   <tr><td>{@value #CARDXREF_DD_NAME}</td><td>input</td>
 *       <td>the card cross-reference KSDS, read randomly by card number</td></tr>
 *   <tr><td>{@value #TRANTYPE_DD_NAME}</td><td>input</td>
 *       <td>the transaction type KSDS, read randomly by 2-byte type code</td></tr>
 *   <tr><td>{@value #TRANCATG_DD_NAME}</td><td>input</td>
 *       <td>the transaction category KSDS, read randomly by 6-byte type-plus-category key</td></tr>
 *   <tr><td>{@value #DATEPARM_DD_NAME}</td><td>input</td>
 *       <td>the reporting date range, read once as a single 80-byte record</td></tr>
 *   <tr><td>{@value #TRANREPT_DD_NAME}</td><td>output</td>
 *       <td>{@code TRANREPT(+1)}, {@code RECFM=FB LRECL=133}</td></tr>
 * </table>
 *
 * <p><strong>The date range is a dataset, not a job parameter.</strong> {@code STEP10R} is a bare
 * {@code EXEC PGM=} with no {@code PARM}, and {@code 0550-DATEPARM-READ} reads the range from the
 * {@value #DATEPARM_DD_NAME} DD at {@code :221}. This job therefore declares
 * <strong>no</strong> {@link org.springframework.batch.core.JobParameters} of any kind and obtains
 * the range from the injected {@link DateParmReader}; the constructor rejects a contract that
 * declares a parameter, and rejects one whose {@code date-range-source} is anything but
 * {@value #DATE_RANGE_SOURCE}.
 *
 * <h2>Two defects that must survive, and one omission</h2>
 *
 * <p>Practice B5 - preserve behaviour including defects - is the dominant constraint on this file.
 * Both of the following look like bugs a well-meaning implementer would quietly fix, and both were
 * verified by reading the source rather than taken on trust.
 *
 * <p><strong>Defect 1 - {@code NEXT SENTENCE} at {@code :177} ends the whole read loop.</strong>
 * {@code NEXT SENTENCE} transfers control to the statement after the period that terminates the
 * <em>current sentence</em>, and the current sentence at {@code :177} is the entire
 * {@code PERFORM UNTIL ... END-PERFORM.} whose period is at {@code :206}. So the first record whose
 * {@code TRAN-PROC-TS (1:10)} falls outside the range does not merely skip that record - it
 * terminates the read loop, and the page and grand totals at {@code :202-203} are never written.
 * See {@link ReportRun#run()}, where it is a {@code break} and never a {@code continue}.
 *
 * <p><strong>Defect 2 - the final amount is counted twice at {@code :200}.</strong> The
 * {@code ELSE} arm at {@code :197} runs when the read reported end of file, and a COBOL
 * {@code READ ... INTO} leaves the receiving area untouched at {@code AT END}, so
 * {@code TRAN-RECORD} still holds the <em>last successfully read record</em>. Its
 * {@code TRAN-AMT} has already been added to both accumulators by
 * {@code 1100-WRITE-TRANSACTION-REPORT}, and {@code :200-201} adds it a second time before the page
 * and grand totals are written. Both totals are therefore overstated by the last record's amount.
 *
 * <p><strong>The omission: the last account's total is never written.</strong> The end-of-file arm
 * performs {@code 1110-WRITE-PAGE-TOTALS} and {@code 1110-WRITE-GRAND-TOTALS} but never
 * {@code 1120-WRITE-ACCOUNT-TOTALS}, so the accumulated total of the final account is added to and
 * then discarded. That is not one of the two defects named above; it is simply what the program
 * does, and it is preserved for the same reason.
 *
 * <p>A related deliberate asymmetry, {@code 1110-WRITE-PAGE-TOTALS} versus
 * {@code 1120-WRITE-ACCOUNT-TOTALS}: <strong>only page totals roll into the grand total</strong>
 * ({@code :297}). Account totals are written and zeroed and never accumulated anywhere. The grand
 * total is consequently the sum of the page totals and bears no arithmetic relationship to the sum
 * of the account totals. Making the two agree would be a business-rule change.
 *
 * <h2>Numbers, statelessness and rendering</h2>
 *
 * <p>The three running totals are {@code PIC S9(09)V99} ({@code :134-136}), so each is a
 * {@link BigDecimal} at scale exactly {@value #TOTAL_SCALE} maintained through
 * {@link CobolDecimal#storeAtPicture(BigDecimal, int, int)} at
 * {@value #TOTAL_INTEGER_DIGITS} integer digits - which truncates toward zero, because
 * {@code ROUNDED} appears nowhere in the 28 programs, and which wraps rather than throws on
 * high-order overflow, because {@code ON SIZE ERROR} appears nowhere either. No
 * {@code double} and no {@code float} is used for anything (gate G22), and no rounding mode but
 * {@link java.math.RoundingMode#DOWN} is reachable from here (gate G24).
 *
 * <p>Every piece of {@code WORKING-STORAGE} becomes per-execution state on {@link ReportRun}.
 * Nothing mutable is static (practice B9, gate G53), every collaborator is constructor-injected, and
 * two concurrent executions share nothing but the immutable collaborators.
 *
 * <p>Report line rendering belongs to {@link TranReportLayouts} and width normalisation to the
 * 133-byte record belongs to {@link TranReportWriter}. This class supplies values and, above all,
 * <em>ordering</em>: which line is written when, and which counter and accumulator moves happen
 * between two writes. No amount is formatted here, so no locale-dependent formatter exists to be
 * misconfigured, and the charset is always the explicitly named dataset code page (practice B8).
 *
 * <h2>Running it</h2>
 *
 * <p>{@link #execute(SysoutSink)} and
 * {@link #execute(SysoutSink, TranReportWriter.RecordSink)} are the program body and need neither a
 * {@code JobLauncher} nor an application context nor a database, which is what makes every branch -
 * six open ladders, six close ladders, three {@code EVALUATE} arms, three keyed-lookup failures, the
 * page boundary, the account break and both defects - reachable from a plain unit test (practice
 * B10, gate G51). The 20 declarative parity cases for {@code CBTRN03C} drive it the same way; their
 * expected values are <em>statically derived</em> from the copybooks, the JCL and the ASCII fixtures
 * rather than captured from a live COBOL run, which is impossible in this environment (risk R-A).
 *
 * <p>{@code review_rules} returns exactly one line - "No user rules provided." - so no user rule
 * governs this file; the AAP's practices B1 to B12 bind in their place.
 */
@Configuration(TransactionReportJob.CONFIGURATION_BEAN_NAME)
public class TransactionReportJob {

    // =================================================================================================
    // Identity. Every name is a configuration key or a JCL name, never a literal invented here.
    // =================================================================================================

    /**
     * Where a swallowed cleanup failure is recorded.
     *
     * <p>Used by {@link ReportRun#releaseHandles()} and by nothing else. Every condition the program
     * itself reports goes to {@code SYSOUT} through a {@link SysoutSink}, because a {@code DISPLAY} is
     * parity-relevant output rather than a log line; this logger carries only what happens
     * <em>outside</em> the program, on a path the COBOL has no statement for.
     */
    private static final Log LOG = LogFactory.getLog(TransactionReportJob.class);

    /**
     * Where a swallowed cleanup failure is described.
     *
     * <p>The throwable itself is never passed to the logger and neither is its message: a driver's text
     * is outside this module's control, and a newline in it would let the message forge a second entry
     * (CWE-117). {@link BackendDiagnostic} carries the {@code SQLSTATE}, the vendor code and the
     * exception type and has no component for a message, which is this module's established way to keep
     * a failure diagnosable without repeating what the backend said.
     *
     * @param ddName         the DD whose handle was being released
     * @param cleanupFailure what the release threw
     */
    private static void reportCleanupFailure(String ddName, RuntimeException cleanupFailure) {
        LOG.warn("Releasing the " + ddName + " handle of " + PROGRAM_NAME + " after an incomplete run "
                + "failed - " + BackendDiagnostic.of(cleanupFailure).describe()
                + ". The run's own outcome is reported to the caller unchanged, because the run's own "
                + "failure is the one that matters.");
    }

    /**
     * The bean name of this configuration class, stated rather than derived from the class name so a
     * rename cannot silently change a bean name an operator may have referred to.
     */
    public static final String CONFIGURATION_BEAN_NAME = "transactionReportJobConfiguration";

    /**
     * The key this job's contract is configured under, {@code carddemo.jobs.transaction-report-job}.
     *
     * <p>{@code BatchConfig.JobContracts.REQUIRED_JOBS} pairs this key with
     * {@value #PROGRAM_NAME}, and the pairing is validated at startup from both ends.
     */
    public static final String JOB_KEY = "transaction-report-job";

    /** The job's name in the Spring Batch metadata, and the name of its bean. */
    public static final String JOB_NAME = "transactionReportJob";

    /** The bean name of the step that runs the program. */
    public static final String STEP_BEAN_NAME = "transactionReportStep";

    /** The bean name of the {@value #BACKUP_STEP_NAME} unload step. */
    public static final String BACKUP_STEP_BEAN_NAME = "transactionReportBackupStep";

    /** The bean name of the {@value #SORT_STEP_NAME} filter-and-sort step. */
    public static final String SORT_STEP_BEAN_NAME = "transactionReportSortStep";

    /** The COBOL {@code PROGRAM-ID} this class translates. */
    public static final String PROGRAM_NAME = "CBTRN03C";

    /**
     * The step this class is: {@code //STEP10R EXEC PGM=CBTRN03C}
     * ({@code app/jcl/TRANREPT.jcl:L59}, {@code app/proc/TRANREPT.prc:L57}).
     */
    public static final String STEP_NAME = "STEP10R";

    /**
     * The first step, {@code EXEC PROC=REPROC} ({@code app/proc/TRANREPT.prc:L21}) - the
     * {@code IDCAMS REPRO INFILE(FILEIN) OUTFILE(FILEOUT)} of {@code app/ctl/REPROCT.ctl:L15}, which
     * unloads the transaction master onto the backup generation.
     *
     * <p>Run by {@link #transactionReportBackupStep()}. It is not decoration: without it the sort step
     * has nothing to read, and {@value #STEP_NAME} would have to report straight from the master.
     */
    public static final String BACKUP_STEP_NAME = "STEP01R";

    /**
     * The second step, {@code EXEC PGM=SORT} ({@code app/proc/TRANREPT.prc:L35}) - the DFSORT that
     * filters the unloaded records to the reporting date range and orders them by card number.
     *
     * <p>Run by {@link #transactionReportSortStep()}. Its output <em>is</em> {@value #STEP_NAME}'s
     * input and its sort order is the precondition the account-break logic rests on.
     */
    public static final String SORT_STEP_NAME = "STEP05R";

    /**
     * The utility {@value #BACKUP_STEP_NAME} runs: {@code EXEC PGM=IDCAMS}, reached through
     * {@code EXEC PROC=REPROC} at {@code app/proc/TRANREPT.prc:L21} whose own {@code PRC001} step is
     * {@code EXEC PGM=IDCAMS} in {@code app/proc/REPROC.prc}.
     */
    public static final String BACKUP_STEP_PROGRAM = "IDCAMS";

    /** The utility {@value #SORT_STEP_NAME} runs: {@code EXEC PGM=SORT}, {@code app/proc/TRANREPT.prc:L35}. */
    public static final String SORT_STEP_PROGRAM = "SORT";

    /**
     * The whole step sequence of {@code app/proc/TRANREPT.prc}: the {@value #BACKUP_STEP_PROGRAM}
     * unload, the {@value #SORT_STEP_PROGRAM} filter-and-sort, then {@value #PROGRAM_NAME}, in that
     * order and none of them gated.
     *
     * <p>The names alone are not the contract. Each step's {@code EXEC PGM=} is part of it too, and it is
     * the part a name-only comparison is blind to: a contract naming {@value #SORT_STEP_PROGRAM} on
     * {@value #BACKUP_STEP_NAME} would keep the sequence looking right while asking the unload step to
     * run a sort against {@code FILEIN} and {@code FILEOUT}, which are DD names DFSORT does not use.
     * Because the utility steps take their data path from configuration, that failure would surface as
     * an empty backup rather than as a wrong program.
     */
    public static final List<StepContract> REQUIRED_STEPS = List.of(
            new StepContract(BACKUP_STEP_NAME, BACKUP_STEP_PROGRAM, false),
            new StepContract(SORT_STEP_NAME, SORT_STEP_PROGRAM, false),
            new StepContract(STEP_NAME, PROGRAM_NAME, false));

    // =================================================================================================
    // DD names. Each is a configuration key; what it resolves to is configuration's business, so no
    // dataset name appears anywhere in this file (gate G46).
    // =================================================================================================

    /**
     * The sequential input: {@code SELECT TRANSACT-FILE ASSIGN TO TRANFILE}
     * ({@code app/cbl/CBTRN03C.cbl:L29}), bound by
     * {@code carddemo.jobs.transaction-report-job.datasets.TRANFILE} to the sorted daily file.
     *
     * <p>The job-scoped binding matters: the <em>global</em> {@code TRANFILE} entry is the transaction
     * master that {@code CBTRN02C} posts to, and reporting from it would report unsorted, unfiltered
     * records. That is why {@link TransactionRepository#openInput} is called with this job's own
     * binding rather than with no argument.
     */
    public static final String TRANFILE_DD_NAME = "TRANFILE";

    /**
     * The card cross-reference: {@code SELECT XREF-FILE ASSIGN TO CARDXREF}
     * ({@code app/cbl/CBTRN03C.cbl:L33-L37}), {@code ORGANIZATION IS INDEXED},
     * {@code ACCESS MODE IS RANDOM}, {@code RECORD KEY IS FD-XREF-CARD-NUM}.
     *
     * <p>The batch DD is spelled {@code CARDXREF} while the CICS file of the same cluster is spelled
     * {@code CCXREF}; {@link CardXrefRepository} addresses the cluster and this constant records the
     * name this program's {@code ASSIGN} clause uses.
     */
    public static final String CARDXREF_DD_NAME = "CARDXREF";

    /**
     * The transaction type file: {@code SELECT TRANTYPE-FILE ASSIGN TO TRANTYPE}
     * ({@code app/cbl/CBTRN03C.cbl:L39-L43}), keyed on the 2-byte {@code FD-TRAN-TYPE}.
     */
    public static final String TRANTYPE_DD_NAME = TranTypeRepository.DD_NAME;

    /**
     * The transaction category file: {@code SELECT TRANCATG-FILE ASSIGN TO TRANCATG}
     * ({@code app/cbl/CBTRN03C.cbl:L45-L49}), keyed on the 6-byte {@code FD-TRAN-CAT-KEY}.
     */
    public static final String TRANCATG_DD_NAME = TranCategoryRepository.DD_NAME;

    /**
     * The reporting date range: {@code SELECT DATE-PARMS-FILE ASSIGN TO DATEPARM}
     * ({@code app/cbl/CBTRN03C.cbl:L55-L57}), one 80-byte sequential record.
     */
    public static final String DATEPARM_DD_NAME = DateParmReader.DD_NAME;

    /**
     * The report output: {@code SELECT REPORT-FILE ASSIGN TO TRANREPT}
     * ({@code app/cbl/CBTRN03C.cbl:L51-L53}), {@code RECFM=FB LRECL=133}.
     */
    public static final String TRANREPT_DD_NAME = TranReportWriter.DD_NAME;

    /**
     * Names the disposition applied when this run does not complete normally:
     * {@code DISP=(NEW,CATLG,DELETE)} on {@value #TRANREPT_DD_NAME}
     * ({@code app/jcl/TRANREPT.jcl:76-80}).
     *
     * <p>Carried into {@link DatasetUnitOfWork#persistDisposition(String, java.util.function.Supplier)}
     * so a disposition that itself fails can name what it was applying.
     */
    static final String TRANREPT_ABNORMAL_DISPOSITION =
            TRANREPT_DD_NAME + " DISP=(NEW,CATLG,DELETE) abnormal disposition";

    /**
     * {@value #BACKUP_STEP_NAME}'s input: {@code //PRC001.FILEIN DD DISP=SHR}
     * ({@code app/jcl/TRANREPT.jcl:L26-L27}, {@code app/proc/TRANREPT.prc:L24-L25}), the transaction
     * master itself - which is why configuration binds it {@code alias: TRANSACT}.
     *
     * <p>{@code app/ctl/REPROCT.ctl:L15} names it as {@code REPRO INFILE(FILEIN)}, so the DD name is the
     * utility's own parameter and not an invention of this migration.
     */
    public static final String BACKUP_INPUT_DD_NAME = "FILEIN";

    /**
     * {@value #BACKUP_STEP_NAME}'s output: {@code //PRC001.FILEOUT DD DISP=(NEW,CATLG,DELETE)} with
     * {@code DCB=(LRECL=350,RECFM=FB,BLKSIZE=0)} ({@code app/jcl/TRANREPT.jcl:L29-L33}), the backup
     * generation - {@code REPRO OUTFILE(FILEOUT)}.
     */
    public static final String BACKUP_OUTPUT_DD_NAME = "FILEOUT";

    /**
     * {@value #SORT_STEP_NAME}'s input: {@code //SORTIN DD DISP=SHR}
     * ({@code app/jcl/TRANREPT.jcl:L38-L39}), the same backup generation
     * {@value #BACKUP_OUTPUT_DD_NAME} just wrote. Two DD names over one dataset, which is exactly how
     * the JCL hands one step's output to the next.
     */
    public static final String SORT_INPUT_DD_NAME = "SORTIN";

    /**
     * {@value #SORT_STEP_NAME}'s output: {@code //SORTOUT DD DISP=(NEW,CATLG,DELETE)} with
     * {@code DCB=(*.SORTIN)} ({@code app/jcl/TRANREPT.jcl:L51-L55}), the sorted daily file - and the
     * dataset this job's own {@value #TRANFILE_DD_NAME} binding then reports from.
     *
     * <p>{@code DCB=(*.SORTIN)} means "the geometry of {@value #SORT_INPUT_DD_NAME}", so the two
     * bindings must agree on width and record format; {@link #requireUtilityStepBindings()} checks that
     * rather than assuming it.
     */
    public static final String SORT_OUTPUT_DD_NAME = "SORTOUT";

    /**
     * The record format every dataset of the two utility steps declares: {@code FB}, fixed blocked
     * ({@code app/jcl/TRANREPT.jcl:L31}, and {@code L53}'s {@code DCB=(*.SORTIN)} by reference).
     *
     * <p>It is what makes "every record is exactly {@value TranRecord#RECORD_LENGTH} bytes" true, and
     * therefore what makes reading a fixed field at a fixed offset meaningful. The unload copies whole
     * records and the sort reads two fixed spans out of them, so a variable-format binding would leave
     * both steps operating on records whose fields are not where the SORT symbol table says they are.
     */
    public static final String UTILITY_RECORD_FORMAT = "FB";

    /**
     * The value {@code carddemo.jobs.transaction-report-job.date-range-source} must hold.
     *
     * <p>Configuration declares this key precisely because the range is <em>not</em> a {@code PARM}.
     * Asserting it at startup is what stops the one plausible mis-migration here: a
     * {@code parmDate}-style job parameter carrying a date range the COBOL has no statement to read.
     */
    public static final String DATE_RANGE_SOURCE = DATEPARM_DD_NAME;

    // =================================================================================================
    // The SORT symbol table of app/jcl/TRANREPT.jcl:L41-L42, transcribed. These are one-based COBOL
    // positions, and they corroborate CVTRA05Y independently of the copybook.
    // =================================================================================================

    /** {@code TRAN-CARD-NUM,263,16,ZD} - one-based position of the sort key. */
    public static final int SORT_TRAN_CARD_NUM_POSITION = TranRecord.TRAN_CARD_NUM_OFFSET + 1;

    /** {@code TRAN-CARD-NUM,263,16,ZD} - length of the sort key. */
    public static final int SORT_TRAN_CARD_NUM_LENGTH = TranRecord.TRAN_CARD_NUM_LENGTH;

    /** {@code TRAN-PROC-DT,305,10,CH} - one-based position of the include-filter field. */
    public static final int SORT_TRAN_PROC_DT_POSITION = TranRecord.TRAN_PROC_DT_OFFSET + 1;

    /** {@code TRAN-PROC-DT,305,10,CH} - length of the include-filter field. */
    public static final int SORT_TRAN_PROC_DT_LENGTH = TranRecord.TRAN_PROC_DT_LENGTH;

    /**
     * {@code PARM-START-DATE,C'2022-01-01'} - {@code app/jcl/TRANREPT.jcl:L43},
     * {@code app/proc/TRANREPT.prc:L41}.
     *
     * <p><strong>A DFSORT symbol, and deliberately not a property.</strong> It is the low bound of the
     * SORT step's {@code INCLUDE} filter and nothing else. It is emphatically <em>not</em> the report's
     * date range: {@code CBTRN03C} reads that from DD {@value #DATEPARM_DD_NAME}
     * ({@code app/cbl/CBTRN03C.cbl:L221}), which is what {@link #DATE_RANGE_SOURCE} records. The two are
     * independent in the source and are kept independent here, and {@code application.yml} says so in as
     * many words: these values "must never become configurable dates". Making them configurable would
     * hand an operator a second, silent way to change which transactions reach the report.
     */
    public static final String SORT_INCLUDE_START_DATE = "2022-01-01";

    /**
     * {@code PARM-END-DATE,C'2022-07-06'} - {@code app/jcl/TRANREPT.jcl:L44},
     * {@code app/proc/TRANREPT.prc:L42}. The high bound of the same {@code INCLUDE} filter, held for the
     * same reason and under the same prohibition as {@link #SORT_INCLUDE_START_DATE}.
     */
    public static final String SORT_INCLUDE_END_DATE = "2022-07-06";

    // =================================================================================================
    // Pagination and arithmetic shapes.
    // =================================================================================================

    /**
     * {@code 05 WS-PAGE-SIZE PIC 9(03) COMP-3 VALUE 20} - {@code app/cbl/CBTRN03C.cbl:L131-L132}.
     *
     * <p><strong>Twenty, and it is behaviour rather than configuration.</strong> It is deliberately
     * not the online page size of 10 that {@code COTRN00C} and {@code COUSR00C} use, and it is not
     * externally tunable: a different value would move every page boundary and with it the entire line
     * sequence of the report.
     */
    public static final int PAGE_SIZE = 20;

    /** Lines written by {@code 1120-WRITE-HEADERS}: four, each followed by {@code ADD 1}. */
    public static final int HEADER_LINES = 4;

    /**
     * Lines written by {@code 1110-WRITE-PAGE-TOTALS} and by {@code 1120-WRITE-ACCOUNT-TOTALS}: the
     * total line and the rule line that follows it, each followed by {@code ADD 1}.
     */
    public static final int TOTALS_BLOCK_LINES = 2;

    /**
     * {@code p} in the {@code PIC S9(09)V99} of the three running totals
     * ({@code app/cbl/CBTRN03C.cbl:L134-L136}).
     */
    public static final int TOTAL_INTEGER_DIGITS = 9;

    /** {@code s} in that same picture: two, the only monetary scale in the estate. */
    public static final int TOTAL_SCALE = CobolDecimal.MONETARY_SCALE;

    /** Width of {@code WS-CURR-CARD-NUM PIC X(16)} - {@code app/cbl/CBTRN03C.cbl:L137}. */
    public static final int CARD_NUMBER_KEY_LENGTH = TranRecord.TRAN_CARD_NUM_LENGTH;

    /** Width of {@code WS-START-DATE} and {@code WS-END-DATE} - {@code :L123} and {@code :L125}. */
    public static final int DATE_LENGTH = DateParmReader.START_DATE_LENGTH;

    // =================================================================================================
    // APPL-RESULT and the two 88-level condition names of app/cbl/CBTRN03C.cbl:L150-L152.
    // =================================================================================================

    /**
     * {@code MOVE 8 TO APPL-RESULT} - the value every open paragraph seeds and the very next test
     * overwrites on both arms. Dead in the COBOL, and reproduced anyway, because a statement present
     * in the source must be accounted for in the translation.
     */
    public static final int APPL_RESULT_ASSUMED_FAILURE = AbendException.RETURN_CODE_ASSUMED_FAILURE;

    /** {@code MOVE 12 TO APPL-RESULT} - the {@code WHEN OTHER} value, and the abend's return code. */
    public static final int APPL_RESULT_FATAL = AbendException.RETURN_CODE_IO_ERROR;

    /** {@code 88 APPL-AOK VALUE 0} - {@code app/cbl/CBTRN03C.cbl:L151}. */
    public static final int APPL_RESULT_AOK = FileStatus.APPL_AOK;

    /** {@code 88 APPL-EOF VALUE 16} - {@code app/cbl/CBTRN03C.cbl:L152}. */
    public static final int APPL_RESULT_EOF = FileStatus.APPL_EOF;

    /**
     * {@code MOVE 23 TO IO-STATUS} - what all three keyed lookups move before rendering the status
     * line ({@code :488}, {@code :498}, {@code :508}).
     *
     * <p>The COBOL moves the <em>number</em> 23 into a {@code PIC X(2)} group, which lands as the two
     * characters {@code '23'} - the same value {@link FileStatus#NOT_FOUND} carries - and
     * {@code 9910-DISPLAY-IO-STATUS} renders it as {@code FILE STATUS IS: NNNN0023}.
     */
    public static final String IO_STATUS_INVALID_KEY = FileStatus.NOT_FOUND;

    /**
     * The VSAM extended-status feedback code reported for a permanent error: binary zero.
     *
     * <p>z/OS COBOL reports an implementor-defined permanent error as {@code '9'} in the first status
     * byte with a binary feedback code in the second, and {@code 9910-DISPLAY-IO-STATUS} is written
     * specifically to decode that form. Zero is the generic "permanent error, no more specific code
     * available", which is the honest report for a failure this module cannot interrogate further.
     */
    private static final char PERMANENT_ERROR_FEEDBACK_CODE = 0;

    /**
     * The two-character file status attributed to a report write or close that the sink refused.
     *
     * <p>{@link TranReportWriter} reports a refusal as {@link FileStatus.Outcome#OTHER}, which by
     * definition stands for no single status, so one has to be named in order to move it into
     * {@code IO-STATUS}. This is the same extended-status form every repository in the module reports,
     * it renders as {@code FILE STATUS IS: NNNN9000} through
     * {@link FileStatus#toDisplayLine(String)}, and {@link FileStatus#outcomeOfStatus(String)}
     * classifies it as {@link FileStatus.Outcome#OTHER} - the {@code WHEN OTHER} arm, which is the
     * abend path, which is exactly where an I/O failure belongs.
     */
    public static final String PERMANENT_ERROR_STATUS = "9" + PERMANENT_ERROR_FEEDBACK_CODE;

    // =================================================================================================
    // Flag values. app/cbl/CBTRN03C.cbl:L128 and :L154 - two one-byte flags, tested by literal.
    // =================================================================================================

    /** {@code WS-FIRST-TIME} before the first detail line is written. */
    public static final String FIRST_TIME = "Y";

    /** {@code WS-FIRST-TIME} after {@code :276} has moved {@code 'N'} into it. */
    public static final String NOT_FIRST_TIME = "N";

    /** {@code END-OF-FILE} once a read has reported end of file, or {@code DATEPARM} was empty. */
    public static final String AT_END_OF_FILE = "Y";

    /** {@code END-OF-FILE} while records remain. */
    public static final String NOT_AT_END_OF_FILE = "N";

    // =================================================================================================
    // Every DISPLAY literal, byte for byte. Trailing spaces are significant and are preserved: COBOL
    // DISPLAY concatenates its operands with no separator, so the space inside 'TRAN-AMT ' is the only
    // thing between the label and the value, and 'WS-PAGE-TOTAL' having none is why that line runs
    // together.
    // =================================================================================================

    /** {@code app/cbl/CBTRN03C.cbl:L160}. */
    public static final String START_OF_EXECUTION = "START OF EXECUTION OF PROGRAM CBTRN03C";

    /** {@code app/cbl/CBTRN03C.cbl:L215}. */
    public static final String END_OF_EXECUTION = "END OF EXECUTION OF PROGRAM CBTRN03C";

    /** {@code app/cbl/CBTRN03C.cbl:L387}, {@code 0000-TRANFILE-OPEN}. */
    public static final String ERROR_OPENING_TRANFILE = "ERROR OPENING TRANFILE";

    /** {@code app/cbl/CBTRN03C.cbl:L405}, {@code 0100-REPTFILE-OPEN}. */
    public static final String ERROR_OPENING_REPTFILE = "ERROR OPENING REPTFILE";

    /** {@code app/cbl/CBTRN03C.cbl:L423}, {@code 0200-CARDXREF-OPEN}. */
    public static final String ERROR_OPENING_CROSS_REF_FILE = "ERROR OPENING CROSS REF FILE";

    /** {@code app/cbl/CBTRN03C.cbl:L441}, {@code 0300-TRANTYPE-OPEN}. */
    public static final String ERROR_OPENING_TRANSACTION_TYPE_FILE =
            "ERROR OPENING TRANSACTION TYPE FILE";

    /** {@code app/cbl/CBTRN03C.cbl:L459}, {@code 0400-TRANCATG-OPEN}. */
    public static final String ERROR_OPENING_TRANSACTION_CATG_FILE =
            "ERROR OPENING TRANSACTION CATG FILE";

    /** {@code app/cbl/CBTRN03C.cbl:L477}, {@code 0500-DATEPARM-OPEN}. */
    public static final String ERROR_OPENING_DATE_PARM_FILE = "ERROR OPENING DATE PARM FILE";

    /** {@code app/cbl/CBTRN03C.cbl:L238}, {@code 0550-DATEPARM-READ}. */
    public static final String ERROR_READING_DATEPARM_FILE = "ERROR READING DATEPARM FILE";

    /** {@code app/cbl/CBTRN03C.cbl:L266}, {@code 1000-TRANFILE-GET-NEXT}. */
    public static final String ERROR_READING_TRANSACTION_FILE = "ERROR READING TRANSACTION FILE";

    /** {@code app/cbl/CBTRN03C.cbl:L354}, {@code 1111-WRITE-REPORT-REC}. */
    public static final String ERROR_WRITING_REPTFILE = "ERROR WRITING REPTFILE";

    /**
     * {@code app/cbl/CBTRN03C.cbl:L525}, {@code 9000-TRANFILE-CLOSE}.
     *
     * <p>The wording differs from the open's, which says only {@code 'ERROR OPENING TRANFILE'}. Both
     * are transcribed exactly as written; making them consistent would change the bytes an operator
     * greps for.
     */
    public static final String ERROR_CLOSING_POSTED_TRANSACTION_FILE =
            "ERROR CLOSING POSTED TRANSACTION FILE";

    /** {@code app/cbl/CBTRN03C.cbl:L543}, {@code 9100-REPTFILE-CLOSE}. */
    public static final String ERROR_CLOSING_REPORT_FILE = "ERROR CLOSING REPORT FILE";

    /** {@code app/cbl/CBTRN03C.cbl:L562}, {@code 9200-CARDXREF-CLOSE}. */
    public static final String ERROR_CLOSING_CROSS_REF_FILE = "ERROR CLOSING CROSS REF FILE";

    /** {@code app/cbl/CBTRN03C.cbl:L580}, {@code 9300-TRANTYPE-CLOSE}. */
    public static final String ERROR_CLOSING_TRANSACTION_TYPE_FILE =
            "ERROR CLOSING TRANSACTION TYPE FILE";

    /** {@code app/cbl/CBTRN03C.cbl:L598}, {@code 9400-TRANCATG-CLOSE}. */
    public static final String ERROR_CLOSING_TRANSACTION_CATG_FILE =
            "ERROR CLOSING TRANSACTION CATG FILE";

    /** {@code app/cbl/CBTRN03C.cbl:L616}, {@code 9500-DATEPARM-CLOSE}. */
    public static final String ERROR_CLOSING_DATE_PARM_FILE = "ERROR CLOSING DATE PARM FILE";

    /**
     * {@code DISPLAY 'INVALID CARD NUMBER : '  FD-XREF-CARD-NUM} - {@code :L487}.
     *
     * <p>The literal ends in a space; the 16-byte key follows it with nothing in between. The two
     * spaces visible in the source lie <em>between</em> the two operands of the {@code DISPLAY} and
     * contribute nothing to the output.
     */
    public static final String INVALID_CARD_NUMBER = "INVALID CARD NUMBER : ";

    /** {@code DISPLAY 'INVALID TRANSACTION TYPE : '  FD-TRAN-TYPE} - {@code :L497}. */
    public static final String INVALID_TRANSACTION_TYPE = "INVALID TRANSACTION TYPE : ";

    /** {@code DISPLAY 'INVALID TRAN CATG KEY : '  FD-TRAN-CAT-KEY} - {@code :L507}. */
    public static final String INVALID_TRAN_CATG_KEY = "INVALID TRAN CATG KEY : ";

    /** The first literal of {@code DISPLAY 'Reporting from ' ... ' to ' ...} - {@code :L232}. */
    public static final String REPORTING_FROM = "Reporting from ";

    /** The second literal of that same {@code DISPLAY} - {@code :L233}. */
    public static final String REPORTING_TO = " to ";

    /**
     * {@code DISPLAY 'TRAN-AMT ' TRAN-AMT} - {@code :L198}, part of defect 2's evidence trail.
     *
     * <p>The trailing space is in the literal. What follows it is {@code TRAN-AMT}'s stored image -
     * eleven characters of zoned {@code DISPLAY} data with the sign overpunched into the last one -
     * because a COBOL {@code DISPLAY} of a numeric {@code DISPLAY} item emits its bytes rather than a
     * formatted number.
     */
    public static final String TRAN_AMT_DISPLAY_LABEL = "TRAN-AMT ";

    /**
     * {@code DISPLAY 'WS-PAGE-TOTAL'  WS-PAGE-TOTAL} - {@code :L199}.
     *
     * <p><strong>No trailing space</strong>, unlike the line above it, so the label and the eleven
     * digits run together in {@code SYSOUT}. That is what the program emits.
     */
    public static final String WS_PAGE_TOTAL_DISPLAY_LABEL = "WS-PAGE-TOTAL";

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

    /**
     * What separates two {@code SYSOUT} lines. A {@code DISPLAY} produces one line; the terminator is
     * named here rather than left to a platform line-separator property, so the captured output of a
     * run does not depend on the machine that produced it.
     */
    private static final char SYSOUT_LINE_TERMINATOR = '\n';

    /** Named in a codec diagnostic when a {@code SYSOUT} line cannot be encoded. */
    private static final String SYSOUT_SUBJECT = "a SYSOUT display line of " + PROGRAM_NAME;


    // =================================================================================================
    // Collaborators. All final, all constructor-injected, none of them holding per-run state
    // (practice B9, gate G53). The per-run state lives on ReportRun.
    // =================================================================================================

    /** The batch seam: job and step builders, the job contract, and DD-name resolution. */
    private final BatchConfig batchConfig;

    /** {@value #TRANFILE_DD_NAME} - the sorted daily file, browsed front to back. */
    private final TransactionRepository transactionRepository;

    /** {@value #CARDXREF_DD_NAME} - keyed by card number, one read per account break. */
    private final CardXrefRepository cardXrefRepository;

    /** {@value #TRANTYPE_DD_NAME} - keyed by the 2-byte type code, one read per detail line. */
    private final TranTypeRepository tranTypeRepository;

    /** {@value #TRANCATG_DD_NAME} - keyed by the 6-byte type-plus-category key, one read per line. */
    private final TranCategoryRepository tranCategoryRepository;

    /** {@value #DATEPARM_DD_NAME} - read exactly once, before the loop. */
    private final DateParmReader dateParmReader;

    /** {@value #TRANREPT_DD_NAME} - the 133-byte output, and the owner of every width decision. */
    private final TranReportWriter tranReportWriter;

    /**
     * The boundary {@value #TRANREPT_DD_NAME}'s abnormal disposition is applied in.
     *
     * <p>The third positional of {@code DISP=(NEW,CATLG,DELETE)} ({@code app/jcl/TRANREPT.jcl:76-80}) is
     * this job's to apply, and it has to be applied <em>outside</em> whatever transaction the step was
     * inside. A disposition is the initiator's work: z/OS applies it after the step has ended, so
     * enrolling the delete in the step's transaction would let the failure that triggered the disposition
     * undo the disposition - leaving exactly the partial report it exists to remove.
     */
    private final DatasetUnitOfWork unitOfWork;

    /**
     * The data path the two utility steps use: {@value #BACKUP_STEP_NAME}'s {@code IDCAMS REPRO} and
     * {@value #SORT_STEP_NAME}'s {@code DFSORT}.
     *
     * <p>Neither step runs a migrated COBOL program - they run mainframe utilities over whole record
     * images - so neither belongs to a repository, whose methods reproduce a program's {@code READ},
     * {@code WRITE} and {@code REWRITE} verbs. The same contract already serves the statement job's
     * three utility steps ({@code app/jcl/CREASTMT.JCL}), so it is reused rather than restated: one
     * dataset-utility path for the module means one place where a deployment overrides it and one place
     * where the record-image representation is decided.
     */
    private final DatasetUtilityPort datasetUtilityPort;

    /**
     * The codec over the module's active dataset code page.
     *
     * <p>Used for three things and nothing else: rendering a {@code SYSOUT} line's bytes, moving a card
     * number into the {@value #CARD_NUMBER_KEY_LENGTH}-byte {@code FD-XREF-CARD-NUM} receiver, and
     * rendering a running total as the zoned {@code DISPLAY} image the two defect-trail lines emit.
     */
    private final FixedWidthCodec codec;

    /**
     * Provider for a deployment-supplied {@code SYSOUT} destination.
     *
     * <p>{@code app/jcl/TRANREPT.jcl:L62-L63} declares {@code //SYSOUT DD SYSOUT=*} and
     * {@code //SYSPRINT DD SYSOUT=*}; neither is a dataset this module binds, so where the lines go is
     * a deployment-time input and arrives as an injected sink.
     */
    private final ObjectProvider<SysoutSink> sysoutSinkProvider;

    /** The configured step name, read from the contract so the metadata matches the JCL. */
    private final String stepName;

    /**
     * The configured name of the unload step, read from the contract for the same reason as
     * {@link #stepName}: the batch metadata an operator reads must carry the JCL's own step names.
     */
    private final String backupStepName;

    /** The configured name of the filter-and-sort step, read from the contract. */
    private final String sortStepName;

    /**
     * The configured {@value #TRANFILE_DD_NAME} dataset name, kept only so an operator or a test can
     * read back which dataset this job will report from.
     */
    private final String tranFileDatasetName;

    /**
     * Wires the job and validates, at startup, that the configured contract still says what
     * {@code app/jcl/TRANREPT.jcl} and {@code app/proc/TRANREPT.prc} say.
     *
     * <p>The guards are not ceremony. Each rejects a configuration that would make this job report from
     * the wrong dataset, run the wrong step, accept an input the COBOL cannot read, or take its date
     * range from somewhere the COBOL never looks - and each fails now, when an operator can act on the
     * message, rather than in the middle of a batch window.
     *
     * @param batchConfig            the batch seam supplying job and step builders, the job contract and
     *                               DD-name resolution; never {@code null}
     * @param transactionRepository  the transaction repository, opened over this job's own
     *                               {@value #TRANFILE_DD_NAME} binding; never {@code null}
     * @param cardXrefRepository     the card cross-reference repository, opened for input and read
     *                               randomly by card number; never {@code null}
     * @param tranTypeRepository     the transaction type repository; never {@code null}
     * @param tranCategoryRepository the transaction category repository; never {@code null}
     * @param dateParmReader         the reporting date range's reader - the reason this job needs no job
     *                               parameter; never {@code null}
     * @param tranReportWriter       the 133-byte report writer; never {@code null}
     * @param datasetCharset         the module's active dataset code page, injected by the bean name
     *                               {@value #DATASET_CHARSET_BEAN_NAME} so it is stated explicitly
     *                               rather than taken from the platform; never {@code null}
     * @param sysoutSinkProvider     provider for a {@code SYSOUT} destination; never {@code null},
     *                               though it may resolve to nothing, in which case
     *                               {@link #defaultSysoutSink()} is used
     * @param jdbcTemplate           the module's single {@link JdbcTemplate}, used only to build the
     *                               default {@linkplain DatasetUtilityPort utility port} when the
     *                               deployment supplies none; never {@code null}
     * @param physicalSequence       the deployment's physical-record ordinal, from
     *                               {@value PhysicalSequence#EXPRESSION_PROPERTY} - carried into the
     *                               default utility port so the backup unload and the sort move records
     *                               in the order they were written; never {@code null}
     * @param recordImageForm        how this deployment's driver presents a record image, from
     *                               {@value RecordImageForm#FORM_PROPERTY} - carried into that default
     *                               port so the two preparatory steps read and write a record image
     *                               exactly as every repository and writer does; never {@code null}
     * @param datasetUtilityPortProvider provider for the dataset-utility path the two preparatory steps
     *                               use; never {@code null}, though it may resolve to nothing, in which
     *                               case the JDBC implementation the statement job also defaults to is
     *                               constructed here
     * @throws NullPointerException  if any argument is {@code null}
     * @throws IllegalStateException if the contract names a different program, declares any job
     *                               parameter, sources its date range from anything but
     *                               {@value #DATE_RANGE_SOURCE}, omits or reorders the three JCL steps,
     *                               gates any of them, binds {@value #TRANFILE_DD_NAME} to a dataset
     *                               whose record length is not the {@value TranRecord#RECORD_LENGTH}
     *                               bytes {@code app/cpy/CVTRA05Y.cpy} declares, binds it to no usable
     *                               dataset name, or binds {@value #TRANREPT_DD_NAME} to a width other
     *                               than {@value TranReportWriter#RECORD_LENGTH}
     */
    public TransactionReportJob(
            BatchConfig batchConfig,
            TransactionRepository transactionRepository,
            CardXrefRepository cardXrefRepository,
            TranTypeRepository tranTypeRepository,
            TranCategoryRepository tranCategoryRepository,
            DateParmReader dateParmReader,
            TranReportWriter tranReportWriter,
            @Qualifier(DATASET_CHARSET_BEAN_NAME) Charset datasetCharset,
            ObjectProvider<SysoutSink> sysoutSinkProvider,
            JdbcTemplate jdbcTemplate,
            RecordImageForm recordImageForm,
            PhysicalSequence physicalSequence,
            DatasetUnitOfWork unitOfWork,
            ObjectProvider<DatasetUtilityPort> datasetUtilityPortProvider) {

        this.batchConfig = Objects.requireNonNull(batchConfig, "The batch configuration seam is "
                + "required: it supplies the job repository, the transaction manager and the abend "
                + "listener, so no job class assembles Spring Batch plumbing of its own");
        this.transactionRepository = Objects.requireNonNull(transactionRepository, "The transaction "
                + "repository is required: app/cbl/CBTRN03C.cbl:29 assigns the report's input to DD "
                + TRANFILE_DD_NAME + ", which app/jcl/TRANREPT.jcl:65-66 binds to the sorted daily "
                + "file");
        this.cardXrefRepository = Objects.requireNonNull(cardXrefRepository, "The card cross-reference "
                + "repository is required: 1500-A-LOOKUP-XREF reads it once per account break to "
                + "resolve XREF-ACCT-ID for the detail line");
        this.tranTypeRepository = Objects.requireNonNull(tranTypeRepository, "The transaction type "
                + "repository is required: 1500-B-LOOKUP-TRANTYPE reads it for every detail line's "
                + "TRAN-TYPE-DESC");
        this.tranCategoryRepository = Objects.requireNonNull(tranCategoryRepository, "The transaction "
                + "category repository is required: 1500-C-LOOKUP-TRANCATG reads it for every detail "
                + "line's TRAN-CAT-TYPE-DESC");
        this.dateParmReader = Objects.requireNonNull(dateParmReader, "The " + DATEPARM_DD_NAME
                + " reader is required: 0550-DATEPARM-READ takes the reporting range from a dataset, "
                + "not from a PARM, so there is no job parameter to fall back on");
        this.tranReportWriter = Objects.requireNonNull(tranReportWriter, "The report writer is "
                + "required: it owns the " + TranReportWriter.RECORD_LENGTH + "-byte record and every "
                + "width decision this job must not make for itself");
        this.codec = new FixedWidthCodec(Objects.requireNonNull(datasetCharset, "A dataset charset is "
                + "required: this job renders SYSOUT lines, a 16-byte key and two zoned DISPLAY images, "
                + "so the code page is stated explicitly and never taken from the platform"));
        this.sysoutSinkProvider = Objects.requireNonNull(sysoutSinkProvider, "A SysoutSink provider is "
                + "required: DISPLAY output is emitted through an injected sink so it can be captured "
                + "and compared, never written straight to a stream from the program body");
        Objects.requireNonNull(jdbcTemplate, "A JdbcTemplate is required to build the default dataset "
                + "utility path for the " + BACKUP_STEP_NAME + " unload and the " + SORT_STEP_NAME
                + " sort; the data-source configuration declares the single instance this module shares");
        Objects.requireNonNull(recordImageForm, "A record-image representation is required: whether "
                + "this deployment's driver presents a record image as characters or as bytes is stated "
                + "once, by " + RecordImageForm.FORM_PROPERTY + ", and never decided per component");
        Objects.requireNonNull(physicalSequence, "A physical-record ordinal is required: the backup "
                + "unload and the sort move a physical-sequential dataset record for record and in "
                + "order, and SQL returns rows in no order unless a statement says which. It is stated "
                + "once, by " + PhysicalSequence.EXPRESSION_PROPERTY + ", and never decided per "
                + "component");
        this.unitOfWork = Objects.requireNonNull(unitOfWork, "A unit of work is required: "
                + "app/jcl/TRANREPT.jcl:76-80 declares " + TRANREPT_DD_NAME
                + " with DISP=(NEW,CATLG,DELETE), so a run that does not complete normally must leave no "
                + "report at all - and that deletion has to be persisted in a boundary of its own, or the "
                + "very failure that triggered it would undo it");
        Objects.requireNonNull(datasetUtilityPortProvider, "A dataset-utility port provider is "
                + "required: the two preparatory steps run mainframe utilities over whole record images, "
                + "and which data path they use is a deployment-time input rather than a decision this "
                + "job makes");
        this.datasetUtilityPort = datasetUtilityPortProvider.getIfAvailable(
                () -> new JdbcDatasetUtilityPort(jdbcTemplate, datasetCharset, recordImageForm,
                        physicalSequence));

        JobContract contract = batchConfig.contract(JOB_KEY);
        requireProgram(contract.program(), "carddemo.jobs." + JOB_KEY + ".program");
        requireNoJobParameters(contract);
        requireDateRangeSource(contract);
        requireStepSequence(contract);
        // requireStepSequence compares names and refuses a gate; this compares the whole tuple, so each
        // step's EXEC PGM= is pinned as well. Both are kept because the first two produce diagnostics
        // that name the specific mistake, and this one closes the case neither can see.
        batchConfig.requireSteps(JOB_KEY, REQUIRED_STEPS, "app/proc/TRANREPT.prc");

        StepContract step = contract.step(STEP_NAME);
        requireProgram(step.program(), "carddemo.jobs." + JOB_KEY + ".steps[" + STEP_NAME
                + "].program");
        this.stepName = step.name();
        this.backupStepName = contract.step(BACKUP_STEP_NAME).name();
        this.sortStepName = contract.step(SORT_STEP_NAME).name();

        // The DD name is a configuration key; what it resolves to is configuration's business. Only the
        // two facts this job needs are read - which dataset, and how wide its records are - and neither
        // the binding nor any statement built from it is retained. The type is inferred rather than
        // named so this file's imports remain exactly its declared dependency set.
        var tranFile = batchConfig.datasetBinding(JOB_KEY, TRANFILE_DD_NAME);
        requireTranFileRecordWidth(tranFile.recordLength());
        this.tranFileDatasetName = requireUsableDatasetName(TRANFILE_DD_NAME, tranFile.dsname());

        requireUtilityStepBindings();
        requireReportRecordWidth(tranReportWriter.recordLength());

        // This program's ASSIGN clauses name CARDXREF, TRANTYPE and TRANCATG; the repositories that
        // serve them are bound to their own keys - CCXREF for the cross-reference cluster, because the
        // online programs address it that way. Each key carries an independent override in
        // application.yml, so a deployment can point a job's DD and its repository's DD at different
        // datasets. This job's report groups and subtotals by account as records arrive, so reading the
        // wrong cross-reference would produce a report with plausible rows and wrong totals - the worst
        // kind of wrong, and invisible without this check. Proven equal here, once, at startup.
        batchConfig.requireSameDataset(JOB_KEY, CARDXREF_DD_NAME, CardXrefRepository.BASE_DD_NAME);
        batchConfig.requireSameDataset(JOB_KEY, TRANTYPE_DD_NAME, TranTypeRepository.DD_NAME);
        batchConfig.requireSameDataset(JOB_KEY, TRANCATG_DD_NAME, TranCategoryRepository.DD_NAME);
    }

    /**
     * Requires the four DDs of the two preparatory steps to be bound, addressable, and to declare the
     * geometry the JCL declares.
     *
     * <p>Checked at startup rather than discovered in a batch window, and checked for all four rather
     * than for the two that are written: {@value #BACKUP_INPUT_DD_NAME} and
     * {@value #SORT_INPUT_DD_NAME} are read whole-record, so a binding of the wrong width would copy
     * and filter records whose {@code TRAN-PROC-DT} and {@code TRAN-CARD-NUM} spans are not where the
     * SORT symbol table says they are - and would produce a plausible report from the wrong bytes.
     *
     * <p>{@value #SORT_OUTPUT_DD_NAME} takes {@code DCB=(*.SORTIN)}, so its geometry <em>is</em>
     * {@value #SORT_INPUT_DD_NAME}'s by definition; that identity is asserted rather than assumed,
     * because a profile that overrode one and not the other would satisfy every individual check while
     * breaking the reference the JCL expresses.
     *
     * @throws IllegalStateException if any of the four is unbound, blank, of the wrong width, or of a
     *                               record format other than {@value #UTILITY_RECORD_FORMAT}, or if the
     *                               two sort bindings disagree
     */
    private void requireUtilityStepBindings() {
        for (String ddName : List.of(BACKUP_INPUT_DD_NAME, BACKUP_OUTPUT_DD_NAME,
                SORT_INPUT_DD_NAME, SORT_OUTPUT_DD_NAME)) {
            var binding = batchConfig.datasetBinding(JOB_KEY, ddName);
            requireUsableDatasetName(ddName, binding.dsname());
            if (binding.recordLength() != TranRecord.RECORD_LENGTH) {
                throw new IllegalStateException("The " + ddName + " binding of " + JOB_KEY
                        + " declares record-length " + binding.recordLength() + ", but "
                        + "app/jcl/TRANREPT.jcl:31 declares DCB=(LRECL=" + TranRecord.RECORD_LENGTH
                        + ",RECFM=" + UTILITY_RECORD_FORMAT + ",BLKSIZE=0) for the unload and its SORT "
                        + "reads TRAN-CARD-NUM at one-based " + SORT_TRAN_CARD_NUM_POSITION
                        + " and TRAN-PROC-DT at one-based " + SORT_TRAN_PROC_DT_POSITION
                        + " out of that record. app/cpy/CVTRA05Y.cpy declares (RECLN "
                        + TranRecord.RECORD_LENGTH + ").");
            }
            if (!UTILITY_RECORD_FORMAT.equalsIgnoreCase(binding.recordFormat())) {
                throw new IllegalStateException("The " + ddName + " binding of " + JOB_KEY
                        + " declares record-format "
                        + (binding.recordFormat() == null
                                ? "absent" : "'" + binding.recordFormat() + "'")
                        + ", but app/jcl/TRANREPT.jcl:31 declares RECFM=" + UTILITY_RECORD_FORMAT
                        + " and its L53 DCB=(*.SORTIN) carries the same format onto the sorted file. "
                        + "Fixed blocked is what makes every record exactly "
                        + TranRecord.RECORD_LENGTH + " bytes, so it is required rather than assumed.");
            }
        }
        var sortIn = batchConfig.datasetBinding(JOB_KEY, SORT_INPUT_DD_NAME);
        var sortOut = batchConfig.datasetBinding(JOB_KEY, SORT_OUTPUT_DD_NAME);
        if (sortIn.recordLength() != sortOut.recordLength()
                || !String.valueOf(sortIn.recordFormat())
                        .equalsIgnoreCase(String.valueOf(sortOut.recordFormat()))) {
            throw new IllegalStateException("The " + SORT_OUTPUT_DD_NAME + " binding of " + JOB_KEY
                    + " declares record-length " + sortOut.recordLength() + " and record-format '"
                    + sortOut.recordFormat() + "', but app/jcl/TRANREPT.jcl:53 declares "
                    + "DCB=(*.SORTIN) - the geometry of " + SORT_INPUT_DD_NAME + ", which is "
                    + sortIn.recordLength() + " and '" + sortIn.recordFormat() + "'. The sorted file is "
                    + "the same records in a different order, so it is the same shape.");
        }
    }

    // =================================================================================================
    // Constructor guards. Each is a static function of its arguments, so each is reachable from a plain
    // unit test with a hand-built contract, and each message names the configuration key to correct.
    // =================================================================================================

    /**
     * Requires a configured program name to be the program this class translates.
     *
     * <p>A contract naming another program would run this job's step body against another program's DD
     * bindings, which is the kind of mismatch that produces a plausible report from the wrong dataset.
     *
     * @param configured the program name configuration declares
     * @param key        the configuration key it was read from, for the diagnostic
     * @throws IllegalStateException if it is not {@value #PROGRAM_NAME}
     */
    private static void requireProgram(String configured, String key) {
        if (!PROGRAM_NAME.equals(configured)) {
            throw new IllegalStateException(key + " is '" + configured + "', but "
                    + TransactionReportJob.class.getSimpleName() + " translates " + PROGRAM_NAME
                    + " (app/cbl/CBTRN03C.cbl), which app/jcl/TRANREPT.jcl:59 runs as its " + STEP_NAME
                    + " step. Correct the configured program name; do not repoint this class.");
        }
    }

    /**
     * Requires the contract to declare no job parameter at all.
     *
     * <p>{@code app/jcl/TRANREPT.jcl:L59} is a bare {@code EXEC PGM=CBTRN03C} with no {@code PARM}, and
     * the program declares no {@code LINKAGE SECTION}. The absence <em>is</em> the contract: the one
     * value this job needs from outside is the reporting date range, and {@code 0550-DATEPARM-READ}
     * reads that from a dataset. A declared parameter would be an input the program has no field to
     * receive and no statement to read.
     *
     * @param contract the configured contract
     * @throws IllegalStateException if any parameter is declared
     */
    private static void requireNoJobParameters(JobContract contract) {
        if (!contract.parameters().isEmpty()) {
            throw new IllegalStateException("carddemo.jobs." + JOB_KEY + ".parameters declares "
                    + contract.parameters().size() + " parameter(s), but app/jcl/TRANREPT.jcl:59 runs "
                    + PROGRAM_NAME + " with no PARM and the program declares no LINKAGE SECTION. Its "
                    + "reporting date range comes from DD " + DATEPARM_DD_NAME + " at "
                    + "app/cbl/CBTRN03C.cbl:221, which is what date-range-source records. The empty "
                    + "list is the contract; declare no parameter here.");
        }
    }

    /**
     * Requires the contract to source its date range from {@value #DATE_RANGE_SOURCE}.
     *
     * <p>This is the positive half of {@link #requireNoJobParameters(JobContract)}. Together they pin
     * the single most plausible mis-migration of this program: turning a dataset the COBOL reads with a
     * {@code READ} statement into a job parameter, which would leave {@code 0550-DATEPARM-READ} with
     * nothing to read and would silently drop its end-of-file arm - the arm that produces an empty
     * report.
     *
     * @param contract the configured contract
     * @throws IllegalStateException if the declared source is absent or is anything else
     */
    private static void requireDateRangeSource(JobContract contract) {
        if (!DATE_RANGE_SOURCE.equals(contract.dateRangeSource())) {
            throw new IllegalStateException("carddemo.jobs." + JOB_KEY + ".date-range-source is "
                    + (contract.dateRangeSource() == null
                            ? "not declared" : "'" + contract.dateRangeSource() + "'")
                    + ", but app/cbl/CBTRN03C.cbl:221 reads the reporting range from DD "
                    + DATE_RANGE_SOURCE + " - a dataset, not a PARM. Declare date-range-source: "
                    + DATE_RANGE_SOURCE + ".");
        }
    }

    /**
     * Requires the contract to record all three JCL steps, in order, all ungated.
     *
     * <p>The first two are the unload and the SORT, and this job runs both - so the contract is not
     * documentation here, it is the source of the two step names the batch metadata records and the
     * proof that the sequence has not been reordered. A contract that had lost them would leave
     * {@code CBTRN03C} reporting straight from whatever its {@code TRANFILE} binding last held.
     *
     * <p>All three must be ungated. {@code COND=(0,NE)} appears on three steps of
     * {@code app/jcl/CREASTMT.JCL} and nowhere else in this estate; the {@code COND=} in
     * {@code app/jcl/TRANREPT.jcl:L47} is the SORT's {@code INCLUDE} filter, not step gating. Reading it
     * as gating would bypass the report whenever a preceding step returned non-zero, which the
     * mainframe does not do.
     *
     * @param contract the configured contract
     * @throws IllegalStateException if the sequence is not exactly
     *                               {@value #BACKUP_STEP_NAME}, {@value #SORT_STEP_NAME},
     *                               {@value #STEP_NAME}, or if any step is gated
     */
    private static void requireStepSequence(JobContract contract) {
        List<String> expected = List.of(BACKUP_STEP_NAME, SORT_STEP_NAME, STEP_NAME);
        List<String> declared = contract.steps().stream().map(StepContract::name).toList();
        if (!expected.equals(declared)) {
            throw new IllegalStateException("carddemo.jobs." + JOB_KEY + ".steps declares " + declared
                    + ", but app/proc/TRANREPT.prc names " + expected + " in that order - the REPROC "
                    + "unload, the DFSORT filter-and-sort, and " + PROGRAM_NAME + " itself. "
                    + "app/jcl/TRANREPT.jcl spells the first two identically, which is a defect in that "
                    + "JCL; the .prc names are the unambiguous ones. Restore the sequence.");
        }
        for (StepContract step : contract.steps()) {
            if (step.requirePrecedingExitCodeZero()) {
                throw new IllegalStateException("carddemo.jobs." + JOB_KEY + ".steps[" + step.name()
                        + "].require-preceding-exit-code-zero is true, but no step of "
                        + "app/jcl/TRANREPT.jcl carries a COND. The COND= at its L47 is the SORT's "
                        + "INCLUDE filter on TRAN-PROC-DT, not step gating, and gating this job's "
                        + "steps would skip a report the mainframe produces.");
            }
        }
    }

    /**
     * Requires the {@value #TRANFILE_DD_NAME} binding to agree with {@code app/cpy/CVTRA05Y.cpy}.
     *
     * <p>Every column of the report is positioned from a field of a 350-byte record, so a binding of
     * any other width would not merely shift one value - it would decode {@code TRAN-CARD-NUM} from
     * wherever the narrower record happened to end and break the account break, the type lookup and the
     * category lookup together.
     *
     * <p>The organization is deliberately <em>not</em> constrained. {@code CBTRN03C} declares
     * {@code ORGANIZATION IS SEQUENTIAL} and reads front to back, and
     * {@link TransactionRepository#openInput} advances a browse of a keyed binding the same way, so a
     * site that presents the sorted daily file as an indexed dataset still gets the COBOL's read order.
     * What must not vary is the width.
     *
     * @param configuredRecordLength the width configuration declares for the DD
     * @throws IllegalStateException if it is not {@value TranRecord#RECORD_LENGTH}
     */
    private static void requireTranFileRecordWidth(int configuredRecordLength) {
        if (configuredRecordLength != TranRecord.RECORD_LENGTH) {
            throw new IllegalStateException("The " + TRANFILE_DD_NAME + " binding of "
                    + JOB_KEY + " declares record-length " + configuredRecordLength
                    + ", but app/cpy/CVTRA05Y.cpy declares (RECLN " + TranRecord.RECORD_LENGTH
                    + ") and app/jcl/TRANREPT.jcl:31 unloads the transaction file at LRECL=350. "
                    + PROGRAM_NAME + " positions every report column from a field of that record, and "
                    + "TRAN-CARD-NUM alone sits at one-based " + SORT_TRAN_CARD_NUM_POSITION + ".");
        }
    }

    /**
     * Requires the report writer to be writing {@value TranReportWriter#RECORD_LENGTH}-byte records.
     *
     * <p>{@code app/jcl/TRANREPT.jcl:L78} declares {@code DCB=(LRECL=133,RECFM=FB,BLKSIZE=0)} and
     * {@code app/cpy/CVTRA07Y.cpy:L48} declares {@code TRANSACTION-HEADER-2 PIC X(133) VALUE ALL '-'},
     * so 133 is fixed by two independent artefacts (gate G20). Checked here rather than assumed,
     * because this job decides <em>which</em> line is written and the writer decides how wide it is;
     * neither can verify the other's half alone.
     *
     * @param configuredRecordLength the width the writer reports
     * @throws IllegalStateException if it is not {@value TranReportWriter#RECORD_LENGTH}
     */
    private static void requireReportRecordWidth(int configuredRecordLength) {
        if (configuredRecordLength != TranReportWriter.RECORD_LENGTH) {
            throw new IllegalStateException("The " + TRANREPT_DD_NAME + " writer reports a record "
                    + "length of " + configuredRecordLength + ", but app/jcl/TRANREPT.jcl:78 declares "
                    + "LRECL=" + TranReportWriter.RECORD_LENGTH + " and app/cpy/CVTRA07Y.cpy:48 "
                    + "declares TRANSACTION-HEADER-2 as PIC X(" + TranReportWriter.RECORD_LENGTH
                    + "). Every line of this report is that wide.");
        }
    }

    /**
     * Requires a DD to bind a dataset name that can actually be addressed.
     *
     * <p>A blank name is the one failure mode a configuration file produces silently: an unset
     * environment-variable reference resolves to the empty string, and every read afterwards addresses
     * nothing.
     *
     * @param ddName the DD name, for the diagnostic
     * @param dsname the configured dataset name
     * @return that name, unchanged
     * @throws IllegalStateException if it is {@code null} or blank
     */
    private static String requireUsableDatasetName(String ddName, String dsname) {
        if (dsname == null || dsname.isBlank()) {
            throw new IllegalStateException("The " + ddName + " binding of " + JOB_KEY + " has a "
                    + (dsname == null ? "missing" : "blank") + " dsname, so DD " + ddName
                    + " names no dataset for " + PROGRAM_NAME + " to read. app/jcl/TRANREPT.jcl binds "
                    + "it to the sorted daily transaction file; supply that dataset, or the "
                    + "environment variable the binding defers to.");
        }
        return dsname;
    }


    // =================================================================================================
    // The Spring Batch assembly: one job, three steps in the JCL's order. app/jcl/TRANREPT.jcl has no
    // step-level COND, so every transition is unconditional and there is no decider to place.
    // =================================================================================================

    /**
     * The job {@code app/jcl/TRANREPT.jcl} is: three steps in order, no parameters, no gating.
     *
     * <p>Built through {@link BatchConfig#job(String)}, so it carries the shared job repository and the
     * abend listener that turns an {@link AbendException}'s {@code RETURN-CODE} into the job's exit
     * status - which is what keeps a JCL-equivalent {@code COND} test on a downstream job meaning what
     * it meant on the mainframe (gate G35).
     *
     * <p>No {@link org.springframework.batch.core.JobParametersValidator} is installed and that absence
     * is deliberate twice over: the JCL step carries no {@code PARM}, so this job declares no
     * parameter; and a validator asserting that the parameters are empty would forbid the identifying
     * parameter a re-run of the same job instance needs.
     *
     * <p><strong>All three steps, in the JCL's order, with unconditional transitions.</strong>
     * {@value #BACKUP_STEP_NAME} unloads the transaction master onto the backup generation,
     * {@value #SORT_STEP_NAME} filters that unload to the {@code INCLUDE} date range and orders it by
     * card number, and {@value #STEP_NAME} reports from the result. The order is the whole point: this
     * job's {@value #TRANFILE_DD_NAME} binding names the <em>sorted daily file</em>, so running the
     * program without its two predecessors would report from whatever the previous run left there.
     *
     * <p>No {@code COND} anywhere, so no decider and no skip transition. The {@code COND=} at
     * {@code app/jcl/TRANREPT.jcl:L47} is the SORT's {@code INCLUDE} filter on {@code TRAN-PROC-DT}, not
     * step gating - reading it as gating would skip a report the mainframe produces. Gating is checked
     * against the contract at startup by {@link #requireStepSequence(JobContract)}, so a profile cannot
     * introduce it either.
     *
     * @return the job, named {@value #JOB_NAME} in the batch metadata
     */
    @Bean(JOB_NAME)
    public Job transactionReportJob() {
        return batchConfig.job(JOB_NAME)
                .start(transactionReportBackupStep())
                .next(transactionReportSortStep())
                .next(transactionReportStep())
                .build();
    }

    /**
     * {@value #BACKUP_STEP_NAME} - {@code EXEC PROC=REPROC} ({@code app/jcl/TRANREPT.jcl:L23},
     * {@code app/proc/TRANREPT.prc:L21}), an {@code IDCAMS REPRO} and nothing more.
     *
     * <p>A tasklet, because {@code REPRO} is one whole-dataset operation with no per-record decision to
     * chunk.
     *
     * @return the unload step; never {@code null}
     */
    @Bean(BACKUP_STEP_BEAN_NAME)
    public Step transactionReportBackupStep() {
        return batchConfig.taskletStep(backupStepName, transactionReportBackupTasklet()).build();
    }

    /**
     * {@value #SORT_STEP_NAME} - {@code EXEC PGM=SORT} ({@code app/jcl/TRANREPT.jcl:L37},
     * {@code app/proc/TRANREPT.prc:L35}).
     *
     * <p>A tasklet for a stronger reason than the unload's: a sort is not a record-at-a-time operation
     * at all. Every record must be in hand before the first one can be written, so a chunk-oriented
     * step could not express it without writing records in an order the sort has not decided yet.
     *
     * @return the filter-and-sort step; never {@code null}
     */
    @Bean(SORT_STEP_BEAN_NAME)
    public Step transactionReportSortStep() {
        return batchConfig.taskletStep(sortStepName, transactionReportSortTasklet()).build();
    }

    /**
     * {@value #BACKUP_STEP_NAME}'s body: one call, one complete unload.
     *
     * <p>Not a bean, for the same reason {@link #transactionReportTasklet()} is not: the step is the
     * bean, and a separately published tasklet would be a second handle on the same body with no
     * caller.
     *
     * @return a tasklet that unloads the master exactly once per step execution
     */
    public Tasklet transactionReportBackupTasklet() {
        return (contribution, chunkContext) ->
                reportRecordsWritten(contribution,
                        unloadTransactionMaster(StopSignal.of(chunkContext)));
    }

    /**
     * {@value #SORT_STEP_NAME}'s body: one call, one complete filter and sort.
     *
     * @return a tasklet that filters and sorts exactly once per step execution
     */
    public Tasklet transactionReportSortTasklet() {
        return (contribution, chunkContext) ->
                reportRecordsWritten(contribution,
                        filterAndSortUnloadedTransactions(StopSignal.of(chunkContext)));
    }

    /**
     * Publishes what a utility step wrote onto the step metadata.
     *
     * <p>A write count is the one number these two steps genuinely produce - {@code REPRO} and DFSORT
     * both report records out - so it is contributed rather than invented. The migrated program's own
     * step contributes nothing, because its only counter counts report lines rather than records.
     *
     * @param contribution  the framework's contribution for this step execution
     * @param recordsWritten how many records the step wrote
     * @return {@link RepeatStatus#FINISHED}, because a utility step runs once
     */
    private static RepeatStatus reportRecordsWritten(StepContribution contribution,
            int recordsWritten) {
        contribution.incrementWriteCount(recordsWritten);
        return RepeatStatus.FINISHED;
    }

    /**
     * {@value #BACKUP_STEP_NAME} - {@code REPRO INFILE(FILEIN) OUTFILE(FILEOUT)}
     * ({@code app/ctl/REPROCT.ctl:L15}).
     *
     * <p>Copies every record of the transaction master onto the backup generation, record for record and
     * unchanged. {@code REPRO} transforms nothing, so neither does this: no field is decoded, no width is
     * adjusted, no record is dropped.
     *
     * <p><strong>The destination is emptied first</strong>, because
     * {@code app/jcl/TRANREPT.jcl:L29} declares {@code DISP=(NEW,CATLG,DELETE)} over
     * {@code ...TRANSACT.BKUP(+1)}: each run unloads into a new generation, so last run's unload is not
     * part of this one. Content only - no data-definition statement is issued anywhere in this module
     * (gate G44).
     *
     * <p><strong>The order is the master's key order.</strong> {@code REPRO} of a KSDS delivers records
     * in ascending key sequence, and the master's key is {@code TRAN-ID} at offset
     * {@value TranRecord#TRAN_ID_OFFSET} for {@value TranRecord#TRAN_ID_KEY_LENGTH} bytes. Stated
     * explicitly and applied here rather than left to whatever order the backend returns rows in,
     * because an unordered unload would make the sort step's treatment of equal card numbers depend on
     * the backend. A binding that declares no key is a physical-sequential dataset, whose records
     * {@code REPRO} copies in the order they were written; that order is then whatever the read returns,
     * and imposing one would reorder the file.
     *
     * @return how many records were unloaded
     * @throws IllegalStateException if either dataset cannot be addressed
     */
    public int unloadTransactionMaster() {
        return unloadTransactionMaster(StopSignal.RUNNING);
    }

    /**
     * {@value #BACKUP_STEP_NAME}, yielding to a stop request between written records.
     *
     * <p>Identical to {@link #unloadTransactionMaster()} in what it reads, how it orders it and what it
     * writes. The read and the clear are one statement each, bounded by the configured statement
     * timeout; the unload is a record at a time, which is where a stop can be honoured. Nothing is
     * retried, so a stopped unload leaves the records it had already written and reports that count -
     * and the step reports as stopped, so nothing downstream reads a partial backup as a complete one.
     *
     * @param stopSignal the between-record cancellation probe; never {@code null}
     * @return how many records were unloaded
     * @throws IllegalStateException if either dataset cannot be addressed
     * @throws BatchConfig.StopRequestedException if the step is asked to stop
     */
    public int unloadTransactionMaster(StopSignal stopSignal) {
        DatasetBinding source = batchConfig.datasetBinding(JOB_KEY, BACKUP_INPUT_DD_NAME);
        DatasetBinding destination = batchConfig.datasetBinding(JOB_KEY, BACKUP_OUTPUT_DD_NAME);
        List<String> unloaded = new ArrayList<>(datasetUtilityPort.readAllRecordImages(source));
        if (source.keyLength() != null) {
            unloaded.sort(MASTER_KEY_ORDER);
        }
        datasetUtilityPort.deleteAllRecords(destination);
        return datasetUtilityPort.writeRecordImages(destination, unloaded, stopSignal);
    }

    /**
     * {@value #SORT_STEP_NAME} - {@code SORT FIELDS=(TRAN-CARD-NUM,A)} with
     * {@code INCLUDE COND=(TRAN-PROC-DT,GE,PARM-START-DATE,AND,TRAN-PROC-DT,LE,PARM-END-DATE)}
     * ({@code app/jcl/TRANREPT.jcl:L46-L48}, {@code app/proc/TRANREPT.prc:L44-L46}).
     *
     * <p>Reads {@value #SORT_INPUT_DD_NAME}, keeps the records whose {@code TRAN-PROC-DT} lies inside
     * the {@code INCLUDE} range, orders what survives by {@code TRAN-CARD-NUM} ascending, and writes the
     * result to {@value #SORT_OUTPUT_DD_NAME} - which it empties first, for the same
     * {@code DISP=(NEW,CATLG,DELETE)} reason the unload does.
     *
     * <p><strong>Filter before sort, and both before the write.</strong> DFSORT applies {@code INCLUDE}
     * as it reads, so a record outside the range never reaches the sort; doing it in the other order
     * would produce the same set but is not what the utility does, and would sort records it then threw
     * away.
     *
     * <p><strong>This ordering is load-bearing.</strong> {@code CBTRN03C} breaks and subtotals by
     * account as records arrive ({@code app/cbl/CBTRN03C.cbl:L168-L207}), resolving the account from the
     * card number, so records for one card must arrive together. Reporting from an unsorted file would
     * produce the right rows under the wrong subtotals.
     *
     * @return how many records were written to {@value #SORT_OUTPUT_DD_NAME}
     * @throws IllegalStateException if either dataset cannot be addressed
     */
    public int filterAndSortUnloadedTransactions() {
        return filterAndSortUnloadedTransactions(StopSignal.RUNNING);
    }

    /**
     * {@value #SORT_STEP_NAME}, yielding to a stop request between written records.
     *
     * <p>Identical to {@link #filterAndSortUnloadedTransactions()} in what it includes, how it orders it
     * and what it writes. The read and the clear are one statement each, bounded by the configured
     * statement timeout; the write is a record at a time, which is where a stop can be honoured. Nothing
     * is retried, so a stopped write leaves the records it had already written and reports that count -
     * and the step reports as stopped, so the report step is never fed a half-sorted file as though it
     * were whole.
     *
     * @param stopSignal the between-record cancellation probe; never {@code null}
     * @return how many records were written to {@value #SORT_OUTPUT_DD_NAME}
     * @throws IllegalStateException if either dataset cannot be addressed
     * @throws BatchConfig.StopRequestedException if the step is asked to stop
     */
    public int filterAndSortUnloadedTransactions(StopSignal stopSignal) {
        DatasetBinding source = batchConfig.datasetBinding(JOB_KEY, SORT_INPUT_DD_NAME);
        DatasetBinding destination = batchConfig.datasetBinding(JOB_KEY, SORT_OUTPUT_DD_NAME);
        List<String> included = filterAndSort(datasetUtilityPort.readAllRecordImages(source));
        datasetUtilityPort.deleteAllRecords(destination);
        return datasetUtilityPort.writeRecordImages(destination, included, stopSignal);
    }

    /**
     * The SORT step's transformation, as a function of its input - so it is assertable with no dataset,
     * no backend and no step execution.
     *
     * @param recordImages the unloaded records, each {@value TranRecord#RECORD_LENGTH} characters; never
     *                     {@code null} and never containing {@code null}
     * @return the included records in {@code TRAN-CARD-NUM} order; empty when none qualifies, which is
     *         not an error - it is an empty report
     * @throws IllegalArgumentException if any record is not {@value TranRecord#RECORD_LENGTH} characters
     */
    public List<String> filterAndSort(List<String> recordImages) {
        Objects.requireNonNull(recordImages, "Records are required to filter and sort; an empty unload "
                + "yields an empty sorted file and is not an error");
        List<String> included = new ArrayList<>(recordImages.size());
        for (String recordImage : recordImages) {
            if (includedByDateRange(requireSortableRecord(recordImage))) {
                included.add(recordImage);
            }
        }
        included.sort(SORT_FIELDS_ORDER);
        return included;
    }

    /**
     * {@code INCLUDE COND=(TRAN-PROC-DT,GE,PARM-START-DATE,AND,TRAN-PROC-DT,LE,PARM-END-DATE)}, applied
     * to one record.
     *
     * <p>Both bounds are <strong>inclusive</strong> - {@code GE} and {@code LE}, not {@code GT} and
     * {@code LT} - so a transaction processed on either boundary date is reported. The field is declared
     * {@code CH} in the SORT symbol table, so it is compared as characters rather than as a date: no
     * parsing, no calendar, no time zone, and a malformed value simply falls outside the range instead of
     * failing the step. Lexicographic comparison is the right one for this domain, and it is the same
     * under both code pages this module reads: {@code TRAN-PROC-DT} holds {@code YYYY-MM-DD}, whose
     * digits are monotonically ordered and whose hyphen sorts below every digit under US-ASCII and under
     * IBM037 alike.
     *
     * @param recordImage one record, already width-checked
     * @return whether DFSORT would have included it
     */
    private static boolean includedByDateRange(String recordImage) {
        String processedDate = jclField(recordImage, SORT_TRAN_PROC_DT_POSITION,
                SORT_TRAN_PROC_DT_LENGTH);
        return processedDate.compareTo(SORT_INCLUDE_START_DATE) >= 0
                && processedDate.compareTo(SORT_INCLUDE_END_DATE) <= 0;
    }

    /**
     * Requires one record to be exactly as wide as the copybook declares, before a fixed span is read
     * out of it.
     *
     * <p>A short record would make {@link #jclField} throw
     * {@link StringIndexOutOfBoundsException} - an exception naming an index rather than the dataset
     * whose record was the wrong width. The check is here so the diagnostic names the DD, the declared
     * width and the width found.
     *
     * @param recordImage the record to check
     * @return that record, unchanged
     * @throws IllegalArgumentException if it is not {@value TranRecord#RECORD_LENGTH} characters
     */
    private static String requireSortableRecord(String recordImage) {
        Objects.requireNonNull(recordImage, "A record of " + SORT_INPUT_DD_NAME + " is null, which is "
                + "not a record a dataset can hold");
        if (recordImage.length() != TranRecord.RECORD_LENGTH) {
            throw new IllegalArgumentException("A record of " + SORT_INPUT_DD_NAME + " is "
                    + recordImage.length() + " characters, but app/cpy/CVTRA05Y.cpy declares (RECLN "
                    + TranRecord.RECORD_LENGTH + ") and app/jcl/TRANREPT.jcl:31 unloads at LRECL="
                    + TranRecord.RECORD_LENGTH + ". TRAN-PROC-DT sits at one-based "
                    + SORT_TRAN_PROC_DT_POSITION + " and TRAN-CARD-NUM at one-based "
                    + SORT_TRAN_CARD_NUM_POSITION + ", so a record of any other width would be filtered "
                    + "and ordered on the wrong bytes.");
        }
        return recordImage;
    }

    /**
     * {@code SORT FIELDS=(TRAN-CARD-NUM,A)} - ascending {@code TRAN-CARD-NUM}, the field the symbol
     * table declares at {@code 263,16,ZD} ({@code app/jcl/TRANREPT.jcl:L41,L46}).
     *
     * <p>{@code ZD} is zoned decimal, and for this field's domain zoned-decimal order and character
     * order coincide: {@code TRAN-CARD-NUM} is a {@code PIC X(16)} span holding sixteen unsigned digits,
     * so there is no sign overpunch to interpret, no length difference to align and no letter to
     * disagree about. A {@link String} comparison therefore produces DFSORT's order, and it produces the
     * same order under US-ASCII and IBM037 because the digits are monotonic in both.
     *
     * <p><strong>The sort is stable, deliberately.</strong> A card has many transactions, so equal keys
     * are the normal case rather than the exception, and their relative order decides the order of the
     * detail lines inside an account's block of the report. DFSORT leaves that order unspecified unless
     * {@code EQUALS} is in effect; a migration cannot leave it unspecified and still be verifiable, so
     * the input order is preserved - which, after a {@code REPRO} of the master, is {@code TRAN-ID}
     * order.
     *
     * <p>Stateless and immutable, so publishing it as a constant introduces no shared mutable state.
     */
    public static final Comparator<String> SORT_FIELDS_ORDER = Comparator.comparing(
            (String recordImage) -> jclField(recordImage, SORT_TRAN_CARD_NUM_POSITION,
                    SORT_TRAN_CARD_NUM_LENGTH));

    /**
     * The master's key order: ascending {@code TRAN-ID}, the {@value TranRecord#TRAN_ID_KEY_LENGTH}-byte
     * key at offset {@value TranRecord#TRAN_ID_OFFSET} that {@code app/csd/CARDDEMO.CSD} defines the
     * cluster on - the sequence {@code REPRO} delivers a KSDS in.
     */
    private static final Comparator<String> MASTER_KEY_ORDER = Comparator.comparing(
            (String recordImage) -> recordImage.substring(TranRecord.TRAN_ID_OFFSET,
                    TranRecord.TRAN_ID_OFFSET + TranRecord.TRAN_ID_KEY_LENGTH));

    /**
     * One JCL field reference: {@code length} characters starting at the 1-based {@code position}.
     *
     * <p><strong>The 1-based-to-0-based conversion lives here and nowhere else.</strong> JCL and DFSORT
     * count byte positions from 1 and Java counts from 0; doing the subtraction inline at each call site
     * is how an off-by-one gets in.
     *
     * @param recordImage      the record to read from
     * @param oneBasedPosition the SORT symbol table's position, counting from 1
     * @param length           how many characters to take
     * @return exactly {@code length} characters
     */
    private static String jclField(String recordImage, int oneBasedPosition, int length) {
        int from = oneBasedPosition - 1;
        return recordImage.substring(from, from + length);
    }

    /**
     * The step, named for {@code //STEP10R EXEC PGM=CBTRN03C}.
     *
     * <p>A {@link Tasklet} step, for the reason set out on this class: {@code CBTRN03C} carries three
     * running totals, a line counter and an account-break key across records, and chunking that would
     * relocate the commit boundaries and with them the order the lines are written in.
     *
     * @return the single migrated step of this job
     */
    @Bean(STEP_BEAN_NAME)
    public Step transactionReportStep() {
        return batchConfig.taskletStep(stepName, transactionReportTasklet()).build();
    }

    /**
     * The step body: a thin adapter that resolves the {@code SYSOUT} destination and runs the program.
     *
     * <p>It holds no logic of its own on purpose. Everything the COBOL does lives in
     * {@link #execute(SysoutSink)}, which needs neither a {@code JobLauncher} nor an application
     * context, so every branch of the translation is reachable from a plain unit test (practice B10,
     * gate G51). The tasklet contributes no read or write counts to the step metadata:
     * {@code CBTRN03C}'s only counter is {@code WS-LINE-COUNTER}, which counts report lines rather than
     * records, and publishing invented metrics would put numbers in the batch metadata that no legacy
     * artefact can confirm.
     *
     * <p>Not a bean. The step is the bean; a separately published tasklet would be a second handle on
     * the same body with no caller.
     *
     * @return a tasklet that runs the program exactly once per step execution
     */
    public Tasklet transactionReportTasklet() {
        return (contribution, chunkContext) -> {
            execute(resolveSysoutSink(), null, StopSignal.of(chunkContext));
            return RepeatStatus.FINISHED;
        };
    }

    // =================================================================================================
    // THE PROGRAM. app/cbl/CBTRN03C.cbl:159-217, statement for statement, with the paragraphs it
    // performs following it in the order the source declares them.
    // =================================================================================================

    /**
     * Runs {@code CBTRN03C} against the configured {@value #TRANREPT_DD_NAME} dataset.
     *
     * @param sysout where the {@code DISPLAY} lines go; never {@code null}
     * @return what the run produced and the return code it ended with
     * @throws NullPointerException  if {@code sysout} is {@code null}
     * @throws AbendException        if any open, read, write or close reports a status the program does
     *                               not name, or if a keyed lookup finds nothing
     * @throws IllegalStateException if the configured {@value #TRANREPT_DD_NAME} name cannot be
     *                               addressed as a dataset, which is a configuration fault rather than
     *                               a dataset condition and therefore has no COBOL guard
     */
    public ExecutionSummary execute(SysoutSink sysout) {
        return execute(sysout, null);
    }

    /**
     * Runs {@code CBTRN03C}, optionally against a caller-supplied report sink.
     *
     * <p>This is the seam that makes the whole program assertable with no database, no filesystem, no
     * application context and no {@code JobLauncher}: a test passes a collecting
     * {@link TranReportWriter.RecordSink} and asserts the emitted 133-byte records, their order and
     * their content directly, while a capturing {@link SysoutSink} collects the {@code DISPLAY} lines.
     * It is equally the supported extension point for a deployment whose data-access driver expects a
     * different parameter shape.
     *
     * <p>The COBOL mainline, and the Java below it, in the same order:
     * <pre>
     * PROCEDURE DIVISION.                                              :159
     *     DISPLAY 'START OF EXECUTION OF PROGRAM CBTRN03C'.            :160
     *     PERFORM 0000-TRANFILE-OPEN.                                  :161
     *     PERFORM 0100-REPTFILE-OPEN.                                  :162
     *     PERFORM 0200-CARDXREF-OPEN.                                  :163
     *     PERFORM 0300-TRANTYPE-OPEN.                                  :164
     *     PERFORM 0400-TRANCATG-OPEN.                                  :165
     *     PERFORM 0500-DATEPARM-OPEN.                                  :166
     *     PERFORM 0550-DATEPARM-READ.                                  :168
     *     PERFORM UNTIL END-OF-FILE = 'Y' ... END-PERFORM.             :170-206
     *     PERFORM 9000-TRANFILE-CLOSE.                                 :208
     *     PERFORM 9100-REPTFILE-CLOSE.                                 :209
     *     PERFORM 9200-CARDXREF-CLOSE.                                 :210
     *     PERFORM 9300-TRANTYPE-CLOSE.                                 :211
     *     PERFORM 9400-TRANCATG-CLOSE.                                 :212
     *     PERFORM 9500-DATEPARM-CLOSE.                                 :213
     *     DISPLAY 'END OF EXECUTION OF PROGRAM CBTRN03C'.              :215
     *     GOBACK.                                                      :217
     * </pre>
     *
     * <p>The six opens and the six closes are in the <em>same</em> order, which is worth stating because
     * it is not the order a Java author would choose: a close sequence usually mirrors its open
     * sequence. Here {@value #TRANFILE_DD_NAME} is opened first and closed first.
     *
     * <p><strong>An abend leaves every handle open</strong>, exactly as {@code CALL 'CEE3ABD'} does: it
     * terminates the task without reaching the close paragraphs. Nothing leaks by doing so - a browse
     * holds no connection between reads and the report sink borrows one per record - and closing here
     * would emit close-failure messages the program never writes.
     *
     * @param sysout     where the {@code DISPLAY} lines go; never {@code null}
     * @param reportSink where the report records go, or {@code null} to write to the configured
     *                   {@value #TRANREPT_DD_NAME} dataset
     * @return what the run produced and the return code it ended with
     * @throws NullPointerException  if {@code sysout} is {@code null}
     * @throws AbendException        if any open, read, write or close reports a status the program does
     *                               not name, or if a keyed lookup finds nothing
     * @throws IllegalStateException if {@code reportSink} is {@code null} and the configured
     *                               {@value #TRANREPT_DD_NAME} name cannot be addressed as a dataset
     */
    public ExecutionSummary execute(SysoutSink sysout, TranReportWriter.RecordSink reportSink) {
        return execute(sysout, reportSink, StopSignal.RUNNING);
    }

    /**
     * Runs {@code CBTRN03C}, yielding to the given stop signal between records.
     *
     * <p>The pass is identical to {@link #execute(SysoutSink, TranReportWriter.RecordSink)} - same reads,
     * same lookups, same report records, same totals, same order - and the signal changes nothing while
     * no stop is pending. It exists because this program is one pass over the whole filtered transaction
     * file inside a single tasklet invocation, so the framework's interruption check at the step's repeat
     * boundary happens once and cannot end a pass already under way.
     *
     * <p>The probe is consulted between records, where the record in flight is complete: its detail line
     * has been written and its subtotals accumulated, or it has not been read. Nothing is retried - a
     * report record already written stays written, and the page and grand totals are simply not reached,
     * exactly as they are not reached on the {@code NEXT SENTENCE} defect the loop preserves. See
     * {@link StopSignal}.
     *
     * @param sysout     where the {@code DISPLAY} lines go; never {@code null}
     * @param reportSink where the report records go, or {@code null} to write to the configured
     *                   {@value #TRANREPT_DD_NAME} dataset
     * @param stopSignal the between-record cancellation probe; {@link StopSignal#RUNNING} for a caller
     *                   outside a step; never {@code null}
     * @return what the run produced and the return code it ended with
     * @throws NullPointerException  if {@code sysout} or {@code stopSignal} is {@code null}
     * @throws AbendException        if any open, read, write or close reports a status the program does
     *                               not name, or if a keyed lookup finds nothing
     * @throws IllegalStateException if {@code reportSink} is {@code null} and the configured
     *                               {@value #TRANREPT_DD_NAME} name cannot be addressed as a dataset
     * @throws BatchConfig.StopRequestedException if the step is asked to stop, which abandons the pass
     *                               between records
     */
    public ExecutionSummary execute(SysoutSink sysout, TranReportWriter.RecordSink reportSink,
            StopSignal stopSignal) {
        Objects.requireNonNull(sysout, "A SYSOUT sink is required to run " + PROGRAM_NAME
                + ": its DISPLAY lines are half of its observable output, so there is nothing to run "
                + "without somewhere to put them");
        Objects.requireNonNull(stopSignal, "A stop signal is required; pass StopSignal.RUNNING outside a "
                + "step, which is what the two-argument overload does");
        return new ReportRun(sysout, reportSink).run(stopSignal);
    }

    // =================================================================================================
    // The two decisions worth asserting on their own, as pure functions. Both are called from the run
    // below; publishing them lets a parity case pin the rule without staging a whole report.
    // =================================================================================================

    /**
     * {@code IF FUNCTION MOD(WS-LINE-COUNTER, WS-PAGE-SIZE) = 0} - {@code app/cbl/CBTRN03C.cbl:282}.
     *
     * <p>The test is against the counter's value <em>before</em> the detail line is written, and the
     * counter has already been advanced by four by the first-time header write, so the first record of
     * a report evaluates {@code MOD(4, 20)} and never breaks a page. That ordering is emergent rather
     * than designed - the two {@code IF}s at {@code :275} and {@code :282} simply run in that order -
     * and reordering them to make the intent clearer would move every page boundary in the report.
     *
     * <p>{@code WS-LINE-COUNTER} is {@code PIC 9(09) COMP-3}, an unsigned nine-digit packed field, so a
     * negative argument cannot arise in the program and is rejected here rather than given a meaning
     * COBOL does not define for it.
     *
     * @param lineCounter the current {@code WS-LINE-COUNTER}
     * @return {@code true} when a page total and a fresh set of headers are due
     * @throws IllegalArgumentException if {@code lineCounter} is negative
     */
    public static boolean isPageBoundary(long lineCounter) {
        if (lineCounter < 0) {
            throw new IllegalArgumentException("WS-LINE-COUNTER is PIC 9(09) COMP-3, an unsigned "
                    + "field, so " + lineCounter + " is not a value it can hold. FUNCTION MOD is being "
                    + "asked about a counter that could not have been produced by "
                    + PROGRAM_NAME + ".");
        }
        return lineCounter % PAGE_SIZE == 0;
    }

    /**
     * {@code IF TRAN-PROC-TS (1:10) >= WS-START-DATE AND TRAN-PROC-TS (1:10) <= WS-END-DATE} -
     * {@code app/cbl/CBTRN03C.cbl:173-174}.
     *
     * <p>An <strong>alphanumeric</strong> comparison of three {@code PIC X(10)} values, not a date
     * comparison: COBOL compares character by character, so the {@code yyyy-mm-dd} shape the fixtures
     * use happens to order correctly while a differently shaped value would not. Reproduced as
     * {@link String#compareTo(String)} on the raw span, with no parsing, no {@code java.time} and no
     * locale involved - parsing would reject values COBOL happily compares, and would turn a report
     * over malformed data into an exception the program has no arm for.
     *
     * <p>The result governs defect 1: when it is {@code false}, the {@code ELSE} at {@code :176}
     * executes {@code NEXT SENTENCE}, which ends the entire read loop.
     *
     * @param procDate  {@code TRAN-PROC-TS (1:10)}, the first ten characters of the processing timestamp
     * @param startDate {@code WS-START-DATE}
     * @param endDate   {@code WS-END-DATE}
     * @return {@code true} when the record is inside the reporting range
     * @throws NullPointerException if any argument is {@code null}
     */
    public static boolean withinReportingRange(String procDate, String startDate, String endDate) {
        Objects.requireNonNull(procDate, "TRAN-PROC-TS (1:10) is required to test the reporting range");
        Objects.requireNonNull(startDate, "WS-START-DATE is required to test the reporting range");
        Objects.requireNonNull(endDate, "WS-END-DATE is required to test the reporting range");
        return procDate.compareTo(startDate) >= 0 && procDate.compareTo(endDate) <= 0;
    }


    // =================================================================================================
    // SYSOUT. app/jcl/TRANREPT.jcl:L62-L63 declares //SYSOUT DD SYSOUT=* and //SYSPRINT DD SYSOUT=*;
    // neither is a dataset this module binds, so the destination is a deployment-time input.
    // =================================================================================================

    /**
     * The {@code SYSOUT} destination for a run: the injected one when a deployment supplies it, and
     * {@link #defaultSysoutSink()} when it does not.
     *
     * @return the sink to display through; never {@code null}
     */
    private SysoutSink resolveSysoutSink() {
        return sysoutSinkProvider.getIfAvailable(this::defaultSysoutSink);
    }

    /**
     * The fallback {@code SYSOUT} destination: the process's standard output, in the dataset code page.
     *
     * <p>Named once, here, and never from the program body - {@link #execute(SysoutSink)} only ever
     * calls {@link SysoutSink#display(String)} - so a test or a parity harness substitutes a
     * destination without this class knowing the difference.
     *
     * @return a sink writing to standard output
     */
    public SysoutSink defaultSysoutSink() {
        return sysoutSinkTo(System.out);
    }

    /**
     * A {@code SYSOUT} sink over an arbitrary stream, encoded in the dataset code page.
     *
     * @param destination where the encoded lines go; never {@code null}
     * @return a sink writing to {@code destination}
     * @throws NullPointerException if {@code destination} is {@code null}
     */
    public SysoutSink sysoutSinkTo(OutputStream destination) {
        return new StreamSysoutSink(destination, codec);
    }

    // =================================================================================================
    // Accessors. Read-only views of what configuration bound, for a test or an operator.
    // =================================================================================================

    /**
     * The dataset DD {@value #TRANFILE_DD_NAME} resolves to for this job.
     *
     * @return the configured dataset name; never blank
     */
    public String tranFileDatasetName() {
        return tranFileDatasetName;
    }

    /**
     * The configured step name, which is {@value #STEP_NAME} unless configuration says otherwise.
     *
     * @return the step name as the batch metadata will record it
     */
    public String stepName() {
        return stepName;
    }

    /**
     * The configured name of the unload step, which is {@value #BACKUP_STEP_NAME} unless configuration
     * says otherwise.
     *
     * @return the step name as the batch metadata will record it
     */
    public String backupStepName() {
        return backupStepName;
    }

    /**
     * The configured name of the filter-and-sort step, which is {@value #SORT_STEP_NAME} unless
     * configuration says otherwise.
     *
     * @return the step name as the batch metadata will record it
     */
    public String sortStepName() {
        return sortStepName;
    }

    /**
     * The dataset-utility path the two preparatory steps use, as resolved.
     *
     * @return the port, deployment-supplied or the JDBC default; never {@code null}
     */
    public DatasetUtilityPort datasetUtilityPort() {
        return datasetUtilityPort;
    }

    /**
     * The dataset code page every byte of this job's input, output and {@code SYSOUT} is read and
     * written in.
     *
     * @return the injected charset; never {@code null}
     */
    public Charset datasetCharset() {
        return codec.charset();
    }

    // =================================================================================================
    // ReportRun - one execution of CBTRN03C. Every item of the program's WORKING-STORAGE is a field
    // here, so nothing survives a run and nothing is shared between two (practice B9, gate G53).
    // =================================================================================================

    /**
     * One execution of {@code CBTRN03C}: its {@code WORKING-STORAGE}, its file handles and its
     * paragraphs.
     *
     * <p>An inner class rather than a set of static helpers taking a state parameter, because the COBOL
     * paragraphs read and write shared {@code WORKING-STORAGE} freely and threading a state object
     * through twenty methods would obscure exactly the ordering this translation exists to preserve.
     * One instance per {@link TransactionReportJob#execute(SysoutSink, TranReportWriter.RecordSink)},
     * never reused, never shared.
     */
    private final class ReportRun {

        /** Where the {@code DISPLAY} lines go. */
        private final SysoutSink sysout;

        /**
         * Where the report records go, or {@code null} for the configured
         * {@value TransactionReportJob#TRANREPT_DD_NAME} dataset.
         */
        private final TranReportWriter.RecordSink reportSink;

        /**
         * The seven {@code CVTRA07Y} record areas, allocated once per run with their {@code VALUE}
         * clauses already applied - so the captions, the {@code ' to '} separator, the two {@code '-'}
         * separators, the three dot leaders and the 133-hyphen rule line are all in place before the
         * first write.
         */
        private final TranReportLayouts layouts;

        /**
         * {@code 01 TRAN-RECORD} - the {@code COPY CVTRA05Y} area at {@code app/cbl/CBTRN03C.cbl:93}.
         *
         * <p>Allocated as a fresh 350-byte area, so its {@code PIC X} fields read back as spaces and its
         * numeric fields as zeros, which is what an unpopulated {@code WORKING-STORAGE} record gives the
         * program. Replaced wholesale by each successful {@code READ ... INTO}, and - crucially -
         * <strong>left untouched at end of file</strong>, which is what makes defect 2 happen and what
         * makes the range test at {@code :173} see the last record read rather than a cleared area.
         */
        private TranRecord tranRecord;

        /** {@code 05 WS-START-DATE PIC X(10)} - {@code :123}, filled by {@code 0550-DATEPARM-READ}. */
        private String startDate = " ".repeat(DATE_LENGTH);

        /** {@code 05 WS-END-DATE PIC X(10)} - {@code :125}. */
        private String endDate = " ".repeat(DATE_LENGTH);

        /** {@code 05 WS-FIRST-TIME PIC X VALUE 'Y'} - {@code :128}. */
        private String firstTime = FIRST_TIME;

        /** {@code 01 END-OF-FILE PIC X(01) VALUE 'N'} - {@code :154}. */
        private String endOfFile = NOT_AT_END_OF_FILE;

        /**
         * {@code 05 WS-LINE-COUNTER PIC 9(09) COMP-3 VALUE 0} - {@code :129-130}.
         *
         * <p>A {@code long} rather than an {@code int}: the field holds nine decimal digits, which an
         * {@code int} would also hold, but the counter is only ever compared and incremented and a
         * {@code long} removes any question of overflow from a very long report. Never a floating-point
         * type (gate G22).
         */
        private long lineCounter;

        /** {@code 05 WS-PAGE-TOTAL PIC S9(09)V99 VALUE 0} - {@code :134}. */
        private BigDecimal pageTotal = CobolDecimal.monetaryZero();

        /** {@code 05 WS-ACCOUNT-TOTAL PIC S9(09)V99 VALUE 0} - {@code :135}. */
        private BigDecimal accountTotal = CobolDecimal.monetaryZero();

        /** {@code 05 WS-GRAND-TOTAL PIC S9(09)V99 VALUE 0} - {@code :136}. */
        private BigDecimal grandTotal = CobolDecimal.monetaryZero();

        /**
         * {@code 05 WS-CURR-CARD-NUM PIC X(16) VALUE SPACES} - {@code :137}.
         *
         * <p>Spaces initially, which is why the first record always looks like an account break: no card
         * number can equal sixteen spaces, so {@code :181} is true and the cross reference is read.
         * {@code WS-FIRST-TIME} is what stops that first break writing an account total for an account
         * that has not been reported yet.
         */
        private String currentCardNumber = " ".repeat(CARD_NUMBER_KEY_LENGTH);

        /**
         * {@code 05 FD-XREF-CARD-NUM PIC X(16)} - the {@code RECORD KEY} of the {@code CARDXREF} FD at
         * {@code :69}, moved at {@code :186} and displayed by {@code 1500-A-LOOKUP-XREF} on failure.
         */
        private String xrefCardNumberKey = " ".repeat(CARD_NUMBER_KEY_LENGTH);

        /**
         * {@code 05 FD-TRAN-TYPE PIC X(02)} - the {@code RECORD KEY} of the {@code TRANTYPE} FD at
         * {@code :74}, moved at {@code :189}.
         */
        private String tranTypeKey = " ".repeat(TranTypeRecord.TRAN_TYPE_LENGTH);

        /**
         * {@code 10 FD-TRAN-TYPE-CD PIC X(02)} - the first half of the {@code TRANCATG} FD's
         * {@code RECORD KEY} at {@code :80}, moved at {@code :191-192}.
         */
        private String tranCatTypeCode = " ".repeat(TranCategoryRecord.TRAN_TYPE_CD_LENGTH);

        /**
         * {@code 10 FD-TRAN-CAT-CD PIC 9(04)} - the second half of that key at {@code :81}, moved at
         * {@code :193-194}. An {@code int}, because the picture is a scale-free {@code PIC 9}.
         */
        private int tranCatCode;

        /**
         * {@code 01 CARD-XREF-RECORD} - the {@code COPY CVACT03Y} area at {@code :98}, filled by
         * {@code 1500-A-LOOKUP-XREF} and read for {@code XREF-ACCT-ID} at {@code :364}.
         */
        private CardXrefRecord xrefRecord;

        /**
         * {@code 01 TRAN-TYPE-RECORD} - the {@code COPY CVTRA03Y} area at {@code :103}, filled by
         * {@code 1500-B-LOOKUP-TRANTYPE} and read for {@code TRAN-TYPE-DESC} at {@code :366}.
         */
        private TranTypeRecord tranTypeRecord;

        /**
         * {@code 01 TRAN-CAT-RECORD} - the {@code COPY CVTRA04Y} area at {@code :108}, filled by
         * {@code 1500-C-LOOKUP-TRANCATG} and read for {@code TRAN-CAT-TYPE-DESC} at {@code :368}.
         */
        private TranCategoryRecord tranCategoryRecord;

        /** The open {@value TransactionReportJob#TRANFILE_DD_NAME} browse. */
        private TransactionRepository.InputFile tranFile;

        /** The open {@value TransactionReportJob#TRANREPT_DD_NAME} handle. */
        private TranReportWriter.ReportFile reportFile;

        /**
         * The open {@value TransactionReportJob#CARDXREF_DD_NAME} handle.
         *
         * <p>{@link CardXrefRepository} publishes no {@code open}/{@code close} pair of its own, so
         * {@link CardXrefRepository#openBrowse()} stands in for {@code OPEN INPUT XREF-FILE}: it
         * establishes that the cluster is addressable, reports the status the {@code '00'}-or-12 ladder
         * at {@code :415} tests, and closes symmetrically at {@code :553}. The keyed reads in between go
         * through {@link CardXrefRepository#readByCardNumber(String)}, because the COBOL's
         * {@code ACCESS MODE IS RANDOM} read is keyed rather than positional.
         */
        private BrowseCursor xrefCursor;

        /** How many records the {@value TransactionReportJob#TRANFILE_DD_NAME} browse returned. */
        private int recordsRead;

        /** How many detail lines {@code 1120-WRITE-DETAIL} wrote. */
        private int detailLinesWritten;

        /**
         * Allocates the run's record areas and remembers where its two kinds of output go.
         *
         * @param sysout     the {@code SYSOUT} destination; already null-checked by the caller
         * @param reportSink the report destination, or {@code null} for the configured dataset
         */
        private ReportRun(SysoutSink sysout, TranReportWriter.RecordSink reportSink) {
            this.sysout = sysout;
            this.reportSink = reportSink;
            this.layouts = new TranReportLayouts(codec);
            this.tranRecord = new TranRecord(codec.charset());
            // The three lookup areas exist from the start, as WORKING-STORAGE items do, rather than
            // being null until a read fills them. It matters for one reachable path: a record whose
            // TRAN-CARD-NUM is sixteen spaces equals the initial WS-CURR-CARD-NUM, so :181 is false, so
            // 1500-A-LOOKUP-XREF never runs - and :364 then moves whatever CARD-XREF-RECORD holds. In
            // COBOL that is an unpopulated area; here it is an unpopulated area too, and the detail line
            // shows a zero account rather than the translation throwing where the program does not.
            this.xrefRecord = new CardXrefRecord("", 0, 0L);
            this.tranTypeRecord = TranTypeRecord.empty(codec);
            this.tranCategoryRecord = TranCategoryRecord.of("  ", 0, "", codec.charset());
        }

        /**
         * The mainline, {@code app/cbl/CBTRN03C.cbl:159-217}.
         *
         * <p>See {@link TransactionReportJob#execute(SysoutSink, TranReportWriter.RecordSink)} for the
         * transcription of the source this follows, and this class's outer documentation for the two
         * defects the loop preserves.
         *
         * @param stopSignal the between-record cancellation probe
         * @return what the run produced
         * @throws AbendException if any paragraph reaches {@code 9999-ABEND-PROGRAM}
         * @throws BatchConfig.StopRequestedException if the step is asked to stop
         */
        private ExecutionSummary run(StopSignal stopSignal) {
            // Whether this run reached 9999-GOBACK. Read by the finally block, which is the only place
            // that can tell a normal end from an abnormal one - and app/jcl/TRANREPT.jcl:76-80 declares a
            // different disposition for each. Per-run state, never a field on the job (gate G53).
            boolean completedNormally = false;
            try {
                ExecutionSummary summary = runToGoback(stopSignal);
                completedNormally = true;
                return summary;
            } finally {
                releaseHandles(completedNormally);
            }
        }

        /**
         * Releases this run's three acquired handles, silently and idempotently.
         *
         * <p>The {@code finally} above is not a second {@code CLOSE}. It reports nothing, displays
         * nothing and cannot change what the caller sees: on the normal path {@code :208-213} has
         * already closed every handle and each close is guarded here by {@code isOpen()}, so it does
         * nothing at all. It exists for the abend and stop paths. {@code CALL 'CEE3ABD'} ends a z/OS
         * task and the operating system reclaims its open files; an {@link AbendException} - or a
         * {@link BatchConfig.StopRequestedException} - ends one job step inside a JVM that keeps
         * running, and a deployment-supplied cursor or sink left open there is held for the life of the
         * process.
         *
         * <p>Three properties, and each is what keeps this from changing the program's behaviour:
         * <ul>
         *   <li><strong>Idempotent, and only what was acquired.</strong> A field is {@code null} until
         *       its open runs, and each handle is asked whether it is still open before it is closed -
         *       which also keeps a failed open silent, because
         *       {@link TransactionRepository.InputFile#closeInput()} and
         *       {@link BrowseCursor#closeBrowse()} both report a permanent error for a close of
         *       something that never opened, and that is a condition the program has already abended
         *       on.</li>
         *   <li><strong>Silent.</strong> No {@code SYSOUT} line, no status returned, nothing branched
         *       on. The six {@code CLOSE} statements of {@code :208-213} are the program's only closes
         *       and they are already translated with their {@code '00'}-or-12 ladders intact. Their
         *       {@code 'ERROR CLOSING ...'} displays belong there and are not repeated here.</li>
         *   <li><strong>Non-throwing.</strong> Every failure is swallowed, because this runs in a
         *       {@code finally}: throwing would replace the {@link AbendException} the caller needs
         *       with a cleanup fault, and the abend is always the more important of the two. The
         *       swallowed condition is logged instead, so it stays diagnosable.</li>
         * </ul>
         *
         * <p>{@value TransactionReportJob#TRANTYPE_DD_NAME},
         * {@value TransactionReportJob#TRANCATG_DD_NAME} and
         * {@value TransactionReportJob#DATEPARM_DD_NAME} hold nothing to release: their repositories
         * expose {@code open}/{@code close} as dataset probes rather than handles, so there is no
         * per-run object for this method to reclaim.
         *
         * <p>The closing is unconditional; the <em>disposition</em> is not.
         * {@value TransactionReportJob#TRANREPT_DD_NAME} is declared
         * {@code DISP=(NEW,CATLG,DELETE)} ({@code app/jcl/TRANREPT.jcl:76-80}), whose second and third
         * positionals are different outcomes: a run that reached {@code 9999-GOBACK} leaves the report
         * catalogued for whoever prints it, and a run that did not must leave no report at all. So a
         * non-normal end additionally runs {@link #discardReportGeneration()}, after the close and never
         * before it.
         *
         * @param completedNormally whether the run reached {@code 9999-GOBACK}. It decides which
         *                          {@code DISP} positional applies to
         *                          {@value TransactionReportJob#TRANREPT_DD_NAME} and nothing else: the
         *                          closing is identical either way
         */
        private void releaseHandles(boolean completedNormally) {
            if (tranFile != null && tranFile.isOpen()) {
                try {
                    tranFile.closeInput();
                } catch (RuntimeException cleanupFailure) {
                    reportCleanupFailure(TRANFILE_DD_NAME, cleanupFailure);
                }
            }
            if (reportFile != null && reportFile.isOpen()) {
                try {
                    reportFile.closeOutput();
                } catch (RuntimeException cleanupFailure) {
                    reportCleanupFailure(TRANREPT_DD_NAME, cleanupFailure);
                }
            }
            if (xrefCursor != null && xrefCursor.isOpen()) {
                try {
                    xrefCursor.closeBrowse();
                } catch (RuntimeException cleanupFailure) {
                    reportCleanupFailure(CARDXREF_DD_NAME, cleanupFailure);
                }
            }
            if (!completedNormally) {
                // Close first, then dispose: the order z/OS uses, and the order the writer documents.
                discardReportGeneration();
            }
        }

        /**
         * Applies {@value TransactionReportJob#TRANREPT_DD_NAME}'s abnormal disposition - the
         * {@code DELETE} positional of {@code DISP=(NEW,CATLG,DELETE)} at
         * {@code app/jcl/TRANREPT.jcl:76-80}.
         *
         * <p><strong>Why this is not a rollback.</strong> The step allocates the report ({@code NEW}) and
         * catalogues it only if it ends normally; any other ending deletes it. A rollback offers two
         * outcomes - every write kept, or the uncommitted writes dropped - and the mainframe's third is
         * neither, because every line written here is durable as it completes over a
         * {@code RECOVERY(NONE)} dataset.
         *
         * <p><strong>Why it matters for this dataset specifically.</strong> A transaction detail report is
         * read by people, and one truncated at the page a run abended on is not obviously truncated: the
         * page totals of {@code :299-321} are all present, and only the account and grand totals of
         * {@code :322-344} - the figures anyone would actually rely on - are missing or partial. z/OS
         * leaves nothing to misread, and so does this.
         *
         * <p><strong>Silent and non-throwing.</strong> Nothing is displayed on {@code SYSOUT}: the COBOL has
         * no paragraph for a disposition, so a line here would be output the program does not produce.
         * Nothing is thrown: this runs in a {@code finally} on a path that is already failing, and the
         * {@link AbendException} the caller needs is always the more important of the two. A disposition
         * that cannot be applied is logged instead, so it stays diagnosable.
         *
         * <p>A run that wrote no line deletes nothing and says nothing -
         * {@link TranReportWriter.ReportFile#discardGeneration()} reports a discard as a no-op when its
         * record count is zero - so a run that abended at its own open leaves no trace here.
         */
        private void discardReportGeneration() {
            if (reportFile == null) {
                return;
            }
            try {
                FileStatus.Outcome disposition = unitOfWork.persistDisposition(
                        TRANREPT_ABNORMAL_DISPOSITION, reportFile::discardGeneration);
                if (disposition != FileStatus.Outcome.OK) {
                    LOG.error("The " + TRANREPT_DD_NAME + " generation of this abended run could not be "
                            + "discarded; it reported FILE STATUS outcome " + disposition.name()
                            + ". app/jcl/TRANREPT.jcl:76 declares DISP=(NEW,CATLG,DELETE), so a partial "
                            + "report may remain where the mainframe would leave none; its account and "
                            + "grand totals are not trustworthy");
                }
            } catch (RuntimeException dispositionFailure) {
                reportCleanupFailure(TRANREPT_ABNORMAL_DISPOSITION, dispositionFailure);
            }
        }

        /**
         * The mainline proper, run inside {@link #run(StopSignal)}'s release boundary.
         *
         * @param stopSignal the between-record cancellation probe
         * @return what the run produced
         * @throws AbendException if any paragraph reaches {@code 9999-ABEND-PROGRAM}
         * @throws BatchConfig.StopRequestedException if the step is asked to stop
         */
        private ExecutionSummary runToGoback(StopSignal stopSignal) {
            // :160  DISPLAY 'START OF EXECUTION OF PROGRAM CBTRN03C'.
            sysout.display(START_OF_EXECUTION);

            // :161-166  The six opens, in source order. TRANFILE first, DATEPARM last.
            openTranFile();
            openReptFile();
            openCardXref();
            openTranType();
            openTranCatg();
            openDateParm();

            // :168  PERFORM 0550-DATEPARM-READ.  - which may set END-OF-FILE before the loop begins.
            dateParmRead();

            // :170  PERFORM UNTIL END-OF-FILE = 'Y'
            while (!AT_END_OF_FILE.equals(endOfFile)) {

                // NO COBOL COUNTERPART. The between-record yield to a stop request: a call rather than a
                // condition, so it adds no arm to the translated control flow, and positioned before
                // 1000-TRANFILE-GET-NEXT so a detail line is never abandoned half written. See
                // BatchConfig.StopSignal.
                stopSignal.checkStopRequested();

                // :171  IF END-OF-FILE = 'N'  - redundant: the PERFORM UNTIL condition already
                // establishes it and the flag holds only 'N' or 'Y', so the false path is unreachable.
                // Reproduced rather than removed; a coverage report shows it as a half-taken branch for
                // exactly that reason.
                if (NOT_AT_END_OF_FILE.equals(endOfFile)) {

                    // :172  PERFORM 1000-TRANFILE-GET-NEXT
                    tranFileGetNext();

                    // :173-174  IF TRAN-PROC-TS (1:10) >= WS-START-DATE AND <= WS-END-DATE
                    //   :175       CONTINUE
                    //   :176     ELSE
                    //   :177       NEXT SENTENCE
                    //
                    // DEFECT 1, PRESERVED DELIBERATELY (practice B5). NEXT SENTENCE transfers control
                    // past the period that ends the CURRENT SENTENCE, and that period is the one closing
                    // the whole PERFORM UNTIL ... END-PERFORM at :206 - not the END-IF two lines below.
                    // So an out-of-range record does not skip a record; it ends the entire read loop,
                    // and the page and grand totals at :202-203 are never written. This is a `break`
                    // and must never become a `continue`.
                    //
                    // In practice the SORT step at app/jcl/TRANREPT.jcl:47-48 has already removed every
                    // out-of-range record, so this rarely fires against production input - but an empty
                    // TRANFILE fires it on the first pass, because the untouched record area holds
                    // spaces and spaces sort below any date, which is why an empty input produces an
                    // empty report rather than a header and a pair of zero totals.
                    if (!withinReportingRange(tranRecord.tranProcDt(), startDate, endDate)) {
                        break;
                    }

                    // :179  IF END-OF-FILE = 'N'
                    if (NOT_AT_END_OF_FILE.equals(endOfFile)) {
                        reportOneRecord();
                    } else {
                        // :197  ELSE  - the read reached end of file.
                        writeFinalTotals();
                    }
                }
            }
            // :206  END-PERFORM.

            // :208-213  The six closes, in the SAME order as the opens.
            closeTranFile();
            closeReptFile();
            closeCardXref();
            closeTranType();
            closeTranCatg();
            closeDateParm();

            // :215  DISPLAY 'END OF EXECUTION OF PROGRAM CBTRN03C'.
            sysout.display(END_OF_EXECUTION);

            // :217  GOBACK.  - RETURN-CODE is never moved anywhere in this program, so a clean run
            // returns zero and the step's exit status stays COMPLETED.
            return new ExecutionSummary(AbendException.RETURN_CODE_OK, recordsRead, detailLinesWritten,
                    reportFile.recordsWritten(), lineCounter, grandTotal);
        }


        // -----------------------------------------------------------------------------------------
        // The in-range arm of the loop, app/cbl/CBTRN03C.cbl:180-196.
        // -----------------------------------------------------------------------------------------

        /**
         * Displays the record, handles the account break, enriches it from three keyed files and
         * reports it.
         *
         * <pre>
         * DISPLAY TRAN-RECORD                                          :180
         * IF WS-CURR-CARD-NUM NOT= TRAN-CARD-NUM                       :181
         *   IF WS-FIRST-TIME = 'N'                                     :182
         *     PERFORM 1120-WRITE-ACCOUNT-TOTALS                        :183
         *   END-IF
         *   MOVE TRAN-CARD-NUM TO WS-CURR-CARD-NUM                     :185
         *   MOVE TRAN-CARD-NUM TO FD-XREF-CARD-NUM                     :186
         *   PERFORM 1500-A-LOOKUP-XREF                                 :187
         * END-IF
         * MOVE TRAN-TYPE-CD OF TRAN-RECORD TO FD-TRAN-TYPE             :189
         * PERFORM 1500-B-LOOKUP-TRANTYPE                               :190
         * MOVE TRAN-TYPE-CD OF TRAN-RECORD TO FD-TRAN-TYPE-CD ...      :191-192
         * MOVE TRAN-CAT-CD  OF TRAN-RECORD TO FD-TRAN-CAT-CD  ...      :193-194
         * PERFORM 1500-C-LOOKUP-TRANCATG                               :195
         * PERFORM 1100-WRITE-TRANSACTION-REPORT                        :196
         * </pre>
         *
         * <p><strong>The {@code OF} qualification at {@code :189-194} is not decoration.</strong>
         * {@code TRAN-TYPE-CD} and {@code TRAN-CAT-CD} are declared in {@code app/cpy/CVTRA05Y.cpy}
         * <em>and again</em> in {@code app/cpy/CVTRA04Y.cpy}, so the program has to say which record it
         * means. In Java they are members of two unrelated types, {@link TranRecord} and
         * {@link TranCategoryRecord}, which removes the ambiguity structurally - and the 6-byte
         * {@code TRAN-CAT-KEY} of {@code CVTRA04Y} must never be confused with the 17-byte
         * {@code TRAN-CAT-KEY} of {@code CVTRA01Y}, which shares its name and belongs to a different
         * file this program does not open.
         *
         * <p>The account break reads the cross reference <strong>once per account</strong>, not once per
         * record, which is only correct because the input arrives sorted by card number.
         *
         * @throws AbendException if any of the three lookups finds no record
         */
        private void reportOneRecord() {
            // :180  DISPLAY TRAN-RECORD  - the whole 350-byte group item, untrimmed, FILLER included,
            // with TRAN-AMT shown as its stored zoned image rather than as a formatted number.
            sysout.display(tranRecord.displayImage());

            // :181  IF WS-CURR-CARD-NUM NOT= TRAN-CARD-NUM  - both PIC X(16), so a plain comparison of
            // the two 16-character images is the whole rule.
            if (!currentCardNumber.equals(tranRecord.tranCardNum())) {

                // :182  IF WS-FIRST-TIME = 'N'  - false for the very first record, which is what stops
                // an account total being written for an account that has not been reported yet.
                if (NOT_FIRST_TIME.equals(firstTime)) {
                    // :183  PERFORM 1120-WRITE-ACCOUNT-TOTALS
                    writeAccountTotals();
                }

                // :185  MOVE TRAN-CARD-NUM TO WS-CURR-CARD-NUM
                currentCardNumber = codec.movePicX(tranRecord.tranCardNum(), CARD_NUMBER_KEY_LENGTH);

                // :186  MOVE TRAN-CARD-NUM TO FD-XREF-CARD-NUM  - the FD's RECORD KEY, and the value
                // 1500-A-LOOKUP-XREF displays if the read fails.
                xrefCardNumberKey = codec.movePicX(tranRecord.tranCardNum(), CARD_NUMBER_KEY_LENGTH);

                // :187  PERFORM 1500-A-LOOKUP-XREF
                lookupXref();
            }

            // :189  MOVE TRAN-TYPE-CD OF TRAN-RECORD TO FD-TRAN-TYPE
            tranTypeKey = codec.movePicX(tranRecord.tranTypeCd(), TranTypeRecord.TRAN_TYPE_LENGTH);

            // :190  PERFORM 1500-B-LOOKUP-TRANTYPE
            lookupTranType();

            // :191-192  MOVE TRAN-TYPE-CD OF TRAN-RECORD TO FD-TRAN-TYPE-CD OF FD-TRAN-CAT-KEY
            tranCatTypeCode =
                    codec.movePicX(tranRecord.tranTypeCd(), TranCategoryRecord.TRAN_TYPE_CD_LENGTH);

            // :193-194  MOVE TRAN-CAT-CD OF TRAN-RECORD TO FD-TRAN-CAT-CD OF FD-TRAN-CAT-KEY
            tranCatCode = tranRecord.tranCatCd();

            // :195  PERFORM 1500-C-LOOKUP-TRANCATG
            lookupTranCategory();

            // :196  PERFORM 1100-WRITE-TRANSACTION-REPORT
            writeTransactionReport();
        }

        // -----------------------------------------------------------------------------------------
        // The end-of-file arm of the loop, app/cbl/CBTRN03C.cbl:197-203. Defect 2 lives here.
        // -----------------------------------------------------------------------------------------

        /**
         * Emits the two diagnostic lines, adds the last record's amount a second time, and writes the
         * page and grand totals.
         *
         * <pre>
         * ELSE                                                         :197
         *  DISPLAY 'TRAN-AMT ' TRAN-AMT                                :198
         *  DISPLAY 'WS-PAGE-TOTAL'  WS-PAGE-TOTAL                      :199
         *  ADD TRAN-AMT TO WS-PAGE-TOTAL                               :200
         *                  WS-ACCOUNT-TOTAL                            :201
         *  PERFORM 1110-WRITE-PAGE-TOTALS                              :202
         *  PERFORM 1110-WRITE-GRAND-TOTALS                             :203
         * END-IF                                                       :204
         * </pre>
         *
         * <p><strong>Defect 2, preserved deliberately (practice B5).</strong> This arm runs because the
         * read reported end of file, and a COBOL {@code READ ... INTO} leaves the receiving area
         * untouched at {@code AT END}. {@code TRAN-RECORD} therefore still holds the last successfully
         * read record, whose {@code TRAN-AMT} was already added to both accumulators by
         * {@code 1100-WRITE-TRANSACTION-REPORT}. Adding it again overstates the final page total, and
         * through {@code :297} the grand total, by exactly that amount. Removing the addition would
         * change every report this program has ever produced.
         *
         * <p>The two {@code DISPLAY} lines are the evidence trail that makes the double count visible in
         * {@code SYSOUT}, and they are emitted <em>before</em> the addition: the amount shown is the
         * record's, and the page total shown is the value <em>without</em> the second addition. Both are
         * rendered as stored zoned {@code DISPLAY} images, because that is what a COBOL
         * {@code DISPLAY} of a numeric {@code DISPLAY} item emits.
         *
         * <p><strong>The last account's total is never written.</strong> This arm performs the page and
         * grand totals and not {@code 1120-WRITE-ACCOUNT-TOTALS}, so {@code WS-ACCOUNT-TOTAL} is
         * incremented here and then discarded when the run ends. Preserved for the same reason.
         *
         * @throws AbendException if a write of either total line is refused
         */
        private void writeFinalTotals() {
            // :198  DISPLAY 'TRAN-AMT ' TRAN-AMT
            sysout.display(TRAN_AMT_DISPLAY_LABEL + tranRecord.tranAmtImage());

            // :199  DISPLAY 'WS-PAGE-TOTAL'  WS-PAGE-TOTAL  - no space in the literal, so the label and
            // the eleven characters run together.
            sysout.display(WS_PAGE_TOTAL_DISPLAY_LABEL + zonedTotalImage(pageTotal));

            // :200-201  ADD TRAN-AMT TO WS-PAGE-TOTAL WS-ACCOUNT-TOTAL. One sending operand, evaluated
            // once, added to each receiver in the order written - and this is the second time it has
            // been added to both.
            BigDecimal amount = tranRecord.tranAmt();
            pageTotal = addToTotal(pageTotal, amount);
            accountTotal = addToTotal(accountTotal, amount);

            // :202  PERFORM 1110-WRITE-PAGE-TOTALS   - which also rolls the page total into the grand.
            writePageTotals();

            // :203  PERFORM 1110-WRITE-GRAND-TOTALS
            writeGrandTotals();
        }

        // -----------------------------------------------------------------------------------------
        // 1100-WRITE-TRANSACTION-REPORT - app/cbl/CBTRN03C.cbl:274-290
        // -----------------------------------------------------------------------------------------

        /**
         * Opens the report on the first record, breaks the page when one is due, accumulates the amount
         * and writes the detail line - in that order.
         *
         * <pre>
         * IF WS-FIRST-TIME = 'Y'                                       :275
         *    MOVE 'N' TO WS-FIRST-TIME                                 :276
         *    MOVE WS-START-DATE TO REPT-START-DATE                     :277
         *    MOVE WS-END-DATE TO REPT-END-DATE                         :278
         *    PERFORM 1120-WRITE-HEADERS                                :279
         * END-IF
         * IF FUNCTION MOD(WS-LINE-COUNTER, WS-PAGE-SIZE) = 0           :282
         *    PERFORM 1110-WRITE-PAGE-TOTALS                            :283
         *    PERFORM 1120-WRITE-HEADERS                                :284
         * END-IF
         * ADD TRAN-AMT TO WS-PAGE-TOTAL                                :287
         *                 WS-ACCOUNT-TOTAL                             :288
         * PERFORM 1120-WRITE-DETAIL                                    :289
         * </pre>
         *
         * <p><strong>The two {@code IF}s must stay in this order.</strong> The first-time block writes
         * four header lines and advances the counter to 4, so the second {@code IF} evaluates
         * {@code MOD(4, 20)} and no page break fires on the first record. Swapping them - or hoisting
         * the page test - would evaluate {@code MOD(0, 20)}, which is zero, and would emit a page total
         * of zero and a duplicate set of headers before the very first detail line. The current
         * behaviour is emergent rather than designed, and it is still the behaviour.
         *
         * <p>The dates are moved into the report name header exactly once, on the first record, so every
         * page of a run carries the same range even though the header is re-rendered per page.
         *
         * @throws AbendException if any write is refused
         */
        private void writeTransactionReport() {
            // :275  IF WS-FIRST-TIME = 'Y'
            if (FIRST_TIME.equals(firstTime)) {
                // :276  MOVE 'N' TO WS-FIRST-TIME
                firstTime = NOT_FIRST_TIME;
                // :277  MOVE WS-START-DATE TO REPT-START-DATE
                layouts.moveReptStartDate(startDate);
                // :278  MOVE WS-END-DATE TO REPT-END-DATE
                layouts.moveReptEndDate(endDate);
                // :279  PERFORM 1120-WRITE-HEADERS   - four lines, counter 0 -> 4.
                writeHeaders();
            }

            // :282  IF FUNCTION MOD(WS-LINE-COUNTER, WS-PAGE-SIZE) = 0
            if (isPageBoundary(lineCounter)) {
                // :283  PERFORM 1110-WRITE-PAGE-TOTALS
                writePageTotals();
                // :284  PERFORM 1120-WRITE-HEADERS
                writeHeaders();
            }

            // :287-288  ADD TRAN-AMT TO WS-PAGE-TOTAL WS-ACCOUNT-TOTAL
            BigDecimal amount = tranRecord.tranAmt();
            pageTotal = addToTotal(pageTotal, amount);
            accountTotal = addToTotal(accountTotal, amount);

            // :289  PERFORM 1120-WRITE-DETAIL
            writeDetail();
        }

        // -----------------------------------------------------------------------------------------
        // 1110-WRITE-PAGE-TOTALS - app/cbl/CBTRN03C.cbl:293-304
        // -----------------------------------------------------------------------------------------

        /**
         * Writes the page total, rolls it into the grand total, zeroes it, and rules off the page.
         *
         * <pre>
         * MOVE WS-PAGE-TOTAL TO REPT-PAGE-TOTAL                        :294
         * MOVE REPORT-PAGE-TOTALS TO FD-REPTFILE-REC                   :295
         * PERFORM 1111-WRITE-REPORT-REC                                :296
         * ADD WS-PAGE-TOTAL TO WS-GRAND-TOTAL                          :297
         * MOVE 0 TO WS-PAGE-TOTAL                                      :298
         * ADD 1 TO WS-LINE-COUNTER                                     :299
         * MOVE TRANSACTION-HEADER-2 TO FD-REPTFILE-REC                 :300
         * PERFORM 1111-WRITE-REPORT-REC                                :301
         * ADD 1 TO WS-LINE-COUNTER                                     :302
         * </pre>
         *
         * <p><strong>The side-effect order is the contract.</strong> The roll-up at {@code :297} happens
         * <em>after</em> the line has been written and <em>before</em> the accumulator is cleared, so the
         * value printed and the value added to the grand total are necessarily the same. The two counter
         * increments each follow their own write.
         *
         * <p>{@code :297} is the <strong>only</strong> place the grand total grows.
         * {@code 1120-WRITE-ACCOUNT-TOTALS} has no counterpart, which is why the grand total is the sum
         * of the page totals and not of the account totals.
         *
         * @throws AbendException if either write is refused
         */
        private void writePageTotals() {
            // :294  MOVE WS-PAGE-TOTAL TO REPT-PAGE-TOTAL  - into the +ZZZ,ZZZ,ZZZ.ZZ edited field.
            layouts.moveReptPageTotal(pageTotal);
            // :295-296  MOVE REPORT-PAGE-TOTALS TO FD-REPTFILE-REC, then write it.
            writeReportRec(layouts.renderReportPageTotals());
            // :297  ADD WS-PAGE-TOTAL TO WS-GRAND-TOTAL
            grandTotal = addToTotal(grandTotal, pageTotal);
            // :298  MOVE 0 TO WS-PAGE-TOTAL
            pageTotal = CobolDecimal.monetaryZero();
            // :299  ADD 1 TO WS-LINE-COUNTER
            lineCounter++;
            // :300-301  MOVE TRANSACTION-HEADER-2 TO FD-REPTFILE-REC, then write it.
            writeReportRec(layouts.renderTransactionHeader2());
            // :302  ADD 1 TO WS-LINE-COUNTER
            lineCounter++;
        }

        // -----------------------------------------------------------------------------------------
        // 1120-WRITE-ACCOUNT-TOTALS - app/cbl/CBTRN03C.cbl:306-316
        // -----------------------------------------------------------------------------------------

        /**
         * Writes the account total, zeroes it, and rules off - and accumulates it nowhere.
         *
         * <pre>
         * MOVE WS-ACCOUNT-TOTAL   TO REPT-ACCOUNT-TOTAL                :307
         * MOVE REPORT-ACCOUNT-TOTALS TO FD-REPTFILE-REC                :308
         * PERFORM 1111-WRITE-REPORT-REC                                :309
         * MOVE 0 TO WS-ACCOUNT-TOTAL                                   :310
         * ADD 1 TO WS-LINE-COUNTER                                     :311
         * MOVE TRANSACTION-HEADER-2 TO FD-REPTFILE-REC                 :312
         * PERFORM 1111-WRITE-REPORT-REC                                :313
         * ADD 1 TO WS-LINE-COUNTER                                     :314
         * </pre>
         *
         * <p><strong>The absence of a roll-up is deliberate and must not be "corrected".</strong> The
         * paragraph is otherwise line for line the same as {@code 1110-WRITE-PAGE-TOTALS}, and the one
         * statement it lacks is that paragraph's {@code :297}. Adding an
         * {@code ADD WS-ACCOUNT-TOTAL TO WS-GRAND-TOTAL} here would roughly double the grand total of
         * every report, and it would be a business-rule change of exactly the kind this migration
         * forbids.
         *
         * <p>It also differs in <em>when</em> it runs: on a change of card number, before the new
         * account's first detail line, and never at end of file - so the final account's total is
         * accumulated and then dropped.
         *
         * @throws AbendException if either write is refused
         */
        private void writeAccountTotals() {
            // :307  MOVE WS-ACCOUNT-TOTAL TO REPT-ACCOUNT-TOTAL
            layouts.moveReptAccountTotal(accountTotal);
            // :308-309  MOVE REPORT-ACCOUNT-TOTALS TO FD-REPTFILE-REC, then write it.
            writeReportRec(layouts.renderReportAccountTotals());
            // :310  MOVE 0 TO WS-ACCOUNT-TOTAL   - and no ADD to WS-GRAND-TOTAL. See above.
            accountTotal = CobolDecimal.monetaryZero();
            // :311  ADD 1 TO WS-LINE-COUNTER
            lineCounter++;
            // :312-313  MOVE TRANSACTION-HEADER-2 TO FD-REPTFILE-REC, then write it.
            writeReportRec(layouts.renderTransactionHeader2());
            // :314  ADD 1 TO WS-LINE-COUNTER
            lineCounter++;
        }

        // -----------------------------------------------------------------------------------------
        // 1110-WRITE-GRAND-TOTALS - app/cbl/CBTRN03C.cbl:318-322
        // -----------------------------------------------------------------------------------------

        /**
         * Writes the grand total line, and nothing else.
         *
         * <pre>
         * MOVE WS-GRAND-TOTAL TO REPT-GRAND-TOTAL                      :319
         * MOVE REPORT-GRAND-TOTALS TO FD-REPTFILE-REC                  :320
         * PERFORM 1111-WRITE-REPORT-REC                                :321
         * </pre>
         *
         * <p><strong>No {@code ADD 1 TO WS-LINE-COUNTER}.</strong> The other four writing paragraphs all
         * increment after each write; this one does not, and it does not need to - it is the last line of
         * the report and nothing consults the counter afterwards. The asymmetry is transcribed rather
         * than tidied, so the final counter value a parity case observes is the one the program leaves.
         *
         * <p>Note also the paragraph's name: {@code 1110-WRITE-GRAND-TOTALS} duplicates the {@code 1110}
         * prefix of {@code 1110-WRITE-PAGE-TOTALS}, and {@code 1120-WRITE-ACCOUNT-TOTALS} collides with
         * {@code 1120-WRITE-HEADERS} and {@code 1120-WRITE-DETAIL}. COBOL paragraph names need not be
         * ordered, so this is legal; it is recorded here because the numbering cannot be used to infer
         * execution order.
         *
         * @throws AbendException if the write is refused
         */
        private void writeGrandTotals() {
            // :319  MOVE WS-GRAND-TOTAL TO REPT-GRAND-TOTAL
            layouts.moveReptGrandTotal(grandTotal);
            // :320-321  MOVE REPORT-GRAND-TOTALS TO FD-REPTFILE-REC, then write it. No increment.
            writeReportRec(layouts.renderReportGrandTotals());
        }

        // -----------------------------------------------------------------------------------------
        // 1120-WRITE-HEADERS - app/cbl/CBTRN03C.cbl:324-341
        // -----------------------------------------------------------------------------------------

        /**
         * Writes the four-line page heading, incrementing the counter after each line.
         *
         * <pre>
         * MOVE REPORT-NAME-HEADER TO FD-REPTFILE-REC / write / ADD 1   :325-327
         * MOVE WS-BLANK-LINE TO FD-REPTFILE-REC      / write / ADD 1   :329-331
         * MOVE TRANSACTION-HEADER-1 TO FD-REPTFILE-REC / write / ADD 1 :333-335
         * MOVE TRANSACTION-HEADER-2 TO FD-REPTFILE-REC / write / ADD 1 :337-339
         * </pre>
         *
         * <p>Exactly {@value TransactionReportJob#HEADER_LINES} lines, so the counter advances by four
         * every time this runs - which is what makes the first page break fall where it does.
         *
         * <p>{@code WS-BLANK-LINE} is {@code PIC X(133) VALUE SPACES} ({@code :133}), a full-width item
         * rather than a short one the writer pads, so it is taken from
         * {@link TranReportWriter#WS_BLANK_LINE_IMAGE} rather than assembled here.
         *
         * @throws AbendException if any of the four writes is refused
         */
        private void writeHeaders() {
            // :325-327
            writeReportRec(layouts.renderReportNameHeader());
            lineCounter++;
            // :329-331
            writeReportRec(TranReportWriter.WS_BLANK_LINE_IMAGE);
            lineCounter++;
            // :333-335
            writeReportRec(layouts.renderTransactionHeader1());
            lineCounter++;
            // :337-339
            writeReportRec(layouts.renderTransactionHeader2());
            lineCounter++;
        }

        // -----------------------------------------------------------------------------------------
        // 1120-WRITE-DETAIL - app/cbl/CBTRN03C.cbl:361-374
        // -----------------------------------------------------------------------------------------

        /**
         * Builds and writes one detail line: an {@code INITIALIZE} and eight moves, in source order.
         *
         * <pre>
         * INITIALIZE TRANSACTION-DETAIL-REPORT                         :362
         * MOVE TRAN-ID TO TRAN-REPORT-TRANS-ID                         :363
         * MOVE XREF-ACCT-ID TO TRAN-REPORT-ACCOUNT-ID                  :364
         * MOVE TRAN-TYPE-CD OF TRAN-RECORD TO TRAN-REPORT-TYPE-CD      :365
         * MOVE TRAN-TYPE-DESC TO TRAN-REPORT-TYPE-DESC                 :366
         * MOVE TRAN-CAT-CD OF TRAN-RECORD TO TRAN-REPORT-CAT-CD        :367
         * MOVE TRAN-CAT-TYPE-DESC TO TRAN-REPORT-CAT-DESC              :368
         * MOVE TRAN-SOURCE TO TRAN-REPORT-SOURCE                       :369
         * MOVE TRAN-AMT TO TRAN-REPORT-AMT                             :370
         * MOVE TRANSACTION-DETAIL-REPORT TO FD-REPTFILE-REC            :371
         * PERFORM 1111-WRITE-REPORT-REC                                :372
         * ADD 1 TO WS-LINE-COUNTER                                     :373
         * </pre>
         *
         * <p><strong>{@code INITIALIZE} skips {@code FILLER}.</strong> With no {@code REPLACING} phrase
         * it moves {@code SPACE} into alphanumeric items and {@code ZERO} into numeric and
         * numeric-edited ones, and an item with an implicit {@code FILLER} clause is not a receiving
         * operand at all. So the {@code '-'} separators that {@code app/cpy/CVTRA07Y.cpy} declares at the
         * type-code and category-code boundaries survive every line, and so do the six space fillers.
         * {@link TranReportLayouts#initializeTransactionDetailReport()} implements that rule
         * specifically, rather than the {@code VALUE}-clause convention that would rewrite the fillers.
         *
         * <p><strong>Two truncations follow from the pictures</strong>, and neither is an error:
         * {@code TRAN-TYPE-DESC} is {@code PIC X(50)} moving into a {@code PIC X(15)} receiver, and
         * {@code TRAN-CAT-TYPE-DESC} is {@code PIC X(50)} moving into a {@code PIC X(29)} one. Both keep
         * their leftmost characters, because an alphanumeric move truncates on the right. Both
         * truncations are performed inside {@link TranReportLayouts}, at the width its receiver declares,
         * rather than by a substring written here.
         *
         * @throws AbendException if the write is refused
         */
        private void writeDetail() {
            // :362  INITIALIZE TRANSACTION-DETAIL-REPORT
            layouts.initializeTransactionDetailReport();
            // :363  MOVE TRAN-ID TO TRAN-REPORT-TRANS-ID              X(16) -> X(16)
            layouts.moveTranReportTransId(tranRecord.tranId());
            // :364  MOVE XREF-ACCT-ID TO TRAN-REPORT-ACCOUNT-ID       9(11) -> X(11)
            layouts.moveTranReportAccountId(xrefRecord.xrefAcctId());
            // :365  MOVE TRAN-TYPE-CD OF TRAN-RECORD TO ...           X(02) -> X(02)
            layouts.moveTranReportTypeCd(tranRecord.tranTypeCd());
            // :366  MOVE TRAN-TYPE-DESC TO TRAN-REPORT-TYPE-DESC      X(50) -> X(15), right-truncated
            layouts.moveTranReportTypeDesc(tranTypeRecord.tranTypeDesc());
            // :367  MOVE TRAN-CAT-CD OF TRAN-RECORD TO ...            9(04) -> 9(04); the sender's own
            // digit image is passed, so the move is a character move and cannot re-derive the value.
            layouts.moveTranReportCatCd(tranRecord.tranCatCdImage());
            // :368  MOVE TRAN-CAT-TYPE-DESC TO TRAN-REPORT-CAT-DESC   X(50) -> X(29), right-truncated
            layouts.moveTranReportCatDesc(tranCategoryRecord.tranCatTypeDesc());
            // :369  MOVE TRAN-SOURCE TO TRAN-REPORT-SOURCE            X(10) -> X(10)
            layouts.moveTranReportSource(tranRecord.tranSource());
            // :370  MOVE TRAN-AMT TO TRAN-REPORT-AMT                  S9(09)V99 -> -ZZZ,ZZZ,ZZZ.ZZ
            layouts.moveTranReportAmt(tranRecord.tranAmt());
            // :371-372  MOVE TRANSACTION-DETAIL-REPORT TO FD-REPTFILE-REC, then write it.
            writeReportRec(layouts.renderTransactionDetailReport());
            // :373  ADD 1 TO WS-LINE-COUNTER
            lineCounter++;
            detailLinesWritten++;
        }

        // -----------------------------------------------------------------------------------------
        // 1111-WRITE-REPORT-REC - app/cbl/CBTRN03C.cbl:343-359
        // -----------------------------------------------------------------------------------------

        /**
         * Moves a rendered layout into {@code FD-REPTFILE-REC}, writes it, and runs the write ladder.
         *
         * <pre>
         * WRITE FD-REPTFILE-REC                                        :345
         * IF TRANREPT-STATUS = '00'  MOVE 0 TO APPL-RESULT              :346-347
         * ELSE                       MOVE 12 TO APPL-RESULT             :348-349
         * IF APPL-AOK  CONTINUE                                         :351-352
         * ELSE  DISPLAY 'ERROR WRITING REPTFILE'                        :354
         *       MOVE TRANREPT-STATUS TO IO-STATUS                       :355
         *       PERFORM 9910-DISPLAY-IO-STATUS                          :356
         *       PERFORM 9999-ABEND-PROGRAM                              :357
         * </pre>
         *
         * <p>A two-way ladder, unlike the reads: a write has no end-of-file outcome, so it moves
         * {@code 0} or {@code 12} and never {@code 16}, and {@code APPL-EOF} is never tested here.
         *
         * <p>The move and the write are fused through {@link TranReportWriter.ReportFile#writeLine} -
         * which is exactly the pair the COBOL performs at every one of this paragraph's seven call sites -
         * and the width normalisation to {@value TranReportWriter#RECORD_LENGTH} bytes happens inside it,
         * because that is the writer's decision and not this job's.
         *
         * @param layoutImage the rendered layout at its natural width; never {@code null}
         * @throws AbendException if the sink refuses the record
         */
        private void writeReportRec(String layoutImage) {
            // :345  WRITE FD-REPTFILE-REC   - preceded by the MOVE its caller performs.
            FileStatus.Outcome outcome = reportFile.writeLine(layoutImage);

            // :346-349  the '00'-or-12 ladder.
            int applResult = outcome == FileStatus.Outcome.OK
                    ? APPL_RESULT_AOK
                    : APPL_RESULT_FATAL;

            // :351  IF APPL-AOK CONTINUE ELSE ...
            if (!applAok(applResult)) {
                String status = statusOf(outcome);
                // :354-356  DISPLAY 'ERROR WRITING REPTFILE', then 9910-DISPLAY-IO-STATUS.
                reportIoFailure(sysout, ERROR_WRITING_REPTFILE, status);
                // :357  PERFORM 9999-ABEND-PROGRAM - which does not return.
                throw abendProgram(sysout, ERROR_WRITING_REPTFILE, status);
            }
        }


        // -----------------------------------------------------------------------------------------
        // 0550-DATEPARM-READ - app/cbl/CBTRN03C.cbl:220-243
        // -----------------------------------------------------------------------------------------

        /**
         * Reads the reporting date range, once, before the loop.
         *
         * <pre>
         * READ DATE-PARMS-FILE INTO WS-DATEPARM-RECORD                 :221
         * EVALUATE DATEPARM-STATUS                                     :222
         *   WHEN '00'    MOVE 0 TO APPL-RESULT                         :223-224
         *   WHEN '10'    MOVE 16 TO APPL-RESULT                        :225-226
         *   WHEN OTHER   MOVE 12 TO APPL-RESULT                        :227-228
         * END-EVALUATE
         * IF APPL-AOK                                                  :231
         *    DISPLAY 'Reporting from ' WS-START-DATE ' to ' WS-END-DATE :232-233
         * ELSE
         *    IF APPL-EOF   MOVE 'Y' TO END-OF-FILE                     :235-236
         *    ELSE          DISPLAY 'ERROR READING DATEPARM FILE'       :238
         *                  MOVE DATEPARM-STATUS TO IO-STATUS           :239
         *                  PERFORM 9910-DISPLAY-IO-STATUS              :240
         *                  PERFORM 9999-ABEND-PROGRAM                  :241
         * </pre>
         *
         * <p>The {@code EVALUATE} arms are written in source order with {@code WHEN OTHER} last (gate
         * G30), and the ladder is kept separate from the {@code APPL-RESULT} test that follows it,
         * exactly as the COBOL keeps them: a status of {@code '22'} or {@code '23'} is neither of the two
         * the program names, so it takes the third arm and abends.
         *
         * <p><strong>An empty {@value TransactionReportJob#DATEPARM_DD_NAME} produces an empty
         * report.</strong> The {@code '10'} arm sets {@code END-OF-FILE} to {@code 'Y'} before the loop
         * has run once, so no record is read, no header is written and no total is produced - only the
         * two banner lines and whatever the closes emit. Not an error: the program does not abend, and
         * {@code RETURN-CODE} stays zero.
         *
         * <p>{@code WS-START-DATE} and {@code WS-END-DATE} are the first ten and the last ten bytes of a
         * 21-byte prefix of the 80-byte record, separated by one byte the program never reads. This job
         * owns the {@code 'Reporting from '} line and the failure texts; {@link DateParmReader} only
         * reports the outcome and decodes the three fields.
         *
         * @throws AbendException if the read reports any other status
         */
        private void dateParmRead() {
            // :221  READ DATE-PARMS-FILE INTO WS-DATEPARM-RECORD.
            DateParmReader.ReadResult read = dateParmReader.read();

            // :222-229  EVALUATE DATEPARM-STATUS - '00', then '10', then WHEN OTHER.
            int applResult;
            if (FileStatus.isOk(read.status())) {
                // :224  MOVE 0 TO APPL-RESULT
                applResult = APPL_RESULT_AOK;
            } else if (FileStatus.isEndOfFile(read.status())) {
                // :226  MOVE 16 TO APPL-RESULT
                applResult = APPL_RESULT_EOF;
            } else {
                // :228  MOVE 12 TO APPL-RESULT
                applResult = APPL_RESULT_FATAL;
            }

            // :231  IF APPL-AOK
            if (applAok(applResult)) {
                DateParmReader.DateParm range = read.dateParm().orElseThrow(() ->
                        new IllegalStateException("A read of DD " + DATEPARM_DD_NAME + " reported file "
                                + "status " + FileStatus.toStatusImage(read.status()) + " and carried no "
                                + "range. The two are contradictory: a successful READ leaves "
                                + "WS-DATEPARM-RECORD populated, which is what " + PROGRAM_NAME
                                + ":232 displays and what every range test afterwards reads."));
                startDate = range.startDate();
                endDate = range.endDate();
                // :232-233  DISPLAY 'Reporting from ' WS-START-DATE ' to ' WS-END-DATE
                sysout.display(REPORTING_FROM + startDate + REPORTING_TO + endDate);
                return;
            }

            // :235  IF APPL-EOF  ->  :236  MOVE 'Y' TO END-OF-FILE
            if (applEof(applResult)) {
                endOfFile = AT_END_OF_FILE;
                return;
            }

            // :238-240  DISPLAY 'ERROR READING DATEPARM FILE', then 9910-DISPLAY-IO-STATUS.
            reportIoFailure(sysout, ERROR_READING_DATEPARM_FILE, read.status());
            // :241  PERFORM 9999-ABEND-PROGRAM - which does not return.
            throw abendProgram(sysout, ERROR_READING_DATEPARM_FILE, read.status());
        }

        // -----------------------------------------------------------------------------------------
        // 1000-TRANFILE-GET-NEXT - app/cbl/CBTRN03C.cbl:248-272
        // -----------------------------------------------------------------------------------------

        /**
         * Reads the next transaction record from the sorted daily file.
         *
         * <pre>
         * READ TRANSACT-FILE INTO TRAN-RECORD.                         :249
         * EVALUATE TRANFILE-STATUS                                     :251
         *   WHEN '00'    MOVE 0 TO APPL-RESULT                         :252-253
         *   WHEN '10'    MOVE 16 TO APPL-RESULT                        :254-255
         *   WHEN OTHER   MOVE 12 TO APPL-RESULT                        :256-257
         * END-EVALUATE
         * IF APPL-AOK  CONTINUE                                        :260-261
         * ELSE
         *   IF APPL-EOF  MOVE 'Y' TO END-OF-FILE                       :263-264
         *   ELSE         DISPLAY 'ERROR READING TRANSACTION FILE'      :266
         *                MOVE TRANFILE-STATUS TO IO-STATUS             :267
         *                PERFORM 9910-DISPLAY-IO-STATUS                :268
         *                PERFORM 9999-ABEND-PROGRAM                    :269
         * </pre>
         *
         * <p><strong>At end of file the record area is left exactly as it was.</strong> A COBOL
         * {@code READ ... INTO} does not touch its receiving item on the {@code AT END} path, so
         * {@code TRAN-RECORD} still holds the last record that was read. Two pieces of observable
         * behaviour depend on that and neither is incidental: the range test at {@code :173} evaluates
         * against the last record rather than against a cleared area, and defect 2 adds that record's
         * amount a second time. The Java form therefore replaces the record reference only on the
         * success arm.
         *
         * <p>Unlike the sibling reader programs, this paragraph displays nothing on success. The record
         * is displayed by the mainline at {@code :180}, once, and only when it is in range.
         *
         * @throws AbendException if the read reports any status but {@code '00'} or {@code '10'}
         */
        private void tranFileGetNext() {
            // :249  READ TRANSACT-FILE INTO TRAN-RECORD.
            TransactionRepository.ReadResult read = tranFile.readNext();
            String status = read.status();

            // :251-258  EVALUATE TRANFILE-STATUS - '00', then '10', then WHEN OTHER.
            int applResult;
            if (FileStatus.isOk(status)) {
                // :253  MOVE 0 TO APPL-RESULT
                applResult = APPL_RESULT_AOK;
                tranRecord = read.record().orElseThrow(() -> new IllegalStateException("A read of DD "
                        + TRANFILE_DD_NAME + " reported file status " + FileStatus.toStatusImage(status)
                        + " and carried no record. The two are contradictory: a successful READ leaves "
                        + "TRAN-RECORD populated, which is what " + PROGRAM_NAME + ":180 displays."));
                recordsRead++;
            } else if (FileStatus.isEndOfFile(status)) {
                // :255  MOVE 16 TO APPL-RESULT   - and TRAN-RECORD keeps the previous record.
                applResult = APPL_RESULT_EOF;
            } else {
                // :257  MOVE 12 TO APPL-RESULT
                applResult = APPL_RESULT_FATAL;
            }

            // :260  IF APPL-AOK CONTINUE
            if (applAok(applResult)) {
                return;
            }

            // :263  IF APPL-EOF  ->  :264  MOVE 'Y' TO END-OF-FILE
            if (applEof(applResult)) {
                endOfFile = AT_END_OF_FILE;
                return;
            }

            // :266-268  DISPLAY 'ERROR READING TRANSACTION FILE', then 9910-DISPLAY-IO-STATUS.
            reportIoFailure(sysout, ERROR_READING_TRANSACTION_FILE, status);
            // :269  PERFORM 9999-ABEND-PROGRAM - which does not return.
            throw abendProgram(sysout, ERROR_READING_TRANSACTION_FILE, status);
        }

        // -----------------------------------------------------------------------------------------
        // The three keyed lookups - app/cbl/CBTRN03C.cbl:484-512. Each is an INVALID KEY arm and
        // nothing else: there is no NOT INVALID KEY branch and no fallback value anywhere.
        //
        // WHICH FAILURES REACH THAT ARM, AND WHICH DO NOT. All three files declare a FILE STATUS
        // (:33-49), and the INVALID KEY phrase of a COBOL READ runs for the invalid-key condition
        // ALONE - a keyed read whose key matches no record, status '23'. A read that fails for any
        // other reason sets the FILE STATUS item and, with no USE AFTER ERROR declarative anywhere in
        // this program, execution passes the END-READ without executing the imperative at all. So the
        // DISPLAY, the MOVE 23 TO IO-STATUS and the 9910/9999 PERFORMs are the invalid-key arm's
        // statements and no other outcome's.
        //
        // Branching on "did it find a record" instead of "was it the invalid-key condition" is
        // therefore wrong three times over on a backend refusal or a malformed row: it emits an
        // 'INVALID CARD NUMBER : <key>' line the program never wrote, it renders
        // FILE STATUS IS: NNNN0023 in place of the status that actually failed - the one line an
        // operator needs - and it ENDS THE RUN where the program continues.
        //
        // That last one is the substantive difference. Each of these three files declares a FILE STATUS
        // item in its SELECT (:6-22) and the program has no USE AFTER ERROR declarative, so a failure
        // the INVALID KEY phrase does not cover sets that item and passes control to the statement after
        // the END-READ - here the paragraph's own EXIT. The report then composes its detail line from a
        // record area the read did not populate, which is to say from whatever the area already held.
        // Each lookup accordingly separates the two conditions: the invalid-key arm abends, and every
        // other failure is logged through noteReadFailedOtherThanInvalidKey and returns with the area
        // untouched. The areas are constructed rather than left null precisely so that "untouched" is a
        // real image and not a null reference.
        // -----------------------------------------------------------------------------------------

        /**
         * {@code 1500-A-LOOKUP-XREF} - resolves the account identifier for the detail line.
         *
         * <pre>
         * READ XREF-FILE INTO CARD-XREF-RECORD                         :485
         *    INVALID KEY
         *       DISPLAY 'INVALID CARD NUMBER : '  FD-XREF-CARD-NUM      :487
         *       MOVE 23 TO IO-STATUS                                    :488
         *       PERFORM 9910-DISPLAY-IO-STATUS                          :489
         *       PERFORM 9999-ABEND-PROGRAM                              :490
         * END-READ                                                      :491
         * </pre>
         *
         * <p><strong>A missing cross-reference row abends the report.</strong> There is no default
         * account identifier and no skip-this-record arm, which is what makes the transaction file and
         * the cross reference a referential pair as far as this program is concerned. The repository
         * reports the absence as {@link FileStatus.Outcome#NOT_FOUND}; the decision to abend, and the
         * text displayed, belong here.
         *
         * <p>{@code MOVE 23 TO IO-STATUS} is <em>not</em> the status the read reported - inside the
         * {@code INVALID KEY} arm the program overwrites it with the literal {@code 23} - so the
         * rendered line on that arm is always {@code FILE STATUS IS: NNNN0023}. Transcribed as written.
         * It applies to the invalid-key condition only; a read that failed for another reason never
         * reaches this arm and reports its own status instead.
         *
         * @throws AbendException on the {@code INVALID KEY} condition alone. A read that fails for any
         *                        other reason does not abend: the {@code FILE STATUS} item is set,
         *                        control passes the {@code END-READ}, and the record area is left
         *                        unchanged for the report to compose its line from
         */
        private void lookupXref() {
            // :485  READ XREF-FILE INTO CARD-XREF-RECORD  - keyed by FD-XREF-CARD-NUM.
            CardXrefRepository.ReadResult read = cardXrefRepository.readByCardNumber(xrefCardNumberKey);
            if (read.isFound()) {
                xrefRecord = read.record().orElseThrow(() -> new IllegalStateException("A keyed read of "
                        + "DD " + CARDXREF_DD_NAME + " reported success and carried no record, which "
                        + "are contradictory: " + PROGRAM_NAME + ":364 moves XREF-ACCT-ID out of the "
                        + "area the read populates."));
                return;
            }
            if (!read.isNotFound()) {
                // Not the INVALID KEY condition, so :487-489 do not run at all. The FILE STATUS item
                // is set, control passes the END-READ, and the paragraph exits with the record area
                // UNCHANGED - which the report then reads, exactly as the source does.
                noteReadFailedOtherThanInvalidKey(CARDXREF_DD_NAME, read.status());
                return;
            }
            // :487  DISPLAY 'INVALID CARD NUMBER : '  FD-XREF-CARD-NUM
            // :488-489  MOVE 23 TO IO-STATUS, then 9910-DISPLAY-IO-STATUS.
            reportIoFailure(sysout, INVALID_CARD_NUMBER + xrefCardNumberKey, IO_STATUS_INVALID_KEY);
            // :490  PERFORM 9999-ABEND-PROGRAM - which does not return.
            throw abendProgram(sysout, INVALID_CARD_NUMBER + xrefCardNumberKey, IO_STATUS_INVALID_KEY);
        }

        /**
         * {@code 1500-B-LOOKUP-TRANTYPE} - resolves {@code TRAN-TYPE-DESC} for the detail line.
         *
         * <pre>
         * READ TRANTYPE-FILE INTO TRAN-TYPE-RECORD                     :495
         *    INVALID KEY
         *       DISPLAY 'INVALID TRANSACTION TYPE : '  FD-TRAN-TYPE     :497
         *       MOVE 23 TO IO-STATUS                                    :498
         *       PERFORM 9910-DISPLAY-IO-STATUS                          :499
         *       PERFORM 9999-ABEND-PROGRAM                              :500
         * END-READ                                                      :501
         * </pre>
         *
         * <p>Read once per detail line, not once per account: two records of the same card may carry
         * different type codes.
         *
         * @throws AbendException on the {@code INVALID KEY} condition alone. A read that fails for any
         *                        other reason does not abend: the {@code FILE STATUS} item is set,
         *                        control passes the {@code END-READ}, and the record area is left
         *                        unchanged for the report to compose its line from
         */
        private void lookupTranType() {
            // :495  READ TRANTYPE-FILE INTO TRAN-TYPE-RECORD  - keyed by FD-TRAN-TYPE.
            TranTypeRepository.ReadResult read = tranTypeRepository.readByTranType(tranTypeKey);
            if (read.isFound()) {
                tranTypeRecord = read.record().orElseThrow(() -> new IllegalStateException("A keyed "
                        + "read of DD " + TRANTYPE_DD_NAME + " reported success and carried no record, "
                        + "which are contradictory: " + PROGRAM_NAME + ":366 moves TRAN-TYPE-DESC out "
                        + "of the area the read populates."));
                return;
            }
            if (!read.isNotFound()) {
                // Not the INVALID KEY condition, so :497-499 do not run at all.
                // Not the INVALID KEY condition, so :497-499 do not run at all. The FILE STATUS item
                // is set, control passes the END-READ, and the paragraph exits with the record area
                // UNCHANGED - which the report then reads, exactly as the source does.
                noteReadFailedOtherThanInvalidKey(TRANTYPE_DD_NAME, read.status());
                return;
            }
            // :497-499  DISPLAY 'INVALID TRANSACTION TYPE : ' with the 2-byte key, MOVE 23 TO IO-STATUS,
            // then 9910-DISPLAY-IO-STATUS. The repository echoes the key image the read actually used.
            String displayed = INVALID_TRANSACTION_TYPE + read.keyImage();
            reportIoFailure(sysout, displayed, IO_STATUS_INVALID_KEY);
            // :500  PERFORM 9999-ABEND-PROGRAM - which does not return.
            throw abendProgram(sysout, displayed, IO_STATUS_INVALID_KEY);
        }

        /**
         * {@code 1500-C-LOOKUP-TRANCATG} - resolves {@code TRAN-CAT-TYPE-DESC} for the detail line.
         *
         * <pre>
         * READ TRANCATG-FILE INTO TRAN-CAT-RECORD                      :505
         *    INVALID KEY
         *       DISPLAY 'INVALID TRAN CATG KEY : '  FD-TRAN-CAT-KEY     :507
         *       MOVE 23 TO IO-STATUS                                    :508
         *       PERFORM 9910-DISPLAY-IO-STATUS                          :509
         *       PERFORM 9999-ABEND-PROGRAM                              :510
         * END-READ                                                      :511
         * </pre>
         *
         * <p>The displayed key is the whole 6-byte {@code FD-TRAN-CAT-KEY} group - the 2-character type
         * code followed by the 4-digit category code, with no separator - and not either half on its own.
         * It is rendered by {@link TranCategoryRepository#keyImage(String, int)} so the composition
         * happens in one place and cannot drift from the key the read used.
         *
         * @throws AbendException on the {@code INVALID KEY} condition alone. A read that fails for any
         *                        other reason does not abend: the {@code FILE STATUS} item is set,
         *                        control passes the {@code END-READ}, and the record area is left
         *                        unchanged for the report to compose its line from
         */
        private void lookupTranCategory() {
            // :505  READ TRANCATG-FILE INTO TRAN-CAT-RECORD  - keyed by FD-TRAN-CAT-KEY.
            TranCategoryRepository.ReadResult read =
                    tranCategoryRepository.readByKey(tranCatTypeCode, tranCatCode);
            if (read.isFound()) {
                tranCategoryRecord = read.record().orElseThrow(() -> new IllegalStateException("A keyed "
                        + "read of DD " + TRANCATG_DD_NAME + " reported success and carried no record, "
                        + "which are contradictory: " + PROGRAM_NAME + ":368 moves TRAN-CAT-TYPE-DESC "
                        + "out of the area the read populates."));
                return;
            }
            if (!read.isNotFound()) {
                // Not the INVALID KEY condition, so :507-509 do not run at all.
                // Not the INVALID KEY condition, so :507-509 do not run at all. The FILE STATUS item
                // is set, control passes the END-READ, and the paragraph exits with the record area
                // UNCHANGED - which the report then reads, exactly as the source does.
                noteReadFailedOtherThanInvalidKey(TRANCATG_DD_NAME, read.status());
                return;
            }
            // :507-509  DISPLAY 'INVALID TRAN CATG KEY : ' with the 6-byte key, MOVE 23 TO IO-STATUS,
            // then 9910-DISPLAY-IO-STATUS.
            String displayed = INVALID_TRAN_CATG_KEY
                    + tranCategoryRepository.keyImage(tranCatTypeCode, tranCatCode);
            reportIoFailure(sysout, displayed, IO_STATUS_INVALID_KEY);
            // :510  PERFORM 9999-ABEND-PROGRAM - which does not return.
            throw abendProgram(sysout, displayed, IO_STATUS_INVALID_KEY);
        }

        /**
         * The outcome of a keyed lookup that delivered no record and was <em>not</em> the
         * {@code INVALID KEY} condition - a backend refusal, a row that carries no record image, or a
         * row whose width is not its copybook's.
         *
         * <p><strong>The {@code INVALID KEY} imperative does not run, and that is the point.</strong>
         * All three of these files declare a {@code FILE STATUS}
         * ({@code app/cbl/CBTRN03C.cbl:33-49}), and the {@code INVALID KEY} phrase of a COBOL
         * {@code READ} covers the invalid-key condition alone. So none of the three statements inside
         * that phrase belongs to this outcome: not the {@code DISPLAY} naming the key as invalid - the
         * key is not invalid, the dataset could not be read - and not {@code MOVE 23 TO IO-STATUS},
         * which would render {@code FILE STATUS IS: NNNN0023} and discard the status that actually
         * failed. The read's own status is carried into the abend reason instead, so the one fact an
         * operator needs survives.
         *
         * <p>What <em>does</em> happen is the abend. {@code 9999-ABEND-PROGRAM} displays
         * {@code 'ABENDING PROGRAM'} and nothing else, so no line this program never wrote reaches
         * {@code SYSOUT}. Continuing past the failed read instead - which is what the COBOL runtime
         * would do with a declared {@code FILE STATUS} and no {@code USE AFTER ERROR} declarative -
         * would leave the record area holding the previous detail line's values and compose a report
         * line out of them, silently. A report is read as fact; an abend that names the dataset and the
         * status is the only outcome here that cannot mislead.
         *
         * <p>The log line names the DD and the status and carries no record content: the transaction
         * detail report is built from card numbers and account identifiers.
         *
         * @param ddName the DD whose keyed read failed
         * @param status the two-character file status the repository reported, carried through unchanged
         * @return the exception to throw
         */
        private void noteReadFailedOtherThanInvalidKey(String ddName, String status) {
            LOG.error("A keyed read of DD " + ddName + " reported file status "
                    + FileStatus.toStatusImage(status) + ", which is not the INVALID KEY condition, so "
                    + PROGRAM_NAME + "'s INVALID KEY arm does not run and no status is substituted for "
                    + "this one. Every one of these files declares a FILE STATUS item in its SELECT and "
                    + "the program has no USE AFTER ERROR declarative, so control passes the END-READ "
                    + "and the paragraph reaches its EXIT: the record area is left exactly as it was and "
                    + "the report goes on to compose a line from it. Reported here rather than on the "
                    + "SYSOUT channel, because the DISPLAY belongs to the INVALID KEY arm and this is "
                    + "not that arm - emitting it would put a line in the report's output that the "
                    + "program never wrote.");
        }

        // -----------------------------------------------------------------------------------------
        // The six opens - app/cbl/CBTRN03C.cbl:376-482. Every one is the same three-stage shape:
        // MOVE 8 TO APPL-RESULT, then '00'-to-0-or-12, then the 88-level test and the abend arm. Each
        // paragraph keeps its own method and its own message, because each is a distinct place an
        // operator looks; the ladder itself lives in checkMoveFormStatus so the shape is stated once.
        // -----------------------------------------------------------------------------------------

        /**
         * {@code 0000-TRANFILE-OPEN} ({@code :376-392}) - {@code OPEN INPUT TRANSACT-FILE} at
         * {@code :378}, against <em>this job's</em> {@value TransactionReportJob#TRANFILE_DD_NAME}
         * binding rather than the module-wide one.
         *
         * <p>The binding is resolved here rather than cached at construction: it is an immutable value
         * that configuration fixes at context refresh, so resolving it per run costs nothing and keeps
         * this class from holding a second copy of something configuration already owns. The declared
         * type is inferred so this file's imports stay exactly its declared dependency set.
         *
         * @throws AbendException if the open reports any status but {@code '00'}
         */
        private void openTranFile() {
            var binding = batchConfig.datasetBinding(JOB_KEY, TRANFILE_DD_NAME);
            // :378  OPEN INPUT TRANSACT-FILE
            tranFile = transactionRepository.openInput(binding);
            checkMoveFormStatus(tranFile.openStatus(), ERROR_OPENING_TRANFILE);
        }

        /**
         * {@code 0100-REPTFILE-OPEN} ({@code :394-410}) - {@code OPEN OUTPUT REPORT-FILE} at
         * {@code :396}.
         *
         * <p>Output rather than extend: {@code app/jcl/TRANREPT.jcl:L76} declares
         * {@code DISP=(NEW,CATLG,DELETE)} against a relative generation, so each run produces a new
         * generation instead of appending to the previous one.
         *
         * @throws AbendException        if the open reports anything but success
         * @throws IllegalStateException if no sink was supplied and the configured dataset name cannot
         *                               be addressed as a dataset - a configuration fault, which the
         *                               COBOL has no guard for and which therefore is not folded into
         *                               the status ladder
         */
        private void openReptFile() {
            // :396  OPEN OUTPUT REPORT-FILE
            reportFile = reportSink == null
                    ? tranReportWriter.openOutput()
                    : tranReportWriter.openOutput(reportSink);
            checkMoveFormStatus(statusOf(reportFile.openOutcome()), ERROR_OPENING_REPTFILE);
        }

        /**
         * {@code 0200-CARDXREF-OPEN} ({@code :412-428}) - {@code OPEN INPUT XREF-FILE} at {@code :414}.
         *
         * @throws AbendException if the open reports any status but {@code '00'}
         */
        private void openCardXref() {
            // :414  OPEN INPUT XREF-FILE
            xrefCursor = cardXrefRepository.openBrowse();
            checkMoveFormStatus(xrefCursor.openStatus(), ERROR_OPENING_CROSS_REF_FILE);
        }

        /**
         * {@code 0300-TRANTYPE-OPEN} ({@code :430-446}) - {@code OPEN INPUT TRANTYPE-FILE} at
         * {@code :432}.
         *
         * @throws AbendException if the open reports any status but {@code '00'}
         */
        private void openTranType() {
            // :432  OPEN INPUT TRANTYPE-FILE
            checkMoveFormStatus(tranTypeRepository.open(), ERROR_OPENING_TRANSACTION_TYPE_FILE);
        }

        /**
         * {@code 0400-TRANCATG-OPEN} ({@code :448-464}) - {@code OPEN INPUT TRANCATG-FILE} at
         * {@code :450}.
         *
         * @throws AbendException if the open reports any status but {@code '00'}
         */
        private void openTranCatg() {
            // :450  OPEN INPUT TRANCATG-FILE
            checkMoveFormStatus(tranCategoryRepository.open(), ERROR_OPENING_TRANSACTION_CATG_FILE);
        }

        /**
         * {@code 0500-DATEPARM-OPEN} ({@code :466-482}) - {@code OPEN INPUT DATE-PARMS-FILE} at
         * {@code :468}.
         *
         * @throws AbendException if the open reports any status but {@code '00'}
         */
        private void openDateParm() {
            // :468  OPEN INPUT DATE-PARMS-FILE
            checkMoveFormStatus(dateParmReader.open(), ERROR_OPENING_DATE_PARM_FILE);
        }

        // -----------------------------------------------------------------------------------------
        // The six closes - app/cbl/CBTRN03C.cbl:514-621. Four use the same MOVE form as the opens.
        // The two written first - 9000 and 9100 - use ADD ... GIVING and SUBTRACT ... FROM instead,
        // which is the same arithmetic said a longer way, and which is transcribed rather than tidied.
        // -----------------------------------------------------------------------------------------

        /**
         * {@code 9000-TRANFILE-CLOSE} ({@code :514-530}) - {@code CLOSE TRANSACT-FILE} at {@code :516}.
         *
         * <p>Its ladder is spelled arithmetically: {@code ADD 8 TO ZERO GIVING APPL-RESULT} at
         * {@code :515}, {@code SUBTRACT APPL-RESULT FROM APPL-RESULT} at {@code :518} to reach zero, and
         * {@code ADD 12 TO ZERO GIVING APPL-RESULT} at {@code :520}. The values are identical to the
         * {@code MOVE}s the open paragraph uses; keeping the shape makes the two diffable against their
         * source, and these are arithmetic sites a parity assertion can name (gate G28).
         *
         * <p>The message is {@code 'ERROR CLOSING POSTED TRANSACTION FILE'} and not the open's
         * {@code 'ERROR OPENING TRANFILE'}: the two paragraphs describe the same dataset differently, and
         * both texts are transcribed exactly.
         *
         * @throws AbendException if the close reports any status but {@code '00'}
         */
        private void closeTranFile() {
            // :515  ADD 8 TO ZERO GIVING APPL-RESULT.   - dead, and preserved.
            int applResult = APPL_RESULT_ASSUMED_FAILURE;

            // :516  CLOSE TRANSACT-FILE
            String status = tranFile.closeInput();

            if (FileStatus.isOk(status)) {
                // :518  SUBTRACT APPL-RESULT FROM APPL-RESULT   - which is to say, zero.
                applResult -= applResult;
            } else {
                // :520  ADD 12 TO ZERO GIVING APPL-RESULT
                applResult = APPL_RESULT_FATAL;
            }

            // :522  IF APPL-AOK CONTINUE ELSE ...
            if (!applAok(applResult)) {
                // :525-527  DISPLAY the message, then 9910-DISPLAY-IO-STATUS.
                reportIoFailure(sysout, ERROR_CLOSING_POSTED_TRANSACTION_FILE, status);
                // :528  PERFORM 9999-ABEND-PROGRAM - which does not return.
                throw abendProgram(sysout, ERROR_CLOSING_POSTED_TRANSACTION_FILE, status);
            }
        }

        /**
         * {@code 9100-REPTFILE-CLOSE} ({@code :532-548}) - {@code CLOSE REPORT-FILE} at {@code :534}.
         *
         * <p>The same arithmetic form as {@code 9000-TRANFILE-CLOSE}: {@code ADD 8 TO ZERO GIVING} at
         * {@code :533}, {@code SUBTRACT APPL-RESULT FROM APPL-RESULT} at {@code :536},
         * {@code ADD 12 TO ZERO GIVING} at {@code :538}.
         *
         * @throws AbendException if the close reports anything but success
         */
        private void closeReptFile() {
            // :533  ADD 8 TO ZERO GIVING APPL-RESULT.   - dead, and preserved.
            int applResult = APPL_RESULT_ASSUMED_FAILURE;

            // :534  CLOSE REPORT-FILE
            String status = statusOf(reportFile.closeOutput());

            if (FileStatus.isOk(status)) {
                // :536  SUBTRACT APPL-RESULT FROM APPL-RESULT
                applResult -= applResult;
            } else {
                // :538  ADD 12 TO ZERO GIVING APPL-RESULT
                applResult = APPL_RESULT_FATAL;
            }

            // :540  IF APPL-AOK CONTINUE ELSE ...
            if (!applAok(applResult)) {
                // :543-545  DISPLAY 'ERROR CLOSING REPORT FILE', then 9910-DISPLAY-IO-STATUS.
                reportIoFailure(sysout, ERROR_CLOSING_REPORT_FILE, status);
                // :546  PERFORM 9999-ABEND-PROGRAM - which does not return.
                throw abendProgram(sysout, ERROR_CLOSING_REPORT_FILE, status);
            }
        }

        /**
         * {@code 9200-CARDXREF-CLOSE} ({@code :551-567}) - {@code CLOSE XREF-FILE} at {@code :553},
         * back to the {@code MOVE} form.
         *
         * @throws AbendException if the close reports any status but {@code '00'}
         */
        private void closeCardXref() {
            // :553  CLOSE XREF-FILE
            checkMoveFormStatus(xrefCursor.closeBrowse(), ERROR_CLOSING_CROSS_REF_FILE);
        }

        /**
         * {@code 9300-TRANTYPE-CLOSE} ({@code :569-585}) - {@code CLOSE TRANTYPE-FILE} at {@code :571}.
         *
         * @throws AbendException if the close reports any status but {@code '00'}
         */
        private void closeTranType() {
            // :571  CLOSE TRANTYPE-FILE
            checkMoveFormStatus(tranTypeRepository.close(), ERROR_CLOSING_TRANSACTION_TYPE_FILE);
        }

        /**
         * {@code 9400-TRANCATG-CLOSE} ({@code :587-603}) - {@code CLOSE TRANCATG-FILE} at {@code :589}.
         *
         * @throws AbendException if the close reports any status but {@code '00'}
         */
        private void closeTranCatg() {
            // :589  CLOSE TRANCATG-FILE
            checkMoveFormStatus(tranCategoryRepository.close(), ERROR_CLOSING_TRANSACTION_CATG_FILE);
        }

        /**
         * {@code 9500-DATEPARM-CLOSE} ({@code :605-621}) - {@code CLOSE DATE-PARMS-FILE} at
         * {@code :607}.
         *
         * @throws AbendException if the close reports any status but {@code '00'}
         */
        private void closeDateParm() {
            // :607  CLOSE DATE-PARMS-FILE
            checkMoveFormStatus(dateParmReader.close(), ERROR_CLOSING_DATE_PARM_FILE);
        }

        // -----------------------------------------------------------------------------------------
        // The shape shared by ten of the twelve open and close ladders.
        // -----------------------------------------------------------------------------------------

        /**
         * The {@code MOVE}-form ladder: {@code MOVE 8}, then {@code '00'} to {@code 0} or otherwise to
         * {@code 12}, then {@code IF APPL-AOK CONTINUE ELSE} display, render and abend.
         *
         * <p>One method for the ten paragraphs that share it, because ten transcriptions of four
         * statements would be ten places for one of them to drift. Each caller keeps its own method, its
         * own line citations and its own message, which is what an operator actually needs.
         *
         * <p>The {@code MOVE 8} is dead in every one of those paragraphs - the very next test overwrites
         * it on both arms - and it is reproduced anyway, because a statement present in the source must
         * be accounted for in the translation.
         *
         * @param status  the two-character file status the operation reported
         * @param message the paragraph's own {@code DISPLAY} literal
         * @throws AbendException if {@code status} is anything but {@code '00'}
         */
        private void checkMoveFormStatus(String status, String message) {
            // MOVE 8 TO APPL-RESULT.   - dead, and preserved.
            int applResult = APPL_RESULT_ASSUMED_FAILURE;

            if (FileStatus.isOk(status)) {
                // MOVE 0 TO APPL-RESULT
                applResult = APPL_RESULT_AOK;
            } else {
                // MOVE 12 TO APPL-RESULT
                applResult = APPL_RESULT_FATAL;
            }

            // IF APPL-AOK CONTINUE ELSE ...
            if (!applAok(applResult)) {
                reportIoFailure(sysout, message, status);
                // PERFORM 9999-ABEND-PROGRAM - which does not return.
                throw abendProgram(sysout, message, status);
            }
        }

        // -----------------------------------------------------------------------------------------
        // Arithmetic and rendering helpers. Every scale and rounding decision is delegated, so this
        // class names neither (gates G23, G24).
        // -----------------------------------------------------------------------------------------

        /**
         * {@code ADD <sender> TO <one of the three S9(09)V99 totals>}.
         *
         * <p>The sum itself is exact {@link BigDecimal} arithmetic - two values at scale 2 cannot produce
         * a third digit - and the single truncation decision is made by
         * {@link CobolDecimal#storeAtPicture(BigDecimal, int, int)}, which truncates the fraction toward
         * zero because {@code ROUNDED} appears nowhere in the 28 programs, and discards high-order digits
         * beyond {@value TransactionReportJob#TOTAL_INTEGER_DIGITS} rather than throwing, because
         * {@code ON SIZE ERROR} appears nowhere either. A receiver that overflows wraps, silently, as the
         * field does.
         *
         * @param total  the receiving accumulator
         * @param addend the sending value
         * @return the new value of the accumulator, at scale exactly
         *         {@value TransactionReportJob#TOTAL_SCALE}
         */
        private static BigDecimal addToTotal(BigDecimal total, BigDecimal addend) {
            return CobolDecimal.storeAtPicture(total.add(addend), TOTAL_INTEGER_DIGITS, TOTAL_SCALE);
        }

        /**
         * A running total rendered as the zoned {@code DISPLAY} image a COBOL {@code DISPLAY} of a
         * {@code PIC S9(09)V99} item emits: eleven characters, the sign overpunched into the last.
         *
         * <p>Used only by {@code :199}. Deliberately not a formatted number: {@code DISPLAY} of a numeric
         * {@code DISPLAY} item writes the field's stored bytes, so {@code 504.77} appears as
         * {@code 0000005047G} and not as {@code 504.77}. No locale is consulted, because no formatter is
         * involved (practice B8).
         *
         * @param total the accumulator to render
         * @return exactly {@value TransactionReportJob#TOTAL_INTEGER_DIGITS} plus
         *         {@value TransactionReportJob#TOTAL_SCALE} characters
         */
        private String zonedTotalImage(BigDecimal total) {
            return codec.encodeSignedScaled(total, TOTAL_INTEGER_DIGITS, TOTAL_SCALE);
        }
    }


    // =================================================================================================
    // The two 88-level condition names of app/cbl/CBTRN03C.cbl:151-152, as named predicates, plus the
    // two paragraphs every guard chain shares: 9910-DISPLAY-IO-STATUS and 9999-ABEND-PROGRAM.
    // =================================================================================================

    /**
     * {@code 88 APPL-AOK VALUE 0} - {@code app/cbl/CBTRN03C.cbl:151}.
     *
     * <p>Tested by all twelve open and close ladders, by both reads and by the write, in both truth
     * states, which is what makes both states reachable from a test rather than only the happy one.
     *
     * @param applResult the current {@code APPL-RESULT}
     * @return {@code true} when it is {@value #APPL_RESULT_AOK}
     */
    private static boolean applAok(int applResult) {
        return applResult == APPL_RESULT_AOK;
    }

    /**
     * {@code 88 APPL-EOF VALUE 16} - {@code app/cbl/CBTRN03C.cbl:152}.
     *
     * <p>Tested by the two reads only. An open, a close and a write have no end-of-file outcome, which is
     * why their ladders move {@code 0} or {@code 12} and never {@code 16}.
     *
     * @param applResult the current {@code APPL-RESULT}
     * @return {@code true} when it is {@value #APPL_RESULT_EOF}
     */
    private static boolean applEof(int applResult) {
        return applResult == APPL_RESULT_EOF;
    }

    /**
     * The two-character {@code FILE STATUS} to attribute to a {@link FileStatus.Outcome} the report
     * writer reported.
     *
     * <p>{@link FileStatus.Outcome#OTHER} stands for no single status by design, so one has to be named
     * in order to move it into {@code IO-STATUS}; every other outcome carries its own.
     *
     * @param outcome what the writer reported; never {@code null}
     * @return the status to render, always two characters
     */
    private static String statusOf(FileStatus.Outcome outcome) {
        return outcome.batchStatus().orElse(PERMANENT_ERROR_STATUS);
    }

    /**
     * A paragraph's failure message followed by the rendered file status - which is
     * {@code MOVE ... TO IO-STATUS} followed by {@code PERFORM 9910-DISPLAY-IO-STATUS}.
     *
     * <p>The status line comes from {@link FileStatus#toDisplayLine(String)} rather than being assembled
     * here, so all sixteen emission sites across the migrated batch programs produce one byte-identical
     * form of {@code DISPLAY 'FILE STATUS IS: NNNN' IO-STATUS-04}. {@code 9910-DISPLAY-IO-STATUS}
     * ({@code app/cbl/CBTRN03C.cbl:633-646}) has two arms - the extended {@code '9'}-plus-binary form and
     * the ordinary numeric form - and both live in that one renderer, which is why this method must not
     * re-implement either.
     *
     * <p>On the three {@code INVALID KEY} paths the status passed here is {@value #IO_STATUS_INVALID_KEY}
     * rather than whatever the read reported, because {@code :488}, {@code :498} and {@code :508} move the
     * literal 23 into {@code IO-STATUS} first. So those lines always render
     * {@code FILE STATUS IS: NNNN0023}, even for a read that failed for some other reason. Transcribed
     * as written.
     *
     * @param sysout  where the two lines go
     * @param message the paragraph's own message literal, already concatenated with any key it displays
     * @param status  the two-character file status to render
     */
    private static void reportIoFailure(SysoutSink sysout, String message, String status) {
        sysout.display(message);
        sysout.display(FileStatus.toDisplayLine(status));
    }

    /**
     * {@code 9999-ABEND-PROGRAM} - {@code app/cbl/CBTRN03C.cbl:626-630}.
     *
     * <pre>
     * DISPLAY 'ABENDING PROGRAM'                                       :627
     * MOVE 0 TO TIMING                                                 :628
     * MOVE 999 TO ABCODE                                               :629
     * CALL 'CEE3ABD'.                                                  :630
     * </pre>
     *
     * <p>Returns the exception rather than throwing it, so every call site reads
     * {@code throw abendProgram(...)} and the compiler can see that the path ends there - which is what
     * {@code CALL 'CEE3ABD'} does: it terminates the task and never returns to the paragraph that
     * performed it.
     *
     * <p>{@link AbendException#standard(String, int, String)} <em>is</em> the two moves at {@code :628-629}:
     * it supplies {@code ABCODE} {@value AbendException#STANDARD_ABEND_CODE} and {@code TIMING}
     * {@value AbendException#STANDARD_TIMING}, and it carries the return code the guard chain arrived
     * with (gate G35). That code is {@value #APPL_RESULT_FATAL} at every one of this program's abend
     * sites: the twelve open and close ladders and the write move it explicitly, and the three keyed
     * lookups never touch {@code APPL-RESULT} at all - so the same fatal value is used for them, which is
     * the convention the repositories' own {@code applResult()} accessors already publish for exactly
     * these three reads.
     *
     * <p>{@code CBTRN03C} moves nothing to {@code RETURN-CODE} anywhere, so a clean run returns zero and
     * only an abend produces a non-zero exit code, through {@code BatchConfig}'s exit-code mapper.
     *
     * @param sysout  where the {@code 'ABENDING PROGRAM'} line goes
     * @param message the message the failing paragraph displayed, carried into the exception's reason so
     *                the cause is legible without the {@code SYSOUT} to hand
     * @param status  the file status that failed, rendered into the same reason
     * @return the exception to throw
     */
    private static AbendException abendProgram(SysoutSink sysout, String message, String status) {
        // :627  DISPLAY 'ABENDING PROGRAM'
        sysout.display(AbendException.ABEND_DISPLAY_TEXT);
        // :628-630  MOVE 0 TO TIMING, MOVE 999 TO ABCODE, CALL 'CEE3ABD'.
        return AbendException.standard(PROGRAM_NAME, APPL_RESULT_FATAL,
                message + " - " + FileStatus.toDisplayLine(status));
    }

    // =================================================================================================
    // Nested types.
    // =================================================================================================

    /**
     * Where a {@code DISPLAY} line goes.
     *
     * <p>A single-method interface so a test, a parity harness or a deployment supplies a destination
     * without this class knowing the difference: the program body only ever calls
     * {@link #display(String)}. Declared per job rather than shared, matching the sibling batch jobs,
     * because a job's {@code SYSOUT} is a property of its JCL step rather than of the module.
     */
    @FunctionalInterface
    public interface SysoutSink {

        /**
         * Emits one line, without its terminator.
         *
         * @param line the line's content, exactly as the {@code DISPLAY} composed it - never
         *             {@code null}, because {@code CBTRN03C} never displays nothing
         */
        void display(String line);
    }

    /**
     * A {@link SysoutSink} over an {@link OutputStream}, encoding each line in the dataset code page and
     * flushing it.
     *
     * <p>Flushed per line on purpose: {@code SYSOUT} interleaved with a report is read to establish
     * <em>when</em> something happened, and a buffered sink that lost its tail on an abend would lose
     * precisely the lines that explain it.
     */
    private static final class StreamSysoutSink implements SysoutSink {

        /** The destination stream, supplied rather than chosen by this class. */
        private final OutputStream destination;

        /** The codec whose charset the line is encoded in. */
        private final FixedWidthCodec codec;

        /**
         * @param destination where the encoded bytes go
         * @param codec       the codec carrying the explicitly chosen charset
         * @throws NullPointerException if either argument is {@code null}
         */
        private StreamSysoutSink(OutputStream destination, FixedWidthCodec codec) {
            this.destination = Objects.requireNonNull(destination, "A destination stream is required to "
                    + "write the SYSOUT lines of " + PROGRAM_NAME);
            this.codec = Objects.requireNonNull(codec, "A codec is required: a SYSOUT line is written in "
                    + "a named code page, never in the platform default");
        }

        /**
         * Encodes the line and its terminator and writes them.
         *
         * @param line the line to emit; never {@code null}
         * @throws NullPointerException if {@code line} is {@code null}
         * @throws UncheckedIOException if the destination refuses the bytes
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
     * What one execution of {@code CBTRN03C} produced.
     *
     * <p>None of it is invented: every component is a value the program itself holds when it reaches
     * {@code GOBACK}, or a count of writes it performed. The two counters that are <em>not</em> COBOL
     * items - {@code recordsRead} and {@code detailLinesWritten} - are counts of operations rather than
     * derived quantities, which is what makes them assertable without claiming the program computed them.
     *
     * <p>Returned rather than logged, so a parity case can diff it and a step needs no side channel.
     *
     * @param returnCode          {@code RETURN-CODE} at {@code GOBACK}. Always
     *                            {@value AbendException#RETURN_CODE_OK}, because the program moves nothing
     *                            to it; an abend does not return a summary at all
     * @param recordsRead         how many records the {@value #TRANFILE_DD_NAME} browse returned. Note
     *                            that the last one may have been read and then <em>not</em> reported, if
     *                            defect 1 ended the loop
     * @param detailLinesWritten  how many detail lines {@code 1120-WRITE-DETAIL} wrote - one per reported
     *                            record
     * @param reportLinesWritten  how many {@value TranReportWriter#RECORD_LENGTH}-byte records reached
     *                            {@value #TRANREPT_DD_NAME} in total: headers, details, totals and rules
     * @param lineCounter         {@code WS-LINE-COUNTER} as the program leaves it. Deliberately
     *                            <em>not</em> equal to {@code reportLinesWritten}:
     *                            {@code 1110-WRITE-GRAND-TOTALS} writes a line and increments nothing
     * @param grandTotal          {@code WS-GRAND-TOTAL} as the program leaves it - the sum of the page
     *                            totals, at scale {@value #TOTAL_SCALE}
     */
    public record ExecutionSummary(int returnCode,
                                   int recordsRead,
                                   int detailLinesWritten,
                                   int reportLinesWritten,
                                   long lineCounter,
                                   BigDecimal grandTotal) {

        /**
         * Rejects a summary that could not have been produced by a run, so a malformed one cannot be
         * built even by a test.
         *
         * @throws NullPointerException     if {@code grandTotal} is {@code null}
         * @throws IllegalArgumentException if any count or the line counter is negative, if
         *                                  {@code detailLinesWritten} exceeds {@code reportLinesWritten},
         *                                  or if {@code grandTotal}'s scale is not
         *                                  {@value #TOTAL_SCALE}
         */
        public ExecutionSummary {
            Objects.requireNonNull(grandTotal, "WS-GRAND-TOTAL is required on a summary; the program "
                    + "initialises it to zero at app/cbl/CBTRN03C.cbl:136, so it is never absent");
            requireNotNegative(recordsRead, "recordsRead");
            requireNotNegative(detailLinesWritten, "detailLinesWritten");
            requireNotNegative(reportLinesWritten, "reportLinesWritten");
            if (lineCounter < 0) {
                throw new IllegalArgumentException("WS-LINE-COUNTER is PIC 9(09) COMP-3, an unsigned "
                        + "field, so " + lineCounter + " is not a value a run of " + PROGRAM_NAME
                        + " could have left in it.");
            }
            if (detailLinesWritten > reportLinesWritten) {
                throw new IllegalArgumentException("A run reported " + detailLinesWritten + " detail "
                        + "line(s) out of " + reportLinesWritten + " report line(s), which is not "
                        + "possible: every detail line is a report line, and a report that has any "
                        + "detail line also has the four header lines that preceded it.");
            }
            if (grandTotal.scale() != TOTAL_SCALE) {
                throw new IllegalArgumentException("WS-GRAND-TOTAL is PIC S9(09)V99, so its scale is "
                        + TOTAL_SCALE + " and not " + grandTotal.scale() + ". Route the value through "
                        + "CobolDecimal so the scale and the rounding mode are named in one place.");
            }
        }

        /**
         * Rejects a negative count.
         *
         * @param count what was supplied
         * @param name  the component's name, for the diagnostic
         * @throws IllegalArgumentException if {@code count} is negative
         */
        private static void requireNotNegative(int count, String name) {
            if (count < 0) {
                throw new IllegalArgumentException("A count of " + count + " is not possible for "
                        + name + ": a run of " + PROGRAM_NAME + " reads and writes zero or more.");
            }
        }

        /**
         * Whether the run produced a report body at all.
         *
         * <p>{@code false} for the two paths that produce none: an empty
         * {@value #DATEPARM_DD_NAME}, whose {@code '10'} arm sets the end-of-file flag before the loop
         * begins, and an empty {@value #TRANFILE_DD_NAME}, where defect 1 ends the loop on the first pass
         * because the untouched record area holds spaces.
         *
         * @return {@code true} when at least one line reached the report
         */
        public boolean producedReport() {
            return reportLinesWritten > 0;
        }

        /**
         * How many report lines were not detail lines: headers, rules and total lines.
         *
         * @return {@code reportLinesWritten} less {@code detailLinesWritten}, never negative
         */
        public int structuralLinesWritten() {
            return reportLinesWritten - detailLinesWritten;
        }
    }
}
