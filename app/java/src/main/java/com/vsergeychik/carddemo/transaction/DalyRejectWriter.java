package com.vsergeychik.carddemo.transaction;

import com.vsergeychik.carddemo.common.DatasetRelation;
import com.vsergeychik.carddemo.common.DatasetRelation.BackendDiagnostic;
import com.vsergeychik.carddemo.common.FileStatus;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.FixedWidthRecord;
import com.vsergeychik.carddemo.common.FixedWidthRecord.FieldSpan;
import com.vsergeychik.carddemo.common.FixedWidthRecord.PictureKind;
import com.vsergeychik.carddemo.common.FixedWidthRecord.RecordLayout;
import com.vsergeychik.carddemo.common.RecordImageForm;
import com.vsergeychik.carddemo.config.CobolCharsetConfig;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBinding;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBindings;
import com.vsergeychik.carddemo.common.DatasetUnitOfWork;
import com.vsergeychik.carddemo.transaction.model.DalyTranRecord;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementSetter;
import org.springframework.stereotype.Component;

import java.nio.charset.Charset;
import java.sql.PreparedStatement;
import java.util.Objects;

/**
 * Writes the {@code DALYREJS} rejected-transaction file: one fixed {@value #RECORD_LENGTH}-byte record per
 * rejected daily transaction, in the order the posting job rejects them, exactly as
 * {@code app/cbl/CBTRN02C.cbl} does.
 *
 * <p>{@code WS-VALIDATION-FAIL-REASON-DESC PIC X(76)} is alphanumeric: space-padded on the right, and
 * truncated on the right too if a description ever exceeded 76 characters.
 */
@Component
public final class DalyRejectWriter {
    /**
     * The DD name this writer resolves from {@code carddemo.datasets}, verbatim from
     * {@code SELECT DALYREJS-FILE ASSIGN TO DALYREJS} at {@code app/cbl/CBTRN02C.cbl:L46}.
     */
    public static final String DD_NAME = "DALYREJS";

    public static final int RECORD_LENGTH = 430;

    /**
     * The record format the JCL declares: {@code F}, fixed unblocked.
     */
    public static final String RECORD_FORMAT = "F";

    /**
     * The block size the JCL declares: {@code BLKSIZE=0}, which asks the system to determine it.
     */
    public static final int BLOCK_SIZE = 0;

    /**
     * The name of the {@code FD} record area: {@code FD-REJS-RECORD} ({@code app/cbl/CBTRN02C.cbl:L82}).
     */
    public static final String FD_REJS_RECORD = "FD-REJS-RECORD";

    /**
     * The name of the first span, the copied transaction: {@code FD-REJECT-RECORD}
     * ({@code app/cbl/CBTRN02C.cbl:L83}).
     */
    public static final String FD_REJECT_RECORD = "FD-REJECT-RECORD";

    /**
     * The name of the {@code FD}'s single 80-byte trailer span: {@code FD-VALIDATION-TRAILER}
     * ({@code app/cbl/CBTRN02C.cbl:L84}).
     */
    public static final String FD_VALIDATION_TRAILER = "FD-VALIDATION-TRAILER";

    /**
     * The name of the trailer's numeric item: {@code WS-VALIDATION-FAIL-REASON}
     * ({@code app/cbl/CBTRN02C.cbl:L181}).
     */
    public static final String WS_VALIDATION_FAIL_REASON = "WS-VALIDATION-FAIL-REASON";

    /**
     * The name of the trailer's alphanumeric item: {@code WS-VALIDATION-FAIL-REASON-DESC}
     * ({@code app/cbl/CBTRN02C.cbl:L182}).
     */
    public static final String WS_VALIDATION_FAIL_REASON_DESC = "WS-VALIDATION-FAIL-REASON-DESC";

    public static final int FD_REJECT_RECORD_OFFSET = 0;

    /**
     * Width of the copied transaction: {@code 350}, from {@code PIC X(350)} at
     * {@code app/cbl/CBTRN02C.cbl:L83}.
     */
    public static final int FD_REJECT_RECORD_LENGTH = 350;

    public static final int VALIDATION_TRAILER_OFFSET = FD_REJECT_RECORD_LENGTH;

