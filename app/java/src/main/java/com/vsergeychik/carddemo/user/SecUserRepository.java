package com.vsergeychik.carddemo.user;

import com.vsergeychik.carddemo.common.CicsResponse;
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
import com.vsergeychik.carddemo.common.DatasetUnitOfWork;
import com.vsergeychik.carddemo.user.model.SecUserRecord;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.core.PreparedStatementSetter;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.stereotype.Repository;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * The {@code USRSEC} security-user file, reached over JDBC as a fixed-width record-image relation.
 *
 * <p>Two kinds of state that a naive translation would put here are deliberately handed to the caller
 * instead, because CICS scopes both of them to the task and not to the file: browse position.
 */
@Repository
public class SecUserRepository {
    private static final Log LOG = LogFactory.getLog(SecUserRepository.class);

    /**
     * The CICS file name, which is also the {@code carddemo.datasets} binding key.
     */
    public static final String CICS_FILE_NAME = "USRSEC";

    /**
     * The declared width of {@code WS-USRSEC-FILE PIC X(8)}.
     */
    public static final int CICS_FILE_NAME_LENGTH = 8;

    /**
     * The file name at its declared {@value #CICS_FILE_NAME_LENGTH}-character width, space-padded.
     */
    public static final String CICS_FILE_NAME_IMAGE = "USRSEC  ";

    /**
     * The record width, {@link #RECORD_LENGTH} bytes, taken from the record type and not restated.
     *
     * <p>Sourced from {@link SecUserRecord#RECORD_LENGTH} so the copybook has exactly one Java
     * representation.
     */
    public static final int RECORD_LENGTH = SecUserRecord.RECORD_LENGTH;

    /**
     * The key width, {@link #KEY_LENGTH} bytes, derived rather than hard-coded.
     */
    public static final int KEY_LENGTH = SecUserRecord.KEY_LENGTH;

    /**
     * The key's offset in the record, {@link #KEY_OFFSET}: {@code SEC-USR-ID} is the leading field.
     */
    public static final int KEY_OFFSET = SecUserRecord.KEY_OFFSET;

    public static final int RECORD_IMAGE_COLUMN_INDEX = DatasetRelation.RECORD_IMAGE_COLUMN_INDEX;

    private static final byte LOW_VALUES_BYTE = (byte) 0x00;

    private static final byte HIGH_VALUES_BYTE = (byte) 0xFF;

    private static byte[] figurativeKeyBytes(byte value) {
        byte[] image = new byte[KEY_LENGTH];
        Arrays.fill(image, value);
        return image;
    }

    /**
     * The key image meaning "before every possible key": COBOL {@code LOW-VALUES} at {@link #KEY_LENGTH}
     * characters.
     */
    public static final String LOW_VALUES_KEY =
            new String(figurativeKeyBytes(LOW_VALUES_BYTE), StandardCharsets.ISO_8859_1);

    /**
     * The key image meaning "after every possible key": COBOL {@code HIGH-VALUES} at {@link #KEY_LENGTH}
     * characters.
     */
    public static final String HIGH_VALUES_KEY =
            new String(figurativeKeyBytes(HIGH_VALUES_BYTE), StandardCharsets.ISO_8859_1);

    /**
     * The {@code RESP2} value reported when the condition has no reason code, which is every condition this
     * class raises for itself.
     */
    public static final int CICS_RESP2_NOT_APPLICABLE = FileStatus.NO_REASON_CODE;

    private static final char PERMANENT_ERROR_FEEDBACK_CODE = 0;

    public static final String PERMANENT_ERROR_STATUS = "9" + PERMANENT_ERROR_FEEDBACK_CODE;

    private static final KeySpan KEY_SPAN = new KeySpan(KEY_OFFSET, KEY_LENGTH);

    private static final int SINGLE_ROW = 1;

    private static final int BROWSE_SCAN_FETCH_SIZE = 32;

    private static final int FAN_OUT_PROBE_LIMIT = 2;

    private final JdbcTemplate jdbcTemplate;

    private final FixedWidthCodec codec;

    private final DatasetRelation relation;

    private final String datasetName;

    private volatile Statements statements;

    private final RecordImageForm recordImageForm;

