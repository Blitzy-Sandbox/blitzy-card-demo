package com.vsergeychik.carddemo.statement;

import com.vsergeychik.carddemo.account.model.AccountRecord;
import com.vsergeychik.carddemo.card.model.CardXrefRecord;
import com.vsergeychik.carddemo.common.AbendException;
import com.vsergeychik.carddemo.common.CobolDecimal;
import com.vsergeychik.carddemo.common.FileStatus;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.config.BatchConfig;
import com.vsergeychik.carddemo.config.BatchConfig.StepContract;
import com.vsergeychik.carddemo.config.CobolCharsetConfig;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBinding;
import com.vsergeychik.carddemo.statement.StatementGenerationJobB.Response;
import com.vsergeychik.carddemo.statement.StatementGenerationJobB.Session;
import com.vsergeychik.carddemo.statement.StatementHtmlWriter.AddressField;
import com.vsergeychik.carddemo.statement.StatementHtmlWriter.BasicDetail;
import com.vsergeychik.carddemo.statement.StatementHtmlWriter.HtmlFixedLine;
import com.vsergeychik.carddemo.statement.StatementHtmlWriter.HtmlStatementFile;
import com.vsergeychik.carddemo.statement.StatementHtmlWriter.TransactionField;
import com.vsergeychik.carddemo.statement.StatementTextWriter.StatementFile;
import com.vsergeychik.carddemo.statement.StatementTextWriter.StatementLine;
import com.vsergeychik.carddemo.statement.StatementTextWriter.StatementSlot;
import com.vsergeychik.carddemo.statement.model.Stm03CustomerRecord;
import com.vsergeychik.carddemo.statement.model.TrnxRecord;

