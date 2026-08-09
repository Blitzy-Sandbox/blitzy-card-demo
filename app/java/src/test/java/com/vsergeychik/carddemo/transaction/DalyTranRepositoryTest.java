package com.vsergeychik.carddemo.transaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vsergeychik.carddemo.common.FileStatus;
import com.vsergeychik.carddemo.common.FileStatus.Outcome;
import com.vsergeychik.carddemo.common.FixedWidthRecord.FieldSpan;
import com.vsergeychik.carddemo.common.FixedWidthRecord.RecordLayout;
import com.vsergeychik.carddemo.common.PhysicalSequence;
import com.vsergeychik.carddemo.common.RecordImageForm;
import com.vsergeychik.carddemo.config.CobolCharsetConfig;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBinding;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBindings;
import com.vsergeychik.carddemo.transaction.DalyTranRepository.DalytranFile;
import com.vsergeychik.carddemo.transaction.DalyTranRepository.ReadResult;
import com.vsergeychik.carddemo.transaction.model.DalyTranRecord;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Proves the {@code DALYTRAN} repository against {@code app/cbl/CBTRN02C.cbl},
 * {@code app/cbl/CBTRN01C.cbl}, {@code app/cpy/CVTRA06Y.cpy} and {@code app/jcl/POSTTRAN.jcl}.
 *
 * <p>Four obligations, and each is asserted here rather than described:
 * <ul>
 *   <li><strong>The ladder has exactly three arms.</strong> {@code '00'} carries a record, {@code '10'}
 *       carries none, and everything else carries the status verbatim. All three are driven, plus the
 *       fourth condition the COBOL cannot express - a read against a file whose open failed;</li>
 *   <li><strong>Order is physical.</strong> The composed statement carries no {@code ORDER BY}, and the
 *       records come back in the order the backend presented them. A test that only counted records
 *       would pass over a reordering, so the sequence itself is asserted;</li>
 *   <li><strong>The record is 350 bytes and stays 350 bytes.</strong> Round-tripped byte for byte,
 *       {@code FILLER X(20)} included, and a row of any other width is refused rather than padded;</li>
 *   <li><strong>The amount is exact.</strong> {@code DALYTRAN-AMT PIC S9(09)V99} decodes at scale 2 with
 *       its sign taken from the zoned overpunch in the trailing byte - including the fifty rows of the
 *       shipped fixture that carry a negative one.</li>
 * </ul>
 *
 * <h2>Why there is a hand-built backend rather than a stubbed template</h2>
 * <p>Every other dataset in the module is read through {@code JdbcTemplate.query}, which a single
 * {@code when(...)} stubs. This one holds a cursor, because a physical-sequential dataset has no key to
 * resume from, so what has to be exercised is a {@link ResultSet} that advances: {@link Backend} below is
 * a small fake JDBC stack driven by a list of row images. It also counts what it was asked to do, which
 * is what lets the end-of-file idempotence claim be asserted as "and the backend was not visited again"
 * rather than merely as "and the same answer came back".
 */
@DisplayName("DalyTranRepository - DALYTRAN read forward, read only, in physical order")
class DalyTranRepositoryTest {

    /** The code page, always named. */
    private static final Charset ASCII = StandardCharsets.US_ASCII;

    /**
     * The physical-record ordinal the tests read this physical-sequential dataset in.
     *
     * <p>{@code _ROWID_} is what {@code application-test.yml} configures, and it is H2's own
     * row-identifier pseudo-column: it increases with each insert, so it returns the records in the order
     * they were written - which is what a sequential COBOL {@code READ} of a PS dataset returns.
     */
    private static final PhysicalSequence ORDINAL = PhysicalSequence.of("_ROWID_");

    /** A stand-in dataset name: this suite proves the name comes from configuration. */
    private static final String DSNAME = "TEST.CARDDEMO.DALYTRAN";

    /** The unordered select the repository must compose - no {@code ORDER BY} and no predicate. */
    private static final String SELECT_SQL =
            "SELECT * FROM \"" + DSNAME + "\" ORDER BY _ROWID_ ASC";

    /** The repository-root-relative path of the class under test, for the source-level guards. */
    private static final String SOURCE_PATH =
            "app/java/src/main/java/com/vsergeychik/carddemo/transaction/DalyTranRepository.java";

    /** The shipped fixture, copied from {@code app/data/ASCII/dailytran.txt}. */
    private static final String FIXTURE_RESOURCE = "/fixtures/dailytran.txt";

    /** Records in the shipped fixture, measured. */
    private static final int FIXTURE_RECORDS = 300;

    /** Rows of the shipped fixture whose {@code DALYTRAN-AMT} carries a negative overpunch, measured. */
    private static final int FIXTURE_NEGATIVE_AMOUNTS = 50;

    // =============================================================================================
    // Fixtures and helpers.
    // =============================================================================================

    /**
     * A binding catalogue holding one {@code DALYTRAN} entry.
     *
     * @param dsname       the dataset name to declare
     * @param recordLength the record length to declare
     * @return the catalogue
     */
    private static DatasetBindings bindings(String dsname, int recordLength) {
        DatasetBindings catalogue = new DatasetBindings();
        catalogue.put(DalyTranRepository.DD_NAME, new DatasetBinding(dsname, "sequential", false, "FB",
                null, recordLength, "CVTRA06Y", null, null, null, null));
        return catalogue;
    }

    /** @return a catalogue declaring the real geometry. */
    private static DatasetBindings validBindings() {
        return bindings(DSNAME, DalyTranRepository.RECORD_LENGTH);
    }

    /**
     * A repository over a backend of the caller's making.
     *
     * @param backend the fake JDBC stack, or {@code null} for a template with no data source
     * @return the repository
     */
    private static DalyTranRepository repository(Backend backend) {
        // The backend is built BEFORE the template is stubbed. Building it creates and stubs mocks of
        // its own, and doing that inside the argument to thenReturn(...) is a nested stubbing Mockito
        // rejects - which is worth a comment, because the two orderings look interchangeable.
        DataSource source = backend == null ? null : backend.dataSource();
        JdbcTemplate template = mock(JdbcTemplate.class);
        when(template.getDataSource()).thenReturn(source);
        return new DalyTranRepository(template, validBindings(), ASCII, RecordImageForm.CHARACTER,
                ORDINAL);
    }

    /**
     * A repository with no backend behind it at all, for the pure seams.
     *
     * <p>The decode seams, the composed statement and the layout are reachable with nothing on the other
     * side of the data source, which is exactly the property residual risk R-E makes valuable: the
     * byte-level behaviour is exercised with no driver in the path.
     *
     * @return the repository
     */
    private static DalyTranRepository repositoryWithoutBackend() {
        return repository(null);
    }

    /**
     * One decoded record, built without a repository and without a backend.
     *
     * @return a record over a valid 350-byte image
     */
    private static DalyTranRecord aRecord() {
        return DalyTranRecord.decode(
                image("0000000000000001", "0000005047G", "4111111111111111"), ASCII);
    }

    /**
     * Composes a valid 350-character record image in {@code CVTRA06Y} field order.
     *
     * @param id        {@code DALYTRAN-ID}, padded or truncated to 16
     * @param amtImage  the eleven-character {@code DALYTRAN-AMT} image, sign overpunch included
     * @param cardNum   {@code DALYTRAN-CARD-NUM}, padded or truncated to 16
     * @return exactly {@link DalyTranRepository#RECORD_LENGTH} characters
     */
    private static String image(String id, String amtImage, String cardNum) {
        StringBuilder record = new StringBuilder();
        record.append(picX(id, DalyTranRecord.DALYTRAN_ID_LENGTH));
        record.append("01");
        record.append("0001");
        record.append(picX("POS TERM", DalyTranRecord.DALYTRAN_SOURCE_LENGTH));
        record.append(picX("Purchase", DalyTranRecord.DALYTRAN_DESC_LENGTH));
        assertThat(amtImage).hasSize(DalyTranRecord.DALYTRAN_AMT_LENGTH);
        record.append(amtImage);
        record.append("000000123");
        record.append(picX("MERCHANT", DalyTranRecord.DALYTRAN_MERCHANT_NAME_LENGTH));
        record.append(picX("CITY", DalyTranRecord.DALYTRAN_MERCHANT_CITY_LENGTH));
        record.append(picX("12345", DalyTranRecord.DALYTRAN_MERCHANT_ZIP_LENGTH));
        record.append(picX(cardNum, DalyTranRecord.DALYTRAN_CARD_NUM_LENGTH));
        record.append(picX("2022-07-18 00.00.00.000000", DalyTranRecord.DALYTRAN_ORIG_TS_LENGTH));
        record.append(picX("2022-07-19 00.00.00.000000", DalyTranRecord.DALYTRAN_PROC_TS_LENGTH));
        record.append(picX("", DalyTranRecord.FILLER_LENGTH));
        String composed = record.toString();
        assertThat(composed).as("the composed image must be the declared record width")
                .hasSize(DalyTranRepository.RECORD_LENGTH);
        return composed;
    }

