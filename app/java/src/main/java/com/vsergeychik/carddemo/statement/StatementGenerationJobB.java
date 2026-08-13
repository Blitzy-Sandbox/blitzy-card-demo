package com.vsergeychik.carddemo.statement;

import com.vsergeychik.carddemo.account.model.AccountRecord;
import com.vsergeychik.carddemo.card.model.CardXrefRecord;
import com.vsergeychik.carddemo.common.DatasetRelation;
import com.vsergeychik.carddemo.common.DatasetRelation.BackendDiagnostic;
import com.vsergeychik.carddemo.common.DatasetRelation.KeySpan;
import com.vsergeychik.carddemo.common.FileStatus;
import com.vsergeychik.carddemo.common.FileStatus.Outcome;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.FixedWidthRecord;
import com.vsergeychik.carddemo.common.RecordImageForm;
import com.vsergeychik.carddemo.config.CobolCharsetConfig;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBinding;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBindings;
import com.vsergeychik.carddemo.statement.model.Stm03CustomerRecord;
import com.vsergeychik.carddemo.statement.model.TrnxRecord;

import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.stereotype.Component;

/**
 * The Java form of {@code app/cbl/CBSTM03B.CBL} - the four-file, six-operation data-access subroutine that
 * {@code CBSTM03A} calls thirteen times to read every input the account statement job consumes.
 *
 * <p>All four inputs are declared here, in {@code CBSTM03B.CBL:31-53} - which is exactly why the caller has
 * to come back thirteen times.
 */
@Component
public class StatementGenerationJobB {
    private static final Log LOG = LogFactory.getLog(StatementGenerationJobB.class);

    /**
     * Total width of {@code LK-M03B-AREA} in bytes: exactly {@code 1040}.
     */
    public static final int AREA_LENGTH = 1040;

    /**
     * Zero-based offset of {@code LK-M03B-DD} within the area: {@code 0}.
     */
    public static final int DD_OFFSET = 0;

    /**
     * Declared width of {@code LK-M03B-DD PIC X(08)}: {@code 8}.
     */
    public static final int DD_LENGTH = 8;

    /**
     * Zero-based offset of {@code LK-M03B-OPER} within the area: {@code 8}.
     */
    public static final int OPER_OFFSET = DD_OFFSET + DD_LENGTH;

    /**
     * Declared width of {@code LK-M03B-OPER PIC X(01)}: {@code 1}.
     */
    public static final int OPER_LENGTH = 1;

    /**
     * Zero-based offset of {@code LK-M03B-RC} within the area: {@code 9}.
     */
    public static final int RC_OFFSET = OPER_OFFSET + OPER_LENGTH;

    /**
     * Declared width of {@code LK-M03B-RC PIC X(02)}: {@code 2}.
     */
    public static final int RC_LENGTH = FileStatus.STATUS_LENGTH;

    /**
     * Zero-based offset of {@code LK-M03B-KEY} within the area: {@code 11}.
     */
    public static final int KEY_OFFSET = RC_OFFSET + RC_LENGTH;

    /**
     * Declared width of {@code LK-M03B-KEY PIC X(25)}: {@code 25}.
     */
    public static final int KEY_LENGTH = 25;

    /**
     * Zero-based offset of {@code LK-M03B-KEY-LN} within the area: {@code 36}.
     */
    public static final int KEY_LN_OFFSET = KEY_OFFSET + KEY_LENGTH;

    /**
     * Declared width of {@code LK-M03B-KEY-LN PIC S9(4)}: {@code 4} bytes.
     */
    public static final int KEY_LN_LENGTH = 4;

    /**
     * Zero-based offset of {@code LK-M03B-FLDT} within the area: {@code 40}.
     */
    public static final int FLDT_OFFSET = KEY_LN_OFFSET + KEY_LN_LENGTH;

    /**
     * Declared width of {@code LK-M03B-FLDT PIC X(1000)}: {@code 1000}.
     */
    public static final int FLDT_LENGTH = 1000;

    // Each is exactly DD_LENGTH characters, which is what makes an exact match against LK-M03B-DD X(08)
    // meaningful.

    /**
     * {@code 'TRNXFILE'} - the sorted transaction extract, {@code CBSTM03B.CBL:31-35} and {@code :119}.
     */
    public static final String TRNXFILE_DD = "TRNXFILE";

    /**
     * {@code 'XREFFILE'} - the card cross reference, {@code CBSTM03B.CBL:37-41} and {@code :121}.
     */
    public static final String XREFFILE_DD = "XREFFILE";

    /**
     * {@code 'CUSTFILE'} - the customer master, {@code CBSTM03B.CBL:43-47} and {@code :123}.
     */
    public static final String CUSTFILE_DD = "CUSTFILE";

    /**
     * {@code 'ACCTFILE'} - the account master, {@code CBSTM03B.CBL:49-53} and {@code :125}.
     */
    public static final String ACCTFILE_DD = "ACCTFILE";

    /**
     * The four DD names in the exact order {@code EVALUATE LK-M03B-DD} tests them
     * ({@code CBSTM03B.CBL:118-126}).
     */
    public static final List<String> DD_NAMES =
            List.of(TRNXFILE_DD, XREFFILE_DD, CUSTFILE_DD, ACCTFILE_DD);

    // Every width is taken from the RECORD_LENGTH or key constant of the model type that owns the copybook,
    // never written as a literal here, so a disagreement between this class and a copybook cannot exist:
    // there is only one number.

    public static final int TRNXFILE_RECORD_LENGTH = TrnxRecord.RECORD_LENGTH;

    public static final int TRNXFILE_KEY_LENGTH = TrnxRecord.TRNX_KEY_LENGTH;

    /**
     * The {@link #TRNXFILE_DD} record's data span after its key: {@code 318} bytes.
     */
    public static final int TRNXFILE_ACCT_DATA_LENGTH = TrnxRecord.TRNX_REST_LENGTH;

    public static final int XREFFILE_RECORD_LENGTH = CardXrefRecord.RECORD_LENGTH;

    public static final int XREFFILE_KEY_LENGTH = CardXrefRecord.XREF_CARD_NUM_LENGTH;

    /**
     * The {@link #XREFFILE_DD} record's data span after its key: {@code 34} bytes, {@code CBSTM03B.CBL:68}.
     */
    public static final int XREFFILE_DATA_LENGTH = XREFFILE_RECORD_LENGTH - XREFFILE_KEY_LENGTH;

    public static final int CUSTFILE_RECORD_LENGTH = Stm03CustomerRecord.RECORD_LENGTH;

    public static final int CUSTFILE_KEY_LENGTH = Stm03CustomerRecord.KEY_LENGTH;

    /**
     * The {@link #CUSTFILE_DD} record's data span after its key: {@code 491} bytes,
     * {@code CBSTM03B.CBL:73}.
     */
    public static final int CUSTFILE_DATA_LENGTH = CUSTFILE_RECORD_LENGTH - CUSTFILE_KEY_LENGTH;

    public static final int ACCTFILE_RECORD_LENGTH = AccountRecord.RECORD_LENGTH;

    public static final int ACCTFILE_KEY_LENGTH = AccountRecord.ACCT_ID_LENGTH;

    /**
     * The {@link #ACCTFILE_DD} record's data span after its key: {@code 289} bytes.
     */
    public static final int ACCTFILE_ACCT_DATA_LENGTH = ACCTFILE_RECORD_LENGTH - ACCTFILE_KEY_LENGTH;

    /**
     * The key length the caller supplies for a {@link #CUSTFILE_DD} read: {@code 9}.
     */
    public static final int CUSTFILE_CALLER_KEY_LENGTH = CardXrefRecord.XREF_CUST_ID_LENGTH;

    /**
     * The key length the caller supplies for an {@link #ACCTFILE_DD} read: {@code 11}.
     */
    public static final int ACCTFILE_CALLER_KEY_LENGTH = CardXrefRecord.XREF_ACCT_ID_LENGTH;

    /**
     * The content of a {@code FILE STATUS} area that no operation has touched yet: two spaces.
     */
    public static final String UNTOUCHED_STATUS = "  ";

    /**
     * {@code '41'} - an {@code OPEN} was attempted for a file already in the open mode.
     */
    public static final String ALREADY_OPEN_STATUS = "41";

    /**
     * {@code '42'} - a {@code CLOSE} was attempted for a file not in the open mode.
     */
    public static final String NOT_OPEN_STATUS = "42";

    /**
     * {@code '47'} - a {@code READ} was attempted for a file not open in the input or I-O mode.
     *
     * <p>Standard COBOL file status, and the correct one here because every {@code OPEN} in this subroutine
     * is {@code OPEN INPUT}: a read before the open, or after the close, is exactly the condition
     * {@code '47'} names.
     */
    public static final String NOT_OPEN_FOR_READ_STATUS = "47";

