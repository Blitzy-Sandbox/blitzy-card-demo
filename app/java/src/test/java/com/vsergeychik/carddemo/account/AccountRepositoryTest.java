package com.vsergeychik.carddemo.account;

import com.vsergeychik.carddemo.account.AccountRepository.OpenMode;
import com.vsergeychik.carddemo.account.AccountRepository.ReadResult;
import com.vsergeychik.carddemo.account.AccountRepository.Statements;
import com.vsergeychik.carddemo.account.AccountRepository.WriteResult;
import com.vsergeychik.carddemo.account.model.AccountRecord;
import com.vsergeychik.carddemo.common.FileStatus;
import com.vsergeychik.carddemo.common.FileStatus.Outcome;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBinding;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBindings;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

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
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
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
                "FB", null, recordLength, "CVACT01Y", keyLength, null, null));
        catalogue.put(AccountRepository.BATCH_DD_NAME, new DatasetBinding(batchDsname, "ksds", false,
                "FB", null, recordLength, "CVACT01Y", keyLength, null, null));
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
        return new AccountRepository(template, validBindings(), ASCII);
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
     * @param repository the repository to browse
     * @param limit      the most reads to perform, so a defect cannot loop for ever
     * @return the record images returned, in the order returned
     */
    private static List<String> drain(AccountRepository repository, int limit) {
        List<String> images = new ArrayList<>();
        for (int read = 0; read < limit; read++) {
            ReadResult result = repository.readNext();
            if (!result.isFound()) {
                break;
            }
            images.add(result.account().orElseThrow().toFixedWidthString());
        }
        return images;
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
            assertThat(repository.openMode()).isEmpty();
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
                    bindings(TEST_DSNAME, TEST_DSNAME, THREE_HUNDRED, ELEVEN), ASCII);

            assertThat(repository.datasetName()).isEqualTo(TEST_DSNAME);
        }

        @Test
        @DisplayName("refuses a declared key length that is not eleven")
        void refusesAnUnexpectedDeclaredKeyLength() {
            DatasetBindings wrongKey = bindings(TEST_DSNAME, TEST_DSNAME, THREE_HUNDRED, 12);

            assertThatIllegalStateException()
                    .isThrownBy(() -> new AccountRepository(new JdbcTemplate(), wrongKey, ASCII))
                    .withMessageContaining("key length")
                    .withMessageContaining("ACCT-ID PIC 9(11)");
        }

        @Test
        @DisplayName("refuses a configured record width that is not the copybook's 300")
        void refusesAnUnexpectedRecordWidth() {
            DatasetBindings wrongWidth = bindings(TEST_DSNAME, TEST_DSNAME, 350, null);

            assertThatIllegalStateException()
                    .isThrownBy(() -> new AccountRepository(new JdbcTemplate(), wrongWidth, ASCII))
                    .withMessageContaining("record length")
                    .withMessageContaining("RECLN 300");
        }

        @Test
        @DisplayName("refuses bindings that name two different datasets for one account master")
        void refusesTwoDifferentDatasets() {
            DatasetBindings split = bindings(TEST_DSNAME, "TEST.OTHER.KSDS", THREE_HUNDRED, null);

            assertThatIllegalStateException()
                    .isThrownBy(() -> new AccountRepository(new JdbcTemplate(), split, ASCII))
                    .withMessageContaining("different datasets");
        }

        @Test
        @DisplayName("refuses an unconfigured DD name, naming the keys that are configured")
        void refusesAnUnconfiguredDdName() {
            DatasetBindings incomplete = new DatasetBindings();
            incomplete.put(AccountRepository.BATCH_DD_NAME, new DatasetBinding(TEST_DSNAME, "ksds",
                    false, "FB", null, THREE_HUNDRED, "CVACT01Y", null, null, null));

            assertThatIllegalStateException()
                    .isThrownBy(() -> new AccountRepository(new JdbcTemplate(), incomplete, ASCII))
                    .withMessageContaining(AccountRepository.CICS_FILE_NAME);
        }

        @ParameterizedTest(name = "dataset name [{0}]")
        @ValueSource(strings = { "", "   " })
        @DisplayName("refuses a blank dataset name")
        void refusesABlankDatasetName(String blank) {
            DatasetBindings blankName = bindings(blank, blank, THREE_HUNDRED, null);

            assertThatIllegalStateException()
                    .isThrownBy(() -> new AccountRepository(new JdbcTemplate(), blankName, ASCII))
                    .withMessageContaining("declares no dataset name");
        }

        @Test
        @DisplayName("refuses an absent dataset name")
        void refusesAnAbsentDatasetName() {
            DatasetBindings absentName = bindings(null, null, THREE_HUNDRED, null);

            assertThatIllegalStateException()
                    .isThrownBy(() -> new AccountRepository(new JdbcTemplate(), absentName, ASCII))
                    .withMessageContaining("declares no dataset name");
        }

        @Test
        @DisplayName("refuses a dataset name carrying a control character")
        void refusesAControlCharacterInTheDatasetName() {
            String corrupt = "TEST.ACCOUNT\u0001KSDS";
            DatasetBindings corruptName = bindings(corrupt, corrupt, THREE_HUNDRED, null);

            assertThatIllegalStateException()
                    .isThrownBy(() -> new AccountRepository(new JdbcTemplate(), corruptName, ASCII))
                    .withMessageContaining("control character");
        }

        @Test
        @DisplayName("refuses a missing collaborator rather than defaulting one")
        void refusesAMissingCollaborator() {
            DatasetBindings catalogue = validBindings();

            assertThatNullPointerException()
                    .isThrownBy(() -> new AccountRepository(null, catalogue, ASCII))
                    .withMessageContaining("JdbcTemplate");
            assertThatNullPointerException()
                    .isThrownBy(() -> new AccountRepository(new JdbcTemplate(), null, ASCII))
                    .withMessageContaining("carddemo.datasets");
            assertThatNullPointerException()
                    .isThrownBy(() -> new AccountRepository(new JdbcTemplate(), catalogue, null))
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
            assertThat(repository.resolvedStatements()).isNull();
        }

        @Test
        @DisplayName("a quote inside a configured name is escaped by repetition, not by removal")
        void aQuoteInsideANameIsEscapedByRepetition() {
            String awkward = "TEST.\"ACCOUNT\".KSDS";
            AccountRepository repository = new AccountRepository(new JdbcTemplate(),
                    bindings(awkward, awkward, THREE_HUNDRED, null), ASCII);

            assertThat(repository.columnProbeSql())
                    .isEqualTo("SELECT * FROM \"TEST.\"\"ACCOUNT\"\".KSDS\" WHERE 1 = 0");
        }

        @Test
        @DisplayName("every statement names the discovered column and states its ordering explicitly")
        void everyStatementNamesTheDiscoveredColumn() {
            AccountRepository repository = repository(seeded(List.of()));

            assertThat(repository.open(OpenMode.INPUT)).isEqualTo(FileStatus.OK);
            Statements statements = repository.resolvedStatements();

            assertThat(statements).isNotNull();
            String dataset = "\"" + TEST_DSNAME + "\"";
            String column = "\"" + RECORD_IMAGE_COLUMN + "\"";
            assertThat(statements.selectFirst())
                    .isEqualTo("SELECT * FROM " + dataset + " ORDER BY " + column + " ASC");
            assertThat(statements.selectNext())
                    .isEqualTo("SELECT * FROM " + dataset + " WHERE " + column + " > ? ORDER BY "
                            + column + " ASC");
            assertThat(statements.selectByKey())
                    .isEqualTo("SELECT * FROM " + dataset + " WHERE " + column + " LIKE ? ESCAPE '\\'");
            assertThat(statements.rewrite())
                    .isEqualTo("UPDATE " + dataset + " SET " + column + " = ? WHERE " + column
                            + " LIKE ? ESCAPE '\\'");
            assertThat(statements.selectFirst()).doesNotContain("FETCH", "OFFSET", "LIMIT");
        }

        @Test
        @DisplayName("the resolved shape is cached, and released by close")
        void theResolvedShapeIsCachedAndReleasedByClose() {
            AccountRepository repository = repository(seeded(List.of()));

            repository.open(OpenMode.INPUT);
            Statements first = repository.resolvedStatements();
            repository.readNext();
            assertThat(repository.resolvedStatements()).isSameAs(first);

            assertThat(repository.close()).isEqualTo(FileStatus.OK);
            assertThat(repository.resolvedStatements()).isNull();
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
        @DisplayName("a successful open reports '00' and records the verb that was issued")
        void aSuccessfulOpenRecordsTheVerb(OpenMode mode) {
            AccountRepository repository = repository(seeded(List.of()));

            assertThat(repository.open(mode)).isEqualTo(FileStatus.OK);
            assertThat(repository.openMode()).contains(mode);
        }

        @Test
        @DisplayName("the two verbs are the ones the estate issues, spelled as the source spells them")
        void theTwoVerbsAreTheOnesTheEstateIssues() {
            assertThat(OpenMode.values()).containsExactly(OpenMode.INPUT, OpenMode.I_O);
            assertThat(OpenMode.INPUT.cobolVerb()).isEqualTo("OPEN INPUT");
            assertThat(OpenMode.I_O.cobolVerb()).isEqualTo("OPEN I-O");
        }

        @Test
        @DisplayName("a failed open leaves the dataset closed, exactly as COBOL does")
        void aFailedOpenLeavesTheDatasetClosed() {
            AccountRepository repository = repository(unreachable());

            assertThat(repository.open(OpenMode.INPUT))
                    .isEqualTo(AccountRepository.PERMANENT_ERROR_STATUS);
            assertThat(repository.openMode()).isEmpty();
        }

        @Test
        @DisplayName("close reports '00' and gives up the recorded verb")
        void closeReportsOkAndGivesUpTheVerb() {
            AccountRepository repository = repository(seeded(List.of()));
            repository.open(OpenMode.I_O);

            assertThat(repository.close()).isEqualTo(FileStatus.OK);
            assertThat(repository.openMode()).isEmpty();
        }

        @Test
        @DisplayName("close reports a permanent error when the dataset is no longer addressable")
        void closeReportsAPermanentErrorWhenUnreachable() {
            AccountRepository repository = repository(unreachable());

            assertThat(repository.close()).isEqualTo(AccountRepository.PERMANENT_ERROR_STATUS);
            assertThat(repository.openMode()).isEmpty();
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

            repository.open(OpenMode.INPUT);
            ReadResult first = repository.readNext();
            ReadResult second = repository.readNext();
            assertThat(first.account().orElseThrow().keyImage()).isEqualTo(lowestKey);
            assertThat(second.account().orElseThrow().keyImage()).isNotEqualTo(lowestKey);

            repository.open(OpenMode.INPUT);
            assertThat(repository.readNext().account().orElseThrow().keyImage()).isEqualTo(lowestKey);

            repository.close();
            assertThat(repository.readNext().account().orElseThrow().keyImage()).isEqualTo(lowestKey);
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
            AccountRepository repository = repository(seeded(rows));
            repository.open(OpenMode.INPUT);

            List<String> read = drain(repository, FIXTURE_RECORDS + 1);

            assertThat(read).hasSize(FIXTURE_RECORDS)
                    .containsExactlyElementsOf(rows.stream().sorted().toList());
            ReadResult atEnd = repository.readNext();
            assertThat(atEnd.isEndOfFile()).isTrue();
            assertThat(atEnd.status()).isEqualTo(FileStatus.END_OF_FILE);
            assertThat(atEnd.account()).isEmpty();
            assertThat(atEnd.applResult()).isEqualTo(FileStatus.APPL_EOF);
            // Reading past end of file keeps reporting end of file rather than inventing a status.
            assertThat(repository.readNext().isEndOfFile()).isTrue();
        }

        @Test
        @DisplayName("delivers ascending ACCT-ID order even when the rows are stored in reverse")
        void deliversAscendingKeyOrderWhateverTheStoredOrder() {
            List<String> rows = new ArrayList<>(fixtureRows());
            rows.sort(Comparator.reverseOrder());
            AccountRepository repository = repository(seeded(rows));

            List<Long> identifiers = new ArrayList<>();
            for (ReadResult result = repository.readNext(); result.isFound();
                    result = repository.readNext()) {
                identifiers.add(result.account().orElseThrow().getAcctId());
            }

            assertThat(identifiers).hasSize(FIXTURE_RECORDS).isSorted();
        }

        @Test
        @DisplayName("preserves the 178 FILLER spaces and every field of a decoded record")
        void preservesFillerAndEveryField() {
            List<String> rows = fixtureRows();
            AccountRepository repository = repository(seeded(rows));

            AccountRecord account = repository.readNext().account().orElseThrow();
            String expected = rows.stream().sorted().findFirst().orElseThrow();

            assertThat(account.recordLength()).isEqualTo(THREE_HUNDRED);
            assertThat(account.toByteArray()).hasSize(THREE_HUNDRED);
            assertThat(account.toFixedWidthString()).isEqualTo(expected);
            assertThat(account.getFiller()).isEqualTo(" ".repeat(FILLER_WIDTH));
            assertThat(account.keyImage()).isEqualTo(keyImageOf(expected));
        }

        @Test
        @DisplayName("widens a short stored image with spaces rather than misplacing every field")
        void widensAShortStoredImage() {
            String full = fixtureRows().get(0);
            String trimmed = full.substring(0, THREE_HUNDRED - FILLER_WIDTH);
            AccountRepository repository = repository(seeded(List.of(trimmed)));

            AccountRecord account = repository.readNext().account().orElseThrow();

            assertThat(account.toFixedWidthString()).isEqualTo(full);
            assertThat(account.getFiller()).isEqualTo(" ".repeat(FILLER_WIDTH));
        }

        @Test
        @DisplayName("rejects a stored image wider than the copybook declares")
        void rejectsAnOverWideStoredImage() {
            String overWide = fixtureRows().get(0) + " ";
            AccountRepository repository = repository(seeded(List.of(overWide), THREE_HUNDRED + 1));

            assertThatIllegalArgumentException().isThrownBy(repository::readNext)
                    .withMessageContaining("wider than the declared width");
        }

        @Test
        @DisplayName("reports an empty dataset as end of file, not as a failure")
        void reportsAnEmptyDatasetAsEndOfFile() {
            AccountRepository repository = repository(seeded(List.of()));

            assertThat(repository.readNext().isEndOfFile()).isTrue();
        }

        @Test
        @DisplayName("reports a permanent error when the browse cannot reach the dataset")
        void reportsAPermanentErrorWhenTheDatasetIsUnreachable() {
            ReadResult result = repository(unreachable()).readNext();

            assertThat(result.isOther()).isTrue();
            assertThat(result.status()).isEqualTo(AccountRepository.PERMANENT_ERROR_STATUS);
            assertThat(result.applResult()).isEqualTo(AccountRepository.APPL_RESULT_FATAL);
        }

        @Test
        @DisplayName("reports a permanent error when the read itself is refused")
        void reportsAPermanentErrorWhenTheReadIsRefused() throws SQLException {
            ReadResult result = repository(describingThenRefusing()).readNext();

            assertThat(result.isOther()).isTrue();
            assertThat(result.status()).isEqualTo(AccountRepository.PERMANENT_ERROR_STATUS);
        }

        @Test
        @DisplayName("a row with no record image is a failure, never an end of file")
        void aRowWithNoRecordImageIsAFailure() throws SQLException {
            ReadResult result = repository(describingThenReturningNoImage()).readNext();

            assertThat(result.isOther()).isTrue();
            assertThat(result.isEndOfFile()).isFalse();
            assertThat(result.status()).isEqualTo(AccountRepository.PERMANENT_ERROR_STATUS);
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
            AccountRepository repository = repository(seeded(rows));
            String wanted = rows.get(0);

            ReadResult result = repository.readForUpdate(keyImageOf(wanted));

            assertThat(result.isFound()).isTrue();
            assertThat(result.cicsResp()).hasValue(FileStatus.NORMAL);
            assertThat(result.account().orElseThrow().toFixedWidthString()).isEqualTo(wanted);
        }

        @Test
        @DisplayName("a blank RIDFLD is a not-found record, not a rejection")
        void aBlankRidfldIsNotFound() {
            AccountRepository repository = repository(seeded(fixtureRows()));

            assertThat(repository.readForUpdate(" ".repeat(ELEVEN)).isNotFound()).isTrue();
        }

        @ParameterizedTest(name = "RIDFLD of eleven [{0}] characters")
        @ValueSource(strings = { "%", "_", "\\" })
        @DisplayName("a LIKE metacharacter in the RIDFLD matches nothing rather than another account")
        void aMetacharacterInTheRidfldMatchesNothing(String character) {
            AccountRepository repository = repository(seeded(fixtureRows()));

            ReadResult result = repository.readForUpdate(character.repeat(ELEVEN));

            assertThat(result.isNotFound()).isTrue();
            assertThat(result.account()).isEmpty();
        }

        @Test
        @DisplayName("refuses a RIDFLD that is not exactly eleven characters")
        void refusesARidfldOfTheWrongWidth() {
            AccountRepository repository = repository(seeded(List.of()));

            assertThatIllegalArgumentException().isThrownBy(() -> repository.readForUpdate("0000000001"))
                    .withMessageContaining("PIC X(11)");
            assertThatNullPointerException().isThrownBy(() -> repository.readForUpdate(null))
                    .withMessageContaining("key image is required");
        }

        @Test
        @DisplayName("reports a permanent error when the dataset cannot be reached or read")
        void reportsAPermanentErrorOnFailure() throws SQLException {
            assertThat(repository(unreachable()).readByKey(1L).status())
                    .isEqualTo(AccountRepository.PERMANENT_ERROR_STATUS);
            assertThat(repository(describingThenRefusing()).readByKey(1L).status())
                    .isEqualTo(AccountRepository.PERMANENT_ERROR_STATUS);
            assertThat(repository(describingThenReturningNoImage()).readByKey(1L).status())
                    .isEqualTo(AccountRepository.PERMANENT_ERROR_STATUS);
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
        @DisplayName("reports a permanent error when more than one row carries the key")
        void reportsAPermanentErrorWhenSeveralRowsCarryTheKey() {
            String duplicated = fixtureRows().get(0);
            AccountRepository repository = repository(seeded(List.of(duplicated, duplicated)));
            AccountRecord account = AccountRecord.decode(duplicated, ASCII);

            WriteResult result = repository.rewrite(account);

            assertThat(result.isOther()).isTrue();
            assertThat(result.status()).isEqualTo(AccountRepository.PERMANENT_ERROR_STATUS);
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

            assertThatIllegalStateException().isThrownBy(repository::readNext)
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

            assertThatIllegalStateException().isThrownBy(() -> repository.close())
                    .withMessageContaining("control character");
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
                    () -> new ReadResult(FileStatus.END_OF_FILE, Outcome.END_OF_FILE, account))
                    .withMessageContaining("carries no record");
        }

        @Test
        @DisplayName("a status and its classification must agree")
        void aStatusAndItsClassificationMustAgree() {
            assertThatIllegalArgumentException().isThrownBy(
                    () -> new ReadResult(FileStatus.END_OF_FILE, Outcome.NOT_FOUND, Optional.empty()))
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
                    () -> new ReadResult(FileStatus.OK, null, Optional.empty()))
                    .withMessageContaining("classification");
            assertThatNullPointerException().isThrownBy(
                    () -> new ReadResult(FileStatus.END_OF_FILE, Outcome.END_OF_FILE, null))
                    .withMessageContaining("empty record");
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