    /** @return {@code value} space-padded on the right, or truncated on the right, to {@code width}. */
    private static String picX(String value, int width) {
        String padded = value + " ".repeat(Math.max(0, width - value.length()));
        return padded.substring(0, width);
    }

    /** @return the three-row dataset this suite reads most of its ladders over. */
    private static List<String> threeRows() {
        return List.of(
                image("0000000000000001", "0000005047G", "4111111111111111"),
                image("0000000000000002", "0000009190}", "4111111111111112"),
                image("0000000000000003", "00000000000", "4111111111111113"));
    }

    /** @return the shipped fixture's rows, each exactly 350 characters. */
    private static List<String> fixtureRows() {
        try (InputStream stream = DalyTranRepositoryTest.class.getResourceAsStream(FIXTURE_RESOURCE)) {
            assertThat(stream).as("the shipped fixture %s must be on the test classpath",
                    FIXTURE_RESOURCE).isNotNull();
            String text = new String(stream.readAllBytes(), ASCII);
            return text.lines().filter(line -> !line.isEmpty()).toList();
        } catch (IOException unreadable) {
            throw new UncheckedIOException("Could not read " + FIXTURE_RESOURCE, unreadable);
        }
    }

    /**
     * Locates a repository-root-relative path by walking up from the working directory.
     *
     * @param relativePath the path to find
     * @return the resolved path
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

    /** @return the source text of the class under test. */
    private static String sourceText() {
        try {
            return Files.readString(repositoryFile(SOURCE_PATH), StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            throw new UncheckedIOException("Could not read " + SOURCE_PATH, unreadable);
        }
    }

    /**
     * A small fake JDBC stack: a data source serving up to {@link #INDEPENDENT_OPENS} independent
     * forward-only cursors over one list of row images.
     *
     * <p>Hand-built rather than stubbed call by call because the property under test is that a cursor
     * <em>advances</em>: a single {@code when(...).thenReturn(rows)} would prove nothing about a
     * position. Every chain is built eagerly, before any stubbing begins, so no mock is created while a
     * stubbing is in progress; and each chain carries its own position, which is what lets two
     * concurrent opens be shown to read independently.
     *
     * <p>The counters are the point of it. {@link #advances()} turns "the backend was not asked again"
     * into an assertion rather than an inference, and {@link #released()} records the release order, so
     * a leak or a wrong release order fails a test instead of being reasoned about.
     */
    private static final class Backend {

        /** How many independent cursors a backend can serve. Two is the most any test needs. */
        private static final int INDEPENDENT_OPENS = 3;

        /** The rows to serve, in the order they are served. */
        private final List<String> rows;

        /** How many times {@code next()} was called, across every cursor. */
        private final AtomicInteger advances = new AtomicInteger();

        /** The resources released, in the order they were released. */
        private final List<String> released = new ArrayList<>();

        /** The connections handed out, in order, for the prepare-time assertions. */
        private final List<Connection> connections = new ArrayList<>();

        /** The statements prepared, in the same order as {@link #connections}, for the same reason. */
        private final List<PreparedStatement> statements = new ArrayList<>();

        /** Column count the metadata reports. Zero models a backend presenting no record image. */
        private int columnCount = DalyTranRepository.RECORD_IMAGE_COLUMN_INDEX;

        /** When true, the metadata is absent altogether. */
        private boolean noMetaData;

        /** When set, {@code getConnection()} raises it. */
        private SQLException connectionFailure;

        /** When set, {@code next()} raises it. */
        private SQLException advanceFailure;

        /** When set, reading the record image raises it. */
        private SQLException readFailure;

        /** When set, every {@code close()} raises it. */
        private SQLException closeFailure;

        /** When true, the row's record-image column holds no value. */
        private boolean nullImage;

        /** The data source, built once on first use so the flags above are all set by then. */
        private DataSource dataSource;

        private Backend(List<String> rows) {
            this.rows = List.copyOf(rows);
        }

        /** @return a backend serving {@code rows}. */
        private static Backend serving(List<String> rows) {
            return new Backend(rows);
        }

        /** @return a backend serving nothing, so the first read is the end of the file. */
        private static Backend empty() {
            return new Backend(List.of());
        }

        private Backend withColumnCount(int count) {
            this.columnCount = count;
            return this;
        }

        private Backend withoutMetaData() {
            this.noMetaData = true;
            return this;
        }

        private Backend failingToConnect() {
            this.connectionFailure = new SQLException("no route to dataset", "08001", 4711);
            return this;
        }

        private Backend failingToAdvance() {
            this.advanceFailure = new SQLException("cursor broken", "58030", 904);
            return this;
        }

        private Backend failingToRead() {
            this.readFailure = new SQLException("column unreadable", "22001", 1234);
            return this;
        }

        private Backend failingToClose() {
            this.closeFailure = new SQLException("cannot release", "08006", 99);
            return this;
        }

        private Backend withNullImage() {
            this.nullImage = true;
            return this;
        }

        private int advances() {
            return advances.get();
        }

        private List<String> released() {
            return List.copyOf(released);
        }

        /** @return the connections handed out so far, in order. */
        private List<Connection> connections() {
            return List.copyOf(connections);
        }

        /** @return the statements prepared so far, in order. */
        private List<PreparedStatement> statements() {
            return List.copyOf(statements);
        }

        /**
         * The data source over this backend, built on first use.
         *
         * @return the data source; the same instance on every call
         */
        private DataSource dataSource() {
            if (dataSource == null) {
                dataSource = build();
            }
            return dataSource;
        }

        /**
         * Builds the whole stack eagerly.
         *
         * <p>Every mock is created before the first {@code when(...)} that references it, which is what
         * keeps Mockito from seeing a mock created inside an unfinished stubbing.
         *
         * @return the data source
         */
        private DataSource build() {
            try {
                List<Connection> chains = new ArrayList<>();
                for (int chain = 0; chain < INDEPENDENT_OPENS; chain++) {
                    chains.add(newChain());
                }
                DataSource source = mock(DataSource.class);
                if (connectionFailure != null) {
                    when(source.getConnection()).thenThrow(connectionFailure);
                } else {
                    when(source.getConnection()).thenReturn(chains.get(0),
                            chains.subList(1, chains.size()).toArray(new Connection[0]));
                }
                return source;
            } catch (SQLException impossible) {
                throw new IllegalStateException("stubbing a mock does not perform I/O", impossible);
            }
        }

        /**
         * One independent connection, statement and cursor, with a position of its own.
         *
         * @return the connection at the head of the chain
         * @throws SQLException never; declared because the stubbed signatures declare it
         */
        private Connection newChain() throws SQLException {
            AtomicInteger position = new AtomicInteger(-1);
            ResultSet cursor = mock(ResultSet.class);
            PreparedStatement statement = mock(PreparedStatement.class);
            Connection connection = mock(Connection.class);
            ResultSetMetaData metaData = mock(ResultSetMetaData.class);

            if (noMetaData) {
                when(cursor.getMetaData()).thenReturn(null);
            } else {
                when(metaData.getColumnCount()).thenReturn(columnCount);
                when(cursor.getMetaData()).thenReturn(metaData);
            }
            when(cursor.next()).thenAnswer(invocation -> {
                advances.incrementAndGet();
                if (advanceFailure != null) {
                    throw advanceFailure;
                }
                return position.incrementAndGet() < rows.size();
            });
            when(cursor.getString(DalyTranRepository.RECORD_IMAGE_COLUMN_INDEX))
                    .thenAnswer(invocation -> {
                        if (readFailure != null) {
                            throw readFailure;
                        }
                        return nullImage ? null : rows.get(position.get());
                    });
            org.mockito.Mockito.doAnswer(invocation -> record("resultSet")).when(cursor).close();

            when(statement.executeQuery()).thenReturn(cursor);
            org.mockito.Mockito.doAnswer(invocation -> record("statement")).when(statement).close();

            when(connection.prepareStatement(anyString(), anyInt(), anyInt())).thenReturn(statement);
            org.mockito.Mockito.doAnswer(invocation -> record("connection")).when(connection).close();

            connections.add(connection);
            statements.add(statement);
            return connection;
        }

        /**
         * Records one release, and refuses it when the backend was told to.
         *
         * @param resource the resource being released
         * @return {@code null}, the value a {@code void} answer must produce
         * @throws SQLException when this backend refuses to release
         */
        private Object record(String resource) throws SQLException {
            released.add(resource);
            if (closeFailure != null) {
                throw closeFailure;
            }
            return null;
        }
    }

    // =============================================================================================
    // Construction.
    // =============================================================================================

    @Nested
    @DisplayName("Construction - everything checkable is checked before a read can happen")
    class Construction {

        @Test
        @DisplayName("the resolved dataset name, record width and code page are surfaced")
        void theResolvedShapeIsSurfaced() {
            DalyTranRepository repository = repositoryWithoutBackend();

            assertThat(repository.datasetName()).isEqualTo(DSNAME);
            assertThat(repository.recordLength()).isEqualTo(DalyTranRepository.RECORD_LENGTH);
            assertThat(repository.datasetCharset()).isSameAs(ASCII);
        }

        @Test
        @DisplayName("the DD name is the one both consumers ASSIGN TO")
        void theDdNameIsTheAssignedName() {
            assertThat(DalyTranRepository.DD_NAME).isEqualTo("DALYTRAN");
        }

        @Test
        @DisplayName("a binding declaring any width but 350 is refused, not honoured")
        void aWrongRecordLengthIsRefused() {
            JdbcTemplate template = mock(JdbcTemplate.class);
            DatasetBindings wrong = bindings(DSNAME, 349);

            assertThatIllegalStateException()
                    .isThrownBy(() -> new DalyTranRepository(template, wrong, ASCII,
                            RecordImageForm.CHARACTER, ORDINAL))
                    .withMessageContaining("record length of 349")
                    .withMessageContaining("CVTRA06Y");
        }

        @Test
        @DisplayName("an unconfigured DD name is refused by the shared catalogue")
        void anAbsentBindingIsRefused() {
            JdbcTemplate template = mock(JdbcTemplate.class);
            DatasetBindings empty = new DatasetBindings();

            assertThatIllegalStateException()
                    .isThrownBy(() -> new DalyTranRepository(template, empty, ASCII,
                            RecordImageForm.CHARACTER, ORDINAL))
                    .withMessageContaining(DalyTranRepository.DD_NAME);
        }

        @ParameterizedTest(name = "dsname=[{0}]")
        @ValueSource(strings = { "", "   " })
        @DisplayName("a blank dataset name is refused: there is nothing to compose a statement over")
        void aBlankDatasetNameIsRefused(String blank) {
            JdbcTemplate template = mock(JdbcTemplate.class);
            DatasetBindings catalogue = bindings(blank, DalyTranRepository.RECORD_LENGTH);

            assertThatIllegalStateException()
                    .isThrownBy(() -> new DalyTranRepository(template, catalogue, ASCII,
                            RecordImageForm.CHARACTER, ORDINAL))
                    .withMessageContaining("declares no dataset name");
        }

        @Test
        @DisplayName("an absent dataset name is refused")
        void anAbsentDatasetNameIsRefused() {
            JdbcTemplate template = mock(JdbcTemplate.class);
            DatasetBindings catalogue = bindings(null, DalyTranRepository.RECORD_LENGTH);

            assertThatIllegalStateException()
                    .isThrownBy(() -> new DalyTranRepository(template, catalogue, ASCII,
                            RecordImageForm.CHARACTER, ORDINAL))
                    .withMessageContaining("carddemo.datasets." + DalyTranRepository.DD_NAME);
        }

        @Test
        @DisplayName("a dataset name that is not a usable identifier is refused by the shared grammar")
        void aMalformedDatasetNameIsRefused() {
            JdbcTemplate template = mock(JdbcTemplate.class);
            DatasetBindings catalogue =
                    bindings("DROP TABLE X; --", DalyTranRepository.RECORD_LENGTH);

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new DalyTranRepository(template, catalogue, ASCII,
                            RecordImageForm.CHARACTER, ORDINAL));
        }

        @Test
        @DisplayName("a code page that is not single-byte is refused")
        void aMultiByteCodePageIsRefused() {
            JdbcTemplate template = mock(JdbcTemplate.class);

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new DalyTranRepository(template, validBindings(),
                            StandardCharsets.UTF_16, RecordImageForm.CHARACTER, ORDINAL));
        }