    /**
     * Width of the validation trailer: {@code 80}, from {@code PIC X(80)} at
     * {@code app/cbl/CBTRN02C.cbl:L84}, and equally the sum {@code 4 + 76} of the two items
     * {@code WS-VALIDATION-TRAILER} declares at {@code L180-L182}.
     */
    public static final int VALIDATION_TRAILER_LENGTH = 80;

    public static final int WS_VALIDATION_FAIL_REASON_OFFSET = VALIDATION_TRAILER_OFFSET;

    /**
     * Digit count of the reason code: {@code 4}, from {@code PIC 9(04)} at
     * {@code app/cbl/CBTRN02C.cbl:L181}.
     */
    public static final int WS_VALIDATION_FAIL_REASON_LENGTH = 4;

    public static final int WS_VALIDATION_FAIL_REASON_DESC_OFFSET =
            WS_VALIDATION_FAIL_REASON_OFFSET + WS_VALIDATION_FAIL_REASON_LENGTH;

    /**
     * Width of the reason description: {@code 76}, from {@code PIC X(76)} at
     * {@code app/cbl/CBTRN02C.cbl:L182}.
     */
    public static final int WS_VALIDATION_FAIL_REASON_DESC_LENGTH = 76;

    /**
     * The layout of {@code FD-REJS-RECORD}: the copied transaction, then the reason, then the description,
     * then the {@code FD}'s own 80-byte view of the last two as a {@code REDEFINES} overlay.
     */
    public static final RecordLayout FD_REJS_RECORD_LAYOUT = RecordLayout.of(
            RECORD_LENGTH,
            FieldSpan.alphanumeric(FD_REJECT_RECORD, FD_REJECT_RECORD_OFFSET,
                    FD_REJECT_RECORD_LENGTH),
            FieldSpan.unsignedNumeric(WS_VALIDATION_FAIL_REASON, WS_VALIDATION_FAIL_REASON_OFFSET,
                    WS_VALIDATION_FAIL_REASON_LENGTH),
            FieldSpan.alphanumeric(WS_VALIDATION_FAIL_REASON_DESC,
                    WS_VALIDATION_FAIL_REASON_DESC_OFFSET, WS_VALIDATION_FAIL_REASON_DESC_LENGTH),
            FieldSpan.redefining(FD_VALIDATION_TRAILER, VALIDATION_TRAILER_OFFSET,
                    VALIDATION_TRAILER_LENGTH, PictureKind.ALPHANUMERIC));

    static {
        // The copied span and the record it copies must be the same width, or L447 stops being the pad-free
        // group move the COBOL relies on.
        if (FD_REJECT_RECORD_LENGTH != DalyTranRecord.RECORD_LENGTH) {
            throw new IllegalStateException("FD-REJECT-RECORD is declared PIC X("
                    + FD_REJECT_RECORD_LENGTH + ") at app/cbl/CBTRN02C.cbl:L83 but a DALYTRAN-RECORD "
                    + "is " + DalyTranRecord.RECORD_LENGTH + " bytes (app/cpy/CVTRA06Y.cpy). "
                    + "MOVE DALYTRAN-RECORD TO REJECT-TRAN-DATA at L447 is a group move between items "
                    + "of identical width and pads and truncates nothing, so these two must agree.");
        }
    }

    private static final Log LOG = LogFactory.getLog(DalyRejectWriter.class);

    public static final int REASON_NONE = 0;

    /**
     * Reason {@code 100} - the card number is not in the cross-reference file.
     */
    public static final int REASON_INVALID_CARD_NUMBER = 100;

    /**
     * Reason {@code 101} - the cross-reference resolved, but its account is not in the account file.
     */
    public static final int REASON_ACCOUNT_RECORD_NOT_FOUND = 101;

    /**
     * Reason {@code 102} - posting the transaction would exceed the account's credit limit.
     */
    public static final int REASON_OVERLIMIT_TRANSACTION = 102;

    /**
     * Reason {@code 103} - the transaction was originated after the account expired.
     */
    public static final int REASON_TRANSACTION_AFTER_EXPIRATION = 103;

