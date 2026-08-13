package com.vsergeychik.carddemo.transaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import com.vsergeychik.carddemo.account.AccountInterestCalcJob;
import com.vsergeychik.carddemo.common.AbendException;
import com.vsergeychik.carddemo.common.CicsResponse;
import com.vsergeychik.carddemo.common.CobolDecimal;
import com.vsergeychik.carddemo.common.DatasetObservation;
import com.vsergeychik.carddemo.common.DatasetRelation.BackendDiagnostic;
import com.vsergeychik.carddemo.common.FileStatus;
import com.vsergeychik.carddemo.common.FileStatus.Outcome;
import com.vsergeychik.carddemo.common.FixedWidthRecord.FieldSpan;
import com.vsergeychik.carddemo.common.FixedWidthRecord.PictureKind;
import com.vsergeychik.carddemo.common.PhysicalSequence;
import com.vsergeychik.carddemo.common.RecordImageForm;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBinding;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBindings;
import com.vsergeychik.carddemo.config.DatasetUnitOfWork;
import com.vsergeychik.carddemo.transaction.TransactionRepository.Browse;
import com.vsergeychik.carddemo.transaction.TransactionRepository.BrowseDirection;
import com.vsergeychik.carddemo.transaction.TransactionRepository.InputFile;
import com.vsergeychik.carddemo.transaction.TransactionRepository.OutputFile;
import com.vsergeychik.carddemo.transaction.TransactionRepository.ReadResult;
import com.vsergeychik.carddemo.transaction.TransactionRepository.WriteResult;
import com.vsergeychik.carddemo.transaction.model.TranRecord;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
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
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The behaviour of {@link TransactionRepository}, asserted against a real relational backend for the
 * access paths and against a stubbed template for the failures a backend will not produce on demand.
 *
 * <h2>Why a real backend for most of it</h2>
 *
 * <p>Every statement this repository sends is <em>composed</em> - the identifier, the key predicate, the
 * ordering, the two descending forms {@code DatasetRelation} does not provide - and a mocked template
 * asserts only that some text was passed to it. Residual risk R-E means the production driver cannot be
 * exercised from this build, which makes it all the more important that the composed SQL is executed by
 * something that will reject it if it is wrong. So the access paths run against an in-memory relation
 * whose single {@code CHAR(350)} column is the record image, which is exactly the shape
 * {@code RecordImageForm.CHARACTER} describes.
 *
 * <p>Three things a real backend will not do on demand - refuse a statement, hand back no result object
 * at all, and reject an insert as an integrity violation after a probe found nothing - are driven from a
 * stubbed template instead. Those are the arms that decide whether a production failure is diagnosable,
 * so they are tested rather than assumed.
 *
 * <h2>What is asserted, and against what</h2>
 * <ul>
 *   <li>the constructor's seven configuration guards, each with a hand-built binding;</li>
 *   <li>the four enumerated file statuses - {@code '00'}, {@code '10'}, {@code '22'}, {@code '23'} - and
 *       the composed permanent-error status, each reachable per operation (gate G47);</li>
 *   <li>a 350-byte round trip whose every byte survives, the trailing {@code FILLER PIC X(20)} included
 *       (gates G19 and G21);</li>
 *   <li>forward and backward browsing from both boundaries and from a concrete key, with the anchor
 *       record included exactly as {@code app/cbl/COCRDLIC.cbl:1284-1307} shows CICS including it;</li>
 *   <li>the {@code HIGH-VALUES} backward browse that {@code app/cbl/COTRN02C.cbl:444-448} and
 *       {@code app/cbl/COBIL00C.cbl:212-215} use to find the highest existing key;</li>
 *   <li>a duplicate keyed add reporting {@code '22'} with nothing written, rather than throwing;</li>
 *   <li>sequential input in key order over an indexed binding and in written order over a sequential
 *       one;</li>
 *   <li>the fourteen {@code CVTRA05Y} spans restated by addition, the three offsets other components
 *       address by number, and {@code TRAN-AMT} at scale 2 truncated rather than rounded (gates G22 and
 *       G24, rules R2, R4 and R5);</li>
 *   <li>the one image that a decode-and-re-encode silently changes - a whole-value negative zero -
 *       carried intact because the repository moves the raw span rather than the value;</li>
 *   <li>that no dataset name is compiled into this repository (gate G46) and that no schema artefact of
 *       any kind is involved in reaching the dataset (gate G44);</li>
 *   <li>that this class never throws an abend, and that no rewrite or delete exists to call.</li>
 * </ul>
 *
 * <h2>What this class deliberately does not reach for</h2>
 *
 * <p>Nothing here imports {@code com.vsergeychik.carddemo.parity} and nothing here reads a
 * {@code src/test/resources/parity} case file. The parity harness is a separate deliverable with its own
 * gate; every byte this class needs is either an inline literal below or a value the repository itself
 * reports. Nothing here reads the wall clock, no test depends on another's ordering, and there is no
 * mutable static state at all: every static member is a {@code final} immutable constant or a pure
 * fixture method, and each test method gets its own database.
 */
@DisplayName("TransactionRepository - the TRANSACT / TRANFILE / SYSTRAN dataset access")
class TransactionRepositoryTest {

    /** The code page of the ASCII fixtures, named explicitly - never a platform default. */
    private static final Charset ASCII = StandardCharsets.US_ASCII;

    /**
     * The physical-record ordinal a physical-sequential read is ordered by, as
     * {@code application-test.yml} configures it: H2's own row-identifier pseudo-column, which increases
     * with each insert and so returns records in the order they were written.
     */
    private static final PhysicalSequence ORDINAL = PhysicalSequence.of("_ROWID_");

    /** The declared record width of {@code app/cpy/CVTRA05Y.cpy}. */
    private static final int RECORD_LENGTH = 350;

    /** The declared key width: {@code TRAN-ID PIC X(16)}. */
    private static final int KEY_LENGTH = 16;

    /** A dataset name for the master that is well-formed and belongs to no real deployment. */
    private static final String MASTER_DS = "CARDDEMO.TEST.TRANSACT.VSAM.KSDS";

    /** A dataset name for the sorted daily file the report job reads. */
    private static final String DALY_DS = "CARDDEMO.TEST.TRANSACT.DALY";

    /** A dataset name for the generated-transaction output. */
    private static final String SYSTRAN_DS = "CARDDEMO.TEST.SYSTRAN";

    /** A dataset name for a relation whose column is not fixed-width, used for malformed rows. */
    private static final String RAGGED_DS = "CARDDEMO.TEST.RAGGED";

    /** The column position the record image occupies, as the repository addresses it. */
    private static final String IMAGE_COLUMN = "RECORD_IMAGE";

    /** The live data source for the test in progress. */
    private DataSource dataSource;

    /** The template over {@link #dataSource}. */
    private JdbcTemplate template;

    /** The repository under test, bound to the tables created in {@link #createRelations()}. */
    private TransactionRepository repository;

    @BeforeEach
    void createRelations() {
        // A fresh in-memory database per test method, named uniquely so that no test - including two
        // invocations of the same @ParameterizedTest - can see another's rows, and so that no test
        // depends on the order the others ran in. The name is generated rather than counted because a
        // static counter would be mutable static state, which practice B9 and gate G53 forbid; nothing
        // asserts on the name, so generating it costs no determinism. DB_CLOSE_DELAY=-1 keeps the
        // database alive between the connections SimpleDriverDataSource opens, which an unnamed
        // jdbc:h2:mem: URL would not: there, every connection would get a private empty database.
        dataSource = new SimpleDriverDataSource(new org.h2.Driver(),
                "jdbc:h2:mem:tranrepo-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        template = new JdbcTemplate(dataSource);
        for (String dataset : new String[] { MASTER_DS, DALY_DS, SYSTRAN_DS }) {
            template.execute("CREATE TABLE \"" + dataset + "\" (\"" + IMAGE_COLUMN + "\" CHAR("
                    + RECORD_LENGTH + "))");
        }
        // A deliberately ragged relation: VARCHAR rather than CHAR, so a short value stays short and a
        // NULL stays null. Both are conditions a fixed-width read must report rather than tolerate.
        template.execute("CREATE TABLE \"" + RAGGED_DS + "\" (\"" + IMAGE_COLUMN + "\" VARCHAR("
                + RECORD_LENGTH + "))");
        repository = new TransactionRepository(template, validBindings(), ASCII,
                RecordImageForm.CHARACTER, ORDINAL);
    }

    /**
     * Drops and shuts down this test's database once the test has finished with it.
     *
     * <p>{@code DB_CLOSE_DELAY=-1} above is what keeps the database alive between the connections
     * {@code SimpleDriverDataSource} opens, and it is also what keeps it alive for the rest of the JVM
     * after the test ends. Left that way every test in this class leaves a live, still-addressable
     * schema behind - a name a later test could reach, which is precisely the shared state a per-test
     * database exists to avoid, and a growing set of them held to the end of the run.
     */
    @AfterEach
    void dropRelations() {
        template.execute("DROP ALL OBJECTS");
        template.execute("SHUTDOWN");
    }

    // =================================================================================================
    // Fixtures.
    // =================================================================================================

    /** An indexed binding at the copybook width, keyed on the leading sixteen bytes. */
    private static DatasetBinding ksds(String dsname) {
        return new DatasetBinding(dsname, "ksds", false, "FB", null, RECORD_LENGTH, "CVTRA05Y",
                KEY_LENGTH, null, null, null);
    }

    /** A sequential binding at the copybook width, with no key, as a PS dataset has none. */
    private static DatasetBinding sequential(String dsname) {
        return new DatasetBinding(dsname, "sequential", false, "F", 0, RECORD_LENGTH, "CVTRA05Y", null,
                null, null, null);
    }

    /** The three bindings the repository resolves, all valid. */
    private static DatasetBindings validBindings() {
        DatasetBindings bindings = new DatasetBindings();
        bindings.put(TransactionRepository.CICS_FILE_NAME, ksds(MASTER_DS));
        bindings.put(TransactionRepository.INPUT_DD_NAME, ksds(MASTER_DS));
        bindings.put(TransactionRepository.SEQUENTIAL_OUTPUT_DD_NAME, sequential(SYSTRAN_DS));
        return bindings;
    }

    /** The three bindings with one replaced, for a guard test. */
    private static DatasetBindings bindingsWith(String key, DatasetBinding replacement) {
        DatasetBindings bindings = validBindings();
        bindings.put(key, replacement);
        return bindings;
    }

    /** A record carrying a given key and recognisable content in every other field. */
    private static TranRecord record(String tranId) {
        TranRecord record = new TranRecord(ASCII);
        record.moveTranId(tranId);
        record.moveTranTypeCd("01");
        record.moveTranCatCd(5);
        record.moveTranSource("System");
        record.moveTranDesc("Int. for a/c 00000000011");
        record.moveTranMerchantId(0L);
        record.moveTranCardNum("4444333322221111");
        record.moveTranOrigTs("2022-07-18 00.00.00.000000");
        record.moveTranProcTs("2022-07-18 00.00.00.000000");
        return record;
    }

    /** Stores a record image directly, bypassing the repository, so a read has something to find. */
    private void seed(String dataset, TranRecord record) {
        template.update("INSERT INTO \"" + dataset + "\" VALUES (?)",
                new String(record.rawImage(), ASCII));
    }

    /** Stores an arbitrary image, for the malformed-row cases. */
    private void seedRaw(String dataset, String image) {
        template.update("INSERT INTO \"" + dataset + "\" VALUES (?)", image);
    }

    /**
     * A data source that hands out real connections and remembers every one, so a test can see how many
     * are still open at a given moment.
     *
     * <p>This is what makes "the pass streams through one cursor" assertable rather than asserted by
     * inspection: a pass that drains its statement into a list holds nothing between reads, while a pass
     * that walks a cursor holds exactly one connection from its first read until its close. Counting the
     * live ones distinguishes the two, and it does so against the real driver rather than a mock's idea
     * of one.
     */
    private static final class RecordingDataSource extends DelegatingDataSource {

        /** Every connection handed out, closed or not. */
        private final List<Connection> handedOut = new ArrayList<>();

        /**
         * Every statement prepared with an explicit cursor type - which is to say, every sequential
         * pass. Recorded so the prepare-time settings can be verified on the object the real driver
         * accepted, rather than on a mock's idea of it.
         */
        private final List<PreparedStatement> cursorStatements = new ArrayList<>();

        RecordingDataSource(DataSource target) {
            super(target);
        }

        @Override
        public Connection getConnection() throws SQLException {
            Connection connection = Mockito.spy(super.getConnection());
            Mockito.doAnswer(invocation -> {
                PreparedStatement prepared = Mockito.spy(
                        (PreparedStatement) invocation.callRealMethod());
                cursorStatements.add(prepared);
                return prepared;
            }).when(connection).prepareStatement(anyString(), ArgumentMatchers.anyInt(),
                    ArgumentMatchers.anyInt());
            handedOut.add(connection);
            return connection;
        }

        /**
         * The statements prepared for a sequential pass, in order.
         *
         * @return the recorded statements
         */
        List<PreparedStatement> cursorStatements() {
            return List.copyOf(cursorStatements);
        }

        /**
         * How many of the connections handed out are still open.
         *
         * @return the count of live connections
         * @throws SQLException if a connection cannot report its own state
         */
        int liveConnections() throws SQLException {
            int live = 0;
            for (Connection connection : handedOut) {
                if (!connection.isClosed()) {
                    live++;
                }
            }
            return live;
        }
    }

    /** A repository whose datasets do not exist, so every statement is refused. */
    private TransactionRepository repositoryOverMissingRelations() {
        DatasetBindings bindings = new DatasetBindings();
        bindings.put(TransactionRepository.CICS_FILE_NAME, ksds("CARDDEMO.TEST.ABSENT.MASTER"));
        bindings.put(TransactionRepository.INPUT_DD_NAME, ksds("CARDDEMO.TEST.ABSENT.INPUT"));
        bindings.put(TransactionRepository.SEQUENTIAL_OUTPUT_DD_NAME,
                sequential("CARDDEMO.TEST.ABSENT.OUTPUT"));
        return new TransactionRepository(template, bindings, ASCII, RecordImageForm.CHARACTER, ORDINAL);
    }

    // =================================================================================================
    // Construction: the configuration this class refuses to start with.
    // =================================================================================================

    @Nested
    @DisplayName("Construction rejects a configuration that would read the wrong bytes")
    class Construction {

        @Test
        @DisplayName("every collaborator is required, because a half-wired repository cannot exist")
        void collaboratorsAreRequired() {
            DatasetBindings bindings = validBindings();
            assertThatNullPointerException().isThrownBy(() -> new TransactionRepository(
                    null, bindings, ASCII, RecordImageForm.CHARACTER, ORDINAL))
                    .withMessageContaining("JdbcTemplate");
            assertThatNullPointerException().isThrownBy(() -> new TransactionRepository(
                    template, null, ASCII, RecordImageForm.CHARACTER, ORDINAL))
                    .withMessageContaining("carddemo.datasets");
            assertThatNullPointerException().isThrownBy(() -> new TransactionRepository(
                    template, bindings, null, RecordImageForm.CHARACTER, ORDINAL))
                    .withMessageContaining("code page");
            assertThatNullPointerException().isThrownBy(() -> new TransactionRepository(
                    template, bindings, ASCII, null, ORDINAL))
                    .withMessageContaining("record-image representation");
        }

        @ParameterizedTest(name = "carddemo.datasets.{0} must declare record-length 350")
        @ValueSource(strings = { "TRANSACT", "TRANFILE", "SYSTRAN" })
        @DisplayName("a record width other than the copybook's 350 is refused on every binding")
        void recordWidthIsCopybookFixed(String ddName) {
            DatasetBinding wrong = new DatasetBinding(MASTER_DS, "ksds", false, "FB", null,
                    RECORD_LENGTH - 1, "CVTRA05Y", KEY_LENGTH, null, null, null);
            assertThatIllegalStateException()
                    .isThrownBy(() -> new TransactionRepository(template, bindingsWith(ddName, wrong),
                            ASCII, RecordImageForm.CHARACTER, ORDINAL))
                    .withMessageContaining("CVTRA05Y")
                    .withMessageContaining(ddName);
        }

        @Test
        @DisplayName("the master must be indexed, because every keyed path addresses its key")
        void masterMustBeIndexed() {
            assertThatIllegalStateException()
                    .isThrownBy(() -> new TransactionRepository(template,
                            bindingsWith(TransactionRepository.CICS_FILE_NAME, sequential(MASTER_DS)),
                            ASCII, RecordImageForm.CHARACTER, ORDINAL))
                    .withMessageContaining("indexed cluster");
        }

        @Test
        @DisplayName("the master's key must be TRAN-ID's declared sixteen bytes, and be declared at all")
        void masterKeyWidthIsDeclaredAndSixteen() {
            DatasetBinding noKeyLength = new DatasetBinding(MASTER_DS, "ksds", false, "FB", null,
                    RECORD_LENGTH, "CVTRA05Y", null, null, null, null);
            assertThatIllegalStateException()
                    .isThrownBy(() -> new TransactionRepository(template,
                            bindingsWith(TransactionRepository.CICS_FILE_NAME, noKeyLength), ASCII,
                            RecordImageForm.CHARACTER, ORDINAL))
                    .withMessageContaining("TRAN-ID PIC X(16)");
            DatasetBinding wrongKeyLength = new DatasetBinding(MASTER_DS, "ksds", false, "FB", null,
                    RECORD_LENGTH, "CVTRA05Y", KEY_LENGTH + 1, null, null, null);
            assertThatIllegalStateException()
                    .isThrownBy(() -> new TransactionRepository(template,
                            bindingsWith(TransactionRepository.CICS_FILE_NAME, wrongKeyLength), ASCII,
                            RecordImageForm.CHARACTER, ORDINAL))
                    .withMessageContaining("TRAN-TYPE-CD");
        }

        @Test
        @DisplayName("the master's key leads the record, so a non-zero offset is refused")
        void masterKeyOffsetIsZero() {
            DatasetBinding offset = new DatasetBinding(MASTER_DS, "ksds", false, "FB", null,
                    RECORD_LENGTH, "CVTRA05Y", KEY_LENGTH, 1, null, null);
            assertThatIllegalStateException()
                    .isThrownBy(() -> new TransactionRepository(template,
                            bindingsWith(TransactionRepository.CICS_FILE_NAME, offset), ASCII,
                            RecordImageForm.CHARACTER, ORDINAL))
                    .withMessageContaining("key offset 1");
        }

        @Test
        @DisplayName("TRANSACT has no alternate-index path, and a binding claiming one is refused (G45)")
        void masterHasNoAlternateIndexPath() {
            DatasetBinding withBase = new DatasetBinding(MASTER_DS, "aix-path", false, "FB", null,
                    RECORD_LENGTH, "CVTRA05Y", KEY_LENGTH, null, "SOMEBASE", "TRAN-CARD-NUM");
            assertThatIllegalStateException()
                    .isThrownBy(() -> new TransactionRepository(template,
                            bindingsWith(TransactionRepository.CICS_FILE_NAME, withBase), ASCII,
                            RecordImageForm.CHARACTER, ORDINAL))
                    .withMessageContaining("base cluster");
            DatasetBinding withAlternateKey = new DatasetBinding(MASTER_DS, "ksds", false, "FB", null,
                    RECORD_LENGTH, "CVTRA05Y", KEY_LENGTH, null, null, "TRAN-CARD-NUM");
            assertThatIllegalStateException()
                    .isThrownBy(() -> new TransactionRepository(template,
                            bindingsWith(TransactionRepository.CICS_FILE_NAME, withAlternateKey), ASCII,
                            RecordImageForm.CHARACTER, ORDINAL))
                    .withMessageContaining("no alternate index");
        }

        @Test
        @DisplayName("SYSTRAN is written front to back, so a keyed binding for it is refused")
        void sequentialOutputIsNotKeyed() {
            assertThatIllegalStateException()
                    .isThrownBy(() -> new TransactionRepository(template,
                            bindingsWith(TransactionRepository.SEQUENTIAL_OUTPUT_DD_NAME,
                                    ksds(SYSTRAN_DS)),
                            ASCII, RecordImageForm.CHARACTER, ORDINAL))
                    .withMessageContaining("sequential output");
        }

        @ParameterizedTest(name = "a dsname of [{0}] is not a dataset name")
        @CsvSource(value = { "NULL", "''", "'   '" }, nullValues = "NULL")
        @DisplayName("an unset or blank dsname is an unsupplied placeholder, not a defaultable absence")
        void datasetNameMustBeConfigured(String dsname) {
            DatasetBinding unset = new DatasetBinding(dsname, "ksds", false, "FB", null, RECORD_LENGTH,
                    "CVTRA05Y", KEY_LENGTH, null, null, null);
            assertThatIllegalStateException()
                    .isThrownBy(() -> new TransactionRepository(template,
                            bindingsWith(TransactionRepository.CICS_FILE_NAME, unset), ASCII,
                            RecordImageForm.CHARACTER, ORDINAL))
                    .withMessageContaining("declares no dataset name");
        }

        @Test
        @DisplayName("a dsname that is not a well-formed z/OS name is refused by the shared grammar")
        void datasetNameMustSatisfyTheGrammar() {
            DatasetBinding malformed = new DatasetBinding("not a dataset name", "ksds", false, "FB",
                    null, RECORD_LENGTH, "CVTRA05Y", KEY_LENGTH, null, null, null);
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new TransactionRepository(template,
                            bindingsWith(TransactionRepository.CICS_FILE_NAME, malformed), ASCII,
                            RecordImageForm.CHARACTER, ORDINAL));
        }

        @Test
        @DisplayName("an unconfigured DD name is reported by the catalogue, naming every configured key")
        void missingDdNameIsReported() {
            DatasetBindings incomplete = new DatasetBindings();
            incomplete.put(TransactionRepository.CICS_FILE_NAME, ksds(MASTER_DS));
            assertThatIllegalStateException()
                    .isThrownBy(() -> new TransactionRepository(template, incomplete, ASCII,
                            RecordImageForm.CHARACTER, ORDINAL))
                    .withMessageContaining(TransactionRepository.INPUT_DD_NAME);
        }
    }

