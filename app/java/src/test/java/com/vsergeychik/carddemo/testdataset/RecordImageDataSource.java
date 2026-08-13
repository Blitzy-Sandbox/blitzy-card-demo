package com.vsergeychik.carddemo.testdataset;

import com.vsergeychik.carddemo.testdataset.RecordImageStore.ColumnForm;
import com.vsergeychik.carddemo.testdataset.RecordImageStore.Relation;

import java.io.PrintWriter;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Logger;

import javax.sql.DataSource;

/**
 * A {@link DataSource} over a {@link RecordImageStore}: the DDL-free stand-in for the record-image
 * relations a site's gateway presents.
 */
public final class RecordImageDataSource implements DataSource {
    public static final String RECORD_IMAGE_COLUMN = "RECORD_IMAGE";

    public static final String PRODUCT_NAME = "CardDemo record-image store";

    private final RecordImageStore store;

    public RecordImageDataSource() {
        this(new RecordImageStore());
    }

    public RecordImageDataSource(RecordImageStore store) {
        this.store = Objects.requireNonNull(store, "A record-image data source serves a store of rows");
    }

    public RecordImageStore store() {
        return store;
    }

    public RecordImageDataSource define(String dsname, String columnName, ColumnForm form, int width) {
        store.define(dsname, columnName, form, width);
        return this;
    }

    public RecordImageDataSource defineUnique(String dsname, String columnName, ColumnForm form,
            int width) {
        store.defineUnique(dsname, columnName, form, width);
        return this;
    }

    @Override
    public Connection getConnection() {
        return (Connection) Proxy.newProxyInstance(RecordImageDataSource.class.getClassLoader(),
                new Class<?>[] {Connection.class}, new ConnectionHandler(store));
    }

    @Override
    public Connection getConnection(String username, String password) {
        return getConnection();
    }

    @Override
    public PrintWriter getLogWriter() {
        return null;
    }

    @Override
    public void setLogWriter(PrintWriter out) {
    }

    @Override
    public void setLoginTimeout(int seconds) {
    }

    @Override
    public int getLoginTimeout() {
        return 0;
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        throw new SQLFeatureNotSupportedException("This record-image store does not log");
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        if (iface.isInstance(this)) {
            return iface.cast(this);
        }
        throw new SQLException("A record-image data source is not a " + iface.getName());
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) {
        return iface.isInstance(this);
    }

    private static final class ConnectionHandler implements InvocationHandler {
        private final RecordImageStore store;

        private boolean autoCommit = true;

        private boolean closed;

        private Map<String, Relation> pending;

        ConnectionHandler(RecordImageStore store) {
            this.store = store;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            switch (method.getName()) {
                case "prepareStatement":
                    return preparedStatement(proxy, (String) args[0]);
                case "createStatement":
                    return preparedStatement(proxy, null);
                case "getAutoCommit":
                    return autoCommit;
                case "setAutoCommit":
                    autoCommit = (Boolean) args[0];
                    if (autoCommit) {
                        pending = null;
                    }
                    return null;
                case "commit":
                    if (pending != null) {
                        Map<String, Relation> committing = store.snapshot();
                        committing.putAll(pending);
                        store.publish(committing);
                        pending = null;
                    }
                    return null;
                case "rollback":
                    pending = null;
                    return null;
                case "close":
                    closed = true;
                    pending = null;
                    return null;
                case "isClosed":
                    return closed;
                case "isValid":
                    return !closed;
                case "getMetaData":
                    return databaseMetaData();
                case "getTransactionIsolation":
                    return Connection.TRANSACTION_READ_COMMITTED;
                case "getHoldability":
                    return ResultSet.HOLD_CURSORS_OVER_COMMIT;
                case "getCatalog":
                case "getSchema":
                    return null;
                case "unwrap":
                    return proxyOrRefuse(proxy, (Class<?>) args[0]);
                case "isWrapperFor":
                    return ((Class<?>) args[0]).isInstance(proxy);
                case "toString":
                    return PRODUCT_NAME + " connection";
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "equals":
                    return proxy == args[0];
                default:
                    return defaultValue(method.getReturnType());
            }
        }

        private Object preparedStatement(Object connection, String sql) {
            return Proxy.newProxyInstance(RecordImageDataSource.class.getClassLoader(),
                    new Class<?>[] {PreparedStatement.class},
                    new StatementHandler(this, connection, sql));
        }

