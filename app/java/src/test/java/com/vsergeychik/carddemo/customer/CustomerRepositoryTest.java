package com.vsergeychik.carddemo.customer;

import com.vsergeychik.carddemo.common.CicsResponse;
import com.vsergeychik.carddemo.common.DatasetIntegrityException;
import com.vsergeychik.carddemo.common.FileStatus;
import com.vsergeychik.carddemo.common.FileStatus.Outcome;
import com.vsergeychik.carddemo.common.RecordImageForm;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBinding;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBindings;
import com.vsergeychik.carddemo.customer.CustomerRepository.CustomerFile;
import com.vsergeychik.carddemo.customer.CustomerRepository.ReadResult;
import com.vsergeychik.carddemo.customer.CustomerRepository.Statements;
import com.vsergeychik.carddemo.customer.CustomerRepository.WriteResult;
import com.vsergeychik.carddemo.customer.model.CustomerRecord;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
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
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * Unit tests for {@link CustomerRepository}, the customer master dataset's only reader and only writer.
 *
 * <h2>Two kinds of test, and why both are needed</h2>
 *
 * <p><strong>Round trips against a real single-column relation.</strong> The dataset is seeded from
 * {@code src/test/resources/fixtures/custdata.txt} - the classpath copy of
 * {@code app/data/ASCII/custdata.txt}, 50 records of exactly 500 bytes each - into an in-memory relation
 * with one record-image column, which is the shape the parity harness seeds and the shape the production
 * gateway is expected to present. That is what makes the byte-level assertions meaningful: a record read
 * and re-encoded has to come back identical, {@code FILLER X(168)} included.
 *
 * <p><strong>Mocked JDBC for the failure arms.</strong> An unreachable dataset, a relation that describes
 * no record-image column, a row whose image is absent, and a key that selects two rows cannot be produced
 * by a healthy database, so they are driven through mocks. Every one of them must reach a reported file
 * status or a thrown contract violation - never a silent success.
 *
 * <h2>No dataset name from the real system appears here</h2>
 *
 * <p>The tests supply their own dataset name. The real one lives only in {@code application.yml}, which is
 * the whole point of the binding the constructor resolves, so this file needs no mainframe dataset literal
 * and contains none.
 *
 * <h2>Expectations are transcribed from the COBOL</h2>
 *
 * <p>The statuses, the {@code APPL-RESULT} values and the outcome ladders below were written by reading
 * {@code app/cbl/CBCUS01C.cbl:L92-L174}, {@code app/cbl/CBSTM03B.CBL:L181-L204},
 * {@code app/cbl/CBTRN01C.cbl:L271-L287} and {@code L379-L395}, {@code app/cbl/COACTVWC.cbl:L825-L872} and
 * {@code app/cbl/COACTUPC.cbl:L3919-L4103}, and are restated here rather than read back off the
 * implementation, so agreement between the two is an audit and not a tautology.
 */
@DisplayName("CustomerRepository - the customer master dataset over JDBC")
class CustomerRepositoryTest {

    /** The code page every test names explicitly; never a platform default. */
    private static final Charset ASCII = StandardCharsets.US_ASCII;

    /** A stand-in dataset name. The real one lives only in {@code application.yml}. */
    private static final String TEST_DSNAME = "TEST.CUSTOMER.KSDS";

    /** A second stand-in, for the check that the two DD names must agree. */
    private static final String OTHER_DSNAME = "TEST.OTHER.KSDS";

    /** The record-image column of the seeded relation, and the name the probe will discover. */
    private static final String RECORD_IMAGE_COLUMN = "REC";

    /** The classpath location of the customer fixture, copied from the reference tree. */
    private static final String FIXTURE = "/fixtures/custdata.txt";

    /** The fixture's measured record count, restated from the reference data. */
    private static final int FIXTURE_RECORDS = 50;

    /** The copybook record width, restated from {@code app/cpy/CVCUS01Y.cpy:L2} ({@code RECLN 500}). */
    private static final int FIVE_HUNDRED = 500;

    /** The copybook key width, restated from {@code CUST-ID PIC 9(09)} and {@code KEYS(9 0)}. */
    private static final int NINE = 9;

    /** The declared width of {@code FILLER}, restated from {@code app/cpy/CVCUS01Y.cpy:L23}. */
    private static final int FILLER_WIDTH = 168;

    /** A customer identifier the fixture does not contain, for the invalid-key arms. */
    private static final long ABSENT_CUST_ID = 999_999_999L;

    /**
     * Distinguishes the in-memory database each seeded test uses, so no two tests share a relation.
     *
     * <p>A counter rather than a random or time-derived name, so a run is reproducible.
     */
    private static final AtomicInteger DATABASE_SEQUENCE = new AtomicInteger();

    // =============================================================================================
    // Fixtures and helpers.
    // =============================================================================================

    /**
     * Builds the pair of bindings the repository resolves, with every component under the test's control
     * so each of the constructor's checks can be driven both ways.
     *
     * @param cicsDsname   the dataset name for the CICS file key
     * @param batchDsname  the dataset name for the batch DD key
     * @param recordLength the record length to configure for both
     * @param keyLength    the key length to configure for both, or {@code null} to omit it
     * @return a catalogue containing exactly those two bindings
     */
    private static DatasetBindings bindings(String cicsDsname, String batchDsname, int recordLength,
            Integer keyLength) {
        DatasetBindings catalogue = new DatasetBindings();
        catalogue.put(CustomerRepository.CICS_FILE_NAME, new DatasetBinding(cicsDsname, "ksds", false,
                "FB", null, recordLength, "CVCUS01Y", keyLength, null, null, null));
        catalogue.put(CustomerRepository.BATCH_DD_NAME, new DatasetBinding(batchDsname, "ksds", false,
                "FB", null, recordLength, "CVCUS01Y", keyLength, null, null, null));
        return catalogue;
    }

    /**
     * The correctly configured binding pair every behavioural test uses.
     *
     * @return a catalogue naming {@link #TEST_DSNAME} at the copybook width under both keys
     */
    private static DatasetBindings validBindings() {
        return bindings(TEST_DSNAME, TEST_DSNAME, FIVE_HUNDRED, NINE);
    }

