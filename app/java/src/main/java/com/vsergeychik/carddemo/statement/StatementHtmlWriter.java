package com.vsergeychik.carddemo.statement;

import com.vsergeychik.carddemo.common.DatasetRelation;
import com.vsergeychik.carddemo.common.DatasetRelation.BackendDiagnostic;
import com.vsergeychik.carddemo.common.RecordImageForm;
import com.vsergeychik.carddemo.common.FileStatus;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.FixedWidthRecord;
import com.vsergeychik.carddemo.common.FixedWidthRecord.FieldSpan;
import com.vsergeychik.carddemo.common.FixedWidthRecord.RecordLayout;
import com.vsergeychik.carddemo.config.CobolCharsetConfig;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBinding;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBindings;
import java.nio.charset.Charset;
import java.sql.DatabaseMetaData;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementSetter;
import org.springframework.stereotype.Component;

/**
 * The Java owner of the 100-byte {@code HTMLFILE} statement record written by {@code app/cbl/CBSTM03A.CBL}.
 *
 * <p>{@code WRITE FD-HTMLFILE-REC FROM HTML-ADDR-LN} moves the sending group into the record area under the
 * alphanumeric {@code MOVE} rule - right-padded when short, right-truncated when long - and then writes the
 * record area.
 */
@Component
public final class StatementHtmlWriter {
    /**
     * The DD name under which {@code app/jcl/CREASTMT.JCL} and {@code app/cbl/CBSTM03A.CBL} address the
     * HTML statement dataset, and therefore the key this class resolves from the {@code carddemo.datasets}
     * configuration catalogue.
     */
    public static final String HTMLFILE_DD_NAME = "HTMLFILE";

    public static final int RECORD_LENGTH = 100;

    /**
     * The record format the creating JCL step declares: {@code FB}, fixed blocked, from
     * {@code DCB=(LRECL=100,BLKSIZE=800,RECFM=FB)} at {@code app/jcl/CREASTMT.JCL:L94}.
     */
    public static final String RECORD_FORMAT = "FB";

    /**
     * The block size the creating JCL step declares: {@code BLKSIZE=800}
     * ({@code app/jcl/CREASTMT.JCL:L94}).
     */
    public static final int BLOCK_SIZE = 800;

    /**
     * {@code app/cbl/CBSTM03A.CBL:L47} - {@code 01 FD-HTMLFILE-REC PIC X(100)}, the record area.
     */
    public static final String RECORD_AREA_NAME = "FD-HTMLFILE-REC";

    /**
     * {@code app/cbl/CBSTM03A.CBL:L149} - {@code 05 HTML-FIXED-LN PIC X(100)}, the field the
     * {@code 88}-level literals are {@code SET} into before {@code WRITE ... FROM HTML-FIXED-LN}.
     */
    public static final String FIXED_LINE_NAME = "HTML-FIXED-LN";

    /**
     * {@code app/cbl/CBSTM03A.CBL:L221} - {@code 05 HTML-ADDR-LN PIC X(100)}.
     */
    public static final String ADDRESS_LINE_NAME = "HTML-ADDR-LN";

    /**
     * {@code app/cbl/CBSTM03A.CBL:L222} - {@code 05 HTML-BSIC-LN PIC X(100)}.
     */
    public static final String BASIC_LINE_NAME = "HTML-BSIC-LN";

    /**
     * {@code app/cbl/CBSTM03A.CBL:L223} - {@code 05 HTML-TRAN-LN PIC X(100)}.
     */
    public static final String TRANSACTION_LINE_NAME = "HTML-TRAN-LN";

    /**
     * The two-space delimiter of {@code DELIMITED BY ' '} ({@code app/cbl/CBSTM03A.CBL:L563}, {@code L571},
     * {@code L579}, {@code L587}).
     */
    public static final String TWO_SPACE_DELIMITER = "  ";

    public static final String ASTERISK_DELIMITER = "*";

    /**
     * The two literal spaces transferred {@code DELIMITED BY SIZE} immediately after the name and after
     * each address ({@code app/cbl/CBSTM03A.CBL:L564}, {@code L572}, {@code L580}, {@code L588}).
     */
    public static final String TWO_SPACE_SEPARATOR = "  ";

    /**
     * {@code ' '} - the plain paragraph open tag that begins each address line
     * ({@code app/cbl/CBSTM03A.CBL:L570}, {@code L578}, {@code L586}) and each transaction line
     * ({@code L687}, {@code L699}, {@code L711}).
     */
    public static final String PARAGRAPH_OPEN_TAG = "<p>";

    public static final String PARAGRAPH_CLOSE_TAG = "</p>";

    public static final String STYLED_PARAGRAPH_OPEN_TAG = "<p style=\"font-size:16px\">";

    /**
     * The 34-character {@code FILLER VALUE} that opens the {@code HTML-L11} group
     * ({@code app/cbl/CBSTM03A.CBL:L213-L214}).
     */
    public static final String ACCOUNT_HEADING_PREFIX = "<h3>Statement for Account Number: ";

    /**
     * The 5-character {@code FILLER VALUE} that closes {@code HTML-L11}: {@code '</h3>'}.
     */
    public static final String ACCOUNT_HEADING_SUFFIX = "</h3>";

    /**
     * The COBOL name of the account-number field inside the group: {@code L11-ACCT}.
     */
    public static final String L11_ACCT_FIELD_NAME = "L11-ACCT";

    /**
     * Declared width of {@code L11-ACCT PIC X(20)} ({@code app/cbl/CBSTM03A.CBL:L215}).
     */
    public static final int L11_ACCT_LENGTH = 20;

    /**
     * The total declared width of the {@code HTML-L11} group: {@code 34 + 20 + 5 = 59} bytes.
     */
    public static final int HTML_L11_LENGTH = 59;

    /**
     * The {@code HTML-L11} layout, span by span, at absolute offsets: {@code FILLER} 0-33 carrying its
     * declared literal, {@code L11-ACCT} 34-53, {@code FILLER} 54-58 carrying {@code '</h3>'}.
     */
    public static final RecordLayout HTML_L11_LAYOUT = RecordLayout.of(HTML_L11_LENGTH,
            FieldSpan.filler(0, ACCOUNT_HEADING_PREFIX.length(), ACCOUNT_HEADING_PREFIX),
            FieldSpan.alphanumeric(L11_ACCT_FIELD_NAME, ACCOUNT_HEADING_PREFIX.length(),
                    L11_ACCT_LENGTH),
            FieldSpan.filler(ACCOUNT_HEADING_PREFIX.length() + L11_ACCT_LENGTH,
                    ACCOUNT_HEADING_SUFFIX.length(), ACCOUNT_HEADING_SUFFIX));

    // DECLARED BUT NEVER WRITTEN: 5200-WRITE-HTML-NMADBS builds the equivalent line with a STRING into the
    // record area instead (L560-L568).

    /**
     * Declared width of {@code L23-NAME PIC X(50)} ({@code app/cbl/CBSTM03A.CBL:L220}).
     */
    public static final int L23_NAME_LENGTH = 50;

    /**
     * The COBOL name of the name field inside the group: {@code L23-NAME}.
     */
    public static final String L23_NAME_FIELD_NAME = "L23-NAME";

    /**
     * The total declared width of the {@code HTML-L23} group: {@code 26 + 50 = 76} bytes.
     */
    public static final int HTML_L23_LENGTH = 76;

