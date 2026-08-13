package com.vsergeychik.carddemo.statement;

import com.vsergeychik.carddemo.common.CobolDecimal;
import com.vsergeychik.carddemo.common.DatasetRelation;
import com.vsergeychik.carddemo.common.DatasetRelation.BackendDiagnostic;
import com.vsergeychik.carddemo.common.FileStatus;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.RecordImageForm;
import com.vsergeychik.carddemo.common.FixedWidthRecord;
import com.vsergeychik.carddemo.common.FixedWidthRecord.FieldSpan;
import com.vsergeychik.carddemo.common.FixedWidthRecord.RecordLayout;
import com.vsergeychik.carddemo.config.CobolCharsetConfig;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBinding;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBindings;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementSetter;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.util.Objects;

/**
 * The Java owner of the 80-byte {@code STMTFILE} plain-text statement record written by
 * {@code app/cbl/CBSTM03A.CBL}.
 *
 * <p>The enumeration and the COBOL agree, and the COBOL is the parity oracle, so seventeen it is.
 */
@Component
public class StatementTextWriter {
    /**
     * The mainframe DD name of the plain-text statement dataset, and the key under which
     * {@code carddemo.datasets} in {@code application.yml} carries its location and geometry.
     */
    public static final String DD_NAME = "STMTFILE";

    public static final int RECORD_LENGTH = 80;

    /**
     * The record format the creating JCL step declares: {@code FB}, fixed blocked, from
     * {@code DCB=(LRECL=80,BLKSIZE=8000,RECFM=FB)} at {@code app/jcl/CREASTMT.JCL:L89}.
     */
    public static final String RECORD_FORMAT = "FB";

    /**
     * The block size declared by the creating JCL step: {@code 8000}, from
     * {@code DCB=(LRECL=80,BLKSIZE=8000,RECFM=FB)} at {@code app/jcl/CREASTMT.JCL:L89}.
     */
    public static final int BLOCK_SIZE = 8000;

    /**
     * The number of {@code 05} line groups in {@code 01 STATEMENT-LINES}: {@code 17}.
     */
    public static final int LINE_COUNT = 17;

    /**
     * The total width of {@code 01 STATEMENT-LINES} in bytes: {@code 1360}, being {@value #LINE_COUNT}
     * groups of {@value #RECORD_LENGTH}.
     */
    public static final int STATEMENT_LINES_LENGTH = LINE_COUNT * RECORD_LENGTH;

    public static final int EDITED_AMOUNT_LENGTH = 13;

    static final int EDITED_INTEGER_DIGITS = 9;

    static final int EDITED_FRACTION_DIGITS = CobolDecimal.MONETARY_SCALE;

    static final int ACCOUNT_ID_DIGITS = 11;

    static final int FICO_SCORE_DIGITS = 3;

    private static final char SPACE = ' ';

    private static final char ZERO = '0';

    private static final char DECIMAL_POINT = '.';

    private static final char MINUS = '-';

    private static final Log LOG = LogFactory.getLog(StatementTextWriter.class);

    /**
     * One {@code 05} line group of {@code 01 STATEMENT-LINES}, in COBOL declaration order.
     *
     * <p>Declaration order is therefore the offset order, exactly as a COBOL group's is.
     */
    public enum StatementLine {
        /**
         * {@code 05 ST-LINE0.} - the opening banner, {@code app/cbl/CBSTM03A.CBL:L86-L89}.
         */
        ST_LINE0("ST-LINE0"),

        /**
         * {@code 05 ST-LINE1.} - the customer name, {@code app/cbl/CBSTM03A.CBL:L90-L92}.
         */
        ST_LINE1("ST-LINE1"),

        /**
         * {@code 05 ST-LINE2.} - address line 1, {@code app/cbl/CBSTM03A.CBL:L93-L95}.
         */
        ST_LINE2("ST-LINE2"),

        /**
         * {@code 05 ST-LINE3.} - address line 2, {@code app/cbl/CBSTM03A.CBL:L96-L98}.
         */
        ST_LINE3("ST-LINE3"),

        /**
         * {@code 05 ST-LINE4.} - address line 3, {@code app/cbl/CBSTM03A.CBL:L99-L100}.
         */
        ST_LINE4("ST-LINE4"),

        /**
         * {@code 05 ST-LINE5.} - an {@code ALL '-'} rule, {@code app/cbl/CBSTM03A.CBL:L101-L102}.
         */
        ST_LINE5("ST-LINE5"),

        /**
         * {@code 05 ST-LINE6.} - the Basic Details heading, {@code L103-L106}.
         */
        ST_LINE6("ST-LINE6"),

        /**
         * {@code 05 ST-LINE7.} - the account id line, {@code L107-L110}.
         */
        ST_LINE7("ST-LINE7"),

        /**
         * {@code 05 ST-LINE8.} - the current balance line, {@code L111-L115}.
         */
        ST_LINE8("ST-LINE8"),

        /**
         * {@code 05 ST-LINE9.} - the FICO score line, {@code L116-L119}.
         */
        ST_LINE9("ST-LINE9"),

        /**
         * {@code 05 ST-LINE10.} - an {@code ALL '-'} rule, {@code L120-L121}.
         */
        ST_LINE10("ST-LINE10"),

        /**
         * {@code 05 ST-LINE11.} - the Transaction Summary heading, {@code L122-L125}.
         */
        ST_LINE11("ST-LINE11"),

