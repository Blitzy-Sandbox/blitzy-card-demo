package com.vsergeychik.carddemo.account;

import com.vsergeychik.carddemo.account.AccountRepository.AccountFile;
import com.vsergeychik.carddemo.account.AccountRepository.OpenMode;
import com.vsergeychik.carddemo.account.AccountRepository.ReadResult;
import com.vsergeychik.carddemo.account.AccountRepository.Statements;
import com.vsergeychik.carddemo.account.AccountRepository.WriteResult;
import com.vsergeychik.carddemo.account.model.AccountRecord;
import com.vsergeychik.carddemo.account.model.DisclosureGroupRecord;
import com.vsergeychik.carddemo.common.AbendException;
import com.vsergeychik.carddemo.common.CicsResponse;
import com.vsergeychik.carddemo.common.CobolDecimal;
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
import org.junit.jupiter.params.provider.CsvSource;
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
import java.lang.annotation.Annotation;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import com.vsergeychik.carddemo.testsupport.ConcurrentTasks;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Timeout;

/**
 * Unit tests for {@link AccountRepository}, the account master dataset's only reader and only writer.
 */
@DisplayName("AccountRepository - the account master dataset over JDBC")
class AccountRepositoryTest {
    private static final Charset ASCII = StandardCharsets.US_ASCII;

    private static final String TEST_DSNAME = "TEST.ACCOUNT.KSDS";

    private static final String RECORD_IMAGE_COLUMN = "REC";

    private static final String FIXTURE = "/fixtures/acctdata.txt";

    private static final int FIXTURE_RECORDS = 50;

    private static final int THREE_HUNDRED = 300;

    private static final int ELEVEN = 11;

    private static final int FILLER_WIDTH = 178;

    private static final long ABSENT_ACCT_ID = 99_999_999_999L;

    private static final AtomicInteger DATABASE_SEQUENCE = new AtomicInteger();

    private static final int LOCK_TIMEOUT_MILLIS = 250;

    private static final String DISCLOSURE_GROUP_FIXTURE = "/fixtures/discgrp.txt";

    private static final int DISCLOSURE_GROUP_FIXTURE_RECORDS = 51;

    private static final int FIFTY = 50;

    private static final int SIXTEEN = 16;

    private static final int SEVENTEEN_BYTE_KEY_WIDTH = 17;

    private static final int SIX = 6;

    private static final int DISCLOSURE_GROUP_FILLER_WIDTH = 28;

    private static final String DISCLOSURE_GROUP_ROW_1 =
            "A00000000001000100150{0000000000000000000000000000";

    private static final String ROW_1_RATE_IMAGE = "00150{";

    private static final String ROW_1_RATE_IMAGE_UNDER_A_17_BYTE_KEY = "0150{0";

    private static final BigDecimal FIFTEEN_PERCENT = new BigDecimal("15.00");

    private static final String REPOSITORY_SOURCE_PATH =
            "app/java/src/main/java/com/vsergeychik/carddemo/account/AccountRepository.java";

    private static final String MAIN_SOURCE_ROOT = "app/java/src/main/java/com/vsergeychik/carddemo";

    private static final String APPLICATION_YAML = "app/java/src/main/resources/application.yml";

    private static final String MAINFRAME_DATASET_PREFIX = "AWS.M2.CARDDEMO.";

    private static final Pattern DDL_STATEMENT = Pattern.compile(
            "(?i)\\b(create|alter|drop|truncate|rename)\\s+(table|index|view|sequence|schema)\\b");

    private static DatasetBindings bindings(String cicsDsname, String batchDsname, int recordLength,
            Integer keyLength) {
        DatasetBindings catalogue = new DatasetBindings();
        catalogue.put(AccountRepository.CICS_FILE_NAME, new DatasetBinding(cicsDsname, "ksds", false,
                "FB", null, recordLength, "CVACT01Y", keyLength, null, null, null));
        catalogue.put(AccountRepository.BATCH_DD_NAME, new DatasetBinding(batchDsname, "ksds", false,
                "FB", null, recordLength, "CVACT01Y", keyLength, null, null, null));
        return catalogue;
    }

    private static DatasetBindings validBindings() {
        return bindings(TEST_DSNAME, TEST_DSNAME, THREE_HUNDRED, null);
    }

    private static AccountRepository repository(JdbcTemplate template) {
        return new AccountRepository(template, validBindings(), ASCII, RecordImageForm.CHARACTER);
    }

    private static JdbcTemplate seeded(List<String> rows) {
        return seeded(rows, THREE_HUNDRED);
    }

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

    private static String keyImageOf(String row) {
        return row.substring(0, ELEVEN);
    }

