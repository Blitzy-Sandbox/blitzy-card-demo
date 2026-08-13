package com.vsergeychik.carddemo.transaction;

import com.vsergeychik.carddemo.common.CobolDecimal;
import com.vsergeychik.carddemo.common.DatasetObservation;
import com.vsergeychik.carddemo.common.DatasetRelation;
import com.vsergeychik.carddemo.common.DatasetRelation.BackendDiagnostic;
import com.vsergeychik.carddemo.common.FileStatus;
import com.vsergeychik.carddemo.common.FileStatus.Outcome;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.FixedWidthRecord.RecordLayout;
import com.vsergeychik.carddemo.common.PhysicalSequence;
import com.vsergeychik.carddemo.common.RecordImageForm;
import com.vsergeychik.carddemo.config.CobolCharsetConfig;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBinding;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBindings;
import com.vsergeychik.carddemo.transaction.model.DalyTranRecord;

import java.nio.charset.Charset;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The {@code DALYTRAN} daily-transaction dataset, read forward and read only.
 *
 * <p>{@code grep -n "DALYTRAN" app/cbl/*.cbl} returns, across both consumers, exactly three verbs against
 * {@code DALYTRAN-FILE}: {@code OPEN INPUT}, {@code READ ... INTO} and {@code CLOSE}.
 */
@Repository
public class DalyTranRepository {
    private static final Log LOG = LogFactory.getLog(DalyTranRepository.class);

    /**
     * The DD name both consumers assign this dataset to: {@code DALYTRAN}.
     */
    public static final String DD_NAME = "DALYTRAN";

    /**
     * The declared record width in bytes: {@value}, delegated to {@link DalyTranRecord#RECORD_LENGTH} so
     * the copybook has exactly one Java home.
     */
    public static final int RECORD_LENGTH = DalyTranRecord.RECORD_LENGTH;

    public static final int RECORD_IMAGE_COLUMN_INDEX = DatasetRelation.RECORD_IMAGE_COLUMN_INDEX;

    /**
     * The driver fetch size the sequential read asks for: {@value}.
     */
    public static final int FETCH_SIZE = 32;

    /**
     * The scale {@code DALYTRAN-AMT} decodes at: {@value}, from {@link CobolDecimal#MONETARY_SCALE}.
     */
    public static final int AMOUNT_SCALE = CobolDecimal.MONETARY_SCALE;

    /**
     * {@code APPL-RESULT} for a successful read: {@value}, from {@link FileStatus#APPL_AOK}.
     */
    public static final int APPL_RESULT_OK = FileStatus.APPL_AOK;

    /**
     * {@code APPL-RESULT} for the end of the file: {@value}, from {@link FileStatus#APPL_EOF}.
     */
    public static final int APPL_RESULT_EOF = FileStatus.APPL_EOF;

    /**
     * {@code APPL-RESULT} for a failed read, open or close: {@value}.
     */
    public static final int APPL_RESULT_FATAL = 12;

    private static final char PERMANENT_ERROR_FEEDBACK_CODE = 0;

    /**
     * The two-character file status reported for a permanent error: {@code '9'} followed by
     * {@link #PERMANENT_ERROR_FEEDBACK_CODE}.
     */
    public static final String PERMANENT_ERROR_STATUS = "9" + PERMANENT_ERROR_FEEDBACK_CODE;

    private final JdbcTemplate jdbcTemplate;

    private final FixedWidthCodec codec;

    private final RecordImageForm recordImageForm;

    private final DatasetRelation relation;

    private final PhysicalSequence physicalSequence;

    private final String selectRecordSql;

    private final RecordLayout layout;

    /**
     * Resolves the {@code DALYTRAN} binding, proves the record geometry and captures the collaborators this
     * repository needs - all of it while the context is still building, so nothing that can be checked
     * early is left to fail in the middle of a posting run.
     *
     * @param jdbcTemplate the module's shared template, whose data source the read cursor is taken from
     * @param datasetBindings the {@code carddemo.datasets} catalogue - dataset names live in configuration
     *     and are never written in Java
     * @param datasetCharset the dataset code page, injected as
     *     {@value CobolCharsetConfig#DATASET_CHARSET_BEAN_NAME} and stated explicitly at every encode and
     *     decode
     * @param recordImageForm how the deployment's driver presents a record image, from
     *     {@value RecordImageForm#FORM_PROPERTY}
     * @param physicalSequence the physical-record ordinal this dataset's sequential read is ordered by,
     *     from {@value PhysicalSequence#EXPRESSION_PROPERTY}
     * @throws NullPointerException if any argument is {@code null}
     * @throws IllegalStateException if no binding is configured for {@link #DD_NAME}, if the binding
     *     declares a record length other than {@link #RECORD_LENGTH}, if the declared spans no longer account
     *     for every byte
     * @throws IllegalArgumentException if {@code datasetCharset} is not a single-byte code page
     */
    public DalyTranRepository(
            JdbcTemplate jdbcTemplate,
            DatasetBindings datasetBindings,
            @Qualifier(CobolCharsetConfig.DATASET_CHARSET_BEAN_NAME) Charset datasetCharset,
            RecordImageForm recordImageForm,
            PhysicalSequence physicalSequence) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "A JdbcTemplate is required: the "
                + DD_NAME + " read cursor is opened on the data source the module's shared template "
                + "was configured with");
        Objects.requireNonNull(datasetBindings, "The carddemo.datasets binding catalogue is required: "
                + "dataset names live in configuration and are never written in Java");
        this.codec = new FixedWidthCodec(Objects.requireNonNull(datasetCharset, "A dataset charset is "
                + "required: a fixed-width mainframe record is bytes in a specific code page, so the "
                + "code page is stated explicitly and never taken from the platform"));
        this.recordImageForm = Objects.requireNonNull(recordImageForm, "A record-image representation "
                + "is required: whether this deployment's driver presents a record image as characters "
                + "or as bytes is stated once, by " + RecordImageForm.FORM_PROPERTY + ", and never "
                + "decided per repository");
        this.physicalSequence = Objects.requireNonNull(physicalSequence, "A physical-record ordinal is "
                + "required: " + DD_NAME + " is a physical-sequential dataset, so the order its records "
                + "were written in is what a sequential READ returns, and SQL returns rows in no order "
                + "unless a statement says which. It is stated once, by "
                + PhysicalSequence.EXPRESSION_PROPERTY + ", and never decided per repository");
        RecordImageForm.requireSingleByteCodePage(datasetCharset);

        DatasetBinding binding = datasetBindings.binding(DD_NAME);
        if (binding.recordLength() != RECORD_LENGTH) {
            throw new IllegalStateException("Dataset binding for DD name '" + DD_NAME + "' declares a "
                    + "record length of " + binding.recordLength() + ", but the daily-transaction "
                    + "record is " + RECORD_LENGTH + " bytes: app/cpy/CVTRA06Y.cpy declares "
                    + "RECLN = " + RECORD_LENGTH + " and app/cbl/CBTRN02C.cbl:L66-L69 confirms it as "
                    + "FD-TRAN-ID PIC X(16) plus FD-CUST-DATA PIC X(334). This repository addresses "
                    + "the record by absolute offset, so a differently-sized record would misplace "
                    + "every field after DALYTRAN-ID. Correct carddemo.datasets." + DD_NAME
                    + ".record-length to " + RECORD_LENGTH + ".");
        }
        this.layout = requireDeclaredGeometry(DalyTranRecord.LAYOUT,
                DalyTranRecord.sumOfDeclaredSpanLengths());
        this.relation = DatasetRelation.of(requireUsableDatasetName(binding.dsname()), RECORD_LENGTH);
        this.selectRecordSql = this.relation.selectAllInPhysicalSequence(this.physicalSequence);
    }

    /**
     * The resolved dataset name, exactly as configuration declares it.
     *
     * @return the configured dataset name for {@link #DD_NAME}; never {@code null} and never blank
     */
    public String datasetName() {
        return relation.dsname();
    }

    /**
     * The record width this repository reads, always {@link #RECORD_LENGTH}.
     *
     * @return {@link #RECORD_LENGTH}
     */
    public int recordLength() {
        return RECORD_LENGTH;
    }

    /**
     * The code page this repository decodes record images in.
     *
     * @return the injected dataset charset; never {@code null}
     */
    public Charset datasetCharset() {
        return codec.charset();
    }

    String selectRecordSql() {
        return selectRecordSql;
    }

    RecordLayout layout() {
        return layout;
    }

    /**
     * Opens the dataset for input and positions before its first record: the Java form of
     * {@code OPEN INPUT DALYTRAN-FILE}.
     *
     * @return the open handle, successful or failed; never {@code null}
     */
    public DalytranFile open() {
        DataSource dataSource = jdbcTemplate.getDataSource();
        if (dataSource == null) {
            LOG.error("Could not open the " + DD_NAME + " dataset for input: the module's JdbcTemplate "
                    + "carries no DataSource, so there is no connection to read the dataset through. "
                    + "Reporting file status " + FileStatus.toStatusImage(PERMANENT_ERROR_STATUS)
                    + " to the caller");
            return new DalytranFile(this, PERMANENT_ERROR_STATUS, null);
        }

        Connection connection = null;
        PreparedStatement statement = null;
        ResultSet rows = null;
        try {
            connection = dataSource.getConnection();
            statement = connection.prepareStatement(selectRecordSql,
                    ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
            statement.setFetchSize(FETCH_SIZE);
            rows = statement.executeQuery();
            ResultSetMetaData metaData = rows.getMetaData();
            if (metaData == null || metaData.getColumnCount() < RECORD_IMAGE_COLUMN_INDEX) {
                LOG.error("Could not open the " + DD_NAME + " dataset for input: the backend describes "
                        + "no column at position " + RECORD_IMAGE_COLUMN_INDEX + ", so it is not "
                        + "presenting the dataset as a record-image relation. Reporting file status "
                        + FileStatus.toStatusImage(PERMANENT_ERROR_STATUS) + " to the caller");
                releaseQuietly(rows, statement, connection, "a refused open");
                return new DalytranFile(this, PERMANENT_ERROR_STATUS, null);
            }
            return new DalytranFile(this, FileStatus.OK, new Cursor(connection, statement, rows));
        } catch (SQLException refusal) {
            releaseQuietly(rows, statement, connection, "a refused open");
            logRefusal(refusal, "open the " + DD_NAME + " dataset for input (a read of the configured "
                    + "relation, which is what distinguishes an absent dataset from an empty one)");
            return new DalytranFile(this, PERMANENT_ERROR_STATUS, null);
        }
    }

    /**
     * Reads the next record in physical dataset order: the Java form of
     * {@code READ DALYTRAN-FILE INTO DALYTRAN-RECORD} and of the guard that classifies its status.
     *
     * <p>A row truncated inside {@code DALYTRAN-AMT} pads into a different amount - the sign overpunch is
     * the last byte of that field - and the job would post it successfully with nothing anywhere saying the
     * amount was not the amount the dataset held.
     *
     * @param file the handle {@link #open()} returned, which owns the position
     * @return the discriminated outcome; never {@code null}
     * @throws NullPointerException if {@code file} is {@code null}
     * @throws IllegalArgumentException if {@code file} was opened by a different repository instance
     * @throws IllegalStateException if {@code file} has been closed
     */
    public ReadResult readNext(DalytranFile file) {
        Objects.requireNonNull(file, "An open " + DD_NAME + " handle is required to read from it; a "
                + "COBOL READ addresses an open file and there is no such thing as reading nothing");
        requireOwnHandle(file, "read the next record");
        file.requireOpen("read the next record");

        Cursor cursor = file.cursor();
        if (cursor == null) {
            return ReadResult.other(file.openStatus());
        }
        if (file.atEndOfFile()) {
            return ReadResult.endOfFile();
        }

        byte[] recordImage;
        try {
            if (!cursor.next()) {
                file.markEndOfFile();
                return ReadResult.endOfFile();
            }
            recordImage = recordImageForm.readImage(cursor.rows(), RECORD_IMAGE_COLUMN_INDEX,
                    codec.charset());
        } catch (SQLException refusal) {
            return ReadResult.other(PERMANENT_ERROR_STATUS,
                    logRefusal(refusal, "read the next record of the " + DD_NAME + " dataset"));
        }

        if (recordImage == null) {
            LOG.error("The " + DD_NAME + " dataset presented a row with no record image at column "
                    + "position " + RECORD_IMAGE_COLUMN_INDEX + "; reporting file status "
                    + FileStatus.toStatusImage(PERMANENT_ERROR_STATUS) + " to the caller rather than "
                    + "an end of file");
            return ReadResult.other(PERMANENT_ERROR_STATUS);
        }
        if (recordImage.length != RECORD_LENGTH) {
            LOG.error("The " + DD_NAME + " dataset presented a row whose "
                    + DatasetObservation.recordWidth(recordImage.length).describe() + ", but "
                    + "DALYTRAN-RECORD is declared RECLN = " + RECORD_LENGTH + " by "
                    + "app/cpy/CVTRA06Y.cpy and confirmed as 16 + 334 by "
                    + "app/cbl/CBTRN02C.cbl:L66-L69; reporting file status "
                    + FileStatus.toStatusImage(PERMANENT_ERROR_STATUS) + " rather than padding the row "
                    + "into a transaction the dataset does not contain");
            return ReadResult.other(PERMANENT_ERROR_STATUS);
        }

        file.countRecord();
        return ReadResult.found(decode(recordImage));
    }

    /**
     * Closes the dataset and releases the cursor: the Java form of {@code CLOSE DALYTRAN-FILE}.
     *
     * @param file the handle {@link #open()} returned
     * @return {@link FileStatus#OK} or {@link #PERMANENT_ERROR_STATUS}; never {@code null}, always
     *     {@value FileStatus#STATUS_LENGTH} characters
     * @throws NullPointerException if {@code file} is {@code null}
     * @throws IllegalArgumentException if {@code file} was opened by a different repository instance
     */
    public String close(DalytranFile file) {
        Objects.requireNonNull(file, "An open " + DD_NAME + " handle is required to close it; a COBOL "
                + "CLOSE addresses an open file");
        requireOwnHandle(file, "close the file");
        return file.releaseCursor();
    }

    /**
     * Decodes stored record bytes into a {@link DalyTranRecord}, with no backend in the path.
     *
     * @param recordImage the stored record bytes, exactly {@link #RECORD_LENGTH} of them
     * @return the decoded record; never {@code null}
     * @throws NullPointerException if {@code recordImage} is {@code null}
     * @throws IllegalArgumentException if {@code recordImage} is not exactly {@link #RECORD_LENGTH} bytes
     */
    public DalyTranRecord decode(byte[] recordImage) {
        Objects.requireNonNull(recordImage, "Record bytes are required to decode a DALYTRAN-RECORD; an "
                + "absent record is an end-of-file outcome, not a decodable image");
        if (recordImage.length != RECORD_LENGTH) {
            throw new IllegalArgumentException("DALYTRAN-RECORD is declared RECLN = " + RECORD_LENGTH
                    + " by app/cpy/CVTRA06Y.cpy and this image is " + recordImage.length + " byte(s). "
                    + "It is not padded to width: app/cbl/CBTRN02C.cbl:L425-L436 maps every field of "
                    + "the record onto TRAN-RECORD and posts it, so a row truncated inside "
                    + "DALYTRAN-AMT would pad into a different amount and post successfully. Widen it "
                    + "deliberately at the call site if a short row is genuinely expected.");
        }
        return DalyTranRecord.decode(recordImage, codec.charset());
    }

    /**
     * Decodes a stored record image supplied as text, for the ASCII fixture and the parity cases.
     *
     * <p>A convenience over {@link #decode(byte[])} for {@code app/data/ASCII/dailytran.txt}, whose 300
     * rows are each exactly {@link #RECORD_LENGTH} characters.
     *
     * @param recordImage the record's text image, exactly {@link #RECORD_LENGTH} characters under this
     *     repository's charset
     * @return the decoded record; never {@code null}
     * @throws NullPointerException if {@code recordImage} is {@code null}
     * @throws IllegalArgumentException if {@code recordImage} does not encode to exactly
     *     {@link #RECORD_LENGTH} bytes
     */
    public DalyTranRecord decode(String recordImage) {
        Objects.requireNonNull(recordImage, "A record image is required to decode a DALYTRAN-RECORD; an "
                + "absent record is an end-of-file outcome, not a decodable image");
        return decode(codec.encodeImage(recordImage, "a " + DD_NAME + " record image"));
    }

    private void requireOwnHandle(DalytranFile file, String attempt) {
        if (file.repository() != this) {
            throw new IllegalArgumentException("Cannot " + attempt + ": the handle was opened by a "
                    + "different " + DalyTranRepository.class.getSimpleName() + " instance. A handle "
                    + "owns a live cursor over one configured dataset, read in one code page, so it is "
                    + "only meaningful to the repository that opened it. Use the handle's own "
                    + "readNext() and closeFile(), or the repository that returned it.");
        }
    }

    static RecordLayout requireDeclaredGeometry(RecordLayout candidate, int declaredSpanTotal) {
        Objects.requireNonNull(candidate, "A record layout is required: the record is addressed by "
                + "absolute offset, and there is no reading it without one");
        if (declaredSpanTotal != candidate.recordLength()
                || candidate.recordLength() != RECORD_LENGTH) {
            throw new IllegalStateException("The declared spans sum to " + declaredSpanTotal
                    + " over a layout of " + candidate.recordLength() + " byte(s), and both must be "
                    + RECORD_LENGTH + ". This is the gate G21 arithmetic: the fourteen spans of "
                    + "app/cpy/CVTRA06Y.cpy - FILLER X(20) included - account for every byte of "
                    + "DALYTRAN-RECORD, and they only sum to " + RECORD_LENGTH + " while FILLER is "
                    + "declared and DALYTRAN-AMT is 11 bytes. A read is refused rather than performed "
                    + "against a layout whose arithmetic no longer holds.");
        }
        return candidate;
    }

    private static String requireUsableDatasetName(String candidate) {
        if (candidate == null || candidate.isBlank()) {
            throw new IllegalStateException("The dataset binding for DD name '" + DD_NAME + "' declares "
                    + "no dataset name. Set carddemo.datasets." + DD_NAME + ".dsname; this repository "
                    + "composes its statement from configuration alone and hard-codes no dataset name.");
        }
        return DatasetRelation.requireDatasetName(candidate);
    }

    private static BackendDiagnostic logRefusal(Throwable refusal, String attempt) {
        BackendDiagnostic diagnostic = BackendDiagnostic.of(refusal);
        LOG.error("Could not " + attempt + " - " + diagnostic.describe() + "; reporting file status "
                + FileStatus.toStatusImage(PERMANENT_ERROR_STATUS) + " to the caller");
        return diagnostic;
    }

    private static void releaseQuietly(ResultSet rows, PreparedStatement statement,
            Connection connection, String context) {
        if (rows != null) {
            try {
                rows.close();
            } catch (SQLException ignored) {
                logRefusal(ignored, "release the " + DD_NAME + " result set after " + context);
            }
        }
        if (statement != null) {
            try {
                statement.close();
            } catch (SQLException ignored) {
                logRefusal(ignored, "release the " + DD_NAME + " statement after " + context);
            }
        }
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException ignored) {
                logRefusal(ignored, "release the " + DD_NAME + " connection after " + context);
            }
        }
    }

    /**
     * The live forward-only cursor behind one open: the JDBC form of an open sequential file's position.
     */
    private static final class Cursor {
        private final Connection connection;

        private final PreparedStatement statement;

        private final ResultSet rows;

        private Cursor(Connection connection, PreparedStatement statement, ResultSet rows) {
            this.connection = connection;
            this.statement = statement;
            this.rows = rows;
        }

        private boolean next() throws SQLException {
            return rows.next();
        }

        private ResultSet rows() {
            return rows;
        }

        private boolean release() {
            boolean clean = true;
            try {
                rows.close();
            } catch (SQLException refusal) {
                clean = false;
                logRefusal(refusal, "close the " + DD_NAME + " result set");
            }
            try {
                statement.close();
            } catch (SQLException refusal) {
                clean = false;
                logRefusal(refusal, "close the " + DD_NAME + " statement");
            }
            try {
                connection.close();
            } catch (SQLException refusal) {
                clean = false;
                logRefusal(refusal, "close the " + DD_NAME + " connection");
            }
            return clean;
        }
    }

    /**
     * One open of the dataset: the Java form of the file position an {@code OPEN INPUT} establishes and
     * each {@code READ} advances.
     *
     * <p>Not thread-safe, and deliberately so: a COBOL file position is not either.
     */
    public static final class DalytranFile implements AutoCloseable {
        private final DalyTranRepository repository;

        private final String openStatus;

        private Cursor cursor;

        private boolean endOfFile;

        private long recordsRead;

        private boolean closed;

        private DalytranFile(DalyTranRepository repository, String openStatus, Cursor cursor) {
            this.repository = repository;
            this.openStatus = openStatus;
            this.cursor = cursor;
            this.endOfFile = false;
            this.recordsRead = 0L;
            this.closed = false;
        }

        /**
         * The status the {@code OPEN} reported: the value {@code IF DALYTRAN-STATUS = '00'} tests at
         * {@code app/cbl/CBTRN02C.cbl:L239} and {@code app/cbl/CBTRN01C.cbl:L255} before moving {@code 0}
         * or {@code 12} into {@code APPL-RESULT}.
         *
         * @return {@link FileStatus#OK} or {@link DalyTranRepository#PERMANENT_ERROR_STATUS}; never
         *     {@code null}, always {@value FileStatus#STATUS_LENGTH} characters
         */
        public String openStatus() {
            return openStatus;
        }

        public Outcome openOutcome() {
            return FileStatus.outcomeOfStatus(openStatus);
        }

        /**
         * Whether the open succeeded: the {@code IF APPL-AOK} test that follows it.
         *
         * @return {@code true} when the dataset is open and readable
         */
        public boolean isOpen() {
            return !closed && cursor != null;
        }

        /**
         * Whether {@code AT END} has been reached: the value of {@code END-OF-FILE} /
         * {@code END-OF-DAILY-TRANS-FILE} that both mainline loops test.
         *
         * @return {@code true} once a read has reported the end of the file
         */
        public boolean atEndOfFile() {
            return endOfFile;
        }

        /**
         * How many records this open has read successfully.
         *
         * @return the number of {@code '00'} reads, never negative
         */
        public long recordsRead() {
            return recordsRead;
        }

        public boolean isClosed() {
            return closed;
        }

        /**
         * The configured dataset name this handle reads.
         *
         * @return the configured name; never {@code null}
         */
        public String datasetName() {
            return repository.datasetName();
        }

        /**
         * Reads the next record through the repository that opened this handle.
         *
         * @return the discriminated outcome; never {@code null}
         * @throws IllegalStateException if this handle has been closed
         */
        public ReadResult readNext() {
            return repository.readNext(this);
        }

        /**
         * Closes the file and reports the resulting status: {@link DalyTranRepository#close(DalytranFile)}
         * against the handle it is called on.
         *
         * @return {@link FileStatus#OK} or {@link DalyTranRepository#PERMANENT_ERROR_STATUS}
         */
        public String closeFile() {
            return releaseCursor();
        }

        /**
         * Closes the file, discarding the status: the {@link AutoCloseable} contract.
         */
        @Override
        public void close() {
            releaseCursor();
        }

        private String releaseCursor() {
            if (closed) {
                return FileStatus.OK;
            }
            closed = true;
            Cursor released = cursor;
            cursor = null;
            if (released == null) {
                return openStatus;
            }
            return released.release() ? FileStatus.OK : PERMANENT_ERROR_STATUS;
        }

        private DalyTranRepository repository() {
            return repository;
        }

        private Cursor cursor() {
            return cursor;
        }

        private void markEndOfFile() {
            endOfFile = true;
        }

        private void countRecord() {
            recordsRead++;
        }

        private void requireOpen(String attempt) {
            if (closed) {
                throw new IllegalStateException("Cannot " + attempt + ": the " + DD_NAME + " file has "
                        + "been closed. Both consumers close once, after their read loop "
                        + "(app/cbl/CBTRN02C.cbl:L221 and app/cbl/CBTRN01C.cbl:L188), so a read after a "
                        + "close corresponds to no COBOL path and is reported as the defect it is "
                        + "rather than as a file status. Open the file again to read it again.");
            }
        }
    }

    /**
     * The outcome of one {@code READ}: the three arms of {@code 1000-DALYTRAN-GET-NEXT}'s guard, and
     * nothing else.
     *
     * <p>A discriminated result rather than a nullable record plus an out-parameter, because the COBOL
     * branches on the status and the presence of a record follows from it: exactly the {@code '00'} arm
     * carries a record, and the invariants below make any other combination unconstructible.
     *
     * @param status the two-character file status the read reported
     * @param outcome that status classified
     * @param dalyTran the decoded record, present exactly when {@code outcome} is {@link Outcome#OK}
     * @param diagnostic what the backend reported, present only when a backend refused
     */
    public record ReadResult(String status,
                             Outcome outcome,
                             Optional<DalyTranRecord> dalyTran,
                             Optional<BackendDiagnostic> diagnostic) {
        public ReadResult {
            Objects.requireNonNull(status, "A read result carries the two-character file status the read "
                    + "reported; it is never absent");
            Objects.requireNonNull(outcome, "A read result carries its classification; it is never "
                    + "absent");
            Objects.requireNonNull(dalyTran, "A read result carries an empty record rather than a null "
                    + "one, so no null escapes the type");
            Objects.requireNonNull(diagnostic, "A read result carries an empty diagnostic rather than a "
                    + "null one, so no null escapes the type");
            if (status.length() != FileStatus.STATUS_LENGTH) {
                throw new IllegalArgumentException("A file status is exactly "
                        + FileStatus.STATUS_LENGTH + " characters, as DALYTRAN-STATUS is declared at "
                        + "app/cbl/CBTRN02C.cbl:L103-L105 and app/cbl/CBTRN01C.cbl:L100-L102; got "
                        + status.length());
            }
            if (outcome != Outcome.OK && outcome != Outcome.END_OF_FILE && outcome != Outcome.OTHER) {
                throw new IllegalArgumentException("The guard at app/cbl/CBTRN02C.cbl:L347-L356 has "
                        + "three arms - '00', '10' and everything else - so a " + DD_NAME + " read is "
                        + "classified as OK, END_OF_FILE or OTHER. A keyed status cannot arise on a "
                        + "sequential read of a physical-sequential dataset and would fall into the "
                        + "third arm; got " + outcome + ".");
            }
            if (dalyTran.isPresent() != (outcome == Outcome.OK)) {
                throw new IllegalArgumentException(dalyTran.isPresent()
                        ? "A read that did not succeed carries no record: outcome " + outcome
                                + " was given one. Only the '00' arm reaches DALYTRAN-RECORD, because "
                                + "READ ... INTO moves nothing at AT END."
                        : "A successful read carries the decoded record, and this one carries none; "
                                + "build it with ReadResult.found(DalyTranRecord).");
            }
            String expectedForOutcome = outcome.batchStatus().orElse(null);
            if (expectedForOutcome != null && !expectedForOutcome.equals(status)) {
                throw new IllegalArgumentException("Outcome " + outcome + " corresponds to status '"
                        + expectedForOutcome + "', but status '" + status + "' was given; the status "
                        + "and its classification must agree.");
            }
            if (expectedForOutcome == null
                    && (FileStatus.isOk(status) || FileStatus.isEndOfFile(status))) {
                throw new IllegalArgumentException("Status '" + status + "' is one of the two statuses "
                        + "the guard names explicitly, so it cannot be classified as the third arm.");
            }
        }

        /**
         * The successful arm: {@code IF DALYTRAN-STATUS = '00' MOVE 0 TO APPL-RESULT}
         * ({@code app/cbl/CBTRN02C.cbl:L347-L348}, {@code app/cbl/CBTRN01C.cbl:L204-L205}).
         *
         * @param dalyTran the decoded record
         * @return a result carrying {@link FileStatus#OK} and the record
         * @throws NullPointerException if {@code dalyTran} is {@code null}
         */
        public static ReadResult found(DalyTranRecord dalyTran) {
            Objects.requireNonNull(dalyTran, "A successful read carries the decoded DALYTRAN-RECORD");
            return new ReadResult(FileStatus.OK, Outcome.OK, Optional.of(dalyTran), Optional.empty());
        }

        /**
         * The end-of-file arm: {@code IF DALYTRAN-STATUS = '10' MOVE 16 TO APPL-RESULT}
         * ({@code app/cbl/CBTRN02C.cbl:L351-L352}, {@code app/cbl/CBTRN01C.cbl:L207-L209}), which the
         * caller turns into {@code MOVE 'Y' TO END-OF-FILE} ({@code CBTRN02C:L361}) or
         * {@code MOVE 'Y' TO END-OF-DAILY-TRANS-FILE} ({@code CBTRN01C:L218}).
         *
         * @return a result carrying {@link FileStatus#END_OF_FILE} and no record
         */
        public static ReadResult endOfFile() {
            return new ReadResult(FileStatus.END_OF_FILE, Outcome.END_OF_FILE, Optional.empty(),
                    Optional.empty());
        }

        /**
         * The third arm: {@code MOVE 12 TO APPL-RESULT} ({@code app/cbl/CBTRN02C.cbl:L354}), which the
         * caller turns into an error line, a rendered status and an abend ({@code CBTRN02C:L363-L366},
         * {@code CBTRN01C:L221-L224}).
         *
         * @param status the two-character status the read reported, carried verbatim so the caller renders
         *     it exactly as {@code 9910-DISPLAY-IO-STATUS} does
         * @return a result carrying {@code status} and no record
         * @throws NullPointerException if {@code status} is {@code null}
         * @throws IllegalArgumentException if {@code status} is not exactly
         *     {@value FileStatus#STATUS_LENGTH} characters, or is one of the two statuses the guard names
         *     explicitly
         */
        public static ReadResult other(String status) {
            return new ReadResult(status, Outcome.OTHER, Optional.empty(), Optional.empty());
        }

        /**
         * The third arm, carrying what the backend actually said about the refusal.
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
         * Whether the read succeeded and a record is available: the {@code IF APPL-AOK} test.
         *
         * @return {@code true} on the {@code '00'} arm
         */
        public boolean isFound() {
            return outcome == Outcome.OK;
        }

        /**
         * Whether the file has ended: the {@code IF APPL-EOF} test ({@code app/cbl/CBTRN02C.cbl:L360},
         * {@code app/cbl/CBTRN01C.cbl:L217}).
         *
         * @return {@code true} on the {@code '10'} arm
         */
        public boolean isEndOfFile() {
            return outcome == Outcome.END_OF_FILE;
        }

        /**
         * Whether the read failed: the {@code ELSE} of the {@code APPL-EOF} test, which displays, renders
         * the status and abends.
         *
         * @return {@code true} on the third arm
         */
        public boolean isOther() {
            return outcome == Outcome.OTHER;
        }

        /**
         * The {@code APPL-RESULT} value the guard moves for this outcome.
         *
         * @return {@link #APPL_RESULT_OK}, {@link #APPL_RESULT_EOF} or {@link #APPL_RESULT_FATAL}
         */
        public int applResult() {
            return switch (outcome) {
                case OK -> APPL_RESULT_OK;
                case END_OF_FILE -> APPL_RESULT_EOF;
                default -> APPL_RESULT_FATAL;
            };
        }

        /**
         * The status rendered exactly as {@code 9910-DISPLAY-IO-STATUS} / {@code Z-DISPLAY-IO-STATUS}
         * writes it.
         *
         * @return the display line, for example {@code FILE STATUS IS: NNNN0010}
         */
        public String displayLine() {
            return FileStatus.toDisplayLine(status);
        }
    }
}