    // =================================================================================================
    // Identity.
    // =================================================================================================

    @Nested
    @DisplayName("Identity comes from configuration, never from a literal (G46)")
    class Identity {

        @Test
        @DisplayName("the three dataset names, the code page and the geometry are all reported")
        void identityIsReported() {
            assertThat(repository.datasetName()).isEqualTo(MASTER_DS);
            assertThat(repository.inputDatasetName()).isEqualTo(MASTER_DS);
            assertThat(repository.sequentialOutputDatasetName()).isEqualTo(SYSTRAN_DS);
            assertThat(repository.datasetCharset()).isEqualTo(ASCII);
            assertThat(repository.recordLength()).isEqualTo(RECORD_LENGTH);
            assertThat(repository.keyLength()).isEqualTo(KEY_LENGTH);
            assertThat(repository.describeStatement()).contains(MASTER_DS).contains("1 = 0");
            assertThat(repository.describeInputStatement()).contains(MASTER_DS);
            assertThat(TransactionRepository.CICS_FILE_NAME)
                    .hasSize(TransactionRepository.CICS_FILE_NAME_LENGTH);
            assertThat(TransactionRepository.APPL_RESULT_FATAL).isEqualTo(12);
            assertThat(TransactionRepository.PERMANENT_ERROR_STATUS)
                    .hasSize(FileStatus.STATUS_LENGTH)
                    .isNotIn(FileStatus.OK, FileStatus.END_OF_FILE, FileStatus.DUPLICATE,
                            FileStatus.NOT_FOUND);
        }

        @Test
        @DisplayName("a key is reshaped by the alphanumeric MOVE - padded and truncated on the RIGHT")
        void keyIsReshapedByAnAlphanumericMove() {
            assertThat(repository.keyImageOf("")).isEqualTo(" ".repeat(KEY_LENGTH));
            assertThat(repository.keyImageOf("1")).isEqualTo("1" + " ".repeat(KEY_LENGTH - 1));
            assertThat(repository.keyImageOf("0123456789ABCDEFGHIJ"))
                    .isEqualTo("0123456789ABCDEF");
            assertThatNullPointerException().isThrownBy(() -> repository.keyImageOf(null))
                    .withMessageContaining("TRAN-ID");
        }
    }