    /**
     * The status reported when the backend refuses an operation: {@code '9'} followed by an
     * implementor-defined byte of binary zero.
     */
    public static final String PERMANENT_ERROR_STATUS = "9" + (char) 0;

    /**
     * An empty {@code LK-M03B-FLDT}: {@value #FLDT_LENGTH} spaces.
     */
    public static final String SPACES_FLDT = " ".repeat(FLDT_LENGTH);

    /**
     * The {@code LK-M03B-OPER} byte rendered for an operation matching none of the six {@code 88}-levels: a
     * single space.
     */
    public static final String UNRECOGNISED_OPER_IMAGE = " ";

    private static final int BROWSE_ROW_LIMIT = 1;

    private static final int KEYED_READ_ROW_LIMIT = 2;

    private static final int UNREADABLE_ROW_PROBE_LIMIT = 1;

    static {
        int declared = DD_LENGTH + OPER_LENGTH + RC_LENGTH + KEY_LENGTH + KEY_LN_LENGTH + FLDT_LENGTH;
        if (declared != AREA_LENGTH) {
            throw new IllegalStateException("The LK-M03B-AREA field widths sum to " + declared
                    + " but AREA_LENGTH is " + AREA_LENGTH + "; app/cbl/CBSTM03B.CBL:100-112 declares "
                    + "X(08) + X(01) + X(02) + X(25) + S9(4) DISPLAY + X(1000) = 1040 bytes, and the "
                    + "caller's own 01 WS-M03B-AREA (app/cbl/CBSTM03A.CBL:71-84) is the identical "
                    + "group. Correct the field constants rather than AREA_LENGTH.");
        }
        if (FLDT_OFFSET + FLDT_LENGTH != AREA_LENGTH) {
            throw new IllegalStateException("LK-M03B-FLDT at offset " + FLDT_OFFSET + " for "
                    + FLDT_LENGTH + " bytes does not end at " + AREA_LENGTH + "; the field offsets are "
                    + "cumulative and must close the area exactly.");
        }
    }

    /**
     * The six operation codes {@code LK-M03B-OPER} can carry - the {@code 88}-level condition names of
     * {@code app/cbl/CBSTM03B.CBL:103-108}.
     */
    public enum Operation {
        OPEN('O'),

        CLOSE('C'),

        READ('R'),

        /**
         * {@code 88 M03B-READ-K VALUE 'K'} - a keyed {@code READ}, honoured by the two RANDOM files.
         */
        READ_K('K'),

        /**
         * {@code 88 M03B-WRITE VALUE 'W'} - declared at {@code CBSTM03B.CBL:107} and tested nowhere.
         */
        WRITE('W'),

        /**
         * {@code 88 M03B-REWRITE VALUE 'Z'} - declared at {@code CBSTM03B.CBL:108} and tested nowhere.
         */
        REWRITE('Z');

        private final char code;

        Operation(char code) {
            this.code = code;
        }

        /**
         * The one-character code this operation is spelled with in the linkage area.
         *
         * @return the {@code VALUE} literal of the corresponding {@code 88}-level
         */
        public char code() {
            return code;
        }

        /**
         * The code as a one-character {@code LK-M03B-OPER} image.
         *
         * @return a string of exactly {@value StatementGenerationJobB#OPER_LENGTH} character
         */
        public String image() {
            return String.valueOf(code);
        }

        /**
         * Whether this operation is one of the two the source declares but never tests.
         *
         * @return {@code true} for {@link #WRITE} and {@link #REWRITE}, {@code false} otherwise
         */
        public boolean declaredButUnusedInSource() {
            return this == WRITE || this == REWRITE;
        }

        /**
         * Resolves an operation from its one-character code.
         *
         * @param code the character to resolve
         * @return the matching operation, or empty when no {@code 88}-level names {@code code}
         */
        public static Optional<Operation> ofCode(char code) {
            for (Operation candidate : values()) {
                if (candidate.code == code) {
                    return Optional.of(candidate);
                }
            }
            return Optional.empty();
        }
    }

    /**
     * The inbound projection of {@code LK-M03B-AREA} - one {@code CALL 'CBSTM03B' USING WS-M03B-AREA} worth
     * of input.
     *
     * @param dd {@code LK-M03B-DD PIC X(08)} - the DD name to dispatch on
     * @param oper {@code LK-M03B-OPER PIC X(01)} - the operation, or {@code null} to mean a byte matching
     *     none of the six {@code 88}-levels, which is a fall-through and not an error
     * @param rc {@code LK-M03B-RC PIC X(02)} on the way in - the status the caller left in the area, which
     *     is what the {@code WHEN OTHER} arm returns untouched
     * @param key {@code LK-M03B-KEY PIC X(25)} - the key, left-justified and space-padded exactly as
     *     {@code MOVE XREF-CUST-ID TO WS-M03B-KEY} leaves it
     * @param keyLength {@code LK-M03B-KEY-LN PIC S9(4)} - how many of those 25 bytes the key occupies
     * @param fldt {@code LK-M03B-FLDT PIC X(1000)} on the way in - the record area as the caller left it,
     *     which a read that finds nothing returns unchanged
     */
    public record Request(String dd,
                          Operation oper,
                          String rc,
                          String key,
                          int keyLength,
                          String fldt) {
        /**
         * Normalises and validates the fixed-width fields.
         */
        public Request {
            Objects.requireNonNull(dd, "A DD name is required: LK-M03B-DD is the field "
                    + "EVALUATE LK-M03B-DD dispatches on (app/cbl/CBSTM03B.CBL:118)");
            Objects.requireNonNull(rc, "An inbound status is required: LK-M03B-RC arrives carrying "
                    + "what the caller left there, and the WHEN OTHER arm returns exactly that");
            Objects.requireNonNull(key, "A key image is required: LK-M03B-KEY is a fixed X(25) span, "
                    + "so an absent key is " + KEY_LENGTH + " spaces rather than null");
            Objects.requireNonNull(fldt, "A record area is required: LK-M03B-FLDT is a fixed X("
                    + FLDT_LENGTH + ") span, and a read that finds nothing returns it unchanged");
            if (rc.length() != RC_LENGTH) {
                throw new IllegalArgumentException("An inbound status of '" + rc.length()
                        + "' character(s) cannot occupy LK-M03B-RC PIC X(" + RC_LENGTH + "); a COBOL "
                        + "FILE STATUS is exactly two characters and is never padded into place.");
            }
            if (fldt.length() != FLDT_LENGTH) {
                throw new IllegalArgumentException("A record area of " + fldt.length()
                        + " character(s) cannot occupy LK-M03B-FLDT PIC X(" + FLDT_LENGTH + "). Use "
                        + "StatementGenerationJobB.SPACES_FLDT for the empty area every call site "
                        + "establishes with MOVE SPACES TO WS-M03B-FLDT.");
            }
            // PIC X receivers: right-padded when short, right-truncated when long. Applied through the same
            // rule the codec states for the whole module, spelled out here because Request is constructible
            // without a codec.
            dd = fitPicX(dd, DD_LENGTH);
            key = fitPicX(key, KEY_LENGTH);
        }

        /**
         * Applies the COBOL {@code PIC X} move rule: pad right with spaces, truncate on the right.
         *
         * @param value the sending value
         * @param declaredWidth the receiver's declared width
         * @return an image of exactly {@code declaredWidth} characters
         */
        private static String fitPicX(String value, int declaredWidth) {
            if (value.length() == declaredWidth) {
                return value;
            }
            return value.length() > declaredWidth
                    ? value.substring(0, declaredWidth)
                    : value + " ".repeat(declaredWidth - value.length());
        }

        /**
         * An {@code OPEN INPUT} request, as {@code app/cbl/CBSTM03A.CBL:731-734} composes it:
         * {@code MOVE ' ' TO WS-M03B-DD}, {@code SET M03B-OPEN TO TRUE}, {@code MOVE ZERO TO WS-M03B-RC}.
         *
         * @param dd the DD name to open
         * @return the request
         * @throws NullPointerException if {@code dd} is {@code null}
         */
        public static Request open(String dd) {
            return new Request(dd, Operation.OPEN, FileStatus.OK, blankKey(), 0, SPACES_FLDT);
        }

        /**
         * A sequential {@code READ} request, as {@code app/cbl/CBSTM03A.CBL:346-350} composes it - with the
         * {@code MOVE ZERO TO WS-M03B-RC} and {@code MOVE SPACES TO WS-M03B-FLDT} the caller performs first
         * already applied.
         *
         * @param dd the DD name to read
         * @return the request
         * @throws NullPointerException if {@code dd} is {@code null}
         */
        public static Request read(String dd) {
            return new Request(dd, Operation.READ, FileStatus.OK, blankKey(), 0, SPACES_FLDT);
        }

        /**
         * A keyed {@code READ} request, as {@code app/cbl/CBSTM03A.CBL:367-376} composes it:
         * {@code MOVE XREF-CUST-ID TO WS-M03B-KEY} then
         * {@code COMPUTE WS-M03B-KEY-LN = LENGTH OF XREF-CUST-ID}.
         *
         * <p>{@code key} is fitted to the declared {@value StatementGenerationJobB#KEY_LENGTH}-character
         * span by the canonical constructor, which is exactly what the caller's
         * {@code MOVE <9- or 11-digit field> TO WS-M03B-KEY} does: left-justify and pad with spaces.
         *
         * @param dd the DD name to read
         * @param key the key value; shorter than the span is normal and is padded
         * @param keyLength how many bytes of the span the key occupies
         * @return the request
         * @throws NullPointerException if {@code dd} or {@code key} is {@code null}
         */
        public static Request readKeyed(String dd, String key, int keyLength) {
            return new Request(dd, Operation.READ_K, FileStatus.OK, key, keyLength, SPACES_FLDT);
        }

        /**
         * A {@code CLOSE} request, as {@code app/cbl/CBSTM03A.CBL:857-860} composes it.
         *
         * @param dd the DD name to close
         * @return the request
         * @throws NullPointerException if {@code dd} is {@code null}
         */
        public static Request close(String dd) {
            return new Request(dd, Operation.CLOSE, FileStatus.OK, blankKey(), 0, SPACES_FLDT);
        }

        /**
         * A request carrying an operation code that matches none of the six {@code 88}-levels.
         *
         * @param dd the DD name to dispatch on
         * @return the request, with a {@code null} operation
         * @throws NullPointerException if {@code dd} is {@code null}
         */
        public static Request unrecognisedOperation(String dd) {
            return new Request(dd, null, FileStatus.OK, blankKey(), 0, SPACES_FLDT);
        }

        /**
         * An empty {@code LK-M03B-KEY}: {@value StatementGenerationJobB#KEY_LENGTH} spaces.
         *
         * @return the blank key image
         */
        public static String blankKey() {
            return " ".repeat(KEY_LENGTH);
        }

        /**
         * The {@code keyLength}-byte prefix of the key - COBOL's {@code LK-M03B-KEY (1:LK-M03B-KEY-LN)}
         * reference modification ({@code app/cbl/CBSTM03B.CBL:189} and {@code :214}).
         *
         * @return the used prefix of the key, exactly {@link #keyLength()} characters
         * @throws IllegalArgumentException if {@code keyLength} is not between 1 and
         *     {@value StatementGenerationJobB#KEY_LENGTH} inclusive
         */
        public String usedKey() {
            if (keyLength < 1 || keyLength > KEY_LENGTH) {
                throw new IllegalArgumentException("A key length of " + keyLength + " cannot address "
                        + "LK-M03B-KEY (1:" + keyLength + "): the span is " + KEY_LENGTH + " bytes and "
                        + "COBOL reference modification is 1-based, so the length must be between 1 and "
                        + KEY_LENGTH + ". app/cbl/CBSTM03A.CBL:374 and :398 always set it from "
                        + "LENGTH OF XREF-CUST-ID (" + CUSTFILE_CALLER_KEY_LENGTH + ") or "
                        + "LENGTH OF XREF-ACCT-ID (" + ACCTFILE_CALLER_KEY_LENGTH + ").");
            }
            return key.substring(0, keyLength);
        }
    }

