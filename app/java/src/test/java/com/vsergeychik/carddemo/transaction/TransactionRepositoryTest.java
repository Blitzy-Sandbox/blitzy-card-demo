package com.vsergeychik.carddemo.transaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

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
import com.vsergeychik.carddemo.common.DatasetUnitOfWork;
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
 * The behaviour of {@link TransactionRepository}, asserted against a real relational backend for the access
 * paths and against a stubbed template for the failures a backend will not produce on demand.
 */
@DisplayName("TransactionRepository - the TRANSACT / TRANFILE / SYSTRAN dataset access")
class TransactionRepositoryTest {
    private static final Charset ASCII = StandardCharsets.US_ASCII;

    private static final PhysicalSequence ORDINAL = PhysicalSequence.of("_ROWID_");

    private static final int RECORD_LENGTH = 350;

    private static final int KEY_LENGTH = 16;

    private static final String MASTER_DS = "CARDDEMO.TEST.TRANSACT.VSAM.KSDS";

    private static final String DALY_DS = "CARDDEMO.TEST.TRANSACT.DALY";

    private static final String SYSTRAN_DS = "CARDDEMO.TEST.SYSTRAN";

    private static final String RAGGED_DS = "CARDDEMO.TEST.RAGGED";

    private static final String IMAGE_COLUMN = "RECORD_IMAGE";

    private DataSource dataSource;

    private JdbcTemplate template;

    private TransactionRepository repository;

    @BeforeEach
    void createRelations() {
        dataSource = new SimpleDriverDataSource(new org.h2.Driver(),
                "jdbc:h2:mem:tranrepo-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        template = new JdbcTemplate(dataSource);
        for (String dataset : new String[] { MASTER_DS, DALY_DS, SYSTRAN_DS }) {
            template.execute("CREATE TABLE \"" + dataset + "\" (\"" + IMAGE_COLUMN + "\" CHAR("
                    + RECORD_LENGTH + "))");
        }
        template.execute("CREATE TABLE \"" + RAGGED_DS + "\" (\"" + IMAGE_COLUMN + "\" VARCHAR("
                + RECORD_LENGTH + "))");
        repository = new TransactionRepository(template, validBindings(), ASCII,
                RecordImageForm.CHARACTER, ORDINAL);
    }

    @AfterEach
    void dropRelations() {
        template.execute("DROP ALL OBJECTS");
        template.execute("SHUTDOWN");
    }

    private static DatasetBinding ksds(String dsname) {
        return new DatasetBinding(dsname, "ksds", false, "FB", null, RECORD_LENGTH, "CVTRA05Y",
                KEY_LENGTH, null, null, null);
    }

    /**
     * An indexed binding declared {@code REUSE}, which is what decides whether load mode may reset it.
     *
     * <p>The twelve-argument constructor, because the eleven-argument one defaults {@code reusable} to
     * {@code false} - the shipped value for every cluster in the estate except {@code USRSEC}
     * ({@code app/catlg/LISTCAT.txt:3885}), and the value {@link #ksds(String)} therefore carries.
     *
     * @param dsname the dataset this binding names
     * @return a keyed binding at the copybook width whose cluster is reusable
     */
    private static DatasetBinding reusableKsds(String dsname) {
        return new DatasetBinding(dsname, "ksds", false, "FB", null, RECORD_LENGTH, "CVTRA05Y",
                KEY_LENGTH, null, null, null, true);
    }

    /** A sequential binding at the copybook width, with no key, as a PS dataset has none. */
    private static DatasetBinding sequential(String dsname) {
        return new DatasetBinding(dsname, "sequential", false, "F", 0, RECORD_LENGTH, "CVTRA05Y", null,
                null, null, null);
    }

    private static DatasetBindings validBindings() {
        DatasetBindings bindings = new DatasetBindings();
        bindings.put(TransactionRepository.CICS_FILE_NAME, ksds(MASTER_DS));
        bindings.put(TransactionRepository.INPUT_DD_NAME, ksds(MASTER_DS));
        bindings.put(TransactionRepository.SEQUENTIAL_OUTPUT_DD_NAME, sequential(SYSTRAN_DS));
        return bindings;
    }

    private static DatasetBindings bindingsWith(String key, DatasetBinding replacement) {
        DatasetBindings bindings = validBindings();
        bindings.put(key, replacement);
        return bindings;
    }

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

    private void seed(String dataset, TranRecord record) {
        template.update("INSERT INTO \"" + dataset + "\" VALUES (?)",
                new String(record.rawImage(), ASCII));
    }

    private void seedRaw(String dataset, String image) {
        template.update("INSERT INTO \"" + dataset + "\" VALUES (?)", image);
    }

    private static final class RecordingDataSource extends DelegatingDataSource {
        private final List<Connection> handedOut = new ArrayList<>();

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

        List<PreparedStatement> cursorStatements() {
            return List.copyOf(cursorStatements);
        }

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

    private TransactionRepository repositoryOverMissingRelations() {
        DatasetBindings bindings = new DatasetBindings();
        bindings.put(TransactionRepository.CICS_FILE_NAME, ksds("CARDDEMO.TEST.ABSENT.MASTER"));
        bindings.put(TransactionRepository.INPUT_DD_NAME, ksds("CARDDEMO.TEST.ABSENT.INPUT"));
        bindings.put(TransactionRepository.SEQUENTIAL_OUTPUT_DD_NAME,
                sequential("CARDDEMO.TEST.ABSENT.OUTPUT"));
        return new TransactionRepository(template, bindings, ASCII, RecordImageForm.CHARACTER, ORDINAL);
    }

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

    private static <T> T inUnitOfWork(java.util.function.Supplier<T> work) {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            return work.get();
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

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
            assertThatIllegalStateException()
                    .isThrownBy(() -> repository.write(record("0000000000000001")))
                    .withMessageContaining("no transaction is open on this thread")
                    .withMessageContaining("changes stored records")
                    .withMessageContaining(MASTER_DS);

            assertThat(template.queryForObject(
                    "SELECT COUNT(*) FROM \"" + MASTER_DS + "\"", Integer.class)).isZero();
        }
    }

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

            assertThat(repository.readByTranId("0000000000000009").status())
                    .isEqualTo(FileStatus.NOT_FOUND);
        }

