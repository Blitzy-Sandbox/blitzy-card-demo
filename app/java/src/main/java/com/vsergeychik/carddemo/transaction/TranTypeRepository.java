package com.vsergeychik.carddemo.transaction;

import com.vsergeychik.carddemo.common.DatasetObservation;
import com.vsergeychik.carddemo.common.DatasetRelation;
import com.vsergeychik.carddemo.common.DatasetRelation.BackendDiagnostic;
import com.vsergeychik.carddemo.common.DatasetRelation.KeySpan;
import com.vsergeychik.carddemo.common.FileStatus;
import com.vsergeychik.carddemo.common.FileStatus.Outcome;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.RecordImageForm;
import com.vsergeychik.carddemo.config.CobolCharsetConfig;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBinding;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBindings;
import com.vsergeychik.carddemo.transaction.model.TranTypeRecord;

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
import org.springframework.stereotype.Repository;

/**
 * The {@code TRANTYPE} transaction-type lookup: one dataset, one access path, one consumer.
 *
 * <p>{@code TRAN-TYPE} is {@code PIC X(02)}, so the move rule is space-padded and truncated on the right:
 * {@code "4"} becomes {@code "4 "}, never {@code "04"} and never {@code " 4"}.
 */
@Repository
public class TranTypeRepository {
    private static final Log LOG = LogFactory.getLog(TranTypeRepository.class);

    /**
     * The DD name this dataset is known by, and the key of its {@code carddemo.datasets} entry: {@value}.
     */
    public static final String DD_NAME = "TRANTYPE";

    /**
     * The record width in bytes, taken from the model rather than restated: {@code CVTRA03Y}'s
     * {@code TRAN-TYPE} 2 + {@code TRAN-TYPE-DESC} 50 + {@code FILLER} 8.
     */
    public static final int RECORD_LENGTH = TranTypeRecord.RECORD_LENGTH;

    /**
     * The key width in bytes: {@code TRAN-TYPE PIC X(02)}, named by {@code RECORD KEY IS FD-TRAN-TYPE} at
     * {@code app/cbl/CBTRN03C.cbl:42}.
     */
    public static final int TRAN_TYPE_KEY_LENGTH = TranTypeRecord.TRAN_TYPE_KEY_LENGTH;

    /**
     * The key's 0-based offset within the record: zero, because {@code TRAN-TYPE} begins the record.
     */
    public static final int TRAN_TYPE_KEY_OFFSET = TranTypeRecord.TRAN_TYPE_OFFSET;

    public static final int RECORD_IMAGE_COLUMN_INDEX = DatasetRelation.RECORD_IMAGE_COLUMN_INDEX;

    /**
     * The organization the {@link #DD_NAME} binding must declare: an indexed base cluster.
     */
    public static final String EXPECTED_ORGANIZATION = DatasetBinding.KSDS;

    /**
     * The copybook the {@link #DD_NAME} binding must name: {@value}.
     */
    public static final String EXPECTED_COPYBOOK = "CVTRA03Y";

    private static final char PERMANENT_ERROR_FEEDBACK_CODE = 0;

    /**
     * The two-character file status reported for a permanent I/O error: {@code '9'} followed by
     * {@link #PERMANENT_ERROR_FEEDBACK_CODE}.
     */
    public static final String PERMANENT_ERROR_STATUS = "9" + PERMANENT_ERROR_FEEDBACK_CODE;

    /**
     * The {@code APPL-RESULT} value this program moves for a fatal status: {@code 12}.
     */
    public static final int APPL_RESULT_FATAL = 12;

    private static final KeySpan KEY_SPAN =
            new KeySpan(TranTypeRecord.TRAN_TYPE.offset(), TranTypeRecord.TRAN_TYPE.length());

    private static final int UNIQUENESS_CHECK_ROW_LIMIT = 2;

    private static final int UNREADABLE_ROW_PROBE_LIMIT = 1;

    private final JdbcTemplate jdbcTemplate;

    private final FixedWidthCodec codec;

    private final RecordImageForm recordImageForm;

