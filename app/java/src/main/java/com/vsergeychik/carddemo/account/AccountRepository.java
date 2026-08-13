package com.vsergeychik.carddemo.account;

import com.vsergeychik.carddemo.account.model.AccountRecord;
import com.vsergeychik.carddemo.common.CicsResponse;
import com.vsergeychik.carddemo.common.DatasetRelation.BackendDiagnostic;
import com.vsergeychik.carddemo.common.DatasetRelation.KeySpan;
import com.vsergeychik.carddemo.common.DatasetRelation;
import com.vsergeychik.carddemo.common.DiagnosticText;
import com.vsergeychik.carddemo.common.SensitiveDiagnostics;
import com.vsergeychik.carddemo.common.FileStatus;
import com.vsergeychik.carddemo.common.FileStatus.Outcome;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.FixedWidthRecord;
import com.vsergeychik.carddemo.common.RecordImageForm;
import com.vsergeychik.carddemo.config.CobolCharsetConfig;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBinding;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBindings;
import com.vsergeychik.carddemo.common.DatasetUnitOfWork;

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
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.Charset;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * The account master dataset, reached over JDBC: one repository for the three access paths the COBOL estate
 * drives against it, and nothing beyond them.
 *
 * <p>A contract violation is a different thing from an I/O outcome and is thrown rather than statused: a
 * configured record width that is not the copybook's, a dataset name that cannot address anything, a key
 * image of the wrong width, or a backend that presents no record-image column at all.
 */
@Repository
public class AccountRepository {
    private static final Log LOG = LogFactory.getLog(AccountRepository.class);

    /**
     * The CICS {@code FILE} name of the account master: {@code ACCTDAT}.
     */
    public static final String CICS_FILE_NAME = "ACCTDAT";

    /**
     * The batch DD name of the same dataset: {@code ACCTFILE}.
     */
    public static final String BATCH_DD_NAME = "ACCTFILE";

    public static final int RECORD_LENGTH = AccountRecord.RECORD_LENGTH;

    /**
     * The primary-key width in bytes: {@value}, delegated to {@link AccountRecord#KEY_LENGTH}.
     */
    public static final int KEY_LENGTH = AccountRecord.KEY_LENGTH;

    public static final int RECORD_IMAGE_COLUMN_INDEX = DatasetRelation.RECORD_IMAGE_COLUMN_INDEX;

    private static final KeySpan KEY_SPAN = new KeySpan(0, KEY_LENGTH);

    /**
     * The {@code APPL-RESULT} value the COBOL moves on the fatal arm of every guard chain over this
     * dataset: {@value}.
     */
    public static final int APPL_RESULT_FATAL = 12;

    private static final char PERMANENT_ERROR_FEEDBACK_CODE = 0;

    /**
     * The status reported when the backend refuses an operation: {@code '9'} followed by
     * {@link #PERMANENT_ERROR_FEEDBACK_CODE}.
     *
     * <p>Passing this value to {@link FileStatus#toDisplayLine(String)} therefore produces exactly the line
     * the COBOL would have emitted.
     */
    public static final String PERMANENT_ERROR_STATUS = "9" + PERMANENT_ERROR_FEEDBACK_CODE;

    private static final String FOR_UPDATE = " FOR UPDATE";

    private static final int FAN_OUT_PROBE_LIMIT = 2;

    private final JdbcTemplate jdbcTemplate;

    private final FixedWidthCodec codec;

    private final String datasetName;

    private final DatasetRelation relation;

    private final RecordImageForm recordImageForm;