    private static List<String> disclosureGroupRows() {
        try (InputStream stream =
                     AccountRepositoryTest.class.getResourceAsStream(DISCLOSURE_GROUP_FIXTURE)) {
            if (stream == null) {
                throw new IllegalStateException("The disclosure-group fixture "
                        + DISCLOSURE_GROUP_FIXTURE + " is absent from the test classpath; the rate "
                        + "expectations in this class are seeded from it");
            }
            return new String(stream.readAllBytes(), ASCII).lines().toList();
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private static Path repositoryFile(String relativePath) {
        Path candidate = Path.of("").toAbsolutePath();
        while (candidate != null) {
            Path resolved = candidate.resolve(relativePath);
            if (Files.exists(resolved)) {
                return resolved;
            }
            candidate = candidate.getParent();
        }
        throw new IllegalStateException("Could not find " + relativePath + " at or above "
                + Path.of("").toAbsolutePath());
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            throw new UncheckedIOException("Could not read " + file, unreadable);
        }
    }

    private static String codeOnly(String source) {
        StringBuilder code = new StringBuilder(source.length());
        int index = 0;
        while (index < source.length()) {
            char current = source.charAt(index);
            char next = index + 1 < source.length() ? source.charAt(index + 1) : '\0';
            if (current == '/' && next == '/') {
                index = skipLineComment(source, index);
                code.append(' ');
            } else if (current == '/' && next == '*') {
                index = skipBlockComment(source, index);
                code.append(' ');
            } else if (current == '"' || current == '\'') {
                int end = skipLiteral(source, index, current);
                code.append(source, index, end);
                index = end;
            } else {
                code.append(current);
                index++;
            }
        }
        return code.toString();
    }

    private static int skipLineComment(String source, int start) {
        int index = start + 2;
        while (index < source.length() && source.charAt(index) != '\n') {
            index++;
        }
        return index;
    }

    private static int skipBlockComment(String source, int start) {
        int index = start + 2;
        while (index + 1 < source.length()
                && !(source.charAt(index) == '*' && source.charAt(index + 1) == '/')) {
            index++;
        }
        return Math.min(index + 2, source.length());
    }

    private static int skipLiteral(String source, int start, char quote) {
        int index = start + 1;
        while (index < source.length()) {
            char current = source.charAt(index);
            if (current == '\\') {
                index += 2;
                continue;
            }
            index++;
            if (current == quote) {
                return index;
            }
        }
        return source.length();
    }

    private static List<Path> mainSources() {
        Path root = repositoryFile(MAIN_SOURCE_ROOT);
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".java"))
                    .toList();
        } catch (IOException unreadable) {
            throw new UncheckedIOException("Could not walk " + root, unreadable);
        }
    }

    private static List<Annotation> allAnnotationsOf(Class<?> type) {
        List<Annotation> found = new ArrayList<>(List.of(type.getAnnotations()));
        for (Field field : type.getDeclaredFields()) {
            found.addAll(List.of(field.getAnnotations()));
        }
        List<Executable> executables = new ArrayList<>();
        executables.addAll(List.of(type.getDeclaredConstructors()));
        executables.addAll(List.of(type.getDeclaredMethods()));
        for (Executable executable : executables) {
            found.addAll(List.of(executable.getAnnotations()));
            for (Annotation[] parameter : executable.getParameterAnnotations()) {
                found.addAll(List.of(parameter));
            }
        }
        return found;
    }

    private static List<Class<?>> datasetAccessTypes() {
        List<Class<?>> types = new ArrayList<>();
        types.add(AccountRepository.class);
        types.addAll(List.of(AccountRepository.class.getDeclaredClasses()));
        types.add(AccountRecord.class);
        types.add(DisclosureGroupRecord.class);
        return types;
    }

    private static Set<String> publicMethodNamesOf(Class<?> type) {
        Set<String> names = new LinkedHashSet<>();
        for (Method method : type.getDeclaredMethods()) {
            if (Modifier.isPublic(method.getModifiers()) && !method.isSynthetic()) {
                names.add(method.getName());
            }
        }
        return names;
    }

    private static List<String> datasetStanza(String datasetKey) {
        List<String> lines = read(repositoryFile(APPLICATION_YAML)).lines().toList();
        String keyLine = "    " + datasetKey + ":";
        int start = lines.indexOf(keyLine);
        if (start < 0) {
            throw new IllegalStateException("application.yml declares no carddemo.datasets." + datasetKey
                    + " stanza, so the dataset name this repository resolves has no configured home");
        }
        List<String> stanza = new ArrayList<>();
        stanza.add(lines.get(start));
        for (int index = start + 1; index < lines.size(); index++) {
            String line = lines.get(index);
            if (!line.isBlank() && !line.startsWith("     ")) {
                break;
            }
            stanza.add(line);
        }
        return stanza;
    }

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

    private static AccountFile openedInput(JdbcTemplate template) {
        return repository(template).open(OpenMode.INPUT);
    }

    private static <T> T withUnitOfWork(Supplier<T> action) {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            return action.get();
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    private static TransactionTemplate transactionOver(JdbcTemplate template) {
        return new TransactionTemplate(new DataSourceTransactionManager(template.getDataSource()));
    }

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

    private static JdbcTemplate describingThenFanningOutOnUpdate() throws SQLException {
        return countingOneThenReportingUpdateCount(2);
    }

    private static JdbcTemplate describingThenLosingTheRowBeforeUpdate() throws SQLException {
        return countingOneThenReportingUpdateCount(0);
    }

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
        Mockito.when(oneRow.next()).thenReturn(true, false);
        Mockito.when(preparedStatement.executeUpdate()).thenReturn(updateCount);
        return new JdbcTemplate(dataSource);
    }

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
                    .isEqualTo("SELECT * FROM " + dataset + " WHERE (" + column + " > ? OR " + column
                            + " IS NULL) ORDER BY " + column + " ASC");
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

            assertThat(second).isEqualTo(first).isNotSameAs(first);
        }
    }

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
            String full = fixtureRows().get(0);
            String trimmed = full.substring(0, THREE_HUNDRED - FILLER_WIDTH);

            try (AccountFile file = openedInput(seeded(List.of(trimmed)))) {
                ReadResult result = file.readNext();

                assertThat(result.isOther()).isTrue();
                assertThat(result.account()).isEmpty();
                assertThat(result.status()).isEqualTo(AccountRepository.PERMANENT_ERROR_STATUS);
                assertThat(result.applResult()).isEqualTo(AccountRepository.APPL_RESULT_FATAL);
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
                assertThat(result.cicsResp()).hasValue(FileStatus.INVREQ);
                assertThat(result.cicsResp2()).isEqualTo(FileStatus.NO_REASON_CODE);
            }
        }
    }

    @Nested
    @DisplayName("readByKey and readForUpdate - the keyed reads")
    class KeyedReadTests {
        @Test
        @DisplayName("finding DB-05: a present-but-unreadable row is not reported as an absent record")
        void anUnreadableRowIsNotReportedAsAbsent() {
            List<String> rows = new ArrayList<>(fixtureRows());
            rows.add(null);
            AccountRepository repository = repository(seeded(rows));

            ReadResult result = repository.readByKey(99999999999L);

            assertThat(result.isNotFound()).isFalse();
            assertThat(result.isOther()).isTrue();
            assertThat(result.cicsResp()).hasValue(FileStatus.INVREQ);
            assertThat(result.account()).isEmpty();
        }

        @Test
        @DisplayName("finding DB-05: a genuinely absent key still reports '23', proved rather than assumed")
        void aGenuinelyAbsentKeyIsStillNotFound() {
            AccountRepository repository = repository(seeded(fixtureRows()));

            ReadResult result = repository.readByKey(99999999999L);

            assertThat(result.isNotFound()).isTrue();
            assertThat(result.status()).isEqualTo(FileStatus.NOT_FOUND);
        }

        @Test
        @DisplayName("finding DB-05: a read that found its record is untouched by an unreadable row")
        void aFoundRecordIsUnaffectedByAnUnreadableRowElsewhere() {
            List<String> rows = new ArrayList<>(fixtureRows());
            rows.add(null);
            AccountRepository repository = repository(seeded(rows));
            long acctId = Long.parseLong(keyImageOf(fixtureRows().get(0)));

            ReadResult result = repository.readByKey(acctId);

            assertThat(result.isFound()).isTrue();
            assertThat(result.account().orElseThrow().getAcctId()).isEqualTo(acctId);
        }

        @Test
        @DisplayName("finding DB-05: a probe the backend refuses is reported, never assumed absent")
        void aRefusedProbeIsReportedRatherThanAssumedAbsent() throws SQLException {
            AccountRepository repository = repository(emptyReadThenRefusedProbe());

            ReadResult result = repository.readByKey(1L);

            assertThat(result.isNotFound())
                    .as("the probe established nothing, so the absence stays unproved")
                    .isFalse();
            assertThat(result.isOther()).isTrue();
        }

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

            assertThatIllegalArgumentException().isThrownBy(() -> repository.readForUpdate("0000000001"))
                    .withMessageContaining("PIC X(11)");
            assertThatNullPointerException().isThrownBy(() -> repository.readForUpdate(null))
                    .withMessageContaining("key image is required");
        }

        @Test
        @DisplayName("a read-for-update with no unit of work open is refused, not issued anyway")
        void aReadForUpdateOutsideAUnitOfWorkIsRefused() {
            AccountRepository repository = repository(seeded(fixtureRows()));

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

            assertThat(withUnitOfWork(() -> unreachableRepository.readForUpdate(key)).status())
                    .isEqualTo(AccountRepository.PERMANENT_ERROR_STATUS);
            assertThat(withUnitOfWork(() -> refusingRepository.readForUpdate(key)).status())
                    .isEqualTo(AccountRepository.PERMANENT_ERROR_STATUS);
            assertThat(withUnitOfWork(() -> refusingRepository.readForUpdate(key)).isFound())
                    .as("not found, so the caller takes the could-not-lock arm")
                    .isFalse();
        }
    }

    private static <T> T inUnitOfWork(java.util.function.Supplier<T> work) {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            return work.get();
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

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

            account.setAcctCurrBal(account.getAcctCurrBal().add(new BigDecimal("12.34")));
            account.zeroAcctCurrCycCredit();
            account.zeroAcctCurrCycDebit();
            WriteResult written = withUnitOfWork(() -> repository.rewrite(account));

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
        @DisplayName("a record built over another code page is refused, and nothing is written")
        void aRecordInAForeignCodePageIsRefused() {
            List<String> rows = fixtureRows();
            AccountRepository repository = repository(seeded(rows));
            long acctId = Long.parseLong(keyImageOf(rows.get(0)));
            AccountRecord stored = repository.readByKey(acctId).account().orElseThrow();
            AccountRecord onEbcdic =
                    AccountRecord.decode(stored.toFixedWidthString(), Charset.forName("IBM037"));

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> withUnitOfWork(() -> repository.rewrite(onEbcdic)))
                    .withMessageContaining("IBM037")
                    .withMessageContaining("US-ASCII");

            assertThat(repository.readByKey(acctId).account().orElseThrow().toFixedWidthString())
                    .isEqualTo(stored.toFixedWidthString());

            assertThat(repository.datasetCharset()).isEqualTo(ASCII);
            assertThat(withUnitOfWork(() -> repository.rewrite(stored)).isWritten()).isTrue();
        }

        @Test
        @DisplayName("a rewrite issued through an open handle writes through that handle's statements")
        void aRewriteThroughAnOpenHandleWrites() {
            List<String> rows = fixtureRows();
            AccountRepository repository = repository(seeded(rows));
            long acctId = Long.parseLong(keyImageOf(rows.get(0)));

            try (AccountFile file = repository.open(OpenMode.I_O)) {
                assertThat(file.openStatus()).isEqualTo(FileStatus.OK);
                AccountRecord account = file.readByKey(acctId).account().orElseThrow();
                account.setAcctGroupId("VIAHANDLE");

                WriteResult written = withUnitOfWork(() -> file.rewrite(account));

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

            assertThat(withUnitOfWork(() -> repository.rewrite(account)).isWritten()).isTrue();

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

            WriteResult result = withUnitOfWork(() -> repository.rewrite(absent));

            assertThat(result.isNotFound()).isTrue();
            assertThat(result.status()).isEqualTo(FileStatus.NOT_FOUND);
            assertThat(result.applResult()).isEqualTo(AccountRepository.APPL_RESULT_FATAL);
        }

        @Test
        @DisplayName("refuses before writing when more than one row carries the key, and changes neither")
        void refusesBeforeWritingWhenSeveralRowsCarryTheKey() {
            String duplicated = fixtureRows().get(0);
            JdbcTemplate template = seeded(List.of(duplicated, duplicated));
            AccountRepository repository = repository(template);
            AccountRecord account = AccountRecord.decode(duplicated, ASCII);
            account.setAcctGroupId("OVERWRITE");

            WriteResult result = withUnitOfWork(() -> repository.rewrite(account));

            assertThat(result.isOther()).isTrue();
            assertThat(result.status()).isEqualTo(AccountRepository.PERMANENT_ERROR_STATUS);
            assertThat(result.applResult()).isEqualTo(AccountRepository.APPL_RESULT_FATAL);
            assertThat(result.cicsResp()).hasValue(FileStatus.INVREQ);
            assertThat(result.cicsResp2()).isEqualTo(FileStatus.NO_REASON_CODE);

            assertThat(template.queryForList("SELECT " + RECORD_IMAGE_COLUMN + " FROM \""
                    + TEST_DSNAME + "\"", String.class))
                    .as("a refused rewrite must not have changed a single row")
                    .containsExactly(duplicated, duplicated);
        }

        @Test
        @DisplayName("outside a unit of work the rewrite is refused before any statement is prepared")
        void outsideAUnitOfWorkTheRewriteIsRefused() throws SQLException {
            List<String> prepared = new ArrayList<>();
            AccountRepository repository = repository(recordingPreparedStatements(prepared));
            AccountRecord account = AccountRecord.decode(fixtureRows().get(0), ASCII);

            assertThatIllegalStateException()
                    .isThrownBy(() -> repository.rewrite(account))
                    .withMessageContaining("no transaction is open on this thread")
                    .withMessageContaining("changes stored records")
                    .withMessageContaining(TEST_DSNAME);

            assertThat(prepared)
                    .as("nothing may be attempted when the change could not be committed")
                    .isEmpty();
        }

        @Test
        @DisplayName("inside a unit of work the refusal says the count was taken under a row lock")
        void insideAUnitOfWorkTheRefusalNamesTheRowLock() throws SQLException {
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
            AccountRepository repository = repository(describingThenLosingTheRowBeforeUpdate());
            AccountRecord account = AccountRecord.decode(fixtureRows().get(0), ASCII);

            WriteResult result = withUnitOfWork(() -> repository.rewrite(account));

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

            transactionOver(template).execute(status -> repository.rewrite(account));
            assertThat(prepared).isNotEmpty();
            assertThat(prepared.get(0)).endsWith("FOR UPDATE");
        }

        @Test
        @DisplayName("a rewrite whose key selects nothing issues no UPDATE at all")
        void aRewriteWhoseKeySelectsNothingIssuesNoUpdate() throws SQLException {
            List<String> prepared = new ArrayList<>();
            AccountRepository repository = repository(recordingPreparedStatements(prepared));
            AccountRecord absent = new AccountRecord(ASCII);
            absent.setAcctId(ABSENT_ACCT_ID);

            assertThat(withUnitOfWork(() -> repository.rewrite(absent)).isNotFound()).isTrue();
            assertThat(prepared)
                    .as("the count found no row, so no UPDATE should have been prepared")
                    .noneMatch(sql -> sql.startsWith("UPDATE"));
        }

        @Test
        @DisplayName("a refused count is reported as a permanent error, not as a missing record")
        void aRefusedCountIsAPermanentError() throws SQLException {
            AccountRepository repository = repository(describingThenRefusing());
            AccountRecord account = AccountRecord.decode(fixtureRows().get(0), ASCII);

            WriteResult result = withUnitOfWork(() -> repository.rewrite(account));

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

            AccountRepository unreachableRepository = repository(unreachable());
            AccountRepository refusingRepository = repository(describingThenRefusing());

            assertThat(withUnitOfWork(() -> unreachableRepository.rewrite(account).status()))
                    .isEqualTo(AccountRepository.PERMANENT_ERROR_STATUS);
            assertThat(withUnitOfWork(() -> refusingRepository.rewrite(account).status()))
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

    @Nested
    @DisplayName("readForUpdate - the locking read")
    class LockingReadTests {
        private String keyedSelect() {
            return "SELECT * FROM \"" + TEST_DSNAME + "\" WHERE \"" + RECORD_IMAGE_COLUMN
                    + "\" LIKE ? ESCAPE '\\' ORDER BY \"" + RECORD_IMAGE_COLUMN + "\" ASC";
        }

        private String unreadableRowsProbe() {
            return "SELECT * FROM \"" + TEST_DSNAME + "\" WHERE \"" + RECORD_IMAGE_COLUMN
                    + "\" IS NULL";
        }

        @Test
        @DisplayName("asks the driver for a locking read, where the plain keyed read does not")
        void asksTheDriverForALockingRead() throws SQLException {
            List<String> prepared = new ArrayList<>();
            AccountRepository repository = repository(recordingPreparedStatements(prepared));

            repository.readByKey(1L);
            assertThat(prepared)
                    .as("the plain keyed read takes no lock, and must not start taking one; the probe "
                            + "that follows it proves the absence and takes none either")
                    .containsExactly(keyedSelect(), unreadableRowsProbe());

            prepared.clear();
            withUnitOfWork(() -> repository.readForUpdate("0".repeat(ELEVEN)));

            assertThat(prepared).containsExactly(keyedSelect() + " FOR UPDATE", unreadableRowsProbe());
        }

        @Test
        @DisplayName("the handle's read for update asks for the same locking statement")
        void theHandlesReadForUpdateLocksToo() throws SQLException {
            List<String> prepared = new ArrayList<>();
            try (AccountFile file = repository(recordingPreparedStatements(prepared))
                    .open(OpenMode.I_O)) {
                prepared.clear();

                withUnitOfWork(() -> file.readForUpdate("0".repeat(ELEVEN)));

                assertThat(prepared).containsExactly(keyedSelect() + " FOR UPDATE",
                        unreadableRowsProbe());
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
                AccountRecord locked = repository.readForUpdate(key).account().orElseThrow();
                assertThat(locked.toFixedWidthString()).isEqualTo(rows.get(0));
                locked.setAcctCurrBal(locked.getAcctCurrBal().add(new BigDecimal("1.00")));
                return repository.rewrite(locked);
            });

            assertThat(written).isNotNull();
            assertThat(written.isWritten()).isTrue();
            assertThat(repository.readByKey(acctId).account().orElseThrow().toFixedWidthString())
                    .hasSize(THREE_HUNDRED)
                    .isNotEqualTo(rows.get(0));
        }
    }

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

                assertThat(first.isClosed()).isFalse();
                assertThat(first.mode()).isSameAs(OpenMode.INPUT);
                assertThat(first.readNext().account().orElseThrow().toFixedWidthString())
                        .isEqualTo(expected.get(1));
            }
        }

        @Test
        @DisplayName("two threads browsing their own handles over one repository each read all 50")
        @Timeout(value = 60, unit = TimeUnit.SECONDS)
        void twoThreadsEachReadEveryRecord() {
            List<String> rows = fixtureRows();
            List<String> expected = rows.stream().sorted().toList();
            AccountRepository repository = repository(seeded(rows));

            CyclicBarrier startTogether = new CyclicBarrier(2);
            Callable<List<String>> browse = () -> {
                startTogether.await(ConcurrentTasks.TIMEOUT_SECONDS, TimeUnit.SECONDS);
                try (AccountFile file = repository.open(OpenMode.INPUT)) {
                    return drain(file, FIXTURE_RECORDS + 1);
                }
            };

            List<List<String>> results = ConcurrentTasks.runBoth(browse, browse);

            assertThat(results).hasSize(2);
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

            assertThat(repository.readByKey(last).account().orElseThrow().getAcctId()).isEqualTo(last);
            assertThat(repository.readByKey(first).account().orElseThrow().getAcctId()).isEqualTo(first);
            assertThat(repository.readByKey(ABSENT_ACCT_ID).isNotFound()).isTrue();
            assertThat(repository.readByKey(last).account().orElseThrow().getAcctId()).isEqualTo(last);
        }
    }

    @Nested
    @DisplayName("ReadResult and WriteResult")
    class OutcomeTests {
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

    @Nested
    @DisplayName("Schema integrity - nothing is defined, indexed or named in Java")
    class SchemaIntegrityTests {
        @Test
        @DisplayName("no persistence annotation appears on the repository, its nested types or either "
                + "record")
        void noPersistenceAnnotationAppearsAnywhereOnTheAccessSurface() {
            List<String> offenders = new ArrayList<>();
            for (Class<?> type : datasetAccessTypes()) {
                for (Annotation annotation : allAnnotationsOf(type)) {
                    Class<?> annotationType = annotation.annotationType();
                    String packageName = annotationType.getPackageName();
                    if (packageName.startsWith("jakarta.persistence")
                            || packageName.startsWith("javax.persistence")) {
                        offenders.add(type.getSimpleName() + " carries @"
                                + annotationType.getSimpleName());
                    }
                }
            }

            assertThat(offenders)
                    .as("an object-relational mapping would impose an entity and table model that the "
                            + "VSAM datasets of this estate do not have")
                    .isEmpty();
        }

        @Test
        @DisplayName("the scan really would notice a mapping annotation")
        void theAnnotationScanIsNotVacuous() {
            List<Class<?>> annotationTypes = new ArrayList<>();
            for (Annotation annotation : allAnnotationsOf(AccountRepository.class)) {
                annotationTypes.add(annotation.annotationType());
            }

            assertThat(annotationTypes)
                    .as("the scan reaches the annotations that are present")
                    .isNotEmpty()
                    .anySatisfy(annotationType -> assertThat(annotationType.getSimpleName())
                            .isEqualTo("Repository"));
            assertThat(annotationTypes)
                    .allSatisfy(annotationType -> assertThat(annotationType.getPackageName())
                            .startsWith("org.springframework"));
        }

        @Test
        @DisplayName("no statement the repository composes is a data-definition statement")
        void noComposedStatementIsDdl() {
            AccountRepository repository = repository(seeded(List.of()));
            Statements statements = repository.resolveStatements();
            List<String> composed = List.of(repository.columnProbeSql(), statements.selectFirst(),
                    statements.selectNext(), statements.selectByKey(),
                    statements.selectByKeyForUpdate(), statements.rewrite());

            assertThat(composed).allSatisfy(sql -> {
                assertThat(DDL_STATEMENT.matcher(sql).find())
                        .describedAs("%s must not define, alter or remove anything", sql)
                        .isFalse();
                assertThat(sql).startsWithIgnoringCase(sql.startsWith("UPDATE") ? "UPDATE" : "SELECT");
            });
        }

        @Test
        @DisplayName("no statement the repository prepares against a driver is a data-definition "
                + "statement")
        void noPreparedStatementIsDdl() throws SQLException {
            List<String> prepared = new ArrayList<>();
            AccountRepository repository = repository(recordingPreparedStatements(prepared));
            AccountRecord blank = new AccountRecord(ASCII);
            blank.setAcctId(ABSENT_ACCT_ID);

            try (AccountFile file = repository.open(OpenMode.I_O)) {
                file.readNext();
                file.readByKey(ABSENT_ACCT_ID);
                withUnitOfWork(() -> file.rewrite(blank));
            }
            repository.readByKey(ABSENT_ACCT_ID);
            withUnitOfWork(() -> repository.readForUpdate(AccountRecord.keyImage(ABSENT_ACCT_ID, ASCII)));
            withUnitOfWork(() -> repository.rewrite(blank));

            assertThat(prepared)
                    .as("the operations must actually have prepared something, or this proves nothing")
                    .isNotEmpty();
            assertThat(prepared).allSatisfy(sql -> assertThat(DDL_STATEMENT.matcher(sql).find())
                    .describedAs("%s must not define, alter or remove anything", sql)
                    .isFalse());
        }

        @Test
        @DisplayName("the data-definition pattern really does recognise a migration statement")
        void theDdlPatternIsNotVacuous() {
            assertThat(DDL_STATEMENT.matcher("CREATE TABLE ACCOUNT (ACCT_ID NUMERIC(11))").find())
                    .isTrue();
            assertThat(DDL_STATEMENT.matcher("alter table ACCOUNT add VERSION int").find()).isTrue();
            assertThat(DDL_STATEMENT.matcher("DROP INDEX ACCT_AIX").find()).isTrue();
            assertThat(DDL_STATEMENT.matcher(
                    "SELECT * FROM \"A\" WHERE \"REC\" LIKE ? ESCAPE '\\'").find()).isFalse();
        }

        @Test
        @DisplayName("no main source in the module contains a data-definition statement, and no "
                + "migration artefact exists")
        void theModuleShipsNoDdlAndNoMigrationArtefact() {
            List<String> offenders = new ArrayList<>();
            for (Path source : mainSources()) {
                if (DDL_STATEMENT.matcher(codeOnly(read(source))).find()) {
                    offenders.add(source.getFileName().toString());
                }
            }

            assertThat(offenders)
                    .as("no DDL, no schema migration: the datasets already exist and are reached, not "
                            + "created")
                    .isEmpty();

            Path resources = repositoryFile("app/java/src/main/resources");
            assertThat(Files.exists(resources.resolve("db/migration"))).isFalse();
            assertThat(Files.exists(resources.resolve("db/changelog"))).isFalse();
            assertThat(Files.exists(resources.resolve("schema.sql"))).isFalse();
            assertThat(Files.exists(resources.resolve("data.sql"))).isFalse();
        }

        @Test
        @DisplayName("the repository exposes no alternate-index finder, because ACCTDAT has no "
                + "alternate index")
        void exposesNoAlternateIndexFinder() {
            Set<String> repositoryMethods = publicMethodNamesOf(AccountRepository.class);
            Set<String> handleMethods = publicMethodNamesOf(AccountFile.class);

            assertThat(repositoryMethods).containsExactlyInAnyOrder(
                    "datasetName", "recordLength", "datasetCharset",
                    "open", "readByKey", "readForUpdate", "rewrite");
            assertThat(handleMethods).containsExactlyInAnyOrder(
                    "openStatus", "openOutcome", "mode", "datasetName", "isClosed",
                    "readNext", "readByKey", "readForUpdate", "rewrite", "closeFile", "close");

            for (String datasetKey : List.of(AccountRepository.CICS_FILE_NAME,
                    AccountRepository.BATCH_DD_NAME)) {
                assertThat(datasetStanza(datasetKey))
                        .as("carddemo.datasets.%s declares no alternate index", datasetKey)
                        .noneMatch(line -> line.contains("alternate-key:"))
                        .noneMatch(line -> line.contains("base:"));
            }
        }

        @Test
        @DisplayName("this repository's own source compiles in no mainframe dataset name")
        void theRepositorySourceCarriesNoDatasetNameLiteral() {
            String code = codeOnly(read(repositoryFile(REPOSITORY_SOURCE_PATH)));

            assertThat(code)
                    .as("a dataset name in Java is a deployment decision compiled into a class; the "
                            + "name belongs to application.yml and to nothing else")
                    .doesNotContain(MAINFRAME_DATASET_PREFIX)
                    .doesNotContain(".VSAM.KSDS")
                    .doesNotContain(".VSAM.AIX.PATH");

            assertThat(code).contains("\"" + AccountRepository.CICS_FILE_NAME + "\"");
            assertThat(code).contains("\"" + AccountRepository.BATCH_DD_NAME + "\"");
        }

        @Test
        @DisplayName("the comment-stripping scan would still catch a name compiled into code")
        void theDatasetNameScanIsNotVacuous() {
            String withProse = "/** Reaches AWS.M2.CARDDEMO.ACCTDATA.VSAM.KSDS. */\n"
                    + "// also AWS.M2.CARDDEMO.CARDDATA.VSAM.KSDS\n"
                    + "String key = \"ACCTDAT\";\n";
            String withLiteral = "String dsname = \"AWS.M2.CARDDEMO.ACCTDATA.VSAM.KSDS\";\n";

            assertThat(codeOnly(withProse)).doesNotContain(MAINFRAME_DATASET_PREFIX);
            assertThat(codeOnly(withProse)).contains("\"ACCTDAT\"");
            assertThat(codeOnly(withLiteral)).contains(MAINFRAME_DATASET_PREFIX);
        }

        @Test
        @DisplayName("both binding keys resolve one dataset name, and the configuration is where it "
                + "lives")
        void theDatasetNameIsResolvedFromConfiguration() {
            List<String> cicsStanza = datasetStanza(AccountRepository.CICS_FILE_NAME);
            List<String> batchStanza = datasetStanza(AccountRepository.BATCH_DD_NAME);

            assertThat(cicsStanza).anyMatch(line -> line.contains("dsname:"));
            assertThat(batchStanza).anyMatch(line -> line.contains("dsname:"));
            assertThat(dsnameOf(cicsStanza))
                    .as("the CICS file and the batch DD are two names for one dataset")
                    .isEqualTo(dsnameOf(batchStanza));
            assertThat(cicsStanza).anyMatch(line -> line.contains("record-length: "
                    + AccountRecord.RECORD_LENGTH));
            assertThat(batchStanza).anyMatch(line -> line.contains("record-length: "
                    + AccountRecord.RECORD_LENGTH));
            assertThat(cicsStanza).anyMatch(line -> line.contains("copybook: CVACT01Y"));

            assertThat(repository(new JdbcTemplate()).datasetName()).isEqualTo(TEST_DSNAME);
        }

        private String dsnameOf(List<String> stanza) {
            String configured = stanza.stream()
                    .filter(line -> line.contains("dsname:"))
                    .map(line -> line.substring(line.indexOf("dsname:") + "dsname:".length()).strip())
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                            "A dataset stanza with no dsname configures nothing"));

            assertThat(configured)
                    .as("every dataset name is overridable at deployment time")
                    .startsWith("${CARDDEMO_DATASET_")
                    .endsWith("}");
            return configured.substring(configured.indexOf(':') + 1, configured.length() - 1);
        }
    }

    @Nested
    @DisplayName("The DISCGRP access path and the meaning of '23'")
    class DisclosureGroupAccessPathTests {
        @Test
        @DisplayName("the disclosure-group read is not a method on this repository - it belongs to "
                + "DisclosureGroupRepository, which is a dataset of its own")
        void theDisclosureGroupReadIsNotOnThisRepository() {
            Set<String> names = new LinkedHashSet<>(publicMethodNamesOf(AccountRepository.class));
            names.addAll(publicMethodNamesOf(AccountFile.class));

            assertThat(names).noneMatch(name -> {
                String lower = name.toLowerCase(Locale.ROOT);
                return lower.contains("discgrp") || lower.contains("disclosure")
                        || lower.contains("intrate") || lower.contains("interestrate");
            });

            // ACCTDAT and DISCGRP are two base datasets, so they are two repositories - one per base
            // dataset is the rule the plan's twelve-repository count is built on (AAP 0.3.5, gate G10).
            // Asserting that the sibling EXISTS, and is a @Repository, is what keeps the disclosure-group
            // access path from drifting back into this class.
            assertThatNoException().isThrownBy(() -> Class.forName(
                    "com.vsergeychik.carddemo.account.DisclosureGroupRepository"));
            assertThat(DisclosureGroupRepository.class
                    .isAnnotationPresent(org.springframework.stereotype.Repository.class))
                    .as("DISCGRP is a base dataset, so its access path is a @Repository of its own")
                    .isTrue();
        }

        @Test
        @DisplayName("the record is exactly 50 bytes with a 16-byte key and the rate at offset 16")
        void theGeometryIsTheCopybookGeometry() {
            assertThat(DisclosureGroupRecord.RECORD_LENGTH).isEqualTo(FIFTY);
            assertThat(DisclosureGroupRecord.DIS_ACCT_GROUP_ID_OFFSET).isZero();
            assertThat(DisclosureGroupRecord.DIS_ACCT_GROUP_ID_LENGTH).isEqualTo(10);
            assertThat(DisclosureGroupRecord.DIS_TRAN_TYPE_CD_OFFSET).isEqualTo(10);
            assertThat(DisclosureGroupRecord.DIS_TRAN_TYPE_CD_LENGTH).isEqualTo(2);
            assertThat(DisclosureGroupRecord.DIS_TRAN_CAT_CD_OFFSET).isEqualTo(12);
            assertThat(DisclosureGroupRecord.DIS_TRAN_CAT_CD_LENGTH).isEqualTo(4);
            assertThat(DisclosureGroupRecord.DIS_GROUP_KEY_OFFSET).isZero();
            assertThat(DisclosureGroupRecord.DIS_GROUP_KEY_LENGTH)
                    .as("DIS-GROUP-KEY spans bytes 0 to 15 - sixteen, not seventeen")
                    .isEqualTo(SIXTEEN)
                    .isNotEqualTo(SEVENTEEN_BYTE_KEY_WIDTH);
            assertThat(DisclosureGroupRecord.DIS_INT_RATE_OFFSET)
                    .as("S9(04)V99 begins where the key ends")
                    .isEqualTo(SIXTEEN);
            assertThat(DisclosureGroupRecord.DIS_INT_RATE_LENGTH)
                    .as("four integer digits plus two decimals, the sign overpunched into the last byte")
                    .isEqualTo(SIX);
            assertThat(DisclosureGroupRecord.DIS_INT_RATE_SCALE)
                    .isEqualTo(CobolDecimal.MONETARY_SCALE);
            assertThat(DisclosureGroupRecord.FILLER_OFFSET).isEqualTo(22);
            assertThat(DisclosureGroupRecord.FILLER_LENGTH)
                    .isEqualTo(DISCLOSURE_GROUP_FILLER_WIDTH);

            assertThat(DisclosureGroupRecord.DIS_GROUP_KEY_LENGTH
                    + DisclosureGroupRecord.DIS_INT_RATE_LENGTH
                    + DisclosureGroupRecord.FILLER_LENGTH).isEqualTo(FIFTY);
        }

        @Test
        @DisplayName("fixture row 1 decodes field for field at its copybook offsets")
        void rowOneDecodesFieldForFieldAtItsOffsets() {
            List<String> rows = disclosureGroupRows();
            String row = rows.get(0);

            assertThat(rows).hasSize(DISCLOSURE_GROUP_FIXTURE_RECORDS);
            assertThat(row).isEqualTo(DISCLOSURE_GROUP_ROW_1).hasSize(FIFTY);
            assertThat(row.substring(0, 10)).isEqualTo("A000000000");
            assertThat(row.substring(10, 12)).isEqualTo("01");
            assertThat(row.substring(12, SIXTEEN)).isEqualTo("0001");
            assertThat(row.substring(SIXTEEN, SIXTEEN + SIX)).isEqualTo(ROW_1_RATE_IMAGE);
            assertThat(row.substring(22)).hasSize(DISCLOSURE_GROUP_FILLER_WIDTH);

            DisclosureGroupRecord record = DisclosureGroupRecord.decode(row, ASCII);

            assertThat(record.recordLength()).isEqualTo(FIFTY);
            assertThat(record.disAcctGroupId()).isEqualTo("A000000000");
            assertThat(record.disTranTypeCd()).isEqualTo("01");
            assertThat(record.disTranCatCd()).isEqualTo(1);
            assertThat(record.disTranCatCdImage()).isEqualTo("0001");
            assertThat(record.disGroupKey()).hasSize(SIXTEEN).isEqualTo("A000000000010001");
            assertThat(record.disGroupKeyBytes()).hasSize(SIXTEEN);
            assertThat(record.disIntRateImage()).isEqualTo(ROW_1_RATE_IMAGE);
            assertThat(record.disIntRate())
                    .isEqualByComparingTo(FIFTEEN_PERCENT)
                    .satisfies(rate -> assertThat(rate.scale()).isEqualTo(CobolDecimal.MONETARY_SCALE));
            assertThat(record.disIntRateIsZero()).isFalse();
            assertThat(record.disIntRateIsNotZero()).isTrue();
        }

        @Test
        @DisplayName("every one of the 51 rows re-encodes to the bytes it was decoded from")
        void everyRowReEncodesToItsStoredBytes() {
            for (String row : disclosureGroupRows()) {
                assertThat(row).hasSize(FIFTY);
                DisclosureGroupRecord record = DisclosureGroupRecord.decode(row, ASCII);

                assertThat(record.encodeToString()).isEqualTo(row);
                assertThat(record.encode()).hasSize(FIFTY);
                assertThat(record.filler())
                        .hasSize(DISCLOSURE_GROUP_FILLER_WIDTH)
                        .isEqualTo(row.substring(22));
                assertThat(record.fillerBytes()).hasSize(DISCLOSURE_GROUP_FILLER_WIDTH);
            }
        }

        @Test
        @DisplayName("a freshly constructed record emits 28 FILLER spaces and still totals 50 bytes")
        void aFreshRecordEmitsItsFillerAsSpaces() {
            DisclosureGroupRecord fresh = new DisclosureGroupRecord(ASCII);
            fresh.disAcctGroupId("A000000000");
            fresh.disTranTypeCd("01");
            fresh.disTranCatCd(1);
            fresh.disIntRate(FIFTEEN_PERCENT);

            assertThat(fresh.filler())
                    .as("FILLER is a declared span written as spaces, not an implicit gap")
                    .isEqualTo(" ".repeat(DISCLOSURE_GROUP_FILLER_WIDTH));
            assertThat(fresh.encodeToString())
                    .hasSize(FIFTY)
                    .startsWith("A000000000010001" + ROW_1_RATE_IMAGE)
                    .endsWith(" ".repeat(DISCLOSURE_GROUP_FILLER_WIDTH));
            assertThat(fresh.encode()).hasSize(FIFTY);
            assertThat(fresh.disIntRate()).isEqualByComparingTo(FIFTEEN_PERCENT);
        }

        @Test
        @DisplayName("a 17-byte key produces a 50-byte row the width check accepts and the rate check "
                + "rejects")
        void aSeventeenByteKeyIsCaughtByTheOffsetAndNotByTheWidth() {
            String row = DISCLOSURE_GROUP_ROW_1;
            assertThat(row.substring(SEVENTEEN_BYTE_KEY_WIDTH, SEVENTEEN_BYTE_KEY_WIDTH + SIX))
                    .as("read one byte late, row 1's rate span is not a rate")
                    .isEqualTo(ROW_1_RATE_IMAGE_UNDER_A_17_BYTE_KEY)
                    .isNotEqualTo(ROW_1_RATE_IMAGE);

            String misLaid = row.substring(0, SIXTEEN) + "0" + ROW_1_RATE_IMAGE
                    + "0".repeat(DISCLOSURE_GROUP_FILLER_WIDTH - 1);
            assertThat(misLaid)
                    .as("the mis-laid row is exactly as wide as a correct one, so G19 admits it")
                    .hasSize(FIFTY);

            DisclosureGroupRecord misread = DisclosureGroupRecord.decode(misLaid, ASCII);

            assertThat(misread.disIntRateImage())
                    .as("the true span at offset 16 now holds the wrong six bytes")
                    .isNotEqualTo(ROW_1_RATE_IMAGE);
            assertThat(misread.disIntRate())
                    .as("a 15.00 APR read one byte late is a different rate entirely, and nothing about "
                            + "the record's width would have said so")
                    .isNotEqualByComparingTo(FIFTEEN_PERCENT);
            assertThat(misread.disGroupKey())
                    .as("the key it does read is 16 bytes, and not the 17 the adapter believed in")
                    .hasSize(SIXTEEN);
        }

        @Test
        @DisplayName("MOVE 'DEFAULT' right-pads a seven-character literal into the ten-byte group id")
        void theDefaultLiteralIsRightPaddedToTen() {
            assertThat(DisclosureGroupRecord.DEFAULT_ACCT_GROUP_ID)
                    .as("the literal in the COBOL is seven characters")
                    .isEqualTo("DEFAULT")
                    .hasSize(7);

            DisclosureGroupRecord record = new DisclosureGroupRecord(ASCII);
            record.disAcctGroupId(DisclosureGroupRecord.DEFAULT_ACCT_GROUP_ID);

            assertThat(record.disAcctGroupId())
                    .isEqualTo("DEFAULT   ")
                    .hasSize(DisclosureGroupRecord.DIS_ACCT_GROUP_ID_LENGTH);
            assertThat(record.encodeToString().substring(0,
                    DisclosureGroupRecord.DIS_ACCT_GROUP_ID_LENGTH)).isEqualTo("DEFAULT   ");

            List<String> defaultRows = disclosureGroupRows().stream()
                    .filter(row -> row.startsWith("DEFAULT"))
                    .toList();
            assertThat(defaultRows)
                    .as("the DEFAULT group is present in the fixture, so the fallback has somewhere to "
                            + "land")
                    .isNotEmpty();
            assertThat(defaultRows).allSatisfy(row -> assertThat(
                    DisclosureGroupRecord.decode(row, ASCII).disAcctGroupId())
                    .isEqualTo("DEFAULT   "));
        }

        @Test
        @DisplayName("'23' is fatal on the account master, benign on the disclosure group, and fatal "
                + "again on the DEFAULT re-read")
        void theSameStatusMeansThreeDifferentThings() {
            assertThat(FileStatus.isNotFound(FileStatus.NOT_FOUND)).isTrue();

            assertThat(FileStatus.isOk(FileStatus.NOT_FOUND)).isFalse();
            assertThat(ReadResult.notFound().applResult())
                    .as("what the account master's caller would abend with")
                    .isEqualTo(AccountRepository.APPL_RESULT_FATAL);

            assertThat(FileStatus.isOkOrNotFound(FileStatus.NOT_FOUND))
                    .as("on DISCGRP, '23' means 'try the DEFAULT group', not 'abend'")
                    .isTrue();
            assertThat(FileStatus.isOkOrNotFound(FileStatus.OK)).isTrue();

            assertThat(FileStatus.isOk(FileStatus.NOT_FOUND)).isFalse();

            assertThat(FileStatus.isOkOrNotFound(FileStatus.END_OF_FILE)).isFalse();
            assertThat(FileStatus.isOkOrNotFound(FileStatus.DUPLICATE)).isFalse();
            assertThat(FileStatus.isOkOrNotFound(AccountRepository.PERMANENT_ERROR_STATUS)).isFalse();
        }

        @Test
        @DisplayName("a not-found read returns its outcome and abends nothing")
        void aNotFoundReadReturnsRatherThanThrows() {
            AccountRepository repository = repository(seeded(fixtureRows()));

            assertThatNoException().isThrownBy(() -> repository.readByKey(ABSENT_ACCT_ID));
            ReadResult result = repository.readByKey(ABSENT_ACCT_ID);

            assertThat(result.isNotFound()).isTrue();
            assertThat(result.status()).isEqualTo(FileStatus.NOT_FOUND);
            assertThat(result.account()).isEmpty();

            AbendException callersAbend = AbendException.standard("CBACT04C",
                    AbendException.RETURN_CODE_IO_ERROR);
            assertThat(callersAbend.getReturnCode())
                    .isEqualTo(result.applResult())
                    .isEqualTo(AccountRepository.APPL_RESULT_FATAL);
            assertThat(callersAbend.getAbendCode()).hasValue(AbendException.STANDARD_ABEND_CODE);
        }
    }

    @Nested
    @DisplayName("The account record's fields sit exactly where CVACT01Y puts them")
    class AccountRecordOffsetTests {
        @Test
        @DisplayName("every field of a stored record decodes from its own copybook offset, and the "
                + "record re-encodes byte for byte")
        void everyFieldDecodesFromItsCopybookOffset() {
            String row = fixtureRows().get(0);
            assertThat(row).hasSize(THREE_HUNDRED);

            AccountRecord account = AccountRecord.decode(row, ASCII);

            assertThat(account.rawAcctId())
                    .isEqualTo(spanOf(row, AccountRecord.ACCT_ID_OFFSET,
                            AccountRecord.ACCT_ID_LENGTH));
            assertThat(account.rawAcctActiveStatus())
                    .isEqualTo(spanOf(row, AccountRecord.ACCT_ACTIVE_STATUS_OFFSET,
                            AccountRecord.ACCT_ACTIVE_STATUS_LENGTH));
            assertThat(account.rawAcctCurrBal())
                    .isEqualTo(spanOf(row, AccountRecord.ACCT_CURR_BAL_OFFSET,
                            AccountRecord.ACCT_CURR_BAL_LENGTH));
            assertThat(account.rawAcctCreditLimit())
                    .isEqualTo(spanOf(row, AccountRecord.ACCT_CREDIT_LIMIT_OFFSET,
                            AccountRecord.ACCT_CREDIT_LIMIT_LENGTH));
            assertThat(account.rawAcctCashCreditLimit())
                    .isEqualTo(spanOf(row, AccountRecord.ACCT_CASH_CREDIT_LIMIT_OFFSET,
                            AccountRecord.ACCT_CASH_CREDIT_LIMIT_LENGTH));
            assertThat(account.rawAcctOpenDate())
                    .isEqualTo(spanOf(row, AccountRecord.ACCT_OPEN_DATE_OFFSET,
                            AccountRecord.ACCT_OPEN_DATE_LENGTH));
            assertThat(account.rawAcctExpiraionDate())
                    .isEqualTo(spanOf(row, AccountRecord.ACCT_EXPIRAION_DATE_OFFSET,
                            AccountRecord.ACCT_EXPIRAION_DATE_LENGTH));
            assertThat(account.rawAcctReissueDate())
                    .isEqualTo(spanOf(row, AccountRecord.ACCT_REISSUE_DATE_OFFSET,
                            AccountRecord.ACCT_REISSUE_DATE_LENGTH));
            assertThat(account.rawAcctCurrCycCredit())
                    .isEqualTo(spanOf(row, AccountRecord.ACCT_CURR_CYC_CREDIT_OFFSET,
                            AccountRecord.ACCT_CURR_CYC_CREDIT_LENGTH));
            assertThat(account.rawAcctCurrCycDebit())
                    .isEqualTo(spanOf(row, AccountRecord.ACCT_CURR_CYC_DEBIT_OFFSET,
                            AccountRecord.ACCT_CURR_CYC_DEBIT_LENGTH));
            assertThat(account.rawAcctAddrZip())
                    .isEqualTo(spanOf(row, AccountRecord.ACCT_ADDR_ZIP_OFFSET,
                            AccountRecord.ACCT_ADDR_ZIP_LENGTH));
            assertThat(account.rawAcctGroupId())
                    .isEqualTo(spanOf(row, AccountRecord.ACCT_GROUP_ID_OFFSET,
                            AccountRecord.ACCT_GROUP_ID_LENGTH));
            assertThat(account.getFiller())
                    .isEqualTo(spanOf(row, AccountRecord.FILLER_OFFSET,
                            AccountRecord.FILLER_LENGTH));

            assertThat(AccountRecord.ACCT_ADDR_ZIP_OFFSET)
                    .isLessThan(AccountRecord.ACCT_GROUP_ID_OFFSET);
            assertThat(AccountRecord.ACCT_OPEN_DATE_OFFSET)
                    .isLessThan(AccountRecord.ACCT_EXPIRAION_DATE_OFFSET);
            assertThat(AccountRecord.ACCT_EXPIRAION_DATE_OFFSET)
                    .isLessThan(AccountRecord.ACCT_REISSUE_DATE_OFFSET);
            assertThat(AccountRecord.ACCT_CURR_CYC_CREDIT_OFFSET)
                    .isLessThan(AccountRecord.ACCT_CURR_CYC_DEBIT_OFFSET);

            assertThat(account.toFixedWidthString()).isEqualTo(row).hasSize(THREE_HUNDRED);
            assertThat(account.toByteArray()).hasSize(THREE_HUNDRED);
            assertThat(account.keyImage())
                    .isEqualTo(keyImageOf(row))
                    .hasSize(AccountRecord.KEY_LENGTH);
        }

        private String spanOf(String row, int offset, int length) {
            return row.substring(offset, offset + length);
        }
    }

    @Nested
    @DisplayName("The status matrix - every outcome, every call site")
    class StatusMatrixTests {
        @ParameterizedTest(name = "read status ''{0}'' -> {1}, APPL-RESULT {2}, RESP {3}, {4}")
        @CsvSource({
            "10, END_OF_FILE, 16, 20, isEndOfFile",
            "22, DUPLICATE,   12, -1, none",
            "23, NOT_FOUND,   12, 13, isNotFound",
            "37, OTHER,       12, -1, isOther"
        })
        @DisplayName("a read reports every status with its classification, APPL-RESULT and CICS pair")
        void everyReadStatusReportsItsWholeShape(String status, Outcome expectedOutcome,
                int expectedApplResult, int expectedCicsResp, String expectedPredicate) {
            ReadResult result = ReadResult.of(status);

            assertThat(result.status()).isEqualTo(status);
            assertThat(result.outcome()).isEqualTo(expectedOutcome);
            assertThat(result.applResult()).isEqualTo(expectedApplResult);
            assertThat(result.account())
                    .as("no read but the '00' arm carries a record")
                    .isEmpty();
            if (expectedCicsResp < 0) {
                assertThat(result.cicsResp()).isEmpty();
            } else {
                assertThat(result.cicsResp()).hasValue(expectedCicsResp);
            }
            assertThat(result.cicsResp2())
                    .as("no reason code is reported unless a backend produced one")
                    .isEqualTo(FileStatus.NO_REASON_CODE);

            assertThat(trueNamesAmong(List.of("isFound", "isEndOfFile", "isNotFound", "isOther"),
                    List.of(result.isFound(), result.isEndOfFile(), result.isNotFound(),
                            result.isOther())))
                    .isEqualTo("none".equals(expectedPredicate)
                            ? List.of() : List.of(expectedPredicate));
        }

        @ParameterizedTest(name = "rewrite status ''{0}'' -> {1}, APPL-RESULT {2}, RESP {3}, {4}")
        @CsvSource({
            "22, DUPLICATE, 12, -1, none",
            "23, NOT_FOUND, 12, 13, isNotFound",
            "37, OTHER,     12, -1, isOther"
        })
        @DisplayName("a rewrite reports every status with its classification, APPL-RESULT and CICS pair")
        void everyRewriteStatusReportsItsWholeShape(String status, Outcome expectedOutcome,
                int expectedApplResult, int expectedCicsResp, String expectedPredicate) {
            WriteResult result = WriteResult.of(status);

            assertThat(result.status()).isEqualTo(status);
            assertThat(result.outcome()).isEqualTo(expectedOutcome);
            assertThat(result.applResult()).isEqualTo(expectedApplResult);
            if (expectedCicsResp < 0) {
                assertThat(result.cicsResp()).isEmpty();
            } else {
                assertThat(result.cicsResp()).hasValue(expectedCicsResp);
            }

            assertThat(trueNamesAmong(List.of("isWritten", "isNotFound", "isOther"),
                    List.of(result.isWritten(), result.isNotFound(), result.isOther())))
                    .isEqualTo("none".equals(expectedPredicate)
                            ? List.of() : List.of(expectedPredicate));
        }

        @Test
        @DisplayName("the permanent-error status is the '9' convention and reaches WHEN OTHER")
        void thePermanentErrorStatusReachesTheCatchAll() {
            assertThat(AccountRepository.PERMANENT_ERROR_STATUS)
                    .hasSize(FileStatus.STATUS_LENGTH)
                    .startsWith("9");
            assertThat(ReadResult.of(AccountRepository.PERMANENT_ERROR_STATUS).outcome())
                    .isEqualTo(Outcome.OTHER);
            assertThat(WriteResult.of(AccountRepository.PERMANENT_ERROR_STATUS).outcome())
                    .isEqualTo(Outcome.OTHER);
            assertThat(FileStatus.toStatusImage(AccountRepository.PERMANENT_ERROR_STATUS))
                    .as("the four-digit image a job's log shows")
                    .hasSize(FileStatus.STATUS_IMAGE_LENGTH);
        }

        @ParameterizedTest(name = "RESP {0} -> {1}, batch status {2}")
        @CsvSource({
            "0,    OK,          00",
            "13,   NOT_FOUND,   23",
            "14,   DUPLICATE,   22",
            "15,   DUPLICATE,   22",
            "20,   END_OF_FILE, 10",
            "16,   OTHER,       none",
            "19,   OTHER,       none",
            "22,   OTHER,       none",
            "4242, OTHER,       none"
        })
        @DisplayName("every CICS response classifies, and only the documented four translate")
        void everyCicsResponseClassifies(int cicsResp, Outcome expectedOutcome,
                String expectedBatchStatus) {
            assertThat(FileStatus.outcomeOfCicsResp(cicsResp)).isEqualTo(expectedOutcome);

            if ("none".equals(expectedBatchStatus)) {
                assertThat(FileStatus.batchStatusOfCicsResp(cicsResp)).isEmpty();
            } else {
                assertThat(FileStatus.batchStatusOfCicsResp(cicsResp)).hasValue(expectedBatchStatus);
                assertThat(FileStatus.outcomeOfStatus(expectedBatchStatus))
                        .as("both directions must classify a value the same way")
                        .isEqualTo(expectedOutcome);
            }
        }

        @Test
        @DisplayName("COACTVWC's EVALUATE has three arms, so everything but NORMAL and NOTFND is OTHER")
        void theOnlineEvaluateHasExactlyThreeArms() {
            assertThat(onlineArmFor(FileStatus.NORMAL)).isEqualTo("NORMAL");
            assertThat(onlineArmFor(FileStatus.NOTFND)).isEqualTo("NOTFND");
            for (int unenumerated : List.of(FileStatus.ENDFILE, FileStatus.DUPREC, FileStatus.DUPKEY,
                    FileStatus.INVREQ, FileStatus.NOTOPEN, FileStatus.LENGERR, 4242)) {
                assertThat(onlineArmFor(unenumerated))
                        .as("RESP %d reaches WHEN OTHER, which is where COACTVWC builds its file-error "
                                + "message", unenumerated)
                        .isEqualTo("OTHER");
            }
        }

        @Test
        @DisplayName("the WHEN OTHER arm receives RESP and RESP2 as two distinct values")
        void theOtherArmReceivesBothRespAndResp2() {
            CicsResponse reported = CicsResponse.reported(FileStatus.LENGERR, 42);
            ReadResult result = ReadResult.of(AccountRepository.PERMANENT_ERROR_STATUS, reported);

            assertThat(result.cicsResp()).hasValue(FileStatus.LENGERR);
            assertThat(result.cicsResp2()).isEqualTo(42);
            assertThat(result.status())
                    .as("the batch guard chain still sees a permanent error, not the CICS number")
                    .isEqualTo(AccountRepository.PERMANENT_ERROR_STATUS);

            WriteResult write = WriteResult.of(AccountRepository.PERMANENT_ERROR_STATUS,
                    CicsResponse.reported(FileStatus.INVREQ, 7));
            assertThat(write.cicsResp()).hasValue(FileStatus.INVREQ);
            assertThat(write.cicsResp2()).isEqualTo(7);
        }

        @Test
        @DisplayName("every operation reports the outcomes it can actually reach, and no others")
        void everyOperationReportsItsReachableOutcomes() {
            List<String> rows = fixtureRows();
            JdbcTemplate template = seeded(rows);
            AccountRepository repository = repository(template);
            String firstKey = keyImageOf(rows.get(0));
            long firstId = Long.parseLong(firstKey);

            try (AccountFile file = repository.open(OpenMode.INPUT)) {
                assertThat(file.openStatus()).isEqualTo(FileStatus.OK);
                assertThat(file.openOutcome()).isEqualTo(Outcome.OK);

                assertThat(drain(file, FIXTURE_RECORDS + 1)).hasSize(FIXTURE_RECORDS);
                assertThat(file.readNext().status()).isEqualTo(FileStatus.END_OF_FILE);

                assertThat(file.closeFile()).isEqualTo(FileStatus.OK);
            }

            assertThat(repository.readByKey(firstId).status()).isEqualTo(FileStatus.OK);
            assertThat(repository.readByKey(ABSENT_ACCT_ID).status()).isEqualTo(FileStatus.NOT_FOUND);

            String absentKey = AccountRecord.keyImage(ABSENT_ACCT_ID, ASCII);
            transactionOver(template).executeWithoutResult(status -> {
                assertThat(repository.readForUpdate(firstKey).status()).isEqualTo(FileStatus.OK);
                assertThat(repository.readForUpdate(absentKey).status())
                        .isEqualTo(FileStatus.NOT_FOUND);
            });
            assertThatIllegalStateException().isThrownBy(() -> repository.readForUpdate(firstKey));

            AccountRecord present = repository.readByKey(firstId).account().orElseThrow();
            assertThat(withUnitOfWork(() -> repository.rewrite(present).status()))
                    .isEqualTo(FileStatus.OK);
            AccountRecord absent = new AccountRecord(ASCII);
            absent.setAcctId(ABSENT_ACCT_ID);
            assertThat(withUnitOfWork(() -> repository.rewrite(absent).status()))
                    .isEqualTo(FileStatus.NOT_FOUND);
            assertThatIllegalStateException().isThrownBy(() -> repository.rewrite(present))
                    .withMessageContaining("no transaction is open on this thread");
        }

        @Test
        @DisplayName("every operation reports the permanent error when the backend refuses it")
        void everyOperationReportsThePermanentError() throws SQLException {
            AccountRepository unreachableRepository = repository(unreachable());
            AccountRecord blank = new AccountRecord(ASCII);
            blank.setAcctId(ABSENT_ACCT_ID);

            assertThat(unreachableRepository.readByKey(ABSENT_ACCT_ID).status())
                    .isEqualTo(AccountRepository.PERMANENT_ERROR_STATUS);
            assertThat(withUnitOfWork(() -> unreachableRepository.rewrite(blank).status()))
                    .isEqualTo(AccountRepository.PERMANENT_ERROR_STATUS);
            assertThat(withUnitOfWork(() -> unreachableRepository
                    .readForUpdate(AccountRecord.keyImage(ABSENT_ACCT_ID, ASCII)).status()))
                    .isEqualTo(AccountRepository.PERMANENT_ERROR_STATUS);

            try (AccountFile file = unreachableRepository.open(OpenMode.INPUT)) {
                assertThat(file.openStatus()).isEqualTo(AccountRepository.PERMANENT_ERROR_STATUS);
                assertThat(file.openOutcome()).isEqualTo(Outcome.OTHER);
                assertThat(file.readNext().status())
                        .isEqualTo(AccountRepository.PERMANENT_ERROR_STATUS);
            }

            AccountRepository refusing = repository(describingThenRefusing());
            assertThat(refusing.readByKey(ABSENT_ACCT_ID).status())
                    .isEqualTo(AccountRepository.PERMANENT_ERROR_STATUS);
        }

        @Test
        @DisplayName("the '00' arm is the one predicate a successful outcome answers")
        void theSuccessfulArmAnswersExactlyOnePredicate() {
            ReadResult read = ReadResult.found(new AccountRecord(ASCII));
            assertThat(trueNamesAmong(List.of("isFound", "isEndOfFile", "isNotFound", "isOther"),
                    List.of(read.isFound(), read.isEndOfFile(), read.isNotFound(), read.isOther())))
                    .containsExactly("isFound");
            assertThat(read.status()).isEqualTo(FileStatus.OK);
            assertThat(read.applResult()).isEqualTo(FileStatus.APPL_AOK);

            WriteResult write = WriteResult.written();
            assertThat(trueNamesAmong(List.of("isWritten", "isNotFound", "isOther"),
                    List.of(write.isWritten(), write.isNotFound(), write.isOther())))
                    .containsExactly("isWritten");
            assertThat(write.status()).isEqualTo(FileStatus.OK);
            assertThat(write.applResult()).isEqualTo(FileStatus.APPL_AOK);
        }

        private List<String> trueNamesAmong(List<String> names, List<Boolean> answers) {
            List<String> claimed = new ArrayList<>();
            for (int index = 0; index < names.size(); index++) {
                if (answers.get(index)) {
                    claimed.add(names.get(index));
                }
            }
            return claimed;
        }

        private String onlineArmFor(int cicsResp) {
            if (cicsResp == FileStatus.NORMAL) {
                return "NORMAL";
            }
            if (cicsResp == FileStatus.NOTFND) {
                return "NOTFND";
            }
            return "OTHER";
        }
    }

    @Nested
    @DisplayName("Numeric parity - scale 2, truncation toward zero, no binary floating point")
    class MonetaryParityTests {
        @Test
        @DisplayName("every monetary field of all 50 fixture records reports scale exactly 2")
        void everyMonetaryFieldOfEveryFixtureRecordReportsScaleTwo() {
            List<String> rows = fixtureRows();
            assertThat(rows).hasSize(FIXTURE_RECORDS);

            for (String row : rows) {
                AccountRecord account = AccountRecord.decode(row, ASCII);
                List<BigDecimal> amounts = List.of(
                        account.getAcctCurrBal(),
                        account.getAcctCreditLimit(),
                        account.getAcctCashCreditLimit(),
                        account.getAcctCurrCycCredit(),
                        account.getAcctCurrCycDebit());

                assertThat(amounts).allSatisfy(amount -> assertThat(amount.scale())
                        .describedAs("a PIC S9(10)V99 field always reads at scale 2, zero included")
                        .isEqualTo(CobolDecimal.MONETARY_SCALE));
            }
        }

        @Test
        @DisplayName("the module's one rounding mode is truncation toward zero")
        void theOnlyRoundingModeIsDown() {
            assertThat(CobolDecimal.COBOL_ROUNDING)
                    .as("ROUNDED appears zero times in all 28 programs, so COBOL truncates on store")
                    .isEqualTo(RoundingMode.DOWN)
                    .isNotEqualTo(RoundingMode.HALF_UP)
                    .isNotEqualTo(RoundingMode.HALF_EVEN);
            assertThat(CobolDecimal.MONETARY_SCALE).isEqualTo(2);
        }

        @Test
        @DisplayName("storing an over-precise amount truncates toward zero rather than rounding")
        void storingTruncatesTowardZero() {
            AccountRecord account = new AccountRecord(ASCII);

            account.setAcctCurrBal(new BigDecimal("12.349"));
            assertThat(account.getAcctCurrBal()).isEqualByComparingTo(new BigDecimal("12.34"));

            account.setAcctCurrBal(new BigDecimal("12.345"));
            assertThat(account.getAcctCurrBal()).isEqualByComparingTo(new BigDecimal("12.34"));

            account.setAcctCreditLimit(new BigDecimal("-12.349"));
            assertThat(account.getAcctCreditLimit()).isEqualByComparingTo(new BigDecimal("-12.34"));

            assertThat(CobolDecimal.store(new BigDecimal("12.345"), CobolDecimal.MONETARY_SCALE))
                    .isEqualByComparingTo(new BigDecimal("12.34"));
            assertThat(CobolDecimal.storeMonetary(new BigDecimal("-0.009")))
                    .isEqualByComparingTo(new BigDecimal("0.00"));
        }

        @Test
        @DisplayName("the 1050-UPDATE-ACCOUNT sequence stores a truncated balance and a 300-byte record")
        void theAccountBreakSequenceStoresATruncatedBalance() {
            List<String> rows = fixtureRows();
            AccountRepository repository = repository(seeded(rows));
            long acctId = Long.parseLong(keyImageOf(rows.get(0)));
            AccountRecord account = repository.readByKey(acctId).account().orElseThrow();
            BigDecimal openingBalance = account.getAcctCurrBal();
            BigDecimal totalInterest = new BigDecimal("1.239");

            account.setAcctCurrBal(CobolDecimal.add(openingBalance, totalInterest,
                    CobolDecimal.MONETARY_SCALE));
            account.zeroAcctCurrCycCredit();
            account.zeroAcctCurrCycDebit();

            assertThat(withUnitOfWork(() -> repository.rewrite(account)).isWritten()).isTrue();

            AccountRecord reread = repository.readByKey(acctId).account().orElseThrow();
            assertThat(reread.getAcctCurrBal())
                    .as("1.239 added to the opening balance truncates to 1.23, not 1.24")
                    .isEqualByComparingTo(openingBalance.add(new BigDecimal("1.23")))
                    .satisfies(balance -> assertThat(balance.scale())
                            .isEqualTo(CobolDecimal.MONETARY_SCALE));
            assertThat(reread.getAcctCurrCycCredit()).isEqualByComparingTo(new BigDecimal("0.00"));
            assertThat(reread.getAcctCurrCycDebit()).isEqualByComparingTo(new BigDecimal("0.00"));
            assertThat(reread.getAcctCurrCycCredit().scale()).isEqualTo(CobolDecimal.MONETARY_SCALE);
            assertThat(reread.toFixedWidthString()).hasSize(THREE_HUNDRED);
            assertThat(reread.getFiller()).isEqualTo(" ".repeat(FILLER_WIDTH));
        }

        @Test
        @DisplayName("no binary floating-point type appears anywhere on the access surface")
        void noBinaryFloatingPointAppearsOnTheAccessSurface() {
            List<Class<?>> forbidden = List.of(double.class, float.class, Double.class, Float.class);
            List<String> offenders = new ArrayList<>();

            for (Class<?> type : datasetAccessTypes()) {
                for (Method method : type.getDeclaredMethods()) {
                    if (forbidden.contains(method.getReturnType())) {
                        offenders.add(type.getSimpleName() + "." + method.getName() + " returns "
                                + method.getReturnType().getSimpleName());
                    }
                    for (Class<?> parameter : method.getParameterTypes()) {
                        if (forbidden.contains(parameter)) {
                            offenders.add(type.getSimpleName() + "." + method.getName() + " accepts "
                                    + parameter.getSimpleName());
                        }
                    }
                }
                for (Field field : type.getDeclaredFields()) {
                    if (forbidden.contains(field.getType())) {
                        offenders.add(type.getSimpleName() + "." + field.getName() + " is "
                                + field.getType().getSimpleName());
                    }
                }
            }

            assertThat(offenders)
                    .as("every PIC 9...V.. field is a BigDecimal at its declared scale")
                    .isEmpty();
        }

        @Test
        @DisplayName("the record's own monetary span is twelve bytes with the sign overpunched, not "
                + "thirteen")
        void theMonetarySpanIsTwelveBytes() {
            assertThat(AccountRecord.MONETARY_INTEGER_DIGITS).isEqualTo(10);
            assertThat(AccountRecord.MONETARY_SCALE).isEqualTo(CobolDecimal.MONETARY_SCALE);
            assertThat(AccountRecord.ACCT_CURR_BAL_LENGTH).isEqualTo(12);

            List<String> rows = fixtureRows();
            AccountRecord account = AccountRecord.decode(rows.get(0), ASCII);

            assertThat(account.rawAcctCurrBal()).hasSize(AccountRecord.ACCT_CURR_BAL_LENGTH);
            assertThat(account.rawAcctCreditLimit()).hasSize(AccountRecord.ACCT_CREDIT_LIMIT_LENGTH);
            assertThat(account.rawAcctCashCreditLimit())
                    .hasSize(AccountRecord.ACCT_CASH_CREDIT_LIMIT_LENGTH);
            assertThat(account.rawAcctCurrCycCredit())
                    .hasSize(AccountRecord.ACCT_CURR_CYC_CREDIT_LENGTH);
            assertThat(account.rawAcctCurrCycDebit())
                    .hasSize(AccountRecord.ACCT_CURR_CYC_DEBIT_LENGTH);

            int declared = AccountRecord.ACCT_ID_LENGTH
                    + AccountRecord.ACCT_ACTIVE_STATUS_LENGTH
                    + AccountRecord.ACCT_CURR_BAL_LENGTH
                    + AccountRecord.ACCT_CREDIT_LIMIT_LENGTH
                    + AccountRecord.ACCT_CASH_CREDIT_LIMIT_LENGTH
                    + AccountRecord.ACCT_OPEN_DATE_LENGTH
                    + AccountRecord.ACCT_EXPIRAION_DATE_LENGTH
                    + AccountRecord.ACCT_REISSUE_DATE_LENGTH
                    + AccountRecord.ACCT_CURR_CYC_CREDIT_LENGTH
                    + AccountRecord.ACCT_CURR_CYC_DEBIT_LENGTH
                    + AccountRecord.ACCT_ADDR_ZIP_LENGTH
                    + AccountRecord.ACCT_GROUP_ID_LENGTH
                    + AccountRecord.FILLER_LENGTH;
            assertThat(declared).isEqualTo(THREE_HUNDRED).isEqualTo(AccountRecord.RECORD_LENGTH);

            assertThat(AccountRecord.ACCT_EXPIRAION_DATE_NAME)
                    .as("app/cpy/CVACT01Y.cpy:L11 spells it EXPIRAION; renaming it would break parity")
                    .isEqualTo("ACCT-EXPIRAION-DATE");
            assertThat(AccountRecord.ACCT_ADDR_ZIP_OFFSET)
                    .as("ACCT-ADDR-ZIP precedes ACCT-GROUP-ID - CVACT01Y.cpy:L15 before L16")
                    .isLessThan(AccountRecord.ACCT_GROUP_ID_OFFSET);
        }
    }

}
