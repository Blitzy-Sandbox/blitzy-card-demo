package com.vsergeychik.carddemo.transaction;

import com.vsergeychik.carddemo.common.DatasetRelation;
import com.vsergeychik.carddemo.common.DatasetRelation.BackendDiagnostic;
import com.vsergeychik.carddemo.common.DatasetRelation.KeySpan;
import com.vsergeychik.carddemo.common.FileStatus;
import com.vsergeychik.carddemo.common.FileStatus.Outcome;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.FixedWidthRecord.FieldSpan;
import com.vsergeychik.carddemo.common.FixedWidthRecord.RecordLayout;
import com.vsergeychik.carddemo.common.RecordImageForm;
import com.vsergeychik.carddemo.config.CobolCharsetConfig;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBinding;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBindings;
import com.vsergeychik.carddemo.transaction.model.TranCategoryRecord;

import java.nio.charset.Charset;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * The one data-access component for the {@code TRANCATG} transaction-category lookup: a 60-byte KSDS
 * addressed by a 6-byte key, opened, read by key, and closed - and nothing else.
 *
 * <p>{@code TRANCATG} is read by exactly one program, {@code app/cbl/CBTRN03C.cbl} - the transaction detail
 * report - and {@code grep -n "TRANCATG" app/cbl/*.cbl} returns nineteen hits across that one file.
 */
@Repository
public class TranCategoryRepository {
    private static final Log LOG = LogFactory.getLog(TranCategoryRepository.class);

    /**
     * The mainframe DD name, and the {@code carddemo.datasets} configuration key: {@code TRANCATG}.
     */
    public static final String DD_NAME = "TRANCATG";

    /**
     * The {@code app/cpy} member defining the layout: {@code CVTRA04Y}.
     */
    public static final String COPYBOOK = "CVTRA04Y";

    /**
     * The sole COBOL consumer: {@code CBTRN03C}, the transaction detail report.
     */
    public static final String CONSUMER_PROGRAM = "CBTRN03C";

    /**
     * The declared record width, 60 bytes - {@code RECLN = 60} from {@code app/cpy/CVTRA04Y.cpy} and
     * {@code RECORDSIZE(60 60)} from {@code app/jcl/TRANCATG.jcl}.
     */
    public static final int RECORD_LENGTH = TranCategoryRecord.RECORD_LENGTH;

    /**
     * The key width, 6 bytes: {@code TRAN-TYPE-CD X(02)} plus {@code TRAN-CAT-CD 9(04)}.
     */
    public static final int KEY_LENGTH = TranCategoryRecord.TRAN_CAT_KEY_LENGTH;

    /**
     * The key's 0-based offset within the record: 0, the start of the record.
     */
    public static final int KEY_OFFSET = TranCategoryRecord.TRAN_CAT_KEY_OFFSET;

    /**
     * The width of the other {@code TRAN-CAT-KEY}: {@code CVTRA01Y}'s 17 bytes, which belong to
     * {@code TCATBALF} and must never be used against {@code TRANCATG}.
     */
    public static final int TRAN_CAT_BAL_KEY_LENGTH = 17;

    public static final int RECORD_IMAGE_COLUMN_INDEX = DatasetRelation.RECORD_IMAGE_COLUMN_INDEX;

    /**
     * The value both COBOL paragraphs move into {@code APPL-RESULT} before attempting the operation:
     * {@code 8} ({@code app/cbl/CBTRN03C.cbl:449} and {@code :588}).
     */
    public static final int APPL_RESULT_INITIAL = 8;

    /**
     * The {@code APPL-RESULT} value both paragraphs move on the failing arm: {@code 12}
     * ({@code app/cbl/CBTRN03C.cbl:454} and {@code :593}).
     */
    public static final int APPL_RESULT_FATAL = 12;

    private static final char PERMANENT_ERROR_FEEDBACK_CODE = 0;

    public static final String PERMANENT_ERROR_STATUS = "9" + PERMANENT_ERROR_FEEDBACK_CODE;

    private static final RecordLayout LAYOUT = TranCategoryRecord.LAYOUT;

    private static final FieldSpan FILLER_SPAN = TranCategoryRecord.FILLER;

    private static final KeySpan KEY_SPAN = new KeySpan(KEY_OFFSET, KEY_LENGTH);

    private static final int KEYED_READ_ROW_LIMIT = 2;

    private static final int UNREADABLE_ROW_PROBE_LIMIT = 1;

    private static final int UNIQUE_KEY_ROW_COUNT = 1;

    private final JdbcTemplate jdbcTemplate;

    private final FixedWidthCodec codec;

    private final RecordImageForm recordImageForm;

    private final DatasetRelation relation;

    private volatile Statements statements;

    /**
     * Assembles the repository from the module's shared {@link JdbcTemplate}, the DD-name-keyed dataset
     * catalogue, the explicitly named dataset code page and the deployment's record-image representation.
     *
     * @param jdbcTemplate the module-wide template; never {@code null}
     * @param datasetBindings the {@code carddemo.datasets} catalogue; never {@code null}
     * @param datasetCharset the code page the dataset holds its record images in, from
     *     {@code carddemo.charset.dataset}
     * @param recordImageForm how the deployment's driver presents a record image over JDBC, from
     *     {@value RecordImageForm#FORM_PROPERTY}
     * @throws NullPointerException if any argument is {@code null}
     * @throws IllegalStateException if this class's own geometry is inconsistent, if no binding is
     *     configured for {@link #DD_NAME}, or if that binding declares a record length, key geometry, base
     *     relationship
     */
    public TranCategoryRepository(
            JdbcTemplate jdbcTemplate,
            DatasetBindings datasetBindings,
            @Qualifier(CobolCharsetConfig.DATASET_CHARSET_BEAN_NAME) Charset datasetCharset,
            RecordImageForm recordImageForm) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "A JdbcTemplate is required: the "
                + DD_NAME + " category lookup is reached through the module's shared template over the "
                + "configuration-bound DataSource");
        Objects.requireNonNull(datasetBindings, "The carddemo.datasets binding catalogue is required: "
                + "dataset names live in configuration and are never written in Java");
        this.codec = new FixedWidthCodec(Objects.requireNonNull(datasetCharset, "A dataset charset is "
                + "required: a fixed-width mainframe record is bytes in a specific code page, so the "
                + "code page is stated explicitly and never taken from the platform"));
        this.recordImageForm = Objects.requireNonNull(recordImageForm, "A record-image representation is "
                + "required: whether this deployment's driver presents a record image as characters or "
                + "as bytes is stated once, by " + RecordImageForm.FORM_PROPERTY + ", and never decided "
                + "per repository");
        RecordImageForm.requireSingleByteCodePage(datasetCharset);

        TranCategoryRecord.verifyDeclaredGeometry();
        verifyRecordGeometry(LAYOUT.recordLength(), sumOfLayoutSpanWidths(),
                FILLER_SPAN.endOffsetExclusive(), KEY_LENGTH);

        DatasetBinding binding = datasetBindings.binding(DD_NAME);
        requireCopybookRecordLength(binding);
        requireSixByteKey(binding);
        requireBaseCluster(binding);
        requireDeclaredCopybook(binding);

        this.relation = DatasetRelation.of(requireUsableDatasetName(binding.dsname()), RECORD_LENGTH);
    }

    static int sumOfLayoutSpanWidths() {
        int total = 0;
        for (FieldSpan span : LAYOUT.storageSpans()) {
            total += span.length();
        }
        return total;
    }

    static void verifyRecordGeometry(int layoutRecordLength,
                                     int summedSpanWidths,
                                     int fillerEndOffset,
                                     int keyLength) {
        if (layoutRecordLength != RECORD_LENGTH) {
            throw new IllegalStateException("The " + COPYBOOK + " layout declares a record length of "
                    + layoutRecordLength + ", but app/cpy/" + COPYBOOK + ".cpy reads RECLN = "
                    + RECORD_LENGTH + " and app/jcl/" + DD_NAME + ".jcl defines the cluster "
                    + "RECORDSIZE(" + RECORD_LENGTH + " " + RECORD_LENGTH + "). This repository decodes "
                    + "by absolute offset against exactly that width.");
        }
        if (summedSpanWidths != RECORD_LENGTH) {
            throw new IllegalStateException("The " + COPYBOOK + " layout's storage spans sum to "
                    + summedSpanWidths + ", not " + RECORD_LENGTH + ". The record is TRAN-CAT-KEY "
                    + KEY_LENGTH + " + TRAN-CAT-TYPE-DESC "
                    + TranCategoryRecord.TRAN_CAT_TYPE_DESC_LENGTH + " + FILLER "
                    + TranCategoryRecord.FILLER_LENGTH + "; a total of "
                    + (RECORD_LENGTH - TranCategoryRecord.FILLER_LENGTH)
                    + " means the trailing FILLER X(0" + TranCategoryRecord.FILLER_LENGTH
                    + ") has been dropped, which would shift every byte of the dataset after the "
                    + "description.");
        }
        if (fillerEndOffset != RECORD_LENGTH) {
            throw new IllegalStateException("The " + COPYBOOK + " trailing FILLER ends at 0-based "
                    + "offset " + fillerEndOffset + ", but it is the last span of a " + RECORD_LENGTH
                    + "-byte record and must end exactly there. FILLER is a first-class span in this "
                    + "module - declared, positioned and always emitted - never an implied gap.");
        }
        if (keyLength != KEY_LENGTH) {
            throw new IllegalStateException("The " + COPYBOOK + " TRAN-CAT-KEY must be " + KEY_LENGTH
                    + " bytes - TRAN-TYPE-CD X(02) plus TRAN-CAT-CD 9(04) - but is declared as "
                    + keyLength + ". BEWARE THE NAMESAKE: app/cpy/CVTRA01Y.cpy declares a group with "
                    + "the IDENTICAL COBOL name TRAN-CAT-KEY that is " + TRAN_CAT_BAL_KEY_LENGTH
                    + " bytes wide (TRANCAT-ACCT-ID 9(11) + TRANCAT-TYPE-CD X(02) + TRANCAT-CD 9(04)) "
                    + "and belongs to the TCATBALF dataset, not " + DD_NAME + ". A "
                    + TRAN_CAT_BAL_KEY_LENGTH + "-byte key against this " + RECORD_LENGTH
                    + "-byte record would compare the whole key AND the first "
                    + (TRAN_CAT_BAL_KEY_LENGTH - KEY_LENGTH) + " characters of "
                    + "TRAN-CAT-TYPE-DESC, so every lookup would silently miss.");
        }
    }

    /**
     * Requires the configured binding to agree with {@code app/cpy/CVTRA04Y.cpy} about the record width.
     *
     * @param binding the configured binding
     * @throws IllegalStateException if the declared record length is not {@link #RECORD_LENGTH}
     */
    static void requireCopybookRecordLength(DatasetBinding binding) {
        if (binding.recordLength() != RECORD_LENGTH) {
            throw new IllegalStateException("Dataset binding for '" + DD_NAME + "' declares a record "
                    + "length of " + binding.recordLength() + ", but app/cpy/" + COPYBOOK + ".cpy "
                    + "declares (RECLN = " + RECORD_LENGTH + "), app/cbl/" + CONSUMER_PROGRAM
                    + ".cbl:78-82 splits the same record as FD-TRAN-CAT-KEY " + KEY_LENGTH
                    + " + FD-TRAN-CAT-DATA X(54), and app/jcl/" + DD_NAME + ".jcl defines the cluster "
                    + "RECORDSIZE(" + RECORD_LENGTH + " " + RECORD_LENGTH + "). All four agree. This "
                    + "repository decodes by absolute offset against exactly that width, so correct "
                    + "carddemo.datasets." + DD_NAME + ".record-length to " + RECORD_LENGTH + ".");
        }
    }

    static void requireSixByteKey(DatasetBinding binding) {
        if (!binding.keyed()) {
            throw new IllegalStateException("Dataset binding for '" + DD_NAME + "' declares "
                    + "organization '" + binding.organization() + "', but app/cbl/" + CONSUMER_PROGRAM
                    + ".cbl:46-48 declares ORGANIZATION IS INDEXED with ACCESS MODE IS RANDOM and "
                    + "RECORD KEY IS FD-TRAN-CAT-KEY, and app/jcl/" + DD_NAME + ".jcl defines the "
                    + "cluster INDEXED. The only access this dataset supports is a keyed read, so "
                    + "correct carddemo.datasets." + DD_NAME + ".organization to "
                    + DatasetBinding.KSDS + ".");
        }
        Integer declaredKeyLength = binding.keyLength();
        if (declaredKeyLength == null) {
            throw new IllegalStateException("Dataset binding for '" + DD_NAME + "' declares no "
                    + "key-length, so nothing states where its key ends and a keyed read against it "
                    + "would be guessing. It is " + KEY_LENGTH + ": TRAN-TYPE-CD X(02) plus "
                    + "TRAN-CAT-CD 9(04) from app/cpy/" + COPYBOOK + ".cpy, confirmed by KEYS("
                    + KEY_LENGTH + " " + KEY_OFFSET + ") in app/jcl/" + DD_NAME + ".jcl.");
        }
        if (declaredKeyLength != KEY_LENGTH) {
            throw new IllegalStateException("Dataset binding for '" + DD_NAME + "' declares key-length "
                    + declaredKeyLength + ", but " + DD_NAME + "'s TRAN-CAT-KEY is " + KEY_LENGTH
                    + " bytes - TRAN-TYPE-CD X(02) plus TRAN-CAT-CD 9(04) - confirmed by KEYS("
                    + KEY_LENGTH + " " + KEY_OFFSET + ") in app/jcl/" + DD_NAME + ".jcl."
                    + (declaredKeyLength == TRAN_CAT_BAL_KEY_LENGTH
                            ? " THAT VALUE IS THE NAMESAKE'S: app/cpy/CVTRA01Y.cpy declares a group "
                                    + "with the IDENTICAL COBOL name TRAN-CAT-KEY that is "
                                    + TRAN_CAT_BAL_KEY_LENGTH + " bytes wide and belongs to TCATBALF, "
                                    + "not " + DD_NAME + ". The two records have been conflated; the "
                                    + TRAN_CAT_BAL_KEY_LENGTH + " belongs on the TCATBALF binding."
                            : "")
                    + " Correct carddemo.datasets." + DD_NAME + ".key-length to " + KEY_LENGTH + ".");
        }
        if (binding.keyOffsetOrZero() != KEY_OFFSET) {
            throw new IllegalStateException("Dataset binding for '" + DD_NAME + "' places its key at "
                    + "0-based offset " + binding.keyOffsetOrZero() + ", but TRAN-CAT-KEY is the first "
                    + "item of the record - app/cbl/" + CONSUMER_PROGRAM + ".cbl:79-81 declares "
                    + "FD-TRAN-CAT-KEY first, and app/jcl/" + DD_NAME + ".jcl says KEYS(" + KEY_LENGTH
                    + " " + KEY_OFFSET + ") explicitly. Remove carddemo.datasets." + DD_NAME
                    + ".key-offset, which defaults to " + KEY_OFFSET + ".");
        }
    }

    static void requireBaseCluster(DatasetBinding binding) {
        if (binding.base() != null) {
            throw new IllegalStateException("Dataset binding for '" + DD_NAME + "' declares base '"
                    + binding.base() + "', but " + DD_NAME + " IS a base cluster: app/jcl/" + DD_NAME
                    + ".jcl defines it INDEXED with KEYS(" + KEY_LENGTH + " " + KEY_OFFSET + "), and no "
                    + "alternate index over it exists anywhere in the estate - it is not among the "
                    + "eight DEFINE FILE entries of app/csd/CARDDEMO.CSD at all. Remove "
                    + "carddemo.datasets." + DD_NAME + ".base.");
        }
    }

    /**
     * Requires the configured binding, where it names a copybook at all, to name {@link #COPYBOOK}.
     *
     * @param binding the configured binding
     * @throws IllegalStateException if the binding names a copybook other than {@link #COPYBOOK}
     */
    static void requireDeclaredCopybook(DatasetBinding binding) {
        String declared = binding.copybook();
        if (declared != null && !COPYBOOK.equals(declared)) {
            throw new IllegalStateException("Dataset binding for '" + DD_NAME + "' names copybook '"
                    + declared + "', but " + DD_NAME + " holds " + COPYBOOK + "'s "
                    + RECORD_LENGTH + "-byte TRAN-CAT-RECORD - app/cbl/" + CONSUMER_PROGRAM
                    + ".cbl:108 reads COPY " + COPYBOOK + ". If that value is CVTRA01Y, this binding "
                    + "has been confused with TCATBALF's, whose TRAN-CAT-KEY is "
                    + TRAN_CAT_BAL_KEY_LENGTH + " bytes rather than " + KEY_LENGTH + ". Correct "
                    + "carddemo.datasets." + DD_NAME + ".copybook to " + COPYBOOK + ".");
        }
    }

    static String requireUsableDatasetName(String candidate) {
        if (candidate == null || candidate.isBlank()) {
            throw new IllegalStateException("The dataset binding for '" + DD_NAME + "' declares no "
                    + "dataset name. Set carddemo.datasets." + DD_NAME + ".dsname; this repository "
                    + "composes its statements from configuration alone and hard-codes no dataset name "
                    + "(gate G46).");
        }
        return DatasetRelation.requireDatasetName(candidate);
    }

    /**
     * The resolved dataset name, exactly as configuration declares it.
     *
     * @return the configured dataset name for {@link #DD_NAME}; never {@code null}, never blank
     */
    public String datasetName() {
        return relation.dsname();
    }

    /**
     * The record width this repository decodes against: {@link #RECORD_LENGTH} bytes.
     *
     * @return the copybook-declared record width
     */
    public int recordLength() {
        return RECORD_LENGTH;
    }

    /**
     * The key width this repository addresses: {@value #TRAN_CAT_BAL_KEY_LENGTH} bytes, and never
     * {@link #TRAN_CAT_BAL_KEY_LENGTH}.
     *
     * @return {@value #TRAN_CAT_BAL_KEY_LENGTH}
     */
    public int keyLength() {
        return KEY_LENGTH;
    }

    /**
     * The code page this repository encodes keys in and decodes records from.
     *
     * @return the dataset code page; never {@code null}
     */
    public Charset datasetCharset() {
        return codec.charset();
    }

    /**
     * Opens the {@code TRANCATG} dataset for input, reporting only the resulting file status: the Java form
     * of {@code 0400-TRANCATG-OPEN} ({@code app/cbl/CBTRN03C.cbl:448-464}).
     *
     * <p>The COBOL is a two-way test - {@code IF TRANCATG-STATUS = '00'} moves {@code 0} into
     * {@code APPL-RESULT}, anything else moves {@link #APPL_RESULT_FATAL} - and this method reproduces
     * exactly that shape and nothing more.
     *
     * @return {@link FileStatus#OK} when the dataset is addressable, otherwise
     *     {@link #PERMANENT_ERROR_STATUS}; never {@code null}, always two characters
     */
    public String open() {
        return probeDatasetAvailability("OPEN INPUT");
    }

    private Statements statements() {
        Statements resolved = this.statements;
        if (resolved != null) {
            return resolved;
        }
        Statements composed = resolveStatements();
        this.statements = composed;
        return composed;
    }

    /**
     * The statements this repository sends, composed from one describe.
     *
     * @param selectByKey the keyed read: the record whose image begins with the
     *     {@value #TRAN_CAT_BAL_KEY_LENGTH}-byte {@code TRAN-CAT-KEY}
     * @param probeUnreadableRows the rows whose record-image column holds nothing
     */
    private record Statements(String selectByKey, String probeUnreadableRows) {
    }

    /**
     * Closes the {@code TRANCATG} dataset, reporting only the resulting file status: the Java form of
     * {@code 9400-TRANCATG-CLOSE} ({@code app/cbl/CBTRN03C.cbl:587-603}).
     *
     * @return {@link FileStatus#OK} when the dataset is still describable, otherwise
     *     {@link #PERMANENT_ERROR_STATUS}; never {@code null}, always two characters
     */
    public String close() {
        try {
            return probeDatasetAvailability("CLOSE");
        } finally {
            this.statements = null;
            this.relation.forgetRecordImageColumn();
        }
    }

    /**
     * The {@code APPL-RESULT} value the open and close paragraphs move for a given file status: {@code 0}
     * for {@code '00'}, {@link #APPL_RESULT_FATAL} for anything else.
     *
     * @param status the two-character file status an {@link #open()} or {@link #close()} reported
     * @return {@link FileStatus#APPL_AOK} or {@link #APPL_RESULT_FATAL}
     * @throws NullPointerException if {@code status} is {@code null}
     */
    public static int applResultOfFileStatus(String status) {
        Objects.requireNonNull(status, "A file status is required to classify it; open() and close() "
                + "always return one, so an absent status is a defect in the caller");
        return FileStatus.isOk(status) ? FileStatus.APPL_AOK : APPL_RESULT_FATAL;
    }

    private String probeDatasetAvailability(String cobolOperation) {
        try {
            this.statements = resolveStatements();
            return FileStatus.OK;
        } catch (IllegalStateException unusable) {
            LOG.error("Could not " + cobolOperation + " the " + DD_NAME + " dataset: "
                    + unusable.getMessage() + "; reporting file status "
                    + FileStatus.toStatusImage(PERMANENT_ERROR_STATUS) + " to the caller");
            return PERMANENT_ERROR_STATUS;
        } catch (DataAccessException translated) {
            logRefusal(translated, cobolOperation + " the " + DD_NAME + " dataset (a describe of the "
                    + "configured relation, which is what distinguishes an absent dataset from an "
                    + "unreadable record)");
            return PERMANENT_ERROR_STATUS;
        }
    }

    /**
     * Builds the {@value #TRAN_CAT_BAL_KEY_LENGTH}-byte {@code TRAN-CAT-KEY} image for a lookup: the Java
     * form of the two moves at {@code app/cbl/CBTRN03C.cbl:191-194}.
     *
     * <p>{@code TRAN-TYPE-CD} is {@code PIC X(02)}, so it is padded and truncated on the right with spaces
     * - a one-character {@code "X"} becomes {@code "X "}.
     *
     * @param tranTypeCd the 2-character transaction type code, {@code "01"} rather than 1
     * @param tranCatCd the category code; must not be negative, because {@code PIC 9(04)} is unsigned and
     *     has no sign position
     * @return the key image, exactly {@value #TRAN_CAT_BAL_KEY_LENGTH} characters
     * @throws NullPointerException if {@code tranTypeCd} is {@code null}
     * @throws IllegalArgumentException if {@code tranCatCd} is negative
     */
    public String keyImage(String tranTypeCd, int tranCatCd) {
        return TranCategoryRecord.tranCatKeyImage(tranTypeCd, tranCatCd, codec.charset());
    }

    /**
     * Reads one category record by its composite key: the Java form of
     * {@code READ TRANCATG-FILE INTO TRAN-CAT-RECORD ... INVALID KEY}
     * ({@code app/cbl/CBTRN03C.cbl:505-511}).
     *
     * <p>The parameters are a {@link String} then an {@code int}, in exactly the order and the Java types
     * of the two moves the caller performs at {@code :191-194}, so a caller holding a transaction record
     * passes its {@code TRAN-TYPE-CD} and {@code TRAN-CAT-CD} straight through.
     *
     * @param tranTypeCd the 2-character transaction type code; never {@code null}
     * @param tranCatCd the category code; must not be negative
     * @return the discriminated outcome; never {@code null}
     * @throws NullPointerException if {@code tranTypeCd} is {@code null}
     * @throws IllegalArgumentException if {@code tranCatCd} is negative
     * @throws IllegalStateException if the backend presents the dataset with no usable record-image column,
     *     which is a contract violation rather than an I/O outcome
     */
    public ReadResult readByKey(String tranTypeCd, int tranCatCd) {
        return readByKeyImage(keyImage(tranTypeCd, tranCatCd));
    }

    private ReadResult readByKeyImage(String keyImage) {
        Statements sql;
        try {
            sql = statements();
        } catch (DataAccessException unreachable) {
            return ReadResult.other(PERMANENT_ERROR_STATUS, logRefusal(unreachable, "describe the "
                    + DD_NAME + " dataset '" + relation.dsname() + "' to read it by key"));
        }

        List<byte[]> rows;
        try {
            rows = fetch(sql.selectByKey(), KEY_SPAN.pattern(keyImage));
        } catch (DataAccessException translated) {
            // Reported as a status, exactly as the COBOL keeps only the status, so the caller's own guard
            // chain decides what to do about it - and carrying the backend's own diagnosis alongside it, so
            // the abend that follows can be traced to a cause.
            return ReadResult.other(PERMANENT_ERROR_STATUS, logRefusal(translated, "read the " + DD_NAME
                    + " dataset '" + relation.dsname() + "' by key"));
        }

        if (rows == null) {
            LOG.error("The " + DD_NAME + " dataset yielded no result object at all for a keyed read; "
                    + "reporting file status " + FileStatus.toStatusImage(PERMANENT_ERROR_STATUS)
                    + " rather than treating it as a dataset with no matching record");
            return ReadResult.other(PERMANENT_ERROR_STATUS);
        }
        if (rows.isEmpty()) {
            // Both halves of TRAN-CAT-KEY live inside the record image (app/cpy/CVTRA04Y.cpy:5-7), so a row
            // whose record-image column holds nothing has no knowable key and the keyed LIKE predicate
            // cannot match it: SQL evaluates every comparison against a null as UNKNOWN.
            return provenAbsence(sql.probeUnreadableRows());
        }
        if (rows.size() > UNIQUE_KEY_ROW_COUNT) {
            // A 6-byte primary key selected more than one row, which a KSDS defined KEYS(6 0) cannot do.
            // The relation is not the dataset the copybook describes.
            LOG.error("A single " + KEY_LENGTH + "-byte " + DD_NAME + " key selected " + rows.size()
                    + " rows; app/jcl/" + DD_NAME + ".jcl defines the cluster INDEXED with KEYS("
                    + KEY_LENGTH + " " + KEY_OFFSET + "), so its primary key is unique by construction "
                    + "and exactly " + UNIQUE_KEY_ROW_COUNT + " row can match. Reporting file status "
                    + FileStatus.toStatusImage(PERMANENT_ERROR_STATUS) + " rather than returning one of "
                    + "several rows as though it were the only one");
            return ReadResult.other(PERMANENT_ERROR_STATUS);
        }

        byte[] recordImage = rows.get(UNIQUE_KEY_ROW_COUNT - 1);
        if (recordImage == null) {
            LOG.error("The matching " + DD_NAME + " row carries no record image at column position "
                    + RECORD_IMAGE_COLUMN_INDEX + "; reporting file status "
                    + FileStatus.toStatusImage(PERMANENT_ERROR_STATUS)
                    + " rather than reporting a record that is present as absent");
            return ReadResult.other(PERMANENT_ERROR_STATUS);
        }
        if (recordImage.length != RECORD_LENGTH) {
            LOG.error("The " + DD_NAME + " dataset presented a matching row of " + recordImage.length
                    + " byte(s) - a stored record width, not a reason code - but TRAN-CAT-RECORD is "
                    + "declared " + RECORD_LENGTH + " bytes by app/cpy/" + COPYBOOK + ".cpy and every "
                    + "row of app/data/ASCII/trancatg.txt is that width; reporting file status "
                    + FileStatus.toStatusImage(PERMANENT_ERROR_STATUS) + " rather than decoding "
                    + "TRAN-CAT-TYPE-DESC from offsets that would not be its own");
            return ReadResult.other(PERMANENT_ERROR_STATUS);
        }
        return ReadResult.found(TranCategoryRecord.decode(recordImage, codec.charset()));
    }

    private ReadResult provenAbsence(String unreadableRowsProbe) {
        List<byte[]> unreadable;
        try {
            unreadable = fetchUnreadableRows(unreadableRowsProbe);
        } catch (DataAccessException translated) {
            return ReadResult.other(PERMANENT_ERROR_STATUS, logRefusal(translated, "establish that the "
                    + DD_NAME + " dataset '" + relation.dsname() + "' holds no unreadable row before "
                    + "reporting a key as absent"));
        }
        if (unreadable == null) {
            LOG.error("The " + DD_NAME + " dataset yielded no result object at all for the unreadable-row "
                    + "probe, so a keyed read's INVALID KEY could not be established; reporting file "
                    + "status " + FileStatus.toStatusImage(PERMANENT_ERROR_STATUS)
                    + " rather than reporting as absent a record that may be present");
            return ReadResult.other(PERMANENT_ERROR_STATUS);
        }
        if (unreadable.isEmpty()) {
            return ReadResult.notFound();
        }
        LOG.error("A keyed read of the " + DD_NAME + " dataset matched no row, but the dataset holds a row "
                + "with no record image at column position " + RECORD_IMAGE_COLUMN_INDEX
                + " - and TRAN-CAT-KEY is part of that image, so that row's key cannot be known; reporting "
                + "file status " + FileStatus.toStatusImage(PERMANENT_ERROR_STATUS)
                + " rather than reporting as absent a category that may well be defined");
        return ReadResult.other(PERMANENT_ERROR_STATUS);
    }

    String resolveKeyedStatement() {
        return resolveStatements().selectByKey();
    }

    private Statements resolveStatements() {
        ResultSetExtractor<String> columnNameExtractor =
                TranCategoryRepository::extractRecordImageColumnName;
        String columnName = jdbcTemplate.query(relation.describeStatement(), columnNameExtractor);
        String recordImageColumn = relation.rememberRecordImageColumn(columnName);
        return new Statements(relation.selectByKey(recordImageColumn),
                relation.selectUnreadableRows(recordImageColumn));
    }

    String columnProbeSql() {
        return relation.describeStatement();
    }

    private static String extractRecordImageColumnName(ResultSet resultSet) throws SQLException {
        return DatasetRelation.recordImageColumnOf(resultSet.getMetaData());
    }

    private List<byte[]> fetch(String statement, String pattern) {
        PreparedStatementCreator creator = connection -> {
            PreparedStatement prepared = connection.prepareStatement(statement);
            prepared.setMaxRows(KEYED_READ_ROW_LIMIT);
            prepared.setFetchSize(KEYED_READ_ROW_LIMIT);
            recordImageForm.bindOperand(prepared, 1, pattern, codec.charset());
            return prepared;
        };
        RowMapper<byte[]> recordImageMapper = this::mapRecordImage;
        ResultSetExtractor<List<byte[]>> extractor = resultSet -> {
            List<byte[]> images = new ArrayList<>(KEYED_READ_ROW_LIMIT);
            int rowNumber = 0;
            while (images.size() < KEYED_READ_ROW_LIMIT && resultSet.next()) {
                images.add(recordImageMapper.mapRow(resultSet, rowNumber++));
            }
            return images;
        };
        return jdbcTemplate.query(creator, extractor);
    }

    private List<byte[]> fetchUnreadableRows(String probeStatement) {
        PreparedStatementCreator creator = connection -> {
            PreparedStatement prepared = connection.prepareStatement(probeStatement);
            prepared.setMaxRows(UNREADABLE_ROW_PROBE_LIMIT);
            prepared.setFetchSize(UNREADABLE_ROW_PROBE_LIMIT);
            return prepared;
        };
        RowMapper<byte[]> recordImageMapper = this::mapRecordImage;
        ResultSetExtractor<List<byte[]>> extractor = resultSet -> {
            List<byte[]> images = new ArrayList<>(UNREADABLE_ROW_PROBE_LIMIT);
            int rowNumber = 0;
            while (images.size() < UNREADABLE_ROW_PROBE_LIMIT && resultSet.next()) {
                images.add(recordImageMapper.mapRow(resultSet, rowNumber++));
            }
            return images;
        };
        return jdbcTemplate.query(creator, extractor);
    }

    private byte[] mapRecordImage(ResultSet resultSet, int rowNumber) throws SQLException {
        return recordImageForm.readImage(resultSet, RECORD_IMAGE_COLUMN_INDEX, codec.charset());
    }

    private static BackendDiagnostic logRefusal(Throwable refusal, String attempt) {
        BackendDiagnostic diagnostic = BackendDiagnostic.of(refusal);
        LOG.error("Could not " + attempt + " - " + diagnostic.describe() + "; reporting file status "
                + FileStatus.toStatusImage(PERMANENT_ERROR_STATUS) + " to the caller");
        return diagnostic;
    }

    /**
     * The discriminated outcome of one keyed read of {@code TRANCATG}: the classification
     * {@code 1500-C-LOOKUP-TRANCATG} performs, and nothing beyond it
     * ({@code app/cbl/CBTRN03C.cbl:504-512}).
     *
     * @param status the two-character {@code TRANCATG-STATUS} the read reported, verbatim: {@code '00'},
     *     {@code '23'}, or a permanent-error status
     * @param outcome its classification: {@link Outcome#OK}, {@link Outcome#NOT_FOUND} or
     *     {@link Outcome#OTHER}
     * @param record the decoded record, present exactly when {@code outcome} is {@link Outcome#OK}
     * @param diagnostic what the backend reported when it refused, present only on an {@link Outcome#OTHER}
     *     arm the driver described
     */
    public record ReadResult(String status,
                             Outcome outcome,
                             Optional<TranCategoryRecord> record,
                             Optional<BackendDiagnostic> diagnostic) {
        public ReadResult {
            Objects.requireNonNull(status, "A read result carries the two-character file status the read "
                    + "reported; it is never absent");
            Objects.requireNonNull(outcome, "A read result carries its classification; it is never "
                    + "absent");
            Objects.requireNonNull(record, "A read result carries an empty record rather than a null "
                    + "one, so no null escapes the type");
            Objects.requireNonNull(diagnostic, "A read result carries an empty diagnostic rather than a "
                    + "null one, so no null escapes the type");
            if (status.length() != FileStatus.STATUS_LENGTH) {
                throw new IllegalArgumentException("A file status is exactly "
                        + FileStatus.STATUS_LENGTH + " characters, as TRANCATG-STATUS is declared at "
                        + "app/cbl/" + CONSUMER_PROGRAM + ".cbl:109-111; got " + status.length() + ".");
            }
            if (outcome != Outcome.OK && outcome != Outcome.NOT_FOUND && outcome != Outcome.OTHER) {
                throw new IllegalArgumentException("The READ at app/cbl/" + CONSUMER_PROGRAM
                        + ".cbl:505-511 has two named outcomes - success and INVALID KEY - plus a "
                        + "catch-all, so a " + DD_NAME + " read is classified as OK, NOT_FOUND or OTHER. "
                        + "END_OF_FILE cannot arise under ACCESS MODE IS RANDOM and DUPLICATE has no "
                        + "counterpart on a unique KSDS key; got " + outcome + ".");
            }
            if (record.isPresent() != (outcome == Outcome.OK)) {
                throw new IllegalArgumentException(record.isPresent()
                        ? "A read that did not succeed carries no record: outcome " + outcome
                                + " was given one. Only the successful arm reaches TRAN-CAT-RECORD."
                        : "A successful read carries the decoded record, and this one carries none.");
            }
            String expectedForOutcome = outcome.batchStatus().orElse(null);
            if (expectedForOutcome != null && !expectedForOutcome.equals(status)) {
                throw new IllegalArgumentException("Outcome " + outcome + " corresponds to status '"
                        + expectedForOutcome + "', but status '" + status + "' was given; the status and "
                        + "its classification must agree.");
            }
            if (expectedForOutcome == null
                    && (FileStatus.isOk(status) || FileStatus.isNotFound(status))) {
                throw new IllegalArgumentException("Status '" + status + "' is one of the two statuses "
                        + "this READ names explicitly, so it cannot be classified as the catch-all "
                        + "arm.");
            }
        }

        /**
         * The successful arm: the {@code READ} completed and {@code TRAN-CAT-RECORD} holds the category.
         *
         * @param record the decoded record
         * @return a result carrying status {@link FileStatus#OK} and the record
         * @throws NullPointerException if {@code record} is {@code null}
         */
        public static ReadResult found(TranCategoryRecord record) {
            Objects.requireNonNull(record, "A successful read carries the decoded TRAN-CAT-RECORD");
            return new ReadResult(FileStatus.OK, Outcome.OK, Optional.of(record), Optional.empty());
        }

        /**
         * The {@code INVALID KEY} arm: status {@code '23'}, which is the value the COBOL itself moves into
         * {@code IO-STATUS} at {@code app/cbl/CBTRN03C.cbl:508} before rendering it and abending.
         *
         * @return a result carrying status {@link FileStatus#NOT_FOUND} and no record
         */
        public static ReadResult notFound() {
            return new ReadResult(FileStatus.NOT_FOUND, Outcome.NOT_FOUND, Optional.empty(),
                    Optional.empty());
        }

        /**
         * The catch-all arm, for a condition the {@code READ} does not name.
         *
         * @param status the two-character status, carried verbatim so the caller can render it exactly as
         *     {@code 9910-DISPLAY-IO-STATUS} does
         * @return a result carrying {@code status} and no record
         * @throws NullPointerException if {@code status} is {@code null}
         * @throws IllegalArgumentException if {@code status} is not exactly two characters, or is one of
         *     the two statuses the {@code READ} names explicitly
         */
        public static ReadResult other(String status) {
            return new ReadResult(status, Outcome.OTHER, Optional.empty(), Optional.empty());
        }

        /**
         * The catch-all arm, carrying what the backend actually said about the refusal.
         *
         * @param status the permanent-error file status
         * @param diagnostic what the backend reported
         * @return the outcome
         * @throws NullPointerException if {@code diagnostic} is {@code null}
         */
        public static ReadResult other(String status, BackendDiagnostic diagnostic) {
            Objects.requireNonNull(diagnostic, "A diagnostic is required by this factory; use "
                    + "other(String) where there is no backend refusal to report");
            return new ReadResult(status, Outcome.OTHER, Optional.empty(), Optional.of(diagnostic));
        }

        /**
         * Whether the read succeeded and a category record is available.
         *
         * @return {@code true} for the successful arm
         */
        public boolean isFound() {
            return outcome == Outcome.OK;
        }

        /**
         * Whether the key named no record: the {@code INVALID KEY} condition at
         * {@code app/cbl/CBTRN03C.cbl:506}.
         *
         * @return {@code true} for the {@code '23'} arm
         */
        public boolean isNotFound() {
            return outcome == Outcome.NOT_FOUND;
        }

        /**
         * Whether the read failed in a way the {@code READ} does not name.
         *
         * @return {@code true} for the catch-all arm
         */
        public boolean isOther() {
            return outcome == Outcome.OTHER;
        }

        /**
         * Whether the COBOL path from this outcome reaches {@code 9999-ABEND-PROGRAM}.
         *
         * @return {@code false} only for the successful arm
         */
        public boolean abends() {
            return outcome != Outcome.OK;
        }

        /**
         * The four-character {@code IO-STATUS-04} image {@code 9910-DISPLAY-IO-STATUS} renders for this
         * status ({@code app/cbl/CBTRN03C.cbl:633-646}).
         *
         * @return exactly {@link FileStatus#STATUS_IMAGE_LENGTH} characters
         */
        public String statusImage() {
            return FileStatus.toStatusImage(status);
        }
    }
}
