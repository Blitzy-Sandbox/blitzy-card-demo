package com.vsergeychik.carddemo.card;

import com.vsergeychik.carddemo.card.model.CardXrefRecord;
import com.vsergeychik.carddemo.common.DatasetRelation;
import com.vsergeychik.carddemo.common.DatasetRelation.BackendDiagnostic;
import com.vsergeychik.carddemo.common.DatasetRelation.KeySpan;
import com.vsergeychik.carddemo.common.FileStatus;
import com.vsergeychik.carddemo.common.FileStatus.Outcome;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.RecordImageForm;
import com.vsergeychik.carddemo.common.FixedWidthRecord;
import com.vsergeychik.carddemo.config.CobolCharsetConfig;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBinding;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBindings;

import java.nio.charset.Charset;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * The one data-access component for the card-to-account cross reference: the {@code CCXREF} base KSDS and
 * the {@code CXACAIX} alternate-index path over it.
 *
 * <p>Reading every {@code COPY} statement in the three card programs shows that not one of them copies
 * {@code CVACT03Y}: {@code app/cbl/COCRDLIC.cbl} never names it, and {@code app/cbl/COCRDSLC.cbl:237} and
 * {@code app/cbl/COCRDUPC.cbl:356} both carry it commented out as {@code *COPY CVACT03Y.}.
 */
@Repository
public class CardXrefRepository {
    // These are CONFIGURATION KEYS and CICS FILE names - never dataset names.

    private static final Log LOG = LogFactory.getLog(CardXrefRepository.class);

    public static final String BASE_DD_NAME = "CCXREF";

    /**
     * The batch DD name of the same base cluster: {@code XREFFILE}.
     */
    public static final String BATCH_DD_NAME = "XREFFILE";

    /**
     * The batch DD name of the alternate-index path: {@code XREFFIL1}.
     */
    public static final String ALTERNATE_INDEX_BATCH_DD_NAME = "XREFFIL1";

    /**
     * The configuration key and CICS {@code FILE} name of the alternate-index path: {@code CXACAIX}.
     */
    public static final String ALTERNATE_INDEX_DD_NAME = "CXACAIX";

    /**
     * The width every row must be, taken from the copybook by way of {@link CardXrefRecord} rather than
     * restated: fifty bytes, {@code FILLER} included.
     */
    public static final int RECORD_LENGTH = CardXrefRecord.RECORD_LENGTH;

    /**
     * The base-KSDS key width: sixteen bytes, {@code XREF-CARD-NUM}'s {@code PIC X(16)}.
     */
    public static final int CARD_NUMBER_KEY_LENGTH = CardXrefRecord.XREF_CARD_NUM_LENGTH;

    /**
     * The alternate-index key width: eleven bytes, {@code XREF-ACCT-ID}'s {@code PIC 9(11)}, viewed either
     * as itself or through its {@code PIC X(11)} {@code REDEFINES}.
     */
    public static final int ACCOUNT_ID_KEY_LENGTH = CardXrefRecord.XREF_ACCT_ID_LENGTH;

    public static final String EXPECTED_ALTERNATE_KEY_FIELD = CardXrefRecord.XREF_ACCT_ID_NAME;

    public static final int RECORD_IMAGE_COLUMN_INDEX = DatasetRelation.RECORD_IMAGE_COLUMN_INDEX;

    private static final KeySpan CARD_NUMBER_KEY_SPAN = new KeySpan(
            CardXrefRecord.XREF_CARD_NUM.offset(), CardXrefRecord.XREF_CARD_NUM.length());

    private static final KeySpan ACCOUNT_ID_KEY_SPAN = new KeySpan(
            CardXrefRecord.XREF_ACCT_ID.offset(), CardXrefRecord.XREF_ACCT_ID.length());

    private static final int DUPLICATE_DETECTION_ROW_LIMIT = 2;

    private static final int SINGLE_ROW_PROBE_LIMIT = 1;

    private static final int BROWSE_ROW_LIMIT = 1;

    /**
     * The width of a CICS {@code FILE} name as the online programs declare it: {@code PIC X(08)}.
     */
    public static final int CICS_FILE_NAME_LENGTH = 8;

    private static final char PERMANENT_ERROR_FEEDBACK_CODE = 0;

    /**
     * The two-character file status reported for a permanent I/O error: {@code '9'} followed by
     * {@link #PERMANENT_ERROR_FEEDBACK_CODE}.
     */
    public static final String PERMANENT_ERROR_STATUS = "9" + PERMANENT_ERROR_FEEDBACK_CODE;

    /**
     * The {@code APPL-RESULT} value the batch consumers move for a fatal I/O status: {@code 12}.
     */
    public static final int APPL_RESULT_FATAL = 12;

    /**
     * The CICS {@code RESP2} value every result carries: zero.
     */
    public static final int CICS_RESP2_NOT_APPLICABLE = 0;

    private static final char LIKE_ESCAPE = '\\';

    private static final char LIKE_WILDCARD = '%';

    private static final char LIKE_SINGLE_WILDCARD = '_';

    private static final String NEVER_TRUE_PREDICATE = "1 = 0";

    private static final int KEYED_READ_ROW_LIMIT = 2;

    private final JdbcTemplate jdbcTemplate;

    private final FixedWidthCodec codec;

    private final RecordImageForm recordImageForm;

    private final DatasetRelation baseRelation;

    private final DatasetRelation alternateIndexRelation;

    private volatile BaseStatements baseStatements;

    private volatile AlternateStatements alternateStatements;

