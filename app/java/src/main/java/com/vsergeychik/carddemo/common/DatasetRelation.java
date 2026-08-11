package com.vsergeychik.carddemo.common;

import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;

/**
 * The one contract by which this module reaches a mainframe dataset over JDBC: how a dataset name
 * becomes a SQL identifier, how a record is located within a relation, how a keyed, browsing or
 * locking access path is expressed, and how a backend refusal is read.
 *
 * <h2>Why one contract rather than one per repository</h2>
 * <p>Every repository here faces the same four questions, and every answer is a property of the
 * <em>deployment</em> rather than of the record: what SQL identifier names this dataset, which column
 * of the relation carries the record image, how is a key at a byte offset expressed as a predicate, and
 * what does this backend's refusal mean. Answered per repository, the answers diverge - and they did:
 * before this class existed there were six independent identifier renderings in this module, one of
 * which interpolated a configured name into SQL with no quoting at all, and one repository composed its
 * statements against <em>copybook field names</em> as though they were SQL columns while simultaneously
 * reading the whole record image out of column 1. Both cannot be true of the same backend. A single
 * contract makes the deployment assumption explicit, reviewable in one place, and impossible to answer
 * two ways.
 *
 * <h2>The record-image assumption, stated once</h2>
 * <p>A VSAM record is a byte string, not a row of typed columns, and the copybooks in {@code app/cpy}
 * are the only description of its interior that exists. So the deployment assumption this module makes
 * is: <strong>a dataset is a relation whose first column carries the whole record image</strong>, and
 * the interior of that image is addressed by absolute byte offset through
 * {@link FixedWidthCodec}, never by SQL. The consequences are deliberate:
 * <ul>
 *   <li><strong>No copybook field name is ever used as a SQL column name.</strong> {@code CARD-NUM} is
 *       a span at offset 0 for 16 bytes; it is not a column, and asking a backend for it would either
 *       fail or - worse - succeed against a relation this module has no other reason to believe in.</li>
 *   <li><strong>The record-image column is discovered, not assumed.</strong> Its name comes from
 *       {@link ResultSetMetaData} at ordinal {@value #RECORD_IMAGE_COLUMN_INDEX}, because the name is a
 *       site-specific deployment detail while its <em>position</em> is the contract. The name is
 *       validated and remembered once per relation. An <em>output-only</em> dataset is the one case
 *       where no name can be discovered, because nothing describes it - see
 *       {@link #insertRecordImage()}, which binds the image positionally instead.</li>
 *   <li><strong>A key is an offset and a length, not a column.</strong> A key span becomes a
 *       {@code LIKE} pattern over the record image - {@code offset} single-character wildcards, the
 *       escaped key, then a trailing wildcard - which is core SQL every backend implements, unlike the
 *       substring functions whose spelling differs between every dialect.</li>
 * </ul>
 * No DDL, no schema, no table definition and no index is created or implied by any of this. The
 * relation is whatever the deployment already exposes.
 *
 * <h2>The dataset name is validated, not merely quoted</h2>
 * <p>A dataset name arrives from configuration, which is externally controlled, and reaches SQL as an
 * identifier - a position no bind parameter can occupy. Quoting alone is not enough there, so the name
 * is first required to be a <strong>well-formed z/OS dataset name</strong>: dot-separated qualifiers,
 * each one to eight characters, each beginning with a letter or one of {@code # @ $} and continuing with
 * those, digits or a hyphen, the whole no longer than 44 characters, with an optional relative
 * generation suffix such as {@code (+1)}. That grammar admits no quotation mark, no semicolon, no space,
 * no comma and no parenthesis except the generation suffix, so a name that passes it cannot carry SQL
 * syntax; the delimited rendering that follows is then belt and braces rather than the only defence.
 * Every one of the eight {@code DSNAME} values in {@code app/csd/CARDDEMO.CSD} satisfies it, the longest
 * being the 38-character {@code AWS.M2.CARDDEMO.CARDDATA.VSAM.AIX.PATH}.
 *
 * <h2>A refusal is read from the backend, never invented</h2>
 * <p>{@link BackendDiagnostic} carries the {@code SQLSTATE}, the vendor code and the exception type
 * exactly as the driver reported them, and classification is by {@code SQLSTATE} <em>class</em> - the
 * two-character prefix the SQL standard defines - rather than by the wrapper type a framework happened
 * to choose. A driver's vendor code is a driver's number: it is reported as one, and it is never passed
 * off as a CICS {@code RESP2}, which is a different quantity from a different system.
 *
 * <h2>What this class deliberately does not do</h2>
 * <p>It composes statements, validates names and reads diagnostics. It executes nothing and holds no
 * framework type: the repositories own the connection, the transaction scope and the mapping of an
 * outcome to a file status, which is why this class imports nothing beyond {@code java.sql} and
 * {@code java.util} - no Spring, and nothing from another package of this module except its sibling
 * {@link FixedWidthCodec} in documentation only.
 *
 * @see FixedWidthCodec
 * @see FileStatus
 */
public final class DatasetRelation {

    /**
     * The ordinal of the column carrying the whole record image: the first.
     *
     * <p>Position rather than name is the contract, because the name is a site-specific deployment
     * detail and the position is not. {@link #discoverRecordImageColumn(DataSource)} exists to learn the
     * name at this position so that an {@code ORDER BY} or a {@code WHERE} can be written, which is the
     * only reason a name is ever needed.
     */
    public static final int RECORD_IMAGE_COLUMN_INDEX = 1;

    /** The SQL delimited-identifier quotation mark. */
    private static final String IDENTIFIER_QUOTE = "\"";

    /** The character that escapes a {@code LIKE} metacharacter, declared in the {@code ESCAPE} clause. */
    private static final char LIKE_ESCAPE = '\\';

    /** {@code LIKE}'s any-sequence metacharacter. */
    private static final char LIKE_ANY_SEQUENCE = '%';

