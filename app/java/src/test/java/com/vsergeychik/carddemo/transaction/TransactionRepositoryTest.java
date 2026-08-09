package com.vsergeychik.carddemo.transaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import com.vsergeychik.carddemo.common.AbendException;
import com.vsergeychik.carddemo.common.CicsResponse;
import com.vsergeychik.carddemo.common.DatasetObservation;
import com.vsergeychik.carddemo.common.DatasetRelation.BackendDiagnostic;
import com.vsergeychik.carddemo.common.FileStatus;
import com.vsergeychik.carddemo.common.FileStatus.Outcome;
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

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentMatchers;
import java.sql.SQLException;

import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;

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
 *   <li>that this class never throws an abend, and that no rewrite or delete exists to call.</li>
 * </ul>
 */
@DisplayName("TransactionRepository - the TRANSACT / TRANFILE / SYSTRAN dataset access")
class TransactionRepositoryTest {

    /** The code page of the ASCII fixtures, named explicitly - never a platform default. */
    private static final Charset ASCII = StandardCharsets.US_ASCII;

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

    /** Distinguishes each test's in-memory database, so no test can see another's rows. */
    private static final AtomicInteger DATABASE_SEQUENCE = new AtomicInteger();

    /** The live data source for the test in progress. */
    private DataSource dataSource;

    /** The template over {@link #dataSource}. */
    private JdbcTemplate template;

    /** The repository under test, bound to the tables created in {@link #createRelations()}. */
    private TransactionRepository repository;

    @BeforeEach
    void createRelations() {
        dataSource = new SimpleDriverDataSource(new org.h2.Driver(),
                "jdbc:h2:mem:tranrepo" + DATABASE_SEQUENCE.incrementAndGet() + ";DB_CLOSE_DELAY=-1",
                "sa", "");
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
                RecordImageForm.CHARACTER);
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