    private final DatasetRelation relation;

    private volatile Statements statements;

    /**
     * Assembles the repository from the module's shared {@link JdbcTemplate}, the DD-name-keyed dataset
     * catalogue, the explicitly named dataset code page and the configured record-image representation.
     *
     * @param jdbcTemplate the module-wide template; never {@code null}
     * @param datasetBindings the {@code carddemo.datasets} catalogue; never {@code null}
     * @param datasetCharset the code page the dataset holds its record images in, from
     *     {@code carddemo.charset.dataset}
     * @param recordImageForm how the deployment's driver presents a record image over JDBC, from
     *     {@value RecordImageForm#FORM_PROPERTY}
     * @throws NullPointerException if any argument is {@code null}
     * @throws IllegalStateException if no binding is configured for {@link #DD_NAME}, or if the binding
     *     disagrees with the copybook about the width, the copybook name, the organization or the key geometry
     * @throws IllegalArgumentException if the injected code page is not a single-byte one, or if the
     *     configured dataset name is not a well-formed z/OS dataset name
     */
    public TranTypeRepository(
            JdbcTemplate jdbcTemplate,
            DatasetBindings datasetBindings,
            @Qualifier(CobolCharsetConfig.DATASET_CHARSET_BEAN_NAME) Charset datasetCharset,
            RecordImageForm recordImageForm) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "A JdbcTemplate is required: the "
                + DD_NAME + " lookup is served through the module's shared template over the "
                + "configuration-bound DataSource");
        Objects.requireNonNull(datasetBindings, "The carddemo.datasets binding catalogue is required: "
                + "dataset names live in configuration and are never written in Java");
        this.codec = new FixedWidthCodec(Objects.requireNonNull(datasetCharset, "A dataset charset is "
                + "required: a fixed-width mainframe record is bytes in a specific code page, so the "
                + "code page is stated explicitly and never taken from the platform"));
        this.recordImageForm = Objects.requireNonNull(recordImageForm, "A record-image representation "
                + "is required: whether this deployment's driver presents a record image as characters "
                + "or as bytes is stated once, by " + RecordImageForm.FORM_PROPERTY + ", and never "
                + "decided per repository");
        RecordImageForm.requireSingleByteCodePage(datasetCharset);

        DatasetBinding binding = datasetBindings.binding(DD_NAME);
        requireCopybookRecordLength(binding);
        requireDeclaredCopybook(binding);
        requireIndexedBaseCluster(binding);
        requireCopybookKeyGeometry(binding);
        this.relation = DatasetRelation.of(requireUsableDatasetName(binding.dsname()), RECORD_LENGTH);

        TranTypeRecord.verifyDeclaredWidth();
    }

    /**
     * Requires the binding to agree with {@code app/cpy/CVTRA03Y.cpy} about the record width.
     *
     * @param binding the configured binding
     * @throws IllegalStateException if the declared record length is not {@link #RECORD_LENGTH}
     */
    private static void requireCopybookRecordLength(DatasetBinding binding) {
        if (binding.recordLength() != RECORD_LENGTH) {
            throw new IllegalStateException("Dataset binding for DD name '" + DD_NAME + "' declares a "
                    + "record length of " + binding.recordLength() + ", but " + EXPECTED_COPYBOOK
                    + ".cpy declares TRAN-TYPE-RECORD as " + RECORD_LENGTH + " bytes - TRAN-TYPE 2 + "
                    + "TRAN-TYPE-DESC 50 + FILLER 8 - and app/cbl/CBTRN03C.cbl:73-75 splits the same "
                    + "record as 2 + 58. This repository decodes by absolute offset, so a differently "
                    + "sized record would misplace TRAN-TYPE-DESC and the detail report would carry "
                    + "fifteen characters of the wrong bytes. Correct carddemo.datasets." + DD_NAME
                    + ".record-length to " + RECORD_LENGTH + ".");
        }
    }

    /**
     * Requires the binding to name the copybook this class decodes.
     *
     * @param binding the configured binding
     * @throws IllegalStateException if the binding names any other copybook, or none
     */
    private static void requireDeclaredCopybook(DatasetBinding binding) {
        if (!EXPECTED_COPYBOOK.equals(binding.copybook())) {
            throw new IllegalStateException("Dataset binding for DD name '" + DD_NAME + "' names "
                    + "copybook '" + binding.copybook() + "', but this repository decodes "
                    + EXPECTED_COPYBOOK + " - TRAN-TYPE X(02), TRAN-TYPE-DESC X(50), FILLER X(08). "
                    + "app/cpy/CVTRA04Y.cpy is also 60 bytes and is a different record in every field "
                    + "after the key, so the width alone does not identify the layout. Correct "
                    + "carddemo.datasets." + DD_NAME + ".copybook to " + EXPECTED_COPYBOOK + ".");
        }
    }

    private static void requireIndexedBaseCluster(DatasetBinding binding) {
        if (!EXPECTED_ORGANIZATION.equals(binding.organization())) {
            throw new IllegalStateException("Dataset binding for DD name '" + DD_NAME + "' declares "
                    + "organization '" + binding.organization() + "', but app/cbl/CBTRN03C.cbl:40-42 "
                    + "reads it with ORGANIZATION IS INDEXED, ACCESS MODE IS RANDOM and RECORD KEY IS "
                    + "FD-TRAN-TYPE. A keyed read against a dataset configured as anything but '"
                    + EXPECTED_ORGANIZATION + "' would be reading it in a way the program does not. "
                    + "Correct carddemo.datasets." + DD_NAME + ".organization to "
                    + EXPECTED_ORGANIZATION + ".");
        }
        if (binding.base() != null) {
            throw new IllegalStateException("Dataset binding for DD name '" + DD_NAME + "' declares "
                    + "base '" + binding.base() + "', but it IS a base cluster: the transaction-type "
                    + "file has no alternate index anywhere in app/csd/CARDDEMO.CSD or app/jcl, and "
                    + "CBTRN03C opens it once, on its primary key. Only an alternate-index path "
                    + "declares a base. Remove carddemo.datasets." + DD_NAME + ".base.");
        }
    }

    /**
     * Requires the binding's key geometry to match {@code app/cpy/CVTRA03Y.cpy}.
     *
     * @param binding the configured binding
     * @throws IllegalStateException if the declared key length is absent or not
     *     {@link #TRAN_TYPE_KEY_LENGTH}, or if the declared key offset is not {@link #TRAN_TYPE_KEY_OFFSET}
     */
    private static void requireCopybookKeyGeometry(DatasetBinding binding) {
        Integer declaredKeyLength = binding.keyLength();
        if (declaredKeyLength == null || declaredKeyLength != TRAN_TYPE_KEY_LENGTH) {
            throw new IllegalStateException("Dataset binding for DD name '" + DD_NAME + "' declares "
                    + "key-length " + declaredKeyLength + ", but RECORD KEY IS FD-TRAN-TYPE at "
                    + "app/cbl/CBTRN03C.cbl:42 names TRAN-TYPE PIC X(0" + TRAN_TYPE_KEY_LENGTH
                    + ") - exactly " + TRAN_TYPE_KEY_LENGTH + " bytes. A keyed predicate composed from "
                    + "a key of any other width matches records this key does not name. Set "
                    + "carddemo.datasets." + DD_NAME + ".key-length to " + TRAN_TYPE_KEY_LENGTH + ".");
        }
        if (binding.keyOffsetOrZero() != TRAN_TYPE_KEY_OFFSET) {
            throw new IllegalStateException("Dataset binding for DD name '" + DD_NAME + "' declares "
                    + "key-offset " + binding.keyOffsetOrZero() + ", but TRAN-TYPE begins "
                    + EXPECTED_COPYBOOK + "'s record, so its 0-based offset is "
                    + TRAN_TYPE_KEY_OFFSET + ". A non-zero offset belongs to an alternate-index path, "
                    + "and this dataset has none. Remove carddemo.datasets." + DD_NAME
                    + ".key-offset.");
        }
    }

    private static String requireUsableDatasetName(String candidate) {
        if (candidate == null || candidate.isBlank()) {
            throw new IllegalStateException("The dataset binding for DD name '" + DD_NAME
                    + "' declares no dataset name. Set carddemo.datasets." + DD_NAME + ".dsname; this "
                    + "repository composes its statements from configuration alone and hard-codes no "
                    + "dataset name (gate G46).");
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
     * Opens the {@link #DD_NAME} dataset for input, reporting only the resulting file status: the Java form
     * of {@code 0300-TRANTYPE-OPEN} ({@code app/cbl/CBTRN03C.cbl:430-446}).
     *
     * <p>The COBOL is a two-way test - {@code IF TRANTYPE-STATUS = '00'} moves {@code 0} into
     * {@code APPL-RESULT}, anything else moves {@value #APPL_RESULT_FATAL} - and this method reproduces
     * exactly that shape and nothing more.
     *
     * @return {@link FileStatus#OK} when the dataset is addressable and presents a usable record-image
     *     column, otherwise {@link #PERMANENT_ERROR_STATUS}; never {@code null}, always two characters
     */
    public String open() {
        return probeDatasetAvailability("OPEN INPUT");
    }

    /**
     * Closes the {@link #DD_NAME} dataset, reporting only the resulting file status: the Java form of
     * {@code 9300-TRANTYPE-CLOSE} ({@code app/cbl/CBTRN03C.cbl:569-585}).
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

    private String probeDatasetAvailability(String cobolOperation) {
        try {
            describeAndComposeKeyedRead();
            return FileStatus.OK;
        } catch (DataAccessException translated) {
            logRefusal(translated, cobolOperation + " the " + DD_NAME + " dataset (a describe of the "
                    + "configured relation, which is what distinguishes an absent dataset from an "
                    + "unreadable record)");
            return PERMANENT_ERROR_STATUS;
        } catch (IllegalStateException unusable) {
            LOG.error("Could not " + cobolOperation + " the " + DD_NAME + " dataset: "
                    + unusable.getMessage() + "; reporting file status "
                    + FileStatus.toStatusImage(PERMANENT_ERROR_STATUS) + " to the caller");
            return PERMANENT_ERROR_STATUS;
        }
    }

    /**
     * Reads one transaction-type record by its key: the Java form of {@code 1500-B-LOOKUP-TRANTYPE}
     * ({@code app/cbl/CBTRN03C.cbl:494-502}).
     *
     * @param tranType the two-character transaction type code to look up; never {@code null}, and may be
     *     shorter or longer than {@link #TRAN_TYPE_KEY_LENGTH} because the {@code PIC X} move reshapes it
     * @return the discriminated outcome; never {@code null}
     * @throws NullPointerException if {@code tranType} is {@code null}
     */
    public ReadResult readByTranType(String tranType) {
        Objects.requireNonNull(tranType, "A transaction type code is required to read the "
                + TranTypeRecord.TRAN_TYPE_FIELD + " key; an absent key is a defect in the caller "
                + "rather than an INVALID KEY outcome, because app/cbl/CBTRN03C.cbl:189 always moves "
                + "two characters into FD-TRAN-TYPE. Pass an empty string for a key of SPACES.");
        // The PIC X move, named rather than implied: right-padded when short, right-truncated when long.
        String keyImage = codec.movePicX(tranType, TRAN_TYPE_KEY_LENGTH);

        List<byte[]> rows;
        Statements sql;
        try {
            sql = statements();
            rows = fetchByKey(sql, keyImage);
        } catch (DataAccessException translated) {
            return refused(keyImage, translated, "read the " + DD_NAME + " dataset by key");
        } catch (IllegalStateException unusable) {
            LOG.error("Could not read the " + DD_NAME + " dataset by key: " + unusable.getMessage()
                    + "; reporting file status " + FileStatus.toStatusImage(PERMANENT_ERROR_STATUS)
                    + " to the caller");
            return ReadResult.other(keyImage, PERMANENT_ERROR_STATUS);
        }

        if (rows == null) {
            LOG.error("The " + DD_NAME + " dataset yielded no result object at all for a keyed read; "
                    + "reporting file status " + FileStatus.toStatusImage(PERMANENT_ERROR_STATUS)
                    + " rather than treating it as a dataset with no matching record");
            return ReadResult.other(keyImage, PERMANENT_ERROR_STATUS);
        }
        if (rows.isEmpty()) {
            // TRAN-TYPE is the leading two bytes of the record image (app/cpy/CVTRA03Y.cpy:5), so a row
            // whose record-image column holds nothing has no knowable key and the keyed LIKE predicate
            // cannot match it: SQL evaluates every comparison against a null as UNKNOWN.
            return provenAbsence(sql.probeUnreadableRows(), keyImage);
        }
        if (rows.size() > 1) {
            LOG.error("The " + DD_NAME + " dataset presented more than one row ("
                    + DatasetObservation.matchingRows(rows.size()).describe() + ") for a key that "
                    + EXPECTED_COPYBOOK + " declares unique (RECORD KEY IS FD-TRAN-TYPE, "
                    + "app/cbl/CBTRN03C.cbl:42); reporting file status "
                    + FileStatus.toStatusImage(PERMANENT_ERROR_STATUS)
                    + " rather than returning one of several records as though it were the only one");
            return ReadResult.other(keyImage, PERMANENT_ERROR_STATUS);
        }

        byte[] recordImage = rows.get(0);
        if (recordImage == null) {
            LOG.error("The matching row of the " + DD_NAME + " dataset carries no record image at "
                    + "column position " + RECORD_IMAGE_COLUMN_INDEX + "; reporting file status "
                    + FileStatus.toStatusImage(PERMANENT_ERROR_STATUS)
                    + " rather than reporting a record that is present as absent");
            return ReadResult.other(keyImage, PERMANENT_ERROR_STATUS);
        }
        if (recordImage.length != RECORD_LENGTH) {
            // Reported rather than padded: the description is moved into a PIC X(15) report field next, so
            // a truncated row would pad into a different description and the report would be wrong with
            // nothing said.
            LOG.error("The " + DD_NAME + " dataset presented a row whose "
                    + DatasetObservation.recordWidth(recordImage.length).describe() + ", but "
                    + EXPECTED_COPYBOOK + " declares TRAN-TYPE-RECORD as " + RECORD_LENGTH
                    + " bytes and app/cbl/CBTRN03C.cbl:73-75 splits it as 2 + 58; reporting file status "
                    + FileStatus.toStatusImage(PERMANENT_ERROR_STATUS)
                    + " rather than padding the row into a description the dataset does not contain");
            return ReadResult.other(keyImage, PERMANENT_ERROR_STATUS);
        }
        return ReadResult.found(keyImage, TranTypeRecord.decode(recordImage, codec));
    }

    private ReadResult provenAbsence(String unreadableRowsProbe, String keyImage) {
        List<byte[]> unreadable;
        try {
            unreadable = fetchUnreadableRows(unreadableRowsProbe);
        } catch (DataAccessException translated) {
            return refused(keyImage, translated, "establish that the " + DD_NAME + " dataset holds no "
                    + "unreadable row before reporting a key as absent");
        }
        if (unreadable == null) {
            LOG.error("The " + DD_NAME + " dataset yielded no result object at all for the unreadable-row "
                    + "probe, so a keyed read's INVALID KEY could not be established; reporting file "
                    + "status " + FileStatus.toStatusImage(PERMANENT_ERROR_STATUS)
                    + " rather than reporting as absent a record that may be present");
            return ReadResult.other(keyImage, PERMANENT_ERROR_STATUS);
        }
        if (unreadable.isEmpty()) {
            return ReadResult.notFound(keyImage);
        }
        LOG.error("A keyed read of the " + DD_NAME + " dataset matched no row, but the dataset holds a row "
                + "with no record image at column position " + RECORD_IMAGE_COLUMN_INDEX
                + " - and TRAN-TYPE is part of that image, so that row's key cannot be known; reporting "
                + "file status " + FileStatus.toStatusImage(PERMANENT_ERROR_STATUS)
                + " rather than reporting as absent a transaction type that may well be defined");
        return ReadResult.other(keyImage, PERMANENT_ERROR_STATUS);
    }

    private List<byte[]> fetchByKey(Statements sql, String keyImage) {
        String statement = sql.selectByKey();
        String pattern = KEY_SPAN.pattern(keyImage);
        PreparedStatementCreator creator = connection -> {
            PreparedStatement prepared = connection.prepareStatement(statement);
            prepared.setMaxRows(UNIQUENESS_CHECK_ROW_LIMIT);
            prepared.setFetchSize(UNIQUENESS_CHECK_ROW_LIMIT);
            recordImageForm.bindOperand(prepared, 1, pattern, codec.charset());
            return prepared;
        };
        ResultSetExtractor<List<byte[]>> extractor = resultSet -> {
            List<byte[]> images = new ArrayList<>(UNIQUENESS_CHECK_ROW_LIMIT);
            while (images.size() < UNIQUENESS_CHECK_ROW_LIMIT && resultSet.next()) {
                images.add(recordImageForm.readImage(resultSet, RECORD_IMAGE_COLUMN_INDEX,
                        codec.charset()));
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
        ResultSetExtractor<List<byte[]>> extractor = resultSet -> {
            List<byte[]> images = new ArrayList<>(UNREADABLE_ROW_PROBE_LIMIT);
            while (images.size() < UNREADABLE_ROW_PROBE_LIMIT && resultSet.next()) {
                images.add(recordImageForm.readImage(resultSet, RECORD_IMAGE_COLUMN_INDEX,
                        codec.charset()));
            }
            return images;
        };
        return jdbcTemplate.query(creator, extractor);
    }

    private Statements statements() {
        Statements resolved = this.statements;
        return resolved == null ? describeAndComposeKeyedRead() : resolved;
    }

    private Statements describeAndComposeKeyedRead() {
        ResultSetExtractor<String> columnNameExtractor = TranTypeRepository::extractRecordImageColumn;
        String recordImageColumn = relation.rememberRecordImageColumn(
                jdbcTemplate.query(relation.describeStatement(), columnNameExtractor));
        Statements composed = new Statements(relation.selectByKey(recordImageColumn),
                relation.selectUnreadableRows(recordImageColumn));
        this.statements = composed;
        return composed;
    }

    /**
     * The statements this repository sends, composed from one describe.
     *
     * @param selectByKey the keyed read: the record whose image begins with the two-character
     *     {@code TRAN-TYPE} key
     * @param probeUnreadableRows the rows whose record-image column holds nothing
     */
    private record Statements(String selectByKey, String probeUnreadableRows) {
    }

    private static String extractRecordImageColumn(ResultSet resultSet) throws SQLException {
        ResultSetMetaData metaData = resultSet.getMetaData();
        return DatasetRelation.recordImageColumnOf(metaData);
    }

    private static ReadResult refused(String keyImage, Throwable refusal, String attempt) {
        return ReadResult.other(keyImage, PERMANENT_ERROR_STATUS, logRefusal(refusal, attempt));
    }

    private static BackendDiagnostic logRefusal(Throwable refusal, String attempt) {
        BackendDiagnostic diagnostic = BackendDiagnostic.of(refusal);
        LOG.error("Could not " + attempt + " - " + diagnostic.describe() + "; reporting file status "
                + FileStatus.toStatusImage(PERMANENT_ERROR_STATUS) + " to the caller");
        return diagnostic;
    }

    String describeStatement() {
        return relation.describeStatement();
    }

    String resolvedKeyedReadStatement() {
        Statements resolved = statements;
        return resolved == null ? null : resolved.selectByKey();
    }

    String keyedPredicatePattern(String keyImage) {
        return KEY_SPAN.pattern(keyImage);
    }

    /**
     * The outcome of one keyed read of the {@link #DD_NAME} dataset: what happened, in the vocabulary every
     * dataset operation in this module speaks, plus the record when there is one.
     *
     * @param keyImage the key the read used, exactly {@link #TRAN_TYPE_KEY_LENGTH} characters after the
     *     {@code PIC X} move
     * @param status the two-character {@code TRANTYPE-STATUS} the read reported: {@link FileStatus#OK},
     *     {@link FileStatus#NOT_FOUND} or {@link TranTypeRepository#PERMANENT_ERROR_STATUS}
     * @param outcome the classification of {@code status}, from {@link FileStatus#outcomeOfStatus(String)},
     *     so the status and its meaning cannot drift apart
     * @param record the record, present exactly when one was read - so for {@link Outcome#OK} and for
     *     nothing else
     * @param diagnostic what the backend reported when it refused, present only on an {@link Outcome#OTHER}
     *     arm the driver described
     */
    public record ReadResult(String keyImage,
                             String status,
                             Outcome outcome,
                             Optional<TranTypeRecord> record,
                             Optional<BackendDiagnostic> diagnostic) {
        public ReadResult {
            Objects.requireNonNull(keyImage, "A read result carries the key image the read used; "
                    + "app/cbl/CBTRN03C.cbl:497 displays exactly those bytes, so they are never absent");
            Objects.requireNonNull(status, "A read result carries the two-character file status the read "
                    + "reported; it is never absent");
            Objects.requireNonNull(outcome, "A read result carries its classification; it is never "
                    + "absent");
            Objects.requireNonNull(record, "An Optional is required, empty rather than null, so no null "
                    + "escapes this type");
            Objects.requireNonNull(diagnostic, "An Optional is required for the backend diagnostic, "
                    + "empty rather than null, so no null escapes this type");
            if (keyImage.length() != TRAN_TYPE_KEY_LENGTH) {
                throw new IllegalArgumentException("The key image is " + keyImage.length()
                        + " character(s); TRAN-TYPE is PIC X(0" + TRAN_TYPE_KEY_LENGTH + ") and "
                        + "FD-TRAN-TYPE always holds exactly " + TRAN_TYPE_KEY_LENGTH
                        + ", so a result carrying any other width did not come from a keyed read of "
                        + "this dataset.");
            }
            if (status.length() != FileStatus.STATUS_LENGTH) {
                throw new IllegalArgumentException("FILE STATUS '" + status + "' is " + status.length()
                        + " character(s); TRANTYPE-STATUS is declared as two PIC X items at "
                        + "app/cbl/CBTRN03C.cbl:104-106 and is compared as such.");
            }
            if (outcome != FileStatus.outcomeOfStatus(status)) {
                throw new IllegalArgumentException("Outcome " + outcome + " does not classify FILE "
                        + "STATUS '" + status + "', which classifies as "
                        + FileStatus.outcomeOfStatus(status) + ". The status and its meaning are two "
                        + "views of one fact and may not disagree.");
            }
            if (outcome == Outcome.END_OF_FILE) {
                throw new IllegalArgumentException("A keyed read cannot report end of file: "
                        + "app/cbl/CBTRN03C.cbl:41 is ACCESS MODE IS RANDOM, so there is no sequence to "
                        + "exhaust. A key that matches nothing is INVALID KEY, which is "
                        + Outcome.NOT_FOUND + " and FILE STATUS '" + FileStatus.NOT_FOUND + "'.");
            }
            if (outcome == Outcome.DUPLICATE) {
                throw new IllegalArgumentException("A read of TRAN-TYPE cannot report a duplicate: it is "
                        + "the primary key of a KSDS (RECORD KEY IS FD-TRAN-TYPE, "
                        + "app/cbl/CBTRN03C.cbl:42) and is unique by construction. More than one "
                        + "matching row means the relation is not presenting this dataset, which is "
                        + "reported as " + Outcome.OTHER + ".");
            }
            if (record.isPresent() != (outcome == Outcome.OK)) {
                throw new IllegalArgumentException(record.isPresent()
                        ? "Outcome " + outcome + " carries no record, but one was supplied. Only the '"
                                + FileStatus.OK + "' arm reaches TRAN-TYPE-RECORD."
                        : "A successful read carries the record it read, and this one carries none.");
            }
        }

        public static ReadResult found(String keyImage, TranTypeRecord record) {
            Objects.requireNonNull(record, "A found outcome must carry the record it found");
            return new ReadResult(keyImage, FileStatus.OK, Outcome.OK, Optional.of(record),
                    Optional.empty());
        }

        /**
         * No record matched the key: status {@link FileStatus#NOT_FOUND}, which is the {@code 23} the
         * program itself moves into {@code IO-STATUS} at {@code app/cbl/CBTRN03C.cbl:498}.
         *
         * @param keyImage the key image the read used, which the caller displays
         * @return the outcome
         */
        public static ReadResult notFound(String keyImage) {
            return new ReadResult(keyImage, FileStatus.NOT_FOUND, Outcome.NOT_FOUND, Optional.empty(),
                    Optional.empty());
        }

        /**
         * Anything else: the {@code WHEN OTHER} arm, which in this program is the display-and-abend path.
         *
         * @param keyImage the key image the read used
         * @param status the status to report, which must classify as {@link Outcome#OTHER} - normally
         *     {@link TranTypeRepository#PERMANENT_ERROR_STATUS}
         * @return the outcome
         * @throws NullPointerException if {@code status} is {@code null}
         * @throws IllegalArgumentException if {@code status} is not two characters, or classifies as one of
         *     the enumerated outcomes rather than as {@link Outcome#OTHER}
         */
        public static ReadResult other(String keyImage, String status) {
            return new ReadResult(keyImage, status, Outcome.OTHER, Optional.empty(), Optional.empty());
        }

        /**
         * The {@code WHEN OTHER} arm, carrying what the backend actually said about the refusal.
         *
         * @param keyImage the key image the read used
         * @param status the permanent-error file status
         * @param diagnostic what the backend reported
         * @return the outcome
         * @throws NullPointerException if {@code diagnostic} is {@code null}
         */
        public static ReadResult other(String keyImage, String status, BackendDiagnostic diagnostic) {
            Objects.requireNonNull(diagnostic, "A diagnostic is required by this factory; use "
                    + "other(String, String) where there is no backend refusal to report");
            return new ReadResult(keyImage, status, Outcome.OTHER, Optional.empty(),
                    Optional.of(diagnostic));
        }

        /**
         * Whether the record was read - the {@code NOT INVALID KEY} test.
         *
         * @return {@code true} for {@link Outcome#OK}
         */
        public boolean isFound() {
            return outcome == Outcome.OK;
        }

        /**
         * Whether the key matched nothing - the {@code INVALID KEY} test of
         * {@code app/cbl/CBTRN03C.cbl:496}.
         *
         * @return {@code true} for {@link Outcome#NOT_FOUND}
         */
        public boolean isNotFound() {
            return outcome == Outcome.NOT_FOUND;
        }

        public boolean isOther() {
            return outcome == Outcome.OTHER;
        }

        /**
         * The {@code APPL-RESULT} value this program moves for the outcome: {@code 0} on success and
         * {@value TranTypeRepository#APPL_RESULT_FATAL} otherwise.
         *
         * @return {@link FileStatus#APPL_AOK} or {@link TranTypeRepository#APPL_RESULT_FATAL}
         */
        public int applResult() {
            return switch (outcome) {
                case OK -> FileStatus.APPL_AOK;
                default -> APPL_RESULT_FATAL;
            };
        }

        /**
         * This status rendered as the four-character image {@code 9910-DISPLAY-IO-STATUS} produces
         * ({@code app/cbl/CBTRN03C.cbl:633-646}), for a caller composing that display line.
         *
         * @return the status image, exactly {@link FileStatus#STATUS_IMAGE_LENGTH} characters
         */
        public String statusImage() {
            return FileStatus.toStatusImage(status);
        }
    }
}
