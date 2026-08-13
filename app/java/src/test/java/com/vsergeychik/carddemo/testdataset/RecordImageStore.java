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
 */
public final class RecordImageStore {
    public static final String RELATION_NOT_FOUND_STATE = "42S02";

    public static final String VALUE_TOO_LONG_STATE = "22001";

    public static final String DUPLICATE_IMAGE_STATE = "23505";

    public static final int DUPLICATE_IMAGE_CODE = 23505;

    public static final int RELATION_NOT_FOUND_CODE = 42102;

    public static final int VALUE_TOO_LONG_CODE = 22001;

    private final Map<String, Relation> relations = new LinkedHashMap<>();

    private final java.util.Set<String> unique = new java.util.LinkedHashSet<>();

    public enum ColumnForm {
        CHARACTER("VARCHAR", java.sql.Types.VARCHAR),

        BINARY("VARBINARY", java.sql.Types.VARBINARY);

        private final String typeName;

        private final int sqlType;

        ColumnForm(String typeName, int sqlType) {
            this.typeName = typeName;
            this.sqlType = sqlType;
        }

        public String typeName() {
            return typeName;
        }

        public int sqlType() {
            return sqlType;
        }
    }

    record Relation(String columnName, ColumnForm form, int width, List<Object> rows) {
        Relation copy() {
            return new Relation(columnName, form, width, new ArrayList<>(rows));
        }
    }

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

    public synchronized RecordImageStore defineUnique(String dsname, String columnName, ColumnForm form,
            int width) {
        define(dsname, columnName, form, width);
        unique.add(dsname);
        return this;
    }

    public synchronized RecordImageStore undefine(String dsname) {
        Objects.requireNonNull(dsname, "A relation is withdrawn by dataset name");
        relations.remove(dsname);
        unique.remove(dsname);
        return this;
    }

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

    public RecordImageStore seed(String dsname, String image) {
        return seed(dsname, java.util.Collections.singletonList(image));
    }

    public synchronized List<String> rows(String dsname) {
        List<String> images = new ArrayList<>();
        for (Object row : declared(dsname).rows()) {
            images.add(row == null ? null : String.valueOf(row));
        }
        return images;
    }

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

    synchronized boolean refusesDuplicates(String dsname) {
        return unique.contains(dsname);
    }

    synchronized Map<String, Relation> snapshot() {
        Map<String, Relation> copy = new LinkedHashMap<>();
        relations.forEach((name, relation) -> copy.put(name, relation.copy()));
        return copy;
    }

    synchronized void publish(Map<String, Relation> committed) {
        relations.clear();
        committed.forEach((name, relation) -> relations.put(name, relation.copy()));
    }

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
