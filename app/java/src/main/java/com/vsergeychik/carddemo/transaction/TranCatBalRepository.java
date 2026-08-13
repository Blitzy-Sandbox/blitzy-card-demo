package com.vsergeychik.carddemo.transaction;

import com.vsergeychik.carddemo.common.CicsResponse;
import com.vsergeychik.carddemo.common.CobolDecimal;
import com.vsergeychik.carddemo.common.DatasetRelation;
import com.vsergeychik.carddemo.common.DatasetRelation.BackendDiagnostic;
import com.vsergeychik.carddemo.common.DatasetRelation.KeySpan;
import com.vsergeychik.carddemo.common.FileStatus;
import com.vsergeychik.carddemo.common.FileStatus.Outcome;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.RecordImageForm;
import com.vsergeychik.carddemo.common.SensitiveDiagnostics;
import com.vsergeychik.carddemo.config.CobolCharsetConfig;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBinding;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBindings;
import com.vsergeychik.carddemo.common.DatasetUnitOfWork;
import com.vsergeychik.carddemo.transaction.model.TranCatBalRecord;
import com.vsergeychik.carddemo.transaction.model.TranCatBalRecord.TranCatKey;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.core.PreparedStatementSetter;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.math.RoundingMode;
import java.nio.charset.Charset;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * The transaction category balance dataset, {@code TCATBALF}, reached over JDBC: one repository for the
 * five access paths the COBOL estate drives against it, and nothing beyond them.
 *
 * <p>Every record of {@code app/data/ASCII/tcatbal.txt} measures exactly 50 bytes.
 */
@Repository
public class TranCatBalRepository {
    private static final Log LOG = LogFactory.getLog(TranCatBalRepository.class);

    /**
     * The DD name under which both consumers bind this dataset: {@value}.
     */
    public static final String DD_NAME = TranCatBalRecord.DD_NAME;

    /**
     * The copybook that defines the record: {@value}.
     */
    public static final String COPYBOOK = TranCatBalRecord.COPYBOOK;

    /**
     * The record width, always 50 bytes - the {@code RECLN = 50} of {@code app/cpy/CVTRA01Y.cpy}.
     */
    public static final int RECORD_LENGTH = TranCatBalRecord.RECORD_LENGTH;

    /**
     * The key width, always 17 bytes - {@code TRANCAT-ACCT-ID 9(11)} plus {@code TRANCAT-TYPE-CD X(02)}
     * plus {@code TRANCAT-CD 9(04)}.
     */
    public static final int KEY_LENGTH = TranCatBalRecord.TRAN_CAT_KEY_LENGTH;

    /**
     * The key width of {@code app/cpy/CVTRA02Y.cpy}'s {@code DIS-GROUP-KEY}: 16 bytes.
     */
    public static final int DISCLOSURE_GROUP_KEY_LENGTH = 16;

    /**
     * The width of {@code app/cpy/CVTRA04Y.cpy}'s identically-named {@code TRAN-CAT-KEY}: 6 bytes.
     */
    public static final int TRAN_CATEGORY_KEY_LENGTH = 6;

    /**
     * The 0-based offset of the key within the record image: {@code 0}.
     */
    public static final int KEY_OFFSET = TranCatBalRecord.TRAN_CAT_KEY_OFFSET;

    /**
     * The scale every balance this repository stores and returns carries: exactly
     * {@value com.vsergeychik.carddemo.common.CobolDecimal#MONETARY_SCALE}.
     */
    public static final int TRAN_CAT_BAL_SCALE = CobolDecimal.MONETARY_SCALE;

    /**
     * The rounding every balance this repository stores is subject to: {@link RoundingMode#DOWN}.
     */
    public static final RoundingMode TRAN_CAT_BAL_ROUNDING = CobolDecimal.COBOL_ROUNDING;

    public static final int RECORD_IMAGE_COLUMN_INDEX = DatasetRelation.RECORD_IMAGE_COLUMN_INDEX;

    /**
     * The {@code APPL-RESULT} value both consumers move on a fatal I/O outcome: {@value}.
     */
    public static final int APPL_RESULT_FATAL = 12;

    private static final char PERMANENT_ERROR_FEEDBACK_CODE = 0;

    /**
     * The status this repository reports for a failure the COBOL vocabulary has no specific code for - an
     * unreachable dataset, a refused statement, an unreadable row, or a row of the wrong width.
     */
    public static final String PERMANENT_ERROR_STATUS = "9" + PERMANENT_ERROR_FEEDBACK_CODE;

    private static final KeySpan KEY_SPAN = new KeySpan(KEY_OFFSET, KEY_LENGTH);

    private static final int FAN_OUT_PROBE_LIMIT = 2;

    static {
        verifyDeclaredGeometry();
    }

    private final JdbcTemplate jdbcTemplate;

    private final FixedWidthCodec codec;

    private final String datasetName;

    private final DatasetRelation relation;

    private final RecordImageForm recordImageForm;

