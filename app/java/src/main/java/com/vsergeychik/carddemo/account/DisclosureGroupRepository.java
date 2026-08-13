package com.vsergeychik.carddemo.account;

import com.vsergeychik.carddemo.account.AccountInterestCalcJob.DisclosureGroupAccess;
import com.vsergeychik.carddemo.account.AccountInterestCalcJob.DisclosureGroupFile;
import com.vsergeychik.carddemo.account.AccountInterestCalcJob.DisclosureGroupRead;
import com.vsergeychik.carddemo.account.model.DisclosureGroupRecord;
import com.vsergeychik.carddemo.common.DatasetRelation;
import com.vsergeychik.carddemo.common.DatasetRelation.BackendDiagnostic;
import com.vsergeychik.carddemo.common.DatasetRelation.KeySpan;
import com.vsergeychik.carddemo.common.DiagnosticText;
import com.vsergeychik.carddemo.common.FileStatus;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.RecordImageForm;
import com.vsergeychik.carddemo.common.SensitiveDiagnostics;
import com.vsergeychik.carddemo.config.CobolCharsetConfig;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBinding;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBindings;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
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

/**
 * The {@code DISCGRP} dataset over JDBC: a keyed single-column record-image relation, addressed by the
 * 16-byte {@code DIS-GROUP-KEY}.
 *
 * <p>The same shape every sibling repository uses - a metadata probe discovers the record-image
 * column's name, a {@code LIKE} pattern over the key span confines the match to the key bytes, and the
 * configured {@link RecordImageForm} decides whether that column is read as characters or as bytes.
 * Only the three verbs {@code CBACT04C} issues exist here: no browse, no write, no delete, because
 * this program performs none and nothing else reads this dataset.
 *
 * <p>All state on this instance is deeply immutable once constructed. The open's resolved statement and
 * the file's position belong to the returned handle, so a singleton is safe to share.
 */
