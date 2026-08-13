package com.vsergeychik.carddemo.transaction;

import com.vsergeychik.carddemo.common.CicsResponse;
import com.vsergeychik.carddemo.common.DatasetObservation;
import com.vsergeychik.carddemo.common.DatasetRelation;
import com.vsergeychik.carddemo.common.DatasetRelation.BackendDiagnostic;
import com.vsergeychik.carddemo.common.DatasetRelation.KeySpan;
import com.vsergeychik.carddemo.common.FileStatus;
import com.vsergeychik.carddemo.common.FileStatus.Outcome;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.FixedWidthRecord;
import com.vsergeychik.carddemo.common.PhysicalSequence;
import com.vsergeychik.carddemo.common.RecordImageForm;
import com.vsergeychik.carddemo.config.CobolCharsetConfig;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBinding;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBindings;
import com.vsergeychik.carddemo.common.DatasetUnitOfWork;
import com.vsergeychik.carddemo.transaction.model.TranRecord;

import java.nio.charset.Charset;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

import javax.sql.DataSource;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.datasource.lookup.DataSourceLookupFailureException;
import org.springframework.stereotype.Repository;

/**
 * The one repository for the transaction master and its two sequential companions - the {@code TRANSACT}
 * KSDS, the {@code TRANFILE} input DD and the {@code SYSTRAN} output DD.
 */
@Repository
public class TransactionRepository {
    private static final Log LOG = LogFactory.getLog(TransactionRepository.class);

    /**
     * The CICS {@code FILE} name and configuration key of the transaction master KSDS -
     * {@code app/csd/CARDDEMO.CSD:76}.
     */
    public static final String CICS_FILE_NAME = "TRANSACT";

    /**
     * The batch DD name under which the transaction master is read - {@code app/jcl/POSTTRAN.jcl:28} - and
     * under which {@code app/jcl/TRANREPT.jcl:65} reads the sorted daily file instead.
     */
    public static final String INPUT_DD_NAME = "TRANFILE";

    /**
     * The configuration key of the sequential output generation {@code app/jcl/INTCALC.jcl:37-41} creates,
     * which that JCL addresses under the DD name {@link #CICS_FILE_NAME}.
     */
    public static final String SEQUENTIAL_OUTPUT_DD_NAME = "SYSTRAN";

    /**
     * The declared width of a CICS {@code FILE} name in the online programs' working storage:
     * {@code 05 WS-TRANSACT-FILE PIC X(08) VALUE 'TRANSACT'} ({@code app/cbl/COTRN00C.cbl:39},
     * {@code app/cbl/COTRN01C.cbl:39}, {@code app/cbl/COTRN02C.cbl:39}, {@code app/cbl/COBIL00C.cbl:40}).
     */
    public static final int CICS_FILE_NAME_LENGTH = 8;

    /**
     * The fixed record width, {@code (RECLN = 350)} of {@code app/cpy/CVTRA05Y.cpy}.
     */
    public static final int RECORD_LENGTH = TranRecord.RECORD_LENGTH;

    /**
     * The KSDS key width: {@code TRAN-ID PIC X(16)}.
     */
    public static final int KEY_LENGTH = TranRecord.TRAN_ID_KEY_LENGTH;

    /**
     * The key's 0-based offset within the record image: {@code TRAN-ID} leads the record.
     */
    public static final int KEY_OFFSET = TranRecord.TRAN_ID_OFFSET;

    public static final int RECORD_IMAGE_COLUMN_INDEX = DatasetRelation.RECORD_IMAGE_COLUMN_INDEX;

    private static final KeySpan KEY_SPAN = new KeySpan(KEY_OFFSET, KEY_LENGTH);

    // The batch programs' APPL-RESULT ladder and the one status this module composes for a failure the
    // COBOL never enumerated.

    /**
     * The {@code APPL-RESULT} every batch consumer moves on a fatal I/O outcome:
     * {@code MOVE 12 TO APPL-RESULT} at {@code app/cbl/CBTRN03C.cbl:257}, {@code app/cbl/CBACT04C.cbl:314},
     * {@code app/cbl/CBTRN02C.cbl:261} and {@code app/cbl/CBTRN01C.cbl:349}.
     */
    public static final int APPL_RESULT_FATAL = 12;

    private static final char PERMANENT_ERROR_FEEDBACK_CODE = 0;

    /**
     * The two-character status this module reports for an I/O failure the COBOL never enumerated.
     */
    public static final String PERMANENT_ERROR_STATUS = "9" + PERMANENT_ERROR_FEEDBACK_CODE;

    private static final String OPEN_OPERATION_NAME = "OPEN";

    private static final String READ_OPERATION_NAME = "READ";

    private static final String WRITE_OPERATION_NAME = "WRITE";

    private static final String DISPOSITION_OPERATION_NAME = "DISPOSITION";

    private static final String CLOSE_OPERATION_NAME = "CLOSE";

    private static final int SINGLE_ROW = 1;

    private static final int PASS_FETCH_SIZE = 32;

    private static final int DUPLICATE_DETECTION_ROW_LIMIT = 2;

    private static final int UNREADABLE_ROW_PROBE_LIMIT = 1;

    private static final char LIKE_ESCAPE = '\\';

    private final JdbcTemplate jdbcTemplate;

    private final FixedWidthCodec codec;

    private final RecordImageForm recordImageForm;

    private final PhysicalSequence physicalSequence;

    private final DatasetRelation masterRelation;

    private final DatasetRelation inputRelation;

    private final boolean inputIsKeyed;

    private final boolean inputIsReusable;

    private final DatasetRelation outputRelation;

    private volatile Statements statements;

