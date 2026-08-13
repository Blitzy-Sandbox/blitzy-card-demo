package com.vsergeychik.carddemo.card;

import com.vsergeychik.carddemo.card.model.CardRecord;
import com.vsergeychik.carddemo.common.DatasetObservation;
import com.vsergeychik.carddemo.common.DatasetRelation;
import com.vsergeychik.carddemo.common.DatasetRelation.BackendDiagnostic;
import com.vsergeychik.carddemo.common.DatasetRelation.KeySpan;
import com.vsergeychik.carddemo.common.FileStatus;
import com.vsergeychik.carddemo.common.FileStatus.Outcome;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.RecordImageForm;
import com.vsergeychik.carddemo.common.FixedWidthRecord;
import com.vsergeychik.carddemo.common.FixedWidthRecord.FieldSpan;
import com.vsergeychik.carddemo.config.CobolCharsetConfig;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBinding;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBindings;
import com.vsergeychik.carddemo.common.DatasetUnitOfWork;

import java.nio.charset.Charset;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.core.PreparedStatementSetter;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.stereotype.Repository;

/**
 * The one data-access component for the CardDemo card master: the {@code CARDDAT} base KSDS and the
 * {@code CARDAIX} alternate-index path over it.
 *
 * <p>The three responses that map to no batch status map to none here either: inventing one for them would
 * fabricate behaviour the COBOL never had, and {@link FileStatus#batchStatusOfCicsResp(int)} reports the
 * absence truthfully instead.
 */
@Repository
public class CardRepository {
    private static final Log LOG = LogFactory.getLog(CardRepository.class);

    /**
     * The configuration key, and CICS {@code FILE} name, of the card base cluster:
     * {@code app/csd/CARDDEMO.CSD:25}.
     */
    public static final String BASE_DD_NAME = "CARDDAT";

    /**
     * The configuration key, and CICS {@code FILE} name, of the alternate-index path over the card base:
     * {@code app/csd/CARDDEMO.CSD:13}.
     */
    public static final String ALTERNATE_INDEX_DD_NAME = "CARDAIX";

    /**
     * The batch DD name of the same base cluster: {@code CARDFILE}.
     */
    public static final String BATCH_DD_NAME = "CARDFILE";

    /**
     * The declared width of a CICS file-name literal in the legacy programs: {@code PIC X(8)}.
     */
    public static final int CICS_FILE_NAME_LENGTH = 8;

    /**
     * The base cluster's CICS file-name literal, {@value #CICS_FILE_NAME_LENGTH} characters including the
     * trailing space: three programs declare it identically, at {@code app/cbl/COCRDLIC.cbl:213-214},
     * {@code app/cbl/COCRDSLC.cbl:187-188} and {@code app/cbl/COCRDUPC.cbl:251-252}.
     */
    public static final String BASE_CICS_FILE_NAME = "CARDDAT ";

    /**
     * The alternate-index path's CICS file-name literal, {@value #CICS_FILE_NAME_LENGTH} characters
     * including the trailing space, declared identically at {@code app/cbl/COCRDLIC.cbl:215-217},
     * {@code app/cbl/COCRDSLC.cbl:189-190}, {@code app/cbl/COCRDUPC.cbl:253-254} and
     * {@code app/cbl/COACTVWC.cbl:190-191}.
     */
    public static final String ALTERNATE_INDEX_CICS_FILE_NAME = "CARDAIX ";

    public static final String DATASET_CHARSET_BEAN_NAME =
            CobolCharsetConfig.DATASET_CHARSET_BEAN_NAME;

    /**
     * The fixed width of a card record in bytes, {@link #RECORD_LENGTH}, taken from
     * {@link CardRecord#RECORD_LENGTH} so that one copybook-derived constant governs the model, the
     * statements and the length checks alike.
     */
    public static final int RECORD_LENGTH = CardRecord.RECORD_LENGTH;

    private static final String RECORD_IMAGE_SUBJECT =
            "the stored CARD-RECORD image displayed by app/cbl/CBACT02C.cbl:78";

    public static final int RECORD_IMAGE_COLUMN_INDEX = DatasetRelation.RECORD_IMAGE_COLUMN_INDEX;

    /**
     * The reason code reported when the backend supplies none: {@value}.
     */
    public static final int NO_REASON_CODE = 0;

    /**
     * The {@code ERROR-OPNAME} value the legacy code reports for a failed read:
     * {@code app/cbl/COCRDSLC.cbl:767}, {@code app/cbl/COCRDUPC.cbl:1407} and
     * {@code app/cbl/COCRDLIC.cbl:1226}, {@code :1250}, {@code :1312} and {@code :1365} all move the
     * literal {@code 'READ'}.
     */
    public static final String READ_OPERATION_NAME = "READ";

    public static final String REWRITE_OPERATION_NAME = "REWRITE";

    private static final int SINGLE_ROW = 1;

    private static final int DUPLICATE_DETECTION_ROW_LIMIT = 2;

    private static final int FAN_OUT_PROBE_LIMIT = 2;

    private static final FieldSpan BASE_KEY_FIELD = CardRecord.cardDatPrimaryKeySpan();

    private static final FieldSpan ALTERNATE_KEY_FIELD = CardRecord.cardAixAlternateKeySpan();

    private static final KeySpan BASE_KEY_SPAN =
            new KeySpan(BASE_KEY_FIELD.offset(), BASE_KEY_FIELD.length());

    private static final KeySpan ALTERNATE_KEY_SPAN =
            new KeySpan(ALTERNATE_KEY_FIELD.offset(), ALTERNATE_KEY_FIELD.length());

    private final JdbcTemplate jdbcTemplate;

    private final FixedWidthCodec codec;

    private final RecordImageForm recordImageForm;

    private final DatasetRelation baseRelation;

    private final DatasetRelation alternateIndexRelation;

    private volatile BaseStatements baseStatements;

    private volatile AlternateStatements alternateStatements;

    @Autowired
    public CardRepository(JdbcTemplate jdbcTemplate,
                          DatasetBindings datasetBindings,
                          @Qualifier(DATASET_CHARSET_BEAN_NAME) Charset datasetCharset,
                          RecordImageForm recordImageForm) {
        this(jdbcTemplate,
                datasetBindings,
                new FixedWidthCodec(Objects.requireNonNull(datasetCharset,
                        "A dataset charset is required: a fixed-width mainframe record is bytes in a "
                                + "specific code page, so the code page is stated explicitly and never "
                                + "taken from the platform")),
                recordImageForm);
    }

