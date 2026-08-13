package com.vsergeychik.carddemo.card;

import com.vsergeychik.carddemo.card.CardRepository.BrowseDirection;
import com.vsergeychik.carddemo.card.CardRepository.CardBrowse;
import com.vsergeychik.carddemo.card.CardRepository.CardReadResult;
import com.vsergeychik.carddemo.card.CardRepository.CardWriteResult;
import com.vsergeychik.carddemo.card.CardRepository.FetchedRows;
import com.vsergeychik.carddemo.card.model.CardRecord;
import com.vsergeychik.carddemo.common.DatasetIntegrityException;
import com.vsergeychik.carddemo.common.DatasetObservation;
import com.vsergeychik.carddemo.common.FileStatus;
import com.vsergeychik.carddemo.common.FileStatus.Outcome;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.config.CobolCharsetConfig;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBinding;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBindings;
import com.vsergeychik.carddemo.common.RecordImageForm;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.annotation.Annotation;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.jdbc.InvalidResultSetAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.core.PreparedStatementSetter;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link CardRepository}, the single data-access component for the {@code CARDDAT} base
 * cluster and its {@code CARDAIX} alternate-index path.
 */
@DisplayName("CardRepository - CARDDAT base cluster and CARDAIX alternate-index path")
class CardRepositoryTest {
    private static final String FIRST_FIXTURE_CARD_NUM = "0500024453765740";

    private static final String SECOND_FIXTURE_CARD_NUM = "0683586198171516";

    private static final long FIRST_FIXTURE_ACCT_ID = 50L;

    private static final int FIRST_FIXTURE_CVV_CD = 747;

    private static final String FIRST_FIXTURE_EXPIRAION_DATE = "2023-03-09";

    private static final String FIRST_FIXTURE_EMBOSSED_NAME = "Aniya Von";

    private static final String FIRST_FIXTURE_ACTIVE_STATUS = "Y";

    private static final String FIXTURE = "/fixtures/carddata.txt";

    private static final int FIXTURE_RECORDS = 50;

    private static final Charset FIXTURE_CHARSET = StandardCharsets.US_ASCII;

    private static final Charset EBCDIC_CHARSET = Charset.forName("IBM037");

    private static final long WIDE_ACCT_ID = 10_000_000_010L;

    private static final int VENDOR_ERROR_CODE = 42_101;

    private JdbcTemplate jdbcTemplate;

    private FixedWidthCodec codec;

    private CardRepository repository;

