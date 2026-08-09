package com.vsergeychik.carddemo.transaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.vsergeychik.carddemo.common.FileStatus;
import com.vsergeychik.carddemo.common.FileStatus.Outcome;
import com.vsergeychik.carddemo.common.FixedWidthRecord.FieldSpan;
import com.vsergeychik.carddemo.common.RecordImageForm;
import com.vsergeychik.carddemo.config.CobolCharsetConfig;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBinding;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBindings;
import com.vsergeychik.carddemo.transaction.DateParmReader.DateParm;
import com.vsergeychik.carddemo.transaction.DateParmReader.ReadResult;

import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.lang.reflect.RecordComponent;
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
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentMatchers;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

/**
 * Proves the {@code DATEPARM} reader against {@code app/cbl/CBTRN03C.cbl}.
 *
 * <p>This is the one contract in the estate where a batch program's parameter arrives in a
 * <strong>dataset</strong> rather than in a JCL {@code PARM}: {@code app/jcl/TRANREPT.jcl} declares a
 * {@code DATEPARM} DD, {@code CBTRN03C} opens it at {@code L166}, reads it exactly once at {@code L168}
 * and closes it at {@code L213}, and the eighty-byte record it reads carries the report's date range in
 * its leading twenty-one bytes. Three obligations follow, and each is held here:
 * <ul>
 *   <li>the record is decoded by absolute offset - {@code 10 + 1 + 10}, then a fifty-nine byte tail the
 *       program never looks at - so a differently shaped record is refused rather than misread;</li>
 *   <li>the three-armed {@code EVALUATE} at {@code L222-L229} keeps its three arms and no more, and an
 *       empty dataset is reported as the end of file it is rather than substituted for something
 *       friendlier;</li>
 *   <li>a backend refusal reaches the caller as a status <em>and</em> as the driver's own diagnosis, so
 *       the abend that follows can be traced to a cause.</li>
 * </ul>
 *
 * <h2>The one thing a future reader must not "simplify"</h2>
 * <p>A date range that arrives as two ten-character strings looks exactly like something that belongs
 * in a job parameter, and turning it into one would compile, wire and pass a naive test. It would also
 * be wrong, so the {@link RangeIsDataNotAParameter} group below asserts the negative directly: the
 * range is obtained by executing a statement that names the configured dataset, and no
 * {@code JobParameter}, no {@code @Value}-injected date and no system property can reach it. The
 * contrast is one file away and is genuinely a parameter -
 * {@code app/jcl/INTCALC.jcl:L22 //STEP15 EXEC PGM=CBACT04C,PARM='2022071800'} - whereas
 * {@code app/jcl/TRANREPT.jcl:L59 //STEP10R EXEC PGM=CBTRN03C} carries no {@code PARM} at all and binds
 * a DD instead at {@code L73-L74}. Two adjacent programs, two different mechanisms; only one of them is
 * this reader's.
 *
 * <h2>Where the SYSOUT text is asserted, and why not here</h2>
 * <p>{@code 0550-DATEPARM-READ} ends in {@code DISPLAY 'Reporting from ' WS-START-DATE ' to '
 * WS-END-DATE} ({@code app/cbl/CBTRN03C.cbl:L232-L233}), and the reader deliberately surfaces no text:
 * it returns a {@link DateParm} and stops. <strong>The choice made here is therefore that the caller
 * owns the line</strong> - the byte-exact literals {@code 'Reporting from '} and {@code ' to '} are
 * asserted in {@code TransactionReportJobTest}, alongside the job's other emitted lines, so the job's
 * whole SYSOUT fingerprint is compared in one place instead of half here and half there. What this
 * suite does assert is that the two <em>operands</em> that line interpolates come back at their exact
 * declared widths and in the right order, which is the part the reader is answerable for; see
 * {@link TheLadderInSourceOrder#theSuccessfulArmSuppliesTheDisplayOperandsUntouched()}.
 */
@DisplayName("DateParmReader - the report date range, read from a dataset rather than a PARM")
class DateParmReaderTest {

    /** The code page, always named. */
    private static final Charset ASCII = StandardCharsets.US_ASCII;

    /** A stand-in dataset name: this suite proves the name comes from configuration. */
    private static final String DSNAME = "TEST.CARDDEMO.DATEPARM";

    /** The column name the stand-in backend describes. Deliberately not a copybook field name. */
    private static final String COLUMN = "PS_RECORD_IMAGE";

    /** The describe the reader issues for an open, a close and before any read. */
    private static final String DESCRIBE_SQL = "SELECT * FROM \"" + DSNAME + "\" WHERE 1 = 0";

    /** The read the reader issues: unordered, because a physical-sequential dataset has no key. */
    private static final String SELECT_SQL = "SELECT * FROM \"" + DSNAME + "\"";

    /** The first shipped date-range record, padded to its declared eighty bytes. */
    private static final String RECORD = "2022-01-01 2022-12-31" + " ".repeat(59);

    /**
     * A binding catalogue declaring {@code DATEPARM} at the width the copybook declares.
     *
     * @param dsname       the dataset name
     * @param recordLength the declared record width
     * @return the catalogue
     */
    private static DatasetBindings bindings(String dsname, int recordLength) {
        DatasetBindings catalogue = new DatasetBindings();
        catalogue.put(DateParmReader.DD_NAME, new DatasetBinding(dsname, "ps", false, "FB", null,
                recordLength, null, null, null, null, null));
        return catalogue;
    }

    /** The catalogue most of these tests use. */
    private static DatasetBindings validBindings() {
        return bindings(DSNAME, DateParmReader.RECORD_LENGTH);
    }

    /**
     * Stubs the describe a reader issues before it composes or probes anything.
     *
     * @param jdbcTemplate the mocked template
     */
    private static void stubDescribe(JdbcTemplate jdbcTemplate) {
        when(jdbcTemplate.query(eq(DESCRIBE_SQL),
                ArgumentMatchers.<ResultSetExtractor<String>>any())).thenReturn(FileStatus.OK);
    }

    /**
     * Stubs the read to return the given record images.
     *
     * @param jdbcTemplate the mocked template
     * @param rows         the images, which may contain a {@code null} element, or {@code null} to make
     *                     the template yield no result object at all
     */
    private static void stubRows(JdbcTemplate jdbcTemplate, List<String> rows) {
        stubRows(jdbcTemplate, rows, new ArrayList<>());
    }

    /**
     * Stubs the read and records how the statement it sent was bounded.
     *
     * <p>The read is issued through a {@link PreparedStatementCreator} rather than as a bare SQL string,
     * because {@code CBTRN03C} performs one {@code READ} and the statement is bounded to one row. So the
     * stub cannot simply match on the SQL: it runs the creator against a recording connection, which is
     * what makes both the statement text and its row limits observable.
     *
     * @param jdbcTemplate the mocked template
     * @param rows         the images, which may contain a {@code null} element, or {@code null} to make
     *                     the template yield no result object at all
     * @param sink         collects one entry per read, in order
     */
    private static void stubRows(JdbcTemplate jdbcTemplate, List<String> rows,
            List<BoundedStatement> sink) {
        // The row mapper now yields the stored BYTES, because the record-image representation - not this
        // reader - decides whether the column is read as characters or as bytes. The fixture rows stay
        // text here, where they are legible, and are encoded in the same code page the reader is given.
        List<byte[]> images = rows == null
                ? null
                : rows.stream().map(row -> row == null ? null : row.getBytes(ASCII)).toList();
        when(jdbcTemplate.query(ArgumentMatchers.<PreparedStatementCreator>any(),
                ArgumentMatchers.<RowMapper<byte[]>>any()))
                .thenAnswer(invocation -> {
                    sink.add(BoundedStatement.of(
                            invocation.getArgument(0, PreparedStatementCreator.class)));
                    return images;
                });
    }

    /**
     * What a statement asked the driver for, captured by running the creator that composed it.
     *
     * @param sql       the statement text prepared
     * @param maxRows   the value passed to {@link java.sql.Statement#setMaxRows(int)}, or -1 if unset
     * @param fetchSize the value passed to {@link java.sql.Statement#setFetchSize(int)}, or -1 if unset
     */
    private record BoundedStatement(String sql, int maxRows, int fetchSize) {

        /** Sentinel for a limit the creator never set, so "unset" is distinguishable from "set to 0". */
        private static final int UNSET = -1;

        /**
         * Runs a creator against a recording connection and reports what it prepared.
         *
         * @param creator the creator to drive
         * @return what it asked for
         */
        private static BoundedStatement of(PreparedStatementCreator creator) throws SQLException {
            Connection connection = mock(Connection.class);
            PreparedStatement prepared = mock(PreparedStatement.class);
            AtomicReference<String> sql = new AtomicReference<>();
            AtomicInteger maxRows = new AtomicInteger(UNSET);
            AtomicInteger fetchSize = new AtomicInteger(UNSET);
            when(connection.prepareStatement(anyString())).thenAnswer(invocation -> {
                sql.set(invocation.getArgument(0, String.class));
                return prepared;
            });
            doAnswer(invocation -> {
                maxRows.set(invocation.getArgument(0, Integer.class));
                return null;
            }).when(prepared).setMaxRows(ArgumentMatchers.anyInt());
            doAnswer(invocation -> {
                fetchSize.set(invocation.getArgument(0, Integer.class));
                return null;
            }).when(prepared).setFetchSize(ArgumentMatchers.anyInt());

            creator.createPreparedStatement(connection);

            return new BoundedStatement(sql.get(), maxRows.get(), fetchSize.get());
        }
    }

    /**
     * A reader over a mocked template whose describe already succeeds.
     *
     * @param jdbcTemplate the mocked template
     * @return the reader
     */
    private static DateParmReader reader(JdbcTemplate jdbcTemplate) {
        stubDescribe(jdbcTemplate);
        return new DateParmReader(jdbcTemplate, validBindings(), ASCII, RecordImageForm.CHARACTER);
    }

    /**
     * Composes an eighty-byte {@code DATEPARM} record from the four spans both
     * {@code app/cbl/CBTRN03C.cbl:L122-L125} and {@code app/cbl/CORPT00C.cbl:L117-L121} declare.
     *
     * <p>The tail fill is a parameter rather than a space on purpose: a record whose bytes 22 to 80 are
     * recognisably <em>not</em> spaces is the only way to tell a reader that truncates from one that
     * merely tolerates trailing whitespace.
     *
     * @param startDate the ten-byte {@code WS-START-DATE} span
     * @param separator the one-byte {@code FILLER} span between the dates
     * @param endDate   the ten-byte {@code WS-END-DATE} span
     * @param tailFill  the character filling the fifty-nine byte tail the receiver move discards
     * @return exactly {@link DateParmReader#RECORD_LENGTH} characters
     */
    private static String record(String startDate, String separator, String endDate, String tailFill) {
        return startDate + separator + endDate
                + tailFill.repeat(DateParmReader.DISCARDED_TAIL_LENGTH);
    }

