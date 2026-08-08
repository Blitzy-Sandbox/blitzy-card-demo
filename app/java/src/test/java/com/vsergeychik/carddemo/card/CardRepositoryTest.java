package com.vsergeychik.carddemo.card;

import com.vsergeychik.carddemo.card.CardRepository.BrowseDirection;
import com.vsergeychik.carddemo.card.CardRepository.CardBrowse;
import com.vsergeychik.carddemo.card.CardRepository.CardReadResult;
import com.vsergeychik.carddemo.card.CardRepository.CardWriteResult;
import com.vsergeychik.carddemo.card.CardRepository.FetchedRows;
import com.vsergeychik.carddemo.card.model.CardRecord;
import com.vsergeychik.carddemo.common.FileStatus;
import com.vsergeychik.carddemo.common.FileStatus.Outcome;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.config.CobolCharsetConfig;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBinding;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBindings;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link CardRepository}, the data-access component for the {@code CARDDAT} base
 * cluster and its {@code CARDAIX} alternate-index path.
 *
 * <p>Every test runs against a stubbed template with <strong>no backend of any kind</strong> - no HTTP,
 * no job launcher, no embedded database. That is not a convenience: production connectivity cannot be
 * exercised in this environment at all (residual risk R-E), so every arm of every guard chain has to be
 * reachable without one, and these tests are the proof that it is.
 *
 * <p>The exception is {@link RealFixtureRoundTripTest}, which reads the real fifty-record fixture from
 * {@code app/data/ASCII/carddata.txt} - read-only, as a reference input - to prove that what this
 * repository decodes from a row and what it would send back are the same bytes.
 */
@DisplayName("CardRepository - CARDDAT base cluster and CARDAIX alternate-index path")
class CardRepositoryTest {

    /** The card number of the first fixture row. It carries a leading zero, which is the point. */
    private static final String FIRST_FIXTURE_CARD_NUM = "0500024453765740";

    /** The second fixture row's card number, which sorts one step above the first. */
    private static final String SECOND_FIXTURE_CARD_NUM = "0683586198171516";

    /** The account id the first fixture row cross-references, measured from the fixture. */
    private static final long FIRST_FIXTURE_ACCT_ID = 50L;

    /** The card verification code the first fixture row carries, measured from the fixture. */
    private static final int FIRST_FIXTURE_CVV_CD = 747;

    /** The expiry the first fixture row carries, measured from the fixture. */
    private static final String FIRST_FIXTURE_EXPIRAION_DATE = "2023-03-09";

    /**
     * An account id whose digits exercise the full eleven-digit width, for the key-shape tests. It is
     * deliberately not a fixture value: what is under test there is the MOVE, not the data.
     */
    private static final long WIDE_ACCT_ID = 10_000_000_010L;

    /** A vendor error code, to prove a real reason code is recovered rather than discarded. */
    private static final int VENDOR_ERROR_CODE = 42_101;

    /** The stubbed template. The only route to a backend, and it never reaches one. */
    private JdbcTemplate jdbcTemplate;

    /** The codec under test conditions: the fixtures are text, so the code page is theirs. */
    private FixedWidthCodec codec;