@Repository
// Deliberately NOT final, and not by oversight. Spring Boot's PersistenceExceptionTranslationAutoConfiguration
// registers a PersistenceExceptionTranslationPostProcessor, which proxies every @Repository bean; a final
// class cannot be subclassed by CGLIB, so the context fails to start with "Could not generate CGLIB
// subclass". Every sibling repository in this module is non-final for the same reason. Nothing here is
// designed for extension - the constructor is the only entry point that mutates anything, and every field
// is assigned once - so leaving the class open costs nothing and keeps the bean proxyable.
public class DisclosureGroupRepository
        implements DisclosureGroupAccess {

    /** Where a backend refusal is reported, so an abend is diagnosable. */
    private static final Log LOG = LogFactory.getLog(DisclosureGroupRepository.class);

    /**
     * The feedback code of the permanent-error status: the null character.
     *
     * <p>{@code FILE STATUS} {@code '9x'} is the implementor-defined permanent error, and its second
     * character is a binary feedback code rather than a digit -
     * {@link FileStatus#toDisplayLine(String)} renders it as
     * {@code 9nnn}, which is what {@code 9910-DISPLAY-IO-STATUS} does with it.
     */
    private static final char PERMANENT_ERROR_FEEDBACK_CODE = 0;

    /**
     * The status reported for a failure the COBOL vocabulary has no specific code for: an unreachable
     * dataset, a refused statement, an unreadable row or a row of the wrong width.
     *
     * <p>{@code CBACT04C} has no branch for a specific permanent-error code - {@code :422} accepts
     * {@code '00'} and {@code '23'} and moves {@link AccountInterestCalcJob#APPL_RESULT_FATAL} for everything else - so what
     * matters is only that this is neither of those two and that it renders faithfully.
     */
    static final String PERMANENT_ERROR_STATUS = "9" + PERMANENT_ERROR_FEEDBACK_CODE;

    /**
     * The key span: offset 0, width {@value DisclosureGroupRecord#DIS_GROUP_KEY_LENGTH}.
     *
     * <p>Sixteen. See {@link AccountInterestCalcJob#verifyDeclaredKeyGeometry()}.
     */
    private static final KeySpan KEY_SPAN =
            new KeySpan(0, DisclosureGroupRecord.DIS_GROUP_KEY_LENGTH);

    /** The module's shared template. */
    private final JdbcTemplate jdbcTemplate;

    /** The codec, for its charset and for the width rules. */
    private final FixedWidthCodec codec;

    /** How this deployment's driver presents a record image. */
    private final RecordImageForm recordImageForm;

    /** The dataset contract, which is also where every statement's text is composed. */
    private final DatasetRelation relation;

    /** The resolved dataset name, as configuration declares it. */
    private final String datasetName;

    /**
     * Resolves the {@code DISCGRP} binding and validates its geometry against the copybook.
     *
     * @param jdbcTemplate    the module's shared template
     * @param datasetBindings the DD-name-keyed dataset catalogue
     * @param datasetCharset  the dataset code page, stated explicitly
     * @param recordImageForm the record-image representation
     * @throws NullPointerException  if any argument is {@code null}
     * @throws IllegalStateException if {@value AccountInterestCalcJob#DISCGRP_DD_NAME} is unconfigured, or its binding
     *                               declares a record width, key width or key offset other than the
     *                               copybook's
     */
    public DisclosureGroupRepository(
            JdbcTemplate jdbcTemplate,
            DatasetBindings datasetBindings,
            @Qualifier(CobolCharsetConfig.DATASET_CHARSET_BEAN_NAME) Charset datasetCharset,
            RecordImageForm recordImageForm) {

        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "A JdbcTemplate is required: the "
                + "disclosure group dataset is reached through the module's shared template");
        Objects.requireNonNull(datasetBindings, "The carddemo.datasets binding catalogue is "
                + "required: dataset names live in configuration and are never written in Java");
        this.codec = new FixedWidthCodec(Objects.requireNonNull(datasetCharset, "A dataset charset "
                + "is required: a fixed-width mainframe record is bytes in a specific code page, so "
                + "the code page is stated explicitly and never taken from the platform"));
        this.recordImageForm = Objects.requireNonNull(recordImageForm, "A record-image "
                + "representation is required: whether this deployment's driver presents a record "
                + "image as characters or as bytes is stated once, by "
                + RecordImageForm.FORM_PROPERTY + ", and never decided per repository");
        RecordImageForm.requireSingleByteCodePage(datasetCharset);

        DatasetBinding binding = requireDiscgrpGeometry(datasetBindings);
        this.relation = DatasetRelation.of(requireUsableDatasetName(binding.dsname()),
                DisclosureGroupRecord.RECORD_LENGTH);
        this.datasetName = this.relation.dsname();
    }

    @Override
    public String datasetName() {
        return datasetName;
    }

    @Override
    public DisclosureGroupFile open() {
        // An OPEN resolves the dataset's shape afresh; the resolved statement belongs to the handle.
        relation.forgetRecordImageColumn();
        try {
            return new JdbcDisclosureGroupFile(this, FileStatus.OK, resolveStatements());
        } catch (DataAccessException unreachable) {
            // The SANITIZED summary only. A throwable handed to a logger emits its message and its
            // whole cause chain verbatim, and a driver composes that message around the values it
            // refused - so the record's content would reach a log file that is read by more people
            // and guarded less than the dataset (CWE-532), and a carriage return anywhere in it would
            // split the entry in two (CWE-117). BackendDiagnostic.describe() carries the SQLSTATE,
            // the vendor code and the exception type, which is what is diagnosable and nothing more.
            LOG.error("Could not open the disclosure group dataset '" + datasetName
                    + "' - " + DatasetRelation.BackendDiagnostic.of(unreachable).describe()
                    + "; reporting file status " + FileStatus.toStatusImage(PERMANENT_ERROR_STATUS)
                    + " from the open, which app/cbl/CBACT04C.cbl:273-284 displays as '"
                    + AccountInterestCalcJob.ERROR_OPENING_DISCGRP + "' before abending");
            return new JdbcDisclosureGroupFile(this, PERMANENT_ERROR_STATUS, null);
        }
    }

    /**
     * Composes the statements this access path sends, discovering the record-image column's name from
     * the backend.
     *
     * <p>The describe is read-only and returns no rows by construction, so the relation is described
     * without any of it being transferred - which is what an {@code OPEN INPUT} establishes too. Both
     * statements come out of that one describe: the column name is remembered on the relation, so
     * composing the unreadable-row probe costs no second round trip.
     *
     * <p>Package-visible so a unit test can assert the composed text without a round trip.
     *
     * @return the keyed-read statement and the unreadable-row probe
     * @throws DataAccessException   if the dataset cannot be described, which the caller turns into a
     *                               status
     * @throws IllegalStateException if the dataset is described but presents no usable record-image
     *                               column
     */
    Statements resolveStatements() {
        ResultSetExtractor<String> columnNameExtractor =
                DisclosureGroupRepository::extractRecordImageColumnName;
        String columnName = jdbcTemplate.query(relation.describeStatement(), columnNameExtractor);
        String recordImageColumn =
                relation.rememberRecordImageColumn(requireUsableColumnName(columnName));
        return new Statements(relation.selectByKey(recordImageColumn),
                relation.selectUnreadableRows(recordImageColumn));
    }

    /**
     * The two statements one open resolves.
     *
     * @param selectByKey         the keyed read, taking the {@code LIKE} pattern as its only parameter
     * @param probeUnreadableRows the rows whose record-image column holds nothing, which is what lets a
     *                            keyed read <em>prove</em> an absence before it reports one. The
     *                            three components of {@code DIS-GROUP-KEY} live inside the record
     *                            image, so a row with no image has no knowable key
     */
    record Statements(String selectByKey, String probeUnreadableRows) {
    }

    /**
     * Reads the record-image column's name from a result set's metadata, without consuming a row.
     *
     * @param resultSet the empty result set the probe produced
     * @return the column's name, or {@code null} if the backend describes none
     * @throws SQLException if the driver cannot supply the metadata
     */
    private static String extractRecordImageColumnName(ResultSet resultSet) throws SQLException {
        return DatasetRelation.recordImageColumnOf(resultSet.getMetaData());
    }

    /**
     * Maps one result-set row to its record image, through the configured representation.
     *
     * <p>The value is <strong>not</strong> trimmed: the trailing 28 bytes of this record are
     * {@code FILLER} and they are part of it, and in every row of
     * {@code app/data/ASCII/discgrp.txt} they are zeros rather than spaces.
     *
     * @return a mapper yielding each row's record image, or {@code null} for a row whose column holds
     *         no value
     */
    private RowMapper<byte[]> recordImageMapper() {
        return (resultSet, rowNumber) -> recordImageForm.readImage(resultSet,
                DatasetRelation.RECORD_IMAGE_COLUMN_INDEX, codec.charset());
    }

    /**
     * Builds a one-row statement whose single parameter is a keyed {@code LIKE} pattern.
     *
     * <p>The row limit is set on the statement rather than expressed as a row-limiting clause, so no
     * dialect syntax appears anywhere here. A driver that declines the limit costs efficiency and
     * nothing else: the first row is taken and the rest ignored.
     *
     * @param sql     the statement text
     * @param pattern the escaped pattern from {@link KeySpan#pattern(String)}
     * @return a creator for the prepared, limited and bound statement
     */
    private PreparedStatementCreator firstRowMatching(String sql, String pattern) {
        return connection -> {
            PreparedStatement statement = connection.prepareStatement(sql);
            statement.setMaxRows(1);
            recordImageForm.bindOperand(statement, 1, pattern, codec.charset());
            return statement;
        };
    }

    /**
     * Renders a disclosure-group key for a log line: one line, with the account group masked.
     *
     * <p>{@code DIS-GROUP-KEY} is three fields ({@code app/cpy/CVTRA02Y.cpy:5-8}), and they do not
     * carry the same risk. {@code DIS-ACCT-GROUP-ID X(10)} identifies the account group whose rate
     * is being looked up, so it is masked. {@code DIS-TRAN-TYPE-CD X(02)} and
     * {@code DIS-TRAN-CAT-CD 9(04)} are classification codes drawn from small fixed code tables -
     * they identify no account and no customer - so they stay legible, which is what makes the log
     * line useful for telling "the rate table has no row for this category" apart from "the dataset
     * refused the read".
     *
     * <p>The whole rendering also passes through {@link DiagnosticText#singleLine(String)}, because
     * the key arrives from storage rather than from a code table: a control character in those bytes
     * would otherwise let a stored value inject a line break and forge a second log record
     * (CWE-117).
     *
     * @param keyImage the key as read or composed; may be shorter than the declared width if the
     *                 caller is reporting a malformed value, and may be {@code null}
     * @return a single-line rendering safe to log
     */
    private static String keyForDiagnostics(String keyImage) {
        if (keyImage == null) {
            return DiagnosticText.ABSENT;
        }
        int split = Math.min(DisclosureGroupRecord.DIS_ACCT_GROUP_ID_LENGTH, keyImage.length());
        String group = keyImage.substring(0, split);
        String codes = keyImage.substring(split);
        return DiagnosticText.masked(group) + DiagnosticText.singleLine(codes);
    }

    /**
     * Performs the keyed read for a handle that opened successfully.
     *
     * @param sql      the statements the handle's open resolved
     * @param keyImage the 16-character key
     * @return the outcome; never {@code null}
     */
    private DisclosureGroupRead readByKey(Statements sql, String keyImage) {
        List<byte[]> rows;
        try {
            rows = jdbcTemplate.query(firstRowMatching(sql.selectByKey(), KEY_SPAN.pattern(keyImage)),
                    recordImageMapper());
        } catch (DataAccessException translated) {
            // The sanitized summary only; see the note in open() for why the throwable is not passed.
            LOG.error("Could not read key '" + keyForDiagnostics(keyImage)
                    + "' from the disclosure group dataset '"
                    + datasetName + "' - "
                    + DatasetRelation.BackendDiagnostic.of(translated).describe()
                    + "; reporting file status "
                    + FileStatus.toStatusImage(PERMANENT_ERROR_STATUS));
            return DisclosureGroupRead.failed(PERMANENT_ERROR_STATUS);
        }

        if (rows.isEmpty()) {
            // The INVALID KEY condition of app/cbl/CBACT04C.cbl:417-419. An EXPECTED outcome and the
            // signal to retry with the DEFAULT group, so it is reported and never thrown - but only
            // once the absence has been PROVED. All three components of DIS-GROUP-KEY live inside the
            // record image (app/cpy/CVTRA02Y.cpy:5-8), so a row whose record-image column holds
            // nothing has no knowable key and the keyed predicate cannot match it. Reporting '23'
            // while such a row sits in the dataset sends 1200-GET-INTEREST-RATE to the DEFAULT group
            // and charges a rate the account's own group may well define - which is a money
            // difference, not a diagnostic one.
            return provenAbsence(sql.probeUnreadableRows(), keyImage);
        }

        byte[] image = rows.get(0);
        if (image == null) {
            LOG.error("The disclosure group dataset '" + datasetName + "' presented key '"
                    + keyForDiagnostics(keyImage)
                    + "' with no record image at column position "
                    + DatasetRelation.RECORD_IMAGE_COLUMN_INDEX + "; reporting file status "
                    + FileStatus.toStatusImage(PERMANENT_ERROR_STATUS)
                    + " rather than " + FileStatus.NOT_FOUND + ", because a row that is present and "
                    + "unreadable is not a missing record and must not send the caller to the "
                    + "DEFAULT group");
            return DisclosureGroupRead.failed(PERMANENT_ERROR_STATUS);
        }

        try {
            return DisclosureGroupRead.found(
                    DisclosureGroupRecord.decode(image, codec.charset()));
        } catch (IllegalArgumentException malformed) {
            // The measured width only, never the row's own bytes and never the rejection's message:
            // the row IS the data this log line must not carry (CWE-532), and the width is the whole
            // of what an operator needs. Same reasoning as the note in open().
            LOG.error("The disclosure group dataset '" + datasetName + "' presented key '"
                    + keyForDiagnostics(keyImage)
                    + "' as " + image.length + " byte(s) where app/cpy/CVTRA02Y.cpy declares "
                    + DisclosureGroupRecord.RECORD_LENGTH + "; reporting file status "
                    + FileStatus.toStatusImage(PERMANENT_ERROR_STATUS)
                    + " rather than padding or truncating it, because every field offset after the "
                    + "discrepancy would be wrong");
            return DisclosureGroupRead.failed(PERMANENT_ERROR_STATUS);
        }
    }

    /**
     * Reports the {@code INVALID KEY} condition only once no row of the rate table is
     * <strong>unreadable</strong>, and the permanent-error status when one is.
     *
     * <h2>Why this one matters in money rather than in diagnostics</h2>
     * <p>{@code app/cbl/CBACT04C.cbl:417-419} takes {@code '23'} from this dataset as "this account
     * group has no rate for this category" and retries the read under the {@code DEFAULT} group
     * ({@code :421-430}). So a keyed-empty result that is really an unreadable row does not merely
     * mislabel an error: it charges the default rate against an account whose own group may define a
     * different one, and the run completes and reports success.
     *
     * <p>The three components of {@code DIS-GROUP-KEY} all live inside the record image, so a row
     * whose record-image column holds nothing has no knowable key and the keyed predicate - a
     * comparison, and therefore {@code UNKNOWN} against a null - cannot match it. The absence is
     * therefore confirmed with one further row-limited read before it is reported, on the empty path
     * only; a read that found its rate is untouched.
     *
     * @param unreadableRowsProbe the statement selecting the rows with no record image
     * @param keyImage            the key that matched nothing, for the diagnostic - masked as ever
     * @return {@link DisclosureGroupRead#notFound()} when the absence is established, the
     *         permanent-error outcome when it is not; never {@code null}
     */
    private DisclosureGroupRead provenAbsence(String unreadableRowsProbe, String keyImage) {
        List<byte[]> unreadable;
        try {
            unreadable = jdbcTemplate.query(firstRow(unreadableRowsProbe), recordImageMapper());
        } catch (DataAccessException translated) {
            // The probe established nothing, so the absence stays unproved - and must not become the
            // DEFAULT-group retry. Reported on the arm a refused read is reported on.
            LOG.error("Could not establish that the disclosure group dataset '" + datasetName
                    + "' holds no unreadable row before reporting key '"
                    + keyForDiagnostics(keyImage) + "' as absent - "
                    + DatasetRelation.BackendDiagnostic.of(translated).describe()
                    + "; reporting file status "
                    + FileStatus.toStatusImage(PERMANENT_ERROR_STATUS)
                    + " rather than the INVALID KEY that would charge the DEFAULT rate");
            return DisclosureGroupRead.failed(PERMANENT_ERROR_STATUS);
        }
        // As on the keyed read above, the list itself is never null, so emptiness is the whole test.
        if (unreadable.isEmpty()) {
            // A genuine INVALID KEY: nothing matched the key and no row of the table is unreadable, so
            // the DEFAULT-group retry at :421-430 is the right next step.
            return DisclosureGroupRead.notFound();
        }
        LOG.error("A keyed read of the disclosure group dataset '" + datasetName + "' matched no row "
                + "for key '" + keyForDiagnostics(keyImage) + "', but the dataset holds a row with no "
                + "record image at column position " + DatasetRelation.RECORD_IMAGE_COLUMN_INDEX
                + " - and DIS-GROUP-KEY is part of that image, so that row's key cannot be known; "
                + "reporting file status " + FileStatus.toStatusImage(PERMANENT_ERROR_STATUS)
                + " rather than the INVALID KEY that would send this account to the DEFAULT group");
        return DisclosureGroupRead.failed(PERMANENT_ERROR_STATUS);
    }

    /**
     * Builds a statement that returns at most one row and binds no parameter: the shape the
     * unreadable-row probe needs.
     *
     * <p>The row limit is set on the {@link PreparedStatement} rather than expressed as a row-limiting
     * clause, exactly as {@link #firstRowMatching(String, String)} does it, so no dialect syntax
     * appears in any statement this class sends. One row settles the question the probe asks.
     *
     * @param sql the statement text
     * @return a creator for the prepared and limited statement
     */
    private PreparedStatementCreator firstRow(String sql) {
        return connection -> {
            PreparedStatement statement = connection.prepareStatement(sql);
            statement.setMaxRows(1);
            statement.setFetchSize(1);
            return statement;
        };
    }

    /**
     * Names a key in a log line without reproducing it.
     *
     * <p>Two reasons, and the first is the one that makes this a defect rather than a preference. The
     * key is assembled from {@code PIC X} spans read out of {@code TCATBALF} and {@code ACCTFILE}
     * ({@code DIS-ACCT-GROUP-ID X(10)}, {@code DIS-TRAN-TYPE-CD X(02)},
     * {@code DIS-TRAN-CAT-CD 9(04)}), and a {@code PIC X} span holds whatever bytes are in the record
     * - including a carriage return or a line feed. Concatenated raw, such a key ends the log entry
     * early and starts a line of the writer's choosing, so a forged entry can be planted in a file an
     * operator trusts (CWE-117). {@link SensitiveDiagnostics#maskIdentifier(String)} escapes control
     * characters in the part it reveals and replaces the rest, so no byte of the record reaches the
     * line unexamined.
     *
     * <p>Second, the leading span links the failure to a set of accounts, and a log file is retained
     * longer and read more widely than the dataset it describes (CWE-532). The read itself always uses
     * the real key; only its rendering is masked. This is the same treatment
     * {@code TranCatBalRepository} gives its own key image, deliberately, so one policy covers every
     * key this module logs.
     *
     * @param keyImage the 16-character key image
     * @return a phrase naming the key, safe to log
     */
    private static String describeKey(String keyImage) {
        return "key '" + SensitiveDiagnostics.maskIdentifier(keyImage) + "'";
    }

    /**
     * Validates the {@code DISCGRP} binding's geometry against {@code app/cpy/CVTRA02Y.cpy}.
     *
     * @param datasetBindings the catalogue
     * @return the validated binding
     * @throws IllegalStateException if the binding is absent or its geometry disagrees with the
     *                               copybook
     */
    private static DatasetBinding requireDiscgrpGeometry(DatasetBindings datasetBindings) {
        DatasetBinding binding = datasetBindings.binding(AccountInterestCalcJob.DISCGRP_DD_NAME);
        if (binding.recordLength() != DisclosureGroupRecord.RECORD_LENGTH) {
            throw new IllegalStateException("carddemo.datasets." + AccountInterestCalcJob.DISCGRP_DD_NAME
                    + ".record-length is " + binding.recordLength()
                    + " but app/cpy/CVTRA02Y.cpy declares " + DisclosureGroupRecord.RECORD_LENGTH
                    + " bytes. A record width the layout disagrees with makes every field offset "
                    + "wrong.");
        }
        if (binding.keyLength() == null
                || binding.keyLength() != DisclosureGroupRecord.DIS_GROUP_KEY_LENGTH) {
            throw new IllegalStateException("carddemo.datasets." + AccountInterestCalcJob.DISCGRP_DD_NAME
                    + ".key-length must be " + DisclosureGroupRecord.DIS_GROUP_KEY_LENGTH
                    + " - DIS-ACCT-GROUP-ID X(10) + DIS-TRAN-TYPE-CD X(02) + DIS-TRAN-CAT-CD 9(04), "
                    + "app/cpy/CVTRA02Y.cpy:5-8 - but it is " + binding.keyLength()
                    + ". Seventeen is CVTRA01Y's TRAN-CAT-KEY; both records are 50 bytes, so the "
                    + "confusion is invisible to a width check and would shift DIS-INT-RATE by one "
                    + "byte.");
        }
        if (binding.keyOffsetOrZero() != 0) {
            throw new IllegalStateException("carddemo.datasets." + AccountInterestCalcJob.DISCGRP_DD_NAME
                    + ".key-offset is " + binding.keyOffsetOrZero()
                    + " but DIS-GROUP-KEY begins the record, so its offset is 0.");
        }
        return binding;
    }

    /**
     * Validates the configured dataset name and returns it verbatim.
     *
     * @param candidate the {@code dsname} component of the resolved binding
     * @return {@code candidate}, unchanged
     * @throws IllegalStateException if {@code candidate} is {@code null} or blank
     */
    private static String requireUsableDatasetName(String candidate) {
        if (candidate == null || candidate.isBlank()) {
            throw new IllegalStateException("The dataset binding for DD name '" + AccountInterestCalcJob.DISCGRP_DD_NAME
                    + "' declares no dataset name. Set carddemo.datasets." + AccountInterestCalcJob.DISCGRP_DD_NAME
                    + ".dsname; this access path composes its statements from configuration alone "
                    + "and hard-codes no dataset name.");
        }
        // The grammar lives in DatasetRelation, so what a dataset name may contain is stated once
        // for the whole module rather than restated - differently - here.
        return DatasetRelation.requireDatasetName(candidate);
    }

    /**
     * Validates the discovered record-image column name and returns it verbatim.
     *
     * @param candidate the name the metadata probe reported, possibly {@code null}
     * @return {@code candidate}, unchanged
     * @throws IllegalStateException if {@code candidate} is {@code null} or blank
     */
    private static String requireUsableColumnName(String candidate) {
        if (candidate == null || candidate.isBlank()) {
            throw new IllegalStateException("The backend describes the disclosure group dataset with "
                    + "no usable column at position " + DatasetRelation.RECORD_IMAGE_COLUMN_INDEX
                    + ", so there is no record-image column to read. Every dataset in this module is "
                    + "reached as a single-column relation whose one column holds the whole "
                    + DisclosureGroupRecord.RECORD_LENGTH + "-byte record image; no column name is "
                    + "invented here to paper over a relation that is not.");
        }
        return candidate;
    }

    /**
     * One open {@code DISCGRP} file over JDBC.
     *
     * <p>The handle <em>is</em> the file: its open status and its resolved statement belong to it and not
     * to the shared access path, so two runs never interfere (practice B9, gate G53). A handle whose open
     * failed carries {@link DisclosureGroupRepository#PERMANENT_ERROR_STATUS} and reports that same
     * failure from every operation, so a caller that ignored the open status cannot mistake a dataset it
     * never reached for one that was empty.
     */
    static final class JdbcDisclosureGroupFile implements DisclosureGroupFile {

        /** The access path that opened this file. */
        private final DisclosureGroupRepository access;

        /** What the open reported. */
        private final String openStatus;

        /** The statements the open resolved, or {@code null} when the open failed. */
        private final DisclosureGroupRepository.Statements statements;

        /** Whether this handle has been closed; a close is idempotent. */
        private boolean closed;

        /**
         * @param access     the access path
         * @param openStatus the status the open reported
         * @param statements the resolved statements, or {@code null} when the open failed
         */
        JdbcDisclosureGroupFile(DisclosureGroupRepository access, String openStatus,
                DisclosureGroupRepository.Statements statements) {
            this.access = access;
            this.openStatus = openStatus;
            this.statements = statements;
        }

        @Override
        public String openStatus() {
            return openStatus;
        }

        @Override
        public DisclosureGroupRead readByKey(String keyImage) {
            Objects.requireNonNull(keyImage, "A DIS-GROUP-KEY image is required to read DISCGRP by key; "
                    + "app/cbl/CBACT04C.cbl:210-212 sets all three components before the READ");
            if (keyImage.length() != DisclosureGroupRecord.DIS_GROUP_KEY_LENGTH) {
                throw new IllegalArgumentException("DIS-GROUP-KEY is "
                        + DisclosureGroupRecord.DIS_GROUP_KEY_LENGTH + " characters "
                        + "(app/cpy/CVTRA02Y.cpy:5-8); '" + keyImage + "' is " + keyImage.length()
                        + ". A short key would silently become a prefix match over more records than it "
                        + "names.");
            }
            if (closed) {
                throw new IllegalStateException("This DISCGRP file has been closed, so it can read "
                        + "nothing more. app/cbl/CBACT04C.cbl closes it once, at :561, after the loop - "
                        + "reading afterwards is a defect in the caller and not a file status.");
            }
            if (statements == null) {
                // The open never reached the dataset. Report the same failure rather than a fresh one.
                return DisclosureGroupRead.failed(DisclosureGroupRepository.PERMANENT_ERROR_STATUS);
            }
            return access.readByKey(statements, keyImage);
        }

        @Override
        public String closeFile() {
            closed = true;
            if (statements != null) {
                return FileStatus.OK;
            }
            // A CLOSE of a file that is not open is not a success, which is what COBOL reports too.
            return DisclosureGroupRepository.PERMANENT_ERROR_STATUS;
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}
