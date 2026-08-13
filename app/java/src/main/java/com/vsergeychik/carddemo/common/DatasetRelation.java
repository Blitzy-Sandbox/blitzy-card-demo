package com.vsergeychik.carddemo.common;

import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;

/**
 * The one contract by which this module reaches a mainframe dataset over JDBC: how a dataset name becomes a
 * SQL identifier, how a record is located within a relation, how a keyed, browsing or locking access path
 * is expressed, and how a backend refusal is read.
 *
 * <p>A driver's vendor code is a driver's number: it is reported as one, and it is never passed off as a
 * CICS {@code RESP2}, which is a different quantity from a different system.
 */
public final class DatasetRelation {
    public static final int RECORD_IMAGE_COLUMN_INDEX = 1;

    private static final String IDENTIFIER_QUOTE = "\"";

    private static final char LIKE_ESCAPE = '\\';

    private static final char LIKE_ANY_SEQUENCE = '%';

    private static final char LIKE_ANY_CHARACTER = '_';

    private static final int MAX_DATASET_NAME_LENGTH = 44;

    private static final int MAX_QUALIFIER_LENGTH = 8;

    private static final char QUALIFIER_SEPARATOR = '.';

    private static final String NATIONAL_CHARACTERS = "#@$";

    private final String dsname;

    private final String identifier;

    private final int recordLength;

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
     * @param dsname the configured dataset name, from a {@code carddemo.datasets.*} binding
     * @param recordLength the copybook-declared record width in bytes
     * @return the relation
     * @throws NullPointerException if {@code dsname} is {@code null}
     * @throws IllegalArgumentException if {@code dsname} is not a well-formed z/OS dataset name, or if
     *     {@code recordLength} is below 1
     */
    public static DatasetRelation of(String dsname, int recordLength) {
        return new DatasetRelation(dsname, recordLength);
    }

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
     * @param candidate the configured name
     * @return {@code candidate}, unchanged
     * @throws NullPointerException if {@code candidate} is {@code null}
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

    private static boolean isQualifierInitial(char character) {
        return isAsciiLetter(character) || NATIONAL_CHARACTERS.indexOf(character) >= 0;
    }

    private static boolean isQualifierBody(char character) {
        return isQualifierInitial(character)
                || (character >= '0' && character <= '9')
                || character == '-';
    }

    private static boolean isAsciiLetter(char character) {
        return (character >= 'A' && character <= 'Z') || (character >= 'a' && character <= 'z');
    }

    private static String rejection(String candidate) {
        return "The configured dataset name is not a well-formed z/OS dataset name, so it is refused "
                + "rather than composed into a SQL identifier: of the " + candidate.length()
                + " character(s) supplied, ";
    }

    /**
     * Renders text as a SQL delimited identifier, doubling any quotation mark within it.
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
     * @param metaData the metadata of a described result set, possibly {@code null} if the driver supplied
     *     none
     * @return the column name at ordinal {@value #RECORD_IMAGE_COLUMN_INDEX}, or {@code null} when the
     *     relation has no column there
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
     * <p>A describe rather than a read - the predicate is false on every row, which every dialect accepts -
     * so it costs one round trip, transfers nothing, and still fails when the dataset is absent, which is
     * exactly what a COBOL {@code OPEN} reports.
     *
     * @return {@code SELECT * FROM <relation> WHERE 1 = 0}
     */
    public String describeStatement() {
        return "SELECT * FROM " + identifier + " WHERE 1 = 0";
    }

    /**
     * The whole relation with no order contract at all.
     *
     * @return the unordered select over the whole relation
     */
    public String selectAll() {
        return "SELECT * FROM " + identifier;
    }

    /**
     * The whole relation in physical-record order - the sequential read of a physical-sequential dataset.
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
     * The whole relation in ascending record-image order - the sequential browse a COBOL {@code READ NEXT}
     * performs.
     *
     * @param recordImageColumnName the discovered column name
     * @return the browse statement
     */
    public String selectAllAscending(String recordImageColumnName) {
        return "SELECT * FROM " + identifier + orderBy(recordImageColumnName, true);
    }

