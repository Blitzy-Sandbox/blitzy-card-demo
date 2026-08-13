package com.vsergeychik.carddemo.transaction;

import com.vsergeychik.carddemo.common.DatasetObservation;
import com.vsergeychik.carddemo.common.DatasetRelation;
import com.vsergeychik.carddemo.common.DatasetRelation.BackendDiagnostic;
import com.vsergeychik.carddemo.common.FileStatus;
import com.vsergeychik.carddemo.common.FileStatus.Outcome;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.PhysicalSequence;
import com.vsergeychik.carddemo.common.RecordImageForm;
import com.vsergeychik.carddemo.common.FixedWidthRecord;
import com.vsergeychik.carddemo.common.FixedWidthRecord.FieldSpan;
import com.vsergeychik.carddemo.common.FixedWidthRecord.RecordLayout;
import com.vsergeychik.carddemo.config.CobolCharsetConfig;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBinding;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBindings;

import java.nio.charset.Charset;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
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
import org.springframework.stereotype.Component;

/**
 * Reads the transaction report's date range out of the {@code DATEPARM} dataset: the Java form of the three
 * {@code DATEPARM} paragraphs of {@code app/cbl/CBTRN03C.cbl}.
 *
 * <p>The physical record is 80 bytes: The receiving group is only 21: so
 * {@code READ DATE-PARMS-FILE INTO WS-DATEPARM-RECORD} at {@code L221} performs an implicit alphanumeric
 * {@code MOVE} of 80 bytes into a 21-byte group, which COBOL right-truncates.
 */
@Component
public class DateParmReader {
    private static final Log LOG = LogFactory.getLog(DateParmReader.class);

    /**
     * The DD name under which the report date range is catalogued: {@code DATEPARM}.
     */
    public static final String DD_NAME = "DATEPARM";

    /**
     * The physical record width in bytes: {@code 80}, from
     * {@code app/cbl/CBTRN03C.cbl:L87-L88 01 FD-DATEPARM-REC PIC X(80)}.
     */
    public static final int RECORD_LENGTH = 80;

    /**
     * The width of the receiving group in bytes: {@code 21}, from {@code app/cbl/CBTRN03C.cbl:L122-L125}.
     */
    public static final int RECEIVER_LENGTH = 21;

    /**
     * Absolute 0-based offset of {@code WS-START-DATE}: {@code 0} - bytes 1 to 10.
     */
    public static final int START_DATE_OFFSET = 0;

    /**
     * Declared width of {@code WS-START-DATE PIC X(10)}: {@code 10}.
     */
    public static final int START_DATE_LENGTH = 10;

    /**
     * Absolute 0-based offset of the {@code FILLER PIC X(01)} separator: {@code 10} - byte 11.
     */
    public static final int SEPARATOR_OFFSET = 10;

    /**
     * Declared width of the {@code FILLER PIC X(01)} separator: {@code 1}.
     */
    public static final int SEPARATOR_LENGTH = 1;

    /**
     * Absolute 0-based offset of {@code WS-END-DATE}: {@code 11} - bytes 12 to 21.
     */
    public static final int END_DATE_OFFSET = 11;

    /**
     * Declared width of {@code WS-END-DATE PIC X(10)}: {@code 10}.
     */
    public static final int END_DATE_LENGTH = 10;

    public static final int DISCARDED_TAIL_OFFSET = 21;

    /**
     * Width of the discarded record tail: {@code 59} bytes.
     */
    public static final int DISCARDED_TAIL_LENGTH = 59;

    /**
     * The copybook name of the start-date item, verbatim: {@code WS-START-DATE}
     * ({@code app/cbl/CBTRN03C.cbl:L123}).
     */
    public static final String START_DATE_FIELD = "WS-START-DATE";

    /**
     * The copybook name of the end-date item, verbatim: {@code WS-END-DATE}
     * ({@code app/cbl/CBTRN03C.cbl:L125}).
     */
    public static final String END_DATE_FIELD = "WS-END-DATE";

    public static final int RECORD_IMAGE_COLUMN_INDEX = DatasetRelation.RECORD_IMAGE_COLUMN_INDEX;

    /**
     * The {@code APPL-RESULT} value the COBOL moves for the {@code WHEN OTHER} arm: {@code 12}
     * ({@code app/cbl/CBTRN03C.cbl:L228}).
     */
    public static final int APPL_RESULT_FATAL = 12;

    private static final char PERMANENT_ERROR_FEEDBACK_CODE = 0;