    /**
     * Reason {@code 109} - the account record could not be rewritten while posting.
     */
    public static final int REASON_ACCOUNT_NOT_FOUND_ON_REWRITE = 109;

    /**
     * The description for {@value #REASON_INVALID_CARD_NUMBER}, 25 characters, from
     * {@code app/cbl/CBTRN02C.cbl:L386}.
     */
    public static final String DESC_INVALID_CARD_NUMBER = "INVALID CARD NUMBER FOUND";

    /**
     * The description for {@value #REASON_ACCOUNT_RECORD_NOT_FOUND}, 24 characters, from
     * {@code app/cbl/CBTRN02C.cbl:L398}.
     */
    public static final String DESC_ACCOUNT_RECORD_NOT_FOUND = "ACCOUNT RECORD NOT FOUND";

    /**
     * The description for {@value #REASON_OVERLIMIT_TRANSACTION}, 21 characters, from
     * {@code app/cbl/CBTRN02C.cbl:L411}.
     */
    public static final String DESC_OVERLIMIT_TRANSACTION = "OVERLIMIT TRANSACTION";

    /**
     * The description for {@value #REASON_TRANSACTION_AFTER_EXPIRATION}, 42 characters, from
     * {@code app/cbl/CBTRN02C.cbl:L418}.
     */
    public static final String DESC_TRANSACTION_AFTER_EXPIRATION =
            "TRANSACTION RECEIVED AFTER ACCT EXPIRATION";

    /**
     * The description {@code MOVE SPACES TO WS-VALIDATION-FAIL-REASON-DESC}
     * ({@code app/cbl/CBTRN02C.cbl:L209}) leaves behind: the empty string, which
     * {@link RejectsFile#moveToValidationTrailer(int, String)} space-pads to the full
     * {@value #WS_VALIDATION_FAIL_REASON_DESC_LENGTH} characters.
     */
    public static final String DESC_SPACES = "";

    /**
     * The description the COBOL pairs with a reject reason code.
     *
     * @param reasonCode a reject reason code
     * @return the description the program moves for that code, or {@link #DESC_SPACES} when the code is not
     *     one the program sets; never {@code null}
     */
    public static String descriptionOfReason(int reasonCode) {
        return switch (reasonCode) {
            case REASON_INVALID_CARD_NUMBER -> DESC_INVALID_CARD_NUMBER;
            case REASON_ACCOUNT_RECORD_NOT_FOUND, REASON_ACCOUNT_NOT_FOUND_ON_REWRITE ->
                    DESC_ACCOUNT_RECORD_NOT_FOUND;
            case REASON_OVERLIMIT_TRANSACTION -> DESC_OVERLIMIT_TRANSACTION;
            case REASON_TRANSACTION_AFTER_EXPIRATION -> DESC_TRANSACTION_AFTER_EXPIRATION;
            default -> DESC_SPACES;
        };
    }