    /**
     * A repository over the given template and the correctly configured bindings.
     *
     * @param template the template to reach the relation with
     * @return the repository
     */
    private static CustomerRepository repository(JdbcTemplate template) {
        return new CustomerRepository(template, validBindings(), ASCII, RecordImageForm.CHARACTER);
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
    private static JdbcTemplate seeded(List<String> rows) {
        return seeded(rows, FIVE_HUNDRED);
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
                "jdbc:h2:mem:custrepo" + DATABASE_SEQUENCE.incrementAndGet()
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
        try (InputStream stream = CustomerRepositoryTest.class.getResourceAsStream(FIXTURE)) {
            if (stream == null) {
                throw new IllegalStateException("The customer fixture " + FIXTURE + " is absent from the "
                        + "test classpath; every expectation in this class is seeded from it");
            }
            return new String(stream.readAllBytes(), ASCII).lines().toList();
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    /**
     * The stored key image of a fixture row: its leading nine bytes.
     *
     * @param row a fixture record image
     * @return the nine-character key image
     */
    private static String keyImageOf(String row) {
        return row.substring(0, NINE);
    }

    /**
     * Drains a browse into a list, stopping at the first outcome that is not a record.
     *
     * @param file  the opened file to browse
     * @param limit the most reads to perform, so a defect cannot loop for ever
     * @return the record images returned, in the order returned
     */
    private static List<String> drain(CustomerFile file, int limit) {
        List<String> images = new ArrayList<>();
        for (int read = 0; read < limit; read++) {
            ReadResult result = file.readNext();
            if (!result.isFound()) {
                break;
            }
            images.add(result.customer().orElseThrow().recordImage(ASCII));
        }
        return images;
    }

    /**
     * Runs an action with the calling thread marked as being inside a transaction.
     *
     * <p>The marker rather than a real transaction, because several failure arms are driven through mocked
     * JDBC chains that cannot yield a connection at all - a real transaction manager would fail while
     * trying to begin, before the operation under test ran. What
     * {@link CustomerRepository#readForUpdate(String)} inspects is exactly this marker, so this is the
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

    /**
     * A mocked chain that records the text of every statement prepared against it, returning no rows.
     *
     * <p>This is how the locking read is proved to be a locking read without depending on any backend's
     * behaviour: what the repository asks the driver for is captured verbatim, so a test can assert that a
     * read for update carries {@code FOR UPDATE} and a plain keyed read does not.
     *
     * @param prepared the list every prepared statement's text is appended to, in order
     * @return a template over the recording chain
     * @throws SQLException never; declared because the mocked JDBC methods declare it
     */
    private static JdbcTemplate recordingPreparedStatements(List<String> prepared) throws SQLException {
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
        Mockito.when(preparedStatement.getUpdateCount()).thenReturn(0);
        Mockito.when(emptyResultSet.next()).thenReturn(false);
        return new JdbcTemplate(dataSource);
    }

    /**
     * A mocked chain whose keyed count finds exactly one row and whose {@code UPDATE} reports the count
     * asked for.
     *
     * @param updateCount what {@code executeUpdate} should report
     * @return a template over the mocked chain
     * @throws SQLException never; declared because the mocked JDBC methods declare it
     */
    private static JdbcTemplate countingOneThenReportingUpdateCount(int updateCount) throws SQLException {
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
            CustomerRepository repository = repository(new JdbcTemplate());

            assertThat(repository.datasetName()).isEqualTo(TEST_DSNAME);
            assertThat(repository.recordLength()).isEqualTo(FIVE_HUNDRED);
            assertThat(repository.datasetCharset()).isEqualTo(ASCII);
        }

        @Test
        @DisplayName("names the CICS file CUSTDAT and the batch DD CUSTFILE, and nothing else")
        void namesTheTwoDdNames() {
            // app/csd/CARDDEMO.CSD:L50 and app/jcl/READCUST.jcl:L9-L10. These are configuration keys, so
            // they are the only dataset-related literals this migration permits in Java.
            assertThat(CustomerRepository.CICS_FILE_NAME).isEqualTo("CUSTDAT");
            assertThat(CustomerRepository.BATCH_DD_NAME).isEqualTo("CUSTFILE");
        }

        @Test
        @DisplayName("carries the copybook's own record and key widths rather than restating them")
        void carriesTheCopybookGeometry() {
            assertThat(CustomerRepository.RECORD_LENGTH).isEqualTo(CustomerRecord.RECORD_LENGTH)
                    .isEqualTo(FIVE_HUNDRED);
            assertThat(CustomerRepository.KEY_LENGTH).isEqualTo(CustomerRecord.CUST_ID.length())
                    .isEqualTo(NINE);
            assertThat(CustomerRepository.APPL_RESULT_FATAL).isEqualTo(12);
            // '9' plus a binary feedback byte: the COBOL permanent-error convention that
            // app/cbl/CBCUS01C.cbl:L162-L163 tests for.
            assertThat(CustomerRepository.PERMANENT_ERROR_STATUS)
                    .hasSize(FileStatus.STATUS_LENGTH)
                    .startsWith("9");
            assertThat(FileStatus.outcomeOfStatus(CustomerRepository.PERMANENT_ERROR_STATUS))
                    .isEqualTo(Outcome.OTHER);
        }

        @Test
        @DisplayName("refuses a missing template, catalogue, charset or record-image form")
        void refusesMissingCollaborators() {
            DatasetBindings valid = validBindings();

            assertThatNullPointerException().isThrownBy(() ->
                    new CustomerRepository(null, valid, ASCII, RecordImageForm.CHARACTER));
            assertThatNullPointerException().isThrownBy(() ->
                    new CustomerRepository(new JdbcTemplate(), null, ASCII, RecordImageForm.CHARACTER));
            assertThatNullPointerException().isThrownBy(() ->
                    new CustomerRepository(new JdbcTemplate(), valid, null, RecordImageForm.CHARACTER));
            assertThatNullPointerException().isThrownBy(() ->
                    new CustomerRepository(new JdbcTemplate(), valid, ASCII, null));
        }

        @Test
        @DisplayName("refuses an unconfigured DD name and names the keys it did find")
        void refusesAnUnconfiguredDdName() {
            DatasetBindings onlyBatch = new DatasetBindings();
            onlyBatch.put(CustomerRepository.BATCH_DD_NAME, new DatasetBinding(TEST_DSNAME, "ksds", false,
                    "FB", null, FIVE_HUNDRED, "CVCUS01Y", NINE, null, null, null));

            assertThatIllegalStateException().isThrownBy(() ->
                            new CustomerRepository(new JdbcTemplate(), onlyBatch, ASCII,
                                    RecordImageForm.CHARACTER))
                    .withMessageContaining(CustomerRepository.CICS_FILE_NAME);
        }

        @Test
        @DisplayName("refuses a record width that is not the copybook's 500")
        void refusesTheWrongRecordWidth() {
            DatasetBindings wrongWidth = bindings(TEST_DSNAME, TEST_DSNAME, 300, NINE);

            assertThatIllegalStateException().isThrownBy(() ->
                            new CustomerRepository(new JdbcTemplate(), wrongWidth, ASCII,
                                    RecordImageForm.CHARACTER))
                    .withMessageContaining("RECLN 500")
                    .withMessageContaining("record-length");
        }

        @Test
        @DisplayName("refuses a declared key width that is not the copybook's 9, and accepts an absent one")
        void refusesTheWrongKeyWidth() {
            DatasetBindings wrongKey = bindings(TEST_DSNAME, TEST_DSNAME, FIVE_HUNDRED, 11);

            assertThatIllegalStateException().isThrownBy(() ->
                            new CustomerRepository(new JdbcTemplate(), wrongKey, ASCII,
                                    RecordImageForm.CHARACTER))
                    .withMessageContaining("key-length");

            // An absent width is accepted: the startup validator already requires a keyed entry to declare
            // one, so a binding built by hand without it is a test's binding and not a deployment's.
            DatasetBindings noKey = bindings(TEST_DSNAME, TEST_DSNAME, FIVE_HUNDRED, null);
            assertThat(new CustomerRepository(new JdbcTemplate(), noKey, ASCII, RecordImageForm.CHARACTER)
                    .datasetName()).isEqualTo(TEST_DSNAME);
        }

        @Test
        @DisplayName("refuses two DD names that point at different datasets")
        void refusesTwoDifferentDatasets() {
            DatasetBindings disagreeing = bindings(TEST_DSNAME, OTHER_DSNAME, FIVE_HUNDRED, NINE);

            assertThatIllegalStateException().isThrownBy(() ->
                            new CustomerRepository(new JdbcTemplate(), disagreeing, ASCII,
                                    RecordImageForm.CHARACTER))
                    .withMessageContaining(CustomerRepository.CICS_FILE_NAME)
                    .withMessageContaining(CustomerRepository.BATCH_DD_NAME);
        }

        @ParameterizedTest
        @ValueSource(strings = {"", "   "})
        @DisplayName("refuses a blank dataset name")
        void refusesABlankDatasetName(String blank) {
            DatasetBindings blankName = bindings(blank, blank, FIVE_HUNDRED, NINE);

            assertThatIllegalStateException().isThrownBy(() ->
                            new CustomerRepository(new JdbcTemplate(), blankName, ASCII,
                                    RecordImageForm.CHARACTER))
                    .withMessageContaining("dsname");
        }

        @Test
        @DisplayName("refuses an absent dataset name")
        void refusesAnAbsentDatasetName() {
            DatasetBindings absent = bindings(null, null, FIVE_HUNDRED, NINE);

            assertThatIllegalStateException().isThrownBy(() ->
                            new CustomerRepository(new JdbcTemplate(), absent, ASCII,
                                    RecordImageForm.CHARACTER))
                    .withMessageContaining("dsname");
        }

        @Test
        @DisplayName("holds no mutable state of its own: every field is final")
        void holdsNoMutableState() {
            // Practice B9 and gate G53, asserted rather than assumed: a @Repository is a singleton, so a
            // non-final field would be shared by every concurrent execution.
            for (Field field : CustomerRepository.class.getDeclaredFields()) {
                assertThat(Modifier.isFinal(field.getModifiers()))
                        .as("field %s of CustomerRepository must be final", field.getName())
                        .isTrue();
            }
        }
    }

    // =============================================================================================
    // OPEN INPUT and CLOSE - app/cbl/CBCUS01C.cbl:L118-L152, app/cbl/CBTRN01C.cbl:L271-L287, L379-L395.
    // =============================================================================================

    /** The open and close lifecycle, which is a first-class operation and not a detail of the browse. */
    @Nested
    @DisplayName("OPEN INPUT and CLOSE report the status the COBOL guard tests")
    class OpenAndCloseTests {

        @Test
        @DisplayName("a successful open reports '00' and APPL-RESULT 0")
        void aSuccessfulOpenReportsOk() {
            try (CustomerFile file = repository(seeded(fixtureRows())).openInput()) {
                assertThat(file.openStatus()).isEqualTo(FileStatus.OK);
                assertThat(file.openOutcome()).isEqualTo(Outcome.OK);
                assertThat(file.openApplResult()).isEqualTo(FileStatus.APPL_AOK);
                assertThat(file.datasetName()).isEqualTo(TEST_DSNAME);
                assertThat(file.isClosed()).isFalse();
            }
        }

        @Test
        @DisplayName("an unreachable dataset reports a permanent error and APPL-RESULT 12")
        void anUnreachableDatasetReportsAPermanentError() {
            try (CustomerFile file = repository(unreachable()).openInput()) {
                assertThat(file.openStatus()).isEqualTo(CustomerRepository.PERMANENT_ERROR_STATUS);
                assertThat(file.openOutcome()).isEqualTo(Outcome.OTHER);
                assertThat(file.openApplResult()).isEqualTo(CustomerRepository.APPL_RESULT_FATAL);
            }
        }

        @Test
        @DisplayName("a relation presenting no record-image column is a contract violation, not a status")
        void aRelationWithNoRecordImageColumnThrows() {
            assertThatIllegalStateException()
                    .isThrownBy(() -> repository(describing(0, null)).openInput())
                    .withMessageContaining("record image");
        }

        @Test
        @DisplayName("a blank record-image column name is refused too")
        void aBlankColumnNameThrows() {
            assertThatIllegalStateException()
                    .isThrownBy(() -> repository(describing(1, "   ")).openInput())
                    .withMessageContaining("record image");
        }

        @Test
        @DisplayName("a control character in the discovered column name is refused")
        void aControlCharacterInTheColumnNameThrows() {
            assertThatIllegalStateException()
                    .isThrownBy(() -> repository(describing(1, "RE\u0001C")).openInput())
                    .withMessageContaining("control character");
        }

        @Test
        @DisplayName("closing a successfully opened file reports '00', and again on a second call")
        void closingReportsOkAndIsIdempotent() {
            CustomerFile file = repository(seeded(fixtureRows())).openInput();

            assertThat(file.closeFile()).isEqualTo(FileStatus.OK);
            assertThat(file.isClosed()).isTrue();
            // Idempotent: the file is already closed, so there is nothing left to fail.
            assertThat(file.closeFile()).isEqualTo(FileStatus.OK);
            assertThat(file.closeApplResult()).isEqualTo(FileStatus.APPL_AOK);
        }

        @Test
        @DisplayName("closing a file whose open failed reports that open's own status, and keeps reporting it")
        void closingAFailedOpenReportsTheOpenStatus() {
            CustomerFile file = repository(unreachable()).openInput();

            assertThat(file.closeFile()).isEqualTo(CustomerRepository.PERMANENT_ERROR_STATUS);
            // Strong idempotence: a close that failed does not become a success by being asked again, so
            // the status and the APPL-RESULT cannot disagree with the first answer.
            assertThat(file.closeFile()).isEqualTo(CustomerRepository.PERMANENT_ERROR_STATUS);
            assertThat(file.closeApplResult()).isEqualTo(CustomerRepository.APPL_RESULT_FATAL);
        }

        @Test
        @DisplayName("a failed close reports the same outcome on every later call, without probing again")
        void aFailedCloseKeepsReportingItsOutcome() throws SQLException {
            // The dataset was addressable at OPEN and is not at CLOSE. The second executeQuery is the
            // close's probe; a third would succeed again if the outcome were recomputed, which is exactly
            // the inconsistency the remembered status prevents.
            DataSource dataSource = Mockito.mock(DataSource.class);
            Connection connection = Mockito.mock(Connection.class);
            Statement statement = Mockito.mock(Statement.class);
            ResultSet resultSet = Mockito.mock(ResultSet.class);
            ResultSetMetaData metaData = Mockito.mock(ResultSetMetaData.class);
            Mockito.when(dataSource.getConnection()).thenReturn(connection);
            Mockito.when(connection.createStatement()).thenReturn(statement);
            Mockito.when(statement.executeQuery(Mockito.anyString())).thenReturn(resultSet)
                    .thenThrow(new SQLException("the dataset is no longer addressable"))
                    .thenReturn(resultSet);
            Mockito.when(resultSet.getMetaData()).thenReturn(metaData);
            Mockito.when(metaData.getColumnCount()).thenReturn(1);
            Mockito.when(metaData.getColumnName(1)).thenReturn(RECORD_IMAGE_COLUMN);

            CustomerFile file = repository(new JdbcTemplate(dataSource)).openInput();

            assertThat(file.closeFile()).isEqualTo(CustomerRepository.PERMANENT_ERROR_STATUS);
            assertThat(file.closeFile()).isEqualTo(CustomerRepository.PERMANENT_ERROR_STATUS);
            assertThat(file.closeApplResult()).isEqualTo(CustomerRepository.APPL_RESULT_FATAL);
        }

        @Test
        @DisplayName("a close whose probe fails reports a permanent error and APPL-RESULT 12")
        void aCloseThatCannotProbeReportsAPermanentError() throws SQLException {
            // The dataset was addressable at OPEN and is not at CLOSE - the analogue of a dataset being
            // de-allocated under the program, which is the one close-time failure this handle can detect.
            DataSource dataSource = Mockito.mock(DataSource.class);
            Connection connection = Mockito.mock(Connection.class);
            Statement statement = Mockito.mock(Statement.class);
            ResultSet resultSet = Mockito.mock(ResultSet.class);
            ResultSetMetaData metaData = Mockito.mock(ResultSetMetaData.class);
            Mockito.when(dataSource.getConnection()).thenReturn(connection);
            Mockito.when(connection.createStatement()).thenReturn(statement);
            Mockito.when(statement.executeQuery(Mockito.anyString())).thenReturn(resultSet)
                    .thenThrow(new SQLException("the dataset is no longer addressable"));
            Mockito.when(resultSet.getMetaData()).thenReturn(metaData);
            Mockito.when(metaData.getColumnCount()).thenReturn(1);
            Mockito.when(metaData.getColumnName(1)).thenReturn(RECORD_IMAGE_COLUMN);

            CustomerFile file = repository(new JdbcTemplate(dataSource)).openInput();
            assertThat(file.openStatus()).isEqualTo(FileStatus.OK);
            assertThat(file.closeFile()).isEqualTo(CustomerRepository.PERMANENT_ERROR_STATUS);
        }

        @Test
        @DisplayName("CBTRN01C's open-without-a-read survives: both guards are observable")
        void reproducesTheOpenWithoutAReadQuirk() {
            // app/cbl/CBTRN01C.cbl opens the customer file at L273 and closes it at L381 and never reads
            // it - there is no READ CUSTOMER-FILE anywhere in that program. Practice B5 keeps that
            // behaviour, which is only expressible because the open and the close each report a status.
            CustomerRepository repository = repository(seeded(fixtureRows()));

            CustomerFile file = repository.openInput();
            assertThat(file.openApplResult()).isEqualTo(FileStatus.APPL_AOK);   // L274-L278
            assertThat(file.closeApplResult()).isEqualTo(FileStatus.APPL_AOK);  // L382-L386
            assertThat(file.isClosed()).isTrue();
        }

        @Test
        @DisplayName("operating on a closed handle is a defect and throws")
        void operatingOnAClosedHandleThrows() {
            CustomerFile file = repository(seeded(fixtureRows())).openInput();
            file.closeFile();

            assertThatIllegalStateException().isThrownBy(file::readNext)
                    .withMessageContaining("has been closed");
            assertThatIllegalStateException().isThrownBy(() -> file.readByKey(1L))
                    .withMessageContaining("has been closed");
            assertThatIllegalStateException()
                    .isThrownBy(() -> file.readByKey("000000001"))
                    .withMessageContaining("has been closed");
        }
    }

    // =============================================================================================
    // The sequential browse - app/cbl/CBCUS01C.cbl:L92-L116.
    // =============================================================================================

    /** The browse, whose ordering is parity-relevant and therefore asserted. */
    @Nested
    @DisplayName("The sequential browse reads every record in ascending CUST-ID order")
    class BrowseTests

    {
        @Test
        @DisplayName("returns all 50 fixture records, then '10' at end of file")
        void readsEveryRecordThenReportsEndOfFile() {
            List<String> rows = fixtureRows();
            assertThat(rows).hasSize(FIXTURE_RECORDS);

            try (CustomerFile file = repository(seeded(rows)).openInput()) {
                List<String> read = drain(file, FIXTURE_RECORDS + 1);

                assertThat(read).hasSize(FIXTURE_RECORDS);
                // Every image round-trips byte for byte, FILLER X(168) included.
                assertThat(read).containsExactlyElementsOf(rows.stream().sorted().toList());

                ReadResult end = file.readNext();
                assertThat(end.isEndOfFile()).isTrue();
                assertThat(end.status()).isEqualTo(FileStatus.END_OF_FILE);
                assertThat(end.applResult()).isEqualTo(FileStatus.APPL_EOF);
                assertThat(end.customer()).isEmpty();
                // Idempotent: the COBOL loop stops on END-OF-FILE = 'Y' and never resumes.
                assertThat(file.readNext().isEndOfFile()).isTrue();
            }
        }

        @Test
        @DisplayName("delivers records in ascending key order even when the relation is seeded backwards")
        void ordersByTheKeyRatherThanByScanOrder() {
            List<String> rows = new ArrayList<>(fixtureRows());
            java.util.Collections.reverse(rows);

            try (CustomerFile file = repository(seeded(rows)).openInput()) {
                List<String> read = drain(file, FIXTURE_RECORDS + 1);

                List<String> keys = read.stream().map(CustomerRepositoryTest::keyImageOf).toList();
                assertThat(keys).isSorted();
            }
        }

        @Test
        @DisplayName("an empty dataset reports '10' on the very first read")
        void anEmptyDatasetIsImmediatelyAtEndOfFile() {
            try (CustomerFile file = repository(seeded(List.of())).openInput()) {
                assertThat(file.readNext().isEndOfFile()).isTrue();
            }
        }

        @Test
        @DisplayName("a decoded record carries the copybook's own field values")
        void decodesTheCopybookFields() {
            List<String> rows = fixtureRows();
            String first = rows.stream().sorted().findFirst().orElseThrow();

            try (CustomerFile file = repository(seeded(rows)).openInput()) {
                ReadResult result = file.readNext();

                assertThat(result.isFound()).isTrue();
                assertThat(result.status()).isEqualTo(FileStatus.OK);
                assertThat(result.applResult()).isEqualTo(FileStatus.APPL_AOK);
                assertThat(result.cicsResp()).hasValue(FileStatus.NORMAL);
                assertThat(result.cicsResp2()).isEqualTo(FileStatus.NO_REASON_CODE);
                assertThat(result.diagnostic()).isEmpty();

                CustomerRecord record = result.customer().orElseThrow();
                assertThat(record.custIdImage(ASCII)).isEqualTo(keyImageOf(first));
                // The record re-encodes to the very bytes it was decoded from: 500 wide, FILLER included.
                assertThat(record.encode(ASCII)).hasSize(FIVE_HUNDRED);
                assertThat(record.recordImage(ASCII)).isEqualTo(first);
                assertThat(record.recordImage(ASCII).substring(FIVE_HUNDRED - FILLER_WIDTH))
                        .isEqualTo(" ".repeat(FILLER_WIDTH));
            }
        }

        @Test
        @DisplayName("a browse over a handle whose open failed reports that open's status, not end of file")
        void aBrowseOverAFailedOpenReportsTheOpenStatus() {
            try (CustomerFile file = repository(unreachable()).openInput()) {
                ReadResult result = file.readNext();

                assertThat(result.isEndOfFile()).isFalse();
                assertThat(result.isOther()).isTrue();
                assertThat(result.status()).isEqualTo(CustomerRepository.PERMANENT_ERROR_STATUS);
                assertThat(result.applResult()).isEqualTo(CustomerRepository.APPL_RESULT_FATAL);
            }
        }

        @Test
        @DisplayName("a refused browse read reports a permanent error rather than throwing")
        void aRefusedBrowseReadReportsAPermanentError() throws SQLException {
            try (CustomerFile file = repository(describingThenRefusing()).openInput()) {
                ReadResult result = file.readNext();

                assertThat(result.isOther()).isTrue();
                assertThat(result.status()).isEqualTo(CustomerRepository.PERMANENT_ERROR_STATUS);
            }
        }

        @Test
        @DisplayName("a row whose record image is absent is an I/O defect, never an end of file")
        void aRowWithNoImageIsNotAnEndOfFile() throws SQLException {
            try (CustomerFile file = repository(describingThenReturningNoImage()).openInput()) {
                ReadResult result = file.readNext();

                assertThat(result.isEndOfFile()).isFalse();
                assertThat(result.isOther()).isTrue();
                assertThat(result.cicsResp()).hasValue(FileStatus.INVREQ);
            }
        }

        @ParameterizedTest
        @ValueSource(ints = {FIVE_HUNDRED - 1, FIVE_HUNDRED + 1})
        @DisplayName("a stored image that is not 500 bytes is reported as LENGERR, never repaired")
        void aWrongWidthImageIsReportedNotRepaired(int storedWidth) {
            String row = "1".repeat(storedWidth);

            try (CustomerFile file = repository(seeded(List.of(row), storedWidth)).openInput()) {
                ReadResult result = file.readNext();

                assertThat(result.isFound()).isFalse();
                assertThat(result.isOther()).isTrue();
                assertThat(result.status()).isEqualTo(CustomerRepository.PERMANENT_ERROR_STATUS);
                assertThat(result.cicsResp()).hasValue(FileStatus.LENGERR);
                assertThat(result.applResult()).isEqualTo(CustomerRepository.APPL_RESULT_FATAL);
            }
        }

        @Test
        @DisplayName("the browse statements carry an explicit ascending ORDER BY and no dialect syntax")
        void theBrowseStatementsCarryAnExplicitOrdering() throws SQLException {
            CustomerRepository repository = repository(describing(1, RECORD_IMAGE_COLUMN));
            Statements statements = repository.resolveStatements();

            assertThat(statements.selectFirst()).contains("ORDER BY").contains("ASC")
                    .doesNotContain("WHERE");
            assertThat(statements.selectNext()).contains("ORDER BY").contains("ASC").contains(" > ?");
            assertThat(statements.selectFirst() + statements.selectNext())
                    .doesNotContainIgnoringCase("fetch first")
                    .doesNotContainIgnoringCase("limit")
                    .doesNotContainIgnoringCase("offset")
                    .doesNotContainIgnoringCase("rownum");
            assertThat(repository.columnProbeSql()).contains("1 = 0");
        }
    }

    // =============================================================================================
    // The keyed read - app/cbl/CBSTM03B.CBL:L188-L193, app/cbl/COACTVWC.cbl:L825-L872.
    // =============================================================================================

    /** The keyed read, from both views of the key the source holds. */
    @Nested
    @DisplayName("The keyed read finds a record by CUST-ID, or reports '23'")
    class KeyedReadTests {

        @Test
        @DisplayName("finds every fixture record by its numeric identifier")
        void findsByNumericIdentifier() {
            List<String> rows = fixtureRows();
            CustomerRepository repository = repository(seeded(rows));

            for (String row : rows) {
                long custId = Long.parseLong(keyImageOf(row));
                ReadResult result = repository.readByKey(custId);

                assertThat(result.isFound()).as("customer %s", custId).isTrue();
                assertThat(result.customer().orElseThrow().recordImage(ASCII)).isEqualTo(row);
            }
        }

        @Test
        @DisplayName("finds a record by the nine-character RIDFLD image")
        void findsByCharacterKeyImage() {
            List<String> rows = fixtureRows();
            String row = rows.get(0);

            ReadResult result = repository(seeded(rows)).readByKey(keyImageOf(row));

            assertThat(result.isFound()).isTrue();
            assertThat(result.customer().orElseThrow().recordImage(ASCII)).isEqualTo(row);
        }

        @Test
        @DisplayName("an absent key reports '23' with no record, and never null")
        void anAbsentKeyReportsNotFound() {
            CustomerRepository repository = repository(seeded(fixtureRows()));

            ReadResult byNumber = repository.readByKey(ABSENT_CUST_ID);
            assertThat(byNumber).isNotNull();
            assertThat(byNumber.isNotFound()).isTrue();
            assertThat(byNumber.status()).isEqualTo(FileStatus.NOT_FOUND);
            assertThat(byNumber.customer()).isEmpty();
            assertThat(byNumber.applResult()).isEqualTo(CustomerRepository.APPL_RESULT_FATAL);
            assertThat(byNumber.cicsResp()).hasValue(FileStatus.NOTFND);

            ReadResult byImage = repository.readByKey("999999998");
            assertThat(byImage.isNotFound()).isTrue();
        }

        @Test
        @DisplayName("a negative identifier is refused: PIC 9 has no sign position")
        void refusesANegativeIdentifier() {
            CustomerRepository repository = repository(seeded(fixtureRows()));

            assertThatIllegalArgumentException().isThrownBy(() -> repository.readByKey(-1L));
        }

        @Test
        @DisplayName("an over-wide identifier is truncated on the LEFT, as COBOL truncates PIC 9")
        void truncatesAnOverWideIdentifierOnTheLeft() {
            List<String> rows = fixtureRows();
            String row = rows.get(0);
            long custId = Long.parseLong(keyImageOf(row));
            // A ten-digit sending value whose low-order nine digits are the stored key. COBOL aligns a
            // numeric receiver on its implied decimal point, so the HIGH-order digit is what is lost, and
            // this must reproduce that rather than reject the value.
            long overWide = 7_000_000_000L + custId;

            ReadResult result = repository(seeded(rows)).readByKey(overWide);

            assertThat(result.isFound()).isTrue();
            assertThat(result.customer().orElseThrow().custIdImage(ASCII)).isEqualTo(keyImageOf(row));
        }

        @ParameterizedTest
        @ValueSource(strings = {"12345678", "1234567890", ""})
        @DisplayName("a key image of the wrong width is refused, never trimmed or padded")
        void refusesAKeyImageOfTheWrongWidth(String malformed) {
            CustomerRepository repository = repository(seeded(fixtureRows()));

            assertThatIllegalArgumentException().isThrownBy(() -> repository.readByKey(malformed))
                    .withMessageContaining("PIC X");
        }

        @Test
        @DisplayName("an absent key image is refused")
        void refusesAnAbsentKeyImage() {
            CustomerRepository repository = repository(seeded(fixtureRows()));

            assertThatNullPointerException().isThrownBy(() -> repository.readByKey((String) null));
        }

        @Test
        @DisplayName("a key image of spaces is read verbatim and simply finds nothing")
        void aBlankKeyImageIsNotParsed() {
            // A partially-typed screen field reaches the file as spaces; the COBOL read reports that no
            // such record exists rather than rejecting the input, so parsing here would add a rejection
            // the program never performs.
            ReadResult result = repository(seeded(fixtureRows())).readByKey(" ".repeat(NINE));

            assertThat(result.isNotFound()).isTrue();
        }

        @Test
        @DisplayName("LIKE metacharacters in a key image cannot widen the match")
        void escapesLikeMetacharacters() {
            // '_________' would match every record if the pattern were not escaped.
            ReadResult result = repository(seeded(fixtureRows())).readByKey("_".repeat(NINE));

            assertThat(result.isNotFound()).isTrue();
        }

        @Test
        @DisplayName("an unreachable dataset reports a permanent error carrying the backend diagnostic")
        void anUnreachableDatasetReportsADiagnostic() {
            ReadResult result = repository(unreachable()).readByKey(1L);

            assertThat(result.isOther()).isTrue();
            assertThat(result.status()).isEqualTo(CustomerRepository.PERMANENT_ERROR_STATUS);
            assertThat(result.diagnostic()).isPresent();
        }

        @Test
        @DisplayName("a refused keyed statement reports a permanent error")
        void aRefusedKeyedStatementReportsAPermanentError() throws SQLException {
            ReadResult result = repository(describingThenRefusing()).readByKey("000000001");

            assertThat(result.isOther()).isTrue();
            assertThat(result.diagnostic()).isPresent();
        }

        @Test
        @DisplayName("a keyed row with no record image reports INVREQ")
        void aKeyedRowWithNoImageReportsInvalidRequest() throws SQLException {
            ReadResult result = repository(describingThenReturningNoImage()).readByKey(1L);

            assertThat(result.isOther()).isTrue();
            assertThat(result.cicsResp()).hasValue(FileStatus.INVREQ);
        }

        @Test
        @DisplayName("the handle's keyed read reuses the open's shape and leaves the browse position alone")
        void theHandleKeyedReadLeavesTheBrowsePositionAlone() {
            List<String> rows = fixtureRows();
            List<String> sorted = rows.stream().sorted().toList();

            try (CustomerFile file = repository(seeded(rows)).openInput()) {
                assertThat(file.readNext().customer().orElseThrow().recordImage(ASCII))
                        .isEqualTo(sorted.get(0));

                // A random READ under the same OPEN INPUT - app/cbl/CBSTM03B.CBL:L184 then L190.
                String last = sorted.get(sorted.size() - 1);
                assertThat(file.readByKey(keyImageOf(last)).customer().orElseThrow()
                        .recordImage(ASCII)).isEqualTo(last);
                assertThat(file.readByKey(Long.parseLong(keyImageOf(last))).isFound()).isTrue();

                // The browse resumes where it was, not where the keyed read went.
                assertThat(file.readNext().customer().orElseThrow().recordImage(ASCII))
                        .isEqualTo(sorted.get(1));
            }
        }

        @Test
        @DisplayName("the handle's keyed reads over a failed open report that open's status")
        void theHandleKeyedReadOverAFailedOpenReportsTheOpenStatus() {
            try (CustomerFile file = repository(unreachable()).openInput()) {
                assertThat(file.readByKey(1L).status())
                        .isEqualTo(CustomerRepository.PERMANENT_ERROR_STATUS);
                assertThat(file.readByKey("000000001").status())
                        .isEqualTo(CustomerRepository.PERMANENT_ERROR_STATUS);
            }
        }

        @Test
        @DisplayName("the handle's keyed read refuses a malformed key image")
        void theHandleKeyedReadRefusesAMalformedKey() {
            try (CustomerFile file = repository(seeded(fixtureRows())).openInput()) {
                assertThatIllegalArgumentException().isThrownBy(() -> file.readByKey("1"));
                assertThatNullPointerException().isThrownBy(() -> file.readByKey((String) null));
            }
        }
    }

    // =============================================================================================
    // The read for update - app/cbl/COACTUPC.cbl:L3921-L3930, "Could we lock the customer record ?".
    // =============================================================================================

    /** The locking read, which is a distinct operation from the plain keyed read. */
    @Nested
    @DisplayName("The read for update takes a row lock and requires a unit of work")
    class ReadForUpdateTests {

        @Test
        @DisplayName("refuses to run outside a unit of work, because the lock would be released at once")
        void refusesOutsideAUnitOfWork() {
            CustomerRepository repository = repository(seeded(fixtureRows()));

            assertThatIllegalStateException()
                    .isThrownBy(() -> repository.readForUpdate("000000001"))
                    .withMessageContaining("unit of work");
        }

        @Test
        @DisplayName("validates the key image before it inspects the unit of work")
        void validatesTheKeyBeforeTheUnitOfWork() {
            CustomerRepository repository = repository(seeded(fixtureRows()));

            // The width check comes first, so a malformed key is reported as a malformed key rather than
            // as a missing transaction.
            assertThatIllegalArgumentException().isThrownBy(() -> repository.readForUpdate("1"));
            assertThatNullPointerException().isThrownBy(() -> repository.readForUpdate(null));
        }

        @Test
        @DisplayName("finds the record inside a unit of work")
        void findsTheRecordInsideAUnitOfWork() {
            List<String> rows = fixtureRows();
            String row = rows.get(0);
            CustomerRepository repository = repository(seeded(rows));

            ReadResult result = withUnitOfWork(() -> repository.readForUpdate(keyImageOf(row)));

            assertThat(result.isFound()).isTrue();
            assertThat(result.customer().orElseThrow().recordImage(ASCII)).isEqualTo(row);
        }

        @Test
        @DisplayName("issues FOR UPDATE, and the plain keyed read does not")
        void issuesForUpdateOnlyForTheLockingRead() throws SQLException {
            List<String> prepared = new ArrayList<>();
            CustomerRepository repository = repository(recordingPreparedStatements(prepared));

            repository.readByKey("000000001");
            assertThat(prepared).hasSize(1);
            assertThat(prepared.get(0)).doesNotContain("FOR UPDATE");

            prepared.clear();
            withUnitOfWork(() -> repository.readForUpdate("000000001"));
            assertThat(prepared).hasSize(1);
            assertThat(prepared.get(0)).endsWith("FOR UPDATE");
        }

        @Test
        @DisplayName("an absent record for update reports '23'")
        void anAbsentRecordForUpdateReportsNotFound() {
            CustomerRepository repository = repository(seeded(fixtureRows()));

            ReadResult result = withUnitOfWork(() -> repository.readForUpdate("999999997"));

            assertThat(result.isNotFound()).isTrue();
        }

        @Test
        @DisplayName("a refused locking read reports a permanent error")
        void aRefusedLockingReadReportsAPermanentError() throws SQLException {
            CustomerRepository repository = repository(describingThenRefusing());

            ReadResult result = withUnitOfWork(() -> repository.readForUpdate("000000001"));

            assertThat(result.isOther()).isTrue();
            assertThat(result.diagnostic()).isPresent();
        }

        @Test
        @DisplayName("an unreachable dataset reports a permanent error rather than throwing")
        void anUnreachableDatasetForUpdateReportsAPermanentError() {
            CustomerRepository repository = repository(unreachable());

            ReadResult result = withUnitOfWork(() -> repository.readForUpdate("000000001"));

            assertThat(result.isOther()).isTrue();
        }

        @Test
        @DisplayName("the handle offers no locking read and no rewrite, because no program opens I-O")
        void theHandleIsReadOnly() {
            // app/cbl grep: OPEN INPUT is the only open verb against this dataset (CBCUS01C:120,
            // CBTRN01C:273, CBSTM03B.CBL:184). A handle-level update would be a state the estate cannot
            // reach, so the surface deliberately does not exist.
            List<String> methods = java.util.Arrays.stream(CustomerFile.class.getDeclaredMethods())
                    .map(java.lang.reflect.Method::getName)
                    .toList();

            assertThat(methods).doesNotContain("readForUpdate", "rewrite");
        }
    }

    // =============================================================================================
    // The rewrite - app/cbl/COACTUPC.cbl:L4085-L4091.
    // =============================================================================================

    /** The only write of any kind against this dataset. */
    @Nested
    @DisplayName("The rewrite replaces exactly one record, or reports why it did not")
    class RewriteTests {

        /**
         * A fixture record with its FICO score span overwritten, so a rewrite is observable.
         *
         * @param row the stored image to change
         * @return a 500-character image differing from {@code row} only in the score span
         */
        private String withChangedScore(String row) {
            int scoreOffset = CustomerRecord.CUST_FICO_CREDIT_SCORE.offset();
            int scoreLength = CustomerRecord.CUST_FICO_CREDIT_SCORE.length();
            return row.substring(0, scoreOffset) + "7".repeat(scoreLength)
                    + row.substring(scoreOffset + scoreLength);
        }

        @Test
        @DisplayName("rewrites one record from a raw image and reports '00'")
        void rewritesFromARawImage() {
            List<String> rows = fixtureRows();
            String row = rows.get(0);
            String changed = withChangedScore(row);
            JdbcTemplate template = seeded(rows);
            CustomerRepository repository = repository(template);

            WriteResult result = repository.rewrite(changed.getBytes(ASCII));

            assertThat(result.isWritten()).isTrue();
            assertThat(result.status()).isEqualTo(FileStatus.OK);
            assertThat(result.applResult()).isEqualTo(FileStatus.APPL_AOK);
            assertThat(result.diagnostic()).isEmpty();
            // Byte-identical to what was handed in, all 500 bytes of it.
            assertThat(repository.readByKey(keyImageOf(row)).customer().orElseThrow()
                    .recordImage(ASCII)).isEqualTo(changed).hasSize(FIVE_HUNDRED);
        }

        @Test
        @DisplayName("rewrites one record from the record model and reports '00'")
        void rewritesFromTheRecordModel() {
            List<String> rows = fixtureRows();
            String row = rows.get(0);
            JdbcTemplate template = seeded(rows);
            CustomerRepository repository = repository(template);

            CustomerRecord record = repository.readByKey(keyImageOf(row)).customer().orElseThrow();
            record.setCustFicoCreditScore(777);

            WriteResult result = repository.rewrite(record);

            assertThat(result.isWritten()).isTrue();
            CustomerRecord reread = repository.readByKey(keyImageOf(row)).customer().orElseThrow();
            assertThat(reread.getCustFicoCreditScore()).isEqualTo(777);
            // Nothing else moved: the whole record round-tripped through the same code page.
            assertThat(reread.recordImage(ASCII)).hasSize(FIVE_HUNDRED)
                    .isEqualTo(record.recordImage(ASCII));
        }

        @Test
        @DisplayName("a key that matches nothing reports '23' and writes no row")
        void anAbsentKeyReportsNotFound() {
            List<String> rows = fixtureRows();
            JdbcTemplate template = seeded(rows);
            CustomerRepository repository = repository(template);
            String absent = "999999996" + " ".repeat(FIVE_HUNDRED - NINE);

            WriteResult result = repository.rewrite(absent.getBytes(ASCII));

            assertThat(result.isNotFound()).isTrue();
            assertThat(result.status()).isEqualTo(FileStatus.NOT_FOUND);
            assertThat(result.applResult()).isEqualTo(CustomerRepository.APPL_RESULT_FATAL);
            assertThat(template.queryForObject("SELECT COUNT(*) FROM \"" + TEST_DSNAME + "\"",
                    Integer.class)).isEqualTo(FIXTURE_RECORDS);
        }

        @ParameterizedTest
        @ValueSource(ints = {0, FIVE_HUNDRED - 1, FIVE_HUNDRED + 1})
        @DisplayName("an image that is not 500 bytes is refused, never padded or truncated")
        void refusesAnImageOfTheWrongWidth(int width) {
            CustomerRepository repository = repository(seeded(fixtureRows()));
            byte[] malformed = new byte[width];

            assertThatIllegalArgumentException().isThrownBy(() -> repository.rewrite(malformed))
                    .withMessageContaining("RECLN 500");
        }

        @Test
        @DisplayName("an absent image or record is refused")
        void refusesAnAbsentOperand() {
            CustomerRepository repository = repository(seeded(fixtureRows()));

            assertThatNullPointerException().isThrownBy(() -> repository.rewrite((byte[]) null));
            assertThatNullPointerException().isThrownBy(() -> repository.rewrite((CustomerRecord) null));
        }

        @Test
        @DisplayName("a key selecting two rows is refused BEFORE anything is written")
        void refusesAFanOutBeforeWriting() {
            List<String> rows = fixtureRows();
            String row = rows.get(0);
            // Two rows under one key: impossible against a KSDS primary key, and the seeded relation
            // deliberately has no constraint so the arm is reachable.
            List<String> duplicated = new ArrayList<>(rows);
            duplicated.add(row);
            JdbcTemplate template = seeded(duplicated);
            CustomerRepository repository = repository(template);
            String changed = withChangedScore(row);

            WriteResult result = repository.rewrite(changed.getBytes(ASCII));

            assertThat(result.isOther()).isTrue();
            assertThat(result.status()).isEqualTo(CustomerRepository.PERMANENT_ERROR_STATUS);
            assertThat(result.cicsResp()).hasValue(FileStatus.INVREQ);
            // Nothing was written: both rows still hold the original image.
            assertThat(template.queryForObject("SELECT COUNT(*) FROM \"" + TEST_DSNAME
                    + "\" WHERE " + RECORD_IMAGE_COLUMN + " = ?", Integer.class, row)).isEqualTo(2);
        }

        @Test
        @DisplayName("a fan-out discovered only after the write refuses the unit of work")
        void refusesTheUnitOfWorkOnAPostWriteFanOut() throws SQLException {
            // The race the pre-write count cannot close: the relation changed between the count and the
            // UPDATE, so rows the count never saw were replaced. The damage is done, and a file status
            // would let the enclosing unit of work commit it.
            CustomerRepository repository = repository(countingOneThenReportingUpdateCount(2));
            byte[] image = ("000000001" + " ".repeat(FIVE_HUNDRED - NINE)).getBytes(ASCII);

            assertThatExceptionOfType(DatasetIntegrityException.class)
                    .isThrownBy(() -> repository.rewrite(image));
        }

        @Test
        @DisplayName("a row lost between the count and the write is the invalid-key condition")
        void aRowLostBeforeTheWriteIsNotFound() throws SQLException {
            CustomerRepository repository = repository(countingOneThenReportingUpdateCount(0));
            byte[] image = ("000000001" + " ".repeat(FIVE_HUNDRED - NINE)).getBytes(ASCII);

            assertThat(repository.rewrite(image).isNotFound()).isTrue();
        }

        @Test
        @DisplayName("an unreachable dataset reports a permanent error rather than throwing")
        void anUnreachableDatasetReportsAPermanentError() {
            byte[] image = ("000000001" + " ".repeat(FIVE_HUNDRED - NINE)).getBytes(ASCII);

            WriteResult result = repository(unreachable()).rewrite(image);

            assertThat(result.isOther()).isTrue();
            assertThat(result.diagnostic()).isPresent();
        }

        @Test
        @DisplayName("a refused statement is reported at the count and at the write")
        void aRefusedStatementIsReported() throws SQLException {
            byte[] image = ("000000001" + " ".repeat(FIVE_HUNDRED - NINE)).getBytes(ASCII);

            WriteResult result = repository(describingThenRefusing()).rewrite(image);

            assertThat(result.isOther()).isTrue();
            assertThat(result.diagnostic()).isPresent();
        }

        @Test
        @DisplayName("the caller's array cannot change what is written after the count")
        void copiesTheCallersArray() {
            List<String> rows = fixtureRows();
            String row = rows.get(0);
            String changed = withChangedScore(row);
            JdbcTemplate template = seeded(rows);
            CustomerRepository repository = repository(template);
            byte[] image = changed.getBytes(ASCII);

            assertThat(repository.rewrite(image).isWritten()).isTrue();
            // Mutating the caller's array afterwards must not have altered what was stored.
            java.util.Arrays.fill(image, (byte) 'Z');
            assertThat(repository.readByKey(keyImageOf(row)).customer().orElseThrow()
                    .recordImage(ASCII)).isEqualTo(changed);
        }

        @Test
        @DisplayName("the rewrite statement is a single-column UPDATE and introduces no schema")
        void theRewriteStatementIntroducesNoSchema() throws SQLException {
            Statements statements = repository(describing(1, RECORD_IMAGE_COLUMN)).resolveStatements();

            assertThat(statements.rewrite()).startsWith("UPDATE ").contains(" SET ").contains("LIKE ? ")
                    .doesNotContainIgnoringCase("create")
                    .doesNotContainIgnoringCase("alter")
                    .doesNotContainIgnoringCase("drop")
                    .doesNotContainIgnoringCase("version");
            assertThat(statements.selectByKeyForUpdate()).isEqualTo(statements.selectByKey()
                    + " FOR UPDATE");
        }
    }

    // =============================================================================================
    // The discriminated outcomes - the COBOL guard chain's vocabulary, and every arm of it.
    // =============================================================================================

    /** Every file status the guard chains distinguish, and the invariants that keep them honest. */
    @Nested
    @DisplayName("The outcomes carry every status the COBOL guard chain distinguishes")
    class OutcomeTests {

        @Test
        @DisplayName("a read reports '00' with a record, and the APPL-RESULT the COBOL moves")
        void aSuccessfulReadCarriesTheRecord() {
            ReadResult result = ReadResult.found(new CustomerRecord());

            assertThat(result.status()).isEqualTo(FileStatus.OK);
            assertThat(result.outcome()).isEqualTo(Outcome.OK);
            assertThat(result.isFound()).isTrue();
            assertThat(result.isEndOfFile()).isFalse();
            assertThat(result.isNotFound()).isFalse();
            assertThat(result.isDuplicate()).isFalse();
            assertThat(result.isOther()).isFalse();
            assertThat(result.customer()).isPresent();
            assertThat(result.applResult()).isEqualTo(FileStatus.APPL_AOK);
            assertThat(result.statusImage()).isEqualTo(FileStatus.toStatusImage(FileStatus.OK));
        }

        @Test
        @DisplayName("a read reports '10' at end of file, with APPL-RESULT 16")
        void aBrowseReportsEndOfFile() {
            ReadResult result = ReadResult.endOfFile();

            assertThat(result.status()).isEqualTo(FileStatus.END_OF_FILE);
            assertThat(result.isEndOfFile()).isTrue();
            assertThat(result.isFound()).isFalse();
            assertThat(result.applResult()).isEqualTo(FileStatus.APPL_EOF);
            assertThat(result.cicsResp()).hasValue(FileStatus.ENDFILE);
        }

        @Test
        @DisplayName("a read reports '23' when the key names nothing, with APPL-RESULT 12")
        void aKeyedReadReportsNotFound() {
            ReadResult result = ReadResult.notFound();

            assertThat(result.status()).isEqualTo(FileStatus.NOT_FOUND);
            assertThat(result.isNotFound()).isTrue();
            assertThat(result.applResult()).isEqualTo(CustomerRepository.APPL_RESULT_FATAL);
        }

        @Test
        @DisplayName("'22' is representable and distinguishable, though this repository cannot produce it")
        void aDuplicateKeyIsRepresentable() {
            // No operation here adds a record, so '22' is unreachable through the repository. It is carried
            // verbatim rather than folded into the catch-all, so a deployment adapter reporting it cannot
            // have it silently reinterpreted.
            ReadResult read = ReadResult.of(FileStatus.DUPLICATE);
            assertThat(read.isDuplicate()).isTrue();
            assertThat(read.outcome()).isEqualTo(Outcome.DUPLICATE);
            assertThat(read.isOther()).isFalse();
            assertThat(read.applResult()).isEqualTo(CustomerRepository.APPL_RESULT_FATAL);

            WriteResult write = WriteResult.of(FileStatus.DUPLICATE);
            assertThat(write.isDuplicate()).isTrue();
            assertThat(write.isWritten()).isFalse();
            assertThat(write.isOther()).isFalse();
        }

        @Test
        @DisplayName("the WHEN OTHER arm carries the status verbatim and renders as the COBOL renders it")
        void theCatchAllArmCarriesTheStatusVerbatim() {
            ReadResult result = ReadResult.of(CustomerRepository.PERMANENT_ERROR_STATUS);

            assertThat(result.isOther()).isTrue();
            assertThat(result.outcome()).isEqualTo(Outcome.OTHER);
            assertThat(result.applResult()).isEqualTo(CustomerRepository.APPL_RESULT_FATAL);
            // Z-DISPLAY-IO-STATUS, app/cbl/CBCUS01C.cbl:L161-L174 - delegated, never reimplemented.
            assertThat(result.statusImage())
                    .isEqualTo(FileStatus.toStatusImage(CustomerRepository.PERMANENT_ERROR_STATUS))
                    .hasSize(FileStatus.STATUS_IMAGE_LENGTH);
            assertThat(FileStatus.toDisplayLine(CustomerRepository.PERMANENT_ERROR_STATUS))
                    .startsWith(FileStatus.DISPLAY_PREFIX);
        }

        @Test
        @DisplayName("a write reports '00' when one row was replaced and '23' when none was")
        void aWriteReportsItsTwoArms() {
            WriteResult written = WriteResult.written();
            assertThat(written.isWritten()).isTrue();
            assertThat(written.isNotFound()).isFalse();
            assertThat(written.isOther()).isFalse();
            assertThat(written.applResult()).isEqualTo(FileStatus.APPL_AOK);
            assertThat(written.statusImage()).isEqualTo(FileStatus.toStatusImage(FileStatus.OK));
            assertThat(written.cicsResp()).hasValue(FileStatus.NORMAL);
            assertThat(written.cicsResp2()).isEqualTo(FileStatus.NO_REASON_CODE);
            assertThat(written.diagnostic()).isEmpty();

            WriteResult absent = WriteResult.notFound();
            assertThat(absent.isNotFound()).isTrue();
            assertThat(absent.applResult()).isEqualTo(CustomerRepository.APPL_RESULT_FATAL);
        }

        @Test
        @DisplayName("a rewrite cannot report an end of file")
        void aWriteRefusesTheEndOfFileStatus() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> WriteResult.of(FileStatus.END_OF_FILE))
                    .withMessageContaining("end of a dataset");
        }

        @Test
        @DisplayName("a successful read cannot be built without a record, nor a failure with one")
        void enforcesTheRecordInvariant() {
            assertThatNullPointerException().isThrownBy(() -> ReadResult.found(null));

            assertThatIllegalArgumentException().isThrownBy(() -> new ReadResult(FileStatus.OK, Outcome.OK,
                    Optional.empty(), Optional.empty(), CicsResponse.ofBatchStatus(FileStatus.OK)))
                    .withMessageContaining("carries the decoded record");

            assertThatIllegalArgumentException().isThrownBy(() -> new ReadResult(FileStatus.NOT_FOUND,
                    Outcome.NOT_FOUND, Optional.of(new CustomerRecord()), Optional.empty(),
                    CicsResponse.ofBatchStatus(FileStatus.NOT_FOUND)))
                    .withMessageContaining("carries no record");
        }

        @Test
        @DisplayName("a status and its classification must agree")
        void enforcesTheClassificationInvariant() {
            assertThatIllegalArgumentException().isThrownBy(() -> new ReadResult(FileStatus.NOT_FOUND,
                    Outcome.OK, Optional.of(new CustomerRecord()), Optional.empty(),
                    CicsResponse.ofBatchStatus(FileStatus.NOT_FOUND)))
                    .withMessageContaining("classifies as");

            assertThatIllegalArgumentException().isThrownBy(() -> new WriteResult(FileStatus.NOT_FOUND,
                    Outcome.OK, Optional.empty(), CicsResponse.ofBatchStatus(FileStatus.NOT_FOUND)))
                    .withMessageContaining("classifies as");
        }

        @ParameterizedTest
        @ValueSource(strings = {"0", "000"})
        @DisplayName("a status that is not exactly two characters is refused")
        void refusesAMalformedStatusWidth(String malformed) {
            assertThatIllegalArgumentException().isThrownBy(() -> ReadResult.of(malformed))
                    .withMessageContaining("exactly 2");
            assertThatIllegalArgumentException().isThrownBy(() -> WriteResult.of(malformed))
                    .withMessageContaining("exactly 2");
        }

        @Test
        @DisplayName("an absent status, outcome, record, diagnostic or response is refused")
        void refusesAbsentComponents() {
            assertThatNullPointerException().isThrownBy(() -> ReadResult.of(null));
            assertThatNullPointerException().isThrownBy(() -> WriteResult.of(null));

            assertThatNullPointerException().isThrownBy(() -> new ReadResult(FileStatus.NOT_FOUND, null,
                    Optional.empty(), Optional.empty(), CicsResponse.ofBatchStatus(FileStatus.NOT_FOUND)));
            assertThatNullPointerException().isThrownBy(() -> new ReadResult(FileStatus.NOT_FOUND,
                    Outcome.NOT_FOUND, null, Optional.empty(),
                    CicsResponse.ofBatchStatus(FileStatus.NOT_FOUND)));
            assertThatNullPointerException().isThrownBy(() -> new ReadResult(FileStatus.NOT_FOUND,
                    Outcome.NOT_FOUND, Optional.empty(), null,
                    CicsResponse.ofBatchStatus(FileStatus.NOT_FOUND)));
            assertThatNullPointerException().isThrownBy(() -> new ReadResult(FileStatus.NOT_FOUND,
                    Outcome.NOT_FOUND, Optional.empty(), Optional.empty(), null));

            assertThatNullPointerException().isThrownBy(() -> new WriteResult(FileStatus.NOT_FOUND, null,
                    Optional.empty(), CicsResponse.ofBatchStatus(FileStatus.NOT_FOUND)));
            assertThatNullPointerException().isThrownBy(() -> new WriteResult(FileStatus.NOT_FOUND,
                    Outcome.NOT_FOUND, null, CicsResponse.ofBatchStatus(FileStatus.NOT_FOUND)));
            assertThatNullPointerException().isThrownBy(() -> new WriteResult(FileStatus.NOT_FOUND,
                    Outcome.NOT_FOUND, Optional.empty(), null));
        }

        @Test
        @DisplayName("a factory taking a diagnostic refuses an absent one")
        void refusesAnAbsentDiagnostic() {
            assertThatNullPointerException().isThrownBy(() -> ReadResult.of(FileStatus.NOT_FOUND,
                    CicsResponse.ofBatchStatus(FileStatus.NOT_FOUND), null));
            assertThatNullPointerException().isThrownBy(() -> WriteResult.of(FileStatus.NOT_FOUND,
                    CicsResponse.ofBatchStatus(FileStatus.NOT_FOUND), null));
        }

        @Test
        @DisplayName("a carried CICS pair is reported verbatim rather than derived from the status")
        void carriesTheCicsPairVerbatim() {
            // The width check is the case that needs it: a permanent error to the batch guard chain and
            // LENGERR to the online one, and neither number is derivable from the other.
            ReadResult read = ReadResult.of(CustomerRepository.PERMANENT_ERROR_STATUS,
                    CicsResponse.of(FileStatus.LENGERR));
            assertThat(read.cicsResp()).hasValue(FileStatus.LENGERR);
            assertThat(read.cicsResp2()).isEqualTo(FileStatus.NO_REASON_CODE);

            WriteResult write = WriteResult.of(CustomerRepository.PERMANENT_ERROR_STATUS,
                    CicsResponse.reported(FileStatus.INVREQ, 42));
            assertThat(write.cicsResp()).hasValue(FileStatus.INVREQ);
            assertThat(write.cicsResp2()).isEqualTo(42);
        }

        @Test
        @DisplayName("a success status is refused by the general read factory, which carries no record")
        void refusesSuccessFromTheGeneralReadFactory() {
            assertThatIllegalArgumentException().isThrownBy(() -> ReadResult.of(FileStatus.OK));
        }
    }
}