    /**
     * The {@code HTML-L23} layout: {@code FILLER} 0-25 carrying {@link #STYLED_PARAGRAPH_OPEN_TAG},
     * {@code L23-NAME} 26-75.
     */
    public static final RecordLayout HTML_L23_LAYOUT = RecordLayout.of(HTML_L23_LENGTH,
            FieldSpan.filler(0, STYLED_PARAGRAPH_OPEN_TAG.length(), STYLED_PARAGRAPH_OPEN_TAG),
            FieldSpan.alphanumeric(L23_NAME_FIELD_NAME, STYLED_PARAGRAPH_OPEN_TAG.length(),
                    L23_NAME_LENGTH));

    private static final FieldSpan RECORD_AREA_SPAN =
            FieldSpan.alphanumeric(RECORD_AREA_NAME, 0, RECORD_LENGTH);

    private static final FieldSpan ADDRESS_LINE_SPAN =
            FieldSpan.alphanumeric(ADDRESS_LINE_NAME, 0, RECORD_LENGTH);

    private static final FieldSpan BASIC_LINE_SPAN =
            FieldSpan.alphanumeric(BASIC_LINE_NAME, 0, RECORD_LENGTH);

    private static final FieldSpan TRANSACTION_LINE_SPAN =
            FieldSpan.alphanumeric(TRANSACTION_LINE_NAME, 0, RECORD_LENGTH);

    private static final FieldSpan L11_ACCT_SPAN = HTML_L11_LAYOUT.span(L11_ACCT_FIELD_NAME);

    private static final FieldSpan L23_NAME_SPAN = HTML_L23_LAYOUT.span(L23_NAME_FIELD_NAME);

    private static final int DELIMITER_NOT_FOUND = -1;

    /**
     * The COBOL {@code FILE STATUS} for a permanent I/O error: {@code '30'}.
     */
    public static final String PERMANENT_ERROR_STATUS = "30";

    private static final Log LOG = LogFactory.getLog(StatementHtmlWriter.class);

    private static final String DSNAME_ALLOWED_PUNCTUATION = "._$@#-()+";

    private static final String ANSI_IDENTIFIER_QUOTE = "\"";

    private static final String INSERT_STATEMENT_PREFIX = "INSERT INTO ";

    private static final String INSERT_STATEMENT_SUFFIX = " VALUES (?)";

    /**
     * The thirty-four fixed HTML lines of {@code app/cbl/CBSTM03A.CBL:L150-L211}, transcribed byte-exactly
     * and in declaration order.
     */
    public enum HtmlFixedLine {
        /**
         * {@code CBSTM03A.CBL:L150} - the document type declaration.
         */
        HTML_L01("HTML-L01", "<!DOCTYPE html>"),

        /**
         * {@code CBSTM03A.CBL:L151} - the root element.
         */
        HTML_L02("HTML-L02", "<html lang=\"en\">"),

        /**
         * {@code CBSTM03A.CBL:L152} - head open.
         */
        HTML_L03("HTML-L03", "<head>"),

        /**
         * {@code CBSTM03A.CBL:L153} - the declared document charset.
         */
        HTML_L04("HTML-L04", "<meta charset=\"utf-8\">"),

        /**
         * {@code CBSTM03A.CBL:L154} - the document title.
         */
        HTML_L05("HTML-L05", "<title>HTML Table Layout</title>"),

        /**
         * {@code CBSTM03A.CBL:L155} - head close.
         */
        HTML_L06("HTML-L06", "</head>"),

        /**
         * {@code CBSTM03A.CBL:L156} - body open.
         */
        HTML_L07("HTML-L07", "<body style=\"margin:0px;\">"),

        /**
         * {@code CBSTM03A.CBL:L157-L158}, continued.
         */
        HTML_L08("HTML-L08",
                "<table  align=\"center\" frame=\"box\" style=\"width:70%; font:12px Segoe UI,sans-serif;\">"),

        /**
         * {@code CBSTM03A.CBL:L159} - table row open.
         */
        HTML_LTRS("HTML-LTRS", "<tr>"),

        /**
         * {@code CBSTM03A.CBL:L160} - table row close.
         */
        HTML_LTRE("HTML-LTRE", "</tr>"),

        /**
         * {@code CBSTM03A.CBL:L161} - bare table cell open.
         */
        HTML_LTDS("HTML-LTDS", "<td>"),

        /**
         * {@code CBSTM03A.CBL:L162} - table cell close.
         */
        HTML_LTDE("HTML-LTDE", "</td>"),

        /**
         * {@code CBSTM03A.CBL:L163-L164}, continued - the dark banner cell.
         */
        HTML_L10("HTML-L10",
                "<td colspan=\"3\" style=\"padding:0px 5px;background-color:#1d1d96b3;\">"),

        /**
         * {@code CBSTM03A.CBL:L165-L166}, continued - the amber bank-details cell.
         */
        HTML_L15("HTML-L15",
                "<td colspan=\"3\" style=\"padding:0px 5px;background-color:#FFAF33;\">"),

        /**
         * {@code CBSTM03A.CBL:L167-L168} - the bank name.
         */
        HTML_L16("HTML-L16", "<p style=\"font-size:16px\">Bank of XYZ</p>"),

        /**
         * {@code CBSTM03A.CBL:L169-L170} - the bank street address.
         */
        HTML_L17("HTML-L17", "<p>410 Terry Ave N</p>"),

        /**
         * {@code CBSTM03A.CBL:L171-L172} - the bank city, state and postal code.
         */
        HTML_L18("HTML-L18", "<p>Seattle WA 99999</p>"),

        /**
         * {@code CBSTM03A.CBL:L173-L175}, continued - the pale grey cell, used for output line 22 (the
         * customer name and address block) and again for line 35 (the basic details block).
         */
        HTML_L22_35("HTML-L22-35",
                "<td colspan=\"3\" style=\"padding:0px 5px;background-color:#f2f2f2;\">"),

        /**
         * {@code CBSTM03A.CBL:L176-L178}, continued - the centred teal heading cell, used for output line
         * 30 (Basic Details) and again for line 42 (Transaction Summary).
         */
        HTML_L30_42("HTML-L30-42",
                "<td colspan=\"3\" style=\"padding:0px 5px;background-color:#33FFD1; text-align:center;\">"),

        /**
         * {@code CBSTM03A.CBL:L179-L180} - the Basic Details heading.
         */
        HTML_L31("HTML-L31", "<p style=\"font-size:16px\">Basic Details</p>"),

        /**
         * {@code CBSTM03A.CBL:L181-L182} - the Transaction Summary heading.
         */
        HTML_L43("HTML-L43", "<p style=\"font-size:16px\">Transaction Summary</p>"),

        /**
         * {@code CBSTM03A.CBL:L183-L185}, continued - the green 25% column header cell, left aligned.
         */
        HTML_L47("HTML-L47",
                "<td style=\"width:25%; padding:0px 5px; background-color:#33FF5E; text-align:left;\">"),

        /**
         * {@code CBSTM03A.CBL:L186-L187} - the Tran ID column heading.
         */
        HTML_L48("HTML-L48", "<p style=\"font-size:16px\">Tran ID</p>"),

        /**
         * {@code CBSTM03A.CBL:L188-L190}, continued - the green 55% column header cell, left aligned.
         */
        HTML_L50("HTML-L50",
                "<td style=\"width:55%; padding:0px 5px; background-color:#33FF5E; text-align:left;\">"),

        /**
         * {@code CBSTM03A.CBL:L191-L192} - the Tran Details column heading.
         */
        HTML_L51("HTML-L51", "<p style=\"font-size:16px\">Tran Details</p>"),

        /**
         * {@code CBSTM03A.CBL:L193-L195}, continued - the green 20% column header cell, right aligned.
         */
        HTML_L53("HTML-L53",
                "<td style=\"width:20%; padding:0px 5px; background-color:#33FF5E; text-align:right;\">"),

