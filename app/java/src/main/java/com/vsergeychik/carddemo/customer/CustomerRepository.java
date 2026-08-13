package com.vsergeychik.carddemo.customer;

import com.vsergeychik.carddemo.common.CicsResponse;
import com.vsergeychik.carddemo.common.DatasetRelation;
import com.vsergeychik.carddemo.common.DatasetRelation.BackendDiagnostic;
import com.vsergeychik.carddemo.common.DatasetRelation.KeySpan;
import com.vsergeychik.carddemo.common.FileStatus;
import com.vsergeychik.carddemo.common.FileStatus.Outcome;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.FixedWidthRecord;
import com.vsergeychik.carddemo.common.RecordImageForm;
import com.vsergeychik.carddemo.common.SensitiveDiagnostics;
import com.vsergeychik.carddemo.config.CobolCharsetConfig;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBinding;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBindings;
import com.vsergeychik.carddemo.common.DatasetUnitOfWork;
import com.vsergeychik.carddemo.customer.model.CustomerRecord;

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

import java.nio.charset.Charset;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * The customer master dataset, reached over JDBC: one repository for the five access paths the COBOL estate
 * drives against it, and nothing beyond them.
 *
 * <p>{@code CUST-SSN PIC 9(09)} and {@code CUST-GOVT-ISSUED-ID PIC X(20)} are stored in the clear, and this
 * class neither masks, hashes, truncates nor redacts them on the way in or out.
 */
@Repository
public class CustomerRepository {
    private static final Log LOG = LogFactory.getLog(CustomerRepository.class);

    /**
     * The CICS {@code FILE} name of the customer master: {@code CUSTDAT}.
     */
    public static final String CICS_FILE_NAME = "CUSTDAT";

    /**
     * The batch DD name of the same dataset: {@code CUSTFILE}.
     */
    public static final String BATCH_DD_NAME = "CUSTFILE";

    public static final int RECORD_LENGTH = CustomerRecord.RECORD_LENGTH;

    private static final String RECORD_IMAGE_SUBJECT =
            "the stored CUSTOMER-RECORD image displayed by app/cbl/CBCUS01C.cbl:78 and :96";

    /**
     * The primary-key width in bytes: nine, taken from the declared width of {@code CUST-ID} rather than
     * restated as a literal.
     */
    public static final int KEY_LENGTH = CustomerRecord.CUST_ID.length();

    public static final int RECORD_IMAGE_COLUMN_INDEX = DatasetRelation.RECORD_IMAGE_COLUMN_INDEX;

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

    private static final KeySpan KEY_SPAN =
            new KeySpan(CustomerRecord.CUST_ID.offset(), KEY_LENGTH);

    private static final int FAN_OUT_PROBE_LIMIT = 2;

    private final JdbcTemplate jdbcTemplate;

    private final FixedWidthCodec codec;

    private final String datasetName;

    private final DatasetRelation relation;

    private final RecordImageForm recordImageForm;

    /**
     * The statement set this relation's record-image column composes, held from the describe that
     * discovered it so that the describe is issued once per open rather than once per read.
     *
     * <p>A {@link Statements} is an immutable record of statement <em>text</em>, so publishing it through a
     * {@code volatile} field is the whole of the thread-safety this needs: a reader either sees the fully
     * composed set or sees nothing and composes it, and two readers racing at startup each issue one
     * describe and store equal values. It is the pattern every other repository in the module already uses
     * - {@code CardRepository}, {@code CardXrefRepository}, {@code TransactionRepository},
     * {@code TranTypeRepository}, {@code TranCategoryRepository}, {@code SecUserRepository} and
     * {@code StatementGenerationJobB} - and this class was the odd one out.
     *
     * <p>This holds no customer data and no result of a read. It is column metadata, so it is not the
     * caching of business data that AAP 0.8.6 forbids: the keyed read, the browse and the rewrite still
     * reach the backend every single time they are called, in the same order, with the same predicates.
     * What no longer reaches the backend is a second {@code WHERE 1 = 0} probe asking a question already
     * answered - free against a co-located database and one network round trip against the gateway a
     * deployment actually binds.
     *
     * <p>Cleared by every {@link #openInput()}, which pairs with
     * {@link DatasetRelation#forgetRecordImageColumn()}: a COBOL {@code OPEN} learns the file afresh, so an
     * open re-describes and the shape it discovers becomes the shape later reads use.
     */
    private volatile Statements statements;

    /**
     * Resolves the customer master's configured bindings, proves the record geometry, and captures the
     * collaborators - all before the context finishes starting, so nothing checkable is left to fail
     * mid-job.
     *
     * <p>The startup validator already requires a keyed entry to declare one, so an absent width means a
     * caller built the binding by hand and is accepted; a present one that disagrees with the copybook is a
     * defect; the two bindings name the same dataset.
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
    public CustomerRepository(
            JdbcTemplate jdbcTemplate,
            DatasetBindings datasetBindings,
            @Qualifier(CobolCharsetConfig.DATASET_CHARSET_BEAN_NAME) Charset datasetCharset,
            RecordImageForm recordImageForm) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "A JdbcTemplate is required: the "
                + "customer master is reached through the module's shared template");
        Objects.requireNonNull(datasetBindings, "The carddemo.datasets binding catalogue is required: "
                + "dataset names live in configuration and are never written in Java");
        this.codec = new FixedWidthCodec(Objects.requireNonNull(datasetCharset, "A dataset charset is "
                + "required: a fixed-width mainframe record is bytes in a specific code page, so the "
                + "code page is stated explicitly and never taken from the platform"));
        this.recordImageForm = Objects.requireNonNull(recordImageForm, "A record-image representation is "
                + "required: whether this deployment's driver presents a record image as characters or as "
                + "bytes is stated once, by " + RecordImageForm.FORM_PROPERTY + ", and never decided per "
                + "repository");
        RecordImageForm.requireSingleByteCodePage(datasetCharset);

        DatasetBinding cicsBinding = requireCustomerGeometry(datasetBindings, CICS_FILE_NAME);
        DatasetBinding batchBinding = requireCustomerGeometry(datasetBindings, BATCH_DD_NAME);
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
     * @return 500
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
     * Opens the customer master for input and hands back the handle that stands for that open: the Java
     * form of {@code OPEN INPUT} at {@code app/cbl/CBCUS01C.cbl:L120}, {@code app/cbl/CBTRN01C.cbl:L273}
     * and {@code app/cbl/CBSTM03B.CBL:L184}.
     *
     * <p>A mode parameter would therefore offer a choice the COBOL never makes, and the returned handle is
     * read-only for the same reason - the update path is CICS-only and issues no open at all.
     *
     * @return a freshly positioned handle whose {@link CustomerFile#openStatus()} reports whether the
     *     dataset was addressable; never {@code null}
     * @throws IllegalStateException if the backend presents the dataset with no usable record-image column,
     *     which is a contract violation rather than an I/O outcome
     */
    public CustomerFile openInput() {
        this.statements = null;
        this.relation.forgetRecordImageColumn();

        Statements resolved;
        try {
            resolved = resolveStatements();
        } catch (DataAccessException unreachable) {
            logRefusal(unreachable, "describe the customer master dataset '" + datasetName
                    + "' to OPEN INPUT it");
            return new CustomerFile(this, PERMANENT_ERROR_STATUS, null);
        }
        this.statements = resolved;
        return new CustomerFile(this, FileStatus.OK, resolved);
    }