    /**
     * Wires the repository and refuses, at startup, any configuration that could not be this dataset.
     *
     * @param jdbcTemplate the module's shared template
     * @param datasetBindings the DD-name-keyed catalogue bound from {@code carddemo.datasets}
     * @param datasetCharset the active dataset code page, selected by bean name so the choice is explicit
     *     at the injection point
     * @param recordImageForm how this deployment's driver presents a record image
     * @throws NullPointerException if any argument is {@code null}
     * @throws IllegalStateException if {@link #CICS_FILE_NAME} is unconfigured, if its binding declares a
     *     record width other than {@link #RECORD_LENGTH}
     * @throws IllegalArgumentException if the configured dataset name is not a well-formed dataset name, or
     *     if the code page is not single-byte
     */
    public SecUserRepository(
            JdbcTemplate jdbcTemplate,
            DatasetBindings datasetBindings,
            @Qualifier(CobolCharsetConfig.DATASET_CHARSET_BEAN_NAME) Charset datasetCharset,
            RecordImageForm recordImageForm) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "A JdbcTemplate is required: the "
                + "security-user file is reached through the module's shared template");
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

        DatasetBinding binding = requireSecurityFileGeometry(datasetBindings);
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
     * The CICS file name at its declared {@code PIC X(8)} width, as the {@code DATASET} option carries it.
     *
     * @return {@link #CICS_FILE_NAME_IMAGE}
     */
    public String cicsFileName() {
        return CICS_FILE_NAME_IMAGE;
    }

    /**
     * The record width this repository reads and writes.
     *
     * @return {@link #RECORD_LENGTH}
     */
    public int recordLength() {
        return RECORD_LENGTH;
    }

    /**
     * The key width every operation here uses, derived from the copybook.
     *
     * @return {@link #KEY_LENGTH}
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
     * Reads one security-user record by key, without taking a lock: the sign-on read.
     *
     * @param userId the key exactly as the {@code RIDFLD} holds it: {@link #KEY_LENGTH} characters,
     *     space-padded, untrimmed and not upper-cased by this method
     * @return the discriminated outcome; never {@code null}
     * @throws NullPointerException if {@code userId} is {@code null}
     * @throws IllegalArgumentException if {@code userId} is not exactly {@link #KEY_LENGTH} characters
     * @throws IllegalStateException if the backend presents the dataset with no usable record-image column
     */
    public ReadResult read(String userId) {
        return readKeyed(requireKeyImage(userId), false);
    }

    /**
     * Reads one security-user record by key and holds it: the update and delete read.
     *
     * <p>The COBOL distinguishes the two commands, only this one may be followed by a {@code REWRITE} or a
     * {@code DELETE}, and this one alone requires a unit of work.
     *
     * @param userId the key exactly as the {@code RIDFLD} holds it: {@link #KEY_LENGTH} characters,
     *     space-padded and untrimmed
     * @return the discriminated outcome, carrying a {@link HeldRecord} when the record was found; never
     *     {@code null}
     * @throws NullPointerException if {@code userId} is {@code null}
     * @throws IllegalArgumentException if {@code userId} is not exactly {@link #KEY_LENGTH} characters
     * @throws IllegalStateException if no unit of work is open, or if the backend presents the dataset with
     *     no usable record-image column
     */
    public ReadResult readForUpdate(String userId) {
        String keyImage = requireKeyImage(userId);
        DatasetUnitOfWork.requireActive("A read for update of the security-user file, which "
                + "EXEC CICS READ ... UPDATE holds for the unit of work under UPDATEMODEL(LOCKING) "
                + "(app/cbl/COUSR02C.cbl:L322-L331 before the REWRITE at L360, and "
                + "app/cbl/COUSR03C.cbl:L269-L278 before the DELETE at L307) - or use read(String) when "
                + "no lock is wanted, as app/cbl/COSGN00C.cbl:L211 does", datasetName);
        return readKeyed(keyImage, true);
    }

    /**
     * Positions a browse of the security-user file at or after a key, and hands back the cursor.
     *
     * <p>The cursor accordingly offers {@link BrowseCursor#readNext()} and
     * {@link BrowseCursor#readPrevious()} over one position rather than being locked to a direction the
     * COBOL never declares.
     *
     * @param startKey the key to position at: {@link #KEY_LENGTH} characters, or {@link #LOW_VALUES_KEY} or
     *     {@link #HIGH_VALUES_KEY}
     * @return the cursor, open or not according to {@link BrowseCursor#openOutcome()}; never {@code null}
     * @throws NullPointerException if {@code startKey} is {@code null}
     * @throws IllegalArgumentException if {@code startKey} is not exactly {@link #KEY_LENGTH} characters
     */
    public BrowseCursor startBrowse(String startKey) {
        String anchorKey = requireKeyImage(startKey);

        Statements sql;
        try {
            sql = resolveStatements();
        } catch (DataAccessException unreachable) {
            return new BrowseCursor(this, null, anchorKey, PERMANENT_ERROR_STATUS,
                    CicsResponse.none(),
                    Optional.of(logRefusal(unreachable, "describe the security-user dataset '"
                            + datasetName + "' to position a browse")));
        }

        boolean positionable;
        try {
            positionable = anyRowFromKey(sql, anchorKey);
        } catch (DataAccessException refused) {
            return new BrowseCursor(this, null, anchorKey, PERMANENT_ERROR_STATUS, CicsResponse.none(),
                    Optional.of(logRefusal(refused, "position a browse of the security-user dataset '"
                            + datasetName + "'")));
        }
        if (!positionable) {
            return new BrowseCursor(this, null, anchorKey, FileStatus.NOT_FOUND,
                    CicsResponse.of(FileStatus.NOTFND), Optional.empty());
        }
        return new BrowseCursor(this, sql, anchorKey, FileStatus.OK,
                CicsResponse.of(FileStatus.NORMAL), Optional.empty());
    }

    /**
     * Adds one security-user record: the Java form of {@code app/cbl/COUSR01C.cbl:L240-L248}.
     *
     * <p>{@link FileStatus#DUPKEY} concerns a non-unique alternate index, and {@link #CICS_FILE_NAME} has
     * no alternate index defined anywhere in {@code app/csd/CARDDEMO.CSD} - so the backend cannot produce
     * it, while the outcome remains expressible because the program has a {@code WHEN} for it.
     *
     * @param record the record to add, complete and already assembled by the caller
     * @return the discriminated outcome; never {@code null}
     * @throws NullPointerException if {@code record} is {@code null}
     * @throws IllegalStateException if no unit of work is open, in which case nothing has been attempted,
     *     or if the backend presents the dataset with no usable record-image column
     */
    public WriteResult add(SecUserRecord record) {
        Objects.requireNonNull(record, "A record is required to add one; a COBOL WRITE writes the record "
                + "area, and there is no such thing as writing nothing");
        DatasetUnitOfWork.requireActiveToPersist("A write to the security-user file, which EXEC CICS WRITE "
                + "issues from the record area COUSR01C has just built (app/cbl/COUSR01C.cbl:L240-L248)",
                datasetName);

        Statements sql;
        try {
            sql = resolveStatements();
        } catch (DataAccessException unreachable) {
            return reportWrite(unreachable, "describe the security-user dataset '" + datasetName
                    + "' to add a record");
        }

        String keyImage = record.key();
        String keyPattern = KEY_SPAN.pattern(keyImage);
        try {
            if (matchingRowCount(sql.selectByKey(), keyPattern) > 0) {
                return WriteResult.duplicateRecord();
            }
        } catch (DataAccessException refused) {
            return reportWrite(refused, "establish whether a record already holds the key being added to "
                    + "the security-user dataset '" + datasetName + "'");
        }

        byte[] recordImage = SecUserRecord.encode(record, codec);
        int written;
        try {
            PreparedStatementSetter binder = parameters ->
                    recordImageForm.bindImage(parameters, 1, recordImage, codec.charset());
            written = jdbcTemplate.update(sql.insert(), binder);
        } catch (DataAccessException refused) {
            BackendDiagnostic diagnostic = BackendDiagnostic.of(refused);
            if (diagnostic.integrityViolation()) {
                LOG.warn("The security-user dataset '" + datasetName + "' refused an add with an "
                        + "integrity violation - " + diagnostic.describe() + "; reporting the "
                        + "duplicate-record response, which is what EXEC CICS WRITE raises for a key "
                        + "that is already present");
                return WriteResult.duplicateRecord(diagnostic);
            }
            return reportWrite(refused, "add a record to the security-user dataset '" + datasetName + "'");
        }

        if (written == SINGLE_ROW) {
            return WriteResult.written();
        }
        LOG.error("An add to the security-user dataset '" + datasetName + "' reported " + written
                + " row(s) written where exactly " + SINGLE_ROW + " was expected; reporting file status "
                + FileStatus.toStatusImage(PERMANENT_ERROR_STATUS) + " so the caller reaches its "
                + "WHEN OTHER arm rather than treating the write as done");
        return WriteResult.of(PERMANENT_ERROR_STATUS, CicsResponse.of(FileStatus.INVREQ));
    }

    /**
     * Rewrites the held security-user record in place: the Java form of
     * {@code app/cbl/COUSR02C.cbl:L360-L366}.
     *
     * @param record the record to write, complete and already mutated by the caller
     * @return the discriminated outcome; never {@code null}
     * @throws NullPointerException if {@code record} is {@code null}
     * @throws IllegalStateException if no unit of work is open, or if the backend presents the dataset with
     *     no usable record-image column
     */
    public WriteResult rewrite(SecUserRecord record) {
        Objects.requireNonNull(record, "A record is required to rewrite one; a COBOL REWRITE writes the "
                + "record area, and there is no such thing as rewriting nothing");
        DatasetUnitOfWork.requireActive("A rewrite of the security-user file, which EXEC CICS REWRITE "
                + "issues against the record the preceding READ ... UPDATE still holds "
                + "(app/cbl/COUSR02C.cbl:L360-L366, no RIDFLD, after the locking read at L322)",
                datasetName);

        Statements sql;
        try {
            sql = resolveStatements();
        } catch (DataAccessException unreachable) {
            return reportWrite(unreachable, "describe the security-user dataset '" + datasetName
                    + "' to rewrite a record");
        }
        return rewrite(sql, record);
    }

    /**
     * Deletes the record currently held for update - not a record named by a key.
     *
     * @param held the record handed back by a successful {@link #readForUpdate(String)}, standing for the
     *     record this task holds
     * @return the discriminated outcome; never {@code null}
     * @throws NullPointerException if {@code held} is {@code null}
     * @throws IllegalArgumentException if {@code held} was produced by a different repository instance,
     *     which would mean deleting from a dataset other than the one read
     * @throws IllegalStateException if no unit of work is open, or if the backend presents the dataset with
     *     no usable record-image column
     */
    public WriteResult deleteHeld(HeldRecord held) {
        Objects.requireNonNull(held, "A held record is required to delete one: EXEC CICS DELETE at "
                + "app/cbl/COUSR03C.cbl:L307-L311 carries no RIDFLD, so it deletes the record the "
                + "preceding READ ... UPDATE holds. Call readForUpdate(String) first and pass the hold "
                + "from its result");
        if (held.repository() != this) {
            throw new IllegalArgumentException("The held record was produced by a different "
                    + "SecUserRepository instance, which may address a different dataset than '"
                    + datasetName + "'. A held record belongs to the read that took it, so it is deleted "
                    + "through the same repository that read it");
        }
        DatasetUnitOfWork.requireActive("A delete of the security-user file, which EXEC CICS DELETE "
                + "issues against the record the preceding READ ... UPDATE still holds "
                + "(app/cbl/COUSR03C.cbl:L307-L311, no RIDFLD, after the locking read at L269)",
                datasetName);

        Statements sql;
        try {
            sql = resolveStatements();
        } catch (DataAccessException unreachable) {
            return reportWrite(unreachable, "describe the security-user dataset '" + datasetName
                    + "' to delete the held record");
        }
        return deleteHeld(sql, held);
    }

    /**
     * Rewrites the record a {@link #readForUpdate(String)} is holding - the form
     * {@link HeldRecord#rewrite(SecUserRecord)} reaches, and the one {@code COUSR02C} performs.
     *
     * <p>Package-private on purpose. The handle is the access surface: a caller that holds a
     * {@link HeldRecord} calls {@link HeldRecord#rewrite(SecUserRecord)} on it, which reads as what the
     * COBOL does - rewrite <em>this</em> record - and cannot be called without a hold at all. Exposing a
     * second public entry point taking the hold as an argument would add surface without adding a path.
     *
     * @param held    the record this task holds
     * @param updated the record to write, complete and already mutated by the caller
     * @return the discriminated outcome; never {@code null}
     * @throws NullPointerException     if either argument is {@code null}
     * @throws IllegalArgumentException if {@code held} was produced by a different repository instance
     * @throws IllegalStateException    if no unit of work is open, or if the backend presents the dataset
     *                                  with no usable record-image column
     */
    WriteResult rewriteHeld(HeldRecord held, SecUserRecord updated) {
        Objects.requireNonNull(held, "A held record is required to rewrite one: EXEC CICS REWRITE at "
                + "app/cbl/COUSR02C.cbl:L360-L366 carries no RIDFLD, so it replaces the record the "
                + "preceding READ ... UPDATE holds. Call readForUpdate(String) first and rewrite through "
                + "the hold from its result");
        Objects.requireNonNull(updated, "A record is required to rewrite one; a COBOL REWRITE writes the "
                + "record area, and there is no such thing as rewriting nothing");
        if (held.repository() != this) {
            throw new IllegalArgumentException("The held record was produced by a different "
                    + "SecUserRepository instance, which may address a different dataset than '"
                    + datasetName + "'. A held record belongs to the read that took it, so it is "
                    + "rewritten through the same repository that read it");
        }
        DatasetUnitOfWork.requireActive("A rewrite of the security-user file, which EXEC CICS REWRITE "
                + "issues against the record the preceding READ ... UPDATE still holds "
                + "(app/cbl/COUSR02C.cbl:L360-L366, no RIDFLD, after the locking read at L322)",
                datasetName);

        Statements sql;
        try {
            sql = resolveStatements();
        } catch (DataAccessException unreachable) {
            return reportWrite(unreachable, "describe the security-user dataset '" + datasetName
                    + "' to rewrite the held record");
        }
        return rewriteHeld(sql, held, updated);
    }

    // =================================================================================================
    // Shared operation bodies. Each is the single implementation of one CICS command, so the entry point
    // on this class and the one on a handle cannot drift apart.
    // =================================================================================================

    private ReadResult readKeyed(String keyImage, boolean forUpdate) {
        Statements sql;
        try {
            sql = resolveStatements();
        } catch (DataAccessException unreachable) {
            return reportRead(unreachable, "describe the security-user dataset '" + datasetName
                    + "' to read a record by key");
        }

        KeyedMatch match;
        try {
            match = rowMatching(forUpdate ? sql.selectByKeyForUpdate() : sql.selectByKey(),
                    KEY_SPAN.pattern(keyImage));
        } catch (DataAccessException refused) {
            return reportRead(refused, "read a record by key from the security-user dataset '"
                    + datasetName + "'");
        }

        if (match.matched() == 0) {
            // Reported, never thrown - COSGN00C's WHEN 13 arm and COUSR02C's and COUSR03C's WHEN
            // DFHRESP(NOTFND) arms all paint a message.
            return provenAbsence(sql.probeUnreadableRows());
        }
        if (match.matched() > SINGLE_ROW) {
            return fanOutReadRefused();
        }
        if (match.firstImage() == null) {
            return unreadableRow("a record read by key");
        }
        return decoded(match.firstImage(), "a record read by key", forUpdate);
    }

    private WriteResult rewrite(Statements sql, SecUserRecord record) {
        byte[] recordImage = SecUserRecord.encode(record, codec);
        String keyPattern = KEY_SPAN.pattern(record.key());

        int matching;
        try {
            matching = matchingRowCount(sql.selectByKeyForUpdate(), keyPattern);
        } catch (DataAccessException refused) {
            return reportWrite(refused, "establish how many rows the key of a record selects in the "
                    + "security-user dataset '" + datasetName + "' before rewriting it");
        }
        if (matching == 0) {
            return WriteResult.notFound();
        }
        if (matching > SINGLE_ROW) {
            return fanOutRefused("rewrite");
        }

        int rewritten;
        try {
            PreparedStatementSetter binder = parameters -> {
                recordImageForm.bindImage(parameters, 1, recordImage, codec.charset());
                recordImageForm.bindOperand(parameters, 2, keyPattern, codec.charset());
            };
            rewritten = jdbcTemplate.update(sql.rewrite(), binder);
        } catch (DataAccessException refused) {
            return reportWrite(refused, "rewrite a record in the security-user dataset '" + datasetName
                    + "'");
        }

        if (rewritten == SINGLE_ROW) {
            return WriteResult.written();
        }
        if (rewritten == 0) {
            return WriteResult.notFound();
        }
        return unexpectedRowCount("rewrite", rewritten);
    }

    /**
     * Rewrites the held record against already-resolved statements.
     *
     * <p>The predicate is the held record's <strong>whole stored image</strong>, not its key - the same
     * addressing {@link #deleteHeld(Statements, HeldRecord)} uses, and for the same reason: the COBOL
     * command carries no {@code RIDFLD}, so the row it acts on is the one the locking read returned and
     * not one selected by anything in the new record. A row that changed in the interval - which
     * {@code UPDATEMODEL(LOCKING)} is there to prevent, but which a deployment without a real row lock
     * would allow - matches nothing and reports the invalid-key condition rather than being overwritten
     * unseen.
     *
     * <p>The key of {@code updated} is required to be the held row's. {@code COUSR02C:L219-L234} changes
     * four fields and {@code SEC-USR-ID} is not one of them, so a differing key is a request the paragraph
     * cannot have produced; it is reported as {@code DFHRESP(INVREQ)} with no statement issued.
     *
     * @param sql     the resolved statements
     * @param held    the record this task holds
     * @param updated the record to write, complete and already mutated by the caller
     * @return the discriminated outcome; never {@code null}
     */
    private WriteResult rewriteHeld(Statements sql, HeldRecord held, SecUserRecord updated) {
        byte[] heldImage = held.storedImage();
        byte[] recordImage = SecUserRecord.encode(updated, codec);
        // The held row's key comes from the record the locking read decoded, and SecUserRecord.key() is by
        // construction the leading KEY_LENGTH bytes of that row - so this compares the two keys without
        // slicing the encoded image by hand.
        String heldKey = held.record().key();
        String updatedKey = updated.key();
        if (!updatedKey.equals(heldKey)) {
            // Neither key is logged, for the reason given on provenAbsence: this dataset holds user
            // identifiers and, per app/cpy/CSUSR01Y.cpy, plaintext passwords. That the two disagree is the
            // whole diagnosis, and the values add nothing to it.
            LOG.error("A rewrite of the security-user dataset '" + datasetName + "' was refused: the "
                    + "record area names a different user from the row this task holds. EXEC CICS REWRITE "
                    + "at app/cbl/COUSR02C.cbl:L360-L366 carries no RIDFLD and so can only replace the "
                    + "held record, and UPDATE-USER-INFO at L219-L234 changes the first name, the last "
                    + "name, the password and the user type - never SEC-USR-ID. File status "
                    + FileStatus.toStatusImage(PERMANENT_ERROR_STATUS) + " is reported and no row has "
                    + "been changed.");
            return WriteResult.of(PERMANENT_ERROR_STATUS, CicsResponse.of(FileStatus.INVREQ));
        }

        int matching;
        try {
            matching = matchingImageCount(sql.selectByImageForUpdate(), heldImage);
        } catch (DataAccessException refused) {
            return reportWrite(refused, "establish how many rows the held record selects in the "
                    + "security-user dataset '" + datasetName + "' before rewriting it");
        }
        if (matching == 0) {
            // WHEN DFHRESP(NOTFND) at COUSR02C:L377. The held row is gone, so nothing is written.
            return WriteResult.notFound();
        }
        if (matching > SINGLE_ROW) {
            return fanOutRefused("rewrite");
        }

        int rewritten;
        try {
            PreparedStatementSetter binder = parameters -> {
                recordImageForm.bindImage(parameters, 1, recordImage, codec.charset());
                recordImageForm.bindImage(parameters, 2, heldImage, codec.charset());
            };
            rewritten = jdbcTemplate.update(sql.rewriteByImage(), binder);
        } catch (DataAccessException refused) {
            return reportWrite(refused, "rewrite the held record of the security-user dataset '"
                    + datasetName + "'");
        }

        if (rewritten == SINGLE_ROW) {
            return WriteResult.written();
        }
        if (rewritten == 0) {
            // The row the probe found is gone. Nothing was written, so this is the invalid-key condition
            // exactly as it would be had the row never been there.
            return WriteResult.notFound();
        }
        return unexpectedRowCount("rewrite", rewritten);
    }

    /**
     * Deletes the held record against already-resolved statements.
     *
     * <p>Shared by {@link #deleteHeld(HeldRecord)} and {@link HeldRecord#deleteHeld()}.
     *
     * <p>The predicate is the held record's <strong>whole stored image</strong>, not its key. That is what
     * makes this the held-record delete rather than a keyed one: the row removed is the exact row the
     * locking read returned, byte for byte. If it changed in the interval - which
     * {@code UPDATEMODEL(LOCKING)} is there to prevent, but which a deployment without a real row lock
     * would allow - the delete matches nothing and reports not-found rather than removing a record the
     * operator never saw.
     *
     * @param sql  the resolved statements
     * @param held the record this task holds
     * @return the discriminated outcome; never {@code null}
     */
    private WriteResult deleteHeld(Statements sql, HeldRecord held) {
        byte[] heldImage = held.storedImage();

        int matching;
        try {
            matching = matchingImageCount(sql.selectByImageForUpdate(), heldImage);
        } catch (DataAccessException refused) {
            return reportWrite(refused, "establish how many rows the held record selects in the "
                    + "security-user dataset '" + datasetName + "' before deleting it");
        }
        if (matching == 0) {
            return WriteResult.notFound();
        }
        if (matching > SINGLE_ROW) {
            return fanOutRefused("delete");
        }

        int deleted;
        try {
            PreparedStatementSetter binder = parameters ->
                    recordImageForm.bindImage(parameters, 1, heldImage, codec.charset());
            deleted = jdbcTemplate.update(sql.deleteByImage(), binder);
        } catch (DataAccessException refused) {
            return reportWrite(refused, "delete the held record from the security-user dataset '"
                    + datasetName + "'");
        }

        if (deleted == SINGLE_ROW) {
            return WriteResult.written();
        }
        if (deleted == 0) {
            return WriteResult.notFound();
        }
        return unexpectedRowCount("delete", deleted);
    }

    private Row browseByKeyOrder(String scanStatement, byte[] boundKey, boolean forward,
            boolean inclusive) {
        PreparedStatementCreator creator = connection -> {
            PreparedStatement prepared = connection.prepareStatement(scanStatement);
            prepared.setFetchSize(BROWSE_SCAN_FETCH_SIZE);
            return prepared;
        };
        ResultSetExtractor<Row> extractor = resultSet -> {
            byte[] best = null;
            while (resultSet.next()) {
                byte[] candidate = recordImageForm.readImage(resultSet, RECORD_IMAGE_COLUMN_INDEX,
                        codec.charset());
                if (candidate == null || candidate.length < KEY_OFFSET + KEY_LENGTH) {
                    return new Row(true, candidate);
                }
                int against = compareKeys(candidate, boundKey);
                boolean admissible = forward
                        ? (inclusive ? against >= 0 : against > 0)
                        : against < 0;
                if (!admissible) {
                    continue;
                }
                if (best == null || (forward ? compareKeys(candidate, best) < 0
                        : compareKeys(candidate, best) > 0)) {
                    best = candidate;
                }
            }
            return best == null ? Row.none() : new Row(true, best);
        };
        Row row = jdbcTemplate.query(creator, extractor);
        return row == null ? Row.none() : row;
    }

    private static int compareKeys(byte[] recordImage, byte[] key) {
        return Arrays.compareUnsigned(recordImage, KEY_OFFSET, KEY_OFFSET + KEY_LENGTH,
                key, 0, key.length);
    }

    private byte[] keyImageBytes(String keyImage) {
        if (LOW_VALUES_KEY.equals(keyImage)) {
            return figurativeKeyBytes(LOW_VALUES_BYTE);
        }
        if (HIGH_VALUES_KEY.equals(keyImage)) {
            return figurativeKeyBytes(HIGH_VALUES_BYTE);
        }
        return codec.encodeImage(keyImage, SecUserRecord.FIELD_SEC_USR_ID);
    }

    private KeyedMatch rowMatching(String statement, String pattern) {
        PreparedStatementCreator creator = connection -> {
            PreparedStatement prepared = bounded(connection.prepareStatement(statement));
            recordImageForm.bindOperand(prepared, 1, pattern, codec.charset());
            return prepared;
        };
        ResultSetExtractor<KeyedMatch> extractor = resultSet -> {
            if (!resultSet.next()) {
                return KeyedMatch.none();
            }
            byte[] first = recordImageForm.readImage(resultSet, RECORD_IMAGE_COLUMN_INDEX,
                    codec.charset());
            int matched = resultSet.next() ? FAN_OUT_PROBE_LIMIT : SINGLE_ROW;
            return new KeyedMatch(matched, first);
        };
        KeyedMatch match = jdbcTemplate.query(creator, extractor);
        return match == null ? KeyedMatch.none() : match;
    }

    private boolean anyRowFromKey(Statements sql, String anchorKey) {
        return browseByKeyOrder(sql.browseScan(), keyImageBytes(anchorKey), true, true).present();
    }

    private int matchingRowCount(String statement, String pattern) {
        return countRows(connection -> {
            PreparedStatement prepared = bounded(connection.prepareStatement(statement));
            recordImageForm.bindOperand(prepared, 1, pattern, codec.charset());
            return prepared;
        });
    }

    private int matchingImageCount(String statement, byte[] image) {
        return countRows(connection -> {
            PreparedStatement prepared = bounded(connection.prepareStatement(statement));
            recordImageForm.bindImage(prepared, 1, image, codec.charset());
            return prepared;
        });
    }

    private int countRows(PreparedStatementCreator creator) {
        ResultSetExtractor<Integer> counter = resultSet -> {
            int rows = 0;
            while (resultSet.next()) {
                rows++;
            }
            return rows;
        };
        Integer counted = jdbcTemplate.query(creator, counter);
        return counted == null ? 0 : counted;
    }

    private static PreparedStatement bounded(PreparedStatement statement) throws SQLException {
        statement.setMaxRows(FAN_OUT_PROBE_LIMIT);
        statement.setFetchSize(FAN_OUT_PROBE_LIMIT);
        return statement;
    }

    private Statements resolveStatements() {
        Statements resolved = this.statements;
        if (resolved != null) {
            return resolved;
        }
        ResultSetExtractor<String> columnNameExtractor = SecUserRepository::extractRecordImageColumnName;
        String columnName = jdbcTemplate.query(relation.describeStatement(), columnNameExtractor);
        Statements composed = Statements.over(relation, requireUsableColumnName(columnName));
        this.statements = composed;
        return composed;
    }

    private static String extractRecordImageColumnName(ResultSet resultSet) throws SQLException {
        return DatasetRelation.recordImageColumnOf(resultSet.getMetaData());
    }

    private ReadResult decoded(byte[] recordImage, String subject, boolean forUpdate) {
        if (recordImage.length != RECORD_LENGTH) {
            LOG.error("The security-user dataset '" + datasetName + "' presented " + subject + " as "
                    + recordImage.length + " byte(s), but SEC-USER-DATA is declared " + RECORD_LENGTH
                    + " bytes by app/cpy/CSUSR01Y.cpy; reporting file status "
                    + FileStatus.toStatusImage(PERMANENT_ERROR_STATUS) + " rather than decoding "
                    + "SEC-USR-TYPE from an offset that would not be its own");
            return ReadResult.of(PERMANENT_ERROR_STATUS, CicsResponse.of(FileStatus.LENGERR));
        }
        SecUserRecord record = SecUserRecord.decode(recordImage, codec);
        if (!forUpdate) {
            return ReadResult.found(record);
        }
        return ReadResult.held(record, new HeldRecord(this, record, recordImage));
    }

    private ReadResult provenAbsence(String unreadableRowsProbe) {
        KeyedMatch unreadable;
        try {
            unreadable = rowsWithNoImage(unreadableRowsProbe);
        } catch (DataAccessException refused) {
            return reportRead(refused, "establish that the security-user dataset '" + datasetName
                    + "' holds no unreadable row before reporting a record as absent");
        }
        if (unreadable.matched() == 0) {
            return ReadResult.notFound();
        }
        LOG.error("A keyed read of the security-user dataset '" + datasetName + "' matched no row, but the "
                + "dataset holds a row with no record image at column position " + RECORD_IMAGE_COLUMN_INDEX
                + " - and SEC-USR-ID is part of that image, so that row's key cannot be known; reporting "
                + "file status " + FileStatus.toStatusImage(PERMANENT_ERROR_STATUS)
                + " rather than reporting as absent a user who may well be defined");
        return ReadResult.of(PERMANENT_ERROR_STATUS, CicsResponse.of(FileStatus.INVREQ));
    }

    private KeyedMatch rowsWithNoImage(String probeStatement) {
        PreparedStatementCreator creator =
                connection -> bounded(connection.prepareStatement(probeStatement));
        ResultSetExtractor<KeyedMatch> extractor = resultSet ->
                resultSet.next() ? new KeyedMatch(SINGLE_ROW, null) : KeyedMatch.none();
        KeyedMatch probed = jdbcTemplate.query(creator, extractor);
        return probed == null ? KeyedMatch.none() : probed;
    }

    private ReadResult unreadableRow(String subject) {
        LOG.error("The security-user dataset '" + datasetName + "' presented " + subject
                + " with no record image at column position " + RECORD_IMAGE_COLUMN_INDEX
                + "; reporting file status " + FileStatus.toStatusImage(PERMANENT_ERROR_STATUS)
                + " to the caller");
        return ReadResult.of(PERMANENT_ERROR_STATUS, CicsResponse.of(FileStatus.INVREQ));
    }

    private ReadResult fanOutReadRefused() {
        LOG.error("A keyed read of the security-user dataset '" + datasetName + "' matched more than one "
                + "row; SEC-USR-ID is the unique primary key of a KSDS, so the read is being refused and "
                + "file status " + FileStatus.toStatusImage(PERMANENT_ERROR_STATUS) + " reported to the "
                + "caller rather than returning an arbitrary one of them as though it were the record. "
                + "The backing relation needs a unique constraint on its key span.");
        return ReadResult.of(PERMANENT_ERROR_STATUS, CicsResponse.of(FileStatus.INVREQ));
    }

    private WriteResult fanOutRefused(String operation) {
        LOG.error("A " + operation + " against the security-user dataset '" + datasetName + "' would "
                + "have affected more than one row; a KSDS primary key is unique, so the " + operation
                + " is being refused before it is issued and file status "
                + FileStatus.toStatusImage(PERMANENT_ERROR_STATUS) + " reported to the caller. No row "
                + "has been changed.");
        return WriteResult.of(PERMANENT_ERROR_STATUS, CicsResponse.of(FileStatus.INVREQ));
    }

    private WriteResult unexpectedRowCount(String operation, int affected) {
        LOG.error("A " + operation + " against the security-user dataset '" + datasetName + "' affected "
                + affected + " row(s) where the probe had found exactly " + SINGLE_ROW
                + "; reporting file status " + FileStatus.toStatusImage(PERMANENT_ERROR_STATUS)
                + " so the caller reaches its WHEN OTHER arm");
        return WriteResult.of(PERMANENT_ERROR_STATUS, CicsResponse.of(FileStatus.INVREQ));
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

    private static String requireKeyImage(String candidate) {
        Objects.requireNonNull(candidate, "A user id is required to address the security-user file: "
                + "every EXEC CICS command in the five programs supplies RIDFLD from a PIC X(08) field");
        if (candidate.length() != KEY_LENGTH) {
            throw new IllegalArgumentException("The user id image is " + candidate.length()
                    + " character(s) where SEC-USR-ID is declared PIC X(08) by app/cpy/CSUSR01Y.cpy, and "
                    + "every command passes KEYLENGTH (LENGTH OF SEC-USR-ID). Supply exactly " + KEY_LENGTH
                    + " characters, space-padded and untrimmed, so that a short key cannot silently become "
                    + "a prefix match over more records than it names");
        }
        return candidate;
    }

    private static DatasetBinding requireSecurityFileGeometry(DatasetBindings datasetBindings) {
        DatasetBinding binding = datasetBindings.binding(CICS_FILE_NAME);
        if (binding.recordLength() != RECORD_LENGTH) {
            throw new IllegalStateException("The dataset binding for '" + CICS_FILE_NAME + "' declares a "
                    + "record length of " + binding.recordLength() + ", but SEC-USER-DATA is "
                    + RECORD_LENGTH + " bytes: app/cpy/CSUSR01Y.cpy:L17-L23 sums to " + RECORD_LENGTH
                    + " and app/jcl/DUSRSECJ.jcl:L48 declares LRECL=80. This repository decodes by "
                    + "absolute offset, so any other width would place SEC-USR-PWD and SEC-USR-TYPE at "
                    + "spans that are not theirs");
        }
        Integer declaredKeyLength = binding.keyLength();
        if (declaredKeyLength != null && declaredKeyLength != KEY_LENGTH) {
            throw new IllegalStateException("The dataset binding for '" + CICS_FILE_NAME + "' declares a "
                    + "key length of " + declaredKeyLength + ", but the key is SEC-USR-ID PIC X(08) and "
                    + "every EXEC CICS command in the five programs passes KEYLENGTH (LENGTH OF "
                    + "SEC-USR-ID), which is " + KEY_LENGTH);
        }
        if (binding.keyOffsetOrZero() != KEY_OFFSET) {
            throw new IllegalStateException("The dataset binding for '" + CICS_FILE_NAME + "' places the "
                    + "key at offset " + binding.keyOffsetOrZero() + ", but SEC-USR-ID is the leading "
                    + "field of SEC-USER-DATA and so begins at offset " + KEY_OFFSET);
        }
        return binding;
    }

    private static String requireUsableDatasetName(String candidate) {
        if (candidate == null || candidate.isBlank()) {
            throw new IllegalStateException("The dataset binding for '" + CICS_FILE_NAME + "' declares "
                    + "no dataset name. Set carddemo.datasets." + CICS_FILE_NAME + ".dsname; this "
                    + "repository composes its statements from configuration alone and hard-codes no "
                    + "dataset name.");
        }
        return DatasetRelation.requireDatasetName(candidate);
    }

    private static String requireUsableColumnName(String candidate) {
        if (candidate == null || candidate.isBlank()) {
            throw new IllegalStateException("The security-user dataset presents no column at position "
                    + RECORD_IMAGE_COLUMN_INDEX + ", so there is nothing to read a record image from. "
                    + "The whole module addresses a dataset as a single-column record-image relation; one "
                    + "that is not cannot be read or written at all, and a name invented here would "
                    + "address nothing");
        }
        requireNoControlCharacter(candidate, "The record-image column name the backend reported");
        return candidate;
    }

    private static void requireNoControlCharacter(String candidate, String subject) {
        for (int index = 0; index < candidate.length(); index++) {
            char character = candidate.charAt(index);
            if (Character.isISOControl(character)) {
                throw new IllegalStateException(subject + " carries a control character at position "
                        + index + ", which would split a log entry in two and cannot appear in a valid "
                        + "identifier. The offending value is not repeated here, deliberately");
            }
        }
    }

    private static Outcome classify(String status) {
        Objects.requireNonNull(status, "An outcome carries the two-character file status the operation "
                + "reported; it is never absent");
        if (status.length() != FileStatus.STATUS_LENGTH) {
            throw new IllegalArgumentException("A file status is exactly " + FileStatus.STATUS_LENGTH
                    + " characters, as every FILE STATUS field in the estate is declared; got "
                    + status.length() + ".");
        }
        return FileStatus.outcomeOfStatus(status);
    }

    /**
     * Whether a read found a row, and the image it carried.
     *
     * @param present whether a row arrived at all
     * @param image the row's record image, which may be {@code null} even when a row arrived
     */
    private record Row(boolean present, byte[] image) {
        private static final Row NONE = new Row(false, null);

        static Row none() {
            return NONE;
        }
    }

    /**
     * How many rows a keyed read's key selected, and the first row's image.
     *
     * @param matched how many rows the key selected, capped at {@value #FAN_OUT_PROBE_LIMIT}; a value of
     *     {@value #FAN_OUT_PROBE_LIMIT} means "at least that many" rather than "exactly"
     * @param image the first row's record image, which may be {@code null} even when a row arrived
     */
    private record KeyedMatch(int matched, byte[] image) {
        private static final KeyedMatch NONE = new KeyedMatch(0, null);

        static KeyedMatch none() {
            return NONE;
        }

        byte[] firstImage() {
            return image;
        }
    }

    /**
     * The statements for this dataset, composed once per operation from the discovered column name.
     *
     * @param selectByKey the keyed read, taking the keyed pattern
     * @param selectByKeyForUpdate the locking keyed read, taking the keyed pattern
     * @param browseScan the browse read: the whole relation, with no predicate and no {@code ORDER BY}
     * @param insert the {@code WRITE}, taking the record image
     * @param rewrite the {@code REWRITE}, taking the new image then the keyed pattern
     * @param selectByImageForUpdate the locking whole-image probe, taking the held image
     * @param deleteByImage the held-record {@code DELETE}, taking the held image
     * @param probeUnreadableRows the rows whose record-image column holds nothing
     */
    record Statements(String selectByKey,
                      String selectByKeyForUpdate,
                      String browseScan,
                      String insert,
                      String rewrite,
                      String selectByImageForUpdate,
                      String rewriteByImage,
                      String deleteByImage,
                      String probeUnreadableRows) {
        private static final String FOR_UPDATE = " FOR UPDATE";

        static Statements over(DatasetRelation relation, String column) {
            String wholeImagePredicate = " WHERE " + DatasetRelation.delimit(column) + " = ?";
            return new Statements(
                    relation.selectByKey(column),
                    relation.selectByKeyForUpdate(column),
                    relation.selectAll(),
                    relation.insertRecordImage(column),
                    relation.rewriteByKey(column),
                    "SELECT * FROM " + relation.identifier() + wholeImagePredicate + FOR_UPDATE,
                    "UPDATE " + relation.identifier() + " SET " + DatasetRelation.delimit(column)
                            + " = ?" + wholeImagePredicate,
                    "DELETE FROM " + relation.identifier() + wholeImagePredicate,
                    relation.selectUnreadableRows(column));
        }
    }

    /**
     * The outcome of a read: the status, its classification, the record where there was one, the hold where
     * the read took one, and the CICS response the programs branch on.
     *
     * @param status the two-character file status
     * @param outcome the classification of {@code status}
     * @param record the decoded record, present only for a successful read
     * @param hold the held record, present only for a successful {@link #readForUpdate(String)}
     * @param response the CICS {@code RESP} and {@code RESP2} the programs capture
     * @param diagnostic what the backend said, present only when it refused
     */
    public record ReadResult(String status,
                             Outcome outcome,
                             Optional<SecUserRecord> record,
                             Optional<HeldRecord> hold,
                             CicsResponse response,
                             Optional<BackendDiagnostic> diagnostic) {
        public ReadResult {
            Objects.requireNonNull(record, "A read outcome's record is an Optional, never null");
            Objects.requireNonNull(hold, "A read outcome's hold is an Optional, never null");
            Objects.requireNonNull(response, "A read outcome carries a CICS response; use "
                    + "CicsResponse.none() where none was reported");
            Objects.requireNonNull(diagnostic, "A read outcome's diagnostic is an Optional, never null");
            Outcome classified = classify(status);
            if (outcome != classified) {
                throw new IllegalArgumentException("Status '" + FileStatus.toStatusImage(status)
                        + "' classifies as " + classified + ", but " + outcome + " was given; use one of "
                        + "the named factory methods");
            }
            if (record.isPresent() != (outcome == Outcome.OK)) {
                throw new IllegalArgumentException("A record is present exactly when the read succeeded: "
                        + "outcome " + outcome + " with record "
                        + (record.isPresent() ? "present" : "absent"));
            }
            if (hold.isPresent() && record.isEmpty()) {
                throw new IllegalArgumentException("A hold stands for a record this task holds, so it "
                        + "cannot be present without the record it holds");
            }
        }

        /**
         * A successful read that took no lock: {@code RESP} {@code 0}, the {@code WHEN 0} arm of
         * {@code app/cbl/COSGN00C.cbl:L222}.
         *
         * @param record the decoded record
         * @return the outcome
         */
        public static ReadResult found(SecUserRecord record) {
            Objects.requireNonNull(record, "A successful read carries the record it read");
            return new ReadResult(FileStatus.OK, Outcome.OK, Optional.of(record), Optional.empty(),
                    CicsResponse.of(FileStatus.NORMAL), Optional.empty());
        }

        /**
         * A successful {@code READ ... UPDATE}: the record, plus the hold a rewrite or a delete needs.
         *
         * @param record the decoded record
         * @param hold the handle standing for the record this task holds
         * @return the outcome
         */
        public static ReadResult held(SecUserRecord record, HeldRecord hold) {
            Objects.requireNonNull(record, "A successful locking read carries the record it read");
            Objects.requireNonNull(hold, "A successful locking read carries the hold it took");
            return new ReadResult(FileStatus.OK, Outcome.OK, Optional.of(record), Optional.of(hold),
                    CicsResponse.of(FileStatus.NORMAL), Optional.empty());
        }

        /**
         * The keyed record was absent: {@code RESP} {@code 13}, {@code DFHRESP(NOTFND)}.
         *
         * @return the outcome
         */
        public static ReadResult notFound() {
            return new ReadResult(FileStatus.NOT_FOUND, Outcome.NOT_FOUND, Optional.empty(),
                    Optional.empty(), CicsResponse.of(FileStatus.NOTFND), Optional.empty());
        }

        /**
         * The browse ran past its last record: {@code DFHRESP(ENDFILE)}, the arm at
         * {@code app/cbl/COUSR00C.cbl:L634} and {@code :L668}.
         *
         * @return the outcome
         */
        public static ReadResult endOfFile() {
            return new ReadResult(FileStatus.END_OF_FILE, Outcome.END_OF_FILE, Optional.empty(),
                    Optional.empty(), CicsResponse.of(FileStatus.ENDFILE), Optional.empty());
        }

        /**
         * An outcome that carries no record, with the CICS response that produced it: the
         * {@code WHEN OTHER} arm, and any other condition that returned nothing.
         *
         * @param status the two-character status of an outcome that carries no record
         * @param response the CICS response
         * @return the outcome
         * @throws IllegalArgumentException if {@code status} is not two characters, or classifies as a
         *     success - for which a record is required
         */
        public static ReadResult of(String status, CicsResponse response) {
            return new ReadResult(status, classify(status), Optional.empty(), Optional.empty(), response,
                    Optional.empty());
        }

        /**
         * An outcome that carries no record, with what the backend said: the {@code WHEN OTHER} arm after a
         * refusal.
         *
         * @param status the two-character status of an outcome that carries no record
         * @param diagnostic what the backend reported
         * @return the outcome
         * @throws NullPointerException if {@code diagnostic} is {@code null}
         * @throws IllegalArgumentException if {@code status} is not two characters, or classifies as a
         *     success
         */
        public static ReadResult of(String status, BackendDiagnostic diagnostic) {
            Objects.requireNonNull(diagnostic, "A reported refusal carries the backend's diagnosis");
            return new ReadResult(status, classify(status), Optional.empty(), Optional.empty(),
                    CicsResponse.none(), Optional.of(diagnostic));
        }

        /**
         * Whether the read succeeded and a record is available: the {@code WHEN 0} test.
         *
         * @return {@code true} for a successful read
         */
        public boolean isFound() {
            return outcome == Outcome.OK;
        }

        /**
         * Whether the keyed record was absent: the {@code WHEN 13} and {@code WHEN DFHRESP(NOTFND)} test.
         *
         * @return {@code true} for a not-found read
         */
        public boolean isNotFound() {
            return outcome == Outcome.NOT_FOUND;
        }

        /**
         * Whether the browse ran past its last record: the {@code WHEN DFHRESP(ENDFILE)} test.
         *
         * @return {@code true} at the end of a browse
         */
        public boolean isEndOfFile() {
            return outcome == Outcome.END_OF_FILE;
        }

        public boolean isOther() {
            return outcome == Outcome.OTHER;
        }

        /**
         * The CICS {@code RESP} value, where one was reported.
         *
         * @return the response value, or empty when the backend refused without one
         */
        public OptionalInt cicsResp() {
            return response.resp();
        }

        /**
         * The CICS {@code RESP2} value the programs move into {@code WS-REAS-CD}.
         *
         * @return the reason code, {@link SecUserRepository#CICS_RESP2_NOT_APPLICABLE} where there is none
         */
        public int cicsResp2() {
            return response.resp2();
        }

        /**
         * The status rendered as the four-character image a {@code DISPLAY} of it produces.
         *
         * @return the status image
         */
        public String statusImage() {
            return FileStatus.toStatusImage(status);
        }

        /**
         * The record, for a caller that has already established the read succeeded.
         *
         * @return the decoded record
         * @throws IllegalStateException if the read did not succeed
         */
        public SecUserRecord requireRecord() {
            return record.orElseThrow(() -> new IllegalStateException("This read reported "
                    + statusImage() + " (" + outcome + "), so it carries no record. Test isFound() "
                    + "first, exactly as the COBOL tests RESP before using SEC-USER-DATA"));
        }

        /**
         * The hold, for a caller that has already established a locking read succeeded.
         *
         * @return the handle standing for the record this task holds
         * @throws IllegalStateException if this was not a successful locking read
         */
        public HeldRecord requireHold() {
            return hold.orElseThrow(() -> new IllegalStateException("This read reported " + statusImage()
                    + " (" + outcome + ") or took no lock, so it holds no record. A rewrite or a delete "
                    + "follows a successful readForUpdate(String) and nothing else"));
        }
    }

    /**
     * The outcome of a write: an {@code add}, a {@code rewrite} or the held-record {@code delete}.
     *
     * @param status the two-character file status
     * @param outcome the classification of {@code status}
     * @param response the CICS {@code RESP} and {@code RESP2}
     * @param diagnostic what the backend said, present only when it refused
     */
    public record WriteResult(String status,
                              Outcome outcome,
                              CicsResponse response,
                              Optional<BackendDiagnostic> diagnostic) {
        public WriteResult {
            Objects.requireNonNull(response, "A write outcome carries a CICS response; use "
                    + "CicsResponse.none() where none was reported");
            Objects.requireNonNull(diagnostic, "A write outcome's diagnostic is an Optional, never null");
            Outcome classified = classify(status);
            if (outcome != classified) {
                throw new IllegalArgumentException("Status '" + FileStatus.toStatusImage(status)
                        + "' classifies as " + classified + ", but " + outcome + " was given; use one of "
                        + "the named factory methods");
            }
        }

        /**
         * The write succeeded: the {@code WHEN DFHRESP(NORMAL)} arm of {@code COUSR01C:L251},
         * {@code COUSR02C:L369} and {@code COUSR03C:L314}.
         *
         * @return the outcome
         */
        public static WriteResult written() {
            return new WriteResult(FileStatus.OK, Outcome.OK, CicsResponse.of(FileStatus.NORMAL),
                    Optional.empty());
        }

        /**
         * There was no such record to rewrite or delete: {@code DFHRESP(NOTFND)}, the arm at
         * {@code COUSR02C:L377} and {@code COUSR03C:L323}.
         *
         * @return the outcome
         */
        public static WriteResult notFound() {
            return new WriteResult(FileStatus.NOT_FOUND, Outcome.NOT_FOUND,
                    CicsResponse.of(FileStatus.NOTFND), Optional.empty());
        }

        /**
         * The key already holds a record: {@code DFHRESP(DUPREC)}, the second of the two consecutive
         * {@code WHEN}s at {@code app/cbl/COUSR01C.cbl:L261}.
         *
         * @return the outcome
         */
        public static WriteResult duplicateRecord() {
            return new WriteResult(FileStatus.DUPLICATE, Outcome.DUPLICATE,
                    CicsResponse.of(FileStatus.DUPREC), Optional.empty());
        }

        /**
         * The key already holds a record, as the backend's integrity constraint reported it.
         *
         * @param diagnostic what the backend said
         * @return the outcome
         */
        public static WriteResult duplicateRecord(BackendDiagnostic diagnostic) {
            Objects.requireNonNull(diagnostic, "A reported refusal carries the backend's diagnosis");
            return new WriteResult(FileStatus.DUPLICATE, Outcome.DUPLICATE,
                    CicsResponse.of(FileStatus.DUPREC), Optional.of(diagnostic));
        }

        /**
         * A non-unique alternate-index key was rejected: {@code DFHRESP(DUPKEY)}, the first of the two
         * consecutive {@code WHEN}s at {@code app/cbl/COUSR01C.cbl:L260}.
         *
         * <p>{@value SecUserRepository#CICS_FILE_NAME} has no alternate index defined in
         * {@code app/csd/CARDDEMO.CSD}, so the backend cannot produce this condition.
         *
         * @return the outcome
         */
        public static WriteResult duplicateKey() {
            return new WriteResult(FileStatus.DUPLICATE, Outcome.DUPLICATE,
                    CicsResponse.of(FileStatus.DUPKEY), Optional.empty());
        }

        /**
         * Any other outcome, carrying the CICS response that produced it: the {@code WHEN OTHER} arm.
         *
         * @param status the two-character status
         * @param response the CICS response
         * @return the outcome
         */
        public static WriteResult of(String status, CicsResponse response) {
            return new WriteResult(status, classify(status), response, Optional.empty());
        }

        /**
         * Any other outcome, carrying what the backend said: the {@code WHEN OTHER} arm after a refusal.
         *
         * @param status the two-character status
         * @param diagnostic what the backend reported
         * @return the outcome
         */
        public static WriteResult of(String status, BackendDiagnostic diagnostic) {
            Objects.requireNonNull(diagnostic, "A reported refusal carries the backend's diagnosis");
            return new WriteResult(status, classify(status), CicsResponse.none(),
                    Optional.of(diagnostic));
        }

        public boolean isWritten() {
            return outcome == Outcome.OK;
        }

        /**
         * Whether there was no such record: the {@code WHEN DFHRESP(NOTFND)} test.
         *
         * @return {@code true} for a not-found write
         */
        public boolean isNotFound() {
            return outcome == Outcome.NOT_FOUND;
        }

        /**
         * Whether either duplicate condition occurred: the pair of {@code WHEN}s taken together, which is
         * how {@code app/cbl/COUSR01C.cbl:L260-L266} treats them.
         *
         * @return {@code true} for {@link FileStatus#DUPREC} or {@link FileStatus#DUPKEY}
         */
        public boolean isDuplicate() {
            return outcome == Outcome.DUPLICATE;
        }

        /**
         * Whether specifically the primary key already held a record: {@code DFHRESP(DUPREC)}.
         *
         * @return {@code true} for the duplicate-record condition alone
         */
        public boolean isDuplicateRecord() {
            return isDuplicate() && response.resp().orElse(-1) == FileStatus.DUPREC;
        }

        /**
         * Whether specifically a non-unique alternate-index key was rejected: {@code DFHRESP(DUPKEY)}.
         *
         * @return {@code true} for the duplicate-key condition alone
         */
        public boolean isDuplicateKey() {
            return isDuplicate() && response.resp().orElse(-1) == FileStatus.DUPKEY;
        }

        public boolean isOther() {
            return outcome == Outcome.OTHER;
        }

        /**
         * The CICS {@code RESP} value, where one was reported.
         *
         * @return the response value, or empty when the backend refused without one
         */
        public OptionalInt cicsResp() {
            return response.resp();
        }

        /**
         * The CICS {@code RESP2} value the programs move into {@code WS-REAS-CD}.
         *
         * @return the reason code, {@link SecUserRepository#CICS_RESP2_NOT_APPLICABLE} where there is none
         */
        public int cicsResp2() {
            return response.resp2();
        }

        /**
         * The status rendered as the four-character image a {@code DISPLAY} of it produces.
         *
         * @return the status image
         */
        public String statusImage() {
            return FileStatus.toStatusImage(status);
        }
    }

    /**
     * The record this task holds from a {@code READ ... UPDATE}, and the only thing a held-record
     * {@code DELETE} can be issued against.
     */
    public static final class HeldRecord {
        private final SecUserRepository repository;

        private final SecUserRecord record;

        private final byte[] storedImage;

        private HeldRecord(SecUserRepository repository, SecUserRecord record, byte[] storedImage) {
            this.repository = repository;
            this.record = record;
            this.storedImage = storedImage.clone();
        }

        public SecUserRecord record() {
            return record;
        }

        public String datasetName() {
            return repository.datasetName();
        }

        public WriteResult rewrite(SecUserRecord updated) {
            return repository.rewriteHeld(this, updated);
        }

        /**
         * Deletes the held record, from the handle - and takes no argument at all.
         *
         * @return the discriminated outcome; never {@code null}
         * @throws IllegalStateException if no unit of work is open
         */
        public WriteResult deleteHeld() {
            return repository.deleteHeld(this);
        }

        private SecUserRepository repository() {
            return repository;
        }

        private byte[] storedImage() {
            return storedImage.clone();
        }
    }

    /**
     * One browse of the security-user file: the position a {@code STARTBR} established, and the reads that
     * advance it.
     *
     * <p>The backward paragraph relies on exactly that: {@code L342-L344} performs one {@code READPREV} and
     * discards what it returns, because the anchor is {@code CDEMO-CU00-USRID-FIRST}, the record already at
     * the top of the page being paged away from.
     */
    public static final class BrowseCursor implements AutoCloseable {
        private final SecUserRepository repository;

        private final Statements statements;

        private final String anchorKey;

        private final String openStatus;

        private final Outcome openOutcome;

        private final CicsResponse openResponse;

        private final Optional<BackendDiagnostic> openDiagnostic;

        private byte[] position;

        private boolean positioned;

        private int returned;

        private boolean ended;

        private BrowseCursor(SecUserRepository repository,
                             Statements statements,
                             String anchorKey,
                             String openStatus,
                             CicsResponse openResponse,
                             Optional<BackendDiagnostic> openDiagnostic) {
            this.repository = repository;
            this.statements = statements;
            this.anchorKey = anchorKey;
            this.openStatus = openStatus;
            this.openOutcome = classify(openStatus);
            this.openResponse = openResponse;
            this.openDiagnostic = openDiagnostic;
            this.position = null;
            this.positioned = false;
            this.returned = 0;
            this.ended = false;
        }

        public String openStatus() {
            return openStatus;
        }

        /**
         * The classification of the open, which is the {@code EVALUATE} at
         * {@code app/cbl/COUSR00C.cbl:L597-L614}: {@link Outcome#OK} is {@code WHEN DFHRESP(NORMAL)},
         * {@link Outcome#NOT_FOUND} is {@code WHEN DFHRESP(NOTFND)} - "You are at the top of the page..." -
         * and {@link Outcome#OTHER} is {@code WHEN OTHER}, "Unable to lookup User...".
         *
         * @return the classification; never {@code null}
         */
        public Outcome openOutcome() {
            return openOutcome;
        }

        /**
         * The CICS {@code RESP} the open reported, where one was reported.
         *
         * @return the response value, or empty when the backend refused without one
         */
        public OptionalInt openCicsResp() {
            return openResponse.resp();
        }

        /**
         * What the backend said, when the open failed because the backend refused it.
         *
         * @return the diagnosis, or empty for a normal or not-found open
         */
        public Optional<BackendDiagnostic> openDiagnostic() {
            return openDiagnostic;
        }

        /**
         * Whether this browse may be read from: the {@code IF NOT ERR-FLG-ON} guard at
         * {@code app/cbl/COUSR00C.cbl:L286} and {@code :L340}.
         *
         * @return {@code true} when the open succeeded and the browse has not been ended
         */
        public boolean isOpen() {
            return statements != null && !ended;
        }

        public boolean isEnded() {
            return ended;
        }

        public String anchorKey() {
            return anchorKey;
        }

        /**
         * The key of the record last returned, before which nothing has been read yet.
         *
         * @return the last returned record's key, or empty before the first successful read
         */
        public Optional<String> positionKey() {
            return positioned
                    ? Optional.of(repository.codec.decodeImage(position, SecUserRecord.FIELD_SEC_USR_ID))
                    : Optional.empty();
        }

        public int returned() {
            return returned;
        }

        public String datasetName() {
            return repository.datasetName();
        }

        /**
         * Reads the next record in ascending key order: {@code app/cbl/COUSR00C.cbl:L621-L629}.
         *
         * @return the read outcome; never {@code null}
         */
        public ReadResult readNext() {
            return read(true);
        }

        /**
         * Reads the previous record in descending key order: {@code app/cbl/COUSR00C.cbl:L655-L663}.
         *
         * @return the read outcome; never {@code null}
         */
        public ReadResult readPrevious() {
            return read(false);
        }

        private ReadResult read(boolean forward) {
            if (ended) {
                LOG.error("A read was requested on a browse of " + CICS_FILE_NAME + " that has already "
                        + "been ended; reporting the invalid-request response");
                return ReadResult.of(PERMANENT_ERROR_STATUS, CicsResponse.of(FileStatus.INVREQ));
            }
            if (statements == null) {
                LOG.error("A read was requested on a browse of " + CICS_FILE_NAME + " that reported "
                        + FileStatus.toStatusImage(openStatus) + " when it was positioned, so no browse "
                        + "was established; reporting the invalid-request response");
                return ReadResult.of(PERMANENT_ERROR_STATUS, CicsResponse.of(FileStatus.INVREQ));
            }

            Row row;
            try {
                row = positioned
                        ? repository.browseByKeyOrder(statements.browseScan(), position, forward, false)
                        : repository.browseByKeyOrder(statements.browseScan(),
                                repository.keyImageBytes(anchorKey), true, true);
            } catch (DataAccessException refused) {
                return repository.reportRead(refused, "read " + (forward ? "the next" : "the previous")
                        + " record of a browse of the security-user dataset '" + datasetName() + "'");
            }

            if (!row.present()) {
                return ReadResult.endOfFile();
            }
            if (row.image() == null) {
                return repository.unreadableRow("a record of a browse");
            }
            ReadResult result = repository.decoded(row.image(), "a record of a browse", false);
            if (result.isFound()) {
                position = Arrays.copyOfRange(row.image(), KEY_OFFSET, KEY_OFFSET + KEY_LENGTH);
                positioned = true;
                returned++;
            }
            return result;
        }

        /**
         * Ends the browse: {@code app/cbl/COUSR00C.cbl:L689-L691}, performed at {@code L325} and
         * {@code L374}.
         */
        public void endBrowse() {
            ended = true;
        }

        /**
         * Ends the browse, so the handle can be used in a try-with-resources block.
         */
        @Override
        public void close() {
            endBrowse();
        }
    }
}
