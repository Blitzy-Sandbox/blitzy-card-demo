package com.vsergeychik.carddemo.transaction;

import com.vsergeychik.carddemo.common.DatasetRelation;
import com.vsergeychik.carddemo.common.DatasetRelation.BackendDiagnostic;
import com.vsergeychik.carddemo.common.FileStatus;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.FixedWidthRecord;
import com.vsergeychik.carddemo.common.FixedWidthRecord.FieldSpan;
import com.vsergeychik.carddemo.common.FixedWidthRecord.RecordLayout;
import com.vsergeychik.carddemo.common.RecordImageForm;
import com.vsergeychik.carddemo.config.CobolCharsetConfig;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBinding;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBindings;
import com.vsergeychik.carddemo.transaction.model.TranReportLayouts;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementSetter;
import org.springframework.stereotype.Component;

import java.nio.charset.Charset;
import java.sql.PreparedStatement;
import java.util.Objects;

/**
 * Writes the {@code TRANREPT} transaction detail report: one fixed
 * {@value #RECORD_LENGTH}-byte record per report line, in call order, exactly as
 * {@code app/cbl/CBTRN03C.cbl} does.
 *
 * <p>This is the second of the module's three fixed-width output writers, and the whole of its job is
 * one COBOL sentence pair repeated eight ways:
 *
 * <pre>
 *     MOVE &lt;layout&gt; TO FD-REPTFILE-REC
 *     PERFORM 1111-WRITE-REPORT-REC
 * </pre>
 *
 * <p>{@code FD-REPTFILE-REC} is declared {@code PIC X(133)} at
 * {@code app/cbl/CBTRN03C.cbl:L84-L85}, and that single fact is the reason this class exists at all.
 * Six of the eight things the program moves into it are <em>narrower</em> than 133 bytes, and a COBOL
 * alphanumeric {@code MOVE} silently right-pads the receiver with spaces to close the gap. Reproduce
 * the layouts faithfully but forget the pad, and every report record is short; the file still
 * <em>looks</em> right line by line and only a width or byte comparison catches it. So the padding
 * lives here, in one place, applied by one rule.
 *
 * <h2>The 133-byte contract</h2>
 *
 * <p>From {@code app/jcl/TRANREPT.jcl:L76-L80} (step {@code STEP10R}, which is
 * {@code EXEC PGM=CBTRN03C}) and, character for character identically, from
 * {@code app/proc/TRANREPT.prc:L74-L78}:
 *
 * <pre>
 *     //TRANREPT DD DISP=(NEW,CATLG,DELETE),
 *     //         UNIT=SYSDA,
 *     //         DCB=(LRECL=133,RECFM=FB,BLKSIZE=0),
 *     //         SPACE=(CYL,(1,1),RLSE),
 *     //         DSN=AWS.M2.CARDDEMO.TRANREPT(+1)
 * </pre>
 *
 * <p>{@code RECFM=FB}, {@code LRECL=133} - gate <strong>G20</strong>. The dataset is a generation
 * data group: {@code app/jcl/REPTFILE.jcl:L25-L28} defines it with
 * {@code DEFINE GENERATIONDATAGROUP LIMIT(10)}, which is why the JCL names a relative generation
 * {@code (+1)}. Both the width and the format are cross-checked against
 * {@code carddemo.datasets.}{@value #DD_NAME} at construction and the bean refuses to exist if either
 * disagrees, so a wrong width fails once, at startup, naming both numbers - rather than 133 times per
 * page for the life of the deployment.
 *
 * <h2>The eight layouts and the padding each one receives</h2>
 *
 * <p>Seven come from {@code app/cpy/CVTRA07Y.cpy} and are rendered by
 * {@link TranReportLayouts} at their <strong>natural</strong> width; the eighth,
 * {@code WS-BLANK-LINE}, is declared in the program's own {@code WORKING-STORAGE} at
 * {@code app/cbl/CBTRN03C.cbl:L133} and is available here as {@link #WS_BLANK_LINE_IMAGE}. The
 * arithmetic that proves each width is given so a reviewer can check it against the copybook without
 * leaving this file:
 *
 * <table border="1">
 *   <caption>Layout widths, their arithmetic, and the pad this writer applies</caption>
 *   <tr><th>Layout</th><th>Width arithmetic</th><th>Width</th><th>Pad to 133</th></tr>
 *   <tr><td>{@code REPORT-NAME-HEADER}</td>
 *       <td>38 + 41 + 12 + 10 + 4 + 10</td>
 *       <td>115</td><td>+ {@value #REPORT_NAME_HEADER_PAD} spaces</td></tr>
 *   <tr><td>{@code WS-BLANK-LINE}</td>
 *       <td>{@code PIC X(133) VALUE SPACES}</td>
 *       <td>133</td><td>none</td></tr>
 *   <tr><td>{@code TRANSACTION-HEADER-1}</td>
 *       <td>17 + 12 + 19 + 35 + 14 + 1 + 16</td>
 *       <td>114</td><td>+ {@value #TRANSACTION_HEADER_1_PAD} spaces</td></tr>
 *   <tr><td>{@code TRANSACTION-HEADER-2}</td>
 *       <td>{@code PIC X(133) VALUE ALL '-'}</td>
 *       <td>133</td><td>none</td></tr>
 *   <tr><td>{@code TRANSACTION-DETAIL-REPORT}</td>
 *       <td>16 + 1 + 11 + 1 + 2 + 1 + 15 + 1 + 4 + 1 + 29 + 1 + 10 + 4 + 15 + 2</td>
 *       <td>114</td><td>+ {@value #TRANSACTION_DETAIL_REPORT_PAD} spaces</td></tr>
 *   <tr><td>{@code REPORT-PAGE-TOTALS}</td>
 *       <td>11 + 86 + 15</td>
 *       <td>112</td><td>+ {@value #REPORT_PAGE_TOTALS_PAD} spaces</td></tr>
 *   <tr><td>{@code REPORT-ACCOUNT-TOTALS}</td>
 *       <td>13 + 84 + 15</td>
 *       <td>112</td><td>+ {@value #REPORT_ACCOUNT_TOTALS_PAD} spaces</td></tr>
 *   <tr><td>{@code REPORT-GRAND-TOTALS}</td>
 *       <td>11 + 86 + 15</td>
 *       <td>112</td><td>+ {@value #REPORT_GRAND_TOTALS_PAD} spaces</td></tr>
 * </table>
 *
 * <p>Two numbers in that arithmetic are worth spelling out, because both are easy to get wrong by one
 * and neither shows up as anything but a shifted column:
 *
 * <ul>
 *   <li><strong>The edit masks are 15 bytes each.</strong> {@code TRAN-REPORT-AMT PIC
 *       -ZZZ,ZZZ,ZZZ.ZZ} on the detail line and {@code REPT-PAGE-TOTAL} /
 *       {@code REPT-ACCOUNT-TOTAL} / {@code REPT-GRAND-TOTAL PIC +ZZZ,ZZZ,ZZZ.ZZ} on the three total
 *       lines all count 1 sign + 3 digits + 1 comma + 3 digits + 1 comma + 3 digits + 1 decimal
 *       point + 2 digits = <strong>15</strong>. The sign and both commas and the point occupy
 *       character positions of their own; they are not free.</li>
 *   <li><strong>The {@code ALL '.'} leaders are 86, 84 and 86.</strong> Page and grand totals each
 *       carry an {@code X(86)} leader after an {@code X(11)} label, and the account total carries an
 *       {@code X(84)} leader after its longer {@code X(13)} label - which is exactly what keeps all
 *       three amounts in the same columns, 11 + 86 == 13 + 84 == 97. The two label lengths differ
 *       and the two leader lengths differ, and only their sums agree.</li>
 * </ul>
 *
 * <h2>What this class deliberately does not do</h2>
 *
 * <ul>
 *   <li><strong>It does not own the line counter.</strong> {@code WS-LINE-COUNTER PIC 9(09) COMP-3}
 *       and {@code WS-PAGE-SIZE PIC 9(03) COMP-3 VALUE 20}
 *       ({@code app/cbl/CBTRN03C.cbl:L129-L132}) are incremented by the calling paragraphs at their
 *       own specific points, and the increments are <em>not</em> uniform: {@code 1120-WRITE-HEADERS}
 *       adds one after each of its four records, {@code 1110-WRITE-PAGE-TOTALS} and
 *       {@code 1120-WRITE-ACCOUNT-TOTALS} add one after each of their two, and
 *       {@code 1110-WRITE-GRAND-TOTALS} ({@code L318-L322}) adds <strong>none at all</strong>. A
 *       counter kept here would have to guess which caller it was serving, and would get the grand
 *       total wrong. Pagination state, page breaks and header re-emission therefore live entirely in
 *       {@code TransactionReportJob}, whose {@code FUNCTION MOD(WS-LINE-COUNTER, WS-PAGE-SIZE)} test
 *       at {@code L282} is the page-break decision. Do not add counting here.</li>
 *   <li><strong>It does not abend.</strong> Nothing here throws an abend. Instead all three of the
 *       COBOL paragraphs this class serves report a {@link FileStatus.Outcome} that the job branches
 *       on - the open through {@link ReportFile#openOutcome()}
 *       ({@code 0100-REPTFILE-OPEN}, {@code L394-L410}), the write through
 *       {@link ReportFile#writeReportRec()} ({@code 1111-WRITE-REPORT-REC}, {@code L343-L359}) and
 *       the close through {@link ReportFile#closeOutput()} ({@code 9100-REPTFILE-CLOSE},
 *       {@code L532-L548}). The {@code '00'}-or-12 ladder itself, the message texts
 *       {@code 'ERROR OPENING REPTFILE'} ({@code L405}), {@code 'ERROR WRITING REPTFILE'}
 *       ({@code L354}) and {@code 'ERROR CLOSING REPORT FILE'} ({@code L543}), the
 *       {@code 9910-DISPLAY-IO-STATUS} rendering and the {@code 9999-ABEND-PROGRAM} call to
 *       {@code CEE3ABD} ({@code L626-L630}) all belong to the job. Note in passing that
 *       {@code 9100-REPTFILE-CLOSE} reaches the same result through different verbs -
 *       {@code ADD 8 TO ZERO GIVING APPL-RESULT}, {@code SUBTRACT APPL-RESULT FROM APPL-RESULT},
 *       {@code ADD 12 TO ZERO GIVING APPL-RESULT} ({@code L533-L539}) - where the open and write
 *       paragraphs use {@code MOVE}. Same outcomes, three more arithmetic sites for the job's
 *       assertions.</li>
 *   <li><strong>It performs no arithmetic and formats no number.</strong> Amounts arrive already
 *       edited, as the character images {@link TranReportLayouts} produced from the two pictures
 *       above. There is consequently no {@code BigDecimal} in this file, and - as everywhere in this
 *       module - no {@code double} and no {@code float} (gate G22).</li>
 *   <li><strong>It introduces no schema.</strong> No data-definition statement, no entity mapping, no
 *       version column, no index (gate G44). The dataset is addressed by the name configuration
 *       supplies, so no {@code AWS.M2.CARDDEMO} literal appears in Java (gate G46) - the JCL excerpt
 *       above is a quotation inside a comment, which is provenance rather than a value.</li>
 * </ul>
 *
 * <h2>Write order is part of the answer</h2>
 *
 * <p>Records are handed to the sink one at a time, immediately, in the order the caller writes them.
 * Nothing is buffered for reordering, nothing is coalesced, nothing is parallelised. The report's
 * line sequence <em>is</em> its content - a page total belongs after the detail lines it totals and
 * before the rule that follows it - so a reordering buffer would not be an optimisation, it would be
 * a defect. This is also why no performance tuning is applied anywhere in this class: no performance
 * objective is stated for this migration, and several jobs depend on strict record ordering.
 *
 * <h2>Thread safety, state and testability</h2>
 *
 * <p>The bean itself is immutable and therefore a safe singleton: it holds the template, the codec,
 * the resolved binding and the resolved dataset, and nothing else. Every piece of mutable state - the
 * {@value #RECORD_LENGTH}-byte {@code FD-REPTFILE-REC} area, the open flag and the record count -
 * lives on the per-execution {@link ReportFile} handle that {@link #openOutput()} returns, so two
 * concurrent report runs cannot overwrite each other's record area and no test outcome depends on
 * what ran before it. Nothing {@code static} here is mutable (practice B9, gate G53). A handle is
 * not thread-safe, exactly as a COBOL record area is not, and belongs to the step or test that
 * opened it.
 *
 * <p>{@link #openOutput(RecordSink)} is the seam that makes all of this assertable with no database,
 * no filesystem, no application context and no {@code JobLauncher}: a caller supplies a collector and
 * reads the emitted bytes back directly.
 *
 * <h2>Provenance of the expectations - residual risk R-E</h2>
 *
 * <p>The legacy COBOL <strong>cannot be executed in this environment</strong>: there is no z/OS
 * runtime, the available compiler has indexed file support disabled, no Language Environment
 * {@code CEE*} services exist and no CICS emulator is present. Every width, pad count and message
 * above is therefore <em>statically derived</em> - read out of {@code app/cpy/CVTRA07Y.cpy},
 * {@code app/cbl/CBTRN03C.cbl}, {@code app/jcl/TRANREPT.jcl}, {@code app/proc/TRANREPT.prc} and
 * {@code app/jcl/REPTFILE.jcl} - rather than captured from a live run, and each carries a dedicated
 * test so a misreading surfaces as a failing assertion rather than as a wrong byte in a report.
 *
 * <p>Risk <strong>R-E</strong> applies equally and is recorded rather than absorbed (practice B12):
 * indexed VSAM has no standard published JDBC driver, so this module pins none and the site-specific
 * driver is a deployment-time input. <strong>Production connectivity cannot be exercised here.</strong>
 * The default sink is consequently the one part of this class no test can prove end to end; it is
 * kept as thin as possible for that reason, and everything above it is proven through
 * {@link #openOutput(RecordSink)}.
 *
 * @see TranReportLayouts
 * @see FileStatus
 * @see FixedWidthCodec
 */