        /**
         * {@code CBSTM03A.CBL:L196-L197} - the Amount column heading.
         */
        HTML_L54("HTML-L54", "<p style=\"font-size:16px\">Amount</p>"),

        /**
         * {@code CBSTM03A.CBL:L198-L200}, continued - the grey 25% transaction-identifier cell.
         */
        HTML_L58("HTML-L58",
                "<td style=\"width:25%; padding:0px 5px; background-color:#f2f2f2; text-align:left;\">"),

        /**
         * {@code CBSTM03A.CBL:L201-L203}, continued - the grey 55% transaction-description cell.
         */
        HTML_L61("HTML-L61",
                "<td style=\"width:55%; padding:0px 5px; background-color:#f2f2f2; text-align:left;\">"),

        /**
         * {@code CBSTM03A.CBL:L204-L206}, continued - the grey 20% transaction-amount cell.
         */
        HTML_L64("HTML-L64",
                "<td style=\"width:20%; padding:0px 5px; background-color:#f2f2f2; text-align:right;\">"),

        /**
         * {@code CBSTM03A.CBL:L207-L208} - the end-of-statement heading, written at {@code L443-L444}.
         */
        HTML_L75("HTML-L75", "<h3>End of Statement</h3>"),

        /**
         * {@code CBSTM03A.CBL:L209} - table close.
         */
        HTML_L78("HTML-L78", "</table>"),

        /**
         * {@code CBSTM03A.CBL:L210} - body close.
         */
        HTML_L79("HTML-L79", "</body>"),

        /**
         * {@code CBSTM03A.CBL:L211} - document close.
         */
        HTML_L80("HTML-L80", "</html>");

        private static final Map<String, HtmlFixedLine> BY_COBOL_NAME = buildIndex();

        private final String cobolName;

        private final String literal;

        HtmlFixedLine(final String cobolName, final String literal) {
            this.cobolName = cobolName;
            this.literal = literal;
        }

        private static Map<String, HtmlFixedLine> buildIndex() {
            Map<String, HtmlFixedLine> index = new LinkedHashMap<>();
            for (HtmlFixedLine line : values()) {
                index.put(line.cobolName, line);
            }
            return Map.copyOf(index);
        }

        /**
         * The COBOL {@code 88}-level condition name, for example {@code HTML-L22-35}.
         *
         * @return the condition name exactly as {@code app/cbl/CBSTM03A.CBL} declares it
         */
        public String cobolName() {
            return this.cobolName;
        }

        /**
         * The declared literal, unpadded and unescaped.
         *
         * @return the literal, between 4 and 85 characters and never more than 100
         */
        public String literal() {
            return this.literal;
        }

        /**
         * The literal's declared length in characters, which is what the COBOL {@code VALUE} occupies
         * before the {@code PIC X(100)} field pads it.
         *
         * @return the unpadded length, always at least 1 and never more than
         *     {@link StatementHtmlWriter#RECORD_LENGTH}
         */
        public int literalLength() {
            return this.literal.length();
        }

        /**
         * Resolves a constant by its COBOL condition name.
         *
         * @param cobolName the condition name to resolve, for example {@code HTML-LTRS}
         * @return the matching constant; never {@code null}
         * @throws NullPointerException if {@code cobolName} is {@code null}
         * @throws IllegalArgumentException if no constant carries that condition name
         */
        public static HtmlFixedLine ofCobolName(final String cobolName) {
            Objects.requireNonNull(cobolName, "A COBOL condition name is required to resolve a "
                    + "fixed HTML line");
            HtmlFixedLine line = BY_COBOL_NAME.get(cobolName);
            if (line == null) {
                throw new IllegalArgumentException("'" + cobolName + "' is not one of the "
                        + BY_COBOL_NAME.size() + " 88-level condition names declared on "
                        + "HTML-FIXED-LN at app/cbl/CBSTM03A.CBL:L150-L211. Names are matched "
                        + "exactly and case-sensitively, hyphens included. Declared names: "
                        + BY_COBOL_NAME.keySet() + ".");
            }
            return line;
        }

        /**
         * Whether a constant is declared under the given COBOL condition name.
         *
         * @param cobolName the condition name to test; may be {@code null}, which is never declared
         * @return {@code true} when {@link #ofCobolName(String)} would resolve it
         */
        public static boolean isDeclared(final String cobolName) {
            return cobolName != null && BY_COBOL_NAME.containsKey(cobolName);
        }
    }

    /**
     * The three customer address lines of {@code 5200-WRITE-HTML-NMADBS}
     * ({@code app/cbl/CBSTM03A.CBL:L569-L592}).
     */
    public enum AddressField {
        /**
         * {@code ST-ADD1 PIC X(50)} ({@code app/cbl/CBSTM03A.CBL:L94}), written at {@code L576}.
         */
        ADDRESS_LINE_1("ST-ADD1", 50),

        /**
         * {@code ST-ADD2 PIC X(50)} ({@code app/cbl/CBSTM03A.CBL:L97}), written at {@code L584}.
         */
        ADDRESS_LINE_2("ST-ADD2", 50),

        /**
         * {@code ST-ADD3 PIC X(80)} ({@code app/cbl/CBSTM03A.CBL:L100}), written at {@code L592}.
         */
        ADDRESS_LINE_3("ST-ADD3", 80);

        private final String cobolName;

        private final int declaredLength;

        AddressField(final String cobolName, final int declaredLength) {
            this.cobolName = cobolName;
            this.declaredLength = declaredLength;
        }

        /**
         * The COBOL name of the sending field.
         *
         * @return {@code ST-ADD1}, {@code ST-ADD2} or {@code ST-ADD3}
         */
        public String cobolName() {
            return this.cobolName;
        }

        /**
         * The declared width the value is normalised to before the {@code STRING} runs.
         *
         * @return 50 for the first two lines, 80 for the third
         */
        public int declaredLength() {
            return this.declaredLength;
        }
    }

    /**
     * The three basic-detail lines of {@code 5200-WRITE-HTML-NMADBS}
     * ({@code app/cbl/CBSTM03A.CBL:L613-L633}).
     */
    public enum BasicDetail {
        /**
         * {@code app/cbl/CBSTM03A.CBL:L614-L619}.
         */
        ACCOUNT_ID("<p>Account ID         : ", "ST-ACCT-ID", 20),

        /**
         * {@code app/cbl/CBSTM03A.CBL:L621-L626}.
         */
        CURRENT_BALANCE("<p>Current Balance    : ", "ST-CURR-BAL", 13),

        /**
         * {@code app/cbl/CBSTM03A.CBL:L628-L633}.
         */
        FICO_SCORE("<p>FICO Score         : ", "ST-FICO-SCORE", 20);

        private final String label;

        private final String cobolName;

        private final int declaredLength;

        BasicDetail(final String label, final String cobolName, final int declaredLength) {
            this.label = label;
            this.cobolName = cobolName;
            this.declaredLength = declaredLength;
        }

        public String label() {
            return this.label;
        }

        /**
         * The COBOL name of the value field.
         *
         * @return {@code ST-ACCT-ID}, {@code ST-CURR-BAL} or {@code ST-FICO-SCORE}
         */
        public String cobolName() {
            return this.cobolName;
        }

        /**
         * The declared width the value is normalised to before the {@code STRING} runs.
         *
         * @return 20 for the identifier and the score, 13 for the edited balance
         */
        public int declaredLength() {
            return this.declaredLength;
        }
    }

    /**
     * The three per-transaction cells of {@code 6000-WRITE-TRANS} ({@code app/cbl/CBSTM03A.CBL:L686-L716}).
     */
    public enum TransactionField {
        /**
         * {@code app/cbl/CBSTM03A.CBL:L687-L692}.
         */
        TRAN_ID("ST-TRANID", 16),

