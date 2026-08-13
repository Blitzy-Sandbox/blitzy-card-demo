package com.vsergeychik.carddemo.testdataset;

import java.sql.SQLException;
import java.util.Locale;

/**
 * The recogniser: turns one of the statement texts this module composes into a plan a
 * {@link RecordImageStore} can carry out, and <strong>refuses anything it does not recognise</strong>.
 *
 * <h2>Refusal is the safety property</h2>
 * <p>This is not a SQL parser and must never behave like one. Every statement the module sends is
 * composed by {@code DatasetRelation}, or by one of the three call sites that compose a whole-image
 * predicate of their own, so the set of shapes is closed and enumerable. If a composed statement changes,
 * this recogniser fails with the text it was handed rather than guessing - which is what makes it safe to
 * put a hand-written store under the parity gate. A permissive fallback would let a changed statement
 * return a plausible-but-different answer, and a parity case would then pass for the wrong reason.
 *
 * <h2>The closed grammar</h2>
 * <pre>
 *   SELECT * FROM r WHERE 1 = 0                                            describe
 *   SELECT * FROM r                                                        every row, unordered
 *   SELECT * FROM r ORDER BY expr ASC                                      every row, write order
 *   SELECT * FROM r ORDER BY c ASC|DESC                                    every row, key order
 *   SELECT * FROM r WHERE c IS NULL                                        the unreadable-row probe
 *   SELECT * FROM r WHERE (c &gt; ? OR c IS NULL) ORDER BY c ASC               read next
 *   SELECT * FROM r WHERE (c &lt; ? OR c IS NULL) ORDER BY c DESC              read previous
 *   SELECT * FROM r WHERE (c &gt;= ? OR c IS NULL) ORDER BY c ASC              start browse
 *   SELECT * FROM r WHERE (c &lt; ? OR c LIKE ? ESCAPE '\') ORDER BY c DESC    start browse backward
 *   SELECT * FROM r WHERE c LIKE ? ESCAPE '\' ORDER BY c ASC [FOR UPDATE]  keyed read
 *   SELECT * FROM r WHERE c = ? [FOR UPDATE]                               whole-image read
 *   SELECT COUNT(*) FROM r                                                 row count
 *   INSERT INTO r [(c)] VALUES (?)                                         write
 *   UPDATE r SET c = ? WHERE c LIKE ? ESCAPE '\'                           rewrite
 *   DELETE FROM r                                                          clear
 *   DELETE FROM r WHERE c = ?                                              delete by whole image
 * </pre>
 *
 * <p>{@code FOR UPDATE} is recognised and then ignored, which is correct rather than lazy: it acquires a
 * row lock, and a single-threaded in-memory store has nothing to contend with. The read it qualifies
 * returns the same rows either way, so the repository's hold-for-update branch is still taken.
 */
final class RecordImageStatement {

    /** The kinds of work a recognised statement asks for. */
    enum Kind {
        /** Metadata only: name the relation, return no row. */
        DESCRIBE,
        /** Return rows, filtered and ordered as the plan says. */
        SELECT,
        /** Return a single row holding the row count. */
        COUNT,
        /** Append one row. */
        INSERT,
        /** Replace the image of every matching row. */
        UPDATE,
        /** Remove every matching row. */
        DELETE
    }

    /** How a plan filters rows. */
    enum Filter {
        /** Every row. */
        NONE,
        /** No row at all - the {@code WHERE 1 = 0} of a describe. */
        NEVER,
        /** Only rows whose image is {@code NULL}. */
        UNREADABLE,
        /** Images greater than the operand, or {@code NULL}. */
        AFTER_OR_UNREADABLE,
        /** Images less than the operand, or {@code NULL}. */
        BEFORE_OR_UNREADABLE,
        /** Images at or after the operand, or {@code NULL}. */
        FROM_OR_UNREADABLE,
        /** Images before the first operand, or matching the second as a pattern. */
        BEFORE_OR_MATCHING,
        /** Images matching the operand as a {@code LIKE} pattern. */
        MATCHING,
        /** Images equal to the operand. */
        EQUAL
    }

    /** How a plan orders the rows it returns. */
    enum Ordering {
        /** Write order - the physical-record ordinal. */
        WRITE,
        /** Ascending by record image. */
        ASCENDING,
        /** Descending by record image. */
        DESCENDING
    }