        /**
         * {@code 05 ST-LINE12.} - an {@code ALL '-'} rule, {@code L126-L127}.
         */
        ST_LINE12("ST-LINE12"),

        /**
         * {@code 05 ST-LINE13.} - the three column headings, {@code L128-L131}.
         */
        ST_LINE13("ST-LINE13"),

        /**
         * {@code 05 ST-LINE14.} - one transaction detail line, {@code L132-L137}.
         */
        ST_LINE14("ST-LINE14"),

        /**
         * {@code 05 ST-LINE14A.} - the {@code Total EXP:} total line, {@code L138-L142}.
         */
        ST_LINE14A("ST-LINE14A"),

        /**
         * {@code 05 ST-LINE15.} - the closing banner, {@code L143-L146}.
         */
        ST_LINE15("ST-LINE15");

        private final String cobolName;

        StatementLine(String cobolName) {
            this.cobolName = cobolName;
        }

        /**
         * The COBOL group name exactly as {@code app/cbl/CBSTM03A.CBL} declares it - for example
         * {@code ST-LINE14A}.
         *
         * @return the COBOL name, never {@code null}
         */
        public String cobolName() {
            return cobolName;
        }

        /**
         * The absolute 0-based byte offset of this line within {@code 01 STATEMENT-LINES}:
         * {@code ordinal *}{@value StatementTextWriter#RECORD_LENGTH}.
         *
         * @return the offset, {@code 0} for {@link #ST_LINE0} and {@code 1280} for {@link #ST_LINE15}
         */
        public int offset() {
            return ordinal() * RECORD_LENGTH;
        }

        /**
         * The width of this line in bytes, which is {@value StatementTextWriter#RECORD_LENGTH} for every
         * line because every line is one whole {@code STMTFILE} record.
         *
         * @return {@value StatementTextWriter#RECORD_LENGTH}
         */
        public int length() {
            return RECORD_LENGTH;
        }
    }

    /**
     * The category of a mutable slot, which decides the one thing that differs between slots when
     * {@code INITIALIZE STATEMENT-LINES} runs: the image the slot is cleared to.
     */
    public enum SlotKind {
        /**
         * {@code PIC X(n)} character data.
         */
        ALPHANUMERIC {
            @Override
            String initialImage(FixedWidthCodec codec, int length) {
                return codec.movePicX("", length);
            }
        },

        /**
         * The {@code PIC 9(9).99-} edited picture of {@code ST-CURR-BAL}, whose leading zeros are retained.
         */
        ZERO_FILLED_AMOUNT {
            @Override
            String initialImage(FixedWidthCodec codec, int length) {
                return editZeroFilledAmount(CobolDecimal.monetaryZero());
            }
        },

        /**
         * The {@code PIC Z(9).99-} edited picture of {@code ST-TRANAMT} and {@code ST-TOTAL-TRAMT}, whose
         * leading zeros are suppressed to spaces.
         */
        ZERO_SUPPRESSED_AMOUNT {
            @Override
            String initialImage(FixedWidthCodec codec, int length) {
                return editZeroSuppressedAmount(CobolDecimal.monetaryZero());
            }
        };

        abstract String initialImage(FixedWidthCodec codec, int length);
    }

    /**
     * One mutable named item inside {@code 01 STATEMENT-LINES} - the eleven elementary items that are not
     * {@code FILLER}, and therefore the only bytes of the group that ever change.
     */
    public enum StatementSlot {
        /**
         * {@code 10 ST-NAME PIC X(75).} at {@code app/cbl/CBSTM03A.CBL:L91}, the whole of {@code ST-LINE1}
         * bar five trailing spaces.
         */
        ST_NAME("ST-NAME", StatementLine.ST_LINE1, 0, 75, SlotKind.ALPHANUMERIC),

        /**
         * {@code 10 ST-ADD1 PIC X(50).} at {@code L94}.
         */
        ST_ADD1("ST-ADD1", StatementLine.ST_LINE2, 0, 50, SlotKind.ALPHANUMERIC),

        /**
         * {@code 10 ST-ADD2 PIC X(50).} at {@code L97}.
         */
        ST_ADD2("ST-ADD2", StatementLine.ST_LINE3, 0, 50, SlotKind.ALPHANUMERIC),

        /**
         * {@code 10 ST-ADD3 PIC X(80).} at {@code L100} - the only slot that occupies an entire line, so
         * {@code ST-LINE4} declares no {@code FILLER} at all.
         */
        ST_ADD3("ST-ADD3", StatementLine.ST_LINE4, 0, 80, SlotKind.ALPHANUMERIC),

        /**
         * {@code 10 ST-ACCT-ID PIC X(20).} at {@code L109}, immediately after the 20-character
         * {@code 'Account ID :'} label.
         */
        ST_ACCT_ID("ST-ACCT-ID", StatementLine.ST_LINE7, 20, 20, SlotKind.ALPHANUMERIC),

        /**
         * {@code 10 ST-CURR-BAL PIC 9(9).99-.} at {@code L113}, immediately after the 20-character
         * {@code 'Current Balance :'} label.
         */
        ST_CURR_BAL("ST-CURR-BAL", StatementLine.ST_LINE8, 20, EDITED_AMOUNT_LENGTH,
                SlotKind.ZERO_FILLED_AMOUNT),

        /**
         * {@code 10 ST-FICO-SCORE PIC X(20).} at {@code L118}, immediately after the 20-character
         * {@code 'FICO Score :'} label.
         */
        ST_FICO_SCORE("ST-FICO-SCORE", StatementLine.ST_LINE9, 20, 20, SlotKind.ALPHANUMERIC),