    /**
     * Resolves the account master's configured bindings, proves the record geometry, and captures the
     * collaborators - all before the context finishes starting, so nothing checkable is left to fail
     * mid-job.
     *
     * @param jdbcTemplate the module's shared template, from the data-access configuration
     * @param datasetBindings the DD-name-keyed dataset catalogue bound from {@code carddemo.datasets}
     * @param datasetCharset the active dataset code page, selected by bean name so the choice is explicit
     *     at the injection point
     * @param recordImageForm how the deployment's driver presents a record image over JDBC, from
     *     {@value RecordImageForm#FORM_PROPERTY}; the module's single authority on that, so this class never
     *     chooses a JDBC type for itself
     * @throws NullPointerException if any argument is {@code null}
     * @throws IllegalStateException if either DD name is unconfigured, if either binding declares a record
     *     width other than {@link #RECORD_LENGTH} or a key width other than {@link #KEY_LENGTH}
     */
    public AccountRepository(
            JdbcTemplate jdbcTemplate,
            DatasetBindings datasetBindings,
            @Qualifier(CobolCharsetConfig.DATASET_CHARSET_BEAN_NAME) Charset datasetCharset,
            RecordImageForm recordImageForm) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "A JdbcTemplate is required: the "
                + "account master is reached through the module's shared template");
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

        DatasetBinding cicsBinding = requireAccountGeometry(datasetBindings, CICS_FILE_NAME);
        DatasetBinding batchBinding = requireAccountGeometry(datasetBindings, BATCH_DD_NAME);
        requireSameDataset(cicsBinding, batchBinding);

        this.relation = DatasetRelation.of(requireUsableDatasetName(cicsBinding.dsname()),
                RECORD_LENGTH);
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
     * @return 300
     */
    public int recordLength() {
        return RECORD_LENGTH;
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
     * Opens the account master and hands back the handle that stands for that open.
     *
     * <p>No program in the estate mixes the modes against this dataset - a browse is always under
     * {@code OPEN INPUT} and a rewrite always under {@code OPEN I-O} - so refusing an operation on mode
     * grounds would add a rejection the COBOL never performs and a branch no caller could reach.
     *
     * @param mode the {@code OPEN} verb being issued
     * @return a freshly positioned handle whose {@link AccountFile#openStatus()} reports whether the
     *     dataset was addressable; never {@code null}
     * @throws NullPointerException if {@code mode} is {@code null}
     * @throws IllegalStateException if the backend presents the dataset with no usable record-image column,
     *     which is a contract violation rather than an I/O outcome
     */
    public AccountFile open(OpenMode mode) {
        Objects.requireNonNull(mode, "An open mode is required: the estate opens this dataset as "
                + OpenMode.INPUT.cobolVerb() + " or as " + OpenMode.I_O.cobolVerb() + ", and which "
                + "verb was issued is part of what the program did");

        this.relation.forgetRecordImageColumn();

        Statements resolved;
        try {
            resolved = resolveStatements();
        } catch (DataAccessException unreachable) {
            logRefusal(unreachable, "describe the account master dataset '" + datasetName + "' to "
                    + mode.cobolVerb() + " it");
            return new AccountFile(this, mode, PERMANENT_ERROR_STATUS, null);
        }
        return new AccountFile(this, mode, FileStatus.OK, resolved);
    }

    /**
     * Reads one record by account identifier: the Java form of the batch keyed read at
     * {@code app/cbl/CBACT04C.cbl:L373-L376}, A {@code long} because that is the COBOL view the batch
     * programs hold: the key is {@code FD-ACCT-ID PIC 9(11)} ({@code CBACT04C.cbl:L86}) and it is set by
     * {@code MOVE TRANCAT-ACCT-ID TO FD-ACCT-ID} at {@code L202} from another {@code PIC 9(11)} item.
     *
     * <p>The number is rendered to the stored eleven-digit zero-filled image by
     * {@link AccountRecord#keyImage(long, Charset)}, so the {@code PIC 9} move rule - zero-fill on the
     * left, truncate on the left - is applied in one place rather than here.
     *
     * @param acctId the account identifier; must not be negative, as {@code PIC 9} declares no sign
     *     position and therefore has no representation for one
     * @return the discriminated outcome; never {@code null}
     * @throws IllegalArgumentException if {@code acctId} is negative, or if the stored image is wider than
     *     {@link #RECORD_LENGTH}
     * @throws IllegalStateException if the backend presents the dataset with no usable record-image column
     */
    public ReadResult readByKey(long acctId) {
        return readKeyed(AccountRecord.keyImage(acctId, codec.charset()), "the account identifier "
                + SensitiveDiagnostics.maskIdentifier(acctId, AccountRecord.ACCT_ID_LENGTH), false);
    }

    /**
     * Reads one record for update by its eleven-character key image: the Java form of
     * {@code app/cbl/COACTUPC.cbl:L3892-L3903}, A {@code String} of exactly {@link #KEY_LENGTH} characters
     * because the {@code RIDFLD} is the character view of the key:
     * {@code WS-CARD-RID-ACCT-ID-X REDEFINES WS-CARD-RID-ACCT-ID PIC X(11)} ({@code L382-L383}), fed from
     * {@code CC-ACCT-ID PIC X(11)}.
     *
     * @param acctIdAsChar11 the key exactly as the {@code RIDFLD} holds it: exactly {@link #KEY_LENGTH}
     *     characters, untrimmed and unparsed
     * @return the discriminated outcome; never {@code null}
     * @throws NullPointerException if {@code acctIdAsChar11} is {@code null}
     * @throws IllegalArgumentException if {@code acctIdAsChar11} is not exactly {@link #KEY_LENGTH}
     *     characters
     * @throws IllegalStateException if no unit of work is open, or if the backend presents the dataset with
     *     no usable record-image column
     */
    public ReadResult readForUpdate(String acctIdAsChar11) {
        String keyImage = requireKeyImage(acctIdAsChar11);
        requireUnitOfWork();
        return readKeyed(keyImage, "the record identification field '"
                + SensitiveDiagnostics.maskIdentifier(keyImage) + "'", true);
    }

    /**
     * Rewrites one record in place: the Java form of {@code REWRITE FD-ACCTFILE-REC FROM ACCOUNT-RECORD}
     * ({@code app/cbl/CBACT04C.cbl:L356}) and of
     * {@code EXEC CICS REWRITE FILE(LIT-ACCTFILENAME) FROM(ACCT-UPDATE-RECORD) LENGTH(…) RESP(…) RESP2(…)}
     * ({@code app/cbl/COACTUPC.cbl:L4065-L4071}).
     *
     * @param record the record to write, complete and already mutated by the caller
     * @return the discriminated outcome; never {@code null}
     * @throws NullPointerException if {@code record} is {@code null}
     * @throws IllegalStateException if no unit of work is open, in which case nothing has been attempted;
     *     if the backend presents the dataset with no usable record-image column; or
     */
    public WriteResult rewrite(AccountRecord record) {
        Objects.requireNonNull(record, "A record is required to rewrite it; a COBOL REWRITE writes the "
                + "record area, and there is no such thing as rewriting nothing");

        Statements sql;
        try {
            sql = resolveStatements();
        } catch (DataAccessException unreachable) {
            return reportWrite(unreachable, "describe the account master dataset '" + datasetName
                    + "' to rewrite a record");
        }
        return rewrite(sql, record);
    }

    private void requireOwnCodePage(AccountRecord record) {
        if (!record.charset().equals(codec.charset())) {
            throw new IllegalArgumentException("An ACCOUNT-RECORD encoded in " + record.charset().name()
                    + " cannot be written to dataset '" + datasetName + "', which is stored in "
                    + codec.charset().name() + ". The record's bytes are bound to the page it was built "
                    + "with and this repository writes them unchanged, so the rewrite would store "
                    + "corrupt bytes and report success. Build the record with the dataset's own code "
                    + "page - AccountRepository.datasetCharset() reports it.");
        }
    }

    private WriteResult rewrite(Statements sql, AccountRecord record) {
        requireOwnCodePage(record);

        DatasetUnitOfWork.requireActiveToPersist("A rewrite of the account master, which REWRITE issues "
                + "against the record area it was handed (app/cbl/CBACT04C.cbl:L356) and which EXEC CICS "
                + "REWRITE issues against the record the preceding READ ... UPDATE still holds "
                + "(app/cbl/COACTUPC.cbl:L4065-L4071)", datasetName);

        byte[] recordImage = record.toByteArray();
        String keyPattern = asPrefixPattern(record.keyImage());
        String maskedKey = SensitiveDiagnostics.maskIdentifier(record.keyImage());

        int matching;
        try {
            matching = matchingRowCount(sql.selectByKeyForUpdate(), keyPattern);
        } catch (DataAccessException rejected) {
            return reportWrite(rejected, "establish how many rows the key of a record selects in the "
                    + "account master dataset '" + datasetName + "' before rewriting it");
        }
        if (matching == 0) {
            return WriteResult.notFound();
        }
        if (matching > 1) {
            LOG.error("The key of account " + maskedKey + " selects more than one row of dataset '"
                    + datasetName + "'; a KSDS primary key is unique, so the rewrite is being refused "
                    + "before it is issued and file status "
                    + FileStatus.toStatusImage(PERMANENT_ERROR_STATUS) + " reported to the caller. "
                    + "No row has been changed.");
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
            return reportWrite(rejected, "rewrite a record in the account master dataset '" + datasetName
                    + "'");
        }

        if (rewritten == 1) {
            return WriteResult.written();
        }
        if (rewritten == 0) {
            return WriteResult.notFound();
        }
        throw DatasetUnitOfWork.commitRefusal(
                "The rewrite of account " + maskedKey + " in dataset '" + datasetName + "'",
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
        Objects.requireNonNull(counted, "The row counter returns a count, never null");
        return counted;
    }

    private ReadResult readKeyed(String keyImage, String subject, boolean forUpdate) {
        Statements sql;
        try {
            sql = resolveStatements();
        } catch (DataAccessException unreachable) {
            return reportRead(unreachable, "describe the account master dataset '" + datasetName
                    + "' to read " + subject);
        }
        return readKeyed(forUpdate ? sql.selectByKeyForUpdate() : sql.selectByKey(), keyImage,
                subject, sql.probeUnreadableRows());
    }

    private ReadResult readKeyed(String statement, String keyImage, String subject,
            String unreadableRowsProbe) {
        List<byte[]> rows;
        try {
            rows = jdbcTemplate.query(firstRowMatching(statement, asPrefixPattern(keyImage)),
                    recordImageMapper());
        } catch (DataAccessException translated) {
            return reportRead(translated, "read " + subject + " from the account master dataset '"
                    + datasetName + "'");
        }

        if (rows.isEmpty()) {
            return provenAbsence(subject, unreadableRowsProbe);
        }
        byte[] recordImage = rows.get(0);
        if (recordImage == null) {
            LOG.error("The account master dataset '" + datasetName + "' presented " + subject
                    + " with no record image at column position " + RECORD_IMAGE_COLUMN_INDEX
                    + "; reporting file status " + FileStatus.toStatusImage(PERMANENT_ERROR_STATUS)
                    + " to the caller");
            return ReadResult.of(PERMANENT_ERROR_STATUS, CicsResponse.of(FileStatus.INVREQ));
        }
        return decoded(recordImage, subject);
    }

    private ReadResult provenAbsence(String subject, String unreadableRowsProbe) {
        List<byte[]> unreadable;
        try {
            unreadable = jdbcTemplate.query(firstRow(unreadableRowsProbe), recordImageMapper());
        } catch (DataAccessException translated) {
            return reportRead(translated, "establish that the account master dataset '" + datasetName
                    + "' holds no unreadable row before reporting " + subject + " as absent");
        }
        if (unreadable.isEmpty()) {
            return ReadResult.notFound();
        }
        LOG.error("A keyed read of " + subject + " from the account master dataset '" + datasetName
                + "' matched no row, but the dataset holds a row with no record image at column position "
                + RECORD_IMAGE_COLUMN_INDEX + " - and ACCT-ID is part of that image, so that row's key "
                + "cannot be known; reporting file status "
                + FileStatus.toStatusImage(PERMANENT_ERROR_STATUS) + " rather than reporting as absent a "
                + "record that may be the one asked for");
        return ReadResult.of(PERMANENT_ERROR_STATUS, CicsResponse.of(FileStatus.INVREQ));
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

    private Statements statementsFor(String subject) {
        try {
            return resolveStatements();
        } catch (DataAccessException unreachable) {
            // The subject names the operation that failed, exactly as the sibling repositories report it
            // (CustomerRepository, TranCatBalRepository). Dropping it left one message - "Could not
            // describe the account master dataset 'X'" - standing for every caller of this method, which
            // is the one thing an operator reading the log needs it to distinguish.
            logRefusal(unreachable, "describe the account master dataset '" + datasetName + "' for "
                    + subject);
            return null;
        }
    }

    private void requireUnitOfWork() {
        DatasetUnitOfWork.requireActive("A read for update of the account master, which "
                + "EXEC CICS READ ... UPDATE holds for the unit of work "
                + "(app/cbl/COACTUPC.cbl:L3894-L3903, and the 'Could we lock the account record ?' "
                + "test at L3903) so that 9700-CHECK-CHANGE-IN-REC can compare and REWRITE can run - "
                + "or use readByKey(long) when no lock is wanted", datasetName);
    }

    Statements resolveStatements() {
        ResultSetExtractor<String> columnNameExtractor =
                AccountRepository::extractRecordImageColumnName;
        String columnName = jdbcTemplate.query(relation.describeStatement(), columnNameExtractor);
        return Statements.over(relation, requireUsableColumnName(columnName));
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
            LOG.error("The account master dataset '" + datasetName + "' presented " + subject + " as "
                    + recordImage.length + " byte(s), but ACCOUNT-RECORD is declared " + RECORD_LENGTH
                    + " bytes by app/cpy/CVACT01Y.cpy; reporting file status "
                    + FileStatus.toStatusImage(PERMANENT_ERROR_STATUS) + " rather than decoding fields "
                    + "from offsets that would not be theirs");
            return ReadResult.of(PERMANENT_ERROR_STATUS, CicsResponse.of(FileStatus.LENGERR));
        }
        return ReadResult.found(AccountRecord.decode(recordImage, codec.charset()));
    }

    private static String asPrefixPattern(String keyImage) {
        return KEY_SPAN.pattern(keyImage);
    }

    private static DatasetBinding requireAccountGeometry(DatasetBindings datasetBindings,
            String ddName) {
        DatasetBinding binding = datasetBindings.binding(ddName);
        if (binding.recordLength() != RECORD_LENGTH) {
            throw new IllegalStateException("Dataset binding for DD name '" + ddName + "' declares a "
                    + "record length of " + binding.recordLength() + ", but the account record is "
                    + RECORD_LENGTH + " bytes - app/cpy/CVACT01Y.cpy:L2 reads RECLN 300, and "
                    + "app/cbl/CBACT01C.cbl:L38-L40 declares FD-ACCT-ID PIC 9(11) plus FD-ACCT-DATA "
                    + "PIC X(289) independently. This repository addresses the record by absolute "
                    + "offset, so a differently-sized record would misplace every field after the "
                    + "first. Correct carddemo.datasets." + ddName + ".record-length to "
                    + RECORD_LENGTH + ".");
        }
        Integer declaredKeyLength = binding.keyLength();
        if (declaredKeyLength != null && declaredKeyLength != KEY_LENGTH) {
            throw new IllegalStateException("Dataset binding for DD name '" + ddName + "' declares a "
                    + "key length of " + declaredKeyLength + ", but the account master key is "
                    + KEY_LENGTH + " bytes - ACCT-ID PIC 9(11) at app/cpy/CVACT01Y.cpy:L5, which is "
                    + "also what app/cbl/COACTUPC.cbl:L3898 passes as KEYLENGTH. Correct "
                    + "carddemo.datasets." + ddName + ".key-length to " + KEY_LENGTH + ", or omit it.");
        }
        return binding;
    }

    /**
     * Requires the CICS file and the batch DD to name the same dataset.
     *
     * @param cicsBinding the binding resolved for {@link #CICS_FILE_NAME}
     * @param batchBinding the binding resolved for {@link #BATCH_DD_NAME}
     * @throws IllegalStateException if the two bindings name different datasets
     */
    private static void requireSameDataset(DatasetBinding cicsBinding, DatasetBinding batchBinding) {
        if (!Objects.equals(cicsBinding.dsname(), batchBinding.dsname())) {
            throw new IllegalStateException("The dataset bindings for DD names '" + CICS_FILE_NAME
                    + "' and '" + BATCH_DD_NAME + "' name different datasets, but they are two names "
                    + "for one account master: app/csd/CARDDEMO.CSD:L1-L2 defines the CICS file and "
                    + "app/jcl/READACCT.jcl:L25-L26 binds the batch DD over the same dataset. Point "
                    + "carddemo.datasets." + CICS_FILE_NAME + ".dsname and carddemo.datasets."
                    + BATCH_DD_NAME + ".dsname at the same dataset.");
        }
    }

    private static String requireUsableDatasetName(String candidate) {
        if (candidate == null || candidate.isBlank()) {
            throw new IllegalStateException("The dataset binding for DD name '" + CICS_FILE_NAME
                    + "' declares no dataset name. Set carddemo.datasets." + CICS_FILE_NAME
                    + ".dsname; this repository composes its statements from configuration alone and "
                    + "hard-codes no dataset name.");
        }
        return DatasetRelation.requireDatasetName(candidate);
    }

    private static String requireUsableColumnName(String candidate) {
        if (candidate == null || candidate.isBlank()) {
            throw new IllegalStateException("The backend describes no column at position "
                    + RECORD_IMAGE_COLUMN_INDEX + " for the account master dataset, so the record "
                    + "image cannot be addressed. Every CardDemo dataset is reached as a relation whose "
                    + "column at that position holds the whole fixed-width record image; a driver or a "
                    + "seeded dataset that presents no such column cannot be read or written by this "
                    + "module at all.");
        }
        requireNoControlCharacter(candidate, "The record-image column name reported by the backend");
        return candidate;
    }

    private static void requireNoControlCharacter(String candidate, String subject) {
        for (int index = 0; index < candidate.length(); index++) {
            if (Character.isISOControl(candidate.charAt(index))) {
                throw new IllegalStateException(subject + " contains a control character at position "
                        + index + "; an identifier cannot contain one, and it would corrupt the "
                        + "composed statements. Correct the value.");
            }
        }
    }

    private static String requireKeyImage(String candidate) {
        Objects.requireNonNull(candidate, "A key image is required to read a record for update; the "
                + "RIDFLD is a PIC X(11) field and is never absent, only spaces");
        if (candidate.length() != KEY_LENGTH) {
            throw new IllegalArgumentException("The account master key is declared PIC X(" + KEY_LENGTH
                    + ") but was given " + candidate.length() + " character(s). A fixed-width key "
                    + "carries its padding, so it is never trimmed and never short - render an "
                    + "account identifier with AccountRecord.keyImage(long, Charset).");
        }
        return candidate;
    }

    /**
     * The five statements this repository issues, composed once the record-image column is known.
     *
     * @param selectFirst the first read of a browse: every record in ascending key order, limited to one
     *     row by the statement
     * @param selectNext every later read of a browse: the first record whose image sorts strictly after the
     *     one already returned, in ascending key order
     * @param selectByKey the keyed read: the record whose image begins with the key
     * @param selectByKeyForUpdate the keyed read with the row lock the CICS {@code READ ... UPDATE} takes,
     *     valid only inside a unit of work
     * @param rewrite the rewrite: replace the image of the record whose image begins with the key
     * @param probeUnreadableRows the rows whose record-image column holds nothing
     */
    record Statements(String selectFirst,
                      String selectNext,
                      String selectByKey,
                      String selectByKeyForUpdate,
                      String rewrite,
                      String probeUnreadableRows) {
        static Statements over(DatasetRelation relation, String column) {
            String recordImageColumn = relation.rememberRecordImageColumn(column);
            return new Statements(
                    relation.selectAllAscending(recordImageColumn),
                    relation.selectAfterAscending(recordImageColumn),
                    relation.selectByKey(recordImageColumn),
                    relation.selectByKeyForUpdate(recordImageColumn),
                    relation.rewriteByKey(recordImageColumn),
                    relation.selectUnreadableRows(recordImageColumn));
        }
    }

    String columnProbeSql() {
        return relation.describeStatement();
    }

    /**
     * One opened account master file: the Java form of the {@code FD} a program opens, reads and closes.
     *
     * <p>A handle stands for one file position and is used by whoever opened it, exactly as a COBOL
     * {@code FD} is used by the program that opened it.
     */
    public static final class AccountFile implements AutoCloseable {
        private final AccountRepository repository;

        private final OpenMode mode;

        private final String openStatus;

        private final Statements statements;

        private byte[] browsePosition;

        private boolean closed;

        private AccountFile(AccountRepository repository, OpenMode mode, String openStatus,
                Statements statements) {
            this.repository = repository;
            this.mode = mode;
            this.openStatus = openStatus;
            this.statements = statements;
            this.browsePosition = null;
            this.closed = false;
        }

        /**
         * The status the {@code OPEN} reported: the value {@code app/cbl/CBACT01C.cbl:L137} tests before
         * moving {@code 0} or {@code 12} into {@code APPL-RESULT}.
         *
         * @return {@link FileStatus#OK} or {@link AccountRepository#PERMANENT_ERROR_STATUS}; never
         *     {@code null}, always two characters
         */
        public String openStatus() {
            return openStatus;
        }

        public Outcome openOutcome() {
            return FileStatus.outcomeOfStatus(openStatus);
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
         * {@code READ ACCTFILE-FILE INTO ACCOUNT-RECORD} ({@code app/cbl/CBACT01C.cbl:L93}) and of the
         * {@code EVALUATE}-equivalent guard that classifies its status at {@code L94-L103}.
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
                LOG.error("Rejected browse read of the account master dataset '" + datasetName()
                        + "' - " + BackendDiagnostic.of(translated).describe() + "; reporting file status "
                        + FileStatus.toStatusImage(PERMANENT_ERROR_STATUS) + " to the caller");
                return ReadResult.of(PERMANENT_ERROR_STATUS);
            }

            if (rows.isEmpty()) {
                return ReadResult.endOfFile();
            }
            byte[] recordImage = rows.get(0);
            if (recordImage == null) {
                LOG.error("The account master dataset '" + datasetName() + "' presented a row with no "
                        + "record image at column position " + RECORD_IMAGE_COLUMN_INDEX + "; reporting "
                        + "file status " + FileStatus.toStatusImage(PERMANENT_ERROR_STATUS)
                        + " to the caller");
                return ReadResult.of(PERMANENT_ERROR_STATUS, CicsResponse.of(FileStatus.INVREQ));
            }

            this.browsePosition = recordImage.clone();
            return repository.decoded(recordImage, "the row at the current browse position");
        }

        /**
         * Reads one record by account identifier under this open: {@link AccountRepository#readByKey(long)}
         * against the shape this {@code OPEN} already resolved.
         *
         * <p>The browse position is untouched, exactly as a COBOL random {@code READ} leaves the position
         * of a sequential browse of the same file alone.
         *
         * @param acctId the account identifier; must not be negative, as {@code PIC 9} declares no sign
         *     position and therefore has no representation for one
         * @return the discriminated outcome; never {@code null}
         * @throws IllegalStateException if this handle has been closed
         * @throws IllegalArgumentException if {@code acctId} is negative, or if the stored image is wider
         *     than {@link AccountRepository#RECORD_LENGTH}
         */
        public ReadResult readByKey(long acctId) {
            requireOpen("read a record by key");
            if (statements == null) {
                return ReadResult.of(openStatus);
            }
            return repository.readKeyed(statements.selectByKey(),
                    AccountRecord.keyImage(acctId, repository.codec.charset()),
                    "the account identifier " + acctId, statements.probeUnreadableRows());
        }

        /**
         * Reads one record for update under this open: {@link AccountRepository#readForUpdate(String)}
         * against the shape this {@code OPEN} already resolved.
         *
         * @param acctIdAsChar11 the key exactly as the {@code RIDFLD} holds it: exactly
         *     {@link AccountRepository#KEY_LENGTH} characters, untrimmed and unparsed
         * @return the discriminated outcome; never {@code null}
         * @throws NullPointerException if {@code acctIdAsChar11} is {@code null}
         * @throws IllegalArgumentException if {@code acctIdAsChar11} is not exactly
         *     {@link AccountRepository#KEY_LENGTH} characters
         * @throws IllegalStateException if this handle has been closed, or if no transaction is active
         */
        public ReadResult readForUpdate(String acctIdAsChar11) {
            String keyImage = requireKeyImage(acctIdAsChar11);
            requireOpen("read a record for update");
            repository.requireUnitOfWork();
            if (statements == null) {
                return ReadResult.of(openStatus);
            }
            return repository.readKeyed(statements.selectByKeyForUpdate(), keyImage,
                    "the record identification field for update", statements.probeUnreadableRows());
        }

        /**
         * Rewrites one record under this open: {@link AccountRepository#rewrite(AccountRecord)} against the
         * shape this {@code OPEN} already resolved.
         *
         * @param record the record to write, complete and already mutated by the caller
         * @return the discriminated outcome; never {@code null}
         * @throws NullPointerException if {@code record} is {@code null}
         * @throws IllegalStateException if this handle has been closed; if no unit of work is open, in
         *     which case nothing has been attempted - {@code CBACT04C}'s rewrite reaches this through
         */
        public WriteResult rewrite(AccountRecord record) {
            Objects.requireNonNull(record, "A record is required to rewrite it; a COBOL REWRITE writes "
                    + "the record area, and there is no such thing as rewriting nothing");
            requireOpen("rewrite a record");
            if (statements == null) {
                return WriteResult.of(openStatus);
            }
            return repository.rewrite(statements, record);
        }

        /**
         * Closes this file, reporting only the resulting file status.
         *
         * @return {@link FileStatus#OK} when the dataset is still addressable, otherwise
         *     {@link AccountRepository#PERMANENT_ERROR_STATUS}; never {@code null}, always two characters
         * @throws IllegalStateException if the backend presents the dataset with no usable record-image
         *     column
         */
        public String closeFile() {
            if (closed) {
                return FileStatus.OK;
            }
            closed = true;
            this.browsePosition = null;
            if (statements == null) {
                return openStatus;
            }
            return repository.statementsFor("a close of the account master") == null
                    ? PERMANENT_ERROR_STATUS
                    : FileStatus.OK;
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
                throw new IllegalStateException("This open of the account master dataset '"
                        + datasetName() + "' has been closed, so it cannot " + operation + ". A COBOL "
                        + "program closes once, after its work - operating afterwards is a defect in the "
                        + "caller, not a file status. Open another file instead.");
            }
        }
    }

    /**
     * The {@code OPEN} verbs the estate issues against the account master, and the only two it issues.
     */
    public enum OpenMode {
        INPUT("OPEN INPUT"),

        I_O("OPEN I-O");

        private final String cobolVerb;

        OpenMode(String cobolVerb) {
            this.cobolVerb = cobolVerb;
        }

        /**
         * The COBOL verb this mode stands for, for a diagnostic that names what the program did.
         *
         * @return {@code "OPEN INPUT"} or {@code "OPEN I-O"}
         */
        public String cobolVerb() {
            return cobolVerb;
        }
    }

    /**
     * The outcome of one read of the account master: the classification the COBOL guard chain performs, and
     * nothing beyond it.
     *
     * @param status the two-character file status the read reported, verbatim
     * @param outcome its classification
     * @param account the decoded record, present exactly when {@code outcome} is {@link Outcome#OK}
     * @param diagnostic what the backend reported when it refused, present only on a failure it described -
     *     so a caller can log the driver's own {@code SQLSTATE} instead of a status this module synthesised
     * @param response the CICS {@code RESP}/{@code RESP2} pair for this outcome, carried rather than
     *     derived on demand
     */
    public record ReadResult(String status,
                            Outcome outcome,
                            Optional<AccountRecord> account,
                            Optional<BackendDiagnostic> diagnostic,
                            CicsResponse response) {
        public ReadResult {
            requireConsistentStatus(status, outcome);
            Objects.requireNonNull(account, "A read result carries an empty record rather than a null "
                    + "one, so no null escapes the type");
            Objects.requireNonNull(diagnostic, "A read result carries an empty diagnostic rather than a "
                    + "null one, so no null escapes the type");
            Objects.requireNonNull(response, "A read result carries a CICS response pair rather than a "
                    + "null one; use CicsResponse.ofBatchStatus(status) where the outcome is a file "
                    + "status and CicsResponse.reported(resp, resp2) where a backend surfaced both");
            if (account.isPresent() != (outcome == Outcome.OK)) {
                throw new IllegalArgumentException(account.isPresent()
                        ? "A read that did not succeed carries no record: outcome " + outcome
                                + " was given one. Only the '00' arm reaches ACCOUNT-RECORD."
                        : "A successful read carries the decoded record, and this one carries none; "
                                + "build it with ReadResult.found(AccountRecord).");
            }
        }

        /**
         * The successful arm: status {@code '00'}, carrying the decoded record.
         *
         * @param account the decoded record
         * @return a result carrying {@link FileStatus#OK} and the record
         * @throws NullPointerException if {@code account} is {@code null}
         */
        public static ReadResult found(AccountRecord account) {
            Objects.requireNonNull(account, "A successful read carries the decoded account record");
            return new ReadResult(FileStatus.OK, Outcome.OK, Optional.of(account), Optional.empty(),
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
         * The invalid-key arm: status {@code '23'}, carrying no record.
         *
         * @return a result carrying {@link FileStatus#NOT_FOUND} and no record
         */
        public static ReadResult notFound() {
            return of(FileStatus.NOT_FOUND);
        }

        /**
         * A result for any status other than success, classified from the status itself.
         *
         * @param status the two-character status the read reported, carried verbatim so the caller can
         *     render it exactly as {@code 9910-DISPLAY-IO-STATUS} does
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
         * @throws NullPointerException if {@code diagnostic} is {@code null}
         */
        public static ReadResult of(String status, BackendDiagnostic diagnostic) {
            return of(status, CicsResponse.ofBatchStatus(status), diagnostic);
        }

        /**
         * A failed read carrying both the CICS pair to report and what the backend said about it.
         *
         * @param status the file status the caller branches on
         * @param response the pair to report
         * @param diagnostic what the backend reported
         * @return the outcome
         * @throws NullPointerException if {@code response} or {@code diagnostic} is {@code null}
         */
        public static ReadResult of(String status, CicsResponse response,
                BackendDiagnostic diagnostic) {
            Objects.requireNonNull(diagnostic, "A diagnostic is required by this factory; use "
                    + "of(String) where there is no backend refusal to report");
            return new ReadResult(status, classify(status), Optional.empty(), Optional.of(diagnostic),
                    response);
        }

        /**
         * Whether the read succeeded and a record is available.
         *
         * @return {@code true} for the {@code '00'} arm
         */
        public boolean isFound() {
            return outcome == Outcome.OK;
        }

        /**
         * Whether the browse reached the end of the dataset: the {@code IF APPL-EOF} test.
         *
         * @return {@code true} for the {@code '10'} arm
         */
        public boolean isEndOfFile() {
            return outcome == Outcome.END_OF_FILE;
        }

        /**
         * Whether the keyed record was absent: the {@code INVALID KEY} condition, and
         * {@code DFHRESP(NOTFND)}.
         *
         * @return {@code true} for the {@code '23'} arm
         */
        public boolean isNotFound() {
            return outcome == Outcome.NOT_FOUND;
        }

        /**
         * Whether the read failed for any other reason: the {@code WHEN OTHER} arm, which every guard chain
         * over this dataset ends in an abend.
         *
         * @return {@code true} for the catch-all arm
         */
        public boolean isOther() {
            return outcome == Outcome.OTHER;
        }

        /**
         * The CICS {@code RESP} value this outcome reports.
         *
         * @return the response value, or an empty {@code OptionalInt}
         */
        public OptionalInt cicsResp() {
            return response.resp();
        }

        /**
         * The CICS {@code RESP2} reason code this outcome reports.
         *
         * @return the reason code, never negative
         */
        public int cicsResp2() {
            return response.resp2();
        }

        /**
         * The {@code APPL-RESULT} value the COBOL guard moves for this outcome.
         *
         * <p>{@code 0} on success, {@code 16} at end of file, and {@code 12} for everything else - exactly
         * the ladder at {@code app/cbl/CBACT01C.cbl:L94-L103}.
         *
         * @return {@link FileStatus#APPL_AOK}, {@link FileStatus#APPL_EOF} or
         *     {@link AccountRepository#APPL_RESULT_FATAL}
         */
        public int applResult() {
            return switch (outcome) {
                case OK -> FileStatus.APPL_AOK;
                case END_OF_FILE -> FileStatus.APPL_EOF;
                default -> APPL_RESULT_FATAL;
            };
        }
    }

    /**
     * The outcome of one rewrite of the account master: the classification {@code 1050-UPDATE-ACCOUNT}'s
     * guard performs ({@code app/cbl/CBACT04C.cbl:L357-L361}), and nothing beyond it.
     *
     * @param status the two-character file status the rewrite reported, verbatim
     * @param outcome its classification
     * @param diagnostic what the backend reported when it refused, present only on a failure it described
     * @param response the CICS {@code RESP}/{@code RESP2} pair for this outcome, carried rather than
     *     derived on demand
     */
    public record WriteResult(String status, Outcome outcome, Optional<BackendDiagnostic> diagnostic,
            CicsResponse response) {
        public WriteResult {
            requireConsistentStatus(status, outcome);
            Objects.requireNonNull(diagnostic, "A write result carries an empty diagnostic rather than a "
                    + "null one, so no null escapes the type");
            Objects.requireNonNull(response, "A write result carries a CICS response pair rather than a "
                    + "null one; use CicsResponse.ofBatchStatus(status) where the outcome is a file "
                    + "status and CicsResponse.reported(resp, resp2) where a backend surfaced both");
            if (outcome == Outcome.END_OF_FILE) {
                throw new IllegalArgumentException("A rewrite cannot reach the end of a dataset, so "
                        + "status '" + FileStatus.END_OF_FILE + "' is not an outcome it can report.");
            }
        }

        /**
         * The successful arm: status {@code '00'}, one record rewritten.
         *
         * @return a result carrying {@link FileStatus#OK}
         */
        public static WriteResult written() {
            return of(FileStatus.OK);
        }

        /**
         * The invalid-key arm: status {@code '23'}, no record rewritten.
         *
         * @return a result carrying {@link FileStatus#NOT_FOUND}
         */
        public static WriteResult notFound() {
            return of(FileStatus.NOT_FOUND);
        }

        /**
         * A result for any status, classified from the status itself.
         *
         * @param status the two-character status the rewrite reported, carried verbatim
         * @return a result carrying {@code status} and its classification
         * @throws NullPointerException if {@code status} is {@code null}
         * @throws IllegalArgumentException if {@code status} is not exactly
         *     {@link FileStatus#STATUS_LENGTH} characters, or is {@link FileStatus#END_OF_FILE}
         */
        public static WriteResult of(String status) {
            return new WriteResult(status, classify(status), Optional.empty(),
                    CicsResponse.ofBatchStatus(status));
        }

        /**
         * A result carrying the CICS pair the outcome should report.
         *
         * @param status the two-character status the rewrite reported, carried verbatim
         * @param response the pair to report
         * @return a result carrying {@code status}, its classification and {@code response}
         * @throws NullPointerException if {@code status} or {@code response} is {@code null}
         * @throws IllegalArgumentException if {@code status} is not exactly
         *     {@link FileStatus#STATUS_LENGTH} characters, or is {@link FileStatus#END_OF_FILE}
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
         * @throws NullPointerException if {@code diagnostic} is {@code null}
         */
        public static WriteResult of(String status, BackendDiagnostic diagnostic) {
            return of(status, CicsResponse.ofBatchStatus(status), diagnostic);
        }

        /**
         * A failed write carrying both the CICS pair to report and what the backend said about it.
         *
         * @param status the file status the caller branches on
         * @param response the pair to report
         * @param diagnostic what the backend reported
         * @return the outcome
         * @throws NullPointerException if {@code response} or {@code diagnostic} is {@code null}
         */
        public static WriteResult of(String status, CicsResponse response,
                BackendDiagnostic diagnostic) {
            Objects.requireNonNull(diagnostic, "A diagnostic is required by this factory; use "
                    + "of(String) where there is no backend refusal to report");
            return new WriteResult(status, classify(status), Optional.of(diagnostic), response);
        }

        /**
         * Whether the rewrite succeeded: the {@code IF APPL-AOK} test, and
         * {@code IF WS-RESP-CD EQUAL TO DFHRESP(NORMAL)}.
         *
         * @return {@code true} for the {@code '00'} arm
         */
        public boolean isWritten() {
            return outcome == Outcome.OK;
        }

        public boolean isNotFound() {
            return outcome == Outcome.NOT_FOUND;
        }

        /**
         * Whether the rewrite failed for any other reason.
         *
         * @return {@code true} for the catch-all arm
         */
        public boolean isOther() {
            return outcome == Outcome.OTHER;
        }

        /**
         * The CICS {@code RESP} value this outcome reports, read from the carried pair rather than
         * recomputed from the status.
         *
         * @return the response value, or an empty {@code OptionalInt}
         */
        public OptionalInt cicsResp() {
            return response.resp();
        }

        /**
         * The CICS {@code RESP2} reason code this outcome reports.
         *
         * @return the reason code, never negative
         */
        public int cicsResp2() {
            return response.resp2();
        }

        /**
         * The {@code APPL-RESULT} value the COBOL guard moves for this outcome: {@code 0} on success and
         * {@code 12} otherwise ({@code app/cbl/CBACT04C.cbl:L357-L361}).
         *
         * @return {@link FileStatus#APPL_AOK} or {@link AccountRepository#APPL_RESULT_FATAL}
         */
        public int applResult() {
            return isWritten() ? FileStatus.APPL_AOK : APPL_RESULT_FATAL;
        }
    }

    private static Outcome classify(String status) {
        return FileStatus.outcomeOfStatus(requireStatusWidth(status));
    }

    private static void requireConsistentStatus(String status, Outcome outcome) {
        Objects.requireNonNull(outcome, "An outcome carries its classification; it is never absent");
        Outcome classified = classify(status);
        if (outcome != classified) {
            throw new IllegalArgumentException("Status '" + FileStatus.toStatusImage(status)
                    + "' classifies as " + classified + ", but " + outcome + " was given; a status and "
                    + "its classification must agree, so that no caller can branch on one and read the "
                    + "other. Use one of the named factory methods.");
        }
    }

    /**
     * Requires a status to be exactly two characters, as every COBOL {@code FILE STATUS} field is.
     *
     * @param status the status to check
     * @return {@code status}, unchanged
     * @throws NullPointerException if {@code status} is {@code null}
     * @throws IllegalArgumentException if {@code status} is not exactly {@link FileStatus#STATUS_LENGTH}
     *     characters
     */
    private static String requireStatusWidth(String status) {
        Objects.requireNonNull(status, "An outcome carries the two-character file status the operation "
                + "reported; it is never absent");
        if (status.length() != FileStatus.STATUS_LENGTH) {
            throw new IllegalArgumentException("A file status is exactly " + FileStatus.STATUS_LENGTH
                    + " characters, as ACCTFILE-STATUS is declared at app/cbl/CBACT01C.cbl:L46-L48; got "
                    + status.length() + ".");
        }
        return status;
    }
}