        @Test
        @DisplayName("finding DB-05: a read that found its record is unaffected by an unreadable row")
        void aFoundRecordIsUnaffectedByAnUnreadableRowElsewhere() {
            seed(MASTER_DS, record("0000000000000001"));
            seedRaw(MASTER_DS, null);

            assertThat(repository.readByTranId("0000000000000001").isFound()).isTrue();
        }

        @Test
        @DisplayName("finding DB-05: an empty browse is an end of file and needs no proof")
        void anEmptyBrowseIsStillAnEndOfFileEvenWithAnUnreadableRow() {
            try (TransactionRepository.InputFile input = repository.openInput(sequential(DALY_DS))) {
                assertThat(input.readNext().isEndOfFile()).isTrue();
            }
        }

        @Test
        @DisplayName("finding DB-05: a refused probe is reported rather than reported as absent")
        void aRefusedProbeIsReportedRatherThanAssumedAbsent() {
            seed(MASTER_DS, record("0000000000000001"));
            JdbcTemplate refusingTheProbe = Mockito.spy(template);
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
                assertThat(browse.positioningResult().isNotFound()).isTrue();
                assertThat(browse.isStarted()).isFalse();
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
            browse.close();
            ReadResult afterEnd = browse.readNext();
            assertThat(afterEnd.isOther()).isTrue();
            assertThat(afterEnd.cicsResp()).hasValue(FileStatus.INVREQ);
        }

