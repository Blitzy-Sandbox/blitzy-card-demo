package com.vsergeychik.carddemo.account;

import com.vsergeychik.carddemo.account.AccountRepository.AccountFile;
import com.vsergeychik.carddemo.account.AccountRepository.OpenMode;
import com.vsergeychik.carddemo.account.AccountRepository.ReadResult;
import com.vsergeychik.carddemo.account.AccountRepository.Statements;
import com.vsergeychik.carddemo.account.AccountRepository.WriteResult;
import com.vsergeychik.carddemo.account.model.AccountRecord;
import com.vsergeychik.carddemo.common.CicsResponse;
import com.vsergeychik.carddemo.common.DatasetIntegrityException;
import com.vsergeychik.carddemo.common.FileStatus;
import com.vsergeychik.carddemo.common.FileStatus.Outcome;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBinding;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBindings;
import com.vsergeychik.carddemo.common.RecordImageForm;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * Unit tests for {@link AccountRepository}, the account master dataset's only reader and only writer.
 *
 * <h2>Two kinds of test, and why both are needed</h2>
 *
 * <p><strong>Round trips against a real single-column relation.</strong> The dataset is seeded from
 * {@code src/test/resources/fixtures/acctdata.txt} - the classpath copy of
 * {@code app/data/ASCII/acctdata.txt}, 50 records of exactly 300 bytes each - into an in-memory
 * relation with one record-image column, which is precisely the shape the parity harness seeds and the
 * shape the production gateway is expected to present. That is what makes the byte-level assertions
 * meaningful: a record read and re-encoded has to come back identical, {@code FILLER X(178)} included.
 *
 * <p><strong>Mocked JDBC for the failure arms.</strong> An unreachable dataset, a relation that
 * describes no record-image column, and a row whose image is absent cannot be produced by a healthy
 * database, so they are driven through mocks. Every one of them must reach a reported file status or a
 * thrown contract violation - never a silent success.
 *
 * <h2>No dataset name from the real system appears here</h2>
 *
 * <p>The tests supply their own dataset name. The real one lives only in {@code application.yml}, which
 * is the whole point of the binding the constructor resolves, so this file needs no mainframe dataset
 * literal and contains none.
 *
 * <h2>Expectations are transcribed from the COBOL</h2>
 *
 * <p>The statuses, the {@code APPL-RESULT} values and the outcome ladders below were written by reading
 * {@code app/cbl/CBACT01C.cbl:L92-L167}, {@code app/cbl/CBACT04C.cbl:L289-L391} and
 * {@code app/cbl/COACTUPC.cbl:L3888-L4081} and are restated here rather than read back off the
 * implementation, so agreement between the two is an audit and not a tautology.
 */
@DisplayName("AccountRepository - the account master dataset over JDBC")
class AccountRepositoryTest {

    /** The code page every test names explicitly; never a platform default. */
    private static final Charset ASCII = StandardCharsets.US_ASCII;

    /** A stand-in dataset name. The real one lives only in {@code application.yml}. */
    private static final String TEST_DSNAME = "TEST.ACCOUNT.KSDS";

    /** The record-image column of the seeded relation, and the name the probe will discover. */
    private static final String RECORD_IMAGE_COLUMN = "REC";

    /** The classpath location of the account fixture, copied from the reference tree. */
    private static final String FIXTURE = "/fixtures/acctdata.txt";

    /** The fixture's measured record count, restated from the reference data. */
    private static final int FIXTURE_RECORDS = 50;

    /** The copybook record width, restated from {@code app/cpy/CVACT01Y.cpy:L2} ({@code RECLN 300}). */
    private static final int THREE_HUNDRED = 300;

    /** The copybook key width, restated from {@code ACCT-ID PIC 9(11)}. */
    private static final int ELEVEN = 11;

    /** The declared width of {@code FILLER}, restated from {@code app/cpy/CVACT01Y.cpy:L17}. */
    private static final int FILLER_WIDTH = 178;

    /** An account identifier the fixture does not contain, for the invalid-key arms. */
    private static final long ABSENT_ACCT_ID = 99_999_999_999L;

    /**
     * Distinguishes the in-memory database each seeded test uses, so no two tests share a relation.
     *
     * <p>A counter rather than a random or time-derived name, so a run is reproducible.
     */
    private static final AtomicInteger DATABASE_SEQUENCE = new AtomicInteger();

    /**
     * How long a second connection waits for a row lock before reporting failure.
     *
     * <p>Short, because the point is to observe that a lock is held rather than to wait out a realistic
     * one, and a held lock must not stall the suite.
     */
    private static final int LOCK_TIMEOUT_MILLIS = 250;

    // =============================================================================================
    // Fixtures and helpers.
    // =============================================================================================

    /**
     * Builds the pair of bindings the repository resolves, with every component under the test's
     * control so each of the constructor's checks can be driven both ways.
     *
     * @param cicsDsname   the dataset name for the CICS file key
     * @param batchDsname  the dataset name for the batch DD key
     * @param recordLength the record length to configure for both
     * @param keyLength    the key length to configure for both, or {@code null} to omit it as
     *                     {@code application.yml} does
     * @return a catalogue containing exactly those two bindings
     */
    private static DatasetBindings bindings(String cicsDsname, String batchDsname, int recordLength,
            Integer keyLength) {
        DatasetBindings catalogue = new DatasetBindings();
        catalogue.put(AccountRepository.CICS_FILE_NAME, new DatasetBinding(cicsDsname, "ksds", false,
                "FB", null, recordLength, "CVACT01Y", keyLength, null, null, null));
        catalogue.put(AccountRepository.BATCH_DD_NAME, new DatasetBinding(batchDsname, "ksds", false,
                "FB", null, recordLength, "CVACT01Y", keyLength, null, null, null));
        return catalogue;
    }

    /**
     * The correctly configured binding pair every behavioural test uses.
     *
     * @return a catalogue naming {@link #TEST_DSNAME} at the copybook width under both keys
     */
    private static DatasetBindings validBindings() {
        return bindings(TEST_DSNAME, TEST_DSNAME, THREE_HUNDRED, null);
    }

    /**
     * A repository over the given template and the correctly configured bindings.
     *
     * @param template the template to reach the relation with
     * @return the repository
     */
    private static AccountRepository repository(JdbcTemplate template) {
        return new AccountRepository(template, validBindings(), ASCII, RecordImageForm.CHARACTER);
    }

    /**
     * Creates a private in-memory relation with one record-image column and seeds it.
     *
     * <p>The relation is deliberately created without a key constraint, so a test can seed two rows
     * under one key and drive the arm that a unique key would otherwise make unreachable.
     *
     * @param rows the record images to insert, in the order given
     * @return a template over the seeded relation
     */
    private static JdbcTemplate seeded(List<String> rows) {
        return seeded(rows, THREE_HUNDRED);
    }