    /**
     * Where a rendered {@value DalyRejectWriter#RECORD_LENGTH}-byte reject record goes.
     *
     * <p>Implementations must preserve call order and must not buffer in a way that could reorder or
     * coalesce records: the rejects file is one sequential pass over {@code DALYTRAN}
     * ({@code app/cbl/CBTRN02C.cbl:L202-L219}), so its record order is part of the parity fingerprint.
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
    public DalyRejectWriter(
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
            throw new IllegalStateException("carddemo.datasets." + DD_NAME + " declares record-length "
                    + binding.recordLength() + ", but a rejected-transaction record is "
                    + RECORD_LENGTH + " bytes: app/cbl/CBTRN02C.cbl:L81-L84 declares "
                    + FD_REJECT_RECORD + " PIC X(" + FD_REJECT_RECORD_LENGTH + ") followed by "
                    + FD_VALIDATION_TRAILER + " PIC X(" + VALIDATION_TRAILER_LENGTH + "), and "
                    + "app/jcl/POSTTRAN.jcl:L36 declares LRECL=" + RECORD_LENGTH + " on the creating "
                    + "step. Correct record-length to " + RECORD_LENGTH + " in application.yml; a "
                    + "record width is fixed by the program's file description and must never be "
                    + "overridden per profile.");
        }
        if (!RECORD_FORMAT.equalsIgnoreCase(binding.recordFormat())) {
            throw new IllegalStateException("carddemo.datasets." + DD_NAME + " declares record-format "
                    + describeConfiguredRecordFormat() + ", but app/jcl/POSTTRAN.jcl:L36 declares "
                    + "RECFM=" + RECORD_FORMAT + ". Fixed unblocked is what makes every reject record "
                    + "exactly " + RECORD_LENGTH + " bytes, and it differs from the FB the two sibling "
                    + "outputs use, so it is required rather than assumed. Set record-format to "
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
     * {@code OPEN OUTPUT DALYREJS-FILE} at {@code app/cbl/CBTRN02C.cbl:L293}.
     *
     * @return a new per-execution handle whose {@code FD-REJS-RECORD} area is {@value #RECORD_LENGTH}
     *     spaces, exactly as a freshly allocated FD record area is
     * @throws IllegalStateException if the configured dataset name cannot be addressed as a dataset, as
     *     {@link #insertStatement()} describes
     * @throws NullPointerException if the sink returns a {@code null} outcome from
     *     {@link RecordSink#open()}
     */
    public RejectsFile openOutput() {
        return new RejectsFile(new JdbcRecordSink(jdbcTemplate, insertStatement(), recordImageForm,
                codec.charset(), requireRelation().describeStatement(),
                requireRelation().deleteAll(), requireRelation().countAllStatement(),
                requireRelation().dsname()));
    }

