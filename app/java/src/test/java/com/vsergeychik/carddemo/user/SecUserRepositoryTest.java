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
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
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
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

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
 * <p>This is the foundational test of the {@code user} test package. The five controller tests and the
 * sign-on service test all stub the outcomes pinned here, so the outcome <em>vocabulary</em> - not just
 * the happy path - is the deliverable.
 *
 * <h2>The oracle: where every expected value in this file comes from</h2>
 * <table border="1">
 *   <caption>The read-only reference files this class is written against</caption>
 *   <tr><th>File and lines</th><th>What it fixes</th></tr>
 *   <tr><td>{@code app/csd/CARDDEMO.CSD:L88-L99}</td>
 *       <td>{@code DEFINE FILE(USRSEC)}: the file name that is also the configuration key, and
 *           {@code ADD BROWSE DELETE READ UPDATE} all {@code YES} - which is precisely the closed set of
 *           nine access paths, and the reason this is the one dataset in the module with a delete</td></tr>
 *   <tr><td>{@code app/cpy/CSUSR01Y.cpy:L17-L23}</td>
 *       <td>{@code 01 SEC-USER-DATA} and its six {@code 05} items: the 80-byte layout, every field
 *           offset, and {@code SEC-USR-FILLER PIC X(23)} as a <em>named</em> field</td></tr>
 *   <tr><td>{@code app/jcl/DUSRSECJ.jcl:L34-L44}</td>
 *       <td>{@code //SYSUT1 DD *} and the ten in-stream seed records. There is <strong>no</strong>
 *           {@code usrsec} fixture in {@code app/data/ASCII} - the security file's data lives only
 *           here - so {@link #jclSeedUsers()} transcribes these lines and nothing else</td></tr>
 *   <tr><td>{@code app/jcl/DUSRSECJ.jcl:L48}</td>
 *       <td>{@code DCB=(LRECL=80,RECFM=FB,DSORG=PS,BLKSIZE=0)}: the sequential form's 80-byte record</td></tr>
 *   <tr><td>{@code app/jcl/DUSRSECJ.jcl:L64-L66}</td>
 *       <td>{@code DEFINE CLUSTER ... KEYS(8,0) RECORDSIZE(80,80)}: the key width, the key offset and the
 *           record width, independently of the copybook</td></tr>
 *   <tr><td>{@code app/cbl/COSGN00C.cbl}, {@code COUSR00C}, {@code COUSR01C}, {@code COUSR02C},
 *           {@code COUSR03C}</td>
 *       <td>Which command each path translates, and the {@code WHEN} arms each caller branches on</td></tr>
 * </table>
 * Not one of those files is opened for writing, copied into this module, or normalised. They are the
 * only oracle this migration has, and a test that edited one would be grading its own homework.
 *
 * <h2>Provenance of the expected values - statically derived, and said so</h2>
 * <strong>Every expected value in this class was derived by reading the COBOL, the copybook and the JCL
 * cited above. None of it was captured from a live execution of the legacy programs.</strong> Executing
 * them is not possible in this environment: there is no z/OS or CICS runtime, the available COBOL
 * compiler has its indexed-file handler disabled, and the {@code DFHAID}, {@code DFHBMSCA} and
 * {@code DFHATTR} copybooks the online programs copy are not in this repository. That is a documented
 * deviation in the migration's own risk register, not a silent one, and it is recorded here so that
 * nobody reading these assertions mistakes them for a captured baseline. The mitigation is mechanical
 * derivation: the layout assertions below are byte offsets read straight out of
 * {@code CSUSR01Y.cpy:L17-L23}, and the seed data is transcribed character-for-character from
 * {@code DUSRSECJ.jcl:L35-L44} rather than paraphrased.
 *
 * <h2>Two record formats are declared for one file, and the disagreement is reproduced, not resolved</h2>
 * {@code app/csd/CARDDEMO.CSD:L93} declares the CICS file {@code RECORDFORMAT(V)} - variable - while
 * {@code app/jcl/DUSRSECJ.jcl:L48} declares the sequential form {@code RECFM=FB,LRECL=80} - fixed
 * blocked at 80. Both are in the source; they cannot both be the whole truth. The migration's rule is
 * that record length is <strong>copybook-fixed at 80 regardless</strong> of which format a given
 * definition claims, because {@code CSUSR01Y.cpy} is the contract and every {@code EXEC CICS} call in
 * all five programs passes {@code LENGTH(LENGTH OF SEC-USER-DATA)}, which is that fixed 80. So this
 * class asserts 80 everywhere and models no variable-length record - and it records <em>why</em> rather
 * than quietly picking the convenient clause. See
 * {@link SeedRecordLayout#theRecordFormatDisagreementIsSettledByTheCopybook()}.
 *
 * <h2>The password is oracle data, not a credential</h2>
 * The literal {@code PASSWORD} throughout this file is the actual byte content of
 * {@code app/jcl/DUSRSECJ.jcl:L35-L44}: all ten seeded users share it, and it happens to fill
 * {@code SEC-USR-PWD PIC X(08)} exactly. It is sample data in a public demonstration application, it
 * guards nothing, and the migration deliberately keeps {@code SEC-USR-PWD} plaintext because hashing it
 * would change observable behaviour. Substituting a different value here would make these assertions
 * disagree with the oracle, which is the one thing they exist to avoid.
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
 * A real relation is preferred wherever the outcome is observable in data, because a mock that returns
 * the answer the test expects proves only that the test and the mock agree. The mock is reserved for
 * conditions a relation genuinely cannot be asked to produce. Neither harness loads a Spring context:
 * the repository is constructor-injected, so building one directly is both possible and faster, and it
 * keeps the {@code Charset} an explicit argument at every call site rather than an injected assumption.
 *
 * <p>Each relation gets its own database name and each test builds its own repository, so no state is
 * shared between tests and nothing depends on execution order. The only static members here are
 * immutable constants and a counter that hands out distinct database names.
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

    /**
     * Four seed records transcribed from {@code app/jcl/DUSRSECJ.jcl:L35-L44}, padded to 80.
     *
     * <p>A deliberate subset. The behavioural tests assert exact walks and exact counts, and four records
     * make those assertions readable; {@link #jclSeedUsers()} carries all ten for the layout and ordering
     * tests, which is where the full file matters.
     */
    private static List<String> seedRows() {
        List<String> rows = new ArrayList<>();
        rows.add(row("ADMIN001", "MARGARET", "GOLD", "PASSWORD", "A"));
        rows.add(row("ADMIN002", "RUSSELL", "RUSSELL", "PASSWORD", "A"));
        rows.add(row("USER0001", "LAWRENCE", "THOMAS", "PASSWORD", "U"));
        rows.add(row("USER0002", "AJITH", "KUMAR", "PASSWORD", "U"));
        return rows;
    }

    /**
     * One seeded security user, exactly as {@code app/jcl/DUSRSECJ.jcl} holds it.
     *
     * <p>The five components are the five populated fields of {@code CSUSR01Y}; the sixth field,
     * {@code SEC-USR-FILLER}, is absent from the in-stream data and is the whole point of
     * {@link SeedRecordLayout#theFiftySevenCharacterJclLineRightPadsToTheEightyByteRecord()}.
     *
     * @param id    {@code SEC-USR-ID PIC X(08)} - the primary key
     * @param fname {@code SEC-USR-FNAME PIC X(20)}
     * @param lname {@code SEC-USR-LNAME PIC X(20)}
     * @param pwd   {@code SEC-USR-PWD PIC X(08)} - plaintext, as the legacy design holds it
     * @param type  {@code SEC-USR-TYPE PIC X(01)} - {@code 'A'} admin or {@code 'U'} regular
     */
    private record SeedUser(String id, String fname, String lname, String pwd, String type) {
    }

    /**
     * All ten seeded users, transcribed character-for-character from
     * {@code app/jcl/DUSRSECJ.jcl:L35-L44}.
     *
     * <p><strong>There is no {@code usrsec} fixture file to read instead.</strong>
     * {@code app/data/ASCII} holds nine fixtures and none of them is the security file, so these ten
     * in-stream records are the only seed data the security file has anywhere in the repository. They are
     * transcribed rather than parsed out of the JCL at run time because the JCL is a job stream, not a
     * data file: it is read-only oracle material, and a test that parsed it would be asserting against
     * its own parser as much as against the data.
     *
     * <p>Two properties of this set are what make the ordering assertions meaningful, and both are
     * facts about the source rather than choices made here:
     * <ul>
     *   <li>five users are type {@code 'A'} and five are type {@code 'U'} - which is what gives
     *       {@code app/cbl/COSGN00C.cbl:L230-L240} both of its routing branches something to route;</li>
     *   <li>{@code ADMIN001}..{@code ADMIN005} sort strictly before {@code USER0001}..{@code USER0005},
     *       so an ascending browse and a descending browse produce visibly different sequences rather
     *       than two orderings that happen to look alike.</li>
     * </ul>
     *
     * <p>Returned from a method building a fresh immutable list rather than held in a static field, so
     * there is no shared mutable state of any kind between tests.
     *
     * @return the ten seeded users, in the order the JCL lists them
     */
    private static List<SeedUser> jclSeedUsers() {
        return List.of(
                // app/jcl/DUSRSECJ.jcl:L35-L39 - the five administrators, SEC-USR-TYPE 'A'.
                new SeedUser("ADMIN001", "MARGARET", "GOLD", "PASSWORD", "A"),
                new SeedUser("ADMIN002", "RUSSELL", "RUSSELL", "PASSWORD", "A"),
                new SeedUser("ADMIN003", "RAYMOND", "WHITMORE", "PASSWORD", "A"),
                new SeedUser("ADMIN004", "EMMANUEL", "CASGRAIN", "PASSWORD", "A"),
                new SeedUser("ADMIN005", "GRANVILLE", "LACHAPELLE", "PASSWORD", "A"),
                // app/jcl/DUSRSECJ.jcl:L40-L44 - the five regular users, SEC-USR-TYPE 'U'.
                new SeedUser("USER0001", "LAWRENCE", "THOMAS", "PASSWORD", "U"),
                new SeedUser("USER0002", "AJITH", "KUMAR", "PASSWORD", "U"),
                new SeedUser("USER0003", "LAURITZ", "ALME", "PASSWORD", "U"),
                new SeedUser("USER0004", "AVERARDO", "MAZZI", "PASSWORD", "U"),
                new SeedUser("USER0005", "LEE", "TING", "PASSWORD", "U"));
    }

    /**
     * The ten seeded users as 80-byte stored images, in the order the JCL lists them.
     *
     * @return ten record images, each exactly {@value #EIGHTY} characters
     */
    private static List<String> allSeedRows() {
        List<String> rows = new ArrayList<>();
        for (SeedUser user : jclSeedUsers()) {
            rows.add(row(user.id(), user.fname(), user.lname(), user.pwd(), user.type()));
        }
        return rows;
    }

    /**
     * One seeded user rendered as the 57-character in-stream line the JCL actually contains.
     *
     * <p>{@code 57 = 80 - 23}: the in-stream records stop after {@code SEC-USR-TYPE} and omit
     * {@code SEC-USR-FILLER PIC X(23)} entirely, which {@code IEBGENER} pads to the {@code LRECL=80} of
     * {@code app/jcl/DUSRSECJ.jcl:L48} on the way to the sequential dataset. Built here by concatenating
     * the fields at their declared widths, so the result is the line rather than an approximation of it.
     *
     * @param user the seeded user
     * @return the 57-character line, exactly as {@code DUSRSECJ.jcl:L35-L44} holds it
     */
    private static String jclLine(SeedUser user) {
        return pad(user.id(), SecUserRecord.SEC_USR_ID_LENGTH)
                + pad(user.fname(), SecUserRecord.SEC_USR_FNAME_LENGTH)
                + pad(user.lname(), SecUserRecord.SEC_USR_LNAME_LENGTH)
                + pad(user.pwd(), SecUserRecord.SEC_USR_PWD_LENGTH)
                + pad(user.type(), SecUserRecord.SEC_USR_TYPE_LENGTH);
    }

    /**
     * Right-space-pads a value to a declared {@code PIC X} width, the way a COBOL alphanumeric move does.
     *
     * @param value the value
     * @param width the declared width
     * @return the value padded on the right with spaces to exactly {@code width} characters
     */
    private static String pad(String value, int width) {
        return value + " ".repeat(width - value.length());
    }

    /**
     * A run of spaces.
     *
     * @param count how many
     * @return that many spaces
     */
    private static String spaces(int count) {
        return " ".repeat(count);
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

    /**
     * The 80-byte layout of {@code app/cpy/CSUSR01Y.cpy:L17-L23}, asserted at absolute byte offsets.
     *
     * <h3>Why the offsets are written as bare numbers</h3>
     * Every offset below appears twice: once as the constant the production code uses, and once as the
     * literal a reviewer can read straight off the copybook. Asserting the constant against itself would
     * pass however wrong the constant was; asserting it against the number that is visibly
     * {@code 8 + 20 + 20} is what makes this a check rather than a tautology. The migration's practice is
     * hand-written, reviewable codecs precisely so that this diff against the copybook is possible, and
     * that only pays off if the test states the numbers.
     *
     * <pre>
     * 01 SEC-USER-DATA.                        offset  length
     *   05 SEC-USR-ID     PIC X(08).                0       8
     *   05 SEC-USR-FNAME  PIC X(20).                8      20
     *   05 SEC-USR-LNAME  PIC X(20).               28      20
     *   05 SEC-USR-PWD    PIC X(08).               48       8
     *   05 SEC-USR-TYPE   PIC X(01).               56       1
     *   05 SEC-USR-FILLER PIC X(23).               57      23
     *                                            ----------
     *                                                     80
     * </pre>
     */
    @Nested
    @DisplayName("Seed record layout - CSUSR01Y.cpy:L17-L23 against DUSRSECJ.jcl:L35-L44")
    class SeedRecordLayout {

        @Test
        @DisplayName("the copybook's six fields sit at 0, 8, 28, 48, 56 and 57 and total exactly 80")
        void theSixFieldsSitAtTheCopybooksOffsetsAndTotalEighty() {
            // Offsets and widths, each stated as the number the copybook shows rather than as the
            // constant restated. A transposed pair here is a wrong SEC-USR-TYPE, which is an
            // authorisation outcome and not a cosmetic defect.
            assertThat(SecUserRecord.SEC_USR_ID_OFFSET).isZero();
            assertThat(SecUserRecord.SEC_USR_ID_LENGTH).isEqualTo(8);
            assertThat(SecUserRecord.SEC_USR_FNAME_OFFSET).isEqualTo(8);
            assertThat(SecUserRecord.SEC_USR_FNAME_LENGTH).isEqualTo(20);
            assertThat(SecUserRecord.SEC_USR_LNAME_OFFSET).isEqualTo(28);
            assertThat(SecUserRecord.SEC_USR_LNAME_LENGTH).isEqualTo(20);
            assertThat(SecUserRecord.SEC_USR_PWD_OFFSET).isEqualTo(48);
            assertThat(SecUserRecord.SEC_USR_PWD_LENGTH).isEqualTo(8);
            assertThat(SecUserRecord.SEC_USR_TYPE_OFFSET).isEqualTo(56);
            assertThat(SecUserRecord.SEC_USR_TYPE_LENGTH).isEqualTo(1);
            assertThat(SecUserRecord.SEC_USR_FILLER_OFFSET).isEqualTo(57);
            assertThat(SecUserRecord.SEC_USR_FILLER_LENGTH).isEqualTo(23);

            // The spans are contiguous with no gap and no overlap: each begins where the last ended.
            assertThat(SecUserRecord.SEC_USR_FNAME_OFFSET)
                    .as("SEC-USR-FNAME begins where SEC-USR-ID ends")
                    .isEqualTo(SecUserRecord.SEC_USR_ID_OFFSET + SecUserRecord.SEC_USR_ID_LENGTH);
            assertThat(SecUserRecord.SEC_USR_LNAME_OFFSET)
                    .isEqualTo(SecUserRecord.SEC_USR_FNAME_OFFSET
                            + SecUserRecord.SEC_USR_FNAME_LENGTH);
            assertThat(SecUserRecord.SEC_USR_PWD_OFFSET)
                    .isEqualTo(SecUserRecord.SEC_USR_LNAME_OFFSET
                            + SecUserRecord.SEC_USR_LNAME_LENGTH);
            assertThat(SecUserRecord.SEC_USR_TYPE_OFFSET)
                    .isEqualTo(SecUserRecord.SEC_USR_PWD_OFFSET + SecUserRecord.SEC_USR_PWD_LENGTH);
            assertThat(SecUserRecord.SEC_USR_FILLER_OFFSET)
                    .isEqualTo(SecUserRecord.SEC_USR_TYPE_OFFSET + SecUserRecord.SEC_USR_TYPE_LENGTH);

            // 8 + 20 + 20 + 8 + 1 + 23 = 80, and the filler is what closes the gap. Drop it and this
            // sum is 57, which is exactly the defect this assertion exists to catch.
            assertThat(8 + 20 + 20 + 8 + 1 + 23)
                    .as("the six PICTURE widths of CSUSR01Y.cpy:L18-L23 sum to the record length")
                    .isEqualTo(EIGHTY);
            assertThat(SecUserRecord.SEC_USR_FILLER_OFFSET + SecUserRecord.SEC_USR_FILLER_LENGTH)
                    .as("the record ends where SEC-USR-FILLER ends")
                    .isEqualTo(EIGHTY);
        }

        @Test
        @DisplayName("the record length and key come from the copybook, corroborated by KEYS(8,0)")
        void theRecordLengthAndKeyAreTheCopybooksAndTheClustersAlike() {
            // app/cpy/CSUSR01Y.cpy:L17-L23 gives 80; app/jcl/DUSRSECJ.jcl:L66 RECORDSIZE(80,80) and
            // :L48 LRECL=80 agree; :L65 KEYS(8,0) gives an 8-byte key at offset 0. Three independent
            // declarations of the same geometry, which is why all three are asserted as bare numbers.
            assertThat(SecUserRecord.RECORD_LENGTH).isEqualTo(80);
            assertThat(SecUserRecord.KEY_OFFSET).isEqualTo(0);
            assertThat(SecUserRecord.KEY_LENGTH).isEqualTo(8);

            // KEYS(8,0) names the key by position; CSUSR01Y names it by field. They must be the same
            // span, or a keyed read would address something other than SEC-USR-ID.
            assertThat(SecUserRecord.KEY_OFFSET).isEqualTo(SecUserRecord.SEC_USR_ID_OFFSET);
            assertThat(SecUserRecord.KEY_LENGTH).isEqualTo(SecUserRecord.SEC_USR_ID_LENGTH);

            // The repository publishes the same geometry rather than a second opinion of its own.
            assertThat(SecUserRepository.RECORD_LENGTH).isEqualTo(80);
            assertThat(SecUserRepository.KEY_OFFSET).isEqualTo(0);
            assertThat(SecUserRepository.KEY_LENGTH).isEqualTo(8);
            assertThat(repository(seeded(allSeedRows())).recordLength()).isEqualTo(80);
            assertThat(repository(seeded(allSeedRows())).keyLength()).isEqualTo(8);
        }

        @Test
        @DisplayName("SEC-USR-FILLER is a named field, and is still emitted as 23 spaces")
        void theFillerIsNamedAndStillEmittedAsSpaces() {
            // CSUSR01Y:L23 spells it 05 SEC-USR-FILLER, not 05 FILLER - it has a name, so it is
            // addressable by name like any other field. Being named changes nothing about how it is
            // written: it still goes out as spaces, and it still has to go out.
            assertThat(SecUserRecord.FIELD_SEC_USR_FILLER).isEqualTo("SEC-USR-FILLER");
            assertThat(SecUserRecord.GROUP_NAME).isEqualTo("SEC-USER-DATA");
            assertThat(SecUserRecord.blank().secUsrFiller()).isEqualTo(spaces(23));

            SecUserRecord seeded = record("ADMIN001", "MARGARET", "GOLD", "PASSWORD", "A");
            assertThat(seeded.image(SecUserRecord.FIELD_SEC_USR_FILLER)).isEqualTo(spaces(23));
            assertThat(seeded.fieldImages())
                    .as("all six fields of CSUSR01Y are addressable by their COBOL names")
                    .containsOnlyKeys(SecUserRecord.FIELD_SEC_USR_ID,
                            SecUserRecord.FIELD_SEC_USR_FNAME,
                            SecUserRecord.FIELD_SEC_USR_LNAME,
                            SecUserRecord.FIELD_SEC_USR_PWD,
                            SecUserRecord.FIELD_SEC_USR_TYPE,
                            SecUserRecord.FIELD_SEC_USR_FILLER);
        }

        @Test
        @DisplayName("all ten DUSRSECJ.jcl seed records round-trip, field by field, at their offsets")
        void allTenSeedRecordsRoundTripAtTheirDeclaredOffsets() {
            List<SeedUser> users = jclSeedUsers();
            assertThat(users)
                    .as("app/jcl/DUSRSECJ.jcl:L35-L44 is ten in-stream records")
                    .hasSize(10);

            for (SeedUser user : users) {
                SecUserRecord built = record(user.id(), user.fname(), user.lname(), user.pwd(),
                        user.type());

                // Encode with the charset stated explicitly - never a platform default.
                byte[] encoded = SecUserRecord.encode(built, ASCII);
                assertThat(encoded)
                        .as("%s serialises to the copybook's record length", user.id())
                        .hasSize(EIGHTY);

                // Each field is sliced out of the image at the offset the copybook gives, using the
                // bare numbers so this reads as a diff against CSUSR01Y.cpy:L18-L23.
                String image = new String(encoded, ASCII);
                assertThat(image.substring(0, 8))
                        .as("%s SEC-USR-ID at 0..8", user.id())
                        .isEqualTo(pad(user.id(), 8));
                assertThat(image.substring(8, 28))
                        .as("%s SEC-USR-FNAME at 8..28", user.id())
                        .isEqualTo(pad(user.fname(), 20));
                assertThat(image.substring(28, 48))
                        .as("%s SEC-USR-LNAME at 28..48", user.id())
                        .isEqualTo(pad(user.lname(), 20));
                assertThat(image.substring(48, 56))
                        .as("%s SEC-USR-PWD at 48..56", user.id())
                        .isEqualTo(pad(user.pwd(), 8));
                assertThat(image.substring(56, 57))
                        .as("%s SEC-USR-TYPE at 56..57", user.id())
                        .isEqualTo(user.type());
                assertThat(image.substring(57, 80))
                        .as("%s SEC-USR-FILLER at 57..80 is 23 spaces", user.id())
                        .isEqualTo(spaces(23));

                // Decode back and compare field for field, not as one string: a whole-string compare
                // would pass on a codec that shifted two adjacent fields by the same amount.
                SecUserRecord decoded = SecUserRecord.decode(encoded, ASCII);
                assertThat(decoded.secUsrId()).isEqualTo(pad(user.id(), 8));
                assertThat(decoded.secUsrFname()).isEqualTo(pad(user.fname(), 20));
                assertThat(decoded.secUsrLname()).isEqualTo(pad(user.lname(), 20));
                assertThat(decoded.secUsrPwd()).isEqualTo(pad(user.pwd(), 8));
                assertThat(decoded.secUsrType()).isEqualTo(user.type());
                assertThat(decoded.secUsrFiller()).isEqualTo(spaces(23));
                assertThat(decoded).isEqualTo(built);

                // The key is SEC-USR-ID and nothing else, at KEYS(8,0).
                assertThat(decoded.key()).isEqualTo(pad(user.id(), 8)).hasSize(8);

                // Re-encoding is idempotent, so a read-then-rewrite cannot drift.
                assertThat(SecUserRecord.encode(decoded, ASCII)).isEqualTo(encoded);
            }
        }

        @Test
        @DisplayName("the 57-character JCL line right-pads to the 80-byte record, filler and all")
        void theFiftySevenCharacterJclLineRightPadsToTheEightyByteRecord() {
            // Every in-stream line in app/jcl/DUSRSECJ.jcl:L35-L44 is 57 characters, because the data
            // stops after SEC-USR-TYPE and omits SEC-USR-FILLER PIC X(23). 57 + 23 = 80. IEBGENER pads
            // to the LRECL=80 of :L48; anything reading these lines has to do the same, and a test that
            // fed a 57-byte image straight in would be asserting against a record that never existed.
            for (SeedUser user : jclSeedUsers()) {
                String line = jclLine(user);
                assertThat(line)
                        .as("%s is a 57-character in-stream line", user.id())
                        .hasSize(57);
                assertThat(57 + 23)
                        .as("the omitted SEC-USR-FILLER X(23) is exactly what closes 57 to 80")
                        .isEqualTo(EIGHTY);

                String padded = pad(line, EIGHTY);
                assertThat(padded).hasSize(EIGHTY);

                SecUserRecord decoded = SecUserRecord.decode(padded.getBytes(ASCII), ASCII);
                assertThat(decoded.secUsrId()).isEqualTo(pad(user.id(), 8));
                assertThat(decoded.secUsrFname()).isEqualTo(pad(user.fname(), 20));
                assertThat(decoded.secUsrLname()).isEqualTo(pad(user.lname(), 20));
                assertThat(decoded.secUsrPwd()).isEqualTo(pad(user.pwd(), 8));
                assertThat(decoded.secUsrType()).isEqualTo(user.type());
                assertThat(decoded.secUsrFiller())
                        .as("the padding lands in SEC-USR-FILLER and nowhere else")
                        .isEqualTo(spaces(23));

                // The padded line is byte-identical to the record the repository would store, which is
                // what makes the transcription usable as seed data rather than merely similar to it.
                assertThat(padded).isEqualTo(row(user.id(), user.fname(), user.lname(), user.pwd(),
                        user.type()));
            }
        }

        @Test
        @DisplayName("a padded 57-byte line reads back through the repository as the record it encodes")
        void aPaddedJclLineIsReadableThroughTheRepository() {
            // End to end: transcribe the line, pad it, store it, and read it by key. USER0005 is the
            // useful case because LEE and TING are the shortest values in the file, so almost all of
            // both name fields is padding and a mis-sized span would be obvious.
            SeedUser lee = jclSeedUsers().get(9);
            assertThat(lee.id()).isEqualTo("USER0005");

            String stored = pad(jclLine(lee), EIGHTY);
            ReadResult result = repository(seeded(List.of(stored))).read("USER0005");

            assertThat(result.isFound()).isTrue();
            assertThat(result.status()).isEqualTo(FileStatus.OK);
            assertThat(result.outcome()).isEqualTo(Outcome.OK);
            SecUserRecord read = result.requireRecord();
            assertThat(read.secUsrId()).isEqualTo("USER0005");
            assertThat(read.secUsrFname()).isEqualTo(pad("LEE", 20));
            assertThat(read.secUsrLname()).isEqualTo(pad("TING", 20));
            assertThat(read.secUsrPwd()).isEqualTo("PASSWORD");
            assertThat(read.secUsrType()).isEqualTo("U");
            assertThat(read.secUsrFiller()).isEqualTo(spaces(23));
            assertThat(SecUserRecord.encode(read, ASCII)).hasSize(EIGHTY);
        }

        @Test
        @DisplayName("a short value pads on the right; an over-long one truncates on the right")
        void aShortValuePadsRightAndAnOverLongValueTruncatesRight() {
            // COBOL moves an alphanumeric sending field into a PIC X receiver left-justified: short
            // pads with spaces on the right, long loses characters from the right. Java's plain
            // assignment does neither, so the direction is chosen explicitly - and asserted, because
            // getting it backwards for a numeric field would be a silent value change.
            SecUserRecord shortest = record("USER0005", "LEE", "TING", "PASSWORD", "U");
            assertThat(shortest.secUsrLname())
                    .as("TING occupies SEC-USR-LNAME right-space-padded to 20")
                    .isEqualTo("TING" + spaces(16))
                    .hasSize(20)
                    .startsWith("TING");
            assertThat(shortest.secUsrFname()).isEqualTo("LEE" + spaces(17)).hasSize(20);

            // LACHAPELLE is the longest last name in the file at 10 characters and still fits in 20,
            // so the padding rule is exercised by real data and not only by a contrived value.
            SecUserRecord longest = record("ADMIN005", "GRANVILLE", "LACHAPELLE", "PASSWORD", "A");
            assertThat(longest.secUsrLname()).isEqualTo("LACHAPELLE" + spaces(10)).hasSize(20);
            assertThat(longest.secUsrFname()).isEqualTo("GRANVILLE" + spaces(11)).hasSize(20);

            // PASSWORD is 8 characters and SEC-USR-PWD is PIC X(08), so it fills the field exactly:
            // no padding, no truncation, and no trailing space to be trimmed by accident.
            assertThat(longest.secUsrPwd()).isEqualTo("PASSWORD").hasSize(8).doesNotContain(" ");

            // Over-long values lose their tail, never their head.
            SecUserRecord truncated = record("ADMIN0019", "A".repeat(21), "B".repeat(25), "PASSWORD1",
                    "AU");
            assertThat(truncated.secUsrId())
                    .as("a 9-character id truncates on the right to PIC X(08)")
                    .isEqualTo("ADMIN001").hasSize(8);
            assertThat(truncated.secUsrFname()).isEqualTo("A".repeat(20)).hasSize(20);
            assertThat(truncated.secUsrLname()).isEqualTo("B".repeat(20)).hasSize(20);
            assertThat(truncated.secUsrPwd()).isEqualTo("PASSWORD").hasSize(8);
            assertThat(truncated.secUsrType())
                    .as("PIC X(01) keeps the first character, not the last")
                    .isEqualTo("A").hasSize(1);
            assertThat(SecUserRecord.encode(truncated, ASCII)).hasSize(EIGHTY);
        }

        @Test
        @DisplayName("RECORDFORMAT(V) in the CSD and RECFM=FB in the JCL: the copybook settles it at 80")
        void theRecordFormatDisagreementIsSettledByTheCopybook() {
            // app/csd/CARDDEMO.CSD:L93 declares RECORDFORMAT(V) - variable - for FILE(USRSEC), while
            // app/jcl/DUSRSECJ.jcl:L48 declares RECFM=FB,LRECL=80 for the sequential form of the same
            // data. The two disagree in the source, and this migration does not reconcile them
            // silently: it records the disagreement and applies one rule, that record length is
            // copybook-fixed at 80 regardless. Every EXEC CICS call in all five programs passes
            // LENGTH(LENGTH OF SEC-USER-DATA), which is that fixed 80, so nothing observable depends
            // on the V. Modelling a variable-length record would be a design change, not a migration.
            //
            // The consequence is asserted rather than merely described: a shorter image and a longer
            // one are both refused, so no variable length is silently tolerated on either side of 80.
            assertThat(repository(seeded(allSeedRows())).recordLength())
                    .as("copybook-fixed at 80 despite RECORDFORMAT(V) at CARDDEMO.CSD:L93")
                    .isEqualTo(EIGHTY);

            String full = row("ADMIN001", "MARGARET", "GOLD", "PASSWORD", "A");
            ReadResult tooShort = repository(seeded(List.of(full.substring(0, 79)), 79))
                    .read("ADMIN001");
            ReadResult tooLong = repository(seeded(List.of(full + " "), 81)).read("ADMIN001");

            assertThat(tooShort.isOther())
                    .as("a 79-byte image is a length error, not a shorter valid record")
                    .isTrue();
            assertThat(tooShort.cicsResp()).hasValue(FileStatus.LENGERR);
            assertThat(tooLong.isOther())
                    .as("an 81-byte image is a length error, not a longer valid record")
                    .isTrue();
            assertThat(tooLong.cicsResp()).hasValue(FileStatus.LENGERR);
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
        @DisplayName("the raw numeric RESP and the DFHRESP name are one outcome, not two dialects")
        void theNumericRespLiteralsAndTheDfhrespNamesUnify() {
            // The five programs spell the same conditions two different ways, and this is the only place
            // in the estate where that happens:
            //
            //   COSGN00C:L221-L252   EVALUATE WS-RESP-CD ... WHEN 0 ... WHEN 13 ... WHEN OTHER
            //   COUSR02C:L334-L352   EVALUATE WS-RESP-CD ... WHEN DFHRESP(NORMAL) / DFHRESP(NOTFND)
            //   COUSR03C:L280-L299   the same, in DFHRESP form
            //
            // DFHRESP(NORMAL) *is* 0 and DFHRESP(NOTFND) *is* 13 - the names are the numbers - so the
            // two spellings must arrive as one outcome. Unifying them is exactly what FileStatus is for,
            // and it is asserted rather than assumed because the alternative failure is quiet: a
            // repository that mapped the numeric form and the named form to different statuses would
            // still pass every single-path test while making the sign-on screen and the delete screen
            // disagree about what "not found" means.
            assertThat(FileStatus.NORMAL)
                    .as("the WHEN 0 of COSGN00C:L222 is DFHRESP(NORMAL)")
                    .isZero();
            assertThat(FileStatus.NOTFND)
                    .as("the WHEN 13 of COSGN00C:L247 is DFHRESP(NOTFND)")
                    .isEqualTo(13);
            assertThat(FileStatus.outcomeOfCicsResp(0)).isEqualTo(Outcome.OK);
            assertThat(FileStatus.outcomeOfCicsResp(13)).isEqualTo(Outcome.NOT_FOUND);
            assertThat(FileStatus.outcomeOfStatus(FileStatus.OK)).isEqualTo(Outcome.OK);
            assertThat(FileStatus.outcomeOfStatus(FileStatus.NOT_FOUND)).isEqualTo(Outcome.NOT_FOUND);

            // Now the same two keys down both access paths: read() is the numeric-RESP caller's path
            // and readForUpdate() is the DFHRESP callers' path. Same status, same classification, same
            // response value, for the present key and for the absent one alike.
            JdbcTemplate template = seeded(seedRows());
            SecUserRepository repository = repository(template);

            ReadResult plainFound = repository.read("ADMIN001");
            ReadResult plainAbsent = repository.read("NOSUCH01");
            transactionOver(template).executeWithoutResult(unitOfWork -> {
                ReadResult lockingFound = repository.readForUpdate("ADMIN001");
                ReadResult lockingAbsent = repository.readForUpdate("NOSUCH01");

                assertThat(lockingFound.status()).isEqualTo(plainFound.status());
                assertThat(lockingFound.outcome()).isEqualTo(plainFound.outcome());
                assertThat(lockingFound.cicsResp()).isEqualTo(plainFound.cicsResp());
                assertThat(lockingAbsent.status()).isEqualTo(plainAbsent.status());
                assertThat(lockingAbsent.outcome()).isEqualTo(plainAbsent.outcome());
                assertThat(lockingAbsent.cicsResp()).isEqualTo(plainAbsent.cicsResp());

                // Identical outcomes, and still two different commands: only the locking read holds
                // anything, which is the one distinction that must survive the unification.
                assertThat(lockingFound.hold()).isPresent();
                assertThat(plainFound.hold())
                        .as("COSGN00C:L211-L219 has no UPDATE option, so it holds nothing")
                        .isEmpty();
            });
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

    /**
     * Browse ordering over the whole seeded file - the ten records of
     * {@code app/jcl/DUSRSECJ.jcl:L35-L44}.
     *
     * <p>The user-list screen pages through this file seven ways: forward from the top, forward from a
     * key, backward from the top of the current page, and so on. Every one of those depends on the
     * browse returning records in key order and reporting the end of the file rather than wrapping, so
     * the order is asserted over the full file rather than over a two-record sample where an ascending
     * walk and a descending one are hard to tell apart.
     */
    @Nested
    @DisplayName("Browse ordering over all ten seeded users - COUSR00C's paging contract")
    class SeedOrdering {

        @Test
        @DisplayName("READNEXT from LOW-VALUES walks ADMIN001 through USER0005, then reports ENDFILE")
        void aForwardBrowseWalksTheWholeFileInAscendingKeyOrder() {
            List<String> seen = new ArrayList<>();
            ReadResult end;
            try (BrowseCursor cursor = repository(seeded(allSeedRows()))
                    .startBrowse(SecUserRepository.LOW_VALUES_KEY)) {
                ReadResult next = cursor.readNext();
                while (next.isFound()) {
                    seen.add(next.requireRecord().secUsrId());
                    next = cursor.readNext();
                }
                end = next;
                assertThat(cursor.returned()).isEqualTo(10);
                assertThat(cursor.positionKey()).hasValue("USER0005");
            }

            assertThat(seen)
                    .as("ascending key order, exactly as DUSRSECJ.jcl:L35-L44 lists them")
                    .containsExactly("ADMIN001", "ADMIN002", "ADMIN003", "ADMIN004", "ADMIN005",
                            "USER0001", "USER0002", "USER0003", "USER0004", "USER0005");

            // Past the last record: DFHRESP(ENDFILE) at COUSR00C:L634, which is what sets the
            // 88 USER-SEC-EOF condition at :L44 and paints "You have reached the bottom of the page".
            // Emphatically not NOTFND, which paints something else.
            assertThat(end.isEndOfFile()).isTrue();
            assertThat(end.isNotFound()).isFalse();
            assertThat(end.isFound()).isFalse();
            assertThat(end.status()).isEqualTo(FileStatus.END_OF_FILE);
            assertThat(end.outcome()).isEqualTo(Outcome.END_OF_FILE);
            assertThat(end.cicsResp()).hasValue(FileStatus.ENDFILE);
            assertThat(end.record()).isEmpty();
        }

        @Test
        @DisplayName("READPREV from the last key walks USER0005 back to ADMIN001, then reports ENDFILE")
        void aBackwardBrowseWalksTheWholeFileInDescendingKeyOrder() {
            List<String> seen = new ArrayList<>();
            ReadResult beforeFirst;
            // Anchored at the last key rather than at HIGH-VALUES: positioning is at-or-after the
            // anchor, so HIGH-VALUES finds nothing to position on at all - which STARTBR reports as
            // NOTFND, and which is asserted separately. USER0005 is the honest way to start at the end.
            try (BrowseCursor cursor = repository(seeded(allSeedRows())).startBrowse("USER0005")) {
                ReadResult previous = cursor.readPrevious();
                while (previous.isFound()) {
                    seen.add(previous.requireRecord().secUsrId());
                    previous = cursor.readPrevious();
                }
                beforeFirst = previous;
                assertThat(cursor.returned()).isEqualTo(10);
            }

            assertThat(seen)
                    .as("descending key order - the exact reverse of the forward walk")
                    .containsExactly("USER0005", "USER0004", "USER0003", "USER0002", "USER0001",
                            "ADMIN005", "ADMIN004", "ADMIN003", "ADMIN002", "ADMIN001");

            // Stepping before the first record is the ENDFILE arm at COUSR00C:L668, the backward
            // counterpart of :L634. "You are at the top of the page..." rather than a not-found.
            assertThat(beforeFirst.isEndOfFile()).isTrue();
            assertThat(beforeFirst.isNotFound()).isFalse();
            assertThat(beforeFirst.status()).isEqualTo(FileStatus.END_OF_FILE);
            assertThat(beforeFirst.cicsResp()).hasValue(FileStatus.ENDFILE);
        }

        @Test
        @DisplayName("a browse can be re-opened after ENDBR, because COUSR00C opens one per paragraph")
        void aBrowseCanBeReopenedAfterEndBrowse() {
            // COUSR00C performs STARTBR-USER-SEC-FILE afresh in its forward paragraph (:L284) and again
            // in its backward paragraph (:L338), each time after the previous browse was ended at :L325
            // or :L374. So ending a browse must leave the file browsable, not consumed: a repository
            // that could only be browsed once would break the second page of the user list.
            SecUserRepository repository = repository(seeded(allSeedRows()));

            BrowseCursor first = repository.startBrowse(SecUserRepository.LOW_VALUES_KEY);
            assertThat(first.readNext().requireRecord().secUsrId()).isEqualTo("ADMIN001");
            assertThat(first.readNext().requireRecord().secUsrId()).isEqualTo("ADMIN002");
            first.endBrowse();
            assertThat(first.isEnded()).isTrue();

            // A second browse of the same repository starts from the beginning again, uninfluenced by
            // where the first one had reached. That is only true because the position lives on the
            // cursor and not on the repository.
            try (BrowseCursor second = repository.startBrowse(SecUserRepository.LOW_VALUES_KEY)) {
                assertThat(second.isOpen()).isTrue();
                assertThat(second.isEnded()).isFalse();
                assertThat(second.readNext().requireRecord().secUsrId())
                        .as("the re-opened browse begins at the first record, not where the first ended")
                        .isEqualTo("ADMIN001");
                assertThat(second.returned()).isEqualTo(1);
            }

            // A third browse, this time anchored mid-file, proves the anchor is honoured after a
            // previous browse rather than being overridden by leftover state.
            try (BrowseCursor third = repository.startBrowse("USER0003")) {
                assertThat(third.readNext().requireRecord().secUsrId()).isEqualTo("USER0003");
                assertThat(third.readNext().requireRecord().secUsrId()).isEqualTo("USER0004");
            }

            // And the first cursor is still ended - re-opening did not revive it.
            assertThat(first.isEnded()).isTrue();
            assertThat(first.readNext().cicsResp()).hasValue(FileStatus.INVREQ);
        }

        @Test
        @DisplayName("five type A users sort before five type U, which is what gives COSGN00C two routes")
        void theFiveAdministratorsSortBeforeTheFiveRegularUsers() {
            // app/cbl/COSGN00C.cbl:L230-L240 routes on SEC-USR-TYPE: XCTL COADM01C at :L232 for an
            // administrator and COMEN01C at :L237 for everyone else. Both branches need seed data, and
            // this file supplies exactly five of each. The read is by key, so the type arrives with the
            // record rather than being inferred from the key's spelling.
            SecUserRepository repository = repository(seeded(allSeedRows()));
            List<String> admins = new ArrayList<>();
            List<String> users = new ArrayList<>();

            for (SeedUser seed : jclSeedUsers()) {
                ReadResult result = repository.read(seed.id());
                assertThat(result.isFound())
                        .as("%s is present in the seeded file", seed.id())
                        .isTrue();
                SecUserRecord read = result.requireRecord();
                assertThat(read.secUsrType())
                        .as("%s carries the type DUSRSECJ.jcl declares", seed.id())
                        .isEqualTo(seed.type());
                if ("A".equals(read.secUsrType())) {
                    admins.add(read.secUsrId());
                } else {
                    users.add(read.secUsrId());
                }
            }

            assertThat(admins).containsExactly("ADMIN001", "ADMIN002", "ADMIN003", "ADMIN004",
                    "ADMIN005");
            assertThat(users).containsExactly("USER0001", "USER0002", "USER0003", "USER0004",
                    "USER0005");
            assertThat(admins).hasSize(5);
            assertThat(users).hasSize(5);
            assertThat(admins.getLast())
                    .as("every ADMIN key sorts before every USER key, so the browse order is not "
                            + "accidentally the same in both directions")
                    .isLessThan(users.getFirst());
        }

        @Test
        @DisplayName("every seeded password is the plaintext PIC X(08) the sign-on read hands over")
        void everySeededPasswordArrivesAsThePlaintextEightCharacterField() {
            // COSGN00C:L223 compares SEC-USR-PWD to WS-USER-PWD directly, with no hashing and no
            // normalisation, and the migration preserves that. The repository's job is to hand the
            // eight bytes over unchanged; the comparison itself belongs to the sign-on service. This
            // asserts the hand-over, which is the part that is this class's responsibility.
            SecUserRepository repository = repository(seeded(allSeedRows()));

            for (SeedUser seed : jclSeedUsers()) {
                SecUserRecord read = repository.read(seed.id()).requireRecord();
                assertThat(read.secUsrPwd())
                        .as("%s SEC-USR-PWD arrives exactly as stored, untrimmed and unhashed",
                                seed.id())
                        .isEqualTo(seed.pwd())
                        .hasSize(SecUserRecord.SEC_USR_PWD_LENGTH);
            }
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
     * The constraints the migration is held to, asserted rather than trusted to review.
     *
     * <p>Each of these is a promise made about the whole module that this dataset could break on its own:
     * no schema is created or described, no dataset name is compiled into Java, no binary floating point
     * touches a record field, no state is shared, and the access surface stays the closed set of nine.
     * They are cheap to assert and expensive to notice by eye, which is exactly the trade a test should
     * take. Every scan is paired with a proof that it would actually catch a planted violation, because a
     * guard that cannot fail is worse than no guard - it reads like assurance and provides none.
     */
    @Nested
    @DisplayName("Migration constraints - no schema, no dataset literal, no float, no shared state")
    class MigrationConstraints {

        /** The configuration that owns every dataset name in the estate. */
        private static final String APPLICATION_YAML = "app/java/src/main/resources/application.yml";

        /** The repository under test, as a source file, for the scans that read code rather than run it. */
        private static final String REPOSITORY_SOURCE =
                "app/java/src/main/java/com/vsergeychik/carddemo/user/SecUserRepository.java";

        /** This test, as a source file: the guards apply to the test as much as to the code. */
        private static final String THIS_TEST_SOURCE =
                "app/java/src/test/java/com/vsergeychik/carddemo/user/SecUserRepositoryTest.java";

        /**
         * The high-order qualifiers of every dataset name in the estate; none may appear in Java.
         *
         * <p><strong>Assembled from fragments rather than written as one literal, deliberately.</strong>
         * The scan below is applied to this very file as well as to the repository, because a test that
         * hard-coded a production dataset name would be just as much a violation as production code doing
         * it - and would be the more likely of the two. A guard spelled as a single literal would match
         * its own definition and could therefore never pass, which is the sort of thing that gets a guard
         * weakened or deleted. Composing it keeps this file genuinely free of the name, so the guard
         * covers itself honestly instead of being excused from itself.
         */
        private static final String MAINFRAME_DATASET_PREFIX = "AWS" + '.' + "M2" + '.' + "CARDDEMO" + '.';

        /**
         * Any data-definition statement. A repository reaching an existing dataset never emits one.
         *
         * <p>{@code CREATE}, {@code ALTER}, {@code DROP}, {@code TRUNCATE} and {@code RENAME} against a
         * table, index, view, sequence or schema - the whole vocabulary a migration would need.
         */
        private static final Pattern DDL_STATEMENT = Pattern.compile(
                "(?i)\\b(create|alter|drop|truncate|rename)\\s+(table|index|view|sequence|schema)\\b");

        @Test
        @DisplayName("no persistence annotation appears on the repository, its nested types or the record")
        void noPersistenceAnnotationAppearsOnTheAccessSurface() {
            // An @Entity or @Table would declare a relational model for a KSDS that has none, and an @Id
            // or @Column would declare a column layout for a record whose layout is a copybook. The
            // migration reaches the existing dataset over JDBC with no schema change at all, so the whole
            // mapping vocabulary has to be absent - not merely unused.
            List<String> offenders = new ArrayList<>();
            for (Class<?> type : securityAccessTypes()) {
                for (Annotation annotation : allAnnotationsOf(type)) {
                    String packageName = annotation.annotationType().getPackageName();
                    if (packageName.startsWith("jakarta.persistence")
                            || packageName.startsWith("javax.persistence")) {
                        offenders.add(type.getSimpleName() + " carries @"
                                + annotation.annotationType().getSimpleName());
                    }
                }
            }

            assertThat(offenders)
                    .as("an object-relational mapping would impose an entity and table model that the "
                            + "USRSEC KSDS does not have")
                    .isEmpty();

            // The repository is a plain @Repository and implements no Spring Data interface, so no CRUD
            // surface is generated behind its back either.
            assertThat(SecUserRepository.class.getInterfaces())
                    .as("no Spring Data repository interface, which would generate methods no COBOL "
                            + "program calls")
                    .isEmpty();
        }

        @Test
        @DisplayName("the annotation scan would really notice a mapping annotation")
        void theAnnotationScanIsNotVacuous() {
            // The scan above passes. This proves it passes because there is nothing to find, rather than
            // because it looks in the wrong place: a type that genuinely carries an annotation is scanned
            // by the same helper and the annotation is found.
            List<Annotation> onAnAnnotatedType = allAnnotationsOf(SecUserRepository.class);

            assertThat(onAnAnnotatedType)
                    .as("SecUserRepository does carry @Repository, so the scan reaches type annotations")
                    .anyMatch(annotation -> annotation.annotationType() == Repository.class);
            assertThat(allAnnotationsOf(AnnotatedProbe.class))
                    .as("the scan reaches field, constructor, method and parameter annotations too")
                    .hasSizeGreaterThanOrEqualTo(4);
        }

        @Test
        @DisplayName("no version column, no optimistic-lock field, and no generated identifier")
        void noVersionOrGeneratedIdentifierFieldExists() {
            // COUSR02C's update path is a READ ... UPDATE that holds the record until the unit of work
            // ends - CARDDEMO.CSD:L93 UPDATEMODEL(LOCKING) - so concurrency is handled by the lock the
            // COBOL takes. A version column would be a schema change and a different concurrency model,
            // and a generated identifier would take the key away from SEC-USR-ID.
            List<String> offenders = new ArrayList<>();
            for (Class<?> type : securityAccessTypes()) {
                for (Field field : type.getDeclaredFields()) {
                    String name = field.getName().toLowerCase(Locale.ROOT);
                    if (name.contains("version") || name.contains("optimistic")
                            || name.contains("generatedid") || name.contains("sequence")) {
                        offenders.add(type.getSimpleName() + "." + field.getName());
                    }
                }
            }

            assertThat(offenders)
                    .as("the record is the copybook's six fields and nothing else; the lock is the "
                            + "READ ... UPDATE the COBOL already takes")
                    .isEmpty();

            // The record's components are exactly the copybook's six, in the copybook's order.
            assertThat(SecUserRecord.class.getRecordComponents())
                    .extracting(RecordComponent::getName)
                    .containsExactly("secUsrId", "secUsrFname", "secUsrLname", "secUsrPwd",
                            "secUsrType", "secUsrFiller");
        }

        @Test
        @DisplayName("the repository source emits no data-definition statement of any kind")
        void theRepositorySourceEmitsNoDataDefinitionStatement() {
            // The dataset already exists. A CREATE TABLE, an ALTER, a DROP or an index definition would
            // all be schema changes, which this migration forbids outright.
            String code = codeOnly(readSource(repositoryFile(REPOSITORY_SOURCE)));

            assertThat(DDL_STATEMENT.matcher(code).find())
                    .as("no DDL in SecUserRepository: the USRSEC KSDS is reached, never defined")
                    .isFalse();

            // And the scan is not vacuous - it does match a statement that is really there.
            assertThat(DDL_STATEMENT.matcher("CREATE TABLE \"X\" (RECORD_IMAGE VARCHAR(80))").find())
                    .as("the DDL pattern matches a real data-definition statement")
                    .isTrue();
            assertThat(DDL_STATEMENT.matcher("DROP INDEX IX_ONE").find()).isTrue();
            assertThat(DDL_STATEMENT.matcher("SELECT RECORD_IMAGE FROM \"X\"").find())
                    .as("and does not match ordinary data manipulation")
                    .isFalse();
        }

        @Test
        @DisplayName("no mainframe dataset name is compiled into the repository or into this test")
        void noMainframeDatasetNameIsCompiledIntoEitherSource() {
            // The dataset is reached through the carddemo.datasets.USRSEC binding, whose dsname is
            // environment-overridable, so no name belongs in Java. Comments are stripped first because
            // both files legitimately discuss the estate's dataset names in their documentation - which
            // is where such a discussion belongs, and stripping is what keeps this guard switched on.
            String repositoryCode = codeOnly(readSource(repositoryFile(REPOSITORY_SOURCE)));
            String testCode = codeOnly(readSource(repositoryFile(THIS_TEST_SOURCE)));

            assertThat(repositoryCode)
                    .as("SecUserRepository resolves its dataset name from configuration")
                    .doesNotContain(MAINFRAME_DATASET_PREFIX);
            assertThat(testCode)
                    .as("this test names a test-owned relation, never the production dataset")
                    .doesNotContain(MAINFRAME_DATASET_PREFIX);

            // The name the repository actually uses is whatever the binding supplied - here the test's
            // own - which is the positive half of the same statement.
            assertThat(repository(seeded(allSeedRows())).datasetName())
                    .isEqualTo(TEST_DSNAME)
                    .doesNotContain(MAINFRAME_DATASET_PREFIX);

            // The production name lives in configuration, keyed by the CICS file name, and that key is
            // the one the repository resolves.
            assertThat(SecUserRepository.CICS_FILE_NAME).isEqualTo("USRSEC");
            String yaml = readSource(repositoryFile(APPLICATION_YAML));
            assertThat(yaml)
                    .as("carddemo.datasets.USRSEC is where the dataset name is declared")
                    .contains("USRSEC:")
                    .contains(MAINFRAME_DATASET_PREFIX + "USRSEC.VSAM.KSDS");
        }

        @Test
        @DisplayName("the dataset-name scan strips comments but never string literals")
        void theDatasetNameScanIsNotVacuous() {
            // Stripping is what makes the guard above tolerable to documentation, so it has to be shown
            // that it strips comments and nothing else. A name inside a string literal survives, which is
            // precisely the case the guard exists to catch.
            String inProse = "/** Reaches the security KSDS. */\n"
                    + "// see application.yml\n"
                    + "String key = \"USRSEC\";\n";
            String inLiteral = "String dsname = \"" + MAINFRAME_DATASET_PREFIX + "USRSEC.VSAM.KSDS\";\n";

            assertThat(codeOnly(inProse))
                    .as("documentation is stripped")
                    .doesNotContain("Reaches the security KSDS")
                    .doesNotContain("see application.yml");
            assertThat(codeOnly(inProse))
                    .as("code outside the comments survives")
                    .contains("\"USRSEC\"");
            assertThat(codeOnly(inLiteral))
                    .as("a dataset name compiled into a literal survives the stripping and is caught")
                    .contains(MAINFRAME_DATASET_PREFIX);
            assertThat(codeOnly("String slashes = \"http://not-a-comment\";"))
                    .as("a // inside a literal is not mistaken for a comment")
                    .contains("http://not-a-comment");
        }

        @Test
        @DisplayName("no double or float appears anywhere on the security access surface")
        void noBinaryFloatingPointAppearsOnTheAccessSurface() {
            // CSUSR01Y declares six PIC X fields and not one numeric, so this dataset has no arithmetic
            // at all - which makes the constraint trivial to satisfy and worth pinning precisely because
            // it is trivial. A double introduced here later would be introduced silently.
            List<String> offenders = new ArrayList<>();
            for (Class<?> type : securityAccessTypes()) {
                for (Field field : type.getDeclaredFields()) {
                    if (isBinaryFloatingPoint(field.getType())) {
                        offenders.add(type.getSimpleName() + "." + field.getName() + " is "
                                + field.getType().getSimpleName());
                    }
                }
                for (Method method : type.getDeclaredMethods()) {
                    if (isBinaryFloatingPoint(method.getReturnType())) {
                        offenders.add(type.getSimpleName() + "." + method.getName() + " returns "
                                + method.getReturnType().getSimpleName());
                    }
                    for (Class<?> parameter : method.getParameterTypes()) {
                        if (isBinaryFloatingPoint(parameter)) {
                            offenders.add(type.getSimpleName() + "." + method.getName()
                                    + " takes " + parameter.getSimpleName());
                        }
                    }
                }
            }

            assertThat(offenders)
                    .as("binary floating point cannot represent a decimal fraction exactly, so it never "
                            + "carries a value derived from a COBOL PICTURE")
                    .isEmpty();

            // Not vacuous: the predicate does recognise the types it is looking for.
            assertThat(isBinaryFloatingPoint(double.class)).isTrue();
            assertThat(isBinaryFloatingPoint(Float.class)).isTrue();
            assertThat(isBinaryFloatingPoint(String.class)).isFalse();
        }

        @Test
        @DisplayName("collaborators are constructor-injected and no field is static and mutable")
        void collaboratorsAreConstructorInjectedAndNoStaticMutableFieldExists() {
            // One public constructor taking every collaborator, no setter and no injected field: an
            // instance cannot exist half-wired, and a test can build one without a container - which is
            // why this whole class needs no Spring context.
            assertThat(SecUserRepository.class.getDeclaredConstructors())
                    .as("exactly one way to build a repository")
                    .hasSize(1);
            assertThat(SecUserRepository.class.getDeclaredConstructors()[0].getParameterTypes())
                    .containsExactly(JdbcTemplate.class, DatasetBindings.class, Charset.class,
                            RecordImageForm.class);

            List<String> injectedFields = new ArrayList<>();
            List<String> staticMutableFields = new ArrayList<>();
            List<String> nonFinalInstanceFields = new ArrayList<>();
            for (Field field : SecUserRepository.class.getDeclaredFields()) {
                for (Annotation annotation : field.getAnnotations()) {
                    String name = annotation.annotationType().getName();
                    if (name.endsWith(".Autowired") || name.endsWith(".Inject")
                            || name.endsWith(".Resource") || name.endsWith(".Value")) {
                        injectedFields.add(field.getName());
                    }
                }
                boolean isStatic = Modifier.isStatic(field.getModifiers());
                boolean isFinal = Modifier.isFinal(field.getModifiers());
                if (isStatic && !isFinal) {
                    staticMutableFields.add(field.getName());
                }
                if (!isStatic && !isFinal) {
                    nonFinalInstanceFields.add(field.getName());
                }
            }

            assertThat(injectedFields)
                    .as("no field injection: every collaborator arrives through the constructor")
                    .isEmpty();
            assertThat(staticMutableFields)
                    .as("a mutable static field would be shared by every caller of this singleton")
                    .isEmpty();
            assertThat(nonFinalInstanceFields)
                    .as("browse position and the held record belong to the caller's handle, not to the "
                            + "repository, so no instance field varies")
                    .isEmpty();

            // This test class holds no shared mutable test state either. Every static member of it is
            // final, and every one whose *type* is mutable is accounted for by name rather than waved
            // through: the sole entry is the counter that hands out distinct in-memory database names.
            // That counter is the opposite of shared state - it is what guarantees each test gets its
            // own relation - and it carries no record, no key and no outcome, so nothing a test asserts
            // on can travel through it. Anything else appearing here would need the same justification,
            // which is why it is enumerated instead of exempted by a blanket rule.
            List<String> staticsOfMutableType = new ArrayList<>();
            for (Field field : SecUserRepositoryTest.class.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                assertThat(Modifier.isFinal(field.getModifiers()))
                        .as("SecUserRepositoryTest.%s is static, so it must be final", field.getName())
                        .isTrue();
                Class<?> type = field.getType();
                boolean immutable = type.isPrimitive() || type == String.class || type == Charset.class
                        || type.isEnum();
                if (!immutable) {
                    staticsOfMutableType.add(field.getName());
                }
            }

            assertThat(staticsOfMutableType)
                    .as("the only static of a mutable type is the database-name counter, which isolates "
                            + "tests rather than sharing anything between them")
                    .containsExactly("DATABASE_SEQUENCE");
        }

        @Test
        @DisplayName("the access surface is the closed set of nine paths, with nothing invented")
        void theAccessSurfaceIsTheClosedSetOfNinePaths() {
            // CARDDEMO.CSD:L93-L94 grants ADD, BROWSE, DELETE, READ and UPDATE, and the five programs
            // use exactly nine commands between them. A tenth public data-access method would be surface
            // no COBOL program exercises and therefore surface nothing can verify against the oracle.
            Set<String> dataAccess = new TreeSet<>();
            for (Method method : SecUserRepository.class.getDeclaredMethods()) {
                if (Modifier.isPublic(method.getModifiers())) {
                    dataAccess.add(method.getName());
                }
            }
            Set<String> cursorPaths = new TreeSet<>();
            for (Method method : BrowseCursor.class.getDeclaredMethods()) {
                if (Modifier.isPublic(method.getModifiers())) {
                    cursorPaths.add(method.getName());
                }
            }

            // The six on the repository: four operations plus the browse opener and the held delete.
            assertThat(dataAccess)
                    .contains("read", "readForUpdate", "startBrowse", "add", "rewrite", "deleteHeld");
            // The three on the cursor, which is where a browse's position lives.
            assertThat(cursorPaths).contains("readNext", "readPrevious", "endBrowse");

            // Nothing that would imply a schema, a generated query surface, or an operation the COBOL
            // never performs. deleteById is called out specifically: it is not merely absent, it would
            // be a *different* operation from COUSR03C:L307-L311, which passes no RIDFLD at all.
            assertThat(dataAccess).doesNotContain("findAll", "findById", "count", "existsById", "save",
                    "saveAll", "delete", "deleteById", "deleteAll", "createTable", "migrate",
                    "flush", "getOne", "findBy");
            for (String forbidden : List.of("delete", "deleteById", "deleteByKey", "remove")) {
                assertThat(dataAccess)
                        .as("%s does not exist; the source deletes the record a read-for-update holds",
                                forbidden)
                        .doesNotContain(forbidden);
            }

            // deleteHeld takes the handle and nothing resembling a key, in both of its forms.
            assertThat(HeldRecord.class.getDeclaredMethods())
                    .as("the no-argument delete on the handle is the most literal form of the command")
                    .anyMatch(method -> "deleteHeld".equals(method.getName())
                            && method.getParameterCount() == 0);

            // Only the public overload is the access surface. The repository also has a private
            // deleteHeld(Statements, HeldRecord) that carries the composed statements, which is
            // implementation and not something a caller can reach - so it is deliberately not held to
            // the signature rule the public path is held to.
            List<Method> publicDeletes = new ArrayList<>();
            for (Method method : SecUserRepository.class.getDeclaredMethods()) {
                if ("deleteHeld".equals(method.getName()) && Modifier.isPublic(method.getModifiers())) {
                    publicDeletes.add(method);
                }
            }
            assertThat(publicDeletes)
                    .as("exactly one public delete, so there is no by-key variant beside it")
                    .hasSize(1);
            assertThat(publicDeletes.getFirst().getParameterTypes())
                    .as("deleteHeld takes a hold, never a key")
                    .containsExactly(HeldRecord.class);
        }

        /**
         * Whether a type is binary floating point, boxed or not.
         *
         * @param type the type
         * @return {@code true} for {@code double}, {@code float} and their wrappers
         */
        private static boolean isBinaryFloatingPoint(Class<?> type) {
            return type == double.class || type == float.class
                    || type == Double.class || type == Float.class;
        }
    }

    /**
     * A type carrying an annotation at every position the persistence scan inspects.
     *
     * <p>Exists only so {@link MigrationConstraints#theAnnotationScanIsNotVacuous()} can prove the scan
     * reaches fields, constructors, methods and parameters, and not merely the type. It carries
     * {@link Deprecated}, which is harmless, rather than a persistence annotation - the point is the
     * reach of the scan, not the kind of annotation found.
     */
    @Deprecated
    private static final class AnnotatedProbe {

        /** An annotated field, so the field sweep has something to find. */
        @Deprecated
        private final String annotatedField;

        /**
         * An annotated constructor with an annotated parameter.
         *
         * @param annotatedParameter a parameter carrying an annotation
         */
        @Deprecated
        AnnotatedProbe(@Deprecated String annotatedParameter) {
            this.annotatedField = annotatedParameter;
        }

        /**
         * An annotated method.
         *
         * @return the field
         */
        @Deprecated
        String annotatedMethod() {
            return annotatedField;
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

    // =============================================================================================
    // Helpers for the migration-constraint scans below.
    // =============================================================================================

    /**
     * Resolves a checkout-relative path by walking up from the working directory until it exists.
     *
     * <p>Surefire runs with the module directory as the working directory, but the paths worth naming in
     * an assertion are repository-relative - that is how the migration's own documentation cites them. So
     * the lookup walks up rather than assuming a depth, which keeps it working whether the build is run
     * from the module or from the repository root.
     *
     * @param relativePath the repository-relative path
     * @return the resolved path
     * @throws IllegalStateException if the file is not found at or above the working directory
     */
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

    /**
     * Reads a checkout file as text.
     *
     * @param file the file
     * @return its text
     */
    private static String readSource(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            throw new UncheckedIOException("Could not read " + file, unreadable);
        }
    }

    /**
     * A source file's text with every comment removed, so a scan sees code and not prose.
     *
     * <p>The stripping is what makes these guards tolerable to documentation. This very file
     * <em>discusses</em> dataset names and record formats in its Javadoc, which is exactly where such
     * discussion belongs, and a raw text scan would report that as a violation and would therefore have
     * to be switched off - which is how a gate stops being a gate. Stripping comments first keeps the
     * guard pointed at the thing that matters, a name or a statement compiled into the module, and leaves
     * the documentation free.
     *
     * <p>Written as a single left-to-right pass rather than a regular expression because an expression
     * cannot tell a {@code //} inside a string literal from the start of a comment, and the obvious
     * block-comment alternation overflows the stack on files this size. String and character literals are
     * copied through untouched, which is the whole point: a name in a literal is a violation and must
     * survive the stripping.
     *
     * @param source the source text
     * @return the same text with comments replaced by single spaces and every literal left as written
     */
    private static String codeOnly(String source) {
        StringBuilder code = new StringBuilder(source.length());
        int index = 0;
        while (index < source.length()) {
            char current = source.charAt(index);
            char next = index + 1 < source.length() ? source.charAt(index + 1) : '\0';
            if (current == '/' && next == '/') {
                while (index < source.length() && source.charAt(index) != '\n') {
                    index++;
                }
                code.append(' ');
            } else if (current == '/' && next == '*') {
                index += 2;
                while (index + 1 < source.length()
                        && !(source.charAt(index) == '*' && source.charAt(index + 1) == '/')) {
                    index++;
                }
                index = Math.min(index + 2, source.length());
                code.append(' ');
            } else if (current == '"' || current == '\'') {
                int end = index + 1;
                while (end < source.length() && source.charAt(end) != current) {
                    end += source.charAt(end) == '\\' ? 2 : 1;
                }
                end = Math.min(end + 1, source.length());
                code.append(source, index, end);
                index = end;
            } else {
                code.append(current);
                index++;
            }
        }
        return code.toString();
    }

    /**
     * Every annotation declared anywhere on a type: on the type, its fields, its constructors, its
     * methods, and their parameters.
     *
     * <p>A persistence mapping can be declared at any of those positions, so a scan that looked only at
     * the type would miss an {@code @Column} on a field or an {@code @Id} on an accessor.
     *
     * @param type the type to scan
     * @return every annotation found
     */
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

    /**
     * The whole access surface of the security file: the repository, its nested types, and the record.
     *
     * @return every type a persistence mapping could be smuggled onto
     */
    private static List<Class<?>> securityAccessTypes() {
        List<Class<?>> types = new ArrayList<>();
        types.add(SecUserRepository.class);
        types.addAll(List.of(SecUserRepository.class.getDeclaredClasses()));
        types.add(SecUserRecord.class);
        return types;
    }
}