@Component
public final class TranReportWriter {

    // =================================================================================================
    // The dataset contract. app/jcl/TRANREPT.jcl:L76-L80 and app/proc/TRANREPT.prc:L74-L78.
    // =================================================================================================

    /**
     * The DD name this writer resolves from {@code carddemo.datasets}, verbatim from
     * {@code SELECT REPORT-FILE ASSIGN TO TRANREPT} at {@code app/cbl/CBTRN03C.cbl:L51}.
     *
     * <p>The DD name, not the dataset name: the dataset name lives only in configuration, so it can
     * differ per deployment without a recompile and never appears in Java (gate G46).
     */
    public static final String DD_NAME = "TRANREPT";

    /**
     * The fixed record width in bytes: {@code 133}.
     *
     * <p>Declared twice in the sources and identically in both. {@code app/jcl/TRANREPT.jcl:L78}
     * carries {@code DCB=(LRECL=133,RECFM=FB,BLKSIZE=0)} and {@code app/cbl/CBTRN03C.cbl:L85}
     * declares {@code 01 FD-REPTFILE-REC PIC X(133)}. Gate <strong>G20</strong>.
     */
    public static final int RECORD_LENGTH = 133;

    /**
     * The record format the JCL declares: {@code FB}, fixed blocked.
     *
     * <p>Checked against configuration at construction because it is the one geometry attribute that
     * would otherwise be decorative. {@code FB} is what makes "every record is exactly
     * {@value #RECORD_LENGTH} bytes" true; a variable format would make the pad rule below
     * meaningless while leaving every other number in this class unchanged.
     */
    public static final String RECORD_FORMAT = "FB";

    /**
     * The block size the JCL declares: {@code BLKSIZE=0}, which asks the system to determine it.
     *
     * <p>Recorded for completeness of the transcription. It has no effect on record content and this
     * writer neither blocks nor buffers, since a rendered record is handed to the sink immediately.
     */
    public static final int BLOCK_SIZE = 0;

    /**
     * The name of the FD record area, verbatim from {@code app/cbl/CBTRN03C.cbl:L85}.
     *
     * <p>Used as the span name in {@link #FD_REPTFILE_REC_LAYOUT} and in diagnostics, so a message
     * about this writer names the COBOL item a reader can go and look at rather than a Java field.
     */
    public static final String FD_REPTFILE_REC = "FD-REPTFILE-REC";

    // =================================================================================================
    // The eight layouts, and the pad each receives. Widths come from TranReportLayouts, which
    // transcribed them from app/cpy/CVTRA07Y.cpy; the pads are derived here rather than restated, so
    // there is exactly one place a width can be wrong.
    // =================================================================================================

    /**
     * Spaces added to {@code REPORT-NAME-HEADER}: {@code 133 - 115 = 18}.
     *
     * <p>{@code REPT-SHORT-NAME X(38)} + {@code REPT-LONG-NAME X(41)} +
     * {@code REPT-DATE-HEADER X(12)} + {@code REPT-START-DATE X(10)} + {@code FILLER X(04)} +
     * {@code REPT-END-DATE X(10)} = 115.
     */
    public static final int REPORT_NAME_HEADER_PAD =
            RECORD_LENGTH - TranReportLayouts.REPORT_NAME_HEADER_LENGTH;

    /**
     * Spaces added to {@code TRANSACTION-DETAIL-REPORT}: {@code 133 - 114 = 19}.
     *
     * <p>Sixteen items, six of them {@code FILLER}, two of those carrying a literal {@code '-'}
     * separator rather than a space, and the last of them the 15-byte edited amount.
     */
    public static final int TRANSACTION_DETAIL_REPORT_PAD =
            RECORD_LENGTH - TranReportLayouts.TRANSACTION_DETAIL_REPORT_LENGTH;

    /**
     * Spaces added to {@code TRANSACTION-HEADER-1}: {@code 133 - 114 = 19}.
     *
     * <p>Seven {@code FILLER} items whose {@code VALUE} literals are shorter than their declared
     * widths, which is what spaces the column headings out: 17 + 12 + 19 + 35 + 14 + 1 + 16 = 114.
     */
    public static final int TRANSACTION_HEADER_1_PAD =
            RECORD_LENGTH - TranReportLayouts.TRANSACTION_HEADER_1_LENGTH;

    /**
     * Spaces added to {@code TRANSACTION-HEADER-2}: {@code 133 - 133 = 0}.
     *
     * <p>The rule line is declared {@code PIC X(133) VALUE ALL '-'} and is therefore already the
     * full record width. It must pass through untouched - neither padded nor truncated - which is
     * precisely what a pad of zero means and what {@link ReportFile#writeLine(String)} guarantees for
     * an image that is already {@value #RECORD_LENGTH} characters long.
     */
    public static final int TRANSACTION_HEADER_2_PAD =
            RECORD_LENGTH - TranReportLayouts.TRANSACTION_HEADER_2_LENGTH;

    /**
     * Spaces added to {@code REPORT-PAGE-TOTALS}: {@code 133 - 112 = 21}.
     *
     * <p>{@code FILLER X(11) VALUE 'Page Total'} + {@code FILLER X(86) VALUE ALL '.'} +
     * {@code REPT-PAGE-TOTAL PIC +ZZZ,ZZZ,ZZZ.ZZ} = 11 + 86 + 15 = 112.
     */
    public static final int REPORT_PAGE_TOTALS_PAD =
            RECORD_LENGTH - TranReportLayouts.REPORT_PAGE_TOTALS_LENGTH;

    /**
     * Spaces added to {@code REPORT-ACCOUNT-TOTALS}: {@code 133 - 112 = 21}.
     *
     * <p>{@code FILLER X(13) VALUE 'Account Total'} + {@code FILLER X(84) VALUE ALL '.'} +
     * {@code REPT-ACCOUNT-TOTAL PIC +ZZZ,ZZZ,ZZZ.ZZ} = 13 + 84 + 15 = 112. The longer label and the
     * shorter leader cancel, so the amount lands in the same columns as the other two totals.
     */
    public static final int REPORT_ACCOUNT_TOTALS_PAD =
            RECORD_LENGTH - TranReportLayouts.REPORT_ACCOUNT_TOTALS_LENGTH;

    /**
     * Spaces added to {@code REPORT-GRAND-TOTALS}: {@code 133 - 112 = 21}.
     *
     * <p>{@code FILLER X(11) VALUE 'Grand Total'} + {@code FILLER X(86) VALUE ALL '.'} +
     * {@code REPT-GRAND-TOTAL PIC +ZZZ,ZZZ,ZZZ.ZZ} = 11 + 86 + 15 = 112.
     */
    public static final int REPORT_GRAND_TOTALS_PAD =
            RECORD_LENGTH - TranReportLayouts.REPORT_GRAND_TOTALS_LENGTH;