    private static final String DESCRIBED_COLUMN = "VSAM_RECORD_IMAGE";

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        codec = new FixedWidthCodec(StandardCharsets.US_ASCII);
        repository = new CardRepository(jdbcTemplate, bindings(), codec, RecordImageForm.CHARACTER);
        stubDescribe(repository);
    }

    private static <T> T inUnitOfWork(Supplier<T> work) {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            return work.get();
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    private void stubDescribe(CardRepository target) {
        when(jdbcTemplate.query(eq(target.describeBaseStatement()),
                CardRepositoryTest.<String>anyExtractor())).thenReturn(DESCRIBED_COLUMN);
        when(jdbcTemplate.query(eq(target.describeAlternateIndexStatement()),
                CardRepositoryTest.<String>anyExtractor())).thenReturn(DESCRIBED_COLUMN);
    }

    private CardRepository.BaseStatements statementsOf(CardRepository target) {
        stubDescribe(target);
        when(jdbcTemplate.query(any(PreparedStatementCreator.class),
                CardRepositoryTest.<FetchedRows>anyExtractor())).thenReturn(FetchedRows.empty());
        target.readByCardNumber(FIRST_FIXTURE_CARD_NUM);
        return target.resolvedBaseStatements();
    }

    private CardRepository.AlternateStatements alternateStatementsOf(CardRepository target) {
        stubDescribe(target);
        when(jdbcTemplate.query(any(PreparedStatementCreator.class),
                CardRepositoryTest.<FetchedRows>anyExtractor())).thenReturn(FetchedRows.empty());
        target.readByAccountIdViaAltIndex(FIRST_FIXTURE_ACCT_ID);
        return target.resolvedAlternateStatements();
    }

    private static DatasetBindings bindings() {
        return bindings(cardDatBinding(), cardAixBinding());
    }

    private static DatasetBindings bindings(DatasetBinding base, DatasetBinding alternateIndex) {
        DatasetBindings catalogue = new DatasetBindings();
        if (base != null) {
            catalogue.put(CardRepository.BASE_DD_NAME, base);
        }
        if (alternateIndex != null) {
            catalogue.put(CardRepository.ALTERNATE_INDEX_DD_NAME, alternateIndex);
        }
        return catalogue;
    }

    private static DatasetBinding cardDatBinding() {
        return cardDatBinding("CARDDEMO.CARDDATA.KSDS", CardRecord.RECORD_LENGTH);
    }

    private static DatasetBinding cardDatBinding(String dsname, int recordLength) {
        return new DatasetBinding(dsname, "ksds", false, "FB", null, recordLength, "CVACT02Y", null,
                null,
                null, null);
    }

    private static DatasetBinding cardAixBinding() {
        return cardAixBinding(CardRepository.BASE_DD_NAME, "CARD-ACCT-ID", CardRecord.RECORD_LENGTH);
    }

    private static DatasetBinding cardAixBinding(String base, String alternateKey, int recordLength) {
        return new DatasetBinding("CARDDEMO.CARDDATA.AIX.PATH", "aix-path", false, "FB", null,
                recordLength, "CVACT02Y", null, null, base, alternateKey);
    }

    private static List<String> fixtureRows() {
        try (InputStream stream = CardRepositoryTest.class.getResourceAsStream(FIXTURE)) {
            assertThat(stream).as("the fixture %s must be on the test classpath", FIXTURE).isNotNull();
            List<String> rows = new ArrayList<>();
            for (String line : new String(stream.readAllBytes(), FIXTURE_CHARSET).split("\n", -1)) {
                if (!line.isEmpty()) {
                    rows.add(line);
                }
            }
            return rows;
        } catch (IOException unreadable) {
            throw new UncheckedIOException("Unable to read the CARDDAT fixture " + FIXTURE, unreadable);
        }
    }

    private static String classpathText(String resource) {
        try (InputStream stream = CardRepositoryTest.class.getResourceAsStream(resource)) {
            assertThat(stream).as("%s must be on the test classpath", resource).isNotNull();
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            throw new UncheckedIOException("Unable to read " + resource, unreadable);
        }
    }

    private static boolean declaresKey(String yaml, String key) {
        for (String line : yaml.split("\n", -1)) {
            if (line.strip().equals(key + ":")) {
                return true;
            }
        }
        return false;
    }

    private static CardRecord cardRecord(String cardNum) {
        return new CardRecord(cardNum, FIRST_FIXTURE_ACCT_ID, 123, "Aniya Von", "2022-01-01", "Y");
    }

    private void stubFetch(FetchedRows rows) {
        when(jdbcTemplate.query(any(PreparedStatementCreator.class),
                CardRepositoryTest.<FetchedRows>anyExtractor())).thenReturn(rows);
    }

    private void stubRejection(DataAccessException rejection) {
        when(jdbcTemplate.query(any(PreparedStatementCreator.class),
                CardRepositoryTest.<FetchedRows>anyExtractor())).thenThrow(rejection);
    }

    @SuppressWarnings("unchecked")
    private static <T> ResultSetExtractor<T> anyExtractor() {
        return any(ResultSetExtractor.class);
    }

    private List<String> preparedStatements(int expectedCalls) throws SQLException {
        ArgumentCaptor<PreparedStatementCreator> captor =
                ArgumentCaptor.forClass(PreparedStatementCreator.class);
        verify(jdbcTemplate, times(expectedCalls)).query(captor.capture(),
                CardRepositoryTest.<FetchedRows>anyExtractor());
        List<String> statements = new ArrayList<>();
        for (PreparedStatementCreator creator : captor.getAllValues()) {
            Connection connection = mock(Connection.class);
            when(connection.prepareStatement(anyString())).thenReturn(mock(PreparedStatement.class));
            creator.createPreparedStatement(connection);
            ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
            verify(connection).prepareStatement(text.capture());
            statements.add(text.getValue());
        }
        return statements;
    }

    private FetchedRows oneRow(CardRecord record) {
        return new FetchedRows(record.encode(codec), 1);
    }

    private PreparedStatement capturePreparedStatement() throws SQLException {
        ArgumentCaptor<PreparedStatementCreator> captor =
                ArgumentCaptor.forClass(PreparedStatementCreator.class);
        verify(jdbcTemplate, atLeastOnce()).query(captor.capture(),
                CardRepositoryTest.<FetchedRows>anyExtractor());
        Connection connection = mock(Connection.class);
        PreparedStatement prepared = mock(PreparedStatement.class);
        when(connection.prepareStatement(anyString())).thenReturn(prepared);
        assertThat(captor.getAllValues().get(0).createPreparedStatement(connection)).isSameAs(prepared);
        return prepared;
    }

    static List<Arguments> alphanumericMoveCases() {
        return List.of(
                Arguments.of(FIRST_FIXTURE_CARD_NUM, FIRST_FIXTURE_CARD_NUM),
                Arguments.of("12345" + " ".repeat(11), "12345" + " ".repeat(11)),
                Arguments.of("12345", "12345" + " ".repeat(11)),
                Arguments.of("", " ".repeat(CardRecord.CARD_NUM_LENGTH)),
                Arguments.of("01234567890123456789", "0123456789012345"));
    }

    @Nested
    @DisplayName("construction")
    class ConstructionTest {
        @Test
        @DisplayName("composes every statement from the configured dataset names and nothing else")
        void composesStatementsFromConfiguration() {
            assertThat(repository.baseDatasetName()).isEqualTo("CARDDEMO.CARDDATA.KSDS");
            assertThat(repository.alternateIndexDatasetName())
                    .isEqualTo("CARDDEMO.CARDDATA.AIX.PATH");

            assertThat(repository.describeBaseStatement())
                    .isEqualTo("SELECT * FROM \"CARDDEMO.CARDDATA.KSDS\" WHERE 1 = 0");
            assertThat(repository.describeAlternateIndexStatement())
                    .isEqualTo("SELECT * FROM \"CARDDEMO.CARDDATA.AIX.PATH\" WHERE 1 = 0");

            CardRepository.BaseStatements sql = statementsOf(repository);
            CardRepository.AlternateStatements pathSql = alternateStatementsOf(repository);
            String base = "\"CARDDEMO.CARDDATA.KSDS\"";
            String path = "\"CARDDEMO.CARDDATA.AIX.PATH\"";
            String image = "\"" + DESCRIBED_COLUMN + "\"";
            assertThat(sql.selectByCardNumber())
                    .isEqualTo("SELECT * FROM " + base + " WHERE " + image
                            + " LIKE ? ESCAPE '\\' ORDER BY " + image + " ASC");
            assertThat(sql.selectForUpdateByCardNumber())
                    .isEqualTo(sql.selectByCardNumber() + " FOR UPDATE");
            assertThat(pathSql.selectByAccountId())
                    .isEqualTo("SELECT * FROM " + path + " WHERE " + image
                            + " LIKE ? ESCAPE '\\' ORDER BY " + image + " ASC");
            assertThat(sql.browseAnchor())
                    .isEqualTo("SELECT * FROM " + base + " WHERE (" + image + " >= ? OR " + image
                            + " IS NULL) ORDER BY " + image + " ASC");
            assertThat(sql.browseForward())
                    .isEqualTo("SELECT * FROM " + base + " WHERE (" + image + " > ? OR " + image
                            + " IS NULL) ORDER BY " + image + " ASC");
            assertThat(sql.browseBackward())
                    .isEqualTo("SELECT * FROM " + base + " WHERE (" + image + " < ? OR " + image
                            + " IS NULL) ORDER BY " + image + " DESC");
            assertThat(sql.probeUnreadableRows())
                    .isEqualTo("SELECT * FROM " + base + " WHERE " + image + " IS NULL");
            assertThat(pathSql.probeUnreadableRows())
                    .isEqualTo("SELECT * FROM " + path + " WHERE " + image + " IS NULL");
            assertThat(sql.rewrite())
                    .isEqualTo("UPDATE " + base + " SET " + image + " = ? WHERE " + image
                            + " LIKE ? ESCAPE '\\'");
        }

        @Test
        @DisplayName("no statement names a copybook field as though it were a SQL column")
        void noStatementNamesACopybookFieldAsAColumn() {
            CardRepository.BaseStatements sql = statementsOf(repository);
            CardRepository.AlternateStatements pathSql = alternateStatementsOf(repository);

            assertThat(List.of(sql.selectByCardNumber(), sql.selectForUpdateByCardNumber(),
                            pathSql.selectByAccountId(), sql.browseAnchor(), sql.browseForward(),
                            sql.browseBackward(), sql.rewrite(), sql.probeUnreadableRows(),
                            pathSql.probeUnreadableRows()))
                    .allSatisfy(statement -> assertThat(statement)
                            .doesNotContain("CARD-NUM")
                            .doesNotContain("CARD-ACCT-ID")
                            .doesNotContain("CARD-RECORD")
                            .contains(DESCRIBED_COLUMN));
        }

        @Test
        @DisplayName("the statements are resolved once and then reused")
        void statementsAreResolvedOnceAndReused() {
            assertThat(repository.resolvedBaseStatements())
                    .as("nothing is asked of the backend until an operation needs it")
                    .isNull();
            assertThat(repository.resolvedAlternateStatements())
                    .as("and the path is not described by an operation that does not read it")
                    .isNull();

            CardRepository.BaseStatements first = statementsOf(repository);
            repository.readByCardNumber(FIRST_FIXTURE_CARD_NUM);

            assertThat(repository.resolvedBaseStatements()).isSameAs(first);
            assertThat(repository.resolvedAlternateStatements())
                    .as("gate G45: two reads of the base cluster still describe the base cluster only")
                    .isNull();
            verify(jdbcTemplate, times(1)).query(eq(repository.describeBaseStatement()),
                    CardRepositoryTest.<String>anyExtractor());
        }

        @Test
        @DisplayName("a backend that describes no usable column is refused, not read anyway")
        void aRelationWithNoRecordImageColumnIsRefused() {
            when(jdbcTemplate.query(eq(repository.describeBaseStatement()),
                    CardRepositoryTest.<String>anyExtractor())).thenReturn(null);

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> repository.readByCardNumber(FIRST_FIXTURE_CARD_NUM))
                    .withMessageContaining("with no name");
        }

        @Test
        @DisplayName("gate G46: no statement carries a dataset name this class chose")
        void everyStatementNamesOnlyTheConfiguredDataset() {
            CardRepository rebound = new CardRepository(jdbcTemplate,
                    bindings(cardDatBinding("OTHER.PLACE.ENTIRELY", CardRecord.RECORD_LENGTH),
                            cardAixBinding()),
                    codec, RecordImageForm.CHARACTER);

            CardRepository.BaseStatements sql = statementsOf(rebound);
            assertThat(sql.selectByCardNumber()).contains("OTHER.PLACE.ENTIRELY");
            assertThat(sql.rewrite()).contains("OTHER.PLACE.ENTIRELY");
            assertThat(sql.selectByCardNumber()).doesNotContain("CARDDATA.KSDS");
        }

        @Test
        @DisplayName("gate G46: both dataset names are resolved by configuration key, in both profiles")
        void bothDatasetNamesAreResolvedByConfigurationKey() {
            assertThat(CardRepository.BASE_DD_NAME).isEqualTo("CARDDAT");
            assertThat(CardRepository.ALTERNATE_INDEX_DD_NAME).isEqualTo("CARDAIX");

            for (String document : List.of("/application.yml", "/application-test.yml")) {
                String yaml = classpathText(document);
                assertThat(declaresKey(yaml, "carddemo"))
                        .as("%s declares the carddemo configuration root", document).isTrue();
                assertThat(declaresKey(yaml, "datasets"))
                        .as("%s declares the datasets catalogue", document).isTrue();
                assertThat(declaresKey(yaml, CardRepository.BASE_DD_NAME))
                        .as("%s binds the %s key this repository looks its base cluster up under",
                                document, CardRepository.BASE_DD_NAME)
                        .isTrue();
                assertThat(declaresKey(yaml, CardRepository.ALTERNATE_INDEX_DD_NAME))
                        .as("%s binds the %s key this repository looks its alternate-index path up under",
                                document, CardRepository.ALTERNATE_INDEX_DD_NAME)
                        .isTrue();
            }
        }

        @Test
        @DisplayName("refuses a configured name that is not a well-formed z/OS dataset name")
        void refusesAMalformedDatasetName() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new CardRepository(jdbcTemplate,
                            bindings(cardDatBinding("ODD\"NAME", CardRecord.RECORD_LENGTH),
                                    cardAixBinding()),
                            codec, RecordImageForm.CHARACTER))
                    .withMessageContaining("well-formed z/OS dataset name");
        }

        @Test
        @DisplayName("the wiring constructor accepts a code page and builds the codec from it")
        void wiringConstructorTakesACodePage() {
            CardRepository wired =
                    new CardRepository(jdbcTemplate, bindings(), StandardCharsets.US_ASCII, RecordImageForm.CHARACTER);

            assertThat(wired.baseDatasetName()).isEqualTo("CARDDEMO.CARDDATA.KSDS");
        }

        @Test
        @DisplayName("the qualifier names the charset bean the configuration actually declares")
        void charsetQualifierMatchesTheConfiguredBeanName() {
            assertThat(CardRepository.DATASET_CHARSET_BEAN_NAME)
                    .isEqualTo(CobolCharsetConfig.DATASET_CHARSET_BEAN_NAME);
        }

        @Test
        @DisplayName("refuses to be built without a template, a catalogue, a codec or a code page")
        void refusesMissingCollaborators() {
            DatasetBindings catalogue = bindings();
            assertThatExceptionOfType(NullPointerException.class).isThrownBy(
                    () -> new CardRepository(null, catalogue, codec, RecordImageForm.CHARACTER));
            assertThatExceptionOfType(NullPointerException.class).isThrownBy(
                    () -> new CardRepository(jdbcTemplate, null, codec, RecordImageForm.CHARACTER));
            assertThatExceptionOfType(NullPointerException.class).isThrownBy(
                    () -> new CardRepository(jdbcTemplate, catalogue, (FixedWidthCodec) null, RecordImageForm.CHARACTER));
            assertThatExceptionOfType(NullPointerException.class).isThrownBy(
                    () -> new CardRepository(jdbcTemplate, catalogue, (Charset) null, RecordImageForm.CHARACTER));
        }

        @ParameterizedTest(name = "a missing \"{0}\" binding is reported against its own key")
        @ValueSource(strings = { CardRepository.BASE_DD_NAME,
                                 CardRepository.ALTERNATE_INDEX_DD_NAME })
        @DisplayName("reports an absent binding against the key it was looked up under")
        void reportsAnAbsentBinding(String missing) {
            DatasetBindings partial = CardRepository.BASE_DD_NAME.equals(missing)
                    ? bindings(null, cardAixBinding())
                    : bindings(cardDatBinding(), null);

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> new CardRepository(jdbcTemplate, partial, codec, RecordImageForm.CHARACTER))
                    .withMessageContaining(missing);
        }

        @ParameterizedTest(name = "a {0}-byte record width is rejected")
        @ValueSource(ints = { 91, 149, 151, 300 })
        @DisplayName("rejects a binding whose record width contradicts the copybook")
        void rejectsAContradictoryRecordWidth(int declaredWidth) {
            DatasetBindings wrongBase =
                    bindings(cardDatBinding("CARDDEMO.CARDDATA.KSDS", declaredWidth),
                            cardAixBinding());
            DatasetBindings wrongPath = bindings(cardDatBinding(),
                    cardAixBinding(CardRepository.BASE_DD_NAME, "CARD-ACCT-ID", declaredWidth));

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> new CardRepository(jdbcTemplate, wrongBase, codec, RecordImageForm.CHARACTER))
                    .withMessageContaining(CardRepository.BASE_DD_NAME)
                    .withMessageContaining("CVACT02Y");
            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> new CardRepository(jdbcTemplate, wrongPath, codec, RecordImageForm.CHARACTER))
                    .withMessageContaining(CardRepository.ALTERNATE_INDEX_DD_NAME);
        }

        @Test
        @DisplayName("gate G45: rejects a path that claims to index a different base")
        void rejectsAPathOverADifferentBase() {
            DatasetBindings foreignPath = bindings(cardDatBinding(),
                    cardAixBinding("CCXREF", "CARD-ACCT-ID", CardRecord.RECORD_LENGTH));

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> new CardRepository(jdbcTemplate, foreignPath, codec, RecordImageForm.CHARACTER))
                    .withMessageContaining("CCXREF")
                    .withMessageContaining(CardRepository.BASE_DD_NAME);
        }

        @Test
        @DisplayName("gate G45: rejects a path that claims a different alternate key")
        void rejectsAPathOnADifferentAlternateKey() {
            DatasetBindings wrongKey = bindings(cardDatBinding(),
                    cardAixBinding(CardRepository.BASE_DD_NAME, "XREF-ACCT-ID",
                            CardRecord.RECORD_LENGTH));

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> new CardRepository(jdbcTemplate, wrongKey, codec, RecordImageForm.CHARACTER))
                    .withMessageContaining("XREF-ACCT-ID")
                    .withMessageContaining("CARD-ACCT-ID");
        }

        @Test
        @DisplayName("accepts a path that states neither a base nor an alternate key")
        void acceptsASilentPathBinding() {
            DatasetBindings silent = bindings(cardDatBinding(),
                    cardAixBinding(null, null, CardRecord.RECORD_LENGTH));

            assertThatNoException()
                    .isThrownBy(() -> new CardRepository(jdbcTemplate, silent, codec, RecordImageForm.CHARACTER));
        }

        @ParameterizedTest(name = "an absent dataset name [{0}] is rejected")
        @CsvSource(value = { "NULL", "''", "'   '" }, nullValues = "NULL")
        @DisplayName("rejects a dataset name the configuration never supplied")
        void rejectsAnAbsentDatasetName(String dsname) {
            DatasetBindings unusable =
                    bindings(cardDatBinding(dsname, CardRecord.RECORD_LENGTH), cardAixBinding());

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> new CardRepository(jdbcTemplate, unusable, codec, RecordImageForm.CHARACTER))
                    .withMessageContaining("carddemo.datasets." + CardRepository.BASE_DD_NAME);
        }

        @ParameterizedTest(name = "a malformed dataset name [{0}] is rejected")
        @ValueSource(strings = { "BAD\tNAME", "BAD\nNAME", "TOOLONGQUALIFIER.X", "A..B", "1LEADING.X",
                "A.B;DROP", "A.B(2)" })
        @DisplayName("rejects a name that is not a well-formed z/OS dataset name")
        void rejectsAMalformedDatasetName(String dsname) {
            DatasetBindings unusable =
                    bindings(cardDatBinding(dsname, CardRecord.RECORD_LENGTH), cardAixBinding());

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new CardRepository(jdbcTemplate, unusable, codec, RecordImageForm.CHARACTER))
                    .withMessageContaining("well-formed z/OS dataset name");
        }

        @Test
        @DisplayName("holds the CICS file-name literals at their declared eight-character width")
        void holdsTheCicsFileNameLiteralsAtDeclaredWidth() {
            assertThat(CardRepository.BASE_CICS_FILE_NAME)
                    .isEqualTo("CARDDAT ")
                    .hasSize(CardRepository.CICS_FILE_NAME_LENGTH);
            assertThat(CardRepository.ALTERNATE_INDEX_CICS_FILE_NAME)
                    .isEqualTo("CARDAIX ")
                    .hasSize(CardRepository.CICS_FILE_NAME_LENGTH);
            assertThat(CardRepository.RECORD_LENGTH).isEqualTo(CardRecord.RECORD_LENGTH).isEqualTo(150);
            assertThat(CardRepository.RECORD_IMAGE_COLUMN_INDEX).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("readByCardNumber - 9100-GETCARD-BYACCTCARD")
    class ReadByCardNumberTest {
        @Test
        @DisplayName("WHEN DFHRESP(NORMAL): returns the record and reports status '00'")
        void normalArm() {
            CardRecord stored = cardRecord(FIRST_FIXTURE_CARD_NUM);
            stubFetch(oneRow(stored));

            CardReadResult result = repository.readByCardNumber(FIRST_FIXTURE_CARD_NUM);

            assertThat(result.isNormal()).isTrue();
            assertThat(result.isRecordReturned()).isTrue();
            assertThat(result.resp()).isEqualTo(FileStatus.NORMAL);
            assertThat(result.resp2()).isEqualTo(CardRepository.NO_REASON_CODE);
            assertThat(result.outcome()).isEqualTo(Outcome.OK);
            assertThat(result.batchStatus()).contains(FileStatus.OK);
            assertThat(result.requireRecord()).isEqualTo(stored);
        }

        @Test
        @DisplayName("WHEN DFHRESP(NOTFND): reports status '23' with no record, and does not throw")
        void notFoundArm() {
            stubFetch(FetchedRows.empty());

            CardReadResult result = repository.readByCardNumber(FIRST_FIXTURE_CARD_NUM);

            assertThat(result.isNotFound()).isTrue();
            assertThat(result.isFailure()).isFalse();
            assertThat(result.resp()).isEqualTo(FileStatus.NOTFND);
            assertThat(result.outcome()).isEqualTo(Outcome.NOT_FOUND);
            assertThat(result.batchStatus()).contains(FileStatus.NOT_FOUND);
            assertThat(result.record()).isEmpty();
        }

        @Test
        @DisplayName("WHEN OTHER: a row carrying no record image is not reported as a missing record")
        void rowWithoutARecordImage() {
            stubFetch(new FetchedRows(null, 1));

            CardReadResult result = repository.readByCardNumber(FIRST_FIXTURE_CARD_NUM);

            assertThat(result.isFailure()).isTrue();
            assertThat(result.isNotFound()).isFalse();
            assertThat(result.resp()).isEqualTo(FileStatus.INVREQ);
            assertThat(result.batchStatus()).isEmpty();
        }

        @Test
        @DisplayName("a NOTFND is PROVED: an unreadable row in the relation makes it WHEN OTHER instead")
        void aNotFoundIsProvedAgainstUnreadableRows() throws SQLException {
            when(jdbcTemplate.query(any(PreparedStatementCreator.class),
                    CardRepositoryTest.<FetchedRows>anyExtractor()))
                    .thenReturn(FetchedRows.empty(), new FetchedRows(null, 1));

            CardReadResult result = repository.readByCardNumber(FIRST_FIXTURE_CARD_NUM);

            assertThat(result.isNotFound()).isFalse();
            assertThat(result.resp()).isEqualTo(FileStatus.INVREQ);
            assertThat(result.record()).isEmpty();
            assertThat(result.batchStatus()).isEmpty();
            assertThat(preparedStatements(2).get(1))
                    .as("the second read is the unreadable-row probe over the base cluster")
                    .isEqualTo(repository.resolvedBaseStatements().probeUnreadableRows());
        }

        @Test
        @DisplayName("a NOTFND stands when every row of the relation is readable")
        void aNotFoundStandsWhenEveryRowIsReadable() throws SQLException {
            when(jdbcTemplate.query(any(PreparedStatementCreator.class),
                    CardRepositoryTest.<FetchedRows>anyExtractor()))
                    .thenReturn(FetchedRows.empty(), FetchedRows.empty());

            CardReadResult result = repository.readByCardNumber(FIRST_FIXTURE_CARD_NUM);

            assertThat(result.isNotFound()).isTrue();
            assertThat(result.resp()).isEqualTo(FileStatus.NOTFND);
            assertThat(preparedStatements(2)).hasSize(2);
        }

        @Test
        @DisplayName("a read that found its record is never second-guessed by the probe")
        void aFoundRecordIsNotSecondGuessed() {
            stubFetch(oneRow(cardRecord(FIRST_FIXTURE_CARD_NUM)));

            assertThat(repository.readByCardNumber(FIRST_FIXTURE_CARD_NUM).isNormal()).isTrue();

            verify(jdbcTemplate, times(1)).query(any(PreparedStatementCreator.class),
                    CardRepositoryTest.<FetchedRows>anyExtractor());
        }

        @ParameterizedTest(name = "a {0}-byte row is a length error, not a record")
        @ValueSource(ints = { 0, 91, 149, 151 })
        @DisplayName("WHEN OTHER: a row that is not 150 bytes wide is a length error")
        void wrongWidthRow(int width) {
            stubFetch(new FetchedRows(new byte[width], 1));

            CardReadResult result = repository.readByCardNumber(FIRST_FIXTURE_CARD_NUM);

            assertThat(result.resp()).isEqualTo(FileStatus.LENGERR);
            assertThat(result.resp2()).as("a width is not a reason code")
                    .isEqualTo(CardRepository.NO_REASON_CODE);
            assertThat(result.observation()).isPresent();
            assertThat(result.observation().orElseThrow().value()).isEqualTo(width);
            assertThat(result.observation().orElseThrow().describe())
                    .isEqualTo("stored record width in bytes = " + width);
            assertThat(result.outcome()).isEqualTo(Outcome.OTHER);
            assertThat(result.batchStatus()).isEmpty();
        }

        @Test
        @DisplayName("WHEN OTHER: an unreachable dataset is reported as not open")
        void unreachableDataset() {
            stubRejection(new CannotGetJdbcConnectionException("no connection",
                    new SQLException("no route to host", "08001", VENDOR_ERROR_CODE)));

            CardReadResult result = repository.readByCardNumber(FIRST_FIXTURE_CARD_NUM);

            assertThat(result.resp()).isEqualTo(FileStatus.NOTOPEN);
            assertThat(result.resp2()).isEqualTo(CardRepository.NO_REASON_CODE);
            assertThat(result.isFailure()).isTrue();
        }

        @Test
        @DisplayName("WHEN OTHER: any other rejection is reported as an invalid request")
        void otherRejection() {
            stubRejection(new InvalidResultSetAccessException(new SQLException("bad", "42000",
                    VENDOR_ERROR_CODE)));

            CardReadResult result = repository.readByCardNumber(FIRST_FIXTURE_CARD_NUM);

            assertThat(result.resp()).isEqualTo(FileStatus.NOTOPEN);
            assertThat(result.resp2())
                    .as("a driver's vendor error number is not a CICS reason code and is never "
                            + "reported as one - COCRDUPC renders ERROR-RESP2 verbatim")
                    .isEqualTo(CardRepository.NO_REASON_CODE);
        }

        @Test
        @DisplayName("a refusal with no SQLSTATE at all is an invalid request, not a guess")
        void aRefusalWithNoSqlStateIsAnInvalidRequest() {
            stubRejection(new DataAccessResourceFailureException("no cause at all"));

            CardReadResult result = repository.readByCardNumber(FIRST_FIXTURE_CARD_NUM);

            assertThat(result.isFailure()).isTrue();
            assertThat(result.resp()).isEqualTo(FileStatus.INVREQ);
            assertThat(result.resp2()).isEqualTo(CardRepository.NO_REASON_CODE);
        }

        @Test
        @DisplayName("the SQLSTATE is found through a wrapper chain, not only on the outermost failure")
        void theSqlStateIsFoundThroughTheCauseChain() {
            stubRejection(new DataAccessResourceFailureException("wrapped",
                    new IllegalStateException(new SQLException("deep", "08001", VENDOR_ERROR_CODE))));

            assertThat(repository.readByCardNumber(FIRST_FIXTURE_CARD_NUM).resp())
                    .isEqualTo(FileStatus.NOTOPEN);
        }

        @Test
        @DisplayName("a template that answers with nothing is an empty answer, not a crash")
        void templateAnsweringWithNothing() {
            when(jdbcTemplate.query(any(PreparedStatementCreator.class),
                    CardRepositoryTest.<FetchedRows>anyExtractor())).thenReturn(null);

            assertThat(repository.readByCardNumber(FIRST_FIXTURE_CARD_NUM).isNotFound()).isTrue();
        }

        @ParameterizedTest(name = "[{0}] becomes the sixteen-character key [{1}]")
        @MethodSource("com.vsergeychik.carddemo.card.CardRepositoryTest#alphanumericMoveCases")
        @DisplayName("moves the card number into PIC X(16): pads and truncates on the RIGHT")
        void movesTheKeyAsAnAlphanumericMove(String supplied, String expectedKey)
                throws SQLException {
            stubFetch(FetchedRows.empty());

            repository.readByCardNumber(supplied);

            PreparedStatement prepared = capturePreparedStatement();
            assertThat(expectedKey).hasSize(CardRecord.CARD_NUM_LENGTH);
            verify(prepared).setString(1, expectedKey + "%");
        }

        @Test
        @DisplayName("the key is sixteen bytes wide: LENGTH OF WS-CARD-RID-CARDNUM, COCRDSLC:742")
        void theKeyLengthIsSixteen() throws SQLException {
            stubFetch(FetchedRows.empty());

            repository.readByCardNumber(FIRST_FIXTURE_CARD_NUM);

            assertThat(CardRecord.CARD_NUM_LENGTH).isEqualTo(16);
            assertThat(CardRecord.cardDatPrimaryKeySpan().length())
                    .isEqualTo(CardRecord.CARD_NUM_LENGTH);
            assertThat(CardRecord.cardDatPrimaryKeySpan().offset())
                    .as("CARD-NUM begins the record, so nothing precedes the key")
                    .isZero();

            ArgumentCaptor<String> bound = ArgumentCaptor.forClass(String.class);
            verify(capturePreparedStatement()).setString(eq(1), bound.capture());
            String pattern = bound.getValue();
            assertThat(pattern.substring(0, CardRecord.CARD_NUM_LENGTH))
                    .as("the first sixteen bytes of the predicate are the key, in full")
                    .isEqualTo(FIRST_FIXTURE_CARD_NUM);
            assertThat(pattern.substring(CardRecord.CARD_NUM_LENGTH))
                    .as("and everything after the key span is unconstrained, so no other field takes "
                            + "part in the match")
                    .isEqualTo("%");
        }

        @Test
        @DisplayName("WHEN OTHER: both RESP and RESP2 reach the caller, as COCRDSLC:768-771 renders them")
        void bothResponseCodesReachTheCaller() {
            stubFetch(new FetchedRows(new byte[CardRecord.RECORD_LENGTH - 1], 1));

            CardReadResult result = repository.readByCardNumber(FIRST_FIXTURE_CARD_NUM);

            assertThat(result.resp()).as("RESP: WS-RESP-CD").isEqualTo(FileStatus.LENGERR);
            assertThat(result.resp2()).as("RESP2: WS-REAS-CD").isEqualTo(CardRepository.NO_REASON_CODE);
            assertThat(result.outcome()).isEqualTo(Outcome.OTHER);
            assertThat(result.observation())
                    .as("the measured width travels labelled, beside the pair - never inside it")
                    .isPresent();
            assertThat(result.observation().orElseThrow().value())
                    .isEqualTo(CardRecord.RECORD_LENGTH - 1);
        }

        @Test
        @DisplayName("bounds the fetch at two rows: one more than a unique key can produce")
        void boundsTheFetch() throws SQLException {
            stubFetch(FetchedRows.empty());

            repository.readByCardNumber(FIRST_FIXTURE_CARD_NUM);

            PreparedStatement prepared = capturePreparedStatement();
            verify(prepared).setMaxRows(2);
            verify(prepared).setFetchSize(2);
            verify(prepared, never()).setMaxRows(1);
        }

        @Test
        @DisplayName("a key matching more than one row is refused, not resolved by taking the first")
        void aFanOutOnThePrimaryKeyIsRefused() {
            CardRecord first = cardRecord(FIRST_FIXTURE_CARD_NUM);
            stubFetch(new FetchedRows(first.encode(codec), 2));

            CardReadResult result = repository.readByCardNumber(FIRST_FIXTURE_CARD_NUM);

            assertThat(result.outcome()).isEqualTo(Outcome.OTHER);
            assertThat(result.resp()).isEqualTo(FileStatus.INVREQ);
            assertThat(result.record())
                    .as("no record is handed over, so nothing arbitrary can be displayed or rewritten")
                    .isEmpty();
            assertThat(result.storedImage()).isEmpty();
            assertThat(result.observation()).isPresent();
            assertThat(result.observation().orElseThrow().value())
                    .as("the row count travels labelled, so the diagnostic says how many matched")
                    .isEqualTo(2);
            assertThat(result.resp())
                    .as("DUPKEY belongs to a read through a PATH on a non-unique ALTERNATE key, which is "
                            + "an expected condition; a non-unique PRIMARY key is not one VSAM can present")
                    .isNotEqualTo(FileStatus.DUPKEY)
                    .isNotEqualTo(FileStatus.DUPREC);
        }

        @Test
        @DisplayName("the locking read refuses a fan-out too, before any record is held for update")
        void theLockingReadRefusesAFanOutAsWell() {
            stubFetch(new FetchedRows(cardRecord(FIRST_FIXTURE_CARD_NUM).encode(codec), 2));

            CardReadResult result = inUnitOfWork(
                    () -> repository.readForUpdateByCardNumber(FIRST_FIXTURE_CARD_NUM));

            assertThat(result.outcome()).isEqualTo(Outcome.OTHER);
            assertThat(result.resp()).isEqualTo(FileStatus.INVREQ);
            assertThat(result.record()).isEmpty();
        }

        @Test
        @DisplayName("exactly one match still succeeds, so the extra probe row is not over-eager")
        void aUniqueMatchStillSucceeds() {
            CardRecord stored = cardRecord(FIRST_FIXTURE_CARD_NUM);
            stubFetch(new FetchedRows(stored.encode(codec), 1));

            CardReadResult result = repository.readByCardNumber(FIRST_FIXTURE_CARD_NUM);

            assertThat(result.outcome()).isEqualTo(Outcome.OK);
            assertThat(result.resp()).isEqualTo(FileStatus.NORMAL);
            assertThat(result.record()).contains(stored);
        }

        @Test
        @DisplayName("refuses a null card number: a COBOL alphanumeric field is never absent")
        void refusesANullCardNumber() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> repository.readByCardNumber(null));
        }
    }

    @Nested
    @DisplayName("readForUpdateByCardNumber - the locking read")
    class ReadForUpdateTest {
        @Test
        @DisplayName("sends the locking statement, not the plain one")
        void sendsTheLockingStatement() throws SQLException {
            stubFetch(oneRow(cardRecord(FIRST_FIXTURE_CARD_NUM)));

            inUnitOfWork(() -> repository.readForUpdateByCardNumber(FIRST_FIXTURE_CARD_NUM));

            ArgumentCaptor<PreparedStatementCreator> captor =
                    ArgumentCaptor.forClass(PreparedStatementCreator.class);
            verify(jdbcTemplate).query(captor.capture(),
                    CardRepositoryTest.<FetchedRows>anyExtractor());
            Connection connection = mock(Connection.class);
            when(connection.prepareStatement(anyString())).thenReturn(mock(PreparedStatement.class));
            captor.getValue().createPreparedStatement(connection);
            verify(connection).prepareStatement(
                    repository.resolvedBaseStatements().selectForUpdateByCardNumber());
        }

        @Test
        @DisplayName("a locking read with no unit of work open is refused, not issued anyway")
        void aLockingReadOutsideAUnitOfWorkIsRefused() {
            stubFetch(oneRow(cardRecord(FIRST_FIXTURE_CARD_NUM)));

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> repository.readForUpdateByCardNumber(FIRST_FIXTURE_CARD_NUM))
                    .withMessageContaining("no transaction is open on this thread");
            verify(jdbcTemplate, never()).query(any(PreparedStatementCreator.class),
                    CardRepositoryTest.<FetchedRows>anyExtractor());
        }

        @Test
        @DisplayName("a normal response is the lock taken, and carries the locked record")
        void lockTaken() {
            CardRecord locked = cardRecord(FIRST_FIXTURE_CARD_NUM);
            stubFetch(oneRow(locked));

            CardReadResult result =
                    inUnitOfWork(() -> repository.readForUpdateByCardNumber(FIRST_FIXTURE_CARD_NUM));

            assertThat(result.isNormal()).isTrue();
            assertThat(result.requireRecord()).isEqualTo(locked);
        }

        @Test
        @DisplayName("COULD-NOT-LOCK-FOR-UPDATE: a record deleted meanwhile is not normal")
        void lockNotTakenBecauseTheRecordIsGone() {
            stubFetch(FetchedRows.empty());

            CardReadResult result =
                    inUnitOfWork(() -> repository.readForUpdateByCardNumber(FIRST_FIXTURE_CARD_NUM));

            assertThat(result.isNormal()).as("the only test COCRDUPC:1441 makes").isFalse();
            assertThat(result.isNotFound()).isTrue();
        }

        @Test
        @DisplayName("COULD-NOT-LOCK-FOR-UPDATE: a rejected lock is not normal either")
        void lockNotTakenBecauseTheRequestFailed() {
            stubRejection(new InvalidResultSetAccessException(new SQLException("locked elsewhere")));

            CardReadResult result =
                    inUnitOfWork(() -> repository.readForUpdateByCardNumber(FIRST_FIXTURE_CARD_NUM));

            assertThat(result.isNormal()).isFalse();
            assertThat(result.resp()).isEqualTo(FileStatus.INVREQ);
        }

        @Test
        @DisplayName("one result serves both sides of the IF WS-RETURN-MSG-OFF guard, COCRDUPC:1441-1449")
        void oneResultServesBothSidesOfTheReturnMessageGuard() {
            stubFetch(FetchedRows.empty());

            CardReadResult result =
                    inUnitOfWork(() -> repository.readForUpdateByCardNumber(FIRST_FIXTURE_CARD_NUM));

            boolean returnMessageOff = true;
            boolean couldNotLockForUpdate = !result.isNormal() && returnMessageOff;
            assertThat(couldNotLockForUpdate)
                    .as("with no message pending, the ELSE arm raises COULD-NOT-LOCK-FOR-UPDATE")
                    .isTrue();

            returnMessageOff = false;
            boolean inputError = !result.isNormal();
            couldNotLockForUpdate = !result.isNormal() && returnMessageOff;
            assertThat(inputError).as("INPUT-ERROR is set on the ELSE arm regardless").isTrue();
            assertThat(couldNotLockForUpdate)
                    .as("but the pending message is not overwritten")
                    .isFalse();

            assertThat(result.isNormal()).isFalse();
            assertThat(result.isNormal()).as("reading it again cannot change the guard's outcome")
                    .isFalse();
            assertThat(result.record()).as("no record was locked, so none is handed out").isEmpty();
            assertThat(result.resp()).isEqualTo(FileStatus.NOTFND);
            assertThat(result.resp2()).isEqualTo(CardRepository.NO_REASON_CODE);
            assertThatExceptionOfType(IllegalStateException.class)
                    .as("and it refuses to invent a record for the arm that returns none")
                    .isThrownBy(result::requireRecord);
        }
    }

    @Nested
    @DisplayName("readByAccountIdViaAltIndex - 9150-GETCARD-BYACCT, via the CARDAIX path")
    class ReadByAccountIdTest {
        @Test
        @DisplayName("gate G45: reads the path, never the base cluster")
        void readsThePathNotTheBase() throws SQLException {
            stubFetch(FetchedRows.empty());

            repository.readByAccountIdViaAltIndex(FIRST_FIXTURE_ACCT_ID);

            ArgumentCaptor<PreparedStatementCreator> captor =
                    ArgumentCaptor.forClass(PreparedStatementCreator.class);
            verify(jdbcTemplate, atLeastOnce()).query(captor.capture(),
                    CardRepositoryTest.<FetchedRows>anyExtractor());
            Connection connection = mock(Connection.class);
            when(connection.prepareStatement(anyString())).thenReturn(mock(PreparedStatement.class));
            captor.getAllValues().get(0).createPreparedStatement(connection);
            String pathStatement = repository.resolvedAlternateStatements().selectByAccountId();
            verify(connection).prepareStatement(pathStatement);
            assertThat(pathStatement)
                    .contains(repository.alternateIndexDatasetName())
                    .doesNotContain(repository.baseDatasetName());
        }

        @Test
        @DisplayName("the alternate key is eleven bytes at offset 16: COCRDSLC:783-784")
        void theAlternateKeyLengthIsEleven() throws SQLException {
            stubFetch(FetchedRows.empty());

            repository.readByAccountIdViaAltIndex(FIRST_FIXTURE_ACCT_ID);

            assertThat(CardRecord.CARD_ACCT_ID_LENGTH).isEqualTo(11);
            assertThat(CardRecord.cardAixAlternateKeySpan().length())
                    .isEqualTo(CardRecord.CARD_ACCT_ID_LENGTH);
            assertThat(CardRecord.cardAixAlternateKeySpan().offset())
                    .isEqualTo(CardRecord.CARD_ACCT_ID_OFFSET).isEqualTo(16);

            ArgumentCaptor<String> bound = ArgumentCaptor.forClass(String.class);
            verify(capturePreparedStatement()).setString(eq(1), bound.capture());
            String pattern = bound.getValue();
            assertThat(pattern.substring(0, CardRecord.CARD_ACCT_ID_OFFSET))
                    .as("the sixteen leading single-character wildcards ARE the offset, in SQL")
                    .isEqualTo("_".repeat(CardRecord.CARD_ACCT_ID_OFFSET));
            assertThat(pattern.substring(CardRecord.CARD_ACCT_ID_OFFSET,
                    CardRecord.CARD_ACCT_ID_OFFSET + CardRecord.CARD_ACCT_ID_LENGTH))
                    .as("eleven digits, zero-filled on the left as PIC 9(11) requires")
                    .isEqualTo("00000000050").hasSize(11);
        }

        @Test
        @DisplayName("a NOTFND through the path is proved against the PATH's own unreadable rows")
        void aNotFoundThroughThePathIsProvedAgainstThePath() throws SQLException {
            when(jdbcTemplate.query(any(PreparedStatementCreator.class),
                    CardRepositoryTest.<FetchedRows>anyExtractor()))
                    .thenReturn(FetchedRows.empty(), new FetchedRows(null, 1));

            CardReadResult result = repository.readByAccountIdViaAltIndex(FIRST_FIXTURE_ACCT_ID);

            assertThat(result.isNotFound()).isFalse();
            assertThat(result.resp()).isEqualTo(FileStatus.INVREQ);
            assertThat(preparedStatements(2).get(1))
                    .as("the probe addresses the path that was read, not the base cluster")
                    .isEqualTo(repository.resolvedAlternateStatements().probeUnreadableRows());
        }

        @Test
        @DisplayName("gate G45: the same record is reachable through both paths, byte for byte")
        void theSameRecordIsReachableThroughBothPaths() {
            CardRecord stored = cardRecord(FIRST_FIXTURE_CARD_NUM);
            stubFetch(oneRow(stored));

            CardRecord throughTheBase =
                    repository.readByCardNumber(FIRST_FIXTURE_CARD_NUM).requireRecord();
            CardRecord throughThePath =
                    repository.readByAccountIdViaAltIndex(throughTheBase.cardAcctId())
                            .requireRecord();

            assertThat(throughThePath.encode(codec))
                    .as("gate G45: one dataset, two access paths - so one record")
                    .hasSize(CardRecord.RECORD_LENGTH)
                    .isEqualTo(throughTheBase.encode(codec));

            assertThat(throughThePath.cardNum()).isEqualTo(throughTheBase.cardNum());
            assertThat(throughThePath.cardAcctId()).isEqualTo(throughTheBase.cardAcctId());
            assertThat(throughThePath.cardCvvCd()).isEqualTo(throughTheBase.cardCvvCd());
            assertThat(throughThePath.cardEmbossedName())
                    .isEqualTo(throughTheBase.cardEmbossedName());
            assertThat(throughThePath.cardExpiraionDate())
                    .isEqualTo(throughTheBase.cardExpiraionDate());
            assertThat(throughThePath.cardActiveStatus())
                    .isEqualTo(throughTheBase.cardActiveStatus());

            verify(jdbcTemplate, times(2)).query(any(PreparedStatementCreator.class),
                    CardRepositoryTest.<FetchedRows>anyExtractor());
            assertThat(repository.alternateIndexDatasetName())
                    .as("two distinct configured names, both reached through this one repository")
                    .isNotEqualTo(repository.baseDatasetName());
            assertThat(CardRepository.ALTERNATE_INDEX_DD_NAME).isEqualTo("CARDAIX");
            assertThat(CardRepository.BASE_DD_NAME).isEqualTo("CARDDAT");
        }

        @Test
        @DisplayName("WHEN DFHRESP(NORMAL): a unique alternate key returns one record")
        void uniqueAlternateKey() {
            CardRecord stored = cardRecord(FIRST_FIXTURE_CARD_NUM);
            stubFetch(oneRow(stored));

            CardReadResult result = repository.readByAccountIdViaAltIndex(FIRST_FIXTURE_ACCT_ID);

            assertThat(result.isNormal()).isTrue();
            assertThat(result.isDuplicateKey()).isFalse();
            assertThat(result.requireRecord()).isEqualTo(stored);
        }

        @Test
        @DisplayName("DUPKEY: a shared alternate key returns the first record AND says more exist")
        void nonUniqueAlternateKey() {
            CardRecord first = cardRecord(FIRST_FIXTURE_CARD_NUM);
            stubFetch(new FetchedRows(first.encode(codec), 2));

            CardReadResult result = repository.readByAccountIdViaAltIndex(FIRST_FIXTURE_ACCT_ID);

            assertThat(result.isDuplicateKey()).isTrue();
            assertThat(result.isFailure()).as("the record is returned, so this is not an error")
                    .isFalse();
            assertThat(result.isRecordReturned()).isTrue();
            assertThat(result.requireRecord()).isEqualTo(first);
            assertThat(result.resp()).isEqualTo(FileStatus.DUPKEY);
            assertThat(result.outcome()).isEqualTo(Outcome.DUPLICATE);
            assertThat(result.batchStatus())
                    .as("gate G47: the '22' status is reachable from this call site")
                    .contains(FileStatus.DUPLICATE);
        }

        @Test
        @DisplayName("WHEN DFHRESP(NOTFND): no card cross-references that account")
        void noMatchingAccount() {
            stubFetch(FetchedRows.empty());

            assertThat(repository.readByAccountIdViaAltIndex(FIRST_FIXTURE_ACCT_ID).isNotFound())
                    .isTrue();
        }

        @Test
        @DisplayName("WHEN OTHER: a rejection through the path is reported like any other")
        void rejectionThroughThePath() {
            stubRejection(new DataAccessResourceFailureException("path unavailable",
                    new SQLException("path unavailable", "08006", VENDOR_ERROR_CODE)));

            assertThat(repository.readByAccountIdViaAltIndex(FIRST_FIXTURE_ACCT_ID).resp())
                    .isEqualTo(FileStatus.NOTOPEN);
        }

        @Test
        @DisplayName("fetches one row more than it needs, so a shared key is distinguishable")
        void fetchesOneRowMoreThanItNeeds() throws SQLException {
            stubFetch(FetchedRows.empty());

            repository.readByAccountIdViaAltIndex(FIRST_FIXTURE_ACCT_ID);

            PreparedStatement prepared = capturePreparedStatement();
            verify(prepared).setMaxRows(2);
        }

        @ParameterizedTest(name = "[{0}] becomes the eleven-digit key [{1}]")
        @CsvSource({
            "10000000010,10000000010",
            "00000000050,00000000050",
            "42,00000000042",
            "0,00000000000",
            "999999999999,99999999999"
        })
        @DisplayName("moves the account id into PIC 9(11): zero-fills and truncates on the LEFT")
        void movesTheKeyAsANumericMove(String supplied, String expectedKey) throws SQLException {
            stubFetch(FetchedRows.empty());

            repository.readByAccountIdViaAltIndex(supplied);

            PreparedStatement prepared = capturePreparedStatement();
            assertThat(expectedKey).hasSize(CardRecord.CARD_ACCT_ID_LENGTH);
            verify(prepared).setString(1, "_".repeat(CardRecord.CARD_ACCT_ID_OFFSET) + expectedKey + "%");
        }

        @Test
        @DisplayName("the numeric view and its alphanumeric redefinition produce the same bytes")
        void bothViewsProduceTheSameKey() throws SQLException {
            stubFetch(FetchedRows.empty());
            repository.readByAccountIdViaAltIndex(WIDE_ACCT_ID);
            String expected = "_".repeat(CardRecord.CARD_ACCT_ID_OFFSET) + "10000000010%";
            PreparedStatement fromLong = capturePreparedStatement();
            verify(fromLong).setString(1, expected);

            jdbcTemplate = mock(JdbcTemplate.class);
            repository = new CardRepository(jdbcTemplate, bindings(), codec, RecordImageForm.CHARACTER);
            stubDescribe(repository);
            stubFetch(FetchedRows.empty());
            repository.readByAccountIdViaAltIndex("10000000010");
            PreparedStatement fromDigits = capturePreparedStatement();
            verify(fromDigits).setString(1, expected);
        }

        @Test
        @DisplayName("refuses a negative account id: PIC 9(11) has no sign position")
        void refusesANegativeAccountId() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> repository.readByAccountIdViaAltIndex(-1L));
            verify(jdbcTemplate, never()).query(any(PreparedStatementCreator.class),
                    CardRepositoryTest.<FetchedRows>anyExtractor());
        }

        @Test
        @DisplayName("refuses a non-numeric account id, and refuses a null one")
        void refusesANonNumericAccountId() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> repository.readByAccountIdViaAltIndex("ABCDEFGHIJK"));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> repository.readByAccountIdViaAltIndex((String) null));
        }
    }

    @Nested
    @DisplayName("rewrite - COCRDUPC:1477-1483")
    class RewriteTest {
        private void stubTheRowIsThere() {
            stubFetch(oneRow(cardRecord(FIRST_FIXTURE_CARD_NUM)));
        }

        private PreparedStatement bindRewrite() throws SQLException {
            ArgumentCaptor<PreparedStatementSetter> captor =
                    ArgumentCaptor.forClass(PreparedStatementSetter.class);
            verify(jdbcTemplate).update(eq(repository.resolvedBaseStatements().rewrite()),
                    captor.capture());
            PreparedStatement prepared = mock(PreparedStatement.class);
            captor.getValue().setValues(prepared);
            return prepared;
        }

        @Test
        @DisplayName("gates G19 and G21: sends all 150 bytes, with FILLER as 59 spaces")
        void sendsTheWholeRecordIncludingFiller() throws SQLException {
            CardRecord updated = cardRecord(FIRST_FIXTURE_CARD_NUM);
            stubTheRowIsThere();
            when(jdbcTemplate.update(anyString(), any(PreparedStatementSetter.class))).thenReturn(1);

            assertThat(inUnitOfWork(() -> repository.rewrite(updated)).isNormal()).isTrue();

            ArgumentCaptor<String> image = ArgumentCaptor.forClass(String.class);
            PreparedStatement prepared = bindRewrite();
            verify(prepared).setString(eq(1), image.capture());
            verify(prepared).setString(2, FIRST_FIXTURE_CARD_NUM + "%");

            String sent = image.getValue();
            assertThat(sent).hasSize(CardRecord.RECORD_LENGTH);
            String filler = sent.substring(CardRecord.FILLER_OFFSET,
                    CardRecord.FILLER_OFFSET + CardRecord.FILLER_LENGTH);
            assertThat(filler).isEqualTo(" ".repeat(CardRecord.FILLER_LENGTH)).hasSize(59);
        }

        @Test
        @DisplayName("round trip: a mutated record rewritten and re-read matches field for field")
        void rewriteRoundTripsFieldByField() throws SQLException {
            String storedRow = fixtureRows().get(0);
            stubFetch(new FetchedRows(storedRow.getBytes(FIXTURE_CHARSET), 1));
            when(jdbcTemplate.update(anyString(), any(PreparedStatementSetter.class))).thenReturn(1);

            CardRecord before = repository.readByCardNumber(FIRST_FIXTURE_CARD_NUM).requireRecord();
            CardRecord mutated = before
                    .withCardEmbossedName("Aniya Von-Reyes")
                    .withCardExpiraionDate("2031-12-31")
                    .withCardActiveStatus("N")
                    .withCardCvvCd(915);

            assertThat(inUnitOfWork(() -> repository.rewrite(mutated)).isNormal()).isTrue();

            ArgumentCaptor<String> image = ArgumentCaptor.forClass(String.class);
            verify(bindRewrite()).setString(eq(1), image.capture());
            String written = image.getValue();
            clearInvocations(jdbcTemplate);
            stubDescribe(repository);
            stubFetch(new FetchedRows(written.getBytes(FIXTURE_CHARSET), 1));

            CardRecord after = repository.readByCardNumber(FIRST_FIXTURE_CARD_NUM).requireRecord();

            assertThat(after.cardNum()).as("CARD-NUM X(16): a rewrite cannot change the key")
                    .isEqualTo(before.cardNum()).isEqualTo(FIRST_FIXTURE_CARD_NUM);
            assertThat(after.cardAcctId()).as("CARD-ACCT-ID 9(11): untouched")
                    .isEqualTo(before.cardAcctId()).isEqualTo(FIRST_FIXTURE_ACCT_ID);
            assertThat(after.cardCvvCd()).as("CARD-CVV-CD 9(03): changed").isEqualTo(915)
                    .isNotEqualTo(before.cardCvvCd());
            assertThat(after.cardEmbossedName())
                    .as("CARD-EMBOSSED-NAME X(50): changed, and still space-padded to its full width")
                    .isEqualTo("Aniya Von-Reyes" + " ".repeat(35))
                    .hasSize(CardRecord.CARD_EMBOSSED_NAME_LENGTH);
            assertThat(after.cardExpiraionDate())
                    .as("CARD-EXPIRAION-DATE X(10): changed - and still spelled as the copybook spells it")
                    .isEqualTo("2031-12-31").hasSize(CardRecord.CARD_EXPIRAION_DATE_LENGTH);
            assertThat(after.cardActiveStatus()).as("CARD-ACTIVE-STATUS X(01): changed").isEqualTo("N");
            assertThat(written.substring(CardRecord.FILLER_OFFSET,
                    CardRecord.FILLER_OFFSET + CardRecord.FILLER_LENGTH))
                    .as("gate G21: FILLER X(59) survives the round trip as 59 spaces")
                    .isEqualTo(" ".repeat(CardRecord.FILLER_LENGTH));
            assertThat(written).as("gate G19: and the image is the whole record, both ways")
                    .hasSize(CardRecord.RECORD_LENGTH);
            assertThat(after).as("the record read back IS the record written")
                    .isEqualTo(mutated);
        }

        @Test
        @DisplayName("keys the rewrite on the record's own card number, moved to sixteen characters")
        void keysOnTheRecordsOwnCardNumber() throws SQLException {
            stubTheRowIsThere();
            when(jdbcTemplate.update(anyString(), any(PreparedStatementSetter.class))).thenReturn(1);

            inUnitOfWork(() -> repository.rewrite(cardRecord("12345")));

            verify(bindRewrite()).setString(2, "12345           " + "%");
        }

        @ParameterizedTest(name = "a key selecting {0} rows is an invalid request")
        @ValueSource(ints = { 0, 2 })
        @DisplayName("a key that does not select exactly one row is refused before anything is written")
        void aKeyThatDoesNotSelectOneRowIsRefusedBeforeWriting(int selected) {
            stubFetch(new FetchedRows(cardRecord(FIRST_FIXTURE_CARD_NUM).encode(codec), selected));

            CardWriteResult result = inUnitOfWork(
                    () -> repository.rewrite(cardRecord(FIRST_FIXTURE_CARD_NUM)));

            assertThat(result.isNormal()).isFalse();
            assertThat(result.isFailure()).isTrue();
            assertThat(result.resp()).isEqualTo(FileStatus.INVREQ);
            assertThat(result.batchStatus()).isEmpty();
            assertThat(result.resp2()).isEqualTo(CardRepository.NO_REASON_CODE);
            assertThat(result.observation()).isPresent();
            assertThat(result.observation().orElseThrow().value()).isEqualTo(selected);
            assertThat(result.observation().orElseThrow().describe())
                    .isEqualTo("rows selected by the key = " + selected);

            verify(jdbcTemplate, never()).update(anyString(), any(PreparedStatementSetter.class));
        }

        @Test
        @DisplayName("the count is always the locking read, because a rewrite always has a unit of work")
        void theCountIsTakenUnderTheRowLockInsideAUnitOfWork() throws SQLException {
            stubTheRowIsThere();
            when(jdbcTemplate.update(anyString(), any(PreparedStatementSetter.class))).thenReturn(1);

            assertThat(inUnitOfWork(() -> repository.rewrite(cardRecord(FIRST_FIXTURE_CARD_NUM)))
                    .isNormal()).isTrue();
            assertThat(statementPreparedByTheCount())
                    .isEqualTo(repository.resolvedBaseStatements().selectForUpdateByCardNumber())
                    .endsWith("FOR UPDATE");
        }

        @Test
        @DisplayName("outside a unit of work the rewrite is refused before any statement is issued")
        void outsideAUnitOfWorkTheRewriteIsRefused() {
            stubTheRowIsThere();
            when(jdbcTemplate.update(anyString(), any(PreparedStatementSetter.class))).thenReturn(1);

            assertThatIllegalStateException()
                    .isThrownBy(() -> repository.rewrite(cardRecord(FIRST_FIXTURE_CARD_NUM)))
                    .withMessageContaining("no transaction is open on this thread")
                    .withMessageContaining("changes stored records");

            verify(jdbcTemplate, never()).update(anyString(), any(PreparedStatementSetter.class));
        }

        private String statementPreparedByTheCount() throws SQLException {
            ArgumentCaptor<PreparedStatementCreator> captor =
                    ArgumentCaptor.forClass(PreparedStatementCreator.class);
            verify(jdbcTemplate).query(captor.capture(),
                    CardRepositoryTest.<FetchedRows>anyExtractor());
            Connection connection = mock(Connection.class);
            when(connection.prepareStatement(anyString())).thenReturn(mock(PreparedStatement.class));
            captor.getValue().createPreparedStatement(connection);
            ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
            verify(connection).prepareStatement(sql.capture());
            return sql.getValue();
        }

        @Test
        @DisplayName("a write that replaces more rows than the count saw refuses the unit of work")
        void aWriteThatFansOutAfterTheCountRefusesTheUnitOfWork() {
            stubTheRowIsThere();
            when(jdbcTemplate.update(anyString(), any(PreparedStatementSetter.class))).thenReturn(2);

            assertThatExceptionOfType(DatasetIntegrityException.class)
                    .isThrownBy(() -> inUnitOfWork(
                            () -> repository.rewrite(cardRecord(FIRST_FIXTURE_CARD_NUM))))
                    .withMessageContaining("2 rows were replaced")
                    .withMessageContaining("must not be allowed to stand");
        }

        @Test
        @DisplayName("inside a unit of work the refusal says the count was taken under a row lock")
        void insideAUnitOfWorkTheRefusalNamesTheRowLock() {
            stubTheRowIsThere();
            when(jdbcTemplate.update(anyString(), any(PreparedStatementSetter.class))).thenReturn(3);

            assertThatExceptionOfType(DatasetIntegrityException.class)
                    .isThrownBy(() -> inUnitOfWork(
                            () -> repository.rewrite(cardRecord(FIRST_FIXTURE_CARD_NUM))))
                    .withMessageContaining("3 rows were replaced")
                    .withMessageContaining("under a row lock")
                    .withMessageContaining("rolled back rather than reported as a file status");
        }

        @Test
        @DisplayName("a row that disappears between the count and the write is an invalid request")
        void aRowThatDisappearsBetweenTheCountAndTheWrite() {
            stubTheRowIsThere();
            when(jdbcTemplate.update(anyString(), any(PreparedStatementSetter.class))).thenReturn(0);

            CardWriteResult result = inUnitOfWork(
                    () -> repository.rewrite(cardRecord(FIRST_FIXTURE_CARD_NUM)));

            assertThat(result.isFailure()).isTrue();
            assertThat(result.resp()).isEqualTo(FileStatus.INVREQ);
            assertThat(result.resp2()).isEqualTo(CardRepository.NO_REASON_CODE);
            assertThat(result.observation().orElseThrow().describe())
                    .isEqualTo("rows replaced by the write = 0");
        }

        @Test
        @DisplayName("a refused count is a failure of the rewrite, not a missing record")
        void aRefusedCountIsAFailure() {
            stubRejection(new DataAccessResourceFailureException("gone",
                    new SQLException("gone", "08003", VENDOR_ERROR_CODE)));

            CardWriteResult result = inUnitOfWork(
                    () -> repository.rewrite(cardRecord(FIRST_FIXTURE_CARD_NUM)));

            assertThat(result.isFailure()).isTrue();
            assertThat(result.resp()).isEqualTo(FileStatus.NOTOPEN);
            assertThat(result.observation()).isEmpty();
            verify(jdbcTemplate, never()).update(anyString(), any(PreparedStatementSetter.class));
        }

        @Test
        @DisplayName("LOCKED-BUT-UPDATE-FAILED: a rejected rewrite reports the response pair")
        void rejectedRewrite() {
            stubTheRowIsThere();
            when(jdbcTemplate.update(anyString(), any(PreparedStatementSetter.class)))
                    .thenThrow(new InvalidResultSetAccessException(
                            new SQLException("write refused", "22001", VENDOR_ERROR_CODE)));

            CardWriteResult result = inUnitOfWork(
                    () -> repository.rewrite(cardRecord(FIRST_FIXTURE_CARD_NUM)));

            assertThat(result.isFailure()).isTrue();
            assertThat(result.resp()).isEqualTo(FileStatus.INVREQ);
            assertThat(result.resp2()).isEqualTo(CardRepository.NO_REASON_CODE);
            assertThat(result.outcome()).isEqualTo(Outcome.OTHER);
        }

        @Test
        @DisplayName("an unreachable dataset is reported as not open")
        void unreachableDatasetOnRewrite() {
            stubTheRowIsThere();
            when(jdbcTemplate.update(anyString(), any(PreparedStatementSetter.class)))
                    .thenThrow(new DataAccessResourceFailureException("gone",
                            new SQLException("gone", "08003", VENDOR_ERROR_CODE)));

            assertThat(inUnitOfWork(() -> repository.rewrite(cardRecord(FIRST_FIXTURE_CARD_NUM)).resp()))
                    .isEqualTo(FileStatus.NOTOPEN);
        }

        @Test
        @DisplayName("refuses a null record: there is no partial-record rewrite")
        void refusesANullRecord() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> repository.rewrite(null));
        }
    }

    @Nested
    @DisplayName("startBrowse - COCRDLIC:1129 forward and :1273 backward")
    class BrowseTest {
        @ParameterizedTest
        @EnumSource(BrowseDirection.class)
        @DisplayName("positions at or after the key, in either direction, with no backend call")
        void positionsWithoutTouchingTheBackend(BrowseDirection direction) {
            CardBrowse browse = repository.startBrowse(FIRST_FIXTURE_CARD_NUM, direction);

            assertThat(browse.direction()).isEqualTo(direction);
            assertThat(browse.anchorKey()).isEqualTo(FIRST_FIXTURE_CARD_NUM)
                    .hasSize(CardRecord.CARD_NUM_LENGTH);
            assertThat(browse.positionKey()).isEmpty();
            assertThat(browse.isEnded()).isFalse();
            verify(jdbcTemplate, never()).query(any(PreparedStatementCreator.class),
                    CardRepositoryTest.<FetchedRows>anyExtractor());
        }

        @Test
        @DisplayName("moves the anchor into PIC X(16) exactly as a keyed read would")
        void movesTheAnchorKey() {
            assertThat(repository.startBrowse("123", BrowseDirection.FORWARD).anchorKey())
                    .isEqualTo("123             ");
        }

        @Test
        @DisplayName("refuses a null key or a null direction")
        void refusesNullArguments() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> repository.startBrowse(null, BrowseDirection.FORWARD));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> repository.startBrowse(FIRST_FIXTURE_CARD_NUM, null));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> repository.openBrowse(null, BrowseDirection.FORWARD));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> repository.openBrowse(FIRST_FIXTURE_CARD_NUM, null));
        }

        @Test
        @DisplayName("startBrowse reports NORMAL without asking, because COCRDLIC discards its own status")
        void startBrowseReportsNormalWithoutAsking() {
            CardBrowse browse = repository.startBrowse(FIRST_FIXTURE_CARD_NUM, BrowseDirection.FORWARD);

            assertThat(browse.openResp()).isEqualTo(FileStatus.NORMAL);
            assertThat(browse.isOpen()).isTrue();
            verify(jdbcTemplate, never()).query(anyString(),
                    CardRepositoryTest.<String>anyExtractor());
        }

        @Test
        @DisplayName("openBrowse describes the relation and reports NORMAL: the batch OPEN INPUT")
        void openBrowseProbesAndReportsNormal() {
            CardBrowse browse = repository.openBrowse(FIRST_FIXTURE_CARD_NUM, BrowseDirection.FORWARD);

            assertThat(browse.openResp()).isEqualTo(FileStatus.NORMAL);
            assertThat(browse.isOpen()).isTrue();
            assertThat(browse.anchorKey()).isEqualTo(FIRST_FIXTURE_CARD_NUM);
            assertThat(browse.direction()).isEqualTo(BrowseDirection.FORWARD);
            verify(jdbcTemplate).query(eq(repository.describeBaseStatement()),
                    CardRepositoryTest.<String>anyExtractor());
        }

        @Test
        @DisplayName("openBrowse reports NOTOPEN when the backend refuses the describe")
        void openBrowseReportsNotOpenOnRefusal() {
            when(jdbcTemplate.query(eq(repository.describeBaseStatement()),
                    CardRepositoryTest.<String>anyExtractor()))
                    .thenThrow(new DataAccessResourceFailureException("the dataset is not there"));

            CardBrowse browse = repository.openBrowse(FIRST_FIXTURE_CARD_NUM, BrowseDirection.FORWARD);

            assertThat(browse.openResp()).isEqualTo(FileStatus.NOTOPEN);
            assertThat(browse.isOpen()).isFalse();
        }

        @Test
        @DisplayName("openBrowse reports NOTOPEN when the relation presents no record-image column")
        void openBrowseReportsNotOpenWhenNoColumnIsPresented() {
            when(jdbcTemplate.query(eq(repository.describeBaseStatement()),
                    CardRepositoryTest.<String>anyExtractor())).thenReturn(null);

            CardBrowse browse = repository.openBrowse(FIRST_FIXTURE_CARD_NUM, BrowseDirection.FORWARD);

            assertThat(browse.openResp()).isEqualTo(FileStatus.NOTOPEN);
            assertThat(browse.isOpen()).isFalse();
        }

        @Test
        @DisplayName("openBrowse probes on EVERY call, so its outcome never depends on what ran before it")
        void openBrowseProbesOnEveryCall() {
            repository.openBrowse(FIRST_FIXTURE_CARD_NUM, BrowseDirection.FORWARD);
            repository.openBrowse(FIRST_FIXTURE_CARD_NUM, BrowseDirection.FORWARD);
            repository.openBrowse(FIRST_FIXTURE_CARD_NUM, BrowseDirection.BACKWARD);

            verify(jdbcTemplate, times(3)).query(eq(repository.describeBaseStatement()),
                    CardRepositoryTest.<String>anyExtractor());
        }

        @Test
        @DisplayName("finding DB-04: openBrowse describes the base cluster ONLY, never the AIX path")
        void openBrowseDescribesTheBaseClusterOnly() {
            repository.openBrowse(FIRST_FIXTURE_CARD_NUM, BrowseDirection.FORWARD);

            verify(jdbcTemplate).query(eq(repository.describeBaseStatement()),
                    CardRepositoryTest.<String>anyExtractor());
            verify(jdbcTemplate, never()).query(eq(repository.describeAlternateIndexStatement()),
                    CardRepositoryTest.<String>anyExtractor());
        }

        @Test
        @DisplayName("finding DB-04: openBrowse succeeds while the AIX path is unavailable")
        void openBrowseSucceedsWhileThePathIsUnavailable() {
            when(jdbcTemplate.query(eq(repository.describeAlternateIndexStatement()),
                    CardRepositoryTest.<String>anyExtractor()))
                    .thenThrow(new DataAccessResourceFailureException("the path is not there"));

            CardBrowse browse = repository.openBrowse(FIRST_FIXTURE_CARD_NUM, BrowseDirection.FORWARD);

            assertThat(browse.openResp())
                    .as("the availability of a path this pass does not read is not a precondition of it")
                    .isEqualTo(FileStatus.NORMAL);
            assertThat(browse.isOpen()).isTrue();
        }

        @Test
        @DisplayName("finding DB-04: a keyed read of the base cluster survives an unavailable AIX path")
        void aBaseKeyedReadSurvivesAnUnavailablePath() {
            when(jdbcTemplate.query(eq(repository.describeAlternateIndexStatement()),
                    CardRepositoryTest.<String>anyExtractor()))
                    .thenThrow(new DataAccessResourceFailureException("the path is not there"));
            stubFetch(new FetchedRows(cardRecord(FIRST_FIXTURE_CARD_NUM)
                    .encode(codec.charset()), 1));

            CardReadResult result = repository.readByCardNumber(FIRST_FIXTURE_CARD_NUM);

            assertThat(result.isRecordReturned()).isTrue();
            verify(jdbcTemplate, never()).query(eq(repository.describeAlternateIndexStatement()),
                    CardRepositoryTest.<String>anyExtractor());
        }

        @Test
        @DisplayName("finding DB-04: a read through the AIX path survives an unavailable base cluster")
        void anAlternateReadSurvivesAnUnavailableBaseCluster() {
            when(jdbcTemplate.query(eq(repository.describeBaseStatement()),
                    CardRepositoryTest.<String>anyExtractor()))
                    .thenThrow(new DataAccessResourceFailureException("the base cluster is not there"));
            stubFetch(new FetchedRows(cardRecord(FIRST_FIXTURE_CARD_NUM)
                    .encode(codec.charset()), 1));

            CardReadResult result = repository.readByAccountIdViaAltIndex(FIRST_FIXTURE_ACCT_ID);

            assertThat(result.isRecordReturned())
                    .as("CARDAIX answered, and CARDDAT was never asked")
                    .isTrue();
        }

        @Test
        @DisplayName("an OPEN after the statements are memoised still detects a dataset that has gone away")
        void openBrowseDetectsAnAbsentDatasetAfterTheStatementsAreMemoised() {
            when(jdbcTemplate.query(any(PreparedStatementCreator.class),
                    CardRepositoryTest.<FetchedRows>anyExtractor())).thenReturn(FetchedRows.empty());
            repository.readByCardNumber(FIRST_FIXTURE_CARD_NUM);
            assertThat(repository.resolvedBaseStatements())
                    .as("the read has resolved and memoised the statement text")
                    .isNotNull();

            when(jdbcTemplate.query(eq(repository.describeBaseStatement()),
                    CardRepositoryTest.<String>anyExtractor()))
                    .thenThrow(new DataAccessResourceFailureException("the dataset is no longer there"));

            CardBrowse browse = repository.openBrowse(FIRST_FIXTURE_CARD_NUM, BrowseDirection.FORWARD);

            assertThat(browse.openResp()).isEqualTo(FileStatus.NOTOPEN);
            assertThat(browse.isOpen()).isFalse();
        }

        @Test
        @DisplayName("an OPEN republishes the memo, so a read after it uses freshly composed text")
        void openBrowseRepublishesTheMemoisedStatements() {
            when(jdbcTemplate.query(any(PreparedStatementCreator.class),
                    CardRepositoryTest.<FetchedRows>anyExtractor())).thenReturn(FetchedRows.empty());
            repository.readByCardNumber(FIRST_FIXTURE_CARD_NUM);
            CardRepository.BaseStatements beforeTheOpen = repository.resolvedBaseStatements();

            repository.openBrowse(FIRST_FIXTURE_CARD_NUM, BrowseDirection.FORWARD);

            CardRepository.BaseStatements afterTheOpen = repository.resolvedBaseStatements();
            assertThat(afterTheOpen)
                    .as("the open composed afresh rather than trusting the memo")
                    .isNotSameAs(beforeTheOpen);
            assertThat(afterTheOpen.selectByCardNumber())
                    .as("and composed the same text, because the described column has not changed")
                    .isEqualTo(beforeTheOpen.selectByCardNumber());
        }

        @Test
        @DisplayName("the first READNEXT anchors at or after the key; the next one advances past it")
        void forwardWalk() throws SQLException {
            CardRecord first = cardRecord(FIRST_FIXTURE_CARD_NUM);
            CardRecord second = cardRecord(SECOND_FIXTURE_CARD_NUM);
            when(jdbcTemplate.query(any(PreparedStatementCreator.class),
                    CardRepositoryTest.<FetchedRows>anyExtractor()))
                    .thenReturn(oneRow(first), oneRow(second), FetchedRows.empty());

            CardBrowse browse = repository.startBrowse(FIRST_FIXTURE_CARD_NUM,
                    BrowseDirection.FORWARD);
            assertThat(browse.readNext().requireRecord()).isEqualTo(first);
            assertThat(browse.positionKey()).contains(FIRST_FIXTURE_CARD_NUM);
            assertThat(browse.readNext().requireRecord()).isEqualTo(second);
            assertThat(browse.positionKey()).contains(SECOND_FIXTURE_CARD_NUM);

            assertThat(statementsSent(2))
                    .as("the first read anchors with GTEQ; every read after it advances")
                    .containsExactly(repository.resolvedBaseStatements().browseAnchor(),
                            repository.resolvedBaseStatements().browseForward());
        }

        @Test
        @DisplayName("walks eight records in ascending order: the repository imposes no page limit")
        void walksPastThePageSizeBecausePagingIsTheControllersBusiness() throws SQLException {
            List<String> rows = fixtureRows();
            int walked = 8;
            assertThat(walked).as("deliberately one more than the controller's screen depth of 7")
                    .isGreaterThan(7);
            FetchedRows[] answers = new FetchedRows[walked];
            for (int index = 0; index < walked; index++) {
                answers[index] = new FetchedRows(rows.get(index).getBytes(FIXTURE_CHARSET), 1);
            }
            when(jdbcTemplate.query(any(PreparedStatementCreator.class),
                    CardRepositoryTest.<FetchedRows>anyExtractor()))
                    .thenReturn(answers[0], Arrays.copyOfRange(answers, 1, walked));

            CardBrowse browse = repository.startBrowse(rows.get(0).substring(0,
                    CardRecord.CARD_NUM_LENGTH), BrowseDirection.FORWARD);
            List<String> delivered = new ArrayList<>();
            for (int step = 0; step < walked; step++) {
                CardReadResult result = browse.readNext();
                assertThat(result.isRecordReturned()).as("step %d returned a record", step + 1).isTrue();
                delivered.add(result.requireRecord().cardNum());
                assertThat(browse.positionKey())
                        .as("each READNEXT advances the position by exactly one record")
                        .contains(delivered.get(step));
            }

            assertThat(delivered).hasSize(walked).doesNotHaveDuplicates()
                    .as("ascending CARD-NUM order, which is the order the fixture stores them in")
                    .isSorted()
                    .containsExactlyElementsOf(rows.subList(0, walked).stream()
                            .map(row -> row.substring(0, CardRecord.CARD_NUM_LENGTH)).toList());
            assertThat(statementsSent(walked))
                    .as("one anchoring read, then seven advancing ones - no limit anywhere in between")
                    .startsWith(repository.resolvedBaseStatements().browseAnchor())
                    .endsWith(repository.resolvedBaseStatements().browseForward());
        }

        @Test
        @DisplayName("GTEQ: anchoring on a key the file lacks positions at the next greater record")
        void anchorsAtOrAfterAKeyTheFileDoesNotContain() throws SQLException {
            List<String> rows = fixtureRows();
            String firstStoredKey = rows.get(0).substring(0, CardRecord.CARD_NUM_LENGTH);
            String absentKey = "0000000000000000";
            assertThat(rows).as("the anchor key is genuinely absent from the fixture")
                    .noneMatch(row -> row.startsWith(absentKey));
            assertThat(absentKey).isLessThan(firstStoredKey)
                    .hasSize(CardRecord.CARD_NUM_LENGTH);
            stubFetch(new FetchedRows(rows.get(0).getBytes(FIXTURE_CHARSET), 1));

            CardBrowse browse = repository.startBrowse(absentKey, BrowseDirection.FORWARD);
            CardReadResult positioned = browse.readNext();

            assertThat(positioned.isRecordReturned())
                    .as("at-or-after positioning: an absent anchor is not a missing record")
                    .isTrue();
            assertThat(positioned.isNotFound()).isFalse();
            assertThat(positioned.requireRecord().cardNum())
                    .as("the browse landed on the next key greater than the anchor")
                    .isEqualTo(firstStoredKey).isGreaterThan(absentKey);
            assertThat(browse.positionKey()).contains(firstStoredKey);

            assertThat(statementsSent(1))
                    .containsExactly(repository.resolvedBaseStatements().browseAnchor());
            assertThat(repository.resolvedBaseStatements().browseAnchor())
                    .as("GTEQ in SQL is a >= predicate on the record image")
                    .contains(">=")
                    .isNotEqualTo(repository.resolvedBaseStatements().selectByCardNumber());
        }

        @Test
        @DisplayName("the browse advances past the exact bytes the row held, not a re-encoding of them")
        void advancesPastTheExactStoredImage() throws SQLException {
            CardRecord stored = cardRecord(FIRST_FIXTURE_CARD_NUM);
            byte[] exact = stored.encode(codec);
            byte[] reEncoded = exact.clone();
            for (int index = CardRecord.FILLER_OFFSET;
                    index < CardRecord.FILLER_OFFSET + CardRecord.FILLER_LENGTH; index++) {
                exact[index] = (byte) 'X';
            }
            assertThat(exact).as("the stored row differs from what the model would write")
                    .isNotEqualTo(reEncoded);

            when(jdbcTemplate.query(any(PreparedStatementCreator.class),
                    CardRepositoryTest.<FetchedRows>anyExtractor()))
                    .thenReturn(new FetchedRows(exact, 1), FetchedRows.empty());

            CardBrowse browse = repository.startBrowse(FIRST_FIXTURE_CARD_NUM,
                    BrowseDirection.FORWARD);
            assertThat(browse.readNext().isRecordReturned()).isTrue();
            assertThat(browse.positionKey()).contains(FIRST_FIXTURE_CARD_NUM);
            assertThat(browse.readNext().isEndOfFile()).isTrue();

            ArgumentCaptor<PreparedStatementCreator> creators =
                    ArgumentCaptor.forClass(PreparedStatementCreator.class);
            verify(jdbcTemplate, times(2)).query(creators.capture(),
                    CardRepositoryTest.<FetchedRows>anyExtractor());
            PreparedStatement advancing = mock(PreparedStatement.class);
            Connection connection = mock(Connection.class);
            when(connection.prepareStatement(anyString())).thenReturn(advancing);
            creators.getAllValues().get(1).createPreparedStatement(connection);
            ArgumentCaptor<String> bound = ArgumentCaptor.forClass(String.class);
            verify(advancing).setString(eq(1), bound.capture());
            assertThat(bound.getValue().getBytes(codec.charset()))
                    .as("the browse advances past the stored bytes, not a re-encoded record")
                    .isEqualTo(exact)
                    .isNotEqualTo(reEncoded);
        }

        @Test
        @DisplayName("a browse read that yields no result object at all is the end of the file, not a null")
        void aBrowseReadThatYieldsNothingIsEndOfFile() {
            when(jdbcTemplate.query(any(PreparedStatementCreator.class),
                    CardRepositoryTest.<FetchedRows>anyExtractor())).thenReturn(null);

            CardBrowse browse = repository.startBrowse(FIRST_FIXTURE_CARD_NUM,
                    BrowseDirection.FORWARD);

            assertThat(browse.readNext().isEndOfFile()).isTrue();
            assertThat(browse.positionKey()).isEmpty();
        }

        @Test
        @DisplayName("gate G47: running past the last record reports '10', repeatably")
        void forwardEndOfFile() {
            when(jdbcTemplate.query(any(PreparedStatementCreator.class),
                    CardRepositoryTest.<FetchedRows>anyExtractor()))
                    .thenReturn(FetchedRows.empty());

            CardBrowse browse = repository.startBrowse(FIRST_FIXTURE_CARD_NUM,
                    BrowseDirection.FORWARD);
            CardReadResult first = browse.readNext();

            assertThat(first.isEndOfFile()).isTrue();
            assertThat(first.resp()).isEqualTo(FileStatus.ENDFILE);
            assertThat(first.outcome()).isEqualTo(Outcome.END_OF_FILE);
            assertThat(first.batchStatus()).contains(FileStatus.END_OF_FILE);
            assertThat(first.record()).isEmpty();
            assertThat(browse.positionKey()).as("an end of file does not advance the position")
                    .isEmpty();
            assertThat(browse.readNext().isEndOfFile()).as("so repeating it says the same thing")
                    .isTrue();
        }

        @Test
        @DisplayName("the first READPREV returns the anchored record; the next ones descend from it")
        void backwardWalk() throws SQLException {
            CardRecord atAnchor = cardRecord(SECOND_FIXTURE_CARD_NUM);
            CardRecord below = cardRecord(FIRST_FIXTURE_CARD_NUM);
            when(jdbcTemplate.query(any(PreparedStatementCreator.class),
                    CardRepositoryTest.<FetchedRows>anyExtractor()))
                    .thenReturn(oneRow(atAnchor), oneRow(below), FetchedRows.empty());

            CardBrowse browse = repository.startBrowse(SECOND_FIXTURE_CARD_NUM,
                    BrowseDirection.BACKWARD);
            assertThat(browse.readPrev().requireRecord())
                    .as("COCRDLIC:1294 reads it and discards it - which is the caller's choice")
                    .isEqualTo(atAnchor);
            assertThat(browse.readPrev().requireRecord()).isEqualTo(below);
            assertThat(browse.readPrev().isEndOfFile()).isTrue();

            assertThat(statementsSent(3))
                    .as("GTEQ anchors both directions; only the advance differs")
                    .containsExactly(repository.resolvedBaseStatements().browseAnchor(),
                            repository.resolvedBaseStatements().browseBackward(),
                            repository.resolvedBaseStatements().browseBackward());
        }

        @Test
        @DisplayName("a duplicate response on a browse step still means a record was returned")
        void duplicateOnABrowseStepIsARecord() {
            CardRecord stored = cardRecord(FIRST_FIXTURE_CARD_NUM);
            when(jdbcTemplate.query(any(PreparedStatementCreator.class),
                    CardRepositoryTest.<FetchedRows>anyExtractor()))
                    .thenReturn(new FetchedRows(stored.encode(codec), 1));

            CardReadResult result = repository.startBrowse(FIRST_FIXTURE_CARD_NUM,
                    BrowseDirection.FORWARD).readNext();

            assertThat(result.isRecordReturned())
                    .as("COCRDLIC:1157-1158 treats NORMAL and the duplicate arm identically")
                    .isTrue();
        }

        @Test
        @DisplayName("a browse step that fails does not advance, so a retry retries the same step")
        void browseStepFailureDoesNotAdvance() {
            stubRejection(new InvalidResultSetAccessException(new SQLException("browse broke")));

            CardBrowse browse = repository.startBrowse(FIRST_FIXTURE_CARD_NUM,
                    BrowseDirection.FORWARD);
            CardReadResult result = browse.readNext();

            assertThat(result.isFailure()).isTrue();
            assertThat(result.resp()).isEqualTo(FileStatus.INVREQ);
            assertThat(browse.positionKey()).isEmpty();
        }

        @Test
        @DisplayName("a wrong-width row during a browse is a length error, not the end of the file")
        void browseStepWrongWidth() {
            when(jdbcTemplate.query(any(PreparedStatementCreator.class),
                    CardRepositoryTest.<FetchedRows>anyExtractor()))
                    .thenReturn(new FetchedRows(new byte[10], 1));

            CardReadResult result = repository.startBrowse(FIRST_FIXTURE_CARD_NUM,
                    BrowseDirection.FORWARD).readNext();

            assertThat(result.resp()).isEqualTo(FileStatus.LENGERR);
            assertThat(result.isEndOfFile()).isFalse();
        }

        @Test
        @DisplayName("ENDBR yields nothing, makes no backend call, and is safe to repeat")
        void endBrowse() {
            CardBrowse browse = repository.startBrowse(FIRST_FIXTURE_CARD_NUM,
                    BrowseDirection.FORWARD);

            browse.endBrowse();
            assertThat(browse.isEnded()).isTrue();
            browse.endBrowse();
            browse.close();
            assertThat(browse.isEnded()).isTrue();
            verify(jdbcTemplate, never()).query(any(PreparedStatementCreator.class),
                    CardRepositoryTest.<FetchedRows>anyExtractor());
        }

        @Test
        @DisplayName("closing works in a try-with-resources block")
        void closesAsAResource() {
            when(jdbcTemplate.query(any(PreparedStatementCreator.class),
                    CardRepositoryTest.<FetchedRows>anyExtractor()))
                    .thenReturn(FetchedRows.empty());
            CardBrowse escaped;
            try (CardBrowse browse = repository.startBrowse(FIRST_FIXTURE_CARD_NUM,
                    BrowseDirection.FORWARD)) {
                assertThat(browse.readNext().isEndOfFile()).isTrue();
                escaped = browse;
            }
            assertThat(escaped.isEnded()).isTrue();
        }

        @Test
        @DisplayName("reading an ended browse is an invalid request, not a resumed browse")
        void readingAnEndedBrowse() {
            CardBrowse forward = repository.startBrowse(FIRST_FIXTURE_CARD_NUM,
                    BrowseDirection.FORWARD);
            CardBrowse backward = repository.startBrowse(FIRST_FIXTURE_CARD_NUM,
                    BrowseDirection.BACKWARD);
            forward.endBrowse();
            backward.endBrowse();

            assertThat(forward.readNext().resp()).isEqualTo(FileStatus.INVREQ);
            assertThat(backward.readPrev().resp()).isEqualTo(FileStatus.INVREQ);
            verify(jdbcTemplate, never()).query(any(PreparedStatementCreator.class),
                    CardRepositoryTest.<FetchedRows>anyExtractor());
        }

        @Test
        @DisplayName("a browse is never read the way it was not positioned for")
        void readingAgainstTheDirection() {
            CardBrowse forward = repository.startBrowse(FIRST_FIXTURE_CARD_NUM,
                    BrowseDirection.FORWARD);
            CardBrowse backward = repository.startBrowse(FIRST_FIXTURE_CARD_NUM,
                    BrowseDirection.BACKWARD);

            assertThat(forward.readPrev().resp()).isEqualTo(FileStatus.INVREQ);
            assertThat(backward.readNext().resp()).isEqualTo(FileStatus.INVREQ);
            verify(jdbcTemplate, never()).query(any(PreparedStatementCreator.class),
                    CardRepositoryTest.<FetchedRows>anyExtractor());
        }

        @Test
        @DisplayName("two browses of the same file cannot see each other's position")
        void browsesAreIndependent() {
            CardRecord first = cardRecord(FIRST_FIXTURE_CARD_NUM);
            when(jdbcTemplate.query(any(PreparedStatementCreator.class),
                    CardRepositoryTest.<FetchedRows>anyExtractor())).thenReturn(oneRow(first));

            CardBrowse mine = repository.startBrowse(FIRST_FIXTURE_CARD_NUM,
                    BrowseDirection.FORWARD);
            CardBrowse yours = repository.startBrowse(SECOND_FIXTURE_CARD_NUM,
                    BrowseDirection.FORWARD);
            mine.readNext();

            assertThat(mine.positionKey()).contains(FIRST_FIXTURE_CARD_NUM);
            assertThat(yours.positionKey()).as("no state leaked through the singleton").isEmpty();
            assertThat(yours.anchorKey()).isEqualTo(SECOND_FIXTURE_CARD_NUM);
        }

        private List<String> statementsSent(int expectedCalls) throws SQLException {
            ArgumentCaptor<PreparedStatementCreator> captor =
                    ArgumentCaptor.forClass(PreparedStatementCreator.class);
            verify(jdbcTemplate, times(expectedCalls)).query(captor.capture(),
                    CardRepositoryTest.<FetchedRows>anyExtractor());
            List<String> statements = new ArrayList<>();
            for (PreparedStatementCreator creator : captor.getAllValues()) {
                Connection connection = mock(Connection.class);
                when(connection.prepareStatement(anyString()))
                        .thenReturn(mock(PreparedStatement.class));
                creator.createPreparedStatement(connection);
                ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
                verify(connection).prepareStatement(text.capture());
                statements.add(text.getValue());
            }
            return statements;
        }
    }

    @Nested
    @DisplayName("row reading")
    class RowReadingTest {
        @Test
        @DisplayName("under BINARY it takes the bytes the column holds, and asks for nothing else")
        void readsABinaryImage() throws SQLException {
            byte[] stored = cardRecord(FIRST_FIXTURE_CARD_NUM).encode(codec);
            ResultSet resultSet = mock(ResultSet.class);
            when(resultSet.getBytes(CardRepository.RECORD_IMAGE_COLUMN_INDEX)).thenReturn(stored);

            assertThat(binaryRepository().readRecordImage(resultSet)).isEqualTo(stored);
            verify(resultSet, never()).getString(CardRepository.RECORD_IMAGE_COLUMN_INDEX);
        }

        @Test
        @DisplayName("under CHARACTER it reads the column as text, and asks for nothing else")
        void readsACharacterImage() throws SQLException {
            CardRecord stored = cardRecord(FIRST_FIXTURE_CARD_NUM);
            ResultSet resultSet = mock(ResultSet.class);
            when(resultSet.getString(CardRepository.RECORD_IMAGE_COLUMN_INDEX))
                    .thenReturn(stored.encodeToImage(StandardCharsets.US_ASCII));

            assertThat(repository.readRecordImage(resultSet)).isEqualTo(stored.encode(codec));
            verify(resultSet, never()).getBytes(CardRepository.RECORD_IMAGE_COLUMN_INDEX);
        }

        @Test
        @DisplayName("neither form falls back to the other: one column has one JDBC type")
        void thereIsNoFallbackBetweenTheTwoForms() throws SQLException {
            byte[] stored = cardRecord(FIRST_FIXTURE_CARD_NUM).encode(codec);

            ResultSet textOnly = mock(ResultSet.class);
            when(textOnly.getBytes(CardRepository.RECORD_IMAGE_COLUMN_INDEX)).thenReturn(null);
            when(textOnly.getString(CardRepository.RECORD_IMAGE_COLUMN_INDEX))
                    .thenReturn(cardRecord(FIRST_FIXTURE_CARD_NUM)
                            .encodeToImage(StandardCharsets.US_ASCII));
            assertThat(binaryRepository().readRecordImage(textOnly))
                    .as("a BINARY deployment does not silently re-read its column as text")
                    .isNull();

            ResultSet bytesOnly = mock(ResultSet.class);
            when(bytesOnly.getBytes(CardRepository.RECORD_IMAGE_COLUMN_INDEX)).thenReturn(stored);
            when(bytesOnly.getString(CardRepository.RECORD_IMAGE_COLUMN_INDEX)).thenReturn(null);
            assertThat(repository.readRecordImage(bytesOnly))
                    .as("a CHARACTER deployment does not silently re-read its column as bytes")
                    .isNull();
        }

        @Test
        @DisplayName("a row whose column holds no value carries no image, under either form")
        void readsNoImageAtAll() throws SQLException {
            ResultSet resultSet = mock(ResultSet.class);
            when(resultSet.getBytes(CardRepository.RECORD_IMAGE_COLUMN_INDEX)).thenReturn(null);
            when(resultSet.getString(CardRepository.RECORD_IMAGE_COLUMN_INDEX)).thenReturn(null);

            assertThat(repository.readRecordImage(resultSet)).isNull();
            assertThat(binaryRepository().readRecordImage(resultSet)).isNull();
        }

        private CardRepository binaryRepository() {
            return new CardRepository(jdbcTemplate, bindings(), codec, RecordImageForm.BINARY);
        }

        @ParameterizedTest(name = "{0} available rows under a limit of {1} yields {2}")
        @CsvSource({ "0,1,0", "1,1,1", "3,1,1", "0,2,0", "1,2,1", "2,2,2", "5,2,2" })
        @DisplayName("reads no more rows than the limit allows, and counts what it read")
        void stopsAtTheRowLimit(int available, int rowLimit, int expectedCount) throws SQLException {
            byte[] stored = cardRecord(FIRST_FIXTURE_CARD_NUM).encode(codec);
            ResultSet resultSet = mock(ResultSet.class);
            Boolean[] remaining = new Boolean[available + 1];
            for (int index = 0; index < available; index++) {
                remaining[index] = Boolean.TRUE;
            }
            remaining[available] = Boolean.FALSE;
            when(resultSet.next()).thenReturn(remaining[0],
                    Arrays.copyOfRange(remaining, 1, remaining.length));
            when(resultSet.getString(CardRepository.RECORD_IMAGE_COLUMN_INDEX))
                    .thenReturn(new String(stored, StandardCharsets.US_ASCII));

            FetchedRows rows = repository.extractRows(resultSet, rowLimit);

            assertThat(rows.rowCount()).isEqualTo(expectedCount);
            if (expectedCount == 0) {
                assertThat(rows.firstImage()).isNull();
            } else {
                assertThat(rows.firstImage()).isEqualTo(stored);
            }
        }

        @Test
        @DisplayName("the empty answer carries no image and no rows")
        void theEmptyAnswer() {
            assertThat(FetchedRows.empty().rowCount()).isZero();
            assertThat(FetchedRows.empty().firstImage()).isNull();
        }

        @Test
        @DisplayName("a statement is prepared once, bound once, and bounded")
        void preparesBindsAndBounds() throws SQLException {
            Connection connection = mock(Connection.class);
            PreparedStatement prepared = mock(PreparedStatement.class);
            when(connection.prepareStatement("SELECT 1")).thenReturn(prepared);

            PreparedStatement returned = repository.keyedStatement("SELECT 1", "KEY", 7)
                    .createPreparedStatement(connection);

            assertThat(returned).isSameAs(prepared);
            verify(prepared).setMaxRows(7);
            verify(prepared).setFetchSize(7);
            verify(prepared).setString(1, "KEY");
        }
    }

    @Nested
    @DisplayName("raw record image fidelity - the FILLER X(59) of CVACT02Y")
    class RawRecordImageFidelityTest {
        private static final String DIRTY_FILLER = "*".repeat(CardRecord.FILLER_LENGTH);

        @Test
        @DisplayName("a keyed read hands back the row's own bytes, FILLER included")
        void aKeyedReadRetainsTheRowsBytes() {
            String dirty = dirtyImage();
            stubFetch(new FetchedRows(dirty.getBytes(StandardCharsets.US_ASCII), 1));

            CardReadResult read = repository.readByCardNumber(FIRST_FIXTURE_CARD_NUM);

            assertThat(read.outcome()).isEqualTo(Outcome.OK);
            assertThat(read.requireStoredImage())
                    .as("the record area DISPLAY writes is the row's, byte for byte")
                    .isEqualTo(dirty);
        }

        @Test
        @DisplayName("a browse step hands back the row's own bytes, FILLER included")
        void aBrowseStepRetainsTheRowsBytes() {
            String dirty = dirtyImage();
            stubFetch(new FetchedRows(dirty.getBytes(StandardCharsets.US_ASCII), 1));

            CardReadResult read = repository
                    .openBrowse(" ".repeat(CardRecord.CARD_NUM_LENGTH), BrowseDirection.FORWARD)
                    .readNext();

            assertThat(read.outcome()).isEqualTo(Outcome.OK);
            assertThat(read.requireStoredImage()).isEqualTo(dirty);
        }

        @Test
        @DisplayName("re-encoding the decoded record would have lost the FILLER, which is the finding")
        void reEncodingTheDecodedRecordWouldHaveLostIt() {
            String dirty = dirtyImage();
            stubFetch(new FetchedRows(dirty.getBytes(StandardCharsets.US_ASCII), 1));

            CardReadResult read = repository.readByCardNumber(FIRST_FIXTURE_CARD_NUM);
            CardRecord decoded = read.record().orElseThrow();

            assertThat(decoded).isEqualTo(cardRecord(FIRST_FIXTURE_CARD_NUM));
            assertThat(decoded.encodeToImage(StandardCharsets.US_ASCII))
                    .as("a fresh record area blanks FILLER X(59), so this is not what CBACT02C:78 writes")
                    .hasSize(CardRepository.RECORD_LENGTH)
                    .isNotEqualTo(dirty)
                    .endsWith(" ".repeat(CardRecord.FILLER_LENGTH));
        }

        private String dirtyImage() {
            String clean = storedImage(cardRecord(FIRST_FIXTURE_CARD_NUM));
            String dirty = clean.substring(0, CardRepository.RECORD_LENGTH - CardRecord.FILLER_LENGTH)
                    + DIRTY_FILLER;
            assertThat(dirty).hasSize(CardRepository.RECORD_LENGTH).isNotEqualTo(clean);
            return dirty;
        }
    }

    @Nested
    @DisplayName("result types")
    class ResultTypeTest {
        @Test
        @DisplayName("every factory builds a result whose response and classification agree")
        void factoriesAgreeWithThemselves() {
            CardRecord stored = cardRecord(FIRST_FIXTURE_CARD_NUM);

            assertThat(cardRead(stored).batchStatus()).contains(FileStatus.OK);
            assertThat(cardReadDuplicate(stored).batchStatus())
                    .contains(FileStatus.DUPLICATE);
            assertThat(CardReadResult.notFound().batchStatus()).contains(FileStatus.NOT_FOUND);
            assertThat(CardReadResult.endOfFile().batchStatus()).contains(FileStatus.END_OF_FILE);
            assertThat(CardReadResult.failed(FileStatus.INVREQ).batchStatus()).isEmpty();
            assertThat(CardWriteResult.normal().batchStatus()).contains(FileStatus.OK);
            assertThat(CardWriteResult.failed(FileStatus.NOTOPEN).batchStatus()).isEmpty();
        }

        @Test
        @DisplayName("gate G47: all four batch statuses are reachable from this class")
        void allFourBatchStatusesAreReachable() {
            assertThat(List.of(cardRead(cardRecord(FIRST_FIXTURE_CARD_NUM)),
                            CardReadResult.endOfFile(),
                            cardReadDuplicate(cardRecord(FIRST_FIXTURE_CARD_NUM)),
                            CardReadResult.notFound())
                    .stream()
                    .map(result -> result.batchStatus().orElseThrow())
                    .toList())
                    .containsExactly(FileStatus.OK, FileStatus.END_OF_FILE, FileStatus.DUPLICATE,
                            FileStatus.NOT_FOUND);
        }

        @Test
        @DisplayName("a read factory refuses a null record where the arm returns one")
        void readFactoriesRefuseANullRecord() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> CardReadResult.normal(null, " ".repeat(
                            CardRepository.RECORD_LENGTH)));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> CardReadResult.duplicateKey(null, " ".repeat(
                            CardRepository.RECORD_LENGTH)));
        }

        @ParameterizedTest(name = "response {0} cannot be reported as WHEN OTHER")
        @ValueSource(ints = { FileStatus.NORMAL, FileStatus.NOTFND, FileStatus.ENDFILE,
                              FileStatus.DUPKEY, FileStatus.DUPREC })
        @DisplayName("a named arm cannot be smuggled through the failure factory")
        void namedArmsAreRefusedByTheFailureFactory(int resp) {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> CardReadResult.failed(resp));
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> CardWriteResult.failed(resp));
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> CardReadResult.failed(resp,
                            DatasetObservation.recordWidth(91)));
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> CardWriteResult.failed(resp,
                            DatasetObservation.matchingRows(2)));
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> CardReadResult.reportedFailure(resp, 0));
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> CardWriteResult.reportedFailure(resp, 0));
        }

        @Test
        @DisplayName("a negative reason code is refused, because it is not one")
        void aNegativeReasonCodeIsRefused() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> CardReadResult.reportedFailure(FileStatus.INVREQ, -1))
                    .withMessageContaining("reason codes are non-negative")
                    .withMessageContaining("DatasetObservation");
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> CardWriteResult.reportedFailure(FileStatus.INVREQ, -911))
                    .withMessageContaining("reason codes are non-negative");
            assertThat(CardReadResult.reportedFailure(FileStatus.LENGERR, 80).resp2()).isEqualTo(80);
            assertThat(CardWriteResult.reportedFailure(FileStatus.NOTOPEN, 12).resp2()).isEqualTo(12);
        }

        @Test
        @DisplayName("a result whose classification disagrees with its response is rejected")
        void classificationMustAgreeWithTheResponse() {
            CardRecord stored = cardRecord(FIRST_FIXTURE_CARD_NUM);

            assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(
                    () -> new CardReadResult(FileStatus.NORMAL, 0, Outcome.OTHER,
                            Optional.of(stored), Optional.of(storedImage(stored)),
                            Optional.empty()));
            assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(
                    () -> new CardWriteResult(FileStatus.NORMAL, 0, Outcome.OTHER,
                            Optional.empty()));
        }

        @Test
        @DisplayName("a result rejects a record on an arm that returns none, and vice versa")
        void recordPresenceMustMatchTheArm() {
            CardRecord stored = cardRecord(FIRST_FIXTURE_CARD_NUM);

            assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(
                    () -> new CardReadResult(FileStatus.NOTFND, 0, Outcome.NOT_FOUND,
                            Optional.of(stored), Optional.of(storedImage(stored)),
                            Optional.empty()));
            assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(
                    () -> new CardReadResult(FileStatus.NORMAL, 0, Outcome.OK, Optional.empty(),
                            Optional.empty(), Optional.empty()));
        }

        @Test
        @DisplayName("the stored image travels with the record and never without it, at its own width")
        void theStoredImageTravelsWithTheRecord() {
            CardRecord stored = cardRecord(FIRST_FIXTURE_CARD_NUM);

            assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(
                    () -> new CardReadResult(FileStatus.NORMAL, 0, Outcome.OK, Optional.of(stored),
                            Optional.empty(), Optional.empty()))
                    .withMessageContaining("must carry the stored image");
            assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(
                    () -> new CardReadResult(FileStatus.NOTFND, 0, Outcome.NOT_FOUND, Optional.empty(),
                            Optional.of(storedImage(stored)), Optional.empty()))
                    .withMessageContaining("no stored image");
            assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(
                    () -> CardReadResult.normal(stored, storedImage(stored).substring(1)))
                    .withMessageContaining(String.valueOf(CardRepository.RECORD_LENGTH));
            assertThatExceptionOfType(NullPointerException.class).isThrownBy(
                    () -> CardReadResult.normal(stored, null));
            assertThatExceptionOfType(NullPointerException.class).isThrownBy(
                    () -> CardReadResult.duplicateKey(stored, null));
        }

        @Test
        @DisplayName("requireStoredImage yields the row's bytes, and refuses an arm that carries none")
        void requireStoredImageYieldsTheRowsBytes() {
            CardRecord stored = cardRecord(FIRST_FIXTURE_CARD_NUM);

            assertThat(cardRead(stored).requireStoredImage())
                    .hasSize(CardRepository.RECORD_LENGTH)
                    .isEqualTo(storedImage(stored));
            assertThat(cardReadDuplicate(stored).requireStoredImage())
                    .isEqualTo(storedImage(stored));
            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> CardReadResult.notFound().requireStoredImage())
                    .withMessageContaining("no stored image");
            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> CardReadResult.endOfFile().requireStoredImage());
        }

        @Test
        @DisplayName("a result never carries a null classification or a null record holder")
        void resultsRejectNulls() {
            CardRecord stored = cardRecord(FIRST_FIXTURE_CARD_NUM);

            assertThatExceptionOfType(NullPointerException.class).isThrownBy(
                    () -> new CardReadResult(FileStatus.NORMAL, 0, null, Optional.empty(),
                            Optional.empty(), Optional.empty()));
            assertThatExceptionOfType(NullPointerException.class).isThrownBy(
                    () -> new CardReadResult(FileStatus.NORMAL, 0, Outcome.OK, null,
                            Optional.empty(), Optional.empty()));
            assertThatExceptionOfType(NullPointerException.class).isThrownBy(
                    () -> new CardReadResult(FileStatus.NORMAL, 0, Outcome.OK, Optional.of(stored),
                            null, Optional.empty()))
                    .withMessageContaining("empty stored image");
            assertThatExceptionOfType(NullPointerException.class).isThrownBy(
                    () -> new CardReadResult(FileStatus.NORMAL, 0, Outcome.OK, Optional.of(stored),
                            Optional.of(storedImage(stored)), null))
                    .withMessageContaining("empty observation");
            assertThatExceptionOfType(NullPointerException.class).isThrownBy(
                    () -> new CardWriteResult(FileStatus.NORMAL, 0, null, Optional.empty()));
            assertThatExceptionOfType(NullPointerException.class).isThrownBy(
                    () -> new CardWriteResult(FileStatus.NORMAL, 0, Outcome.OK, null))
                    .withMessageContaining("empty observation");
        }

        @Test
        @DisplayName("requireRecord refuses to invent one on an arm that returns none")
        void requireRecordOnAnArmWithoutOne() {
            assertThat(cardRead(cardRecord(FIRST_FIXTURE_CARD_NUM)).requireRecord())
                    .isNotNull();
            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> CardReadResult.notFound().requireRecord());
            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> CardReadResult.endOfFile().requireRecord());
        }

        @Test
        @DisplayName("the predicates agree with the response they are derived from")
        void predicatesAgreeWithTheResponse() {
            CardRecord stored = cardRecord(FIRST_FIXTURE_CARD_NUM);
            CardReadResult normal = cardRead(stored);
            CardReadResult duplicate = cardReadDuplicate(stored);
            CardReadResult notFound = CardReadResult.notFound();
            CardReadResult endOfFile = CardReadResult.endOfFile();
            CardReadResult failed =
                    CardReadResult.failed(FileStatus.LENGERR, DatasetObservation.recordWidth(91));

            assertThat(normal.isNormal()).isTrue();
            assertThat(normal.isDuplicateKey()).isFalse();
            assertThat(normal.isNotFound()).isFalse();
            assertThat(normal.isEndOfFile()).isFalse();
            assertThat(normal.isFailure()).isFalse();
            assertThat(duplicate.isDuplicateKey()).isTrue();
            assertThat(duplicate.isNormal()).isFalse();
            assertThat(notFound.isNotFound()).isTrue();
            assertThat(notFound.isRecordReturned()).isFalse();
            assertThat(endOfFile.isEndOfFile()).isTrue();
            assertThat(failed.isFailure()).isTrue();
            assertThat(failed.isNormal()).isFalse();
            assertThat(failed.isEndOfFile()).isFalse();
            assertThat(failed.isDuplicateKey()).isFalse();
            assertThat(failed.isNotFound()).isFalse();
            assertThat(CardWriteResult.normal().isNormal()).isTrue();
            assertThat(CardWriteResult.normal().isFailure()).isFalse();
            assertThat(CardWriteResult.failed(FileStatus.INVREQ).isNormal()).isFalse();
            assertThat(CardWriteResult.failed(FileStatus.INVREQ).isFailure()).isTrue();
        }
    }

    @Nested
    @DisplayName("the record invariants the rewrite relies on rather than re-checking")
    class ReliedUponRecordInvariantsTest {
        @Test
        @DisplayName("the layout sums to the declared width, with the reserved span accounted for")
        void theLayoutSumsToTheDeclaredWidth() {
            assertThat(CardRecord.LAYOUT.recordLength()).isEqualTo(CardRepository.RECORD_LENGTH);
            assertThat(CardRecord.FILLER_OFFSET + CardRecord.FILLER_LENGTH)
                    .isEqualTo(CardRepository.RECORD_LENGTH);
            assertThat(CardRecord.CARD_NUM_LENGTH + CardRecord.CARD_ACCT_ID_LENGTH
                    + CardRecord.CARD_CVV_CD_LENGTH + CardRecord.CARD_EMBOSSED_NAME_LENGTH
                    + CardRecord.CARD_EXPIRAION_DATE_LENGTH + CardRecord.CARD_ACTIVE_STATUS_LENGTH
                    + CardRecord.FILLER_LENGTH).isEqualTo(CardRepository.RECORD_LENGTH);
        }

        @Test
        @DisplayName("every character component is held at exactly its declared width")
        void everyCharacterComponentIsHeldAtItsDeclaredWidth() {
            CardRecord shortValues = new CardRecord("1", 1L, 1, "A", "B", "C");

            assertThat(shortValues.cardNum()).hasSize(CardRecord.CARD_NUM_LENGTH);
            assertThat(shortValues.cardEmbossedName()).hasSize(CardRecord.CARD_EMBOSSED_NAME_LENGTH);
            assertThat(shortValues.cardExpiraionDate())
                    .hasSize(CardRecord.CARD_EXPIRAION_DATE_LENGTH);
            assertThat(shortValues.cardActiveStatus())
                    .hasSize(CardRecord.CARD_ACTIVE_STATUS_LENGTH);
            assertThat(shortValues.encode(codec)).hasSize(CardRepository.RECORD_LENGTH);
            assertThat(CardRecord.initialised().encode(codec))
                    .hasSize(CardRepository.RECORD_LENGTH);
        }

        @Test
        @DisplayName("a value wider than its span cannot become a record, so it cannot be written")
        void anOverWideValueCannotBecomeARecord() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new CardRecord("0".repeat(CardRecord.CARD_NUM_LENGTH + 1), 1L, 1,
                            "A", "B", "C"));
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new CardRecord("1", 1L, 1,
                            "A".repeat(CardRecord.CARD_EMBOSSED_NAME_LENGTH + 1), "B", "C"));
        }

        @Test
        @DisplayName("an unsigned span cannot hold a negative value or one too wide for its digits")
        void anUnsignedSpanRejectsWhatItCannotHold() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new CardRecord("1", -1L, 1, "A", "B", "C"));
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new CardRecord("1",
                            CardRecord.CARD_ACCT_ID_EXCLUSIVE_LIMIT, 1, "A", "B", "C"));
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new CardRecord("1", 1L, -1, "A", "B", "C"));
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new CardRecord("1", 1L,
                            CardRecord.CARD_CVV_CD_EXCLUSIVE_LIMIT, "A", "B", "C"));
        }

        @Test
        @DisplayName("the key spans this repository binds are the ones the copybook declares")
        void theKeySpansAreTheCopybooksOwn() {
            assertThat(CardRecord.cardDatPrimaryKeySpan().name()).isEqualTo("CARD-NUM");
            assertThat(CardRecord.cardDatPrimaryKeySpan().offset()).isZero();
            assertThat(CardRecord.cardDatPrimaryKeySpan().length())
                    .isEqualTo(CardRecord.CARD_NUM_LENGTH);
            assertThat(CardRecord.cardAixAlternateKeySpan().name()).isEqualTo("CARD-ACCT-ID");
            assertThat(CardRecord.cardAixAlternateKeySpan().offset())
                    .as("the sibling cross-reference record carries an account id at a different "
                            + "offset, and confusing the two reads the wrong records")
                    .isEqualTo(CardRecord.CARD_NUM_LENGTH);
            assertThat(CardRecord.cardAixAlternateKeySpan().length())
                    .isEqualTo(CardRecord.CARD_ACCT_ID_LENGTH);
        }
    }

    @Nested
    @DisplayName("round trip against the shipped copy of app/data/ASCII/carddata.txt")
    class RealFixtureRoundTripTest {
        @Test
        @DisplayName("every one of the fifty rows decodes and re-encodes to the identical 150 bytes")
        void roundTripsEveryFixtureRow() {
            List<String> rows = fixtureRows();

            assertThat(rows).hasSize(FIXTURE_RECORDS);
            for (String row : rows) {
                assertThat(row).as("every fixture row is exactly one card record wide")
                        .hasSize(CardRecord.RECORD_LENGTH);

                byte[] stored = row.getBytes(FIXTURE_CHARSET);
                CardRecord decoded = CardRecord.decode(stored, codec);

                assertThat(decoded.encode(codec)).as("re-encoding row %s is byte-identical",
                        decoded.cardNum()).isEqualTo(stored);
                assertThat(decoded.cardNum()).hasSize(CardRecord.CARD_NUM_LENGTH);
                assertThat(new String(stored, CardRecord.FILLER_OFFSET, CardRecord.FILLER_LENGTH,
                        FIXTURE_CHARSET))
                        .as("gate G21: the reserved span is 59 spaces")
                        .isEqualTo(" ".repeat(CardRecord.FILLER_LENGTH));
            }
        }

        @Test
        @DisplayName("a fixture row read through the repository yields the record it encodes")
        void readsAFixtureRowThroughTheRepository() {
            String row = fixtureRows().get(0);
            stubFetch(new FetchedRows(row.getBytes(FIXTURE_CHARSET), 1));

            CardReadResult result = repository.readByCardNumber(FIRST_FIXTURE_CARD_NUM);

            assertThat(result.isNormal()).isTrue();
            CardRecord card = result.requireRecord();
            assertThat(card.cardNum()).as("the leading zero survives, because CARD-NUM is PIC X(16)")
                    .isEqualTo(FIRST_FIXTURE_CARD_NUM);
            assertThat(card.cardAcctId()).isEqualTo(FIRST_FIXTURE_ACCT_ID);
            assertThat(card.cardCvvCd()).isEqualTo(FIRST_FIXTURE_CVV_CD);
            assertThat(card.cardEmbossedName())
                    .as("padding is data: the name is never trimmed on read")
                    .isEqualTo(FIRST_FIXTURE_EMBOSSED_NAME + " ".repeat(41))
                    .hasSize(CardRecord.CARD_EMBOSSED_NAME_LENGTH);
            assertThat(card.cardExpiraionDate()).isEqualTo(FIRST_FIXTURE_EXPIRAION_DATE)
                    .hasSize(CardRecord.CARD_EXPIRAION_DATE_LENGTH);
            assertThat(card.cardActiveStatus()).isEqualTo(FIRST_FIXTURE_ACTIVE_STATUS)
                    .hasSize(CardRecord.CARD_ACTIVE_STATUS_LENGTH);
        }

        @Test
        @DisplayName("all seven CVACT02Y spans land at the offsets and widths the copybook declares")
        void everySpanLandsWhereTheCopybookPutsIt() {
            String row = fixtureRows().get(0);
            stubFetch(new FetchedRows(row.getBytes(FIXTURE_CHARSET), 1));

            CardRecord card = repository.readByCardNumber(FIRST_FIXTURE_CARD_NUM).requireRecord();

            assertThat(span(row, CardRecord.CARD_NUM_OFFSET, CardRecord.CARD_NUM_LENGTH))
                    .isEqualTo(card.cardNum()).isEqualTo(FIRST_FIXTURE_CARD_NUM).hasSize(16);
            assertThat(span(row, CardRecord.CARD_ACCT_ID_OFFSET, CardRecord.CARD_ACCT_ID_LENGTH))
                    .as("the eleven digits are stored zero-filled on the LEFT")
                    .isEqualTo("00000000050").hasSize(11);
            assertThat(Long.parseLong(span(row, CardRecord.CARD_ACCT_ID_OFFSET,
                    CardRecord.CARD_ACCT_ID_LENGTH))).isEqualTo(card.cardAcctId());
            assertThat(span(row, CardRecord.CARD_CVV_CD_OFFSET, CardRecord.CARD_CVV_CD_LENGTH))
                    .isEqualTo("747").hasSize(3);
            assertThat(Integer.parseInt(span(row, CardRecord.CARD_CVV_CD_OFFSET,
                    CardRecord.CARD_CVV_CD_LENGTH))).isEqualTo(card.cardCvvCd());
            assertThat(span(row, CardRecord.CARD_EMBOSSED_NAME_OFFSET,
                    CardRecord.CARD_EMBOSSED_NAME_LENGTH))
                    .isEqualTo(card.cardEmbossedName())
                    .startsWith(FIRST_FIXTURE_EMBOSSED_NAME).hasSize(50);
            assertThat(span(row, CardRecord.CARD_EXPIRAION_DATE_OFFSET,
                    CardRecord.CARD_EXPIRAION_DATE_LENGTH))
                    .isEqualTo(card.cardExpiraionDate()).isEqualTo(FIRST_FIXTURE_EXPIRAION_DATE)
                    .hasSize(10);
            assertThat(span(row, CardRecord.CARD_ACTIVE_STATUS_OFFSET,
                    CardRecord.CARD_ACTIVE_STATUS_LENGTH))
                    .isEqualTo(card.cardActiveStatus()).isEqualTo(FIRST_FIXTURE_ACTIVE_STATUS)
                    .hasSize(1);
            assertThat(span(row, CardRecord.FILLER_OFFSET, CardRecord.FILLER_LENGTH))
                    .as("gate G21: FILLER is a span of the record, not padding to be skipped")
                    .isEqualTo(" ".repeat(59)).hasSize(59);
            assertThat(CardRecord.FILLER_OFFSET + CardRecord.FILLER_LENGTH)
                    .as("gate G19: the seven spans sum to the declared record width")
                    .isEqualTo(CardRecord.RECORD_LENGTH);
        }

        @Test
        @DisplayName("the code page is named at every decode, and naming the wrong one changes the bytes")
        void theCodePageIsAlwaysNamedExplicitly() {
            String row = fixtureRows().get(0);
            byte[] stored = row.getBytes(FIXTURE_CHARSET);

            CardRecord underTheConfiguredCodePage = CardRecord.decode(stored,
                    new FixedWidthCodec(FIXTURE_CHARSET));

            assertThat(underTheConfiguredCodePage.cardNum()).isEqualTo(FIRST_FIXTURE_CARD_NUM);
            assertThat(underTheConfiguredCodePage.cardEmbossedName())
                    .startsWith(FIRST_FIXTURE_EMBOSSED_NAME);
            assertThat(new FixedWidthCodec(FIXTURE_CHARSET).charset())
                    .as("the codec carries the code page rather than looking one up")
                    .isEqualTo(FIXTURE_CHARSET);

            assertThat(new String(stored, CardRecord.CARD_EMBOSSED_NAME_OFFSET,
                    CardRecord.CARD_EMBOSSED_NAME_LENGTH, EBCDIC_CHARSET))
                    .as("US-ASCII and IBM037 disagree about these bytes, which is why the code page is "
                            + "always stated")
                    .isNotEqualTo(underTheConfiguredCodePage.cardEmbossedName());
            assertThat(EBCDIC_CHARSET.name()).isEqualTo("IBM037");
            assertThat(FIXTURE_CHARSET.name()).isEqualTo("US-ASCII");
        }

        private String span(String row, int offset, int length) {
            return row.substring(offset, offset + length);
        }
    }

    @Nested
    @DisplayName("gate G47: every FileStatus outcome, per call site")
    class FileStatusVocabularyPerCallSiteTest {
        @Test
        @DisplayName("the base keyed read: '00' and '23' are reachable; '10' and '22' are not, and why")
        void baseKeyedReadVocabulary() {
            stubFetch(oneRow(cardRecord(FIRST_FIXTURE_CARD_NUM)));
            assertThat(repository.readByCardNumber(FIRST_FIXTURE_CARD_NUM).batchStatus())
                    .contains(FileStatus.OK);

            clearInvocations(jdbcTemplate);
            stubDescribe(repository);
            stubFetch(FetchedRows.empty());
            CardReadResult missing = repository.readByCardNumber(FIRST_FIXTURE_CARD_NUM);
            assertThat(missing.batchStatus()).contains(FileStatus.NOT_FOUND);
            assertThat(missing.resp()).isEqualTo(FileStatus.NOTFND);

            assertThat(missing.isEndOfFile()).isFalse();
            assertThat(missing.isDuplicateKey()).isFalse();
        }

        @Test
        @DisplayName("the alternate-index read: '00', '22' and '23' are all reachable")
        void alternateIndexVocabulary() {
            CardRecord stored = cardRecord(FIRST_FIXTURE_CARD_NUM);
            stubFetch(new FetchedRows(stored.encode(codec), 2));
            CardReadResult shared = repository.readByAccountIdViaAltIndex(FIRST_FIXTURE_ACCT_ID);
            assertThat(shared.batchStatus())
                    .as("an alternate key need not be unique, so '22' is reachable only here")
                    .contains(FileStatus.DUPLICATE);
            assertThat(shared.isRecordReturned()).as("and it is not an error: the record is there")
                    .isTrue();

            clearInvocations(jdbcTemplate);
            stubDescribe(repository);
            stubFetch(new FetchedRows(stored.encode(codec), 1));
            assertThat(repository.readByAccountIdViaAltIndex(FIRST_FIXTURE_ACCT_ID).batchStatus())
                    .contains(FileStatus.OK);

            clearInvocations(jdbcTemplate);
            stubDescribe(repository);
            stubFetch(FetchedRows.empty());
            assertThat(repository.readByAccountIdViaAltIndex(FIRST_FIXTURE_ACCT_ID).batchStatus())
                    .contains(FileStatus.NOT_FOUND);
        }

        @Test
        @DisplayName("the browse: '00' and '10' are reachable, and '10' is where a paging loop ends")
        void browseVocabulary() {
            stubFetch(oneRow(cardRecord(FIRST_FIXTURE_CARD_NUM)));
            assertThat(repository.startBrowse(FIRST_FIXTURE_CARD_NUM, BrowseDirection.FORWARD)
                    .readNext().batchStatus()).contains(FileStatus.OK);

            clearInvocations(jdbcTemplate);
            stubDescribe(repository);
            stubFetch(FetchedRows.empty());
            CardReadResult ended = repository.startBrowse(FIRST_FIXTURE_CARD_NUM,
                    BrowseDirection.FORWARD).readNext();
            assertThat(ended.batchStatus())
                    .as("COCRDLIC's paging loop terminates on this and nothing else")
                    .contains(FileStatus.END_OF_FILE);
            assertThat(ended.resp()).isEqualTo(FileStatus.ENDFILE);
            assertThat(ended.isNotFound())
                    .as("the end of a pass is not a missing record, and conflating them would make the "
                            + "list report a lookup failure at the bottom of every file")
                    .isFalse();
        }

        @Test
        @DisplayName("the rewrite: only a normal response is a success, and no batch status is invented")
        void rewriteVocabulary() {
            stubFetch(oneRow(cardRecord(FIRST_FIXTURE_CARD_NUM)));
            when(jdbcTemplate.update(anyString(), any(PreparedStatementSetter.class))).thenReturn(1);
            CardWriteResult written = inUnitOfWork(
                    () -> repository.rewrite(cardRecord(FIRST_FIXTURE_CARD_NUM)));
            assertThat(written.isNormal()).isTrue();
            assertThat(written.batchStatus()).contains(FileStatus.OK);

            clearInvocations(jdbcTemplate);
            stubDescribe(repository);
            stubFetch(FetchedRows.empty());
            CardWriteResult refused = inUnitOfWork(
                    () -> repository.rewrite(cardRecord(FIRST_FIXTURE_CARD_NUM)));
            assertThat(refused.isFailure()).isTrue();
            assertThat(refused.resp()).isEqualTo(FileStatus.INVREQ);
            assertThat(refused.batchStatus()).isEmpty();
        }

        @Test
        @DisplayName("all four batch statuses are the COBOL's own two-character codes, unchanged")
        void theBatchStatusCodesAreTheCobolsOwn() {
            assertThat(FileStatus.OK).isEqualTo("00");
            assertThat(FileStatus.END_OF_FILE).isEqualTo("10");
            assertThat(FileStatus.DUPLICATE).isEqualTo("22");
            assertThat(FileStatus.NOT_FOUND).isEqualTo("23");
            assertThat(FileStatus.batchStatusOfCicsResp(FileStatus.NORMAL)).contains(FileStatus.OK);
            assertThat(FileStatus.batchStatusOfCicsResp(FileStatus.ENDFILE))
                    .contains(FileStatus.END_OF_FILE);
            assertThat(FileStatus.batchStatusOfCicsResp(FileStatus.DUPKEY))
                    .contains(FileStatus.DUPLICATE);
            assertThat(FileStatus.batchStatusOfCicsResp(FileStatus.NOTFND))
                    .contains(FileStatus.NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("gate G44: no schema footprint")
    class NoSchemaFootprintTest {
        @Test
        @DisplayName("no statement this repository composes is DDL, and none creates or alters anything")
        void noStatementIsDdl() {
            CardRepository.BaseStatements sql = statementsOf(repository);
            CardRepository.AlternateStatements pathSql = alternateStatementsOf(repository);

            assertThat(List.of(sql.selectByCardNumber(), sql.selectForUpdateByCardNumber(),
                    pathSql.selectByAccountId(), sql.rewrite(), sql.browseAnchor(), sql.browseForward(),
                    sql.browseBackward()))
                    .allSatisfy(statement -> assertThat(statement.toUpperCase(Locale.ROOT))
                            .doesNotContain("CREATE ").doesNotContain("ALTER ")
                            .doesNotContain("DROP ").doesNotContain("TRUNCATE ")
                            .doesNotContain("INSERT ").doesNotContain("DELETE ")
                            .doesNotContain("GRANT "))
                    .allSatisfy(statement -> assertThat(statement.startsWith("SELECT ")
                            || statement.startsWith("UPDATE "))
                            .as("the operations the card programs perform are reads, a rewrite and a "
                                    + "browse - and nothing else, because no card program adds or "
                                    + "deletes a card (AAP §0.3.5): [%s]", statement)
                            .isTrue());
        }

        @Test
        @DisplayName("no statement names a version, timestamp or optimistic-lock column")
        void noVersionColumnIsIntroduced() {
            CardRepository.BaseStatements sql = statementsOf(repository);
            CardRepository.AlternateStatements pathSql = alternateStatementsOf(repository);

            assertThat(List.of(sql.selectByCardNumber(), sql.selectForUpdateByCardNumber(),
                    pathSql.selectByAccountId(), sql.rewrite(), sql.browseAnchor(), sql.browseForward(),
                    sql.browseBackward()))
                    .allSatisfy(statement -> assertThat(statement.toUpperCase(Locale.ROOT))
                            .doesNotContain("VERSION").doesNotContain("OPTLOCK")
                            .doesNotContain("ROWVERSION").doesNotContain("LAST_UPDATED")
                            .doesNotContain("UPDATED_AT"));
        }

        @Test
        @DisplayName("no execute() is ever issued, so no statement escapes the composed set")
        void noStatementIsIssuedOutsideTheComposedSet() {
            stubFetch(FetchedRows.empty());
            when(jdbcTemplate.update(anyString(), any(PreparedStatementSetter.class))).thenReturn(1);

            repository.readByCardNumber(FIRST_FIXTURE_CARD_NUM);
            repository.readByAccountIdViaAltIndex(FIRST_FIXTURE_ACCT_ID);
            repository.startBrowse(FIRST_FIXTURE_CARD_NUM, BrowseDirection.FORWARD).readNext();
            inUnitOfWork(() -> repository.rewrite(cardRecord(FIRST_FIXTURE_CARD_NUM)));

            verify(jdbcTemplate, never()).execute(anyString());
        }

        @Test
        @DisplayName("neither the repository nor the record carries a persistence or ORM annotation")
        void noEntityAnnotationIsPresent() {
            List<String> annotations = new ArrayList<>();
            for (Class<?> type : List.of(CardRepository.class, CardRecord.class,
                    CardReadResult.class, CardWriteResult.class, CardBrowse.class)) {
                for (Annotation annotation : type.getAnnotations()) {
                    annotations.add(annotation.annotationType().getName());
                }
            }

            assertThat(annotations).isNotEmpty()
                    .as("@Repository is a stereotype, not a mapping")
                    .contains("org.springframework.stereotype.Repository")
                    .allSatisfy(name -> assertThat(name)
                            .doesNotStartWith("jakarta.persistence.")
                            .doesNotStartWith("javax.persistence.")
                            .doesNotStartWith("org.hibernate."));
        }
    }

    @Nested
    @DisplayName("addressing - the caller's own DD binding is the one read")
    class TheBatchDdView {
        @Test
        @DisplayName("the batch DD name is the one app/jcl/READCARD.jcl:25-26 binds")
        void theBatchDdNameIsTheJcls() {
            assertThat(CardRepository.BATCH_DD_NAME).isEqualTo("CARDFILE");
            assertThat(CardRepository.BATCH_DD_NAME).isNotEqualTo(CardRepository.BASE_DD_NAME);
        }

        @Test
        @DisplayName("a binding naming the dataset already addressed hands back the same instance")
        void anIdenticalBindingIsIdentity() {
            CardRepository same = repository.addressing(
                    cardDatBinding("CARDDEMO.CARDDATA.KSDS", CardRecord.RECORD_LENGTH),
                    CardRepository.BATCH_DD_NAME);

            assertThat(same).isSameAs(repository);
        }

        @Test
        @DisplayName("a binding naming a different dataset yields an instance addressing that dataset")
        void aDifferentBindingRebases() {
            CardRepository rebound = repository.addressing(
                    cardDatBinding("CARDDEMO.BATCH.CARDDATA.KSDS", CardRecord.RECORD_LENGTH),
                    CardRepository.BATCH_DD_NAME);

            assertThat(rebound).isNotSameAs(repository);
            assertThat(rebound.baseDatasetName()).isEqualTo("CARDDEMO.BATCH.CARDDATA.KSDS");
            assertThat(repository.baseDatasetName()).isEqualTo("CARDDEMO.CARDDATA.KSDS");
            assertThat(rebound.describeBaseStatement())
                    .contains("CARDDEMO.BATCH.CARDDATA.KSDS")
                    .doesNotContain("CARDDEMO.CARDDATA.KSDS");
        }

        @Test
        @DisplayName("the alternate-index path is unchanged, because a DD selects a dataset and nothing "
                + "else")
        void theAlternateIndexIsCarriedOver() {
            CardRepository rebound = repository.addressing(
                    cardDatBinding("CARDDEMO.BATCH.CARDDATA.KSDS", CardRecord.RECORD_LENGTH),
                    CardRepository.BATCH_DD_NAME);

            assertThat(rebound.alternateIndexDatasetName())
                    .isEqualTo(repository.alternateIndexDatasetName());
        }

        @Test
        @DisplayName("a binding of the wrong record width is refused, naming the key to correct")
        void aWrongWidthIsRefused() {
            DatasetBinding wrong = cardDatBinding("CARDDEMO.BATCH.CARDDATA.KSDS",
                    CardRecord.RECORD_LENGTH + 1);

            assertThatIllegalStateException()
                    .isThrownBy(() -> repository.addressing(wrong, CardRepository.BATCH_DD_NAME))
                    .withMessageContaining(CardRepository.BATCH_DD_NAME)
                    .withMessageContaining("record-length")
                    .withMessageContaining(String.valueOf(CardRecord.RECORD_LENGTH));
        }

        @Test
        @DisplayName("a binding declaring no dataset name is refused")
        void aBlankDatasetNameIsRefused() {
            DatasetBinding blank = cardDatBinding("   ", CardRecord.RECORD_LENGTH);

            assertThatIllegalStateException()
                    .isThrownBy(() -> repository.addressing(blank, CardRepository.BATCH_DD_NAME))
                    .withMessageContaining(CardRepository.BATCH_DD_NAME);
        }

        @Test
        @DisplayName("neither argument may be null, and the DD name is checked first so it can be named")
        void neitherArgumentMayBeNull() {
            assertThatNullPointerException()
                    .isThrownBy(() -> repository.addressing(cardDatBinding(), null))
                    .withMessageContaining("DD name");
            assertThatNullPointerException()
                    .isThrownBy(() -> repository.addressing(null, CardRepository.BATCH_DD_NAME))
                    .withMessageContaining(CardRepository.BATCH_DD_NAME);
        }

        @Test
        @DisplayName("a re-bound instance builds keys through the same codec, so it moves identically")
        void theCodecIsShared() {
            CardRepository rebound = repository.addressing(
                    cardDatBinding("CARDDEMO.BATCH.CARDDATA.KSDS", CardRecord.RECORD_LENGTH),
                    CardRepository.BATCH_DD_NAME);

            assertThat(rebound.describeAlternateIndexStatement())
                    .isEqualTo(repository.describeAlternateIndexStatement());
        }
    }

    private static CardReadResult cardRead(CardRecord record) {
        return CardReadResult.normal(record, record.encodeToImage(StandardCharsets.US_ASCII));
    }

    private static CardReadResult cardReadDuplicate(CardRecord record) {
        return CardReadResult.duplicateKey(record, record.encodeToImage(StandardCharsets.US_ASCII));
    }

    private static String storedImage(CardRecord record) {
        return record.encodeToImage(StandardCharsets.US_ASCII);
    }
}
