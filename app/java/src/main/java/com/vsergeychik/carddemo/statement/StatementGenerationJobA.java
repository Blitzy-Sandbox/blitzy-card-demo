package com.vsergeychik.carddemo.statement;

import com.vsergeychik.carddemo.account.model.AccountRecord;
import com.vsergeychik.carddemo.card.model.CardXrefRecord;
import com.vsergeychik.carddemo.common.AbendException;
import com.vsergeychik.carddemo.common.CobolDecimal;
import com.vsergeychik.carddemo.common.DatasetRelation;
import com.vsergeychik.carddemo.common.DatasetRelation.BackendDiagnostic;
import com.vsergeychik.carddemo.common.FileStatus;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.FixedWidthRecord;
import com.vsergeychik.carddemo.common.PhysicalSequence;
import com.vsergeychik.carddemo.common.RecordImageForm;
import com.vsergeychik.carddemo.config.BatchConfig;
import com.vsergeychik.carddemo.config.BatchConfig.StepContract;
import com.vsergeychik.carddemo.config.BatchConfig.StopSignal;
import com.vsergeychik.carddemo.config.CobolCharsetConfig;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBinding;
import com.vsergeychik.carddemo.common.DatasetUnitOfWork;
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

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
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
import org.springframework.jdbc.core.PreparedStatementCreator;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * {@code CBSTM03A} - the CardDemo statement report driver, translated field for field and statement for
 * statement from {@code app/cbl/CBSTM03A.CBL} (924 lines, upper-case {@code .CBL}), published as the Spring
 * Batch job that {@code app/jcl/CREASTMT.JCL} runs.
 */
@Configuration(StatementGenerationJobA.CONFIGURATION_BEAN_NAME)
public class StatementGenerationJobA {
    private static final Log LOG = LogFactory.getLog(StatementGenerationJobA.class);

    public static final String CONFIGURATION_BEAN_NAME = "statementGenerationJobAConfiguration";

    /**
     * The COBOL {@code PROGRAM-ID} this class is the translation of: {@code CBSTM03A.CBL:L2}.
     */
    public static final String PROGRAM_ID = "CBSTM03A";

    public static final String JOB_KEY = "statement-generation-job-a";

    public static final String JOB_NAME = "statementGenerationJobA";

    /**
     * The JCL job name, {@code app/jcl/CREASTMT.JCL:L1} ({@code //CREASTMT JOB 'Create Statement'}).
     */
    public static final String JCL_JOB_NAME = "CREASTMT";

    /**
     * {@code app/jcl/CREASTMT.JCL:L22} - {@code EXEC PGM=IDCAMS}, no {@code COND}.
     */
    public static final String STEP_DELDEF01 = "DELDEF01";

    /**
     * {@code app/jcl/CREASTMT.JCL:L44} - {@code EXEC PGM=SORT}, no {@code COND}.
     */
    public static final String STEP_010 = "STEP010";

    /**
     * {@code app/jcl/CREASTMT.JCL:L56} - {@code EXEC PGM=IDCAMS,COND=(0,NE)}.
     */
    public static final String STEP_020 = "STEP020";

    /**
     * {@code app/jcl/CREASTMT.JCL:L66} - {@code EXEC PGM=IEFBR14,COND=(0,NE)}.
     */
    public static final String STEP_030 = "STEP030";

    /**
     * {@code app/jcl/CREASTMT.JCL:L79} - {@code EXEC PGM=CBSTM03A,COND=(0,NE)}, the program itself.
     */
    public static final String STEP_040 = "STEP040";

    /**
     * The five step names in JCL declaration order.
     */
    public static final List<String> STEP_NAMES =
            List.of(STEP_DELDEF01, STEP_010, STEP_020, STEP_030, STEP_040);

    public static final List<String> GATED_STEP_NAMES = List.of(STEP_020, STEP_030, STEP_040);

    /**
     * The utility {@link #STEP_DELDEF01} and {@link #STEP_020} run: {@code EXEC PGM=IDCAMS}
     * ({@code app/jcl/CREASTMT.JCL:L22} and {@code L56}).
     */
    public static final String UTILITY_PROGRAM = "IDCAMS";

    /**
     * The program {@link #STEP_010} runs: {@code EXEC PGM=SORT} ({@code app/jcl/CREASTMT.JCL:L44}).
     */
    public static final String SORT_PROGRAM = "SORT";

    /**
     * The program {@link #STEP_030} runs: {@code EXEC PGM=IEFBR14} ({@code app/jcl/CREASTMT.JCL:L66}) - the
     * no-op utility whose only effect is the {@code DISP} disposition on its DD statements, which is how
     * the statement outputs are deleted before {@link #STEP_040} recreates them.
     */
    public static final String NOOP_PROGRAM = "IEFBR14";

    /**
     * The whole step sequence of {@code app/jcl/CREASTMT.JCL}: five steps, each with the program its
     * {@code EXEC PGM=} names, the last three gated on {@code COND=(0,NE)}.
     */
    public static final List<StepContract> REQUIRED_STEPS = List.of(
            new StepContract(STEP_DELDEF01, UTILITY_PROGRAM, false),
            new StepContract(STEP_010, SORT_PROGRAM, false),
            new StepContract(STEP_020, UTILITY_PROGRAM, true),
            new StepContract(STEP_030, NOOP_PROGRAM, true),
            new StepContract(STEP_040, PROGRAM_ID, true));

    /**
     * {@code //TRNXFILE DD} ({@code CREASTMT.JCL:L83}) - the statement extract KSDS, read by the
     * subroutine.
     */
    public static final String TRNXFILE_DD = StatementGenerationJobB.TRNXFILE_DD;

    /**
     * {@code //XREFFILE DD} ({@code CREASTMT.JCL:L84}) - the card cross-reference, browsed front to back.
     */
    public static final String XREFFILE_DD = StatementGenerationJobB.XREFFILE_DD;

    /**
     * {@code //ACCTFILE DD} ({@code CREASTMT.JCL:L85}) - the account master, read by key.
     */
    public static final String ACCTFILE_DD = StatementGenerationJobB.ACCTFILE_DD;

    /**
     * {@code //CUSTFILE DD} ({@code CREASTMT.JCL:L86}) - the customer master, read by key.
     */
    public static final String CUSTFILE_DD = StatementGenerationJobB.CUSTFILE_DD;

    /**
     * {@code //STMTFILE DD} ({@code CREASTMT.JCL:L87-L91}) - the 80-byte plain-text statement output.
     */
    public static final String STMTFILE_DD = StatementTextWriter.DD_NAME;

    /**
     * {@code //HTMLFILE DD} ({@code CREASTMT.JCL:L92-L96}) - the 100-byte HTML statement output.
     */
    public static final String HTMLFILE_DD = StatementHtmlWriter.HTMLFILE_DD_NAME;

    /**
     * {@code //SORTIN DD} ({@code CREASTMT.JCL:L45}) - the transaction master {@link #STEP_010} reads.
     */
    public static final String SORTIN_DD = "SORTIN";

    /**
     * {@code //SORTOUT DD} ({@code CREASTMT.JCL:L48-L51}) - the intermediate sequential copy.
     */
    public static final String SORTOUT_DD = "SORTOUT";

    /**
     * {@code //INFILE DD} ({@code CREASTMT.JCL:L58}) - the same intermediate file, as {@link #STEP_020}
     * reads it.
     */
    public static final String INFILE_DD = "INFILE";

    /**
     * {@code //OUTFILE DD} ({@code CREASTMT.JCL:L59}) - the work KSDS {@link #STEP_020} loads.
     */
    public static final String OUTFILE_DD = "OUTFILE";

    /**
     * The six data DD names {@code STEP040} allocates, in {@code app/jcl/CREASTMT.JCL:L83-L96} declaration
     * order.
     */
    public static final List<String> STEP_040_DD_NAMES = List.of(
            TRNXFILE_DD, XREFFILE_DD, ACCTFILE_DD, CUSTFILE_DD, STMTFILE_DD, HTMLFILE_DD);

    /**
     * {@code WHEN 'TRNXFILE'} ({@code L299}) and the declared initial value of
     * {@code WS-FL-DD PIC X(8) VALUE 'TRNXFILE'} ({@code L67}) - so this is where the dispatch starts.
     */
    public static final String STATE_TRNXFILE = TRNXFILE_DD;

    public static final String STATE_XREFFILE = XREFFILE_DD;

    /**
     * {@code WHEN 'CUSTFILE'} ({@code L305}), reached from {@code 8200-XREFFILE-OPEN} ({@code L779}).
     */
    public static final String STATE_CUSTFILE = CUSTFILE_DD;

    /**
     * {@code WHEN 'ACCTFILE'} ({@code L308}), reached from {@code 8300-CUSTFILE-OPEN} ({@code L797}).
     */
    public static final String STATE_ACCTFILE = ACCTFILE_DD;

    /**
     * {@code WHEN 'READTRNX'} ({@code L311}), reached from {@code 8100-TRNXFILE-OPEN} ({@code L760}).
     */
    public static final String STATE_READTRNX = "READTRNX";

    /**
     * The sixth arm, {@code WHEN OTHER}, is deliberately absent from this list: it is not a value but the
     * default, it is evaluated last, and it branches straight to {@code 9999-GOBACK}.
     */
    public static final List<String> FILE_CONTROL_STATES = List.of(
            STATE_TRNXFILE, STATE_XREFFILE, STATE_CUSTFILE, STATE_ACCTFILE, STATE_READTRNX);

    /**
     * {@code 05 WS-CARD-TBL OCCURS 51 TIMES} ({@code L226}) - the table holds at most fifty-one cards.
     */
    public static final int CARD_TABLE_OCCURS = 51;

    /**
     * {@code 10 WS-TRAN-TBL OCCURS 10 TIMES} ({@code L228}) - at most ten transactions per card.
     */
    public static final int TRAN_TABLE_OCCURS = 10;

    /**
     * {@code 10 WS-CARD-NUM PIC X(16)} ({@code L227}).
     */
    public static final int WS_CARD_NUM_LENGTH = TrnxRecord.TRNX_CARD_NUM_LENGTH;

    /**
     * {@code 15 WS-TRAN-NUM PIC X(16)} ({@code L229}).
     */
    public static final int WS_TRAN_NUM_LENGTH = TrnxRecord.TRNX_ID_LENGTH;

    /**
     * {@code 15 WS-TRAN-REST PIC X(318)} ({@code L230}) - the whole {@code TRNX-REST} span, moved in and
     * out wholesale and never decomposed inside the table.
     */
    public static final int WS_TRAN_REST_LENGTH = TrnxRecord.TRNX_REST_LENGTH;