    /**
     * Spaces added to {@code WS-BLANK-LINE}: {@code 133 - 133 = 0}.
     *
     * <p>Declared {@code PIC X(133) VALUE SPACES} at {@code app/cbl/CBTRN03C.cbl:L133}, so it is
     * already the full record width and passes through untouched.
     */
    public static final int WS_BLANK_LINE_PAD = 0;

    /**
     * {@code WS-BLANK-LINE PIC X(133) VALUE SPACES} - {@value #RECORD_LENGTH} spaces.
     *
     * <p>Written by {@code 1120-WRITE-HEADERS} at {@code app/cbl/CBTRN03C.cbl:L329}, between the
     * report name header and the first column heading line. It lives here rather than in
     * {@link TranReportLayouts} because it is declared in the program's own {@code WORKING-STORAGE}
     * and not in {@code app/cpy/CVTRA07Y.cpy} - the copybook type models the copybook, and only the
     * copybook.
     *
     * <p>It is a full-width image, so writing it costs no padding:
     * {@code writeLine(WS_BLANK_LINE_IMAGE)} emits {@value #RECORD_LENGTH} spaces and nothing else.
     * Note that this is <em>not</em> the same thing as writing an empty string, which would be padded
     * to the same {@value #RECORD_LENGTH} spaces by a different route and would therefore prove
     * nothing about the blank line; the constant states the intent.
     */
    public static final String WS_BLANK_LINE_IMAGE = " ".repeat(RECORD_LENGTH);

    /**
     * The layout of {@code FD-REPTFILE-REC}: one alphanumeric span of {@value #RECORD_LENGTH} bytes
     * at offset 0, and nothing else.
     *
     * <p>{@code 01 FD-REPTFILE-REC PIC X(133)} ({@code app/cbl/CBTRN03C.cbl:L85}) is an elementary
     * item, not a group: the FD record area has <strong>no subordinate fields and no {@code FILLER}
     * of its own</strong>, because the structure belongs to whichever {@code CVTRA07Y} layout was
     * last moved into it. Modelling it as a single span is therefore the faithful transcription, not
     * a simplification - and the alternative, eight competing layouts over one area, is exactly the
     * {@code REDEFINES} the COBOL declines to write.
     *
     * <p>{@code static final} and deeply immutable: {@link RecordLayout} is a record that copies its
     * span list defensively and {@link FieldSpan} is a record of primitives and strings, so this
     * introduces no shared mutable state (practice B9, gate G53). Only the record <em>area</em> is
     * per-handle.
     *
     * <p>Its width is machine-checked at class-initialisation time by {@link RecordLayout}'s own
     * geometry self-check, which rejects a gap, an overlap or a total that misses
     * {@value #RECORD_LENGTH}. That is what makes {@value #RECORD_LENGTH} structural here rather than
     * merely asserted (gates G20, G21).
     */
    private static final RecordLayout FD_REPTFILE_REC_LAYOUT = RecordLayout.of(
            RECORD_LENGTH,
            FieldSpan.alphanumeric(FD_REPTFILE_REC, 0, RECORD_LENGTH));

    /** Where a non-{@code OK} outcome that cannot be returned is reported. */
    private static final Log LOG = LogFactory.getLog(TranReportWriter.class);

    // =================================================================================================
    // The sink seam. WRITE FD-REPTFILE-REC, app/cbl/CBTRN03C.cbl:L345.
    // =================================================================================================

    /**
     * Where a rendered {@value TranReportWriter#RECORD_LENGTH}-byte report record goes.
     *
     * <p>This is the seam that keeps the padding rule above assertable and keeps the deployment-time
     * data-access decision out of it. {@link TranReportWriter#openOutput()} supplies a
     * {@link JdbcTemplate}-backed implementation that writes to the configured
     * {@value TranReportWriter#DD_NAME} dataset; {@link TranReportWriter#openOutput(RecordSink)}
     * accepts any other, which is how a unit test collects the emitted records in memory with no
     * database and no filesystem, and how a site whose data-access driver expects a different
     * parameter shape substitutes its own.
     *
     * <p>Implementations must preserve <strong>call order</strong> and must not buffer in a way that
     * could reorder or coalesce records: the report's line sequence is its content.
     *
     * <p>The single abstract method returns a {@link FileStatus.Outcome} rather than throwing, because
     * {@code 1111-WRITE-REPORT-REC} ({@code app/cbl/CBTRN03C.cbl:L343-L359}) is a guard chain - it
     * tests {@code TRANREPT-STATUS = '00'}, sets {@code APPL-RESULT} to 0 or 12, and only then decides
     * whether to display {@code 'ERROR WRITING REPTFILE'} and abend. That decision is the report
     * job's, and {@link FileStatus} deliberately carries no dependency on the abend type, which is
     * what lets it stay there.
     */
    public interface RecordSink {

        /**
         * Accepts one whole report record, reproducing {@code WRITE FD-REPTFILE-REC} at
         * {@code app/cbl/CBTRN03C.cbl:L345}.
         *
         * @param recordImage the record's bytes in the dataset code page, exactly
         *                    {@value TranReportWriter#RECORD_LENGTH} of them. The array is freshly
         *                    allocated for this call and is neither retained nor reused by the caller,
         *                    so an implementation may keep it
         * @return {@link FileStatus.Outcome#OK} for the {@code TRANREPT-STATUS = '00'} arm, or
         *         {@link FileStatus.Outcome#OTHER} for any failure - the arm that sets
         *         {@code APPL-RESULT} to 12. <strong>Never {@code null}</strong>: there is no COBOL
         *         {@code FILE STATUS} meaning "no answer", so a {@code null} is an implementation
         *         defect and {@link ReportFile#writeReportRec()} rejects it rather than carrying it
         *         forward
         */
        FileStatus.Outcome write(byte[] recordImage);

        /**
         * Prepares the destination, mirroring {@code OPEN OUTPUT REPORT-FILE} in
         * {@code 0100-REPTFILE-OPEN} at {@code app/cbl/CBTRN03C.cbl:L396}.
         *
         * <p>Called exactly once, by {@link ReportFile}'s constructor, and its answer is published as
         * {@link ReportFile#openOutcome()} so the report job can run the same {@code '00'}-or-12 ladder
         * over the open that it runs over the write and the close. Without it the open would be the one
         * paragraph of the three whose guard chain - the {@code 'ERROR OPENING REPTFILE'} arm at
         * {@code L405} - had no outcome to branch on.
         *
         * <p>Defaulted to {@link FileStatus.Outcome#OK} because an in-memory collector - what a unit test
         * and the parity harness supply - has no destination outside the process and so nothing that
         * could refuse to be established. Every sink that does address something outside the process
         * overrides this and reports whether that destination could be reached, the
         * {@link JdbcTemplate}-backed default included: it borrows a pooled connection per record rather
         * than holding one open, but the relation it will insert into either exists for this run or does
         * not, and that is an open-time fact. Leaving it defaulted there was a parity defect, because it
         * made {@code L405} reachable only through the write.
         *
         * @return {@link FileStatus.Outcome#OK} when the destination is ready, or
         *         {@link FileStatus.Outcome#OTHER} otherwise. <strong>Never {@code null}</strong>, for the
         *         same reason {@link #write(byte[])} is never {@code null}
         */
        default FileStatus.Outcome open() {
            return FileStatus.Outcome.OK;
        }

        /**
         * Releases whatever the sink holds, mirroring {@code CLOSE REPORT-FILE} at
         * {@code app/cbl/CBTRN03C.cbl:L534}.
         *
         * <p>Defaulted to {@link FileStatus.Outcome#OK} because a sink that holds nothing - the
         * in-memory collector a test supplies, and the {@link JdbcTemplate}-backed default, which
         * borrows a pooled connection per record and returns it immediately - has nothing to release.
         *
         * @return {@link FileStatus.Outcome#OK} when the sink closed cleanly, or
         *         {@link FileStatus.Outcome#OTHER} otherwise. <strong>Never {@code null}</strong>, for
         *         the same reason {@link #write(byte[])} is never {@code null}
         */
        default FileStatus.Outcome close() {
            return FileStatus.Outcome.OK;
        }

        /**
         * Applies the <strong>abnormal</strong> disposition {@code app/jcl/TRANREPT.jcl:L76-L80} declares
         * for {@value TranReportWriter#DD_NAME}: the third positional of
         * {@code DISP=(NEW,CATLG,DELETE)}.
         *
         * <p>A {@code DISP} parameter carries three dispositions, and only two of them were reproduced
         * before this method existed. {@code NEW} is the status - the step allocates the generation - and
         * {@link #open()} reproduces it by clearing. {@code CATLG} is the <em>normal</em> disposition, and
         * {@link #close()} reproduces it by leaving the report where it is. {@code DELETE} is the
         * <em>abnormal</em> disposition, and it is a different outcome from either: a run that abends
         * leaves <strong>no report at all</strong>. MVS does not unwrite the pages; it deletes the dataset
         * that held them.
         *
         * <p>For this dataset that matters beyond tidiness. A transaction detail report is read by people,
         * and a report truncated at the page the run abended on is a report whose totals are wrong while
         * looking entirely well-formed - the page totals present, the account and grand totals missing or
         * partial ({@code app/cbl/CBTRN03C.cbl:L299-L344}). The mainframe's answer is to leave nothing to
         * misread, and this reproduces that.
         *
         * <p>Not a transaction rollback and not delegable to one: a rollback offers every write kept or
         * the uncommitted writes dropped, and the mainframe's third outcome is neither. Each write here is
         * durable as it completes; this discard then removes them, in the same order of events the
         * mainframe uses, with nothing buffered to make it possible.
         *
         * <p>Defaulted to {@link FileStatus.Outcome#OK} for the same reason {@link #open()} and
         * {@link #close()} are: an in-memory collector - what a unit test and the parity harness supply -
         * holds no catalogued generation. Its lines are per-run state that ceases to exist when the run
         * does, which is precisely the outcome {@code DELETE} produces, so reporting {@code OK} without
         * issuing anything is the honest answer rather than a stub. Every sink that does address a
         * catalogued destination overrides this.
         *
         * <p>Called at most once per handle, by {@link ReportFile#discardGeneration()}, and only on a path
         * that is already abending. An implementation must therefore <strong>not throw</strong>.
         *
         * @param recordsWritten how many records this run handed to the sink, so an implementation
         *                       addressing a shared destination can establish that what it is about to
         *                       delete is the generation <em>this</em> run allocated
         * @return {@link FileStatus.Outcome#OK} when the generation was discarded or there was none, or
         *         {@link FileStatus.Outcome#OTHER} when it could not be. <strong>Never
         *         {@code null}</strong>, for the same reason {@link #write(byte[])} is never {@code null}
         */
        default FileStatus.Outcome discard(int recordsWritten) {
            return FileStatus.Outcome.OK;
        }
    }

