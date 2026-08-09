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
import com.vsergeychik.carddemo.config.DatasetUnitOfWork;
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
 * <h2>What this class is, and what it deliberately is not</h2>
 * This is the data-access layer for the five COBOL programs that touch the security file, and it is the
 * foundational type of the {@code user} package: every other class in it, and both {@code admin}
 * controllers, reach {@code USRSEC} through here and nowhere else. It exposes <strong>exactly the access
 * paths those five programs issue</strong> and not one more, because a repository method with no COBOL
 * caller is a surface nobody can verify against the oracle.
 *
 * <table border="1">
 *   <caption>Every access path, with the {@code EXEC CICS} command it translates</caption>
 *   <tr><th>Method</th><th>COBOL command</th><th>Evidence</th></tr>
 *   <tr><td>{@link #read(String)}</td><td>{@code READ}</td>
 *       <td>{@code app/cbl/COSGN00C.cbl:L211-L219}</td></tr>
 *   <tr><td>{@link #readForUpdate(String)}</td><td>{@code READ ... UPDATE}</td>
 *       <td>{@code app/cbl/COUSR02C.cbl:L322-L331}, {@code app/cbl/COUSR03C.cbl:L269-L278}</td></tr>
 *   <tr><td>{@link #startBrowse(String)}</td><td>{@code STARTBR}</td>
 *       <td>{@code app/cbl/COUSR00C.cbl:L588-L595}</td></tr>
 *   <tr><td>{@link BrowseCursor#readNext()}</td><td>{@code READNEXT}</td>
 *       <td>{@code app/cbl/COUSR00C.cbl:L621-L629}</td></tr>
 *   <tr><td>{@link BrowseCursor#readPrevious()}</td><td>{@code READPREV}</td>
 *       <td>{@code app/cbl/COUSR00C.cbl:L655-L663}</td></tr>
 *   <tr><td>{@link BrowseCursor#endBrowse()}</td><td>{@code ENDBR}</td>
 *       <td>{@code app/cbl/COUSR00C.cbl:L689-L691}</td></tr>
 *   <tr><td>{@link #add(SecUserRecord)}</td><td>{@code WRITE}</td>
 *       <td>{@code app/cbl/COUSR01C.cbl:L240-L248}</td></tr>
 *   <tr><td>{@link #rewrite(SecUserRecord)}</td><td>{@code REWRITE}</td>
 *       <td>{@code app/cbl/COUSR02C.cbl:L360-L366}</td></tr>
 *   <tr><td>{@link #deleteHeld(HeldRecord)}</td><td>{@code DELETE}, no {@code RIDFLD}</td>
 *       <td>{@code app/cbl/COUSR03C.cbl:L307-L311}</td></tr>
 * </table>
 *
 * <p>There is deliberately no {@code findAll}, no {@code count}, no {@code existsById}, no {@code save}
 * that merges an add with a rewrite, no {@code deleteById} and no paging helper, and this class
 * implements no Spring Data interface. None of those appears anywhere in the five programs. A
 * {@code deleteById} in particular would be a <em>different operation</em> from the one the source
 * performs - see {@link #deleteHeld(HeldRecord)}.
 *
 * <h2>The file definition this class honours</h2>
 * {@code app/csd/CARDDEMO.CSD:L88-L99} defines the CICS file:
 * <pre>
 * DEFINE FILE(USRSEC) GROUP(CARDDEMO)
 *        DSNAME(&lt;the security-user KSDS&gt;) RLSACCESS(NO)
 *        LSRPOOLNUM(1) READINTEG(UNCOMMITTED) DSNSHARING(ALLREQS)
 *        STRINGS(1) STATUS(ENABLED) OPENTIME(FIRSTREF) DISPOSITION(SHARE)
 *        UPDATEMODEL(LOCKING) LOAD(NO) RECORDFORMAT(V) ADD(YES)
 *        BROWSE(YES) DELETE(YES) READ(YES) UPDATE(YES) JOURNAL(NO)
 *        JNLSYNCWRITE(YES) RECOVERY(NONE)
 * </pre>
 * Four clauses of that definition are load-bearing here and are honoured rather than merely noted:
 * <ul>
 *   <li>{@code ADD BROWSE DELETE READ UPDATE} all {@code YES} - which is exactly the set of nine
 *       methods above, and the reason this is the only dataset in the module with a delete at all;</li>
 *   <li>{@code UPDATEMODEL(LOCKING)} - a {@code READ ... UPDATE} holds the record until the unit of work
 *       ends, which is what {@link #readForUpdate(String)} requires a unit of work for;</li>
 *   <li>{@code RECOVERY(NONE)} - there is no backout, so a failed write is not undone and the caller's
 *       error path is the whole of the recovery. That is a reason to report a status faithfully rather
 *       than to throw;</li>
 *   <li>{@code RECORDFORMAT(V)} - and here the CSD and the JCL disagree.
 *       {@code app/jcl/DUSRSECJ.jcl:L48} declares {@code DCB=(LRECL=80,RECFM=FB,DSORG=PS,BLKSIZE=0)}.
 *       The migration's VSAM-access analysis settles it: the Java layer treats record length as
 *       <strong>copybook-fixed at {@value #RECORD_LENGTH}</strong> regardless of which record format a
 *       given definition claims, because {@code app/cpy/CSUSR01Y.cpy} is the contract and every
 *       {@code EXEC CICS} call in all five programs passes {@code LENGTH(LENGTH OF SEC-USER-DATA)},
 *       which is that fixed 80.</li>
 * </ul>
 *
 * <h2>The dataset name is configured, never written here</h2>
 * The name is resolved from the {@code carddemo.datasets.USRSEC} binding, whose {@code dsname} is
 * itself overridable by environment variable. <strong>No dataset literal appears in this file</strong>,
 * and a source scan for one is part of the migration's acceptance criteria. The binding also carries the
 * record width and the key width, and both are checked against the copybook at construction time: a
 * deployment that pointed this repository at an 80-byte relation with a 4-byte key would decode
 * plausible-looking rubbish, so the mismatch is refused loudly at startup instead.
 *
 * <h2>No schema is created, migrated or described</h2>
 * There is no DDL here, no migration script, no {@code @Entity}, no {@code @Table}, no {@code @Column},
 * no generated table definition, no version column and no index creation. The dataset already exists;
 * this class reads and writes it through {@link JdbcTemplate} as a single-column record-image relation,
 * and the one thing it asks the backend to describe is the <em>name</em> of that column - because SQL
 * accepts an ordinal in {@code ORDER BY} but not in a {@code WHERE}, a {@code SET} or a {@code DELETE}
 * predicate. That name is discovered from result-set metadata, never invented and never defaulted.
 *
 * <h2>Outcomes are returned, never thrown</h2>
 * Every method hands back a discriminated outcome carrying a two-character file status, a
 * {@link Outcome}, the record where there is one, and the CICS response the five programs branch on. All
 * five are <strong>online</strong> programs: each handles a bad outcome by setting {@code WS-ERR-FLG},
 * moving a message into {@code WS-MESSAGE} and painting the screen. So this class raises no
 * {@code AbendException} - that is the batch {@code CALL 'CEE3ABD'} equivalent and has no counterpart in
 * any of these programs - and it never signals a normal COBOL outcome by throwing. An exception from
 * here means a wiring or contract defect (a null argument, a mis-sized key, a relation that is not this
 * dataset), never "the record was not found".
 *
 * <h2>No state lives on this class</h2>
 * Every field is {@code private final}, and the one static field is a logger. Two kinds of state that a
 * naive translation would put here are deliberately handed to the caller instead, because CICS scopes
 * both of them to the <em>task</em> and not to the file:
 * <ul>
 *   <li><strong>browse position.</strong> {@link #startBrowse(String)} returns a {@link BrowseCursor}
 *       that owns its own position. Two concurrent browses therefore cannot interfere, and a test does
 *       not have to reset anything between cases. A field holding "the current key" would have made this
 *       singleton's behaviour depend on whoever browsed last;</li>
 *   <li><strong>the held record.</strong> {@link #readForUpdate(String)} hands back a
 *       {@link HeldRecord}, and that handle - not a repository field, and not a key - is what
 *       {@link #deleteHeld(HeldRecord)} acts on.</li>
 * </ul>
 * Collaborators are constructor-injected; there is no field injection.
 *
 * <h2>The password stays plaintext</h2>
 * {@code SEC-USR-PWD PIC X(08)} is stored and compared in plaintext, exactly as
 * {@code app/cbl/COSGN00C.cbl:L223} does it. This class neither hashes nor encodes nor normalises it: it
 * moves the declared 80 bytes in both directions and nothing else. Strengthening the posture would
 * change observable behaviour and pull in a framework that is out of scope; weakening it is guarded
 * against separately, in that no method here logs a record image, a key or a password - see
 * {@link #logRefusal(Throwable, String)}.
 *
 * @see SecUserRecord the 80-byte record layout this class encodes and decodes
 * @see FileStatus the status and CICS-response vocabulary every outcome is drawn from
 */
@Repository
public class SecUserRepository {

    /** Where a refusal is reported. Static and {@code final}: a logger is not mutable state. */
    private static final Log LOG = LogFactory.getLog(SecUserRepository.class);

    // =================================================================================================
    // Identity of the file, as the five programs name it.
    // =================================================================================================

    /**
     * The CICS file name, which is also the {@code carddemo.datasets} binding key.
     *
     * <p>{@code app/csd/CARDDEMO.CSD:L88} defines {@code FILE(USRSEC)}, and all five programs address it
     * through {@code WS-USRSEC-FILE}. This is the name of the file, not the name of the dataset: the
     * dataset name is configured.
     */
    public static final String CICS_FILE_NAME = "USRSEC";

    /**
     * The declared width of {@code WS-USRSEC-FILE PIC X(8)}.
     *
     * <p>Every program declares the file name as an eight-character field, so a diagnostic that renders
     * it renders it at that width rather than trimmed.
     */
    public static final int CICS_FILE_NAME_LENGTH = 8;

    /**
     * The file name at its declared {@value #CICS_FILE_NAME_LENGTH}-character width, space-padded.
     *
     * <p>{@code MOVE 'USRSEC' TO WS-USRSEC-FILE} on a {@code PIC X(8)} leaves two trailing spaces, and
     * that is the image the {@code DATASET} option actually carries.
     */
    public static final String CICS_FILE_NAME_IMAGE = "USRSEC  ";

    /**
     * The record width, {@value #RECORD_LENGTH} bytes, taken from the record type and not restated.
     *
     * <p>Sourced from {@link SecUserRecord#RECORD_LENGTH} so the copybook has exactly one Java
     * representation. {@code app/cpy/CSUSR01Y.cpy:L17-L23} sums to this, and
     * {@code app/jcl/DUSRSECJ.jcl:L48} declares {@code LRECL=80} independently.
     */
    public static final int RECORD_LENGTH = SecUserRecord.RECORD_LENGTH;

    /**
     * The key width, {@value #KEY_LENGTH} bytes, <strong>derived</strong> rather than hard-coded.
     *
     * <p>The key is {@code SEC-USR-ID PIC X(08)}, and every {@code EXEC CICS} call in the five programs
     * passes {@code KEYLENGTH (LENGTH OF SEC-USR-ID)} - {@code COSGN00C:L216} passes
     * {@code LENGTH OF WS-USER-ID}, which is the same eight. Taking the value from
     * {@link SecUserRecord#KEY_LENGTH} means the copybook, not this file, decides it.
     */
    public static final int KEY_LENGTH = SecUserRecord.KEY_LENGTH;

    /**
     * The key's offset in the record, {@value #KEY_OFFSET}: {@code SEC-USR-ID} is the leading field.
     */
    public static final int KEY_OFFSET = SecUserRecord.KEY_OFFSET;

    /**
     * The one-based position of the record-image column in the relation.
     *
     * <p>A dataset carrying no relational metadata is presented as a single column holding the whole
     * record image. Sequential reads address it by position; keyed predicates address it by the name
     * discovered from the backend.
     */
    public static final int RECORD_IMAGE_COLUMN_INDEX = DatasetRelation.RECORD_IMAGE_COLUMN_INDEX;

    /**
     * The raw byte COBOL {@code LOW-VALUES} is: {@code x'00'}, the lowest value a byte can hold.
     *
     * <p>Named as a byte rather than as a character because that is what it is. A figurative constant is
     * a value in the record's code page, not a character in the JVM's, and the two only coincide by
     * accident.
     */
    private static final byte LOW_VALUES_BYTE = (byte) 0x00;

    /**
     * The raw byte COBOL {@code HIGH-VALUES} is: {@code x'FF'}, the highest value a byte can hold.
     *
     * <p>See {@link #HIGH_VALUES_KEY} for why naming the byte - rather than a character that happens to
     * render as one - is what makes the sentinel genuinely maximal.
     */
    private static final byte HIGH_VALUES_BYTE = (byte) 0xFF;

    /**
     * A key-width run of one raw byte: the byte image of a figurative constant.
     *
     * @param value the byte to repeat
     * @return a fresh {@value #KEY_LENGTH}-byte array, so no caller can mutate a shared one
     */
    private static byte[] figurativeKeyBytes(byte value) {
        byte[] image = new byte[KEY_LENGTH];
        Arrays.fill(image, value);
        return image;
    }

    /**
     * The key image meaning "before every possible key": COBOL {@code LOW-VALUES} at
     * {@value #KEY_LENGTH} characters.
     *
     * <p>{@code app/cbl/COUSR00C.cbl:L240} reads {@code MOVE LOW-VALUES TO SEC-USR-ID} before the
     * backward-paging {@code STARTBR}, which is how the user-list screen positions at the very start of
     * the file. {@code LOW-VALUES} is the lowest value the code page can hold, so this is
     * {@value #KEY_LENGTH} NUL characters rather than spaces - spaces are an ordinary printable value
     * that a real key could equal or fall below.
     */
    public static final String LOW_VALUES_KEY =
            new String(figurativeKeyBytes(LOW_VALUES_BYTE), StandardCharsets.ISO_8859_1);

    /**
     * The key image meaning "after every possible key": COBOL {@code HIGH-VALUES} at
     * {@value #KEY_LENGTH} characters.
     *
     * <p>{@code app/cbl/COUSR00C.cbl:L263} reads {@code MOVE HIGH-VALUES TO SEC-USR-ID} before the
     * forward-paging {@code STARTBR}. Positioning greater-than-or-equal to this finds nothing, so the
     * browse opens {@link Outcome#NOT_FOUND} and {@code L600-L606} reports "You are at the top of the
     * page...". <strong>That outcome is reproduced, not corrected.</strong> It is what the program does.
     *
     * <p>{@code x'FF'} and not {@code \uffff}: the dataset code page is single-byte - the record-image
     * form requires that - so the highest representable value is one byte of {@code x'FF'}.
     *
     * <p><strong>The value is derived from the raw byte, and the browse compares raw bytes.</strong> That
     * distinction is the whole of it. Encoding this string <em>through the dataset code page</em> would
     * not produce {@code x'FF'}: under {@code IBM037} the character {@code U+00FF} encodes to
     * {@code x'DF'}, which sits below every upper-case letter, so the sentinel would stop being maximal
     * and PF8's "no key is at or after high values" would find records; under {@code US-ASCII} the
     * character is not representable at all. So this constant is the {@code ISO-8859-1} rendering of the
     * raw sentinel byte - that code page maps byte to code point one for one, which is what makes it the
     * right choice for naming a byte rather than a character - and
     * {@link #keyImageBytes(String)} maps it back to the raw byte instead of encoding it. The comparison
     * that then uses it is unsigned-byte, so {@code x'FF'} really is greater than every other byte.
     */
    public static final String HIGH_VALUES_KEY =
            new String(figurativeKeyBytes(HIGH_VALUES_BYTE), StandardCharsets.ISO_8859_1);

    /**
     * The {@code RESP2} value reported when the condition has no reason code, which is every condition
     * this class raises for itself.
     *
     * <p>All five programs capture {@code RESP2} into {@code WS-REAS-CD} and three of them display it,
     * but none branches on it. A value invented here would be displayed as though CICS had supplied it,
     * so zero - {@link FileStatus#NO_REASON_CODE} - is what is reported unless the backend gave one.
     */
    public static final int CICS_RESP2_NOT_APPLICABLE = FileStatus.NO_REASON_CODE;

    /**
     * The feedback character of {@link #PERMANENT_ERROR_STATUS}: {@code x'00'}, not the digit zero.
     *
     * <p>A COBOL {@code FILE STATUS} of {@code '9x'} carries a binary feedback code in its second byte,
     * and this is the "no further information" one.
     */
    private static final char PERMANENT_ERROR_FEEDBACK_CODE = 0;

    /**
     * The status reported when the backend refuses an operation outright.
     *
     * <p>{@code '9'} followed by {@link #PERMANENT_ERROR_FEEDBACK_CODE}. None of the five programs has a
     * {@code WHEN} for it, which is the point: it lands on their {@code WHEN OTHER} arm, which is where
     * "Unable to lookup User...", "Unable to Add User..." and "Unable to Update User..." live. Reporting
     * it as an ordinary not-found would send the caller down a branch the failure does not justify.
     */
    public static final String PERMANENT_ERROR_STATUS = "9" + PERMANENT_ERROR_FEEDBACK_CODE;

    /**
     * The key span every keyed predicate in this class is composed from.
     *
     * <p>Immutable, so sharing it introduces no mutable static state. It is the module's one renderer of
     * a keyed {@code LIKE} pattern, which matters because a {@code RIDFLD} here is a character field
     * carrying whatever the screen supplied: an unescaped {@code %} in it would match records the key
     * does not name, and the rewrite and the delete share that predicate.
     */
    private static final KeySpan KEY_SPAN = new KeySpan(KEY_OFFSET, KEY_LENGTH);

    /** One row is all any single CICS file operation returns, so no statement here asks for more. */
    private static final int SINGLE_ROW = 1;

    /**
     * The driver fetch size the browse scan asks for: {@value}.
     *
     * <p>The scan reads the relation once and hands back at most one record, so nothing needs to be
     * buffered beyond a page. A positive value stated here is what stops a driver from choosing to
     * materialise the whole result set on the client, which is the bound a source-faithful sequential
     * read is entitled to; the JDBC contract makes the value a hint, so it can only reduce buffering and
     * never change which record is returned.
     */
    private static final int BROWSE_SCAN_FETCH_SIZE = 32;

    /**
     * The cap on the probe that establishes how many rows a key selects.
     *
     * <p>Two, because "none, one, or more than one" is the whole question a unique KSDS key can raise.
     */
    private static final int FAN_OUT_PROBE_LIMIT = 2;

    // =================================================================================================
    // Injected collaborators. All private and final; no field injection, no cached mutable shape.
    // =================================================================================================

    /** The module's shared template. Every statement this class issues goes through it. */
    private final JdbcTemplate jdbcTemplate;

    /** The fixed-width codec, holding the explicitly injected dataset code page. */
    private final FixedWidthCodec codec;

    /** The relation this repository addresses, and the composer of every statement it issues. */
    private final DatasetRelation relation;

    /** The resolved dataset name, from configuration. Surfaced for diagnostics only. */
    private final String datasetName;

    /**
     * This dataset's composed statements, resolved on first use and reused thereafter.
     *
     * <p>See {@link #resolveStatements()} for why they are cached rather than recomposed per operation.
     * {@code volatile} so the deeply immutable {@link Statements} record is published safely to every
     * thread that may enter this singleton; recomposing it yields an equal value, so no lock is needed
     * and none is taken. Never {@code static} - that would be shared mutable state across datasets, which
     * practice B9 and gate G53 forbid.
     */
    private volatile Statements statements;

    /** How this deployment's driver presents a record image over JDBC. Configured, never chosen here. */
    private final RecordImageForm recordImageForm;

    // =================================================================================================
    // Construction.
    // =================================================================================================

    /**
     * Wires the repository and refuses, at startup, any configuration that could not be this dataset.
     *
     * <p>Constructor injection only, so an instance cannot exist half-wired and a test can build one
     * without a container. Four things are established here, each failing loudly rather than degrading:
     * <ol>
     *   <li>every collaborator is present - a missing one is a wiring defect and is reported as one;</li>
     *   <li>{@value #CICS_FILE_NAME} is configured. {@link DatasetBindings#binding(String)} matches the
     *       key exactly, with no case-insensitive or fuzzy fallback, and names every configured key in
     *       its diagnostic when one is absent;</li>
     *   <li>the binding declares {@value #RECORD_LENGTH} bytes and, where it declares a key width at all,
     *       {@value #KEY_LENGTH}. This class decodes by absolute offset against
     *       {@code app/cpy/CSUSR01Y.cpy}, so any other width would place {@code SEC-USR-PWD} and
     *       {@code SEC-USR-TYPE} at spans that are not theirs - and a wrong user type is an
     *       authorisation outcome, not a cosmetic defect;</li>
     *   <li>the code page is single-byte, which the record-image form requires and which
     *       {@link #HIGH_VALUES_KEY} depends on.</li>
     * </ol>
     *
     * <p>The code page is injected by bean name and handed straight to the codec - never derived, never
     * the platform default. A fixed-width mainframe record is bytes in a specific encoding, and this
     * module states which one exactly once, in configuration.
     *
     * <p><strong>Nothing about the relation's shape is cached.</strong> The record-image column's name is
     * discovered per operation. A resolved shape held on this singleton would be shared mutable state,
     * and this migration is explicitly not a performance refactoring, so the metadata round trip is
     * accepted in exchange for a repository with no mutable field at all.
     *
     * @param jdbcTemplate    the module's shared template
     * @param datasetBindings the DD-name-keyed catalogue bound from {@code carddemo.datasets}
     * @param datasetCharset  the active dataset code page, selected by bean name so the choice is
     *                        explicit at the injection point
     * @param recordImageForm how this deployment's driver presents a record image
     * @throws NullPointerException     if any argument is {@code null}
     * @throws IllegalStateException    if {@value #CICS_FILE_NAME} is unconfigured, if its binding
     *                                  declares a record width other than {@value #RECORD_LENGTH}, a key
     *                                  width other than {@value #KEY_LENGTH} or a key at any offset but
     *                                  {@value #KEY_OFFSET}, or if it declares no dataset name at all
     * @throws IllegalArgumentException if the configured dataset name is not a well-formed dataset name,
     *                                  or if the code page is not single-byte
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

    // =================================================================================================
    // Read-only accessors. Diagnostics and assertions, never a way to reach around this class.
    // =================================================================================================

    /**
     * The resolved dataset name, exactly as configuration declares it.
     *
     * <p>Surfacing it introduces no literal into Java: the value came from
     * {@code carddemo.datasets.USRSEC.dsname}, which is itself environment-overridable.
     *
     * @return the configured dataset name; never {@code null} and never blank
     */
    public String datasetName() {
        return datasetName;
    }

    /**
     * The CICS file name at its declared {@code PIC X(8)} width, as the {@code DATASET} option carries it.
     *
     * @return {@value #CICS_FILE_NAME_IMAGE}
     */
    public String cicsFileName() {
        return CICS_FILE_NAME_IMAGE;
    }

    /**
     * The record width this repository reads and writes.
     *
     * @return {@value #RECORD_LENGTH}
     */
    public int recordLength() {
        return RECORD_LENGTH;
    }

    /**
     * The key width every operation here uses, derived from the copybook.
     *
     * @return {@value #KEY_LENGTH}
     */
    public int keyLength() {
        return KEY_LENGTH;
    }

    /**
     * The code page in which this repository encodes and decodes record images.
     *
     * <p>Surfaced so a caller that renders a record itself uses the same code page rather than choosing
     * one, and so a test can assert that the injected charset is the one actually in use.
     *
     * @return the injected dataset code page; never {@code null}
     */
    public Charset datasetCharset() {
        return codec.charset();
    }

    // =================================================================================================
    // READ - app/cbl/COSGN00C.cbl:L209-L219, paragraph READ-USER-SEC-FILE.
    //
    //     EXEC CICS READ
    //          DATASET   (WS-USRSEC-FILE)
    //          INTO      (SEC-USER-DATA)
    //          LENGTH    (LENGTH OF SEC-USER-DATA)
    //          RIDFLD    (WS-USER-ID)
    //          KEYLENGTH (LENGTH OF WS-USER-ID)
    //          RESP      (WS-RESP-CD)
    //          RESP2     (WS-REAS-CD)
    //     END-EXEC.
    // =================================================================================================

    /**
     * Reads one security-user record by key, without taking a lock: the sign-on read.
     *
     * <p>The Java form of {@code app/cbl/COSGN00C.cbl:L211-L219}. No {@code UPDATE} option, so no record
     * is held - see {@link #readForUpdate(String)} for the form that does.
     *
     * <p><strong>The caller's branch is a three-way split on the raw response value.</strong>
     * {@code L221-L257} is worth reading in full, because it is the only place in the five programs that
     * tests numeric {@code RESP} values instead of {@code DFHRESP} names:
     * <pre>
     * EVALUATE WS-RESP-CD
     *     WHEN 0      IF SEC-USR-PWD = WS-USER-PWD ... ELSE 'Wrong Password. Try again ...'
     *     WHEN 13     'User not found. Try again ...'
     *     WHEN OTHER  'Unable to verify the User ...'
     * END-EVALUATE.
     * </pre>
     * {@code 0} is {@link FileStatus#NORMAL} and {@code 13} is {@link FileStatus#NOTFND}, so the split is
     * exactly {@link ReadResult#isFound()}, {@link ReadResult#isNotFound()} and
     * {@link ReadResult#isOther()}. All three arms are reachable from here, and the distinction between
     * the second and the third is not cosmetic: "no such user" and "the security file is unreachable"
     * paint different messages, and collapsing them would tell an operator the wrong thing.
     *
     * <p>The password comparison itself is emphatically <em>not</em> done here. This method returns the
     * record; {@code IF SEC-USR-PWD = WS-USER-PWD} belongs to the sign-on service, which is where the
     * plaintext comparison the legacy design specifies is performed.
     *
     * @param userId the key exactly as the {@code RIDFLD} holds it: {@value #KEY_LENGTH} characters,
     *               space-padded, untrimmed and not upper-cased by this method. A shorter or longer value
     *               is a caller defect, because a short key would silently become a prefix match over
     *               more records than it names
     * @return the discriminated outcome; never {@code null}
     * @throws NullPointerException     if {@code userId} is {@code null}
     * @throws IllegalArgumentException if {@code userId} is not exactly {@value #KEY_LENGTH} characters
     * @throws IllegalStateException    if the backend presents the dataset with no usable record-image
     *                                  column
     */
    public ReadResult read(String userId) {
        return readKeyed(requireKeyImage(userId), false);
    }

    /**
     * Reads one security-user record by key <em>and holds it</em>: the update and delete read.
     *
     * <p>The Java form of {@code app/cbl/COUSR02C.cbl:L322-L331} and
     * {@code app/cbl/COUSR03C.cbl:L269-L278}, which are the same command as {@link #read(String)} plus
     * the {@code UPDATE} option:
     * <pre>
     * EXEC CICS READ
     *      DATASET   (WS-USRSEC-FILE)
     *      INTO      (SEC-USER-DATA)
     *      LENGTH    (LENGTH OF SEC-USER-DATA)
     *      RIDFLD    (SEC-USR-ID)
     *      KEYLENGTH (LENGTH OF SEC-USR-ID)
     *      UPDATE
     *      RESP      (WS-RESP-CD)
     *      RESP2     (WS-REAS-CD)
     * END-EXEC.
     * </pre>
     *
     * <p><strong>A distinct method, not a flag.</strong> The COBOL distinguishes the two commands, only
     * this one may be followed by a {@code REWRITE} or a {@code DELETE}, and this one alone requires a
     * unit of work. Folding them into one method with a boolean would hide all three differences at every
     * call site.
     *
     * <p><strong>What the hold is, and where it lives.</strong> The file is
     * {@code UPDATEMODEL(LOCKING)}, so CICS holds the record from this read until the unit of work ends;
     * {@code COUSR02C}'s {@code REWRITE} and {@code COUSR03C}'s {@code DELETE} both depend on that.
     * A successful read therefore returns a {@link HeldRecord} in {@link ReadResult#hold()}, and that
     * handle is what {@link #deleteHeld(HeldRecord)} acts on. The hold is <strong>not</strong> a field on
     * this repository: CICS scopes a held record to the task, and a field would scope it to the
     * singleton, so two concurrent updates would each delete whatever the other had just read.
     *
     * <p><strong>Why a unit of work is required.</strong> A {@code FOR UPDATE} row lock lives for the
     * length of the transaction that took it. Under auto-commit that is the length of the statement, so
     * the lock would be gone before the caller could rewrite or delete - and the caller would never know.
     * A CICS task always has a unit of work, so there is no legacy behaviour in which this succeeds
     * unlocked and nothing to report as a file status. It is a defect in the Java caller's wiring and is
     * reported as one.
     *
     * <p>No version column, no optimistic-lock annotation and no ETag is introduced. The record is held;
     * that is the mechanism the source uses and the one preserved.
     *
     * @param userId the key exactly as the {@code RIDFLD} holds it: {@value #KEY_LENGTH} characters,
     *               space-padded and untrimmed
     * @return the discriminated outcome, carrying a {@link HeldRecord} when the record was found; never
     *         {@code null}
     * @throws NullPointerException     if {@code userId} is {@code null}
     * @throws IllegalArgumentException if {@code userId} is not exactly {@value #KEY_LENGTH} characters
     * @throws IllegalStateException    if no unit of work is open, or if the backend presents the dataset
     *                                  with no usable record-image column
     */
    public ReadResult readForUpdate(String userId) {
        String keyImage = requireKeyImage(userId);
        // The key is deliberately absent from the refusal message. This is a wiring diagnostic - which
        // dataset, which operation - and a user identifier adds nothing to it while putting a credential
        // subject into a stack trace that may be logged or returned (CWE-532).
        DatasetUnitOfWork.requireActive("A read for update of the security-user file, which "
                + "EXEC CICS READ ... UPDATE holds for the unit of work under UPDATEMODEL(LOCKING) "
                + "(app/cbl/COUSR02C.cbl:L322-L331 before the REWRITE at L360, and "
                + "app/cbl/COUSR03C.cbl:L269-L278 before the DELETE at L307) - or use read(String) when "
                + "no lock is wanted, as app/cbl/COSGN00C.cbl:L211 does", datasetName);
        return readKeyed(keyImage, true);
    }

    // =================================================================================================
    // STARTBR - app/cbl/COUSR00C.cbl:L586-L614, paragraph STARTBR-USER-SEC-FILE.
    //
    //     EXEC CICS STARTBR
    //          DATASET   (WS-USRSEC-FILE)
    //          RIDFLD    (SEC-USR-ID)
    //          KEYLENGTH (LENGTH OF SEC-USR-ID)
    //   *      GTEQ                                    <-- L592, COMMENTED OUT IN THE SOURCE
    //          RESP      (WS-RESP-CD)
    //          RESP2     (WS-REAS-CD)
    //     END-EXEC.
    // =================================================================================================

    /**
     * Positions a browse of the security-user file at or after a key, and hands back the cursor.
     *
     * <p>The Java form of {@code app/cbl/COUSR00C.cbl:L588-L595}, the {@code STARTBR} both of the user
     * list screen's paging paragraphs begin with: {@code PROCESS-PAGE-FORWARD} at {@code L282-L331} and
     * {@code PROCESS-PAGE-BACKWARD} at {@code L336-L379}.
     *
     * <p><strong>{@code GTEQ} is commented out in the source, and greater-than-or-equal is still
     * correct - do not "fix" this.</strong> Line 592 of {@code COUSR00C} reads, verbatim:
     * <pre>
     *      KEYLENGTH (LENGTH OF SEC-USR-ID)
     * *         GTEQ
     *      RESP      (WS-RESP-CD)
     * </pre>
     * The {@code GTEQ} keyword is inside a COBOL comment, so it is <em>not</em> passed to CICS. That does
     * not make the positioning equal-only: {@code GTEQ} is the documented default of
     * {@code EXEC CICS STARTBR} when neither {@code GTEQ} nor {@code EQUAL} is specified, so commenting
     * it out selects exactly the behaviour it would have requested. Greater-than-or-equal positioning is
     * therefore what the program gets and what this method reproduces. The keyword is noted here so that a
     * later reader who finds it commented out does not conclude the code page has drifted and switch this
     * to an equality match - which would break both paging directions, because neither
     * {@link #LOW_VALUES_KEY} nor the anchor key of a backward page need be a key that exists.
     *
     * <p><strong>Both sentinel start keys are supported, including the one that finds nothing.</strong>
     * <ul>
     *   <li>{@link #LOW_VALUES_KEY} - {@code L240}, {@code MOVE LOW-VALUES TO SEC-USR-ID}. Every key is
     *       greater than or equal to it, so the browse opens {@link Outcome#OK} positioned at the first
     *       record;</li>
     *   <li>{@link #HIGH_VALUES_KEY} - {@code L263}, {@code MOVE HIGH-VALUES TO SEC-USR-ID}. No key is
     *       greater than or equal to it, so the browse opens {@link Outcome#NOT_FOUND}. That is a real
     *       outcome of the real program, not an error introduced here: {@code L600-L606} handles it by
     *       setting {@code USER-SEC-EOF} and painting "You are at the top of the page...".
     *       {@link BrowseCursor#openOutcome()} is that branch.</li>
     * </ul>
     *
     * <p><strong>The cursor is a returned value, never repository state.</strong> The position belongs
     * to the browse, so it lives on the {@link BrowseCursor} this method returns.
     * Two concurrent browses therefore cannot interfere - each advances its own position - and a test
     * needs no reset between cases. The cursor is {@link AutoCloseable}, so a caller may use
     * try-with-resources; {@link BrowseCursor#endBrowse()} is the explicit form and matches
     * {@code L325} and {@code L374}.
     *
     * <p>One {@code STARTBR} serves both directions, exactly as the source does: the same paragraph is
     * performed before the {@code READNEXT} loop and before the {@code READPREV} loop, and it specifies no
     * direction. The cursor accordingly offers {@link BrowseCursor#readNext()} and
     * {@link BrowseCursor#readPrevious()} over one position rather than being locked to a direction the
     * COBOL never declares.
     *
     * @param startKey the key to position at: {@value #KEY_LENGTH} characters, or
     *                 {@link #LOW_VALUES_KEY} or {@link #HIGH_VALUES_KEY}
     * @return the cursor, open or not according to {@link BrowseCursor#openOutcome()}; never {@code null}
     * @throws NullPointerException     if {@code startKey} is {@code null}
     * @throws IllegalArgumentException if {@code startKey} is not exactly {@value #KEY_LENGTH} characters
     */
    public BrowseCursor startBrowse(String startKey) {
        String anchorKey = requireKeyImage(startKey);

        Statements sql;
        try {
            // The dataset is described, not read: the probe's predicate is false on every row, so this
            // establishes that the relation exists and presents a record-image column without any of it
            // crossing the wire. Failing here is the analogue of a STARTBR that could not reach the file.
            sql = resolveStatements();
        } catch (DataAccessException unreachable) {
            return new BrowseCursor(this, null, anchorKey, PERMANENT_ERROR_STATUS,
                    CicsResponse.none(),
                    Optional.of(logRefusal(unreachable, "describe the security-user dataset '"
                            + datasetName + "' to position a browse")));
        }

        // GTEQ positioning, decided here and not by the first read: a STARTBR that finds no key at or
        // after the RIDFLD reports NOTFND, and the program branches on that before it reads anything.
        boolean positionable;
        try {
            positionable = anyRowFromKey(sql, anchorKey);
        } catch (DataAccessException refused) {
            return new BrowseCursor(this, null, anchorKey, PERMANENT_ERROR_STATUS, CicsResponse.none(),
                    Optional.of(logRefusal(refused, "position a browse of the security-user dataset '"
                            + datasetName + "'")));
        }
        if (!positionable) {
            // WHEN DFHRESP(NOTFND) at COUSR00C:L600. The cursor is handed back unopened rather than
            // null, so the caller branches on an outcome instead of on a reference.
            return new BrowseCursor(this, null, anchorKey, FileStatus.NOT_FOUND,
                    CicsResponse.of(FileStatus.NOTFND), Optional.empty());
        }
        return new BrowseCursor(this, sql, anchorKey, FileStatus.OK,
                CicsResponse.of(FileStatus.NORMAL), Optional.empty());
    }

    // =================================================================================================
    // WRITE - app/cbl/COUSR01C.cbl:L238-L274, paragraph WRITE-USER-SEC-FILE.
    //
    //     EXEC CICS WRITE
    //          DATASET   (WS-USRSEC-FILE)
    //          FROM      (SEC-USER-DATA)
    //          LENGTH    (LENGTH OF SEC-USER-DATA)
    //          RIDFLD    (SEC-USR-ID)
    //          KEYLENGTH (LENGTH OF SEC-USR-ID)
    //          RESP      (WS-RESP-CD)
    //          RESP2     (WS-REAS-CD)
    //     END-EXEC.
    // =================================================================================================

    /**
     * Adds one security-user record: the Java form of {@code app/cbl/COUSR01C.cbl:L240-L248}.
     *
     * <p>All {@value #RECORD_LENGTH} declared bytes are written, {@code SEC-USR-FILLER X(23)} included, so
     * the stored row is byte-identical to what {@link SecUserRecord#encode(SecUserRecord, Charset)}
     * produces. Dropping the filler would store 57 bytes and shift every subsequent byte of the dataset.
     *
     * <p><strong>The record carries its own key.</strong> {@code RIDFLD(SEC-USR-ID)} names the record's own
     * leading field, so there is no separate key parameter and there should not be: the row written is the
     * one whose key is {@link SecUserRecord#key()} of the argument.
     *
     * <p><strong>{@code DUPKEY} and {@code DUPREC} stay two outcomes.</strong> {@code L250-L274}
     * reads:
     * <pre>
     * EVALUATE WS-RESP-CD
     *     WHEN DFHRESP(NORMAL)   ... ' has been added ...'
     *     WHEN DFHRESP(DUPKEY)
     *     WHEN DFHRESP(DUPREC)   'User ID already exist...'
     *     WHEN OTHER             'Unable to Add User...'
     * END-EVALUATE.
     * </pre>
     * Two consecutive {@code WHEN}s share one action - which is a COBOL idiom for "either of these", not
     * for "these are the same condition". They are therefore reported as two distinguishable outcomes,
     * {@link WriteResult#isDuplicateRecord()} and {@link WriteResult#isDuplicateKey()}, with
     * {@link WriteResult#isDuplicate()} spanning both so the caller can collapse them itself exactly as
     * the source does. Merging them here would take that choice away and lose which condition occurred.
     *
     * <p>A key that already exists is {@link FileStatus#DUPREC}: that is the condition CICS raises for a
     * {@code WRITE} whose primary key is present. {@link FileStatus#DUPKEY} concerns a non-unique
     * alternate index, and {@value #CICS_FILE_NAME} has no alternate index defined anywhere in
     * {@code app/csd/CARDDEMO.CSD} - so the backend cannot produce it, while the outcome remains
     * expressible because the program has a {@code WHEN} for it.
     *
     * <p><strong>The duplicate is detected before the insert, not after it.</strong> The key is probed
     * first, and the insert is not issued when a row already holds it. Relying on an integrity violation
     * instead would work only where the deployment declared a primary key over the right span - the
     * driver is a deployment-time input, and a relation without that constraint would happily store a
     * second record under an existing user id and report success. An integrity violation is still
     * translated to {@link FileStatus#DUPREC} if one arrives, so a constrained relation and an
     * unconstrained one behave identically.
     *
     * @param record the record to add, complete and already assembled by the caller
     * @return the discriminated outcome; never {@code null}
     * @throws NullPointerException  if {@code record} is {@code null}
     * @throws IllegalStateException if the backend presents the dataset with no usable record-image column
     */
    public WriteResult add(SecUserRecord record) {
        Objects.requireNonNull(record, "A record is required to add one; a COBOL WRITE writes the record "
                + "area, and there is no such thing as writing nothing");

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
                // WHEN DFHRESP(DUPREC): the user id already exists. Reported without issuing the insert,
                // which is the same outcome the insert would have produced on a constrained relation and
                // the only correct one on an unconstrained one.
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
                // A unique-key violation IS the duplicate-record condition, so a relation that does
                // enforce the primary key reports the same outcome the probe above would have.
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

    // =================================================================================================
    // REWRITE - app/cbl/COUSR02C.cbl:L358-L390, paragraph UPDATE-USER-SEC-FILE.
    //
    //     EXEC CICS REWRITE
    //          DATASET   (WS-USRSEC-FILE)
    //          FROM      (SEC-USER-DATA)
    //          LENGTH    (LENGTH OF SEC-USER-DATA)
    //          RESP      (WS-RESP-CD)
    //          RESP2     (WS-REAS-CD)
    //     END-EXEC.                                    <-- note: NO RIDFLD
    // =================================================================================================

    /**
     * Rewrites the held security-user record in place: the Java form of
     * {@code app/cbl/COUSR02C.cbl:L360-L366}.
     *
     * <p><strong>Valid only after {@link #readForUpdate(String)}.</strong> The COBOL {@code REWRITE}
     * carries no {@code RIDFLD}: it rewrites the record the preceding {@code READ ... UPDATE} holds, and
     * {@code COUSR02C} performs {@code READ-USER-SEC-FILE} before it every time. This method requires a
     * unit of work for the same reason that read does - the row lock the read took must still be held, or
     * the comparison the program made between the screen fields and the record it read is protecting
     * nothing.
     *
     * <p><strong>The record carries its own key.</strong> {@code FROM(SEC-USER-DATA)} writes the whole
     * record area, whose leading eight bytes <em>are</em> {@code SEC-USR-ID}, so the row rewritten is the
     * one whose key is {@link SecUserRecord#key()} of the argument. That is why this method takes a record
     * rather than a {@link HeldRecord}: the argument is the record area, exactly as the COBOL option is,
     * and {@code COUSR02C:L219-L234} has already moved the four changed screen fields into it.
     * {@link HeldRecord#rewrite(SecUserRecord)} is the same operation reached from the handle, for a
     * caller that would rather the precondition were enforced than documented.
     *
     * <p>All {@value #RECORD_LENGTH} bytes are written, filler included, so a rewritten record is
     * byte-identical to the one the caller built. This method changes nothing about it: the four
     * mutations - first name, last name, password and user type - belong to the program.
     *
     * <p><strong>It never silently succeeds.</strong> Three outcomes:
     * <ul>
     *   <li>one row rewritten - {@link Outcome#OK}, the {@code WHEN DFHRESP(NORMAL)} arm at {@code L369}
     *       that composes "User ... has been updated ...";</li>
     *   <li>no row rewritten - {@link Outcome#NOT_FOUND}, because a rewrite whose key matches nothing is
     *       an invalid-key condition and not a success. {@code L377-L382} paints "User ID NOT found...";</li>
     *   <li>more than one row would be rewritten - a permanent error, reported <em>before</em> anything is
     *       written. A KSDS primary key is unique, so a relation in which this key selects several rows is
     *       not the dataset the copybook describes, and issuing the update would replace all of them with
     *       this one record. Discovering that from the affected-row count would be discovering it after the
     *       damage, so the key is required to name exactly one row first, under the same lock the update
     *       will use.</li>
     * </ul>
     * The cost is one extra bounded {@code SELECT} per rewrite, capped at {@value #FAN_OUT_PROBE_LIMIT}
     * rows because "none, one, or more than one" is the whole question. It is accepted deliberately: this
     * migration is explicitly not a performance refactoring, and a CICS {@code REWRITE} cannot produce a
     * multi-record outcome for the COBOL to have code for, so the state must not be reachable.
     *
     * @param record the record to write, complete and already mutated by the caller
     * @return the discriminated outcome; never {@code null}
     * @throws NullPointerException  if {@code record} is {@code null}
     * @throws IllegalStateException if no unit of work is open, or if the backend presents the dataset
     *                               with no usable record-image column
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

    // =================================================================================================
    // DELETE - app/cbl/COUSR03C.cbl:L305-L336, paragraph DELETE-USER-SEC-FILE.
    //
    //     EXEC CICS DELETE
    //          DATASET   (WS-USRSEC-FILE)
    //          RESP      (WS-RESP-CD)
    //          RESP2     (WS-REAS-CD)
    //     END-EXEC.                                    <-- note: NO RIDFLD. THIS MATTERS.
    // =================================================================================================

    /**
     * Deletes the record currently held for update - <strong>not</strong> a record named by a key.
     *
     * <p>The Java form of {@code app/cbl/COUSR03C.cbl:L307-L311}. Read that command again, because the
     * absence is the whole point: it carries {@code DATASET}, {@code RESP} and {@code RESP2} and
     * <strong>no {@code RIDFLD}</strong>. In CICS that is not an omission, it is a different operation:
     * a {@code DELETE} without a {@code RIDFLD} deletes the record the task currently holds from a
     * preceding {@code READ ... UPDATE}, which for {@code COUSR03C} is the read at {@code L269-L278}.
     * {@code L307} is reached only after {@code PROCESS-PF5-KEY} has performed that read.
     *
     * <p>So there is deliberately <strong>no delete-by-key method on this class</strong>. A
     * {@code deleteById} would issue a keyed delete with no preceding locking read - a different command,
     * with different concurrency behaviour, that no program in this codebase performs. Adding one would
     * invent surface, and worse, it would let a caller delete a record it had never read and so had never
     * shown the operator for confirmation, which is precisely the confirmation step {@code COUSR03C}
     * exists to impose.
     *
     * <p><strong>This method takes no key.</strong> Its argument is the {@link HeldRecord} that
     * {@link #readForUpdate(String)} produced, which is the Java expression of "the record this task
     * holds". Three properties follow, and all three are deliberate:
     * <ul>
     *   <li>the precondition is <em>enforced</em> rather than documented - a {@link HeldRecord} cannot be
     *       constructed except by a successful locking read, so the sequence cannot be got wrong;</li>
     *   <li>the held record is not repository state, so two concurrent deletes cannot each remove what the
     *       other read;</li>
     *   <li>the row deleted is the exact row that was read, matched on its whole stored image and not
     *       merely on its key - so a record that changed between the read and the delete is reported as
     *       not found instead of being removed unseen.</li>
     * </ul>
     *
     * <p>Outcomes, matching {@code L313-L336}: {@link Outcome#OK} for the row deleted, which is the
     * {@code WHEN DFHRESP(NORMAL)} arm composing "User ... has been deleted ..."; {@link Outcome#NOT_FOUND}
     * when the held row is no longer there, which is {@code L323-L328}, "User ID NOT found..."; and the
     * permanent-error status for a refusal, which lands on {@code WHEN OTHER}. Note that {@code L332}
     * paints "Unable to Update User..." on that arm rather than "Unable to Delete User..." - a wording
     * quirk of the source that belongs to the controller and is not corrected here.
     *
     * @param held the record handed back by a successful {@link #readForUpdate(String)}, standing for the
     *             record this task holds
     * @return the discriminated outcome; never {@code null}
     * @throws NullPointerException     if {@code held} is {@code null}
     * @throws IllegalArgumentException if {@code held} was produced by a different repository instance,
     *                                  which would mean deleting from a dataset other than the one read
     * @throws IllegalStateException    if no unit of work is open, or if the backend presents the dataset
     *                                  with no usable record-image column
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

    // =================================================================================================
    // Shared operation bodies. Each is the single implementation of one CICS command, so the entry point
    // on this class and the one on a handle cannot drift apart.
    // =================================================================================================

    /**
     * Reads one record by key, with or without the row lock a {@code READ ... UPDATE} takes.
     *
     * @param keyImage  the key exactly as the record stores it, {@value #KEY_LENGTH} characters
     * @param forUpdate whether to request the lock, and so whether to hand back a {@link HeldRecord}
     * @return the discriminated outcome; never {@code null}
     */
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
            // The INVALID KEY condition: RESP 13, DFHRESP(NOTFND). Reported, never thrown - COSGN00C's
            // WHEN 13 arm and COUSR02C's and COUSR03C's WHEN DFHRESP(NOTFND) arms all paint a message.
            return ReadResult.notFound();
        }
        if (match.matched() > SINGLE_ROW) {
            // Fan-out on a unique primary key. Refused rather than resolved, for the reason the write
            // paths refuse it: a caller handed an arbitrary one of several records has no way to know a
            // choice was made, and COSGN00C would authenticate against it.
            return fanOutReadRefused();
        }
        if (match.firstImage() == null) {
            return unreadableRow("a record read by key");
        }
        return decoded(match.firstImage(), "a record read by key", forUpdate);
    }

    /**
     * Rewrites one record against already-resolved statements.
     *
     * <p>Shared by {@link #rewrite(SecUserRecord)} and {@link HeldRecord#rewrite(SecUserRecord)}, so the
     * two entry points cannot behave differently.
     *
     * @param sql    the resolved statements
     * @param record the record to write, complete and already mutated by the caller
     * @return the discriminated outcome; never {@code null}
     */
    private WriteResult rewrite(Statements sql, SecUserRecord record) {
        byte[] recordImage = SecUserRecord.encode(record, codec);
        String keyPattern = KEY_SPAN.pattern(record.key());

        // Establish that the key names exactly one row BEFORE any row is replaced, under the same
        // FOR UPDATE lock the rewrite will use so nothing can change between the two. Reading the
        // affected-row count afterwards would discover a fan-out only after the rows were overwritten.
        int matching;
        try {
            matching = matchingRowCount(sql.selectByKeyForUpdate(), keyPattern);
        } catch (DataAccessException refused) {
            return reportWrite(refused, "establish how many rows the key of a record selects in the "
                    + "security-user dataset '" + datasetName + "' before rewriting it");
        }
        if (matching == 0) {
            // WHEN DFHRESP(NOTFND) at COUSR02C:L377. Reported without issuing the update, which is the
            // same outcome the update would have reported and one statement fewer.
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
            // WHEN DFHRESP(NOTFND) at COUSR03C:L323.
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

    /**
     * Reads one step of a browse: the anchor read, or the read that advances past a position.
     *
     * @param statement    the anchor statement or the advancing one
     * @param keyOperand   the anchor key, or {@code null} when advancing
     * @param positionImage the whole image to advance past, or {@code null} on the anchor read
     * @return whether a row arrived and, if it did, the image it carried; never {@code null}
     * @throws DataAccessException if the backend refused the read
     */
    private Row browseByKeyOrder(String scanStatement, byte[] boundKey, boolean forward,
            boolean inclusive) {
        PreparedStatementCreator creator = connection -> {
            // The single-argument overload, which JDBC defines as TYPE_FORWARD_ONLY and
            // CONCUR_READ_ONLY - exactly what this scan wants, and stated here rather than restated as
            // arguments so the statement is prepared the same way every other read in this class
            // prepares one. A small fetch size is asked for because the scan hands back at most one
            // record: the driver is to buffer a page, not the relation.
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
                    // A row with no image, or one too short to carry a key, cannot be placed in the key
                    // order at all - so it is REPORTED rather than skipped, and reported at once. Handing
                    // it straight back is what routes the caller to the same arm the previous
                    // collation-ordered read reached when such a row was the next one: a null image
                    // becomes the invalid-request response and a short one the length error, each naming
                    // what was observed. Skipping it instead would let a browse walk past a row the
                    // dataset genuinely holds and report a clean end of file over a damaged relation.
                    return new Row(true, candidate);
                }
                int against = compareKeys(candidate, boundKey);
                boolean admissible = forward
                        ? (inclusive ? against >= 0 : against > 0)
                        : against < 0;
                if (!admissible) {
                    continue;
                }
                // Forward wants the smallest admissible key, backward the largest: that is READNEXT and
                // READPREV, and it is decided here rather than by an ORDER BY so the ordering is the code
                // page's and not the backend collation's.
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

    /**
     * Compares two key spans as unsigned bytes: the VSAM collating sequence, in the dataset's own code
     * page.
     *
     * <p>The first argument is a whole record image and the key is read out of it at
     * {@value #KEY_OFFSET}; the second is a bare {@value #KEY_LENGTH}-byte key. Comparing the key span
     * rather than the whole image is what lets a position be a key - which is what {@code RIDFLD} is -
     * instead of needing the trailing bytes to break a tie.
     *
     * <p>{@link Arrays#compareUnsigned(byte[], int, int, byte[], int, int)} and not signed comparison:
     * a signed {@code byte} makes {@code x'FF'} negative, which would place {@code HIGH-VALUES} below
     * every printable character and invert the sentinel this browse depends on.
     *
     * @param recordImage a whole record image, at least {@value #KEY_OFFSET} + {@value #KEY_LENGTH} bytes
     * @param key         a bare key image of exactly {@value #KEY_LENGTH} bytes
     * @return negative, zero or positive as the record's key sorts before, with or after {@code key}
     */
    private static int compareKeys(byte[] recordImage, byte[] key) {
        return Arrays.compareUnsigned(recordImage, KEY_OFFSET, KEY_OFFSET + KEY_LENGTH,
                key, 0, key.length);
    }

    /**
     * The raw bytes of a key image, honouring the two figurative constants.
     *
     * <p>An ordinary key is encoded through the dataset's code page, because that is what it is: eight
     * characters a program moved into {@code SEC-USR-ID}, stored as eight bytes in the dataset's
     * encoding. The two figurative constants are <strong>not</strong> encoded, and that is the whole
     * point of {@link #HIGH_VALUES_KEY}'s note: {@code HIGH-VALUES} is the byte {@code x'FF'} and
     * {@code LOW-VALUES} the byte {@code x'00'} regardless of code page, and encoding them as characters
     * would produce {@code x'DF'} under {@code IBM037} or fail outright under {@code US-ASCII}.
     *
     * @param keyImage exactly {@value #KEY_LENGTH} characters, possibly one of the two sentinels
     * @return the {@value #KEY_LENGTH} bytes to compare against; a fresh array in every case
     */
    private byte[] keyImageBytes(String keyImage) {
        if (LOW_VALUES_KEY.equals(keyImage)) {
            return figurativeKeyBytes(LOW_VALUES_BYTE);
        }
        if (HIGH_VALUES_KEY.equals(keyImage)) {
            return figurativeKeyBytes(HIGH_VALUES_BYTE);
        }
        return codec.encodeImage(keyImage, SecUserRecord.FIELD_SEC_USR_ID);
    }

    // =================================================================================================
    // Statement issuing. Every JDBC interaction in this class funnels through these few methods.
    // =================================================================================================

    /**
     * Issues a keyed statement whose single parameter is a keyed {@code LIKE} pattern, and reports both
     * how many rows the key selected and the first row's image.
     *
     * <p>Bounded at {@value #FAN_OUT_PROBE_LIMIT} rather than at one row, and the extra row is the point.
     * {@code SEC-USR-ID} is the unique primary key of a KSDS ({@code app/cpy/CSUSR01Y.cpy}), so a second
     * matching row cannot arise in the legacy system - but a limit of one made that undetectable, and the
     * read would have returned whichever row the backend ordered first as though it were the record. The
     * write paths already probe with this same bound before they issue anything; the read now agrees with
     * them, so one integrity condition has one outcome across all of this class.
     *
     * @param statement the composed statement
     * @param pattern   the escaped pattern from {@link KeySpan#pattern(String)}
     * @return how many rows matched, capped at {@value #FAN_OUT_PROBE_LIMIT}, and the first row's image;
     *         never {@code null}
     * @throws DataAccessException if the backend refused the read
     */
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
            // One more probe and no further: "more than one" is the whole question, and the second row's
            // image is never needed because no answer built from it would be a faithful one.
            int matched = resultSet.next() ? FAN_OUT_PROBE_LIMIT : SINGLE_ROW;
            return new KeyedMatch(matched, first);
        };
        KeyedMatch match = jdbcTemplate.query(creator, extractor);
        // A driver that somehow produced no result object at all has told us nothing, and nothing is not
        // an absent record - but it is also not a fan-out, so it becomes the no-row answer here and the
        // caller's not-found arm, exactly as it did before this method reported a count.
        return match == null ? KeyedMatch.none() : match;
    }

    /**
     * Whether any row's key is greater than or equal to a key: the {@code STARTBR} with {@code GTEQ}.
     *
     * @param sql       the resolved statements
     * @param anchorKey the key to position at
     * @return {@code true} when the browse can be positioned, {@code false} for {@code NOTFND}
     * @throws DataAccessException if the backend refused the read
     */
    private boolean anyRowFromKey(Statements sql, String anchorKey) {
        return browseByKeyOrder(sql.browseScan(), keyImageBytes(anchorKey), true, true).present();
    }

    /**
     * How many rows a keyed pattern selects, capped at {@value #FAN_OUT_PROBE_LIMIT}.
     *
     * @param statement the keyed select, with the row lock where one is wanted
     * @param pattern   the escaped keyed pattern
     * @return {@code 0}, {@code 1}, or {@value #FAN_OUT_PROBE_LIMIT} meaning "more than one"
     * @throws DataAccessException if the backend refused the read
     */
    private int matchingRowCount(String statement, String pattern) {
        return countRows(connection -> {
            PreparedStatement prepared = bounded(connection.prepareStatement(statement));
            recordImageForm.bindOperand(prepared, 1, pattern, codec.charset());
            return prepared;
        });
    }

    /**
     * How many rows hold exactly one record image, capped at {@value #FAN_OUT_PROBE_LIMIT}.
     *
     * @param statement the whole-image select, with the row lock
     * @param image     the exact stored image
     * @return {@code 0}, {@code 1}, or {@value #FAN_OUT_PROBE_LIMIT} meaning "more than one"
     * @throws DataAccessException if the backend refused the read
     */
    private int matchingImageCount(String statement, byte[] image) {
        return countRows(connection -> {
            PreparedStatement prepared = bounded(connection.prepareStatement(statement));
            recordImageForm.bindImage(prepared, 1, image, codec.charset());
            return prepared;
        });
    }

    /**
     * Counts the rows a bounded statement returns, without materialising any record.
     *
     * @param creator the prepared, capped and bound statement
     * @return the row count, at most {@value #FAN_OUT_PROBE_LIMIT}
     * @throws DataAccessException if the backend refused the read
     */
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

    /**
     * Caps a statement at {@value #FAN_OUT_PROBE_LIMIT} rows: enough to tell "one" from "more than one".
     *
     * <p>Every keyed statement this class issues goes through here - the read, the locking read, and the
     * probes the rewrite and the delete take before they change anything - so all four bound the backend
     * identically and all four can tell a unique key from a violated one. There is deliberately no
     * one-row variant: a statement capped at one row cannot distinguish those two, and that was the
     * blind spot on the read path.
     *
     * <p>The fetch size is set as well as the row cap. {@code setMaxRows} bounds what the driver returns;
     * {@code setFetchSize} bounds what it buffers to get there, and the JDBC contract makes it a hint, so
     * it can only reduce buffering and never change which row arrives.
     *
     * @param statement the freshly prepared statement
     * @return the same statement, bounded
     * @throws SQLException if the driver refuses either bound
     */
    private static PreparedStatement bounded(PreparedStatement statement) throws SQLException {
        statement.setMaxRows(FAN_OUT_PROBE_LIMIT);
        statement.setFetchSize(FAN_OUT_PROBE_LIMIT);
        return statement;
    }

    /**
     * Composes this dataset's statements, discovering the record-image column's name from the backend.
     *
     * <p>SQL permits an ordinal in {@code ORDER BY} but not in a {@code WHERE}, a {@code SET} or a
     * {@code DELETE} predicate, so the name has to be known. It is taken from the result-set metadata of
     * the probe - discovered from the backend, never invented here, never defaulted and never added as a
     * configuration key, because a name this file made up would be exactly the kind of unverifiable
     * literal the migration forbids.
     *
     * <p><strong>Resolved once and reused.</strong> Five logical CICS operations reach this method - the
     * keyed read, the locking keyed read, {@code STARTBR}, {@code WRITE}, {@code REWRITE} and
     * {@code DELETE} - and each one used to describe the relation again first. That is a metadata round
     * trip per operation for a name that cannot change while the dataset exists, and the cost lands
     * exactly where it hurts most: {@code COUSR02C} and {@code COUSR03C} describe the dataset a second
     * time <em>after</em> a read has already taken a lock, so the extra query sits inside the window the
     * lock is held. The composed shape is cached instead.
     *
     * <p>{@link Statements} is a {@code record} of nine {@link String}s, so what is cached is deeply
     * immutable and two callers cannot pull a resolved shape out from under each other - they either see
     * the same one or each composes an identical one. The field is {@code volatile} for safe publication
     * and is never {@code static}: it is per-dataset instance state on a singleton, which is what practice
     * B9 and gate G53 permit, and what {@link DatasetRelation}'s own discovered column name already is.
     *
     * @return the composed statements; never {@code null}
     * @throws DataAccessException   if the dataset cannot be described
     * @throws IllegalStateException if the dataset presents no usable record-image column
     */
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

    // =================================================================================================
    // Outcome construction for the conditions this class raises for itself.
    // =================================================================================================

    /**
     * Decodes a stored record image, or reports that it is not a security-user record.
     *
     * <p><strong>The width is required, not repaired.</strong> A short image is not widened with spaces
     * even though the record's trailing field is {@code SEC-USR-FILLER}: bytes could have been lost
     * anywhere, and padding a row that was written against a different layout produces a record whose
     * {@code SEC-USR-TYPE} decodes from a span that is not its own. A wrong user type is an authorisation
     * outcome - {@code 'A'} routes to the admin menu at {@code app/cbl/COSGN00C.cbl:L232} - so the repair
     * would convert a diagnosable failure into a plausible privilege, which is worse than the failure.
     * An over-long image is rejected for the same reason, symmetrically.
     *
     * <p>Reported as {@link FileStatus#LENGERR}, the response CICS gives when a record does not fit its
     * receiver. The actual width is named in the log line rather than carried as a reason code, because a
     * byte count is not a CICS reason code.
     *
     * @param recordImage the stored image, exactly as the configured representation presented it
     * @param subject     how to name the row in a diagnostic - never its content
     * @param forUpdate   whether to attach a {@link HeldRecord} to the successful outcome
     * @return the successful outcome carrying the decoded record, or a permanent-error outcome
     */
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
        // The hold carries the exact bytes the backend presented, not a re-encoding of the decoded
        // record: the delete matches on that image, and the two differ for any stored row that does not
        // already hold exactly what the model would write.
        return ReadResult.held(record, new HeldRecord(this, record, recordImage));
    }

    /**
     * Reports a row that is present but carries no record image.
     *
     * <p>Present-and-unreadable is not an end of file and not a not-found: it is an invalid request, which
     * is what CICS reports for a row it cannot deliver. Collapsing it onto not-found would let a caller
     * conclude the user does not exist when in fact the dataset is malformed.
     *
     * @param subject how to name the row in a diagnostic
     * @return the permanent-error outcome
     */
    private ReadResult unreadableRow(String subject) {
        LOG.error("The security-user dataset '" + datasetName + "' presented " + subject
                + " with no record image at column position " + RECORD_IMAGE_COLUMN_INDEX
                + "; reporting file status " + FileStatus.toStatusImage(PERMANENT_ERROR_STATUS)
                + " to the caller");
        return ReadResult.of(PERMANENT_ERROR_STATUS, CicsResponse.of(FileStatus.INVREQ));
    }

    /**
     * Refuses a keyed read whose key selects more than one row.
     *
     * <p>The same outcome {@link #fanOutRefused(String)} gives a write, and deliberately so: one integrity
     * violation, one status, whichever operation met it. Not-found would be a lie - the records exist -
     * and the duplicate-key response would be worse, because CICS reports {@code DUPKEY} for a read
     * through a <em>path</em> on a non-unique alternate key, an expected condition with a defined
     * meaning, and this is a non-unique <em>primary</em> key, which VSAM cannot present at all. So it
     * lands on the caller's {@code WHEN OTHER} arm.
     *
     * <p>Neither the key nor any record content is logged: this dataset holds user identifiers and, per
     * {@code app/cpy/CSUSR01Y.cpy}, plaintext passwords.
     *
     * @return the permanent-error outcome
     */
    private ReadResult fanOutReadRefused() {
        LOG.error("A keyed read of the security-user dataset '" + datasetName + "' matched more than one "
                + "row; SEC-USR-ID is the unique primary key of a KSDS, so the read is being refused and "
                + "file status " + FileStatus.toStatusImage(PERMANENT_ERROR_STATUS) + " reported to the "
                + "caller rather than returning an arbitrary one of them as though it were the record. "
                + "The backing relation needs a unique constraint on its key span.");
        return ReadResult.of(PERMANENT_ERROR_STATUS, CicsResponse.of(FileStatus.INVREQ));
    }

    /**
     * Refuses a write whose predicate selects more than one row, before it is issued.
     *
     * @param operation the COBOL verb being refused, for the log line
     * @return the permanent-error outcome
     */
    private WriteResult fanOutRefused(String operation) {
        LOG.error("A " + operation + " against the security-user dataset '" + datasetName + "' would "
                + "have affected more than one row; a KSDS primary key is unique, so the " + operation
                + " is being refused before it is issued and file status "
                + FileStatus.toStatusImage(PERMANENT_ERROR_STATUS) + " reported to the caller. No row "
                + "has been changed.");
        return WriteResult.of(PERMANENT_ERROR_STATUS, CicsResponse.of(FileStatus.INVREQ));
    }

    /**
     * Reports a write that affected a number of rows no CICS command could have produced.
     *
     * @param operation the COBOL verb, for the log line
     * @param affected  how many rows the statement reported
     * @return the permanent-error outcome
     */
    private WriteResult unexpectedRowCount(String operation, int affected) {
        LOG.error("A " + operation + " against the security-user dataset '" + datasetName + "' affected "
                + affected + " row(s) where the probe had found exactly " + SINGLE_ROW
                + "; reporting file status " + FileStatus.toStatusImage(PERMANENT_ERROR_STATUS)
                + " so the caller reaches its WHEN OTHER arm");
        return WriteResult.of(PERMANENT_ERROR_STATUS, CicsResponse.of(FileStatus.INVREQ));
    }

    // =================================================================================================
    // Backend-refusal reporting. Every catch arm goes through one of these three.
    // =================================================================================================

    /**
     * Logs a backend refusal with the driver's own diagnosis and returns it for the caller to carry.
     *
     * <p>What is logged is what the backend said - {@code SQLSTATE}, vendor code and the exception type
     * that carried them - and the dataset name. Deliberately <strong>not</strong> logged: the user id, the
     * record image, or any part of either. A security-user record holds a plaintext
     * {@code SEC-USR-PWD PIC X(08)}, so a log line that echoed it would put a live credential into a file
     * that is read by more people, retained for longer and protected less than the dataset itself
     * (CWE-532).
     *
     * <p>The {@link Throwable} is <strong>not</strong> handed to the logger either, and that is worth
     * stating plainly because it looks like a loss. A driver's message is prose composed around the values
     * it refused, so a rejected record image can appear in it verbatim; logging the exception emits that
     * text and its whole cause chain, and a control character anywhere in it splits the entry in two
     * (CWE-117). The codes that survive are what separate an absent dataset from wrong credentials from a
     * dropped connection, which is the whole of what an operator acts on, and the driver's own words remain
     * in the driver's own log, which is access-controlled as an application log is not.
     *
     * @param refusal the exception the backend or the framework raised
     * @param attempt what was being attempted, phrased to complete "Could not ..."
     * @return the diagnostic read out of {@code refusal}
     */
    private BackendDiagnostic logRefusal(Throwable refusal, String attempt) {
        BackendDiagnostic diagnostic = BackendDiagnostic.of(refusal);
        LOG.error("Could not " + attempt + " - " + diagnostic.describe() + "; reporting file status "
                + FileStatus.toStatusImage(PERMANENT_ERROR_STATUS) + " to the caller");
        return diagnostic;
    }

    /**
     * Reports a backend refusal of a read as a permanent-error status carrying the diagnostic.
     *
     * @param refusal the exception raised
     * @param attempt what was being attempted
     * @return the outcome
     */
    private ReadResult reportRead(Throwable refusal, String attempt) {
        return ReadResult.of(PERMANENT_ERROR_STATUS, logRefusal(refusal, attempt));
    }

    /**
     * Reports a backend refusal of a write as a permanent-error status carrying the diagnostic.
     *
     * @param refusal the exception raised
     * @param attempt what was being attempted
     * @return the outcome
     */
    private WriteResult reportWrite(Throwable refusal, String attempt) {
        return WriteResult.of(PERMANENT_ERROR_STATUS, logRefusal(refusal, attempt));
    }

    // =================================================================================================
    // Pure helpers. Private and static where no instance state is involved.
    // =================================================================================================

    /**
     * Requires a key image of exactly the declared width.
     *
     * <p>A short key would silently become a prefix match over more records than it names, and a long one
     * would address bytes past {@code SEC-USR-ID}. Both are caller defects rather than COBOL outcomes: the
     * {@code RIDFLD} is a {@code PIC X(08)} field, so it is always exactly eight characters by the time a
     * program issues a command with it. The value is <strong>not</strong> trimmed, padded or upper-cased
     * here - {@code FUNCTION UPPER-CASE} normalisation belongs to the sign-on service, which is where the
     * source performs it.
     *
     * @param candidate the supplied key image
     * @return {@code candidate}, unchanged
     * @throws NullPointerException     if {@code candidate} is {@code null}
     * @throws IllegalArgumentException if it is not exactly {@value #KEY_LENGTH} characters
     */
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

    /**
     * Resolves and validates the {@value #CICS_FILE_NAME} binding against the copybook's geometry.
     *
     * @param datasetBindings the configured catalogue
     * @return the validated binding
     * @throws IllegalStateException if the binding is absent or declares a geometry this record cannot have
     */
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

    /**
     * Requires a dataset name that can be used as a SQL identifier.
     *
     * <p>Two distinct failures, reported as two distinct kinds, matching the module's sibling
     * repositories exactly:
     * <ul>
     *   <li><strong>nothing was configured</strong> - an absent or blank {@code dsname}. That is a
     *       configuration <em>state</em> defect, so it is an {@link IllegalStateException} and the
     *       diagnostic names the property to set. This arm is raised here rather than delegated,
     *       because only this class knows which binding key was being resolved;</li>
     *   <li><strong>something was configured but is not a dataset name</strong> - delegated to
     *       {@link DatasetRelation#requireDatasetName(String)}, which raises an
     *       {@link IllegalArgumentException}. The z/OS naming grammar lives there so that the rule is
     *       stated once for every repository rather than restated - differently - in each. It already
     *       excludes control characters, which is why this method does not check for them a second time;
     *       {@link #requireUsableColumnName(String)} does, because a name the backend reports is subject
     *       to no such grammar.</li>
     * </ul>
     *
     * @param candidate the {@code dsname} component of the resolved binding
     * @return {@code candidate}, unchanged
     * @throws IllegalStateException    if {@code candidate} is {@code null} or blank
     * @throws IllegalArgumentException if it is not a well-formed dataset name
     */
    private static String requireUsableDatasetName(String candidate) {
        if (candidate == null || candidate.isBlank()) {
            throw new IllegalStateException("The dataset binding for '" + CICS_FILE_NAME + "' declares "
                    + "no dataset name. Set carddemo.datasets." + CICS_FILE_NAME + ".dsname; this "
                    + "repository composes its statements from configuration alone and hard-codes no "
                    + "dataset name.");
        }
        return DatasetRelation.requireDatasetName(candidate);
    }

    /**
     * Requires a record-image column name the backend actually reported.
     *
     * @param candidate the discovered name, or {@code null}
     * @return {@code candidate}, unchanged
     * @throws IllegalStateException if the backend described no usable column
     */
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

    /**
     * Refuses a value that carries a control character.
     *
     * <p>Such a value reaches both a composed SQL identifier and a log line, and in a log line it splits
     * one entry into two (CWE-117). No legitimate dataset or column name contains one.
     *
     * @param candidate the value to check
     * @param subject   how to name it in the diagnostic
     * @throws IllegalStateException if a control character is present
     */
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

    /**
     * Classifies a two-character file status into the module's shared outcome vocabulary.
     *
     * @param status the two-character status
     * @return the classification; {@link Outcome#OTHER} for any status the programs did not enumerate
     * @throws NullPointerException     if {@code status} is {@code null}
     * @throws IllegalArgumentException if {@code status} is not exactly two characters
     */
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

    // =================================================================================================
    // Nested types.
    // =================================================================================================

    /**
     * Whether a read found a row, and the image it carried.
     *
     * <p>Three states, because the callers distinguish three: no row is the end of a browse or an absent
     * key; a row whose record-image column holds nothing is a record that is present and unreadable, which
     * is an I/O failure and emphatically not an absence; and a row with an image is a record. Collapsing
     * the first two onto one {@code null} would let a browse stop early and silently.
     *
     * @param present whether a row arrived at all
     * @param image   the row's record image, which may be {@code null} even when a row arrived
     */
    private record Row(boolean present, byte[] image) {

        /** The shared no-row answer. Immutable, so sharing it introduces no mutable static state. */
        private static final Row NONE = new Row(false, null);

        /**
         * The answer for a read that found no row.
         *
         * @return the no-row answer
         */
        static Row none() {
            return NONE;
        }
    }

    /**
     * How many rows a keyed read's key selected, and the first row's image.
     *
     * <p>Separate from {@link Row} because the count is a keyed read's question and no other read's: a
     * browse step returns one record at a time by construction, so "how many share this key" has no
     * meaning there. Keeping the two apart is what stops the browse from carrying a count no caller can
     * interpret.
     *
     * @param matched how many rows the key selected, capped at {@value #FAN_OUT_PROBE_LIMIT}; a value of
     *                {@value #FAN_OUT_PROBE_LIMIT} means "at least that many" rather than "exactly"
     * @param image   the first row's record image, which may be {@code null} even when a row arrived
     */
    private record KeyedMatch(int matched, byte[] image) {

        /** The shared no-row answer. Immutable, so sharing it introduces no mutable static state. */
        private static final KeyedMatch NONE = new KeyedMatch(0, null);

        /**
         * The answer for a keyed read whose key selected nothing.
         *
         * @return the no-row answer
         */
        static KeyedMatch none() {
            return NONE;
        }

        /**
         * The first row's image, named to read at the call site as what it is.
         *
         * @return the image, or {@code null} when no row arrived or the row carried none
         */
        byte[] firstImage() {
            return image;
        }
    }

    /**
     * The statements for this dataset, composed once per operation from the discovered column name.
     *
     * <p>Every text is built by {@link DatasetRelation}, which is what keeps a keyed read, a locking read,
     * a browse and a rewrite identical in form across the module's repositories - with two exceptions,
     * both noted on {@link #over(DatasetRelation, String)} and both composed here because
     * {@value SecUserRepository#CICS_FILE_NAME} is the only dataset in the estate whose COBOL deletes a record.
     *
     * @param selectByKey            the keyed read, taking the keyed pattern
     * @param selectByKeyForUpdate   the locking keyed read, taking the keyed pattern
     * @param browseScan             the browse read: the whole relation, with <strong>no</strong>
     *                               predicate and <strong>no</strong> {@code ORDER BY}, because
     *                               positioning and ordering are decided by unsigned-byte comparison of
     *                               the encoded key and not by the backend's character collation. See
     *                               {@link SecUserRepository#browseByKeyOrder(String, byte[], boolean,
     *                               boolean)}
     * @param insert                 the {@code WRITE}, taking the record image
     * @param rewrite                the {@code REWRITE}, taking the new image then the keyed pattern
     * @param selectByImageForUpdate the locking whole-image probe, taking the held image
     * @param deleteByImage          the held-record {@code DELETE}, taking the held image
     */
    record Statements(String selectByKey,
                      String selectByKeyForUpdate,
                      String browseScan,
                      String insert,
                      String rewrite,
                      String selectByImageForUpdate,
                      String deleteByImage) {

        /** The {@code FOR UPDATE} clause, appended where a row lock is wanted. */
        private static final String FOR_UPDATE = " FOR UPDATE";

        /**
         * Composes every statement over one relation and one discovered column name.
         *
         * <p>Five of the seven come straight from {@link DatasetRelation}. The remaining two - the
         * whole-image probe and the held-record delete - are composed here because
         * {@link DatasetRelation} offers no delete builder, and it offers none because
         * {@value SecUserRepository#CICS_FILE_NAME} is the only dataset any program in this codebase deletes from. They are
         * built from {@link DatasetRelation#identifier()} and
         * {@link DatasetRelation#delimit(String)} - the same identifier and the same quoting every other
         * statement uses - rather than by hand, so the delete cannot quote a name differently from the
         * read that found it.
         *
         * <p>The browse statement is deliberately {@link DatasetRelation#selectAll()} - no predicate and
         * no {@code ORDER BY}. {@link DatasetRelation}'s ordered builders exist and are correct for every
         * other dataset in the estate, whose keys are digits and spaces; they are not used here because
         * {@code SEC-USR-ID} is the estate's only <strong>alphanumeric</strong> key, and letters and
         * digits are the one domain where the code page's byte order and a SQL character collation
         * genuinely disagree.
         *
         * @param relation the relation to address
         * @param column   the record-image column's name, as discovered from the backend
         * @return the composed statements
         */
        static Statements over(DatasetRelation relation, String column) {
            String wholeImagePredicate = " WHERE " + DatasetRelation.delimit(column) + " = ?";
            return new Statements(
                    relation.selectByKey(column),
                    relation.selectByKeyForUpdate(column),
                    relation.selectAll(),
                    relation.insertRecordImage(column),
                    relation.rewriteByKey(column),
                    "SELECT * FROM " + relation.identifier() + wholeImagePredicate + FOR_UPDATE,
                    "DELETE FROM " + relation.identifier() + wholeImagePredicate);
        }
    }

    /**
     * The outcome of a read: the status, its classification, the record where there was one, the hold
     * where the read took one, and the CICS response the programs branch on.
     *
     * <p>Constructed only through the named factories, so a status and its classification can never
     * disagree and no caller can branch on one while reading the other.
     *
     * @param record     the decoded record, present only for a successful read
     * @param hold       the held record, present only for a successful {@link #readForUpdate(String)}
     * @param status     the two-character file status
     * @param outcome    the classification of {@code status}
     * @param response   the CICS {@code RESP} and {@code RESP2} the programs capture
     * @param diagnostic what the backend said, present only when it refused
     */
    public record ReadResult(String status,
                             Outcome outcome,
                             Optional<SecUserRecord> record,
                             Optional<HeldRecord> hold,
                             CicsResponse response,
                             Optional<BackendDiagnostic> diagnostic) {

        /**
         * Validates the outcome's internal consistency.
         *
         * @throws NullPointerException     if any component is {@code null}
         * @throws IllegalArgumentException if the status is not two characters, if the classification does
         *                                  not match it, if a record is present without success, or if a
         *                                  hold is present without a record
         */
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
         * @param hold   the handle standing for the record this task holds
         * @return the outcome
         */
        public static ReadResult held(SecUserRecord record, HeldRecord hold) {
            Objects.requireNonNull(record, "A successful locking read carries the record it read");
            Objects.requireNonNull(hold, "A successful locking read carries the hold it took");
            return new ReadResult(FileStatus.OK, Outcome.OK, Optional.of(record), Optional.of(hold),
                    CicsResponse.of(FileStatus.NORMAL), Optional.empty());
        }

        /**
         * The keyed record was absent: {@code RESP} {@code 13}, {@code DFHRESP(NOTFND)}. The
         * {@code WHEN 13} arm of {@code app/cbl/COSGN00C.cbl:L247} and the
         * {@code WHEN DFHRESP(NOTFND)} arms of {@code COUSR02C:L340} and {@code COUSR03C:L287}.
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
         * <p>Distinct from {@link #notFound()} on purpose. The user-list screen paints "You have reached
         * the bottom of the page..." for this and "You are at the top of the page..." for a
         * {@code STARTBR} that found nothing, so the two cannot be the same outcome.
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
         * <p>Deliberately unable to report a success. A successful read carries the record it read - that
         * is what makes {@link #isFound()} a guarantee rather than a hint - so a success status with no
         * record is refused by the canonical constructor rather than quietly accepted. Use
         * {@link #found(SecUserRecord)} or {@link #held(SecUserRecord, HeldRecord)} for a success.
         *
         * @param status   the two-character status of an outcome that carries no record
         * @param response the CICS response
         * @return the outcome
         * @throws IllegalArgumentException if {@code status} is not two characters, or classifies as a
         *                                  success - for which a record is required
         */
        public static ReadResult of(String status, CicsResponse response) {
            return new ReadResult(status, classify(status), Optional.empty(), Optional.empty(), response,
                    Optional.empty());
        }

        /**
         * An outcome that carries no record, with what the backend said: the {@code WHEN OTHER} arm after
         * a refusal.
         *
         * <p>No CICS response value travels with it, because a backend refusal is not a CICS condition and
         * inventing a {@code RESP} for one would make a driver's failure look like something CICS
         * reported. The {@code SQLSTATE} and vendor code travel in the diagnostic instead.
         *
         * @param status     the two-character status of an outcome that carries no record
         * @param diagnostic what the backend reported
         * @return the outcome
         * @throws NullPointerException     if {@code diagnostic} is {@code null}
         * @throws IllegalArgumentException if {@code status} is not two characters, or classifies as a
         *                                  success
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

        /**
         * Whether this is the {@code WHEN OTHER} arm.
         *
         * @return {@code true} for any outcome the programs did not enumerate
         */
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
     * <p>{@link FileStatus#DUPREC} and {@link FileStatus#DUPKEY} both classify as
     * {@link Outcome#DUPLICATE} and both carry status {@link FileStatus#DUPLICATE}, but they remain
     * distinguishable through {@link #isDuplicateRecord()} and {@link #isDuplicateKey()} - which is what
     * lets a caller collapse them itself, exactly as the two consecutive {@code WHEN}s at
     * {@code app/cbl/COUSR01C.cbl:L260-L261} do.
     *
     * @param status     the two-character file status
     * @param outcome    the classification of {@code status}
     * @param response   the CICS {@code RESP} and {@code RESP2}
     * @param diagnostic what the backend said, present only when it refused
     */
    public record WriteResult(String status,
                              Outcome outcome,
                              CicsResponse response,
                              Optional<BackendDiagnostic> diagnostic) {

        /**
         * Validates the outcome's internal consistency.
         *
         * @throws NullPointerException     if any component is {@code null}
         * @throws IllegalArgumentException if the status is not two characters, or if the classification
         *                                  does not match it
         */
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
         * <p>{@value SecUserRepository#CICS_FILE_NAME} has no alternate index defined in {@code app/csd/CARDDEMO.CSD}, so
         * the backend cannot produce this condition. The factory exists because the program has a
         * {@code WHEN} for it, and preserving the program's branch structure means the outcome has to be
         * expressible rather than folded into its neighbour.
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
         * @param status   the two-character status
         * @param response the CICS response
         * @return the outcome
         */
        public static WriteResult of(String status, CicsResponse response) {
            return new WriteResult(status, classify(status), response, Optional.empty());
        }

        /**
         * Any other outcome, carrying what the backend said: the {@code WHEN OTHER} arm after a refusal.
         *
         * @param status     the two-character status
         * @param diagnostic what the backend reported
         * @return the outcome
         */
        public static WriteResult of(String status, BackendDiagnostic diagnostic) {
            Objects.requireNonNull(diagnostic, "A reported refusal carries the backend's diagnosis");
            return new WriteResult(status, classify(status), CicsResponse.none(),
                    Optional.of(diagnostic));
        }

        /**
         * Whether the write succeeded.
         *
         * @return {@code true} for a successful write
         */
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

        /**
         * Whether this is the {@code WHEN OTHER} arm.
         *
         * @return {@code true} for any outcome the programs did not enumerate
         */
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
     *
     * <h2>Why this type exists at all</h2>
     * {@code app/cbl/COUSR03C.cbl:L307-L311} issues {@code EXEC CICS DELETE} with a {@code DATASET} and
     * <strong>no {@code RIDFLD}</strong>. That command has no key operand because it does not need one:
     * CICS deletes whatever record the task is currently holding. Java has no task-held-record concept, so
     * the hold has to be represented by something - and the two obvious candidates are both wrong.
     * A field on the repository would make the hold shared across every caller of a singleton, so two
     * concurrent deletes would each remove what the other had just read. A key parameter would turn the
     * command into a keyed delete, which is a <em>different</em> operation with different concurrency
     * behaviour that no program in this codebase performs. This handle is the third option: task-scoped
     * because the caller holds it, unforgeable because only a successful locking read can produce one, and
     * carrying no key because it identifies the row by the exact bytes that were read.
     *
     * <h2>What it carries, and why the exact bytes</h2>
     * The record as decoded, and the <strong>exact stored image</strong> the backend presented. The image
     * and not a re-encoding of the record, because the two differ for any stored row that does not already
     * hold precisely what the model would write, and it is the stored row that has to be matched. Matching
     * on it rather than on the key is what makes the delete target the row that was read: a row that
     * changed in the interval - which {@code UPDATEMODEL(LOCKING)} exists to prevent, but which a
     * deployment whose backend took no real row lock would allow - reports not-found instead of being
     * removed unseen.
     *
     * <p>The image is copied in and copied out, so a caller cannot reach through this handle and alter the
     * bytes the delete will match on.
     */
    public static final class HeldRecord {

        /** The repository that took this hold, for its statements and its dataset identity. */
        private final SecUserRepository repository;

        /** The record as decoded from the held row. */
        private final SecUserRecord record;

        /** The exact bytes of the held row, as the backend presented them. */
        private final byte[] storedImage;

        /**
         * Constructed only by a successful {@link SecUserRepository#readForUpdate(String)}, which is what
         * makes the precondition of the delete unforgeable rather than merely documented.
         *
         * @param repository  the repository that took the hold
         * @param record      the decoded record
         * @param storedImage the exact stored image, copied in
         */
        private HeldRecord(SecUserRepository repository, SecUserRecord record, byte[] storedImage) {
            this.repository = repository;
            this.record = record;
            this.storedImage = storedImage.clone();
        }

        /**
         * The record this task holds.
         *
         * <p>This is what {@code app/cbl/COUSR02C.cbl:L219-L234} compares the screen fields against before
         * deciding whether anything changed, and what {@code app/cbl/COUSR03C.cbl} paints for confirmation
         * before the operator presses PF5.
         *
         * @return the decoded record; never {@code null}
         */
        public SecUserRecord record() {
            return record;
        }

        /**
         * The dataset this hold belongs to.
         *
         * @return the configured dataset name
         */
        public String datasetName() {
            return repository.datasetName();
        }

        /**
         * Rewrites the held record, from the handle.
         *
         * <p>The same operation as {@link SecUserRepository#rewrite(SecUserRecord)}, reached from the hold
         * so that the {@code READ ... UPDATE} precondition is visible in the call rather than only in the
         * documentation. It takes a record, not no argument, because the COBOL {@code REWRITE} writes the
         * record <em>area</em>: {@code COUSR02C:L219-L234} moves the four changed screen fields into it
         * first, so the record written is not the record read.
         *
         * @param updated the record to write, complete and already mutated by the caller
         * @return the discriminated outcome; never {@code null}
         * @throws NullPointerException  if {@code updated} is {@code null}
         * @throws IllegalStateException if no unit of work is open
         */
        public WriteResult rewrite(SecUserRecord updated) {
            return repository.rewrite(updated);
        }

        /**
         * Deletes the held record, from the handle - and takes no argument at all.
         *
         * <p>The most literal available translation of {@code app/cbl/COUSR03C.cbl:L307-L311}: the COBOL
         * command names a dataset and a response pair and nothing else, and neither does this. There is no
         * key to supply because there is no key in the command, and no repository state is consulted
         * because the hold is right here.
         *
         * @return the discriminated outcome; never {@code null}
         * @throws IllegalStateException if no unit of work is open
         */
        public WriteResult deleteHeld() {
            return repository.deleteHeld(this);
        }

        /**
         * The repository that took this hold, for the identity check the delete performs.
         *
         * @return the opening repository
         */
        private SecUserRepository repository() {
            return repository;
        }

        /**
         * The exact bytes of the held row, copied out.
         *
         * @return a copy of the stored image, exactly
         *         {@value SecUserRepository#RECORD_LENGTH} bytes
         */
        private byte[] storedImage() {
            return storedImage.clone();
        }
    }

    /**
     * One browse of the security-user file: the position a {@code STARTBR} established, and the reads that
     * advance it.
     *
     * <h2>The position lives here, not on the repository</h2>
     * This handle is what {@link SecUserRepository#startBrowse(String)} returns, and it owns the whole of
     * the browse's mutable state. Two concurrent browses therefore cannot interfere - each advances its own
     * position - and a test needs no reset between cases. Had the position been a repository field, this
     * singleton's behaviour would have depended on whoever browsed last, which is exactly the shared-state
     * defect that a stateless translation of a pseudo-conversational program has to avoid.
     *
     * <h2>Both directions, from one {@code STARTBR}</h2>
     * {@code app/cbl/COUSR00C.cbl} performs the same {@code STARTBR-USER-SEC-FILE} paragraph before its
     * forward loop ({@code L284}, then {@code READNEXT} at {@code L289}, {@code L301} and {@code L311}) and
     * before its backward loop ({@code L338}, then {@code READPREV} at {@code L343}, {@code L355} and
     * {@code L363}), and that paragraph specifies no direction. So this cursor offers both reads over one
     * position rather than being locked to a direction the source never declares.
     *
     * <h2>The first read is the anchor read, in either direction</h2>
     * The first read after positioning returns the record <em>at or after</em> the anchor key - which is
     * what greater-than-or-equal positioning means, and it holds for {@link #readPrevious()} too. The
     * backward paragraph relies on exactly that: {@code L342-L344} performs one {@code READPREV} and
     * discards what it returns, because the anchor is {@code CDEMO-CU00-USRID-FIRST}, the record already at
     * the top of the page being paged away from. Every read after the anchor advances strictly past the
     * previous record's whole image.
     */
    public static final class BrowseCursor implements AutoCloseable {

        /** The repository that opened this browse, for its statement issuing and dataset identity. */
        private final SecUserRepository repository;

        /**
         * The statements the open resolved, or {@code null} when the open did not succeed.
         *
         * <p>Resolved once, by the {@code STARTBR}, and reused by every read of this browse - which is
         * both faithful (a CICS browse establishes its access path once) and the reason a browse costs one
         * metadata round trip rather than one per read.
         */
        private final Statements statements;

        /** The key the browse was positioned at. */
        private final String anchorKey;

        /** The status the {@code STARTBR} reported. */
        private final String openStatus;

        /** The classification of {@link #openStatus}. */
        private final Outcome openOutcome;

        /** The CICS response the {@code STARTBR} reported. */
        private final CicsResponse openResponse;

        /** What the backend said, when the open failed because it refused. */
        private final Optional<BackendDiagnostic> openDiagnostic;

        /**
         * The key bytes of the record last returned, or {@code null} before the first read.
         *
         * <p><strong>The key, and the exact bytes the backend presented for it.</strong> A key is what
         * {@code RIDFLD} holds and what VSAM sequences on, so the key alone is a complete position: the
         * next record is the one whose key is the smallest strictly greater than this, which is precisely
         * {@code READNEXT}. That is only expressible because the comparison is this class's own -
         * unsigned-byte over the encoded key - rather than the backend's, where a bare key would sort
         * below the record carrying it and the browse would return the same record for ever.
         *
         * <p>The bytes are taken from the stored image rather than re-encoded from the decoded record,
         * because those two differ for any stored row that does not already hold exactly what the model
         * would write, and a position must be what the dataset actually contains.
         */
        private byte[] position;

        /** Whether any read has succeeded yet, and so whether the next one advances or anchors. */
        private boolean positioned;

        /** How many records this browse has returned. */
        private int returned;

        /** Whether {@link #endBrowse()} or {@link #close()} has been called. */
        private boolean ended;

        /**
         * Constructed only by {@link SecUserRepository#startBrowse(String)}, which is what guarantees that
         * a cursor's permission to read reflects what the positioning actually established.
         *
         * @param repository the opening repository
         * @param statements the resolved statements, or {@code null} when the open did not succeed
         * @param anchorKey  the key positioned at
         * @param openStatus the status the open reported
         * @param openResponse the CICS response the open reported
         * @param openDiagnostic what the backend said, when it refused
         */
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

        /**
         * The status the {@code STARTBR} reported.
         *
         * @return {@link FileStatus#OK}, {@link FileStatus#NOT_FOUND}, or the permanent-error status
         */
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

        /**
         * Whether the browse has been ended.
         *
         * @return {@code true} once {@link #endBrowse()} or {@link #close()} has been called
         */
        public boolean isEnded() {
            return ended;
        }

        /**
         * The key this browse was positioned at.
         *
         * @return the anchor key, exactly {@value SecUserRepository#KEY_LENGTH} characters
         */
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

        /**
         * How many records this browse has returned.
         *
         * @return the count, never negative
         */
        public int returned() {
            return returned;
        }

        /**
         * The dataset this browse reads.
         *
         * @return the configured dataset name
         */
        public String datasetName() {
            return repository.datasetName();
        }

        /**
         * Reads the next record in ascending key order: {@code app/cbl/COUSR00C.cbl:L621-L629}.
         *
         * <p>The first read returns the record at or after the anchor key; each read after it returns the
         * next higher key. Running past the last record reports {@link ReadResult#isEndOfFile()},
         * distinctly, because that is what terminates the paging loop at {@code L300-L306} - and repeating
         * the read reports it again rather than wrapping round, because an end of file leaves the position
         * alone.
         *
         * @return the read outcome; never {@code null}
         */
        public ReadResult readNext() {
            return read(true);
        }

        /**
         * Reads the previous record in descending key order: {@code app/cbl/COUSR00C.cbl:L655-L663}.
         *
         * <p>The first read returns the record at or after the anchor key - greater-than-or-equal
         * positioning applies in this direction too - and each read after it returns the next lower key.
         * The source discards that first record deliberately at {@code L342-L344}, because it is the one
         * already at the top of the page being paged away from; discarding it is the caller's decision to
         * make and is made there, not here.
         *
         * @return the read outcome; never {@code null}
         */
        public ReadResult readPrevious() {
            return read(false);
        }

        /**
         * Reads one step, anchoring on the first read and advancing on every read after it.
         *
         * @param forward whether this is a {@code READNEXT} rather than a {@code READPREV}
         * @return the read outcome; never {@code null}
         */
        private ReadResult read(boolean forward) {
            if (ended) {
                // No browse is in progress, so there is nothing to read from. CICS reports an invalid
                // request for a browse operation without a browse, and so does this.
                LOG.error("A read was requested on a browse of " + CICS_FILE_NAME + " that has already "
                        + "been ended; reporting the invalid-request response");
                return ReadResult.of(PERMANENT_ERROR_STATUS, CicsResponse.of(FileStatus.INVREQ));
            }
            if (statements == null) {
                // The STARTBR did not succeed, so the source never reaches a read at all: both paging
                // paragraphs guard their loops with IF NOT ERR-FLG-ON. Reporting the open's own outcome
                // keeps a caller that ignored that guard on the branch the open put it on.
                LOG.error("A read was requested on a browse of " + CICS_FILE_NAME + " that reported "
                        + FileStatus.toStatusImage(openStatus) + " when it was positioned; reporting that "
                        + "outcome rather than reading from a browse that was never established");
                return openDiagnostic
                        .map(diagnostic -> ReadResult.of(openStatus, diagnostic))
                        .orElseGet(() -> ReadResult.of(openStatus, openResponse));
            }

            Row row;
            try {
                // The anchor step is inclusive - STARTBR's GTEQ default positions AT or after the RIDFLD
                // and the first read returns that record - and every step after it is exclusive, which is
                // READNEXT and READPREV. The bound is a KEY in both cases, so no trailing byte of the
                // previous record is needed to break a tie, and the comparison is unsigned-byte in the
                // dataset's own code page rather than the backend's character collation.
                row = positioned
                        ? repository.browseByKeyOrder(statements.browseScan(), position, forward, false)
                        : repository.browseByKeyOrder(statements.browseScan(),
                                repository.keyImageBytes(anchorKey), true, true);
            } catch (DataAccessException refused) {
                return repository.reportRead(refused, "read " + (forward ? "the next" : "the previous")
                        + " record of a browse of the security-user dataset '" + datasetName() + "'");
            }

            if (!row.present()) {
                // WHEN DFHRESP(ENDFILE) at L634 and L668. The position is deliberately left alone, so
                // repeating the read reports the end of the file again rather than restarting the browse.
                return ReadResult.endOfFile();
            }
            if (row.image() == null) {
                return repository.unreadableRow("a record of a browse");
            }
            ReadResult result = repository.decoded(row.image(), "a record of a browse", false);
            if (result.isFound()) {
                // Advance only on a record, and advance to the bytes the backend actually presented. A
                // failure likewise leaves the position alone, so a caller that retries retries the same
                // step rather than skipping one.
                // The key span of the record just returned. Copied out rather than aliased, so a later
                // read cannot be steered by a caller that kept a reference to the image.
                position = Arrays.copyOfRange(row.image(), KEY_OFFSET, KEY_OFFSET + KEY_LENGTH);
                positioned = true;
                returned++;
            }
            return result;
        }

        /**
         * Ends the browse: {@code app/cbl/COUSR00C.cbl:L689-L691}, performed at {@code L325} and
         * {@code L374}.
         *
         * <p><strong>Nothing is reported, deliberately.</strong> The COBOL command specifies no
         * {@code RESP} and no {@code RESP2} and the paragraph has no {@code EVALUATE} after it, so the
         * program looks at no outcome and neither does this method. Inventing an error path here would
         * create a branch the source cannot take and that no test could justify.
         *
         * <p>No backend call is made, because none is owed: this browse holds no server-side cursor open,
         * only a position. What ending it does is close the handle, so that a read after it is refused
         * rather than silently resuming a browse the caller believes it has finished with. Ending an
         * already-ended browse does nothing, which makes {@link #close()} safe to call after it.
         */
        public void endBrowse() {
            ended = true;
        }

        /**
         * Ends the browse, so the handle can be used in a try-with-resources block.
         *
         * <p>Declared without a checked exception, because ending a browse cannot fail.
         */
        @Override
        public void close() {
            endBrowse();
        }
    }
}