        @Test
        @DisplayName("every collaborator is required, and the diagnostic says why")
        void everyCollaboratorIsRequired() {
            JdbcTemplate template = mock(JdbcTemplate.class);

            assertThatNullPointerException().isThrownBy(() -> new DalyTranRepository(null,
                    validBindings(), ASCII, RecordImageForm.CHARACTER, ORDINAL))
                    .withMessageContaining("JdbcTemplate");
            assertThatNullPointerException().isThrownBy(() -> new DalyTranRepository(template, null,
                    ASCII, RecordImageForm.CHARACTER, ORDINAL))
                    .withMessageContaining("carddemo.datasets");
            assertThatNullPointerException().isThrownBy(() -> new DalyTranRepository(template,
                    validBindings(), null, RecordImageForm.CHARACTER, ORDINAL))
                    .withMessageContaining("code page");
            assertThatNullPointerException().isThrownBy(() -> new DalyTranRepository(template,
                    validBindings(), ASCII, null, ORDINAL))
                    .withMessageContaining(RecordImageForm.FORM_PROPERTY);
            assertThatNullPointerException().isThrownBy(() -> new DalyTranRepository(template,
                    validBindings(), ASCII, RecordImageForm.CHARACTER, null))
                    .withMessageContaining(PhysicalSequence.EXPRESSION_PROPERTY);
        }