    // =================================================================================================
    // Collaborators. All constructor-injected; every one of them immutable.
    // =================================================================================================

    /**
     * The module's single {@link JdbcTemplate}, declared by the data-source configuration. Used only
     * by the default sink that {@link #openOutput()} creates; a caller that supplies its own sink
     * never touches it.
     */
    private final JdbcTemplate jdbcTemplate;

    /**
     * The one representation this writer's record image crosses JDBC in.
     *
     * <p>Injected rather than decided here, and that is the point: whether a record image binds as
     * characters or as bytes is a property of the deployment's driver, so it is stated once, by
     * {@link RecordImageForm#FORM_PROPERTY}, and never re-decided by each writer. Two writers that
     * answered it differently would both look correct in isolation and at most one of them would be
     * right on a backend nobody here can exercise.
     */
    private final RecordImageForm recordImageForm;

    /**
     * The fixed-width codec, constructed over the injected dataset {@link Charset}.
     *
     * <p>Immutable and holds only that charset, so sharing one across every handle is safe. Every
     * pad, truncate and byte conversion this class performs goes through it, which keeps the code page
     * explicit at every boundary and keeps the alphanumeric {@code MOVE} rule in exactly one place in
     * the module (practice B11).
     */
    private final FixedWidthCodec codec;

    /**
     * The configured binding for {@value #DD_NAME}: where the dataset lives and what shape its records
     * are. Resolved once, by DD-name key, so no dataset name is written into this file.
     */
    private final DatasetBinding binding;

    /**
     * The dataset as the module's one data-access contract sees it, or {@code null} when the configured
     * name is not a dataset name at all.
     *
     * <p>Resolved at construction and held, not recomposed per open, so the configured name is
     * validated once. It is <strong>deliberately nullable</strong>: the fixture-backed {@code test}
     * profile binds {@value #DD_NAME} to a filesystem location, which is not a dataset name and cannot
     * become a SQL identifier, and refusing to construct the bean at all would stop the application
     * context from starting under that profile (gate G3) even though every test and the parity harness
     * supply their own sink and need no dataset. So the refusal is deferred to
     * {@link #insertStatement()} - the one place that would otherwise compose the name into SQL - and
     * {@link #datasetRefusal} carries the reason until then.
     */
    private final DatasetRelation relation;

    /**
     * Why {@link #relation} is absent, or {@code null} when it is present.
     *
     * <p>Kept so the diagnostic is the one the dataset-name grammar produced, at the position it found
     * the fault, rather than a second description written from memory of it.
     */
    private final RuntimeException datasetRefusal;