        int mutate(Mutation mutation) throws SQLException {
            if (autoCommit) {
                Map<String, Relation> immediate = store.snapshot();
                int affected = mutation.applyTo(immediate);
                store.publish(immediate);
                return affected;
            }
            Map<String, Relation> committed = store.snapshot();
            Map<String, Relation> working = new LinkedHashMap<>();
            committed.forEach((dsname, relation) -> working.put(dsname, relation.copy()));
            if (pending != null) {
                working.putAll(pending);
            }
            int affected = mutation.applyTo(working);
            Map<String, Relation> modified = pending == null ? new LinkedHashMap<>() : pending;
            working.forEach((dsname, relation) -> {
                Relation before = committed.get(dsname);
                if (modified.containsKey(dsname) || before == null || !sameRows(before, relation)) {
                    modified.put(dsname, relation);
                }
            });
            pending = modified;
            return affected;
        }

        private static boolean sameRows(Relation before, Relation now) {
            List<Object> was = before.rows();
            List<Object> is = now.rows();
            if (was.size() != is.size()) {
                return false;
            }
            for (int row = 0; row < was.size(); row++) {
                Object left = was.get(row);
                Object right = is.get(row);
                if (left instanceof byte[] leftBytes && right instanceof byte[] rightBytes) {
                    if (!Arrays.equals(leftBytes, rightBytes)) {
                        return false;
                    }
                } else if (!Objects.equals(left, right)) {
                    return false;
                }
            }
            return true;
        }

        boolean refusesDuplicates(String dsname) {
            return store.refusesDuplicates(dsname);
        }

        Relation read(String dsname) throws SQLException {
            if (pending != null) {
                Relation own = pending.get(dsname);
                if (own != null) {
                    return own;
                }
            }
            return RecordImageStore.require(store.snapshot(), dsname);
        }
    }

    private interface Mutation {
        int applyTo(Map<String, Relation> relations) throws SQLException;
    }

    private static final class StatementHandler implements InvocationHandler {
        private final ConnectionHandler connection;

        private final Object connectionProxy;

        private final Map<Integer, Object> operands = new LinkedHashMap<>();

        private RecordImageStatement plan;

        private int maxRows;

        private ResultSet current;

        private int updateCount = -1;

        StatementHandler(ConnectionHandler connection, Object connectionProxy, String sql) {
            this.connection = connection;
            this.connectionProxy = connectionProxy;
            this.pendingSql = sql;
        }

        private final String pendingSql;

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            switch (method.getName()) {
                case "setString":
                case "setBytes":
                case "setObject":
                    operands.put((Integer) args[0], args[1]);
                    return null;
                case "setNull":
                    operands.put((Integer) args[0], null);
                    return null;
                case "setMaxRows":
                    maxRows = (Integer) args[0];
                    return null;
                case "getMaxRows":
                    return maxRows;
                case "executeQuery":
                    current = query(args);
                    return current;
                case "executeUpdate":
                    updateCount = update(args);
                    return updateCount;
                case "execute":
                    return executeEither(args);
                case "getResultSet":
                    return current;
                case "getUpdateCount":
                    return updateCount;
                case "getMoreResults":
                    current = null;
                    updateCount = -1;
                    return false;
                case "getConnection":
                    return connectionProxy;
                case "isClosed":
                    return false;
                case "getResultSetType":
                    return ResultSet.TYPE_FORWARD_ONLY;
                case "getResultSetConcurrency":
                    return ResultSet.CONCUR_READ_ONLY;
                case "getParameterMetaData":
                    return parameterMetaData();
                case "unwrap":
                    return proxyOrRefuse(proxy, (Class<?>) args[0]);
                case "isWrapperFor":
                    return ((Class<?>) args[0]).isInstance(proxy);
                case "toString":
                    return PRODUCT_NAME + " statement [" + pendingSql + ']';
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "equals":
                    return proxy == args[0];
                default:
                    return defaultValue(method.getReturnType());
            }
        }

        private Object executeEither(Object[] args) throws SQLException {
            RecordImageStatement resolved = resolve(args);
            if (resolved.kind() == RecordImageStatement.Kind.SELECT
                    || resolved.kind() == RecordImageStatement.Kind.DESCRIBE
                    || resolved.kind() == RecordImageStatement.Kind.COUNT) {
                current = query(args);
                return true;
            }
            updateCount = update(args);
            return false;
        }

        private RecordImageStatement resolve(Object[] args) throws SQLException {
            String sql = args != null && args.length > 0 && args[0] instanceof String given
                    ? given : pendingSql;
            if (plan == null || sql != null && !sql.equals(pendingSql)) {
                plan = RecordImageStatement.of(sql);
            }
            return plan;
        }