        /**
         * {@code 10 ST-TRANID PIC X(16).} at {@code L133}, at the start of the detail line.
         */
        ST_TRANID("ST-TRANID", StatementLine.ST_LINE14, 0, 16, SlotKind.ALPHANUMERIC),

        /**
         * {@code 10 ST-TRANDT PIC X(49).} at {@code L135}, after the one-space separator at offset 16.
         */
        ST_TRANDT("ST-TRANDT", StatementLine.ST_LINE14, 17, 49, SlotKind.ALPHANUMERIC),

        /**
         * {@code 10 ST-TRANAMT PIC Z(9).99-.} at {@code L137}, after the literal {@code '$'} at offset 66.
         */
        ST_TRANAMT("ST-TRANAMT", StatementLine.ST_LINE14, 67, EDITED_AMOUNT_LENGTH,
                SlotKind.ZERO_SUPPRESSED_AMOUNT),

        /**
         * {@code 10 ST-TOTAL-TRAMT PIC Z(9).99-.} at {@code L142}, after the literal {@code '$'} at offset
         * 66 of {@code ST-LINE14A}.
         */
        ST_TOTAL_TRAMT("ST-TOTAL-TRAMT", StatementLine.ST_LINE14A, 67, EDITED_AMOUNT_LENGTH,
                SlotKind.ZERO_SUPPRESSED_AMOUNT);

        private final String cobolName;

        private final StatementLine line;

        private final int offsetWithinLine;

        private final int length;

        private final SlotKind kind;

        StatementSlot(String cobolName,
                      StatementLine line,
                      int offsetWithinLine,
                      int length,
                      SlotKind kind) {
            this.cobolName = cobolName;
            this.line = line;
            this.offsetWithinLine = offsetWithinLine;
            this.length = length;
            this.kind = kind;
        }

        /**
         * The COBOL item name exactly as {@code app/cbl/CBSTM03A.CBL} declares it - for example
         * {@code ST-TOTAL-TRAMT}.
         *
         * @return the COBOL name, never {@code null}
         */
        public String cobolName() {
            return cobolName;
        }

        /**
         * The line group this slot belongs to, so a caller can see which record a change will appear in.
         *
         * @return the owning line, never {@code null}
         */
        public StatementLine line() {
            return line;
        }

        /**
         * The slot's 0-based offset within its own line, which is the offset a reader can check directly
         * against the {@code PIC} widths preceding it in the copybook group.
         *
         * @return the within-line offset, from {@code 0} to {@code 79}
         */
        public int offsetWithinLine() {
            return offsetWithinLine;
        }

        /**
         * The slot's absolute 0-based offset within {@code 01 STATEMENT-LINES}:
         * {@code line().offset() + offsetWithinLine()}.
         *
         * @return the absolute offset
         */
        public int offset() {
            return line.offset() + offsetWithinLine;
        }

        /**
         * The slot's declared width in bytes, taken from its {@code PICTURE}.
         *
         * @return the width in bytes, at least 1
         */
        public int length() {
            return length;
        }

        /**
         * What {@code INITIALIZE STATEMENT-LINES} clears this slot to.
         *
         * @return the slot's category, never {@code null}
         */
        public SlotKind kind() {
            return kind;
        }
    }

    /**
     * Where a rendered {@value StatementTextWriter#RECORD_LENGTH}-byte statement record goes.
     */
    public interface RecordSink {
        FileStatus.Outcome write(byte[] recordImage);

        default FileStatus.Outcome open() {
            return FileStatus.Outcome.OK;
        }

        default FileStatus.Outcome close() {
            return FileStatus.Outcome.OK;
        }

        default FileStatus.Outcome discard(int recordsWritten) {
            return FileStatus.Outcome.OK;
        }
    }

    private static final RecordLayout STATEMENT_LINES = buildStatementLinesLayout();