    /** {@code LIKE}'s exactly-one-character metacharacter, which spans a byte a key does not cover. */
    private static final char LIKE_ANY_CHARACTER = '_';

    /** The longest a z/OS dataset name may be, qualifiers and separating dots included. */
    private static final int MAX_DATASET_NAME_LENGTH = 44;

    /** The longest a single qualifier of a z/OS dataset name may be. */
    private static final int MAX_QUALIFIER_LENGTH = 8;

    /** The qualifier separator. */
    private static final char QUALIFIER_SEPARATOR = '.';

    /** The national characters a qualifier may begin with, alongside a letter. */
    private static final String NATIONAL_CHARACTERS = "#@$";

    /** The configured dataset name, exactly as the deployment supplied it. */
    private final String dsname;

    /** {@link #dsname} rendered as a SQL delimited identifier. */
    private final String identifier;

    /** The copybook-declared record width in bytes. */
    private final int recordLength;

    /**
     * The discovered record-image column name, or {@code null} until first discovered.
     *
     * <p>Instance state, never static: a static cache would be shared mutable state and would also be
     * wrong, because two relations have two different columns. {@code volatile} because a repository is
     * a singleton whose methods may be entered from several request threads, and a half-published
     * reference to a name would be worse than discovering it twice.
     */
    private volatile String recordImageColumn;

    private DatasetRelation(String dsname, int recordLength) {
        this.dsname = requireDatasetName(dsname);
        this.identifier = delimit(this.dsname);
        if (recordLength < 1) {
            throw new IllegalArgumentException("Dataset '" + dsname + "' is declared " + recordLength
                    + " byte(s) wide; a record occupies at least 1 byte, and the width comes from the "
                    + "copybook by way of the dataset binding");
        }
        this.recordLength = recordLength;
    }

    /**
     * Binds a validated dataset name and its declared record width into a relation.
     *
     * @param dsname       the configured dataset name, from a {@code carddemo.datasets.*} binding
     * @param recordLength the copybook-declared record width in bytes
     * @return the relation
     * @throws NullPointerException     if {@code dsname} is {@code null}
     * @throws IllegalArgumentException if {@code dsname} is not a well-formed z/OS dataset name, or if
     *                                  {@code recordLength} is below 1
     */
    public static DatasetRelation of(String dsname, int recordLength) {
        return new DatasetRelation(dsname, recordLength);
    }

    /**
     * The configured dataset name, unchanged.
     *
     * @return the dataset name
     */
    public String dsname() {
        return dsname;
    }

    /**
     * The dataset name as a SQL delimited identifier, ready to be composed into a statement.
     *
     * @return the delimited identifier, quotation marks included
     */
    public String identifier() {
        return identifier;
    }

    /**
     * The copybook-declared record width in bytes.
     *
     * @return the record width
     */
    public int recordLength() {
        return recordLength;
    }

    /**
     * Validates a z/OS dataset name and returns it unchanged.
     *
     * <p>This is the single grammar in the module, and it is a grammar rather than a list of forbidden
     * characters. A rule written as "reject a quote and a semicolon" answers the question "which
     * characters have I thought of"; a rule written as "each qualifier is one to eight characters drawn
     * from this alphabet" answers "which names does the platform actually permit", and everything else -
     * including whatever punctuation SQL happens to find significant this decade - falls outside it
     * without having to be enumerated.
     *
     * <p>The optional trailing relative-generation suffix is admitted because the configuration uses it:
     * {@code AWS.M2.CARDDEMO.DALYREJS(+1)}, {@code TRANREPT(+1)} and {@code SYSTRAN(+1)} are the three
     * generation-data-group outputs, and their {@code (+n)} form is part of the name a deployment
     * supplies.
     *
     * @param candidate the configured name
     * @return {@code candidate}, unchanged
     * @throws NullPointerException     if {@code candidate} is {@code null}
     * @throws IllegalArgumentException if the name is not well formed, with the offending position named
     */
    public static String requireDatasetName(String candidate) {
        Objects.requireNonNull(candidate, "A dataset name is required; it comes from a "
                + "carddemo.datasets.* binding and is never defaulted");
        String name = candidate;
        String generation = "";
        int suffixStart = candidate.indexOf('(');
        if (suffixStart >= 0) {
            name = candidate.substring(0, suffixStart);
            generation = candidate.substring(suffixStart);
            requireGenerationSuffix(generation, candidate);
        }
        if (name.isEmpty() || name.length() > MAX_DATASET_NAME_LENGTH) {
            throw new IllegalArgumentException(rejection(candidate) + "its " + name.length()
                    + "-character name is outside the 1 to " + MAX_DATASET_NAME_LENGTH
                    + " characters a z/OS dataset name occupies");
        }
        int qualifierStart = 0;
        for (int position = 0; position <= name.length(); position++) {
            boolean atEnd = position == name.length();
            if (!atEnd && name.charAt(position) != QUALIFIER_SEPARATOR) {
                continue;
            }
            requireQualifier(name, qualifierStart, position, candidate);
            qualifierStart = position + 1;
        }
        return candidate;
    }

    /** Validates one dot-separated qualifier of a dataset name, in place. */
    private static void requireQualifier(String name, int from, int to, String candidate) {
        int length = to - from;
        if (length < 1 || length > MAX_QUALIFIER_LENGTH) {
            throw new IllegalArgumentException(rejection(candidate) + "the qualifier starting at "
                    + "0-based position " + from + " is " + length + " character(s) long, where a "
                    + "z/OS qualifier is 1 to " + MAX_QUALIFIER_LENGTH);
        }
        for (int position = from; position < to; position++) {
            char character = name.charAt(position);
            boolean acceptable = position == from
                    ? isQualifierInitial(character)
                    : isQualifierBody(character);
            if (!acceptable) {
                throw new IllegalArgumentException(rejection(candidate) + "the character at 0-based "
                        + "position " + position + " is not one a z/OS qualifier admits "
                        + (position == from
                                ? "in its first position, which is a letter or one of "
                                        + NATIONAL_CHARACTERS
                                : ", which are letters, digits, a hyphen and "
                                        + NATIONAL_CHARACTERS));
            }
        }
    }