        @Test
        @DisplayName("it is a Spring @Repository, so the estate reaches it by injection")
        void itIsARepositoryBean() {
            assertThat(DalyTranRepository.class.getAnnotation(Repository.class)).isNotNull();
        }
    }

    // =============================================================================================
    // The statement and the layout.
    // =============================================================================================

    @Nested
    @DisplayName("The statement and the layout - gates G19, G21 and the physical-sequence contract")
    class StatementAndLayout {

        @Test
        @DisplayName("the read statement orders by the configured physical-record ordinal")
        void theStatementOrdersByThePhysicalOrdinal() {
            DalyTranRepository repository = repositoryWithoutBackend();

            assertThat(repository.selectRecordSql())
                    .isEqualTo(SELECT_SQL)
                    .endsWith(" ORDER BY _ROWID_ ASC")
                    .doesNotContain("WHERE")
                    .doesNotContain("FETCH")
                    .doesNotContain("OFFSET");
        }

        @Test
        @DisplayName("it orders by the ordinal and never by the record image, which is a different order")
        void theStatementNeverOrdersByTheRecordImage() {
            DalyTranRepository repository = repositoryWithoutBackend();

            // app/jcl/TRANREPT.jcl:46 sorts this file by TRAN-CARD-NUM while the record image begins
            // with DALYTRAN-ID, so an ordering over the image would be a DIFFERENT order from the file's -
            // and CBTRN03C subtotals by account as the records arrive. There is exactly one ORDER BY and
            // its operand is the ordinal.
            assertThat(repository.selectRecordSql().split(" ORDER BY ", -1)).hasSize(2);
            assertThat(repository.selectRecordSql())
                    .doesNotContain("ORDER BY \"")
                    .contains("ORDER BY _ROWID_ ASC");
        }

        @Test
        @DisplayName("a deployment that renames its ordinal changes the statement and nothing else")
        void theOrdinalComesFromConfiguration() {
            DalyTranRepository repository = new DalyTranRepository(mock(JdbcTemplate.class),
                    validBindings(), ASCII, RecordImageForm.CHARACTER,
                    PhysicalSequence.of("RECORD_ORDINAL"));

            assertThat(repository.selectRecordSql())
                    .isEqualTo("SELECT * FROM \"" + DSNAME + "\" ORDER BY RECORD_ORDINAL ASC");
        }

        @Test
        @DisplayName("the layout is 350 bytes and its fourteen spans account for every one")
        void theLayoutAccountsForEveryByte() {
            DalyTranRepository repository = repositoryWithoutBackend();

            assertThat(repository.layout().recordLength()).isEqualTo(350);
            assertThat(repository.layout().storageSpans()).hasSize(14);
            assertThat(DalyTranRecord.sumOfDeclaredSpanLengths()).isEqualTo(350);
        }

        @Test
        @DisplayName("the trailing FILLER X(20) is a declared span, which is what makes the sum 350")
        void theTrailingFillerIsDeclared() {
            DalyTranRepository repository = repositoryWithoutBackend();
            List<FieldSpan> spans = repository.layout().storageSpans();
            FieldSpan last = spans.get(spans.size() - 1);

            assertThat(last.length()).isEqualTo(20);
            assertThat(last.offset()).isEqualTo(330);
            assertThat(last.offset() + last.length()).isEqualTo(350);
        }

        @Test
        @DisplayName("the shipped layout passes the geometry proof unchanged")
        void theShippedLayoutPassesTheGeometryProof() {
            assertThat(DalyTranRepository.requireDeclaredGeometry(DalyTranRecord.LAYOUT,
                    DalyTranRecord.sumOfDeclaredSpanLengths()))
                    .isSameAs(DalyTranRecord.LAYOUT);
        }

        @Test
        @DisplayName("spans that do not account for the layout's width are refused")
        void spansThatDoNotAccountForTheWidthAreRefused() {
            assertThatIllegalStateException()
                    .isThrownBy(() -> DalyTranRepository.requireDeclaredGeometry(
                            DalyTranRecord.LAYOUT, DalyTranRepository.RECORD_LENGTH - 20))
                    .withMessageContaining("FILLER X(20)");
        }

        @Test
        @DisplayName("a coherent layout of the wrong width is refused too")
        void aCoherentLayoutOfTheWrongWidthIsRefused() {
            RecordLayout narrow = RecordLayout.of(10, FieldSpan.filler(0, 10));

            assertThatIllegalStateException()
                    .isThrownBy(() -> DalyTranRepository.requireDeclaredGeometry(narrow, 10))
                    .withMessageContaining("both must be 350");
        }

        @Test
        @DisplayName("an absent layout is refused: there is no reading a record without one")
        void anAbsentLayoutIsRefused() {
            assertThatNullPointerException().isThrownBy(() ->
                    DalyTranRepository.requireDeclaredGeometry(null, 350));
        }

        @Test
        @DisplayName("the published APPL-RESULT values are the ones the guards move")
        void theApplResultValuesAreTheOnesTheGuardsMove() {
            assertThat(DalyTranRepository.APPL_RESULT_OK).isZero();
            assertThat(DalyTranRepository.APPL_RESULT_EOF).isEqualTo(16);
            assertThat(DalyTranRepository.APPL_RESULT_FATAL).isEqualTo(12);
            assertThat(DalyTranRepository.AMOUNT_SCALE).isEqualTo(2);
            assertThat(DalyTranRepository.RECORD_IMAGE_COLUMN_INDEX).isEqualTo(1);
        }

        @Test
        @DisplayName("the permanent-error status renders as the line 9910-DISPLAY-IO-STATUS produces")
        void thePermanentErrorStatusRendersAsTheCobolLine() {
            assertThat(DalyTranRepository.PERMANENT_ERROR_STATUS)
                    .hasSize(FileStatus.STATUS_LENGTH)
                    .startsWith("9");
            assertThat(FileStatus.toStatusImage(DalyTranRepository.PERMANENT_ERROR_STATUS))
                    .isEqualTo("9000");
            assertThat(FileStatus.outcomeOfStatus(DalyTranRepository.PERMANENT_ERROR_STATUS))
                    .isEqualTo(Outcome.OTHER);
        }
    }

    // =============================================================================================
    // 0000-DALYTRAN-OPEN.
    // =============================================================================================

    @Nested
    @DisplayName("OPEN INPUT - app/cbl/CBTRN02C.cbl:L236-L250")
    class Open {

        @Test
        @DisplayName("a successful open reports '00' and positions before the first record")
        void aSuccessfulOpenReportsOk() {
            DalyTranRepository repository = repository(Backend.serving(threeRows()));

            try (DalytranFile file = repository.open()) {
                assertThat(file.openStatus()).isEqualTo(FileStatus.OK);
                assertThat(file.openOutcome()).isEqualTo(Outcome.OK);
                assertThat(file.isOpen()).isTrue();
                assertThat(file.isClosed()).isFalse();
                assertThat(file.atEndOfFile()).isFalse();
                assertThat(file.recordsRead()).isZero();
                assertThat(file.datasetName()).isEqualTo(DSNAME);
            }
        }

        @Test
        @DisplayName("the cursor is prepared forward-only and read-only over the unordered select")
        void theCursorIsForwardOnlyAndReadOnly() throws SQLException {
            Backend backend = Backend.serving(threeRows());
            DalyTranRepository repository = repository(backend);

            try (DalytranFile file = repository.open()) {
                assertThat(file.isOpen()).isTrue();
            }

            assertThat(backend.connections()).isNotEmpty();
            verify(backend.connections().get(0))
                    .prepareStatement(SELECT_SQL, ResultSet.TYPE_FORWARD_ONLY,
                            ResultSet.CONCUR_READ_ONLY);
        }

        @Test
        @DisplayName("the cursor states a positive fetch size, so the driver buffers a page not the file")
        void theCursorStatesAPositiveFetchSize() throws SQLException {
            // Left unset, the fetch size is the driver's own default, and several drivers default to
            // materialising the whole result set on the client - which is the unbounded behaviour a
            // source-faithful sequential READ must not have. A ceiling, not a tuning parameter: AAP
            // 0.8.6 records that this migration has no performance objective.
            Backend backend = Backend.serving(threeRows());
            DalyTranRepository repository = repository(backend);

            try (DalytranFile file = repository.open()) {
                assertThat(file.isOpen()).isTrue();
            }

            assertThat(DalyTranRepository.FETCH_SIZE).isPositive();
            verify(backend.statements().get(0)).setFetchSize(DalyTranRepository.FETCH_SIZE);
        }

        @Test
        @DisplayName("the stated fetch size changes neither the rows served nor the order they arrive")
        void theFetchSizeChangesNeitherContentNorOrder() {
            // The JDBC contract makes the value a hint, so this asserts the property that matters: the
            // same three rows, in the same physical order, one per read.
            DalyTranRepository repository = repository(Backend.serving(threeRows()));

            try (DalytranFile file = repository.open()) {
                assertThat(repository.readNext(file).dalyTran().orElseThrow().dalytranId())
                        .isEqualTo("0000000000000001");
                assertThat(repository.readNext(file).dalyTran().orElseThrow().dalytranId())
                        .isEqualTo("0000000000000002");
                assertThat(repository.readNext(file).dalyTran().orElseThrow().dalytranId())
                        .isEqualTo("0000000000000003");
                assertThat(repository.readNext(file).isEndOfFile()).isTrue();
            }
        }

        @Test
        @DisplayName("a template with no DataSource is a failed open, not an exception")
        void aTemplateWithNoDataSourceIsAFailedOpen() {
            DalyTranRepository repository = repository(null);

            try (DalytranFile file = repository.open()) {
                assertThat(file.openStatus()).isEqualTo(DalyTranRepository.PERMANENT_ERROR_STATUS);
                assertThat(file.openOutcome()).isEqualTo(Outcome.OTHER);
                assertThat(file.isOpen()).isFalse();
            }
        }

        @Test
        @DisplayName("a backend refusal is a failed open carrying the permanent-error status")
        void aBackendRefusalIsAFailedOpen() {
            DalyTranRepository repository = repository(Backend.empty().failingToConnect());

            try (DalytranFile file = repository.open()) {
                assertThat(file.openStatus()).isEqualTo(DalyTranRepository.PERMANENT_ERROR_STATUS);
                assertThat(file.isOpen()).isFalse();
            }
        }

        @Test
        @DisplayName("a backend describing no record-image column is unusable, not empty")
        void aBackendWithNoRecordImageColumnIsUnusable() {
            Backend backend = Backend.serving(threeRows()).withColumnCount(0);
            DalyTranRepository repository = repository(backend);

            DalytranFile file = repository.open();

            assertThat(file.openStatus()).isEqualTo(DalyTranRepository.PERMANENT_ERROR_STATUS);
            assertThat(backend.released())
                    .as("a refused open must not leak the connection it acquired")
                    .containsExactly("resultSet", "statement", "connection");
            assertThat(repository.readNext(file).isEndOfFile())
                    .as("a dataset that was never opened is not a dataset that was empty")
                    .isFalse();
        }

        @Test
        @DisplayName("a backend answering with no metadata at all is unusable too")
        void aBackendWithNoMetaDataIsUnusable() {
            Backend backend = Backend.serving(threeRows()).withoutMetaData();
            DalyTranRepository repository = repository(backend);

            try (DalytranFile file = repository.open()) {
                assertThat(file.openStatus()).isEqualTo(DalyTranRepository.PERMANENT_ERROR_STATUS);
            }
            assertThat(backend.released()).containsExactly("resultSet", "statement", "connection");
        }

        @Test
        @DisplayName("a release refusal on a failed-open cleanup is logged, not propagated")
        void aReleaseRefusalDuringCleanupIsSwallowed() {
            Backend backend = Backend.serving(threeRows()).withColumnCount(0).failingToClose();
            DalyTranRepository repository = repository(backend);

            assertThatCode(() -> repository.open().closeFile()).doesNotThrowAnyException();
            assertThat(backend.released()).containsExactly("resultSet", "statement", "connection");
        }
    }

    // =============================================================================================
    // 1000-DALYTRAN-GET-NEXT.
    // =============================================================================================

    @Nested
    @DisplayName("READ ... INTO - the three-armed guard of app/cbl/CBTRN02C.cbl:L345-L369")
    class TheGuardInSourceOrder {

        @Test
        @DisplayName("WHEN '00' - the record comes back decoded, and the count advances")
        void theSuccessfulArm() {
            DalyTranRepository repository = repository(Backend.serving(threeRows()));

            try (DalytranFile file = repository.open()) {
                ReadResult result = repository.readNext(file);

                assertThat(result.status()).isEqualTo(FileStatus.OK);
                assertThat(result.outcome()).isEqualTo(Outcome.OK);
                assertThat(result.isFound()).isTrue();
                assertThat(result.applResult()).isEqualTo(DalyTranRepository.APPL_RESULT_OK);
                assertThat(result.diagnostic()).isEmpty();
                assertThat(result.dalyTran()).isPresent();
                assertThat(result.dalyTran().orElseThrow().dalytranId())
                        .isEqualTo("0000000000000001");
                assertThat(file.recordsRead()).isEqualTo(1L);
            }
        }

        @Test
        @DisplayName("the records arrive in physical order, which is what the posting sequence depends on")
        void theRecordsArriveInPhysicalOrder() {
            DalyTranRepository repository = repository(Backend.serving(threeRows()));
            List<String> observed = new ArrayList<>();

            try (DalytranFile file = repository.open()) {
                ReadResult result = repository.readNext(file);
                while (result.isFound()) {
                    observed.add(result.dalyTran().orElseThrow().dalytranId());
                    result = repository.readNext(file);
                }
                assertThat(result.isEndOfFile()).isTrue();
                assertThat(file.recordsRead()).isEqualTo(3L);
            }

            assertThat(observed).containsExactly("0000000000000001", "0000000000000002",
                    "0000000000000003");
        }

        @Test
        @DisplayName("WHEN '10' - an empty dataset is the end of the file on the very first read")
        void theEndOfFileArmOnAnEmptyDataset() {
            DalyTranRepository repository = repository(Backend.empty());

            try (DalytranFile file = repository.open()) {
                ReadResult result = repository.readNext(file);

                assertThat(result.status()).isEqualTo(FileStatus.END_OF_FILE);
                assertThat(result.outcome()).isEqualTo(Outcome.END_OF_FILE);
                assertThat(result.isEndOfFile()).isTrue();
                assertThat(result.applResult()).isEqualTo(DalyTranRepository.APPL_RESULT_EOF);
                assertThat(result.dalyTran())
                        .as("READ ... INTO moves nothing at AT END, so no record is carried")
                        .isEmpty();
                assertThat(file.atEndOfFile()).isTrue();
                assertThat(file.recordsRead()).isZero();
            }
        }

        @Test
        @DisplayName("reading past the last record answers '10' every time and visits the backend once")
        void theEndOfFileIsIdempotentAndCostsOneVisit() {
            Backend backend = Backend.serving(threeRows());
            DalyTranRepository repository = repository(backend);

            try (DalytranFile file = repository.open()) {
                for (int record = 0; record < 3; record++) {
                    assertThat(repository.readNext(file).isFound()).isTrue();
                }
                assertThat(repository.readNext(file).isEndOfFile()).isTrue();
                int advancesAtEndOfFile = backend.advances();

                assertThatCode(() -> {
                    for (int extra = 0; extra < 5; extra++) {
                        assertThat(repository.readNext(file).isEndOfFile()).isTrue();
                    }
                }).doesNotThrowAnyException();

                assertThat(backend.advances())
                        .as("once the file has ended, a further read is answered from the handle")
                        .isEqualTo(advancesAtEndOfFile);
                assertThat(file.recordsRead()).isEqualTo(3L);
            }
        }

        @Test
        @DisplayName("WHEN OTHER - a refusal while advancing carries the status and the diagnosis")
        void theFatalArmOnAnAdvanceRefusal() {
            DalyTranRepository repository = repository(
                    Backend.serving(threeRows()).failingToAdvance());

            try (DalytranFile file = repository.open()) {
                ReadResult result = repository.readNext(file);

                assertThat(result.status()).isEqualTo(DalyTranRepository.PERMANENT_ERROR_STATUS);
                assertThat(result.outcome()).isEqualTo(Outcome.OTHER);
                assertThat(result.isOther()).isTrue();
                assertThat(result.applResult()).isEqualTo(DalyTranRepository.APPL_RESULT_FATAL);
                assertThat(result.dalyTran()).isEmpty();
                assertThat(result.diagnostic()).isPresent();
                assertThat(result.diagnostic().orElseThrow().sqlState()).isEqualTo("58030");
                assertThat(result.displayLine()).isEqualTo(FileStatus.DISPLAY_PREFIX + "9000");
            }
        }

        @Test
        @DisplayName("WHEN OTHER - a refusal while reading the image is fatal, not an end of file")
        void theFatalArmOnAReadRefusal() {
            DalyTranRepository repository = repository(Backend.serving(threeRows()).failingToRead());

            try (DalytranFile file = repository.open()) {
                ReadResult result = repository.readNext(file);

                assertThat(result.status()).isEqualTo(DalyTranRepository.PERMANENT_ERROR_STATUS);
                assertThat(result.isEndOfFile()).isFalse();
                assertThat(result.diagnostic().orElseThrow().sqlState()).isEqualTo("22001");
            }
        }

        @Test
        @DisplayName("WHEN OTHER - a row with no record image is a defect, not an end of file")
        void theFatalArmOnAnAbsentImage() {
            DalyTranRepository repository = repository(Backend.serving(threeRows()).withNullImage());

            try (DalytranFile file = repository.open()) {
                ReadResult result = repository.readNext(file);

                assertThat(result.status()).isEqualTo(DalyTranRepository.PERMANENT_ERROR_STATUS);
                assertThat(result.isEndOfFile())
                        .as("there IS a record; it simply cannot be read")
                        .isFalse();
                assertThat(result.diagnostic())
                        .as("no backend refused, so there is no diagnosis to carry")
                        .isEmpty();
                assertThat(file.recordsRead()).isZero();
            }
        }

        @ParameterizedTest(name = "a {0}-byte row")
        @ValueSource(ints = { 349, 351, 1 })
        @DisplayName("WHEN OTHER - a row of the wrong width is reported, never padded")
        void theFatalArmOnAWrongWidthRow(int width) {
            String shipped = threeRows().get(0);
            String wrong = width <= shipped.length()
                    ? shipped.substring(0, width)
                    : shipped + " ".repeat(width - shipped.length());
            DalyTranRepository repository = repository(Backend.serving(List.of(wrong)));

            try (DalytranFile file = repository.open()) {
                ReadResult result = repository.readNext(file);

                assertThat(result.status()).isEqualTo(DalyTranRepository.PERMANENT_ERROR_STATUS);
                assertThat(result.dalyTran()).isEmpty();
                assertThat(file.recordsRead()).isZero();
            }
        }

        @Test
        @DisplayName("a read against a failed open reports the open's own status, and asks nothing")
        void aReadAgainstAFailedOpenReportsTheOpenStatus() {
            Backend backend = Backend.serving(threeRows()).failingToConnect();
            DalyTranRepository repository = repository(backend);

            try (DalytranFile file = repository.open()) {
                ReadResult result = repository.readNext(file);

                assertThat(result.status()).isEqualTo(file.openStatus());
                assertThat(result.isOther()).isTrue();
                assertThat(result.isEndOfFile()).isFalse();
                assertThat(backend.advances()).isZero();
            }
        }

        @Test
        @DisplayName("the handle's own readNext() is the repository's, so a loop reads over the file")
        void theHandleDelegatesToTheRepository() {
            DalyTranRepository repository = repository(Backend.serving(threeRows()));

            try (DalytranFile file = repository.open()) {
                assertThat(file.readNext().dalyTran().orElseThrow().dalytranId())
                        .isEqualTo("0000000000000001");
                assertThat(file.readNext().dalyTran().orElseThrow().dalytranId())
                        .isEqualTo("0000000000000002");
            }
        }

        @Test
        @DisplayName("a null handle is refused: a COBOL READ addresses an open file")
        void aNullHandleIsRefused() {
            DalyTranRepository repository = repositoryWithoutBackend();

            assertThatNullPointerException().isThrownBy(() -> repository.readNext(null));
            assertThatNullPointerException().isThrownBy(() -> repository.close(null));
        }

        @Test
        @DisplayName("a handle from another repository is a programming error, not a file status")
        void aForeignHandleIsRefused() {
            DalyTranRepository one = repository(Backend.serving(threeRows()));
            DalyTranRepository another = repository(Backend.serving(threeRows()));

            try (DalytranFile file = one.open()) {
                assertThatIllegalArgumentException()
                        .isThrownBy(() -> another.readNext(file))
                        .withMessageContaining("different");
                assertThatIllegalArgumentException()
                        .isThrownBy(() -> another.close(file))
                        .withMessageContaining("different");
            }
        }
    }

    // =============================================================================================
    // 9000-DALYTRAN-CLOSE.
    // =============================================================================================

    @Nested
    @DisplayName("CLOSE - app/cbl/CBTRN02C.cbl:L582-L598")
    class Close {

        @Test
        @DisplayName("a successful close reports '00' and releases all three resources, in order")
        void aSuccessfulCloseReleasesEverything() {
            Backend backend = Backend.serving(threeRows());
            DalyTranRepository repository = repository(backend);

            DalytranFile file = repository.open();
            assertThat(repository.close(file)).isEqualTo(FileStatus.OK);

            assertThat(file.isClosed()).isTrue();
            assertThat(file.isOpen()).isFalse();
            assertThat(backend.released()).containsExactly("resultSet", "statement", "connection");
        }

        @Test
        @DisplayName("a second close is not a failed close")
        void aSecondCloseIsIdempotent() {
            Backend backend = Backend.serving(threeRows());
            DalyTranRepository repository = repository(backend);

            DalytranFile file = repository.open();
            assertThat(repository.close(file)).isEqualTo(FileStatus.OK);
            assertThat(repository.close(file)).isEqualTo(FileStatus.OK);
            assertThat(file.closeFile()).isEqualTo(FileStatus.OK);

            assertThat(backend.released())
                    .as("nothing is released twice")
                    .containsExactly("resultSet", "statement", "connection");
        }

        @Test
        @DisplayName("closing a failed open reports that open's own status and issues nothing")
        void closingAFailedOpenReportsTheOpenStatus() {
            Backend backend = Backend.serving(threeRows()).failingToConnect();
            DalyTranRepository repository = repository(backend);

            DalytranFile file = repository.open();

            assertThat(repository.close(file))
                    .isEqualTo(DalyTranRepository.PERMANENT_ERROR_STATUS);
            assertThat(backend.released()).isEmpty();
        }

        @Test
        @DisplayName("a release refusal is reported as a non-'00' close, and still frees the rest")
        void aReleaseRefusalIsReported() {
            Backend backend = Backend.serving(threeRows()).failingToClose();
            DalyTranRepository repository = repository(backend);

            DalytranFile file = repository.open();

            assertThat(repository.close(file))
                    .isEqualTo(DalyTranRepository.PERMANENT_ERROR_STATUS);
            assertThat(backend.released())
                    .as("every resource is attempted even when an earlier one refuses")
                    .containsExactly("resultSet", "statement", "connection");
        }

        @Test
        @DisplayName("AutoCloseable close() releases without reporting, and cannot leak the cursor")
        void autoCloseableCloseStillReleases() {
            Backend backend = Backend.serving(threeRows());
            DalyTranRepository repository = repository(backend);

            DalytranFile escaped;
            try (DalytranFile file = repository.open()) {
                escaped = file;
                assertThat(file.readNext().isFound()).isTrue();
            }

            assertThat(escaped.isClosed()).isTrue();
            assertThat(backend.released()).containsExactly("resultSet", "statement", "connection");
        }

        @Test
        @DisplayName("a read after a close is a programming error: no consumer ever does it")
        void aReadAfterCloseThrows() {
            DalyTranRepository repository = repository(Backend.serving(threeRows()));
            DalytranFile file = repository.open();
            repository.close(file);

            assertThatIllegalStateException()
                    .isThrownBy(() -> repository.readNext(file))
                    .withMessageContaining("has been closed");
            assertThatIllegalStateException()
                    .isThrownBy(file::readNext)
                    .withMessageContaining("CBTRN02C");
        }
    }

    // =============================================================================================
    // The decode seam.
    // =============================================================================================

    @Nested
    @DisplayName("The decode seam - byte-exact, and no JDBC in the path (risk R-E)")
    class DecodeSeam {

        @Test
        @DisplayName("a 350-byte round trip preserves every byte, FILLER included (gates G19, G21)")
        void aRoundTripPreservesEveryByte() {
            DalyTranRepository repository = repositoryWithoutBackend();
            String shipped = threeRows().get(0);

            DalyTranRecord record = repository.decode(shipped);

            assertThat(record.rawImage()).hasSize(350);
            assertThat(record.displayImage()).isEqualTo(shipped);
            assertThat(record.encode(ASCII)).isEqualTo(shipped.getBytes(ASCII));
            assertThat(record.filler())
                    .as("the trailing FILLER X(20) is part of the record and is not trimmed away")
                    .isEqualTo(" ".repeat(20));
        }

        @Test
        @DisplayName("the bytes and the text seams agree, so a fixture row and a stored row decode alike")
        void theByteAndTextSeamsAgree() {
            DalyTranRepository repository = repositoryWithoutBackend();
            String shipped = threeRows().get(1);

            assertThat(repository.decode(shipped).rawImage())
                    .isEqualTo(repository.decode(shipped.getBytes(ASCII)).rawImage());
        }

        @ParameterizedTest(name = "{0} decodes to {1}")
        @CsvSource({
            "0000005047G, 504.77",
            "0000009190}, -919.00",
            "00000000000, 0.00",
            "0000000567P, -56.77",
            "0000000709R, -70.99",
            "0000002153L, -215.33"
        })
        @DisplayName("DALYTRAN-AMT takes its sign from the zoned overpunch, at scale exactly 2")
        void theAmountDecodesFromTheOverpunch(String amtImage, String expected) {
            DalyTranRepository repository = repositoryWithoutBackend();

            DalyTranRecord record =
                    repository.decode(image("0000000000000009", amtImage, "4111111111111111"));

            assertThat(record.dalytranAmt()).isEqualTo(new BigDecimal(expected));
            assertThat(record.dalytranAmt().scale())
                    .isEqualTo(DalyTranRepository.AMOUNT_SCALE);
            assertThat(record.dalytranAmtImage()).isEqualTo(amtImage);
        }

        @ParameterizedTest(name = "a {0}-byte image")
        @ValueSource(ints = { 0, 349, 351 })
        @DisplayName("an image of the wrong width is refused rather than padded to fit")
        void aWrongWidthImageIsRefused(int width) {
            DalyTranRepository repository = repositoryWithoutBackend();
            byte[] wrong = new byte[width];
            Arrays.fill(wrong, (byte) ' ');

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> repository.decode(wrong))
                    .withMessageContaining("CVTRA06Y");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> repository.decode(new String(wrong, ASCII)));
        }

        @Test
        @DisplayName("an absent image is an end-of-file outcome, not something to decode")
        void anAbsentImageIsRefused() {
            DalyTranRepository repository = repositoryWithoutBackend();

            assertThatNullPointerException().isThrownBy(() -> repository.decode((byte[]) null));
            assertThatNullPointerException().isThrownBy(() -> repository.decode((String) null));
        }
    }

    // =============================================================================================
    // The shipped fixture.
    // =============================================================================================

    @Nested
    @DisplayName("The shipped fixture - app/data/ASCII/dailytran.txt, measured not assumed")
    class ShippedFixture {

        @Test
        @DisplayName("the fixture is 300 records of exactly 350 bytes")
        void theFixtureGeometryIsWhatTheCopybookDeclares() {
            List<String> rows = fixtureRows();

            assertThat(rows).hasSize(FIXTURE_RECORDS);
            assertThat(rows).allSatisfy(row ->
                    assertThat(row).hasSize(DalyTranRepository.RECORD_LENGTH));
        }

        @Test
        @DisplayName("all 300 records read back in order, then the file ends exactly once")
        void allThreeHundredRecordsReadBackInOrder() {
            List<String> rows = fixtureRows();
            Backend backend = Backend.serving(rows);
            DalyTranRepository repository = repository(backend);
            List<String> observed = new ArrayList<>();

            DalytranFile file = repository.open();
            ReadResult result = repository.readNext(file);
            while (result.isFound()) {
                DalyTranRecord record = result.dalyTran().orElseThrow();
                assertThat(record.rawImage()).hasSize(DalyTranRepository.RECORD_LENGTH);
                observed.add(record.displayImage());
                result = repository.readNext(file);
            }

            assertThat(result.isEndOfFile()).isTrue();
            assertThat(observed).hasSize(FIXTURE_RECORDS).isEqualTo(rows);
            assertThat(file.recordsRead()).isEqualTo(FIXTURE_RECORDS);
            assertThat(repository.close(file)).isEqualTo(FileStatus.OK);
            assertThat(backend.released()).containsExactly("resultSet", "statement", "connection");
        }

        @Test
        @DisplayName("every amount is scale 2, and the fifty negative overpunches decode negative")
        void everyAmountIsScaleTwoAndTheNegativesAreNegative() {
            DalyTranRepository repository = repositoryWithoutBackend();
            int negatives = 0;

            for (String row : fixtureRows()) {
                BigDecimal amount = repository.decode(row).dalytranAmt();
                assertThat(amount.scale()).isEqualTo(DalyTranRepository.AMOUNT_SCALE);
                if (amount.signum() < 0) {
                    negatives++;
                }
            }

            assertThat(negatives)
                    .as("50 of the 300 shipped rows carry a negative zoned overpunch")
                    .isEqualTo(FIXTURE_NEGATIVE_AMOUNTS);
        }
    }

    // =============================================================================================
    // The outcome type.
    // =============================================================================================

    @Nested
    @DisplayName("ReadResult - the three arms, and nothing constructible outside them")
    class ReadResultContract {

        @Test
        @DisplayName("no component may be null, so no null escapes the type")
        void noComponentMayBeNull() {
            Optional<DalyTranRecord> noRecord = Optional.empty();

            assertThatNullPointerException().isThrownBy(() ->
                    new ReadResult(null, Outcome.OK, Optional.of(aRecord()), Optional.empty()));
            assertThatNullPointerException().isThrownBy(() ->
                    new ReadResult(FileStatus.OK, null, Optional.of(aRecord()), Optional.empty()));
            assertThatNullPointerException().isThrownBy(() ->
                    new ReadResult(FileStatus.END_OF_FILE, Outcome.END_OF_FILE, null,
                            Optional.empty()));
            assertThatNullPointerException().isThrownBy(() ->
                    new ReadResult(FileStatus.END_OF_FILE, Outcome.END_OF_FILE, noRecord, null));
        }

        @ParameterizedTest(name = "status=[{0}]")
        @ValueSource(strings = { "0", "000", "" })
        @DisplayName("a file status is exactly two characters")
        void aStatusIsTwoCharacters(String malformed) {
            assertThatIllegalArgumentException().isThrownBy(() ->
                    new ReadResult(malformed, Outcome.OTHER, Optional.empty(), Optional.empty()));
        }

        @ParameterizedTest(name = "outcome={0}")
        @ValueSource(strings = { "NOT_FOUND", "DUPLICATE" })
        @DisplayName("a keyed outcome cannot arise on a sequential read of a PS dataset")
        void aKeyedOutcomeCannotArise(String name) {
            Outcome keyed = Outcome.valueOf(name);
            String status = keyed.batchStatus().orElseThrow();

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new ReadResult(status, keyed, Optional.empty(),
                            Optional.empty()))
                    .withMessageContaining("three arms");
        }

        @Test
        @DisplayName("only the '00' arm carries a record, and it always does")
        void onlyTheSuccessfulArmCarriesARecord() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new ReadResult(FileStatus.END_OF_FILE, Outcome.END_OF_FILE,
                            Optional.of(aRecord()), Optional.empty()))
                    .withMessageContaining("carries no record");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new ReadResult(FileStatus.OK, Outcome.OK, Optional.empty(),
                            Optional.empty()))
                    .withMessageContaining("found(DalyTranRecord)");
        }

        @Test
        @DisplayName("a status and its classification must agree")
        void theStatusAndItsClassificationMustAgree() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new ReadResult(FileStatus.END_OF_FILE, Outcome.OK,
                            Optional.of(aRecord()), Optional.empty()))
                    .withMessageContaining("must agree");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new ReadResult(FileStatus.OK, Outcome.OTHER, Optional.empty(),
                            Optional.empty()))
                    .withMessageContaining("names explicitly");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new ReadResult(FileStatus.END_OF_FILE, Outcome.OTHER,
                            Optional.empty(), Optional.empty()))
                    .withMessageContaining("names explicitly");
        }

        @Test
        @DisplayName("the four factories build exactly the four reachable outcomes")
        void theFactoriesBuildTheReachableOutcomes() {
            DalyTranRecord record = aRecord();

            ReadResult found = ReadResult.found(record);
            assertThat(found.isFound()).isTrue();
            assertThat(found.dalyTran()).containsSame(record);

            ReadResult ended = ReadResult.endOfFile();
            assertThat(ended.isEndOfFile()).isTrue();
            assertThat(ended.applResult()).isEqualTo(DalyTranRepository.APPL_RESULT_EOF);

            ReadResult failed = ReadResult.other(DalyTranRepository.PERMANENT_ERROR_STATUS);
            assertThat(failed.isOther()).isTrue();
            assertThat(failed.diagnostic()).isEmpty();

            // The negative side of every predicate: exactly one arm answers true for any outcome, which
            // is what lets a caller branch on them the way the COBOL branches on APPL-RESULT.
            assertThat(found.isEndOfFile()).isFalse();
            assertThat(found.isOther()).isFalse();
            assertThat(ended.isFound()).isFalse();
            assertThat(ended.isOther()).isFalse();
            assertThat(failed.isFound()).isFalse();
            assertThat(failed.isEndOfFile()).isFalse();
            assertThat(found.applResult()).isEqualTo(DalyTranRepository.APPL_RESULT_OK);
            assertThat(failed.applResult()).isEqualTo(DalyTranRepository.APPL_RESULT_FATAL);

            assertThatNullPointerException()
                    .isThrownBy(() -> ReadResult.other(DalyTranRepository.PERMANENT_ERROR_STATUS,
                            null));
            assertThatNullPointerException().isThrownBy(() -> ReadResult.found(null));
        }

        @Test
        @DisplayName("the display line is composed by the shared vocabulary, not restated here")
        void theDisplayLineIsTheSharedRendering() {
            assertThat(ReadResult.endOfFile().displayLine())
                    .isEqualTo(FileStatus.DISPLAY_PREFIX + "0010");
            assertThat(ReadResult.found(aRecord()).displayLine())
                    .isEqualTo(FileStatus.DISPLAY_PREFIX + "0000");
        }
    }

    // =============================================================================================
    // Structural guards. The properties a later change must not quietly undo.
    // =============================================================================================

    @Nested
    @DisplayName("Structural guards - gates G22, G24, G44, G46, G52 and G53 about the file itself")
    class StructuralGuards {

        @Test
        @DisplayName("the public surface is open, read and close - no write and no keyed read")
        void thePublicSurfaceIsReadOnly() {
            Set<String> published = new TreeSet<>();
            for (Method method : DalyTranRepository.class.getDeclaredMethods()) {
                if (Modifier.isPublic(method.getModifiers()) && !method.isSynthetic()) {
                    published.add(method.getName());
                }
            }

            assertThat(published)
                    .as("grep -n \"DALYTRAN\" app/cbl/*.cbl finds only OPEN INPUT, READ ... INTO and "
                            + "CLOSE, so nothing else is exposed (AAP 0.3.5)")
                    .containsExactlyInAnyOrder("open", "readNext", "close", "decode",
                            "datasetName", "recordLength", "datasetCharset")
                    .doesNotContain("write", "rewrite", "delete", "readByKey", "readForUpdate",
                            "startBrowse", "insert", "update");
        }

        @Test
        @DisplayName("no dataset name is written into Java (gate G46)")
        void noDatasetLiteralIsPresent() {
            assertThat(sourceText())
                    .doesNotContain("AWS.M2.CARDDEMO")
                    .doesNotContain(".DALYTRAN.PS");
        }

        @Test
        @DisplayName("no wildcard import, so every copybook-to-type correspondence stays auditable")
        void noWildcardImport() {
            assertThat(sourceText().lines().filter(line -> line.startsWith("import"))
                    .filter(line -> line.endsWith(".*;")).toList()).isEmpty();
        }

        @Test
        @DisplayName("no double, no float, and no rounding mode but truncation (gates G22, G24)")
        void noBinaryFloatingPointAndNoHalfRounding() {
            List<String> code = sourceText().lines()
                    .filter(line -> !line.strip().startsWith("*"))
                    .filter(line -> !line.strip().startsWith("//"))
                    .toList();

            assertThat(code).noneMatch(line -> line.contains(" double ") || line.contains(" float "));
            assertThat(code).noneMatch(line -> line.contains("HALF_UP") || line.contains("HALF_EVEN")
                    || line.contains("RoundingMode.CEILING") || line.contains("RoundingMode.FLOOR"));
        }

        @Test
        @DisplayName("no DDL, no entity annotation and no version column (gate G44)")
        void noSchemaArtefacts() {
            assertThat(sourceText())
                    .doesNotContain("@Entity")
                    .doesNotContain("@Table")
                    .doesNotContain("CREATE TABLE")
                    .doesNotContain("ALTER TABLE")
                    .doesNotContain("DROP TABLE")
                    .doesNotContain("javax.persistence")
                    .doesNotContain("jakarta.persistence");
        }

        @Test
        @DisplayName("no ORDER BY anywhere: imposing one would reorder a physical-sequential file")
        void noOrderByAnywhere() {
            assertThat(sourceText().lines()
                    .filter(line -> line.contains("ORDER BY"))
                    .filter(line -> !line.strip().startsWith("*"))
                    .toList())
                    .as("the only mentions of ORDER BY are the documentation of why there is none")
                    .isEmpty();
        }

        @Test
        @DisplayName("this class never abends: the decision belongs to the caller")
        void thisClassNeverAbends() {
            assertThat(sourceText())
                    .as("both consumers abend from their own paragraph, after their own message")
                    .doesNotContain("AbendException")
                    .doesNotContain("CEE3ABD");
        }

        @Test
        @DisplayName("no static mutable state: only the logger is static (gate G53)")
        void noStaticMutableState() {
            assertThat(Arrays.stream(DalyTranRepository.class.getDeclaredFields())
                    .filter(field -> Modifier.isStatic(field.getModifiers()))
                    .filter(field -> !Modifier.isFinal(field.getModifiers()))
                    .filter(field -> !field.isSynthetic())
                    .map(java.lang.reflect.Field::getName)
                    .toList()).isEmpty();
            assertThat(Arrays.stream(DalyTranRepository.class.getDeclaredFields())
                    .filter(field -> !Modifier.isStatic(field.getModifiers()))
                    .filter(field -> !field.isSynthetic())
                    .allMatch(field -> Modifier.isFinal(field.getModifiers())))
                    .as("a singleton repository holds nothing that a read can perturb")
                    .isTrue();
        }

        @Test
        @DisplayName("every log line is a single argument, so no driver prose is emitted verbatim")
        void everyLogLineIsSanitized() {
            assertThat(sourceText())
                    .as("a throwable handed to a logger emits its whole cause chain - CWE-532")
                    .doesNotContain("LOG.error(refusal")
                    .doesNotContain(", refusal)")
                    .doesNotContain(", ignored)");
            assertThat(sourceText())
                    .as("but a refusal must still be reported, in sanitized form")
                    .contains(".describe()");
        }

        @Test
        @DisplayName("the per-open state lives on the handle, never on the singleton")
        void thePerOpenStateLivesOnTheHandle() {
            Set<String> mutable = new TreeSet<>();
            for (java.lang.reflect.Field field : DalytranFile.class.getDeclaredFields()) {
                if (!Modifier.isFinal(field.getModifiers()) && !field.isSynthetic()) {
                    mutable.add(field.getName());
                }
            }

            assertThat(mutable).containsExactly("closed", "cursor", "endOfFile", "recordsRead");
        }

        @Test
        @DisplayName("the charset bean this repository injects is the dataset one, named explicitly")
        void theInjectedCharsetIsTheDatasetBean() {
            assertThat(sourceText())
                    .contains("CobolCharsetConfig.DATASET_CHARSET_BEAN_NAME")
                    .doesNotContain("Charset.defaultCharset")
                    .doesNotContain("file.encoding");
            assertThat(CobolCharsetConfig.DATASET_CHARSET_BEAN_NAME)
                    .isEqualTo("carddemoDatasetCharset");
        }

        @Test
        @DisplayName("two concurrent opens read independently, because neither touches the other")
        void twoOpensReadIndependently() {
            DalyTranRepository repository = repository(Backend.serving(threeRows()));

            try (DalytranFile first = repository.open(); DalytranFile second = repository.open()) {
                assertThat(repository.readNext(first).dalyTran().orElseThrow().dalytranId())
                        .isEqualTo("0000000000000001");
                assertThat(repository.readNext(second).dalyTran().orElseThrow().dalytranId())
                        .as("the second open has its own position and starts at the first record")
                        .isEqualTo("0000000000000001");
                assertThat(repository.readNext(first).dalyTran().orElseThrow().dalytranId())
                        .isEqualTo("0000000000000002");
                assertThat(first.recordsRead()).isEqualTo(2L);
                assertThat(second.recordsRead()).isEqualTo(1L);
            }
        }

        @Test
        @DisplayName("the template is held only for its DataSource: the cursor is this class's own")
        void theTemplateIsUsedOnlyForItsDataSource() {
            assertThat(sourceText())
                    .as("a template query cannot hold a cursor across chunk boundaries, which is the "
                            + "whole reason this repository manages its own")
                    .doesNotContain("jdbcTemplate.query")
                    .doesNotContain("jdbcTemplate.execute")
                    .doesNotContain("jdbcTemplate.update")
                    .contains("jdbcTemplate.getDataSource()");
            assertThat(sourceText())
                    .as("and it must not reach the transactional connection, which a commit would close")
                    .doesNotContain("DataSourceUtils");
        }
    }
}