        /**
         * {@code app/cbl/CBSTM03A.CBL:L699-L704}.
         */
        TRAN_DETAILS("ST-TRANDT", 49),

        /**
         * {@code app/cbl/CBSTM03A.CBL:L711-L716}.
         */
        TRAN_AMOUNT("ST-TRANAMT", 13);

        private final String cobolName;

        private final int declaredLength;

        TransactionField(final String cobolName, final int declaredLength) {
            this.cobolName = cobolName;
            this.declaredLength = declaredLength;
        }

        /**
         * The COBOL name of the value field.
         *
         * @return {@code ST-TRANID}, {@code ST-TRANDT} or {@code ST-TRANAMT}
         */
        public String cobolName() {
            return this.cobolName;
        }

        /**
         * The declared width the value is normalised to before the {@code STRING} runs.
         *
         * @return 16, 49 or 13
         */
        public int declaredLength() {
            return this.declaredLength;
        }
    }

    /**
     * The destination of the 100-byte {@code HTMLFILE} records - the injectable seam that replaces the
     * COBOL {@code OPEN OUTPUT} / {@code WRITE} / {@code CLOSE} triple.
     */
    @FunctionalInterface
    public interface HtmlRecordSink {
        String write(byte[] record);

        default String open() {
            return FileStatus.OK;
        }

        default String close() {
            return FileStatus.OK;
        }

        default String discard(long recordsWritten) {
            return FileStatus.OK;
        }
    }

    /**
     * The per-execution handle for one opened {@code HTMLFILE} - the COBOL {@code FD} plus the
     * {@code WORKING-STORAGE} lines that feed it, scoped to a single statement run.
     */
    public static final class HtmlStatementFile {
        private final HtmlRecordSink sink;

        private final FixedWidthRecord recordArea;

        private final FixedWidthRecord fixedLine;

        private final FixedWidthRecord accountHeadingLine;

        private final FixedWidthRecord addressLine;

        private final FixedWidthRecord basicLine;

        private final FixedWidthRecord transactionLine;

        private final String openStatus;

        private boolean open;

        private long recordsWritten;

        private boolean discarded;

        private HtmlStatementFile(final HtmlRecordSink sink, final Charset charset,
                                  final String openStatus) {
            this.sink = sink;
            this.recordArea = new FixedWidthRecord(RECORD_LENGTH, charset);
            this.fixedLine = new FixedWidthRecord(RECORD_LENGTH, charset);
            this.accountHeadingLine = FixedWidthRecord.forLayout(HTML_L11_LAYOUT, charset);
            this.addressLine = new FixedWidthRecord(RECORD_LENGTH, charset);
            this.basicLine = new FixedWidthRecord(RECORD_LENGTH, charset);
            this.transactionLine = new FixedWidthRecord(RECORD_LENGTH, charset);
            this.openStatus = openStatus;
            this.open = true;
            this.recordsWritten = 0L;
        }

        public HtmlRecordSink sink() {
            return this.sink;
        }

        /**
         * The {@code FD-HTMLFILE-REC} record area.
         *
         * @return the live 100-byte record area
         */
        public FixedWidthRecord recordArea() {
            return this.recordArea;
        }

        /**
         * The {@code HTML-FIXED-LN} staging line.
         *
         * @return the live 100-byte staging line
         */
        public FixedWidthRecord fixedLine() {
            return this.fixedLine;
        }

        /**
         * The {@code HTML-L11} account-heading group.
         *
         * @return the live 59-byte group
         */
        public FixedWidthRecord accountHeadingLine() {
            return this.accountHeadingLine;
        }

        /**
         * The {@code HTML-ADDR-LN} scratch line.
         *
         * @return the live 100-byte scratch line
         */
        public FixedWidthRecord addressLine() {
            return this.addressLine;
        }

        /**
         * The {@code HTML-BSIC-LN} scratch line.
         *
         * @return the live 100-byte scratch line
         */
        public FixedWidthRecord basicLine() {
            return this.basicLine;
        }

        /**
         * The {@code HTML-TRAN-LN} scratch line.
         *
         * @return the live 100-byte scratch line
         */
        public FixedWidthRecord transactionLine() {
            return this.transactionLine;
        }

        public boolean isOpen() {
            return this.open;
        }

        /**
         * How many records have been emitted through this handle.
         *
         * @return the count, starting at zero
         */
        public long recordsWritten() {
            return this.recordsWritten;
        }

        /**
         * The status the sink reported when this handle was opened.
         *
         * @return a two-character COBOL {@code FILE STATUS}; never {@code null}
         */
        public String openStatus() {
            return this.openStatus;
        }
    }

    /**
     * The production sink: one 100-byte record per {@link JdbcTemplate} statement, against the dataset the
     * {@code carddemo.datasets.HTMLFILE} binding names.
     */
    public static final class JdbcHtmlRecordSink implements HtmlRecordSink {
        private final JdbcTemplate jdbcTemplate;

        private final String insertStatement;

        private final String dsname;

        private final String identifierQuote;

        private BackendDiagnostic lastFailure;

        private final RecordImageForm recordImageForm;

        private final Charset charset;

        private final String describeStatement;

        private final String clearStatement;

        private final String countStatement;

        private JdbcHtmlRecordSink(final JdbcTemplate jdbcTemplate, final DatasetRelation relation,
                                   final RecordImageForm recordImageForm, final Charset charset) {
            this.jdbcTemplate = jdbcTemplate;
            this.recordImageForm = recordImageForm;
            this.charset = charset;
            this.dsname = relation.dsname();
            this.identifierQuote = resolveIdentifierQuote(jdbcTemplate);
            this.insertStatement = ANSI_IDENTIFIER_QUOTE.equals(this.identifierQuote)
                    ? relation.insertRecordImage()
                    : StatementHtmlWriter.insertStatement(this.dsname, this.identifierQuote);
            this.describeStatement = relation.describeStatement();
            this.clearStatement = relation.deleteAll();
            this.countStatement = relation.countAllStatement();
        }

        /**
         * Establishes the generation this run writes into: {@code OPEN OUTPUT HTML-FILE} at
         * {@code app/cbl/CBSTM03A.CBL:L293}, over a dataset {@code app/jcl/CREASTMT.JCL:L92-L96} declares
         * {@code DISP=(NEW,CATLG,DELETE)} with {@code LRECL=100 BLKSIZE=800}.
         *
         * <p>The describe resolves the DD name to a real destination and fails if it cannot - read-only,
         * its predicate false on every row, so nothing is transferred - which is how an absent, unreachable
         * or refused destination is reported once rather than record by record.
         *
         * @return {@link FileStatus#OK} when the destination is established, or
         *     {@link #PERMANENT_ERROR_STATUS} when it could not be
         */
        @Override
        public String open() {
            try {
                this.jdbcTemplate.execute(this.describeStatement);
                this.jdbcTemplate.update(this.clearStatement);
                this.lastFailure = null;
                return FileStatus.OK;
            } catch (DataAccessException failure) {
                this.lastFailure = BackendDiagnostic.of(failure);
                return PERMANENT_ERROR_STATUS;
            }
        }

        /**
         * Confirms the destination survived the run: {@code CLOSE HTML-FILE} at
         * {@code app/cbl/CBSTM03A.CBL:L339}.
         *
         * @return {@link FileStatus#OK} when the destination is still addressable, or
         *     {@link #PERMANENT_ERROR_STATUS} when it is not
         */
        @Override
        public String close() {
            try {
                this.jdbcTemplate.execute(this.describeStatement);
                this.lastFailure = null;
                return FileStatus.OK;
            } catch (DataAccessException failure) {
                this.lastFailure = BackendDiagnostic.of(failure);
                return PERMANENT_ERROR_STATUS;
            }
        }