    /** Validates a relative-generation suffix, the only parenthesised form a name may carry. */
    private static void requireGenerationSuffix(String suffix, String candidate) {
        boolean wellFormed = suffix.length() >= 4
                && suffix.charAt(0) == '('
                && (suffix.charAt(1) == '+' || suffix.charAt(1) == '-')
                && suffix.charAt(suffix.length() - 1) == ')';
        if (wellFormed) {
            for (int position = 2; position < suffix.length() - 1; position++) {
                char digit = suffix.charAt(position);
                if (digit < '0' || digit > '9') {
                    wellFormed = false;
                    break;
                }
            }
        }
        if (!wellFormed) {
            throw new IllegalArgumentException(rejection(candidate) + "the parenthesised suffix is not "
                    + "a relative generation, which is the only such form a dataset name may carry - "
                    + "'(+1)' and '(-1)' are well formed, anything else is not");
        }
    }

    /** Whether a character may begin a qualifier: a letter or a national character. */
    private static boolean isQualifierInitial(char character) {
        return isAsciiLetter(character) || NATIONAL_CHARACTERS.indexOf(character) >= 0;
    }

    /** Whether a character may continue a qualifier: the initials plus digits and a hyphen. */
    private static boolean isQualifierBody(char character) {
        return isQualifierInitial(character)
                || (character >= '0' && character <= '9')
                || character == '-';
    }

    /** Whether a character is an unaccented Latin letter, upper or lower case. */
    private static boolean isAsciiLetter(char character) {
        return (character >= 'A' && character <= 'Z') || (character >= 'a' && character <= 'z');
    }

    /** The opening of every rejection message, which names the subject without quoting SQL back. */
    private static String rejection(String candidate) {
        return "The configured dataset name is not a well-formed z/OS dataset name, so it is refused "
                + "rather than composed into a SQL identifier: of the " + candidate.length()
                + " character(s) supplied, ";
    }

    /**
     * Renders text as a SQL delimited identifier, doubling any quotation mark within it.
     *
     * <p>Doubling is unreachable for a name that has passed {@link #requireDatasetName(String)}, since
     * the grammar admits no quotation mark. It is written anyway because this is the module's only
     * identifier renderer and it must be correct for whatever is handed to it, rather than correct only
     * for the inputs one caller happens to supply.
     *
     * @param text the identifier text
     * @return the delimited identifier
     * @throws NullPointerException if {@code text} is {@code null}
     */
    public static String delimit(String text) {
        Objects.requireNonNull(text, "Identifier text is required to render a delimited identifier");
        return IDENTIFIER_QUOTE + text.replace(IDENTIFIER_QUOTE, IDENTIFIER_QUOTE + IDENTIFIER_QUOTE)
                + IDENTIFIER_QUOTE;
    }

    /**
     * Reads the record-image column's name out of a described result set's metadata.
     *
     * <p>Static and side-effect free, so the repository that owns the connection performs the round trip
     * - through whatever template or callback it already uses - while the rule about <em>which</em>
     * column is the record image stays here, in one place, rather than being restated at each repository.
     *
     * @param metaData the metadata of a described result set, possibly {@code null} if the driver
     *                 supplied none
     * @return the column name at ordinal {@value #RECORD_IMAGE_COLUMN_INDEX}, or {@code null} when the
     *         relation has no column there
     * @throws SQLException if the driver fails while describing
     */
    public static String recordImageColumnOf(ResultSetMetaData metaData) throws SQLException {
        if (metaData == null || metaData.getColumnCount() < RECORD_IMAGE_COLUMN_INDEX) {
            return null;
        }
        return metaData.getColumnName(RECORD_IMAGE_COLUMN_INDEX);
    }

    /**
     * Validates a discovered record-image column name, caches it, and returns it.
     *
     * <p>Caching here rather than in the repository is what makes "discover once" a property of the
     * relation instead of a habit each repository has to remember, and it is why
     * {@link #recordImageColumn()} can answer without another round trip.
     *
     * @param candidate the name the backend described, or {@code null} if it described none
     * @return the validated name
     * @throws IllegalStateException if the name is absent, blank or carries a control character
     */
    public String rememberRecordImageColumn(String candidate) {
        String usable = requireUsableColumnName(candidate);
        recordImageColumn = usable;
        return usable;
    }

    /**
     * The record-image column's name, if it has been discovered.
     *
     * @return the name, or an empty {@link Optional} while the relation has not been described
     */
    public Optional<String> recordImageColumn() {
        return Optional.ofNullable(recordImageColumn);
    }

    /**
     * Forgets the discovered column name, as a {@code CLOSE} forgets everything it learned by opening.
     */
    public void forgetRecordImageColumn() {
        recordImageColumn = null;
    }

    /** Validates a discovered column name before it is composed into a statement. */
    private String requireUsableColumnName(String candidate) {
        if (candidate == null || candidate.isBlank()) {
            throw new IllegalStateException("The backend describes the column at position "
                    + RECORD_IMAGE_COLUMN_INDEX + " of dataset '" + dsname + "' with "
                    + (candidate == null ? "no name" : "a blank name")
                    + ", so it cannot be named in an ORDER BY or a WHERE clause");
        }
        for (int position = 0; position < candidate.length(); position++) {
            if (Character.isISOControl(candidate.charAt(position))) {
                throw new IllegalStateException("The column name the backend describes at position "
                        + RECORD_IMAGE_COLUMN_INDEX + " of dataset '" + dsname + "' contains a control "
                        + "character at 0-based position " + position
                        + ", so it is refused rather than composed into a statement");
            }
        }
        return candidate;
    }

    /**
     * The metadata probe: a statement that names the relation and returns no row.
     *
     * <p>A describe rather than a read - the predicate is false on every row, which every dialect
     * accepts - so it costs one round trip, transfers nothing, and still fails when the dataset is
     * absent, which is exactly what a COBOL {@code OPEN} reports.
     *
     * @return {@code SELECT * FROM <relation> WHERE 1 = 0}
     */
    public String describeStatement() {
        return "SELECT * FROM " + identifier + " WHERE 1 = 0";
    }

