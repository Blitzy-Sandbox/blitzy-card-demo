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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementSetter;
import org.springframework.stereotype.Component;

import java.nio.charset.Charset;
import java.util.Objects;

/**
 * Writes the {@code TRANREPT} transaction detail report: one fixed {@value #RECORD_LENGTH}-byte record per
 * report line, in call order, exactly as {@code app/cbl/CBTRN03C.cbl} does.
 *
 * <p>A handle is not thread-safe, exactly as a COBOL record area is not, and belongs to the step or test
 * that opened it.
 */
@Component
public final class TranReportWriter {
    /**
     * The DD name this writer resolves from {@code carddemo.datasets}, verbatim from
     * {@code SELECT REPORT-FILE ASSIGN TO TRANREPT} at {@code app/cbl/CBTRN03C.cbl:L51}.
     */
    public static final String DD_NAME = "TRANREPT";

    public static final int RECORD_LENGTH = 133;

    /**
     * The record format the JCL declares: {@code FB}, fixed blocked.
     */
    public static final String RECORD_FORMAT = "FB";

    /**
     * The block size the JCL declares: {@code BLKSIZE=0}, which asks the system to determine it.
     */
    public static final int BLOCK_SIZE = 0;

    /**
     * The name of the FD record area, verbatim from {@code app/cbl/CBTRN03C.cbl:L85}.
     */
    public static final String FD_REPTFILE_REC = "FD-REPTFILE-REC";

    // Widths come from TranReportLayouts, which transcribed them from app/cpy/CVTRA07Y.cpy; the pads are
    // derived here rather than restated, so there is exactly one place a width can be wrong.

    /**
     * Spaces added to {@code REPORT-NAME-HEADER}: {@code 133 - 115 = 18}.
     */
    public static final int REPORT_NAME_HEADER_PAD =
            RECORD_LENGTH - TranReportLayouts.REPORT_NAME_HEADER_LENGTH;

    /**
     * Spaces added to {@code TRANSACTION-DETAIL-REPORT}: {@code 133 - 114 = 19}.
     */
    public static final int TRANSACTION_DETAIL_REPORT_PAD =
            RECORD_LENGTH - TranReportLayouts.TRANSACTION_DETAIL_REPORT_LENGTH;

    /**
     * Spaces added to {@code TRANSACTION-HEADER-1}: {@code 133 - 114 = 19}.
     */
    public static final int TRANSACTION_HEADER_1_PAD =
            RECORD_LENGTH - TranReportLayouts.TRANSACTION_HEADER_1_LENGTH;

    /**
     * Spaces added to {@code TRANSACTION-HEADER-2}: {@code 133 - 133 = 0}.
     */
    public static final int TRANSACTION_HEADER_2_PAD =
            RECORD_LENGTH - TranReportLayouts.TRANSACTION_HEADER_2_LENGTH;

    /**
     * Spaces added to {@code REPORT-PAGE-TOTALS}: {@code 133 - 112 = 21}.
     */
    public static final int REPORT_PAGE_TOTALS_PAD =
            RECORD_LENGTH - TranReportLayouts.REPORT_PAGE_TOTALS_LENGTH;

    /**
     * Spaces added to {@code REPORT-ACCOUNT-TOTALS}: {@code 133 - 112 = 21}.
     */
    public static final int REPORT_ACCOUNT_TOTALS_PAD =
            RECORD_LENGTH - TranReportLayouts.REPORT_ACCOUNT_TOTALS_LENGTH;

    /**
     * Spaces added to {@code REPORT-GRAND-TOTALS}: {@code 133 - 112 = 21}.
     */
    public static final int REPORT_GRAND_TOTALS_PAD =
            RECORD_LENGTH - TranReportLayouts.REPORT_GRAND_TOTALS_LENGTH;

    /**
     * Spaces added to {@code WS-BLANK-LINE}: {@code 133 - 133 = 0}.
     */
    public static final int WS_BLANK_LINE_PAD = 0;

    /**
     * {@code WS-BLANK-LINE PIC X(133) VALUE SPACES} - {@value #RECORD_LENGTH} spaces.
     */
    public static final String WS_BLANK_LINE_IMAGE = " ".repeat(RECORD_LENGTH);

    private static final RecordLayout FD_REPTFILE_REC_LAYOUT = RecordLayout.of(
            RECORD_LENGTH,
            FieldSpan.alphanumeric(FD_REPTFILE_REC, 0, RECORD_LENGTH));

    private static final Log LOG = LogFactory.getLog(TranReportWriter.class);

