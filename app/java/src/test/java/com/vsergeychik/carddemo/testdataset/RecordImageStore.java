package com.vsergeychik.carddemo.testdataset;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The rows behind a {@link RecordImageDataSource}: a map from dataset name to an ordered list of record
 * images, with no schema and therefore no DDL.
 *
 * <h2>The model</h2>
 * <p>A relation is one column wide and holds record images in the order they were written, which is the
 * whole of what a fixed-width dataset presented over JDBC offers. A {@code null} element models a row
 * that is <em>present but unreadable</em> - the {@code RECORD_IMAGE IS NULL} case every keyed reader must
 * prove absent before it reports {@code NOTFND}. Insertion order is the physical-record order that
 * {@code carddemo.physical-sequence.expression} names, so an {@code ORDER BY} over an expression that is
 * not the record-image column reads rows back in the order they were stored, exactly as {@code _ROWID_}
 * does on an in-memory relation.
 *
 * <h2>Widths are enforced, and that is behavioural</h2>
 * <p>A relation declares its width and refuses a longer image with {@code SQLSTATE 22001}, as a
 * {@code VARCHAR(n)} column does. Shorter images are stored short and are <strong>not</strong> padded:
 * several parity cases seed a row narrower than the copybook precisely to reach a program's fatal read
 * arm, and a store that padded would make those cases unreachable.
 *
 * <h2>Absence is an error, not an empty answer</h2>
 * <p>A statement naming a relation that was never declared fails with {@code SQLSTATE 42S02}, the
 * standard class-42 syntax-or-access state. That is what makes {@link #undefine(String)} a faithful
 * stand-in for dropping a relation: a suite that needs "the dataset is not there" to drive an open
 * failure gets a class-42 {@code SQLException}, which is the class {@code BackendDiagnostic} sorts on and
 * which Spring translates into a {@code DataAccessException} just as a real driver's would.
 *
 * <h2>Isolation</h2>
 * <p>Every mutating method is synchronized on this instance, and the row lists handed out are copies, so
 * a connection can never hold a live view of another connection's list. That is what lets
 * {@link RecordImageDataSource} give each transaction private copies of the relations it modifies and
 * publish those on commit, while every relation it has not modified is read as the store stands.
 */
public final class RecordImageStore {

    /** {@code SQLSTATE} for a relation that was never declared - class 42, syntax or access rule. */
    public static final String RELATION_NOT_FOUND_STATE = "42S02";

    /** {@code SQLSTATE} for an image wider than the relation - class 22, data exception. */
    public static final String VALUE_TOO_LONG_STATE = "22001";

    /** {@code SQLSTATE} for a duplicate image in a unique relation - class 23, integrity violation. */
    public static final String DUPLICATE_IMAGE_STATE = "23505";

    /** The vendor code reported alongside {@link #DUPLICATE_IMAGE_STATE}. */
    public static final int DUPLICATE_IMAGE_CODE = 23505;

    /** The vendor code reported alongside {@link #RELATION_NOT_FOUND_STATE}. */
    public static final int RELATION_NOT_FOUND_CODE = 42102;

    /** The vendor code reported alongside {@link #VALUE_TOO_LONG_STATE}. */
    public static final int VALUE_TOO_LONG_CODE = 22001;

    /** The declared relations, keyed by dataset name, in declaration order. */
    private final Map<String, Relation> relations = new LinkedHashMap<>();

    /** The relations that refuse a duplicate record image, standing in for a unique key. */
    private final java.util.Set<String> unique = new java.util.LinkedHashSet<>();

    /**
     * How a relation presents its record image, which decides how a row is read and bound.
     *
     * <p>The two forms are the two a deployment can offer, and they are deliberately not
     * interchangeable here: a character relation answers {@code getString} and a binary relation answers
     * {@code getBytes}, and asking the other way round fails loudly rather than guessing a code page.
     * {@code RecordImageForm} chooses one per dataset binding and never mixes them, so a test that mixes
     * them has mis-declared its relation.
     */
    public enum ColumnForm {

        /** A character column: {@code getString} reads it, {@code setString} binds it. */
        CHARACTER("VARCHAR", java.sql.Types.VARCHAR),

        /** A binary column: {@code getBytes} reads it, {@code setBytes} binds it. */
        BINARY("VARBINARY", java.sql.Types.VARBINARY);

        private final String typeName;

        private final int sqlType;

        ColumnForm(String typeName, int sqlType) {
            this.typeName = typeName;
            this.sqlType = sqlType;
        }

        /**
         * The type name this form reports through {@code ResultSetMetaData}.
         *
         * @return {@code VARCHAR} or {@code VARBINARY}
         */
        public String typeName() {
            return typeName;
        }

        /**
         * The {@link java.sql.Types} constant this form reports.
         *
         * @return the JDBC type code
         */
        public int sqlType() {
            return sqlType;
        }
    }

    /**
     * One declared relation: its column name, its form, its width and its rows.
     *
     * @param columnName the record-image column's name, reported at ordinal 1
     * @param form       how the image is presented
     * @param width      the declared image width, in characters or bytes
     * @param rows       the images, in write order; a {@code null} element is an unreadable row
     */
    record Relation(String columnName, ColumnForm form, int width, List<Object> rows) {

        Relation copy() {
            return new Relation(columnName, form, width, new ArrayList<>(rows));
        }
    }



    /**
     * Declares a relation whose record-image column carries the given name.
     *
     * <p>The name is a parameter because the repository <strong>discovers</strong> it through
     * {@code ResultSetMetaData} rather than assuming it, and several suites declare a name other than the
     * conventional one precisely to prove that discovery happens. A store that imposed one name would make
     * those suites assert nothing.
     *
     * @param dsname     the dataset name, which is also the relation's identifier
     * @param columnName the record-image column's name, as metadata will report it at ordinal 1
     * @param form       how the record image is presented
     * @param width      the record width; at least 1
     * @return this store, for chaining
     * @throws NullPointerException     if any argument is {@code null}
     * @throws IllegalArgumentException if {@code width} is less than 1
     */
    public synchronized RecordImageStore define(String dsname, String columnName, ColumnForm form,
            int width) {
        Objects.requireNonNull(dsname, "A relation is declared under a dataset name");
        Objects.requireNonNull(columnName, "A record-image column has a name, because the repository "
                + "discovers it from metadata rather than assuming it");
        Objects.requireNonNull(form, "A relation presents its record image in one declared form");
        if (width < 1) {
            throw new IllegalArgumentException("A record occupies at least 1 unit, so a relation "
                    + "declared " + width + " wide could hold nothing");
        }
        relations.put(dsname, new Relation(columnName, form, width, new ArrayList<>()));
        unique.remove(dsname);
        return this;
    }

    /**
     * Declares a character relation that refuses a duplicate record image.
     *
     * <p>The stand-in for a unique key on the record-image column, and it is a real behaviour rather than
     * a convenience: a copy into a destination that already holds a record has to fail <em>part way</em>,
     * leaving what it had already loaded, because {@code IDCAMS REPRO} is not atomic against a
     * {@code DISP=SHR} dataset. The refusal carries {@code SQLSTATE 23505} - class 23, integrity-constraint
     * violation - so Spring translates it to a {@code DataAccessException} exactly as a driver's unique-key
     * violation would be.
     *
     * @param dsname     the dataset name
     * @param columnName the record-image column's name
     * @param form       how the record image is presented
     * @param width      the record width
     * @return this store, for chaining
     */
    public synchronized RecordImageStore defineUnique(String dsname, String columnName, ColumnForm form,
            int width) {
        define(dsname, columnName, form, width);
        unique.add(dsname);
        return this;
    }

    /**
     * Withdraws a relation, so every statement naming it fails as an absent dataset would.
     *
     * <p>The {@code DROP TABLE} replacement. A suite proving an open failure, a read failure or a write
     * failure needs the dataset to be unreachable, and withdrawing the declaration is how that is
     * arranged without DDL. Withdrawing a name that was never declared is not an error - the end state
     * is the one the caller asked for.
     *
     * @param dsname the dataset name to withdraw
     * @return this store, for chaining
     * @throws NullPointerException if {@code dsname} is {@code null}
     */
    public synchronized RecordImageStore undefine(String dsname) {
        Objects.requireNonNull(dsname, "A relation is withdrawn by dataset name");
        relations.remove(dsname);
        unique.remove(dsname);
        return this;
    }

    /**
     * Appends record images to a declared relation, in the order given.
     *
     * <p>Order is preserved and never sorted. A keyed browse reads in key order whatever order the
     * records were loaded in, and parity cases exist to prove the translation does the same, so a seed
     * must be free to disagree with the read order.
     *
     * @param dsname the relation to seed
     * @param images the images to append; a {@code null} element seeds an unreadable row
     * @return this store, for chaining
     * @throws IllegalStateException    if the relation was not declared
     * @throws IllegalArgumentException if an image is wider than the relation
     */
    public synchronized RecordImageStore seed(String dsname, List<String> images) {
        Objects.requireNonNull(images, "A seed is a list of images, empty or not");
        Relation relation = declared(dsname);
        for (String image : images) {
            if (image != null && image.length() > relation.width()) {
                throw new IllegalArgumentException("Dataset '" + dsname + "' is declared "
                        + relation.width() + " wide and the seeded image is " + image.length()
                        + "; a relation refuses an over-long image rather than truncating it");
            }
            relation.rows().add(image);
        }
        return this;
    }

    /**
     * Appends one record image to a declared relation.
     *
     * @param dsname the relation to seed
     * @param image  the image to append; {@code null} seeds an unreadable row
     * @return this store, for chaining
     */
    public RecordImageStore seed(String dsname, String image) {
        return seed(dsname, java.util.Collections.singletonList(image));
    }


    /**
     * The character images a relation currently holds, in write order.
     *
     * <p>The read-back a test asserts on. An unreadable row appears as {@code null}, and nothing is
     * trimmed: trailing {@code FILLER} spaces are part of a record image.
     *
     * @param dsname the relation to read
     * @return the images, in write order; never {@code null}
     * @throws IllegalStateException if the relation was not declared
     */
    public synchronized List<String> rows(String dsname) {
        List<String> images = new ArrayList<>();
        for (Object row : declared(dsname).rows()) {
            images.add(row == null ? null : String.valueOf(row));
        }
        return images;
    }

    /**
     * The binary images a relation currently holds, in write order.
     *
     * @param dsname the relation to read
     * @return the images, in write order; never {@code null}
     * @throws IllegalStateException if the relation was not declared or is not binary
     */
    public synchronized List<byte[]> rowBytes(String dsname) {
        Relation relation = declared(dsname);
        List<byte[]> images = new ArrayList<>();
        for (Object row : relation.rows()) {
            if (row == null) {
                images.add(null);
            } else if (row instanceof byte[] bytes) {
                images.add(bytes.clone());
            } else {
                throw new IllegalStateException("Dataset '" + dsname + "' holds a character image, so "
                        + "its bytes depend on a code page this store does not choose; read it with "
                        + "rows(String) and encode it in the charset the binding declares");
            }
        }
        return images;
    }



    /**
     * Whether a relation refuses a duplicate record image.
     *
     * @param dsname the dataset name
     * @return {@code true} when it was declared with {@link #defineUnique}
     */
    synchronized boolean refusesDuplicates(String dsname) {
        return unique.contains(dsname);
    }

    /**
     * A snapshot of every declared relation, as the store stands.
     *
     * @return an independent copy
     */
    synchronized Map<String, Relation> snapshot() {
        Map<String, Relation> copy = new LinkedHashMap<>();
        relations.forEach((name, relation) -> copy.put(name, relation.copy()));
        return copy;
    }

    /**
     * Replaces every relation with a committed snapshot.
     *
     * <p>Callers compose that snapshot from {@link #snapshot()} taken at commit time overlaid with the
     * relations they modified, so a boundary that committed in the meantime is preserved rather than
     * overwritten by a stale copy.
     *
     * @param committed the snapshot to publish
     */
    synchronized void publish(Map<String, Relation> committed) {
        relations.clear();
        committed.forEach((name, relation) -> relations.put(name, relation.copy()));
    }

    /**
     * The relation under a name, or a class-42 failure when it was never declared.
     *
     * @param dsname the dataset name
     * @return the relation
     * @throws SQLException if the relation was not declared
     */
    static Relation require(Map<String, Relation> relations, String dsname) throws SQLException {
        Relation relation = relations.get(dsname);
        if (relation == null) {
            throw new SQLException("Relation \"" + dsname + "\" not found; it was never declared to "
                    + "this record-image store, which is how an absent dataset is modelled",
                    RELATION_NOT_FOUND_STATE, RELATION_NOT_FOUND_CODE);
        }
        return relation;
    }

    private Relation declared(String dsname) {
        Relation relation = relations.get(dsname);
        if (relation == null) {
            throw new IllegalStateException("Dataset '" + dsname + "' has not been declared to this "
                    + "store, so it holds no rows. Declare it with define(...) first; the declared "
                    + "relations are " + relations.keySet());
        }
        return relation;
    }
}