    /**
     * The whole relation with <strong>no order contract at all</strong>.
     *
     * <p>Reserved for a read whose outcome does not depend on the order rows arrive in, and there is
     * exactly one of those in this module: the security-user browse, which scans the whole relation and
     * selects the least or greatest admissible key <em>in Java</em>, by unsigned byte comparison in the
     * dataset code page, because {@code SEC-USR-ID} is the estate's only alphanumeric key and a backend
     * collation would not agree with the code page's byte order there.
     *
     * <p>It is <strong>not</strong> the sequential read of a physical-sequential dataset. SQL guarantees
     * no row order without an {@code ORDER BY}, so a physical-sequential read composed from this would
     * arrive in whatever order the backend scanned, and every consumer of {@code DALYTRAN}, of the sorted
     * daily file and of the sorted statement file depends on the order the records were written in. Those
     * reads use {@link #selectAllInPhysicalSequence(PhysicalSequence)}, and a keyed browse - the opposite
     * case, ordered by key - uses {@link #selectAllAscending(String)}.
     *
     * @return the unordered select over the whole relation
     */
    public String selectAll() {
        return "SELECT * FROM " + identifier;
    }

    /**
     * The whole relation in <strong>physical-record order</strong> - the sequential read of a
     * physical-sequential dataset.
     *
     * <p>A physical-sequential file has no key: its records are in the order they were written and a
     * COBOL {@code READ} returns them in that order. What stands for that order over JDBC is the ordinal
     * the deployment's driver presents for a record's position, which is what {@link PhysicalSequence}
     * carries - and it is deliberately not the record image, because ordering by the image orders by the
     * record's leading bytes and those are a different order from the file's. {@code app/jcl/TRANREPT.jcl:46}
     * sorts the daily file {@code SORT FIELDS=(TRAN-CARD-NUM,A)} while its record image begins with
     * {@code TRAN-ID}, and {@code CBTRN03C} subtotals by account as the records arrive
     * ({@code app/cbl/CBTRN03C.cbl:168-207}), so an image ordering would produce a report with the right
     * rows and the wrong totals.
     *
     * <p>The ordinal is rendered as {@link PhysicalSequence} validated it - a bare identifier, unquoted -
     * because a pseudo-column cannot be delimited. See that type for why the grammar rather than the
     * quoting is what makes it safe.
     *
     * @param sequence the deployment's physical-record ordinal; must not be {@code null}
     * @return the ordered select over the whole relation
     * @throws NullPointerException if {@code sequence} is {@code null}
     */
    public String selectAllInPhysicalSequence(PhysicalSequence sequence) {
        Objects.requireNonNull(sequence, "A physical-record ordinal is required to read a "
                + "physical-sequential dataset: SQL guarantees no row order without an ORDER BY, and the "
                + "order records were written in is what a sequential READ returns. It is configured by "
                + PhysicalSequence.EXPRESSION_PROPERTY + ".");
        return "SELECT * FROM " + identifier + sequence.orderByClause();
    }

    /**
     * The whole relation in ascending record-image order - the sequential browse a COBOL
     * {@code READ NEXT} performs.
     *
     * <p>The ordering is explicit and never left to the backend's scan order, because a job's output
     * sequence and, in the interest calculator's case, its per-account accumulation both depend on
     * ascending key order. Ordering by the record image orders by the key, since the key is a prefix of
     * the image in every keyed dataset this module reads.
     *
     * @param recordImageColumnName the discovered column name
     * @return the browse statement
     */
    public String selectAllAscending(String recordImageColumnName) {
        return "SELECT * FROM " + identifier + orderBy(recordImageColumnName, true);
    }

    /**
     * The rows strictly after a given record image, in ascending order - {@code READNEXT} from a
     * position.
     *
     * <p>An <strong>unreadable</strong> row - one whose record-image column holds nothing - qualifies as
     * well, for the reason set out on {@link #unreadableRowsVisible(String)}: a browse that cannot see it
     * skips it silently, and a skipped record is the one outcome a sequential read must never produce.
     *
     * @param recordImageColumnName the discovered column name
     * @return the forward-browse statement, taking the previous whole image as its parameter
     */
    public String selectAfterAscending(String recordImageColumnName) {
        return "SELECT * FROM " + identifier + " WHERE (" + delimit(recordImageColumnName) + " > ?"
                + unreadableRowsVisible(recordImageColumnName) + ")"
                + orderBy(recordImageColumnName, true);
    }

    /**
     * The rows strictly before a given record image, in descending order - {@code READPREV}.
     *
     * <p>An unreadable row qualifies here too - see {@link #unreadableRowsVisible(String)}.
     *
     * @param recordImageColumnName the discovered column name
     * @return the backward-browse statement, taking the previous whole image as its parameter
     */
    public String selectBeforeDescending(String recordImageColumnName) {
        return "SELECT * FROM " + identifier + " WHERE (" + delimit(recordImageColumnName) + " < ?"
                + unreadableRowsVisible(recordImageColumnName) + ")"
                + orderBy(recordImageColumnName, false);
    }

    /**
     * The rows at or after a key, in ascending order - the {@code STARTBR} with {@code GTEQ}.
     *
     * <p>The comparison is against the key image rather than a whole record image, which is sound for
     * "at or after" precisely because the key is a prefix: a record whose key equals the supplied key
     * compares greater than or equal to it, and one whose key is greater compares greater.
     *
     * <p>An unreadable row qualifies as well - see {@link #unreadableRowsVisible(String)}. It has no key
     * to compare, so "at or after" cannot decide about it, and a positioning read that excluded it would
     * begin a pass that walks straight past a record.
     *
     * @param recordImageColumnName the discovered column name
     * @return the positioning statement, taking the key image as its parameter
     */
    public String selectFromKeyAscending(String recordImageColumnName) {
        return "SELECT * FROM " + identifier + " WHERE (" + delimit(recordImageColumnName) + " >= ?"
                + unreadableRowsVisible(recordImageColumnName) + ")"
                + orderBy(recordImageColumnName, true);
    }