    /**
     * Resolves the configured binding, proves the record and key geometry, and captures the collaborators -
     * all before the context finishes starting, so nothing checkable is left to fail mid-job.
     *
     * @param jdbcTemplate the module's shared template, from the data-access configuration
     * @param datasetBindings the DD-name-keyed dataset catalogue bound from {@code carddemo.datasets}
     * @param datasetCharset the active dataset code page, selected by bean name so the choice is explicit
     *     at the injection point
     * @param recordImageForm how the deployment's driver presents a record image over JDBC, from
     *     {@value RecordImageForm#FORM_PROPERTY}; the module's single authority on that, so this class never
     *     chooses a JDBC type for itself
     * @throws NullPointerException if any argument is {@code null}
     * @throws IllegalStateException if {@link #DD_NAME} is unconfigured, if the binding declares a record
     *     width other than {@link #RECORD_LENGTH} or a key width other than {@link #KEY_LENGTH}, or if the
     *     resolved dataset name is unusable
     */
    public TranCatBalRepository(
            JdbcTemplate jdbcTemplate,
            DatasetBindings datasetBindings,
            @Qualifier(CobolCharsetConfig.DATASET_CHARSET_BEAN_NAME) Charset datasetCharset,
            RecordImageForm recordImageForm) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "A JdbcTemplate is required: the "
                + "transaction category balance dataset is reached through the module's shared template");
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

        DatasetBinding binding = requireTranCatBalGeometry(datasetBindings);
        this.relation = DatasetRelation.of(requireUsableDatasetName(binding.dsname()), RECORD_LENGTH);
        this.datasetName = this.relation.dsname();
    }

    /**
     * The resolved dataset name, exactly as configuration declares it.
     *
     * @return the configured dataset name; never {@code null} and never blank
     */
    public String datasetName() {
        return datasetName;
    }

    /**
     * The record width this repository reads and writes, always {@link #RECORD_LENGTH}.
     *
     * @return 50
     */
    public int recordLength() {
        return RECORD_LENGTH;
    }

    /**
     * The key width this repository addresses records by, always {@link #KEY_LENGTH}.
     *
     * @return 17
     */
    public int keyLength() {
        return KEY_LENGTH;
    }

    /**
     * The code page in which this repository encodes and decodes record images.
     *
     * @return the injected dataset code page; never {@code null}
     */
    public Charset datasetCharset() {
        return codec.charset();
    }

    /**
     * Opens the transaction category balance dataset and hands back the handle that stands for that open.
     *
     * @param mode the {@code OPEN} verb being issued
     * @return a freshly positioned handle whose {@link TranCatBalFile#openStatus()} reports whether the
     *     dataset was addressable; never {@code null}
     * @throws NullPointerException if {@code mode} is {@code null}
     * @throws IllegalStateException if the backend presents the dataset with no usable record-image column,
     *     which is a contract violation rather than an I/O outcome
     */
    public TranCatBalFile open(OpenMode mode) {
        Objects.requireNonNull(mode, "An open mode is required: the estate opens this dataset as "
                + OpenMode.INPUT.cobolVerb() + " or as " + OpenMode.I_O.cobolVerb() + ", and which verb "
                + "was issued is part of what the program did");

        this.relation.forgetRecordImageColumn();

        Statements resolved;
        try {
            resolved = resolveStatements();
        } catch (DataAccessException unreachable) {
            logRefusal(unreachable, "describe the transaction category balance dataset '" + datasetName
                    + "' to " + mode.cobolVerb() + " it");
            return new TranCatBalFile(this, mode, PERMANENT_ERROR_STATUS, null);
        }
        return new TranCatBalFile(this, mode, FileStatus.OK, resolved);
    }

    /**
     * Reads one record by its three key components: the Java form of
     * {@code app/cbl/CBTRN02C.cbl:L469-L479}, The three parameters are the three COBOL sources, in copybook
     * order and in their own COBOL types.
     *
     * @param acctId the account identifier, {@code TRANCAT-ACCT-ID PIC 9(11)}; must not be negative, as
     *     {@code PIC 9} declares no sign position and has no representation for one
     * @param typeCd the transaction type code, {@code TRANCAT-TYPE-CD PIC X(02)}; used verbatim, padded and
     *     truncated on the right as {@code PIC X} requires
     * @param catCd the transaction category code, {@code TRANCAT-CD PIC 9(04)}; must not be negative
     * @return the discriminated outcome; never {@code null}
     * @throws NullPointerException if {@code typeCd} is {@code null}
     * @throws IllegalArgumentException if either numeric component is negative
     * @throws IllegalStateException if the backend presents the dataset with no usable record-image column
     */
    public ReadResult readByKey(long acctId, String typeCd, int catCd) {
        return readByKey(new TranCatKey(acctId, typeCd, catCd));
    }

    public ReadResult readByKey(TranCatKey key) {
        Objects.requireNonNull(key, "A TRAN-CAT-KEY is required to read TCATBALF by key; the COBOL sets "
                + "all three components before the READ (app/cbl/CBTRN02C.cbl:L469-L471)");

        Statements sql;
        try {
            sql = resolveStatements();
        } catch (DataAccessException unreachable) {
            return reportRead(unreachable, "describe the transaction category balance dataset '"
                    + datasetName + "' to read a record by key");
        }
        return readByKey(sql, key);
    }

    private ReadResult readByKey(Statements sql, TranCatKey key) {
        String keyImage = key.image(codec.charset());
        String subject = describeKey(keyImage);

        List<byte[]> rows;
        try {
            rows = jdbcTemplate.query(firstRowMatching(sql.selectByKey(), KEY_SPAN.pattern(keyImage)),
                    recordImageMapper());
        } catch (DataAccessException translated) {
            return reportRead(translated, "read " + subject + " from the transaction category balance "
                    + "dataset '" + datasetName + "'");
        }

        if (rows.isEmpty()) {
            // All three components of TRAN-CAT-KEY live inside the record image (app/cpy/CVTRA01Y.cpy:5-8),
            // so a row whose record-image column holds nothing has no knowable key and the keyed LIKE
            // predicate cannot match it: SQL evaluates every comparison against a null as UNKNOWN.
            return provenAbsence(sql.probeUnreadableRows(), subject);
        }
        byte[] recordImage = rows.get(0);
        if (recordImage == null) {
            LOG.error("The transaction category balance dataset '" + datasetName + "' presented "
                    + subject + " with no record image at column position " + RECORD_IMAGE_COLUMN_INDEX
                    + "; reporting file status " + FileStatus.toStatusImage(PERMANENT_ERROR_STATUS)
                    + " to the caller");
            return ReadResult.of(PERMANENT_ERROR_STATUS, CicsResponse.of(FileStatus.INVREQ));
        }
        return decoded(recordImage, subject);
    }

    private ReadResult provenAbsence(String unreadableRowsProbe, String subject) {
        List<byte[]> unreadable;
        try {
            unreadable = jdbcTemplate.query(firstRow(unreadableRowsProbe), recordImageMapper());
        } catch (DataAccessException translated) {
            return reportRead(translated, "establish that the transaction category balance dataset '"
                    + datasetName + "' holds no unreadable row before reporting " + subject
                    + " as absent - which would authorise 2700-A-CREATE-TCATBAL-REC to write it");
        }
        if (unreadable.isEmpty()) {
            return ReadResult.notFound();
        }
        LOG.error("A keyed read of " + subject + " from the transaction category balance dataset '"
                + datasetName + "' matched no row, but the dataset holds a row with no record image at "
                + "column position " + RECORD_IMAGE_COLUMN_INDEX + " - and TRAN-CAT-KEY is part of that "
                + "image, so that row's key cannot be known; reporting file status "
                + FileStatus.toStatusImage(PERMANENT_ERROR_STATUS)
                + " rather than the INVALID KEY that would have a balance written for a category that may "
                + "already have one");
        return ReadResult.of(PERMANENT_ERROR_STATUS, CicsResponse.of(FileStatus.INVREQ));
    }

    /**
     * Adds a record that does not yet exist: the Java form of
     * {@code WRITE FD-TRAN-CAT-BAL-RECORD FROM TRAN-CAT-BAL-RECORD} ({@code app/cbl/CBTRN02C.cbl:L510}).
     *
     * @param record the record to add, complete and already mutated by the caller
     * @return the discriminated outcome; never {@code null}
     * @throws NullPointerException if {@code record} is {@code null}
     * @throws IllegalStateException if no unit of work is open, in which case nothing has been attempted -
     *     see {@link DatasetUnitOfWork#requireActiveToPersist(String, String)}
     */
    public WriteResult write(TranCatBalRecord record) {
        Objects.requireNonNull(record, "A record is required to write it; a COBOL WRITE writes the record "
                + "area, and there is no such thing as writing nothing");

        Statements sql;
        try {
            sql = resolveStatements();
        } catch (DataAccessException unreachable) {
            return reportWrite(unreachable, "describe the transaction category balance dataset '"
                    + datasetName + "' to write a record");
        }
        return write(sql, record);
    }

    private WriteResult write(Statements sql, TranCatBalRecord record) {
        byte[] recordImage = requireStorableImage(record, "written");
        DatasetUnitOfWork.requireActiveToPersist("A write to the transaction category balance file, which "
                + "WRITE issues from the record area 2700-A-CREATE-TCATBAL-REC has just built "
                + "(app/cbl/CBTRN02C.cbl:L510)", datasetName);
        String keyImage = record.tranCatKeyImage();
        String keyPattern = KEY_SPAN.pattern(keyImage);
        String subject = describeKey(keyImage);

        int existing;
        try {
            existing = matchingRowCount(sql.selectByKey(), keyPattern);
        } catch (DataAccessException rejected) {
            return reportWrite(rejected, "establish whether " + subject + " already exists in the "
                    + "transaction category balance dataset '" + datasetName + "' before writing it");
        }
        if (existing > 0) {
            LOG.warn("A record for " + subject + " already exists in the transaction category balance "
                    + "dataset '" + datasetName + "', so the write is being refused before it is issued "
                    + "and file status " + FileStatus.toStatusImage(FileStatus.DUPLICATE)
                    + " reported to the caller. No row has been added.");
            return WriteResult.duplicate();
        }

        int inserted;
        try {
            PreparedStatementSetter binder = parameters ->
                    recordImageForm.bindImage(parameters, 1, recordImage, codec.charset());
            inserted = jdbcTemplate.update(sql.insert(), binder);
        } catch (DataAccessException rejected) {
            BackendDiagnostic diagnostic = BackendDiagnostic.of(rejected);
            if (diagnostic.integrityViolation()) {
                LOG.warn("The transaction category balance dataset '" + datasetName + "' refused a write "
                        + "of " + subject + " as an integrity violation - " + diagnostic.describe()
                        + "; reporting file status " + FileStatus.toStatusImage(FileStatus.DUPLICATE)
                        + " to the caller");
                return WriteResult.of(FileStatus.DUPLICATE, CicsResponse.of(FileStatus.DUPREC),
                        diagnostic);
            }
            return reportWrite(rejected, "write " + subject + " to the transaction category balance "
                    + "dataset '" + datasetName + "'");
        }

        if (inserted == 1) {
            return WriteResult.written();
        }
        if (inserted == 0) {
            LOG.error("A write of " + subject + " to the transaction category balance dataset '"
                    + datasetName + "' reported that no row was added, although the key selected none "
                    + "beforehand; reporting file status "
                    + FileStatus.toStatusImage(PERMANENT_ERROR_STATUS) + " to the caller");
            return WriteResult.of(PERMANENT_ERROR_STATUS, CicsResponse.of(FileStatus.INVREQ));
        }
        throw DatasetUnitOfWork.commitRefusal(
                "The write of " + subject + " to dataset '" + datasetName + "'",
                inserted + " rows were added where one record was written, so the relation is not the "
                        + "single-record-image dataset app/cpy/" + COPYBOOK + ".cpy describes");
    }

    /**
     * Rewrites one record in place: the Java form of
     * {@code REWRITE FD-TRAN-CAT-BAL-RECORD FROM TRAN-CAT-BAL-RECORD} ({@code app/cbl/CBTRN02C.cbl:L528}).
     *
     * <p>Unlike the account and card masters, neither consumer of this dataset performs a
     * {@code 9300-CHECK-CHANGE-IN-REC}-style comparison either - {@code CBTRN02C} reads and rewrites within
     * one batch step - so there is no concurrency check here to preserve or to substitute.
     *
     * @param record the record to write, complete and already mutated by the caller
     * @return the discriminated outcome; never {@code null}
     * @throws NullPointerException if {@code record} is {@code null}
     * @throws IllegalStateException if no unit of work is open, in which case nothing has been attempted;
     *     if the backend presents the dataset with no usable record-image column; or
     */
    public WriteResult rewrite(TranCatBalRecord record) {
        Objects.requireNonNull(record, "A record is required to rewrite it; a COBOL REWRITE writes the "
                + "record area, and there is no such thing as rewriting nothing");

        Statements sql;
        try {
            sql = resolveStatements();
        } catch (DataAccessException unreachable) {
            return reportWrite(unreachable, "describe the transaction category balance dataset '"
                    + datasetName + "' to rewrite a record");
        }
        return rewrite(sql, record);
    }

    private WriteResult rewrite(Statements sql, TranCatBalRecord record) {
        byte[] recordImage = requireStorableImage(record, "rewritten");
        DatasetUnitOfWork.requireActiveToPersist("A rewrite of the transaction category balance file, "
                + "which REWRITE issues against the record area 2700-B-UPDATE-TCATBAL-REC has just "
                + "accumulated into (app/cbl/CBTRN02C.cbl:L528)", datasetName);
        String keyImage = record.tranCatKeyImage();
        String keyPattern = KEY_SPAN.pattern(keyImage);
        String subject = describeKey(keyImage);

        int matching;
        try {
            matching = matchingRowCount(sql.selectByKeyForUpdate(), keyPattern);
        } catch (DataAccessException rejected) {
            return reportWrite(rejected, "establish how many rows the key of " + subject + " selects in "
                    + "the transaction category balance dataset '" + datasetName + "' before rewriting it");
        }
        if (matching == 0) {
            return WriteResult.notFound();
        }
        if (matching > 1) {
            LOG.error("The key of " + subject + " selects more than one row of the transaction category "
                    + "balance dataset '" + datasetName + "'; a KSDS primary key is unique, so the "
                    + "rewrite is being refused before it is issued and file status "
                    + FileStatus.toStatusImage(PERMANENT_ERROR_STATUS) + " reported to the caller. No "
                    + "row has been changed.");
            return WriteResult.of(PERMANENT_ERROR_STATUS, CicsResponse.of(FileStatus.INVREQ));
        }

        int rewritten;
        try {
            PreparedStatementSetter binder = parameters -> {
                recordImageForm.bindImage(parameters, 1, recordImage, codec.charset());
                recordImageForm.bindOperand(parameters, 2, keyPattern, codec.charset());
            };
            rewritten = jdbcTemplate.update(sql.rewrite(), binder);
        } catch (DataAccessException rejected) {
            return reportWrite(rejected, "rewrite " + subject + " in the transaction category balance "
                    + "dataset '" + datasetName + "'");
        }

        if (rewritten == 1) {
            return WriteResult.written();
        }
        if (rewritten == 0) {
            return WriteResult.notFound();
        }
        throw DatasetUnitOfWork.commitRefusal(
                "The rewrite of " + subject + " in dataset '" + datasetName + "'",
                rewritten + " rows were replaced where the key selected exactly one when it was checked "
                        + "under a row lock");
    }

    private int matchingRowCount(String statement, String pattern) {
        ResultSetExtractor<Integer> rowCounter = resultSet -> {
            int counted = 0;
            while (counted < FAN_OUT_PROBE_LIMIT && resultSet.next()) {
                counted++;
            }
            return counted;
        };
        Integer counted = jdbcTemplate.query(boundedMatching(statement, pattern, FAN_OUT_PROBE_LIMIT),
                rowCounter);
        return counted == null ? FAN_OUT_PROBE_LIMIT : counted;
    }

    private BackendDiagnostic logRefusal(Throwable refusal, String attempt) {
        BackendDiagnostic diagnostic = BackendDiagnostic.of(refusal);
        LOG.error("Could not " + attempt + " - " + diagnostic.describe() + "; reporting file status "
                + FileStatus.toStatusImage(PERMANENT_ERROR_STATUS) + " to the caller");
        return diagnostic;
    }

    private ReadResult reportRead(Throwable refusal, String attempt) {
        return ReadResult.of(PERMANENT_ERROR_STATUS, logRefusal(refusal, attempt));
    }

    private WriteResult reportWrite(Throwable refusal, String attempt) {
        return WriteResult.of(PERMANENT_ERROR_STATUS, logRefusal(refusal, attempt));
    }

    Statements resolveStatements() {
        ResultSetExtractor<String> columnNameExtractor =
                TranCatBalRepository::extractRecordImageColumnName;
        String columnName = jdbcTemplate.query(relation.describeStatement(), columnNameExtractor);
        return Statements.over(relation, requireUsableColumnName(columnName));
    }

    private Statements statementsFor(String subject) {
        try {
            return resolveStatements();
        } catch (DataAccessException unreachable) {
            logRefusal(unreachable, "describe the transaction category balance dataset '" + datasetName
                    + "' for " + subject);
            return null;
        }
    }

    private static String extractRecordImageColumnName(ResultSet resultSet) throws SQLException {
        return DatasetRelation.recordImageColumnOf(resultSet.getMetaData());
    }

    private RowMapper<byte[]> recordImageMapper() {
        return (resultSet, rowNumber) ->
                recordImageForm.readImage(resultSet, RECORD_IMAGE_COLUMN_INDEX, codec.charset());
    }

    private PreparedStatementCreator firstRow(String sql) {
        return connection -> limited(connection.prepareStatement(sql));
    }

    private PreparedStatementCreator firstRowMatching(String sql, String pattern) {
        return connection -> {
            PreparedStatement statement = limited(connection.prepareStatement(sql));
            recordImageForm.bindOperand(statement, 1, pattern, codec.charset());
            return statement;
        };
    }

    private PreparedStatementCreator boundedMatching(String sql, String pattern, int maxRows) {
        return connection -> {
            PreparedStatement statement = connection.prepareStatement(sql);
            statement.setMaxRows(maxRows);
            recordImageForm.bindOperand(statement, 1, pattern, codec.charset());
            return statement;
        };
    }

    private PreparedStatementCreator firstRowAfter(String sql, byte[] position) {
        return connection -> {
            PreparedStatement statement = limited(connection.prepareStatement(sql));
            recordImageForm.bindImage(statement, 1, position, codec.charset());
            return statement;
        };
    }

    private static PreparedStatement limited(PreparedStatement statement) throws SQLException {
        statement.setMaxRows(1);
        return statement;
    }

    private ReadResult decoded(byte[] recordImage, String subject) {
        if (recordImage.length != RECORD_LENGTH) {
            LOG.error("The transaction category balance dataset '" + datasetName + "' presented " + subject
                    + " as " + recordImage.length + " byte(s), but TRAN-CAT-BAL-RECORD is declared "
                    + RECORD_LENGTH + " bytes by app/cpy/" + COPYBOOK + ".cpy (a " + KEY_LENGTH
                    + "-byte TRAN-CAT-KEY, an 11-byte TRAN-CAT-BAL and a 22-byte FILLER); reporting file "
                    + "status " + FileStatus.toStatusImage(PERMANENT_ERROR_STATUS) + " rather than "
                    + "decoding fields from offsets that would not be theirs");
            return ReadResult.of(PERMANENT_ERROR_STATUS, CicsResponse.of(FileStatus.LENGERR));
        }
        return ReadResult.found(TranCatBalRecord.decode(recordImage, codec.charset()));
    }

    private static byte[] requireStorableImage(TranCatBalRecord record, String verb) {
        byte[] recordImage = record.encode();
        if (recordImage.length != RECORD_LENGTH) {
            throw new IllegalStateException("A record about to be " + verb + " encodes to "
                    + recordImage.length + " byte(s), but TRAN-CAT-BAL-RECORD is declared "
                    + RECORD_LENGTH + " bytes by app/cpy/" + COPYBOOK + ".cpy. Every span is written, "
                    + "FILLER X(22) included, so an image of any other width means the record's own "
                    + "layout is wrong - storing it would leave a row that no read could decode.");
        }
        return recordImage;
    }

    private static String describeKey(String keyImage) {
        return "the transaction category balance key '" + SensitiveDiagnostics.maskIdentifier(keyImage)
                + "'";
    }

    private static DatasetBinding requireTranCatBalGeometry(DatasetBindings datasetBindings) {
        DatasetBinding binding = datasetBindings.binding(DD_NAME);
        if (binding.recordLength() != RECORD_LENGTH) {
            throw new IllegalStateException("Dataset binding for DD name '" + DD_NAME + "' declares a "
                    + "record length of " + binding.recordLength() + ", but TRAN-CAT-BAL-RECORD is "
                    + RECORD_LENGTH + " bytes - app/cpy/" + COPYBOOK + ".cpy reads RECLN = 50, and both "
                    + "app/cbl/CBTRN02C.cbl:L91-L97 and app/cbl/CBACT04C.cbl:L61-L67 declare a "
                    + KEY_LENGTH + "-byte FD-TRAN-CAT-KEY plus FD-FD-TRAN-CAT-DATA PIC X(33) "
                    + "independently. This repository addresses the record by absolute offset, so a "
                    + "differently-sized record would misplace every field after the first. Correct "
                    + "carddemo.datasets." + DD_NAME + ".record-length to " + RECORD_LENGTH + ".");
        }
        Integer declaredKeyLength = binding.keyLength();
        // A key width is configured only where a source file states one, so an absent one is normal and is
        // accepted; a PRESENT one that disagrees with the copybook is a defect - and for THIS dataset it is
        // the defect that a width check cannot otherwise catch.
        if (declaredKeyLength != null && declaredKeyLength != KEY_LENGTH) {
            throw new IllegalStateException("Dataset binding for DD name '" + DD_NAME + "' declares a key "
                    + "length of " + declaredKeyLength + ", but TRAN-CAT-KEY is " + KEY_LENGTH + " bytes "
                    + "- TRANCAT-ACCT-ID 9(11) + TRANCAT-TYPE-CD X(02) + TRANCAT-CD 9(04) in app/cpy/"
                    + COPYBOOK + ".cpy, which is also what RECORD KEY IS FD-TRAN-CAT-KEY names at "
                    + "app/cbl/CBTRN02C.cbl:L60. BEWARE THE CVTRA02Y TRAP: DIS-GROUP-RECORD also totals "
                    + RECORD_LENGTH + " bytes but its DIS-GROUP-KEY is only "
                    + DISCLOSURE_GROUP_KEY_LENGTH + ", so a declared "
                    + DISCLOSURE_GROUP_KEY_LENGTH + " here would read this dataset through the "
                    + "disclosure group's key and silently corrupt every balance. Correct "
                    + "carddemo.datasets." + DD_NAME + ".key-length to " + KEY_LENGTH + ", or omit it.");
        }
        return binding;
    }

    static int verifyDeclaredGeometry() {
        TranCatBalRecord.verifyGeometry();
        TranCatBalRecord.verifyRecordLength(RECORD_LENGTH, TranCatBalRecord.LAYOUT.recordLength());
        TranCatBalRecord.verifyKeyGeometry(KEY_LENGTH, TranCatBalRecord.TRAN_CAT_BAL_OFFSET);
        if (KEY_LENGTH == DISCLOSURE_GROUP_KEY_LENGTH) {
            throw new IllegalStateException("TCATBALF's TRAN-CAT-KEY is " + KEY_LENGTH + " bytes and must "
                    + "never be " + DISCLOSURE_GROUP_KEY_LENGTH + ". app/cpy/CVTRA02Y.cpy's "
                    + "DIS-GROUP-RECORD also totals " + RECORD_LENGTH + " bytes, so no width check can "
                    + "distinguish the two layouts: a " + DISCLOSURE_GROUP_KEY_LENGTH + "-byte key here "
                    + "would read TRAN-CAT-BAL from 0-based " + DISCLOSURE_GROUP_KEY_LENGTH
                    + " for 6 bytes instead of from " + TranCatBalRecord.TRAN_CAT_BAL_OFFSET
                    + " for 11, and every balance would be silently wrong.");
        }
        if (KEY_LENGTH == TRAN_CATEGORY_KEY_LENGTH) {
            throw new IllegalStateException("TCATBALF's TRAN-CAT-KEY is " + KEY_LENGTH + " bytes and must "
                    + "never be " + TRAN_CATEGORY_KEY_LENGTH + ". app/cpy/CVTRA04Y.cpy declares a group "
                    + "of the identical COBOL name TRAN-CAT-KEY that is only "
                    + TRAN_CATEGORY_KEY_LENGTH + " bytes - TRAN-TYPE-CD X(02) + TRAN-CAT-CD 9(04), with "
                    + "no account identifier - and it belongs to TranCategoryRecord, not to this "
                    + "dataset.");
        }
        verifyBalancePolicy();
        return KEY_LENGTH;
    }

    private static int verifyBalancePolicy() {
        if (TRAN_CAT_BAL_SCALE != TranCatBalRecord.TRAN_CAT_BAL_SCALE) {
            throw new IllegalStateException("TRAN-CAT-BAL is PIC S9(09)V99, so its scale is "
                    + TranCatBalRecord.TRAN_CAT_BAL_SCALE + ", but this repository declares "
                    + TRAN_CAT_BAL_SCALE + ". A balance stored at the wrong scale is wrong money.");
        }
        if (TRAN_CAT_BAL_ROUNDING != RoundingMode.DOWN) {
            throw new IllegalStateException("TRAN-CAT-BAL must be stored with RoundingMode.DOWN, but "
                    + TRAN_CAT_BAL_ROUNDING + " is configured. The keyword ROUNDED appears nowhere in any "
                    + "of the 28 COBOL programs, and absent ROUNDED the COBOL standard truncates excess "
                    + "fractional digits on store - so any rounding mode other than DOWN would round "
                    + "money that the legacy system truncates.");
        }
        return TRAN_CAT_BAL_SCALE;
    }

    private static String requireUsableDatasetName(String candidate) {
        if (candidate == null || candidate.isBlank()) {
            throw new IllegalStateException("The dataset binding for DD name '" + DD_NAME + "' declares "
                    + "no dataset name. Set carddemo.datasets." + DD_NAME + ".dsname; this repository "
                    + "composes its statements from configuration alone and hard-codes no dataset name.");
        }
        return DatasetRelation.requireDatasetName(candidate);
    }

    private static String requireUsableColumnName(String candidate) {
        if (candidate == null || candidate.isBlank()) {
            throw new IllegalStateException("The backend describes the transaction category balance "
                    + "dataset with no usable column at position " + RECORD_IMAGE_COLUMN_INDEX
                    + ", so there is no record-image column to read, rewrite or insert into. Every "
                    + "dataset in this module is reached as a single-column relation whose one column "
                    + "holds the whole " + RECORD_LENGTH + "-byte record image; a relation that is not "
                    + "cannot be addressed at all, and no column name is invented here to paper over "
                    + "it.");
        }
        return candidate;
    }

    /**
     * The six statements this repository issues, composed once the record-image column is known.
     *
     * @param selectFirst the first read of a browse: every record in ascending key order, limited to one
     *     row by the statement
     * @param selectNext every later read of a browse: the first record whose image sorts strictly after the
     *     one already returned, in ascending key order
     * @param selectByKey the keyed read: the record whose image begins with the 17-byte key
     * @param selectByKeyForUpdate the keyed read with the row lock, valid only inside a unit of work
     * @param rewrite the rewrite: replace the image of the record whose image begins with the key
     * @param insert the write: add one record image
     * @param probeUnreadableRows the rows whose record-image column holds nothing
     */
    record Statements(String selectFirst,
                      String selectNext,
                      String selectByKey,
                      String selectByKeyForUpdate,
                      String rewrite,
                      String insert,
                      String probeUnreadableRows) {
        static Statements over(DatasetRelation relation, String column) {
            String recordImageColumn = relation.rememberRecordImageColumn(column);
            return new Statements(
                    relation.selectAllAscending(recordImageColumn),
                    relation.selectAfterAscending(recordImageColumn),
                    relation.selectByKey(recordImageColumn),
                    relation.selectByKeyForUpdate(recordImageColumn),
                    relation.rewriteByKey(recordImageColumn),
                    relation.insertRecordImage(recordImageColumn),
                    relation.selectUnreadableRows(recordImageColumn));
        }
    }

    /**
     * The {@code OPEN} verbs the estate issues against this dataset, and the only two it issues.
     */
    public enum OpenMode {
        /**
         * {@code OPEN INPUT}: read-only, for the sequential browse that drives the interest calculator
         * ({@code app/cbl/CBACT04C.cbl:L236}).
         */
        INPUT("OPEN INPUT"),

        /**
         * {@code OPEN I-O}: read and write, for the create-or-update pair in the transaction poster
         * ({@code app/cbl/CBTRN02C.cbl:L329}).
         */
        I_O("OPEN I-O");

        private final String cobolVerb;

        OpenMode(String cobolVerb) {
            this.cobolVerb = cobolVerb;
        }

        /**
         * The COBOL verb this mode stands for, verbatim.
         *
         * @return {@code "OPEN INPUT"} or {@code "OPEN I-O"}
         */
        public String cobolVerb() {
            return cobolVerb;
        }
    }

    /**
     * One open of the transaction category balance dataset - a COBOL {@code FD} for this dataset, as a Java
     * object.
     */
    public static final class TranCatBalFile implements AutoCloseable {
        private final TranCatBalRepository repository;

        private final OpenMode mode;

        private final String openStatus;

        private final Statements statements;

        private byte[] browsePosition;

        private boolean closed;

        private TranCatBalFile(TranCatBalRepository repository, OpenMode mode, String openStatus,
                Statements statements) {
            this.repository = repository;
            this.mode = mode;
            this.openStatus = openStatus;
            this.statements = statements;
            this.browsePosition = null;
            this.closed = false;
        }

        /**
         * The status the {@code OPEN} reported - the quantity {@code IF TCATBALF-STATUS = '00'} tests at
         * {@code app/cbl/CBACT04C.cbl:L237} and {@code app/cbl/CBTRN02C.cbl:L330}.
         *
         * @return {@link FileStatus#OK} or {@link TranCatBalRepository#PERMANENT_ERROR_STATUS}
         */
        public String openStatus() {
            return openStatus;
        }

        public Outcome openOutcome() {
            return FileStatus.outcomeOfStatus(openStatus);
        }

        /**
         * The {@code APPL-RESULT} value the open's guard moves: {@code 0} on success, {@code 12} otherwise.
         *
         * @return {@link FileStatus#APPL_AOK} or {@link TranCatBalRepository#APPL_RESULT_FATAL}
         */
        public int openApplResult() {
            return FileStatus.isOk(openStatus) ? FileStatus.APPL_AOK : APPL_RESULT_FATAL;
        }

        public OpenMode mode() {
            return mode;
        }

        public String datasetName() {
            return repository.datasetName();
        }

        /**
         * Whether {@link #closeFile()} has been called on this handle.
         *
         * @return {@code true} once the file has been closed
         */
        public boolean isClosed() {
            return closed;
        }

        /**
         * Reads the next record in ascending key order: the Java form of
         * {@code READ TCATBAL-FILE INTO TRAN-CAT-BAL-RECORD} ({@code app/cbl/CBACT04C.cbl:L326}) and of the
         * guard that classifies its status at {@code L327-L347}.
         *
         * @return the discriminated outcome; never {@code null}
         * @throws IllegalStateException if this handle has been closed
         */
        public ReadResult readNext() {
            requireOpen("read the next record");
            if (statements == null) {
                return ReadResult.of(openStatus);
            }

            byte[] position = this.browsePosition;
            boolean fromStart = position == null;
            String statement = fromStart ? statements.selectFirst() : statements.selectNext();

            List<byte[]> rows;
            try {
                PreparedStatementCreator creator = fromStart
                        ? repository.firstRow(statement)
                        : repository.firstRowAfter(statement, position);
                rows = repository.jdbcTemplate.query(creator, repository.recordImageMapper());
            } catch (DataAccessException translated) {
                LOG.error("Rejected browse read of the transaction category balance dataset '"
                        + datasetName() + "' - " + BackendDiagnostic.of(translated).describe()
                        + "; reporting file status "
                        + FileStatus.toStatusImage(PERMANENT_ERROR_STATUS) + " to the caller");
                return ReadResult.of(PERMANENT_ERROR_STATUS);
            }

            if (rows.isEmpty()) {
                return ReadResult.endOfFile();
            }
            byte[] recordImage = rows.get(0);
            if (recordImage == null) {
                LOG.error("The transaction category balance dataset '" + datasetName() + "' presented a "
                        + "browsed row with no record image at column position "
                        + RECORD_IMAGE_COLUMN_INDEX + "; reporting file status "
                        + FileStatus.toStatusImage(PERMANENT_ERROR_STATUS) + " to the caller");
                return ReadResult.of(PERMANENT_ERROR_STATUS, CicsResponse.of(FileStatus.INVREQ));
            }

            ReadResult result = repository.decoded(recordImage, "a browsed row");
            if (result.isFound()) {
                this.browsePosition = recordImage;
            }
            return result;
        }

        /**
         * Reads one record by its three key components, against this open's resolved statements.
         *
         * @param acctId the account identifier, {@code TRANCAT-ACCT-ID PIC 9(11)}
         * @param typeCd the transaction type code, {@code TRANCAT-TYPE-CD PIC X(02)}
         * @param catCd the transaction category code, {@code TRANCAT-CD PIC 9(04)}
         * @return the discriminated outcome; never {@code null}
         * @throws NullPointerException if {@code typeCd} is {@code null}
         * @throws IllegalArgumentException if either numeric component is negative
         * @throws IllegalStateException if this handle has been closed
         */
        public ReadResult readByKey(long acctId, String typeCd, int catCd) {
            return readByKey(new TranCatKey(acctId, typeCd, catCd));
        }

        /**
         * Reads one record by its composite key, against this open's resolved statements.
         *
         * @param key the 17-byte composite key
         * @return the discriminated outcome; never {@code null}
         * @throws NullPointerException if {@code key} is {@code null}
         * @throws IllegalArgumentException if either numeric component of {@code key} is negative
         * @throws IllegalStateException if this handle has been closed
         */
        public ReadResult readByKey(TranCatKey key) {
            requireOpen("read a record by key");
            Objects.requireNonNull(key, "A TRAN-CAT-KEY is required to read TCATBALF by key");
            if (statements == null) {
                return ReadResult.of(openStatus);
            }
            return repository.readByKey(statements, key);
        }

        /**
         * Adds a record that does not yet exist, against this open's resolved statements.
         *
         * @param record the record to add, complete and already mutated by the caller
         * @return the discriminated outcome; never {@code null}
         * @throws NullPointerException if {@code record} is {@code null}
         * @throws IllegalStateException if this handle has been closed, or if no unit of work is open - the
         *     step's own transaction is the boundary {@code CBTRN02C} reaches this inside
         */
        public WriteResult write(TranCatBalRecord record) {
            requireOpen("write a record");
            Objects.requireNonNull(record, "A record is required to write it");
            if (statements == null) {
                return WriteResult.of(openStatus);
            }
            return repository.write(statements, record);
        }

        /**
         * Rewrites one record in place, against this open's resolved statements.
         *
         * @param record the record to write, complete and already mutated by the caller
         * @return the discriminated outcome; never {@code null}
         * @throws NullPointerException if {@code record} is {@code null}
         * @throws IllegalStateException if this handle has been closed, or if no unit of work is open - the
         *     step's own transaction is the boundary {@code CBTRN02C} reaches this inside
         */
        public WriteResult rewrite(TranCatBalRecord record) {
            requireOpen("rewrite a record");
            Objects.requireNonNull(record, "A record is required to rewrite it");
            if (statements == null) {
                return WriteResult.of(openStatus);
            }
            return repository.rewrite(statements, record);
        }

        /**
         * Closes this open and reports the status the {@code CLOSE} would have.
         *
         * @return {@link FileStatus#OK}, or {@link TranCatBalRepository#PERMANENT_ERROR_STATUS} when the
         *     dataset is no longer addressable or the open had failed
         */
        public String closeFile() {
            if (closed) {
                return statements == null ? openStatus : FileStatus.OK;
            }
            closed = true;
            this.browsePosition = null;
            if (statements == null) {
                return openStatus;
            }
            return repository.statementsFor("a close of the transaction category balance dataset") == null
                    ? PERMANENT_ERROR_STATUS
                    : FileStatus.OK;
        }

        /**
         * The {@code APPL-RESULT} value the close's guard moves: {@code 0} on success, {@code 12}
         * otherwise.
         *
         * @return {@link FileStatus#APPL_AOK} or {@link TranCatBalRepository#APPL_RESULT_FATAL}
         */
        public int closeApplResult() {
            return FileStatus.isOk(closeFile()) ? FileStatus.APPL_AOK : APPL_RESULT_FATAL;
        }

        /**
         * {@link AutoCloseable} form of {@link #closeFile()}, so a handle can be used with
         * try-with-resources.
         */
        @Override
        public void close() {
            closeFile();
        }

        private void requireOpen(String operation) {
            if (closed) {
                throw new IllegalStateException("This open of the transaction category balance dataset '"
                        + datasetName() + "' has been closed, so it cannot " + operation + ". A COBOL "
                        + "program closes once, after its work - operating afterwards is a defect in the "
                        + "caller, not a file status. Open another file instead.");
            }
        }
    }

    /**
     * The outcome of a read: the file status, its classification, the record when there is one, and what
     * the backend said when it refused.
     *
     * @param status the two-character {@code FILE STATUS}, carried verbatim so the caller can render it
     *     exactly as {@code 9910-DISPLAY-IO-STATUS} does
     * @param outcome the classification of {@code status}
     * @param record the decoded record, present if and only if {@code outcome} is {@link Outcome#OK}
     * @param diagnostic what the backend reported, when the outcome was a refusal
     * @param response the CICS {@code RESP}/{@code RESP2} pair for the outcome
     */
    public record ReadResult(String status,
                             Outcome outcome,
                             Optional<TranCatBalRecord> record,
                             Optional<BackendDiagnostic> diagnostic,
                             CicsResponse response) {
        public ReadResult {
            requireConsistentStatus(status, outcome);
            Objects.requireNonNull(record, "A read result carries an empty record rather than a null one, "
                    + "so no null escapes the type");
            Objects.requireNonNull(diagnostic, "A read result carries an empty diagnostic rather than a "
                    + "null one, so no null escapes the type");
            Objects.requireNonNull(response, "A read result carries a CICS response pair rather than a "
                    + "null one; use CicsResponse.ofBatchStatus(status) where the outcome is a file "
                    + "status");
            if (record.isPresent() != (outcome == Outcome.OK)) {
                throw new IllegalArgumentException(record.isPresent()
                        ? "A read that did not succeed carries no record: outcome " + outcome
                                + " was given one. Only the '00' arm reaches TRAN-CAT-BAL-RECORD."
                        : "A successful read carries the decoded record, and this one carries none; build "
                                + "it with ReadResult.found(TranCatBalRecord).");
            }
        }

        /**
         * The successful arm: status {@code '00'}, carrying the decoded record.
         *
         * @param record the decoded record
         * @return a result carrying {@link FileStatus#OK} and the record
         * @throws NullPointerException if {@code record} is {@code null}
         */
        public static ReadResult found(TranCatBalRecord record) {
            Objects.requireNonNull(record, "A successful read carries the decoded TRAN-CAT-BAL-RECORD");
            return new ReadResult(FileStatus.OK, Outcome.OK, Optional.of(record), Optional.empty(),
                    CicsResponse.ofBatchStatus(FileStatus.OK));
        }

        /**
         * The end-of-file arm: status {@code '10'}, carrying no record.
         *
         * @return a result carrying {@link FileStatus#END_OF_FILE} and no record
         */
        public static ReadResult endOfFile() {
            return of(FileStatus.END_OF_FILE);
        }

        /**
         * The invalid-key arm: status {@code '23'}, carrying no record - and an expected outcome, not an
         * error.
         *
         * @return a result carrying {@link FileStatus#NOT_FOUND} and no record
         */
        public static ReadResult notFound() {
            return of(FileStatus.NOT_FOUND);
        }

        /**
         * A result for any status other than success, classified from the status itself.
         *
         * @param status the two-character status the read reported
         * @return a result carrying {@code status}, its classification, and no record
         * @throws NullPointerException if {@code status} is {@code null}
         * @throws IllegalArgumentException if {@code status} is not exactly
         *     {@link FileStatus#STATUS_LENGTH} characters, or is {@link FileStatus#OK}
         */
        public static ReadResult of(String status) {
            return new ReadResult(status, classify(status), Optional.empty(), Optional.empty(),
                    CicsResponse.ofBatchStatus(status));
        }

        /**
         * A failed read carrying the CICS pair the outcome should report.
         *
         * @param status the file status the caller branches on
         * @param response the pair to report
         * @return a result carrying {@code status}, its classification, {@code response} and no record
         * @throws NullPointerException if {@code status} or {@code response} is {@code null}
         * @throws IllegalArgumentException if {@code status} is not exactly
         *     {@link FileStatus#STATUS_LENGTH} characters, or is {@link FileStatus#OK}
         */
        public static ReadResult of(String status, CicsResponse response) {
            return new ReadResult(status, classify(status), Optional.empty(), Optional.empty(), response);
        }

        /**
         * A failed read carrying what the backend actually said about it.
         *
         * @param status the file status the caller branches on
         * @param diagnostic what the backend reported
         * @return the outcome
         * @throws NullPointerException if {@code status} or {@code diagnostic} is {@code null}
         */
        public static ReadResult of(String status, BackendDiagnostic diagnostic) {
            Objects.requireNonNull(diagnostic, "A backend diagnostic is required; use of(String) where "
                    + "there was no refusal to diagnose");
            return new ReadResult(status, classify(status), Optional.empty(), Optional.of(diagnostic),
                    CicsResponse.ofBatchStatus(status));
        }

        /**
         * Whether the read returned a record: {@code IF TCATBALF-STATUS = '00'}.
         *
         * @return {@code true} for status {@code '00'}
         */
        public boolean isFound() {
            return outcome == Outcome.OK;
        }

        /**
         * Whether the browse reached the end of the file: {@code IF TCATBALF-STATUS = '10'}
         * ({@code app/cbl/CBACT04C.cbl:L330}).
         *
         * @return {@code true} for status {@code '10'}
         */
        public boolean isEndOfFile() {
            return outcome == Outcome.END_OF_FILE;
        }

        /**
         * Whether the key matched no record - the {@code INVALID KEY} condition, and the signal to create.
         *
         * @return {@code true} for status {@code '23'}
         */
        public boolean isNotFound() {
            return outcome == Outcome.NOT_FOUND;
        }

        /**
         * Whether the read succeeded or reported a missing record - {@code CBTRN02C}'s own success
         * condition.
         *
         * @return {@code true} for status {@code '00'} or {@code '23'}
         */
        public boolean isOkOrNotFound() {
            return FileStatus.isOkOrNotFound(status);
        }

        /**
         * Whether the outcome is none of the statuses the COBOL names - the fatal arm.
         *
         * @return {@code true} for any status that is not {@code '00'}, {@code '10'} or {@code '23'}
         */
        public boolean isOther() {
            return !isFound() && !isEndOfFile() && !isNotFound();
        }

        /**
         * The status as {@code 9910-DISPLAY-IO-STATUS} renders it, for a caller's diagnostic line.
         *
         * @return the four-character status image
         */
        public String statusImage() {
            return FileStatus.toStatusImage(status);
        }

        /**
         * The CICS {@code RESP} value for this outcome, where one applies.
         *
         * @return the response value, or empty where the outcome has no CICS counterpart
         */
        public OptionalInt cicsResp() {
            return response.resp();
        }

        /**
         * The CICS {@code RESP2} reason code for this outcome.
         *
         * @return the reason code, {@link FileStatus#NO_REASON_CODE} where none applies
         */
        public int cicsResp2() {
            return response.resp2();
        }

        /**
         * The {@code APPL-RESULT} value {@code CBACT04C}'s guard moves for this outcome.
         *
         * <p>{@code 0} on success, {@code 16} at end of file, and {@code 12} for everything else - exactly
         * the ladder at {@code app/cbl/CBACT04C.cbl:L327-L335}.
         *
         * @return {@link FileStatus#APPL_AOK}, {@link FileStatus#APPL_EOF} or
         *     {@link TranCatBalRepository#APPL_RESULT_FATAL}
         */
        public int applResult() {
            return switch (outcome) {
                case OK -> FileStatus.APPL_AOK;
                case END_OF_FILE -> FileStatus.APPL_EOF;
                default -> APPL_RESULT_FATAL;
            };
        }

        /**
         * The {@code APPL-RESULT} value {@code CBTRN02C}'s guard moves for this outcome.
         *
         * @return {@link FileStatus#APPL_AOK} or {@link TranCatBalRepository#APPL_RESULT_FATAL}
         */
        public int applResultWhereNotFoundIsNormal() {
            return isOkOrNotFound() ? FileStatus.APPL_AOK : APPL_RESULT_FATAL;
        }
    }

    /**
     * The outcome of a write or a rewrite: the file status, its classification, and what the backend said
     * when it refused.
     *
     * @param status the two-character {@code FILE STATUS}, carried verbatim
     * @param outcome the classification of {@code status}
     * @param diagnostic what the backend reported, when the outcome was a refusal
     * @param response the CICS {@code RESP}/{@code RESP2} pair for the outcome
     */
    public record WriteResult(String status,
                              Outcome outcome,
                              Optional<BackendDiagnostic> diagnostic,
                              CicsResponse response) {
        public WriteResult {
            requireConsistentStatus(status, outcome);
            Objects.requireNonNull(diagnostic, "A write result carries an empty diagnostic rather than a "
                    + "null one, so no null escapes the type");
            Objects.requireNonNull(response, "A write result carries a CICS response pair rather than a "
                    + "null one");
        }

        public static WriteResult written() {
            return new WriteResult(FileStatus.OK, Outcome.OK, Optional.empty(),
                    CicsResponse.ofBatchStatus(FileStatus.OK));
        }

        /**
         * The invalid-key arm of a rewrite: status {@code '23'}, meaning there was no such record to
         * rewrite.
         *
         * @return a result carrying {@link FileStatus#NOT_FOUND}
         */
        public static WriteResult notFound() {
            return of(FileStatus.NOT_FOUND);
        }

        /**
         * The duplicate-key arm of a write: status {@code '22'}, meaning a record already carries this key.
         *
         * @return a result carrying {@link FileStatus#DUPLICATE}
         */
        public static WriteResult duplicate() {
            return of(FileStatus.DUPLICATE, CicsResponse.of(FileStatus.DUPREC));
        }

        /**
         * A result for any status other than success, classified from the status itself.
         *
         * @param status the two-character status the write reported
         * @return a result carrying {@code status} and its classification
         * @throws NullPointerException if {@code status} is {@code null}
         * @throws IllegalArgumentException if {@code status} is not exactly
         *     {@link FileStatus#STATUS_LENGTH} characters, or is {@link FileStatus#OK}
         */
        public static WriteResult of(String status) {
            return new WriteResult(status, classify(status), Optional.empty(),
                    CicsResponse.ofBatchStatus(status));
        }

        /**
         * A failed write carrying the CICS pair the outcome should report.
         *
         * @param status the file status the caller branches on
         * @param response the pair to report
         * @return the outcome
         * @throws NullPointerException if {@code status} or {@code response} is {@code null}
         * @throws IllegalArgumentException if {@code status} is not exactly
         *     {@link FileStatus#STATUS_LENGTH} characters, or is {@link FileStatus#OK}
         */
        public static WriteResult of(String status, CicsResponse response) {
            return new WriteResult(status, classify(status), Optional.empty(), response);
        }

        /**
         * A failed write carrying what the backend actually said about it.
         *
         * @param status the file status the caller branches on
         * @param diagnostic what the backend reported
         * @return the outcome
         * @throws NullPointerException if {@code status} or {@code diagnostic} is {@code null}
         */
        public static WriteResult of(String status, BackendDiagnostic diagnostic) {
            Objects.requireNonNull(diagnostic, "A backend diagnostic is required; use of(String) where "
                    + "there was no refusal to diagnose");
            return new WriteResult(status, classify(status), Optional.of(diagnostic),
                    CicsResponse.ofBatchStatus(status));
        }

        /**
         * A failed write carrying both the CICS pair and the backend's diagnosis.
         *
         * @param status the file status the caller branches on
         * @param response the pair to report
         * @param diagnostic what the backend reported
         * @return the outcome
         * @throws NullPointerException if any argument is {@code null}
         */
        public static WriteResult of(String status, CicsResponse response,
                BackendDiagnostic diagnostic) {
            Objects.requireNonNull(diagnostic, "A backend diagnostic is required; use of(String, "
                    + "CicsResponse) where there was no refusal to diagnose");
            return new WriteResult(status, classify(status), Optional.of(diagnostic), response);
        }

        /**
         * Whether the record was stored: {@code IF TCATBALF-STATUS = '00'}.
         *
         * @return {@code true} for status {@code '00'}
         */
        public boolean isWritten() {
            return outcome == Outcome.OK;
        }

        /**
         * Whether a rewrite found no record to rewrite.
         *
         * @return {@code true} for status {@code '23'}
         */
        public boolean isNotFound() {
            return outcome == Outcome.NOT_FOUND;
        }

        /**
         * Whether a write found a record already carrying the key.
         *
         * @return {@code true} for status {@code '22'}
         */
        public boolean isDuplicate() {
            return outcome == Outcome.DUPLICATE;
        }

        /**
         * Whether the outcome is none of the statuses the COBOL names - the fatal arm.
         *
         * @return {@code true} for any status that is not {@code '00'}, {@code '22'} or {@code '23'}
         */
        public boolean isOther() {
            return !isWritten() && !isNotFound() && !isDuplicate();
        }

        /**
         * The status as {@code 9910-DISPLAY-IO-STATUS} renders it, for a caller's diagnostic line.
         *
         * @return the four-character status image
         */
        public String statusImage() {
            return FileStatus.toStatusImage(status);
        }

        /**
         * The CICS {@code RESP} value for this outcome, where one applies.
         *
         * @return the response value, or empty where the outcome has no CICS counterpart
         */
        public OptionalInt cicsResp() {
            return response.resp();
        }

        /**
         * The CICS {@code RESP2} reason code for this outcome.
         *
         * @return the reason code, {@link FileStatus#NO_REASON_CODE} where none applies
         */
        public int cicsResp2() {
            return response.resp2();
        }

        /**
         * The {@code APPL-RESULT} value the COBOL guard moves for this outcome.
         *
         * @return {@link FileStatus#APPL_AOK} or {@link TranCatBalRepository#APPL_RESULT_FATAL}
         */
        public int applResult() {
            return isWritten() ? FileStatus.APPL_AOK : APPL_RESULT_FATAL;
        }
    }

    private static Outcome classify(String status) {
        if (FileStatus.OK.equals(requireStatusWidth(status))) {
            throw new IllegalArgumentException("A successful outcome is built by found(), written() or "
                    + "endOfFile() rather than by of(\"" + FileStatus.OK + "\"), so that a successful read "
                    + "can never be constructed without the record it is supposed to carry");
        }
        return FileStatus.outcomeOfStatus(status);
    }

    private static void requireConsistentStatus(String status, Outcome outcome) {
        requireStatusWidth(status);
        Objects.requireNonNull(outcome, "An outcome classification is required");
        Outcome classified = FileStatus.outcomeOfStatus(status);
        if (classified != outcome) {
            throw new IllegalArgumentException("File status " + FileStatus.toStatusImage(status)
                    + " classifies as " + classified + ", not as " + outcome + "; a result whose status "
                    + "and outcome disagree would branch one way and report another");
        }
    }

    private static String requireStatusWidth(String status) {
        Objects.requireNonNull(status, "A two-character FILE STATUS is required");
        if (status.length() != FileStatus.STATUS_LENGTH) {
            throw new IllegalArgumentException("A FILE STATUS is exactly " + FileStatus.STATUS_LENGTH
                    + " characters - TCATBALF-STAT1 and TCATBALF-STAT2, each PIC X "
                    + "(app/cbl/CBTRN02C.cbl:L127-L129) - but " + status.length() + " were given");
        }
        return status;
    }
}