    /**
     * Reads one record by customer identifier: the Java form of the numeric view of the key.
     *
     * <p>The number is rendered to the stored nine-digit zero-filled image by the module's {@code PIC 9}
     * move rule, so an identifier wider than nine digits loses its high order digits exactly as COBOL's
     * decimal-point alignment makes it lose them, and is not rejected.
     *
     * @param custId the customer identifier; must not be negative
     * @return the discriminated outcome; never {@code null}
     * @throws IllegalArgumentException if {@code custId} is negative
     * @throws IllegalStateException if the backend presents the dataset with no usable record-image column
     */
    public ReadResult readByKey(long custId) {
        return readKeyed(keyImageOf(custId), "the customer identifier "
                + SensitiveDiagnostics.maskIdentifier(custId, KEY_LENGTH), false);
    }

    /**
     * Reads one record by its nine-character key image: the Java form of
     * {@code app/cbl/COACTVWC.cbl:L826-L834}, and of {@code app/cbl/CBSTM03B.CBL:L189-L190}, whose
     * {@code M03B-READ-K} arm moves {@code LK-M03B-KEY (1:LK-M03B-KEY-LN)} - a character substring - into
     * {@code FD-CUST-ID PIC X(09)} before reading.
     *
     * <p>A {@code String} of exactly {@link #KEY_LENGTH} characters because the {@code RIDFLD} is the
     * character view of the key: {@code WS-CARD-RID-CUST-ID-X REDEFINES WS-CARD-RID-CUST-ID PIC X(09)}
     * ({@code app/cbl/COACTVWC.cbl:L76-L77}).
     *
     * @param custIdAsChar9 the key exactly as the {@code RIDFLD} holds it: exactly {@link #KEY_LENGTH}
     *     characters, untrimmed and unparsed
     * @return the discriminated outcome; never {@code null}
     * @throws NullPointerException if {@code custIdAsChar9} is {@code null}
     * @throws IllegalArgumentException if {@code custIdAsChar9} is not exactly {@link #KEY_LENGTH}
     *     characters
     * @throws IllegalStateException if the backend presents the dataset with no usable record-image column
     */
    public ReadResult readByKey(String custIdAsChar9) {
        String keyImage = requireKeyImage(custIdAsChar9);
        return readKeyed(keyImage, "the record identification field '"
                + SensitiveDiagnostics.maskIdentifier(keyImage) + "'", false);
    }

    /**
     * Reads one record for update by its nine-character key image: the Java form of
     * {@code app/cbl/COACTUPC.cbl:L3921-L3930}, A distinct operation from {@link #readByKey(String)},
     * deliberately.
     *
     * @param custIdAsChar9 the key exactly as the {@code RIDFLD} holds it: exactly {@link #KEY_LENGTH}
     *     characters, untrimmed and unparsed
     * @return the discriminated outcome; never {@code null}
     * @throws NullPointerException if {@code custIdAsChar9} is {@code null}
     * @throws IllegalArgumentException if {@code custIdAsChar9} is not exactly {@link #KEY_LENGTH}
     *     characters
     * @throws IllegalStateException if no unit of work is open, or if the backend presents the dataset with
     *     no usable record-image column
     */
    public ReadResult readForUpdate(String custIdAsChar9) {
        String keyImage = requireKeyImage(custIdAsChar9);
        requireUnitOfWork();
        return readKeyed(keyImage, "the record identification field '"
                + SensitiveDiagnostics.maskIdentifier(keyImage) + "' for update", true);
    }

    /**
     * Rewrites one record in place from a complete record image: the Java form of
     * {@code app/cbl/COACTUPC.cbl:L4085-L4091}, Why the operand is a raw image and not a
     * {@link CustomerRecord}.
     *
     * @param recordImage the complete record to write, exactly {@link #RECORD_LENGTH} bytes, already
     *     assembled by the caller
     * @return the discriminated outcome; never {@code null}
     * @throws NullPointerException if {@code recordImage} is {@code null}
     * @throws IllegalArgumentException if {@code recordImage} is not exactly {@link #RECORD_LENGTH} bytes
     * @throws IllegalStateException if no unit of work is open, in which case nothing has been attempted -
     *     see {@link DatasetUnitOfWork#requireActiveToPersist(String, String)}
     */
    public WriteResult rewrite(byte[] recordImage) {
        byte[] image = requireRecordImage(recordImage);
        Statements sql;
        try {
            sql = statements();
        } catch (DataAccessException unreachable) {
            return reportWrite(unreachable, "describe the customer master dataset '" + datasetName
                    + "' to rewrite a record");
        }
        return rewrite(sql, image);
    }

    /**
     * Rewrites one record in place from the module's record model: {@link #rewrite(byte[])} over
     * {@link CustomerRecord#encode(FixedWidthCodec)}.
     *
     * @param record the record to write, complete and already mutated by the caller
     * @return the discriminated outcome; never {@code null}
     * @throws NullPointerException if {@code record} is {@code null}
     * @throws IllegalStateException if no unit of work is open, in which case nothing has been attempted;
     *     if the backend presents the dataset with no usable record-image column; or
     */
    public WriteResult rewrite(CustomerRecord record) {
        Objects.requireNonNull(record, "A record is required to rewrite it; a COBOL REWRITE writes the "
                + "record area, and there is no such thing as rewriting nothing");
        return rewrite(record.encode(codec));
    }

    // =================================================================================================
    // The held rewrite - the same app/cbl/COACTUPC.cbl:L4085-L4091 command, addressed by the row the
    // preceding READ ... UPDATE holds rather than by the key inside the record area.
    // =================================================================================================