    /**
     * Creates a private in-memory relation whose record-image column is as wide as asked, and seeds it.
     *
     * <p>The width is a parameter for one reason only: a test has to be able to store an image
     * <em>wider</em> than the copybook declares, in order to prove that such an image is rejected rather
     * than truncated. Every other test uses the copybook width.
     *
     * @param rows        the record images to insert, in the order given
     * @param columnWidth the declared width of the record-image column
     * @return a template over the seeded relation
     */
    private static JdbcTemplate seeded(List<String> rows, int columnWidth) {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:acctrepo" + DATABASE_SEQUENCE.incrementAndGet()
                        + ";DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE", "sa", "");
        dataSource.setDriverClassName("org.h2.Driver");
        JdbcTemplate template = new JdbcTemplate(dataSource);
        template.execute("CREATE TABLE \"" + TEST_DSNAME + "\" (" + RECORD_IMAGE_COLUMN
                + " VARCHAR(" + columnWidth + "))");
        for (String row : rows) {
            template.update("INSERT INTO \"" + TEST_DSNAME + "\" VALUES (?)", row);
        }
        return template;
    }

    /**
     * The 50 fixture records, exactly as stored.
     *
     * @return the fixture's lines
     */
    private static List<String> fixtureRows() {
        try (InputStream stream = AccountRepositoryTest.class.getResourceAsStream(FIXTURE)) {
            if (stream == null) {
                throw new IllegalStateException("The account fixture " + FIXTURE + " is absent from "
                        + "the test classpath; every expectation in this class is seeded from it");
            }
            return new String(stream.readAllBytes(), ASCII).lines().toList();
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    /**
     * The stored key image of a fixture row: its leading eleven bytes.
     *
     * @param row a fixture record image
     * @return the eleven-character key image
     */
    private static String keyImageOf(String row) {
        return row.substring(0, ELEVEN);
    }

    /**
     * Drains a browse into a list, stopping at the first end-of-file outcome.
     *
     * @param file  the opened file to browse
     * @param limit the most reads to perform, so a defect cannot loop for ever
     * @return the record images returned, in the order returned
     */
    private static List<String> drain(AccountFile file, int limit) {
        List<String> images = new ArrayList<>();
        for (int read = 0; read < limit; read++) {
            ReadResult result = file.readNext();
            if (!result.isFound()) {
                break;
            }
            images.add(result.account().orElseThrow().toFixedWidthString());
        }
        return images;
    }

    /**
     * Opens the account master for input over a template, asserting nothing.
     *
     * @param template the template to reach the relation with
     * @return the opened file
     */
    private static AccountFile openedInput(JdbcTemplate template) {
        return repository(template).open(OpenMode.INPUT);
    }

    /**
     * Runs an action with the calling thread marked as being inside a transaction.
     *
     * <p>The marker rather than a real transaction, because the failure arms are driven through mocked
     * JDBC chains that cannot yield a connection at all - a real transaction manager would fail while
     * trying to begin, before the operation under test ran. What
     * {@link AccountRepository#readForUpdate(String)} inspects is exactly this marker, so this is the
     * honest way to reach the code after the guard.
     *
     * @param action the action to run
     * @param <T>    the action's result type
     * @return the action's result
     */
    private static <T> T withUnitOfWork(Supplier<T> action) {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            return action.get();
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    /**
     * A transaction template over a seeded relation, for the tests that need a genuine unit of work.
     *
     * @param template the template whose data source the transaction is bound to
     * @return a transaction template that begins and commits a real transaction
     */
    private static TransactionTemplate transactionOver(JdbcTemplate template) {
        return new TransactionTemplate(new DataSourceTransactionManager(template.getDataSource()));
    }

    /**
     * A mocked chain that describes a usable column and records the text of every statement prepared
     * against it, returning no rows.
     *
     * <p>This is how the locking read is proved to be a locking read without depending on any backend's
     * behaviour: what the repository asks the driver for is captured verbatim, so a test can assert that
     * a read for update carries {@code FOR UPDATE} and a plain keyed read does not.
     *
     * @param prepared the list every prepared statement's text is appended to, in order
     * @return a template over the recording chain
     * @throws SQLException never; declared because the mocked JDBC methods declare it
     */
    private static JdbcTemplate recordingPreparedStatements(List<String> prepared)
            throws SQLException {
        DataSource dataSource = Mockito.mock(DataSource.class);
        Connection connection = Mockito.mock(Connection.class);
        Statement statement = Mockito.mock(Statement.class);
        ResultSet probeResultSet = Mockito.mock(ResultSet.class);
        ResultSetMetaData metaData = Mockito.mock(ResultSetMetaData.class);
        PreparedStatement preparedStatement = Mockito.mock(PreparedStatement.class);
        ResultSet emptyResultSet = Mockito.mock(ResultSet.class);
        Mockito.when(dataSource.getConnection()).thenReturn(connection);
        Mockito.when(connection.createStatement()).thenReturn(statement);
        Mockito.when(statement.executeQuery(Mockito.anyString())).thenReturn(probeResultSet);
        Mockito.when(probeResultSet.getMetaData()).thenReturn(metaData);
        Mockito.when(metaData.getColumnCount()).thenReturn(1);
        Mockito.when(metaData.getColumnName(1)).thenReturn(RECORD_IMAGE_COLUMN);
        Mockito.when(connection.prepareStatement(Mockito.anyString())).thenAnswer(invocation -> {
            prepared.add(invocation.getArgument(0));
            return preparedStatement;
        });
        Mockito.when(preparedStatement.executeQuery()).thenReturn(emptyResultSet);
        Mockito.when(preparedStatement.execute()).thenReturn(true);
        Mockito.when(preparedStatement.getResultSet()).thenReturn(emptyResultSet);
        Mockito.when(preparedStatement.getUpdateCount()).thenReturn(0);
        Mockito.when(emptyResultSet.next()).thenReturn(false);
        return new JdbcTemplate(dataSource);
    }

    /**
     * A mocked chain whose keyed count finds exactly one row and whose {@code UPDATE} then reports two.
     *
     * <p>The race the pre-write count cannot close: between the count and the {@code UPDATE} the relation
     * changed, so the write replaced rows the count never saw. It is unreachable against a unique primary
     * key and is mocked rather than assumed away, because the outcome it produces - rows already
     * overwritten - is the one outcome a file status must not carry.
     *
     * @return a template whose count says one and whose update says two
     * @throws SQLException never; declared because the mocked JDBC methods declare it
     */
    private static JdbcTemplate describingThenFanningOutOnUpdate() throws SQLException {
        return countingOneThenReportingUpdateCount(2);
    }

    /**
     * A mocked chain whose keyed count finds exactly one row and whose {@code UPDATE} then reports none.
     *
     * <p>The other side of the race the count cannot close: the row the count saw was deleted before the
     * write reached it. Nothing was written, so the outcome is the invalid-key condition - which is what
     * it would have been had the row never existed - and must not be confused with the fan-out case,
     * where something <em>was</em> written.
     *
     * @return a template whose count says one and whose update says none
     * @throws SQLException never; declared because the mocked JDBC methods declare it
     */
    private static JdbcTemplate describingThenLosingTheRowBeforeUpdate() throws SQLException {
        return countingOneThenReportingUpdateCount(0);
    }

    /**
     * A mocked chain whose keyed count finds exactly one row and whose {@code UPDATE} reports the count
     * asked for.
     *
     * @param updateCount what {@code executeUpdate} should report
     * @return a template over the mocked chain
     * @throws SQLException never; declared because the mocked JDBC methods declare it
     */
    private static JdbcTemplate countingOneThenReportingUpdateCount(int updateCount)
            throws SQLException {
        DataSource dataSource = Mockito.mock(DataSource.class);
        Connection connection = Mockito.mock(Connection.class);
        Statement statement = Mockito.mock(Statement.class);
        ResultSet probeResultSet = Mockito.mock(ResultSet.class);
        ResultSetMetaData metaData = Mockito.mock(ResultSetMetaData.class);
        PreparedStatement preparedStatement = Mockito.mock(PreparedStatement.class);
        ResultSet oneRow = Mockito.mock(ResultSet.class);
        Mockito.when(dataSource.getConnection()).thenReturn(connection);
        Mockito.when(connection.createStatement()).thenReturn(statement);
        Mockito.when(statement.executeQuery(Mockito.anyString())).thenReturn(probeResultSet);
        Mockito.when(probeResultSet.getMetaData()).thenReturn(metaData);
        Mockito.when(metaData.getColumnCount()).thenReturn(1);
        Mockito.when(metaData.getColumnName(1)).thenReturn(RECORD_IMAGE_COLUMN);
        Mockito.when(connection.prepareStatement(Mockito.anyString())).thenReturn(preparedStatement);
        Mockito.when(preparedStatement.executeQuery()).thenReturn(oneRow);
        // One row, then exhausted: the count the rewrite requires before it writes.
        Mockito.when(oneRow.next()).thenReturn(true, false);
        Mockito.when(preparedStatement.executeUpdate()).thenReturn(updateCount);
        return new JdbcTemplate(dataSource);
    }

    /**
     * Whether an independent connection can take an exclusive lock on the row carrying a key.
     *
     * <p>A genuinely separate connection, obtained from the data source directly rather than through
     * {@code DataSourceUtils}, so it is not the transaction-bound one the repository is using. Its lock
     * timeout is set to a fraction of a second, so a held lock is reported as a failure quickly instead
     * of stalling the suite.
     *
     * @param dataSource the seeded relation's data source
     * @param keyImage   the eleven-character key of the row to try to lock
     * @return {@code true} when the lock was granted, {@code false} when it was refused or timed out
     */
    private static boolean anotherConnectionCanLock(DataSource dataSource, String keyImage) {
        try (Connection other = dataSource.getConnection()) {
            other.setAutoCommit(false);
            try (Statement timeout = other.createStatement()) {
                timeout.execute("SET LOCK_TIMEOUT " + LOCK_TIMEOUT_MILLIS);
            }
            try (PreparedStatement locking = other.prepareStatement("SELECT * FROM \"" + TEST_DSNAME
                    + "\" WHERE " + RECORD_IMAGE_COLUMN + " LIKE ? FOR UPDATE")) {
                locking.setString(1, keyImage + "%");
                try (ResultSet locked = locking.executeQuery()) {
                    return locked.next();
                }
            } catch (SQLException refused) {
                return false;
            } finally {
                other.rollback();
            }
        } catch (SQLException unusable) {
            throw new IllegalStateException("The second connection could not be established, so the "
                    + "lock could not be tested", unusable);
        }
    }

    /**
     * A mocked chain whose connection cannot be obtained at all.
     *
     * @return a template that translates every operation into a data-access failure
     */
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

    /**
     * A mocked chain whose probe reports the given column metadata.
     *
     * @param columnCount how many columns the relation describes
     * @param columnName  the name of the column at position one
     * @return a template whose probe answers with that metadata
     * @throws SQLException never; declared because the mocked JDBC methods declare it
     */
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

    /**
     * A mocked chain whose probe describes no metadata at all.
     *
     * @return a template whose probe answers with a null metadata object
     * @throws SQLException never; declared because the mocked JDBC methods declare it
     */
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

    /**
     * A mocked chain that describes a usable column but refuses every prepared statement.
     *
     * @return a template whose probe succeeds and whose reads and writes fail
     * @throws SQLException never; declared because the mocked JDBC methods declare it
     */
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

    /**
     * A mocked chain that describes a usable column and then returns one row with no record image.
     *
     * @return a template whose reads yield a row whose image is absent
     * @throws SQLException never; declared because the mocked JDBC methods declare it
     */
    private static JdbcTemplate describingThenReturningNoImage() throws SQLException {
        DataSource dataSource = Mockito.mock(DataSource.class);
        Connection connection = Mockito.mock(Connection.class);
        Statement statement = Mockito.mock(Statement.class);
        ResultSet probeResultSet = Mockito.mock(ResultSet.class);
        ResultSetMetaData metaData = Mockito.mock(ResultSetMetaData.class);
        PreparedStatement preparedStatement = Mockito.mock(PreparedStatement.class);
        ResultSet rowResultSet = Mockito.mock(ResultSet.class);
        Mockito.when(dataSource.getConnection()).thenReturn(connection);
        Mockito.when(connection.createStatement()).thenReturn(statement);
        Mockito.when(statement.executeQuery(Mockito.anyString())).thenReturn(probeResultSet);
        Mockito.when(probeResultSet.getMetaData()).thenReturn(metaData);
        Mockito.when(metaData.getColumnCount()).thenReturn(1);
        Mockito.when(metaData.getColumnName(1)).thenReturn(RECORD_IMAGE_COLUMN);
        Mockito.when(connection.prepareStatement(Mockito.anyString())).thenReturn(preparedStatement);
        Mockito.when(preparedStatement.executeQuery()).thenReturn(rowResultSet);
        Mockito.when(rowResultSet.next()).thenReturn(true, false);
        Mockito.when(rowResultSet.getString(1)).thenReturn(null);
        return new JdbcTemplate(dataSource);
    }

    // =============================================================================================
    // Construction: the bindings and the geometry they must declare.
    // =============================================================================================

    /** Everything the constructor establishes before the context finishes starting. */
    @Nested
    @DisplayName("Construction resolves the bindings and proves the geometry")
    class ConstructionTests {

        @Test
        @DisplayName("resolves the configured dataset name, the record width and the code page")
        void resolvesTheConfiguredBinding() {
            AccountRepository repository = repository(new JdbcTemplate());

            assertThat(repository.datasetName()).isEqualTo(TEST_DSNAME);
            assertThat(repository.recordLength()).isEqualTo(THREE_HUNDRED);
            assertThat(repository.datasetCharset()).isEqualTo(ASCII);
        }

        @Test
        @DisplayName("names the DD keys the CSD and the JCL name, and delegates the geometry")
        void namesTheDdKeysAndDelegatesTheGeometry() {
            assertThat(AccountRepository.CICS_FILE_NAME).isEqualTo("ACCTDAT");
            assertThat(AccountRepository.BATCH_DD_NAME).isEqualTo("ACCTFILE");
            assertThat(AccountRepository.RECORD_LENGTH).isEqualTo(AccountRecord.RECORD_LENGTH)
                    .isEqualTo(THREE_HUNDRED);
            assertThat(AccountRepository.KEY_LENGTH).isEqualTo(AccountRecord.KEY_LENGTH)
                    .isEqualTo(ELEVEN);
            assertThat(AccountRepository.RECORD_IMAGE_COLUMN_INDEX).isOne();
            assertThat(AccountRepository.APPL_RESULT_FATAL).isEqualTo(12);
        }

        @Test
        @DisplayName("a permanent-error status is '9' plus a binary feedback byte, so it renders as 9000")
        void permanentErrorStatusFollowsTheCobolConvention() {
            assertThat(AccountRepository.PERMANENT_ERROR_STATUS)
                    .hasSize(FileStatus.STATUS_LENGTH)
                    .startsWith("9");
            assertThat(FileStatus.toStatusImage(AccountRepository.PERMANENT_ERROR_STATUS))
                    .isEqualTo("9000");
            assertThat(FileStatus.outcomeOfStatus(AccountRepository.PERMANENT_ERROR_STATUS))
                    .isEqualTo(Outcome.OTHER);
        }

        @Test
        @DisplayName("accepts a declared key length of eleven")
        void acceptsADeclaredKeyLengthOfEleven() {
            AccountRepository repository = new AccountRepository(new JdbcTemplate(),
                    bindings(TEST_DSNAME, TEST_DSNAME, THREE_HUNDRED, ELEVEN), ASCII, RecordImageForm.CHARACTER);

            assertThat(repository.datasetName()).isEqualTo(TEST_DSNAME);
        }

        @Test
        @DisplayName("refuses a declared key length that is not eleven")
        void refusesAnUnexpectedDeclaredKeyLength() {
            DatasetBindings wrongKey = bindings(TEST_DSNAME, TEST_DSNAME, THREE_HUNDRED, 12);

            assertThatIllegalStateException()
                    .isThrownBy(() -> new AccountRepository(new JdbcTemplate(), wrongKey, ASCII, RecordImageForm.CHARACTER))
                    .withMessageContaining("key length")
                    .withMessageContaining("ACCT-ID PIC 9(11)");
        }

        @Test
        @DisplayName("refuses a configured record width that is not the copybook's 300")
        void refusesAnUnexpectedRecordWidth() {
            DatasetBindings wrongWidth = bindings(TEST_DSNAME, TEST_DSNAME, 350, null);

            assertThatIllegalStateException()
                    .isThrownBy(() -> new AccountRepository(new JdbcTemplate(), wrongWidth, ASCII, RecordImageForm.CHARACTER))
                    .withMessageContaining("record length")
                    .withMessageContaining("RECLN 300");
        }

        @Test
        @DisplayName("refuses bindings that name two different datasets for one account master")
        void refusesTwoDifferentDatasets() {
            DatasetBindings split = bindings(TEST_DSNAME, "TEST.OTHER.KSDS", THREE_HUNDRED, null);

            assertThatIllegalStateException()
                    .isThrownBy(() -> new AccountRepository(new JdbcTemplate(), split, ASCII, RecordImageForm.CHARACTER))
                    .withMessageContaining("different datasets");
        }

        @Test
        @DisplayName("refuses an unconfigured DD name, naming the keys that are configured")
        void refusesAnUnconfiguredDdName() {
            DatasetBindings incomplete = new DatasetBindings();
            incomplete.put(AccountRepository.BATCH_DD_NAME, new DatasetBinding(TEST_DSNAME, "ksds",
                    false, "FB", null, THREE_HUNDRED, "CVACT01Y", null, null, null, null));

            assertThatIllegalStateException()
                    .isThrownBy(() -> new AccountRepository(new JdbcTemplate(), incomplete, ASCII, RecordImageForm.CHARACTER))
                    .withMessageContaining(AccountRepository.CICS_FILE_NAME);
        }

        @ParameterizedTest(name = "dataset name [{0}]")
        @ValueSource(strings = { "", "   " })
        @DisplayName("refuses a blank dataset name")
        void refusesABlankDatasetName(String blank) {
            DatasetBindings blankName = bindings(blank, blank, THREE_HUNDRED, null);

            assertThatIllegalStateException()
                    .isThrownBy(() -> new AccountRepository(new JdbcTemplate(), blankName, ASCII, RecordImageForm.CHARACTER))
                    .withMessageContaining("declares no dataset name");
        }

        @Test
        @DisplayName("refuses an absent dataset name")
        void refusesAnAbsentDatasetName() {
            DatasetBindings absentName = bindings(null, null, THREE_HUNDRED, null);

            assertThatIllegalStateException()
                    .isThrownBy(() -> new AccountRepository(new JdbcTemplate(), absentName, ASCII, RecordImageForm.CHARACTER))
                    .withMessageContaining("declares no dataset name");
        }

        @Test
        @DisplayName("refuses a dataset name carrying a control character")
        void refusesAControlCharacterInTheDatasetName() {
            String corrupt = "TEST.ACCOUNT\u0001KSDS";
            DatasetBindings corruptName = bindings(corrupt, corrupt, THREE_HUNDRED, null);

            // The grammar decides, so the diagnostic names the offending position rather than the
            // category of character - which is what a reader needs to correct the configured value.
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new AccountRepository(new JdbcTemplate(), corruptName, ASCII, RecordImageForm.CHARACTER))
                    .withMessageContaining("well-formed z/OS dataset name");
        }

        @Test
        @DisplayName("refuses a missing collaborator rather than defaulting one")
        void refusesAMissingCollaborator() {
            DatasetBindings catalogue = validBindings();

            assertThatNullPointerException()
                    .isThrownBy(() -> new AccountRepository(null, catalogue, ASCII, RecordImageForm.CHARACTER))
                    .withMessageContaining("JdbcTemplate");
            assertThatNullPointerException()
                    .isThrownBy(() -> new AccountRepository(new JdbcTemplate(), null, ASCII, RecordImageForm.CHARACTER))
                    .withMessageContaining("carddemo.datasets");
            assertThatNullPointerException()
                    .isThrownBy(() -> new AccountRepository(new JdbcTemplate(), catalogue, null, RecordImageForm.CHARACTER))
                    .withMessageContaining("charset");
        }
    }

    // =============================================================================================
    // The composed statements.
    // =============================================================================================

    /** The statements the repository issues, asserted as text rather than inferred from behaviour. */
    @Nested
    @DisplayName("Statement composition")
    class StatementCompositionTests {

        @Test
        @DisplayName("the probe delimits the dataset name and transfers no rows")
        void probeDelimitsTheDatasetNameAndTransfersNothing() {
            AccountRepository repository = repository(new JdbcTemplate());

            assertThat(repository.columnProbeSql())
                    .isEqualTo("SELECT * FROM \"" + TEST_DSNAME + "\" WHERE 1 = 0");
        }

        @Test
        @DisplayName("a quote inside a configured name is escaped by repetition, not by removal")
        void aQuoteInsideANameIsRefused() {
            String awkward = "TEST.\"ACCOUNT\".KSDS";
            DatasetBindings quoted = bindings(awkward, awkward, THREE_HUNDRED, null);

            // A quotation mark is not a character a z/OS dataset name admits, so the name is refused
            // rather than quoted into an identifier. The grammar is the defence; the delimited rendering
            // that follows it is belt and braces, and is asserted directly on DatasetRelation.
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new AccountRepository(new JdbcTemplate(), quoted, ASCII, RecordImageForm.CHARACTER))
                    .withMessageContaining("well-formed z/OS dataset name");
        }

        @Test
        @DisplayName("every statement names the discovered column and states its ordering explicitly")
        void everyStatementNamesTheDiscoveredColumn() {
            AccountRepository repository = repository(seeded(List.of()));

            Statements statements = repository.resolveStatements();

            assertThat(statements).isNotNull();
            String dataset = "\"" + TEST_DSNAME + "\"";
            String column = "\"" + RECORD_IMAGE_COLUMN + "\"";
            String keyed = " WHERE " + column + " LIKE ? ESCAPE '\\'";
            assertThat(statements.selectFirst())
                    .isEqualTo("SELECT * FROM " + dataset + " ORDER BY " + column + " ASC");
            assertThat(statements.selectNext())
                    .isEqualTo("SELECT * FROM " + dataset + " WHERE " + column + " > ? ORDER BY "
                            + column + " ASC");
            assertThat(statements.selectByKey())
                    .isEqualTo("SELECT * FROM " + dataset + " WHERE " + column
                            + " LIKE ? ESCAPE '\\' ORDER BY " + column + " ASC");
            assertThat(statements.selectByKeyForUpdate())
                    .isEqualTo(statements.selectByKey() + " FOR UPDATE");
            assertThat(statements.rewrite())
                    .isEqualTo("UPDATE " + dataset + " SET " + column + " = ?" + keyed);
            assertThat(statements.selectFirst()).doesNotContain("FETCH", "OFFSET", "LIMIT");
        }

        @Test
        @DisplayName("the locking select is the plain keyed select plus the clause, and nothing else")
        void theLockingSelectDiffersOnlyByTheClause() {
            Statements statements = repository(seeded(List.of())).resolveStatements();

            assertThat(statements.selectByKeyForUpdate())
                    .isEqualTo(statements.selectByKey() + " FOR UPDATE");
            assertThat(statements.selectByKey()).doesNotContain("FOR UPDATE");
        }

        @Test
        @DisplayName("the shape is resolved afresh per call and cached nowhere on the singleton")
        void theShapeIsResolvedAfreshAndCachedNowhere() {
            AccountRepository repository = repository(seeded(List.of()));

            Statements first = repository.resolveStatements();
            Statements second = repository.resolveStatements();

            // Equal in every component, because the dataset and the discovered column cannot change -
            // and NOT the same instance, because nothing is held between calls. A cache here would be
            // shared mutable state on a Spring singleton, which is what F02 is about.
            assertThat(second).isEqualTo(first).isNotSameAs(first);
        }
    }

    // =============================================================================================
    // OPEN and CLOSE.
    // =============================================================================================

    /** The two {@code OPEN} verbs, the {@code CLOSE}, and what each does to the file position. */
    @Nested
    @DisplayName("OPEN INPUT, OPEN I-O and CLOSE")
    class OpenAndCloseTests {

        @ParameterizedTest
        @EnumSource(OpenMode.class)
        @DisplayName("a successful open reports '00' and its handle records the verb that was issued")
        void aSuccessfulOpenRecordsTheVerb(OpenMode mode) {
            try (AccountFile file = repository(seeded(List.of())).open(mode)) {
                assertThat(file.openStatus()).isEqualTo(FileStatus.OK);
                assertThat(file.openOutcome()).isSameAs(Outcome.OK);
                assertThat(file.mode()).isSameAs(mode);
                assertThat(file.datasetName()).isEqualTo(TEST_DSNAME);
                assertThat(file.isClosed()).isFalse();
            }
        }

        @Test
        @DisplayName("the two verbs are the ones the estate issues, spelled as the source spells them")
        void theTwoVerbsAreTheOnesTheEstateIssues() {
            assertThat(OpenMode.values()).containsExactly(OpenMode.INPUT, OpenMode.I_O);
            assertThat(OpenMode.INPUT.cobolVerb()).isEqualTo("OPEN INPUT");
            assertThat(OpenMode.I_O.cobolVerb()).isEqualTo("OPEN I-O");
        }

        @Test
        @DisplayName("a failed open yields a handle that reports the failure from every operation")
        void aFailedOpenReportsItsFailureFromEveryOperation() {
            try (AccountFile file = repository(unreachable()).open(OpenMode.I_O)) {
                assertThat(file.openStatus()).isEqualTo(AccountRepository.PERMANENT_ERROR_STATUS);
                assertThat(file.openOutcome()).isSameAs(Outcome.OTHER);

                // A caller that ignored openStatus() still cannot mistake a dataset it never reached
                // for one that was empty: the open's own status comes back, not a fresh one and not
                // end of file.
                ReadResult browsed = file.readNext();
                assertThat(browsed.isEndOfFile()).isFalse();
                assertThat(browsed.status()).isEqualTo(AccountRepository.PERMANENT_ERROR_STATUS);
                assertThat(file.readByKey(1L).status())
                        .isEqualTo(AccountRepository.PERMANENT_ERROR_STATUS);
                assertThat(withUnitOfWork(() -> file.readForUpdate("0".repeat(ELEVEN))).status())
                        .isEqualTo(AccountRepository.PERMANENT_ERROR_STATUS);
                assertThat(file.rewrite(new AccountRecord(ASCII)).status())
                        .isEqualTo(AccountRepository.PERMANENT_ERROR_STATUS);
            }
        }

        @Test
        @DisplayName("close reports '00' and is idempotent, so try-with-resources is safe")
        void closeReportsOkAndIsIdempotent() {
            AccountFile file = repository(seeded(List.of())).open(OpenMode.I_O);

            assertThat(file.closeFile()).isEqualTo(FileStatus.OK);
            assertThat(file.isClosed()).isTrue();
            assertThat(file.closeFile()).isEqualTo(FileStatus.OK);
            file.close();
            assertThat(file.isClosed()).isTrue();
        }

        @Test
        @DisplayName("close reports a permanent error when the dataset is no longer addressable")
        void closeReportsAPermanentErrorWhenUnreachable() throws SQLException {
            // The open succeeds against a chain that describes the column, and the close then finds the
            // dataset undescribable - the analogue of a dataset de-allocated under an open file. Both of
            // the COBOL's symmetric guards are therefore reachable, and neither is dead code.
            DataSource dataSource = Mockito.mock(DataSource.class);
            Connection connection = Mockito.mock(Connection.class);
            Statement statement = Mockito.mock(Statement.class);
            ResultSet resultSet = Mockito.mock(ResultSet.class);
            ResultSetMetaData metaData = Mockito.mock(ResultSetMetaData.class);
            Mockito.when(dataSource.getConnection()).thenReturn(connection)
                    .thenThrow(new SQLException("the dataset is gone"));
            Mockito.when(connection.createStatement()).thenReturn(statement);
            Mockito.when(statement.executeQuery(Mockito.anyString())).thenReturn(resultSet);
            Mockito.when(resultSet.getMetaData()).thenReturn(metaData);
            Mockito.when(metaData.getColumnCount()).thenReturn(1);
            Mockito.when(metaData.getColumnName(1)).thenReturn(RECORD_IMAGE_COLUMN);

            AccountFile file = repository(new JdbcTemplate(dataSource)).open(OpenMode.INPUT);
            assertThat(file.openStatus()).isEqualTo(FileStatus.OK);

            assertThat(file.closeFile()).isEqualTo(AccountRepository.PERMANENT_ERROR_STATUS);
            assertThat(file.isClosed()).isTrue();
        }

        @Test
        @DisplayName("closing a handle whose open failed reports that open's status, and probes nothing")
        void closingAFailedOpenReportsTheOpenStatus() {
            AccountFile file = repository(unreachable()).open(OpenMode.INPUT);

            assertThat(file.closeFile()).isEqualTo(AccountRepository.PERMANENT_ERROR_STATUS);
            assertThat(file.closeFile()).isEqualTo(FileStatus.OK);
        }

        @Test
        @DisplayName("an open requires a verb rather than defaulting one")
        void anOpenRequiresAVerb() {
            AccountRepository repository = repository(new JdbcTemplate());

            assertThatNullPointerException().isThrownBy(() -> repository.open(null))
                    .withMessageContaining("open mode is required");
        }

        @Test
        @DisplayName("a re-open positions the browse before the first record again")
        void aReopenRepositionsTheBrowse() {
            List<String> rows = fixtureRows();
            AccountRepository repository = repository(seeded(rows));
            String lowestKey = rows.stream().map(AccountRepositoryTest::keyImageOf).sorted()
                    .findFirst().orElseThrow();

            try (AccountFile first = repository.open(OpenMode.INPUT)) {
                assertThat(first.readNext().account().orElseThrow().keyImage()).isEqualTo(lowestKey);
                assertThat(first.readNext().account().orElseThrow().keyImage())
                        .isNotEqualTo(lowestKey);
            }

            // A CLOSE followed by an OPEN INPUT re-reads a COBOL file from its first record, and a fresh
            // handle re-reads this one from its first record too.
            try (AccountFile second = repository.open(OpenMode.INPUT)) {
                assertThat(second.readNext().account().orElseThrow().keyImage()).isEqualTo(lowestKey);
            }
        }

        @Test
        @DisplayName("every operation on a closed handle is a loud defect, never an invented status")
        void everyOperationOnAClosedHandleThrows() {
            AccountFile file = repository(seeded(fixtureRows())).open(OpenMode.I_O);
            file.closeFile();

            assertThatIllegalStateException().isThrownBy(file::readNext)
                    .withMessageContaining("has been closed");
            assertThatIllegalStateException().isThrownBy(() -> file.readByKey(1L))
                    .withMessageContaining("has been closed");
            assertThatIllegalStateException()
                    .isThrownBy(() -> withUnitOfWork(() -> file.readForUpdate("0".repeat(ELEVEN))))
                    .withMessageContaining("has been closed");
            assertThatIllegalStateException()
                    .isThrownBy(() -> file.rewrite(new AccountRecord(ASCII)))
                    .withMessageContaining("has been closed");
        }
    }

    // =============================================================================================
    // The sequential browse.
    // =============================================================================================

    /** The browse: every record, in ascending key order, then end of file. */
    @Nested
    @DisplayName("readNext - the sequential browse")
    class BrowseTests {

        @Test
        @DisplayName("returns all 50 fixture records byte-identically, then reports '10'")
        void returnsEveryRecordByteIdenticallyThenEndOfFile() {
            List<String> rows = fixtureRows();
            assertThat(rows).hasSize(FIXTURE_RECORDS).allSatisfy(row ->
                    assertThat(row).hasSize(THREE_HUNDRED));

            try (AccountFile file = openedInput(seeded(rows))) {
                List<String> read = drain(file, FIXTURE_RECORDS + 1);

                assertThat(read).hasSize(FIXTURE_RECORDS)
                        .containsExactlyElementsOf(rows.stream().sorted().toList());
                ReadResult atEnd = file.readNext();
                assertThat(atEnd.isEndOfFile()).isTrue();
                assertThat(atEnd.status()).isEqualTo(FileStatus.END_OF_FILE);
                assertThat(atEnd.account()).isEmpty();
                assertThat(atEnd.applResult()).isEqualTo(FileStatus.APPL_EOF);
                // Reading past end of file keeps reporting end of file rather than inventing a status.
                assertThat(file.readNext().isEndOfFile()).isTrue();
            }
        }

        @Test
        @DisplayName("delivers ascending ACCT-ID order even when the rows are stored in reverse")
        void deliversAscendingKeyOrderWhateverTheStoredOrder() {
            List<String> rows = new ArrayList<>(fixtureRows());
            rows.sort(Comparator.reverseOrder());

            List<Long> identifiers = new ArrayList<>();
            try (AccountFile file = openedInput(seeded(rows))) {
                for (ReadResult result = file.readNext(); result.isFound();
                        result = file.readNext()) {
                    identifiers.add(result.account().orElseThrow().getAcctId());
                }
            }

            assertThat(identifiers).hasSize(FIXTURE_RECORDS).isSorted();
        }

        @Test
        @DisplayName("preserves the 178 FILLER spaces and every field of a decoded record")
        void preservesFillerAndEveryField() {
            List<String> rows = fixtureRows();
            String expected = rows.stream().sorted().findFirst().orElseThrow();

            try (AccountFile file = openedInput(seeded(rows))) {
                AccountRecord account = file.readNext().account().orElseThrow();

                assertThat(account.recordLength()).isEqualTo(THREE_HUNDRED);
                assertThat(account.toByteArray()).hasSize(THREE_HUNDRED);
                assertThat(account.toFixedWidthString()).isEqualTo(expected);
                assertThat(account.getFiller()).isEqualTo(" ".repeat(FILLER_WIDTH));
                assertThat(account.keyImage()).isEqualTo(keyImageOf(expected));
            }
        }

        @Test
        @DisplayName("reports a short stored image rather than padding it into a different record")
        void reportsAShortStoredImage() {
            // The row is the first fixture record with its trailing FILLER X(178) removed, which is
            // exactly the shape a backend that trims a character column presents. Widening it back with
            // spaces would be right if the missing bytes were certainly the trailing ones - and nothing
            // about a short row says they are. A row that lost bytes anywhere else pads into a record
            // whose every field decodes from an offset that is not its own, and ACCT-CURR-BAL read from
            // the wrong span is still a number, so the caller could not tell.
            String full = fixtureRows().get(0);
            String trimmed = full.substring(0, THREE_HUNDRED - FILLER_WIDTH);

            try (AccountFile file = openedInput(seeded(List.of(trimmed)))) {
                ReadResult result = file.readNext();

                assertThat(result.isOther()).isTrue();
                assertThat(result.account()).isEmpty();
                assertThat(result.status()).isEqualTo(AccountRepository.PERMANENT_ERROR_STATUS);
                assertThat(result.applResult()).isEqualTo(AccountRepository.APPL_RESULT_FATAL);
                // LENGERR is what CICS reports when a record does not fit its receiver, and the actual
                // width is named in the log line rather than carried as a reason code.
                assertThat(result.cicsResp()).hasValue(FileStatus.LENGERR);
                assertThat(result.cicsResp2()).isEqualTo(FileStatus.NO_REASON_CODE);
            }
        }

        @Test
        @DisplayName("reports a stored image wider than the copybook declares")
        void reportsAnOverWideStoredImage() {
            String overWide = fixtureRows().get(0) + " ";

            try (AccountFile file = openedInput(seeded(List.of(overWide), THREE_HUNDRED + 1))) {
                ReadResult result = file.readNext();

                assertThat(result.isOther()).isTrue();
                assertThat(result.account()).isEmpty();
                assertThat(result.status()).isEqualTo(AccountRepository.PERMANENT_ERROR_STATUS);
                assertThat(result.cicsResp()).hasValue(FileStatus.LENGERR);
                assertThat(result.cicsResp2()).isEqualTo(FileStatus.NO_REASON_CODE);
            }
        }

        @Test
        @DisplayName("a row of exactly the declared width is decoded, so the check is a width check and "
                + "not a refusal to read")
        void aRowOfExactlyTheDeclaredWidthIsDecoded() {
            String full = fixtureRows().get(0);

            try (AccountFile file = openedInput(seeded(List.of(full)))) {
                ReadResult result = file.readNext();

                assertThat(result.isFound()).isTrue();
                assertThat(result.account().orElseThrow().toFixedWidthString()).isEqualTo(full);
                assertThat(result.account().orElseThrow().getFiller())
                        .isEqualTo(" ".repeat(FILLER_WIDTH));
            }
        }

        @Test
        @DisplayName("reports an empty dataset as end of file, not as a failure")
        void reportsAnEmptyDatasetAsEndOfFile() {
            try (AccountFile file = openedInput(seeded(List.of()))) {
                assertThat(file.readNext().isEndOfFile()).isTrue();
            }
        }

        @Test
        @DisplayName("reports a permanent error when the read itself is refused")
        void reportsAPermanentErrorWhenTheReadIsRefused() throws SQLException {
            try (AccountFile file = openedInput(describingThenRefusing())) {
                ReadResult result = file.readNext();

                assertThat(result.isOther()).isTrue();
                assertThat(result.status()).isEqualTo(AccountRepository.PERMANENT_ERROR_STATUS);
                assertThat(result.applResult()).isEqualTo(AccountRepository.APPL_RESULT_FATAL);
            }
        }

        @Test
        @DisplayName("a row with no record image is a failure, never an end of file")
        void aRowWithNoRecordImageIsAFailure() throws SQLException {
            try (AccountFile file = openedInput(describingThenReturningNoImage())) {
                ReadResult result = file.readNext();

                assertThat(result.isOther()).isTrue();
                assertThat(result.isEndOfFile()).isFalse();
                assertThat(result.status()).isEqualTo(AccountRepository.PERMANENT_ERROR_STATUS);
                // The permanent status is this module's own; the condition it stands for has a
                // determinate CICS counterpart, and carrying it is what lets an online caller render
                // ' Resp:' ERROR-RESP ' Reas:' ERROR-RESP2 for a row it could not read.
                assertThat(result.cicsResp()).hasValue(FileStatus.INVREQ);
                assertThat(result.cicsResp2()).isEqualTo(FileStatus.NO_REASON_CODE);
            }
        }
    }

    // =============================================================================================
    // The keyed reads.
    // =============================================================================================

    /** The random keyed read and the read for update. */
    @Nested
    @DisplayName("readByKey and readForUpdate - the keyed reads")
    class KeyedReadTests {

        @Test
        @DisplayName("finds the record for an existing identifier")
        void findsAnExistingRecord() {
            List<String> rows = fixtureRows();
            AccountRepository repository = repository(seeded(rows));
            String wanted = rows.get(rows.size() - 1);
            long acctId = Long.parseLong(keyImageOf(wanted));

            ReadResult result = repository.readByKey(acctId);

            assertThat(result.isFound()).isTrue();
            assertThat(result.status()).isEqualTo(FileStatus.OK);
            assertThat(result.applResult()).isEqualTo(FileStatus.APPL_AOK);
            assertThat(result.account().orElseThrow().toFixedWidthString()).isEqualTo(wanted);
        }

        @Test
        @DisplayName("reports '23' for an absent identifier instead of raising")
        void reportsInvalidKeyForAnAbsentIdentifier() {
            AccountRepository repository = repository(seeded(fixtureRows()));

            ReadResult result = repository.readByKey(ABSENT_ACCT_ID);

            assertThat(result.isNotFound()).isTrue();
            assertThat(result.status()).isEqualTo(FileStatus.NOT_FOUND);
            assertThat(result.account()).isEmpty();
            assertThat(result.applResult()).isEqualTo(AccountRepository.APPL_RESULT_FATAL);
            assertThat(result.cicsResp()).hasValue(FileStatus.NOTFND);
        }

        @Test
        @DisplayName("refuses a negative identifier, because PIC 9 has no sign position")
        void refusesANegativeIdentifier() {
            AccountRepository repository = repository(seeded(List.of()));

            assertThatIllegalArgumentException().isThrownBy(() -> repository.readByKey(-1L));
        }

        @Test
        @DisplayName("finds the record for the eleven-character RIDFLD image")
        void findsARecordForTheRidfldImage() {
            List<String> rows = fixtureRows();
            JdbcTemplate template = seeded(rows);
            AccountRepository repository = repository(template);
            String wanted = rows.get(0);

            ReadResult result = inUnitOfWork(() -> repository.readForUpdate(keyImageOf(wanted)));

            assertThat(result).isNotNull();
            assertThat(result.isFound()).isTrue();
            assertThat(result.cicsResp()).hasValue(FileStatus.NORMAL);
            assertThat(result.account().orElseThrow().toFixedWidthString()).isEqualTo(wanted);
        }

        @Test
        @DisplayName("a blank RIDFLD is a not-found record, not a rejection")
        void aBlankRidfldIsNotFound() {
            JdbcTemplate template = seeded(fixtureRows());
            AccountRepository repository = repository(template);

            assertThat(inUnitOfWork(() -> repository.readForUpdate(" ".repeat(ELEVEN))).isNotFound())
                    .isTrue();
        }

        @ParameterizedTest(name = "RIDFLD of eleven [{0}] characters")
        @ValueSource(strings = { "%", "_", "\\" })
        @DisplayName("a LIKE metacharacter in the RIDFLD matches nothing rather than another account")
        void aMetacharacterInTheRidfldMatchesNothing(String character) {
            JdbcTemplate template = seeded(fixtureRows());
            AccountRepository repository = repository(template);

            ReadResult result = inUnitOfWork(() -> repository.readForUpdate(character.repeat(ELEVEN)));

            assertThat(result).isNotNull();
            assertThat(result.isNotFound()).isTrue();
            assertThat(result.account()).isEmpty();
        }

        @Test
        @DisplayName("refuses a RIDFLD that is not exactly eleven characters, before anything else")
        void refusesARidfldOfTheWrongWidth() {
            AccountRepository repository = repository(seeded(List.of()));

            // The width check precedes the unit-of-work check, so a malformed key is reported as a
            // malformed key whether or not the caller happened to be inside a transaction.
            assertThatIllegalArgumentException().isThrownBy(() -> repository.readForUpdate("0000000001"))
                    .withMessageContaining("PIC X(11)");
            assertThatNullPointerException().isThrownBy(() -> repository.readForUpdate(null))
                    .withMessageContaining("key image is required");
        }

        @Test
        @DisplayName("a read-for-update with no unit of work open is refused, not issued anyway")
        void aReadForUpdateOutsideAUnitOfWorkIsRefused() {
            AccountRepository repository = repository(seeded(fixtureRows()));

            // COACTUPC's 9600-WRITE-PROCESSING locks the account, locks the customer, compares each
            // against the copy the screen was painted from, and only then rewrites - and that comparison
            // is only meaningful because nothing can change the records in between. A lock taken with no
            // transaction to hold it is released at once, so the comparison would still pass and protect
            // nothing. Refusing turns a silent correctness bug into a loud wiring bug.
            assertThatIllegalStateException()
                    .isThrownBy(() -> repository.readForUpdate("00000000001"))
                    .withMessageContaining("no transaction is open on this thread")
                    .as("a wiring diagnostic names the dataset and the operation; an account identifier "
                            + "adds nothing to it and would put a customer identifier in a stack trace")
                    .withMessageNotContaining("00000000001");
        }

        @Test
        @DisplayName("the locking read asks for the lock; the plain keyed read does not")
        void theLockingReadRequestsTheLock() {
            AccountRepository repository = repository(seeded(fixtureRows()));
            repository.readByKey(1L);
            Statements statements = repository.resolveStatements();

            assertThat(statements.selectByKey()).doesNotContain("FOR UPDATE");
            assertThat(statements.selectByKeyForUpdate())
                    .isEqualTo(statements.selectByKey() + " FOR UPDATE");
        }

        @Test
        @DisplayName("reports the width of a malformed RIDFLD but never the key itself")
        void reportsTheWidthOfAMalformedRidfldButNotTheKey() {
            AccountRepository repository = repository(seeded(List.of()));
            String malformedKey = "00000000012345";

            // The account identifier is the caller's data and this message is one an error boundary
            // could publish, so the guard names the declared width and the width it was given and
            // stops there.
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> repository.readForUpdate(malformedKey))
                    .withMessageContaining("declared PIC X(11)")
                    .withMessageContaining("given 14 character(s)")
                    .withMessageNotContaining(malformedKey);
        }

        @Test
        @DisplayName("reports a permanent error when the dataset cannot be reached or read")
        void reportsAPermanentErrorOnFailure() throws SQLException {
            assertThat(repository(unreachable()).readByKey(1L).status())
                    .isEqualTo(AccountRepository.PERMANENT_ERROR_STATUS);
            assertThat(repository(describingThenRefusing()).readByKey(1L).status())
                    .isEqualTo(AccountRepository.PERMANENT_ERROR_STATUS);
            ReadResult noImage = repository(describingThenReturningNoImage()).readByKey(1L);
            assertThat(noImage.status()).isEqualTo(AccountRepository.PERMANENT_ERROR_STATUS);
            assertThat(noImage.cicsResp()).hasValue(FileStatus.INVREQ);

            // A backend refusal has no determinate CICS counterpart, and inventing one would be exactly
            // the fabrication a carried pair exists to prevent, so the response is reported as absent and
            // the driver's own SQLSTATE travels in the diagnostic instead.
            ReadResult refused = repository(describingThenRefusing()).readByKey(1L);
            assertThat(refused.cicsResp()).isEmpty();
            assertThat(refused.cicsResp2()).isEqualTo(FileStatus.NO_REASON_CODE);
            assertThat(refused.response().describe()).isEqualTo("Resp:none Reas:0");
            assertThat(refused.diagnostic()).isPresent();
        }

        @Test
        @DisplayName("a read for update reports a permanent error when it cannot reach or lock the record")
        void aReadForUpdateReportsAPermanentErrorOnFailure() throws SQLException {
            AccountRepository unreachableRepository = repository(unreachable());
            AccountRepository refusingRepository = repository(describingThenRefusing());
            String key = "0".repeat(ELEVEN);

            // A conflict, a timeout or an outright refusal all arrive as a DataAccessException and all
            // become the same coarse status, which is what puts COACTUPC on its
            // COULD-NOT-LOCK-ACCT-FOR-UPDATE arm at L3910-L3913.
            assertThat(withUnitOfWork(() -> unreachableRepository.readForUpdate(key)).status())
                    .isEqualTo(AccountRepository.PERMANENT_ERROR_STATUS);
            assertThat(withUnitOfWork(() -> refusingRepository.readForUpdate(key)).status())
                    .isEqualTo(AccountRepository.PERMANENT_ERROR_STATUS);
            assertThat(withUnitOfWork(() -> refusingRepository.readForUpdate(key)).isFound())
                    .as("not found, so the caller takes the could-not-lock arm")
                    .isFalse();
        }
    }

    /**
     * Runs a body with a transaction marked active on this thread, which a locking read requires.
     *
     * <p>Marking the flag rather than starting a real transaction is the honest test of the precondition:
     * what {@code readForUpdate} demands is that a unit of work be open, and this asserts exactly that
     * without dragging a transaction manager and a live {@code DataSource} into a test whose subject is
     * the read.
     *
     * @param work the body
     * @param <T>  its result type
     * @return the body's result
     */
    private static <T> T inUnitOfWork(java.util.function.Supplier<T> work) {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            return work.get();
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    // =============================================================================================
    // The rewrite.
    // =============================================================================================

    /** The rewrite: keyed by the record's own key, and never silently successful. */
    @Nested
    @DisplayName("rewrite - the REWRITE of one record")
    class RewriteTests {

        @Test
        @DisplayName("rewrites the account-break mutations and leaves the record 300 bytes wide")
        void rewritesTheAccountBreakMutations() {
            List<String> rows = fixtureRows();
            AccountRepository repository = repository(seeded(rows));
            long acctId = Long.parseLong(keyImageOf(rows.get(0)));
            AccountRecord account = repository.readByKey(acctId).account().orElseThrow();

            // The 1050-UPDATE-ACCOUNT sequence, performed by the caller exactly as CBACT04C does.
            account.setAcctCurrBal(account.getAcctCurrBal().add(new BigDecimal("12.34")));
            account.zeroAcctCurrCycCredit();
            account.zeroAcctCurrCycDebit();
            WriteResult written = repository.rewrite(account);

            assertThat(written.isWritten()).isTrue();
            assertThat(written.status()).isEqualTo(FileStatus.OK);
            assertThat(written.applResult()).isEqualTo(FileStatus.APPL_AOK);
            assertThat(written.cicsResp()).hasValue(FileStatus.NORMAL);

            AccountRecord reread = repository.readByKey(acctId).account().orElseThrow();
            assertThat(reread.toFixedWidthString())
                    .isEqualTo(account.toFixedWidthString())
                    .hasSize(THREE_HUNDRED);
            assertThat(reread.getAcctCurrCycCredit()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(reread.getAcctCurrCycDebit()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(reread.getFiller()).isEqualTo(" ".repeat(FILLER_WIDTH));
        }

        @Test
        @DisplayName("a rewrite issued through an open handle writes through that handle's statements")
        void aRewriteThroughAnOpenHandleWrites() {
            // Every other rewrite test here goes straight to the repository. A COBOL program does not:
            // it issues REWRITE against a file it has OPEN I-O, and the handle is what carries the
            // prepared statements. Exercising that route proves the handle delegates rather than
            // holding a second, divergent path to the same dataset.
            List<String> rows = fixtureRows();
            AccountRepository repository = repository(seeded(rows));
            long acctId = Long.parseLong(keyImageOf(rows.get(0)));

            try (AccountFile file = repository.open(OpenMode.I_O)) {
                assertThat(file.openStatus()).isEqualTo(FileStatus.OK);
                AccountRecord account = file.readByKey(acctId).account().orElseThrow();
                account.setAcctGroupId("VIAHANDLE");

                WriteResult written = file.rewrite(account);

                assertThat(written.isWritten()).isTrue();
                assertThat(written.status()).isEqualTo(FileStatus.OK);
                assertThat(file.readByKey(acctId).account().orElseThrow().toFixedWidthString())
                        .isEqualTo(account.toFixedWidthString())
                        .hasSize(THREE_HUNDRED);
            }
        }

        @Test
        @DisplayName("rewrites only the record whose key it carries")
        void rewritesOnlyItsOwnRecord() {
            List<String> rows = fixtureRows();
            AccountRepository repository = repository(seeded(rows));
            long firstId = Long.parseLong(keyImageOf(rows.get(0)));
            String secondImage = rows.get(1);
            AccountRecord account = repository.readByKey(firstId).account().orElseThrow();
            account.setAcctGroupId("REWRITTEN");

            assertThat(repository.rewrite(account).isWritten()).isTrue();

            long secondId = Long.parseLong(keyImageOf(secondImage));
            assertThat(repository.readByKey(secondId).account().orElseThrow().toFixedWidthString())
                    .isEqualTo(secondImage);
        }

        @Test
        @DisplayName("reports '23' when no record carries that key")
        void reportsInvalidKeyWhenNothingMatches() {
            AccountRepository repository = repository(seeded(fixtureRows()));
            AccountRecord absent = new AccountRecord(ASCII);
            absent.setAcctId(ABSENT_ACCT_ID);

            WriteResult result = repository.rewrite(absent);

            assertThat(result.isNotFound()).isTrue();
            assertThat(result.status()).isEqualTo(FileStatus.NOT_FOUND);
            assertThat(result.applResult()).isEqualTo(AccountRepository.APPL_RESULT_FATAL);
        }

        @Test
        @DisplayName("refuses before writing when more than one row carries the key, and changes neither")
        void refusesBeforeWritingWhenSeveralRowsCarryTheKey() {
            // The seeded relation deliberately has no key constraint, which is the shape of a deployment
            // whose primary key is absent or declared over the wrong span. A KSDS key is unique, so the
            // rewrite's LIKE predicate names one row; here it names two, and the same UPDATE would
            // replace BOTH with this one record. Learning that from the affected-row count is learning it
            // after the fact, so the count is established first and nothing is written at all.
            String duplicated = fixtureRows().get(0);
            JdbcTemplate template = seeded(List.of(duplicated, duplicated));
            AccountRepository repository = repository(template);
            AccountRecord account = AccountRecord.decode(duplicated, ASCII);
            account.setAcctGroupId("OVERWRITE");

            WriteResult result = repository.rewrite(account);

            assertThat(result.isOther()).isTrue();
            assertThat(result.status()).isEqualTo(AccountRepository.PERMANENT_ERROR_STATUS);
            assertThat(result.applResult()).isEqualTo(AccountRepository.APPL_RESULT_FATAL);
            // INVREQ is what CICS reports for a request the file cannot satisfy; the row count is named
            // in the log line rather than carried as a reason code, because a count is not a reason code.
            assertThat(result.cicsResp()).hasValue(FileStatus.INVREQ);
            assertThat(result.cicsResp2()).isEqualTo(FileStatus.NO_REASON_CODE);

            // The whole point: both rows are still exactly as they were seeded.
            assertThat(template.queryForList("SELECT " + RECORD_IMAGE_COLUMN + " FROM \""
                    + TEST_DSNAME + "\"", String.class))
                    .as("a refused rewrite must not have changed a single row")
                    .containsExactly(duplicated, duplicated);
        }

        @Test
        @DisplayName("refuses the whole unit of work when the update fans out after the count was one")
        void refusesTheUnitOfWorkWhenTheUpdateFansOutAfterTheProbe() throws SQLException {
            // The one case a status cannot carry. The probe saw exactly one row, so the rewrite was
            // issued - and the driver then reports two rows replaced, meaning the relation changed
            // underneath it. The damage is already done, and a status returned from here would let the
            // enclosing unit of work commit it, so the commit is refused instead.
            AccountRepository repository = repository(describingThenFanningOutOnUpdate());
            AccountRecord account = AccountRecord.decode(fixtureRows().get(0), ASCII);

            assertThatExceptionOfType(DatasetIntegrityException.class)
                    .isThrownBy(() -> repository.rewrite(account))
                    .withMessageContaining("must not be allowed to stand")
                    .withMessageContaining("2 rows were replaced")
                    .withMessageContaining("no row lock, because no unit of work was open");
        }

        @Test
        @DisplayName("inside a unit of work the refusal says the count was taken under a row lock")
        void insideAUnitOfWorkTheRefusalNamesTheRowLock() throws SQLException {
            // The same race, with a unit of work open. The refusal has to say which it was, because the
            // remedy differs: under a lock the relation itself changed, and without one there was never
            // anything holding it still.
            JdbcTemplate template = describingThenFanningOutOnUpdate();
            AccountRepository repository = repository(template);
            AccountRecord account = AccountRecord.decode(fixtureRows().get(0), ASCII);

            assertThatExceptionOfType(DatasetIntegrityException.class)
                    .isThrownBy(() -> transactionOver(template)
                            .execute(status -> repository.rewrite(account)))
                    .withMessageContaining("2 rows were replaced")
                    .withMessageContaining("under a row lock")
                    .withMessageContaining("rolled back rather than reported as a file status");
        }

        @Test
        @DisplayName("a row that disappears between the count and the write is '23', not a refusal")
        void aRowThatDisappearsBetweenTheCountAndTheWriteIsInvalidKey() throws SQLException {
            // Nothing was written, so nothing has to be undone: this is the invalid-key condition, which
            // is exactly what it would have been had the row never existed. Only a write that changed too
            // much refuses the commit.
            AccountRepository repository = repository(describingThenLosingTheRowBeforeUpdate());
            AccountRecord account = AccountRecord.decode(fixtureRows().get(0), ASCII);

            WriteResult result = repository.rewrite(account);

            assertThat(result.isNotFound()).isTrue();
            assertThat(result.status()).isEqualTo(FileStatus.NOT_FOUND);
            assertThat(result.applResult()).isEqualTo(AccountRepository.APPL_RESULT_FATAL);
        }

        @Test
        @DisplayName("the pre-write count is taken under the row lock when a unit of work is open")
        void thePreWriteCountIsTakenUnderTheRowLock() throws SQLException {
            List<String> prepared = new ArrayList<>();
            JdbcTemplate template = recordingPreparedStatements(prepared);
            AccountRepository repository = repository(template);
            AccountRecord account = AccountRecord.decode(fixtureRows().get(0), ASCII);

            // Outside a unit of work there is no lock to share, and requiring one would refuse a rewrite
            // the COBOL performs, so the count is taken with a plain keyed read.
            repository.rewrite(account);
            assertThat(prepared).hasSize(1);
            assertThat(prepared.get(0)).doesNotContain("FOR UPDATE");

            // Inside one, the count and the UPDATE must see the same rows, so the count takes the same
            // lock the UPDATE will use.
            prepared.clear();
            transactionOver(template).execute(status -> repository.rewrite(account));
            assertThat(prepared).isNotEmpty();
            assertThat(prepared.get(0)).endsWith("FOR UPDATE");
        }

        @Test
        @DisplayName("a rewrite whose key selects nothing issues no UPDATE at all")
        void aRewriteWhoseKeySelectsNothingIssuesNoUpdate() throws SQLException {
            // Reporting '23' without issuing the UPDATE is the same outcome the UPDATE would have
            // reported, and one statement fewer against a dataset that has no such record.
            List<String> prepared = new ArrayList<>();
            AccountRepository repository = repository(recordingPreparedStatements(prepared));
            AccountRecord absent = new AccountRecord(ASCII);
            absent.setAcctId(ABSENT_ACCT_ID);

            assertThat(repository.rewrite(absent).isNotFound()).isTrue();
            assertThat(prepared)
                    .as("the count found no row, so no UPDATE should have been prepared")
                    .noneMatch(sql -> sql.startsWith("UPDATE"));
        }

        @Test
        @DisplayName("a refused count is reported as a permanent error, not as a missing record")
        void aRefusedCountIsAPermanentError() throws SQLException {
            // The count is a statement like any other and the backend can refuse it. That must not be
            // mistaken for "no such record", which is what a count of zero means.
            AccountRepository repository = repository(describingThenRefusing());
            AccountRecord account = AccountRecord.decode(fixtureRows().get(0), ASCII);

            WriteResult result = repository.rewrite(account);

            assertThat(result.isOther()).isTrue();
            assertThat(result.isNotFound()).isFalse();
            assertThat(result.status()).isEqualTo(AccountRepository.PERMANENT_ERROR_STATUS);
            assertThat(result.diagnostic()).isPresent();
        }

        @Test
        @DisplayName("reports a permanent error when the dataset cannot be reached or written")
        void reportsAPermanentErrorOnFailure() throws SQLException {
            AccountRecord account = new AccountRecord(ASCII);
            account.setAcctId(1L);

            assertThat(repository(unreachable()).rewrite(account).status())
                    .isEqualTo(AccountRepository.PERMANENT_ERROR_STATUS);
            assertThat(repository(describingThenRefusing()).rewrite(account).status())
                    .isEqualTo(AccountRepository.PERMANENT_ERROR_STATUS);
        }

        @Test
        @DisplayName("requires a record rather than rewriting nothing")
        void requiresARecord() {
            AccountRepository repository = repository(new JdbcTemplate());

            assertThatNullPointerException().isThrownBy(() -> repository.rewrite(null))
                    .withMessageContaining("record is required");
        }
    }

    // =============================================================================================
    // The shape of the dataset itself.
    // =============================================================================================

    /** A relation that is not a record-image relation is a contract violation, not a file status. */
    @Nested
    @DisplayName("A dataset that is not a record-image relation")
    class DatasetShapeTests {

        @Test
        @DisplayName("no metadata at all is refused, naming the column position it needed")
        void noMetadataIsRefused() throws SQLException {
            AccountRepository repository = repository(describingNothing());

            assertThatIllegalStateException().isThrownBy(() -> repository.open(OpenMode.INPUT))
                    .withMessageContaining("describes no column at position 1");
        }

        @Test
        @DisplayName("a relation with no columns is refused")
        void noColumnsIsRefused() throws SQLException {
            AccountRepository repository = repository(describing(0, RECORD_IMAGE_COLUMN));

            assertThatIllegalStateException().isThrownBy(repository::resolveStatements)
                    .withMessageContaining("describes no column");
        }

        @ParameterizedTest(name = "column name [{0}]")
        @ValueSource(strings = { "", "   " })
        @DisplayName("a blank column name is refused")
        void aBlankColumnNameIsRefused(String blank) throws SQLException {
            AccountRepository repository = repository(describing(1, blank));

            assertThatIllegalStateException().isThrownBy(() -> repository.readByKey(1L))
                    .withMessageContaining("describes no column");
        }

        @Test
        @DisplayName("a column name carrying a control character is refused")
        void aControlCharacterInTheColumnNameIsRefused() throws SQLException {
            AccountRepository repository = repository(describing(1, "RE\u0001C"));

            assertThatIllegalStateException().isThrownBy(() -> repository.open(OpenMode.I_O))
                    .withMessageContaining("control character");
        }
    }

    // =============================================================================================
    // The locking read. F01: readForUpdate used to issue the same plain SELECT as readByKey, so nothing
    // held the record between COACTUPC's comparison at L3947-L3948 and its REWRITE at L4065.
    // =============================================================================================

    /** {@code EXEC CICS READ ... UPDATE}: a lock that is really taken, and really held. */
    @Nested
    @DisplayName("readForUpdate - the locking read")
    class LockingReadTests {

        /**
         * The statement text a keyed read against the seeded relation must produce.
         *
         * <p>The ordering clause is part of it: every read this module composes states its order
         * explicitly rather than relying on whatever a backend happens to return, so a keyed read is
         * ordered by the record image exactly as a browse of the same relation is.
         */
        private String keyedSelect() {
            return "SELECT * FROM \"" + TEST_DSNAME + "\" WHERE \"" + RECORD_IMAGE_COLUMN
                    + "\" LIKE ? ESCAPE '\\' ORDER BY \"" + RECORD_IMAGE_COLUMN + "\" ASC";
        }

        @Test
        @DisplayName("asks the driver for a locking read, where the plain keyed read does not")
        void asksTheDriverForALockingRead() throws SQLException {
            List<String> prepared = new ArrayList<>();
            AccountRepository repository = repository(recordingPreparedStatements(prepared));

            repository.readByKey(1L);
            assertThat(prepared)
                    .as("the plain keyed read takes no lock, and must not start taking one")
                    .containsExactly(keyedSelect());

            prepared.clear();
            withUnitOfWork(() -> repository.readForUpdate("0".repeat(ELEVEN)));

            // What the repository actually handed the driver, captured verbatim. This is the whole of
            // F01: the two reads must not be the same statement.
            assertThat(prepared).containsExactly(keyedSelect() + " FOR UPDATE");
        }

        @Test
        @DisplayName("the handle's read for update asks for the same locking statement")
        void theHandlesReadForUpdateLocksToo() throws SQLException {
            List<String> prepared = new ArrayList<>();
            try (AccountFile file = repository(recordingPreparedStatements(prepared))
                    .open(OpenMode.I_O)) {
                prepared.clear();

                withUnitOfWork(() -> file.readForUpdate("0".repeat(ELEVEN)));

                assertThat(prepared).containsExactly(keyedSelect() + " FOR UPDATE");
            }
        }

        @Test
        @DisplayName("refuses to run outside a unit of work, where the lock would not survive the call")
        void refusesOutsideAUnitOfWork() {
            AccountRepository repository = repository(seeded(fixtureRows()));
            String key = keyImageOf(fixtureRows().get(0));

            assertThatIllegalStateException().isThrownBy(() -> repository.readForUpdate(key))
                    .withMessageContaining("no transaction is open on this thread")
                    .withMessageContaining("9700-CHECK-CHANGE-IN-REC");

            // The plain keyed read has no such requirement: it takes no lock, so there is nothing for a
            // transaction to hold, and CBACT04C reads by key with no unit of work of its own.
            assertThat(repository.readByKey(Long.parseLong(key)).isFound()).isTrue();
        }

        @Test
        @DisplayName("the handle refuses outside a unit of work for the same reason")
        void theHandleRefusesOutsideAUnitOfWork() {
            try (AccountFile file = repository(seeded(fixtureRows())).open(OpenMode.I_O)) {
                String key = keyImageOf(fixtureRows().get(0));

                assertThatIllegalStateException().isThrownBy(() -> file.readForUpdate(key))
                        .withMessageContaining("no transaction is open on this thread");
                assertThat(file.readByKey(Long.parseLong(key)).isFound()).isTrue();
            }
        }

        @Test
        @DisplayName("the record is genuinely held: a second connection cannot lock it concurrently")
        void theRecordIsGenuinelyHeldAgainstAnotherConnection() {
            List<String> rows = fixtureRows();
            JdbcTemplate template = seeded(rows);
            AccountRepository repository = repository(template);
            DataSource dataSource = template.getDataSource();
            assertThat(dataSource).isNotNull();
            String key = keyImageOf(rows.get(0));

            Boolean secondReaderWasBlocked = transactionOver(template).execute(status -> {
                assertThat(repository.readForUpdate(key).isFound()).isTrue();
                return !anotherConnectionCanLock(dataSource, key);
            });

            assertThat(secondReaderWasBlocked)
                    .as("the row must stay locked for the whole unit of work, or nothing protects the "
                            + "comparison and the rewrite that follow it")
                    .isTrue();
        }

        @Test
        @DisplayName("without the lock the same row is free, which is what made the old read unsafe")
        void withoutTheLockTheRowIsFree() {
            List<String> rows = fixtureRows();
            JdbcTemplate template = seeded(rows);
            AccountRepository repository = repository(template);
            DataSource dataSource = template.getDataSource();
            assertThat(dataSource).isNotNull();
            String key = keyImageOf(rows.get(0));

            // The control for the test above: the plain keyed read leaves the row unlocked, so the
            // second connection takes it immediately. That is exactly the state readForUpdate used to
            // leave COACTUPC in.
            Boolean free = transactionOver(template).execute(status -> {
                assertThat(repository.readByKey(Long.parseLong(key)).isFound()).isTrue();
                return anotherConnectionCanLock(dataSource, key);
            });

            assertThat(free).isTrue();
        }

        @Test
        @DisplayName("read, compare and rewrite run inside one unit of work, as 9600 does")
        void readCompareAndRewriteRunInOneUnitOfWork() {
            List<String> rows = fixtureRows();
            JdbcTemplate template = seeded(rows);
            AccountRepository repository = repository(template);
            String key = keyImageOf(rows.get(0));
            long acctId = Long.parseLong(key);

            WriteResult written = transactionOver(template).execute(status -> {
                // 9600-WRITE-PROCESSING: read for update ...
                AccountRecord locked = repository.readForUpdate(key).account().orElseThrow();
                // ... 9700-CHECK-CHANGE-IN-REC: the caller's field-by-field comparison, which is the
                // service's job and is represented here by the image it compares ...
                assertThat(locked.toFixedWidthString()).isEqualTo(rows.get(0));
                locked.setAcctCurrBal(locked.getAcctCurrBal().add(new BigDecimal("1.00")));
                // ... then the REWRITE at L4065, still holding the record.
                return repository.rewrite(locked);
            });

            assertThat(written).isNotNull();
            assertThat(written.isWritten()).isTrue();
            assertThat(repository.readByKey(acctId).account().orElseThrow().toFixedWidthString())
                    .hasSize(THREE_HUNDRED)
                    .isNotEqualTo(rows.get(0));
        }
    }

    // =============================================================================================
    // Execution-state isolation. F02: the singleton used to hold the resolved shape, the open mode and
    // the browse position, so two concurrent executions shared one file position.
    // =============================================================================================

    /** One repository, many independent files: the state a COBOL program owns is not shared. */
    @Nested
    @DisplayName("Per-execution state isolation")
    class StateIsolationTests {

        @Test
        @DisplayName("the repository declares no non-final instance field, so it holds nothing mutable")
        void theRepositoryHoldsNothingMutable() {
            for (Field field : AccountRepository.class.getDeclaredFields()) {
                if (field.isSynthetic() || Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                assertThat(Modifier.isFinal(field.getModifiers()))
                        .describedAs("AccountRepository.%s is an instance field of a Spring singleton "
                                + "and must be final: a browse position or an open mode held here "
                                + "would be shared by every concurrent execution", field.getName())
                        .isTrue();
            }
        }

        @Test
        @DisplayName("the per-execution state lives on the handle, which is where COBOL keeps it")
        void thePerExecutionStateLivesOnTheHandle() {
            List<String> mutable = new ArrayList<>();
            for (Field field : AccountFile.class.getDeclaredFields()) {
                if (!field.isSynthetic() && !Modifier.isStatic(field.getModifiers())
                        && !Modifier.isFinal(field.getModifiers())) {
                    mutable.add(field.getName());
                }
            }

            assertThat(mutable)
                    .as("exactly the file position and the closed flag, and nothing else")
                    .containsExactlyInAnyOrder("browsePosition", "closed");
            assertThat(AutoCloseable.class).isAssignableFrom(AccountFile.class);
            assertThat(Modifier.isFinal(AccountFile.class.getModifiers())).isTrue();
            assertThat(AccountFile.class.getDeclaredConstructors())
                    .as("constructed only by open(OpenMode), so a handle always matches a real open")
                    .allSatisfy(constructor ->
                            assertThat(Modifier.isPrivate(constructor.getModifiers())).isTrue());
        }

        @Test
        @DisplayName("two interleaved browses over one repository each see every record in order")
        void twoInterleavedBrowsesDoNotInterfere() {
            List<String> rows = fixtureRows();
            List<String> expected = rows.stream().sorted().toList();
            AccountRepository repository = repository(seeded(rows));

            List<String> left = new ArrayList<>();
            List<String> right = new ArrayList<>();
            try (AccountFile first = repository.open(OpenMode.INPUT);
                    AccountFile second = repository.open(OpenMode.INPUT)) {
                for (int read = 0; read < FIXTURE_RECORDS; read++) {
                    left.add(first.readNext().account().orElseThrow().toFixedWidthString());
                    right.add(second.readNext().account().orElseThrow().toFixedWidthString());
                }
                assertThat(first.readNext().isEndOfFile()).isTrue();
                assertThat(second.readNext().isEndOfFile()).isTrue();
            }

            // Before the fix the two browses shared one position on the singleton: the second open would
            // have rewound the first, and the reads would have consumed alternate records.
            assertThat(left).containsExactlyElementsOf(expected);
            assertThat(right).containsExactlyElementsOf(expected);
        }

        @Test
        @DisplayName("opening a second file does not rewind or close the first")
        void openingASecondFileDoesNotDisturbTheFirst() {
            List<String> rows = fixtureRows();
            List<String> expected = rows.stream().sorted().toList();
            AccountRepository repository = repository(seeded(rows));

            try (AccountFile first = repository.open(OpenMode.INPUT)) {
                assertThat(first.readNext().account().orElseThrow().toFixedWidthString())
                        .isEqualTo(expected.get(0));

                try (AccountFile second = repository.open(OpenMode.I_O)) {
                    assertThat(second.mode()).isSameAs(OpenMode.I_O);
                    assertThat(second.readNext().account().orElseThrow().toFixedWidthString())
                            .isEqualTo(expected.get(0));
                }

                // The inner file's close ended the inner file only.
                assertThat(first.isClosed()).isFalse();
                assertThat(first.mode()).isSameAs(OpenMode.INPUT);
                assertThat(first.readNext().account().orElseThrow().toFixedWidthString())
                        .isEqualTo(expected.get(1));
            }
        }

        @Test
        @DisplayName("two threads browsing their own handles over one repository each read all 50")
        void twoThreadsEachReadEveryRecord() throws InterruptedException {
            List<String> rows = fixtureRows();
            List<String> expected = rows.stream().sorted().toList();
            AccountRepository repository = repository(seeded(rows));
            List<List<String>> results = List.of(new ArrayList<>(), new ArrayList<>());
            List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());
            CyclicBarrier startTogether = new CyclicBarrier(2);

            List<Thread> workers = new ArrayList<>();
            for (List<String> sink : results) {
                Thread worker = new Thread(() -> {
                    try {
                        startTogether.await();
                        try (AccountFile file = repository.open(OpenMode.INPUT)) {
                            sink.addAll(drain(file, FIXTURE_RECORDS + 1));
                        }
                    } catch (RuntimeException | InterruptedException | BrokenBarrierException problem) {
                        failures.add(problem);
                    }
                });
                workers.add(worker);
                worker.start();
            }
            for (Thread worker : workers) {
                worker.join();
            }

            assertThat(failures).isEmpty();
            assertThat(results).allSatisfy(read -> assertThat(read)
                    .as("a shared position would have split the 50 records between the two threads")
                    .containsExactlyElementsOf(expected));
        }

        @Test
        @DisplayName("the keyed operations hold nothing between calls either")
        void theKeyedOperationsHoldNothingBetweenCalls() {
            List<String> rows = fixtureRows();
            AccountRepository repository = repository(seeded(rows));
            long first = Long.parseLong(keyImageOf(rows.get(0)));
            long last = Long.parseLong(keyImageOf(rows.get(rows.size() - 1)));

            // Repeated in a different order, from one instance, with no open in sight: each call depends
            // only on its own argument.
            assertThat(repository.readByKey(last).account().orElseThrow().getAcctId()).isEqualTo(last);
            assertThat(repository.readByKey(first).account().orElseThrow().getAcctId()).isEqualTo(first);
            assertThat(repository.readByKey(ABSENT_ACCT_ID).isNotFound()).isTrue();
            assertThat(repository.readByKey(last).account().orElseThrow().getAcctId()).isEqualTo(last);
        }
    }

    // =============================================================================================
    // The discriminated outcomes.
    // =============================================================================================

    /** {@link ReadResult} and {@link WriteResult}: their invariants, predicates and mappings. */
    @Nested
    @DisplayName("ReadResult and WriteResult")
    class OutcomeTests {

        /** A freshly allocated record, for the arms that need one. */
        private AccountRecord blank() {
            return new AccountRecord(ASCII);
        }

        @Test
        @DisplayName("a found read carries the record, status '00' and the NORMAL response")
        void aFoundReadCarriesTheRecord() {
            AccountRecord account = blank();
            ReadResult result = ReadResult.found(account);

            assertThat(result.status()).isEqualTo(FileStatus.OK);
            assertThat(result.outcome()).isEqualTo(Outcome.OK);
            assertThat(result.account()).containsSame(account);
            assertThat(result.isFound()).isTrue();
            assertThat(result.isEndOfFile()).isFalse();
            assertThat(result.isNotFound()).isFalse();
            assertThat(result.isOther()).isFalse();
            assertThat(result.applResult()).isEqualTo(FileStatus.APPL_AOK);
            assertThat(result.cicsResp()).hasValue(FileStatus.NORMAL);
        }

        @Test
        @DisplayName("an end-of-file read carries status '10', no record, and APPL-RESULT 16")
        void anEndOfFileReadCarriesNoRecord() {
            ReadResult result = ReadResult.endOfFile();

            assertThat(result.status()).isEqualTo(FileStatus.END_OF_FILE);
            assertThat(result.outcome()).isEqualTo(Outcome.END_OF_FILE);
            assertThat(result.account()).isEmpty();
            assertThat(result.isEndOfFile()).isTrue();
            assertThat(result.isFound()).isFalse();
            assertThat(result.applResult()).isEqualTo(FileStatus.APPL_EOF);
            assertThat(result.cicsResp()).hasValue(FileStatus.ENDFILE);
        }

        @Test
        @DisplayName("a not-found read carries status '23' and the fatal APPL-RESULT")
        void aNotFoundReadIsFatalForItsCaller() {
            ReadResult result = ReadResult.notFound();

            assertThat(result.status()).isEqualTo(FileStatus.NOT_FOUND);
            assertThat(result.isNotFound()).isTrue();
            assertThat(result.isOther()).isFalse();
            assertThat(result.applResult()).isEqualTo(AccountRepository.APPL_RESULT_FATAL);
        }

        @Test
        @DisplayName("a duplicate status is carried verbatim rather than collapsed into the catch-all")
        void aDuplicateStatusIsCarriedVerbatim() {
            ReadResult result = ReadResult.of(FileStatus.DUPLICATE);

            assertThat(result.status()).isEqualTo(FileStatus.DUPLICATE);
            assertThat(result.outcome()).isEqualTo(Outcome.DUPLICATE);
            assertThat(result.isOther()).isFalse();
            assertThat(result.isNotFound()).isFalse();
            assertThat(result.applResult()).isEqualTo(AccountRepository.APPL_RESULT_FATAL);
            assertThat(result.cicsResp()).isEmpty();
        }

        @Test
        @DisplayName("a successful read cannot be built without its record")
        void aSuccessfulReadCannotBeBuiltWithoutItsRecord() {
            assertThatIllegalArgumentException().isThrownBy(() -> ReadResult.of(FileStatus.OK))
                    .withMessageContaining("carries the decoded record");
            assertThatNullPointerException().isThrownBy(() -> ReadResult.found(null))
                    .withMessageContaining("decoded account record");
        }

        @Test
        @DisplayName("a read that did not succeed cannot carry a record")
        void aFailedReadCannotCarryARecord() {
            Optional<AccountRecord> account = Optional.of(blank());

            assertThatIllegalArgumentException().isThrownBy(
                    () -> new ReadResult(FileStatus.END_OF_FILE, Outcome.END_OF_FILE, account,
                            Optional.empty(), CicsResponse.ofBatchStatus(FileStatus.END_OF_FILE)))
                    .withMessageContaining("carries no record");
        }

        @Test
        @DisplayName("a status and its classification must agree")
        void aStatusAndItsClassificationMustAgree() {
            assertThatIllegalArgumentException().isThrownBy(
                    () -> new ReadResult(FileStatus.END_OF_FILE, Outcome.NOT_FOUND, Optional.empty(),
                            Optional.empty(), CicsResponse.ofBatchStatus(FileStatus.END_OF_FILE)))
                    .withMessageContaining("must agree");
        }

        @Test
        @DisplayName("a status is exactly two characters and is never absent")
        void aStatusIsAlwaysTwoCharacters() {
            assertThatIllegalArgumentException().isThrownBy(() -> ReadResult.of("0"))
                    .withMessageContaining("exactly 2 characters");
            assertThatNullPointerException().isThrownBy(() -> ReadResult.of(null))
                    .withMessageContaining("two-character file status");
            assertThatNullPointerException().isThrownBy(
                    () -> new ReadResult(FileStatus.OK, null, Optional.empty(), Optional.empty(),
                            CicsResponse.ofBatchStatus(FileStatus.OK)))
                    .withMessageContaining("classification");
            assertThatNullPointerException().isThrownBy(
                    () -> new ReadResult(FileStatus.END_OF_FILE, Outcome.END_OF_FILE, null,
                            Optional.empty(), CicsResponse.ofBatchStatus(FileStatus.END_OF_FILE)))
                    .withMessageContaining("empty record");
            assertThatNullPointerException().isThrownBy(
                    () -> new ReadResult(FileStatus.END_OF_FILE, Outcome.END_OF_FILE, Optional.empty(),
                            null, CicsResponse.ofBatchStatus(FileStatus.END_OF_FILE)))
                    .withMessageContaining("empty diagnostic");
            assertThatNullPointerException().isThrownBy(
                    () -> new ReadResult(FileStatus.END_OF_FILE, Outcome.END_OF_FILE, Optional.empty(),
                            Optional.empty(), null))
                    .withMessageContaining("CICS response pair");
        }

        @Test
        @DisplayName("a written rewrite carries status '00' and APPL-RESULT 0")
        void aWrittenRewriteCarriesOk() {
            WriteResult result = WriteResult.written();

            assertThat(result.status()).isEqualTo(FileStatus.OK);
            assertThat(result.outcome()).isEqualTo(Outcome.OK);
            assertThat(result.isWritten()).isTrue();
            assertThat(result.isNotFound()).isFalse();
            assertThat(result.isOther()).isFalse();
            assertThat(result.applResult()).isEqualTo(FileStatus.APPL_AOK);
            assertThat(result.cicsResp()).hasValue(FileStatus.NORMAL);
        }

        @Test
        @DisplayName("a not-found rewrite carries status '23' and the fatal APPL-RESULT")
        void aNotFoundRewriteIsFatal() {
            WriteResult result = WriteResult.notFound();

            assertThat(result.isNotFound()).isTrue();
            assertThat(result.isWritten()).isFalse();
            assertThat(result.applResult()).isEqualTo(AccountRepository.APPL_RESULT_FATAL);
        }

        @Test
        @DisplayName("a failed rewrite classifies as the catch-all arm")
        void aFailedRewriteIsTheCatchAll() {
            WriteResult result = WriteResult.of(AccountRepository.PERMANENT_ERROR_STATUS);

            assertThat(result.isOther()).isTrue();
            assertThat(result.isWritten()).isFalse();
            assertThat(result.isNotFound()).isFalse();
            assertThat(result.cicsResp()).isEmpty();
            assertThat(result.applResult()).isEqualTo(AccountRepository.APPL_RESULT_FATAL);
        }

        @Test
        @DisplayName("a rewrite cannot reach the end of a dataset")
        void aRewriteCannotReachEndOfFile() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> WriteResult.of(FileStatus.END_OF_FILE))
                    .withMessageContaining("cannot reach the end of a dataset");
        }
    }

    // =============================================================================================
    // Structure.
    // =============================================================================================

    /** The structural guarantees the migration's practices require of this class. */
    @Nested
    @DisplayName("Structure")
    class StructuralTests {

        @Test
        @DisplayName("holds no mutable static state, in the class or in any nested type")
        void holdsNoMutableStaticState() {
            List<Class<?>> types = new ArrayList<>();
            types.add(AccountRepository.class);
            types.addAll(List.of(AccountRepository.class.getDeclaredClasses()));

            for (Class<?> type : types) {
                for (Field field : type.getDeclaredFields()) {
                    if (Modifier.isStatic(field.getModifiers()) && !field.isSynthetic()) {
                        assertThat(Modifier.isFinal(field.getModifiers()))
                                .describedAs("%s.%s is static and must be final",
                                        type.getSimpleName(), field.getName())
                                .isTrue();
                    }
                }
            }
        }

        @Test
        @DisplayName("exposes exactly one constructor, so every collaborator is injected through it")
        void exposesExactlyOneConstructor() {
            assertThat(AccountRepository.class.getDeclaredConstructors()).hasSize(1);
            assertThat(AccountRepository.class.getDeclaredFields())
                    .allSatisfy(field -> assertThat(field.getAnnotations())
                            .describedAs("%s carries no injection annotation", field.getName())
                            .isEmpty());
        }
    }
}