    /**
     * The rows whose record-image column holds nothing: the rows a browse cannot decode and must not
     * skip.
     *
     * <p>Used where an absence has to be <em>proved</em> rather than assumed. A keyed read whose predicate
     * matched no row can only report {@code NOTFND} if no row of the relation is unreadable: the key of
     * every record in this module lives <em>inside</em> the record image, so a row with no image has no
     * knowable key, and reporting "no such record" while one is sitting there unreadable states something
     * the data does not support. That is the condition {@code app/cbl/CBACT02C.cbl:101} lands on -
     * {@code MOVE 12 TO APPL-RESULT}, then {@code 'ERROR READING CARDFILE'} - and it is emphatically not
     * {@code NOTFND}.
     *
     * @param recordImageColumnName the discovered column name
     * @return the statement selecting only the unreadable rows
     */
    public String selectUnreadableRows(String recordImageColumnName) {
        return "SELECT * FROM " + identifier + " WHERE " + delimit(recordImageColumnName) + " IS NULL";
    }

    /**
     * The rows whose key span equals a key - the keyed read.
     *
     * <p>The predicate is in the <strong>statement</strong>, not in the caller. That is the whole point:
     * a keyed read that fetched every row and then compared keys in Java would touch, decode and
     * width-validate records that have nothing to do with the key, so one malformed row anywhere in the
     * dataset would fail an unrelated read - and on a real dataset it would also transfer the entire
     * file to answer a single-record question.
     *
     * <p>The parameter the statement takes is a {@link KeySpan#pattern(String)} of the dataset's key
     * span, which is what confines the match to the key bytes.
     *
     * @param recordImageColumnName the discovered column name
     * @return the keyed-read statement, taking the {@code LIKE} pattern as its parameter
     */
    public String selectByKey(String recordImageColumnName) {
        return "SELECT * FROM " + identifier + keyedPredicate(recordImageColumnName)
                + orderBy(recordImageColumnName, true);
    }

    /**
     * The keyed read with a row lock requested - the CICS {@code READ ... UPDATE} equivalent.
     *
     * <p>{@code FOR UPDATE} is the SQL request for the lock that {@code UPDATEMODEL(LOCKING)} gives a
     * CICS read-for-update. It is only meaningful inside a transaction: a lock taken outside one is
     * released at once, which would leave the read-compare-rewrite sequence it exists to protect exactly
     * as exposed as an unlocked read. Callers are therefore required to have a transaction open, and say
     * so loudly when they do not, rather than issuing a statement whose guarantee is vacuous.
     *
     * @param recordImageColumnName the discovered column name
     * @return the locking keyed-read statement, taking the {@code LIKE} pattern as its parameter
     */
    public String selectByKeyForUpdate(String recordImageColumnName) {
        return selectByKey(recordImageColumnName) + " FOR UPDATE";
    }

    /**
     * Replaces the record image of the rows a key selects - the {@code REWRITE}.
     *
     * @param recordImageColumnName the discovered column name
     * @return the rewrite statement, taking the new image then the {@code LIKE} pattern
     */
    public String rewriteByKey(String recordImageColumnName) {
        return "UPDATE " + identifier + " SET " + delimit(recordImageColumnName) + " = ?"
                + keyedPredicate(recordImageColumnName);
    }

    /**
     * Adds a record image - the {@code WRITE} to a sequential output.
     *
     * @param recordImageColumnName the discovered column name
     * @return the insert statement, taking the record image as its only parameter
     */
    public String insertRecordImage(String recordImageColumnName) {
        return "INSERT INTO " + identifier + " (" + delimit(recordImageColumnName) + ") VALUES (?)";
    }

    /**
     * Adds a record image without naming a column - the {@code WRITE} to an output-only
     * physical-sequential dataset.
     *
     * <p>Two facts make the column list impossible rather than merely optional here, and both are
     * properties of the dataset rather than preferences of the caller:
     * <ul>
     *   <li>An output-only dataset is <strong>never described</strong>. {@code OPEN OUTPUT} issues no
     *       read, so there is no result set whose {@link ResultSetMetaData} could report the
     *       record-image column's name, and inventing a round trip purely to learn a name would add an
     *       access this migration's COBOL never performs.</li>
     *   <li>Naming a column would be declaring a schema, and this migration declares none - no
     *       data-definition statement, no entity mapping, no version column (gate G44).</li>
     * </ul>
     *
     * <p>The record is therefore bound positionally, as the single value of a one-column relation,
     * which is the same record-image model {@value #RECORD_IMAGE_COLUMN_INDEX} expresses on the read
     * side. The identifier is rendered by exactly the same {@link #delimit(String)} as every other
     * statement form on this class, so a dotted dataset name is one delimited object here and one
     * delimited object everywhere else: a deployment's driver sees a single, consistent syntax rather
     * than one shape per writer.
     *
     * @return {@code INSERT INTO <relation> VALUES (?)}, taking the record image as its only parameter
     */
    public String insertRecordImage() {
        return "INSERT INTO " + identifier + " VALUES (?)";
    }

    /**
     * How many records the relation holds.
     *
     * <p>Needed for one purpose, and it is worth naming so this is not mistaken for a general-purpose
     * count: a dataset a step allocates {@code NEW} and whose DD statement carries an abnormal
     * disposition of {@code DELETE} must be discarded when the step abends, and a discard is only safe
     * once the relation has been shown to hold nothing but what this run wrote. It takes no column name,
     * which matters because an output-only relation created {@code NEW} is never described and has no
     * column name to be had.
     *
     * @return {@code SELECT COUNT(*) FROM <relation>}
     */
    public String countAllStatement() {
        return "SELECT COUNT(*) FROM " + identifier;
    }