    /**
     * The outbound projection of {@code LK-M03B-AREA} - the two fields one call can leave behind.
     *
     * @param rc {@code LK-M03B-RC PIC X(02)} - exactly {@value StatementGenerationJobB#RC_LENGTH}
     *     characters
     * @param fldt {@code LK-M03B-FLDT PIC X(1000)} - always exactly
     *     {@value StatementGenerationJobB#FLDT_LENGTH} characters, with any record left-justified and the
     *     remainder spaces
     */
    public record Response(String rc, String fldt) {
        public Response {
            Objects.requireNonNull(rc, "A status is required: every return from a matched DD runs "
                    + "MOVE <dd>FILE-STATUS TO LK-M03B-RC (app/cbl/CBSTM03B.CBL:152, :176, :201, :226)");
            Objects.requireNonNull(fldt, "A record area is required: LK-M03B-FLDT is a fixed X("
                    + FLDT_LENGTH + ") span and is returned even when no record was read");
            if (rc.length() != RC_LENGTH) {
                throw new IllegalArgumentException("A status of " + rc.length() + " character(s) "
                        + "cannot occupy LK-M03B-RC PIC X(" + RC_LENGTH + ").");
            }
            if (fldt.length() != FLDT_LENGTH) {
                throw new IllegalArgumentException("A record area of " + fldt.length()
                        + " character(s) cannot occupy LK-M03B-FLDT PIC X(" + FLDT_LENGTH + ").");
            }
        }

        /**
         * The status classified into the outcomes the callers' {@code EVALUATE WS-M03B-RC} arms enumerate.
         *
         * @return {@link Outcome#OK} for {@code '00'}, {@link Outcome#END_OF_FILE} for {@code '10'},
         *     {@link Outcome#NOT_FOUND} for {@code '23'}, {@link Outcome#DUPLICATE} for {@code '22'}
         */
        public Outcome outcome() {
            return FileStatus.outcomeOfStatus(rc);
        }

        public boolean ok() {
            return FileStatus.isOk(rc);
        }

        /**
         * Whether the operation reported {@code '10'} - the {@code AT END} the two sequential browses reach
         * and the arm {@code app/cbl/CBSTM03A.CBL:356} turns into {@code MOVE 'Y' TO END-OF-FILE}.
         *
         * @return {@code true} for {@link FileStatus#END_OF_FILE}
         */
        public boolean endOfFile() {
            return FileStatus.isEndOfFile(rc);
        }

        /**
         * The record the caller would move out of {@code LK-M03B-FLDT}: its leading {@code recordLength}
         * characters.
         *
         * @param recordLength the receiving record's declared width
         * @return the leading {@code recordLength} characters of the record area
         * @throws IllegalArgumentException if {@code recordLength} is not between 1 and
         *     {@value StatementGenerationJobB#FLDT_LENGTH} inclusive
         */
        public String recordImage(int recordLength) {
            if (recordLength < 1 || recordLength > FLDT_LENGTH) {
                throw new IllegalArgumentException("A record length of " + recordLength + " cannot be "
                        + "taken from LK-M03B-FLDT PIC X(" + FLDT_LENGTH + "); a receiving record is at "
                        + "least 1 byte and no wider than the area that carries it.");
            }
            return fldt.substring(0, recordLength);
        }
    }

    private final JdbcTemplate jdbcTemplate;

    private final FixedWidthCodec codec;

    private final RecordImageForm recordImageForm;

    private final Map<String, DatasetAccess> datasets;

    /**
     * Assembles the subroutine from the module's shared {@link JdbcTemplate}, the DD-name-keyed dataset
     * catalogue and the explicitly named dataset code page.
     *
     * @param jdbcTemplate the module-wide template; never {@code null}
     * @param datasetBindings the {@code carddemo.datasets} catalogue; never {@code null}
     * @param datasetCharset the code page the dataset holds its record images in, from
     *     {@code carddemo.charset.dataset}
     * @param recordImageForm how the deployment's driver presents a record image over JDBC
     * @throws NullPointerException if any argument is {@code null}
     * @throws IllegalStateException if any of the four bindings is absent, disagrees with its copybook
     *     about the record width or the key width, or declares no usable dataset name
     */
    public StatementGenerationJobB(
            JdbcTemplate jdbcTemplate,
            DatasetBindings datasetBindings,
            @Qualifier(CobolCharsetConfig.DATASET_CHARSET_BEAN_NAME) Charset datasetCharset,
            RecordImageForm recordImageForm,
            TrnxRepository trnxRepository) {

        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "A JdbcTemplate is required: the "
                + "four statement inputs are reached through the module's shared template over the "
                + "configuration-bound DataSource");
        Objects.requireNonNull(datasetBindings, "The carddemo.datasets binding catalogue is required: "
                + "dataset names live in configuration and are never written in Java (gate G46)");
        this.codec = new FixedWidthCodec(Objects.requireNonNull(datasetCharset, "A dataset charset is "
                + "required: a fixed-width mainframe record is bytes in a specific code page, so the "
                + "code page is stated explicitly and never taken from the platform (practice B8)"));
        this.recordImageForm = Objects.requireNonNull(recordImageForm, "A record-image representation "
                + "is required: whether this deployment's driver presents a record image as characters "
                + "or as bytes is stated once, by " + RecordImageForm.FORM_PROPERTY + ", and never "
                + "decided per collaborator");
        RecordImageForm.requireSingleByteCodePage(datasetCharset);

