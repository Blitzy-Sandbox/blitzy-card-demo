package com.vsergeychik.carddemo.user;

import com.vsergeychik.carddemo.common.CicsResponse;
import com.vsergeychik.carddemo.common.FileStatus;
import com.vsergeychik.carddemo.common.FileStatus.Outcome;
import com.vsergeychik.carddemo.common.RecordImageForm;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBinding;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBindings;
import com.vsergeychik.carddemo.user.SecUserRepository.BrowseCursor;
import com.vsergeychik.carddemo.user.SecUserRepository.HeldRecord;
import com.vsergeychik.carddemo.user.SecUserRepository.ReadResult;
import com.vsergeychik.carddemo.user.SecUserRepository.WriteResult;
import com.vsergeychik.carddemo.user.model.SecUserRecord;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * Behavioural tests for {@link SecUserRepository}, the {@code USRSEC} data-access layer.
 *
 * <h2>What is being proved</h2>
 * Every one of the nine access paths corresponds to exactly one {@code EXEC CICS} command in one of the
 * five COBOL programs that touch the security file, and every {@code WHEN} arm those programs branch on
 * has to be reachable and distinguishable from its neighbours. That is what this class drives: not "the
 * repository works", but "each outcome the source enumerates arrives as its own outcome". Where two
 * conditions share one action in the COBOL - {@code DUPKEY} and {@code DUPREC} at
 * {@code app/cbl/COUSR01C.cbl:L260-L261} - they are proved to stay two, because collapsing them here
 * would take the choice away from the caller that the source leaves open.
 *
 * <h2>How the backend is stood up</h2>
 * Two harnesses, chosen per test rather than mixed:
 * <ul>
 *   <li>a private in-memory relation, seeded with records transcribed from
 *       {@code app/jcl/DUSRSECJ.jcl:L35-L44}, for everything that has an observable data outcome. It is
 *       created <strong>without</strong> a key constraint on purpose, so a test can seed two rows under
 *       one key and drive the fan-out arm a unique primary key would otherwise make unreachable;</li>
 *   <li>a mocked JDBC chain for the outcomes no backend can be asked to produce on demand - a row whose
 *       record-image column holds nothing, a relation that describes no usable column, an
 *       {@code UPDATE} that affects a different number of rows than the count that preceded it.</li>
 * </ul>
 * Each relation gets its own database name, so tests neither share state nor depend on order.
 */
@DisplayName("SecUserRepository - the USRSEC security-user file")
class SecUserRepositoryTest {

    /** The test code page. Single-byte, as the record-image form requires. */
    private static final Charset ASCII = StandardCharsets.US_ASCII;

    /** A well-formed dataset name for the test relation. The real one lives in configuration. */
    private static final String TEST_DSNAME = "TEST.USRSEC.VSAM.KSDS";

    /** The record-image column of the test relation. */
    private static final String RECORD_IMAGE_COLUMN = "RECORD_IMAGE";

    /** The declared record width, from the copybook. */
    private static final int EIGHTY = 80;

    /** The declared key width, from {@code SEC-USR-ID PIC X(08)}. */
    private static final int EIGHT = 8;

    /** Makes each in-memory relation private to its test. */
    private static final AtomicInteger DATABASE_SEQUENCE = new AtomicInteger();

    // =============================================================================================
    // Harness
    // =============================================================================================

    /**
     * Builds the single binding this repository resolves, with every component under the test's control.
     *
     * @param dsname       the dataset name to configure
     * @param recordLength the record length to configure
     * @param keyLength    the key length, or {@code null} to omit it as some bindings do
     * @param keyOffset    the key offset, or {@code null} to omit it
     * @return a catalogue containing exactly that binding
     */
    private static DatasetBindings bindings(String dsname, int recordLength, Integer keyLength,
            Integer keyOffset) {
        DatasetBindings catalogue = new DatasetBindings();
        catalogue.put(SecUserRepository.CICS_FILE_NAME, new DatasetBinding(dsname, "ksds", false, "FB",
                null, recordLength, "CSUSR01Y", keyLength, keyOffset, null, null));
        return catalogue;
    }

    /**
     * The correctly configured binding every behavioural test uses.
     *
     * @return a catalogue naming {@link #TEST_DSNAME} at the copybook's geometry
     */
    private static DatasetBindings validBindings() {
        return bindings(TEST_DSNAME, EIGHTY, EIGHT, null);
    }

    /**
     * A repository over the given template and the correctly configured binding.
     *
     * @param template the template to reach the relation with
     * @return the repository
     */
    private static SecUserRepository repository(JdbcTemplate template) {
        return new SecUserRepository(template, validBindings(), ASCII, RecordImageForm.CHARACTER);
    }

    /**
     * Creates a private in-memory relation with one record-image column and seeds it.
     *
     * @param rows the record images to insert, in the order given; a {@code null} element is inserted as
     *             a row whose column holds nothing
     * @return a template over the seeded relation
     */
    private static JdbcTemplate seeded(List<String> rows) {
        return seeded(rows, EIGHTY);
    }

    /**
     * Creates a private in-memory relation whose record-image column is as wide as asked, and seeds it.
     *
     * <p>The width is a parameter for one reason: a test has to be able to store an image that is
     * <em>not</em> the copybook width, to prove such an image is refused rather than padded or truncated.
     *
     * @param rows        the record images to insert, in the order given
     * @param columnWidth the declared width of the record-image column
     * @return a template over the seeded relation
     */
    private static JdbcTemplate seeded(List<String> rows, int columnWidth) {
        JdbcTemplate template = emptyRelation("CREATE TABLE \"" + TEST_DSNAME + "\" ("
                + RECORD_IMAGE_COLUMN + " VARCHAR(" + columnWidth + "))");
        for (String row : rows) {
            template.update("INSERT INTO \"" + TEST_DSNAME + "\" VALUES (?)", row);
        }
        return template;
    }

