package com.vsergeychik.carddemo.transaction;

import com.vsergeychik.carddemo.common.CicsResponse;
import com.vsergeychik.carddemo.common.DatasetIntegrityException;
import com.vsergeychik.carddemo.common.DatasetRelation.BackendDiagnostic;
import com.vsergeychik.carddemo.common.FileStatus;
import com.vsergeychik.carddemo.common.FileStatus.Outcome;
import com.vsergeychik.carddemo.common.RecordImageForm;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBinding;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBindings;
import com.vsergeychik.carddemo.transaction.TranCatBalRepository.OpenMode;
import com.vsergeychik.carddemo.transaction.TranCatBalRepository.ReadResult;
import com.vsergeychik.carddemo.transaction.TranCatBalRepository.Statements;
import com.vsergeychik.carddemo.transaction.TranCatBalRepository.TranCatBalFile;
import com.vsergeychik.carddemo.transaction.TranCatBalRepository.WriteResult;
import com.vsergeychik.carddemo.transaction.model.TranCatBalRecord;
import com.vsergeychik.carddemo.transaction.model.TranCatBalRecord.TranCatKey;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * {@link TranCatBalRepository} - the {@code TCATBALF} dataset, and the 17-byte key that is this
 * package's signature correctness risk.
 *
 * <p>Organised around the two consumers rather than around the Java methods, because the behaviour under
 * test is theirs: {@code app/cbl/CBACT04C.cbl} browses this dataset sequentially to drive the interest
 * calculator, and {@code app/cbl/CBTRN02C.cbl} reads it by key and then creates or updates. The two use
 * genuinely different guard ladders, and the tests that prove they do are the point of the class.
 *
 * <p>Every expectation is seeded from {@code app/data/ASCII/tcatbal.txt}, copied to the test classpath -
 * 50 records of exactly 50 bytes, already in ascending key order. Failure arms that a real relation cannot
 * produce are driven through mocked JDBC chains, because a state the COBOL has no code for must be
 * unreachable rather than merely unobserved.
 *
 * <p>Four subjects are singled out because a mistake in any of them is <em>invisible</em> to a width check
 * and would surface only as a byte difference in another package's output:
 * <ol>
 *   <li><strong>The 17-byte key.</strong> {@code app/cpy/CVTRA02Y.cpy} is also a 50-byte record, so the
 *       record width cannot tell the two apart; only the key width and the offset of the signed span can.
 *       See {@link KeyGeometry}.</li>
 *   <li><strong>The name {@code TRAN-CAT-KEY}.</strong> {@code app/cpy/CVTRA04Y.cpy} declares a group of
 *       exactly that name that is 6 bytes rather than 17. Neither COBOL group is renamed to disambiguate
 *       them - a field name is part of the contract. See {@link KeyGeometry}.</li>
 *   <li><strong>{@code INITIALIZE} skips {@code FILLER}.</strong> {@code CBTRN02C}'s create-on-miss path
 *       initialises the record area and writes it, so the 22 reserved bytes the failed read left behind go
 *       to the dataset unchanged. See {@link CreateOnMissInitialize}.</li>
 *   <li><strong>There is no schema.</strong> No DDL, no mapping annotation, no alternate index and no
 *       hard-coded dataset name. See {@link SchemaAbsence}.</li>
 * </ol>
 */
@DisplayName("TranCatBalRepository - the TCATBALF transaction category balance dataset")
class TranCatBalRepositoryTest {

    /** The dataset code page for the ASCII fixtures, stated explicitly and never defaulted. */
    private static final Charset ASCII = StandardCharsets.US_ASCII;

    /** The dataset name the bindings declare, standing in for the configured one. */
    private static final String TEST_DSNAME = "AWS.TEST.TCATBALF.VSAM.KSDS";

    /** The record-image column of the seeded relation, and the name the probe will discover. */
    private static final String RECORD_IMAGE_COLUMN = "REC";

    /** The classpath location of the fixture, copied from the reference tree. */
    private static final String FIXTURE = "/fixtures/tcatbal.txt";

    /** The fixture's measured record count, restated from the reference data. */
    private static final int FIXTURE_RECORDS = 50;

    /** The copybook record width, restated from {@code app/cpy/CVTRA01Y.cpy} ({@code RECLN = 50}). */
    private static final int FIFTY = 50;

    /** The copybook key width: {@code TRANCAT-ACCT-ID 9(11) + TRANCAT-TYPE-CD X(02) + TRANCAT-CD 9(04)}. */
    private static final int SEVENTEEN = 17;

    /** The declared width of the trailing {@code FILLER}. */
    private static final int FILLER_WIDTH = 22;

    // ---- the four elementary widths of app/cpy/CVTRA01Y.cpy, so 17 and 50 are reached by addition ----

    /** {@code TRANCAT-ACCT-ID PIC 9(11)}. */
    private static final int ACCT_ID_WIDTH = 11;

    /** {@code TRANCAT-TYPE-CD PIC X(02)}. */
    private static final int TYPE_CD_WIDTH = 2;

    /** {@code TRANCAT-CD PIC 9(04)}. */
    private static final int CAT_CD_WIDTH = 4;

    /**
     * {@code TRAN-CAT-BAL PIC S9(09)V99}: 9 integer digits plus 2 fractional ones.
     *
     * <p>Eleven bytes and not twelve, and not six either: there is no {@code COMP-3} anywhere in
     * {@code app/cpy}, so the span is zoned {@code DISPLAY} with the sign overpunched into the trailing
     * byte, and {@code S9(p)V99} therefore occupies exactly {@code p + 2} bytes.
     */
    private static final int BALANCE_WIDTH = 9 + 2;

    // ---- the CVTRA02Y near-miss, restated here rather than imported ----

    /**
     * The offset of {@code DIS-INT-RATE} within {@code app/cpy/CVTRA02Y.cpy}'s 50-byte
     * {@code DIS-GROUP-RECORD}: {@code X(10) + X(02) + 9(04)} = 16.
     *
     * <p>Restated as a literal rather than imported from {@code account.model.DisclosureGroupRecord},
     * because that type belongs to another package and this class must not depend on it to state the
     * contrast. The point of the contrast is in {@link KeyGeometry#signedSpanStartsAtSeventeenNotSixteen()}.
     */
    private static final int DISCLOSURE_GROUP_RATE_OFFSET = 16;

    /** {@code DIS-INT-RATE PIC S9(04)V99} is {@code 4 + 2} = 6 bytes, not 11. */
    private static final int DISCLOSURE_GROUP_RATE_WIDTH = 4 + 2;

    // ---- the vocabulary of a schema, which this module must not have anywhere (gates G44 and G45) ----

    /**
     * Annotation simple names that would mean an object-relational mapping had been imposed on a dataset.
     *
     * <p>Immutable, so this list is shared state without being mutable state (B9 / gate G53).
     */
    private static final List<String> MAPPING_ANNOTATION_NAMES = List.of(
            "Entity", "Table", "SecondaryTable", "Id", "IdClass", "EmbeddedId", "Embeddable", "Embedded",
            "Column", "JoinColumn", "PrimaryKeyJoinColumn", "GeneratedValue", "SequenceGenerator",
            "TableGenerator", "Version", "OneToOne", "OneToMany", "ManyToOne", "ManyToMany", "Basic",
            "Convert", "Document", "PersistenceCapable");

    /**
     * Statement fragments that define or alter structure rather than reading and writing rows.
     *
     * <p>{@code UPDATE} and {@code INSERT} are deliberately absent: they are the rewrite and the write the
     * COBOL performs. What must never appear is anything that would bring a schema into being.
     */
    private static final List<String> DDL_FRAGMENTS = List.of(
            "CREATE ", "ALTER ", "DROP ", "TRUNCATE ", "RENAME ", "GRANT ", "REVOKE ", "COMMENT ON",
            "PRIMARY KEY", "FOREIGN KEY", "CONSTRAINT", "SEQUENCE", "INDEX", "MERGE ");

    /**
     * Method-name fragments this module uses when a dataset genuinely has an alternate index.
     *
     * <p>Taken from the two repositories that do have one - {@code CARDAIX} over {@code CARDDAT} and
     * {@code CXACAIX} over {@code CCXREF} - so the absence asserted here is measured against the
     * convention actually in use rather than an invented one.
     */
    private static final List<String> ALTERNATE_INDEX_NAME_FRAGMENTS = List.of(
            "alternateindex", "altindex", "aix", "alternatekey", "altkey", "secondaryindex");

    /** An account identifier the fixture does not contain, for the invalid-key arms. */
    private static final long ABSENT_ACCT_ID = 99_999_999_999L;

    /** The type code the fixture uses throughout. */
    private static final String FIXTURE_TYPE_CD = "01";

    /** The category code the fixture uses throughout. */
    private static final int FIXTURE_CAT_CD = 1;


    /**
     * Every in-memory database this test created, so the teardown can dispose of all of them.
     *
     * <p>An instance field, populated only by this test's own helpers: nothing is shared between tests
     * and no static holds it (gate G53, practice B9).
     */
    private final List<DataSource> createdDatabases = new ArrayList<>();

    /**
     * Drops and shuts down every database this test created.
     *
     * <p>{@code DB_CLOSE_DELAY=-1} is what makes the seed usable at all - without it each connection
     * {@code DriverManagerDataSource} opens would get its own empty database - and it is also what keeps
     * every one of them alive for the rest of the JVM once the test that made it has finished. Over a
     * suite this size that is hundreds of live schemas held to the end of the run, each one still
     * addressable by name; and a name that outlives its test is a name a later test could reach, which
     * is the shared state the per-test database exists to avoid. Dropping the objects and shutting the
     * database down closes both, and it frees the name for reuse - which is why the ordinal below can
     * restart at zero for each test instead of needing a counter that outlives one.
     */
    @AfterEach
    void disposeCreatedDatabases() {
        for (DataSource created : createdDatabases) {
            JdbcTemplate template = new JdbcTemplate(created);
            template.execute("DROP ALL OBJECTS");
            template.execute("SHUTDOWN");
        }
        createdDatabases.clear();
    }

    /**
     * Distinguishes the in-memory databases one test creates, so no two of them share a relation.
     *
     * <p>An instance field rather than a static counter. A static counter is mutable static state,
     * which gate G53 and practice B9 forbid, and it is only what a static counter buys - a name unique
     * across the whole run - that made it tempting. {@link #disposeCreatedDatabases()} removes the need:
     * each database is shut down when its test ends, so the name is free again and a per-test ordinal
     * is enough. It is also still a counter rather than a random value, so a run stays reproducible.
     */
    private int databaseOrdinal;

    // =============================================================================================
    // Fixtures and helpers.
    // =============================================================================================

    /**
     * Builds the binding the repository resolves, with every component under the test's control so each
     * of the constructor's checks can be driven both ways.
     *
     * @param dsname       the dataset name to configure
     * @param recordLength the record length to configure
     * @param keyLength    the key length to configure, or {@code null} to omit it
     * @return a catalogue containing exactly that one binding
     */
    private static DatasetBindings bindings(String dsname, int recordLength, Integer keyLength) {
        DatasetBindings catalogue = new DatasetBindings();
        catalogue.put(TranCatBalRepository.DD_NAME, new DatasetBinding(dsname, "ksds", false, "FB", null,
                recordLength, "CVTRA01Y", keyLength, null, null, null));
        return catalogue;
    }

    /**
     * The correctly configured binding every behavioural test uses.
     *
     * @return a catalogue naming {@link #TEST_DSNAME} at the copybook width and key width
     */
    private static DatasetBindings validBindings() {
        return bindings(TEST_DSNAME, FIFTY, SEVENTEEN);
    }

    /**
     * A repository over the given template and the correctly configured bindings.
     *
     * @param template the template to reach the relation with
     * @return the repository
     */
    private static TranCatBalRepository repository(JdbcTemplate template) {
        return new TranCatBalRepository(template, validBindings(), ASCII, RecordImageForm.CHARACTER);
    }

    /**
     * Creates a private in-memory relation with one record-image column and seeds it.
     *
     * <p>The relation is deliberately created without a key constraint, so a test can seed two rows under
     * one key and drive the arm that a unique key would otherwise make unreachable.
     *
     * @param rows the record images to insert, in the order given
     * @return a template over the seeded relation
     */
    private JdbcTemplate seeded(List<String> rows) {
        return seeded(rows, FIFTY);
    }

    /**
     * Creates a private in-memory relation whose record-image column is as wide as asked, and seeds it.
     *
     * <p>The width is a parameter for one reason: a test has to be able to store an image wider than the
     * copybook declares, to prove such an image is rejected rather than truncated.
     *
     * @param rows        the record images to insert, in the order given
     * @param columnWidth the declared width of the record-image column
     * @return a template over the seeded relation
     */
    private JdbcTemplate seeded(List<String> rows, int columnWidth) {
        JdbcTemplate template = new JdbcTemplate(seededDataSource(columnWidth));
        for (String row : rows) {
            template.update("INSERT INTO \"" + TEST_DSNAME + "\" VALUES (?)", row);
        }
        return template;
    }