        requireCallerKeyWidth(CUSTFILE_DD, CardXrefRecord.XREF_CUST_ID_NAME,
                CUSTFILE_CALLER_KEY_LENGTH, CUSTFILE_KEY_LENGTH);
        requireCallerKeyWidth(ACCTFILE_DD, CardXrefRecord.XREF_ACCT_ID_NAME,
                ACCTFILE_CALLER_KEY_LENGTH, ACCTFILE_KEY_LENGTH);

        Map<String, DatasetAccess> resolved = new LinkedHashMap<>();
        // TRNXFILE's identity and geometry belong to TrnxRepository, the @Repository that owns this
        // dataset (gate G10). It resolved and validated the binding in its own constructor, so what is
        // taken from it here is the resolved relation and key span - and this class no longer looks
        // carddemo.datasets.TRNXFILE up at all. The capability set stays here, because it is not a
        // property of the dataset: it is what CBSTM03B's 1000-TRNXFILE-PROC paragraph implements.
        Objects.requireNonNull(trnxRepository, "A TrnxRepository is required: it owns the TRNXFILE "
                + "dataset's identity, its " + TRNXFILE_RECORD_LENGTH + "-byte geometry and the "
                + "relation this component's statements over it are composed from (gate G10)");
        resolved.put(TRNXFILE_DD, new DatasetAccess(TRNXFILE_DD, trnxRepository.relation(),
                trnxRepository.recordLength(), trnxRepository.keySpan(), KeyPicture.ALPHANUMERIC,
                SEQUENTIAL_OPERATIONS));
        resolved.put(XREFFILE_DD, access(datasetBindings, XREFFILE_DD, XREFFILE_RECORD_LENGTH,
                XREFFILE_KEY_LENGTH, CardXrefRecord.XREF_CARD_NUM_OFFSET, KeyPicture.ALPHANUMERIC,
                SEQUENTIAL_OPERATIONS, "app/cpy/CVACT03Y.cpy"));
        resolved.put(CUSTFILE_DD, access(datasetBindings, CUSTFILE_DD, CUSTFILE_RECORD_LENGTH,
                CUSTFILE_KEY_LENGTH, Stm03CustomerRecord.KEY_OFFSET, KeyPicture.ALPHANUMERIC,
                RANDOM_OPERATIONS, "app/cpy/CUSTREC.cpy"));
        resolved.put(ACCTFILE_DD, access(datasetBindings, ACCTFILE_DD, ACCTFILE_RECORD_LENGTH,
                ACCTFILE_KEY_LENGTH, AccountRecord.ACCT_ID_OFFSET, KeyPicture.NUMERIC_DISPLAY,
                RANDOM_OPERATIONS, "app/cpy/CVACT01Y.cpy"));
        this.datasets = Collections.unmodifiableMap(resolved);
    }

    private static final Set<Operation> SEQUENTIAL_OPERATIONS =
            Collections.unmodifiableSet(EnumSet.of(Operation.OPEN, Operation.READ, Operation.CLOSE));

    private static final Set<Operation> RANDOM_OPERATIONS =
            Collections.unmodifiableSet(EnumSet.of(Operation.OPEN, Operation.READ_K, Operation.CLOSE));

    /**
     * Which of the two {@code RECORD KEY} pictures a file declares, because the two take different
     * {@code MOVE} rules.
     */
    private enum KeyPicture {
        /**
         * {@code PIC X(n)} - {@link FixedWidthCodec#movePicX(String, int)}.
         */
        ALPHANUMERIC,

        NUMERIC_DISPLAY
    }

    private DatasetAccess access(DatasetBindings bindings,
                                 String ddName,
                                 int recordLength,
                                 int keyLength,
                                 int keyOffset,
                                 KeyPicture keyPicture,
                                 Set<Operation> capabilities,
                                 String copybook) {
        DatasetBinding binding = bindings.binding(ddName);
        requireCopybookRecordLength(ddName, binding, recordLength, copybook);
        requireDeclaredKeyLength(ddName, binding, keyLength, copybook);
        return new DatasetAccess(
                ddName,
                DatasetRelation.of(requireUsableDatasetName(ddName, binding.dsname()), recordLength),
                recordLength,
                new KeySpan(keyOffset, keyLength),
                keyPicture,
                capabilities);
    }

    /**
     * Requires a binding to agree with its copybook about the record width.
     *
     * @param ddName the configuration key, for the diagnostic
     * @param binding the configured binding
     * @param declared the width the copybook declares
     * @param copybook the copybook path
     * @throws IllegalStateException if the declared record length differs
     */
    private static void requireCopybookRecordLength(String ddName, DatasetBinding binding,
                                                    int declared, String copybook) {
        if (binding.recordLength() != declared) {
            throw new IllegalStateException("Dataset binding for '" + ddName + "' declares a record "
                    + "length of " + binding.recordLength() + ", but " + copybook + " declares "
                    + declared + " and app/cbl/CBSTM03B.CBL:58-78 splits its FD record to exactly that "
                    + "width. This subroutine returns raw record bytes in an X(" + FLDT_LENGTH + ") "
                    + "area, so a wrong width does not fail here - it displaces every field the caller "
                    + "then decodes. Correct carddemo.datasets." + ddName + ".record-length to "
                    + declared + ".");
        }
    }

    private static void requireDeclaredKeyLength(String ddName, DatasetBinding binding,
                                                 int declared, String copybook) {
        Integer configured = binding.keyLength();
        if (configured == null) {
            throw new IllegalStateException("Dataset binding for '" + ddName + "' declares no "
                    + "key-length, but app/cbl/CBSTM03B.CBL:31-53 declares it ORGANIZATION IS INDEXED "
                    + "with a RECORD KEY, and " + copybook + " gives that key a width of " + declared
                    + ". Set carddemo.datasets." + ddName + ".key-length to " + declared + ".");
        }
        if (configured != declared) {
            throw new IllegalStateException("Dataset binding for '" + ddName + "' declares key-length "
                    + configured + ", but the RECORD KEY of app/cbl/CBSTM03B.CBL is " + declared
                    + " bytes wide per " + copybook + ". A keyed read composed from the wrong width "
                    + "would compare a short key as a prefix and match records the key does not name. "
                    + "Correct carddemo.datasets." + ddName + ".key-length.");
        }
        if (binding.keyOffsetOrZero() != 0) {
            throw new IllegalStateException("Dataset binding for '" + ddName + "' declares key-offset "
                    + binding.keyOffsetOrZero() + ", but every RECORD KEY in app/cbl/CBSTM03B.CBL:58-78 "
                    + "is the first field of its FD record, so the key begins at offset 0. Remove "
                    + "carddemo.datasets." + ddName + ".key-offset.");
        }
    }

    private static void requireCallerKeyWidth(String ddName, String callerField,
                                              int callerWidth, int declaredWidth) {
        if (callerWidth != declaredWidth) {
            throw new IllegalStateException("The key width app/cbl/CBSTM03A.CBL computes for '" + ddName
                    + "' is LENGTH OF " + callerField + " = " + callerWidth + ", but "
                    + "app/cbl/CBSTM03B.CBL declares its RECORD KEY " + declaredWidth + " bytes wide. "
                    + "app/cpy/CVACT03Y.cpy and the file's own copybook must agree about how wide that "
                    + "identifier is, because the caller's COMPUTE WS-M03B-KEY-LN is what the keyed "
                    + "read's reference modification uses.");
        }
    }

    private static String requireUsableDatasetName(String ddName, String candidate) {
        if (candidate == null || candidate.isBlank()) {
            throw new IllegalStateException("The dataset binding for '" + ddName + "' declares no "
                    + "dataset name. Set carddemo.datasets." + ddName + ".dsname; this class composes "
                    + "its statements from configuration alone and hard-codes no dataset name (gate "
                    + "G46).");
        }
        return DatasetRelation.requireDatasetName(candidate);
    }

    /**
     * The resolved dataset name behind a DD name, exactly as configuration declares it.
     *
     * @param dd one of {@link #TRNXFILE_DD}, {@link #XREFFILE_DD}, {@link #CUSTFILE_DD},
     *     {@link #ACCTFILE_DD}
     * @return the configured dataset name; never {@code null}, never blank
     * @throws IllegalArgumentException if {@code dd} is not one of the four
     */
    public String datasetName(String dd) {
        return require(dd).relation.dsname();
    }

    /**
     * The record width of the dataset behind a DD name, from its copybook.
     *
     * @param dd one of the four DD names
     * @return {@code 350}, {@code 50}, {@code 500} or {@code 300}
     * @throws IllegalArgumentException if {@code dd} is not one of the four
     */
    public int recordLength(String dd) {
        return require(dd).recordLength;
    }

    /**
     * The operations a DD actually honours - the three {@code IF <op>} tests its {@code PROC} paragraph
     * performs.
     *
     * @param dd one of the four DD names
     * @return the unmodifiable set of honoured operations
     * @throws IllegalArgumentException if {@code dd} is not one of the four
     */
    public Set<Operation> supportedOperations(String dd) {
        return require(dd).capabilities;
    }

    /**
     * Whether a DD name is one of the four {@code EVALUATE LK-M03B-DD} recognises.
     *
     * <p>Comparison is exact and unpadded on the caller's side: a {@link Request} has already fitted its DD
     * name to {@value #DD_LENGTH} characters, and all four names are exactly that wide, so an exact match
     * here is the same comparison COBOL makes against {@code PIC X(08)}.
     *
     * @param dd the DD name to test; {@code null} answers {@code false}
     * @return {@code true} for the four recognised names
     */
    public boolean recognises(String dd) {
        return dd != null && datasets.containsKey(dd);
    }

    private DatasetAccess require(String dd) {
        DatasetAccess access = dd == null ? null : datasets.get(dd);
        if (access == null) {
            throw new IllegalArgumentException("'" + dd + "' is not one of the four DD names "
                    + "app/cbl/CBSTM03B.CBL:118-126 dispatches on " + DD_NAMES + ". This accessor "
                    + "requires a known DD; to exercise the WHEN OTHER arm, call "
                    + "call(Session, Request) with the unrecognised name - that path is a no-op that "
                    + "returns the request's own status untouched.");
        }
        return access;
    }

    /**
     * Opens a fresh execution session - four closed cursors with four untouched {@code FILE STATUS} areas.
     *
     * @return a new session; never {@code null}
     */
    public Session newSession() {
        Map<String, Cursor> cursors = new LinkedHashMap<>();
        for (String dd : DD_NAMES) {
            cursors.put(dd, new Cursor(datasets.get(dd)));
        }
        return new Session(cursors);
    }

    /**
     * The single generic entry point: the Java form of {@code PROCEDURE DIVISION USING LK-M03B-AREA} and of
     * {@code 0000-START} ({@code app/cbl/CBSTM03B.CBL:114-131}).
     *
     * <p>{@code GO TO 9999-GOBACK} skips every {@code nnn900-EXIT}, so no
     * {@code MOVE FILE-STATUS TO LK-M03B-RC} runs, no file is touched, and {@code LK-M03B-RC} and
     * {@code LK-M03B-FLDT} still hold exactly what the caller left there.
     *
     * @param session the execution session holding the four cursors and their statuses
     * @param request the inbound projection of {@code LK-M03B-AREA}
     * @return the outbound projection: the status and the record area this call leaves behind
     * @throws NullPointerException if {@code session} or {@code request} is {@code null}
     * @throws IllegalStateException if {@code session} has been closed
     */
    public Response call(Session session, Request request) {
        Objects.requireNonNull(session, "A session is required: the four cursors and their FILE STATUS "
                + "areas live on it, because app/cbl/CBSTM03B.CBL keeps them across all thirteen calls");
        Objects.requireNonNull(request, "A request is required: it is the inbound projection of "
                + "LK-M03B-AREA");
        session.requireUsable();

        String dd = request.dd();
        if (TRNXFILE_DD.equals(dd)) {
            return trnxFileProc(session, request);
        }
        if (XREFFILE_DD.equals(dd)) {
            return xrefFileProc(session, request);
        }
        if (CUSTFILE_DD.equals(dd)) {
            return custFileProc(session, request);
        }
        if (ACCTFILE_DD.equals(dd)) {
            return acctFileProc(session, request);
        }
        return new Response(request.rc(), request.fldt());
    }

    /**
     * {@code 1000-TRNXFILE-PROC THRU 1999-EXIT} - {@code app/cbl/CBSTM03B.CBL:133-155}.
     *
     * @param session the open dataset session this operation runs against
     * @param request the request; its DD name is not re-checked, so this may be called directly
     * @return the status and record area this operation leaves behind
     * @throws NullPointerException if either argument is {@code null}
     * @throws IllegalStateException if {@code session} has been closed
     */
    public Response trnxFileProc(Session session, Request request) {
        return proc(session, request, TRNXFILE_DD);
    }

    /**
     * {@code 2000-XREFFILE-PROC THRU 2999-EXIT} - {@code app/cbl/CBSTM03B.CBL:157-179}.
     *
     * @param session the open dataset session this operation runs against
     * @param request the operation request - DD name, operation code, key and key length
     * @return the status and record area this operation leaves behind
     * @throws NullPointerException if either argument is {@code null}
     * @throws IllegalStateException if {@code session} has been closed
     */
    public Response xrefFileProc(Session session, Request request) {
        return proc(session, request, XREFFILE_DD);
    }

    /**
     * {@code 3000-CUSTFILE-PROC THRU 3999-EXIT} - {@code app/cbl/CBSTM03B.CBL:181-204}.
     *
     * @param session the open dataset session this operation runs against
     * @param request the request; a keyed read reads {@link Request#usedKey()}
     * @return the status and record area this operation leaves behind
     * @throws NullPointerException if either argument is {@code null}
     * @throws IllegalStateException if {@code session} has been closed
     * @throws IllegalArgumentException on a keyed read whose {@link Request#keyLength()} is outside
     *     {@code 1..}{@value #KEY_LENGTH}
     */
    public Response custFileProc(Session session, Request request) {
        return proc(session, request, CUSTFILE_DD);
    }

    /**
     * {@code 4000-ACCTFILE-PROC THRU 4999-EXIT} - {@code app/cbl/CBSTM03B.CBL:206-229}.
     *
     * @param session the open dataset session this operation runs against
     * @param request the request; a keyed read reads {@link Request#usedKey()}
     * @return the status and record area this operation leaves behind
     * @throws NullPointerException if either argument is {@code null}
     * @throws IllegalStateException if {@code session} has been closed
     * @throws IllegalArgumentException on a keyed read whose {@link Request#keyLength()} is outside
     *     {@code 1..}{@value #KEY_LENGTH}, or whose used key is not all digits - {@code FD-ACCT-ID} is a
     *     numeric picture
     */
    public Response acctFileProc(Session session, Request request) {
        return proc(session, request, ACCTFILE_DD);
    }

    private Response proc(Session session, Request request, String dd) {
        Objects.requireNonNull(session, "A session is required");
        Objects.requireNonNull(request, "A request is required");
        session.requireUsable();
        Cursor cursor = session.cursor(dd);
        Operation oper = request.oper();
        String fldt = request.fldt();

        if (oper == Operation.OPEN) {
            cursor.openInput();
            return exit(cursor, fldt);
        }
        if (oper == Operation.READ && cursor.access.capabilities.contains(Operation.READ)) {
            fldt = cursor.readNext(fldt);
            return exit(cursor, fldt);
        }
        if (oper == Operation.READ_K && cursor.access.capabilities.contains(Operation.READ_K)) {
            fldt = cursor.readByKey(request, fldt);
            return exit(cursor, fldt);
        }
        if (oper == Operation.CLOSE) {
            cursor.close();
            return exit(cursor, fldt);
        }
        // So this is a SILENT NO-OP that returns the file's LAST-KNOWN FILE STATUS, stale and unchanged,
        // and the record area exactly as it arrived. DO NOT "harden" this into an exception or a fresh
        // error status.
        return exit(cursor, fldt);
    }

    private static Response exit(Cursor cursor, String fldt) {
        return new Response(cursor.status(), fldt);
    }

    /**
     * {@code OPEN INPUT} - {@code SET M03B-OPEN TO TRUE} then {@code CALL 'CBSTM03B' USING WS-M03B-AREA},
     * as {@code app/cbl/CBSTM03A.CBL:731-734} does it.
     *
     * @param session the open dataset session this operation runs against
     * @param dd the DD name to open
     * @return the status the open reported, with the record area untouched
     * @throws NullPointerException if {@code session} or {@code dd} is {@code null}
     * @throws IllegalStateException if {@code session} has been closed
     */
    public Response open(Session session, String dd) {
        return call(session, Request.open(dd));
    }

    /**
     * A sequential {@code READ ... INTO LK-M03B-FLDT}, as {@code app/cbl/CBSTM03A.CBL:346-351} does it.
     *
     * @param session the open dataset session this operation runs against
     * @param dd the DD name to read
     * @return {@code '00'} and a record, {@code '10'} at end of file with the record area unchanged, or a
     *     reported failure
     * @throws NullPointerException if {@code session} or {@code dd} is {@code null}
     * @throws IllegalStateException if {@code session} has been closed
     */
    public Response readNext(Session session, String dd) {
        return call(session, Request.read(dd));
    }

    /**
     * A keyed {@code READ}, as {@code app/cbl/CBSTM03A.CBL:367-377} and {@code :391-401} do it.
     *
     * @param session the open dataset session this operation runs against
     * @param dd the DD name to read
     * @param key the key value; it is fitted to the {@value #KEY_LENGTH}-byte span exactly as
     *     {@code MOVE XREF-CUST-ID TO WS-M03B-KEY} fits it
     * @param keyLength how many bytes of the span the key occupies - what
     *     {@code COMPUTE WS-M03B-KEY-LN = LENGTH OF ...} computes
     * @return {@code '00'} and a record, {@code '23'} when no record carries that key, or a reported
     *     failure
     * @throws NullPointerException if {@code session}, {@code dd} or {@code key} is {@code null}
     * @throws IllegalStateException if {@code session} has been closed
     */
    public Response readByKey(Session session, String dd, String key, int keyLength) {
        return call(session, Request.readKeyed(dd, key, keyLength));
    }

    /**
     * {@code CLOSE}, as {@code app/cbl/CBSTM03A.CBL:857-860} does it.
     *
     * @param session the open dataset session this operation runs against
     * @param dd the DD name to close
     * @return the status the close reported, with the record area untouched
     * @throws NullPointerException if {@code session} or {@code dd} is {@code null}
     * @throws IllegalStateException if {@code session} has been closed
     */
    public Response close(Session session, String dd) {
        return call(session, Request.close(dd));
    }

    /**
     * The {@link FixedWidthRecord.RecordLayout} of {@code LK-M03B-AREA}, field for field.
     */
    public static final FixedWidthRecord.RecordLayout AREA_LAYOUT = areaLayout();

    /**
     * {@code 01 LK-M03B-DD PIC X(08)}, the field name verbatim from {@code CBSTM03B.CBL:101}.
     */
    public static final String DD_FIELD_NAME = "LK-M03B-DD";

    /**
     * {@code LK-M03B-OPER PIC X(01)}, verbatim from {@code CBSTM03B.CBL:102}.
     */
    public static final String OPER_FIELD_NAME = "LK-M03B-OPER";

    /**
     * {@code LK-M03B-RC PIC X(02)}, verbatim from {@code CBSTM03B.CBL:109}.
     */
    public static final String RC_FIELD_NAME = "LK-M03B-RC";

    /**
     * {@code LK-M03B-KEY PIC X(25)}, verbatim from {@code CBSTM03B.CBL:110}.
     */
    public static final String KEY_FIELD_NAME = "LK-M03B-KEY";

    /**
     * {@code LK-M03B-KEY-LN PIC S9(4)}, verbatim from {@code CBSTM03B.CBL:111}.
     */
    public static final String KEY_LN_FIELD_NAME = "LK-M03B-KEY-LN";

    /**
     * {@code LK-M03B-FLDT PIC X(1000)}, verbatim from {@code CBSTM03B.CBL:112}.
     */
    public static final String FLDT_FIELD_NAME = "LK-M03B-FLDT";

    private static FixedWidthRecord.RecordLayout areaLayout() {
        return new FixedWidthRecord.RecordLayout(AREA_LENGTH, List.of(
                FixedWidthRecord.FieldSpan.alphanumeric(DD_FIELD_NAME, DD_OFFSET, DD_LENGTH),
                FixedWidthRecord.FieldSpan.alphanumeric(OPER_FIELD_NAME, OPER_OFFSET, OPER_LENGTH),
                FixedWidthRecord.FieldSpan.alphanumeric(RC_FIELD_NAME, RC_OFFSET, RC_LENGTH),
                FixedWidthRecord.FieldSpan.alphanumeric(KEY_FIELD_NAME, KEY_OFFSET, KEY_LENGTH),
                FixedWidthRecord.FieldSpan.signedScaled(KEY_LN_FIELD_NAME, KEY_LN_OFFSET,
                        KEY_LN_LENGTH, 0),
                FixedWidthRecord.FieldSpan.alphanumeric(FLDT_FIELD_NAME, FLDT_OFFSET, FLDT_LENGTH)));
    }

    /**
     * Renders one call's worth of {@code LK-M03B-AREA} as its {@value #AREA_LENGTH} bytes.
     *
     * @param request the inbound half; never {@code null}
     * @param response the outbound half, or {@code null} to render the inbound area
     * @return the area image, exactly {@value #AREA_LENGTH} bytes
     * @throws NullPointerException if {@code request} is {@code null}
     */
    public byte[] toAreaImage(Request request, Response response) {
        Objects.requireNonNull(request, "A request is required to render LK-M03B-AREA");
        String rc = response == null ? request.rc() : response.rc();
        String fldt = response == null ? request.fldt() : response.fldt();

        FixedWidthRecord area = codec.newRecord(AREA_LAYOUT);
        codec.writePicX(area, span(DD_FIELD_NAME), request.dd());
        codec.writePicX(area, span(OPER_FIELD_NAME),
                request.oper() == null ? UNRECOGNISED_OPER_IMAGE : request.oper().image());
        codec.writePicX(area, span(RC_FIELD_NAME), rc);
        codec.writePicX(area, span(KEY_FIELD_NAME), request.key());
        codec.writeSignedScaled(area, span(KEY_LN_FIELD_NAME),
                BigDecimal.valueOf(request.keyLength()), 0);
        codec.writePicX(area, span(FLDT_FIELD_NAME), fldt);
        return area.toByteArray();
    }

    /**
     * Parses a {@value #AREA_LENGTH}-byte {@code LK-M03B-AREA} image back into its inbound projection.
     *
     * @param area the area image, exactly {@value #AREA_LENGTH} bytes
     * @return the inbound projection it encodes
     * @throws NullPointerException if {@code area} is {@code null}
     * @throws IllegalArgumentException if {@code area} is not exactly {@value #AREA_LENGTH} bytes
     */
    public Request fromAreaImage(byte[] area) {
        Objects.requireNonNull(area, "An area image is required to parse LK-M03B-AREA");
        FixedWidthRecord record = codec.wrap(area, AREA_LAYOUT);
        String oper = codec.readPicX(record, span(OPER_FIELD_NAME));
        return new Request(
                codec.readPicX(record, span(DD_FIELD_NAME)),
                Operation.ofCode(oper.charAt(0)).orElse(null),
                codec.readPicX(record, span(RC_FIELD_NAME)),
                codec.readPicX(record, span(KEY_FIELD_NAME)),
                codec.readSignedScaled(record, span(KEY_LN_FIELD_NAME), 0).intValueExact(),
                codec.readPicX(record, span(FLDT_FIELD_NAME)));
    }

    private static FixedWidthRecord.FieldSpan span(String name) {
        for (FixedWidthRecord.FieldSpan candidate : AREA_LAYOUT.spans()) {
            if (candidate.name().equals(name)) {
                return candidate;
            }
        }
        throw new IllegalStateException("LK-M03B-AREA declares no field named '" + name
                + "'; app/cbl/CBSTM03B.CBL:100-112 declares " + DD_FIELD_NAME + ", " + OPER_FIELD_NAME
                + ", " + RC_FIELD_NAME + ", " + KEY_FIELD_NAME + ", " + KEY_LN_FIELD_NAME + " and "
                + FLDT_FIELD_NAME + ", and AREA_LAYOUT must declare exactly those six.");
    }

    /**
     * Everything this class knows about one of the four datasets, and the only place its statements are
     * sent.
     */
    private final class DatasetAccess {
        private final String ddName;

        private final DatasetRelation relation;

        private final int recordLength;

        private final KeySpan keySpan;

        private final KeyPicture keyPicture;

        private final Set<Operation> capabilities;

        private volatile Statements statements;

        private DatasetAccess(String ddName,
                              DatasetRelation relation,
                              int recordLength,
                              KeySpan keySpan,
                              KeyPicture keyPicture,
                              Set<Operation> capabilities) {
            this.ddName = ddName;
            this.relation = relation;
            this.recordLength = recordLength;
            this.keySpan = keySpan;
            this.keyPicture = keyPicture;
            this.capabilities = capabilities;
        }

        private void probe() {
            resolveStatements();
        }

        private Row nextRow(byte[] position) {
            Statements sql = resolveStatements();
            String statement = position == null ? sql.browseFirst() : sql.browseAfter();
            PreparedStatementCreator creator = connection -> {
                PreparedStatement prepared = connection.prepareStatement(statement);
                prepared.setMaxRows(BROWSE_ROW_LIMIT);
                prepared.setFetchSize(BROWSE_ROW_LIMIT);
                if (position != null) {
                    recordImageForm.bindImage(prepared, 1, position, codec.charset());
                }
                return prepared;
            };
            ResultSetExtractor<Row> extractor = resultSet -> resultSet.next()
                    ? new Row(true, recordImageForm.readImage(resultSet,
                            DatasetRelation.RECORD_IMAGE_COLUMN_INDEX, codec.charset()))
                    : Row.none();
            Row row = jdbcTemplate.query(creator, extractor);
            return row == null ? Row.none() : row;
        }

        private List<byte[]> rowsByKey(String keyImage) {
            Statements sql = resolveStatements();
            String pattern = keySpan.pattern(keyImage);
            PreparedStatementCreator creator = connection -> {
                PreparedStatement prepared = connection.prepareStatement(sql.selectByKey());
                prepared.setMaxRows(KEYED_READ_ROW_LIMIT);
                prepared.setFetchSize(KEYED_READ_ROW_LIMIT);
                recordImageForm.bindOperand(prepared, 1, pattern, codec.charset());
                return prepared;
            };
            ResultSetExtractor<List<byte[]>> extractor = resultSet -> {
                List<byte[]> images = new ArrayList<>(KEYED_READ_ROW_LIMIT);
                while (images.size() < KEYED_READ_ROW_LIMIT && resultSet.next()) {
                    images.add(recordImageForm.readImage(resultSet,
                            DatasetRelation.RECORD_IMAGE_COLUMN_INDEX, codec.charset()));
                }
                return images;
            };
            return jdbcTemplate.query(creator, extractor);
        }

        private List<byte[]> rowsWithNoImage() {
            Statements sql = resolveStatements();
            PreparedStatementCreator creator = connection -> {
                PreparedStatement prepared = connection.prepareStatement(sql.probeUnreadableRows());
                prepared.setMaxRows(UNREADABLE_ROW_PROBE_LIMIT);
                prepared.setFetchSize(UNREADABLE_ROW_PROBE_LIMIT);
                return prepared;
            };
            ResultSetExtractor<List<byte[]>> extractor = resultSet -> {
                List<byte[]> images = new ArrayList<>(UNREADABLE_ROW_PROBE_LIMIT);
                while (images.size() < UNREADABLE_ROW_PROBE_LIMIT && resultSet.next()) {
                    images.add(recordImageForm.readImage(resultSet,
                            DatasetRelation.RECORD_IMAGE_COLUMN_INDEX, codec.charset()));
                }
                return images;
            };
            return jdbcTemplate.query(creator, extractor);
        }

        private String moveIntoRecordKey(String usedKey) {
            return keyPicture == KeyPicture.NUMERIC_DISPLAY
                    ? codec.movePic9(usedKey, keySpan.length())
                    : codec.movePicX(usedKey, keySpan.length());
        }

        private String intoRecordArea(byte[] image) {
            return codec.movePicX(codec.decodeImage(image, "a " + ddName + " row image"), FLDT_LENGTH);
        }

        private Statements resolveStatements() {
            Statements resolved = this.statements;
            if (resolved == null) {
                ResultSetExtractor<String> columnNameExtractor =
                        StatementGenerationJobB::extractRecordImageColumn;
                String column = relation.rememberRecordImageColumn(
                        jdbcTemplate.query(relation.describeStatement(), columnNameExtractor));
                resolved = new Statements(
                        relation.selectAllAscending(column),
                        relation.selectAfterAscending(column),
                        relation.selectByKey(column),
                        relation.selectUnreadableRows(column));
                this.statements = resolved;
            }
            return resolved;
        }
    }

    private static String extractRecordImageColumn(ResultSet resultSet) throws SQLException {
        return DatasetRelation.recordImageColumnOf(resultSet.getMetaData());
    }

    /**
     * The three statements one dataset needs - and not one more.
     *
     * @param browseFirst the first read of a sequential browse, in ascending key order
     * @param browseAfter every read after the first: the lowest image strictly above the one already
     *     returned, which is what makes a browse advance one record per read
     * @param selectByKey the keyed read, taking an escaped {@code LIKE} pattern confined to the key span
     * @param probeUnreadableRows the diagnostic statement that looks for rows carrying no record image, so
     *     an absent key is told apart from an undecodable row
     */
    private record Statements(String browseFirst, String browseAfter, String selectByKey,
                             String probeUnreadableRows) {
    }

    /**
     * Whether a read found a row, and the bytes it carried.
     *
     * @param present whether a row arrived at all
     * @param image the row's bytes, which may be {@code null} even when a row arrived
     */
    private record Row(boolean present, byte[] image) {
        private static final Row NONE = new Row(false, null);

        private static Row none() {
            return NONE;
        }
    }

    /**
     * One execution's worth of subroutine state: four cursors, each with its own open state, its own
     * position and its own {@code FILE STATUS}.
     *
     * <p>Not thread-safe within one session, deliberately: one session models one COBOL run unit, and
     * {@code CBSTM03A} is single-threaded.
     */
    public static final class Session implements AutoCloseable {
        private final Map<String, Cursor> cursors;

        private boolean closed;

        private Session(Map<String, Cursor> cursors) {
            this.cursors = cursors;
            this.closed = false;
        }

        private Cursor cursor(String dd) {
            Cursor cursor = dd == null ? null : cursors.get(dd);
            if (cursor == null) {
                throw new IllegalArgumentException("'" + dd + "' is not one of the four DD names "
                        + DD_NAMES + " this session holds a cursor for. An unrecognised DD name takes "
                        + "the WHEN OTHER arm of call(Session, Request), which touches no file at all.");
            }
            return cursor;
        }

        private void requireUsable() {
            if (closed) {
                throw new IllegalStateException("This session has been closed, so its four cursors no "
                        + "longer hold a position. app/cbl/CBSTM03A.CBL closes each DD once, at :857-911, "
                        + "after its work is done - operating afterwards is a defect in the caller and "
                        + "not a file status, because no COBOL path could produce it. Open another "
                        + "session instead.");
            }
        }

        /**
         * The current {@code FILE STATUS} of one DD - the value the next
         * {@code MOVE FILE-STATUS TO LK-M03B-RC} would move.
         *
         * @param dd one of the four DD names
         * @return the two-character status; {@link StatementGenerationJobB#UNTOUCHED_STATUS} if no
         *     operation has touched this DD yet
         * @throws IllegalArgumentException if {@code dd} is not one of the four
         */
        public String status(String dd) {
            return cursor(dd).status();
        }

        public boolean isOpen(String dd) {
            return cursor(dd).opened;
        }

        /**
         * How many records a sequential browse of one DD has returned so far, which is also the zero-based
         * index of the record its next read would return.
         *
         * @param dd one of the four DD names
         * @return the count; never negative
         * @throws IllegalArgumentException if {@code dd} is not one of the four
         */
        public int position(String dd) {
            return cursor(dd).returned;
        }

        /**
         * Whether a sequential browse of one DD has run past its last record.
         *
         * @param dd one of the four DD names
         * @return {@code true} once a read has reported {@code '10'}
         * @throws IllegalArgumentException if {@code dd} is not one of the four
         */
        public boolean isExhausted(String dd) {
            return cursor(dd).exhausted;
        }

        public boolean isClosed() {
            return closed;
        }

        /**
         * Releases the session: marks every cursor closed and forgets every position.
         */
        @Override
        public void close() {
            if (closed) {
                return;
            }
            for (Cursor cursor : cursors.values()) {
                cursor.release();
            }
            closed = true;
        }
    }

    /**
     * One file's open state, browse position and {@code FILE STATUS} - the per-session half of
     * {@code CBSTM03B}'s state.
     */
    private static final class Cursor {
        private final DatasetAccess access;

        private String status;

        private boolean opened;

        private byte[] position;

        private int returned;

        private boolean exhausted;

        private Cursor(DatasetAccess access) {
            this.access = access;
            this.status = UNTOUCHED_STATUS;
            this.opened = false;
            this.position = null;
            this.returned = 0;
            this.exhausted = false;
        }

        private String status() {
            return status;
        }

        private void openInput() {
            if (opened) {
                status = ALREADY_OPEN_STATUS;
                return;
            }
            try {
                access.probe();
            } catch (DataAccessException translated) {
                status = PERMANENT_ERROR_STATUS;
                LOG.error("Could not open " + access.ddName + " for input - "
                        + BackendDiagnostic.of(translated).describe() + "; reporting file status "
                        + FileStatus.toStatusImage(PERMANENT_ERROR_STATUS) + " to the caller, which "
                        + "app/cbl/CBSTM03A.CBL:736 tests before displaying and abending");
                return;
            } catch (IllegalStateException unusable) {
                status = PERMANENT_ERROR_STATUS;
                LOG.error("Could not open " + access.ddName + " for input: the configured dataset "
                        + "presents no usable record-image column - " + unusable.getMessage()
                        + "; reporting file status " + FileStatus.toStatusImage(PERMANENT_ERROR_STATUS));
                return;
            }
            opened = true;
            position = null;
            returned = 0;
            exhausted = false;
            status = FileStatus.OK;
        }

        private void close() {
            if (!opened) {
                status = NOT_OPEN_STATUS;
                return;
            }
            opened = false;
            position = null;
            returned = 0;
            exhausted = false;
            status = FileStatus.OK;
        }

        private void release() {
            opened = false;
            position = null;
            exhausted = false;
        }

        private String readNext(String currentFldt) {
            if (!opened) {
                status = NOT_OPEN_FOR_READ_STATUS;
                return currentFldt;
            }
            if (exhausted) {
                status = FileStatus.END_OF_FILE;
                return currentFldt;
            }
            Row row;
            try {
                row = access.nextRow(position);
            } catch (DataAccessException translated) {
                status = PERMANENT_ERROR_STATUS;
                LOG.error("Could not read the next record of " + access.ddName + " - "
                        + BackendDiagnostic.of(translated).describe() + "; reporting file status "
                        + FileStatus.toStatusImage(PERMANENT_ERROR_STATUS) + " rather than treating an "
                        + "unreachable dataset as one that has ended");
                return currentFldt;
            } catch (IllegalArgumentException unrepresentable) {
                status = notReadable(access.ddName, "the next record of", unrepresentable);
                return currentFldt;
            }
            if (!row.present()) {
                exhausted = true;
                status = FileStatus.END_OF_FILE;
                return currentFldt;
            }
            String area = accept(row.image(), "the next record of");
            if (area == null) {
                return currentFldt;
            }
            position = row.image().clone();
            returned++;
            return area;
        }

        private String readByKey(Request request, String currentFldt) {
            if (!opened) {
                status = NOT_OPEN_FOR_READ_STATUS;
                return currentFldt;
            }
            String keyImage = access.moveIntoRecordKey(request.usedKey());
            List<byte[]> rows;
            try {
                rows = access.rowsByKey(keyImage);
            } catch (DataAccessException translated) {
                status = PERMANENT_ERROR_STATUS;
                LOG.error("Could not read " + access.ddName + " by key - "
                        + BackendDiagnostic.of(translated).describe() + "; reporting file status "
                        + FileStatus.toStatusImage(PERMANENT_ERROR_STATUS)
                        + " rather than reporting a record that may exist as absent");
                return currentFldt;
            } catch (IllegalArgumentException unrepresentable) {
                status = notReadable(access.ddName, "a keyed record of", unrepresentable);
                return currentFldt;
            }
            if (rows == null) {
                status = PERMANENT_ERROR_STATUS;
                LOG.error("The " + access.ddName + " keyed read yielded no result object at all; "
                        + "reporting file status " + FileStatus.toStatusImage(PERMANENT_ERROR_STATUS)
                        + " rather than treating it as a key with no matching record");
                return currentFldt;
            }
            if (rows.isEmpty()) {
                status = provenAbsenceStatus();
                return currentFldt;
            }
            if (rows.size() > 1) {
                // A base KSDS primary key is unique - RECORD KEY IS FD-CUST-ID (app/cbl/CBSTM03B.CBL:46)
                // and RECORD KEY IS FD-ACCT-ID (:52) - so more than one match cannot arise in the legacy
                // system and is an integrity defect in the backing relation.
                status = PERMANENT_ERROR_STATUS;
                LOG.error("Could not read " + access.ddName + " by key: " + rows.size() + " rows match a "
                        + "single primary key, but its RECORD KEY (app/cbl/CBSTM03B.CBL:46, :52) is "
                        + "unique. Reporting file status "
                        + FileStatus.toStatusImage(PERMANENT_ERROR_STATUS)
                        + " rather than returning an arbitrary one of them as though it were the record: "
                        + "the caller composes a customer statement from whatever it is handed, so an "
                        + "arbitrary choice here would surface as plausible and wrong output. The backing "
                        + "relation needs a unique constraint on its key span");
                return currentFldt;
            }
            byte[] image = rows.get(0);
            String area = accept(image, "a keyed record of");
            if (area == null) {
                return currentFldt;
            }
            return area;
        }

        private String provenAbsenceStatus() {
            List<byte[]> unreadable;
            try {
                unreadable = access.rowsWithNoImage();
            } catch (DataAccessException translated) {
                LOG.error("Could not establish that " + access.ddName + " holds no unreadable row before "
                        + "reporting a key as absent - " + BackendDiagnostic.of(translated).describe()
                        + "; reporting file status " + FileStatus.toStatusImage(PERMANENT_ERROR_STATUS)
                        + " rather than reporting a record that may exist as absent");
                return PERMANENT_ERROR_STATUS;
            }
            if (unreadable == null) {
                LOG.error("The " + access.ddName + " unreadable-row probe yielded no result object at all, "
                        + "so a keyed read's INVALID KEY could not be established; reporting file status "
                        + FileStatus.toStatusImage(PERMANENT_ERROR_STATUS)
                        + " rather than reporting a record that may exist as absent");
                return PERMANENT_ERROR_STATUS;
            }
            if (unreadable.isEmpty()) {
                return FileStatus.NOT_FOUND;
            }
            LOG.error("A keyed read of " + access.ddName + " matched no row, but the dataset holds a row "
                    + "with no record image at column position "
                    + DatasetRelation.RECORD_IMAGE_COLUMN_INDEX + " - and the RECORD KEY is part of that "
                    + "image, so that row's key cannot be known; reporting file status "
                    + FileStatus.toStatusImage(PERMANENT_ERROR_STATUS)
                    + " rather than reporting as absent a record that may well be present");
            return PERMANENT_ERROR_STATUS;
        }

        private static String notReadable(String ddName, String attempt,
                                          IllegalArgumentException unreadable) {
            LOG.error("Could not read " + attempt + " " + ddName + ": a stored character has no "
                    + "representation in the configured dataset code page, so the record image cannot be "
                    + "recovered as bytes - " + unreadable.getMessage() + "; reporting file status "
                    + FileStatus.toStatusImage(PERMANENT_ERROR_STATUS));
            return PERMANENT_ERROR_STATUS;
        }

        private String accept(byte[] image, String attempt) {
            if (image == null) {
                status = PERMANENT_ERROR_STATUS;
                LOG.error("Could not read " + attempt + " " + access.ddName + ": the row carries no "
                        + "record image at column position " + DatasetRelation.RECORD_IMAGE_COLUMN_INDEX
                        + "; reporting file status " + FileStatus.toStatusImage(PERMANENT_ERROR_STATUS)
                        + " rather than reporting a record that is present as absent");
                return null;
            }
            String area;
            try {
                area = access.intoRecordArea(image);
            } catch (IllegalStateException undecodable) {
                status = PERMANENT_ERROR_STATUS;
                LOG.error("Could not read " + attempt + " " + access.ddName + ": a stored byte is not "
                        + "valid data in the configured dataset code page - " + undecodable.getMessage()
                        + "; reporting file status " + FileStatus.toStatusImage(PERMANENT_ERROR_STATUS));
                return null;
            }
            if (image.length != access.recordLength) {
                status = FileStatus.RECORD_LENGTH_CONFLICT;
                LOG.warn("Read " + attempt + " " + access.ddName + " with a record-length conflict: the "
                        + "row is " + image.length + " byte(s) where its copybook declares "
                        + access.recordLength + ". Reporting file status "
                        + FileStatus.toStatusImage(FileStatus.RECORD_LENGTH_CONFLICT)
                        + " and returning the record area, which is what a COBOL READ does. A caller that "
                        + "decodes by absolute offset must treat this as fatal (gate G19); the nine "
                        + "app/cbl/CBSTM03A.CBL guards that accept '04' do not decode the area they "
                        + "accept it on, and the loop read that does decode it abends");
                return area;
            }
            status = FileStatus.OK;
            return area;
        }
    }
}