    /**
     * Assembles the repository from the module's shared {@link JdbcTemplate}, the DD-name-keyed dataset
     * catalogue, the explicitly named dataset code page and the deployment's record-image representation.
     *
     * @param jdbcTemplate the module-wide template; never {@code null}
     * @param datasetBindings the {@code carddemo.datasets} catalogue; never {@code null}
     * @param datasetCharset the code page the dataset holds its record images in, from
     *     {@code carddemo.charset.dataset}
     * @param recordImageForm how the deployment's driver presents a record image over JDBC, from
     *     {@value RecordImageForm#FORM_PROPERTY}
     * @param physicalSequence the physical-record ordinal a physical-sequential input is read in, from
     *     {@value PhysicalSequence#EXPRESSION_PROPERTY}
     * @throws NullPointerException if any argument is {@code null}
     * @throws IllegalStateException if any binding is absent, declares a record length other than
     *     {@link #RECORD_LENGTH}, declares key geometry that contradicts its source, or names something that is
     *     not a well-formed dataset name
     */
    public TransactionRepository(
            JdbcTemplate jdbcTemplate,
            DatasetBindings datasetBindings,
            @Qualifier(CobolCharsetConfig.DATASET_CHARSET_BEAN_NAME) Charset datasetCharset,
            RecordImageForm recordImageForm,
            PhysicalSequence physicalSequence) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "A JdbcTemplate is required: the "
                + "transaction master is reached through the module's shared template over the "
                + "configuration-bound DataSource");
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
                + "required: " + INPUT_DD_NAME + " is read sequentially, and where its binding declares a "
                + "physical-sequential organization the order its records were written in is what a "
                + "READ returns - which SQL will not give without an ORDER BY. It is stated once, by "
                + PhysicalSequence.EXPRESSION_PROPERTY + ", and never decided per repository");
        RecordImageForm.requireSingleByteCodePage(datasetCharset);

        DatasetBinding master = requireRecordWidth(CICS_FILE_NAME,
                datasetBindings.binding(CICS_FILE_NAME));
        DatasetBinding input = requireRecordWidth(INPUT_DD_NAME,
                datasetBindings.binding(INPUT_DD_NAME));
        DatasetBinding output = requireRecordWidth(SEQUENTIAL_OUTPUT_DD_NAME,
                datasetBindings.binding(SEQUENTIAL_OUTPUT_DD_NAME));

        requireKeyedMaster(master);
        requireNoAlternateIndexPath(master);
        requireSequentialOutput(output);

        this.masterRelation = DatasetRelation.of(
                requireUsableDatasetName(CICS_FILE_NAME, master.dsname()), RECORD_LENGTH);
        this.inputRelation = DatasetRelation.of(
                requireUsableDatasetName(INPUT_DD_NAME, input.dsname()), RECORD_LENGTH);
        this.outputRelation = DatasetRelation.of(
                requireUsableDatasetName(SEQUENTIAL_OUTPUT_DD_NAME, output.dsname()), RECORD_LENGTH);
        this.inputIsKeyed = input.keyed();
        this.inputIsReusable = input.reusableCluster();
    }

    /**
     * Requires a binding to agree with {@code app/cpy/CVTRA05Y.cpy} about the record width.
     *
     * @param ddName the configuration key, for the diagnostic
     * @param binding the configured binding
     * @return {@code binding}, unchanged, so the check composes into an assignment
     * @throws IllegalStateException if the declared record length is not {@link #RECORD_LENGTH}
     */
    private static DatasetBinding requireRecordWidth(String ddName, DatasetBinding binding) {
        if (binding.recordLength() != RECORD_LENGTH) {
            throw new IllegalStateException("Dataset binding for '" + ddName + "' declares a record "
                    + "length of " + binding.recordLength() + ", but app/cpy/CVTRA05Y.cpy declares "
                    + "(RECLN " + RECORD_LENGTH + ") and this repository addresses the record by "
                    + "absolute offset against exactly that width. The CICS definition's "
                    + "RECORDFORMAT(V) (app/csd/CARDDEMO.CSD:81) does not change it: every JCL DCB "
                    + "over the same data declares LRECL=" + RECORD_LENGTH
                    + " (app/jcl/TRANREPT.jcl:31, app/jcl/INTCALC.jcl:39) and the record width is "
                    + "copybook-fixed. Correct carddemo.datasets." + ddName + ".record-length to "
                    + RECORD_LENGTH + ".");
        }
        return binding;
    }

    private static void requireKeyedMaster(DatasetBinding master) {
        if (!master.keyed()) {
            throw new IllegalStateException("Dataset binding for '" + CICS_FILE_NAME + "' declares "
                    + "organization '" + master.organization() + "', but the transaction master is an "
                    + "indexed cluster: app/csd/CARDDEMO.CSD:76-87 defines it as a KSDS and "
                    + "app/cbl/CBTRN02C.cbl:34-38 reads it ORGANIZATION IS INDEXED with RECORD KEY IS "
                    + "FD-TRANS-ID. Every keyed read, browse and add on this repository addresses that "
                    + "key. Correct carddemo.datasets." + CICS_FILE_NAME + ".organization to "
                    + DatasetBinding.KSDS + ".");
        }
        Integer declaredKeyLength = master.keyLength();
        if (declaredKeyLength == null || declaredKeyLength != KEY_LENGTH) {
            throw new IllegalStateException("Dataset binding for '" + CICS_FILE_NAME + "' declares key "
                    + "length " + declaredKeyLength + ", but the key is TRAN-ID PIC X(" + KEY_LENGTH
                    + ") - app/cpy/CVTRA05Y.cpy - and every consumer passes "
                    + "KEYLENGTH(LENGTH OF TRAN-ID), for example app/cbl/COTRN01C.cbl:274. A "
                    + "narrower width would turn a keyed read into a prefix match over more records "
                    + "than the key names; a wider one would reach into TRAN-TYPE-CD. Correct "
                    + "carddemo.datasets." + CICS_FILE_NAME + ".key-length to " + KEY_LENGTH + ".");
        }
        if (master.keyOffsetOrZero() != KEY_OFFSET) {
            throw new IllegalStateException("Dataset binding for '" + CICS_FILE_NAME + "' declares key "
                    + "offset " + master.keyOffsetOrZero() + ", but TRAN-ID leads the record: it is the "
                    + "first field of app/cpy/CVTRA05Y.cpy, at 0-based offset " + KEY_OFFSET
                    + ". Remove carddemo.datasets." + CICS_FILE_NAME + ".key-offset, which defaults to "
                    + KEY_OFFSET + ".");
        }
    }

    private static void requireNoAlternateIndexPath(DatasetBinding master) {
        if (master.base() != null) {
            throw new IllegalStateException("Dataset binding for '" + CICS_FILE_NAME + "' declares "
                    + "base '" + master.base() + "', but it IS a base cluster: "
                    + "app/csd/CARDDEMO.CSD:76-87 defines FILE(" + CICS_FILE_NAME + ") over "
                    + "one KSDS and defines no alternate-index path over it. Only an aix-path entry "
                    + "declares a base. Remove carddemo.datasets." + CICS_FILE_NAME + ".base.");
        }
        if (master.alternateKey() != null) {
            throw new IllegalStateException("Dataset binding for '" + CICS_FILE_NAME + "' nominates "
                    + "alternate key '" + master.alternateKey() + "', but the transaction master has "
                    + "no alternate index anywhere in app/csd/CARDDEMO.CSD, and no program reads it by "
                    + "any key but TRAN-ID. This repository therefore exposes no second finder (gate "
                    + "G45). Remove carddemo.datasets." + CICS_FILE_NAME + ".alternate-key.");
        }
    }

    private static void requireSequentialOutput(DatasetBinding output) {
        if (output.keyed()) {
            throw new IllegalStateException("Dataset binding for '" + SEQUENTIAL_OUTPUT_DD_NAME
                    + "' declares organization '" + output.organization() + "', but it is a sequential "
                    + "output: app/jcl/INTCALC.jcl:37-41 creates it with DISP=(NEW,CATLG,DELETE) and "
                    + "DCB=(RECFM=F,LRECL=" + RECORD_LENGTH + ") and app/cbl/CBACT04C.cbl:53-55 opens "
                    + "it ORGANIZATION IS SEQUENTIAL / ACCESS MODE IS SEQUENTIAL. It is written front "
                    + "to back and never by key. Correct carddemo.datasets." + SEQUENTIAL_OUTPUT_DD_NAME
                    + ".organization to sequential.");
        }
    }

    private static String requireUsableDatasetName(String ddName, String candidate) {
        if (candidate == null || candidate.isBlank()) {
            throw new IllegalStateException("The dataset binding for '" + ddName + "' declares no "
                    + "dataset name. Set carddemo.datasets." + ddName + ".dsname; this repository "
                    + "composes every statement from configuration alone and hard-codes no dataset "
                    + "name (gate G46).");
        }
        return DatasetRelation.requireDatasetName(candidate);
    }

    /**
     * The resolved transaction-master dataset name, exactly as configuration declares it.
     *
     * @return the configured {@link #CICS_FILE_NAME} dataset name; never {@code null}, never blank
     */
    public String datasetName() {
        return masterRelation.dsname();
    }

    /**
     * The resolved input dataset name for {@link #INPUT_DD_NAME}, exactly as configuration declares it.
     *
     * @return the configured {@link #INPUT_DD_NAME} dataset name; never {@code null}, never blank
     */
    public String inputDatasetName() {
        return inputRelation.dsname();
    }

    /**
     * The resolved sequential-output dataset name for {@link #SEQUENTIAL_OUTPUT_DD_NAME}.
     *
     * @return the configured {@link #SEQUENTIAL_OUTPUT_DD_NAME} dataset name; never {@code null}, never
     *     blank
     */
    public String sequentialOutputDatasetName() {
        return outputRelation.dsname();
    }

    /**
     * The code page this repository decodes and encodes records in, as injected.
     *
     * @return the dataset charset; never {@code null}
     */
    public Charset datasetCharset() {
        return codec.charset();
    }

    /**
     * The fixed record width in bytes, cross-checked against all three bindings at construction.
     *
     * @return {@link #RECORD_LENGTH}
     */
    public int recordLength() {
        return RECORD_LENGTH;
    }

    public int keyLength() {
        return KEY_LENGTH;
    }

    /**
     * The statement that describes the master without transferring a row.
     *
     * @return the describe statement over the transaction master
     */
    public String describeStatement() {
        return masterRelation.describeStatement();
    }

    /**
     * The statement that describes the input dataset without transferring a row.
     *
     * @return the describe statement over the {@link #INPUT_DD_NAME} dataset
     */
    public String describeInputStatement() {
        return inputRelation.describeStatement();
    }

    /**
     * Reshapes a caller's transaction identifier into the key image the dataset holds.
     *
     * @param tranId the identifier as the caller holds it; never {@code null}
     * @return exactly {@link #KEY_LENGTH} characters
     * @throws NullPointerException if {@code tranId} is {@code null}
     */
    public String keyImageOf(String tranId) {
        Objects.requireNonNull(tranId, "A transaction identifier is required to compose the TRAN-ID "
                + "key; an absent key is a defect in the caller and not a NOTFND outcome. Pass an empty "
                + "string for a key of SPACES.");
        return codec.movePicX(tranId, KEY_LENGTH);
    }

    // INTO TRAN-RECORD :514-530 9000-TRANFILE-CLOSE - CLOSE app/cbl/CBTRN01C.cbl:343-359 0500-TRANFILE-OPEN
    // - OPEN INPUT, and never a READ :451-467 9500-TRANFILE-CLOSE - CLOSE.

    /**
     * Opens the globally configured {@link #INPUT_DD_NAME} dataset for input.
     *
     * <p>A cursor cannot be rewound: a caller that wants to start again opens another one, exactly as the
     * COBOL closes and reopens.
     *
     * @return a freshly opened cursor whose {@link InputFile#openStatus()} reports whether the open
     *     succeeded; never {@code null}
     */
    public InputFile openInput() {
        return openInput(inputRelation, inputIsKeyed, INPUT_DD_NAME);
    }

    /**
     * Opens a job-scoped {@link #INPUT_DD_NAME} dataset for input.
     *
     * @param jobScopedBinding the binding the job resolved for its own {@link #INPUT_DD_NAME}; never
     *     {@code null}
     * @return a freshly opened cursor over that dataset; never {@code null}
     * @throws NullPointerException if {@code jobScopedBinding} is {@code null}
     * @throws IllegalStateException if the binding declares a record length other than
     *     {@link #RECORD_LENGTH}
     */
    public InputFile openInput(DatasetBinding jobScopedBinding) {
        Objects.requireNonNull(jobScopedBinding, "A job-scoped dataset binding is required: call "
                + "openInput() for the globally configured " + INPUT_DD_NAME + " binding instead of "
                + "passing null");
        requireRecordWidth(INPUT_DD_NAME, jobScopedBinding);
        DatasetRelation relation;
        try {
            relation = DatasetRelation.of(
                    requireUsableDatasetName(INPUT_DD_NAME, jobScopedBinding.dsname()), RECORD_LENGTH);
        } catch (IllegalStateException | IllegalArgumentException notADatasetName) {
            LOG.error("A job-scoped " + INPUT_DD_NAME + " binding does not name a dataset this module "
                    + "can address - " + notADatasetName.getClass().getSimpleName()
                    + "; reporting file status " + FileStatus.toStatusImage(PERMANENT_ERROR_STATUS)
                    + " from the open rather than throwing into the middle of a job");
            return new InputFile(this, null, INPUT_DD_NAME, PERMANENT_ERROR_STATUS, null);
        }
        return openInput(relation, jobScopedBinding.keyed(), INPUT_DD_NAME);
    }

    private InputFile openInput(DatasetRelation relation, boolean keyed, String ddName) {
        InputStatements composed;
        try {
            composed = composeInputStatements(relation, keyed);
        } catch (DataAccessException refused) {
            return new InputFile(this, relation, ddName,
                    reportRefusal(OPEN_OPERATION_NAME, ddName, "for input", refused), null);
        } catch (IllegalStateException noRecordImageColumn) {
            // A keyed relation that presents no usable record-image column cannot be ordered by key, and a
            // read of it in an unspecified order is not the read the COBOL performs.
            LOG.error("The " + ddName + " dataset is configured as an indexed cluster but presents no "
                    + "usable record-image column at position " + RECORD_IMAGE_COLUMN_INDEX
                    + ", so a read of it in key order cannot be composed; reporting file status "
                    + FileStatus.toStatusImage(PERMANENT_ERROR_STATUS) + " from the open");
            return new InputFile(this, relation, ddName, PERMANENT_ERROR_STATUS, null);
        }
        return new InputFile(this, relation, ddName, FileStatus.OK, composed);
    }

    private InputStatements composeInputStatements(DatasetRelation relation, boolean keyed) {
        String described = jdbcTemplate.query(relation.describeStatement(),
                TransactionRepository::extractRecordImageColumn);
        if (!keyed) {
            // No record-image column name is needed, and none is required: a physical-sequential read names
            // no record-image column at all, so demanding one here would fail an open that COBOL performs
            // happily.
            return new InputStatements(relation.selectAllInPhysicalSequence(physicalSequence),
                    Optional.empty());
        }
        String column = relation.rememberRecordImageColumn(described);
        return new InputStatements(relation.selectAllAscending(column),
                Optional.of(relation.selectAfterAscending(column)));
    }

    /**
     * Opens the {@link #SEQUENTIAL_OUTPUT_DD_NAME} generation for output: the Java form of
     * {@code 0400-TRANFILE-OPEN} ({@code app/cbl/CBACT04C.cbl:307-323}).
     *
     * <p>The DD name is the same spelling as the CICS file and the dataset is not the same dataset, which
     * is exactly the collision this class keeps three keys for.
     *
     * @return a new per-execution output handle, carrying whatever status the open reported; never
     *     {@code null}
     */
    public OutputFile openOutput() {
        return openOutput(outputRelation, SEQUENTIAL_OUTPUT_DD_NAME);
    }

    /**
     * Opens the sequential output the caller's own DD binding names.
     *
     * @param binding the caller's resolved binding, normally from
     *     {@code BatchConfig.datasetBinding(jobKey, ddName)}
     * @param ddName the DD name it was resolved for; carried into the diagnostic and the refusal
     * @return a new per-execution output handle; never {@code null}
     * @throws NullPointerException if either argument is {@code null}
     * @throws IllegalStateException if the binding declares a record width other than
     *     {@link #RECORD_LENGTH}, or no usable dataset name
     */
    public OutputFile openOutput(DatasetBinding binding, String ddName) {
        Objects.requireNonNull(ddName, "A DD name is required: it is what a diagnostic names when the "
                + "binding is at fault");
        Objects.requireNonNull(binding, "A resolved dataset binding is required for DD name '" + ddName
                + "': a job writes through the DD its JCL declares, not through the name this repository "
                + "resolved at construction");
        DatasetBinding checked = requireRecordWidth(ddName, binding);
        // The caller's own relation and the caller's own DD name, always. The two agreeing is a property of
        // the shipped configuration, not of the code.
        return openOutput(DatasetRelation.of(
                requireUsableDatasetName(ddName, checked.dsname()), RECORD_LENGTH), ddName);
    }

    /**
     * {@code OPEN OUTPUT} of the indexed master - VSAM load mode - {@code app/cbl/CBTRN02C.cbl:256}.
     *
     * @return a new per-execution load-mode handle carrying the status the open reported; never
     *     {@code null}
     */
    public LoadModeFile openLoadMode() {
        String openStatus;
        // Names the statement being issued, so a refusal says which of the three it was rather than
        // "the open". Reassigned before each one; read only from the catch.
        String attempting = "to count the records load mode would have to reset";
        try {
            Integer held = jdbcTemplate.queryForObject(inputRelation.countAllStatement(), Integer.class);
            int holding = held == null ? 0 : held;
            if (holding == 0) {
                openStatus = FileStatus.OK;
            } else if (inputIsReusable) {
                attempting = "to reset the REUSE cluster load mode opens over";
                int reset = jdbcTemplate.update(inputRelation.deleteAll());
                openStatus = FileStatus.OK;
                LOG.warn("OPEN OUTPUT of " + INPUT_DD_NAME + " (" + inputRelation.dsname()
                        + ") is VSAM load mode - app/cbl/CBTRN02C.cbl:256 over the ORGANIZATION IS "
                        + "INDEXED SELECT at :34-38 - and this cluster is configured REUSE, so the open "
                        + "resets it. It held " + holding + " record(s) and " + reset + " were removed "
                        + "before load mode began; the run's postings are the only records it will hold. "
                        + "Reporting file status " + FileStatus.toStatusImage(openStatus)
                        + ". This is what REUSE means, not a discretionary clear: were the records left "
                        + "in place, this run would post on top of records the source's own open had "
                        + "already discarded");
            } else {
                openStatus = FileStatus.OPEN_MODE_CONFLICT;
                LOG.error("OPEN OUTPUT of " + INPUT_DD_NAME + " (" + inputRelation.dsname()
                        + ") is VSAM load mode - app/cbl/CBTRN02C.cbl:256 over the ORGANIZATION IS "
                        + "INDEXED SELECT at :34-38 - and load mode requires an empty base cluster. This "
                        + "one holds " + holding + " record(s) and is configured NOREUSE, so the open "
                        + "cannot reset it. Reporting file status " + FileStatus.toStatusImage(openStatus)
                        + ", which is what app/cbl/CBTRN02C.cbl:257-268 abends on. This is the source's "
                        + "outcome against app/catlg/LISTCAT.txt's catalogued master, not a defect of "
                        + "this run: POSTTRAN cannot post into a master that already holds records");
            }
        } catch (DataAccessException refused) {
            openStatus = reportRefusal(OPEN_OPERATION_NAME, INPUT_DD_NAME, attempting, refused);
        }
        return new LoadModeFile(inputRelation, INPUT_DD_NAME, openStatus);
    }

    private OutputFile openOutput(DatasetRelation relation, String ddName) {
        String openStatus;
        try {
            jdbcTemplate.execute(relation.describeStatement());
            jdbcTemplate.update(relation.deleteAll());
            openStatus = FileStatus.OK;
        } catch (DataAccessException refused) {
            openStatus = reportRefusal(OPEN_OPERATION_NAME, ddName, "for output", refused);
        }
        return new OutputFile(this, relation, ddName, openStatus);
    }

    /**
     * Adds one record to the transaction master, keyed on the record's own {@code TRAN-ID}.
     *
     * @param record the record to add, carrying its own key; never {@code null}
     * @return the discriminated outcome; never {@code null}
     * @throws NullPointerException if {@code record} is {@code null}
     * @throws IllegalStateException if no unit of work is open, in which case nothing has been attempted
     */
    public WriteResult write(TranRecord record) {
        Objects.requireNonNull(record, "A transaction record is required to add one: the WRITE is "
                + "addressed by the TRAN-ID the record itself carries (app/cbl/COTRN02C.cbl:716), so "
                + "there is no record to write and no key to write it under");
        String keyImage = keyImageOf(record.tranId());
        byte[] image = record.encode(codec.charset());
        if (image.length != RECORD_LENGTH) {
            LOG.error("A transaction record encoded to " + image.length + " bytes where the dataset "
                    + "holds " + RECORD_LENGTH + "; refusing the add rather than storing a record no "
                    + "read of it could decode");
            return WriteResult.other(CICS_FILE_NAME, PERMANENT_ERROR_STATUS,
                    CicsResponse.of(FileStatus.LENGERR),
                    DatasetObservation.recordWidth(image.length));
        }
        DatasetUnitOfWork.requireActiveToPersist("A write to the transaction master, which the WRITE at "
                + "app/cbl/CBTRN02C.cbl:564 and the EXEC CICS WRITE at app/cbl/COTRN02C.cbl:716-724 "
                + "issue from the record area", masterRelation.dsname());
        try {
            Statements sql = resolveStatements();
            FetchedRows existing = fetch(sql.selectByKey(), KEY_SPAN.pattern(keyImage), SINGLE_ROW);
            if (existing.rowCount() > 0) {
                return WriteResult.duplicate(CICS_FILE_NAME);
            }
            int added = insert(sql.insertRecordImage(), image);
            if (added == SINGLE_ROW) {
                return WriteResult.written(CICS_FILE_NAME);
            }
            LOG.error("A keyed add to the transaction master reported " + added + " affected row(s) "
                    + "where exactly " + SINGLE_ROW + " was expected; reporting file status "
                    + FileStatus.toStatusImage(PERMANENT_ERROR_STATUS) + " rather than reporting a "
                    + "record as stored");
            return WriteResult.other(CICS_FILE_NAME, PERMANENT_ERROR_STATUS,
                    CicsResponse.of(FileStatus.INVREQ), DatasetObservation.matchingRows(added));
        } catch (DataAccessException rejected) {
            BackendDiagnostic diagnostic = BackendDiagnostic.of(rejected);
            if (reportsDuplicateKey(rejected, diagnostic)) {
                LOG.error("A keyed add to the transaction master was refused as an integrity violation "
                        + "- " + diagnostic.describe() + "; reporting file status "
                        + FileStatus.toStatusImage(FileStatus.DUPLICATE)
                        + ", the duplicate-key condition, because that is what the relation reported");
                return WriteResult.duplicate(CICS_FILE_NAME, diagnostic);
            }
            return WriteResult.other(CICS_FILE_NAME,
                    reportRefusal(WRITE_OPERATION_NAME, CICS_FILE_NAME, "by key", rejected),
                    CicsResponse.ofBatchStatus(PERMANENT_ERROR_STATUS), diagnostic);
        }
    }

    private static boolean reportsDuplicateKey(DataAccessException rejected,
                                               BackendDiagnostic diagnostic) {
        return diagnostic.integrityViolation() || rejected instanceof DataIntegrityViolationException;
    }

    /**
     * Reads one record by its {@code TRAN-ID}: the Java form of {@code READ-TRANSACT-FILE}
     * ({@code app/cbl/COTRN01C.cbl:269-278}).
     *
     * <p>The key goes through the alphanumeric {@code MOVE} of {@link #keyImageOf(String)}, so a short
     * value is space-padded on the right exactly as {@code MOVE TRNIDINI OF COTRN1AI TO TRAN-ID} pads a
     * screen field.
     *
     * @param tranId the transaction identifier to look up; never {@code null}, and it may be shorter or
     *     longer than {@link #KEY_LENGTH} because the {@code PIC X} move reshapes it
     * @return the discriminated outcome; never {@code null}
     * @throws NullPointerException if {@code tranId} is {@code null}
     */
    public ReadResult readByTranId(String tranId) {
        return readKeyed(keyImageOf(tranId), false);
    }

    /**
     * Reads one record by its {@code TRAN-ID} with the record held for update: the exact form
     * {@code app/cbl/COTRN01C.cbl:269-278} issues, {@code UPDATE} option and all ({@code :275}).
     *
     * @param tranId the transaction identifier to look up; never {@code null}
     * @return the discriminated outcome; never {@code null}
     * @throws NullPointerException if {@code tranId} is {@code null}
     * @throws IllegalStateException if no unit of work is open
     */
    public ReadResult readForUpdateByTranId(String tranId) {
        String keyImage = keyImageOf(tranId);
        DatasetUnitOfWork.requireActive("A read for update of the transaction master, which requests "
                + "the row lock that UPDATEMODEL(LOCKING) gives a CICS READ ... UPDATE "
                + "(app/cbl/COTRN01C.cbl:275)", masterRelation.dsname());
        return readKeyed(keyImage, true);
    }

    private ReadResult readKeyed(String keyImage, boolean locking) {
        try {
            Statements sql = resolveStatements();
            String statement = locking ? sql.selectByKeyForUpdate() : sql.selectByKey();
            FetchedRows rows = fetch(statement, KEY_SPAN.pattern(keyImage),
                    DUPLICATE_DETECTION_ROW_LIMIT);
            return provenAbsence(classify(rows, CICS_FILE_NAME, false).result(),
                    sql.probeUnreadableRows());
        } catch (DataAccessException refused) {
            return ReadResult.other(CICS_FILE_NAME,
                    reportRefusal(READ_OPERATION_NAME, CICS_FILE_NAME,
                            locking ? "by key with the UPDATE option" : "by key", refused),
                    BackendDiagnostic.of(refused));
        }
    }

    /**
     * Positions a browse at the boundary of the file and returns the handle that walks it.
     *
     * @param direction which way the browse will be walked; never {@code null}
     * @return a fresh handle, positioned and not yet read from; never {@code null}
     * @throws NullPointerException if {@code direction} is {@code null}
     */
    public Browse startBrowse(BrowseDirection direction) {
        return startedBrowse(requireDirection(direction), null);
    }

    /**
     * Positions a browse at a concrete key and returns the handle that walks it.
     *
     * <p>The key is reshaped by {@link #keyImageOf(String)}, so a screen field that is shorter than the key
     * is space-padded on the right exactly as {@code MOVE TRNIDINI OF COTRN0AI TO TRAN-ID} pads it at
     * {@code :210}.
     *
     * @param ridfldTranId the {@code RIDFLD} value to position at; never {@code null}
     * @param direction which way the browse will be walked; never {@code null}
     * @return a fresh handle, positioned and not yet read from; never {@code null}
     * @throws NullPointerException if either argument is {@code null}
     */
    public Browse startBrowse(String ridfldTranId, BrowseDirection direction) {
        return startedBrowse(requireDirection(direction), keyImageOf(ridfldTranId));
    }

    private Browse startedBrowse(BrowseDirection direction, String anchorKey) {
        Browse browse = new Browse(this, direction, anchorKey);
        browse.position();
        return browse;
    }

    private static BrowseDirection requireDirection(BrowseDirection direction) {
        return Objects.requireNonNull(direction, "A browse direction is required: the legacy code "
                + "issues a separate STARTBR for each direction and never reads one the other way, so a "
                + "browse is never direction-less");
    }

    private Step browseAnchorStep(String statement, String operand, String ddName) {
        try {
            return classify(fetch(statement, operand, SINGLE_ROW), ddName, true);
        } catch (DataAccessException refused) {
            return failedStep(ddName, "while positioning a browse", refused);
        }
    }

    private Step browseAnchorStep(String statement, String operand, String pattern, String ddName) {
        PreparedStatementCreator creator = connection -> {
            PreparedStatement prepared = connection.prepareStatement(statement);
            prepared.setMaxRows(SINGLE_ROW);
            prepared.setFetchSize(SINGLE_ROW);
            recordImageForm.bindOperand(prepared, 1, operand, codec.charset());
            recordImageForm.bindOperand(prepared, 2, pattern, codec.charset());
            return prepared;
        };
        try {
            return classify(execute(creator, SINGLE_ROW), ddName, true);
        } catch (DataAccessException refused) {
            return failedStep(ddName, "while positioning a browse", refused);
        }
    }

    private Step unparameterisedStep(String statement, String ddName) {
        PreparedStatementCreator creator = connection -> {
            PreparedStatement prepared = connection.prepareStatement(statement);
            prepared.setMaxRows(SINGLE_ROW);
            prepared.setFetchSize(SINGLE_ROW);
            return prepared;
        };
        try {
            return classify(execute(creator, SINGLE_ROW), ddName, true);
        } catch (DataAccessException refused) {
            return failedStep(ddName, "at the start of a browse", refused);
        }
    }

    private Step advanceStep(String statement, byte[] position, String ddName) {
        PreparedStatementCreator creator = connection -> {
            PreparedStatement prepared = connection.prepareStatement(statement);
            prepared.setMaxRows(SINGLE_ROW);
            prepared.setFetchSize(SINGLE_ROW);
            recordImageForm.bindImage(prepared, 1, position, codec.charset());
            return prepared;
        };
        try {
            return classify(execute(creator, SINGLE_ROW), ddName, true);
        } catch (DataAccessException refused) {
            return failedStep(ddName, "during a browse", refused);
        }
    }

    private FetchedRows fetch(String statement, String operand, int rowLimit) {
        PreparedStatementCreator creator = connection -> {
            PreparedStatement prepared = connection.prepareStatement(statement);
            prepared.setMaxRows(rowLimit);
            prepared.setFetchSize(rowLimit);
            recordImageForm.bindOperand(prepared, 1, operand, codec.charset());
            return prepared;
        };
        return execute(creator, rowLimit);
    }

    private FetchedRows execute(PreparedStatementCreator creator, int rowLimit) {
        ResultSetExtractor<FetchedRows> extractor = resultSet -> extractRows(resultSet, rowLimit);
        FetchedRows rows = jdbcTemplate.query(creator, extractor);
        return rows == null ? FetchedRows.empty() : rows;
    }

    FetchedRows extractRows(ResultSet resultSet, int rowLimit) throws SQLException {
        int rowCount = 0;
        byte[] firstImage = null;
        boolean firstImageMissing = false;
        while (rowCount < rowLimit && resultSet.next()) {
            if (rowCount == 0) {
                firstImage = recordImageForm.readImage(resultSet, RECORD_IMAGE_COLUMN_INDEX,
                        codec.charset());
                firstImageMissing = firstImage == null;
            }
            rowCount++;
        }
        return new FetchedRows(rowCount, firstImage, firstImageMissing);
    }

    private ReadResult provenAbsence(ReadResult classified, String unreadableRowsProbe) {
        if (!classified.isNotFound()) {
            return classified;
        }
        FetchedRows unreadable = fetch(unreadableRowsProbe, UNREADABLE_ROW_PROBE_LIMIT);
        if (unreadable.rowCount() == 0) {
            return classified;
        }
        LOG.error("A keyed read of the " + CICS_FILE_NAME + " dataset matched no row, but the dataset holds "
                + "a row with no record image at column position " + RECORD_IMAGE_COLUMN_INDEX
                + " - and TRAN-ID is part of that image, so that row's key cannot be known; reporting file "
                + "status " + FileStatus.toStatusImage(PERMANENT_ERROR_STATUS)
                + " rather than reporting as absent a transaction that may well be present");
        return ReadResult.other(CICS_FILE_NAME, PERMANENT_ERROR_STATUS,
                CicsResponse.of(FileStatus.INVREQ));
    }

    private FetchedRows fetch(String statement, int rowLimit) {
        return execute(connection -> {
            PreparedStatement prepared = connection.prepareStatement(statement);
            prepared.setMaxRows(rowLimit);
            prepared.setFetchSize(rowLimit);
            return prepared;
        }, rowLimit);
    }

    private Step classify(FetchedRows rows, String ddName, boolean emptyMeansEndOfFile) {
        if (rows.rowCount() == 0) {
            return new Step(emptyMeansEndOfFile
                    ? ReadResult.endOfFile(ddName)
                    : ReadResult.notFound(ddName), null, false);
        }
        if (rows.firstImageMissing()) {
            LOG.error("The " + ddName + " dataset presented a row with no record image at column "
                    + "position " + RECORD_IMAGE_COLUMN_INDEX + "; reporting file status "
                    + FileStatus.toStatusImage(PERMANENT_ERROR_STATUS)
                    + " rather than reporting a record that is present as absent");
            return new Step(ReadResult.other(ddName, PERMANENT_ERROR_STATUS), null, true);
        }
        byte[] image = rows.firstImage();
        TranRecord record;
        try {
            record = TranRecord.decode(image, codec.charset());
        } catch (IllegalArgumentException wrongWidth) {
            // A row that is not exactly 350 bytes is not a TRAN-RECORD, and widening or truncating it would
            // shift every field after the first.
            LOG.error("The " + ddName + " dataset presented a row of " + image.length + " byte(s) where "
                    + "app/cpy/CVTRA05Y.cpy declares (RECLN " + RECORD_LENGTH + "); reporting file "
                    + "status " + FileStatus.toStatusImage(PERMANENT_ERROR_STATUS)
                    + " rather than decoding a record against the wrong layout");
            return new Step(ReadResult.other(ddName, PERMANENT_ERROR_STATUS,
                    CicsResponse.of(FileStatus.LENGERR),
                    DatasetObservation.recordWidth(image.length)), null, true);
        }
        if (rows.rowCount() > 1) {
            return new Step(ReadResult.duplicate(ddName, record), image.clone(), true);
        }
        return new Step(ReadResult.found(ddName, record), image.clone(), true);
    }

    private Step classifyRow(byte[] image, String ddName) {
        return classify(new FetchedRows(SINGLE_ROW, image, image == null), ddName, true);
    }

    private static Step failedStep(String ddName, String attempt, DataAccessException refusal) {
        return failedStep(ddName, attempt, refusal, false);
    }

    private static Step failedStep(String ddName, String attempt, DataAccessException refusal,
                                   boolean rowSeen) {
        return new Step(ReadResult.other(ddName,
                reportRefusal(READ_OPERATION_NAME, ddName, attempt, refusal),
                BackendDiagnostic.of(refusal)), null, rowSeen);
    }

    private SequentialPass openPass(String statement, String ddName) {
        DataSource dataSource = jdbcTemplate.getDataSource();
        if (dataSource == null) {
            throw new DataSourceLookupFailureException("The module's JdbcTemplate carries no DataSource, "
                    + "so no cursor can be opened over the " + ddName + " dataset");
        }
        Connection connection = null;
        PreparedStatement prepared = null;
        ResultSet rows = null;
        try {
            connection = dataSource.getConnection();
            prepared = connection.prepareStatement(statement, ResultSet.TYPE_FORWARD_ONLY,
                    ResultSet.CONCUR_READ_ONLY);
            prepared.setFetchSize(PASS_FETCH_SIZE);
            rows = prepared.executeQuery();
            return new SequentialPass(connection, prepared, rows, ddName);
        } catch (SQLException refusal) {
            releaseQuietly(rows, prepared, connection);
            throw translate("open a forward-only pass", statement, refusal);
        }
    }

    private DataAccessException translate(String task, String statement, SQLException refusal) {
        DataAccessException translated = jdbcTemplate.getExceptionTranslator()
                .translate(task, statement, refusal);
        if (translated != null) {
            return translated;
        }
        return new UncategorizedSQLException(task, statement, refusal);
    }

    private static void releaseQuietly(ResultSet rows, PreparedStatement statement,
                                       Connection connection) {
        if (rows != null) {
            try {
                rows.close();
            } catch (SQLException ignored) {
                LOG.warn("Could not close the result set of a refused pass - "
                        + BackendDiagnostic.of(ignored).describe()
                        + "; the refusal that caused the release is the one being reported");
            }
        }
        if (statement != null) {
            try {
                statement.close();
            } catch (SQLException ignored) {
                LOG.warn("Could not close the statement of a refused pass - "
                        + BackendDiagnostic.of(ignored).describe()
                        + "; the refusal that caused the release is the one being reported");
            }
        }
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException ignored) {
                LOG.warn("Could not close the connection of a refused pass - "
                        + BackendDiagnostic.of(ignored).describe()
                        + "; the refusal that caused the release is the one being reported");
            }
        }
    }

    private String keyOfImage(byte[] image) {
        return FixedWidthRecord.decodeStrictly(image, KEY_OFFSET, KEY_LENGTH, codec.charset(),
                "the TRAN-ID key of a stored record image");
    }

    private PreparedStatementCreator insertOf(String statement, byte[] image) {
        return connection -> {
            PreparedStatement prepared = connection.prepareStatement(statement);
            recordImageForm.bindImage(prepared, 1, image, codec.charset());
            return prepared;
        };
    }

    private int insert(String statement, byte[] image) {
        return jdbcTemplate.update(insertOf(statement, image));
    }

    private static String reportRefusal(String operation, String ddName, String attempt,
                                       Throwable refusal) {
        BackendDiagnostic diagnostic = BackendDiagnostic.of(refusal);
        LOG.error("Could not " + operation + " the " + ddName + " dataset " + attempt + " - "
                + diagnostic.describe() + "; reporting file status "
                + FileStatus.toStatusImage(PERMANENT_ERROR_STATUS) + " to the caller");
        return PERMANENT_ERROR_STATUS;
    }

    private Statements resolveStatements() {
        Statements resolved = this.statements;
        if (resolved == null) {
            String column = masterRelation.rememberRecordImageColumn(
                    jdbcTemplate.query(masterRelation.describeStatement(),
                            TransactionRepository::extractRecordImageColumn));
            resolved = new Statements(
                    masterRelation.selectByKey(column),
                    masterRelation.selectByKeyForUpdate(column),
                    masterRelation.insertRecordImage(column),
                    masterRelation.selectAllAscending(column),
                    masterRelation.selectFromKeyAscending(column),
                    masterRelation.selectAfterAscending(column),
                    selectAllDescending(column),
                    selectAtOrBeforeKeyDescending(column),
                    masterRelation.selectBeforeDescending(column),
                    masterRelation.selectUnreadableRows(column));
            this.statements = resolved;
        }
        return resolved;
    }

    Statements resolvedStatements() {
        return statements;
    }

    private static String extractRecordImageColumn(ResultSet resultSet) throws SQLException {
        return DatasetRelation.recordImageColumnOf(resultSet.getMetaData());
    }

    private String selectAllDescending(String recordImageColumnName) {
        return "SELECT * FROM " + masterRelation.identifier() + " ORDER BY "
                + DatasetRelation.delimit(recordImageColumnName) + " DESC";
    }

    private String selectAtOrBeforeKeyDescending(String recordImageColumnName) {
        String column = DatasetRelation.delimit(recordImageColumnName);
        return "SELECT * FROM " + masterRelation.identifier()
                + " WHERE (" + column + " < ? OR " + column + " LIKE ? ESCAPE '" + LIKE_ESCAPE + "')"
                + " ORDER BY " + column + " DESC";
    }

    /**
     * The statements this repository sends against the transaction master, composed once against the
     * discovered record-image column.
     *
     * @param selectByKey the keyed {@code READ} - {@code app/cbl/COTRN01C.cbl:269}
     * @param selectByKeyForUpdate the keyed {@code READ ... UPDATE} - the {@code UPDATE} option at
     *     {@code app/cbl/COTRN01C.cbl:275}
     * @param insertRecordImage the keyed {@code WRITE} - {@code app/cbl/COTRN02C.cbl:712}
     * @param browseForwardAll the first read of an unanchored forward browse: the
     *     {@code MOVE LOW-VALUES TO TRAN-ID} case, taking no parameter
     * @param browseForwardAnchor the first read of a forward browse anchored on a key, taking the key image
     * @param browseForwardAfter every forward read after the first: the lowest image strictly above the
     *     record already returned, taking that record's stored image
     * @param browseBackwardAll the first read of an unanchored backward browse: the
     *     {@code MOVE HIGH-VALUES TO TRAN-ID} case, taking no parameter
     * @param browseBackwardAnchor the first read of a backward browse anchored on a key, taking the key
     *     image and then the escaped pattern over the key span
     * @param browseBackwardBefore every backward read after the first: the highest image strictly below the
     *     record already returned, taking that record's stored image
     * @param probeUnreadableRows the diagnostic statement that looks for rows carrying no record image, so
     *     an absent key is told apart from an undecodable row
     */
    record Statements(String selectByKey,
                      String selectByKeyForUpdate,
                      String insertRecordImage,
                      String browseForwardAll,
                      String browseForwardAnchor,
                      String browseForwardAfter,
                      String browseBackwardAll,
                      String browseBackwardAnchor,
                      String browseBackwardBefore,
                      String probeUnreadableRows) {
    }

    /**
     * The statements one sequential read of one dataset uses.
     *
     * @param firstRead the first read of the pass
     * @param advanceAfter the statement that advances one record past a stored image, present only for an
     *     indexed dataset
     */
    record InputStatements(String firstRead, Optional<String> advanceAfter) {
        boolean advancesByKey() {
            return advanceAfter.isPresent();
        }
    }

    /**
     * What one statement brought back: how many rows, and the first row's record image.
     *
     * @param rowCount how many rows arrived, capped at the row limit that was requested
     * @param firstImage the first row's record image, or {@code null} when no row arrived or the column
     *     held nothing
     * @param firstImageMissing whether a row arrived whose record-image column held nothing
     */
    record FetchedRows(int rowCount, byte[] firstImage, boolean firstImageMissing) {
        private static final FetchedRows NONE = new FetchedRows(0, null, false);

        static FetchedRows empty() {
            return NONE;
        }
    }

    /**
     * One read's outcome together with the exact bytes it came from.
     *
     * @param result the outcome the caller branches on
     * @param exactImage the bytes the row's record-image column held, or {@code null} when no record was
     *     returned
     * @param rowSeen whether a row arrived at all
     */
    record Step(ReadResult result, byte[] exactImage, boolean rowSeen) {
    }

    /**
     * One live forward-only pass over a physical-sequential dataset: the JDBC form of an open
     * {@code ORGANIZATION SEQUENTIAL} file positioned between two records.
     *
     * <p>Not thread-safe, exactly as a COBOL file position is not, and confined to the {@link InputFile}
     * that owns it.
     */
    private static final class SequentialPass {
        private final Connection connection;

        private final PreparedStatement statement;

        private final ResultSet rows;

        private final String ddName;

        private SequentialPass(Connection connection, PreparedStatement statement, ResultSet rows,
                               String ddName) {
            this.connection = connection;
            this.statement = statement;
            this.rows = rows;
            this.ddName = ddName;
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
                LOG.error("Could not close the result set of a pass over the " + ddName + " dataset - "
                        + BackendDiagnostic.of(refusal).describe()
                        + "; the close will report a failure");
            }
            try {
                statement.close();
            } catch (SQLException refusal) {
                clean = false;
                LOG.error("Could not close the statement of a pass over the " + ddName + " dataset - "
                        + BackendDiagnostic.of(refusal).describe()
                        + "; the close will report a failure");
            }
            try {
                connection.close();
            } catch (SQLException refusal) {
                clean = false;
                LOG.error("Could not close the connection of a pass over the " + ddName + " dataset - "
                        + BackendDiagnostic.of(refusal).describe()
                        + "; the close will report a failure");
            }
            return clean;
        }
    }

    public enum BrowseDirection {
        /**
         * Ascending key order, walked with {@link Browse#readNext()} -
         * {@code app/cbl/COTRN00C.cbl:624-653}, driven from {@code PROCESS-PAGE-FORWARD} at
         * {@code :279-328}.
         */
        FORWARD,

        BACKWARD
    }

    /**
     * The outcome of one read: what happened, in the vocabulary every consumer already speaks, plus the
     * record when there is one.
     *
     * @param ddName the configuration key of the dataset that was read -
     *     {@value TransactionRepository#CICS_FILE_NAME}
     * @param status the two-character batch {@code FILE STATUS}: {@link FileStatus#OK},
     *     {@link FileStatus#END_OF_FILE}, {@link FileStatus#NOT_FOUND}
     * @param outcome the classification of {@code status}, from {@link FileStatus#outcomeOfStatus(String)},
     *     so the status and its meaning cannot drift apart
     * @param record the record, present exactly when one was read - so on {@link Outcome#OK} and on
     *     {@link Outcome#DUPLICATE}, which carries the first of the matching records, and absent on the other
     *     three
     * @param response the CICS {@code RESP} and {@code RESP2} pair, which is what the online programs
     *     render side by side into their {@code WHEN OTHER} messages ({@code app/cbl/COTRN00C.cbl:645}
     * @param observation what the read measured, where a measurement is what explains the outcome - a row's
     *     actual width, for instance
     * @param diagnostic what the backend reported when it refused: present only on the
     *     {@link Outcome#OTHER} arm of a refusal the driver described
     */
    public record ReadResult(String ddName,
                             String status,
                             Outcome outcome,
                             Optional<TranRecord> record,
                             CicsResponse response,
                             Optional<DatasetObservation> observation,
                             Optional<BackendDiagnostic> diagnostic) {
        public ReadResult {
            Objects.requireNonNull(ddName, "A read outcome names the dataset it came from");
            Objects.requireNonNull(status, "A read outcome carries a two-character FILE STATUS");
            Objects.requireNonNull(outcome, "A read outcome carries its classification");
            Objects.requireNonNull(record, "An absent record is an empty Optional, never null");
            Objects.requireNonNull(response, "A read outcome carries the CICS RESP/RESP2 pair");
            Objects.requireNonNull(observation, "An absent observation is an empty Optional");
            Objects.requireNonNull(diagnostic, "An absent diagnostic is an empty Optional");
            if (status.length() != FileStatus.STATUS_LENGTH) {
                throw new IllegalArgumentException("A FILE STATUS is " + FileStatus.STATUS_LENGTH
                        + " characters; '" + status + "' is " + status.length());
            }
            if (outcome != FileStatus.outcomeOfStatus(status)) {
                throw new IllegalArgumentException("Outcome " + outcome + " does not classify status "
                        + FileStatus.toStatusImage(status) + ", which is "
                        + FileStatus.outcomeOfStatus(status) + ". The two are derived from one value so "
                        + "a caller may switch on either and reach the same arm.");
            }
            boolean returnsRecord = outcome == Outcome.OK || outcome == Outcome.DUPLICATE;
            if (record.isPresent() != returnsRecord) {
                throw new IllegalArgumentException("Outcome " + outcome
                        + (returnsRecord ? " returns a record, and none was supplied"
                                         : " returns no record, and one was supplied")
                        + ". A record is present exactly on the normal and duplicate arms.");
            }
        }

        public static ReadResult found(String ddName, TranRecord record) {
            return new ReadResult(ddName, FileStatus.OK, Outcome.OK,
                    Optional.of(Objects.requireNonNull(record, "The normal arm carries a record")),
                    CicsResponse.of(FileStatus.NORMAL), Optional.empty(), Optional.empty());
        }

        /**
         * The end-of-file arm: {@code AT END} on a sequential read, {@code DFHRESP(ENDFILE)} on a browse.
         *
         * @param ddName the dataset that was read
         * @return the outcome, status {@code '10'}, CICS {@link FileStatus#ENDFILE}
         */
        public static ReadResult endOfFile(String ddName) {
            return new ReadResult(ddName, FileStatus.END_OF_FILE, Outcome.END_OF_FILE,
                    Optional.empty(), CicsResponse.of(FileStatus.ENDFILE), Optional.empty(),
                    Optional.empty());
        }

        /**
         * The not-found arm: {@code INVALID KEY} on a keyed read, {@code DFHRESP(NOTFND)} online.
         *
         * @param ddName the dataset that was read
         * @return the outcome, status {@code '23'}, CICS {@link FileStatus#NOTFND}
         */
        public static ReadResult notFound(String ddName) {
            return new ReadResult(ddName, FileStatus.NOT_FOUND, Outcome.NOT_FOUND,
                    Optional.empty(), CicsResponse.of(FileStatus.NOTFND), Optional.empty(),
                    Optional.empty());
        }

        /**
         * The duplicate arm: more than one record matched a key that a KSDS makes unique.
         *
         * @param ddName the dataset that was read
         * @param record the first matching record
         * @return the outcome, status {@code '22'}, CICS {@link FileStatus#DUPREC}
         */
        public static ReadResult duplicate(String ddName, TranRecord record) {
            return new ReadResult(ddName, FileStatus.DUPLICATE, Outcome.DUPLICATE,
                    Optional.of(Objects.requireNonNull(record, "The duplicate arm carries the first "
                            + "matching record")),
                    CicsResponse.of(FileStatus.DUPREC), Optional.empty(), Optional.empty());
        }

        public static ReadResult other(String ddName, String status) {
            return new ReadResult(ddName, status, FileStatus.outcomeOfStatus(status), Optional.empty(),
                    CicsResponse.ofBatchStatus(status), Optional.empty(), Optional.empty());
        }

        /**
         * The {@code WHEN OTHER} arm, carrying a status and the driver's own diagnosis.
         *
         * @param ddName the dataset that was read
         * @param status the status to report
         * @param diagnostic what the backend reported
         * @return the outcome
         */
        public static ReadResult other(String ddName, String status, BackendDiagnostic diagnostic) {
            return new ReadResult(ddName, status, FileStatus.outcomeOfStatus(status), Optional.empty(),
                    CicsResponse.ofBatchStatus(status), Optional.empty(),
                    Optional.of(Objects.requireNonNull(diagnostic, "A diagnostic arm carries one")));
        }

        /**
         * The {@code WHEN OTHER} arm, carrying a status and a named CICS condition.
         *
         * @param ddName the dataset that was read
         * @param status the status to report
         * @param response the CICS condition the failure corresponds to
         * @return the outcome
         */
        public static ReadResult other(String ddName, String status, CicsResponse response) {
            return new ReadResult(ddName, status, FileStatus.outcomeOfStatus(status), Optional.empty(),
                    Objects.requireNonNull(response, "A named-condition arm carries a response"),
                    Optional.empty(), Optional.empty());
        }

        /**
         * The {@code WHEN OTHER} arm, carrying a status, a named CICS condition and a measurement.
         *
         * @param ddName the dataset that was read
         * @param status the status to report
         * @param response the CICS condition the failure corresponds to
         * @param observation what was measured
         * @return the outcome
         */
        public static ReadResult other(String ddName, String status, CicsResponse response,
                                       DatasetObservation observation) {
            return new ReadResult(ddName, status, FileStatus.outcomeOfStatus(status), Optional.empty(),
                    Objects.requireNonNull(response, "A named-condition arm carries a response"),
                    Optional.of(Objects.requireNonNull(observation, "A measured arm carries one")),
                    Optional.empty());
        }

        public boolean isFound() {
            return outcome == Outcome.OK;
        }

        /**
         * Whether a record is available at all - the normal arm or the duplicate arm.
         *
         * @return {@code true} when {@link #record()} is present
         */
        public boolean isRecordReturned() {
            return record.isPresent();
        }

        /**
         * Whether the pass has run past its last record.
         *
         * @return {@code true} for {@link Outcome#END_OF_FILE}
         */
        public boolean isEndOfFile() {
            return outcome == Outcome.END_OF_FILE;
        }

        public boolean isNotFound() {
            return outcome == Outcome.NOT_FOUND;
        }

        /**
         * Whether more than one record matched a unique key.
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
         * The CICS {@code RESP} value, where the outcome has one.
         *
         * @return the response, or empty where no single CICS condition corresponds
         */
        public OptionalInt cicsResp() {
            return response.resp();
        }

        /**
         * The CICS {@code RESP2} reason code.
         *
         * @return the reason code, {@link FileStatus#NO_REASON_CODE} where the condition carries none
         */
        public int cicsResp2() {
            return response.resp2();
        }

        /**
         * The {@code APPL-RESULT} the batch consumers move on this outcome.
         *
         * @return {@link FileStatus#APPL_AOK}, {@link FileStatus#APPL_EOF} or
         *     {@link TransactionRepository#APPL_RESULT_FATAL}
         */
        public int applResult() {
            if (outcome == Outcome.OK) {
                return FileStatus.APPL_AOK;
            }
            if (outcome == Outcome.END_OF_FILE) {
                return FileStatus.APPL_EOF;
            }
            return APPL_RESULT_FATAL;
        }

        /**
         * The status rendered as {@code 9910-DISPLAY-IO-STATUS} renders it.
         *
         * @return the four-character status image
         */
        public String statusImage() {
            return FileStatus.toStatusImage(status);
        }

        /**
         * The CICS response pair rendered as the online programs render it -
         * {@code DISPLAY 'RESP:' WS-RESP-CD 'REAS:' WS-REAS-CD} ({@code app/cbl/COTRN00C.cbl:645}).
         *
         * @return for example {@code "Resp:13 Reas:0"}
         */
        public String describeResponse() {
            return response.describe();
        }

        /**
         * The record, or a failure that says which arm was reached instead.
         *
         * @return the record
         * @throws IllegalStateException if this outcome carries none
         */
        public TranRecord requireRecord() {
            return record.orElseThrow(() -> new IllegalStateException("This read of the " + ddName
                    + " dataset reached the " + outcome + " arm, status " + statusImage()
                    + ", which carries no record. Branch on outcome() before asking for one."));
        }
    }

    /**
     * The outcome of one write: what happened, and nothing about a record, because a write returns none.
     *
     * @param ddName the configuration key of the dataset that was written
     * @param status the two-character batch {@code FILE STATUS}: {@link FileStatus#OK},
     *     {@link FileStatus#DUPLICATE} or {@link TransactionRepository#PERMANENT_ERROR_STATUS}
     * @param outcome the classification of {@code status}, agreeing with it by construction
     * @param response the CICS {@code RESP} and {@code RESP2} pair
     * @param observation what the write measured - an affected-row count, or a record width - where a
     *     measurement explains the outcome
     * @param diagnostic what the backend reported when it refused
     */
    public record WriteResult(String ddName,
                              String status,
                              Outcome outcome,
                              CicsResponse response,
                              Optional<DatasetObservation> observation,
                              Optional<BackendDiagnostic> diagnostic) {
        public WriteResult {
            Objects.requireNonNull(ddName, "A write outcome names the dataset it was written to");
            Objects.requireNonNull(status, "A write outcome carries a two-character FILE STATUS");
            Objects.requireNonNull(outcome, "A write outcome carries its classification");
            Objects.requireNonNull(response, "A write outcome carries the CICS RESP/RESP2 pair");
            Objects.requireNonNull(observation, "An absent observation is an empty Optional");
            Objects.requireNonNull(diagnostic, "An absent diagnostic is an empty Optional");
            if (status.length() != FileStatus.STATUS_LENGTH) {
                throw new IllegalArgumentException("A FILE STATUS is " + FileStatus.STATUS_LENGTH
                        + " characters; '" + status + "' is " + status.length());
            }
            if (outcome != FileStatus.outcomeOfStatus(status)) {
                throw new IllegalArgumentException("Outcome " + outcome + " does not classify status "
                        + FileStatus.toStatusImage(status) + ", which is "
                        + FileStatus.outcomeOfStatus(status) + ".");
            }
        }

        public static WriteResult written(String ddName) {
            return new WriteResult(ddName, FileStatus.OK, Outcome.OK,
                    CicsResponse.of(FileStatus.NORMAL), Optional.empty(), Optional.empty());
        }

        /**
         * The duplicate arm: the key already exists and nothing was written.
         *
         * @param ddName the dataset that refused the add
         * @return the outcome, status {@code '22'}, CICS {@link FileStatus#DUPREC}
         */
        public static WriteResult duplicate(String ddName) {
            return new WriteResult(ddName, FileStatus.DUPLICATE, Outcome.DUPLICATE,
                    CicsResponse.of(FileStatus.DUPREC), Optional.empty(), Optional.empty());
        }

        /**
         * The duplicate arm, carrying the driver's own diagnosis - for the case where the relation itself
         * enforced the key's uniqueness and refused.
         *
         * @param ddName the dataset that refused the add
         * @param diagnostic what the backend reported
         * @return the outcome, status {@code '22'}, CICS {@link FileStatus#DUPREC}
         */
        public static WriteResult duplicate(String ddName, BackendDiagnostic diagnostic) {
            return new WriteResult(ddName, FileStatus.DUPLICATE, Outcome.DUPLICATE,
                    CicsResponse.of(FileStatus.DUPREC), Optional.empty(),
                    Optional.of(Objects.requireNonNull(diagnostic, "A diagnostic arm carries one")));
        }

        /**
         * The {@code WHEN OTHER} arm, carrying a status and a measurement.
         *
         * @param ddName the dataset that was written
         * @param status the status to report
         * @param response the CICS condition the failure corresponds to
         * @param observation what was measured
         * @return the outcome
         */
        public static WriteResult other(String ddName, String status, CicsResponse response,
                                        DatasetObservation observation) {
            return new WriteResult(ddName, status, FileStatus.outcomeOfStatus(status),
                    Objects.requireNonNull(response, "A named-condition arm carries a response"),
                    Optional.of(Objects.requireNonNull(observation, "A measured arm carries one")),
                    Optional.empty());
        }

        /**
         * The {@code WHEN OTHER} arm, carrying a status, a named CICS condition and the driver's own
         * diagnosis.
         *
         * @param ddName the dataset that was written
         * @param status the status to report
         * @param response the CICS condition the failure corresponds to
         * @param diagnostic what the backend reported
         * @return the outcome
         */
        public static WriteResult other(String ddName, String status, CicsResponse response,
                                        BackendDiagnostic diagnostic) {
            return new WriteResult(ddName, status, FileStatus.outcomeOfStatus(status),
                    Objects.requireNonNull(response, "A named-condition arm carries a response"),
                    Optional.empty(),
                    Optional.of(Objects.requireNonNull(diagnostic, "A diagnostic arm carries one")));
        }

        public static WriteResult other(String ddName, String status) {
            return new WriteResult(ddName, status, FileStatus.outcomeOfStatus(status),
                    CicsResponse.ofBatchStatus(status), Optional.empty(), Optional.empty());
        }

        public boolean isWritten() {
            return outcome == Outcome.OK;
        }

        /**
         * Whether the key already existed, so nothing was written.
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
         * The CICS {@code RESP} value, where the outcome has one.
         *
         * @return the response, or empty where no single CICS condition corresponds
         */
        public OptionalInt cicsResp() {
            return response.resp();
        }

        /**
         * The CICS {@code RESP2} reason code.
         *
         * @return the reason code, {@link FileStatus#NO_REASON_CODE} where the condition carries none
         */
        public int cicsResp2() {
            return response.resp2();
        }

        /**
         * The {@code APPL-RESULT} the batch consumers move on this outcome.
         *
         * @return {@link FileStatus#APPL_AOK} or {@link TransactionRepository#APPL_RESULT_FATAL}
         */
        public int applResult() {
            return outcome == Outcome.OK ? FileStatus.APPL_AOK : APPL_RESULT_FATAL;
        }

        /**
         * The status rendered as {@code 9910-DISPLAY-IO-STATUS} renders it.
         *
         * @return the four-character status image
         */
        public String statusImage() {
            return FileStatus.toStatusImage(status);
        }

        /**
         * The CICS response pair rendered as the online programs render it.
         *
         * @return for example {@code "Resp:14 Reas:0"}
         */
        public String describeResponse() {
            return response.describe();
        }
    }

    // None is thread-safe, exactly as a COBOL record area is not, and each is meant to be confined to the
    // step, request or test that opened it.

    /**
     * One opened sequential input pass over the {@value TransactionRepository#INPUT_DD_NAME} dataset.
     */
    public static final class InputFile implements AutoCloseable {
        private final TransactionRepository repository;

        private final DatasetRelation relation;

        private final String ddName;

        private final String openStatus;

        private final boolean opened;

        private final InputStatements statements;

        private byte[] position;

        private SequentialPass pass;

        private int returned;

        private boolean exhausted;

        private boolean closed;

        private String closeStatus;

        private InputFile(TransactionRepository repository, DatasetRelation relation, String ddName,
                          String openStatus, InputStatements statements) {
            this.repository = repository;
            this.relation = relation;
            this.ddName = ddName;
            this.openStatus = openStatus;
            this.opened = FileStatus.isOk(openStatus) && statements != null;
            this.statements = statements;
        }

        /**
         * The status {@code OPEN INPUT} reported: the value {@code app/cbl/CBTRN03C.cbl:379} tests before
         * moving {@code 0} or {@code 12} into {@code APPL-RESULT}, and {@code app/cbl/CBTRN01C.cbl:346}
         * likewise.
         *
         * @return {@link FileStatus#OK} or {@link TransactionRepository#PERMANENT_ERROR_STATUS}; never
         *     {@code null}, always two characters
         */
        public String openStatus() {
            return openStatus;
        }

        public Outcome openOutcome() {
            return FileStatus.outcomeOfStatus(openStatus);
        }

        /**
         * The {@code APPL-RESULT} the open sets: {@code 0} on success and {@code 12} otherwise - the ladder
         * at {@code app/cbl/CBTRN03C.cbl:377-383}, which seeds {@code 8}, moves {@code 0} on {@code '00'}
         * and {@code 12} on anything else.
         *
         * @return {@link FileStatus#APPL_AOK} or {@link TransactionRepository#APPL_RESULT_FATAL}
         */
        public int openApplResult() {
            return opened ? FileStatus.APPL_AOK : APPL_RESULT_FATAL;
        }

        /**
         * Whether this pass is still usable: opened successfully and not yet closed.
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
         * The dataset this pass is reading, exactly as configuration declares it.
         *
         * @return the dataset name, or an empty {@link Optional} when the open could address none
         */
        public Optional<String> datasetName() {
            return Optional.ofNullable(relation).map(DatasetRelation::dsname);
        }

        /**
         * The configuration key of the dataset this pass is reading.
         *
         * @return {@value TransactionRepository#INPUT_DD_NAME}
         */
        public String ddName() {
            return ddName;
        }

        /**
         * Reads the next record: the Java form of {@code 1000-TRANFILE-GET-NEXT}
         * ({@code app/cbl/CBTRN03C.cbl:248-272}).
         *
         * @return the discriminated outcome; never {@code null}
         * @throws IllegalStateException if this pass has been closed
         */
        public ReadResult readNext() {
            if (closed) {
                throw new IllegalStateException("This pass over the " + ddName + " dataset has been "
                        + "closed, so it has no next record. app/cbl/CBTRN03C.cbl closes once, at :516, "
                        + "after its loop has ended - reading afterwards is a defect in the caller and "
                        + "not a file status. Open another pass instead.");
            }
            if (!opened) {
                return ReadResult.other(ddName, openStatus);
            }
            if (exhausted) {
                return ReadResult.endOfFile(ddName);
            }
            Step step = statements.advancesByKey() ? readNextByKey() : readNextInWrittenOrder();
            ReadResult result = step.result();
            if (result.isRecordReturned()) {
                position = step.exactImage();
                returned++;
            } else if (result.isEndOfFile() || step.rowSeen()) {
                exhausted = true;
            }
            return result;
        }

        private Step readNextByKey() {
            if (position == null) {
                return repository.unparameterisedStep(statements.firstRead(), ddName);
            }
            return repository.advanceStep(statements.advanceAfter().orElseThrow(), position, ddName);
        }

        private Step readNextInWrittenOrder() {
            if (pass == null) {
                try {
                    pass = repository.openPass(statements.firstRead(), ddName);
                } catch (DataAccessException refused) {
                    return failedStep(ddName, "front to back", refused);
                }
            }
            boolean rowReached;
            try {
                rowReached = pass.next();
            } catch (SQLException refusal) {
                return failedStep(ddName, "front to back",
                        repository.translate("advance a forward-only pass", statements.firstRead(),
                                refusal));
            } catch (DataAccessException refused) {
                return failedStep(ddName, "front to back", refused);
            }
            if (!rowReached) {
                return new Step(ReadResult.endOfFile(ddName), null, false);
            }
            byte[] image;
            try {
                image = repository.recordImageForm.readImage(pass.rows(), RECORD_IMAGE_COLUMN_INDEX,
                        repository.codec.charset());
            } catch (SQLException refusal) {
                return failedStep(ddName, "front to back",
                        repository.translate("read a record image from a forward-only pass",
                                statements.firstRead(), refusal), true);
            } catch (DataAccessException refused) {
                return failedStep(ddName, "front to back", refused, true);
            }
            return repository.classifyRow(image, ddName);
        }

        /**
         * Closes this pass: the Java form of {@code 9000-TRANFILE-CLOSE}
         * ({@code app/cbl/CBTRN03C.cbl:514-530}) and of {@code 9500-TRANFILE-CLOSE}
         * ({@code app/cbl/CBTRN01C.cbl:451-467}).
         *
         * @return {@link FileStatus#OK} for a pass that was open, or
         *     {@link TransactionRepository#PERMANENT_ERROR_STATUS} for one that never opened; never
         *     {@code null}, always two characters
         */
        public String closeInput() {
            if (closed) {
                return closeStatus;
            }
            closed = true;
            SequentialPass released = pass;
            pass = null;
            position = null;
            if (!opened) {
                LOG.error("A pass over the " + ddName + " dataset was closed although it never opened - "
                        + "its open reported file status " + FileStatus.toStatusImage(openStatus)
                        + "; reporting file status " + FileStatus.toStatusImage(PERMANENT_ERROR_STATUS)
                        + " from the close as well, because a CLOSE of a file that is not open is not a "
                        + "success");
                closeStatus = PERMANENT_ERROR_STATUS;
                return closeStatus;
            }
            closeStatus = released == null || released.release()
                    ? FileStatus.OK
                    : PERMANENT_ERROR_STATUS;
            return closeStatus;
        }

        /**
         * The {@code APPL-RESULT} the close sets: {@code 0} on success and {@code 12} otherwise - the
         * ladder at {@code app/cbl/CBTRN03C.cbl:515-521}.
         *
         * @return {@link FileStatus#APPL_AOK} or {@link TransactionRepository#APPL_RESULT_FATAL}
         */
        public int closeApplResult() {
            // Derived from what the close actually reported, so the status and the APPL-RESULT cannot
            // disagree - including when a cursor release was refused, which is a failed CLOSE over a
            // dataset that opened perfectly well.
            if (closed) {
                return FileStatus.isOk(closeStatus) ? FileStatus.APPL_AOK : APPL_RESULT_FATAL;
            }
            return opened ? FileStatus.APPL_AOK : APPL_RESULT_FATAL;
        }

        /**
         * {@link AutoCloseable} form of {@link #closeInput()}, so a pass can be used with
         * try-with-resources.
         */
        @Override
        public void close() {
            closeInput();
        }
    }

    /**
     * One execution's load-mode open of the indexed master - {@code OPEN OUTPUT TRANSACT-FILE} at
     * {@code app/cbl/CBTRN02C.cbl:256}.
     */
    public static final class LoadModeFile implements AutoCloseable {
        private final DatasetRelation relation;

        private final String ddName;

        private final String openStatus;

        private boolean closed;

        private LoadModeFile(DatasetRelation relation, String ddName, String openStatus) {
            this.relation = relation;
            this.ddName = ddName;
            this.openStatus = openStatus;
        }

        public String datasetName() {
            return relation.dsname();
        }

        /**
         * The DD name this open was resolved for.
         *
         * @return the DD name; never {@code null}
         */
        public String ddName() {
            return ddName;
        }

        /**
         * The status {@code OPEN OUTPUT} reported - the value {@code app/cbl/CBTRN02C.cbl:257} tests and
         * {@code :262-268} abends on as {@code 'ERROR OPENING TRANSACTION FILE'}.
         *
         * @return {@link FileStatus#OK} when load mode could begin, {@link FileStatus#OPEN_MODE_CONFLICT}
         *     when the cluster holds records and is not reusable
         */
        public String openStatus() {
            return openStatus;
        }

        public Outcome openOutcome() {
            return FileStatus.outcomeOfStatus(openStatus);
        }

        /**
         * The {@code APPL-RESULT} the open sets - the ladder at {@code app/cbl/CBTRN02C.cbl:257-261}:
         * {@code MOVE 0} on {@code '00'} and {@code MOVE 12} otherwise.
         *
         * @return {@link FileStatus#APPL_AOK} or {@link TransactionRepository#APPL_RESULT_FATAL}
         */
        public int openApplResult() {
            return FileStatus.OK.equals(openStatus) ? FileStatus.APPL_AOK : APPL_RESULT_FATAL;
        }

        public boolean isOpen() {
            return !closed;
        }

        /**
         * {@code CLOSE TRANSACT-FILE} - {@code app/cbl/CBTRN02C.cbl:602}.
         *
         * @return the close status; never {@code null}
         */
        public String closeLoadMode() {
            closed = true;
            return FileStatus.isOk(openStatus) ? FileStatus.OK : openStatus;
        }

        /**
         * Releases the handle, so try-with-resources reads the same as the source's close paragraph.
         */
        @Override
        public void close() {
            closed = true;
        }
    }
    /**
     * One opened sequential output run over the generation a step allocated.
     */
    public static final class OutputFile implements AutoCloseable {
        private final TransactionRepository repository;

        private final DatasetRelation relation;

        private final String ddName;

        private final String insertStatement;

        private final String openStatus;

        private int recordsWritten;

        private boolean closed;

        private boolean discarded;

        private String closeStatus;

        private OutputFile(TransactionRepository repository, DatasetRelation relation, String ddName,
                           String openStatus) {
            this.repository = repository;
            this.relation = relation;
            this.ddName = ddName;
            this.insertStatement = relation.insertRecordImage();
            this.openStatus = openStatus;
        }

        /**
         * The dataset this run writes to, exactly as configuration named it.
         *
         * @return the resolved dataset name; never {@code null}
         */
        public String datasetName() {
            return relation.dsname();
        }

        /**
         * The DD name this run was opened for.
         *
         * @return the DD name; never {@code null}
         */
        public String ddName() {
            return ddName;
        }

        /**
         * The status {@code OPEN OUTPUT} reported - the value {@code app/cbl/CBACT04C.cbl:310} tests and
         * {@code :318-321} abends on as {@code 'ERROR OPENING TRANSACTION FILE'}.
         *
         * @return the file status; never {@code null}, always two characters
         */
        public String openStatus() {
            return openStatus;
        }

        public Outcome openOutcome() {
            return FileStatus.outcomeOfStatus(openStatus);
        }

        /**
         * The {@code APPL-RESULT} the open sets - the ladder at {@code app/cbl/CBACT04C.cbl:310-314}:
         * {@code MOVE 0} on {@code '00'} and {@code MOVE 12} otherwise.
         *
         * @return {@link FileStatus#APPL_AOK} or {@link TransactionRepository#APPL_RESULT_FATAL}
         */
        public int openApplResult() {
            return FileStatus.OK.equals(openStatus) ? FileStatus.APPL_AOK : APPL_RESULT_FATAL;
        }

        public boolean isOpen() {
            return !closed;
        }

        public int recordsWritten() {
            return recordsWritten;
        }

        /**
         * The statement this run issues for each record.
         *
         * @return the parameterised insert
         */
        public String insertStatement() {
            return insertStatement;
        }

        /**
         * Writes one record: the Java form of {@code WRITE FD-TRANFILE-REC FROM TRAN-RECORD}
         * ({@code app/cbl/CBACT04C.cbl:500}).
         *
         * <p>A physical-sequential dataset has no key, so there is nothing for a record to duplicate, and
         * {@code app/cbl/CBACT04C.cbl:501-505} tests exactly two arms: {@code '00'} to
         * {@code APPL-RESULT 0} and anything else to {@code 12}.
         *
         * @param record the record to write; never {@code null}
         * @return the discriminated outcome; never {@code null}
         * @throws NullPointerException if {@code record} is {@code null}
         * @throws IllegalStateException if this run has been closed
         */
        public WriteResult writeSequential(TranRecord record) {
            Objects.requireNonNull(record, "A transaction record is required to write one");
            if (closed) {
                throw new IllegalStateException("This run over the " + ddName
                        + " dataset has been closed, so it can write nothing more. "
                        + "app/cbl/CBACT04C.cbl closes once, at :597, after its last write - writing "
                        + "afterwards is a defect in the caller and not a file status. Open another run "
                        + "instead.");
            }
            byte[] image = record.encode(repository.codec.charset());
            if (image.length != RECORD_LENGTH) {
                LOG.error("A transaction record encoded to " + image.length + " bytes where "
                        + ddName + " holds " + RECORD_LENGTH
                        + " (app/jcl/INTCALC.jcl:39 declares LRECL=" + RECORD_LENGTH
                        + "); refusing the write rather than emitting a record of the wrong width");
                return WriteResult.other(ddName, PERMANENT_ERROR_STATUS,
                        CicsResponse.of(FileStatus.LENGERR),
                        DatasetObservation.recordWidth(image.length));
            }
            int added;
            try {
                added = repository.insert(insertStatement, image);
            } catch (DataAccessException rejected) {
                return WriteResult.other(ddName,
                        reportRefusal(WRITE_OPERATION_NAME, ddName, "sequentially", rejected),
                        CicsResponse.ofBatchStatus(PERMANENT_ERROR_STATUS),
                        BackendDiagnostic.of(rejected));
            }
            if (added == SINGLE_ROW) {
                recordsWritten++;
                return WriteResult.written(ddName);
            }
            LOG.error("A sequential write to " + ddName + " reported " + added
                    + " affected row(s) where exactly " + SINGLE_ROW + " was expected; reporting file "
                    + "status " + FileStatus.toStatusImage(PERMANENT_ERROR_STATUS)
                    + " rather than reporting a record as written");
            return WriteResult.other(ddName, PERMANENT_ERROR_STATUS,
                    CicsResponse.of(FileStatus.INVREQ), DatasetObservation.matchingRows(added));
        }

        /**
         * Closes this run: the Java form of {@code 9400-TRANFILE-CLOSE}
         * ({@code app/cbl/CBACT04C.cbl:595-611}).
         *
         * @return {@link FileStatus#OK} when the destination is still addressable, or
         *     {@link TransactionRepository#PERMANENT_ERROR_STATUS} when it is not; never {@code null}, always
         *     two characters
         */
        public String closeOutput() {
            if (closed) {
                return closeStatus;
            }
            closed = true;
            if (!FileStatus.OK.equals(openStatus)) {
                LOG.error("This run over " + ddName + " was closed although it never "
                        + "opened - its open reported file status "
                        + FileStatus.toStatusImage(openStatus) + "; reporting file status "
                        + FileStatus.toStatusImage(PERMANENT_ERROR_STATUS) + " from the close as well, "
                        + "because a CLOSE of a file that is not open is not a success");
                closeStatus = PERMANENT_ERROR_STATUS;
                return closeStatus;
            }
            try {
                repository.jdbcTemplate.execute(relation.describeStatement());
                closeStatus = FileStatus.OK;
            } catch (DataAccessException refused) {
                closeStatus = reportRefusal(CLOSE_OPERATION_NAME, ddName,
                        "after writing " + recordsWritten + " record(s)", refused);
            }
            return closeStatus;
        }

        /**
         * The {@code APPL-RESULT} the close sets - the ladder at {@code app/cbl/CBACT04C.cbl:598-602}:
         * {@code MOVE 0} on {@code '00'} and {@code MOVE 12} otherwise.
         *
         * <p>A run that has not been closed yet has produced no close status, and saying {@code 0} for a
         * close that never ran would be the one answer that cannot be right - so it reports the
         * assumed-failure value the COBOL itself holds at {@code :596} until the close succeeds.
         *
         * @return {@link FileStatus#APPL_AOK} or {@link TransactionRepository#APPL_RESULT_FATAL}
         */
        public int closeApplResult() {
            return FileStatus.OK.equals(closeStatus) ? FileStatus.APPL_AOK : APPL_RESULT_FATAL;
        }

        /**
         * Applies the abnormal disposition of {@code app/jcl/INTCALC.jcl:37} -
         * {@code DISP=(NEW,CATLG, DELETE)} - by discarding everything this run wrote.
         *
         * @return {@link FileStatus#OK} when the generation was discarded or was empty,
         *     {@link TransactionRepository#PERMANENT_ERROR_STATUS} when it could not be
         */
        public String discardGeneration() {
            if (discarded || recordsWritten == 0) {
                discarded = true;
                return FileStatus.OK;
            }
            try {
                Integer held = repository.jdbcTemplate.queryForObject(
                        relation.countAllStatement(), Integer.class);
                if (held == null || held != recordsWritten) {
                    LOG.error("Refusing to apply the " + ddName
                            + " abnormal disposition of app/jcl/INTCALC.jcl:37: this run wrote "
                            + recordsWritten + " record(s) but the dataset holds " + held
                            + ". DISP=(NEW,CATLG,DELETE) deletes the generation this step allocated, so a "
                            + "dataset holding records this step did not write is not that generation. "
                            + "Leaving it untouched and reporting file status "
                            + FileStatus.toStatusImage(PERMANENT_ERROR_STATUS)
                            + "; bind " + ddName
                            + " to a relation of its own so each run allocates its own generation");
                    discarded = true;
                    return PERMANENT_ERROR_STATUS;
                }
                int removed = repository.jdbcTemplate.update(relation.deleteAllStatement());
                discarded = true;
                if (removed == recordsWritten) {
                    return FileStatus.OK;
                }
                LOG.error("The " + ddName + " abnormal disposition removed " + removed
                        + " record(s) where this run wrote " + recordsWritten
                        + "; reporting file status " + FileStatus.toStatusImage(PERMANENT_ERROR_STATUS)
                        + " rather than reporting the generation as discarded");
                return PERMANENT_ERROR_STATUS;
            } catch (DataAccessException refused) {
                discarded = true;
                return reportRefusal(DISPOSITION_OPERATION_NAME, ddName,
                        "for its abnormal disposition", refused);
            }
        }

        @Override
        public void close() {
            closeOutput();
        }
    }

    /**
     * One browse of the transaction master, in one direction.
     */
    public static final class Browse implements AutoCloseable {
        private final TransactionRepository repository;

        private final BrowseDirection direction;

        private final String anchorKey;

        private String positionKey;

        private byte[] positionImage;

        private boolean positioned;

        private boolean ended;

        private ReadResult positioningResult;

        private boolean started;

        private Browse(TransactionRepository repository, BrowseDirection direction, String anchorKey) {
            this.repository = repository;
            this.direction = direction;
            this.anchorKey = anchorKey;
        }

        private void position() {
            Statements sql;
            try {
                sql = repository.resolveStatements();
            } catch (DataAccessException refused) {
                positioningResult = failedStep(CICS_FILE_NAME, "while positioning a browse", refused)
                        .result();
                started = false;
                return;
            }
            positioningResult = anchor(sql).result();
            if (!positioningResult.isRecordReturned() && positioningResult.isEndOfFile()) {
                positioningResult = ReadResult.notFound(CICS_FILE_NAME);
            }
            started = positioningResult.isRecordReturned();
        }

        /**
         * The outcome the {@code STARTBR} reported - the value {@code app/cbl/COBIL00C.cbl:451-467},
         * {@code app/cbl/COTRN00C.cbl:602-620} and {@code app/cbl/COTRN02C.cbl:652-668} each evaluate.
         *
         * @return {@link Outcome#OK} when a record satisfies the positioning, {@link Outcome#NOT_FOUND}
         *     when none does, {@link Outcome#OTHER} when the backend refused; never {@code null}
         */
        public Outcome positioningOutcome() {
            return positioningResult.outcome();
        }

        /**
         * The full {@code STARTBR} outcome, including the {@code RESP} and {@code RESP2} a
         * {@code WHEN OTHER} arm displays.
         *
         * @return the positioning result; never {@code null}
         */
        public ReadResult positioningResult() {
            return positioningResult;
        }

        public boolean isStarted() {
            return started;
        }

        public BrowseDirection direction() {
            return direction;
        }

        public Optional<String> anchorKey() {
            return Optional.ofNullable(anchorKey);
        }

        public Optional<String> positionKey() {
            return Optional.ofNullable(positionKey);
        }

        public boolean isEnded() {
            return ended;
        }

        /**
         * Reads the next record in ascending key order: the Java form of {@code READNEXT-TRANSACT-FILE}
         * ({@code app/cbl/COTRN00C.cbl:624-653}).
         *
         * @return the discriminated outcome; never {@code null}
         */
        public ReadResult readNext() {
            return read(BrowseDirection.FORWARD);
        }

        /**
         * Reads the previous record in descending key order: the Java form of
         * {@code READPREV-TRANSACT-FILE} ({@code app/cbl/COTRN00C.cbl:658-687},
         * {@code app/cbl/COTRN02C.cbl:673-697}, {@code app/cbl/COBIL00C.cbl:472-496}).
         *
         * @return the discriminated outcome; never {@code null}
         */
        public ReadResult readPrev() {
            return read(BrowseDirection.BACKWARD);
        }

        private ReadResult read(BrowseDirection required) {
            if (!started) {
                // CICS reports an invalid request, and COBIL00C's unconditional PERFORM
                // READPREV-TRANSACT-FILE at :214 reaches its WHEN OTHER arm through exactly this path.
                LOG.error("A read was requested on a browse of " + CICS_FILE_NAME + " whose STARTBR "
                        + "reported " + positioningResult.outcome() + ", so no browse was established; "
                        + "reporting the invalid-request condition");
                return ReadResult.other(CICS_FILE_NAME, PERMANENT_ERROR_STATUS,
                        CicsResponse.of(FileStatus.INVREQ));
            }
            if (ended) {
                LOG.error("A read was requested on a browse of " + CICS_FILE_NAME + " that has already "
                        + "been ended; reporting the invalid-request condition");
                return ReadResult.other(CICS_FILE_NAME, PERMANENT_ERROR_STATUS,
                        CicsResponse.of(FileStatus.INVREQ));
            }
            if (direction != required) {
                LOG.error("A " + required + " read was requested on a browse of " + CICS_FILE_NAME
                        + " positioned for " + direction + "; reporting the invalid-request condition "
                        + "rather than reversing a browse the legacy code never reverses");
                return ReadResult.other(CICS_FILE_NAME, PERMANENT_ERROR_STATUS,
                        CicsResponse.of(FileStatus.INVREQ));
            }
            Statements sql;
            try {
                sql = repository.resolveStatements();
            } catch (DataAccessException refused) {
                return failedStep(CICS_FILE_NAME, "while positioning a browse", refused).result();
            }
            Step step = positioned ? advance(sql) : anchor(sql);
            ReadResult result = step.result();
            if (result.isRecordReturned()) {
                positionImage = step.exactImage();
                positionKey = repository.keyOfImage(positionImage);
                positioned = true;
            }
            return result;
        }

        private Step anchor(Statements sql) {
            if (direction == BrowseDirection.FORWARD) {
                return anchorKey == null
                        ? repository.unparameterisedStep(sql.browseForwardAll(), CICS_FILE_NAME)
                        : repository.browseAnchorStep(sql.browseForwardAnchor(), anchorKey,
                                CICS_FILE_NAME);
            }
            if (anchorKey == null) {
                return repository.unparameterisedStep(sql.browseBackwardAll(), CICS_FILE_NAME);
            }
            return repository.browseAnchorStep(sql.browseBackwardAnchor(), anchorKey,
                    KEY_SPAN.pattern(anchorKey), CICS_FILE_NAME);
        }

        private Step advance(Statements sql) {
            String statement = direction == BrowseDirection.FORWARD
                    ? sql.browseForwardAfter()
                    : sql.browseBackwardBefore();
            return repository.advanceStep(statement, positionImage, CICS_FILE_NAME);
        }

        /**
         * Ends the browse: the Java form of {@code ENDBR-TRANSACT-FILE}
         * ({@code app/cbl/COTRN00C.cbl:692-695}, {@code app/cbl/COTRN02C.cbl:702-704},
         * {@code app/cbl/COBIL00C.cbl:501-504}).
         */
        public void endBrowse() {
            ended = true;
            positionImage = null;
        }

        /**
         * Ends the browse, so a handle can be used in a try-with-resources block.
         */
        @Override
        public void close() {
            endBrowse();
        }
    }
}