    /**
     * A stand-in backend built from a <strong>real</strong> {@link JdbcTemplate} over a stubbed JDBC
     * stack, rather than from a mocked template.
     *
     * <p>The difference is the whole point of the {@link RangeIsDataNotAParameter} group. A mocked
     * template proves what the reader <em>asks</em> for; this proves that the statement it composed was
     * executed against the configured dataset and that the range came back through a
     * {@link ResultSet} column - which is precisely the claim "the range is data, read from a dataset"
     * makes. Every SQL string that reaches the driver is recorded, so a second source of the range
     * would have to show up either as an extra statement or as no statement at all.
     *
     * <p>Not static, and holds no static state: each instance owns its own stubs and its own recording.
     */
    private static final class SeededDataset {

        /** The zero-row predicate the describe statement ends in, per {@code DatasetRelation}. */
        private static final String NO_ROW_PREDICATE = "1 = 0";

        /** The real template, wired to the stubbed stack. */
        private final JdbcTemplate template;

        /** Every statement the driver was actually asked to execute, in order. */
        private final List<String> executedSql = new ArrayList<>();

        /** The row limit each prepared statement carried, in order. */
        private final List<Integer> preparedMaxRows = new ArrayList<>();

        /** The fetch size each prepared statement carried, in order. */
        private final List<Integer> preparedFetchSizes = new ArrayList<>();

        /**
         * @param rows the record images the dataset holds, in physical-sequential order
         * @throws SQLException never in practice - declared because the stubbed JDBC methods declare it
         */
        private SeededDataset(String... rows) throws SQLException {
            List<String> seeded = List.of(rows);
            DataSource dataSource = mock(DataSource.class);
            Connection connection = mock(Connection.class);
            Statement statement = mock(Statement.class);
            when(dataSource.getConnection()).thenReturn(connection);
            when(connection.createStatement()).thenReturn(statement);
            when(statement.executeQuery(anyString())).thenAnswer(invocation -> {
                String sql = invocation.getArgument(0, String.class);
                executedSql.add(sql);
                // The describe deliberately matches no row - it resolves the relation and transfers
                // nothing - so it is answered with metadata and an empty cursor, exactly as a driver
                // would answer it.
                return resultSetOver(sql.endsWith(NO_ROW_PREDICATE) ? List.of() : seeded);
            });
            // The read is a bounded PREPARED statement: CBTRN03C performs one READ, so the statement
            // carries setMaxRows(1). This stub HONOURS that limit rather than ignoring it, so a fixture
            // holding two records serves one - which is what a driver does and what makes the bound
            // observable rather than merely asserted.
            when(connection.prepareStatement(anyString())).thenAnswer(prepareInvocation -> {
                String sql = prepareInvocation.getArgument(0, String.class);
                PreparedStatement prepared = mock(PreparedStatement.class);
                AtomicInteger limit = new AtomicInteger(Integer.MAX_VALUE);
                doAnswer(limitInvocation -> {
                    int requested = limitInvocation.getArgument(0, Integer.class);
                    preparedMaxRows.add(requested);
                    limit.set(requested == 0 ? Integer.MAX_VALUE : requested);
                    return null;
                }).when(prepared).setMaxRows(ArgumentMatchers.anyInt());
                doAnswer(fetchInvocation -> {
                    preparedFetchSizes.add(fetchInvocation.getArgument(0, Integer.class));
                    return null;
                }).when(prepared).setFetchSize(ArgumentMatchers.anyInt());
                when(prepared.executeQuery()).thenAnswer(executeInvocation -> {
                    executedSql.add(sql);
                    List<String> served = sql.endsWith(NO_ROW_PREDICATE) ? List.of() : seeded;
                    return resultSetOver(served.subList(0, Math.min(limit.get(), served.size())));
                });
                return prepared;
            });
            this.template = new JdbcTemplate(dataSource);
        }

        /**
         * A cursor over the given record images, presenting one record-image column.
         *
         * @param rows the images to serve
         * @return the stubbed cursor
         * @throws SQLException never in practice
         */
        private static ResultSet resultSetOver(List<String> rows) throws SQLException {
            ResultSetMetaData metaData = mock(ResultSetMetaData.class);
            when(metaData.getColumnCount()).thenReturn(1);
            when(metaData.getColumnName(DateParmReader.RECORD_IMAGE_COLUMN_INDEX)).thenReturn(COLUMN);
            ResultSet resultSet = mock(ResultSet.class);
            when(resultSet.getMetaData()).thenReturn(metaData);
            AtomicInteger cursor = new AtomicInteger();
            when(resultSet.next()).thenAnswer(invocation -> {
                if (cursor.get() >= rows.size()) {
                    return false;
                }
                cursor.incrementAndGet();
                return true;
            });
            // Read by POSITION, never by name: RecordImageForm.CHARACTER reads column 1 as characters.
            when(resultSet.getString(DateParmReader.RECORD_IMAGE_COLUMN_INDEX))
                    .thenAnswer(invocation -> rows.get(cursor.get() - 1));
            return resultSet;
        }

        /**
         * A reader over this backend.
         *
         * @return the reader
         */
        private DateParmReader reader() {
            return new DateParmReader(template, validBindings(), ASCII, RecordImageForm.CHARACTER);
        }
    }

    // =================================================================================================
    // Construction. Everything checkable about the configuration is checked before the context finishes
    // starting, so a misconfiguration fails at refresh rather than mid-report.
    // =================================================================================================

    @Nested
    @DisplayName("Construction is configuration-bound and refuses to guess")
    class Construction {

        @Test
        @DisplayName("resolves the dataset name from configuration and composes both statements from it")
        void resolvesTheNameFromConfiguration() {
            DateParmReader reader = reader(mock(JdbcTemplate.class));

            assertThat(reader.datasetName()).isEqualTo(DSNAME);
            assertThat(reader.describeStatement()).isEqualTo(DESCRIBE_SQL);
            assertThat(reader.selectRecordSql()).isEqualTo(SELECT_SQL);
        }

        @Test
        @DisplayName("the read is deliberately unordered: a physical-sequential dataset has no key")
        void thePsReadIsUnordered() {
            // A PS file's records are in the order they were written and READ returns them in that
            // order, so an ORDER BY would reorder the file rather than make the read deterministic. A
            // keyed dataset is the opposite case, and its browse states its ordering explicitly.
            assertThat(reader(mock(JdbcTemplate.class)).selectRecordSql())
                    .doesNotContain("ORDER BY");
        }

        @Test
        @DisplayName("every collaborator is required and none is defaulted")
        void everyCollaboratorIsRequired() {
            DatasetBindings catalogue = validBindings();

            assertThatNullPointerException()
                    .isThrownBy(() -> new DateParmReader(null, catalogue, ASCII, RecordImageForm.CHARACTER))
                    .withMessageContaining("JdbcTemplate is required");
            assertThatNullPointerException()
                    .isThrownBy(() -> new DateParmReader(new JdbcTemplate(), null, ASCII, RecordImageForm.CHARACTER))
                    .withMessageContaining("carddemo.datasets");
            assertThatNullPointerException()
                    .isThrownBy(() -> new DateParmReader(new JdbcTemplate(), catalogue, null, RecordImageForm.CHARACTER))
                    .withMessageContaining("code page is stated explicitly");
        }

        @Test
        @DisplayName("a binding that disagrees with the copybook width is rejected, not accommodated")
        void aWidthDisagreementIsRejected() {
            DatasetBindings wrong = bindings(DSNAME, 79);

            assertThatIllegalStateException()
                    .isThrownBy(() -> new DateParmReader(new JdbcTemplate(), wrong, ASCII, RecordImageForm.CHARACTER))
                    .withMessageContaining("record length of 79");
        }

        @ParameterizedTest(name = "an absent dataset name [{0}] is rejected")
        @CsvSource(value = { "NULL", "''", "'   '" }, nullValues = "NULL")
        @DisplayName("a dataset name the configuration never supplied is rejected")
        void anAbsentDatasetNameIsRejected(String dsname) {
            DatasetBindings absent = bindings(dsname, DateParmReader.RECORD_LENGTH);

            assertThatIllegalStateException()
                    .isThrownBy(() -> new DateParmReader(new JdbcTemplate(), absent, ASCII, RecordImageForm.CHARACTER))
                    .withMessageContaining("carddemo.datasets." + DateParmReader.DD_NAME);
        }

        @ParameterizedTest(name = "a malformed dataset name [{0}] is rejected")
        @ValueSource(strings = { "BAD\tNAME", "TEST.\"ODD\".NAME", "TOOLONGQUALIFIER.X" })
        @DisplayName("a name that is not a well-formed z/OS dataset name never reaches a statement")
        void aMalformedDatasetNameIsRejected(String dsname) {
            DatasetBindings malformed = bindings(dsname, DateParmReader.RECORD_LENGTH);

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new DateParmReader(new JdbcTemplate(), malformed, ASCII, RecordImageForm.CHARACTER))
                    .withMessageContaining("well-formed z/OS dataset name");
        }