    /**
     * Creates the empty relation and returns its data source, for a test that needs the source itself.
     *
     * @param columnWidth the declared width of the record-image column
     * @return the data source over the created relation
     */
    private DataSource seededDataSource(int columnWidth) {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:tcatbalrepo" + (++databaseOrdinal)
                        + ";DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE", "sa", "");
        dataSource.setDriverClassName("org.h2.Driver");
        createdDatabases.add(dataSource);
        new JdbcTemplate(dataSource).execute("CREATE TABLE \"" + TEST_DSNAME + "\" ("
                + RECORD_IMAGE_COLUMN + " VARCHAR(" + columnWidth + "))");
        return dataSource;
    }

    /**
     * The 50 fixture records, exactly as stored.
     *
     * @return the fixture's lines
     */
    private static List<String> fixtureRows() {
        try (InputStream stream = TranCatBalRepositoryTest.class.getResourceAsStream(FIXTURE)) {
            if (stream == null) {
                throw new IllegalStateException("The fixture " + FIXTURE + " is absent from the test "
                        + "classpath; every expectation in this class is seeded from it");
            }
            return new String(stream.readAllBytes(), ASCII).lines().toList();
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    /**
     * A record built from nothing, with the fixture's key and a stated balance.
     *
     * @param acctId  the account identifier
     * @param balance the balance to store
     * @return the record
     */
    private static TranCatBalRecord recordFor(long acctId, String balance) {
        return TranCatBalRecord.newInstance(ASCII)
                .trancatAcctId(acctId)
                .trancatTypeCd(FIXTURE_TYPE_CD)
                .trancatCd(FIXTURE_CAT_CD)
                .tranCatBal(new BigDecimal(balance));
    }

    /**
     * Drains a browse into the list of key images it returned, stopping at end of file.
     *
     * @param file  the opened file to browse
     * @param limit the most reads to perform, so a defect cannot loop for ever
     * @return the key images returned, in the order returned
     */
    private static List<String> drainKeys(TranCatBalFile file, int limit) {
        List<String> keys = new ArrayList<>();
        for (int read = 0; read < limit; read++) {
            ReadResult result = file.readNext();
            if (!result.isFound()) {
                break;
            }
            keys.add(result.record().orElseThrow().tranCatKeyImage());
        }
        return keys;
    }

    /**
     * Runs an action with the calling thread marked as being inside a transaction.
     *
     * <p>The marker rather than a real transaction, because what the rewrite inspects is exactly this
     * marker when it decides whether to take the row lock its probe uses.
     *
     * @param action the action to run
     */
    private static void insideUnitOfWork(Runnable action) {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            action.run();
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    /**
     * The same declaration, for an action that answers something the caller then asserts on.
     *
     * <p>A write and a rewrite both require one: the pool hands out connections with auto-commit
     * disabled, so a statement issued with nothing bound to the thread is rolled back when the connection
     * is returned, and the repository refuses rather than reporting a record as stored.
     *
     * @param action the action to run
     * @param <T>    what it answers
     * @return what {@code action} answered
     */
    private static <T> T insideUnitOfWork(java.util.function.Supplier<T> action) {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            return action.get();
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    // ---- mocked JDBC chains, for the arms a real relation cannot produce ----

    /** A chain whose connection cannot be obtained at all. */
    private static JdbcTemplate unreachable() {
        DataSource dataSource = Mockito.mock(DataSource.class);
        try {
            Mockito.when(dataSource.getConnection())
                    .thenThrow(new SQLException("the dataset is unreachable"));
        } catch (SQLException impossible) {
            throw new IllegalStateException(impossible);
        }
        return new JdbcTemplate(dataSource);
    }

    /** A chain whose probe reports the given column metadata. */
    private static JdbcTemplate describing(int columnCount, String columnName) throws SQLException {
        DataSource dataSource = Mockito.mock(DataSource.class);
        Connection connection = Mockito.mock(Connection.class);
        Statement statement = Mockito.mock(Statement.class);
        ResultSet resultSet = Mockito.mock(ResultSet.class);
        ResultSetMetaData metaData = Mockito.mock(ResultSetMetaData.class);
        Mockito.when(dataSource.getConnection()).thenReturn(connection);
        Mockito.when(connection.createStatement()).thenReturn(statement);
        Mockito.when(statement.executeQuery(Mockito.anyString())).thenReturn(resultSet);
        Mockito.when(resultSet.getMetaData()).thenReturn(metaData);
        Mockito.when(metaData.getColumnCount()).thenReturn(columnCount);
        Mockito.when(metaData.getColumnName(1)).thenReturn(columnName);
        return new JdbcTemplate(dataSource);
    }

    /** A chain whose probe describes no metadata at all. */
    private static JdbcTemplate describingNothing() throws SQLException {
        DataSource dataSource = Mockito.mock(DataSource.class);
        Connection connection = Mockito.mock(Connection.class);
        Statement statement = Mockito.mock(Statement.class);
        ResultSet resultSet = Mockito.mock(ResultSet.class);
        Mockito.when(dataSource.getConnection()).thenReturn(connection);
        Mockito.when(connection.createStatement()).thenReturn(statement);
        Mockito.when(statement.executeQuery(Mockito.anyString())).thenReturn(resultSet);
        Mockito.when(resultSet.getMetaData()).thenReturn(null);
        return new JdbcTemplate(dataSource);
    }

    /** A chain that describes a usable column but refuses every prepared statement. */
    private static JdbcTemplate describingThenRefusing() throws SQLException {
        DataSource dataSource = Mockito.mock(DataSource.class);
        Connection connection = Mockito.mock(Connection.class);
        Statement statement = Mockito.mock(Statement.class);
        ResultSet resultSet = Mockito.mock(ResultSet.class);
        ResultSetMetaData metaData = Mockito.mock(ResultSetMetaData.class);
        Mockito.when(dataSource.getConnection()).thenReturn(connection);
        Mockito.when(connection.createStatement()).thenReturn(statement);
        Mockito.when(statement.executeQuery(Mockito.anyString())).thenReturn(resultSet);
        Mockito.when(resultSet.getMetaData()).thenReturn(metaData);
        Mockito.when(metaData.getColumnCount()).thenReturn(1);
        Mockito.when(metaData.getColumnName(1)).thenReturn(RECORD_IMAGE_COLUMN);
        Mockito.when(connection.prepareStatement(Mockito.anyString()))
                .thenThrow(new SQLException("the statement is refused"));
        return new JdbcTemplate(dataSource);
    }

    /** A chain that describes a usable column and then returns one row whose image is absent. */
    private static JdbcTemplate describingThenReturningNoImage() throws SQLException {
        DataSource dataSource = Mockito.mock(DataSource.class);
        Connection connection = Mockito.mock(Connection.class);
        Statement statement = Mockito.mock(Statement.class);
        ResultSet probe = Mockito.mock(ResultSet.class);
        ResultSetMetaData metaData = Mockito.mock(ResultSetMetaData.class);
        PreparedStatement prepared = Mockito.mock(PreparedStatement.class);
        ResultSet row = Mockito.mock(ResultSet.class);
        Mockito.when(dataSource.getConnection()).thenReturn(connection);
        Mockito.when(connection.createStatement()).thenReturn(statement);
        Mockito.when(statement.executeQuery(Mockito.anyString())).thenReturn(probe);
        Mockito.when(probe.getMetaData()).thenReturn(metaData);
        Mockito.when(metaData.getColumnCount()).thenReturn(1);
        Mockito.when(metaData.getColumnName(1)).thenReturn(RECORD_IMAGE_COLUMN);
        Mockito.when(connection.prepareStatement(Mockito.anyString())).thenReturn(prepared);
        Mockito.when(prepared.executeQuery()).thenReturn(row);
        Mockito.when(row.next()).thenReturn(true, false);
        Mockito.when(row.getString(1)).thenReturn(null);
        return new JdbcTemplate(dataSource);
    }

    /**
     * A chain whose keyed count finds the stated number of rows and whose write reports the stated count.
     *
     * @param rowsFound   how many rows the pre-write probe should see
     * @param updateCount what {@code executeUpdate} should report
     * @return a template over the mocked chain
     */
    private static JdbcTemplate countingThenReporting(int rowsFound, int updateCount)
            throws SQLException {
        DataSource dataSource = Mockito.mock(DataSource.class);
        Connection connection = Mockito.mock(Connection.class);
        Statement statement = Mockito.mock(Statement.class);
        ResultSet probe = Mockito.mock(ResultSet.class);
        ResultSetMetaData metaData = Mockito.mock(ResultSetMetaData.class);
        PreparedStatement prepared = Mockito.mock(PreparedStatement.class);
        ResultSet rows = Mockito.mock(ResultSet.class);
        Mockito.when(dataSource.getConnection()).thenReturn(connection);
        Mockito.when(connection.createStatement()).thenReturn(statement);
        Mockito.when(statement.executeQuery(Mockito.anyString())).thenReturn(probe);
        Mockito.when(probe.getMetaData()).thenReturn(metaData);
        Mockito.when(metaData.getColumnCount()).thenReturn(1);
        Mockito.when(metaData.getColumnName(1)).thenReturn(RECORD_IMAGE_COLUMN);
        Mockito.when(connection.prepareStatement(Mockito.anyString())).thenReturn(prepared);
        Mockito.when(prepared.executeQuery()).thenReturn(rows);
        if (rowsFound <= 0) {
            Mockito.when(rows.next()).thenReturn(false);
        } else if (rowsFound == 1) {
            Mockito.when(rows.next()).thenReturn(true, false);
        } else {
            Mockito.when(rows.next()).thenReturn(true, true, false);
        }
        Mockito.when(prepared.executeUpdate()).thenReturn(updateCount);
        return new JdbcTemplate(dataSource);
    }

    /** A chain that describes a column and then rejects a write as an integrity violation. */
    private static JdbcTemplate describingThenViolatingIntegrityOnWrite() throws SQLException {
        DataSource dataSource = Mockito.mock(DataSource.class);
        Connection connection = Mockito.mock(Connection.class);
        Statement statement = Mockito.mock(Statement.class);
        ResultSet probe = Mockito.mock(ResultSet.class);
        ResultSetMetaData metaData = Mockito.mock(ResultSetMetaData.class);
        PreparedStatement prepared = Mockito.mock(PreparedStatement.class);
        ResultSet noRows = Mockito.mock(ResultSet.class);
        Mockito.when(dataSource.getConnection()).thenReturn(connection);
        Mockito.when(connection.createStatement()).thenReturn(statement);
        Mockito.when(statement.executeQuery(Mockito.anyString())).thenReturn(probe);
        Mockito.when(probe.getMetaData()).thenReturn(metaData);
        Mockito.when(metaData.getColumnCount()).thenReturn(1);
        Mockito.when(metaData.getColumnName(1)).thenReturn(RECORD_IMAGE_COLUMN);
        Mockito.when(connection.prepareStatement(Mockito.anyString())).thenReturn(prepared);
        Mockito.when(prepared.executeQuery()).thenReturn(noRows);
        Mockito.when(noRows.next()).thenReturn(false);
        // SQLSTATE class 23 is the integrity-constraint-violation class: a duplicate key.
        Mockito.when(prepared.executeUpdate())
                .thenThrow(new SQLException("duplicate key", "23505", 23505));
        return new JdbcTemplate(dataSource);
    }

    /**
     * A chain that describes a column, finds no existing row, and then refuses the write for a reason that
     * is <em>not</em> an integrity violation.
     *
     * <p>The other arm of the write's integrity test: a connection failure is a permanent error and must
     * not be reported as a duplicate key, because a caller told "this key already exists" would take a
     * different action from one told "the dataset went away".
     *
     * @return a template whose insert fails with a connection-class {@code SQLSTATE}
     */
    private static JdbcTemplate describingThenFailingWriteWithoutIntegrityViolation()
            throws SQLException {
        DataSource dataSource = Mockito.mock(DataSource.class);
        Connection connection = Mockito.mock(Connection.class);
        Statement statement = Mockito.mock(Statement.class);
        ResultSet probe = Mockito.mock(ResultSet.class);
        ResultSetMetaData metaData = Mockito.mock(ResultSetMetaData.class);
        PreparedStatement prepared = Mockito.mock(PreparedStatement.class);
        ResultSet noRows = Mockito.mock(ResultSet.class);
        Mockito.when(dataSource.getConnection()).thenReturn(connection);
        Mockito.when(connection.createStatement()).thenReturn(statement);
        Mockito.when(statement.executeQuery(Mockito.anyString())).thenReturn(probe);
        Mockito.when(probe.getMetaData()).thenReturn(metaData);
        Mockito.when(metaData.getColumnCount()).thenReturn(1);
        Mockito.when(metaData.getColumnName(1)).thenReturn(RECORD_IMAGE_COLUMN);
        Mockito.when(connection.prepareStatement(Mockito.anyString())).thenReturn(prepared);
        Mockito.when(prepared.executeQuery()).thenReturn(noRows);
        Mockito.when(noRows.next()).thenReturn(false);
        // SQLSTATE class 08 is the connection-exception class, not the integrity class.
        Mockito.when(prepared.executeUpdate())
                .thenThrow(new SQLException("connection lost", "08006", 8006));
        return new JdbcTemplate(dataSource);
    }

    /**
     * A template over a seeded relation whose row-counting query reports no count at all.
     *
     * <p>Only the {@link org.springframework.jdbc.core.PreparedStatementCreator} plus
     * {@link org.springframework.jdbc.core.ResultSetExtractor} overload is overridden - the one the
     * pre-write probe uses - so statement resolution, which uses the {@code String} overload, still works.
     * The template contract says that overload may return {@code null}, and the repository treats an
     * absent count as "more than one" so that a write is refused rather than proceeding unchecked. This is
     * the only way to observe that decision.
     *
     * @param rows the record images to seed
     * @return a template whose probe reports no count
     */
    private JdbcTemplate countReportingNothing(List<String> rows) {
        DataSource dataSource = seededDataSource(FIFTY);
        JdbcTemplate seeding = new JdbcTemplate(dataSource);
        for (String row : rows) {
            seeding.update("INSERT INTO \"" + TEST_DSNAME + "\" VALUES (?)", row);
        }
        return new JdbcTemplate(dataSource) {
            @Override
            public <T> T query(org.springframework.jdbc.core.PreparedStatementCreator creator,
                    org.springframework.jdbc.core.ResultSetExtractor<T> extractor) {
                return null;
            }
        };
    }

    // =============================================================================================
    // The 17-byte key, and the two collisions that make a mistake in it invisible.
    // =============================================================================================

    @Nested
    @DisplayName("the 17-byte TRAN-CAT-KEY, and the two layouts it must never be confused with")
    class KeyGeometry {

        @Test
        @DisplayName("the key is 17 bytes and NOT 16 - the CVTRA02Y near-miss")
        void keyIsSeventeenAndNotSixteen() {
            assertThat(TranCatBalRepository.KEY_LENGTH).isEqualTo(SEVENTEEN);
            // app/cpy/CVTRA02Y.cpy's DIS-GROUP-RECORD is ALSO 50 bytes, so no width check distinguishes
            // the two layouts. This is the assertion that does. That record is modelled in the account
            // package - account.model.DisclosureGroupRecord - and deliberately not here; only its two
            // measurements are restated, so this class states the contrast without depending on it.
            assertThat(TranCatBalRepository.DISCLOSURE_GROUP_KEY_LENGTH).isEqualTo(16);
            assertThat(TranCatBalRepository.KEY_LENGTH)
                    .isNotEqualTo(TranCatBalRepository.DISCLOSURE_GROUP_KEY_LENGTH);
        }

        @Test
        @DisplayName("every width and offset is reached by ADDITION from the copybook, never from memory")
        void geometryIsReachedByAddition() {
            // app/cpy/CVTRA01Y.cpy, elementary item by elementary item:
            //   05 TRAN-CAT-KEY.
            //      10 TRANCAT-ACCT-ID PIC 9(11).      11
            //      10 TRANCAT-TYPE-CD PIC X(02).       2
            //      10 TRANCAT-CD      PIC 9(04).       4   -> 11 + 2 + 4 = 17
            //   05 TRAN-CAT-BAL       PIC S9(09)V99.  11   -> at 17, ending at 28
            //   05 FILLER             PIC X(22).      22   -> at 28, ending at 50
            int keyByAddition = ACCT_ID_WIDTH + TYPE_CD_WIDTH + CAT_CD_WIDTH;
            assertThat(keyByAddition).isEqualTo(SEVENTEEN);
            assertThat(TranCatBalRecord.TRAN_CAT_KEY_LENGTH).isEqualTo(keyByAddition);

            // Each component sits immediately after the one before it, with no gap and no overlap.
            assertThat(TranCatBalRecord.TRANCAT_ACCT_ID_OFFSET).isZero();
            assertThat(TranCatBalRecord.TRANCAT_TYPE_CD_OFFSET)
                    .isEqualTo(TranCatBalRecord.TRANCAT_ACCT_ID_OFFSET + ACCT_ID_WIDTH);
            assertThat(TranCatBalRecord.TRANCAT_CD_OFFSET)
                    .isEqualTo(TranCatBalRecord.TRANCAT_TYPE_CD_OFFSET + TYPE_CD_WIDTH);
            assertThat(TranCatBalRecord.TRAN_CAT_BAL_OFFSET)
                    .isEqualTo(TranCatBalRecord.TRANCAT_CD_OFFSET + CAT_CD_WIDTH);
            assertThat(TranCatBalRecord.FILLER_OFFSET)
                    .isEqualTo(TranCatBalRecord.TRAN_CAT_BAL_OFFSET + BALANCE_WIDTH);

            // And the record ends exactly where FILLER does - gate G19, and the check that fails the
            // instant a FILLER span is dropped (gate G21's structural half).
            assertThat(TranCatBalRecord.FILLER_OFFSET + FILLER_WIDTH).isEqualTo(FIFTY);
            assertThat(TranCatBalRecord.RECORD_LENGTH).isEqualTo(FIFTY);
            assertThat(TranCatBalRepository.RECORD_LENGTH).isEqualTo(FIFTY);
            assertThat(TranCatBalRecord.newInstance(ASCII).encode()).hasSize(FIFTY);
        }

        @Test
        @DisplayName("the signed span starts at 17 and is 11 bytes - CVTRA02Y's starts at 16 and is 6")
        void signedSpanStartsAtSeventeenNotSixteen() {
            // The offset-level form of the near-miss, and the one that actually bites: both records are
            // 50 bytes, so a type confusion survives every width check and then silently decodes the
            // balance from the wrong six bytes. TRAN-CAT-BAL S9(09)V99 is 11 bytes at 17; CVTRA02Y's
            // DIS-INT-RATE S9(04)V99 is 6 bytes at 16 (account.model.DisclosureGroupRecord, not here).
            assertThat(TranCatBalRecord.TRAN_CAT_BAL_OFFSET).isEqualTo(SEVENTEEN);
            assertThat(TranCatBalRecord.TRAN_CAT_BAL_LENGTH).isEqualTo(BALANCE_WIDTH);
            assertThat(TranCatBalRecord.TRAN_CAT_BAL_INTEGER_DIGITS).isEqualTo(9);

            assertThat(TranCatBalRecord.TRAN_CAT_BAL_OFFSET)
                    .isNotEqualTo(DISCLOSURE_GROUP_RATE_OFFSET);
            assertThat(TranCatBalRecord.TRAN_CAT_BAL_LENGTH)
                    .isNotEqualTo(DISCLOSURE_GROUP_RATE_WIDTH);
            // The signed span begins exactly where the key ends in both layouts, which is why the two
            // offsets differ by exactly the one byte their key widths differ by.
            assertThat(TranCatBalRecord.TRAN_CAT_BAL_OFFSET - DISCLOSURE_GROUP_RATE_OFFSET)
                    .isEqualTo(TranCatBalRepository.KEY_LENGTH
                            - TranCatBalRepository.DISCLOSURE_GROUP_KEY_LENGTH)
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("the key is not 6 bytes - CVTRA04Y declares an identically-named TRAN-CAT-KEY")
        void keyIsNotTheSixByteTranCategoryKey() {
            // app/cpy/CVTRA04Y.cpy's TRAN-CAT-RECORD declares a group with literally this same name -
            // 05 TRAN-CAT-KEY - built from TRAN-TYPE-CD X(02) + TRAN-CAT-CD 9(04) = 6 bytes. Neither
            // COBOL group is renamed to disambiguate them: a field name is part of the contract, and
            // silently correcting the source is exactly what B4 forbids. The 6-byte one is exercised in
            // TranCategoryRepositoryTest; this assertion only pins down which of the two is ours.
            assertThat(TranCatBalRepository.TRAN_CATEGORY_KEY_LENGTH)
                    .isEqualTo(TYPE_CD_WIDTH + CAT_CD_WIDTH)
                    .isEqualTo(6);
            assertThat(TranCatBalRepository.KEY_LENGTH)
                    .isEqualTo(SEVENTEEN)
                    .isNotEqualTo(TranCatBalRepository.TRAN_CATEGORY_KEY_LENGTH);
            // All three layouts are mutually distinguishable, so no pair can be crossed unnoticed.
            assertThat(List.of(TranCatBalRepository.KEY_LENGTH,
                    TranCatBalRepository.DISCLOSURE_GROUP_KEY_LENGTH,
                    TranCatBalRepository.TRAN_CATEGORY_KEY_LENGTH)).doesNotHaveDuplicates();
        }

        @Test
        @DisplayName("TRANCAT-TYPE-CD is character data, not a number - PIC X(02) keeps its leading zero")
        void typeCodeIsCharacterDataAndNotANumber() throws NoSuchMethodException {
            // The picture is X, so the accessor's declared type must be String. An int accessor would
            // decode the fixture's "01" as 1, re-encode it as "1 " under the PIC X move rule, and shift
            // nothing - it would simply write a different key that no read would ever match again.
            Method accessor = TranCatBalRecord.class.getMethod("trancatTypeCd");
            assertThat(accessor.getReturnType()).isEqualTo(String.class);
            assertThat(TranCatBalRecord.class.getMethod("trancatTypeCd", String.class)
                    .getReturnType()).isEqualTo(TranCatBalRecord.class);

            // TranCatKey carries it as a String for the same reason.
            assertThat(new TranCatKey(1L, FIXTURE_TYPE_CD, FIXTURE_CAT_CD).trancatTypeCd())
                    .isEqualTo(FIXTURE_TYPE_CD);
            // The leading zero is significant: "01" and "1" are different keys, and neither is the
            // number 1. TRANCAT-CD, whose picture is 9(04), is the int the other two are not.
            assertThat(new TranCatKey(1L, "01", 1).image(ASCII))
                    .isNotEqualTo(new TranCatKey(1L, "1", 1).image(ASCII));
            assertThat(TranCatBalRecord.class.getMethod("trancatCd").getReturnType())
                    .isEqualTo(int.class);
        }

        @Test
        @DisplayName("the declared geometry verifies, and reports the key width")
        void declaredGeometryVerifies() {
            assertThat(TranCatBalRepository.verifyDeclaredGeometry()).isEqualTo(SEVENTEEN);
            assertThat(TranCatBalRepository.RECORD_LENGTH).isEqualTo(FIFTY);
            assertThat(TranCatBalRepository.KEY_OFFSET).isZero();
            assertThat(TranCatBalRepository.RECORD_IMAGE_COLUMN_INDEX).isEqualTo(1);
            assertThat(TranCatBalRepository.APPL_RESULT_FATAL).isEqualTo(12);
            assertThat(TranCatBalRepository.DD_NAME).isEqualTo("TCATBALF");
            assertThat(TranCatBalRepository.COPYBOOK).isEqualTo("CVTRA01Y");
        }

        @Test
        @DisplayName("the balance is scale 2 and rounds DOWN, because ROUNDED appears nowhere")
        void balancePolicyIsScaleTwoTruncating() {
            assertThat(TranCatBalRepository.TRAN_CAT_BAL_SCALE).isEqualTo(2);
            assertThat(TranCatBalRepository.TRAN_CAT_BAL_ROUNDING).isEqualTo(RoundingMode.DOWN);
        }

        @Test
        @DisplayName("the key image is 17 characters for the shortest possible components")
        void keyImageIsSeventeenCharactersForShortComponents() {
            // acctId 1 zero-fills to 11; a lower-case PIC X(02) is preserved verbatim; catCd 5 -> 0005.
            assertThat(new TranCatKey(1L, "ab", 5).image(ASCII))
                    .hasSize(SEVENTEEN)
                    .isEqualTo("00000000001ab0005");
            assertThat(new TranCatKey(1L, "ab", 5).toByteArray(ASCII)).hasSize(SEVENTEEN);
        }

        @Test
        @DisplayName("a one-character type code is space-padded on the right, as PIC X requires")
        void shortTypeCodeIsSpacePadded() {
            assertThat(new TranCatKey(1L, "a", 5).image(ASCII)).isEqualTo("00000000001a 0005");
        }
    }

    // =============================================================================================
    // Construction: the binding and the geometry it must declare.
    // =============================================================================================

    @Nested
    @DisplayName("construction - everything provable before the context finishes starting")
    class Construction {

        @Test
        @DisplayName("a valid binding yields a repository reporting the configured dataset")
        void validBindingIsAccepted() {
            TranCatBalRepository repository = repository(new JdbcTemplate());
            assertThat(repository.datasetName()).isEqualTo(TEST_DSNAME);
            assertThat(repository.recordLength()).isEqualTo(FIFTY);
            assertThat(repository.keyLength()).isEqualTo(SEVENTEEN);
            assertThat(repository.datasetCharset()).isEqualTo(ASCII);
        }

        @Test
        @DisplayName("an omitted key length is accepted, as application.yml may leave it unstated")
        void omittedKeyLengthIsAccepted() {
            assertThatCode(() -> new TranCatBalRepository(new JdbcTemplate(),
                    bindings(TEST_DSNAME, FIFTY, null), ASCII, RecordImageForm.CHARACTER))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("a declared key length of 16 is refused, and the diagnostic names CVTRA02Y")
        void sixteenByteKeyLengthIsRefusedByName() {
            assertThatIllegalStateException()
                    .isThrownBy(() -> new TranCatBalRepository(new JdbcTemplate(),
                            bindings(TEST_DSNAME, FIFTY, 16), ASCII, RecordImageForm.CHARACTER))
                    .withMessageContaining("key length of 16")
                    .withMessageContaining("CVTRA02Y")
                    .withMessageContaining("key-length to 17");
        }

        @ParameterizedTest
        @ValueSource(ints = {6, 11, 18, 50})
        @DisplayName("any key length other than 17 is refused")
        void anyOtherKeyLengthIsRefused(int declared) {
            assertThatIllegalStateException()
                    .isThrownBy(() -> new TranCatBalRepository(new JdbcTemplate(),
                            bindings(TEST_DSNAME, FIFTY, declared), ASCII, RecordImageForm.CHARACTER))
                    .withMessageContaining("key length of " + declared);
        }

        @ParameterizedTest
        @ValueSource(ints = {49, 51, 60, 300})
        @DisplayName("any record length other than 50 is refused")
        void anyOtherRecordLengthIsRefused(int declared) {
            assertThatIllegalStateException()
                    .isThrownBy(() -> new TranCatBalRepository(new JdbcTemplate(),
                            bindings(TEST_DSNAME, declared, SEVENTEEN), ASCII,
                            RecordImageForm.CHARACTER))
                    .withMessageContaining("record length of " + declared)
                    .withMessageContaining("CVTRA01Y");
        }

        @Test
        @DisplayName("an unconfigured DD name is refused, naming the configuration key")
        void unconfiguredDdNameIsRefused() {
            assertThatIllegalStateException()
                    .isThrownBy(() -> new TranCatBalRepository(new JdbcTemplate(),
                            new DatasetBindings(), ASCII, RecordImageForm.CHARACTER))
                    .withMessageContaining(TranCatBalRepository.DD_NAME);
        }

        @ParameterizedTest
        @ValueSource(strings = {"", "   "})
        @DisplayName("a blank dataset name is refused rather than defaulted")
        void blankDatasetNameIsRefused(String dsname) {
            assertThatIllegalStateException()
                    .isThrownBy(() -> new TranCatBalRepository(new JdbcTemplate(),
                            bindings(dsname, FIFTY, SEVENTEEN), ASCII, RecordImageForm.CHARACTER))
                    .withMessageContaining("dsname");
        }

        @Test
        @DisplayName("a null dataset name is refused rather than defaulted")
        void nullDatasetNameIsRefused() {
            assertThatIllegalStateException()
                    .isThrownBy(() -> new TranCatBalRepository(new JdbcTemplate(),
                            bindings(null, FIFTY, SEVENTEEN), ASCII, RecordImageForm.CHARACTER))
                    .withMessageContaining("declares no dataset name");
        }

        @Test
        @DisplayName("a missing collaborator is a wiring defect and is reported as one")
        void missingCollaboratorsAreRefused() {
            assertThatNullPointerException().isThrownBy(() -> new TranCatBalRepository(null,
                    validBindings(), ASCII, RecordImageForm.CHARACTER));
            assertThatNullPointerException().isThrownBy(() -> new TranCatBalRepository(
                    new JdbcTemplate(), null, ASCII, RecordImageForm.CHARACTER));
            assertThatNullPointerException().isThrownBy(() -> new TranCatBalRepository(
                    new JdbcTemplate(), validBindings(), null, RecordImageForm.CHARACTER));
            assertThatNullPointerException().isThrownBy(() -> new TranCatBalRepository(
                    new JdbcTemplate(), validBindings(), ASCII, null));
        }

        @Test
        @DisplayName("no dataset name is hard-coded: the configured one is the one used")
        void datasetNameComesOnlyFromConfiguration() {
            TranCatBalRepository other = new TranCatBalRepository(new JdbcTemplate(),
                    bindings("SOME.OTHER.DATASET", FIFTY, SEVENTEEN), ASCII,
                    RecordImageForm.CHARACTER);
            assertThat(other.datasetName()).isEqualTo("SOME.OTHER.DATASET");
        }
    }

    // =============================================================================================
    // Statelessness - gate G53.
    // =============================================================================================

    @Nested
    @DisplayName("statelessness - a singleton that shares nothing between executions")
    class Statelessness {

        @Test
        @DisplayName("every field is final, so a singleton holds nothing mutable")
        void everyFieldIsFinal() {
            for (Field field : TranCatBalRepository.class.getDeclaredFields()) {
                assertThat(Modifier.isFinal(field.getModifiers()))
                        .as("field %s must be final", field.getName()).isTrue();
            }
        }

        @Test
        @DisplayName("every static member is final, so there is no static mutable state")
        void everyStaticFieldIsFinal() {
            for (Field field : TranCatBalRepository.class.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) {
                    assertThat(Modifier.isFinal(field.getModifiers()))
                            .as("static field %s must be final", field.getName()).isTrue();
                }
            }
        }

        @Test
        @DisplayName("two handles browse independently, so neither consumes the other's records")
        void twoHandlesBrowseIndependently() {
            TranCatBalRepository repository = repository(seeded(fixtureRows()));
            try (TranCatBalFile first = repository.open(OpenMode.INPUT);
                    TranCatBalFile second = repository.open(OpenMode.INPUT)) {
                String firstOfFirst = first.readNext().record().orElseThrow().tranCatKeyImage();
                String firstOfSecond = second.readNext().record().orElseThrow().tranCatKeyImage();
                // Each handle starts before the first record, so both see it.
                assertThat(firstOfFirst).isEqualTo(firstOfSecond);
                String secondOfFirst = first.readNext().record().orElseThrow().tranCatKeyImage();
                assertThat(secondOfFirst).isNotEqualTo(firstOfFirst);
                // The second handle is unaffected by the first's advance.
                assertThat(second.readNext().record().orElseThrow().tranCatKeyImage())
                        .isEqualTo(secondOfFirst);
            }
        }
    }

    // =============================================================================================
    // CBACT04C's sequential browse - 0000-TCATBALF-OPEN, 1000-TCATBALF-GET-NEXT, 9000-TCATBALF-CLOSE.
    // =============================================================================================

    @Nested
    @DisplayName("the sequential browse - app/cbl/CBACT04C.cbl:L234-L348 and L522-L538")
    class SequentialBrowse {

        @ParameterizedTest
        @EnumSource(OpenMode.class)
        @DisplayName("both OPEN verbs succeed against a reachable dataset and name themselves")
        void bothOpenModesSucceed(OpenMode mode) {
            try (TranCatBalFile file = repository(seeded(fixtureRows())).open(mode)) {
                assertThat(file.openStatus()).isEqualTo(FileStatus.OK);
                assertThat(file.openOutcome()).isEqualTo(Outcome.OK);
                assertThat(file.openApplResult()).isEqualTo(FileStatus.APPL_AOK);
                assertThat(file.mode()).isEqualTo(mode);
                assertThat(file.mode().cobolVerb()).startsWith("OPEN ");
                assertThat(file.datasetName()).isEqualTo(TEST_DSNAME);
                assertThat(file.isClosed()).isFalse();
            }
        }

        @Test
        @DisplayName("a null open mode is refused")
        void nullOpenModeIsRefused() {
            assertThatNullPointerException()
                    .isThrownBy(() -> repository(new JdbcTemplate()).open(null));
        }

        @Test
        @DisplayName("all 50 fixture records come back in ascending physical key order")
        void browseReturnsEveryRecordInKeyOrder() {
            List<String> rows = fixtureRows();
            assertThat(rows).hasSize(FIXTURE_RECORDS);
            try (TranCatBalFile file = repository(seeded(rows)).open(OpenMode.INPUT)) {
                List<String> keys = drainKeys(file, FIXTURE_RECORDS + 10);
                assertThat(keys).hasSize(FIXTURE_RECORDS);
                assertThat(keys).isSorted();
                assertThat(keys.get(0)).isEqualTo("00000000001010001");
                assertThat(keys.get(FIXTURE_RECORDS - 1)).isEqualTo("00000000050010001");
                // The browse order IS the stored order, which is what CBACT04C's account break needs.
                assertThat(keys).isEqualTo(rows.stream().map(r -> r.substring(0, SEVENTEEN)).toList());
            }
        }

        @Test
        @DisplayName("ordering is asserted even when the rows were stored out of key order")
        void browseOrdersRowsStoredOutOfOrder() {
            List<String> rows = new ArrayList<>(fixtureRows());
            java.util.Collections.reverse(rows);
            try (TranCatBalFile file = repository(seeded(rows)).open(OpenMode.INPUT)) {
                List<String> keys = drainKeys(file, FIXTURE_RECORDS + 10);
                assertThat(keys).hasSize(FIXTURE_RECORDS).isSorted();
                assertThat(keys.get(0)).isEqualTo("00000000001010001");
            }
        }

        @Test
        @DisplayName("end of file is status '10' with APPL-RESULT 16, and is idempotent")
        void endOfFileIsTenAndSixteenAndIdempotent() {
            try (TranCatBalFile file = repository(seeded(new ArrayList<>())).open(OpenMode.INPUT)) {
                ReadResult first = file.readNext();
                assertThat(first.isEndOfFile()).isTrue();
                assertThat(first.status()).isEqualTo(FileStatus.END_OF_FILE);
                assertThat(first.applResult()).isEqualTo(FileStatus.APPL_EOF);
                assertThat(first.record()).isEmpty();
                assertThat(first.isFound()).isFalse();
                assertThat(first.isNotFound()).isFalse();
                assertThat(first.isOther()).isFalse();
                // Reported again: the COBOL loop stops on the flag and never resumes.
                assertThat(file.readNext().isEndOfFile()).isTrue();
            }
        }

        @Test
        @DisplayName("an unreachable dataset yields a failed open whose status every operation repeats")
        void failedOpenPropagatesItsStatus() {
            TranCatBalFile file = repository(unreachable()).open(OpenMode.INPUT);
            assertThat(file.openStatus()).isEqualTo(TranCatBalRepository.PERMANENT_ERROR_STATUS);
            assertThat(file.openOutcome()).isEqualTo(Outcome.OTHER);
            assertThat(file.openApplResult()).isEqualTo(TranCatBalRepository.APPL_RESULT_FATAL);
            // A caller that ignored the status cannot mistake an unreachable dataset for an empty one.
            assertThat(file.readNext().isEndOfFile()).isFalse();
            assertThat(file.readNext().status())
                    .isEqualTo(TranCatBalRepository.PERMANENT_ERROR_STATUS);
            assertThat(file.readByKey(1L, FIXTURE_TYPE_CD, FIXTURE_CAT_CD).status())
                    .isEqualTo(TranCatBalRepository.PERMANENT_ERROR_STATUS);
            assertThat(file.write(recordFor(1L, "1.00")).status())
                    .isEqualTo(TranCatBalRepository.PERMANENT_ERROR_STATUS);
            assertThat(file.rewrite(recordFor(1L, "1.00")).status())
                    .isEqualTo(TranCatBalRepository.PERMANENT_ERROR_STATUS);
            // Closing a file that never opened reports the open's own status, and issues no probe.
            assertThat(file.closeFile()).isEqualTo(TranCatBalRepository.PERMANENT_ERROR_STATUS);
            assertThat(file.closeFile()).isEqualTo(TranCatBalRepository.PERMANENT_ERROR_STATUS);
        }

        @Test
        @DisplayName("a refused browse statement is reported as a status, not thrown")
        void refusedBrowseIsReportedAsStatus() throws SQLException {
            try (TranCatBalFile file = repository(describingThenRefusing()).open(OpenMode.INPUT)) {
                ReadResult result = file.readNext();
                assertThat(result.isOther()).isTrue();
                assertThat(result.status()).isEqualTo(TranCatBalRepository.PERMANENT_ERROR_STATUS);
                assertThat(result.applResult()).isEqualTo(TranCatBalRepository.APPL_RESULT_FATAL);
            }
        }

        @Test
        @DisplayName("a browsed row with no record image is a permanent error, not an end of file")
        void browsedRowWithNoImageIsNotEndOfFile() throws SQLException {
            try (TranCatBalFile file =
                    repository(describingThenReturningNoImage()).open(OpenMode.INPUT)) {
                ReadResult result = file.readNext();
                assertThat(result.isEndOfFile()).isFalse();
                assertThat(result.isOther()).isTrue();
                assertThat(result.cicsResp()).hasValue(FileStatus.INVREQ);
            }
        }

        @Test
        @DisplayName("a row of the wrong width is reported as LENGERR and does not advance the browse")
        void wrongWidthRowIsReportedAndDoesNotAdvance() {
            // A 40-character row in a 50-wide column: shorter than TRAN-CAT-BAL-RECORD.
            JdbcTemplate template = seeded(List.of("0".repeat(40)));
            try (TranCatBalFile file = repository(template).open(OpenMode.INPUT)) {
                ReadResult first = file.readNext();
                assertThat(first.isFound()).isFalse();
                assertThat(first.isOther()).isTrue();
                assertThat(first.cicsResp()).hasValue(FileStatus.LENGERR);
                // The position did not advance, so the same failing row is re-read rather than skipped.
                assertThat(file.readNext().cicsResp()).hasValue(FileStatus.LENGERR);
            }
        }

        @Test
        @DisplayName("closing reports '00' and is idempotent; operating afterwards throws")
        void closeIsIdempotentAndClosedHandleRefusesOperations() {
            TranCatBalFile file = repository(seeded(fixtureRows())).open(OpenMode.INPUT);
            assertThat(file.closeFile()).isEqualTo(FileStatus.OK);
            assertThat(file.isClosed()).isTrue();
            assertThat(file.closeFile()).isEqualTo(FileStatus.OK);
            assertThat(file.closeApplResult()).isEqualTo(FileStatus.APPL_AOK);
            assertThatIllegalStateException().isThrownBy(file::readNext)
                    .withMessageContaining("has been closed");
            assertThatIllegalStateException()
                    .isThrownBy(() -> file.readByKey(1L, FIXTURE_TYPE_CD, FIXTURE_CAT_CD));
            assertThatIllegalStateException()
                    .isThrownBy(() -> file.write(recordFor(1L, "1.00")));
            assertThatIllegalStateException()
                    .isThrownBy(() -> file.rewrite(recordFor(1L, "1.00")));
        }

        @Test
        @DisplayName("a close whose dataset has become unreachable reports a permanent error")
        void closeOfAnUnreachableDatasetReportsPermanentError() throws SQLException {
            // Describes once for the OPEN, then refuses the second describe the CLOSE issues.
            DataSource dataSource = Mockito.mock(DataSource.class);
            Connection connection = Mockito.mock(Connection.class);
            Statement statement = Mockito.mock(Statement.class);
            ResultSet probe = Mockito.mock(ResultSet.class);
            ResultSetMetaData metaData = Mockito.mock(ResultSetMetaData.class);
            Mockito.when(dataSource.getConnection()).thenReturn(connection);
            Mockito.when(connection.createStatement()).thenReturn(statement);
            Mockito.when(statement.executeQuery(Mockito.anyString()))
                    .thenReturn(probe)
                    .thenThrow(new SQLException("the dataset has gone"));
            Mockito.when(probe.getMetaData()).thenReturn(metaData);
            Mockito.when(metaData.getColumnCount()).thenReturn(1);
            Mockito.when(metaData.getColumnName(1)).thenReturn(RECORD_IMAGE_COLUMN);

            TranCatBalFile file = repository(new JdbcTemplate(dataSource)).open(OpenMode.INPUT);
            assertThat(file.openStatus()).isEqualTo(FileStatus.OK);
            assertThat(file.closeFile()).isEqualTo(TranCatBalRepository.PERMANENT_ERROR_STATUS);
        }

        @Test
        @DisplayName("closeApplResult reports 12 when the close could not confirm the dataset")
        void closeApplResultReportsFatalOnFailure() {
            TranCatBalFile file = repository(unreachable()).open(OpenMode.INPUT);
            assertThat(file.closeApplResult()).isEqualTo(TranCatBalRepository.APPL_RESULT_FATAL);
        }
    }

    // =============================================================================================
    // CBTRN02C's keyed read - 2700-UPDATE-TCATBAL, and the '00' OR '23' guard at L481.
    // =============================================================================================

    /**
     * The keyed read, and the reason {@code '23'} is not an error here.
     *
     * <p>{@code 2700-UPDATE-TCATBAL} reads by the composite key and then accepts
     * {@code IF TCATBALF-STATUS = '00' OR '23'} [{@code app/cbl/CBTRN02C.cbl:481}]. Anything else moves the
     * status to {@code IO-STATUS}, displays it and abends. So "not found" is a success-shaped outcome that
     * the caller acts on - it is what sets {@code WS-CREATE-TRANCAT-REC} to {@code 'Y'} at {@code :478} and
     * sends the program down the create path - and it must be reachable without an exception and without
     * being folded into the generic failure arm.
     *
     * <p>Every status arm this dataset can produce is driven here, which is what acceptance gate G47 asks
     * for: {@code '00'}, {@code '23'}, and the "other" arm that leads to the abend. {@code '10'} belongs to
     * the browse and is driven in {@link SequentialBrowse}; {@code '22'} belongs to the write and is driven
     * in {@link Write}.
     */
    @Nested
    @DisplayName("the keyed read - app/cbl/CBTRN02C.cbl:L467-L501, where '23' is a normal outcome")
    class KeyedRead {

        @Test
        @DisplayName("a seeded record is found, with both ladders reporting success")
        void seededRecordIsFound() {
            ReadResult result = repository(seeded(fixtureRows()))
                    .readByKey(1L, FIXTURE_TYPE_CD, FIXTURE_CAT_CD);
            assertThat(result.isFound()).isTrue();
            assertThat(result.status()).isEqualTo(FileStatus.OK);
            assertThat(result.outcome()).isEqualTo(Outcome.OK);
            assertThat(result.record()).isPresent();
            assertThat(result.record().orElseThrow().trancatAcctId()).isEqualTo(1L);
            assertThat(result.record().orElseThrow().trancatTypeCd()).isEqualTo(FIXTURE_TYPE_CD);
            assertThat(result.record().orElseThrow().trancatCd()).isEqualTo(FIXTURE_CAT_CD);
            assertThat(result.record().orElseThrow().recordLength()).isEqualTo(FIFTY);
            assertThat(result.diagnostic()).isEmpty();
            assertThat(result.applResult()).isEqualTo(FileStatus.APPL_AOK);
            assertThat(result.applResultWhereNotFoundIsNormal()).isEqualTo(FileStatus.APPL_AOK);
            assertThat(result.isOkOrNotFound()).isTrue();
            assertThat(result.statusImage()).isEqualTo(FileStatus.toStatusImage(FileStatus.OK));
        }

        @Test
        @DisplayName("a missing key yields '23' WITHOUT throwing - the INVALID KEY branch")
        void missingKeyYieldsTwentyThreeWithoutThrowing() {
            TranCatBalRepository repository = repository(seeded(fixtureRows()));
            ReadResult[] captured = new ReadResult[1];
            assertThatCode(() -> captured[0] = repository.readByKey(ABSENT_ACCT_ID, "ZZ", 9999))
                    .doesNotThrowAnyException();
            ReadResult result = captured[0];
            assertThat(result.isNotFound()).isTrue();
            assertThat(result.status()).isEqualTo(FileStatus.NOT_FOUND);
            assertThat(result.record()).isEmpty();
            assertThat(result.isFound()).isFalse();
            assertThat(result.isEndOfFile()).isFalse();
            assertThat(result.isOther()).isFalse();
        }

        @Test
        @DisplayName("finding DB-05: an unreadable row is not reported as '23', which would authorise a WRITE")
        void anUnreadableRowIsNotReportedAsAbsent() {
            // A row the dataset holds and cannot present. All three components of TRAN-CAT-KEY live inside
            // the record image, so SQL evaluates the keyed LIKE against it as UNKNOWN and the read matches
            // nothing - which looks exactly like INVALID KEY and is not. '23' here is an INSTRUCTION:
            // 2700-UPDATE-TCATBAL falls through to 2700-A-CREATE-TCATBAL-REC and WRITEs a record whose key
            // may already be present.
            List<String> withUnreadable = new ArrayList<>(fixtureRows());
            withUnreadable.add(null);

            ReadResult result = repository(seeded(withUnreadable))
                    .readByKey(ABSENT_ACCT_ID, "ZZ", 9999);

            assertThat(result.isNotFound())
                    .as("the unreadable row's key cannot be known, so no absence can be asserted")
                    .isFalse();
            assertThat(result.isOther()).isTrue();
            assertThat(result.status()).isEqualTo(TranCatBalRepository.PERMANENT_ERROR_STATUS);
            assertThat(result.cicsResp())
                    .as("the same arm the visible form of this condition already reports")
                    .hasValue(FileStatus.INVREQ);
            assertThat(result.isOkOrNotFound())
                    .as("CBTRN02C's L481 ladder must NOT see this as normal, or it would create the record")
                    .isFalse();
        }

        @Test
        @DisplayName("finding DB-05: a genuinely absent key still reports '23' and still authorises the create")
        void aGenuinelyAbsentKeyIsStillNotFound() {
            // No row of the dataset is unreadable, so the absence is established rather than assumed and
            // the create at app/cbl/CBTRN02C.cbl:L503-L524 is still the right next step.
            ReadResult result = repository(seeded(fixtureRows()))
                    .readByKey(ABSENT_ACCT_ID, "ZZ", 9999);

            assertThat(result.isNotFound()).isTrue();
            assertThat(result.status()).isEqualTo(FileStatus.NOT_FOUND);
            assertThat(result.isOkOrNotFound()).isTrue();
        }

        @Test
        @DisplayName("finding DB-05: a read that found its record is unaffected by an unreadable row")
        void aFoundRecordIsUnaffectedByAnUnreadableRowElsewhere() {
            List<String> withUnreadable = new ArrayList<>(fixtureRows());
            withUnreadable.add(null);

            // A VSAM READ of a key that resolves does not fail because another record in the cluster is
            // damaged, so the proof is confined to the not-found path.
            assertThat(repository(seeded(withUnreadable))
                    .readByKey(2L, FIXTURE_TYPE_CD, FIXTURE_CAT_CD).isFound()).isTrue();
        }

        @Test
        @DisplayName("finding DB-05: a refused probe is reported rather than reported as absent")
        void aRefusedProbeIsReportedRatherThanAssumedAbsent() {
            JdbcTemplate refusingTheProbe = Mockito.spy(seeded(fixtureRows()));
            // The keyed read is answered for real; the very next creator-bound query - the probe - is not.
            Mockito.doCallRealMethod()
                    .doThrow(new DataAccessResourceFailureException("the probe cannot be answered"))
                    .when(refusingTheProbe).query(Mockito.any(PreparedStatementCreator.class),
                            Mockito.<RowMapper<Object>>any());

            ReadResult result = repository(refusingTheProbe).readByKey(ABSENT_ACCT_ID, "ZZ", 9999);

            assertThat(result.isNotFound())
                    .as("the probe established nothing, so the absence stays unproved")
                    .isFalse();
            assertThat(result.status()).isEqualTo(TranCatBalRepository.PERMANENT_ERROR_STATUS);
            assertThat(result.diagnostic())
                    .as("the driver's own diagnosis reaches the caller")
                    .isPresent();
        }

        @Test
        @DisplayName("the two consumers' ladders genuinely differ on '23' - 0 for CBTRN02C, 12 for CBACT04C")
        void theTwoLaddersDifferOnNotFound() {
            ReadResult notFound = repository(seeded(fixtureRows()))
                    .readByKey(ABSENT_ACCT_ID, "ZZ", 9999);
            // CBTRN02C L481: IF TCATBALF-STATUS = '00' OR '23' -> MOVE 0 TO APPL-RESULT.
            assertThat(notFound.isOkOrNotFound()).isTrue();
            assertThat(notFound.applResultWhereNotFoundIsNormal()).isEqualTo(FileStatus.APPL_AOK);
            // CBACT04C L327-L335: only '00' is 0, '10' is 16, everything else is 12.
            assertThat(notFound.applResult()).isEqualTo(TranCatBalRepository.APPL_RESULT_FATAL);
        }

        @Test
        @DisplayName("the composite-key form reads the same record as the three-component form")
        void compositeKeyFormAgreesWithComponentForm() {
            TranCatBalRepository repository = repository(seeded(fixtureRows()));
            ReadResult byComponents = repository.readByKey(2L, FIXTURE_TYPE_CD, FIXTURE_CAT_CD);
            ReadResult byKey = repository.readByKey(
                    new TranCatKey(2L, FIXTURE_TYPE_CD, FIXTURE_CAT_CD));
            assertThat(byComponents.isFound()).isTrue();
            assertThat(byKey.isFound()).isTrue();
            assertThat(byKey.record().orElseThrow().rawImage())
                    .isEqualTo(byComponents.record().orElseThrow().rawImage());
        }

        @Test
        @DisplayName("a null key is refused")
        void nullKeyIsRefused() {
            TranCatBalRepository repository = repository(new JdbcTemplate());
            assertThatNullPointerException().isThrownBy(() -> repository.readByKey(null));
            assertThatNullPointerException()
                    .isThrownBy(() -> repository.readByKey(1L, null, FIXTURE_CAT_CD));
        }

        @Test
        @DisplayName("an unreachable dataset is reported as a status carrying the driver's diagnosis")
        void unreachableDatasetIsReportedAsStatus() {
            ReadResult result = repository(unreachable())
                    .readByKey(1L, FIXTURE_TYPE_CD, FIXTURE_CAT_CD);
            assertThat(result.isOther()).isTrue();
            assertThat(result.status()).isEqualTo(TranCatBalRepository.PERMANENT_ERROR_STATUS);
            assertThat(result.diagnostic()).isPresent();
            assertThat(result.applResult()).isEqualTo(TranCatBalRepository.APPL_RESULT_FATAL);
            assertThat(result.applResultWhereNotFoundIsNormal())
                    .isEqualTo(TranCatBalRepository.APPL_RESULT_FATAL);
            assertThat(result.isOkOrNotFound()).isFalse();
        }

        @Test
        @DisplayName("a refused keyed statement is reported as a status")
        void refusedKeyedStatementIsReportedAsStatus() throws SQLException {
            ReadResult result = repository(describingThenRefusing())
                    .readByKey(1L, FIXTURE_TYPE_CD, FIXTURE_CAT_CD);
            assertThat(result.isOther()).isTrue();
            assertThat(result.diagnostic()).isPresent();
        }

        @Test
        @DisplayName("a row present but unreadable is INVREQ, never '23' - it must not trigger a create")
        void unreadableRowIsNotReportedAsNotFound() throws SQLException {
            ReadResult result = repository(describingThenReturningNoImage())
                    .readByKey(1L, FIXTURE_TYPE_CD, FIXTURE_CAT_CD);
            assertThat(result.isNotFound()).isFalse();
            assertThat(result.isOther()).isTrue();
            assertThat(result.cicsResp()).hasValue(FileStatus.INVREQ);
            assertThat(result.cicsResp2()).isEqualTo(FileStatus.NO_REASON_CODE);
        }

        @Test
        @DisplayName("a stored row of the wrong width is LENGERR rather than padded into plausible money")
        void wrongWidthRowIsLengErr() {
            JdbcTemplate template = seeded(List.of("0".repeat(30)));
            ReadResult result = repository(template).readByKey(0L, "00", 0);
            assertThat(result.isFound()).isFalse();
            assertThat(result.cicsResp()).hasValue(FileStatus.LENGERR);
        }

        @Test
        @DisplayName("the handle's keyed read reuses the shape its OPEN resolved")
        void handleKeyedReadWorks() {
            try (TranCatBalFile file = repository(seeded(fixtureRows())).open(OpenMode.I_O)) {
                assertThat(file.readByKey(3L, FIXTURE_TYPE_CD, FIXTURE_CAT_CD).isFound()).isTrue();
                assertThat(file.readByKey(new TranCatKey(4L, FIXTURE_TYPE_CD, FIXTURE_CAT_CD))
                        .isFound()).isTrue();
                assertThat(file.readByKey(ABSENT_ACCT_ID, "ZZ", 9999).isNotFound()).isTrue();
                assertThatNullPointerException().isThrownBy(() -> file.readByKey(null));
            }
        }

        @Test
        @DisplayName("a key containing LIKE metacharacters cannot match another record")
        void keyMetacharactersAreEscaped() {
            // '_' matches any single character in an unescaped LIKE pattern. Escaped, it matches only
            // itself - so a type code of "__" must NOT return the fixture's "01" rows.
            ReadResult result = repository(seeded(fixtureRows()))
                    .readByKey(1L, "__", FIXTURE_CAT_CD);
            assertThat(result.isNotFound()).isTrue();
        }
    }

    // =============================================================================================
    // INITIALIZE TRAN-CAT-BAL-RECORD - app/cbl/CBTRN02C.cbl:504, and the FILLER it does not touch.
    // =============================================================================================

    /**
     * The {@code INITIALIZE} that opens {@code 2700-A-CREATE-TCATBAL-REC}, and the one detail of it that a
     * reasonable reader would get wrong.
     *
     * <p>{@code INITIALIZE} without a {@code REPLACING} phrase sets every elementary item to the figurative
     * constant for its category - {@code ZERO} for a numeric item, {@code SPACE} for an alphanumeric one -
     * and <strong>skips {@code FILLER}</strong>, because {@code FILLER} is unnamed and no statement can
     * refer to it. In {@code 2700-A} the {@code INITIALIZE} at {@code :504} runs immediately after the
     * failed {@code READ} at {@code :474}, so the record area still holds the bytes of whatever was read
     * into it last, and the {@code WRITE} at {@code :510} sends those 22 reserved bytes to the dataset.
     *
     * <p>That is preserved rather than tidied (B5). Blanking the reserved span would be a 22-byte
     * difference in every created record, which is exactly the class of silent divergence the field-level
     * differ exists to catch - and it would be a change to the program, not a fix to it.
     */
    @Nested
    @DisplayName("INITIALIZE on the create-on-miss path - app/cbl/CBTRN02C.cbl:L503-L510")
    class CreateOnMissInitialize {

        @Test
        @DisplayName("INITIALIZE resets the four named items: 11 zeros, 2 SPACES, 4 zeros, +0.00")
        void initializeResetsEveryNamedItem() {
            TranCatBalRecord record = TranCatBalRecord.decode(fixtureRows().get(0), ASCII);
            // Preconditions, so the reset below is proved to have done something.
            assertThat(record.trancatAcctId()).isEqualTo(1L);
            assertThat(record.trancatTypeCd()).isEqualTo(FIXTURE_TYPE_CD);
            assertThat(record.trancatCd()).isEqualTo(FIXTURE_CAT_CD);

            record.initialize();

            // A numeric item goes to ZERO, an alphanumeric one to SPACE - so TRANCAT-TYPE-CD, whose
            // picture is X(02), becomes two spaces and not "00".
            Map<String, String> images = record.fieldImages();
            assertThat(images)
                    .containsEntry(TranCatBalRecord.TRANCAT_ACCT_ID_NAME, "0".repeat(ACCT_ID_WIDTH))
                    .containsEntry(TranCatBalRecord.TRANCAT_TYPE_CD_NAME, " ".repeat(TYPE_CD_WIDTH))
                    .containsEntry(TranCatBalRecord.TRANCAT_CD_NAME, "0".repeat(CAT_CD_WIDTH))
                    // S9(09)V99 zero carries the positive-zero overpunch '{' in its trailing byte, which
                    // is the image every row of the shipped fixture holds.
                    .containsEntry(TranCatBalRecord.TRAN_CAT_BAL_NAME,
                            "0".repeat(BALANCE_WIDTH - 1) + "{");

            assertThat(record.trancatAcctId()).isZero();
            assertThat(record.trancatTypeCd()).isEqualTo("  ");
            assertThat(record.trancatCd()).isZero();
            assertThat(record.tranCatBalImage()).isEqualTo("0".repeat(BALANCE_WIDTH - 1) + "{");
            assertThat(record.tranCatBal()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(record.tranCatBal().scale()).isEqualTo(TranCatBalRepository.TRAN_CAT_BAL_SCALE);
            assertThat(record.tranCatBalIsZero()).isTrue();

            // Exactly four items are addressable by name, and FILLER is not among them. That is the
            // reason INITIALIZE cannot reach it, rather than an oversight in this model.
            assertThat(images).hasSize(4).containsOnlyKeys(
                    TranCatBalRecord.TRANCAT_ACCT_ID_NAME,
                    TranCatBalRecord.TRANCAT_TYPE_CD_NAME,
                    TranCatBalRecord.TRANCAT_CD_NAME,
                    TranCatBalRecord.TRAN_CAT_BAL_NAME);
        }

        @Test
        @DisplayName("INITIALIZE leaves FILLER untouched - the fixture's 22 zeros survive it")
        void initializeLeavesTheFixturesFillerUntouched() {
            TranCatBalRecord record = TranCatBalRecord.decode(fixtureRows().get(0), ASCII);
            // Measured from app/data/ASCII/tcatbal.txt: the reserved span holds 22 ASCII zeros, not
            // spaces. That difference is what makes this observable at all.
            String reserved = record.fillerImage();
            assertThat(reserved).hasSize(FILLER_WIDTH).isEqualTo("0".repeat(FILLER_WIDTH));

            record.initialize();

            assertThat(record.fillerImage()).isEqualTo(reserved);
            assertThat(record.fillerBytes()).isEqualTo(reserved.getBytes(ASCII));
            assertThat(record.fillerImage()).isNotEqualTo(" ".repeat(FILLER_WIDTH));
            assertThat(record.rawImage()).hasSize(FIFTY);
        }

        @Test
        @DisplayName("INITIALIZE leaves FILLER untouched the other way too - spaces stay spaces")
        void initializeLeavesAnAllocatedRecordsSpaceFillerUntouched() {
            // The other state of the same predicate, which is what gate G50 asks for: the previous test
            // shows non-space reserved bytes surviving, this one shows space ones surviving.
            // A record allocated rather than read space-fills its
            // reserved span, because the copybook gives FILLER no VALUE clause; INITIALIZE leaves that
            // alone as well. Together the two tests show the span is carried, not regenerated.
            TranCatBalRecord fresh = TranCatBalRecord.newInstance(ASCII);
            assertThat(fresh.fillerImage()).isEqualTo(" ".repeat(FILLER_WIDTH));

            fresh.trancatAcctId(7L).trancatTypeCd("XY").trancatCd(9)
                    .tranCatBal(new BigDecimal("3.00"));
            fresh.initialize();

            assertThat(fresh.fillerImage()).isEqualTo(" ".repeat(FILLER_WIDTH));
            assertThat(fresh.trancatAcctId()).isZero();
            assertThat(fresh.trancatTypeCd()).isEqualTo("  ");
            assertThat(fresh.trancatCd()).isZero();
            assertThat(fresh.tranCatBalIsZero()).isTrue();
        }

        @Test
        @DisplayName("INITIALIZE rewrites exactly the first 28 bytes and no others")
        void initializeRewritesExactlyTheFirstTwentyEightBytes() {
            TranCatBalRecord record = TranCatBalRecord.decode(fixtureRows().get(0), ASCII);
            String before = record.rawImage();

            record.initialize();
            String after = record.rawImage();

            assertThat(after).hasSize(FIFTY).isNotEqualTo(before);
            // The named items occupy 11 + 2 + 4 + 11 = 28 bytes, and those are the ones that change.
            assertThat(TranCatBalRecord.FILLER_OFFSET)
                    .isEqualTo(ACCT_ID_WIDTH + TYPE_CD_WIDTH + CAT_CD_WIDTH + BALANCE_WIDTH);
            assertThat(after.substring(0, TranCatBalRecord.FILLER_OFFSET))
                    .isEqualTo("0".repeat(ACCT_ID_WIDTH) + " ".repeat(TYPE_CD_WIDTH)
                            + "0".repeat(CAT_CD_WIDTH) + "0".repeat(BALANCE_WIDTH - 1) + "{");
            // Everything from offset 28 on is byte-identical to what it was.
            assertThat(after.substring(TranCatBalRecord.FILLER_OFFSET))
                    .hasSize(FILLER_WIDTH)
                    .isEqualTo(before.substring(TranCatBalRecord.FILLER_OFFSET));
        }

        @Test
        @DisplayName("INITIALIZE returns the same record, so the MOVEs that follow it can chain")
        void initializeIsChainableOnTheSameInstance() {
            // COBOL's INITIALIZE operates on storage in place; the Java form returns this so that :505
            // through :508 read as the single statement sequence they are.
            TranCatBalRecord record = TranCatBalRecord.newInstance(ASCII);
            assertThat(record.initialize()).isSameAs(record);
            assertThat(record.initialize().trancatAcctId(4L).trancatCd(3)).isSameAs(record);
        }

        @Test
        @DisplayName("the whole 2700-A sequence: a '23' read, INITIALIZE, the MOVEs, the ADD, a '00' WRITE")
        void createOnMissCarriesTheReservedBytesToTheDataset() {
            JdbcTemplate template = seeded(fixtureRows());
            TranCatBalRepository repository = repository(template);

            // A first transaction hits an existing key, so 2700-UPDATE-TCATBAL's READ at :474 succeeds
            // and leaves that record - reserved span included - in the record area.
            TranCatBalRecord area = repository.readByKey(1L, FIXTURE_TYPE_CD, FIXTURE_CAT_CD)
                    .record().orElseThrow();
            String carriedOver = area.fillerImage();
            assertThat(carriedOver).isEqualTo("0".repeat(FILLER_WIDTH));

            // A second transaction arrives for a key the dataset does not hold. The READ takes its
            // INVALID KEY branch, WS-CREATE-TRANCAT-REC becomes 'Y' at :478, and the guard at :481
            // treats '23' as normal - so the program continues rather than abending.
            ReadResult miss = repository.readByKey(ABSENT_ACCT_ID, FIXTURE_TYPE_CD, FIXTURE_CAT_CD);
            assertThat(miss.isNotFound()).isTrue();
            assertThat(miss.isOkOrNotFound()).isTrue();
            assertThat(miss.isOther()).isFalse();
            assertThat(miss.applResultWhereNotFoundIsNormal()).isEqualTo(FileStatus.APPL_AOK);
            // A failed READ does not clear the record area, which is why the reserved bytes are still
            // the previous record's when the create path starts.
            assertThat(miss.record()).isEmpty();
            assertThat(area.fillerImage()).isEqualTo(carriedOver);

            // 2700-A-CREATE-TCATBAL-REC, statement for statement.
            area.initialize()                              // :504 INITIALIZE TRAN-CAT-BAL-RECORD
                    .trancatAcctId(ABSENT_ACCT_ID)         // :505 MOVE XREF-ACCT-ID
                    .trancatTypeCd(FIXTURE_TYPE_CD)        // :506 MOVE DALYTRAN-TYPE-CD
                    .trancatCd(FIXTURE_CAT_CD)             // :507 MOVE DALYTRAN-CAT-CD
                    .addToTranCatBal(new BigDecimal("25.75"));   // :508 ADD DALYTRAN-AMT
            WriteResult written =
                    insideUnitOfWork(() -> repository.write(area));  // :510 WRITE, '00' or abend
            assertThat(written.isWritten()).isTrue();
            assertThat(written.status()).isEqualTo(FileStatus.OK);
            assertThat(written.applResult()).isEqualTo(FileStatus.APPL_AOK);

            // The reserved bytes reached the dataset unchanged: INITIALIZE never touched them and the
            // WRITE sent all 50. Blanking them here would differ from the COBOL in 22 bytes per created
            // record - invisible to a width check, and visible to the field-level differ.
            TranCatBalRecord stored = repository
                    .readByKey(ABSENT_ACCT_ID, FIXTURE_TYPE_CD, FIXTURE_CAT_CD)
                    .record().orElseThrow();
            assertThat(stored.rawImage()).hasSize(FIFTY);
            assertThat(stored.fillerImage())
                    .isEqualTo(carriedOver)
                    .isNotEqualTo(" ".repeat(FILLER_WIDTH));
            assertThat(stored.trancatAcctId()).isEqualTo(ABSENT_ACCT_ID);
            assertThat(stored.trancatTypeCd()).isEqualTo(FIXTURE_TYPE_CD);
            assertThat(stored.trancatCd()).isEqualTo(FIXTURE_CAT_CD);
            assertThat(stored.tranCatBal()).isEqualByComparingTo(new BigDecimal("25.75"));
            // And nothing else moved: 50 fixture rows plus the one created.
            assertThat(template.queryForObject(
                    "SELECT COUNT(*) FROM \"" + TEST_DSNAME + "\"", Integer.class))
                    .isEqualTo(FIXTURE_RECORDS + 1);
        }

        @Test
        @DisplayName("after INITIALIZE the ADD yields the amount itself, truncated toward zero")
        void createdBalanceIsTheTruncatedAmountBecauseTheAugendIsZero() {
            // ADD DALYTRAN-AMT TO TRAN-CAT-BAL at :508 follows the INITIALIZE at :504, so the augend is
            // +0.00 and the result is the amount. The store truncates at scale 2 with RoundingMode.DOWN,
            // because ROUNDED appears zero times in the 28 programs (R2 / gate G24).
            TranCatBalRecord credit = TranCatBalRecord.decode(fixtureRows().get(0), ASCII)
                    .initialize()
                    .addToTranCatBal(new BigDecimal("12.349"));
            assertThat(credit.tranCatBal())
                    .isEqualByComparingTo(new BigDecimal("12.349").setScale(2, RoundingMode.DOWN))
                    .isEqualByComparingTo(new BigDecimal("12.34"));
            assertThat(credit.tranCatBal().scale()).isEqualTo(2);

            // Truncation is toward zero on both sides of it: -12.349 becomes -12.34, never -12.35.
            TranCatBalRecord debit = TranCatBalRecord.newInstance(ASCII)
                    .initialize()
                    .addToTranCatBal(new BigDecimal("-12.349"));
            assertThat(debit.tranCatBal())
                    .isEqualByComparingTo(new BigDecimal("-12.349").setScale(2, RoundingMode.DOWN))
                    .isEqualByComparingTo(new BigDecimal("-12.34"));
            assertThat(TranCatBalRepository.TRAN_CAT_BAL_ROUNDING).isEqualTo(RoundingMode.DOWN);
        }
    }


    // =============================================================================================
    // 2700-A-CREATE-TCATBAL-REC - app/cbl/CBTRN02C.cbl:L503-L524, the WRITE.
    // =============================================================================================

    @Nested
    @DisplayName("the write - app/cbl/CBTRN02C.cbl:L503-L524")
    class Write {

        @Test
        @DisplayName("a new record is stored as exactly 50 bytes, FILLER included")
        void newRecordIsStoredAtFiftyBytes() {
            JdbcTemplate template = seeded(new ArrayList<>());
            TranCatBalRepository repository = repository(template);
            WriteResult result = insideUnitOfWork(() -> repository.write(recordFor(42L, "10.00")));
            assertThat(result.isWritten()).isTrue();
            assertThat(result.status()).isEqualTo(FileStatus.OK);
            assertThat(result.outcome()).isEqualTo(Outcome.OK);
            assertThat(result.applResult()).isEqualTo(FileStatus.APPL_AOK);
            assertThat(result.diagnostic()).isEmpty();
            assertThat(result.statusImage()).isEqualTo(FileStatus.toStatusImage(FileStatus.OK));

            String stored = template.queryForObject(
                    "SELECT " + RECORD_IMAGE_COLUMN + " FROM \"" + TEST_DSNAME + "\"", String.class);
            assertThat(stored).hasSize(FIFTY);
            assertThat(stored).startsWith("00000000042" + FIXTURE_TYPE_CD + "0001");
            // The trailing FILLER is present and space-filled - gates G19 and G21.
            assertThat(stored).endsWith(" ".repeat(FILLER_WIDTH));
        }

        @Test
        @DisplayName("a written record reads back byte-identical, balance and all")
        void writtenRecordReadsBackIdentical()  {
            JdbcTemplate template = seeded(new ArrayList<>());
            TranCatBalRepository repository = repository(template);
            TranCatBalRecord written = recordFor(42L, "1234.56");
            assertThat(insideUnitOfWork(() -> repository.write(written)).isWritten()).isTrue();

            ReadResult read = repository.readByKey(42L, FIXTURE_TYPE_CD, FIXTURE_CAT_CD);
            assertThat(read.isFound()).isTrue();
            assertThat(read.record().orElseThrow().rawImage()).isEqualTo(written.rawImage());
            assertThat(read.record().orElseThrow().tranCatBal())
                    .isEqualByComparingTo(new BigDecimal("1234.56"));
        }

        @Test
        @DisplayName("a duplicate key is '22' and is refused BEFORE the insert - no second row appears")
        void duplicateKeyIsRefusedBeforeInsert() {
            JdbcTemplate template = seeded(new ArrayList<>());
            TranCatBalRepository repository = repository(template);
            TranCatBalRecord record = recordFor(42L, "10.00");
            assertThat(insideUnitOfWork(() -> repository.write(record)).isWritten()).isTrue();

            WriteResult second = insideUnitOfWork(() -> repository.write(record));
            assertThat(second.isDuplicate()).isTrue();
            assertThat(second.status()).isEqualTo(FileStatus.DUPLICATE);
            assertThat(second.outcome()).isEqualTo(Outcome.DUPLICATE);
            assertThat(second.cicsResp()).hasValue(FileStatus.DUPREC);
            assertThat(second.applResult()).isEqualTo(TranCatBalRepository.APPL_RESULT_FATAL);
            assertThat(second.isWritten()).isFalse();
            assertThat(second.isNotFound()).isFalse();
            assertThat(second.isOther()).isFalse();
            // Nothing was added: the pre-check refused the insert.
            assertThat(template.queryForObject("SELECT COUNT(*) FROM \"" + TEST_DSNAME + "\"",
                    Integer.class)).isEqualTo(1);
        }

        @Test
        @DisplayName("a backend that enforces uniqueness itself yields the same '22'")
        void backendIntegrityViolationAlsoYieldsDuplicate() throws SQLException {
            TranCatBalRepository repository = repository(describingThenViolatingIntegrityOnWrite());
            WriteResult result = insideUnitOfWork(() -> repository.write(recordFor(42L, "10.00")));
            assertThat(result.isDuplicate()).isTrue();
            assertThat(result.status()).isEqualTo(FileStatus.DUPLICATE);
            assertThat(result.cicsResp()).hasValue(FileStatus.DUPREC);
            assertThat(result.diagnostic()).isPresent();
            assertThat(result.diagnostic().orElseThrow().integrityViolation()).isTrue();
        }

        @Test
        @DisplayName("a null record is refused")
        void nullRecordIsRefused() {
            assertThatNullPointerException()
                    .isThrownBy(() -> repository(new JdbcTemplate()).write(null));
        }

        @Test
        @DisplayName("an unreachable dataset is reported as a status")
        void unreachableDatasetIsReportedAsStatus() {
            TranCatBalRepository repository = repository(unreachable());
            WriteResult result = insideUnitOfWork(() -> repository.write(recordFor(42L, "10.00")));
            assertThat(result.isOther()).isTrue();
            assertThat(result.status()).isEqualTo(TranCatBalRepository.PERMANENT_ERROR_STATUS);
            assertThat(result.diagnostic()).isPresent();
        }

        @Test
        @DisplayName("a refused pre-check statement is reported as a status")
        void refusedPreCheckIsReportedAsStatus() throws SQLException {
            TranCatBalRepository repository = repository(describingThenRefusing());
            WriteResult result = insideUnitOfWork(() -> repository.write(recordFor(42L, "10.00")));
            assertThat(result.isOther()).isTrue();
            assertThat(result.diagnostic()).isPresent();
        }

        @Test
        @DisplayName("an insert that adds no row is a permanent error, never a silent success")
        void insertAddingNoRowIsAPermanentError() throws SQLException {
            TranCatBalRepository repository = repository(countingThenReporting(0, 0));
            WriteResult result = insideUnitOfWork(() -> repository.write(recordFor(42L, "10.00")));
            assertThat(result.isWritten()).isFalse();
            assertThat(result.isOther()).isTrue();
            assertThat(result.status()).isEqualTo(TranCatBalRepository.PERMANENT_ERROR_STATUS);
            assertThat(result.cicsResp()).hasValue(FileStatus.INVREQ);
        }

        @Test
        @DisplayName("an insert that adds several rows refuses the unit of work rather than reporting")
        void insertAddingSeveralRowsRefusesTheUnitOfWork() throws SQLException {
            TranCatBalRepository repository = repository(countingThenReporting(0, 2));
            assertThatExceptionOfType(DatasetIntegrityException.class)
                    .isThrownBy(() -> insideUnitOfWork(
                            () -> repository.write(recordFor(42L, "10.00"))))
                    .withMessageContaining("2 rows were added");
        }

        @Test
        @DisplayName("the handle's write reuses the shape its OPEN resolved")
        void handleWriteWorks() {
            JdbcTemplate template = seeded(new ArrayList<>());
            try (TranCatBalFile file = repository(template).open(OpenMode.I_O)) {
                assertThat(insideUnitOfWork(() -> file.write(recordFor(42L, "5.00"))).isWritten())
                        .isTrue();
                assertThat(insideUnitOfWork(() -> file.write(recordFor(42L, "5.00"))).isDuplicate())
                        .isTrue();
                assertThatNullPointerException().isThrownBy(() -> file.write(null));
            }
        }
    }

    // =============================================================================================
    // 2700-B-UPDATE-TCATBAL-REC - app/cbl/CBTRN02C.cbl:L526-L542, the REWRITE.
    // =============================================================================================

    @Nested
    @DisplayName("the rewrite - app/cbl/CBTRN02C.cbl:L526-L542")
    class Rewrite {

        @Test
        @DisplayName("an existing record is replaced in place and reads back with the new balance")
        void existingRecordIsReplaced() {
            TranCatBalRepository repository = repository(seeded(fixtureRows()));
            TranCatBalRecord record = repository.readByKey(1L, FIXTURE_TYPE_CD, FIXTURE_CAT_CD)
                    .record().orElseThrow();
            record.tranCatBal(new BigDecimal("77.77"));

            WriteResult result = insideUnitOfWork(() -> repository.rewrite(record));
            assertThat(result.isWritten()).isTrue();
            assertThat(result.status()).isEqualTo(FileStatus.OK);
            assertThat(result.applResult()).isEqualTo(FileStatus.APPL_AOK);
            assertThat(repository.readByKey(1L, FIXTURE_TYPE_CD, FIXTURE_CAT_CD)
                    .record().orElseThrow().tranCatBal())
                    .isEqualByComparingTo(new BigDecimal("77.77"));
        }

        @Test
        @DisplayName("the rewrite writes all 50 bytes, preserving the read record's reserved FILLER")
        void rewritePreservesReservedFiller() {
            List<String> rows = fixtureRows();
            JdbcTemplate template = seeded(rows);
            TranCatBalRepository repository = repository(template);
            TranCatBalRecord record = repository.readByKey(1L, FIXTURE_TYPE_CD, FIXTURE_CAT_CD)
                    .record().orElseThrow();
            // The fixture's FILLER is 22 zero characters, not spaces - and a REWRITE of a record area
            // preserves whatever came back from the read.
            String fillerAsRead = record.fillerImage();
            assertThat(fillerAsRead).hasSize(FILLER_WIDTH);
            record.tranCatBal(new BigDecimal("1.00"));
            assertThat(insideUnitOfWork(() -> repository.rewrite(record)).isWritten()).isTrue();

            TranCatBalRecord reread = repository.readByKey(1L, FIXTURE_TYPE_CD, FIXTURE_CAT_CD)
                    .record().orElseThrow();
            assertThat(reread.fillerImage()).isEqualTo(fillerAsRead);
            assertThat(reread.rawImage()).hasSize(FIFTY);
        }

        @Test
        @DisplayName("a key matching nothing is '23' - a rewrite is not an insert")
        void keyMatchingNothingIsNotFound() {
            TranCatBalRepository repository = repository(seeded(fixtureRows()));
            WriteResult result =
                    insideUnitOfWork(() -> repository.rewrite(recordFor(ABSENT_ACCT_ID, "1.00")));
            assertThat(result.isNotFound()).isTrue();
            assertThat(result.status()).isEqualTo(FileStatus.NOT_FOUND);
            assertThat(result.applResult()).isEqualTo(TranCatBalRepository.APPL_RESULT_FATAL);
            assertThat(result.isDuplicate()).isFalse();
            assertThat(result.isOther()).isFalse();
        }

        @Test
        @DisplayName("a key selecting several rows is refused BEFORE the update - nothing is overwritten")
        void fanOutIsRefusedBeforeWriting() {
            // The relation has no unique constraint, so two rows can carry one key.
            String row = fixtureRows().get(0);
            JdbcTemplate template = seeded(List.of(row, row));
            TranCatBalRepository repository = repository(template);
            TranCatBalRecord record = TranCatBalRecord.decode(row, ASCII)
                    .tranCatBal(new BigDecimal("99.99"));

            WriteResult result = insideUnitOfWork(() -> repository.rewrite(record));
            assertThat(result.isWritten()).isFalse();
            assertThat(result.status()).isEqualTo(TranCatBalRepository.PERMANENT_ERROR_STATUS);
            assertThat(result.cicsResp()).hasValue(FileStatus.INVREQ);
            // Neither row was changed: both still hold the original balance.
            List<String> stored = template.queryForList(
                    "SELECT " + RECORD_IMAGE_COLUMN + " FROM \"" + TEST_DSNAME + "\"", String.class);
            assertThat(stored).containsExactly(row, row);
        }

        @Test
        @DisplayName("inside a unit of work the pre-check takes the row lock the UPDATE will use")
        void insideAUnitOfWorkTheProbeLocks() {
            TranCatBalRepository repository = repository(seeded(fixtureRows()));
            insideUnitOfWork(() -> {
                TranCatBalRecord record = repository.readByKey(1L, FIXTURE_TYPE_CD, FIXTURE_CAT_CD)
                        .record().orElseThrow();
                record.tranCatBal(new BigDecimal("33.33"));
                assertThat(repository.rewrite(record).isWritten()).isTrue();
            });
        }

        @Test
        @DisplayName("a null record is refused")
        void nullRecordIsRefused() {
            assertThatNullPointerException()
                    .isThrownBy(() -> repository(new JdbcTemplate()).rewrite(null));
        }

        @Test
        @DisplayName("an unreachable dataset is reported as a status")
        void unreachableDatasetIsReportedAsStatus() {
            TranCatBalRepository repository = repository(unreachable());
            WriteResult result = insideUnitOfWork(() -> repository.rewrite(recordFor(1L, "1.00")));
            assertThat(result.isOther()).isTrue();
            assertThat(result.diagnostic()).isPresent();
        }

        @Test
        @DisplayName("a refused pre-check statement is reported as a status")
        void refusedPreCheckIsReportedAsStatus() throws SQLException {
            TranCatBalRepository repository = repository(describingThenRefusing());
            WriteResult result = insideUnitOfWork(() -> repository.rewrite(recordFor(1L, "1.00")));
            assertThat(result.isOther()).isTrue();
            assertThat(result.diagnostic()).isPresent();
        }

        @Test
        @DisplayName("a row lost between the pre-check and the update is '23', because nothing was written")
        void rowLostBetweenCheckAndUpdateIsNotFound() throws SQLException {
            TranCatBalRepository repository = repository(countingThenReporting(1, 0));
            WriteResult result = insideUnitOfWork(() -> repository.rewrite(recordFor(1L, "1.00")));
            assertThat(result.isNotFound()).isTrue();
            assertThat(result.status()).isEqualTo(FileStatus.NOT_FOUND);
        }

        @Test
        @DisplayName("rows replaced beyond the one checked refuses the unit of work rather than reporting")
        void fanOutAfterTheCheckRefusesTheUnitOfWork() throws SQLException {
            TranCatBalRepository repository = repository(countingThenReporting(1, 2));
            assertThatExceptionOfType(DatasetIntegrityException.class)
                    .isThrownBy(() -> insideUnitOfWork(
                            () -> repository.rewrite(recordFor(1L, "1.00"))))
                    .withMessageContaining("2 rows were replaced")
                    .withMessageContaining("a row lock");
        }

        @Test
        @DisplayName("outside a unit of work both verbs are refused, never reported as stored")
        void outsideAUnitOfWorkTheWriteAndTheRewriteAreRefused() {
            // The pool hands out connections with auto-commit disabled, so either statement would
            // execute, report the row it touched, and then be rolled back when the connection was
            // returned - leaving 2700-A believing it had created a balance record and 2700-B believing it
            // had stored an accumulated one. There is no FILE STATUS for that, so nothing is attempted.
            JdbcTemplate template = seeded(fixtureRows());
            TranCatBalRepository repository = repository(template);

            assertThatIllegalStateException()
                    .isThrownBy(() -> repository.write(recordFor(ABSENT_ACCT_ID, "1.00")))
                    .withMessageContaining("no transaction is open on this thread")
                    .withMessageContaining(TEST_DSNAME);
            assertThatIllegalStateException()
                    .isThrownBy(() -> repository.rewrite(recordFor(1L, "1.00")))
                    .withMessageContaining("no transaction is open on this thread")
                    .withMessageContaining(TEST_DSNAME);

            assertThat(template.queryForList(
                    "SELECT " + RECORD_IMAGE_COLUMN + " FROM \"" + TEST_DSNAME + "\"", String.class))
                    .as("nothing may be attempted when the change could not be committed")
                    .isEqualTo(fixtureRows());
        }

        @Test
        @DisplayName("the same race inside a unit of work names the row lock in its refusal")
        void fanOutInsideAUnitOfWorkNamesTheLock() throws SQLException {
            TranCatBalRepository repository = repository(countingThenReporting(1, 3));
            insideUnitOfWork(() -> assertThatExceptionOfType(DatasetIntegrityException.class)
                    .isThrownBy(() -> repository.rewrite(recordFor(1L, "1.00")))
                    .withMessageContaining("3 rows were replaced")
                    .withMessageContaining("a row lock"));
        }

        @Test
        @DisplayName("the handle's rewrite reuses the shape its OPEN resolved")
        void handleRewriteWorks() {
            TranCatBalRepository repository = repository(seeded(fixtureRows()));
            try (TranCatBalFile file = repository.open(OpenMode.I_O)) {
                TranCatBalRecord record = file.readByKey(1L, FIXTURE_TYPE_CD, FIXTURE_CAT_CD)
                        .record().orElseThrow();
                record.tranCatBal(new BigDecimal("12.34"));
                assertThat(insideUnitOfWork(() -> file.rewrite(record)).isWritten()).isTrue();
                assertThat(insideUnitOfWork(
                        () -> file.rewrite(recordFor(ABSENT_ACCT_ID, "1.00"))).isNotFound()).isTrue();
                assertThatNullPointerException().isThrownBy(() -> file.rewrite(null));
            }
        }
    }

    // =============================================================================================
    // The balance itself: scale 2, truncating, sign-correct - gates G22, G23, G24.
    // =============================================================================================

    @Nested
    @DisplayName("the balance - PIC S9(09)V99, scale 2, truncating, sign in the trailing byte")
    class Balance {

        @ParameterizedTest
        @ValueSource(strings = {"0.00", "1.00", "-1.00", "1234.56", "-1234.56", "999999999.99",
                "-999999999.99"})
        @DisplayName("a balance round-trips through the dataset sign-correct at scale 2")
        void balanceRoundTripsSignCorrect(String amount) {
            JdbcTemplate template = seeded(new ArrayList<>());
            TranCatBalRepository repository = repository(template);
            assertThat(insideUnitOfWork(() -> repository.write(recordFor(42L, amount))).isWritten())
                    .isTrue();

            BigDecimal read = repository.readByKey(42L, FIXTURE_TYPE_CD, FIXTURE_CAT_CD)
                    .record().orElseThrow().tranCatBal();
            assertThat(read).isEqualByComparingTo(new BigDecimal(amount));
            assertThat(read.scale()).isEqualTo(2);
        }

        @ParameterizedTest
        @ValueSource(strings = {"1.239", "-1.239", "0.005", "-0.005", "1.999", "-1.999"})
        @DisplayName("excess fractional digits TRUNCATE toward zero - never round, because no ROUNDED")
        void excessDigitsTruncateTowardZero(String amount) {
            BigDecimal expected = new BigDecimal(amount).setScale(2, RoundingMode.DOWN);
            JdbcTemplate template = seeded(new ArrayList<>());
            TranCatBalRepository repository = repository(template);
            assertThat(insideUnitOfWork(() -> repository.write(recordFor(42L, amount))).isWritten())
                    .isTrue();
            assertThat(repository.readByKey(42L, FIXTURE_TYPE_CD, FIXTURE_CAT_CD)
                    .record().orElseThrow().tranCatBal()).isEqualByComparingTo(expected);
        }

        @Test
        @DisplayName("the fixture's balances are zero, with a positive-zero sign overpunch")
        void fixtureBalancesAreZero() {
            ReadResult result = repository(seeded(fixtureRows()))
                    .readByKey(1L, FIXTURE_TYPE_CD, FIXTURE_CAT_CD);
            assertThat(result.record().orElseThrow().tranCatBal())
                    .isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(result.record().orElseThrow().tranCatBalIsZero()).isTrue();
        }
    }

    // =============================================================================================
    // The discriminated outcomes, and the invariants that keep a status and an outcome agreeing.
    // =============================================================================================

    @Nested
    @DisplayName("ReadResult - the outcome invariants and both APPL-RESULT ladders")
    class ReadResultContract {

        @Test
        @DisplayName("found carries the record; the other arms carry none")
        void factoriesCarryTheRightPayload() {
            TranCatBalRecord record = recordFor(1L, "1.00");
            ReadResult found = ReadResult.found(record);
            assertThat(found.status()).isEqualTo(FileStatus.OK);
            assertThat(found.record()).contains(record);
            assertThat(ReadResult.endOfFile().record()).isEmpty();
            assertThat(ReadResult.notFound().record()).isEmpty();
            assertThat(ReadResult.endOfFile().status()).isEqualTo(FileStatus.END_OF_FILE);
            assertThat(ReadResult.notFound().status()).isEqualTo(FileStatus.NOT_FOUND);
        }

        @Test
        @DisplayName("a null record cannot be a successful read")
        void nullRecordIsRefused() {
            assertThatNullPointerException().isThrownBy(() -> ReadResult.found(null));
        }

        @Test
        @DisplayName("of() refuses '00', so a successful read can never lack its record")
        void ofRefusesSuccess() {
            assertThatIllegalArgumentException().isThrownBy(() -> ReadResult.of(FileStatus.OK))
                    .withMessageContaining("found()");
        }

        @ParameterizedTest
        @ValueSource(strings = {"", "0", "000", "0000"})
        @DisplayName("a status of the wrong width is refused rather than padded")
        void wrongWidthStatusIsRefused(String status) {
            assertThatIllegalArgumentException().isThrownBy(() -> ReadResult.of(status))
                    .withMessageContaining("exactly 2 characters");
        }

        @Test
        @DisplayName("a null status is refused")
        void nullStatusIsRefused() {
            assertThatNullPointerException().isThrownBy(() -> ReadResult.of((String) null));
        }

        @Test
        @DisplayName("a status and an outcome that disagree cannot be constructed")
        void disagreeingStatusAndOutcomeAreRefused() {
            assertThatIllegalArgumentException().isThrownBy(() -> new ReadResult(
                    FileStatus.NOT_FOUND, Outcome.END_OF_FILE, Optional.empty(), Optional.empty(),
                    CicsResponse.none())).withMessageContaining("classifies as");
        }

        @Test
        @DisplayName("a record present on a failed outcome cannot be constructed")
        void recordOnAFailedOutcomeIsRefused() {
            assertThatIllegalArgumentException().isThrownBy(() -> new ReadResult(
                    FileStatus.NOT_FOUND, Outcome.NOT_FOUND, Optional.of(recordFor(1L, "1.00")),
                    Optional.empty(), CicsResponse.none()))
                    .withMessageContaining("carries no record");
        }

        @Test
        @DisplayName("a record absent on a successful outcome cannot be constructed")
        void missingRecordOnSuccessIsRefused() {
            assertThatIllegalArgumentException().isThrownBy(() -> new ReadResult(
                    FileStatus.OK, Outcome.OK, Optional.empty(), Optional.empty(),
                    CicsResponse.ofBatchStatus(FileStatus.OK)))
                    .withMessageContaining("carries none");
        }

        @Test
        @DisplayName("no component may be null")
        void noComponentMayBeNull() {
            assertThatNullPointerException().isThrownBy(() -> new ReadResult(FileStatus.NOT_FOUND,
                    Outcome.NOT_FOUND, null, Optional.empty(), CicsResponse.none()));
            assertThatNullPointerException().isThrownBy(() -> new ReadResult(FileStatus.NOT_FOUND,
                    Outcome.NOT_FOUND, Optional.empty(), null, CicsResponse.none()));
            assertThatNullPointerException().isThrownBy(() -> new ReadResult(FileStatus.NOT_FOUND,
                    Outcome.NOT_FOUND, Optional.empty(), Optional.empty(), null));
            assertThatNullPointerException().isThrownBy(() -> new ReadResult(null, Outcome.NOT_FOUND,
                    Optional.empty(), Optional.empty(), CicsResponse.none()));
        }

        @Test
        @DisplayName("of(status, diagnostic) requires the diagnostic it names")
        void diagnosticFactoryRequiresADiagnostic() {
            assertThatNullPointerException().isThrownBy(() -> ReadResult.of(
                    TranCatBalRepository.PERMANENT_ERROR_STATUS, (BackendDiagnostic) null));
            ReadResult carried = ReadResult.of(TranCatBalRepository.PERMANENT_ERROR_STATUS,
                    BackendDiagnostic.of(new SQLException("refused", "08006")));
            assertThat(carried.diagnostic()).isPresent();
            assertThat(carried.diagnostic().orElseThrow().connectionFailure()).isTrue();
        }

        @Test
        @DisplayName("of(status, response) carries a response that the status does not imply")
        void responseFactoryCarriesAnIndependentResponse() {
            ReadResult lengthError = ReadResult.of(TranCatBalRepository.PERMANENT_ERROR_STATUS,
                    CicsResponse.of(FileStatus.LENGERR));
            assertThat(lengthError.cicsResp()).hasValue(FileStatus.LENGERR);
            assertThat(lengthError.isOther()).isTrue();
        }

        @Test
        @DisplayName("the predicates partition the statuses, and only one is ever true")
        void predicatesPartitionTheStatuses() {
            ReadResult found = ReadResult.found(recordFor(1L, "1.00"));
            assertThat(found.isFound()).isTrue();
            assertThat(found.isEndOfFile() || found.isNotFound() || found.isOther()).isFalse();

            ReadResult eof = ReadResult.endOfFile();
            assertThat(eof.isEndOfFile()).isTrue();
            assertThat(eof.isFound() || eof.isNotFound() || eof.isOther()).isFalse();

            ReadResult notFound = ReadResult.notFound();
            assertThat(notFound.isNotFound()).isTrue();
            assertThat(notFound.isFound() || notFound.isEndOfFile() || notFound.isOther()).isFalse();

            ReadResult other = ReadResult.of(TranCatBalRepository.PERMANENT_ERROR_STATUS);
            assertThat(other.isOther()).isTrue();
            assertThat(other.isFound() || other.isEndOfFile() || other.isNotFound()).isFalse();

            ReadResult duplicate = ReadResult.of(FileStatus.DUPLICATE);
            assertThat(duplicate.isOther()).isTrue();
        }

        @Test
        @DisplayName("CBACT04C's ladder: 0 on success, 16 at end of file, 12 for everything else")
        void cbact04cLadder() {
            assertThat(ReadResult.found(recordFor(1L, "1.00")).applResult())
                    .isEqualTo(FileStatus.APPL_AOK);
            assertThat(ReadResult.endOfFile().applResult()).isEqualTo(FileStatus.APPL_EOF);
            assertThat(ReadResult.notFound().applResult())
                    .isEqualTo(TranCatBalRepository.APPL_RESULT_FATAL);
            assertThat(ReadResult.of(FileStatus.DUPLICATE).applResult())
                    .isEqualTo(TranCatBalRepository.APPL_RESULT_FATAL);
            assertThat(ReadResult.of(TranCatBalRepository.PERMANENT_ERROR_STATUS).applResult())
                    .isEqualTo(TranCatBalRepository.APPL_RESULT_FATAL);
        }

        @Test
        @DisplayName("CBTRN02C's ladder: 0 for '00' OR '23', 12 for everything else")
        void cbtrn02cLadder() {
            assertThat(ReadResult.found(recordFor(1L, "1.00")).applResultWhereNotFoundIsNormal())
                    .isEqualTo(FileStatus.APPL_AOK);
            assertThat(ReadResult.notFound().applResultWhereNotFoundIsNormal())
                    .isEqualTo(FileStatus.APPL_AOK);
            assertThat(ReadResult.endOfFile().applResultWhereNotFoundIsNormal())
                    .isEqualTo(TranCatBalRepository.APPL_RESULT_FATAL);
            assertThat(ReadResult.of(FileStatus.DUPLICATE).applResultWhereNotFoundIsNormal())
                    .isEqualTo(TranCatBalRepository.APPL_RESULT_FATAL);
        }

        @Test
        @DisplayName("isOkOrNotFound is true for exactly '00' and '23'")
        void okOrNotFoundIsTrueForExactlyTwoStatuses() {
            assertThat(ReadResult.found(recordFor(1L, "1.00")).isOkOrNotFound()).isTrue();
            assertThat(ReadResult.notFound().isOkOrNotFound()).isTrue();
            assertThat(ReadResult.endOfFile().isOkOrNotFound()).isFalse();
            assertThat(ReadResult.of(FileStatus.DUPLICATE).isOkOrNotFound()).isFalse();
        }

        @Test
        @DisplayName("the status renders as 9910-DISPLAY-IO-STATUS renders it")
        void statusImageAndCicsPairAreExposed() {
            ReadResult notFound = ReadResult.notFound();
            assertThat(notFound.statusImage())
                    .isEqualTo(FileStatus.toStatusImage(FileStatus.NOT_FOUND));
            assertThat(notFound.cicsResp()).hasValue(FileStatus.NOTFND);
            assertThat(notFound.cicsResp2()).isEqualTo(FileStatus.NO_REASON_CODE);
        }
    }

    @Nested
    @DisplayName("WriteResult - one ladder, because both write paragraphs share a guard")
    class WriteResultContract {

        @Test
        @DisplayName("the factories carry the statuses their paragraphs report")
        void factoriesCarryTheRightStatus() {
            assertThat(WriteResult.written().status()).isEqualTo(FileStatus.OK);
            assertThat(WriteResult.notFound().status()).isEqualTo(FileStatus.NOT_FOUND);
            assertThat(WriteResult.duplicate().status()).isEqualTo(FileStatus.DUPLICATE);
            assertThat(WriteResult.duplicate().cicsResp()).hasValue(FileStatus.DUPREC);
            assertThat(WriteResult.written().diagnostic()).isEmpty();
        }

        @Test
        @DisplayName("of() refuses '00', so a success is always built by written()")
        void ofRefusesSuccess() {
            assertThatIllegalArgumentException().isThrownBy(() -> WriteResult.of(FileStatus.OK))
                    .withMessageContaining("written()");
        }

        @ParameterizedTest
        @ValueSource(strings = {"", "2", "222"})
        @DisplayName("a status of the wrong width is refused")
        void wrongWidthStatusIsRefused(String status) {
            assertThatIllegalArgumentException().isThrownBy(() -> WriteResult.of(status));
        }

        @Test
        @DisplayName("a status and an outcome that disagree cannot be constructed")
        void disagreeingStatusAndOutcomeAreRefused() {
            assertThatIllegalArgumentException().isThrownBy(() -> new WriteResult(
                    FileStatus.DUPLICATE, Outcome.NOT_FOUND, Optional.empty(), CicsResponse.none()));
        }

        @Test
        @DisplayName("no component may be null")
        void noComponentMayBeNull() {
            assertThatNullPointerException().isThrownBy(() -> new WriteResult(FileStatus.NOT_FOUND,
                    Outcome.NOT_FOUND, null, CicsResponse.none()));
            assertThatNullPointerException().isThrownBy(() -> new WriteResult(FileStatus.NOT_FOUND,
                    Outcome.NOT_FOUND, Optional.empty(), null));
        }

        @Test
        @DisplayName("the diagnostic factories require the diagnostic they name")
        void diagnosticFactoriesRequireADiagnostic() {
            assertThatNullPointerException().isThrownBy(() -> WriteResult.of(
                    TranCatBalRepository.PERMANENT_ERROR_STATUS, (BackendDiagnostic) null));
            assertThatNullPointerException().isThrownBy(() -> WriteResult.of(FileStatus.DUPLICATE,
                    CicsResponse.of(FileStatus.DUPREC), null));
            WriteResult carried = WriteResult.of(FileStatus.DUPLICATE,
                    CicsResponse.of(FileStatus.DUPREC),
                    BackendDiagnostic.of(new SQLException("dup", "23505")));
            assertThat(carried.isDuplicate()).isTrue();
            assertThat(carried.diagnostic()).isPresent();
        }

        @Test
        @DisplayName("of(status, response) carries a response the status does not imply")
        void responseFactoryCarriesAnIndependentResponse() {
            WriteResult invalid = WriteResult.of(TranCatBalRepository.PERMANENT_ERROR_STATUS,
                    CicsResponse.of(FileStatus.INVREQ));
            assertThat(invalid.cicsResp()).hasValue(FileStatus.INVREQ);
            assertThat(invalid.cicsResp2()).isEqualTo(FileStatus.NO_REASON_CODE);
        }

        @Test
        @DisplayName("the predicates partition the statuses, and only one is ever true")
        void predicatesPartitionTheStatuses() {
            assertThat(WriteResult.written().isWritten()).isTrue();
            assertThat(WriteResult.written().isNotFound()).isFalse();
            assertThat(WriteResult.written().isDuplicate()).isFalse();
            assertThat(WriteResult.written().isOther()).isFalse();

            assertThat(WriteResult.notFound().isNotFound()).isTrue();
            assertThat(WriteResult.notFound().isWritten()).isFalse();
            assertThat(WriteResult.notFound().isOther()).isFalse();

            assertThat(WriteResult.duplicate().isDuplicate()).isTrue();
            assertThat(WriteResult.duplicate().isOther()).isFalse();

            WriteResult other = WriteResult.of(TranCatBalRepository.PERMANENT_ERROR_STATUS);
            assertThat(other.isOther()).isTrue();
            assertThat(other.isWritten() || other.isNotFound() || other.isDuplicate()).isFalse();

            assertThat(WriteResult.of(FileStatus.END_OF_FILE).isOther()).isTrue();
        }

        @Test
        @DisplayName("the ladder is 0 when written and 12 otherwise, for both paragraphs")
        void theSingleLadder() {
            assertThat(WriteResult.written().applResult()).isEqualTo(FileStatus.APPL_AOK);
            assertThat(WriteResult.notFound().applResult())
                    .isEqualTo(TranCatBalRepository.APPL_RESULT_FATAL);
            assertThat(WriteResult.duplicate().applResult())
                    .isEqualTo(TranCatBalRepository.APPL_RESULT_FATAL);
            assertThat(WriteResult.of(TranCatBalRepository.PERMANENT_ERROR_STATUS).applResult())
                    .isEqualTo(TranCatBalRepository.APPL_RESULT_FATAL);
        }

        @Test
        @DisplayName("the status renders as 9910-DISPLAY-IO-STATUS renders it")
        void statusImageIsExposed() {
            assertThat(WriteResult.duplicate().statusImage())
                    .isEqualTo(FileStatus.toStatusImage(FileStatus.DUPLICATE));
        }
    }

    // =============================================================================================
    // Statement composition: core SQL only, ordering stated, no invented identifier.
    // =============================================================================================

    // =============================================================================================
    // Defensive invariants: the arms that exist so an impossible state cannot pass silently.
    // =============================================================================================

    @Nested
    @DisplayName("defensive invariants - the guards against states the collaborators should preclude")
    class DefensiveInvariants {

        @Test
        @DisplayName("a write refused for a NON-integrity reason is a permanent error, not a duplicate")
        void nonIntegrityWriteFailureIsNotADuplicate() throws SQLException {
            TranCatBalRepository repository =
                    repository(describingThenFailingWriteWithoutIntegrityViolation());
            WriteResult result = insideUnitOfWork(() -> repository.write(recordFor(42L, "10.00")));
            assertThat(result.isDuplicate()).isFalse();
            assertThat(result.isOther()).isTrue();
            assertThat(result.status()).isEqualTo(TranCatBalRepository.PERMANENT_ERROR_STATUS);
            assertThat(result.diagnostic()).isPresent();
            assertThat(result.diagnostic().orElseThrow().integrityViolation()).isFalse();
            assertThat(result.diagnostic().orElseThrow().connectionFailure()).isTrue();
        }

        @Test
        @DisplayName("a probe reporting no count is treated as a fan-out, so no write proceeds unchecked")
        void anAbsentRowCountRefusesTheWrite() {
            TranCatBalRepository repository = repository(countReportingNothing(fixtureRows()));
            // A write sees "already exists" and refuses; a rewrite sees "more than one" and refuses.
            assertThat(insideUnitOfWork(() -> repository.write(recordFor(42L, "1.00"))).isDuplicate())
                    .isTrue();
            WriteResult rewritten = insideUnitOfWork(() -> repository.rewrite(recordFor(1L, "1.00")));
            assertThat(rewritten.isWritten()).isFalse();
            assertThat(rewritten.status()).isEqualTo(TranCatBalRepository.PERMANENT_ERROR_STATUS);
            assertThat(rewritten.cicsResp()).hasValue(FileStatus.INVREQ);
        }

        @Test
        @DisplayName("a record that does not encode to 50 bytes is refused rather than stored")
        void aRecordOfTheWrongWidthIsNeverStored() {
            // TranCatBalRecord maintains its 50-byte area as its single source of truth, so this state
            // should be impossible. It is checked rather than trusted because a short image would store a
            // row that every later read would then reject - one bad write becoming a permanent defect.
            TranCatBalRecord malformed = Mockito.mock(TranCatBalRecord.class);
            Mockito.when(malformed.encode()).thenReturn(new byte[40]);
            Mockito.when(malformed.tranCatKeyImage()).thenReturn("00000000042010001");

            TranCatBalRepository repository = repository(seeded(fixtureRows()));
            assertThatIllegalStateException().isThrownBy(() -> repository.write(malformed))
                    .withMessageContaining("encodes to 40 byte(s)")
                    .withMessageContaining("CVTRA01Y");
            assertThatIllegalStateException().isThrownBy(() -> repository.rewrite(malformed))
                    .withMessageContaining("encodes to 40 byte(s)");
        }
    }

    @Nested
    @DisplayName("statement composition - core SQL, explicit ordering, discovered column name")
    class StatementComposition {

        @Test
        @DisplayName("all six statements are composed over the configured dataset")
        void allSixStatementsAreComposed() {
            Statements sql = repository(seeded(fixtureRows())).resolveStatements();
            assertThat(sql.selectFirst()).contains(TEST_DSNAME).contains("ORDER BY").contains("ASC");
            assertThat(sql.selectNext()).contains("> ?").contains("ORDER BY").contains("ASC");
            assertThat(sql.selectByKey()).contains("LIKE ?").contains("ESCAPE");
            assertThat(sql.selectByKeyForUpdate()).endsWith("FOR UPDATE");
            assertThat(sql.rewrite()).startsWith("UPDATE ").contains(" SET ").contains("LIKE ?");
            assertThat(sql.insert()).startsWith("INSERT INTO ").contains("VALUES (?)");
        }

        @Test
        @DisplayName("no dialect row-limiting syntax appears anywhere")
        void noDialectRowLimitingSyntax() {
            Statements sql = repository(seeded(fixtureRows())).resolveStatements();
            String all = sql.selectFirst() + sql.selectNext() + sql.selectByKey()
                    + sql.selectByKeyForUpdate() + sql.rewrite() + sql.insert();
            assertThat(all).doesNotContain("FETCH FIRST").doesNotContain("LIMIT")
                    .doesNotContain("OFFSET").doesNotContain("TOP ").doesNotContain("ROWNUM");
        }

        @Test
        @DisplayName("the record-image column name is discovered, never invented")
        void columnNameIsDiscovered() throws SQLException {
            Statements sql = repository(describing(1, "WHATEVER_THE_BACKEND_CALLS_IT"))
                    .resolveStatements();
            assertThat(sql.selectByKey()).contains("WHATEVER_THE_BACKEND_CALLS_IT");
        }

        @Test
        @DisplayName("a relation presenting no usable column is a contract violation, and throws")
        void noUsableColumnIsAContractViolation() throws SQLException {
            TranCatBalRepository noMetadata = repository(describingNothing());
            assertThatIllegalStateException().isThrownBy(noMetadata::resolveStatements)
                    .withMessageContaining("no usable column");

            TranCatBalRepository noColumns = repository(describing(0, null));
            assertThatIllegalStateException().isThrownBy(noColumns::resolveStatements);

            TranCatBalRepository blankName = repository(describing(1, "   "));
            assertThatIllegalStateException().isThrownBy(blankName::resolveStatements);
        }

        @Test
        @DisplayName("an open against a relation with no usable column throws rather than statusing")
        void openAgainstAnUnusableRelationThrows() throws SQLException {
            TranCatBalRepository repository = repository(describingNothing());
            assertThatIllegalStateException().isThrownBy(() -> repository.open(OpenMode.INPUT));
        }

        @Test
        @DisplayName("nothing is cached: each call resolves the dataset's shape afresh")
        void nothingIsCached() {
            TranCatBalRepository repository = repository(seeded(fixtureRows()));
            Statements first = repository.resolveStatements();
            Statements second = repository.resolveStatements();
            assertThat(first).isEqualTo(second);
            assertThat(first).isNotSameAs(second);
        }
    }

    // =============================================================================================
    // No schema at all - gates G44, G45 and G46. The dataset is reached, never defined.
    // =============================================================================================

    /**
     * The three absences that make this a dataset access path rather than a database design.
     *
     * <p>They are asserted rather than assumed because each would be introduced by a plausible,
     * well-intentioned edit: an {@code @Entity} to "tidy up" the record, a {@code CREATE TABLE} to make the
     * test self-seeding, an alternate-index finder because two sibling repositories have one, or the
     * production dataset name inlined "so the mapping is obvious". Every one of those would break a stated
     * constraint of the migration, and none of them would fail any other test in this class.
     */
    @Nested
    @DisplayName("no schema - no DDL, no mapping, no alternate index, no hard-coded dataset name")
    class SchemaAbsence {

        @Test
        @DisplayName("a test's database is disposed of when the test ends, name and schema alike")
        void aTestsDatabaseIsDisposedOf() {
            // DB_CLOSE_DELAY=-1 is required for the seed to work at all and, left alone, keeps every
            // database this suite ever created alive and addressable by name until the JVM exits. That
            // is not a leak in the abstract: a name that outlives its test is a name another test can
            // reach, and per-test isolation then depends on nobody ever reusing one.
            DataSource created = seededDataSource(FIFTY);

            assertThat(new JdbcTemplate(created).queryForObject(
                    "SELECT COUNT(*) FROM \"" + TEST_DSNAME + "\"", Integer.class))
                    .as("the relation exists while the test is using it")
                    .isZero();

            disposeCreatedDatabases();

            assertThatExceptionOfType(BadSqlGrammarException.class)
                    .as("and afterwards the schema is gone, so the name addresses nothing a later "
                            + "test could see")
                    .isThrownBy(() -> new JdbcTemplate(created).queryForObject(
                            "SELECT COUNT(*) FROM \"" + TEST_DSNAME + "\"", Integer.class));
        }

        @Test
        @DisplayName("no object-relational mapping is declared anywhere - gate G44")
        void noObjectRelationalMappingIsDeclared() {
            // The repository, every type nested inside it, and the record it carries.
            assertNoMappingAnnotations(TranCatBalRepository.class);
            for (Class<?> nested : TranCatBalRepository.class.getDeclaredClasses()) {
                assertNoMappingAnnotations(nested);
            }
            assertNoMappingAnnotations(TranCatBalRecord.class);
            assertNoMappingAnnotations(TranCatKey.class);

            // The repository is a Spring @Repository and nothing more: a stereotype, not a mapping.
            assertThat(TranCatBalRepository.class.getAnnotations())
                    .extracting(annotation -> annotation.annotationType().getSimpleName())
                    .containsExactly("Repository");
        }

        @Test
        @DisplayName("there is no version column - the concurrency check is the COBOL's own re-read")
        void thereIsNoVersionColumn() {
            // COACTUPC and COCRDUPC do their own optimistic-concurrency check by re-reading and
            // comparing; a version column would be a schema change, which is forbidden outright.
            // TCATBALF has no such check at all - CBTRN02C reads, adds and rewrites - so there is
            // nothing here for a version column even to attach to.
            assertThat(memberNames(TranCatBalRepository.class))
                    .noneMatch(name -> name.contains("version"));
            assertThat(memberNames(TranCatBalRecord.class))
                    .noneMatch(name -> name.contains("version"));
            // The four addressable items are the copybook's four, with no synthetic fifth.
            assertThat(TranCatBalRecord.newInstance(ASCII).fieldImages()).hasSize(4);
        }

        @Test
        @DisplayName("no statement defines or alters structure - only reads, an insert and a rewrite")
        void noStatementDefinesStructure() {
            Statements sql = repository(seeded(fixtureRows())).resolveStatements();
            String all = (sql.selectFirst() + " " + sql.selectNext() + " " + sql.selectByKey() + " "
                    + sql.selectByKeyForUpdate() + " " + sql.rewrite() + " " + sql.insert())
                    .toUpperCase(Locale.ROOT);
            for (String fragment : DDL_FRAGMENTS) {
                assertThat(all).as("a composed statement contains '%s'", fragment)
                        .doesNotContain(fragment);
            }
            // What is there instead: the five verbs the COBOL performs on this dataset.
            assertThat(sql.selectFirst()).startsWith("SELECT ");
            assertThat(sql.selectNext()).startsWith("SELECT ");
            assertThat(sql.selectByKey()).startsWith("SELECT ");
            assertThat(sql.selectByKeyForUpdate()).startsWith("SELECT ");
            assertThat(sql.rewrite()).startsWith("UPDATE ");
            assertThat(sql.insert()).startsWith("INSERT INTO ");
        }

        @Test
        @DisplayName("one dataset, one relation, and no alternate index - gate G45's spirit")
        void oneDatasetOneRelationAndNoAlternateIndex() {
            TranCatBalRepository repository = repository(seeded(fixtureRows()));
            Statements sql = repository.resolveStatements();

            // Every statement names exactly one relation, and it is the same one in all six. TCATBALF is
            // bound identically by app/jcl/POSTTRAN.jcl:41-42 and app/jcl/INTCALC.jcl:27-28 - one DD, one
            // DSNAME - and app/csd/CARDDEMO.CSD declares no path over it, unlike CARDAIX over CARDDAT and
            // CXACAIX over CCXREF. So there is no second relation and no second repository to have.
            List<String> relations = new ArrayList<>();
            for (String statement : List.of(sql.selectFirst(), sql.selectNext(), sql.selectByKey(),
                    sql.selectByKeyForUpdate(), sql.rewrite(), sql.insert())) {
                // A statement quotes two kinds of identifier: the relation and the record-image column.
                // Setting the column aside, exactly one relation is left - and it is the configured one.
                List<String> named = quotedRelations(statement);
                assertThat(named).as("statement [%s]", statement).containsExactly(TEST_DSNAME);
                relations.addAll(named);
                // And nothing else is quoted: no second relation and no invented identifier.
                assertThat(quotedIdentifiers(statement)).as("statement [%s]", statement)
                        .containsOnly(TEST_DSNAME, RECORD_IMAGE_COLUMN);
            }
            assertThat(relations).hasSize(6).containsOnly(TEST_DSNAME);
            assertThat(repository.datasetName()).isEqualTo(TEST_DSNAME);

            // And no method is an alternate-index finder, by the naming convention the two repositories
            // that legitimately have one actually use.
            for (String name : memberNames(TranCatBalRepository.class)) {
                assertThat(ALTERNATE_INDEX_NAME_FRAGMENTS)
                        .as("member '%s' looks like an alternate-index accessor", name)
                        .noneMatch(name::contains);
            }
            for (String name : memberNames(TranCatBalFile.class)) {
                assertThat(ALTERNATE_INDEX_NAME_FRAGMENTS)
                        .as("member '%s' looks like an alternate-index accessor", name)
                        .noneMatch(name::contains);
            }
        }

        @Test
        @DisplayName("no mainframe dataset name is compiled in - gate G46")
        void noMainframeDatasetNameIsCompiledIn() {
            // The DD name is a copybook-level fact and is compiled in; the DSNAME behind it is a
            // deployment input, resolved from carddemo.datasets.TCATBALF. So the constant that names the
            // DD is present and the one that would name the dataset must not be.
            assertThat(TranCatBalRepository.DD_NAME).isEqualTo("TCATBALF");
            for (String constant : staticStringConstants(TranCatBalRepository.class)) {
                assertThat(constant).doesNotContain("AWS.M2.CARDDEMO").doesNotContain(".VSAM.");
            }
            for (String constant : staticStringConstants(TranCatBalRecord.class)) {
                assertThat(constant).doesNotContain("AWS.M2.CARDDEMO").doesNotContain(".VSAM.");
            }

            // Nor is one reachable through the binding: point the DD at a different name and that is the
            // name the repository reports, which is only possible because nothing was inlined.
            TranCatBalRepository elsewhere = new TranCatBalRepository(new JdbcTemplate(),
                    bindings("SOME.OTHER.DATASET", FIFTY, SEVENTEEN), ASCII,
                    RecordImageForm.CHARACTER);
            assertThat(elsewhere.datasetName())
                    .isEqualTo("SOME.OTHER.DATASET")
                    .doesNotContain("AWS.M2.CARDDEMO");

            // The statements over the configured relation name that relation and no other, so the dataset
            // a statement addresses is always the configured one.
            Statements sql = repository(seeded(fixtureRows())).resolveStatements();
            assertThat(quotedRelations(sql.selectByKey())).containsExactly(TEST_DSNAME);
            assertThat(sql.selectByKey()).doesNotContain("AWS.M2.CARDDEMO");
        }
    }

    // =============================================================================================
    // Reflection helpers for the absence assertions. Kept at the bottom: they prove properties of the
    // production types rather than exercising behaviour, and nothing else in the class needs them.
    // =============================================================================================

    /**
     * Asserts that neither a type nor any of its declared members carries a persistence-mapping
     * annotation.
     *
     * @param type the type to inspect
     */
    private static void assertNoMappingAnnotations(Class<?> type) {
        assertAnnotationsAreNotMappings(type.getSimpleName(), type.getAnnotations());
        for (Field field : type.getDeclaredFields()) {
            assertAnnotationsAreNotMappings(type.getSimpleName() + "." + field.getName(),
                    field.getAnnotations());
        }
        for (Method method : type.getDeclaredMethods()) {
            assertAnnotationsAreNotMappings(type.getSimpleName() + "." + method.getName() + "()",
                    method.getAnnotations());
        }
    }

    /**
     * Asserts that none of the given annotations is one of the mapping annotations.
     *
     * @param subject     what is being inspected, for the failure message
     * @param annotations the annotations found on it
     */
    private static void assertAnnotationsAreNotMappings(String subject, Annotation[] annotations) {
        for (Annotation annotation : annotations) {
            String name = annotation.annotationType().getSimpleName();
            assertThat(MAPPING_ANNOTATION_NAMES)
                    .as("%s carries the mapping annotation @%s", subject, name)
                    .doesNotContain(name);
        }
    }

    /**
     * The lower-cased names of every field and method a type declares, including inherited public ones.
     *
     * @param type the type to inspect
     * @return the names, lower-cased so a substring test is case-insensitive
     */
    private static List<String> memberNames(Class<?> type) {
        List<String> names = new ArrayList<>();
        for (Field field : type.getDeclaredFields()) {
            names.add(field.getName().toLowerCase(Locale.ROOT));
        }
        for (Method method : type.getMethods()) {
            names.add(method.getName().toLowerCase(Locale.ROOT));
        }
        return names;
    }

    /**
     * The values of every {@code static} {@link String} field a type declares, whatever its visibility.
     *
     * <p>Private constants are read too, deliberately: a hard-coded dataset name would most naturally be
     * written as a private constant, so an assertion that only looked at the public ones would miss it.
     *
     * @param type the type to inspect
     * @return the constant values, never {@code null} entries
     */
    private static List<String> staticStringConstants(Class<?> type) {
        List<String> values = new ArrayList<>();
        for (Field field : type.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers()) || field.getType() != String.class) {
                continue;
            }
            field.setAccessible(true);
            try {
                Object value = field.get(null);
                if (value != null) {
                    values.add((String) value);
                }
            } catch (IllegalAccessException unreachable) {
                throw new IllegalStateException(
                        "The constant " + type.getSimpleName() + "." + field.getName()
                                + " could not be read to prove no dataset name is compiled in",
                        unreachable);
            }
        }
        return values;
    }

    /**
     * The identifiers a statement quotes, in the order they appear.
     *
     * <p>Splitting on the quote character rather than matching a pattern, so the helper has no behaviour of
     * its own to get wrong: the odd-numbered segments of the split are exactly the quoted spans.
     *
     * @param sql the statement to scan
     * @return the quoted identifiers, which for this repository must always be the one dataset
     */
    private static List<String> quotedIdentifiers(String sql) {
        List<String> identifiers = new ArrayList<>();
        String[] segments = sql.split("\"", -1);
        for (int index = 1; index < segments.length; index += 2) {
            identifiers.add(segments[index]);
        }
        return identifiers;
    }

    /**
     * The relations a statement names: its quoted identifiers with the record-image column set aside.
     *
     * <p>Both kinds of identifier are quoted, because a dataset name contains full stops and a column name
     * is whatever the backend calls it, and neither should be at the mercy of a dialect's folding rules.
     * Only the relation is interesting for counting how many datasets a statement touches.
     *
     * @param sql the statement to scan
     * @return the quoted identifiers that are not the record-image column
     */
    private static List<String> quotedRelations(String sql) {
        List<String> relations = new ArrayList<>(quotedIdentifiers(sql));
        relations.removeIf(RECORD_IMAGE_COLUMN::equals);
        return relations;
    }
}