    /**
     * Assembles the repository from the module's shared {@link JdbcTemplate}, the DD-name-keyed dataset
     * catalogue and the explicitly named dataset code page.
     *
     * @param jdbcTemplate the module-wide template; never {@code null}
     * @param datasetBindings the {@code carddemo.datasets} catalogue; never {@code null}
     * @param datasetCharset the code page the dataset holds its record images in, from
     *     {@code carddemo.charset.dataset}
     * @param recordImageForm how the deployment's driver presents a record image over JDBC, from
     *     {@value RecordImageForm#FORM_PROPERTY}
     * @throws NullPointerException if any argument is {@code null}
     * @throws IllegalStateException if the operation cannot complete
     */
    @Autowired
    public CardXrefRepository(
            JdbcTemplate jdbcTemplate,
            DatasetBindings datasetBindings,
            @Qualifier(CobolCharsetConfig.DATASET_CHARSET_BEAN_NAME) Charset datasetCharset,
            RecordImageForm recordImageForm) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "A JdbcTemplate is required: the "
                + "card cross reference is reached through the module's shared template over the "
                + "configuration-bound DataSource");
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

        DatasetBinding base = datasetBindings.binding(BASE_DD_NAME);
        DatasetBinding alternateIndex = datasetBindings.binding(ALTERNATE_INDEX_DD_NAME);

        requireCopybookRecordLength(BASE_DD_NAME, base);
        requireCopybookRecordLength(ALTERNATE_INDEX_DD_NAME, alternateIndex);
        requireAlternateIndexOverBase(alternateIndex);
        requireBaseCluster(base);

        this.baseRelation = DatasetRelation.of(
                requireUsableDatasetName(BASE_DD_NAME, base.dsname()), RECORD_LENGTH);
        this.alternateIndexRelation = DatasetRelation.of(
                requireUsableDatasetName(ALTERNATE_INDEX_DD_NAME, alternateIndex.dsname()),
                RECORD_LENGTH);
    }

    private CardXrefRepository(CardXrefRepository source,
                               DatasetRelation baseRelation,
                               DatasetRelation alternateIndexRelation) {
        this.jdbcTemplate = source.jdbcTemplate;
        this.codec = source.codec;
        this.recordImageForm = source.recordImageForm;
        this.baseRelation = baseRelation;
        this.alternateIndexRelation = alternateIndexRelation;
    }

    /**
     * Returns this repository addressing the cross-reference cluster a caller's own DD bindings name.
     *
     * @param baseBinding the caller's resolved base binding, normally from
     *     {@code BatchConfig.datasetBinding(jobKey, ddName)}
     * @param baseDdName the DD name the base binding was resolved for
     * @param alternateIndexBinding the caller's resolved alternate-index binding, or {@code null} when the
     *     caller's JCL declares no alternate-index DD - a sequential reader such as {@code CBACT03C} declares
     *     only the base
     * @param alternateIndexDdName the DD name the alternate-index binding was resolved for; ignored when
     *     that binding is {@code null}
     * @return this repository, or one addressing the given datasets; never {@code null}
     * @throws NullPointerException if the base binding or either DD name is {@code null}
     * @throws IllegalStateException if a binding declares a record width other than {@link #RECORD_LENGTH},
     *     or no usable dataset name
     */
    public CardXrefRepository addressing(DatasetBinding baseBinding,
                                         String baseDdName,
                                         DatasetBinding alternateIndexBinding,
                                         String alternateIndexDdName) {
        Objects.requireNonNull(baseDdName, "A base DD name is required: it is what a diagnostic names "
                + "when the binding is at fault");
        Objects.requireNonNull(alternateIndexDdName, "An alternate-index DD name is required even when "
                + "no alternate-index binding is supplied: it is what a diagnostic would name");
        Objects.requireNonNull(baseBinding, "A resolved base binding is required for DD name '"
                + baseDdName + "': a batch job reads through the DD its JCL declares, not through the "
                + "CICS file name this repository resolved at construction");

        DatasetRelation rebasedBase = relationFor(baseBinding, baseDdName);
        DatasetRelation rebasedPath = alternateIndexBinding == null
                ? alternateIndexRelation
                : relationFor(alternateIndexBinding, alternateIndexDdName);
        if (rebasedBase.dsname().equals(baseRelation.dsname())
                && rebasedPath.dsname().equals(alternateIndexRelation.dsname())) {
            return this;
        }
        return new CardXrefRepository(this, rebasedBase, rebasedPath);
    }

    private static DatasetRelation relationFor(DatasetBinding binding, String ddName) {
        requireCopybookRecordLength(ddName, binding);
        return DatasetRelation.of(requireUsableDatasetName(ddName, binding.dsname()), RECORD_LENGTH);
    }

    /**
     * Requires a binding to agree with {@code app/cpy/CVACT03Y.cpy} about the record width.
     *
     * @param ddName the configuration key, for the diagnostic
     * @param binding the configured binding
     * @throws IllegalStateException if the declared record length is not {@link #RECORD_LENGTH}
     */
    private static void requireCopybookRecordLength(String ddName, DatasetBinding binding) {
        if (binding.recordLength() != RECORD_LENGTH) {
            throw new IllegalStateException("Dataset binding for '" + ddName + "' declares a record "
                    + "length of " + binding.recordLength() + ", but app/cpy/CVACT03Y.cpy declares "
                    + "(RECLN " + RECORD_LENGTH + ") and this repository decodes by absolute offset "
                    + "against exactly that width. The 36-byte form of app/data/ASCII/cardxref.txt is "
                    + "the fixture omitting its trailing FILLER X(" + CardXrefRecord.FILLER_LENGTH
                    + "); it is normalised UP to " + RECORD_LENGTH + " by the parity harness (risk "
                    + "R-F, gate G16) and is never configured DOWN here, because a shorter width "
                    + "would shift XREF-CUST-ID and XREF-ACCT-ID out of position. Correct "
                    + "carddemo.datasets." + ddName + ".record-length to " + RECORD_LENGTH + ".");
        }
    }

    /**
     * Requires the {@code CXACAIX} binding to describe an alternate-index path over {@code CCXREF}, keyed
     * on {@code XREF-ACCT-ID}.
     *
     * @param alternateIndex the configured alternate-index binding
     * @throws IllegalStateException if it names a different base, or a different alternate key
     */
    private static void requireAlternateIndexOverBase(DatasetBinding alternateIndex) {
        if (!BASE_DD_NAME.equals(alternateIndex.base())) {
            throw new IllegalStateException("Dataset binding for '" + ALTERNATE_INDEX_DD_NAME
                    + "' declares base '" + alternateIndex.base() + "', but it must declare '"
                    + BASE_DD_NAME + "'. app/csd/CARDDEMO.CSD:64 is DESCRIPTION(ALTERNATE INDEX TO "
                    + BASE_DD_NAME + " VIA ACCOUNT KEY), app/jcl/INTCALC.jcl:29-32 opens the base and "
                    + "the path in one step as XREFFILE and XREFFIL1, and app/cbl/CBACT04C.cbl:34-39 "
                    + "carries both keys on a single SELECT. The path is an additional access path "
                    + "over one cluster, never a dataset of its own (gate G45). Correct "
                    + "carddemo.datasets." + ALTERNATE_INDEX_DD_NAME + ".base.");
        }
        if (!EXPECTED_ALTERNATE_KEY_FIELD.equals(alternateIndex.alternateKey())) {
            throw new IllegalStateException("Dataset binding for '" + ALTERNATE_INDEX_DD_NAME
                    + "' nominates alternate key '" + alternateIndex.alternateKey() + "', but the "
                    + "path the four online callers address is keyed on "
                    + EXPECTED_ALTERNATE_KEY_FIELD + ", the PIC 9(" + ACCOUNT_ID_KEY_LENGTH
                    + ") field at offset " + CardXrefRecord.XREF_ACCT_ID_OFFSET
                    + " of app/cpy/CVACT03Y.cpy. Correct carddemo.datasets."
                    + ALTERNATE_INDEX_DD_NAME + ".alternate-key.");
        }
    }

    private static void requireBaseCluster(DatasetBinding base) {
        if (base.base() != null) {
            throw new IllegalStateException("Dataset binding for '" + BASE_DD_NAME + "' declares base '"
                    + base.base() + "', but it IS the base cluster - app/csd/CARDDEMO.CSD:37-39, "
                    + "DESCRIPTION(CARD TO ACCOUNT XREF), keyed on "
                    + CardXrefRecord.XREF_CARD_NUM_NAME + ". Only '" + ALTERNATE_INDEX_DD_NAME
                    + "' declares a base. Remove carddemo.datasets." + BASE_DD_NAME + ".base.");
        }
    }

    private static String requireUsableDatasetName(String ddName, String candidate) {
        if (candidate == null || candidate.isBlank()) {
            throw new IllegalStateException("The dataset binding for '" + ddName + "' declares no "
                    + "dataset name. Set carddemo.datasets." + ddName + ".dsname; this repository "
                    + "composes its statements from configuration alone and hard-codes no dataset "
                    + "name (gate G46).");
        }
        return DatasetRelation.requireDatasetName(candidate);
    }

    /**
     * The resolved {@code CCXREF} base-cluster dataset name, exactly as configuration declares it.
     *
     * @return the base dataset name; never {@code null}, never blank
     */
    public String baseDatasetName() {
        return baseRelation.dsname();
    }

    /**
     * The resolved {@code CXACAIX} alternate-index path dataset name, exactly as configuration declares it.
     *
     * @return the alternate-index path dataset name; never {@code null}, never blank
     */
    public String alternateIndexDatasetName() {
        return alternateIndexRelation.dsname();
    }

    /**
     * The base cluster's CICS {@code FILE} name in the eight-character form the online programs hold it:
     * {@code 'CCXREF '}.
     *
     * <p>The padding is applied through the codec's {@code PIC X} move rather than by concatenating spaces,
     * so the one truncation-and-padding implementation in the module governs it here too.
     *
     * @return the name padded to {@value #CICS_FILE_NAME_LENGTH} characters
     */
    public String baseFileNameForCics() {
        return codec.movePicX(BASE_DD_NAME, CICS_FILE_NAME_LENGTH);
    }

    /**
     * The alternate-index path's CICS {@code FILE} name in its eight-character form: {@code 'CXACAIX '}.
     *
     * @return the name padded to {@value #CICS_FILE_NAME_LENGTH} characters
     */
    public String alternateIndexFileNameForCics() {
        return codec.movePicX(ALTERNATE_INDEX_DD_NAME, CICS_FILE_NAME_LENGTH);
    }

    /**
     * Reads one cross-reference record by its base key, {@code XREF-CARD-NUM}: the Java form of
     * {@code READ-CCXREF-FILE} ({@code app/cbl/COTRN02C.cbl:609-637}) and of the batch keyed reads listed
     * on this class.
     *
     * <p>The supplied value goes through {@link FixedWidthCodec#movePicX(String, int)} at width
     * {@link #CARD_NUMBER_KEY_LENGTH}, so a shorter value is space-padded on the right and a longer one is
     * truncated on the right, exactly as a {@code PIC X(16)} receiver behaves.
     *
     * @param cardNumber the card number to look up; never {@code null}, and may be shorter or longer than
     *     {@link #CARD_NUMBER_KEY_LENGTH} because the {@code PIC X} move reshapes it
     * @return the discriminated outcome; never {@code null}
     * @throws NullPointerException if {@code cardNumber} is {@code null}
     */
    public ReadResult readByCardNumber(String cardNumber) {
        Objects.requireNonNull(cardNumber, "A card number is required to read the "
                + CardXrefRecord.XREF_CARD_NUM_NAME + " key; an absent key is a defect in the caller, "
                + "not a NOTFND outcome. Pass an empty string for a key of SPACES.");
        String keyImage = codec.movePicX(cardNumber, CARD_NUMBER_KEY_LENGTH);
        return readByKey(BASE_DD_NAME, this::baseKeyedAccess, CARD_NUMBER_KEY_SPAN, keyImage,
                FileStatus.DUPREC);
    }

    /**
     * Reads one cross-reference record by its alternate key, {@code XREF-ACCT-ID}, through the
     * {@code CXACAIX} path: the Java form of {@code READ-CXACAIX-FILE}
     * ({@code app/cbl/COTRN02C.cbl:576-604}, {@code app/cbl/COBIL00C.cbl:408-437}) and of
     * {@code 9200-GETCARDXREF-BYACCT} ({@code app/cbl/COACTVWC.cbl:723-770}).
     *
     * <p>The value goes through {@link FixedWidthCodec#movePic9(long, int)} at width
     * {@link #ACCOUNT_ID_KEY_LENGTH}, so it is zero-filled on the left - account 50 becomes
     * {@code 00000000050}, exactly as {@code app/data/ASCII/cardxref.txt} holds it.
     *
     * @param accountId the account id to look up; never negative, because {@code PIC 9(11)} is unsigned,
     *     and never wider than {@link #ACCOUNT_ID_KEY_LENGTH} digits
     * @return the discriminated outcome; never {@code null}
     * @throws IllegalArgumentException if {@code accountId} is negative, which no {@code PIC 9(11)} value
     *     can be
     */
    public ReadResult readByAccountIdViaAltIndex(long accountId) {
        String keyImage = codec.movePic9(accountId, ACCOUNT_ID_KEY_LENGTH);
        return readByKey(ALTERNATE_INDEX_DD_NAME, this::alternateKeyedAccess, ACCOUNT_ID_KEY_SPAN,
                keyImage, FileStatus.DUPKEY);
    }

    /**
     * Reads one cross-reference record by its alternate key supplied as the {@code PIC X(11)}
     * {@code REDEFINES} view of the same eleven bytes: the form {@code COACTVWC} and {@code COACTUPC} pass.
     *
     * <p>The consequence is the one that matters - both overloads produce the identical eleven bytes on the
     * wire, so the two COBOL views cannot diverge here.
     *
     * @param accountIdKeyImage the eleven-byte key image, all digits; a shorter image is left-zero filled
     *     and a longer one keeps its low-order {@link #ACCOUNT_ID_KEY_LENGTH} digits
     * @return the discriminated outcome; never {@code null}
     * @throws NullPointerException if {@code accountIdKeyImage} is {@code null}
     * @throws IllegalArgumentException if {@code accountIdKeyImage} is empty or holds a non-digit
     */
    public ReadResult readByAccountIdViaAltIndex(String accountIdKeyImage) {
        Objects.requireNonNull(accountIdKeyImage, "An account id key image is required to read the "
                + CardXrefRecord.XREF_ACCT_ID_NAME + " alternate key; the PIC X(" + ACCOUNT_ID_KEY_LENGTH
                + ") REDEFINES view of app/cbl/COACTVWC.cbl:79-80 always holds digits, so an absent "
                + "image is a defect in the caller rather than a NOTFND outcome");
        String keyImage = codec.movePic9(accountIdKeyImage, ACCOUNT_ID_KEY_LENGTH);
        return readByKey(ALTERNATE_INDEX_DD_NAME, this::alternateKeyedAccess, ACCOUNT_ID_KEY_SPAN,
                keyImage, FileStatus.DUPKEY);
    }

    private ReadResult readByKey(String ddName,
                                 Supplier<KeyedAccess> accessOf,
                                 KeySpan keySpan,
                                 String keyImage,
                                 int duplicateCicsResp) {
        List<String> rows;
        KeyedAccess access;
        try {
            access = accessOf.get();
            rows = fetch(access.keyedStatement(), keySpan.pattern(keyImage));
        } catch (DataAccessException translated) {
            // Reported as a status, exactly as the COBOL keeps only the status, so the caller's own guard
            // chain decides what to do about it - and carrying the backend's own diagnosis alongside it, so
            // an operator can tell an unreachable backend from a missing relation from a rejected
            // credential.
            return other(ddName, translated, "read the " + ddName + " access path by key");
        }
        if (rows == null) {
            LOG.error("The " + ddName + " access path yielded no result object at all for a keyed read; "
                    + "reporting file status " + FileStatus.toStatusImage(PERMANENT_ERROR_STATUS)
                    + " rather than treating it as a dataset with no matching record");
            return ReadResult.other(ddName, PERMANENT_ERROR_STATUS);
        }
        List<DecodedRow> matches = new ArrayList<>(rows.size());
        for (String rowImage : rows) {
            if (rowImage == null) {
                LOG.error("A matching row of the " + ddName + " access path carries no record image at "
                        + "column position " + RECORD_IMAGE_COLUMN_INDEX + "; reporting file status "
                        + FileStatus.toStatusImage(PERMANENT_ERROR_STATUS)
                        + " rather than reporting a record that is present as absent");
                return ReadResult.other(ddName, PERMANENT_ERROR_STATUS);
            }
            ReadResult row = readRow(ddName, rowImage, "by key");
            if (!row.isFound()) {
                return row;
            }
            matches.add(new DecodedRow(row.record().orElseThrow(), row.requireStoredImage()));
        }
        if (matches.isEmpty()) {
            return provenAbsence(ddName, access.probeUnreadableRows());
        }
        if (matches.size() > 1) {
            return ReadResult.duplicate(ddName, matches.get(0).record(),
                    matches.get(0).storedImage(), duplicateCicsResp);
        }
        return ReadResult.found(ddName, matches.get(0).record(), matches.get(0).storedImage());
    }

    private ReadResult provenAbsence(String ddName, String unreadableRowsProbe) {
        List<String> unreadable;
        try {
            unreadable = fetchUnparameterised(unreadableRowsProbe);
        } catch (DataAccessException translated) {
            return other(ddName, translated, "establish that the " + ddName + " access path holds no "
                    + "unreadable row before reporting a key as absent");
        }
        if (unreadable == null || unreadable.isEmpty()) {
            return ReadResult.notFound(ddName);
        }
        LOG.error("A keyed read of the " + ddName + " access path matched no row, but the relation holds "
                + "a row with no record image at column position " + RECORD_IMAGE_COLUMN_INDEX
                + " - and both of this dataset's keys are part of that image, so that row's key cannot be "
                + "known; reporting file status " + FileStatus.toStatusImage(PERMANENT_ERROR_STATUS)
                + " rather than reporting as absent a record that may be the one asked for");
        return ReadResult.other(ddName, PERMANENT_ERROR_STATUS);
    }

    private List<String> fetchUnparameterised(String statement) {
        PreparedStatementCreator creator = connection -> {
            PreparedStatement prepared = connection.prepareStatement(statement);
            prepared.setMaxRows(SINGLE_ROW_PROBE_LIMIT);
            prepared.setFetchSize(SINGLE_ROW_PROBE_LIMIT);
            return prepared;
        };
        ResultSetExtractor<List<String>> extractor = resultSet -> {
            List<String> images = new ArrayList<>(SINGLE_ROW_PROBE_LIMIT);
            while (images.size() < SINGLE_ROW_PROBE_LIMIT && resultSet.next()) {
                images.add("");
            }
            return images;
        };
        return jdbcTemplate.query(creator, extractor);
    }

    private List<String> fetch(String statement, String pattern) {
        PreparedStatementCreator creator = connection -> {
            PreparedStatement prepared = connection.prepareStatement(statement);
            prepared.setMaxRows(DUPLICATE_DETECTION_ROW_LIMIT);
            prepared.setFetchSize(DUPLICATE_DETECTION_ROW_LIMIT);
            recordImageForm.bindOperand(prepared, 1, pattern, codec.charset());
            return prepared;
        };
        RowMapper<String> recordImageMapper = this::mapRecordImage;
        ResultSetExtractor<List<String>> extractor = resultSet -> {
            List<String> images = new ArrayList<>(DUPLICATE_DETECTION_ROW_LIMIT);
            int rowNumber = 0;
            while (images.size() < DUPLICATE_DETECTION_ROW_LIMIT && resultSet.next()) {
                images.add(recordImageMapper.mapRow(resultSet, rowNumber++));
            }
            return images;
        };
        return jdbcTemplate.query(creator, extractor);
    }

    private static ReadResult other(String ddName, Throwable refusal, String attempt) {
        BackendDiagnostic diagnostic = BackendDiagnostic.of(refusal);
        LOG.error("Could not " + attempt + " - " + diagnostic.describe() + "; reporting file status "
                + FileStatus.toStatusImage(PERMANENT_ERROR_STATUS) + " to the caller");
        return ReadResult.other(ddName, PERMANENT_ERROR_STATUS, diagnostic);
    }

    /**
     * Opens a sequential browse of the base cluster in {@code XREF-CARD-NUM} order: the Java form of
     * {@code 0000-XREFFILE-OPEN} ({@code app/cbl/CBACT03C.cbl:118-134}) and of {@code CBSTM03B}'s
     * {@code M03B-OPEN} arm for {@code XREFFILE}.
     *
     * @return a freshly positioned cursor, whose {@link BrowseCursor#openStatus()} reports whether the open
     *     succeeded; never {@code null}
     */
    public BrowseCursor openBrowse() {
        try {
            composeBaseStatements();
        } catch (DataAccessException translated) {
            BackendDiagnostic diagnostic = BackendDiagnostic.of(translated);
            LOG.error("Could not open a browse of the " + BASE_DD_NAME + " base cluster - "
                    + diagnostic.describe() + "; reporting file status "
                    + FileStatus.toStatusImage(PERMANENT_ERROR_STATUS) + " to the caller");
            return new BrowseCursor(this, PERMANENT_ERROR_STATUS);
        } catch (IllegalStateException unusable) {
            // That is a fact about the dataset, discovered at open time, and it belongs on the same arm as
            // an unreachable dataset rather than escaping as an exception the COBOL has no equivalent for:
            // OPEN INPUT reports a FILE STATUS and CBACT03C:121 branches on it.
            LOG.error("Could not open a browse of the " + BASE_DD_NAME + " base cluster: "
                    + unusable.getMessage() + "; reporting file status "
                    + FileStatus.toStatusImage(PERMANENT_ERROR_STATUS) + " to the caller");
            return new BrowseCursor(this, PERMANENT_ERROR_STATUS);
        }
        return new BrowseCursor(this, FileStatus.OK);
    }

    private BrowseRow browseRow(String statement, byte[] position) {
        PreparedStatementCreator creator = connection -> {
            PreparedStatement prepared = connection.prepareStatement(statement);
            prepared.setMaxRows(BROWSE_ROW_LIMIT);
            prepared.setFetchSize(BROWSE_ROW_LIMIT);
            if (position != null) {
                recordImageForm.bindImage(prepared, 1, position, codec.charset());
            }
            return prepared;
        };
        ResultSetExtractor<BrowseRow> extractor = resultSet -> resultSet.next()
                ? new BrowseRow(true,
                        recordImageForm.readImage(resultSet, RECORD_IMAGE_COLUMN_INDEX,
                                codec.charset()))
                : BrowseRow.none();
        BrowseRow row = jdbcTemplate.query(creator, extractor);
        return row == null ? BrowseRow.none() : row;
    }

    /**
     * Whether a browse read found a row, and the image it carried.
     *
     * @param present whether a row arrived at all
     * @param image the row's record image, which may be {@code null} even when a row arrived
     */
    private record BrowseRow(boolean present, byte[] image) {
        private static final BrowseRow NONE = new BrowseRow(false, null);

        static BrowseRow none() {
            return NONE;
        }
    }

    private String mapRecordImage(ResultSet resultSet, int rowNumber) throws SQLException {
        byte[] image = recordImageForm.readImage(resultSet, RECORD_IMAGE_COLUMN_INDEX, codec.charset());
        return image == null ? null : codec.decodeImage(image, "a CARD-XREF-RECORD row image");
    }

    private FixedWidthRecord wrapRow(String rowImage) {
        return codec.wrap(codec.encodeImage(rowImage, "a CARD-XREF-RECORD row image"),
                CardXrefRecord.LAYOUT);
    }

    private DecodedRow decodeRow(String rowImage) {
        return decodeArea(wrapRow(rowImage));
    }

    private DecodedRow decodeRow(byte[] rowImage) {
        return decodeArea(codec.wrap(rowImage, CardXrefRecord.LAYOUT));
    }

    /**
     * Decodes one stored row and reports it, turning a row that cannot be decoded into the file status the
     * COBOL would have seen instead of letting an exception escape.
     *
     * @param ddName the access path that was read, for the outcome and the diagnostic
     * @param rowImage the stored bytes, exactly as the configured representation presented them
     * @param attempt what was being read, phrased to complete "... of the ... access path
     *     &#123;attempt&#125;"
     * @return the record, or the status the malformation reports; never {@code null}
     */
    private ReadResult readRow(String ddName, byte[] rowImage, String attempt) {
        if (rowImage.length != RECORD_LENGTH) {
            return lengthConflict(ddName, rowImage.length, attempt);
        }
        try {
            DecodedRow decoded = decodeRow(rowImage);
            return ReadResult.found(ddName, decoded.record(), decoded.storedImage());
        } catch (IllegalArgumentException undecodable) {
            return undecodableRow(ddName, undecodable, attempt);
        }
    }

    private ReadResult readRow(String ddName, String rowImage, String attempt) {
        if (rowImage.length() != RECORD_LENGTH) {
            return lengthConflict(ddName, rowImage.length(), attempt);
        }
        try {
            DecodedRow decoded = decodeRow(rowImage);
            return ReadResult.found(ddName, decoded.record(), decoded.storedImage());
        } catch (IllegalArgumentException undecodable) {
            return undecodableRow(ddName, undecodable, attempt);
        }
    }

    private static ReadResult lengthConflict(String ddName, int width, String attempt) {
        LOG.error("A row of the " + ddName + " access path read " + attempt + " is " + width
                + " byte(s) wide, but CARD-XREF-RECORD is declared " + RECORD_LENGTH
                + " bytes by app/cpy/CVACT03Y.cpy; reporting file status "
                + FileStatus.toStatusImage(FileStatus.RECORD_LENGTH_CONFLICT)
                + " rather than decoding fields from offsets that would not be theirs. A row that omits "
                + "the trailing FILLER X(" + CardXrefRecord.FILLER_LENGTH + ") must be widened with "
                + "FixedWidthCodec.padToDeclaredWidth first");
        return ReadResult.lengthError(ddName);
    }

    private static ReadResult undecodableRow(String ddName, IllegalArgumentException undecodable,
                                             String attempt) {
        LOG.error("A row of the " + ddName + " access path read " + attempt + " is " + RECORD_LENGTH
                + " bytes but is not a readable CARD-XREF-RECORD - a numeric span holds something other "
                + "than digits, or a stored byte is not a character in the configured dataset code page ("
                + undecodable.getClass().getName() + "); reporting file status "
                + FileStatus.toStatusImage(PERMANENT_ERROR_STATUS)
                + " rather than reporting a record that is present as absent");
        return ReadResult.other(ddName, PERMANENT_ERROR_STATUS);
    }

    private DecodedRow decodeArea(FixedWidthRecord area) {
        return new DecodedRow(CardXrefRecord.decodeSpan(area, codec),
                area.readString(0, CardXrefRecord.RECORD_LENGTH));
    }

    /**
     * One decoded row: the record's fields and the bytes they were decoded from.
     *
     * @param record the decoded record
     * @param storedImage the area's own characters, exactly {@link CardXrefRecord#RECORD_LENGTH} of them
     */
    private record DecodedRow(CardXrefRecord record, String storedImage) {
    }

    private KeyedAccess baseKeyedAccess() {
        BaseStatements sql = resolveBaseStatements();
        return new KeyedAccess(sql.selectByCardNumber(), sql.probeUnreadableRows());
    }

    private KeyedAccess alternateKeyedAccess() {
        AlternateStatements sql = resolveAlternateStatements();
        return new KeyedAccess(sql.selectByAccountId(), sql.probeUnreadableRows());
    }

    private BaseStatements resolveBaseStatements() {
        BaseStatements resolved = this.baseStatements;
        if (resolved == null) {
            resolved = composeBaseStatements();
        }
        return resolved;
    }

    private AlternateStatements resolveAlternateStatements() {
        AlternateStatements resolved = this.alternateStatements;
        if (resolved == null) {
            resolved = composeAlternateStatements();
        }
        return resolved;
    }

    private BaseStatements composeBaseStatements() {
        ResultSetExtractor<String> columnNameExtractor =
                CardXrefRepository::extractRecordImageColumn;
        String baseColumn = baseRelation.rememberRecordImageColumn(
                jdbcTemplate.query(baseRelation.describeStatement(), columnNameExtractor));
        BaseStatements composed = new BaseStatements(
                baseRelation.selectByKey(baseColumn),
                baseRelation.selectAllAscending(baseColumn),
                baseRelation.selectAfterAscending(baseColumn),
                baseRelation.selectUnreadableRows(baseColumn));
        this.baseStatements = composed;
        return composed;
    }

    private AlternateStatements composeAlternateStatements() {
        String alternateColumn = alternateIndexRelation.rememberRecordImageColumn(jdbcTemplate.query(
                alternateIndexRelation.describeStatement(),
                CardXrefRepository::extractRecordImageColumn));
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
     * The statements this repository sends against the base cluster, composed once against that relation's
     * discovered record-image column.
     *
     * @param selectByCardNumber {@code READ} on the base cluster, keyed on {@code XREF-CARD-NUM} at offset
     *     0
     * @param browseInKeyOrder the first read of a sequential browse of the base cluster, in ascending key
     *     order
     * @param browseAfterInKeyOrder every read after the first: the lowest key strictly above the record
     *     already returned
     * @param probeUnreadableRows the rows of the base cluster whose record image is absent
     */
    record BaseStatements(String selectByCardNumber,
                          String browseInKeyOrder,
                          String browseAfterInKeyOrder,
                          String probeUnreadableRows) {
    }

    /**
     * The statements this repository sends through the alternate-index path, composed once against that
     * path's discovered record-image column.
     *
     * @param selectByAccountId {@code READ} through the alternate-index path, keyed on {@code XREF-ACCT-ID}
     *     at offset 25
     * @param probeUnreadableRows the same absence proof over the path, so a read through the path proves
     *     its absence against the path it read
     */
    record AlternateStatements(String selectByAccountId, String probeUnreadableRows) {
    }

    /**
     * One access path's keyed read: the statement that performs it and the probe that proves an absence
     * against the same path.
     *
     * @param keyedStatement the keyed {@code SELECT} for this path
     * @param probeUnreadableRows this path's unreadable-row probe
     */
    private record KeyedAccess(String keyedStatement, String probeUnreadableRows) {
    }

    BaseStatements resolvedBaseStatements() {
        return baseStatements;
    }

    AlternateStatements resolvedAlternateStatements() {
        return alternateStatements;
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

    /**
     * The outcome of one cross-reference read: what happened, in the vocabulary every consumer already
     * speaks, plus the record when there is one.
     *
     * @param ddName the configuration key and CICS {@code FILE} name of the access path that was read:
     *     {@value CardXrefRepository#BASE_DD_NAME} or {@value CardXrefRepository#ALTERNATE_INDEX_DD_NAME}
     * @param status the two-character batch {@code FILE STATUS}: {@link FileStatus#OK},
     *     {@link FileStatus#END_OF_FILE}, {@link FileStatus#NOT_FOUND}
     * @param outcome the classification of {@code status}, from {@link FileStatus#outcomeOfStatus(String)},
     *     so the status and its meaning cannot drift apart
     * @param record the record, present exactly when one was read - so for {@link Outcome#OK} and for
     *     {@link Outcome#DUPLICATE}, which carries the first of the matching records, and absent for the other
     *     three
     * @param storedImage the row's stored fixed-width record image, exactly as the dataset holds it
     * @param cicsResp the CICS {@code RESP} the condition corresponds to: {@link FileStatus#NORMAL},
     *     {@link FileStatus#NOTFND}, {@link FileStatus#ENDFILE}
     * @param cicsResp2 the CICS {@code RESP2}, always {@link CardXrefRepository#CICS_RESP2_NOT_APPLICABLE}
     *     and documented there
     * @param diagnostic what the backend reported when it refused - present only on the
     *     {@link Outcome#OTHER} arm of a refusal the driver described
     */
    public record ReadResult(
            String ddName,
            String status,
            Outcome outcome,
            Optional<CardXrefRecord> record,
            Optional<String> storedImage,
            int cicsResp,
            int cicsResp2,
            Optional<BackendDiagnostic> diagnostic) {
        public ReadResult {
            Objects.requireNonNull(ddName, "A DD name is required on a read outcome: the caller moves "
                    + "it into ERROR-FILE, and it identifies which access path was read");
            Objects.requireNonNull(status, "A two-character FILE STATUS is required on a read outcome");
            Objects.requireNonNull(outcome, "An outcome classification is required on a read outcome");
            Objects.requireNonNull(record, "An Optional is required, empty rather than null, so no "
                    + "null escapes this type");
            Objects.requireNonNull(storedImage, "An Optional is required for the stored image, empty "
                    + "rather than null, so no null escapes this type");
            Objects.requireNonNull(diagnostic, "An Optional is required for the backend diagnostic, "
                    + "empty rather than null, so no null escapes this type");
            if (status.length() != FileStatus.STATUS_LENGTH) {
                throw new IllegalArgumentException("FILE STATUS '" + status + "' is "
                        + status.length() + " character(s); it is exactly "
                        + FileStatus.STATUS_LENGTH + " in COBOL and is compared as such.");
            }
            if (outcome != FileStatus.outcomeOfStatus(status)) {
                throw new IllegalArgumentException("Outcome " + outcome + " does not classify FILE "
                        + "STATUS '" + status + "', which classifies as "
                        + FileStatus.outcomeOfStatus(status) + ". The status and its meaning are two "
                        + "views of one fact and may not disagree.");
            }
            boolean shouldCarryRecord = outcome == Outcome.OK || outcome == Outcome.DUPLICATE;
            if (shouldCarryRecord != record.isPresent()) {
                throw new IllegalArgumentException("Outcome " + outcome + (shouldCarryRecord
                        ? " carries the record that was read, but none was supplied."
                        : " carries no record, but one was supplied.")
                        + " A record is present exactly for OK and for DUPLICATE, which hands back the "
                        + "first of the matching records as CICS does.");
            }
            // That is what makes DISPLAY CARD-XREF-RECORD reproducible: a caller on a record-bearing arm
            // can always reach the row's own bytes and never has to re-encode the decoded fields, which
            // would emit FILLER X(14) as spaces whatever the row held.
            if (storedImage.isPresent() != record.isPresent()) {
                throw new IllegalArgumentException(storedImage.isPresent()
                        ? "Outcome " + outcome + " carries no record, so it carries no stored image "
                                + "either; an image with no record to belong to has no meaning."
                        : "Outcome " + outcome + " carries a record, so it must carry the stored image "
                                + "that record was decoded from. Build it with "
                                + "ReadResult.found(ddName, record, storedImage) or "
                                + "ReadResult.duplicate(ddName, first, storedImage, cicsResp).");
            }
            storedImage.ifPresent(image -> {
                if (image.length() != CardXrefRecord.RECORD_LENGTH) {
                    throw new IllegalArgumentException("A stored CARD-XREF-RECORD image is "
                            + CardXrefRecord.RECORD_LENGTH + " characters as app/cpy/CVACT03Y.cpy "
                            + "declares, but this one is " + image.length()
                            + ". DISPLAY CARD-XREF-RECORD writes the whole record area, so an image of "
                            + "any other width would emit a line the program cannot produce. A row that "
                            + "omits the trailing FILLER X(" + CardXrefRecord.FILLER_LENGTH
                            + ") must be widened with FixedWidthCodec.padToDeclaredWidth first.");
                }
            });
        }

        /**
         * A record was read: status {@code '00'}, {@code RESP} of {@link FileStatus#NORMAL}.
         *
         * @param ddName the access path that was read
         * @param record the record that was read; never {@code null}
         * @param storedImage the row's own characters, exactly {@link CardXrefRecord#RECORD_LENGTH} of
         *     them; never {@code null}
         * @return the outcome
         * @throws NullPointerException if {@code record} or {@code storedImage} is {@code null}
         * @throws IllegalArgumentException if {@code storedImage} is not
         *     {@link CardXrefRecord#RECORD_LENGTH} characters
         */
        public static ReadResult found(String ddName, CardXrefRecord record, String storedImage) {
            Objects.requireNonNull(record, "A found outcome must carry the record it found");
            Objects.requireNonNull(storedImage, "A found outcome must carry the stored image the record "
                    + "was decoded from, so DISPLAY CARD-XREF-RECORD can write the row's own bytes");
            return new ReadResult(ddName, FileStatus.OK, Outcome.OK, Optional.of(record),
                    Optional.of(storedImage), FileStatus.NORMAL, CICS_RESP2_NOT_APPLICABLE,
                    Optional.empty());
        }

        /**
         * No record matched the key: status {@code '23'}, {@code RESP} of {@link FileStatus#NOTFND}.
         *
         * <p>The {@code WHEN DFHRESP(NOTFND)} arm online and the {@code INVALID KEY} arm in batch - a
         * normal branch in every consumer and never an exception.
         *
         * @param ddName the access path that was read
         * @return the outcome
         */
        public static ReadResult notFound(String ddName) {
            return new ReadResult(ddName, FileStatus.NOT_FOUND, Outcome.NOT_FOUND, Optional.empty(),
                    Optional.empty(), FileStatus.NOTFND, CICS_RESP2_NOT_APPLICABLE, Optional.empty());
        }

        /**
         * The browse has no further record: status {@code '10'}, {@code RESP} of
         * {@link FileStatus#ENDFILE}.
         *
         * @param ddName the access path that was read
         * @return the outcome
         */
        public static ReadResult endOfFile(String ddName) {
            return new ReadResult(ddName, FileStatus.END_OF_FILE, Outcome.END_OF_FILE, Optional.empty(),
                    Optional.empty(), FileStatus.ENDFILE, CICS_RESP2_NOT_APPLICABLE, Optional.empty());
        }

        /**
         * More than one record matched the key: status {@code '22'}, carrying the first of them, with the
         * {@code RESP} naming which key duplicated.
         *
         * @param ddName the access path that was read
         * @param first the first matching record, in base-key order; never {@code null}
         * @param storedImage the row's stored fixed-width record image, exactly as the dataset holds it
         * @param cicsResp {@link FileStatus#DUPREC} for a base-key duplicate, {@link FileStatus#DUPKEY} for
         *     an alternate-key duplicate
         * @return the outcome
         * @throws NullPointerException if {@code first} is {@code null}
         * @throws IllegalArgumentException if {@code cicsResp} is neither {@link FileStatus#DUPREC} nor
         *     {@link FileStatus#DUPKEY}
         */
        public static ReadResult duplicate(String ddName, CardXrefRecord first, String storedImage,
                int cicsResp) {
            Objects.requireNonNull(first, "A duplicate outcome must carry the first matching record, "
                    + "because CICS returns it alongside the DUPKEY condition");
            Objects.requireNonNull(storedImage, "A duplicate outcome returns a record, so it carries "
                    + "the stored image that record was decoded from");
            if (cicsResp != FileStatus.DUPREC && cicsResp != FileStatus.DUPKEY) {
                throw new IllegalArgumentException("A duplicate outcome must name which key "
                        + "duplicated: DUPREC (" + FileStatus.DUPREC + ") for the base key or DUPKEY ("
                        + FileStatus.DUPKEY + ") for an alternate key, not " + cicsResp + ". The two "
                        + "collapse onto FILE STATUS '" + FileStatus.DUPLICATE + "' in one direction "
                        + "only, so the distinction is kept where it is still known.");
            }
            return new ReadResult(ddName, FileStatus.DUPLICATE, Outcome.DUPLICATE, Optional.of(first),
                    Optional.of(storedImage), cicsResp, CICS_RESP2_NOT_APPLICABLE, Optional.empty());
        }

        /**
         * Anything else: the {@code WHEN OTHER} arm, which in every consumer is the error or abend path.
         *
         * @param ddName the access path that was read
         * @param status the status to report, which must classify as {@link Outcome#OTHER} - normally
         *     {@link CardXrefRepository#PERMANENT_ERROR_STATUS}
         * @return the outcome
         * @throws NullPointerException if {@code status} is {@code null}
         * @throws IllegalArgumentException if {@code status} is not two characters, or classifies as one of
         *     the four enumerated outcomes rather than as {@link Outcome#OTHER}
         */
        public static ReadResult other(String ddName, String status) {
            return new ReadResult(ddName, status, Outcome.OTHER, Optional.empty(), Optional.empty(),
                    FileStatus.NOTOPEN, CICS_RESP2_NOT_APPLICABLE, Optional.empty());
        }

        /**
         * A row whose width disagrees with the copybook: status {@link FileStatus#RECORD_LENGTH_CONFLICT},
         * {@code RESP} of {@link FileStatus#LENGERR}.
         *
         * @param ddName the access path that was read
         * @return the outcome
         */
        public static ReadResult lengthError(String ddName) {
            return new ReadResult(ddName, FileStatus.RECORD_LENGTH_CONFLICT, Outcome.OTHER,
                    Optional.empty(), Optional.empty(), FileStatus.LENGERR, CICS_RESP2_NOT_APPLICABLE,
                    Optional.empty());
        }

        /**
         * The {@code WHEN OTHER} arm, carrying what the backend actually said about the refusal.
         *
         * @param ddName the access path that was read
         * @param status the permanent-error file status
         * @param diagnostic what the backend reported
         * @return the outcome
         * @throws NullPointerException if {@code diagnostic} is {@code null}
         */
        public static ReadResult other(String ddName, String status, BackendDiagnostic diagnostic) {
            Objects.requireNonNull(diagnostic, "A diagnostic is required by this factory; use "
                    + "other(String, String) where there is no backend refusal to report");
            return new ReadResult(ddName, status, Outcome.OTHER, Optional.empty(), Optional.empty(),
                    FileStatus.NOTOPEN, CICS_RESP2_NOT_APPLICABLE, Optional.of(diagnostic));
        }

        /**
         * The row's own bytes as characters, for a caller already on an arm that carries a record.
         *
         * @return exactly {@link CardXrefRecord#RECORD_LENGTH} characters
         * @throws IllegalStateException if this arm carries no record, and so no image either
         */
        public String requireStoredImage() {
            return storedImage.orElseThrow(() -> new IllegalStateException("Outcome " + outcome
                    + " carries no record, so it carries no stored image. DISPLAY CARD-XREF-RECORD is "
                    + "reached only on an arm that carries one - app/cbl/CBACT03C.cbl:94 tests the "
                    + "status before it displays - so branch on the outcome first."));
        }

        /**
         * Whether a record was read - the {@code WHEN DFHRESP(NORMAL)} test.
         *
         * @return {@code true} for {@link Outcome#OK}
         */
        public boolean isFound() {
            return outcome == Outcome.OK;
        }

        /**
         * Whether the key matched nothing - the {@code WHEN DFHRESP(NOTFND)} test.
         *
         * @return {@code true} for {@link Outcome#NOT_FOUND}
         */
        public boolean isNotFound() {
            return outcome == Outcome.NOT_FOUND;
        }

        /**
         * Whether the browse is exhausted - what {@code app/cbl/CBACT03C.cbl:74} loops until.
         *
         * @return {@code true} for {@link Outcome#END_OF_FILE}
         */
        public boolean isEndOfFile() {
            return outcome == Outcome.END_OF_FILE;
        }

        /**
         * Whether more than one record matched the key.
         *
         * @return {@code true} for {@link Outcome#DUPLICATE}
         */
        public boolean isDuplicate() {
            return outcome == Outcome.DUPLICATE;
        }

        public boolean isOther() {
            return outcome == Outcome.OTHER;
        }

        /**
         * The {@code APPL-RESULT} value the batch consumers move for this outcome.
         *
         * @return {@link FileStatus#APPL_AOK}, {@link FileStatus#APPL_EOF} or
         *     {@link CardXrefRepository#APPL_RESULT_FATAL}
         */
        public int applResult() {
            return switch (outcome) {
                case OK -> FileStatus.APPL_AOK;
                case END_OF_FILE -> FileStatus.APPL_EOF;
                default -> APPL_RESULT_FATAL;
            };
        }

        /**
         * This status rendered as the four-character image {@code 9910-DISPLAY-IO-STATUS} produces, for a
         * caller composing that display line.
         *
         * @return the status image, exactly {@link FileStatus#STATUS_IMAGE_LENGTH} characters
         */
        public String statusImage() {
            return FileStatus.toStatusImage(status);
        }
    }

    /**
     * One sequential pass over the base cluster, in {@code XREF-CARD-NUM} order.
     *
     * <p>A cursor stands for one file position and is used by whoever opened it, exactly as a COBOL file
     * handle is used by the program that opened it.
     */
    public static final class BrowseCursor implements AutoCloseable {
        private final CardXrefRepository repository;

        private final String openStatus;

        private final boolean opened;

        private byte[] position;

        private int returned;

        private boolean exhausted;

        private boolean closed;

        private BrowseCursor(CardXrefRepository repository, String openStatus) {
            this.repository = repository;
            this.openStatus = openStatus;
            this.opened = FileStatus.isOk(openStatus);
            this.position = null;
            this.returned = 0;
            this.exhausted = false;
            this.closed = false;
        }

        /**
         * The status {@code OPEN INPUT} reported: the value {@code app/cbl/CBACT03C.cbl:121} tests before
         * moving {@code 0} or {@code 12} into {@code APPL-RESULT}.
         *
         * @return {@link FileStatus#OK} or {@link CardXrefRepository#PERMANENT_ERROR_STATUS}; never
         *     {@code null}, always two characters
         */
        public String openStatus() {
            return openStatus;
        }

        public Outcome openOutcome() {
            return FileStatus.outcomeOfStatus(openStatus);
        }

        /**
         * The {@code APPL-RESULT} the open sets: {@code 0} on success, {@code 12} otherwise
         * ({@code app/cbl/CBACT03C.cbl:119-125}, which seeds {@code 8}, moves {@code 0} on {@code '00'} and
         * {@code 12} on anything else).
         *
         * @return {@link FileStatus#APPL_AOK} or {@link CardXrefRepository#APPL_RESULT_FATAL}
         */
        public int openApplResult() {
            return FileStatus.isOk(openStatus) ? FileStatus.APPL_AOK : APPL_RESULT_FATAL;
        }

        /**
         * Whether this cursor is still usable, that is opened successfully and not yet closed.
         *
         * @return {@code true} when {@link #readNext()} will report an I/O outcome rather than throw
         */
        public boolean isOpen() {
            return !closed && opened;
        }

        /**
         * How many records this pass has returned so far, which is also the zero-based index of the record
         * the next {@link #readNext()} will return.
         *
         * @return the count; never negative
         */
        public int position() {
            return returned;
        }

        /**
         * The dataset this pass is reading: always the base cluster, because a browse of the cross
         * reference is a browse in base-key order.
         *
         * @return the configured {@value CardXrefRepository#BASE_DD_NAME} dataset name
         */
        public String datasetName() {
            return repository.baseDatasetName();
        }

        /**
         * Reads the next record: the Java form of {@code 1000-XREFFILE-GET-NEXT}
         * ({@code app/cbl/CBACT03C.cbl:92-116}) and of {@code CBSTM03B}'s {@code M03B-READ} arm for
         * {@code XREFFILE}.
         *
         * @return the discriminated outcome; never {@code null}
         * @throws IllegalStateException if this cursor has been closed
         */
        public ReadResult readNext() {
            if (closed) {
                throw new IllegalStateException("This browse of '" + repository.baseDatasetName()
                        + "' has been closed, so it has no next record. app/cbl/CBACT03C.cbl closes "
                        + "once, at :138, after its loop has ended - reading afterwards is a defect in "
                        + "the caller, not a file status. Open another browse instead.");
            }
            if (!opened) {
                return ReadResult.other(BASE_DD_NAME, openStatus);
            }
            if (exhausted) {
                // app/cbl/CBACT03C.cbl:98-99 then :108: the loop stops on the flag and never resumes, so
                // repeating the read repeats the answer.
                return ReadResult.endOfFile(BASE_DD_NAME);
            }

            BaseStatements sql;
            BrowseRow row;
            try {
                sql = repository.resolveBaseStatements();
                row = position == null
                        ? repository.browseRow(sql.browseInKeyOrder(), null)
                        : repository.browseRow(sql.browseAfterInKeyOrder(), position);
            } catch (DataAccessException refused) {
                return CardXrefRepository.other(BASE_DD_NAME, refused, "read the next record of the "
                        + BASE_DD_NAME + " base cluster during a browse");
            }

            if (!row.present()) {
                exhausted = true;
                return ReadResult.endOfFile(BASE_DD_NAME);
            }
            byte[] rowImage = row.image();
            if (rowImage == null) {
                exhausted = true;
                LOG.error("The cross-reference base cluster '" + repository.baseDatasetName() + "' (DD "
                        + BASE_DD_NAME + ") presented a row with no record image at column position "
                        + RECORD_IMAGE_COLUMN_INDEX + " during a browse; reporting file status "
                        + FileStatus.toStatusImage(PERMANENT_ERROR_STATUS) + " to the caller");
                return ReadResult.other(BASE_DD_NAME, PERMANENT_ERROR_STATUS);
            }
            // Decoding can fail on a malformed row, and the position must already have moved past it if it
            // does, exactly as a COBOL READ advances past the record it reported an error on.
            position = rowImage.clone();
            returned++;
            return repository.readRow(BASE_DD_NAME, rowImage, "during a browse");
        }

        /**
         * Closes this pass: the Java form of {@code 9000-XREFFILE-CLOSE}
         * ({@code app/cbl/CBACT03C.cbl:136-151}).
         *
         * @return {@link FileStatus#OK} for a browse that was open, or
         *     {@link CardXrefRepository#PERMANENT_ERROR_STATUS} for one that never opened; never {@code null},
         *     always two characters
         */
        public String closeBrowse() {
            closed = true;
            if (opened) {
                return FileStatus.OK;
            }
            LOG.error("A browse of the " + BASE_DD_NAME + " base cluster was closed although it never "
                    + "opened - its open reported file status " + FileStatus.toStatusImage(openStatus)
                    + "; reporting file status " + FileStatus.toStatusImage(PERMANENT_ERROR_STATUS)
                    + " from the close as well, because a CLOSE of a file that is not open is not a "
                    + "success");
            return PERMANENT_ERROR_STATUS;
        }

        /**
         * The {@code APPL-RESULT} the close sets: {@code 0} on success and {@code 12} otherwise, the ladder
         * at {@code app/cbl/CBACT03C.cbl:139-145}.
         *
         * @return {@link FileStatus#APPL_AOK} or {@link CardXrefRepository#APPL_RESULT_FATAL}
         */
        public int closeApplResult() {
            // The same condition closeBrowse() reports on, so the status and the APPL-RESULT cannot
            // disagree. Whether the cursor has since been closed is irrelevant: closing twice is idempotent
            // and reports the same outcome both times.
            return opened ? FileStatus.APPL_AOK : APPL_RESULT_FATAL;
        }

        /**
         * {@link AutoCloseable} form of {@link #closeBrowse()}, so a cursor can be used with
         * try-with-resources.
         */
        @Override
        public void close() {
            closeBrowse();
        }
    }
}