    /**
     * {@code 05 WS-SAVE-CARD VALUE SPACES PIC X(16)} ({@code L69}).
     */
    public static final int WS_SAVE_CARD_LENGTH = TrnxRecord.TRNX_CARD_NUM_LENGTH;

    public static final String END_OF_FILE_NO = "N";

    /**
     * {@code 'Y'} - what {@code 1000-XREFFILE-GET-NEXT} moves in at end of file ({@code L357}) and the
     * literal the outer {@code PERFORM UNTIL} tests against ({@code L317}).
     */
    public static final String END_OF_FILE_YES = "Y";

    public static final String STATUS_RECORD_LENGTH_CONFLICT = FileStatus.RECORD_LENGTH_CONFLICT;

    /**
     * {@code DISPLAY 'Running JCL : ' TIOTNJOB ' Step ' TIOTJSTP} - the leading literal, {@code L270}.
     */
    public static final String RUNNING_JCL_PREFIX = "Running JCL : ";

    /**
     * {@code DISPLAY 'Running JCL : ' TIOTNJOB ' Step ' TIOTJSTP} - the middle literal, {@code L270}.
     */
    public static final String RUNNING_JCL_STEP_LABEL = " Step ";

    public static final String DD_NAMES_FROM_TIOT = "DD Names from TIOT: ";

    public static final String TIOT_ENTRY_PREFIX = ": ";

    public static final String VALID_UCB_SUFFIX = " -- valid UCB";

    public static final String NULL_UCB_SUFFIX_IN_LOOP = " --  null UCB";

    public static final String NULL_UCB_SUFFIX_AFTER_LOOP = " -- null  UCB";

    /**
     * {@code TIOTNJOB PIC X(08)}, {@code TIOTJSTP PIC X(08)} and {@code TIOCDDNM PIC X(08)} - all eight.
     */
    public static final int TIOT_NAME_WIDTH = 8;

    /**
     * {@code LENGTH OF TIOT-BLOCK} = 24, added to {@code BUMP-TIOT} once at {@code L272}.
     */
    public static final int TIOT_BLOCK_LENGTH = 3 * TIOT_NAME_WIDTH;

    /**
     * {@code LENGTH OF TIOT-SEG} = 20, added to {@code BUMP-TIOT} once per entry at {@code L283}.
     */
    public static final int TIOT_SEG_LENGTH = 20;

    public static final String ERROR_OPENING_TRNXFILE = "ERROR OPENING TRNXFILE";

    public static final String ERROR_OPENING_XREFFILE = "ERROR OPENING XREFFILE";

    public static final String ERROR_OPENING_CUSTFILE = "ERROR OPENING CUSTFILE";

    public static final String ERROR_OPENING_ACCTFILE = "ERROR OPENING ACCTFILE";

    public static final String ERROR_READING_TRNXFILE = "ERROR READING TRNXFILE";

    public static final String ERROR_READING_XREFFILE = "ERROR READING XREFFILE";

    public static final String ERROR_READING_CUSTFILE = "ERROR READING CUSTFILE";

    public static final String ERROR_READING_ACCTFILE = "ERROR READING ACCTFILE";

    public static final String ERROR_CLOSING_TRNXFILE = "ERROR CLOSING TRNXFILE";

    public static final String ERROR_CLOSING_XREFFILE = "ERROR CLOSING XREFFILE";

    public static final String ERROR_CLOSING_CUSTFILE = "ERROR CLOSING CUSTFILE";

    public static final String ERROR_CLOSING_ACCTFILE = "ERROR CLOSING ACCTFILE";

    /**
     * {@code DISPLAY 'RETURN CODE: ' WS-M03B-RC} - the second line of all ten guard sites.
     */
    public static final String RETURN_CODE_PREFIX = "RETURN CODE: ";

    public static final String ABENDING_PROGRAM = AbendException.ABEND_DISPLAY_TEXT;

    /**
     * The return code this program's abend carries.
     */
    public static final int ABEND_RETURN_CODE = AbendException.RETURN_CODE_ASSUMED_FAILURE;

    static final String SORTOUT_ABNORMAL_DISPOSITION =
            SORTOUT_DD + " DISP=(NEW,CATLG,DELETE) abnormal disposition";

    static final String STMTFILE_ABNORMAL_DISPOSITION =
            STMTFILE_DD + " DISP=(NEW,CATLG,DELETE) abnormal disposition";

    static final String HTMLFILE_ABNORMAL_DISPOSITION =
            HTMLFILE_DD + " DISP=(NEW,CATLG,DELETE) abnormal disposition";

    /**
     * The width of both the sending {@code TRAN-RECORD} and the derived {@code TRNX-RECORD}: 350 bytes.
     */
    public static final int SORT_RECORD_LENGTH = TrnxRecord.RECORD_LENGTH;

    /**
     * {@code SORT FIELDS=(263,16,...)} - the major key: {@code TRAN-CARD-NUM} at JCL position 263, sixteen
     * bytes, character, ascending.
     */
    public static final int SORT_MAJOR_KEY_POSITION = 263;

    /**
     * The major key's width, {@code CH,A} at {@code CREASTMT.JCL:L53}.
     */
    public static final int SORT_MAJOR_KEY_LENGTH = TrnxRecord.TRNX_CARD_NUM_LENGTH;

    /**
     * {@code SORT FIELDS=(...,1,16,CH,A)} - the minor key: {@code TRAN-ID} at JCL position 1, sixteen
     * bytes, character, ascending.
     */
    public static final int SORT_MINOR_KEY_POSITION = 1;

    public static final int SORT_MINOR_KEY_LENGTH = TrnxRecord.TRNX_ID_LENGTH;

    public static final int OUTREC_CARD_NUM_OUTPUT_POSITION = 1;

    public static final int OUTREC_BODY_OUTPUT_POSITION = 17;

    public static final int OUTREC_BODY_INPUT_POSITION = 1;

    /**
     * {@code OUTREC FIELDS=(...,17:1,262,...)} - the second triple's width: bytes 1-262 of the input, which
     * is everything from {@code TRAN-ID} through {@code TRAN-MERCHANT-ZIP}.
     */
    public static final int OUTREC_BODY_LENGTH = 262;

    public static final int OUTREC_TAIL_POSITION = 279;

    /**
     * {@code OUTREC FIELDS=(...,279:279,50)} - the third triple's width: 50, and not 52.
     */
    public static final int OUTREC_TAIL_LENGTH = 50;

    /**
     * {@code KEYS(32 0)} ({@code CREASTMT.JCL:L30}) - a 32-byte key at offset 0.
     */
    public static final int WORK_KSDS_KEY_LENGTH = TrnxRecord.TRNX_KEY_LENGTH;

    public static final int WORK_KSDS_KEY_OFFSET = TrnxRecord.TRNX_KEY_OFFSET;

    /**
     * {@code RECORDSIZE(350 350)} ({@code CREASTMT.JCL:L32}) - average and maximum both 350, so the cluster
     * is fixed-length at the copybook's declared width.
     */
    public static final int WORK_KSDS_RECORD_LENGTH = TrnxRecord.RECORD_LENGTH;

    /**
     * {@code DCB=(LRECL=350,BLKSIZE=3500,RECFM=FB)} ({@code CREASTMT.JCL:L50}) - the intermediate
     * sequential file's block size, ten records to a block.
     */
    public static final int WORK_SEQUENTIAL_BLOCK_SIZE = 3500;

    public static final String ORGANIZATION_KSDS = DatasetBinding.KSDS;