        @Test
        @DisplayName("ENDBR releases the cursor, so the next STARTBR is positioned afresh")
        void aFreshBrowseAfterEndBrowseStartsClean() {
            Browse first = repository.startBrowse(BrowseDirection.FORWARD);
            assertThat(first.readNext().requireRecord().tranId()).isEqualTo("0000000000000001");
            assertThat(first.readNext().requireRecord().tranId()).isEqualTo("0000000000000002");
            assertThat(first.positionKey()).contains("0000000000000002");
            first.endBrowse();
            assertThat(first.isEnded()).isTrue();

            try (Browse second = repository.startBrowse(BrowseDirection.FORWARD)) {
                assertThat(second.isEnded()).isFalse();
                assertThat(second.anchorKey()).isEmpty();
                assertThat(second.positionKey()).isEmpty();
                assertThat(second.readNext().requireRecord().tranId()).isEqualTo("0000000000000001");
            }
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
                ReadResult refused = browse.positioningResult();
                assertThat(refused.isOther()).isTrue();
                assertThat(refused.isEndOfFile()).isFalse();
                assertThat(refused.diagnostic()).isPresent();
                assertThat(browse.isStarted()).isFalse();
                assertThat(browse.readNext().cicsResp()).hasValue(FileStatus.INVREQ);
            }
        }
    }

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
            seed(DALY_DS, record("0000000000000003"));
            seed(DALY_DS, record("0000000000000002"));
            seed(DALY_DS, record("0000000000000001"));
            RecordingDataSource recording = new RecordingDataSource(dataSource);
            TransactionRepository subject = new TransactionRepository(new JdbcTemplate(recording),
                    validBindings(), ASCII, RecordImageForm.CHARACTER, ORDINAL);

            InputFile input = subject.openInput(sequential(DALY_DS));

            assertThat(input.openStatus()).isEqualTo(FileStatus.OK);
            assertThat(recording.liveConnections()).isZero();

            assertThat(input.readNext().requireRecord().tranId()).isEqualTo("0000000000000003");
            assertThat(recording.liveConnections()).isEqualTo(1);
            assertThat(input.readNext().requireRecord().tranId()).isEqualTo("0000000000000002");
            assertThat(recording.liveConnections()).isEqualTo(1);
            assertThat(input.readNext().requireRecord().tranId()).isEqualTo("0000000000000001");
            assertThat(recording.liveConnections()).isEqualTo(1);
            assertThat(input.readNext().isEndOfFile()).isTrue();
            assertThat(recording.liveConnections()).isEqualTo(1);

            assertThat(input.closeInput()).isEqualTo(FileStatus.OK);
            assertThat(recording.liveConnections()).isZero();
            assertThat(input.closeInput()).isEqualTo(FileStatus.OK);
            assertThat(input.closeApplResult()).isEqualTo(FileStatus.APPL_AOK);
        }

        @Test
        @DisplayName("the pass is prepared forward-only, read-only, with a positive fetch size stated")
        void theCursorIsForwardOnlyReadOnlyAndBounded() throws SQLException {
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

    @Nested
    @DisplayName("The sequential output writes CBACT04C's generated transactions front to back")
    class SequentialOutput {
        @Test
        @DisplayName("records are written in call order, each at exactly 350 bytes, and counted")
        void recordsAreWrittenInOrder() {
            try (OutputFile output = repository.openOutput()) {
                assertThat(output.openStatus()).isEqualTo(FileStatus.OK);
                assertThat(output.openOutcome()).isEqualTo(Outcome.OK);
                assertThat(output.openApplResult()).isEqualTo(FileStatus.APPL_AOK);
                assertThat(output.isOpen()).isTrue();
                assertThat(output.insertStatement()).contains(SYSTRAN_DS).contains("VALUES (?)");
                assertThat(output.recordsWritten()).isZero();

                assertThat(output.writeSequential(record("2022071800002")).isWritten()).isTrue();
                assertThat(output.writeSequential(record("2022071800001")).isWritten()).isTrue();
                assertThat(output.recordsWritten()).isEqualTo(2);
                assertThat(output.closeOutput()).isEqualTo(FileStatus.OK);
                assertThat(output.closeApplResult()).isEqualTo(FileStatus.APPL_AOK);
                assertThat(output.isOpen()).isFalse();
            }

            List<String> stored = template.queryForList(
                    "SELECT \"" + IMAGE_COLUMN + "\" FROM \"" + SYSTRAN_DS + "\"", String.class);

            assertThat(stored).hasSize(2).allSatisfy(image -> {
                assertThat(image).hasSize(RECORD_LENGTH);
                assertThat(image.getBytes(ASCII)).hasSize(RECORD_LENGTH);
            });
            assertThat(stored)
                    .extracting(image -> image.substring(TranRecord.TRAN_ID_OFFSET,
                            TranRecord.TRAN_ID_OFFSET + TranRecord.TRAN_ID_LENGTH))
                    .containsExactly("2022071800002   ", "2022071800001   ");
        }

        @Test
        @DisplayName("an unaddressable destination fails the OPEN, not the first write")
        void anUnaddressableDestinationFailsTheOpen() {
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
            try (OutputFile first = repository.openOutput()) {
                assertThat(first.writeSequential(record("2022071800001")).isWritten()).isTrue();
            }
            assertThat(template.queryForObject(
                    "SELECT COUNT(*) FROM \"" + SYSTRAN_DS + "\"", Integer.class)).isOne();

            try (OutputFile second = repository.openOutput()) {
                assertThat(second.openStatus()).isEqualTo(FileStatus.OK);
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
            OutputFile output = repositoryOverMissingRelations().openOutput();

            assertThat(output.openStatus())
                    .isEqualTo(TransactionRepository.PERMANENT_ERROR_STATUS);
            assertThat(output.openOutcome()).isEqualTo(Outcome.OTHER);
            assertThat(output.openApplResult())
                    .isEqualTo(TransactionRepository.APPL_RESULT_FATAL);

            assertThat(output.closeOutput())
                    .isEqualTo(TransactionRepository.PERMANENT_ERROR_STATUS);
            assertThat(output.closeApplResult())
                    .isEqualTo(TransactionRepository.APPL_RESULT_FATAL);
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
            assertThat(output.closeOutput()).isEqualTo(FileStatus.OK);
            assertThatIllegalStateException()
                    .isThrownBy(() -> output.writeSequential(record("2022071800001")))
                    .withMessageContaining("has been closed");
        }

        @Test
        @DisplayName("the abnormal disposition discards the whole generation this run wrote")
        void theAbnormalDispositionDiscardsTheGeneration() {
            OutputFile output = repository.openOutput();
            assertThat(output.writeSequential(record("2022071800001")).isWritten()).isTrue();
            assertThat(output.writeSequential(record("2022071800002")).isWritten()).isTrue();
            assertThat(output.recordsWritten()).isEqualTo(2);

            assertThat(output.discardGeneration()).isEqualTo(FileStatus.OK);

            assertThat(template.queryForList("SELECT \"" + IMAGE_COLUMN + "\" FROM \"" + SYSTRAN_DS + "\"", String.class)).isEmpty();
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
            OutputFile output = repository.openOutput();
            assertThat(output.writeSequential(record("2022071800001")).isWritten()).isTrue();
            template.execute("DROP TABLE \"" + SYSTRAN_DS + "\"");

            assertThat(output.discardGeneration())
                    .isEqualTo(TransactionRepository.PERMANENT_ERROR_STATUS);
        }
    }

    @Nested
    @DisplayName("The arms a real backend will not produce on demand")
    class StubbedBackendArms {
        private static <T> ResultSetExtractor<T> anyExtractor() {
            return ArgumentMatchers.any();
        }

        private JdbcTemplate stub;

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

            assertThat(input.openStatus()).isEqualTo(FileStatus.OK);
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

            List<Boolean> predicates = List.of(result.isFound(), result.isEndOfFile(),
                    result.isNotFound(), result.isDuplicate(), result.isOther());
            assertThat(predicates).filteredOn(answer -> answer).hasSize(1);
            assertThat(predicates).filteredOn(answer -> !answer).hasSize(4);

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

    @Nested
    @DisplayName("The surface this repository deliberately does not have")
    class AbsentSurface {
        @Test
        @DisplayName("a test's relations are dropped when the test ends, so no schema outlives it")
        void aTestsRelationsAreDroppedWhenTheTestEnds() {
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
            assertThat(publicSurfaceOf(TransactionRepository.class))
                    .as("no mutation or removal of a posted transaction is reachable")
                    .isNotEmpty()
                    .noneMatch(name -> name.contains("rewrite") || name.contains("delete")
                            || name.contains("purge") || name.contains("remove")
                            || name.contains("erase") || name.contains("truncate"));

            assertThat(publicSurfaceOf(TransactionRepository.class))
                    .filteredOn(name -> name.contains("update"))
                    .as("every UPDATE on this surface is the read-with-lock option, never a mutation")
                    .containsOnly("readforupdatebytranid", "selectbykeyforupdate");
        }

        @Test
        @DisplayName("TRANSACT has no alternate index, so there is no second finder (G45)")
        void noAlternateIndexFinder() {
            assertThat(publicSurfaceOf(TransactionRepository.class))
                    .noneMatch(name -> name.contains("altindex") || name.contains("alternateindex")
                            || name.contains("aix"));
        }

        @Test
        @DisplayName("no operation throws an abend: the caller decides, exactly as the COBOL does")
        void noOperationThrowsAnAbend() {
            for (Method method : TransactionRepository.class.getDeclaredMethods()) {
                assertThat(method.getExceptionTypes())
                        .as("%s declares no checked or abend exception", method.getName())
                        .noneMatch(AbendException.class::isAssignableFrom);
            }
        }
    }

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

            assertThat(refused.isWritten()).isFalse();
            assertThat(refused.isDuplicate()).isFalse();
            assertThat(refused.isOther()).isTrue();
            assertThat(refused.status()).isEqualTo(TransactionRepository.PERMANENT_ERROR_STATUS);
            assertThat(refused.applResult()).isEqualTo(TransactionRepository.APPL_RESULT_FATAL);
            assertThat(refused.cicsResp()).hasValue(FileStatus.LENGERR);
            assertThat(refused.observation()).contains(DatasetObservation.recordWidth(width));
            assertThat(refused.ddName()).isEqualTo(TransactionRepository.CICS_FILE_NAME);
            assertThat(template.queryForObject(
                    "SELECT COUNT(*) FROM \"" + MASTER_DS + "\"", Integer.class)).isZero();
        }

        @ParameterizedTest(name = "a sequential write of a {0}-byte record is refused rather than emitted")
        @ValueSource(ints = { 349, 351 })
        @DisplayName("the sequential write refuses any width but 350, because RECFM=F has no slack (G19)")
        void aSequentialWriteOfTheWrongWidthIsRefused(int width) {
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
                assertThat(output.writeSequential(record("2022071800001")).isWritten()).isTrue();
                assertThat(output.recordsWritten()).isEqualTo(1);
            }
            assertThat(template.queryForObject(
                    "SELECT COUNT(*) FROM \"" + SYSTRAN_DS + "\"", Integer.class)).isEqualTo(1);
        }

        @Test
        @DisplayName("a row present but unreadable is told apart from a row that is simply readable")
        void aFetchedRowWithNoRecordImageIsDistinguished() throws SQLException {
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
            int cursor = 0;
            cursor += 16;
            assertThat(TranRecord.TRAN_TYPE_CD_OFFSET).isEqualTo(cursor);
            cursor += 2;
            assertThat(TranRecord.TRAN_CAT_CD_OFFSET).isEqualTo(cursor);
            cursor += 4;
            assertThat(TranRecord.TRAN_SOURCE_OFFSET).isEqualTo(cursor);
            cursor += 10;
            assertThat(TranRecord.TRAN_DESC_OFFSET).isEqualTo(cursor);
            cursor += 100;
            assertThat(TranRecord.TRAN_AMT_OFFSET).isEqualTo(cursor);
            cursor += 11;
            assertThat(TranRecord.TRAN_MERCHANT_ID_OFFSET).isEqualTo(cursor);
            cursor += 9;
            assertThat(TranRecord.TRAN_MERCHANT_NAME_OFFSET).isEqualTo(cursor);
            cursor += 50;
            assertThat(TranRecord.TRAN_MERCHANT_CITY_OFFSET).isEqualTo(cursor);
            cursor += 50;
            assertThat(TranRecord.TRAN_MERCHANT_ZIP_OFFSET).isEqualTo(cursor);
            cursor += 10;
            assertThat(TranRecord.TRAN_CARD_NUM_OFFSET).isEqualTo(cursor);
            cursor += 16;
            assertThat(TranRecord.TRAN_ORIG_TS_OFFSET).isEqualTo(cursor);
            cursor += 26;
            assertThat(TranRecord.TRAN_PROC_TS_OFFSET).isEqualTo(cursor);
            cursor += 26;
            assertThat(TranRecord.FILLER_OFFSET).isEqualTo(cursor);
            cursor += 20;

            assertThat(cursor).isEqualTo(RECORD_LENGTH);
            assertThat(TranRecord.RECORD_LENGTH).isEqualTo(RECORD_LENGTH);
            assertThat(TranRecord.sumOfDeclaredSpanLengths()).isEqualTo(RECORD_LENGTH);
            assertThat(repository.recordLength()).isEqualTo(RECORD_LENGTH);
            assertThat(TranRecord.FILLER_LENGTH).isEqualTo(20);
            assertThat(TranRecord.FILLER_OFFSET + TranRecord.FILLER_LENGTH).isEqualTo(RECORD_LENGTH);
            assertThat(TranRecord.FILLER.kind()).isEqualTo(PictureKind.FILLER);
            assertThat(TranRecord.LAYOUT.storageSpans()).hasSize(14);
            assertThat(TranRecord.LAYOUT.redefinitions()).isEmpty();
        }

        @Test
        @DisplayName("a stored row carries TRAN-CARD-NUM at 262 and TRAN-PROC-DT at 304, as SORT expects")
        void theSortStepsOffsetsAreWhereItExpectsThem() {
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
            assertThat(TranRecord.TRAN_PROC_TS_OFFSET).isEqualTo(TranRecord.TRAN_PROC_DT_OFFSET);
            assertThat(stored.substring(304, 304 + 10)).isEqualTo("2022-07-18");

            assertThat(TranRecord.TRAN_ID_OFFSET).isZero();
            assertThat(repository.keyLength()).isEqualTo(TranRecord.TRAN_ID_KEY_LENGTH);
            assertThat(stored.substring(0, KEY_LENGTH)).isEqualTo("0000000001774260");
            assertThat(stored.substring(330)).isEqualTo(" ".repeat(20));
        }

        @Test
        @DisplayName("TRAN-AMT is eleven zoned DISPLAY bytes at offset 132, sign overpunched, no COMP-3")
        void theAmountOccupiesElevenZonedBytesAtOffset132() {
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
            assertThat(CobolDecimal.COBOL_ROUNDING).isEqualTo(RoundingMode.DOWN);

            TranRecord written = record("0000000000000001");
            written.moveTranAmt(new BigDecimal("504.779"));
            assertThat(inUnitOfWork(() -> repository.write(written)).isWritten()).isTrue();

            BigDecimal readBack = repository.readByTranId("0000000000000001").requireRecord().tranAmt();
            assertThat(readBack).isEqualByComparingTo(new BigDecimal("504.77"));
            assertThat(readBack.scale()).isEqualTo(CobolDecimal.MONETARY_SCALE);
            assertThat(readBack).isEqualTo(new BigDecimal("504.779").setScale(2, RoundingMode.DOWN));
            assertThat(readBack).isNotEqualByComparingTo(
                    new BigDecimal("504.779").setScale(2, RoundingMode.HALF_UP));
            assertThat(repository.readByTranId("0000000000000001").requireRecord().tranAmtImage())
                    .isEqualTo("0000005047G");

            TranRecord negative = record("0000000000000002");
            negative.moveTranAmt(new BigDecimal("-919.006"));
            assertThat(inUnitOfWork(() -> repository.write(negative)).isWritten()).isTrue();

            TranRecord storedNegative =
                    repository.readByTranId("0000000000000002").requireRecord();
            assertThat(storedNegative.tranAmt()).isEqualByComparingTo(new BigDecimal("-919.00"));
            assertThat(storedNegative.tranAmt().scale()).isEqualTo(2);
            assertThat(storedNegative.tranAmt()).isNotEqualByComparingTo(
                    new BigDecimal("-919.006").setScale(2, RoundingMode.FLOOR));
            assertThat(storedNegative.tranAmtImage()).isEqualTo("0000009190}");
            assertThat(storedNegative.hasZeroTranAmt()).isFalse();
        }

        @Test
        @DisplayName("a whole-value negative zero survives, because the raw span is what moves (R5)")
        void aNegativeZeroSurvivesOnlyOnTheRawSpan() {
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
            assertThat(readBack.tranAmt()).isEqualByComparingTo(CobolDecimal.monetaryZero());
            assertThat(readBack.hasZeroTranAmt()).isTrue();

            TranRecord reEncoded = readBack.copy();
            reEncoded.moveTranAmt(readBack.tranAmt());
            assertThat(reEncoded.tranAmtImage()).isEqualTo("0000000000{");
            assertThat(reEncoded.rawImage()).isNotEqualTo(beforeWrite);

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

            String otherName = "CARDDEMO.OTHER.TRANSACT.VSAM.KSDS";
            TransactionRepository rebound = new TransactionRepository(template,
                    bindingsWith(TransactionRepository.CICS_FILE_NAME, ksds(otherName)), ASCII,
                    RecordImageForm.CHARACTER, ORDINAL);
            assertThat(rebound.datasetName()).isEqualTo(otherName);
            assertThat(rebound.describeStatement()).contains(otherName);
            assertThat(TransactionRepository.CICS_FILE_NAME).isEqualTo("TRANSACT");
            assertThat(TransactionRepository.INPUT_DD_NAME).isEqualTo("TRANFILE");
            assertThat(TransactionRepository.SEQUENTIAL_OUTPUT_DD_NAME).isEqualTo("SYSTRAN");
        }

        @Test
        @DisplayName("no schema artefact of any kind is involved in reaching the dataset (G44)")
        void noSchemaArtefactIsInvolved() {
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
            assertThat(TranRecord.LAYOUT.storageSpans())
                    .extracting(FieldSpan::name)
                    .containsExactly("TRAN-ID", "TRAN-TYPE-CD", "TRAN-CAT-CD", "TRAN-SOURCE",
                            "TRAN-DESC", "TRAN-AMT", "TRAN-MERCHANT-ID", "TRAN-MERCHANT-NAME",
                            "TRAN-MERCHANT-CITY", "TRAN-MERCHANT-ZIP", "TRAN-CARD-NUM", "TRAN-ORIG-TS",
                            "TRAN-PROC-TS", "FILLER");
        }
    }

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

    // =================================================================================================
    // openLoadMode() - OPEN OUTPUT of the INDEXED master, app/cbl/CBTRN02C.cbl:256.
    //
    // Three outcomes, and the middle one is a mutation: over a cluster catalogued REUSE, VSAM load mode
    // RESETS the cluster, so the open empties it and only then reports '00'. Reporting '00' while leaving
    // the records in place is the case these tests exist to keep out - the run would then post on top of
    // records the source's own open had already discarded, and every count and balance computed from the
    // master afterwards would be computed over a dataset the mainframe would have emptied.
    // =================================================================================================

    @Nested
    @DisplayName("openLoadMode() - empty opens, REUSE resets then opens, NOREUSE refuses")
    class TheLoadModeOpen {

        @Test
        @DisplayName("an EMPTY cluster opens, and there is nothing to reset")
        void anEmptyClusterOpens() {
            TransactionRepository.LoadModeFile file = repository.openLoadMode();

            assertThat(file.openStatus()).isEqualTo(FileStatus.OK);
            assertThat(file.openApplResult()).isEqualTo(FileStatus.APPL_AOK);
            assertThat(file.openOutcome()).isEqualTo(Outcome.OK);
            assertThat(file.datasetName()).isEqualTo(MASTER_DS);
            assertThat(file.ddName()).isEqualTo(TransactionRepository.INPUT_DD_NAME);
            assertThat(heldByTheMaster()).isZero();
            assertThat(file.closeLoadMode()).isEqualTo(FileStatus.OK);
        }

        @Test
        @DisplayName("a NON-EMPTY NOREUSE cluster is refused with '37', and NOTHING is removed")
        void aNonEmptyNoReuseClusterIsRefused() {
            // The shipped configuration: LISTCAT.txt:3595-3597 records the master NOREUSE holding 311
            // records. Load mode cannot reset it, so the open reports '37' - and emptying it to make the
            // open succeed would be inventing the precondition the source fails on.
            seed(MASTER_DS, record("0000000000000001"));
            seed(MASTER_DS, record("0000000000000002"));

            TransactionRepository.LoadModeFile file = repository.openLoadMode();

            assertThat(file.openStatus()).isEqualTo(FileStatus.OPEN_MODE_CONFLICT);
            assertThat(file.openApplResult()).isEqualTo(12);
            assertThat(file.openOutcome()).isEqualTo(Outcome.OTHER);
            assertThat(heldByTheMaster())
                    .as("a refused open removes nothing at all")
                    .isEqualTo(2);
        }

        @Test
        @DisplayName("a NON-EMPTY REUSE cluster is RESET by the open, and only then reports '00'")
        void aNonEmptyReusableClusterIsResetBeforeTheOpenSucceeds() {
            // The case the finding was about. reusable=true is a statement about the cluster, and what it
            // states is that OPEN OUTPUT resets it: the records it held are gone the moment load mode
            // begins. So '00' and an empty cluster go together - '00' over retained records would be the
            // divergence, because the run's postings would land on top of them.
            TransactionRepository reusable = reusableMasterRepository();
            seed(MASTER_DS, record("0000000000000001"));
            seed(MASTER_DS, record("0000000000000002"));
            seed(MASTER_DS, record("0000000000000003"));

            TransactionRepository.LoadModeFile file = reusable.openLoadMode();

            assertThat(file.openStatus()).isEqualTo(FileStatus.OK);
            assertThat(file.openApplResult()).isEqualTo(FileStatus.APPL_AOK);
            assertThat(heldByTheMaster())
                    .as("load mode over a REUSE cluster resets it, so a successful open leaves it empty")
                    .isZero();
            assertThat(file.closeLoadMode()).isEqualTo(FileStatus.OK);
        }

        @Test
        @DisplayName("the reset is the whole cluster, so a record written after it is the only one there")
        void theRunWritesIntoAnEmptyCluster() {
            // What the reset is for, asserted end to end: the postings that follow the open are the only
            // records the master holds, which is what load mode means.
            TransactionRepository reusable = reusableMasterRepository();
            seed(MASTER_DS, record("0000000000000001"));

            assertThat(reusable.openLoadMode().openStatus()).isEqualTo(FileStatus.OK);
            assertThat(inUnitOfWork(() -> reusable.write(record("0000000000000009"))).isWritten())
                    .isTrue();

            assertThat(heldByTheMaster()).isOne();
            assertThat(reusable.readByTranId("0000000000000009").isFound()).isTrue();
            assertThat(reusable.readByTranId("0000000000000001").isNotFound())
                    .as("the record the reset removed is not readable afterwards")
                    .isTrue();
        }

        @Test
        @DisplayName("an already-empty REUSE cluster opens without issuing the reset")
        void anEmptyReusableClusterOpensWithoutResetting() {
            // The count decides whether the reset is issued at all, so an empty reusable cluster takes the
            // first arm - the same outcome, one statement fewer, and no delete over a cluster that holds
            // nothing.
            TransactionRepository reusable = reusableMasterRepository();

            assertThat(reusable.openLoadMode().openStatus()).isEqualTo(FileStatus.OK);
            assertThat(heldByTheMaster()).isZero();
        }

        @Test
        @DisplayName("a REFUSED reset reports the refusal, never '00' over records that are still there")
        void aRefusedResetIsReportedRatherThanClaimedAsAnOpen() {
            // The arm that matters most: if the reset cannot be applied, the open must NOT report success.
            // A template whose count succeeds and whose update is refused reproduces exactly that -
            // a backend that permits the read and rejects the delete.
            JdbcTemplate refusing = mock(JdbcTemplate.class);
            doReturn(3).when(refusing).queryForObject(anyString(), eq(Integer.class));
            doThrow(new DataAccessResourceFailureException("the delete was refused"))
                    .when(refusing).update(anyString());

            TransactionRepository repositoryOverRefusingBackend = new TransactionRepository(refusing,
                    reusableBindings(), ASCII, RecordImageForm.CHARACTER, ORDINAL);

            TransactionRepository.LoadModeFile file = repositoryOverRefusingBackend.openLoadMode();

            assertThat(file.openStatus())
                    .as("a refused reset is a failed open, not a successful one")
                    .isEqualTo(TransactionRepository.PERMANENT_ERROR_STATUS);
            assertThat(file.openApplResult()).isEqualTo(12);
            assertThat(file.openOutcome()).isEqualTo(Outcome.OTHER);
        }

        @Test
        @DisplayName("a refused COUNT is reported too, so an unreachable cluster is not an open")
        void aRefusedCountIsReported() {
            JdbcTemplate refusing = mock(JdbcTemplate.class);
            doThrow(new DataAccessResourceFailureException("the count was refused"))
                    .when(refusing).queryForObject(anyString(), eq(Integer.class));

            TransactionRepository.LoadModeFile file = new TransactionRepository(refusing,
                    reusableBindings(), ASCII, RecordImageForm.CHARACTER, ORDINAL).openLoadMode();

            assertThat(file.openStatus()).isEqualTo(TransactionRepository.PERMANENT_ERROR_STATUS);
        }

        @Test
        @DisplayName("a null count is read as empty, so a backend that answers nothing still opens")
        void aNullCountIsReadAsEmpty() {
            JdbcTemplate silent = mock(JdbcTemplate.class);
            doReturn(null).when(silent).queryForObject(anyString(), eq(Integer.class));

            TransactionRepository.LoadModeFile file = new TransactionRepository(silent,
                    reusableBindings(), ASCII, RecordImageForm.CHARACTER, ORDINAL).openLoadMode();

            assertThat(file.openStatus()).isEqualTo(FileStatus.OK);
            verify(silent, never()).update(anyString());
        }

        @Test
        @DisplayName("each open is its own handle, so two runs never share a status or an open flag")
        void eachOpenIsItsOwnHandle() {
            TransactionRepository.LoadModeFile first = repository.openLoadMode();
            TransactionRepository.LoadModeFile second = repository.openLoadMode();

            assertThat(first).isNotSameAs(second);
            assertThat(first.isOpen()).isTrue();
            assertThat(first.closeLoadMode()).isEqualTo(FileStatus.OK);
            assertThat(first.isOpen()).isFalse();
            assertThat(second.isOpen())
                    .as("closing one handle cannot close another (practice B9, gate G53)")
                    .isTrue();
        }

        /** The number of records the master relation actually holds, read straight from the backend. */
        private int heldByTheMaster() {
            Integer held = template.queryForObject("SELECT COUNT(*) FROM \"" + MASTER_DS + "\"",
                    Integer.class);
            return held == null ? 0 : held;
        }

        /** The shipped bindings with {@value TransactionRepository#INPUT_DD_NAME} declared REUSE. */
        private DatasetBindings reusableBindings() {
            DatasetBindings bindings = new DatasetBindings();
            bindings.put(TransactionRepository.CICS_FILE_NAME, ksds(MASTER_DS));
            bindings.put(TransactionRepository.INPUT_DD_NAME, reusableKsds(MASTER_DS));
            bindings.put(TransactionRepository.SEQUENTIAL_OUTPUT_DD_NAME, sequential(SYSTRAN_DS));
            return bindings;
        }

        /** A repository over this test's database whose load-mode DD is a REUSE cluster. */
        private TransactionRepository reusableMasterRepository() {
            return new TransactionRepository(template, reusableBindings(), ASCII,
                    RecordImageForm.CHARACTER, ORDINAL);
        }
    }
}