    /**
     * Creates a private in-memory database and executes one statement against it.
     *
     * @param ddl the statement to execute, or {@code null} to leave the database empty
     * @return a template over the database
     */
    private static JdbcTemplate emptyRelation(String ddl) {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:secuser" + DATABASE_SEQUENCE.incrementAndGet()
                        + ";DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE", "sa", "");
        dataSource.setDriverClassName("org.h2.Driver");
        JdbcTemplate template = new JdbcTemplate(dataSource);
        if (ddl != null) {
            template.execute(ddl);
        }
        return template;
    }

    /**
     * A database with no relation at all, so every operation must report the permanent-error status.
     *
     * @return a template over an empty database
     */
    private static JdbcTemplate missingRelation() {
        return emptyRelation(null);
    }

    /**
     * A transaction template over a seeded relation, for the paths that require a genuine unit of work.
     *
     * @param template the template whose data source the transaction is bound to
     * @return a transaction template that begins and commits a real transaction
     */
    private static TransactionTemplate transactionOver(JdbcTemplate template) {
        return new TransactionTemplate(new DataSourceTransactionManager(template.getDataSource()));
    }

    /**
     * Runs work with a unit of work declared active, for tests whose backend is mocked.
     *
     * <p>A mocked chain cannot begin a real transaction, and what the repository actually requires is
     * that one be active on the calling thread. Declaring it directly is therefore the honest way to
     * satisfy the precondition without pretending a mock has transactional semantics. Always undone.
     *
     * @param work the work to run
     */
    private static void withUnitOfWork(Runnable work) {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            work.run();
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    /** Four seed records transcribed from {@code app/jcl/DUSRSECJ.jcl:L35-L44}, padded to 80. */
    private static List<String> seedRows() {
        List<String> rows = new ArrayList<>();
        rows.add(row("ADMIN001", "MARGARET", "GOLD", "PASSWORD", "A"));
        rows.add(row("ADMIN002", "RUSSELL", "RUSSELL", "PASSWORD", "A"));
        rows.add(row("USER0001", "LAWRENCE", "THOMAS", "PASSWORD", "U"));
        rows.add(row("USER0002", "AJITH", "KUMAR", "PASSWORD", "U"));
        return rows;
    }

    /**
     * One stored row, encoded exactly as the repository would write it.
     *
     * @param id    the user id
     * @param first the first name
     * @param last  the last name
     * @param pwd   the plaintext password, as the legacy design holds it
     * @param type  the user type
     * @return the 80-character stored image
     */
    private static String row(String id, String first, String last, String pwd, String type) {
        return new String(SecUserRecord.encode(record(id, first, last, pwd, type), ASCII), ASCII);
    }

    /**
     * One record, every field padded to its declared width.
     *
     * @param id    the user id
     * @param first the first name
     * @param last  the last name
     * @param pwd   the plaintext password
     * @param type  the user type
     * @return the record
     */
    private static SecUserRecord record(String id, String first, String last, String pwd, String type) {
        return SecUserRecord.of(id, first, last, pwd, type, ASCII);
    }

    /**
     * A mocked chain that describes a column of the caller's choosing and returns rows on demand.
     *
     * @param describedColumn what the metadata reports at ordinal 1, or {@code null} for no column
     * @param columnCount     how many columns the metadata reports
     * @param rowsFromQuery   how many rows every {@code executeQuery} yields
     * @param updateCount     what every {@code executeUpdate} reports
     * @return a template over the mocked chain
     * @throws SQLException never; declared because the mocked JDBC methods declare it
     */
    private static JdbcTemplate mockedChain(String describedColumn, int columnCount, int rowsFromQuery,
            int updateCount) throws SQLException {
        return mockedChain(describedColumn, columnCount, rowsFromQuery, updateCount,
                row("ADMIN001", "MARGARET", "GOLD", "PASSWORD", "A"));
    }

    /**
     * A mocked chain whose rows carry the record image of the caller's choosing, including none.
     *
     * <p>A row that is present but whose record-image column holds nothing cannot be produced by a real
     * relation through either access path this repository uses: {@code NULL LIKE 'key%'} and
     * {@code NULL >= 'key'} both evaluate to unknown, so such a row never satisfies a keyed predicate or a
     * browse predicate and is simply not returned. The guard against it is therefore driven here rather
     * than assumed away, because the outcome it must produce - an invalid request, emphatically not an
     * absence and not an end of file - is one a caller would otherwise misread.
     *
     * @param describedColumn what the metadata reports at ordinal 1, or {@code null} for no column
     * @param columnCount     how many columns the metadata reports
     * @param rowsFromQuery   how many rows every {@code executeQuery} yields
     * @param updateCount     what every {@code executeUpdate} reports
     * @param storedImage     what each row's record-image column holds, or {@code null} for nothing
     * @return a template over the mocked chain
     * @throws SQLException never; declared because the mocked JDBC methods declare it
     */
    private static JdbcTemplate mockedChain(String describedColumn, int columnCount, int rowsFromQuery,
            int updateCount, String storedImage) throws SQLException {
        DataSource dataSource = Mockito.mock(DataSource.class);
        Connection connection = Mockito.mock(Connection.class);
        Statement statement = Mockito.mock(Statement.class);
        ResultSet probe = Mockito.mock(ResultSet.class);
        ResultSetMetaData metaData = Mockito.mock(ResultSetMetaData.class);
        PreparedStatement prepared = Mockito.mock(PreparedStatement.class);
        Mockito.when(dataSource.getConnection()).thenReturn(connection);
        Mockito.when(connection.createStatement()).thenReturn(statement);
        Mockito.when(statement.executeQuery(Mockito.anyString())).thenReturn(probe);
        Mockito.when(probe.getMetaData()).thenReturn(metaData);
        Mockito.when(metaData.getColumnCount()).thenReturn(columnCount);
        Mockito.when(metaData.getColumnName(1)).thenReturn(describedColumn);
        Mockito.when(connection.prepareStatement(Mockito.anyString())).thenReturn(prepared);
        Mockito.when(prepared.executeUpdate()).thenReturn(updateCount);
        // A FRESH result set per query, each yielding exactly rowsFromQuery rows. One shared result set
        // would carry its cursor from one statement to the next, so a positioning probe would silently
        // consume the row the read after it expects - which is a property of the mock and not of the code
        // under test, and would make these tests assert the wrong thing.
        Mockito.when(prepared.executeQuery()).thenAnswer(query -> {
            ResultSet rows = Mockito.mock(ResultSet.class);
            Mockito.when(rows.next()).thenAnswer(new org.mockito.stubbing.Answer<Boolean>() {
                private int served;

                @Override
                public Boolean answer(org.mockito.invocation.InvocationOnMock cursor) {
                    return served++ < rowsFromQuery;
                }
            });
            Mockito.when(rows.getString(1)).thenReturn(storedImage);
            return rows;
        });
        return new JdbcTemplate(dataSource);
    }

    /**
     * A template whose every parameterised query answers {@code null}, which the extractors never do.
     *
     * <p>The guards this drives are defensive: {@code firstRow} and {@code countRows} both check the
     * template's answer for {@code null} even though their own extractors cannot return one. Mocking the
     * template is the only way to prove those guards degrade to a determinate outcome rather than
     * becoming a {@link NullPointerException} in the caller, so they are proved rather than assumed.
     *
     * @return a mocked template that describes a usable column and answers {@code null} to everything else
     */
    private static JdbcTemplate templateAnsweringNull() {
        JdbcTemplate template = Mockito.mock(JdbcTemplate.class);
        Mockito.when(template.query(Mockito.anyString(),
                Mockito.<ResultSetExtractor<String>>any())).thenReturn(RECORD_IMAGE_COLUMN);
        // Every other interaction is left unstubbed, so it answers null (or zero for update).
        return template;
    }

    // =============================================================================================

    @Nested
    @DisplayName("Record geometry - eighty bytes, and the filler that must never be dropped")
    class Geometry {

        @Test
        @DisplayName("every stored row is exactly 80 bytes with SEC-USR-FILLER space-filled")
        void everyStoredRowIsEightyBytesWithTheFillerIntact() {
            for (String stored : seedRows()) {
                assertThat(stored).hasSize(EIGHTY);
                assertThat(stored.substring(57)).isEqualTo(" ".repeat(23));
                SecUserRecord decoded = SecUserRecord.decode(stored.getBytes(ASCII), ASCII);
                assertThat(decoded.secUsrFiller()).isEqualTo(" ".repeat(23));
                assertThat(new String(SecUserRecord.encode(decoded, ASCII), ASCII)).isEqualTo(stored);
            }
        }

        @Test
        @DisplayName("the repository publishes the copybook's geometry, not its own")
        void theRepositoryPublishesTheCopybooksGeometry() {
            assertThat(SecUserRepository.RECORD_LENGTH).isEqualTo(SecUserRecord.RECORD_LENGTH)
                    .isEqualTo(EIGHTY);
            assertThat(SecUserRepository.KEY_LENGTH).isEqualTo(SecUserRecord.KEY_LENGTH).isEqualTo(EIGHT);
            assertThat(SecUserRepository.KEY_OFFSET).isEqualTo(SecUserRecord.KEY_OFFSET).isZero();
            assertThat(SecUserRepository.CICS_FILE_NAME).isEqualTo("USRSEC");
            assertThat(SecUserRepository.CICS_FILE_NAME_IMAGE)
                    .hasSize(SecUserRepository.CICS_FILE_NAME_LENGTH).isEqualTo("USRSEC  ");
            assertThat(SecUserRepository.LOW_VALUES_KEY).isEqualTo("\u0000".repeat(EIGHT));
            assertThat(SecUserRepository.HIGH_VALUES_KEY).isEqualTo("\u00ff".repeat(EIGHT));
            assertThat(SecUserRepository.PERMANENT_ERROR_STATUS)
                    .hasSize(FileStatus.STATUS_LENGTH).startsWith("9");
            assertThat(SecUserRepository.CICS_RESP2_NOT_APPLICABLE)
                    .isEqualTo(FileStatus.NO_REASON_CODE);
        }

        @ParameterizedTest(name = "a {0}-byte row is refused, never padded or truncated")
        @ValueSource(ints = {57, 100})
        @DisplayName("a row that is not 80 bytes is refused, because a shifted SEC-USR-TYPE is a privilege")
        void aRowOfTheWrongWidthIsRefused(int storedWidth) {
            String full = row("ADMIN001", "MARGARET", "GOLD", "PASSWORD", "A");
            String wrong = storedWidth < EIGHTY ? full.substring(0, storedWidth)
                    : full + " ".repeat(storedWidth - EIGHTY);
            ReadResult result = repository(seeded(List.of(wrong), storedWidth)).read("ADMIN001");
            assertThat(result.isOther()).isTrue();
            assertThat(result.cicsResp()).hasValue(FileStatus.LENGERR);
            assertThat(result.record()).isEmpty();
            assertThat(result.hold()).isEmpty();
        }
    }

    @Nested
    @DisplayName("READ - app/cbl/COSGN00C.cbl:L211-L257, the raw-RESP 0 / 13 / OTHER split")
    class Read {

        @Test
        @DisplayName("WHEN 0 - the record arrives untrimmed, and no lock is taken")
        void whenZeroTheRecordArrivesUntrimmed() {
            ReadResult normal = repository(seeded(seedRows())).read("ADMIN001");
            assertThat(normal.isFound()).isTrue();
            assertThat(normal.isNotFound()).isFalse();
            assertThat(normal.isEndOfFile()).isFalse();
            assertThat(normal.isOther()).isFalse();
            assertThat(normal.outcome()).isEqualTo(Outcome.OK);
            assertThat(normal.status()).isEqualTo(FileStatus.OK);
            assertThat(normal.cicsResp()).hasValue(FileStatus.NORMAL);
            assertThat(normal.cicsResp2()).isEqualTo(FileStatus.NO_REASON_CODE);
            assertThat(normal.statusImage()).hasSize(FileStatus.STATUS_IMAGE_LENGTH);
            assertThat(normal.diagnostic()).isEmpty();
            // PIC X is padded on write and never trimmed on read.
            assertThat(normal.requireRecord().secUsrLname()).isEqualTo("GOLD" + " ".repeat(16));
            assertThat(normal.requireRecord().secUsrType()).isEqualTo("A");
            // A plain READ carries no UPDATE option, so it holds nothing.
            assertThat(normal.hold()).isEmpty();
            assertThatIllegalStateException().isThrownBy(normal::requireHold);
        }

        @Test
        @DisplayName("WHEN 13 - 'User not found', distinct from the WHEN OTHER arm")
        void whenThirteenIsNotFound() {
            ReadResult notFound = repository(seeded(seedRows())).read("NOSUCH01");
            assertThat(notFound.isNotFound()).isTrue();
            assertThat(notFound.isFound()).isFalse();
            assertThat(notFound.isOther()).isFalse();
            assertThat(notFound.status()).isEqualTo(FileStatus.NOT_FOUND);
            assertThat(notFound.cicsResp()).hasValue(FileStatus.NOTFND);
            assertThatIllegalStateException().isThrownBy(notFound::requireRecord);
        }

        @Test
        @DisplayName("WHEN OTHER - 'Unable to verify the User', carrying what the backend said")
        void whenOtherCarriesTheBackendsDiagnosis() {
            ReadResult other = repository(missingRelation()).read("ADMIN001");
            assertThat(other.isOther()).isTrue();
            assertThat(other.outcome()).isEqualTo(Outcome.OTHER);
            assertThat(other.diagnostic()).isPresent();
            assertThat(other.diagnostic().orElseThrow().describe()).isNotBlank();
            assertThat(other.cicsResp()).isEmpty();
            assertThat(other.record()).isEmpty();
        }

        @Test
        @DisplayName("a row present but unreadable is an invalid request, not an absence")
        void aRowWithNoImageIsAnInvalidRequestRatherThanAnAbsence() throws SQLException {
            ReadResult result = repository(mockedChain(RECORD_IMAGE_COLUMN, 1, 1, 0, null))
                    .read("ADMIN001");
            assertThat(result.isOther()).isTrue();
            assertThat(result.isNotFound()).isFalse();
            assertThat(result.cicsResp()).hasValue(FileStatus.INVREQ);
        }

        @Test
        @DisplayName("a row whose column is null never satisfies a keyed predicate, so it is not found")
        void aNullColumnNeverSatisfiesAKeyedPredicate() {
            List<String> withNull = new ArrayList<>();
            withNull.add(null);
            // NULL LIKE 'ADMIN001%' is unknown, so SQL returns no row at all - the absence condition.
            assertThat(repository(seeded(withNull)).read("ADMIN001").isNotFound()).isTrue();
        }

        @Test
        @DisplayName("the RIDFLD is always PIC X(08), so any other width is a caller defect")
        void theKeyMustBeTheDeclaredWidth() {
            SecUserRepository repository = repository(seeded(seedRows()));
            assertThatNullPointerException().isThrownBy(() -> repository.read(null));
            assertThatIllegalArgumentException().isThrownBy(() -> repository.read("SHORT"));
            assertThatIllegalArgumentException().isThrownBy(() -> repository.read("TOOLONGKEY"));
            assertThatIllegalArgumentException().isThrownBy(() -> repository.read(""));
        }

        @Test
        @DisplayName("a keyed read escapes the key, so a wildcard in it matches nothing extra")
        void aWildcardInTheKeyIsEscapedRatherThanTrusted() {
            // Eight characters, all LIKE metacharacters. Unescaped this would match every record.
            assertThat(repository(seeded(seedRows())).read("%%%%%%%%").isNotFound()).isTrue();
            assertThat(repository(seeded(seedRows())).read("________").isNotFound()).isTrue();
        }

        @Test
        @DisplayName("a template that answers null is degraded to not-found, never to an exception")
        void aNullAnswerFromTheTemplateIsDegradedToNotFound() {
            assertThat(repository(templateAnsweringNull()).read("ADMIN001").isNotFound()).isTrue();
        }
    }

    @Nested
    @DisplayName("READ ... UPDATE - COUSR02C:L322-L331 and COUSR03C:L269-L278, and what holds the lock")
    class ReadForUpdate {

        @Test
        @DisplayName("a successful locking read hands back the hold a rewrite or a delete needs")
        void aSuccessfulLockingReadHandsBackTheHold() {
            JdbcTemplate template = seeded(seedRows());
            SecUserRepository repository = repository(template);
            transactionOver(template).executeWithoutResult(status -> {
                ReadResult held = repository.readForUpdate("ADMIN001");
                assertThat(held.isFound()).isTrue();
                assertThat(held.hold()).isPresent();
                HeldRecord hold = held.requireHold();
                assertThat(hold.record().secUsrId()).isEqualTo("ADMIN001");
                assertThat(hold.datasetName()).isEqualTo(TEST_DSNAME);
            });
        }

        @Test
        @DisplayName("an absent key reports WHEN DFHRESP(NOTFND) and holds nothing")
        void anAbsentKeyHoldsNothing() {
            JdbcTemplate template = seeded(seedRows());
            SecUserRepository repository = repository(template);
            transactionOver(template).executeWithoutResult(status -> {
                ReadResult absent = repository.readForUpdate("NOSUCH01");
                assertThat(absent.isNotFound()).isTrue();
                assertThat(absent.hold()).isEmpty();
            });
        }

        @Test
        @DisplayName("outside a unit of work the lock would die before the caller could act, so it refuses")
        void outsideAUnitOfWorkTheLockingReadRefuses() {
            SecUserRepository repository = repository(seeded(seedRows()));
            assertThatIllegalStateException()
                    .isThrownBy(() -> repository.readForUpdate("ADMIN001"))
                    .withMessageContaining(TEST_DSNAME);
        }

        @Test
        @DisplayName("the key is validated before the unit of work is demanded")
        void theKeyIsValidatedBeforeTheUnitOfWorkIsDemanded() {
            SecUserRepository repository = repository(seeded(seedRows()));
            assertThatIllegalArgumentException().isThrownBy(() -> repository.readForUpdate("SHORT"));
            assertThatNullPointerException().isThrownBy(() -> repository.readForUpdate(null));
        }

        @Test
        @DisplayName("a locking read requests the row lock the plain read does not")
        void theLockingReadRequestsTheRowLock() throws SQLException {
            List<String> prepared = new ArrayList<>();
            JdbcTemplate template = recordingChain(prepared);
            SecUserRepository repository = repository(template);
            repository.read("ADMIN001");
            withUnitOfWork(() -> repository.readForUpdate("ADMIN001"));
            assertThat(prepared).hasSize(2);
            assertThat(prepared.get(0)).doesNotContain("FOR UPDATE");
            assertThat(prepared.get(1)).contains("FOR UPDATE");
        }

        /**
         * A mocked chain that records the text of every statement prepared against it and returns no rows.
         *
         * @param prepared the list every prepared statement's text is appended to, in order
         * @return a template over the recording chain
         * @throws SQLException never; declared because the mocked JDBC methods declare it
         */
        private JdbcTemplate recordingChain(List<String> prepared) throws SQLException {
            DataSource dataSource = Mockito.mock(DataSource.class);
            Connection connection = Mockito.mock(Connection.class);
            Statement statement = Mockito.mock(Statement.class);
            ResultSet probe = Mockito.mock(ResultSet.class);
            ResultSetMetaData metaData = Mockito.mock(ResultSetMetaData.class);
            PreparedStatement preparedStatement = Mockito.mock(PreparedStatement.class);
            ResultSet empty = Mockito.mock(ResultSet.class);
            Mockito.when(dataSource.getConnection()).thenReturn(connection);
            Mockito.when(connection.createStatement()).thenReturn(statement);
            Mockito.when(statement.executeQuery(Mockito.anyString())).thenReturn(probe);
            Mockito.when(probe.getMetaData()).thenReturn(metaData);
            Mockito.when(metaData.getColumnCount()).thenReturn(1);
            Mockito.when(metaData.getColumnName(1)).thenReturn(RECORD_IMAGE_COLUMN);
            Mockito.when(connection.prepareStatement(Mockito.anyString())).thenAnswer(invocation -> {
                prepared.add(invocation.getArgument(0));
                return preparedStatement;
            });
            Mockito.when(preparedStatement.executeQuery()).thenReturn(empty);
            Mockito.when(empty.next()).thenReturn(false);
            return new JdbcTemplate(dataSource);
        }
    }

    @Nested
    @DisplayName("STARTBR - COUSR00C:L588-L614, with GTEQ commented out at L592")
    class StartBrowse {

        @Test
        @DisplayName("LOW-VALUES positions at the first record - WHEN DFHRESP(NORMAL)")
        void lowValuesOpensAtTheStartOfTheFile() {
            try (BrowseCursor cursor =
                         repository(seeded(seedRows())).startBrowse(SecUserRepository.LOW_VALUES_KEY)) {
                assertThat(cursor.openOutcome()).isEqualTo(Outcome.OK);
                assertThat(cursor.openStatus()).isEqualTo(FileStatus.OK);
                assertThat(cursor.openCicsResp()).hasValue(FileStatus.NORMAL);
                assertThat(cursor.openDiagnostic()).isEmpty();
                assertThat(cursor.isOpen()).isTrue();
                assertThat(cursor.isEnded()).isFalse();
                assertThat(cursor.anchorKey()).isEqualTo(SecUserRepository.LOW_VALUES_KEY);
                assertThat(cursor.positionKey()).isEmpty();
                assertThat(cursor.returned()).isZero();
                assertThat(cursor.datasetName()).isEqualTo(TEST_DSNAME);
            }
        }

        @Test
        @DisplayName("HIGH-VALUES finds nothing at or after it - WHEN DFHRESP(NOTFND), reproduced not fixed")
        void highValuesReportsNotFoundBecauseNothingIsAtOrAfterIt() {
            try (BrowseCursor cursor =
                         repository(seeded(seedRows())).startBrowse(SecUserRepository.HIGH_VALUES_KEY)) {
                assertThat(cursor.openOutcome()).isEqualTo(Outcome.NOT_FOUND);
                assertThat(cursor.openStatus()).isEqualTo(FileStatus.NOT_FOUND);
                assertThat(cursor.openCicsResp()).hasValue(FileStatus.NOTFND);
                assertThat(cursor.openDiagnostic()).isEmpty();
                assertThat(cursor.isOpen()).isFalse();
                // The source guards both paging loops with IF NOT ERR-FLG-ON, so it never reads here. A
                // caller that ignores the guard gets the open's own outcome back.
                assertThat(cursor.readNext().isNotFound()).isTrue();
                assertThat(cursor.readPrevious().isNotFound()).isTrue();
            }
        }

        @Test
        @DisplayName("an unreachable dataset opens as WHEN OTHER, carrying the diagnosis to every read")
        void anUnreachableDatasetOpensAsWhenOther() {
            try (BrowseCursor cursor =
                         repository(missingRelation()).startBrowse(SecUserRepository.LOW_VALUES_KEY)) {
                assertThat(cursor.openOutcome()).isEqualTo(Outcome.OTHER);
                assertThat(cursor.openDiagnostic()).isPresent();
                assertThat(cursor.isOpen()).isFalse();
                ReadResult refused = cursor.readNext();
                assertThat(refused.isOther()).isTrue();
                assertThat(refused.diagnostic()).isPresent();
            }
        }

        @Test
        @DisplayName("GTEQ positioning: a key that exists and a key that does not both position")
        void positioningIsGreaterThanOrEqualRatherThanEqual() {
            SecUserRepository repository = repository(seeded(seedRows()));
            try (BrowseCursor exact = repository.startBrowse("ADMIN002")) {
                assertThat(exact.openOutcome()).isEqualTo(Outcome.OK);
                assertThat(exact.readNext().requireRecord().secUsrId()).isEqualTo("ADMIN002");
            }
            // 'ADMIN0015' would sort between ADMIN001 and ADMIN002; an eight-character key between them
            // is 'ADMIN00Z'. Equal-only positioning would report NOTFND here, and both paging directions
            // would break, which is exactly why the commented-out GTEQ must not be read as EQUAL.
            try (BrowseCursor between = repository.startBrowse("ADMIN00Z")) {
                assertThat(between.openOutcome()).isEqualTo(Outcome.OK);
                assertThat(between.readNext().requireRecord().secUsrId()).isEqualTo("USER0001");
            }
        }

        @Test
        @DisplayName("the anchor key is validated exactly as a RIDFLD is")
        void theAnchorKeyIsValidated() {
            SecUserRepository repository = repository(seeded(seedRows()));
            assertThatNullPointerException().isThrownBy(() -> repository.startBrowse(null));
            assertThatIllegalArgumentException().isThrownBy(() -> repository.startBrowse("SHORT"));
        }

        @Test
        @DisplayName("a template that answers null positions nothing rather than failing")
        void aNullAnswerPositionsNothing() {
            try (BrowseCursor cursor = repository(templateAnsweringNull())
                    .startBrowse(SecUserRepository.LOW_VALUES_KEY)) {
                assertThat(cursor.openOutcome()).isEqualTo(Outcome.NOT_FOUND);
            }
        }
    }

    @Nested
    @DisplayName("READNEXT and READPREV - COUSR00C:L621-L682, the anchor read and ENDFILE")
    class BrowseReads {

        @Test
        @DisplayName("READNEXT walks ascending, then reports ENDFILE repeatably rather than wrapping")
        void readNextWalksAscendingThenEndsRepeatably() {
            List<String> seen = new ArrayList<>();
            try (BrowseCursor cursor =
                         repository(seeded(seedRows())).startBrowse(SecUserRepository.LOW_VALUES_KEY)) {
                ReadResult next = cursor.readNext();
                while (next.isFound()) {
                    seen.add(next.requireRecord().secUsrId());
                    next = cursor.readNext();
                }
                assertThat(next.isEndOfFile()).isTrue();
                assertThat(next.isNotFound()).isFalse();
                assertThat(next.status()).isEqualTo(FileStatus.END_OF_FILE);
                assertThat(next.cicsResp()).hasValue(FileStatus.ENDFILE);
                // The position is left alone at the end, so the outcome repeats.
                assertThat(cursor.readNext().isEndOfFile()).isTrue();
                assertThat(cursor.returned()).isEqualTo(4);
                assertThat(cursor.positionKey()).hasValue("USER0002");
            }
            assertThat(seen).containsExactly("ADMIN001", "ADMIN002", "USER0001", "USER0002");
        }

        @Test
        @DisplayName("READPREV anchors at-or-after the key - the record COUSR00C:L342-L344 discards")
        void readPreviousAnchorsThenWalksDescending() {
            List<String> seen = new ArrayList<>();
            try (BrowseCursor cursor = repository(seeded(seedRows())).startBrowse("USER0001")) {
                ReadResult previous = cursor.readPrevious();
                while (previous.isFound()) {
                    seen.add(previous.requireRecord().secUsrId());
                    previous = cursor.readPrevious();
                }
                assertThat(previous.isEndOfFile()).isTrue();
                assertThat(cursor.readPrevious().isEndOfFile()).isTrue();
            }
            assertThat(seen).containsExactly("USER0001", "ADMIN002", "ADMIN001");
        }

        @Test
        @DisplayName("both directions read one position, as one STARTBR serves both paragraphs")
        void bothDirectionsShareOnePosition() {
            try (BrowseCursor cursor =
                         repository(seeded(seedRows())).startBrowse(SecUserRepository.LOW_VALUES_KEY)) {
                assertThat(cursor.readNext().requireRecord().secUsrId()).isEqualTo("ADMIN001");
                assertThat(cursor.readNext().requireRecord().secUsrId()).isEqualTo("ADMIN002");
                assertThat(cursor.readPrevious().requireRecord().secUsrId()).isEqualTo("ADMIN001");
                assertThat(cursor.readPrevious().isEndOfFile()).isTrue();
            }
        }

        @Test
        @DisplayName("a row present but unreadable stops the browse as an invalid request, not an end")
        void anUnreadableRowIsAnInvalidRequestRatherThanAnEnd() throws SQLException {
            try (BrowseCursor cursor = repository(mockedChain(RECORD_IMAGE_COLUMN, 1, 1, 0, null))
                    .startBrowse(SecUserRepository.LOW_VALUES_KEY)) {
                ReadResult result = cursor.readNext();
                assertThat(result.isOther()).isTrue();
                assertThat(result.isEndOfFile()).isFalse();
                assertThat(result.cicsResp()).hasValue(FileStatus.INVREQ);
                // The position did not advance, so a retry retries the same step.
                assertThat(cursor.returned()).isZero();
            }
        }

        @Test
        @DisplayName("a row of the wrong width stops the browse without advancing the position")
        void aRowOfTheWrongWidthDoesNotAdvanceThePosition() {
            String short57 = row("ADMIN001", "MARGARET", "GOLD", "PASSWORD", "A").substring(0, 57);
            try (BrowseCursor cursor = repository(seeded(List.of(short57), 57))
                    .startBrowse(SecUserRepository.LOW_VALUES_KEY)) {
                assertThat(cursor.readNext().cicsResp()).hasValue(FileStatus.LENGERR);
                assertThat(cursor.returned()).isZero();
                assertThat(cursor.positionKey()).isEmpty();
            }
        }

        @Test
        @DisplayName("a backend that fails mid-browse reports the refusal, carrying its diagnosis")
        void aBackendFailingMidBrowseReportsTheRefusal() {
            JdbcTemplate template = seeded(seedRows());
            try (BrowseCursor cursor = repository(template)
                    .startBrowse(SecUserRepository.LOW_VALUES_KEY)) {
                assertThat(cursor.readNext().isFound()).isTrue();
                // The relation disappears between one read and the next.
                template.execute("DROP TABLE \"" + TEST_DSNAME + "\"");
                ReadResult refused = cursor.readNext();
                assertThat(refused.isOther()).isTrue();
                assertThat(refused.diagnostic()).isPresent();
                // The backward read reports its own refusal, naming its own direction.
                assertThat(cursor.readPrevious().isOther()).isTrue();
            }
        }

        @Test
        @DisplayName("a backward read of a browse that was never positioned reports the open's outcome")
        void aBackwardReadOfAnUnpositionedBrowseReportsTheOpensOutcome() {
            JdbcTemplate template = seeded(seedRows());
            try (BrowseCursor cursor = repository(template)
                    .startBrowse(SecUserRepository.LOW_VALUES_KEY)) {
                template.execute("DROP TABLE \"" + TEST_DSNAME + "\"");
                assertThat(cursor.readPrevious().isOther()).isTrue();
            }
        }
    }

    @Nested
    @DisplayName("ENDBR - COUSR00C:L689-L691, which reports nothing at all")
    class EndBrowse {

        @Test
        @DisplayName("ending reports nothing and refuses every later read as an invalid request")
        void endingRefusesEveryLaterRead() {
            BrowseCursor cursor =
                    repository(seeded(seedRows())).startBrowse(SecUserRepository.LOW_VALUES_KEY);
            assertThat(cursor.readNext().isFound()).isTrue();
            cursor.endBrowse();
            assertThat(cursor.isEnded()).isTrue();
            assertThat(cursor.isOpen()).isFalse();
            ReadResult afterEnd = cursor.readNext();
            assertThat(afterEnd.isOther()).isTrue();
            assertThat(afterEnd.cicsResp()).hasValue(FileStatus.INVREQ);
            assertThat(cursor.readPrevious().cicsResp()).hasValue(FileStatus.INVREQ);
        }

        @Test
        @DisplayName("ending twice does nothing, so close() after endBrowse() is safe")
        void endingTwiceIsSafe() {
            BrowseCursor cursor =
                    repository(seeded(seedRows())).startBrowse(SecUserRepository.LOW_VALUES_KEY);
            cursor.endBrowse();
            cursor.endBrowse();
            cursor.close();
            assertThat(cursor.isEnded()).isTrue();
        }

        @Test
        @DisplayName("endBrowse returns void, because the COBOL command specifies no RESP")
        void endBrowseReturnsNothing() throws NoSuchMethodException {
            assertThat(BrowseCursor.class.getMethod("endBrowse").getReturnType())
                    .isEqualTo(void.class);
        }
    }

    @Nested
    @DisplayName("Browse position is per-browse state, never repository state")
    class BrowseIsolation {

        @Test
        @DisplayName("two concurrent browses of one repository do not interfere")
        void twoConcurrentBrowsesDoNotInterfere() {
            SecUserRepository repository = repository(seeded(seedRows()));
            try (BrowseCursor first = repository.startBrowse(SecUserRepository.LOW_VALUES_KEY);
                 BrowseCursor second = repository.startBrowse(SecUserRepository.LOW_VALUES_KEY)) {
                assertThat(first.readNext().requireRecord().secUsrId()).isEqualTo("ADMIN001");
                assertThat(first.readNext().requireRecord().secUsrId()).isEqualTo("ADMIN002");
                assertThat(second.readNext().requireRecord().secUsrId()).isEqualTo("ADMIN001");
                assertThat(first.readNext().requireRecord().secUsrId()).isEqualTo("USER0001");
                assertThat(second.readNext().requireRecord().secUsrId()).isEqualTo("ADMIN002");
                assertThat(first.returned()).isEqualTo(3);
                assertThat(second.returned()).isEqualTo(2);
                first.endBrowse();
                // Ending one leaves the other readable.
                assertThat(second.readNext().requireRecord().secUsrId()).isEqualTo("USER0001");
                assertThat(second.isOpen()).isTrue();
            }
        }

        @Test
        @DisplayName("the repository declares no mutable field, so nothing can be shared by accident")
        void theRepositoryDeclaresNoMutableField() {
            for (Field field : SecUserRepository.class.getDeclaredFields()) {
                assertThat(Modifier.isFinal(field.getModifiers()))
                        .as("field %s must be final", field.getName()).isTrue();
            }
        }

        @Test
        @DisplayName("a cursor and a hold can only come from the operation that produced them")
        void aCursorAndAHoldCannotBeForged() {
            assertThat(BrowseCursor.class.getConstructors()).isEmpty();
            assertThat(HeldRecord.class.getConstructors()).isEmpty();
        }
    }

    @Nested
    @DisplayName("WRITE - COUSR01C:L240-L274, and the two duplicates the source keeps apart")
    class Add {

        @Test
        @DisplayName("WHEN DFHRESP(NORMAL) - all eighty bytes are stored, filler included")
        void aSuccessfulAddStoresAllEightyBytes() {
            JdbcTemplate template = seeded(seedRows());
            WriteResult written = repository(template)
                    .add(record("USER0009", "NEW", "USER", "PASSWORD", "U"));
            assertThat(written.isWritten()).isTrue();
            assertThat(written.isNotFound()).isFalse();
            assertThat(written.isDuplicate()).isFalse();
            assertThat(written.isOther()).isFalse();
            assertThat(written.status()).isEqualTo(FileStatus.OK);
            assertThat(written.cicsResp()).hasValue(FileStatus.NORMAL);
            assertThat(written.cicsResp2()).isEqualTo(FileStatus.NO_REASON_CODE);
            assertThat(written.statusImage()).hasSize(FileStatus.STATUS_IMAGE_LENGTH);
            assertThat(written.diagnostic()).isEmpty();
            String stored = template.queryForObject("SELECT " + RECORD_IMAGE_COLUMN + " FROM \""
                    + TEST_DSNAME + "\" WHERE " + RECORD_IMAGE_COLUMN + " LIKE 'USER0009%'",
                    String.class);
            assertThat(stored).hasSize(EIGHTY);
            assertThat(stored.substring(57)).isEqualTo(" ".repeat(23));
        }

        @Test
        @DisplayName("DUPREC and DUPKEY stay two outcomes, as two consecutive WHENs are two conditions")
        void theTwoDuplicateConditionsStayDistinguishable() {
            JdbcTemplate template = seeded(seedRows());
            SecUserRepository repository = repository(template);

            WriteResult duplicate = repository.add(record("ADMIN001", "OTHER", "NAME", "PASSWORD", "A"));
            assertThat(duplicate.isDuplicate()).isTrue();
            assertThat(duplicate.isDuplicateRecord()).isTrue();
            assertThat(duplicate.isDuplicateKey()).isFalse();
            assertThat(duplicate.status()).isEqualTo(FileStatus.DUPLICATE);
            assertThat(duplicate.cicsResp()).hasValue(FileStatus.DUPREC);
            // The rejected add stored nothing.
            assertThat(template.queryForObject("SELECT COUNT(*) FROM \"" + TEST_DSNAME + "\"",
                    Integer.class)).isEqualTo(4);

            WriteResult duplicateKey = WriteResult.duplicateKey();
            assertThat(duplicateKey.isDuplicate()).isTrue();
            assertThat(duplicateKey.isDuplicateKey()).isTrue();
            assertThat(duplicateKey.isDuplicateRecord()).isFalse();
            assertThat(duplicateKey.cicsResp()).hasValue(FileStatus.DUPKEY);
        }

        @Test
        @DisplayName("an integrity violation is the duplicate-record condition, carrying the diagnosis")
        void anIntegrityViolationIsReportedAsADuplicateRecord() {
            // A constrained relation refuses the insert itself. The probe finds no such key, so this is
            // the arm that translates the backend's own integrity violation.
            JdbcTemplate template = emptyRelation("CREATE TABLE \"" + TEST_DSNAME + "\" ("
                    + RECORD_IMAGE_COLUMN + " VARCHAR(" + EIGHTY + ") CHECK ("
                    + RECORD_IMAGE_COLUMN + " NOT LIKE 'ZZZ%'))");
            WriteResult refused = repository(template)
                    .add(record("ZZZZZZZZ", "BLOCKED", "BLOCKED", "PASSWORD", "U"));
            assertThat(refused.isDuplicate()).isTrue();
            assertThat(refused.isDuplicateRecord()).isTrue();
            assertThat(refused.cicsResp()).hasValue(FileStatus.DUPREC);
            assertThat(refused.diagnostic()).isPresent();
        }

        @Test
        @DisplayName("a backend refusal that is not an integrity violation lands on WHEN OTHER")
        void anUnreachableDatasetLandsOnWhenOther() {
            assertThat(repository(missingRelation())
                    .add(record("USER0009", "X", "Y", "PASSWORD", "U")).isOther()).isTrue();
            // A column too narrow for the record: the insert is refused with a data exception, which is
            // NOT an integrity violation and must not be reported as a duplicate. The distinction matters
            // because 'User ID already exist...' and 'Unable to Add User...' are different messages.
            JdbcTemplate narrow = emptyRelation("CREATE TABLE \"" + TEST_DSNAME + "\" ("
                    + RECORD_IMAGE_COLUMN + " VARCHAR(10))");
            WriteResult refused = repository(narrow)
                    .add(record("USER0009", "X", "Y", "PASSWORD", "U"));
            assertThat(refused.isOther()).isTrue();
            assertThat(refused.isDuplicate()).isFalse();
            assertThat(refused.diagnostic()).isPresent();
        }

        @Test
        @DisplayName("an add that reports no row written is WHEN OTHER, never a silent success")
        void anAddThatWritesNoRowIsNotASilentSuccess() {
            // The mocked template's update answers zero, and its keyed probe answers null - so the add
            // reaches the insert and then finds it changed nothing.
            WriteResult result = repository(templateAnsweringNull())
                    .add(record("USER0009", "X", "Y", "PASSWORD", "U"));
            assertThat(result.isOther()).isTrue();
            assertThat(result.cicsResp()).hasValue(FileStatus.INVREQ);
        }

        @Test
        @DisplayName("a probe the backend refuses is reported, not treated as 'no duplicate'")
        void aRefusedProbeIsReported() throws SQLException {
            // The chain describes a usable column, so the statements resolve; the keyed probe then fails.
            JdbcTemplate template = failingAfterDescribe();
            assertThat(repository(template).add(record("USER0009", "X", "Y", "PASSWORD", "U")).isOther())
                    .isTrue();
        }

        @Test
        @DisplayName("there is no such thing as adding nothing")
        void addingNothingIsRefused() {
            assertThatNullPointerException()
                    .isThrownBy(() -> repository(seeded(seedRows())).add(null));
        }
    }

    @Nested
    @DisplayName("REWRITE - COUSR02C:L360-L390, and the fan-out refused before anything is written")
    class Rewrite {

        @Test
        @DisplayName("WHEN DFHRESP(NORMAL) - the record area is written, all eighty bytes of it")
        void aSuccessfulRewriteReplacesTheRecord() {
            JdbcTemplate template = seeded(seedRows());
            SecUserRepository repository = repository(template);
            transactionOver(template).executeWithoutResult(status -> {
                HeldRecord hold = repository.readForUpdate("ADMIN001").requireHold();
                SecUserRecord updated = record("ADMIN001", "MARGARETTE", "GOLDEN", "NEWPASSW", "U");
                assertThat(hold.rewrite(updated).isWritten()).isTrue();
                ReadResult reread = repository.read("ADMIN001");
                assertThat(reread.requireRecord().secUsrFname())
                        .isEqualTo("MARGARETTE" + " ".repeat(10));
                assertThat(reread.requireRecord().secUsrType()).isEqualTo("U");
                assertThat(reread.requireRecord().secUsrPwd()).isEqualTo("NEWPASSW");
            });
        }

        @Test
        @DisplayName("WHEN DFHRESP(NOTFND) - a rewrite whose key matches nothing is not a success")
        void aRewriteOfAnAbsentKeyIsNotFound() {
            JdbcTemplate template = seeded(seedRows());
            SecUserRepository repository = repository(template);
            transactionOver(template).executeWithoutResult(status ->
                    assertThat(repository.rewrite(record("NOSUCH01", "A", "B", "PASSWORD", "U"))
                            .isNotFound()).isTrue());
        }

        @Test
        @DisplayName("a key selecting more than one row is refused before any row is replaced")
        void aFanOutIsRefusedBeforeAnythingIsWritten() {
            List<String> duplicated = new ArrayList<>(seedRows());
            duplicated.add(row("ADMIN001", "IMPOSTOR", "IMPOSTOR", "PASSWORD", "A"));
            JdbcTemplate template = seeded(duplicated);
            SecUserRepository repository = repository(template);
            transactionOver(template).executeWithoutResult(status -> {
                WriteResult refused = repository.rewrite(record("ADMIN001", "X", "Y", "PASSWORD", "A"));
                assertThat(refused.isOther()).isTrue();
                assertThat(refused.cicsResp()).hasValue(FileStatus.INVREQ);
            });
            // Neither row was touched.
            assertThat(template.queryForObject("SELECT COUNT(*) FROM \"" + TEST_DSNAME + "\" WHERE "
                    + RECORD_IMAGE_COLUMN + " LIKE 'ADMIN001%'", Integer.class)).isEqualTo(2);
        }

        @Test
        @DisplayName("a row lost between the count and the write is the invalid-key condition")
        void aRowLostBeforeTheWriteIsNotFound() throws SQLException {
            JdbcTemplate template = mockedChain(RECORD_IMAGE_COLUMN, 1, 1, 0);
            withUnitOfWork(() -> assertThat(repository(template)
                    .rewrite(record("ADMIN001", "X", "Y", "PASSWORD", "A")).isNotFound()).isTrue());
        }

        @Test
        @DisplayName("a write that affected more rows than the count found is WHEN OTHER")
        void aWriteAffectingMoreRowsThanCountedIsWhenOther() throws SQLException {
            JdbcTemplate template = mockedChain(RECORD_IMAGE_COLUMN, 1, 1, 2);
            withUnitOfWork(() -> {
                WriteResult result = repository(template)
                        .rewrite(record("ADMIN001", "X", "Y", "PASSWORD", "A"));
                assertThat(result.isOther()).isTrue();
                assertThat(result.cicsResp()).hasValue(FileStatus.INVREQ);
            });
        }

        @Test
        @DisplayName("an unreachable dataset and a refused count both land on WHEN OTHER")
        void refusalsLandOnWhenOther() throws SQLException {
            withUnitOfWork(() -> assertThat(repository(missingRelation())
                    .rewrite(record("ADMIN001", "X", "Y", "PASSWORD", "A")).isOther()).isTrue());
            JdbcTemplate failing = failingAfterDescribe();
            withUnitOfWork(() -> assertThat(repository(failing)
                    .rewrite(record("ADMIN001", "X", "Y", "PASSWORD", "A")).isOther()).isTrue());
        }

        @Test
        @DisplayName("a rewrite outside a unit of work refuses, and rewriting nothing is refused too")
        void preconditionsAreEnforced() {
            SecUserRepository repository = repository(seeded(seedRows()));
            assertThatIllegalStateException().isThrownBy(
                    () -> repository.rewrite(record("ADMIN001", "A", "B", "PASSWORD", "A")));
            assertThatNullPointerException().isThrownBy(() -> repository.rewrite(null));
        }
    }

    @Nested
    @DisplayName("DELETE - COUSR03C:L307-L336, which carries no RIDFLD at all")
    class DeleteHeld {

        @Test
        @DisplayName("WHEN DFHRESP(NORMAL) - the exact row that was read is removed")
        void theExactRowThatWasReadIsRemoved() {
            JdbcTemplate template = seeded(seedRows());
            SecUserRepository repository = repository(template);
            transactionOver(template).executeWithoutResult(status -> {
                HeldRecord hold = repository.readForUpdate("ADMIN002").requireHold();
                assertThat(hold.deleteHeld().isWritten()).isTrue();
                assertThat(repository.read("ADMIN002").isNotFound()).isTrue();
                // Every other record survives.
                assertThat(repository.read("ADMIN001").isFound()).isTrue();
                assertThat(template.queryForObject("SELECT COUNT(*) FROM \"" + TEST_DSNAME + "\"",
                        Integer.class)).isEqualTo(3);
            });
        }

        @Test
        @DisplayName("WHEN DFHRESP(NOTFND) - the held row is gone, so there is nothing to delete")
        void deletingAnAlreadyDeletedHoldIsNotFound() {
            JdbcTemplate template = seeded(seedRows());
            SecUserRepository repository = repository(template);
            transactionOver(template).executeWithoutResult(status -> {
                HeldRecord hold = repository.readForUpdate("ADMIN002").requireHold();
                assertThat(repository.deleteHeld(hold).isWritten()).isTrue();
                assertThat(repository.deleteHeld(hold).isNotFound()).isTrue();
            });
        }

        @Test
        @DisplayName("the whole image is matched, so a record that changed is reported, not removed")
        void aRecordThatChangedIsReportedRatherThanRemoved() {
            JdbcTemplate template = seeded(seedRows());
            SecUserRepository repository = repository(template);
            transactionOver(template).executeWithoutResult(status -> {
                HeldRecord hold = repository.readForUpdate("ADMIN002").requireHold();
                // The stored row is replaced by another process between the read and the delete.
                template.update("UPDATE \"" + TEST_DSNAME + "\" SET " + RECORD_IMAGE_COLUMN
                        + " = ? WHERE " + RECORD_IMAGE_COLUMN + " LIKE 'ADMIN002%'",
                        row("ADMIN002", "CHANGED", "CHANGED", "PASSWORD", "A"));
                assertThat(repository.deleteHeld(hold).isNotFound()).isTrue();
                // Nothing was removed.
                assertThat(template.queryForObject("SELECT COUNT(*) FROM \"" + TEST_DSNAME + "\"",
                        Integer.class)).isEqualTo(4);
            });
        }

        @Test
        @DisplayName("a hold selecting more than one row is refused before anything is removed")
        void aFanOutIsRefusedBeforeAnythingIsRemoved() {
            List<String> duplicated = new ArrayList<>(seedRows());
            duplicated.add(row("ADMIN001", "MARGARET", "GOLD", "PASSWORD", "A"));
            JdbcTemplate template = seeded(duplicated);
            SecUserRepository repository = repository(template);
            transactionOver(template).executeWithoutResult(status -> {
                HeldRecord hold = repository.readForUpdate("ADMIN001").requireHold();
                WriteResult refused = repository.deleteHeld(hold);
                assertThat(refused.isOther()).isTrue();
                assertThat(refused.cicsResp()).hasValue(FileStatus.INVREQ);
            });
            assertThat(template.queryForObject("SELECT COUNT(*) FROM \"" + TEST_DSNAME + "\"",
                    Integer.class)).isEqualTo(5);
        }

        @Test
        @DisplayName("a row lost before the delete is not-found, and one that fans out is WHEN OTHER")
        void theRacesTheCountCannotCloseAreReportedFaithfully() throws SQLException {
            SecUserRepository losing = repository(mockedChain(RECORD_IMAGE_COLUMN, 1, 1, 0));
            withUnitOfWork(() -> {
                HeldRecord hold = losing.readForUpdate("ADMIN001").requireHold();
                assertThat(losing.deleteHeld(hold).isNotFound()).isTrue();
                assertThat(hold.deleteHeld().isNotFound()).isTrue();
            });
            SecUserRepository fanningOut = repository(mockedChain(RECORD_IMAGE_COLUMN, 1, 1, 3));
            withUnitOfWork(() -> {
                HeldRecord hold = fanningOut.readForUpdate("ADMIN001").requireHold();
                WriteResult result = hold.deleteHeld();
                assertThat(result.isOther()).isTrue();
                assertThat(result.cicsResp()).hasValue(FileStatus.INVREQ);
            });
        }

        @Test
        @DisplayName("a refused count and an unreachable dataset both land on WHEN OTHER")
        void refusalsLandOnWhenOther() throws SQLException {
            JdbcTemplate seededTemplate = seeded(seedRows());
            SecUserRepository repository = repository(seededTemplate);
            transactionOver(seededTemplate).executeWithoutResult(status -> {
                HeldRecord hold = repository.readForUpdate("ADMIN001").requireHold();
                seededTemplate.execute("DROP TABLE \"" + TEST_DSNAME + "\"");
                assertThat(repository.deleteHeld(hold).isOther()).isTrue();
            });
            // Described, then every statement refused: the second catch arm, distinct from the first.
            SecUserRepository holding = repository(mockedChainHolding());
            HeldRecord[] captured = new HeldRecord[1];
            withUnitOfWork(() -> captured[0] = holding.readForUpdate("ADMIN001").requireHold());
            SecUserRepository refusing = repository(failingAfterDescribe());
            withUnitOfWork(() -> assertThatIllegalArgumentException()
                    .isThrownBy(() -> refusing.deleteHeld(captured[0])));
        }

        @Test
        @DisplayName("no key, no argument at all from the handle, and no delete-by-key of any name")
        void thereIsNoDeleteByKeySurface() throws NoSuchMethodException {
            assertThat(HeldRecord.class.getMethod("deleteHeld").getParameterCount()).isZero();
            assertThat(SecUserRepository.class.getMethod("deleteHeld", HeldRecord.class)
                    .getParameterTypes()).containsExactly(HeldRecord.class);
            for (Method method : SecUserRepository.class.getMethods()) {
                assertThat(method.getName()).isNotIn("deleteById", "delete", "removeById", "findAll",
                        "findById", "existsById", "save", "saveAll", "count");
            }
        }

        @Test
        @DisplayName("deleting nothing, and deleting through the wrong repository, are both refused")
        void preconditionsAreEnforced() {
            JdbcTemplate template = seeded(seedRows());
            SecUserRepository one = repository(template);
            SecUserRepository two = repository(template);
            assertThatNullPointerException().isThrownBy(() -> one.deleteHeld(null));
            transactionOver(template).executeWithoutResult(status -> {
                HeldRecord hold = one.readForUpdate("ADMIN001").requireHold();
                assertThatIllegalArgumentException().isThrownBy(() -> two.deleteHeld(hold))
                        .withMessageContaining(TEST_DSNAME);
            });
        }

        @Test
        @DisplayName("a delete outside a unit of work refuses")
        void aDeleteOutsideAUnitOfWorkRefuses() {
            JdbcTemplate template = seeded(seedRows());
            SecUserRepository repository = repository(template);
            HeldRecord[] captured = new HeldRecord[1];
            transactionOver(template).executeWithoutResult(status ->
                    captured[0] = repository.readForUpdate("ADMIN001").requireHold());
            assertThatIllegalStateException().isThrownBy(() -> repository.deleteHeld(captured[0]));
        }

        /**
         * A mocked chain whose keyed read finds one row, for a test that needs a hold and nothing else.
         *
         * @return a template that yields one row per query
         * @throws SQLException never; declared because the mocked JDBC methods declare it
         */
        private JdbcTemplate mockedChainHolding() throws SQLException {
            return mockedChain(RECORD_IMAGE_COLUMN, 1, 1, 1);
        }
    }

    @Nested
    @DisplayName("Wiring, and the geometry the constructor refuses at startup")
    class Wiring {

        @Test
        @DisplayName("the resolved dataset comes from configuration, and is published for diagnostics")
        void theDatasetComesFromConfiguration() {
            JdbcTemplate template = seeded(seedRows());
            SecUserRepository repository = repository(template);
            assertThat(repository.datasetName()).isEqualTo(TEST_DSNAME);
            assertThat(repository.recordLength()).isEqualTo(EIGHTY);
            assertThat(repository.keyLength()).isEqualTo(EIGHT);
            assertThat(repository.datasetCharset()).isEqualTo(ASCII);
            assertThat(repository.cicsFileName()).isEqualTo("USRSEC  ");
            // A different configured name is honoured, which is what proves nothing is hard-coded.
            SecUserRepository other = new SecUserRepository(template,
                    bindings("OTHER.USRSEC.KSDS", EIGHTY, EIGHT, 0), ASCII, RecordImageForm.CHARACTER);
            assertThat(other.datasetName()).isEqualTo("OTHER.USRSEC.KSDS");
        }

        @Test
        @DisplayName("a record or key width this record cannot have is refused, naming the copybook")
        void aGeometryThisRecordCannotHaveIsRefused() {
            JdbcTemplate template = new JdbcTemplate();
            assertThatIllegalStateException().isThrownBy(() -> new SecUserRepository(template,
                    bindings(TEST_DSNAME, 350, EIGHT, null), ASCII, RecordImageForm.CHARACTER))
                    .withMessageContaining("CSUSR01Y");
            assertThatIllegalStateException().isThrownBy(() -> new SecUserRepository(template,
                    bindings(TEST_DSNAME, EIGHTY, 11, null), ASCII, RecordImageForm.CHARACTER))
                    .withMessageContaining("SEC-USR-ID");
            assertThatIllegalStateException().isThrownBy(() -> new SecUserRepository(template,
                    bindings(TEST_DSNAME, EIGHTY, EIGHT, 4), ASCII, RecordImageForm.CHARACTER))
                    .withMessageContaining("leading field");
            // An omitted key length is normal, exactly as some bindings omit it.
            assertThat(new SecUserRepository(template, bindings(TEST_DSNAME, EIGHTY, null, null), ASCII,
                    RecordImageForm.CHARACTER).keyLength()).isEqualTo(EIGHT);
        }

        @Test
        @DisplayName("an unconfigured file, and an unconfigured dataset name, are configuration defects")
        void anUnconfiguredBindingIsAConfigurationDefect() {
            JdbcTemplate template = new JdbcTemplate();
            assertThatIllegalStateException().isThrownBy(() -> new SecUserRepository(template,
                    new DatasetBindings(), ASCII, RecordImageForm.CHARACTER));
            assertThatIllegalStateException().isThrownBy(() -> new SecUserRepository(template,
                    bindings(null, EIGHTY, EIGHT, null), ASCII, RecordImageForm.CHARACTER))
                    .withMessageContaining("carddemo.datasets.USRSEC.dsname");
            assertThatIllegalStateException().isThrownBy(() -> new SecUserRepository(template,
                    bindings("   ", EIGHTY, EIGHT, null), ASCII, RecordImageForm.CHARACTER))
                    .withMessageContaining("carddemo.datasets.USRSEC.dsname");
        }

        @ParameterizedTest(name = "the malformed dataset name {0} is refused by the shared grammar")
        @ValueSource(strings = {"TEST.USRSEC\u0001KSDS", "1BAD.USRSEC.KSDS", "TEST..USRSEC"})
        @DisplayName("a name that was configured but is not a dataset name is an argument defect")
        void aMalformedDatasetNameIsAnArgumentDefect(String malformed) {
            assertThatIllegalArgumentException().isThrownBy(() -> new SecUserRepository(
                    new JdbcTemplate(), bindings(malformed, EIGHTY, EIGHT, null), ASCII,
                    RecordImageForm.CHARACTER));
        }

        @Test
        @DisplayName("every collaborator is required, and none is injected into a field")
        void everyCollaboratorIsRequired() throws NoSuchMethodException {
            JdbcTemplate template = new JdbcTemplate();
            assertThatNullPointerException().isThrownBy(() -> new SecUserRepository(null,
                    validBindings(), ASCII, RecordImageForm.CHARACTER));
            assertThatNullPointerException().isThrownBy(() -> new SecUserRepository(template, null,
                    ASCII, RecordImageForm.CHARACTER));
            assertThatNullPointerException().isThrownBy(() -> new SecUserRepository(template,
                    validBindings(), null, RecordImageForm.CHARACTER));
            assertThatNullPointerException().isThrownBy(() -> new SecUserRepository(template,
                    validBindings(), ASCII, null));
            // One constructor, four parameters, and no field injection anywhere.
            assertThat(SecUserRepository.class.getDeclaredConstructors()).hasSize(1);
            assertThat(SecUserRepository.class.getConstructor(JdbcTemplate.class, DatasetBindings.class,
                    Charset.class, RecordImageForm.class)).isNotNull();
        }

        @Test
        @DisplayName("a multi-byte code page is refused, because a fixed-width record is bytes")
        void aMultiByteCodePageIsRefused() {
            assertThatIllegalArgumentException().isThrownBy(() -> new SecUserRepository(
                    new JdbcTemplate(), validBindings(), StandardCharsets.UTF_16,
                    RecordImageForm.CHARACTER));
        }

        @Test
        @DisplayName("a relation that describes no usable record-image column is a contract violation")
        void aRelationWithNoUsableColumnIsAContractViolation() throws SQLException {
            SecUserRepository noColumn = repository(mockedChain(RECORD_IMAGE_COLUMN, 0, 0, 0));
            assertThatIllegalStateException().isThrownBy(() -> noColumn.read("ADMIN001"))
                    .withMessageContaining("single-column record-image relation");
            SecUserRepository blankColumn = repository(mockedChain("   ", 1, 0, 0));
            assertThatIllegalStateException().isThrownBy(() -> blankColumn.read("ADMIN001"));
            SecUserRepository nullColumn = repository(mockedChain(null, 1, 0, 0));
            assertThatIllegalStateException().isThrownBy(() -> nullColumn.read("ADMIN001"));
        }

        @Test
        @DisplayName("a column name carrying a control character is refused, without repeating it")
        void aColumnNameWithAControlCharacterIsRefused() throws SQLException {
            SecUserRepository repository = repository(mockedChain("REC\u0001IMAGE", 1, 0, 0));
            assertThatIllegalStateException().isThrownBy(() -> repository.read("ADMIN001"))
                    .withMessageContaining("control character")
                    .withMessageNotContaining("REC\u0001IMAGE");
        }
    }

    @Nested
    @DisplayName("Outcome types - a status and its classification can never disagree")
    class Outcomes {

        @Test
        @DisplayName("every named read factory classifies itself consistently")
        void everyNamedReadFactoryIsConsistent() {
            assertThat(ReadResult.found(record("A", "B", "C", "D", "E")).isFound()).isTrue();
            assertThat(ReadResult.notFound().isNotFound()).isTrue();
            assertThat(ReadResult.endOfFile().isEndOfFile()).isTrue();
            assertThat(ReadResult.of(SecUserRepository.PERMANENT_ERROR_STATUS,
                    CicsResponse.of(FileStatus.INVREQ)).isOther()).isTrue();
            // of(...) reports the arms that carry no record, so a success status has no meaning for it:
            // a successful read carries the record it read, and the invariant refuses the alternative.
            assertThatIllegalArgumentException().isThrownBy(
                    () -> ReadResult.of(FileStatus.OK, CicsResponse.of(FileStatus.NORMAL)));
            assertThat(ReadResult.endOfFile().isFound()).isFalse();
            assertThat(ReadResult.endOfFile().isNotFound()).isFalse();
            assertThat(ReadResult.endOfFile().isOther()).isFalse();
            assertThat(ReadResult.endOfFile().cicsResp2()).isEqualTo(FileStatus.NO_REASON_CODE);
        }

        @Test
        @DisplayName("a read outcome refuses an inconsistent status, record or hold")
        void aReadOutcomeRefusesInconsistency() {
            SecUserRecord any = record("ADMIN001", "A", "B", "PASSWORD", "A");
            assertThatIllegalArgumentException().isThrownBy(() -> new ReadResult(FileStatus.OK,
                    Outcome.NOT_FOUND, Optional.empty(), Optional.empty(), CicsResponse.none(),
                    Optional.empty()));
            // A record present without success.
            assertThatIllegalArgumentException().isThrownBy(() -> new ReadResult(FileStatus.NOT_FOUND,
                    Outcome.NOT_FOUND, Optional.of(any), Optional.empty(), CicsResponse.none(),
                    Optional.empty()));
            // Success without a record.
            assertThatIllegalArgumentException().isThrownBy(() -> new ReadResult(FileStatus.OK,
                    Outcome.OK, Optional.empty(), Optional.empty(), CicsResponse.none(),
                    Optional.empty()));
            // A hold stands for a record this task holds, so it cannot travel without that record.
            assertThatIllegalArgumentException().isThrownBy(() -> new ReadResult(FileStatus.NOT_FOUND,
                    Outcome.NOT_FOUND, Optional.empty(),
                    Optional.of(forgedHold()), CicsResponse.none(), Optional.empty()));
            // A status of the wrong width is not a file status at all.
            assertThatIllegalArgumentException().isThrownBy(() -> new ReadResult("0", Outcome.OK,
                    Optional.empty(), Optional.empty(), CicsResponse.none(), Optional.empty()));
            assertThatNullPointerException().isThrownBy(() -> new ReadResult(FileStatus.NOT_FOUND,
                    Outcome.NOT_FOUND, null, Optional.empty(), CicsResponse.none(), Optional.empty()));
            assertThatNullPointerException().isThrownBy(() -> new ReadResult(FileStatus.NOT_FOUND,
                    Outcome.NOT_FOUND, Optional.empty(), null, CicsResponse.none(), Optional.empty()));
            assertThatNullPointerException().isThrownBy(() -> new ReadResult(FileStatus.NOT_FOUND,
                    Outcome.NOT_FOUND, Optional.empty(), Optional.empty(), null, Optional.empty()));
            assertThatNullPointerException().isThrownBy(() -> new ReadResult(FileStatus.NOT_FOUND,
                    Outcome.NOT_FOUND, Optional.empty(), Optional.empty(), CicsResponse.none(), null));
            assertThatNullPointerException().isThrownBy(() -> ReadResult.found(null));
            assertThatNullPointerException()
                    .isThrownBy(() -> ReadResult.held(any, null));
            assertThatNullPointerException().isThrownBy(() -> ReadResult.held(null, null));
            assertThatNullPointerException()
                    .isThrownBy(() -> ReadResult.of(FileStatus.OK, (com.vsergeychik.carddemo.common
                            .DatasetRelation.BackendDiagnostic) null));
        }

        @Test
        @DisplayName("every named write factory classifies itself consistently")
        void everyNamedWriteFactoryIsConsistent() {
            assertThat(WriteResult.written().isWritten()).isTrue();
            assertThat(WriteResult.written().isNotFound()).isFalse();
            assertThat(WriteResult.written().isDuplicate()).isFalse();
            assertThat(WriteResult.written().isDuplicateRecord()).isFalse();
            assertThat(WriteResult.written().isDuplicateKey()).isFalse();
            assertThat(WriteResult.written().isOther()).isFalse();
            assertThat(WriteResult.notFound().isNotFound()).isTrue();
            assertThat(WriteResult.notFound().isWritten()).isFalse();
            assertThat(WriteResult.duplicateRecord().isWritten()).isFalse();
            assertThat(WriteResult.duplicateRecord().isDuplicateRecord()).isTrue();
            assertThat(WriteResult.duplicateKey().isDuplicateKey()).isTrue();
            assertThat(WriteResult.of(FileStatus.OK, CicsResponse.none()).cicsResp()).isEmpty();
            assertThat(WriteResult.of(FileStatus.OK, CicsResponse.none()).statusImage())
                    .hasSize(FileStatus.STATUS_IMAGE_LENGTH);
            // A duplicate reported with no response value is still a duplicate, but neither specific one.
            WriteResult unattributed = WriteResult.of(FileStatus.DUPLICATE, CicsResponse.none());
            assertThat(unattributed.isDuplicate()).isTrue();
            assertThat(unattributed.isDuplicateRecord()).isFalse();
            assertThat(unattributed.isDuplicateKey()).isFalse();
        }

        @Test
        @DisplayName("a write outcome refuses an inconsistent status")
        void aWriteOutcomeRefusesInconsistency() {
            assertThatIllegalArgumentException().isThrownBy(() -> new WriteResult(FileStatus.OK,
                    Outcome.NOT_FOUND, CicsResponse.none(), Optional.empty()));
            assertThatIllegalArgumentException().isThrownBy(() -> new WriteResult("000",
                    Outcome.OK, CicsResponse.none(), Optional.empty()));
            assertThatNullPointerException().isThrownBy(() -> new WriteResult(FileStatus.OK,
                    Outcome.OK, null, Optional.empty()));
            assertThatNullPointerException().isThrownBy(() -> new WriteResult(FileStatus.OK,
                    Outcome.OK, CicsResponse.none(), null));
            assertThatNullPointerException().isThrownBy(() -> WriteResult.duplicateRecord(null));
            assertThatNullPointerException()
                    .isThrownBy(() -> WriteResult.of(FileStatus.OK, (com.vsergeychik.carddemo.common
                            .DatasetRelation.BackendDiagnostic) null));
            assertThatNullPointerException().isThrownBy(() -> new WriteResult(null, Outcome.OK,
                    CicsResponse.none(), Optional.empty()));
        }
    }

    /**
     * A hold produced by a throwaway repository, for the invariant that refuses a hold without a record.
     *
     * <p>Obtained the only way one can be - from a successful locking read - because the handle has no
     * accessible constructor. That is the property being relied on, so it is exercised rather than
     * circumvented.
     *
     * @return a hold over a private relation
     */
    private static HeldRecord forgedHold() {
        JdbcTemplate template = seeded(seedRows());
        SecUserRepository repository = repository(template);
        HeldRecord[] captured = new HeldRecord[1];
        transactionOver(template).executeWithoutResult(status ->
                captured[0] = repository.readForUpdate("ADMIN001").requireHold());
        return captured[0];
    }

    /**
     * A mocked chain that describes a usable column and then refuses every prepared statement.
     *
     * <p>This separates "the dataset cannot be described" from "the dataset was described and then the
     * operation was refused", which are two different catch arms reporting the same coarse status.
     *
     * @return a template whose statements all fail
     * @throws SQLException never; declared because the mocked JDBC methods declare it
     */
    private static JdbcTemplate failingAfterDescribe() throws SQLException {
        DataSource dataSource = Mockito.mock(DataSource.class);
        Connection connection = Mockito.mock(Connection.class);
        Statement statement = Mockito.mock(Statement.class);
        ResultSet probe = Mockito.mock(ResultSet.class);
        ResultSetMetaData metaData = Mockito.mock(ResultSetMetaData.class);
        Mockito.when(dataSource.getConnection()).thenReturn(connection);
        Mockito.when(connection.createStatement()).thenReturn(statement);
        Mockito.when(statement.executeQuery(Mockito.anyString())).thenReturn(probe);
        Mockito.when(probe.getMetaData()).thenReturn(metaData);
        Mockito.when(metaData.getColumnCount()).thenReturn(1);
        Mockito.when(metaData.getColumnName(1)).thenReturn(RECORD_IMAGE_COLUMN);
        Mockito.when(connection.prepareStatement(Mockito.anyString()))
                .thenThrow(new SQLException("the relation is not available", "08006"));
        return new JdbcTemplate(dataSource);
    }
}