    /**
     * Rewrites <strong>the row a preceding {@link #readForUpdate(String)} returned</strong>, from a
     * complete record image.
     *
     * <p><strong>Why the held row and not the key.</strong> {@code EXEC CICS REWRITE} at
     * {@code app/cbl/COACTUPC.cbl:L4085-L4091} carries no {@code RIDFLD}. In CICS that is not an omission
     * but the definition of the command: it replaces the record the task is holding from the
     * {@code READ ... UPDATE} at {@code L3921-L3930}, and the key bytes inside {@code CUST-UPDATE-RECORD}
     * do not select the row. That distinction is load-bearing here, because the two keys have
     * <em>different provenance</em>: the lock is taken with {@code CDEMO-CUST-ID} from the communication
     * area ({@code MOVE CDEMO-CUST-ID TO WS-CARD-RID-CUST-ID}, {@code L3919}) while the staged image's
     * {@code CUST-UPDATE-ID} comes from {@code ACUP-NEW-CUST-ID} ({@code L4009}), which
     * {@code L1234-L1240} moved out of the screen's {@code ACSTNUMI}. On a 3270 those two are necessarily
     * equal - {@code L3531} moves {@code DFHBMPRF} onto {@code ACSTNUMA}, so the field is protected and
     * modified-data-tagged and the terminal can only send back the value the program painted, which is
     * why {@code L1222} calls the customer identifier "actually not editable". A REST payload has no such
     * guarantee. Addressing the rewrite by the key inside the payload-derived image would therefore let a
     * crafted request lock one customer and overwrite another; addressing it by the held row cannot.
     *
     * <p><strong>A key that disagrees with the held row is refused, not obeyed.</strong> When the image's
     * {@code CUST-ID} span differs from the held row's, no statement is issued at all and the outcome is
     * the permanent-error status carrying {@code DFHRESP(INVREQ)} - which is what CICS reports for a
     * {@code REWRITE} whose record area no longer matches the record being held, and which lands the
     * caller on {@code app/cbl/COACTUPC.cbl:L4096-L4103}: {@code SET LOCKED-BUT-UPDATE-FAILED TO TRUE}
     * and the {@code EXEC CICS SYNCPOINT ROLLBACK} that backs the account rewrite out. Refusing is
     * therefore not an invented behaviour but the one the source already has code for, and it is
     * preferred to quietly substituting the held key because it leaves the tampering observable.
     *
     * <p><strong>The row written is the row read, matched on its whole stored image.</strong> The
     * predicate is the held row's {@link #RECORD_LENGTH} bytes, not its key, exactly as the held delete of
     * {@code USRSEC} is. Two consequences, both wanted: a row that changed between the read and the
     * rewrite - which {@code UPDATEMODEL(LOCKING)} exists to prevent, but which a deployment whose backend
     * does not honour {@code FOR UPDATE} would allow - is reported as the invalid-key condition instead of
     * being overwritten unseen; and the row's identity cannot be redirected by anything in the new image.
     *
     * <p>As with every write in this class the fan-out probe runs <em>before</em> the update, under the
     * same lock the update will use, so a relation in which the held image is not unique is refused rather
     * than discovered from an affected-row count after the rows have already been replaced.
     *
     * @param heldStoredImage the stored image of the row this task holds, exactly as
     *                        {@link ReadResult#requireStoredImage()} presented it - exactly
     *                        {@link #RECORD_LENGTH} characters
     * @param recordImage     the complete record to write, exactly {@link #RECORD_LENGTH} bytes
     * @return the discriminated outcome; never {@code null}
     * @throws NullPointerException     if either argument is {@code null}
     * @throws IllegalArgumentException if either image is not exactly {@link #RECORD_LENGTH} in its own
     *                                  unit
     * @throws IllegalStateException    if no unit of work is open, in which case nothing has been
     *                                  attempted; if the backend presents the dataset with no usable
     *                                  record-image column; or - as a
     *                                  {@link com.vsergeychik.carddemo.common.DatasetIntegrityException} -
     *                                  if the write replaced more rows than the held image selected when
     *                                  it was checked
     */
    public WriteResult rewriteHeld(String heldStoredImage, byte[] recordImage) {
        byte[] image = requireRecordImage(recordImage);
        byte[] held = requireHeldImage(heldStoredImage);
        Statements sql;
        try {
            sql = statements();
        } catch (DataAccessException unreachable) {
            return reportWrite(unreachable, "describe the customer master dataset '" + datasetName
                    + "' to rewrite the record it is holding");
        }
        return rewriteHeld(sql, held, image);
    }

    /**
     * Rewrites the held row from the module's record model: {@link #rewriteHeld(String, byte[])} over
     * {@link CustomerRecord#encode(FixedWidthCodec)}.
     *
     * <p>Encoding here rather than at the call site keeps the code page - and therefore the stored bytes -
     * the same on the write as on the read that produced the held image, which is what makes the
     * whole-image predicate comparable at all.
     *
     * @param heldStoredImage the stored image of the row this task holds, exactly
     *                        {@link #RECORD_LENGTH} characters
     * @param record          the record to write, complete and already mutated by the caller
     * @return the discriminated outcome; never {@code null}
     * @throws NullPointerException     if either argument is {@code null}
     * @throws IllegalArgumentException if {@code heldStoredImage} is not exactly {@link #RECORD_LENGTH}
     *                                  characters
     * @throws IllegalStateException    if no unit of work is open, if the backend presents the dataset with
     *                                  no usable record-image column, or - as a
     *                                  {@link com.vsergeychik.carddemo.common.DatasetIntegrityException} -
     *                                  if the write replaced more rows than the held image selected
     */
    public WriteResult rewriteHeld(String heldStoredImage, CustomerRecord record) {
        Objects.requireNonNull(record, "A record is required to rewrite the held row; a COBOL REWRITE "
                + "writes the record area, and there is no such thing as rewriting nothing");
        return rewriteHeld(heldStoredImage, record.encode(codec));
    }

    /**
     * Rewrites the held row against already-resolved statements.
     *
     * <p>The single body {@link #rewriteHeld(String, byte[])} reaches, kept separate from
     * {@link #rewrite(Statements, byte[])} because the two address different things: this one addresses a
     * row, that one addresses a key.
     *
     * @param sql   the resolved statements
     * @param held  the held row's stored image, already validated as {@link #RECORD_LENGTH} bytes
     * @param image the complete record to write, already validated as {@link #RECORD_LENGTH} bytes
     * @return the discriminated outcome; never {@code null}
     */
    private WriteResult rewriteHeld(Statements sql, byte[] held, byte[] image) {
        // Refused before anything is attempted when no unit of work is open: the lock the held read took
        // is what makes "the row this task holds" mean anything, and a lock taken outside a transaction
        // was released the moment the read returned.
        DatasetUnitOfWork.requireActiveToPersist("A rewrite of the customer master, which EXEC CICS "
                + "REWRITE issues against the record the preceding READ ... UPDATE still holds "
                + "(app/cbl/COACTUPC.cbl:L4085-L4091, after the locking read at L3921-L3930)",
                datasetName);

        String heldKey = keyImageOf(held);
        String newKey = keyImageOf(image);
        if (!newKey.equals(heldKey)) {
            // CICS reports INVREQ rather than rewriting a different record, and COACTUPC:L4096-L4103 has
            // the arm for it. The two masked keys are logged because "which row was locked and which row
            // the image named" is the whole diagnosis; neither value is a credential.
            LOG.error("A rewrite of the customer master dataset '" + datasetName + "' was refused: the "
                    + "record area names customer " + SensitiveDiagnostics.maskIdentifier(newKey)
                    + " while the row this task holds is customer "
                    + SensitiveDiagnostics.maskIdentifier(heldKey) + ". EXEC CICS REWRITE at "
                    + "app/cbl/COACTUPC.cbl:L4085-L4091 carries no RIDFLD and so can only replace the "
                    + "held record; the customer identifier is protected on the screen "
                    + "(app/cbl/COACTUPC.cbl:L3531 moves DFHBMPRF onto ACSTNUMA) and is documented at "
                    + "L1222 as not editable, so a differing value cannot have come from the terminal "
                    + "contract. File status "
                    + FileStatus.toStatusImage(PERMANENT_ERROR_STATUS) + " is reported and no row has "
                    + "been changed.");
            return WriteResult.of(PERMANENT_ERROR_STATUS, CicsResponse.of(FileStatus.INVREQ));
        }

        // Establish that the held image names exactly one row BEFORE any row is replaced, under the same
        // FOR UPDATE lock the update will use so nothing can change between the two.
        int matching;
        try {
            matching = matchingImageCount(sql.selectByImageForUpdate(), held);
        } catch (DataAccessException rejected) {
            return reportWrite(rejected, "establish how many rows the held record selects in the "
                    + "customer master dataset '" + datasetName + "' before rewriting it");
        }
        if (matching == 0) {
            // The held row is no longer there. Nothing is written, and this is the invalid-key condition
            // exactly as it would be had the row never been read.
            return WriteResult.notFound();
        }
        if (matching > 1) {
            LOG.error("The held record of customer " + SensitiveDiagnostics.maskIdentifier(heldKey)
                    + " selects more than one row of dataset '" + datasetName + "'; a KSDS primary key is "
                    + "unique - app/jcl/CUSTFILE.jcl:L50 declares KEYS(9 0) - so the rewrite is being "
                    + "refused before it is issued and file status "
                    + FileStatus.toStatusImage(PERMANENT_ERROR_STATUS) + " reported to the caller. No row "
                    + "has been changed.");
            return WriteResult.of(PERMANENT_ERROR_STATUS, CicsResponse.of(FileStatus.INVREQ));
        }

        int rewritten;
        try {
            PreparedStatementSetter binder = parameters -> {
                recordImageForm.bindImage(parameters, 1, image, codec.charset());
                recordImageForm.bindImage(parameters, 2, held, codec.charset());
            };
            rewritten = jdbcTemplate.update(sql.rewriteByImage(), binder);
        } catch (DataAccessException rejected) {
            return reportWrite(rejected, "rewrite the held record of the customer master dataset '"
                    + datasetName + "'");
        }

        if (rewritten == 1) {
            return WriteResult.written();
        }
        if (rewritten == 0) {
            // The row the probe found is gone. Nothing was written, so this is the invalid-key condition.
            return WriteResult.notFound();
        }
        // The probe said one row and the UPDATE replaced several, so the rows changed underneath it. The
        // damage is done and a status would let it commit, so the unit of work is refused instead.
        throw DatasetUnitOfWork.commitRefusal(
                "The rewrite of the held record of customer "
                        + SensitiveDiagnostics.maskIdentifier(heldKey) + " in dataset '" + datasetName
                        + "'",
                rewritten + " rows were replaced where the held record selected exactly one when it was "
                        + "checked under a row lock");
    }

