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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
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

    /** An account identifier the fixture does not contain, for the invalid-key arms. */
    private static final long ABSENT_ACCT_ID = 99_999_999_999L;

    /** The type code the fixture uses throughout. */
    private static final String FIXTURE_TYPE_CD = "01";

    /** The category code the fixture uses throughout. */
    private static final int FIXTURE_CAT_CD = 1;

    /**
     * Distinguishes the in-memory database each seeded test uses, so no two tests share a relation.
     *
     * <p>A counter rather than a random or time-derived name, so a run is reproducible.
     */
    private static final java.util.concurrent.atomic.AtomicInteger DATABASE_SEQUENCE =
            new java.util.concurrent.atomic.AtomicInteger();

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
    private static JdbcTemplate seeded(List<String> rows) {
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
    private static JdbcTemplate seeded(List<String> rows, int columnWidth) {
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
    private static DataSource seededDataSource(int columnWidth) {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:tcatbalrepo" + DATABASE_SEQUENCE.incrementAndGet()
                        + ";DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE", "sa", "");
        dataSource.setDriverClassName("org.h2.Driver");
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
    private static JdbcTemplate countReportingNothing(List<String> rows) {
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
            // the two layouts. This is the assertion that does.
            assertThat(TranCatBalRepository.DISCLOSURE_GROUP_KEY_LENGTH).isEqualTo(16);
            assertThat(TranCatBalRepository.KEY_LENGTH)
                    .isNotEqualTo(TranCatBalRepository.DISCLOSURE_GROUP_KEY_LENGTH);
        }

        @Test
        @DisplayName("the key is not 6 bytes - CVTRA04Y declares an identically-named TRAN-CAT-KEY")
        void keyIsNotTheSixByteTranCategoryKey() {
            assertThat(TranCatBalRepository.TRAN_CATEGORY_KEY_LENGTH).isEqualTo(6);
            assertThat(TranCatBalRepository.KEY_LENGTH)
                    .isNotEqualTo(TranCatBalRepository.TRAN_CATEGORY_KEY_LENGTH);
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
    // 2700-A-CREATE-TCATBAL-REC - app/cbl/CBTRN02C.cbl:L503-L524, the WRITE.
    // =============================================================================================

    @Nested
    @DisplayName("the write - app/cbl/CBTRN02C.cbl:L503-L524")
    class Write {

        @Test
        @DisplayName("a new record is stored as exactly 50 bytes, FILLER included")
        void newRecordIsStoredAtFiftyBytes() {
            JdbcTemplate template = seeded(new ArrayList<>());
            WriteResult result = repository(template).write(recordFor(42L, "10.00"));
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
            assertThat(repository.write(written).isWritten()).isTrue();

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
            assertThat(repository.write(record).isWritten()).isTrue();

            WriteResult second = repository.write(record);
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
            WriteResult result = repository(describingThenViolatingIntegrityOnWrite())
                    .write(recordFor(42L, "10.00"));
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
            WriteResult result = repository(unreachable()).write(recordFor(42L, "10.00"));
            assertThat(result.isOther()).isTrue();
            assertThat(result.status()).isEqualTo(TranCatBalRepository.PERMANENT_ERROR_STATUS);
            assertThat(result.diagnostic()).isPresent();
        }

        @Test
        @DisplayName("a refused pre-check statement is reported as a status")
        void refusedPreCheckIsReportedAsStatus() throws SQLException {
            WriteResult result = repository(describingThenRefusing()).write(recordFor(42L, "10.00"));
            assertThat(result.isOther()).isTrue();
            assertThat(result.diagnostic()).isPresent();
        }

        @Test
        @DisplayName("an insert that adds no row is a permanent error, never a silent success")
        void insertAddingNoRowIsAPermanentError() throws SQLException {
            WriteResult result = repository(countingThenReporting(0, 0)).write(recordFor(42L, "10.00"));
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
                    .isThrownBy(() -> repository.write(recordFor(42L, "10.00")))
                    .withMessageContaining("2 rows were added");
        }

        @Test
        @DisplayName("the handle's write reuses the shape its OPEN resolved")
        void handleWriteWorks() {
            JdbcTemplate template = seeded(new ArrayList<>());
            try (TranCatBalFile file = repository(template).open(OpenMode.I_O)) {
                assertThat(file.write(recordFor(42L, "5.00")).isWritten()).isTrue();
                assertThat(file.write(recordFor(42L, "5.00")).isDuplicate()).isTrue();
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

            WriteResult result = repository.rewrite(record);
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
            assertThat(repository.rewrite(record).isWritten()).isTrue();

            TranCatBalRecord reread = repository.readByKey(1L, FIXTURE_TYPE_CD, FIXTURE_CAT_CD)
                    .record().orElseThrow();
            assertThat(reread.fillerImage()).isEqualTo(fillerAsRead);
            assertThat(reread.rawImage()).hasSize(FIFTY);
        }

        @Test
        @DisplayName("a key matching nothing is '23' - a rewrite is not an insert")
        void keyMatchingNothingIsNotFound() {
            WriteResult result = repository(seeded(fixtureRows()))
                    .rewrite(recordFor(ABSENT_ACCT_ID, "1.00"));
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

            WriteResult result = repository.rewrite(record);
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
            WriteResult result = repository(unreachable()).rewrite(recordFor(1L, "1.00"));
            assertThat(result.isOther()).isTrue();
            assertThat(result.diagnostic()).isPresent();
        }

        @Test
        @DisplayName("a refused pre-check statement is reported as a status")
        void refusedPreCheckIsReportedAsStatus() throws SQLException {
            WriteResult result = repository(describingThenRefusing()).rewrite(recordFor(1L, "1.00"));
            assertThat(result.isOther()).isTrue();
            assertThat(result.diagnostic()).isPresent();
        }

        @Test
        @DisplayName("a row lost between the pre-check and the update is '23', because nothing was written")
        void rowLostBetweenCheckAndUpdateIsNotFound() throws SQLException {
            WriteResult result = repository(countingThenReporting(1, 0)).rewrite(recordFor(1L, "1.00"));
            assertThat(result.isNotFound()).isTrue();
            assertThat(result.status()).isEqualTo(FileStatus.NOT_FOUND);
        }

        @Test
        @DisplayName("rows replaced beyond the one checked refuses the unit of work rather than reporting")
        void fanOutAfterTheCheckRefusesTheUnitOfWork() throws SQLException {
            TranCatBalRepository repository = repository(countingThenReporting(1, 2));
            assertThatExceptionOfType(DatasetIntegrityException.class)
                    .isThrownBy(() -> repository.rewrite(recordFor(1L, "1.00")))
                    .withMessageContaining("2 rows were replaced")
                    .withMessageContaining("no row lock");
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
                assertThat(file.rewrite(record).isWritten()).isTrue();
                assertThat(file.rewrite(recordFor(ABSENT_ACCT_ID, "1.00")).isNotFound()).isTrue();
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
            assertThat(repository.write(recordFor(42L, amount)).isWritten()).isTrue();

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
            assertThat(repository.write(recordFor(42L, amount)).isWritten()).isTrue();
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
            WriteResult result = repository(describingThenFailingWriteWithoutIntegrityViolation())
                    .write(recordFor(42L, "10.00"));
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
            assertThat(repository.write(recordFor(42L, "1.00")).isDuplicate()).isTrue();
            WriteResult rewritten = repository.rewrite(recordFor(1L, "1.00"));
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
}