    private static RecordLayout buildStatementLinesLayout() {
        int line0 = StatementLine.ST_LINE0.offset();
        int line1 = StatementLine.ST_LINE1.offset();
        int line2 = StatementLine.ST_LINE2.offset();
        int line3 = StatementLine.ST_LINE3.offset();
        int line4 = StatementLine.ST_LINE4.offset();
        int line5 = StatementLine.ST_LINE5.offset();
        int line6 = StatementLine.ST_LINE6.offset();
        int line7 = StatementLine.ST_LINE7.offset();
        int line8 = StatementLine.ST_LINE8.offset();
        int line9 = StatementLine.ST_LINE9.offset();
        int line10 = StatementLine.ST_LINE10.offset();
        int line11 = StatementLine.ST_LINE11.offset();
        int line12 = StatementLine.ST_LINE12.offset();
        int line13 = StatementLine.ST_LINE13.offset();
        int line14 = StatementLine.ST_LINE14.offset();
        int line14a = StatementLine.ST_LINE14A.offset();
        int line15 = StatementLine.ST_LINE15.offset();

        return RecordLayout.of(STATEMENT_LINES_LENGTH,

                FieldSpan.filler(line0, 31, repeat('*', 31)),
                FieldSpan.filler(line0 + 31, 18, "START OF STATEMENT"),
                FieldSpan.filler(line0 + 49, 31, repeat('*', 31)),

                slotSpan(StatementSlot.ST_NAME),
                FieldSpan.filler(line1 + 75, 5),

                slotSpan(StatementSlot.ST_ADD1),
                FieldSpan.filler(line2 + 50, 30),

                slotSpan(StatementSlot.ST_ADD2),
                FieldSpan.filler(line3 + 50, 30),

                slotSpan(StatementSlot.ST_ADD3),

                FieldSpan.filler(line5, RECORD_LENGTH, repeat('-', RECORD_LENGTH)),

                FieldSpan.filler(line6, 33),
                FieldSpan.filler(line6 + 33, 14, "Basic Details"),
                FieldSpan.filler(line6 + 47, 33),

                FieldSpan.filler(line7, 20, "Account ID         :"),
                slotSpan(StatementSlot.ST_ACCT_ID),
                FieldSpan.filler(line7 + 40, 40),

                FieldSpan.filler(line8, 20, "Current Balance    :"),
                slotSpan(StatementSlot.ST_CURR_BAL),
                FieldSpan.filler(line8 + 33, 7),
                FieldSpan.filler(line8 + 40, 40),

                FieldSpan.filler(line9, 20, "FICO Score         :"),
                slotSpan(StatementSlot.ST_FICO_SCORE),
                FieldSpan.filler(line9 + 40, 40),

                FieldSpan.filler(line10, RECORD_LENGTH, repeat('-', RECORD_LENGTH)),

                FieldSpan.filler(line11, 30),
                FieldSpan.filler(line11 + 30, 20, "TRANSACTION SUMMARY "),
                FieldSpan.filler(line11 + 50, 30),

                FieldSpan.filler(line12, RECORD_LENGTH, repeat('-', RECORD_LENGTH)),

                FieldSpan.filler(line13, 16, "Tran ID         "),
                FieldSpan.filler(line13 + 16, 51, "Tran Details    "),
                FieldSpan.filler(line13 + 67, 13, "  Tran Amount"),

                slotSpan(StatementSlot.ST_TRANID),
                FieldSpan.filler(line14 + 16, 1, " "),
                slotSpan(StatementSlot.ST_TRANDT),
                FieldSpan.filler(line14 + 66, 1, "$"),
                slotSpan(StatementSlot.ST_TRANAMT),

                FieldSpan.filler(line14a, 10, "Total EXP:"),
                FieldSpan.filler(line14a + 10, 56),
                FieldSpan.filler(line14a + 66, 1, "$"),
                slotSpan(StatementSlot.ST_TOTAL_TRAMT),

                FieldSpan.filler(line15, 32, repeat('*', 32)),
                FieldSpan.filler(line15 + 32, 16, "END OF STATEMENT"),
                FieldSpan.filler(line15 + 48, 32, repeat('*', 32)));
    }

    private static FieldSpan slotSpan(StatementSlot slot) {
        return FieldSpan.alphanumeric(slot.cobolName(), slot.offset(), slot.length());
    }

    private static String repeat(char character, int width) {
        return String.valueOf(character).repeat(width);
    }

    static String editZeroFilledAmount(BigDecimal value) {
        return editAmount(value, false);
    }

    static String editZeroSuppressedAmount(BigDecimal value) {
        return editAmount(value, true);
    }

    private static String editAmount(BigDecimal value, boolean suppressLeadingZeros) {
        Objects.requireNonNull(value, "A value is required to edit through a numeric-edited "
                + "PICTURE; to render a zero amount pass CobolDecimal.monetaryZero() explicitly, "
                + "because the two masks render zero differently and the choice must be visible");

        char signPosition = value.signum() < 0 ? MINUS : SPACE;

        BigDecimal magnitude = CobolDecimal.storeAtPicture(value.abs(), EDITED_INTEGER_DIGITS,
                EDITED_FRACTION_DIGITS);
        String digits = alignedDigits(magnitude);
        String integerPositions = digits.substring(0, EDITED_INTEGER_DIGITS);
        String fractionPositions = digits.substring(EDITED_INTEGER_DIGITS);

        StringBuilder image = new StringBuilder(EDITED_AMOUNT_LENGTH);
        image.append(suppressLeadingZeros ? suppress(integerPositions) : integerPositions);
        image.append(DECIMAL_POINT);
        image.append(fractionPositions);
        image.append(signPosition);
        return image.toString();
    }

    private static String alignedDigits(BigDecimal magnitude) {
        int width = EDITED_INTEGER_DIGITS + EDITED_FRACTION_DIGITS;
        String unscaled = magnitude.unscaledValue().toString();
        String padded = repeat(ZERO, width) + unscaled;
        return padded.substring(padded.length() - width);
    }

    private static String suppress(String integerPositions) {
        StringBuilder suppressed = new StringBuilder(integerPositions);
        for (int position = 0; position < suppressed.length(); position++) {
            if (suppressed.charAt(position) != ZERO) {
                break;
            }
            suppressed.setCharAt(position, SPACE);
        }
        return suppressed.toString();
    }

    private final JdbcTemplate jdbcTemplate;

    private final RecordImageForm recordImageForm;

    private final FixedWidthCodec codec;

    private final DatasetBinding binding;

    private final DatasetRelation relation;

    private final RuntimeException datasetRefusal;

