package com.vsergeychik.carddemo.account;

import com.vsergeychik.carddemo.account.AccountRepository.AccountFile;
import com.vsergeychik.carddemo.account.model.AccountRecord;
import com.vsergeychik.carddemo.account.model.DisclosureGroupRecord;
import com.vsergeychik.carddemo.card.CardXrefRepository;
import com.vsergeychik.carddemo.card.CardXrefRepository.BrowseCursor;
import com.vsergeychik.carddemo.card.model.CardXrefRecord;
import com.vsergeychik.carddemo.common.AbendException;
import com.vsergeychik.carddemo.common.CobolDecimal;
import com.vsergeychik.carddemo.common.DatasetRelation;
import com.vsergeychik.carddemo.common.DatasetRelation.KeySpan;
import com.vsergeychik.carddemo.common.DiagnosticText;
import com.vsergeychik.carddemo.common.FileStatus;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.RecordImageForm;
import com.vsergeychik.carddemo.common.SensitiveDiagnostics;
import com.vsergeychik.carddemo.config.BatchConfig;
import com.vsergeychik.carddemo.config.BatchConfig.StepContract;
import com.vsergeychik.carddemo.config.CobolCharsetConfig;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBinding;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBindings;
import com.vsergeychik.carddemo.config.DatasetUnitOfWork;
import com.vsergeychik.carddemo.transaction.TranCatBalRepository;
import com.vsergeychik.carddemo.transaction.TranCatBalRepository.TranCatBalFile;
import com.vsergeychik.carddemo.transaction.TransactionRepository;
import com.vsergeychik.carddemo.transaction.TransactionRepository.OutputFile;
import com.vsergeychik.carddemo.transaction.model.TranCatBalRecord;
import com.vsergeychik.carddemo.transaction.model.TranRecord;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.StepExecutionListener;
import org.springframework.batch.core.step.builder.SimpleStepBuilder;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.batch.item.ItemStreamReader;
import org.springframework.batch.item.ItemWriter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.RowMapper;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.Charset;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * {@code CBACT04C} - the interest calculator, and the arithmetic heart of this package.
 *
 * <p>Translated from {@code app/cbl/CBACT04C.cbl} (652 lines) under the step contract of
 * {@code app/jcl/INTCALC.jcl}. Every statement below carries the source line it was transcribed from,
 * because this is the one program in the estate whose output is money.
 *
 * <h2>The single most important fact about this file</h2>
 *
 * <p>The keyword {@code ROUNDED} appears <strong>zero</strong> times in any of the 28 COBOL programs -
 * verified by exhaustive scan, not assumed. Absent {@code ROUNDED} the COBOL standard <em>truncates</em>
 * excess fractional digits on store. Every scaled store in this file therefore goes through
 * {@link CobolDecimal}, whose one rounding mode is {@link RoundingMode#DOWN}, and this source contains
 * no other rounding mode and neither of the JVM's binary approximate primitives (gates G22 and G24).
 *
 * <p>The whole of the program's arithmetic is four {@code ADD} statements and one {@code COMPUTE} - there
 * is no {@code MULTIPLY}, no {@code DIVIDE} and no {@code SUBTRACT} verb anywhere in it:
 * <table border="1">
 *   <caption>Every arithmetic site in CBACT04C</caption>
 *   <tr><th>Source</th><th>Statement</th><th>Receiver</th><th>Here</th></tr>
 *   <tr><td>{@code :192}</td><td>{@code ADD 1 TO WS-RECORD-COUNT}</td><td>{@code 9(09)}</td>
 *       <td>{@link WorkingStorage#addOneToRecordCount()}</td></tr>
 *   <tr><td>{@code :352}</td><td>{@code ADD WS-TOTAL-INT TO ACCT-CURR-BAL}</td><td>{@code S9(10)V99}</td>
 *       <td>{@link InterestCalculationRun#updateAccount()}</td></tr>
 *   <tr><td>{@code :464}</td><td>{@code COMPUTE WS-MONTHLY-INT = ( TRAN-CAT-BAL * DIS-INT-RATE) / 1200}</td>
 *       <td>{@code S9(09)V99}</td><td>{@link #computeMonthlyInterest(BigDecimal, BigDecimal)}</td></tr>
 *   <tr><td>{@code :467}</td><td>{@code ADD WS-MONTHLY-INT TO WS-TOTAL-INT}</td><td>{@code S9(09)V99}</td>
 *       <td>{@link WorkingStorage#addToTotalInterest(BigDecimal)}</td></tr>
 *   <tr><td>{@code :474}</td><td>{@code ADD 1 TO WS-TRANID-SUFFIX}</td><td>{@code 9(06)}</td>
 *       <td>{@link WorkingStorage#addOneToTranidSuffix()}</td></tr>
 * </table>
 *
 * <p>The {@code / 1200} inside that one {@code COMPUTE} is the only division in the entire migrated
 * estate, which makes {@link #computeMonthlyInterest(BigDecimal, BigDecimal)} the highest-value
 * arithmetic assertion in the whole project (gate G25). Its operands' shapes fix the answer completely:
 * {@code TRAN-CAT-BAL PIC S9(09)V99} [{@code app/cpy/CVTRA01Y.cpy:9}] times
 * {@code DIS-INT-RATE PIC S9(04)V99} [{@code app/cpy/CVTRA02Y.cpy:9}] is an exact scale-4 product, and
 * the division is the last operation before the store and so the only place a digit is lost. A balance
 * of {@code 1000.00} at {@code 12.50} stores {@code 10.41} and never {@code 10.42}.
 *
 * <h2>THE 16-BYTE DISCLOSURE KEY - the byte correction this file exists to get right</h2>
 *
 * <p>{@code DIS-GROUP-KEY} is <strong>16</strong> bytes:
 * {@code DIS-ACCT-GROUP-ID X(10)} + {@code DIS-TRAN-TYPE-CD X(02)} + {@code DIS-TRAN-CAT-CD 9(04)}
 * [{@code app/cpy/CVTRA02Y.cpy:5-8}], independently corroborated by {@code FD-DISCGRP-KEY} 16 plus
 * {@code FD-DISCGRP-DATA X(34)} at {@code app/cbl/CBACT04C.cbl:78-82}. The <strong>17</strong>-byte key
 * is {@code TRAN-CAT-KEY} of {@code app/cpy/CVTRA01Y.cpy}. Both records total 50 bytes, which is exactly
 * why confusing them is invisible to a width check - and a 17-byte disclosure key would shift
 * {@code DIS-INT-RATE} by one byte and corrupt every rate this job reads, destroying G25 silently. The
 * two widths are asserted against their owning types in {@link #verifyDeclaredKeyGeometry()}.
 *
 * <h2>Three stale-state behaviours, all preserved (practice B5)</h2>
 * <ol>
 *   <li><strong>The {@code '23'} disclosure-group window.</strong> {@code app/cbl/CBACT04C.cbl:415-439}
 *       accepts {@code '00'} <em>or</em> {@code '23'} from the keyed read. On {@code '23'} the
 *       {@code INTO} never happened, so {@code DIS-GROUP-RECORD} still holds the <em>previous</em>
 *       record - rate included - until {@code 1200-A} succeeds. The record area here is therefore
 *       replaced only by a successful read and is never cleared on a failed one.</li>
 *   <li><strong>The {@code TRAN-DESC} residue.</strong> {@code :485-489} strings 24 characters into
 *       {@code TRAN-DESC PIC X(100)}, and COBOL {@code STRING} does <strong>not</strong> blank the
 *       remainder of its receiver. Bytes 25 to 100 keep whatever the previous iteration left there. One
 *       {@link TranRecord} instance therefore serves the whole run, and the transfer goes through
 *       {@link TranRecord#stringIntoTranDesc(String...)} rather than a padding setter.</li>
 *   <li><strong>The never-reset transaction-id suffix.</strong> {@code WS-TRANID-SUFFIX} is incremented
 *       at {@code :474} and reset nowhere, so it counts written transactions across account boundaries
 *       for the life of the run.</li>
 * </ol>
 *
 * <h2>THE UNREACHABLE ELSE - do not "fix" it</h2>
 *
 * <p>The mainline loop is {@code PERFORM UNTIL END-OF-FILE = 'Y'} ({@code :188}) and its body is
 * {@code IF END-OF-FILE = 'N' ... ELSE PERFORM 1050-UPDATE-ACCOUNT END-IF} ({@code :189}, {@code :219-221}).
 * The loop guard has already established that the flag is not {@code 'Y'}, and the only value the program
 * ever moves into it besides {@code 'N'} is {@code 'Y'} - so the {@code ELSE} at {@code :220}
 * <strong>can never execute</strong>, and <strong>the last account group's accumulated interest is never
 * written back</strong>. That is a defect in the legacy program and it is part of the contract:
 * {@link #calculateInterest(String, SysoutSink)} keeps the branch exactly where the COBOL puts it and
 * adds no end-of-file flush. Adding one would change the account master on every run and produce a
 * non-zero diff on every parity case for this program.
 *
 * <p>The branch is nevertheless reachable from a test, exactly as it is reachable in COBOL: the flag is
 * a one-character field with a package-visible mover ({@link WorkingStorage#moveEndOfFile(String)}), so
 * a third value drives the {@code ELSE} arm without the production paths ever producing one.
 *
 * <h2>Two more preserved source defects</h2>
 * <ul>
 *   <li>{@code 0200-DISCGRP-OPEN} displays {@value #ERROR_OPENING_DISCGRP} when the
 *       <em>disclosure group</em> file fails to open ({@code :281}). The text names the wrong file. It is
 *       reproduced verbatim, because an operator's runbook matches on that string.</li>
 *   <li>{@code 0100-XREFFILE-OPEN} appends {@code XREFFILE-STATUS} to its own {@code DISPLAY}
 *       ({@code :263}), which none of the other four opens do, so that line carries a two-character
 *       suffix the others do not.</li>
 * </ul>
 *
 * <h2>{@code 1400-COMPUTE-FEES} is a no-op, and it is still called (gate G26)</h2>
 *
 * <p>{@code :518-520} is a paragraph header, the comment {@code * To be implemented}, and {@code EXIT.}
 * - <strong>zero statements</strong>. {@link #computeFees()} therefore does nothing, and it is invoked
 * from the exact position of {@code :216}: immediately after {@code 1300-COMPUTE-INTEREST}, <em>inside</em>
 * the {@code IF DIS-INT-RATE NOT = 0} guard, so it runs once per interest computation and never when the
 * rate is zero. Implementing fee logic would be a new feature and a parity violation.
 *
 * <h2>Why the disclosure-group access path lives in this file</h2>
 *
 * <p>{@code app/cpy/CVTRA02Y.cpy} has exactly <strong>one</strong> consumer in the whole estate, and it
 * is this program. There is consequently no standalone disclosure-group repository, and inventing a
 * cross-package one for a single caller would spread a dataset's access path away from the only code
 * that can exercise it. The path is a nested seam - {@link DisclosureGroupAccess}, published as a bean by
 * {@link #disclosureGroupAccess(JdbcTemplate, DatasetBindings, Charset, RecordImageForm)} - built on the
 * same primitives every sibling repository uses, so the dataset name still comes from
 * {@code carddemo.datasets.DISCGRP} and no dataset literal appears in this source (gate G46). The seam
 * is also what lets a unit test drive all four of the read outcomes without a database.
 *
 * <h2>Chunk orientation, chunk size 1, and where the writes live</h2>
 *
 * <p>{@link BatchConfig#chunkStep(String, int)} names this program as one of exactly two whose COBOL
 * loop is genuinely record-at-a-time, so the step is chunk-oriented. The commit interval is
 * {@value #CHUNK_SIZE}, chosen to preserve ordering rather than throughput (AAP 0.8.6): one record per
 * chunk makes the read / process / write triple atomic per record, which is the granularity of the COBOL
 * loop itself, and no chunk boundary can fall inside an account group in a way that changes the
 * accumulator.
 *
 * <p><strong>The dataset writes stay in the processor stage, deliberately.</strong> Moving them to the
 * writer would relocate the account {@code REWRITE} of {@code :196} to <em>after</em> the {@code :203}
 * and {@code :205} reads of the next account, and those reads emit
 * {@value #ACCOUNT_NOT_FOUND_PREFIX} lines of their own. On the happy path the two orders are
 * indistinguishable; on the rewrite-failure path they are not, because the COBOL abends before reaching
 * the reads. Parity is decided by the whole line sequence, including the failure paths, so the verbs
 * stay where the source puts them and the writer stage does bookkeeping only. The prompt's escape clause
 * for exactly this situation - state it in a comment and constrain the chunk size - is what this
 * paragraph is.
 *
 * <h2>One implementation, two drivers</h2>
 *
 * <p>{@link InterestCalculationRun} holds the {@code WORKING-STORAGE} and the five open files and
 * exposes every paragraph as a package-visible method. Two things drive it and neither duplicates the
 * other:
 * <ol>
 *   <li>{@link #calculateInterest(String, SysoutSink)} - the whole {@code PROCEDURE DIVISION} as a plain
 *       method, runnable with no application context, no {@code JobLauncher} and no HTTP layer, which is
 *       what makes the arithmetic reachable by the parity harness (practice B10, gate G51);</li>
 *   <li>{@link ChunkDelegate} - the same {@code Run} presented to Spring Batch as reader, processor and
 *       writer.</li>
 * </ol>
 *
 * <h2>The PARM is character data</h2>
 *
 * <p>{@code app/jcl/INTCALC.jcl:22} is {@code EXEC PGM=CBACT04C,PARM='2022071800'}, received as
 * {@code 01 EXTERNAL-PARMS. 05 PARM-LENGTH PIC S9(04) COMP. 05 PARM-DATE PIC X(10).} ({@code :175-180}).
 * {@code :476-480} concatenates {@code PARM-DATE} verbatim into every generated transaction identifier
 * and nothing ever parses it, so it travels as the {@code String} job parameter
 * {@value BatchConfig#PARM_DATE_PARAMETER} and is never converted to a date or revalidated as one.
 *
 * <h2>User-specified rules</h2>
 *
 * <p>{@code review_rules} returns exactly one line - "No user rules provided." - and that single line is
 * the entire document, so <strong>no user rule governs this file</strong>. Its absence is not licence to
 * lower the bar; the migration plan elevates twelve enterprise practices to binding constraints instead,
 * and the ones bearing on this file are B1 (no dependency is added - every type used here arrives from
 * the batch, jdbc and core starters already declared), B2 (Spring Batch 5.2.6 APIs through
 * {@link BatchConfig}'s builder seams, and the annotation that activates batch infrastructure manually -
 * which under Boot 3 <em>disables</em> the auto-configuration - appears nowhere in this module and is
 * described rather than named here so a compliance scan of this source records no hit), B3 (the COBOL,
 * JCL and copybooks cited throughout are read-only), B5 (the unreachable {@code ELSE}, the wrong open
 * message, the fee stub and the three stale-state behaviours are preserved rather than tidied), B7 (one
 * non-interactive command runs the whole cycle), B8 (explicit imports with no wildcard, the dataset name
 * externalised, the code page stated, the scale and rounding mode named at every store), B9 (no static
 * mutable state - the accumulators live on a {@link WorkingStorage} instance created per run), B10 (the
 * arithmetic is reachable without the framework) and B11 (every byte is written through the hand-written
 * codec, so it is auditable against the copybook).
 *
 * @see CobolDecimal
 * @see TranCatBalRepository
 * @see DisclosureGroupRecord
 * @see BatchConfig
 */
@Configuration(AccountInterestCalcJob.CONFIGURATION_BEAN_NAME)
public class AccountInterestCalcJob {

    /**
     * The program's own logger, distinct from the one {@link JdbcDisclosureGroupAccess} keeps.
     *
     * <p>It carries exactly one kind of line: a report that the {@code TRANSACT} DD's abnormal
     * disposition could not be applied. That deliberately does not go to {@code SYSOUT}, because
     * {@code CBACT04C} emits no such line and {@code SYSOUT} is compared byte for byte - the disposition
     * is the JCL's work, not the program's.
     */
    private static final Log LOG = LogFactory.getLog(AccountInterestCalcJob.class);

    // =================================================================================================
    // Identity: the COBOL program, the configuration key, the Spring Batch names and the DD names.
    // =================================================================================================

    /** The bean name of this configuration class itself, so nothing has to guess at it. */
    public static final String CONFIGURATION_BEAN_NAME = "accountInterestCalcJobConfiguration";

    /** The COBOL program this class is the translation of: {@code app/cbl/CBACT04C.cbl}. */
    public static final String PROGRAM_ID = "CBACT04C";

    /** The {@code carddemo.jobs} key whose contract carries this job's PARM, step and DD overrides. */
    public static final String JOB_KEY = "account-interest-calc-job";

    /** The published {@link Job} bean's name. */
    public static final String JOB_NAME = "accountInterestCalcJob";

    /** The step name, transcribed from {@code app/jcl/INTCALC.jcl:22} - {@code //STEP15 EXEC PGM=...}. */
    public static final String STEP_NAME = "STEP15";

    /**
     * The whole step sequence of {@code app/jcl/INTCALC.jcl}: one step, {@value #STEP_NAME}, running
     * {@value #PROGRAM_ID}, ungated.
     *
     * <p>Stated as a sequence because the step's own fields, checked one at a time, cannot express
     * "and nothing else". This job writes a new {@code SYSTRAN} generation and rewrites account
     * balances, so an extra step declared beside {@value #STEP_NAME} would do that work twice against
     * the same generation.
     */
    public static final List<StepContract> REQUIRED_STEPS =
            List.of(new StepContract(STEP_NAME, PROGRAM_ID, false));

    /**
     * The commit interval, in items: {@value}.
     *
     * <p>Not a performance setting. One record per chunk is the granularity of the COBOL loop, so the
     * read / process / write triple commits exactly where the single-pass program would have, and no
     * chunk boundary can fall inside an account group. See this class's documentation.
     */
    public static final int CHUNK_SIZE = 1;

    /**
     * Names the {@code REWRITE} of {@code 1050-UPDATE-ACCOUNT} in a persistence failure.
     *
     * <p>The paragraph and the verb, so a diagnostic says which of the program's two writes could not be
     * persisted without the reader having to infer it from a stack trace.
     */
    static final String REWRITE_ACCTFILE_VERB = "1050-UPDATE-ACCOUNT REWRITE FD-ACCTFILE-REC";

    /** Names the {@code WRITE} of {@code 1300-B-WRITE-TX} in a persistence failure. */
    static final String WRITE_TRANFILE_VERB = "1300-B-WRITE-TX WRITE FD-TRANFILE-REC";

    /**
     * Names the {@code TRANSACT} DD's abnormal disposition for attribution in a diagnostic.
     *
     * <p>Not a COBOL statement. {@code app/jcl/INTCALC.jcl:37} declares
     * {@code DISP=(NEW,CATLG,DELETE)}, whose third positional is what the initiator applies when the step
     * ends abnormally.
     */
    static final String TRANSACT_ABNORMAL_DISPOSITION =
            "app/jcl/INTCALC.jcl:37 TRANSACT DISP=(NEW,CATLG,DELETE) abnormal disposition";

    /**
     * Names the {@code TRANSACT} DD's <em>normal</em> disposition - the {@code NEW} allocation - for
     * attribution in a diagnostic.
     *
     * <p>The first positional of the same {@code DISP=(NEW,CATLG,DELETE)} at
     * {@code app/jcl/INTCALC.jcl:37}: the step allocates a new generation, so the run writes into an empty
     * one rather than appending to the previous run's. Applied in a boundary of its own for the same
     * reason the abnormal disposition is - see {@link InterestCalculationRun#tranfileOpen()}.
     */
    static final String TRANSACT_OPEN_DISPOSITION =
            "app/jcl/INTCALC.jcl:37 TRANSACT DISP=(NEW,CATLG,DELETE) new-generation allocation";

    /** {@code TCATBALF} - the driving browse ({@code app/jcl/INTCALC.jcl:27-28}). */
    public static final String TCATBALF_DD_NAME = TranCatBalRepository.DD_NAME;

    /** {@code ACCTFILE} - the account master, opened {@code I-O} ({@code app/jcl/INTCALC.jcl:33-34}). */
    public static final String ACCTFILE_DD_NAME = AccountRepository.BATCH_DD_NAME;

    /**
     * {@code DISCGRP} - the disclosure group rates ({@code app/jcl/INTCALC.jcl:35-36}).
     *
     * <p>A configuration key and not a dataset name: {@link DisclosureGroupAccess} resolves it against
     * {@code carddemo.datasets}, so no dataset name appears in this source (gate G46).
     */
    public static final String DISCGRP_DD_NAME = "DISCGRP";

    /**
     * {@code XREFFILE} - the cross-reference base cluster ({@code app/jcl/INTCALC.jcl:29-30}).
     *
     * <p>The DD name this step's own JCL declares, spelled as the JCL spells it. It is deliberately
     * <em>not</em> {@link CardXrefRepository#BASE_DD_NAME}: that constant is {@code CCXREF}, the CICS
     * file name the online programs address, and it is a different configuration key with its own
     * independent override. Naming the repository's key here made this job resolve, validate and report
     * a binding its JCL never mentions - so a deployment that pointed the two keys at different datasets
     * would have had this job read the wrong one with nothing saying so. The two are now proven equal at
     * construction instead, by {@code BatchConfig.requireSameDataset}.
     */
    public static final String XREFFILE_DD_NAME = CardXrefRepository.BATCH_DD_NAME;

    /**
     * {@code XREFFIL1} - the alternate-index path over the same cluster
     * ({@code app/jcl/INTCALC.jcl:31-32}).
     *
     * <p>{@code app/cbl/CBACT04C.cbl:38} declares {@code ALTERNATE RECORD KEY IS FD-XREF-ACCT-ID}, and
     * {@code 1500-A-LOOKUP-XREF} reads through it. It is a second access path over the dataset
     * {@link #XREFFILE_DD_NAME} names, never a second dataset (gate G45), and it is declared here
     * because this job resolves it and hands it to the repository - the alias is a configuration key,
     * so it has to be consumed to have any effect at all.
     */
    public static final String XREFFIL1_DD_NAME = CardXrefRepository.ALTERNATE_INDEX_BATCH_DD_NAME;

    /**
     * {@code TRANSACT} - the generated-transaction output ({@code app/jcl/INTCALC.jcl:37-41}).
     *
     * <p>{@code DISP=(NEW,CATLG,DELETE)} over {@code SYSTRAN(+1)} at {@code RECFM=F LRECL=350}, which is
     * {@code app/cpy/CVTRA05Y.cpy}'s width exactly (gates G19 and G20). The DD name collides with the
     * CICS transaction master, which is why {@code carddemo.jobs.account-interest-calc-job} re-points it.
     */
    public static final String TRANSACT_DD_NAME = TransactionRepository.CICS_FILE_NAME;

    // =================================================================================================
    // The SYSOUT literals. Every one is byte-exact observable output: a changed character is a failed
    // parity case, so each is quoted with the source line it was transcribed from.
    // =================================================================================================

    /** {@code DISPLAY 'START OF EXECUTION OF PROGRAM CBACT04C'} - {@code app/cbl/CBACT04C.cbl:181}. */
    public static final String START_OF_EXECUTION = "START OF EXECUTION OF PROGRAM " + PROGRAM_ID;

    /** {@code DISPLAY 'END OF EXECUTION OF PROGRAM CBACT04C'} - {@code app/cbl/CBACT04C.cbl:230}. */
    public static final String END_OF_EXECUTION = "END OF EXECUTION OF PROGRAM " + PROGRAM_ID;

    /**
     * {@code app/cbl/CBACT04C.cbl:245}. Note the text stops at "BALANCE" - it does not say "FILE".
     */
    public static final String ERROR_OPENING_TCATBALF =
            "ERROR OPENING TRANSACTION CATEGORY BALANCE";

    /**
     * {@code app/cbl/CBACT04C.cbl:263}.
     *
     * <p>Uniquely among the five opens, this {@code DISPLAY} names a second operand -
     * {@code DISPLAY 'ERROR OPENING CROSS REF FILE' XREFFILE-STATUS} - so the emitted line carries the
     * two-character file status appended directly to this text, with no separator. See
     * {@link InterestCalculationRun#xreffileOpen()}.
     */
    public static final String ERROR_OPENING_XREFFILE = "ERROR OPENING CROSS REF FILE";

    /**
     * {@code app/cbl/CBACT04C.cbl:281} - and <strong>the text names the wrong file</strong>.
     *
     * <p>{@code 0200-DISCGRP-OPEN} opens the disclosure group dataset, yet on failure it displays
     * {@code 'ERROR OPENING DALY REJECTS FILE'} - a message that belongs to {@code CBTRN02C}'s reject
     * file and has nothing to do with this dataset. It is reproduced verbatim under practice B5:
     * correcting it would change observable output, and an operator runbook keyed on the string would
     * stop matching.
     */
    public static final String ERROR_OPENING_DISCGRP = "ERROR OPENING DALY REJECTS FILE";

    /** {@code app/cbl/CBACT04C.cbl:300}. */
    public static final String ERROR_OPENING_ACCTFILE = "ERROR OPENING ACCOUNT MASTER FILE";

    /** {@code app/cbl/CBACT04C.cbl:318}. */
    public static final String ERROR_OPENING_TRANFILE = "ERROR OPENING TRANSACTION FILE";

    /** {@code app/cbl/CBACT04C.cbl:342}. */
    public static final String ERROR_READING_TCATBALF = "ERROR READING TRANSACTION CATEGORY FILE";

    /** {@code app/cbl/CBACT04C.cbl:365}. */
    public static final String ERROR_REWRITING_ACCTFILE = "ERROR RE-WRITING ACCOUNT FILE";

    /** {@code app/cbl/CBACT04C.cbl:386}. */
    public static final String ERROR_READING_ACCTFILE = "ERROR READING ACCOUNT FILE";

    /** {@code app/cbl/CBACT04C.cbl:408}. */
    public static final String ERROR_READING_XREFFILE = "ERROR READING XREF FILE";

    /** {@code app/cbl/CBACT04C.cbl:431}. */
    public static final String ERROR_READING_DISCGRP = "ERROR READING DISCLOSURE GROUP FILE";

    /** {@code app/cbl/CBACT04C.cbl:455}. */
    public static final String ERROR_READING_DEFAULT_DISCGRP =
            "ERROR READING DEFAULT DISCLOSURE GROUP";

    /** {@code app/cbl/CBACT04C.cbl:510}. */
    public static final String ERROR_WRITING_TRANSACTION = "ERROR WRITING TRANSACTION RECORD";

    /** {@code app/cbl/CBACT04C.cbl:533}. */
    public static final String ERROR_CLOSING_TCATBALF = "ERROR CLOSING TRANSACTION BALANCE FILE";

    /** {@code app/cbl/CBACT04C.cbl:552}. */
    public static final String ERROR_CLOSING_XREFFILE = "ERROR CLOSING CROSS REF FILE";

    /** {@code app/cbl/CBACT04C.cbl:570}. */
    public static final String ERROR_CLOSING_DISCGRP = "ERROR CLOSING DISCLOSURE GROUP FILE";

    /** {@code app/cbl/CBACT04C.cbl:588}. */
    public static final String ERROR_CLOSING_ACCTFILE = "ERROR CLOSING ACCOUNT FILE";

    /** {@code app/cbl/CBACT04C.cbl:606}. */
    public static final String ERROR_CLOSING_TRANFILE = "ERROR CLOSING TRANSACTION FILE";

    /**
     * {@code DISPLAY 'ACCOUNT NOT FOUND: ' FD-ACCT-ID} - {@code app/cbl/CBACT04C.cbl:375}, and again at
     * {@code :397} for {@code FD-XREF-ACCT-ID}.
     *
     * <p>The literal ends with a space, which is part of it: COBOL {@code DISPLAY} concatenates its
     * operands with no separator of its own, so the emitted line is this text followed immediately by the
     * key's eleven-digit display image.
     */
    public static final String ACCOUNT_NOT_FOUND_PREFIX = "ACCOUNT NOT FOUND: ";

    /** {@code DISPLAY 'DISCLOSURE GROUP RECORD MISSING'} - {@code app/cbl/CBACT04C.cbl:418}. */
    public static final String DISCLOSURE_GROUP_RECORD_MISSING = "DISCLOSURE GROUP RECORD MISSING";

    /** {@code DISPLAY 'TRY WITH DEFAULT GROUP CODE'} - {@code app/cbl/CBACT04C.cbl:419}. */
    public static final String TRY_WITH_DEFAULT_GROUP_CODE = "TRY WITH DEFAULT GROUP CODE";

    // =================================================================================================
    // The generated transaction's literal field values - app/cbl/CBACT04C.cbl:482-489.
    // =================================================================================================

    /** {@code MOVE '01' TO TRAN-TYPE-CD} - {@code app/cbl/CBACT04C.cbl:482}, into {@code PIC X(02)}. */
    public static final String GENERATED_TRAN_TYPE_CD = "01";

    /**
     * {@code MOVE '05' TO TRAN-CAT-CD} - {@code app/cbl/CBACT04C.cbl:483}.
     *
     * <p>An alphanumeric literal into {@code TRAN-CAT-CD PIC 9(04)}, so it is stored zero-filled on the
     * left as {@code 0005}. The digits are carried as text and moved through the numeric mover, which is
     * where that padding rule lives.
     */
    public static final String GENERATED_TRAN_CAT_CD = "05";

    /**
     * {@code MOVE 'System' TO TRAN-SOURCE} - {@code app/cbl/CBACT04C.cbl:484}.
     *
     * <p>Six characters into {@code PIC X(10)}, so the stored value is {@code "System    "}. The padding
     * is applied by the alphanumeric mover, not written into this literal.
     */
    public static final String GENERATED_TRAN_SOURCE = "System";

    /**
     * {@code STRING 'Int. for a/c ' , ACCT-ID ... INTO TRAN-DESC} - {@code app/cbl/CBACT04C.cbl:485-489}.
     *
     * <p>Thirteen characters, the trailing space included, followed by {@code ACCT-ID}'s eleven-digit
     * image: 24 characters into a 100-byte field whose remaining 76 bytes are <strong>not</strong>
     * blanked. See the residue note in this class's documentation.
     */
    public static final String GENERATED_TRAN_DESC_PREFIX = "Int. for a/c ";

    /**
     * {@code MOVE 'DEFAULT' TO FD-DIS-ACCT-GROUP-ID} - {@code app/cbl/CBACT04C.cbl:437}.
     *
     * <p>Seven characters into {@code PIC X(10)}, so the retry's key carries {@code "DEFAULT   "}. The
     * value is taken from {@link DisclosureGroupRecord#DEFAULT_ACCT_GROUP_ID} rather than restated, and
     * the padding is the mover's job.
     */
    public static final String DEFAULT_ACCT_GROUP_ID = DisclosureGroupRecord.DEFAULT_ACCT_GROUP_ID;

    // =================================================================================================
    // The DB2 timestamp - app/cbl/CBACT04C.cbl:140-165 and :613-626.
    // =================================================================================================

    /**
     * The width of {@code DB2-FORMAT-TS PIC X(26)}: {@value}.
     *
     * <p>The redefinition at {@code :151-165} accounts for every one of the 26 bytes -
     * {@code 4 + 1 + 2 + 1 + 2 + 1 + 2 + 1 + 2 + 1 + 2 + 1 + 2 + 4} - and
     * {@code Z-GET-DB2-FORMAT-TIMESTAMP} writes all of them, so the composed value is fully determined
     * and never carries residue.
     */
    public static final int DB2_TIMESTAMP_LENGTH = 26;

    /**
     * {@code MOVE '-' TO DB2-STREEP-1 DB2-STREEP-2 DB2-STREEP-3} - {@code app/cbl/CBACT04C.cbl:623}.
     *
     * <p>Three hyphens, not two. The <strong>third</strong> separator - between the day and the hour - is
     * a hyphen and not a space or a {@code 'T'}: this is the DB2 for z/OS internal timestamp format, not
     * ISO-8601, and the comment at {@code :140} spells the shape out.
     */
    public static final char DB2_TIMESTAMP_HYPHEN = '-';

    /** {@code MOVE '.' TO DB2-DOT-1 DB2-DOT-2 DB2-DOT-3} - {@code app/cbl/CBACT04C.cbl:624}. */
    public static final char DB2_TIMESTAMP_DOT = '.';

    /** {@code MOVE '0000' TO DB2-REST} - {@code app/cbl/CBACT04C.cbl:622}, filling {@code DB2-REST X(04)}. */
    public static final String DB2_TIMESTAMP_REST = "0000";

    /**
     * The width of {@code COBOL-TS}, the receiver of {@code FUNCTION CURRENT-DATE}: {@value}.
     *
     * <p>{@code :141-149} declares {@code X(04)} for the year, six {@code X(02)} items and a trailing
     * {@code X(05)} for the offset from Greenwich - 21 characters, which is exactly what the intrinsic
     * returns.
     */
    public static final int COBOL_TIMESTAMP_LENGTH = 21;

    // =================================================================================================
    // APPL-RESULT values and the END-OF-FILE flag - app/cbl/CBACT04C.cbl:133-137.
    //
    // Every value is taken from a collaborator that already declares it rather than restated, so there is
    // one definition of each number in the module.
    // =================================================================================================

    /** {@code 88 APPL-AOK VALUE 0} - {@code app/cbl/CBACT04C.cbl:134}. */
    public static final int APPL_AOK = FileStatus.APPL_AOK;

    /** {@code 88 APPL-EOF VALUE 16} - {@code app/cbl/CBACT04C.cbl:135}. */
    public static final int APPL_EOF = FileStatus.APPL_EOF;

    /**
     * The {@code MOVE 8 TO APPL-RESULT} every open and close paragraph begins with.
     *
     * <p>A pessimistic pre-set: the verb has not run yet, so the register says "assume it failed". Only
     * a {@code '00'} status replaces it with {@link #APPL_AOK}.
     */
    public static final int APPL_RESULT_ASSUMED_FAILURE = AbendException.RETURN_CODE_ASSUMED_FAILURE;

    /** The {@code MOVE 12 TO APPL-RESULT} of every failure arm, and so the abend's return code. */
    public static final int APPL_RESULT_FATAL = AbendException.RETURN_CODE_IO_ERROR;

    /** {@code 01 END-OF-FILE PIC X(01) VALUE 'N'} - {@code app/cbl/CBACT04C.cbl:137}. */
    public static final String END_OF_FILE_NO = "N";

    /** {@code MOVE 'Y' TO END-OF-FILE} - {@code app/cbl/CBACT04C.cbl:340}. */
    public static final String END_OF_FILE_YES = "Y";

    /** {@code 05 WS-FIRST-TIME PIC X(01) VALUE 'Y'} - {@code app/cbl/CBACT04C.cbl:170}. */
    public static final String FIRST_TIME_YES = "Y";

    /** {@code MOVE 'N' TO WS-FIRST-TIME} - {@code app/cbl/CBACT04C.cbl:198}. */
    public static final String FIRST_TIME_NO = "N";

    /** {@code 05 WS-LAST-ACCT-NUM PIC X(11) VALUE SPACES} - {@code app/cbl/CBACT04C.cbl:167}. */
    public static final int LAST_ACCT_NUM_LENGTH = 11;

    /**
     * The initial content of {@code WS-LAST-ACCT-NUM}: eleven spaces.
     *
     * <p>This is what makes the first record always look like an account break. The comparison at
     * {@code :194} is {@code IF TRANCAT-ACCT-ID NOT= WS-LAST-ACCT-NUM} - a {@code PIC 9(11)} against a
     * {@code PIC X(11)} - so it is an alphanumeric comparison of the account id's eleven-digit display
     * image against these eleven spaces, which can never be equal.
     */
    public static final String LAST_ACCT_NUM_INITIAL = " ".repeat(LAST_ACCT_NUM_LENGTH);

    /** {@code 05 WS-RECORD-COUNT PIC 9(09) VALUE 0} - {@code app/cbl/CBACT04C.cbl:172}. */
    public static final long RECORD_COUNT_MODULUS = 1_000_000_000L;

    /**
     * {@code 05 WS-TRANID-SUFFIX PIC 9(06) VALUE 0} - {@code app/cbl/CBACT04C.cbl:173}.
     *
     * <p>Six digits, so the counter wraps at a million rather than overflowing: {@code ADD} without
     * {@code ON SIZE ERROR} discards the high-order digit, and no program here writes
     * {@code ON SIZE ERROR}. The 50-record fixture never approaches the bound; the modulus is applied
     * anyway so the field's declared width is what decides, not the {@code long} that holds it.
     */
    public static final long TRANID_SUFFIX_MODULUS = 1_000_000L;

    /** The width of {@code WS-TRANID-SUFFIX PIC 9(06)}, which is also its contribution to {@code TRAN-ID}. */
    public static final int TRANID_SUFFIX_WIDTH = 6;

    /**
     * The width of {@code PARM-DATE PIC X(10)} - {@code app/cbl/CBACT04C.cbl:178}.
     *
     * <p>Taken from {@link BatchConfig#PARM_DATE_WIDTH} rather than restated, and asserted against
     * {@link TranRecord#TRAN_ID_LENGTH} minus {@link #TRANID_SUFFIX_WIDTH} in
     * {@link #verifyDeclaredKeyGeometry()}: the {@code STRING} at {@code :476-480} fills
     * {@code TRAN-ID X(16)} exactly, and it only does so because 10 plus 6 is 16.
     */
    public static final int PARM_DATE_WIDTH = BatchConfig.PARM_DATE_WIDTH;


    // =================================================================================================
    // Declared geometry, asserted rather than trusted. The 16-versus-17 key confusion is invisible to a
    // record-width check, so it is checked directly and at class-initialisation time.
    // =================================================================================================

    static {
        verifyDeclaredKeyGeometry();
    }

    /**
     * Asserts the four geometric facts this program's correctness rests on.
     *
     * <p>Package-visible and returning a value so a unit test can invoke it directly rather than relying
     * on class initialisation having happened.
     *
     * <ol>
     *   <li>{@code DIS-GROUP-KEY} is 16 bytes. Seventeen would shift {@code DIS-INT-RATE} by one byte and
     *       silently corrupt every rate the job reads.</li>
     *   <li>{@code TRAN-CAT-KEY} is 17 bytes - the key the 16 is so easily confused with.</li>
     *   <li>{@code DIS-INT-RATE} begins at offset 16, immediately after the key, which is the same fact
     *       stated from the other side.</li>
     *   <li>{@code PARM-DATE X(10)} plus {@code WS-TRANID-SUFFIX 9(06)} is exactly
     *       {@code TRAN-ID X(16)}, which is why the {@code STRING} at {@code :476-480} fills the receiver
     *       with nothing left over.</li>
     * </ol>
     *
     * @return the disclosure group key length, 16, so the check has a value a test can assert on
     * @throws IllegalStateException if any declared width disagrees with its owning type
     */
    static int verifyDeclaredKeyGeometry() {
        if (DisclosureGroupRecord.DIS_GROUP_KEY_LENGTH != 16) {
            throw new IllegalStateException("DIS-GROUP-KEY is DIS-ACCT-GROUP-ID X(10) + "
                    + "DIS-TRAN-TYPE-CD X(02) + DIS-TRAN-CAT-CD 9(04) = 16 bytes "
                    + "[app/cpy/CVTRA02Y.cpy:5-8], but DisclosureGroupRecord declares "
                    + DisclosureGroupRecord.DIS_GROUP_KEY_LENGTH + ". A 17-byte disclosure key shifts "
                    + "DIS-INT-RATE by one byte and corrupts every interest rate this job reads.");
        }
        if (TranCatBalRecord.TRAN_CAT_KEY_LENGTH != 17) {
            throw new IllegalStateException("TRAN-CAT-KEY is TRANCAT-ACCT-ID 9(11) + "
                    + "TRANCAT-TYPE-CD X(02) + TRANCAT-CD 9(04) = 17 bytes [app/cpy/CVTRA01Y.cpy:5-8], "
                    + "but TranCatBalRecord declares " + TranCatBalRecord.TRAN_CAT_KEY_LENGTH
                    + ". This is the 17 that must never be confused with the disclosure group's 16.");
        }
        if (DisclosureGroupRecord.DIS_INT_RATE_OFFSET != DisclosureGroupRecord.DIS_GROUP_KEY_LENGTH) {
            throw new IllegalStateException("DIS-INT-RATE begins immediately after DIS-GROUP-KEY, at "
                    + "offset " + DisclosureGroupRecord.DIS_GROUP_KEY_LENGTH + ", but the record type "
                    + "places it at " + DisclosureGroupRecord.DIS_INT_RATE_OFFSET + '.');
        }
        if (PARM_DATE_WIDTH + TRANID_SUFFIX_WIDTH != TranRecord.TRAN_ID_LENGTH) {
            throw new IllegalStateException("STRING PARM-DATE, WS-TRANID-SUFFIX INTO TRAN-ID "
                    + "(app/cbl/CBACT04C.cbl:476-480) transfers " + PARM_DATE_WIDTH + " + "
                    + TRANID_SUFFIX_WIDTH + " characters into TRAN-ID X("
                    + TranRecord.TRAN_ID_LENGTH + "). Those must sum exactly: a short transfer would "
                    + "leave the receiver's tail bytes untouched, because COBOL STRING does not blank "
                    + "the remainder of its receiver.");
        }
        return DisclosureGroupRecord.DIS_GROUP_KEY_LENGTH;
    }

    // =================================================================================================
    // Collaborators. All final, all constructor-injected, none static (practice B9, gate G53).
    // =================================================================================================

    /** The batch scaffolding: job and step builders, the job repository and the transaction manager. */
    private final BatchConfig batchConfig;

    /** {@code TCATBALF} - the driving browse of {@code 1000-TCATBALF-GET-NEXT}. */
    private final TranCatBalRepository tranCatBalRepository;

    /** {@code ACCTFILE} - the {@code I-O} account master read by {@code 1100} and rewritten by {@code 1050}. */
    private final AccountRepository accountRepository;

    /** {@code XREFFILE} - the cross reference, read by {@code 1110} through its alternate index. */
    private final CardXrefRepository cardXrefRepository;

    /** {@code TRANSACT} - the sequential output written by {@code 1300-B-WRITE-TX}. */
    private final TransactionRepository transactionRepository;

    /** {@code DISCGRP} - the rate lookup of {@code 1200} and {@code 1200-A}. */
    private final DisclosureGroupAccess disclosureGroupAccess;

    /**
     * The persistence boundary for this program's two mutating verbs and for its output's disposition.
     *
     * <p>Reached only through {@link DatasetUnitOfWork#persistVerb(String, java.util.function.Supplier)}
     * and {@link DatasetUnitOfWork#persistDisposition(String, java.util.function.Supplier)} - never
     * through {@code execute}, which is the online task boundary - and that is the point.
     * {@code CBACT04C} is a non-CICS batch program: it issues no syncpoint, and every dataset it touches
     * is defined {@code RECOVERY(NONE)} ({@code app/csd/CARDDEMO.CSD:9} and siblings). So its
     * {@code REWRITE} at {@code app/cbl/CBACT04C.cbl:356} and its {@code WRITE} at {@code :500} are each
     * durable the moment they complete, and the abend at {@code :632} does not take them back.
     *
     * <p>Left to the step's own chunk transaction they would be undone instead - and specifically, an
     * account whose interest had been posted and whose cycle amounts had been zeroed would revert, so a
     * re-run would post that interest a second time. That is a financial difference, which is why this
     * boundary is here rather than inherited.
     *
     * <p>The step's two datasets then part company, which is what per-resource persistence means:
     * {@code ACCTFILE} is {@code DISP=SHR} over an existing dataset and keeps every rewrite, while
     * {@code TRANSACT} is {@code DISP=(NEW,CATLG,DELETE)} ({@code app/jcl/INTCALC.jcl:37}) and loses its
     * whole generation if the step abends. The second is applied through {@code persistDisposition},
     * because a disposition is the initiator's work and must outlive the failure that called for it.
     */
    private final DatasetUnitOfWork unitOfWork;

    /**
     * The dataset code page, stated explicitly and never taken from the platform.
     *
     * <p>Taken from the account repository, which resolves it from
     * {@value CobolCharsetConfig#DATASET_CHARSET_PROPERTY}, so every record this job decodes, builds and
     * writes agrees with every record its collaborators do.
     */
    private final Charset datasetCharset;

    /** The shared codec, for the pad, truncate and {@code DELIMITED BY SIZE} rules. */
    private final FixedWidthCodec codec;

    /** The {@code SYSOUT} destination. One seam, so a run's line sequence can be captured exactly. */
    private final SysoutSink sysoutSink;

    /**
     * The clock behind {@code FUNCTION CURRENT-DATE}.
     *
     * <p>Injected rather than read from the system, so a parity case can pin the two timestamps every
     * generated transaction carries and assert them byte for byte.
     *
     * <p><strong>Required, with no fallback.</strong> {@code WebConfig} declares exactly one
     * {@link Clock} bean unconditionally, so there is no context in which this job runs and no clock is
     * available - which means an optional parameter with a {@link Clock#systemDefaultZone()} default
     * could only ever take effect where the clock had been mis-wired, and then it would hide that fact
     * behind timestamps that look plausible and cannot be reproduced. Every one of the other thirteen
     * {@link Clock} consumers in this module takes it as a required constructor argument; this one does
     * too.
     */
    private final Clock clock;

    /** This job's validated {@code carddemo.jobs} step contract. */
    private final StepContract stepContract;

    /**
     * The {@code PARM} the JCL step card carries, as declared by the job contract.
     *
     * <p>{@code app/jcl/INTCALC.jcl:22} is the authority for it. A launcher may still supply its own
     * {@value BatchConfig#PARM_DATE_PARAMETER}, exactly as an operator may override a PARM at submission,
     * and {@link ChunkDelegate#beforeStep(StepExecution)} honours that when it is present.
     */
    private final String declaredParmDate;

    /**
     * Wires the job from its five datasets, the batch scaffolding and one optional seam.
     *
     * @param batchConfig           the batch scaffolding; required
     * @param tranCatBalRepository  {@code TCATBALF}; required
     * @param accountRepository     {@code ACCTFILE}; required
     * @param cardXrefRepository    {@code XREFFILE} and its alternate-index path; required
     * @param transactionRepository {@code TRANSACT}, the generated-transaction output; required
     * @param disclosureGroupAccess {@code DISCGRP}, the rate lookup; required
     * @param unitOfWork            the persistence boundary this program's two mutating verbs commit
     *                              through, one verb at a time; required
     * @param sysoutSinkProvider    the {@code SYSOUT} destination; may resolve to no bean, in which case
     *                              the process's standard output is used in the dataset code page
     * @param clock                 the clock behind {@code FUNCTION CURRENT-DATE}; required
     * @throws NullPointerException  if any required collaborator or provider is {@code null}
     * @throws IllegalStateException if this job's {@code carddemo.jobs} contract names another program,
     *                               gates its only step, or declares no {@code parmDate}
     */
    public AccountInterestCalcJob(BatchConfig batchConfig,
            TranCatBalRepository tranCatBalRepository,
            AccountRepository accountRepository,
            CardXrefRepository cardXrefRepository,
            TransactionRepository transactionRepository,
            DisclosureGroupAccess disclosureGroupAccess,
            DatasetUnitOfWork unitOfWork,
            ObjectProvider<SysoutSink> sysoutSinkProvider,
            Clock clock) {

        this.batchConfig = Objects.requireNonNull(batchConfig, "The batch scaffolding is required: the "
                + "job and step builders, the job repository and the transaction manager all arrive "
                + "through it, so this class holds no Spring Batch plumbing of its own");
        this.tranCatBalRepository = Objects.requireNonNull(tranCatBalRepository, "The transaction "
                + "category balance repository is required: " + TCATBALF_DD_NAME + " is the browse that "
                + "drives this program's only loop");
        this.accountRepository = Objects.requireNonNull(accountRepository, "The account master "
                + "repository is required: " + ACCTFILE_DD_NAME + " is opened I-O, read on every account "
                + "break and rewritten with the accumulated interest");
        this.cardXrefRepository = Objects.requireNonNull(cardXrefRepository, "The cross reference "
                + "repository is required: 1110-GET-XREF-DATA reads it by the ALTERNATE key to obtain "
                + "the card number every generated transaction carries");
        this.transactionRepository = Objects.requireNonNull(transactionRepository, "The transaction "
                + "repository is required: " + TRANSACT_DD_NAME + " is this job's generated-transaction "
                + "output, written sequentially at RECFM=F LRECL=" + TranRecord.RECORD_LENGTH);
        this.disclosureGroupAccess = Objects.requireNonNull(disclosureGroupAccess, "The disclosure group "
                + "access path is required: without a rate there is no interest to compute");
        this.unitOfWork = Objects.requireNonNull(unitOfWork, "A dataset unit of work is required: this "
                + "program's REWRITE at app/cbl/CBACT04C.cbl:356 and its WRITE at :500 are each durable "
                + "the moment they complete, because the program issues no syncpoint and every dataset "
                + "it touches is RECOVERY(NONE), so each is persisted on its own rather than left to a "
                + "chunk transaction a later abend would roll back");
        Objects.requireNonNull(sysoutSinkProvider, "A SYSOUT sink provider is required; it may resolve "
                + "to no bean, in which case the standard output stream is used");
        this.clock = Objects.requireNonNull(clock, "A Clock is required: FUNCTION CURRENT-DATE at "
                + "app/cbl/CBACT04C.cbl:L212 supplies the two timestamps every generated transaction "
                + "carries, so the clock is injected rather than defaulted - a job that silently fell "
                + "back to the system clock would write timestamps no parity case could pin");

        this.datasetCharset = accountRepository.datasetCharset();
        this.codec = new FixedWidthCodec(this.datasetCharset);
        this.sysoutSink = sysoutSinkProvider.getIfAvailable(() -> standardOutput(this.datasetCharset));
        this.stepContract = requireUngatedStep(batchConfig);
        this.declaredParmDate = requireDeclaredParmDate(batchConfig);
        requireStepDatasets(batchConfig);
    }

    /**
     * Proves that every DD name {@code app/jcl/INTCALC.jcl:25-41} declares resolves to the dataset the
     * repository that reads it is actually bound to.
     *
     * <p><strong>Why this is not ceremony.</strong> This step names five input DDs and one output DD, and
     * before this check not one of them was resolved against configuration here. Two were worse than
     * unresolved: {@link #XREFFILE_DD_NAME} was defined as {@code CardXrefRepository.BASE_DD_NAME} - the
     * CICS file name {@code CCXREF}, a different configuration key with its own independent override - so
     * the job named, and would have reported, a binding its JCL never mentions; and
     * {@link #XREFFIL1_DD_NAME} had no constant at all, so the alternate-index path this step explicitly
     * opens a second time was never checked in any form.
     *
     * <p>{@code XREFFILE} and {@code XREFFIL1} are two access paths over one dataset, so they are proven
     * against the base cluster and the alternate-index path respectively - which is also what keeps this
     * from becoming a second repository per DD name and duplicating the cluster (gate G45).
     *
     * <p>The output DD is checked the same way, because {@code TRANSACT} is a genuine collision in this
     * estate: it is a CICS file in the online programs and a {@code DISP=(NEW,CATLG,DELETE)} generation
     * in {@code app/jcl/INTCALC.jcl:37-41}. The repository keeps separate keys for exactly that reason,
     * and this proves the step is writing through the one its JCL declares.
     *
     * @param scaffolding the batch scaffolding holding the contracts and the DD catalogue
     * @throws IllegalStateException if any DD is undeclared, or resolves to a different dataset from the
     *                               repository that reads or writes it
     */
    private static void requireStepDatasets(BatchConfig scaffolding) {
        scaffolding.requireSameDataset(JOB_KEY, TCATBALF_DD_NAME, TranCatBalRepository.DD_NAME);
        // The account repository binds to the CICS file name, because the online programs address it that
        // way; ACCTFILE is this step's own DD name and a separate configuration key.
        scaffolding.requireSameDataset(JOB_KEY, ACCTFILE_DD_NAME, AccountRepository.CICS_FILE_NAME);
        scaffolding.requireSameDataset(JOB_KEY, XREFFILE_DD_NAME, CardXrefRepository.BASE_DD_NAME);
        scaffolding.requireSameDataset(JOB_KEY, XREFFIL1_DD_NAME,
                CardXrefRepository.ALTERNATE_INDEX_DD_NAME);
        scaffolding.requireSameDataset(JOB_KEY, TRANSACT_DD_NAME,
                TransactionRepository.SEQUENTIAL_OUTPUT_DD_NAME);
    }

    /**
     * Validates this job's step contract: it must be exactly {@link #REQUIRED_STEPS}.
     *
     * <p>{@code app/jcl/INTCALC.jcl} declares one step and carries no {@code COND}, so gating the only
     * step of a single-step job would bypass all of its work. The two guards here name that case and
     * the re-pointed-program case specifically; the sequence comparison behind them is what rejects an
     * added, omitted or reordered step, which no per-step check can see.
     *
     * @param scaffolding the batch scaffolding holding the parsed contracts
     * @return the validated step contract
     * @throws IllegalStateException if the contract names another program, gates the step, or declares
     *                               any sequence other than {@link #REQUIRED_STEPS}
     */
    private static StepContract requireUngatedStep(BatchConfig scaffolding) {
        StepContract contract = scaffolding.contract(JOB_KEY).step(STEP_NAME);
        if (!PROGRAM_ID.equals(contract.program())) {
            throw new IllegalStateException("The carddemo.jobs contract for '" + JOB_KEY + "' declares "
                    + "step '" + STEP_NAME + "' running program '" + contract.program() + "', but this "
                    + "job is the translation of " + PROGRAM_ID + " (app/jcl/INTCALC.jcl:22). A step "
                    + "that named another program would resolve another program's DD names.");
        }
        if (contract.requirePrecedingExitCodeZero()) {
            throw new IllegalStateException("The carddemo.jobs contract for '" + JOB_KEY + "' gates step "
                    + "'" + STEP_NAME + "' on a preceding exit code, but app/jcl/INTCALC.jcl carries no "
                    + "COND and declares only this step. Gating the only step of a single-step job "
                    + "would bypass all of its work.");
        }
        scaffolding.requireSteps(JOB_KEY, REQUIRED_STEPS, "app/jcl/INTCALC.jcl:22");
        return contract;
    }

    /**
     * Extracts the declared {@code parmDate} from this job's contract, in {@code PIC X(10)} form.
     *
     * <p>The value is moved into a ten-character field exactly as {@code PARM-DATE PIC X(10)} receives
     * it, so a shorter configured value is space-padded on the right and a longer one truncated on the
     * right - the alphanumeric {@code MOVE} rule, applied here at the boundary where the JCL PARM enters
     * the program rather than later where the truncation would be invisible.
     *
     * @param scaffolding the batch scaffolding holding the parsed contracts
     * @return the ten-character {@code PARM-DATE}
     * @throws IllegalStateException if the contract declares no {@code parmDate}
     */
    private String requireDeclaredParmDate(BatchConfig scaffolding) {
        JobParameters declared = scaffolding.contract(JOB_KEY).jobParameters();
        String configured = declared.getString(BatchConfig.PARM_DATE_PARAMETER);
        if (configured == null) {
            throw new IllegalStateException("The carddemo.jobs contract for '" + JOB_KEY + "' declares no "
                    + BatchConfig.PARM_DATE_PARAMETER + " parameter, but app/jcl/INTCALC.jcl:22 is "
                    + "EXEC PGM=" + PROGRAM_ID + ",PARM='2022071800' and app/cbl/CBACT04C.cbl:476-480 "
                    + "concatenates that value into every transaction identifier this job writes. "
                    + "Declare carddemo.jobs." + JOB_KEY + ".parameters with name: "
                    + BatchConfig.PARM_DATE_PARAMETER + '.');
        }
        return codec.movePicX(configured, PARM_DATE_WIDTH);
    }


    // =================================================================================================
    // The DISCGRP access path, published as a bean.
    //
    // STATIC deliberately. This configuration class's own constructor consumes a DisclosureGroupAccess,
    // so a non-static factory would require the configuration instance in order to build one of its own
    // constructor arguments. A static @Bean method needs no instance and breaks that cycle at the point
    // where it would otherwise form.
    // =================================================================================================

    /**
     * The {@code DISCGRP} dataset, reached over JDBC as a keyed single-column record-image relation -
     * the same shape every sibling repository uses.
     *
     * <p>{@code app/cpy/CVTRA02Y.cpy} has exactly one consumer in the estate and it is this program, so
     * the access path is published from here rather than from a repository no other class could use. The
     * dataset name is resolved from {@code carddemo.datasets.}{@value #DISCGRP_DD_NAME} and never written
     * in Java (gate G46), and the key width it addresses by comes from
     * {@link DisclosureGroupRecord#DIS_GROUP_KEY_LENGTH} - the 16 that this whole file turns on.
     *
     * @param jdbcTemplate    the module's shared template
     * @param datasetBindings the DD-name-keyed dataset catalogue bound from {@code carddemo.datasets}
     * @param datasetCharset  the active dataset code page, selected by bean name so the choice is
     *                        explicit at the injection point
     * @param recordImageForm how this deployment's driver presents a record image over JDBC
     * @return the access path; never {@code null}
     * @throws NullPointerException  if any argument is {@code null}
     * @throws IllegalStateException if {@value #DISCGRP_DD_NAME} is unconfigured, or its binding declares
     *                               a record or key width other than the copybook's
     */
    @Bean
    public static DisclosureGroupAccess carddemoDisclosureGroupAccess(
            JdbcTemplate jdbcTemplate,
            DatasetBindings datasetBindings,
            @Qualifier(CobolCharsetConfig.DATASET_CHARSET_BEAN_NAME) Charset datasetCharset,
            RecordImageForm recordImageForm) {
        return new JdbcDisclosureGroupAccess(jdbcTemplate, datasetBindings, datasetCharset,
                recordImageForm);
    }

    // =================================================================================================
    // The Spring Batch surface: one job, one chunk-oriented step of CHUNK_SIZE items.
    //
    // Only the Job and the DISCGRP access path are beans. The step and the chunk delegate are ordinary
    // methods, deliberately: ten sibling job classes live in this module, and a published Step,
    // ItemReader or ItemWriter bean from each would make every by-type injection of those interfaces
    // ambiguous at once.
    // =================================================================================================

    /**
     * The job {@code app/jcl/INTCALC.jcl} submits: one step, no gate, and one validated parameter.
     *
     * <p>{@code spring.batch.job.enabled} is {@code false} in {@code application.yml}, so publishing this
     * bean does not run it. It runs when something deliberately launches it, exactly as it ran only when
     * JCL submitted {@code STEP15}.
     *
     * <h2>How this job's one parameter is validated, and why not from here</h2>
     * <p>This is the one job in the estate that takes a {@code PARM}
     * ({@code app/jcl/INTCALC.jcl:L22}, {@code PARM='2022071800'}), and that {@code PARM} is character
     * data which {@code app/cbl/CBACT04C.cbl:L476-L480} concatenates <em>verbatim</em> into every
     * transaction identifier the job generates. A launcher-supplied value of the wrong width is
     * therefore not a cosmetic problem: nine characters shift the generated suffix left in every
     * identifier written, eleven push it off the end of the {@code PIC X(16)} field, and neither would
     * fail at run time. The width is rejected <em>before the job starts</em>, so a bad launch produces
     * no records at all rather than a full generation of subtly wrong ones.
     *
     * <p>No validator is attached here, deliberately, and that is a correction rather than an omission.
     * {@link BatchConfig#job(String)} attaches {@link BatchConfig#jclParametersValidator(String)} to
     * every job, and for this job that validator applies the same
     * {@link BatchConfig#PARM_DATE_WIDTH} rule <em>and</em> the allow-list that refuses an undeclared
     * parameter beside {@code parmDate}. A second {@code .validator(...)} call on the builder would
     * replace it rather than add to it - {@code JobBuilder} keeps one validator - which would have
     * narrowed this job's checking to the width alone and let an undeclared key through, resolving the
     * submission to a silently different job instance.
     *
     * <p>What is checked is the width and nothing else. The value is not parsed as a date, reformatted
     * or defaulted - all three would corrupt the identifiers just as surely (rule R1, gate G29).
     *
     * @return the job; never {@code null}
     */
    @Bean
    public Job accountInterestCalcJob() {
        return batchConfig.job(JOB_NAME)
                // Restart is refused, and that is a parity decision rather than a policy one. A JCL step
                // has no restart: an operator who re-runs INTCALC submits the job again, which is a NEW
                // run over the whole TCATBALF file that writes a NEW SYSTRAN generation
                // (app/jcl/INTCALC.jcl:37-41 is DISP=(NEW,CATLG,DELETE)). Spring Batch's restart is a
                // different thing entirely - it resumes the SAME JobInstance from the item after the last
                // commit - and this program stores no position in its execution context, so a resumed run
                // would re-read TCATBALF from the beginning while the accounts it had already rewritten
                // stayed rewritten. Every account before the failure point would have its interest posted
                // a second time. Refusing the restart is what keeps that from being reachable at all.
                .preventRestart()
                .start(accountInterestCalcStep())
                .build();
    }

    /**
     * The single step, chunk-oriented at a commit interval of {@value #CHUNK_SIZE}.
     *
     * <p>One delegate instance is the reader, the processor, the writer and the step listener, because
     * all four need the same {@code WORKING-STORAGE} and the same five open files - which is what a COBOL
     * program is. Registering it as a stream is what gives the {@code OPEN} and {@code CLOSE} paragraphs
     * their lifecycle: {@link ChunkDelegate#open(ExecutionContext)} runs them at the start of the step
     * execution and {@link ChunkDelegate#close()} at the end. Registering it as a step listener is what
     * lets a launcher's own {@value BatchConfig#PARM_DATE_PARAMETER} reach the program, since
     * {@code beforeStep} is invoked before the stream is opened.
     *
     * <p>The reader, processor, writer, stream and listener registered here are all one
     * {@link StepScopedChunkDelegate}, which builds a fresh {@link ChunkDelegate} for each step execution
     * rather than closing over one. A {@code Step} bean is built once and then launched as often as the
     * operator launches the job, so a delegate captured here would be shared by every execution -
     * including two that overlap. {@code CBACT04C} has no such sharing to reproduce: each JCL submission
     * is its own address space with its own {@code WORKING-STORAGE} and its own five open files.
     *
     * @return the step; never {@code null}
     */
    public Step accountInterestCalcStep() {
        StepScopedChunkDelegate delegate = new StepScopedChunkDelegate(this);
        SimpleStepBuilder<TranCatBalRecord, RecordOutcome> builder =
                batchConfig.chunkStep(stepContract.name(), CHUNK_SIZE);
        builder.reader(delegate).processor(delegate).writer(delegate).stream(delegate);
        builder.listener((StepExecutionListener) delegate);
        return builder.build();
    }

    /**
     * A fresh chunk delegate over this job.
     *
     * <p>Exposed so a unit test can drive the reader, processor and writer contract directly - including
     * the stream lifecycle - with no {@code JobLauncher} and no application context.
     *
     * @return a new delegate; never {@code null}
     */
    public ChunkDelegate newChunkDelegate() {
        return new ChunkDelegate(this);
    }

    // =================================================================================================
    // Read-only accessors. Diagnostics and assertions, never a way to reach around this class.
    // =================================================================================================

    /**
     * The job parameters {@code app/jcl/INTCALC.jcl:22} declares: the single
     * {@value BatchConfig#PARM_DATE_PARAMETER} string.
     *
     * @return the declared parameters; never {@code null}
     */
    public JobParameters jobParameters() {
        return batchConfig.contract(JOB_KEY).jobParameters();
    }

    /**
     * The cross-reference repository addressing <strong>this job's</strong> two cross-reference DDs.
     *
     * <p>{@code app/jcl/INTCALC.jcl} STEP15 opens the cross-reference cluster twice in one step:
     * {@code //XREFFILE} on the base KSDS at {@code :29-30} and {@code //XREFFIL1} on the
     * alternate-index path at {@code :31-32}. {@code app/cbl/CBACT04C.cbl:34-39} is one {@code SELECT}
     * with an {@code ALTERNATE RECORD KEY}, so both are read - the base sequentially by
     * {@code 1050-GET-NEXT-XREF} and the path by key in {@code 1500-A-LOOKUP-XREF}.
     *
     * <p>Both are resolved through this job's own view of the catalogue and handed to the repository, so
     * the DDs the JCL declares are the ones read. The injected repository resolved the <em>online</em>
     * names {@code CCXREF} and {@code CXACAIX} from the global catalogue; the finding was that this job
     * named the JCL's DDs and then read through those instead, which made the JCL's DD statements
     * decorative.
     *
     * <p>Resolved per call rather than held, because this class keeps no I/O state (practice B9), and
     * the repository returns itself when both bindings name the datasets it already addresses.
     *
     * @return the repository this run reads the cross-reference through; never {@code null}
     * @throws IllegalStateException if either DD is unconfigured, or a binding is unusable
     */
    private CardXrefRepository xrefFileRepository() {
        return cardXrefRepository.addressing(
                batchConfig.datasetBinding(JOB_KEY, XREFFILE_DD_NAME), XREFFILE_DD_NAME,
                batchConfig.datasetBinding(JOB_KEY, XREFFIL1_DD_NAME), XREFFIL1_DD_NAME);
    }

    /**
     * This job's validated step contract.
     *
     * @return the contract; never {@code null}
     */
    public StepContract stepContract() {
        return stepContract;
    }

    /**
     * The {@code PARM-DATE} the JCL step card declares, already in {@code PIC X(10)} form.
     *
     * @return exactly {@value #PARM_DATE_WIDTH} characters; never {@code null}
     */
    public String declaredParmDate() {
        return declaredParmDate;
    }

    /**
     * The {@code SYSOUT} destination this job writes to when nothing overrides it.
     *
     * @return the sink; never {@code null}
     */
    public SysoutSink sysoutSink() {
        return sysoutSink;
    }

    /**
     * The dataset code page every record this job touches is encoded in.
     *
     * @return the charset; never {@code null}
     */
    public Charset datasetCharset() {
        return datasetCharset;
    }

    /**
     * The clock behind {@code FUNCTION CURRENT-DATE}.
     *
     * @return the clock; never {@code null}
     */
    public Clock clock() {
        return clock;
    }

    // =================================================================================================
    // PROCEDURE DIVISION USING EXTERNAL-PARMS - app/cbl/CBACT04C.cbl:180-232.
    //
    // Runnable with no application context, no JobLauncher and no HTTP layer (practice B10, gate G51),
    // so every branch below is reachable from a plain unit test and the arithmetic is reachable by the
    // parity harness.
    // =================================================================================================

    /**
     * Runs the whole program against the declared {@code PARM} and the injected {@code SYSOUT} sink.
     *
     * @return the number of {@code TCATBALF} records processed, which is {@code WS-RECORD-COUNT} at
     *         {@code GOBACK}
     * @throws AbendException if any file operation the program checks reports a status it treats as fatal
     */
    public long calculateInterest() {
        return calculateInterest(declaredParmDate, sysoutSink);
    }

    /**
     * Runs the whole program: {@code app/cbl/CBACT04C.cbl:181-232}, statement for statement.
     *
     * <pre>
     * DISPLAY 'START OF EXECUTION OF PROGRAM CBACT04C'.                                        L181
     * PERFORM 0000-TCATBALF-OPEN.                                                              L182
     * PERFORM 0100-XREFFILE-OPEN.                                                              L183
     * PERFORM 0200-DISCGRP-OPEN.                                                               L184
     * PERFORM 0300-ACCTFILE-OPEN.                                                              L185
     * PERFORM 0400-TRANFILE-OPEN.                                                              L186
     * PERFORM UNTIL END-OF-FILE = 'Y'                                                          L188
     *     IF  END-OF-FILE = 'N'                                                                L189
     *         PERFORM 1000-TCATBALF-GET-NEXT                                                   L190
     *         IF  END-OF-FILE = 'N'                                                            L191
     *           ... the record body ...                                                   L192-L217
     *         END-IF                                                                           L218
     *     ELSE                                                                                 L219
     *          PERFORM 1050-UPDATE-ACCOUNT                                                     L220
     *     END-IF                                                                               L221
     * END-PERFORM.                                                                             L222
     * PERFORM 9000-TCATBALF-CLOSE.                                                             L224
     * PERFORM 9100-XREFFILE-CLOSE.                                                             L225
     * PERFORM 9200-DISCGRP-CLOSE.                                                              L226
     * PERFORM 9300-ACCTFILE-CLOSE.                                                             L227
     * PERFORM 9400-TRANFILE-CLOSE.                                                             L228
     * DISPLAY 'END OF EXECUTION OF PROGRAM CBACT04C'.                                          L230
     * GOBACK.                                                                                  L232
     * </pre>
     *
     * <p><strong>The {@code ELSE} at {@code L219-L221} is unreachable and stays.</strong> The loop guard
     * has already established that the flag is not {@code 'Y'} and the program moves nothing but
     * {@code 'N'} and {@code 'Y'} into it, so the last account group's accumulated interest is never
     * written back. That is the legacy behaviour and this method adds no end-of-file flush; see this
     * class's documentation. The branch is present, in the position the COBOL puts it, and is reachable
     * from a test through {@link WorkingStorage#moveEndOfFile(String)} exactly as it would be reachable
     * in COBOL through a third value.
     *
     * <p>The loop test and the guard immediately inside it are <em>different</em> tests in COBOL - one
     * asks whether the flag is {@code 'Y'}, the other whether it is {@code 'N'} - so they are two
     * different predicates here as well, rather than one predicate used twice.
     *
     * <p>The five opens and the five closes are performed in the source's order, which is observable: the
     * message a failure emits names the file, so a different order would emit a different message.
     *
     * @param parmDate the {@code PARM-DATE} to concatenate into the generated transaction identifiers;
     *                 taken verbatim, never parsed, and moved into {@code PIC X(10)} on the way in
     * @param sysout   where the displayed lines go; the whole of this program's non-dataset output
     * @return {@code WS-RECORD-COUNT} at {@code GOBACK}
     * @throws NullPointerException if {@code parmDate} or {@code sysout} is {@code null}
     * @throws AbendException       if any file operation the program checks reports a status it treats as
     *                              fatal, carrying {@link #APPL_RESULT_FATAL} as the return code
     */
    public long calculateInterest(String parmDate, SysoutSink sysout) {
        InterestCalculationRun run = newRun(parmDate, sysout);
        boolean normalEnd = false;
        try {
            // DISPLAY 'START OF EXECUTION OF PROGRAM CBACT04C'.                                     L181
            run.sysout().write(START_OF_EXECUTION);

            // PERFORM 0000-TCATBALF-OPEN through 0400-TRANFILE-OPEN, in order.                 L182-L186
            run.openFiles();

            // PERFORM UNTIL END-OF-FILE = 'Y'                                                       L188
            while (!run.workingStorage().endOfFileIsYes()) {
                if (run.workingStorage().endOfFileIsNo()) {                                     //   L189
                    // PERFORM 1000-TCATBALF-GET-NEXT                                                L190
                    TranCatBalRecord item = run.tcatbalfGetNext();

                    // IF END-OF-FILE = 'N'                                                          L191
                    if (run.workingStorage().endOfFileIsNo()) {
                        // The record body, L192-L217.
                        run.processRecord(item);
                    }
                } else {
                    // UNREACHABLE. app/cbl/CBACT04C.cbl:219-221. The loop guard above already
                    // established that END-OF-FILE is not 'Y', and 1000-TCATBALF-GET-NEXT moves nothing
                    // but 'Y' into it, so this arm cannot run and the final account group is never
                    // rewritten. PRESERVED ON PURPOSE (practice B5): adding an end-of-file flush here
                    // would alter the account master on every run and fail every parity case for this
                    // program. Do not "fix" it.
                    run.updateAccount();                                                        //   L220
                }
            }

            // PERFORM 9000-TCATBALF-CLOSE through 9400-TRANFILE-CLOSE, in order.               L224-L228
            run.closeFiles();

            // DISPLAY 'END OF EXECUTION OF PROGRAM CBACT04C'.                                       L230
            run.sysout().write(END_OF_EXECUTION);

            // GOBACK. RETURN-CODE is untouched on a normal end.                                     L232
            normalEnd = true;
            return run.workingStorage().recordCount();
        } finally {
            // An abend leaves the COBOL CLOSE paragraphs unperformed - which is why the closes above are
            // inside the try and not here. This releases the handles without emitting anything, so a
            // failed run's SYSOUT ends at the abend exactly as the mainframe's would.
            //
            // A run that did not reach GOBACK also has the TRANSACT DD's abnormal disposition applied:
            // app/jcl/INTCALC.jcl:37 is DISP=(NEW,CATLG,DELETE), so the generation is catalogued only on a
            // normal end and is deleted otherwise. The account rewrites are untouched either way, because
            // ACCTFILE is DISP=SHR over an existing dataset.
            if (normalEnd) {
                run.release();
            } else {
                run.releaseAbnormally();
            }
        }
    }

    /**
     * Allocates a fresh run: the {@code WORKING-STORAGE} of one execution, and nothing shared with any
     * other.
     *
     * <p>Package-visible so a unit test can drive the paragraphs individually, which is how the guard
     * chain of each is covered without contriving a dataset failure for every one of them.
     *
     * @param parmDate the {@code PARM-DATE}, moved into {@code PIC X(10)} here
     * @param sysout   the {@code SYSOUT} destination
     * @return a new run; never {@code null}
     * @throws NullPointerException if either argument is {@code null}
     */
    InterestCalculationRun newRun(String parmDate, SysoutSink sysout) {
        Objects.requireNonNull(parmDate, "A PARM-DATE is required: app/cbl/CBACT04C.cbl:476-480 "
                + "concatenates it into every transaction identifier this program writes, so a run "
                + "without one could not build an identifier at all");
        Objects.requireNonNull(sysout, "A SYSOUT sink is required: the displayed line sequence is this "
                + "program's observable output beyond its datasets, so there is nothing to run without "
                + "somewhere to write it");
        return new InterestCalculationRun(this, codec.movePicX(parmDate, PARM_DATE_WIDTH), sysout);
    }

    // =================================================================================================
    // 1300-COMPUTE-INTEREST's arithmetic and 1400-COMPUTE-FEES, as methods of this class rather than of
    // the run: neither touches WORKING-STORAGE, and hoisting them here is what lets a parity test assert
    // the formula with no files, no run and no framework in the way (gate G51).
    // =================================================================================================

    /**
     * {@code COMPUTE WS-MONTHLY-INT = ( TRAN-CAT-BAL * DIS-INT-RATE) / 1200} -
     * {@code app/cbl/CBACT04C.cbl:464-465}, and gate G25.
     *
     * <p>There is no {@code ROUNDED} phrase and no {@code ON SIZE ERROR} phrase on that statement, and
     * the receiver is {@code WS-MONTHLY-INT PIC S9(09)V99} ({@code :168}). So the product is formed
     * exactly - scale 2 times scale 2 is an exact scale-4 value - the division by 1200 is the last
     * operation before the store, and the store <strong>truncates</strong> to two decimal places.
     * {@link CobolDecimal#monthlyInterest(BigDecimal, BigDecimal)} is where that sequence lives; this
     * method exists so the call site carries the source citation and so the formula has a name a test can
     * assert against.
     *
     * <p>Worked example: {@code 1000.00} at {@code 12.50} gives an exact product of {@code 12500.0000},
     * an exact quotient of {@code 10.41666...}, and a stored result of {@code 10.41}. It is never
     * {@code 10.42}; that would be the answer if this system rounded, and it does not.
     *
     * @param tranCatBal  {@code TRAN-CAT-BAL}, the category balance to charge interest on
     * @param disIntRate  {@code DIS-INT-RATE}, the annual rate as a percentage, so {@code 12.50} means
     *                    twelve and a half percent
     * @return the monthly interest, truncated to scale 2
     * @throws NullPointerException if either operand is {@code null}
     */
    public static BigDecimal computeMonthlyInterest(BigDecimal tranCatBal, BigDecimal disIntRate) {
        return CobolDecimal.monthlyInterest(tranCatBal, disIntRate);
    }

    /**
     * {@code 1400-COMPUTE-FEES} - {@code app/cbl/CBACT04C.cbl:518-520}, and gate G26.
     *
     * <p><strong>This method does nothing, and that is the specification.</strong> The COBOL paragraph is
     * three lines: the paragraph header, the comment {@code * To be implemented}, and {@code EXIT.} - it
     * contains <strong>zero statements</strong>. It is nevertheless {@code PERFORM}ed unconditionally at
     * {@code :216}, immediately after {@code 1300-COMPUTE-INTEREST} and inside the
     * {@code IF DIS-INT-RATE NOT = 0} guard, so the call is part of the program's control flow even
     * though the paragraph is empty. {@link InterestCalculationRun#processRecord(TranCatBalRecord)}
     * invokes it from exactly that position.
     *
     * <p>Computing a fee here would be adding a feature the legacy estate does not have (practice B5). It
     * would also be undetectable by inspection of the account master alone, because a fee added to
     * {@code WS-TOTAL-INT} would look exactly like interest. So the emptiness is stated in prose, checked
     * by a test that asserts the method changes nothing and is still called once per interest
     * computation, and left alone.
     *
     * <p>Not {@code static} and not {@code final}, so a test can spy on the invocation and prove the call
     * site is where the source puts it.
     */
    public void computeFees() {
        // Intentionally empty: app/cbl/CBACT04C.cbl:518-520 is a stub marked "To be implemented" and
        // performs no statement. Preserving dead code exactly is a parity requirement, not an oversight.
    }


    // =================================================================================================
    // The run: one execution's WORKING-STORAGE, one execution's five open files, and every paragraph.
    //
    // A COBOL program's storage belongs to the program's execution, so it belongs here and not to the
    // singleton bean (practice B9, gate G53). Two runs share nothing.
    // =================================================================================================

    /**
     * One execution of {@code CBACT04C}: its {@code WORKING-STORAGE}, its five open files, and each of
     * its paragraphs as a separately callable method.
     *
     * <p>Package-visible, and so is every paragraph, so a unit test can drive each guard chain on its own
     * rather than having to contrive a dataset failure for the whole program.
     *
     * <p><strong>The record areas are allocated once per run and reused, exactly as
     * {@code WORKING-STORAGE} is.</strong> That is not an optimisation - it is what produces the
     * {@code TRAN-DESC} residue described on the enclosing class, and a fresh {@link TranRecord} per
     * transaction would silently erase it.
     */
    static final class InterestCalculationRun {

        /** The enclosing job, for its repositories, its codec, its clock and {@link #computeFees()}. */
        private final AccountInterestCalcJob job;

        /** {@code PARM-DATE PIC X(10)} - already exactly {@value #PARM_DATE_WIDTH} characters. */
        private final String parmDate;

        /** Where the displayed lines go. */
        private final SysoutSink sysout;

        /** {@code WS-MISC-VARS} and {@code WS-COUNTERS}, plus {@code APPL-RESULT} and {@code END-OF-FILE}. */
        private final WorkingStorage workingStorage = new WorkingStorage();

        /**
         * {@code 01 TRAN-RECORD} - {@code app/cpy/CVTRA05Y.cpy}, copied at {@code app/cbl/CBACT04C.cbl:117}.
         *
         * <p><strong>One instance for the whole run.</strong> Its 350 bytes are the residue carrier: the
         * {@code STRING} into {@code TRAN-DESC} leaves bytes 25 to 100 as the previous iteration left
         * them, and {@code FILLER X(20)} is never written at all and so stays spaces from allocation.
         */
        private final TranRecord tranRecord;

        /**
         * {@code FD-DISCGRP-REC} - the <em>file</em> record area, whose leading 16 bytes are the key the
         * {@code READ} is addressed by ({@code app/cbl/CBACT04C.cbl:76-82}).
         *
         * <p>Distinct from {@link #disGroupRecord}, which is the {@code WORKING-STORAGE} area the
         * {@code INTO} phrase populates. Keeping them apart is what makes the {@code '23'} stale window
         * expressible at all: the key survives a failed read, and so does the previous record.
         */
        private final DisclosureGroupRecord fdDiscgrpRec;

        /**
         * {@code 01 DIS-GROUP-RECORD} - {@code app/cpy/CVTRA02Y.cpy}, copied at {@code :107}.
         *
         * <p><strong>Replaced only by a successful read, never cleared by a failed one.</strong> On file
         * status {@code '23'} the {@code READ ... INTO} at {@code :416} transfers nothing, so this field
         * still holds the previous record - rate included - until {@code 1200-A} succeeds. That window is
         * the legacy behaviour and is preserved deliberately.
         */
        private DisclosureGroupRecord disGroupRecord;

        /**
         * {@code 01 ACCOUNT-RECORD} - {@code app/cpy/CVACT01Y.cpy}, copied at {@code :112}.
         *
         * <p>Initialised as {@code WORKING-STORAGE} is - character spans spaces, numeric spans zeros -
         * so the unreachable {@code ELSE} at {@code :220} rewrites account {@code 00000000000} and takes
         * the not-found arm, which is what the COBOL would do with the same uninitialised area. It is
         * therefore never {@code null} and no guard has to pretend it might be.
         */
        private AccountRecord accountRecord;

        /**
         * {@code 01 CARD-XREF-RECORD} - {@code app/cpy/CVACT03Y.cpy}, copied at {@code :102}.
         *
         * <p>Initialised to {@code SPACES} and zeros for the same reason as {@link #accountRecord}.
         */
        private CardXrefRecord cardXrefRecord;

        /**
         * {@code 01 TRAN-CAT-BAL-RECORD} - {@code app/cpy/CVTRA01Y.cpy}, copied at {@code :97}.
         *
         * <p>Populated by the {@code INTO} phrase of {@code :326} on file status {@code '00'} only, so
         * like every other {@code INTO} target it keeps its previous content on any other status.
         */
        private TranCatBalRecord tranCatBalRecord;

        /** {@code TCATBAL-FILE}, {@code OPEN INPUT} at {@code app/cbl/CBACT04C.cbl:236}. */
        private TranCatBalFile tcatbalFile;

        /**
         * {@code XREF-FILE}, {@code OPEN INPUT} at {@code app/cbl/CBACT04C.cbl:254}.
         *
         * <p>The handle exists to carry the {@code OPEN} and {@code CLOSE} statuses the program tests.
         * {@code CBACT04C} declares the file {@code ACCESS MODE IS RANDOM} and never reads it
         * sequentially, so the keyed reads of {@code 1110-GET-XREF-DATA} go through the repository's
         * alternate-index finder rather than through this cursor.
         */
        private BrowseCursor xrefFile;

        /**
         * The cross-reference repository this execution reads through, resolved once at the open.
         *
         * <p>Resolved once and held for the run rather than per read, and that is not only an efficiency
         * point: {@link CardXrefRepository#addressing} hands back an instance whose statements resolve
         * against its own relation on first use, so asking for a fresh one per record would re-describe
         * the relation on every record of a 50-record browse. One open, one resolution - which is also
         * what {@code OPEN INPUT XREF-FILE} at {@code app/cbl/CBACT04C.cbl:254} does.
         *
         * <p>{@code null} until {@link #xreffileOpen()} has run, which is the state before the program's
         * own open.
         */
        private CardXrefRepository xrefRepository;

        /** {@code DISCGRP-FILE}, {@code OPEN INPUT} at {@code app/cbl/CBACT04C.cbl:272}. */
        private DisclosureGroupFile discgrpFile;

        /**
         * {@code ACCOUNT-FILE}, {@code OPEN I-O} at {@code app/cbl/CBACT04C.cbl:291}.
         *
         * <p>{@code I-O} and not {@code INPUT}: this is the one dataset the program writes back to, and
         * {@code 1050-UPDATE-ACCOUNT} rewrites through this handle.
         */
        private AccountFile acctFile;

        /** {@code TRANSACT-FILE}, {@code OPEN OUTPUT} at {@code app/cbl/CBACT04C.cbl:309}. */
        private OutputFile tranFile;

        /**
         * Allocates the run's storage.
         *
         * @param job      the enclosing job
         * @param parmDate {@code PARM-DATE}, already at its declared width
         * @param sysout   the {@code SYSOUT} destination
         */
        private InterestCalculationRun(AccountInterestCalcJob job, String parmDate, SysoutSink sysout) {
            this.job = job;
            this.parmDate = parmDate;
            this.sysout = sysout;
            Charset charset = job.datasetCharset;
            this.tranRecord = new TranRecord(charset);
            this.fdDiscgrpRec = new DisclosureGroupRecord(charset);
            this.disGroupRecord = new DisclosureGroupRecord(charset);
            this.accountRecord = new AccountRecord(charset);
            this.cardXrefRecord = new CardXrefRecord("", 0, 0L);
            this.tranCatBalRecord = TranCatBalRecord.newInstance(charset);
        }

        // ---------------------------------------------------------------------------------------------
        // Read-only views of the run's storage, for assertions.
        // ---------------------------------------------------------------------------------------------

        /**
         * This run's {@code WORKING-STORAGE}.
         *
         * @return the storage; never {@code null}
         */
        WorkingStorage workingStorage() {
            return workingStorage;
        }

        /**
         * This run's {@code SYSOUT} destination.
         *
         * @return the sink; never {@code null}
         */
        SysoutSink sysout() {
            return sysout;
        }

        /**
         * {@code PARM-DATE}, exactly {@value #PARM_DATE_WIDTH} characters.
         *
         * @return the PARM; never {@code null}
         */
        String parmDate() {
            return parmDate;
        }

        /**
         * {@code 01 TRAN-RECORD}, the live 350-byte area including its residue.
         *
         * @return the record; never {@code null}
         */
        TranRecord tranRecord() {
            return tranRecord;
        }

        /**
         * {@code FD-DISCGRP-REC}, whose leading 16 bytes are the key the next {@code READ} will use.
         *
         * @return the file record area; never {@code null}
         */
        DisclosureGroupRecord fdDiscgrpRec() {
            return fdDiscgrpRec;
        }

        /**
         * {@code 01 DIS-GROUP-RECORD} as it stands, which on a {@code '23'} is still the previous one.
         *
         * @return the working-storage record; never {@code null}
         */
        DisclosureGroupRecord disGroupRecord() {
            return disGroupRecord;
        }

        /**
         * {@code 01 ACCOUNT-RECORD} as it stands.
         *
         * @return the account; never {@code null}
         */
        AccountRecord accountRecord() {
            return accountRecord;
        }

        /**
         * {@code 01 CARD-XREF-RECORD} as it stands.
         *
         * @return the cross-reference record; never {@code null}
         */
        CardXrefRecord cardXrefRecord() {
            return cardXrefRecord;
        }

        /**
         * {@code 01 TRAN-CAT-BAL-RECORD} as it stands.
         *
         * @return the category balance record; never {@code null}
         */
        TranCatBalRecord tranCatBalRecord() {
            return tranCatBalRecord;
        }

        // ---------------------------------------------------------------------------------------------
        // The five OPEN paragraphs - app/cbl/CBACT04C.cbl:234-323.
        //
        // Every one has the identical four-part shape: pessimistic pre-set, the verb, the status ladder,
        // the guard. Only the verb, the mode and the message differ, and all three are observable, so all
        // five are written out rather than folded into a loop over a table.
        // ---------------------------------------------------------------------------------------------

        /**
         * Performs the five opens in the source's order: {@code app/cbl/CBACT04C.cbl:182-186}.
         *
         * <p>The order matters because each failure emits a different message, so a run that failed on
         * the third open must have emitted nothing from the fourth and fifth.
         *
         * @throws AbendException if any open reports a status other than {@code '00'}
         */
        void openFiles() {
            tcatbalfOpen();                                                                   //     L182
            xreffileOpen();                                                                   //     L183
            discgrpOpen();                                                                    //     L184
            acctfileOpen();                                                                   //     L185
            tranfileOpen();                                                                   //     L186
        }

        /**
         * {@code 0000-TCATBALF-OPEN} - {@code app/cbl/CBACT04C.cbl:234-250}.
         *
         * @throws AbendException if the open does not report {@code '00'}
         */
        void tcatbalfOpen() {
            workingStorage.moveToApplResult(APPL_RESULT_ASSUMED_FAILURE);                     //     L235
            tcatbalFile = job.tranCatBalRepository.open(TranCatBalRepository.OpenMode.INPUT);  //     L236
            String status = tcatbalFile.openStatus();
            applResultFromOkStatus(status);                                                   // L237-241
            if (!workingStorage.applAok()) {                                                  //     L242
                throw reportAndAbend(ERROR_OPENING_TCATBALF, status);                         // L245-248
            }
        }

        /**
         * {@code 0100-XREFFILE-OPEN} - {@code app/cbl/CBACT04C.cbl:252-268}.
         *
         * <p>The one open whose {@code DISPLAY} names a second operand:
         * {@code DISPLAY 'ERROR OPENING CROSS REF FILE' XREFFILE-STATUS} ({@code :263}). COBOL
         * {@code DISPLAY} contributes each operand's own characters with no separator of its own, so the
         * emitted line is the message with the two-character file status appended directly - and the
         * whitespace between the two operands in the source is source layout, not output.
         *
         * @throws AbendException if the open does not report {@code '00'}
         */
        void xreffileOpen() {
            workingStorage.moveToApplResult(APPL_RESULT_ASSUMED_FAILURE);                     //     L253
            xrefRepository = job.xrefFileRepository();
            xrefFile = xrefRepository.openBrowse();                                            //     L254
            String status = xrefFile.openStatus();
            applResultFromOkStatus(status);                                                   // L255-259
            if (!workingStorage.applAok()) {                                                  //     L260
                throw reportAndAbend(ERROR_OPENING_XREFFILE + status, status);                // L263-266
            }
        }

        /**
         * {@code 0200-DISCGRP-OPEN} - {@code app/cbl/CBACT04C.cbl:270-286}.
         *
         * <p>Its failure message is {@value #ERROR_OPENING_DISCGRP}, which <strong>names the wrong
         * file</strong>. See the constant's documentation; it is preserved verbatim.
         *
         * @throws AbendException if the open does not report {@code '00'}
         */
        void discgrpOpen() {
            workingStorage.moveToApplResult(APPL_RESULT_ASSUMED_FAILURE);                     //     L271
            discgrpFile = job.disclosureGroupAccess.open();                                   //     L272
            String status = discgrpFile.openStatus();
            applResultFromOkStatus(status);                                                   // L273-277
            if (!workingStorage.applAok()) {                                                  //     L278
                throw reportAndAbend(ERROR_OPENING_DISCGRP, status);                          // L281-284
            }
        }

        /**
         * {@code 0300-ACCTFILE-OPEN} - {@code app/cbl/CBACT04C.cbl:289-305}, and the only
         * {@code OPEN I-O} in the program.
         *
         * @throws AbendException if the open does not report {@code '00'}
         */
        void acctfileOpen() {
            workingStorage.moveToApplResult(APPL_RESULT_ASSUMED_FAILURE);                     //     L290
            acctFile = job.accountRepository.open(AccountRepository.OpenMode.I_O);             //     L291
            String status = acctFile.openStatus();
            applResultFromOkStatus(status);                                                   // L292-296
            if (!workingStorage.applAok()) {                                                  //     L297
                throw reportAndAbend(ERROR_OPENING_ACCTFILE, status);                         // L300-303
            }
        }

        /**
         * {@code 0400-TRANFILE-OPEN} - {@code app/cbl/CBACT04C.cbl:307-323}, an {@code OPEN OUTPUT} over
         * a generation created {@code NEW} by {@code app/jcl/INTCALC.jcl:37-41}.
         *
         * @throws AbendException if the open does not report {@code '00'}
         */
        void tranfileOpen() {
            workingStorage.moveToApplResult(APPL_RESULT_ASSUMED_FAILURE);                     //     L308
            // The open clears the destination, which is what the NEW of DISP=(NEW,CATLG,DELETE) means -
            // and it runs in the ItemStream open callback, which Spring Batch invokes OUTSIDE the chunk
            // transaction. With the pool handing out connections with auto-commit disabled, the clear
            // would execute, report the rows it removed, and then be rolled back when the connection
            // returned: the open would report '00' over a generation still holding the previous run's
            // transactions, and this run's records would be appended to them. So the allocation gets a
            // boundary of its own, exactly as its abnormal counterpart does in releaseAbnormally().
            DatasetBinding binding = job.batchConfig.datasetBinding(JOB_KEY, TRANSACT_DD_NAME);
            tranFile = job.unitOfWork.persistDisposition(TRANSACT_OPEN_DISPOSITION,               //  L309
                    () -> job.transactionRepository.openOutput(binding, TRANSACT_DD_NAME));
            String status = tranFile.openStatus();
            applResultFromOkStatus(status);                                                   // L310-314
            if (!workingStorage.applAok()) {                                                  //     L315
                throw reportAndAbend(ERROR_OPENING_TRANFILE, status);                         // L318-321
            }
        }

        // ---------------------------------------------------------------------------------------------
        // The five CLOSE paragraphs - app/cbl/CBACT04C.cbl:522-611.
        // ---------------------------------------------------------------------------------------------

        /**
         * Performs the five closes in the source's order: {@code app/cbl/CBACT04C.cbl:224-228}.
         *
         * <p>Reached only when the loop ended normally. An abend leaves these unperformed, which is why
         * {@link AccountInterestCalcJob#calculateInterest(String, SysoutSink)} calls this inside its
         * {@code try} and releases the handles silently in its {@code finally}.
         *
         * @throws AbendException if any close reports a status other than {@code '00'}
         */
        void closeFiles() {
            tcatbalfClose();                                                                  //     L224
            xreffileClose();                                                                  //     L225
            discgrpClose();                                                                   //     L226
            acctfileClose();                                                                  //     L227
            tranfileClose();                                                                  //     L228
        }

        /**
         * {@code 9000-TCATBALF-CLOSE} - {@code app/cbl/CBACT04C.cbl:522-538}.
         *
         * @throws AbendException if the close does not report {@code '00'}
         */
        void tcatbalfClose() {
            workingStorage.moveToApplResult(APPL_RESULT_ASSUMED_FAILURE);                     //     L523
            String status = tcatbalFile.closeFile();                                          //     L524
            applResultFromOkStatus(status);                                                   // L525-529
            if (!workingStorage.applAok()) {                                                  //     L530
                throw reportAndAbend(ERROR_CLOSING_TCATBALF, status);                         // L533-536
            }
        }

        /**
         * {@code 9100-XREFFILE-CLOSE} - {@code app/cbl/CBACT04C.cbl:541-557}.
         *
         * @throws AbendException if the close does not report {@code '00'}
         */
        void xreffileClose() {
            workingStorage.moveToApplResult(APPL_RESULT_ASSUMED_FAILURE);                     //     L542
            String status = xrefFile.closeBrowse();                                           //     L543
            applResultFromOkStatus(status);                                                   // L544-548
            if (!workingStorage.applAok()) {                                                  //     L549
                throw reportAndAbend(ERROR_CLOSING_XREFFILE, status);                         // L552-555
            }
        }

        /**
         * {@code 9200-DISCGRP-CLOSE} - {@code app/cbl/CBACT04C.cbl:559-575}.
         *
         * @throws AbendException if the close does not report {@code '00'}
         */
        void discgrpClose() {
            workingStorage.moveToApplResult(APPL_RESULT_ASSUMED_FAILURE);                     //     L560
            String status = discgrpFile.closeFile();                                          //     L561
            applResultFromOkStatus(status);                                                   // L562-566
            if (!workingStorage.applAok()) {                                                  //     L567
                throw reportAndAbend(ERROR_CLOSING_DISCGRP, status);                          // L570-573
            }
        }

        /**
         * {@code 9300-ACCTFILE-CLOSE} - {@code app/cbl/CBACT04C.cbl:577-593}.
         *
         * @throws AbendException if the close does not report {@code '00'}
         */
        void acctfileClose() {
            workingStorage.moveToApplResult(APPL_RESULT_ASSUMED_FAILURE);                     //     L578
            String status = acctFile.closeFile();                                             //     L579
            applResultFromOkStatus(status);                                                   // L580-584
            if (!workingStorage.applAok()) {                                                  //     L585
                throw reportAndAbend(ERROR_CLOSING_ACCTFILE, status);                         // L588-591
            }
        }

        /**
         * {@code 9400-TRANFILE-CLOSE} - {@code app/cbl/CBACT04C.cbl:595-611}.
         *
         * @throws AbendException if the close does not report {@code '00'}
         */
        void tranfileClose() {
            workingStorage.moveToApplResult(APPL_RESULT_ASSUMED_FAILURE);                     //     L596
            String status = tranFile.closeOutput();                                           //     L597
            applResultFromOkStatus(status);                                                   // L598-602
            if (!workingStorage.applAok()) {                                                  //     L603
                throw reportAndAbend(ERROR_CLOSING_TRANFILE, status);                         // L606-609
            }
        }

        /**
         * Releases whichever handles were opened, emitting nothing.
         *
         * <p>This is <strong>not</strong> a {@code CLOSE} paragraph and displays no line: it exists so an
         * abended run leaves no handle behind while its {@code SYSOUT} still ends exactly where the
         * mainframe's would. Every handle's own close is idempotent, so calling this after a successful
         * {@link #closeFiles()} changes nothing.
         */
        /**
         * Applies the abnormal disposition of {@code app/jcl/INTCALC.jcl:37} to the {@code TRANSACT}
         * generation, then releases the handles.
         *
         * <p>Called instead of {@link #release()} on the path a run takes when it does <em>not</em> reach
         * end of file - which is to say when it abends. {@code DISP=(NEW,CATLG,DELETE)} catalogues the
         * generation on a normal end and deletes it otherwise, so an abended run must leave no generation
         * behind even though each of its writes was durable as it completed.
         *
         * <p>The account master is deliberately not touched: {@code ACCTFILE} is {@code DISP=SHR}
         * ({@code app/jcl/INTCALC.jcl:33}) over an existing {@code RECOVERY(NONE)} dataset, so every
         * rewrite this run performed stands. Two datasets, two dispositions - which is what per-resource
         * persistence means here.
         *
         * <p>Nothing is thrown. The abend that brought the run here is what the caller must see, so a
         * disposition that cannot be applied reports itself through the log and the returned status of
         * {@link OutputFile#discardGeneration()} rather than by raising something of its own.
         */
        void releaseAbnormally() {
            if (tranFile != null) {
                // Applied in its own boundary. A disposition is the initiator's work and runs after the
                // step, so enrolling it in whatever transaction the step was inside would let the failure
                // that triggered the disposition undo the disposition - leaving exactly the partial
                // generation it exists to remove.
                String disposition = job.unitOfWork.persistDisposition(
                        TRANSACT_ABNORMAL_DISPOSITION, tranFile::discardGeneration);
                if (!FileStatus.OK.equals(disposition)) {
                    LOG.error("The " + TransactionRepository.SEQUENTIAL_OUTPUT_DD_NAME
                            + " generation of this abended run could not be discarded; it reported file "
                            + "status " + FileStatus.toStatusImage(disposition)
                            + ". app/jcl/INTCALC.jcl:37 declares DISP=(NEW,CATLG,DELETE), so a partial "
                            + "generation may remain where the mainframe would leave none");
                }
            }
            release();
        }

        void release() {
            if (tranFile != null) {
                tranFile.close();
            }
            if (acctFile != null) {
                acctFile.close();
            }
            if (discgrpFile != null) {
                discgrpFile.close();
            }
            if (xrefFile != null) {
                xrefFile.close();
            }
            if (tcatbalFile != null) {
                tcatbalFile.close();
            }
        }

        // ---------------------------------------------------------------------------------------------
        // 1000-TCATBALF-GET-NEXT - app/cbl/CBACT04C.cbl:325-348.
        // ---------------------------------------------------------------------------------------------

        /**
         * Reads the next category balance record: {@code app/cbl/CBACT04C.cbl:325-348}.
         *
         * <p>Three-armed status ladder - {@code '00'} to {@code APPL-RESULT 0}, {@code '10'} to 16, and
         * anything else to 12 - then the guard: {@code APPL-EOF} moves {@code 'Y'} into
         * {@code END-OF-FILE} and returns quietly, and anything else displays, renders the status and
         * abends.
         *
         * <p>{@code READ ... INTO TRAN-CAT-BAL-RECORD} transfers only on success, so on end of file or on
         * a failure the record area keeps its previous content. That is why the returned value is
         * {@code null} at end of file rather than a stale record, and it is also the signal Spring Batch's
         * {@code ItemReader} contract uses to end a step.
         *
         * @return the record just read, or {@code null} at end of file
         * @throws AbendException if the read reports a status that is neither {@code '00'} nor {@code '10'}
         */
        TranCatBalRecord tcatbalfGetNext() {
            // READ TCATBAL-FILE INTO TRAN-CAT-BAL-RECORD.                                            L326
            TranCatBalRepository.ReadResult result = tcatbalFile.readNext();
            String status = result.status();

            if (FileStatus.isOk(status)) {                                                    //     L327
                workingStorage.moveToApplResult(APPL_AOK);                                    //     L328
            } else if (FileStatus.isEndOfFile(status)) {                                      //     L330
                workingStorage.moveToApplResult(APPL_EOF);                                    //     L331
            } else {
                workingStorage.moveToApplResult(APPL_RESULT_FATAL);                           //     L333
            }

            // IF APPL-AOK CONTINUE ELSE ...                                                     L336-347
            if (!workingStorage.applAok()) {
                if (workingStorage.applEof()) {                                                //     L339
                    workingStorage.moveEndOfFile(END_OF_FILE_YES);                             //     L340
                    return null;
                }
                throw reportAndAbend(ERROR_READING_TCATBALF, status);                          // L342-345
            }

            tranCatBalRecord = result.record().orElseThrow(() -> new IllegalStateException(
                    "A read of " + TCATBALF_DD_NAME + " reported file status " + FileStatus.OK
                            + " with no decoded record, so READ ... INTO TRAN-CAT-BAL-RECORD had nothing "
                            + "to transfer. Only the success arm carries a record, and it always does."));
            return tranCatBalRecord;
        }

        // ---------------------------------------------------------------------------------------------
        // The record body - app/cbl/CBACT04C.cbl:192-217.
        // ---------------------------------------------------------------------------------------------

        /**
         * Processes one category balance record: {@code app/cbl/CBACT04C.cbl:192-217}, statement for
         * statement.
         *
         * <pre>
         * ADD 1 TO WS-RECORD-COUNT                                                               L192
         * DISPLAY TRAN-CAT-BAL-RECORD                                                            L193
         * IF TRANCAT-ACCT-ID NOT= WS-LAST-ACCT-NUM                                               L194
         *   IF WS-FIRST-TIME NOT = 'Y'                                                           L195
         *      PERFORM 1050-UPDATE-ACCOUNT                                                       L196
         *   ELSE                                                                                 L197
         *      MOVE 'N' TO WS-FIRST-TIME                                                         L198
         *   END-IF                                                                               L199
         *   MOVE 0 TO WS-TOTAL-INT                                                               L200
         *   MOVE TRANCAT-ACCT-ID TO WS-LAST-ACCT-NUM                                             L201
         *   MOVE TRANCAT-ACCT-ID TO FD-ACCT-ID                                                   L202
         *   PERFORM 1100-GET-ACCT-DATA                                                           L203
         *   MOVE TRANCAT-ACCT-ID TO FD-XREF-ACCT-ID                                              L204
         *   PERFORM 1110-GET-XREF-DATA                                                           L205
         * END-IF                                                                                 L206
         * MOVE ACCT-GROUP-ID TO FD-DIS-ACCT-GROUP-ID                                             L210
         * MOVE TRANCAT-CD TO FD-DIS-TRAN-CAT-CD                                                  L211
         * MOVE TRANCAT-TYPE-CD TO FD-DIS-TRAN-TYPE-CD                                            L212
         * PERFORM 1200-GET-INTEREST-RATE                                                         L213
         * IF DIS-INT-RATE NOT = 0                                                                L214
         *   PERFORM 1300-COMPUTE-INTEREST                                                        L215
         *   PERFORM 1400-COMPUTE-FEES                                                            L216
         * END-IF                                                                                 L217
         * </pre>
         *
         * <p><strong>The account-break test compares a number with a string.</strong>
         * {@code TRANCAT-ACCT-ID} is {@code PIC 9(11)} and {@code WS-LAST-ACCT-NUM} is {@code PIC X(11)},
         * so {@code :194} is an alphanumeric comparison of the account id's eleven-digit display image
         * against eleven stored characters. {@code WS-LAST-ACCT-NUM} starts as {@code SPACES}, which can
         * never equal eleven digits, so the <strong>first record always looks like a break</strong> - and
         * that is precisely what the {@code WS-FIRST-TIME} guard at {@code :195} exists to stop from
         * rewriting an account that was never read.
         *
         * <p>The three commented-out displays at {@code :207-209} are absent, because they are absent from
         * the program's output.
         *
         * <p>{@code MOVE ACCT-GROUP-ID TO FD-DIS-ACCT-GROUP-ID} at {@code :210} reads the account record
         * as it stands - refreshed on a break, carried over otherwise. Carrying it over is correct rather
         * than stale: a non-break record belongs to the same account, so the group id is the same
         * account's.
         *
         * <p>{@code 1400-COMPUTE-FEES} is invoked from inside the {@code IF DIS-INT-RATE NOT = 0} guard,
         * immediately after the interest computation, exactly as {@code :216} puts it. It does nothing,
         * and it is still called (gate G26).
         *
         * @param item the record just read; must be the value {@link #tcatbalfGetNext()} returned
         * @return what this record did, for the writer stage's bookkeeping and for assertions
         * @throws NullPointerException if {@code item} is {@code null}
         * @throws AbendException       if any file operation this record triggers reports a fatal status
         */
        RecordOutcome processRecord(TranCatBalRecord item) {
            Objects.requireNonNull(item, "A category balance record is required to process one; end of "
                    + "file is signalled by tcatbalfGetNext() returning null and is handled by the "
                    + "mainline, not here");

            // ADD 1 TO WS-RECORD-COUNT                                                              L192
            workingStorage.addOneToRecordCount();

            // DISPLAY TRAN-CAT-BAL-RECORD - the raw 50-byte image, one line per record.              L193
            sysout.write(item.rawImage());

            boolean accountRewritten = false;

            // IF TRANCAT-ACCT-ID NOT= WS-LAST-ACCT-NUM                                               L194
            if (!item.trancatAcctIdImage().equals(workingStorage.lastAcctNum())) {
                if (!workingStorage.firstTimeIsYes()) {                                       //     L195
                    updateAccount();                                                          //     L196
                    accountRewritten = true;
                } else {
                    workingStorage.moveFirstTime(FIRST_TIME_NO);                              //     L198
                }
                workingStorage.moveZeroToTotalInterest();                                     //     L200
                workingStorage.moveLastAcctNum(item.trancatAcctIdImage());                    //     L201
                getAcctData(item.trancatAcctId());                                            // L202-203
                getXrefData(item.trancatAcctId());                                            // L204-205
            }

            // The DISCGRP key, built in the source's order into the FD record area.              L210-212
            fdDiscgrpRec.disAcctGroupId(accountRecord.rawAcctGroupId());                      //     L210
            fdDiscgrpRec.disTranCatCd(item.trancatCd());                                      //     L211
            fdDiscgrpRec.disTranTypeCd(item.trancatTypeCd());                                 //     L212

            // PERFORM 1200-GET-INTEREST-RATE                                                        L213
            getInterestRate();

            boolean interestComputed = false;
            boolean feesComputed = false;
            BigDecimal monthlyInterest = CobolDecimal.monetaryZero();
            String tranId = "";

            // IF DIS-INT-RATE NOT = 0                                                                L214
            if (disGroupRecord.disIntRateIsNotZero()) {
                computeInterest(item);                                                        //     L215
                monthlyInterest = workingStorage.monthlyInterest();
                tranId = tranRecord.tranId();
                interestComputed = true;

                // PERFORM 1400-COMPUTE-FEES - a genuine no-op, called from exactly here.              L216
                job.computeFees();
                feesComputed = true;
            }

            return new RecordOutcome(workingStorage.recordCount(), item.tranCatKeyImage(),
                    accountRewritten, interestComputed, feesComputed, interestComputed, monthlyInterest,
                    tranId);
        }

        // ---------------------------------------------------------------------------------------------
        // 1050-UPDATE-ACCOUNT - app/cbl/CBACT04C.cbl:350-370. Gate G27.
        // ---------------------------------------------------------------------------------------------

        /**
         * Closes out the previous account: {@code app/cbl/CBACT04C.cbl:350-370}, and gate G27.
         *
         * <p><strong>Four steps, in exactly this order:</strong>
         * <pre>
         * ADD WS-TOTAL-INT  TO ACCT-CURR-BAL                                                      L352
         * MOVE 0 TO ACCT-CURR-CYC-CREDIT                                                          L353
         * MOVE 0 TO ACCT-CURR-CYC-DEBIT                                                           L354
         * REWRITE FD-ACCTFILE-REC FROM  ACCOUNT-RECORD                                            L356
         * </pre>
         * The balance is updated <em>before</em> the cycle amounts are zeroed and the record is rewritten
         * <em>after</em> all three, so a rewrite that fails leaves the accumulated interest unposted and
         * the cycle amounts untouched on the dataset. Reordering any of the four would change what a
         * failed run leaves behind.
         *
         * <p>The {@code ADD} receives {@code ACCT-CURR-BAL PIC S9(10)V99} with no {@code ROUNDED} and no
         * {@code ON SIZE ERROR}, so the sum is stored truncated to two decimal places and, on high-order
         * overflow, wrapped to the receiver's ten integer digits - which is what
         * {@link CobolDecimal#storeAtPicture(BigDecimal, int, int)} does and what a COBOL {@code ADD}
         * without {@code ON SIZE ERROR} does. The two {@code MOVE 0} statements store zero <em>at scale
         * 2</em>, because a scale-0 zero would serialise the wrong bytes.
         *
         * <p>The record rewritten is {@code ACCOUNT-RECORD} as it stands, which on the break path is still
         * the <em>previous</em> account's - {@code 1100-GET-ACCT-DATA} has not run yet for the new one.
         * That sequencing is the whole mechanism of the account break.
         *
         * @throws AbendException if the rewrite does not report {@code '00'}
         */
        void updateAccount() {
            // ADD WS-TOTAL-INT TO ACCT-CURR-BAL                                                      L352
            BigDecimal posted = CobolDecimal.add(accountRecord.getAcctCurrBal(),
                    workingStorage.totalInterest(), AccountRecord.MONETARY_SCALE);
            accountRecord.setAcctCurrBal(CobolDecimal.storeAtPicture(posted,
                    AccountRecord.MONETARY_INTEGER_DIGITS, AccountRecord.MONETARY_SCALE));

            accountRecord.zeroAcctCurrCycCredit();                                            //     L353
            accountRecord.zeroAcctCurrCycDebit();                                             //     L354

            // REWRITE FD-ACCTFILE-REC FROM ACCOUNT-RECORD                                            L356
            //
            // Persisted on its own. This paragraph rewrites the PREVIOUS account and processing then
            // continues into the next account group, where a failed read (:387), a failed rate lookup
            // (:412) or a failed WRITE (:510) abends at :632. The COBOL leaves this rewrite in place -
            // no syncpoint, and ACCTDAT is RECOVERY(NONE) - so it must not be enrolled in a boundary a
            // later failure rolls back. An account reverted here would have its interest posted twice by
            // the re-run, because its cycle amounts would have been un-zeroed with it.
            AccountRepository.WriteResult result = job.unitOfWork.persistVerb(
                    REWRITE_ACCTFILE_VERB, () -> acctFile.rewrite(accountRecord));
            String status = result.status();
            applResultFromOkStatus(status);                                                   // L357-361
            if (!workingStorage.applAok()) {                                                  //     L362
                throw reportAndAbend(ERROR_REWRITING_ACCTFILE, status);                       // L365-368
            }
        }

        // ---------------------------------------------------------------------------------------------
        // 1100-GET-ACCT-DATA - app/cbl/CBACT04C.cbl:372-391.
        // ---------------------------------------------------------------------------------------------

        /**
         * Reads the account master by key: {@code app/cbl/CBACT04C.cbl:372-391}.
         *
         * <p><strong>A missing account emits two lines and then abends.</strong> The {@code INVALID KEY}
         * phrase at {@code :374-375} displays {@value #ACCOUNT_NOT_FOUND_PREFIX} followed by the
         * eleven-digit key, and then the guard at {@code :378} accepts <em>only</em> {@code '00'} - so
         * {@code '23'} falls through to {@value #ERROR_READING_ACCTFILE} and the abend. Both lines are
         * emitted, in that order. This is deliberately unlike
         * {@link #getInterestRate() 1200-GET-INTEREST-RATE}, which does accept {@code '23'}.
         *
         * @param acctId {@code MOVE TRANCAT-ACCT-ID TO FD-ACCT-ID} ({@code :202}), the key to read by
         * @throws AbendException if the read does not report {@code '00'}
         */
        void getAcctData(long acctId) {
            String keyImage = job.codec.movePic9(acctId, AccountRecord.ACCT_ID_LENGTH);       //     L202

            // READ ACCOUNT-FILE INTO ACCOUNT-RECORD ... END-READ                                 L373-376
            AccountRepository.ReadResult result = acctFile.readByKey(acctId);
            if (result.isNotFound()) {                                                        //     L374
                sysout.write(ACCOUNT_NOT_FOUND_PREFIX + keyImage);                            //     L375
            }

            String status = result.status();
            applResultFromOkStatus(status);                                                   // L378-382
            if (!workingStorage.applAok()) {                                                  //     L383
                throw reportAndAbend(ERROR_READING_ACCTFILE, status);                         // L386-389
            }

            accountRecord = result.account().orElseThrow(() -> new IllegalStateException(
                    "A read of " + ACCTFILE_DD_NAME + " reported file status " + FileStatus.OK
                            + " with no decoded record, so READ ... INTO ACCOUNT-RECORD had nothing to "
                            + "transfer. Only the success arm carries a record, and it always does."));
        }

        // ---------------------------------------------------------------------------------------------
        // 1110-GET-XREF-DATA - app/cbl/CBACT04C.cbl:393-413.
        // ---------------------------------------------------------------------------------------------

        /**
         * Reads the cross reference by its <strong>alternate</strong> key:
         * {@code app/cbl/CBACT04C.cbl:393-413}.
         *
         * <pre>
         * READ XREF-FILE INTO CARD-XREF-RECORD
         *  KEY IS FD-XREF-ACCT-ID
         *     INVALID KEY
         *        DISPLAY 'ACCOUNT NOT FOUND: ' FD-XREF-ACCT-ID
         * END-READ
         * </pre>
         *
         * <p>{@code KEY IS FD-XREF-ACCT-ID} names the {@code ALTERNATE RECORD KEY} declared at
         * {@code :38}, not the {@code RECORD KEY}. That is the access path {@code app/jcl/INTCALC.jcl}
         * gives a second DD name - {@code XREFFIL1} over the alternate-index path of the very same
         * dataset {@code XREFFILE} opens - so it resolves to the repository's alternate-index finder over
         * one base cluster, never to a second table (gate G45).
         *
         * <p>Only {@code '00'} is accepted. A duplicate on a non-unique alternate key reports
         * {@code '22'} and therefore abends here, exactly as {@code IF XREFFILE-STATUS = '00'} at
         * {@code :400} requires.
         *
         * <p>What this read is <em>for</em> is the card number: {@code :495} moves
         * {@code XREF-CARD-NUM} into every generated transaction.
         *
         * @param acctId {@code MOVE TRANCAT-ACCT-ID TO FD-XREF-ACCT-ID} ({@code :204}), the alternate key
         * @throws AbendException if the read does not report {@code '00'}
         */
        void getXrefData(long acctId) {
            String keyImage = job.codec.movePic9(acctId, CardXrefRecord.XREF_ACCT_ID_LENGTH);  //     L204

            // READ XREF-FILE INTO CARD-XREF-RECORD KEY IS FD-XREF-ACCT-ID ... END-READ           L394-398
            CardXrefRepository.ReadResult result =
                    xrefRepository.readByAccountIdViaAltIndex(acctId);
            if (result.isNotFound()) {                                                        //     L396
                sysout.write(ACCOUNT_NOT_FOUND_PREFIX + keyImage);                            //     L397
            }

            String status = result.status();
            applResultFromOkStatus(status);                                                   // L400-404
            if (!workingStorage.applAok()) {                                                  //     L405
                throw reportAndAbend(ERROR_READING_XREFFILE, status);                         // L408-411
            }

            cardXrefRecord = result.record().orElseThrow(() -> new IllegalStateException(
                    "A read of " + XREFFILE_DD_NAME + " reported file status " + FileStatus.OK
                            + " with no decoded record, so READ ... INTO CARD-XREF-RECORD had nothing to "
                            + "transfer. Only the success arm carries a record, and it always does."));
        }

        // ---------------------------------------------------------------------------------------------
        // 1200-GET-INTEREST-RATE and 1200-A-GET-DEFAULT-INT-RATE - app/cbl/CBACT04C.cbl:415-460.
        // ---------------------------------------------------------------------------------------------

        /**
         * Looks up the disclosure group's rate, falling back to the default group:
         * {@code app/cbl/CBACT04C.cbl:415-440}.
         *
         * <p><strong>This is the one read in the program that accepts {@code '23'}.</strong> The guard at
         * {@code :422} is {@code IF DISCGRP-STATUS = '00' OR '23'}, so a missing group is a branch and not
         * a failure: the {@code INVALID KEY} phrase displays two lines, and the {@code IF} at {@code :436}
         * then moves {@code 'DEFAULT'} into the group id and retries.
         *
         * <p><strong>Only the account group id changes on the retry.</strong> {@code :437} moves into
         * {@code FD-DIS-ACCT-GROUP-ID} alone; the transaction type and category components of the key are
         * left exactly as {@code :211-212} set them, so the retry looks up the default group's rate
         * <em>for the same transaction category</em>.
         *
         * <p><strong>The stale window.</strong> On {@code '23'} the {@code READ ... INTO} transferred
         * nothing, so {@code DIS-GROUP-RECORD} still holds the previous record - and therefore the
         * previous rate - from here until {@code 1200-A} succeeds. That is reproduced by replacing the
         * record area only when a read carries one, and never clearing it. The window is not observable on
         * any reachable path, because {@code 1200-A} either populates the area or abends; it is preserved
         * because a future change that made it observable must find the legacy behaviour here, not a
         * tidied-up version of it.
         *
         * @throws AbendException if the read reports a status that is neither {@code '00'} nor {@code '23'},
         *                        or if the retry does not report {@code '00'}
         */
        void getInterestRate() {
            // READ DISCGRP-FILE INTO DIS-GROUP-RECORD ... END-READ.                              L416-420
            DisclosureGroupRead read = discgrpFile.readByKey(fdDiscgrpRec.disGroupKey());
            if (read.isNotFound()) {                                                          //     L417
                sysout.write(DISCLOSURE_GROUP_RECORD_MISSING);                                 //     L418
                sysout.write(TRY_WITH_DEFAULT_GROUP_CODE);                                     //     L419
            }
            // The INTO phrase transfers on success only. Never an else-clear: see the stale window above.
            read.record().ifPresent(record -> disGroupRecord = record);

            String status = read.status();
            if (FileStatus.isOkOrNotFound(status)) {                                           //     L422
                workingStorage.moveToApplResult(APPL_AOK);                                     //     L423
            } else {
                workingStorage.moveToApplResult(APPL_RESULT_FATAL);                            //     L425
            }
            if (!workingStorage.applAok()) {                                                   //     L428
                throw reportAndAbend(ERROR_READING_DISCGRP, status);                           // L431-434
            }

            if (FileStatus.isNotFound(status)) {                                               //     L436
                fdDiscgrpRec.disAcctGroupId(DEFAULT_ACCT_GROUP_ID);                            //     L437
                getDefaultInterestRate();                                                      //     L438
            }
        }

        /**
         * Retries the rate lookup against the default group: {@code app/cbl/CBACT04C.cbl:443-460}.
         *
         * <p><strong>No {@code INVALID KEY} phrase.</strong> {@code :444} is a bare
         * {@code READ DISCGRP-FILE INTO DIS-GROUP-RECORD}, so a missing default group displays no
         * not-found line at all - it simply fails the {@code IF DISCGRP-STATUS = '00'} guard at
         * {@code :446} and abends with {@value #ERROR_READING_DEFAULT_DISCGRP}. That asymmetry with
         * {@link #getInterestRate()} is the source's, and it is what makes a {@code '23'} on the retry
         * fatal.
         *
         * @throws AbendException if the retry does not report {@code '00'}
         */
        void getDefaultInterestRate() {
            // READ DISCGRP-FILE INTO DIS-GROUP-RECORD                                                L444
            DisclosureGroupRead read = discgrpFile.readByKey(fdDiscgrpRec.disGroupKey());
            read.record().ifPresent(record -> disGroupRecord = record);

            String status = read.status();
            applResultFromOkStatus(status);                                                   // L446-450
            if (!workingStorage.applAok()) {                                                  //     L452
                throw reportAndAbend(ERROR_READING_DEFAULT_DISCGRP, status);                  // L455-458
            }
        }

        // ---------------------------------------------------------------------------------------------
        // 1300-COMPUTE-INTEREST and 1300-B-WRITE-TX - app/cbl/CBACT04C.cbl:462-515.
        // ---------------------------------------------------------------------------------------------

        /**
         * Computes this category's interest, accumulates it and writes the transaction:
         * {@code app/cbl/CBACT04C.cbl:462-470}.
         *
         * <pre>
         * COMPUTE WS-MONTHLY-INT
         *  = ( TRAN-CAT-BAL * DIS-INT-RATE) / 1200                                             L464-465
         * ADD WS-MONTHLY-INT  TO WS-TOTAL-INT                                                      L467
         * PERFORM 1300-B-WRITE-TX.                                                                 L468
         * </pre>
         *
         * <p>Three statements, in this order, and the order is observable: the transaction the third one
         * writes carries the value the first one computed, and the running total the second one keeps is
         * what the next account break posts.
         *
         * @param item the record whose {@code TRAN-CAT-BAL} is being charged
         * @throws AbendException if the transaction write does not report {@code '00'}
         */
        void computeInterest(TranCatBalRecord item) {
            // COMPUTE WS-MONTHLY-INT = ( TRAN-CAT-BAL * DIS-INT-RATE) / 1200                     L464-465
            workingStorage.moveToMonthlyInterest(
                    computeMonthlyInterest(item.tranCatBal(), disGroupRecord.disIntRate()));

            // ADD WS-MONTHLY-INT TO WS-TOTAL-INT                                                     L467
            workingStorage.addToTotalInterest(workingStorage.monthlyInterest());

            // PERFORM 1300-B-WRITE-TX.                                                               L468
            writeTx();
        }

        /**
         * Builds and writes the generated interest transaction: {@code app/cbl/CBACT04C.cbl:473-515}.
         *
         * <p><strong>The identifier.</strong> {@code ADD 1 TO WS-TRANID-SUFFIX} ({@code :474}) then
         * {@code STRING PARM-DATE, WS-TRANID-SUFFIX DELIMITED BY SIZE INTO TRAN-ID} ({@code :476-480}):
         * the ten-character PARM followed by the suffix's six zero-filled digits, which is exactly
         * {@code TRAN-ID X(16)}. The suffix is <strong>never reset</strong>, so it counts transactions
         * across account boundaries for the life of the run.
         *
         * <p><strong>The description keeps the previous iteration's tail.</strong>
         * {@code STRING 'Int. for a/c ' , ACCT-ID DELIMITED BY SIZE INTO TRAN-DESC} ({@code :485-489})
         * transfers 24 characters into a 100-byte field, and COBOL {@code STRING} does not blank the
         * remainder of its receiver - so bytes 25 to 100 hold whatever the previous transaction left
         * there, and on the first transaction of a run they hold the spaces the record area was allocated
         * with. {@link TranRecord#stringIntoTranDesc(String...)} is the transfer that behaves that way;
         * a padding setter would silently erase the residue and change the 350 bytes that land in the
         * output.
         *
         * <p><strong>Every other field is a plain {@code MOVE}, and three of them are literals.</strong>
         * {@code '01'} into {@code TRAN-TYPE-CD X(02)}; {@code '05'} into {@code TRAN-CAT-CD 9(04)}, so it
         * stores {@code 0005}; {@code 'System'} into {@code TRAN-SOURCE X(10)}, so it stores
         * {@code "System    "}. {@code TRAN-MERCHANT-ID} takes zero and the three merchant text fields
         * take {@code SPACES}. {@code TRAN-CARD-NUM} takes {@code XREF-CARD-NUM} - the whole reason
         * {@code 1110-GET-XREF-DATA} runs.
         *
         * <p><strong>Both timestamps are the same value.</strong> {@code :496} composes it once and
         * {@code :497-498} move it into {@code TRAN-ORIG-TS} and {@code TRAN-PROC-TS}, so the two are
         * identical by construction rather than by coincidence.
         *
         * <p>{@code FILLER X(20)} is never written by this paragraph, so the record's last 20 bytes are
         * the spaces it was allocated with, and all {@value TranRecord#RECORD_LENGTH} bytes reach the
         * output (gates G19 and G21).
         *
         * @throws AbendException if the write does not report {@code '00'}
         */
        void writeTx() {
            // ADD 1 TO WS-TRANID-SUFFIX                                                              L474
            workingStorage.addOneToTranidSuffix();

            // STRING PARM-DATE, WS-TRANID-SUFFIX DELIMITED BY SIZE INTO TRAN-ID END-STRING.      L476-480
            tranRecord.stringIntoTranId(parmDate, workingStorage.tranidSuffixImage(job.codec));

            tranRecord.moveTranTypeCd(GENERATED_TRAN_TYPE_CD);                                //     L482
            tranRecord.moveTranCatCd(GENERATED_TRAN_CAT_CD);                                  //     L483
            tranRecord.moveTranSource(GENERATED_TRAN_SOURCE);                                 //     L484

            // STRING 'Int. for a/c ' , ACCT-ID DELIMITED BY SIZE INTO TRAN-DESC END-STRING      L485-489
            tranRecord.stringIntoTranDesc(GENERATED_TRAN_DESC_PREFIX, accountRecord.rawAcctId());

            tranRecord.moveTranAmt(workingStorage.monthlyInterest());                         //     L490
            tranRecord.moveTranMerchantId(0L);                                                //     L491
            tranRecord.moveTranMerchantName("");                                              //     L492
            tranRecord.moveTranMerchantCity("");                                              //     L493
            tranRecord.moveTranMerchantZip("");                                               //     L494
            tranRecord.moveTranCardNum(cardXrefRecord.xrefCardNum());                         //     L495

            // PERFORM Z-GET-DB2-FORMAT-TIMESTAMP                                                     L496
            String timestamp = job.db2FormatTimestamp();
            tranRecord.moveTranOrigTs(timestamp);                                             //     L497
            tranRecord.moveTranProcTs(timestamp);                                             //     L498

            // WRITE FD-TRANFILE-REC FROM TRAN-RECORD                                                 L500
            //
            // Persisted on its own, for the same reason the rewrite is: the generation is being built
            // record by record, each WRITE is durable when it completes, and the abend at :632 truncates
            // the generation rather than emptying it. Every transaction written before the failure is a
            // transaction the COBOL leaves on the dataset.
            TransactionRepository.WriteResult result = job.unitOfWork.persistVerb(
                    WRITE_TRANFILE_VERB, () -> tranFile.writeSequential(tranRecord));
            String status = result.status();
            applResultFromOkStatus(status);                                                   // L501-505
            if (!workingStorage.applAok()) {                                                  //     L507
                throw reportAndAbend(ERROR_WRITING_TRANSACTION, status);                      // L510-513
            }
        }

        // ---------------------------------------------------------------------------------------------
        // The status ladder, the fatal arm, 9910-DISPLAY-IO-STATUS and 9999-ABEND-PROGRAM.
        // ---------------------------------------------------------------------------------------------

        /**
         * The two-armed ladder every paragraph but {@code 1000} and {@code 1200} uses:
         * {@code IF <file>-STATUS = '00' MOVE 0 TO APPL-RESULT ELSE MOVE 12 TO APPL-RESULT END-IF}.
         *
         * <p>Written once because it is literally the same four lines in eleven paragraphs. The two
         * paragraphs that differ do not use it: {@code 1000-TCATBALF-GET-NEXT} has a three-armed ladder
         * with an end-of-file arm, and {@code 1200-GET-INTEREST-RATE} accepts {@code '23'} as well.
         *
         * @param status the file status the verb reported
         */
        private void applResultFromOkStatus(String status) {
            if (FileStatus.isOk(status)) {
                workingStorage.moveToApplResult(APPL_AOK);
            } else {
                workingStorage.moveToApplResult(APPL_RESULT_FATAL);
            }
        }

        /**
         * The fatal arm, shared by every I/O paragraph: display the message, render the status, abend.
         *
         * <p>Returns the exception rather than throwing it, so the call site reads
         * {@code throw reportAndAbend(...)} and the compiler can see that the path ends there.
         *
         * @param errorText the paragraph's own message, byte-exact
         * @param status    the file status to render through {@code 9910-DISPLAY-IO-STATUS}
         * @return the abend to throw
         */
        private AbendException reportAndAbend(String errorText, String status) {
            // DISPLAY '<the paragraph's message>'
            sysout.write(errorText);

            // MOVE <file>-STATUS TO IO-STATUS, then PERFORM 9910-DISPLAY-IO-STATUS.
            String statusLine = displayIoStatus(status);

            // PERFORM 9999-ABEND-PROGRAM.
            return abendProgram(errorText + " - " + statusLine);
        }

        /**
         * {@code 9910-DISPLAY-IO-STATUS} - {@code app/cbl/CBACT04C.cbl:635-648}.
         *
         * <p>The paragraph's two arms - a numeric status rendered as {@code '00' + status}, and a
         * non-numeric or {@code '9x'} status rendered as the first character followed by the second
         * character's binary value in three digits - are both {@link FileStatus#toDisplayLine(String)},
         * which is the module's single implementation of them.
         *
         * @param status the file status
         * @return the line that was displayed, so the abend's reason can carry it
         */
        private String displayIoStatus(String status) {
            String statusLine = FileStatus.toDisplayLine(status);
            sysout.write(statusLine);
            return statusLine;
        }

        /**
         * {@code 9999-ABEND-PROGRAM} - {@code app/cbl/CBACT04C.cbl:628-632}.
         *
         * <pre>
         * DISPLAY 'ABENDING PROGRAM'                                                               L629
         * MOVE 0 TO TIMING                                                                         L630
         * MOVE 999 TO ABCODE                                                                       L631
         * CALL 'CEE3ABD'.                                                                          L632
         * </pre>
         *
         * <p>{@link AbendException#standard(String, int, String)} supplies the abend code 999 and the
         * timing 0 those two {@code MOVE} statements set, and carries {@code APPL-RESULT} as the return
         * code. {@link BatchConfig}'s listeners then put that value on the step's exit status and the
         * process exit code, so the {@code 0} / {@code 4} / {@code 8} / {@code 12} contract of gate G35
         * holds without this class touching an exit status itself.
         *
         * @param reason what failed, for the diagnostic the exception carries
         * @return the abend to throw
         */
        private AbendException abendProgram(String reason) {
            sysout.write(AbendException.ABEND_DISPLAY_TEXT);                                  //     L629
            return AbendException.standard(PROGRAM_ID, workingStorage.applResult(), reason);   // L630-632
        }
    }

    // =================================================================================================
    // Z-GET-DB2-FORMAT-TIMESTAMP - app/cbl/CBACT04C.cbl:613-626.
    // =================================================================================================

    /**
     * Composes {@code DB2-FORMAT-TS} from the clock: {@code app/cbl/CBACT04C.cbl:613-626}.
     *
     * <p>The shape is {@code YYYY-MM-DD-HH.MM.SS.mm0000} - <strong>three hyphens and three dots</strong>,
     * exactly {@value #DB2_TIMESTAMP_LENGTH} characters. The comment at {@code :140} spells it out, and
     * the redefinition at {@code :151-165} accounts for every byte.
     *
     * <p><strong>The third separator is a hyphen, not a space and not a {@code 'T'}.</strong> This is the
     * DB2 for z/OS internal timestamp format, not ISO-8601, and {@code :623} moves {@code '-'} into all
     * three separator positions in one statement. A run that emitted {@code "2022-07-18 12.34.56.780000"}
     * would differ from the legacy output in one byte per generated transaction, twice over, because both
     * {@code TRAN-ORIG-TS} and {@code TRAN-PROC-TS} carry it.
     *
     * <p>{@code DB2-MIL PIC 9(02)} takes {@code COB-MIL}, which {@code FUNCTION CURRENT-DATE} fills with
     * hundredths of a second, and {@code DB2-REST X(04)} takes the literal {@value #DB2_TIMESTAMP_REST} -
     * so the fractional part is always two significant digits followed by four zeros, and the value is
     * never microsecond-precise however precise the clock is.
     *
     * <p>The clock is injected, so a parity case can pin this value and assert the two timestamps byte for
     * byte. It is read in the clock's own zone because {@code FUNCTION CURRENT-DATE} reports local time.
     *
     * @return exactly {@value #DB2_TIMESTAMP_LENGTH} characters
     */
    public String db2FormatTimestamp() {
        // MOVE FUNCTION CURRENT-DATE TO COBOL-TS                                                    L614
        CobolTimestamp current = currentDate();

        // The eight MOVEs at L615-L622, then the separators at L623-L624, in one composition: every one
        // of the 26 bytes is written by the paragraph, so nothing is carried over from a previous call.
        return current.yyyy()                                                                 //     L615
                + DB2_TIMESTAMP_HYPHEN                                                        //     L623
                + current.mm()                                                                //     L616
                + DB2_TIMESTAMP_HYPHEN                                                        //     L623
                + current.dd()                                                                //     L617
                + DB2_TIMESTAMP_HYPHEN                                                        //     L623
                + current.hh()                                                                //     L618
                + DB2_TIMESTAMP_DOT                                                           //     L624
                + current.min()                                                               //     L619
                + DB2_TIMESTAMP_DOT                                                           //     L624
                + current.ss()                                                                //     L620
                + DB2_TIMESTAMP_DOT                                                           //     L624
                + current.mil()                                                               //     L621
                + DB2_TIMESTAMP_REST;                                                         //     L622
    }

    /**
     * {@code FUNCTION CURRENT-DATE} into {@code COBOL-TS} - {@code app/cbl/CBACT04C.cbl:141-149}.
     *
     * <p>The intrinsic returns {@value #COBOL_TIMESTAMP_LENGTH} characters:
     * {@code YYYY} {@code MM} {@code DD} {@code HH} {@code MM} {@code SS} {@code hundredths} and a
     * five-character offset from Greenwich. All eight are modelled even though
     * {@link #db2FormatTimestamp()} uses seven of them, because the eighth occupies declared bytes of
     * {@code COBOL-TS} and a partial model of a group item is a model that cannot be checked.
     *
     * <p>Every component is produced by the numeric mover, so each is zero-filled on the left to its
     * declared width exactly as a {@code PIC 9} receiver would be.
     *
     * @return the current date and time in the clock's own zone; never {@code null}
     */
    public CobolTimestamp currentDate() {
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), clock.getZone());
        int offsetSeconds = clock.getZone().getRules().getOffset(clock.instant()).getTotalSeconds();
        return new CobolTimestamp(
                codec.movePic9(now.getYear(), 4),
                codec.movePic9(now.getMonthValue(), 2),
                codec.movePic9(now.getDayOfMonth(), 2),
                codec.movePic9(now.getHour(), 2),
                codec.movePic9(now.getMinute(), 2),
                codec.movePic9(now.getSecond(), 2),
                codec.movePic9(now.getNano() / NANOS_PER_HUNDREDTH, 2),
                greenwichOffsetImage(offsetSeconds));
    }

    /**
     * Renders {@code COB-REST PIC X(05)}: the offset from Greenwich as {@code +hhmm} or {@code -hhmm}.
     *
     * <p>Never read by {@code Z-GET-DB2-FORMAT-TIMESTAMP}, and modelled anyway so {@code COBOL-TS} is
     * complete at its declared {@value #COBOL_TIMESTAMP_LENGTH} characters.
     *
     * @param totalSeconds the zone offset in seconds, which may be negative
     * @return exactly five characters
     */
    private String greenwichOffsetImage(int totalSeconds) {
        char sign = totalSeconds < 0 ? '-' : '+';
        int magnitude = Math.abs(totalSeconds);
        int hours = magnitude / SECONDS_PER_HOUR;
        int minutes = (magnitude % SECONDS_PER_HOUR) / SECONDS_PER_MINUTE;
        return sign + codec.movePic9(hours, 2) + codec.movePic9(minutes, 2);
    }

    /** Nanoseconds in one hundredth of a second, the precision {@code FUNCTION CURRENT-DATE} reports. */
    private static final int NANOS_PER_HUNDREDTH = 10_000_000;

    /** Seconds in an hour, for rendering {@code COB-REST}. */
    private static final int SECONDS_PER_HOUR = 3_600;

    /** Seconds in a minute, for rendering {@code COB-REST}. */
    private static final int SECONDS_PER_MINUTE = 60;


    /**
     * {@code 01 COBOL-TS} - {@code app/cbl/CBACT04C.cbl:141-149}, the receiver of
     * {@code FUNCTION CURRENT-DATE}.
     *
     * <p>Eight character items summing to {@value #COBOL_TIMESTAMP_LENGTH} bytes:
     * {@code X(04) + X(02) + X(02) + X(02) + X(02) + X(02) + X(02) + X(05)}. Each component is stored as
     * the group declares it, so each is characters rather than a number and none of them is trimmed.
     *
     * @param yyyy {@code COB-YYYY PIC X(04)} - the four-digit year
     * @param mm   {@code COB-MM PIC X(02)} - the month, {@code 01} to {@code 12}
     * @param dd   {@code COB-DD PIC X(02)} - the day of the month
     * @param hh   {@code COB-HH PIC X(02)} - the hour, {@code 00} to {@code 23}
     * @param min  {@code COB-MIN PIC X(02)} - the minute
     * @param ss   {@code COB-SS PIC X(02)} - the second
     * @param mil  {@code COB-MIL PIC X(02)} - hundredths of a second, which is the whole of the
     *             intrinsic's sub-second precision
     * @param rest {@code COB-REST PIC X(05)} - the offset from Greenwich, never read by this program
     */
    public record CobolTimestamp(String yyyy, String mm, String dd, String hh, String min, String ss,
                                 String mil, String rest) {

        /**
         * Validates every component's declared width, so a partially composed timestamp cannot exist.
         *
         * @throws NullPointerException     if any component is {@code null}
         * @throws IllegalArgumentException if any component is not exactly its declared width
         */
        public CobolTimestamp {
            requireWidth(yyyy, 4, "COB-YYYY");
            requireWidth(mm, 2, "COB-MM");
            requireWidth(dd, 2, "COB-DD");
            requireWidth(hh, 2, "COB-HH");
            requireWidth(min, 2, "COB-MIN");
            requireWidth(ss, 2, "COB-SS");
            requireWidth(mil, 2, "COB-MIL");
            requireWidth(rest, 5, "COB-REST");
        }

        /**
         * The whole {@value #COBOL_TIMESTAMP_LENGTH}-character group image, in declaration order - which
         * is what {@code FUNCTION CURRENT-DATE} returned.
         *
         * @return the group's characters; never {@code null}
         */
        public String image() {
            return yyyy + mm + dd + hh + min + ss + mil + rest;
        }

        /**
         * Rejects a component whose width does not match its {@code PICTURE}.
         *
         * @param value     the component
         * @param width     its declared width
         * @param cobolName its COBOL name, for the diagnostic
         * @throws NullPointerException     if {@code value} is {@code null}
         * @throws IllegalArgumentException if {@code value} is not exactly {@code width} characters
         */
        private static void requireWidth(String value, int width, String cobolName) {
            Objects.requireNonNull(value, cobolName + " is a character item and holds characters, never "
                    + "null");
            if (value.length() != width) {
                throw new IllegalArgumentException(cobolName + " is PIC X(" + width + "), so it holds "
                        + "exactly " + width + " character(s); '" + value + "' is " + value.length()
                        + ". COBOL-TS is a group item of exactly " + COBOL_TIMESTAMP_LENGTH
                        + " bytes and a component of the wrong width would move every item after it.");
            }
        }
    }

    // =================================================================================================
    // WORKING-STORAGE SECTION - app/cbl/CBACT04C.cbl:133-173.
    //
    // An instance per run, never a static field (practice B9, gate G53): two concurrent executions must
    // not share an accumulator, and a parity case must not depend on what ran before it.
    // =================================================================================================

    /**
     * One run's {@code WORKING-STORAGE}: {@code APPL-RESULT}, {@code END-OF-FILE}, {@code WS-MISC-VARS}
     * and {@code WS-COUNTERS}.
     *
     * <p>Every mutation is a named method carrying the COBOL statement it reproduces, so the register's
     * value transitions are auditable against the source rather than inferred from assignments.
     *
     * <p><strong>Both interest accumulators are {@code BigDecimal} at scale exactly 2</strong>, because
     * {@code WS-MONTHLY-INT} and {@code WS-TOTAL-INT} are both {@code PIC S9(09)V99}
     * ({@code app/cbl/CBACT04C.cbl:168-169}). Neither is ever a binary floating-point primitive, and
     * every store names its scale and its rounding mode (gates G22, G23 and G24).
     */
    static final class WorkingStorage {

        /** {@code 01 APPL-RESULT PIC S9(9) COMP} - {@code app/cbl/CBACT04C.cbl:133}. */
        private int applResult;

        /** {@code 01 END-OF-FILE PIC X(01) VALUE 'N'} - {@code app/cbl/CBACT04C.cbl:137}. */
        private String endOfFile = END_OF_FILE_NO;

        /** {@code 05 WS-LAST-ACCT-NUM PIC X(11) VALUE SPACES} - {@code app/cbl/CBACT04C.cbl:167}. */
        private String lastAcctNum = LAST_ACCT_NUM_INITIAL;

        /** {@code 05 WS-MONTHLY-INT PIC S9(09)V99} - {@code app/cbl/CBACT04C.cbl:168}. */
        private BigDecimal monthlyInterest = CobolDecimal.monetaryZero();

        /** {@code 05 WS-TOTAL-INT PIC S9(09)V99} - {@code app/cbl/CBACT04C.cbl:169}. */
        private BigDecimal totalInterest = CobolDecimal.monetaryZero();

        /** {@code 05 WS-FIRST-TIME PIC X(01) VALUE 'Y'} - {@code app/cbl/CBACT04C.cbl:170}. */
        private String firstTime = FIRST_TIME_YES;

        /** {@code 05 WS-RECORD-COUNT PIC 9(09) VALUE 0} - {@code app/cbl/CBACT04C.cbl:172}. */
        private long recordCount;

        /** {@code 05 WS-TRANID-SUFFIX PIC 9(06) VALUE 0} - {@code app/cbl/CBACT04C.cbl:173}. */
        private long tranidSuffix;

        // ---------------------------------------------------------------------------------------------
        // APPL-RESULT and its two 88-level condition names.
        // ---------------------------------------------------------------------------------------------

        /**
         * {@code 88 APPL-AOK VALUE 0} - {@code app/cbl/CBACT04C.cbl:134}.
         *
         * @return whether the register is zero
         */
        boolean applAok() {
            return applResult == APPL_AOK;
        }

        /**
         * {@code 88 APPL-EOF VALUE 16} - {@code app/cbl/CBACT04C.cbl:135}.
         *
         * @return whether the register is sixteen
         */
        boolean applEof() {
            return applResult == APPL_EOF;
        }

        /**
         * {@code MOVE <n> TO APPL-RESULT}.
         *
         * @param value the value to move
         */
        void moveToApplResult(int value) {
            applResult = value;
        }

        /**
         * The register's value, which is the abend's return code.
         *
         * @return {@code APPL-RESULT}
         */
        int applResult() {
            return applResult;
        }

        // ---------------------------------------------------------------------------------------------
        // END-OF-FILE. Two predicates, not one, because COBOL asks two different questions.
        // ---------------------------------------------------------------------------------------------

        /**
         * {@code END-OF-FILE = 'Y'} - the loop's own test at {@code app/cbl/CBACT04C.cbl:188}.
         *
         * @return whether the flag is {@code 'Y'}
         */
        boolean endOfFileIsYes() {
            return END_OF_FILE_YES.equals(endOfFile);
        }

        /**
         * {@code END-OF-FILE = 'N'} - the guard at {@code app/cbl/CBACT04C.cbl:189} and {@code :191}.
         *
         * <p>Deliberately <strong>not</strong> the negation of {@link #endOfFileIsYes()}. COBOL asks two
         * different questions and collapsing them would silently assume a two-valued flag that only the
         * program's own {@code MOVE} statements happen to guarantee - and it is exactly that third value
         * which makes the unreachable {@code ELSE} at {@code :220} reachable from a test.
         *
         * @return whether the flag is {@code 'N'}
         */
        boolean endOfFileIsNo() {
            return END_OF_FILE_NO.equals(endOfFile);
        }

        /**
         * {@code MOVE 'Y' TO END-OF-FILE} - {@code app/cbl/CBACT04C.cbl:340}.
         *
         * <p>Package-visible and accepting any single character, because {@code END-OF-FILE PIC X(01)} is
         * a character field and the program's two-valued use of it is a property of the program rather
         * than of the field. A test that moves a third value drives the {@code ELSE} arm at {@code :220},
         * which is the only way that branch is reachable in COBOL either.
         *
         * @param value exactly one character
         * @throws NullPointerException     if {@code value} is {@code null}
         * @throws IllegalArgumentException if {@code value} is not exactly one character
         */
        void moveEndOfFile(String value) {
            Objects.requireNonNull(value, "END-OF-FILE is PIC X(01) and holds a character, never null");
            if (value.length() != 1) {
                throw new IllegalArgumentException("END-OF-FILE is PIC X(01), so it holds exactly one "
                        + "character; '" + value + "' is " + value.length() + ". A wider value would be "
                        + "truncated on the right and a narrower one space-padded, and neither is a move "
                        + "this program performs.");
            }
            endOfFile = value;
        }

        /**
         * The flag as stored.
         *
         * @return one character; never {@code null}
         */
        String endOfFileFlag() {
            return endOfFile;
        }

        // ---------------------------------------------------------------------------------------------
        // WS-FIRST-TIME - the guard that stops the first record from rewriting an unread account.
        // ---------------------------------------------------------------------------------------------

        /**
         * {@code WS-FIRST-TIME = 'Y'} - the test at {@code app/cbl/CBACT04C.cbl:195} is
         * {@code IF WS-FIRST-TIME NOT = 'Y'}, so the caller negates this.
         *
         * @return whether the flag is still {@code 'Y'}
         */
        boolean firstTimeIsYes() {
            return FIRST_TIME_YES.equals(firstTime);
        }

        /**
         * {@code MOVE 'N' TO WS-FIRST-TIME} - {@code app/cbl/CBACT04C.cbl:198}.
         *
         * @param value exactly one character
         * @throws NullPointerException     if {@code value} is {@code null}
         * @throws IllegalArgumentException if {@code value} is not exactly one character
         */
        void moveFirstTime(String value) {
            Objects.requireNonNull(value, "WS-FIRST-TIME is PIC X(01) and holds a character, never null");
            if (value.length() != 1) {
                throw new IllegalArgumentException("WS-FIRST-TIME is PIC X(01), so it holds exactly one "
                        + "character; '" + value + "' is " + value.length() + '.');
            }
            firstTime = value;
        }

        /**
         * The flag as stored.
         *
         * @return one character; never {@code null}
         */
        String firstTimeFlag() {
            return firstTime;
        }

        // ---------------------------------------------------------------------------------------------
        // WS-LAST-ACCT-NUM - the account-break comparand.
        // ---------------------------------------------------------------------------------------------

        /**
         * {@code WS-LAST-ACCT-NUM PIC X(11)} as stored, untrimmed.
         *
         * <p>Eleven spaces until the first record is processed, which is what makes that record a break.
         *
         * @return exactly {@value #LAST_ACCT_NUM_LENGTH} characters; never {@code null}
         */
        String lastAcctNum() {
            return lastAcctNum;
        }

        /**
         * {@code MOVE TRANCAT-ACCT-ID TO WS-LAST-ACCT-NUM} - {@code app/cbl/CBACT04C.cbl:201}.
         *
         * <p>A {@code PIC 9(11)} sender into a {@code PIC X(11)} receiver, so the eleven characters of the
         * sender's display image are moved as characters.
         *
         * @param acctIdImage the account id's eleven-digit display image
         * @throws NullPointerException     if {@code acctIdImage} is {@code null}
         * @throws IllegalArgumentException if it is not exactly {@value #LAST_ACCT_NUM_LENGTH} characters
         */
        void moveLastAcctNum(String acctIdImage) {
            Objects.requireNonNull(acctIdImage, "WS-LAST-ACCT-NUM is PIC X(11) and receives the eleven "
                    + "characters of TRANCAT-ACCT-ID's display image, never null");
            if (acctIdImage.length() != LAST_ACCT_NUM_LENGTH) {
                throw new IllegalArgumentException("WS-LAST-ACCT-NUM is PIC X(" + LAST_ACCT_NUM_LENGTH
                        + ") and TRANCAT-ACCT-ID is PIC 9(" + LAST_ACCT_NUM_LENGTH + "), so the move at "
                        + "app/cbl/CBACT04C.cbl:201 is width-for-width; '" + acctIdImage + "' is "
                        + acctIdImage.length() + " character(s). A narrower value would be space-padded "
                        + "and would then never compare equal to a full-width account id.");
            }
            lastAcctNum = acctIdImage;
        }

        // ---------------------------------------------------------------------------------------------
        // WS-MONTHLY-INT and WS-TOTAL-INT - both PIC S9(09)V99, both scale 2, both truncating.
        // ---------------------------------------------------------------------------------------------

        /**
         * {@code WS-MONTHLY-INT} as stored.
         *
         * @return the value, with {@link BigDecimal#scale()} of exactly 2
         */
        BigDecimal monthlyInterest() {
            return monthlyInterest;
        }

        /**
         * {@code COMPUTE WS-MONTHLY-INT = ...} - the store at {@code app/cbl/CBACT04C.cbl:464}.
         *
         * <p>The receiver is {@code PIC S9(09)V99}, so the value is truncated to two decimal places and
         * wrapped to nine integer digits, which is what a {@code COMPUTE} with neither {@code ROUNDED}
         * nor {@code ON SIZE ERROR} does.
         *
         * @param value the computed interest
         * @throws NullPointerException if {@code value} is {@code null}
         */
        void moveToMonthlyInterest(BigDecimal value) {
            Objects.requireNonNull(value, "WS-MONTHLY-INT is PIC S9(09)V99 and holds a number, never "
                    + "null");
            monthlyInterest = CobolDecimal.storeAtPicture(value, INTEREST_INTEGER_DIGITS,
                    CobolDecimal.MONETARY_SCALE);
        }

        /**
         * {@code WS-TOTAL-INT} as stored: the interest accumulated for the account currently in hand.
         *
         * @return the value, with {@link BigDecimal#scale()} of exactly 2
         */
        BigDecimal totalInterest() {
            return totalInterest;
        }

        /**
         * {@code MOVE 0 TO WS-TOTAL-INT} - {@code app/cbl/CBACT04C.cbl:200}, the per-account reset.
         *
         * <p>Zero <em>at scale 2</em>: a scale-0 zero would be a different byte image and would compare
         * unequal to a scale-2 zero under {@link BigDecimal#equals(Object)}.
         */
        void moveZeroToTotalInterest() {
            totalInterest = CobolDecimal.monetaryZero();
        }

        /**
         * {@code ADD WS-MONTHLY-INT TO WS-TOTAL-INT} - {@code app/cbl/CBACT04C.cbl:467}.
         *
         * <p>The sum is formed exactly and stored truncated to the receiver's two decimal places and nine
         * integer digits. Adding two scale-2 values is exact at scale 2, so in practice the store changes
         * nothing - the scale is named anyway, at the site, so the receiver's declared shape is visible
         * rather than inferred.
         *
         * @param addend {@code WS-MONTHLY-INT}
         * @throws NullPointerException if {@code addend} is {@code null}
         */
        void addToTotalInterest(BigDecimal addend) {
            Objects.requireNonNull(addend, "ADD WS-MONTHLY-INT TO WS-TOTAL-INT adds a number, never "
                    + "null");
            totalInterest = CobolDecimal.storeAtPicture(
                    CobolDecimal.add(totalInterest, addend, CobolDecimal.MONETARY_SCALE),
                    INTEREST_INTEGER_DIGITS, CobolDecimal.MONETARY_SCALE);
        }

        // ---------------------------------------------------------------------------------------------
        // WS-COUNTERS. Both wrap at their declared width, because ADD without ON SIZE ERROR truncates
        // high-order digits and no statement in this program writes ON SIZE ERROR.
        // ---------------------------------------------------------------------------------------------

        /**
         * {@code WS-RECORD-COUNT} - the number of category balance records processed.
         *
         * @return the count, in the range 0 to 999999999
         */
        long recordCount() {
            return recordCount;
        }

        /**
         * {@code ADD 1 TO WS-RECORD-COUNT} - {@code app/cbl/CBACT04C.cbl:192}.
         *
         * <p>{@code PIC 9(09)}, so the counter wraps to zero after 999999999 rather than growing past its
         * declared width.
         */
        void addOneToRecordCount() {
            recordCount = (recordCount + 1) % RECORD_COUNT_MODULUS;
        }

        /**
         * {@code WS-TRANID-SUFFIX} - the number of transactions written, and never reset.
         *
         * @return the suffix, in the range 0 to 999999
         */
        long tranidSuffix() {
            return tranidSuffix;
        }

        /**
         * {@code ADD 1 TO WS-TRANID-SUFFIX} - {@code app/cbl/CBACT04C.cbl:474}.
         *
         * <p>{@code PIC 9(06)}, so it wraps at a million. <strong>It is reset nowhere in the program</strong>
         * - not on an account break, not on a new group - so it counts every transaction the run writes.
         */
        void addOneToTranidSuffix() {
            tranidSuffix = (tranidSuffix + 1) % TRANID_SUFFIX_MODULUS;
        }

        /**
         * {@code WS-TRANID-SUFFIX}'s display image: {@value #TRANID_SUFFIX_WIDTH} zero-filled digits.
         *
         * <p>This is the image the {@code STRING ... DELIMITED BY SIZE} at
         * {@code app/cbl/CBACT04C.cbl:476-480} contributes - a numeric {@code DISPLAY} item transfers its
         * stored digits, so suffix 1 contributes {@code "000001"} and not {@code "1"}.
         *
         * @param codec the codec whose numeric mover applies the zero fill
         * @return exactly {@value #TRANID_SUFFIX_WIDTH} digits; never {@code null}
         * @throws NullPointerException if {@code codec} is {@code null}
         */
        String tranidSuffixImage(FixedWidthCodec codec) {
            Objects.requireNonNull(codec, "A codec is required to render WS-TRANID-SUFFIX at its "
                    + "declared width; the zero-fill rule lives there and is not restated here");
            return codec.movePic9(tranidSuffix, TRANID_SUFFIX_WIDTH);
        }
    }

    /**
     * The integer precision of {@code WS-MONTHLY-INT} and {@code WS-TOTAL-INT}, both
     * {@code PIC S9(09)V99}: {@value}.
     *
     * <p>Nine, not ten. {@code ACCT-CURR-BAL} is {@code PIC S9(10)V99} and the two are stored at
     * different widths, which is why {@link AccountRecord#MONETARY_INTEGER_DIGITS} is used for the balance
     * and this constant for the accumulators.
     */
    public static final int INTEREST_INTEGER_DIGITS = 9;


    // =================================================================================================
    // SYSOUT: one seam, so a run's line sequence can be captured exactly.
    // =================================================================================================

    /**
     * Where a {@code DISPLAY} goes.
     *
     * <p>One line per {@code DISPLAY}, in order, with no decoration. A logger would prepend a timestamp,
     * a level and a thread name, and this program's {@code SYSOUT} is parity-observable output rather than
     * diagnostics - so nothing here logs, and diagnostics that are not COBOL output travel in the abend's
     * message instead.
     */
    @FunctionalInterface
    public interface SysoutSink {

        /**
         * Emits one displayed line.
         *
         * @param line the line's characters, exactly as {@code DISPLAY} composed them; never {@code null}
         */
        void write(String line);
    }

    /**
     * The default {@code SYSOUT}: the process's standard output, in the dataset's own code page.
     *
     * <p>The code page is a parameter and never the platform default, because a displayed record is the
     * dataset's own bytes.
     *
     * @param charset the code page to encode the displayed lines in
     * @return a sink writing one line per call; never {@code null}
     * @throws NullPointerException if {@code charset} is {@code null}
     */
    public static SysoutSink standardOutput(Charset charset) {
        Objects.requireNonNull(charset, "A code page is required for SYSOUT: a displayed record is the "
                + "dataset's own bytes, and the platform default is never assumed");
        PrintStream stream = new PrintStream(new FileOutputStream(FileDescriptor.out), true, charset);
        return stream::println;
    }

    // =================================================================================================
    // What one record did - the item the writer stage consumes.
    // =================================================================================================

    /**
     * What processing one category balance record did.
     *
     * <p>The chunk step's output item, and the value a unit test asserts on. It carries no dataset state:
     * the verbs have already run by the time it exists, because relocating them into the writer would
     * reorder them against the {@code SYSOUT} sequence on the failure paths. See the enclosing class's
     * documentation.
     *
     * @param recordNumber        {@code WS-RECORD-COUNT} after this record, so the first record is 1
     * @param tranCatKeyImage     the record's 17-character {@code TRAN-CAT-KEY} image
     * @param accountRewritten    whether this record's account break rewrote the previous account -
     *                            {@code true} only when {@code 1050-UPDATE-ACCOUNT} ran, so never on the
     *                            first record
     * @param interestComputed    whether {@code DIS-INT-RATE} was non-zero and {@code 1300-COMPUTE-INTEREST}
     *                            therefore ran
     * @param feesComputed        whether {@code 1400-COMPUTE-FEES} was invoked; always equal to
     *                            {@code interestComputed}, because the two are performed from the same
     *                            guarded block, and carried separately so a test can prove the no-op is
     *                            still called
     * @param transactionWritten  whether a transaction reached the output dataset
     * @param monthlyInterest     {@code WS-MONTHLY-INT} for this record, at scale 2, or {@code 0.00} when
     *                            no interest was computed
     * @param tranId              the {@code TRAN-ID} written, or the empty string when none was
     */
    public record RecordOutcome(long recordNumber,
                                String tranCatKeyImage,
                                boolean accountRewritten,
                                boolean interestComputed,
                                boolean feesComputed,
                                boolean transactionWritten,
                                BigDecimal monthlyInterest,
                                String tranId) {

        /**
         * Enforces the outcome's invariants, so an inconsistent one cannot be constructed even by a test.
         *
         * @throws NullPointerException     if any reference component is {@code null}
         * @throws IllegalArgumentException if {@code monthlyInterest} is not at scale 2, if the fee call
         *                                  does not accompany the interest computation, or if a
         *                                  transaction was written without interest having been computed
         */
        public RecordOutcome {
            Objects.requireNonNull(tranCatKeyImage, "A TRAN-CAT-KEY image is required on an outcome: it "
                    + "is what identifies which record produced it");
            Objects.requireNonNull(monthlyInterest, "WS-MONTHLY-INT is carried as 0.00 rather than as "
                    + "null when no interest was computed, so no null escapes this type");
            Objects.requireNonNull(tranId, "TRAN-ID is carried as the empty string rather than as null "
                    + "when no transaction was written");
            if (monthlyInterest.scale() != CobolDecimal.MONETARY_SCALE) {
                throw new IllegalArgumentException("WS-MONTHLY-INT is PIC S9(09)V99, so its scale is "
                        + CobolDecimal.MONETARY_SCALE + ", but the outcome carries scale "
                        + monthlyInterest.scale() + ". A value at another scale serialises different "
                        + "bytes and is different money.");
            }
            if (feesComputed != interestComputed) {
                throw new IllegalArgumentException("1400-COMPUTE-FEES is performed from inside the same "
                        + "IF DIS-INT-RATE NOT = 0 block as 1300-COMPUTE-INTEREST "
                        + "(app/cbl/CBACT04C.cbl:214-217), so the two always run together. An outcome "
                        + "claiming otherwise would mean the fee call moved, which is gate G26.");
            }
            if (transactionWritten && !interestComputed) {
                throw new IllegalArgumentException("A transaction is written only by 1300-B-WRITE-TX, "
                        + "which is performed only from 1300-COMPUTE-INTEREST "
                        + "(app/cbl/CBACT04C.cbl:468), so one cannot be written without interest having "
                        + "been computed.");
            }
        }
    }

    // =================================================================================================
    // The DISCGRP access path: the seam, its handle, its outcome, and the JDBC implementation.
    //
    // Exactly the three verbs CBACT04C issues against this dataset - OPEN INPUT, a keyed READ, and CLOSE -
    // and no others, so no unused SQL surface is invented (AAP 0.3.5).
    // =================================================================================================

    /**
     * The {@code DISCGRP} dataset, as the interest calculator uses it.
     *
     * <p>An interface rather than a class so a unit test can drive all four read outcomes - found, not
     * found, an unreadable row and an unreachable dataset - with no database, which is what makes this
     * program's {@code '23'} retry and its two abend paths coverable.
     */
    public interface DisclosureGroupAccess {

        /**
         * The resolved dataset name, exactly as {@code carddemo.datasets} declares it.
         *
         * @return the dataset name; never {@code null} and never blank
         */
        String datasetName();

        /**
         * {@code OPEN INPUT DISCGRP-FILE} - {@code app/cbl/CBACT04C.cbl:272}.
         *
         * <p>Returns a handle rather than a status, so the open's outcome and the file's identity travel
         * together and no position or resolved shape is kept on a shared singleton.
         *
         * @return a handle whose {@link DisclosureGroupFile#openStatus()} reports whether the dataset was
         *         addressable; never {@code null}
         */
        DisclosureGroupFile open();
    }

    /**
     * One open {@code DISCGRP} file: its status, its keyed read and its close.
     *
     * <p>{@link AutoCloseable} so a caller may use try-with-resources, and every close is idempotent so an
     * explicit close inside the block is safe too.
     */
    public interface DisclosureGroupFile extends AutoCloseable {

        /**
         * The status the {@code OPEN} reported, which is what {@code app/cbl/CBACT04C.cbl:273} tests.
         *
         * @return two characters; never {@code null}
         */
        String openStatus();

        /**
         * {@code READ DISCGRP-FILE INTO DIS-GROUP-RECORD} - {@code app/cbl/CBACT04C.cbl:416} and
         * {@code :444}.
         *
         * <p>Both call sites read by the same 16-byte key; they differ only in whether the caller supplies
         * an {@code INVALID KEY} phrase, which is the caller's business and not this method's. A missing
         * record is reported as {@link FileStatus#NOT_FOUND} and never thrown, because {@code :422}
         * accepts it as a branch.
         *
         * @param keyImage the {@code DIS-GROUP-KEY} image, exactly
         *                 {@value DisclosureGroupRecord#DIS_GROUP_KEY_LENGTH} characters
         * @return the outcome; never {@code null}
         * @throws NullPointerException     if {@code keyImage} is {@code null}
         * @throws IllegalArgumentException if {@code keyImage} is not exactly the declared key width
         */
        DisclosureGroupRead readByKey(String keyImage);

        /**
         * {@code CLOSE DISCGRP-FILE} - {@code app/cbl/CBACT04C.cbl:561}.
         *
         * @return the status {@code app/cbl/CBACT04C.cbl:562} tests; never {@code null}
         */
        String closeFile();

        /** Releases the handle without reporting a status, for try-with-resources and for an abend path. */
        @Override
        void close();
    }

    /**
     * The outcome of one {@code DISCGRP} read.
     *
     * @param status the two-character file status: {@link FileStatus#OK} when a record was returned,
     *               {@link FileStatus#NOT_FOUND} for the {@code INVALID KEY} condition, or an
     *               implementation-specific permanent error
     * @param record the decoded record, present if and only if {@code status} is {@link FileStatus#OK} -
     *               because a {@code READ ... INTO} transfers on success only
     */
    public record DisclosureGroupRead(String status, Optional<DisclosureGroupRecord> record) {

        /**
         * Enforces the outcome's invariants.
         *
         * @throws NullPointerException     if either component is {@code null}
         * @throws IllegalArgumentException if {@code status} is not two characters, or if the record's
         *                                  presence disagrees with the status
         */
        public DisclosureGroupRead {
            Objects.requireNonNull(status, "A two-character FILE STATUS is required on a read outcome");
            Objects.requireNonNull(record, "An Optional is required, empty rather than null, so no null "
                    + "escapes this type");
            if (status.length() != FileStatus.STATUS_LENGTH) {
                throw new IllegalArgumentException("A FILE STATUS is exactly "
                        + FileStatus.STATUS_LENGTH + " characters; '" + status + "' is "
                        + status.length() + '.');
            }
            if (FileStatus.isOk(status) != record.isPresent()) {
                throw new IllegalArgumentException("READ ... INTO DIS-GROUP-RECORD transfers on file "
                        + "status " + FileStatus.OK + " and on no other, so a record must be present "
                        + "exactly when the status is " + FileStatus.OK + "; status '" + status
                        + "' was reported with the record "
                        + (record.isPresent() ? "present" : "absent") + '.');
            }
        }

        /**
         * A record was read: file status {@link FileStatus#OK}.
         *
         * @param found the decoded record; never {@code null}
         * @return the outcome
         * @throws NullPointerException if {@code found} is {@code null}
         */
        public static DisclosureGroupRead found(DisclosureGroupRecord found) {
            Objects.requireNonNull(found, "A found outcome carries the record that was read");
            return new DisclosureGroupRead(FileStatus.OK, Optional.of(found));
        }

        /**
         * The {@code INVALID KEY} condition: file status {@link FileStatus#NOT_FOUND}.
         *
         * <p>The outcome {@code app/cbl/CBACT04C.cbl:417-419} displays two lines for and {@code :436}
         * retries on. Expected, not exceptional.
         *
         * @return the outcome
         */
        public static DisclosureGroupRead notFound() {
            return new DisclosureGroupRead(FileStatus.NOT_FOUND, Optional.empty());
        }

        /**
         * A failure the COBOL vocabulary has no specific code for.
         *
         * @param status the status to report; must be two characters and must not be
         *               {@link FileStatus#OK}
         * @return the outcome
         * @throws NullPointerException     if {@code status} is {@code null}
         * @throws IllegalArgumentException if {@code status} is {@link FileStatus#OK} or not two
         *                                  characters
         */
        public static DisclosureGroupRead failed(String status) {
            return new DisclosureGroupRead(status, Optional.empty());
        }

        /**
         * Whether a record was returned.
         *
         * @return {@code true} for file status {@link FileStatus#OK}
         */
        public boolean isFound() {
            return FileStatus.isOk(status);
        }

        /**
         * Whether the {@code INVALID KEY} condition was raised.
         *
         * @return {@code true} for file status {@link FileStatus#NOT_FOUND}
         */
        public boolean isNotFound() {
            return FileStatus.isNotFound(status);
        }

        /**
         * The decoded record, or a failure.
         *
         * @return the record
         * @throws IllegalStateException if this outcome carries none
         */
        public DisclosureGroupRecord requireRecord() {
            return record.orElseThrow(() -> new IllegalStateException("This read reported file status '"
                    + status + "' and carries no record; only " + FileStatus.OK + " does."));
        }
    }


    /**
     * The {@code DISCGRP} dataset over JDBC: a keyed single-column record-image relation, addressed by the
     * 16-byte {@code DIS-GROUP-KEY}.
     *
     * <p>The same shape every sibling repository uses - a metadata probe discovers the record-image
     * column's name, a {@code LIKE} pattern over the key span confines the match to the key bytes, and the
     * configured {@link RecordImageForm} decides whether that column is read as characters or as bytes.
     * Only the three verbs {@code CBACT04C} issues exist here: no browse, no write, no delete, because
     * this program performs none and nothing else reads this dataset.
     *
     * <p>All state on this instance is deeply immutable once constructed. The open's resolved statement and
     * the file's position belong to the returned handle, so a singleton is safe to share.
     */
    static final class JdbcDisclosureGroupAccess implements DisclosureGroupAccess {

        /** Where a backend refusal is reported, so an abend is diagnosable. */
        private static final Log LOG = LogFactory.getLog(JdbcDisclosureGroupAccess.class);

        /**
         * The feedback code of the permanent-error status: the null character.
         *
         * <p>{@code FILE STATUS} {@code '9x'} is the implementor-defined permanent error, and its second
         * character is a binary feedback code rather than a digit -
         * {@link FileStatus#toDisplayLine(String)} renders it as
         * {@code 9nnn}, which is what {@code 9910-DISPLAY-IO-STATUS} does with it.
         */
        private static final char PERMANENT_ERROR_FEEDBACK_CODE = 0;

        /**
         * The status reported for a failure the COBOL vocabulary has no specific code for: an unreachable
         * dataset, a refused statement, an unreadable row or a row of the wrong width.
         *
         * <p>{@code CBACT04C} has no branch for a specific permanent-error code - {@code :422} accepts
         * {@code '00'} and {@code '23'} and moves {@link #APPL_RESULT_FATAL} for everything else - so what
         * matters is only that this is neither of those two and that it renders faithfully.
         */
        static final String PERMANENT_ERROR_STATUS = "9" + PERMANENT_ERROR_FEEDBACK_CODE;

        /**
         * The key span: offset 0, width {@value DisclosureGroupRecord#DIS_GROUP_KEY_LENGTH}.
         *
         * <p>Sixteen. See {@link AccountInterestCalcJob#verifyDeclaredKeyGeometry()}.
         */
        private static final KeySpan KEY_SPAN =
                new KeySpan(0, DisclosureGroupRecord.DIS_GROUP_KEY_LENGTH);

        /** The module's shared template. */
        private final JdbcTemplate jdbcTemplate;

        /** The codec, for its charset and for the width rules. */
        private final FixedWidthCodec codec;

        /** How this deployment's driver presents a record image. */
        private final RecordImageForm recordImageForm;

        /** The dataset contract, which is also where every statement's text is composed. */
        private final DatasetRelation relation;

        /** The resolved dataset name, as configuration declares it. */
        private final String datasetName;

        /**
         * Resolves the {@code DISCGRP} binding and validates its geometry against the copybook.
         *
         * @param jdbcTemplate    the module's shared template
         * @param datasetBindings the DD-name-keyed dataset catalogue
         * @param datasetCharset  the dataset code page, stated explicitly
         * @param recordImageForm the record-image representation
         * @throws NullPointerException  if any argument is {@code null}
         * @throws IllegalStateException if {@value #DISCGRP_DD_NAME} is unconfigured, or its binding
         *                               declares a record width, key width or key offset other than the
         *                               copybook's
         */
        JdbcDisclosureGroupAccess(JdbcTemplate jdbcTemplate,
                DatasetBindings datasetBindings,
                Charset datasetCharset,
                RecordImageForm recordImageForm) {

            this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "A JdbcTemplate is required: the "
                    + "disclosure group dataset is reached through the module's shared template");
            Objects.requireNonNull(datasetBindings, "The carddemo.datasets binding catalogue is "
                    + "required: dataset names live in configuration and are never written in Java");
            this.codec = new FixedWidthCodec(Objects.requireNonNull(datasetCharset, "A dataset charset "
                    + "is required: a fixed-width mainframe record is bytes in a specific code page, so "
                    + "the code page is stated explicitly and never taken from the platform"));
            this.recordImageForm = Objects.requireNonNull(recordImageForm, "A record-image "
                    + "representation is required: whether this deployment's driver presents a record "
                    + "image as characters or as bytes is stated once, by "
                    + RecordImageForm.FORM_PROPERTY + ", and never decided per repository");
            RecordImageForm.requireSingleByteCodePage(datasetCharset);

            DatasetBinding binding = requireDiscgrpGeometry(datasetBindings);
            this.relation = DatasetRelation.of(requireUsableDatasetName(binding.dsname()),
                    DisclosureGroupRecord.RECORD_LENGTH);
            this.datasetName = this.relation.dsname();
        }

        @Override
        public String datasetName() {
            return datasetName;
        }

        @Override
        public DisclosureGroupFile open() {
            // An OPEN resolves the dataset's shape afresh; the resolved statement belongs to the handle.
            relation.forgetRecordImageColumn();
            try {
                return new JdbcDisclosureGroupFile(this, FileStatus.OK, resolveStatements());
            } catch (DataAccessException unreachable) {
                // The SANITIZED summary only. A throwable handed to a logger emits its message and its
                // whole cause chain verbatim, and a driver composes that message around the values it
                // refused - so the record's content would reach a log file that is read by more people
                // and guarded less than the dataset (CWE-532), and a carriage return anywhere in it would
                // split the entry in two (CWE-117). BackendDiagnostic.describe() carries the SQLSTATE,
                // the vendor code and the exception type, which is what is diagnosable and nothing more.
                LOG.error("Could not open the disclosure group dataset '" + datasetName
                        + "' - " + DatasetRelation.BackendDiagnostic.of(unreachable).describe()
                        + "; reporting file status " + FileStatus.toStatusImage(PERMANENT_ERROR_STATUS)
                        + " from the open, which app/cbl/CBACT04C.cbl:273-284 displays as '"
                        + ERROR_OPENING_DISCGRP + "' before abending");
                return new JdbcDisclosureGroupFile(this, PERMANENT_ERROR_STATUS, null);
            }
        }

        /**
         * Composes the statements this access path sends, discovering the record-image column's name from
         * the backend.
         *
         * <p>The describe is read-only and returns no rows by construction, so the relation is described
         * without any of it being transferred - which is what an {@code OPEN INPUT} establishes too. Both
         * statements come out of that one describe: the column name is remembered on the relation, so
         * composing the unreadable-row probe costs no second round trip.
         *
         * <p>Package-visible so a unit test can assert the composed text without a round trip.
         *
         * @return the keyed-read statement and the unreadable-row probe
         * @throws DataAccessException   if the dataset cannot be described, which the caller turns into a
         *                               status
         * @throws IllegalStateException if the dataset is described but presents no usable record-image
         *                               column
         */
        Statements resolveStatements() {
            ResultSetExtractor<String> columnNameExtractor =
                    JdbcDisclosureGroupAccess::extractRecordImageColumnName;
            String columnName = jdbcTemplate.query(relation.describeStatement(), columnNameExtractor);
            String recordImageColumn =
                    relation.rememberRecordImageColumn(requireUsableColumnName(columnName));
            return new Statements(relation.selectByKey(recordImageColumn),
                    relation.selectUnreadableRows(recordImageColumn));
        }

        /**
         * The two statements one open resolves.
         *
         * @param selectByKey         the keyed read, taking the {@code LIKE} pattern as its only parameter
         * @param probeUnreadableRows the rows whose record-image column holds nothing, which is what lets a
         *                            keyed read <em>prove</em> an absence before it reports one. The
         *                            three components of {@code DIS-GROUP-KEY} live inside the record
         *                            image, so a row with no image has no knowable key
         */
        record Statements(String selectByKey, String probeUnreadableRows) {
        }

        /**
         * Reads the record-image column's name from a result set's metadata, without consuming a row.
         *
         * @param resultSet the empty result set the probe produced
         * @return the column's name, or {@code null} if the backend describes none
         * @throws SQLException if the driver cannot supply the metadata
         */
        private static String extractRecordImageColumnName(ResultSet resultSet) throws SQLException {
            return DatasetRelation.recordImageColumnOf(resultSet.getMetaData());
        }

        /**
         * Maps one result-set row to its record image, through the configured representation.
         *
         * <p>The value is <strong>not</strong> trimmed: the trailing 28 bytes of this record are
         * {@code FILLER} and they are part of it, and in every row of
         * {@code app/data/ASCII/discgrp.txt} they are zeros rather than spaces.
         *
         * @return a mapper yielding each row's record image, or {@code null} for a row whose column holds
         *         no value
         */
        private RowMapper<byte[]> recordImageMapper() {
            return (resultSet, rowNumber) -> recordImageForm.readImage(resultSet,
                    DatasetRelation.RECORD_IMAGE_COLUMN_INDEX, codec.charset());
        }

        /**
         * Builds a one-row statement whose single parameter is a keyed {@code LIKE} pattern.
         *
         * <p>The row limit is set on the statement rather than expressed as a row-limiting clause, so no
         * dialect syntax appears anywhere here. A driver that declines the limit costs efficiency and
         * nothing else: the first row is taken and the rest ignored.
         *
         * @param sql     the statement text
         * @param pattern the escaped pattern from {@link KeySpan#pattern(String)}
         * @return a creator for the prepared, limited and bound statement
         */
        private PreparedStatementCreator firstRowMatching(String sql, String pattern) {
            return connection -> {
                PreparedStatement statement = connection.prepareStatement(sql);
                statement.setMaxRows(1);
                recordImageForm.bindOperand(statement, 1, pattern, codec.charset());
                return statement;
            };
        }

        /**
         * Renders a disclosure-group key for a log line: one line, with the account group masked.
         *
         * <p>{@code DIS-GROUP-KEY} is three fields ({@code app/cpy/CVTRA02Y.cpy:5-8}), and they do not
         * carry the same risk. {@code DIS-ACCT-GROUP-ID X(10)} identifies the account group whose rate
         * is being looked up, so it is masked. {@code DIS-TRAN-TYPE-CD X(02)} and
         * {@code DIS-TRAN-CAT-CD 9(04)} are classification codes drawn from small fixed code tables -
         * they identify no account and no customer - so they stay legible, which is what makes the log
         * line useful for telling "the rate table has no row for this category" apart from "the dataset
         * refused the read".
         *
         * <p>The whole rendering also passes through {@link DiagnosticText#singleLine(String)}, because
         * the key arrives from storage rather than from a code table: a control character in those bytes
         * would otherwise let a stored value inject a line break and forge a second log record
         * (CWE-117).
         *
         * @param keyImage the key as read or composed; may be shorter than the declared width if the
         *                 caller is reporting a malformed value, and may be {@code null}
         * @return a single-line rendering safe to log
         */
        private static String keyForDiagnostics(String keyImage) {
            if (keyImage == null) {
                return DiagnosticText.ABSENT;
            }
            int split = Math.min(DisclosureGroupRecord.DIS_ACCT_GROUP_ID_LENGTH, keyImage.length());
            String group = keyImage.substring(0, split);
            String codes = keyImage.substring(split);
            return DiagnosticText.masked(group) + DiagnosticText.singleLine(codes);
        }

        /**
         * Performs the keyed read for a handle that opened successfully.
         *
         * @param sql      the statements the handle's open resolved
         * @param keyImage the 16-character key
         * @return the outcome; never {@code null}
         */
        private DisclosureGroupRead readByKey(Statements sql, String keyImage) {
            List<byte[]> rows;
            try {
                rows = jdbcTemplate.query(firstRowMatching(sql.selectByKey(), KEY_SPAN.pattern(keyImage)),
                        recordImageMapper());
            } catch (DataAccessException translated) {
                // The sanitized summary only; see the note in open() for why the throwable is not passed.
                LOG.error("Could not read key '" + keyForDiagnostics(keyImage)
                        + "' from the disclosure group dataset '"
                        + datasetName + "' - "
                        + DatasetRelation.BackendDiagnostic.of(translated).describe()
                        + "; reporting file status "
                        + FileStatus.toStatusImage(PERMANENT_ERROR_STATUS));
                return DisclosureGroupRead.failed(PERMANENT_ERROR_STATUS);
            }

            if (rows.isEmpty()) {
                // The INVALID KEY condition of app/cbl/CBACT04C.cbl:417-419. An EXPECTED outcome and the
                // signal to retry with the DEFAULT group, so it is reported and never thrown - but only
                // once the absence has been PROVED. All three components of DIS-GROUP-KEY live inside the
                // record image (app/cpy/CVTRA02Y.cpy:5-8), so a row whose record-image column holds
                // nothing has no knowable key and the keyed predicate cannot match it. Reporting '23'
                // while such a row sits in the dataset sends 1200-GET-INTEREST-RATE to the DEFAULT group
                // and charges a rate the account's own group may well define - which is a money
                // difference, not a diagnostic one.
                return provenAbsence(sql.probeUnreadableRows(), keyImage);
            }

            byte[] image = rows.get(0);
            if (image == null) {
                LOG.error("The disclosure group dataset '" + datasetName + "' presented key '"
                        + keyForDiagnostics(keyImage)
                        + "' with no record image at column position "
                        + DatasetRelation.RECORD_IMAGE_COLUMN_INDEX + "; reporting file status "
                        + FileStatus.toStatusImage(PERMANENT_ERROR_STATUS)
                        + " rather than " + FileStatus.NOT_FOUND + ", because a row that is present and "
                        + "unreadable is not a missing record and must not send the caller to the "
                        + "DEFAULT group");
                return DisclosureGroupRead.failed(PERMANENT_ERROR_STATUS);
            }

            try {
                return DisclosureGroupRead.found(
                        DisclosureGroupRecord.decode(image, codec.charset()));
            } catch (IllegalArgumentException malformed) {
                // The measured width only, never the row's own bytes and never the rejection's message:
                // the row IS the data this log line must not carry (CWE-532), and the width is the whole
                // of what an operator needs. Same reasoning as the note in open().
                LOG.error("The disclosure group dataset '" + datasetName + "' presented key '"
                        + keyForDiagnostics(keyImage)
                        + "' as " + image.length + " byte(s) where app/cpy/CVTRA02Y.cpy declares "
                        + DisclosureGroupRecord.RECORD_LENGTH + "; reporting file status "
                        + FileStatus.toStatusImage(PERMANENT_ERROR_STATUS)
                        + " rather than padding or truncating it, because every field offset after the "
                        + "discrepancy would be wrong");
                return DisclosureGroupRead.failed(PERMANENT_ERROR_STATUS);
            }
        }

        /**
         * Reports the {@code INVALID KEY} condition only once no row of the rate table is
         * <strong>unreadable</strong>, and the permanent-error status when one is.
         *
         * <h2>Why this one matters in money rather than in diagnostics</h2>
         * <p>{@code app/cbl/CBACT04C.cbl:417-419} takes {@code '23'} from this dataset as "this account
         * group has no rate for this category" and retries the read under the {@code DEFAULT} group
         * ({@code :421-430}). So a keyed-empty result that is really an unreadable row does not merely
         * mislabel an error: it charges the default rate against an account whose own group may define a
         * different one, and the run completes and reports success.
         *
         * <p>The three components of {@code DIS-GROUP-KEY} all live inside the record image, so a row
         * whose record-image column holds nothing has no knowable key and the keyed predicate - a
         * comparison, and therefore {@code UNKNOWN} against a null - cannot match it. The absence is
         * therefore confirmed with one further row-limited read before it is reported, on the empty path
         * only; a read that found its rate is untouched.
         *
         * @param unreadableRowsProbe the statement selecting the rows with no record image
         * @param keyImage            the key that matched nothing, for the diagnostic - masked as ever
         * @return {@link DisclosureGroupRead#notFound()} when the absence is established, the
         *         permanent-error outcome when it is not; never {@code null}
         */
        private DisclosureGroupRead provenAbsence(String unreadableRowsProbe, String keyImage) {
            List<byte[]> unreadable;
            try {
                unreadable = jdbcTemplate.query(firstRow(unreadableRowsProbe), recordImageMapper());
            } catch (DataAccessException translated) {
                // The probe established nothing, so the absence stays unproved - and must not become the
                // DEFAULT-group retry. Reported on the arm a refused read is reported on.
                LOG.error("Could not establish that the disclosure group dataset '" + datasetName
                        + "' holds no unreadable row before reporting key '"
                        + keyForDiagnostics(keyImage) + "' as absent - "
                        + DatasetRelation.BackendDiagnostic.of(translated).describe()
                        + "; reporting file status "
                        + FileStatus.toStatusImage(PERMANENT_ERROR_STATUS)
                        + " rather than the INVALID KEY that would charge the DEFAULT rate");
                return DisclosureGroupRead.failed(PERMANENT_ERROR_STATUS);
            }
            // As on the keyed read above, the list itself is never null, so emptiness is the whole test.
            if (unreadable.isEmpty()) {
                // A genuine INVALID KEY: nothing matched the key and no row of the table is unreadable, so
                // the DEFAULT-group retry at :421-430 is the right next step.
                return DisclosureGroupRead.notFound();
            }
            LOG.error("A keyed read of the disclosure group dataset '" + datasetName + "' matched no row "
                    + "for key '" + keyForDiagnostics(keyImage) + "', but the dataset holds a row with no "
                    + "record image at column position " + DatasetRelation.RECORD_IMAGE_COLUMN_INDEX
                    + " - and DIS-GROUP-KEY is part of that image, so that row's key cannot be known; "
                    + "reporting file status " + FileStatus.toStatusImage(PERMANENT_ERROR_STATUS)
                    + " rather than the INVALID KEY that would send this account to the DEFAULT group");
            return DisclosureGroupRead.failed(PERMANENT_ERROR_STATUS);
        }

        /**
         * Builds a statement that returns at most one row and binds no parameter: the shape the
         * unreadable-row probe needs.
         *
         * <p>The row limit is set on the {@link PreparedStatement} rather than expressed as a row-limiting
         * clause, exactly as {@link #firstRowMatching(String, String)} does it, so no dialect syntax
         * appears in any statement this class sends. One row settles the question the probe asks.
         *
         * @param sql the statement text
         * @return a creator for the prepared and limited statement
         */
        private PreparedStatementCreator firstRow(String sql) {
            return connection -> {
                PreparedStatement statement = connection.prepareStatement(sql);
                statement.setMaxRows(1);
                statement.setFetchSize(1);
                return statement;
            };
        }

        /**
         * Names a key in a log line without reproducing it.
         *
         * <p>Two reasons, and the first is the one that makes this a defect rather than a preference. The
         * key is assembled from {@code PIC X} spans read out of {@code TCATBALF} and {@code ACCTFILE}
         * ({@code DIS-ACCT-GROUP-ID X(10)}, {@code DIS-TRAN-TYPE-CD X(02)},
         * {@code DIS-TRAN-CAT-CD 9(04)}), and a {@code PIC X} span holds whatever bytes are in the record
         * - including a carriage return or a line feed. Concatenated raw, such a key ends the log entry
         * early and starts a line of the writer's choosing, so a forged entry can be planted in a file an
         * operator trusts (CWE-117). {@link SensitiveDiagnostics#maskIdentifier(String)} escapes control
         * characters in the part it reveals and replaces the rest, so no byte of the record reaches the
         * line unexamined.
         *
         * <p>Second, the leading span links the failure to a set of accounts, and a log file is retained
         * longer and read more widely than the dataset it describes (CWE-532). The read itself always uses
         * the real key; only its rendering is masked. This is the same treatment
         * {@code TranCatBalRepository} gives its own key image, deliberately, so one policy covers every
         * key this module logs.
         *
         * @param keyImage the 16-character key image
         * @return a phrase naming the key, safe to log
         */
        private static String describeKey(String keyImage) {
            return "key '" + SensitiveDiagnostics.maskIdentifier(keyImage) + "'";
        }

        /**
         * Validates the {@code DISCGRP} binding's geometry against {@code app/cpy/CVTRA02Y.cpy}.
         *
         * @param datasetBindings the catalogue
         * @return the validated binding
         * @throws IllegalStateException if the binding is absent or its geometry disagrees with the
         *                               copybook
         */
        private static DatasetBinding requireDiscgrpGeometry(DatasetBindings datasetBindings) {
            DatasetBinding binding = datasetBindings.binding(DISCGRP_DD_NAME);
            if (binding.recordLength() != DisclosureGroupRecord.RECORD_LENGTH) {
                throw new IllegalStateException("carddemo.datasets." + DISCGRP_DD_NAME
                        + ".record-length is " + binding.recordLength()
                        + " but app/cpy/CVTRA02Y.cpy declares " + DisclosureGroupRecord.RECORD_LENGTH
                        + " bytes. A record width the layout disagrees with makes every field offset "
                        + "wrong.");
            }
            if (binding.keyLength() == null
                    || binding.keyLength() != DisclosureGroupRecord.DIS_GROUP_KEY_LENGTH) {
                throw new IllegalStateException("carddemo.datasets." + DISCGRP_DD_NAME
                        + ".key-length must be " + DisclosureGroupRecord.DIS_GROUP_KEY_LENGTH
                        + " - DIS-ACCT-GROUP-ID X(10) + DIS-TRAN-TYPE-CD X(02) + DIS-TRAN-CAT-CD 9(04), "
                        + "app/cpy/CVTRA02Y.cpy:5-8 - but it is " + binding.keyLength()
                        + ". Seventeen is CVTRA01Y's TRAN-CAT-KEY; both records are 50 bytes, so the "
                        + "confusion is invisible to a width check and would shift DIS-INT-RATE by one "
                        + "byte.");
            }
            if (binding.keyOffsetOrZero() != 0) {
                throw new IllegalStateException("carddemo.datasets." + DISCGRP_DD_NAME
                        + ".key-offset is " + binding.keyOffsetOrZero()
                        + " but DIS-GROUP-KEY begins the record, so its offset is 0.");
            }
            return binding;
        }

        /**
         * Validates the configured dataset name and returns it verbatim.
         *
         * @param candidate the {@code dsname} component of the resolved binding
         * @return {@code candidate}, unchanged
         * @throws IllegalStateException if {@code candidate} is {@code null} or blank
         */
        private static String requireUsableDatasetName(String candidate) {
            if (candidate == null || candidate.isBlank()) {
                throw new IllegalStateException("The dataset binding for DD name '" + DISCGRP_DD_NAME
                        + "' declares no dataset name. Set carddemo.datasets." + DISCGRP_DD_NAME
                        + ".dsname; this access path composes its statements from configuration alone "
                        + "and hard-codes no dataset name.");
            }
            // The grammar lives in DatasetRelation, so what a dataset name may contain is stated once
            // for the whole module rather than restated - differently - here.
            return DatasetRelation.requireDatasetName(candidate);
        }

        /**
         * Validates the discovered record-image column name and returns it verbatim.
         *
         * @param candidate the name the metadata probe reported, possibly {@code null}
         * @return {@code candidate}, unchanged
         * @throws IllegalStateException if {@code candidate} is {@code null} or blank
         */
        private static String requireUsableColumnName(String candidate) {
            if (candidate == null || candidate.isBlank()) {
                throw new IllegalStateException("The backend describes the disclosure group dataset with "
                        + "no usable column at position " + DatasetRelation.RECORD_IMAGE_COLUMN_INDEX
                        + ", so there is no record-image column to read. Every dataset in this module is "
                        + "reached as a single-column relation whose one column holds the whole "
                        + DisclosureGroupRecord.RECORD_LENGTH + "-byte record image; no column name is "
                        + "invented here to paper over a relation that is not.");
            }
            return candidate;
        }
    }

    /**
     * One open {@code DISCGRP} file over JDBC.
     *
     * <p>The handle <em>is</em> the file: its open status and its resolved statement belong to it and not
     * to the shared access path, so two runs never interfere (practice B9, gate G53). A handle whose open
     * failed carries {@link JdbcDisclosureGroupAccess#PERMANENT_ERROR_STATUS} and reports that same
     * failure from every operation, so a caller that ignored the open status cannot mistake a dataset it
     * never reached for one that was empty.
     */
    static final class JdbcDisclosureGroupFile implements DisclosureGroupFile {

        /** The access path that opened this file. */
        private final JdbcDisclosureGroupAccess access;

        /** What the open reported. */
        private final String openStatus;

        /** The statements the open resolved, or {@code null} when the open failed. */
        private final JdbcDisclosureGroupAccess.Statements statements;

        /** Whether this handle has been closed; a close is idempotent. */
        private boolean closed;

        /**
         * @param access     the access path
         * @param openStatus the status the open reported
         * @param statements the resolved statements, or {@code null} when the open failed
         */
        JdbcDisclosureGroupFile(JdbcDisclosureGroupAccess access, String openStatus,
                JdbcDisclosureGroupAccess.Statements statements) {
            this.access = access;
            this.openStatus = openStatus;
            this.statements = statements;
        }

        @Override
        public String openStatus() {
            return openStatus;
        }

        @Override
        public DisclosureGroupRead readByKey(String keyImage) {
            Objects.requireNonNull(keyImage, "A DIS-GROUP-KEY image is required to read DISCGRP by key; "
                    + "app/cbl/CBACT04C.cbl:210-212 sets all three components before the READ");
            if (keyImage.length() != DisclosureGroupRecord.DIS_GROUP_KEY_LENGTH) {
                throw new IllegalArgumentException("DIS-GROUP-KEY is "
                        + DisclosureGroupRecord.DIS_GROUP_KEY_LENGTH + " characters "
                        + "(app/cpy/CVTRA02Y.cpy:5-8); '" + keyImage + "' is " + keyImage.length()
                        + ". A short key would silently become a prefix match over more records than it "
                        + "names.");
            }
            if (closed) {
                throw new IllegalStateException("This DISCGRP file has been closed, so it can read "
                        + "nothing more. app/cbl/CBACT04C.cbl closes it once, at :561, after the loop - "
                        + "reading afterwards is a defect in the caller and not a file status.");
            }
            if (statements == null) {
                // The open never reached the dataset. Report the same failure rather than a fresh one.
                return DisclosureGroupRead.failed(JdbcDisclosureGroupAccess.PERMANENT_ERROR_STATUS);
            }
            return access.readByKey(statements, keyImage);
        }

        @Override
        public String closeFile() {
            closed = true;
            if (statements != null) {
                return FileStatus.OK;
            }
            // A CLOSE of a file that is not open is not a success, which is what COBOL reports too.
            return JdbcDisclosureGroupAccess.PERMANENT_ERROR_STATUS;
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    // =================================================================================================
    // The step-execution scope around the chunk delegate.
    // =================================================================================================

    /**
     * The reader, processor, writer, stream and listener the step is actually built from: a thin router
     * that owns no run of its own and forwards every callback to a {@link ChunkDelegate} belonging to the
     * step execution that is calling.
     *
     * <p>This exists because of a lifetime mismatch. A {@code Step} bean is constructed once, when the
     * application context starts, and the reader, processor and writer it was given at that moment are
     * the ones every later execution uses. A {@link ChunkDelegate} captured there would therefore be
     * shared by every launch of the job - and it is deliberately stateful, holding the
     * {@value BatchConfig#PARM_DATE_PARAMETER} the launcher supplied, the five open files, the
     * cross-record accumulators {@code WS-TOTAL-INT} and {@code WS-LAST-ACCT-NUM}, and the two run
     * counters. Two overlapping launches sharing one would interleave their reads of {@code TCATBALF},
     * accumulate each other's interest into one {@code WS-TOTAL-INT}, and post the sum to whichever
     * account happened to break first.
     *
     * <p>{@code CBACT04C} has no such sharing to reproduce, which is what makes the isolation a parity
     * requirement rather than a hardening measure. Each {@code EXEC PGM=CBACT04C}
     * ({@code app/jcl/INTCALC.jcl:22}) runs in its own address space: its own {@code WORKING-STORAGE},
     * its own {@code PARM}, and its own five {@code OPEN}s. One delegate per step execution is that
     * address space.
     *
     * <p>The delegate is held in a {@link ThreadLocal} rather than a map keyed by
     * {@link StepExecution}, because every callback of a serial chunk step - {@code beforeStep},
     * {@code open}, each {@code read}, {@code process} and {@code write}, then {@code close} - is invoked
     * on the thread executing the step, and a {@code ThreadLocal} is the only structure that needs no key
     * to be threaded through interfaces that do not carry one ({@link ItemStreamReader#read()} takes no
     * arguments). The field is {@code final} and per-instance, so no mutable state is shared: what varies
     * is per-thread, which is precisely the isolation being reproduced. The step is left serial - no
     * {@code TaskExecutor} is configured - because {@code CBACT04C}'s per-account accumulation depends on
     * reading {@code TCATBALF} in physical order.
     */
    public static final class StepScopedChunkDelegate implements ItemStreamReader<TranCatBalRecord>,
            ItemProcessor<TranCatBalRecord, RecordOutcome>, ItemWriter<RecordOutcome>,
            StepExecutionListener {

        private final AccountInterestCalcJob job;

        /**
         * The scope belonging to the step execution running on this thread. Per-instance and
         * {@code final}; the mutability is per-thread, never shared.
         */
        private final ThreadLocal<Scope> executionScope = new ThreadLocal<>();

        StepScopedChunkDelegate(AccountInterestCalcJob job) {
            this.job = Objects.requireNonNull(job, "A job is required to scope its chunk delegate");
        }

        /**
         * One step execution and the delegate that is its address space.
         *
         * @param stepExecution the execution the delegate belongs to
         * @param delegate      that execution's delegate
         */
        private record Scope(StepExecution stepExecution, ChunkDelegate delegate) {
        }

        /**
         * Builds this execution's delegate and hands it the launcher's parameters.
         *
         * <p>Spring Batch invokes this before the stream is opened, so the delegate exists before any
         * other callback can reach for it.
         *
         * <p>The scope is keyed by the {@link StepExecution} rather than by call order, and that is
         * deliberate: a chunk step registers its reader both as a stream and, when the reader is also a
         * listener, as a step-execution listener, so this callback can legitimately arrive more than once
         * for one execution. Arriving again for the <em>same</em> execution therefore reuses that
         * execution's delegate, which is safe because handing a delegate its parameters twice yields the
         * same {@code PARM}. Arriving for a <em>different</em> execution while one is still scoped is
         * refused, because that is two executions nested on one thread and silently replacing the first
         * would abandon its five open files.
         *
         * @param stepExecution the execution starting; must not be {@code null}
         */
        @Override
        public void beforeStep(StepExecution stepExecution) {
            Objects.requireNonNull(stepExecution, "A step execution is required to scope a delegate");
            Scope existing = executionScope.get();
            if (existing != null) {
                if (existing.stepExecution() != stepExecution) {
                    throw new IllegalStateException("A chunk delegate is already scoped to this thread "
                            + "for a different step execution. Each step execution gets its own delegate, "
                            + "and one cannot be started inside another on the same thread: replacing the "
                            + "first would abandon the five files it has open.");
                }
                existing.delegate().beforeStep(stepExecution);
                return;
            }
            ChunkDelegate delegate = job.newChunkDelegate();
            executionScope.set(new Scope(stepExecution, delegate));
            delegate.beforeStep(stepExecution);
        }

        /**
         * Forwards the stream open - the banner and the five {@code OPEN}s - to this execution's delegate.
         *
         * @param executionContext the step's execution context; must not be {@code null}
         */
        @Override
        public void open(ExecutionContext executionContext) {
            requireScopedDelegate("open").open(executionContext);
        }

        /**
         * Forwards {@code 1000-TCATBALF-GET-NEXT} to this execution's delegate.
         *
         * @return the next record, or {@code null} at end of file
         */
        @Override
        public TranCatBalRecord read() {
            return requireScopedDelegate("read").read();
        }

        /**
         * Forwards the record body to this execution's delegate.
         *
         * @param item the record read; must not be {@code null}
         * @return the outcome of the record body; never {@code null}
         */
        @Override
        public RecordOutcome process(TranCatBalRecord item) {
            return requireScopedDelegate("process").process(item);
        }

        /**
         * Forwards the chunk bookkeeping to this execution's delegate.
         *
         * @param chunk the processed outcomes; must not be {@code null}
         */
        @Override
        public void write(Chunk<? extends RecordOutcome> chunk) {
            requireScopedDelegate("write").write(chunk);
        }

        /**
         * Forwards the stream update to this execution's delegate when one is scoped.
         *
         * <p>{@link ChunkDelegate} stores no restartable position, so this contributes nothing to the
         * execution context; it is forwarded rather than swallowed so the delegate remains the single
         * definition of the stream contract.
         *
         * @param executionContext the step's execution context; must not be {@code null}
         */
        @Override
        public void update(ExecutionContext executionContext) {
            Scope scope = executionScope.get();
            if (scope != null) {
                scope.delegate().update(executionContext);
            }
        }

        /**
         * Forwards the stream close - the five {@code CLOSE}s and the closing banner, or a silent release
         * after an abend - and then releases the scope.
         *
         * <p>The scope is released here rather than in {@code afterStep} because Spring Batch invokes the
         * step listeners before closing the streams, so clearing it there would leave the closes with
         * nothing to run. A close with no delegate scoped is a no-op: a stream may be closed without ever
         * having been opened.
         */
        @Override
        public void close() {
            Scope scope = executionScope.get();
            if (scope == null) {
                return;
            }
            try {
                scope.delegate().close();
            } finally {
                executionScope.remove();
            }
        }

        /**
         * Leaves the exit status to the step and the job listener.
         *
         * <p>{@link ChunkDelegate} contributes no exit status of its own - an abend's status comes from
         * {@link BatchConfig}'s job listener translating {@link AbendException} - so this returns
         * {@code null}, which Spring Batch reads as "no change".
         *
         * @param stepExecution the execution finishing; must not be {@code null}
         * @return {@code null} always
         */
        @Override
        public ExitStatus afterStep(StepExecution stepExecution) {
            Objects.requireNonNull(stepExecution, "A step execution is required to finish a delegate");
            return null;
        }

        /**
         * This execution's delegate, or a diagnosis of the callback order that reached here without one.
         *
         * @param callback the callback name, for the message
         * @return the scoped delegate; never {@code null}
         */
        private ChunkDelegate requireScopedDelegate(String callback) {
            Scope scope = executionScope.get();
            if (scope == null) {
                throw new IllegalStateException("No chunk delegate is scoped to this thread, so '"
                        + callback + "' has no run to address. beforeStep establishes the scope and it is "
                        + "released by close, so this means the callback arrived outside a step execution "
                        + "or on a different thread from the one executing the step.");
            }
            return scope.delegate();
        }

        /**
         * The delegate currently scoped to the calling thread, if any.
         *
         * <p>Exposed so a test can assert that two step executions were handed different delegates, and
         * that the scope is released when the step ends.
         *
         * @return the scoped delegate, or empty when no step execution is in progress on this thread
         */
        public Optional<ChunkDelegate> scopedDelegate() {
            return Optional.ofNullable(executionScope.get()).map(Scope::delegate);
        }
    }

    // =================================================================================================
    // The Spring Batch chunk delegate: one object, four roles, one run.
    // =================================================================================================

    /**
     * The step's reader, processor, writer and step listener, all backed by one
     * {@link InterestCalculationRun}.
     *
     * <p>Four roles rather than four objects because all four need the same {@code WORKING-STORAGE} and
     * the same five open files, which is what a COBOL program is. Splitting them would mean sharing the
     * run through a field on the singleton bean, and two step executions would then consume each other's
     * records.
     *
     * <p>The lifecycle maps onto the program's own:
     * <table border="1">
     *   <caption>Spring Batch lifecycle to COBOL paragraph</caption>
     *   <tr><th>Callback</th><th>COBOL</th></tr>
     *   <tr><td>{@code beforeStep}</td><td>{@code PROCEDURE DIVISION USING EXTERNAL-PARMS} - the PARM
     *       arrives</td></tr>
     *   <tr><td>{@code open}</td><td>{@code :181-186} - the banner and the five opens</td></tr>
     *   <tr><td>{@code read}</td><td>{@code :190} - {@code 1000-TCATBALF-GET-NEXT}</td></tr>
     *   <tr><td>{@code process}</td><td>{@code :192-217} - the record body</td></tr>
     *   <tr><td>{@code write}</td><td>bookkeeping only; the verbs already ran in {@code process}</td></tr>
     *   <tr><td>{@code close}</td><td>{@code :224-230} - the five closes and the banner, and only when
     *       the loop ended at end of file</td></tr>
     * </table>
     *
     * <p><strong>An abended step emits no closes and no closing banner</strong>, because the COBOL abend
     * leaves those paragraphs unperformed. {@link #close()} therefore performs them only when the run
     * reached end of file, and otherwise releases the handles silently.
     */
    public static final class ChunkDelegate implements ItemStreamReader<TranCatBalRecord>,
            ItemProcessor<TranCatBalRecord, RecordOutcome>, ItemWriter<RecordOutcome>,
            StepExecutionListener {

        /** The job this delegate runs. */
        private final AccountInterestCalcJob job;

        /**
         * The {@code PARM-DATE} this execution will use.
         *
         * <p>Initialised from the JCL step card's declared value and replaced by
         * {@link #beforeStep(StepExecution)} when a launcher supplied one, which is the equivalent of an
         * operator overriding a PARM at submission.
         */
        private String parmDate;

        /** This step execution's run, allocated by {@link #open(ExecutionContext)}. */
        private InterestCalculationRun run;

        /** How many transactions the run wrote, for the step's write count. */
        private long transactionsWritten;

        /** How many accounts the run rewrote, for diagnostics and assertions. */
        private long accountsRewritten;

        /**
         * @param job the job this delegate runs
         * @throws NullPointerException if {@code job} is {@code null}
         */
        ChunkDelegate(AccountInterestCalcJob job) {
            this.job = Objects.requireNonNull(job, "A job is required to build its chunk delegate");
            this.parmDate = job.declaredParmDate;
        }

        /**
         * Accepts the launcher's {@value BatchConfig#PARM_DATE_PARAMETER} when it supplied one.
         *
         * <p>Invoked before the stream is opened, which is why the PARM is available to
         * {@link #open(ExecutionContext)}. A launcher that supplied none leaves the JCL step card's
         * declared value in place.
         *
         * <p><strong>A supplied value is taken as it is or refused - never repaired.</strong> The value
         * is concatenated verbatim into every generated transaction identifier
         * ({@code app/cbl/CBACT04C.cbl:L476-L480}), so padding a short one or truncating a long one
         * would write a whole generation of subtly misplaced identifiers and report success.
         * {@link BatchConfig#parmDateValidator()}, attached to
         * {@link AccountInterestCalcJob#accountInterestCalcJob()}, rejects a wrong width before the job
         * starts; this guard is the same rule at the point of use, so a step exercised outside that job
         * cannot slip past it either.
         *
         * @param stepExecution the execution about to start
         * @throws IllegalArgumentException if a supplied value is not exactly
         *                                  {@link BatchConfig#PARM_DATE_WIDTH} characters
         */
        @Override
        public void beforeStep(StepExecution stepExecution) {
            Objects.requireNonNull(stepExecution, "A step execution is required to read its job "
                    + "parameters");
            String supplied = stepExecution.getJobParameters()
                    .getString(BatchConfig.PARM_DATE_PARAMETER);
            parmDate = supplied == null
                    ? job.declaredParmDate
                    : requireDeclaredParmWidth(supplied);
        }

        /**
         * Requires a supplied {@value BatchConfig#PARM_DATE_PARAMETER} to be exactly the width the COBOL
         * linkage item declares.
         *
         * @param supplied the launcher's value; never {@code null}
         * @return the value, unaltered
         * @throws IllegalArgumentException if it is any other width
         */
        private static String requireDeclaredParmWidth(String supplied) {
            if (supplied.length() != PARM_DATE_WIDTH) {
                throw new IllegalArgumentException("The job parameter '"
                        + BatchConfig.PARM_DATE_PARAMETER + "' is " + supplied.length()
                        + " characters, but " + PROGRAM_ID + " needs exactly " + PARM_DATE_WIDTH
                        + ". app/cbl/CBACT04C.cbl:178 declares PARM-DATE PIC X(" + PARM_DATE_WIDTH
                        + ") and L476-L480 concatenates the whole declared width into a fixed "
                        + "PIC X(16) transaction identifier, so a shorter value shifts the generated "
                        + "suffix left in every identifier this job writes and a longer one pushes it "
                        + "off the end. The value is never padded or truncated to fit, because that "
                        + "would write a full generation of wrong identifiers and report success.");
            }
            return supplied;
        }

        /**
         * {@code app/cbl/CBACT04C.cbl:181-186} - the start banner and the five opens, in order.
         *
         * @param executionContext the step's execution context, which this program stores nothing in:
         *                         {@code CBACT04C} has no restart semantics, and inventing some would let
         *                         a restarted step resume mid-account and post interest twice
         * @throws IllegalStateException if a run is already open on this delegate
         * @throws AbendException        if any open reports a status other than {@code '00'}
         */
        @Override
        public void open(ExecutionContext executionContext) {
            Objects.requireNonNull(executionContext, "An execution context is required by the "
                    + "ItemStream contract, even though this program stores nothing in it");
            if (run != null) {
                throw new IllegalStateException("A run is already open on this delegate. One delegate "
                        + "serves one step execution at a time, because a COBOL program's "
                        + "WORKING-STORAGE belongs to its execution; build another delegate with "
                        + "newChunkDelegate() for a concurrent run.");
            }
            transactionsWritten = 0;
            accountsRewritten = 0;
            InterestCalculationRun opening = job.newRun(parmDate, job.sysoutSink);
            run = opening;

            // DISPLAY 'START OF EXECUTION OF PROGRAM CBACT04C'.                                      L181
            opening.sysout().write(START_OF_EXECUTION);

            // PERFORM 0000-TCATBALF-OPEN through 0400-TRANFILE-OPEN.                             L182-L186
            opening.openFiles();
        }

        /**
         * {@code app/cbl/CBACT04C.cbl:190} - {@code PERFORM 1000-TCATBALF-GET-NEXT}.
         *
         * <p>Returns {@code null} at end of file, which is both what {@code MOVE 'Y' TO END-OF-FILE}
         * means and what the {@code ItemReader} contract uses to end a step.
         *
         * @return the next category balance record, or {@code null} at end of file
         * @throws IllegalStateException if no run is open
         * @throws AbendException        if the read reports a status that is neither {@code '00'} nor
         *                               {@code '10'}
         */
        @Override
        public TranCatBalRecord read() {
            return requireRun().tcatbalfGetNext();
        }

        /**
         * {@code app/cbl/CBACT04C.cbl:192-217} - the record body.
         *
         * @param item the record the reader returned
         * @return what the record did; never {@code null}, so no item is ever filtered out
         * @throws NullPointerException  if {@code item} is {@code null}
         * @throws IllegalStateException if no run is open
         * @throws AbendException        if any file operation this record triggers reports a fatal status
         */
        @Override
        public RecordOutcome process(TranCatBalRecord item) {
            return requireRun().processRecord(item);
        }

        /**
         * The writer stage: bookkeeping, and no dataset I/O.
         *
         * <p><strong>Deliberately not where the writes happen.</strong> The account {@code REWRITE} of
         * {@code :196} and the transaction {@code WRITE} of {@code :500} both run in
         * {@link #process(TranCatBalRecord)}, where the source puts them: relocating the rewrite here would
         * move it after the {@code :203} and {@code :205} reads of the next account, and those reads emit
         * their own {@value #ACCOUNT_NOT_FOUND_PREFIX} lines. On the happy path the two orders are
         * indistinguishable; on the rewrite-failure path they are not, because the COBOL abends before
         * reaching the reads. See the enclosing class's documentation.
         *
         * @param chunk the outcomes of this chunk's records - exactly one, since the commit interval is
         *              {@value #CHUNK_SIZE}
         * @throws NullPointerException if {@code chunk} is {@code null}
         */
        @Override
        public void write(Chunk<? extends RecordOutcome> chunk) {
            Objects.requireNonNull(chunk, "A chunk is required to write one");
            for (RecordOutcome outcome : chunk) {
                if (outcome.transactionWritten()) {
                    transactionsWritten++;
                }
                if (outcome.accountRewritten()) {
                    accountsRewritten++;
                }
            }
        }

        /**
         * {@code app/cbl/CBACT04C.cbl:224-230} - the five closes and the closing banner, and <em>only</em>
         * when the loop ended at end of file.
         *
         * <p>An abend leaves those paragraphs unperformed on the mainframe, so a failed step emits neither
         * here. The handles are released either way, silently, so nothing is left open.
         *
         * @throws AbendException if a close reports a status other than {@code '00'}
         */
        @Override
        public void close() {
            InterestCalculationRun finishing = run;
            if (finishing == null) {
                return;
            }
            run = null;
            boolean normalEnd = finishing.workingStorage().endOfFileIsYes();
            try {
                if (normalEnd) {
                    // PERFORM 9000-TCATBALF-CLOSE through 9400-TRANFILE-CLOSE.                   L224-L228
                    finishing.closeFiles();

                    // DISPLAY 'END OF EXECUTION OF PROGRAM CBACT04C'.                                 L230
                    finishing.sysout().write(END_OF_EXECUTION);
                }
            } finally {
                // The TRANSACT DD's abnormal disposition, for a step that ended before end of file:
                // app/jcl/INTCALC.jcl:37 is DISP=(NEW,CATLG,DELETE), so the generation is catalogued only
                // on a normal end. The account rewrites stand either way - ACCTFILE is DISP=SHR.
                if (normalEnd) {
                    finishing.release();
                } else {
                    finishing.releaseAbnormally();
                }
            }
        }

        /**
         * The run in progress.
         *
         * @return the run; never {@code null}
         * @throws IllegalStateException if the stream has not been opened
         */
        private InterestCalculationRun requireRun() {
            if (run == null) {
                throw new IllegalStateException("No run is open on this delegate. The ItemStream must be "
                        + "opened first, which is what performs the five OPEN paragraphs of "
                        + "app/cbl/CBACT04C.cbl:182-186; reading or processing before that is reading a "
                        + "file that is not open.");
            }
            return run;
        }

        /**
         * The run in progress, for assertions.
         *
         * @return the run, or {@code null} when the stream is not open
         */
        InterestCalculationRun run() {
            return run;
        }

        /**
         * The {@code PARM-DATE} this execution will use.
         *
         * @return exactly {@value #PARM_DATE_WIDTH} characters; never {@code null}
         */
        public String parmDate() {
            return parmDate;
        }

        /**
         * How many transactions the run has written.
         *
         * @return the count
         */
        public long transactionsWritten() {
            return transactionsWritten;
        }

        /**
         * How many accounts the run has rewritten.
         *
         * <p>Always one fewer than the number of account groups the run saw, because the unreachable
         * {@code ELSE} at {@code app/cbl/CBACT04C.cbl:220} never rewrites the last one.
         *
         * @return the count
         */
        public long accountsRewritten() {
            return accountsRewritten;
        }
    }


}
