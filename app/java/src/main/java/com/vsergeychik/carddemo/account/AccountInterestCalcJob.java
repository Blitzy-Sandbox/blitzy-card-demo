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
import com.vsergeychik.carddemo.common.DatasetUnitOfWork;
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
 * <h2>Where the disclosure-group access path lives</h2>
 *
 * <p>{@code app/cpy/CVTRA02Y.cpy} has exactly <strong>one</strong> consumer in the whole estate, and it
 * is this program - but the access path is still a repository of its own, {@link DisclosureGroupRepository},
 * because the migration plan names {@code DisclosureGroup} among the twelve dataset repositories and
 * gates on that count (AAP 0.3.5, 0.4.12, gate G10). One dataset, one {@code @Repository}, whatever the
 * number of callers: a per-dataset repository surface that made an exception for single-caller datasets
 * would be a surface nobody could check against the plan.
 *
 * <p>The port stays here, which is the part worth being precise about. {@link DisclosureGroupAccess},
 * {@link DisclosureGroupFile} and {@link DisclosureGroupRead} are this program's contract with the
 * dataset - the Java form of {@code CBACT04C}'s own {@code SELECT DISCGRP-FILE} and its {@code FD} - and
 * {@code DisclosureGroupRepository} is the JDBC adapter that satisfies it, injected through the
 * constructor. That split is what lets a unit test drive all four read outcomes with no database at all,
 * and it is why the repository is built on the same primitives every sibling repository uses: the dataset
 * name comes from {@code carddemo.datasets.DISCGRP} and no dataset literal appears in either source
 * (gate G46).
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
     * The program's own logger, distinct from the one {@link DisclosureGroupRepository} keeps.
     *
     * <p>It carries exactly one kind of line: a report that the {@code TRANSACT} DD's abnormal
     * disposition could not be applied. That deliberately does not go to {@code SYSOUT}, because
     * {@code CBACT04C} emits no such line and {@code SYSOUT} is compared byte for byte - the disposition
     * is the JCL's work, not the program's.
     */
    private static final Log LOG = LogFactory.getLog(AccountInterestCalcJob.class);

    public static final String CONFIGURATION_BEAN_NAME = "accountInterestCalcJobConfiguration";

    /**
     * The COBOL program this class is the translation of: {@code app/cbl/CBACT04C.cbl}.
     */
    public static final String PROGRAM_ID = "CBACT04C";

    public static final String JOB_KEY = "account-interest-calc-job";

    public static final String JOB_NAME = "accountInterestCalcJob";

    /**
     * The step name, transcribed from {@code app/jcl/INTCALC.jcl:22} - {@code //STEP15 EXEC PGM=...}.
     */
    public static final String STEP_NAME = "STEP15";

    /**
     * The whole step sequence of {@code app/jcl/INTCALC.jcl}: one step, {@link #STEP_NAME}, running
     * {@link #PROGRAM_ID}, ungated.
     */
    public static final List<StepContract> REQUIRED_STEPS =
            List.of(new StepContract(STEP_NAME, PROGRAM_ID, false));

    public static final int CHUNK_SIZE = 1;

    static final String REWRITE_ACCTFILE_VERB = "1050-UPDATE-ACCOUNT REWRITE FD-ACCTFILE-REC";

    static final String WRITE_TRANFILE_VERB = "1300-B-WRITE-TX WRITE FD-TRANFILE-REC";

    static final String TRANSACT_ABNORMAL_DISPOSITION =
            "app/jcl/INTCALC.jcl:37 TRANSACT DISP=(NEW,CATLG,DELETE) abnormal disposition";

    static final String TRANSACT_OPEN_DISPOSITION =
            "app/jcl/INTCALC.jcl:37 TRANSACT DISP=(NEW,CATLG,DELETE) new-generation allocation";

    /**
     * {@code TCATBALF} - the driving browse ({@code app/jcl/INTCALC.jcl:27-28}).
     */
    public static final String TCATBALF_DD_NAME = TranCatBalRepository.DD_NAME;

    /**
     * {@code ACCTFILE} - the account master, opened {@code I-O} ({@code app/jcl/INTCALC.jcl:33-34}).
     */
    public static final String ACCTFILE_DD_NAME = AccountRepository.BATCH_DD_NAME;

    /**
     * {@code DISCGRP} - the disclosure group rates ({@code app/jcl/INTCALC.jcl:35-36}).
     */
    public static final String DISCGRP_DD_NAME = "DISCGRP";

    /**
     * {@code XREFFILE} - the cross-reference base cluster ({@code app/jcl/INTCALC.jcl:29-30}).
     */
    public static final String XREFFILE_DD_NAME = CardXrefRepository.BATCH_DD_NAME;

    /**
     * {@code XREFFIL1} - the alternate-index path over the same cluster
     * ({@code app/jcl/INTCALC.jcl:31-32}).
     */
    public static final String XREFFIL1_DD_NAME = CardXrefRepository.ALTERNATE_INDEX_BATCH_DD_NAME;

    /**
     * {@code TRANSACT} - the generated-transaction output ({@code app/jcl/INTCALC.jcl:37-41}).
     */
    public static final String TRANSACT_DD_NAME = TransactionRepository.CICS_FILE_NAME;

    /**
     * {@code DISPLAY 'START OF EXECUTION OF PROGRAM CBACT04C'} - {@code app/cbl/CBACT04C.cbl:181}.
     */
    public static final String START_OF_EXECUTION = "START OF EXECUTION OF PROGRAM " + PROGRAM_ID;

    /**
     * {@code DISPLAY 'END OF EXECUTION OF PROGRAM CBACT04C'} - {@code app/cbl/CBACT04C.cbl:230}.
     */
    public static final String END_OF_EXECUTION = "END OF EXECUTION OF PROGRAM " + PROGRAM_ID;

    /**
     * {@code app/cbl/CBACT04C.cbl:245}.
     */
    public static final String ERROR_OPENING_TCATBALF =
            "ERROR OPENING TRANSACTION CATEGORY BALANCE";

    /**
     * {@code app/cbl/CBACT04C.cbl:263}.
     */
    public static final String ERROR_OPENING_XREFFILE = "ERROR OPENING CROSS REF FILE";

    /**
     * {@code app/cbl/CBACT04C.cbl:281} - and the text names the wrong file.
     */
    public static final String ERROR_OPENING_DISCGRP = "ERROR OPENING DALY REJECTS FILE";

    /**
     * {@code app/cbl/CBACT04C.cbl:300}.
     */
    public static final String ERROR_OPENING_ACCTFILE = "ERROR OPENING ACCOUNT MASTER FILE";

    /**
     * {@code app/cbl/CBACT04C.cbl:318}.
     */
    public static final String ERROR_OPENING_TRANFILE = "ERROR OPENING TRANSACTION FILE";

    /**
     * {@code app/cbl/CBACT04C.cbl:342}.
     */
    public static final String ERROR_READING_TCATBALF = "ERROR READING TRANSACTION CATEGORY FILE";

    /**
     * {@code app/cbl/CBACT04C.cbl:365}.
     */
    public static final String ERROR_REWRITING_ACCTFILE = "ERROR RE-WRITING ACCOUNT FILE";

    /**
     * {@code app/cbl/CBACT04C.cbl:386}.
     */
    public static final String ERROR_READING_ACCTFILE = "ERROR READING ACCOUNT FILE";

    /**
     * {@code app/cbl/CBACT04C.cbl:408}.
     */
    public static final String ERROR_READING_XREFFILE = "ERROR READING XREF FILE";

    /**
     * {@code app/cbl/CBACT04C.cbl:431}.
     */
    public static final String ERROR_READING_DISCGRP = "ERROR READING DISCLOSURE GROUP FILE";

    /**
     * {@code app/cbl/CBACT04C.cbl:455}.
     */
    public static final String ERROR_READING_DEFAULT_DISCGRP =
            "ERROR READING DEFAULT DISCLOSURE GROUP";

    /**
     * {@code app/cbl/CBACT04C.cbl:510}.
     */
    public static final String ERROR_WRITING_TRANSACTION = "ERROR WRITING TRANSACTION RECORD";

    /**
     * {@code app/cbl/CBACT04C.cbl:533}.
     */
    public static final String ERROR_CLOSING_TCATBALF = "ERROR CLOSING TRANSACTION BALANCE FILE";

    /**
     * {@code app/cbl/CBACT04C.cbl:552}.
     */
    public static final String ERROR_CLOSING_XREFFILE = "ERROR CLOSING CROSS REF FILE";

    /**
     * {@code app/cbl/CBACT04C.cbl:570}.
     */
    public static final String ERROR_CLOSING_DISCGRP = "ERROR CLOSING DISCLOSURE GROUP FILE";

    /**
     * {@code app/cbl/CBACT04C.cbl:588}.
     */
    public static final String ERROR_CLOSING_ACCTFILE = "ERROR CLOSING ACCOUNT FILE";

    /**
     * {@code app/cbl/CBACT04C.cbl:606}.
     */
    public static final String ERROR_CLOSING_TRANFILE = "ERROR CLOSING TRANSACTION FILE";

    /**
     * {@code DISPLAY 'ACCOUNT NOT FOUND: ' FD-ACCT-ID} - {@code app/cbl/CBACT04C.cbl:375}, and again at
     * {@code :397} for {@code FD-XREF-ACCT-ID}.
     */
    public static final String ACCOUNT_NOT_FOUND_PREFIX = "ACCOUNT NOT FOUND: ";

    /**
     * {@code DISPLAY 'DISCLOSURE GROUP RECORD MISSING'} - {@code app/cbl/CBACT04C.cbl:418}.
     */
    public static final String DISCLOSURE_GROUP_RECORD_MISSING = "DISCLOSURE GROUP RECORD MISSING";

    /**
     * {@code DISPLAY 'TRY WITH DEFAULT GROUP CODE'} - {@code app/cbl/CBACT04C.cbl:419}.
     */
    public static final String TRY_WITH_DEFAULT_GROUP_CODE = "TRY WITH DEFAULT GROUP CODE";

    /**
     * {@code MOVE '01' TO TRAN-TYPE-CD} - {@code app/cbl/CBACT04C.cbl:482}, into {@code PIC X(02)}.
     */
    public static final String GENERATED_TRAN_TYPE_CD = "01";

    /**
     * {@code MOVE '05' TO TRAN-CAT-CD} - {@code app/cbl/CBACT04C.cbl:483}.
     */
    public static final String GENERATED_TRAN_CAT_CD = "05";

    /**
     * {@code MOVE 'System' TO TRAN-SOURCE} - {@code app/cbl/CBACT04C.cbl:484}.
     */
    public static final String GENERATED_TRAN_SOURCE = "System";

    /**
     * {@code STRING 'Int. for a/c ' , ACCT-ID ... INTO TRAN-DESC} - {@code app/cbl/CBACT04C.cbl:485-489}.
     */
    public static final String GENERATED_TRAN_DESC_PREFIX = "Int. for a/c ";

    /**
     * {@code MOVE 'DEFAULT' TO FD-DIS-ACCT-GROUP-ID} - {@code app/cbl/CBACT04C.cbl:437}.
     */
    public static final String DEFAULT_ACCT_GROUP_ID = DisclosureGroupRecord.DEFAULT_ACCT_GROUP_ID;

    /**
     * The width of {@code DB2-FORMAT-TS PIC X(26)}: {@value}.
     */
    public static final int DB2_TIMESTAMP_LENGTH = 26;

    /**
     * {@code MOVE '-' TO DB2-STREEP-1 DB2-STREEP-2 DB2-STREEP-3} - {@code app/cbl/CBACT04C.cbl:623}.
     */
    public static final char DB2_TIMESTAMP_HYPHEN = '-';

    /**
     * {@code MOVE '.' TO DB2-DOT-1 DB2-DOT-2 DB2-DOT-3} - {@code app/cbl/CBACT04C.cbl:624}.
     */
    public static final char DB2_TIMESTAMP_DOT = '.';

    /**
     * {@code MOVE '0000' TO DB2-REST} - {@code app/cbl/CBACT04C.cbl:622}, filling {@code DB2-REST X(04)}.
     */
    public static final String DB2_TIMESTAMP_REST = "0000";

    /**
     * The width of {@code COBOL-TS}, the receiver of {@code FUNCTION CURRENT-DATE}: {@value}.
     */
    public static final int COBOL_TIMESTAMP_LENGTH = 21;

    /**
     * {@code 88 APPL-AOK VALUE 0} - {@code app/cbl/CBACT04C.cbl:134}.
     */
    public static final int APPL_AOK = FileStatus.APPL_AOK;

    /**
     * {@code 88 APPL-EOF VALUE 16} - {@code app/cbl/CBACT04C.cbl:135}.
     */
    public static final int APPL_EOF = FileStatus.APPL_EOF;

    /**
     * The {@code MOVE 8 TO APPL-RESULT} every open and close paragraph begins with.
     */
    public static final int APPL_RESULT_ASSUMED_FAILURE = AbendException.RETURN_CODE_ASSUMED_FAILURE;

    /**
     * The {@code MOVE 12 TO APPL-RESULT} of every failure arm, and so the abend's return code.
     */
    public static final int APPL_RESULT_FATAL = AbendException.RETURN_CODE_IO_ERROR;

    /**
     * {@code 01 END-OF-FILE PIC X(01) VALUE 'N'} - {@code app/cbl/CBACT04C.cbl:137}.
     */
    public static final String END_OF_FILE_NO = "N";

    /**
     * {@code MOVE 'Y' TO END-OF-FILE} - {@code app/cbl/CBACT04C.cbl:340}.
     */
    public static final String END_OF_FILE_YES = "Y";

    /**
     * {@code 05 WS-FIRST-TIME PIC X(01) VALUE 'Y'} - {@code app/cbl/CBACT04C.cbl:170}.
     */
    public static final String FIRST_TIME_YES = "Y";

    /**
     * {@code MOVE 'N' TO WS-FIRST-TIME} - {@code app/cbl/CBACT04C.cbl:198}.
     */
    public static final String FIRST_TIME_NO = "N";

    /**
     * {@code 05 WS-LAST-ACCT-NUM PIC X(11) VALUE SPACES} - {@code app/cbl/CBACT04C.cbl:167}.
     */
    public static final int LAST_ACCT_NUM_LENGTH = 11;

    /**
     * The initial content of {@code WS-LAST-ACCT-NUM}: eleven spaces.
     */
    public static final String LAST_ACCT_NUM_INITIAL = " ".repeat(LAST_ACCT_NUM_LENGTH);

    /**
     * {@code 05 WS-RECORD-COUNT PIC 9(09) VALUE 0} - {@code app/cbl/CBACT04C.cbl:172}.
     */
    public static final long RECORD_COUNT_MODULUS = 1_000_000_000L;

    /**
     * {@code 05 WS-TRANID-SUFFIX PIC 9(06) VALUE 0} - {@code app/cbl/CBACT04C.cbl:173}.
     */
    public static final long TRANID_SUFFIX_MODULUS = 1_000_000L;

    /**
     * The width of {@code WS-TRANID-SUFFIX PIC 9(06)}, which is also its contribution to {@code TRAN-ID}.
     */
    public static final int TRANID_SUFFIX_WIDTH = 6;

    /**
     * The width of {@code PARM-DATE PIC X(10)} - {@code app/cbl/CBACT04C.cbl:178}.
     */
    public static final int PARM_DATE_WIDTH = BatchConfig.PARM_DATE_WIDTH;

    static {
        verifyDeclaredKeyGeometry();
    }

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

    private final BatchConfig batchConfig;

    private final TranCatBalRepository tranCatBalRepository;

    private final AccountRepository accountRepository;

    private final CardXrefRepository cardXrefRepository;

    private final TransactionRepository transactionRepository;

    private final DisclosureGroupAccess disclosureGroupAccess;

    private final DatasetUnitOfWork unitOfWork;

    private final Charset datasetCharset;

    private final FixedWidthCodec codec;

    private final SysoutSink sysoutSink;

    private final Clock clock;

    private final StepContract stepContract;

    private final String declaredParmDate;

    /**
     * Wires the job from its five datasets, the batch scaffolding and one optional seam.
     *
     * @param batchConfig the batch scaffolding; required
     * @param tranCatBalRepository {@code TCATBALF}; required
     * @param accountRepository {@code ACCTFILE}; required
     * @param cardXrefRepository {@code XREFFILE} and its alternate-index path; required
     * @param transactionRepository {@code TRANSACT}, the generated-transaction output; required
     * @param disclosureGroupAccess {@code DISCGRP}, the rate lookup; required
     * @param unitOfWork the persistence boundary this program's two mutating verbs commit through, one verb
     *     at a time; required
     * @param sysoutSinkProvider the {@code SYSOUT} destination; may resolve to no bean, in which case the
     *     process's standard output is used in the dataset code page
     * @param clock the clock behind {@code FUNCTION CURRENT-DATE}; required
     * @throws NullPointerException if any required collaborator or provider is {@code null}
     * @throws IllegalStateException if this job's {@code carddemo.jobs} contract names another program,
     *     gates its only step, or declares no {@code parmDate}
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

    private static void requireStepDatasets(BatchConfig scaffolding) {
        scaffolding.requireSameDataset(JOB_KEY, TCATBALF_DD_NAME, TranCatBalRepository.DD_NAME);
        scaffolding.requireSameDataset(JOB_KEY, ACCTFILE_DD_NAME, AccountRepository.CICS_FILE_NAME);
        scaffolding.requireSameDataset(JOB_KEY, XREFFILE_DD_NAME, CardXrefRepository.BASE_DD_NAME);
        scaffolding.requireSameDataset(JOB_KEY, XREFFIL1_DD_NAME,
                CardXrefRepository.ALTERNATE_INDEX_DD_NAME);
        scaffolding.requireSameDataset(JOB_KEY, TRANSACT_DD_NAME,
                TransactionRepository.SEQUENTIAL_OUTPUT_DD_NAME);
    }

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
    // The Spring Batch surface: one job, one chunk-oriented step of CHUNK_SIZE items.
    //
    // Only the Job is a bean here. The DISCGRP access path is DisclosureGroupRepository, a @Repository
    // in this package that the context supplies by component scan (gate G10). The step and the chunk
    // delegate are ordinary methods, deliberately: nine sibling job classes live in this module, and a
    // published Step, ItemReader or ItemWriter bean from each would make every by-type injection of
    // those interfaces ambiguous at once.
    // =================================================================================================

    /**
     * The job {@code app/jcl/INTCALC.jcl} submits: one step, no gate, and one validated parameter.
     *
     * @return the job; never {@code null}
     */
    @Bean
    public Job accountInterestCalcJob() {
        return batchConfig.job(JOB_NAME)
                .preventRestart()
                .start(accountInterestCalcStep())
                .build();
    }

    /**
     * The single step, chunk-oriented at a commit interval of {@value #CHUNK_SIZE}.
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

    public ChunkDelegate newChunkDelegate() {
        return new ChunkDelegate(this);
    }

    /**
     * The job parameters {@code app/jcl/INTCALC.jcl:22} declares: the single
     * {@value BatchConfig#PARM_DATE_PARAMETER} string.
     *
     * @return the declared parameters; never {@code null}
     */
    public JobParameters jobParameters() {
        return batchConfig.contract(JOB_KEY).jobParameters();
    }

    private CardXrefRepository xrefFileRepository() {
        return cardXrefRepository.addressing(
                batchConfig.datasetBinding(JOB_KEY, XREFFILE_DD_NAME), XREFFILE_DD_NAME,
                batchConfig.datasetBinding(JOB_KEY, XREFFIL1_DD_NAME), XREFFIL1_DD_NAME);
    }

    public StepContract stepContract() {
        return stepContract;
    }

    /**
     * The {@code PARM-DATE} the JCL step card declares, already in {@code PIC X(10)} form.
     *
     * @return exactly {@link #PARM_DATE_WIDTH} characters; never {@code null}
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

    /**
     * Runs the whole program against the declared {@code PARM} and the injected {@code SYSOUT} sink.
     *
     * @return the number of {@code TCATBALF} records processed, which is {@code WS-RECORD-COUNT} at
     *     {@code GOBACK}
     * @throws AbendException if any file operation the program checks reports a status it treats as fatal
     */
    public long calculateInterest() {
        return calculateInterest(declaredParmDate, sysoutSink);
    }

    /**
     * Runs the whole program: {@code app/cbl/CBACT04C.cbl:181-232}, statement for statement.
     *
     * <p>The branch is present, in the position the COBOL puts it, and is reachable from a test through
     * {@link WorkingStorage#moveEndOfFile(String)} exactly as it would be reachable in COBOL through a
     * third value.
     *
     * @param parmDate the {@code PARM-DATE} to concatenate into the generated transaction identifiers;
     *     taken verbatim, never parsed, and moved into {@code PIC X(10)} on the way in
     * @param sysout where the displayed lines go; the whole of this program's non-dataset output
     * @return {@code WS-RECORD-COUNT} at {@code GOBACK}
     * @throws NullPointerException if {@code parmDate} or {@code sysout} is {@code null}
     * @throws AbendException if any file operation the program checks reports a status it treats as fatal,
     *     carrying {@link #APPL_RESULT_FATAL} as the return code
     */
    public long calculateInterest(String parmDate, SysoutSink sysout) {
        InterestCalculationRun run = newRun(parmDate, sysout);
        boolean normalEnd = false;
        try {
            run.sysout().write(START_OF_EXECUTION);

            run.openFiles();

            while (!run.workingStorage().endOfFileIsYes()) {
                if (run.workingStorage().endOfFileIsNo()) {
                    TranCatBalRecord item = run.tcatbalfGetNext();

                    if (run.workingStorage().endOfFileIsNo()) {
                        run.processRecord(item);
                    }
                } else {
                    // The loop guard above already established that END-OF-FILE is not 'Y', and
                    // 1000-TCATBALF-GET-NEXT moves nothing but 'Y' into it, so this arm cannot run and the
                    // final account group is never rewritten.
                    run.updateAccount();
                }
            }

            run.closeFiles();

            run.sysout().write(END_OF_EXECUTION);

            normalEnd = true;
            return run.workingStorage().recordCount();
        } finally {
            if (normalEnd) {
                run.release();
            } else {
                run.releaseAbnormally();
            }
        }
    }

    InterestCalculationRun newRun(String parmDate, SysoutSink sysout) {
        Objects.requireNonNull(parmDate, "A PARM-DATE is required: app/cbl/CBACT04C.cbl:476-480 "
                + "concatenates it into every transaction identifier this program writes, so a run "
                + "without one could not build an identifier at all");
        Objects.requireNonNull(sysout, "A SYSOUT sink is required: the displayed line sequence is this "
                + "program's observable output beyond its datasets, so there is nothing to run without "
                + "somewhere to write it");
        return new InterestCalculationRun(this, codec.movePicX(parmDate, PARM_DATE_WIDTH), sysout);
    }

    /**
     * There is no {@code ROUNDED} phrase and no {@code ON SIZE ERROR} phrase on that statement, and the
     * receiver is {@code WS-MONTHLY-INT PIC S9(09)V99} ({@code :168}).
     *
     * @param tranCatBal {@code TRAN-CAT-BAL}, the category balance to charge interest on
     * @param disIntRate {@code DIS-INT-RATE}, the annual rate as a percentage, so {@code 12.50} means
     *     twelve and a half percent
     * @return the monthly interest, truncated to scale 2
     * @throws NullPointerException if either operand is {@code null}
     */
    public static BigDecimal computeMonthlyInterest(BigDecimal tranCatBal, BigDecimal disIntRate) {
        return CobolDecimal.monthlyInterest(tranCatBal, disIntRate);
    }

    /**
     * This method does nothing, and that is the specification.
     */
    public void computeFees() {
        // Intentionally empty: app/cbl/CBACT04C.cbl:518-520 is a stub marked "To be implemented" and
        // performs no statement. Preserving dead code exactly is a parity requirement, not an oversight.
    }

    /**
     * One execution of {@code CBACT04C}: its {@code WORKING-STORAGE}, its five open files, and each of its
     * paragraphs as a separately callable method.
     *
     * <p>The record areas are allocated once per run and reused, exactly as {@code WORKING-STORAGE} is.
     */
    static final class InterestCalculationRun {
        private final AccountInterestCalcJob job;

        private final String parmDate;

        private final SysoutSink sysout;

        private final WorkingStorage workingStorage = new WorkingStorage();

        private final TranRecord tranRecord;

        private final DisclosureGroupRecord fdDiscgrpRec;

        private DisclosureGroupRecord disGroupRecord;

        private AccountRecord accountRecord;

        private CardXrefRecord cardXrefRecord;

        private TranCatBalRecord tranCatBalRecord;

        private TranCatBalFile tcatbalFile;

        private BrowseCursor xrefFile;

        private CardXrefRepository xrefRepository;

        private DisclosureGroupFile discgrpFile;

        private AccountFile acctFile;

        private OutputFile tranFile;

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

        WorkingStorage workingStorage() {
            return workingStorage;
        }

        SysoutSink sysout() {
            return sysout;
        }

        /**
         * {@code PARM-DATE}, exactly {@link #PARM_DATE_WIDTH} characters.
         *
         * @return the PARM; never {@code null}
         */
        String parmDate() {
            return parmDate;
        }

        TranRecord tranRecord() {
            return tranRecord;
        }

        DisclosureGroupRecord fdDiscgrpRec() {
            return fdDiscgrpRec;
        }

        DisclosureGroupRecord disGroupRecord() {
            return disGroupRecord;
        }

        AccountRecord accountRecord() {
            return accountRecord;
        }

        CardXrefRecord cardXrefRecord() {
            return cardXrefRecord;
        }

        TranCatBalRecord tranCatBalRecord() {
            return tranCatBalRecord;
        }

        void openFiles() {
            tcatbalfOpen();
            xreffileOpen();
            discgrpOpen();
            acctfileOpen();
            tranfileOpen();
        }

        void tcatbalfOpen() {
            workingStorage.moveToApplResult(APPL_RESULT_ASSUMED_FAILURE);
            tcatbalFile = job.tranCatBalRepository.open(TranCatBalRepository.OpenMode.INPUT);
            String status = tcatbalFile.openStatus();
            applResultFromOkStatus(status);
            if (!workingStorage.applAok()) {
                throw reportAndAbend(ERROR_OPENING_TCATBALF, status);
            }
        }

        void xreffileOpen() {
            workingStorage.moveToApplResult(APPL_RESULT_ASSUMED_FAILURE);
            xrefRepository = job.xrefFileRepository();
            xrefFile = xrefRepository.openBrowse();
            String status = xrefFile.openStatus();
            applResultFromOkStatus(status);
            if (!workingStorage.applAok()) {
                throw reportAndAbend(ERROR_OPENING_XREFFILE + status, status);
            }
        }

        void discgrpOpen() {
            workingStorage.moveToApplResult(APPL_RESULT_ASSUMED_FAILURE);
            discgrpFile = job.disclosureGroupAccess.open();
            String status = discgrpFile.openStatus();
            applResultFromOkStatus(status);
            if (!workingStorage.applAok()) {
                throw reportAndAbend(ERROR_OPENING_DISCGRP, status);
            }
        }

        void acctfileOpen() {
            workingStorage.moveToApplResult(APPL_RESULT_ASSUMED_FAILURE);
            acctFile = job.accountRepository.open(AccountRepository.OpenMode.I_O);
            String status = acctFile.openStatus();
            applResultFromOkStatus(status);
            if (!workingStorage.applAok()) {
                throw reportAndAbend(ERROR_OPENING_ACCTFILE, status);
            }
        }

        void tranfileOpen() {
            workingStorage.moveToApplResult(APPL_RESULT_ASSUMED_FAILURE);
            DatasetBinding binding = job.batchConfig.datasetBinding(JOB_KEY, TRANSACT_DD_NAME);
            tranFile = job.unitOfWork.persistDisposition(TRANSACT_OPEN_DISPOSITION,
                    () -> job.transactionRepository.openOutput(binding, TRANSACT_DD_NAME));
            String status = tranFile.openStatus();
            applResultFromOkStatus(status);
            if (!workingStorage.applAok()) {
                throw reportAndAbend(ERROR_OPENING_TRANFILE, status);
            }
        }

        void closeFiles() {
            tcatbalfClose();
            xreffileClose();
            discgrpClose();
            acctfileClose();
            tranfileClose();
        }

        void tcatbalfClose() {
            workingStorage.moveToApplResult(APPL_RESULT_ASSUMED_FAILURE);
            String status = tcatbalFile.closeFile();
            applResultFromOkStatus(status);
            if (!workingStorage.applAok()) {
                throw reportAndAbend(ERROR_CLOSING_TCATBALF, status);
            }
        }

        void xreffileClose() {
            workingStorage.moveToApplResult(APPL_RESULT_ASSUMED_FAILURE);
            String status = xrefFile.closeBrowse();
            applResultFromOkStatus(status);
            if (!workingStorage.applAok()) {
                throw reportAndAbend(ERROR_CLOSING_XREFFILE, status);
            }
        }

        void discgrpClose() {
            workingStorage.moveToApplResult(APPL_RESULT_ASSUMED_FAILURE);
            String status = discgrpFile.closeFile();
            applResultFromOkStatus(status);
            if (!workingStorage.applAok()) {
                throw reportAndAbend(ERROR_CLOSING_DISCGRP, status);
            }
        }

        void acctfileClose() {
            workingStorage.moveToApplResult(APPL_RESULT_ASSUMED_FAILURE);
            String status = acctFile.closeFile();
            applResultFromOkStatus(status);
            if (!workingStorage.applAok()) {
                throw reportAndAbend(ERROR_CLOSING_ACCTFILE, status);
            }
        }

        void tranfileClose() {
            workingStorage.moveToApplResult(APPL_RESULT_ASSUMED_FAILURE);
            String status = tranFile.closeOutput();
            applResultFromOkStatus(status);
            if (!workingStorage.applAok()) {
                throw reportAndAbend(ERROR_CLOSING_TRANFILE, status);
            }
        }

        void releaseAbnormally() {
            if (tranFile != null) {
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

        TranCatBalRecord tcatbalfGetNext() {
            TranCatBalRepository.ReadResult result = tcatbalFile.readNext();
            String status = result.status();

            if (FileStatus.isOk(status)) {
                workingStorage.moveToApplResult(APPL_AOK);
            } else if (FileStatus.isEndOfFile(status)) {
                workingStorage.moveToApplResult(APPL_EOF);
            } else {
                workingStorage.moveToApplResult(APPL_RESULT_FATAL);
            }

            if (!workingStorage.applAok()) {
                if (workingStorage.applEof()) {
                    workingStorage.moveEndOfFile(END_OF_FILE_YES);
                    return null;
                }
                throw reportAndAbend(ERROR_READING_TCATBALF, status);
            }

            tranCatBalRecord = result.record().orElseThrow(() -> new IllegalStateException(
                    "A read of " + TCATBALF_DD_NAME + " reported file status " + FileStatus.OK
                            + " with no decoded record, so READ ... INTO TRAN-CAT-BAL-RECORD had nothing "
                            + "to transfer. Only the success arm carries a record, and it always does."));
            return tranCatBalRecord;
        }

        RecordOutcome processRecord(TranCatBalRecord item) {
            Objects.requireNonNull(item, "A category balance record is required to process one; end of "
                    + "file is signalled by tcatbalfGetNext() returning null and is handled by the "
                    + "mainline, not here");

            workingStorage.addOneToRecordCount();

            sysout.write(item.rawImage());

            boolean accountRewritten = false;

            if (!item.trancatAcctIdImage().equals(workingStorage.lastAcctNum())) {
                if (!workingStorage.firstTimeIsYes()) {
                    updateAccount();
                    accountRewritten = true;
                } else {
                    workingStorage.moveFirstTime(FIRST_TIME_NO);
                }
                workingStorage.moveZeroToTotalInterest();
                workingStorage.moveLastAcctNum(item.trancatAcctIdImage());
                getAcctData(item.trancatAcctId());
                getXrefData(item.trancatAcctId());
            }

            fdDiscgrpRec.disAcctGroupId(accountRecord.rawAcctGroupId());
            fdDiscgrpRec.disTranCatCd(item.trancatCd());
            fdDiscgrpRec.disTranTypeCd(item.trancatTypeCd());

            getInterestRate();

            boolean interestComputed = false;
            boolean feesComputed = false;
            BigDecimal monthlyInterest = CobolDecimal.monetaryZero();
            String tranId = "";

            if (disGroupRecord.disIntRateIsNotZero()) {
                computeInterest(item);
                monthlyInterest = workingStorage.monthlyInterest();
                tranId = tranRecord.tranId();
                interestComputed = true;

                // PERFORM 1400-COMPUTE-FEES - a genuine no-op, called from exactly here. L216.
                job.computeFees();
                feesComputed = true;
            }

            return new RecordOutcome(workingStorage.recordCount(), item.tranCatKeyImage(),
                    accountRewritten, interestComputed, feesComputed, interestComputed, monthlyInterest,
                    tranId);
        }

        void updateAccount() {
            BigDecimal posted = CobolDecimal.add(accountRecord.getAcctCurrBal(),
                    workingStorage.totalInterest(), AccountRecord.MONETARY_SCALE);
            accountRecord.setAcctCurrBal(CobolDecimal.storeAtPicture(posted,
                    AccountRecord.MONETARY_INTEGER_DIGITS, AccountRecord.MONETARY_SCALE));

            accountRecord.zeroAcctCurrCycCredit();
            accountRecord.zeroAcctCurrCycDebit();

            // The COBOL leaves this rewrite in place - no syncpoint, and ACCTDAT is RECOVERY(NONE) - so it
            // must not be enrolled in a boundary a later failure rolls back.
            AccountRepository.WriteResult result = job.unitOfWork.persistVerb(
                    REWRITE_ACCTFILE_VERB, () -> acctFile.rewrite(accountRecord));
            String status = result.status();
            applResultFromOkStatus(status);
            if (!workingStorage.applAok()) {
                throw reportAndAbend(ERROR_REWRITING_ACCTFILE, status);
            }
        }

        void getAcctData(long acctId) {
            String keyImage = job.codec.movePic9(acctId, AccountRecord.ACCT_ID_LENGTH);

            AccountRepository.ReadResult result = acctFile.readByKey(acctId);
            if (result.isNotFound()) {
                sysout.write(ACCOUNT_NOT_FOUND_PREFIX + keyImage);
            }

            String status = result.status();
            applResultFromOkStatus(status);
            if (!workingStorage.applAok()) {
                throw reportAndAbend(ERROR_READING_ACCTFILE, status);
            }

            accountRecord = result.account().orElseThrow(() -> new IllegalStateException(
                    "A read of " + ACCTFILE_DD_NAME + " reported file status " + FileStatus.OK
                            + " with no decoded record, so READ ... INTO ACCOUNT-RECORD had nothing to "
                            + "transfer. Only the success arm carries a record, and it always does."));
        }

        void getXrefData(long acctId) {
            String keyImage = job.codec.movePic9(acctId, CardXrefRecord.XREF_ACCT_ID_LENGTH);

            CardXrefRepository.ReadResult result =
                    xrefRepository.readByAccountIdViaAltIndex(acctId);
            if (result.isNotFound()) {
                sysout.write(ACCOUNT_NOT_FOUND_PREFIX + keyImage);
            }

            String status = result.status();
            applResultFromOkStatus(status);
            if (!workingStorage.applAok()) {
                throw reportAndAbend(ERROR_READING_XREFFILE, status);
            }

            cardXrefRecord = result.record().orElseThrow(() -> new IllegalStateException(
                    "A read of " + XREFFILE_DD_NAME + " reported file status " + FileStatus.OK
                            + " with no decoded record, so READ ... INTO CARD-XREF-RECORD had nothing to "
                            + "transfer. Only the success arm carries a record, and it always does."));
        }

        void getInterestRate() {
            DisclosureGroupRead read = discgrpFile.readByKey(fdDiscgrpRec.disGroupKey());
            if (read.isNotFound()) {
                sysout.write(DISCLOSURE_GROUP_RECORD_MISSING);
                sysout.write(TRY_WITH_DEFAULT_GROUP_CODE);
            }
            read.record().ifPresent(record -> disGroupRecord = record);

            String status = read.status();
            if (FileStatus.isOkOrNotFound(status)) {
                workingStorage.moveToApplResult(APPL_AOK);
            } else {
                workingStorage.moveToApplResult(APPL_RESULT_FATAL);
            }
            if (!workingStorage.applAok()) {
                throw reportAndAbend(ERROR_READING_DISCGRP, status);
            }

            if (FileStatus.isNotFound(status)) {
                fdDiscgrpRec.disAcctGroupId(DEFAULT_ACCT_GROUP_ID);
                getDefaultInterestRate();
            }
        }

        void getDefaultInterestRate() {
            DisclosureGroupRead read = discgrpFile.readByKey(fdDiscgrpRec.disGroupKey());
            read.record().ifPresent(record -> disGroupRecord = record);

            String status = read.status();
            applResultFromOkStatus(status);
            if (!workingStorage.applAok()) {
                throw reportAndAbend(ERROR_READING_DEFAULT_DISCGRP, status);
            }
        }

        void computeInterest(TranCatBalRecord item) {
            workingStorage.moveToMonthlyInterest(
                    computeMonthlyInterest(item.tranCatBal(), disGroupRecord.disIntRate()));

            workingStorage.addToTotalInterest(workingStorage.monthlyInterest());

            writeTx();
        }

        void writeTx() {
            workingStorage.addOneToTranidSuffix();

            tranRecord.stringIntoTranId(parmDate, workingStorage.tranidSuffixImage(job.codec));

            tranRecord.moveTranTypeCd(GENERATED_TRAN_TYPE_CD);
            tranRecord.moveTranCatCd(GENERATED_TRAN_CAT_CD);
            tranRecord.moveTranSource(GENERATED_TRAN_SOURCE);

            tranRecord.stringIntoTranDesc(GENERATED_TRAN_DESC_PREFIX, accountRecord.rawAcctId());

            tranRecord.moveTranAmt(workingStorage.monthlyInterest());
            tranRecord.moveTranMerchantId(0L);
            tranRecord.moveTranMerchantName("");
            tranRecord.moveTranMerchantCity("");
            tranRecord.moveTranMerchantZip("");
            tranRecord.moveTranCardNum(cardXrefRecord.xrefCardNum());

            String timestamp = job.db2FormatTimestamp();
            tranRecord.moveTranOrigTs(timestamp);
            tranRecord.moveTranProcTs(timestamp);

            TransactionRepository.WriteResult result = job.unitOfWork.persistVerb(
                    WRITE_TRANFILE_VERB, () -> tranFile.writeSequential(tranRecord));
            String status = result.status();
            applResultFromOkStatus(status);
            if (!workingStorage.applAok()) {
                throw reportAndAbend(ERROR_WRITING_TRANSACTION, status);
            }
        }

        private void applResultFromOkStatus(String status) {
            if (FileStatus.isOk(status)) {
                workingStorage.moveToApplResult(APPL_AOK);
            } else {
                workingStorage.moveToApplResult(APPL_RESULT_FATAL);
            }
        }

        private AbendException reportAndAbend(String errorText, String status) {
            sysout.write(errorText);

            String statusLine = displayIoStatus(status);

            return abendProgram(errorText + " - " + statusLine);
        }

        private String displayIoStatus(String status) {
            String statusLine = FileStatus.toDisplayLine(status);
            sysout.write(statusLine);
            return statusLine;
        }

        private AbendException abendProgram(String reason) {
            sysout.write(AbendException.ABEND_DISPLAY_TEXT);
            return AbendException.standard(PROGRAM_ID, workingStorage.applResult(), reason);
        }
    }

    /**
     * Composes {@code DB2-FORMAT-TS} from the clock: {@code app/cbl/CBACT04C.cbl:613-626}.
     *
     * <p>The shape is {@code YYYY-MM-DD-HH.MM.SS.mm0000} - three hyphens and three dots, exactly
     * {@value #DB2_TIMESTAMP_LENGTH} characters.
     *
     * @return exactly {@value #DB2_TIMESTAMP_LENGTH} characters
     */
    public String db2FormatTimestamp() {
        CobolTimestamp current = currentDate();

        return current.yyyy()
                + DB2_TIMESTAMP_HYPHEN
                + current.mm()
                + DB2_TIMESTAMP_HYPHEN
                + current.dd()
                + DB2_TIMESTAMP_HYPHEN
                + current.hh()
                + DB2_TIMESTAMP_DOT
                + current.min()
                + DB2_TIMESTAMP_DOT
                + current.ss()
                + DB2_TIMESTAMP_DOT
                + current.mil()
                + DB2_TIMESTAMP_REST;
    }

    /**
     * {@code FUNCTION CURRENT-DATE} into {@code COBOL-TS} - {@code app/cbl/CBACT04C.cbl:141-149}.
     *
     * <p>All eight are modelled even though {@link #db2FormatTimestamp()} uses seven of them, because the
     * eighth occupies declared bytes of {@code COBOL-TS} and a partial model of a group item is a model
     * that cannot be checked.
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

    private String greenwichOffsetImage(int totalSeconds) {
        char sign = totalSeconds < 0 ? '-' : '+';
        int magnitude = Math.abs(totalSeconds);
        int hours = magnitude / SECONDS_PER_HOUR;
        int minutes = (magnitude % SECONDS_PER_HOUR) / SECONDS_PER_MINUTE;
        return sign + codec.movePic9(hours, 2) + codec.movePic9(minutes, 2);
    }

    private static final int NANOS_PER_HUNDREDTH = 10_000_000;

    private static final int SECONDS_PER_HOUR = 3_600;

    private static final int SECONDS_PER_MINUTE = 60;

    /**
     * {@code 01 COBOL-TS} - {@code app/cbl/CBACT04C.cbl:141-149}, the receiver of
     * {@code FUNCTION CURRENT-DATE}.
     *
     * @param yyyy {@code COB-YYYY PIC X(04)} - the four-digit year
     * @param mm {@code COB-MM PIC X(02)} - the month, {@code 01} to {@code 12}
     * @param dd {@code COB-DD PIC X(02)} - the day of the month
     * @param hh {@code COB-HH PIC X(02)} - the hour, {@code 00} to {@code 23}
     * @param min {@code COB-MIN PIC X(02)} - the minute
     * @param ss {@code COB-SS PIC X(02)} - the second
     * @param mil {@code COB-MIL PIC X(02)} - hundredths of a second, which is the whole of the intrinsic's
     *     sub-second precision
     * @param rest {@code COB-REST PIC X(05)} - the offset from Greenwich, never read by this program
     */
    public record CobolTimestamp(String yyyy, String mm, String dd, String hh, String min, String ss,
                                 String mil, String rest) {
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
         * The whole {@value #COBOL_TIMESTAMP_LENGTH}-character group image, in declaration order - which is
         * what {@code FUNCTION CURRENT-DATE} returned.
         *
         * @return the group's characters; never {@code null}
         */
        public String image() {
            return yyyy + mm + dd + hh + min + ss + mil + rest;
        }

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

    /**
     * One run's {@code WORKING-STORAGE}: {@code APPL-RESULT}, {@code END-OF-FILE}, {@code WS-MISC-VARS} and
     * {@code WS-COUNTERS}.
     */
    static final class WorkingStorage {
        private int applResult;

        private String endOfFile = END_OF_FILE_NO;

        private String lastAcctNum = LAST_ACCT_NUM_INITIAL;

        private BigDecimal monthlyInterest = CobolDecimal.monetaryZero();

        private BigDecimal totalInterest = CobolDecimal.monetaryZero();

        private String firstTime = FIRST_TIME_YES;

        private long recordCount;

        private long tranidSuffix;

        boolean applAok() {
            return applResult == APPL_AOK;
        }

        boolean applEof() {
            return applResult == APPL_EOF;
        }

        void moveToApplResult(int value) {
            applResult = value;
        }

        int applResult() {
            return applResult;
        }

        boolean endOfFileIsYes() {
            return END_OF_FILE_YES.equals(endOfFile);
        }

        boolean endOfFileIsNo() {
            return END_OF_FILE_NO.equals(endOfFile);
        }

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

        String endOfFileFlag() {
            return endOfFile;
        }

        boolean firstTimeIsYes() {
            return FIRST_TIME_YES.equals(firstTime);
        }

        void moveFirstTime(String value) {
            Objects.requireNonNull(value, "WS-FIRST-TIME is PIC X(01) and holds a character, never null");
            if (value.length() != 1) {
                throw new IllegalArgumentException("WS-FIRST-TIME is PIC X(01), so it holds exactly one "
                        + "character; '" + value + "' is " + value.length() + '.');
            }
            firstTime = value;
        }

        String firstTimeFlag() {
            return firstTime;
        }

        String lastAcctNum() {
            return lastAcctNum;
        }

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

        // WS-MONTHLY-INT and WS-TOTAL-INT - both PIC S9(09)V99, both scale 2, both truncating.

        BigDecimal monthlyInterest() {
            return monthlyInterest;
        }

        void moveToMonthlyInterest(BigDecimal value) {
            Objects.requireNonNull(value, "WS-MONTHLY-INT is PIC S9(09)V99 and holds a number, never "
                    + "null");
            monthlyInterest = CobolDecimal.storeAtPicture(value, INTEREST_INTEGER_DIGITS,
                    CobolDecimal.MONETARY_SCALE);
        }

        BigDecimal totalInterest() {
            return totalInterest;
        }

        void moveZeroToTotalInterest() {
            totalInterest = CobolDecimal.monetaryZero();
        }

        void addToTotalInterest(BigDecimal addend) {
            Objects.requireNonNull(addend, "ADD WS-MONTHLY-INT TO WS-TOTAL-INT adds a number, never "
                    + "null");
            totalInterest = CobolDecimal.storeAtPicture(
                    CobolDecimal.add(totalInterest, addend, CobolDecimal.MONETARY_SCALE),
                    INTEREST_INTEGER_DIGITS, CobolDecimal.MONETARY_SCALE);
        }

        long recordCount() {
            return recordCount;
        }

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

        void addOneToTranidSuffix() {
            tranidSuffix = (tranidSuffix + 1) % TRANID_SUFFIX_MODULUS;
        }

        String tranidSuffixImage(FixedWidthCodec codec) {
            Objects.requireNonNull(codec, "A codec is required to render WS-TRANID-SUFFIX at its "
                    + "declared width; the zero-fill rule lives there and is not restated here");
            return codec.movePic9(tranidSuffix, TRANID_SUFFIX_WIDTH);
        }
    }

    /**
     * The integer precision of {@code WS-MONTHLY-INT} and {@code WS-TOTAL-INT}, both {@code PIC S9(09)V99}:
     * {@value}.
     */
    public static final int INTEREST_INTEGER_DIGITS = 9;

    @FunctionalInterface
    public interface SysoutSink {
        void write(String line);
    }

    /**
     * The default {@code SYSOUT}: the process's standard output, in the dataset's own code page.
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

    /**
     * What processing one category balance record did.
     *
     * @param recordNumber {@code WS-RECORD-COUNT} after this record, so the first record is 1
     * @param tranCatKeyImage the record's 17-character {@code TRAN-CAT-KEY} image
     * @param accountRewritten whether this record's account break rewrote the previous account -
     *     {@code true} only when {@code 1050-UPDATE-ACCOUNT} ran, so never on the first record
     * @param interestComputed whether {@code DIS-INT-RATE} was non-zero and {@code 1300-COMPUTE-INTEREST}
     *     therefore ran
     * @param feesComputed whether {@code 1400-COMPUTE-FEES} was invoked; always equal to
     *     {@code interestComputed}, because the two are performed from the same guarded block
     * @param transactionWritten whether a transaction reached the output dataset
     * @param monthlyInterest {@code WS-MONTHLY-INT} for this record, at scale 2, or {@code 0.00} when no
     *     interest was computed
     * @param tranId the {@code TRAN-ID} written, or the empty string when none was
     */
    public record RecordOutcome(long recordNumber,
                                String tranCatKeyImage,
                                boolean accountRewritten,
                                boolean interestComputed,
                                boolean feesComputed,
                                boolean transactionWritten,
                                BigDecimal monthlyInterest,
                                String tranId) {
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

    /**
     * The {@code DISCGRP} dataset, as the interest calculator uses it.
     */
    public interface DisclosureGroupAccess {
        String datasetName();

        DisclosureGroupFile open();
    }

    /**
     * One open {@code DISCGRP} file: its status, its keyed read and its close.
     */
    public interface DisclosureGroupFile extends AutoCloseable {
        String openStatus();

        DisclosureGroupRead readByKey(String keyImage);

        String closeFile();

        @Override
        void close();
    }

    public record DisclosureGroupRead(String status, Optional<DisclosureGroupRecord> record) {
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
         * @return the outcome
         */
        public static DisclosureGroupRead notFound() {
            return new DisclosureGroupRead(FileStatus.NOT_FOUND, Optional.empty());
        }

        /**
         * A failure the COBOL vocabulary has no specific code for.
         *
         * @param status the status to report; must be two characters and must not be {@link FileStatus#OK}
         * @return the outcome
         * @throws NullPointerException if {@code status} is {@code null}
         * @throws IllegalArgumentException if {@code status} is {@link FileStatus#OK} or not two characters
         */
        public static DisclosureGroupRead failed(String status) {
            return new DisclosureGroupRead(status, Optional.empty());
        }

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

        public DisclosureGroupRecord requireRecord() {
            return record.orElseThrow(() -> new IllegalStateException("This read reported file status '"
                    + status + "' and carries no record; only " + FileStatus.OK + " does."));
        }
    }



    /**
     * The reader, processor, writer, stream and listener the step is actually built from: a thin router
     * that owns no run of its own and forwards every callback to a {@link ChunkDelegate} belonging to the
     * step execution that is calling.
     */
    public static final class StepScopedChunkDelegate implements ItemStreamReader<TranCatBalRecord>,
            ItemProcessor<TranCatBalRecord, RecordOutcome>, ItemWriter<RecordOutcome>,
            StepExecutionListener {
        private final AccountInterestCalcJob job;

        private final ThreadLocal<Scope> executionScope = new ThreadLocal<>();

        StepScopedChunkDelegate(AccountInterestCalcJob job) {
            this.job = Objects.requireNonNull(job, "A job is required to scope its chunk delegate");
        }

        /**
         * One step execution and the delegate that is its address space.
         *
         * @param stepExecution the execution the delegate belongs to
         * @param delegate that execution's delegate
         */
        private record Scope(StepExecution stepExecution, ChunkDelegate delegate) {
        }

        /**
         * Builds this execution's delegate and hands it the launcher's parameters.
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
         * @param stepExecution the execution finishing; must not be {@code null}
         * @return {@code null} always
         */
        @Override
        public ExitStatus afterStep(StepExecution stepExecution) {
            Objects.requireNonNull(stepExecution, "A step execution is required to finish a delegate");
            return null;
        }

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
         * @return the scoped delegate, or empty when no step execution is in progress on this thread
         */
        public Optional<ChunkDelegate> scopedDelegate() {
            return Optional.ofNullable(executionScope.get()).map(Scope::delegate);
        }
    }

    /**
     * The step's reader, processor, writer and step listener, all backed by one
     * {@link InterestCalculationRun}.
     */
    public static final class ChunkDelegate implements ItemStreamReader<TranCatBalRecord>,
            ItemProcessor<TranCatBalRecord, RecordOutcome>, ItemWriter<RecordOutcome>,
            StepExecutionListener {
        private final AccountInterestCalcJob job;

        private String parmDate;

        private InterestCalculationRun run;

        private long transactionsWritten;

        private long accountsRewritten;

        ChunkDelegate(AccountInterestCalcJob job) {
            this.job = Objects.requireNonNull(job, "A job is required to build its chunk delegate");
            this.parmDate = job.declaredParmDate;
        }

        /**
         * Accepts the launcher's {@value BatchConfig#PARM_DATE_PARAMETER} when it supplied one.
         *
         * <p>The value is concatenated verbatim into every generated transaction identifier
         * ({@code app/cbl/CBACT04C.cbl:L476-L480}), so padding a short one or truncating a long one would
         * write a whole generation of subtly misplaced identifiers and report success.
         *
         * @param stepExecution the execution about to start
         * @throws IllegalArgumentException if a supplied value is not exactly
         *     {@link BatchConfig#PARM_DATE_WIDTH} characters
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
         *     {@code CBACT04C} has no restart semantics, and inventing some would let a restarted step resume
         *     mid-account and post interest twice
         * @throws IllegalStateException if a run is already open on this delegate
         * @throws AbendException if any open reports a status other than {@code '00'}
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

            opening.sysout().write(START_OF_EXECUTION);

            opening.openFiles();
        }

        /**
         * {@code app/cbl/CBACT04C.cbl:190} - {@code PERFORM 1000-TCATBALF-GET-NEXT}.
         *
         * @return the next category balance record, or {@code null} at end of file
         * @throws IllegalStateException if no run is open
         * @throws AbendException if the read reports a status that is neither {@code '00'} nor {@code '10'}
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
         * @throws NullPointerException if {@code item} is {@code null}
         * @throws IllegalStateException if no run is open
         * @throws AbendException if any file operation this record triggers reports a fatal status
         */
        @Override
        public RecordOutcome process(TranCatBalRecord item) {
            return requireRun().processRecord(item);
        }

        /**
         * The writer stage: bookkeeping, and no dataset I/O.
         *
         * @param chunk the outcomes of this chunk's records - exactly one, since the commit interval is
         *     {@value #CHUNK_SIZE}
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
         * {@code app/cbl/CBACT04C.cbl:224-230} - the five closes and the closing banner, and only when the
         * loop ended at end of file.
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
                    finishing.closeFiles();

                    finishing.sysout().write(END_OF_EXECUTION);
                }
            } finally {
                if (normalEnd) {
                    finishing.release();
                } else {
                    finishing.releaseAbnormally();
                }
            }
        }

        private InterestCalculationRun requireRun() {
            if (run == null) {
                throw new IllegalStateException("No run is open on this delegate. The ItemStream must be "
                        + "opened first, which is what performs the five OPEN paragraphs of "
                        + "app/cbl/CBACT04C.cbl:182-186; reading or processing before that is reading a "
                        + "file that is not open.");
            }
            return run;
        }

        InterestCalculationRun run() {
            return run;
        }

        /**
         * The {@code PARM-DATE} this execution will use.
         *
         * @return exactly {@link #PARM_DATE_WIDTH} characters; never {@code null}
         */
        public String parmDate() {
            return parmDate;
        }

        public long transactionsWritten() {
            return transactionsWritten;
        }

        public long accountsRewritten() {
            return accountsRewritten;
        }
    }

}