    /** A repository whose datasets do not exist, so every statement is refused. */
    private TransactionRepository repositoryOverMissingRelations() {
        DatasetBindings bindings = new DatasetBindings();
        bindings.put(TransactionRepository.CICS_FILE_NAME, ksds("CARDDEMO.TEST.ABSENT.MASTER"));
        bindings.put(TransactionRepository.INPUT_DD_NAME, ksds("CARDDEMO.TEST.ABSENT.INPUT"));
        bindings.put(TransactionRepository.SEQUENTIAL_OUTPUT_DD_NAME,
                sequential("CARDDEMO.TEST.ABSENT.OUTPUT"));
        return new TransactionRepository(template, bindings, ASCII, RecordImageForm.CHARACTER);
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
                    null, bindings, ASCII, RecordImageForm.CHARACTER))
                    .withMessageContaining("JdbcTemplate");
            assertThatNullPointerException().isThrownBy(() -> new TransactionRepository(
                    template, null, ASCII, RecordImageForm.CHARACTER))
                    .withMessageContaining("carddemo.datasets");
            assertThatNullPointerException().isThrownBy(() -> new TransactionRepository(
                    template, bindings, null, RecordImageForm.CHARACTER))
                    .withMessageContaining("code page");
            assertThatNullPointerException().isThrownBy(() -> new TransactionRepository(
                    template, bindings, ASCII, null))
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
                            ASCII, RecordImageForm.CHARACTER))
                    .withMessageContaining("CVTRA05Y")
                    .withMessageContaining(ddName);
        }

        @Test
        @DisplayName("the master must be indexed, because every keyed path addresses its key")
        void masterMustBeIndexed() {
            assertThatIllegalStateException()
                    .isThrownBy(() -> new TransactionRepository(template,
                            bindingsWith(TransactionRepository.CICS_FILE_NAME, sequential(MASTER_DS)),
                            ASCII, RecordImageForm.CHARACTER))
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
                            RecordImageForm.CHARACTER))
                    .withMessageContaining("TRAN-ID PIC X(16)");
            DatasetBinding wrongKeyLength = new DatasetBinding(MASTER_DS, "ksds", false, "FB", null,
                    RECORD_LENGTH, "CVTRA05Y", KEY_LENGTH + 1, null, null, null);
            assertThatIllegalStateException()
                    .isThrownBy(() -> new TransactionRepository(template,
                            bindingsWith(TransactionRepository.CICS_FILE_NAME, wrongKeyLength), ASCII,
                            RecordImageForm.CHARACTER))
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
                            RecordImageForm.CHARACTER))
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
                            RecordImageForm.CHARACTER))
                    .withMessageContaining("base cluster");
            DatasetBinding withAlternateKey = new DatasetBinding(MASTER_DS, "ksds", false, "FB", null,
                    RECORD_LENGTH, "CVTRA05Y", KEY_LENGTH, null, null, "TRAN-CARD-NUM");
            assertThatIllegalStateException()
                    .isThrownBy(() -> new TransactionRepository(template,
                            bindingsWith(TransactionRepository.CICS_FILE_NAME, withAlternateKey), ASCII,
                            RecordImageForm.CHARACTER))
                    .withMessageContaining("no alternate index");
        }

        @Test
        @DisplayName("SYSTRAN is written front to back, so a keyed binding for it is refused")
        void sequentialOutputIsNotKeyed() {
            assertThatIllegalStateException()
                    .isThrownBy(() -> new TransactionRepository(template,
                            bindingsWith(TransactionRepository.SEQUENTIAL_OUTPUT_DD_NAME,
                                    ksds(SYSTRAN_DS)),
                            ASCII, RecordImageForm.CHARACTER))
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
                            RecordImageForm.CHARACTER))
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
                            RecordImageForm.CHARACTER));
        }

        @Test
        @DisplayName("an unconfigured DD name is reported by the catalogue, naming every configured key")
        void missingDdNameIsReported() {
            DatasetBindings incomplete = new DatasetBindings();
            incomplete.put(TransactionRepository.CICS_FILE_NAME, ksds(MASTER_DS));
            assertThatIllegalStateException()
                    .isThrownBy(() -> new TransactionRepository(template, incomplete, ASCII,
                            RecordImageForm.CHARACTER))
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
            WriteResult result = repository.write(written);

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
            assertThat(repository.write(written).isWritten()).isTrue();

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
            assertThat(repository.write(record("0000000000000001")).isWritten()).isTrue();

            WriteResult duplicate = repository.write(record("0000000000000001"));

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
            WriteResult refused = repositoryOverMissingRelations().write(record("0000000000000001"));

            assertThat(refused.isOther()).isTrue();
            assertThat(refused.status()).isEqualTo(TransactionRepository.PERMANENT_ERROR_STATUS);
            assertThat(refused.applResult()).isEqualTo(TransactionRepository.APPL_RESULT_FATAL);
            assertThat(refused.diagnostic()).isPresent();
            assertThat(refused.describeResponse()).isEqualTo("Resp:none Reas:0");
        }

        @Test
        @DisplayName("the key predicate escapes LIKE metacharacters, so a wildcard key matches nothing")
        void theKeyPredicateEscapesWildcards() {
            assertThat(repository.write(record("0000000000000001")).isWritten()).isTrue();

            // '%' would match every record if it were not escaped, which would make this a duplicate.
            assertThat(repository.write(record("%%%%%%%%%%%%%%%%")).isWritten()).isTrue();
            assertThat(repository.readByTranId("%%%%%%%%%%%%%%%%").isFound()).isTrue();
            assertThat(repository.readByTranId("________________").isNotFound()).isTrue();
        }

        @Test
        @DisplayName("a record is required, because the WRITE is addressed by the key it carries")
        void aRecordIsRequired() {
            assertThatNullPointerException().isThrownBy(() -> repository.write(null))
                    .withMessageContaining("TRAN-ID");
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
                assertThat(browse.readPrev().isEndOfFile()).isTrue();
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
                assertThat(forward.readNext().isEndOfFile()).isTrue();
            }
            try (Browse backward =
                    repository.startBrowse("0000000000000000", BrowseDirection.BACKWARD)) {
                assertThat(backward.readPrev().isEndOfFile()).isTrue();
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
                ReadResult refused = browse.readNext();
                assertThat(refused.isOther()).isTrue();
                assertThat(refused.isEndOfFile()).isFalse();
                assertThat(refused.diagnostic()).isPresent();
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
            seed(DALY_DS, record("0000000000000002"));
            seed(DALY_DS, record("0000000000000001"));

            try (InputFile input = repository.openInput(sequential(DALY_DS))) {
                assertThat(input.openStatus()).isEqualTo(FileStatus.OK);
                assertThat(input.datasetName()).contains(DALY_DS);
                // Written order, NOT key order: an ORDER BY here would reorder the file the SORT step
                // produced (app/jcl/TRANREPT.jcl:46 sorts by TRAN-CARD-NUM, not by TRAN-ID).
                assertThat(input.readNext().requireRecord().tranId()).isEqualTo("0000000000000002");
                assertThat(input.readNext().requireRecord().tranId()).isEqualTo("0000000000000001");
                assertThat(input.readNext().isEndOfFile()).isTrue();
                assertThat(input.readNext().isEndOfFile()).isTrue();
                assertThat(input.position()).isEqualTo(2);
            }
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
        @DisplayName("records are written in call order, at full width, and counted")
        void recordsAreWrittenInOrder() {
            try (OutputFile output = repository.openOutput()) {
                assertThat(output.openStatus()).isEqualTo(FileStatus.OK);
                assertThat(output.openOutcome()).isEqualTo(Outcome.OK);
                assertThat(output.openApplResult()).isEqualTo(FileStatus.APPL_AOK);
                assertThat(output.isOpen()).isTrue();
                assertThat(output.insertStatement()).contains(SYSTRAN_DS).contains("VALUES (?)");
                assertThat(output.recordsWritten()).isZero();

                assertThat(output.writeSequential(record("2022071800001")).isWritten()).isTrue();
                assertThat(output.writeSequential(record("2022071800002")).isWritten()).isTrue();
                assertThat(output.recordsWritten()).isEqualTo(2);
                assertThat(output.closeOutput()).isEqualTo(FileStatus.OK);
                assertThat(output.closeApplResult()).isEqualTo(FileStatus.APPL_AOK);
                assertThat(output.isOpen()).isFalse();
            }

            assertThat(template.queryForList(
                    "SELECT \"" + IMAGE_COLUMN + "\" FROM \"" + SYSTRAN_DS + "\"", String.class))
                    .hasSize(2)
                    .allSatisfy(image -> assertThat(image).hasSize(RECORD_LENGTH));
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
                    RecordImageForm.CHARACTER);
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
            // The pass then yields nothing, because the same stub answers the read with nothing - which
            // presents as an immediate end of file rather than as a silent success.
            assertThat(input.readNext().isEndOfFile()).isTrue();
        }

        @Test
        @DisplayName("a template that yields no result object at all is reported, never taken as empty")
        void noResultObjectIsReported() {
            doReturn(IMAGE_COLUMN).when(stub).query(anyString(), anyExtractor());
            doReturn(null).when(stub).query(any(PreparedStatementCreator.class),
                    anyExtractor());

            // A keyed read of nothing is a missing record; a browse of nothing is an end of file. Both
            // are reached from the same null answer, which is why the two are distinguished by the
            // caller's own access path rather than by the answer.
            assertThat(stubbed.readByTranId("0000000000000001").isNotFound()).isTrue();
            try (Browse browse = stubbed.startBrowse(BrowseDirection.FORWARD)) {
                assertThat(browse.readNext().isEndOfFile()).isTrue();
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

            WriteResult result = stubbed.write(record("0000000000000001"));

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

            WriteResult result = stubbed.write(record("0000000000000001"));

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

            WriteResult result = stubbed.write(record("0000000000000001"));

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

            WriteResult result = stubbed.write(record("0000000000000001"));

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
        @DisplayName("there is no rewrite and no delete, because no program in the estate performs one")
        void noRewriteAndNoDelete() {
            // app/csd/CARDDEMO.CSD:81-82 grants UPDATE(YES) DELETE(YES), but a granted capability is not
            // an access path: a verified scan of all 28 programs in app/cbl finds no REWRITE and no
            // DELETE against TRANSACT or TRANFILE. Asserting the absence keeps a future edit from
            // quietly adding a mutation of the audit trail the legacy system cannot perform.
            assertThat(TransactionRepository.class.getMethods())
                    .extracting(java.lang.reflect.Method::getName)
                    .doesNotContain("rewrite", "delete", "deleteByTranId", "rewriteByTranId");
        }

        @Test
        @DisplayName("TRANSACT has no alternate index, so there is no second finder (G45)")
        void noAlternateIndexFinder() {
            assertThat(TransactionRepository.class.getMethods())
                    .extracting(java.lang.reflect.Method::getName)
                    .noneMatch(name -> name.contains("ViaAltIndex") || name.contains("AlternateIndex"));
        }

        @Test
        @DisplayName("no operation throws an abend: the caller decides, exactly as the COBOL does")
        void noOperationThrowsAnAbend() {
            for (java.lang.reflect.Method method : TransactionRepository.class.getDeclaredMethods()) {
                assertThat(method.getExceptionTypes())
                        .as("%s declares no checked or abend exception", method.getName())
                        .noneMatch(AbendException.class::isAssignableFrom);
            }
        }
    }
}