    public CardRepository(JdbcTemplate jdbcTemplate,
                          DatasetBindings datasetBindings,
                          FixedWidthCodec codec,
                          RecordImageForm recordImageForm) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "A JdbcTemplate is required: the card "
                + "file is reached through the module's single template");
        this.codec = Objects.requireNonNull(codec, "A fixed-width codec is required: it carries the "
                + "dataset code page and owns the MOVE semantics every key is built with");
        Objects.requireNonNull(datasetBindings, "The carddemo.datasets binding catalogue is required: "
                + "dataset names live in configuration and are never written in Java");
        this.recordImageForm = Objects.requireNonNull(recordImageForm, "A record-image representation is "
                + "required: whether this deployment's driver presents a record image as characters or as "
                + "bytes is stated once, by " + RecordImageForm.FORM_PROPERTY + ", and never decided per "
                + "repository");
        RecordImageForm.requireSingleByteCodePage(this.codec.charset());

        DatasetBinding base = requireCardRecordBinding(datasetBindings, BASE_DD_NAME);
        DatasetBinding alternateIndex = requireCardRecordBinding(datasetBindings,
                ALTERNATE_INDEX_DD_NAME);
        requireAlternateIndexOverBase(alternateIndex);

        this.baseRelation = DatasetRelation.of(
                requireUsableDatasetName(base.dsname(), BASE_DD_NAME), RECORD_LENGTH);
        this.alternateIndexRelation = DatasetRelation.of(
                requireUsableDatasetName(alternateIndex.dsname(), ALTERNATE_INDEX_DD_NAME),
                RECORD_LENGTH);
    }

    private CardRepository(CardRepository source, DatasetRelation baseRelation) {
        this.jdbcTemplate = source.jdbcTemplate;
        this.codec = source.codec;
        this.recordImageForm = source.recordImageForm;
        this.baseRelation = baseRelation;
        this.alternateIndexRelation = source.alternateIndexRelation;
    }

    /**
     * Returns this repository addressing the base cluster a caller's own DD binding names.
     *
     * @param binding the caller's resolved binding, normally from
     *     {@code BatchConfig.datasetBinding(jobKey, ddName)}
     * @param ddName the DD name it was resolved for; used only to say which key is at fault
     * @return this repository, or one addressing the binding's dataset; never {@code null}
     * @throws NullPointerException if either argument is {@code null}
     * @throws IllegalStateException if the binding declares a record width other than
     *     {@link #RECORD_LENGTH}, or no usable dataset name
     */
    public CardRepository addressing(DatasetBinding binding, String ddName) {
        Objects.requireNonNull(ddName, "A DD name is required: it is what a diagnostic names when the "
                + "binding is at fault");
        Objects.requireNonNull(binding, "A resolved dataset binding is required for DD name '" + ddName
                + "': a batch job reads through the DD its JCL declares, not through the CICS file name "
                + "this repository resolved at construction");
        if (binding.recordLength() != RECORD_LENGTH) {
            throw new IllegalStateException("The dataset binding for '" + ddName + "' declares a record "
                    + "length of " + binding.recordLength() + ", but the card file is decoded by "
                    + "absolute offset against a " + RECORD_LENGTH + "-byte record (app/cpy/CVACT02Y.cpy). "
                    + "Correct carddemo.datasets." + ddName + ".record-length to " + RECORD_LENGTH + ".");
        }
        String dsname = requireUsableDatasetName(binding.dsname(), ddName);
        if (dsname.equals(baseRelation.dsname())) {
            return this;
        }
        return new CardRepository(this, DatasetRelation.of(dsname, RECORD_LENGTH));
    }

    /**
     * The card base cluster's dataset name as configuration declared it.
     *
     * @return the value of {@code carddemo.datasets.CARDDAT.dsname}; never blank
     */
    public String baseDatasetName() {
        return baseRelation.dsname();
    }

    /**
     * The alternate-index path's dataset name as configuration declared it.
     *
     * @return the value of {@code carddemo.datasets.CARDAIX.dsname}; never blank
     */
    public String alternateIndexDatasetName() {
        return alternateIndexRelation.dsname();
    }

    /**
     * The statement that describes the base cluster without transferring a row.
     *
     * @return the describe statement over the base cluster
     */
    public String describeBaseStatement() {
        return baseRelation.describeStatement();
    }

    /**
     * The statement that describes the alternate-index path without transferring a row.
     *
     * @return the describe statement over the alternate-index path
     */
    public String describeAlternateIndexStatement() {
        return alternateIndexRelation.describeStatement();
    }

    BaseStatements resolvedBaseStatements() {
        return baseStatements;
    }

    AlternateStatements resolvedAlternateStatements() {
        return alternateStatements;
    }

    /**
     * Reads one card by its card number, from the base cluster.
     *
     * <p>The COBOL is: {@code WS-CARD-RID-CARDNUM} is {@code PIC X(16)} ({@code COCRDSLC:98},
     * {@code COCRDUPC:129}), so the argument is subjected to an alphanumeric {@code MOVE} into a
     * sixteen-character receiver: a shorter value is padded on the right with spaces, a longer one is
     * truncated on the right.
     *
     * @param cardNumber the card number to read, moved into the sixteen-character key
     * @return the read outcome: the record on the normal arm, no record on the not-found arm, and the raw
     *     response pair on either of the failure arms
     * @throws NullPointerException if {@code cardNumber} is {@code null}
     */
    public CardReadResult readByCardNumber(String cardNumber) {
        return readOnBaseCluster(cardNumber, false);
    }

    /**
     * Reads one card by its card number and holds it locked for a rewrite.
     *
     * @param cardNumber the card number to read and lock, moved into the sixteen-character key
     * @return the read outcome
     * @throws NullPointerException if {@code cardNumber} is {@code null}
     */
    public CardReadResult readForUpdateByCardNumber(String cardNumber) {
        DatasetUnitOfWork.requireActive("A read-for-update of " + BASE_CICS_FILE_NAME.trim(),
                baseRelation.dsname());
        return readOnBaseCluster(cardNumber, true);
    }

    /**
     * Reads one card by account id, through the alternate-index path.
     *
     * @param accountId the account id, moved into the eleven-digit key
     * @return the read outcome
     * @throws IllegalArgumentException if {@code accountId} is negative
     */
    public CardReadResult readByAccountIdViaAltIndex(long accountId) {
        return readOnAlternateIndex(codec.movePic9(accountId, ALTERNATE_KEY_SPAN.length()));
    }

    /**
     * Reads one card by account id through the alternate-index path, taking the account id as digits.
     *
     * <p>The overload exists because the callers hold the account id in two COBOL views of one eleven-byte
     * span - {@code CC-ACCT-ID PIC X(11)} and {@code CC-ACCT-ID-N REDEFINES CC-ACCT-ID PIC 9(11)}
     * ({@code app/cpy/CVCRD01Y.cpy}) - and both must reach the key identically.
     *
     * @param accountIdDigits the account id as digits, of any length
     * @return the read outcome
     * @throws NullPointerException if {@code accountIdDigits} is {@code null}
     * @throws IllegalArgumentException if {@code accountIdDigits} is not all digits
     */
    public CardReadResult readByAccountIdViaAltIndex(String accountIdDigits) {
        Objects.requireNonNull(accountIdDigits, "An account id is required to read the card file "
                + "through the alternate-index path; to search for zeroes, pass zeroes");
        return readOnAlternateIndex(codec.movePic9(accountIdDigits, ALTERNATE_KEY_SPAN.length()));
    }

    /**
     * Rewrites a card record in place, at full record width.
     *
     * @param record the record to write
     * @return the write outcome: normal when exactly one record was replaced, otherwise the failure arm
     *     carrying the raw response pair
     * @throws NullPointerException if {@code record} is {@code null}
     * @throws IllegalStateException if no unit of work is open, in which case nothing has been attempted
     */
    public CardWriteResult rewrite(CardRecord record) {
        Objects.requireNonNull(record, "A card record is required to rewrite one; there is no "
                + "partial-record rewrite, because CARD-UPDATE-RECORD is a full " + RECORD_LENGTH
                + "-byte record at app/cbl/COCRDUPC.cbl:314-321");
        DatasetUnitOfWork.requireActiveToPersist("A rewrite of " + BASE_CICS_FILE_NAME.trim()
                + ", which EXEC CICS REWRITE issues against the record the preceding READ ... UPDATE "
                + "still holds (app/cbl/COCRDUPC.cbl:1477-1492)", baseRelation.dsname());

        byte[] recordImage = record.encode(codec.charset());
        String keyPattern = BASE_KEY_SPAN.pattern(baseKeyOf(record.cardNum()));
        PreparedStatementSetter binder = parameters -> {
            recordImageForm.bindImage(parameters, 1, recordImage, codec.charset());
            recordImageForm.bindOperand(parameters, 2, keyPattern, codec.charset());
        };

        int selected;
        BaseStatements sql;
        try {
            sql = resolveBaseStatements();
            selected = fetch(sql.selectForUpdateByCardNumber(), keyPattern, FAN_OUT_PROBE_LIMIT)
                    .rowCount();
        } catch (DataAccessException rejected) {
            return CardWriteResult.failed(
                    responseOf(logRefusal(REWRITE_OPERATION_NAME, BASE_CICS_FILE_NAME,
                            "while establishing how many records its key selects", rejected)));
        }
        if (selected != SINGLE_ROW) {
            LOG.error("The key of a " + BASE_CICS_FILE_NAME.trim() + " record selects " + selected
                    + " row(s) where exactly " + SINGLE_ROW + " was expected; reporting the "
                    + "invalid-request response, which is what CICS reports for a REWRITE with no held "
                    + "record, without issuing the write. No row has been changed.");
            return CardWriteResult.failed(FileStatus.INVREQ,
                    DatasetObservation.matchingRows(selected));
        }

        int replaced;
        try {
            replaced = jdbcTemplate.update(sql.rewrite(), binder);
        } catch (DataAccessException rejected) {
            return CardWriteResult.failed(
                    responseOf(logRefusal(REWRITE_OPERATION_NAME, BASE_CICS_FILE_NAME,
                            "at full record width", rejected)));
        }
        if (replaced == SINGLE_ROW) {
            return CardWriteResult.normal();
        }
        if (replaced == 0) {
            LOG.error("A rewrite of " + BASE_CICS_FILE_NAME.trim() + " replaced no record although its "
                    + "key selected one when it was checked; reporting the invalid-request response");
            return CardWriteResult.failed(FileStatus.INVREQ, DatasetObservation.replacedRows(replaced));
        }
        throw DatasetUnitOfWork.commitRefusal(
                "The rewrite of a record of " + BASE_CICS_FILE_NAME.trim(),
                replaced + " rows were replaced where the key selected exactly one when it was checked "
                        + "under a row lock");
    }

    /**
     * Positions a browse at or after a card number and returns the handle that walks it.
     *
     * @param cardNumber the key to position at, moved into the sixteen-character key
     * @param direction which way the browse will be walked
     * @return a fresh handle, positioned and not yet read from
     * @throws NullPointerException if either argument is {@code null}
     */
    public CardBrowse startBrowse(String cardNumber, BrowseDirection direction) {
        Objects.requireNonNull(direction, "A browse direction is required: the legacy code issues a "
                + "separate STARTBR for each direction (app/cbl/COCRDLIC.cbl:1129 and :1273), so a "
                + "browse is never direction-less");
        return new CardBrowse(this, baseKeyOf(cardNumber), direction);
    }

    /**
     * Opens the base cluster for a sequential pass and reports whether it could be opened: the batch entry
     * point, for {@code OPEN INPUT CARDFILE-FILE} at {@code app/cbl/CBACT02C.cbl:120}.
     *
     * <p>{@code COCRDLIC} issues {@code EXEC CICS STARTBR}, captures the response and never tests it, so
     * {@code startBrowse} reports nothing and makes no call - surfacing a status there would invite a
     * caller to branch on something the COBOL ignores.
     *
     * @param cardNumber the key to position at or after, moved to the declared key width
     * @param direction which way the pass is walked
     * @return a handle whose {@link CardBrowse#openResp()} reports whether the dataset was opened; never
     *     {@code null}
     * @throws NullPointerException if {@code direction} is {@code null}
     */
    public CardBrowse openBrowse(String cardNumber, BrowseDirection direction) {
        Objects.requireNonNull(direction, "A browse direction is required: the legacy code issues a "
                + "separate STARTBR for each direction (app/cbl/COCRDLIC.cbl:1129 and :1273), so a "
                + "browse is never direction-less");
        String key = baseKeyOf(cardNumber);
        try {
            composeBaseStatements();
        } catch (DataAccessException translated) {
            LOG.error("Could not open a sequential pass over the " + BASE_DD_NAME + " base cluster - "
                    + BackendDiagnostic.of(translated).describe() + "; reporting CICS response "
                    + FileStatus.NOTOPEN + " to the caller, which is the arm that displays "
                    + "ERROR OPENING CARDFILE and abends");
            return new CardBrowse(this, key, direction, FileStatus.NOTOPEN);
        } catch (IllegalStateException unusable) {
            LOG.error("Could not open a sequential pass over the " + BASE_DD_NAME + " base cluster: "
                    + unusable.getMessage() + "; reporting CICS response " + FileStatus.NOTOPEN
                    + " to the caller");
            return new CardBrowse(this, key, direction, FileStatus.NOTOPEN);
        }
        return new CardBrowse(this, key, direction, FileStatus.NORMAL);
    }

    private CardReadResult readOnBaseCluster(String cardNumber, boolean locking) {
        String pattern = BASE_KEY_SPAN.pattern(baseKeyOf(cardNumber));
        try {
            BaseStatements sql = resolveBaseStatements();
            String statement = locking ? sql.selectForUpdateByCardNumber() : sql.selectByCardNumber();
            FetchedRows rows = fetch(statement, pattern, DUPLICATE_DETECTION_ROW_LIMIT);
            return provenAbsence(classifyRead(rows, false), sql.probeUnreadableRows(),
                    BASE_CICS_FILE_NAME);
        } catch (DataAccessException rejected) {
            return failedRead(READ_OPERATION_NAME, BASE_CICS_FILE_NAME,
                    locking ? "with the UPDATE option" : "without the UPDATE option", rejected);
        }
    }

    private CardReadResult readOnAlternateIndex(String alternateKey) {
        String pattern = ALTERNATE_KEY_SPAN.pattern(alternateKey);
        try {
            AlternateStatements sql = resolveAlternateStatements();
            FetchedRows rows = fetch(sql.selectByAccountId(), pattern, DUPLICATE_DETECTION_ROW_LIMIT);
            return provenAbsence(classifyRead(rows, true), sql.probeUnreadableRows(),
                    ALTERNATE_INDEX_CICS_FILE_NAME);
        } catch (DataAccessException rejected) {
            return failedRead(READ_OPERATION_NAME, ALTERNATE_INDEX_CICS_FILE_NAME,
                    "through the alternate-index path", rejected);
        }
    }

    private CardReadResult provenAbsence(CardReadResult classified, String unreadableRowsProbe,
                                         String fileName) {
        if (!classified.isNotFound()) {
            return classified;
        }
        FetchedRows unreadable = fetchUnparameterised(unreadableRowsProbe, SINGLE_ROW);
        if (unreadable.rowCount() == 0) {
            return classified;
        }
        LOG.error("A keyed read of " + fileName.trim() + " matched no row, but the relation holds a row "
                + "with no record image at position " + RECORD_IMAGE_COLUMN_INDEX + " - and CARD-NUM is "
                + "part of that image, so that row's key cannot be known; reporting the invalid-request "
                + "response rather than reporting as absent a record that may well be the one asked for");
        return CardReadResult.failed(FileStatus.INVREQ);
    }

    private FetchedRows fetchUnparameterised(String statement, int rowLimit) {
        PreparedStatementCreator creator = connection -> {
            PreparedStatement prepared = connection.prepareStatement(statement);
            prepared.setMaxRows(rowLimit);
            prepared.setFetchSize(rowLimit);
            return prepared;
        };
        ResultSetExtractor<FetchedRows> extractor = resultSet -> extractRows(resultSet, rowLimit);
        FetchedRows rows = jdbcTemplate.query(creator, extractor);
        return rows == null ? FetchedRows.empty() : rows;
    }

    private BrowseStep browseStep(String statement, String parameter, String operation) {
        return browseStep(keyedStatement(statement, parameter, SINGLE_ROW), operation);
    }

    private BrowseStep browseStepAfter(String statement, byte[] position, String operation) {
        return browseStep(imageStatement(statement, position, SINGLE_ROW), operation);
    }

    private BrowseStep browseStep(PreparedStatementCreator creator, String operation) {
        try {
            ResultSetExtractor<FetchedRows> extractor = resultSet -> extractRows(resultSet, SINGLE_ROW);
            FetchedRows fetched = jdbcTemplate.query(creator, extractor);
            FetchedRows rows = fetched == null ? FetchedRows.empty() : fetched;
            if (rows.rowCount() == 0) {
                return new BrowseStep(CardReadResult.endOfFile(), null);
            }
            return new BrowseStep(classifyRead(rows, false), rows.firstImage());
        } catch (DataAccessException rejected) {
            return new BrowseStep(
                    failedRead(operation, BASE_CICS_FILE_NAME, "during a browse", rejected), null);
        }
    }

    private PreparedStatementCreator imageStatement(String statement, byte[] image, int rowLimit) {
        return connection -> {
            PreparedStatement prepared = connection.prepareStatement(statement);
            prepared.setMaxRows(rowLimit);
            prepared.setFetchSize(rowLimit);
            recordImageForm.bindImage(prepared, 1, image, codec.charset());
            return prepared;
        };
    }

    private FetchedRows fetch(String statement, String key, int rowLimit) {
        PreparedStatementCreator creator = keyedStatement(statement, key, rowLimit);
        ResultSetExtractor<FetchedRows> extractor = resultSet -> extractRows(resultSet, rowLimit);
        FetchedRows rows = jdbcTemplate.query(creator, extractor);
        return rows == null ? FetchedRows.empty() : rows;
    }

    PreparedStatementCreator keyedStatement(String statement, String key, int rowLimit) {
        return connection -> {
            PreparedStatement prepared = connection.prepareStatement(statement);
            prepared.setMaxRows(rowLimit);
            prepared.setFetchSize(rowLimit);
            recordImageForm.bindOperand(prepared, 1, key, codec.charset());
            return prepared;
        };
    }

    FetchedRows extractRows(ResultSet resultSet, int rowLimit) throws SQLException {
        byte[] firstImage = null;
        int rowCount = 0;
        while (rowCount < rowLimit && resultSet.next()) {
            if (rowCount == 0) {
                firstImage = readRecordImage(resultSet);
            }
            rowCount++;
        }
        return new FetchedRows(firstImage, rowCount);
    }

    byte[] readRecordImage(ResultSet resultSet) throws SQLException {
        return recordImageForm.readImage(resultSet, RECORD_IMAGE_COLUMN_INDEX, codec.charset());
    }

    private CardReadResult classifyRead(FetchedRows rows, boolean alternateIndex) {
        if (rows.rowCount() == 0) {
            return CardReadResult.notFound();
        }
        if (!alternateIndex && rows.rowCount() > SINGLE_ROW) {
            // CARDDAT is a KSDS whose CARD-NUM is its unique primary key (app/csd/CARDDEMO.CSD), so more
            // than one match cannot arise in the legacy system and is an integrity defect in the backing
            // relation.
            LOG.error("A keyed read of " + BASE_CICS_FILE_NAME.trim() + " matched " + rows.rowCount()
                    + " rows, but CARD-NUM is the base cluster's unique primary key; reporting the "
                    + "invalid-request response rather than returning an arbitrary one of them as though "
                    + "it were the record. The backing relation needs a unique constraint on its key span");
            return CardReadResult.failed(FileStatus.INVREQ,
                    DatasetObservation.matchingRows(rows.rowCount()));
        }
        byte[] recordImage = rows.firstImage();
        if (recordImage == null) {
            LOG.error("A row of " + BASE_CICS_FILE_NAME.trim() + " carries no record image at "
                    + "position " + RECORD_IMAGE_COLUMN_INDEX + "; reporting the invalid-request "
                    + "response rather than reporting a record that is present as absent");
            return CardReadResult.failed(FileStatus.INVREQ);
        }
        if (recordImage.length != RECORD_LENGTH) {
            // It is NOT padded into shape: every row of app/data/ASCII/carddata.txt measures exactly 150,
            // so a short row here can only mean the backend is not serving this layout, and hiding that
            // would hide a real defect.
            LOG.error("A row of " + BASE_CICS_FILE_NAME.trim() + " is " + recordImage.length
                    + " byte(s) wide, but CARD-RECORD is declared " + RECORD_LENGTH
                    + " bytes by app/cpy/CVACT02Y.cpy; reporting a length error rather than decoding "
                    + "fields from offsets that would not be theirs");
            return CardReadResult.failed(FileStatus.LENGERR,
                    DatasetObservation.recordWidth(recordImage.length));
        }
        CardRecord record = CardRecord.decode(recordImage, codec);
        // INTO CARD-RECORD moves the whole record area, so DISPLAY CARD-RECORD (app/cbl/CBACT02C.cbl:78)
        // writes bytes this record model cannot reproduce: CARD-RECORD ends with FILLER X(59), which
        // carries no field and which a re-encode would therefore emit as fifty-nine spaces whatever the row
        // actually held.
        String storedImage = codec.decodeImage(recordImage, RECORD_IMAGE_SUBJECT);
        if (alternateIndex && rows.rowCount() > 1) {
            return CardReadResult.duplicateKey(record, storedImage);
        }
        return CardReadResult.normal(record, storedImage);
    }

    private String baseKeyOf(String cardNumber) {
        Objects.requireNonNull(cardNumber, "A card number is required to key the card file; a COBOL "
                + "alphanumeric field is never absent, so to search for spaces pass spaces");
        return codec.movePicX(cardNumber, BASE_KEY_SPAN.length());
    }

    private String keyOfImage(byte[] recordImage) {
        Objects.requireNonNull(recordImage, "A record image is required to read a key out of it");
        return new String(recordImage, 0, BASE_KEY_SPAN.length(), codec.charset());
    }

    // What the backend said is read from the backend; what the caller branches on is a CICS response value,
    // and the two are never confused for one another.

    private static int responseOf(BackendDiagnostic diagnostic) {
        return diagnostic.connectionFailure() || diagnostic.syntaxOrAccessViolation()
                ? FileStatus.NOTOPEN
                : FileStatus.INVREQ;
    }

    private static BackendDiagnostic logRefusal(String operation, String fileName, String qualifier,
                                               Throwable refusal) {
        BackendDiagnostic diagnostic = BackendDiagnostic.of(refusal);
        LOG.error("File error: " + operation + " on " + fileName.trim() + " " + qualifier
                + " was rejected - " + diagnostic.describe() + "; reporting response "
                + responseOf(diagnostic) + " and reason code " + NO_REASON_CODE + " to the caller");
        return diagnostic;
    }

    private static void requireOtherArm(int resp) {
        Outcome classified = FileStatus.outcomeOfCicsResp(resp);
        if (classified != Outcome.OTHER) {
            throw new IllegalArgumentException("CICS response " + resp + " classifies as "
                    + classified + ", which is an arm the guard chain names explicitly, so it "
                    + "cannot be reported as WHEN OTHER. Use the factory for that arm.");
        }
    }

    /**
     * Requires a value that could be a CICS reason code.
     *
     * @param resp2 the value to check
     * @throws IllegalArgumentException if it is negative
     */
    private static void requireReasonCode(int resp2) {
        if (resp2 < 0) {
            throw new IllegalArgumentException("A CICS reason code is " + resp2
                    + "; reason codes are non-negative, and a negative one means something that is not "
                    + "a reason code was reported as one. Report " + NO_REASON_CODE
                    + " and carry the measurement as a " + DatasetObservation.class.getSimpleName()
                    + " instead.");
        }
    }

    private static CardReadResult failedRead(String operation, String fileName, String qualifier,
                                             Throwable refusal) {
        return CardReadResult.failed(
                responseOf(logRefusal(operation, fileName, qualifier, refusal)));
    }

    private static DatasetBinding requireCardRecordBinding(DatasetBindings datasetBindings,
                                                           String ddName) {
        DatasetBinding binding = datasetBindings.binding(ddName);
        if (binding.recordLength() != RECORD_LENGTH) {
            throw new IllegalStateException("The dataset binding for '" + ddName + "' declares a record "
                    + "length of " + binding.recordLength() + ", but the card file is decoded by "
                    + "absolute offset against a " + RECORD_LENGTH + "-byte record: "
                    + "app/cpy/CVACT02Y.cpy declares CARD-RECORD as 16 + 11 + 3 + 50 + 10 + 1 + 59 = "
                    + RECORD_LENGTH + " and says so in its own (RECLN " + RECORD_LENGTH + ") header. "
                    + "Reading a differently sized record would place every field at an offset that is "
                    + "not its own, so the disagreement is rejected here rather than discovered in the "
                    + "data. Correct carddemo.datasets." + ddName + ".record-length to "
                    + RECORD_LENGTH + ".");
        }
        return binding;
    }

    private static void requireAlternateIndexOverBase(DatasetBinding alternateIndex) {
        String declaredBase = alternateIndex.base();
        if (declaredBase != null && !BASE_DD_NAME.equals(declaredBase)) {
            throw new IllegalStateException("The dataset binding for '" + ALTERNATE_INDEX_DD_NAME
                    + "' declares that it indexes '" + declaredBase + "', but it is the alternate-index "
                    + "path over '" + BASE_DD_NAME + "' - one cluster reached by two keys, per "
                    + "app/csd/CARDDEMO.CSD:13-14 and :25-26. This repository owns that base and its "
                    + "path and nothing else, so a path over a different base belongs to a different "
                    + "repository. Correct carddemo.datasets." + ALTERNATE_INDEX_DD_NAME + ".base to '"
                    + BASE_DD_NAME + "'.");
        }
        String declaredKey = alternateIndex.alternateKey();
        if (declaredKey != null && !ALTERNATE_KEY_FIELD.name().equals(declaredKey)) {
            throw new IllegalStateException("The dataset binding for '" + ALTERNATE_INDEX_DD_NAME
                    + "' declares its alternate key as '" + declaredKey + "', but the path is keyed on "
                    + ALTERNATE_KEY_FIELD.name() + " - the field app/cbl/COCRDSLC.cbl:785 supplies as "
                    + "RIDFLD(WS-CARD-RID-ACCT-ID). Reading it under a different key would return the "
                    + "wrong records. Correct carddemo.datasets." + ALTERNATE_INDEX_DD_NAME
                    + ".alternate-key to '" + ALTERNATE_KEY_FIELD.name() + "'.");
        }
    }

    private static String requireUsableDatasetName(String candidate, String ddName) {
        if (candidate == null || candidate.isBlank()) {
            throw new IllegalStateException("The dataset binding for '" + ddName + "' declares no "
                    + "dataset name. Set carddemo.datasets." + ddName + ".dsname; this repository "
                    + "composes its statements from configuration alone and hard-codes no dataset "
                    + "name.");
        }
        return DatasetRelation.requireDatasetName(candidate);
    }

    private BaseStatements resolveBaseStatements() {
        BaseStatements resolved = this.baseStatements;
        if (resolved == null) {
            resolved = composeBaseStatements();
        }
        return resolved;
    }

    private BaseStatements composeBaseStatements() {
        String baseColumn = baseRelation.rememberRecordImageColumn(jdbcTemplate.query(
                baseRelation.describeStatement(), CardRepository::extractRecordImageColumn));
        BaseStatements composed = new BaseStatements(
                baseRelation.selectByKey(baseColumn),
                baseRelation.selectByKeyForUpdate(baseColumn),
                baseRelation.selectFromKeyAscending(baseColumn),
                baseRelation.selectAfterAscending(baseColumn),
                baseRelation.selectBeforeDescending(baseColumn),
                baseRelation.rewriteByKey(baseColumn),
                baseRelation.selectUnreadableRows(baseColumn));
        this.baseStatements = composed;
        return composed;
    }

    private AlternateStatements resolveAlternateStatements() {
        AlternateStatements resolved = this.alternateStatements;
        if (resolved == null) {
            resolved = composeAlternateStatements();
        }
        return resolved;
    }

    private AlternateStatements composeAlternateStatements() {
        String alternateColumn = alternateIndexRelation.rememberRecordImageColumn(jdbcTemplate.query(
                alternateIndexRelation.describeStatement(), CardRepository::extractRecordImageColumn));
        AlternateStatements composed = new AlternateStatements(
                alternateIndexRelation.selectByKey(alternateColumn),
                alternateIndexRelation.selectUnreadableRows(alternateColumn));
        this.alternateStatements = composed;
        return composed;
    }

    private static String extractRecordImageColumn(ResultSet resultSet) throws SQLException {
        return DatasetRelation.recordImageColumnOf(resultSet.getMetaData());
    }

    /**
     * Every statement this repository sends against the base cluster, composed once against that relation's
     * discovered record-image column.
     *
     * @param selectByCardNumber {@code READ} on the base cluster, keyed on the card number
     * @param selectForUpdateByCardNumber {@code READ ... UPDATE}: the same read, holding the record locked
     *     for the unit of work
     * @param browseAnchor {@code STARTBR ... GTEQ} and the first read: at or after the supplied key,
     *     ascending in both directions, because the anchor is the lowest qualifying key either way
     * @param browseForward {@code READNEXT}: strictly beyond the last record returned, ascending
     * @param browseBackward {@code READPREV}: strictly before the last record returned, descending, on the
     *     previous whole image for the same reason
     * @param rewrite {@code REWRITE}: the whole record image, keyed on the record's own card number
     * @param probeUnreadableRows the rows of the base cluster whose record image is absent
     */
    record BaseStatements(String selectByCardNumber,
                          String selectForUpdateByCardNumber,
                          String browseAnchor,
                          String browseForward,
                          String browseBackward,
                          String rewrite,
                          String probeUnreadableRows) {
    }

    /**
     * Every statement this repository sends through the alternate-index path, composed once against that
     * path's discovered record-image column.
     *
     * @param selectByAccountId {@code READ} through the alternate-index path, keyed on the account id at
     *     its own offset, ordered so that "the first record with this alternate key" is deterministic
     * @param probeUnreadableRows the same absence proof {@link BaseStatements#probeUnreadableRows()}
     *     provides, over the path, so a read through the path proves its absence against the path it read
     */
    record AlternateStatements(String selectByAccountId, String probeUnreadableRows) {
    }

    /**
     * What one read brought back, before it is classified: the first record image if there was one, and how
     * many rows arrived up to the limit that was asked for.
     *
     * @param firstImage the first row's record image, or {@code null} when no row arrived or the row
     *     carried none
     * @param rowCount how many rows arrived, never more than the limit the read asked for
     */
    record FetchedRows(byte[] firstImage, int rowCount) {
        static FetchedRows empty() {
            return new FetchedRows(null, 0);
        }
    }

    /**
     * One browse step's outcome together with the exact bytes it came from.
     *
     * @param result the outcome the caller branches on
     * @param exactImage the bytes the row's record-image column held, or {@code null} when no record was
     *     returned
     */
    record BrowseStep(CardReadResult result, byte[] exactImage) {
    }

    public enum BrowseDirection {
        FORWARD,

        BACKWARD
    }

    /**
     * The outcome of one read: the arm of the guard chain it landed on, the record where there is one, and
     * both raw CICS response values.
     *
     * @param resp the raw CICS response, one of the {@link FileStatus} response constants
     * @param resp2 the CICS reason code the deployment's adapter reported, or
     *     {@link CardRepository#NO_REASON_CODE} where none is available
     * @param outcome the classification of {@code resp}, agreeing with it by construction
     * @param record the record on the two arms that return one - the normal arm and the duplicate-key arm -
     *     and empty on every other arm
     * @param storedImage the row's stored fixed-width record image, exactly as the dataset holds it
     * @param observation what the operation measured, where a measurement is what explains the outcome - a
     *     row's actual width, for instance
     */
    public record CardReadResult(int resp, int resp2, Outcome outcome, Optional<CardRecord> record,
            Optional<String> storedImage, Optional<DatasetObservation> observation) {
        public CardReadResult {
            Objects.requireNonNull(outcome, "A read result carries the arm it landed on; it is never "
                    + "absent");
            Objects.requireNonNull(record, "A read result carries an empty record rather than a null "
                    + "one, so no null escapes the type");
            Objects.requireNonNull(storedImage, "A read result carries an empty stored image rather than "
                    + "a null one, so no null escapes the type");
            Objects.requireNonNull(observation, "A read result carries an empty observation rather than "
                    + "a null one, so no null escapes the type");
            requireReasonCode(resp2);
            Outcome classified = FileStatus.outcomeOfCicsResp(resp);
            if (outcome != classified) {
                throw new IllegalArgumentException("CICS response " + resp + " classifies as "
                        + classified + ", but the result was built as " + outcome + ". The response and "
                        + "its classification must agree, or a caller switching on one would reach a "
                        + "different arm than a caller switching on the other.");
            }
            boolean recordExpected = outcome == Outcome.OK || outcome == Outcome.DUPLICATE;
            if (record.isPresent() != recordExpected) {
                throw new IllegalArgumentException(record.isPresent()
                        ? "Outcome " + outcome + " returns no record, but one was given. Only the "
                                + "normal arm and the duplicate-key arm reach CARD-RECORD."
                        : "Outcome " + outcome + " returns a record, and this result carries none.");
            }
            // That is what makes DISPLAY CARD-RECORD reproducible: a caller reaching the record-bearing arm
            // can always reach the row's own bytes, and never has to fall back on re-encoding the decoded
            // fields - which would emit FILLER X(59) as spaces whatever the row held.
            if (storedImage.isPresent() != record.isPresent()) {
                throw new IllegalArgumentException(storedImage.isPresent()
                        ? "Outcome " + outcome + " returns no record, so it carries no stored image "
                                + "either; an image with no record to belong to has no meaning."
                        : "Outcome " + outcome + " returns a record, so it must carry the stored image "
                                + "that record was decoded from. Build it with "
                                + "CardReadResult.normal(record, storedImage) or "
                                + "CardReadResult.duplicateKey(record, storedImage).");
            }
            storedImage.ifPresent(image -> {
                if (image.length() != RECORD_LENGTH) {
                    throw new IllegalArgumentException("A stored CARD-RECORD image is " + RECORD_LENGTH
                            + " characters as app/cpy/CVACT02Y.cpy declares, but this one is "
                            + image.length() + ". DISPLAY CARD-RECORD writes the whole record area, so "
                            + "an image of any other width would emit a line the program cannot "
                            + "produce.");
                }
            });
        }

        /**
         * The normal arm: {@code WHEN DFHRESP(NORMAL)}, the record read.
         *
         * <p>{@code CARD-RECORD} ends with {@code FILLER X(59)} ({@code app/cpy/CVACT02Y.cpy}), which holds
         * no field and which the record model therefore cannot reproduce; {@code DISPLAY CARD-RECORD}
         * ({@code app/cbl/CBACT02C.cbl:78}) writes the whole area including it.
         *
         * @param record the record read
         * @param storedImage the row's own bytes, decoded in the dataset code page - exactly
         *     {@value CardRepository#RECORD_LENGTH} characters
         * @return the result
         * @throws NullPointerException if either argument is {@code null}
         * @throws IllegalArgumentException if {@code storedImage} is not
         *     {@value CardRepository#RECORD_LENGTH} characters
         */
        public static CardReadResult normal(CardRecord record, String storedImage) {
            Objects.requireNonNull(record, "The normal arm carries the record that was read");
            Objects.requireNonNull(storedImage, "The normal arm carries the stored image the record was "
                    + "decoded from, so DISPLAY CARD-RECORD can write the row's own bytes");
            return new CardReadResult(FileStatus.NORMAL, NO_REASON_CODE, Outcome.OK,
                    Optional.of(record), Optional.of(storedImage), Optional.empty());
        }

        /**
         * The duplicate-key arm, reachable only through the alternate-index path: the first record sharing
         * the alternate key, with more behind it.
         *
         * @param record the first record sharing the alternate key
         * @param storedImage that record's own bytes, decoded in the dataset code page - exactly
         *     {@value CardRepository#RECORD_LENGTH} characters
         * @return the result
         * @throws NullPointerException if either argument is {@code null}
         * @throws IllegalArgumentException if {@code storedImage} is not
         *     {@value CardRepository#RECORD_LENGTH} characters
         */
        public static CardReadResult duplicateKey(CardRecord record, String storedImage) {
            Objects.requireNonNull(record, "The duplicate-key arm carries the first record sharing the "
                    + "alternate key; the record is returned, which is what makes it not an error");
            Objects.requireNonNull(storedImage, "The duplicate-key arm returns a record, so it carries "
                    + "the stored image that record was decoded from");
            return new CardReadResult(FileStatus.DUPKEY, NO_REASON_CODE, Outcome.DUPLICATE,
                    Optional.of(record), Optional.of(storedImage), Optional.empty());
        }

        /**
         * The not-found arm: {@code WHEN DFHRESP(NOTFND)}.
         *
         * <p>An ordinary outcome that sets a screen message ({@code app/cbl/COCRDSLC.cbl:755-761}), never
         * an exception.
         *
         * @return the result
         */
        public static CardReadResult notFound() {
            return new CardReadResult(FileStatus.NOTFND, NO_REASON_CODE, Outcome.NOT_FOUND,
                    Optional.empty(), Optional.empty(), Optional.empty());
        }

        /**
         * The end-of-file arm: {@code WHEN DFHRESP(ENDFILE)}, reachable from a browse step.
         *
         * @return the result
         */
        public static CardReadResult endOfFile() {
            return new CardReadResult(FileStatus.ENDFILE, NO_REASON_CODE, Outcome.END_OF_FILE,
                    Optional.empty(), Optional.empty(), Optional.empty());
        }

        public static CardReadResult failed(int resp) {
            return failed(resp, Optional.empty());
        }

        /**
         * The {@code WHEN OTHER} arm, carrying what the operation measured.
         *
         * @param resp the raw CICS response, which must classify as {@link Outcome#OTHER}
         * @param observation what was measured
         * @return the result
         * @throws NullPointerException if {@code observation} is {@code null}
         * @throws IllegalArgumentException if {@code resp} classifies as anything other than
         *     {@link Outcome#OTHER}
         */
        public static CardReadResult failed(int resp, DatasetObservation observation) {
            Objects.requireNonNull(observation, "This factory carries an observation; use failed(int) "
                    + "where there is nothing measured to report");
            return failed(resp, Optional.of(observation));
        }

        /**
         * The {@code WHEN OTHER} arm with a reason code the deployment's adapter actually reported.
         *
         * @param resp the raw CICS response, which must classify as {@link Outcome#OTHER}
         * @param resp2 the reason code the adapter reported
         * @return the result
         * @throws IllegalArgumentException if {@code resp} classifies as anything other than
         *     {@link Outcome#OTHER}, or if {@code resp2} is negative
         */
        public static CardReadResult reportedFailure(int resp, int resp2) {
            requireOtherArm(resp);
            return new CardReadResult(resp, resp2, Outcome.OTHER, Optional.empty(), Optional.empty(),
                    Optional.empty());
        }

        private static CardReadResult failed(int resp, Optional<DatasetObservation> observation) {
            requireOtherArm(resp);
            return new CardReadResult(resp, NO_REASON_CODE, Outcome.OTHER, Optional.empty(),
                    Optional.empty(), observation);
        }

        /**
         * The two-character batch file status this response corresponds to, where it has one.
         *
         * @return the corresponding status, or empty where there is none
         */
        public Optional<String> batchStatus() {
            return FileStatus.batchStatusOfCicsResp(resp);
        }

        public boolean isNormal() {
            return resp == FileStatus.NORMAL;
        }

        /**
         * Whether a record came back, on either of the two arms that return one.
         *
         * @return {@code true} when {@link #record()} is present
         */
        public boolean isRecordReturned() {
            return record.isPresent();
        }

        public boolean isNotFound() {
            return resp == FileStatus.NOTFND;
        }

        public boolean isEndOfFile() {
            return resp == FileStatus.ENDFILE;
        }

        /**
         * Whether the alternate key matched more than one record.
         *
         * @return {@code true} for a duplicate-key response
         */
        public boolean isDuplicateKey() {
            return resp == FileStatus.DUPKEY;
        }

        public boolean isFailure() {
            return outcome == Outcome.OTHER;
        }

        /**
         * The record, for a caller already on an arm that returns one.
         *
         * @return the record
         * @throws IllegalStateException if this arm returns no record
         */
        public CardRecord requireRecord() {
            return record.orElseThrow(() -> new IllegalStateException("Outcome " + outcome
                    + " returns no record. Branch on the outcome first, as the EVALUATE at "
                    + "app/cbl/COCRDSLC.cbl:752-772 does, and read the record only on an arm that "
                    + "returns one."));
        }

        /**
         * The row's own bytes as characters, for a caller already on an arm that returns a record.
         *
         * @return exactly {@value CardRepository#RECORD_LENGTH} characters
         * @throws IllegalStateException if this arm returns no record, and so no image either
         */
        public String requireStoredImage() {
            return storedImage.orElseThrow(() -> new IllegalStateException("Outcome " + outcome
                    + " returns no record, so it carries no stored image. DISPLAY CARD-RECORD is "
                    + "reached only on the arm that returns one - app/cbl/CBACT02C.cbl:77 tests "
                    + "END-OF-FILE before it displays - so branch on the outcome first."));
        }
    }

    /**
     * The outcome of a rewrite: the arm it landed on and both raw CICS response values.
     *
     * @param resp the raw CICS response
     * @param resp2 the CICS reason code the deployment's adapter reported, or
     *     {@link CardRepository#NO_REASON_CODE} where none is available
     * @param outcome the classification of {@code resp}, agreeing with it by construction
     * @param observation what the write measured, where a measurement explains the outcome - the number of
     *     rows a key selected, for instance
     */
    public record CardWriteResult(int resp, int resp2, Outcome outcome,
            Optional<DatasetObservation> observation) {
        public CardWriteResult {
            Objects.requireNonNull(outcome, "A write result carries the arm it landed on; it is never "
                    + "absent");
            Objects.requireNonNull(observation, "A write result carries an empty observation rather "
                    + "than a null one, so no null escapes the type");
            requireReasonCode(resp2);
            Outcome classified = FileStatus.outcomeOfCicsResp(resp);
            if (outcome != classified) {
                throw new IllegalArgumentException("CICS response " + resp + " classifies as "
                        + classified + ", but the result was built as " + outcome
                        + ". The response and its classification must agree.");
            }
        }

        /**
         * The normal arm: exactly one record was replaced.
         *
         * @return the result
         */
        public static CardWriteResult normal() {
            return new CardWriteResult(FileStatus.NORMAL, NO_REASON_CODE, Outcome.OK,
                    Optional.empty());
        }

        /**
         * The failure arm, on which the legacy code sets its update-failed flag.
         *
         * @param resp the response to report; it must classify as {@link Outcome#OTHER}, which every way a
         *     rewrite can fail does
         * @return the result, reporting {@link CardRepository#NO_REASON_CODE}
         * @throws IllegalArgumentException if {@code resp} classifies as anything other than
         *     {@link Outcome#OTHER}
         */
        public static CardWriteResult failed(int resp) {
            return failed(resp, Optional.empty());
        }

        /**
         * The failure arm, carrying what the write measured.
         *
         * @param resp the response to report; it must classify as {@link Outcome#OTHER}
         * @param observation what was measured - a row count, never a reason code
         * @return the result, reporting {@link CardRepository#NO_REASON_CODE} as its reason
         * @throws NullPointerException if {@code observation} is {@code null}
         * @throws IllegalArgumentException if {@code resp} classifies as anything other than
         *     {@link Outcome#OTHER}
         */
        public static CardWriteResult failed(int resp, DatasetObservation observation) {
            Objects.requireNonNull(observation, "This factory carries an observation; use failed(int) "
                    + "where there is nothing measured to report");
            return failed(resp, Optional.of(observation));
        }

        /**
         * The failure arm with a reason code the deployment's adapter actually reported.
         *
         * @param resp the response to report; it must classify as {@link Outcome#OTHER}
         * @param resp2 the reason code the adapter reported
         * @return the result
         * @throws IllegalArgumentException if {@code resp} classifies as anything other than
         *     {@link Outcome#OTHER}, or if {@code resp2} is negative
         */
        public static CardWriteResult reportedFailure(int resp, int resp2) {
            requireRewriteFailureArm(resp);
            return new CardWriteResult(resp, resp2, Outcome.OTHER, Optional.empty());
        }

        private static CardWriteResult failed(int resp, Optional<DatasetObservation> observation) {
            requireRewriteFailureArm(resp);
            return new CardWriteResult(resp, NO_REASON_CODE, Outcome.OTHER, observation);
        }

        private static void requireRewriteFailureArm(int resp) {
            Outcome classified = FileStatus.outcomeOfCicsResp(resp);
            if (classified != Outcome.OTHER) {
                throw new IllegalArgumentException("CICS response " + resp + " classifies as "
                        + classified + ", which a rewrite cannot report: a rewrite either replaces its "
                        + "record or fails. Only a response classifying as WHEN OTHER may be reported "
                        + "as a failure.");
            }
        }

        /**
         * The two-character batch file status this response corresponds to, where it has one.
         *
         * @return the corresponding status, or empty where there is none
         */
        public Optional<String> batchStatus() {
            return FileStatus.batchStatusOfCicsResp(resp);
        }

        public boolean isNormal() {
            return resp == FileStatus.NORMAL;
        }

        /**
         * Whether the rewrite failed, which is the arm that sets the legacy update-failed flag.
         *
         * @return {@code true} when the rewrite failed
         */
        public boolean isFailure() {
            return outcome == Outcome.OTHER;
        }
    }

    /**
     * One browse in progress: where it is positioned, which way it is walked, and whether it has been
     * ended.
     */
    public static final class CardBrowse implements AutoCloseable {
        private final CardRepository repository;

        private final String anchorKey;

        private final BrowseDirection direction;

        private String positionKey;

        private byte[] positionImage;

        private boolean positioned;

        private boolean ended;

        private final int openResp;

        private CardBrowse(CardRepository repository, String anchorKey, BrowseDirection direction) {
            this(repository, anchorKey, direction, FileStatus.NORMAL);
        }

        private CardBrowse(CardRepository repository, String anchorKey, BrowseDirection direction,
                           int openResp) {
            this.repository = repository;
            this.anchorKey = anchorKey;
            this.direction = direction;
            this.openResp = openResp;
        }

        /**
         * The CICS response the open reported, for a caller whose COBOL tests its {@code OPEN}.
         *
         * @return {@link FileStatus#NORMAL} when the dataset was opened, or {@link FileStatus#NOTOPEN} when
         *     it could not be
         */
        public int openResp() {
            return openResp;
        }

        /**
         * Whether the open succeeded, and so whether reading this handle can return anything.
         *
         * @return {@code true} when the open reported {@link FileStatus#NORMAL}
         */
        public boolean isOpen() {
            return openResp == FileStatus.NORMAL;
        }

        public BrowseDirection direction() {
            return direction;
        }

        /**
         * The key this browse was positioned at or after.
         *
         * @return the anchor key, exactly {@code CARD-NUM}'s declared width
         */
        public String anchorKey() {
            return anchorKey;
        }

        public Optional<String> positionKey() {
            return Optional.ofNullable(positionKey);
        }

        public boolean isEnded() {
            return ended;
        }

        /**
         * Reads the next record in ascending key order.
         *
         * @return the read outcome; never {@code null}
         */
        public CardReadResult readNext() {
            return read(BrowseDirection.FORWARD, BaseStatements::browseForward);
        }

        /**
         * Reads the previous record in descending key order.
         *
         * @return the read outcome; never {@code null}
         */
        public CardReadResult readPrev() {
            return read(BrowseDirection.BACKWARD, BaseStatements::browseBackward);
        }

        private CardReadResult read(BrowseDirection required,
                                    Function<BaseStatements, String> advanceStatement) {
            if (ended) {
                LOG.error("A read was requested on a browse of " + BASE_CICS_FILE_NAME.trim()
                        + " that has already been ended; reporting the invalid-request response");
                return CardReadResult.failed(FileStatus.INVREQ);
            }
            if (direction != required) {
                LOG.error("A " + required + " read was requested on a browse of "
                        + BASE_CICS_FILE_NAME.trim() + " positioned for " + direction
                        + "; reporting the invalid-request response rather than reversing a browse the "
                        + "legacy code never reverses");
                return CardReadResult.failed(FileStatus.INVREQ);
            }
            BaseStatements sql;
            try {
                sql = repository.resolveBaseStatements();
            } catch (DataAccessException rejected) {
                return CardRepository.failedRead(CardRepository.READ_OPERATION_NAME,
                        BASE_CICS_FILE_NAME, "while positioning a browse", rejected);
            }

            BrowseStep step = positioned
                    ? repository.browseStepAfter(advanceStatement.apply(sql), positionImage,
                            CardRepository.READ_OPERATION_NAME)
                    : repository.browseStep(sql.browseAnchor(), anchorKey,
                            CardRepository.READ_OPERATION_NAME);
            CardReadResult result = step.result();
            if (result.isRecordReturned()) {
                positionImage = step.exactImage().clone();
                positionKey = repository.keyOfImage(positionImage);
                positioned = true;
            }
            return result;
        }

        public void endBrowse() {
            ended = true;
        }

        /**
         * Ends the browse, so that a handle can be used in a try-with-resources block.
         */
        @Override
        public void close() {
            endBrowse();
        }
    }
}