    /** The repository under test, wired through the canonical constructor. */
    private CardRepository repository;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        codec = new FixedWidthCodec(StandardCharsets.US_ASCII);
        repository = new CardRepository(jdbcTemplate, bindings(), codec);
    }

    // =============================================================================================
    // Fixtures and helpers.
    // =============================================================================================

    /**
     * A binding catalogue declaring both card keys exactly as {@code application.yml} declares them.
     *
     * @return the catalogue
     */
    private static DatasetBindings bindings() {
        return bindings(cardDatBinding(), cardAixBinding());
    }

    /**
     * A binding catalogue built from the two card bindings supplied.
     *
     * @param base           the {@code CARDDAT} binding, or {@code null} to omit the key entirely
     * @param alternateIndex the {@code CARDAIX} binding, or {@code null} to omit the key entirely
     * @return the catalogue
     */
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

    /**
     * The {@code CARDDAT} binding as {@code application.yml} declares it.
     *
     * @return the binding
     */
    private static DatasetBinding cardDatBinding() {
        return cardDatBinding("CARDDEMO.CARDDATA.KSDS", CardRecord.RECORD_LENGTH);
    }

    /**
     * A {@code CARDDAT} binding with the dataset name and record width supplied.
     *
     * @param dsname       the dataset name
     * @param recordLength the declared record width
     * @return the binding
     */
    private static DatasetBinding cardDatBinding(String dsname, int recordLength) {
        return new DatasetBinding(dsname, "ksds", false, "FB", null, recordLength, "CVACT02Y", null,
                null, null);
    }

    /**
     * The {@code CARDAIX} binding as {@code application.yml} declares it: a path over
     * {@code CARDDAT}, keyed on the account id.
     *
     * @return the binding
     */
    private static DatasetBinding cardAixBinding() {
        return cardAixBinding(CardRepository.BASE_DD_NAME, "CARD-ACCT-ID", CardRecord.RECORD_LENGTH);
    }

    /**
     * A {@code CARDAIX} binding with the base, alternate key and record width supplied.
     *
     * @param base         the base it claims to index, or {@code null} to declare none
     * @param alternateKey the alternate key it claims, or {@code null} to declare none
     * @param recordLength the declared record width
     * @return the binding
     */
    private static DatasetBinding cardAixBinding(String base, String alternateKey, int recordLength) {
        return new DatasetBinding("CARDDEMO.CARDDATA.AIX.PATH", "aix-path", false, "FB", null,
                recordLength, "CVACT02Y", null, base, alternateKey);
    }

    /**
     * A card record whose components are all inside their declared widths.
     *
     * @param cardNum the card number
     * @return the record
     */
    private static CardRecord cardRecord(String cardNum) {
        return new CardRecord(cardNum, FIRST_FIXTURE_ACCT_ID, 123, "Aniya Von", "2022-01-01", "Y");
    }

    /**
     * Stubs the template to hand back what a read found.
     *
     * @param rows what the read found
     */
    private void stubFetch(FetchedRows rows) {
        when(jdbcTemplate.query(any(PreparedStatementCreator.class),
                CardRepositoryTest.<FetchedRows>anyExtractor())).thenReturn(rows);
    }

    /**
     * Stubs the template to reject a read.
     *
     * @param rejection the failure to raise
     */
    private void stubRejection(DataAccessException rejection) {
        when(jdbcTemplate.query(any(PreparedStatementCreator.class),
                CardRepositoryTest.<FetchedRows>anyExtractor())).thenThrow(rejection);
    }

    /**
     * A matcher for the extractor argument, isolating the generic cast to one place.
     *
     * @param <T> the extracted type
     * @return the matcher
     */
    @SuppressWarnings("unchecked")
    private static <T> ResultSetExtractor<T> anyExtractor() {
        return any(ResultSetExtractor.class);
    }

    /**
     * What a read found: one record image, as a card record encoded with the test code page.
     *
     * @param record the record the row carries
     * @return the found rows
     */
    private FetchedRows oneRow(CardRecord record) {
        return new FetchedRows(record.encode(codec), 1);
    }

    /**
     * Captures the statement creator the repository sent, and drives it against a stubbed connection.
     *
     * @return the statement the creator prepared
     * @throws SQLException never; declared because the JDBC API declares it
     */
    private PreparedStatement capturePreparedStatement() throws SQLException {
        ArgumentCaptor<PreparedStatementCreator> captor =
                ArgumentCaptor.forClass(PreparedStatementCreator.class);
        verify(jdbcTemplate).query(captor.capture(), CardRepositoryTest.<FetchedRows>anyExtractor());
        Connection connection = mock(Connection.class);
        PreparedStatement prepared = mock(PreparedStatement.class);
        when(connection.prepareStatement(anyString())).thenReturn(prepared);
        assertThat(captor.getValue().createPreparedStatement(connection)).isSameAs(prepared);
        return prepared;
    }

    /**
     * The alphanumeric {@code MOVE} cases for {@code CARD-NUM PIC X(16)}, expressed in Java so that no
     * data-format rule can silently eat the padding that is the whole point of the test.
     *
     * <p>A COBOL alphanumeric {@code MOVE} fills the receiver from the left: a short value is padded on
     * the right with spaces, and a long one loses its <em>trailing</em> characters. Every expected key
     * below is exactly sixteen characters, because the receiver is.
     *
     * @return sending value and the key it must produce
     */
    static List<Arguments> alphanumericMoveCases() {
        return List.of(
                // Already the declared width: unchanged.
                Arguments.of(FIRST_FIXTURE_CARD_NUM, FIRST_FIXTURE_CARD_NUM),
                Arguments.of("12345" + " ".repeat(11), "12345" + " ".repeat(11)),
                // Short: padded on the right, which is what makes these two produce the same key.
                Arguments.of("12345", "12345" + " ".repeat(11)),
                Arguments.of("", " ".repeat(CardRecord.CARD_NUM_LENGTH)),
                // Long: the LEADING sixteen characters survive. Keeping the trailing ones instead is
                // the numeric rule, and applying it here would read an entirely different record.
                Arguments.of("01234567890123456789", "0123456789012345"));
    }

    // =============================================================================================
    // Construction: configuration resolution, and the gate G45 invariant enforced in code.
    // =============================================================================================

    @Nested
    @DisplayName("construction")
    class ConstructionTest {

        @Test
        @DisplayName("composes every statement from the configured dataset names and nothing else")
        void composesStatementsFromConfiguration() {
            assertThat(repository.baseDatasetName()).isEqualTo("CARDDEMO.CARDDATA.KSDS");
            assertThat(repository.alternateIndexDatasetName())
                    .isEqualTo("CARDDEMO.CARDDATA.AIX.PATH");

            assertThat(repository.selectByCardNumberStatement())
                    .isEqualTo("SELECT * FROM \"CARDDEMO.CARDDATA.KSDS\" WHERE \"CARD-NUM\" = ?");
            assertThat(repository.selectForUpdateByCardNumberStatement())
                    .isEqualTo(repository.selectByCardNumberStatement() + " FOR UPDATE");
            assertThat(repository.selectByAccountIdStatement())
                    .isEqualTo("SELECT * FROM \"CARDDEMO.CARDDATA.AIX.PATH\" "
                            + "WHERE \"CARD-ACCT-ID\" = ? ORDER BY \"CARD-NUM\" ASC");
            assertThat(repository.browseAnchorStatement())
                    .isEqualTo("SELECT * FROM \"CARDDEMO.CARDDATA.KSDS\" "
                            + "WHERE \"CARD-NUM\" >= ? ORDER BY \"CARD-NUM\" ASC");
            assertThat(repository.browseForwardStatement())
                    .isEqualTo("SELECT * FROM \"CARDDEMO.CARDDATA.KSDS\" "
                            + "WHERE \"CARD-NUM\" > ? ORDER BY \"CARD-NUM\" ASC");
            assertThat(repository.browseBackwardStatement())
                    .isEqualTo("SELECT * FROM \"CARDDEMO.CARDDATA.KSDS\" "
                            + "WHERE \"CARD-NUM\" < ? ORDER BY \"CARD-NUM\" DESC");
            assertThat(repository.rewriteStatement())
                    .isEqualTo("UPDATE \"CARDDEMO.CARDDATA.KSDS\" SET \"CARD-RECORD\" = ? "
                            + "WHERE \"CARD-NUM\" = ?");
        }

        @Test
        @DisplayName("gate G46: no statement carries a dataset name this class chose")
        void everyStatementNamesOnlyTheConfiguredDataset() {
            CardRepository rebound = new CardRepository(jdbcTemplate,
                    bindings(cardDatBinding("SOMEWHERE.ELSE.ENTIRELY", CardRecord.RECORD_LENGTH),
                            cardAixBinding()),
                    codec);

            assertThat(rebound.selectByCardNumberStatement()).contains("SOMEWHERE.ELSE.ENTIRELY");
            assertThat(rebound.rewriteStatement()).contains("SOMEWHERE.ELSE.ENTIRELY");
            assertThat(rebound.selectByCardNumberStatement()).doesNotContain("CARDDATA.KSDS");
        }

        @Test
        @DisplayName("quotes a name that carries the delimiter itself, by repeating it")
        void quotesAnEmbeddedDelimiter() {
            CardRepository quoted = new CardRepository(jdbcTemplate,
                    bindings(cardDatBinding("ODD\"NAME", CardRecord.RECORD_LENGTH), cardAixBinding()),
                    codec);

            assertThat(quoted.selectByCardNumberStatement()).contains("\"ODD\"\"NAME\"");
        }

        @Test
        @DisplayName("the wiring constructor accepts a code page and builds the codec from it")
        void wiringConstructorTakesACodePage() {
            CardRepository wired =
                    new CardRepository(jdbcTemplate, bindings(), StandardCharsets.US_ASCII);

            assertThat(wired.baseDatasetName()).isEqualTo("CARDDEMO.CARDDATA.KSDS");
        }

        @Test
        @DisplayName("the qualifier names the charset bean the configuration actually declares")
        void charsetQualifierMatchesTheConfiguredBeanName() {
            // The one place the restated bean name could drift. It is asserted rather than trusted,
            // because this file's dependency whitelist keeps the main source from referencing it.
            assertThat(CardRepository.DATASET_CHARSET_BEAN_NAME)
                    .isEqualTo(CobolCharsetConfig.DATASET_CHARSET_BEAN_NAME);
        }

        @Test
        @DisplayName("refuses to be built without a template, a catalogue, a codec or a code page")
        void refusesMissingCollaborators() {
            DatasetBindings catalogue = bindings();
            assertThatExceptionOfType(NullPointerException.class).isThrownBy(
                    () -> new CardRepository(null, catalogue, codec));
            assertThatExceptionOfType(NullPointerException.class).isThrownBy(
                    () -> new CardRepository(jdbcTemplate, null, codec));
            assertThatExceptionOfType(NullPointerException.class).isThrownBy(
                    () -> new CardRepository(jdbcTemplate, catalogue, (FixedWidthCodec) null));
            assertThatExceptionOfType(NullPointerException.class).isThrownBy(
                    () -> new CardRepository(jdbcTemplate, catalogue, (Charset) null));
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
                    .isThrownBy(() -> new CardRepository(jdbcTemplate, partial, codec))
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
                    .isThrownBy(() -> new CardRepository(jdbcTemplate, wrongBase, codec))
                    .withMessageContaining(CardRepository.BASE_DD_NAME)
                    .withMessageContaining("CVACT02Y");
            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> new CardRepository(jdbcTemplate, wrongPath, codec))
                    .withMessageContaining(CardRepository.ALTERNATE_INDEX_DD_NAME);
        }

        @Test
        @DisplayName("gate G45: rejects a path that claims to index a different base")
        void rejectsAPathOverADifferentBase() {
            DatasetBindings foreignPath = bindings(cardDatBinding(),
                    cardAixBinding("CCXREF", "CARD-ACCT-ID", CardRecord.RECORD_LENGTH));

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> new CardRepository(jdbcTemplate, foreignPath, codec))
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
                    .isThrownBy(() -> new CardRepository(jdbcTemplate, wrongKey, codec))
                    .withMessageContaining("XREF-ACCT-ID")
                    .withMessageContaining("CARD-ACCT-ID");
        }

        @Test
        @DisplayName("accepts a path that states neither a base nor an alternate key")
        void acceptsASilentPathBinding() {
            DatasetBindings silent = bindings(cardDatBinding(),
                    cardAixBinding(null, null, CardRecord.RECORD_LENGTH));

            assertThatNoException()
                    .isThrownBy(() -> new CardRepository(jdbcTemplate, silent, codec));
        }

        @ParameterizedTest(name = "an unusable dataset name [{0}] is rejected")
        @CsvSource(value = { "NULL", "''", "'   '", "'BAD\tNAME'", "'BAD\nNAME'" }, nullValues = "NULL")
        @DisplayName("rejects a dataset name that cannot be composed into a statement")
        void rejectsAnUnusableDatasetName(String dsname) {
            DatasetBindings unusable =
                    bindings(cardDatBinding(dsname, CardRecord.RECORD_LENGTH), cardAixBinding());

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> new CardRepository(jdbcTemplate, unusable, codec))
                    .withMessageContaining("carddemo.datasets." + CardRepository.BASE_DD_NAME);
        }

        @Test
        @DisplayName("holds the CICS file-name literals at their declared eight-character width")
        void holdsTheCicsFileNameLiteralsAtDeclaredWidth() {
            // COCRDLIC:213-217, COCRDSLC:187-190 and COCRDUPC:251-254 all declare PIC X(8) with a
            // trailing space, and COCRDUPC's 80-character file-error message depends on the width.
            assertThat(CardRepository.BASE_CICS_FILE_NAME)
                    .isEqualTo("CARDDAT ")
                    .hasSize(CardRepository.CICS_FILE_NAME_LENGTH);
            assertThat(CardRepository.ALTERNATE_INDEX_CICS_FILE_NAME)
                    .isEqualTo("CARDAIX ")
                    .hasSize(CardRepository.CICS_FILE_NAME_LENGTH);
            assertThat(CardRepository.RECORD_LENGTH).isEqualTo(CardRecord.RECORD_LENGTH).isEqualTo(150);
            assertThat(CardRepository.RECORD_IMAGE_COLUMN_NAME).isEqualTo("CARD-RECORD");
        }
    }

    // =============================================================================================
    // 9100-GETCARD-BYACCTCARD - the three-armed EVALUATE, arm by arm.
    // =============================================================================================

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

        @ParameterizedTest(name = "a {0}-byte row is a length error, not a record")
        @ValueSource(ints = { 0, 91, 149, 151 })
        @DisplayName("WHEN OTHER: a row that is not 150 bytes wide is a length error")
        void wrongWidthRow(int width) {
            stubFetch(new FetchedRows(new byte[width], 1));

            CardReadResult result = repository.readByCardNumber(FIRST_FIXTURE_CARD_NUM);

            assertThat(result.resp()).isEqualTo(FileStatus.LENGERR);
            assertThat(result.resp2()).as("the reason code carries the width actually found")
                    .isEqualTo(width);
            assertThat(result.outcome()).isEqualTo(Outcome.OTHER);
            assertThat(result.batchStatus()).isEmpty();
        }

        @Test
        @DisplayName("WHEN OTHER: an unreachable dataset is reported as not open")
        void unreachableDataset() {
            stubRejection(new CannotGetJdbcConnectionException("no connection"));

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

            assertThat(result.resp()).isEqualTo(FileStatus.INVREQ);
            assertThat(result.resp2()).as("the driver's own error code is recovered, not discarded")
                    .isEqualTo(VENDOR_ERROR_CODE);
        }

        @Test
        @DisplayName("recovers a reason code from a nested cause, and reports none when there is none")
        void reasonCodeRecovery() {
            stubRejection(new DataAccessResourceFailureException("wrapped",
                    new IllegalStateException(new SQLException("deep", "08001", VENDOR_ERROR_CODE))));
            assertThat(repository.readByCardNumber(FIRST_FIXTURE_CARD_NUM).resp2())
                    .isEqualTo(VENDOR_ERROR_CODE);

            jdbcTemplate = mock(JdbcTemplate.class);
            repository = new CardRepository(jdbcTemplate, bindings(), codec);
            stubRejection(new DataAccessResourceFailureException("no cause at all"));
            assertThat(repository.readByCardNumber(FIRST_FIXTURE_CARD_NUM).resp2())
                    .isEqualTo(CardRepository.NO_REASON_CODE);
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
            verify(prepared).setString(1, expectedKey);
        }

        @Test
        @DisplayName("bounds the fetch: a unique key cannot want more than one row")
        void boundsTheFetch() throws SQLException {
            stubFetch(FetchedRows.empty());

            repository.readByCardNumber(FIRST_FIXTURE_CARD_NUM);

            PreparedStatement prepared = capturePreparedStatement();
            verify(prepared).setMaxRows(1);
            verify(prepared).setFetchSize(1);
        }

        @Test
        @DisplayName("refuses a null card number: a COBOL alphanumeric field is never absent")
        void refusesANullCardNumber() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> repository.readByCardNumber(null));
        }
    }

    // =============================================================================================
    // 9200-WRITE-PROCESSING, the locking read - the two-way could-not-lock test.
    // =============================================================================================

    @Nested
    @DisplayName("readForUpdateByCardNumber - the locking read")
    class ReadForUpdateTest {

        @Test
        @DisplayName("sends the locking statement, not the plain one")
        void sendsTheLockingStatement() throws SQLException {
            stubFetch(oneRow(cardRecord(FIRST_FIXTURE_CARD_NUM)));

            repository.readForUpdateByCardNumber(FIRST_FIXTURE_CARD_NUM);

            ArgumentCaptor<PreparedStatementCreator> captor =
                    ArgumentCaptor.forClass(PreparedStatementCreator.class);
            verify(jdbcTemplate).query(captor.capture(),
                    CardRepositoryTest.<FetchedRows>anyExtractor());
            Connection connection = mock(Connection.class);
            when(connection.prepareStatement(anyString())).thenReturn(mock(PreparedStatement.class));
            captor.getValue().createPreparedStatement(connection);
            verify(connection).prepareStatement(repository.selectForUpdateByCardNumberStatement());
        }

        @Test
        @DisplayName("a normal response is the lock taken, and carries the locked record")
        void lockTaken() {
            CardRecord locked = cardRecord(FIRST_FIXTURE_CARD_NUM);
            stubFetch(oneRow(locked));

            CardReadResult result = repository.readForUpdateByCardNumber(FIRST_FIXTURE_CARD_NUM);

            assertThat(result.isNormal()).isTrue();
            assertThat(result.requireRecord()).isEqualTo(locked);
        }

        @Test
        @DisplayName("COULD-NOT-LOCK-FOR-UPDATE: a record deleted meanwhile is not normal")
        void lockNotTakenBecauseTheRecordIsGone() {
            stubFetch(FetchedRows.empty());

            CardReadResult result = repository.readForUpdateByCardNumber(FIRST_FIXTURE_CARD_NUM);

            assertThat(result.isNormal()).as("the only test COCRDUPC:1441 makes").isFalse();
            assertThat(result.isNotFound()).isTrue();
        }

        @Test
        @DisplayName("COULD-NOT-LOCK-FOR-UPDATE: a rejected lock is not normal either")
        void lockNotTakenBecauseTheRequestFailed() {
            stubRejection(new InvalidResultSetAccessException(new SQLException("locked elsewhere")));

            CardReadResult result = repository.readForUpdateByCardNumber(FIRST_FIXTURE_CARD_NUM);

            assertThat(result.isNormal()).isFalse();
            assertThat(result.resp()).isEqualTo(FileStatus.INVREQ);
        }
    }

    // =============================================================================================
    // 9150-GETCARD-BYACCT - the alternate-index read, coded in the legacy source but never performed.
    // =============================================================================================

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
            verify(jdbcTemplate).query(captor.capture(),
                    CardRepositoryTest.<FetchedRows>anyExtractor());
            Connection connection = mock(Connection.class);
            when(connection.prepareStatement(anyString())).thenReturn(mock(PreparedStatement.class));
            captor.getValue().createPreparedStatement(connection);
            verify(connection).prepareStatement(repository.selectByAccountIdStatement());
            assertThat(repository.selectByAccountIdStatement())
                    .contains(repository.alternateIndexDatasetName())
                    .doesNotContain(repository.baseDatasetName());
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
            stubRejection(new DataAccessResourceFailureException("path unavailable"));

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
            verify(prepared).setString(1, expectedKey);
        }

        @Test
        @DisplayName("the numeric view and its alphanumeric redefinition produce the same bytes")
        void bothViewsProduceTheSameKey() throws SQLException {
            stubFetch(FetchedRows.empty());
            repository.readByAccountIdViaAltIndex(WIDE_ACCT_ID);
            PreparedStatement fromLong = capturePreparedStatement();
            verify(fromLong).setString(1, "10000000010");

            jdbcTemplate = mock(JdbcTemplate.class);
            repository = new CardRepository(jdbcTemplate, bindings(), codec);
            stubFetch(FetchedRows.empty());
            repository.readByAccountIdViaAltIndex("10000000010");
            PreparedStatement fromDigits = capturePreparedStatement();
            verify(fromDigits).setString(1, "10000000010");
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

    // =============================================================================================
    // REWRITE - full record width, FILLER included.
    // =============================================================================================

    @Nested
    @DisplayName("rewrite - COCRDUPC:1477-1483")
    class RewriteTest {

        /** Captures the two bind values the rewrite sends. */
        private PreparedStatement bindRewrite() throws SQLException {
            ArgumentCaptor<PreparedStatementSetter> captor =
                    ArgumentCaptor.forClass(PreparedStatementSetter.class);
            verify(jdbcTemplate).update(eq(repository.rewriteStatement()), captor.capture());
            PreparedStatement prepared = mock(PreparedStatement.class);
            captor.getValue().setValues(prepared);
            return prepared;
        }

        @Test
        @DisplayName("gates G19 and G21: sends all 150 bytes, with FILLER as 59 spaces")
        void sendsTheWholeRecordIncludingFiller() throws SQLException {
            CardRecord updated = cardRecord(FIRST_FIXTURE_CARD_NUM);
            when(jdbcTemplate.update(anyString(), any(PreparedStatementSetter.class))).thenReturn(1);

            assertThat(repository.rewrite(updated).isNormal()).isTrue();

            ArgumentCaptor<byte[]> image = ArgumentCaptor.forClass(byte[].class);
            PreparedStatement prepared = bindRewrite();
            verify(prepared).setBytes(eq(1), image.capture());
            verify(prepared).setString(2, FIRST_FIXTURE_CARD_NUM);

            byte[] sent = image.getValue();
            assertThat(sent).hasSize(CardRecord.RECORD_LENGTH);
            String filler = new String(sent, CardRecord.FILLER_OFFSET, CardRecord.FILLER_LENGTH,
                    StandardCharsets.US_ASCII);
            assertThat(filler).isEqualTo(" ".repeat(CardRecord.FILLER_LENGTH)).hasSize(59);
        }

        @Test
        @DisplayName("keys the rewrite on the record's own card number, moved to sixteen characters")
        void keysOnTheRecordsOwnCardNumber() throws SQLException {
            when(jdbcTemplate.update(anyString(), any(PreparedStatementSetter.class))).thenReturn(1);

            repository.rewrite(cardRecord("12345"));

            verify(bindRewrite()).setString(2, "12345           ");
        }

        @ParameterizedTest(name = "replacing {0} records is an invalid request")
        @ValueSource(ints = { 0, 2 })
        @DisplayName("a rewrite that did not replace exactly one record is a failure")
        void rewriteThatReplacedTheWrongNumberOfRecords(int replaced) {
            when(jdbcTemplate.update(anyString(), any(PreparedStatementSetter.class)))
                    .thenReturn(replaced);

            CardWriteResult result = repository.rewrite(cardRecord(FIRST_FIXTURE_CARD_NUM));

            assertThat(result.isNormal()).isFalse();
            assertThat(result.isFailure()).isTrue();
            assertThat(result.resp()).isEqualTo(FileStatus.INVREQ);
            assertThat(result.resp2()).as("the reason code says how many were replaced")
                    .isEqualTo(replaced);
            assertThat(result.batchStatus()).isEmpty();
        }

        @Test
        @DisplayName("LOCKED-BUT-UPDATE-FAILED: a rejected rewrite reports the response pair")
        void rejectedRewrite() {
            when(jdbcTemplate.update(anyString(), any(PreparedStatementSetter.class)))
                    .thenThrow(new InvalidResultSetAccessException(
                            new SQLException("write refused", "42000", VENDOR_ERROR_CODE)));

            CardWriteResult result = repository.rewrite(cardRecord(FIRST_FIXTURE_CARD_NUM));

            assertThat(result.isFailure()).isTrue();
            assertThat(result.resp()).isEqualTo(FileStatus.INVREQ);
            assertThat(result.resp2()).isEqualTo(VENDOR_ERROR_CODE);
            assertThat(result.outcome()).isEqualTo(Outcome.OTHER);
        }

        @Test
        @DisplayName("an unreachable dataset is reported as not open")
        void unreachableDatasetOnRewrite() {
            when(jdbcTemplate.update(anyString(), any(PreparedStatementSetter.class)))
                    .thenThrow(new DataAccessResourceFailureException("gone"));

            assertThat(repository.rewrite(cardRecord(FIRST_FIXTURE_CARD_NUM)).resp())
                    .isEqualTo(FileStatus.NOTOPEN);
        }

        @Test
        @DisplayName("refuses a null record: there is no partial-record rewrite")
        void refusesANullRecord() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> repository.rewrite(null));
        }
    }

    // =============================================================================================
    // STARTBR / READNEXT / READPREV / ENDBR - the browse, and the state that lives in the handle.
    // =============================================================================================

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
                    .containsExactly(repository.browseAnchorStatement(),
                            repository.browseForwardStatement());
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
                    .containsExactly(repository.browseAnchorStatement(),
                            repository.browseBackwardStatement(),
                            repository.browseBackwardStatement());
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

        /**
         * The statements the repository actually prepared, in order.
         *
         * @param expectedCalls how many reads to expect
         * @return the statement texts
         * @throws SQLException never; declared because the JDBC API declares it
         */
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

    // =============================================================================================
    // Row reading, in isolation from the template.
    // =============================================================================================

    @Nested
    @DisplayName("row reading")
    class RowReadingTest {

        @Test
        @DisplayName("takes a binary record image as the bytes it already is")
        void readsABinaryImage() throws SQLException {
            byte[] stored = cardRecord(FIRST_FIXTURE_CARD_NUM).encode(codec);
            ResultSet resultSet = mock(ResultSet.class);
            when(resultSet.getBytes(CardRepository.RECORD_IMAGE_COLUMN_INDEX)).thenReturn(stored);

            assertThat(repository.readRecordImage(resultSet)).isEqualTo(stored);
            verify(resultSet, never()).getString(CardRepository.RECORD_IMAGE_COLUMN_INDEX);
        }

        @Test
        @DisplayName("encodes a character record image with the dataset code page, never a default")
        void readsACharacterImage() throws SQLException {
            CardRecord stored = cardRecord(FIRST_FIXTURE_CARD_NUM);
            ResultSet resultSet = mock(ResultSet.class);
            when(resultSet.getBytes(CardRepository.RECORD_IMAGE_COLUMN_INDEX)).thenReturn(null);
            when(resultSet.getString(CardRepository.RECORD_IMAGE_COLUMN_INDEX))
                    .thenReturn(stored.encodeToImage(StandardCharsets.US_ASCII));

            assertThat(repository.readRecordImage(resultSet)).isEqualTo(stored.encode(codec));
        }

        @Test
        @DisplayName("a row carrying neither shape carries no image")
        void readsNoImageAtAll() throws SQLException {
            ResultSet resultSet = mock(ResultSet.class);
            when(resultSet.getBytes(CardRepository.RECORD_IMAGE_COLUMN_INDEX)).thenReturn(null);
            when(resultSet.getString(CardRepository.RECORD_IMAGE_COLUMN_INDEX)).thenReturn(null);

            assertThat(repository.readRecordImage(resultSet)).isNull();
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
                    java.util.Arrays.copyOfRange(remaining, 1, remaining.length));
            when(resultSet.getBytes(CardRepository.RECORD_IMAGE_COLUMN_INDEX)).thenReturn(stored);

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

    // =============================================================================================
    // The result types: no part of a result may contradict another part.
    // =============================================================================================

    @Nested
    @DisplayName("result types")
    class ResultTypeTest {

        @Test
        @DisplayName("every factory builds a result whose response and classification agree")
        void factoriesAgreeWithThemselves() {
            CardRecord stored = cardRecord(FIRST_FIXTURE_CARD_NUM);

            assertThat(CardReadResult.normal(stored).batchStatus()).contains(FileStatus.OK);
            assertThat(CardReadResult.duplicateKey(stored).batchStatus())
                    .contains(FileStatus.DUPLICATE);
            assertThat(CardReadResult.notFound().batchStatus()).contains(FileStatus.NOT_FOUND);
            assertThat(CardReadResult.endOfFile().batchStatus()).contains(FileStatus.END_OF_FILE);
            assertThat(CardReadResult.failed(FileStatus.INVREQ, 1).batchStatus()).isEmpty();
            assertThat(CardWriteResult.normal().batchStatus()).contains(FileStatus.OK);
            assertThat(CardWriteResult.failed(FileStatus.NOTOPEN, 0).batchStatus()).isEmpty();
        }

        @Test
        @DisplayName("gate G47: all four batch statuses are reachable from this class")
        void allFourBatchStatusesAreReachable() {
            assertThat(List.of(CardReadResult.normal(cardRecord(FIRST_FIXTURE_CARD_NUM)),
                            CardReadResult.endOfFile(),
                            CardReadResult.duplicateKey(cardRecord(FIRST_FIXTURE_CARD_NUM)),
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
                    .isThrownBy(() -> CardReadResult.normal(null));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> CardReadResult.duplicateKey(null));
        }

        @ParameterizedTest(name = "response {0} cannot be reported as WHEN OTHER")
        @ValueSource(ints = { FileStatus.NORMAL, FileStatus.NOTFND, FileStatus.ENDFILE,
                              FileStatus.DUPKEY, FileStatus.DUPREC })
        @DisplayName("a named arm cannot be smuggled through the failure factory")
        void namedArmsAreRefusedByTheFailureFactory(int resp) {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> CardReadResult.failed(resp, 0));
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> CardWriteResult.failed(resp, 0));
        }

        @Test
        @DisplayName("a result whose classification disagrees with its response is rejected")
        void classificationMustAgreeWithTheResponse() {
            CardRecord stored = cardRecord(FIRST_FIXTURE_CARD_NUM);

            assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(
                    () -> new CardReadResult(FileStatus.NORMAL, 0, Outcome.OTHER,
                            Optional.of(stored)));
            assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(
                    () -> new CardWriteResult(FileStatus.NORMAL, 0, Outcome.OTHER));
        }

        @Test
        @DisplayName("a result rejects a record on an arm that returns none, and vice versa")
        void recordPresenceMustMatchTheArm() {
            CardRecord stored = cardRecord(FIRST_FIXTURE_CARD_NUM);

            assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(
                    () -> new CardReadResult(FileStatus.NOTFND, 0, Outcome.NOT_FOUND,
                            Optional.of(stored)));
            assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(
                    () -> new CardReadResult(FileStatus.NORMAL, 0, Outcome.OK, Optional.empty()));
        }

        @Test
        @DisplayName("a result never carries a null classification or a null record holder")
        void resultsRejectNulls() {
            assertThatExceptionOfType(NullPointerException.class).isThrownBy(
                    () -> new CardReadResult(FileStatus.NORMAL, 0, null, Optional.empty()));
            assertThatExceptionOfType(NullPointerException.class).isThrownBy(
                    () -> new CardReadResult(FileStatus.NORMAL, 0, Outcome.OK, null));
            assertThatExceptionOfType(NullPointerException.class).isThrownBy(
                    () -> new CardWriteResult(FileStatus.NORMAL, 0, null));
        }

        @Test
        @DisplayName("requireRecord refuses to invent one on an arm that returns none")
        void requireRecordOnAnArmWithoutOne() {
            assertThat(CardReadResult.normal(cardRecord(FIRST_FIXTURE_CARD_NUM)).requireRecord())
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
            CardReadResult normal = CardReadResult.normal(stored);
            CardReadResult duplicate = CardReadResult.duplicateKey(stored);
            CardReadResult notFound = CardReadResult.notFound();
            CardReadResult endOfFile = CardReadResult.endOfFile();
            CardReadResult failed = CardReadResult.failed(FileStatus.LENGERR, 91);

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
            assertThat(CardWriteResult.failed(FileStatus.INVREQ, 0).isNormal()).isFalse();
            assertThat(CardWriteResult.failed(FileStatus.INVREQ, 0).isFailure()).isTrue();
        }
    }

    // =============================================================================================
    // The invariants the rewrite path relies on instead of re-checking.
    // =============================================================================================

    @Nested
    @DisplayName("the record invariants the rewrite relies on rather than re-checking")
    class ReliedUponRecordInvariantsTest {

        // CardRepository.rewrite sends CardRecord.encode(codec) straight to the backend and does NOT
        // re-check its width, on the documented grounds that the guarantee is structural: the layout
        // cannot exist unless its spans sum to the declared width, and no component can exist wider
        // than the span that receives it. A documented reliance is only as good as its proof, so these
        // tests are that proof. They assert the guarantee from the side that depends on it, which is
        // where a regression would actually bite.

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

    // =============================================================================================
    // The real fixture. Read-only, as a reference input.
    // =============================================================================================

    @Nested
    @DisplayName("round trip against app/data/ASCII/carddata.txt")
    class RealFixtureRoundTripTest {

        /** The fixture, relative to the module directory this test runs in. */
        private static final Path FIXTURE = Path.of("..", "..", "app", "data", "ASCII",
                "carddata.txt");

        @Test
        @DisplayName("every one of the fifty rows decodes and re-encodes to the identical 150 bytes")
        void roundTripsEveryFixtureRow() throws IOException {
            List<String> rows = Files.readAllLines(FIXTURE, StandardCharsets.US_ASCII);

            assertThat(rows).hasSize(50);
            for (String row : rows) {
                assertThat(row).as("every fixture row is exactly one card record wide")
                        .hasSize(CardRecord.RECORD_LENGTH);

                byte[] stored = row.getBytes(StandardCharsets.US_ASCII);
                CardRecord decoded = CardRecord.decode(stored, codec);

                assertThat(decoded.encode(codec)).as("re-encoding row %s is byte-identical",
                        decoded.cardNum()).isEqualTo(stored);
                assertThat(decoded.cardNum()).hasSize(CardRecord.CARD_NUM_LENGTH);
                assertThat(new String(stored, CardRecord.FILLER_OFFSET, CardRecord.FILLER_LENGTH,
                        StandardCharsets.US_ASCII))
                        .as("gate G21: the reserved span is 59 spaces")
                        .isEqualTo(" ".repeat(CardRecord.FILLER_LENGTH));
            }
        }

        @Test
        @DisplayName("a fixture row read through the repository yields the record it encodes")
        void readsAFixtureRowThroughTheRepository() throws IOException {
            String row = Files.readAllLines(FIXTURE, StandardCharsets.US_ASCII).get(0);
            stubFetch(new FetchedRows(row.getBytes(StandardCharsets.US_ASCII), 1));

            CardReadResult result = repository.readByCardNumber(FIRST_FIXTURE_CARD_NUM);

            assertThat(result.isNormal()).isTrue();
            CardRecord card = result.requireRecord();
            assertThat(card.cardNum()).as("the leading zero survives, because CARD-NUM is PIC X(16)")
                    .isEqualTo(FIRST_FIXTURE_CARD_NUM);
            assertThat(card.cardAcctId()).isEqualTo(FIRST_FIXTURE_ACCT_ID);
            assertThat(card.cardCvvCd()).isEqualTo(FIRST_FIXTURE_CVV_CD);
            assertThat(card.cardEmbossedName())
                    .as("padding is data: the name is never trimmed on read")
                    .isEqualTo("Aniya Von" + " ".repeat(41))
                    .hasSize(CardRecord.CARD_EMBOSSED_NAME_LENGTH);
            assertThat(card.cardExpiraionDate()).isEqualTo(FIRST_FIXTURE_EXPIRAION_DATE)
                    .hasSize(CardRecord.CARD_EXPIRAION_DATE_LENGTH);
            assertThat(card.cardActiveStatus()).isEqualTo("Y")
                    .hasSize(CardRecord.CARD_ACTIVE_STATUS_LENGTH);
        }
    }
}