    /**
     * Declares a unit of work around work that answers something, and always undoes the declaration.
     *
     * <p>Every write needs one: the pool hands out connections with auto-commit disabled, so an INSERT
     * issued with nothing bound to the thread is rolled back when the connection is returned, and the
     * repository refuses rather than reporting a record as stored. Some of the tests below drive mocked
     * JDBC chains that cannot begin a real transaction at all, and what the repository inspects is exactly
     * this thread state, so declaring it directly is the honest way to satisfy the precondition.
     *
     * @param work the work to run
     * @param <T>  what it answers
     * @return what {@code work} answered
     */
    private static <T> T inUnitOfWork(java.util.function.Supplier<T> work) {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            return work.get();
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    // =================================================================================================
    // The keyed add.
    // =================================================================================================

    @Nested
    @DisplayName("The keyed WRITE adds a record, or reports a duplicate without writing")
    class KeyedAdd {

        @Test
        @DisplayName("a record is added at its full 350-byte width, FILLER included (G19, G21)")
        void aRecordIsAddedByteExact() {
            TranRecord written = record("0000000000000001");
            WriteResult result = inUnitOfWork(() -> repository.write(written));

            assertThat(result.isWritten()).isTrue();
            assertThat(result.status()).isEqualTo(FileStatus.OK);
            assertThat(result.outcome()).isEqualTo(Outcome.OK);
            assertThat(result.applResult()).isEqualTo(FileStatus.APPL_AOK);
            assertThat(result.cicsResp()).hasValue(FileStatus.NORMAL);
            assertThat(result.cicsResp2()).isEqualTo(FileStatus.NO_REASON_CODE);
            assertThat(result.ddName()).isEqualTo(TransactionRepository.CICS_FILE_NAME);
            assertThat(result.observation()).isEmpty();
            assertThat(result.diagnostic()).isEmpty();
            assertThat(result.statusImage()).isEqualTo("0000");
            assertThat(result.describeResponse()).isEqualTo("Resp:0 Reas:0");

            String stored = template.queryForObject(
                    "SELECT \"" + IMAGE_COLUMN + "\" FROM \"" + MASTER_DS + "\"", String.class);
            assertThat(stored).hasSize(RECORD_LENGTH);
            assertThat(stored.getBytes(ASCII)).isEqualTo(written.rawImage());
            // The trailing FILLER PIC X(20) is present and space-filled: omit it and the record is 330
            // bytes and every downstream offset is wrong.
            assertThat(stored.substring(RECORD_LENGTH - TranRecord.FILLER_LENGTH))
                    .isEqualTo(" ".repeat(TranRecord.FILLER_LENGTH));
        }

        @Test
        @DisplayName("a stored record decodes back to every field it was written with")
        void aStoredRecordRoundTrips() {
            TranRecord written = record("0000000000000001");
            assertThat(inUnitOfWork(() -> repository.write(written)).isWritten()).isTrue();

            TranRecord read = repository.readByTranId("0000000000000001").requireRecord();
            assertThat(read.rawImage()).isEqualTo(written.rawImage());
            assertThat(read.tranId()).isEqualTo("0000000000000001");
            assertThat(read.tranTypeCd()).isEqualTo("01");
            assertThat(read.tranCatCd()).isEqualTo(5);
            assertThat(read.tranCardNum()).isEqualTo("4444333322221111");
            assertThat(read.filler()).isEqualTo(" ".repeat(TranRecord.FILLER_LENGTH));
        }

        @Test
        @DisplayName("an existing key yields '22' with nothing written, and never an exception")
        void anExistingKeyYieldsDuplicate() {
            assertThat(inUnitOfWork(() -> repository.write(record("0000000000000001"))).isWritten())
                    .isTrue();

            WriteResult duplicate = inUnitOfWork(() -> repository.write(record("0000000000000001")));

            assertThat(duplicate.isDuplicate()).isTrue();
            assertThat(duplicate.status()).isEqualTo(FileStatus.DUPLICATE);
            assertThat(duplicate.outcome()).isEqualTo(Outcome.DUPLICATE);
            assertThat(duplicate.cicsResp()).hasValue(FileStatus.DUPREC);
            // CBTRN02C does not enumerate '22' and lands it on APPL-RESULT 12.
            assertThat(duplicate.applResult()).isEqualTo(TransactionRepository.APPL_RESULT_FATAL);
            assertThat(template.queryForObject(
                    "SELECT COUNT(*) FROM \"" + MASTER_DS + "\"", Integer.class)).isEqualTo(1);
        }

        @Test
        @DisplayName("a refused write reports the permanent status and the driver's own diagnosis")
        void aRefusedWriteIsReported() {
            TransactionRepository missing = repositoryOverMissingRelations();
            WriteResult refused = inUnitOfWork(() -> missing.write(record("0000000000000001")));

            assertThat(refused.isOther()).isTrue();
            assertThat(refused.status()).isEqualTo(TransactionRepository.PERMANENT_ERROR_STATUS);
            assertThat(refused.applResult()).isEqualTo(TransactionRepository.APPL_RESULT_FATAL);
            assertThat(refused.diagnostic()).isPresent();
            assertThat(refused.describeResponse()).isEqualTo("Resp:none Reas:0");
        }

        @Test
        @DisplayName("the key predicate escapes LIKE metacharacters, so a wildcard key matches nothing")
        void theKeyPredicateEscapesWildcards() {
            assertThat(inUnitOfWork(() -> repository.write(record("0000000000000001"))).isWritten())
                    .isTrue();

            // '%' would match every record if it were not escaped, which would make this a duplicate.
            assertThat(inUnitOfWork(() -> repository.write(record("%%%%%%%%%%%%%%%%"))).isWritten())
                    .isTrue();
            assertThat(repository.readByTranId("%%%%%%%%%%%%%%%%").isFound()).isTrue();
            assertThat(repository.readByTranId("________________").isNotFound()).isTrue();
        }

        @Test
        @DisplayName("a record is required, because the WRITE is addressed by the key it carries")
        void aRecordIsRequired() {
            assertThatNullPointerException().isThrownBy(() -> repository.write(null))
                    .withMessageContaining("TRAN-ID");
        }

        @Test
        @DisplayName("outside a unit of work the write is refused, never reported as a posted record")
        void outsideAUnitOfWorkTheWriteIsRefused() {
            // The pool hands out connections with auto-commit disabled, so the INSERT would execute,
            // report the row it added, and then be rolled back when the connection was returned - leaving
            // CBTRN02C counting a posted transaction and the two online programs painting
            // 'Transaction added successfully.' and 'Payment successful.' for a record that reached no
            // dataset. There is no FILE STATUS for that, so nothing is attempted.
            assertThatIllegalStateException()
                    .isThrownBy(() -> repository.write(record("0000000000000001")))
                    .withMessageContaining("no transaction is open on this thread")
                    .withMessageContaining("changes stored records")
                    .withMessageContaining(MASTER_DS);

            assertThat(template.queryForObject(
                    "SELECT COUNT(*) FROM \"" + MASTER_DS + "\"", Integer.class)).isZero();
        }
    }

    // =================================================================================================
    // The keyed read.
    // =================================================================================================

    @Nested
    @DisplayName("The keyed READ reports NORMAL, NOTFND, a duplicate or WHEN OTHER")
    class KeyedRead {

        @Test
        @DisplayName("a present key reports '00' and the record")
        void aPresentKeyIsFound() {
            seed(MASTER_DS, record("0000000000000001"));

            ReadResult found = repository.readByTranId("0000000000000001");

            assertThat(found.isFound()).isTrue();
            assertThat(found.isRecordReturned()).isTrue();
            assertThat(found.status()).isEqualTo(FileStatus.OK);
            assertThat(found.applResult()).isEqualTo(FileStatus.APPL_AOK);
            assertThat(found.cicsResp()).hasValue(FileStatus.NORMAL);
            assertThat(found.requireRecord().tranId()).isEqualTo("0000000000000001");
        }

        @Test
        @DisplayName("an absent key reports '23' - the NOTFND arm, a normal branch and not an exception")
        void anAbsentKeyIsNotFound() {
            ReadResult missing = repository.readByTranId("0000000000000009");

            assertThat(missing.isNotFound()).isTrue();
            assertThat(missing.status()).isEqualTo(FileStatus.NOT_FOUND);
            assertThat(missing.outcome()).isEqualTo(Outcome.NOT_FOUND);
            assertThat(missing.cicsResp()).hasValue(FileStatus.NOTFND);
            assertThat(missing.applResult()).isEqualTo(TransactionRepository.APPL_RESULT_FATAL);
            assertThat(missing.record()).isEmpty();
            assertThatIllegalStateException().isThrownBy(missing::requireRecord)
                    .withMessageContaining("NOT_FOUND");
        }

        @Test
        @DisplayName("finding DB-05: an unreadable row is not reported as NOTFND")
        void anUnreadableRowIsNotReportedAsAbsent() {
            // A row the dataset holds and cannot present. TRAN-ID is the leading sixteen bytes of the
            // record image, so SQL evaluates the keyed LIKE against a null image as UNKNOWN and the read
            // matches nothing - which looks exactly like NOTFND and is not: that row's key is unknowable
            // and may be the one asked for. COTRN01C:283-288 paints "Transaction ID NOT found" on the
            // claim, and COTRN02C takes it as licence to add a record under that identifier.
            seedRaw(MASTER_DS, null);

            ReadResult result = repository.readByTranId("0000000000000009");

            assertThat(result.isNotFound())
                    .as("the unreadable row's key cannot be known, so no absence can be asserted")
                    .isFalse();
            assertThat(result.isOther()).isTrue();
            assertThat(result.status()).isEqualTo(TransactionRepository.PERMANENT_ERROR_STATUS);
            assertThat(result.cicsResp())
                    .as("the same arm the visible form of this condition already reports")
                    .hasValue(FileStatus.INVREQ);
        }

        @Test
        @DisplayName("finding DB-05: a genuinely absent key still reports '23'")
        void aGenuinelyAbsentKeyIsStillNotFound() {
            seed(MASTER_DS, record("0000000000000001"));

            // No row of the dataset is unreadable, so the absence is established rather than assumed.
            assertThat(repository.readByTranId("0000000000000009").status())
                    .isEqualTo(FileStatus.NOT_FOUND);
        }

        @Test
        @DisplayName("finding DB-05: a read that found its record is unaffected by an unreadable row")
        void aFoundRecordIsUnaffectedByAnUnreadableRowElsewhere() {
            seed(MASTER_DS, record("0000000000000001"));
            seedRaw(MASTER_DS, null);

            // A VSAM READ of a key that resolves does not fail because another record in the cluster is
            // damaged, so the proof is confined to the not-found path.
            assertThat(repository.readByTranId("0000000000000001").isFound()).isTrue();
        }

        @Test
        @DisplayName("finding DB-05: an empty browse is an end of file and needs no proof")
        void anEmptyBrowseIsStillAnEndOfFileEvenWithAnUnreadableRow() {
            // The proof qualifies the KEYED path only. A browse that walked the rows it was pointed at has
            // already seen every row there is, and an unreadable one among them is a row it read rather
            // than a row it missed - so an empty browse read stays ENDFILE, unchanged.
            try (TransactionRepository.InputFile input = repository.openInput(sequential(DALY_DS))) {
                assertThat(input.readNext().isEndOfFile()).isTrue();
            }
        }

        @Test
        @DisplayName("finding DB-05: a refused probe is reported rather than reported as absent")
        void aRefusedProbeIsReportedRatherThanAssumedAbsent() {
            seed(MASTER_DS, record("0000000000000001"));
            JdbcTemplate refusingTheProbe = Mockito.spy(template);
            // The keyed read is answered for real; the very next creator-bound query - the probe - is not.
            Mockito.doCallRealMethod()
                    .doThrow(new DataAccessResourceFailureException("the probe cannot be answered"))
                    .when(refusingTheProbe).query(Mockito.any(PreparedStatementCreator.class),
                            Mockito.<ResultSetExtractor<Object>>any());
            TransactionRepository spied = new TransactionRepository(refusingTheProbe, validBindings(),
                    ASCII, RecordImageForm.CHARACTER, ORDINAL);

            ReadResult result = spied.readByTranId("0000000000000009");

            assertThat(result.isNotFound())
                    .as("the probe established nothing, so the absence stays unproved")
                    .isFalse();
            assertThat(result.status()).isEqualTo(TransactionRepository.PERMANENT_ERROR_STATUS);
        }

        @Test
        @DisplayName("two records under one key report '22' and carry the first, as CICS does")
        void aLostUniqueKeyReportsDuplicate() {
            TranRecord first = record("0000000000000001");
            TranRecord second = record("0000000000000001");
            second.moveTranDesc("A SECOND RECORD UNDER ONE KEY");
            seed(MASTER_DS, first);
            seed(MASTER_DS, second);

            ReadResult duplicate = repository.readByTranId("0000000000000001");

            assertThat(duplicate.isDuplicate()).isTrue();
            assertThat(duplicate.isRecordReturned()).isTrue();
            assertThat(duplicate.status()).isEqualTo(FileStatus.DUPLICATE);
            assertThat(duplicate.cicsResp()).hasValue(FileStatus.DUPREC);
            assertThat(duplicate.requireRecord().tranId()).isEqualTo("0000000000000001");
        }

        @Test
        @DisplayName("a refused read reports the permanent status and carries the driver's diagnosis")
        void aRefusedReadIsReported() {
            ReadResult refused = repositoryOverMissingRelations().readByTranId("0000000000000001");

            assertThat(refused.isOther()).isTrue();
            assertThat(refused.status()).isEqualTo(TransactionRepository.PERMANENT_ERROR_STATUS);
            assertThat(refused.diagnostic()).isPresent();
        }

        @Test
        @DisplayName("a refused locking read is reported the same way, naming the UPDATE option it used")
        void aRefusedLockingReadIsReported() {
            TransactionRepository absent = repositoryOverMissingRelations();
            DatasetUnitOfWork unitOfWork =
                    new DatasetUnitOfWork(new JdbcTransactionManager(dataSource));

            ReadResult refused = unitOfWork.execute("a locking read of an unreachable master",
                    () -> absent.readForUpdateByTranId("0000000000000001"));

            assertThat(refused.isOther()).isTrue();
            assertThat(refused.isFound()).isFalse();
            assertThat(refused.status()).isEqualTo(TransactionRepository.PERMANENT_ERROR_STATUS);
            assertThat(refused.diagnostic()).isPresent();
        }

        @Test
        @DisplayName("READ ... UPDATE requires an open unit of work, because a lock outside one is void")
        void readForUpdateRequiresAUnitOfWork() {
            seed(MASTER_DS, record("0000000000000001"));

            assertThatIllegalStateException()
                    .isThrownBy(() -> repository.readForUpdateByTranId("0000000000000001"));

            DatasetUnitOfWork unitOfWork =
                    new DatasetUnitOfWork(new JdbcTransactionManager(dataSource));
            ReadResult locked = unitOfWork.execute("a locking read of the transaction master",
                    () -> repository.readForUpdateByTranId("0000000000000001"));
            assertThat(locked.isFound()).isTrue();
            assertThat(locked.requireRecord().tranId()).isEqualTo("0000000000000001");
        }
    }

    // =================================================================================================
    // The browse.
    // =================================================================================================

    @Nested
    @DisplayName("The browse walks the master forward and backward, from a boundary or from a key")
    class BrowseBehaviour {

        @BeforeEach
        void seedThreeRecords() {
            seed(MASTER_DS, record("0000000000000002"));
            seed(MASTER_DS, record("0000000000000001"));
            seed(MASTER_DS, record("0000000000000003"));
        }

        @Test
        @DisplayName("LOW-VALUES forward: from the first record, in ascending key order, then ENDFILE")
        void forwardFromTheStart() {
            try (Browse browse = repository.startBrowse(BrowseDirection.FORWARD)) {
                assertThat(browse.direction()).isEqualTo(BrowseDirection.FORWARD);
                assertThat(browse.anchorKey()).isEmpty();
                assertThat(browse.positionKey()).isEmpty();
                assertThat(browse.readNext().requireRecord().tranId()).isEqualTo("0000000000000001");
                assertThat(browse.positionKey()).contains("0000000000000001");
                assertThat(browse.readNext().requireRecord().tranId()).isEqualTo("0000000000000002");
                assertThat(browse.readNext().requireRecord().tranId()).isEqualTo("0000000000000003");
                ReadResult end = browse.readNext();
                assertThat(end.isEndOfFile()).isTrue();
                assertThat(end.status()).isEqualTo(FileStatus.END_OF_FILE);
                assertThat(end.cicsResp()).hasValue(FileStatus.ENDFILE);
                assertThat(end.applResult()).isEqualTo(FileStatus.APPL_EOF);
                // Repeating it reports the end of the file again rather than wrapping round.
                assertThat(browse.readNext().isEndOfFile()).isTrue();
                assertThat(browse.isEnded()).isFalse();
            }
        }

        @Test
        @DisplayName("HIGH-VALUES backward: from the LAST record - how COTRN02C finds the next id")
        void backwardFromTheEnd() {
            try (Browse browse = repository.startBrowse(BrowseDirection.BACKWARD)) {
                assertThat(browse.readPrev().requireRecord().tranId()).isEqualTo("0000000000000003");
                assertThat(browse.readPrev().requireRecord().tranId()).isEqualTo("0000000000000002");
                assertThat(browse.readPrev().requireRecord().tranId()).isEqualTo("0000000000000001");
                assertThat(browse.readPrev().isEndOfFile()).isTrue();
            }
        }

        @Test
        @DisplayName("an empty master reports ENDFILE at once, which is COTRN02C's MOVE ZEROS arm")
        void backwardOverAnEmptyMaster() {
            template.update("DELETE FROM \"" + MASTER_DS + "\"");
            try (Browse browse = repository.startBrowse(BrowseDirection.BACKWARD)) {
                // Nothing satisfies the position, so the STARTBR reports NOTFND and starts no browse.
                assertThat(browse.positioningResult().isNotFound()).isTrue();
                assertThat(browse.isStarted()).isFalse();
                // A read of a browse that was never started is an invalid request rather than an end of
                // file: there is no browse to have reached the end of.
                assertThat(browse.readPrev().cicsResp()).hasValue(FileStatus.INVREQ);
            }
        }

        @Test
        @DisplayName("a forward anchor is INCLUSIVE of the record whose key it names")
        void forwardFromAKeyIncludesIt() {
            try (Browse browse =
                    repository.startBrowse("0000000000000002", BrowseDirection.FORWARD)) {
                assertThat(browse.anchorKey()).contains("0000000000000002");
                assertThat(browse.readNext().requireRecord().tranId()).isEqualTo("0000000000000002");
                assertThat(browse.readNext().requireRecord().tranId()).isEqualTo("0000000000000003");
                assertThat(browse.readNext().isEndOfFile()).isTrue();
            }
        }

        @Test
        @DisplayName("a backward anchor is INCLUSIVE too - the record COCRDLIC deliberately discards")
        void backwardFromAKeyIncludesIt() {
            try (Browse browse =
                    repository.startBrowse("0000000000000002", BrowseDirection.BACKWARD)) {
                assertThat(browse.readPrev().requireRecord().tranId()).isEqualTo("0000000000000002");
                assertThat(browse.readPrev().requireRecord().tranId()).isEqualTo("0000000000000001");
                assertThat(browse.readPrev().isEndOfFile()).isTrue();
            }
        }

        @Test
        @DisplayName("a forward anchor above every key, and a backward anchor below every key, end at once")
        void anchorsOutsideTheKeyRange() {
            // No key is at or after the first anchor, and none is at or before the second, so both
            // positions report NOTFND and neither browse is started.
            try (Browse forward = repository.startBrowse("9999999999999999", BrowseDirection.FORWARD)) {
                assertThat(forward.positioningResult().isNotFound()).isTrue();
                assertThat(forward.isStarted()).isFalse();
                assertThat(forward.readNext().cicsResp()).hasValue(FileStatus.INVREQ);
            }
            try (Browse backward =
                    repository.startBrowse("0000000000000000", BrowseDirection.BACKWARD)) {
                assertThat(backward.positioningResult().isNotFound()).isTrue();
                assertThat(backward.isStarted()).isFalse();
                assertThat(backward.readPrev().cicsResp()).hasValue(FileStatus.INVREQ);
            }
        }

        @Test
        @DisplayName("an anchor that names no record positions at the next one, as GTEQ does")
        void anAnchorThatMatchesNoRecord() {
            try (Browse forward =
                    repository.startBrowse("00000000000000015", BrowseDirection.FORWARD)) {
                // The key truncates to '0000000000000001' on the PIC X(16) move, so it names a record.
                assertThat(forward.readNext().requireRecord().tranId()).isEqualTo("0000000000000001");
            }
            seed(MASTER_DS, record("0000000000000005"));
            try (Browse backward =
                    repository.startBrowse("0000000000000004", BrowseDirection.BACKWARD)) {
                assertThat(backward.readPrev().requireRecord().tranId()).isEqualTo("0000000000000003");
            }
        }

        @Test
        @DisplayName("a browse accepts only the read its direction was positioned for")
        void aBrowseIsNeverReversed() {
            try (Browse forward = repository.startBrowse(BrowseDirection.FORWARD)) {
                ReadResult reversed = forward.readPrev();
                assertThat(reversed.isOther()).isTrue();
                assertThat(reversed.cicsResp()).hasValue(FileStatus.INVREQ);
            }
            try (Browse backward = repository.startBrowse(BrowseDirection.BACKWARD)) {
                assertThat(backward.readNext().cicsResp()).hasValue(FileStatus.INVREQ);
            }
        }

        @Test
        @DisplayName("a read after ENDBR is an invalid request, not a silently resumed browse")
        void aReadAfterEndBrowseIsInvalid() {
            Browse browse = repository.startBrowse(BrowseDirection.FORWARD);
            assertThat(browse.readNext().isFound()).isTrue();
            browse.endBrowse();
            assertThat(browse.isEnded()).isTrue();
            // Ending twice does nothing, which is what makes close() safe after an explicit endBrowse.
            browse.close();
            ReadResult afterEnd = browse.readNext();
            assertThat(afterEnd.isOther()).isTrue();
            assertThat(afterEnd.cicsResp()).hasValue(FileStatus.INVREQ);
        }

        @Test
        @DisplayName("ENDBR releases the cursor, so the next STARTBR is positioned afresh")
        void aFreshBrowseAfterEndBrowseStartsClean() {
            // app/cbl/COTRN00C.cbl walks a page with STARTBR (~593) / READNEXT (~626) and then issues
            // ENDBR (~694) before the transaction returns; the next invocation issues its own STARTBR.
            // If ENDBR left position behind, the second page would resume mid-file and COTRN02C's
            // backward walk (~644/675/704), which exists purely to find the highest existing key, would
            // read the wrong key and mint a duplicate transaction id.
            Browse first = repository.startBrowse(BrowseDirection.FORWARD);
            assertThat(first.readNext().requireRecord().tranId()).isEqualTo("0000000000000001");
            assertThat(first.readNext().requireRecord().tranId()).isEqualTo("0000000000000002");
            assertThat(first.positionKey()).contains("0000000000000002");
            first.endBrowse();
            assertThat(first.isEnded()).isTrue();

            try (Browse second = repository.startBrowse(BrowseDirection.FORWARD)) {
                // Clean: no anchor, no inherited position, and the first record again - not the third.
                assertThat(second.isEnded()).isFalse();
                assertThat(second.anchorKey()).isEmpty();
                assertThat(second.positionKey()).isEmpty();
                assertThat(second.readNext().requireRecord().tranId()).isEqualTo("0000000000000001");
            }
            // A backward browse opened after the same ENDBR is equally unaffected by it.
            try (Browse third = repository.startBrowse(BrowseDirection.BACKWARD)) {
                assertThat(third.readPrev().requireRecord().tranId()).isEqualTo("0000000000000003");
            }
        }

        @Test
        @DisplayName("a direction is required, because the legacy code never browses direction-less")
        void aDirectionIsRequired() {
            assertThatNullPointerException()
                    .isThrownBy(() -> repository.startBrowse((BrowseDirection) null));
            assertThatNullPointerException()
                    .isThrownBy(() -> repository.startBrowse("0000000000000001", null));
            assertThatNullPointerException()
                    .isThrownBy(() -> repository.startBrowse(null, BrowseDirection.FORWARD));
        }

        @Test
        @DisplayName("a refused browse reports the failure rather than an empty file")
        void aRefusedBrowseIsReported() {
            try (Browse browse = repositoryOverMissingRelations()
                    .startBrowse(BrowseDirection.FORWARD)) {
                // The refusal strikes on the position, because that is the operation that touches the
                // relation first, so it is the positioning outcome that carries the diagnostic.
                ReadResult refused = browse.positioningResult();
                assertThat(refused.isOther()).isTrue();
                assertThat(refused.isEndOfFile()).isFalse();
                assertThat(refused.diagnostic()).isPresent();
                assertThat(browse.isStarted()).isFalse();
                // The read that follows reports the invalid request, having no browse to read.
                assertThat(browse.readNext().cicsResp()).hasValue(FileStatus.INVREQ);
            }
        }
    }

    // =================================================================================================
    // The sequential input path.
    // =================================================================================================

    @Nested
    @DisplayName("The sequential input reads a KSDS in key order and a PS dataset in written order")
    class SequentialInput {

        @Test
        @DisplayName("an indexed binding is read in ascending key order, one row per read")
        void anIndexedBindingIsReadInKeyOrder() {
            seed(MASTER_DS, record("0000000000000002"));
            seed(MASTER_DS, record("0000000000000001"));

            try (InputFile input = repository.openInput()) {
                assertThat(input.openStatus()).isEqualTo(FileStatus.OK);
                assertThat(input.openOutcome()).isEqualTo(Outcome.OK);
                assertThat(input.openApplResult()).isEqualTo(FileStatus.APPL_AOK);
                assertThat(input.isOpen()).isTrue();
                assertThat(input.position()).isZero();
                assertThat(input.ddName()).isEqualTo(TransactionRepository.INPUT_DD_NAME);
                assertThat(input.datasetName()).contains(MASTER_DS);

                assertThat(input.readNext().requireRecord().tranId()).isEqualTo("0000000000000001");
                assertThat(input.readNext().requireRecord().tranId()).isEqualTo("0000000000000002");
                ReadResult end = input.readNext();
                assertThat(end.isEndOfFile()).isTrue();
                assertThat(end.applResult()).isEqualTo(FileStatus.APPL_EOF);
                // Idempotent, exactly as CBTRN03C's END-OF-FILE flag is.
                assertThat(input.readNext().isEndOfFile()).isTrue();
                assertThat(input.position()).isEqualTo(2);
                assertThat(input.closeInput()).isEqualTo(FileStatus.OK);
                assertThat(input.closeApplResult()).isEqualTo(FileStatus.APPL_AOK);
                assertThat(input.isOpen()).isFalse();
            }
        }

        @Test
        @DisplayName("a job-scoped sequential binding is read in the order its records were written")
        void aSequentialBindingIsReadInWrittenOrder() {
            // Seeded in an order that is neither ascending nor descending by key, so no ordering over the
            // record image - in either direction - could reproduce it by accident. What does reproduce it
            // is the physical-record ordinal, which stands for a record's position and for nothing in its
            // content: ordering by the image would reorder the file the SORT step produced, since
            // app/jcl/TRANREPT.jcl:46 sorts by TRAN-CARD-NUM while the image begins with TRAN-ID.
            seed(DALY_DS, record("0000000000000002"));
            seed(DALY_DS, record("0000000000000003"));
            seed(DALY_DS, record("0000000000000001"));

            try (InputFile input = repository.openInput(sequential(DALY_DS))) {
                assertThat(input.openStatus()).isEqualTo(FileStatus.OK);
                assertThat(input.datasetName()).contains(DALY_DS);
                assertThat(input.readNext().requireRecord().tranId()).isEqualTo("0000000000000002");
                assertThat(input.readNext().requireRecord().tranId()).isEqualTo("0000000000000003");
                assertThat(input.readNext().requireRecord().tranId()).isEqualTo("0000000000000001");
                assertThat(input.readNext().isEndOfFile()).isTrue();
                assertThat(input.readNext().isEndOfFile()).isTrue();
                assertThat(input.position()).isEqualTo(3);
            }
        }

        @Test
        @DisplayName("the same pass repeated returns the same sequence, which is what an order contract "
                + "buys and an unordered select does not")
        void aSequentialPassIsRepeatable() {
            // SQL guarantees no row order without an ORDER BY, so an unordered select's sequence is a
            // property of the backend's scan rather than of the dataset - and two passes are entitled to
            // differ. The ordinal is what makes the two agree, which is what CBTRN03C's account
            // subtotalling and CBTRN02C's reject sequence both rest on.
            seed(DALY_DS, record("0000000000000009"));
            seed(DALY_DS, record("0000000000000004"));
            seed(DALY_DS, record("0000000000000007"));

            List<String> first = new ArrayList<>();
            List<String> second = new ArrayList<>();
            for (List<String> pass : List.of(first, second)) {
                try (InputFile input = repository.openInput(sequential(DALY_DS))) {
                    for (ReadResult read = input.readNext(); read.isRecordReturned();
                            read = input.readNext()) {
                        pass.add(read.requireRecord().tranId());
                    }
                }
            }

            assertThat(first).containsExactly("0000000000000009", "0000000000000004",
                    "0000000000000007");
            assertThat(second).isEqualTo(first);
        }

        @Test
        @DisplayName("a physical-sequential pass streams through one cursor and releases it at close")
        void aSequentialPassStreamsThroughOneCursor() throws SQLException {
            // The defect this replaces: the first READ drained the whole relation into a List<byte[]>
            // and later reads handed rows out of it, so a READ of the first record cost the whole
            // dataset in heap - unbounded by anything in the source, since neither CBTRN03C nor
            // CBTRN01C bounds how many records a generation holds.
            //
            // What proves the fix is not the absence of a list but the presence of a cursor, and a
            // cursor is a connection held across reads. So the connections are counted.
            seed(DALY_DS, record("0000000000000003"));
            seed(DALY_DS, record("0000000000000002"));
            seed(DALY_DS, record("0000000000000001"));
            RecordingDataSource recording = new RecordingDataSource(dataSource);
            TransactionRepository subject = new TransactionRepository(new JdbcTemplate(recording),
                    validBindings(), ASCII, RecordImageForm.CHARACTER, ORDINAL);

            InputFile input = subject.openInput(sequential(DALY_DS));

            assertThat(input.openStatus()).isEqualTo(FileStatus.OK);
            // The open describes the relation and gives its connection straight back, so an opened pass
            // that is never read holds nothing.
            assertThat(recording.liveConnections()).isZero();

            // Every read walks the same cursor: one connection from the first read onward, never one per
            // row and never none.
            assertThat(input.readNext().requireRecord().tranId()).isEqualTo("0000000000000003");
            assertThat(recording.liveConnections()).isEqualTo(1);
            assertThat(input.readNext().requireRecord().tranId()).isEqualTo("0000000000000002");
            assertThat(recording.liveConnections()).isEqualTo(1);
            assertThat(input.readNext().requireRecord().tranId()).isEqualTo("0000000000000001");
            assertThat(recording.liveConnections()).isEqualTo(1);
            assertThat(input.readNext().isEndOfFile()).isTrue();
            assertThat(recording.liveConnections()).isEqualTo(1);

            // And the close releases it, which is the other half of the contract: a job that opened a
            // pass and closed it leaks no connection.
            assertThat(input.closeInput()).isEqualTo(FileStatus.OK);
            assertThat(recording.liveConnections()).isZero();
            // Idempotent, and the same answer - the release is not attempted twice.
            assertThat(input.closeInput()).isEqualTo(FileStatus.OK);
            assertThat(input.closeApplResult()).isEqualTo(FileStatus.APPL_AOK);
        }

        @Test
        @DisplayName("the pass is prepared forward-only, read-only, with a positive fetch size stated")
        void theCursorIsForwardOnlyReadOnlyAndBounded() throws SQLException {
            // Forward-only and read-only is the JDBC statement of ORGANIZATION SEQUENTIAL with
            // ACCESS MODE IS SEQUENTIAL on an OPEN INPUT: no consumer repositions and none writes. The
            // stated fetch size is what makes the buffering a bound rather than the driver's own default,
            // which for several drivers is the whole result set - the very thing being removed here.
            //
            // Asserted through a spy over the real driver rather than a mock of one, so the statement
            // that is inspected is the statement the driver actually accepted.
            seed(DALY_DS, record("0000000000000001"));
            RecordingDataSource recording = new RecordingDataSource(dataSource);
            TransactionRepository subject = new TransactionRepository(new JdbcTemplate(recording),
                    validBindings(), ASCII, RecordImageForm.CHARACTER, ORDINAL);

            try (InputFile input = subject.openInput(sequential(DALY_DS))) {
                assertThat(input.readNext().isFound()).isTrue();
            }

            assertThat(recording.cursorStatements()).hasSize(1);
            Mockito.verify(recording.cursorStatements().get(0))
                    .setFetchSize(ArgumentMatchers.intThat(size -> size > 0));
        }

        @Test
        @DisplayName("closing an unread physical-sequential pass releases nothing and still reports OK")
        void closingAnUnreadSequentialPassReportsOk() throws SQLException {
            RecordingDataSource recording = new RecordingDataSource(dataSource);
            TransactionRepository subject = new TransactionRepository(new JdbcTemplate(recording),
                    validBindings(), ASCII, RecordImageForm.CHARACTER, ORDINAL);

            InputFile input = subject.openInput(sequential(DALY_DS));

            assertThat(input.closeInput()).isEqualTo(FileStatus.OK);
            assertThat(input.closeApplResult()).isEqualTo(FileStatus.APPL_AOK);
            assertThat(recording.liveConnections()).isZero();
        }

        @Test
        @DisplayName("try-with-resources releases the cursor even when the body never closes the pass")
        void tryWithResourcesReleasesTheCursor() throws SQLException {
            seed(DALY_DS, record("0000000000000001"));
            RecordingDataSource recording = new RecordingDataSource(dataSource);
            TransactionRepository subject = new TransactionRepository(new JdbcTemplate(recording),
                    validBindings(), ASCII, RecordImageForm.CHARACTER, ORDINAL);

            try (InputFile input = subject.openInput(sequential(DALY_DS))) {
                assertThat(input.readNext().isFound()).isTrue();
                assertThat(recording.liveConnections()).isEqualTo(1);
            }

            assertThat(recording.liveConnections()).isZero();
        }

        @Test
        @DisplayName("CBTRN01C's dead path: open and close with no read between them, both reported")
        void openAndCloseWithNoRead() {
            try (InputFile input = repository.openInput()) {
                assertThat(input.openStatus()).isEqualTo(FileStatus.OK);
                assertThat(input.closeInput()).isEqualTo(FileStatus.OK);
            }
        }

        @Test
        @DisplayName("an unreachable dataset fails the OPEN, and a read of it reports the open's status")
        void anUnreachableDatasetFailsTheOpen() {
            InputFile input = repositoryOverMissingRelations().openInput();

            assertThat(input.openStatus()).isEqualTo(TransactionRepository.PERMANENT_ERROR_STATUS);
            assertThat(input.openOutcome()).isEqualTo(Outcome.OTHER);
            assertThat(input.openApplResult()).isEqualTo(TransactionRepository.APPL_RESULT_FATAL);
            assertThat(input.isOpen()).isFalse();
            ReadResult read = input.readNext();
            assertThat(read.isOther()).isTrue();
            assertThat(read.status()).isEqualTo(TransactionRepository.PERMANENT_ERROR_STATUS);
            // A CLOSE of a file that never opened is not a success either.
            assertThat(input.closeInput()).isEqualTo(TransactionRepository.PERMANENT_ERROR_STATUS);
            assertThat(input.closeApplResult()).isEqualTo(TransactionRepository.APPL_RESULT_FATAL);
        }

        @Test
        @DisplayName("a job-scoped binding that names no dataset reports a failed open, and never throws")
        void aJobScopedBindingThatIsNotADatasetName() {
            DatasetBinding filesystemLocation = new DatasetBinding("/tmp/work/transact-daly.txt",
                    "sequential", false, "FB", 0, RECORD_LENGTH, "CVTRA05Y", null, null, null, null);

            InputFile input = repository.openInput(filesystemLocation);

            assertThat(input.openStatus()).isEqualTo(TransactionRepository.PERMANENT_ERROR_STATUS);
            assertThat(input.datasetName()).isEmpty();
            assertThat(input.readNext().isOther()).isTrue();
        }

        @Test
        @DisplayName("a job-scoped binding is held to the copybook width, and required at all")
        void aJobScopedBindingIsValidated() {
            assertThatNullPointerException()
                    .isThrownBy(() -> repository.openInput((DatasetBinding) null));
            DatasetBinding wrongWidth = new DatasetBinding(DALY_DS, "sequential", false, "FB", 0,
                    RECORD_LENGTH + 1, "CVTRA05Y", null, null, null, null);
            assertThatIllegalStateException().isThrownBy(() -> repository.openInput(wrongWidth));
        }

        @Test
        @DisplayName("a refused read reports the READ's own failure and leaves the pass unfetched")
        void aRefusedSequentialReadIsReported() {
            InputFile input = repository.openInput(sequential(DALY_DS));
            template.execute("DROP TABLE \"" + DALY_DS + "\"");

            ReadResult refused = input.readNext();

            assertThat(refused.isOther()).isTrue();
            assertThat(refused.isEndOfFile()).isFalse();
            assertThat(refused.diagnostic()).isPresent();
            // The position did not move, so a caller that retries retries the same read.
            assertThat(input.position()).isZero();
        }

        @Test
        @DisplayName("reading a closed pass is a defect in the caller, and throws rather than reporting")
        void readingAClosedPassThrows() {
            InputFile input = repository.openInput();
            input.closeInput();
            assertThatIllegalStateException().isThrownBy(input::readNext)
                    .withMessageContaining("has been closed");
        }

        @Test
        @DisplayName("a row that is not 350 bytes stops the pass and reports the width it measured")
        void aRowOfTheWrongWidthIsReported() {
            seedRaw(RAGGED_DS, "TOO SHORT");

            try (InputFile input = repository.openInput(sequential(RAGGED_DS))) {
                ReadResult malformed = input.readNext();
                assertThat(malformed.isOther()).isTrue();
                assertThat(malformed.cicsResp()).hasValue(FileStatus.LENGERR);
                assertThat(malformed.observation()).contains(DatasetObservation.recordWidth(9));
                // The pass stopped rather than re-reading a row it cannot advance past.
                assertThat(input.readNext().isEndOfFile()).isTrue();
            }
        }

        @Test
        @DisplayName("a row whose record image is absent is an I/O defect, not an end of file")
        void aRowWithNoRecordImageIsReported() {
            seedRaw(RAGGED_DS, null);

            try (InputFile input = repository.openInput(sequential(RAGGED_DS))) {
                ReadResult absent = input.readNext();
                assertThat(absent.isOther()).isTrue();
                assertThat(absent.isEndOfFile()).isFalse();
                assertThat(absent.status()).isEqualTo(TransactionRepository.PERMANENT_ERROR_STATUS);
            }
        }
    }

    // =================================================================================================
    // The sequential output path.
    // =================================================================================================

    @Nested
    @DisplayName("The sequential output writes CBACT04C's generated transactions front to back")
    class SequentialOutput {

        @Test
        @DisplayName("records are written in call order, each at exactly 350 bytes, and counted")
        void recordsAreWrittenInOrder() {
            // app/jcl/INTCALC.jcl:37-41 defines this DD as DISP=(NEW,CATLG,DELETE) with
            // DCB=(RECFM=F,LRECL=350,BLKSIZE=0) over DSN=...SYSTRAN(+1). RECFM=F with LRECL=350 is an
            // UNBLOCKED FIXED file: every record occupies exactly 350 bytes, so a row of any other
            // width is not a short record - it is a corrupt dataset. This repository is where that
            // width is enforced, and app/cbl/CBACT04C.cbl:309/500/597 is the only run that opens it.
            try (OutputFile output = repository.openOutput()) {
                assertThat(output.openStatus()).isEqualTo(FileStatus.OK);
                assertThat(output.openOutcome()).isEqualTo(Outcome.OK);
                assertThat(output.openApplResult()).isEqualTo(FileStatus.APPL_AOK);
                assertThat(output.isOpen()).isTrue();
                assertThat(output.insertStatement()).contains(SYSTRAN_DS).contains("VALUES (?)");
                assertThat(output.recordsWritten()).isZero();

                // Written in DESCENDING key order deliberately: a sequential file has no key, so an
                // ORDER BY anywhere on this path would reorder them and the assertion below would fail.
                assertThat(output.writeSequential(record("2022071800002")).isWritten()).isTrue();
                assertThat(output.writeSequential(record("2022071800001")).isWritten()).isTrue();
                assertThat(output.recordsWritten()).isEqualTo(2);
                assertThat(output.closeOutput()).isEqualTo(FileStatus.OK);
                assertThat(output.closeApplResult()).isEqualTo(FileStatus.APPL_AOK);
                assertThat(output.isOpen()).isFalse();
            }

            List<String> stored = template.queryForList(
                    "SELECT \"" + IMAGE_COLUMN + "\" FROM \"" + SYSTRAN_DS + "\"", String.class);

            // Gate G19: exactly 350 bytes per row, not "at least" and not "about".
            assertThat(stored).hasSize(2).allSatisfy(image -> {
                assertThat(image).hasSize(RECORD_LENGTH);
                assertThat(image.getBytes(ASCII)).hasSize(RECORD_LENGTH);
            });
            // Call order, read back off the key span at offset 0 rather than off a decoded field.
            assertThat(stored)
                    .extracting(image -> image.substring(TranRecord.TRAN_ID_OFFSET,
                            TranRecord.TRAN_ID_OFFSET + TranRecord.TRAN_ID_LENGTH))
                    .containsExactly("2022071800002   ", "2022071800001   ");
        }

        @Test
        @DisplayName("an unaddressable destination fails the OPEN, not the first write")
        void anUnaddressableDestinationFailsTheOpen() {
            // app/cbl/CBACT04C.cbl:307-323 status-checks OPEN OUTPUT exactly as it status-checks the
            // write at :501-514 and the close at :598, and displays 'ERROR OPENING TRANSACTION FILE' at
            // :318 on the failing arm. Before this, the open could not fail at all, so that arm was
            // unreachable and an unusable destination first appeared as 'ERROR WRITING TRANSACTION
            // RECORD' - the wrong paragraph, for a condition that was true before the first record
            // existed.
            try (OutputFile output = repositoryOverMissingRelations().openOutput()) {
                assertThat(output.openStatus())
                        .isEqualTo(TransactionRepository.PERMANENT_ERROR_STATUS);
                assertThat(output.openOutcome()).isEqualTo(Outcome.OTHER);
                assertThat(output.openApplResult())
                        .isEqualTo(TransactionRepository.APPL_RESULT_FATAL);
            }
        }

        @Test
        @DisplayName("the OPEN probe writes no record, so a refused open leaves the generation empty")
        void theOpenProbeWritesNothing() {
            try (OutputFile output = repository.openOutput()) {
                assertThat(output.openStatus()).isEqualTo(FileStatus.OK);
                // The probe prepared the insert and released it; it did not execute it.
                assertThat(output.recordsWritten()).isZero();
            }

            assertThat(template.queryForObject(
                    "SELECT COUNT(*) FROM \"" + SYSTRAN_DS + "\"", Integer.class)).isZero();
        }

        @Test
        @DisplayName("a sequential dataset has no key, so the same record may be written twice")
        void aSequentialOutputHasNoDuplicateOutcome() {
            try (OutputFile output = repository.openOutput()) {
                assertThat(output.writeSequential(record("2022071800001")).isWritten()).isTrue();
                assertThat(output.writeSequential(record("2022071800001")).isWritten()).isTrue();
                assertThat(output.recordsWritten()).isEqualTo(2);
            }
        }

        @Test
        @DisplayName("a refused write reports the permanent status and the driver's own diagnosis")
        void aRefusedWriteIsReported() {
            try (OutputFile output = repositoryOverMissingRelations().openOutput()) {
                WriteResult refused = output.writeSequential(record("2022071800001"));
                assertThat(refused.isOther()).isTrue();
                assertThat(refused.status()).isEqualTo(TransactionRepository.PERMANENT_ERROR_STATUS);
                assertThat(refused.diagnostic()).isPresent();
                assertThat(output.recordsWritten()).isZero();
            }
        }

        @Test
        @DisplayName("the OPEN OUTPUT empties the generation, because INTCALC.jcl declares it "
                + "DISP=(NEW,CATLG,DELETE) - it does not append to the last run")
        void theOpenEmptiesTheGeneration() {
            // A run writes into a brand-new generation, so last run's generated interest transactions
            // are not part of this one. Before the open cleared, a second run over the same configured
            // destination left both runs' records in it - which is OPEN EXTEND, a verb CBACT04C does
            // not issue.
            try (OutputFile first = repository.openOutput()) {
                assertThat(first.writeSequential(record("2022071800001")).isWritten()).isTrue();
            }
            assertThat(template.queryForObject(
                    "SELECT COUNT(*) FROM \"" + SYSTRAN_DS + "\"", Integer.class)).isOne();

            try (OutputFile second = repository.openOutput()) {
                assertThat(second.openStatus()).isEqualTo(FileStatus.OK);
                // Emptied by the open itself, before a single record of this run was written.
                assertThat(template.queryForObject(
                        "SELECT COUNT(*) FROM \"" + SYSTRAN_DS + "\"", Integer.class)).isZero();
                assertThat(second.writeSequential(record("2022071800002")).isWritten()).isTrue();
            }

            List<String> stored = template.queryForList(
                    "SELECT \"" + IMAGE_COLUMN + "\" FROM \"" + SYSTRAN_DS + "\"", String.class);
            assertThat(stored).hasSize(1);
            assertThat(stored.get(0)).startsWith("2022071800002");
        }

        @Test
        @DisplayName("a run that writes nothing still leaves the empty generation the JCL created")
        void aRunThatWritesNothingLeavesTheEmptyGeneration() {
            // CBACT04C computes no interest when no category balance qualifies, and the JCL still
            // creates SYSTRAN(+1). An empty dataset and an absent one are different states downstream.
            try (OutputFile output = repository.openOutput()) {
                assertThat(output.recordsWritten()).isZero();
                assertThat(output.closeOutput()).isEqualTo(FileStatus.OK);
            }

            assertThat(template.queryForObject(
                    "SELECT COUNT(*) FROM \"" + SYSTRAN_DS + "\"", Integer.class)).isZero();
        }

        @Test
        @DisplayName("a destination that cannot be established fails the OPEN, and the CLOSE reports "
                + "the same - the ladder at CBACT04C:310-314 and :598-602")
        void aRefusedOpenIsReportedByBothTheOpenAndTheClose() {
            // 'ERROR OPENING TRANSACTION FILE' (:318-321) is what an absent destination must produce,
            // and it must produce it from the OPEN rather than 350 bytes later from the first write.
            OutputFile output = repositoryOverMissingRelations().openOutput();

            assertThat(output.openStatus())
                    .isEqualTo(TransactionRepository.PERMANENT_ERROR_STATUS);
            assertThat(output.openOutcome()).isEqualTo(Outcome.OTHER);
            assertThat(output.openApplResult())
                    .isEqualTo(TransactionRepository.APPL_RESULT_FATAL);

            // A CLOSE of a file that never opened is not a success, which is the same rule the read
            // side applies.
            assertThat(output.closeOutput())
                    .isEqualTo(TransactionRepository.PERMANENT_ERROR_STATUS);
            assertThat(output.closeApplResult())
                    .isEqualTo(TransactionRepository.APPL_RESULT_FATAL);
            // Idempotent, and it does not change its mind on the second call.
            assertThat(output.closeOutput())
                    .isEqualTo(TransactionRepository.PERMANENT_ERROR_STATUS);
        }

        @Test
        @DisplayName("the APPL-RESULT of a close that has not happened yet is the assumed-failure "
                + "value CBACT04C holds at :596, never a success")
        void theCloseApplResultBeforeTheCloseIsTheAssumedFailure() {
            try (OutputFile output = repository.openOutput()) {
                assertThat(output.closeApplResult())
                        .isEqualTo(TransactionRepository.APPL_RESULT_FATAL);
                assertThat(output.closeOutput()).isEqualTo(FileStatus.OK);
                assertThat(output.closeApplResult()).isEqualTo(FileStatus.APPL_AOK);
            }
        }

        @Test
        @DisplayName("writing to a closed run is a defect in the caller, and a record is required")
        void writingToAClosedRunThrows() {
            OutputFile output = repository.openOutput();
            assertThatNullPointerException().isThrownBy(() -> output.writeSequential(null));
            output.closeOutput();
            // Closing twice is idempotent.
            assertThat(output.closeOutput()).isEqualTo(FileStatus.OK);
            assertThatIllegalStateException()
                    .isThrownBy(() -> output.writeSequential(record("2022071800001")))
                    .withMessageContaining("has been closed");
        }

        // ------------------------------------------------------- DISP=(NEW,CATLG,DELETE), third position

        @Test
        @DisplayName("the abnormal disposition discards the whole generation this run wrote")
        void theAbnormalDispositionDiscardsTheGeneration() {
            // app/jcl/INTCALC.jcl:37 declares DISP=(NEW,CATLG,DELETE). The third positional is the
            // ABNORMAL disposition: a step that abends leaves no generation at all, even though every
            // write it managed was durable as it completed (RECOVERY(NONE), app/csd/CARDDEMO.CSD:84).
            OutputFile output = repository.openOutput();
            assertThat(output.writeSequential(record("2022071800001")).isWritten()).isTrue();
            assertThat(output.writeSequential(record("2022071800002")).isWritten()).isTrue();
            assertThat(output.recordsWritten()).isEqualTo(2);

            assertThat(output.discardGeneration()).isEqualTo(FileStatus.OK);

            assertThat(template.queryForList("SELECT \"" + IMAGE_COLUMN + "\" FROM \"" + SYSTRAN_DS + "\"", String.class)).isEmpty();
            // Idempotent: a caller already abending must be able to apply it without guarding the call.
            assertThat(output.discardGeneration()).isEqualTo(FileStatus.OK);
            output.closeOutput();
        }

        @Test
        @DisplayName("a run that wrote nothing has nothing to discard, and issues no statement")
        void anEmptyGenerationNeedsNoDiscard() {
            try (OutputFile output = repository.openOutput()) {
                assertThat(output.recordsWritten()).isZero();
                assertThat(output.discardGeneration()).isEqualTo(FileStatus.OK);
            }
        }

        @Test
        @DisplayName("the discard is refused when the dataset holds records this run did not write")
        void theDiscardIsRefusedWhenTheGenerationIsNotThisRunsAlone() {
            // DISP=NEW means the step allocates its own generation, so a faithful deployment gives this
            // run a relation of its own. A deployment that instead maps successive generations onto one
            // relation would have this delete another generation's records - so it is refused, loudly,
            // rather than acted on.
            // The foreign record arrives AFTER the open, and it has to: the open applies DISP=NEW by
            // emptying the generation, so a record present beforehand is not there to be miscounted. What
            // the guard defends is the window between the open and the disposition - a second DD mapped
            // onto this relation, or a concurrent writer - and that is the scenario constructed here.
            OutputFile output = repository.openOutput();
            assertThat(output.writeSequential(record("2022071800001")).isWritten()).isTrue();
            template.update("INSERT INTO \"" + SYSTRAN_DS + "\" VALUES (?)",
                    new String(record("2022071700009").encode(ASCII), ASCII));

            assertThat(output.discardGeneration())
                    .isEqualTo(TransactionRepository.PERMANENT_ERROR_STATUS);

            assertThat(template.queryForList("SELECT \"" + IMAGE_COLUMN + "\" FROM \"" + SYSTRAN_DS + "\"", String.class))
                    .as("neither generation may be touched when the premise does not hold")
                    .hasSize(2);
            output.closeOutput();
        }

        @Test
        @DisplayName("a backend that refuses the discard reports a status rather than throwing")
        void aRefusedDiscardIsReported() {
            // The caller is already abending when it applies a disposition, so a disposition that cannot
            // be applied must not replace the abend that caused it.
            OutputFile output = repository.openOutput();
            assertThat(output.writeSequential(record("2022071800001")).isWritten()).isTrue();
            template.execute("DROP TABLE \"" + SYSTRAN_DS + "\"");

            assertThat(output.discardGeneration())
                    .isEqualTo(TransactionRepository.PERMANENT_ERROR_STATUS);
        }
    }

    // =================================================================================================
    // The failures a real backend will not produce on demand.
    // =================================================================================================

    @Nested
    @DisplayName("The arms a real backend will not produce on demand")
    class StubbedBackendArms {

        /**
         * A matcher for any result-set extractor, typed rather than raw.
         *
         * <p>{@code any(ResultSetExtractor.class)} yields a raw type, which makes every stubbed
         * {@code query} call an unchecked invocation. Inferring the parameter instead keeps the test
         * source warning-free under the build's {@code -Xlint:all}.
         *
         * @param <T> the extractor's result type, inferred at the call site
         * @return the matcher
         */
        private static <T> ResultSetExtractor<T> anyExtractor() {
            return ArgumentMatchers.any();
        }

        /** A repository over a stubbed template, so a refusal can be produced exactly where wanted. */
        private JdbcTemplate stub;

        /** The repository under test, over {@link #stub}. */
        private TransactionRepository stubbed;

        @BeforeEach
        void buildStub() {
            stub = mock(JdbcTemplate.class);
            stubbed = new TransactionRepository(stub, validBindings(), ASCII,
                    RecordImageForm.CHARACTER, ORDINAL);
        }

        @Test
        @DisplayName("a row the driver refuses to read stops the pass instead of being re-read for ever")
        void aRowRefusedOnReadStopsThePass() throws SQLException {
            // Two conditions look alike and must not be treated alike. A refusal before a row is reached
            // leaves the pass where it was. A refusal after one is reached is a record that is present
            // and unreadable, and the pass has to stop on it - otherwise a caller looping until end of
            // file, which is exactly what CBTRN03C:172 and CBTRN01C:169 do, would re-read that one row
            // without end and never terminate.
            ResultSetMetaData metaData = mock(ResultSetMetaData.class);
            doReturn(1).when(metaData).getColumnCount();
            doReturn(IMAGE_COLUMN).when(metaData).getColumnName(1);

            ResultSet describeRows = mock(ResultSet.class);
            doReturn(metaData).when(describeRows).getMetaData();
            Statement describeStatement = mock(Statement.class);
            doReturn(describeRows).when(describeStatement).executeQuery(anyString());

            ResultSet passRows = mock(ResultSet.class);
            doReturn(true).when(passRows).next();
            doThrow(new SQLException("the row cannot be read", "58005", 1))
                    .when(passRows).getString(ArgumentMatchers.anyInt());
            PreparedStatement passStatement = mock(PreparedStatement.class);
            doReturn(passRows).when(passStatement).executeQuery();

            Connection connection = mock(Connection.class);
            doReturn(describeStatement).when(connection).createStatement();
            doReturn(passStatement).when(connection).prepareStatement(anyString(),
                    ArgumentMatchers.anyInt(), ArgumentMatchers.anyInt());
            DataSource refusing = mock(DataSource.class);
            doReturn(connection).when(refusing).getConnection();

            TransactionRepository subject = new TransactionRepository(new JdbcTemplate(refusing),
                    validBindings(), ASCII, RecordImageForm.CHARACTER, ORDINAL);
            InputFile input = subject.openInput(sequential(DALY_DS));

            assertThat(input.openStatus()).isEqualTo(FileStatus.OK);
            ReadResult refused = input.readNext();
            assertThat(refused.isOther()).isTrue();
            assertThat(refused.status()).isEqualTo(TransactionRepository.PERMANENT_ERROR_STATUS);
            assertThat(refused.applResult()).isEqualTo(TransactionRepository.APPL_RESULT_FATAL);

            // Stopped: the next read reports the end rather than reaching the same unreadable row again.
            assertThat(input.readNext().isEndOfFile()).isTrue();
            assertThat(input.position()).isZero();
            Mockito.verify(passRows, Mockito.times(1)).getString(ArgumentMatchers.anyInt());
        }

        @Test
        @DisplayName("a describe that yields no record-image column fails the OPEN of a keyed dataset")
        void noRecordImageColumnFailsAKeyedOpen() {
            doReturn(null).when(stub).query(anyString(), anyExtractor());

            InputFile input = stubbed.openInput();

            assertThat(input.openStatus()).isEqualTo(TransactionRepository.PERMANENT_ERROR_STATUS);
            assertThat(input.readNext().isOther()).isTrue();
        }

        @Test
        @DisplayName("a describe that yields no column does NOT fail a sequential open, which needs none")
        void noRecordImageColumnStillOpensASequentialDataset() {
            doReturn(null).when(stub).query(anyString(), anyExtractor());

            InputFile input = stubbed.openInput(sequential(DALY_DS));

            // The point of the test: a physical-sequential dataset names no column, so the open does not
            // need one and does not fail for the want of it.
            assertThat(input.openStatus()).isEqualTo(FileStatus.OK);
            // The read is a separate matter, and over this stub it cannot succeed: a sequential read now
            // opens a forward-only cursor, which needs a DataSource, and a stubbed template carries none.
            // That is a wiring defect rather than a dataset condition, and it is reported through the
            // read's own WHEN OTHER arm - which is the arm a caller has - instead of being thrown into
            // the middle of a job.
            ReadResult read = input.readNext();
            assertThat(read.isOther()).isTrue();
            assertThat(read.isEndOfFile()).isFalse();
            assertThat(read.status()).isEqualTo(TransactionRepository.PERMANENT_ERROR_STATUS);
        }

        @Test
        @DisplayName("a template that yields no result object at all is reported, never taken as empty")
        void noResultObjectIsReported() {
            doReturn(IMAGE_COLUMN).when(stub).query(anyString(), anyExtractor());
            doReturn(null).when(stub).query(any(PreparedStatementCreator.class),
                    anyExtractor());

            // A keyed read of nothing is a missing record; a position that finds nothing is a NOTFND,
            // which is the condition a STARTBR raises for it. Both are reached from the same null
            // answer, which is why the two are distinguished by the caller's own access path rather
            // than by the answer.
            assertThat(stubbed.readByTranId("0000000000000001").isNotFound()).isTrue();
            try (Browse browse = stubbed.startBrowse(BrowseDirection.FORWARD)) {
                assertThat(browse.positioningResult().isNotFound()).isTrue();
                assertThat(browse.readNext().cicsResp()).hasValue(FileStatus.INVREQ);
            }
        }

        @Test
        @DisplayName("SQLSTATE class 23 on the insert is the duplicate condition, reported as '22'")
        void aReportedIntegrityViolationIsADuplicate() {
            doReturn(IMAGE_COLUMN).when(stub).query(anyString(), anyExtractor());
            doReturn(null).when(stub).query(any(PreparedStatementCreator.class),
                    anyExtractor());
            doThrow(new DataIntegrityViolationException("the relation enforced the key",
                    new SQLException("duplicate key", "23505", 23505)))
                    .when(stub).update(any(PreparedStatementCreator.class));

            WriteResult result = inUnitOfWork(
                    () -> stubbed.write(record("0000000000000001")));

            assertThat(result.isDuplicate()).isTrue();
            assertThat(result.status()).isEqualTo(FileStatus.DUPLICATE);
            assertThat(result.cicsResp()).hasValue(FileStatus.DUPREC);
            assertThat(result.diagnostic()).isPresent();
            assertThat(result.diagnostic().orElseThrow().integrityViolation()).isTrue();
        }

        @Test
        @DisplayName("a driver that reports a code and no SQLSTATE still yields '22', not an abend")
        void aClassifiedRefusalWithNoSqlStateIsStillADuplicate() {
            doReturn(IMAGE_COLUMN).when(stub).query(anyString(), anyExtractor());
            doReturn(null).when(stub).query(any(PreparedStatementCreator.class),
                    anyExtractor());
            // No SQLException in the chain, so no SQLSTATE: the shape Spring's vendor-error-code
            // translation produces for an adapter that reports only a numeric code. The condition must
            // still be the duplicate one, or which driver is deployed would decide whether CBTRN02C
            // abends on a duplicate key.
            doThrow(new DuplicateKeyException("the relation enforced the key"))
                    .when(stub).update(any(PreparedStatementCreator.class));

            WriteResult result = inUnitOfWork(
                    () -> stubbed.write(record("0000000000000001")));

            assertThat(result.isDuplicate()).isTrue();
            assertThat(result.cicsResp()).hasValue(FileStatus.DUPREC);
            assertThat(result.diagnostic()).isPresent();
            assertThat(result.diagnostic().orElseThrow().integrityViolation()).isFalse();
        }

        @Test
        @DisplayName("a non-integrity refusal on the insert is the WHEN OTHER arm, not a duplicate")
        void aNonIntegrityRefusalIsNotADuplicate() {
            doReturn(IMAGE_COLUMN).when(stub).query(anyString(), anyExtractor());
            doReturn(null).when(stub).query(any(PreparedStatementCreator.class),
                    anyExtractor());
            doThrow(new DataAccessResourceFailureException("the backend is unreachable"))
                    .when(stub).update(any(PreparedStatementCreator.class));

            WriteResult result = inUnitOfWork(
                    () -> stubbed.write(record("0000000000000001")));

            assertThat(result.isOther()).isTrue();
            assertThat(result.isDuplicate()).isFalse();
            assertThat(result.diagnostic()).isPresent();
        }

        @Test
        @DisplayName("a keyed add that affected no row is never reported as written")
        void aKeyedAddThatAffectedNoRow() {
            doReturn(IMAGE_COLUMN).when(stub).query(anyString(), anyExtractor());
            doReturn(null).when(stub).query(any(PreparedStatementCreator.class),
                    anyExtractor());
            doReturn(0).when(stub).update(any(PreparedStatementCreator.class));

            WriteResult result = inUnitOfWork(
                    () -> stubbed.write(record("0000000000000001")));

            assertThat(result.isOther()).isTrue();
            assertThat(result.cicsResp()).hasValue(FileStatus.INVREQ);
            assertThat(result.observation()).contains(DatasetObservation.matchingRows(0));
        }

        @Test
        @DisplayName("a sequential write that affected no row is never reported as written either")
        void aSequentialWriteThatAffectedNoRow() {
            doReturn(0).when(stub).update(any(PreparedStatementCreator.class));

            try (OutputFile output = stubbed.openOutput()) {
                WriteResult result = output.writeSequential(record("2022071800001"));
                assertThat(result.isOther()).isTrue();
                assertThat(result.cicsResp()).hasValue(FileStatus.INVREQ);
                assertThat(result.observation()).contains(DatasetObservation.matchingRows(0));
                assertThat(output.recordsWritten()).isZero();
            }
        }

        @Test
        @DisplayName("the master's statements are composed once and cached, not per read")
        void statementsAreComposedOnce() {
            doReturn(IMAGE_COLUMN).when(stub).query(anyString(), anyExtractor());
            doReturn(null).when(stub).query(any(PreparedStatementCreator.class),
                    anyExtractor());

            assertThat(stubbed.resolvedStatements()).isNull();
            stubbed.readByTranId("0000000000000001");
            TransactionRepository.Statements first = stubbed.resolvedStatements();
            assertThat(first).isNotNull();
            stubbed.readByTranId("0000000000000002");
            assertThat(stubbed.resolvedStatements()).isSameAs(first);

            // Every composed statement names the configured dataset and no literal of its own.
            assertThat(first.selectByKey()).contains(MASTER_DS).contains("LIKE ? ESCAPE");
            assertThat(first.selectByKeyForUpdate()).endsWith("FOR UPDATE");
            assertThat(first.insertRecordImage()).startsWith("INSERT INTO");
            assertThat(first.browseForwardAll()).endsWith("ASC");
            assertThat(first.browseForwardAnchor()).contains(">= ?");
            assertThat(first.browseForwardAfter()).contains("> ?");
            assertThat(first.browseBackwardAll()).endsWith("DESC");
            assertThat(first.browseBackwardAnchor()).contains("< ? OR").contains("LIKE ? ESCAPE");
            assertThat(first.browseBackwardBefore()).contains("< ?").endsWith("DESC");
        }
    }

    // =================================================================================================
    // The outcome types' own invariants.
    // =================================================================================================

    @Nested
    @DisplayName("The outcome types cannot contradict themselves")
    class OutcomeInvariants {

        @Test
        @DisplayName("a read outcome's status, classification and record must agree")
        void readResultInvariants() {
            String dd = TransactionRepository.CICS_FILE_NAME;
            TranRecord present = record("0000000000000001");

            assertThatNullPointerException().isThrownBy(() -> new ReadResult(null, FileStatus.OK,
                    Outcome.OK, Optional.of(present), CicsResponse.none(), Optional.empty(),
                    Optional.empty()));
            assertThatNullPointerException().isThrownBy(() -> new ReadResult(dd, null, Outcome.OK,
                    Optional.of(present), CicsResponse.none(), Optional.empty(), Optional.empty()));
            assertThatNullPointerException().isThrownBy(() -> new ReadResult(dd, FileStatus.OK, null,
                    Optional.of(present), CicsResponse.none(), Optional.empty(), Optional.empty()));
            assertThatNullPointerException().isThrownBy(() -> new ReadResult(dd, FileStatus.OK,
                    Outcome.OK, null, CicsResponse.none(), Optional.empty(), Optional.empty()));
            assertThatNullPointerException().isThrownBy(() -> new ReadResult(dd, FileStatus.OK,
                    Outcome.OK, Optional.of(present), null, Optional.empty(), Optional.empty()));
            assertThatNullPointerException().isThrownBy(() -> new ReadResult(dd, FileStatus.OK,
                    Outcome.OK, Optional.of(present), CicsResponse.none(), null, Optional.empty()));
            assertThatNullPointerException().isThrownBy(() -> new ReadResult(dd, FileStatus.OK,
                    Outcome.OK, Optional.of(present), CicsResponse.none(), Optional.empty(), null));

            assertThatIllegalArgumentException().isThrownBy(() -> new ReadResult(dd, "0", Outcome.OK,
                    Optional.of(present), CicsResponse.none(), Optional.empty(), Optional.empty()))
                    .withMessageContaining("characters");
            assertThatIllegalArgumentException().isThrownBy(() -> new ReadResult(dd, FileStatus.OK,
                    Outcome.END_OF_FILE, Optional.empty(), CicsResponse.none(), Optional.empty(),
                    Optional.empty()))
                    .withMessageContaining("does not classify");
            assertThatIllegalArgumentException().isThrownBy(() -> new ReadResult(dd, FileStatus.OK,
                    Outcome.OK, Optional.empty(), CicsResponse.none(), Optional.empty(),
                    Optional.empty()))
                    .withMessageContaining("returns a record");
            assertThatIllegalArgumentException().isThrownBy(() -> new ReadResult(dd,
                    FileStatus.NOT_FOUND, Outcome.NOT_FOUND, Optional.of(present),
                    CicsResponse.none(), Optional.empty(), Optional.empty()))
                    .withMessageContaining("returns no record");
        }

        @Test
        @DisplayName("every read arm reports the status, classification and APPL-RESULT it should")
        void readResultArms() {
            String dd = TransactionRepository.INPUT_DD_NAME;
            TranRecord present = record("0000000000000001");

            ReadResult found = ReadResult.found(dd, present);
            assertThat(found.isFound()).isTrue();
            assertThat(found.isDuplicate()).isFalse();
            assertThat(found.isNotFound()).isFalse();
            assertThat(found.isEndOfFile()).isFalse();
            assertThat(found.isOther()).isFalse();
            assertThat(found.requireRecord()).isSameAs(present);
            assertThatNullPointerException().isThrownBy(() -> ReadResult.found(dd, null));

            assertThat(ReadResult.endOfFile(dd).isFound()).isFalse();
            assertThat(ReadResult.endOfFile(dd).applResult()).isEqualTo(FileStatus.APPL_EOF);
            assertThat(ReadResult.notFound(dd).applResult())
                    .isEqualTo(TransactionRepository.APPL_RESULT_FATAL);
            assertThat(ReadResult.duplicate(dd, present).isDuplicate()).isTrue();
            assertThatNullPointerException().isThrownBy(() -> ReadResult.duplicate(dd, null));

            ReadResult other = ReadResult.other(dd, TransactionRepository.PERMANENT_ERROR_STATUS);
            assertThat(other.isOther()).isTrue();
            assertThat(other.cicsResp()).isEmpty();
            assertThat(other.statusImage()).hasSize(FileStatus.STATUS_IMAGE_LENGTH);
            assertThat(ReadResult.other(dd, TransactionRepository.PERMANENT_ERROR_STATUS,
                    CicsResponse.of(FileStatus.INVREQ)).cicsResp()).hasValue(FileStatus.INVREQ);
            assertThatNullPointerException().isThrownBy(() -> ReadResult.other(dd,
                    TransactionRepository.PERMANENT_ERROR_STATUS, (CicsResponse) null));
            assertThatNullPointerException().isThrownBy(() -> ReadResult.other(dd,
                    TransactionRepository.PERMANENT_ERROR_STATUS, (BackendDiagnostic) null));
            assertThatNullPointerException().isThrownBy(() -> ReadResult.other(dd,
                    TransactionRepository.PERMANENT_ERROR_STATUS, CicsResponse.none(), null));
        }

        @ParameterizedTest(name = "the {0} arm is distinguishable from all four others (G47, G50)")
        @ValueSource(strings = { "OK", "END_OF_FILE", "DUPLICATE", "NOT_FOUND", "OTHER" })
        @DisplayName("each enumerated status is caller-visible as itself and as nothing else")
        void everyArmIsDistinguishableFromEveryOther(String arm) {
            // Gate G47. A COBOL caller branches on the two-character FILE STATUS - app/cbl/CBTRN03C.cbl
            // :251-258 is a three-arm EVALUATE over '00', '10' and WHEN OTHER, and app/cbl/COTRN01C.cbl
            // :280-291 is a three-arm EVALUATE over NORMAL, NOTFND and WHEN OTHER. Reproducing those
            // branch structures requires that the five outcomes be mutually exclusive and separately
            // observable, which is what this asserts. Gate G50 rides along: each of the five
            // 88-level-derived predicates is driven TRUE by exactly one arm and FALSE by the other four,
            // so across the five parameters every predicate is exercised both ways.
            String dd = TransactionRepository.INPUT_DD_NAME;
            TranRecord present = record("0000000000000001");

            ReadResult result;
            String expectedStatus;
            Outcome expectedOutcome;
            int expectedApplResult;
            boolean expectedRecord;
            switch (arm) {
                case "OK" -> {
                    result = ReadResult.found(dd, present);
                    expectedStatus = FileStatus.OK;
                    expectedOutcome = Outcome.OK;
                    expectedApplResult = FileStatus.APPL_AOK;
                    expectedRecord = true;
                }
                case "END_OF_FILE" -> {
                    result = ReadResult.endOfFile(dd);
                    expectedStatus = FileStatus.END_OF_FILE;
                    expectedOutcome = Outcome.END_OF_FILE;
                    expectedApplResult = FileStatus.APPL_EOF;
                    expectedRecord = false;
                }
                case "DUPLICATE" -> {
                    result = ReadResult.duplicate(dd, present);
                    expectedStatus = FileStatus.DUPLICATE;
                    expectedOutcome = Outcome.DUPLICATE;
                    expectedApplResult = TransactionRepository.APPL_RESULT_FATAL;
                    expectedRecord = true;
                }
                case "NOT_FOUND" -> {
                    result = ReadResult.notFound(dd);
                    expectedStatus = FileStatus.NOT_FOUND;
                    expectedOutcome = Outcome.NOT_FOUND;
                    expectedApplResult = TransactionRepository.APPL_RESULT_FATAL;
                    expectedRecord = false;
                }
                case "OTHER" -> {
                    result = ReadResult.other(dd, TransactionRepository.PERMANENT_ERROR_STATUS);
                    expectedStatus = TransactionRepository.PERMANENT_ERROR_STATUS;
                    expectedOutcome = Outcome.OTHER;
                    expectedApplResult = TransactionRepository.APPL_RESULT_FATAL;
                    expectedRecord = false;
                }
                default -> throw new IllegalArgumentException("unenumerated arm " + arm);
            }

            assertThat(result.status()).isEqualTo(expectedStatus);
            assertThat(result.outcome()).isEqualTo(expectedOutcome);
            assertThat(result.applResult()).isEqualTo(expectedApplResult);
            assertThat(result.isRecordReturned()).isEqualTo(expectedRecord);
            assertThat(result.ddName()).isEqualTo(dd);
            assertThat(result.statusImage()).hasSize(FileStatus.STATUS_IMAGE_LENGTH);
            assertThat(result.describeResponse()).isNotBlank();

            // Exactly one of the five predicates answers true, whichever arm this is.
            List<Boolean> predicates = List.of(result.isFound(), result.isEndOfFile(),
                    result.isNotFound(), result.isDuplicate(), result.isOther());
            assertThat(predicates).filteredOn(answer -> answer).hasSize(1);
            assertThat(predicates).filteredOn(answer -> !answer).hasSize(4);

            // And this arm's status is shared with no other arm, so a caller's EVALUATE cannot collapse
            // two conditions into one branch.
            List<String> everyStatus = List.of(FileStatus.OK, FileStatus.END_OF_FILE,
                    FileStatus.DUPLICATE, FileStatus.NOT_FOUND,
                    TransactionRepository.PERMANENT_ERROR_STATUS);
            assertThat(everyStatus).doesNotHaveDuplicates();
            assertThat(everyStatus).filteredOn(expectedStatus::equals).hasSize(1);
        }

        @Test
        @DisplayName("a write outcome's status and classification must agree, and every arm is reachable")
        void writeResultInvariantsAndArms() {
            String dd = TransactionRepository.CICS_FILE_NAME;

            assertThatNullPointerException().isThrownBy(() -> new WriteResult(null, FileStatus.OK,
                    Outcome.OK, CicsResponse.none(), Optional.empty(), Optional.empty()));
            assertThatNullPointerException().isThrownBy(() -> new WriteResult(dd, null, Outcome.OK,
                    CicsResponse.none(), Optional.empty(), Optional.empty()));
            assertThatNullPointerException().isThrownBy(() -> new WriteResult(dd, FileStatus.OK, null,
                    CicsResponse.none(), Optional.empty(), Optional.empty()));
            assertThatNullPointerException().isThrownBy(() -> new WriteResult(dd, FileStatus.OK,
                    Outcome.OK, null, Optional.empty(), Optional.empty()));
            assertThatNullPointerException().isThrownBy(() -> new WriteResult(dd, FileStatus.OK,
                    Outcome.OK, CicsResponse.none(), null, Optional.empty()));
            assertThatNullPointerException().isThrownBy(() -> new WriteResult(dd, FileStatus.OK,
                    Outcome.OK, CicsResponse.none(), Optional.empty(), null));
            assertThatIllegalArgumentException().isThrownBy(() -> new WriteResult(dd, "0", Outcome.OK,
                    CicsResponse.none(), Optional.empty(), Optional.empty()));
            assertThatIllegalArgumentException().isThrownBy(() -> new WriteResult(dd, FileStatus.OK,
                    Outcome.DUPLICATE, CicsResponse.none(), Optional.empty(), Optional.empty()));

            assertThat(WriteResult.written(dd).isWritten()).isTrue();
            assertThat(WriteResult.written(dd).isOther()).isFalse();
            assertThat(WriteResult.written(dd).isDuplicate()).isFalse();
            assertThat(WriteResult.duplicate(dd).isWritten()).isFalse();
            assertThat(WriteResult.other(dd, TransactionRepository.PERMANENT_ERROR_STATUS).isWritten())
                    .isFalse();
            assertThat(WriteResult.duplicate(dd).applResult())
                    .isEqualTo(TransactionRepository.APPL_RESULT_FATAL);
            assertThat(WriteResult.other(dd, TransactionRepository.PERMANENT_ERROR_STATUS).cicsResp())
                    .isEmpty();
            assertThat(WriteResult.other(dd, TransactionRepository.PERMANENT_ERROR_STATUS,
                    CicsResponse.of(FileStatus.INVREQ), DatasetObservation.matchingRows(0))
                    .cicsResp2()).isEqualTo(FileStatus.NO_REASON_CODE);
            assertThat(WriteResult.written(dd).describeResponse()).isEqualTo("Resp:0 Reas:0");
            assertThat(WriteResult.written(dd).statusImage()).isEqualTo("0000");
            assertThatNullPointerException()
                    .isThrownBy(() -> WriteResult.duplicate(dd, (BackendDiagnostic) null));
            assertThatNullPointerException().isThrownBy(() -> WriteResult.other(dd,
                    TransactionRepository.PERMANENT_ERROR_STATUS, null,
                    DatasetObservation.matchingRows(0)));
            assertThatNullPointerException().isThrownBy(() -> WriteResult.other(dd,
                    TransactionRepository.PERMANENT_ERROR_STATUS, CicsResponse.none(),
                    (DatasetObservation) null));
            assertThatNullPointerException().isThrownBy(() -> WriteResult.other(dd,
                    TransactionRepository.PERMANENT_ERROR_STATUS, CicsResponse.none(),
                    (BackendDiagnostic) null));
        }

        @Test
        @DisplayName("both browse directions exist, and only those two")
        void browseDirectionIsClosed() {
            assertThat(BrowseDirection.values())
                    .containsExactly(BrowseDirection.FORWARD, BrowseDirection.BACKWARD);
            assertThat(BrowseDirection.valueOf("FORWARD")).isEqualTo(BrowseDirection.FORWARD);
        }
    }

    // =================================================================================================
    // The surface this class deliberately does NOT have.
    // =================================================================================================

    @Nested
    @DisplayName("The surface this repository deliberately does not have")
    class AbsentSurface {
        @Test
        @DisplayName("a test's relations are dropped when the test ends, so no schema outlives it")
        void aTestsRelationsAreDroppedWhenTheTestEnds() {
            // DB_CLOSE_DELAY=-1 above is what keeps the four relations alive between the connections
            // the data source opens, and it is also what would keep them alive for the rest of the JVM.
            // A schema that outlives its test is a schema another test can reach by name.
            assertThat(template.queryForObject("SELECT COUNT(*) FROM \"" + MASTER_DS + "\"",
                    Integer.class))
                    .as("the relation exists while the test is using it")
                    .isZero();

            dropRelations();

            assertThatExceptionOfType(BadSqlGrammarException.class)
                    .as("and afterwards nothing is addressable through that name")
                    .isThrownBy(() -> template.queryForObject(
                            "SELECT COUNT(*) FROM \"" + MASTER_DS + "\"", Integer.class));
        }


        @Test
        @DisplayName("there is no rewrite and no delete, because no program in the estate performs one")
        void noRewriteAndNoDelete() {
            // app/csd/CARDDEMO.CSD:81-82 grants ADD(YES) BROWSE(YES) DELETE(YES) READ(YES) UPDATE(YES),
            // but a granted capability is not an access path. An exhaustive scan of all 28 programs in
            // app/cbl finds no REWRITE and no DELETE against TRANSACT or TRANFILE: every REWRITE in the
            // estate targets ACCTFILE, TRAN-CAT-BAL, CUSTFILE, CARDFILE or USRSEC, and the only DELETE is
            // app/cbl/COUSR03C.cbl:307 against USRSEC. The transaction file is an append-only audit
            // trail, and this absence is a deliberate parity boundary rather than an oversight: adding
            // either operation would give the Java system a capability the COBOL system does not have,
            // which is a behaviour change however convenient it looks.
            //
            // Asserted as a PREDICATE over the whole public surface, not as a list of exact spellings,
            // so that rewriteRecord, deleteAll, purge-by-any-other-name and every nested type's method
            // are caught as well.
            assertThat(publicSurfaceOf(TransactionRepository.class))
                    .as("no mutation or removal of a posted transaction is reachable")
                    .isNotEmpty()
                    .noneMatch(name -> name.contains("rewrite") || name.contains("delete")
                            || name.contains("purge") || name.contains("remove")
                            || name.contains("erase") || name.contains("truncate"));

            // "update" is deliberately NOT in that list, because exactly two names legitimately carry
            // it and both are READS:
            //   * readForUpdateByTranId  - app/cbl/COTRN01C.cbl:269-278's EXEC CICS READ with the UPDATE
            //     option, which takes the record lock that UPDATEMODEL(LOCKING) grants;
            //   * selectByKeyForUpdate   - the composed SELECT ... FOR UPDATE that carries that lock
            //     request to the backend.
            // They are enumerated exactly rather than excluded by keyword, so a genuine
            // updateTransaction could never hide behind the word.
            assertThat(publicSurfaceOf(TransactionRepository.class))
                    .filteredOn(name -> name.contains("update"))
                    .as("every UPDATE on this surface is the read-with-lock option, never a mutation")
                    .containsOnly("readforupdatebytranid", "selectbykeyforupdate");
        }

        @Test
        @DisplayName("TRANSACT has no alternate index, so there is no second finder (G45)")
        void noAlternateIndexFinder() {
            // Unlike CARDDAT/CARDAIX and CCXREF/CXACAIX, app/csd/CARDDEMO.CSD defines no AIX PATH over
            // AWS.M2.CARDDEMO.TRANSACT.VSAM.KSDS, so there is no second access path to expose.
            assertThat(publicSurfaceOf(TransactionRepository.class))
                    .noneMatch(name -> name.contains("altindex") || name.contains("alternateindex")
                            || name.contains("aix"));
        }

        @Test
        @DisplayName("no operation throws an abend: the caller decides, exactly as the COBOL does")
        void noOperationThrowsAnAbend() {
            // app/cbl/CBTRN02C.cbl:562-575 runs its own '00' -> APPL-RESULT 0 / else -> 12 ladder and
            // only then displays and abends; app/cbl/CBTRN03C.cbl:248-258 does the same with a three-arm
            // EVALUATE. The branch therefore belongs to the caller, so no operation here may pre-empt it
            // by throwing.
            for (Method method : TransactionRepository.class.getDeclaredMethods()) {
                assertThat(method.getExceptionTypes())
                        .as("%s declares no checked or abend exception", method.getName())
                        .noneMatch(AbendException.class::isAssignableFrom);
            }
        }
    }

    // =================================================================================================
    // The record and configuration contracts: the copybook geometry, the fixed-point policy, and the two
    // things that must be absent from the way this class reaches its dataset.
    // =================================================================================================

    /**
     * Every public method name reachable on a type and on every type nested inside it, lower-cased so a
     * predicate over it is spelling-insensitive.
     *
     * <p>{@link Class#getMethods()} alone would miss {@code InputFile}, {@code OutputFile} and
     * {@code Browse}, which is precisely where an added mutation would be easiest to overlook.
     *
     * @param type the type to survey
     * @return the lower-cased names, never {@code null} and never empty for a type with any method
     */
    private static List<String> publicSurfaceOf(Class<?> type) {
        List<String> names = new ArrayList<>();
        for (Method method : type.getMethods()) {
            if (method.getDeclaringClass() != Object.class) {
                names.add(method.getName().toLowerCase(Locale.ROOT));
            }
        }
        for (Class<?> nested : type.getDeclaredClasses()) {
            for (Method method : nested.getMethods()) {
                if (method.getDeclaringClass() != Object.class) {
                    names.add(method.getName().toLowerCase(Locale.ROOT));
                }
            }
        }
        return names;
    }

    /**
     * The compiled bytes of a class, read from the classpath rather than from a source tree.
     *
     * <p>A source-file scan would depend on the working directory the test JVM happens to start in;
     * reading the {@code .class} resource does not, and it sees exactly the string literals that ended up
     * in the constant pool - which is what "no dataset name is compiled in" actually means.
     *
     * @param type the class whose bytes are wanted
     * @return the class file's bytes
     */
    private static byte[] compiledBytesOf(Class<?> type) {
        String resource = type.getName().substring(type.getName().lastIndexOf('.') + 1) + ".class";
        try (InputStream in = type.getResourceAsStream(resource)) {
            assertThat(in).as("the compiled form of %s is on the classpath", type.getName()).isNotNull();
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            in.transferTo(bytes);
            return bytes.toByteArray();
        } catch (IOException failure) {
            throw new UncheckedIOException("Could not read the compiled form of " + type.getName(),
                    failure);
        }
    }

    /** The simple names of every annotation on an element, for the schema-artefact scan. */
    private static List<String> annotationNamesOf(AnnotatedElement element) {
        List<String> names = new ArrayList<>();
        for (Annotation annotation : element.getAnnotations()) {
            names.add(annotation.annotationType().getSimpleName());
        }
        return names;
    }

    @Nested
    @DisplayName("The width guards that only an induced failure reaches")
    class InducedWidthFailures {

        /**
         * A record that reports a usable key but encodes to the wrong number of bytes.
         *
         * <p>{@link TranRecord} cannot be made to do this - its {@code RecordLayout} refuses to
         * initialise at any width but 350 - so the only way to reach the guards below is to stand in for
         * it. That is not a contrivance: the guards exist precisely because a record of the wrong width
         * must never reach the dataset, and a guard that is never executed is a guard nobody has proved.
         *
         * @param width the width to encode to
         * @return a stand-in record encoding to exactly {@code width} bytes
         */
        private TranRecord recordEncodingTo(int width) {
            TranRecord malformed = mock(TranRecord.class);
            doReturn("0000000000000001").when(malformed).tranId();
            doReturn(new byte[width]).when(malformed).encode(ArgumentMatchers.any(Charset.class));
            return malformed;
        }

        @ParameterizedTest(name = "a keyed add of a {0}-byte record is refused rather than stored")
        @ValueSource(ints = { 349, 351 })
        @DisplayName("the keyed WRITE refuses any width but 350, short or long (G19)")
        void aKeyedAddOfTheWrongWidthIsRefused(int width) {
            WriteResult refused = repository.write(recordEncodingTo(width));
            // No unit of work is declared and none is needed: the width is established before the
            // boundary is, so a malformed record is still diagnosed as a malformed record.

            assertThat(refused.isWritten()).isFalse();
            assertThat(refused.isDuplicate()).isFalse();
            assertThat(refused.isOther()).isTrue();
            assertThat(refused.status()).isEqualTo(TransactionRepository.PERMANENT_ERROR_STATUS);
            assertThat(refused.applResult()).isEqualTo(TransactionRepository.APPL_RESULT_FATAL);
            assertThat(refused.cicsResp()).hasValue(FileStatus.LENGERR);
            assertThat(refused.observation()).contains(DatasetObservation.recordWidth(width));
            assertThat(refused.ddName()).isEqualTo(TransactionRepository.CICS_FILE_NAME);
            // Refused means refused: nothing reached the dataset.
            assertThat(template.queryForObject(
                    "SELECT COUNT(*) FROM \"" + MASTER_DS + "\"", Integer.class)).isZero();
        }

        @ParameterizedTest(name = "a sequential write of a {0}-byte record is refused rather than emitted")
        @ValueSource(ints = { 349, 351 })
        @DisplayName("the sequential write refuses any width but 350, because RECFM=F has no slack (G19)")
        void aSequentialWriteOfTheWrongWidthIsRefused(int width) {
            // app/jcl/INTCALC.jcl:39 declares RECFM=F LRECL=350. In an unblocked fixed file a record of
            // the wrong width does not truncate one record - it shifts every record after it.
            try (OutputFile output = repository.openOutput()) {
                WriteResult refused = output.writeSequential(recordEncodingTo(width));

                assertThat(refused.isWritten()).isFalse();
                assertThat(refused.isOther()).isTrue();
                assertThat(refused.status()).isEqualTo(TransactionRepository.PERMANENT_ERROR_STATUS);
                assertThat(refused.cicsResp()).hasValue(FileStatus.LENGERR);
                assertThat(refused.observation()).contains(DatasetObservation.recordWidth(width));
                assertThat(refused.ddName())
                        .isEqualTo(TransactionRepository.SEQUENTIAL_OUTPUT_DD_NAME);
                assertThat(output.recordsWritten()).isZero();
                // The run is still usable, so one refused record does not abandon the generation.
                assertThat(output.writeSequential(record("2022071800001")).isWritten()).isTrue();
                assertThat(output.recordsWritten()).isEqualTo(1);
            }
            assertThat(template.queryForObject(
                    "SELECT COUNT(*) FROM \"" + SYSTRAN_DS + "\"", Integer.class)).isEqualTo(1);
        }

        @Test
        @DisplayName("a row present but unreadable is told apart from a row that is simply readable")
        void aFetchedRowWithNoRecordImageIsDistinguished() throws SQLException {
            // extractRows is package-private precisely so this can be driven without a backend. Both
            // sides of "the record-image column held nothing" are exercised here, because collapsing an
            // unreadable row onto an absent row would report an I/O defect as an end of file - and
            // app/cbl/CBTRN03C.cbl:251-258 branches differently on '10' than on WHEN OTHER.
            ResultSet readable = mock(ResultSet.class);
            doReturn(true, false).when(readable).next();
            doReturn("X".repeat(RECORD_LENGTH)).when(readable)
                    .getString(TransactionRepository.RECORD_IMAGE_COLUMN_INDEX);

            var present = repository.extractRows(readable, 1);
            assertThat(present.rowCount()).isEqualTo(1);
            assertThat(present.firstImageMissing()).isFalse();
            assertThat(present.firstImage()).hasSize(RECORD_LENGTH);

            ResultSet unreadable = mock(ResultSet.class);
            doReturn(true, false).when(unreadable).next();
            doReturn(null).when(unreadable)
                    .getString(TransactionRepository.RECORD_IMAGE_COLUMN_INDEX);

            var absent = repository.extractRows(unreadable, 1);
            assertThat(absent.rowCount()).isEqualTo(1);
            assertThat(absent.firstImageMissing()).isTrue();
            assertThat(absent.firstImage()).isNull();

            // And a result set that yields nothing at all is neither of those two conditions.
            ResultSet empty = mock(ResultSet.class);
            doReturn(false).when(empty).next();
            var none = repository.extractRows(empty, 1);
            assertThat(none.rowCount()).isZero();
            assertThat(none.firstImageMissing()).isFalse();
            assertThat(none.firstImage()).isNull();
        }

        @Test
        @DisplayName("an open either reports OK and is usable, or reports a failure and is not")
        void anOpenIsEitherUsableOrReportsWhyItIsNot() {
            // The one branch outcome in this class that no test reaches is the impossible half of
            // InputFile's private construction guard, "OPEN reported '00' but there are no statements to
            // read with". Every one of the four construction sites pairs a null statement set with
            // PERMANENT_ERROR_STATUS or with a refusal status, and pairs a composed statement set with
            // FileStatus.OK - so that combination cannot be produced by any caller, and reaching it
            // would take reflection into a private constructor to assert behaviour nobody can observe.
            // What IS observable is the invariant the guard protects, and that is asserted here from
            // both sides: OK implies usable, and not-OK implies not usable.
            try (InputFile opened = repository.openInput()) {
                assertThat(opened.openStatus()).isEqualTo(FileStatus.OK);
                assertThat(opened.isOpen()).isTrue();
                assertThat(opened.readNext().isEndOfFile()).isTrue();
            }
            InputFile failed = repositoryOverMissingRelations().openInput();
            assertThat(failed.openStatus()).isNotEqualTo(FileStatus.OK);
            assertThat(failed.isOpen()).isFalse();
            assertThat(failed.readNext().isOther()).isTrue();
        }
    }

    @Nested
    @DisplayName("The record geometry, the fixed-point policy, and what must be absent")
    class RecordContract {

        @Test
        @DisplayName("the fourteen CVTRA05Y spans add up to 350, FILLER included (G19, G21)")
        void everySpanAddsUpToThreeHundredAndFifty() {
            // Restated by ADDITION from app/cpy/CVTRA05Y.cpy, item by item, so this assertion is
            // independent of the constants it is checking. If the two ever disagree, the copybook wins.
            int cursor = 0;
            cursor += 16;   // TRAN-ID            PIC X(16)   -> next span starts at 16
            assertThat(TranRecord.TRAN_TYPE_CD_OFFSET).isEqualTo(cursor);
            cursor += 2;    // TRAN-TYPE-CD       PIC X(02)   -> 18
            assertThat(TranRecord.TRAN_CAT_CD_OFFSET).isEqualTo(cursor);
            cursor += 4;    // TRAN-CAT-CD        PIC 9(04)   -> 22
            assertThat(TranRecord.TRAN_SOURCE_OFFSET).isEqualTo(cursor);
            cursor += 10;   // TRAN-SOURCE        PIC X(10)   -> 32
            assertThat(TranRecord.TRAN_DESC_OFFSET).isEqualTo(cursor);
            cursor += 100;  // TRAN-DESC          PIC X(100)  -> 132
            assertThat(TranRecord.TRAN_AMT_OFFSET).isEqualTo(cursor);
            cursor += 11;   // TRAN-AMT           PIC S9(09)V99 = 9 + 2 zoned bytes, no sign byte -> 143
            assertThat(TranRecord.TRAN_MERCHANT_ID_OFFSET).isEqualTo(cursor);
            cursor += 9;    // TRAN-MERCHANT-ID   PIC 9(09)   -> 152
            assertThat(TranRecord.TRAN_MERCHANT_NAME_OFFSET).isEqualTo(cursor);
            cursor += 50;   // TRAN-MERCHANT-NAME PIC X(50)   -> 202
            assertThat(TranRecord.TRAN_MERCHANT_CITY_OFFSET).isEqualTo(cursor);
            cursor += 50;   // TRAN-MERCHANT-CITY PIC X(50)   -> 252
            assertThat(TranRecord.TRAN_MERCHANT_ZIP_OFFSET).isEqualTo(cursor);
            cursor += 10;   // TRAN-MERCHANT-ZIP  PIC X(10)   -> 262
            assertThat(TranRecord.TRAN_CARD_NUM_OFFSET).isEqualTo(cursor);
            cursor += 16;   // TRAN-CARD-NUM      PIC X(16)   -> 278
            assertThat(TranRecord.TRAN_ORIG_TS_OFFSET).isEqualTo(cursor);
            cursor += 26;   // TRAN-ORIG-TS       PIC X(26)   -> 304
            assertThat(TranRecord.TRAN_PROC_TS_OFFSET).isEqualTo(cursor);
            cursor += 26;   // TRAN-PROC-TS       PIC X(26)   -> 330
            assertThat(TranRecord.FILLER_OFFSET).isEqualTo(cursor);
            cursor += 20;   // FILLER             PIC X(20)   -> 350

            assertThat(cursor).isEqualTo(RECORD_LENGTH);
            assertThat(TranRecord.RECORD_LENGTH).isEqualTo(RECORD_LENGTH);
            assertThat(TranRecord.sumOfDeclaredSpanLengths()).isEqualTo(RECORD_LENGTH);
            assertThat(repository.recordLength()).isEqualTo(RECORD_LENGTH);
            // Gate G21: the trailing FILLER is a declared span. Drop it and the sum is 330, every
            // downstream offset is wrong, and the width assertion above fails immediately - which is
            // exactly why the width assertion is the cheapest FILLER check there is.
            assertThat(TranRecord.FILLER_LENGTH).isEqualTo(20);
            assertThat(TranRecord.FILLER_OFFSET + TranRecord.FILLER_LENGTH).isEqualTo(RECORD_LENGTH);
            assertThat(TranRecord.FILLER.kind()).isEqualTo(PictureKind.FILLER);
            assertThat(TranRecord.LAYOUT.storageSpans()).hasSize(14);
            assertThat(TranRecord.LAYOUT.redefinitions()).isEmpty();
        }

        @Test
        @DisplayName("a stored row carries TRAN-CARD-NUM at 262 and TRAN-PROC-DT at 304, as SORT expects")
        void theSortStepsOffsetsAreWhereItExpectsThem() {
            // app/cbl/CORPT00C.cbl:100 and :102 write these two positions into the SYMNAMES of the SORT
            // step that app/jcl/TRANREPT.jcl runs ahead of CBTRN03C:
            //     TRAN-CARD-NUM,263,16,ZD
            //     TRAN-PROC-DT,305,10,CH
            // Those are 1-based byte positions, so they are 0-based 262 and 304 here. They corroborate
            // the copybook from outside it, which is why they are worth asserting separately: an
            // off-by-one in either would sort the report by the wrong bytes and the diff would show up
            // as a mysteriously reordered report rather than as a bad offset.
            TranRecord written = record("0000000001774260");
            written.moveTranProcTs("2022-07-18 12.34.56.000000");
            assertThat(inUnitOfWork(() -> repository.write(written)).isWritten()).isTrue();

            String stored = template.queryForObject(
                    "SELECT \"" + IMAGE_COLUMN + "\" FROM \"" + MASTER_DS + "\"", String.class);
            assertThat(stored).hasSize(RECORD_LENGTH);

            assertThat(TranRecord.TRAN_CARD_NUM_OFFSET).isEqualTo(263 - 1);
            assertThat(stored.substring(262, 262 + 16)).isEqualTo("4444333322221111");

            assertThat(TranRecord.TRAN_PROC_DT_OFFSET).isEqualTo(305 - 1);
            assertThat(TranRecord.TRAN_PROC_DT_LENGTH).isEqualTo(10);
            // TRAN-PROC-DT is the leading ten characters of TRAN-PROC-TS PIC X(26); both begin at the
            // same byte, which is why one 1-based position serves both.
            assertThat(TranRecord.TRAN_PROC_TS_OFFSET).isEqualTo(TranRecord.TRAN_PROC_DT_OFFSET);
            assertThat(stored.substring(304, 304 + 10)).isEqualTo("2022-07-18");

            // And the key the whole dataset is addressed by is still the leading sixteen bytes.
            assertThat(TranRecord.TRAN_ID_OFFSET).isZero();
            assertThat(repository.keyLength()).isEqualTo(TranRecord.TRAN_ID_KEY_LENGTH);
            assertThat(stored.substring(0, KEY_LENGTH)).isEqualTo("0000000001774260");
            // Gate G21 again, on the wire rather than on a constant.
            assertThat(stored.substring(330)).isEqualTo(" ".repeat(20));
        }

        @Test
        @DisplayName("TRAN-AMT is eleven zoned DISPLAY bytes at offset 132, sign overpunched, no COMP-3")
        void theAmountOccupiesElevenZonedBytesAtOffset132() {
            // PIC S9(09)V99 occupies p + s = 9 + 2 = 11 bytes. The V is an assumed decimal point and
            // takes none, and the S takes none either because the sign is overpunched into the trailing
            // byte. There is ZERO COMP-3 in app/cpy - verified across all 28 copybooks - so no nibble
            // unpacking exists to test: a packed S9(09)V99 would be 6 bytes, not 11, and asserting 11
            // here is what pins the field to the zoned form the fixtures actually carry.
            assertThat(TranRecord.TRAN_AMT_OFFSET).isEqualTo(132);
            assertThat(TranRecord.TRAN_AMT_LENGTH).isEqualTo(11);
            assertThat(TranRecord.TRAN_AMT_INTEGER_DIGITS + TranRecord.TRAN_AMT_SCALE)
                    .isEqualTo(TranRecord.TRAN_AMT_LENGTH);
            assertThat(TranRecord.TRAN_AMT_SCALE).isEqualTo(CobolDecimal.MONETARY_SCALE).isEqualTo(2);

            FieldSpan amount = TranRecord.TRAN_AMT;
            assertThat(amount.name()).isEqualTo("TRAN-AMT");
            assertThat(amount.kind()).isEqualTo(PictureKind.SIGNED_SCALED);
            assertThat(amount.offset()).isEqualTo(132);
            assertThat(amount.length()).isEqualTo(11);
            assertThat(amount.endOffsetExclusive()).isEqualTo(143);
            assertThat(amount.redefinition()).isFalse();

            // On the wire: the eleven bytes at 132 are the stored image, overpunch and all.
            TranRecord written = record("0000000000000001");
            written.writeTranAmtImage("0000009190}");
            assertThat(inUnitOfWork(() -> repository.write(written)).isWritten()).isTrue();

            String stored = template.queryForObject(
                    "SELECT \"" + IMAGE_COLUMN + "\" FROM \"" + MASTER_DS + "\"", String.class);
            assertThat(stored.substring(132, 143)).isEqualTo("0000009190}").hasSize(11);
            assertThat(stored.charAt(142)).isEqualTo('}');
        }

        @Test
        @DisplayName("an amount is stored at scale 2 and TRUNCATED, never rounded (R2, R4, G22, G24)")
        void anAmountIsStoredAtScaleTwoAndTruncatedNeverRounded() {
            // ROUNDED appears ZERO times across all 28 programs, so COBOL truncates excess fraction
            // digits on store and RoundingMode.DOWN is the only faithful policy. Never HALF_UP, never
            // HALF_EVEN, never CEILING, never FLOOR - and never a double or a float, which cannot
            // represent a decimal fraction exactly and would drift by a cent.
            assertThat(CobolDecimal.COBOL_ROUNDING).isEqualTo(RoundingMode.DOWN);

            TranRecord written = record("0000000000000001");
            // Three excess fraction digits on a positive value, and the digit dropped is a 9: HALF_UP
            // and HALF_EVEN would both carry it and store 504.78, which is a cent of parity failure.
            written.moveTranAmt(new BigDecimal("504.779"));
            assertThat(inUnitOfWork(() -> repository.write(written)).isWritten()).isTrue();

            BigDecimal readBack = repository.readByTranId("0000000000000001").requireRecord().tranAmt();
            assertThat(readBack).isEqualByComparingTo(new BigDecimal("504.77"));
            assertThat(readBack.scale()).isEqualTo(CobolDecimal.MONETARY_SCALE);
            assertThat(readBack).isEqualTo(new BigDecimal("504.779").setScale(2, RoundingMode.DOWN));
            assertThat(readBack).isNotEqualByComparingTo(
                    new BigDecimal("504.779").setScale(2, RoundingMode.HALF_UP));
            // 504.77 fills the eleven digit positions as 000000504|77, and the trailing 7 carries the
            // positive sign as 'G' ('{' is +0 and 'A'-'I' are +1 to +9), so the stored image is
            // 0000005047G - eleven bytes, with no separate sign byte and no decimal point.
            assertThat(repository.readByTranId("0000000000000001").requireRecord().tranAmtImage())
                    .isEqualTo("0000005047G");

            // And on the negative side, where DOWN means toward zero and FLOOR would go the other way.
            TranRecord negative = record("0000000000000002");
            negative.moveTranAmt(new BigDecimal("-919.006"));
            assertThat(inUnitOfWork(() -> repository.write(negative)).isWritten()).isTrue();

            TranRecord storedNegative =
                    repository.readByTranId("0000000000000002").requireRecord();
            assertThat(storedNegative.tranAmt()).isEqualByComparingTo(new BigDecimal("-919.00"));
            assertThat(storedNegative.tranAmt().scale()).isEqualTo(2);
            assertThat(storedNegative.tranAmt()).isNotEqualByComparingTo(
                    new BigDecimal("-919.006").setScale(2, RoundingMode.FLOOR));
            // -919.00 is magnitude 0000009190 with a negative final digit 0, which overpunches as '}'.
            assertThat(storedNegative.tranAmtImage()).isEqualTo("0000009190}");
            assertThat(storedNegative.hasZeroTranAmt()).isFalse();
        }

        @Test
        @DisplayName("a whole-value negative zero survives, because the raw span is what moves (R5)")
        void aNegativeZeroSurvivesOnlyOnTheRawSpan() {
            // app/data/ASCII/dailytran.txt carries six amount images ending in '}' out of three hundred
            // records - '}' is "negative, final digit zero". Five of those, and the sixth, are ordinary
            // negative amounts such as 0000009190} = -919.00, and those DO survive a numeric round trip.
            // The image that does not is the whole-value negative zero 0000000000}: BigDecimal has no
            // signed zero, so it decodes to plain 0.00 and a numeric re-encode stores 0000000000{.
            //
            // This repository must therefore carry record bytes, not record values. Asserted on the raw
            // 350-byte span rather than on any decoded field, which is the only assertion that can tell
            // the difference.
            TranRecord written = record("0000000000000001");
            written.writeTranAmtImage("0000000000}");
            byte[] beforeWrite = written.rawImage();

            assertThat(inUnitOfWork(() -> repository.write(written)).isWritten()).isTrue();
            TranRecord readBack = repository.readByTranId("0000000000000001").requireRecord();

            assertThat(readBack.rawImage()).isEqualTo(beforeWrite);
            assertThat(readBack.rawImage()).hasSize(RECORD_LENGTH);
            assertThat(readBack.tranAmtImage()).isEqualTo("0000000000}");
            assertThat(readBack.rawSpan(TranRecord.TRAN_AMT)).isEqualTo("0000000000}");
            assertThat(readBack.rawSpanBytes(TranRecord.TRAN_AMT)).hasSize(11);
            // The value it decodes to is an unsigned zero, and that is not a defect - it is why the
            // byte assertion above is the one that matters.
            assertThat(readBack.tranAmt()).isEqualByComparingTo(CobolDecimal.monetaryZero());
            assertThat(readBack.hasZeroTranAmt()).isTrue();

            // Demonstrating the hazard rather than merely describing it: a numeric re-encode of the very
            // record just read loses the sign byte, so a copy path must never take that route.
            TranRecord reEncoded = readBack.copy();
            reEncoded.moveTranAmt(readBack.tranAmt());
            assertThat(reEncoded.tranAmtImage()).isEqualTo("0000000000{");
            assertThat(reEncoded.rawImage()).isNotEqualTo(beforeWrite);

            // Whereas the fixture's real shape round-trips through the value path unchanged.
            TranRecord ordinary = record("0000000000000002");
            ordinary.writeTranAmtImage("0000009190}");
            assertThat(inUnitOfWork(() -> repository.write(ordinary)).isWritten()).isTrue();
            TranRecord ordinaryBack = repository.readByTranId("0000000000000002").requireRecord();
            assertThat(ordinaryBack.tranAmt()).isEqualByComparingTo(new BigDecimal("-919.00"));
            TranRecord ordinaryReEncoded = ordinaryBack.copy();
            ordinaryReEncoded.moveTranAmt(ordinaryBack.tranAmt());
            assertThat(ordinaryReEncoded.tranAmtImage()).isEqualTo("0000009190}");
            assertThat(ordinaryReEncoded.rawImage()).isEqualTo(ordinaryBack.rawImage());
        }

        @Test
        @DisplayName("no dataset name is compiled into this repository - it comes from the binding (G46)")
        void noDatasetNameIsCompiledIn() {
            // Gate G46. app/csd/CARDDEMO.CSD:77 names DSNAME(AWS.M2.CARDDEMO.TRANSACT.VSAM.KSDS) and
            // app/jcl/INTCALC.jcl and POSTTRAN.jcl name the DDs, but not one of those literals may reach
            // Java: the name resolves from the carddemo.datasets.TRANSACT / TRANFILE / SYSTRAN keys of
            // application.yml. Scanned over the COMPILED bytes rather than over a source path, so the
            // assertion sees the constant pool itself and does not depend on the working directory.
            List<Class<?>> compiled = new ArrayList<>();
            compiled.add(TransactionRepository.class);
            compiled.addAll(List.of(TransactionRepository.class.getDeclaredClasses()));

            for (Class<?> type : compiled) {
                String constants = new String(compiledBytesOf(type), StandardCharsets.ISO_8859_1);
                assertThat(constants)
                        .as("%s compiles in no production dataset name", type.getName())
                        .doesNotContain("AWS.M2.CARDDEMO")
                        .doesNotContain("AWS.M2")
                        .doesNotContain("VSAM.KSDS");
            }

            // The positive half of the same gate: change the binding and the identity changes with it,
            // which is only possible if nothing is hard-coded.
            String otherName = "CARDDEMO.OTHER.TRANSACT.VSAM.KSDS";
            TransactionRepository rebound = new TransactionRepository(template,
                    bindingsWith(TransactionRepository.CICS_FILE_NAME, ksds(otherName)), ASCII,
                    RecordImageForm.CHARACTER, ORDINAL);
            assertThat(rebound.datasetName()).isEqualTo(otherName);
            assertThat(rebound.describeStatement()).contains(otherName);
            // And the three keys the bindings are looked up under are DD and file names, not datasets.
            assertThat(TransactionRepository.CICS_FILE_NAME).isEqualTo("TRANSACT");
            assertThat(TransactionRepository.INPUT_DD_NAME).isEqualTo("TRANFILE");
            assertThat(TransactionRepository.SEQUENTIAL_OUTPUT_DD_NAME).isEqualTo("SYSTRAN");
        }

        @Test
        @DisplayName("no schema artefact of any kind is involved in reaching the dataset (G44)")
        void noSchemaArtefactIsInvolved() {
            // Gate G44: JDBC to the existing backend, with no DDL, no ORM mapping, no version column and
            // no migration. The dataset is reached by composed statements against a relation that
            // already exists; nothing here declares, creates or evolves one.
            List<String> forbidden = List.of("Entity", "Table", "Id", "Column", "GeneratedValue",
                    "Version", "IdClass", "EmbeddedId", "SequenceGenerator", "JoinColumn");

            for (Class<?> type : List.of(TransactionRepository.class, TranRecord.class,
                    TransactionRepository.ReadResult.class, TransactionRepository.WriteResult.class,
                    TransactionRepository.InputFile.class, TransactionRepository.OutputFile.class,
                    TransactionRepository.Browse.class)) {
                assertThat(annotationNamesOf(type))
                        .as("%s carries no persistence mapping annotation", type.getSimpleName())
                        .doesNotContainAnyElementsOf(forbidden);
                for (Field field : type.getDeclaredFields()) {
                    assertThat(annotationNamesOf(field))
                            .as("%s.%s carries no persistence mapping annotation", type.getSimpleName(),
                                    field.getName())
                            .doesNotContainAnyElementsOf(forbidden);
                }
                for (Method method : type.getDeclaredMethods()) {
                    assertThat(annotationNamesOf(method))
                            .as("%s.%s carries no persistence mapping annotation",
                                    type.getSimpleName(), method.getName())
                            .doesNotContainAnyElementsOf(forbidden);
                }
                for (Constructor<?> constructor : type.getDeclaredConstructors()) {
                    assertThat(annotationNamesOf(constructor))
                            .as("a constructor of %s carries no persistence mapping annotation",
                                    type.getSimpleName())
                            .doesNotContainAnyElementsOf(forbidden);
                }
            }

            // No composed statement is a data-definition statement, and none names a version column.
            List<String> statements = new ArrayList<>();
            statements.add(repository.describeStatement());
            statements.add(repository.describeInputStatement());
            try (OutputFile output = repository.openOutput()) {
                statements.add(output.insertStatement());
            }
            for (String statement : statements) {
                String upper = statement.toUpperCase(Locale.ROOT);
                assertThat(upper)
                        .as("[%s] reads or writes rows and defines nothing", statement)
                        .doesNotContain("CREATE ")
                        .doesNotContain("ALTER ")
                        .doesNotContain("DROP ")
                        .doesNotContain("TRUNCATE")
                        .doesNotContain("GRANT ")
                        .doesNotContain("VERSION")
                        .doesNotContain("OPTLOCK");
            }
            // The record itself models exactly the copybook's fourteen spans - no surrogate key column,
            // no discriminator, no optimistic-locking counter has been added to it.
            assertThat(TranRecord.LAYOUT.storageSpans())
                    .extracting(FieldSpan::name)
                    .containsExactly("TRAN-ID", "TRAN-TYPE-CD", "TRAN-CAT-CD", "TRAN-SOURCE",
                            "TRAN-DESC", "TRAN-AMT", "TRAN-MERCHANT-ID", "TRAN-MERCHANT-NAME",
                            "TRAN-MERCHANT-CITY", "TRAN-MERCHANT-ZIP", "TRAN-CARD-NUM", "TRAN-ORIG-TS",
                            "TRAN-PROC-TS", "FILLER");
        }
    }

    // =================================================================================================
    // The DD-scoped output open - the DD-mapping finding.
    //
    // app/jcl/INTCALC.jcl:37-41 declares the generated-transaction output under the DD name TRANSACT -
    // the same eight characters as the CICS transaction master, addressing a completely different
    // dataset. Only a job-scoped resolution can see the alias that resolves the collision, so a job
    // hands its resolved binding in rather than calling the no-argument open.
    // =================================================================================================

    @Nested
    @DisplayName("openOutput(binding, ddName) - the caller's own DD is the one written")
    class TheDdScopedOutputOpen {

        @Test
        @DisplayName("a binding naming the configured output opens the configured relation")
        void theConfiguredBindingOpensTheConfiguredRelation() {
            TransactionRepository.OutputFile file = repository.openOutput(sequential(SYSTRAN_DS),
                    AccountInterestCalcJob.TRANSACT_DD_NAME);

            assertThat(file.openStatus()).isEqualTo(FileStatus.OK);
            assertThat(file.insertStatement()).contains(SYSTRAN_DS);
        }

        @Test
        @DisplayName("a binding naming another dataset writes there, not to the configured output")
        void anotherDatasetIsWrittenTo() {
            // This is the case the finding was about, made observable: two distinct destinations, and the
            // DD decides which one receives the record.
            template.execute("CREATE TABLE \"" + SYSTRAN_DS + ".ALT\" (\"" + IMAGE_COLUMN + "\" CHAR("
                    + RECORD_LENGTH + "))");

            TransactionRepository.OutputFile file = repository.openOutput(
                    sequential(SYSTRAN_DS + ".ALT"), AccountInterestCalcJob.TRANSACT_DD_NAME);
            assertThat(file.openStatus()).isEqualTo(FileStatus.OK);
            assertThat(file.writeSequential(record("2022071800001")).isWritten()).isTrue();
            assertThat(file.closeOutput()).isEqualTo(FileStatus.OK);

            assertThat(template.queryForObject("SELECT COUNT(*) FROM \"" + SYSTRAN_DS + ".ALT\"",
                    Integer.class)).isOne();
            assertThat(template.queryForObject("SELECT COUNT(*) FROM \"" + SYSTRAN_DS + "\"",
                    Integer.class)).isZero();
        }

        @Test
        @DisplayName("the open clears the generation it addresses, exactly as the no-argument open does")
        void theOpenClearsTheGenerationItAddresses() {
            // DISP=(NEW,CATLG,DELETE): a run writes into an empty generation whichever DD named it.
            TransactionRepository.OutputFile first = repository.openOutput(sequential(SYSTRAN_DS),
                    AccountInterestCalcJob.TRANSACT_DD_NAME);
            assertThat(first.writeSequential(record("2022071800001")).isWritten()).isTrue();
            assertThat(first.closeOutput()).isEqualTo(FileStatus.OK);

            TransactionRepository.OutputFile second = repository.openOutput(sequential(SYSTRAN_DS),
                    AccountInterestCalcJob.TRANSACT_DD_NAME);

            assertThat(second.openStatus()).isEqualTo(FileStatus.OK);
            assertThat(template.queryForObject("SELECT COUNT(*) FROM \"" + SYSTRAN_DS + "\"",
                    Integer.class)).isZero();
        }

        @Test
        @DisplayName("a destination that does not exist is reported by the open, not by the write")
        void anAbsentDestinationIsReportedByTheOpen() {
            TransactionRepository.OutputFile file = repository.openOutput(
                    sequential("CARDDEMO.TEST.ABSENT"), AccountInterestCalcJob.TRANSACT_DD_NAME);

            assertThat(file.openStatus()).isEqualTo(TransactionRepository.PERMANENT_ERROR_STATUS);
            assertThat(file.openApplResult()).isEqualTo(12);
        }

        @Test
        @DisplayName("a binding of the wrong record width is refused, naming the key to correct")
        void aWrongWidthIsRefused() {
            DatasetBinding wrong = new DatasetBinding(SYSTRAN_DS, "sequential", false, "F", 0,
                    RECORD_LENGTH + 1, "CVTRA05Y", null, null, null, null);

            assertThatIllegalStateException()
                    .isThrownBy(() -> repository.openOutput(wrong,
                            AccountInterestCalcJob.TRANSACT_DD_NAME))
                    .withMessageContaining(AccountInterestCalcJob.TRANSACT_DD_NAME);
        }

        @Test
        @DisplayName("a binding declaring no dataset name is refused")
        void aBlankDatasetNameIsRefused() {
            assertThatIllegalStateException()
                    .isThrownBy(() -> repository.openOutput(sequential("   "),
                            AccountInterestCalcJob.TRANSACT_DD_NAME))
                    .withMessageContaining(AccountInterestCalcJob.TRANSACT_DD_NAME);
        }

        @Test
        @DisplayName("neither argument may be null, and the DD name is checked first")
        void neitherArgumentMayBeNull() {
            assertThatNullPointerException()
                    .isThrownBy(() -> repository.openOutput(sequential(SYSTRAN_DS), null))
                    .withMessageContaining("DD name");
            assertThatNullPointerException()
                    .isThrownBy(() -> repository.openOutput(null,
                            AccountInterestCalcJob.TRANSACT_DD_NAME))
                    .withMessageContaining(AccountInterestCalcJob.TRANSACT_DD_NAME);
        }

        @Test
        @DisplayName("a handle reports the dataset and the DD it was opened for, not the configured ones")
        void aHandleReportsWhatItWasOpenedFor() {
            template.execute("CREATE TABLE \"" + SYSTRAN_DS + ".ALT\" (\"" + IMAGE_COLUMN + "\" CHAR("
                    + RECORD_LENGTH + "))");

            TransactionRepository.OutputFile file = repository.openOutput(
                    sequential(SYSTRAN_DS + ".ALT"), "SYSTRAN2");

            assertThat(file.datasetName()).isEqualTo(SYSTRAN_DS + ".ALT");
            assertThat(file.ddName()).isEqualTo("SYSTRAN2");
        }

        @Test
        @DisplayName("the abnormal disposition deletes the generation this run opened, not another")
        void theDiscardTargetsTheRelationActuallyOpened() {
            // The decisive case. DISP=(NEW,CATLG,DELETE) deletes a generation, and deleting the wrong one
            // is unrecoverable, so a handle must delete what it wrote rather than whatever the repository
            // resolved at construction. Two live destinations, both non-empty, and only one is the
            // generation this run allocated.
            template.execute("CREATE TABLE \"" + SYSTRAN_DS + ".ALT\" (\"" + IMAGE_COLUMN + "\" CHAR("
                    + RECORD_LENGTH + "))");
            template.update("INSERT INTO \"" + SYSTRAN_DS + "\" (\"" + IMAGE_COLUMN + "\") VALUES (?)",
                    " ".repeat(RECORD_LENGTH));

            TransactionRepository.OutputFile file = repository.openOutput(
                    sequential(SYSTRAN_DS + ".ALT"), AccountInterestCalcJob.TRANSACT_DD_NAME);
            assertThat(file.writeSequential(record("2022071800001")).isWritten()).isTrue();

            assertThat(file.discardGeneration()).isEqualTo(FileStatus.OK);

            assertThat(template.queryForObject("SELECT COUNT(*) FROM \"" + SYSTRAN_DS + ".ALT\"",
                    Integer.class)).isZero();
            assertThat(template.queryForObject("SELECT COUNT(*) FROM \"" + SYSTRAN_DS + "\"",
                    Integer.class)).isOne();
        }

        @Test
        @DisplayName("the count that gates the discard is read from the opened relation")
        void theGatingCountIsReadFromTheOpenedRelation() {
            // The configured relation is left holding one record more than this run wrote. Were the count
            // read from there, the counts would disagree and the discard would refuse - so a discard that
            // succeeds proves the count came from the relation actually opened.
            template.execute("CREATE TABLE \"" + SYSTRAN_DS + ".ALT\" (\"" + IMAGE_COLUMN + "\" CHAR("
                    + RECORD_LENGTH + "))");
            template.update("INSERT INTO \"" + SYSTRAN_DS + "\" (\"" + IMAGE_COLUMN + "\") VALUES (?)",
                    " ".repeat(RECORD_LENGTH));
            template.update("INSERT INTO \"" + SYSTRAN_DS + "\" (\"" + IMAGE_COLUMN + "\") VALUES (?)",
                    " ".repeat(RECORD_LENGTH));

            TransactionRepository.OutputFile file = repository.openOutput(
                    sequential(SYSTRAN_DS + ".ALT"), AccountInterestCalcJob.TRANSACT_DD_NAME);
            assertThat(file.writeSequential(record("2022071800001")).isWritten()).isTrue();

            assertThat(file.discardGeneration()).isEqualTo(FileStatus.OK);
            assertThat(template.queryForObject("SELECT COUNT(*) FROM \"" + SYSTRAN_DS + "\"",
                    Integer.class)).isEqualTo(2);
        }

        @Test
        @DisplayName("a close probes the opened relation, so it succeeds where the configured one is gone")
        void aCloseProbesTheOpenedRelation() {
            template.execute("CREATE TABLE \"" + SYSTRAN_DS + ".ALT\" (\"" + IMAGE_COLUMN + "\" CHAR("
                    + RECORD_LENGTH + "))");

            TransactionRepository.OutputFile file = repository.openOutput(
                    sequential(SYSTRAN_DS + ".ALT"), AccountInterestCalcJob.TRANSACT_DD_NAME);
            assertThat(file.writeSequential(record("2022071800001")).isWritten()).isTrue();
            template.execute("DROP TABLE \"" + SYSTRAN_DS + "\"");

            assertThat(file.closeOutput()).isEqualTo(FileStatus.OK);
            assertThat(file.closeApplResult()).isZero();
        }
    }
}
