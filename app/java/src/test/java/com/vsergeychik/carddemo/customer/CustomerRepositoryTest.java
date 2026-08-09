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
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * Unit tests for {@link CustomerRepository}, the customer master dataset's only reader and only writer.
 *
 * <p>This is the foundational test of the {@code customer} package: it pins the 500-byte, nine-byte-key
 * data-access contract that the customer service, the customer batch reader and the cross-package
 * {@code account}, {@code statement} and {@code transaction} expectations all rest on. An error here is
 * inherited by every one of them, which is why the geometry is asserted against hard-coded offsets rather
 * than against anything the production code also computes.
 *
 * <h2>Every expected value here is STATICALLY DERIVED, not captured from a run</h2>
 *
 * <p><strong>The COBOL cannot be executed in this environment,</strong> so no expectation below was
 * recorded from a legacy run. Eight blockers are independently verified in the migration plan's special
 * analysis (§0.7.6, open risk <strong>R-A</strong>): there is no z/OS or mainframe runtime; GnuCOBOL's
 * indexed file handler reports {@code disabled}, which the seven {@code ORGANIZATION INDEXED} programs
 * need; {@code PROCEDURE … USING} is rejected under {@code -x}, so the called subprograms cannot be
 * linked as executables; {@code app/cpy/CUSTREC.cpy} fails to parse on literal tab characters in its
 * margin; no Language Environment {@code CEE*} services exist, so neither {@code CEEDAYS} nor
 * {@code CEE3ABD} is callable; there is no CICS emulator and the three IBM-supplied copybooks are absent
 * from the repository; the EBCDIC fixtures need binary handling the compiler is not configured for; and
 * no alternative compiler is installable.
 *
 * <p>Every value asserted here was therefore <strong>read out of the source</strong> and is cited at its
 * point of use: the record geometry from {@code app/cpy/CVCUS01Y.cpy}; the access mode, the guard chains
 * and the {@code APPL-RESULT} values from {@code app/cbl/CBCUS01C.cbl:L29-L174}; the keyed read and the
 * six-operation subroutine contract from {@code app/cbl/CBSTM03B.CBL:L43-L204}; the open-without-a-read
 * quirk from {@code app/cbl/CBTRN01C.cbl:L271-L287} and {@code L379-L395}; the online read and its
 * {@code EVALUATE WS-RESP-CD} arms from {@code app/cbl/COACTVWC.cbl:L825-L872}; the locking read, the
 * optimistic comparison and the rewrite from {@code app/cbl/COACTUPC.cbl:L3919-L4103}; the file
 * capabilities from {@code app/csd/CARDDEMO.CSD:L50-L62}; the DD binding from
 * {@code app/jcl/READCUST.jcl:L9-L10}; and the record data from {@code app/data/ASCII/custdata.txt}. They
 * are restated here rather than read back off the implementation, so agreement between the two is an
 * audit and not a tautology. A reader must not mistake them for captured values.
 *
 * <h2>Two kinds of test, and why both are needed</h2>
 *
 * <p><strong>Round trips against a real single-column relation.</strong> The dataset is seeded from the
 * <em>test classpath</em> resource {@code /fixtures/custdata.txt} - the classpath copy of
 * {@code app/data/ASCII/custdata.txt}, 50 records of exactly 500 bytes each - into an in-memory relation
 * with one record-image column, which is the shape the parity harness seeds and the shape the production
 * gateway is expected to present. That is what makes the byte-level assertions meaningful: a record read
 * and re-encoded has to come back identical, {@code FILLER X(168)} included.
 *
 * <p>The fixture is reached <strong>only</strong> through {@link Class#getResourceAsStream(String)}.
 * Nothing here opens {@code app/data/ASCII/custdata.txt} by filesystem path and nothing walks up the
 * directory tree to the reference trees: the COBOL, copybook, JCL, CSD and data trees are the migration's
 * only behavioural oracle and are read-only, so they appear in this file exclusively as provenance in
 * comments. Note also that {@code custdata.txt} matches its copybook <em>exactly</em> and needs no
 * width normalisation - unlike {@code cardxref}, which is 36 bytes where {@code CVACT03Y} declares 50.
 * Right-padding a customer row would corrupt every expectation in this class.
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
 * and contains none - and {@code AbsenceTests} proves the production class contains none either, by
 * scanning its compiled constant pool.
 *
 * <h2>Which types this test necessarily reaches, and why</h2>
 *
 * <p>Beyond the subject and its record model, three collaborating types are imported because they appear
 * in the subject's <em>own public signature</em> and it cannot be exercised without them:
 * {@link RecordImageForm} is the fourth constructor argument;
 * {@link com.vsergeychik.carddemo.common.CicsResponse} is a component of both result records and the
 * operand of their two-argument factories; and
 * {@link com.vsergeychik.carddemo.common.DatasetIntegrityException} is what a rewrite raises when the
 * write replaced more rows than the key selected. Each is a type the repository already publishes, not a
 * new dependency this test introduces, and each was verified present before being used.
 *
 * <h2>Where the production API differs from the summary this file was written against</h2>
 *
 * <p>Two differences are recorded here rather than papered over, because the source file is authoritative
 * for what its members are called:
 * <ul>
 *   <li>there is no {@code startBrowse} and no repository-level {@code close}. A browse is
 *       {@link CustomerRepository#openInput()} followed by {@link CustomerFile#readNext()}, and the close
 *       is {@link CustomerFile#closeFile()} - because an open produces a file with a position, and a
 *       position kept on a Spring singleton would be shared by every concurrent execution while COBOL
 *       shares none of it;</li>
 *   <li>the repository never throws
 *       {@link com.vsergeychik.carddemo.common.AbendException}. It reports the two-character
 *       {@code FILE STATUS} and hands the caller the {@code APPL-RESULT} its guard chain would have
 *       moved; the {@code CALL 'CEE3ABD'} lives in the caller, at
 *       {@code app/cbl/CBCUS01C.cbl:L158} ({@code Z-ABEND-PROGRAM}). That division is exactly where the
 *       COBOL puts it, so {@code FileStatusMatrixTests} asserts the translation at the seam - the
 *       repository yields {@code WHEN OTHER} with {@code APPL-RESULT} 12, and the abend the caller raises
 *       from it carries return code 12 - rather than expecting a throw from a class that correctly has
 *       none.</li>
 * </ul>
 *
 * <h2>User-specified rules</h2>
 *
 * <p>{@code review_rules} returns exactly one line - "No user rules provided." - and that one line is the
 * whole document, so <strong>no user rule governs this file</strong>. Their absence is not licence to
 * lower the bar: the migration plan elevates twelve enterprise practices to binding constraints, and the
 * ones bearing on this file are honoured as follows.
 * <ul>
 *   <li><strong>B1 - exact verified versions, no placeholders.</strong> Only the closed test stack is
 *       used: JUnit Jupiter, Mockito and AssertJ as they arrive through
 *       {@code spring-boot-starter-test}, Spring's own JDBC and transaction support, and H2 at test
 *       scope. No test library is added, and no version is declared here at all.</li>
 *   <li><strong>B3 - reference inputs are immutable.</strong> Nothing here writes, and nothing here
 *       reads by filesystem path, into {@code app/cbl}, {@code app/cpy}, {@code app/cpy-bms},
 *       {@code app/bms}, {@code app/jcl}, {@code app/proc}, {@code app/csd} or {@code app/data}.</li>
 *   <li><strong>B7 - deterministic, non-interactive, reproducible.</strong> No clock, no randomness, no
 *       network and no dependence on test ordering. Each test names its own in-memory database from its
 *       own test identity, so the whole class is order-independent and repeatable.</li>
 *   <li><strong>B8 - explicit over implicit.</strong> {@link StandardCharsets#US_ASCII} is named at every
 *       decode and encode; there is not one wildcard import; the dataset name arrives from configuration
 *       in every test.</li>
 *   <li><strong>B9 - no static mutable state.</strong> This class holds none - every {@code static}
 *       member is an immutable constant - and it asserts the same of the production class.</li>
 *   <li><strong>B11 - hand-written, reviewable codecs.</strong> The nineteen spans are asserted against
 *       hard-coded offsets and lengths transcribed from the copybook, so a wrong offset fails here and is
 *       visible in review, rather than being confirmed by the same table that produced it.</li>
 *   <li><strong>B12 - environmental limits documented, not absorbed.</strong> The inability to run the
 *       COBOL is stated above, with its consequence for provenance, rather than left implicit.</li>
 * </ul>
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
     * The prefix that distinguishes this test's in-memory databases from every other test's.
     *
     * <p>Derived in {@link #nameThisTestsDatabases(TestInfo)} from the running test's own identity, so no
     * two tests - and no two invocations of one parameterized test - can share a relation, while the name
     * a given test uses is the same on every run. That rules out both interference and irreproducibility
     * without a clock, a random source or a shared counter.
     *
     * <p><strong>An instance field, deliberately not {@code static}.</strong> Practice B9 and gate G53
     * forbid static mutable state, and this class holds itself to the standard it asserts of the
     * production code: JUnit builds a fresh instance per test, so instance state is per-test state and is
     * shared with nothing.
     */
    private String databaseNamePrefix;

    /**
     * How many databases this test has already created, so a test that needs two relations gets two.
     *
     * <p>Per-instance and therefore per-test, and incremented in a fixed order within a test, so the names
     * are stable across runs.
     */
    private int databaseOrdinal;


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

    // =============================================================================================
    // Per-test setup. Collaborators are built fresh for every test; nothing survives between them.
    // =============================================================================================

    /**
     * Names this test's databases from the test's own identity, before the test body runs.
     *
     * <p>The display name is included because a {@code @ParameterizedTest}'s invocations share a method
     * name but never a display name, and its hash is used only to keep the resulting identifier legal in a
     * JDBC URL. {@link String#hashCode()} is specified by the language, so this is deterministic.
     *
     * @param testInfo the running test's identity, injected by JUnit
     */
    @BeforeEach
    void nameThisTestsDatabases(TestInfo testInfo) {
        String method = testInfo.getTestMethod().map(Method::getName).orElse("unnamed");
        int discriminator = testInfo.getDisplayName().hashCode() & Integer.MAX_VALUE;
        this.databaseNamePrefix = "custrepo_" + method + "_" + Integer.toString(discriminator, 36);
        this.databaseOrdinal = 0;
    }

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
    private JdbcTemplate seeded(List<String> rows) {
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

    /**
     * The names of the public methods a class declares itself.
     *
     * <p>Used where a test asserts that two operations really are two operations rather than one aliased
     * to the other, and by the absence assertions.
     *
     * @param type the class to inspect
     * @return its own public method names, in declaration order, without synthetic members
     */
    private static Set<String> declaredMethodNamesOf(Class<?> type) {
        Set<String> names = new LinkedHashSet<>();
        for (Method method : type.getDeclaredMethods()) {
            if (Modifier.isPublic(method.getModifiers()) && !method.isSynthetic()) {
                names.add(method.getName());
            }
        }
        return names;
    }

    /**
     * A template whose relation describes one column and whose key selects one row, but whose
     * {@code UPDATE} the backend then rejects.
     *
     * <p>The arm between "the count succeeded" and "a row was replaced": a healthy database will not
     * produce it, and the COBOL has no code for an exception there - only for a file status - so it has to
     * be driven through a mock and has to arrive as the {@code WHEN OTHER} arm.
     *
     * @return a template that counts one row and then refuses the write
     * @throws SQLException never; declared because the mocked JDBC methods declare it
     */
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
            assertThat(statements.selectNext()).contains("ORDER BY").contains("ASC").contains(" > ?")
                    // And a row whose record image is absent stays visible to the advancing read, so a
                    // present-but-unreadable record is reported rather than skipped.
                    .contains("IS NULL");
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
        @DisplayName("is a distinct method from the plain keyed read, and returns the identical bytes")
        void isDistinctFromThePlainReadYetReturnsTheSameRecord() {
            // Distinct because the COBOL distinguishes them - 9400-GETCUSTDATA-BYCUST reads without UPDATE
            // (app/cbl/COACTVWC.cbl:L826-L834) and app/cbl/COACTUPC.cbl:L3921-L3930 reads with it - and the
            // lock is the difference. What must NOT differ is the record: 9700-CHECK-CHANGE-IN-REC compares
            // the locked re-read field by field against the copy the screen was painted from
            // (app/cbl/COACTUPC.cbl:L4109-L4193), so a locking read that returned different bytes from the
            // plain read would make that comparison report a change nobody made.
            List<String> rows = fixtureRows();
            String row = rows.get(0);
            CustomerRepository repository = repository(seeded(rows));

            CustomerRecord plainly = repository.readByKey(keyImageOf(row)).customer().orElseThrow();
            CustomerRecord locked = withUnitOfWork(() -> repository.readForUpdate(keyImageOf(row)))
                    .customer().orElseThrow();

            assertThat(locked.encode(ASCII)).isEqualTo(plainly.encode(ASCII));
            assertThat(locked.encode(ASCII)).hasSize(FIVE_HUNDRED);
            assertThat(locked).isEqualTo(plainly);
            // Two different methods, not one aliased to the other.
            assertThat(declaredMethodNamesOf(CustomerRepository.class))
                    .contains("readByKey", "readForUpdate");
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
            List<String> methods = Arrays.stream(CustomerFile.class.getDeclaredMethods())
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
            // The locking arm of the pre-write count. app/csd/CARDDEMO.CSD:L53 defines the file
            // UPDATEMODEL(LOCKING), so where a unit of work is open the count must be taken under the very
            // FOR UPDATE lock the rewrite will use - otherwise the relation could change between the two
            // and the fan-out check would be describing a state that no longer exists.
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
            // The stored image is byte-identical to what was handed in, FILLER included.
            assertThat(template.queryForObject("SELECT " + RECORD_IMAGE_COLUMN + " FROM \""
                    + TEST_DSNAME + "\" WHERE " + RECORD_IMAGE_COLUMN + " LIKE ?", String.class,
                    keyImageOf(row) + "%")).isEqualTo(changed);
        }

        @Test
        @DisplayName("a post-write fan-out under a row lock refuses the unit of work and says so")
        void refusesTheUnitOfWorkOnAPostWriteFanOutUnderALock() throws SQLException {
            // The same race as the unlocked case, but with a unit of work open, so the refusal has to
            // report that the count was taken under a row lock. That distinction is the difference
            // between "the relation is not a KSDS" and "something changed underneath us", and an operator
            // reading the refusal needs to know which.
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
            // The count succeeds and the UPDATE itself is then rejected by the backend. app/cbl/CBCUS01C
            // has no code for an exception, only for a file status, so this has to arrive as the WHEN
            // OTHER arm with the backend's reason attached for the log.
            CustomerRepository repository = repository(countingOneThenRefusingTheUpdate());
            byte[] image = ("000000001" + " ".repeat(FIVE_HUNDRED - NINE)).getBytes(ASCII);

            WriteResult result = repository.rewrite(image);

            assertThat(result.isOther()).isTrue();
            assertThat(result.status()).isEqualTo(CustomerRepository.PERMANENT_ERROR_STATUS);
            assertThat(result.applResult()).isEqualTo(CustomerRepository.APPL_RESULT_FATAL);
            assertThat(result.diagnostic()).isPresent();
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

            // All eighteen named fields round trip. Only the span no field covers does not - and
            // DISPLAY writes it.
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
            // The other half of the finding. INITIALIZE CUSTOMER-RECORD, which is what an update
            // composes from, does blank the area - so a record built field by field must still render
            // FILLER as spaces. Only the DISPLAY of a record just READ must not.
            assertThat(new CustomerRecord().recordImage(ASCII))
                    .hasSize(FIVE_HUNDRED)
                    .endsWith(" ".repeat(FILLER_WIDTH));
        }

        /**
         * The first fixture row with its trailing {@code FILLER X(168)} overwritten.
         *
         * @return a 500-character image every declared span of which decodes, whose FILLER is not spaces
         */
        private String dirtyRow() {
            String clean = fixtureRows().stream().sorted().findFirst().orElseThrow();
            String dirty = clean.substring(0, FIVE_HUNDRED - FILLER_WIDTH) + "*".repeat(FILLER_WIDTH);
            assertThat(dirty).hasSize(FIVE_HUNDRED).isNotEqualTo(clean);
            return dirty;
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

    // =============================================================================================
    // RECORD GEOMETRY AND THE CODEC ROUND TRIP - app/cpy/CVCUS01Y.cpy.
    //
    // The nineteen spans below are transcribed from the copybook by hand and are hard-coded here on
    // purpose (practice B11): asserting the production descriptor table against itself would pass with a
    // wrong offset in both places, whereas asserting it against a transcription makes a wrong offset a
    // failure that a reviewer can diff against app/cpy/CVCUS01Y.cpy line by line.
    // =============================================================================================

    /**
     * The record's byte geometry, and the codec round trip that proves nothing is lost in it.
     *
     * <p>This group is the reason the rest of the class can trust a decoded record. Gate G19 requires every
     * written record to be byte-identical in length to its copybook declaration - 500 for the customer
     * master - and gate G21 requires {@code FILLER} to be present and space-filled, which the total width
     * proves immediately: drop the trailing {@code FILLER X(168)} and the record is 332 bytes, so the
     * width assertion fails before any field assertion gets the chance to mislead.
     */
    @Nested
    @DisplayName("The record is 500 bytes of nineteen declared spans, and survives a round trip")
    class RecordGeometryTests {

        /**
         * One row of the copybook, transcribed by hand: the item name, its 0-based offset and its width.
         *
         * @param name   the copybook item name verbatim, or {@code FILLER} for the reserved span
         * @param offset the 0-based byte offset of the span within the record
         * @param length the declared width of the span in bytes
         */
        private record Span(String name, int offset, int length) {
        }

        /**
         * The nineteen spans of {@code app/cpy/CVCUS01Y.cpy}, in declaration order.
         *
         * <p>Transcribed from the copybook, never derived from {@link CustomerRecord#LAYOUT}. The widths
         * are {@code 9+25+25+25+50+50+50+2+3+10+15+15+9+20+10+10+1+3+168}, which is 500.
         *
         * @return the transcribed geometry
         */
        private List<Span> copybookGeometry() {
            return List.of(
                    new Span("CUST-ID", 0, 9),                       // PIC 9(09) - the KSDS key
                    new Span("CUST-FIRST-NAME", 9, 25),              // PIC X(25)
                    new Span("CUST-MIDDLE-NAME", 34, 25),            // PIC X(25)
                    new Span("CUST-LAST-NAME", 59, 25),              // PIC X(25)
                    new Span("CUST-ADDR-LINE-1", 84, 50),            // PIC X(50)
                    new Span("CUST-ADDR-LINE-2", 134, 50),           // PIC X(50)
                    new Span("CUST-ADDR-LINE-3", 184, 50),           // PIC X(50)
                    new Span("CUST-ADDR-STATE-CD", 234, 2),          // PIC X(02)
                    new Span("CUST-ADDR-COUNTRY-CD", 236, 3),        // PIC X(03)
                    new Span("CUST-ADDR-ZIP", 239, 10),              // PIC X(10)
                    new Span("CUST-PHONE-NUM-1", 249, 15),           // PIC X(15)
                    new Span("CUST-PHONE-NUM-2", 264, 15),           // PIC X(15)
                    new Span("CUST-SSN", 279, 9),                    // PIC 9(09)
                    new Span("CUST-GOVT-ISSUED-ID", 288, 20),        // PIC X(20)
                    new Span("CUST-DOB-YYYY-MM-DD", 308, 10),        // PIC X(10)
                    new Span("CUST-EFT-ACCOUNT-ID", 318, 10),        // PIC X(10)
                    new Span("CUST-PRI-CARD-HOLDER-IND", 328, 1),    // PIC X(01)
                    new Span("CUST-FICO-CREDIT-SCORE", 329, 3),      // PIC 9(03)
                    new Span("FILLER", 332, 168));                   // PIC X(168) - reserved, spaces
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
            // The arithmetic the copybook's RECLN 500 asserts, done on the transcription rather than on
            // the production table, so the two are independent witnesses.
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
                // Nothing in CVCUS01Y redefines anything: there is no REDEFINES in this copybook at all.
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
            // Hard-coded against the transcription, field by field, so a transposed offset fails here.
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

        /**
         * Asserts one span against its hand-transcribed name, offset, width and picture category.
         *
         * @param span     the declared span
         * @param name     the copybook item name expected
         * @param offset   the 0-based offset expected
         * @param length   the width expected
         * @param kind     the picture category expected
         */
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
            // Gate G21. app/cpy/CVCUS01Y.cpy:L23 declares FILLER PIC X(168); it carries no VALUE literal,
            // so it holds spaces, and a codec that omitted it would produce a 332-byte record.
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
                // Gate G19: the stored width is the copybook width for every record, with no padding
                // normalisation - custdata.txt matches CVCUS01Y exactly, unlike cardxref.
                assertThat(stored).as("stored image of customer %s", keyImageOf(row))
                        .hasSize(FIVE_HUNDRED);

                CustomerRecord decoded = CustomerRecord.decode(stored, ASCII);
                byte[] reencoded = decoded.encode(ASCII);

                assertThat(reencoded).as("round trip of customer %s", keyImageOf(row))
                        .hasSize(FIVE_HUNDRED)
                        .isEqualTo(stored);
                // FILLER survives the round trip as spaces in every record, which is what keeps every
                // later offset where the copybook puts it.
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
            // COACTVWC paints CUST-FIRST-NAME into a fixed screen field, so its trailing spaces are part
            // of the value. Trimming here would be a parity violation, not a tidy-up.
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
            // The leading zero of the stored social-security number is significant: 020973888 is nine
            // digits, and rendering it as 20973888 would be a different eight-byte field.
            assertThat(first.getCustSsn()).isEqualTo(20_973_888);
            assertThat(first.custSsnImage(ASCII)).hasSize(9).isEqualTo("020973888");
            assertThat(first.getCustFicoCreditScore()).isEqualTo(274);
            assertThat(first.custFicoCreditScoreImage(ASCII)).hasSize(3).isEqualTo("274");
        }

        @Test
        @DisplayName("the CUST-SSN reference-modification slices are 020 / 97 / 3888")
        void theSocialSecuritySlicesMatchTheOnlineScreen() {
            // app/cbl/COACTVWC.cbl:L496-L504 paints the number in three pieces:
            //   CUST-SSN (1:3), CUST-SSN (4:2) and CUST-SSN (6:4) - COBOL reference modification, whose
            //   positions are 1-based, so they are 0-based [0,3), [3,5) and [5,9) here. The off-by-one is
            //   the point of asserting it.
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

    // =============================================================================================
    // WHAT MUST NOT EXIST.
    //
    // A migration is judged as much by what it declined to add as by what it translated. These are the
    // absence assertions: an access path the estate never uses, a schema artefact, a hard-coded dataset
    // name, a binary floating-point type. Each would pass every behavioural test in this file and each
    // would still be a defect, which is exactly why they are asserted rather than assumed.
    // =============================================================================================

    /**
     * The access paths, annotations, literals and numeric types that must be absent.
     *
     * <p>Every assertion here is reflective or reads the compiled class from the classpath, so none of it
     * depends on the checkout's directory layout and none of it reads the read-only reference trees.
     */
    @Nested
    @DisplayName("What the estate never does is absent, and provably so")
    class AbsenceTests {

        /** The types that make up the customer data path, and the whole of it. */
        private List<Class<?>> customerDataPath() {
            return List.of(CustomerRepository.class,
                    CustomerFile.class,
                    ReadResult.class,
                    WriteResult.class,
                    Statements.class,
                    CustomerRecord.class);
        }

        /**
         * Reads a compiled class straight off the test classpath.
         *
         * <p>Through {@link Class#getResourceAsStream(String)} rather than a file path, so this works from
         * a directory or a jar and cannot wander into the reference trees (practice B3).
         *
         * @param type the class to read
         * @return its class-file bytes, decoded 1:1 so a byte search is an exact search
         */
        private String compiledForm(Class<?> type) {
            String simpleName = type.getName().substring(type.getName().lastIndexOf('.') + 1);
            try (InputStream stream = type.getResourceAsStream(simpleName + ".class")) {
                if (stream == null) {
                    throw new IllegalStateException("The compiled form of " + type.getName()
                            + " is absent from the test classpath, so its constant pool cannot be "
                            + "inspected");
                }
                // ISO-8859-1 maps every byte to the code point of the same value, so an ASCII needle
                // found in this string was present in the bytes verbatim. The constant pool stores
                // ASCII identically under modified UTF-8, so a literal cannot hide from this.
                return new String(stream.readAllBytes(), StandardCharsets.ISO_8859_1);
            } catch (IOException failure) {
                throw new UncheckedIOException(failure);
            }
        }

        @Test
        @DisplayName("no add, insert, delete or remove path exists, though the CSD grants both")
        void exposesNoAddAndNoDelete() {
            // app/csd/CARDDEMO.CSD:L56-L57 grants ADD(YES) and DELETE(YES) on CUSTDAT, so this assertion
            // is deliberate rather than an oversight: the CICS permissions are broader than the estate's
            // actual use, and no program among the 28 writes a new customer record or deletes one. There
            // is no WRITE, no DELETE and no batch REWRITE against this dataset anywhere. Exposing either
            // would invent an access path the COBOL does not have (migration plan §0.3.5).
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

            // "rewrite" is not caught by the "write" prefix, and that is the intended reading: a CICS
            // REWRITE replaces a record that already exists (app/cbl/COACTUPC.cbl:L4085-L4091) and is one
            // of the five sanctioned paths. It is asserted present so this test cannot pass vacuously.
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
            // Gate G45. CARDAIX is a path over the card master and CXACAIX one over the cross-reference;
            // app/csd/CARDDEMO.CSD defines no path over CUSTDAT, and no program reads the customer master
            // by anything but its nine-digit primary key. So unlike CardRepository and CardXrefRepository,
            // this repository has no secondary finder to assert on - and must not acquire one.
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
            // Gate G44: no data-definition statement, no entity mapping, no migration, no index creation
            // and no version column. The prompt forbids schema change outright, so an ORM's shape must be
            // absent - not merely unused. Annotation simple names are compared because jakarta.persistence
            // is not on the classpath at all, which is itself the strongest form of this guarantee.
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

        /**
         * Asserts that none of the annotations present is one an object-relational mapping would add.
         *
         * @param forbidden the forbidden annotation simple names
         * @param subject   what is being described, for the failure message
         * @param present   the annotations actually present
         */
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
            // Gate G46. The needle is assembled from pieces on purpose: writing it whole would put the very
            // literal this test forbids into this file, and a scan of the test sources would then flag it.
            String forbiddenPrefix = "AWS" + "." + "M2" + "." + "CARDDEMO";

            for (Class<?> type : customerDataPath()) {
                assertThat(compiledForm(type))
                        .as("the compiled form of %s must contain no mainframe dataset literal; the name "
                                + "arrives from the carddemo.datasets.%s and .%s keys", type.getName(),
                                CustomerRepository.CICS_FILE_NAME, CustomerRepository.BATCH_DD_NAME)
                        .doesNotContain(forbiddenPrefix)
                        .doesNotContain("CUSTDATA.VSAM");
            }

            // The two constants the class does hold are DD-name keys, not dataset names, and are exactly
            // the names app/csd/CARDDEMO.CSD:L50 and app/jcl/READCUST.jcl:L9-L10 use.
            assertThat(CustomerRepository.CICS_FILE_NAME).isEqualTo("CUSTDAT");
            assertThat(CustomerRepository.BATCH_DD_NAME).isEqualTo("CUSTFILE");
        }

        @Test
        @DisplayName("the dataset name is whatever configuration says, and follows it when it changes")
        void resolvesTheDatasetNameFromConfiguration() {
            // Two different configurations, two different resolved names: proof the value is read rather
            // than compiled in. A constant could not do this.
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
            // Keys are matched exactly, with no case-insensitive or fuzzy fallback: a repository that
            // silently fell back to a default would read the wrong dataset and report success.
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
            // Rule R4 and gate G22: a value derived from a PIC 9 field is never held in binary floating
            // point. And there is no BigDecimal here either, which is the correct answer rather than an
            // omission: app/cpy/CVCUS01Y.cpy declares NO signed picture and NO V anywhere, so the record
            // has no scale at all - CUST-ID and CUST-SSN are PIC 9(09) and CUST-FICO-CREDIT-SCORE is
            // PIC 9(03), all scale-free, and they map to int. This package is consequently the one place
            // where gates G23 (scale exactly 2) and G24 (RoundingMode.DOWN) are ABSENCE assertions: there
            // is no monetary field to scale and no rounding to perform, because nothing here computes.
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
            // Gate G24 as an absence assertion. ROUNDED appears zero times in all 28 programs, so the
            // module's only faithful rounding mode is DOWN - and this data path performs no arithmetic at
            // all, so it must reference no rounding mode whatsoever.
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
            // Practice B9 and gate G53, at both levels: no field injection, and every static member an
            // immutable constant. COBOL WORKING-STORAGE belongs to one program execution, so turning it
            // into a static Java field would share a browse position between concurrent executions and
            // destroy the ordering parity depends on.
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
                    // No field carries an injection annotation: everything arrives through the
                    // constructor, so there is no half-built instance to observe.
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
            // The same standard, applied to the test. Each in-memory database is named from the running
            // test's own identity by nameThisTestsDatabases(TestInfo), so nothing is shared between tests
            // and nothing depends on the order they run in (practice B7).
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

    // =============================================================================================
    // THE FILE STATUS MATRIX - gate G47: every outcome, at every call site.
    //
    // app/cbl/CBCUS01C.cbl:L104-L115 is the shape every guard chain over this dataset has:
    //
    //     IF  APPL-AOK        CONTINUE
    //     ELSE IF APPL-EOF    MOVE 'Y' TO END-OF-FILE
    //          ELSE           DISPLAY 'ERROR READING CUSTOMER FILE'
    //                         MOVE CUSTFILE-STATUS TO IO-STATUS
    //                         PERFORM Z-DISPLAY-IO-STATUS
    //                         PERFORM Z-ABEND-PROGRAM
    //
    // so '00', '10', '22' and '23' have to arrive as VALUES the caller can branch on, and only the
    // WHEN OTHER arm reaches the abend - which lives in the caller, at L158, not here.
    // =============================================================================================

    /**
     * Every status this dataset can report, driven at every call site, and the abend seam beyond them.
     *
     * <p>Gate G47 asks for all five outcomes - {@code '00'} ok, {@code '10'} end of file, {@code '22'}
     * duplicate, {@code '23'} not found, and an unexpected other - to be driven per call site, and for the
     * caller's branch structure to be able to switch on them exactly as the COBOL does.
     *
     * <p><strong>Where the abend is.</strong> {@link CustomerRepository} deliberately throws no
     * {@link AbendException}: it reports the status and hands over the {@code APPL-RESULT} the guard chain
     * would have moved, because {@code Z-ABEND-PROGRAM} and its {@code CALL 'CEE3ABD'}
     * ({@code app/cbl/CBCUS01C.cbl:L153-L159}) belong to the program, not to the I/O paragraph. The
     * translation is therefore asserted at the seam - the {@code WHEN OTHER} arm yields
     * {@code APPL-RESULT} 12, and the abend a caller raises from it carries return code 12 - rather than
     * expected as a throw from a class that correctly has none.
     */
    @Nested
    @DisplayName("Every file status is reported as a value; only WHEN OTHER reaches the caller's abend")
    class FileStatusMatrixTests {

        @Test
        @DisplayName("a test's database is disposed of when the test ends, name and schema alike")
        void aTestsDatabaseIsDisposedOf() {
            // The name is derived from the test's own identity, which stops two tests colliding - but
            // DB_CLOSE_DELAY=-1, needed for the seed to work at all, keeps each one alive and
            // addressable for the rest of the JVM. A name that outlives its test is a name another test
            // can reach, so the database is disposed of rather than merely uniquely named.
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
            // '00' is built by the found() factory because a successful read carries the record; every
            // other status is built by of(), which carries none. That asymmetry is the COBOL's: only the
            // '00' arm reaches CUSTOMER-RECORD.
            ReadResult result = FileStatus.OK.equals(status)
                    ? customerFound(new CustomerRecord())
                    : ReadResult.of(status);

            assertThat(result.status()).isEqualTo(status);
            assertThat(result.outcome()).isEqualTo(expectedOutcome);
            assertThat(result.applResult()).isEqualTo(expectedApplResult);
            assertThat(result.statusImage()).isEqualTo(expectedImage);
            // Exactly one predicate is true for any status: the arms of an EVALUATE are exclusive.
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
            // There is no add against this dataset, so '22' is unreachable from every call site - but it is
            // part of the vocabulary CBCUS01C's guard chain shares with every other dataset, so it must
            // classify correctly rather than fall into WHEN OTHER and mislead a caller.
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
            // app/cbl/CBCUS01C.cbl:L161-L174 (Z-DISPLAY-IO-STATUS) displays
            //     'FILE STATUS IS: NNNN'  with IO-STATUS-04 moved into the NNNN positions.
            // The four characters NNNN are part of the literal WS-IO-STATUS-04 is displayed beside; they
            // are NOT a placeholder to substitute, so the emitted line contains them verbatim.
            assertThat(FileStatus.DISPLAY_PREFIX).isEqualTo("FILE STATUS IS: NNNN");
            assertThat(FileStatus.toDisplayLine(status)).isEqualTo("FILE STATUS IS: NNNN" + image);
            assertThat(FileStatus.toStatusImage(status)).hasSize(FileStatus.STATUS_IMAGE_LENGTH)
                    .isEqualTo(image);
        }

        @Test
        @DisplayName("the display line for each surfaced status, written out in full")
        void rendersEachSurfacedStatusAsAWholeLiteral() {
            // The same four lines as the matrix above, written as whole literals rather than composed, so
            // the exact text a job's SYSOUT would carry is visible in this file and cannot be produced by
            // a concatenation that is itself wrong. The four characters NNNN are part of the literal
            // app/cbl/CBCUS01C.cbl:L166-L174 displays; they are not a placeholder to substitute.
            assertThat(FileStatus.toDisplayLine(FileStatus.OK))
                    .isEqualTo("FILE STATUS IS: NNNN0000");
            assertThat(FileStatus.toDisplayLine(FileStatus.END_OF_FILE))
                    .isEqualTo("FILE STATUS IS: NNNN0010");
            assertThat(FileStatus.toDisplayLine(FileStatus.DUPLICATE))
                    .isEqualTo("FILE STATUS IS: NNNN0022");
            assertThat(FileStatus.toDisplayLine(FileStatus.NOT_FOUND))
                    .isEqualTo("FILE STATUS IS: NNNN0023");
            // And the same line reached through the result types the call sites actually return, so the
            // status a caller renders is the status the operation reported.
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
            // A status whose first byte is '9' carries a binary feedback code in its second, which is the
            // case app/cbl/CBCUS01C.cbl:L162-L163 tests for with
            //     IF IO-STATUS NOT NUMERIC OR IO-STAT1 = '9'
            // before rendering the byte as three digits. The translated permanent error reports no VSAM
            // feedback code, so the byte is zero and the line is NNNN9000.
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
            // app/cbl/CBCUS01C.cbl:L118-L134 (0000-CUSTFILE-OPEN) and app/cbl/CBTRN01C.cbl:L271-L287.
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
            // app/cbl/CBCUS01C.cbl:L92-L116 - READ ... INTO until CUSTFILE-STATUS is '10'.
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
            // app/cbl/CBSTM03B.CBL:L188-L193, app/cbl/COACTVWC.cbl:L825-L872 and
            // app/cbl/COACTUPC.cbl:L3919-L3948. Every one of these branches on a value.
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
            // app/cbl/COACTUPC.cbl:L4085-L4101 - DFHRESP(NORMAL) at L4095, and
            // SET LOCKED-BUT-UPDATE-FAILED TO TRUE with a SYNCPOINT ROLLBACK at L4098-L4101 otherwise.
            List<String> rows = fixtureRows();
            CustomerRepository repository = repository(seeded(rows));
            byte[] present = rows.get(0).getBytes(ASCII);
            byte[] absent = ("999999999" + rows.get(0).substring(NINE)).getBytes(ASCII);

            assertThatCode(() -> {
                assertThat(repository.rewrite(present).status()).isEqualTo(FileStatus.OK);
                assertThat(repository.rewrite(absent).status()).isEqualTo(FileStatus.NOT_FOUND);
            }).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("the WHEN OTHER arm is a status here and becomes the caller's abend, RETURN-CODE 12")
        void theWhenOtherArmBecomesTheCallersAbend() {
            // The seam. The repository reports; CBCUS01C's guard chain then moves 12 to APPL-RESULT
            // (L101, L124, L142), displays its error line, performs Z-DISPLAY-IO-STATUS and performs
            // Z-ABEND-PROGRAM, whose CALL 'CEE3ABD' at L158 is what AbendException translates.
            CustomerRepository repository = repository(unreachable());

            ReadResult read = repository.readByKey(1L);
            WriteResult write = repository.rewrite(new CustomerRecord());

            assertThat(read.isOther()).isTrue();
            assertThat(write.isOther()).isTrue();
            assertThat(read.applResult()).isEqualTo(CustomerRepository.APPL_RESULT_FATAL);
            assertThat(write.applResult()).isEqualTo(CustomerRepository.APPL_RESULT_FATAL);

            // The abend the caller raises from that outcome, with the RETURN-CODE the COBOL sets. The
            // constant is checked against the repository's own so the two cannot drift apart.
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
            // Asserted rather than assumed, because a repository that abended would take the DISPLAY line
            // and the rendered status image with it - CBCUS01C emits both BEFORE Z-ABEND-PROGRAM runs, and
            // a throw from the I/O paragraph would skip them.
            CustomerRepository repository = repository(unreachable());

            assertThatCode(() -> {
                CustomerFile file = repository.openInput();
                file.readNext();
                file.readByKey(1L);
                file.readByKey("000000001");
                file.closeFile();
                repository.readByKey(1L);
                repository.readByKey("000000001");
                repository.rewrite(new CustomerRecord());
            }).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("the four named statuses round-trip through the CICS response vocabulary")
        void theNamedStatusesMapOntoTheCicsVocabulary() {
            // The online programs branch on EVALUATE WS-RESP-CD and the batch ones on FILE STATUS, so the
            // two vocabularies have to agree on the same four outcomes.
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

    // =================================================================================================
    // Synthesised customer read outcomes. A ReadResult carries the decoded record AND the bytes it was
    // decoded from, because DISPLAY CUSTOMER-RECORD (app/cbl/CBCUS01C.cbl:78 and :96) writes the record
    // area and the area's trailing FILLER holds whatever the row held. A test constructing an outcome has
    // no row, so the image it supplies is the one a row of exactly this record would carry - stated once
    // here rather than at every call site.
    // =================================================================================================

    /**
     * The successful arm over a synthesised row of this record.
     *
     * @param customer the record the row would carry
     * @return the outcome, carrying the record and the image a row of it would hold
     */
    private static CustomerRepository.ReadResult customerFound(CustomerRecord customer) {
        return CustomerRepository.ReadResult.found(customer,
                customer.recordImage(StandardCharsets.US_ASCII));
    }
}