        private ResultSet query(Object[] args) throws SQLException {
            RecordImageStatement resolved = resolve(args);
            Relation relation = connection.read(resolved.dsname());
            if (resolved.kind() == RecordImageStatement.Kind.COUNT) {
                return resultSet(relation, List.of((Object) relation.rows().size()), true);
            }
            List<Object> selected = select(resolved, relation.rows());
            if (maxRows > 0 && selected.size() > maxRows) {
                selected = new ArrayList<>(selected.subList(0, maxRows));
            }
            return resultSet(relation, selected, false);
        }

        private int update(Object[] args) throws SQLException {
            RecordImageStatement resolved = resolve(args);
            return connection.mutate(relations -> {
                Relation relation = RecordImageStore.require(relations, resolved.dsname());
                switch (resolved.kind()) {
                    case INSERT: {
                        Object image = operand(1);
                        requireWidth(relation, resolved.dsname(), image);
                        if (connection.refusesDuplicates(resolved.dsname())
                                && holdsImage(relation, image)) {
                            throw new SQLException("Unique index or primary key violation: dataset '"
                                    + resolved.dsname() + "' already holds that record image",
                                    RecordImageStore.DUPLICATE_IMAGE_STATE,
                                    RecordImageStore.DUPLICATE_IMAGE_CODE);
                        }
                        relation.rows().add(image);
                        return 1;
                    }
                    case UPDATE: {
                        Object replacement = operand(1);
                        requireWidth(relation, resolved.dsname(), replacement);
                        int affected = 0;
                        List<Object> rows = relation.rows();
                        for (int index = 0; index < rows.size(); index++) {
                            if (matches(resolved, rows.get(index), 2)) {
                                rows.set(index, replacement);
                                affected++;
                            }
                        }
                        return affected;
                    }
                    case DELETE: {
                        List<Object> rows = relation.rows();
                        int before = rows.size();
                        if (resolved.filter() == RecordImageStatement.Filter.NONE) {
                            rows.clear();
                        } else {
                            rows.removeIf(row -> matches(resolved, row, 1));
                        }
                        return before - rows.size();
                    }
                    default:
                        throw new SQLException("A " + resolved.kind() + " statement returns rows and "
                                + "cannot be executed as an update", "42000", 42000);
                }
            });
        }

        private List<Object> select(RecordImageStatement resolved, List<Object> rows) {
            List<Object> selected = new ArrayList<>();
            if (resolved.filter() != RecordImageStatement.Filter.NEVER) {
                for (Object row : rows) {
                    if (matches(resolved, row, 1)) {
                        selected.add(row);
                    }
                }
            }
            if (resolved.ordering() != RecordImageStatement.Ordering.WRITE) {
                boolean ascending = resolved.ordering() == RecordImageStatement.Ordering.ASCENDING;
                selected.sort(Comparator.comparing(row -> row, (left, right) ->
                        RecordImagePredicate.order(left, right, ascending)));
            }
            return selected;
        }

        private boolean matches(RecordImageStatement resolved, Object row, int firstIndex) {
            switch (resolved.filter()) {
                case NONE:
                    return true;
                case NEVER:
                    return false;
                case UNREADABLE:
                    return row == null;
                case AFTER_OR_UNREADABLE:
                    return row == null
                            || RecordImagePredicate.compareImages(row, operand(firstIndex)) > 0;
                case BEFORE_OR_UNREADABLE:
                    return row == null
                            || RecordImagePredicate.compareImages(row, operand(firstIndex)) < 0;
                case FROM_OR_UNREADABLE:
                    return row == null
                            || RecordImagePredicate.compareImages(row, operand(firstIndex)) >= 0;
                case BEFORE_OR_MATCHING:
                    if (row == null) {
                        return false;
                    }
                    return RecordImagePredicate.compareImages(row, operand(firstIndex)) < 0
                            || like(row, operand(firstIndex + 1));
                case MATCHING:
                    return row != null && like(row, operand(firstIndex));
                case EQUAL:
                    return row != null && RecordImagePredicate
                            .compareImages(row, operand(firstIndex)) == 0;
                default:
                    return false;
            }
        }

        private boolean like(Object row, Object pattern) {
            return RecordImagePredicate.like(text(row), text(pattern), RecordImageStatement.LIKE_ESCAPE);
        }

        private Object operand(int index) {
            return operands.get(index);
        }