        /**
         * Deletes the generation this run wrote: the {@code DELETE} positional of
         * {@code app/jcl/CREASTMT.JCL:L92-L96}.
         *
         * @param recordsWritten how many records this run emitted
         * @return {@link FileStatus#OK} when the generation was discarded, otherwise
         *     {@value StatementHtmlWriter#PERMANENT_ERROR_STATUS}
         */
        @Override
        public String discard(final long recordsWritten) {
            try {
                final Long held = this.jdbcTemplate.queryForObject(this.countStatement, Long.class);
                if (held == null || held != recordsWritten) {
                    LOG.error("Refusing to apply the " + HTMLFILE_DD_NAME + " abnormal disposition of "
                            + "app/jcl/CREASTMT.JCL:L92: this run wrote " + recordsWritten
                            + " record(s) but the destination holds " + held
                            + ". DISP=(NEW,CATLG,DELETE) deletes the generation this step allocated, so "
                            + "a destination holding records this step did not write is not that "
                            + "generation. Leaving it untouched and reporting FILE STATUS "
                            + PERMANENT_ERROR_STATUS + "; bind " + HTMLFILE_DD_NAME
                            + " to a relation of its own so each run allocates its own generation");
                    this.lastFailure = null;
                    return PERMANENT_ERROR_STATUS;
                }
                final int removed = this.jdbcTemplate.update(this.clearStatement);
                this.lastFailure = null;
                if (removed == recordsWritten) {
                    return FileStatus.OK;
                }
                LOG.error("The " + HTMLFILE_DD_NAME + " abnormal disposition removed " + removed
                        + " record(s) where this run wrote " + recordsWritten
                        + "; reporting FILE STATUS " + PERMANENT_ERROR_STATUS
                        + " rather than reporting the generation as discarded");
                return PERMANENT_ERROR_STATUS;
            } catch (DataAccessException failure) {
                this.lastFailure = BackendDiagnostic.of(failure);
                LOG.error("Could not apply the " + HTMLFILE_DD_NAME + " abnormal disposition after "
                        + recordsWritten + " record(s) - " + this.lastFailure.describe()
                        + "; reporting FILE STATUS " + PERMANENT_ERROR_STATUS
                        + ". Unterminated HTML for the accounts this run got through may remain "
                        + "catalogued, which DISP=(NEW,CATLG,DELETE) says it should not; it must be "
                        + "deleted by hand before anything is issued from it");
                return PERMANENT_ERROR_STATUS;
            }
        }

        @Override
        public String write(final byte[] record) {
            final PreparedStatementSetter binder = parameters -> this.recordImageForm.bindImage(
                    parameters, DatasetRelation.RECORD_IMAGE_COLUMN_INDEX, record, this.charset);
            try {
                this.jdbcTemplate.update(this.insertStatement, binder);
                this.lastFailure = null;
                return FileStatus.OK;
            } catch (DataAccessException failure) {
                this.lastFailure = BackendDiagnostic.of(failure);
                return PERMANENT_ERROR_STATUS;
            }
        }

        public String dsname() {
            return this.dsname;
        }

        /**
         * The statement this sink issues, exposed so a deployment can confirm what its driver will receive
         * without having to run the job.
         *
         * @return the single parameterised statement, whose only identifier is the delimited dataset name
         *     and whose only value is a positional parameter; never {@code null}
         */
        public String insertStatement() {
            return this.insertStatement;
        }

        /**
         * The quote character the dataset name is delimited with in {@link #insertStatement()}.
         *
         * @return the driver's own {@code getIdentifierQuoteString()} when it reported a usable one,
         *     otherwise {@value StatementHtmlWriter#ANSI_IDENTIFIER_QUOTE}; never {@code null} and never blank
         */
        public String identifierQuote() {
            return this.identifierQuote;
        }

        public Optional<BackendDiagnostic> lastFailure() {
            return Optional.ofNullable(this.lastFailure);
        }
    }

    private final JdbcTemplate jdbcTemplate;

    private final Charset datasetCharset;

    private final FixedWidthCodec codec;

    private final RecordImageForm recordImageForm;

    private final DatasetBinding binding;