    /**
     * Removes every record the relation holds - the Java form of a DD statement's {@code DELETE}
     * abnormal disposition.
     *
     * <p>{@code DISP=(NEW,CATLG,DELETE)} names three things: the dataset is created by the step, is
     * catalogued if the step ends normally, and is <em>deleted</em> if it does not. The deletion is of the
     * whole dataset, not of selected records, which is why this statement carries no predicate - and it is
     * only equivalent to the mainframe's behaviour where the relation holds exactly one allocation's
     * records. A caller must establish that with {@link #countAllStatement()} before issuing this, and
     * refuse the discard rather than issue it when the counts disagree.
     *
     * @return {@code DELETE FROM <relation>}
     */
    public String deleteAllStatement() {
        return "DELETE FROM " + identifier;
    }

    /**
     * Empties the relation - what {@code DISP=(NEW,CATLG,DELETE)} means for a dataset whose relation
     * already exists.
     *
     * <p>Every output dataset in this estate is created {@code NEW} by its JCL: {@code SYSTRAN(+1)} at
     * {@code app/jcl/INTCALC.jcl:L37-L41}, {@code DALYREJS(+1)} at {@code app/jcl/POSTTRAN.jcl:L34-L38},
     * {@code TRANREPT(+1)} at {@code app/jcl/TRANREPT.jcl:L76-L80}, and the statement pair at
     * {@code app/jcl/CREASTMT.JCL:L87-L96}. A run therefore writes into an <em>empty</em> generation,
     * and whatever a previous run produced is not part of it.
     *
     * <p>This is the one statement form that expresses that, and it is deliberately a delete of rows
     * rather than anything that touches the relation's definition: no {@code DROP}, no
     * {@code TRUNCATE}, no {@code CREATE} - this migration issues no data-definition statement at all
     * (gate G44). Emptying an already-empty relation removes nothing and is not a failure, exactly as
     * {@code app/jcl/CREASTMT.JCL:L28} follows its deletes with {@code SET MAXCC = 0} because on a first
     * run there is nothing there to delete.
     *
     * <p>No column is named and no predicate is composed, so nothing external reaches the statement
     * text beyond the dataset name this class has already validated as a z/OS dataset name and
     * delimited.
     *
     * @return {@code DELETE FROM <relation>}
     */
    public String deleteAll() {
        return "DELETE FROM " + identifier;
    }

    /**
     * The disjunct that keeps an <strong>unreadable</strong> row inside a positioning or advancing read's
     * candidate set.
     *
     * <h2>Why a comparison alone is not enough</h2>
     * <p>Every browse in this module orders and positions on the record-image column, because the key is a
     * prefix of the image. SQL evaluates every comparison against a null as {@code UNKNOWN}, so a row whose
     * record-image column holds nothing satisfies neither {@code >}, {@code >=} nor {@code <}: it is
     * <em>invisible to the predicate</em>. A browse composed of comparisons alone therefore walks straight
     * past it and reports the records either side, and the caller cannot tell that anything is missing.
     *
     * <p>That is silent data loss, and it is the one outcome a COBOL sequential read cannot produce. A row
     * that exists and cannot be read is an I/O failure: {@code app/cbl/CBACT02C.cbl:94-101} tests
     * {@code '00'} then {@code '10'} and moves {@code 12} into {@code APPL-RESULT} for anything else,
     * reaching {@code DISPLAY 'ERROR READING CARDFILE'} at {@code :110} and abending at {@code :113}. Every
     * repository in this module already classifies a row with no image onto exactly that arm - the arm was
     * simply unreachable while the predicate hid the row.
     *
     * <p>Spelled as {@code OR <column> IS NULL}: core SQL, understood identically by every backend, and
     * deliberately not a {@code SUBSTRING} over the key span, whose spelling differs by dialect and whose
     * result would be null for the same rows anyway.
     *
     * <h2>Where this is deliberately NOT applied</h2>
     * <ul>
     *   <li>{@link #keyedPredicate(String)}, and therefore {@link #selectByKey(String)},
     *       {@link #selectByKeyForUpdate(String)} and {@link #rewriteByKey(String)}. A keyed operation
     *       names one record. Widening its predicate would make every read of a relation that holds one
     *       unreadable row fail, which VSAM does not do - a corrupt record does not break reads of other
     *       keys - and widening the {@code REWRITE} would let an {@code UPDATE} overwrite the very row
     *       whose contents nothing could establish. A keyed read proves an absence instead through
     *       {@link #selectUnreadableRows(String)}, which changes the answer only where {@code NOTFND}
     *       would otherwise be a claim the data cannot support.</li>
     *   <li>{@link #selectAll()} and {@link #selectAllAscending(String)}, which carry no predicate and so
     *       already see every row, unreadable ones included.</li>
     * </ul>
     *
     * @param recordImageColumnName the discovered column name
     * @return the {@code OR ... IS NULL} disjunct, ready to close inside the caller's parentheses
     */
    private String unreadableRowsVisible(String recordImageColumnName) {
        return " OR " + delimit(recordImageColumnName) + " IS NULL";
    }

    /** The keyed predicate: an escaped {@code LIKE} over the record image. */
    private String keyedPredicate(String recordImageColumnName) {
        return " WHERE " + delimit(recordImageColumnName) + " LIKE ? ESCAPE '" + LIKE_ESCAPE + "'";
    }

    /** The explicit ordering clause, ascending or descending, over the record image. */
    private String orderBy(String recordImageColumnName, boolean ascending) {
        return " ORDER BY " + delimit(recordImageColumnName) + (ascending ? " ASC" : " DESC");
    }