    /**
     * The rows strictly after a given record image, in ascending order - {@code READNEXT} from a position.
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
     * @param recordImageColumnName the discovered column name
     * @return the positioning statement, taking the key image as its parameter
     */
    public String selectFromKeyAscending(String recordImageColumnName) {
        return "SELECT * FROM " + identifier + " WHERE (" + delimit(recordImageColumnName) + " >= ?"
                + unreadableRowsVisible(recordImageColumnName) + ")"
                + orderBy(recordImageColumnName, true);
    }

    /**
     * The rows whose record-image column holds nothing: the rows a browse cannot decode and must not skip.
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
     * Adds a record image without naming a column - the {@code WRITE} to an output-only physical-sequential
     * dataset.
     *
     * <p>{@code OPEN OUTPUT} issues no read, so there is no result set whose {@link ResultSetMetaData}
     * could report the record-image column's name, and inventing a round trip purely to learn a name would
     * add an access this migration's COBOL never performs.
     *
     * @return {@code INSERT INTO <relation> VALUES (?)}, taking the record image as its only parameter
     */
    public String insertRecordImage() {
        return "INSERT INTO " + identifier + " VALUES (?)";
    }

    public String countAllStatement() {
        return "SELECT COUNT(*) FROM " + identifier;
    }

    /**
     * Removes every record the relation holds - the Java form of a DD statement's {@code DELETE} abnormal
     * disposition.
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
     * <p>Emptying an already-empty relation removes nothing and is not a failure, exactly as
     * {@code app/jcl/CREASTMT.JCL:L28} follows its deletes with {@code SET MAXCC = 0} because on a first
     * run there is nothing there to delete.
     *
     * @return {@code DELETE FROM <relation>}
     */
    public String deleteAll() {
        return "DELETE FROM " + identifier;
    }

    private String unreadableRowsVisible(String recordImageColumnName) {
        return " OR " + delimit(recordImageColumnName) + " IS NULL";
    }

    private String keyedPredicate(String recordImageColumnName) {
        return " WHERE " + delimit(recordImageColumnName) + " LIKE ? ESCAPE '" + LIKE_ESCAPE + "'";
    }

    private String orderBy(String recordImageColumnName, boolean ascending) {
        return " ORDER BY " + delimit(recordImageColumnName) + (ascending ? " ASC" : " DESC");
    }

    /**
     * A key's position and width inside the record image, which is how every key in this module is
     * addressed.
     *
     * @param offset the 0-based byte offset of the key within the record image
     * @param length the key width in bytes
     */
    public record KeySpan(int offset, int length) {
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
         * @param keyImage the key exactly as wide as this span
         * @return the escaped pattern
         * @throws NullPointerException if {@code keyImage} is {@code null}
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
     * @param sqlState the {@code SQLSTATE} the driver reported, or {@code null} if it reported none
     * @param vendorCode the driver's own error number, or {@code 0} if it reported none
     * @param exceptionType the fully qualified type of the exception the driver or framework raised
     */
    public record BackendDiagnostic(String sqlState,
                                    int vendorCode,
                                    String exceptionType) {
        public static final String CONNECTION_EXCEPTION_CLASS = "08";

        public static final String DATA_EXCEPTION_CLASS = "22";

        public static final String INTEGRITY_VIOLATION_CLASS = "23";

        public static final String TRANSACTION_ROLLBACK_CLASS = "40";

        public static final String SYNTAX_OR_ACCESS_CLASS = "42";

        public BackendDiagnostic {
            Objects.requireNonNull(exceptionType, "The exception type is required: a diagnostic that "
                    + "cannot say what was raised is not a diagnostic");
        }

        /**
         * Reads a diagnostic out of a thrown failure, walking the cause chain for the {@link SQLException}
         * the driver raised.
         *
         * @param failure the exception raised
         * @return the diagnostic, with a {@code null} {@code SQLSTATE} when no {@link SQLException} is in
         *     the chain
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
         * Whether the data itself was rejected - a value out of range or a conversion the backend refused.
         *
         * @return {@code true} for {@code SQLSTATE} class {@code 22}
         */
        public boolean dataException() {
            return isClass(DATA_EXCEPTION_CLASS);
        }

        /**
         * A single line naming everything the backend reported, for a log or an operator message.
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