    /**
     * Opens {@link #DD_NAME} for output against a caller-supplied sink.
     *
     * @param sink where rendered records go; must not be {@code null}
     * @return a new per-execution handle, whose {@link RejectsFile#openOutcome()} carries what the sink
     *     reported from {@link RecordSink#open()}
     * @throws NullPointerException if {@code sink} is {@code null}, or if the sink returns a {@code null}
     *     outcome from {@link RecordSink#open()}
     */
    public RejectsFile openOutput(RecordSink sink) {
        return new RejectsFile(Objects.requireNonNull(sink, "A record sink is required to open "
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

        private final String datasetName;

        JdbcRecordSink(JdbcTemplate jdbcTemplate, String statement, RecordImageForm recordImageForm,
                       Charset charset, String describeStatement, String clearStatement,
                       String countStatement, String datasetName) {
            this.jdbcTemplate = jdbcTemplate;
            this.statement = statement;
            this.recordImageForm = recordImageForm;
            this.charset = charset;
            this.describeStatement = describeStatement;
            this.clearStatement = clearStatement;
            this.countStatement = countStatement;
            this.datasetName = datasetName;
        }

        /**
         * Establishes the generation this run writes into: {@code OPEN OUTPUT DALYREJS-FILE} in
         * {@code 0300-DALYREJS-OPEN}, over a dataset {@code app/jcl/POSTTRAN.jcl:L34-L38} declares
         * {@code DISP=(NEW,CATLG,DELETE)} on {@code DALYREJS(+1)}.
         *
         * <p>The describe resolves the DD name to a real destination and fails if it cannot - read-only,
         * its predicate false on every row, so nothing is transferred - which is how an absent, unreachable
         * or refused destination is reported once at the open rather than record by record.
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
                        + " to the caller, which is the arm that sets APPL-RESULT to 12");
                return FileStatus.Outcome.OTHER;
            }
        }

        /**
         * Confirms the destination survived the run: {@code CLOSE DALYREJS-FILE} in
         * {@code 9300-DALYREJS-CLOSE}.
         *
         * <p>A close that could not fail would leave the COBOL's own close-failure arm unreachable, which
         * is exactly what its guard chain says must not be true.
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
                        + " to the caller, which is the arm that sets APPL-RESULT to 12");
                return FileStatus.Outcome.OTHER;
            }
        }

        /**
         * Deletes the generation this run wrote: the {@code DELETE} positional of
         * {@code app/jcl/POSTTRAN.jcl:L34-L38}.
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
                            + "app/jcl/POSTTRAN.jcl:L34: this run wrote " + recordsWritten
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
                        + ". The generation may remain catalogued, which DISP=(NEW,CATLG,DELETE) says it "
                        + "should not; it must be deleted by hand before the next run");
                return FileStatus.Outcome.OTHER;
            }
        }

        /**
         * Writes one record, mapping a rejected write onto the arm that sets {@code APPL-RESULT} to 12.
         *
         * @param recordImage the record's bytes in the dataset code page
         * @return {@link FileStatus.Outcome#OK}, or {@link FileStatus.Outcome#OTHER} when the write was
         *     rejected
         * @throws IllegalStateException if no unit of work is open, in which case nothing is attempted -
         *     the record would not have been stored and reporting {@link FileStatus.Outcome#OK} would lose a
         *     reject silently
         */

        @Override
        public FileStatus.Outcome write(byte[] recordImage) {
            // The pool hands out connections with auto-commit disabled, so the INSERT would execute, report
            // the row it added, and then be rolled back when the connection returned - and this sink would
            // report OK, which is the DALYREJS-STATUS = '00' arm, for a rejected customer transaction that
            // was never recorded anywhere.
            DatasetUnitOfWork.requireActiveToPersist("A write of a " + DD_NAME + " record, which "
                    + "WRITE FD-REJS-RECORD FROM REJECT-RECORD issues at app/cbl/CBTRN02C.cbl:L451",
                    datasetName);
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
     * {@code FD-REJS-RECORD} area that a rejected transaction and its trailer are moved into and written
     * from.
     *
     * <p>A handle is not thread-safe, exactly as a COBOL record area is not, and is meant to be confined to
     * the step, chunk or test that opened it.
     */
    public final class RejectsFile implements AutoCloseable {
        private final RecordSink sink;

        private final FixedWidthRecord rejectRecord;

        private final FileStatus.Outcome openOutcome;

        private boolean open;

        private int recordsWritten;

        private boolean discarded;

        private RejectsFile(RecordSink sink) {
            this.sink = sink;
            this.rejectRecord = codec.newRecord(FD_REJS_RECORD_LAYOUT);
            this.open = true;
            this.openOutcome = Objects.requireNonNull(sink.open(),
                    "The record sink supplied for " + DD_NAME + " returned a null outcome from "
                            + "open(). A sink must report FileStatus.Outcome.OK when the destination "
                            + "is ready or FileStatus.Outcome.OTHER otherwise, because there is no "
                            + "COBOL FILE STATUS meaning 'no answer'.");
        }

        /**
         * What the sink reported when this handle was opened: the outcome of
         * {@code OPEN OUTPUT DALYREJS-FILE} in {@code 0300-DALYREJS-OPEN}
         * ({@code app/cbl/CBTRN02C.cbl:L291-L307}).
         *
         * @return the open outcome, never {@code null}
         */
        public FileStatus.Outcome openOutcome() {
            return openOutcome;
        }

        /**
         * Performs {@code MOVE DALYTRAN-RECORD TO REJECT-TRAN-DATA}: copies an already-encoded
         * {@value #FD_REJECT_RECORD_LENGTH}-byte transaction image into the first
         * {@value #FD_REJECT_RECORD_LENGTH} bytes of the record area, verbatim.
         *
         * <p>The bytes are copied without being decoded, so {@code DALYTRAN-AMT}'s sign overpunch and the
         * trailing {@code FILLER} cross into the reject record exactly as they stood in the input.
         *
         * @param transactionImage the rejected transaction's bytes in the dataset code page, exactly
         *     {@value #FD_REJECT_RECORD_LENGTH} of them; the array is copied, not retained
         * @throws NullPointerException if {@code transactionImage} is {@code null}
         * @throws IllegalArgumentException if {@code transactionImage} is not exactly
         *     {@value #FD_REJECT_RECORD_LENGTH} bytes long
         * @throws IllegalStateException if this handle has already been closed
         */
        public void moveToRejectTranData(byte[] transactionImage) {
            Objects.requireNonNull(transactionImage, "A sending record is required to MOVE into "
                    + FD_REJECT_RECORD + "; app/cbl/CBTRN02C.cbl:L447 moves DALYTRAN-RECORD, and a "
                    + "COBOL MOVE has no null sender");
            requireOpen("MOVE a transaction into " + FD_REJECT_RECORD);
            if (transactionImage.length != FD_REJECT_RECORD_LENGTH) {
                throw new IllegalArgumentException("A rejected transaction image must be exactly "
                        + FD_REJECT_RECORD_LENGTH + " bytes but " + transactionImage.length
                        + " were supplied. MOVE DALYTRAN-RECORD TO REJECT-TRAN-DATA at "
                        + "app/cbl/CBTRN02C.cbl:L447 is a group move between items of identical width, "
                        + "so it neither pads nor truncates and there is no rule here to adjust a "
                        + "different width by. A "
                        + (transactionImage.length < FD_REJECT_RECORD_LENGTH ? "short" : "long")
                        + " image usually means the trailing FILLER X(20) that "
                        + "app/cpy/CVTRA06Y.cpy:L18 declares was dropped; encode the record through "
                        + "DalyTranRecord, whose width is " + DalyTranRecord.RECORD_LENGTH + " bytes.");
            }
            rejectRecord.writeSpanBytes(FD_REJS_RECORD_LAYOUT.span(FD_REJECT_RECORD), transactionImage);
        }

        /**
         * Performs {@code MOVE DALYTRAN-RECORD TO REJECT-TRAN-DATA} from the record itself.
         *
         * @param transaction the rejected transaction; must not be {@code null}
         * @throws NullPointerException if {@code transaction} is {@code null}
         * @throws IllegalStateException if this handle has already been closed
         */
        public void moveToRejectTranData(DalyTranRecord transaction) {
            Objects.requireNonNull(transaction, "A rejected transaction is required to MOVE into "
                    + FD_REJECT_RECORD);
            moveToRejectTranData(transaction.encode(codec.charset()));
        }

        /**
         * Performs {@code MOVE WS-VALIDATION-TRAILER TO VALIDATION-TRAILER}: renders the reason code and
         * its description into the trailing {@value #VALIDATION_TRAILER_LENGTH} bytes of the record area.
         *
         * @param reasonCode the reject reason, a scale-free {@code PIC 9(04)} value; must not be negative
         * @param description the reason text; any length is accepted, including empty
         *     ({@link #DESC_SPACES}), and is normalised as described above
         * @throws NullPointerException if {@code description} is {@code null}
         * @throws IllegalArgumentException if {@code reasonCode} is negative
         * @throws IllegalStateException if this handle has already been closed
         */
        public void moveToValidationTrailer(int reasonCode, String description) {
            Objects.requireNonNull(description, "A reason description is required to MOVE into "
                    + WS_VALIDATION_FAIL_REASON_DESC + "; to blank it pass DESC_SPACES explicitly, "
                    + "which is what MOVE SPACES at app/cbl/CBTRN02C.cbl:L209 does, rather than null");
            requireOpen("MOVE a validation trailer into " + FD_VALIDATION_TRAILER);
            // writePic9 refuses a negative value on the caller's behalf: PIC 9(04) is unsigned, so a
            // negative reason has no representation and must not be stored as its magnitude.
            codec.writePic9(rejectRecord, FD_REJS_RECORD_LAYOUT.span(WS_VALIDATION_FAIL_REASON),
                    reasonCode);
            codec.writePicX(rejectRecord, FD_REJS_RECORD_LAYOUT.span(WS_VALIDATION_FAIL_REASON_DESC),
                    description);
        }

        /**
         * Performs {@code MOVE WS-VALIDATION-TRAILER TO VALIDATION-TRAILER} using the description the
         * program pairs with the given code.
         *
         * @param reasonCode the reject reason; must not be negative
         * @throws IllegalArgumentException if {@code reasonCode} is negative
         * @throws IllegalStateException if this handle has already been closed
         */
        public void moveToValidationTrailer(int reasonCode) {
            moveToValidationTrailer(reasonCode, descriptionOfReason(reasonCode));
        }

        /**
         * The current content of {@code FD-REJS-RECORD} as the bytes that would reach the dataset: exactly
         * {@value #RECORD_LENGTH} of them, in the injected code page.
         *
         * @return a fresh array of exactly {@value #RECORD_LENGTH} bytes, never the record's own backing
         *     array
         */
        public byte[] rejectRecordBytes() {
            return rejectRecord.toByteArray();
        }

        /**
         * The current content of {@code FD-REJS-RECORD}: exactly {@value #RECORD_LENGTH} characters, every
         * pad byte included.
         *
         * @return the record image, exactly {@value #RECORD_LENGTH} characters
         */
        public String rejectRecord() {
            return rejectRecord.readString(0, RECORD_LENGTH);
        }

        /**
         * The copied transaction currently held in the record area, as bytes.
         *
         * @return a fresh array of exactly {@value #FD_REJECT_RECORD_LENGTH} bytes
         */
        public byte[] rejectTranDataBytes() {
            return rejectRecord.readSpanBytes(FD_REJS_RECORD_LAYOUT.span(FD_REJECT_RECORD));
        }

        /**
         * The validation trailer currently held in the record area, read through the {@code FD}'s own
         * single {@value #VALIDATION_TRAILER_LENGTH}-byte view.
         *
         * @return the trailer image, exactly {@value #VALIDATION_TRAILER_LENGTH} characters
         */
        public String validationTrailer() {
            return rejectRecord.readSpan(FD_REJS_RECORD_LAYOUT.span(FD_VALIDATION_TRAILER));
        }

        /**
         * The reason code currently held in the record area, decoded from its four zoned digits.
         *
         * @return the reason code
         * @throws IllegalArgumentException if the span does not hold four digits, which this class's own
         *     API cannot bring about - {@code writePic9} only ever stores digits
         */
        public int validationFailReason() {
            return codec.readPic9AsInt(rejectRecord,
                    FD_REJS_RECORD_LAYOUT.span(WS_VALIDATION_FAIL_REASON));
        }

        /**
         * The reason description currently held in the record area, untrimmed.
         *
         * @return the description image, exactly {@value #WS_VALIDATION_FAIL_REASON_DESC_LENGTH}
         *     characters, trailing pad included
         */
        public String validationFailReasonDesc() {
            return codec.readPicX(rejectRecord,
                    FD_REJS_RECORD_LAYOUT.span(WS_VALIDATION_FAIL_REASON_DESC));
        }

        /**
         * Writes whatever {@code FD-REJS-RECORD} currently holds, reproducing
         * {@code WRITE FD-REJS-RECORD FROM REJECT-RECORD} at {@code app/cbl/CBTRN02C.cbl:L451} - the
         * program's one and only write statement for this dataset.
         *
         * <p>Calling it twice without an intervening move writes the same record twice, which is what the
         * COBOL would do and is therefore what it must do here.
         *
         * @return {@link FileStatus.Outcome#OK} when the record was accepted, or
         *     {@link FileStatus.Outcome#OTHER} when the sink rejected it; never {@code null}
         * @throws NullPointerException if the sink returns a {@code null} outcome
         * @throws IllegalStateException if this handle has already been closed; if the record area is not
         *     {@value #RECORD_LENGTH} bytes
         */
        public FileStatus.Outcome writeRejectRec() {
            requireOpen("WRITE " + FD_REJS_RECORD);
            byte[] image = rejectRecordBytes();
            if (image.length != RECORD_LENGTH) {
                throw new IllegalStateException("The " + FD_REJS_RECORD + " area rendered "
                        + image.length + " bytes but every " + DD_NAME + " record is exactly "
                        + RECORD_LENGTH + " (" + FD_REJECT_RECORD_LENGTH + " + "
                        + WS_VALIDATION_FAIL_REASON_LENGTH + " + "
                        + WS_VALIDATION_FAIL_REASON_DESC_LENGTH + "): app/jcl/POSTTRAN.jcl:L36 declares "
                        + "RECFM=" + RECORD_FORMAT + ",LRECL=" + RECORD_LENGTH + ". A record of any "
                        + "other width would shift every following record in a fixed-format file, so it "
                        + "is refused rather than written.");
            }
            FileStatus.Outcome outcome = Objects.requireNonNull(sink.write(image),
                    "The record sink supplied for " + DD_NAME + " returned a null outcome from "
                            + "write(byte[]) after " + recordsWritten + " record(s). A sink must "
                            + "report FileStatus.Outcome.OK for the DALYREJS-STATUS = '00' arm or "
                            + "FileStatus.Outcome.OTHER for any failure - the arm that sets "
                            + "APPL-RESULT to 12 - because there is no COBOL FILE STATUS meaning "
                            + "'no answer'.");
            recordsWritten++;
            return outcome;
        }

        /**
         * The whole of {@code 2500-WRITE-REJECT-REC}: both moves and the write, in the source's order.
         *
         * @param transactionImage the rejected transaction's bytes, exactly
         *     {@value #FD_REJECT_RECORD_LENGTH} of them
         * @param reasonCode the reject reason; must not be negative
         * @param description the reason text; any length, normalised to
         *     {@value #WS_VALIDATION_FAIL_REASON_DESC_LENGTH} characters
         * @return {@link FileStatus.Outcome#OK} when the record was accepted, or
         *     {@link FileStatus.Outcome#OTHER} when the sink rejected it; never {@code null}
         * @throws NullPointerException if {@code transactionImage} or {@code description} is {@code null},
         *     or if the sink returns a {@code null} outcome
         * @throws IllegalArgumentException if {@code transactionImage} is not exactly
         *     {@value #FD_REJECT_RECORD_LENGTH} bytes, or if {@code reasonCode} is negative
         * @throws IllegalStateException if this handle has already been closed
         */
        public FileStatus.Outcome writeRejectRec(byte[] transactionImage, int reasonCode,
                                                String description) {
            moveToRejectTranData(transactionImage);
            moveToValidationTrailer(reasonCode, description);
            return writeRejectRec();
        }

        /**
         * The whole of {@code 2500-WRITE-REJECT-REC} from an encoded image, with the description the
         * program pairs with the given code.
         *
         * @param transactionImage the rejected transaction's bytes, exactly
         *     {@value #FD_REJECT_RECORD_LENGTH} of them
         * @param reasonCode the reject reason; must not be negative
         * @return {@link FileStatus.Outcome#OK} when the record was accepted, or
         *     {@link FileStatus.Outcome#OTHER} when the sink rejected it; never {@code null}
         * @throws NullPointerException if {@code transactionImage} is {@code null}, or if the sink returns
         *     a {@code null} outcome
         * @throws IllegalArgumentException if {@code transactionImage} is not exactly
         *     {@value #FD_REJECT_RECORD_LENGTH} bytes, or if {@code reasonCode} is negative
         * @throws IllegalStateException if this handle has already been closed
         */
        public FileStatus.Outcome writeRejectRec(byte[] transactionImage, int reasonCode) {
            return writeRejectRec(transactionImage, reasonCode, descriptionOfReason(reasonCode));
        }

        /**
         * The whole of {@code 2500-WRITE-REJECT-REC} from the record itself, with the description the
         * program pairs with the given code.
         *
         * @param transaction the rejected transaction; must not be {@code null}
         * @param reasonCode the reject reason; must not be negative
         * @return {@link FileStatus.Outcome#OK} when the record was accepted, or
         *     {@link FileStatus.Outcome#OTHER} when the sink rejected it; never {@code null}
         * @throws NullPointerException if {@code transaction} is {@code null}, or if the sink returns a
         *     {@code null} outcome
         * @throws IllegalArgumentException if {@code reasonCode} is negative
         * @throws IllegalStateException if this handle has already been closed
         */
        public FileStatus.Outcome writeRejectRec(DalyTranRecord transaction, int reasonCode) {
            moveToRejectTranData(transaction);
            moveToValidationTrailer(reasonCode);
            return writeRejectRec();
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
         * Closes the dataset, reproducing {@code CLOSE DALYREJS-FILE} in {@code 9300-DALYREJS-CLOSE} at
         * {@code app/cbl/CBTRN02C.cbl:L639}, and reports the outcome.
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
         * Applies the abnormal disposition of {@code app/jcl/POSTTRAN.jcl:L34-L38} -
         * {@code DISP=(NEW,CATLG,DELETE)} - by discarding everything this run wrote.
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
                        + "(app/cbl/CBTRN02C.cbl:L198) and closes it once at the end (L224), and the "
                        + "JCL creates a new GDG generation per run, so open a new handle for a new run "
                        + "rather than reusing a closed one.");
            }
        }
    }
}