    /**
     * A key's position and width inside the record image, which is how every key in this module is
     * addressed.
     *
     * <p>An offset and a length rather than a column name, because the key is part of the record image
     * and not a column of anything: {@code XREF-ACCT-ID} lives at offset 25 for 11 bytes of
     * {@code CVACT03Y}'s 50, and an alternate index over it is a second access path to the same bytes
     * rather than a second relation. The {@code LIKE} pattern this produces is core SQL, which matters
     * because the substring functions that would express the same predicate more directly are spelled
     * differently by every backend, and the deployment's backend is not known here.
     *
     * @param offset the 0-based byte offset of the key within the record image
     * @param length the key width in bytes
     */
    public record KeySpan(int offset, int length) {

        /**
         * @throws IllegalArgumentException if the offset is negative or the length is below 1
         */
        public KeySpan {
            if (offset < 0) {
                throw new IllegalArgumentException("A key span's offset is " + offset
                        + "; offsets into a record image are absolute and 0-based");
            }
            if (length < 1) {
                throw new IllegalArgumentException("A key span's length is " + length
                        + "; a key occupies at least 1 byte");
            }
        }

        /**
         * The {@code LIKE} pattern matching a record whose key span equals {@code keyImage}.
         *
         * <p>{@code offset} single-character wildcards skip the bytes before the key, the key itself
         * follows with every {@code LIKE} metacharacter escaped, and a trailing any-sequence wildcard
         * covers the remainder of the record. The key is escaped rather than trusted because an online
         * {@code RIDFLD} is a character field carrying whatever the screen supplied: an unescaped
         * {@code %} in it would match records the key does not name, and the {@code REWRITE} that uses
         * the same predicate would then overwrite them.
         *
         * @param keyImage the key exactly as wide as this span
         * @return the escaped pattern
         * @throws NullPointerException     if {@code keyImage} is {@code null}
         * @throws IllegalArgumentException if {@code keyImage} is not exactly {@link #length()} characters
         */
        public String pattern(String keyImage) {
            Objects.requireNonNull(keyImage, "A key image is required to compose a keyed predicate");
            if (keyImage.length() != length) {
                throw new IllegalArgumentException("The key image is " + keyImage.length()
                        + " character(s) where the key span is " + length + "; a keyed predicate is "
                        + "composed from a key of exactly its declared width, so that a short key "
                        + "cannot silently become a prefix match over more records than it names");
            }
            StringBuilder pattern = new StringBuilder(offset + keyImage.length() + 2);
            pattern.append(String.valueOf(LIKE_ANY_CHARACTER).repeat(offset));
            for (int position = 0; position < keyImage.length(); position++) {
                char character = keyImage.charAt(position);
                if (character == LIKE_ANY_SEQUENCE || character == LIKE_ANY_CHARACTER
                        || character == LIKE_ESCAPE) {
                    pattern.append(LIKE_ESCAPE);
                }
                pattern.append(character);
            }
            return pattern.append(LIKE_ANY_SEQUENCE).toString();
        }
    }