    /** The escape character every composed {@code LIKE} declares. */
    static final char LIKE_ESCAPE = '\\';

    private final Kind kind;

    private final String dsname;

    private final Filter filter;

    private final Ordering ordering;

    private final int operandCount;

    private RecordImageStatement(Kind kind, String dsname, Filter filter, Ordering ordering,
            int operandCount) {
        this.kind = kind;
        this.dsname = dsname;
        this.filter = filter;
        this.ordering = ordering;
        this.operandCount = operandCount;
    }

    Kind kind() {
        return kind;
    }

    String dsname() {
        return dsname;
    }

    Filter filter() {
        return filter;
    }

    Ordering ordering() {
        return ordering;
    }

    /**
     * How many positional parameters the statement declares, so a bind at a higher index is refused.
     *
     * @return the parameter count
     */
    int operandCount() {
        return operandCount;
    }

    /**
     * Recognises a composed statement.
     *
     * @param sql the statement text, exactly as the module composed it
     * @return the plan
     * @throws SQLException if the text is not one of the composed shapes
     */
    static RecordImageStatement of(String sql) throws SQLException {
        if (sql == null) {
            throw refuse("null");
        }
        String text = sql.trim();
        String upper = text.toUpperCase(Locale.ROOT);

        if (upper.startsWith("SELECT COUNT(*) FROM ")) {
            return new RecordImageStatement(Kind.COUNT,
                    relationOf(text, "SELECT COUNT(*) FROM ".length(), text.length()),
                    Filter.NONE, Ordering.WRITE, 0);
        }
        if (upper.startsWith("SELECT * FROM ")) {
            return select(text, upper);
        }
        if (upper.startsWith("INSERT INTO ")) {
            return insert(text, upper);
        }
        if (upper.startsWith("UPDATE ")) {
            return update(text, upper);
        }
        if (upper.startsWith("DELETE FROM ")) {
            return delete(text, upper);
        }
        throw refuse(sql);
    }

    private static RecordImageStatement select(String text, String upper) throws SQLException {
        String body = text.substring("SELECT * FROM ".length());
        String bodyUpper = upper.substring("SELECT * FROM ".length());

        boolean forUpdate = bodyUpper.endsWith(" FOR UPDATE");
        if (forUpdate) {
            body = body.substring(0, body.length() - " FOR UPDATE".length());
            bodyUpper = bodyUpper.substring(0, bodyUpper.length() - " FOR UPDATE".length());
        }

        Ordering ordering = Ordering.WRITE;
        int orderAt = bodyUpper.indexOf(" ORDER BY ");
        if (orderAt >= 0) {
            String clause = body.substring(orderAt + " ORDER BY ".length());
            String clauseUpper = bodyUpper.substring(orderAt + " ORDER BY ".length());
            if (clauseUpper.endsWith(" DESC")) {
                ordering = Ordering.DESCENDING;
            } else if (clauseUpper.endsWith(" ASC")) {
                // An ordering over the delimited record-image column is a key order; an ordering over any
                // other expression is the physical-record ordinal, which this store holds as write order.
                ordering = clause.startsWith("\"") ? Ordering.ASCENDING : Ordering.WRITE;
            } else {
                throw refuse(text);
            }
            body = body.substring(0, orderAt);
            bodyUpper = bodyUpper.substring(0, orderAt);
        }

        int whereAt = bodyUpper.indexOf(" WHERE ");
        if (whereAt < 0) {
            return new RecordImageStatement(Kind.SELECT, relationOf(body, 0, body.length()),
                    Filter.NONE, ordering, 0);
        }
        String relation = relationOf(body, 0, whereAt);
        String predicate = body.substring(whereAt + " WHERE ".length()).trim();
        String predicateUpper = bodyUpper.substring(whereAt + " WHERE ".length()).trim();

        if ("1 = 0".equals(predicate)) {
            return new RecordImageStatement(Kind.DESCRIBE, relation, Filter.NEVER, ordering, 0);
        }
        if (predicateUpper.endsWith(" IS NULL") && !predicateUpper.startsWith("(")) {
            return new RecordImageStatement(Kind.SELECT, relation, Filter.UNREADABLE, ordering, 0);
        }
        if (predicateUpper.startsWith("(") && predicateUpper.endsWith(")")) {
            String inner = predicateUpper.substring(1, predicateUpper.length() - 1);
            if (inner.contains(" > ? OR ") && inner.endsWith(" IS NULL")) {
                return new RecordImageStatement(Kind.SELECT, relation, Filter.AFTER_OR_UNREADABLE,
                        ordering, 1);
            }
            if (inner.contains(" < ? OR ") && inner.endsWith(" IS NULL")) {
                return new RecordImageStatement(Kind.SELECT, relation, Filter.BEFORE_OR_UNREADABLE,
                        ordering, 1);
            }
            if (inner.contains(" >= ? OR ") && inner.endsWith(" IS NULL")) {
                return new RecordImageStatement(Kind.SELECT, relation, Filter.FROM_OR_UNREADABLE,
                        ordering, 1);
            }
            if (inner.contains(" < ? OR ") && inner.contains(" LIKE ? ESCAPE ")) {
                return new RecordImageStatement(Kind.SELECT, relation, Filter.BEFORE_OR_MATCHING,
                        ordering, 2);
            }
            throw refuse(text);
        }
        if (predicateUpper.contains(" LIKE ? ESCAPE ")) {
            return new RecordImageStatement(Kind.SELECT, relation, Filter.MATCHING, ordering, 1);
        }
        if (predicateUpper.endsWith(" = ?")) {
            return new RecordImageStatement(Kind.SELECT, relation, Filter.EQUAL, ordering, 1);
        }
        throw refuse(text);
    }

