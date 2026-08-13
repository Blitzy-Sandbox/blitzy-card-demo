package com.vsergeychik.carddemo.customer;

import com.vsergeychik.carddemo.common.AbendException;
import com.vsergeychik.carddemo.common.CicsResponse;
import com.vsergeychik.carddemo.common.DatasetIntegrityException;
import com.vsergeychik.carddemo.common.FileStatus;
import com.vsergeychik.carddemo.common.FileStatus.Outcome;
import com.vsergeychik.carddemo.common.FixedWidthRecord.FieldSpan;
import com.vsergeychik.carddemo.common.FixedWidthRecord.PictureKind;
import com.vsergeychik.carddemo.common.RecordImageForm;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBinding;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBindings;
import com.vsergeychik.carddemo.customer.CustomerRepository.CustomerFile;
import com.vsergeychik.carddemo.customer.CustomerRepository.ReadResult;
import com.vsergeychik.carddemo.customer.CustomerRepository.Statements;
import com.vsergeychik.carddemo.customer.CustomerRepository.WriteResult;
import com.vsergeychik.carddemo.customer.model.CustomerRecord;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
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
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * Unit tests for {@link CustomerRepository}, the customer master dataset's only reader and only writer.
 */
@DisplayName("CustomerRepository - the customer master dataset over JDBC")
class CustomerRepositoryTest {
    private static final Charset ASCII = StandardCharsets.US_ASCII;

    private static final String TEST_DSNAME = "TEST.CUSTOMER.KSDS";

    private static final String OTHER_DSNAME = "TEST.OTHER.KSDS";

    private static final String RECORD_IMAGE_COLUMN = "REC";

    private static final String FIXTURE = "/fixtures/custdata.txt";

    private static final int FIXTURE_RECORDS = 50;

    private static final int FIVE_HUNDRED = 500;

    private static final int NINE = 9;

    private static final int FILLER_WIDTH = 168;

    private static final long ABSENT_CUST_ID = 999_999_999L;

    private String databaseNamePrefix;

    private int databaseOrdinal;

    private final List<DataSource> createdDatabases = new ArrayList<>();

    @AfterEach
    void disposeCreatedDatabases() {
        for (DataSource created : createdDatabases) {
            JdbcTemplate template = new JdbcTemplate(created);
            template.execute("DROP ALL OBJECTS");
            template.execute("SHUTDOWN");
        }
        createdDatabases.clear();
    }

    @BeforeEach
    void nameThisTestsDatabases(TestInfo testInfo) {
        String method = testInfo.getTestMethod().map(Method::getName).orElse("unnamed");
        int discriminator = testInfo.getDisplayName().hashCode() & Integer.MAX_VALUE;
        this.databaseNamePrefix = "custrepo_" + method + "_" + Integer.toString(discriminator, 36);
        this.databaseOrdinal = 0;
    }

    private static DatasetBindings bindings(String cicsDsname, String batchDsname, int recordLength,
            Integer keyLength) {
        DatasetBindings catalogue = new DatasetBindings();
        catalogue.put(CustomerRepository.CICS_FILE_NAME, new DatasetBinding(cicsDsname, "ksds", false,
                "FB", null, recordLength, "CVCUS01Y", keyLength, null, null, null));
        catalogue.put(CustomerRepository.BATCH_DD_NAME, new DatasetBinding(batchDsname, "ksds", false,
                "FB", null, recordLength, "CVCUS01Y", keyLength, null, null, null));
        return catalogue;
    }

    private static DatasetBindings validBindings() {
        return bindings(TEST_DSNAME, TEST_DSNAME, FIVE_HUNDRED, NINE);
    }

    private static CustomerRepository repository(JdbcTemplate template) {
        return new CustomerRepository(template, validBindings(), ASCII, RecordImageForm.CHARACTER);
    }

    private JdbcTemplate seeded(List<String> rows) {
        return seeded(rows, FIVE_HUNDRED);
    }