    /**
     * Where a rendered {@value TranReportWriter#RECORD_LENGTH}-byte report record goes.
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

    private final JdbcTemplate jdbcTemplate;

    private final RecordImageForm recordImageForm;

    private final FixedWidthCodec codec;

    private final DatasetBinding binding;

    private final DatasetRelation relation;

    private final RuntimeException datasetRefusal;

    /**
     * Wires the writer and verifies, before the application can start, that the configured dataset geometry
     * agrees with the JCL and the COBOL file description.
     *
     * @param jdbcTemplate the module's single {@link JdbcTemplate}
     * @param datasetCharset the code page the dataset holds its record images in, from
     *     {@code carddemo.charset.dataset}
     * @param datasetBindings the DD-name-keyed dataset catalogue bound from {@code carddemo.datasets}
     * @param recordImageForm how a record image crosses JDBC in this deployment
     * @throws NullPointerException if any argument is {@code null}
     * @throws IllegalStateException if no binding is configured for {@link #DD_NAME}, which the catalogue
     *     itself reports, or if the configured record length is not {@value #RECORD_LENGTH}
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
     * {@code OPEN OUTPUT REPORT-FILE} at {@code app/cbl/CBTRN03C.cbl:L396}.
     *
     * @return a new per-execution handle whose {@code FD-REPTFILE-REC} area is {@value #RECORD_LENGTH}
     *     spaces, exactly as a freshly allocated {@code PIC X(133)} FD record area is
     * @throws IllegalStateException if the configured dataset name cannot be addressed as a dataset, as
     *     {@link #insertStatement()} describes
     * @throws NullPointerException if the sink returns a {@code null} outcome from
     *     {@link RecordSink#open()}
     */
    public ReportFile openOutput() {
        return new ReportFile(new JdbcRecordSink(jdbcTemplate, insertStatement(), recordImageForm,
                codec.charset(), requireRelation().describeStatement(),
                requireRelation().deleteAll(), requireRelation().countAllStatement()));
    }

    /**
     * Opens {@link #DD_NAME} for output against a caller-supplied sink.
     *
     * @param sink where rendered records go; must not be {@code null}
     * @return a new per-execution handle, whose {@link ReportFile#openOutcome()} carries what the sink
     *     reported from {@link RecordSink#open()}
     * @throws NullPointerException if {@code sink} is {@code null}, or if the sink returns a {@code null}
     *     outcome from {@link RecordSink#open()}
     */
    public ReportFile openOutput(RecordSink sink) {
        return new ReportFile(Objects.requireNonNull(sink, "A record sink is required to open "
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
         * Establishes the generation this run writes into: {@code OPEN OUTPUT REPORT-FILE} at
         * {@code app/cbl/CBTRN03C.cbl:L396}, over a dataset {@code app/jcl/TRANREPT.jcl:L76-L80} declares
         * {@code DISP=(NEW,CATLG,DELETE)}.
         *
         * <p>An {@code OPEN} on the mainframe resolves the DD name to a real dataset and fails if it
         * cannot.
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
                        + " to the caller, which is the ERROR OPENING REPTFILE arm that sets "
                        + "APPL-RESULT to 12");
                return FileStatus.Outcome.OTHER;
            }
        }

        /**
         * Confirms the destination survived the run: {@code CLOSE REPORT-FILE} at
         * {@code app/cbl/CBTRN03C.cbl:L534}.
         *
         * <p>A close that could not fail would leave that arm unreachable, which is precisely what the
         * COBOL's own guard chain says must not be true.
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
                        + " to the caller, which is the ERROR CLOSING REPORT FILE arm");
                return FileStatus.Outcome.OTHER;
            }
        }

        /**
         * Deletes the generation this run wrote: the {@code DELETE} positional of
         * {@code app/jcl/TRANREPT.jcl:L76-L80}.
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
         * @param recordImage the record's bytes in the dataset code page
         * @return {@link FileStatus.Outcome#OK}, or {@link FileStatus.Outcome#OTHER} when the write was
         *     rejected
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
                // production abend undiagnosable.
                LOG.error("Rejected write of a " + RECORD_LENGTH + "-byte " + DD_NAME
                        + " record - " + BackendDiagnostic.of(rejected).describe()
                        + "; reporting FILE STATUS outcome " + FileStatus.Outcome.OTHER.name()
                        + " to the caller, which is the arm that sets APPL-RESULT to 12");
                return FileStatus.Outcome.OTHER;
            }
        }
    }

    /**
     * One opened {@link #DD_NAME} dataset, together with the {@value #RECORD_LENGTH}-byte
     * {@code FD-REPTFILE-REC} area that records are moved into and written from.
     *
     * <p>A handle is not thread-safe, exactly as a COBOL record area is not, and is meant to be confined to
     * the step, chunk or test that opened it.
     */
    public final class ReportFile implements AutoCloseable {
        private final RecordSink sink;