    private static RecordImageStatement insert(String text, String upper) throws SQLException {
        int valuesAt = upper.indexOf(" VALUES (?)");
        if (valuesAt < 0 || !upper.endsWith(" VALUES (?)")) {
            throw refuse(text);
        }
        String target = text.substring("INSERT INTO ".length(), valuesAt).trim();
        int columnListAt = target.indexOf(" (");
        if (columnListAt >= 0) {
            target = target.substring(0, columnListAt).trim();
        }
        return new RecordImageStatement(Kind.INSERT, relationOf(target, 0, target.length()),
                Filter.NONE, Ordering.WRITE, 1);
    }

    private static RecordImageStatement update(String text, String upper) throws SQLException {
        int setAt = upper.indexOf(" SET ");
        int whereAt = upper.indexOf(" WHERE ");
        if (setAt < 0 || whereAt < setAt || !upper.contains(" LIKE ? ESCAPE ")) {
            throw refuse(text);
        }
        return new RecordImageStatement(Kind.UPDATE,
                relationOf(text, "UPDATE ".length(), setAt), Filter.MATCHING, Ordering.WRITE, 2);
    }

    private static RecordImageStatement delete(String text, String upper) throws SQLException {
        int whereAt = upper.indexOf(" WHERE ");
        if (whereAt < 0) {
            return new RecordImageStatement(Kind.DELETE,
                    relationOf(text, "DELETE FROM ".length(), text.length()), Filter.NONE,
                    Ordering.WRITE, 0);
        }
        if (!upper.endsWith(" = ?")) {
            throw refuse(text);
        }
        return new RecordImageStatement(Kind.DELETE,
                relationOf(text, "DELETE FROM ".length(), whereAt), Filter.EQUAL, Ordering.WRITE, 1);
    }

    /**
     * The dataset name inside a delimited identifier, with the doubled quotes an embedded quote becomes
     * folded back to one.
     */
    private static String relationOf(String text, int from, int to) throws SQLException {
        String identifier = text.substring(from, to).trim();
        if (identifier.length() < 3 || identifier.charAt(0) != '"'
                || identifier.charAt(identifier.length() - 1) != '"') {
            throw new SQLException("A record-image store addresses a relation by delimited identifier "
                    + "and was handed '" + identifier + "'. Every dataset name this module composes is "
                    + "delimited, so an undelimited one means the statement was not composed by "
                    + "DatasetRelation.", RecordImageStore.RELATION_NOT_FOUND_STATE,
                    RecordImageStore.RELATION_NOT_FOUND_CODE);
        }
        return identifier.substring(1, identifier.length() - 1).replace("\"\"", "\"");
    }

    private static SQLException refuse(String sql) {
        return new SQLException("This record-image store recognises only the statements this module "
                + "composes, and was handed: " + sql + ". It refuses rather than guessing, so that a "
                + "change to a composed statement fails here instead of returning a plausible but "
                + "different answer to a parity case. Extend RecordImageStatement's grammar to match the "
                + "new composition.", "42000", 42000);
    }
}