    private JdbcTemplate seeded(List<String> rows, int columnWidth) {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:" + databaseNamePrefix + "_" + (++databaseOrdinal)
                        + ";DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE", "sa", "");
        dataSource.setDriverClassName("org.h2.Driver");
        createdDatabases.add(dataSource);
        JdbcTemplate template = new JdbcTemplate(dataSource);
        template.execute("CREATE TABLE \"" + TEST_DSNAME + "\" (" + RECORD_IMAGE_COLUMN
                + " VARCHAR(" + columnWidth + "))");
        for (String row : rows) {
            template.update("INSERT INTO \"" + TEST_DSNAME + "\" VALUES (?)", row);
        }
        return template;
    }

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

    private static String keyImageOf(String row) {
        return row.substring(0, NINE);
    }

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

    private static <T> T withUnitOfWork(Supplier<T> action) {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            return action.get();
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

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
     * A template whose describe refuses its first {@code failuresBeforeSuccess} attempts and then answers,
     * and whose reads find nothing - so a caller can prove what a repository does and does not remember
     * about a describe that failed.
     *
     * @param describes counts every describe attempt, refused or answered
     * @param failuresBeforeSuccess how many leading describe attempts raise {@link SQLException}
     * @return the template
     * @throws SQLException never in practice; declared because the mocked JDBC methods declare it
     */
    private static JdbcTemplate describingWithFailures(AtomicInteger describes,
            int failuresBeforeSuccess) throws SQLException {
        DataSource dataSource = Mockito.mock(DataSource.class);
        Connection connection = Mockito.mock(Connection.class);
        Statement statement = Mockito.mock(Statement.class);
        ResultSet probeResultSet = Mockito.mock(ResultSet.class);
        ResultSetMetaData metaData = Mockito.mock(ResultSetMetaData.class);
        PreparedStatement preparedStatement = Mockito.mock(PreparedStatement.class);
        ResultSet emptyResultSet = Mockito.mock(ResultSet.class);
        Mockito.when(dataSource.getConnection()).thenReturn(connection);
        Mockito.when(connection.createStatement()).thenReturn(statement);
        Mockito.when(statement.executeQuery(Mockito.anyString())).thenAnswer(invocation -> {
            if (describes.incrementAndGet() <= failuresBeforeSuccess) {
                throw new SQLException("the customer master could not be described");
            }
            return probeResultSet;
        });
        Mockito.when(probeResultSet.getMetaData()).thenReturn(metaData);
        Mockito.when(metaData.getColumnCount()).thenReturn(1);
        Mockito.when(metaData.getColumnName(1)).thenReturn(RECORD_IMAGE_COLUMN);
        Mockito.when(connection.prepareStatement(Mockito.anyString())).thenReturn(preparedStatement);
        Mockito.when(preparedStatement.executeQuery()).thenReturn(emptyResultSet);
        Mockito.when(emptyResultSet.next()).thenReturn(false);
        return new JdbcTemplate(dataSource);
    }

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

    private static JdbcTemplate emptyReadThenRefusedProbe() throws SQLException {
        DataSource dataSource = Mockito.mock(DataSource.class);
        Connection connection = Mockito.mock(Connection.class);
        Statement statement = Mockito.mock(Statement.class);
        ResultSet describeResultSet = Mockito.mock(ResultSet.class);
        ResultSetMetaData metaData = Mockito.mock(ResultSetMetaData.class);
        Mockito.when(dataSource.getConnection()).thenReturn(connection);
        Mockito.when(connection.createStatement()).thenReturn(statement);
        Mockito.when(statement.executeQuery(Mockito.anyString())).thenReturn(describeResultSet);
        Mockito.when(describeResultSet.getMetaData()).thenReturn(metaData);
        Mockito.when(metaData.getColumnCount()).thenReturn(1);
        Mockito.when(metaData.getColumnName(1)).thenReturn(RECORD_IMAGE_COLUMN);

        PreparedStatement keyedRead = Mockito.mock(PreparedStatement.class);
        ResultSet noRows = Mockito.mock(ResultSet.class);
        Mockito.when(noRows.next()).thenReturn(false);
        Mockito.when(keyedRead.executeQuery()).thenReturn(noRows);
        Mockito.when(connection.prepareStatement(Mockito.anyString())).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            if (sql.endsWith("IS NULL")) {
                throw new SQLException("the unreadable-row probe is refused");
            }
            return keyedRead;
        });
        return new JdbcTemplate(dataSource);
    }

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
        Mockito.when(oneRow.next()).thenReturn(true, false);
        Mockito.when(preparedStatement.executeUpdate()).thenReturn(updateCount);
        return new JdbcTemplate(dataSource);
    }

    private static Set<String> declaredMethodNamesOf(Class<?> type) {
        Set<String> names = new LinkedHashSet<>();
        for (Method method : type.getDeclaredMethods()) {
            if (Modifier.isPublic(method.getModifiers()) && !method.isSynthetic()) {
                names.add(method.getName());
            }
        }
        return names;
    }

    private static JdbcTemplate countingOneThenRefusingTheUpdate() throws SQLException {
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
        Mockito.when(oneRow.next()).thenReturn(true, false);
        Mockito.when(preparedStatement.executeUpdate())
                .thenThrow(new SQLException("the update is refused"));
        return new JdbcTemplate(dataSource);
    }

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
        @DisplayName("holds no mutable state of its own: every field is final, bar the volatile, "
                + "immutable statement set")
        void holdsNoMutableState() {
            for (Field field : CustomerRepository.class.getDeclaredFields()) {
                if (Modifier.isFinal(field.getModifiers())) {
                    continue;
                }
                assertThat(Modifier.isVolatile(field.getModifiers()))
                        .as("field %s of CustomerRepository is not final, so it must at least be "
                                + "volatile", field.getName())
                        .isTrue();
                assertThat(field.getType())
                        .as("field %s of CustomerRepository is not final, so the value it publishes must "
                                + "be immutable", field.getName())
                        .isEqualTo(CustomerRepository.Statements.class);
            }
        }
    }

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
            assertThat(file.closeFile()).isEqualTo(FileStatus.OK);
            assertThat(file.closeApplResult()).isEqualTo(FileStatus.APPL_AOK);
        }

        @Test
        @DisplayName("closing a file whose open failed reports that open's own status, and keeps reporting it")
        void closingAFailedOpenReportsTheOpenStatus() {
            CustomerFile file = repository(unreachable()).openInput();

            assertThat(file.closeFile()).isEqualTo(CustomerRepository.PERMANENT_ERROR_STATUS);
            assertThat(file.closeFile()).isEqualTo(CustomerRepository.PERMANENT_ERROR_STATUS);
            assertThat(file.closeApplResult()).isEqualTo(CustomerRepository.APPL_RESULT_FATAL);
        }

        @Test
        @DisplayName("a failed close reports the same outcome on every later call, without probing again")
        void aFailedCloseKeepsReportingItsOutcome() throws SQLException {
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
            CustomerRepository repository = repository(seeded(fixtureRows()));

            CustomerFile file = repository.openInput();
            assertThat(file.openApplResult()).isEqualTo(FileStatus.APPL_AOK);
            assertThat(file.closeApplResult()).isEqualTo(FileStatus.APPL_AOK);
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
                assertThat(read).containsExactlyElementsOf(rows.stream().sorted().toList());

                ReadResult end = file.readNext();
                assertThat(end.isEndOfFile()).isTrue();
                assertThat(end.status()).isEqualTo(FileStatus.END_OF_FILE);
                assertThat(end.applResult()).isEqualTo(FileStatus.APPL_EOF);
                assertThat(end.customer()).isEmpty();
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
            assertThat(statements.selectNext()).contains("ORDER BY").contains("ASC").contains(" > ?")
                    .contains("IS NULL");
            assertThat(statements.selectFirst() + statements.selectNext())
                    .doesNotContainIgnoringCase("fetch first")
                    .doesNotContainIgnoringCase("limit")
                    .doesNotContainIgnoringCase("offset")
                    .doesNotContainIgnoringCase("rownum");
            assertThat(repository.columnProbeSql()).contains("1 = 0");
        }

        @Test
        @DisplayName("a read describes the relation once and every later read reuses what it found")
        void aReadDescribesTheRelationOnceAndLaterReadsReuseIt() {
            List<String> rows = fixtureRows();
            JdbcTemplate counting = Mockito.spy(seeded(rows));
            CustomerRepository repository = repository(counting);
            String key = keyImageOf(rows.get(0));

            assertThat(repository.readByKey(key).isFound()).isTrue();
            assertThat(repository.readByKey(Long.parseLong(key)).isFound()).isTrue();
            assertThat(repository.readByKey(ABSENT_CUST_ID).isNotFound()).isTrue();
            assertThat(withUnitOfWork(() -> repository.rewrite(rows.get(0).getBytes(ASCII)))
                    .isWritten()).isTrue();
            assertThat(withUnitOfWork(() ->
                    repository.rewriteHeld(rows.get(0), rows.get(0).getBytes(ASCII))).isWritten())
                    .isTrue();

            Mockito.verify(counting, Mockito.times(1))
                    .query(Mockito.eq(repository.columnProbeSql()),
                            Mockito.<ResultSetExtractor<String>>any());
        }

        @Test
        @DisplayName("every OPEN INPUT learns the file afresh, and the reads after it reuse what it found")
        void everyOpenLearnsTheFileAfresh() {
            List<String> rows = fixtureRows();
            JdbcTemplate counting = Mockito.spy(seeded(rows));
            CustomerRepository repository = repository(counting);
            String key = keyImageOf(rows.get(0));

            assertThat(repository.readByKey(key).isFound()).isTrue();
            try (CustomerFile file = repository.openInput()) {
                assertThat(file.openStatus()).isEqualTo(FileStatus.OK);
                assertThat(file.readNext().isFound()).isTrue();
            }
            assertThat(repository.readByKey(key).isFound()).isTrue();

            Mockito.verify(counting, Mockito.times(3))
                    .query(Mockito.eq(repository.columnProbeSql()),
                            Mockito.<ResultSetExtractor<String>>any());
        }

        @Test
        @DisplayName("a describe that fails is not remembered as an answer: the next read describes again")
        void aFailedDescribeIsNotRemembered() throws SQLException {
            AtomicInteger describes = new AtomicInteger();
            CustomerRepository repository = repository(describingWithFailures(describes, 1));

            ReadResult refused = repository.readByKey("000000001");
            ReadResult retried = repository.readByKey("000000001");

            assertThat(refused.status()).isEqualTo(CustomerRepository.PERMANENT_ERROR_STATUS);
            assertThat(retried.isNotFound()).isTrue();
            assertThat(describes.get())
                    .as("the failed describe left nothing behind, so the second read asked again")
                    .isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("The keyed read finds a record by CUST-ID, or reports '23'")
    class KeyedReadTests {
        @Test
        @DisplayName("finding DB-05: a present-but-unreadable row is not reported as an absent customer")
        void anUnreadableRowIsNotReportedAsAbsent() {
            List<String> rows = new ArrayList<>(fixtureRows());
            rows.add(null);
            CustomerRepository repository = repository(seeded(rows));

            ReadResult result = repository.readByKey("999999997");

            assertThat(result.isNotFound()).isFalse();
            assertThat(result.isOther()).isTrue();
            assertThat(result.customer()).isEmpty();
        }

        @Test
        @DisplayName("finding DB-05: a genuinely absent key still reports '23', proved rather than assumed")
        void aGenuinelyAbsentKeyIsStillNotFound() {
            CustomerRepository repository = repository(seeded(fixtureRows()));

            ReadResult result = repository.readByKey("999999997");

            assertThat(result.isNotFound()).isTrue();
            assertThat(result.status()).isEqualTo(FileStatus.NOT_FOUND);
        }

        @Test
        @DisplayName("finding DB-05: a read that found its record is untouched by an unreadable row")
        void aFoundRecordIsUnaffectedByAnUnreadableRowElsewhere() {
            List<String> rows = new ArrayList<>(fixtureRows());
            rows.add(null);
            String first = fixtureRows().get(0);

            ReadResult result = repository(seeded(rows)).readByKey(keyImageOf(first));

            assertThat(result.isFound()).isTrue();
            assertThat(result.customer().orElseThrow().recordImage(ASCII)).isEqualTo(first);
        }

        @Test
        @DisplayName("finding DB-05: a probe the backend refuses is reported, never assumed absent")
        void aRefusedProbeIsReportedRatherThanAssumedAbsent() throws SQLException {
            ReadResult result = repository(emptyReadThenRefusedProbe()).readByKey("000000001");

            assertThat(result.isNotFound())
                    .as("the probe established nothing, so the absence stays unproved")
                    .isFalse();
            assertThat(result.isOther()).isTrue();
        }

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
            ReadResult result = repository(seeded(fixtureRows())).readByKey(" ".repeat(NINE));

            assertThat(result.isNotFound()).isTrue();
        }

        @Test
        @DisplayName("LIKE metacharacters in a key image cannot widen the match")
        void escapesLikeMetacharacters() {
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

                String last = sorted.get(sorted.size() - 1);
                assertThat(file.readByKey(keyImageOf(last)).customer().orElseThrow()
                        .recordImage(ASCII)).isEqualTo(last);
                assertThat(file.readByKey(Long.parseLong(keyImageOf(last))).isFound()).isTrue();

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
        @DisplayName("is a distinct method from the plain keyed read, and returns the identical bytes")
        void isDistinctFromThePlainReadYetReturnsTheSameRecord() {
            List<String> rows = fixtureRows();
            String row = rows.get(0);
            CustomerRepository repository = repository(seeded(rows));

            CustomerRecord plainly = repository.readByKey(keyImageOf(row)).customer().orElseThrow();
            CustomerRecord locked = withUnitOfWork(() -> repository.readForUpdate(keyImageOf(row)))
                    .customer().orElseThrow();

            assertThat(locked.encode(ASCII)).isEqualTo(plainly.encode(ASCII));
            assertThat(locked.encode(ASCII)).hasSize(FIVE_HUNDRED);
            assertThat(locked).isEqualTo(plainly);
            assertThat(declaredMethodNamesOf(CustomerRepository.class))
                    .contains("readByKey", "readForUpdate");
        }

        @Test
        @DisplayName("issues FOR UPDATE, and the plain keyed read does not")
        void issuesForUpdateOnlyForTheLockingRead() throws SQLException {
            List<String> prepared = new ArrayList<>();
            CustomerRepository repository = repository(recordingPreparedStatements(prepared));

            repository.readByKey("000000001");
            assertThat(prepared).hasSize(2);
            assertThat(prepared.get(0)).doesNotContain("FOR UPDATE");
            assertThat(prepared.get(1)).endsWith("IS NULL").doesNotContain("FOR UPDATE");

            prepared.clear();
            withUnitOfWork(() -> repository.readForUpdate("000000001"));
            assertThat(prepared).hasSize(2);
            assertThat(prepared.get(0)).endsWith("FOR UPDATE");
            assertThat(prepared.get(1)).endsWith("IS NULL").doesNotContain("FOR UPDATE");
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
            List<String> methods = Arrays.stream(CustomerFile.class.getDeclaredMethods())
                    .map(java.lang.reflect.Method::getName)
                    .toList();

            assertThat(methods).doesNotContain("readForUpdate", "rewrite");
        }
    }

    @Nested
    @DisplayName("The rewrite replaces exactly one record, or reports why it did not")
    class RewriteTests {
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

            WriteResult result = withUnitOfWork(() -> repository.rewrite(changed.getBytes(ASCII)));

            assertThat(result.isWritten()).isTrue();
            assertThat(result.status()).isEqualTo(FileStatus.OK);
            assertThat(result.applResult()).isEqualTo(FileStatus.APPL_AOK);
            assertThat(result.diagnostic()).isEmpty();
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

            WriteResult result = withUnitOfWork(() -> repository.rewrite(record));

            assertThat(result.isWritten()).isTrue();
            CustomerRecord reread = repository.readByKey(keyImageOf(row)).customer().orElseThrow();
            assertThat(reread.getCustFicoCreditScore()).isEqualTo(777);
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

            WriteResult result = withUnitOfWork(() -> repository.rewrite(absent.getBytes(ASCII)));

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
            List<String> duplicated = new ArrayList<>(rows);
            duplicated.add(row);
            JdbcTemplate template = seeded(duplicated);
            CustomerRepository repository = repository(template);
            String changed = withChangedScore(row);

            WriteResult result = withUnitOfWork(() -> repository.rewrite(changed.getBytes(ASCII)));

            assertThat(result.isOther()).isTrue();
            assertThat(result.status()).isEqualTo(CustomerRepository.PERMANENT_ERROR_STATUS);
            assertThat(result.cicsResp()).hasValue(FileStatus.INVREQ);
            assertThat(template.queryForObject("SELECT COUNT(*) FROM \"" + TEST_DSNAME
                    + "\" WHERE " + RECORD_IMAGE_COLUMN + " = ?", Integer.class, row)).isEqualTo(2);
        }

        @Test
        @DisplayName("a fan-out discovered only after the write refuses the unit of work")
        void refusesTheUnitOfWorkOnAPostWriteFanOut() throws SQLException {
            CustomerRepository repository = repository(countingOneThenReportingUpdateCount(2));
            byte[] image = ("000000001" + " ".repeat(FIVE_HUNDRED - NINE)).getBytes(ASCII);

            assertThatExceptionOfType(DatasetIntegrityException.class)
                    .isThrownBy(() -> withUnitOfWork(() -> repository.rewrite(image)));
        }

        @Test
        @DisplayName("outside a unit of work the rewrite is refused rather than reported as written")
        void outsideAUnitOfWorkTheRewriteIsRefused() {
            List<String> rows = fixtureRows();
            JdbcTemplate template = seeded(rows);
            CustomerRepository repository = repository(template);
            byte[] image = withChangedScore(rows.get(0)).getBytes(ASCII);

            assertThatIllegalStateException().isThrownBy(() -> repository.rewrite(image))
                    .withMessageContaining("no transaction is open on this thread")
                    .withMessageContaining("changes stored records");

            assertThat(template.queryForObject("SELECT COUNT(*) FROM \"" + TEST_DSNAME
                    + "\" WHERE " + RECORD_IMAGE_COLUMN + " = ?", Integer.class, rows.get(0)))
                    .as("nothing may have been attempted")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("a row lost between the count and the write is the invalid-key condition")
        void aRowLostBeforeTheWriteIsNotFound() throws SQLException {
            CustomerRepository repository = repository(countingOneThenReportingUpdateCount(0));
            byte[] image = ("000000001" + " ".repeat(FIVE_HUNDRED - NINE)).getBytes(ASCII);

            assertThat(withUnitOfWork(() -> repository.rewrite(image)).isNotFound()).isTrue();
        }

        @Test
        @DisplayName("an unreachable dataset reports a permanent error rather than throwing")
        void anUnreachableDatasetReportsAPermanentError() {
            byte[] image = ("000000001" + " ".repeat(FIVE_HUNDRED - NINE)).getBytes(ASCII);
            CustomerRepository repository = repository(unreachable());

            WriteResult result = withUnitOfWork(() -> repository.rewrite(image));

            assertThat(result.isOther()).isTrue();
            assertThat(result.diagnostic()).isPresent();
        }

        @Test
        @DisplayName("a refused statement is reported at the count and at the write")
        void aRefusedStatementIsReported() throws SQLException {
            byte[] image = ("000000001" + " ".repeat(FIVE_HUNDRED - NINE)).getBytes(ASCII);
            CustomerRepository repository = repository(describingThenRefusing());

            WriteResult result = withUnitOfWork(() -> repository.rewrite(image));

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

            assertThat(withUnitOfWork(() -> repository.rewrite(image)).isWritten()).isTrue();
            Arrays.fill(image, (byte) 'Z');
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

        @Test
        @DisplayName("inside a unit of work the pre-write count takes the same row lock the UPDATE will")
        void countsUnderARowLockInsideAUnitOfWork() {
            List<String> rows = fixtureRows();
            String row = rows.get(0);
            String changed = withChangedScore(row);
            JdbcTemplate template = seeded(rows);
            CustomerRepository repository = repository(template);

            WriteResult result =
                    withUnitOfWork(() -> repository.rewrite(changed.getBytes(ASCII)));

            assertThat(result.isWritten()).isTrue();
            assertThat(result.status()).isEqualTo(FileStatus.OK);
            assertThat(result.applResult()).isEqualTo(FileStatus.APPL_AOK);
            assertThat(template.queryForObject("SELECT " + RECORD_IMAGE_COLUMN + " FROM \""
                    + TEST_DSNAME + "\" WHERE " + RECORD_IMAGE_COLUMN + " LIKE ?", String.class,
                    keyImageOf(row) + "%")).isEqualTo(changed);
        }

        @Test
        @DisplayName("a post-write fan-out under a row lock refuses the unit of work and says so")
        void refusesTheUnitOfWorkOnAPostWriteFanOutUnderALock() throws SQLException {
            CustomerRepository repository = repository(countingOneThenReportingUpdateCount(2));
            byte[] image = ("000000001" + " ".repeat(FIVE_HUNDRED - NINE)).getBytes(ASCII);

            DatasetIntegrityException refusal = null;
            TransactionSynchronizationManager.setActualTransactionActive(true);
            try {
                repository.rewrite(image);
            } catch (DatasetIntegrityException thrown) {
                refusal = thrown;
            } finally {
                TransactionSynchronizationManager.setActualTransactionActive(false);
            }

            assertThat(refusal).isNotNull();
            assertThat(refusal).hasMessageContaining("row lock")
                    .hasMessageContaining("2 rows were replaced");
        }

        @Test
        @DisplayName("a refused UPDATE is reported as a permanent error, not thrown")
        void aRefusedUpdateIsReported() throws SQLException {
            CustomerRepository repository = repository(countingOneThenRefusingTheUpdate());
            byte[] image = ("000000001" + " ".repeat(FIVE_HUNDRED - NINE)).getBytes(ASCII);

            WriteResult result = withUnitOfWork(() -> repository.rewrite(image));

            assertThat(result.isOther()).isTrue();
            assertThat(result.status()).isEqualTo(CustomerRepository.PERMANENT_ERROR_STATUS);
            assertThat(result.applResult()).isEqualTo(CustomerRepository.APPL_RESULT_FATAL);
            assertThat(result.diagnostic()).isPresent();
        }
    }

    // =============================================================================================
    // The held rewrite - the same app/cbl/COACTUPC.cbl:L4085-L4091 command, addressed by the row the
    // READ ... UPDATE at :L3921-L3930 holds rather than by the key inside the record area.
    //
    // Why this is a distinct operation and not a duplicate of the keyed one: the two keys have
    // different provenance. The lock is taken with CDEMO-CUST-ID from the communication area (:3919)
    // and the staged image carries ACUP-NEW-CUST-ID (:4009), which :1234-1240 moved out of the
    // screen's ACSTNUMI. On a 3270 those cannot differ - :3531 moves DFHBMPRF onto ACSTNUMA, and
    // :1222 records the identifier as "actually not editable" - but a REST payload carries no such
    // guarantee, and a rewrite addressed by the payload's key would let one customer be locked and
    // another overwritten.
    // =============================================================================================

    @Nested
    @DisplayName("The held rewrite replaces the row that was read, or reports why it did not")
    class HeldRewriteTests {

        /**
         * A stored image with its FICO score span overwritten, so a rewrite is observable.
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

        /**
         * The number of rows holding a given image.
         *
         * @param template the seeded template
         * @param image    the image to count
         * @return the count
         */
        private int rowsHolding(JdbcTemplate template, String image) {
            Integer counted = template.queryForObject("SELECT COUNT(*) FROM \"" + TEST_DSNAME
                    + "\" WHERE " + RECORD_IMAGE_COLUMN + " = ?", Integer.class, image);
            return counted == null ? -1 : counted;
        }

        @Test
        @DisplayName("rewrites the held row from a raw image and reports '00'")
        void rewritesTheHeldRowFromARawImage() {
            List<String> rows = fixtureRows();
            String held = rows.get(0);
            String changed = withChangedScore(held);
            JdbcTemplate template = seeded(rows);
            CustomerRepository repository = repository(template);

            WriteResult result = withUnitOfWork(() -> {
                String locked = repository.readForUpdate(keyImageOf(held)).requireStoredImage();
                return repository.rewriteHeld(locked, changed.getBytes(ASCII));
            });

            assertThat(result.isWritten()).isTrue();
            assertThat(result.status()).isEqualTo(FileStatus.OK);
            assertThat(result.applResult()).isEqualTo(FileStatus.APPL_AOK);
            assertThat(result.diagnostic()).isEmpty();
            // Byte-identical to what was handed in, all 500 bytes of it, FILLER included.
            assertThat(repository.readByKey(keyImageOf(held)).customer().orElseThrow()
                    .recordImage(ASCII)).isEqualTo(changed).hasSize(FIVE_HUNDRED);
        }

        @Test
        @DisplayName("rewrites the held row from the record model and reports '00'")
        void rewritesTheHeldRowFromTheRecordModel() {
            List<String> rows = fixtureRows();
            String held = rows.get(0);
            JdbcTemplate template = seeded(rows);
            CustomerRepository repository = repository(template);

            WriteResult result = withUnitOfWork(() -> {
                CustomerRepository.ReadResult locked = repository.readForUpdate(keyImageOf(held));
                CustomerRecord record = locked.customer().orElseThrow();
                record.setCustFicoCreditScore(777);
                return repository.rewriteHeld(locked.requireStoredImage(), record);
            });

            assertThat(result.isWritten()).isTrue();
            assertThat(repository.readByKey(keyImageOf(held)).customer().orElseThrow()
                    .getCustFicoCreditScore()).isEqualTo(777);
        }

        @Test
        @DisplayName("a staged image naming another customer is refused with INVREQ and writes NOTHING")
        void refusesAnImageWhoseKeyIsNotTheHeldRows() {
            // The tampering regression. Two rows, two customers: the lock is taken on the first and the
            // staged image names the second, which is what a crafted payload does when it overwrites the
            // protected ACSTNUM. A rewrite addressed by the staged key would replace the SECOND row while
            // the FIRST was the one locked - lock A, overwrite B - so this asserts both halves: the
            // refusal, and that neither row moved.
            List<String> rows = fixtureRows();
            String heldRow = rows.get(0);
            String otherRow = rows.get(1);
            JdbcTemplate template = seeded(new ArrayList<>(List.of(heldRow, otherRow)));
            CustomerRepository repository = repository(template);
            String impostor = withChangedScore(otherRow);

            WriteResult result = withUnitOfWork(() -> {
                String locked = repository.readForUpdate(keyImageOf(heldRow)).requireStoredImage();
                return repository.rewriteHeld(locked, impostor.getBytes(ASCII));
            });

            assertThat(result.isWritten()).isFalse();
            assertThat(result.isOther()).isTrue();
            assertThat(result.status()).isEqualTo(CustomerRepository.PERMANENT_ERROR_STATUS);
            assertThat(result.cicsResp()).hasValue(FileStatus.INVREQ);
            // Neither row was touched, and the impostor image is nowhere in the relation.
            assertThat(rowsHolding(template, heldRow)).isEqualTo(1);
            assertThat(rowsHolding(template, otherRow)).isEqualTo(1);
            assertThat(rowsHolding(template, impostor)).isZero();
        }

        @Test
        @DisplayName("the held row's own key still rewrites, so the check is on identity and not on change")
        void acceptsAnImageThatKeepsTheHeldKey() {
            // The mirror of the refusal: every field but the key may change, which is what an account
            // update does - name, address, phone, SSN and score all move and CUST-ID does not.
            List<String> rows = fixtureRows();
            String held = rows.get(0);
            JdbcTemplate template = seeded(rows);
            CustomerRepository repository = repository(template);
            String changed = keyImageOf(held) + "Z".repeat(FIVE_HUNDRED - NINE);

            WriteResult result = withUnitOfWork(() -> {
                String locked = repository.readForUpdate(keyImageOf(held)).requireStoredImage();
                return repository.rewriteHeld(locked, changed.getBytes(ASCII));
            });

            assertThat(result.isWritten()).isTrue();
            assertThat(rowsHolding(template, changed)).isEqualTo(1);
        }

        @Test
        @DisplayName("a held row that is no longer there reports '23' and writes no row")
        void aHeldRowThatIsGoneIsNotFound() {
            // UPDATEMODEL(LOCKING) is there to prevent this, but a deployment whose backend does not
            // honour FOR UPDATE would allow it, and the honest answer is the invalid-key condition rather
            // than resurrecting the row from the image the reader still holds.
            List<String> rows = fixtureRows();
            String held = rows.get(0);
            JdbcTemplate template = seeded(rows);
            CustomerRepository repository = repository(template);
            template.update("DELETE FROM \"" + TEST_DSNAME + "\" WHERE " + RECORD_IMAGE_COLUMN + " = ?",
                    held);

            WriteResult result = withUnitOfWork(
                    () -> repository.rewriteHeld(held, withChangedScore(held).getBytes(ASCII)));

            assertThat(result.isNotFound()).isTrue();
            assertThat(result.status()).isEqualTo(FileStatus.NOT_FOUND);
            assertThat(result.applResult()).isEqualTo(CustomerRepository.APPL_RESULT_FATAL);
            assertThat(rowsHolding(template, withChangedScore(held))).isZero();
        }

        @Test
        @DisplayName("a held image selecting two rows is refused BEFORE anything is written")
        void refusesAFanOutBeforeWriting() {
            List<String> rows = fixtureRows();
            String held = rows.get(0);
            List<String> duplicated = new ArrayList<>(rows);
            duplicated.add(held);
            JdbcTemplate template = seeded(duplicated);
            CustomerRepository repository = repository(template);

            WriteResult result = withUnitOfWork(
                    () -> repository.rewriteHeld(held, withChangedScore(held).getBytes(ASCII)));

            assertThat(result.isOther()).isTrue();
            assertThat(result.status()).isEqualTo(CustomerRepository.PERMANENT_ERROR_STATUS);
            assertThat(result.cicsResp()).hasValue(FileStatus.INVREQ);
            // Nothing was written: both rows still hold the original image.
            assertThat(rowsHolding(template, held)).isEqualTo(2);
        }

        @Test
        @DisplayName("outside a unit of work the held rewrite is refused rather than reported as written")
        void outsideAUnitOfWorkTheHeldRewriteIsRefused() {
            List<String> rows = fixtureRows();
            String held = rows.get(0);
            JdbcTemplate template = seeded(rows);
            CustomerRepository repository = repository(template);
            byte[] image = withChangedScore(held).getBytes(ASCII);

            assertThatIllegalStateException().isThrownBy(() -> repository.rewriteHeld(held, image))
                    .withMessageContaining("no transaction is open on this thread");

            assertThat(rowsHolding(template, held)).as("nothing may have been attempted").isEqualTo(1);
        }

        @ParameterizedTest
        @ValueSource(ints = {0, FIVE_HUNDRED - 1, FIVE_HUNDRED + 1})
        @DisplayName("a held image that is not 500 characters is refused, never padded or truncated")
        void refusesAHeldImageOfTheWrongWidth(int width) {
            CustomerRepository repository = repository(seeded(fixtureRows()));
            byte[] image = new byte[FIVE_HUNDRED];
            Arrays.fill(image, (byte) ' ');
            String malformed = " ".repeat(width);

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> repository.rewriteHeld(malformed, image))
                    .withMessageContaining("RECLN 500")
                    .withMessageContaining("requireStoredImage");
        }

        @Test
        @DisplayName("an absent held image, image or record is refused")
        void refusesAnAbsentOperand() {
            CustomerRepository repository = repository(seeded(fixtureRows()));
            String held = fixtureRows().get(0);
            byte[] image = new byte[FIVE_HUNDRED];
            Arrays.fill(image, (byte) ' ');

            assertThatNullPointerException().isThrownBy(() -> repository.rewriteHeld(null, image))
                    .withMessageContaining("no RIDFLD");
            assertThatNullPointerException()
                    .isThrownBy(() -> repository.rewriteHeld(held, (byte[]) null));
            assertThatNullPointerException()
                    .isThrownBy(() -> repository.rewriteHeld(held, (CustomerRecord) null));
        }

        @Test
        @DisplayName("a row lost between the count and the write is the invalid-key condition")
        void aRowLostBeforeTheWriteIsNotFound() throws SQLException {
            CustomerRepository repository = repository(countingOneThenReportingUpdateCount(0));
            String held = "000000001" + " ".repeat(FIVE_HUNDRED - NINE);

            assertThat(withUnitOfWork(() -> repository.rewriteHeld(held, held.getBytes(ASCII)))
                    .isNotFound()).isTrue();
        }

        @Test
        @DisplayName("a fan-out discovered only after the write refuses the unit of work")
        void refusesTheUnitOfWorkOnAPostWriteFanOut() throws SQLException {
            CustomerRepository repository = repository(countingOneThenReportingUpdateCount(2));
            String held = "000000001" + " ".repeat(FIVE_HUNDRED - NINE);

            assertThatExceptionOfType(DatasetIntegrityException.class)
                    .isThrownBy(() -> withUnitOfWork(() -> repository.rewriteHeld(held,
                            held.getBytes(ASCII))))
                    .withMessageContaining("held record")
                    .withMessageContaining("2 rows were replaced");
        }

        @Test
        @DisplayName("an unreachable dataset, a refused probe and a refused UPDATE all report WHEN OTHER")
        void refusalsAreReportedRatherThanThrown() throws SQLException {
            String held = "000000001" + " ".repeat(FIVE_HUNDRED - NINE);
            byte[] image = held.getBytes(ASCII);

            CustomerRepository unreachableRepository = repository(unreachable());
            CustomerRepository refusingProbe = repository(describingThenRefusing());
            CustomerRepository refusingUpdate = repository(countingOneThenRefusingTheUpdate());

            WriteResult unreachableDataset =
                    withUnitOfWork(() -> unreachableRepository.rewriteHeld(held, image));
            WriteResult refusedProbe = withUnitOfWork(() -> refusingProbe.rewriteHeld(held, image));
            WriteResult refusedUpdate = withUnitOfWork(() -> refusingUpdate.rewriteHeld(held, image));

            assertThat(unreachableDataset.isOther()).isTrue();
            assertThat(unreachableDataset.diagnostic()).isPresent();
            assertThat(refusedProbe.isOther()).isTrue();
            assertThat(refusedProbe.diagnostic()).isPresent();
            assertThat(refusedUpdate.isOther()).isTrue();
            assertThat(refusedUpdate.status()).isEqualTo(CustomerRepository.PERMANENT_ERROR_STATUS);
            assertThat(refusedUpdate.diagnostic()).isPresent();
        }

        @Test
        @DisplayName("the two held statements address a whole row, carry no LIKE and introduce no schema")
        void theHeldStatementsIntroduceNoSchema() throws SQLException {
            Statements statements = repository(describing(1, RECORD_IMAGE_COLUMN)).resolveStatements();

            assertThat(statements.selectByImageForUpdate())
                    .startsWith("SELECT * FROM ")
                    .endsWith(" FOR UPDATE")
                    .contains(" = ?")
                    .doesNotContain("LIKE");
            assertThat(statements.rewriteByImage())
                    .startsWith("UPDATE ")
                    .contains(" SET ")
                    .contains(" = ?")
                    .doesNotContain("LIKE")
                    .doesNotContainIgnoringCase("create")
                    .doesNotContainIgnoringCase("alter")
                    .doesNotContainIgnoringCase("drop")
                    .doesNotContainIgnoringCase("version");
        }
    }

    // =============================================================================================
    // Raw record image fidelity. DISPLAY CUSTOMER-RECORD (app/cbl/CBCUS01C.cbl:78 and :96) writes the
    // whole 500-byte FD record area, and CVCUS01Y ends in FILLER X(168) that no field of the record
    // covers. Every record-bearing arm therefore carries the row's own bytes, because reconstructing an
    // image from the eighteen decoded fields allocates a fresh area and writes spaces across that span
    // whatever the row held. Gates G19 and G21, and AAP R5.
    // =============================================================================================

    @Nested
    @DisplayName("Raw record image fidelity - the FILLER X(168) of CVCUS01Y")
    class RawRecordImageFidelityTests {
        @Test
        @DisplayName("a browse step hands back the row's own bytes, FILLER included")
        void aBrowseStepRetainsTheRowsBytes() {
            String dirty = dirtyRow();

            try (CustomerFile file = repository(seeded(List.of(dirty))).openInput()) {
                ReadResult result = file.readNext();

                assertThat(result.isFound()).isTrue();
                assertThat(result.requireStoredImage())
                        .as("the record area DISPLAY writes at :96 and again at :78 is the row's own")
                        .hasSize(FIVE_HUNDRED)
                        .isEqualTo(dirty);
            }
        }

        @Test
        @DisplayName("a keyed read hands back the row's own bytes, FILLER included")
        void aKeyedReadRetainsTheRowsBytes() {
            String dirty = dirtyRow();

            ReadResult result = repository(seeded(List.of(dirty))).readByKey(keyImageOf(dirty));

            assertThat(result.isFound()).isTrue();
            assertThat(result.requireStoredImage()).isEqualTo(dirty);
        }

        @Test
        @DisplayName("re-encoding the decoded record would have lost the FILLER, which is the finding")
        void reEncodingTheDecodedRecordWouldHaveLostIt() {
            String dirty = dirtyRow();

            ReadResult result = repository(seeded(List.of(dirty))).readByKey(keyImageOf(dirty));
            CustomerRecord decoded = result.customer().orElseThrow();

            assertThat(decoded.recordImage(ASCII))
                    .as("a fresh record area blanks FILLER X(168), so this is not what CBCUS01C writes")
                    .hasSize(FIVE_HUNDRED)
                    .isNotEqualTo(dirty)
                    .endsWith(" ".repeat(FILLER_WIDTH));
            assertThat(decoded.recordImage(ASCII).substring(0, FIVE_HUNDRED - FILLER_WIDTH))
                    .as("every declared span is unaffected; the divergence is confined to FILLER")
                    .isEqualTo(dirty.substring(0, FIVE_HUNDRED - FILLER_WIDTH));
        }

        @Test
        @DisplayName("the update path still starts from a fresh, space-filled area")
        void theUpdatePathStillStartsFromSpaces() {
            assertThat(new CustomerRecord().recordImage(ASCII))
                    .hasSize(FIVE_HUNDRED)
                    .endsWith(" ".repeat(FILLER_WIDTH));
        }

        private String dirtyRow() {
            String clean = fixtureRows().stream().sorted().findFirst().orElseThrow();
            String dirty = clean.substring(0, FIVE_HUNDRED - FILLER_WIDTH) + "*".repeat(FILLER_WIDTH);
            assertThat(dirty).hasSize(FIVE_HUNDRED).isNotEqualTo(clean);
            return dirty;
        }
    }

    @Nested
    @DisplayName("The outcomes carry every status the COBOL guard chain distinguishes")
    class OutcomeTests {
        @Test
        @DisplayName("a read reports '00' with a record, and the APPL-RESULT the COBOL moves")
        void aSuccessfulReadCarriesTheRecord() {
            ReadResult result = customerFound(new CustomerRecord());

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
            assertThatNullPointerException().isThrownBy(() -> customerFound(null));

            assertThatIllegalArgumentException().isThrownBy(() -> new ReadResult(FileStatus.OK, Outcome.OK,
                    Optional.empty(), Optional.empty(), Optional.empty(),
                    CicsResponse.ofBatchStatus(FileStatus.OK)))
                    .withMessageContaining("carries the decoded record");

            assertThatIllegalArgumentException().isThrownBy(() -> new ReadResult(FileStatus.NOT_FOUND,
                    Outcome.NOT_FOUND, Optional.of(new CustomerRecord()), Optional.empty(),
                    Optional.empty(), CicsResponse.ofBatchStatus(FileStatus.NOT_FOUND)))
                    .withMessageContaining("carries no record");
        }

        @Test
        @DisplayName("the stored image travels with the record and never without it")
        void enforcesTheStoredImageInvariant() {
            CustomerRecord record = new CustomerRecord();
            String image = " ".repeat(CustomerRecord.RECORD_LENGTH);

            assertThatIllegalArgumentException().isThrownBy(() -> new ReadResult(FileStatus.OK, Outcome.OK,
                    Optional.of(record), Optional.empty(), Optional.empty(),
                    CicsResponse.ofBatchStatus(FileStatus.OK)))
                    .withMessageContaining("carries the stored image");

            assertThatIllegalArgumentException().isThrownBy(() -> new ReadResult(FileStatus.NOT_FOUND,
                    Outcome.NOT_FOUND, Optional.empty(), Optional.of(image), Optional.empty(),
                    CicsResponse.ofBatchStatus(FileStatus.NOT_FOUND)))
                    .withMessageContaining("carries no stored image");

            assertThatIllegalArgumentException().isThrownBy(() -> new ReadResult(FileStatus.OK, Outcome.OK,
                    Optional.of(record), Optional.of(image.substring(1)), Optional.empty(),
                    CicsResponse.ofBatchStatus(FileStatus.OK)))
                    .withMessageContaining("is 499");
        }

        @Test
        @DisplayName("a status and its classification must agree")
        void enforcesTheClassificationInvariant() {
            assertThatIllegalArgumentException().isThrownBy(() -> new ReadResult(FileStatus.NOT_FOUND,
                    Outcome.OK, Optional.of(new CustomerRecord()), Optional.empty(), Optional.empty(),
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
        @DisplayName("an absent status, outcome, record, stored image, diagnostic or response is refused")
        void refusesAbsentComponents() {
            assertThatNullPointerException().isThrownBy(() -> ReadResult.of(null));
            assertThatNullPointerException().isThrownBy(() -> WriteResult.of(null));

            assertThatNullPointerException().isThrownBy(() -> new ReadResult(FileStatus.NOT_FOUND, null,
                    Optional.empty(), Optional.empty(), Optional.empty(),
                    CicsResponse.ofBatchStatus(FileStatus.NOT_FOUND)));
            assertThatNullPointerException().isThrownBy(() -> new ReadResult(FileStatus.NOT_FOUND,
                    Outcome.NOT_FOUND, null, Optional.empty(), Optional.empty(),
                    CicsResponse.ofBatchStatus(FileStatus.NOT_FOUND)));
            assertThatNullPointerException().isThrownBy(() -> new ReadResult(FileStatus.NOT_FOUND,
                    Outcome.NOT_FOUND, Optional.empty(), null, Optional.empty(),
                    CicsResponse.ofBatchStatus(FileStatus.NOT_FOUND)));
            assertThatNullPointerException().isThrownBy(() -> new ReadResult(FileStatus.NOT_FOUND,
                    Outcome.NOT_FOUND, Optional.empty(), Optional.empty(), null,
                    CicsResponse.ofBatchStatus(FileStatus.NOT_FOUND)));
            assertThatNullPointerException().isThrownBy(() -> new ReadResult(FileStatus.NOT_FOUND,
                    Outcome.NOT_FOUND, Optional.empty(), Optional.empty(), Optional.empty(), null));

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

    @Nested
    @DisplayName("The record is 500 bytes of nineteen declared spans, and survives a round trip")
    class RecordGeometryTests {
        /**
         * One row of the copybook, transcribed by hand: the item name, its 0-based offset and its width.
         *
         * @param name the copybook item name verbatim, or {@code FILLER} for the reserved span
         * @param offset the 0-based byte offset of the span within the record
         * @param length the declared width of the span in bytes
         */
        private record Span(String name, int offset, int length) {
        }

        private List<Span> copybookGeometry() {
            return List.of(
                    new Span("CUST-ID", 0, 9),
                    new Span("CUST-FIRST-NAME", 9, 25),
                    new Span("CUST-MIDDLE-NAME", 34, 25),
                    new Span("CUST-LAST-NAME", 59, 25),
                    new Span("CUST-ADDR-LINE-1", 84, 50),
                    new Span("CUST-ADDR-LINE-2", 134, 50),
                    new Span("CUST-ADDR-LINE-3", 184, 50),
                    new Span("CUST-ADDR-STATE-CD", 234, 2),
                    new Span("CUST-ADDR-COUNTRY-CD", 236, 3),
                    new Span("CUST-ADDR-ZIP", 239, 10),
                    new Span("CUST-PHONE-NUM-1", 249, 15),
                    new Span("CUST-PHONE-NUM-2", 264, 15),
                    new Span("CUST-SSN", 279, 9),
                    new Span("CUST-GOVT-ISSUED-ID", 288, 20),
                    new Span("CUST-DOB-YYYY-MM-DD", 308, 10),
                    new Span("CUST-EFT-ACCOUNT-ID", 318, 10),
                    new Span("CUST-PRI-CARD-HOLDER-IND", 328, 1),
                    new Span("CUST-FICO-CREDIT-SCORE", 329, 3),
                    new Span("FILLER", 332, 168));
        }

        @Test
        @DisplayName("the nineteen transcribed spans sum to exactly 500 bytes")
        void theTranscribedSpansSumToFiveHundred() {
            List<Span> geometry = copybookGeometry();

            assertThat(geometry).hasSize(19);
            int total = 0;
            for (Span span : geometry) {
                total += span.length();
            }
            assertThat(total).isEqualTo(FIVE_HUNDRED);
            assertThat(CustomerRecord.RECORD_LENGTH).isEqualTo(FIVE_HUNDRED);
            assertThat(CustomerRepository.RECORD_LENGTH).isEqualTo(FIVE_HUNDRED);
        }

        @Test
        @DisplayName("the transcribed spans are contiguous from byte 0 with no gap and no overlap")
        void theTranscribedSpansAreContiguous() {
            int cursor = 0;
            for (Span span : copybookGeometry()) {
                assertThat(span.offset())
                        .as("%s must begin where the preceding span ends", span.name())
                        .isEqualTo(cursor);
                cursor = span.offset() + span.length();
            }
            assertThat(cursor).isEqualTo(FIVE_HUNDRED);
        }

        @Test
        @DisplayName("the model declares exactly those nineteen spans, in that order, at those offsets")
        void theModelMatchesTheTranscribedGeometry() {
            List<FieldSpan> declared = CustomerRecord.LAYOUT.spans();
            List<Span> expected = copybookGeometry();

            assertThat(CustomerRecord.LAYOUT.recordLength()).isEqualTo(FIVE_HUNDRED);
            assertThat(declared).hasSameSizeAs(expected);
            for (int index = 0; index < expected.size(); index++) {
                Span span = expected.get(index);
                FieldSpan actual = declared.get(index);
                assertThat(actual.name())
                        .as("span %d must be %s", index, span.name())
                        .isEqualTo(span.name());
                assertThat(actual.offset())
                        .as("%s must be at offset %d", span.name(), span.offset())
                        .isEqualTo(span.offset());
                assertThat(actual.length())
                        .as("%s must be %d byte(s) wide", span.name(), span.length())
                        .isEqualTo(span.length());
                assertThat(actual.redefinition())
                        .as("%s is not a REDEFINES overlay", span.name())
                        .isFalse();
            }
            assertThat(CustomerRecord.LAYOUT.redefinitions()).isEmpty();
            assertThat(CustomerRecord.LAYOUT.storageSpans()).hasSize(19);
        }

        @Test
        @DisplayName("each named span is reachable as a constant carrying that same offset and width")
        void eachNamedSpanIsExposedAtItsTranscribedOffset() {
            assertSpan(CustomerRecord.CUST_ID, "CUST-ID", 0, 9, PictureKind.UNSIGNED_NUMERIC);
            assertSpan(CustomerRecord.CUST_FIRST_NAME, "CUST-FIRST-NAME", 9, 25,
                    PictureKind.ALPHANUMERIC);
            assertSpan(CustomerRecord.CUST_MIDDLE_NAME, "CUST-MIDDLE-NAME", 34, 25,
                    PictureKind.ALPHANUMERIC);
            assertSpan(CustomerRecord.CUST_LAST_NAME, "CUST-LAST-NAME", 59, 25,
                    PictureKind.ALPHANUMERIC);
            assertSpan(CustomerRecord.CUST_ADDR_LINE_1, "CUST-ADDR-LINE-1", 84, 50,
                    PictureKind.ALPHANUMERIC);
            assertSpan(CustomerRecord.CUST_ADDR_LINE_2, "CUST-ADDR-LINE-2", 134, 50,
                    PictureKind.ALPHANUMERIC);
            assertSpan(CustomerRecord.CUST_ADDR_LINE_3, "CUST-ADDR-LINE-3", 184, 50,
                    PictureKind.ALPHANUMERIC);
            assertSpan(CustomerRecord.CUST_ADDR_STATE_CD, "CUST-ADDR-STATE-CD", 234, 2,
                    PictureKind.ALPHANUMERIC);
            assertSpan(CustomerRecord.CUST_ADDR_COUNTRY_CD, "CUST-ADDR-COUNTRY-CD", 236, 3,
                    PictureKind.ALPHANUMERIC);
            assertSpan(CustomerRecord.CUST_ADDR_ZIP, "CUST-ADDR-ZIP", 239, 10,
                    PictureKind.ALPHANUMERIC);
            assertSpan(CustomerRecord.CUST_PHONE_NUM_1, "CUST-PHONE-NUM-1", 249, 15,
                    PictureKind.ALPHANUMERIC);
            assertSpan(CustomerRecord.CUST_PHONE_NUM_2, "CUST-PHONE-NUM-2", 264, 15,
                    PictureKind.ALPHANUMERIC);
            assertSpan(CustomerRecord.CUST_SSN, "CUST-SSN", 279, 9, PictureKind.UNSIGNED_NUMERIC);
            assertSpan(CustomerRecord.CUST_GOVT_ISSUED_ID, "CUST-GOVT-ISSUED-ID", 288, 20,
                    PictureKind.ALPHANUMERIC);
            assertSpan(CustomerRecord.CUST_DOB_YYYY_MM_DD, "CUST-DOB-YYYY-MM-DD", 308, 10,
                    PictureKind.ALPHANUMERIC);
            assertSpan(CustomerRecord.CUST_EFT_ACCOUNT_ID, "CUST-EFT-ACCOUNT-ID", 318, 10,
                    PictureKind.ALPHANUMERIC);
            assertSpan(CustomerRecord.CUST_PRI_CARD_HOLDER_IND, "CUST-PRI-CARD-HOLDER-IND", 328, 1,
                    PictureKind.ALPHANUMERIC);
            assertSpan(CustomerRecord.CUST_FICO_CREDIT_SCORE, "CUST-FICO-CREDIT-SCORE", 329, 3,
                    PictureKind.UNSIGNED_NUMERIC);
        }

        private void assertSpan(FieldSpan span, String name, int offset, int length, PictureKind kind) {
            assertThat(span.name()).isEqualTo(name);
            assertThat(span.offset()).as("%s offset", name).isEqualTo(offset);
            assertThat(span.length()).as("%s length", name).isEqualTo(length);
            assertThat(span.kind()).as("%s picture kind", name).isEqualTo(kind);
            assertThat(span.endOffsetExclusive()).as("%s end", name).isEqualTo(offset + length);
        }

        @Test
        @DisplayName("FILLER occupies bytes 332-499 and is written as 168 spaces, never dropped")
        void fillerIsPresentAndSpaceFilled() {
            assertThat(CustomerRecord.FILLER.name()).isEqualTo("FILLER");
            assertThat(CustomerRecord.FILLER.offset()).isEqualTo(332);
            assertThat(CustomerRecord.FILLER.length()).isEqualTo(FILLER_WIDTH);
            assertThat(CustomerRecord.FILLER.kind()).isEqualTo(PictureKind.FILLER);
            assertThat(CustomerRecord.FILLER.hasInitialValue()).isFalse();
            assertThat(CustomerRecord.FILLER.endOffsetExclusive()).isEqualTo(FIVE_HUNDRED);

            byte[] encoded = new CustomerRecord().encode(ASCII);

            assertThat(encoded).hasSize(FIVE_HUNDRED);
            assertThat(new String(encoded, ASCII).substring(332))
                    .as("FILLER X(168) is emitted as spaces")
                    .isEqualTo(" ".repeat(FILLER_WIDTH));
        }

        @Test
        @DisplayName("every one of the 50 fixture rows decodes and re-encodes to the identical 500 bytes")
        void everyFixtureRowRoundTripsByteForByte() {
            List<String> rows = fixtureRows();

            assertThat(rows).hasSize(FIXTURE_RECORDS);
            for (String row : rows) {
                byte[] stored = row.getBytes(ASCII);
                assertThat(stored).as("stored image of customer %s", keyImageOf(row))
                        .hasSize(FIVE_HUNDRED);

                CustomerRecord decoded = CustomerRecord.decode(stored, ASCII);
                byte[] reencoded = decoded.encode(ASCII);

                assertThat(reencoded).as("round trip of customer %s", keyImageOf(row))
                        .hasSize(FIVE_HUNDRED)
                        .isEqualTo(stored);
                assertThat(new String(reencoded, ASCII).substring(332))
                        .isEqualTo(" ".repeat(FILLER_WIDTH));
            }
        }

        @Test
        @DisplayName("the fixture keys run 000000001 to 000000050 in ascending order")
        void theFixtureKeysAreAscending() {
            List<String> keys = new ArrayList<>();
            for (String row : fixtureRows()) {
                keys.add(keyImageOf(row));
            }

            assertThat(keys).hasSize(FIXTURE_RECORDS)
                    .startsWith("000000001")
                    .endsWith("000000050")
                    .isSorted()
                    .doesNotHaveDuplicates();
        }

        @Test
        @DisplayName("a PIC X field is space-padded and is NOT trimmed on read")
        void picXFieldsKeepTheirPadding() {
            CustomerRecord first = CustomerRecord.decode(fixtureRows().get(0).getBytes(ASCII), ASCII);

            assertThat(first.getCustFirstName())
                    .hasSize(25)
                    .isEqualTo("Immanuel" + " ".repeat(17));
            assertThat(first.getCustMiddleName())
                    .hasSize(25)
                    .isEqualTo("Madeline" + " ".repeat(17));
            assertThat(first.getCustLastName())
                    .hasSize(25)
                    .isEqualTo("Kessler" + " ".repeat(18));
            assertThat(first.getCustAddrLine1())
                    .hasSize(50)
                    .startsWith("618 Deshaun Rout")
                    .isEqualTo("618 Deshaun Route" + " ".repeat(33));
            assertThat(first.getCustAddrLine2()).hasSize(50).isEqualTo("Apt. 802" + " ".repeat(42));
            assertThat(first.getCustAddrLine3())
                    .hasSize(50)
                    .isEqualTo("Altenwerthshire" + " ".repeat(35));
            assertThat(first.getCustAddrStateCd()).hasSize(2).isEqualTo("NC");
            assertThat(first.getCustAddrCountryCd()).hasSize(3).isEqualTo("USA");
            assertThat(first.getCustAddrZip()).hasSize(10).isEqualTo("12546     ");
            assertThat(first.getCustPhoneNum1()).hasSize(15).isEqualTo("(908)119-8310  ");
            assertThat(first.getCustPhoneNum2()).hasSize(15).isEqualTo("(373)693-8684  ");
            assertThat(first.getCustGovtIssuedId()).hasSize(20).isEqualTo("00000000000049368437");
            assertThat(first.getCustDobYyyyMmDd()).hasSize(10).isEqualTo("1961-06-08");
            assertThat(first.getCustEftAccountId()).hasSize(10).isEqualTo("0053581756");
            assertThat(first.getCustPriCardHolderInd()).hasSize(1).isEqualTo("Y");
        }

        @Test
        @DisplayName("a PIC 9 field is zero-filled to its declared width, leading zeros and all")
        void pic9FieldsAreZeroFilledToWidth() {
            CustomerRecord first = CustomerRecord.decode(fixtureRows().get(0).getBytes(ASCII), ASCII);

            assertThat(first.getCustId()).isEqualTo(1);
            assertThat(first.custIdImage(ASCII)).hasSize(9).isEqualTo("000000001");
            assertThat(first.getCustSsn()).isEqualTo(20_973_888);
            assertThat(first.custSsnImage(ASCII)).hasSize(9).isEqualTo("020973888");
            assertThat(first.getCustFicoCreditScore()).isEqualTo(274);
            assertThat(first.custFicoCreditScoreImage(ASCII)).hasSize(3).isEqualTo("274");
        }

        @Test
        @DisplayName("the CUST-SSN reference-modification slices are 020 / 97 / 3888")
        void theSocialSecuritySlicesMatchTheOnlineScreen() {
            String ssn = CustomerRecord.decode(fixtureRows().get(0).getBytes(ASCII), ASCII)
                    .custSsnImage(ASCII);

            assertThat(ssn).hasSize(9).isEqualTo("020973888");
            assertThat(ssn.substring(0, 3)).as("CUST-SSN (1:3)").isEqualTo("020");
            assertThat(ssn.substring(3, 5)).as("CUST-SSN (4:2)").isEqualTo("97");
            assertThat(ssn.substring(5, 9)).as("CUST-SSN (6:4)").isEqualTo("3888");
        }

        @Test
        @DisplayName("the repository reads the record at the copybook geometry it declares")
        void theRepositoryReadsAtTheDeclaredGeometry() {
            List<String> rows = fixtureRows();
            CustomerRepository repository = repository(seeded(rows));

            ReadResult result = repository.readByKey("000000001");

            CustomerRecord record = result.customer().orElseThrow();
            assertThat(repository.recordLength()).isEqualTo(FIVE_HUNDRED);
            assertThat(CustomerRepository.KEY_LENGTH).isEqualTo(NINE);
            assertThat(record.recordImage(ASCII)).hasSize(FIVE_HUNDRED).isEqualTo(rows.get(0));
            assertThat(record.encode(ASCII)).isEqualTo(rows.get(0).getBytes(ASCII));
        }
    }

    @Nested
    @DisplayName("What the estate never does is absent, and provably so")
    class AbsenceTests {
        private List<Class<?>> customerDataPath() {
            return List.of(CustomerRepository.class,
                    CustomerFile.class,
                    ReadResult.class,
                    WriteResult.class,
                    Statements.class,
                    CustomerRecord.class);
        }

        private String compiledForm(Class<?> type) {
            String simpleName = type.getName().substring(type.getName().lastIndexOf('.') + 1);
            try (InputStream stream = type.getResourceAsStream(simpleName + ".class")) {
                if (stream == null) {
                    throw new IllegalStateException("The compiled form of " + type.getName()
                            + " is absent from the test classpath, so its constant pool cannot be "
                            + "inspected");
                }
                return new String(stream.readAllBytes(), StandardCharsets.ISO_8859_1);
            } catch (IOException failure) {
                throw new UncheckedIOException(failure);
            }
        }

        @Test
        @DisplayName("no add, insert, delete or remove path exists, though the CSD grants both")
        void exposesNoAddAndNoDelete() {
            List<String> forbiddenPrefixes = List.of("add", "insert", "delete", "remove", "create",
                    "save", "store", "put", "write", "merge", "upsert");

            for (Class<?> type : List.of(CustomerRepository.class, CustomerFile.class)) {
                for (String name : declaredMethodNamesOf(type)) {
                    String lowered = name.toLowerCase(Locale.ROOT);
                    for (String prefix : forbiddenPrefixes) {
                        assertThat(lowered.startsWith(prefix))
                                .as("%s.%s must not exist: the customer master is never added to, "
                                        + "written to as a new record, or deleted from", type.getSimpleName(),
                                        name)
                                .isFalse();
                    }
                }
            }

            assertThat(declaredMethodNamesOf(CustomerRepository.class))
                    .contains("rewrite", "openInput", "readByKey", "readForUpdate")
                    .doesNotContain("write", "add", "delete");
            assertThat(declaredMethodNamesOf(CustomerFile.class))
                    .contains("readNext", "readByKey", "closeFile", "close")
                    .doesNotContain("rewrite", "readForUpdate");
        }

        @Test
        @DisplayName("no alternate-index finder exists, because the customer master has no alternate index")
        void exposesNoAlternateIndexFinder() {
            for (String name : declaredMethodNamesOf(CustomerRepository.class)) {
                String lowered = name.toLowerCase(Locale.ROOT);
                assertThat(lowered)
                        .as("%s must not be an alternate-index finder", name)
                        .doesNotContain("aix")
                        .doesNotContain("alternate")
                        .doesNotContain("byaccount")
                        .doesNotContain("bycard");
            }
        }

        @Test
        @DisplayName("no persistence annotation and no version column anywhere in the data path")
        void carriesNoPersistenceAnnotationAndNoVersionColumn() {
            Set<String> forbidden = Set.of("Entity", "Table", "Id", "EmbeddedId", "Column",
                    "JoinColumn", "Version", "GeneratedValue", "SequenceGenerator", "TableGenerator",
                    "Embeddable", "Embedded", "MappedSuperclass", "OneToOne", "OneToMany", "ManyToOne",
                    "ManyToMany", "Basic", "Lob", "Convert", "EntityListeners", "Document");

            for (Class<?> type : customerDataPath()) {
                assertAnnotationsAreAllowed(forbidden, type.getSimpleName(), type.getAnnotations());
                for (Field field : type.getDeclaredFields()) {
                    assertAnnotationsAreAllowed(forbidden, type.getSimpleName() + "." + field.getName(),
                            field.getAnnotations());
                    String lowered = field.getName().toLowerCase(Locale.ROOT);
                    assertThat(lowered)
                            .as("%s.%s must not be an optimistic-lock counter: the concurrency check is "
                                    + "9700-CHECK-CHANGE-IN-REC's field-by-field comparison "
                                    + "(app/cbl/COACTUPC.cbl:L4109-L4193), and a version column would be "
                                    + "a schema change", type.getSimpleName(), field.getName())
                            .doesNotContain("version")
                            .doesNotContain("etag")
                            .doesNotContain("revision")
                            .doesNotContain("optimistic");
                }
                for (Method method : type.getDeclaredMethods()) {
                    assertAnnotationsAreAllowed(forbidden,
                            type.getSimpleName() + "." + method.getName() + "()",
                            method.getAnnotations());
                }
            }
        }

        private void assertAnnotationsAreAllowed(Set<String> forbidden, String subject,
                Annotation[] present) {
            for (Annotation annotation : present) {
                assertThat(forbidden)
                        .as("%s carries @%s, which belongs to an object-relational mapping this "
                                + "migration must not introduce", subject,
                                annotation.annotationType().getSimpleName())
                        .doesNotContain(annotation.annotationType().getSimpleName());
            }
        }

        @Test
        @DisplayName("no mainframe dataset name is compiled into the data path; it comes from configuration")
        void carriesNoDatasetLiteral() {
            String forbiddenPrefix = "AWS" + "." + "M2" + "." + "CARDDEMO";

            for (Class<?> type : customerDataPath()) {
                assertThat(compiledForm(type))
                        .as("the compiled form of %s must contain no mainframe dataset literal; the name "
                                + "arrives from the carddemo.datasets.%s and .%s keys", type.getName(),
                                CustomerRepository.CICS_FILE_NAME, CustomerRepository.BATCH_DD_NAME)
                        .doesNotContain(forbiddenPrefix)
                        .doesNotContain("CUSTDATA.VSAM");
            }

            assertThat(CustomerRepository.CICS_FILE_NAME).isEqualTo("CUSTDAT");
            assertThat(CustomerRepository.BATCH_DD_NAME).isEqualTo("CUSTFILE");
        }

        @Test
        @DisplayName("the dataset name is whatever configuration says, and follows it when it changes")
        void resolvesTheDatasetNameFromConfiguration() {
            CustomerRepository configured = new CustomerRepository(new JdbcTemplate(),
                    bindings(TEST_DSNAME, TEST_DSNAME, FIVE_HUNDRED, NINE), ASCII,
                    RecordImageForm.CHARACTER);
            CustomerRepository reconfigured = new CustomerRepository(new JdbcTemplate(),
                    bindings(OTHER_DSNAME, OTHER_DSNAME, FIVE_HUNDRED, NINE), ASCII,
                    RecordImageForm.CHARACTER);

            assertThat(configured.datasetName()).isEqualTo(TEST_DSNAME);
            assertThat(reconfigured.datasetName()).isEqualTo(OTHER_DSNAME);
            assertThat(configured.datasetCharset()).isSameAs(ASCII);
        }

        @Test
        @DisplayName("an unknown DD name fails fast rather than defaulting to something plausible")
        void anUnknownDdNameFailsFast() {
            DatasetBindings misspelled = new DatasetBindings();
            misspelled.put("CUSTDATA", new DatasetBinding(TEST_DSNAME, "ksds", false, "FB", null,
                    FIVE_HUNDRED, "CVCUS01Y", NINE, null, null, null));
            misspelled.put("custdat", new DatasetBinding(TEST_DSNAME, "ksds", false, "FB", null,
                    FIVE_HUNDRED, "CVCUS01Y", NINE, null, null, null));

            assertThatIllegalStateException().isThrownBy(() ->
                            new CustomerRepository(new JdbcTemplate(), misspelled, ASCII,
                                    RecordImageForm.CHARACTER))
                    .withMessageContaining(CustomerRepository.CICS_FILE_NAME)
                    .withMessageContaining("carddemo.datasets");
        }

        @Test
        @DisplayName("no double, no float and no BigDecimal anywhere in the customer data path")
        void usesNoBinaryFloatingPointAndNoBigDecimal() {
            Set<Class<?>> forbiddenTypes = Set.of(double.class, float.class, Double.class, Float.class,
                    double[].class, float[].class, BigDecimal.class);

            for (Class<?> type : customerDataPath()) {
                for (Field field : type.getDeclaredFields()) {
                    assertThat(forbiddenTypes)
                            .as("field %s.%s is %s", type.getSimpleName(), field.getName(),
                                    field.getType().getSimpleName())
                            .doesNotContain(field.getType());
                }
                for (Method method : type.getDeclaredMethods()) {
                    assertThat(forbiddenTypes)
                            .as("%s.%s returns %s", type.getSimpleName(), method.getName(),
                                    method.getReturnType().getSimpleName())
                            .doesNotContain(method.getReturnType());
                    for (Class<?> parameter : method.getParameterTypes()) {
                        assertThat(forbiddenTypes)
                                .as("%s.%s takes a %s", type.getSimpleName(), method.getName(),
                                        parameter.getSimpleName())
                                .doesNotContain(parameter);
                    }
                }
                for (Constructor<?> constructor : type.getDeclaredConstructors()) {
                    for (Class<?> parameter : constructor.getParameterTypes()) {
                        assertThat(forbiddenTypes)
                                .as("a constructor of %s takes a %s", type.getSimpleName(),
                                        parameter.getSimpleName())
                                .doesNotContain(parameter);
                    }
                }
            }
        }

        @Test
        @DisplayName("no half-up or half-even rounding mode is referenced, because nothing here rounds")
        void referencesNoRoundingMode() {
            for (Class<?> type : customerDataPath()) {
                assertThat(compiledForm(type))
                        .as("the compiled form of %s must reference no rounding mode", type.getName())
                        .doesNotContain("HALF_UP")
                        .doesNotContain("HALF_EVEN")
                        .doesNotContain("CEILING")
                        .doesNotContain("RoundingMode");
            }
        }

        @Test
        @DisplayName("the repository is constructor-injected and holds no static mutable state")
        void isConstructorInjectedAndHoldsNoStaticMutableState() {
            Constructor<?>[] constructors = CustomerRepository.class.getDeclaredConstructors();

            assertThat(constructors).hasSize(1);
            assertThat(constructors[0].getParameterCount()).isEqualTo(4);

            for (Class<?> type : customerDataPath()) {
                for (Field field : type.getDeclaredFields()) {
                    if (field.isSynthetic()) {
                        continue;
                    }
                    if (Modifier.isStatic(field.getModifiers())) {
                        assertThat(Modifier.isFinal(field.getModifiers()))
                                .as("static field %s.%s must be final", type.getSimpleName(),
                                        field.getName())
                                .isTrue();
                        assertThat(field.getType().isArray())
                                .as("static field %s.%s must not be a mutable array",
                                        type.getSimpleName(), field.getName())
                                .isFalse();
                    }
                    for (Annotation annotation : field.getAnnotations()) {
                        assertThat(annotation.annotationType().getSimpleName())
                                .as("field %s.%s must not be injected", type.getSimpleName(),
                                        field.getName())
                                .isNotEqualTo("Autowired")
                                .isNotEqualTo("Inject")
                                .isNotEqualTo("Resource")
                                .isNotEqualTo("Value");
                    }
                }
            }
        }

        @Test
        @DisplayName("this test class holds no static mutable state either")
        void thisTestClassHoldsNoStaticMutableState() {
            for (Field field : CustomerRepositoryTest.class.getDeclaredFields()) {
                if (field.isSynthetic() || !Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                assertThat(Modifier.isFinal(field.getModifiers()))
                        .as("static field %s of this test must be final", field.getName())
                        .isTrue();
                assertThat(field.getType().isArray())
                        .as("static field %s of this test must not be a mutable array", field.getName())
                        .isFalse();
                assertThat(field.getType())
                        .as("static field %s of this test must be an immutable constant", field.getName())
                        .isIn(String.class, int.class, long.class, boolean.class, Charset.class);
            }
            assertThat(databaseNamePrefix).startsWith("custrepo_");
        }
    }

    @Nested
    @DisplayName("Every file status is reported as a value; only WHEN OTHER reaches the caller's abend")
    class FileStatusMatrixTests {
        @Test
        @DisplayName("a test's database is disposed of when the test ends, name and schema alike")
        void aTestsDatabaseIsDisposedOf() {
            JdbcTemplate template = seeded(fixtureRows());
            DataSource created = template.getDataSource();

            assertThat(new JdbcTemplate(created).queryForObject(
                    "SELECT COUNT(*) FROM \"" + TEST_DSNAME + "\"", Integer.class))
                    .as("the relation holds this test's rows while the test is using it")
                    .isPositive();

            disposeCreatedDatabases();

            assertThatExceptionOfType(BadSqlGrammarException.class)
                    .as("and afterwards the schema is gone, so the name addresses nothing a later "
                            + "test could see")
                    .isThrownBy(() -> new JdbcTemplate(created).queryForObject(
                            "SELECT COUNT(*) FROM \"" + TEST_DSNAME + "\"", Integer.class));
        }

        @ParameterizedTest(name = "read status ''{0}'' classifies as {1} with APPL-RESULT {2}")
        @CsvSource({
                "00, OK,          0,  0000",
                "10, END_OF_FILE, 16, 0010",
                "22, DUPLICATE,   12, 0022",
                "23, NOT_FOUND,   12, 0023",
                "30, OTHER,       12, 0030",
                "35, OTHER,       12, 0035",
        })
        @DisplayName("a read outcome classifies, scores and renders as the COBOL does")
        void aReadOutcomeClassifiesAsTheCobolDoes(String status, Outcome expectedOutcome,
                int expectedApplResult, String expectedImage) {
            ReadResult result = FileStatus.OK.equals(status)
                    ? customerFound(new CustomerRecord())
                    : ReadResult.of(status);

            assertThat(result.status()).isEqualTo(status);
            assertThat(result.outcome()).isEqualTo(expectedOutcome);
            assertThat(result.applResult()).isEqualTo(expectedApplResult);
            assertThat(result.statusImage()).isEqualTo(expectedImage);
            assertThat(List.of(result.isFound(), result.isEndOfFile(), result.isNotFound(),
                    result.isDuplicate(), result.isOther()).stream().filter(Boolean::booleanValue).count())
                    .isEqualTo(1L);
        }

        @ParameterizedTest(name = "write status ''{0}'' classifies as {1} with APPL-RESULT {2}")
        @CsvSource({
                "00, OK,        0,  0000",
                "22, DUPLICATE, 12, 0022",
                "23, NOT_FOUND, 12, 0023",
                "30, OTHER,     12, 0030",
                "35, OTHER,     12, 0035",
        })
        @DisplayName("a write outcome classifies, scores and renders as the COBOL does")
        void aWriteOutcomeClassifiesAsTheCobolDoes(String status, Outcome expectedOutcome,
                int expectedApplResult, String expectedImage) {
            WriteResult result = WriteResult.of(status);

            assertThat(result.status()).isEqualTo(status);
            assertThat(result.outcome()).isEqualTo(expectedOutcome);
            assertThat(result.applResult()).isEqualTo(expectedApplResult);
            assertThat(result.statusImage()).isEqualTo(expectedImage);
            assertThat(List.of(result.isWritten(), result.isNotFound(), result.isDuplicate(),
                    result.isOther()).stream().filter(Boolean::booleanValue).count()).isEqualTo(1L);
        }

        @Test
        @DisplayName("'22' is a duplicate on both result types, though no path here can produce one")
        void theDuplicateArmIsDistinguishableOnBothResultTypes() {
            assertThat(ReadResult.of(FileStatus.DUPLICATE).isDuplicate()).isTrue();
            assertThat(ReadResult.of(FileStatus.DUPLICATE).isOther()).isFalse();
            assertThat(WriteResult.of(FileStatus.DUPLICATE).isDuplicate()).isTrue();
            assertThat(WriteResult.of(FileStatus.DUPLICATE).isOther()).isFalse();
            assertThat(WriteResult.of(FileStatus.DUPLICATE).isWritten()).isFalse();
            assertThat(WriteResult.of(FileStatus.DUPLICATE).isNotFound()).isFalse();
        }

        @ParameterizedTest(name = "''{0}'' renders as FILE STATUS IS: NNNN{1}")
        @CsvSource({
                "00, 0000",
                "10, 0010",
                "22, 0022",
                "23, 0023",
                "30, 0030",
        })
        @DisplayName("Z-DISPLAY-IO-STATUS renders the four-digit image after the literal NNNN")
        void rendersTheDisplayLineByteExactly(String status, String image) {
            assertThat(FileStatus.DISPLAY_PREFIX).isEqualTo("FILE STATUS IS: NNNN");
            assertThat(FileStatus.toDisplayLine(status)).isEqualTo("FILE STATUS IS: NNNN" + image);
            assertThat(FileStatus.toStatusImage(status)).hasSize(FileStatus.STATUS_IMAGE_LENGTH)
                    .isEqualTo(image);
        }

        @Test
        @DisplayName("the display line for each surfaced status, written out in full")
        void rendersEachSurfacedStatusAsAWholeLiteral() {
            assertThat(FileStatus.toDisplayLine(FileStatus.OK))
                    .isEqualTo("FILE STATUS IS: NNNN0000");
            assertThat(FileStatus.toDisplayLine(FileStatus.END_OF_FILE))
                    .isEqualTo("FILE STATUS IS: NNNN0010");
            assertThat(FileStatus.toDisplayLine(FileStatus.DUPLICATE))
                    .isEqualTo("FILE STATUS IS: NNNN0022");
            assertThat(FileStatus.toDisplayLine(FileStatus.NOT_FOUND))
                    .isEqualTo("FILE STATUS IS: NNNN0023");
            assertThat(FileStatus.toDisplayLine(ReadResult.notFound().status()))
                    .isEqualTo("FILE STATUS IS: NNNN0023");
            assertThat(FileStatus.toDisplayLine(ReadResult.endOfFile().status()))
                    .isEqualTo("FILE STATUS IS: NNNN0010");
            assertThat(FileStatus.toDisplayLine(WriteResult.notFound().status()))
                    .isEqualTo("FILE STATUS IS: NNNN0023");
            assertThat(FileStatus.toDisplayLine(WriteResult.written().status()))
                    .isEqualTo("FILE STATUS IS: NNNN0000");
        }

        @Test
        @DisplayName("a permanent error renders as NNNN9000, the '9' plus its binary feedback byte")
        void rendersAPermanentErrorAsTheCobolWould() {
            assertThat(CustomerRepository.PERMANENT_ERROR_STATUS)
                    .hasSize(FileStatus.STATUS_LENGTH)
                    .startsWith("9");
            assertThat(FileStatus.toStatusImage(CustomerRepository.PERMANENT_ERROR_STATUS))
                    .isEqualTo("9000");
            assertThat(FileStatus.toDisplayLine(CustomerRepository.PERMANENT_ERROR_STATUS))
                    .isEqualTo("FILE STATUS IS: NNNN9000");
            assertThat(FileStatus.outcomeOfStatus(CustomerRepository.PERMANENT_ERROR_STATUS))
                    .isEqualTo(Outcome.OTHER);
        }

        @Test
        @DisplayName("the open call site reports '00' and, when the dataset is unreachable, WHEN OTHER")
        void theOpenCallSiteReportsBothOfItsOutcomes() {
            CustomerFile opened = repository(seeded(fixtureRows())).openInput();
            assertThat(opened.openStatus()).isEqualTo(FileStatus.OK);
            assertThat(opened.openOutcome()).isEqualTo(Outcome.OK);
            assertThat(opened.openApplResult()).isEqualTo(FileStatus.APPL_AOK);
            assertThat(opened.closeFile()).isEqualTo(FileStatus.OK);
            assertThat(opened.closeApplResult()).isEqualTo(FileStatus.APPL_AOK);

            CustomerFile refused = repository(unreachable()).openInput();
            assertThat(refused.openOutcome()).isEqualTo(Outcome.OTHER);
            assertThat(refused.openApplResult()).isEqualTo(CustomerRepository.APPL_RESULT_FATAL);
        }

        @Test
        @DisplayName("the browse call site reports '00' then '10', and never throws for either")
        void theBrowseCallSiteReportsOkThenEndOfFile() {
            try (CustomerFile file = repository(seeded(fixtureRows())).openInput()) {
                assertThatCode(() -> {
                    for (int record = 0; record < FIXTURE_RECORDS; record++) {
                        ReadResult read = file.readNext();
                        assertThat(read.status()).isEqualTo(FileStatus.OK);
                        assertThat(read.applResult()).isEqualTo(FileStatus.APPL_AOK);
                    }
                    ReadResult atEnd = file.readNext();
                    assertThat(atEnd.status()).isEqualTo(FileStatus.END_OF_FILE);
                    assertThat(atEnd.isEndOfFile()).isTrue();
                    assertThat(atEnd.customer()).isEmpty();
                    assertThat(atEnd.applResult()).isEqualTo(FileStatus.APPL_EOF);
                }).doesNotThrowAnyException();
            }
        }

        @Test
        @DisplayName("the keyed and locking read call sites report '00' and '23' as values, never throwing")
        void theKeyedCallSitesReportOkAndNotFoundAsValues() {
            CustomerRepository repository = repository(seeded(fixtureRows()));

            assertThatCode(() -> {
                assertThat(repository.readByKey(1L).status()).isEqualTo(FileStatus.OK);
                assertThat(repository.readByKey("000000050").status()).isEqualTo(FileStatus.OK);
                assertThat(repository.readByKey(ABSENT_CUST_ID).status()).isEqualTo(FileStatus.NOT_FOUND);
                assertThat(repository.readByKey("999999999").isNotFound()).isTrue();
                assertThat(withUnitOfWork(() -> repository.readForUpdate("000000001")).status())
                        .isEqualTo(FileStatus.OK);
                assertThat(withUnitOfWork(() -> repository.readForUpdate("999999999")).status())
                        .isEqualTo(FileStatus.NOT_FOUND);
            }).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("the rewrite call site reports '00' and '23' as values, never throwing")
        void theRewriteCallSiteReportsOkAndNotFoundAsValues() {
            List<String> rows = fixtureRows();
            CustomerRepository repository = repository(seeded(rows));
            byte[] present = rows.get(0).getBytes(ASCII);
            byte[] absent = ("999999999" + rows.get(0).substring(NINE)).getBytes(ASCII);

            assertThatCode(() -> {
                assertThat(withUnitOfWork(() -> repository.rewrite(present).status()))
                        .isEqualTo(FileStatus.OK);
                assertThat(withUnitOfWork(() -> repository.rewrite(absent).status()))
                        .isEqualTo(FileStatus.NOT_FOUND);
            }).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("the WHEN OTHER arm is a status here and becomes the caller's abend, RETURN-CODE 12")
        void theWhenOtherArmBecomesTheCallersAbend() {
            CustomerRepository repository = repository(unreachable());

            ReadResult read = repository.readByKey(1L);
            WriteResult write = withUnitOfWork(() -> repository.rewrite(new CustomerRecord()));

            assertThat(read.isOther()).isTrue();
            assertThat(write.isOther()).isTrue();
            assertThat(read.applResult()).isEqualTo(CustomerRepository.APPL_RESULT_FATAL);
            assertThat(write.applResult()).isEqualTo(CustomerRepository.APPL_RESULT_FATAL);

            assertThat(AbendException.RETURN_CODE_IO_ERROR)
                    .isEqualTo(CustomerRepository.APPL_RESULT_FATAL);
            AbendException abend = AbendException.standard("CBCUS01C", read.applResult(),
                    FileStatus.toDisplayLine(read.status()));

            assertThat(abend.getReturnCode()).isEqualTo(AbendException.RETURN_CODE_IO_ERROR);
            assertThat(abend.getProgram()).isEqualTo("CBCUS01C");
            assertThat(abend.getAbendCode()).hasValue(AbendException.STANDARD_ABEND_CODE);
            assertThat(abend.getTiming()).hasValue(AbendException.STANDARD_TIMING);
            assertThat(abend.getReason()).contains("FILE STATUS IS: NNNN9000");
            assertThat(abend).hasMessageContaining(AbendException.ABEND_DISPLAY_TEXT);
        }

        @Test
        @DisplayName("no call site throws an AbendException: the abend belongs to the caller")
        void noCallSiteThrowsAnAbendException() {
            CustomerRepository repository = repository(unreachable());

            assertThatCode(() -> {
                CustomerFile file = repository.openInput();
                file.readNext();
                file.readByKey(1L);
                file.readByKey("000000001");
                file.closeFile();
                repository.readByKey(1L);
                repository.readByKey("000000001");
                withUnitOfWork(() -> repository.rewrite(new CustomerRecord()));
            }).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("the four named statuses round-trip through the CICS response vocabulary")
        void theNamedStatusesMapOntoTheCicsVocabulary() {
            assertThat(FileStatus.outcomeOfStatus(FileStatus.OK)).isEqualTo(Outcome.OK);
            assertThat(FileStatus.outcomeOfStatus(FileStatus.END_OF_FILE))
                    .isEqualTo(Outcome.END_OF_FILE);
            assertThat(FileStatus.outcomeOfStatus(FileStatus.NOT_FOUND)).isEqualTo(Outcome.NOT_FOUND);
            assertThat(FileStatus.outcomeOfStatus(FileStatus.DUPLICATE)).isEqualTo(Outcome.DUPLICATE);

            assertThat(FileStatus.outcomeOfCicsResp(FileStatus.NORMAL)).isEqualTo(Outcome.OK);
            assertThat(FileStatus.outcomeOfCicsResp(FileStatus.ENDFILE))
                    .isEqualTo(Outcome.END_OF_FILE);
            assertThat(FileStatus.outcomeOfCicsResp(FileStatus.NOTFND)).isEqualTo(Outcome.NOT_FOUND);
            assertThat(FileStatus.outcomeOfCicsResp(FileStatus.DUPREC)).isEqualTo(Outcome.DUPLICATE);
            assertThat(FileStatus.outcomeOfCicsResp(FileStatus.INVREQ)).isEqualTo(Outcome.OTHER);

            assertThat(ReadResult.notFound().cicsResp()).hasValue(FileStatus.NOTFND);
            assertThat(ReadResult.endOfFile().cicsResp()).hasValue(FileStatus.ENDFILE);
            assertThat(customerFound(new CustomerRecord()).cicsResp())
                    .hasValue(FileStatus.NORMAL);
            assertThat(WriteResult.written().cicsResp()).hasValue(FileStatus.NORMAL);
            assertThat(WriteResult.notFound().cicsResp()).hasValue(FileStatus.NOTFND);
        }
    }

    private static CustomerRepository.ReadResult customerFound(CustomerRecord customer) {
        return CustomerRepository.ReadResult.found(customer,
                customer.recordImage(StandardCharsets.US_ASCII));
    }
}