        @Test
        @DisplayName("gate G21: the layout is contiguous from offset zero and sums to eighty bytes")
        void theLayoutProvesItsOwnWidth() {
            assertThat(reader(mock(JdbcTemplate.class)).layout().recordLength())
                    .isEqualTo(DateParmReader.RECORD_LENGTH)
                    .isEqualTo(80);
            assertThat(DateParmReader.START_DATE_LENGTH + DateParmReader.SEPARATOR_LENGTH
                    + DateParmReader.END_DATE_LENGTH).isEqualTo(DateParmReader.RECEIVER_LENGTH);
            assertThat(DateParmReader.RECEIVER_LENGTH + DateParmReader.DISCARDED_TAIL_LENGTH)
                    .isEqualTo(DateParmReader.RECORD_LENGTH);
        }
    }

    // =================================================================================================
    // THE DEFINING ASSERTION.
    //
    // DATEPARM is a DD naming a catalogued dataset - app/jcl/TRANREPT.jcl:L73-L74, mirrored at
    // app/proc/TRANREPT.prc:L71-L72 - and app/jcl/TRANREPT.jcl:L59 //STEP10R EXEC PGM=CBTRN03C carries
    // NO PARM. One file away, app/jcl/INTCALC.jcl:L22 //STEP15 EXEC PGM=CBACT04C,PARM='2022071800'
    // genuinely does pass a date-like string as a parameter - to a DIFFERENT program. CBTRN03C reads its
    // range; CBACT04C is handed one. This group asserts that distinction is real in the Java, and not
    // merely described in a comment, because a ten-character date range is exactly the kind of value a
    // later "tidy-up" would lift into a JobParameter.
    // =================================================================================================

    @Nested
    @DisplayName("The range is DATA read from a dataset - never a JobParameter, property or PARM")
    class RangeIsDataNotAParameter {

        /** Type-name fragments that would betray parameter or property machinery in this reader. */
        private static final List<String> PARAMETER_MACHINERY = List.of(
                "JobParameter", "JobParameters", "JobExecution", "StepExecution", "StepContribution",
                "ChunkContext", "JobLauncher", "Environment", "PropertyResolver", "PropertySources",
                "ExpressionParser", "ApplicationContext", "BeanFactory");

        /** Annotation names that would bind a value from outside the dataset. */
        private static final List<String> BINDING_ANNOTATIONS = List.of(
                "Value", "StepScope", "JobScope", "ConfigurationProperties", "PropertySource",
                "Scheduled", "RequestParam");

        @Test
        @DisplayName("read() executes a statement naming the configured dataset, and that is its source")
        void theRangeIsObtainedByExecutingAStatementAgainstTheDataset() throws SQLException {
            // A real JdbcTemplate over a stubbed driver, so this is not "what the reader asked a mock
            // for" but "what the driver was actually made to execute, and what came back through a row".
            SeededDataset dataset = new SeededDataset(RECORD);
            DateParmReader reader = dataset.reader();

            ReadResult result = reader.read();

            assertThat(result.isFound())
                    .as("the range must arrive by reading the dataset, so the read has to succeed")
                    .isTrue();
            assertThat(dataset.executedSql)
                    .as("exactly one statement, and it names the dataset configuration supplied")
                    .containsExactly(SELECT_SQL);
            assertThat(dataset.executedSql.get(0)).contains(DSNAME);
            assertThat(result.dateParm().orElseThrow().receiverImage())
                    .as("and the value returned is the dataset's own leading 21 bytes")
                    .isEqualTo(RECORD.substring(0, DateParmReader.RECEIVER_LENGTH));
        }

        @Test
        @DisplayName("the range is the row's column-one image, so removing the row removes the range")
        void withoutARowThereIsNoRangeAtAll() throws SQLException {
            // The negative half of the same claim. If any second source existed - a parameter, a
            // property, a hard-coded default - an empty dataset would still yield a range. It does not:
            // it yields the end of file, with nothing substituted for the missing record.
            SeededDataset empty = new SeededDataset();

            ReadResult result = empty.reader().read();

            assertThat(result.isFound()).isFalse();
            assertThat(result.dateParm())
                    .as("no fallback range exists anywhere in this reader")
                    .isEmpty();
            assertThat(empty.executedSql).containsExactly(SELECT_SQL);
        }

        @Test
        @DisplayName("a driver honouring the row limit serves one record of two, and the read still works")
        void theDriverIsAskedForOneRowOfTwo() throws SQLException {
            // The same claim as the mocked-template tests, but through a real JdbcTemplate against a
            // driver stub that HONOURS setMaxRows. Two records are seeded and one is transferred, so the
            // bound is observed in what came back rather than only in what was asked for.
            SeededDataset dataset = new SeededDataset(
                    RECORD, "1999-01-01 1999-12-31" + " ".repeat(59));
            DateParmReader reader = dataset.reader();

            ReadResult result = reader.read();

            assertThat(result.isFound()).isTrue();
            assertThat(result.dateParm().orElseThrow().startDate())
                    .as("the first record in physical order, which is the one the single READ sees")
                    .isEqualTo("2022-01-01");
            assertThat(dataset.preparedMaxRows).containsExactly(1);
            assertThat(dataset.preparedFetchSizes).containsExactly(1);
            assertThat(dataset.executedSql).containsExactly(SELECT_SQL);
        }

        @Test
        @DisplayName("open() and close() name the dataset too, so neither is a mere connection check")
        void theOpenAndCloseProbesAlsoNameTheDataset() throws SQLException {
            SeededDataset dataset = new SeededDataset(RECORD);
            DateParmReader reader = dataset.reader();

            assertThat(reader.open()).isEqualTo(FileStatus.OK);
            assertThat(reader.close()).isEqualTo(FileStatus.OK);

            assertThat(dataset.executedSql)
                    .containsExactly(DESCRIBE_SQL, DESCRIBE_SQL)
                    .allSatisfy(sql -> assertThat(sql).contains(DSNAME));
        }

        @Test
        @DisplayName("no parameter or property machinery appears in this reader's types (gate G46)")
        void noParameterMachineryAppearsInTheReadersTypes() {
            Set<String> typeNames = new LinkedHashSet<>();
            collectTypeNames(DateParmReader.class, typeNames);
            for (Class<?> nested : DateParmReader.class.getDeclaredClasses()) {
                collectTypeNames(nested, typeNames);
            }

            assertThat(typeNames).isNotEmpty();
            for (String fragment : PARAMETER_MACHINERY) {
                assertThat(typeNames)
                        .withFailMessage("the DATEPARM range is read from a dataset, so no %s may "
                                + "appear anywhere in this reader - see app/jcl/TRANREPT.jcl:L73-L74, "
                                + "and contrast app/jcl/INTCALC.jcl:L22 which really is a PARM. Found "
                                + "in: %s", fragment, typeNames)
                        .noneMatch(name -> name.contains(fragment));
            }
            // The positive control: the collaborator that DOES appear is the one that reaches the
            // dataset, which is what makes the absence of the others meaningful rather than vacuous.
            assertThat(typeNames).anyMatch(name -> name.contains("JdbcTemplate"));
        }

        @Test
        @DisplayName("no annotation binds a value from outside the dataset, and the only scope is singleton")
        void noBindingAnnotationAppearsOnTheReader() {
            List<Annotation> annotations = new ArrayList<>(
                    Arrays.asList(DateParmReader.class.getAnnotations()));
            for (Field field : DateParmReader.class.getDeclaredFields()) {
                annotations.addAll(Arrays.asList(field.getAnnotations()));
            }
            for (Method method : DateParmReader.class.getDeclaredMethods()) {
                annotations.addAll(Arrays.asList(method.getAnnotations()));
                for (Parameter parameter : method.getParameters()) {
                    annotations.addAll(Arrays.asList(parameter.getAnnotations()));
                }
            }
            for (Constructor<?> constructor : DateParmReader.class.getDeclaredConstructors()) {
                annotations.addAll(Arrays.asList(constructor.getAnnotations()));
                for (Parameter parameter : constructor.getParameters()) {
                    annotations.addAll(Arrays.asList(parameter.getAnnotations()));
                }
            }
            List<String> names = annotations.stream()
                    .map(annotation -> annotation.annotationType().getSimpleName())
                    .toList();

            for (String forbidden : BINDING_ANNOTATIONS) {
                assertThat(names)
                        .withFailMessage("@%s would bind the reporting range from somewhere other than "
                                + "the DATEPARM dataset; found among %s", forbidden, names)
                        .doesNotContain(forbidden);
            }
            // What IS there: the plain component stereotype - not the data-access one, which would make a
            // mechanical scan report a thirteenth repository and break the count gate G10 - and a single
            // qualifier, naming the dataset code-page bean at the injection point so the code page is
            // explicit rather than defaulted (practice B8).
            assertThat(DateParmReader.class.getAnnotation(Component.class)).isNotNull();
            assertThat(names).contains("Component", "Qualifier");
        }

        @Test
        @DisplayName("the injected code page is named by bean, so it is never a platform default")
        void theCodePageIsQualifiedByBeanName() {
            Constructor<?> constructor = DateParmReader.class.getDeclaredConstructors()[0];
            List<String> qualifiers = new ArrayList<>();
            for (Parameter parameter : constructor.getParameters()) {
                Qualifier qualifier = parameter.getAnnotation(Qualifier.class);
                if (qualifier != null) {
                    qualifiers.add(qualifier.value());
                }
            }

            assertThat(qualifiers).containsExactly(CobolCharsetConfig.DATASET_CHARSET_BEAN_NAME);
        }

        @Test
        @DisplayName("a system property named like a date parameter changes nothing, because none is read")
        void aSystemPropertyCannotChangeTheRange() throws SQLException {
            SeededDataset dataset = new SeededDataset(RECORD);
            DateParmReader reader = dataset.reader();
            DateParm fromTheDataset = reader.read().dateParm().orElseThrow();
            // The four spellings a well-meaning "simplification" would reach for first. Fixed literals
            // throughout: nothing here consults a clock (practice B7).
            List<String> keys = List.of(
                    "carddemo.report.start-date",
                    "carddemo.report.end-date",
                    "carddemo.datasets." + DateParmReader.DD_NAME + ".start-date",
                    "carddemo.batch.parm-date");
            List<String> restore = new ArrayList<>();
            keys.forEach(key -> restore.add(System.getProperty(key)));
            try {
                keys.forEach(key -> System.setProperty(key, "1970-01-01"));

                DateParm afterPerturbation = reader.read().dateParm().orElseThrow();

                assertThat(afterPerturbation)
                        .as("the range is the dataset's bytes; an external property has no way in")
                        .isEqualTo(fromTheDataset);
                assertThat(afterPerturbation.startDate()).isEqualTo("2022-01-01");
            } finally {
                // Restored whatever the surrounding build set, so this test leaves the JVM as it found
                // it and the suite stays order-independent.
                for (int index = 0; index < keys.size(); index++) {
                    String previous = restore.get(index);
                    if (previous == null) {
                        System.clearProperty(keys.get(index));
                    } else {
                        System.setProperty(keys.get(index), previous);
                    }
                }
            }
        }

        /**
         * Collects every type name reachable from a type's own declarations.
         *
         * @param type      the type to scan
         * @param collected the accumulating set of fully qualified type names
         */
        private void collectTypeNames(Class<?> type, Set<String> collected) {
            for (Field field : type.getDeclaredFields()) {
                collected.add(field.getType().getName());
                collected.add(field.getGenericType().getTypeName());
            }
            for (Constructor<?> constructor : type.getDeclaredConstructors()) {
                for (Class<?> parameterType : constructor.getParameterTypes()) {
                    collected.add(parameterType.getName());
                }
            }
            for (Method method : type.getDeclaredMethods()) {
                collected.add(method.getReturnType().getName());
                collected.add(method.getGenericReturnType().getTypeName());
                for (Class<?> parameterType : method.getParameterTypes()) {
                    collected.add(parameterType.getName());
                }
            }
        }
    }

    // =================================================================================================
    // 0500-DATEPARM-OPEN and 9500-DATEPARM-CLOSE - app/cbl/CBTRN03C.cbl:L466-L482 and L605-L621.
    // =================================================================================================

    @Nested
    @DisplayName("open and close - the two-way status the COBOL guard tests")
    class OpenAndClose {

        @Test
        @DisplayName("a describable dataset opens and closes with status '00'")
        void aDescribableDatasetOpensAndCloses() {
            DateParmReader reader = reader(mock(JdbcTemplate.class));

            assertThat(reader.open()).isEqualTo(FileStatus.OK);
            assertThat(reader.close()).isEqualTo(FileStatus.OK);
        }

        @Test
        @DisplayName("the probe describes the DATASET, not merely the connection")
        void theProbeIsDatasetScoped() throws SQLException {
            // A connection-only check succeeds against a reachable backend that has no such dataset, and
            // would then hand the job an end of file it should have seen as a failed open. This probe
            // names the dataset, so an absent one fails here - which is what an OPEN INPUT reports.
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            DateParmReader reader = new DateParmReader(jdbc, validBindings(), ASCII, RecordImageForm.CHARACTER);
            ResultSetMetaData metaData = mock(ResultSetMetaData.class);
            when(metaData.getColumnCount()).thenReturn(1);
            when(metaData.getColumnName(1)).thenReturn(COLUMN);
            ResultSet described = mock(ResultSet.class);
            when(described.getMetaData()).thenReturn(metaData);
            when(jdbc.query(eq(DESCRIBE_SQL), ArgumentMatchers.<ResultSetExtractor<String>>any()))
                    .thenAnswer(invocation -> invocation
                            .<ResultSetExtractor<String>>getArgument(1)
                            .extractData(described));

            assertThat(reader.open()).isEqualTo(FileStatus.OK);
        }

        @Test
        @DisplayName("a relation with no record-image column is unusable, not a successful open")
        void aRelationWithNoImageColumnIsUnusable() throws SQLException {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            DateParmReader reader = new DateParmReader(jdbc, validBindings(), ASCII, RecordImageForm.CHARACTER);
            ResultSetMetaData metaData = mock(ResultSetMetaData.class);
            when(metaData.getColumnCount()).thenReturn(0);
            ResultSet described = mock(ResultSet.class);
            when(described.getMetaData()).thenReturn(metaData);
            when(jdbc.query(eq(DESCRIBE_SQL), ArgumentMatchers.<ResultSetExtractor<String>>any()))
                    .thenAnswer(invocation -> invocation
                            .<ResultSetExtractor<String>>getArgument(1)
                            .extractData(described));

            // Reporting success on a relation this reader cannot read would hand the job a range it
            // never actually read.
            assertThat(reader.open()).isEqualTo(DateParmReader.PERMANENT_ERROR_STATUS);
        }

        @Test
        @DisplayName("a driver that supplies no metadata at all is unusable rather than successful")
        void noMetadataIsUnusable() throws SQLException {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            DateParmReader reader = new DateParmReader(jdbc, validBindings(), ASCII, RecordImageForm.CHARACTER);
            ResultSet described = mock(ResultSet.class);
            when(described.getMetaData()).thenReturn(null);
            when(jdbc.query(eq(DESCRIBE_SQL), ArgumentMatchers.<ResultSetExtractor<String>>any()))
                    .thenAnswer(invocation -> invocation
                            .<ResultSetExtractor<String>>getArgument(1)
                            .extractData(described));

            assertThat(reader.open()).isEqualTo(DateParmReader.PERMANENT_ERROR_STATUS);
        }

        @Test
        @DisplayName("a template that yields no status at all is a permanent error, not success")
        void aNullStatusIsAPermanentError() {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            when(jdbc.query(anyString(), ArgumentMatchers.<ResultSetExtractor<String>>any()))
                    .thenReturn(null);
            DateParmReader reader = new DateParmReader(jdbc, validBindings(), ASCII, RecordImageForm.CHARACTER);

            assertThat(reader.open()).isEqualTo(DateParmReader.PERMANENT_ERROR_STATUS);
            assertThat(reader.close()).isEqualTo(DateParmReader.PERMANENT_ERROR_STATUS);
        }

        @Test
        @DisplayName("an unreachable dataset is a status, never a throw - the caller owns the abend")
        void anUnreachableDatasetIsAStatus() {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            when(jdbc.query(anyString(), ArgumentMatchers.<ResultSetExtractor<String>>any()))
                    .thenThrow(new DataAccessResourceFailureException("gone",
                            new SQLException("gone", "08006", 17_002)));
            DateParmReader reader = new DateParmReader(jdbc, validBindings(), ASCII, RecordImageForm.CHARACTER);

            assertThat(reader.open()).isEqualTo(DateParmReader.PERMANENT_ERROR_STATUS);
            assertThat(reader.close()).isEqualTo(DateParmReader.PERMANENT_ERROR_STATUS);
        }
    }

    // =================================================================================================
    // 0550-DATEPARM-READ - app/cbl/CBTRN03C.cbl:L220-L243, performed exactly once from L168.
    // =================================================================================================

    @Nested
    @DisplayName("read - the three-armed EVALUATE, arm by arm")
    class Read {

        @Test
        @DisplayName("WHEN '00': the record decodes into the two dates and the separator")
        void aRecordDecodesIntoItsThreeSpans() {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            DateParmReader reader = reader(jdbc);
            stubRows(jdbc, List.of(RECORD));

            ReadResult result = reader.read();

            assertThat(result.isFound()).isTrue();
            assertThat(result.status()).isEqualTo(FileStatus.OK);
            assertThat(result.outcome()).isEqualTo(Outcome.OK);
            assertThat(result.applResult()).isEqualTo(FileStatus.APPL_AOK);
            assertThat(result.diagnostic()).isEmpty();
            DateParm range = result.dateParm().orElseThrow();
            assertThat(range.startDate()).isEqualTo("2022-01-01");
            assertThat(range.separator()).isEqualTo(" ");
            assertThat(range.endDate()).isEqualTo("2022-12-31");
            assertThat(range.receiverImage())
                    .isEqualTo("2022-01-01 2022-12-31")
                    .hasSize(DateParmReader.RECEIVER_LENGTH);
        }

        @Test
        @DisplayName("WHEN '10': an empty dataset is the end of file, and is reported as such")
        void anEmptyDatasetIsEndOfFile() {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            DateParmReader reader = reader(jdbc);
            stubRows(jdbc, Collections.emptyList());

            ReadResult result = reader.read();

            // Real legacy behaviour: the report body comes out empty. It is reported, never substituted.
            assertThat(result.isEndOfFile()).isTrue();
            assertThat(result.status()).isEqualTo(FileStatus.END_OF_FILE);
            assertThat(result.dateParm()).isEmpty();
            assertThat(result.applResult()).isEqualTo(FileStatus.APPL_EOF);
        }

        @Test
        @DisplayName("WHEN OTHER: a refusal carries a status AND what the backend said about it")
        void aRefusalCarriesTheBackendsOwnWords() {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            DateParmReader reader = reader(jdbc);
            // The mapper's element type is byte[], because the record-image representation - not this
            // reader - decides whether column 1 is read as characters or as bytes.
            when(jdbc.query(ArgumentMatchers.<PreparedStatementCreator>any(),
                    ArgumentMatchers.<RowMapper<byte[]>>any()))
                    .thenThrow(new DataAccessResourceFailureException("unreachable",
                            new SQLException("no route to host", "08001", 17_002)));

            ReadResult result = reader.read();

            assertThat(result.isOther()).isTrue();
            assertThat(result.status()).isEqualTo(DateParmReader.PERMANENT_ERROR_STATUS);
            assertThat(result.applResult()).isEqualTo(DateParmReader.APPL_RESULT_FATAL);
            // A permanent-error status says something went wrong and nothing about what. The driver's
            // own SQLSTATE distinguishes an unreachable backend from a missing dataset from a rejected
            // credential - three failures needing three different responses from whoever is on call.
            assertThat(result.diagnostic()).isPresent();
            assertThat(result.diagnostic().orElseThrow().sqlState()).isEqualTo("08001");
            assertThat(result.diagnostic().orElseThrow().vendorCode()).isEqualTo(17_002);
            assertThat(result.diagnostic().orElseThrow().connectionFailure()).isTrue();
        }

        @Test
        @DisplayName("a template that yields no row list at all is the AT END it represents, not a throw")
        void aNullRowListIsTreatedAsAtEnd() {
            // The reader's own guard is `rows == null || rows.isEmpty()`, so an absent list and an empty
            // list are the same outcome: AT END with no record read. That is deliberate rather than
            // careless - a template that answers "no rows" and one that answers "nothing" have both told
            // us the read produced no record, and CBTRN03C has exactly one arm for that, WHEN '10'. What
            // it must never become is a NullPointerException on a batch path whose COBOL cannot raise one.
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            DateParmReader reader = reader(jdbc);
            stubRows(jdbc, null);

            ReadResult result = reader.read();

            assertThat(result.isEndOfFile()).isTrue();
            assertThat(result.status()).isEqualTo(FileStatus.END_OF_FILE);
            assertThat(result.applResult()).isEqualTo(FileStatus.APPL_EOF);
            assertThat(result.dateParm()).isEmpty();
        }

        @Test
        @DisplayName("a row whose image is absent is WHEN OTHER: there is a record and it cannot be read")
        void anAbsentRowImageIsAnIoDefect() {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            DateParmReader reader = reader(jdbc);
            stubRows(jdbc, Collections.singletonList(null));

            ReadResult result = reader.read();

            assertThat(result.isOther()).isTrue();
            assertThat(result.isEndOfFile()).isFalse();
            assertThat(result.diagnostic())
                    .as("no backend refusal occurred, so there is nothing for the driver to diagnose")
                    .isEmpty();
        }

        @Test
        @DisplayName("a stored row that is not 80 bytes is WHEN OTHER, never a padded date range")
        void aRowOfTheWrongWidthIsAnIoDefect() {
            // Finding BD-05. Padded, this row would have decoded to the end date '2022-07   ' - a real
            // range, three weeks short of the one the operator asked for - and the job would have reported
            // it successfully with nothing anywhere saying the parameter had been altered. Reported on the
            // WHEN OTHER arm instead, which is where CBTRN03C displays 'ERROR READING DATEPARM FILE',
            // renders the status and abends.
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            DateParmReader reader = reader(jdbc);
            stubRows(jdbc, List.of("2022-01-01 2022-07"));

            ReadResult result = reader.read();

            assertThat(result.isOther()).isTrue();
            assertThat(result.isFound()).isFalse();
            assertThat(result.isEndOfFile()).isFalse();
            assertThat(result.dateParm()).isEmpty();
            assertThat(result.status()).isEqualTo(DateParmReader.PERMANENT_ERROR_STATUS);
            assertThat(result.applResult()).isEqualTo(DateParmReader.APPL_RESULT_FATAL);
            assertThat(result.diagnostic())
                    .as("no backend refusal occurred; the row itself is the defect")
                    .isEmpty();
        }

        @Test
        @DisplayName("an over-wide stored row is WHEN OTHER too, so the width check is symmetric")
        void anOverWideStoredRowIsAlsoAnIoDefect() {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            DateParmReader reader = reader(jdbc);
            stubRows(jdbc, List.of(RECORD + " "));

            ReadResult result = reader.read();

            assertThat(result.isOther()).isTrue();
            assertThat(result.status()).isEqualTo(DateParmReader.PERMANENT_ERROR_STATUS);
        }

        @Test
        @DisplayName("a row of exactly 80 bytes still decodes, so the check is a width check")
        void aRowOfExactlyEightyBytesStillDecodes() {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            DateParmReader reader = reader(jdbc);
            stubRows(jdbc, List.of(RECORD));

            ReadResult result = reader.read();

            assertThat(result.isFound()).isTrue();
            assertThat(result.dateParm().orElseThrow().receiverImage())
                    .isEqualTo("2022-01-01 2022-12-31");
        }

        @Test
        @DisplayName("only the first record is read: CBTRN03C performs 0550-DATEPARM-READ exactly once")
        void onlyTheFirstRecordIsRead() {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            DateParmReader reader = reader(jdbc);
            stubRows(jdbc, Arrays.asList(RECORD, "1999-01-01 1999-12-31" + " ".repeat(59)));

            assertThat(reader.read().dateParm().orElseThrow().startDate()).isEqualTo("2022-01-01");
        }

        // ------------------------------------------------- the read asks for one row, not the dataset

        @Test
        @DisplayName("the statement is bounded to one row at the backend and at the fetch")
        void theStatementAsksForOneRowOnly() {
            // CBTRN03C performs one READ of DATE-PARM-FILE and never resumes from it, so one record is
            // ever consumed. Asking for the relation and taking element zero would transfer and hold
            // every record to satisfy a read of eighty bytes - on a dataset whose size is a deployment's
            // business, not this reader's.
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            DateParmReader reader = reader(jdbc);
            List<BoundedStatement> sent = new ArrayList<>();
            stubRows(jdbc, List.of(RECORD), sent);

            assertThat(reader.read().isFound()).isTrue();

            assertThat(sent).hasSize(1);
            BoundedStatement bounded = sent.get(0);
            assertThat(bounded.sql())
                    .as("the same statement as before: bounding the read must not change what it reads")
                    .isEqualTo(SELECT_SQL);
            // Two different limits. setMaxRows bounds what the backend produces at all; setFetchSize
            // bounds what one round trip carries. Either alone leaves the other at a driver default.
            assertThat(bounded.maxRows()).isEqualTo(1);
            assertThat(bounded.fetchSize()).isEqualTo(1);
        }

        @Test
        @DisplayName("the statement adds no ORDER BY: ordering a PS dataset would change which record")
        void theBoundedStatementImposesNoOrder() {
            // DATEPARM is physical-sequential - app/proc/TRANREPT.prc:65-66 binds it with no key - so its
            // records are in the order they were written and READ returns the first of them. Limiting the
            // statement does not change which record that is; ordering it would.
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            DateParmReader reader = reader(jdbc);
            List<BoundedStatement> sent = new ArrayList<>();
            stubRows(jdbc, List.of(RECORD), sent);

            reader.read();

            assertThat(sent.get(0).sql()).doesNotContainIgnoringCase("order by");
        }

        @Test
        @DisplayName("re-reading bounds each read the same way, because no cursor is carried between them")
        void everyReadIsBoundedIndependently() {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            DateParmReader reader = reader(jdbc);
            List<BoundedStatement> sent = new ArrayList<>();
            stubRows(jdbc, List.of(RECORD), sent);

            reader.read();
            reader.read();

            assertThat(sent).hasSize(2);
            assertThat(sent).allSatisfy(bounded -> {
                assertThat(bounded.sql()).isEqualTo(SELECT_SQL);
                assertThat(bounded.maxRows()).isEqualTo(1);
                assertThat(bounded.fetchSize()).isEqualTo(1);
            });
        }
    }

    // =================================================================================================
    // The decode seam, free of JDBC so that every offset is exercisable with no backend in the path.
    // =================================================================================================

    @Nested
    @DisplayName("decode - by absolute offset, and the tail the program never looks at")
    class Decode {

        @Test
        @DisplayName("the fifty-nine byte tail is skipped, whatever it holds")
        void theTailIsSkipped() {
            DateParmReader reader = reader(mock(JdbcTemplate.class));
            String withRubbishTail = "2022-01-01 2022-12-31" + "X".repeat(59);

            DateParm range = reader.decode(withRubbishTail);

            assertThat(range.receiverImage()).isEqualTo("2022-01-01 2022-12-31");
        }

        @Test
        @DisplayName("a short image is refused rather than padded into a different reporting range")
        void aShortImageIsRefused() {
            // The receiver move takes the first 21 bytes, so padding a short row is not the harmless
            // repair it looks like: a row truncated inside WS-END-DATE pads into a range the dataset
            // never held, and decodes without complaint. The 21-byte image here is the extreme case -
            // exactly the receiver - and it is refused like any other wrong width.
            DateParmReader reader = reader(mock(JdbcTemplate.class));

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> reader.decode("2022-01-01 2022-12-31"))
                    .withMessageContaining("declared PIC X(" + DateParmReader.RECORD_LENGTH + ")")
                    .withMessageContaining("stored record width in bytes = 21")
                    .withMessageContaining("80-into-21 move");
        }

        @Test
        @DisplayName("a row truncated inside the end date is refused, not turned into another range")
        void aRowTruncatedInsideTheEndDateIsRefused() {
            // The case the finding names. Padded, '2022-01-01 2022-07-06' truncated at 17 bytes would
            // have decoded to the end date '2022-07   ' - a real range, three weeks short, reported
            // successfully.
            DateParmReader reader = reader(mock(JdbcTemplate.class));
            String truncated = "2022-01-01 2022-07";

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> reader.decode(truncated))
                    .withMessageContaining("stored record width in bytes = 18");
            // And the same range at its full declared width decodes exactly as it always did, so the
            // check is a width check and not a refusal to read.
            String full = truncated + "-06" + " ".repeat(DateParmReader.RECORD_LENGTH - 21);
            assertThat(reader.decode(full).endDate()).isEqualTo("2022-07-06");
        }

        @Test
        @DisplayName("an over-wide image is refused rather than silently truncated")
        void anOverWideImageIsRefused() {
            DateParmReader reader = reader(mock(JdbcTemplate.class));

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> reader.decode(RECORD + " "))
                    .withMessageContaining("stored record width in bytes = 81");
        }

        @Test
        @DisplayName("both decode forms agree, and neither accepts an absent image")
        void bothFormsAgree() {
            DateParmReader reader = reader(mock(JdbcTemplate.class));

            assertThat(reader.decode(RECORD)).isEqualTo(reader.decode(RECORD.getBytes(ASCII)));
            assertThatNullPointerException().isThrownBy(() -> reader.decode((String) null));
            assertThatNullPointerException().isThrownBy(() -> reader.decode((byte[]) null));
        }

        @Test
        @DisplayName("a stored byte the code page cannot represent is refused, not substituted")
        void anUndecodableImageIsRefused() {
            DateParmReader reader = reader(mock(JdbcTemplate.class));

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> reader.decode("2022-01-01\u00e92022-12-31"))
                    .withMessageContaining("code page US-ASCII cannot represent")
                    .withMessageContaining("content is withheld deliberately");
        }
    }

    // =================================================================================================
    // The 80-into-21 receiver move, asserted as byte arithmetic rather than as a parsed value.
    //
    // app/cbl/CBTRN03C.cbl:L87-L88 declares the record PIC X(80); L122-L125 declares the receiver as
    // X(10) + X(01) + X(10) = 21. READ ... INTO therefore performs an alphanumeric MOVE of 80 bytes into
    // a 21-byte group, and COBOL right-truncates a PIC X move: bytes 22 to 80 are read off the dataset
    // and discarded. Every assertion below is a byte range, not a date.
    // =================================================================================================

    @Nested
    @DisplayName("Byte offsets - 0..9, the single byte at 10, 11..20, and a tail that is thrown away")
    class ByteOffsets {

        /**
         * A record in which every byte position is individually identifiable, so an off-by-one in any
         * span shows up as a wrong character rather than as a coincidentally equal date.
         */
        private static final String POSITIONAL = record("ABCDEFGHIJ", "|", "KLMNOPQRST", "#");

        @Test
        @DisplayName("each span is exactly the byte range the copybook declares")
        void eachSpanIsExactlyTheDeclaredByteRange() {
            DateParmReader reader = reader(mock(JdbcTemplate.class));

            DateParm range = reader.decode(POSITIONAL);

            assertThat(POSITIONAL).hasSize(DateParmReader.RECORD_LENGTH);
            assertThat(range.startDate())
                    .as("WS-START-DATE PIC X(10) is bytes 1-10, offsets 0..9")
                    .isEqualTo(POSITIONAL.substring(DateParmReader.START_DATE_OFFSET,
                            DateParmReader.START_DATE_OFFSET + DateParmReader.START_DATE_LENGTH))
                    .isEqualTo("ABCDEFGHIJ");
            assertThat(range.separator())
                    .as("the FILLER PIC X(01) separator is the single byte 11, offset 10")
                    .isEqualTo(POSITIONAL.substring(DateParmReader.SEPARATOR_OFFSET,
                            DateParmReader.SEPARATOR_OFFSET + DateParmReader.SEPARATOR_LENGTH))
                    .isEqualTo("|");
            assertThat(range.endDate())
                    .as("WS-END-DATE PIC X(10) is bytes 12-21, offsets 11..20")
                    .isEqualTo(POSITIONAL.substring(DateParmReader.END_DATE_OFFSET,
                            DateParmReader.END_DATE_OFFSET + DateParmReader.END_DATE_LENGTH))
                    .isEqualTo("KLMNOPQRST");
            assertThat(range.receiverImage())
                    .as("and the receiver is those three spans and nothing else")
                    .isEqualTo(POSITIONAL.substring(0, DateParmReader.RECEIVER_LENGTH))
                    .isEqualTo("ABCDEFGHIJ|KLMNOPQRST");
        }

        @Test
        @DisplayName("the offset constants are contiguous from zero and end at the record width")
        void theOffsetConstantsAreContiguous() {
            assertThat(DateParmReader.START_DATE_OFFSET).isZero();
            assertThat(DateParmReader.START_DATE_OFFSET + DateParmReader.START_DATE_LENGTH)
                    .isEqualTo(DateParmReader.SEPARATOR_OFFSET)
                    .isEqualTo(10);
            assertThat(DateParmReader.SEPARATOR_OFFSET + DateParmReader.SEPARATOR_LENGTH)
                    .isEqualTo(DateParmReader.END_DATE_OFFSET)
                    .isEqualTo(11);
            assertThat(DateParmReader.END_DATE_OFFSET + DateParmReader.END_DATE_LENGTH)
                    .as("the receiver ends at byte 21, which is where the discarded tail begins")
                    .isEqualTo(DateParmReader.DISCARDED_TAIL_OFFSET)
                    .isEqualTo(DateParmReader.RECEIVER_LENGTH)
                    .isEqualTo(21);
            assertThat(DateParmReader.DISCARDED_TAIL_OFFSET + DateParmReader.DISCARDED_TAIL_LENGTH)
                    .isEqualTo(DateParmReader.RECORD_LENGTH)
                    .isEqualTo(80);
        }

        @Test
        @DisplayName("gate G21: the layout declares four spans, both FILLERs included, summing to eighty")
        void theLayoutDeclaresEveryByteIncludingFiller() {
            List<FieldSpan> spans = reader(mock(JdbcTemplate.class)).layout().spans();

            // Omitting either FILLER would leave the layout short of 80 bytes and shift every later
            // offset - which is exactly what gate G21 exists to catch.
            assertThat(spans).hasSize(4);
            assertThat(spans).extracting(FieldSpan::offset).containsExactly(0, 10, 11, 21);
            assertThat(spans).extracting(FieldSpan::length).containsExactly(10, 1, 10, 59);
            assertThat(spans.stream().mapToInt(FieldSpan::length).sum())
                    .isEqualTo(DateParmReader.RECORD_LENGTH);
            // The two referable items carry their copybook names verbatim, because the parity differ
            // compares field by field BY NAME: a tidied name would make a real difference invisible.
            assertThat(spans.get(0).name()).isEqualTo("WS-START-DATE")
                    .isEqualTo(DateParmReader.START_DATE_FIELD);
            assertThat(spans.get(2).name()).isEqualTo("WS-END-DATE")
                    .isEqualTo(DateParmReader.END_DATE_FIELD);
            assertThat(spans.get(1).kind().filler())
                    .as("byte 11 is FILLER PIC X(01) in the copybook, and is declared as FILLER here")
                    .isTrue();
            assertThat(spans.get(3).kind().filler())
                    .as("bytes 22-80 are the tail the receiver move discards, declared not ignored")
                    .isTrue();
        }

        @Test
        @DisplayName("a recognisable non-space tail decodes identically to a blank one: this is truncation")
        void aNonSpaceTailIsDiscardedExactlyAsABlankTailIs() {
            // THE assertion that separates a reader which truncates from one which merely tolerates
            // trailing whitespace. Bytes 22-80 here are 'Z's - impossible to mistake for padding - and
            // they change nothing, because the receiver is 21 bytes wide and never sees them.
            DateParmReader reader = reader(mock(JdbcTemplate.class));
            String withLoudTail = record("2022-01-01", " ", "2022-12-31", "Z");
            String withBlankTail = record("2022-01-01", " ", "2022-12-31", " ");

            DateParm fromLoudTail = reader.decode(withLoudTail);

            assertThat(fromLoudTail).isEqualTo(reader.decode(withBlankTail));
            assertThat(fromLoudTail.receiverImage())
                    .isEqualTo("2022-01-01 2022-12-31")
                    .doesNotContain("Z");
            assertThat(fromLoudTail.startDate() + fromLoudTail.separator() + fromLoudTail.endDate())
                    .as("no byte of the discarded tail reaches any span of the receiver")
                    .doesNotContain("Z");
        }

        @ParameterizedTest(name = "a tail of [{0}] is discarded")
        @ValueSource(strings = { "X", "0", "~", "*", "9", " " })
        @DisplayName("whatever fills bytes 22-80, the range is the same twenty-one bytes")
        void theTailIsDiscardedWhateverItHolds(String tailFill) {
            DateParmReader reader = reader(mock(JdbcTemplate.class));

            DateParm range = reader.decode(record("2022-01-01", " ", "2022-07-06", tailFill));

            assertThat(range.receiverImage()).isEqualTo("2022-01-01 2022-07-06");
        }

        @Test
        @DisplayName("the truncation survives the JDBC path too, not only the decode seam")
        void theTruncationHoldsOnTheReadPath() throws SQLException {
            SeededDataset dataset = new SeededDataset(record("2022-01-01", " ", "2022-07-06", "Q"));

            ReadResult result = dataset.reader().read();

            assertThat(result.dateParm().orElseThrow().receiverImage())
                    .isEqualTo("2022-01-01 2022-07-06");
        }
    }

    // =================================================================================================
    // Characters, not dates. app/cbl/CBTRN03C.cbl:L173-L174 compares TRAN-PROC-TS (1:10) against these
    // two spans with >= and <=, which for PIC X operands is a character comparison. Parsing them into a
    // temporal type would impose validation the COBOL does not perform and would discard the padding the
    // comparison depends on.
    // =================================================================================================

    @Nested
    @DisplayName("Untrimmed PIC X(10) characters - never a parsed date")
    class UntrimmedCharactersNotDates {

        @Test
        @DisplayName("an underfilled date keeps its trailing spaces: the span is padded to its width")
        void anUnderfilledDateKeepsItsPadding() {
            // The receiver is fixed-width, so a value narrower than ten characters arrives space-padded
            // to ten and is returned that way. Trimming would change which transactions fall inside the
            // range, because a COBOL alphanumeric comparison pads the shorter operand with spaces.
            DateParmReader reader = reader(mock(JdbcTemplate.class));

            DateParm range = reader.decode(record("2022-1-1  ", " ", "2022-12-1 ", " "));

            assertThat(range.startDate()).isEqualTo("2022-1-1  ").hasSize(10);
            assertThat(range.endDate()).isEqualTo("2022-12-1 ").hasSize(10);
            assertThat(range.startDate()).isNotEqualTo(range.startDate().trim());
            assertThat(range.receiverImage()).hasSize(DateParmReader.RECEIVER_LENGTH);
        }

        @Test
        @DisplayName("an all-space record is a range of spaces, not an absent range")
        void anAllSpaceRecordIsARangeOfSpaces() {
            // Real behaviour worth pinning: CBTRN03C does not validate the record, so an unpopulated
            // DATEPARM record yields two ten-space operands and the L173-L174 comparison then selects
            // nothing. That is a report with no rows - not an error, and not an end of file.
            DateParmReader reader = reader(mock(JdbcTemplate.class));

            DateParm range = reader.decode(" ".repeat(DateParmReader.RECORD_LENGTH));

            assertThat(range.startDate()).isEqualTo(" ".repeat(10));
            assertThat(range.endDate()).isEqualTo(" ".repeat(10));
            assertThat(range.receiverImage()).isEqualTo(" ".repeat(DateParmReader.RECEIVER_LENGTH));
        }

        @Test
        @DisplayName("the range is text: every component is a String, and no temporal type appears")
        void theRangeIsTextAndNotATemporalType() {
            RecordComponent[] components = DateParm.class.getRecordComponents();

            assertThat(components).hasSize(3);
            assertThat(components).extracting(RecordComponent::getName)
                    .containsExactly("startDate", "separator", "endDate");
            for (RecordComponent component : components) {
                assertThat(component.getType())
                        .withFailMessage("component %s must stay a String: CBTRN03C compares these "
                                + "spans as characters at L173-L174 and never parses them",
                                component.getName())
                        .isEqualTo(String.class);
            }
            for (Method method : DateParm.class.getDeclaredMethods()) {
                assertThat(method.getReturnType().getName())
                        .withFailMessage("no accessor may hand back a parsed date; %s returns %s",
                                method.getName(), method.getReturnType().getName())
                        .doesNotStartWith("java.time.");
            }
        }

        @ParameterizedTest(name = "[{0}] decodes untouched, because nothing parses it")
        @ValueSource(strings = { "9999-99-99", "----------", "2022/07/06", "0000-00-00", "not-a-date" })
        @DisplayName("a value no calendar would accept still decodes, exactly as the COBOL leaves it")
        void aValueNoCalendarWouldAcceptStillDecodes(String impossible) {
            // A malformed date in this dataset does not fail CBTRN03C: it simply selects nothing. So the
            // reader must not validate, and must not throw.
            DateParmReader reader = reader(mock(JdbcTemplate.class));

            DateParm range = reader.decode(record(impossible, " ", impossible, " "));

            assertThat(range.startDate()).isEqualTo(impossible);
            assertThat(range.endDate()).isEqualTo(impossible);
        }

        @Test
        @DisplayName("the spans behave as the mainline's character comparison expects")
        void theSpansSupportTheCharacterComparisonTheMainlinePerforms() {
            // Mirrors app/cbl/CBTRN03C.cbl:L173-L174 - IF TRAN-PROC-TS (1:10) >= WS-START-DATE AND
            // TRAN-PROC-TS (1:10) <= WS-END-DATE - to document why untrimmed matters: the operands are
            // ordered lexicographically, and an ISO-shaped ten-character date orders identically to its
            // calendar order only while every span keeps its full declared width.
            DateParmReader reader = reader(mock(JdbcTemplate.class));
            DateParm range = reader.decode(record("2022-01-01", " ", "2022-07-06", " "));
            String insideTheRange = "2022-03-15";
            String afterTheRange = "2022-07-07";

            assertThat(insideTheRange.compareTo(range.startDate())).isGreaterThanOrEqualTo(0);
            assertThat(insideTheRange.compareTo(range.endDate())).isLessThanOrEqualTo(0);
            assertThat(afterTheRange.compareTo(range.endDate())).isGreaterThan(0);
        }

        @Test
        @DisplayName("underfilled INSIDE eighty bytes is padded; not eighty bytes at all is declined")
        void paddingWithinTheRecordIsAcceptedButAShortRecordIsNot() {
            // The two cases are easy to conflate and the reader treats them differently on purpose.
            //
            // A span underfilled inside a full-width record is padding, and is read back verbatim - the
            // record IS eighty bytes, so the receiver move is the one the COBOL performs.
            //
            // A row that is not eighty bytes is something else: FD-DATEPARM-REC is PIC X(80) and
            // CORPT00C:L117-L121 emits exactly eighty bytes, so a short row can only mean the backend is
            // not serving this layout. Widening it would let a row truncated inside WS-END-DATE decode
            // to a DIFFERENT range - '2022-07-06' becomes '2022-07   ' - and the job would report that
            // range successfully with nothing anywhere saying the parameter had been altered. So the
            // width is required first and the disagreement is reported, which is the same conclusion the
            // reader's own documentation reaches. Recorded here because a plausible reading of the brief
            // is that a short record should be space-padded rather than refused; it is refused, and the
            // reason is that padding this particular record silently changes what the report covers.
            DateParmReader reader = reader(mock(JdbcTemplate.class));

            assertThat(reader.decode(record("2022-7    ", " ", "2022-12   ", " ")).startDate())
                    .isEqualTo("2022-7    ");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> reader.decode("2022-01-01 2022-07-06"))
                    .withMessageContaining("80-into-21 move");
        }
    }

    // =================================================================================================
    // The two outcome types' own invariants.
    // =================================================================================================

    @Nested
    @DisplayName("DateParm and ReadResult enforce their own invariants")
    class Invariants {

        @Test
        @DisplayName("every span carries its padding: never trimmed and never short")
        void everySpanIsExactlyItsDeclaredWidth() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new DateParm("2022-01-1", " ", "2022-12-31"))
                    .withMessageContaining(DateParmReader.START_DATE_FIELD);
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new DateParm("2022-01-01", "", "2022-12-31"))
                    .withMessageContaining("FILLER");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new DateParm("2022-01-01", " ", "2022-12-3"))
                    .withMessageContaining(DateParmReader.END_DATE_FIELD);
            assertThatNullPointerException()
                    .isThrownBy(() -> new DateParm(null, " ", "2022-12-31"));
        }

        @Test
        @DisplayName("a status and its classification are two views of one fact and may not disagree")
        void aStatusAndItsClassificationMustAgree() {
            DateParm range = new DateParm("2022-01-01", " ", "2022-12-31");

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new ReadResult(FileStatus.OK, Outcome.END_OF_FILE,
                            Optional.empty(), Optional.empty()))
                    .withMessageContaining("must agree");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new ReadResult(FileStatus.END_OF_FILE, Outcome.OK,
                            Optional.of(range), Optional.empty()))
                    .withMessageContaining("must agree");
        }

        @Test
        @DisplayName("the EVALUATE has three arms, so a keyed classification cannot be built")
        void onlyThreeArmsExist() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new ReadResult(FileStatus.NOT_FOUND, Outcome.NOT_FOUND,
                            Optional.empty(), Optional.empty()))
                    .withMessageContaining("three arms");
        }

        @Test
        @DisplayName("the range is present exactly on the successful arm")
        void theRangeIsPresentExactlyOnSuccess() {
            DateParm range = new DateParm("2022-01-01", " ", "2022-12-31");

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new ReadResult(FileStatus.END_OF_FILE, Outcome.END_OF_FILE,
                            Optional.of(range), Optional.empty()))
                    .withMessageContaining("carries no date range");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new ReadResult(FileStatus.OK, Outcome.OK, Optional.empty(),
                            Optional.empty()))
                    .withMessageContaining("carries the decoded date range");
        }

        @Test
        @DisplayName("a status the EVALUATE names explicitly cannot be classified as WHEN OTHER")
        void anExplicitlyNamedStatusIsNotWhenOther() {
            // Both of them: the EVALUATE names '00' and '10', and neither may arrive dressed as the
            // catch-all, or a caller switching on the outcome would take a different arm from one
            // switching on the status.
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new ReadResult(FileStatus.OK, Outcome.OTHER, Optional.empty(),
                            Optional.empty()))
                    .withMessageContaining("cannot be classified as WHEN OTHER");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new ReadResult(FileStatus.END_OF_FILE, Outcome.OTHER,
                            Optional.empty(), Optional.empty()))
                    .withMessageContaining("cannot be classified as WHEN OTHER");
        }

        @Test
        @DisplayName("a status is exactly two characters, and no component may be null")
        void everyComponentIsRequired() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> ReadResult.other("9"))
                    .withMessageContaining("exactly 2 characters");
            assertThatNullPointerException()
                    .isThrownBy(() -> new ReadResult(null, Outcome.OTHER, Optional.empty(),
                            Optional.empty()));
            assertThatNullPointerException()
                    .isThrownBy(() -> new ReadResult(DateParmReader.PERMANENT_ERROR_STATUS, null,
                            Optional.empty(), Optional.empty()));
            assertThatNullPointerException()
                    .isThrownBy(() -> new ReadResult(DateParmReader.PERMANENT_ERROR_STATUS,
                            Outcome.OTHER, null, Optional.empty()))
                    .withMessageContaining("empty range");
            assertThatNullPointerException()
                    .isThrownBy(() -> new ReadResult(DateParmReader.PERMANENT_ERROR_STATUS,
                            Outcome.OTHER, Optional.empty(), null))
                    .withMessageContaining("empty diagnostic");
            assertThatNullPointerException().isThrownBy(() -> ReadResult.found(null));
            assertThatNullPointerException()
                    .isThrownBy(() -> ReadResult.other(DateParmReader.PERMANENT_ERROR_STATUS, null))
                    .withMessageContaining("diagnostic is required");
        }

        @Test
        @DisplayName("the end-of-file arm is built without a range and reports the EOF APPL-RESULT")
        void theEndOfFileArmIsWellFormed() {
            ReadResult endOfFile = ReadResult.endOfFile();

            assertThat(endOfFile.isEndOfFile()).isTrue();
            assertThat(endOfFile.isFound()).isFalse();
            assertThat(endOfFile.isOther()).isFalse();
            assertThat(endOfFile.applResult()).isEqualTo(FileStatus.APPL_EOF);
        }
    }

    // =================================================================================================
    // The EVALUATE as a ladder, in source order - gate G30.
    //
    // app/cbl/CBTRN03C.cbl:L222-L229
    //     EVALUATE DATEPARM-STATUS
    //       WHEN '00'   MOVE  0 TO APPL-RESULT
    //       WHEN '10'   MOVE 16 TO APPL-RESULT
    //       WHEN OTHER  MOVE 12 TO APPL-RESULT
    //     END-EVALUATE
    //
    // EVALUATE is ordered: the first matching WHEN wins and WHEN OTHER is the default, so the arms are
    // asserted in that order and the catch-all is asserted last. The two 88-levels the guard chain then
    // tests are declared at L150-L152 - 88 APPL-AOK VALUE 0 and 88 APPL-EOF VALUE 16 - and gate G50 wants
    // both of them driven true and false, which the arm-by-arm predicate triple below does.
    // =================================================================================================

    @Nested
    @DisplayName("The ladder in source order - '00', then '10', then WHEN OTHER last")
    class TheLadderInSourceOrder {

        @Test
        @DisplayName("gate G30: the three arms are driven in EVALUATE source order, catch-all last")
        void theThreeArmsAreDrivenInSourceOrder() throws SQLException {
            // Driven through the reader itself, in the order the EVALUATE lists them, so the sequence
            // asserted below is the sequence the COBOL evaluates rather than an order chosen here.
            List<ReadResult> ladder = List.of(
                    new SeededDataset(RECORD).reader().read(),
                    new SeededDataset().reader().read(),
                    new SeededDataset("2022-01-01 2022-07").reader().read());

            assertThat(ladder).extracting(ReadResult::status)
                    .containsExactly(FileStatus.OK, FileStatus.END_OF_FILE,
                            DateParmReader.PERMANENT_ERROR_STATUS);
            assertThat(ladder).extracting(ReadResult::outcome)
                    .containsExactly(Outcome.OK, Outcome.END_OF_FILE, Outcome.OTHER);
            assertThat(ladder).extracting(ReadResult::applResult)
                    .as("MOVE 0, MOVE 16, MOVE 12 - the three APPL-RESULT values, in order")
                    .containsExactly(FileStatus.APPL_AOK, FileStatus.APPL_EOF,
                            DateParmReader.APPL_RESULT_FATAL);
            // The third arm is the catch-all, not a fourth named status: the status it carries is neither
            // of the two the EVALUATE names, and it classifies as WHEN OTHER.
            String catchAll = ladder.get(2).status();
            assertThat(FileStatus.isOk(catchAll)).isFalse();
            assertThat(FileStatus.isEndOfFile(catchAll)).isFalse();
            assertThat(FileStatus.outcomeOfStatus(catchAll)).isEqualTo(Outcome.OTHER);
        }

        @Test
        @DisplayName("gate G50: on every arm exactly one predicate is true, so both 88-levels see both states")
        void exactlyOnePredicateIsTrueOnEachArm() throws SQLException {
            List<ReadResult> ladder = List.of(
                    new SeededDataset(RECORD).reader().read(),
                    new SeededDataset().reader().read(),
                    new SeededDataset("2022-01-01 2022-07").reader().read());

            // IF APPL-AOK (L231) and IF APPL-EOF (L235) are the two tests the guard chain performs, and
            // each is exercised true once and false twice across the three arms.
            assertThat(ladder).extracting(ReadResult::isFound).containsExactly(true, false, false);
            assertThat(ladder).extracting(ReadResult::isEndOfFile).containsExactly(false, true, false);
            assertThat(ladder).extracting(ReadResult::isOther).containsExactly(false, false, true);
            for (ReadResult arm : ladder) {
                assertThat(List.of(arm.isFound(), arm.isEndOfFile(), arm.isOther())
                        .stream().filter(Boolean::booleanValue).count())
                        .withFailMessage("the arms are mutually exclusive; %s matched more or fewer "
                                + "than one", arm)
                        .isEqualTo(1L);
            }
        }

        @Test
        @DisplayName("WHEN '10' sets the mainline's end flag BEFORE the loop, so the report body is empty")
        void anEmptyDatasetEmptiesTheWholeReportBody() throws SQLException {
            // The consequence that is easy to miss and must survive intact (practice B5). 0550-DATEPARM-
            // READ is performed at L168; the report loop is PERFORM UNTIL END-OF-FILE = 'Y' at L170. So
            // when the read reports '10', L236 moves 'Y' to END-OF-FILE before the loop is ever entered,
            // and the loop body - read a transaction, filter it, total it, print it - runs zero times.
            // No page heading, no account total, no grand total: an empty report, reported as a success.
            ReadResult result = new SeededDataset().reader().read();

            assertThat(result.isEndOfFile()).isTrue();

            // The mainline, modelled exactly: the flag is set from the read outcome, then the loop is
            // entered - or, here, not entered.
            String endOfFile = result.isEndOfFile() ? "Y" : "N";
            int iterations = 0;
            while (!"Y".equals(endOfFile)) {
                iterations++;
                endOfFile = "Y";
            }

            assertThat(endOfFile).isEqualTo("Y");
            assertThat(iterations)
                    .as("PERFORM UNTIL END-OF-FILE = 'Y' at L170 never runs its body")
                    .isZero();
            // And none of the friendlier alternatives happened: no throw, no substituted default range.
            assertThat(result.dateParm()).isEmpty();
            assertThatCode(() -> new SeededDataset().reader().read()).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("the successful arm hands back the two DISPLAY operands untouched")
        void theSuccessfulArmSuppliesTheDisplayOperandsUntouched() throws SQLException {
            // The line itself - DISPLAY 'Reporting from ' WS-START-DATE ' to ' WS-END-DATE at L232-L233 -
            // is the report job's to emit, and its byte-exact literals are asserted in
            // TransactionReportJobTest so the job's whole SYSOUT fingerprint is compared in one place.
            // What belongs here is that the two OPERANDS that line interpolates come back at their exact
            // declared widths, in the right order, and with their padding: if they did not, the job would
            // emit a correctly-worded line with the wrong bytes in it.
            DateParm range = new SeededDataset(RECORD).reader().read().dateParm().orElseThrow();

            assertThat(range.startDate()).hasSize(DateParmReader.START_DATE_LENGTH);
            assertThat(range.endDate()).hasSize(DateParmReader.END_DATE_LENGTH);
            assertThat("Reporting from " + range.startDate() + " to " + range.endDate())
                    .isEqualTo("Reporting from 2022-01-01 to 2022-12-31");
        }

        @ParameterizedTest(name = "status [{0}] falls into WHEN OTHER and is carried verbatim")
        @ValueSource(strings = { "35", "37", "39", "41", "92", "99" })
        @DisplayName("gate G47: any other status reaches the abend arm carrying its own two characters")
        void anyOtherStatusIsCarriedVerbatimToTheCatchAll(String status) {
            // The ELSE of IF APPL-EOF at L237-L242 displays 'ERROR READING DATEPARM FILE', moves
            // DATEPARM-STATUS to IO-STATUS, renders it and abends - so the status must survive the trip
            // unaltered, or 9910-DISPLAY-IO-STATUS renders the wrong one.
            ReadResult result = ReadResult.other(status);

            assertThat(result.isOther()).isTrue();
            assertThat(result.status()).isEqualTo(status).hasSize(FileStatus.STATUS_LENGTH);
            assertThat(result.applResult()).isEqualTo(DateParmReader.APPL_RESULT_FATAL);
            assertThat(result.dateParm()).isEmpty();
        }

        @Test
        @DisplayName("the fatal APPL-RESULT is 12, and is neither 88-level value")
        void theFatalApplResultIsTwelveAndIsNeitherConditionName() {
            assertThat(DateParmReader.APPL_RESULT_FATAL).isEqualTo(12);
            assertThat(DateParmReader.APPL_RESULT_FATAL)
                    .as("88 APPL-AOK VALUE 0 and 88 APPL-EOF VALUE 16 - so 12 satisfies neither, which "
                            + "is what makes the ELSE arm at L237 reachable")
                    .isNotEqualTo(FileStatus.APPL_AOK)
                    .isNotEqualTo(FileStatus.APPL_EOF);
            assertThat(FileStatus.APPL_AOK).isZero();
            assertThat(FileStatus.APPL_EOF).isEqualTo(16);
        }
    }

    // =================================================================================================
    // The cross-component byte identity: the record this reader consumes is written by another program.
    //
    // app/cbl/CORPT00C.cbl builds a JCL skeleton as a table of eighty-byte lines - JOB-DATA-1 at L82,
    // redefined at L126-L127 as 05 JOB-LINES OCCURS 1000 TIMES PIC X(80) - and emits them one at a time
    // (L501). Line fourteen is the //STEP10R.DATEPARM DD * card at L116, and line fifteen, immediately
    // after it, is the record itself:
    //
    // app/cbl/CORPT00C.cbl:L117-L121
    //     05 FILLER-3.
    //        10 PARM-START-DATE-2       PIC X(10) VALUE SPACES.
    //        10 FILLER                  PIC X     VALUE SPACE.
    //        10 PARM-END-DATE-2         PIC X(10) VALUE SPACES.
    //        10 FILLER                  PIC X(59) VALUE SPACES.
    //
    // Ten, one, ten, fifty-nine - derived from the WRITER's copybook, and identical to the receiver this
    // reader decodes. ReportRequestController produces that record and DateParmReader consumes it, so the
    // two must never drift apart; the producing side is asserted in ReportRequestControllerTest and the
    // consuming side here, and this group is the seam where a divergence would surface.
    // =================================================================================================

    @Nested
    @DisplayName("Cross-component byte identity - CORPT00C writes exactly what this reader reads")
    class CrossComponentByteIdentity {

        /** {@code PARM-START-DATE-2 PIC X(10)} - app/cbl/CORPT00C.cbl:L118. */
        private static final int WRITER_START_DATE_WIDTH = 10;

        /** {@code FILLER PIC X VALUE SPACE} - app/cbl/CORPT00C.cbl:L119. */
        private static final int WRITER_SEPARATOR_WIDTH = 1;

        /** {@code PARM-END-DATE-2 PIC X(10)} - app/cbl/CORPT00C.cbl:L120. */
        private static final int WRITER_END_DATE_WIDTH = 10;

        /** {@code FILLER PIC X(59) VALUE SPACES} - app/cbl/CORPT00C.cbl:L121. */
        private static final int WRITER_TAIL_WIDTH = 59;

        @Test
        @DisplayName("a record shaped exactly like CORPT00C's line fifteen parses into the same two dates")
        void aRecordShapedLikeTheWritersLineFifteenIsParsed() {
            // Composed the way FILLER-3 composes it, span by span, using the two dates the estate itself
            // ships: app/jcl/TRANREPT.jcl:L43-L44 carries C'2022-01-01' and C'2022-07-06'.
            String asTheWriterEmitsIt = "2022-01-01"
                    + " ".repeat(WRITER_SEPARATOR_WIDTH)
                    + "2022-07-06"
                    + " ".repeat(WRITER_TAIL_WIDTH);
            DateParmReader reader = reader(mock(JdbcTemplate.class));

            assertThat(asTheWriterEmitsIt)
                    .as("JOB-LINES is PIC X(80), so the line the writer emits is eighty bytes")
                    .hasSize(DateParmReader.RECORD_LENGTH);

            DateParm range = reader.decode(asTheWriterEmitsIt);

            assertThat(range.startDate()).isEqualTo("2022-01-01");
            assertThat(range.separator())
                    .as("FILLER PIC X VALUE SPACE at L119 - a space, and surfaced as the byte it is")
                    .isEqualTo(" ");
            assertThat(range.endDate()).isEqualTo("2022-07-06");
        }

        @Test
        @DisplayName("the writer's four spans are the reader's four spans, span for span")
        void theWriterSpansAndTheReaderSpansAgree() {
            // The drift alarm. These four numbers are transcribed from the writer's copybook above; the
            // four they are compared against come from the reader. If either side is ever changed alone,
            // this fails before any report is produced with a shifted date in it.
            assertThat(WRITER_START_DATE_WIDTH).isEqualTo(DateParmReader.START_DATE_LENGTH);
            assertThat(WRITER_SEPARATOR_WIDTH).isEqualTo(DateParmReader.SEPARATOR_LENGTH);
            assertThat(WRITER_END_DATE_WIDTH).isEqualTo(DateParmReader.END_DATE_LENGTH);
            assertThat(WRITER_TAIL_WIDTH).isEqualTo(DateParmReader.DISCARDED_TAIL_LENGTH);
            assertThat(WRITER_START_DATE_WIDTH + WRITER_SEPARATOR_WIDTH + WRITER_END_DATE_WIDTH)
                    .isEqualTo(DateParmReader.RECEIVER_LENGTH);
            assertThat(WRITER_START_DATE_WIDTH + WRITER_SEPARATOR_WIDTH + WRITER_END_DATE_WIDTH
                    + WRITER_TAIL_WIDTH)
                    .as("10 + 1 + 10 + 59 = 80 on both sides of the queue")
                    .isEqualTo(DateParmReader.RECORD_LENGTH);
        }

        @Test
        @DisplayName("the writer-to-reader round trip survives the JDBC path with every byte intact")
        void theRoundTripFromWriterToReaderPreservesEveryByte() throws SQLException {
            String emitted = "2022-02-14" + " " + "2022-06-30" + " ".repeat(WRITER_TAIL_WIDTH);

            ReadResult result = new SeededDataset(emitted).reader().read();

            assertThat(result.isFound()).isTrue();
            assertThat(result.dateParm().orElseThrow().receiverImage())
                    .isEqualTo(emitted.substring(0, DateParmReader.RECEIVER_LENGTH));
        }

        @Test
        @DisplayName("the SYMNAMES line is a DIFFERENT consumer of the same two dates, and is not this input")
        void theSymnamesLineIsNotThisReadersInput() {
            // The trap this group exists to keep shut. CORPT00C populates the same two dates into TWO
            // places: skeleton lines eleven and twelve - FILLER-1 at L104-L107 and FILLER-2 at
            // L109-L112, shaped X(18) + X(10) + X(52) and X(16) + X(10) + X(54) - which become the
            // SYMNAMES cards at app/jcl/TRANREPT.jcl:L43-L44 configuring the DFSORT INCLUDE filter of the
            // SORT step, and line fifteen, which is the DATEPARM record. Only line fifteen is this
            // reader's input. Feeding it a SYMNAMES line does not fail - the record is eighty bytes and
            // the reader does not validate dates - it simply yields the wrong twenty-one bytes, which is
            // exactly why the two must never be conflated.
            String symnamesLine = "PARM-START-DATE,C'" + "2022-01-01" + "'";
            String paddedToTheLineWidth = symnamesLine
                    + " ".repeat(DateParmReader.RECORD_LENGTH - symnamesLine.length());
            DateParmReader reader = reader(mock(JdbcTemplate.class));

            assertThat(paddedToTheLineWidth).hasSize(DateParmReader.RECORD_LENGTH);

            DateParm misread = reader.decode(paddedToTheLineWidth);

            assertThat(misread.startDate())
                    .as("bytes 1-10 of a SYMNAMES card are its keyword, not a date")
                    .isEqualTo("PARM-START")
                    .isNotEqualTo("2022-01-01");
            assertThat(misread.endDate()).isNotEqualTo("2022-01-01");
        }
    }

    // =================================================================================================
    // Structure - gate G53. COBOL WORKING-STORAGE must never become static Java state: this reader is a
    // singleton every controller and job can share, so a mutable field would couple unrelated callers and
    // make a test's outcome depend on what ran before it.
    // =================================================================================================

    @Nested
    @DisplayName("Structure - no mutable state at all, static or instance (gate G53)")
    class Structure {

        @Test
        @DisplayName("every static field is final, so nothing global can be reassigned")
        void everyStaticFieldIsFinal() {
            for (Field field : DateParmReader.class.getDeclaredFields()) {
                if (!field.isSynthetic() && Modifier.isStatic(field.getModifiers())) {
                    assertThat(Modifier.isFinal(field.getModifiers()))
                            .withFailMessage("static field %s is not final", field.getName())
                            .isTrue();
                }
            }
        }

        @Test
        @DisplayName("every instance field is final, so the bean cannot be re-wired after construction")
        void everyInstanceFieldIsFinal() {
            for (Field field : DateParmReader.class.getDeclaredFields()) {
                if (!field.isSynthetic() && !Modifier.isStatic(field.getModifiers())) {
                    assertThat(Modifier.isFinal(field.getModifiers()))
                            .withFailMessage("instance field %s is not final", field.getName())
                            .isTrue();
                }
            }
        }

        @Test
        @DisplayName("there is one constructor, so wiring cannot be ambiguous and injection is explicit")
        void exactlyOneConstructorExists() {
            assertThat(DateParmReader.class.getDeclaredConstructors()).hasSize(1);
        }

        @Test
        @DisplayName("re-reading yields the same range: no cursor is kept between calls")
        void reReadingYieldsTheSameRangeBecauseNoCursorIsKept() throws SQLException {
            // CBTRN03C performs 0550-DATEPARM-READ exactly once (L168) and never resumes from a saved
            // position, so this reader keeps none. Two calls therefore see the same first record rather
            // than the second call seeing an exhausted cursor.
            SeededDataset dataset = new SeededDataset(RECORD);
            DateParmReader reader = dataset.reader();

            assertThat(reader.read().dateParm()).isEqualTo(reader.read().dateParm());
            assertThat(dataset.executedSql).containsExactly(SELECT_SQL, SELECT_SQL);
        }
    }
}