    /**
     * Constructs the writer and verifies the 100-byte record width.
     *
     * @param jdbcTemplate the module-wide {@link JdbcTemplate}
     * @param datasetCharset the active dataset code page, selected by qualifier because
     *     {@code CobolCharsetConfig} publishes three {@link Charset} beans and declares no primary
     * @param datasetBindings the {@code carddemo.datasets} catalogue
     * @param recordImageForm how the deployment's driver presents a record image over JDBC, from
     *     {@value RecordImageForm#FORM_PROPERTY}
     * @throws NullPointerException if any argument is {@code null}
     * @throws IllegalStateException if no {@code HTMLFILE} binding is configured, or if it declares a
     *     record length other than {@link #RECORD_LENGTH}, or a record format other than {@link #RECORD_FORMAT}
     */
    public StatementHtmlWriter(
            final JdbcTemplate jdbcTemplate,
            @Qualifier(CobolCharsetConfig.DATASET_CHARSET_BEAN_NAME) final Charset datasetCharset,
            final DatasetBindings datasetBindings,
            final RecordImageForm recordImageForm) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate,
                "A JdbcTemplate is required; config/DataSourceConfig publishes exactly one");
        this.datasetCharset = Objects.requireNonNull(datasetCharset,
                "A dataset Charset is required and must be selected by qualifier: "
                        + "config/CobolCharsetConfig publishes three Charset beans and declares no "
                        + "primary, so an unqualified injection point is ambiguous by design");
        Objects.requireNonNull(datasetBindings,
                "The carddemo.datasets binding catalogue is required; dataset names are never "
                        + "hard-coded in Java");
        this.recordImageForm = Objects.requireNonNull(recordImageForm,
                "A record-image representation is required: whether this deployment's driver takes a "
                        + "record image as characters or as bytes is stated once, by "
                        + RecordImageForm.FORM_PROPERTY + ", and never decided per writer");
        RecordImageForm.requireSingleByteCodePage(datasetCharset);
        this.codec = new FixedWidthCodec(datasetCharset);
        this.binding = datasetBindings.binding(HTMLFILE_DD_NAME);
        if (this.binding.recordLength() != RECORD_LENGTH) {
            throw new IllegalStateException("carddemo.datasets." + HTMLFILE_DD_NAME
                    + ".record-length is " + this.binding.recordLength() + " but the HTML statement "
                    + "record is " + RECORD_LENGTH + " bytes. app/jcl/CREASTMT.JCL declares this DD "
                    + "TWICE with different widths: the STEP030 IEFBR14 pre-delete at L69 says "
                    + "LRECL=80,BLKSIZE=3200 and the STEP040 step that actually CREATES the file at "
                    + "L94 says LRECL=100,BLKSIZE=800. The creating step is authoritative, and "
                    + "app/cbl/CBSTM03A.CBL:L47 confirms it with '01 FD-HTMLFILE-REC PIC X(100)'. "
                    + "Restore record-length: " + RECORD_LENGTH + "; do not use 80 and do not "
                    + "average the two (gate G20, risk R-G).");
        }
        if (!RECORD_FORMAT.equalsIgnoreCase(this.binding.recordFormat())) {
            throw new IllegalStateException("carddemo.datasets." + HTMLFILE_DD_NAME
                    + ".record-format is " + describeConfiguredRecordFormat() + " but "
                    + "app/jcl/CREASTMT.JCL declares RECFM=" + RECORD_FORMAT + " at L94, the STEP040 "
                    + "step that creates the dataset - and at L69, its pre-delete, so the two "
                    + "declarations agree on the format even though they disagree on the width. Fixed "
                    + "blocked is what makes every HTML record exactly " + RECORD_LENGTH + " bytes, so "
                    + "it is required rather than assumed. Set record-format: " + RECORD_FORMAT
                    + " in application.yml (gate G20).");
        }
    }

    private String describeConfiguredRecordFormat() {
        return this.binding.recordFormat() == null ? "absent" : "'" + this.binding.recordFormat() + "'";
    }

    public int recordLength() {
        return RECORD_LENGTH;
    }

    /**
     * The block size the creating JCL step declares.
     *
     * @return always {@link #BLOCK_SIZE}, that is 800
     */
    public int blockSize() {
        return BLOCK_SIZE;
    }

    public Charset datasetCharset() {
        return this.datasetCharset;
    }

    /**
     * The resolved {@code carddemo.datasets.HTMLFILE} binding.
     *
     * @return the binding, whose {@code recordLength()} the constructor has already verified to be
     *     {@link #RECORD_LENGTH}
     */
    public DatasetBinding datasetBinding() {
        return this.binding;
    }

    /**
     * Builds the production sink over the configured {@code HTMLFILE} dataset.
     *
     * @return a fresh per-execution {@link JdbcHtmlRecordSink}
     * @throws IllegalStateException if the configured dataset name is empty, or is not a well-formed z/OS
     *     dataset name - which includes the filesystem locations the {@code test} profile binds
     */
    public JdbcHtmlRecordSink defaultSink() {
        return new JdbcHtmlRecordSink(this.jdbcTemplate,
                requireAddressableDataset(this.binding.dsname()),
                this.recordImageForm,
                this.datasetCharset);
    }

    /**
     * {@code OPEN OUTPUT HTML-FILE} against the production sink ({@code app/cbl/CBSTM03A.CBL:L293}).
     *
     * @return a fresh per-execution handle
     * @throws IllegalStateException if {@link #defaultSink()} cannot be built
     */
    public HtmlStatementFile open() {
        return open(defaultSink());
    }

    /**
     * {@code OPEN OUTPUT HTML-FILE} against a supplied sink ({@code app/cbl/CBSTM03A.CBL:L293}).
     *
     * @param sink where the records go
     * @return a fresh per-execution handle, already open
     * @throws NullPointerException if {@code sink} is {@code null}, or if it returns a {@code null} status
     *     from {@link HtmlRecordSink#open()}
     */
    public HtmlStatementFile open(final HtmlRecordSink sink) {
        Objects.requireNonNull(sink, "A record sink is required to open " + HTMLFILE_DD_NAME
                + "; call open() for the configured default sink");
        final String status = Objects.requireNonNull(sink.open(),
                "A record sink must report a two-character FILE STATUS from open(), never null");
        return new HtmlStatementFile(sink, this.datasetCharset, status);
    }

    /**
     * {@code CLOSE HTML-FILE} ({@code app/cbl/CBSTM03A.CBL:L339}).
     *
     * @param file the handle returned by {@link #open}
     * @return the outcome of the sink's own close
     * @throws NullPointerException if {@code file} is {@code null}, or if the sink returns a {@code null}
     *     status
     * @throws IllegalStateException if {@code file} is already closed
     */
    public FileStatus.Outcome close(final HtmlStatementFile file) {
        requireOpen(file, "close");
        file.open = false;
        final String status = Objects.requireNonNull(file.sink.close(),
                "A record sink must report a two-character FILE STATUS from close(), never null");
        return FileStatus.outcomeOfStatus(status);
    }

    /**
     * Applies the abnormal disposition of {@code app/jcl/CREASTMT.JCL:L92-L96} -
     * {@code DISP=(NEW,CATLG,DELETE)} - by discarding the HTML statements this run wrote.
     *
     * @param file the handle whose generation is to be discarded; a {@code null} is reported rather than
     *     raised
     * @return {@link FileStatus.Outcome#OK} when the generation was discarded, when this run wrote nothing,
     *     or when the disposition had already been applied; otherwise {@link FileStatus.Outcome#OTHER}
     */
    public FileStatus.Outcome discardGeneration(final HtmlStatementFile file) {
        if (file == null) {
            LOG.error("No " + HTMLFILE_DD_NAME + " handle was supplied for its abnormal disposition, so "
                    + "app/jcl/CREASTMT.JCL:L92's DELETE cannot be applied; reporting FILE STATUS outcome "
                    + FileStatus.Outcome.OTHER.name()
                    + " rather than raising, because this path is already abending");
            return FileStatus.Outcome.OTHER;
        }
        if (file.discarded || file.recordsWritten == 0L) {
            file.discarded = true;
            return FileStatus.Outcome.OK;
        }
        file.discarded = true;
        final String status = file.sink.discard(file.recordsWritten);
        if (status == null) {
            LOG.error("The record sink supplied for " + HTMLFILE_DD_NAME + " returned a null status from "
                    + "discard(long) after " + file.recordsWritten + " record(s); reading it as FILE "
                    + "STATUS outcome " + FileStatus.Outcome.OTHER.name()
                    + " rather than raising, because this path is already abending");
            return FileStatus.Outcome.OTHER;
        }
        return FileStatus.outcomeOfStatus(status);
    }

    /**
     * {@code SET HTML-Lxx TO TRUE} followed by {@code WRITE FD-HTMLFILE-REC FROM HTML-FIXED-LN} - one of
     * the thirty-four fixed HTML lines.
     *
     * @param file the open handle
     * @param line which fixed line to emit
     * @return the outcome the sink reported
     * @throws NullPointerException if {@code file} or {@code line} is {@code null}
     * @throws IllegalStateException if {@code file} is closed
     */
    public FileStatus.Outcome writeFixedLine(final HtmlStatementFile file, final HtmlFixedLine line) {
        requireOpen(file, "write a fixed HTML line");
        Objects.requireNonNull(line, "A fixed HTML line is required; resolve one from the "
                + "HtmlFixedLine catalogue or by its COBOL condition name");
        file.fixedLine.writeString(0, RECORD_LENGTH,
                this.codec.movePicX(line.literal(), RECORD_LENGTH));
        return writeFrom(file, file.fixedLine);
    }

    /**
     * {@code MOVE ACCT-ID TO L11-ACCT} followed by {@code WRITE FD-HTMLFILE-REC FROM HTML-L11}
     * ({@code app/cbl/CBSTM03A.CBL:L529-L530}).
     *
     * @param file the open handle
     * @param accountId the {@code ACCT-ID} image; right-truncated to {@link #L11_ACCT_LENGTH} characters if
     *     longer, right-space padded if shorter, exactly as the alphanumeric {@code MOVE} rule requires
     * @return the outcome the sink reported
     * @throws NullPointerException if {@code file} or {@code accountId} is {@code null}
     * @throws IllegalStateException if {@code file} is closed
     */
    public FileStatus.Outcome writeAccountHeading(final HtmlStatementFile file,
                                                  final String accountId) {
        requireOpen(file, "write the HTML-L11 account heading");
        Objects.requireNonNull(accountId, "An ACCT-ID image is required for L11-ACCT; move an empty "
                + "string to blank it explicitly");
        file.accountHeadingLine.initialize(HTML_L11_LAYOUT,
                FixedWidthRecord.FillerHandling.WITH_FILLER,
                FixedWidthRecord.ValueHandling.CATEGORY_DEFAULTS);
        file.accountHeadingLine.loadDeclaredValues(HTML_L11_LAYOUT);
        this.codec.writePicX(file.accountHeadingLine, L11_ACCT_SPAN, accountId);
        return writeFrom(file, file.accountHeadingLine);
    }

    /**
     * The customer name line of {@code 5200-WRITE-HTML-NMADBS} ({@code app/cbl/CBSTM03A.CBL:L560-L568}),
     * verbatim: Three details make this the subtlest record in the file: The {@code WRITE} has no
     * {@code FROM} clause.
     *
     * <p>{@code MOVE ST-NAME TO L23-NAME} truncates {@code ST-NAME PIC X(75)} on the right to
     * {@link #L23_NAME_LENGTH} characters before the {@code STRING} runs, so a long name loses its tail
     * here while keeping it in the plain-text statement.
     *
     * @param file the open handle
     * @param stName the {@code ST-NAME} value; normalised to {@link #L23_NAME_LENGTH} characters by the
     *     alphanumeric {@code MOVE} rule before the {@code STRING} sees it
     * @return the outcome the sink reported
     * @throws NullPointerException if {@code file} or {@code stName} is {@code null}
     * @throws IllegalStateException if {@code file} is closed
     */
    public FileStatus.Outcome writeNameLine(final HtmlStatementFile file, final String stName) {
        requireOpen(file, "write the customer name line");
        Objects.requireNonNull(stName, "An ST-NAME value is required; move an empty string to blank "
                + "the line explicitly");
        // MOVE ST-NAME TO L23-NAME: PIC X(75) into PIC X(50), truncated on the right.
        final String l23Name = this.codec.movePicX(stName, L23_NAME_LENGTH);
        moveSpaces(file.recordArea);
        this.codec.stringIntoDelimitedBySize(file.recordArea, RECORD_AREA_SPAN,
                delimitedBy(STYLED_PARAGRAPH_OPEN_TAG, ASTERISK_DELIMITER),
                delimitedBy(l23Name, TWO_SPACE_DELIMITER),
                delimitedBySize(TWO_SPACE_SEPARATOR),
                delimitedBy(PARAGRAPH_CLOSE_TAG, ASTERISK_DELIMITER));
        return emitRecordArea(file);
    }

    /**
     * One customer address line of {@code 5200-WRITE-HTML-NMADBS} ({@code app/cbl/CBSTM03A.CBL:L569-L592}),
     * verbatim: The COBOL performs this three times, for {@code ST-ADD1}, {@code ST-ADD2} and
     * {@code ST-ADD3} in that order.
     *
     * @param file the open handle
     * @param field which address line, carrying the declared width the {@code STRING} sends from
     * @param value the address value; normalised to {@link AddressField#declaredLength()} by the
     *     alphanumeric {@code MOVE} rule first, because the COBOL sends from the field at its full declared
     *     width
     * @return the outcome the sink reported
     * @throws NullPointerException if any argument is {@code null}
     * @throws IllegalStateException if {@code file} is closed
     */
    public FileStatus.Outcome writeAddressLine(final HtmlStatementFile file,
                                               final AddressField field,
                                               final String value) {
        requireOpen(file, "write a customer address line");
        Objects.requireNonNull(field, "An address line identity is required; the three lines have "
                + "different declared widths, so which one is being written is not optional");
        Objects.requireNonNull(value, "An address value is required; move an empty string to blank "
                + "the line explicitly");
        final String sending = this.codec.movePicX(value, field.declaredLength());
        moveSpaces(file.addressLine);
        this.codec.stringIntoDelimitedBySize(file.addressLine, ADDRESS_LINE_SPAN,
                delimitedBy(PARAGRAPH_OPEN_TAG, ASTERISK_DELIMITER),
                delimitedBy(sending, TWO_SPACE_DELIMITER),
                delimitedBySize(TWO_SPACE_SEPARATOR),
                delimitedBy(PARAGRAPH_CLOSE_TAG, ASTERISK_DELIMITER));
        return writeFrom(file, file.addressLine);
    }

    /**
     * One basic-detail line of {@code 5200-WRITE-HTML-NMADBS} ({@code app/cbl/CBSTM03A.CBL:L613-L633}),
     * verbatim: Every operand here is {@code DELIMITED BY '*'} and none contains an asterisk, so each is
     * transferred in full - trailing spaces included.
     *
     * <p>The value is deliberately not trimmed: {@code ST-ACCT-ID PIC X(20)} contributes twenty characters,
     * of which the padding is as much part of the record as the digits.
     *
     * @param file the open handle
     * @param detail which detail line, carrying its 24-character label and its value's declared width
     * @param value the value; normalised to {@link BasicDetail#declaredLength()} by the alphanumeric
     *     {@code MOVE} rule and then transferred whole
     * @return the outcome the sink reported
     * @throws NullPointerException if any argument is {@code null}
     * @throws IllegalStateException if {@code file} is closed
     */
    public FileStatus.Outcome writeBasicDetail(final HtmlStatementFile file,
                                               final BasicDetail detail,
                                               final String value) {
        requireOpen(file, "write a basic-detail line");
        Objects.requireNonNull(detail, "A basic-detail identity is required; it selects both the "
                + "24-character label and the value's declared width");
        Objects.requireNonNull(value, "A basic-detail value is required; move an empty string to "
                + "blank it explicitly");
        final String sending = this.codec.movePicX(value, detail.declaredLength());
        moveSpaces(file.basicLine);
        this.codec.stringIntoDelimitedBySize(file.basicLine, BASIC_LINE_SPAN,
                delimitedBy(detail.label(), ASTERISK_DELIMITER),
                delimitedBy(sending, ASTERISK_DELIMITER),
                delimitedBy(PARAGRAPH_CLOSE_TAG, ASTERISK_DELIMITER));
        return writeFrom(file, file.basicLine);
    }

    /**
     * One per-transaction line of {@code 6000-WRITE-TRANS} ({@code app/cbl/CBSTM03A.CBL:L686-L716}),
     * verbatim: As with the basic details, every operand is {@code DELIMITED BY '*'}, so the value is
     * transferred in full with its trailing spaces intact and is never trimmed.
     *
     * @param file the open handle
     * @param field which transaction cell, carrying its value's declared width
     * @param value the value; normalised to {@link TransactionField#declaredLength()} by the alphanumeric
     *     {@code MOVE} rule and then transferred whole
     * @return the outcome the sink reported
     * @throws NullPointerException if any argument is {@code null}
     * @throws IllegalStateException if {@code file} is closed
     */
    public FileStatus.Outcome writeTransactionField(final HtmlStatementFile file,
                                                    final TransactionField field,
                                                    final String value) {
        requireOpen(file, "write a transaction line");
        Objects.requireNonNull(field, "A transaction field identity is required; the three cells "
                + "have different declared widths");
        Objects.requireNonNull(value, "A transaction value is required; move an empty string to "
                + "blank it explicitly");
        final String sending = this.codec.movePicX(value, field.declaredLength());
        moveSpaces(file.transactionLine);
        this.codec.stringIntoDelimitedBySize(file.transactionLine, TRANSACTION_LINE_SPAN,
                delimitedBy(PARAGRAPH_OPEN_TAG, ASTERISK_DELIMITER),
                delimitedBy(sending, ASTERISK_DELIMITER),
                delimitedBy(PARAGRAPH_CLOSE_TAG, ASTERISK_DELIMITER));
        return writeFrom(file, file.transactionLine);
    }

    /**
     * Materialises the {@code HTML-L23} group of {@code app/cbl/CBSTM03A.CBL:L217-L220} as its declared 76
     * bytes.
     *
     * @param stName the {@code ST-NAME} value, normalised to {@link #L23_NAME_LENGTH} by the alphanumeric
     *     {@code MOVE} rule exactly as {@code L560} does
     * @return exactly {@link #HTML_L23_LENGTH} bytes, encoded in the dataset charset
     * @throws NullPointerException if {@code stName} is {@code null}
     */
    public byte[] composeNameParagraphGroup(final String stName) {
        Objects.requireNonNull(stName, "An ST-NAME value is required to materialise HTML-L23");
        final FixedWidthRecord group =
                FixedWidthRecord.forLayout(HTML_L23_LAYOUT, this.datasetCharset);
        this.codec.writePicX(group, L23_NAME_SPAN, stName);
        return group.toByteArray();
    }

    // Those operations are all subtly different from what COBOL does, and one of them looking close enough
    // is exactly how a parity defect gets in.

    /**
     * {@code STRING <sending> DELIMITED BY <delimiter>}: the characters of {@code sending} up to but not
     * including the first occurrence of {@code delimiter}, or all of {@code sending} when the delimiter
     * does not occur.
     *
     * @param sending the sending item, at its full declared width
     * @param delimiter the delimiter literal; at least one character
     * @return the transferred characters, possibly empty and never longer than {@code sending}
     * @throws NullPointerException if either argument is {@code null}
     * @throws IllegalArgumentException if {@code delimiter} is empty, which no COBOL {@code DELIMITED BY}
     *     phrase can express
     */
    public static String delimitedBy(final String sending, final String delimiter) {
        Objects.requireNonNull(sending, "A sending item is required for STRING ... DELIMITED BY");
        Objects.requireNonNull(delimiter, "A delimiter is required; use delimitedBySize(String) for "
                + "DELIMITED BY SIZE");
        if (delimiter.isEmpty()) {
            throw new IllegalArgumentException("An empty delimiter cannot be expressed by a COBOL "
                    + "DELIMITED BY phrase; use delimitedBySize(String) to transfer a sending item "
                    + "whole");
        }
        final int position = delimiterPosition(sending, delimiter);
        if (position == DELIMITER_NOT_FOUND) {
            return sending;
        }
        return sending.substring(0, position);
    }

    /**
     * {@code STRING <sending> DELIMITED BY SIZE}: the whole sending item, every declared character of it.
     *
     * @param sending the sending item, at its full declared width
     * @return {@code sending}, unchanged
     * @throws NullPointerException if {@code sending} is {@code null}
     */
    public static String delimitedBySize(final String sending) {
        return Objects.requireNonNull(sending,
                "A sending item is required for STRING ... DELIMITED BY SIZE");
    }

    private static int delimiterPosition(final String sending, final String delimiter) {
        final int lastStart = sending.length() - delimiter.length();
        for (int start = 0; start <= lastStart; start++) {
            boolean matched = true;
            for (int offset = 0; offset < delimiter.length(); offset++) {
                if (sending.charAt(start + offset) != delimiter.charAt(offset)) {
                    matched = false;
                    break;
                }
            }
            if (matched) {
                return start;
            }
        }
        return DELIMITER_NOT_FOUND;
    }

    private static void moveSpaces(final FixedWidthRecord line) {
        line.fill(0, line.recordLength(), line.spacePadByte());
    }

    private FileStatus.Outcome writeFrom(final HtmlStatementFile file,
                                         final FixedWidthRecord source) {
        final byte[] sending = source.toByteArray();
        moveSpaces(file.recordArea);
        file.recordArea.writeBytes(0,
                Arrays.copyOf(sending, Math.min(sending.length, RECORD_LENGTH)));
        return emitRecordArea(file);
    }

    private FileStatus.Outcome emitRecordArea(final HtmlStatementFile file) {
        final byte[] record = file.recordArea.toByteArray();
        final String status = Objects.requireNonNull(file.sink.write(record),
                "A record sink must report a two-character FILE STATUS from write(byte[]), never "
                        + "null");
        file.recordsWritten++;
        return FileStatus.outcomeOfStatus(status);
    }

    private static void requireOpen(final HtmlStatementFile file, final String operation) {
        Objects.requireNonNull(file, "An open " + HTMLFILE_DD_NAME + " handle is required to "
                + operation + "; obtain one from open() or open(HtmlRecordSink)");
        if (!file.open) {
            throw new IllegalStateException("Cannot " + operation + ": this " + HTMLFILE_DD_NAME
                    + " handle is closed. app/cbl/CBSTM03A.CBL opens the file once at L293 and "
                    + "closes it once at L339, so a second close or a write after close means the "
                    + "caller's control flow has diverged from the COBOL's. Open a new handle.");
        }
    }

    private static DatasetRelation requireAddressableDataset(final String dsname) {
        if (dsname == null || dsname.isEmpty()) {
            throw new IllegalStateException("carddemo.datasets." + HTMLFILE_DD_NAME
                    + ".dsname is not configured, so no default sink can be built. Configure the "
                    + "dataset name, or open the file with an explicit sink through "
                    + "open(HtmlRecordSink).");
        }
        try {
            return DatasetRelation.of(dsname, RECORD_LENGTH);
        } catch (IllegalArgumentException notADatasetName) {
            throw new IllegalStateException("carddemo.datasets." + HTMLFILE_DD_NAME
                    + ".dsname cannot be addressed as a dataset, so no default sink can be built. A "
                    + "filesystem location - which the fixture-backed 'test' profile binds - is one of "
                    + "the things this refuses, on purpose: open the file with an explicit sink through "
                    + "open(HtmlRecordSink) instead, exactly as the unit tests and the parity harness "
                    + "do. The dataset-name grammar's own verdict is attached.", notADatasetName);
        }
    }

    static String insertStatement(final String dsname, final String identifierQuote) {
        Objects.requireNonNull(dsname, "A dataset name is required to address " + HTMLFILE_DD_NAME
                + "; it is declared as carddemo.datasets." + HTMLFILE_DD_NAME + ".dsname");
        Objects.requireNonNull(identifierQuote,
                "An identifier quote character is required; normaliseIdentifierQuote(String) "
                        + "substitutes the SQL-standard one when a driver reports none");
        if (identifierQuote.isBlank()) {
            throw new IllegalArgumentException("An identifier quote character cannot be blank: "
                    + "delimiting with blanks would leave " + HTMLFILE_DD_NAME + "'s dataset name "
                    + "undelimited, and an undelimited period is a qualifier separator. Pass the "
                    + "result of normaliseIdentifierQuote(String), which substitutes the "
                    + "SQL-standard quote in exactly this case.");
        }
        return INSERT_STATEMENT_PREFIX + identifierQuote
                + dsname.replace(identifierQuote, identifierQuote + identifierQuote)
                + identifierQuote + INSERT_STATEMENT_SUFFIX;
    }

    static String normaliseIdentifierQuote(final String reported) {
        if (reported == null || reported.isBlank()) {
            return ANSI_IDENTIFIER_QUOTE;
        }
        return reported;
    }

    private static String resolveIdentifierQuote(final JdbcTemplate jdbcTemplate) {
        try {
            return normaliseIdentifierQuote(jdbcTemplate.execute(
                    (ConnectionCallback<String>) connection -> {
                        final DatabaseMetaData metaData = connection.getMetaData();
                        return metaData == null ? null : metaData.getIdentifierQuoteString();
                    }));
        } catch (DataAccessException unreachable) {
            LOG.warn("Could not ask the driver behind " + HTMLFILE_DD_NAME + " for its identifier "
                    + "quote character - " + BackendDiagnostic.of(unreachable).describe()
                    + "; composing the INSERT with the SQL-standard quote. The write "
                    + "itself will report this condition as FILE STATUS " + PERMANENT_ERROR_STATUS
                    + ".");
            return ANSI_IDENTIFIER_QUOTE;
        }
    }
}