    /**
     * Wires the writer and verifies, before the application can start, that the configured dataset geometry
     * agrees with the COBOL file description.
     *
     * @param jdbcTemplate the module's single {@link JdbcTemplate}
     * @param datasetCharset the code page the dataset holds its record images in, from
     *     {@code carddemo.charset.dataset}
     * @param datasetBindings the DD-name-keyed dataset catalogue bound from {@code carddemo.datasets}
     * @param recordImageForm how this deployment's driver presents a record image, character or binary
     * @throws NullPointerException if any argument is {@code null}
     * @throws IllegalStateException if no binding is configured for {@link #DD_NAME}, which the catalogue
     *     itself reports, or if the configured record length is not {@value #RECORD_LENGTH}
     */
    public StatementTextWriter(
            JdbcTemplate jdbcTemplate,
            @Qualifier(CobolCharsetConfig.DATASET_CHARSET_BEAN_NAME) Charset datasetCharset,
            DatasetBindings datasetBindings,
            RecordImageForm recordImageForm) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "A JdbcTemplate is required to "
                + "write the " + DD_NAME + " dataset; the data-source configuration declares the "
                + "single instance this module shares");
        this.recordImageForm = Objects.requireNonNull(recordImageForm, "A record-image representation is "
                + "required: whether this deployment's driver takes a record image as characters or as "
                + "bytes is stated once, by " + RecordImageForm.FORM_PROPERTY + ", and never decided per "
                + "writer");
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
                    + "record-length " + binding.recordLength() + ", but the plain-text statement "
                    + "record is " + RECORD_LENGTH + " bytes: app/cbl/CBSTM03A.CBL:L45 declares "
                    + "01 FD-STMTFILE-REC PIC X(80) and app/jcl/CREASTMT.JCL:L89 declares LRECL=80 "
                    + "on the creating step. Correct record-length to " + RECORD_LENGTH
                    + " in application.yml; a record width is copybook-fixed and must never be "
                    + "overridden per profile.");
        }
        if (!RECORD_FORMAT.equalsIgnoreCase(binding.recordFormat())) {
            throw new IllegalStateException("carddemo.datasets." + DD_NAME + " declares record-format "
                    + describeConfiguredRecordFormat() + ", but app/jcl/CREASTMT.JCL:L89 declares "
                    + "RECFM=" + RECORD_FORMAT + " on the STEP040 step that creates the dataset. Fixed "
                    + "blocked is what makes every statement record exactly " + RECORD_LENGTH
                    + " bytes, so it is required rather than assumed. Set record-format to "
                    + RECORD_FORMAT + " in application.yml.");
        }

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

    private String describeConfiguredRecordFormat() {
        return binding.recordFormat() == null ? "absent" : "'" + binding.recordFormat() + "'";
    }

    /**
     * The configured binding for {@link #DD_NAME} - its location, organization, record format, block size
     * and record length exactly as configuration declares them.
     *
     * @return the binding, never {@code null}
     */
    public DatasetBinding datasetBinding() {
        return binding;
    }

    /**
     * The code page this writer encodes records in, as injected.
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

    /**
     * Opens {@link #DD_NAME} for output against the configured dataset, mirroring
     * {@code OPEN OUTPUT STMT-FILE} at {@code app/cbl/CBSTM03A.CBL:L293}.
     *
     * @return a new per-execution handle, already in the {@code INITIALIZE STATEMENT-LINES} state
     * @throws IllegalStateException if the configured dataset name cannot be addressed as a dataset, as
     *     {@link #insertStatement()} describes
     */
    public StatementFile openOutput() {
        return new StatementFile(new JdbcRecordSink(jdbcTemplate, insertStatement(), recordImageForm,
                codec.charset(), requireRelation().describeStatement(),
                requireRelation().deleteAll(), requireRelation().countAllStatement()));
    }

    /**
     * Opens {@link #DD_NAME} for output against a caller-supplied sink.
     *
     * @param sink where rendered records go; must not be {@code null}
     * @return a new per-execution handle, already in the {@code INITIALIZE STATEMENT-LINES} state
     * @throws NullPointerException if {@code sink} is {@code null}
     */
    public StatementFile openOutput(RecordSink sink) {
        return new StatementFile(Objects.requireNonNull(sink, "A record sink is required to open "
                + DD_NAME + " for output; call openOutput() for the configured dataset"));
    }

    String insertStatement() {
        return requireRelation().insertRecordImage();
    }

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
     * The default {@link RecordSink}: one parameterised insert of the whole record image per record, issued
     * immediately and in call order.
     */
    private static final class JdbcRecordSink implements RecordSink {
        private final JdbcTemplate jdbcTemplate;

        private final String statement;

        private final RecordImageForm recordImageForm;

        private final Charset charset;

        private final String describeStatement;

        private final String clearStatement;

        private final String countStatement;

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
         * Establishes the generation this run writes into: {@code OPEN OUTPUT STMT-FILE} at
         * {@code app/cbl/CBSTM03A.CBL:L293}, over a dataset {@code app/jcl/CREASTMT.JCL:L87-L91} declares
         * {@code DISP=(NEW,CATLG,DELETE)} with {@code LRECL=80 BLKSIZE=8000}.
         *
         * <p>The describe resolves the DD name to a real destination and fails if it cannot - read-only,
         * its predicate false on every row, so nothing is transferred.
         *
         * @return {@link FileStatus.Outcome#OK} when the destination is established, or
         *     {@link FileStatus.Outcome#OTHER} when it could not be
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
                        + " to the caller");
                return FileStatus.Outcome.OTHER;
            }
        }

        /**
         * Confirms the destination survived the run: {@code CLOSE STMT-FILE} at
         * {@code app/cbl/CBSTM03A.CBL:L339}.
         *
         * @return {@link FileStatus.Outcome#OK} when the destination is still addressable, or
         *     {@link FileStatus.Outcome#OTHER} when it is not
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
                        + " to the caller");
                return FileStatus.Outcome.OTHER;
            }
        }

        /**
         * Deletes the generation this run wrote: the {@code DELETE} positional of
         * {@code app/jcl/CREASTMT.JCL:L87-L91}.
         *
         * @param recordsWritten how many records this run handed to this sink
         * @return {@link FileStatus.Outcome#OK} when the generation was discarded, or
         *     {@link FileStatus.Outcome#OTHER} when it was not
         */
        @Override
        public FileStatus.Outcome discard(int recordsWritten) {
            try {
                Integer held = jdbcTemplate.queryForObject(countStatement, Integer.class);
                if (held == null || held != recordsWritten) {
                    LOG.error("Refusing to apply the " + DD_NAME + " abnormal disposition of "
                            + "app/jcl/CREASTMT.JCL:L87: this run wrote " + recordsWritten
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
                        + ". Statements for the accounts this run got through may remain catalogued, "
                        + "which DISP=(NEW,CATLG,DELETE) says they should not; they are incomplete and "
                        + "must be deleted by hand before anything is issued from them");
                return FileStatus.Outcome.OTHER;
            }
        }

        /**
         * Writes one record, mapping a rejected write onto the {@code WHEN OTHER} arm.
         *
         * @param recordImage the record's bytes in the dataset code page
         * @return {@link FileStatus.Outcome#OK}, or {@link FileStatus.Outcome#OTHER} when the write was
         *     rejected
         */
        @Override
        public FileStatus.Outcome write(byte[] recordImage) {
            PreparedStatementSetter binder = parameters ->
                    recordImageForm.bindImage(parameters, 1, recordImage, charset);
            try {
                jdbcTemplate.update(statement, binder);
                return FileStatus.Outcome.OK;
            } catch (DataAccessException rejected) {
                // The reason is logged because the outcome travelling back to the caller is deliberately
                // coarse - the COBOL guard chain has a single WHEN OTHER arm - and discarding it would
                // leave a production abend undiagnosable.
                LOG.error("Rejected write of a " + RECORD_LENGTH + "-byte " + DD_NAME
                        + " record - " + BackendDiagnostic.of(rejected).describe()
                        + "; reporting FILE STATUS outcome "
                        + FileStatus.Outcome.OTHER.name() + " to the caller");
                return FileStatus.Outcome.OTHER;
            }
        }
    }

    /**
     * One opened {@link #DD_NAME} dataset, together with the {@link #STATEMENT_LINES_LENGTH}-byte
     * {@code 01 STATEMENT-LINES} area whose slots the caller populates.
     *
     * <p>A handle is not thread-safe, exactly as a COBOL record area is not, and is meant to be confined to
     * the step, chunk or test that opened it.
     */
    public final class StatementFile implements AutoCloseable {
        private final RecordSink sink;

        private final FixedWidthRecord lineArea;

        private boolean open;

        private final FileStatus.Outcome openOutcome;

        private int recordsWritten;

        private boolean discarded;

        private StatementFile(RecordSink sink) {
            this.sink = sink;
            this.lineArea = codec.newRecord(STATEMENT_LINES);
            this.open = true;
            this.openOutcome = Objects.requireNonNull(sink.open(), "A record sink must report an "
                    + "outcome for OPEN OUTPUT " + DD_NAME + "; there is no COBOL FILE STATUS meaning "
                    + "'no answer', so a null is an implementation defect");
            initializeStatementLines();
        }

        /**
         * What the open reported - {@code OPEN OUTPUT STMT-FILE} at {@code app/cbl/CBSTM03A.CBL:L293}.
         *
         * @return {@link FileStatus.Outcome#OK} when the destination was established, or
         *     {@link FileStatus.Outcome#OTHER} when it was not; never {@code null}
         */
        public FileStatus.Outcome openOutcome() {
            return openOutcome;
        }

        /**
         * Reproduces {@code INITIALIZE STATEMENT-LINES.} from {@code app/cbl/CBSTM03A.CBL:L459} - the
         * statement executed at the top of {@code 5000-CREATE-STATEMENT}, immediately before
         * {@code ST-LINE0} is written.
         */
        public void initializeStatementLines() {
            for (StatementSlot slot : StatementSlot.values()) {
                lineArea.writeSpan(slotSpan(slot), slot.kind().initialImage(codec, slot.length()));
            }
        }

        // Every one is an explicit COBOL MOVE, never a Java assignment.

        /**
         * Sets {@code ST-NAME PIC X(75)}, the customer name line.
         *
         * @param name the composed name; must not be {@code null}
         * @throws NullPointerException if {@code name} is {@code null}
         */
        public void setName(String name) {
            movePicX(StatementSlot.ST_NAME, name);
        }

        /**
         * Sets {@code ST-ADD1 PIC X(50)} from {@code CUST-ADDR-LINE-1 PIC X(50)}
         * ({@code app/cpy/CUSTREC.cpy:9}), per {@code MOVE CUST-ADDR-LINE-1 TO ST-ADD1} at
         * {@code app/cbl/CBSTM03A.CBL:L470}.
         *
         * @param addressLine1 the first address line; must not be {@code null}
         * @throws NullPointerException if {@code addressLine1} is {@code null}
         */
        public void setAddressLine1(String addressLine1) {
            movePicX(StatementSlot.ST_ADD1, addressLine1);
        }

        /**
         * Sets {@code ST-ADD2 PIC X(50)} from {@code CUST-ADDR-LINE-2 PIC X(50)}
         * ({@code app/cpy/CUSTREC.cpy:10}), per {@code MOVE CUST-ADDR-LINE-2 TO ST-ADD2} at
         * {@code app/cbl/CBSTM03A.CBL:L471}.
         *
         * @param addressLine2 the second address line; must not be {@code null}
         * @throws NullPointerException if {@code addressLine2} is {@code null}
         */
        public void setAddressLine2(String addressLine2) {
            movePicX(StatementSlot.ST_ADD2, addressLine2);
        }

        /**
         * Sets {@code ST-ADD3 PIC X(80)}, the composite third address line.
         *
         * @param addressLine3 the composed third address line; must not be {@code null}
         * @throws NullPointerException if {@code addressLine3} is {@code null}
         */
        public void setAddressLine3(String addressLine3) {
            movePicX(StatementSlot.ST_ADD3, addressLine3);
        }

        /**
         * Sets {@code ST-ACCT-ID PIC X(20)} from {@code ACCT-ID PIC 9(11)}
         * ({@code app/cpy/CVACT01Y.cpy:5}), per {@code MOVE ACCT-ID TO ST-ACCT-ID} at
         * {@code app/cbl/CBSTM03A.CBL:L483}.
         *
         * @param accountId the account id; must not be negative, because {@code PIC 9(11)} is an unsigned
         *     picture with no sign position
         * @throws IllegalArgumentException if {@code accountId} is negative
         */
        public void setAccountId(long accountId) {
            movePicX(StatementSlot.ST_ACCT_ID, codec.movePic9(accountId, ACCOUNT_ID_DIGITS));
        }

        /**
         * Sets {@code ST-CURR-BAL PIC 9(9).99-} from {@code ACCT-CURR-BAL PIC S9(10)V99}
         * ({@code app/cpy/CVACT01Y.cpy:7}), per {@code MOVE ACCT-CURR-BAL TO ST-CURR-BAL} at
         * {@code app/cbl/CBSTM03A.CBL:L484}.
         *
         * @param currentBalance the account's current balance; must not be {@code null}
         * @throws NullPointerException if {@code currentBalance} is {@code null}
         */
        public void setCurrentBalance(BigDecimal currentBalance) {
            writeEdited(StatementSlot.ST_CURR_BAL, editZeroFilledAmount(currentBalance));
        }

        /**
         * Sets {@code ST-FICO-SCORE PIC X(20)} from {@code CUST-FICO-CREDIT-SCORE PIC 9(03)}
         * ({@code app/cpy/CUSTREC.cpy:22}), per {@code MOVE CUST-FICO-CREDIT-SCORE TO ST-FICO-SCORE} at
         * {@code app/cbl/CBSTM03A.CBL:L485}.
         *
         * @param ficoScore the FICO credit score; must not be negative, because {@code PIC 9(03)} is
         *     unsigned
         * @throws IllegalArgumentException if {@code ficoScore} is negative
         */
        public void setFicoScore(int ficoScore) {
            movePicX(StatementSlot.ST_FICO_SCORE, codec.movePic9(ficoScore, FICO_SCORE_DIGITS));
        }

        /**
         * Sets {@code ST-TRANID PIC X(16)} from {@code TRNX-ID PIC X(16)} ({@code app/cpy/COSTM01.CPY:23}),
         * per {@code MOVE TRNX-ID TO ST-TRANID} at {@code app/cbl/CBSTM03A.CBL:L676}.
         *
         * @param transactionId the transaction id; must not be {@code null}
         * @throws NullPointerException if {@code transactionId} is {@code null}
         */
        public void setTransactionId(String transactionId) {
            movePicX(StatementSlot.ST_TRANID, transactionId);
        }

        /**
         * Sets {@code ST-TRANDT PIC X(49)} from {@code TRNX-DESC PIC X(100)}
         * ({@code app/cpy/COSTM01.CPY:28}), per {@code MOVE TRNX-DESC TO ST-TRANDT} at
         * {@code app/cbl/CBSTM03A.CBL:L677}.
         *
         * @param transactionDetails the transaction description; must not be {@code null}
         * @throws NullPointerException if {@code transactionDetails} is {@code null}
         */
        public void setTransactionDetails(String transactionDetails) {
            movePicX(StatementSlot.ST_TRANDT, transactionDetails);
        }

        /**
         * Sets {@code ST-TRANAMT PIC Z(9).99-} from {@code TRNX-AMT PIC S9(09)V99}
         * ({@code app/cpy/COSTM01.CPY:29}), per {@code MOVE TRNX-AMT TO ST-TRANAMT} at
         * {@code app/cbl/CBSTM03A.CBL:L678}.
         *
         * @param transactionAmount the transaction amount; must not be {@code null}
         * @throws NullPointerException if {@code transactionAmount} is {@code null}
         */
        public void setTransactionAmount(BigDecimal transactionAmount) {
            writeEdited(StatementSlot.ST_TRANAMT, editZeroSuppressedAmount(transactionAmount));
        }

        /**
         * Sets {@code ST-TOTAL-TRAMT PIC Z(9).99-} from {@code WS-TRN-AMT PIC S9(9)V99}
         * ({@code app/cbl/CBSTM03A.CBL:L68}), per {@code MOVE WS-TRN-AMT TO ST-TOTAL-TRAMT} at {@code L434}
         * - the running total the statement job accumulates across the customer's transactions.
         *
         * @param totalTransactionAmount the total of the customer's transaction amounts; must not be
         *     {@code null}
         * @throws NullPointerException if {@code totalTransactionAmount} is {@code null}
         */
        public void setTotalTransactionAmount(BigDecimal totalTransactionAmount) {
            writeEdited(StatementSlot.ST_TOTAL_TRAMT,
                    editZeroSuppressedAmount(totalTransactionAmount));
        }

        private void movePicX(StatementSlot slot, String value) {
            codec.writePicX(lineArea, slotSpan(slot), value);
        }

        private void writeEdited(StatementSlot slot, String image) {
            lineArea.writeSpan(slotSpan(slot), image);
        }

        /**
         * The characters a slot currently holds, at its full declared width and untrimmed.
         *
         * @param slot the slot to read; must not be {@code null}
         * @return exactly {@code slot.length()} characters
         * @throws NullPointerException if {@code slot} is {@code null}
         */
        public String slotImage(StatementSlot slot) {
            Objects.requireNonNull(slot, "A slot is required to read a named item of "
                    + "01 STATEMENT-LINES");
            return lineArea.readSpan(slotSpan(slot));
        }

        /**
         * Renders one line as text: exactly {@value #RECORD_LENGTH} characters, decoded under the injected
         * dataset code page.
         *
         * @param line the line to render; must not be {@code null}
         * @return exactly {@value #RECORD_LENGTH} characters
         * @throws NullPointerException if {@code line} is {@code null}
         */
        public String renderLine(StatementLine line) {
            Objects.requireNonNull(line, "A line identity is required to render a statement record");
            return lineArea.readString(line.offset(), line.length());
        }

        /**
         * Renders one line as the bytes that would reach the dataset: exactly {@value #RECORD_LENGTH} of
         * them, in the injected code page.
         *
         * @param line the line to render; must not be {@code null}
         * @return a fresh array of exactly {@value #RECORD_LENGTH} bytes
         * @throws NullPointerException if {@code line} is {@code null}
         */
        public byte[] renderLineBytes(StatementLine line) {
            Objects.requireNonNull(line, "A line identity is required to render a statement record");
            return lineArea.readBytes(line.offset(), line.length());
        }

        /**
         * Writes one line to the dataset, reproducing a single {@code WRITE FD-STMTFILE-REC FROM ST-LINEn}
         * statement.
         *
         * <p>Writing the same line twice writes two identical records, which is exactly what the COBOL does
         * with {@code ST-LINE5} at {@code app/cbl/CBSTM03A.CBL:L492} and {@code L494} and with
         * {@code ST-LINE12} at {@code L500}, {@code L502} and {@code L435}.
         *
         * @param line the line to write; must not be {@code null}
         * @return {@link FileStatus.Outcome#OK} when the record was accepted, or
         *     {@link FileStatus.Outcome#OTHER} when the sink rejected it; never {@code null}
         * @throws NullPointerException if {@code line} is {@code null}, or if the sink returns a
         *     {@code null} outcome
         * @throws IllegalStateException if this handle has already been closed
         */
        public FileStatus.Outcome writeLine(StatementLine line) {
            Objects.requireNonNull(line, "A line identity is required to write a statement record");
            if (!open) {
                throw new IllegalStateException("Cannot write " + line.cobolName() + " to "
                        + DD_NAME + ": this handle was closed after " + recordsWritten
                        + " record(s). The COBOL opens the dataset once at the start of the run "
                        + "(app/cbl/CBSTM03A.CBL:L293) and closes it once at the end (L339), so open "
                        + "a new handle for a new run rather than reusing a closed one.");
            }
            FileStatus.Outcome outcome = Objects.requireNonNull(sink.write(renderLineBytes(line)),
                    "The record sink supplied for " + DD_NAME + " returned a null outcome from "
                            + "write(byte[]) for " + line.cobolName() + " after " + recordsWritten
                            + " record(s). A sink must report FileStatus.Outcome.OK when the record "
                            + "was accepted or FileStatus.Outcome.OTHER for any failure - the WHEN "
                            + "OTHER arm the statement job branches on - because there is no COBOL "
                            + "FILE STATUS meaning 'no answer'.");
            recordsWritten++;
            return outcome;
        }

        /**
         * How many records have been handed to the sink through this handle.
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
         * Closes the dataset, reproducing {@code CLOSE STMT-FILE} at {@code app/cbl/CBSTM03A.CBL:L339}, and
         * reports the outcome.
         *
         * @return {@link FileStatus.Outcome#OK} when the sink closed cleanly or was already closed, or
         *     {@link FileStatus.Outcome#OTHER} otherwise; never {@code null}
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
         * Applies the abnormal disposition of {@code app/jcl/CREASTMT.JCL:L87-L91} -
         * {@code DISP=(NEW,CATLG,DELETE)} - by discarding the statements this run wrote.
         *
         * @return {@link FileStatus.Outcome#OK} when the generation was discarded, when this run wrote
         *     nothing, or when the disposition had already been applied; otherwise
         *     {@link FileStatus.Outcome#OTHER}
         */
        public FileStatus.Outcome discardGeneration() {
            if (discarded || recordsWritten == 0) {
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
         * @throws NullPointerException if the sink returns a {@code null} outcome from
         *     {@link RecordSink#close()}
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
    }
}