    /**
     * {@code 5000-CREATE-STATEMENT}'s fifteen plain-text writes, {@code app/cbl/CBSTM03A.CBL:L488-L502}.
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
     * {@code app/cbl/CBSTM03A.CBL:L508-L527}.
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
     * {@code app/cbl/CBSTM03A.CBL:L531-L552}: the bank's own name and address block, and the opening of the
     * customer cell.
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
     * {@code 4000-TRNXFILE-GET}'s eight closing HTML records, {@code app/cbl/CBSTM03A.CBL:L439-L454}: the
     * end-of-statement heading and the three document-closing tags.
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

    private final BatchConfig batchConfig;

    private final StatementGenerationJobB statementSubroutine;

    private final StatementTextWriter textWriter;

    private final StatementHtmlWriter htmlWriter;

    private final Charset datasetCharset;

    private final FixedWidthCodec codec;

    private final SysoutSink sysoutSink;

    private final TiotSource tiotSource;

    private final DatasetUtilityPort datasetUtilityPort;

    private final DatasetUnitOfWork unitOfWork;

    private final List<StepContract> stepContracts;

    /**
     * Constructor injection throughout, with the whole JCL contract validated before the job can be built.
     *
     * <p>A parameter declared here would change how this job is identified in the batch metadata and would
     * be an input the COBOL never receives.
     *
     * @param statementSubroutine {@code CBSTM03B}; never {@code null}
     * @param textWriter the {@code STMTFILE} writer; never {@code null}
     * @param htmlWriter the {@code HTMLFILE} writer; never {@code null}
     * @param batchConfig the batch scaffolding; never {@code null}
     * @param datasetCharset the dataset code page, named by bean qualifier
     * @param jdbcTemplate the module's shared template, used only to build the default
     *     {@linkplain DatasetUtilityPort utility port}
     * @param recordImageForm how this deployment's driver presents a record image, from
     *     {@value RecordImageForm#FORM_PROPERTY}
     * @param physicalSequence the deployment's physical-record ordinal, from
     *     {@value PhysicalSequence#EXPRESSION_PROPERTY}
     * @param unitOfWork the dataset unit of work the step's reads and writes run inside
     * @param sysoutSinkProvider provider for an injected {@code SYSOUT} sink; may resolve to no bean, in
     *     which case standard output is used
     * @param tiotSourceProvider provider for an injected {@code TIOT} substitute; may resolve to no bean,
     *     in which case the configured one is used
     * @param datasetUtilityPortProvider provider for an injected utility port; may resolve to no bean, in
     *     which case the {@link JdbcTemplate}-backed default is used
     * @throws NullPointerException if any required argument is {@code null}
     * @throws IllegalStateException if the configured contract is absent, names another program, gates the
     *     wrong steps, or declares a job parameter
     */
    public StatementGenerationJobA(
            StatementGenerationJobB statementSubroutine,
            StatementTextWriter textWriter,
            StatementHtmlWriter htmlWriter,
            BatchConfig batchConfig,
            @Qualifier(CobolCharsetConfig.DATASET_CHARSET_BEAN_NAME) Charset datasetCharset,
            JdbcTemplate jdbcTemplate,
            RecordImageForm recordImageForm,
            PhysicalSequence physicalSequence,
            DatasetUnitOfWork unitOfWork,
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
        Objects.requireNonNull(recordImageForm, "A record-image representation is required to build the "
                + "default dataset utility port: SORT and REPRO move records whole, so whether the driver "
                + "presents an image as characters or as bytes decides whether they survive");
        Objects.requireNonNull(physicalSequence, "A physical-record ordinal is required to build the "
                + "default dataset utility port: SORT and REPRO move records in order, and SQL returns "
                + "rows in no order unless a statement says which. It is stated once, by "
                + PhysicalSequence.EXPRESSION_PROPERTY + ", and never decided per step");
        Objects.requireNonNull(unitOfWork, "A unit of work is required to build the default dataset "
                + "utility port: app/jcl/CREASTMT.JCL:59 binds OUTFILE with DISP=SHR, so a REPRO that "
                + "fails part way leaves what it had already loaded, and each record's insert therefore "
                + "has to persist on its own rather than inside the step's transaction");
        Objects.requireNonNull(sysoutSinkProvider, "A SYSOUT sink provider is required; it may resolve "
                + "to no bean, in which case the standard output stream is used");
        Objects.requireNonNull(tiotSourceProvider, "A TIOT source provider is required; it may resolve "
                + "to no bean, in which case the configured substitute is used");
        Objects.requireNonNull(datasetUtilityPortProvider, "A dataset utility port provider is "
                + "required; it may resolve to no bean, in which case the JDBC-backed default is used");

        this.codec = new FixedWidthCodec(datasetCharset);
        this.unitOfWork = unitOfWork;
        this.stepContracts = requireJclContract(batchConfig);
        this.sysoutSink = sysoutSinkProvider.getIfAvailable(() -> standardOutput(datasetCharset));
        this.tiotSource = tiotSourceProvider.getIfAvailable(this::configuredTiotSource);
        this.datasetUtilityPort = datasetUtilityPortProvider.getIfAvailable(() ->
                new JdbcDatasetUtilityPort(jdbcTemplate, datasetCharset, recordImageForm,
                        physicalSequence, unitOfWork));
    }

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
        scaffolding.requireSteps(JOB_KEY, REQUIRED_STEPS, "app/jcl/CREASTMT.JCL");
        return List.copyOf(contracts);
    }

    /**
     * The job, published as a bean: the five steps of {@code app/jcl/CREASTMT.JCL} in declaration order,
     * with {@code COND=(0,NE)} gates before the last three.
     *
     * <p>The flow reads exactly as the JCL does.
     *
     * @return the {@link #JOB_NAME} job; never {@code null}
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
                .from(beforeStep020).on(BatchConfig.SKIP.getName())
                        .end(BatchConfig.COND_BYPASSED_EXIT_CODE)
                .from(beforeStep030).on(BatchConfig.SKIP.getName())
                        .end(BatchConfig.COND_BYPASSED_EXIT_CODE)
                .from(beforeStep040).on(BatchConfig.SKIP.getName())
                        .end(BatchConfig.COND_BYPASSED_EXIT_CODE)
                .end()
                .build();
    }

    private JobExecutionDecider condGate() {
        return new CondGate(batchConfig.precedingExitCodeZeroDecider());
    }

    /**
     * {@link #STEP_DELDEF01} - {@code EXEC PGM=IDCAMS} ({@code app/jcl/CREASTMT.JCL:L22}), ungated.
     *
     * @return the step; never {@code null}
     */
    public Step deldef01Step() {
        return batchConfig.taskletStep(stepContract(STEP_DELDEF01).name(),
                deleteAndDefineTasklet()).build();
    }

    /**
     * {@link #STEP_010} - {@code EXEC PGM=SORT} ({@code app/jcl/CREASTMT.JCL:L44}), ungated.
     *
     * @return the step; never {@code null}
     */
    public Step step010Step() {
        return batchConfig.taskletStep(stepContract(STEP_010).name(),
                sortAndReformatTasklet()).build();
    }

    /**
     * {@link #STEP_020} - {@code EXEC PGM=IDCAMS,COND=(0,NE)} ({@code app/jcl/CREASTMT.JCL:L56}).
     *
     * @return the step; never {@code null}
     */
    public Step step020Step() {
        return batchConfig.taskletStep(stepContract(STEP_020).name(), reproTasklet()).build();
    }

    /**
     * {@link #STEP_030} - {@code EXEC PGM=IEFBR14,COND=(0,NE)} ({@code app/jcl/CREASTMT.JCL:L66}).
     *
     * @return the step; never {@code null}
     */
    public Step step030Step() {
        return batchConfig.taskletStep(stepContract(STEP_030).name(),
                deletePreviousReportsTasklet()).build();
    }

    /**
     * {@link #STEP_040} - {@code EXEC PGM=CBSTM03A,COND=(0,NE)} ({@code app/jcl/CREASTMT.JCL:L79}), the
     * program itself.
     *
     * <p>{@code CBSTM03A} accumulates across records - the whole two-dimensional table is loaded before the
     * first statement is written, and {@code WS-TOTAL-AMT} is carried across a customer's transactions -
     * and its write ordering is a single pass.
     *
     * @return the step; never {@code null}
     */
    public Step step040Step() {
        return batchConfig.taskletStep(stepContract(STEP_040).name(), statementTasklet()).build();
    }

    /**
     * {@link #STEP_DELDEF01}'s body: one call, one dataset preparation.
     *
     * @return the tasklet; never {@code null}
     */
    public Tasklet deleteAndDefineTasklet() {
        return (contribution, chunkContext) ->
                report(contribution, chunkContext, deleteAndDefineWorkDatasets());
    }

    /**
     * {@link #STEP_010}'s body: one call, one complete sort and reformat.
     *
     * @return the tasklet; never {@code null}
     */
    public Tasklet sortAndReformatTasklet() {
        return (contribution, chunkContext) ->
                report(contribution, chunkContext,
                        sortAndReformatTransactions(StopSignal.of(chunkContext)));
    }

    /**
     * {@link #STEP_020}'s body: one call, one complete load.
     *
     * @return the tasklet; never {@code null}
     */
    public Tasklet reproTasklet() {
        return (contribution, chunkContext) ->
                report(contribution, chunkContext,
                        reproSortedFileIntoWorkDataset(StopSignal.of(chunkContext)));
    }

    /**
     * {@link #STEP_030}'s body: one call, both report datasets cleared.
     *
     * @return the tasklet; never {@code null}
     */
    public Tasklet deletePreviousReportsTasklet() {
        return (contribution, chunkContext) ->
                report(contribution, chunkContext, deletePreviousReportDatasets());
    }

    /**
     * {@link #STEP_040}'s body: a thin adapter over
     * {@link #printAccountStatements(SysoutSink, StopSignal)}.
     *
     * @return the tasklet; never {@code null}
     */
    public Tasklet statementTasklet() {
        return (contribution, chunkContext) ->
                report(contribution, chunkContext,
                        printAccountStatements(sysoutSink, StopSignal.of(chunkContext)));
    }

    private static RepeatStatus report(StepContribution contribution, ChunkContext chunkContext,
            int records) {
        Objects.requireNonNull(chunkContext, "A chunk context is supplied by the framework");
        contribution.incrementWriteCount(records);
        return RepeatStatus.FINISHED;
    }

    /**
     * The job parameters this job is launched with: none.
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
     * @return the resolved sink; never {@code null}
     */
    public SysoutSink sysoutSink() {
        return sysoutSink;
    }

    public TiotSource tiotSource() {
        return tiotSource;
    }

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
     * @param ddName one of the DD names {@code app/jcl/CREASTMT.JCL} declares
     * @return the binding; never {@code null}
     * @throws IllegalStateException if neither this job nor the global catalogue declares it
     */
    public DatasetBinding datasetBinding(String ddName) {
        return batchConfig.datasetBinding(JOB_KEY, ddName);
    }

    /**
     * The {@code COND=(0,NE)} gate as one distinct decision state.
     */
    static final class CondGate implements JobExecutionDecider {
        private final JobExecutionDecider gate;

        CondGate(JobExecutionDecider gate) {
            this.gate = Objects.requireNonNull(gate, "The shared COND=(0,NE) decider is required; this "
                    + "wrapper exists only to give it a distinct decision-state identity");
        }

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

    /**
     * {@link #STEP_DELDEF01} - {@code IDCAMS DELETE} of the two work datasets, then {@code SET MAXCC = 0},
     * then {@code DEFINE CLUSTER} ({@code app/jcl/CREASTMT.JCL:L25-L39}).
     *
     * @return how many records the two clears removed
     * @throws IllegalStateException if the configured geometry does not match the {@code DEFINE CLUSTER}
     *     contract, or if a dataset cannot be addressed
     */
    public int deleteAndDefineWorkDatasets() {
        requireWorkDatasetGeometry();
        int cleared = datasetUtilityPort.deleteAllRecords(datasetBinding(SORTOUT_DD));
        cleared += datasetUtilityPort.deleteAllRecords(datasetBinding(OUTFILE_DD));
        return cleared;
    }

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
     * {@link #STEP_010} - {@code SORT FIELDS=(263,16,CH,A,1,16,CH,A)} and
     * {@code OUTREC FIELDS=(1:263,16,17:1,262,279:279,50)} ({@code app/jcl/CREASTMT.JCL:L53-L54}).
     *
     * <p>Card number then transaction id is exactly the 32-byte {@code TRNX-KEY} order, and it is the
     * reason the card-break grouping in {@link #readTrnxRead} produces one table row per card and the
     * reason the early exit in {@link #trnxFileGet} is correct.
     *
     * @return how many records were written
     * @throws IllegalStateException if either dataset cannot be addressed
     */
    public int sortAndReformatTransactions() {
        return sortAndReformatTransactions(StopSignal.RUNNING);
    }

    /**
     * {@link #STEP_010}, yielding to a stop request between written records.
     *
     * @param stopSignal the between-record cancellation probe; never {@code null}
     * @return how many records were written
     * @throws IllegalStateException if either dataset cannot be addressed
     * @throws BatchConfig.StopRequestedException if the step is asked to stop
     */
    public int sortAndReformatTransactions(StopSignal stopSignal) {
        List<String> input = datasetUtilityPort.readAllRecordImages(datasetBinding(SORTIN_DD));
        boolean completedNormally = false;
        try {
            int written = datasetUtilityPort.writeRecordImages(datasetBinding(SORTOUT_DD),
                    sortAndReformat(input), stopSignal);
            completedNormally = true;
            return written;
        } finally {
            if (!completedNormally) {
                discardSortOutGeneration();
            }
        }
    }

    private void discardSortOutGeneration() {
        try {
            int removed = unitOfWork.persistDisposition(SORTOUT_ABNORMAL_DISPOSITION,
                    () -> datasetUtilityPort.deleteAllRecords(datasetBinding(SORTOUT_DD)));
            if (removed > 0) {
                LOG.info("Discarded " + removed + " record(s) from " + SORTOUT_DD
                        + " because " + STEP_010 + " did not complete normally; "
                        + "app/jcl/CREASTMT.JCL:L49 declares DISP=(NEW,CATLG,DELETE)");
            }
        } catch (RuntimeException dispositionFailure) {
            reportCleanupFailure(SORTOUT_ABNORMAL_DISPOSITION, dispositionFailure);
        }
    }

    /**
     * The {@code OUTREC} reformat as a view over the sorted records, applied one record at a time.
     */
    private final class OutrecView extends AbstractList<String> {
        private final List<String> ordered;

        private OutrecView(List<String> ordered) {
            this.ordered = ordered;
        }

        @Override
        public String get(int index) {
            return applyOutrec(ordered.get(index));
        }

        @Override
        public int size() {
            return ordered.size();
        }
    }

    /**
     * {@link #STEP_020} - {@code REPRO INFILE(INFILE) OUTFILE(OUTFILE)} ({@code app/jcl/CREASTMT.JCL:L61}).
     *
     * @return how many records were loaded
     * @throws IllegalStateException if either dataset cannot be addressed
     */
    public int reproSortedFileIntoWorkDataset() {
        return reproSortedFileIntoWorkDataset(StopSignal.RUNNING);
    }

    /**
     * {@link #STEP_020}, yielding to a stop request between copied records.
     *
     * @param stopSignal the between-record cancellation probe; never {@code null}
     * @return how many records were loaded
     * @throws IllegalStateException if either dataset cannot be addressed
     * @throws BatchConfig.StopRequestedException if the step is asked to stop
     */
    public int reproSortedFileIntoWorkDataset(StopSignal stopSignal) {
        stopSignal.checkStopRequested();
        return datasetUtilityPort.copyRecordImages(datasetBinding(INFILE_DD),
                datasetBinding(OUTFILE_DD));
    }

    /**
     * {@link #STEP_030} - {@code EXEC PGM=IEFBR14} with {@code DISP=(MOD,DELETE,DELETE)} on both report
     * datasets ({@code app/jcl/CREASTMT.JCL:L66-L75}).
     *
     * @return how many records the two clears removed
     * @throws IllegalStateException if either dataset cannot be addressed
     */
    public int deletePreviousReportDatasets() {
        int cleared = datasetUtilityPort.deleteAllRecords(datasetBinding(HTMLFILE_DD));
        cleared += datasetUtilityPort.deleteAllRecords(datasetBinding(STMTFILE_DD));
        return cleared;
    }

    /**
     * {@code SORT FIELDS} followed by {@code OUTREC FIELDS}: the whole of {@link #STEP_010}'s control
     * statements as one function.
     *
     * @param tranRecordImages the {@code TRAN-RECORD} images to sort and reformat, in dataset order; must
     *     not be {@code null} and must contain no {@code null}
     * @return the derived {@code TRNX-RECORD} images, in key order; a new list, the input untouched
     * @throws NullPointerException if {@code tranRecordImages} or any element is {@code null}
     */
    public List<String> sortAndReformat(List<String> tranRecordImages) {
        Objects.requireNonNull(tranRecordImages, "Records are required to sort; an empty transaction "
                + "master yields an empty work dataset and is not an error");
        List<String> ordered = new ArrayList<>(tranRecordImages);
        ordered.sort(SORT_FIELDS_ORDER);
        return new OutrecView(ordered);
    }

    /**
     * {@code SORT FIELDS=(263,16,CH,A,1,16,CH,A)} - ascending {@code TRAN-CARD-NUM}, then ascending
     * {@code TRAN-ID}, both compared as character data.
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
     * @param tranRecordImage one {@code TRAN-RECORD}; fitted to {@link #SORT_RECORD_LENGTH} characters
     *     under the alphanumeric {@code MOVE} rule first
     * @return exactly {@link #SORT_RECORD_LENGTH} characters
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

    private static String jclField(String record, int oneBasedPosition, int length) {
        int from = oneBasedPosition - 1;
        return record.substring(from, from + length);
    }

    private static void overlay(StringBuilder target, int oneBasedPosition, String value) {
        int from = oneBasedPosition - 1;
        target.replace(from, from + value.length(), value);
    }

    /**
     * The three whole-dataset operations {@code IDCAMS} and {@code DFSORT} perform, and nothing else.
     */
    public interface DatasetUtilityPort {
        int deleteAllRecords(DatasetBinding binding);

        List<String> readAllRecordImages(DatasetBinding binding);

        int writeRecordImages(DatasetBinding binding, List<String> recordImages);

        default int copyRecordImages(DatasetBinding source, DatasetBinding target) {
            return writeRecordImages(target, readAllRecordImages(source));
        }

        default int writeRecordImages(DatasetBinding binding, List<String> recordImages,
                StopSignal stopSignal) {
            stopSignal.checkStopRequested();
            return writeRecordImages(binding, recordImages);
        }
    }

    /**
     * The default {@link DatasetUtilityPort}: three ordinary statements over the module's shared
     * {@link JdbcTemplate}.
     */
    public static final class JdbcDatasetUtilityPort implements DatasetUtilityPort {
        private static final int RECORD_IMAGE_COLUMN_INDEX = DatasetRelation.RECORD_IMAGE_COLUMN_INDEX;

        private static final String REPRO_RECORD_VERB =
                "app/jcl/CREASTMT.JCL:61 REPRO INFILE(INFILE) OUTFILE(OUTFILE) - one record";

        private static final int COPY_FETCH_SIZE = 32;

        private final JdbcTemplate jdbcTemplate;

        private final Charset datasetCharset;

        private final FixedWidthCodec codec;

        private final RecordImageForm recordImageForm;

        private final PhysicalSequence physicalSequence;

        private final DatasetUnitOfWork unitOfWork;

        /**
         * Wires the port without a per-record boundary, for a destination whose own disposition discards
         * the whole generation when the step fails.
         *
         * @param jdbcTemplate the module's shared template; must not be {@code null}
         * @param datasetCharset the dataset code page, stated explicitly and never defaulted; must not be
         *     {@code null}
         * @param recordImageForm how the driver presents a record image; must not be {@code null}
         * @param physicalSequence the physical-record ordinal every read is ordered by; must not be
         *     {@code null}
         * @throws NullPointerException if any argument is {@code null}
         * @throws IllegalArgumentException if {@code datasetCharset} is not a total single-byte code page
         */
        public JdbcDatasetUtilityPort(JdbcTemplate jdbcTemplate, Charset datasetCharset,
                RecordImageForm recordImageForm, PhysicalSequence physicalSequence) {
            this(jdbcTemplate, datasetCharset, recordImageForm, physicalSequence, null);
        }

        public JdbcDatasetUtilityPort(JdbcTemplate jdbcTemplate, Charset datasetCharset,
                RecordImageForm recordImageForm, PhysicalSequence physicalSequence,
                DatasetUnitOfWork unitOfWork) {
            this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "A JdbcTemplate is required to "
                    + "address a dataset; the data-source configuration declares the single instance "
                    + "this module shares");
            this.datasetCharset = Objects.requireNonNull(datasetCharset, "A dataset charset "
                    + "is required: a fixed-width record is bytes in a specific code page, which is "
                    + "never derived from the platform");
            this.codec = new FixedWidthCodec(this.datasetCharset);
            this.recordImageForm = Objects.requireNonNull(recordImageForm, "A record-image "
                    + "representation is required: whether this deployment's driver presents a record "
                    + "image as characters or as bytes is stated once, by " + RecordImageForm.FORM_PROPERTY
                    + ", and never decided per dataset or per step");
            this.physicalSequence = Objects.requireNonNull(physicalSequence, "A physical-record ordinal "
                    + "is required: every dataset these utility steps move is physical-sequential and is "
                    + "moved record for record and in order, and SQL returns rows in no order unless a "
                    + "statement says which. It is stated once, by "
                    + PhysicalSequence.EXPRESSION_PROPERTY + ", and never decided per dataset or per "
                    + "step");
            this.unitOfWork = unitOfWork;
            RecordImageForm.requireSingleByteCodePage(datasetCharset);
        }

        @Override
        public int deleteAllRecords(DatasetBinding binding) {
            return jdbcTemplate.update(relationOf(binding).deleteAllStatement());
        }

        @Override
        public List<String> readAllRecordImages(DatasetBinding binding) {
            DatasetRelation relation = relationOf(binding);
            int recordLength = binding.recordLength();
            return jdbcTemplate.query(relation.selectAllInPhysicalSequence(physicalSequence),
                    (row, rowNumber) -> requireRecordImage(readImage(row), relation.identifier(),
                            rowNumber, recordLength));
        }

        @Override
        public int writeRecordImages(DatasetBinding binding, List<String> recordImages) {
            return writeRecordImages(binding, recordImages, StopSignal.RUNNING);
        }

        /**
         * Writes each record with its own statement, yielding to a stop request between records.
         *
         * @param binding the dataset to write; never {@code null}
         * @param recordImages the records, in order; never {@code null} and never containing {@code null}
         * @param stopSignal the between-record cancellation probe; never {@code null}
         * @return how many records were written
         * @throws IllegalStateException if the dataset cannot be addressed
         * @throws BatchConfig.StopRequestedException if the step is asked to stop
         */
        @Override
        public int writeRecordImages(DatasetBinding binding, List<String> recordImages,
                StopSignal stopSignal) {
            Objects.requireNonNull(recordImages, "Records are required to write; pass an empty list to "
                    + "write nothing");
            Objects.requireNonNull(stopSignal, "A stop signal is required; pass StopSignal.RUNNING "
                    + "outside a step, which is what the two-argument overload does");
            String statement = relationOf(binding).insertRecordImage();
            int recordLength = binding.recordLength();
            int written = 0;
            for (String recordImage : recordImages) {
                stopSignal.checkStopRequested();
                written += insert(statement, recordImage, recordLength);
            }
            return written;
        }

        /**
         * {@code REPRO INFILE(INFILE) OUTFILE(OUTFILE)} as a stream: one record read, one record written,
         * in order, with no whole-dataset list anywhere.
         *
         * @param source the dataset to copy from; must not be {@code null}
         * @param target the dataset to copy into; must not be {@code null}
         * @return how many records were copied
         * @throws IllegalStateException if either dataset cannot be addressed
         */
        @Override
        public int copyRecordImages(DatasetBinding source, DatasetBinding target) {
            DatasetRelation from = relationOf(source);
            String insert = relationOf(target).insertRecordImage();
            int sourceLength = source.recordLength();
            int targetLength = target.recordLength();
            RowCounter copied = new RowCounter();
            jdbcTemplate.query(streamed(from.selectAllInPhysicalSequence(physicalSequence)),
                    (ResultSet row) -> {
                while (row.next()) {
                    String recordImage = requireRecordImage(readImage(row), from.identifier(),
                            copied.count(), sourceLength);
                    copied.add(unitOfWork == null
                            ? insert(insert, recordImage, targetLength)
                            : unitOfWork.persistVerb(REPRO_RECORD_VERB,
                                    () -> insert(insert, recordImage, targetLength)));
                }
                return null;
            });
            return copied.count();
        }

        private byte[] readImage(ResultSet row) throws SQLException {
            return recordImageForm.readImage(row, RECORD_IMAGE_COLUMN_INDEX, codec.charset());
        }

        private int insert(String statement, String recordImage, int recordLength) {
            byte[] image = FixedWidthRecord.encodeStrictly(codec.movePicX(recordImage, recordLength),
                    codec.charset(), "a record image being written");
            return jdbcTemplate.update(statement,
                    recordImageForm.imageParameter(image, codec.charset()));
        }

        private static PreparedStatementCreator streamed(String statement) {
            return connection -> {
                PreparedStatement prepared = connection.prepareStatement(statement,
                        ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
                prepared.setFetchSize(COPY_FETCH_SIZE);
                return prepared;
            };
        }

        /**
         * A mutable count, so a streaming extractor can report progress without a field on the port.
         */
        private static final class RowCounter {
            private int count;

            private void add(int rows) {
                count += rows;
            }

            private int count() {
                return count;
            }
        }

        private String requireRecordImage(byte[] recordImage, String identifier, int rowNumber,
                int recordLength) {
            if (recordImage == null) {
                throw new IllegalStateException("Dataset " + identifier + " presented row " + rowNumber
                        + " with no record image at column position " + RECORD_IMAGE_COLUMN_INDEX
                        + ". A fixed-width dataset row is its record; there is nothing to copy from a "
                        + "row that has none, and treating it as an empty record would load a "
                        + recordLength + "-byte run of spaces the mainframe never wrote.");
            }
            return codec.movePicX(FixedWidthRecord.decodeText(recordImage, codec.charset(),
                    "a stored record image of " + identifier), recordLength);
        }

        private static DatasetRelation relationOf(DatasetBinding binding) {
            Objects.requireNonNull(binding, "A dataset binding is required to address a dataset");
            String dsname = binding.dsname();
            if (dsname == null || dsname.isEmpty()) {
                throw new IllegalStateException("A dataset binding of job '" + JOB_KEY + "' declares no "
                        + "dsname, so there is nothing to address. Declare it under carddemo.datasets, "
                        + "or under carddemo.jobs." + JOB_KEY + ".datasets for a job-scoped override.");
            }
            try {
                return DatasetRelation.of(dsname, binding.recordLength());
            } catch (IllegalArgumentException notADatasetName) {
                throw new IllegalStateException("The " + JOB_KEY + " job cannot address the dataset a "
                        + "binding names: " + notADatasetName.getMessage() + " Supply a dataset name, or "
                        + "inject a " + DatasetUtilityPort.class.getSimpleName() + " that can address "
                        + "whatever this deployment binds instead.", notADatasetName);
            }
        }
    }

    @FunctionalInterface
    public interface SysoutSink {
        void write(String line);
    }

    /**
     * The production {@code SYSOUT}: one line per call to the process's standard output stream, in the code
     * page given.
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

    /**
     * One {@code TIOT} entry as the prologue displays it: a DD name and whether it has a unit control
     * block.
     *
     * @param ddName {@code TIOCDDNM PIC X(08)} ({@code app/cbl/CBSTM03A.CBL:L255}) - fitted to
     *     {@value #TIOT_NAME_WIDTH} characters on construction
     * @param validUcb whether {@code UCB-ADDR PIC X(03)} ({@code L257}) holds an address
     */
    public record TiotEntry(String ddName, boolean validUcb) {
        /**
         * Fits the DD name to its declared width.
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
         * @return the terminator entry
         */
        public static TiotEntry terminator() {
            return new TiotEntry(" ".repeat(TIOT_NAME_WIDTH), false);
        }
    }

    /**
     * The substitute for what the {@code PSA}/{@code TCB}/{@code TIOT} chain would have yielded.
     *
     * @param jobName {@code TIOTNJOB PIC X(08)} ({@code app/cbl/CBSTM03A.CBL:L248}) - fitted to
     *     {@value #TIOT_NAME_WIDTH} characters
     * @param stepName {@code TIOTJSTP PIC X(08)} ({@code L249}) - fitted to {@value #TIOT_NAME_WIDTH}
     *     characters
     * @param entries the allocated DD entries, in the order the walk would meet them; never {@code null},
     *     and copied so a caller cannot mutate a live prologue
     * @param terminator the entry the walk stops on, which the post-loop test then displays
     */
    public record TiotImage(String jobName, String stepName, List<TiotEntry> entries,
                            TiotEntry terminator) {
        public TiotImage {
            jobName = fitName(jobName, "TIOTNJOB");
            stepName = fitName(stepName, "TIOTJSTP");
            entries = List.copyOf(Objects.requireNonNull(entries, "A TIOT entry list is required; an "
                    + "address space with no data allocations yields an empty list, not null"));
            Objects.requireNonNull(terminator, "A terminator entry is required: the COBOL displays it "
                    + "once after the loop, with a literal that differs from the in-loop one");
        }

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
     */
    @FunctionalInterface
    public interface TiotSource {
        TiotImage read();
    }

    private TiotSource configuredTiotSource() {
        return this::configuredTiotImage;
    }

    private TiotImage configuredTiotImage() {
        List<TiotEntry> entries = new ArrayList<>(STEP_040_DD_NAMES.size());
        for (String ddName : STEP_040_DD_NAMES) {
            String dsname = datasetBinding(ddName).dsname();
            entries.add(new TiotEntry(ddName, dsname != null && !dsname.isEmpty()));
        }
        return new TiotImage(JCL_JOB_NAME, stepContract(STEP_040).name(), entries,
                TiotEntry.terminator());
    }

    private static final String SPACE_DELIMITER = " ";

    private static final List<StatementSlot> HTML_ADDRESS_SLOTS = List.of(
            StatementSlot.ST_ADD1, StatementSlot.ST_ADD2, StatementSlot.ST_ADD3);

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
     * @param sysout where every displayed line goes; must not be {@code null}
     * @return how many statements were written; {@code 0} when the cross-reference is empty
     * @throws NullPointerException if {@code sysout} is {@code null}
     * @throws AbendException if any open, read or close reports a status this program treats as fatal,
     *     carrying {@link #ABEND_RETURN_CODE}
     */
    public int printAccountStatements(SysoutSink sysout) {
        return printAccountStatements(sysout, StopSignal.RUNNING);
    }

    /**
     * Runs {@code CBSTM03A} once, yielding to the given stop signal between records.
     *
     * @param sysout where every displayed line goes; must not be {@code null}
     * @param stopSignal the between-record cancellation probe; {@link StopSignal#RUNNING} for a caller
     *     outside a step; must not be {@code null}
     * @return how many statements were written; {@code 0} when the cross-reference is empty
     * @throws NullPointerException if {@code sysout} or {@code stopSignal} is {@code null}
     * @throws AbendException if any open, read or close reports a status this program treats as fatal,
     *     carrying {@link #ABEND_RETURN_CODE}
     * @throws BatchConfig.StopRequestedException if the step is asked to stop, which abandons the pass
     *     between records
     */
    public int printAccountStatements(SysoutSink sysout, StopSignal stopSignal) {
        Objects.requireNonNull(sysout, "A SYSOUT sink is required: the displayed line sequence is part "
                + "of this program's observable output, so there is nothing to run without somewhere to "
                + "write it");
        Objects.requireNonNull(stopSignal, "A stop signal is required; pass StopSignal.RUNNING outside a "
                + "step, which is what the single-argument overload does");

        WorkingStorage ws = new WorkingStorage(codec, statementSubroutine.newSession());

        boolean completedNormally = false;
        try {
            checkUnitControlBlocks(ws, sysout);

            boolean reachedMainline = runFileControl(ws, sysout);

            int statements = 0;
            if (reachedMainline) {
                statements = mainline(ws, sysout, stopSignal);
            }

            ws.session().close();
            completedNormally = true;
            return statements;
        } finally {
            releaseHandles(ws, completedNormally);
        }
    }

    private void releaseHandles(WorkingStorage ws, boolean completedNormally) {
        HtmlStatementFile html = ws.htmlFileOrNull();
        if (html != null && html.isOpen()) {
            try {
                htmlWriter.close(html);
            } catch (RuntimeException cleanupFailure) {
                reportCleanupFailure(HTMLFILE_DD, cleanupFailure);
            }
        }
        StatementFile stmt = ws.stmtFileOrNull();
        if (stmt != null && stmt.isOpen()) {
            try {
                stmt.closeOutput();
            } catch (RuntimeException cleanupFailure) {
                reportCleanupFailure(STMTFILE_DD, cleanupFailure);
            }
        }
        if (!completedNormally) {
            discardStatementGenerations(ws);
        }
        try {
            ws.session().close();
        } catch (RuntimeException cleanupFailure) {
            reportCleanupFailure(PROGRAM_ID + " subroutine session", cleanupFailure);
        }
    }

    private void discardStatementGenerations(WorkingStorage ws) {
        StatementFile stmt = ws.stmtFileOrNull();
        if (stmt != null) {
            try {
                FileStatus.Outcome disposition = unitOfWork.persistDisposition(
                        STMTFILE_ABNORMAL_DISPOSITION, stmt::discardGeneration);
                if (disposition != FileStatus.Outcome.OK) {
                    LOG.error("The " + STMTFILE_DD + " generation of this abended run could not be "
                            + "discarded; it reported FILE STATUS outcome " + disposition.name()
                            + ". app/jcl/CREASTMT.JCL:L87 declares DISP=(NEW,CATLG,DELETE), so an "
                            + "incomplete set of plain-text statements may remain where the mainframe "
                            + "would leave none; it must not be issued");
                }
            } catch (RuntimeException dispositionFailure) {
                reportCleanupFailure(STMTFILE_ABNORMAL_DISPOSITION, dispositionFailure);
            }
        }
        HtmlStatementFile html = ws.htmlFileOrNull();
        if (html != null) {
            try {
                FileStatus.Outcome disposition = unitOfWork.persistDisposition(
                        HTMLFILE_ABNORMAL_DISPOSITION, () -> htmlWriter.discardGeneration(html));
                if (disposition != FileStatus.Outcome.OK) {
                    LOG.error("The " + HTMLFILE_DD + " generation of this abended run could not be "
                            + "discarded; it reported FILE STATUS outcome " + disposition.name()
                            + ". app/jcl/CREASTMT.JCL:L92 declares DISP=(NEW,CATLG,DELETE), so "
                            + "unterminated HTML over an incomplete account set may remain where the "
                            + "mainframe would leave none; it must not be issued");
                }
            } catch (RuntimeException dispositionFailure) {
                reportCleanupFailure(HTMLFILE_ABNORMAL_DISPOSITION, dispositionFailure);
            }
        }
    }

    private static void reportCleanupFailure(String what, RuntimeException cleanupFailure) {
        LOG.warn("Releasing " + what + " after an incomplete run failed - "
                + BackendDiagnostic.of(cleanupFailure).describe()
                + ". The run's own outcome is reported to the caller unchanged, because the run's own "
                + "failure is the one that matters.");
    }

    private void checkUnitControlBlocks(WorkingStorage ws, SysoutSink sysout) {
        TiotImage tiot = tiotSource.read();

        sysout.write(RUNNING_JCL_PREFIX + tiot.jobName() + RUNNING_JCL_STEP_LABEL + tiot.stepName());

        ws.addToBumpTiot(TIOT_BLOCK_LENGTH);

        sysout.write(DD_NAMES_FROM_TIOT);

        for (TiotEntry entry : tiot.entries()) {
            if (entry.validUcb()) {
                sysout.write(TIOT_ENTRY_PREFIX + entry.ddName() + VALID_UCB_SUFFIX);
            } else {
                sysout.write(TIOT_ENTRY_PREFIX + entry.ddName() + NULL_UCB_SUFFIX_IN_LOOP);
            }
            ws.addToBumpTiot(TIOT_SEG_LENGTH);
        }

        TiotEntry stopped = tiot.terminator();
        if (stopped.validUcb()) {
            sysout.write(TIOT_ENTRY_PREFIX + stopped.ddName() + VALID_UCB_SUFFIX);
        } else {
            sysout.write(TIOT_ENTRY_PREFIX + stopped.ddName() + NULL_UCB_SUFFIX_AFTER_LOOP);
        }

        // OPEN OUTPUT STMT-FILE HTML-FILE.  Both, in that order, once per run.                  L293
        // Java evaluates arguments left to right, so STMT-FILE is prepared before HTML-FILE, which is the
        // operand order the statement declares. Both handles are registered in working storage before
        // either outcome is examined, deliberately: releaseHandles asks ws for whichever handles exist and
        // applies each one's abnormal disposition, so a generation established here is discarded on the
        // way out rather than left behind. On the mainframe a failure on the first operand would abend
        // before the second was opened, so the second generation would not exist to be discarded; the
        // difference is one that leaves LESS behind, never more, and it is stated rather than assumed.
        ws.openOutput(textWriter.openOutput(), htmlWriter.open());

        // The OPEN is unguarded in the source, which does not mean a failed open is survivable - it means
        // the program has no code that could survive one. A file with no FILE STATUS clause and no
        // declarative terminates the run unit when its OPEN fails, and a run that continued past a refused
        // open would write its records into nothing and report success.
        //
        // Both sinks report their open outcome, so BOTH are checked, and in the statement's own operand
        // order: STMT-FILE first, HTML-FILE second. Checking only the second would let a refused plain-text
        // open reach INITIALIZE WS-TRNX-TABLE below, then the four input opens, then eighty records per
        // account written into a destination that never existed - and the run would report the statements
        // as produced. The order matters for the same reason it matters in the source: when both are
        // unusable, the condition reported is the one the first operand raised.
        requireOutputSucceeded(ws.stmtFile().openOutcome(), STMTFILE_DD, "OPEN OUTPUT STMT-FILE");
        requireOutputSucceeded(FileStatus.outcomeOfStatus(ws.htmlFile().openStatus()), HTMLFILE_DD,
                "OPEN OUTPUT HTML-FILE");

        // INITIALIZE WS-TRNX-TABLE WS-TRN-TBL-CNTR.  Both groups are entirely non-FILLER, so every
        // card number, transaction number, transaction remainder and counter is cleared.        L294
        // Reached only when both output datasets are usable: the two checks above are the last thing
        // between an unusable destination and a pass that would produce nothing while reporting success.
        ws.initializeTrnxTable();
    }

    boolean runFileControl(WorkingStorage ws, SysoutSink sysout) {
        return runFileControl(ws, sysout, StopSignal.RUNNING);
    }

    boolean runFileControl(WorkingStorage ws, SysoutSink sysout, StopSignal stopSignal) {
        while (true) {
            String state = ws.wsFlDd();
            if (STATE_TRNXFILE.equals(state)) {
                trnxFileOpen(ws, sysout);
            } else if (STATE_XREFFILE.equals(state)) {
                xrefFileOpen(ws, sysout);
            } else if (STATE_CUSTFILE.equals(state)) {
                custFileOpen(ws, sysout);
            } else if (STATE_ACCTFILE.equals(state)) {
                acctFileOpen(ws, sysout);
                return true;
            } else if (STATE_READTRNX.equals(state)) {
                readTrnxRead(ws, sysout, stopSignal);
            } else {
                return false;
            }
        }
    }

    private void trnxFileOpen(WorkingStorage ws, SysoutSink sysout) {
        Response opened = statementSubroutine.open(ws.session(), TRNXFILE_DD);
        if (!isOkOrRecordLengthConflict(opened.rc())) {
            throw reportAndAbend(sysout, ERROR_OPENING_TRNXFILE, opened.rc());
        }

        Response read = statementSubroutine.readNext(ws.session(), TRNXFILE_DD);
        if (!isOkOrRecordLengthConflict(read.rc())) {
            throw reportAndAbend(sysout, ERROR_READING_TRNXFILE, read.rc());
        }

        ws.moveToTrnxRecord(read);
        ws.moveToWsSaveCard(ws.trnxRecord().readTrnxCardNum());
        ws.moveToCrCnt(1);
        ws.moveToTrCnt(0);
        ws.moveToWsFlDd(STATE_READTRNX);
    }

    private void readTrnxRead(WorkingStorage ws, SysoutSink sysout, StopSignal stopSignal) {
        boolean reading = true;
        while (reading) {
            stopSignal.checkStopRequested();

            if (ws.wsSaveCard().equals(ws.trnxRecord().readTrnxCardNum())) {
                ws.addOneToTrCnt();
            } else {
                ws.table().setTrct(ws.crCnt(), ws.trCnt());
                ws.addOneToCrCnt();
                ws.moveToTrCnt(1);
            }

            ws.table().setCardNum(ws.crCnt(), ws.trnxRecord().readTrnxCardNum());
            ws.table().setTranNum(ws.crCnt(), ws.trCnt(), ws.trnxRecord().readTrnxId());
            ws.table().setTranRest(ws.crCnt(), ws.trCnt(), ws.trnxRecord().readTrnxRest());
            ws.moveToWsSaveCard(ws.trnxRecord().readTrnxCardNum());

            Response read = statementSubroutine.readNext(ws.session(), TRNXFILE_DD);

            if (FileStatus.isOk(read.rc())) {
                ws.moveToTrnxRecord(read);
            } else if (FileStatus.isEndOfFile(read.rc())) {
                reading = false;
            } else {
                throw reportAndAbend(sysout, ERROR_READING_TRNXFILE, read.rc());
            }
        }

        ws.table().setTrct(ws.crCnt(), ws.trCnt());
        ws.moveToWsFlDd(STATE_XREFFILE);
    }

    private void xrefFileOpen(WorkingStorage ws, SysoutSink sysout) {
        Response opened = statementSubroutine.open(ws.session(), XREFFILE_DD);
        if (!isOkOrRecordLengthConflict(opened.rc())) {
            throw reportAndAbend(sysout, ERROR_OPENING_XREFFILE, opened.rc());
        }
        ws.moveToWsFlDd(STATE_CUSTFILE);
    }

    private void custFileOpen(WorkingStorage ws, SysoutSink sysout) {
        Response opened = statementSubroutine.open(ws.session(), CUSTFILE_DD);
        if (!isOkOrRecordLengthConflict(opened.rc())) {
            throw reportAndAbend(sysout, ERROR_OPENING_CUSTFILE, opened.rc());
        }
        ws.moveToWsFlDd(STATE_ACCTFILE);
    }

    private void acctFileOpen(WorkingStorage ws, SysoutSink sysout) {
        Response opened = statementSubroutine.open(ws.session(), ACCTFILE_DD);
        if (!isOkOrRecordLengthConflict(opened.rc())) {
            throw reportAndAbend(sysout, ERROR_OPENING_ACCTFILE, opened.rc());
        }
    }

    private int mainline(WorkingStorage ws, SysoutSink sysout, StopSignal stopSignal) {
        int statements = 0;

        while (!END_OF_FILE_YES.equals(ws.endOfFile())) {
            // The between-statement yield to a stop request: a call rather than a condition, so it adds no
            // arm to the translated control flow, and positioned before 1000-XREFFILE-GET-NEXT so a
            // statement is never abandoned half written.
            stopSignal.checkStopRequested();

            if (END_OF_FILE_NO.equals(ws.endOfFile())) {
                xrefFileGetNext(ws, sysout);
                if (END_OF_FILE_NO.equals(ws.endOfFile())) {
                    custFileGet(ws, sysout);
                    acctFileGet(ws, sysout);
                    createStatement(ws);
                    ws.moveToCrJmp(1);
                    ws.moveZeroToWsTotalAmt();
                    trnxFileGet(ws);
                    statements++;
                }
            }
        }

        trnxFileClose(ws, sysout);
        xrefFileClose(ws, sysout);
        custFileClose(ws, sysout);
        acctFileClose(ws, sysout);

        FileStatus.Outcome textClosed = ws.stmtFile().closeOutput();
        FileStatus.Outcome htmlClosed = htmlWriter.close(ws.htmlFile());
        requireOutputSucceeded(textClosed, STMTFILE_DD, "CLOSE STMT-FILE");
        requireOutputSucceeded(htmlClosed, HTMLFILE_DD, "CLOSE HTML-FILE");

        return statements;
    }

    private void xrefFileGetNext(WorkingStorage ws, SysoutSink sysout) {
        Response read = statementSubroutine.readNext(ws.session(), XREFFILE_DD);

        if (FileStatus.isOk(read.rc())) {
        } else if (FileStatus.isEndOfFile(read.rc())) {
            ws.moveToEndOfFile(END_OF_FILE_YES);
        } else {
            throw reportAndAbend(sysout, ERROR_READING_XREFFILE, read.rc());
        }

        ws.moveToCardXrefRecord(read);
    }

    private void custFileGet(WorkingStorage ws, SysoutSink sysout) {
        Response read = statementSubroutine.readByKey(ws.session(), CUSTFILE_DD, ws.xrefCustId(),
                CardXrefRecord.XREF_CUST_ID_LENGTH);

        if (!FileStatus.isOk(read.rc())) {
            throw reportAndAbend(sysout, ERROR_READING_CUSTFILE, read.rc());
        }

        // MOVE WS-M03B-FLDT TO CUSTOMER-RECORD - truncated to 500 bytes. L388.
        ws.moveToCustomerRecord(read);
    }

    private void acctFileGet(WorkingStorage ws, SysoutSink sysout) {
        Response read = statementSubroutine.readByKey(ws.session(), ACCTFILE_DD, ws.xrefAcctId(),
                CardXrefRecord.XREF_ACCT_ID_LENGTH);

        if (!FileStatus.isOk(read.rc())) {
            throw reportAndAbend(sysout, ERROR_READING_ACCTFILE, read.rc());
        }

        // MOVE WS-M03B-FLDT TO ACCOUNT-RECORD - truncated to 300 bytes. L412.
        ws.moveToAccountRecord(read);
    }

    private void trnxFileGet(WorkingStorage ws) {
        String xrefCardNum = ws.xrefCardNum();

        ws.moveToCrJmp(1);
        while (!(ws.crJmp() > ws.crCnt()
                || ws.table().cardNum(ws.crJmp()).compareTo(xrefCardNum) > 0)) {
            if (xrefCardNum.equals(ws.table().cardNum(ws.crJmp()))) {
                ws.trnxRecord().writeTrnxCardNum(ws.table().cardNum(ws.crJmp()));
                ws.moveToTrJmp(1);
                while (!(ws.trJmp() > ws.table().trct(ws.crJmp()))) {
                    ws.trnxRecord().writeTrnxId(ws.table().tranNum(ws.crJmp(), ws.trJmp()));
                    ws.trnxRecord().writeTrnxRest(ws.table().tranRest(ws.crJmp(), ws.trJmp()));
                    writeTrans(ws);
                    ws.addTrnxAmtToWsTotalAmt(ws.trnxRecord().readTrnxAmt());
                    ws.moveToTrJmp(ws.trJmp() + 1);
                }
            }
            ws.moveToCrJmp(ws.crJmp() + 1);
        }

        ws.moveWsTotalAmtToWsTrnAmt();
        ws.stmtFile().setTotalTransactionAmount(ws.wsTrnAmt());

        for (StatementLine line : STATEMENT_TOTAL_TEXT_LINES) {
            writeStatementLine(ws.stmtFile(), line);
        }

        for (HtmlFixedLine line : HTML_FOOTER_LINES) {
            writeHtmlFixedLine(ws.htmlFile(), line);
        }
    }

    private void trnxFileClose(WorkingStorage ws, SysoutSink sysout) {
        Response closed = statementSubroutine.close(ws.session(), TRNXFILE_DD);
        if (!isOkOrRecordLengthConflict(closed.rc())) {
            throw reportAndAbend(sysout, ERROR_CLOSING_TRNXFILE, closed.rc());
        }
    }

    private void xrefFileClose(WorkingStorage ws, SysoutSink sysout) {
        Response closed = statementSubroutine.close(ws.session(), XREFFILE_DD);
        if (!isOkOrRecordLengthConflict(closed.rc())) {
            throw reportAndAbend(sysout, ERROR_CLOSING_XREFFILE, closed.rc());
        }
    }

    private void custFileClose(WorkingStorage ws, SysoutSink sysout) {
        Response closed = statementSubroutine.close(ws.session(), CUSTFILE_DD);
        if (!isOkOrRecordLengthConflict(closed.rc())) {
            throw reportAndAbend(sysout, ERROR_CLOSING_CUSTFILE, closed.rc());
        }
    }

    private void acctFileClose(WorkingStorage ws, SysoutSink sysout) {
        Response closed = statementSubroutine.close(ws.session(), ACCTFILE_DD);
        if (!isOkOrRecordLengthConflict(closed.rc())) {
            throw reportAndAbend(sysout, ERROR_CLOSING_ACCTFILE, closed.rc());
        }
    }

    /**
     * Whether a status is one of the two every {@code OPEN} guard, every {@code CLOSE} guard and the first
     * {@link #TRNXFILE_DD} read accepts: {@code '00'} or {@code '04'}.
     *
     * @param status a two-character {@code FILE STATUS}
     * @return {@code true} for {@code '00'} and for {@code '04'}
     */
    public static boolean isOkOrRecordLengthConflict(String status) {
        return FileStatus.isOk(status) || STATUS_RECORD_LENGTH_CONFLICT.equals(status);
    }

    private AbendException reportAndAbend(SysoutSink sysout, String message, String status) {
        sysout.write(message);
        sysout.write(RETURN_CODE_PREFIX + status);
        sysout.write(ABENDING_PROGRAM);
        return AbendException.withoutAbendParameters(PROGRAM_ID, ABEND_RETURN_CODE,
                message + "; " + RETURN_CODE_PREFIX + status);
    }

    private static void requireOutputSucceeded(FileStatus.Outcome outcome, String ddName,
                                               String operation) {
        Objects.requireNonNull(outcome, "An output operation on " + ddName + " must report an outcome; "
                + "there is no COBOL FILE STATUS meaning 'no answer'");
        if (outcome == FileStatus.Outcome.OK) {
            return;
        }
        throw unhandledOutputCondition(ddName, operation, outcome.name());
    }

    private static AbendException unhandledOutputCondition(String ddName, String operation,
                                                          String condition) {
        LOG.error(operation + " on " + ddName + " reported FILE STATUS outcome " + condition
                + ". app/cbl/CBSTM03A.CBL declares no FILE STATUS clause for this file and guards none "
                + "of its OPEN, WRITE or CLOSE statements, so there is no path on which the program "
                + "continues: the run unit terminates. Any statement records already written stand, "
                + "exactly as they would on the mainframe.");
        return AbendException.withoutAbendParameters(PROGRAM_ID, AbendException.RETURN_CODE_IO_ERROR,
                operation + " on " + ddName + " reported FILE STATUS outcome " + condition
                        + ", and app/cbl/CBSTM03A.CBL guards neither this file's OPEN, WRITE nor CLOSE, "
                        + "so the condition terminates the run unit");
    }

    private static void writeStatementLine(StatementFile stmt, StatementLine line) {
        requireOutputSucceeded(stmt.writeLine(line), STMTFILE_DD,
                "WRITE FD-STMTFILE-REC FROM " + line.cobolName());
    }

    private void writeHtmlFixedLine(HtmlStatementFile html, HtmlFixedLine line) {
        requireOutputSucceeded(htmlWriter.writeFixedLine(html, line), HTMLFILE_DD,
                "WRITE FD-HTMLFILE-REC FROM " + line.cobolName());
    }

    private void writeHtmlTransactionField(HtmlStatementFile html, TransactionField field,
                                           String value) {
        requireOutputSucceeded(htmlWriter.writeTransactionField(html, field, value), HTMLFILE_DD,
                "WRITE FD-HTMLFILE-REC FROM HTML-TRAN-LN " + field.cobolName());
    }

    private void createStatement(WorkingStorage ws) {
        StatementFile stmt = ws.stmtFile();
        Stm03CustomerRecord customer = ws.customerRecord();
        AccountRecord account = ws.accountRecord();

        stmt.initializeStatementLines();

        writeStatementLine(stmt, StatementLine.ST_LINE0);

        writeHtmlHeader(ws);

        stmt.setName(codec.concatenateDelimitedBySize(
                StatementHtmlWriter.delimitedBy(customer.custFirstName(), SPACE_DELIMITER),
                StatementHtmlWriter.delimitedBySize(SPACE_DELIMITER),
                StatementHtmlWriter.delimitedBy(customer.custMiddleName(), SPACE_DELIMITER),
                StatementHtmlWriter.delimitedBySize(SPACE_DELIMITER),
                StatementHtmlWriter.delimitedBy(customer.custLastName(), SPACE_DELIMITER),
                StatementHtmlWriter.delimitedBySize(SPACE_DELIMITER)));

        stmt.setAddressLine1(customer.custAddrLine1());
        stmt.setAddressLine2(customer.custAddrLine2());

        stmt.setAddressLine3(codec.concatenateDelimitedBySize(
                StatementHtmlWriter.delimitedBy(customer.custAddrLine3(), SPACE_DELIMITER),
                StatementHtmlWriter.delimitedBySize(SPACE_DELIMITER),
                StatementHtmlWriter.delimitedBy(customer.custAddrStateCd(), SPACE_DELIMITER),
                StatementHtmlWriter.delimitedBySize(SPACE_DELIMITER),
                StatementHtmlWriter.delimitedBy(customer.custAddrCountryCd(), SPACE_DELIMITER),
                StatementHtmlWriter.delimitedBySize(SPACE_DELIMITER),
                StatementHtmlWriter.delimitedBy(customer.custAddrZip(), SPACE_DELIMITER),
                StatementHtmlWriter.delimitedBySize(SPACE_DELIMITER)));

        stmt.setAccountId(account.getAcctId());
        // That loss is the COBOL's and is required. L484.
        stmt.setCurrentBalance(account.getAcctCurrBal());
        stmt.setFicoScore(customer.custFicoCreditScoreValue(datasetCharset));

        writeHtmlNmAdBs(ws);

        for (StatementLine line : STATEMENT_BODY_TEXT_LINES) {
            writeStatementLine(stmt, line);
        }
    }

    private void writeHtmlHeader(WorkingStorage ws) {
        HtmlStatementFile html = ws.htmlFile();

        for (HtmlFixedLine line : HTML_HEADER_PROLOGUE_LINES) {
            writeHtmlFixedLine(html, line);
        }

        requireOutputSucceeded(htmlWriter.writeAccountHeading(html,
                codec.movePic9(ws.accountRecord().getAcctId(), AccountRecord.ACCT_ID_LENGTH)),
                HTMLFILE_DD, "WRITE FD-HTMLFILE-REC FROM HTML-L11");

        for (HtmlFixedLine line : HTML_HEADER_BANK_LINES) {
            writeHtmlFixedLine(html, line);
        }
    }

    private void writeHtmlNmAdBs(WorkingStorage ws) {
        HtmlStatementFile html = ws.htmlFile();
        StatementFile stmt = ws.stmtFile();

        requireOutputSucceeded(
                htmlWriter.writeNameLine(html, stmt.slotImage(StatementSlot.ST_NAME)),
                HTMLFILE_DD, "WRITE FD-HTMLFILE-REC (the name paragraph group)");

        for (int line = 0; line < HTML_ADDRESS_FIELDS.size(); line++) {
            AddressField field = HTML_ADDRESS_FIELDS.get(line);
            requireOutputSucceeded(htmlWriter.writeAddressLine(html, field,
                    stmt.slotImage(HTML_ADDRESS_SLOTS.get(line))),
                    HTMLFILE_DD, "WRITE FD-HTMLFILE-REC FROM HTML-ADDR-LN " + field.cobolName());
        }

        for (HtmlFixedLine line : HTML_BASIC_DETAILS_PRELUDE_LINES) {
            writeHtmlFixedLine(html, line);
        }

        for (int detail = 0; detail < HTML_BASIC_DETAILS.size(); detail++) {
            BasicDetail basic = HTML_BASIC_DETAILS.get(detail);
            requireOutputSucceeded(htmlWriter.writeBasicDetail(html, basic,
                    stmt.slotImage(HTML_BASIC_DETAIL_SLOTS.get(detail))),
                    HTMLFILE_DD, "WRITE FD-HTMLFILE-REC FROM HTML-BSIC-LN " + basic.cobolName());
        }

        for (HtmlFixedLine line : HTML_COLUMN_HEADING_LINES) {
            writeHtmlFixedLine(html, line);
        }
    }

    private void writeTrans(WorkingStorage ws) {
        StatementFile stmt = ws.stmtFile();
        HtmlStatementFile html = ws.htmlFile();
        TrnxRecord trnx = ws.trnxRecord();

        stmt.setTransactionId(trnx.readTrnxId());
        // MOVE TRNX-DESC TO ST-TRANDT - X(100) into X(49), truncated on the right. L677.
        stmt.setTransactionDetails(trnx.readTrnxDesc());
        stmt.setTransactionAmount(trnx.readTrnxAmt());
        writeStatementLine(stmt, StatementLine.ST_LINE14);

        writeHtmlFixedLine(html, HtmlFixedLine.HTML_LTRS);
        writeHtmlFixedLine(html, HtmlFixedLine.HTML_L58);
        writeHtmlTransactionField(html, TransactionField.TRAN_ID,
                stmt.slotImage(StatementSlot.ST_TRANID));
        writeHtmlFixedLine(html, HtmlFixedLine.HTML_LTDE);
        writeHtmlFixedLine(html, HtmlFixedLine.HTML_L61);
        writeHtmlTransactionField(html, TransactionField.TRAN_DETAILS,
                stmt.slotImage(StatementSlot.ST_TRANDT));
        writeHtmlFixedLine(html, HtmlFixedLine.HTML_LTDE);
        writeHtmlFixedLine(html, HtmlFixedLine.HTML_L64);
        writeHtmlTransactionField(html, TransactionField.TRAN_AMOUNT,
                stmt.slotImage(StatementSlot.ST_TRANAMT));
        writeHtmlFixedLine(html, HtmlFixedLine.HTML_LTDE);
        writeHtmlFixedLine(html, HtmlFixedLine.HTML_LTRE);
    }

    /**
     * The whole of {@code CBSTM03A}'s working storage that this translation needs, plus the three handles a
     * run holds open.
     */
    static final class WorkingStorage {
        private final FixedWidthCodec codec;

        private final Session session;

        private final TrnxTable table = new TrnxTable();

        private int crCnt;

        private int trCnt;

        private int crJmp;

        private int trJmp;

        private BigDecimal wsTotalAmt = CobolDecimal.monetaryZero();

        private BigDecimal wsTrnAmt = CobolDecimal.monetaryZero();

        private String wsSaveCard = " ".repeat(WS_SAVE_CARD_LENGTH);

        private String endOfFile = END_OF_FILE_NO;

        private String wsFlDd = STATE_TRNXFILE;

        private int bumpTiot;

        private TrnxRecord trnxRecord;

        private String cardXrefRecord;

        private Stm03CustomerRecord customerRecord;

        private AccountRecord accountRecord;

        private StatementFile stmtFile;

        private HtmlStatementFile htmlFile;

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

        void openOutput(StatementFile stmtFile, HtmlStatementFile htmlFile) {
            this.stmtFile = Objects.requireNonNull(stmtFile, "An open STMTFILE handle is required");
            this.htmlFile = Objects.requireNonNull(htmlFile, "An open HTMLFILE handle is required");
        }

        void initializeTrnxTable() {
            table.initialize();
        }

        Session session() {
            return session;
        }

        TrnxTable table() {
            return table;
        }

        StatementFile stmtFile() {
            return Objects.requireNonNull(stmtFile, "STMTFILE is not open; OPEN OUTPUT runs once, in "
                    + "the prologue at app/cbl/CBSTM03A.CBL:L293");
        }

        HtmlStatementFile htmlFile() {
            return Objects.requireNonNull(htmlFile, "HTMLFILE is not open; OPEN OUTPUT runs once, in "
                    + "the prologue at app/cbl/CBSTM03A.CBL:L293");
        }

        StatementFile stmtFileOrNull() {
            return stmtFile;
        }

        HtmlStatementFile htmlFileOrNull() {
            return htmlFile;
        }

        TrnxRecord trnxRecord() {
            return trnxRecord;
        }

        Stm03CustomerRecord customerRecord() {
            return customerRecord;
        }

        AccountRecord accountRecord() {
            return accountRecord;
        }

        String cardXrefRecord() {
            return cardXrefRecord;
        }

        String xrefCardNum() {
            return span(CardXrefRecord.XREF_CARD_NUM_OFFSET, CardXrefRecord.XREF_CARD_NUM_LENGTH);
        }

        String xrefCustId() {
            return span(CardXrefRecord.XREF_CUST_ID_OFFSET, CardXrefRecord.XREF_CUST_ID_LENGTH);
        }

        String xrefAcctId() {
            return span(CardXrefRecord.XREF_ACCT_ID_OFFSET, CardXrefRecord.XREF_ACCT_ID_LENGTH);
        }

        private String span(int offset, int length) {
            return cardXrefRecord.substring(offset, offset + length);
        }

        void moveToTrnxRecord(Response response) {
            Objects.requireNonNull(response, "A response is required to move a record area");
            trnxRecord = TrnxRecord.decode(
                    codec.encodeImage(response.fldt(), "a TRNX-RECORD group move"), codec.charset());
        }

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

        void moveToWsSaveCard(String cardNum) {
            wsSaveCard = codec.movePicX(cardNum, WS_SAVE_CARD_LENGTH);
        }

        String wsSaveCard() {
            return wsSaveCard;
        }

        void moveToEndOfFile(String value) {
            endOfFile = Objects.requireNonNull(value, "An END-OF-FILE value is required");
        }

        String endOfFile() {
            return endOfFile;
        }

        void moveToWsFlDd(String value) {
            wsFlDd = Objects.requireNonNull(value, "A WS-FL-DD value is required");
        }

        String wsFlDd() {
            return wsFlDd;
        }

        void moveToCrCnt(int value) {
            crCnt = value;
        }

        void addOneToCrCnt() {
            crCnt = crCnt + 1;
        }

        int crCnt() {
            return crCnt;
        }

        void moveToTrCnt(int value) {
            trCnt = value;
        }

        void addOneToTrCnt() {
            trCnt = trCnt + 1;
        }

        int trCnt() {
            return trCnt;
        }

        void moveToCrJmp(int value) {
            crJmp = value;
        }

        int crJmp() {
            return crJmp;
        }

        void moveToTrJmp(int value) {
            trJmp = value;
        }

        int trJmp() {
            return trJmp;
        }

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

        BigDecimal wsTotalAmt() {
            return wsTotalAmt;
        }

        void moveWsTotalAmtToWsTrnAmt() {
            wsTrnAmt = CobolDecimal.storeAtPicture(wsTotalAmt, TrnxRecord.TRNX_AMT_INTEGER_DIGITS,
                    CobolDecimal.MONETARY_SCALE);
        }

        BigDecimal wsTrnAmt() {
            return wsTrnAmt;
        }

        void addToBumpTiot(int length) {
            bumpTiot = bumpTiot + length;
        }

        int bumpTiot() {
            return bumpTiot;
        }
    }

    /**
     * {@code 01 WS-TRNX-TABLE} and {@code 01 WS-TRN-TBL-CNTR} - fifty-one cards, ten transactions each,
     * {@code app/cbl/CBSTM03A.CBL:L225-L233}.
     */
    static final class TrnxTable {
        private final String[] cardNum = new String[CARD_TABLE_OCCURS + 1];

        private final String[][] tranNum =
                new String[CARD_TABLE_OCCURS + 1][TRAN_TABLE_OCCURS + 1];

        private final String[][] tranRest =
                new String[CARD_TABLE_OCCURS + 1][TRAN_TABLE_OCCURS + 1];

        private final int[] trct = new int[CARD_TABLE_OCCURS + 1];

        TrnxTable() {
            initialize();
        }

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

        String cardNum(int card) {
            return cardNum[subscript(card, CARD_TABLE_OCCURS, "WS-CARD-TBL")];
        }

        void setCardNum(int card, String value) {
            cardNum[subscript(card, CARD_TABLE_OCCURS, "WS-CARD-TBL")] =
                    Objects.requireNonNull(value, "A card number is required");
        }

        String tranNum(int card, int tran) {
            return tranNum[subscript(card, CARD_TABLE_OCCURS, "WS-CARD-TBL")]
                    [subscript(tran, TRAN_TABLE_OCCURS, "WS-TRAN-TBL")];
        }

        void setTranNum(int card, int tran, String value) {
            tranNum[subscript(card, CARD_TABLE_OCCURS, "WS-CARD-TBL")]
                    [subscript(tran, TRAN_TABLE_OCCURS, "WS-TRAN-TBL")] =
                    Objects.requireNonNull(value, "A transaction id is required");
        }

        String tranRest(int card, int tran) {
            return tranRest[subscript(card, CARD_TABLE_OCCURS, "WS-CARD-TBL")]
                    [subscript(tran, TRAN_TABLE_OCCURS, "WS-TRAN-TBL")];
        }

        void setTranRest(int card, int tran, String value) {
            tranRest[subscript(card, CARD_TABLE_OCCURS, "WS-CARD-TBL")]
                    [subscript(tran, TRAN_TABLE_OCCURS, "WS-TRAN-TBL")] =
                    Objects.requireNonNull(value, "A transaction remainder is required");
        }

        int trct(int card) {
            return trct[subscript(card, CARD_TABLE_OCCURS, "WS-TRN-TBL-CTR")];
        }

        void setTrct(int card, int value) {
            trct[subscript(card, CARD_TABLE_OCCURS, "WS-TRN-TBL-CTR")] = value;
        }

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