        private final FixedWidthRecord reportRecord;

        private final FileStatus.Outcome openOutcome;

        private boolean open;

        private int recordsWritten;

        private boolean discarded;

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
         * @return the open outcome, never {@code null}
         */
        public FileStatus.Outcome openOutcome() {
            return openOutcome;
        }

        /**
         * Performs {@code MOVE <layout> TO FD-REPTFILE-REC}: normalises a rendered layout image to exactly
         * {@value #RECORD_LENGTH} characters and stores it in the record area, without writing.
         *
         * @param layoutImage the rendered layout image - one of {@link TranReportLayouts}' {@code render…}
         *     results, or {@link TranReportWriter#WS_BLANK_LINE_IMAGE}
         * @throws NullPointerException if {@code layoutImage} is {@code null}
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
         * Performs {@code MOVE <layout> TO FD-REPTFILE-REC} from a rendered layout image already held as
         * bytes.
         *
         * @param layoutImage the rendered layout image in the dataset code page; any length is accepted
         * @throws NullPointerException if {@code layoutImage} is {@code null}
         * @throws IllegalStateException if a byte is not valid data in the dataset code page, or if this
         *     handle has already been closed
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
         * @return the record image, exactly {@value #RECORD_LENGTH} characters
         */
        public String reportRecord() {
            return codec.readPicX(reportRecord, FD_REPTFILE_REC_LAYOUT.span(FD_REPTFILE_REC));
        }

        /**
         * The current content of {@code FD-REPTFILE-REC} as the bytes that would reach the dataset: exactly
         * {@value #RECORD_LENGTH} of them, in the injected code page.
         *
         * @return a fresh array of exactly {@value #RECORD_LENGTH} bytes, never the record's own backing
         *     array
         */
        public byte[] reportRecordBytes() {
            return reportRecord.toByteArray();
        }

        /**
         * Writes whatever {@code FD-REPTFILE-REC} currently holds, reproducing
         * {@code WRITE FD-REPTFILE-REC} at {@code app/cbl/CBTRN03C.cbl:L345} - the program's one and only
         * write statement for this dataset.
         *
         * <p>Calling it twice without an intervening move writes the same record twice, which is what the
         * COBOL would do and is therefore what it must do here.
         *
         * @return {@link FileStatus.Outcome#OK} when the record was accepted, or
         *     {@link FileStatus.Outcome#OTHER} when the sink rejected it; never {@code null}
         * @throws NullPointerException if the sink returns a {@code null} outcome
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
         * Moves a rendered layout image into {@code FD-REPTFILE-REC} and writes it: the fused {@code MOVE}
         * plus {@code PERFORM 1111-WRITE-REPORT-REC} that every caller paragraph in
         * {@code app/cbl/CBTRN03C.cbl} performs.
         *
         * @param layoutImage the rendered layout image; must not be {@code null}
         * @return {@link FileStatus.Outcome#OK} when the record was accepted, or
         *     {@link FileStatus.Outcome#OTHER} when the sink rejected it; never {@code null}
         * @throws NullPointerException if {@code layoutImage} is {@code null}, or if the sink returns a
         *     {@code null} outcome
         * @throws IllegalStateException if this handle has already been closed
         */
        public FileStatus.Outcome writeLine(String layoutImage) {
            moveToReportRecord(layoutImage);
            return writeReportRec();
        }

        /**
         * Moves a rendered layout image held as bytes into {@code FD-REPTFILE-REC} and writes it.
         *
         * @param layoutImage the rendered layout image in the dataset code page; must not be {@code null}
         * @return {@link FileStatus.Outcome#OK} when the record was accepted, or
         *     {@link FileStatus.Outcome#OTHER} when the sink rejected it; never {@code null}
         * @throws NullPointerException if {@code layoutImage} is {@code null}, or if the sink returns a
         *     {@code null} outcome
         * @throws IllegalStateException if a byte is not valid data in the dataset code page, or if this
         *     handle has already been closed
         */
        public FileStatus.Outcome writeLine(byte[] layoutImage) {
            moveToReportRecord(layoutImage);
            return writeReportRec();
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
         * Closes the dataset, reproducing {@code CLOSE REPORT-FILE} in {@code 9100-REPTFILE-CLOSE} at
         * {@code app/cbl/CBTRN03C.cbl:L534}, and reports the outcome.
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
         * Applies the abnormal disposition of {@code app/jcl/TRANREPT.jcl:L76-L80} -
         * {@code DISP=(NEW,CATLG,DELETE)} - by discarding the report this run wrote.
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
