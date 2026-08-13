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
import com.vsergeychik.carddemo.common.PhysicalSequence;
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
 */
@DisplayName("DateParmReader - the report date range, read from a dataset rather than a PARM")
class DateParmReaderTest {
    private static final Charset ASCII = StandardCharsets.US_ASCII;

    private static final PhysicalSequence ORDINAL = PhysicalSequence.of("_ROWID_");

    private static final String DSNAME = "TEST.CARDDEMO.DATEPARM";

    private static final String COLUMN = "PS_RECORD_IMAGE";

    private static final String DESCRIBE_SQL = "SELECT * FROM \"" + DSNAME + "\" WHERE 1 = 0";

    private static final String SELECT_SQL =
            "SELECT * FROM \"" + DSNAME + "\" ORDER BY _ROWID_ ASC";

    private static final String RECORD = "2022-01-01 2022-12-31" + " ".repeat(59);

    private static DatasetBindings bindings(String dsname, int recordLength) {
        DatasetBindings catalogue = new DatasetBindings();
        catalogue.put(DateParmReader.DD_NAME, new DatasetBinding(dsname, "ps", false, "FB", null,
                recordLength, null, null, null, null, null));
        return catalogue;
    }

    private static DatasetBindings validBindings() {
        return bindings(DSNAME, DateParmReader.RECORD_LENGTH);
    }

    private static void stubDescribe(JdbcTemplate jdbcTemplate) {
        when(jdbcTemplate.query(eq(DESCRIBE_SQL),
                ArgumentMatchers.<ResultSetExtractor<String>>any())).thenReturn(FileStatus.OK);
    }

    private static void stubRows(JdbcTemplate jdbcTemplate, List<String> rows) {
        stubRows(jdbcTemplate, rows, new ArrayList<>());
    }

    private static void stubRows(JdbcTemplate jdbcTemplate, List<String> rows,
            List<BoundedStatement> sink) {
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

    private record BoundedStatement(String sql, int maxRows, int fetchSize) {
        private static final int UNSET = -1;

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

    private static DateParmReader reader(JdbcTemplate jdbcTemplate) {
        stubDescribe(jdbcTemplate);
        return new DateParmReader(jdbcTemplate, validBindings(), ASCII, RecordImageForm.CHARACTER, ORDINAL);
    }

    private static String record(String startDate, String separator, String endDate, String tailFill) {
        return startDate + separator + endDate
                + tailFill.repeat(DateParmReader.DISCARDED_TAIL_LENGTH);
    }

    private static final class SeededDataset {
        private static final String NO_ROW_PREDICATE = "1 = 0";

        private final JdbcTemplate template;

        private final List<String> executedSql = new ArrayList<>();

        private final List<Integer> preparedMaxRows = new ArrayList<>();

        private final List<Integer> preparedFetchSizes = new ArrayList<>();

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
                return resultSetOver(sql.endsWith(NO_ROW_PREDICATE) ? List.of() : seeded);
            });
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
            when(resultSet.getString(DateParmReader.RECORD_IMAGE_COLUMN_INDEX))
                    .thenAnswer(invocation -> rows.get(cursor.get() - 1));
            return resultSet;
        }