import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.job.flow.FlowExecutionStatus;
import org.springframework.batch.core.job.flow.JobExecutionDecider;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * {@code CBSTM03A} - the CardDemo statement report driver, translated field for field and statement
 * for statement from {@code app/cbl/CBSTM03A.CBL} (924 lines, upper-case {@code .CBL}), published as
 * the Spring Batch job that {@code app/jcl/CREASTMT.JCL} runs.
 *
 * <p>The program prints one account statement per cross-reference record, in two formats: an 80-byte
 * plain-text statement on {@code STMTFILE} and a 100-byte HTML statement on {@code HTMLFILE}. It is
 * the only one of the twenty-eight programs whose {@code GO TO}s form implicit loops, and the only one
 * that dispatches through {@code ALTER ... TO PROCEED TO}, so the control-flow restructuring is this
 * file's central concern.
 *
 * <h2>What this class owns, and what it does not</h2>
 *
 * <p>This class owns <strong>sequence</strong>. The two writer collaborators own <strong>bytes</strong>:
 * {@link StatementTextWriter} holds {@code 01 STATEMENT-LINES} and every edit mask, and
 * {@link StatementHtmlWriter} holds every {@code HTML-LINES} literal and every {@code STRING} template.
 * {@link StatementGenerationJobB} owns <strong>all four inputs</strong> - {@code TRNXFILE},
 * {@code XREFFILE}, {@code CUSTFILE} and {@code ACCTFILE} are declared in {@code CBSTM03B.CBL}, not
 * here, which is exactly why {@code CBSTM03A} calls it thirteen times and declares only its own two
 * output files ({@code app/cbl/CBSTM03A.CBL:L39-L47}).
 *
 * <p>The write order is therefore the parity-critical asset of this file, and it is expressed as ordered
 * constant lists and straight-line call sequences so it can be asserted directly rather than inferred.
 *
 * <h2>The {@code ALTER}/{@code GO TO} state machine (gate G32)</h2>
 *
 * <p>{@code 0000-START} ({@code L296-L314}) evaluates {@code WS-FL-DD} and, for four of its six arms,
 * rewrites the target of the bare {@code GO TO} inside {@code 8100-FILE-OPEN} ({@code L726-L728}) before
 * branching to it - a self-modifying computed dispatch. Each opened file then moves the next DD name
 * into {@code WS-FL-DD} and branches back to {@code 0000-START}, so the {@code GO TO}s form a loop.
 *
 * <p>It is restructured here as {@link #runFileControl} - an explicit {@code while} loop over the state
 * variable, with the six {@code EVALUATE} arms preserved in source order and {@code WHEN OTHER} last
 * (gate G30). The loop form is kept deliberately rather than collapsed into a straight-line sequence:
 * collapsing it would make the {@code WHEN OTHER} arm unreachable, and the resolved iteration order
 * would become an assumption instead of something a test can pin. The resolved order is
 * {@code TRNXFILE} open and first read, then the whole two-dimensional table load, then the
 * {@code XREFFILE}, {@code CUSTFILE} and {@code ACCTFILE} opens, then {@code 1000-MAINLINE}; a test
 * records the state and operation sequence and asserts exactly that.
 *
 * <h2>Three source defects that are reproduced, not corrected (practice B4)</h2>
 *
 * <ol>
 *   <li><strong>{@code app/jcl/CREASTMT.JCL:L90} is corrupted.</strong> It reads
 *       {@code //         SPACE=(CYL,(1,1),RLSE), 00,RECFM=FB), ATA.VSAM.KSDS} - a copy/paste artefact
 *       that overwrote part of the line. It is not parsed and no meaning is recovered from it;
 *       {@code L89} is the authoritative {@code DCB} for {@code STMTFILE}
 *       ({@code LRECL=80,BLKSIZE=8000,RECFM=FB}).</li>
 *   <li><strong>{@code HTMLFILE} is declared twice with different widths.</strong> {@code L69}, in the
 *       {@code STEP030} pre-delete, says {@code LRECL=80}; {@code L94}, in the {@code STEP040} creating
 *       step, says {@code LRECL=100}. <strong>The creating step wins: 100 bytes</strong> (risk R-G, gate
 *       G20), which is also what {@code 01 FD-HTMLFILE-REC PIC X(100)} at {@code L47} declares.</li>
 *   <li><strong>The {@code STEP010} {@code OUTREC} copies 50 trailing bytes where 52 would be needed.</strong>
 *       {@code OUTREC FIELDS=(1:263,16,17:1,262,279:279,50)} carries input 279-328 to output 279-328,
 *       which is the whole 26-byte {@code TRAN-ORIG-TS} but only the first <strong>24</strong> bytes of
 *       the 26-byte {@code TRAN-PROC-TS}. A derived {@code TRNX} record therefore legitimately holds a
 *       24-character processing timestamp followed by two spaces, and a blank {@code FILLER X(20)}. The
 *       50 is <strong>never</strong> widened to 52 - see {@link #applyOutrec(String)}.</li>
 * </ol>
 *
 * <h2>Four preservations that look like tidying opportunities and are not (practice B5)</h2>
 *
 * <ol>
 *   <li>{@code L317-L318}: {@code PERFORM UNTIL END-OF-FILE = 'Y'} immediately contains
 *       {@code IF END-OF-FILE = 'N'}. Both tests are kept - they are two distinct branches against two
 *       different literals.</li>
 *   <li>{@code L324}: {@code MOVE 1 TO CR-JMP} is overwritten by {@code PERFORM VARYING CR-JMP FROM 1}
 *       at {@code L417} before it is ever read. It is kept.</li>
 *   <li>{@code L364}: {@code MOVE WS-M03B-FLDT TO CARD-XREF-RECORD} sits <em>after</em> the
 *       {@code EVALUATE} and runs <strong>unconditionally</strong>, so on the end-of-file arm the
 *       cross-reference record is overwritten with the spaces the caller had just moved into the record
 *       area. That ordering is preserved exactly.</li>
 *   <li>{@code L313-L314}: the {@code WHEN OTHER} arm of {@code 0000-START} is unreachable in the
 *       observed sequence but is a real branch. It is kept and it is covered.</li>
 * </ol>
 *
 * <h2>The z/OS control-block walk has no Java equivalent (practice B12)</h2>
 *
 * <p>{@code L266-L291} walks the {@code PSA}, {@code TCB} and {@code TIOT} control blocks through
 * {@code SET ADDRESS OF} over {@code LINKAGE SECTION} overlays and displays the job name, the step name
 * and every allocated DD name with its unit-control-block state. No JVM can address those control
 * blocks. The <em>observable</em> part - five {@code DISPLAY} forms - is reproduced byte for byte from a
 * substitute source behind the replaceable {@link TiotSource} seam: the job and step names come from the
 * JCL job name and the validated {@code STEP040} step contract, and the DD-name list and its
 * valid/null classification come from the resolved {@code carddemo.datasets} bindings for that step. The
 * output is <strong>not</strong> dropped, because it is behaviour.
 *
 * <p>One byte-level trap deserves naming, because normalising it is the obvious mistake and it was
 * verified with {@code cat -A}: the two "null UCB" literals are <strong>different strings</strong>.
 * Inside the loop ({@code L281}) it is {@code ' --  null UCB'}, with two spaces after the dashes; after
 * the loop ({@code L290}) it is {@code ' -- null  UCB'}, with two spaces after {@code null}. The two
 * "valid UCB" literals are identical. Both forms are reproduced.
 *
 * <h2>Statelessness and testability</h2>
 *
 * <p>A {@code @Configuration} class is a singleton, so <strong>none</strong> of
 * {@code CBSTM03A}'s working storage lives on it. The 51x10 table, the 51 counters,
 * {@code CR-CNT}/{@code TR-CNT}/{@code CR-JMP}/{@code TR-JMP}, {@code WS-TOTAL-AMT},
 * {@code WS-TRN-AMT}, {@code WS-SAVE-CARD}, {@code END-OF-FILE}, {@code WS-FL-DD}, the four current
 * record areas and the three file handles are all fields of {@link WorkingStorage}, created inside the
 * invocation and discarded when it returns. There is no static mutable state anywhere (gate G53) and
 * every collaborator arrives through the constructor.
 *
 * <p>Every step body is a plain, directly callable method, so the twenty parity cases and every branch
 * test drive this program with no {@code JobLauncher}, no application context and no HTTP layer in the
 * path (practice B10, gate G51).
 *
 * <h2>Numeric parity</h2>
 *
 * <p>{@code WS-TOTAL-AMT PIC S9(9)V99 COMP-3} and {@code TRNX-AMT PIC S9(09)V99} are both scale 2, so
 * the accumulator is a {@link BigDecimal} at scale exactly 2 and every store routes through
 * {@link CobolDecimal}, whose only rounding mode is {@link java.math.RoundingMode#DOWN} - the keyword
 * {@code ROUNDED} appears zero times in all twenty-eight programs (gates G22, G23, G24). No binary
 * floating-point type is declared, cast to or returned anywhere in this file. {@code COMP-3} here is
 * working storage only; no copybook in the estate declares packed decimal, so no nibble unpacking exists
 * anywhere.
 *
 * @see StatementGenerationJobB the data-access collaborator that owns all four input files
 * @see StatementTextWriter the 80-byte plain-text statement writer
 * @see StatementHtmlWriter the 100-byte HTML statement writer
 */
@Configuration(StatementGenerationJobA.CONFIGURATION_BEAN_NAME)
public class StatementGenerationJobA {

    // =================================================================================================
    // Identity. Every name is either transcribed from a source artefact or derived from the class name;
    // none is invented at a call site.
    // =================================================================================================

    /**
     * The explicit bean name of this configuration class.
     *
     * <p>Required, and not cosmetic. The {@link Bean} method below is named {@value #JOB_NAME}, which is
     * the decapitalised form of this class's own name, so the default configuration-class bean name and
     * the job bean name would collide and the context would refuse to start. Naming the configuration
     * explicitly leaves {@value #JOB_NAME} free for the {@link Job}, which is what a launcher looks the
     * job up by.
     */
    public static final String CONFIGURATION_BEAN_NAME = "statementGenerationJobAConfiguration";

    /** The COBOL {@code PROGRAM-ID} this class is the translation of: {@code CBSTM03A.CBL:L2}. */
    public static final String PROGRAM_ID = "CBSTM03A";

    /** The {@code carddemo.jobs} key under which this job's contract is configured. */
    public static final String JOB_KEY = "statement-generation-job-a";

    /** The Spring Batch job name, which is also this job's identity in the batch metadata. */
    public static final String JOB_NAME = "statementGenerationJobA";

    /**
     * The JCL job name, {@code app/jcl/CREASTMT.JCL:L1} ({@code //CREASTMT JOB 'Create Statement'}).
     *
     * <p>This is what {@code TIOTNJOB PIC X(08)} holds on the mainframe, and it is exactly eight
     * characters, so it renders into that field without padding. It is the job name the prologue
     * displays - not the Spring Batch job name, which is a different identifier belonging to a different
     * system.
     */
    public static final String JCL_JOB_NAME = "CREASTMT";

    // -------------------------------------------------------------------------------------------------
    // The five steps of app/jcl/CREASTMT.JCL, in declaration order. Exactly three carry COND=(0,NE).
    // -------------------------------------------------------------------------------------------------

    /** {@code app/jcl/CREASTMT.JCL:L22} - {@code EXEC PGM=IDCAMS}, <strong>no</strong> {@code COND}. */
    public static final String STEP_DELDEF01 = "DELDEF01";

    /** {@code app/jcl/CREASTMT.JCL:L44} - {@code EXEC PGM=SORT}, <strong>no</strong> {@code COND}. */
    public static final String STEP_010 = "STEP010";

    /** {@code app/jcl/CREASTMT.JCL:L56} - {@code EXEC PGM=IDCAMS,COND=(0,NE)}. */
    public static final String STEP_020 = "STEP020";

    /** {@code app/jcl/CREASTMT.JCL:L66} - {@code EXEC PGM=IEFBR14,COND=(0,NE)}. */
    public static final String STEP_030 = "STEP030";

    /** {@code app/jcl/CREASTMT.JCL:L79} - {@code EXEC PGM=CBSTM03A,COND=(0,NE)}, the program itself. */
    public static final String STEP_040 = "STEP040";

    /**
     * The five step names in JCL declaration order.
     *
     * <p>Published so a test can assert the job's flow against the JCL rather than against a
     * re-transcription of it.
     */
    public static final List<String> STEP_NAMES =
            List.of(STEP_DELDEF01, STEP_010, STEP_020, STEP_030, STEP_040);

    /**
     * The step names that {@code COND=(0,NE)} gates - {@value #STEP_020}, {@value #STEP_030} and
     * {@value #STEP_040}, and no others.
     *
     * <p><strong>Exactly three of five.</strong> {@value #STEP_DELDEF01} is the first step and has
     * nothing before it to test, and {@value #STEP_010} carries no {@code COND} at all. Gating four
     * steps, or two, is the easy mistake; the count is asserted against the configured contracts in the
     * constructor and against this list by test.
     */
    public static final List<String> GATED_STEP_NAMES = List.of(STEP_020, STEP_030, STEP_040);

    // -------------------------------------------------------------------------------------------------
    // DD binding keys. Every dataset is addressed by key through carddemo.datasets; no mainframe dataset
    // name appears anywhere in this file (gate G46).
    // -------------------------------------------------------------------------------------------------

    /** {@code //TRNXFILE DD} ({@code CREASTMT.JCL:L83}) - the statement extract KSDS, read by the subroutine. */
    public static final String TRNXFILE_DD = StatementGenerationJobB.TRNXFILE_DD;

    /** {@code //XREFFILE DD} ({@code CREASTMT.JCL:L84}) - the card cross-reference, browsed front to back. */
    public static final String XREFFILE_DD = StatementGenerationJobB.XREFFILE_DD;

    /** {@code //ACCTFILE DD} ({@code CREASTMT.JCL:L85}) - the account master, read by key. */
    public static final String ACCTFILE_DD = StatementGenerationJobB.ACCTFILE_DD;

    /** {@code //CUSTFILE DD} ({@code CREASTMT.JCL:L86}) - the customer master, read by key. */
    public static final String CUSTFILE_DD = StatementGenerationJobB.CUSTFILE_DD;

    /** {@code //STMTFILE DD} ({@code CREASTMT.JCL:L87-L91}) - the 80-byte plain-text statement output. */
    public static final String STMTFILE_DD = StatementTextWriter.DD_NAME;

    /** {@code //HTMLFILE DD} ({@code CREASTMT.JCL:L92-L96}) - the 100-byte HTML statement output. */
    public static final String HTMLFILE_DD = StatementHtmlWriter.HTMLFILE_DD_NAME;

    /** {@code //SORTIN DD} ({@code CREASTMT.JCL:L45}) - the transaction master {@value #STEP_010} reads. */
    public static final String SORTIN_DD = "SORTIN";

    /** {@code //SORTOUT DD} ({@code CREASTMT.JCL:L48-L51}) - the intermediate sequential copy. */
    public static final String SORTOUT_DD = "SORTOUT";

    /** {@code //INFILE DD} ({@code CREASTMT.JCL:L58}) - the same intermediate file, as {@value #STEP_020} reads it. */
    public static final String INFILE_DD = "INFILE";

    /** {@code //OUTFILE DD} ({@code CREASTMT.JCL:L59}) - the work KSDS {@value #STEP_020} loads. */
    public static final String OUTFILE_DD = "OUTFILE";

    /**
     * The six data DD names {@code STEP040} allocates, in {@code app/jcl/CREASTMT.JCL:L83-L96}
     * declaration order.
     *
     * <p>This is the substitute for the {@code TIOT} chain the COBOL walks: the entries it would find,
     * in the order it would find them. {@code STEPLIB}, {@code SYSPRINT} and {@code SYSOUT} ({@code L80-L82})
     * are not among them - they are not data allocations and are not configured as datasets.
     */
    public static final List<String> STEP_040_DD_NAMES = List.of(
            TRNXFILE_DD, XREFFILE_DD, ACCTFILE_DD, CUSTFILE_DD, STMTFILE_DD, HTMLFILE_DD);

    // -------------------------------------------------------------------------------------------------
    // WS-FL-DD - the state variable of the ALTER/GO TO dispatch. app/cbl/CBSTM03A.CBL:L67, L298-L314.
    // -------------------------------------------------------------------------------------------------

    /**
     * {@code WHEN 'TRNXFILE'} ({@code L299}) and the declared initial value of
     * {@code WS-FL-DD PIC X(8) VALUE 'TRNXFILE'} ({@code L67}) - so this is where the dispatch starts.
     */
    public static final String STATE_TRNXFILE = TRNXFILE_DD;

    /** {@code WHEN 'XREFFILE'} ({@code L302}), reached from {@code 8599-EXIT} ({@code L851}). */
    public static final String STATE_XREFFILE = XREFFILE_DD;

    /** {@code WHEN 'CUSTFILE'} ({@code L305}), reached from {@code 8200-XREFFILE-OPEN} ({@code L779}). */
    public static final String STATE_CUSTFILE = CUSTFILE_DD;

    /** {@code WHEN 'ACCTFILE'} ({@code L308}), reached from {@code 8300-CUSTFILE-OPEN} ({@code L797}). */
    public static final String STATE_ACCTFILE = ACCTFILE_DD;

    /**
     * {@code WHEN 'READTRNX'} ({@code L311}), reached from {@code 8100-TRNXFILE-OPEN} ({@code L760}).
     *
     * <p>The only state that is not a DD name: it selects {@code 8500-READTRNX-READ}, the table-load
     * loop, rather than a file open.
     */
    public static final String STATE_READTRNX = "READTRNX";

    /**
     * The five named {@code EVALUATE WS-FL-DD} arms in <strong>source order</strong>, which is the order
     * COBOL compares them in and the order {@link #runFileControl} tests them in (gate G30).
     *
     * <p>The sixth arm, {@code WHEN OTHER}, is deliberately absent from this list: it is not a value but
     * the default, it is evaluated last, and it branches straight to {@code 9999-GOBACK}.
     */
    public static final List<String> FILE_CONTROL_STATES = List.of(
            STATE_TRNXFILE, STATE_XREFFILE, STATE_CUSTFILE, STATE_ACCTFILE, STATE_READTRNX);

    // -------------------------------------------------------------------------------------------------
    // WS-TRNX-TABLE geometry. app/cbl/CBSTM03A.CBL:L225-L233.
    // -------------------------------------------------------------------------------------------------

    /**
     * {@code 05 WS-CARD-TBL OCCURS 51 TIMES} ({@code L226}) - the table holds at most fifty-one cards.
     *
     * <p>A fixed capacity, inherited from the source and <strong>not</strong> extended. The COBOL neither
     * tests {@code CR-CNT} against it nor handles overflow, so no such handling is invented here; the
     * limit is documented as the constraint it is.
     */
    public static final int CARD_TABLE_OCCURS = 51;

    /** {@code 10 WS-TRAN-TBL OCCURS 10 TIMES} ({@code L228}) - at most ten transactions per card. */
    public static final int TRAN_TABLE_OCCURS = 10;

    /** {@code 10 WS-CARD-NUM PIC X(16)} ({@code L227}). */
    public static final int WS_CARD_NUM_LENGTH = TrnxRecord.TRNX_CARD_NUM_LENGTH;

    /** {@code 15 WS-TRAN-NUM PIC X(16)} ({@code L229}). */
    public static final int WS_TRAN_NUM_LENGTH = TrnxRecord.TRNX_ID_LENGTH;

    /**
     * {@code 15 WS-TRAN-REST PIC X(318)} ({@code L230}) - the whole {@code TRNX-REST} span, moved in and
     * out wholesale and never decomposed inside the table.
     */
    public static final int WS_TRAN_REST_LENGTH = TrnxRecord.TRNX_REST_LENGTH;

    /** {@code 05 WS-SAVE-CARD VALUE SPACES PIC X(16)} ({@code L69}). */
    public static final int WS_SAVE_CARD_LENGTH = TrnxRecord.TRNX_CARD_NUM_LENGTH;

    // -------------------------------------------------------------------------------------------------
    // END-OF-FILE PIC X(01) VALUE 'N'. app/cbl/CBSTM03A.CBL:L70.
    // -------------------------------------------------------------------------------------------------

    /**
     * {@code 'N'} - the declared initial value, and the literal the two guards inside
     * {@code 1000-MAINLINE} test against ({@code L318}, {@code L320}).
     */
    public static final String END_OF_FILE_NO = "N";

    /**
     * {@code 'Y'} - what {@code 1000-XREFFILE-GET-NEXT} moves in at end of file ({@code L357}) and the
     * literal the outer {@code PERFORM UNTIL} tests against ({@code L317}).
     *
     * <p>Held as a one-character string rather than a boolean precisely because the COBOL tests it
     * against two <em>different</em> literals in adjacent places, and a boolean could not tell those two
     * tests apart.
     */
    public static final String END_OF_FILE_YES = "Y";

    // -------------------------------------------------------------------------------------------------
    // FILE STATUS acceptance. Three different shapes in one program - see the class documentation.
    // -------------------------------------------------------------------------------------------------

    /**
     * {@code '04'} - accepted alongside {@code '00'} by every {@code OPEN} guard
     * ({@code L736}, {@code L771}, {@code L789}, {@code L807}), every {@code CLOSE} guard
     * ({@code L862}, {@code L879}, {@code L895}, {@code L911}) and, notably, the <strong>first</strong>
     * {@code TRNXFILE} read ({@code L748}).
     *
     * <p>It is a record-length-conflict status. The loop read at {@code L837} does <em>not</em> accept it:
     * that site uses a three-way {@code EVALUATE} whose {@code WHEN OTHER} arm abends. Three different
     * status-checking shapes in one program, and they are not interchangeable.
     */
    public static final String STATUS_RECORD_LENGTH_CONFLICT = "04";

    // -------------------------------------------------------------------------------------------------
    // Every DISPLAY literal, byte-exact. app/cbl/CBSTM03A.CBL:L270-L290 and the ten guard sites.
    // -------------------------------------------------------------------------------------------------

    /** {@code DISPLAY 'Running JCL : ' TIOTNJOB ' Step ' TIOTJSTP} - the leading literal, {@code L270}. */
    public static final String RUNNING_JCL_PREFIX = "Running JCL : ";

    /** {@code DISPLAY 'Running JCL : ' TIOTNJOB ' Step ' TIOTJSTP} - the middle literal, {@code L270}. */
    public static final String RUNNING_JCL_STEP_LABEL = " Step ";

    /**
     * {@code DISPLAY 'DD Names from TIOT: '} ({@code L275}).
     *
     * <p>The <strong>trailing space is inside the literal</strong> and is part of the emitted line.
     */
    public static final String DD_NAMES_FROM_TIOT = "DD Names from TIOT: ";

    /** {@code DISPLAY ': ' TIOCDDNM ...} - the leading literal of every TIOT entry line. */
    public static final String TIOT_ENTRY_PREFIX = ": ";

    /**
     * {@code ' -- valid UCB'} - {@code L279} inside the loop and {@code L288} after it.
     *
     * <p>The two "valid" literals <em>are</em> identical, which is why one constant serves both. The two
     * "null" literals are not; see below.
     */
    public static final String VALID_UCB_SUFFIX = " -- valid UCB";

    /**
     * {@code ' --  null UCB'} - {@code L281}, <strong>inside</strong> the loop: two spaces after the
     * dashes and one before {@code UCB}.
     *
     * <p>Verified with {@code cat -A}. Normalising this to match {@link #NULL_UCB_SUFFIX_AFTER_LOOP}
     * would change emitted bytes, so the two are kept apart.
     */
    public static final String NULL_UCB_SUFFIX_IN_LOOP = " --  null UCB";

    /**
     * {@code ' -- null  UCB'} - {@code L290}, <strong>after</strong> the loop: one space after the dashes
     * and two before {@code UCB}.
     *
     * <p>The mirror image of {@link #NULL_UCB_SUFFIX_IN_LOOP}, and a genuinely different string.
     */
    public static final String NULL_UCB_SUFFIX_AFTER_LOOP = " -- null  UCB";

    /** {@code TIOTNJOB PIC X(08)}, {@code TIOTJSTP PIC X(08)} and {@code TIOCDDNM PIC X(08)} - all eight. */
    public static final int TIOT_NAME_WIDTH = 8;

    /**
     * {@code LENGTH OF TIOT-BLOCK} = 24, added to {@code BUMP-TIOT} once at {@code L272}.
     *
     * <p>{@code TIOTNJOB X(08)} + {@code TIOTJSTP X(08)} + {@code TIOTPSTP X(08)}
     * ({@code L247-L250}).
     */
    public static final int TIOT_BLOCK_LENGTH = 3 * TIOT_NAME_WIDTH;

    /**
     * {@code LENGTH OF TIOT-SEG} = 20, added to {@code BUMP-TIOT} once per entry at {@code L283}.
     *
     * <p>{@code TIO-LEN X(01)} + {@code FILLER X(03)} + {@code TIOCDDNM X(08)} + {@code FILLER X(05)} +
     * {@code UCB-ADDR X(03)} ({@code L252-L257}).
     */
    public static final int TIOT_SEG_LENGTH = 20;

    /** {@code DISPLAY 'ERROR OPENING TRNXFILE'} ({@code L739}). */
    public static final String ERROR_OPENING_TRNXFILE = "ERROR OPENING TRNXFILE";

    /** {@code DISPLAY 'ERROR OPENING XREFFILE'} ({@code L774}). */
    public static final String ERROR_OPENING_XREFFILE = "ERROR OPENING XREFFILE";

    /** {@code DISPLAY 'ERROR OPENING CUSTFILE'} ({@code L792}). */
    public static final String ERROR_OPENING_CUSTFILE = "ERROR OPENING CUSTFILE";

    /** {@code DISPLAY 'ERROR OPENING ACCTFILE'} ({@code L810}). */
    public static final String ERROR_OPENING_ACCTFILE = "ERROR OPENING ACCTFILE";

    /** {@code DISPLAY 'ERROR READING TRNXFILE'} ({@code L751}, first read; {@code L844}, loop read). */
    public static final String ERROR_READING_TRNXFILE = "ERROR READING TRNXFILE";

    /** {@code DISPLAY 'ERROR READING XREFFILE'} ({@code L359}). */
    public static final String ERROR_READING_XREFFILE = "ERROR READING XREFFILE";

    /** {@code DISPLAY 'ERROR READING CUSTFILE'} ({@code L383}). */
    public static final String ERROR_READING_CUSTFILE = "ERROR READING CUSTFILE";

    /** {@code DISPLAY 'ERROR READING ACCTFILE'} ({@code L407}). */
    public static final String ERROR_READING_ACCTFILE = "ERROR READING ACCTFILE";

    /** {@code DISPLAY 'ERROR CLOSING TRNXFILE'} ({@code L865}). */
    public static final String ERROR_CLOSING_TRNXFILE = "ERROR CLOSING TRNXFILE";

    /** {@code DISPLAY 'ERROR CLOSING XREFFILE'} ({@code L882}). */
    public static final String ERROR_CLOSING_XREFFILE = "ERROR CLOSING XREFFILE";

    /** {@code DISPLAY 'ERROR CLOSING CUSTFILE'} ({@code L898}). */
    public static final String ERROR_CLOSING_CUSTFILE = "ERROR CLOSING CUSTFILE";

    /** {@code DISPLAY 'ERROR CLOSING ACCTFILE'} ({@code L914}). */
    public static final String ERROR_CLOSING_ACCTFILE = "ERROR CLOSING ACCTFILE";

    /**
     * {@code DISPLAY 'RETURN CODE: ' WS-M03B-RC} - the second line of all ten guard sites.
     *
     * <p>{@code WS-M03B-RC} is {@code PIC X(02)} ({@code L80}), so the emitted line is this literal
     * followed by exactly two characters.
     */
    public static final String RETURN_CODE_PREFIX = "RETURN CODE: ";

    /**
     * {@code DISPLAY 'ABENDING PROGRAM'} ({@code L922}), immediately before {@code CALL 'CEE3ABD'}.
     *
     * <p>Taken from {@link AbendException#ABEND_DISPLAY_TEXT} so the module has one spelling of it.
     */
    public static final String ABENDING_PROGRAM = AbendException.ABEND_DISPLAY_TEXT;

    /**
     * The return code this program's abend carries.
     *
     * <p>Neither {@code CBSTM03A} nor {@code CBSTM03B} ever executes {@code MOVE ... TO RETURN-CODE}, so
     * there is no source-declared value to transcribe. {@code CALL 'CEE3ABD'} terminates the run unit
     * abnormally, which is a failure and not a warning, so the module's
     * {@link AbendException#RETURN_CODE_ASSUMED_FAILURE} is used - the same code its siblings use for an
     * abend with no explicit {@code MOVE}. Normal completion is {@code GOBACK}, which is return code
     * zero, and nothing here sets one.
     */
    public static final int ABEND_RETURN_CODE = AbendException.RETURN_CODE_ASSUMED_FAILURE;

    // -------------------------------------------------------------------------------------------------
    // STEP010 - SORT FIELDS and OUTREC FIELDS. app/jcl/CREASTMT.JCL:L53-L54.
    //
    // Every position below is a JCL position: 1-based, counting bytes from the start of the record. The
    // 1-based-to-0-based conversion happens once, in applyOutrec, and nowhere else.
    //
    // The input layout is app/cpy/CVTRA05Y.cpy (TRAN-RECORD, 350 bytes): TRAN-ID 1-16, TRAN-TYPE-CD
    // 17-18, TRAN-CAT-CD 19-22, TRAN-SOURCE 23-32, TRAN-DESC 33-132, TRAN-AMT 133-143,
    // TRAN-MERCHANT-ID 144-152, TRAN-MERCHANT-NAME 153-202, TRAN-MERCHANT-CITY 203-252,
    // TRAN-MERCHANT-ZIP 253-262, TRAN-CARD-NUM 263-278, TRAN-ORIG-TS 279-304, TRAN-PROC-TS 305-330,
    // FILLER 331-350.
    // -------------------------------------------------------------------------------------------------

    /** The width of both the sending {@code TRAN-RECORD} and the derived {@code TRNX-RECORD}: 350 bytes. */
    public static final int SORT_RECORD_LENGTH = TrnxRecord.RECORD_LENGTH;

    /**
     * {@code SORT FIELDS=(263,16,...)} - the major key: {@code TRAN-CARD-NUM} at JCL position 263,
     * sixteen bytes, character, ascending.
     */
    public static final int SORT_MAJOR_KEY_POSITION = 263;

    /** The major key's width, {@code CH,A} at {@code CREASTMT.JCL:L53}. */
    public static final int SORT_MAJOR_KEY_LENGTH = TrnxRecord.TRNX_CARD_NUM_LENGTH;

    /**
     * {@code SORT FIELDS=(...,1,16,CH,A)} - the minor key: {@code TRAN-ID} at JCL position 1, sixteen
     * bytes, character, ascending.
     */
    public static final int SORT_MINOR_KEY_POSITION = 1;

    /** The minor key's width. */
    public static final int SORT_MINOR_KEY_LENGTH = TrnxRecord.TRNX_ID_LENGTH;

    /** {@code OUTREC FIELDS=(1:263,16,...)} - the first triple's output position. */
    public static final int OUTREC_CARD_NUM_OUTPUT_POSITION = 1;

    /** {@code OUTREC FIELDS=(...,17:1,262,...)} - the second triple's output position. */
    public static final int OUTREC_BODY_OUTPUT_POSITION = 17;

    /** {@code OUTREC FIELDS=(...,17:1,262,...)} - the second triple's input position. */
    public static final int OUTREC_BODY_INPUT_POSITION = 1;

    /**
     * {@code OUTREC FIELDS=(...,17:1,262,...)} - the second triple's width: bytes 1-262 of the input,
     * which is everything from {@code TRAN-ID} through {@code TRAN-MERCHANT-ZIP}.
     */
    public static final int OUTREC_BODY_LENGTH = 262;

    /** {@code OUTREC FIELDS=(...,279:279,50)} - the third triple's position, the same in and out. */
    public static final int OUTREC_TAIL_POSITION = 279;

    /**
     * {@code OUTREC FIELDS=(...,279:279,50)} - the third triple's width: <strong>50</strong>, and not 52.
     *
     * <p>Fifty bytes carry input 279-328 to output 279-328. That is the whole 26-byte
     * {@code TRAN-ORIG-TS} (279-304) plus only the <strong>first 24</strong> bytes of the 26-byte
     * {@code TRAN-PROC-TS} (305-330). Output 329-350 - the last two bytes of {@code TRNX-PROC-TS} and the
     * whole {@code FILLER X(20)} - is unaddressed and is left blank.
     *
     * <p>Fifty-two would have round-tripped both timestamps. It says fifty, so a derived record
     * legitimately carries a 24-character processing timestamp with two trailing spaces. That is the
     * source's behaviour and it is reproduced, never corrected (practice B4).
     * {@link TrnxRecord#TRNX_PROC_TS_SORT_DERIVED_LENGTH} records the same fact on the model type.
     */
    public static final int OUTREC_TAIL_LENGTH = 50;

    // -------------------------------------------------------------------------------------------------
    // DELDEF01 - the DEFINE CLUSTER contract. app/jcl/CREASTMT.JCL:L29-L39.
    //
    // DEFINE CLUSTER is dataset DEFINITION. Gate G44 forbids this migration from emitting any
    // data-definition statement, schema migration or generated table definition, so the cluster's
    // geometry is not created here - it is ASSERTED against what carddemo.datasets declares. Where the
    // mainframe defines the cluster, the Java module requires it to have been declared, and says so if
    // it has not.
    // -------------------------------------------------------------------------------------------------

    /**
     * {@code KEYS(32 0)} ({@code CREASTMT.JCL:L30}) - a 32-byte key at offset 0.
     *
     * <p>Independent corroboration of {@code app/cpy/COSTM01.CPY}: {@code TRNX-KEY} is
     * {@code TRNX-CARD-NUM X(16)} followed by {@code TRNX-ID X(16)}, which is 32 bytes starting at the
     * record's first byte. The value is taken from {@link TrnxRecord#TRNX_KEY_LENGTH} so the copybook
     * remains the single source of it.
     */
    public static final int WORK_KSDS_KEY_LENGTH = TrnxRecord.TRNX_KEY_LENGTH;

    /** {@code KEYS(32 0)} - the second operand is the key's offset within the record: zero. */
    public static final int WORK_KSDS_KEY_OFFSET = TrnxRecord.TRNX_KEY_OFFSET;

    /**
     * {@code RECORDSIZE(350 350)} ({@code CREASTMT.JCL:L32}) - average and maximum both 350, so the
     * cluster is fixed-length at the copybook's declared width.
     */
    public static final int WORK_KSDS_RECORD_LENGTH = TrnxRecord.RECORD_LENGTH;

    /**
     * {@code DCB=(LRECL=350,BLKSIZE=3500,RECFM=FB)} ({@code CREASTMT.JCL:L50}) - the intermediate
     * sequential file's block size, ten records to a block.
     */
    public static final int WORK_SEQUENTIAL_BLOCK_SIZE = 3500;

    /** {@code organization: ksds} - the value {@code carddemo.datasets} uses for an indexed cluster. */
    public static final String ORGANIZATION_KSDS = DatasetBinding.KSDS;

    // -------------------------------------------------------------------------------------------------
    // The write order. This class's parity-critical asset: the writers own the bytes, these lists own the
    // sequence. Each is transcribed straight from the source and is published so a test asserts against
    // the list rather than against a second transcription of it.
    // -------------------------------------------------------------------------------------------------

    /**
     * {@code 5000-CREATE-STATEMENT}'s fifteen plain-text writes, {@code app/cbl/CBSTM03A.CBL:L488-L502}.
     *
     * <p><strong>Two deliberate repeats.</strong> {@code ST-LINE5} is written at {@code L492} and again at
     * {@code L494}, and {@code ST-LINE12} at {@code L500} and again at {@code L502}. Both produce two
     * identical records and neither is a transcription slip: the rules bracket the Basic Details heading
     * and the column headings respectively.
     *
     * <p>{@code ST-LINE0} is not in the list because it is written before the HTML header block
     * ({@code L460-L461}), and {@code ST-LINE12}, {@code ST-LINE14A} and {@code ST-LINE15} are not
     * because they belong to {@code 4000-TRNXFILE-GET} ({@code L435-L437}).
     */
    public static final List<StatementLine> STATEMENT_BODY_TEXT_LINES = List.of(
            StatementLine.ST_LINE1,
            StatementLine.ST_LINE2,
            StatementLine.ST_LINE3,
            StatementLine.ST_LINE4,
            StatementLine.ST_LINE5,
            StatementLine.ST_LINE6,
            StatementLine.ST_LINE5,
            StatementLine.ST_LINE7,
            StatementLine.ST_LINE8,
            StatementLine.ST_LINE9,
            StatementLine.ST_LINE10,
            StatementLine.ST_LINE11,
            StatementLine.ST_LINE12,
            StatementLine.ST_LINE13,
            StatementLine.ST_LINE12);

    /**
     * {@code 4000-TRNXFILE-GET}'s three closing plain-text writes, {@code app/cbl/CBSTM03A.CBL:L435-L437}:
     * the rule, the {@code Total EXP:} total line and the closing banner.
     */
    public static final List<StatementLine> STATEMENT_TOTAL_TEXT_LINES = List.of(
            StatementLine.ST_LINE12,
            StatementLine.ST_LINE14A,
            StatementLine.ST_LINE15);

    /**
     * {@code 5100-WRITE-HTML-HEADER}'s ten records up to and including the banner cell,
     * {@code app/cbl/CBSTM03A.CBL:L508-L527}. The account heading follows them.
     */
    public static final List<HtmlFixedLine> HTML_HEADER_PROLOGUE_LINES = List.of(
            HtmlFixedLine.HTML_L01,
            HtmlFixedLine.HTML_L02,
            HtmlFixedLine.HTML_L03,
            HtmlFixedLine.HTML_L04,
            HtmlFixedLine.HTML_L05,
            HtmlFixedLine.HTML_L06,
            HtmlFixedLine.HTML_L07,
            HtmlFixedLine.HTML_L08,
            HtmlFixedLine.HTML_LTRS,
            HtmlFixedLine.HTML_L10);

    /**
     * {@code 5100-WRITE-HTML-HEADER}'s eleven records after the account heading,
     * {@code app/cbl/CBSTM03A.CBL:L531-L552}: the bank's own name and address block, and the opening of
     * the customer cell.
     */
    public static final List<HtmlFixedLine> HTML_HEADER_BANK_LINES = List.of(
            HtmlFixedLine.HTML_LTDE,
            HtmlFixedLine.HTML_LTRE,
            HtmlFixedLine.HTML_LTRS,
            HtmlFixedLine.HTML_L15,
            HtmlFixedLine.HTML_L16,
            HtmlFixedLine.HTML_L17,
            HtmlFixedLine.HTML_L18,
            HtmlFixedLine.HTML_LTDE,
            HtmlFixedLine.HTML_LTRE,
            HtmlFixedLine.HTML_LTRS,
            HtmlFixedLine.HTML_L22_35);

    /**
     * {@code 5200-WRITE-HTML-NMADBS}'s nine records between the address block and the basic details,
     * {@code app/cbl/CBSTM03A.CBL:L594-L611}.
     */
    public static final List<HtmlFixedLine> HTML_BASIC_DETAILS_PRELUDE_LINES = List.of(
            HtmlFixedLine.HTML_LTDE,
            HtmlFixedLine.HTML_LTRE,
            HtmlFixedLine.HTML_LTRS,
            HtmlFixedLine.HTML_L30_42,
            HtmlFixedLine.HTML_L31,
            HtmlFixedLine.HTML_LTDE,
            HtmlFixedLine.HTML_LTRE,
            HtmlFixedLine.HTML_LTRS,
            HtmlFixedLine.HTML_L22_35);

    /**
     * {@code 5200-WRITE-HTML-NMADBS}'s eighteen closing records, {@code app/cbl/CBSTM03A.CBL:L634-L669}:
     * the Transaction Summary heading and the three column headings.
     */
    public static final List<HtmlFixedLine> HTML_COLUMN_HEADING_LINES = List.of(
            HtmlFixedLine.HTML_LTDE,
            HtmlFixedLine.HTML_LTRE,
            HtmlFixedLine.HTML_LTRS,
            HtmlFixedLine.HTML_L30_42,
            HtmlFixedLine.HTML_L43,
            HtmlFixedLine.HTML_LTDE,
            HtmlFixedLine.HTML_LTRE,
            HtmlFixedLine.HTML_LTRS,
            HtmlFixedLine.HTML_L47,
            HtmlFixedLine.HTML_L48,
            HtmlFixedLine.HTML_LTDE,
            HtmlFixedLine.HTML_L50,
            HtmlFixedLine.HTML_L51,
            HtmlFixedLine.HTML_LTDE,
            HtmlFixedLine.HTML_L53,
            HtmlFixedLine.HTML_L54,
            HtmlFixedLine.HTML_LTDE,
            HtmlFixedLine.HTML_LTRE);

    /**
     * {@code 4000-TRNXFILE-GET}'s eight closing HTML records, {@code app/cbl/CBSTM03A.CBL:L439-L454}:
     * the end-of-statement heading and the three document-closing tags.
     */
    public static final List<HtmlFixedLine> HTML_FOOTER_LINES = List.of(
            HtmlFixedLine.HTML_LTRS,
            HtmlFixedLine.HTML_L10,
            HtmlFixedLine.HTML_L75,
            HtmlFixedLine.HTML_LTDE,
            HtmlFixedLine.HTML_LTRE,
            HtmlFixedLine.HTML_L78,
            HtmlFixedLine.HTML_L79,
            HtmlFixedLine.HTML_L80);

    /**
     * The three {@code ST-ADD} slots {@code 5200-WRITE-HTML-NMADBS} emits, in the order it emits them
     * ({@code app/cbl/CBSTM03A.CBL:L569-L592}).
     */
    public static final List<AddressField> HTML_ADDRESS_FIELDS = List.of(
            AddressField.ADDRESS_LINE_1,
            AddressField.ADDRESS_LINE_2,
            AddressField.ADDRESS_LINE_3);

    /**
     * The three basic details {@code 5200-WRITE-HTML-NMADBS} emits, in the order it emits them
     * ({@code app/cbl/CBSTM03A.CBL:L613-L633}).
     */
    public static final List<BasicDetail> HTML_BASIC_DETAILS = List.of(
            BasicDetail.ACCOUNT_ID,
            BasicDetail.CURRENT_BALANCE,
            BasicDetail.FICO_SCORE);

    // =================================================================================================
    // Injected collaborators. Every one is final and arrives through the constructor: no field injection,
    // no setter, no static mutable state (practice B9, gate G53).
    // =================================================================================================

    /** The module's batch scaffolding: job and step builders, the {@code COND} gate, the job catalogue. */
    private final BatchConfig batchConfig;

    /** {@code CBSTM03B} - the data-access collaborator that owns all four input files. */
    private final StatementGenerationJobB statementSubroutine;

    /** {@code STMTFILE} - the 80-byte plain-text statement writer. */
    private final StatementTextWriter textWriter;

    /** {@code HTMLFILE} - the 100-byte HTML statement writer. */
    private final StatementHtmlWriter htmlWriter;

    /**
     * The dataset code page, injected by bean qualifier and never derived from the platform (practice
     * B8). Every record area this class allocates and every group {@code MOVE} it performs is bytes in
     * this code page.
     */
    private final Charset datasetCharset;

    /** The hand-written fixed-width codec over {@link #datasetCharset} (practices B8 and B11). */
    private final FixedWidthCodec codec;

    /** Where every {@code DISPLAY} of this program goes, resolved once at construction. */
    private final SysoutSink sysoutSink;

    /** The substitute for the z/OS {@code TIOT} walk, resolved once at construction (practice B12). */
    private final TiotSource tiotSource;

    /** The three utility steps' data path, resolved once at construction. */
    private final DatasetUtilityPort datasetUtilityPort;

    /**
     * The five validated step contracts, in JCL declaration order, keyed by position in
     * {@link #STEP_NAMES}.
     */
    private final List<StepContract> stepContracts;

    /**
     * Constructor injection throughout, with the whole JCL contract validated before the job can be
     * built.
     *
     * <p>What is checked, and why each check earns its place:
     * <ul>
     *   <li><strong>All five steps exist, in order, naming the right program.</strong> A step that named
     *       another program would resolve another program's DD names.</li>
     *   <li><strong>Exactly {@value #STEP_020}, {@value #STEP_030} and {@value #STEP_040} are gated.</strong>
     *       Getting the gating on the wrong count of steps is the easy mistake here and it is silent: a
     *       job with four gated steps bypasses work the mainframe performs, and one with two runs work
     *       the mainframe bypasses.</li>
     *   <li><strong>The job declares no parameters.</strong> No step of {@code app/jcl/CREASTMT.JCL}
     *       carries a {@code PARM}; the only {@code PARM} in the estate belongs to the interest
     *       calculator. A parameter declared here would change how this job is identified in the batch
     *       metadata and would be an input the COBOL never receives.</li>
     * </ul>
     *
     * @param statementSubroutine       {@code CBSTM03B}; never {@code null}
     * @param textWriter                the {@code STMTFILE} writer; never {@code null}
     * @param htmlWriter                the {@code HTMLFILE} writer; never {@code null}
     * @param batchConfig               the batch scaffolding; never {@code null}
     * @param datasetCharset            the dataset code page, named by bean qualifier
     * @param jdbcTemplate              the module's shared template, used only to build the default
     *                                  {@linkplain DatasetUtilityPort utility port}
     * @param sysoutSinkProvider        provider for an injected {@code SYSOUT} sink; may resolve to no
     *                                  bean, in which case standard output is used
     * @param tiotSourceProvider        provider for an injected {@code TIOT} substitute; may resolve to
     *                                  no bean, in which case the configured one is used
     * @param datasetUtilityPortProvider provider for an injected utility port; may resolve to no bean, in
     *                                  which case the {@link JdbcTemplate}-backed default is used
     * @throws NullPointerException  if any required argument is {@code null}
     * @throws IllegalStateException if the configured contract is absent, names another program, gates
     *                               the wrong steps, or declares a job parameter
     */
    public StatementGenerationJobA(
            StatementGenerationJobB statementSubroutine,
            StatementTextWriter textWriter,
            StatementHtmlWriter htmlWriter,
            BatchConfig batchConfig,
            @Qualifier(CobolCharsetConfig.DATASET_CHARSET_BEAN_NAME) Charset datasetCharset,
            JdbcTemplate jdbcTemplate,
            ObjectProvider<SysoutSink> sysoutSinkProvider,
            ObjectProvider<TiotSource> tiotSourceProvider,
            ObjectProvider<DatasetUtilityPort> datasetUtilityPortProvider) {

        this.statementSubroutine = Objects.requireNonNull(statementSubroutine, "CBSTM03B is required: "
                + PROGRAM_ID + " declares only its two output files and delegates every input read to "
                + "the subroutine, calling it thirteen times");
        this.textWriter = Objects.requireNonNull(textWriter, "The " + STMTFILE_DD + " writer is "
                + "required: the plain-text statement is one of this program's two outputs");
        this.htmlWriter = Objects.requireNonNull(htmlWriter, "The " + HTMLFILE_DD + " writer is "
                + "required: the HTML statement is the other of this program's two outputs");
        this.batchConfig = Objects.requireNonNull(batchConfig, "The batch scaffolding is required: the "
                + "job and step builders, the COND=(0,NE) gate and the job contract all arrive through "
                + "it, so this class holds no Spring Batch plumbing of its own");
        this.datasetCharset = Objects.requireNonNull(datasetCharset, "A dataset charset is required: a "
                + "fixed-width mainframe record is bytes in a specific code page, so the code page is "
                + "injected explicitly and is never derived from the platform");
        Objects.requireNonNull(jdbcTemplate, "A JdbcTemplate is required to build the default dataset "
                + "utility port; the data-source configuration declares the single instance this module "
                + "shares");
        Objects.requireNonNull(sysoutSinkProvider, "A SYSOUT sink provider is required; it may resolve "
                + "to no bean, in which case the standard output stream is used");
        Objects.requireNonNull(tiotSourceProvider, "A TIOT source provider is required; it may resolve "
                + "to no bean, in which case the configured substitute is used");
        Objects.requireNonNull(datasetUtilityPortProvider, "A dataset utility port provider is "
                + "required; it may resolve to no bean, in which case the JDBC-backed default is used");

        this.codec = new FixedWidthCodec(datasetCharset);
        this.stepContracts = requireJclContract(batchConfig);
        this.sysoutSink = sysoutSinkProvider.getIfAvailable(() -> standardOutput(datasetCharset));
        this.tiotSource = tiotSourceProvider.getIfAvailable(this::configuredTiotSource);
        this.datasetUtilityPort = datasetUtilityPortProvider
                .getIfAvailable(() -> new JdbcDatasetUtilityPort(jdbcTemplate, datasetCharset));
    }

    /**
     * Resolves and validates the five step contracts against {@code app/jcl/CREASTMT.JCL}.
     *
     * @param scaffolding the batch scaffolding holding the {@code carddemo.jobs} catalogue
     * @return the five validated contracts, in JCL declaration order
     * @throws IllegalStateException if a step is absent, names another program, is gated when the JCL
     *                               does not gate it or ungated when it does, or if the job declares a
     *                               parameter
     */
    private static List<StepContract> requireJclContract(BatchConfig scaffolding) {
        List<StepContract> contracts = new ArrayList<>(STEP_NAMES.size());
        for (String stepName : STEP_NAMES) {
            StepContract contract = scaffolding.contract(JOB_KEY).step(stepName);
            boolean shouldBeGated = GATED_STEP_NAMES.contains(stepName);
            if (contract.requirePrecedingExitCodeZero() != shouldBeGated) {
                throw new IllegalStateException("The carddemo.jobs contract for '" + JOB_KEY
                        + "' declares step '" + stepName + "' with "
                        + "require-preceding-exit-code-zero=" + contract.requirePrecedingExitCodeZero()
                        + ", but app/jcl/CREASTMT.JCL carries COND=(0,NE) on exactly "
                        + GATED_STEP_NAMES + " and on no other step. Gating a step the JCL does not "
                        + "gate bypasses work the mainframe performs; ungating one it does gate runs "
                        + "work the mainframe bypasses.");
            }
            contracts.add(contract);
        }
        StepContract programStep = contracts.get(STEP_NAMES.indexOf(STEP_040));
        if (!PROGRAM_ID.equals(programStep.program())) {
            throw new IllegalStateException("The carddemo.jobs contract for '" + JOB_KEY + "' declares "
                    + "step '" + STEP_040 + "' running program '" + programStep.program() + "', but "
                    + "app/jcl/CREASTMT.JCL:L79 is EXEC PGM=" + PROGRAM_ID + ". A step that named "
                    + "another program would resolve another program's DD names.");
        }
        if (!scaffolding.contract(JOB_KEY).parameters().isEmpty()) {
            throw new IllegalStateException("The carddemo.jobs contract for '" + JOB_KEY + "' declares "
                    + scaffolding.contract(JOB_KEY).parameters().size() + " job parameter(s), but no "
                    + "step of app/jcl/CREASTMT.JCL carries a PARM. The only PARM in this estate is "
                    + "app/jcl/INTCALC.jcl:L22, which belongs to the interest calculator alone.");
        }
        return List.copyOf(contracts);
    }

    // =================================================================================================
    // The Spring Batch surface: one job, five tasklet steps, three COND gates.
    //
    // Only the Job is a bean. The steps and the tasklets are ordinary methods, deliberately: ten sibling
    // job classes live in this module, and a published Step or Tasklet bean from each would make every
    // by-type injection of those interfaces ambiguous at once.
    // =================================================================================================

    /**
     * The job, published as a bean: the five steps of {@code app/jcl/CREASTMT.JCL} in declaration order,
     * with {@code COND=(0,NE)} gates before the last three.
     *
     * <p>The flow reads exactly as the JCL does. {@value #STEP_DELDEF01} and {@value #STEP_010} run
     * unconditionally; before each of {@value #STEP_020}, {@value #STEP_030} and {@value #STEP_040} a gate
     * asks whether every step so far returned zero, and if not the job ends without running the rest.
     * Ending is not failing: on the mainframe a step flushed by {@code COND} does not itself fail the
     * job, and the non-zero code that caused the bypass is already recorded on the step that produced it.
     *
     * <p>The gate itself is {@link BatchConfig#precedingExitCodeZeroDecider()} - it is consumed, never
     * re-implemented here. Three <em>distinct</em> {@link CondGate} instances wrap it because Spring
     * Batch keys a decision state on the decider instance: one shared instance would collapse the three
     * gates onto a single state with two conflicting outgoing transitions.
     *
     * <p>Publishing the job does <strong>not</strong> run it. {@code spring.batch.job.enabled} is
     * {@code false}, so it executes only when something deliberately launches it, with the empty
     * {@link #jobParameters()} this job declares.
     *
     * @return the {@value #JOB_NAME} job; never {@code null}
     */
    @Bean
    public Job statementGenerationJobA() {
        JobExecutionDecider beforeStep020 = condGate();
        JobExecutionDecider beforeStep030 = condGate();
        JobExecutionDecider beforeStep040 = condGate();
        return batchConfig.job(JOB_NAME)
                .start(deldef01Step())
                .next(step010Step())
                .next(beforeStep020).on(BatchConfig.PROCEED.getName()).to(step020Step())
                .next(beforeStep030).on(BatchConfig.PROCEED.getName()).to(step030Step())
                .next(beforeStep040).on(BatchConfig.PROCEED.getName()).to(step040Step())
                .from(beforeStep020).on(BatchConfig.SKIP.getName()).end()
                .from(beforeStep030).on(BatchConfig.SKIP.getName()).end()
                .from(beforeStep040).on(BatchConfig.SKIP.getName()).end()
                .end()
                .build();
    }

    /**
     * A fresh {@code COND=(0,NE)} gate delegating to the shared decider.
     *
     * @return a new gate instance; never {@code null}
     */
    private JobExecutionDecider condGate() {
        return new CondGate(batchConfig.precedingExitCodeZeroDecider());
    }

    /**
     * {@value #STEP_DELDEF01} - {@code EXEC PGM=IDCAMS} ({@code app/jcl/CREASTMT.JCL:L22}), ungated.
     *
     * @return the step; never {@code null}
     */
    public Step deldef01Step() {
        return batchConfig.taskletStep(stepContract(STEP_DELDEF01).name(),
                deleteAndDefineTasklet()).build();
    }

    /**
     * {@value #STEP_010} - {@code EXEC PGM=SORT} ({@code app/jcl/CREASTMT.JCL:L44}), <strong>ungated</strong>.
     *
     * @return the step; never {@code null}
     */
    public Step step010Step() {
        return batchConfig.taskletStep(stepContract(STEP_010).name(),
                sortAndReformatTasklet()).build();
    }

    /**
     * {@value #STEP_020} - {@code EXEC PGM=IDCAMS,COND=(0,NE)} ({@code app/jcl/CREASTMT.JCL:L56}).
     *
     * @return the step; never {@code null}
     */
    public Step step020Step() {
        return batchConfig.taskletStep(stepContract(STEP_020).name(), reproTasklet()).build();
    }

    /**
     * {@value #STEP_030} - {@code EXEC PGM=IEFBR14,COND=(0,NE)} ({@code app/jcl/CREASTMT.JCL:L66}).
     *
     * @return the step; never {@code null}
     */
    public Step step030Step() {
        return batchConfig.taskletStep(stepContract(STEP_030).name(),
                deletePreviousReportsTasklet()).build();
    }

    /**
     * {@value #STEP_040} - {@code EXEC PGM=CBSTM03A,COND=(0,NE)} ({@code app/jcl/CREASTMT.JCL:L79}), the
     * program itself.
     *
     * <p>A <strong>tasklet</strong> step, and never a chunk-oriented one. {@code CBSTM03A} accumulates
     * across records - the whole two-dimensional table is loaded before the first statement is written,
     * and {@code WS-TOTAL-AMT} is carried across a customer's transactions - and its write ordering is a
     * single pass. Chunking would relocate the commit boundaries that ordering sits inside, which is
     * exactly what parity forbids.
     *
     * @return the step; never {@code null}
     */
    public Step step040Step() {
        return batchConfig.taskletStep(stepContract(STEP_040).name(), statementTasklet()).build();
    }

    /**
     * {@value #STEP_DELDEF01}'s body: one call, one dataset preparation.
     *
     * @return the tasklet; never {@code null}
     */
    public Tasklet deleteAndDefineTasklet() {
        return (contribution, chunkContext) ->
                report(contribution, chunkContext, deleteAndDefineWorkDatasets());
    }

    /**
     * {@value #STEP_010}'s body: one call, one complete sort and reformat.
     *
     * @return the tasklet; never {@code null}
     */
    public Tasklet sortAndReformatTasklet() {
        return (contribution, chunkContext) ->
                report(contribution, chunkContext, sortAndReformatTransactions());
    }

    /**
     * {@value #STEP_020}'s body: one call, one complete load.
     *
     * @return the tasklet; never {@code null}
     */
    public Tasklet reproTasklet() {
        return (contribution, chunkContext) ->
                report(contribution, chunkContext, reproSortedFileIntoWorkDataset());
    }

    /**
     * {@value #STEP_030}'s body: one call, both report datasets cleared.
     *
     * @return the tasklet; never {@code null}
     */
    public Tasklet deletePreviousReportsTasklet() {
        return (contribution, chunkContext) ->
                report(contribution, chunkContext, deletePreviousReportDatasets());
    }

    /**
     * {@value #STEP_040}'s body: a thin adapter over {@link #printAccountStatements(SysoutSink)}.
     *
     * <p>Thin on purpose (practice B10, gate G51). The adapter creates nothing and decides nothing, so
     * the twenty parity cases and every branch test call the program directly, with no
     * {@code JobLauncher} and no application context in the path.
     *
     * <p>Nothing is caught. An {@link AbendException} raised on a fatal I/O arm must reach the framework
     * so {@code BatchConfig}'s listeners can carry its return code onto the step's exit status and the
     * process exit code (gate G35); swallowing it would report a failed job as complete.
     *
     * @return the tasklet; never {@code null}
     */
    public Tasklet statementTasklet() {
        return (contribution, chunkContext) ->
                report(contribution, chunkContext, printAccountStatements(sysoutSink));
    }

    /**
     * Reports a step body's record count to the framework and finishes the step.
     *
     * <p>{@link RepeatStatus#FINISHED} always: each of the five bodies performs exactly one pass, and
     * returning {@code CONTINUABLE} would re-run the whole step.
     *
     * @param contribution the step's contribution, which the count is reported to
     * @param chunkContext the framework's chunk context; deliberately unused, because a tasklet that runs
     *                     once has no per-chunk state to consult and this job has no restart semantics
     *                     beyond re-running the step
     * @param records      how many records the body handled
     * @return {@link RepeatStatus#FINISHED}
     */
    private static RepeatStatus report(StepContribution contribution, ChunkContext chunkContext,
            int records) {
        Objects.requireNonNull(chunkContext, "A chunk context is supplied by the framework");
        contribution.incrementWriteCount(records);
        return RepeatStatus.FINISHED;
    }

    /**
     * The job parameters this job is launched with: <strong>none</strong>.
     *
     * <p>Read from the contract rather than built here, so the empty list configuration declares is
     * demonstrably what reaches the launcher.
     *
     * @return empty job parameters; never {@code null}
     */
    public JobParameters jobParameters() {
        return batchConfig.contract(JOB_KEY).jobParameters();
    }

    /**
     * The five validated step contracts, in JCL declaration order.
     *
     * @return an immutable list of five contracts; never {@code null}
     */
    public List<StepContract> stepContracts() {
        return stepContracts;
    }

    /**
     * One validated step contract, by step name.
     *
     * @param stepName one of {@link #STEP_NAMES}
     * @return the contract; never {@code null}
     * @throws IllegalArgumentException if {@code stepName} is not one of this job's five steps
     */
    public StepContract stepContract(String stepName) {
        int position = STEP_NAMES.indexOf(stepName);
        if (position < 0) {
            throw new IllegalArgumentException("'" + stepName + "' is not a step of "
                    + "app/jcl/CREASTMT.JCL, whose steps are " + STEP_NAMES + " in declaration order.");
        }
        return stepContracts.get(position);
    }

    /**
     * The sink every {@code DISPLAY} of this program is written to.
     *
     * <p>Surfaced so a caller can confirm which sink was resolved, and so a test can assert that an
     * injected sink really is in use rather than shadowed by the default.
     *
     * @return the resolved sink; never {@code null}
     */
    public SysoutSink sysoutSink() {
        return sysoutSink;
    }

    /**
     * The substitute source for the {@code TIOT} prologue.
     *
     * @return the resolved source; never {@code null}
     */
    public TiotSource tiotSource() {
        return tiotSource;
    }

    /**
     * The data path the three utility steps use.
     *
     * @return the resolved port; never {@code null}
     */
    public DatasetUtilityPort datasetUtilityPort() {
        return datasetUtilityPort;
    }

    /**
     * The dataset code page this job reads and writes records in, as injected.
     *
     * @return the charset; never {@code null}
     */
    public Charset datasetCharset() {
        return datasetCharset;
    }

    /**
     * This job's view of a DD name, resolved job-scoped override first and global catalogue second.
     *
     * <p>Only keys are named. No mainframe dataset name appears anywhere in this file (gate G46).
     *
     * @param ddName one of the DD names {@code app/jcl/CREASTMT.JCL} declares
     * @return the binding; never {@code null}
     * @throws IllegalStateException if neither this job nor the global catalogue declares it
     */
    public DatasetBinding datasetBinding(String ddName) {
        return batchConfig.datasetBinding(JOB_KEY, ddName);
    }

    /**
     * The {@code COND=(0,NE)} gate as one distinct decision state.
     *
     * <p>It delegates in full to {@link BatchConfig#precedingExitCodeZeroDecider()} and holds no policy
     * of its own - the gating rule is stated once, in {@code BatchConfig}, because that class was written
     * from this very JCL. This wrapper exists for one narrow reason: Spring Batch keys a decision state
     * on the decider <em>instance</em>, so three gated steps need three instances or their transitions
     * collapse onto one state.
     *
     * <p>Stateless and immutable, so it introduces no shared mutable state.
     */
    static final class CondGate implements JobExecutionDecider {

        /** The shared gating policy, which this class only forwards to. */
        private final JobExecutionDecider gate;

        /**
         * @param gate the shared decider from {@code BatchConfig}; must not be {@code null}
         */
        CondGate(JobExecutionDecider gate) {
            this.gate = Objects.requireNonNull(gate, "The shared COND=(0,NE) decider is required; this "
                    + "wrapper exists only to give it a distinct decision-state identity");
        }

        /**
         * Forwards the decision unchanged.
         *
         * @param jobExecution  the running job
         * @param stepExecution the step the flow arrived from, or {@code null} at the head of a flow
         * @return {@link BatchConfig#PROCEED} or {@link BatchConfig#SKIP}
         */
        @Override
        public FlowExecutionStatus decide(JobExecution jobExecution, StepExecution stepExecution) {
            return gate.decide(jobExecution, stepExecution);
        }

        /**
         * The JCL condition this gate implements, so a flow dump names it recognisably.
         *
         * @return {@code "COND=(0,NE)"}
         */
        @Override
        public String toString() {
            return "COND=(0,NE)";
        }
    }

    // =================================================================================================
    // DELDEF01, STEP010, STEP020 and STEP030 - dataset preparation, not COBOL logic.
    //
    // These four steps run IDCAMS, DFSORT, IDCAMS and IEFBR14. None of them is a migrated program, and
    // none has a COBOL paragraph behind it, so what is translated here is each utility's own control
    // statements, against the configured carddemo.datasets bindings and nothing else.
    //
    // One boundary is drawn explicitly. DEFINE CLUSTER is dataset DEFINITION - the VSAM equivalent of a
    // data-definition statement - and gate G44 forbids this migration from emitting any DDL, schema
    // migration or generated table definition. So the cluster is not created here. Its geometry is
    // ASSERTED against what configuration declares, which is where a schema-frozen JDBC target keeps it,
    // and a mismatch is reported rather than silently worked around.
    // =================================================================================================

    /**
     * {@value #STEP_DELDEF01} - {@code IDCAMS DELETE} of the two work datasets, then
     * {@code SET MAXCC = 0}, then {@code DEFINE CLUSTER} ({@code app/jcl/CREASTMT.JCL:L25-L39}).
     *
     * <p>What each control statement becomes:
     * <ul>
     *   <li>{@code DELETE ...TRXFL.SEQ} and {@code DELETE ...TRXFL.VSAM.KSDS CLUSTER} become a clearing
     *       of the {@value #SORTOUT_DD} and {@value #OUTFILE_DD} bindings - content, not definition, so no
     *       DDL is emitted.</li>
     *   <li>{@code SET MAXCC = 0} is why this step never reports a failure for a dataset that was already
     *       absent: on the mainframe the two deletes are expected to fail on the first run and the reset
     *       hides it. Clearing an empty dataset removes nothing and is likewise not a failure here.</li>
     *   <li>{@code DEFINE CLUSTER ... KEYS(32 0) RECORDSIZE(350 350)} becomes the assertion in
     *       {@link #requireWorkDatasetGeometry()} - the record width and key span the definition would
     *       have created must already be what configuration declares.</li>
     * </ul>
     *
     * <p>{@code VOLUMES(TSU023)}, {@code SHAREOPTIONS(2 3)}, {@code ERASE}, {@code CYL(1 5)} and
     * {@code DATA (... CISZ(4096))} describe physical placement on a z/OS volume. They have no JDBC
     * counterpart, they are deployment concerns rather than behaviour, and nothing is invented for them.
     *
     * @return how many records the two clears removed
     * @throws IllegalStateException if the configured geometry does not match the {@code DEFINE CLUSTER}
     *                               contract, or if a dataset cannot be addressed
     */
    public int deleteAndDefineWorkDatasets() {
        requireWorkDatasetGeometry();
        // DELETE of the intermediate sequential file - the SORTOUT binding.         CREASTMT.JCL:L25
        int cleared = datasetUtilityPort.deleteAllRecords(datasetBinding(SORTOUT_DD));
        // DELETE ... CLUSTER of the work KSDS - the OUTFILE binding.            CREASTMT.JCL:L26-L27
        cleared += datasetUtilityPort.deleteAllRecords(datasetBinding(OUTFILE_DD));
        // SET MAXCC = 0 - neither delete failing is a failure of this step.             CREASTMT.JCL:L28
        return cleared;
    }

    /**
     * Asserts the geometry {@code DEFINE CLUSTER} would have created against what configuration declares.
     *
     * <p>Three facts are checked, and each is one the definition states outright: the work cluster is
     * indexed, its records are {@value #WORK_KSDS_RECORD_LENGTH} bytes, and its key is
     * {@value #WORK_KSDS_KEY_LENGTH} bytes at offset {@value #WORK_KSDS_KEY_OFFSET}. The intermediate
     * sequential file's width and block size are checked alongside them, from
     * {@code app/jcl/CREASTMT.JCL:L50}.
     *
     * @throws IllegalStateException if any of them disagrees with configuration
     */
    private void requireWorkDatasetGeometry() {
        DatasetBinding work = datasetBinding(OUTFILE_DD);
        if (!ORGANIZATION_KSDS.equals(work.organization())) {
            throw new IllegalStateException("The " + OUTFILE_DD + " binding of job '" + JOB_KEY
                    + "' declares organization '" + work.organization() + "', but "
                    + "app/jcl/CREASTMT.JCL:L35 defines the work cluster INDEXED and "
                    + "app/cbl/CBSTM03B.CBL opens TRNXFILE as an indexed file read by a 32-byte key. A "
                    + "sequential work dataset could not answer the keyed reads the statement job makes.");
        }
        requireRecordLength(work, OUTFILE_DD, WORK_KSDS_RECORD_LENGTH,
                "app/jcl/CREASTMT.JCL:L32 defines RECORDSIZE(350 350) and app/cpy/COSTM01.CPY declares a "
                        + "350-byte TRNX-RECORD");
        if (work.keyLength() == null || work.keyLength() != WORK_KSDS_KEY_LENGTH
                || work.keyOffsetOrZero() != WORK_KSDS_KEY_OFFSET) {
            throw new IllegalStateException("The " + OUTFILE_DD + " binding of job '" + JOB_KEY
                    + "' declares key-length " + work.keyLength() + " at offset "
                    + work.keyOffsetOrZero() + ", but app/jcl/CREASTMT.JCL:L30 defines KEYS("
                    + WORK_KSDS_KEY_LENGTH + " " + WORK_KSDS_KEY_OFFSET + ") - which is exactly "
                    + "app/cpy/COSTM01.CPY's TRNX-KEY, TRNX-CARD-NUM X(16) followed by TRNX-ID X(16). A "
                    + "different key span would read a different record for every customer.");
        }
        DatasetBinding sorted = datasetBinding(SORTOUT_DD);
        requireRecordLength(sorted, SORTOUT_DD, WORK_KSDS_RECORD_LENGTH,
                "app/jcl/CREASTMT.JCL:L50 declares DCB=(LRECL=350,BLKSIZE=3500,RECFM=FB)");
        if (sorted.blockSize() == null || sorted.blockSize() != WORK_SEQUENTIAL_BLOCK_SIZE) {
            throw new IllegalStateException("The " + SORTOUT_DD + " binding of job '" + JOB_KEY
                    + "' declares block-size " + sorted.blockSize() + ", but "
                    + "app/jcl/CREASTMT.JCL:L50 declares BLKSIZE=" + WORK_SEQUENTIAL_BLOCK_SIZE
                    + " - ten 350-byte records to a block.");
        }
    }

    /**
     * Asserts one binding's declared record width.
     *
     * @param binding   the binding to check
     * @param ddName    its DD name, for the diagnostic
     * @param expected  the width the source declares
     * @param authority the source line that declares it, quoted into the diagnostic
     * @throws IllegalStateException if the declared width differs
     */
    private static void requireRecordLength(DatasetBinding binding, String ddName, int expected,
            String authority) {
        if (binding.recordLength() != expected) {
            throw new IllegalStateException("The " + ddName + " binding of job '" + JOB_KEY
                    + "' declares record-length " + binding.recordLength() + ", but " + authority
                    + ", so the width is " + expected + ". A record width is copybook-fixed and must "
                    + "never be overridden per profile.");
        }
    }

    /**
     * {@value #STEP_010} - {@code SORT FIELDS=(263,16,CH,A,1,16,CH,A)} and
     * {@code OUTREC FIELDS=(1:263,16,17:1,262,279:279,50)} ({@code app/jcl/CREASTMT.JCL:L53-L54}).
     *
     * <p>Reads {@value #SORTIN_DD} - the transaction master, {@code app/cpy/CVTRA05Y.cpy} - reorders it by
     * card number then transaction id, reformats each record into the {@code app/cpy/COSTM01.CPY} layout
     * and writes the result to {@value #SORTOUT_DD}.
     *
     * <p><strong>This ordering is load-bearing and is not an optimisation.</strong> Card number then
     * transaction id is exactly the 32-byte {@code TRNX-KEY} order, and it is the reason the card-break
     * grouping in {@link #readTrnxRead} produces one table row per card and the reason the early exit in
     * {@link #trnxFileGet} is correct.
     *
     * @return how many records were written
     * @throws IllegalStateException if either dataset cannot be addressed
     */
    public int sortAndReformatTransactions() {
        List<String> input = datasetUtilityPort.readAllRecordImages(datasetBinding(SORTIN_DD));
        return datasetUtilityPort.writeRecordImages(datasetBinding(SORTOUT_DD), sortAndReformat(input));
    }

    /**
     * {@value #STEP_020} - {@code REPRO INFILE(INFILE) OUTFILE(OUTFILE)}
     * ({@code app/jcl/CREASTMT.JCL:L61}).
     *
     * <p>Copies the sorted sequential file into the keyed work cluster, record for record and in order.
     * {@code REPRO} transforms nothing, so neither does this.
     *
     * @return how many records were loaded
     * @throws IllegalStateException if either dataset cannot be addressed
     */
    public int reproSortedFileIntoWorkDataset() {
        List<String> sorted = datasetUtilityPort.readAllRecordImages(datasetBinding(INFILE_DD));
        return datasetUtilityPort.writeRecordImages(datasetBinding(OUTFILE_DD), sorted);
    }

    /**
     * {@value #STEP_030} - {@code EXEC PGM=IEFBR14} with {@code DISP=(MOD,DELETE,DELETE)} on both report
     * datasets ({@code app/jcl/CREASTMT.JCL:L66-L75}).
     *
     * <p>{@code IEFBR14} does nothing at all; the deletion is the disposition. The previous run's HTML and
     * plain-text statements are removed so {@value #STEP_040} writes into empty datasets, which is what
     * its own {@code DISP=(NEW,CATLG,DELETE)} then expects.
     *
     * <p>Note which {@code DCB} is <em>not</em> read here. This step declares {@value #HTMLFILE_DD} at
     * {@code LRECL=80} ({@code L69}); the creating step declares it at {@code LRECL=100} ({@code L94}).
     * The creating step is authoritative, so nothing in this method asserts a width (risk R-G, gate G20).
     *
     * @return how many records the two clears removed
     * @throws IllegalStateException if either dataset cannot be addressed
     */
    public int deletePreviousReportDatasets() {
        // HTMLFILE DD DISP=(MOD,DELETE,DELETE) ... STATEMNT.HTML                   CREASTMT.JCL:L67-L71
        int cleared = datasetUtilityPort.deleteAllRecords(datasetBinding(HTMLFILE_DD));
        // STMTFILE DD DISP=(MOD,DELETE,DELETE) ... STATEMNT.PS                     CREASTMT.JCL:L72-L75
        cleared += datasetUtilityPort.deleteAllRecords(datasetBinding(STMTFILE_DD));
        return cleared;
    }

    // =================================================================================================
    // The SORT/OUTREC transformation, as two pure functions. No dataset, no port, no state.
    // =================================================================================================

    /**
     * {@code SORT FIELDS} followed by {@code OUTREC FIELDS}: the whole of {@value #STEP_010}'s control
     * statements as one function.
     *
     * <p>The sort happens <strong>first</strong>, against the <em>input</em> record's positions, and the
     * reformat second - which is the order DFSORT applies them and the only order that produces the
     * documented result, since {@code OUTREC} moves the card number to the front of the record.
     *
     * <p>The sort is stable, which costs nothing and settles the question: the 32-byte key is unique
     * within the transaction master, so no two records compare equal on it, but a stable sort makes the
     * output a function of the input rather than of the algorithm.
     *
     * @param tranRecordImages the {@code TRAN-RECORD} images to sort and reformat, in dataset order; must
     *                         not be {@code null} and must contain no {@code null}
     * @return the derived {@code TRNX-RECORD} images, in key order; a new list, the input untouched
     * @throws NullPointerException if {@code tranRecordImages} or any element is {@code null}
     */
    public List<String> sortAndReformat(List<String> tranRecordImages) {
        Objects.requireNonNull(tranRecordImages, "Records are required to sort; an empty transaction "
                + "master yields an empty work dataset and is not an error");
        List<String> ordered = new ArrayList<>(tranRecordImages);
        ordered.sort(SORT_FIELDS_ORDER);
        List<String> derived = new ArrayList<>(ordered.size());
        for (String tranRecordImage : ordered) {
            derived.add(applyOutrec(tranRecordImage));
        }
        return derived;
    }

    /**
     * {@code SORT FIELDS=(263,16,CH,A,1,16,CH,A)} - ascending {@code TRAN-CARD-NUM}, then ascending
     * {@code TRAN-ID}, both compared as character data.
     *
     * <p>Java's lexicographic {@link String} comparison is used, and it agrees with the mainframe's native
     * collating sequence for this field's domain: both keys are fixed sixteen-byte spans holding digits,
     * possibly space-padded, and the digits and the space are monotonically ordered identically under
     * US-ASCII and under IBM037. A field whose domain included letters and digits together would need the
     * EBCDIC sequence spelled out, because there the two disagree; these do not.
     *
     * <p>{@code Comparator} is stateless and immutable here, so publishing it as a constant introduces no
     * shared mutable state.
     */
    public static final Comparator<String> SORT_FIELDS_ORDER =
            Comparator.comparing((String record) ->
                            jclField(record, SORT_MAJOR_KEY_POSITION, SORT_MAJOR_KEY_LENGTH))
                    .thenComparing(record ->
                            jclField(record, SORT_MINOR_KEY_POSITION, SORT_MINOR_KEY_LENGTH));

    /**
     * {@code OUTREC FIELDS=(1:263,16,17:1,262,279:279,50)} - one {@code TRAN-RECORD} reformatted into one
     * {@code TRNX-RECORD}.
     *
     * <p>Three triples, each {@code outputPosition:inputPosition,length}:
     * <ol>
     *   <li>{@code 1:263,16} - {@code TRAN-CARD-NUM} becomes {@code TRNX-CARD-NUM}, moving from the back
     *       of the record to the front.</li>
     *   <li>{@code 17:1,262} - input 1-262, everything from {@code TRAN-ID} through
     *       {@code TRAN-MERCHANT-ZIP}, lands at output 17-278. Output 17-32 is therefore {@code TRAN-ID}
     *       becoming {@code TRNX-ID}, which completes the 32-byte key, and every field after it lands at
     *       the offset {@code COSTM01} declares for it.</li>
     *   <li>{@code 279:279,50} - fifty bytes, not fifty-two. See {@link #OUTREC_TAIL_LENGTH}: the whole
     *       26-byte {@code TRAN-ORIG-TS} plus only the first 24 bytes of the 26-byte
     *       {@code TRAN-PROC-TS}.</li>
     * </ol>
     *
     * <p>Output 329-350 is addressed by no triple. DFSORT leaves an unaddressed output position blank, so
     * the derived record carries a 24-character processing timestamp followed by two spaces and a
     * space-filled {@code FILLER X(20)}. That is a defect of the JCL, it is visible in every record the
     * job loads, and it is reproduced rather than corrected (practice B4).
     *
     * @param tranRecordImage one {@code TRAN-RECORD}; fitted to {@value #SORT_RECORD_LENGTH} characters
     *                        under the alphanumeric {@code MOVE} rule first, so a short or over-long row
     *                        is handled the way the utility handles it rather than throwing
     * @return exactly {@value #SORT_RECORD_LENGTH} characters
     * @throws NullPointerException if {@code tranRecordImage} is {@code null}
     */
    public String applyOutrec(String tranRecordImage) {
        Objects.requireNonNull(tranRecordImage, "A TRAN-RECORD image is required to reformat");
        String source = codec.movePicX(tranRecordImage, SORT_RECORD_LENGTH);
        StringBuilder derived = new StringBuilder(" ".repeat(SORT_RECORD_LENGTH));
        overlay(derived, OUTREC_CARD_NUM_OUTPUT_POSITION,
                jclField(source, SORT_MAJOR_KEY_POSITION, SORT_MAJOR_KEY_LENGTH));
        overlay(derived, OUTREC_BODY_OUTPUT_POSITION,
                jclField(source, OUTREC_BODY_INPUT_POSITION, OUTREC_BODY_LENGTH));
        overlay(derived, OUTREC_TAIL_POSITION,
                jclField(source, OUTREC_TAIL_POSITION, OUTREC_TAIL_LENGTH));
        return derived.toString();
    }

    /**
     * One JCL field reference: {@code length} bytes starting at the 1-based {@code position}.
     *
     * <p><strong>The 1-based-to-0-based conversion lives here and in {@link #overlay} and nowhere else.</strong>
     * JCL and DFSORT count byte positions from 1, Java counts from 0, and doing the subtraction inline at
     * six call sites is how an off-by-one gets in.
     *
     * @param record         the record to read from
     * @param oneBasedPosition the JCL position, counting from 1
     * @param length         how many bytes to take
     * @return exactly {@code length} characters
     */
    private static String jclField(String record, int oneBasedPosition, int length) {
        int from = oneBasedPosition - 1;
        return record.substring(from, from + length);
    }

    /**
     * Overlays {@code value} at the 1-based {@code position} of {@code target}, leaving every other
     * position as it was.
     *
     * @param target           the output record under construction
     * @param oneBasedPosition the JCL output position, counting from 1
     * @param value            the characters to place there
     */
    private static void overlay(StringBuilder target, int oneBasedPosition, String value) {
        int from = oneBasedPosition - 1;
        target.replace(from, from + value.length(), value);
    }

    // =================================================================================================
    // The utility steps' data path, as a replaceable seam.
    // =================================================================================================

    /**
     * The three whole-dataset operations {@code IDCAMS} and {@code DFSORT} perform, and nothing else.
     *
     * <p>Deliberately narrow. {@code DELETE}, {@code REPRO} and a sort read a dataset in full, write a
     * dataset in full, or empty one; they do not read by key, do not update in place and do not browse. No
     * access path beyond those three is offered, so no SQL surface the mainframe never exercises is
     * invented (AAP 0.3.5).
     *
     * <p>This is also the deployment seam. The default implementation issues three ordinary statements
     * through the module's shared {@link JdbcTemplate}, which is right for a backend that presents each
     * dataset as a single-column relation of fixed-width record images; a deployment whose driver expects
     * a different parameter shape supplies its own bean and the four utility steps pick it up untouched.
     * A test supplies an in-memory implementation and asserts the records that moved, with no database and
     * no application context involved (practice B10, gate G51).
     */
    public interface DatasetUtilityPort {

        /**
         * Empties a dataset: {@code IDCAMS DELETE} on a cluster whose definition survives, and the
         * {@code DISP=(MOD,DELETE,DELETE)} disposition of a sequential file.
         *
         * <p>Removing nothing is <strong>not</strong> a failure. {@code app/jcl/CREASTMT.JCL:L28} follows
         * its two deletes with {@code SET MAXCC = 0} precisely because on a first run there is nothing
         * there to delete.
         *
         * @param binding the dataset to empty; never {@code null}
         * @return how many records were removed, {@code 0} for an already-empty dataset
         * @throws IllegalStateException if the dataset cannot be addressed
         */
        int deleteAllRecords(DatasetBinding binding);

        /**
         * Reads a dataset in full, in its own stored order: {@code SORTIN} and {@code REPRO INFILE}.
         *
         * @param binding the dataset to read; never {@code null}
         * @return every record image, each exactly {@link DatasetBinding#recordLength()} characters, in
         *         dataset order; empty for an empty dataset
         * @throws IllegalStateException if the dataset cannot be addressed
         */
        List<String> readAllRecordImages(DatasetBinding binding);

        /**
         * Writes records to a dataset in the order given: {@code SORTOUT} and {@code REPRO OUTFILE}.
         *
         * <p>Order is preserved exactly, because for {@value #SORTOUT_DD} the order <em>is</em> the
         * product of the step.
         *
         * @param binding      the dataset to write; never {@code null}
         * @param recordImages the records, in order; never {@code null} and never containing {@code null}
         * @return how many records were written
         * @throws IllegalStateException if the dataset cannot be addressed
         */
        int writeRecordImages(DatasetBinding binding, List<String> recordImages);
    }

    /**
     * The default {@link DatasetUtilityPort}: three ordinary statements over the module's shared
     * {@link JdbcTemplate}.
     *
     * <p>What the statement shape asserts, and why:
     * <ul>
     *   <li><strong>No column list.</strong> This migration introduces no schema and no column names
     *       (gate G44). A record is one fixed-width image, bound positionally, and read from column
     *       position {@value #RECORD_IMAGE_COLUMN_INDEX}.</li>
     *   <li><strong>The dataset name is one delimited identifier.</strong> A mainframe dataset name
     *       contains dots, which an SQL parser would otherwise read as a qualified
     *       catalogue-schema-table reference.</li>
     *   <li><strong>The name is validated as a dataset name before it is rendered.</strong> It arrives from
     *       configuration, which is externally controlled, and reaches a position no bind parameter can
     *       occupy, so it must satisfy the z/OS dataset-name grammar rather than merely survive a scan for
     *       punctuation somebody thought of.</li>
     *   <li><strong>The name comes from configuration, verbatim, resolved by DD-name key.</strong> No
     *       mainframe dataset literal appears anywhere in this file (gate G46).</li>
     * </ul>
     *
     * <p>Validation happens at the point of <em>use</em> and not at construction, on purpose: a profile may
     * legitimately bind a dataset to something this port cannot address - the test profile binds work files
     * to filesystem paths - and in that case the right outcome is a refusal naming the binding when a step
     * tries to use it, not a context that will not start.
     *
     * <p>Immutable and stateless beyond its two collaborators, so it is safe to share.
     */
    public static final class JdbcDatasetUtilityPort implements DatasetUtilityPort {

        /**
         * The z/OS dataset-name grammar: dot-separated qualifiers, each one to eight characters, each
         * beginning with a letter or one of {@code $ # @} and continuing with those, digits or a hyphen.
         *
         * <p>Upper case only, and not case-folded. Configuration declares these names in the case the
         * mainframe uses them in, and quietly upper-casing something that arrived lower-case would be
         * addressing a name nobody wrote.
         */
        private static final Pattern DATASET_NAME =
                Pattern.compile("[A-Z$#@][A-Z0-9$#@-]{0,7}(?:\\.[A-Z$#@][A-Z0-9$#@-]{0,7})*");

        /** The maximum length of a z/OS dataset name, including the separating dots. */
        private static final int MAX_DATASET_NAME_LENGTH = 44;

        /** The 1-based column position the record image occupies in a dataset relation. */
        private static final int RECORD_IMAGE_COLUMN_INDEX = 1;

        /** The module's shared template over the configuration-bound {@code DataSource}. */
        private final JdbcTemplate jdbcTemplate;

        /** The codec that fits a read row to its binding's declared width under the {@code PIC X} rule. */
        private final FixedWidthCodec codec;

        /**
         * @param jdbcTemplate   the module's shared template; must not be {@code null}
         * @param datasetCharset the dataset code page, stated explicitly and never defaulted; must not be
         *                       {@code null}
         * @throws NullPointerException if either argument is {@code null}
         */
        public JdbcDatasetUtilityPort(JdbcTemplate jdbcTemplate, Charset datasetCharset) {
            this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "A JdbcTemplate is required to "
                    + "address a dataset; the data-source configuration declares the single instance "
                    + "this module shares");
            this.codec = new FixedWidthCodec(Objects.requireNonNull(datasetCharset, "A dataset charset "
                    + "is required: a fixed-width record is bytes in a specific code page, which is "
                    + "never derived from the platform"));
        }

        @Override
        public int deleteAllRecords(DatasetBinding binding) {
            return jdbcTemplate.update("DELETE FROM " + identifierOf(binding));
        }

        @Override
        public List<String> readAllRecordImages(DatasetBinding binding) {
            String identifier = identifierOf(binding);
            int recordLength = binding.recordLength();
            return jdbcTemplate.query("SELECT * FROM " + identifier,
                    (row, rowNumber) -> requireRecordImage(row.getString(RECORD_IMAGE_COLUMN_INDEX),
                            identifier, rowNumber, recordLength));
        }

        @Override
        public int writeRecordImages(DatasetBinding binding, List<String> recordImages) {
            Objects.requireNonNull(recordImages, "Records are required to write; pass an empty list to "
                    + "write nothing");
            String statement = "INSERT INTO " + identifierOf(binding) + " VALUES (?)";
            int written = 0;
            for (String recordImage : recordImages) {
                written += jdbcTemplate.update(statement,
                        codec.movePicX(recordImage, binding.recordLength()));
            }
            return written;
        }

        /**
         * Fits one read row to its binding's declared width, refusing a row that carries no image at all.
         *
         * @param recordImage  the column's value, which a malformed relation may report as {@code null}
         * @param identifier   the delimited dataset identifier, for the diagnostic
         * @param rowNumber    the 0-based row number the template is on, for the diagnostic
         * @param recordLength the binding's declared width
         * @return exactly {@code recordLength} characters
         * @throws IllegalStateException if {@code recordImage} is {@code null}
         */
        private String requireRecordImage(String recordImage, String identifier, int rowNumber,
                int recordLength) {
            if (recordImage == null) {
                throw new IllegalStateException("Dataset " + identifier + " presented row " + rowNumber
                        + " with no record image at column position " + RECORD_IMAGE_COLUMN_INDEX
                        + ". A fixed-width dataset row is its record; there is nothing to copy from a "
                        + "row that has none, and treating it as an empty record would load a "
                        + recordLength + "-byte run of spaces the mainframe never wrote.");
            }
            return codec.movePicX(recordImage, recordLength);
        }

        /**
         * The delimited SQL identifier for a binding's dataset, once its name is confirmed to be one.
         *
         * @param binding the binding to address; must not be {@code null}
         * @return the dataset name wrapped in double quotes
         * @throws NullPointerException  if {@code binding} is {@code null}
         * @throws IllegalStateException if the binding names no dataset, or names something that is not a
         *                               well-formed z/OS dataset name
         */
        private static String identifierOf(DatasetBinding binding) {
            Objects.requireNonNull(binding, "A dataset binding is required to address a dataset");
            String dsname = binding.dsname();
            if (dsname == null || dsname.isEmpty()) {
                throw new IllegalStateException("A dataset binding of job '" + JOB_KEY + "' declares no "
                        + "dsname, so there is nothing to address. Declare it under carddemo.datasets, "
                        + "or under carddemo.jobs." + JOB_KEY + ".datasets for a job-scoped override.");
            }
            if (dsname.length() > MAX_DATASET_NAME_LENGTH
                    || !DATASET_NAME.matcher(dsname).matches()) {
                throw new IllegalStateException("'" + dsname + "' is not a z/OS dataset name, so it "
                        + "cannot be rendered as the identifier of a dataset relation. A name is at most "
                        + MAX_DATASET_NAME_LENGTH + " characters of dot-separated qualifiers, each one "
                        + "to eight upper-case characters beginning with a letter or $ # @. Supply a "
                        + "dataset name, or inject a " + DatasetUtilityPort.class.getSimpleName()
                        + " that can address whatever this deployment binds instead.");
            }
            // Delimited so the dots are part of one identifier rather than a qualified reference. A
            // double quote inside the name is impossible under the grammar just enforced, so there is
            // nothing left to escape.
            return "\"" + dsname + "\"";
        }
    }

    // =================================================================================================
    // SYSOUT - //SYSOUT DD SYSOUT=* (app/jcl/CREASTMT.JCL:L82). Every DISPLAY this program performs.
    // =================================================================================================

    /**
     * Where one {@code DISPLAY} statement's line goes.
     *
     * <p>Declared here rather than in the shared package on purpose: each batch program has its own
     * {@code SYSOUT}, and a per-job type means ten sibling job classes can each accept an injected sink
     * without their beans becoming ambiguous with one another.
     *
     * <p>Being a functional interface, a test supplies {@code lines::add} and reads the sequence back in
     * order - which is how the {@code TIOT} prologue's five literals and the ten guard messages are
     * asserted byte for byte.
     */
    @FunctionalInterface
    public interface SysoutSink {

        /**
         * Writes one complete line, exactly as {@code DISPLAY} emits it.
         *
         * <p>The line arrives without a terminator and must not be trimmed, wrapped, re-encoded or
         * decorated. Trailing spaces are significant: {@code TIOCDDNM} is {@code PIC X(08)}, so a
         * six-character DD name is followed by two spaces that belong to the emitted line.
         *
         * @param line the line to emit; never {@code null}
         */
        void write(String line);
    }

    /**
     * The production {@code SYSOUT}: one line per call to the process's standard output stream, in the code
     * page given.
     *
     * <p>Used when the context declares no {@link SysoutSink} bean of its own. The charset is a
     * <strong>parameter</strong> and the platform default is never consulted (practice B8): the lines this
     * program emits are dataset characters and configured DD names, so the honest code page for them is the
     * one the datasets are read in.
     *
     * <p>The stream is auto-flushing, so a line is visible as soon as it is written rather than at process
     * exit, and it is opened on the standard output file descriptor rather than taken from a mutable global,
     * so a caller cannot silently redirect one job's {@code SYSOUT} by reassigning something else. It is
     * deliberately never closed: standard output outlives every job that writes to it, and closing it would
     * silence the rest of the process.
     *
     * @param charset the code page to encode each line in; must not be {@code null}
     * @return a sink writing to standard output
     * @throws NullPointerException if {@code charset} is {@code null}
     */
    public static SysoutSink standardOutput(Charset charset) {
        Objects.requireNonNull(charset, "A code page is required for SYSOUT: a displayed line carries "
                + "dataset characters, and the platform default is never assumed");
        PrintStream stream = new PrintStream(new FileOutputStream(FileDescriptor.out), true, charset);
        return stream::println;
    }

    // =================================================================================================
    // The z/OS control-block walk - app/cbl/CBSTM03A.CBL:L266-L291 - behind a replaceable seam.
    //
    // SET ADDRESS OF PSA-BLOCK TO PSAPTR, then TCB-BLOCK TO TCB-POINT, then TIOT-BLOCK TO TIOT-POINT,
    // then a walk of the TIOT entry chain. Those are real storage addresses in a z/OS address space and no
    // JVM can reach them, so the addressing itself is NOT reproduced and no substitute for it is invented.
    //
    // What IS reproduced is the observable behaviour: five DISPLAY forms, byte for byte, from a stated
    // substitute source. Dropping them because the mechanism cannot be translated would silently delete
    // output the program produces (practice B12).
    // =================================================================================================

    /**
     * One {@code TIOT} entry as the prologue displays it: a DD name and whether it has a unit control
     * block.
     *
     * @param ddName   {@code TIOCDDNM PIC X(08)} ({@code app/cbl/CBSTM03A.CBL:L255}) - fitted to
     *                 {@value #TIOT_NAME_WIDTH} characters on construction, because that is the declared
     *                 width and the padding is part of the emitted line
     * @param validUcb whether {@code UCB-ADDR PIC X(03)} ({@code L257}) holds an address. The COBOL tests
     *                 {@code IF NOT NULL-UCB}, where {@code 88 NULL-UCB VALUES LOW-VALUES} ({@code L258}),
     *                 so {@code true} here is that test succeeding
     */
    public record TiotEntry(String ddName, boolean validUcb) {

        /**
         * Fits the DD name to its declared width.
         *
         * @throws NullPointerException if {@code ddName} is {@code null}
         */
        public TiotEntry {
            Objects.requireNonNull(ddName, "A DD name is required for a TIOT entry; the chain "
                    + "terminator is TiotEntry.terminator()");
            ddName = ddName.length() > TIOT_NAME_WIDTH
                    ? ddName.substring(0, TIOT_NAME_WIDTH)
                    : ddName + " ".repeat(TIOT_NAME_WIDTH - ddName.length());
        }

        /**
         * The entry the walk stops on: a blank name and no unit control block.
         *
         * <p>The COBOL loop exits <em>without</em> displaying the entry it stopped on - its condition is
         * {@code UNTIL END-OF-TIOT OR TIO-LEN = LOW-VALUES} ({@code L276-L277}) - and the
         * {@code IF NOT NULL-UCB} that follows the loop at {@code L287} then displays that very entry,
         * which is why one extra line appears after the real DD names and why it appears with the
         * <em>other</em> null-UCB literal.
         *
         * @return the terminator entry
         */
        public static TiotEntry terminator() {
            return new TiotEntry(" ".repeat(TIOT_NAME_WIDTH), false);
        }
    }

    /**
     * The substitute for what the {@code PSA}/{@code TCB}/{@code TIOT} chain would have yielded.
     *
     * @param jobName    {@code TIOTNJOB PIC X(08)} ({@code app/cbl/CBSTM03A.CBL:L248}) - fitted to
     *                   {@value #TIOT_NAME_WIDTH} characters
     * @param stepName   {@code TIOTJSTP PIC X(08)} ({@code L249}) - fitted to {@value #TIOT_NAME_WIDTH}
     *                   characters
     * @param entries    the allocated DD entries, in the order the walk would meet them; never
     *                   {@code null}, and copied so a caller cannot mutate a live prologue
     * @param terminator the entry the walk stops on, which the post-loop test then displays
     */
    public record TiotImage(String jobName, String stepName, List<TiotEntry> entries,
                            TiotEntry terminator) {

        /**
         * Fits both names to their declared widths and copies the entry list.
         *
         * @throws NullPointerException if any component is {@code null}
         */
        public TiotImage {
            jobName = fitName(jobName, "TIOTNJOB");
            stepName = fitName(stepName, "TIOTJSTP");
            entries = List.copyOf(Objects.requireNonNull(entries, "A TIOT entry list is required; an "
                    + "address space with no data allocations yields an empty list, not null"));
            Objects.requireNonNull(terminator, "A terminator entry is required: the COBOL displays it "
                    + "once after the loop, with a literal that differs from the in-loop one");
        }

        /**
         * Fits one {@code PIC X(08)} name.
         *
         * @param name  the value
         * @param field the COBOL field name, for the diagnostic
         * @return exactly {@value StatementGenerationJobA#TIOT_NAME_WIDTH} characters
         */
        private static String fitName(String name, String field) {
            Objects.requireNonNull(name, "A value is required for " + field + " PIC X(0"
                    + TIOT_NAME_WIDTH + "); move spaces to blank it explicitly");
            return name.length() > TIOT_NAME_WIDTH
                    ? name.substring(0, TIOT_NAME_WIDTH)
                    : name + " ".repeat(TIOT_NAME_WIDTH - name.length());
        }
    }

    /**
     * Supplies the {@link TiotImage} the prologue displays.
     *
     * <p>A seam, so a parity case can pin the prologue's output deterministically and so a deployment that
     * really can enumerate its allocations - a z/OS-hosted JVM, say - can supply the genuine chain without
     * this class changing.
     */
    @FunctionalInterface
    public interface TiotSource {

        /**
         * Reads the task input/output table as this run sees it.
         *
         * @return the image; never {@code null}
         */
        TiotImage read();
    }

    /**
     * The default {@link TiotSource}: {@link #configuredTiotImage()}, read afresh on every run so a
     * configuration change is picked up without the bean being rebuilt.
     *
     * @return the configured source; never {@code null}
     */
    private TiotSource configuredTiotSource() {
        return this::configuredTiotImage;
    }

    /**
     * The configured substitute, used when the context declares no {@link TiotSource} of its own.
     *
     * <p>Every value is derived and every derivation is stated:
     * <ul>
     *   <li>{@code TIOTNJOB} is {@value #JCL_JOB_NAME}, the JCL job name at
     *       {@code app/jcl/CREASTMT.JCL:L1} - which is eight characters, so it fills the field exactly.
     *       It is not the Spring Batch job name, which is a different identifier belonging to a different
     *       system.</li>
     *   <li>{@code TIOTJSTP} is the validated {@value #STEP_040} step contract's name, so what is displayed
     *       is demonstrably the step name configuration declares and the framework runs.</li>
     *   <li>The entries are {@link #STEP_040_DD_NAMES} in JCL declaration order, each classified by whether
     *       its resolved binding names a dataset. A DD with no dataset name has nothing allocated behind it
     *       and therefore no unit control block, which is exactly what {@code 88 NULL-UCB} tests.</li>
     * </ul>
     *
     * <p>Nothing here reads the wall clock, the locale, the platform charset or a directory listing, so two
     * runs of the same configuration emit identical bytes (practice B7).
     *
     * @return the substitute image; never {@code null}
     */
    private TiotImage configuredTiotImage() {
        List<TiotEntry> entries = new ArrayList<>(STEP_040_DD_NAMES.size());
        for (String ddName : STEP_040_DD_NAMES) {
            String dsname = datasetBinding(ddName).dsname();
            entries.add(new TiotEntry(ddName, dsname != null && !dsname.isEmpty()));
        }
        return new TiotImage(JCL_JOB_NAME, stepContract(STEP_040).name(), entries,
                TiotEntry.terminator());
    }

    // =================================================================================================
    // PROCEDURE DIVISION - app/cbl/CBSTM03A.CBL:L262-L923.
    //
    // Runnable with no application context, no JobLauncher and no HTTP layer (practice B10, gate G51), so
    // every branch below is reachable from a plain unit test and the twenty parity cases drive the program
    // directly.
    // =================================================================================================

    /** The single-space delimiter of the two {@code STRING} statements, {@code L462-L481}. */
    private static final String SPACE_DELIMITER = " ";

    /** {@code ST-ADD1}, {@code ST-ADD2} and {@code ST-ADD3}, paired with {@link #HTML_ADDRESS_FIELDS}. */
    private static final List<StatementSlot> HTML_ADDRESS_SLOTS = List.of(
            StatementSlot.ST_ADD1, StatementSlot.ST_ADD2, StatementSlot.ST_ADD3);

    /**
     * {@code ST-ACCT-ID}, {@code ST-CURR-BAL} and {@code ST-FICO-SCORE}, paired with
     * {@link #HTML_BASIC_DETAILS}.
     *
     * <p>{@code ST-CURR-BAL} is read as an <em>already-edited</em> image, which is what
     * {@code app/cbl/CBSTM03A.CBL:L622} does: it sends {@code ST-CURR-BAL}, the edited item of
     * {@code 01 STATEMENT-LINES}, and not {@code ACCT-CURR-BAL}. The HTML writer never re-applies the mask.
     */
    private static final List<StatementSlot> HTML_BASIC_DETAIL_SLOTS = List.of(
            StatementSlot.ST_ACCT_ID, StatementSlot.ST_CURR_BAL, StatementSlot.ST_FICO_SCORE);

    /**
     * Runs the program against the configured {@code SYSOUT} sink.
     *
     * @return how many statements were written, one per cross-reference record
     * @throws AbendException if any open, read or close reports a status this program treats as fatal
     */
    public int printAccountStatements() {
        return printAccountStatements(sysoutSink);
    }

    /**
     * Runs {@code CBSTM03A} once, writing every {@code DISPLAY} to the given sink: the whole of
     * {@code app/cbl/CBSTM03A.CBL:L266-L342}, in order.
     *
     * <p>The sink is a parameter as well as a field so a caller can capture one run's output without
     * reconfiguring the bean - which is exactly what a parity case does.
     *
     * <p><strong>No try-with-resources, deliberately.</strong> {@code CBSTM03A} abends outright, without
     * closing anything, and none of the three handles holds an operating-system resource: a
     * {@link Session} carries a record image and a position, and both writer sinks borrow a pooled
     * connection per record and return it immediately. Wrapping the pass would therefore add close
     * behaviour on the abend path that the COBOL does not have.
     *
     * @param sysout where every displayed line goes; must not be {@code null}
     * @return how many statements were written; {@code 0} when the cross-reference is empty
     * @throws NullPointerException if {@code sysout} is {@code null}
     * @throws AbendException       if any open, read or close reports a status this program treats as
     *                              fatal, carrying {@link #ABEND_RETURN_CODE}
     */
    public int printAccountStatements(SysoutSink sysout) {
        Objects.requireNonNull(sysout, "A SYSOUT sink is required: the displayed line sequence is part "
                + "of this program's observable output, so there is nothing to run without somewhere to "
                + "write it");

        // WORKING-STORAGE, fresh per run. None of it belongs to this singleton bean (practice B9, gate
        // G53), so two concurrent runs share not one byte of table, counter or accumulator.
        WorkingStorage ws = new WorkingStorage(codec, statementSubroutine.newSession());

        // The unnamed paragraph that runs before 0000-START.                                L266-L294
        checkUnitControlBlocks(ws, sysout);

        // Control FALLS THROUGH into 0000-START - there is no PERFORM and no GO TO here.          L296
        boolean reachedMainline = runFileControl(ws, sysout);

        int statements = 0;
        if (reachedMainline) {
            statements = mainline(ws, sysout);
        }

        // 9999-GOBACK.  GOBACK.                                                            L341-L342
        // The run unit ends. Releasing the subroutine's cursors is not a COBOL CLOSE and reports no
        // status; the four CLOSE statements already happened, inside 1000-MAINLINE.
        ws.session().close();
        return statements;
    }

    /**
     * The control-block prologue, {@code app/cbl/CBSTM03A.CBL:L266-L291}.
     *
     * <p>Five {@code DISPLAY} forms in a fixed order: the job and step line, the {@code DD Names from TIOT:}
     * heading, one line per entry inside the loop, and one more line after it for the entry the loop
     * stopped on. The two "null UCB" literals differ between the in-loop and post-loop sites and both are
     * reproduced - see {@link #NULL_UCB_SUFFIX_IN_LOOP} and {@link #NULL_UCB_SUFFIX_AFTER_LOOP}.
     *
     * <p>{@code BUMP-TIOT} is maintained even though the address it would compute is unusable here: it is
     * {@code + LENGTH OF TIOT-BLOCK} once and {@code + LENGTH OF TIOT-SEG} per entry, so after n entries it
     * holds {@code 24 + 20n}. Keeping it makes the walk's arithmetic assertable rather than merely
     * described.
     *
     * @param ws     this run's working storage
     * @param sysout where the five displays go
     */
    private void checkUnitControlBlocks(WorkingStorage ws, SysoutSink sysout) {
        // SET ADDRESS OF PSA-BLOCK TO PSAPTR / TCB-BLOCK / TIOT-BLOCK / TIOT-INDEX.        L266-L269
        // Real z/OS storage addresses; the substitute stands in for what the walk would have found.
        TiotImage tiot = tiotSource.read();

        // DISPLAY 'Running JCL : ' TIOTNJOB ' Step ' TIOTJSTP.                                  L270
        sysout.write(RUNNING_JCL_PREFIX + tiot.jobName() + RUNNING_JCL_STEP_LABEL + tiot.stepName());

        // COMPUTE BUMP-TIOT = BUMP-TIOT + LENGTH OF TIOT-BLOCK.                                 L272
        ws.addToBumpTiot(TIOT_BLOCK_LENGTH);

        // DISPLAY 'DD Names from TIOT: '.  The trailing space is inside the literal.             L275
        sysout.write(DD_NAMES_FROM_TIOT);

        // PERFORM UNTIL END-OF-TIOT OR TIO-LEN = LOW-VALUES ... END-PERFORM.               L276-L285
        for (TiotEntry entry : tiot.entries()) {
            // IF NOT NULL-UCB ... ELSE ... END-IF.                                        L278-L282
            if (entry.validUcb()) {
                sysout.write(TIOT_ENTRY_PREFIX + entry.ddName() + VALID_UCB_SUFFIX);
            } else {
                sysout.write(TIOT_ENTRY_PREFIX + entry.ddName() + NULL_UCB_SUFFIX_IN_LOOP);
            }
            // COMPUTE BUMP-TIOT = BUMP-TIOT + LENGTH OF TIOT-SEG.                              L283
            ws.addToBumpTiot(TIOT_SEG_LENGTH);
        }

        // IF NOT NULL-UCB ... ELSE ... END-IF.  The loop stopped WITHOUT displaying the entry it
        // stopped on, so this displays that entry - and with the other null-UCB literal.    L287-L291
        TiotEntry stopped = tiot.terminator();
        if (stopped.validUcb()) {
            sysout.write(TIOT_ENTRY_PREFIX + stopped.ddName() + VALID_UCB_SUFFIX);
        } else {
            sysout.write(TIOT_ENTRY_PREFIX + stopped.ddName() + NULL_UCB_SUFFIX_AFTER_LOOP);
        }

        // OPEN OUTPUT STMT-FILE HTML-FILE.  Both, in that order, once per run.                  L293
        ws.openOutput(textWriter.openOutput(), htmlWriter.open());

        // INITIALIZE WS-TRNX-TABLE WS-TRN-TBL-CNTR.  Both groups are entirely non-FILLER, so every
        // card number, transaction number, transaction remainder and counter is cleared.        L294
        ws.initializeTrnxTable();
    }

    /**
     * {@code 0000-START} and {@code 8100-FILE-OPEN} - the {@code ALTER}/{@code GO TO} dispatch, restructured
     * (gate G32).
     *
     * <p>The {@code EVALUATE WS-FL-DD} arms are tested in <strong>source order</strong>, which is the order
     * COBOL compares them in, with {@code WHEN OTHER} last (gate G30). An {@code if}-chain rather than a
     * {@code switch} for exactly that reason: a {@code switch} on a string is a hash lookup and would stop
     * being a model of sequential comparison.
     *
     * <p>Every arm's method performs the paragraph's work and then moves the next DD name into
     * {@code WS-FL-DD}, which is the {@code GO TO 0000-START} at the end of each one. Two arms do not:
     * {@code 8400-ACCTFILE-OPEN} branches to {@code 1000-MAINLINE} instead ({@code L815}), leaving
     * {@code WS-FL-DD} untouched, and {@code WHEN OTHER} branches to {@code 9999-GOBACK} ({@code L314}).
     *
     * <p>The resolved order is therefore: {@value #STATE_TRNXFILE} open and first read,
     * {@value #STATE_READTRNX} table load, {@value #STATE_XREFFILE} open, {@value #STATE_CUSTFILE} open,
     * {@value #STATE_ACCTFILE} open, mainline. That order is <strong>asserted</strong> by test and not
     * assumed (practice B12).
     *
     * <p>Package-private rather than private, and deliberately so. {@code WS-FL-DD} is initialised to
     * {@value #STATE_TRNXFILE} and every transition below moves one of the five recognised names into it,
     * so the {@code WHEN OTHER} arm cannot be reached by running the program from the top. It is still a
     * real branch of the source and practice B5 keeps it, which leaves exactly one way to prove it behaves
     * as {@code GO TO 9999-GOBACK} does: hand this method a working storage positioned at an unrecognised
     * state. That is what the test does, and it is the only reason the visibility is not private.
     *
     * @param ws     this run's working storage
     * @param sysout where the guard messages go
     * @return {@code true} when control reached {@code 1000-MAINLINE}, {@code false} when the
     *         {@code WHEN OTHER} arm sent it straight to {@code 9999-GOBACK}
     * @throws AbendException if any open or read on the way reports a fatal status
     */
    boolean runFileControl(WorkingStorage ws, SysoutSink sysout) {
        while (true) {
            String state = ws.wsFlDd();
            // WHEN 'TRNXFILE'  ALTER ... TO PROCEED TO 8100-TRNXFILE-OPEN  GO TO 8100-FILE-OPEN
            if (STATE_TRNXFILE.equals(state)) {
                trnxFileOpen(ws, sysout);
            // WHEN 'XREFFILE'  ALTER ... TO PROCEED TO 8200-XREFFILE-OPEN                  L302-L304
            } else if (STATE_XREFFILE.equals(state)) {
                xrefFileOpen(ws, sysout);
            // WHEN 'CUSTFILE'  ALTER ... TO PROCEED TO 8300-CUSTFILE-OPEN                  L305-L307
            } else if (STATE_CUSTFILE.equals(state)) {
                custFileOpen(ws, sysout);
            // WHEN 'ACCTFILE'  ALTER ... TO PROCEED TO 8400-ACCTFILE-OPEN                  L308-L310
            } else if (STATE_ACCTFILE.equals(state)) {
                acctFileOpen(ws, sysout);
                return true;                                     // GO TO 1000-MAINLINE.        L815
            // WHEN 'READTRNX'  GO TO 8500-READTRNX-READ                                    L311-L312
            } else if (STATE_READTRNX.equals(state)) {
                readTrnxRead(ws, sysout);
            // WHEN OTHER  GO TO 9999-GOBACK.  Unreachable in the observed sequence, and a real
            // branch all the same, so it is kept and it is covered (practice B5).           L313-L314
            } else {
                return false;
            }
        }
    }

    /**
     * {@code 8100-TRNXFILE-OPEN} - open the statement extract and read its first record,
     * {@code app/cbl/CBSTM03A.CBL:L730-L762}.
     *
     * <p>Both guards accept {@code '00'} <strong>or</strong> {@code '04'}. That is worth naming for the
     * read: it is the only read in the program that accepts {@code '04'}, because the loop read at
     * {@code L837} uses a three-way {@code EVALUATE} whose {@code WHEN OTHER} arm abends instead.
     *
     * @param ws     this run's working storage
     * @param sysout where the guard messages go
     * @throws AbendException if the open or the read reports anything but {@code '00'} or {@code '04'}
     */
    private void trnxFileOpen(WorkingStorage ws, SysoutSink sysout) {
        // MOVE 'TRNXFILE' TO WS-M03B-DD.  SET M03B-OPEN TO TRUE.  MOVE ZERO TO WS-M03B-RC.
        // CALL 'CBSTM03B' USING WS-M03B-AREA.                                           L731-L734
        Response opened = statementSubroutine.open(ws.session(), TRNXFILE_DD);
        // IF WS-M03B-RC = '00' OR '04' CONTINUE ELSE DISPLAY ... PERFORM 9999-ABEND.     L736-L742
        if (!isOkOrRecordLengthConflict(opened.rc())) {
            throw reportAndAbend(sysout, ERROR_OPENING_TRNXFILE, opened.rc());
        }

        // SET M03B-READ TO TRUE.  MOVE SPACES TO WS-M03B-FLDT.  CALL 'CBSTM03B'.         L744-L746
        Response read = statementSubroutine.readNext(ws.session(), TRNXFILE_DD);
        // IF WS-M03B-RC = '00' OR '04' - the first read accepts '04' too.                L748-L754
        if (!isOkOrRecordLengthConflict(read.rc())) {
            throw reportAndAbend(sysout, ERROR_READING_TRNXFILE, read.rc());
        }

        // MOVE WS-M03B-FLDT TO TRNX-RECORD.  A group move from PIC X(1000) into a 350-byte
        // receiver, so the leading 350 bytes survive and the padding is discarded.             L756
        ws.moveToTrnxRecord(read);
        // MOVE TRNX-CARD-NUM TO WS-SAVE-CARD.                                                 L757
        ws.moveToWsSaveCard(ws.trnxRecord().readTrnxCardNum());
        // MOVE 1 TO CR-CNT.  MOVE 0 TO TR-CNT.                                           L758-L759
        ws.moveToCrCnt(1);
        ws.moveToTrCnt(0);
        // MOVE 'READTRNX' TO WS-FL-DD.  GO TO 0000-START.                                L760-L761
        ws.moveToWsFlDd(STATE_READTRNX);
    }

    /**
     * {@code 8500-READTRNX-READ} and {@code 8599-EXIT} - the two-dimensional table load,
     * {@code app/cbl/CBSTM03A.CBL:L818-L853}.
     *
     * <p>A <strong>do/while</strong>, not a while: the paragraph is entered with a record already in
     * {@code TRNX-RECORD} - {@code 8100-TRNXFILE-OPEN} read it - so the body runs first, the read happens
     * at the end, and {@code WHEN '00'} branches back to the top ({@code L840}).
     *
     * <p>The card break is the whole of it. {@code TR-CNT} starts at zero and {@code WS-SAVE-CARD} was
     * preset to the first record's card number, so the first pass takes the {@code then} arm and yields
     * {@code TR-CNT = 1}. Every later pass either counts another transaction for the same card, or closes
     * the current card by recording its count, advances {@code CR-CNT} and restarts the count at one.
     *
     * <p>{@code 8599-EXIT} then records the <strong>final</strong> card's count, which is the one the loop
     * never closed, and hands control back to the dispatch with {@code WS-FL-DD} set to
     * {@value #STATE_XREFFILE}.
     *
     * @param ws     this run's working storage
     * @param sysout where the guard messages go
     * @throws AbendException if a read reports anything but {@code '00'} or {@code '10'}
     */
    private void readTrnxRead(WorkingStorage ws, SysoutSink sysout) {
        boolean reading = true;
        while (reading) {
            // IF WS-SAVE-CARD = TRNX-CARD-NUM  ADD 1 TO TR-CNT                           L819-L820
            if (ws.wsSaveCard().equals(ws.trnxRecord().readTrnxCardNum())) {
                ws.addOneToTrCnt();
            // ELSE  MOVE TR-CNT TO WS-TRCT (CR-CNT)  ADD 1 TO CR-CNT  MOVE 1 TO TR-CNT   L821-L825
            } else {
                ws.table().setTrct(ws.crCnt(), ws.trCnt());
                ws.addOneToCrCnt();
                ws.moveToTrCnt(1);
            }

            // MOVE TRNX-CARD-NUM TO WS-CARD-NUM (CR-CNT).                                     L827
            ws.table().setCardNum(ws.crCnt(), ws.trnxRecord().readTrnxCardNum());
            // MOVE TRNX-ID TO WS-TRAN-NUM (CR-CNT, TR-CNT).                                   L828
            ws.table().setTranNum(ws.crCnt(), ws.trCnt(), ws.trnxRecord().readTrnxId());
            // MOVE TRNX-REST TO WS-TRAN-REST (CR-CNT, TR-CNT).                                L829
            ws.table().setTranRest(ws.crCnt(), ws.trCnt(), ws.trnxRecord().readTrnxRest());
            // MOVE TRNX-CARD-NUM TO WS-SAVE-CARD.                                             L830
            ws.moveToWsSaveCard(ws.trnxRecord().readTrnxCardNum());

            // MOVE 'TRNXFILE' TO WS-M03B-DD.  SET M03B-READ TO TRUE.
            // MOVE SPACES TO WS-M03B-FLDT.  CALL 'CBSTM03B' USING WS-M03B-AREA.          L832-L835
            Response read = statementSubroutine.readNext(ws.session(), TRNXFILE_DD);

            // EVALUATE WS-M03B-RC - three arms, in source order.                         L837-L847
            if (FileStatus.isOk(read.rc())) {
                // WHEN '00'  MOVE WS-M03B-FLDT TO TRNX-RECORD  GO TO 8500-READTRNX-READ  L838-L840
                ws.moveToTrnxRecord(read);
            } else if (FileStatus.isEndOfFile(read.rc())) {
                // WHEN '10'  GO TO 8599-EXIT.                                            L841-L842
                reading = false;
            } else {
                // WHEN OTHER  DISPLAY ... PERFORM 9999-ABEND-PROGRAM.                    L843-L846
                throw reportAndAbend(sysout, ERROR_READING_TRNXFILE, read.rc());
            }
        }

        // 8599-EXIT.  MOVE TR-CNT TO WS-TRCT (CR-CNT) - the final card's count.               L850
        ws.table().setTrct(ws.crCnt(), ws.trCnt());
        // MOVE 'XREFFILE' TO WS-FL-DD.  GO TO 0000-START.                                L851-L852
        ws.moveToWsFlDd(STATE_XREFFILE);
    }

    /**
     * {@code 8200-XREFFILE-OPEN}, {@code app/cbl/CBSTM03A.CBL:L765-L781}.
     *
     * @param ws     this run's working storage
     * @param sysout where the guard message goes
     * @throws AbendException if the open reports anything but {@code '00'} or {@code '04'}
     */
    private void xrefFileOpen(WorkingStorage ws, SysoutSink sysout) {
        Response opened = statementSubroutine.open(ws.session(), XREFFILE_DD);
        if (!isOkOrRecordLengthConflict(opened.rc())) {
            throw reportAndAbend(sysout, ERROR_OPENING_XREFFILE, opened.rc());
        }
        // MOVE 'CUSTFILE' TO WS-FL-DD.  GO TO 0000-START.                                L779-L780
        ws.moveToWsFlDd(STATE_CUSTFILE);
    }

    /**
     * {@code 8300-CUSTFILE-OPEN}, {@code app/cbl/CBSTM03A.CBL:L783-L799}.
     *
     * @param ws     this run's working storage
     * @param sysout where the guard message goes
     * @throws AbendException if the open reports anything but {@code '00'} or {@code '04'}
     */
    private void custFileOpen(WorkingStorage ws, SysoutSink sysout) {
        Response opened = statementSubroutine.open(ws.session(), CUSTFILE_DD);
        if (!isOkOrRecordLengthConflict(opened.rc())) {
            throw reportAndAbend(sysout, ERROR_OPENING_CUSTFILE, opened.rc());
        }
        // MOVE 'ACCTFILE' TO WS-FL-DD.  GO TO 0000-START.                                L797-L798
        ws.moveToWsFlDd(STATE_ACCTFILE);
    }

    /**
     * {@code 8400-ACCTFILE-OPEN}, {@code app/cbl/CBSTM03A.CBL:L801-L816}.
     *
     * <p>The one open that does <strong>not</strong> move a next DD name into {@code WS-FL-DD}: it branches
     * to {@code 1000-MAINLINE} instead ({@code L815}), which is what ends the dispatch loop.
     *
     * @param ws     this run's working storage
     * @param sysout where the guard message goes
     * @throws AbendException if the open reports anything but {@code '00'} or {@code '04'}
     */
    private void acctFileOpen(WorkingStorage ws, SysoutSink sysout) {
        Response opened = statementSubroutine.open(ws.session(), ACCTFILE_DD);
        if (!isOkOrRecordLengthConflict(opened.rc())) {
            throw reportAndAbend(sysout, ERROR_OPENING_ACCTFILE, opened.rc());
        }
    }

    /**
     * {@code 1000-MAINLINE} - one statement per cross-reference record, then the five closes,
     * {@code app/cbl/CBSTM03A.CBL:L316-L339}.
     *
     * <p>The outer {@code PERFORM UNTIL END-OF-FILE = 'Y'} immediately contains
     * {@code IF END-OF-FILE = 'N'}, which is redundant by construction: entering the loop already proves
     * the flag is not {@code 'Y'}, and nothing in the program ever moves a third value into it. Both tests
     * are kept - they are two distinct branches against two different literals, and collapsing them would
     * be a change to the program's shape (practice B5). The same applies to {@code MOVE 1 TO CR-JMP} at
     * {@code L324}, which {@code PERFORM VARYING CR-JMP FROM 1} at {@code L417} overwrites before it can be
     * read.
     *
     * <p>The five closes run in a fixed order - {@value #TRNXFILE_DD}, {@value #XREFFILE_DD},
     * {@value #CUSTFILE_DD}, {@value #ACCTFILE_DD}, then both output files - and are reached only from
     * here. The {@code WHEN OTHER} arm of {@code 0000-START} bypasses them entirely, which is exactly what
     * {@code GO TO 9999-GOBACK} does.
     *
     * @param ws     this run's working storage
     * @param sysout where the guard messages go
     * @return how many statements were written
     * @throws AbendException if any read or close reports a fatal status
     */
    private int mainline(WorkingStorage ws, SysoutSink sysout) {
        int statements = 0;

        // PERFORM UNTIL END-OF-FILE = 'Y'                                                      L317
        while (!END_OF_FILE_YES.equals(ws.endOfFile())) {
            // IF END-OF-FILE = 'N' - redundant, PRESERVED (practice B5).                        L318
            if (END_OF_FILE_NO.equals(ws.endOfFile())) {
                // PERFORM 1000-XREFFILE-GET-NEXT                                               L319
                xrefFileGetNext(ws, sysout);
                // IF END-OF-FILE = 'N' - not redundant: the read above may have set it.         L320
                if (END_OF_FILE_NO.equals(ws.endOfFile())) {
                    // PERFORM 2000-CUSTFILE-GET                                                L321
                    custFileGet(ws, sysout);
                    // PERFORM 3000-ACCTFILE-GET                                                L322
                    acctFileGet(ws, sysout);
                    // PERFORM 5000-CREATE-STATEMENT                                            L323
                    createStatement(ws);
                    // MOVE 1 TO CR-JMP - overwritten at L417, PRESERVED (practice B5).         L324
                    ws.moveToCrJmp(1);
                    // MOVE ZERO TO WS-TOTAL-AMT - per customer, not per run.                   L325
                    ws.moveZeroToWsTotalAmt();
                    // PERFORM 4000-TRNXFILE-GET                                                L326
                    trnxFileGet(ws);
                    statements++;
                }
            }
        }

        // PERFORM 9100-TRNXFILE-CLOSE / 9200 / 9300 / 9400, in that order.              L331-L337
        trnxFileClose(ws, sysout);
        xrefFileClose(ws, sysout);
        custFileClose(ws, sysout);
        acctFileClose(ws, sysout);

        // CLOSE STMT-FILE HTML-FILE.  Neither file has a FILE STATUS clause and neither close is
        // guarded, so the reported outcomes are not branched on - inventing a failure path here would
        // add control flow the COBOL does not have.                                             L339
        ws.stmtFile().closeOutput();
        htmlWriter.close(ws.htmlFile());

        return statements;
    }

    /**
     * {@code 1000-XREFFILE-GET-NEXT} - the sequential cross-reference browse,
     * {@code app/cbl/CBSTM03A.CBL:L345-L366}.
     *
     * <p>This is the {@code EVALUATE WS-M03B-RC} shape the whole module treats as its convention: {@code '00'}
     * continues, {@code '10'} raises end of file, anything else is fatal (gate G47).
     *
     * <p><strong>The record move at {@code L364} is unconditional and sits after the {@code EVALUATE}.</strong>
     * On the end-of-file arm the subroutine returns the record area exactly as the caller left it - which
     * is spaces, because {@code L350} moved spaces into it - so the cross-reference record is overwritten
     * with spaces before the loop notices the flag. That ordering is behaviour and is preserved exactly
     * (practice B5). It is also why the record is held as a raw 50-byte image rather than a decoded value:
     * a group {@code MOVE} of spaces into a record whose second and third fields are {@code PIC 9} is
     * perfectly legal in COBOL and must not fail here.
     *
     * @param ws     this run's working storage
     * @param sysout where the guard messages go
     * @throws AbendException if the read reports anything but {@code '00'} or {@code '10'}
     */
    private void xrefFileGetNext(WorkingStorage ws, SysoutSink sysout) {
        // MOVE 'XREFFILE' TO WS-M03B-DD.  SET M03B-READ TO TRUE.  MOVE ZERO TO WS-M03B-RC.
        // MOVE SPACES TO WS-M03B-FLDT.  CALL 'CBSTM03B' USING WS-M03B-AREA.             L347-L351
        Response read = statementSubroutine.readNext(ws.session(), XREFFILE_DD);

        // EVALUATE WS-M03B-RC                                                           L353-L362
        if (FileStatus.isOk(read.rc())) {
            // WHEN '00'  CONTINUE.                                                       L354-L355
            // Empty on purpose: COBOL's CONTINUE is a statement that does nothing, not an
            // unimplemented branch. The arm has to exist, because it is what stops a good read from
            // falling into the end-of-file arm, and putting anything in it would add behaviour.
        } else if (FileStatus.isEndOfFile(read.rc())) {
            // WHEN '10'  MOVE 'Y' TO END-OF-FILE                                        L356-L357
            ws.moveToEndOfFile(END_OF_FILE_YES);
        } else {
            // WHEN OTHER  DISPLAY ... PERFORM 9999-ABEND-PROGRAM                        L358-L361
            throw reportAndAbend(sysout, ERROR_READING_XREFFILE, read.rc());
        }

        // MOVE WS-M03B-FLDT TO CARD-XREF-RECORD - UNCONDITIONAL, including on the EOF arm.  L364
        ws.moveToCardXrefRecord(read);
    }

    /**
     * {@code 2000-CUSTFILE-GET} - the keyed customer read, {@code app/cbl/CBSTM03A.CBL:L368-L390}.
     *
     * <p>The key is {@code XREF-CUST-ID}, moved into {@code WS-M03B-KEY PIC X(25)} as characters, with
     * {@code WS-M03B-KEY-LN} computed as {@code LENGTH OF XREF-CUST-ID} - which is
     * {@value CardXrefRecord#XREF_CUST_ID_LENGTH}, taken from the copybook's own constant rather than
     * written as a literal.
     *
     * <p><strong>There is no {@code '10'} arm.</strong> Two arms only: {@code '00'} and {@code WHEN OTHER}.
     * A cross-reference record naming a customer the master does not hold is therefore fatal, not skipped,
     * and {@code '10'} at this site abends like anything else (gate G47).
     *
     * @param ws     this run's working storage
     * @param sysout where the guard messages go
     * @throws AbendException if the read reports anything but {@code '00'}
     */
    private void custFileGet(WorkingStorage ws, SysoutSink sysout) {
        // MOVE 'CUSTFILE' TO WS-M03B-DD.  SET M03B-READ-K TO TRUE.
        // MOVE XREF-CUST-ID TO WS-M03B-KEY.  MOVE ZERO TO WS-M03B-KEY-LN.
        // COMPUTE WS-M03B-KEY-LN = LENGTH OF XREF-CUST-ID.  MOVE ZERO TO WS-M03B-RC.
        // MOVE SPACES TO WS-M03B-FLDT.  CALL 'CBSTM03B' USING WS-M03B-AREA.             L370-L377
        Response read = statementSubroutine.readByKey(ws.session(), CUSTFILE_DD, ws.xrefCustId(),
                CardXrefRecord.XREF_CUST_ID_LENGTH);

        // EVALUATE WS-M03B-RC  WHEN '00' CONTINUE  WHEN OTHER ... abend.                 L379-L386
        if (!FileStatus.isOk(read.rc())) {
            throw reportAndAbend(sysout, ERROR_READING_CUSTFILE, read.rc());
        }

        // MOVE WS-M03B-FLDT TO CUSTOMER-RECORD - truncated to 500 bytes.                     L388
        ws.moveToCustomerRecord(read);
    }

    /**
     * {@code 3000-ACCTFILE-GET} - the keyed account read, {@code app/cbl/CBSTM03A.CBL:L392-L414}.
     *
     * <p>Identical in shape to {@link #custFileGet}, with {@code XREF-ACCT-ID} and its
     * {@value CardXrefRecord#XREF_ACCT_ID_LENGTH}-byte length, and likewise <strong>no {@code '10}' arm</strong>.
     *
     * @param ws     this run's working storage
     * @param sysout where the guard messages go
     * @throws AbendException if the read reports anything but {@code '00'}
     */
    private void acctFileGet(WorkingStorage ws, SysoutSink sysout) {
        // MOVE 'ACCTFILE' TO WS-M03B-DD.  SET M03B-READ-K TO TRUE.
        // MOVE XREF-ACCT-ID TO WS-M03B-KEY.
        // COMPUTE WS-M03B-KEY-LN = LENGTH OF XREF-ACCT-ID.  CALL 'CBSTM03B'.             L394-L401
        Response read = statementSubroutine.readByKey(ws.session(), ACCTFILE_DD, ws.xrefAcctId(),
                CardXrefRecord.XREF_ACCT_ID_LENGTH);

        // EVALUATE WS-M03B-RC  WHEN '00' CONTINUE  WHEN OTHER ... abend.                 L403-L410
        if (!FileStatus.isOk(read.rc())) {
            throw reportAndAbend(sysout, ERROR_READING_ACCTFILE, read.rc());
        }

        // MOVE WS-M03B-FLDT TO ACCOUNT-RECORD - truncated to 300 bytes.                       L412
        ws.moveToAccountRecord(read);
    }

    /**
     * {@code 4000-TRNXFILE-GET} - the table scan, the customer's transaction total, and the statement's
     * closing records, {@code app/cbl/CBSTM03A.CBL:L416-L456}.
     *
     * <p>Three things about the outer loop matter and each is easy to get wrong:
     * <ul>
     *   <li><strong>{@code PERFORM VARYING ... UNTIL} is test-before.</strong> The condition is evaluated
     *       before the first iteration and again after each increment, so a table whose first card already
     *       sorts past the wanted one is never entered.</li>
     *   <li><strong>The {@code UNTIL} condition short-circuits left to right.</strong> IBM Enterprise COBOL
     *       evaluates {@code A OR B} left to right and stops at the first true operand, so once
     *       {@code CR-JMP > CR-CNT} holds, {@code WS-CARD-NUM (CR-JMP)} is never evaluated - which is
     *       precisely what keeps the subscript inside the table. Java's {@code ||} in that same order
     *       reproduces it; reversing the operands would be an out-of-range read.</li>
     *   <li><strong>The early exit depends on both sequences being in ascending card-number order.</strong>
     *       The table was built from {@value #STEP_010}'s output, which is sorted on
     *       {@code TRAN-CARD-NUM} then {@code TRAN-ID}, and the cross-reference browse runs over a KSDS
     *       whose {@code RECORD KEY} is {@code FD-XREF-CARD-NUM} ({@code app/cbl/CBSTM03B.CBL}). Abandoning
     *       the scan the moment the table's card sorts past the wanted one is correct only because of
     *       that.</li>
     * </ul>
     *
     * <p>The total accumulates through {@link CobolDecimal} at scale exactly 2 with
     * {@link java.math.RoundingMode#DOWN}, matching {@code WS-TOTAL-AMT PIC S9(9)V99 COMP-3} and
     * {@code TRNX-AMT PIC S9(09)V99} (gates G22, G23, G24). It was zeroed per customer at {@code L325},
     * not per run.
     *
     * @param ws this run's working storage
     */
    private void trnxFileGet(WorkingStorage ws) {
        String xrefCardNum = ws.xrefCardNum();

        // PERFORM VARYING CR-JMP FROM 1 BY 1 UNTIL CR-JMP > CR-CNT
        //                                      OR (WS-CARD-NUM (CR-JMP) > XREF-CARD-NUM)  L417-L419
        ws.moveToCrJmp(1);
        while (!(ws.crJmp() > ws.crCnt()
                || ws.table().cardNum(ws.crJmp()).compareTo(xrefCardNum) > 0)) {
            // IF XREF-CARD-NUM = WS-CARD-NUM (CR-JMP)                                          L420
            if (xrefCardNum.equals(ws.table().cardNum(ws.crJmp()))) {
                // MOVE WS-CARD-NUM (CR-JMP) TO TRNX-CARD-NUM                                   L421
                ws.trnxRecord().writeTrnxCardNum(ws.table().cardNum(ws.crJmp()));
                // PERFORM VARYING TR-JMP FROM 1 BY 1 UNTIL (TR-JMP > WS-TRCT (CR-JMP))    L422-L423
                ws.moveToTrJmp(1);
                while (!(ws.trJmp() > ws.table().trct(ws.crJmp()))) {
                    // MOVE WS-TRAN-NUM (CR-JMP, TR-JMP) TO TRNX-ID                        L424-L425
                    ws.trnxRecord().writeTrnxId(ws.table().tranNum(ws.crJmp(), ws.trJmp()));
                    // MOVE WS-TRAN-REST (CR-JMP, TR-JMP) TO TRNX-REST                     L426-L427
                    ws.trnxRecord().writeTrnxRest(ws.table().tranRest(ws.crJmp(), ws.trJmp()));
                    // PERFORM 6000-WRITE-TRANS                                                 L428
                    writeTrans(ws);
                    // ADD TRNX-AMT TO WS-TOTAL-AMT - read back from the span just written,
                    // which is what makes this the new record's amount and not the previous
                    // one's.                                                                   L429
                    ws.addTrnxAmtToWsTotalAmt(ws.trnxRecord().readTrnxAmt());
                    ws.moveToTrJmp(ws.trJmp() + 1);
                }
            }
            ws.moveToCrJmp(ws.crJmp() + 1);
        }

        // MOVE WS-TOTAL-AMT TO WS-TRN-AMT.  MOVE WS-TRN-AMT TO ST-TOTAL-TRAMT.          L433-L434
        ws.moveWsTotalAmtToWsTrnAmt();
        ws.stmtFile().setTotalTransactionAmount(ws.wsTrnAmt());

        // WRITE ST-LINE12, ST-LINE14A, ST-LINE15.                                       L435-L437
        for (StatementLine line : STATEMENT_TOTAL_TEXT_LINES) {
            ws.stmtFile().writeLine(line);
        }

        // The eight closing HTML records.                                               L439-L454
        for (HtmlFixedLine line : HTML_FOOTER_LINES) {
            htmlWriter.writeFixedLine(ws.htmlFile(), line);
        }
    }

    /**
     * {@code 9100-TRNXFILE-CLOSE}, {@code app/cbl/CBSTM03A.CBL:L856-L870}.
     *
     * @param ws     this run's working storage
     * @param sysout where the guard message goes
     * @throws AbendException if the close reports anything but {@code '00'} or {@code '04'}
     */
    private void trnxFileClose(WorkingStorage ws, SysoutSink sysout) {
        Response closed = statementSubroutine.close(ws.session(), TRNXFILE_DD);
        if (!isOkOrRecordLengthConflict(closed.rc())) {
            throw reportAndAbend(sysout, ERROR_CLOSING_TRNXFILE, closed.rc());
        }
    }

    /**
     * {@code 9200-XREFFILE-CLOSE}, {@code app/cbl/CBSTM03A.CBL:L873-L887}.
     *
     * @param ws     this run's working storage
     * @param sysout where the guard message goes
     * @throws AbendException if the close reports anything but {@code '00'} or {@code '04'}
     */
    private void xrefFileClose(WorkingStorage ws, SysoutSink sysout) {
        Response closed = statementSubroutine.close(ws.session(), XREFFILE_DD);
        if (!isOkOrRecordLengthConflict(closed.rc())) {
            throw reportAndAbend(sysout, ERROR_CLOSING_XREFFILE, closed.rc());
        }
    }

    /**
     * {@code 9300-CUSTFILE-CLOSE}, {@code app/cbl/CBSTM03A.CBL:L889-L903}.
     *
     * @param ws     this run's working storage
     * @param sysout where the guard message goes
     * @throws AbendException if the close reports anything but {@code '00'} or {@code '04'}
     */
    private void custFileClose(WorkingStorage ws, SysoutSink sysout) {
        Response closed = statementSubroutine.close(ws.session(), CUSTFILE_DD);
        if (!isOkOrRecordLengthConflict(closed.rc())) {
            throw reportAndAbend(sysout, ERROR_CLOSING_CUSTFILE, closed.rc());
        }
    }

    /**
     * {@code 9400-ACCTFILE-CLOSE}, {@code app/cbl/CBSTM03A.CBL:L905-L919}.
     *
     * @param ws     this run's working storage
     * @param sysout where the guard message goes
     * @throws AbendException if the close reports anything but {@code '00'} or {@code '04'}
     */
    private void acctFileClose(WorkingStorage ws, SysoutSink sysout) {
        Response closed = statementSubroutine.close(ws.session(), ACCTFILE_DD);
        if (!isOkOrRecordLengthConflict(closed.rc())) {
            throw reportAndAbend(sysout, ERROR_CLOSING_ACCTFILE, closed.rc());
        }
    }

    /**
     * Whether a status is one of the two every {@code OPEN} guard, every {@code CLOSE} guard and the first
     * {@value #TRNXFILE_DD} read accepts: {@code '00'} or {@code '04'}.
     *
     * <p>{@code IF WS-M03B-RC = '00' OR '04'} appears at nine sites and nowhere else. The tenth status
     * check, the loop read at {@code app/cbl/CBSTM03A.CBL:L837}, is a three-way {@code EVALUATE} and does
     * <em>not</em> accept {@code '04'}; the two keyed reads accept only {@code '00'}.
     *
     * @param status a two-character {@code FILE STATUS}
     * @return {@code true} for {@code '00'} and for {@code '04'}
     */
    public static boolean isOkOrRecordLengthConflict(String status) {
        return FileStatus.isOk(status) || STATUS_RECORD_LENGTH_CONFLICT.equals(status);
    }

    /**
     * {@code 9999-ABEND-PROGRAM}, {@code app/cbl/CBSTM03A.CBL:L921-L923}, together with the two
     * {@code DISPLAY} statements every one of its ten callers emits first.
     *
     * <p>Reached by {@code PERFORM} rather than {@code GO TO} from ten sites: the four
     * {@code ERROR OPENING} guards, the four {@code ERROR CLOSING} guards, and the {@code ERROR READING}
     * guards for {@value #TRNXFILE_DD} (twice - the first read and the loop read), {@value #XREFFILE_DD},
     * {@value #CUSTFILE_DD} and {@value #ACCTFILE_DD}. All three lines are emitted in order before the
     * exception leaves.
     *
     * <p><strong>Built without an {@code ABCODE} and without a {@code TIMING}.</strong> {@code L923} is
     * {@code CALL 'CEE3ABD'} with no {@code USING} clause - the only abend site in the estate that passes
     * neither - and {@link AbendException} models both as optional precisely for it.
     *
     * <p>The return code is not mapped here. {@code BatchConfig}'s shared step and job listeners carry it
     * onto the step's exit status and the process exit code (gate G35), and duplicating that mapping in a
     * job class would give one job a second opinion about its own exit status.
     *
     * @param sysout  where the three lines go
     * @param message the guard's own {@code DISPLAY} literal
     * @param status  the two-character status the subroutine reported
     * @return the exception to throw, so the call site reads {@code throw reportAndAbend(...)} and the
     *         compiler can see that control does not continue
     */
    private AbendException reportAndAbend(SysoutSink sysout, String message, String status) {
        // DISPLAY 'ERROR <verb> <DD>'.                                        e.g. L739, L844, L865
        sysout.write(message);
        // DISPLAY 'RETURN CODE: ' WS-M03B-RC.                                 e.g. L740, L845, L866
        sysout.write(RETURN_CODE_PREFIX + status);
        // 9999-ABEND-PROGRAM.  DISPLAY 'ABENDING PROGRAM'.                                    L922
        sysout.write(ABENDING_PROGRAM);
        // CALL 'CEE3ABD'.                                                                     L923
        return AbendException.withoutAbendParameters(PROGRAM_ID, ABEND_RETURN_CODE,
                message + "; " + RETURN_CODE_PREFIX + status);
    }

    // =================================================================================================
    // Statement composition. The writers own the bytes; this section owns the sequence, and the sequence is
    // parity-invariant.
    // =================================================================================================

    /**
     * {@code 5000-CREATE-STATEMENT} - one customer's statement heading, in both formats,
     * {@code app/cbl/CBSTM03A.CBL:L458-L504}.
     *
     * <p>Two things about {@code INITIALIZE STATEMENT-LINES} at {@code L459} decide how the rest of the
     * paragraph behaves. It clears only the <strong>eleven named slots</strong>: IBM Enterprise COBOL's
     * {@code INITIALIZE} without {@code REPLACING} does not affect elementary {@code FILLER} items, so
     * every banner, rule line, heading, colon label and dollar sign in the group survives untouched. That
     * is why {@code ST-LINE0} at {@code L460} really does print its asterisk banner, and why the rules and
     * headings print theirs (gate G21). And because it blanks the slots, the two {@code STRING} statements
     * that follow behave identically to an alphanumeric {@code MOVE}: {@code STRING} overwrites only what
     * it transfers, and what it does not reach is already spaces.
     *
     * <p>{@code STRING ... DELIMITED BY ' '} transfers characters up to the <strong>first single space</strong>,
     * so each name and address part is effectively cut at its first blank. That is implemented as COBOL
     * implements it, through {@link StatementHtmlWriter#delimitedBy(String, String)} and
     * {@link FixedWidthCodec#concatenateDelimitedBySize(String...)} - never by trimming, joining or a
     * regular expression, each of which would produce a different answer for a value with an internal
     * space or a value that is all spaces.
     *
     * <p>Note what is <em>not</em> here: {@code ST-TOTAL-TRAMT}. The total belongs to
     * {@link #trnxFileGet}, which runs after this paragraph, and the three lines that carry it are written
     * there.
     *
     * @param ws this run's working storage
     */
    private void createStatement(WorkingStorage ws) {
        StatementFile stmt = ws.stmtFile();
        Stm03CustomerRecord customer = ws.customerRecord();
        AccountRecord account = ws.accountRecord();

        // INITIALIZE STATEMENT-LINES - the eleven named slots only; FILLER survives.            L459
        stmt.initializeStatementLines();

        // WRITE FD-STMTFILE-REC FROM ST-LINE0 - and it is not blank, see above.                 L460
        stmt.writeLine(StatementLine.ST_LINE0);

        // PERFORM 5100-WRITE-HTML-HEADER THRU 5100-EXIT - one method call (AAP 0.7.5).          L461
        writeHtmlHeader(ws);

        // STRING CUST-FIRST-NAME DELIMITED BY ' '  ' ' DELIMITED BY SIZE
        //        CUST-MIDDLE-NAME DELIMITED BY ' '  ' ' DELIMITED BY SIZE
        //        CUST-LAST-NAME DELIMITED BY ' '  ' ' DELIMITED BY SIZE  INTO ST-NAME.     L462-L469
        stmt.setName(codec.concatenateDelimitedBySize(
                StatementHtmlWriter.delimitedBy(customer.custFirstName(), SPACE_DELIMITER),
                StatementHtmlWriter.delimitedBySize(SPACE_DELIMITER),
                StatementHtmlWriter.delimitedBy(customer.custMiddleName(), SPACE_DELIMITER),
                StatementHtmlWriter.delimitedBySize(SPACE_DELIMITER),
                StatementHtmlWriter.delimitedBy(customer.custLastName(), SPACE_DELIMITER),
                StatementHtmlWriter.delimitedBySize(SPACE_DELIMITER)));

        // MOVE CUST-ADDR-LINE-1 TO ST-ADD1.  MOVE CUST-ADDR-LINE-2 TO ST-ADD2.  Both X(50)
        // into X(50), so nothing is padded or discarded for a well-formed record.         L470-L471
        stmt.setAddressLine1(customer.custAddrLine1());
        stmt.setAddressLine2(customer.custAddrLine2());

        // STRING CUST-ADDR-LINE-3 / CUST-ADDR-STATE-CD / CUST-ADDR-COUNTRY-CD / CUST-ADDR-ZIP,
        // each DELIMITED BY ' ' and each followed by ' ' DELIMITED BY SIZE, INTO ST-ADD3.  L472-L481
        stmt.setAddressLine3(codec.concatenateDelimitedBySize(
                StatementHtmlWriter.delimitedBy(customer.custAddrLine3(), SPACE_DELIMITER),
                StatementHtmlWriter.delimitedBySize(SPACE_DELIMITER),
                StatementHtmlWriter.delimitedBy(customer.custAddrStateCd(), SPACE_DELIMITER),
                StatementHtmlWriter.delimitedBySize(SPACE_DELIMITER),
                StatementHtmlWriter.delimitedBy(customer.custAddrCountryCd(), SPACE_DELIMITER),
                StatementHtmlWriter.delimitedBySize(SPACE_DELIMITER),
                StatementHtmlWriter.delimitedBy(customer.custAddrZip(), SPACE_DELIMITER),
                StatementHtmlWriter.delimitedBySize(SPACE_DELIMITER)));

        // MOVE ACCT-ID TO ST-ACCT-ID - 9(11) into X(20): zero-filled on the left to eleven
        // digits, then left justified in an alphanumeric receiver and padded on the right.       L483
        stmt.setAccountId(account.getAcctId());
        // MOVE ACCT-CURR-BAL TO ST-CURR-BAL - S9(10)V99 into PIC 9(9).99-, so the tenth integer
        // digit is discarded. That loss is the COBOL's and is required.                          L484
        stmt.setCurrentBalance(account.getAcctCurrBal());
        // MOVE CUST-FICO-CREDIT-SCORE TO ST-FICO-SCORE - 9(03) into X(20).                       L485
        stmt.setFicoScore(customer.custFicoCreditScoreValue(datasetCharset));

        // PERFORM 5200-WRITE-HTML-NMADBS THRU 5200-EXIT - one method call (AAP 0.7.5).           L486
        writeHtmlNmAdBs(ws);

        // The fifteen plain-text writes, with ST-LINE5 and ST-LINE12 each written twice.  L488-L502
        for (StatementLine line : STATEMENT_BODY_TEXT_LINES) {
            stmt.writeLine(line);
        }
    }

    /**
     * {@code 5100-WRITE-HTML-HEADER THRU 5100-EXIT} - twenty-two HTML records,
     * {@code app/cbl/CBSTM03A.CBL:L506-L555}.
     *
     * <p>Ten fixed lines, then the account heading, then eleven more fixed lines. A {@code PERFORM ... THRU}
     * over a paragraph and its own exit label collapses to a single method call (AAP 0.7.5).
     *
     * <p>{@code MOVE ACCT-ID TO L11-ACCT} at {@code L529} sends {@code ACCT-ID}, the account record's own
     * {@code PIC 9(11)} field - <strong>not</strong> {@code ST-ACCT-ID}, which at this point in the
     * paragraph has not been set yet ({@code L483} runs later). Reading the statement slot here would emit
     * the previous customer's account id on every statement but the first, and spaces on the first.
     *
     * @param ws this run's working storage
     */
    private void writeHtmlHeader(WorkingStorage ws) {
        HtmlStatementFile html = ws.htmlFile();

        // L01, L02, L03, L04, L05, L06, L07, L08, LTRS, L10.                             L508-L527
        for (HtmlFixedLine line : HTML_HEADER_PROLOGUE_LINES) {
            htmlWriter.writeFixedLine(html, line);
        }

        // MOVE ACCT-ID TO L11-ACCT.  WRITE FD-HTMLFILE-REC FROM HTML-L11.                L529-L530
        htmlWriter.writeAccountHeading(html,
                codec.movePic9(ws.accountRecord().getAcctId(), AccountRecord.ACCT_ID_LENGTH));

        // LTDE, LTRE, LTRS, L15, L16, L17, L18, LTDE, LTRE, LTRS, L22-35.                L531-L552
        for (HtmlFixedLine line : HTML_HEADER_BANK_LINES) {
            htmlWriter.writeFixedLine(html, line);
        }
    }

    /**
     * {@code 5200-WRITE-HTML-NMADBS THRU 5200-EXIT} - thirty-four HTML records,
     * {@code app/cbl/CBSTM03A.CBL:L558-L672}.
     *
     * <p>The name line, the three address lines, nine fixed lines, the three basic details, then eighteen
     * fixed lines. Every value is read back from {@code 01 STATEMENT-LINES} as an
     * <strong>already-formatted image</strong>, which is what the COBOL sends: {@code L560} sends
     * {@code ST-NAME}, {@code L571} sends {@code ST-ADD1}, {@code L622} sends {@code ST-CURR-BAL}. The
     * balance in particular is the edited {@code PIC 9(9).99-} item and not {@code ACCT-CURR-BAL}, so the
     * HTML writer never re-applies a mask and cannot disagree with the plain-text statement about a
     * customer's balance.
     *
     * @param ws this run's working storage
     */
    private void writeHtmlNmAdBs(WorkingStorage ws) {
        HtmlStatementFile html = ws.htmlFile();
        StatementFile stmt = ws.stmtFile();

        // MOVE ST-NAME TO L23-NAME.  MOVE SPACES TO FD-HTMLFILE-REC.  STRING ... INTO
        // FD-HTMLFILE-REC.  WRITE FD-HTMLFILE-REC - with no FROM, from the record area.   L560-L568
        htmlWriter.writeNameLine(html, stmt.slotImage(StatementSlot.ST_NAME));

        // The three <p> address lines, built from ST-ADD1, ST-ADD2 and ST-ADD3.           L569-L592
        for (int line = 0; line < HTML_ADDRESS_FIELDS.size(); line++) {
            htmlWriter.writeAddressLine(html, HTML_ADDRESS_FIELDS.get(line),
                    stmt.slotImage(HTML_ADDRESS_SLOTS.get(line)));
        }

        // LTDE, LTRE, LTRS, L30-42, L31, LTDE, LTRE, LTRS, L22-35.                        L594-L611
        for (HtmlFixedLine line : HTML_BASIC_DETAILS_PRELUDE_LINES) {
            htmlWriter.writeFixedLine(html, line);
        }

        // The three labelled detail lines: ST-ACCT-ID, ST-CURR-BAL, ST-FICO-SCORE.        L613-L633
        for (int detail = 0; detail < HTML_BASIC_DETAILS.size(); detail++) {
            htmlWriter.writeBasicDetail(html, HTML_BASIC_DETAILS.get(detail),
                    stmt.slotImage(HTML_BASIC_DETAIL_SLOTS.get(detail)));
        }

        // The Transaction Summary heading and the three column headings.                  L634-L669
        for (HtmlFixedLine line : HTML_COLUMN_HEADING_LINES) {
            htmlWriter.writeFixedLine(html, line);
        }
    }

    /**
     * {@code 6000-WRITE-TRANS} - one transaction detail line in both formats,
     * {@code app/cbl/CBSTM03A.CBL:L675-L723}.
     *
     * <p>One plain-text record then eleven HTML records, in a fixed order: row open, the identifier cell and
     * its value, cell close, the description cell and its value, cell close, the amount cell and its value,
     * cell close, row close.
     *
     * <p>{@code MOVE TRNX-DESC TO ST-TRANDT} moves {@code X(100)} into {@code X(49)}, so the description is
     * truncated on the right and its first forty-nine characters survive. That loss is visible on every
     * statement the program produces and is preserved: the slot is not widened and the description is not
     * abbreviated, wrapped or elided.
     *
     * @param ws this run's working storage
     */
    private void writeTrans(WorkingStorage ws) {
        StatementFile stmt = ws.stmtFile();
        HtmlStatementFile html = ws.htmlFile();
        TrnxRecord trnx = ws.trnxRecord();

        // MOVE TRNX-ID TO ST-TRANID - X(16) into X(16).                                        L676
        stmt.setTransactionId(trnx.readTrnxId());
        // MOVE TRNX-DESC TO ST-TRANDT - X(100) into X(49), truncated on the right.             L677
        stmt.setTransactionDetails(trnx.readTrnxDesc());
        // MOVE TRNX-AMT TO ST-TRANAMT - S9(09)V99 into PIC Z(9).99-.                           L678
        stmt.setTransactionAmount(trnx.readTrnxAmt());
        // WRITE FD-STMTFILE-REC FROM ST-LINE14.                                                L679
        stmt.writeLine(StatementLine.ST_LINE14);

        // SET HTML-LTRS TO TRUE.  WRITE.                                                  L681-L682
        htmlWriter.writeFixedLine(html, HtmlFixedLine.HTML_LTRS);
        // SET HTML-L58 TO TRUE.  WRITE.  Then <p>ST-TRANID</p>, then LTDE.                L684-L694
        htmlWriter.writeFixedLine(html, HtmlFixedLine.HTML_L58);
        htmlWriter.writeTransactionField(html, TransactionField.TRAN_ID,
                stmt.slotImage(StatementSlot.ST_TRANID));
        htmlWriter.writeFixedLine(html, HtmlFixedLine.HTML_LTDE);
        // SET HTML-L61 TO TRUE.  WRITE.  Then <p>ST-TRANDT</p>, then LTDE.                L696-L706
        htmlWriter.writeFixedLine(html, HtmlFixedLine.HTML_L61);
        htmlWriter.writeTransactionField(html, TransactionField.TRAN_DETAILS,
                stmt.slotImage(StatementSlot.ST_TRANDT));
        htmlWriter.writeFixedLine(html, HtmlFixedLine.HTML_LTDE);
        // SET HTML-L64 TO TRUE.  WRITE.  Then <p>ST-TRANAMT</p>, then LTDE.               L708-L718
        htmlWriter.writeFixedLine(html, HtmlFixedLine.HTML_L64);
        htmlWriter.writeTransactionField(html, TransactionField.TRAN_AMOUNT,
                stmt.slotImage(StatementSlot.ST_TRANAMT));
        htmlWriter.writeFixedLine(html, HtmlFixedLine.HTML_LTDE);
        // SET HTML-LTRE TO TRUE.  WRITE.                                                  L720-L721
        htmlWriter.writeFixedLine(html, HtmlFixedLine.HTML_LTRE);
    }

    // =================================================================================================
    // WORKING-STORAGE SECTION - app/cbl/CBSTM03A.CBL:L49-L233.
    //
    // ONE INSTANCE PER RUN, NEVER A FIELD OF THE BEAN. A @Configuration class is a singleton, so holding
    // the table, the counters or the accumulator on it would make one run's state visible to another's
    // loop. Everything below is created by printAccountStatements and discarded when it returns, which is
    // what keeps two concurrent runs independent and what gate G53 requires.
    //
    // The mutators are named after the COBOL statements rather than after Java conventions, so a reviewer
    // can put this class beside the source and read one method per statement. MOVE 1 TO CR-CNT and
    // ADD 1 TO CR-CNT reach the same value by different statements in different paragraphs, and keeping
    // them apart is the whole point of transcribing the transitions instead of simplifying them.
    // =================================================================================================

    /**
     * The whole of {@code CBSTM03A}'s working storage that this translation needs, plus the three handles a
     * run holds open.
     */
    static final class WorkingStorage {

        /** The hand-written codec, used for every group {@code MOVE} and every cross-width transfer. */
        private final FixedWidthCodec codec;

        /** The subroutine session this run's four input files live in. */
        private final Session session;

        /** {@code 01 WS-TRNX-TABLE} and {@code 01 WS-TRN-TBL-CNTR}, {@code L225-L233}. */
        private final TrnxTable table = new TrnxTable();

        /** {@code 05 CR-CNT PIC S9(4) COMP VALUE 0} ({@code L60}) - how many cards the table holds. */
        private int crCnt;

        /** {@code 05 TR-CNT PIC S9(4) COMP VALUE 0} ({@code L61}) - transactions counted for the current card. */
        private int trCnt;

        /** {@code 05 CR-JMP PIC S9(4) COMP VALUE 0} ({@code L62}) - the card subscript of the table scan. */
        private int crJmp;

        /** {@code 05 TR-JMP PIC S9(4) COMP VALUE 0} ({@code L63}) - the transaction subscript of the table scan. */
        private int trJmp;

        /**
         * {@code 05 WS-TOTAL-AMT PIC S9(9)V99 COMP-3 VALUE 0} ({@code L65}).
         *
         * <p>{@code COMP-3} in working storage only - no copybook in the estate declares packed decimal - so
         * this is a {@link BigDecimal} at scale 2 in memory and there is no nibble packing anywhere.
         */
        private BigDecimal wsTotalAmt = CobolDecimal.monetaryZero();

        /** {@code 05 WS-TRN-AMT PIC S9(9)V99 VALUE 0} ({@code L68}) - the total on its way to the edited slot. */
        private BigDecimal wsTrnAmt = CobolDecimal.monetaryZero();

        /** {@code 05 WS-SAVE-CARD VALUE SPACES PIC X(16)} ({@code L69}) - the card the break test compares against. */
        private String wsSaveCard = " ".repeat(WS_SAVE_CARD_LENGTH);

        /** {@code 05 END-OF-FILE PIC X(01) VALUE 'N'} ({@code L70}). */
        private String endOfFile = END_OF_FILE_NO;

        /** {@code 05 WS-FL-DD PIC X(8) VALUE 'TRNXFILE'} ({@code L67}) - the dispatch state. */
        private String wsFlDd = STATE_TRNXFILE;

        /**
         * {@code 01 BUMP-TIOT PIC S9(08) BINARY VALUE ZERO} ({@code L236}).
         *
         * <p>The address offset the control-block walk accumulates. The address itself is unusable outside
         * z/OS; the arithmetic is kept so it stays assertable.
         */
        private int bumpTiot;

        /** {@code COPY COSTM01} - {@code 01 TRNX-RECORD}, the current statement-extract record. */
        private TrnxRecord trnxRecord;

        /**
         * {@code COPY CVACT03Y} - {@code 01 CARD-XREF-RECORD}, held as its <strong>raw 50-byte image</strong>.
         *
         * <p>Raw and not decoded, because {@code app/cbl/CBSTM03A.CBL:L364} moves the record area into this
         * group unconditionally - including on the end-of-file arm, where the area is spaces. A group
         * {@code MOVE} of spaces into a record whose second and third fields are {@code PIC 9} is legal
         * COBOL and must not fail here, so the numeric spans are read as characters and interpreted by
         * nobody.
         */
        private String cardXrefRecord;

        /** {@code COPY CUSTREC} - {@code 01 CUSTOMER-RECORD}, the current customer, 500 bytes. */
        private Stm03CustomerRecord customerRecord;

        /** {@code COPY CVACT01Y} - {@code 01 ACCOUNT-RECORD}, the current account, 300 bytes. */
        private AccountRecord accountRecord;

        /** {@code STMT-FILE} - the open plain-text output handle. */
        private StatementFile stmtFile;

        /** {@code HTML-FILE} - the open HTML output handle. */
        private HtmlStatementFile htmlFile;

        /**
         * Allocates one run's working storage at its declared initial values.
         *
         * @param codec   the codec over the injected dataset code page; must not be {@code null}
         * @param session the subroutine session this run uses; must not be {@code null}
         * @throws NullPointerException if either argument is {@code null}
         */
        WorkingStorage(FixedWidthCodec codec, Session session) {
            this.codec = Objects.requireNonNull(codec, "A codec is required: every group MOVE in this "
                    + "program is a fixed-width transfer in an explicitly named code page");
            this.session = Objects.requireNonNull(session, "A subroutine session is required: all four "
                    + "input files live in CBSTM03B, not here");
            Charset charset = codec.charset();
            this.trnxRecord = TrnxRecord.newRecord(charset);
            this.cardXrefRecord = " ".repeat(CardXrefRecord.RECORD_LENGTH);
            this.customerRecord = Stm03CustomerRecord.blank(charset);
            this.accountRecord = new AccountRecord(charset);
        }

        /**
         * {@code OPEN OUTPUT STMT-FILE HTML-FILE} ({@code L293}) - both handles, in that order, once.
         *
         * @param stmtFile the plain-text handle; must not be {@code null}
         * @param htmlFile the HTML handle; must not be {@code null}
         * @throws NullPointerException if either handle is {@code null}
         */
        void openOutput(StatementFile stmtFile, HtmlStatementFile htmlFile) {
            this.stmtFile = Objects.requireNonNull(stmtFile, "An open STMTFILE handle is required");
            this.htmlFile = Objects.requireNonNull(htmlFile, "An open HTMLFILE handle is required");
        }

        /** {@code INITIALIZE WS-TRNX-TABLE WS-TRN-TBL-CNTR} ({@code L294}). */
        void initializeTrnxTable() {
            table.initialize();
        }

        /**
         * The subroutine session.
         *
         * @return the session; never {@code null}
         */
        Session session() {
            return session;
        }

        /**
         * The two-dimensional table and its counters.
         *
         * @return the table; never {@code null}
         */
        TrnxTable table() {
            return table;
        }

        /**
         * The open plain-text handle.
         *
         * @return the handle
         * @throws IllegalStateException if {@code OPEN OUTPUT} has not run
         */
        StatementFile stmtFile() {
            return Objects.requireNonNull(stmtFile, "STMTFILE is not open; OPEN OUTPUT runs once, in "
                    + "the prologue at app/cbl/CBSTM03A.CBL:L293");
        }

        /**
         * The open HTML handle.
         *
         * @return the handle
         * @throws IllegalStateException if {@code OPEN OUTPUT} has not run
         */
        HtmlStatementFile htmlFile() {
            return Objects.requireNonNull(htmlFile, "HTMLFILE is not open; OPEN OUTPUT runs once, in "
                    + "the prologue at app/cbl/CBSTM03A.CBL:L293");
        }

        /**
         * {@code TRNX-RECORD} - the live record area, so a write followed by a read sees the new bytes.
         *
         * @return the record; never {@code null}
         */
        TrnxRecord trnxRecord() {
            return trnxRecord;
        }

        /**
         * {@code CUSTOMER-RECORD}.
         *
         * @return the record; never {@code null}
         */
        Stm03CustomerRecord customerRecord() {
            return customerRecord;
        }

        /**
         * {@code ACCOUNT-RECORD}.
         *
         * @return the record; never {@code null}
         */
        AccountRecord accountRecord() {
            return accountRecord;
        }

        /**
         * The whole {@code CARD-XREF-RECORD} image.
         *
         * @return exactly {@value CardXrefRecord#RECORD_LENGTH} characters
         */
        String cardXrefRecord() {
            return cardXrefRecord;
        }

        /**
         * {@code XREF-CARD-NUM PIC X(16)} - the cross-reference's card number.
         *
         * @return exactly {@value CardXrefRecord#XREF_CARD_NUM_LENGTH} characters
         */
        String xrefCardNum() {
            return span(CardXrefRecord.XREF_CARD_NUM_OFFSET, CardXrefRecord.XREF_CARD_NUM_LENGTH);
        }

        /**
         * {@code XREF-CUST-ID PIC 9(09)} as characters - what {@code MOVE XREF-CUST-ID TO WS-M03B-KEY}
         * transfers.
         *
         * @return exactly {@value CardXrefRecord#XREF_CUST_ID_LENGTH} characters
         */
        String xrefCustId() {
            return span(CardXrefRecord.XREF_CUST_ID_OFFSET, CardXrefRecord.XREF_CUST_ID_LENGTH);
        }

        /**
         * {@code XREF-ACCT-ID PIC 9(11)} as characters - what {@code MOVE XREF-ACCT-ID TO WS-M03B-KEY}
         * transfers.
         *
         * @return exactly {@value CardXrefRecord#XREF_ACCT_ID_LENGTH} characters
         */
        String xrefAcctId() {
            return span(CardXrefRecord.XREF_ACCT_ID_OFFSET, CardXrefRecord.XREF_ACCT_ID_LENGTH);
        }

        /**
         * One span of the cross-reference image, addressed by the copybook's own offset.
         *
         * <p>The offsets are already 0-based on {@link CardXrefRecord}, so no conversion happens here and
         * none is invented.
         *
         * @param offset the span's 0-based offset
         * @param length the span's width
         * @return exactly {@code length} characters
         */
        private String span(int offset, int length) {
            return cardXrefRecord.substring(offset, offset + length);
        }

        /**
         * {@code MOVE WS-M03B-FLDT TO TRNX-RECORD} ({@code L756} and {@code L839}) - a group move into a
         * 350-byte receiver, so the leading 350 bytes survive.
         *
         * @param response the subroutine's response; must not be {@code null}
         */
        void moveToTrnxRecord(Response response) {
            Objects.requireNonNull(response, "A response is required to move a record area");
            trnxRecord = TrnxRecord.decode(
                    codec.encodeImage(response.fldt(), "a TRNX-RECORD group move"), codec.charset());
        }

        /**
         * {@code MOVE WS-M03B-FLDT TO CARD-XREF-RECORD} ({@code L364}) - a group move into a 50-byte
         * alphanumeric receiver, which is the alphanumeric {@code MOVE} rule at group level.
         *
         * @param response the subroutine's response; must not be {@code null}
         */
        void moveToCardXrefRecord(Response response) {
            Objects.requireNonNull(response, "A response is required to move a record area");
            cardXrefRecord = codec.movePicX(response.fldt(), CardXrefRecord.RECORD_LENGTH);
        }

        /**
         * {@code MOVE WS-M03B-FLDT TO CUSTOMER-RECORD} ({@code L388}) - truncated to 500 bytes.
         *
         * @param response the subroutine's response; must not be {@code null}
         */
        void moveToCustomerRecord(Response response) {
            Objects.requireNonNull(response, "A response is required to move a record area");
            customerRecord = Stm03CustomerRecord.decode(response.fldt(), codec.charset());
        }

        /**
         * {@code MOVE WS-M03B-FLDT TO ACCOUNT-RECORD} ({@code L412}) - truncated to 300 bytes.
         *
         * @param response the subroutine's response; must not be {@code null}
         */
        void moveToAccountRecord(Response response) {
            Objects.requireNonNull(response, "A response is required to move a record area");
            accountRecord = AccountRecord.decode(
                    codec.movePicX(response.fldt(), AccountRecord.RECORD_LENGTH), codec.charset());
        }

        /**
         * {@code MOVE TRNX-CARD-NUM TO WS-SAVE-CARD} ({@code L757} and {@code L830}).
         *
         * @param cardNum the card number; fitted to {@value StatementGenerationJobA#WS_SAVE_CARD_LENGTH}
         *                characters under the alphanumeric {@code MOVE} rule
         */
        void moveToWsSaveCard(String cardNum) {
            wsSaveCard = codec.movePicX(cardNum, WS_SAVE_CARD_LENGTH);
        }

        /**
         * {@code WS-SAVE-CARD}.
         *
         * @return exactly {@value StatementGenerationJobA#WS_SAVE_CARD_LENGTH} characters
         */
        String wsSaveCard() {
            return wsSaveCard;
        }

        /**
         * {@code MOVE 'N'|'Y' TO END-OF-FILE} ({@code L357}).
         *
         * @param value the one-character flag value
         */
        void moveToEndOfFile(String value) {
            endOfFile = Objects.requireNonNull(value, "An END-OF-FILE value is required");
        }

        /**
         * {@code END-OF-FILE}.
         *
         * @return the flag, {@value StatementGenerationJobA#END_OF_FILE_NO} until end of file
         */
        String endOfFile() {
            return endOfFile;
        }

        /**
         * {@code MOVE '<DD>' TO WS-FL-DD} ({@code L760}, {@code L779}, {@code L797}, {@code L851}).
         *
         * @param value the next dispatch state
         */
        void moveToWsFlDd(String value) {
            wsFlDd = Objects.requireNonNull(value, "A WS-FL-DD value is required");
        }

        /**
         * {@code WS-FL-DD}.
         *
         * @return the dispatch state, {@value StatementGenerationJobA#STATE_TRNXFILE} at the start
         */
        String wsFlDd() {
            return wsFlDd;
        }

        /**
         * {@code MOVE 1 TO CR-CNT} ({@code L758}).
         *
         * @param value the value to move
         */
        void moveToCrCnt(int value) {
            crCnt = value;
        }

        /** {@code ADD 1 TO CR-CNT} ({@code L823}). */
        void addOneToCrCnt() {
            crCnt = crCnt + 1;
        }

        /**
         * {@code CR-CNT}.
         *
         * @return how many cards the table holds
         */
        int crCnt() {
            return crCnt;
        }

        /**
         * {@code MOVE 0 TO TR-CNT} ({@code L759}) and {@code MOVE 1 TO TR-CNT} ({@code L824}).
         *
         * @param value the value to move
         */
        void moveToTrCnt(int value) {
            trCnt = value;
        }

        /** {@code ADD 1 TO TR-CNT} ({@code L820}). */
        void addOneToTrCnt() {
            trCnt = trCnt + 1;
        }

        /**
         * {@code TR-CNT}.
         *
         * @return transactions counted for the current card
         */
        int trCnt() {
            return trCnt;
        }

        /**
         * {@code MOVE 1 TO CR-JMP} ({@code L324}) and the {@code PERFORM VARYING} control variable
         * ({@code L417}).
         *
         * @param value the value to move
         */
        void moveToCrJmp(int value) {
            crJmp = value;
        }

        /**
         * {@code CR-JMP}.
         *
         * @return the card subscript of the table scan
         */
        int crJmp() {
            return crJmp;
        }

        /**
         * The {@code PERFORM VARYING TR-JMP} control variable ({@code L422}).
         *
         * @param value the value to move
         */
        void moveToTrJmp(int value) {
            trJmp = value;
        }

        /**
         * {@code TR-JMP}.
         *
         * @return the transaction subscript of the table scan
         */
        int trJmp() {
            return trJmp;
        }

        /**
         * {@code MOVE ZERO TO WS-TOTAL-AMT} ({@code L325}) - once per customer, not once per run.
         *
         * <p>Zero at scale 2, not {@link BigDecimal#ZERO}: a scale-0 zero in a scale-2 field renders the
         * wrong fixed-width image.
         */
        void moveZeroToWsTotalAmt() {
            wsTotalAmt = CobolDecimal.monetaryZero();
        }

        /**
         * {@code ADD TRNX-AMT TO WS-TOTAL-AMT} ({@code L429}) - at scale exactly 2, truncating.
         *
         * @param amount the transaction amount just written into the record area; must not be {@code null}
         */
        void addTrnxAmtToWsTotalAmt(BigDecimal amount) {
            wsTotalAmt = CobolDecimal.add(wsTotalAmt,
                    Objects.requireNonNull(amount, "A transaction amount is required to accumulate"),
                    CobolDecimal.MONETARY_SCALE);
        }

        /**
         * {@code WS-TOTAL-AMT}.
         *
         * @return the running total, at scale exactly {@value CobolDecimal#MONETARY_SCALE}
         */
        BigDecimal wsTotalAmt() {
            return wsTotalAmt;
        }

        /**
         * {@code MOVE WS-TOTAL-AMT TO WS-TRN-AMT} ({@code L433}).
         *
         * <p>Both fields are {@code PIC S9(9)V99}, so this is a store at nine integer digits and scale two:
         * no digit is lost for any value the accumulator can hold, and the store is still performed
         * explicitly rather than assigned, because the receiver's picture is what decides that.
         */
        void moveWsTotalAmtToWsTrnAmt() {
            wsTrnAmt = CobolDecimal.storeAtPicture(wsTotalAmt, TrnxRecord.TRNX_AMT_INTEGER_DIGITS,
                    CobolDecimal.MONETARY_SCALE);
        }

        /**
         * {@code WS-TRN-AMT}.
         *
         * @return the total on its way to {@code ST-TOTAL-TRAMT}
         */
        BigDecimal wsTrnAmt() {
            return wsTrnAmt;
        }

        /**
         * {@code COMPUTE BUMP-TIOT = BUMP-TIOT + LENGTH OF ...} ({@code L272} and {@code L283}).
         *
         * @param length the length to add
         */
        void addToBumpTiot(int length) {
            bumpTiot = bumpTiot + length;
        }

        /**
         * {@code BUMP-TIOT}.
         *
         * @return {@code 24 + 20n} after a walk over n entries
         */
        int bumpTiot() {
            return bumpTiot;
        }
    }

    /**
     * {@code 01 WS-TRNX-TABLE} and {@code 01 WS-TRN-TBL-CNTR} - fifty-one cards, ten transactions each,
     * {@code app/cbl/CBSTM03A.CBL:L225-L233}.
     *
     * <p><strong>COBOL subscripts are 1-based and Java indices are 0-based, and that is the single largest
     * defect risk in this migration</strong> (AAP 0.7.2 names {@code OCCURS} the top one). It is handled the
     * safest way available: each dimension is allocated one element longer than declared and element zero is
     * never used, so a COBOL subscript and a Java index are the same number and there is no arithmetic to get
     * wrong. Every access goes through {@link #subscript}, which refuses zero and refuses anything past the
     * declared {@code OCCURS}, so an off-by-one is a named failure rather than a silent write into the unused
     * slot.
     *
     * <p>The capacity is <strong>fixed at the declared {@code OCCURS}</strong> and is not grown. The COBOL
     * neither tests {@code CR-CNT} against fifty-one nor handles overflow, so no overflow handling is
     * invented; the limit is an inherited constraint of the source and is documented as one.
     */
    static final class TrnxTable {

        /** {@code 10 WS-CARD-NUM PIC X(16)}, one per card, 1-based. */
        private final String[] cardNum = new String[CARD_TABLE_OCCURS + 1];

        /** {@code 15 WS-TRAN-NUM PIC X(16)}, one per card and transaction, both 1-based. */
        private final String[][] tranNum =
                new String[CARD_TABLE_OCCURS + 1][TRAN_TABLE_OCCURS + 1];

        /** {@code 15 WS-TRAN-REST PIC X(318)}, one per card and transaction, both 1-based. */
        private final String[][] tranRest =
                new String[CARD_TABLE_OCCURS + 1][TRAN_TABLE_OCCURS + 1];

        /** {@code 10 WS-TRCT PIC S9(4) COMP}, one per card, 1-based. A binary halfword, so an {@code int}. */
        private final int[] trct = new int[CARD_TABLE_OCCURS + 1];

        /** Allocates the table at its {@code INITIALIZE} values, so it is readable before anything is loaded. */
        TrnxTable() {
            initialize();
        }

        /**
         * {@code INITIALIZE WS-TRNX-TABLE WS-TRN-TBL-CNTR} ({@code L294}).
         *
         * <p>Both groups are entirely non-{@code FILLER}, so {@code INITIALIZE} reaches every item: the
         * alphanumeric items become spaces and the binary counters become zero. Element zero is filled too,
         * which costs nothing and means a stray read of the unused slot yields the same shape as a real one
         * rather than a null.
         */
        void initialize() {
            String blankCardNum = " ".repeat(WS_CARD_NUM_LENGTH);
            String blankTranNum = " ".repeat(WS_TRAN_NUM_LENGTH);
            String blankTranRest = " ".repeat(WS_TRAN_REST_LENGTH);
            for (int card = 0; card <= CARD_TABLE_OCCURS; card++) {
                cardNum[card] = blankCardNum;
                trct[card] = 0;
                for (int tran = 0; tran <= TRAN_TABLE_OCCURS; tran++) {
                    tranNum[card][tran] = blankTranNum;
                    tranRest[card][tran] = blankTranRest;
                }
            }
        }

        /**
         * {@code WS-CARD-NUM (card)}.
         *
         * @param card the 1-based card subscript
         * @return exactly {@value StatementGenerationJobA#WS_CARD_NUM_LENGTH} characters
         * @throws IllegalArgumentException if {@code card} is outside 1..{@value StatementGenerationJobA#CARD_TABLE_OCCURS}
         */
        String cardNum(int card) {
            return cardNum[subscript(card, CARD_TABLE_OCCURS, "WS-CARD-TBL")];
        }

        /**
         * {@code MOVE ... TO WS-CARD-NUM (card)} ({@code L827}).
         *
         * @param card  the 1-based card subscript
         * @param value the card number
         * @throws IllegalArgumentException if {@code card} is out of range
         */
        void setCardNum(int card, String value) {
            cardNum[subscript(card, CARD_TABLE_OCCURS, "WS-CARD-TBL")] =
                    Objects.requireNonNull(value, "A card number is required");
        }

        /**
         * {@code WS-TRAN-NUM (card, tran)}.
         *
         * @param card the 1-based card subscript
         * @param tran the 1-based transaction subscript
         * @return exactly {@value StatementGenerationJobA#WS_TRAN_NUM_LENGTH} characters
         * @throws IllegalArgumentException if either subscript is out of range
         */
        String tranNum(int card, int tran) {
            return tranNum[subscript(card, CARD_TABLE_OCCURS, "WS-CARD-TBL")]
                    [subscript(tran, TRAN_TABLE_OCCURS, "WS-TRAN-TBL")];
        }

        /**
         * {@code MOVE ... TO WS-TRAN-NUM (card, tran)} ({@code L828}).
         *
         * @param card  the 1-based card subscript
         * @param tran  the 1-based transaction subscript
         * @param value the transaction id
         * @throws IllegalArgumentException if either subscript is out of range
         */
        void setTranNum(int card, int tran, String value) {
            tranNum[subscript(card, CARD_TABLE_OCCURS, "WS-CARD-TBL")]
                    [subscript(tran, TRAN_TABLE_OCCURS, "WS-TRAN-TBL")] =
                    Objects.requireNonNull(value, "A transaction id is required");
        }

        /**
         * {@code WS-TRAN-REST (card, tran)}.
         *
         * @param card the 1-based card subscript
         * @param tran the 1-based transaction subscript
         * @return exactly {@value StatementGenerationJobA#WS_TRAN_REST_LENGTH} characters
         * @throws IllegalArgumentException if either subscript is out of range
         */
        String tranRest(int card, int tran) {
            return tranRest[subscript(card, CARD_TABLE_OCCURS, "WS-CARD-TBL")]
                    [subscript(tran, TRAN_TABLE_OCCURS, "WS-TRAN-TBL")];
        }

        /**
         * {@code MOVE ... TO WS-TRAN-REST (card, tran)} ({@code L829}).
         *
         * @param card  the 1-based card subscript
         * @param tran  the 1-based transaction subscript
         * @param value the whole 318-byte remainder of the record
         * @throws IllegalArgumentException if either subscript is out of range
         */
        void setTranRest(int card, int tran, String value) {
            tranRest[subscript(card, CARD_TABLE_OCCURS, "WS-CARD-TBL")]
                    [subscript(tran, TRAN_TABLE_OCCURS, "WS-TRAN-TBL")] =
                    Objects.requireNonNull(value, "A transaction remainder is required");
        }

        /**
         * {@code WS-TRCT (card)} - how many transactions that card has.
         *
         * @param card the 1-based card subscript
         * @return the count, {@code 0} for a card the load never reached
         * @throws IllegalArgumentException if {@code card} is out of range
         */
        int trct(int card) {
            return trct[subscript(card, CARD_TABLE_OCCURS, "WS-TRN-TBL-CTR")];
        }

        /**
         * {@code MOVE TR-CNT TO WS-TRCT (card)} ({@code L822} on a card break and {@code L850} for the final
         * card).
         *
         * @param card  the 1-based card subscript
         * @param value the transaction count to record
         * @throws IllegalArgumentException if {@code card} is out of range
         */
        void setTrct(int card, int value) {
            trct[subscript(card, CARD_TABLE_OCCURS, "WS-TRN-TBL-CTR")] = value;
        }

        /**
         * Validates a COBOL subscript and returns it unchanged as the Java index.
         *
         * <p>Unchanged, because each dimension is allocated one element longer than declared: the conversion
         * is the allocation, not an arithmetic adjustment, so there is no {@code - 1} anywhere to be got
         * wrong. What this method does is refuse the two values that would otherwise be silently wrong -
         * zero, which would address the unused slot, and anything past the declared {@code OCCURS}.
         *
         * @param oneBased the COBOL subscript
         * @param occurs   the declared {@code OCCURS} count
         * @param table    the COBOL table name, for the diagnostic
         * @return {@code oneBased}
         * @throws IllegalArgumentException if {@code oneBased} is not between 1 and {@code occurs} inclusive
         */
        private static int subscript(int oneBased, int occurs, String table) {
            if (oneBased < 1 || oneBased > occurs) {
                throw new IllegalArgumentException("Subscript " + oneBased + " does not address " + table
                        + ", which is declared OCCURS " + occurs + " TIMES in "
                        + "app/cbl/CBSTM03A.CBL:L225-L233. COBOL subscripts run from 1 to " + occurs
                        + " inclusive. The capacity is the source's and is not extended: CBSTM03A neither "
                        + "tests its counters against it nor handles overflow, so growing the table here "
                        + "would give the program behaviour it does not have.");
            }
            return oneBased;
        }
    }
}