    /**
     * What the backend actually said when it refused an operation.
     *
     * <h2>Why the driver's own words are carried</h2>
     * <p>A refusal has to reach a job's log and an operator's screen as something diagnosable. Reducing
     * it to a single synthesized status - or classifying it by the exception class a framework wrapped
     * it in - discards the only information that distinguishes "the dataset is not there" from "the
     * credentials are wrong" from "the connection dropped", and those three need three different
     * responses from whoever is on call. So the {@code SQLSTATE}, the vendor code and the exception type
     * travel to the caller exactly as reported.
     *
     * <h2>Classification is by SQLSTATE class</h2>
     * <p>The two-character prefix of a {@code SQLSTATE} is defined by the SQL standard and is the same
     * across drivers: {@code 08} is a connection exception, {@code 23} an integrity-constraint
     * violation, {@code 42} a syntax error or access-rule violation, {@code 40} a transaction rollback,
     * {@code 22} a data exception. A framework's exception hierarchy is a framework's opinion about
     * those codes and is not part of any driver's contract, so it is not what decides anything here.
     *
     * <p>A vendor code is a <strong>vendor's</strong> number. It is reported as {@link #vendorCode()}
     * and is never presented as a CICS {@code RESP2}: those are quantities from two different systems,
     * and putting one where the other belongs produces a diagnostic that looks authoritative and means
     * nothing.
     *
     * <h2>What is deliberately NOT carried: the driver's message text</h2>
     * <p>The three values above are <em>codes</em>. A driver's message text is prose, and prose composed
     * by the backend around the values it refused - which is to say around a record image. A rejected
     * card operation's message can read {@code value '4444333322221111' rejected}, so a diagnostic that
     * carried the text would put a primary account number into every log line, exception message and
     * assertion failure that rendered it (CWE-532), and into a log line that a control character in the
     * same text could split in two (CWE-117). This record once carried it, and being a {@code record}
     * meant the generated {@link #toString()} published it the first time anything rendered a refusal.
     *
     * <p>So the text is dropped where the diagnostic is read, not merely omitted from
     * {@link #describe()}: there is no accessor, no component and nothing for a future call site to
     * reach for. What survives - {@code SQLSTATE}, vendor code, exception type - is what distinguishes
     * "the dataset is not there" from "the credentials are wrong" from "the connection dropped", which
     * is the whole of what an operator needs and none of what a cardholder would object to. A deployment
     * that needs the driver's own words has them in the driver's own log, which is access-controlled as
     * an application log is not.
     *
     * @param sqlState     the {@code SQLSTATE} the driver reported, or {@code null} if it reported none
     * @param vendorCode   the driver's own error number, or {@code 0} if it reported none
     * @param exceptionType the fully qualified type of the exception the driver or framework raised
     */
    public record BackendDiagnostic(String sqlState,
                                    int vendorCode,
                                    String exceptionType) {

        /** The {@code SQLSTATE} class of a connection exception. */
        public static final String CONNECTION_EXCEPTION_CLASS = "08";

        /** The {@code SQLSTATE} class of a data exception. */
        public static final String DATA_EXCEPTION_CLASS = "22";

        /** The {@code SQLSTATE} class of an integrity-constraint violation. */
        public static final String INTEGRITY_VIOLATION_CLASS = "23";

        /** The {@code SQLSTATE} class of a transaction rollback. */
        public static final String TRANSACTION_ROLLBACK_CLASS = "40";

        /** The {@code SQLSTATE} class of a syntax error or access-rule violation. */
        public static final String SYNTAX_OR_ACCESS_CLASS = "42";

        /**
         * @throws NullPointerException if {@code exceptionType} is {@code null}
         */
        public BackendDiagnostic {
            Objects.requireNonNull(exceptionType, "The exception type is required: a diagnostic that "
                    + "cannot say what was raised is not a diagnostic");
        }

        /**
         * Reads a diagnostic out of a thrown failure, walking the cause chain for the {@link SQLException}
         * the driver raised.
         *
         * <p>The chain is walked because a framework's data-access exception is a wrapper: the
         * {@code SQLSTATE} and the vendor code live on the {@link SQLException} inside it, and taking the
         * wrapper's own type as the diagnosis is what loses them. The wrapper's type is still reported,
         * because knowing which layer refused is useful, but it is reported <em>as well as</em> the
         * driver's own words rather than instead of them.
         *
         * <p>The failure's message is read from neither the wrapper nor the {@link SQLException}: see the
         * note on this record about why the driver's message text is not carried at all.
         *
         * @param failure the exception raised
         * @return the diagnostic, with a {@code null} {@code SQLSTATE} when no {@link SQLException} is in
         *         the chain
         * @throws NullPointerException if {@code failure} is {@code null}
         */
        public static BackendDiagnostic of(Throwable failure) {
            Objects.requireNonNull(failure, "A failure is required to read a backend diagnostic from");
            SQLException reported = firstSqlException(failure);
            return new BackendDiagnostic(
                    reported == null ? null : reported.getSQLState(),
                    reported == null ? 0 : reported.getErrorCode(),
                    failure.getClass().getName());
        }

        /** The first {@link SQLException} in a cause chain, or {@code null} when there is none. */
        private static SQLException firstSqlException(Throwable failure) {
            Throwable candidate = failure;
            while (candidate != null) {
                if (candidate instanceof SQLException sqlException) {
                    return sqlException;
                }
                candidate = candidate.getCause();
            }
            return null;
        }

        /**
         * The {@code SQLSTATE} class - its first two characters - or empty when none was reported.
         *
         * @return the two-character class, or an empty {@link Optional}
         */
        public Optional<String> sqlStateClass() {
            return sqlState == null || sqlState.length() < 2
                    ? Optional.empty()
                    : Optional.of(sqlState.substring(0, 2));
        }

        /**
         * Whether the reported {@code SQLSTATE} belongs to a given class.
         *
         * @param sqlStateClassPrefix the two-character class
         * @return {@code true} when a {@code SQLSTATE} was reported and starts with that class
         * @throws NullPointerException if {@code sqlStateClassPrefix} is {@code null}
         */
        public boolean isClass(String sqlStateClassPrefix) {
            Objects.requireNonNull(sqlStateClassPrefix, "A SQLSTATE class is required to test against");
            return sqlStateClass().map(sqlStateClassPrefix::equals).orElse(false);
        }

        /**
         * Whether the backend could not be reached or the session failed.
         *
         * @return {@code true} for {@code SQLSTATE} class {@code 08}
         */
        public boolean connectionFailure() {
            return isClass(CONNECTION_EXCEPTION_CLASS);
        }

        /**
         * Whether the operation violated an integrity constraint, which is how a backend reports the
         * duplicate a COBOL {@code WRITE} sees as a duplicate key.
         *
         * @return {@code true} for {@code SQLSTATE} class {@code 23}
         */
        public boolean integrityViolation() {
            return isClass(INTEGRITY_VIOLATION_CLASS);
        }

        /**
         * Whether the statement was rejected as malformed or refused by an access rule, which is what a
         * backend reports when the relation does not exist or the credentials do not reach it.
         *
         * @return {@code true} for {@code SQLSTATE} class {@code 42}
         */
        public boolean syntaxOrAccessViolation() {
            return isClass(SYNTAX_OR_ACCESS_CLASS);
        }

        /**
         * Whether the transaction was rolled back beneath the caller - a deadlock or a serialisation
         * failure.
         *
         * @return {@code true} for {@code SQLSTATE} class {@code 40}
         */
        public boolean transactionRollback() {
            return isClass(TRANSACTION_ROLLBACK_CLASS);
        }

        /**
         * Whether the data itself was rejected - a value out of range or a conversion the backend
         * refused.
         *
         * @return {@code true} for {@code SQLSTATE} class {@code 22}
         */
        public boolean dataException() {
            return isClass(DATA_EXCEPTION_CLASS);
        }

        /**
         * A single line naming everything the backend reported, for a log or an operator message.
         *
         * <p>It quotes the driver's {@code SQLSTATE}, vendor code and exception type and nothing else -
         * in particular no record content, since a failing operation's record may carry a card number or
         * a government identifier.
         *
         * <p>The line distinguishes the two cases {@link #of(Throwable)} can produce, because they are
         * not the same fault and reading them as one sends an operator to the wrong place. When an
         * {@link SQLException} was found in the cause chain the backend genuinely refused the operation
         * and reported a {@code SQLSTATE}. When none was found - which is what {@code sqlState} being
         * {@code null} means - nothing was asked of the backend at all: the failure arose in this
         * module, above the driver. Calling that "backend refusal: SQLSTATE not reported" reads as a
         * backend that answered without saying why, and it was the misdiagnosis a QA pass caught on an
         * unrepresentable screen value, where the transcoder refused the write before any statement was
         * prepared.
         *
         * @return the rendered diagnostic
         */
        public String describe() {
            if (sqlState == null) {
                return "no backend diagnostic: no SQLException in the cause chain, so the failure "
                        + "arose above the driver rather than in the backend"
                        + ", raised as " + exceptionType;
            }
            return "backend refusal: SQLSTATE " + sqlState
                    + ", vendor code " + vendorCode
                    + ", raised as " + exceptionType;
        }
    }
}