    /**
     * The two-character file status this reader reports for a permanent error: {@code '9'} followed by
     * {@link #PERMANENT_ERROR_FEEDBACK_CODE}.
     */
    public static final String PERMANENT_ERROR_STATUS = "9" + PERMANENT_ERROR_FEEDBACK_CODE;

    private static final String NEVER_TRUE_PREDICATE = "1 = 0";

    private static final int SINGLE_ROW = 1;

    private final JdbcTemplate jdbcTemplate;

    private final FixedWidthCodec codec;

    private final RecordImageForm recordImageForm;

    private final PhysicalSequence physicalSequence;

    private final DatasetRelation relation;

    private final RecordLayout layout;

    private final FieldSpan startDateSpan;

    private final FieldSpan separatorSpan;

    private final FieldSpan endDateSpan;

    /**
     * Resolves the {@code DATEPARM} binding, proves the record geometry, and captures the collaborators
     * this reader needs - all of it before the context finishes starting, so nothing that can be checked
     * early is left to fail mid-job.
     *
     * @param jdbcTemplate the module's shared template, from the data-access configuration
     * @param datasetBindings the DD-name-keyed dataset catalogue bound from {@code carddemo.datasets}
     * @param datasetCharset the active dataset code page, selected by bean name so the choice is explicit
     *     at the injection point
     * @param recordImageForm how the deployment's driver presents a record image over JDBC, from
     *     {@value RecordImageForm#FORM_PROPERTY}
     * @param physicalSequence the physical-record ordinal this dataset's read is ordered by, from
     *     {@value PhysicalSequence#EXPRESSION_PROPERTY}
     * @throws NullPointerException if any argument is {@code null}
     * @throws IllegalStateException if no binding is configured for {@link #DD_NAME}, if that binding
     *     declares a record length other than {@link #RECORD_LENGTH}, or if its dataset name is unusable
     */
    public DateParmReader(
            JdbcTemplate jdbcTemplate,
            DatasetBindings datasetBindings,
            @Qualifier(CobolCharsetConfig.DATASET_CHARSET_BEAN_NAME) Charset datasetCharset,
            RecordImageForm recordImageForm,
            PhysicalSequence physicalSequence) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "A JdbcTemplate is required: the "
                + "report date range is read from the " + DD_NAME + " dataset through the module's "
                + "shared template");
        Objects.requireNonNull(datasetBindings, "The carddemo.datasets binding catalogue is required: "
                + "dataset names live in configuration and are never written in Java");
        this.codec = new FixedWidthCodec(Objects.requireNonNull(datasetCharset, "A dataset charset is "
                + "required: a fixed-width mainframe record is bytes in a specific code page, so the "
                + "code page is stated explicitly and never taken from the platform"));
        this.recordImageForm = Objects.requireNonNull(recordImageForm, "A record-image representation is "
                + "required: whether this deployment's driver presents a record image as characters or as "
                + "bytes is stated once, by " + RecordImageForm.FORM_PROPERTY + ", and never decided per "
                + "reader");
        this.physicalSequence = Objects.requireNonNull(physicalSequence, "A physical-record ordinal is "
                + "required: " + DD_NAME + " is a physical-sequential dataset and 0550-DATEPARM-READ "
                + "takes its FIRST record, so which record that is depends on the order the dataset is "
                + "read in - and SQL returns rows in no order unless a statement says which. It is "
                + "stated once, by " + PhysicalSequence.EXPRESSION_PROPERTY + ", and never decided per "
                + "reader");
        RecordImageForm.requireSingleByteCodePage(datasetCharset);

        DatasetBinding binding = datasetBindings.binding(DD_NAME);
        if (binding.recordLength() != RECORD_LENGTH) {
            throw new IllegalStateException("Dataset binding for DD name '" + DD_NAME + "' declares a "
                    + "record length of " + binding.recordLength() + ", but this reader decodes by "
                    + "absolute offset against a " + RECORD_LENGTH + "-byte record - "
                    + "app/cbl/CBTRN03C.cbl:L87-L88 declares 01 FD-DATEPARM-REC PIC X(" + RECORD_LENGTH
                    + "). Reading a differently-sized record would misplace both dates, so the "
                    + "disagreement is rejected here. Correct carddemo.datasets." + DD_NAME
                    + ".record-length to " + RECORD_LENGTH + ".");
        }
        this.relation = DatasetRelation.of(requireUsableDatasetName(binding.dsname()), RECORD_LENGTH);

        this.startDateSpan =
                FieldSpan.alphanumeric(START_DATE_FIELD, START_DATE_OFFSET, START_DATE_LENGTH);
        this.separatorSpan = FieldSpan.filler(SEPARATOR_OFFSET, SEPARATOR_LENGTH);
        this.endDateSpan = FieldSpan.alphanumeric(END_DATE_FIELD, END_DATE_OFFSET, END_DATE_LENGTH);
        this.layout = RecordLayout.of(RECORD_LENGTH,
                this.startDateSpan,
                this.separatorSpan,
                this.endDateSpan,
                FieldSpan.filler(DISCARDED_TAIL_OFFSET, DISCARDED_TAIL_LENGTH));
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
     * Opens the {@code DATEPARM} dataset for input, reporting only the resulting file status: the Java form
     * of {@code 0500-DATEPARM-OPEN} ({@code app/cbl/CBTRN03C.cbl:L466-L482}).
     *
     * <p>The COBOL is a two-way test - {@code IF DATEPARM-STATUS = '00'} moves {@code 0} into
     * {@code APPL-RESULT}, anything else moves {@code 12} - and this method reproduces exactly that shape:
     * {@link FileStatus#OK} or a non-{@code '00'} status, and nothing more.
     *
     * @return {@link FileStatus#OK} when the dataset is addressable, otherwise
     *     {@link #PERMANENT_ERROR_STATUS}; never {@code null}, always two characters
     */
    public String open() {
        return probeDatasetAvailability("OPEN INPUT");
    }

    /**
     * Closes the {@code DATEPARM} dataset, reporting only the resulting file status: the Java form of
     * {@code 9500-DATEPARM-CLOSE} ({@code app/cbl/CBTRN03C.cbl:L605-L621}).
     *
     * @return {@link FileStatus#OK} when the dataset is still describable, otherwise
     *     {@link #PERMANENT_ERROR_STATUS}; never {@code null}, always two characters
     */
    public String close() {
        return probeDatasetAvailability("CLOSE");
    }

    private String probeDatasetAvailability(String cobolOperation) {
        // The predicate is false on every row, so nothing is transferred - but the statement names the
        // dataset, so an absent or unreachable dataset fails here, which is exactly what a COBOL OPEN
        // reports and what a connection-only check cannot see.
        ResultSetExtractor<String> describe = resultSet -> {
            ResultSetMetaData metaData = resultSet.getMetaData();
            return metaData == null || metaData.getColumnCount() < RECORD_IMAGE_COLUMN_INDEX
                    ? PERMANENT_ERROR_STATUS
                    : FileStatus.OK;
        };
        try {
            String status = jdbcTemplate.query(relation.describeStatement(), describe);
            return status == null ? PERMANENT_ERROR_STATUS : status;
        } catch (DataAccessException translated) {
            logRefusal(translated, cobolOperation + " the " + DD_NAME + " dataset (a describe of the "
                    + "configured relation, which is what distinguishes an absent dataset from an "
                    + "unreadable record)");
            return PERMANENT_ERROR_STATUS;
        }
    }

    private static BackendDiagnostic logRefusal(Throwable refusal, String attempt) {
        BackendDiagnostic diagnostic = BackendDiagnostic.of(refusal);
        LOG.error("Could not " + attempt + " - " + diagnostic.describe() + "; reporting file status "
                + FileStatus.toStatusImage(PERMANENT_ERROR_STATUS) + " to the caller");
        return diagnostic;
    }

    // 0550-DATEPARM-READ - app/cbl/CBTRN03C.cbl:L220-L243, performed exactly once, from L168.

    /**
     * Reads the date range: the Java form of {@code 0550-DATEPARM-READ}'s {@code READ} and the
     * {@code EVALUATE} that classifies its status ({@code app/cbl/CBTRN03C.cbl:L221-L229}).
     *
     * <p>Three outcomes, and exactly the three the COBOL enumerates: found - status {@code '00'}, carrying
     * the decoded {@link DateParm}.
     *
     * @return the discriminated outcome; never {@code null}
     */
    public ReadResult read() {
        List<byte[]> rows;
        try {
            rows = jdbcTemplate.query(firstRowOnly(selectRecordSql()), this::mapRecordImage);
        } catch (DataAccessException translated) {
            return ReadResult.other(PERMANENT_ERROR_STATUS,
                    logRefusal(translated, "read the " + DD_NAME + " dataset"));
        }
        if (rows == null || rows.isEmpty()) {
            return ReadResult.endOfFile();
        }
        byte[] recordImage = rows.get(0);
        if (recordImage == null) {
            LOG.error("The " + DD_NAME + " dataset presented a row with no record image at column "
                    + "position " + RECORD_IMAGE_COLUMN_INDEX + "; reporting file status "
                    + FileStatus.toStatusImage(PERMANENT_ERROR_STATUS) + " to the caller");
            return ReadResult.other(PERMANENT_ERROR_STATUS);
        }
        if (recordImage.length != RECORD_LENGTH) {
            LOG.error("The " + DD_NAME + " dataset presented a row whose "
                    + DatasetObservation.recordWidth(recordImage.length).describe() + ", but "
                    + "FD-DATEPARM-REC is declared PIC X(" + RECORD_LENGTH + ") by "
                    + "app/cbl/CBTRN03C.cbl:L87-L88; reporting file status "
                    + FileStatus.toStatusImage(PERMANENT_ERROR_STATUS) + " rather than padding the row "
                    + "into a date range the dataset does not contain");
            return ReadResult.other(PERMANENT_ERROR_STATUS);
        }
        return ReadResult.found(decode(recordImage));
    }

    private byte[] mapRecordImage(ResultSet resultSet, int rowNumber) throws SQLException {
        return recordImageForm.readImage(resultSet, RECORD_IMAGE_COLUMN_INDEX, codec.charset());
    }

    private static PreparedStatementCreator firstRowOnly(String statement) {
        return connection -> {
            PreparedStatement prepared = connection.prepareStatement(statement);
            prepared.setMaxRows(SINGLE_ROW);
            prepared.setFetchSize(SINGLE_ROW);
            return prepared;
        };
    }

    /**
     * Decodes a stored record image into the date range, reproducing the 80-into-21 truncation of
     * {@code READ DATE-PARMS-FILE INTO WS-DATEPARM-RECORD}.
     *
     * @param recordImage the stored record image, exactly {@link #RECORD_LENGTH} characters
     * @return the decoded range; both dates exactly 10 characters, untrimmed
     * @throws NullPointerException if {@code recordImage} is {@code null}
     * @throws IllegalArgumentException if {@code recordImage} does not encode to exactly
     *     {@link #RECORD_LENGTH} bytes
     */
    public DateParm decode(String recordImage) {
        Objects.requireNonNull(recordImage, "A record image is required to decode the " + DD_NAME
                + " record; an absent record is an end-of-file outcome, not a decodable image");
        return decode(codec.encodeImage(recordImage, "a " + DD_NAME + " record image"));
    }

    /**
     * Decodes stored record bytes into the date range, reproducing the 80-into-21 truncation of
     * {@code READ DATE-PARMS-FILE INTO WS-DATEPARM-RECORD}.
     *
     * <p>Three steps, in this order, and each one is the faithful counterpart of something the COBOL does:
     * require exactly {@link #RECORD_LENGTH} bytes, in either direction.
     *
     * @param recordBytes the stored record bytes, exactly {@link #RECORD_LENGTH} of them
     * @return the decoded range; both dates exactly 10 characters, untrimmed
     * @throws NullPointerException if {@code recordBytes} is {@code null}
     * @throws IllegalArgumentException if {@code recordBytes} is not exactly {@link #RECORD_LENGTH} bytes
     */
    public DateParm decode(byte[] recordBytes) {
        Objects.requireNonNull(recordBytes, "Record bytes are required to decode the " + DD_NAME
                + " record; an absent record is an end-of-file outcome, not a decodable image");
        if (recordBytes.length != RECORD_LENGTH) {
            throw new IllegalArgumentException("The " + DD_NAME + " record is declared PIC X("
                    + RECORD_LENGTH + ") at app/cbl/CBTRN03C.cbl:L87-L88 and this image is "
                    + recordBytes.length + " byte(s). It is not padded to width: the next step is the "
                    + "80-into-21 move of READ ... INTO WS-DATEPARM-RECORD, so a row truncated inside "
                    + "WS-END-DATE would pad into a different reporting range and decode without "
                    + "complaint. " + DatasetObservation.recordWidth(recordBytes.length).describe()
                    + ".");
        }
        FixedWidthRecord record = codec.wrap(recordBytes, layout);
        return new DateParm(
                codec.readPicX(record, startDateSpan),
                codec.readPicX(record, separatorSpan),
                codec.readPicX(record, endDateSpan));
    }

    String selectRecordSql() {
        return relation.selectAllInPhysicalSequence(physicalSequence);
    }

    String describeStatement() {
        return relation.describeStatement();
    }

    RecordLayout layout() {
        return layout;
    }

    private static String requireUsableDatasetName(String candidate) {
        if (candidate == null || candidate.isBlank()) {
            throw new IllegalStateException("The dataset binding for DD name '" + DD_NAME
                    + "' declares no dataset name. Set carddemo.datasets." + DD_NAME
                    + ".dsname; this reader composes its statement from configuration alone and "
                    + "hard-codes no dataset name.");
        }
        return DatasetRelation.requireDatasetName(candidate);
    }

    private static String requireExactLength(String value, int expectedLength, String itemName) {
        Objects.requireNonNull(value, itemName + " is required; a fixed-width span is never absent, "
                + "only spaces");
        if (value.length() != expectedLength) {
            throw new IllegalArgumentException(itemName + " is declared PIC X(" + expectedLength
                    + ") but was given " + value.length() + " character(s): '" + value + "'. A "
                    + "fixed-width span carries its padding, so it is never trimmed and never short.");
        }
        return value;
    }

    /**
     * The report date range, exactly as {@code WS-DATEPARM-RECORD} holds it
     * ({@code app/cbl/CBTRN03C.cbl:L122-L125}).
     *
     * @param startDate the {@code WS-START-DATE PIC X(10)} span, verbatim and untrimmed - exactly 10
     *     characters
     * @param separator the {@code FILLER PIC X(01)} span between the dates, byte 11 of the record
     * @param endDate the {@code WS-END-DATE PIC X(10)} span, verbatim and untrimmed - exactly 10 characters
     */
    public record DateParm(String startDate, String separator, String endDate) {
        public DateParm {
            requireExactLength(startDate, START_DATE_LENGTH, START_DATE_FIELD);
            requireExactLength(separator, SEPARATOR_LENGTH, "The FILLER PIC X(01) separator");
            requireExactLength(endDate, END_DATE_LENGTH, END_DATE_FIELD);
        }

        /**
         * The 21-byte image of {@code WS-DATEPARM-RECORD}: the two dates with the separator between them,
         * every byte at its declared width.
         *
         * @return exactly {@link DateParmReader#RECEIVER_LENGTH} characters
         */
        public String receiverImage() {
            return startDate + separator + endDate;
        }
    }

    /**
     * The discriminated outcome of one {@code READ} of the {@code DATEPARM} dataset: the classification
     * {@code 0550-DATEPARM-READ}'s {@code EVALUATE} performs, and nothing beyond it
     * ({@code app/cbl/CBTRN03C.cbl:L221-L229}).
     *
     * @param status the two-character {@code DATEPARM-STATUS} the read reported, verbatim
     * @param outcome its classification: {@link Outcome#OK}, {@link Outcome#END_OF_FILE} or
     *     {@link Outcome#OTHER}
     * @param dateParm the decoded range, present exactly when {@code outcome} is {@link Outcome#OK}
     * @param diagnostic what the backend reported when it refused, present only on a {@link Outcome#OTHER}
     *     arm the driver described
     */
    public record ReadResult(String status,
                             Outcome outcome,
                             Optional<DateParm> dateParm,
                             Optional<BackendDiagnostic> diagnostic) {
        public ReadResult {
            Objects.requireNonNull(status, "A read result carries the two-character file status the "
                    + "read reported; it is never absent");
            Objects.requireNonNull(outcome, "A read result carries its classification; it is never "
                    + "absent");
            Objects.requireNonNull(dateParm, "A read result carries an empty range rather than a null "
                    + "one, so no null escapes the type");
            Objects.requireNonNull(diagnostic, "A read result carries an empty diagnostic rather than a "
                    + "null one, so no null escapes the type");
            if (status.length() != FileStatus.STATUS_LENGTH) {
                throw new IllegalArgumentException("A file status is exactly "
                        + FileStatus.STATUS_LENGTH + " characters, as DATEPARM-STATUS is declared at "
                        + "app/cbl/CBTRN03C.cbl:L118-L120; got " + status.length());
            }
            if (outcome != Outcome.OK && outcome != Outcome.END_OF_FILE && outcome != Outcome.OTHER) {
                throw new IllegalArgumentException("The EVALUATE at app/cbl/CBTRN03C.cbl:L222-L229 has "
                        + "three arms - '00', '10' and WHEN OTHER - so a DATEPARM read is classified as "
                        + "OK, END_OF_FILE or OTHER. A keyed status cannot arise on a sequential read "
                        + "and would fall into WHEN OTHER; got " + outcome + ".");
            }
            if (dateParm.isPresent() != (outcome == Outcome.OK)) {
                throw new IllegalArgumentException(dateParm.isPresent()
                        ? "A read that did not succeed carries no date range: outcome " + outcome
                                + " was given one. Only the '00' arm reaches WS-DATEPARM-RECORD."
                        : "A successful read carries the decoded date range, and this one carries "
                                + "none.");
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
                        + "the EVALUATE names explicitly, so it cannot be classified as WHEN OTHER.");
            }
        }

        /**
         * The successful arm: {@code WHEN '00' MOVE 0 TO APPL-RESULT}
         * ({@code app/cbl/CBTRN03C.cbl:L223-L224}).
         *
         * @param dateParm the decoded range
         * @return a result carrying status {@link FileStatus#OK} and the range
         * @throws NullPointerException if {@code dateParm} is {@code null}
         */
        public static ReadResult found(DateParm dateParm) {
            Objects.requireNonNull(dateParm, "A successful read carries the decoded date range");
            return new ReadResult(FileStatus.OK, Outcome.OK, Optional.of(dateParm), Optional.empty());
        }

        /**
         * The end-of-file arm: {@code WHEN '10' MOVE 16 TO APPL-RESULT}
         * ({@code app/cbl/CBTRN03C.cbl:L225-L226}), which the caller turns into
         * {@code MOVE 'Y' TO END-OF-FILE} at {@code L236}.
         *
         * @return a result carrying status {@link FileStatus#END_OF_FILE} and no range
         */
        public static ReadResult endOfFile() {
            return new ReadResult(FileStatus.END_OF_FILE, Outcome.END_OF_FILE, Optional.empty(),
                    Optional.empty());
        }

        /**
         * The {@code WHEN OTHER} arm: {@code MOVE 12 TO APPL-RESULT}
         * ({@code app/cbl/CBTRN03C.cbl:L227-L228}), which the caller turns into an error line, a rendered
         * status and an abend at {@code L238-L241}.
         *
         * @param status the two-character status the read reported, carried verbatim so the caller can
         *     render it exactly as {@code 9910-DISPLAY-IO-STATUS} does
         * @return a result carrying {@code status} and no range
         * @throws NullPointerException if {@code status} is {@code null}
         * @throws IllegalArgumentException if {@code status} is not exactly two characters, or is one of
         *     the two statuses the {@code EVALUATE} names explicitly
         */
        public static ReadResult other(String status) {
            return new ReadResult(status, Outcome.OTHER, Optional.empty(), Optional.empty());
        }

        /**
         * The {@code WHEN OTHER} arm, carrying what the backend actually said about the refusal.
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
         * Whether the read succeeded and a range is available: the {@code IF APPL-AOK} test at
         * {@code app/cbl/CBTRN03C.cbl:L231}.
         *
         * @return {@code true} for the {@code '00'} arm
         */
        public boolean isFound() {
            return outcome == Outcome.OK;
        }

        /**
         * Whether the dataset was empty: the {@code IF APPL-EOF} test at {@code app/cbl/CBTRN03C.cbl:L235}.
         *
         * @return {@code true} for the {@code '10'} arm
         */
        public boolean isEndOfFile() {
            return outcome == Outcome.END_OF_FILE;
        }

        /**
         * Whether the read failed: the {@code ELSE} of the {@code APPL-EOF} test, which displays, renders
         * the status and abends ({@code app/cbl/CBTRN03C.cbl:L237-L242}).
         *
         * @return {@code true} for the {@code WHEN OTHER} arm
         */
        public boolean isOther() {
            return outcome == Outcome.OTHER;
        }

        /**
         * The {@code APPL-RESULT} value the {@code EVALUATE} moves for this outcome: {@code 0}, {@code 16}
         * or {@code 12} ({@code app/cbl/CBTRN03C.cbl:L222-L229}).
         *
         * @return {@link FileStatus#APPL_AOK}, {@link FileStatus#APPL_EOF} or
         *     {@link DateParmReader#APPL_RESULT_FATAL}
         */
        public int applResult() {
            return switch (outcome) {
                case OK -> FileStatus.APPL_AOK;
                case END_OF_FILE -> FileStatus.APPL_EOF;
                default -> APPL_RESULT_FATAL;
            };
        }
    }
}