        private DateParmReader reader() {
            return new DateParmReader(template, validBindings(), ASCII, RecordImageForm.CHARACTER, ORDINAL);
        }
    }

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
        @DisplayName("the read is ordered by the physical-record ordinal, because 0550-DATEPARM-READ "
                + "takes the FIRST record")
        void thePsReadIsOrderedByThePhysicalOrdinal() {
            assertThat(reader(mock(JdbcTemplate.class)).selectRecordSql())
                    .endsWith(" ORDER BY _ROWID_ ASC")
                    .doesNotContain("ORDER BY \"");
        }

        @Test
        @DisplayName("a deployment that renames its ordinal changes the statement and nothing else")
        void theOrdinalComesFromConfiguration() {
            DateParmReader reader = new DateParmReader(mock(JdbcTemplate.class), validBindings(), ASCII,
                    RecordImageForm.CHARACTER, PhysicalSequence.of("RRN"));

            assertThat(reader.selectRecordSql())
                    .isEqualTo("SELECT * FROM \"" + DSNAME + "\" ORDER BY RRN ASC");
        }

        @Test
        @DisplayName("every collaborator is required and none is defaulted")
        void everyCollaboratorIsRequired() {
            DatasetBindings catalogue = validBindings();

            assertThatNullPointerException()
                    .isThrownBy(() -> new DateParmReader(null, catalogue, ASCII, RecordImageForm.CHARACTER, ORDINAL))
                    .withMessageContaining("JdbcTemplate is required");
            assertThatNullPointerException()
                    .isThrownBy(() -> new DateParmReader(new JdbcTemplate(), null, ASCII, RecordImageForm.CHARACTER, ORDINAL))
                    .withMessageContaining("carddemo.datasets");
            assertThatNullPointerException()
                    .isThrownBy(() -> new DateParmReader(new JdbcTemplate(), catalogue, null, RecordImageForm.CHARACTER, ORDINAL))
                    .withMessageContaining("code page is stated explicitly");
            assertThatNullPointerException()
                    .isThrownBy(() -> new DateParmReader(new JdbcTemplate(), catalogue, ASCII,
                            RecordImageForm.CHARACTER, null))
                    .withMessageContaining(PhysicalSequence.EXPRESSION_PROPERTY);
        }

        @Test
        @DisplayName("a binding that disagrees with the copybook width is rejected, not accommodated")
        void aWidthDisagreementIsRejected() {
            DatasetBindings wrong = bindings(DSNAME, 79);

            assertThatIllegalStateException()
                    .isThrownBy(() -> new DateParmReader(new JdbcTemplate(), wrong, ASCII, RecordImageForm.CHARACTER, ORDINAL))
                    .withMessageContaining("record length of 79");
        }

        @ParameterizedTest(name = "an absent dataset name [{0}] is rejected")
        @CsvSource(value = { "NULL", "''", "'   '" }, nullValues = "NULL")
        @DisplayName("a dataset name the configuration never supplied is rejected")
        void anAbsentDatasetNameIsRejected(String dsname) {
            DatasetBindings absent = bindings(dsname, DateParmReader.RECORD_LENGTH);

            assertThatIllegalStateException()
                    .isThrownBy(() -> new DateParmReader(new JdbcTemplate(), absent, ASCII, RecordImageForm.CHARACTER, ORDINAL))
                    .withMessageContaining("carddemo.datasets." + DateParmReader.DD_NAME);
        }

        @ParameterizedTest(name = "a malformed dataset name [{0}] is rejected")
        @ValueSource(strings = { "BAD\tNAME", "TEST.\"ODD\".NAME", "TOOLONGQUALIFIER.X" })
        @DisplayName("a name that is not a well-formed z/OS dataset name never reaches a statement")
        void aMalformedDatasetNameIsRejected(String dsname) {
            DatasetBindings malformed = bindings(dsname, DateParmReader.RECORD_LENGTH);

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new DateParmReader(new JdbcTemplate(), malformed, ASCII, RecordImageForm.CHARACTER, ORDINAL))
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

    @Nested
    @DisplayName("The range is DATA read from a dataset - never a JobParameter, property or PARM")
    class RangeIsDataNotAParameter {
        private static final List<String> PARAMETER_MACHINERY = List.of(
                "JobParameter", "JobParameters", "JobExecution", "StepExecution", "StepContribution",
                "ChunkContext", "JobLauncher", "Environment", "PropertyResolver", "PropertySources",
                "ExpressionParser", "ApplicationContext", "BeanFactory");

        private static final List<String> BINDING_ANNOTATIONS = List.of(
                "Value", "StepScope", "JobScope", "ConfigurationProperties", "PropertySource",
                "Scheduled", "RequestParam");

        @Test
        @DisplayName("read() executes a statement naming the configured dataset, and that is its source")
        void theRangeIsObtainedByExecutingAStatementAgainstTheDataset() throws SQLException {
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
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            DateParmReader reader = new DateParmReader(jdbc, validBindings(), ASCII, RecordImageForm.CHARACTER, ORDINAL);
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
            DateParmReader reader = new DateParmReader(jdbc, validBindings(), ASCII, RecordImageForm.CHARACTER, ORDINAL);
            ResultSetMetaData metaData = mock(ResultSetMetaData.class);
            when(metaData.getColumnCount()).thenReturn(0);
            ResultSet described = mock(ResultSet.class);
            when(described.getMetaData()).thenReturn(metaData);
            when(jdbc.query(eq(DESCRIBE_SQL), ArgumentMatchers.<ResultSetExtractor<String>>any()))
                    .thenAnswer(invocation -> invocation
                            .<ResultSetExtractor<String>>getArgument(1)
                            .extractData(described));

            assertThat(reader.open()).isEqualTo(DateParmReader.PERMANENT_ERROR_STATUS);
        }

        @Test
        @DisplayName("a driver that supplies no metadata at all is unusable rather than successful")
        void noMetadataIsUnusable() throws SQLException {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            DateParmReader reader = new DateParmReader(jdbc, validBindings(), ASCII, RecordImageForm.CHARACTER, ORDINAL);
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
            DateParmReader reader = new DateParmReader(jdbc, validBindings(), ASCII, RecordImageForm.CHARACTER, ORDINAL);

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
            DateParmReader reader = new DateParmReader(jdbc, validBindings(), ASCII, RecordImageForm.CHARACTER, ORDINAL);

            assertThat(reader.open()).isEqualTo(DateParmReader.PERMANENT_ERROR_STATUS);
            assertThat(reader.close()).isEqualTo(DateParmReader.PERMANENT_ERROR_STATUS);
        }
    }

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
            when(jdbc.query(ArgumentMatchers.<PreparedStatementCreator>any(),
                    ArgumentMatchers.<RowMapper<byte[]>>any()))
                    .thenThrow(new DataAccessResourceFailureException("unreachable",
                            new SQLException("no route to host", "08001", 17_002)));

            ReadResult result = reader.read();

            assertThat(result.isOther()).isTrue();
            assertThat(result.status()).isEqualTo(DateParmReader.PERMANENT_ERROR_STATUS);
            assertThat(result.applResult()).isEqualTo(DateParmReader.APPL_RESULT_FATAL);
            assertThat(result.diagnostic()).isPresent();
            assertThat(result.diagnostic().orElseThrow().sqlState()).isEqualTo("08001");
            assertThat(result.diagnostic().orElseThrow().vendorCode()).isEqualTo(17_002);
            assertThat(result.diagnostic().orElseThrow().connectionFailure()).isTrue();
        }

        @Test
        @DisplayName("a template that yields no row list at all is the AT END it represents, not a throw")
        void aNullRowListIsTreatedAsAtEnd() {
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

        @Test
        @DisplayName("the statement is bounded to one row at the backend and at the fetch")
        void theStatementAsksForOneRowOnly() {
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
            assertThat(bounded.maxRows()).isEqualTo(1);
            assertThat(bounded.fetchSize()).isEqualTo(1);
        }

        @Test
        @DisplayName("bounding the read does not change which record it returns - the ordinal decides "
                + "that, and it is the only ORDER BY in the statement")
        void theBoundedStatementKeepsThePhysicalOrder() {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            DateParmReader reader = reader(jdbc);
            List<BoundedStatement> sent = new ArrayList<>();
            stubRows(jdbc, List.of(RECORD), sent);

            reader.read();

            assertThat(sent.get(0).sql())
                    .isEqualTo(SELECT_SQL)
                    .endsWith(" ORDER BY _ROWID_ ASC")
                    .doesNotContain("ORDER BY \"");
            assertThat(sent.get(0).sql().split(" ORDER BY ", -1)).hasSize(2);
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
            DateParmReader reader = reader(mock(JdbcTemplate.class));
            String truncated = "2022-01-01 2022-07";

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> reader.decode(truncated))
                    .withMessageContaining("stored record width in bytes = 18");
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

    @Nested
    @DisplayName("Byte offsets - 0..9, the single byte at 10, 11..20, and a tail that is thrown away")
    class ByteOffsets {
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

            assertThat(spans).hasSize(4);
            assertThat(spans).extracting(FieldSpan::offset).containsExactly(0, 10, 11, 21);
            assertThat(spans).extracting(FieldSpan::length).containsExactly(10, 1, 10, 59);
            assertThat(spans.stream().mapToInt(FieldSpan::length).sum())
                    .isEqualTo(DateParmReader.RECORD_LENGTH);
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

    @Nested
    @DisplayName("Untrimmed PIC X(10) characters - never a parsed date")
    class UntrimmedCharactersNotDates {
        @Test
        @DisplayName("an underfilled date keeps its trailing spaces: the span is padded to its width")
        void anUnderfilledDateKeepsItsPadding() {
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
            DateParmReader reader = reader(mock(JdbcTemplate.class));

            DateParm range = reader.decode(record(impossible, " ", impossible, " "));

            assertThat(range.startDate()).isEqualTo(impossible);
            assertThat(range.endDate()).isEqualTo(impossible);
        }

        @Test
        @DisplayName("the spans behave as the mainline's character comparison expects")
        void theSpansSupportTheCharacterComparisonTheMainlinePerforms() {
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
            DateParmReader reader = reader(mock(JdbcTemplate.class));

            assertThat(reader.decode(record("2022-7    ", " ", "2022-12   ", " ")).startDate())
                    .isEqualTo("2022-7    ");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> reader.decode("2022-01-01 2022-07-06"))
                    .withMessageContaining("80-into-21 move");
        }
    }

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

    @Nested
    @DisplayName("The ladder in source order - '00', then '10', then WHEN OTHER last")
    class TheLadderInSourceOrder {
        @Test
        @DisplayName("gate G30: the three arms are driven in EVALUATE source order, catch-all last")
        void theThreeArmsAreDrivenInSourceOrder() throws SQLException {
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
            ReadResult result = new SeededDataset().reader().read();

            assertThat(result.isEndOfFile()).isTrue();

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
            assertThat(result.dateParm()).isEmpty();
            assertThatCode(() -> new SeededDataset().reader().read()).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("the successful arm hands back the two DISPLAY operands untouched")
        void theSuccessfulArmSuppliesTheDisplayOperandsUntouched() throws SQLException {
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

    @Nested
    @DisplayName("Cross-component byte identity - CORPT00C writes exactly what this reader reads")
    class CrossComponentByteIdentity {
        private static final int WRITER_START_DATE_WIDTH = 10;

        private static final int WRITER_SEPARATOR_WIDTH = 1;

        private static final int WRITER_END_DATE_WIDTH = 10;

        private static final int WRITER_TAIL_WIDTH = 59;

        @Test
        @DisplayName("a record shaped exactly like CORPT00C's line fifteen parses into the same two dates")
        void aRecordShapedLikeTheWritersLineFifteenIsParsed() {
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
            SeededDataset dataset = new SeededDataset(RECORD);
            DateParmReader reader = dataset.reader();

            assertThat(reader.read().dateParm()).isEqualTo(reader.read().dateParm());
            assertThat(dataset.executedSql).containsExactly(SELECT_SQL, SELECT_SQL);
        }
    }
}
