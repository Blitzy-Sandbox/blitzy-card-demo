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
 * cluster <em>and</em> its {@code CARDAIX} alternate-index path.
 *
 * <h2>What is under test, and where it comes from</h2>
 *
 * <p>The unit is the Java migration of the card file access performed by three CICS programs, and the
 * COBOL is the only oracle for it - this is a like-for-like language migration, so every assertion below
 * traces to a measured line of source rather than to what a repository "ought" to do:
 * <ul>
 *   <li>{@code app/cbl/COCRDSLC.cbl} - the keyed read on the base cluster at {@code :742} (paragraph
 *       {@code 9100-GETCARD-BYACCTCARD}, key {@code LENGTH OF WS-CARD-RID-CARDNUM} = 16) and the
 *       alternate-index read at {@code :783-784} (paragraph {@code 9150-GETCARD-BYACCT}, key
 *       {@code LENGTH OF WS-CARD-RID-ACCT-ID} = 11), each with its own three-armed
 *       {@code EVALUATE WS-RESP-CD} at {@code :752-772};</li>
 *   <li>{@code app/cbl/COCRDUPC.cbl} - the same keyed read at {@code :1382}, the locking
 *       {@code READ ... UPDATE} at {@code :1427-1436} with its two-way could-not-lock test at
 *       {@code :1441-1449}, and the full-width {@code REWRITE} at {@code :1478-1484} with its own
 *       two-way test at {@code :1488-1493};</li>
 *   <li>{@code app/cbl/COCRDLIC.cbl} - the browse: {@code STARTBR} at {@code :1129} forward and
 *       {@code :1273} backward, {@code READNEXT} at {@code :1146} and {@code :1197}, {@code READPREV}
 *       at {@code :1294} and {@code :1322}, {@code ENDBR} at {@code :1258} and {@code :1376}.</li>
 * </ul>
 * The record shape is {@code app/cpy/CVACT02Y.cpy}: {@code 01 CARD-RECORD}, exactly 150 bytes, ending in
 * {@code FILLER PIC X(59)}. The dataset names are {@code app/csd/CARDDEMO.CSD}'s, reached only as
 * {@code carddemo.datasets} configuration keys. All of these are read-only reference inputs, cited here
 * and never opened, copied or written by any test in this file.
 *
 * <h2>Governing directives</h2>
 *
 * <p><strong>No user rules were provided for this project</strong> - {@code review_rules} returns the
 * single line "No user rules provided", which is the whole document. Their absence is not licence to
 * lower the bar: this file is held instead to the enterprise practices the Agent Action Plan codifies in
 * §0.10.2 B1-B12 and to the absolutes in §0.8.9. Specifically B1, the closed test stack (JUnit Jupiter,
 * Mockito and AssertJ as {@code spring-boot-starter-test} supplies them - nothing added, no version
 * literal anywhere); B2, JUnit 5 only; B3, the reference tree is immutable and is never read at runtime -
 * fixture bytes arrive from the test classpath at {@value #FIXTURE}; B7, deterministic and
 * non-interactive - no network, no wall clock, no randomness, no ordering between tests; B8, explicit
 * over implicit - the code page is always named and never the platform default; B9, no static mutable
 * state; and B11, hand-written assertions against explicit copybook offsets rather than a parser or a
 * reflective deep-equality that would hide which offset diverged.
 *
 * <h2>Gates this file is answerable for</h2>
 *
 * <table>
 *   <caption>Validation gates and where they are asserted</caption>
 *   <tr><th>Gate</th><th>Assertion</th></tr>
 *   <tr><td>G45</td><td>the alternate index is a second access path on <em>one</em> repository over
 *       <em>one</em> dataset - never a second repository, table or {@code DataSource}: enforced at
 *       construction in {@link ConstructionTest} and proved behaviourally in
 *       {@link ReadByAccountIdTest#theSameRecordIsReachableThroughBothPaths()}</td></tr>
 *   <tr><td>G46</td><td>no {@code DSNAME} literal appears in Java: every statement names only what
 *       configuration supplied ({@link ConstructionTest})</td></tr>
 *   <tr><td>G47</td><td>every {@link FileStatus} outcome is exercised <em>per call site</em>, because
 *       each COBOL paragraph carries its own {@code EVALUATE}</td></tr>
 *   <tr><td>G19, G21</td><td>150 bytes on the wire with the reserved span space-filled
 *       ({@link RewriteTest}, {@link RealFixtureRoundTripTest})</td></tr>
 *   <tr><td>G44</td><td>nothing schema-shaped is required or emitted - no DDL, no entity annotation, no
 *       version column ({@link NoSchemaFootprintTest})</td></tr>
 *   <tr><td>G49</td><td>these tests are what carry {@code com.vsergeychik.carddemo.card} over the
 *       JaCoCo {@code BRANCH} &ge; 0.90 rule, which is scoped per package</td></tr>
 *   <tr><td>G52, G53</td><td>every import is explicit and no field is static and mutable</td></tr>
 * </table>
 *
 * <p>Every test runs against a stubbed template with <strong>no backend of any kind</strong> - no HTTP,
 * no job launcher, no embedded database. That is not a convenience: production connectivity cannot be
 * exercised in this environment at all (residual risk R-E), so every arm of every guard chain has to be
 * reachable without one, and these tests are the proof that it is. {@link RealFixtureRoundTripTest} adds
 * the real fifty-record fixture on the classpath - byte-identical to
 * {@code app/data/ASCII/carddata.txt} - to prove that what this repository decodes from a row and what it
 * would send back are the same bytes.
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

    /** The embossed name the first fixture row carries: nine characters in a fifty-byte span. */
    private static final String FIRST_FIXTURE_EMBOSSED_NAME = "Aniya Von";

    /** The active-status flag the first fixture row carries. */
    private static final String FIRST_FIXTURE_ACTIVE_STATUS = "Y";

    /**
     * The card fixture, <strong>on the test classpath</strong>.
     *
     * <p>This file is a byte-identical copy of {@code app/data/ASCII/carddata.txt}, shipped under
     * {@code src/test/resources} precisely so that no test has to reach into the reference tree. AAP
     * §0.10.2 B3 makes that tree immutable and forbids opening it at runtime - it is the parity oracle,
     * and a test that reads it through a relative path is also a test that depends on the directory the
     * build happened to be launched from, which B7 rules out. Unlike {@code cardxref.txt}, this fixture
     * needs no width normalisation: all fifty rows measure exactly {@value CardRecord#RECORD_LENGTH}
     * bytes, matching {@code app/cpy/CVACT02Y.cpy} as declared.
     */
    private static final String FIXTURE = "/fixtures/carddata.txt";

    /** The fixture's measured record count. */
    private static final int FIXTURE_RECORDS = 50;

    /**
     * The code page of the {@code app/data/ASCII} fixtures, named rather than inherited.
     *
     * <p>{@link CobolCharsetConfig} publishes exactly this choice as a bean: {@code IBM037} for the
     * EBCDIC datasets and {@code US-ASCII} for these text fixtures, with the active dataset code page
     * selected per profile. The platform default is consulted nowhere in the module, and nowhere here.
     */
    private static final Charset FIXTURE_CHARSET = StandardCharsets.US_ASCII;

    /**
     * The EBCDIC code page {@link CobolCharsetConfig} names for the binary datasets.
     *
     * <p>Held here only to prove the negative: decoding these bytes under the wrong code page does not
     * quietly produce the same fields, which is what makes naming the code page load-bearing rather than
     * decorative.
     */
    private static final Charset EBCDIC_CHARSET = Charset.forName("IBM037");

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

    /**
     * The record-image column name the stubbed backend describes.
     *
     * <p>Deliberately not a copybook field name. The repository discovers this name from result-set
     * metadata rather than assuming one, so the test supplies a name no copybook contains - which is what
     * proves the discovery is real and that no field name has been smuggled into a statement.
     */
    private static final String DESCRIBED_COLUMN = "VSAM_RECORD_IMAGE";

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        codec = new FixedWidthCodec(StandardCharsets.US_ASCII);
        repository = new CardRepository(jdbcTemplate, bindings(), codec, RecordImageForm.CHARACTER);
        stubDescribe(repository);
    }

    /**
     * Runs a body with a transaction marked active on this thread, which is what a locking read requires.
     *
     * <p>Marking the flag directly rather than starting a real transaction is the honest test of the
     * precondition: what {@code readForUpdateByCardNumber} demands is that a unit of work be open, and
     * this asserts exactly that demand without dragging a transaction manager and a real
     * {@code DataSource} into a test whose subject is a composed statement.
     *
     * @param work the body
     * @param <T>  its result type
     * @return the body's result
     */
    private static <T> T inUnitOfWork(Supplier<T> work) {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            return work.get();
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    /**
     * Stubs the describe round trip a repository performs before it composes its statements.
     *
     * @param target the repository whose describes are to be answered
     */
    private void stubDescribe(CardRepository target) {
        when(jdbcTemplate.query(eq(target.describeBaseStatement()),
                CardRepositoryTest.<String>anyExtractor())).thenReturn(DESCRIBED_COLUMN);
        when(jdbcTemplate.query(eq(target.describeAlternateIndexStatement()),
                CardRepositoryTest.<String>anyExtractor())).thenReturn(DESCRIBED_COLUMN);
    }

    /**
     * Forces resolution of the base cluster's statements and returns them.
     *
     * @param target the repository
     * @return its resolved base-cluster statements
     */
    private CardRepository.BaseStatements statementsOf(CardRepository target) {
        stubDescribe(target);
        when(jdbcTemplate.query(any(PreparedStatementCreator.class),
                CardRepositoryTest.<FetchedRows>anyExtractor())).thenReturn(FetchedRows.empty());
        target.readByCardNumber(FIRST_FIXTURE_CARD_NUM);
        return target.resolvedBaseStatements();
    }

    /**
     * Forces resolution of the alternate-index path's statements and returns them.
     *
     * <p>Through a read of the path, because that is the only operation that resolves it: the base
     * cluster and the path are described separately, by whichever operation needs one.
     *
     * @param target the repository
     * @return its resolved alternate-index statements
     */
    private CardRepository.AlternateStatements alternateStatementsOf(CardRepository target) {
        stubDescribe(target);
        when(jdbcTemplate.query(any(PreparedStatementCreator.class),
                CardRepositoryTest.<FetchedRows>anyExtractor())).thenReturn(FetchedRows.empty());
        target.readByAccountIdViaAltIndex(FIRST_FIXTURE_ACCT_ID);
        return target.resolvedAlternateStatements();
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
                null,
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
                recordLength, "CVACT02Y", null, null, base, alternateKey);
    }

    /**
     * The fixture's rows, exactly as stored and with nothing trimmed.
     *
     * <p>Read from the test classpath, never from {@code app/data/ASCII} - see {@link #FIXTURE}. The
     * code page is stated, not inherited (AAP §0.10.2 B8), and the line terminator is dropped without
     * touching anything inside a row, because trailing spaces inside a fixed-width record are data.
     *
     * @return the {@value #FIXTURE_RECORDS} rows in file order, each exactly
     *         {@value CardRecord#RECORD_LENGTH} characters
     */
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

    /**
     * A text resource read from the test classpath, with the code page named.
     *
     * <p>Used to read the module's own configuration documents, which are classpath resources of this
     * build - not the reference tree, which AAP §0.10.2 B3 keeps closed.
     *
     * @param resource the absolute classpath location
     * @return the resource's text
     */
    private static String classpathText(String resource) {
        try (InputStream stream = CardRepositoryTest.class.getResourceAsStream(resource)) {
            assertThat(stream).as("%s must be on the test classpath", resource).isNotNull();
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            throw new UncheckedIOException("Unable to read " + resource, unreadable);
        }
    }

    /**
     * Whether a YAML document declares a mapping key, at any indentation.
     *
     * @param yaml the document text
     * @param key  the key name, without its colon
     * @return {@code true} if some line is exactly that key
     */
    private static boolean declaresKey(String yaml, String key) {
        for (String line : yaml.split("\n", -1)) {
            if (line.strip().equals(key + ":")) {
                return true;
            }
        }
        return false;
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
     * The statements the repository prepared, in the order it prepared them.
     *
     * @param expectedCalls how many reads to expect
     * @return the statement texts
     * @throws SQLException never; declared because the JDBC API declares it
     */
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
     * Captures the statement creator the repository sent <em>first</em>, and drives it against a stubbed
     * connection.
     *
     * <p>The first, not the only one: a keyed read that matches no row is followed by the unreadable-row
     * probe, because {@code CARD-NUM} lives inside the record image and an absence therefore has to be
     * proved rather than assumed. Every caller of this helper is asserting the read's own statement or its
     * own bound operand, so the read's creator is the one it wants.
     *
     * @return the statement the read's creator prepared
     * @throws SQLException never; declared because the JDBC API declares it
     */
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
            // Each positioning read carries "OR <image> IS NULL" as well, because a comparison against a
            // null is UNKNOWN: without it a row whose record image is absent qualifies for no browse step
            // and the pass walks silently past a record it should have failed on (QA finding B).
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

            // CARD-NUM and CARD-ACCT-ID name spans of app/cpy/CVACT02Y.cpy. Asking a backend for columns
            // so named would assert a relational schema nothing here describes - while this same class
            // reads the whole record image out of column one, so both cannot be true of one backend.
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
            // The names this repository uses are looked up as carddemo.datasets.CARDDAT and
            // carddemo.datasets.CARDAIX, and nowhere else. Two halves are asserted here:
            //
            //  - the Java side never spells a dataset name. The DD names below are configuration KEYS,
            //    not dataset names, and no production dataset name appears anywhere in this file - not in
            //    code and not in a comment, which is the whole of gate G46. That is also why every test in
            //    this class supplies its own names through a binding catalogue rather than expecting a
            //    built-in one.
            //  - the configuration side really declares those two keys, in the production document and in
            //    the test profile alike. A rename on either side would otherwise be found only at
            //    context refresh, and the failure would name a missing binding rather than a renamed key.
            //
            // Only the keys are asserted, never their values: reading a value into this file would put a
            // dataset name back into Java by the back door.
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
            // A quotation mark is not a character a z/OS dataset name admits, so the name is refused
            // rather than quoted into a statement. Quoting alone would be the weaker answer: it defends
            // against the punctuation someone thought of, whereas the grammar defends against every
            // character the platform does not permit - which is a superset that needs no maintenance.
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
            // COCRDLIC:213-217, COCRDSLC:187-190 and COCRDUPC:251-254 all declare PIC X(8) with a
            // trailing space, and COCRDUPC's 80-character file-error message depends on the width.
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

        @Test
        @DisplayName("a NOTFND is PROVED: an unreadable row in the relation makes it WHEN OTHER instead")
        void aNotFoundIsProvedAgainstUnreadableRows() throws SQLException {
            // CARD-NUM lives inside the record image, so a row with no image has no knowable key: the
            // keyed predicate cannot match it, and answering NOTFND would report as absent a record that
            // may well be the one asked for. COCRDSLC:755-761 takes NOTFND as "there is no such card" and
            // says so on the screen, which is why the claim has to be established rather than assumed.
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

            // Exactly one read. A corrupt row elsewhere in the dataset is none of this read's business:
            // a VSAM READ of a key that resolves does not fail because another record is damaged.
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
            // The width is a measurement of the stored row, not a CICS reason code. It travels labelled,
            // beside the response pair, because COCRDUPC:1410 renders ERROR-RESP2 verbatim onto a screen
            // and a reader has no way to tell a width reported there from a genuine reason code.
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
            // SQLSTATE class 08 is the standard connection exception, and it is what decides the
            // response - not the wrapper type, which is a framework's opinion about the code beneath it.
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

            // SQLSTATE class 42 is a syntax error or access-rule violation: the relation does not
            // exist, or this identity may not reach it. Either way the file is not open to the task.
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
            // A framework's data-access exception is a wrapper: the SQLSTATE lives on the SQLException
            // inside it, so taking the wrapper's own type as the diagnosis is what loses it.
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
            // The key is at offset 0, so the pattern is the key followed by the any-sequence wildcard
            // that covers the remaining 134 bytes of the record image.
            verify(prepared).setString(1, expectedKey + "%");
        }

        @Test
        @DisplayName("the key is sixteen bytes wide: LENGTH OF WS-CARD-RID-CARDNUM, COCRDSLC:742")
        void theKeyLengthIsSixteen() throws SQLException {
            // COCRDSLC:742 and COCRDUPC:1382 both pass KEYLENGTH(LENGTH OF WS-CARD-RID-CARDNUM), and
            // WS-CARD-RID-CARDNUM is PIC X(16) (COCRDSLC:98, COCRDUPC:129). Sixteen is therefore not a
            // convention this class chose - it is the full width of CARDDAT's primary key, and a read
            // issued with a shorter key would be a generic key read, which CICS treats as a different
            // request entirely. Three things must agree on it: the copybook constant, the key span this
            // repository binds, and the bytes that actually reach the statement.
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
            // COCRDSLC:768-771 moves WS-RESP-CD and WS-REAS-CD into the composed error message, so both
            // halves of the response pair have to survive the trip - a result that carried only the
            // response would leave that message with a blank where the reason code belongs. The pair is
            // carried on every arm, named, and a measurement is never smuggled into resp2's place.
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

            // Two, not one. CARD-NUM is the base cluster's unique primary key, so a second matching row
            // is an integrity defect in the backing relation - and a cap of one row would make it
            // invisible, leaving the read to return whichever row the backend ordered first as though it
            // were the record. One extra row is what makes "more than one" detectable, and nothing
            // beyond it is fetched because nothing beyond it changes the answer.
            PreparedStatement prepared = capturePreparedStatement();
            verify(prepared).setMaxRows(2);
            verify(prepared).setFetchSize(2);
            verify(prepared, never()).setMaxRows(1);
        }

        @Test
        @DisplayName("a key matching more than one row is refused, not resolved by taking the first")
        void aFanOutOnThePrimaryKeyIsRefused() {
            // CARD-NUM is the base cluster's unique primary key, so two matches cannot arise in the
            // legacy system and are an integrity defect in the backing relation. Returning the first with
            // the normal response would hand COCRDSLC or COCRDUPC a card record chosen by whatever order
            // the backend produced - and COCRDUPC would then REWRITE it. So the read reports WHEN OTHER.
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

            // FOR UPDATE outside a transaction releases its lock the moment the statement returns, so
            // the read-compare-rewrite sequence it exists to protect would be exactly as exposed as an
            // unlocked read - while reporting the same outcome. Refusing turns a silent correctness bug
            // into a loud wiring bug.
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
            // The COBOL guard has two nested decisions, and only the outer one belongs to this layer:
            //
            //   IF WS-RESP-CD EQUAL TO DFHRESP(NORMAL)      <-- the repository's decision
            //      CONTINUE
            //   ELSE
            //      SET INPUT-ERROR TO TRUE
            //      IF  WS-RETURN-MSG-OFF                    <-- the controller's decision
            //          SET COULD-NOT-LOCK-FOR-UPDATE TO TRUE
            //      END-IF
            //      GO TO 9200-WRITE-PROCESSING-EXIT
            //   END-IF
            //
            // WS-RETURN-MSG-OFF asks whether a message is ALREADY pending on the screen, which is screen
            // state - it lives in the controller, and putting it here would make the repository answer a
            // question it cannot see. What this layer owes both sides is therefore the same thing: one
            // result, from which a caller with a message pending suppresses the lock diagnostic and a
            // caller without one raises it. So both sides are driven here against a single result, and the
            // demand is that reading it is stable: the ELSE branch is taken identically either way, and no
            // amount of re-reading changes what the caller would decide. A result that mutated on read, or
            // that reported the lock as taken to one caller and not the other, would break the guard.
            stubFetch(FetchedRows.empty());

            CardReadResult result =
                    inUnitOfWork(() -> repository.readForUpdateByCardNumber(FIRST_FIXTURE_CARD_NUM));

            // Side 1 - WS-RETURN-MSG-OFF true: nothing is pending, so this caller sets
            // COULD-NOT-LOCK-FOR-UPDATE. It needs to know the lock was not taken, and why.
            boolean returnMessageOff = true;
            boolean couldNotLockForUpdate = !result.isNormal() && returnMessageOff;
            assertThat(couldNotLockForUpdate)
                    .as("with no message pending, the ELSE arm raises COULD-NOT-LOCK-FOR-UPDATE")
                    .isTrue();

            // Side 2 - WS-RETURN-MSG-OFF false: a message is already on the screen, so this caller takes
            // the same ELSE arm and sets INPUT-ERROR only, leaving the earlier message in place.
            returnMessageOff = false;
            boolean inputError = !result.isNormal();
            couldNotLockForUpdate = !result.isNormal() && returnMessageOff;
            assertThat(inputError).as("INPUT-ERROR is set on the ELSE arm regardless").isTrue();
            assertThat(couldNotLockForUpdate)
                    .as("but the pending message is not overwritten")
                    .isFalse();

            // The result both sides read is the same object read twice, and it says the same thing twice:
            // the lock was not taken, with the response pair intact for the diagnostic either caller
            // chooses to compose.
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
            // atLeastOnce, because a read that matched no row goes on to prove the absence with the
            // unreadable-row probe over the same path. The read's own creator is the first one.
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
            // COCRDSLC:783-784 reads the path with RIDFLD(WS-CARD-RID-ACCT-ID), and WS-CARD-RID-ACCT-ID
            // is PIC 9(11) - so eleven digits, and eleven is the whole of CARDDAT's alternate key. The
            // offset matters as much as the width: CARD-ACCT-ID sits at 16 in CVACT02Y, whereas the
            // cross-reference record CVACT03Y carries an account id of the same width at a DIFFERENT
            // offset. A predicate that lost the offset would read the wrong records while looking right.
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
            // CARD-ACCT-ID lives inside the record image too, so a row with no image has no knowable
            // alternate key either, and an unreadable row leaves "no card for this account" unprovable.
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
            // THE gate G45 assertion, stated as behaviour rather than as structure.
            //
            // CARDAIX is not a second dataset and not a second copy of the data: app/csd/CARDDEMO.CSD
            // defines FILE(CARDAIX) at :13 with a DSNAME ending ".VSAM.AIX.PATH" - a VSAM PATH over the
            // base cluster FILE(CARDDAT) defines at :25 - so the two names address one set of records
            // through two access paths. The names themselves are deliberately not written here, in this
            // comment or anywhere else in this file: gate G46 keeps every dataset name in configuration,
            // and a name in a comment is the first step to a name in code.
            // app/jcl/INTCALC.jcl proves the pattern is the norm on
            // this system rather than an interpretation: one step opens the cross-reference twice, as
            // XREFFILE on the base KSDS and XREFFIL1 on the AIX path over that same base.
            //
            // The industry default would be two repositories, or two tables, or a join. AAP §0.7.4
            // forbids all three, and this test is what makes the prohibition checkable: one repository,
            // one relation of records, two keys - and a record fetched by card number is byte-identical
            // to the record fetched by that record's own account id. There is deliberately no second
            // CardRepository, no second DataSource and no second JdbcTemplate anywhere in this file.
            CardRecord stored = cardRecord(FIRST_FIXTURE_CARD_NUM);
            stubFetch(oneRow(stored));

            CardRecord throughTheBase =
                    repository.readByCardNumber(FIRST_FIXTURE_CARD_NUM).requireRecord();
            CardRecord throughThePath =
                    repository.readByAccountIdViaAltIndex(throughTheBase.cardAcctId())
                            .requireRecord();

            // All 150 bytes, compared as bytes: the strongest form of "the same record".
            assertThat(throughThePath.encode(codec))
                    .as("gate G45: one dataset, two access paths - so one record")
                    .hasSize(CardRecord.RECORD_LENGTH)
                    .isEqualTo(throughTheBase.encode(codec));

            // And field by field, so a failure names the span that diverged rather than one opaque
            // inequality over 150 bytes (AAP §0.3.8 and §0.10.2 B11).
            assertThat(throughThePath.cardNum()).isEqualTo(throughTheBase.cardNum());
            assertThat(throughThePath.cardAcctId()).isEqualTo(throughTheBase.cardAcctId());
            assertThat(throughThePath.cardCvvCd()).isEqualTo(throughTheBase.cardCvvCd());
            assertThat(throughThePath.cardEmbossedName())
                    .isEqualTo(throughTheBase.cardEmbossedName());
            assertThat(throughThePath.cardExpiraionDate())
                    .isEqualTo(throughTheBase.cardExpiraionDate());
            assertThat(throughThePath.cardActiveStatus())
                    .isEqualTo(throughTheBase.cardActiveStatus());

            // ONE repository and ONE template served both reads. That is the structural half of G45, and
            // it is asserted rather than assumed: two reads, two configured names, one component. The
            // constructor is what keeps the second name honest - it refuses a path binding that claims to
            // index a different base or a different alternate key, which ConstructionTest drives directly.
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
            // CARD-ACCT-ID is at offset 16, so sixteen single-character wildcards precede the key. That
            // leading run IS the offset, expressed in SQL - and it is what keeps this predicate from
            // matching the cross-reference record's account id, the same width at a different offset.
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

    // =============================================================================================
    // REWRITE - full record width, FILLER included.
    // =============================================================================================

    @Nested
    @DisplayName("rewrite - COCRDUPC:1477-1483")
    class RewriteTest {

        /**
         * Stubs the count the rewrite takes before it writes, so a test reaches the write at all.
         *
         * <p>The rewrite establishes that its key selects exactly one row before it replaces anything -
         * finding BD-03 - so a mocked template that answers nothing to that count refuses the write
         * without issuing it. Every test that is about the write itself therefore has to say that the
         * record is there, which is also what makes the tests below say out loud that the count happens.
         */
        private void stubTheRowIsThere() {
            stubFetch(oneRow(cardRecord(FIRST_FIXTURE_CARD_NUM)));
        }

        /** Captures the two bind values the rewrite sends. */
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
            // The full COCRDUPC sequence, end to end: read the record under a lock (:1427-1436), change
            // the fields the screen changed, rewrite at full width (:1478-1484), and read back what is
            // now stored. The comparison is FIELD BY FIELD, never as one whole string - that is the AAP
            // §0.3.8 rule, and the reason is diagnostic: a 150-character inequality tells you the record
            // is wrong, whereas a field comparison tells you which span moved.
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

            // What went onto the wire is what a subsequent read would find, so the re-read is seeded with
            // the very image the rewrite sent rather than with a re-encoding of the record.
            ArgumentCaptor<String> image = ArgumentCaptor.forClass(String.class);
            verify(bindRewrite()).setString(eq(1), image.capture());
            String written = image.getValue();
            clearInvocations(jdbcTemplate);
            stubDescribe(repository);
            stubFetch(new FetchedRows(written.getBytes(FIXTURE_CHARSET), 1));

            CardRecord after = repository.readByCardNumber(FIRST_FIXTURE_CARD_NUM).requireRecord();

            // Field by field. The changed spans carry the new values, at their declared widths; the
            // untouched spans are byte-for-byte what they were; and the key is unchanged, because a
            // REWRITE replaces a record and cannot re-key it.
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
            // Nothing is written and nothing needs undoing: the count comes first, so a fan-out is
            // precluded rather than discovered from the affected-row count once the rows are gone.
            stubFetch(new FetchedRows(cardRecord(FIRST_FIXTURE_CARD_NUM).encode(codec), selected));

            CardWriteResult result = inUnitOfWork(
                    () -> repository.rewrite(cardRecord(FIRST_FIXTURE_CARD_NUM)));

            assertThat(result.isNormal()).isFalse();
            assertThat(result.isFailure()).isTrue();
            assertThat(result.resp()).isEqualTo(FileStatus.INVREQ);
            assertThat(result.batchStatus()).isEmpty();
            // The count is a measurement, so it travels labelled - it is emphatically NOT reported as a
            // CICS reason code, which COCRDUPC:1410 renders verbatim onto an operator's screen.
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

            // The count and the UPDATE must see the same rows, so the count takes the very lock the
            // UPDATE will use. There is no second, lock-free form to choose between: a rewrite with no
            // unit of work open is refused rather than issued, so this is the only shape reachable.
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

            // The pool hands out connections with auto-commit disabled, so the UPDATE would execute,
            // report the row it replaced, and then be rolled back when the connection was returned -
            // leaving COCRDUPC painting a successful update for a card no later read could find.
            assertThatIllegalStateException()
                    .isThrownBy(() -> repository.rewrite(cardRecord(FIRST_FIXTURE_CARD_NUM)))
                    .withMessageContaining("no transaction is open on this thread")
                    .withMessageContaining("changes stored records");

            verify(jdbcTemplate, never()).update(anyString(), any(PreparedStatementSetter.class));
        }

        /**
         * The statement text the pre-write count prepared.
         *
         * @return that text
         * @throws SQLException never; declared because the JDBC API declares it
         */
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
            // The one case a response cannot carry: the rows are already replaced, so returning a status
            // would report damage that the enclosing unit of work then commits on the way out.
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
            // SQLSTATE class 22 is a data exception: the value was rejected. Nothing about that says the
            // file is closed, so it reaches the invalid-request response and the caller's not-normal arm.
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
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> repository.openBrowse(null, BrowseDirection.FORWARD));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> repository.openBrowse(FIRST_FIXTURE_CARD_NUM, null));
        }

        @Test
        @DisplayName("startBrowse reports NORMAL without asking, because COCRDLIC discards its own status")
        void startBrowseReportsNormalWithoutAsking() {
            // Both EXEC CICS STARTBR sites capture the response and never test it (COCRDLIC:1129-1136 and
            // :1273-1280), so this entry point makes no call and a caller that does look sees the normal
            // arm rather than a status the COBOL never produced.
            CardBrowse browse = repository.startBrowse(FIRST_FIXTURE_CARD_NUM, BrowseDirection.FORWARD);

            assertThat(browse.openResp()).isEqualTo(FileStatus.NORMAL);
            assertThat(browse.isOpen()).isTrue();
            verify(jdbcTemplate, never()).query(anyString(),
                    CardRepositoryTest.<String>anyExtractor());
        }

        @Test
        @DisplayName("openBrowse describes the relation and reports NORMAL: the batch OPEN INPUT")
        void openBrowseProbesAndReportsNormal() {
            // app/cbl/CBACT02C.cbl:120 is an OPEN INPUT with FILE STATUS IS CARDFILE-STATUS declared at
            // :33 and tested at :121-:127, so the batch caller needs an outcome. The probe describes the
            // relation - no row crosses the wire - which is what an OPEN INPUT establishes too.
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

            // Three opens, three describes. app/cbl/CBACT02C.cbl:120-132 establishes the file and tests
            // the status on its own account, so an OPEN that reused an earlier one's proof could not fail
            // after the first success.
            verify(jdbcTemplate, times(3)).query(eq(repository.describeBaseStatement()),
                    CardRepositoryTest.<String>anyExtractor());
        }

        @Test
        @DisplayName("finding DB-04: openBrowse describes the base cluster ONLY, never the AIX path")
        void openBrowseDescribesTheBaseClusterOnly() {
            // OPEN INPUT CARDFILE (app/cbl/CBACT02C.cbl:120) establishes CARDFILE. CARDAIX is a second DD
            // name over the same cluster (gate G45) that this operation never reads, so describing it here
            // paid a metadata round trip for nothing and - the real defect - reported
            // 'ERROR OPENING CARDFILE' when only the PATH was unavailable.
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
            // The QA reproduction, at the seam: a successful pass first, which memoises the statements,
            // then the dataset goes away, then the same singleton is asked to open again.
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

            // NOTOPEN is the arm that displays ERROR OPENING CARDFILE (app/cbl/CBACT02C.cbl:129) and
            // abends. Before the fix this reported NORMAL and the failure surfaced one line later on the
            // first READNEXT, under ERROR READING CARDFILE (:110) - the same abend, the wrong paragraph.
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
            // Page size 7 is NOT this class's concern. COCRDLIC:177-178 declares
            // WS-MAX-SCREEN-LINES PIC S9(4) COMP-3 VALUE 7 in the CONTROLLER, and it is the controller
            // that stops filling a screen after seven rows; the file itself has no such notion and
            // READNEXT keeps returning records until the file ends. Baking 7 into the repository would
            // make the card list unable to answer any question but "one screenful", and would break
            // COCRDLIC's own backward paging, which walks the same file past its screen boundaries.
            // So: eight records, requested one at a time, all delivered, in ascending key order.
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
            // STARTBR ... GTEQ (COCRDLIC:1129, and :1277 for the backward browse) positions at the first
            // record whose key is greater than OR EQUAL to the one supplied - it does not require the key
            // to exist, and it does not report NOTFND when it does not. COCRDLIC relies on exactly that:
            // it pages from a card number the operator typed, which need not be a card number the file
            // holds. An implementation that demanded equality would report an empty list for a perfectly
            // valid filter.
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

            // The statement sent is the anchoring one, whose predicate is >= the key - not the advancing
            // one, and not the keyed read that would have demanded equality.
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
            // Finding BD-08. The stored row is a real card record whose trailing FILLER X(59) holds
            // something other than spaces - which a dataset written by anything but this model may well
            // do. Re-encoding the record decoded from it yields an image with the FILLER space-filled,
            // and spaces sort BELOW the stored bytes, so a browse advancing by the re-encoded value would
            // ask for the first image after a value no row has - and hand back this same record again,
            // for ever. Advancing by the bytes the backend gave excludes it strictly.
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

            // The advancing step's bind value is the stored bytes verbatim.
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
            // A template that answers with no object has told the browse nothing, and "nothing" is not a
            // record. It must not become a NullPointerException inside the handle either, so it is read as
            // the end of the pass - the same outcome an empty answer has.
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
            // This is the finding, expressed as a test. The repository used to read getBytes and, when
            // the driver returned none, read the SAME column again as getString and encode it - a
            // fallback across two representations of one column. It could not fail loudly: it returned a
            // plausible 150 bytes either way, so a driver presenting the column as the other type yielded
            // records whose fields sat at the right offsets holding the wrong values. A column has one
            // type, configuration states which, and a form that finds nothing reports nothing.
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

        /**
         * The same repository over the same bindings, reading its record image as bytes.
         *
         * @return a repository whose deployment presents the record image as a binary column
         */
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

    // =============================================================================================
    // Raw record image fidelity. DISPLAY CARD-RECORD (app/cbl/CBACT02C.cbl:78) writes the whole
    // 150-byte FD record area, and CVACT02Y ends in FILLER X(59) that no field of the record covers.
    // A read must therefore hand its caller the bytes the row held, because reconstructing an image
    // from the decoded fields allocates a fresh area and so writes spaces across that span whatever
    // the row carried. Gates G19 and G21, and AAP R5.
    // =============================================================================================

    @Nested
    @DisplayName("raw record image fidelity - the FILLER X(59) of CVACT02Y")
    class RawRecordImageFidelityTest {

        /** The 59 bytes of CVACT02Y FILLER, carrying a value no field of the record can hold. */
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
            // This is the arm CBACT02C actually reads through: 1000-CARDFILE-GET-NEXT then DISPLAY.
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

            // Every field round trips. Only the span no field covers does not - and DISPLAY writes it.
            assertThat(decoded).isEqualTo(cardRecord(FIRST_FIXTURE_CARD_NUM));
            assertThat(decoded.encodeToImage(StandardCharsets.US_ASCII))
                    .as("a fresh record area blanks FILLER X(59), so this is not what CBACT02C:78 writes")
                    .hasSize(CardRepository.RECORD_LENGTH)
                    .isNotEqualTo(dirty)
                    .endsWith(" ".repeat(CardRecord.FILLER_LENGTH));
        }

        /**
         * The fixture record's own bytes, with the trailing {@code FILLER X(59)} overwritten.
         *
         * @return a 150-character image every field of which decodes, whose FILLER holds no spaces
         */
        private String dirtyImage() {
            String clean = storedImage(cardRecord(FIRST_FIXTURE_CARD_NUM));
            String dirty = clean.substring(0, CardRepository.RECORD_LENGTH - CardRecord.FILLER_LENGTH)
                    + DIRTY_FILLER;
            assertThat(dirty).hasSize(CardRepository.RECORD_LENGTH).isNotEqualTo(clean);
            return dirty;
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
            // The guard exists because a negative value in RESP2 means something that is not a reason code
            // was reported as one - which is finding BD-07 in its most literal form. A driver's vendor
            // error number is routinely negative.
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> CardReadResult.reportedFailure(FileStatus.INVREQ, -1))
                    .withMessageContaining("reason codes are non-negative")
                    .withMessageContaining("DatasetObservation");
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> CardWriteResult.reportedFailure(FileStatus.INVREQ, -911))
                    .withMessageContaining("reason codes are non-negative");
            // And the positive form is accepted, because an adapter that reports a real reason code must
            // be able to.
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
            // DISPLAY CARD-RECORD writes the record area, and the area's FILLER X(59) holds whatever the
            // row held. A record-bearing arm with no image would leave a raw display with nothing to write
            // but a re-encoding of the fields, which blanks that span - so the type refuses the shape.
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
            // Hand-written offset arithmetic, deliberately (AAP §0.10.2 B11): each field is cut out of
            // the stored row by absolute offset and width and compared with what the repository decoded,
            // so a regression names the span that moved instead of reporting one opaque inequality. The
            // seven spans of app/cpy/CVACT02Y.cpy are contiguous from zero and sum to 150:
            //   CARD-NUM            X(16) @  0   CARD-ACCT-ID        9(11) @ 16
            //   CARD-CVV-CD         9(03) @ 27   CARD-EMBOSSED-NAME  X(50) @ 30
            //   CARD-EXPIRAION-DATE X(10) @ 80   CARD-ACTIVE-STATUS  X(01) @ 90
            //   FILLER              X(59) @ 91
            // CARD-EXPIRAION-DATE keeps the copybook's misspelling; renaming it would break the
            // field-for-field diffing the parity gate performs (AAP §0.8.9).
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
            // AAP §0.10.2 B8. CobolCharsetConfig publishes IBM037 for the EBCDIC datasets under
            // app/data/EBCDIC and US-ASCII for these text fixtures, and the repository takes the active
            // code page as a constructor argument selected by bean name - so the platform default is
            // consulted nowhere. This test is the proof that the choice is load-bearing: the same bytes
            // decoded as EBCDIC are NOT the same record, so a decode that silently inherited a default
            // could not pass by luck.
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

            // The same span, read as EBCDIC: not the same characters. The comparison is on the decoded
            // text rather than on a decode that might legitimately reject the bytes.
            assertThat(new String(stored, CardRecord.CARD_EMBOSSED_NAME_OFFSET,
                    CardRecord.CARD_EMBOSSED_NAME_LENGTH, EBCDIC_CHARSET))
                    .as("US-ASCII and IBM037 disagree about these bytes, which is why the code page is "
                            + "always stated")
                    .isNotEqualTo(underTheConfiguredCodePage.cardEmbossedName());
            assertThat(EBCDIC_CHARSET.name()).isEqualTo("IBM037");
            assertThat(FIXTURE_CHARSET.name()).isEqualTo("US-ASCII");
        }

        /**
         * One span of a stored row, cut out by absolute offset and width.
         *
         * @param row    the stored record image
         * @param offset the span's zero-based offset, from {@code app/cpy/CVACT02Y.cpy}
         * @param length the span's declared width
         * @return the span, untrimmed
         */
        private String span(String row, int offset, int length) {
            return row.substring(offset, offset + length);
        }
    }

    // =============================================================================================
    // Gate G47: the FileStatus vocabulary, call site by call site. Each COBOL paragraph carries its own
    // EVALUATE, so coverage is owed per call site rather than per method family - 9100-GETCARD-BYACCTCARD
    // and 9150-GETCARD-BYACCT are separate branch sets even though both are "a read".
    // =============================================================================================

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

            // '10' END-OF-FILE and '22' DUPLICATE are unreachable HERE, and that is a property of the
            // access path rather than a gap in this test. A keyed read has no cursor, so it can never run
            // off the end of one - the end of file belongs to the browse, where forwardEndOfFile drives
            // it. And CARD-NUM is CARDDAT's unique primary key, so a key cannot select two records: the
            // duplicate arm belongs to the alternate-index path, where nonUniqueAlternateKey drives it.
            // Asserting the absence is what keeps that reasoning honest rather than assumed.
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

            // COCRDUPC:1488-1492 is a two-way test, so the failure arm has one meaning - the update did
            // not happen - and it carries the response pair rather than a batch status, because there is
            // no batch FILE STATUS for "invalid request".
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
            // The values themselves matter: a batch program tests a two-character FILE STATUS literally,
            // so '00', '10', '22' and '23' are data, not an enumeration this module is free to renumber.
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

    // =============================================================================================
    // Gate G44: what this repository does NOT need. A migration whose brief forbids schema change has
    // to be able to show that it made none, and the absence of a thing is only demonstrable by looking
    // for it.
    // =============================================================================================

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
            // COCRDUPC's own concurrency check is 9300-CHECK-CHANGE-IN-REC at :1453-1457: it re-reads the
            // record and compares it field by field. That check is preserved - in the update service,
            // where the business logic belongs - and it is emphatically NOT replaced by a version column,
            // because adding a column is a schema change and the brief forbids one.
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

            // JdbcTemplate.execute(String) is the route a DDL statement would take. It is never taken.
            verify(jdbcTemplate, never()).execute(anyString());
        }

        @Test
        @DisplayName("neither the repository nor the record carries a persistence or ORM annotation")
        void noEntityAnnotationIsPresent() {
            // No JPA, no Hibernate, no entity model: the record layout is the copybook's and the access
            // is JdbcTemplate's. An ORM would impose an entity and table model that does not exist, which
            // is exactly what the brief excludes.
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

    // =============================================================================================
    // The batch DD view - the DD-mapping finding.
    //
    // A batch reader reads through the DD its JCL binds, and it resolves that DD through its own view
    // of the catalogue. This repository resolved CARDDAT from the global catalogue at construction, so
    // a job needs a way to say "address MY binding" or it validates one name and reads another.
    // =============================================================================================

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
            // The shipped configuration: CARDFILE and CARDDAT are two names for one dataset. Returning
            // the same instance keeps the resolved statements, so the common case costs nothing and
            // performs no second describe.
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
            // The statements the re-bound instance sends address ITS relation. Carrying the source's
            // resolved statements over would have sent them at the wrong dataset - which is the whole
            // defect, expressed in SQL.
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
            // The codec carries the code page and the MOVE semantics every key is built with. A re-bound
            // instance that had its own would pad or truncate differently from the one it came from.
            CardRepository rebound = repository.addressing(
                    cardDatBinding("CARDDEMO.BATCH.CARDDATA.KSDS", CardRecord.RECORD_LENGTH),
                    CardRepository.BATCH_DD_NAME);

            assertThat(rebound.describeAlternateIndexStatement())
                    .isEqualTo(repository.describeAlternateIndexStatement());
        }
    }

    // =================================================================================================
    // Synthesised read outcomes. A CardReadResult carries the decoded record AND the bytes it was
    // decoded from, because DISPLAY CARD-RECORD (app/cbl/CBACT02C.cbl:78) writes the record area and the
    // area's FILLER X(59) holds whatever the row held. A test constructing an outcome has no row, so the
    // image it supplies is the one a row of exactly this record would carry - which is what these two
    // helpers state, once, rather than at every call site.
    // =================================================================================================

    /**
     * The normal arm over a synthesised row of this record.
     *
     * @param record the record the row would carry
     * @return the outcome, carrying the record and the image a row of it would hold
     */
    private static CardReadResult cardRead(CardRecord record) {
        return CardReadResult.normal(record, record.encodeToImage(StandardCharsets.US_ASCII));
    }

    /**
     * The duplicate-key arm over a synthesised row of this record.
     *
     * @param record the first record sharing the alternate key
     * @return the outcome, carrying the record and the image a row of it would hold
     */
    private static CardReadResult cardReadDuplicate(CardRecord record) {
        return CardReadResult.duplicateKey(record, record.encodeToImage(StandardCharsets.US_ASCII));
    }

    /**
     * The 150-character image a row of this record would hold.
     *
     * @param record the record
     * @return its encoded image
     */
    private static String storedImage(CardRecord record) {
        return record.encodeToImage(StandardCharsets.US_ASCII);
    }
}