    /**
     * Wires the writer and verifies, before the application can start, that the configured dataset
     * geometry agrees with the JCL and the COBOL file description.
     *
     * <p>Four collaborators, all constructor-injected: there is no setter and no field injection, so a
     * fully constructed instance is always usable and never half-configured (practice B9, gate G53).
     *
     * <p>The two geometry cross-checks are the point of doing any work here at all.
     * <ul>
     *   <li><strong>Record length.</strong> A binding declaring, say, 132 bytes would produce records
     *       of the wrong width for every line of every page, and the failure would surface as a mass
     *       parity diff a long way from its cause. Failing at startup with the DD name, both widths and
     *       the authoritative sources cited turns that into a one-line fix. Gate <strong>G20</strong>
     *       is this check.</li>
     *   <li><strong>Record format.</strong> {@code RECFM=FB} is what makes "every record is exactly
     *       {@value #RECORD_LENGTH} bytes" true. A binding that omitted the key, or declared
     *       {@code F}, would leave every number in this class unchanged while quietly describing a
     *       different file, so it is refused here rather than tolerated. Note that the catalogue's own
     *       validation only checks the value is <em>one of</em> {@code F} and {@code FB}; which one
     *       this DD requires is knowledge that belongs to this class.</li>
     * </ul>
     *
     * @param jdbcTemplate    the module's single {@link JdbcTemplate}
     * @param datasetCharset  the active dataset code page, resolved by the charset configuration from
     *                        {@link CobolCharsetConfig#DATASET_CHARSET_PROPERTY} and named here by bean
     *                        qualifier so no platform default can be picked up by accident
     *                        (practice B8)
     * @param datasetBindings the DD-name-keyed dataset catalogue bound from {@code carddemo.datasets}
     * @param recordImageForm how a record image crosses JDBC in this deployment
     * @throws NullPointerException  if any argument is {@code null}
     * @throws IllegalStateException if no binding is configured for {@value #DD_NAME}, which the
     *                               catalogue itself reports, or if the configured record length is not
     *                               {@value #RECORD_LENGTH}, or if the configured record format is not
     *                               {@value #RECORD_FORMAT}
     */
    public TranReportWriter(
            JdbcTemplate jdbcTemplate,
            @Qualifier(CobolCharsetConfig.DATASET_CHARSET_BEAN_NAME) Charset datasetCharset,
            DatasetBindings datasetBindings,
            RecordImageForm recordImageForm) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "A JdbcTemplate is required to "
                + "write the " + DD_NAME + " dataset; the data-source configuration declares the "
                + "single instance this module shares");
        this.recordImageForm = Objects.requireNonNull(recordImageForm, "A record-image representation "
                + "is required: whether this deployment's driver takes a record image as characters or "
                + "as bytes is stated once, by " + RecordImageForm.FORM_PROPERTY + ", and never "
                + "decided per writer");
        Objects.requireNonNull(datasetCharset, "A dataset charset is required: a fixed-width "
                + "mainframe record is bytes in a specific code page, so the code page is injected "
                + "explicitly and is never derived from the platform");
        Objects.requireNonNull(datasetBindings, "The carddemo.datasets catalogue is required to "
                + "resolve the " + DD_NAME + " dataset; dataset names are never hard-coded in Java");

        this.codec = new FixedWidthCodec(datasetCharset);
        RecordImageForm.requireSingleByteCodePage(datasetCharset);
        this.binding = datasetBindings.binding(DD_NAME);
        if (binding.recordLength() != RECORD_LENGTH) {
            throw new IllegalStateException("carddemo.datasets." + DD_NAME + " declares "
                    + "record-length " + binding.recordLength() + ", but a transaction report record "
                    + "is " + RECORD_LENGTH + " bytes: app/cbl/CBTRN03C.cbl:L85 declares "
                    + "01 " + FD_REPTFILE_REC + " PIC X(" + RECORD_LENGTH + ") and "
                    + "app/jcl/TRANREPT.jcl:L78 declares LRECL=" + RECORD_LENGTH + " on the creating "
                    + "step, as does app/proc/TRANREPT.prc:L76. Correct record-length to "
                    + RECORD_LENGTH + " in application.yml; a record width is copybook-fixed and must "
                    + "never be overridden per profile.");
        }
        if (!RECORD_FORMAT.equalsIgnoreCase(binding.recordFormat())) {
            throw new IllegalStateException("carddemo.datasets." + DD_NAME + " declares record-format "
                    + describeConfiguredRecordFormat() + ", but app/jcl/TRANREPT.jcl:L78 and "
                    + "app/proc/TRANREPT.prc:L76 both declare RECFM=" + RECORD_FORMAT + ". Fixed "
                    + "blocked is what makes every report record exactly " + RECORD_LENGTH + " bytes, "
                    + "so it is required rather than assumed. Set record-format to " + RECORD_FORMAT
                    + " in application.yml.");
        }

        // Null and empty are separated from malformed so the diagnostic says which of the two happened,
        // and so the only exception caught here is the one the dataset-name grammar raises rather than
        // any unchecked type that might come from somewhere else inside it.
        DatasetRelation resolved = null;
        RuntimeException refusal = null;
        if (binding.dsname() == null || binding.dsname().isEmpty()) {
            refusal = new IllegalArgumentException("carddemo.datasets." + DD_NAME + ".dsname is not "
                    + "configured, so there is no destination to address");
        } else {
            try {
                resolved = DatasetRelation.of(binding.dsname(), RECORD_LENGTH);
            } catch (IllegalArgumentException notADatasetName) {
                refusal = notADatasetName;
            }
        }
        this.relation = resolved;
        this.datasetRefusal = refusal;
    }

    /**
     * Renders the configured record format for the constructor's diagnostic, distinguishing an omitted
     * key from a wrong value.
     *
     * <p>They are different mistakes with different fixes - one is "the key is missing", the other is
     * "the key says {@code F}" - and a message that rendered {@code null} as the text {@code "null"}
     * would read as though the value were the four-letter word.
     *
     * @return {@code "absent"} when no record format is configured, or the configured value in quotes
     */
    private String describeConfiguredRecordFormat() {
        return binding.recordFormat() == null ? "absent" : "'" + binding.recordFormat() + "'";
    }

    // =================================================================================================
    // What was configured, exposed so a caller need not re-resolve it.
    // =================================================================================================

    /**
     * The configured binding for {@value #DD_NAME} - its location, organization, record format, block
     * size and record length exactly as configuration declares them.
     *
     * @return the binding, never {@code null}
     */
    public DatasetBinding datasetBinding() {
        return binding;
    }

    /**
     * The code page this writer encodes records in, as injected.
     *
     * <p>Exposed so a caller that needs to decode an emitted record - the parity harness, or a test -
     * uses the same explicitly chosen encoding rather than resolving one of its own.
     *
     * @return the dataset charset, never {@code null}
     */
    public Charset datasetCharset() {
        return codec.charset();
    }

    /**
     * The fixed record width in bytes, {@value #RECORD_LENGTH}, cross-checked against configuration at
     * construction.
     *
     * @return {@value #RECORD_LENGTH}
     */
    public int recordLength() {
        return RECORD_LENGTH;
    }

    // =================================================================================================
    // Opening the dataset. 0100-REPTFILE-OPEN, app/cbl/CBTRN03C.cbl:L394-L410.
    // =================================================================================================

    /**
     * Opens {@value #DD_NAME} for output against the configured dataset, mirroring
     * {@code OPEN OUTPUT REPORT-FILE} at {@code app/cbl/CBTRN03C.cbl:L396}.
     *
     * <p>Output, not append: the JCL declares {@code DISP=(NEW,CATLG,DELETE)} against a relative
     * generation ({@code app/jcl/TRANREPT.jcl:L76-L80}), so each run produces a new generation rather
     * than extending the previous one, and the COBOL opens once at {@code L162} and closes once at
     * {@code L209}. Each call returns a fresh handle with its own record area, so a caller that opens
     * twice gets two independent runs and never shares record state between them.
     *
     * <p>The COBOL open is itself a guard chain - {@code MOVE 8 TO APPL-RESULT} first, then
     * {@code '00'} to 0 or otherwise to 12, then the {@code 'ERROR OPENING REPTFILE'} arm - so the
     * outcome is reported rather than assumed: read it from {@link ReportFile#openOutcome()} and run
     * the ladder there. Two things are nevertheless refused outright rather than folded into that
     * outcome, because neither is a dataset condition the COBOL has a guard for: a configuration fault,
     * which has already failed the context at startup, and a configured name that cannot be addressed
     * as a dataset at all, which {@link #insertStatement()} reports.
     *
     * @return a new per-execution handle whose {@code FD-REPTFILE-REC} area is
     *         {@value #RECORD_LENGTH} spaces, exactly as a freshly allocated {@code PIC X(133)} FD
     *         record area is, and whose {@link ReportFile#openOutcome()} carries what the sink reported
     * @throws IllegalStateException if the configured dataset name cannot be addressed as a dataset, as
     *                               {@link #insertStatement()} describes
     * @throws NullPointerException  if the sink returns a {@code null} outcome from
     *                               {@link RecordSink#open()}
     */
    public ReportFile openOutput() {
        return new ReportFile(new JdbcRecordSink(jdbcTemplate, insertStatement(), recordImageForm,
                codec.charset(), requireRelation().describeStatement(),
                requireRelation().deleteAll(), requireRelation().countAllStatement()));
    }

    /**
     * Opens {@value #DD_NAME} for output against a caller-supplied sink.
     *
     * <p>This is the seam that makes the whole class assertable with no database, no filesystem, no
     * application context and no {@code JobLauncher} (practice B10, gate G51): a test passes a
     * collector and asserts the emitted bytes, their widths and their order directly. It is equally the
     * supported extension point for a deployment whose data-access driver expects a different parameter
     * shape from the default sink's.
     *
     * @param sink where rendered records go; must not be {@code null}
     * @return a new per-execution handle, whose {@link ReportFile#openOutcome()} carries what the sink
     *         reported from {@link RecordSink#open()}
     * @throws NullPointerException if {@code sink} is {@code null}, or if the sink returns a
     *                              {@code null} outcome from {@link RecordSink#open()}
     */
    public ReportFile openOutput(RecordSink sink) {
        return new ReportFile(Objects.requireNonNull(sink, "A record sink is required to open "
                + DD_NAME + " for output; call openOutput() for the configured dataset"));
    }

    /**
     * The single-parameter statement the default sink issues for one record, composed by the module's
     * one data-access contract.
     *
     * <p>Every character of it comes from {@link DatasetRelation#insertRecordImage()} - the identifier
     * rendering included - and that matters more than it looks. Three sequential outputs in this module
     * are written by sibling classes against the same deployment driver, so if each rendered the
     * configured dataset name its own way, at most one of them could be right and the others would
     * fail, or worse address something else, on a backend nobody here can exercise. Routing them all
     * through one renderer makes the statements differ in exactly one respect: which dataset they name.
     *
     * <p>What that shape asserts, and why:
     * <ul>
     *   <li><strong>No column list.</strong> This migration introduces no schema, no data-definition
     *       statement, no entity mapping and no version column (gate G44), and an output-only dataset is
     *       never described, so there is no column name to be had. The record is one fixed-width image
     *       and is bound positionally, at
     *       {@link DatasetRelation#RECORD_IMAGE_COLUMN_INDEX}.</li>
     *   <li><strong>The dataset name is one delimited identifier.</strong> A mainframe dataset name
     *       contains dots, which an SQL parser would otherwise read as a qualified
     *       catalogue-schema-table reference.</li>
     *   <li><strong>The name is validated as a dataset name before it is rendered.</strong> It arrives
     *       from configuration, which is externally controlled, and reaches a position no bind parameter
     *       can occupy, so it must satisfy the z/OS dataset-name grammar rather than merely survive a
     *       scan for punctuation somebody thought of. That check happened at construction; this method
     *       reports its verdict.</li>
     *   <li><strong>The name comes from configuration, verbatim.</strong> Resolved by DD-name key from
     *       {@code carddemo.datasets}, so no mainframe dataset literal appears in this file or anywhere
     *       else in the Java sources (gate G46).</li>
     * </ul>
     *
     * <p>Package-visible so its text is asserted directly by a unit test rather than inferred from a
     * database round trip.
     *
     * @return the parameterised statement
     * @throws IllegalStateException if {@code carddemo.datasets.}{@value #DD_NAME}{@code .dsname} is
     *                               absent, or is not a well-formed z/OS dataset name - which includes
     *                               the filesystem location the fixture-backed {@code test} profile
     *                               binds. In that case write through {@link #openOutput(RecordSink)}
     *                               with a caller-supplied sink instead, exactly as every unit test and
     *                               the parity harness do
     */
    String insertStatement() {
        return requireRelation().insertRecordImage();
    }

    /**
     * The resolved relation, or a refusal naming the configuration that could not be addressed.
     *
     * <p>Refused here rather than at startup for the reason the constructor records: a profile may
     * legitimately bind this DD name to something no JDBC statement can address, and in that case the
     * right outcome is a refusal when a sink is actually built - a fixture-backed profile, every unit
     * test and the parity harness all write through {@link #openOutput(RecordSink)} instead and never
     * reach this.
     *
     * @return the relation; never {@code null}
     * @throws IllegalStateException if the configured name is not a dataset name
     */
    private DatasetRelation requireRelation() {
        if (relation == null) {
            throw new IllegalStateException("carddemo.datasets." + DD_NAME + ".dsname cannot be "
                    + "addressed as a dataset, so no statement can be composed for it and the default "
                    + "sink cannot be built. Set it to a well-formed z/OS dataset name, or write "
                    + "through openOutput(RecordSink) with your own sink - which is what a "
                    + "fixture-backed profile, every unit test and the parity harness do, and why this "
                    + "is refused here rather than at startup. The grammar's own verdict is attached.",
                    datasetRefusal);
        }
        return relation;
    }

    /**
     * The default {@link RecordSink}: one parameterised insert of the whole record image per record,
     * issued immediately and in call order.
     *
     * <p>Immutable and stateless, so it is safe to hold and safe to share; the pooled connection is
     * borrowed and returned inside each call, which is why {@link RecordSink#close()} has nothing to do
     * and is left defaulted. {@link RecordSink#open()} is <em>not</em> left defaulted: holding no
     * connection is not the same as having no destination, and whether the configured relation can be
     * addressed at all is what {@code OPEN OUTPUT} answers.
     *
     * <p>The record image is bound through the injected {@link RecordImageForm} - the module's single
     * authority on whether a record image crosses JDBC as characters or as bytes. The code page
     * decision was already taken, explicitly, when the record area encoded the image; what this sink
     * must not do is take a second decision about the column's JDBC type (practice B8).
     */
    private static final class JdbcRecordSink implements RecordSink {

        /** The template that issues the insert. */
        private final JdbcTemplate jdbcTemplate;

        /** The parameterised statement, built once by {@link TranReportWriter#insertStatement()}. */
        private final String statement;

        /** How the record image crosses JDBC: the deployment's answer, not this sink's. */
        private final RecordImageForm recordImageForm;

        /** The dataset code page, needed by the representation to bind a character image. */
        private final Charset charset;

        /**
         * A read-only statement that resolves and describes the destination without transferring any of
         * it - the probe both {@link #open()} and {@link #close()} use to establish that the relation is
         * actually addressable.
         */
        private final String describeStatement;

        /**
         * Empties the destination, which is what {@code DISP=(NEW,CATLG,DELETE)} means for a relation
         * that already exists. Issued by {@link #open()} and nowhere else.
         */
        private final String clearStatement;

        /**
         * Counts what the destination holds, so {@link #discard(int)} can establish that the generation it
         * is about to delete is the one this run allocated. Read-only, and issued nowhere else.
         */
        private final String countStatement;

        /**
         * Creates the sink.
         *
         * @param jdbcTemplate      the template that issues the insert
         * @param statement         the parameterised statement
         * @param recordImageForm   how a record image crosses JDBC in this deployment
         * @param charset           the dataset code page
         * @param describeStatement the read-only probe {@link #open()} and {@link #close()} issue
         * @param clearStatement    the statement {@link #open()} issues to establish an empty generation
         * @param countStatement    the read-only count {@link #discard(int)} issues before deleting
         */
        JdbcRecordSink(JdbcTemplate jdbcTemplate, String statement, RecordImageForm recordImageForm,
                       Charset charset, String describeStatement, String clearStatement,
                       String countStatement) {
            this.jdbcTemplate = jdbcTemplate;
            this.statement = statement;
            this.recordImageForm = recordImageForm;
            this.charset = charset;
            this.describeStatement = describeStatement;
            this.clearStatement = clearStatement;
            this.countStatement = countStatement;
        }

        /**
         * Establishes the generation this run writes into: {@code OPEN OUTPUT REPORT-FILE} at
         * {@code app/cbl/CBTRN03C.cbl:L396}, over a dataset {@code app/jcl/TRANREPT.jcl:L76-L80}
         * declares {@code DISP=(NEW,CATLG,DELETE)}.
         *
         * <p>Two statements, in this order, and each is doing something the COBOL open does:
         * <ol>
         *   <li><strong>Describe.</strong> An {@code OPEN} on the mainframe resolves the DD name to a
         *       real dataset and fails if it cannot. The describe is read-only - its predicate is false
         *       on every row - so it resolves and describes the relation while none of it is
         *       transferred, and a destination that does not exist, cannot be reached or is refused by
         *       the credentials is reported here rather than record by record.</li>
         *   <li><strong>Clear.</strong> {@code NEW} means the run writes into an <em>empty</em>
         *       generation. Emptying it is what makes the previous run's report cease to be part of this
         *       one, and it is also what leaves an empty report behind when a run writes no line at all -
         *       a dataset the JCL created and the program left empty, rather than no dataset. Nothing
         *       touches the relation's definition: no data-definition statement is issued anywhere in
         *       this module (gate G44).</li>
         * </ol>
         *
         * <p>Either statement failing is the {@code TRANREPT-STATUS NOT = '00'} arm at
         * {@code app/cbl/CBTRN03C.cbl:L404-L409}, which displays {@code 'ERROR OPENING REPTFILE'} and
         * abends with {@code APPL-RESULT} 12. That is the whole outcome vocabulary the COBOL has for an
         * open, so it is the whole vocabulary reported.
         *
         * @return {@link FileStatus.Outcome#OK} when the destination is established, or
         *         {@link FileStatus.Outcome#OTHER} when it could not be
         */
        @Override
        public FileStatus.Outcome open() {
            try {
                jdbcTemplate.execute(describeStatement);
                jdbcTemplate.update(clearStatement);
                return FileStatus.Outcome.OK;
            } catch (DataAccessException refused) {
                LOG.error("Could not establish the " + DD_NAME + " generation for output - "
                        + BackendDiagnostic.of(refused).describe()
                        + "; reporting FILE STATUS outcome " + FileStatus.Outcome.OTHER.name()
                        + " to the caller, which is the ERROR OPENING REPTFILE arm that sets "
                        + "APPL-RESULT to 12");
                return FileStatus.Outcome.OTHER;
            }
        }

        /**
         * Confirms the destination survived the run: {@code CLOSE REPORT-FILE} at
         * {@code app/cbl/CBTRN03C.cbl:L534}.
         *
         * <p>There is nothing buffered to flush - each record was inserted as it was written, through a
         * connection borrowed and returned per record - so what a close can still discover is that the
         * destination is no longer there: a relation dropped, revoked or unreachable part-way through a
         * report. The same read-only describe {@link #open()} used answers that, and a failure is the
         * {@code 'ERROR CLOSING REPORT FILE'} arm at {@code app/cbl/CBTRN03C.cbl:L540-L545}. A close
         * that could not fail would leave that arm unreachable, which is precisely what the COBOL's own
         * guard chain says must not be true.
         *
         * @return {@link FileStatus.Outcome#OK} when the destination is still addressable, or
         *         {@link FileStatus.Outcome#OTHER} when it is not
         */
        @Override
        public FileStatus.Outcome close() {
            try {
                jdbcTemplate.execute(describeStatement);
                return FileStatus.Outcome.OK;
            } catch (DataAccessException refused) {
                LOG.error("Could not confirm the " + DD_NAME + " destination on close - "
                        + BackendDiagnostic.of(refused).describe()
                        + "; reporting FILE STATUS outcome " + FileStatus.Outcome.OTHER.name()
                        + " to the caller, which is the ERROR CLOSING REPORT FILE arm");
                return FileStatus.Outcome.OTHER;
            }
        }

        /**
         * Deletes the generation this run wrote: the {@code DELETE} positional of
         * {@code app/jcl/TRANREPT.jcl:L76-L80}.
         *
         * <p><strong>Refused unless the destination holds exactly this run's records.</strong>
         * {@code NEW} means the step allocates the generation, so a faithful deployment gives a run a
         * relation of its own and the two counts agree. A deployment that instead maps successive
         * generations onto one relation would have this delete a previously published report, so the count
         * is read first and a disagreement is reported rather than acted on. That is deliberately loud: it
         * is a deployment-time binding question, and deleting a report an operator is already reading
         * would be far worse than that operator seeing an outcome.
         *
         * <p>Never throws. The caller is already abending.
         *
         * @param recordsWritten how many records this run handed to this sink
         * @return {@link FileStatus.Outcome#OK} when the generation was discarded, or
         *         {@link FileStatus.Outcome#OTHER} when it was not
         */
        @Override
        public FileStatus.Outcome discard(int recordsWritten) {
            try {
                Integer held = jdbcTemplate.queryForObject(countStatement, Integer.class);
                if (held == null || held != recordsWritten) {
                    LOG.error("Refusing to apply the " + DD_NAME + " abnormal disposition of "
                            + "app/jcl/TRANREPT.jcl:L76: this run wrote " + recordsWritten
                            + " record(s) but the destination holds " + held
                            + ". DISP=(NEW,CATLG,DELETE) deletes the generation this step allocated, so "
                            + "a destination holding records this step did not write is not that "
                            + "generation. Leaving it untouched and reporting FILE STATUS outcome "
                            + FileStatus.Outcome.OTHER.name() + "; bind " + DD_NAME
                            + " to a relation of its own so each run allocates its own generation");
                    return FileStatus.Outcome.OTHER;
                }
                int removed = jdbcTemplate.update(clearStatement);
                if (removed == recordsWritten) {
                    return FileStatus.Outcome.OK;
                }
                LOG.error("The " + DD_NAME + " abnormal disposition removed " + removed
                        + " record(s) where this run wrote " + recordsWritten
                        + "; reporting FILE STATUS outcome " + FileStatus.Outcome.OTHER.name()
                        + " rather than reporting the generation as discarded");
                return FileStatus.Outcome.OTHER;
            } catch (DataAccessException refused) {
                LOG.error("Could not apply the " + DD_NAME + " abnormal disposition after "
                        + recordsWritten + " record(s) - " + BackendDiagnostic.of(refused).describe()
                        + "; reporting FILE STATUS outcome " + FileStatus.Outcome.OTHER.name()
                        + ". A partial report may remain catalogued, which DISP=(NEW,CATLG,DELETE) says "
                        + "it should not; its totals are not trustworthy and it must be deleted by hand");
                return FileStatus.Outcome.OTHER;
            }
        }

        /**
         * Writes one record, mapping a rejected write onto the arm that sets {@code APPL-RESULT} to 12.
         *
         * <p>Only {@link DataAccessException} is caught, and that narrowness is deliberate: it is the
         * family Spring translates a genuine data-access failure into. A configuration defect such as
         * an unset data source raises a different, unchecked type and is left to propagate, because
         * mapping it to a file-status outcome would let a misconfigured deployment abend with a
         * misleading reason for every line of every page instead of failing once, clearly.
         *
         * <p>The row count the statement returns is deliberately not inspected.
         * {@code WRITE FD-REPTFILE-REC} declares no {@code INVALID KEY} and no {@code AT END} phrase,
         * and the program's only test is {@code TRANREPT-STATUS = '00'}, so anything short of a raised
         * failure is a completed write and inventing a stricter check here would reject records the
         * COBOL accepts.
         *
         * @param recordImage the record's bytes in the dataset code page
         * @return {@link FileStatus.Outcome#OK}, or {@link FileStatus.Outcome#OTHER} when the write was
         *         rejected
         */
        @Override
        public FileStatus.Outcome write(byte[] recordImage) {
            PreparedStatementSetter binder = parameters -> recordImageForm.bindImage(parameters,
                    DatasetRelation.RECORD_IMAGE_COLUMN_INDEX, recordImage, charset);
            try {
                jdbcTemplate.update(statement, binder);
                return FileStatus.Outcome.OK;
            } catch (DataAccessException rejected) {
                // The reason is logged because the outcome travelling back to the caller is deliberately
                // coarse - the COBOL guard chain has one failure arm - and discarding it would leave a
                // production abend undiagnosable. What is logged is the backend's SQLSTATE, vendor code
                // and exception type; the exception itself is not, because a driver's message is prose it
                // composed around the record it refused, in text a control character could split into a
                // second log entry.
                LOG.error("Rejected write of a " + RECORD_LENGTH + "-byte " + DD_NAME
                        + " record - " + BackendDiagnostic.of(rejected).describe()
                        + "; reporting FILE STATUS outcome " + FileStatus.Outcome.OTHER.name()
                        + " to the caller, which is the arm that sets APPL-RESULT to 12");
                return FileStatus.Outcome.OTHER;
            }
        }
    }

    // =================================================================================================
    // The per-execution handle. EVERY piece of mutable state in this file lives here.
    // =================================================================================================

    /**
     * One opened {@value #DD_NAME} dataset, together with the {@value #RECORD_LENGTH}-byte
     * {@code FD-REPTFILE-REC} area that records are moved into and written from.
     *
     * <p>This type exists so the writer bean can be a stateless singleton. A COBOL record area is
     * mutable and per-run; making it a field on a singleton would let two concurrent report runs
     * overwrite each other's line and would make a test's outcome depend on what ran before it. So the
     * area, the sink, the open flag and the record count are all here, obtained from
     * {@link TranReportWriter#openOutput()} and discarded at the end of the run (practice B9,
     * gate G53).
     *
     * <p>A handle is <strong>not</strong> thread-safe, exactly as a COBOL record area is not, and is
     * meant to be confined to the step, chunk or test that opened it. It implements
     * {@link AutoCloseable} so a try-with-resources block reproduces the {@code OPEN OUTPUT} /
     * {@code CLOSE} pairing of {@code app/cbl/CBTRN03C.cbl:L162} and {@code L209};
     * {@link #closeOutput()} is the form that returns the outcome.
     *
     * <p>A freshly opened handle's area is {@value #RECORD_LENGTH} spaces. That is what a newly
     * allocated {@code PIC X(133)} FD record area contains, and it means a caller that wrote nothing
     * into it and issued {@link #writeReportRec()} anyway would emit a blank line rather than
     * something undefined - the same record {@code WS-BLANK-LINE} produces.
     */
    public final class ReportFile implements AutoCloseable {

        /** Where rendered records go, in call order. */
        private final RecordSink sink;

        /**
         * The {@code 01 FD-REPTFILE-REC PIC X(133)} area, exactly as the FD declares it: one span,
         * {@value #RECORD_LENGTH} bytes, no subordinate fields.
         */
        private final FixedWidthRecord reportRecord;

        /**
         * What the sink reported when the destination was prepared: the answer to
         * {@code OPEN OUTPUT REPORT-FILE}, held so the job can branch on it.
         *
         * <p>Final, because an open happens once and its verdict does not change afterwards.
         */
        private final FileStatus.Outcome openOutcome;

        /** Whether the dataset is still open. Set once, by {@link #closeOutput()}. */
        private boolean open;

        /** How many records have been handed to the sink, successfully or not. */
        private int recordsWritten;

        /**
         * Whether the abnormal disposition has already been applied, so {@link #discardGeneration()} is
         * idempotent.
         *
         * <p>It has to be: an abnormal path can reach a cleanup more than once - a {@code finally} inside a
         * {@code finally}, or a caller that discards and then closes through try-with-resources - and a
         * second delete would find a count of zero against a non-zero {@code recordsWritten} and report a
         * refusal for work that had already succeeded.
         */
        private boolean discarded;

        /**
         * Allocates the record area and prepares the destination, in that order.
         *
         * <p>The area comes first because it exists independently of the file - a COBOL record area is
         * {@code WORKING-STORAGE}-like storage that a failed {@code OPEN} does not unallocate - and
         * {@link FixedWidthCodec#newRecord(RecordLayout)} establishes it as {@value #RECORD_LENGTH}
         * spaces. The sink's {@link RecordSink#open()} is then called exactly once and its answer is
         * kept for {@link #openOutcome()}.
         *
         * <p>A non-{@code OK} open leaves the handle usable and open, deliberately. The COBOL abends on
         * that arm ({@code app/cbl/CBTRN03C.cbl:L405-L408}) but the abend is the report job's to raise,
         * and a writer that pre-closed the handle or threw would take the decision away from it - and
         * would do so inconsistently with how a rejected {@code WRITE} is treated three lines further
         * down. The writer reports; the job decides.
         *
         * @param sink where rendered records go
         * @throws NullPointerException if the sink returns a {@code null} outcome from
         *                              {@link RecordSink#open()}
         */
        private ReportFile(RecordSink sink) {
            this.sink = sink;
            this.reportRecord = codec.newRecord(FD_REPTFILE_REC_LAYOUT);
            this.open = true;
            this.openOutcome = Objects.requireNonNull(sink.open(),
                    "The record sink supplied for " + DD_NAME + " returned a null outcome from "
                            + "open(). A sink must report FileStatus.Outcome.OK when the destination "
                            + "is ready or FileStatus.Outcome.OTHER otherwise, because there is no "
                            + "COBOL FILE STATUS meaning 'no answer'.");
        }

        /**
         * What the sink reported when this handle was opened: the outcome of
         * {@code OPEN OUTPUT REPORT-FILE} in {@code 0100-REPTFILE-OPEN}
         * ({@code app/cbl/CBTRN03C.cbl:L394-L410}).
         *
         * <p>This is the third of the three outcomes a report job needs, and it completes the set: the
         * open here, the write from {@link #writeReportRec()}, and the close from
         * {@link #closeOutput()}. The COBOL runs the same shape of guard over all three - test
         * {@code TRANREPT-STATUS = '00'}, set {@code APPL-RESULT} to 0 or 12, and on the failing arm
         * display its own message ({@code 'ERROR OPENING REPTFILE'} at {@code L405}) before abending -
         * so all three are reported here and none of them is decided here.
         *
         * <p>Stable for the life of the handle, and unaffected by anything that happens afterwards: a
         * later rejected write does not retroactively make the open a failure.
         *
         * @return the open outcome, never {@code null}
         */
        public FileStatus.Outcome openOutcome() {
            return openOutcome;
        }

        // -------------------------------------------------------------------------------------------
        // MOVE <layout> TO FD-REPTFILE-REC. The padding rule, in one place.
        // -------------------------------------------------------------------------------------------

        /**
         * Performs {@code MOVE <layout> TO FD-REPTFILE-REC}: normalises a rendered layout image to
         * exactly {@value #RECORD_LENGTH} characters and stores it in the record area, without writing.
         *
         * <p>This is the single implementation of the padding rule, and it is deliberately the COBOL
         * rule rather than a convenience:
         * <ul>
         *   <li><strong>Shorter than {@value #RECORD_LENGTH} - right-padded with spaces.</strong> A
         *       COBOL alphanumeric receiver is filled from its leftmost position and the remainder is
         *       space-filled, which is what turns the 115-byte {@code REPORT-NAME-HEADER} into a
         *       133-byte record with {@value #REPORT_NAME_HEADER_PAD} trailing spaces, the two
         *       114-byte layouts into records with {@value #TRANSACTION_HEADER_1_PAD}, and the three
         *       112-byte totals into records with {@value #REPORT_PAGE_TOTALS_PAD}.</li>
         *   <li><strong>Exactly {@value #RECORD_LENGTH} - passes through untouched.</strong> Neither
         *       padded nor truncated, which is what keeps {@code TRANSACTION-HEADER-2}'s
         *       {@value #RECORD_LENGTH} hyphens and {@code WS-BLANK-LINE}'s
         *       {@value #RECORD_LENGTH} spaces byte-identical to their declarations.</li>
         *   <li><strong>Longer than {@value #RECORD_LENGTH} - truncated on the RIGHT.</strong> The
         *       leading {@value #RECORD_LENGTH} characters survive and the overflow is discarded, so
         *       {@code "AB…"} in a 140-character image yields the first 133 of them, never the last
         *       133. It does not throw and it does not wrap onto a second record: COBOL discards, and a
         *       writer that threw here would abend a report the mainframe would have produced.</li>
         * </ul>
         *
         * <p>The direction of both the pad and the truncation comes from
         * {@link FixedWidthCodec#writePicX(FixedWidthRecord, FieldSpan, String)}, so it is chosen by a
         * named rule rather than incidentally by a substring somebody wrote at a call site. A plain Java
         * assignment would neither pad nor truncate, and the resulting parity defect would be invisible
         * where it was introduced.
         *
         * <p>Separate from {@link #writeReportRec()} because the COBOL separates them: every caller
         * paragraph moves first and performs the write paragraph second, and
         * {@code 1110-WRITE-PAGE-TOTALS} ({@code app/cbl/CBTRN03C.cbl:L293-L304}) does it twice in
         * succession with two different layouts. {@link #writeLine(String)} fuses the pair for the
         * common case.
         *
         * @param layoutImage the rendered layout image - one of {@link TranReportLayouts}'
         *                    {@code render…} results, or {@link TranReportWriter#WS_BLANK_LINE_IMAGE}.
         *                    Any length is accepted, including empty, and is normalised as described
         *                    above
         * @throws NullPointerException  if {@code layoutImage} is {@code null}. A COBOL {@code MOVE}
         *                               has no null sender; to blank the record move
         *                               {@link TranReportWriter#WS_BLANK_LINE_IMAGE} or an empty string
         *                               explicitly
         * @throws IllegalStateException if this handle has already been closed
         */
        public void moveToReportRecord(String layoutImage) {
            Objects.requireNonNull(layoutImage, "A sending value is required to MOVE into "
                    + FD_REPTFILE_REC + "; to blank the record move WS_BLANK_LINE_IMAGE or an empty "
                    + "string explicitly rather than null");
            requireOpen("MOVE a layout into " + FD_REPTFILE_REC);
            codec.writePicX(reportRecord, FD_REPTFILE_REC_LAYOUT.span(FD_REPTFILE_REC), layoutImage);
        }

        /**
         * Performs {@code MOVE <layout> TO FD-REPTFILE-REC} from a rendered layout image already held
         * as bytes.
         *
         * <p>Provided because {@link TranReportLayouts} renders every layout both ways - as characters
         * and as {@code render…Bytes()} - and a caller holding the byte form should not have to decode
         * it with a charset of its own choosing. The bytes are decoded strictly, under this writer's
         * injected code page, and then take exactly the same normalisation path as
         * {@link #moveToReportRecord(String)}; the code page is single-byte by construction, so the
         * round trip is lossless.
         *
         * @param layoutImage the rendered layout image in the dataset code page; any length is accepted
         * @throws NullPointerException  if {@code layoutImage} is {@code null}
         * @throws IllegalStateException if a byte is not valid data in the dataset code page, or if this
         *                               handle has already been closed
         */
        public void moveToReportRecord(byte[] layoutImage) {
            Objects.requireNonNull(layoutImage, "Sending bytes are required to MOVE into "
                    + FD_REPTFILE_REC);
            moveToReportRecord(codec.decodeImage(layoutImage, FD_REPTFILE_REC));
        }

        /**
         * The current content of {@code FD-REPTFILE-REC}: exactly {@value #RECORD_LENGTH} characters,
         * trailing pad included.
         *
         * <p>Untrimmed, deliberately. The pad is part of the record - it is what makes the record 133
         * bytes - and the parity differ compares byte for byte, so trimming here would discard exactly
         * the bytes it is meant to compare.
         *
         * @return the record image, exactly {@value #RECORD_LENGTH} characters
         */
        public String reportRecord() {
            return codec.readPicX(reportRecord, FD_REPTFILE_REC_LAYOUT.span(FD_REPTFILE_REC));
        }

        /**
         * The current content of {@code FD-REPTFILE-REC} as the bytes that would reach the dataset:
         * exactly {@value #RECORD_LENGTH} of them, in the injected code page.
         *
         * @return a fresh array of exactly {@value #RECORD_LENGTH} bytes, never the record's own
         *         backing array
         */
        public byte[] reportRecordBytes() {
            return reportRecord.toByteArray();
        }

        // -------------------------------------------------------------------------------------------
        // 1111-WRITE-REPORT-REC. app/cbl/CBTRN03C.cbl:L343-L359.
        // -------------------------------------------------------------------------------------------

        /**
         * Writes whatever {@code FD-REPTFILE-REC} currently holds, reproducing
         * {@code WRITE FD-REPTFILE-REC} at {@code app/cbl/CBTRN03C.cbl:L345} - the program's one and
         * only write statement for this dataset.
         *
         * <p>One call, one record, immediately: nothing is batched, reordered, coalesced or skipped.
         * Calling it twice without an intervening move writes the same record twice, which is what the
         * COBOL would do and is therefore what it must do here.
         *
         * <p>A rejected write is returned as {@link FileStatus.Outcome#OTHER} rather than thrown, so
         * the report job runs its own {@code '00'}-or-12 ladder, displays
         * {@code 'ERROR WRITING REPTFILE'} and decides whether to abend. Writing to a handle that has
         * already been closed is a different thing entirely: that is a caller sequencing defect rather
         * than a dataset condition, and it throws, because reporting it as an ordinary failed write
         * would let a job quietly lose lines it believes it wrote.
         *
         * <p>A sink that answers {@code null} is a third thing again, and it is rejected here rather
         * than returned. {@code null} is not a {@code FILE STATUS} the COBOL can branch on, so carrying
         * it forward would let it reach {@link #close()} and surface there as an unrelated
         * {@link NullPointerException} - at a point where the sink that produced it is no longer in the
         * stack trace.
         *
         * @return {@link FileStatus.Outcome#OK} when the record was accepted, or
         *         {@link FileStatus.Outcome#OTHER} when the sink rejected it; never {@code null}
         * @throws NullPointerException  if the sink returns a {@code null} outcome
         * @throws IllegalStateException if this handle has already been closed
         */
        public FileStatus.Outcome writeReportRec() {
            requireOpen("WRITE " + FD_REPTFILE_REC);
            FileStatus.Outcome outcome = Objects.requireNonNull(sink.write(reportRecordBytes()),
                    "The record sink supplied for " + DD_NAME + " returned a null outcome from "
                            + "write(byte[]) after " + recordsWritten + " record(s). A sink must "
                            + "report FileStatus.Outcome.OK for the TRANREPT-STATUS = '00' arm or "
                            + "FileStatus.Outcome.OTHER for any failure - the arm that sets "
                            + "APPL-RESULT to 12 - because there is no COBOL FILE STATUS meaning "
                            + "'no answer'.");
            recordsWritten++;
            return outcome;
        }

        /**
         * Moves a rendered layout image into {@code FD-REPTFILE-REC} and writes it: the fused
         * {@code MOVE} plus {@code PERFORM 1111-WRITE-REPORT-REC} that every caller paragraph in
         * {@code app/cbl/CBTRN03C.cbl} performs.
         *
         * <p>This is the method a report job normally calls, once per line, in line order. Every rule
         * described on {@link #moveToReportRecord(String)} and {@link #writeReportRec()} applies
         * unchanged - so the record is exactly {@value #RECORD_LENGTH} bytes whatever the layout's
         * natural width, a full-width image passes through untouched, and an over-long one is truncated
         * on the right rather than rejected.
         *
         * @param layoutImage the rendered layout image; must not be {@code null}
         * @return {@link FileStatus.Outcome#OK} when the record was accepted, or
         *         {@link FileStatus.Outcome#OTHER} when the sink rejected it; never {@code null}
         * @throws NullPointerException  if {@code layoutImage} is {@code null}, or if the sink returns a
         *                               {@code null} outcome
         * @throws IllegalStateException if this handle has already been closed
         */
        public FileStatus.Outcome writeLine(String layoutImage) {
            moveToReportRecord(layoutImage);
            return writeReportRec();
        }

        /**
         * Moves a rendered layout image held as bytes into {@code FD-REPTFILE-REC} and writes it.
         *
         * @param layoutImage the rendered layout image in the dataset code page; must not be
         *                    {@code null}
         * @return {@link FileStatus.Outcome#OK} when the record was accepted, or
         *         {@link FileStatus.Outcome#OTHER} when the sink rejected it; never {@code null}
         * @throws NullPointerException  if {@code layoutImage} is {@code null}, or if the sink returns a
         *                               {@code null} outcome
         * @throws IllegalStateException if a byte is not valid data in the dataset code page, or if this
         *                               handle has already been closed
         */
        public FileStatus.Outcome writeLine(byte[] layoutImage) {
            moveToReportRecord(layoutImage);
            return writeReportRec();
        }

        /**
         * How many records have been handed to the sink through this handle.
         *
         * <p>Counts every record handed over, including one the sink rejected, because the count answers
         * "how far did this run get" - which is what a diagnostic needs - rather than "how many
         * succeeded". It is emphatically <strong>not</strong> {@code WS-LINE-COUNTER}: that counter
         * paginates, is incremented by the calling paragraphs at their own points, and is not
         * incremented at all by {@code 1110-WRITE-GRAND-TOTALS}. It lives in the report job.
         *
         * @return the record count, never negative
         */
        public int recordsWritten() {
            return recordsWritten;
        }

        /**
         * Whether this handle is still open for writing.
         *
         * @return {@code true} until {@link #closeOutput()} or {@link #close()} has run
         */
        public boolean isOpen() {
            return open;
        }

        /**
         * Closes the dataset, reproducing {@code CLOSE REPORT-FILE} in {@code 9100-REPTFILE-CLOSE} at
         * {@code app/cbl/CBTRN03C.cbl:L534}, and reports the outcome.
         *
         * <p>Idempotent: closing an already-closed handle is reported as {@link FileStatus.Outcome#OK}
         * and does not reach the sink a second time, so a try-with-resources block around an explicit
         * close is harmless.
         *
         * <p>The handle is marked closed before the sink is reached, so a sink that violates its
         * contract by answering {@code null} - which is rejected here, exactly as in
         * {@link #writeReportRec()} - still cannot be closed a second time.
         *
         * <p>As with the write, a non-{@code OK} outcome is returned rather than thrown: the
         * {@code 'ERROR CLOSING REPORT FILE'} display at {@code L543} and the abend that follows it
         * belong to the report job.
         *
         * @return {@link FileStatus.Outcome#OK} when the sink closed cleanly or was already closed, or
         *         {@link FileStatus.Outcome#OTHER} otherwise; never {@code null}
         * @throws NullPointerException if the sink returns a {@code null} outcome
         */
        public FileStatus.Outcome closeOutput() {
            if (!open) {
                return FileStatus.Outcome.OK;
            }
            open = false;
            return Objects.requireNonNull(sink.close(),
                    "The record sink supplied for " + DD_NAME + " returned a null outcome from "
                            + "close() after " + recordsWritten + " record(s). A sink must report "
                            + "FileStatus.Outcome.OK when it closed cleanly or "
                            + "FileStatus.Outcome.OTHER otherwise, because there is no COBOL FILE "
                            + "STATUS meaning 'no answer'.");
        }

        /**
         * Applies the abnormal disposition of {@code app/jcl/TRANREPT.jcl:L76-L80} -
         * {@code DISP=(NEW,CATLG,DELETE)} - by discarding the report this run wrote.
         *
         * <p><strong>Call this only when the step is ending abnormally</strong>, and after
         * {@link #closeOutput()}. The two are separate on purpose, because {@code DISP} says they are:
         * {@code CATLG} is the normal disposition and a close alone reproduces it, leaving the report
         * catalogued for whoever prints it. {@code DELETE} is the abnormal one, and a run that abends must
         * leave no report at all - which for this dataset is the point, because a report truncated at the
         * page a run failed on carries page totals without the account and grand totals that make them
         * mean anything. Closing then discarding is the order the mainframe uses.
         *
         * <p>The record count is owned here rather than passed in, so a caller cannot get it wrong: it is
         * the same counter every write increments, and it is what lets the sink establish that the
         * generation it is deleting is the one this run allocated.
         *
         * <p>Idempotent, and it never throws - not even a {@link NullPointerException} for a sink that
         * breaks its contract by answering {@code null}. Every other outcome on this handle is checked for
         * {@code null} and refuses, because a caller can still act on the refusal; here the caller is
         * already abending, and replacing the abend that a report failure caused with a diagnostic about
         * the cleanup would lose the reason the run failed. A {@code null} is therefore logged and read as
         * {@link FileStatus.Outcome#OTHER}.
         *
         * @return {@link FileStatus.Outcome#OK} when the generation was discarded, when this run wrote
         *         nothing, or when the disposition had already been applied; otherwise
         *         {@link FileStatus.Outcome#OTHER}; never {@code null}
         */
        public FileStatus.Outcome discardGeneration() {
            if (discarded || recordsWritten == 0) {
                // Nothing was written, so there is no generation to delete. On the mainframe the step
                // still allocates and still deletes an empty dataset; there is no observable difference.
                discarded = true;
                return FileStatus.Outcome.OK;
            }
            discarded = true;
            FileStatus.Outcome outcome = sink.discard(recordsWritten);
            if (outcome == null) {
                LOG.error("The record sink supplied for " + DD_NAME + " returned a null outcome from "
                        + "discard(int) after " + recordsWritten + " record(s); reading it as FILE "
                        + "STATUS outcome " + FileStatus.Outcome.OTHER.name()
                        + " rather than raising, because this path is already abending");
                return FileStatus.Outcome.OTHER;
            }
            return outcome;
        }

        /**
         * Closes the dataset for a try-with-resources block.
         *
         * <p>{@link AutoCloseable#close()} cannot return a value, so a non-{@code OK} outcome is logged
         * rather than discarded, and it is not turned into an exception: throwing from {@code close()}
         * would mask whatever the block itself was doing. A caller that needs to act on the outcome -
         * the report job, deciding on an abend - calls {@link #closeOutput()} instead.
         *
         * <p>A dataset condition and a broken sink are treated differently, deliberately.
         * {@code OTHER} is a dataset condition the COBOL has a guard for, so it is logged and the block
         * continues; a {@code null} outcome is a sink that does not implement its contract, so the
         * {@link NullPointerException} {@link #closeOutput()} raises is allowed to propagate. That
         * cannot mask a failure in the block either: a try-with-resources block whose body already threw
         * records a {@code close()} failure as a suppressed exception rather than replacing the primary
         * one.
         *
         * @throws NullPointerException if the sink returns a {@code null} outcome from
         *                              {@link RecordSink#close()}
         */
        @Override
        public void close() {
            FileStatus.Outcome outcome = closeOutput();
            if (outcome != FileStatus.Outcome.OK) {
                LOG.error("Closing " + DD_NAME + " after " + recordsWritten
                        + " record(s) reported FILE STATUS outcome " + outcome.name()
                        + "; call closeOutput() rather than close() to handle this in the caller");
            }
        }

        /**
         * Refuses an operation on a closed handle, naming what was attempted and how far the run got.
         *
         * <p>One implementation rather than three copies of the same guard, so the move, the write and
         * the fused pair cannot drift apart in what they permit.
         *
         * @param attempt what the caller was trying to do, in COBOL terms
         * @throws IllegalStateException always, when the handle is closed
         */
        private void requireOpen(String attempt) {
            if (!open) {
                throw new IllegalStateException("Cannot " + attempt + " on " + DD_NAME
                        + ": this handle was closed after " + recordsWritten + " record(s). The COBOL "
                        + "opens the dataset once at the start of the run "
                        + "(app/cbl/CBTRN03C.cbl:L162) and closes it once at the end (L209), so open a "
                        + "new handle for a new run rather than reusing a closed one.");
            }
        }
    }
}