    /**
     * Rewrites one record image against already-resolved statements.
     *
     * <p>The single body both public rewrites reach, so the fan-out precaution and the outcome ladder
     * cannot drift between them.
     *
     * @param sql   the resolved statements
     * @param image the complete record to write, already validated as {@link #RECORD_LENGTH} bytes
     * @return the discriminated outcome; never {@code null}
     */
    private WriteResult rewrite(Statements sql, byte[] image) {
        // COACTUPC's 9600-WRITE-PROCESSING rewrites the account and the customer inside one CICS task, so
        // the boundary AccountUpdateService opens is the same one this requires.
        DatasetUnitOfWork.requireActiveToPersist("A rewrite of the customer master, which EXEC CICS "
                + "REWRITE issues against the record the preceding READ ... UPDATE still holds "
                + "(app/cbl/COACTUPC.cbl:L4085-L4091, after the locking read at L3921-L3930)",
                datasetName);

        String keyImage = keyImageOf(image);
        String keyPattern = asPrefixPattern(keyImage);
        String maskedKey = SensitiveDiagnostics.maskIdentifier(keyImage);

        int matching;
        try {
            matching = matchingRowCount(sql.selectByKeyForUpdate(), keyPattern);
        } catch (DataAccessException rejected) {
            return reportWrite(rejected, "establish how many rows the key of a record selects in the "
                    + "customer master dataset '" + datasetName + "' before rewriting it");
        }
        if (matching == 0) {
            return WriteResult.notFound();
        }
        if (matching > 1) {
            LOG.error("The key of customer " + maskedKey + " selects more than one row of dataset '"
                    + datasetName + "'; a KSDS primary key is unique - app/jcl/CUSTFILE.jcl:L50 declares "
                    + "KEYS(9 0) - so the rewrite is being refused before it is issued and file status "
                    + FileStatus.toStatusImage(PERMANENT_ERROR_STATUS) + " reported to the caller. No row "
                    + "has been changed.");
            return WriteResult.of(PERMANENT_ERROR_STATUS, CicsResponse.of(FileStatus.INVREQ));
        }

        int rewritten;
        try {
            PreparedStatementSetter binder = parameters -> {
                recordImageForm.bindImage(parameters, 1, image, codec.charset());
                recordImageForm.bindOperand(parameters, 2, keyPattern, codec.charset());
            };
            rewritten = jdbcTemplate.update(sql.rewrite(), binder);
        } catch (DataAccessException rejected) {
            return reportWrite(rejected, "rewrite a record in the customer master dataset '" + datasetName
                    + "'");
        }

        if (rewritten == 1) {
            return WriteResult.written();
        }
        if (rewritten == 0) {
            return WriteResult.notFound();
        }
        throw DatasetUnitOfWork.commitRefusal(
                "The rewrite of customer " + maskedKey + " in dataset '" + datasetName + "'",
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

    /**
     * Counts the rows a whole stored image selects, stopping as soon as the answer is known.
     *
     * <p>The same bounded question {@link #matchingRowCount(String, String)} answers - none, one, or more
     * than one - asked of a row rather than of a key. It is the probe {@link #rewriteHeld(String, byte[])}
     * uses, and it binds its operand as an <em>image</em> rather than as a pattern, because a held row is a
     * stored record and comparing it as a {@code LIKE} pattern would give any {@code _} or {@code %} byte
     * inside it a meaning the record never had.
     *
     * @param statement the whole-image select, carrying {@code FOR UPDATE}
     * @param image     the held row's stored image, exactly {@link #RECORD_LENGTH} bytes
     * @return {@code 0}, {@code 1}, or {@code 2} meaning "at least two"
     * @throws DataAccessException if the backend refuses the statement
     */
    private int matchingImageCount(String statement, byte[] image) {
        ResultSetExtractor<Integer> rowCounter = resultSet -> {
            int counted = 0;
            while (counted < FAN_OUT_PROBE_LIMIT && resultSet.next()) {
                counted++;
            }
            return counted;
        };
        PreparedStatementCreator creator = connection -> {
            PreparedStatement prepared = connection.prepareStatement(statement);
            prepared.setMaxRows(FAN_OUT_PROBE_LIMIT);
            recordImageForm.bindImage(prepared, 1, image, codec.charset());
            return prepared;
        };
        Integer counted = jdbcTemplate.query(creator, rowCounter);
        Objects.requireNonNull(counted, "The row counter returns a count, never null");
        return counted;
    }

    // =================================================================================================
    // The keyed read, shared by the batch and the online form and by the plain and the locking read.
    // =================================================================================================

    /**
     * Reads the single record whose key equals {@code keyImage}, reporting the outcome.
     *
     * <p>Shared by {@link #readByKey(long)}, {@link #readByKey(String)} and
     * {@link #readForUpdate(String)}, because the COBOL keyed read is one operation reached from two views
     * of the key and with or without a lock. The predicate matches a record image that <em>begins with</em>
     * the key, which for a fixed-width record whose leading field is the key is exactly "the record with
     * this key". Only one row is transferred: the row limit is set on the statement.
     *
     * @param keyImage  the stored key image, exactly {@link #KEY_LENGTH} characters
     * @param subject   how to name the key in a diagnostic, so a log line says which read failed
     * @param forUpdate whether to request the row lock a CICS {@code READ ... UPDATE} takes
     * @return the discriminated outcome; never {@code null}
     */
    private ReadResult readKeyed(String keyImage, String subject, boolean forUpdate) {
        Statements sql;
        try {
            sql = statements();
        } catch (DataAccessException unreachable) {
            return reportRead(unreachable, "describe the customer master dataset '" + datasetName
                    + "' to read " + subject);
        }
        return readKeyed(forUpdate ? sql.selectByKeyForUpdate() : sql.selectByKey(), keyImage, subject,
                sql.probeUnreadableRows());
    }

    private ReadResult readKeyed(String statement, String keyImage, String subject,
            String unreadableRowsProbe) {
        List<byte[]> rows;
        try {
            rows = jdbcTemplate.query(firstRowMatching(statement, asPrefixPattern(keyImage)),
                    recordImageMapper());
        } catch (DataAccessException translated) {
            return reportRead(translated, "read " + subject + " from the customer master dataset '"
                    + datasetName + "'");
        }

        if (rows.isEmpty()) {
            return provenAbsence(subject, unreadableRowsProbe);
        }
        byte[] recordImage = rows.get(0);
        if (recordImage == null) {
            LOG.error("The customer master dataset '" + datasetName + "' presented " + subject
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
            return reportRead(translated, "establish that the customer master dataset '" + datasetName
                    + "' holds no unreadable row before reporting " + subject + " as absent");
        }
        if (unreadable.isEmpty()) {
            return ReadResult.notFound();
        }
        LOG.error("A keyed read of " + subject + " from the customer master dataset '" + datasetName
                + "' matched no row, but the dataset holds a row with no record image at column position "
                + RECORD_IMAGE_COLUMN_INDEX + " - and CUST-ID is part of that image, so that row's key "
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

    /**
     * The statement set every keyed read and rewrite issues, describing the relation only if this
     * repository has not already learned its record-image column.
     *
     * <p>The first caller after startup or after an {@link #openInput()} pays the describe; every caller
     * after that reuses what it discovered, because the column name of a relation does not change between
     * two reads of it and asking again answers nothing. That is what makes an account screen's customer
     * read one keyed read rather than a keyed read and a metadata probe.
     *
     * <p>Nothing is stored when the describe fails, so a backend that was unreachable for one read is
     * described again by the next one rather than remembered as broken.
     *
     * @return the statement set for this relation's record-image column; never {@code null}
     * @throws DataAccessException if the backend refuses the describe, exactly as
     *     {@link #resolveStatements()} would - the callers translate it into a file status
     * @throws IllegalStateException if the backend presents the dataset with no usable record-image column
     */
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
     * Describes the relation and composes its statement set from the column that describe reported.
     *
     * <p>Always issues the describe: this is the primitive {@link #openInput()} uses to learn the file and
     * {@link #statementsFor(String)} uses to prove, at {@code CLOSE}, that the dataset is still
     * addressable. Reads and rewrites go through {@link #statements()} instead.
     *
     * @return the freshly composed statement set; never {@code null}
     * @throws DataAccessException if the backend refuses the describe
     * @throws IllegalStateException if the backend presents the dataset with no usable record-image column
     */
    Statements resolveStatements() {
        ResultSetExtractor<String> columnNameExtractor =
                CustomerRepository::extractRecordImageColumnName;
        String columnName = jdbcTemplate.query(relation.describeStatement(), columnNameExtractor);
        return Statements.over(relation, requireUsableColumnName(columnName));
    }

    /**
     * Describes the relation on behalf of an operation that exists to find out whether it can be described
     * at all, reporting the failure rather than propagating it.
     *
     * <p>Deliberately {@link #resolveStatements()} and not {@link #statements()}: its one caller is
     * {@code CLOSE}, whose whole question is whether the dataset is still addressable <em>now</em>. A
     * remembered answer would report a dataset that has since gone away as closing cleanly.
     *
     * @param subject how to name the operation in the log line, so an operator can tell which one failed
     * @return the freshly composed statement set, or {@code null} when the backend refused the describe -
     *     in which case the refusal has already been logged
     */
    private Statements statementsFor(String subject) {
        try {
            return resolveStatements();
        } catch (DataAccessException unreachable) {
            logRefusal(unreachable, "describe the customer master dataset '" + datasetName + "' for "
                    + subject);
            return null;
        }
    }

    private void requireUnitOfWork() {
        DatasetUnitOfWork.requireActive("A read for update of the customer master, which "
                + "EXEC CICS READ ... UPDATE holds for the unit of work "
                + "(app/cbl/COACTUPC.cbl:L3921-L3930, and the 'Could we lock the customer record ?' test "
                + "at L3932) so that 9700-CHECK-CHANGE-IN-REC can compare and REWRITE can run - or use "
                + "readByKey(String) when no lock is wanted", datasetName);
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
            LOG.error("The customer master dataset '" + datasetName + "' presented " + subject + " as "
                    + recordImage.length + " byte(s), but CUSTOMER-RECORD is declared " + RECORD_LENGTH
                    + " bytes by app/cpy/CVCUS01Y.cpy; reporting file status "
                    + FileStatus.toStatusImage(PERMANENT_ERROR_STATUS) + " rather than decoding fields from "
                    + "offsets that would not be theirs");
            return ReadResult.of(PERMANENT_ERROR_STATUS, CicsResponse.of(FileStatus.LENGERR));
        }
        return ReadResult.found(CustomerRecord.decode(recordImage, codec),
                codec.decodeImage(recordImage, RECORD_IMAGE_SUBJECT));
    }

    private String keyImageOf(long custId) {
        return codec.movePic9(custId, KEY_LENGTH);
    }

    private String keyImageOf(byte[] recordImage) {
        return FixedWidthRecord.decodeStrictly(recordImage, KEY_SPAN.offset(), KEY_SPAN.length(),
                codec.charset(), "the CUST-ID key of a customer record image");
    }

    private static String asPrefixPattern(String keyImage) {
        return KEY_SPAN.pattern(keyImage);
    }

    private static DatasetBinding requireCustomerGeometry(DatasetBindings datasetBindings, String ddName) {
        DatasetBinding binding = datasetBindings.binding(ddName);
        if (binding.recordLength() != RECORD_LENGTH) {
            throw new IllegalStateException("Dataset binding for DD name '" + ddName + "' declares a "
                    + "record length of " + binding.recordLength() + ", but the customer record is "
                    + RECORD_LENGTH + " bytes - app/cpy/CVCUS01Y.cpy:L2 reads RECLN 500, "
                    + "app/cbl/CBCUS01C.cbl:L37-L40 declares FD-CUST-ID PIC 9(09) plus FD-CUST-DATA "
                    + "PIC X(491) independently, and app/jcl/CUSTFILE.jcl:L51 defines the cluster "
                    + "RECORDSIZE(500 500). This repository addresses the record by absolute offset, so a "
                    + "differently-sized record would misplace every field after the first. Correct "
                    + "carddemo.datasets." + ddName + ".record-length to " + RECORD_LENGTH + ".");
        }
        Integer declaredKeyLength = binding.keyLength();
        // The startup validator requires a keyed entry to declare a key width, so an absent one here means
        // the binding was built by hand and is accepted; a PRESENT one that disagrees with the copybook is
        // a defect, because a keyed read would then address a span that is not the key.
        if (declaredKeyLength != null && declaredKeyLength != KEY_LENGTH) {
            throw new IllegalStateException("Dataset binding for DD name '" + ddName + "' declares a key "
                    + "length of " + declaredKeyLength + ", but the customer master key is " + KEY_LENGTH
                    + " bytes - CUST-ID PIC 9(09) at app/cpy/CVCUS01Y.cpy:L5, KEYS(9 0) at "
                    + "app/jcl/CUSTFILE.jcl:L50, and the value app/cbl/COACTUPC.cbl:L3925 passes as "
                    + "KEYLENGTH. Correct carddemo.datasets." + ddName + ".key-length to " + KEY_LENGTH
                    + ", or omit it.");
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
                    + "' and '" + BATCH_DD_NAME + "' name different datasets, but they are two names for "
                    + "one customer master: app/csd/CARDDEMO.CSD:L50-L52 defines the CICS file and "
                    + "app/jcl/READCUST.jcl:L9-L10 binds the batch DD over the same dataset. Point "
                    + "carddemo.datasets." + CICS_FILE_NAME + ".dsname and carddemo.datasets."
                    + BATCH_DD_NAME + ".dsname at the same dataset.");
        }
    }

    private static String requireUsableDatasetName(String candidate) {
        if (candidate == null || candidate.isBlank()) {
            throw new IllegalStateException("The dataset binding for DD name '" + CICS_FILE_NAME
                    + "' declares no dataset name. Set carddemo.datasets." + CICS_FILE_NAME + ".dsname; "
                    + "this repository composes its statements from configuration alone and hard-codes no "
                    + "dataset name.");
        }
        return DatasetRelation.requireDatasetName(candidate);
    }

    private static String requireUsableColumnName(String candidate) {
        if (candidate == null || candidate.isBlank()) {
            throw new IllegalStateException("The backend describes no column at position "
                    + RECORD_IMAGE_COLUMN_INDEX + " for the customer master dataset, so the record image "
                    + "cannot be addressed. Every CardDemo dataset is reached as a relation whose column at "
                    + "that position holds the whole fixed-width record image; a driver or a seeded dataset "
                    + "that presents no such column cannot be read or written by this module at all.");
        }
        requireNoControlCharacter(candidate, "The record-image column name reported by the backend");
        return candidate;
    }

    private static void requireNoControlCharacter(String candidate, String subject) {
        for (int index = 0; index < candidate.length(); index++) {
            if (Character.isISOControl(candidate.charAt(index))) {
                throw new IllegalStateException(subject + " contains a control character at position "
                        + index + "; an identifier cannot contain one, and it would corrupt the composed "
                        + "statements. Correct the value.");
            }
        }
    }

    private static String requireKeyImage(String candidate) {
        Objects.requireNonNull(candidate, "A key image is required to read a customer record; the RIDFLD is "
                + "a PIC X(09) field and is never absent, only spaces");
        if (candidate.length() != KEY_LENGTH) {
            throw new IllegalArgumentException("The customer master key is declared PIC X(0" + KEY_LENGTH
                    + ") but was given " + candidate.length() + " character(s). A fixed-width key carries "
                    + "its padding, so it is never trimmed and never short - render a customer identifier "
                    + "with CustomerRecord.custIdImage(Charset), or pass the identifier itself to "
                    + "readByKey(long).");
        }
        return candidate;
    }

    private static byte[] requireRecordImage(byte[] candidate) {
        Objects.requireNonNull(candidate, "A record image is required to rewrite a customer record; a COBOL "
                + "REWRITE writes the record area, and there is no such thing as rewriting nothing");
        if (candidate.length != RECORD_LENGTH) {
            throw new IllegalArgumentException("A customer record image is exactly " + RECORD_LENGTH
                    + " bytes - app/cpy/CVCUS01Y.cpy:L2 reads RECLN 500 and app/jcl/CUSTFILE.jcl:L51 defines "
                    + "the cluster RECORDSIZE(500 500) - but " + candidate.length + " byte(s) were given. "
                    + "Every declared span is written including FILLER X(168), so an image of another width "
                    + "is not this record; build it with CustomerRecord.encode(Charset), or with the same "
                    + "layout COACTUPC's CUST-UPDATE-RECORD declares.");
        }
        return candidate.clone();
    }

    /**
     * Validates the stored image of the row a held rewrite is to replace, and encodes it for the predicate.
     *
     * <p>It is the {@link ReadResult#requireStoredImage()} of the read that took the lock: exactly
     * {@link #RECORD_LENGTH} characters, as the backend presented them. Encoding it in this repository's
     * own code page is what makes the comparison with the stored column meaningful - the read decoded the
     * row in that code page, so re-encoding it there yields the bytes the row holds.
     *
     * @param candidate the held row's stored image
     * @return the encoded image, exactly {@link #RECORD_LENGTH} bytes
     * @throws NullPointerException     if {@code candidate} is {@code null}
     * @throws IllegalArgumentException if it is not exactly {@link #RECORD_LENGTH} characters, or if a
     *                                  character it holds has no representation in the dataset's code page
     */
    private byte[] requireHeldImage(String candidate) {
        Objects.requireNonNull(candidate, "The stored image of the held row is required to rewrite it: "
                + "EXEC CICS REWRITE carries no RIDFLD (app/cbl/COACTUPC.cbl:L4085-L4091), so the row it "
                + "replaces is named by the READ ... UPDATE that holds it and by nothing else");
        if (candidate.length() != RECORD_LENGTH) {
            throw new IllegalArgumentException("The held row's stored image is exactly " + RECORD_LENGTH
                    + " character(s) - app/cpy/CVCUS01Y.cpy:L2 reads RECLN 500 - but " + candidate.length()
                    + " were given. Pass CustomerRepository.ReadResult.requireStoredImage() of the read "
                    + "that took the lock, unmodified.");
        }
        return codec.encodeImage(candidate, "the stored image of the held customer record");
    }

    // =================================================================================================
    // The composed statements.
    // =================================================================================================

    /**
     * The seven statements this repository issues, composed once the record-image column is known.
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
                      String selectByImageForUpdate,
                      String rewriteByImage,
                      String probeUnreadableRows) {
        static Statements over(DatasetRelation relation, String column) {
            String recordImageColumn = relation.rememberRecordImageColumn(column);
            // The two whole-image statements are composed here because DatasetRelation offers no
            // image-addressed builders - CUSTDAT is the only dataset whose rewrite has to name a held row
            // rather than a key, because it is the only one whose lock key and record-area key have
            // different provenance (app/cbl/COACTUPC.cbl:L3919 against L4009). They are built from
            // DatasetRelation.identifier() and DatasetRelation.delimit(String) - the same identifier and
            // the same quoting every other statement here uses - so a held rewrite cannot quote a name
            // differently from the read that found the row.
            String wholeImagePredicate = " WHERE " + DatasetRelation.delimit(recordImageColumn) + " = ?";
            return new Statements(
                    relation.selectAllAscending(recordImageColumn),
                    relation.selectAfterAscending(recordImageColumn),
                    relation.selectByKey(recordImageColumn),
                    relation.selectByKeyForUpdate(recordImageColumn),
                    relation.rewriteByKey(recordImageColumn),
                    "SELECT * FROM " + relation.identifier() + wholeImagePredicate + " FOR UPDATE",
                    "UPDATE " + relation.identifier() + " SET "
                            + DatasetRelation.delimit(recordImageColumn) + " = ?" + wholeImagePredicate,
                    relation.selectUnreadableRows(recordImageColumn));
        }
    }

    String columnProbeSql() {
        return relation.describeStatement();
    }

    /**
     * One opened customer master file: the Java form of the {@code FD} a program opens, reads and closes.
     *
     * <p>{@code app/cbl/CBTRN01C.cbl} uses the same handle for less: it opens at {@code :273}, never reads,
     * and closes at {@code :381}.
     */
    public static final class CustomerFile implements AutoCloseable {
        private final CustomerRepository repository;

        private final String openStatus;

        private final Statements statements;

        private byte[] browsePosition;

        private boolean closed;

        private String closeStatus;

        private CustomerFile(CustomerRepository repository, String openStatus, Statements statements) {
            this.repository = repository;
            this.openStatus = openStatus;
            this.statements = statements;
            this.browsePosition = null;
            this.closed = false;
            this.closeStatus = null;
        }

        /**
         * The status the {@code OPEN} reported: the value {@code app/cbl/CBCUS01C.cbl:L121} tests before
         * moving {@code 0} or {@code 12} into {@code APPL-RESULT}, and the value
         * {@code app/cbl/CBTRN01C.cbl:L274} tests for the open it never reads from.
         *
         * @return {@link FileStatus#OK} or {@link CustomerRepository#PERMANENT_ERROR_STATUS}; never
         *     {@code null}, always two characters
         */
        public String openStatus() {
            return openStatus;
        }

        public Outcome openOutcome() {
            return FileStatus.outcomeOfStatus(openStatus);
        }

        /**
         * The {@code APPL-RESULT} value the open's guard moves: {@code 0} on success and {@code 12}
         * otherwise.
         *
         * @return {@link FileStatus#APPL_AOK} or {@link CustomerRepository#APPL_RESULT_FATAL}
         */
        public int openApplResult() {
            return FileStatus.isOk(openStatus) ? FileStatus.APPL_AOK : APPL_RESULT_FATAL;
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
         * {@code READ CUSTFILE-FILE INTO CUSTOMER-RECORD} ({@code app/cbl/CBCUS01C.cbl:L93}) and of the
         * guard that classifies its status at {@code L94-L103}.
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
                LOG.error("Rejected browse read of the customer master dataset '" + datasetName() + "' - "
                        + BackendDiagnostic.of(translated).describe() + "; reporting file status "
                        + FileStatus.toStatusImage(PERMANENT_ERROR_STATUS) + " to the caller");
                return ReadResult.of(PERMANENT_ERROR_STATUS);
            }

            if (rows.isEmpty()) {
                return ReadResult.endOfFile();
            }
            byte[] recordImage = rows.get(0);
            if (recordImage == null) {
                LOG.error("The customer master dataset '" + datasetName() + "' presented a row with no "
                        + "record image at column position " + RECORD_IMAGE_COLUMN_INDEX + "; reporting file "
                        + "status " + FileStatus.toStatusImage(PERMANENT_ERROR_STATUS) + " to the caller");
                return ReadResult.of(PERMANENT_ERROR_STATUS, CicsResponse.of(FileStatus.INVREQ));
            }

            this.browsePosition = recordImage.clone();
            return repository.decoded(recordImage, "the row at the current browse position");
        }

        /**
         * Reads one record by customer identifier under this open:
         * {@link CustomerRepository#readByKey(long)} against the shape this {@code OPEN} already resolved.
         *
         * <p>The browse position is untouched, exactly as a COBOL random {@code READ} leaves the position
         * of a sequential browse of the same file alone.
         *
         * @param custId the customer identifier; must not be negative
         * @return the discriminated outcome; never {@code null}
         * @throws IllegalStateException if this handle has been closed
         * @throws IllegalArgumentException if {@code custId} is negative
         */
        public ReadResult readByKey(long custId) {
            requireOpen("read a record by key");
            if (statements == null) {
                return ReadResult.of(openStatus);
            }
            return repository.readKeyed(statements.selectByKey(), repository.keyImageOf(custId),
                    "the customer identifier "
                            + SensitiveDiagnostics.maskIdentifier(custId, KEY_LENGTH),
                    statements.probeUnreadableRows());
        }

        /**
         * Reads one record by its nine-character key image under this open:
         * {@link CustomerRepository#readByKey(String)} against the shape this {@code OPEN} already
         * resolved.
         *
         * @param custIdAsChar9 the key exactly as {@code LK-M03B-KEY} holds it: exactly
         *     {@link CustomerRepository#KEY_LENGTH} characters, untrimmed and unparsed
         * @return the discriminated outcome; never {@code null}
         * @throws NullPointerException if {@code custIdAsChar9} is {@code null}
         * @throws IllegalArgumentException if {@code custIdAsChar9} is not exactly
         *     {@link CustomerRepository#KEY_LENGTH} characters
         * @throws IllegalStateException if this handle has been closed
         */
        public ReadResult readByKey(String custIdAsChar9) {
            String keyImage = requireKeyImage(custIdAsChar9);
            requireOpen("read a record by key");
            if (statements == null) {
                return ReadResult.of(openStatus);
            }
            return repository.readKeyed(statements.selectByKey(), keyImage,
                    "the record identification field '" + SensitiveDiagnostics.maskIdentifier(keyImage)
                            + "'", statements.probeUnreadableRows());
        }

        /**
         * Closes this file, reporting only the resulting file status.
         *
         * @return {@link FileStatus#OK} when the dataset is still addressable, otherwise
         *     {@link CustomerRepository#PERMANENT_ERROR_STATUS} - or, for a handle whose open failed, that
         *     open's own status
         * @throws IllegalStateException if the backend presents the dataset with no usable record-image
         *     column
         */
        public String closeFile() {
            if (closed) {
                return closeStatus;
            }
            closed = true;
            this.browsePosition = null;
            if (statements == null) {
                closeStatus = openStatus;
            } else {
                closeStatus = repository.statementsFor("a close of the customer master") == null
                        ? PERMANENT_ERROR_STATUS
                        : FileStatus.OK;
            }
            return closeStatus;
        }

        /**
         * The {@code APPL-RESULT} value the close's guard moves: {@code 0} on success and {@code 12}
         * otherwise.
         *
         * <p>Closes the file if it is still open, so a caller can use this in place of {@link #closeFile()}
         * when the number is what it needs; on an already-closed handle it reports the same outcome that
         * close reported, so the status and the {@code APPL-RESULT} can never disagree.
         *
         * @return {@link FileStatus#APPL_AOK} or {@link CustomerRepository#APPL_RESULT_FATAL}
         * @throws IllegalStateException if the backend presents the dataset with no usable record-image
         *     column
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
                throw new IllegalStateException("This open of the customer master dataset '" + datasetName()
                        + "' has been closed, so it cannot " + operation + ". A COBOL program closes once, "
                        + "after its work - operating afterwards is a defect in the caller, not a file "
                        + "status. Open another file instead.");
            }
        }
    }

    /**
     * The outcome of one read of the customer master: the classification the COBOL guard chain performs,
     * and nothing beyond it.
     *
     * @param status the two-character file status the read reported, verbatim
     * @param outcome its classification
     * @param customer the decoded record, present exactly when {@code outcome} is {@link Outcome#OK}
     * @param storedImage the row's stored fixed-width record image, exactly as the dataset holds it
     * @param diagnostic what the backend reported when it refused, present only on a failure it described -
     *     so a caller can log the driver's own {@code SQLSTATE} instead of a status this module synthesised
     * @param response the CICS {@code RESP}/{@code RESP2} pair for this outcome, carried rather than
     *     derived on demand
     */
    public record ReadResult(String status,
                             Outcome outcome,
                             Optional<CustomerRecord> customer,
                             Optional<String> storedImage,
                             Optional<BackendDiagnostic> diagnostic,
                             CicsResponse response) {
        public ReadResult {
            requireConsistentStatus(status, outcome);
            Objects.requireNonNull(customer, "A read result carries an empty record rather than a null one, "
                    + "so no null escapes the type");
            Objects.requireNonNull(storedImage, "A read result carries an empty stored image rather than a "
                    + "null one, so no null escapes the type");
            Objects.requireNonNull(diagnostic, "A read result carries an empty diagnostic rather than a null "
                    + "one, so no null escapes the type");
            Objects.requireNonNull(response, "A read result carries a CICS response pair rather than a null "
                    + "one; use CicsResponse.ofBatchStatus(status) where the outcome is a file status and "
                    + "CicsResponse.reported(resp, resp2) where a backend surfaced both");
            if (customer.isPresent() != (outcome == Outcome.OK)) {
                throw new IllegalArgumentException(customer.isPresent()
                        ? "A read that did not succeed carries no record: outcome " + outcome
                                + " was given one. Only the '00' arm reaches CUSTOMER-RECORD."
                        : "A successful read carries the decoded record, and this one carries none; build it "
                                + "with ReadResult.found(CustomerRecord, String).");
            }
            // That is what makes DISPLAY CUSTOMER-RECORD reproducible: a caller on the successful arm can
            // always reach the row's own bytes and never has to render the decoded record, which allocates
            // a fresh area and so blanks the trailing FILLER whatever the row held.
            if (storedImage.isPresent() != customer.isPresent()) {
                throw new IllegalArgumentException(storedImage.isPresent()
                        ? "A read that did not succeed carries no record, so it carries no stored image "
                                + "either: outcome " + outcome + " was given one."
                        : "A successful read carries the stored image the record was decoded from, and "
                                + "this one carries none; build it with "
                                + "ReadResult.found(CustomerRecord, String).");
            }
            storedImage.ifPresent(image -> {
                if (image.length() != CustomerRecord.RECORD_LENGTH) {
                    throw new IllegalArgumentException("A stored CUSTOMER-RECORD image is "
                            + CustomerRecord.RECORD_LENGTH + " characters as app/cpy/CVCUS01Y.cpy "
                            + "declares, but this one is " + image.length()
                            + ". DISPLAY CUSTOMER-RECORD writes the whole record area, so an image of any "
                            + "other width would emit a line the program cannot produce.");
                }
            });
        }

        /**
         * The successful arm: status {@code '00'}, carrying the decoded record.
         *
         * @param customer the decoded record
         * @param storedImage the row's own characters, exactly {@value CustomerRecord#RECORD_LENGTH} of
         *     them
         * @return a result carrying {@link FileStatus#OK}, the record and its stored image
         * @throws NullPointerException if either argument is {@code null}
         * @throws IllegalArgumentException if {@code storedImage} is not
         *     {@value CustomerRecord#RECORD_LENGTH} characters
         */
        public static ReadResult found(CustomerRecord customer, String storedImage) {
            Objects.requireNonNull(customer, "A successful read carries the decoded customer record");
            Objects.requireNonNull(storedImage, "A successful read carries the stored image the record "
                    + "was decoded from, so DISPLAY CUSTOMER-RECORD can write the row's own bytes");
            return new ReadResult(FileStatus.OK, Outcome.OK, Optional.of(customer),
                    Optional.of(storedImage), Optional.empty(), CicsResponse.ofBatchStatus(FileStatus.OK));
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
         *     render it exactly as {@code Z-DISPLAY-IO-STATUS} does
         * @return a result carrying {@code status}, its classification, and no record
         * @throws NullPointerException if {@code status} is {@code null}
         * @throws IllegalArgumentException if {@code status} is not exactly
         *     {@link FileStatus#STATUS_LENGTH} characters, or is {@link FileStatus#OK}
         */
        public static ReadResult of(String status) {
            return new ReadResult(status, classify(status), Optional.empty(), Optional.empty(),
                    Optional.empty(),
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
            return new ReadResult(status, classify(status), Optional.empty(), Optional.empty(),
                    Optional.empty(), response);
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
        public static ReadResult of(String status, CicsResponse response, BackendDiagnostic diagnostic) {
            Objects.requireNonNull(diagnostic, "A diagnostic is required by this factory; use of(String) "
                    + "where there is no backend refusal to report");
            return new ReadResult(status, classify(status), Optional.empty(), Optional.empty(),
                    Optional.of(diagnostic),
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
         * The row's own bytes as characters, for a caller already on the successful arm.
         *
         * @return exactly {@value CustomerRepository#RECORD_LENGTH} characters
         * @throws IllegalStateException if this arm carries no record, and so no image either
         */
        public String requireStoredImage() {
            return storedImage.orElseThrow(() -> new IllegalStateException("A read that reported file "
                    + "status " + FileStatus.toStatusImage(status) + " carries no record, so it carries "
                    + "no stored image. DISPLAY CUSTOMER-RECORD is reached only on the '" + FileStatus.OK
                    + "' arm - app/cbl/CBCUS01C.cbl:94 tests the status before it displays - so branch on "
                    + "the outcome first."));
        }

        /**
         * Whether the browse reached the end of the dataset: the {@code IF APPL-EOF} test
         * ({@code app/cbl/CBCUS01C.cbl:L107}).
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
         * Whether the backend reported a duplicate key: status {@code '22'}.
         *
         * @return {@code true} for the {@code '22'} arm
         */
        public boolean isDuplicate() {
            return outcome == Outcome.DUPLICATE;
        }

        /**
         * Whether the read failed for any other reason: the {@code WHEN OTHER} arm, which every guard chain
         * over this dataset ends in an abend ({@code app/cbl/CBCUS01C.cbl:L109-L114}).
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
         * The four-character status image the COBOL renders, for a caller composing its own diagnostic
         * line.
         *
         * @return exactly {@link FileStatus#STATUS_IMAGE_LENGTH} characters
         */
        public String statusImage() {
            return FileStatus.toStatusImage(status);
        }

        /**
         * The {@code APPL-RESULT} value the COBOL guard moves for this outcome.
         *
         * <p>{@code 0} on success, {@code 16} at end of file, and {@code 12} for everything else - exactly
         * the ladder at {@code app/cbl/CBCUS01C.cbl:L94-L103}.
         *
         * @return {@link FileStatus#APPL_AOK}, {@link FileStatus#APPL_EOF} or
         *     {@link CustomerRepository#APPL_RESULT_FATAL}
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
     * The outcome of one rewrite of the customer master: the classification {@code 9600-WRITE-PROCESSING}'s
     * guard performs ({@code app/cbl/COACTUPC.cbl:L4095-L4103}), and nothing beyond it.
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
            Objects.requireNonNull(response, "A write result carries a CICS response pair rather than a null "
                    + "one; use CicsResponse.ofBatchStatus(status) where the outcome is a file status and "
                    + "CicsResponse.reported(resp, resp2) where a backend surfaced both");
            if (outcome == Outcome.END_OF_FILE) {
                throw new IllegalArgumentException("A rewrite cannot reach the end of a dataset, so status '"
                        + FileStatus.END_OF_FILE + "' is not an outcome it can report.");
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
        public static WriteResult of(String status, CicsResponse response, BackendDiagnostic diagnostic) {
            Objects.requireNonNull(diagnostic, "A diagnostic is required by this factory; use of(String) "
                    + "where there is no backend refusal to report");
            return new WriteResult(status, classify(status), Optional.of(diagnostic), response);
        }

        /**
         * Whether the rewrite succeeded: {@code IF WS-RESP-CD EQUAL TO DFHRESP(NORMAL)}
         * ({@code app/cbl/COACTUPC.cbl:L4095}).
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
         * Whether the backend reported a duplicate key: status {@code '22'}.
         *
         * @return {@code true} for the {@code '22'} arm
         */
        public boolean isDuplicate() {
            return outcome == Outcome.DUPLICATE;
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
         * The four-character status image the COBOL renders, for a caller composing its own diagnostic
         * line.
         *
         * @return exactly {@link FileStatus#STATUS_IMAGE_LENGTH} characters
         */
        public String statusImage() {
            return FileStatus.toStatusImage(status);
        }

        /**
         * The {@code APPL-RESULT} value the COBOL guard moves for this outcome: {@code 0} on success and
         * {@code 12} otherwise.
         *
         * @return {@link FileStatus#APPL_AOK} or {@link CustomerRepository#APPL_RESULT_FATAL}
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
                    + "' classifies as " + classified + ", but " + outcome + " was given; a status and its "
                    + "classification must agree, so that no caller can branch on one and read the other. "
                    + "Use one of the named factory methods.");
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
                    + " characters, as CUSTFILE-STATUS is declared at app/cbl/CBCUS01C.cbl:L46-L48; got "
                    + status.length() + ".");
        }
        return status;
    }
}