        private static String text(Object value) {
            if (value instanceof byte[] bytes) {
                return new String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1);
            }
            return String.valueOf(value);
        }

        private static boolean holdsImage(Relation relation, Object image) {
            for (Object row : relation.rows()) {
                if (row == null ? image == null
                        : image != null && RecordImagePredicate.compareImages(row, image) == 0) {
                    return true;
                }
            }
            return false;
        }

        private static void requireWidth(Relation relation, String dsname, Object image)
                throws SQLException {
            int length;
            if (image == null) {
                return;
            } else if (image instanceof String characters) {
                length = characters.length();
            } else if (image instanceof byte[] bytes) {
                length = bytes.length;
            } else {
                throw new SQLException("A record image is bound as a string or as bytes, and dataset '"
                        + dsname + "' was handed a " + image.getClass().getName(),
                        RecordImageStore.VALUE_TOO_LONG_STATE, RecordImageStore.VALUE_TOO_LONG_CODE);
            }
            if (length > relation.width()) {
                throw new SQLException("Value too long for column \"" + relation.columnName() + '"'
                        + ": dataset '" + dsname + "' is declared " + relation.width()
                        + " wide and the image is " + length,
                        RecordImageStore.VALUE_TOO_LONG_STATE, RecordImageStore.VALUE_TOO_LONG_CODE);
            }
        }

        private Object parameterMetaData() {
            int declared = plan == null ? 1 : plan.operandCount();
            return Proxy.newProxyInstance(RecordImageDataSource.class.getClassLoader(),
                    new Class<?>[] {java.sql.ParameterMetaData.class},
                    (proxy, method, args) -> {
                        switch (method.getName()) {
                            case "getParameterCount":
                                return declared;
                            case "getParameterType":
                                return java.sql.Types.VARCHAR;
                            case "getParameterTypeName":
                                return "VARCHAR";
                            case "getParameterClassName":
                                return String.class.getName();
                            case "getParameterMode":
                                return java.sql.ParameterMetaData.parameterModeIn;
                            case "isNullable":
                                return java.sql.ParameterMetaData.parameterNullable;
                            case "toString":
                                return PRODUCT_NAME + " parameter metadata";
                            case "hashCode":
                                return System.identityHashCode(proxy);
                            case "equals":
                                return proxy == args[0];
                            default:
                                return defaultValue(method.getReturnType());
                        }
                    });
        }

        private ResultSet resultSet(Relation relation, List<Object> rows, boolean counting) {
            return (ResultSet) Proxy.newProxyInstance(RecordImageDataSource.class.getClassLoader(),
                    new Class<?>[] {ResultSet.class},
                    new ResultSetHandler(relation, rows, counting));
        }
    }

    private static final class ResultSetHandler implements InvocationHandler {
        private final Relation relation;

        private final List<Object> rows;

        private final boolean counting;

        private int cursor = -1;

        private boolean lastWasNull;

        ResultSetHandler(Relation relation, List<Object> rows, boolean counting) {
            this.relation = relation;
            this.rows = rows;
            this.counting = counting;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            switch (method.getName()) {
                case "next":
                    return ++cursor < rows.size();
                case "getString":
                    return asString(value());
                case "getBytes":
                    return asBytes(value());
                case "getObject":
                    return value();
                case "getInt":
                    return asNumber(value()).intValue();
                case "getLong":
                    return asNumber(value()).longValue();
                case "wasNull":
                    return lastWasNull;
                case "getMetaData":
                    return metaData();
                case "getRow":
                    return cursor + 1;
                case "isBeforeFirst":
                    return cursor < 0;
                case "isAfterLast":
                    return cursor >= rows.size();
                case "getType":
                    return ResultSet.TYPE_FORWARD_ONLY;
                case "getConcurrency":
                    return ResultSet.CONCUR_READ_ONLY;
                case "findColumn":
                    return 1;
                case "unwrap":
                    return proxyOrRefuse(proxy, (Class<?>) args[0]);
                case "isWrapperFor":
                    return ((Class<?>) args[0]).isInstance(proxy);
                case "toString":
                    return PRODUCT_NAME + " result set of " + rows.size() + " row(s)";
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "equals":
                    return proxy == args[0];
                default:
                    return defaultValue(method.getReturnType());
            }
        }

        private Object value() throws SQLException {
            if (cursor < 0 || cursor >= rows.size()) {
                throw new SQLException("No data is available: the cursor is "
                        + (cursor < 0 ? "before the first row" : "after the last row")
                        + ", so next() must be called and must return true before a column is read",
                        "24000", 24000);
            }
            Object value = rows.get(cursor);
            lastWasNull = value == null;
            return value;
        }

        private String asString(Object value) throws SQLException {
            if (value == null) {
                return null;
            }
            if (value instanceof String characters) {
                return characters;
            }
            if (counting) {
                return String.valueOf(value);
            }
            throw new SQLException("Dataset '" + relation.columnName() + "' presents its record image as "
                    + relation.form().typeName() + ", so it is read with getBytes. Reading it as a string "
                    + "would need a code page this store does not choose; declare the relation as "
                    + ColumnForm.CHARACTER + " if the binding reads strings.", "0A000", 0);
        }

        private byte[] asBytes(Object value) throws SQLException {
            if (value == null) {
                return null;
            }
            if (value instanceof byte[] bytes) {
                return bytes.clone();
            }
            throw new SQLException("Dataset '" + relation.columnName() + "' presents its record image as "
                    + relation.form().typeName() + ", so it is read with getString. Reading it as bytes "
                    + "would need a code page this store does not choose; declare the relation as "
                    + ColumnForm.BINARY + " if the binding reads bytes.", "0A000", 0);
        }

        private Number asNumber(Object value) throws SQLException {
            if (value instanceof Number number) {
                return number;
            }
            throw new SQLException("A record image is not a number, so it cannot be read as one",
                    "22018", 22018);
        }

        private Object metaData() {
            return Proxy.newProxyInstance(RecordImageDataSource.class.getClassLoader(),
                    new Class<?>[] {ResultSetMetaData.class},
                    (proxy, method, args) -> {
                        switch (method.getName()) {
                            case "getColumnCount":
                                return 1;
                            case "getColumnName":
                            case "getColumnLabel":
                                return counting ? "COUNT(*)" : relation.columnName();
                            case "getColumnType":
                                return counting ? java.sql.Types.BIGINT : relation.form().sqlType();
                            case "getColumnTypeName":
                                return counting ? "BIGINT" : relation.form().typeName();
                            case "getColumnDisplaySize":
                            case "getPrecision":
                                return counting ? 20 : relation.width();
                            case "isNullable":
                                return ResultSetMetaData.columnNullable;
                            case "getColumnClassName":
                                return counting ? Long.class.getName()
                                        : relation.form() == ColumnForm.CHARACTER
                                                ? String.class.getName() : byte[].class.getName();
                            case "toString":
                                return PRODUCT_NAME + " metadata";
                            case "hashCode":
                                return System.identityHashCode(proxy);
                            case "equals":
                                return proxy == args[0];
                            default:
                                return defaultValue(method.getReturnType());
                        }
                    });
        }
    }

    private static Object databaseMetaData() {
        return Proxy.newProxyInstance(RecordImageDataSource.class.getClassLoader(),
                new Class<?>[] {DatabaseMetaData.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getDatabaseProductName":
                            return PRODUCT_NAME;
                        case "getDatabaseProductVersion":
                        case "getDriverVersion":
                            return "1";
                        case "getDriverName":
                            return PRODUCT_NAME + " driver";
                        case "getURL":
                            return "record-image:memory";
                        case "getUserName":
                            return "";
                        case "getIdentifierQuoteString":
                            return "\"";
                        case "storesUpperCaseIdentifiers":
                        case "supportsGetGeneratedKeys":
                            return true;
                        case "getDatabaseMajorVersion":
                        case "getDatabaseMinorVersion":
                        case "getJDBCMajorVersion":
                            return 1;
                        case "toString":
                            return PRODUCT_NAME + " database metadata";
                        case "hashCode":
                            return System.identityHashCode(proxy);
                        case "equals":
                            return proxy == args[0];
                        default:
                            return defaultValue(method.getReturnType());
                    }
                });
    }

    private static Object proxyOrRefuse(Object proxy, Class<?> iface) throws SQLException {
        if (iface.isInstance(proxy)) {
            return proxy;
        }
        throw new SQLException("A " + PRODUCT_NAME + " object is not a " + iface.getName());
    }

    private static Object defaultValue(Class<?> returnType) {
        if (!returnType.isPrimitive()) {
            return null;
        }
        if (returnType == boolean.class) {
            return false;
        }
        if (returnType == void.class) {
            return null;
        }
        if (returnType == int.class) {
            return 0;
        }
        if (returnType == long.class) {
            return 0L;
        }
        if (returnType == short.class) {
            return (short) 0;
        }
        if (returnType == byte.class) {
            return (byte) 0;
        }
        if (returnType == double.class) {
            return 0d;
        }
        if (returnType == float.class) {
            return 0f;
        }
        return (char) 0;
    }
}
